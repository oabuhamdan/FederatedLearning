import math
import random
import re

import yaml
from mininet.node import Docker
from mininet.topo import Topo

link_config = {"bw": 100, "delay": "1ms", "max_queue_size": 5000, "use_tbf": False, "latency_ms": 400}


class MyTopo(Topo):
    def __init__(self, *args, **kwargs):
        random.seed(43)
        super().__init__(*args, **kwargs)

        with open('links.yaml', 'r') as yaml_file:
            self.link_configs = yaml.safe_load(yaml_file)

        self.no_fl_clients = kwargs.get('fl_clients', 10)
        self.no_bg_clients = kwargs.get('bg_clients', 10)
        self.max_fl_clients_per_edge = kwargs.get('max_fl_clients_per_edge', 2)
        self.max_bg_clients_per_edge = kwargs.get('max_bg_clients_per_edge', 2)
        self.max_edges_per_agg = kwargs.get('max_edges_per_agg', 2)
        self.number_of_levels = kwargs.get('number_of_levels', 2)
        self.min_switches_per_level = kwargs.get('min_switches_per_level', 2)
        self.max_switches_per_level = kwargs.get('max_switches_per_level', 4)
        self.no_core_switches = kwargs.get('no_core_switches', 3)
        self.links_by_level = kwargs.get(
            'links_by_level',
            ['fast_lan', 'fast_lan', 'fast_lan', 'fast_lan', 'std_lan', 'std_lan']
        )
        self.start_level_for_aggregation = kwargs.get('start_level_for_aggregation', 0)

        # Switch config default
        self.switch_config = kwargs.get('switch_config', dict(protocols="OpenFlow13"))
        self.containernet_kwargs = {
            "volumes": [
                "/home/osama/PycharmProjects/FederatedLearning/MininetFederatedLearning/logs:/app/logs",
                "/home/osama/PycharmProjects/FederatedLearning/MininetFederatedLearning/data:/app/data",
            ],
            "dimage": "fl_mininet_image:latest",
            "cls": Docker
        }
        self.fl_host_limits = dict(mem_limit="1g", memswap_limit="2g", cpu_period=100000, cpu_quota=int(0.75 * 100000))
        self.bg_host_limits = dict(cpu_period=100000, cpu_quota=5000, mem_limit="128m", memswap_limit="256m")
        self.build(config_loaded=True)

    def __str__(self):
        info = {
            'fl_clients': self.no_fl_clients,
            'bg_clients': self.no_bg_clients,
            'number_of_levels': self.number_of_levels,
            'no_core_switches': self.no_core_switches,
            'min_switches_per_level': self.min_switches_per_level,
            'max_switches_per_level': self.max_switches_per_level,
        }
        return str(info)

    def build(self, *args, **params):
        if not params.get("config_loaded", False):
            return
        # Calculate number of edge switches needed
        num_fl_edge_switches = math.ceil(self.no_fl_clients / self.max_fl_clients_per_edge)
        num_bg_edge_switches = math.ceil(self.no_bg_clients / self.max_bg_clients_per_edge)

        num_agg_switches = math.ceil((num_fl_edge_switches + num_bg_edge_switches) / self.max_edges_per_agg)
        # Core Switches
        core_switches = self.create_core_switches()

        # fl_server
        fl_server = self.addHost('flserver', ip=f"10.0.0.100", mac="00:00:00:00:00:AA", **self.containernet_kwargs)
        self.addLink(fl_server, core_switches[0])

        # Intermediate levels
        intermediate_levels = self.create_middle_switches(core_switches[1:])

        valid_intermediate_levels = intermediate_levels[self.start_level_for_aggregation:]
        agg_switches = self.create_agg_switches(valid_intermediate_levels, num_agg_switches)

        # create fl edge switches and clients
        fl_edge_switches = self.create_fl_edge_switches(agg_switches, num_fl_edge_switches)
        self.create_fl_clients(fl_edge_switches)

        # create bg edge switches and clients
        if self.no_bg_clients > 0:
            bg_edge_core, bg_edge_agg = self.create_bg_edge_switches(
                core_switches[1:], agg_switches, num_bg_edge_switches
            )
            self.create_bg_clients(bg_edge_core, bg_edge_agg)

    def create_bg_edge_switches(self, core_switches, agg_switches, num_bg_edge_switches):
        num_edge_agg = math.ceil(len(agg_switches) / (len(core_switches) + len(agg_switches)) * num_bg_edge_switches)
        num_edge_core = num_bg_edge_switches - num_edge_agg

        bg_edge_core = [self.addSwitch(f'bgc_es{i + 1}', dpid=f"41{i}", **self.switch_config)
                        for i in range(num_edge_core)]
        bg_edge_agg = [self.addSwitch(f'bga_es{i + 1}', dpid=f"42{i}", **self.switch_config)
                       for i in range(num_edge_agg)]

        # self.link_configs[self.links_by_level[-2]]

        for i, core in enumerate(bg_edge_core):
            self.addLink(core, core_switches[i % len(core_switches)], **link_config)

        for i, aggregate in enumerate(bg_edge_agg):
            self.addLink(aggregate, agg_switches[i % len(agg_switches)], **link_config)

        return bg_edge_core, bg_edge_agg

    @staticmethod
    def int_to_mac(n):
        return ''.join(f'{(n >> (i * 8)) & 0xff:02x}' for i in range(5, -1, -1))

    def create_bg_clients(self, bg_edge_core, bg_edge_agg):
        num_client_agg = math.ceil(len(bg_edge_agg) / (len(bg_edge_agg) + len(bg_edge_core)) * self.no_bg_clients)
        num_client_core = self.no_bg_clients - num_client_agg

        bga_hosts = [
            self.addHost(f'bgahost{i + 1}', ip=f"10.0.0.{i + 50}", mac=self.int_to_mac(i + 50),
                         **self.containernet_kwargs, **self.bg_host_limits)
            for i in range(num_client_agg)
        ]
        bgc_hosts = [
            self.addHost(f'bgchost{i + 1}', ip=f"10.0.0.{i + 30}", mac=self.int_to_mac(i + 30),
                         **self.containernet_kwargs, **self.bg_host_limits)
            for i in range(num_client_core)
        ]

        # self.link_configs[self.links_by_level[-1]]
        len_bg_edge_agg = len(bg_edge_agg)
        for i, client in enumerate(bga_hosts):
            self.addLink(client, bg_edge_agg[i % len_bg_edge_agg], **link_config)

        len_bg_edge_core = len(bg_edge_core)
        for i, client in enumerate(bgc_hosts):
            self.addLink(client, bg_edge_core[i % len_bg_edge_core], **link_config)

    def create_fl_clients(self, edge_switches):

        clients = [
            self.addHost(f'flclient{i + 1}', ip=f"10.0.0.{i + 1}", mac=self.int_to_mac(i + 1),
                         **self.containernet_kwargs, **self.fl_host_limits) for i in range(self.no_fl_clients)
        ]
        len_edge = len(edge_switches)
        # self.link_configs[self.links_by_level[-1]]
        for i, client in enumerate(clients):
            self.addLink(client, edge_switches[i % len_edge], **link_config)

        return clients

    def create_fl_edge_switches(self, agg_switches, number_edge_switches):
        edge_switches = [self.addSwitch(f'fl_es{i + 1}', dpid=f"40{i}", **self.switch_config)
                         for i in range(number_edge_switches)]
        len_agg = len(agg_switches)
        # self.link_configs[self.links_by_level[-2]]
        for i, edge_switch in enumerate(edge_switches):
            self.addLink(edge_switch, agg_switches[i % len_agg], **link_config)

        return edge_switches

    def create_agg_switches(self, valid_intermediate_levels, num_agg):
        agg_switches = [self.addSwitch(f'as{i + 1}', dpid=f"30{i}", **self.switch_config) for i in
                        range(num_agg)]
        # Connect aggregation switches to random intermediate levels (between 1 and n)
        # self.link_configs[self.links_by_level[-3]]
        for agg_switch in agg_switches:
            random_level = random.choice(valid_intermediate_levels)  # Pick a random valid level
            for int_switch in random.sample(random_level, k=random.randint(2, len(random_level))):
                self.addLink(agg_switch, int_switch, **link_config)
        return agg_switches

    def create_middle_switches(self, core_switches):
        previous_level_switches = core_switches  # Start linking from core switches
        intermediate_levels = []
        for level in range(self.number_of_levels):
            # Randomly determine the number of switches in this level
            num_switches = random.randint(self.min_switches_per_level, self.max_switches_per_level)
            level_switches = [self.addSwitch(f'is{level}_{i}', dpid=f"2{level}{i}", **self.switch_config)
                              for i in range(num_switches)]
            intermediate_levels.append(level_switches)

            # Connect each switch in the previous level to a random subset of switches in the current level
            # self.link_configs[self.links_by_level[level + 1]]  # starting from one to skip core connection
            for prev_switch in previous_level_switches:
                # randomly choose how many connection are there
                # len(level_switches) - 1 to prevent all to all connection
                current_switches = random.sample(level_switches, k=random.randint(2, len(level_switches) - 1))
                for curr_switch in current_switches:
                    self.addLink(prev_switch, curr_switch, **link_config)

            previous_level_switches = level_switches  # Update previous level for the next iteration
        print(f"Intermediate Levels {intermediate_levels}")
        return intermediate_levels

    def create_core_switches(self):
        core_switches = [self.addSwitch(f'cs{i}', dpid=f"10{i}", **self.switch_config) for i in
                         range(self.no_core_switches)]  # Example with 2 core switches
        # self.link_configs[self.links_by_level[0]]  # starting from one to skip core connection
        for i in range(len(core_switches) - 1):
            for j in range(i + 1, len(core_switches)):
                self.addLink(core_switches[i], core_switches[j], **link_config)
        return core_switches
