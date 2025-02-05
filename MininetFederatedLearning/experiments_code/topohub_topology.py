import json

from urllib import request

import yaml
from mininet.node import Docker
from mininet.topo import Topo
from collections import Counter


class MyTopo2(Topo):
    def __init__(self, fl_clients_number, topo_type="gabriel", topo_size=10, variation=5, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.link_config = {"bw": 100, "delay": "2ms", "max_queue_size": 1000}

        self.switch_config = dict(protocols="OpenFlow13")
        self.topo_type = topo_type
        self.topo_size = topo_size
        self.variation = variation
        self.fl_clients_number = fl_clients_number
        self.nodes_data, self.links_data, = self.get_data(topo_type, topo_size, variation)
        self.fl_clients = None

        self.containernet_kwargs = {
            "volumes": [
                "/home/osama/PycharmProjects/FederatedLearning/MininetFederatedLearning/logs:/app/logs",
                "/home/osama/PycharmProjects/FederatedLearning/MininetFederatedLearning/data:/app/data",
            ],
            "dimage": "fl_mininet_image:latest",
            "cls": Docker,
            "sysctls": {"net.ipv4.tcp_congestion_control": "cubic"}
        }
        self.fl_host_limits = dict(mem_limit="1g", memswap_limit="2g", cpu_period=100000, cpu_quota=int(0.75 * 100000))
        self.bg_host_limits = dict(cpu_period=100000, cpu_quota=int(0.25 * 100000), mem_limit="256m",
                                   memswap_limit="1gb")

        self.build(config_loaded=True)

    def build(self, *args, **params):
        if not params.get("config_loaded", False):
            return

        self.create_nodes(self.nodes_data)
        self.create_links(self.nodes_data, self.links_data)
        fl_server = self.addHost('flserver', ip=f"10.0.0.100", mac="00:00:00:00:00:AA", **self.containernet_kwargs)
        node_max_degree = max(self.nodes_data.values(), key=lambda x: x['degree'])['node']
        self.addLink(node_max_degree, fl_server)

        self.create_bg_hosts()
        self.fl_clients = self.create_fl_hosts()

    def create_links(self, nodes, links_data):
        for link in links_data:
            src, dst = link["src"], link["dst"]
            self.addLink(nodes[src]['node'], nodes[dst]['node'], **self.link_config)

    def create_nodes(self, nodes_data):
        for nid in nodes_data:  # 10 nodes in the graph
            nodes_data[nid]['node'] = self.addSwitch(f'S{nid}', **self.switch_config, dpid=f"100{nid}")

    def create_bg_hosts(self):
        for i, node in self.nodes_data.items():
            node["bgclient"] = self.addHost(f'bgclient{i}', ip=f"10.0.0.{150 + i}", mac=self.int_to_mac(150 + i),
                                            **self.containernet_kwargs, **self.bg_host_limits)

        for node in self.nodes_data.values():
            self.addLink(node["bgclient"], node['node'])

    def create_fl_hosts(self):
        fl_hosts = [
            self.addHost(f'flclient{i}', ip=f"10.0.0.{50 + i}", mac=self.int_to_mac(50 + i),
                         **self.containernet_kwargs, **self.fl_host_limits) for i in range(self.fl_clients_number)
        ]

        nodes_sorted_by_degree = sorted(self.nodes_data.values(), key=lambda x: x["degree"])[:-2]
        for i, host in enumerate(fl_hosts):
            self.addLink(host, nodes_sorted_by_degree[i % len(nodes_sorted_by_degree)]['node'])

        return fl_hosts

    @staticmethod
    def int_to_mac(n):
        return ''.join(f'{(n >> (i * 8)) & 0xff:02x}' for i in range(5, -1, -1))

    @staticmethod
    def get_data(topo_type, topo_size, variation):
        url = f"https://www.topohub.org/data/{topo_type}/{topo_size}/{variation}.json"
        with request.urlopen(url) as response:
            data = json.load(response)
        links = data["links"]
        my_links = []
        for link in links:
            my_link = dict()
            my_link["src"] = link["source"]
            my_link["dst"] = link["target"]

            my_link["src-dst"] = int(link["ecmp_fwd"]["deg"] * 0.9)
            my_link["dst-src"] = int(link["ecmp_bwd"]["deg"] * 0.9)
            my_links.append(my_link)

        degree = Counter([link['src'] for link in my_links] + [link['dst'] for link in my_links])
        nodes = dict()
        for node in data["nodes"]:
            nodes[node["id"]] = dict(degree=degree[node["id"]])

        return nodes, my_links

    def __str__(self):
        info = {
            'topo_type': self.topo_type,
            'topo_size': self.topo_size,
            'variation': self.variation,
            'fl_clients_number': self.fl_clients_number,
        }
        return str(info)
