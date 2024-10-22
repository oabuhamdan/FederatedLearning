import time
import os
from mininet.cli import CLI
from mininet.log import setLogLevel, info
from mininet.net import Containernet
from mininet.node import OVSSwitch, RemoteController
from mininet.link import TCLink
from numpy import random as np_random
import random as py_random
import threading
import math

DATASET = "cifar10"
ROUNDS = 3
EPOCHS = 1
BATCH_SIZE = 16
containernet_kwargs = {
    "volumes": [
        "/home/osama/PycharmProjects/FederatedLearning/MininetFederatedLearning/logs:/app/logs",
        "/home/osama/PycharmProjects/FederatedLearning/MininetFederatedLearning/data:/app/data",
    ],
    "dimage": "fl_mininet_image:latest",
    "network_mode": "none",
}
static_hosts_limits = {
    "mem_limit": f"750m",
    "memswap_limit": f"1g",
    "cpu_period": 100000,
}


def host_specific_limitation(num_fl_clients):
    cpu_limit = py_random.choices([0.3, 0.5, 0.8, 1], k=num_fl_clients)
    limits = [
        {
            "cpu_quota": int(static_hosts_limits["cpu_period"] * cpu_limit[i]),
        } for i in range(num_fl_clients)
    ]
    return limits


class MyMininet(Containernet):
    def __init__(self, *args, **kwargs):
        Containernet.__init__(self, *args, **kwargs)
        self.fl_server = None
        self.fl_clients = []
        self.bg_hosts = []
        self.stop_event = threading.Event()  # Create a stop event

    def setup_exp(self, fl_server, fl_clients, bg_hosts):
        self.fl_server = fl_server
        self.fl_clients = fl_clients
        self.bg_hosts = bg_hosts

    def start_experiment(self, exp_name, bg_traffic):
        log_dir = f"{exp_name}_{ROUNDS}rounds_{len(self.fl_clients)}hosts_with{'' if bg_traffic else 'out'}_bg"
        self.stop_event.clear()
        if bg_traffic:
            thread = threading.Thread(target=self.start_bg_traffic, args=(log_dir,), daemon=True)
            thread.start()
        self.run_exp(self.fl_clients, self.fl_server, log_dir)
        self.stop_event.set()
        [host.cmd("pkill -f 'iperf3'") for host in self.bg_hosts]
        [host.cmd("pkill -f 'network_stats.sh'") for host in self.fl_clients]

    def run_exp(self, stations, server, log_dir):
        log_path = f"logs/{log_dir}/"
        os.makedirs(log_path, exist_ok=True)

        serv_inf = server.defaultIntf()
        server_addr = str(server.params['ip']) + ":8080"

        server.cmd(f"./network_stats.sh {serv_inf} 1 {log_path}/server_network.csv > /dev/null 2>&1 &")
        server.sendCmd(
            f"source venv/bin/activate && python FlowerServer.py --dataset {DATASET}"
            f" --num-clients {len(stations)} --rounds {ROUNDS} --server-address {server_addr}"
            f" --epochs {EPOCHS} --batch-size {BATCH_SIZE} --log-path {log_path}"
        )
        for i, sta in enumerate(stations):
            cmd = (f"python FlowerClient.py --cid {i} --dataset {DATASET} --log-path {log_path} "
                   f"--server-address {server_addr}")
            sta.cmd(f"source venv/bin/activate && {cmd} > /dev/null 2>&1 &")
            inf = sta.defaultIntf()
            sta.cmd(f"./network_stats.sh {inf} 1 {log_path}/client_{i}_network.csv > /dev/null 2>&1 &")

        server.waitOutput(verbose=True)

    def start_bg_traffic(self, log_dir):
        py_random.seed(43)
        np_random.seed(43)
        log_path = f"logs/{log_dir}/iperf_logs"
        os.makedirs(log_path, exist_ok=True)
        iperf_logs = open(log_path + "/iperf_logs.txt", "w")
        time.sleep(10)
        for i in range(50):
            if self.stop_event.is_set():
                break
            src, dst = py_random.sample(self.bg_hosts, 2)
            traffic_size = int(np_random.normal(500, 100))
            bandwidth = int(np_random.normal(30, 10))
            port = py_random.randint(9090, 9990)
            iperf_logs.write(f"From {src} to {dst} with Traffic Size {traffic_size} and Bandwidth {bandwidth}\n")
            dst.cmd(f'iperf3 -s --daemon --one-off -p {port} > /dev/null &')
            time.sleep(1)
            src.cmd(f'iperf3 -c {dst.IP()} -b {bandwidth}M -n {traffic_size}M -p {port} '
                    f'--logfile {log_path}/{port}_logs.txt > /dev/null &')
            time.sleep(py_random.randint(1, 10))
        iperf_logs.close()
        print("~ Generated 50 flows ~")


