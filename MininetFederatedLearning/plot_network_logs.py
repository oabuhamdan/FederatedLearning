import pandas as pd
import matplotlib.pyplot as plt
import os

DIR = "logs/[withBW]_flowsched_cubic_0115_164245"


# Load data for all 4 clients
def plot_network_logs(DIR, type="fl", number_of_clients=5, offset=0):
    def get_data(node):
        data = pd.read_csv(f"logs/{DIR}{'/iperf_logs' if type =='bgclient' else ''}/{node}_network.csv", skiprows=2,
                           names=["time", "rx_rate", "tx_rate"], delimiter='\s+', usecols=["rx_rate", "tx_rate"])
        return data[data >= 0]

    number_of_clients+= 1 if type =='bgclient' else 0
    fig, axs = plt.subplots(number_of_clients , 1, figsize=(10, 15))  # 2x2 grid of subplots

    # Plot data for each client in a different subplot
    for i in range(number_of_clients):
        client_data = get_data(f"{type}{offset+i}")
        client_data["tx_rate"] = client_data["tx_rate"] // 1024
        client_data["rx_rate"] = client_data["rx_rate"] // 1024
        axs[i].plot(client_data, label=["tx_rate", "rx_rate"])
        axs[i].legend()
        axs[i].grid()
        axs[i].set_title(f"Client {offset+i}")
    if type != "bgclient":
        server = get_data("server")
        server["tx_rate"] = server["tx_rate"] // 1024
        server["rx_rate"] = server["rx_rate"] // 1024
        axs[-1].plot(server, label=["tx_rate", "rx_rate"])
        axs[-1].grid()
        axs[-1].set_title("Server")

    plt.tight_layout()
    # plt.show()
    plt.savefig(f"logs/plots/{DIR}_netlogs.png")

plot_network_logs("[withBW]_test_networkstat_0203_214908", type="flclient_", number_of_clients=5, offset=0)