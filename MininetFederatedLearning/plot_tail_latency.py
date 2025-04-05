import csv
from collections import defaultdict

import matplotlib.pyplot as plt
from matplotlib.backends.backend_pdf import PdfPages

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
    min_time = 0  # min(client["server_to_client_time"] for client in round_data.values())
    deltas = {
        client_name: client["server_to_client_time"] - min_time
        for client_name, client in round_data.items()
    }
    return sorted(deltas.items(), key=lambda x: x[1])


# Part B: Client-to-Server Time Delta Analysis
def get_client_to_server_time_deltas(round_data):
    min_time = 0  # min(client["client_to_server_time"] for client in round_data.values())
    deltas = {
        client_name: client["client_to_server_time"] - min_time
        for client_name, client in round_data.items()
    }
    return sorted(deltas.items(), key=lambda x: x[1])


# Part C: Plotting the Data
def plot_all_rounds(clients_info, file_title, round_range=None):
    if round_range:
        rounds = [str(n) for n in range(round_range[0], round_range[1])]
    else:
        rounds = list(clients_info.keys())
    s2c_data = []
    s2c_labels = []
    c2s_data = []
    c2s_labels = []
    round_positions = []
    current_position = 0

    # Organize data by rounds with gaps in between
    for round_key in rounds:
        s2c_deltas = get_server_to_client_time_deltas(clients_info[round_key])
        c2s_deltas = get_client_to_server_time_deltas(clients_info[round_key])

        # Extract and append server-to-client times for this round
        s2c_clients, s2c_values = zip(*s2c_deltas)
        s2c_data.extend(s2c_values)
        s2c_labels.extend(s2c_clients)  # Add round info in labels

        c2s_clients, c2s_values = zip(*c2s_deltas)
        c2s_data.extend(c2s_values)
        c2s_labels.extend(c2s_clients)

        round_positions.extend([current_position + i for i in range(len(s2c_values))])
        current_position += len(s2c_values) + 2  # Add gap after each round

        # Extract and append client-to-server times for this round

    # Plotting
    fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(8, 5.1),  sharey=True)

    # Upper Plot - Server-to-Client Time
    # ax1.text(0.95, 0.99, file_title, fontsize=12, color='red', transform=ax1.transAxes, ha='right', va='top')
    ax1.bar(round_positions, s2c_data, width=0.8, color='blue', alpha=0.7, label='Server to Client')

    ax1.tick_params(axis='x', which='both',  labelsize=12, pad=6)
    ax1.tick_params(axis='y', labelsize=20)
    ax1.set_xticks(round_positions)
    ax1.set_xticklabels(s2c_labels, rotation=90, va='center')
    ax1.grid(axis='y', linestyle='--', alpha=0.7)
    ax1.set_ylim(0, 35)
    ax1.legend(loc='upper right', fontsize=20)
    ax1.set_yticks([x for x in range(0,35,10)], [str(x) for x in range(0,35,10)])

    # Lower Plot - Client-to-Server Time
    ax2.bar(round_positions, c2s_data, width=0.8, color='green', alpha=0.7, label='Client to Server',)
    # ax2.axes.set_xlim(0, 30)
    ax2.set_xlabel("Clients", fontsize=20)
    ax2.tick_params(axis='y', labelsize=20)
    ax2.tick_params(axis='x', which='both', labelsize=12, pad=6)
    ax2.set_xticks(round_positions)
    ax2.set_xticklabels(c2s_labels, rotation=90, va='center')
    ax2.grid(axis='y', linestyle='--', alpha=0.7)
    ax2.set_ylim(0, 35)
    ax2.set_yticks([x for x in range(0,35,10)], [str(x) for x in range(0,35,10)])
    ax2.legend(loc='upper right', fontsize=20)
    plt.tight_layout()

    # plt.show()
    fig.text(-0.015, 0.5, 'Time (s)', va='center', rotation='vertical', fontsize=20)
    plt.savefig(f"logs/plots/per_client_gabriel_15v9_hyb.pdf", bbox_inches='tight')


file_title = "gabriel15_v9/agmixed"
clients_info = get_info(f"logs_new/{file_title}/fl_task_client_times.csv")
plot_all_rounds(clients_info, file_title, round_range=(23, 26))
