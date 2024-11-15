import re
import ast
import matplotlib.pyplot as plt
import numpy as np


def get_info(file_path):
    pattern_metrics = r"Fit Metrics:\s*(\[\(.*?\)\])"
    pattern_round_time = r'ROUND ([0-9]+) FIT (START|END) TIME ([0-9]+(?:\.[0-9]+)?)'

    with open(file_path) as f:
        file_content = f.read()

    matches_metrics = re.findall(pattern_metrics, file_content, re.DOTALL)
    matches_round_time = re.findall(pattern_round_time, file_content, re.DOTALL)

    rounds = []
    for match in matches_metrics:
        fit_metrics_list = ast.literal_eval(match)
        rounds.append(fit_metrics_list)

    round_time_info = {f"round_{round_number}": {} for round_number in range(1, len(rounds) + 1)}
    for match in matches_round_time:
        round_number = match[0]  # First group: round number
        start_end = match[1]  # Second group: either START or END
        time = match[2]  # Third group: time
        round_time_info[f"round_{round_number}"][f"{start_end.lower()}_time"] = time

    clients_info = {}
    for i, flround in enumerate(rounds):
        clients_info[f"Round-{i}"] = {}
        for client_info in flround:
            client_info = client_info[1]
            client = client_info["client"]
            round_start_time = client_info["client_round_start_time"]
            round_finish_time = client_info["client_round_finish_time"]
            computing_start_time = client_info["computing_start_time"]
            computing_finish_time = client_info["computing_finish_time"]
            total_computing_time = computing_finish_time - computing_start_time
            server_to_client_time = computing_start_time - round_start_time
            client_to_server_time = round_finish_time - computing_finish_time
            clients_info[f"Round-{i}"][f"Client-{client}"] = dict(
                total_computing_time=total_computing_time,
                server_to_client_time=server_to_client_time,
                client_to_server_time=client_to_server_time,
                client_round_finish_time=round_finish_time
            )
    return clients_info, round_time_info


# Part A: Server-to-Client Time Delta Analysis
def get_server_to_client_time_deltas(round_data):
    min_time = min(client["server_to_client_time"] for client in round_data.values())
    deltas = {
        client_name: client["server_to_client_time"] - min_time
        for client_name, client in round_data.items()
    }
    return sorted(deltas.items(), key=lambda x: x[1])


# Part B: Client-to-Server Time Delta Analysis
def get_client_to_server_time_deltas(round_data):
    min_time = min(client["client_to_server_time"] for client in round_data.values())
    deltas = {
        client_name: client["client_to_server_time"] - min_time
        for client_name, client in round_data.items()
    }
    return sorted(deltas.items(), key=lambda x: x[1])


# Part C: Plotting the Data
def plot_all_rounds(clients_info):
    rounds = list(clients_info.keys())
    server_data = []
    client_data = []
    client_labels = []
    round_positions = []
    current_position = 0

    # Organize data by rounds with gaps in between
    for round_key in rounds:
        server_deltas = get_server_to_client_time_deltas(clients_info[round_key])
        client_deltas = get_client_to_server_time_deltas(clients_info[round_key])

        # Extract and append server-to-client times for this round
        server_clients, server_values = zip(*server_deltas)
        server_data.extend(server_values)
        client_labels.extend([f"{client}" for client in server_clients])  # Add round info in labels
        round_positions.extend([current_position + i for i in range(len(server_values))])
        current_position += len(server_values) + 2  # Add gap after each round

        # Extract and append client-to-server times for this round
        client_clients, client_values = zip(*client_deltas)
        client_data.extend(client_values)

    # Plotting
    fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(18, 10))

    # Upper Plot - Server-to-Client Time
    ax1.bar(round_positions, server_data, width=0.8, color='blue', alpha=0.7)
    ax1.set_title("Server-to-Client Time Deltas for All Rounds")
    ax1.set_ylabel("Time Delta")
    ax1.tick_params(axis='x', which='both', bottom=False)
    ax1.set_xticks(round_positions)
    ax1.set_xticklabels(client_labels, rotation=90)
    ax1.grid(axis='y', linestyle='--', alpha=0.7)

    # Lower Plot - Client-to-Server Time
    ax2.bar(round_positions, client_data, width=0.8, color='green', alpha=0.7)
    ax2.set_title("Client-to-Server Time Deltas for All Rounds")
    ax2.set_ylabel("Time Delta")
    ax2.set_xticks(round_positions)
    ax2.set_xticklabels(client_labels, rotation=90)
    ax2.grid(axis='y', linestyle='--', alpha=0.7)

    plt.tight_layout(rect=[0, 0.03, 1, 0.95])
    plt.show()


file_title = "Exp6_mobilenet_large_5rounds_15hosts_with_bg_with_batch32"
clients_info, round_time_info = get_info(f"logs/{file_title}/server.log")
plot_all_rounds(clients_info)
