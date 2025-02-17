import argparse
import json
import time

from mininet.cli import CLI
from mininet.log import setLogLevel, info
from mininet.node import OVSSwitch, RemoteController
from mininet.link import TCLink

from topohub_bg_gen import BGTrafficGenerator
from topohub_topology import MyTopo2
from run_and_monitor_exp import ExperimentRunner
from mininet.net import Containernet


class MyMininet(Containernet):
    def __init__(self,  **kwargs):
        Containernet.__init__(self, **kwargs)

    def get_fl_hosts(self):
        fl_server = self.get("flserver")
        fl_clients = [host for host in self.hosts if "flclient" in host.name]
        return fl_server, fl_clients

    def get_bg_hosts(self):
        bg_clients = {host.name: host for host in self.hosts if "bgclient" in host.name}
        return bg_clients

    def __enter__(self):
        info('*** Start Network\n')
        self.start()

    def __exit__(self, exc_type, exc_value, traceback):
        if exc_type is not None:
            print(f"Exception type: {exc_type}")
            print(f"Exception value: {exc_value}")
            print(f"Traceback: {traceback}")
        else:
            print("Exiting the context without exception")
        info('*** Stopping network\n')
        self.stop()
        return True  # If False, the exception will be re-raised


def start():
    controller = RemoteController('c0', ip=args.onos, port=6653)
    topo_creator = MyTopo2(fl_clients_number=args.clients, topo_size=args.size, variation=args.variation)
    net1 = MyMininet(topo=topo_creator, switch=OVSSwitch, link=TCLink, controller=controller)

    (fl_server, fl_clients), bg_clients = net1.get_fl_hosts(), net1.get_bg_hosts()

    exp_name = f"{args.name}_r{args.rounds}_g{args.size}_v{args.variation}_{time.strftime('%m%d_%H%M%S')}"
    bg_gen = BGTrafficGenerator(bg_clients, topo_creator.nodes_data, topo_creator.links_data, exp_name)
    exp_runner = ExperimentRunner(exp_name, fl_server=fl_server, fl_clients=fl_clients, bg_clients=bg_clients,
                                  rounds=args.rounds, batch_size=32, zmq=args.zmq, onos_server=args.onos)
    with net1:
        CLI(net1)
        with exp_runner:
            with open(f"logs/{exp_runner.exp_name}/exp_info.txt", "w") as exp_info:
                exp_info.write(json.dumps(str(net1.topo)))
                exp_info.write("\n")
                exp_info.write(json.dumps(str(exp_runner)))
            bg_gen.start()
            info(f'*** Starting {exp_runner.exp_name} - Turn FlowSched Now\n')
            time.sleep(5)
            exp_runner.start_experiment()
            bg_gen.stop()
        CLI(net1)


if __name__ == '__main__':
    setLogLevel('info')
    parser = argparse.ArgumentParser()
    parser.add_argument("-r", "--rounds", type=int, default=10)
    parser.add_argument("-c", "--clients", type=int, default=10)
    parser.add_argument("-s", "--size", type=int, default=10)
    parser.add_argument("-v", "--variation", type=int, default=5)
    parser.add_argument("-n", "--name", type=str, default="test")
    parser.add_argument("-o", "--onos", type=str, default="localhost")
    parser.add_argument("--zmq", action='store_true')
    args = parser.parse_args()
    start()
