#!/bin/bash

# Function to start the server
start_server() {
    port=$1
    log_path=$2
    echo "Starting iperf server on port $port" >> logs/iperflogs.txt
    iperf3 -s --one-off -i 10 -p "$port" >> /dev/null 2>&1 &
    PID=$!

    sleep 0.2

    if ps -p $PID > /dev/null; then echo "Server started successfully with PID $PID..." >> logs/iperflogs.txt
        return 0
    else
        echo "Server failed to start .. Retrying ." >> logs/iperflogs.txt
        return 1
    fi
}

# Function to start the client
start_client() {
    port=$1
    dst_ip=$2
    rate=$3
    log_path=$4
    echo "Starting iperf client to $dst_ip on port $port with rate $rate" >> logs/iperflogs.txt
    iperf3 -c "$dst_ip" -i 10 -t 10000 -b "$rate"M -p "$port" --connect-timeout 500 >> /dev/null 2>&1 &
    PID=$!

    sleep 0.2

    if ps -p $PID > /dev/null; then
        echo "Client started successfully with PID $PID..." >> logs/iperflogs.txt
        return 0
    else
        echo "Client failed to start .. Retrying ." >> logs/iperflogs.txt
        return 1
    fi
}

# Main script logic
if [[ "$1" == "server" ]]; then
    while ! start_server "$2" "$3"; do
        sleep 1  # Sleep for 1 seconds before retrying
    done

elif [[ "$1" == "client" ]]; then
    while ! start_client "$2" "$3" "$4" "$5"; do
        sleep 1  # Sleep for 1 seconds before retrying
    done

else
    echo "Invalid mode. Use 'server' or 'client'."
    exit 1
fi
