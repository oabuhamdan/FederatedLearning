import time
import os

from mininet.cli import CLI
from mininet.link import TCLink
from mininet.log import setLogLevel, info
from mininet.net import Mininet
from mininet.node import OVSSwitch, RemoteController
from numpy import random as np_random
import random as py_random
import threading

DATASET = "cifar10"
ROUNDS = 5
EPOCHS = 1
BATCH_SIZE = 32


class MyMininet(Mininet):
    def __init__(self, *args, **kwargs):
        Mininet.__init__(self, *args, **kwargs)
        self.fl_server = None
        self.fl_clients = []
        self.bg_hosts = []

    def setup_exp(self, fl_server, fl_clients, bg_hosts):
        self.fl_server = fl_server
        self.fl_clients = fl_clients
        self.bg_hosts = bg_hosts

    def start_experiment(self, log_dir, bg_traffic):
        if bg_traffic:
            threading.Thread(target=self.start_bg_traffic, args=(log_dir,), daemon=True).start()
        self.run_exp(self.fl_clients, self.fl_server, log_dir)
        [host.cmd("pkill -f 'iperf3'") for host in self.fl_clients]
        self.fl_server.cmd("pkill -f 'network_stats.sh'")
        [client.cmd("pkill -f 'network_stats.sh'") for client in self.fl_clients]
        self.fl_server.cmd("killall bash")

    def run_exp(self, stations, server, log_dir):
        log_path = f"logs/{log_dir}/"
        os.makedirs(log_path, exist_ok=True)

        serv_inf = server.defaultIntf()
        server_addr = str(server.params['ip']) + ":8080"

        server.cmd(f"./network_stats.sh {serv_inf} 1 {log_path}/server_network.csv > /dev/null 2>&1 &")
        server.sendCmd(
            f"source .venv/bin/activate && python FlowerServer.py --dataset {DATASET}"
            f" --num-clients {len(stations)} --rounds {ROUNDS} --server-address {server_addr}"
            f" --epochs {EPOCHS} --batch-size {BATCH_SIZE} --log-path {log_path}"
        )
        for i, sta in enumerate(stations):
            cmd = (f"python FlowerClient.py --cid {i} --dataset {DATASET} --log-path {log_path} "
                   f"--server-address {server_addr}")
            sta.cmd(f"source .venv/bin/activate && {cmd} > /dev/null 2>&1 &")
            inf = sta.defaultIntf()
            sta.cmd(f"./network_stats.sh {inf} 1 {log_path}/client_{i}_network.csv > /dev/null 2>&1 &")

        server.waitOutput(verbose=True)

        server.cmd("pkill -f 'network_stats.sh'")
        [sta.cmd("pkill -f 'network_stats.sh'") for sta in stations]

    def start_bg_traffic(self, log_dir):
        py_random.seed(43)
        np_random.seed(43)

        log_path = f"logs/{log_dir}/iperf_logs"
        os.makedirs(log_path, exist_ok=True)
        time.sleep(10)
        for i in range(10):
            src, dst = py_random.sample(self.bg_hosts, 2)
            traffic_size = int(np_random.normal(500, 100))
            bandwidth = int(np_random.normal(20, 5))
            port = py_random.randint(9090, 9990)

            dst.cmd(f'iperf3 -s --daemon --one-off -p {port} > /dev/null &')
            time.sleep(1)
            src.cmd(f'iperf3 -c {dst.IP()} -b {bandwidth}M -n {traffic_size}M -p {port} '
                    f'--logfile {log_path}/{port}_logs.txt > /dev/null &')
            time.sleep(10)
        print("~ Generated 30 flows ~")


def int_to_mac(n):
    return ''.join(f'{(n >> (i * 8)) & 0xff:02x}' for i in range(5, -1, -1))


def create_topology():
    net = MyMininet(link=TCLink, switch=OVSSwitch, controller=RemoteController)

    # Create the controller
    info('*** Adding controller\n')
    net.addController('c0', controller=RemoteController, ip='127.0.0.1', port=6653)

    scount = 1
    switch_config = dict(protocols="OpenFlow13", )
    core_switches = [net.addSwitch(f'c{i + 1}', dpid=str(scount + i), **switch_config) for i in range(2)]
    scount += len(core_switches)
    aggregation_switches = [net.addSwitch(f'a{i + 1}', dpid=str(scount + i), **switch_config) for i in range(4)]
    scount += len(aggregation_switches)
    edge_switches = [net.addSwitch(f'e{i + 1}', dpid=str(scount + i), **switch_config) for i in range(8)]

    # Add FL server
    fl_server = net.addHost('fl_server', ip="10.0.0.100", mac="00:00:00:00:00:AA")

    # Add FL clients
    hcount = 1
    fl_clients = [net.addHost(f'flclient{i + 1}', mac=int_to_mac(hcount + i)) for i in range(10)]

    hcount = len(fl_clients)
    # Add background (non-FL) clients
    bg_clients = [net.addHost(f'bgclient{i + 1}', mac="aa" + int_to_mac(hcount + i)[-10:]) for i in range(5)]

    # Connect FL server to core switches
    net.addLink(fl_server, net.getNodeByName("c1"), bw=1000)

    # Connect core switches to each other
    net.addLink(core_switches[0], core_switches[1], bw=100)

    # Connect core switches to aggregation switches
    for c_switch in core_switches:
        for a_switch in aggregation_switches:
            net.addLink(c_switch, a_switch, bw=100)

    # Connect aggregation switches to edge switches
    for i, a_switch in enumerate(aggregation_switches):
        for j in range(2):
            net.addLink(a_switch, edge_switches[i * 2 + j], bw=100)

    # Connect FL clients to edge switches
    for i, client in enumerate(fl_clients):
        switch = edge_switches[i % len(edge_switches)]
        net.addLink(client, switch, bw=100)

    # Connect background clients to edge switches
    for i, client in enumerate(bg_clients):
        switch = edge_switches[i % len(edge_switches)]
        net.addLink(client, switch, bw=100)

    info('*** Starting network\n')
    net.start()
    net.setup_exp(fl_server, fl_clients, bg_clients)

    info('*** Running CLI\n')
    CLI(net)

    info('*** Stopping network\n')
    net.stop()


if __name__ == '__main__':
    setLogLevel('info')
    create_topology()
