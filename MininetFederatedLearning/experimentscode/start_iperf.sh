#!/bin/bash

# Function to start the server
start_server() {
    port=$1
    log_path=$2
    echo "Starting iperf server on port $port" >> "$log_path"
    iperf3 -s -i 10 -p "$port" >> /dev/null 2>&1 &
    PID=$!

    sleep 0.2

    if ps -p $PID > /dev/null; then
        echo "Server started successfully with PID $PID..." >> "$log_path"
        return 0
    else
        echo "Server failed to start .. Retrying." >> "$log_path"
        return 1
    fi
}

# Function to control dynamic traffic rate
control_traffic() {
    dst_ip=$1
    port=$2
    rate_values=($3)
    interval_values=($4)
    parallel=$5
    log_path=$6

    rate_index=0
    interval_index=0

    while true; do
        rate_index=$((rate_index % ${#rate_values[@]}))
        rate=${rate_values[$rate_index]}

        interval_index=$((interval_index % ${#rate_values[@]}))
        interval=${interval_values[$interval_index]}

        echo "$(date): Setting traffic rate to $rate Mbps for approximately $interval seconds" >> "$log_path"

        # Start iperf3 client with the current rate for the interval duration
        iperf3 -c "$dst_ip" -i 5 -t "$interval" -b "${rate}M" -p "$port" -P "$parallel" --connect-timeout 500 >> /dev/null 2>&1

        ((rate_index++, interval_index++))
        # Small delay to prevent potential race conditions
        sleep 0.5
    done
}

# Function to start the client with Gaussian distributed traffic
start_client() {
    dst_ip=$1
    port=$2
    rate_values=$3
    interval_values=$4
    parallel=$5
    log_path=$6

    echo "Starting iperf client to $dst_ip on port $port" >> "$log_path"

    # Start the traffic controller process
    control_traffic "$dst_ip" "$port" "$rate_values" "$interval_values" "$parallel" "$log_path" &
    PID=$!

    sleep 0.2

    if ps -p $PID > /dev/null; then
        echo "Traffic controller started successfully with PID $PID..." >> "$log_path"
        return 0
    else
        echo "Traffic controller failed to start .. Retrying ." >> "$log_path"
        return 1
    fi
}

# Main script logic
if [[ "$1" == "server" ]]; then
    while ! start_server "$2" "$3"; do
        sleep 1  # Sleep for 1 second before retrying
    done

elif [[ "$1" == "client" ]]; then
    if [ $# -lt 6 ]; then
        echo "Missing parameters for client mode."
        echo "Usage: $0 client port dst_ip rate_values interval_values log_path"
        exit 1
    fi

    while ! start_client "$2" "$3" "$4" "$5" "$6" "$7"; do
        sleep 1  # Sleep for 1 second before retrying
    done
fi