import csv
from collections import defaultdict

import matplotlib.pyplot as plt


def get_info(file_path):
    clients_info = defaultdict(dict)

    # Open the CSV file and read it
    with open(file_path, 'r') as file:
        reader = csv.DictReader(file)

        # Iterate through the data in the CSV
        for row in reader:
            round_num = row['current_round']
            client_id = row['client_id']

            # Create the dictionary for the client in this round
            clients_info[f"{round_num}"][f"{client_id}"] = {
                "server_to_client_time": float(row['server_to_client_time']),
                "client_to_server_time": float(row['client_to_server_time']),
            }
    return clients_info


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
def plot_all_rounds(clients_info, file_title):
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
    fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(14, 8))
    client_labels = [label for label in client_labels]

    # Upper Plot - Server-to-Client Time
    ax1.text(0.95, 0.99, file_title, fontsize=12, color='red', transform=ax1.transAxes, ha='right', va='top')
    ax1.bar(round_positions, server_data, width=0.8, color='blue', alpha=0.7)
    ax1.set_title("Server-to-Client Time Deltas for All Rounds", fontsize=16)
    ax1.set_ylabel("Time Delta", fontsize=14)
    ax1.set_xlabel("Clients", fontsize=14)
    ax1.tick_params(axis='x', which='both', bottom=False)
    ax1.tick_params(axis='y', labelsize=14)
    ax1.set_xticks(round_positions)
    ax1.set_xticklabels(client_labels)
    ax1.grid(axis='y', linestyle='--', alpha=0.7)
    # ax1.set_ylim(0, 25)
    # Lower Plot - Client-to-Server Time
    ax2.bar(round_positions, client_data, width=0.8, color='green', alpha=0.7)
    ax2.set_title("Client-to-Server Time Deltas for All Rounds", fontsize=16)
    ax2.set_ylabel("Time Delta", fontsize=14)
    ax2.set_xlabel("Clients", fontsize=14)
    ax2.tick_params(axis='y', labelsize=14)
    ax2.set_xticks(round_positions)
    ax2.set_xticklabels(client_labels)
    ax2.grid(axis='y', linestyle='--', alpha=0.7)
    # ax2.set_ylim(0, 25)

    plt.tight_layout(rect=[0, 0.03, 1, 0.95])
    plt.show()
    # plt.savefig(f"{file_title}.png")


file_title = "[withQoS]_flowsched_1226_165022"
clients_info = get_info(f"logs/{file_title}/fl_task_times.csv")
plot_all_rounds(clients_info, file_title)
