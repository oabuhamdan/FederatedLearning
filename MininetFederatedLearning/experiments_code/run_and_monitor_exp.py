import json
import os
import random
import shutil
import threading
import time


class ExperimentRunner:
    def __init__(self, exp_name, **kwargs):
        random.seed(43)  # Consistent random choices
        self.exp_name = exp_name
        self.rounds = kwargs.get('rounds', 10)
        self.batch_size = kwargs.get('batch_size', 16)
        self.dataset = kwargs.get('dataset', 'cifar10')
        self.model = kwargs.get('model', 'mobilenet_large')
        self.epochs = kwargs.get('epochs', 1)
        self.learning_rate = kwargs.get('learning_rate', 0.001)
        self.fl_server = kwargs.get('fl_server', None)
        self.fl_clients = kwargs.get('fl_clients', [])
        self.zmq = kwargs.get('zmq', False)
        self.bg_clients = kwargs.get('bg_clients', [])
        auto_start_bg = kwargs.get('auto_start_bg', True)
        self.bg_traffic_generator = BGTrafficGenerator(self.bg_clients, self.exp_name, auto_start_bg) if self.bg_clients else None

    def __str__(self):
        info = {
            'exp_name': self.exp_name,
            'rounds': self.rounds,
            'batch_size': self.batch_size,
            'dataset': self.dataset,
            'model': self.model,
            'epochs': self.epochs,
            'learning_rate': self.learning_rate,
            'zmq': self.zmq,
        }
        return str(info)

    def __enter__(self):
        self.log_path = f"logs/{self.exp_name}/"
        os.makedirs("../" + self.log_path, exist_ok=True)
        with open(f"../{self.log_path}/exp_info.txt", "w") as exp_info:
            exp_info.write(json.dumps(self.__str__()))
        print("Starting Experiment")
        os.system(f"./monitor_netem.sh ../{self.log_path} > /dev/null 2>&1 &")

    def __exit__(self, exc_type, exc_value, traceback):
        if exc_type is not None:
            print(f"Exception type: {exc_type}")
            print(f"Exception value: {exc_value}")
            print(f"Traceback: {traceback}")
            shutil.rmtree(f"../logs/{self.exp_name}/", ignore_errors=True)
        self.stop_experiment()
        os.system("pkill -f 'monitor_netem.sh'")
        if self.bg_clients:
            self.bg_traffic_generator.stop()
        return True  # If False, the exception will be re-raised

    def start_experiment(self):
        if self.bg_clients:
            self.bg_traffic_generator.start()

        self.run_fl_training()

    def stop_experiment(self):
        print("Calling Stop Experiment")
        for host in self.fl_clients:
            host.cmd("pkill -f 'network_stats.sh'")
            host.cmd("pkill -f 'Flower'")
        self.fl_server.cmd("pkill -f 'Flower'")
        self.fl_server.cmd("pkill -f 'network_stats.sh'")
        self.fl_server.waiting = False

    def run_fl_training(self):

        serv_inf = self.fl_server.defaultIntf()
        server_addr = str(self.fl_server.params['ip'])

        self.fl_server.cmd(f"./network_stats.sh {serv_inf} 1 {self.log_path}/server_network.csv > /dev/null 2>&1 &")
        self.fl_server.sendCmd(
            f"source venv/bin/activate && python FlowerServer.py --dataset {self.dataset}"
            f" --num-clients {len(self.fl_clients)} --rounds {self.rounds} --server-address {server_addr}"
            f" --epochs {self.epochs} --batch-size {self.batch_size} --log-path {self.log_path} {'--zmq' if self.zmq else ''}"
        )
        for i, sta in enumerate(self.fl_clients):
            cmd = (f"python FlowerClient.py --cid {i} --dataset {self.dataset} --log-path {self.log_path} "
                   f"--server-address {server_addr} {'--zmq' if self.zmq else ''}")
            sta.cmd(f"source venv/bin/activate && {cmd} > /dev/null 2>&1 &")
            inf = sta.defaultIntf()
            sta.cmd(f"./network_stats.sh {inf} 1 {self.log_path}/flclient_{i}_network.csv > /dev/null 2>&1 &")
        print(f"Discarded: {self.fl_server.read(1024)}")  #
        self.fl_server.waitOutput(verbose=True)


class BGTrafficGenerator:
    def __init__(self, bg_hosts, log_dir_name, auto_start_bg):
        self.bg_hosts = bg_hosts
        self.auto_start_bg = auto_start_bg
        self.log_path = f"logs/{log_dir_name}/iperf_logs"
        self.stop_event = threading.Event()
        self._traffic_thread = None

    def _gen_traffic(self):
        i = 0 # used for round-robin
        with open(f"../{self.log_path}/traffic_logs.txt", "w") as traffic_log:
            random.shuffle(self.bg_hosts["bgcore"])
            random.shuffle(self.bg_hosts["bgagg"])
            # bw_choices = list(range(60, 90, 10))
            # t_choices = list(range(25, 100, 25))
            flow_sizes = list(range(50, 200, 25))
            while not self.stop_event.is_set():
                src, dst = random.choice([("bgcore", "bgagg"), ("bgagg", "bgcore")])
                src_host = self.bg_hosts[src][i % len(self.bg_hosts[src])]
                dst_host = self.bg_hosts[dst][i % len(self.bg_hosts[dst])]

                # bandwidth = random.choice(bw_choices)  # Mbps
                # duration = random.choice(t_choices)  # duration
                flow_size = random.choice(flow_sizes)
                port = random.randint(49152, 65535)

                # Log the flow details
                traffic_log.write(
                    f"From {src_host.name} to {dst_host.name}"
                    f" with flow size {flow_size}MB\n"
                )
                traffic_log.flush()

                # Start the server on the destination host
                dst_host.cmd(f'iperf3 -s --daemon --one-off -p {port} > /dev/null &')
                time.sleep(1)

                # Start the client on the source host
                src_host.cmd(f'iperf3 -c {dst_host.IP()} -n {flow_size}M -p {port} '
                             f'--logfile {self.log_path}/{src_host.name}_{dst_host.name}_{port}_logs.txt > /dev/null &')

                # Delay before generating the next flow
                i += 1
                time.sleep(2)

    def monitor_network(self):
        for bghost in self.bg_hosts["bgcore"] + self.bg_hosts["bgagg"]:
            inf = bghost.defaultIntf()
            bghost.cmd(f"./network_stats.sh {inf} 1 {self.log_path}/{bghost.name}_network.csv > /dev/null 2>&1 &")

    def start(self):
        if self.auto_start_bg and not self._traffic_thread:
            self.stop_event.clear()
            os.makedirs("../" + self.log_path, exist_ok=True)
            self._traffic_thread = threading.Thread(target=self._gen_traffic, daemon=True)
            self._traffic_thread.start()
            self.monitor_network()
            print("~ Traffic generation started ~")

    def stop(self):
        self.stop_event.set()

        if self._traffic_thread:
            self._traffic_thread.join(timeout=1)
            self._traffic_thread = None

        [host.cmd("pkill -f 'iperf3'") for host in self.bg_hosts["bgcore"] + self.bg_hosts["bgagg"]]
        [host.cmd("pkill -f 'network_stats.sh'") for host in self.bg_hosts["bgcore"] + self.bg_hosts["bgagg"]]

        print("~ Traffic generation has stopped, and all iperf3 processes have been terminated ~")
