import pandas as pd
import matplotlib.pyplot as plt
import os

# Load data for all 4 clients
def plot_network_logs(DIR, type="fl", number_of_clients=5, offset=0):
    def get_data(node):
        data = pd.read_csv(f"logs/{DIR}{'/iperf_logs' if type =='bgclient' else ''}/{node}_network.csv", usecols=["rx_rate_bps", "tx_rate_bps"])
        return data[data >= 0]

    fig, axs = plt.subplots(number_of_clients + (1 if type=="flclient" else 0) , 1, figsize=(10, 15))  # 2x2 grid of subplots

    # Plot data for each client in a different subplot
    for i in range(number_of_clients):
        client_data = get_data(f"{type}{offset+i}")
        client_data["tx_rate_bps"] = client_data["tx_rate_bps"] // (1024*1024)
        client_data["rx_rate_bps"] = client_data["rx_rate_bps"] // (1024*1024)
        axs[i].plot(client_data, label=["rx_rate", "tx_rate"])
        axs[i].legend()
        axs[i].grid()
        axs[i].set_title(f"Client {offset+i}")
    if type == "flclient":
        server = get_data("server")
        server["tx_rate_bps"] = server["tx_rate_bps"] // (1024 * 1024)
        server["rx_rate_bps"] = server["rx_rate_bps"] // (1024 * 1024)
        axs[-1].plot(server, label=["rx_rate", "tx_rate"])
        axs[-1].grid()
        axs[-1].legend()
        axs[-1].set_title("Server")

    plt.tight_layout()
    plt.show()
    # plt.savefig(f"logs/plots/{DIR}_netlogs.png")

plot_network_logs("[withBW]_agfs_15rounds_g25_70212_174326", type="flclient", number_of_clients=3, offset=14)