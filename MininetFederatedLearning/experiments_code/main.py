import time

from mininet.cli import CLI
from mininet.log import setLogLevel, info
from mininet.net import Mininet
from mininet.node import OVSSwitch, RemoteController
from mininet.link import TCLink

from plotter import MininetPlotter
from run_and_monitor_exp import ExperimentRunner
from topology import MyTopo
from mininet.net import Containernet


class MyMininet(Containernet):
    def __init__(self, *args, **kwargs):
        Mininet.__init__(self, *args, **kwargs)

    def get_fl_hosts(self):
        fl_server = self.get("flserver")
        fl_clients = [self.get(f"flclient{i + 1}") for i in range(self.topo.no_fl_clients)]
        bg_clients = [self.get(f"bgclient{i + 1}") for i in range(self.topo.no_bg_clients)]
        return fl_server, fl_clients, bg_clients

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
    controller = RemoteController('c0', ip='11.66.33.46', port=6653)
    topo_creator = MyTopo(fl_clients=10, bg_clients=10, number_of_levels=3, no_core_switches=2,
                          min_switches_per_level=2, max_switches_per_level=3, start_level_for_aggregation=0)
    net1 = MyMininet(topo=topo_creator, switch=OVSSwitch, link=TCLink, controller=controller)
    MininetPlotter(net1).plot()

    with net1:
        # CLI(net1)
        print("Starting Net1 ....")
        [net1.ping(hosts=[net1.hosts[0], host2]) for host2 in net1.hosts]
        [net1.ping(hosts=[net1.hosts[1], host2]) for host2 in net1.hosts]
        time.sleep(300)
        fl_server, fl_clients, bg_clients = net1.get_fl_hosts()
        exp_runner1 = ExperimentRunner("fwd_exp1", fl_server, fl_clients, bg_clients, rounds=5, batch_size=32)
        with exp_runner1:
            info(f'*** Starting {exp_runner1.exp_name}\n')
            exp_runner1.start_experiment()


if __name__ == '__main__':
    setLogLevel('info')
    start()
