import docker
import sys

from docker import errors


def create_container(client, container_name, network):
    volumes = {
        "/host/data": {"bind": "/app/data", "mode": "rw"},
        "/host/logs": {"bind": "/app/logs", "mode": "rw"}
    }

    cpu_quota = 75000 if "server" in container_name  else None
    mem_limit = "2g" if "server" in container_name else None

    # Create the container
    container = client.containers.run(
        image="fl_mininet_image:latest",
        name=container_name,
        command="tail -f /dev/null",
        detach=True,
        network=network.name,
        cpu_quota=cpu_quota,
        mem_limit=mem_limit,
        volumes=volumes,
    )

    print(f"Container '{container.name}' created and running.")
    return container


def create_network(client, network_name):
    try:
        network = client.networks.get(network_name)
    except errors.NotFound:
        network = client.networks.create(network_name, driver="bridge")

    return network


def run_command_in_container(container, cid, log_path):
    inf = container.exec_run("bash -c 'ip route get 1.1.1.1 | grep -oP \'dev \K\S+\''").output.decode().strip()
    if cid:
        port = f"123{cid}"

        full_cmd = (
            "bash -c '"
            f"./network_stats.sh {inf} 5 {log_path}/{container.name}_network.csv & "
            f"venv/bin/flower-supernode --insecure --isolation process --superlink=172.17.0.2:9092 "
            f"--node-config=cid={cid} --clientappio-api-address=0.0.0.0:{port} & "
            f"stdbuf -oL venv/bin/flwr-clientapp --insecure --clientappio-api-address=0.0.0.0:{port}"
            "'"
        )
    else:
        full_cmd = (
            "bash -c '"
            f"./network_stats.sh {inf} 5 {log_path}/{container.name}_network.csv & "
            f"venv/bin/flower-superlink --isolation process --insecure & "
            f"stdbuf -oL venv/bin/flwr-serverapp --insecure --run-once"
            "'"
        )

    try:
        exec_result = container.exec_run(cmd=full_cmd, stream=True, stdout=True, stderr=True)
        for line in exec_result.output:
            print(line.decode(), end="")

    except KeyboardInterrupt:
        print("\n🛑 Caught Ctrl+C. Cleaning up...")
        container.stop(timeout=3)
        container.remove(force=True)
        print(f"✅ Container {container.name} removed.")


def main():
    client = docker.from_env()
    network = create_network(client, "my_network")
    name = sys.argv[1]
    cid = int(sys.argv[2])
    log_path = sys.argv[3]
    if cid > 0:
        name += cid
    container = create_container(client, name, network)
    run_command_in_container(container, cid, log_path)


if __name__ == "__main__":
    main()
