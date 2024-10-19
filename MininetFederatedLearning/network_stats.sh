#!/bin/bash

# Define the network interface, interval (in seconds), and output file
INTERFACE=$1       # Change this to your network interface
INTERVAL=$2             # Time interval in seconds
OUTPUT_FILE=$3

## TX Stats
prev_tx_bytes=$(cat /sys/class/net/$INTERFACE/statistics/tx_bytes)
prev_tx_errors=$(cat /sys/class/net/$INTERFACE/statistics/tx_errors)
prev_tx_dropped=$(cat /sys/class/net/$INTERFACE/statistics/tx_dropped)

# RX Stats
prev_rx_bytes=$(cat /sys/class/net/$INTERFACE/statistics/rx_bytes)
prev_rx_errors=$(cat /sys/class/net/$INTERFACE/statistics/rx_errors)
prev_rx_dropped=$(cat /sys/class/net/$INTERFACE/statistics/rx_dropped)

start_time=$(date +%s)

# Write the CSV header
echo "Time,Elapsed_Seconds,TX_Bytes,TX_Errors,TX_Dropped,RX_Bytes,RX_Errors,RX_Dropped" > $OUTPUT_FILE

while true; do
    # Get the current time in seconds
    current_time=$(date +%s)

    # Calculate the elapsed time since the script started
    elapsed_time=$((current_time - start_time))

    # Read current TX and RX bytes
    tx_bytes=$(cat /sys/class/net/$INTERFACE/statistics/tx_bytes)
    tx_errors=$(cat /sys/class/net/$INTERFACE/statistics/tx_errors)
    tx_dropped=$(cat /sys/class/net/$INTERFACE/statistics/tx_dropped)

    rx_bytes=$(cat /sys/class/net/$INTERFACE/statistics/rx_bytes)
    rx_errors=$(cat /sys/class/net/$INTERFACE/statistics/rx_errors)
    rx_dropped=$(cat /sys/class/net/$INTERFACE/statistics/rx_dropped)

    # Calculate the difference (delta) from the previous read
    delta_tx_bytes=$((tx_bytes - prev_tx_bytes))
    delta_tx_errors=$((tx_errors - prev_tx_errors))
    delta_tx_dropped=$((tx_dropped - prev_tx_dropped))

    delta_rx_bytes=$((rx_bytes - prev_rx_bytes))
    delta_rx_errors=$((rx_errors - prev_rx_errors))
    delta_rx_dropped=$((rx_dropped - prev_rx_dropped))

    # Write the data to the CSV file
    output="$(date +%s),"
    output+="$elapsed_time,"
    output+="$delta_tx_bytes,$delta_tx_errors,$delta_tx_dropped,"
    output+="$delta_rx_bytes,$delta_rx_errors,$delta_rx_dropped"

    echo "$output" >> $OUTPUT_FILE
    # Update previous values

    prev_tx_bytes=$tx_bytes
    prev_tx_errors=$tx_errors
    prev_tx_dropped=$tx_dropped

    # RX Stats
    prev_rx_bytes=$rx_bytes
    prev_rx_errors=$rx_errors
    prev_rx_dropped=$rx_dropped

    # Wait for the specified interval before the next read
    sleep $INTERVAL
done
