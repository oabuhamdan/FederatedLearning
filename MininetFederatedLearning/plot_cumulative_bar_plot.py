import matplotlib.pyplot as plt
import numpy as np
import ast, re


def get_info(file_path):
    pattern = r"Fit Metrics:\s*(\[\(.*?\)\])"

    with open(file_path) as f:
        file_content = f.read()

    matches = re.findall(pattern, file_content, re.DOTALL)

    rounds = []
    for match in matches:
        fit_metrics_list = ast.literal_eval(match)
        rounds.append(fit_metrics_list)

    info = {}
    for i, flround in enumerate(rounds):
        info[f"Round-{i}"] = {}
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
            info[f"Round-{i}"][f"Client-{client}"] = dict(total_computing_time=total_computing_time,
                                                          server_to_client_time=server_to_client_time,
                                                          client_to_server_time=client_to_server_time)
    return info


def plot_fl_task_times(info, title):
    num_rounds = len(info)
    num_clients = len(info['Round-0'])
    bar_width = 0.8
    space_between_rounds = 2

    fig, ax = plt.subplots(figsize=(15, 6))

    for round_idx in range(num_rounds):
        round_key = f'Round-{round_idx}'
        x_offset = round_idx * (num_clients + space_between_rounds)

        for client_idx in range(num_clients):
            client_key = f'Client-{client_idx}'
            client_data = info[round_key][client_key]

            server_to_client = client_data['server_to_client_time']
            computing_time = client_data['total_computing_time']
            client_to_server = client_data['client_to_server_time']

            bottom = 0
            for phase, color in zip(
                    [server_to_client, computing_time, client_to_server],
                    ['#FF9999', '#66B2FF', '#99FF99']
            ):
                ax.bar(x_offset + client_idx, phase, bar_width, bottom=bottom, color=color)
                bottom += phase

    # Customize the plot
    # ax.set_xlabel('Rounds and Clients', fontsize=20)
    ax.set_ylabel('Time', fontsize=20)
    # ax.set_title('FL Task Time Breakdown by Round and Client')

    # Set x-ticks and labels
    x_ticks = np.arange(num_clients / 2, num_rounds * (num_clients + space_between_rounds) - space_between_rounds,
                        num_clients + space_between_rounds)

    ax.set_xticks(x_ticks)
    ax.set_xticklabels([f'Round {i + 1}' for i in range(num_rounds)], fontsize=20)

    # Add legend
    ax.legend(['Server to Client', 'Client Computing', 'Client to Server'], fontsize=20)

    # Add grid lines for better readability
    ax.grid(axis='y', linestyle='--', alpha=0.7)
    plt.yticks(fontsize=20)

    # plt.tight_layout()
    plt.title(title, fontsize=20)
    plt.show()


file_title = "fwd_exp2_mobilenet_large_10rounds_10hosts_with_bg_with_batch32"
info = get_info(f"logs/{file_title}/server.log")
plot_fl_task_times(info, file_title)
