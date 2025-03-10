import json
import os
import shutil


class ExperimentRunner:
    def __init__(self, exp_name, **kwargs):
        self.exp_name = exp_name
        self.fl_server = kwargs.get('fl_server', None)
        self.fl_clients = kwargs.get('fl_clients', [])
        self.onos_server = kwargs.get('onos_server', 'localhost')
        self.num_clients = kwargs.get('num_clients', 10)
        self.num_rounds = kwargs.get('num_rounds', 10)

    def __enter__(self):
        self.log_path = f"logs/{self.exp_name}/"
        os.makedirs(self.log_path, exist_ok=True)
        with open(f"{self.log_path}/exp_info.txt", "w") as exp_info:
            exp_info.write(json.dumps(self.__str__()))
        print("Starting Experiment")

    def __exit__(self, exc_type, exc_value, traceback):
        if os.path.isdir("/home/osama/flow_sched_logs/"):
            shutil.move("/home/osama/flow_sched_logs/", f"logs/{self.exp_name}/")
        self.stop_experiment()
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
            host.sendCmd("pkill -9 -f 'network_stats.sh'")
            host.waiting = False
            host.sendCmd("pkill -9 -f 'Flower|flwr|flower'")
            host.waiting = False
        self.fl_server.sendCmd("pkill -9 -f 'Flower|flwr|flower'")
        self.fl_server.waiting = False
        self.fl_server.sendCmd("pkill -9 -f 'network_stats.sh'")
        self.fl_server.waiting = False


    def start_experiment(self):
        venv = "source venv/bin/activate &&"
        devnull = "/dev/null 2>&1 &"
        serv_inf = self.fl_server.defaultIntf()
        server_addr = str(self.fl_server.params['ip'])
        env_var = "FLWR_LOG_LEVEL=INFO GRPC_VERBOSITY=ERROR GRPC_TRACE=connectivity_state,client_channel,channel,http,call_error,timer"

        self.fl_server.cmd(f"ip route add {self.onos_server}/32 via 172.17.0.1")
        self.fl_server.cmd(f"./network_stats.sh {serv_inf} 5 {self.log_path}/server_network.csv > {devnull}")
        self.fl_server.cmd(f"{venv} {env_var} flower-superlink --isolation process --insecure > {devnull}")
        self.fl_server.sendCmd(f"{venv} {env_var} flwr-serverapp --insecure --run-once")

        for i, client in enumerate(self.fl_clients):
            cmd_supernode = f"flower-supernode --insecure --isolation process --superlink='{server_addr}:9092' --node-config='cid={i + 1}'"
            cmd_clientapp = f"flwr-clientapp --insecure"
            client.cmd(f"{venv} {env_var} {cmd_supernode} > {devnull}")
            client.cmd(f"{venv} {env_var} {cmd_clientapp} > {devnull}")
            inf = client.defaultIntf()
            client.cmd(f"./network_stats.sh {inf} 5 {self.log_path}/flclient{i}_network.csv > {devnull}")
        print(f"Discarded: {self.fl_server.read(1024)}")
        self.fl_server.waitOutput(verbose=True)
