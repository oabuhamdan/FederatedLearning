import argparse
import json
import time
import tomli
import os

from mininet.cli import CLI
from mininet.log import setLogLevel, info
from mininet.node import OVSSwitch, RemoteController
from mininet.link import TCLink

from topohub_bg_gen import BGTrafficGenerator
from topohub_topology import MyTopo2
from run_and_monitor_exp import ExperimentRunner
from mininet.net import Containernet


class MyMininet(Containernet):
    def __init__(self, **kwargs):
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
    topo_creator = MyTopo2(fl_clients_number=args.clients, topo_size=args.topo_size, variation=args.topo_variation)
    net1 = MyMininet(topo=topo_creator, switch=OVSSwitch, link=TCLink, controller=controller)

    (fl_server, fl_clients), bg_clients = net1.get_fl_hosts(), net1.get_bg_hosts()

    exp_name = f"{args.name}_r{args.rounds}_g{args.topo_size}_v{args.topo_variation}_{time.strftime('%m%d_%H%M%S')}"
    bg_gen = BGTrafficGenerator(bg_clients, topo_creator.nodes_data, topo_creator.links_data, exp_name)
    exp_runner = ExperimentRunner(exp_name, fl_server=fl_server, fl_clients=fl_clients,
                                  num_clients=args.clients, num_rounds=args.rounds, onos_server=args.onos)

    with net1:
        print("Exp Name:", exp_name)
        CLI(net1)
        with exp_runner:
            bg_gen.start()
            info(f'*** Starting Experiment - Turn FlowSched Now\n')
            exp_runner.start_experiment()
            bg_gen.stop()
    os.system("pkill -9 bazel")


if __name__ == '__main__':
    setLogLevel('info')
    parser = argparse.ArgumentParser()
    parser.add_argument("-n", "--name", type=str, default="test")
    parser.add_argument("-o", "--onos", type=str, default="localhost")
    parser.add_argument("-r", "--rounds", type=int, default=10)
    parser.add_argument("-c", "--clients", type=int, default=5)
    parser.add_argument("-s", "--topo-size", type=int, default=10)
    parser.add_argument("-v", "--topo-variation", type=int, default=5)
    args = parser.parse_args()
    start()
