#!/usr/bin/python
import os
import time

from mininet.log import setLogLevel, info
from mininet.node import RemoteController
from mn_wifi.net import Mininet_wifi
from mn_wifi.node import Station, OVSKernelAP
from mn_wifi.cli import CLI
from mn_wifi.link import wmediumd, mesh
from mn_wifi.wmediumdConnector import interference
from subprocess import call

DATASET = "cifar10"
ROUNDS = 5
EPOCHS = 5
BATCH_SIZE = 16
LOG_PATH = f"logs/wifi_exp_{DATASET}_{time.strftime('%H_%M')}"


def myNetwork():
    controller = RemoteController('c0', ip='172.17.0.2', port=6653)
    net = Mininet_wifi(controller=controller, link=wmediumd, wmediumd_mode=interference, autoSetMacs=True)

    ap_configs = dict(protocols="OpenFlow13", channel=1, mode='g', cls=OVSKernelAP, range=500, wlans=2)
    ap1 = net.addAccessPoint('ap1', ssid='mesh-ssid', position='672.0,389.0,0', **ap_configs)
    ap2 = net.addAccessPoint('ap2', ssid='mesh-ssid', position='1120.0,391.0,0', **ap_configs)
    ap3 = net.addAccessPoint('ap3', ssid='mesh-ssid', position='894.0,745.0,0', **ap_configs)
    aps = [ap1, ap2, ap3]
    info('*** Add hosts/stations\n')

    sta1 = net.addStation('sta1', ip='10.0.0.1', position='503.0,-11.0,0', range=120)
    sta2 = net.addStation('sta2', ip='10.0.0.2', position='228.0,444.0,0', range=120)
    sta3 = net.addStation('sta3', ip='10.0.0.3', position='1364.0,22.0,0', range=120)
    sta4 = net.addStation('sta4', ip='10.0.0.4', position='1554.0,532.0,0', range=120)
    sta5 = net.addStation('sta5', ip='10.0.0.5', position='1218.0,1070.0,0', range=120)
    sta6 = net.addStation('sta6', ip='10.0.0.6', position='609.0,1085.0,0', range=120)

    info("*** Configuring Propagation Model\n")
    net.setPropagationModel(model="logDistance", exp=3)

    info("*** Configuring wifi nodes\n")
    net.configureWifiNodes()

    for ap in aps:
        mesh_intf = f"{ap}-wlan2"
        ap.cmd(f"ip link set {mesh_intf} down")
        ap.cmd(f"iw dev {mesh_intf} set type mp")
        ap.cmd(f"ip link set {mesh_intf} up")
        ap.cmd(f'iw dev {mesh_intf} mesh join mesh-ssid')
        # ap.cmd(f"ip addr add 10.0.0.9{i}/24 dev {mesh_intf}")

    net.plotGraph(min_x=-500, min_y=-500, max_x=2000, max_y=2000)

    info('*** Starting network\n')
    net.build()
    info('*** Starting controllers\n')

    controller.start()

    info('*** Starting switches/APs\n')
    for ap in aps:
        ap.start([controller])

    # CLI(net)
    net.pingAll()
    total_time = run_exp([sta2, sta3, sta4, sta5, sta6], sta1)
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
