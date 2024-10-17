import pandas as pd
import matplotlib.pyplot as plt
import os

DIR = "fwd_resnet18_5rounds_10hosts_without_bg"


# Load data for all 4 clients
def get_data(node, cols):
    data = pd.read_csv(f"logs/{DIR}/{node}_network.csv", usecols=cols)
    return data[data >= 0]


log_files = list(filter(lambda x: x.startswith("client") and x.endswith("network.csv"), os.listdir(f"logs/{DIR}")))
lngth = min(len(log_files), 5)

fig, axs = plt.subplots(lngth + 1, 1, figsize=(12, 8))  # 2x2 grid of subplots

# Plot data for each client in a different subplot
for i in range(lngth):
    client_data = get_data(f"client_{i}", ["TX_Bytes", "RX_Bytes"], )
    axs[i].plot(client_data, label=["TX_Bytes", "RX_Bytes"])
    axs[i].legend()
    axs[i].set_title(f"Client {i}")

server = get_data("server", ["TX_Bytes", "RX_Bytes"], )
axs[lngth].plot(server, label=["TX_Bytes", "RX_Bytes"])
axs[lngth].set_title("Server")

plt.tight_layout()

# Show the plot
# plt.show()
plt.savefig(f"logs/plots/{DIR}_plot_network_logs.png")
