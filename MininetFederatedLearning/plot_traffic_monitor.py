import pandas as pd
import matplotlib.pyplot as plt
import numpy as np
# Load your data into a DataFrame
def plot(file_name):
    df = pd.read_csv(f"logs/new_exp/{file_name}")

    # Define clients and server
    client_ips = sorted([ip for ip in set(df['Src IP'].tolist() + df['Dst IP'].tolist()) if ip != '10.0.0.250'])

    # Prepare data for plotting
    client_data = []
    for client_ip in client_ips:
        # Client to server (upload)
        upload_data = df[(df['Src IP'] == client_ip) & (df['Dst IP'] == '10.0.0.250')]
        upload_bytes = upload_data['Bytes'].sum() if not upload_data.empty else 0

        # Server to client (download)
        download_data = df[(df['Src IP'] == '10.0.0.250') & (df['Dst IP'] == client_ip)]
        download_bytes = download_data['Bytes'].sum() if not download_data.empty else 0

        client_data.append({
            'client': client_ip,
            'upload': upload_bytes / 1e6,  # Convert to MB
            'download': download_bytes / 1e6  # Convert to MB
        })

    # Set up the plot
    fig, ax = plt.subplots(figsize=(12, 6))

    # Set width of bars
    bar_width = 0.35
    index = np.arange(len(client_data))

    # Create bars
    upload_bars = ax.bar(index - bar_width / 2, [d['upload'] for d in client_data], bar_width,
                         label='Upload (Client to Server)', color='skyblue')
    download_bars = ax.bar(index + bar_width / 2, [d['download'] for d in client_data], bar_width,
                           label='Download (Server to Client)', color='salmon')

    # Add labels, title and legend
    ax.set_xlabel('Client IP')
    ax.set_ylabel('Data Transfer (MB)')
    ax.set_title('Network Traffic by Client')
    ax.set_xticks(index)
    ax.set_xticklabels([d['client'] for d in client_data])
    ax.legend()

    # Add value labels on top of bars
    def add_labels(bars):
        for bar in bars:
            height = bar.get_height()
            ax.annotate(f'{height:.1f}',
                        xy=(bar.get_x() + bar.get_width() / 2, height),
                        xytext=(0, 3),  # 3 points vertical offset
                        textcoords="offset points",
                        ha='center', va='bottom')

    add_labels(upload_bars)
    add_labels(download_bars)

    plt.tight_layout()
    plt.show()

plot("local/traffic_monitor_server.csv")