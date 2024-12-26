import pandas as pd
import matplotlib.pyplot as plt
import os

DIR = "logs/[withQoS]_flowsched_1226_165022"


# Load data for all 4 clients
def get_data(node, cols):
    data = pd.read_csv(f"{DIR}/{node}_network.csv", usecols=cols)
    return data[data >= 0][:250]


log_files = list(filter(lambda x: x.startswith("client") and x.endswith("network.csv"), os.listdir(f"{DIR}")))

fig, axs = plt.subplots(11 , 1, figsize=(10, 15))  # 2x2 grid of subplots

# Plot data for each client in a different subplot
for i in range(10):
    client_data = get_data(f"flclient_{i}", ["TX_Bytes", "RX_Bytes"], )
    client_data["TX_Bytes"] = client_data["TX_Bytes"] * 8 // (1024 * 1024)
    client_data["RX_Bytes"] = client_data["RX_Bytes"] * 8// (1024 * 1024)
    axs[i].plot(client_data, label=["TX_Mbps", "RX_Mbps"])
    axs[i].legend()
    axs[i].set_title(f"Client {i}")

server = get_data("server", ["TX_Bytes", "RX_Bytes"], )
server["TX_Bytes"] = server["TX_Bytes"] * 8 // (1024 * 1024)
server["RX_Bytes"] = server["RX_Bytes"] * 8// (1024 * 1024)
axs[10].plot(server, label=["TX_Bytes", "RX_Bytes"])
axs[10].set_title("Server")

plt.tight_layout()

# Show the plot
plt.show()
# plt.savefig(f"logs/plots/{DIR}_plot_network_logs.png")
