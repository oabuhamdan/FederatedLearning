import pandas as pd
import matplotlib.pyplot as plt


# Load data for all 4 clients
def get_data(node, cols):
    data = pd.read_csv(f"logs/wifi_exp_cifar10_12_49/{node}_network.csv", usecols=cols)
    return data


# client_0.plot(figsize=(10, 4))
# Create subplots
fig, axs = plt.subplots(4, 1, figsize=(12, 8))  # 2x2 grid of subplots

# Plot data for each client in a different subplot
client0_data = get_data("client_0", ["TX_Bytes", "RX_Bytes"], )
axs[0].plot(client0_data, label=["TX_Bytes", "RX_Bytes"])
axs[0].legend()
axs[0].set_title("Client 0")

client1_data = get_data("client_1", ["TX_Bytes", "RX_Bytes"], )
axs[1].plot(client1_data, label=["TX_Bytes", "RX_Bytes"])
axs[1].set_title("Client 1")

client2_data = get_data("client_2", ["TX_Bytes", "RX_Bytes"], )
axs[2].plot(client2_data, label=["TX_Bytes", "RX_Bytes"])
axs[2].set_title("Client 2")

server = get_data("server", ["TX_Bytes", "RX_Bytes"], )
axs[3].plot(server, label=["TX_Bytes", "RX_Bytes"])
axs[3].set_title("Server")

plt.tight_layout()

# Show the plot
plt.show()
