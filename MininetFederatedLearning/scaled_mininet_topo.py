import time
import csv
from mininet.cli import CLI
from mininet.link import TCLink
from mininet.log import setLogLevel, info
from mininet.net import Mininet
from mininet.node import DefaultController, OVSBridge, CPULimitedHost
import re


def collect_total_traffic_for_host(total_time, net, output_file):
    """
    Collects the total traffic statistics for a specific host and writes them to a CSV file.

    :param host: Mininet host object
    :param output_file: Name of the CSV file to save the data
    """
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
                [f"{host.name}", tx_bytes, tx_packets, tx_dropped, rx_bytes, rx_packets, rx_dropped])

        # Write the total experiment time
        csv_writer.writerow([f'Total Experiment Time', total_time])


NUM_HOSTS = 50
DATASET = "cifar10"
ROUNDS = 5
EPOCHS = 100
BATCH_SIZE = 16


def create_network():
    net = Mininet(controller=DefaultController, link=TCLink, switch=OVSBridge)

    # Create the controller
    info('*** Adding controller\n')
    net.addController('c0')

    # Create the central switch (server switch)
    info('*** Adding central switch\n')
    s_central = net.addSwitch('s0')

    # Create other switches
    info('*** Adding switches\n')
    switches = []
    num_switches = NUM_HOSTS // 25
    for j in range(num_switches):
        switch = net.addSwitch(f's{j + 1}')
        switches.append(switch)

    # Create hosts and connect each group of 25 hosts to a switch
    info('*** Adding hosts\n')
    hosts = []
    for i in range(0, NUM_HOSTS):
        host = net.addHost(f'h{i + 1}', ip=f'10.0.0.{i + 1}/24')
        hosts.append(host)
        # Connect each host to its respective switch
        net.addLink(host, switches[i // 25])

    # Create server
    server = net.addHost('server', ip='10.0.0.100/24')

    # Add links between switches and central switch
    info('*** Creating links\n')
    for switch in switches:
        net.addLink(switch, s_central)

    # Connect the server to the central switch
    net.addLink(server, s_central)

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
    tik = time.time()
    server.cmd("source ../.venv/bin/activate")
    server.cmd(
        f"python FlowerServer.py --dataset {DATASET} --num-clients {NUM_HOSTS} --round {ROUNDS}"
        f" --epochs {EPOCHS} --batch-size {BATCH_SIZE} &")
    for i in range(0, NUM_HOSTS - 1):
        host = net.getNodeByName(f'h{i + 1}')
        host.cmd("source ../.venv/bin/activate")
        host.cmd(f"python FlowerClient.py --cid {i} --dataset {DATASET} &")

    host = net.getNodeByName(f'h{NUM_HOSTS}')
    host.cmd("source ../.venv/bin/activate")
    host.cmd(f"python FlowerClient.py --cid {NUM_HOSTS - 1} --dataset {DATASET}", verbose=True)

    total_time = time.time() - tik
    collect_total_traffic_for_host(total_time, net, output_file=f'logs/{DATASET}/total_traffic_stats.csv')
    # Stop the network
    info('*** Stopping network\n')
    net.stop()


if __name__ == '__main__':
    setLogLevel('info')
    create_network()
