import csv
from collections import defaultdict

import matplotlib.pyplot as plt
import numpy as np
import ast, re


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
                "computing_time": float(row['computing_time']),
            }
    return clients_info


def plot_fl_task_times(info, title, num_clients, round_range):
    num_rounds = round_range[1] - round_range[0]
    bar_width = 0.8
    space_between_rounds = 3

    plt.figure(figsize=(10, 6))

    for round_idx in range(round_range[0], round_range[1]):
        x_offset = (round_idx - round_range[0]) * (num_clients + space_between_rounds)

        for client_idx in range(1, num_clients+1):
            client_key = f'{client_idx}'
            client_data = info[f"{round_idx+1}"][client_key]

            server_to_client = client_data['server_to_client_time']
            computing_time = client_data['computing_time']
            client_to_server = client_data['client_to_server_time']

            bottom = 0
            for phase, color in zip(
                    [server_to_client, computing_time, client_to_server],
                    ['#FF9999', '#66B2FF', '#99FF99']
            ):
                plt.bar(x_offset + client_idx, phase, bar_width, bottom=bottom, color=color)
                bottom += phase

    # Customize the plot
    # ax.set_xlabel('Rounds and Clients', fontsize=20)
    plt.ylabel('Time', fontsize=24)
    plt.xlabel('Clients', fontsize=24)
    # ax.set_title('FL Task Time Breakdown by Round and Client')

    # Set x-ticks and labels

    plt.xticks(list(range(1, num_clients+1)), [str(x) for x in range(1,num_clients+1)], fontsize=20)

    # Add legend
    plt.legend(['Server to Client', 'Client Computing', 'Client to Server'], fontsize=20)

    # Add grid lines for better readability
    plt.grid(axis='y', linestyle='--', alpha=0.7)
    plt.yticks(fontsize=20)

    # plt.tight_layout()
    # plt.title(title, fontsize=20)
    # plt.show()
    plt.savefig('logs/plots/cumulative_bar_plot.pdf', bbox_inches='tight')


file_title = "50rounds_gabriel50_v0/fwd2_r50_g50_v0_0308_191653"
info = get_info(f"logs/{file_title}/fl_task_client_times.csv")
plot_fl_task_times(info, file_title, num_clients=20, round_range=(22, 23))
