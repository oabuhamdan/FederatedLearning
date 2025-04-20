import sys
import csv
import threading
import time

from scapy.all import sniff, IP, TCP, UDP, AsyncSniffer
from collections import defaultdict
import signal

traffic_data = defaultdict(lambda: {"packets": 0, "bytes": 0})
data_lock = threading.Lock()
output_file = None
logged = False

def handle_sigterm(signum, frame):
    print(f"Caught {signal.Signals(signum).name}, shutting down.")
    log_to_csv_periodically(output_file)
    time.sleep(2)
    sys.exit(0)

# Register the signal handler
signal.signal(signal.SIGTERM, handle_sigterm)
signal.signal(signal.SIGINT, handle_sigterm)
signal.signal(signal.SIGHUP, handle_sigterm)


def packet_callback(packet):
    if IP in packet:
        ip_layer = packet[IP]
        proto = packet.payload.name
        src_ip = ip_layer.src
        dst_ip = ip_layer.dst

        src_port = dst_port = None
        if TCP in packet:
            src_port = packet[TCP].sport
            dst_port = packet[TCP].dport
        elif UDP in packet:
            src_port = packet[UDP].sport
            dst_port = packet[UDP].dport

        size = len(packet)
        key = (src_ip, dst_ip, src_port, dst_port, proto)

        with data_lock:
            traffic_data[key]["packets"] += 1
            traffic_data[key]["bytes"] += size


def log_to_csv_periodically(file):
    global logged
    if not logged:
        logged = True
        with open(file, "w") as csvfile:
            writer = csv.writer(csvfile)
            writer.writerow(["Src IP", "Dst IP", "Src Port", "Dst Port", "Protocol", "Packets", "Bytes"])
            print("[*] Logging traffic data to CSV...")
            for (src_ip, dst_ip, src_port, dst_port, proto), data in traffic_data.items():
                writer.writerow([
                    src_ip,
                    dst_ip,
                    src_port if src_port is not None else "",
                    dst_port if dst_port is not None else "",
                    proto,
                    data["packets"],
                    data["bytes"]
                ])


def main():
    global output_file

    intf = sys.argv[1]
    output_file = sys.argv[2]

    try:
        sniff(prn=packet_callback, store=0, iface=intf)  # Or specify iface manually
    except KeyboardInterrupt:
        print("\n[*] Capture stopped. Final data written.\n")
    finally:
        log_to_csv_periodically(output_file)


if __name__ == "__main__":
    main()
