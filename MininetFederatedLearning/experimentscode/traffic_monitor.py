import sys
import csv
from scapy.all import sniff
from collections import defaultdict

from scapy.layers.inet import TCP, UDP, IP

traffic_data = defaultdict(lambda: {"packets": 0, "bytes": 0})


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

        traffic_data[key]["packets"] += 1
        traffic_data[key]["bytes"] += size


def write_to_file(filename):
    with open(filename, mode="w", newline="") as f:
        writer = csv.writer(f)
        # Write header
        writer.writerow(["Src IP", "Dst IP", "Src Port", "Dst Port", "Protocol", "Packets", "Bytes"])
        # Write each traffic record
        for (src_ip, dst_ip, src_port, dst_port, proto), data in traffic_data.items():
            writer.writerow([src_ip, dst_ip, src_port if src_port is not None else "",
                              dst_port if dst_port is not None else "", proto, data["packets"], data["bytes"] ])
    print(f"\n[*] Traffic data saved to CSV file: {filename}")


def main():
    if len(sys.argv) != 2:
        print(f"Usage: python {sys.argv[0]} <output_filename>")
        sys.exit(1)

    output_file = sys.argv[1]

    print(f"[*] Starting packet capture... Output will be saved to '{output_file}'. Press Ctrl+C to stop.")
    try:
        sniff(prn=packet_callback, store=0)
    except Exception as e:
        print("\n[*] Capture stopped. Writing traffic data to file...\n")
    finally:
        write_to_file(output_file)


if __name__ == "__main__":
    main()
