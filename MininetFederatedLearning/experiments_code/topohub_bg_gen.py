import os

class BGTrafficGenerator:
    def __init__(self, bg_hosts, topo_nodes_info, topo_links_info, log_dir_name):
        self.topo_nodes_info = topo_nodes_info
        self.topo_links_info = topo_links_info
        self.log_path = f"logs/{log_dir_name}/iperf_logs"
        self.bg_hosts = bg_hosts

    def gen_traffic(self):
        port = 12345
        with open(f"{self.log_path}/traffic_logs.txt", "w") as traffic_log:
            def start_flow(src, dst, rate):
                nonlocal port
                log_file = f'{self.log_path}/{src.name}_{dst.name}_{port}_logs.txt'
                dst.cmd(f"./start_iperf.sh server {port} {log_file}")
                traffic_log.write(f"From {src.IP()} to {dst.IP()}" f" with rate {rate}\n")
                src.cmd(f"./start_iperf.sh client {port} {dst.IP()} {rate} {log_file}")
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
