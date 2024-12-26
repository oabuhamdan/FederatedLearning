import concurrent.futures
import csv
import threading
import time
import timeit
import traceback
from enum import Enum
from logging import INFO, ERROR
from typing import Optional, Union

import zmq
from flwr.common import FitIns, FitRes, Parameters, Scalar, GetPropertiesIns
from flwr.common.logger import log
from flwr.server import Server, History, SimpleClientManager
from flwr.server.client_proxy import ClientProxy
from flwr.server.criterion import Criterion
from flwr.server.server import FitResultsAndFailures, _handle_finished_future_after_fit


class ZMQHandler:
    class MessageType(Enum):
        UPDATE_DIRECTORY = 1
        SERVER_TO_CLIENTS = 2
        CLIENT_TO_SERVER = 3

    def __init__(self, my_server_address, onos_server_address):
        self.my_server_address = my_server_address
        self.onos_server_address = onos_server_address
        self.snd_socket = None
        self.recv_socket = None

        self.init_zmq()
        threading.Thread(target=self.zmq_bridge, args=(self.snd_socket, self.recv_socket,),
                         daemon=True).start()

    def init_zmq(self):
        context = zmq.Context()
        self.recv_socket = context.socket(zmq.PULL)
        self.recv_socket.bind(f"tcp://{self.my_server_address}:5555")
        self.snd_socket = context.socket(zmq.PUSH)
        self.snd_socket.connect(f"tcp://{self.onos_server_address}:5555")

    def send_data_to_server(self, message_type: MessageType, message):
        model_update = {"sender_id": "server", "message_type": message_type.value, "message": message,
                        "time_ms": round(time.time() * 1000)}
        self.snd_socket.send_json(model_update)

    @staticmethod
    def zmq_bridge(snd_socket, recv_socket):
        while True:
            snd_socket.send(recv_socket.recv())


def fit_client(
        client: ClientProxy, ins: FitIns, timeout: Optional[float], group_id: int
) -> tuple[ClientProxy, FitRes]:
    """Refine parameters on a single client."""
    client_round_start_time = timeit.default_timer()
    fit_res = None
    try:
        fit_res = client.fit(ins, timeout=timeout, group_id=group_id)
    except Exception as exc:
        tb_str = traceback.format_exc()
        log(ERROR, "Failed to fit client %s to server %s", exc.args, tb_str)
    client_round_finish_time = timeit.default_timer()
    fit_res.metrics["client_round_start_time"] = client_round_start_time
    fit_res.metrics["client_round_finish_time"] = client_round_finish_time
    return client, fit_res


def fit_clients(
        client_instructions: list[tuple[ClientProxy, FitIns]],
        max_workers: Optional[int],
        timeout: Optional[float],
        group_id: int,
) -> FitResultsAndFailures:
    """Refine parameters concurrently on all selected clients."""
    with concurrent.futures.ThreadPoolExecutor(max_workers=max_workers) as executor:
        submitted_fs = {
            executor.submit(fit_client, client_proxy, ins, timeout, group_id)
            for client_proxy, ins in client_instructions
        }
        finished_fs, _ = concurrent.futures.wait(
            fs=submitted_fs,
            timeout=None,  # Handled in the respective communication stack
        )

    # Gather results
    results: list[tuple[ClientProxy, FitRes]] = []
    failures: list[Union[tuple[ClientProxy, FitRes], BaseException]] = []
    for future in finished_fs:
        _handle_finished_future_after_fit(
            future=future, results=results, failures=failures
        )
    return results, failures


