import json
from collections import Counter
from urllib import request

from mininet.node import Docker
from mininet.topo import Topo


class MyTopo2(Topo):
    def __init__(self, topo_conf, client_conf, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.link_config = {
            "bw": topo_conf["links"]["link-bw"],
            "delay": topo_conf["links"]["link-delay"],
            "max_queue_size": topo_conf["links"]["link-max-queue-size"]
        }
        self.switch_config = dict(protocols="OpenFlow13", failMode='standalone', stp=topo_conf["stp"])

        self.topo_conf = topo_conf
        self.client_conf = client_conf

        self.nodes_data, self.links_data, = self.get_data(
            topo_conf["topo-type"],
            topo_conf["topo-size"],
            topo_conf["topo-variation"]
        )
        self.fl_clients = None

        self.containernet_kwargs = {
            "volumes": [
                f"{client_conf['data-mount']}:/app/data",
                f"{client_conf['logs-mount']}:/app/logs",
                "/etc/localtime:/etc/localtime:ro",
            ],
            "cls": Docker,
        }
        self.fl_host_limits = dict(mem_limit=client_conf['mem-limit'], memswap_limit=client_conf['memswap-limit'],
                                   cpu_period=100000,
                                   cpu_quota=int(client_conf['cpu-limit'] * 100000),
                                   sysctls={"net.ipv4.tcp_congestion_control": "cubic"},
                                   dimage=client_conf['fl-imgname'])
        self.bg_host_limits = dict(mem_limit="256m", memswap_limit="512m", cpu_period=100000,
                                   cpu_quota=int(0.10 * 100000),
                                   sysctls={"net.ipv4.tcp_congestion_control": "cubic",
                                            "net.ipv4.tcp_rmem": "87380 16777216 16777216",
                                            "net.ipv4.tcp_wmem": "87380 16777216 16777216"},
                                   dimage=client_conf['bg-imgname'])

        self.build(config_loaded=True)

    def build(self, *args, **params):
        if not params.get("config_loaded", False):
            return

        self.create_nodes(self.nodes_data)
        self.create_links(self.nodes_data, self.links_data)
        fl_server = self.addHost('flserver', ip=f"10.0.0.250", mac="00:00:00:00:00:FA",
                                 dimage=self.client_conf['fl-imgname'], **self.containernet_kwargs)
        nodes_sorted_by_degree = sorted(self.nodes_data.values(), key=lambda x: x["degree"])
        node_max_degree = nodes_sorted_by_degree[-1]["node"]
        self.addLink(node_max_degree, fl_server)

        if self.topo_conf["enable-bg-traffic"]:
            self.create_bg_hosts()

        self.fl_clients = self.create_fl_hosts(
            nodes_sorted_by_degree[:-int(self.topo_conf["topo-size"] * self.topo_conf["switch-exclude"])])  # exclude

    def create_links(self, nodes, links_data):
        for link in links_data:
            src, dst = link["src"], link["dst"]
            self.addLink(nodes[src]['node'], nodes[dst]['node'], **self.link_config)

    def create_nodes(self, nodes_data):
        for nid in nodes_data:  # 10 nodes in the graph
            nodes_data[nid]['node'] = self.addSwitch(f'S{nid}', **self.switch_config, dpid=f"100{nid}")

    def create_bg_hosts(self):
        for i, node in self.nodes_data.items():
            node["bgclient"] = self.addHost(f'bgclient{i}', ip=f"10.0.1.{i + 1}", mac=self.int_to_mac(i + 257),
                                            **self.containernet_kwargs, **self.bg_host_limits)

        for node in self.nodes_data.values():
            self.addLink(node["bgclient"], node['node'])

    def create_fl_hosts(self, nodes_sorted_by_degree):
        fl_hosts = [
            self.addHost(f'flclient{i + 1}', ip=f"10.0.0.{i + 1}", mac=self.int_to_mac(i + 1),
                         **self.containernet_kwargs, **self.fl_host_limits)
            for i in range(self.topo_conf["clients-number"])
        ]
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

            my_link["src-dst"] = int(link["ecmp_fwd"]["deg"] * 0.90)
            my_link["dst-src"] = int(link["ecmp_bwd"]["deg"] * 0.90)
            my_links.append(my_link)

        degree = Counter([link['src'] for link in my_links] + [link['dst'] for link in my_links])
        nodes = dict()
        for node in data["nodes"]:
            nodes[node["id"]] = dict(degree=degree[node["id"]])

        return nodes, my_links