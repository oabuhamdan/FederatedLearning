import os
import random
import time


class BGTrafficGenerator:
    def __init__(self, bg_hosts, topo_nodes_info, topo_links_info, log_dir_name):
        self.topo_nodes_info = topo_nodes_info
        self.topo_links_info = topo_links_info
        self.log_path = f"logs/{log_dir_name}/iperf_logs"
        self.bg_hosts = bg_hosts

    def generate_dynamic_flows(self, src, dst, avg_throughput_mbps):
        max_flows = 10  # Maximum number of concurrent flows
        min_flows = 3  # Minimum number of concurrent flows
        active_flows = []  # List to track active flow PIDs
        current_total_rate = 0
        flow_bandwidth = avg_throughput_mbps
        with open(f"{self.log_path}/traffic_logs.txt", "w") as traffic_log:
            while True:
                num_active_flows = len(active_flows)
                # Randomly decide to increase or decrease the number of flows
                if num_active_flows < max_flows and random.random() <= 0.5:
                    # Start a new flow with 50% probability if under max_flows
                    port = str(time.time_ns())[-5:]
                    dst.cmd(f'iperf3 -s --daemon --one-off -p {port} > /dev/null &')
                    src.cmd(f'iperf3 -c {dst.IP()} -t {2000} -b {flow_bandwidth}M -p {port} '
                            f'--logfile {self.log_path}/{src.name}_{dst.name}_{port}_logs.txt > /dev/null &')

                elif num_active_flows > min_flows and random.random() >= 0.5:
                    # Stop a random flow with 50% probability if over min_flows
                    pid_to_stop = random.choice(active_flows)
                    traffic_log.write(f"Stopping flow with PID {pid_to_stop} (Total flows: {len(active_flows) - 1})"
                                      f"(Total Rate: {current_total_rate})")
                    src.cmd(f'kill -9 {pid_to_stop}')  # Kill the process by PID
                    active_flows.remove(pid_to_stop)

                flow_bandwidth = avg_throughput_mbps // (num_active_flows + 1)
                time.sleep(2)

    def gen_traffic(self):
        port = 12345
        with open(f"{self.log_path}/traffic_logs.txt", "w") as traffic_log:
            def start_flow(src, dst, rate):
                nonlocal port
                dst.cmd(f"./start_iperf.sh server {port} '{self.log_path}/{src.name}_{dst.name}_{port}_logs.txt'")
                traffic_log.write(f"From {src.IP()} to {dst.IP()}" f" with rate {rate}\n")
                src.cmd(f"./start_iperf.sh client {port} {dst.IP()} {rate} '{self.log_path}/{src.name}_{dst.name}_{port}_logs.txt'")
                port += 1

            for link in self.topo_links_info:
                src_switch = link["src"]
                dst_switch = link["dst"]
                src_host = self.bg_hosts[self.topo_nodes_info[src_switch]["bgclient"]]
                dst_host = self.bg_hosts[self.topo_nodes_info[dst_switch]["bgclient"]]
                src2dst_flow_rate = link["src-dst"]
                dst2src_flow_rate = link["dst-src"]

                start_flow(src_host, dst_host, src2dst_flow_rate)
                start_flow(dst_host, src_host, dst2src_flow_rate)

    def monitor_network(self):
        for bghost in self.bg_hosts.values():
            inf = bghost.defaultIntf()
            bghost.cmd(f"./network_stats.sh {inf} 10 {self.log_path}/{bghost.name}_network.csv > /dev/null 2>&1 &")

    def start(self):
        os.makedirs(self.log_path, exist_ok=True)
        self.gen_traffic()
        self.monitor_network()
        print("~ Traffic generation started ~")

    def stop(self):
        [host.cmd("pkill -f 'iperf3'") for host in self.bg_hosts.values()]
        [host.cmd("pkill -f 'network_stats.sh'") for host in self.bg_hosts.values()]

        print("~ Traffic generation has stopped, and all iperf3 processes have been terminated ~")
