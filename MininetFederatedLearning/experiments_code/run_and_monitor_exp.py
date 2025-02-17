import json
import os
import shutil


class ExperimentRunner:
    def __init__(self, exp_name, **kwargs):
        self.exp_name = exp_name
        self.rounds = kwargs.get('rounds', 10)
        self.batch_size = kwargs.get('batch_size', 16)
        self.dataset = kwargs.get('dataset', 'cifar10')
        self.model = kwargs.get('model', 'mobilenet_large')
        self.epochs = kwargs.get('epochs', 1)
        self.learning_rate = kwargs.get('learning_rate', 0.001)
        self.fl_server = kwargs.get('fl_server', None)
        self.fl_clients = kwargs.get('fl_clients', [])
        self.onos_server = kwargs.get('onos_server', 'localhost')
        self.zmq = kwargs.get('zmq', False)

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
        os.makedirs(self.log_path, exist_ok=True)
        with open(f"{self.log_path}/exp_info.txt", "w") as exp_info:
            exp_info.write(json.dumps(self.__str__()))
        print("Starting Experiment")
        # os.system(f"./monitor_netem.sh {self.log_path} > /dev/null 2>&1 &")

    def __exit__(self, exc_type, exc_value, traceback):
        if os.path.isdir("/home/osama/flow_sched_logs/"):
            shutil.move("/home/osama/flow_sched_logs/", f"logs/{self.exp_name}/")
        self.stop_experiment()
        # os.system("pkill -f 'monitor_netem.sh'")
        if exc_type:
            print(f"Exception type: {exc_type}")
            print(f"Exception value: {exc_value}")
            print(f"Traceback: {traceback}")
            shutil.rmtree(f"logs/{self.exp_name}/")
        return True  # If False, the exception will be re-raised

    def stop_experiment(self):
        print("Calling Stop Experiment")
        self.fl_server.waiting = False
        for host in self.fl_clients:
            host.waiting = False
            host.sendCmd("pkill -9 -f 'iperf3'")
            host.waiting = False
            host.sendCmd("pkill -9 -f 'network_stats.sh'")
            host.waiting = False
            host.sendCmd("pkill -9 -f 'Flower'")
            host.waiting = False
        self.fl_server.cmd("pkill -9 -f 'Flower'")
        self.fl_server.cmd("pkill -9 -f 'network_stats.sh'")
        self.fl_server.waiting = False

    def start_experiment(self):
        serv_inf = self.fl_server.defaultIntf()
        server_addr = str(self.fl_server.params['ip'])
        self.fl_server.cmd(f"ip route add {self.onos_server}/32 via 172.17.0.1")
        self.fl_server.cmd(f"./network_stats.sh {serv_inf} 5 {self.log_path}/server_network.csv > /dev/null 2>&1 &")
        self.fl_server.sendCmd(
            f"source venv/bin/activate && python FlowerServer.py --dataset {self.dataset}"
            f" --num-clients {len(self.fl_clients)} --rounds {self.rounds} --fl-server {server_addr}"
            f" --onos-server {self.onos_server} --epochs {self.epochs} --batch-size {self.batch_size}"
            f" --log-path {self.log_path} {'--zmq' if self.zmq else ''}"
        )
        for i, sta in enumerate(self.fl_clients):
            cmd = (f"python FlowerClient.py --cid {i + 1} --dataset {self.dataset} --log-path {self.log_path}"
                   f" --fl-server {server_addr} {'--zmq' if self.zmq else ''}")
            sta.cmd(f"source venv/bin/activate && {cmd} > /dev/null  2>&1 &")
            inf = sta.defaultIntf()
            sta.cmd(f"./network_stats.sh {inf} 5 {self.log_path}/flclient{i}_network.csv > /dev/null 2>&1 &")
        print(f"Discarded: {self.fl_server.read(1024)}")  #
        self.fl_server.waitOutput(verbose=True)
