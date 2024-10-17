import re
import ast
import matplotlib.pyplot as plt
import numpy as np


def get_info(file_path):
    pattern_metrics = r"Fit Metrics:\s*(\[\(.*?\)\])"
    pattern_round_time = r'ROUND ([0-9]+) (START|END) TIME ([0-9]+(?:\.[0-9]+)?)'

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
            total_round_time = round_finish_time - round_start_time
            server_to_client_time = computing_start_time - round_start_time
            client_to_server_time = round_finish_time - computing_finish_time
            clients_info[f"Round-{i}"][f"Client-{client}"] = dict(
                total_computing_time=total_computing_time,
                server_to_client_time=server_to_client_time,
                client_to_server_time=client_to_server_time,
                client_round_finish_time=round_finish_time
            )
    return clients_info, round_time_info


def plot_client_latencies_by_round(clients_info, round_time_info, file_title):
    rounds = sorted(clients_info.keys(), key=lambda x: int(x.split('-')[1]))
    all_clients = sorted(set(client for round_data in clients_info.values() for client in round_data.keys()))

    fig, ax = plt.subplots(figsize=(20, 8))

    bar_width = 0.8
    round_spacing = 3  # Increase this value to add more space between rounds
    # colors = plt.cm.rainbow(np.linspace(0, 1, len(all_clients)))
    # client_color_map = dict(zip(all_clients, colors))
    colors = plt.cm.rainbow(np.linspace(0, 1, len(rounds)))

    for i, round_name in enumerate(rounds):
        round_number = int(round_name.split('-')[1]) + 1
        round_end_time = float(round_time_info[f'round_{round_number}']['end_time'])
        round_start_time = float(round_time_info[f'round_{round_number}']['start_time'])

        latencies = []

        for client in all_clients:
            if client in clients_info[round_name]:
                client_data = clients_info[round_name][client]
                client_round_finish_time = client_data['client_round_finish_time']
                latency = round_end_time - client_round_finish_time
            else:
                latency = 0  # or np.nan if you prefer

            latencies.append(latency)
            # client_colors.append(client_color_map[client])

        x = np.arange(len(all_clients)) + i * (len(all_clients) + round_spacing)
        ax.bar(x, latencies, width=bar_width, color=colors[i], align='center', alpha=0.7)

    # ax.set_xlabel('Clients (sorted, grouped by Round)')
    ax.set_ylabel('Latency (seconds)', fontsize=20)
    # ax.set_title('Client Latencies Relative to Round End Time')

    # Set x-ticks in the middle of each round group
    group_centers = [np.mean(np.arange(len(all_clients)) + i * (len(all_clients) + round_spacing))
                     for i in range(len(rounds))]
    ax.set_xticks(group_centers)
    ax.set_xticklabels([f'Round {int(r.split("-")[1]) + 1}' for r in rounds], fontsize=20)

    # Add vertical lines to separate rounds
    for i in range(1, len(rounds)):
        ax.axvline(x=i * (len(all_clients) + round_spacing) - round_spacing / 2, color='gray', linestyle='--',
                   alpha=0.5)

    # Create a custom legend
    # legend_elements = [plt.Rectangle((0, 0), 1, 1, facecolor=client_color_map[client], alpha=1) for client in
    #                    all_clients]

    # ax.legend(legend_elements, all_clients, title="Clients", loc='center left', bbox_to_anchor=(1, 0.5), fontsize=20)
    plt.grid(True, which='major', axis='y', linestyle='--')
    plt.yticks(fontsize=20)
    # plt.tight_layout()
    plt.title(file_title, fontsize=20)
    plt.show()


file_title = "flowsched2_mnlarge_10rounds_10hosts_with_bg"
clients_info, round_time_info = get_info(f"logs/{file_title}/server.log")
# plot_client_latencies(clients_info, round_time_info, file_title)
plot_client_latencies_by_round(clients_info, round_time_info, file_title)
