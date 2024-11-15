import os
import random
import shutil
import threading
import time


class ExperimentRunner:
    def __init__(self, exp_name, fl_server, fl_clients, bg_clients, **kwargs):
        self.exp_name = exp_name
        self.rounds = kwargs.get('rounds', 10)
        self.batch_size = kwargs.get('batch_size', 16)
        self.dataset = kwargs.get('dataset', 'cifar10')
        self.model = kwargs.get('model', 'mobilenet_large')
        self.epochs = kwargs.get('epochs', 1)
        self.learning_rate = kwargs.get('learning_rate', 0.001)
        self.fl_server = fl_server
        self.fl_clients = fl_clients
        self.zmq = kwargs.get('zmq', False)
        self.log_dir_name = (
            f"{self.exp_name}_{self.model}_{self.rounds}rounds_{len(self.fl_clients)}hosts"
            f"_with{'' if bg_clients else 'out'}_bg_with_batch{self.batch_size}"
        )
        self.bg_gen = BGTrafficGenerator(bg_clients, self.log_dir_name) if bg_clients else None

    def __enter__(self):
        print("Starting Experiment")

    def __exit__(self, exc_type, exc_value, traceback):
        if exc_type is not None:
            print(f"Exception type: {exc_type}")
            print(f"Exception value: {exc_value}")
            print(f"Traceback: {traceback}")
            shutil.rmtree(f"../logs/{self.log_dir_name}/", ignore_errors=True)
        self.stop_experiment()
        self.bg_gen.stop()
        return True  # If False, the exception will be re-raised

    def start_experiment(self):
        if self.bg_gen:
            self.bg_gen.start()

        self.run_fl_training()

        if self.bg_gen:
            self.bg_gen.stop()

    def stop_experiment(self):
        print("Calling Stop Experiment")
        for host in self.fl_clients:
            host.cmd("pkill -f 'network_stats.sh'")
            host.cmd("pkill -f 'Flower'")

        self.fl_server.waiting = False
        self.fl_server.cmd("pkill -f 'network_stats.sh'")
        self.fl_server.cmd("pkill -f 'Flower'")

    def run_fl_training(self):
        log_path = f"logs/{self.log_dir_name}/"
        os.makedirs("../" + log_path, exist_ok=True)
        serv_inf = self.fl_server.defaultIntf()
        server_addr = str(self.fl_server.params['ip'])

        self.fl_server.cmd(f"./network_stats.sh {serv_inf} 1 {log_path}/server_network.csv > /dev/null 2>&1 &")
        self.fl_server.sendCmd(
            f"source venv/bin/activate && python FlowerServer.py --dataset {self.dataset}"
            f" --num-clients {len(self.fl_clients)} --rounds {self.rounds} --server-address {server_addr}"
            f" --epochs {self.epochs} --batch-size {self.batch_size} --log-path {log_path} {'--zmq' if self.zmq else ''}"
        )
        for i, sta in enumerate(self.fl_clients):
            cmd = (f"python FlowerClient.py --cid {i} --dataset {self.dataset} --log-path {log_path} "
                   f"--server-address {server_addr} {'--zmq' if self.zmq else ''}")
            sta.cmd(f"source venv/bin/activate && {cmd} > /dev/null 2>&1 &")
            inf = sta.defaultIntf()
            sta.cmd(f"./network_stats.sh {inf} 1 {log_path}/client_{i}_network.csv > /dev/null 2>&1 &")
        print(f"Discarded: {self.fl_server.read(1024)}")  #
        self.fl_server.waitOutput(verbose=True)


class BGTrafficGenerator:
    def __init__(self, bg_hosts, log_dir_name):
        self.bg_hosts = bg_hosts
        self.log_path = f"logs/{log_dir_name}/iperf_logs"
        self.stop_event = threading.Event()
        self._traffic_thread = None

    @staticmethod
    def get_switch(host):
        return host.defaultIntf().link.intf2.node

    def _gen_traffic(self):
        random.seed(43)  # Consistent random choices

        with open(f"../{self.log_path}/traffic_logs.txt", "w") as traffic_log:
            while not self.stop_event.is_set():
                # Determine if the next flow should be an 'elephant' flow
                src_host = random.choice(self.bg_hosts)
                # chose a dst that doesn't share the same switch with the src
                dst_host = random.choice(
                    [host for host in self.bg_hosts if self.get_switch(host) != self.get_switch(src_host)]
                )

                bandwidth = random.choice([40, 50, 60])  # Mbps
                duration = random.choice([10, 20, 30])  # duration
                port = random.randint(49152, 65535)

                # Log the flow details
                traffic_log.write(
                    f"From {src_host.name} to {dst_host.name}"
                    f" with Bandwidth {bandwidth}Mbps and Duration {duration} seconds\n"
                )
                traffic_log.flush()

                # Start the server on the destination host
                dst_host.cmd(f'iperf3 -s --daemon --one-off -p {port} > /dev/null &')
                time.sleep(1)

                # Start the client on the source host
                src_host.cmd(f'iperf3 -c {dst_host.IP()} -b {bandwidth}M -t {duration} -p {port} '
                             f'--logfile {self.log_path}/{src_host.name}_{dst_host.name}_{port}_logs.txt > /dev/null &')

                # Delay before generating the next flow
                time.sleep(2)

    def start(self):
        if not self._traffic_thread:
            self.stop_event.clear()
            os.makedirs("../" + self.log_path, exist_ok=True)
            self._traffic_thread = threading.Thread(target=self._gen_traffic, daemon=True)
            self._traffic_thread.start()
            print("~ Traffic generation started ~")

    def stop(self):
        self.stop_event.set()

        if self._traffic_thread:
            self._traffic_thread.join()
            self._traffic_thread = None

        for host in self.bg_hosts:
            host.cmd("pkill -f 'iperf3'")

        print("~ Traffic generation has stopped, and all iperf3 processes have been terminated ~")
