from collections import defaultdict

import pandas as pd
import matplotlib.pyplot as plt
import numpy as np

# Function to plot round time as a bar chart for multiple files
def plot_round_time(files, round_range=(1, 50), direction="s2c"):
    # Number of files provided
    num_files = len(files)

    # Create a figure and axes for plotting
    plt.figure(figsize=(5, 4))

    # Prepare the width of the bars for multiple files (slightly offset)
    bar_width = 0.8 / num_files

    # Extract the round range
    start_round, end_round = round_range
    x_positions = np.arange(start_round, end_round + 1)  # Range of rounds to plot

    # Loop through each file and plot its data
    for i, file_path in enumerate(files):
        # Read the CSV file
        data = pd.read_csv(f"logs_new/{file_path[0]}/fl_task_client_times.csv")

        # Extract current_round and round_time columns
        rounds = data['current_round']
        # Filter the data based on the round range
        filtered_data = data[(rounds >= start_round) & (rounds <= end_round)]
        if direction == "s2c":
            max_per_round = filtered_data.groupby('current_round')['server_to_client_time'].max()
        elif direction == "c2s":
            max_per_round = filtered_data.groupby('current_round')['client_to_server_time'].max()
        elif direction == "both":
            filtered_data['total_time'] = filtered_data['server_to_client_time'] + filtered_data[
                'client_to_server_time']
            max_per_round = filtered_data.groupby('current_round')['total_time'].max()
        else:
            raise ValueError
        # Set the position of the bars for this file
        positions = x_positions + i * bar_width

        # Plot a bar chart for the round times
        plt.bar(positions, max_per_round, bar_width, label=file_path[1])

    # Add labels and title
    plt.xlabel('Round', fontsize=16)
    plt.ylabel('Round Time (s)', fontsize=16)
    # plt.title(f'Comparison of Tail Latency per Round ({direction.upper()} - Rounds {start_round} to {end_round})')

    # Set x-ticks to be the center of the grouped bars
    plt.xticks(x_positions + bar_width * (num_files - 1) / 2,[str(x) for x in range(1,6)], fontsize=16)
    plt.yticks(fontsize=16)
    # Add legend
    plt.legend(fontsize=16, ncol=2)

    # Show grid
    plt.grid(True, linestyle='--', alpha=0.7)
    plt.tight_layout()

    # Display the plot
    # plt.show()
    plt.savefig("logs/plots/gabriel_50v0_all_round_time.pdf", bbox_inches='tight')


files =  [
         ('gabriel50_v0/fwd', "RFWD"),
         ('gabriel50_v0/agfc', "FreeCap"),
         ('gabriel50_v0/agfs', "FairShare"),
         ('gabriel50_v0/agmixed', "Hybrid"),
         ]
round_range = (13, 17)
# round_range = (25, 35)
plot_round_time(files, round_range, "both")
