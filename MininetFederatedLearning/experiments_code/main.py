import json
import os
import re
import time

from mininet.cli import CLI
from mininet.log import setLogLevel, info
from mininet.node import OVSSwitch, RemoteController
from mininet.link import TCLink

from topohub_bg_gen import BGTrafficGenerator
from topohub_topology import MyTopo2
from topology import MyTopo
from plotter import MininetPlotter
from run_and_monitor_exp import ExperimentRunner
from mininet.net import Containernet
import topohub.mininet


class MyMininet(Containernet):
    def __init__(self, *args, **kwargs):
        self.qos = kwargs.pop('qos', False)
        Containernet.__init__(self, *args, **kwargs)

    def get_fl_hosts(self):
        fl_server = self.get("flserver")
        fl_clients = [host for host in self.hosts if "flclient" in host.name]
        return fl_server, fl_clients

    def get_bg_hosts(self):
        bg_clients = {host.name:host for host in self.hosts if "bgclient" in host.name}
        return bg_clients

    """
    NOTES:
    1- Token escort packets to be dequeued.
    2- Root paren Q is where token generation happens
    3- Tokens are generated at root parent's max-limit rate.
    4- HTB only works for TX data (existing interface)
    5- Parent will try to satisfy the rate, then based on priority, it'll try to satisfy the max-rate. 
    6- Sum of rate values of children shouldn't exceed max-rate of parent.
    7- Child's max limit should exceed max-rate of parent.
    8-  
    """

    def create_qos(self):
        print("Creating QoS ...")
        common = "create queue other_config:priority={prio}"
        cmd = (
            f"ovs-vsctl -- set port {{intf}} qos=@newqos "
            f"-- --id=@newqos create qos type=linux-htb  other-config:max-rate={{max}}"
            f" queues=0=@q0,1=@q1 "
            f"-- --id=@q0 {common.format(prio=2)} other-config:max-rate={{max}} other-config:min-rate=1000000 "
            f"-- --id=@q1 {common.format(prio=1)} other-config:max-rate={{max}} other-config:min-rate={{min}}"
            # high prio
        )

        intf_list = [intf for sw in self.switches for intf in sw.intfs.values() if re.match(".*-eth.*", intf.name)]
        for intf in intf_list:
            max_rate = 1_000_000_000 if intf.name.startswith("cs") else 100_000_000
            min_rate = max_rate//2 #- 100_000_000 # rate - 10mbps
            os.system(cmd.format(intf=intf.name, max=max_rate, min=min_rate))

    @staticmethod
    def delete_qos():
        print("Deleting QoS ...")
        os.system("ovs-vsctl -- --all destroy QoS -- --all destroy Queue")

    def __enter__(self):
        info('*** Start Network\n')
        self.start()
        if self.qos:
            self.create_qos()

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
    topo_creator = MyTopo2(fl_clients_number=10, topo_size=15, variation=9)
    net1 = MyMininet(topo=topo_creator, switch=OVSSwitch, link=TCLink, controller=controller, qos=False)

    (fl_server, fl_clients), bg_clients = net1.get_fl_hosts(), net1.get_bg_hosts()

    exp_name = f"[withBW]_flowsched_agfc_50rounds_{time.strftime('%m%d_%H%M%S')}"
    bg_gen = BGTrafficGenerator(bg_clients, topo_creator.nodes_data, topo_creator.links_data, exp_name)
    exp_runner1 = ExperimentRunner(exp_name, fl_server=fl_server,fl_clients=fl_clients, bg_clients=bg_clients,
                                   rounds=50, batch_size=32, zmq=True)
    with net1:
        CLI(net1)
        with exp_runner1:
            with open(f"logs/{exp_runner1.exp_name}/exp_info.txt", "w") as exp_info:
                exp_info.write(json.dumps(str(net1.topo)))
                exp_info.write("\n")
                exp_info.write(json.dumps(str(exp_runner1)))
            bg_gen.start()
            info(f'*** Starting {exp_runner1.exp_name} - Turn FlowSched Now\n')
            time.sleep(5)
            exp_runner1.start_experiment()
            bg_gen.stop()
        CLI(net1)


if __name__ == '__main__':
    setLogLevel('info')
    start()
