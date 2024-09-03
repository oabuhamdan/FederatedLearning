import csv
import re
import time
import os
from mininet.link import TCLink
from mininet.log import setLogLevel, info
from mininet.net import Mininet
from mininet.node import DefaultController, OVSBridge


def collect_total_traffic_for_host(total_time, net, output_file):
    with open(output_file, 'w', newline='') as csvfile:
        csv_writer = csv.writer(csvfile)

        # Write the header row
        csv_writer.writerow(
            ['Host', 'TotalSentBytes', 'TotalSentPackets', "TotalTXDrops", 'TotalReceivedBytes', 'TotalReceivedPackets',
             "TotalRXDrops"])

        for host in net.hosts:
            iface = host.defaultIntf()
            output = host.cmd(f'ifconfig {iface}')

            # Extract received packets, bytes, and dropped packets using regex
            rx_match = re.search(r"RX packets (\d+) +bytes (\d+).+?dropped (\d+)", output, re.DOTALL)
            rx_packets = int(rx_match.group(1)) if rx_match else None
            rx_bytes = int(rx_match.group(2)) if rx_match else None
            rx_dropped = int(rx_match.group(3)) if rx_match else None

            # Extract sent (transmitted) packets, bytes, and dropped packets using regex
            tx_match = re.search(r"TX packets (\d+) +bytes (\d+).+?dropped (\d+)", output, re.DOTALL)
            tx_packets = int(tx_match.group(1)) if tx_match else None
            tx_bytes = int(tx_match.group(2)) if tx_match else None
            tx_dropped = int(tx_match.group(3)) if tx_match else None

            # Write the data row for each host
            csv_writer.writerow(
                [f"{host.name}", tx_bytes, tx_packets, tx_dropped, rx_bytes, rx_packets, rx_dropped]
            )

        # Write the total experiment time
        csv_writer.writerow([f'Total Experiment Time', total_time])


NUM_HOSTS = 5
DATASET = "cifar10"
ROUNDS = 10
EPOCHS = 10
BATCH_SIZE = 16
LOG_PATH = f"logs/exp_{DATASET}_{time.strftime('%H_%M')}"
os.makedirs(LOG_PATH, exist_ok=True)

def create_network():
    net = Mininet(controller=DefaultController, link=TCLink, switch=OVSBridge)

    # Create the controller
    info('*** Adding controller\n')
    net.addController('c0')

    # Create the central switch (server switch)
    info('*** Adding switch s1 \n')
    switch = net.addSwitch('s1')

    # Create hosts and connect each group of 25 hosts to a switch
    info('*** Adding hosts\n')
    hosts = []
    for i in range(0, NUM_HOSTS):
        host = net.addHost(f'h{i + 1}', ip=f'10.0.0.{i + 1}/24')
        hosts.append(host)
        net.addLink(host, switch)

    # Create server
    server = net.addHost('server', ip='10.0.0.100/24')

    # Connect the server to the central switch
    net.addLink(server, switch)

    # Start the network
    info('*** Starting network\n')
    net.start()

    # Test connectivity
    info('*** Testing connectivity\n')
    net.pingAll()
    # Dynamically change bandwidth to 5 Mbps
    # h1_inf = net.get('h1').defaultIntf()
    # h1.cmd('tc qdisc add dev {} root netem rate 2mbit'.format(h1_intf))  # Change to 2 Mbps
    # Start the CLI
    info('*** Running CLI\n')
    # CLI(net)
    total_time = run_exp(net, server)
    # collect_total_traffic_for_host(total_time=total_time, net=net,
    #                                output_file=f'logs/{DATASET}/total_traffic_stats.csv')
    # Stop the network
    info('*** Stopping network\n')
    net.stop()


def run_exp(net, server):
    tik = time.time()
    serv_inf = server.defaultIntf()
    server.cmd(f"./network_stats.sh {serv_inf} 60 {LOG_PATH}/server_network.csv > /dev/null 2>&1 &")
    server.sendCmd(
        f"source ../.venv/bin/activate && python FlowerServer.py --dataset {DATASET}"
        f" --num-clients {NUM_HOSTS} --round {ROUNDS}"
        f" --epochs {EPOCHS} --batch-size {BATCH_SIZE} --log-path {LOG_PATH}"
    )
    for i in range(0, NUM_HOSTS):
        host = net.getNodeByName(f'h{i + 1}')
        cmd = f"python FlowerClient.py --cid {i} --dataset {DATASET} --log-path {LOG_PATH}"
        host.cmd(f"source ../.venv/bin/activate && {cmd} > /dev/null 2>&1 &")
        inf = host.defaultIntf()
        host.cmd(f"./network_stats.sh {inf} 60 {LOG_PATH}/client_{i}_network.csv > /dev/null 2>&1 &")
        print(f"Host {host.name}")
    server.waitOutput(verbose=True)
    total_time = time.time() - tik
    return total_time


if __name__ == '__main__':
    setLogLevel('info')
    create_network()