def build_topology(net, core_count, agg_count, edge_count, fl_client_count, bg_client_count):
    def int_to_mac(n):
        return ''.join(f'{(n >> (i * 8)) & 0xff:02x}' for i in range(5, -1, -1))

    # Add switches
    scount = 1
    switch_config = dict(protocols="OpenFlow13", )
    core_switches = [net.addSwitch(f'c{i + 1}', dpid=str(scount + i), **switch_config) for i in range(core_count)]
    scount += len(core_switches)

    agg_switches = [net.addSwitch(f'a{i + 1}', dpid=str(scount + i), **switch_config) for i in range(agg_count)]
    scount += len(agg_switches)

    edge_switches = [net.addSwitch(f'e{i + 1}', dpid=str(scount + i), **switch_config) for i in range(edge_count)]

    fl_server = net.addHost(
        'fl_server', ip="10.0.0.100", mac="00:00:00:00:00:AA"
    )
    hos_specific_limitation = host_specific_limitation(fl_client_count)
    # Add FL clients
    fl_clients = [
        net.addDocker(f'flclient{i + 1}', mac=int_to_mac(i + 1), ip=f"10.0.0.{i + 1}",
                      **static_hosts_limits, **hos_specific_limitation[i], **containernet_kwargs)
        for i in range(fl_client_count)
    ]
    # Add background clients
    bg_clients = [
        net.addDocker(f'bgclient{i + 1}', mac="aa" + int_to_mac(fl_client_count + i + 1)[2:],
                      cpu_period=100000, cpu_quota=10000, mem_limit="128m", memswap_limit="128m",
                      ip=f"10.0.0.{i + 20}", **containernet_kwargs)
        for i in range(bg_client_count)
    ]

    # Connect FL server to all core switches for redundancy
    net.addLink(fl_server, core_switches[0], bw=1000)

    # Connect core switches to each other
    for i in range(len(core_switches)):
        for j in range(i + 1, len(core_switches)):
            net.addLink(core_switches[i], core_switches[j], bw=100, delay="1ms")

    # Connect core switches to aggregation switches
    for c_switch in core_switches:
        for a_switch in agg_switches:
            net.addLink(c_switch, a_switch, bw=100, delay="5ms")

    bg_edge_switches = edge_switches[0::2]
    fl_edge_switches = edge_switches[1::2]

    # Connect FL aggregation switches to FL edge switches
    connect_agg_to_edge(net, agg_switches, edge_switches)

    # Distribute FL clients across FL edge switches
    distribute_clients(net, fl_clients, fl_edge_switches)

    # Distribute BG clients across BG edge switches
    distribute_clients(net, bg_clients, bg_edge_switches)

    return fl_server, fl_clients, bg_clients


def connect_agg_to_edge(net, agg_switches, edge_switches):
    edges_per_agg = math.ceil(len(edge_switches) / len(agg_switches))
    for i, a_switch in enumerate(agg_switches):
        start = i * edges_per_agg
        end = min(start + edges_per_agg, len(edge_switches))
        for e_switch in edge_switches[start:end]:
            net.addLink(a_switch, e_switch, bw=100, delay="5ms")


def distribute_clients(net, clients, edge_switches):
    clients_per_edge = math.ceil(len(clients) / len(edge_switches))
    for i, client in enumerate(clients):
        edge_index = i // clients_per_edge
        edge_index = min(edge_index, len(edge_switches) - 1)  # Ensure we don't go out of bounds
        net.addLink(client, edge_switches[edge_index], bw=100, delay="5ms")


def calculate_topology_parameters(total_clients, fl_ratio=0.5, max_clients_per_edge=2, max_edges_per_agg=2):
    fl_client_count = int(total_clients * fl_ratio)
    bg_client_count = total_clients - fl_client_count

    # Calculate edge count
    edge_count = -(-total_clients // max_clients_per_edge)  # Ceiling division
    edge_count += edge_count % 2  # Ensure even number of edge switches

    # Calculate aggregation count
    agg_count = -(-edge_count // max_edges_per_agg)  # Ceiling division

    # Calculate core count
    core_count = 2 if edge_count <= 16 else 4

    return {
        "core_count": core_count,
        "agg_count": agg_count,
        "edge_count": edge_count,
        "fl_client_count": fl_client_count,
        "bg_client_count": bg_client_count
    }


def create_network():
    net = MyMininet(switch=OVSSwitch, controller=RemoteController, link=TCLink)
    device_counts = calculate_topology_parameters(20, 0.5, max_clients_per_edge=2)
    fl_server, fl_clients, bg_clients = build_topology(net, core_count=device_counts["core_count"],
                                                       agg_count=device_counts["agg_count"],
                                                       edge_count=device_counts["edge_count"],
                                                       fl_client_count=device_counts["fl_client_count"],
                                                       bg_client_count=device_counts["bg_client_count"])

    net.addController('c0', controller=RemoteController, ip='127.0.0.1', port=6653)

    net.start()
    # Assuming net.setup_exp is a custom method you've defined
    net.setup_exp(fl_server, fl_clients, bg_clients)

    info('*** Running CLI\n')
    CLI(net)

    info('*** Stopping network\n')
    net.stop()


if __name__ == '__main__':
    setLogLevel('info')
    create_network()