class MyServer(Server):
    def __init__(self, *, client_manager, strategy, zmq, my_server_address, onos_server_address, log_path):
        super().__init__(client_manager=client_manager, strategy=strategy)
        self.server_log = csv.writer(open(f'{log_path}/fl_task_times.csv', 'w'), dialect='unix')
        self.zmq = zmq
        if self.zmq:
            self.zmq_handler = ZMQHandler(my_server_address, onos_server_address)
            client_manager.set_zmq_handler(self.zmq_handler)

    def fit_round(
            self,
            server_round: int,
            timeout: Optional[float],
    ):
        """Perform a single round of federated averaging."""
        # Get clients and their respective instructions from strategy
        client_instructions = self.strategy.configure_fit(
            server_round=server_round,
            parameters=self.parameters,
            client_manager=self._client_manager,
        )

        if not client_instructions:
            log(INFO, "configure_fit: no clients selected, cancel")
            return None
        log(
            INFO,
            "configure_fit: strategy sampled %s clients (out of %s)",
            len(client_instructions),
            self._client_manager.num_available(),
        )

        # Collect clients_info
        if self.zmq:
            round_clients = [client_proxy.cid for client_proxy, _ in client_instructions]
            self.zmq_handler.send_data_to_server(ZMQHandler.MessageType.SERVER_TO_CLIENTS, round_clients)

        results, failures = fit_clients(
            client_instructions=client_instructions,
            max_workers=self.max_workers,
            timeout=timeout,
            group_id=server_round,
        )
        log(
            INFO,
            "aggregate_fit: received %s results and %s failures",
            len(results),
            len(failures),
        )

        # Aggregate training results
        aggregated_result: tuple[
            Optional[Parameters],
            dict[str, Scalar],
        ] = self.strategy.aggregate_fit(server_round, results, failures)

        parameters_aggregated, metrics_aggregated = aggregated_result
        return parameters_aggregated, metrics_aggregated, (results, failures)

    def fit(self, num_rounds: int, timeout: Optional[float]) -> tuple[History, float]:
        """Run federated averaging for a number of rounds."""
        history = History()

        # Initialize parameters
        log(INFO, "[INIT]")
        self.parameters = self._get_initial_parameters(server_round=0, timeout=timeout)
        log(INFO, "Starting evaluation of initial global parameters")
        res = self.strategy.evaluate(0, parameters=self.parameters)
        if res is not None:
            log(
                INFO,
                "initial parameters (loss, other metrics): %s, %s",
                res[0],
                res[1],
            )
            history.add_loss_centralized(server_round=0, loss=res[0])
            history.add_metrics_centralized(server_round=0, metrics=res[1])
        else:
            log(INFO, "Evaluation returned no results (`None`)")

        # Run federated learning for num_rounds
        start_time = timeit.default_timer()
        self.server_log.writerow(['current_round', 'client_id', 'round_time', 'server_to_client_time',
                                  'computing_time', 'client_to_server_time'])
        for current_round in range(1, num_rounds + 1):
            round_start_time = timeit.default_timer()
            log(INFO, "")
            log(INFO, "[ROUND %s]", current_round)
            res_fit = self.fit_round(
                server_round=current_round,
                timeout=timeout,
            )
            if res_fit is not None:
                parameters_prime, fit_metrics, (results, failures) = res_fit  # fit_metrics_aggregated
                if parameters_prime:
                    self.parameters = parameters_prime
                history.add_metrics_distributed_fit(
                    server_round=current_round, metrics=fit_metrics
                )
                self.log_csv_metrics(current_round, round_start_time, results)

            # Evaluate model using strategy implementation
            res_cen = self.strategy.evaluate(current_round, parameters=self.parameters)
            if res_cen is not None:
                loss_cen, metrics_cen = res_cen
                log(
                    INFO,
                    "fit progress: (%s, %s, %s, %s)",
                    current_round,
                    loss_cen,
                    metrics_cen,
                    timeit.default_timer() - start_time,
                )
                history.add_loss_centralized(server_round=current_round, loss=loss_cen)
                history.add_metrics_centralized(
                    server_round=current_round, metrics=metrics_cen
                )

            # Evaluate model on a sample of available clients
            res_fed = self.evaluate_round(server_round=current_round, timeout=timeout)
            if res_fed is not None:
                loss_fed, evaluate_metrics_fed, _ = res_fed
                if loss_fed is not None:
                    history.add_loss_distributed(
                        server_round=current_round, loss=loss_fed
                    )
                    history.add_metrics_distributed(
                        server_round=current_round, metrics=evaluate_metrics_fed
                    )

        # Bookkeeping
        end_time = timeit.default_timer()
        elapsed = end_time - start_time
        return history, elapsed

    def log_csv_metrics(self, current_round, round_start_time, results):
        for result in results:
            metrics = result[1].metrics
            client_id = metrics['client']
            server_to_client_time = metrics["computing_start_time"] - metrics["client_round_start_time"]
            computing_time = metrics["computing_finish_time"] - metrics["computing_start_time"]
            round_time = metrics["client_round_finish_time"] - metrics["client_round_start_time"]
            client_to_server_time = metrics["client_round_finish_time"] - metrics["computing_finish_time"]
            self.server_log.writerow([current_round, client_id, round_time, server_to_client_time,
                                      computing_time, client_to_server_time])


class MyClient:
    def __init__(self, client_id, client_cid, ip, mac):
        self.client_id = client_id
        self.client_cid = client_cid
        self.ip = ip
        self.mac = mac

    def json(self):
        return {"client_id": str(self.client_id), "client_cid": str(self.client_cid), "ip": self.ip, "mac": self.mac}


class MySimpleClientManager(SimpleClientManager):
    def __init__(self) -> None:
        super().__init__()
        self.clients_info: dict[str, MyClient] = {}
        self.zmq_handler = None

    def set_zmq_handler(self, zmq_handler):
        self.zmq_handler = zmq_handler

    def unregister(self, client: ClientProxy) -> None:
        if client.cid in self.clients:
            del self.clients[client.cid]
            del self.clients_info[client.cid]

            with self._cv:
                self._cv.notify_all()

    def sample(
            self,
            num_clients: int,
            min_num_clients: Optional[int] = None,
            criterion: Optional[Criterion] = None,
    ) -> list[ClientProxy]:
        sampled_clients = super().sample(num_clients, min_num_clients, criterion)
        for client in filter(lambda c: c.cid not in self.clients_info, sampled_clients):
            self.add_client_info(client)
        return sampled_clients

    def add_client_info(self, client: ClientProxy):
        properties = client.get_properties(GetPropertiesIns({}), 10, 0).properties
        if properties:
            ip = properties.get("ip", "0.0.0.0")
            mac = properties.get("mac", "00:00:00:00:00:00")
            client_id = client.cid
            client_cid = properties.get("cid", "0")
            my_client = MyClient(client_id, client_cid, ip, mac)
            self.clients_info[client_id] = my_client

            if self.zmq_handler:
                log(
                    INFO,
                    "Sending this data to server %s",
                    my_client.json(),
                )
                self.zmq_handler.send_data_to_server(ZMQHandler.MessageType.UPDATE_DIRECTORY, my_client.json())
