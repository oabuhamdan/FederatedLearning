#!/usr/bin/python
import os
import time

from mininet.cli import CLI
from mininet.link import TCLink
from mininet.log import setLogLevel, info
from mininet.node import RemoteController
from mn_wifi.link import wmediumd, _4address
from mn_wifi.net import Mininet_wifi
from mn_wifi.node import OVSKernelAP
from mn_wifi.wmediumdConnector import interference

DATASET = "cifar10"
ROUNDS = 5
EPOCHS = 1
BATCH_SIZE = 16
LOG_PATH = f"logs/wifi_exp_{DATASET}_{time.strftime('%H_%M')}"


def myNetwork():
    net = Mininet_wifi(link=wmediumd, wmediumd_mode=interference, config4addr=True,
                       ipBase='10.0.0.0/8', mode='g', channel='1')

    info('*** Adding controller\n')
    controller = net.addController(name='c0', controller=RemoteController, ip='127.0.0.1', protocol='tcp', port=6653)

    info('*** Add switches/APs\n')
    ap_configs = dict(protocols="OpenFlow13", cls=OVSKernelAP, range=500, wlans=3)

    info('*** Add switches/APs\n')
    ap1 = net.addAccessPoint('ap1', ssid='ap1-ssid', dpid="1", position='761.0,510.0,0', **ap_configs)
    ap2 = net.addAccessPoint('ap2', ssid='ap2-ssid', dpid="2", position='1105.0,500.0,0', **ap_configs)
    ap3 = net.addAccessPoint('ap3', ssid='ap3-ssid', dpid="3", position='1085.0,902.0,0', **ap_configs)
    ap4 = net.addAccessPoint('ap4', ssid='ap4-ssid', dpid="4", position='744.0,910.0,0', **ap_configs)
    aps = [ap1, ap2, ap3, ap4]

    info('*** Add hosts/stations\n')
    server = net.addHost('server', ip='10.0.0.100', mac="00:00:00:00:00:AA")
    sta1 = net.addStation('sta1', ip='10.0.0.1', position='1287.0,1061.0,0', range=150, mac="00:00:00:00:00:01")
    sta2 = net.addStation('sta2', ip='10.0.0.2', position='1292.0,389.0,0', range=150, mac="00:00:00:00:00:02")
    sta3 = net.addStation('sta3', ip='10.0.0.3', position='548.0,1019.0,0', range=150, mac="00:00:00:00:00:03")
    sta4 = net.addStation('sta4', ip='10.0.0.4', position='1357.0,988.0,0', range=150, mac="00:00:00:00:00:04")
    stas = [sta1, sta2, sta3, sta4]

    info("*** Configuring Propagation Model\n")
    net.setPropagationModel(model="logDistance", exp=3)

    info("*** Configuring wifi nodes\n")
    net.configureWifiNodes()

    net.addLink(ap1, ap2, cls=_4address, port1=1, port2=1)
    net.addLink(ap2, ap3, cls=_4address, port1=2, port2=1)
    net.addLink(ap3, ap4, cls=_4address, port1=2, port2=1)
    net.addLink(ap4, ap1, cls=_4address, port1=2, port2=2)

    net.addLink(server, ap1, cls=TCLink, bw=1000)
    net.addLink(sta1, ap1)
    net.addLink(sta2, ap2)
    net.addLink(sta3, ap3)
    net.addLink(sta4, ap4)

    info('*** Add links.yaml\n')

    net.plotGraph(min_x=-200, min_y=-200, max_x=1500, max_y=1500)

    info('*** Starting network\n')
    net.build()
    info('*** Starting controllers\n')
    for controller in net.controllers:
        controller.start()

    info('*** Starting switches/APs\n')
    for ap in aps:
        ap.start([controller])

    cli = CLI(net)
    net.pingAll()
    info('*** *Start App Now ****')
    time.sleep(30)
    total_time = run_exp(stas, server)
    print(total_time)
    net.stop()


def run_exp(stations, server):
    os.makedirs(LOG_PATH, exist_ok=True)
    tik = time.time()
    serv_inf = server.defaultIntf()
    server_addr = str(server.params['ip']) + ":8080"
    server.cmd(f"./network_stats.sh {serv_inf} 1 {LOG_PATH}/server_network.csv > /dev/null 2>&1 &")
    server.sendCmd(
        f"source ../.venv/bin/activate && python FlowerServer.py --dataset {DATASET}"
        f" --num-clients {len(stations)} --rounds {ROUNDS} --server-address {server_addr}"
        f" --epochs {EPOCHS} --batch-size {BATCH_SIZE} --log-path {LOG_PATH}"
    )
    for i, sta in enumerate(stations):
        cmd = (f"python FlowerClient.py --cid {i} --dataset {DATASET} --log-path {LOG_PATH} "
               f"--server-address {server_addr}")
        sta.cmd(f"source ../.venv/bin/activate && {cmd} > /dev/null 2>&1 &")
        inf = sta.defaultIntf()
        sta.cmd(f"./network_stats.sh {inf} 1 {LOG_PATH}/client_{i}_network.csv > /dev/null 2>&1 &")
    server.waitOutput(verbose=True)
    total_time = time.time() - tik
    return total_time


if __name__ == '__main__':
    setLogLevel('info')
    myNetwork()
