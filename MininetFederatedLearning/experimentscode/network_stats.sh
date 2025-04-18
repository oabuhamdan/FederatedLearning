#!/bin/bash

# Read RX and TX bytes for given interface
interface="$1"
interval="$2"
output_file="$3"

# Initialize output file with headers
echo "elapsed_seconds,rx_bytes,tx_bytes,rx_rate_bps,tx_rate_bps" > "$output_file"

# Get initial statistics and time
start_time=$(date +%s%N)
prev_time=$start_time
prev_rx=$(< /sys/class/net/"$interface"/statistics/rx_bytes)
prev_tx=$(< /sys/class/net/"$interface"/statistics/tx_bytes)

while true; do
    sleep "$interval"

    # Get current time (in nanoseconds) and statistics
    current_time=$(date +%s%N)
    curr_rx=$(< /sys/class/net/"$interface"/statistics/rx_bytes)
    curr_tx=$(< /sys/class/net/"$interface"/statistics/tx_bytes)

    # Calculate actual time difference in seconds (converting from nanoseconds)
    time_diff=$(( (current_time - prev_time) / 1000000000 ))

    # Prevent division by zero
    if [ "$time_diff" -gt 0 ]; then
        # Calculate elapsed time and rates using integer arithmetic
        elapsed=$(( (current_time - start_time) / 1000000000 ))
        rx_diff=$((curr_rx - prev_rx))
        tx_diff=$((curr_tx - prev_tx))
        # Convert to bits per second using actual time difference
        rx_rate=$(((rx_diff * 8) / time_diff))
        tx_rate=$(((tx_diff * 8) / time_diff))

        # Format timestamp

        # Log to file
        echo "$elapsed,$curr_rx,$curr_tx,$rx_rate,$tx_rate" >> "$output_file"

        # Update previous values
        prev_rx=$curr_rx
        prev_tx=$curr_tx
        prev_time=$current_time
    fi
done