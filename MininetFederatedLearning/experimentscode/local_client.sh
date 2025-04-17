cid="$1"
port="1234$cid"
source ../venv/bin/activate
flower-supernode --insecure --isolation process --superlink='127.0.0.1:9092' --node-config="cid=$cid" --clientappio-api-address="0.0.0.0:$port" &
flwr-clientapp --insecure --clientappio-api-address="0.0.0.0:$port"
