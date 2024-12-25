#!/bin/bash

# Output CSV file
LOG_PATH=$1
OUTPUT_FILE="$LOG_PATH/monitor_netem.csv"

# Add header to CSV if it doesn't exist
if [ ! -f "$OUTPUT_FILE" ]; then
    echo "timestamp,interface,qdisc,bytes_sent,packets_sent,dropped,overlimits,requeues,backlog" > "$OUTPUT_FILE"
fi

extract_stats() {
    local line="$1"
    local qdisc="$2"

    # Extract bytes, packets, drops, overlimits and requeues
    bytes=$(echo "$line" | grep -o '[0-9]\+ bytes' | cut -d' ' -f1)
    packets=$(echo "$line" | grep -o '[0-9]\+ pkt' | cut -d' ' -f1)
    dropped=$(echo "$line" | grep -o 'dropped [0-9]\+' | cut -d' ' -f2)
    overlimits=$(echo "$line" | grep -o 'overlimits [0-9]\+' | cut -d' ' -f2)
    requeues=$(echo "$line" | grep -o 'requeues [0-9]\+' | cut -d' ' -f2 | head -n1)
    backlog=$(echo "$line" | grep -o 'backlog [0-9]\+' | cut -d' ' -f2)

    if [ -z "$bytes" ] || [ "$bytes" = "0" ] && \
       [ -z "$packets" ] || [ "$packets" = "0" ]; then
        return
    fi

    echo "$timestamp,$interface,$qdisc,$bytes,$packets,$dropped,$overlimits,$requeues,$backlog" >> "$OUTPUT_FILE"
}

# Function to get traffic control data for an interface
get_tc_stats() {
    local interface="$1"
    local timestamp
    timestamp=$(date +"%H_%M_%S")

    # Run tc command to get stats for the interface
    tc_output=$(tc -s qdisc show dev "$interface" 2>/dev/null)

    # If there's no output, skip this interface
    if [ -z "$tc_output" ]; then
        return
    fi

    # Extract qdisc details
    htb_stats=$(echo "$tc_output" | grep -A2 "qdisc htb")
    extract_stats "$htb_stats" "htb"

    netem_stats=$(echo "$tc_output" | grep -A2 "qdisc netem")
    extract_stats "$netem_stats" "netem"
}

while true; do
    for interface in $(ls /sys/class/net/ | grep -E 'cs[0-9]-eth[0-9]$'); do
        get_tc_stats "$interface"
    done
    echo ",,,,,,," >> "$OUTPUT_FILE"
    sleep 5
done