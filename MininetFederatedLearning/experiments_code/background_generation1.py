import os
import random
import threading
import time

from mininet.node import Switch, Node


class BGTrafficGenerator:
    def __init__(self, bg_hosts, log_dir_name, auto_start_bg):
        self.bg_hosts = bg_hosts
        self.auto_start_bg = auto_start_bg
        self.log_path = f"logs/{log_dir_name}/iperf_logs"
        self.stop_event = threading.Event()
        self._traffic_thread = None

    @staticmethod
    def get_switch(host) -> Switch:
        return host.defaultIntf().link.intf2.node

    @staticmethod
    def get_opposite_node(node, intf=None) -> Node:
        if intf:
            return node.intfs[intf].link.intf2.node
        else:
            return node.defaultIntf().link.intf2.node

    def get_core_hosts(self):
        return [host for host in self.bg_hosts if
                self.get_opposite_node(self.get_opposite_node(host), 1).name.startswith("cs")]

    def _gen_traffic(self):
        i = 0  # used for round-robin

        with open(f"{self.log_path}/traffic_logs.txt", "w") as traffic_log:
            random.shuffle(self.bg_hosts)
            core = self.get_core_hosts()
            edge = [host for host in self.bg_hosts if host not in core]
            rate_choices = range(50, 90, 10)
            dur_choices = range(50, 100, 10)
            flow_sizes = list(range(50, 500, 50))
            while not self.stop_event.is_set():
                src_host, dst_host = random.sample([core[i % len(core)], edge[i % len(edge)]], 2)
                rate = random.choice(rate_choices)
                duration = random.choice(dur_choices)
                flow_size = random.choice(flow_sizes)
                port = random.randint(49152, 65535)

                # Log the flow details
                traffic_log.write(
                    f"From {src_host.IP()} to {dst_host.IP()}"
                    f" with rate {rate} and dur {duration}\n"
                )
                traffic_log.flush()

                # Start the server on the destination host
                dst_host.cmd(f'iperf3 -s --daemon --one-off -p {port} > /dev/null &')
                time.sleep(1)

                # Start the client on the source host
                src_host.cmd(f'iperf3 -c {dst_host.IP()} -t {duration} -b {rate}M -p {port} '
                             f'--logfile {self.log_path}/{src_host.name}_{dst_host.name}_{port}_logs.txt > /dev/null &')

                # Delay before generating the next flow
                time.sleep(random.randint(10, 20))
                i += 1

    def monitor_network(self):
        for bghost in self.bg_hosts:
            inf = bghost.defaultIntf()
            bghost.cmd(f"./network_stats.sh {inf} 1 {self.log_path}/{bghost.name}_network.csv > /dev/null 2>&1 &")

    def start(self):
        if self.auto_start_bg and not self._traffic_thread:
            self.stop_event.clear()
            os.makedirs(self.log_path, exist_ok=True)
            self._traffic_thread = threading.Thread(target=self._gen_traffic, daemon=True)
            self._traffic_thread.start()
            self.monitor_network()
            print("~ Traffic generation started ~")

    def stop(self):
        self.stop_event.set()

        if self._traffic_thread:
            self._traffic_thread.join(timeout=1)
            self._traffic_thread = None

        [host.cmd("pkill -f 'iperf3'") for host in self.bg_hosts]
        [host.cmd("pkill -f 'network_stats.sh'") for host in self.bg_hosts]

        print("~ Traffic generation has stopped, and all iperf3 processes have been terminated ~")
