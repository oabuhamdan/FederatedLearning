import pandas as pd
import matplotlib.pyplot as plt
import os

DIR = "logs/archive/fwd_without_bg_traffic"


# Load data for all 4 clients
def get_data(node, cols):
    data = pd.read_csv(f"{DIR}/{node}_network.csv", usecols=cols)
    return data[data >= 0]


log_files = list(filter(lambda x: x.startswith("client") and x.endswith("network.csv"), os.listdir(f"{DIR}")))
lngth = min(len(log_files), 3)

fig, axs = plt.subplots(lngth , 1, figsize=(12, 6))  # 2x2 grid of subplots

# Plot data for each client in a different subplot
for i in range(lngth - 1):
    client_data = get_data(f"client_{i + 1}", ["TX_Bytes", "RX_Bytes"], )
    client_data["TX_Bytes"] = client_data["TX_Bytes"] * 8 // (1024 * 1024)
    client_data["RX_Bytes"] = client_data["RX_Bytes"] * 8// (1024 * 1024)
    axs[i].plot(client_data, label=["TX_Mbps", "RX_Mbps"])
    axs[i].legend()
    axs[i].set_title(f"Client {i}")

server = get_data("server", ["TX_Bytes", "RX_Bytes"], )
server["TX_Bytes"] = server["TX_Bytes"] * 8 // (1024 * 1024)
server["RX_Bytes"] = server["RX_Bytes"] * 8// (1024 * 1024)
axs[lngth - 1].plot(server, label=["TX_Bytes", "RX_Bytes"])
axs[lngth - 1].set_title("Server")

plt.tight_layout()

# Show the plot
plt.show()
# plt.savefig(f"logs/plots/{DIR}_plot_network_logs.png")
