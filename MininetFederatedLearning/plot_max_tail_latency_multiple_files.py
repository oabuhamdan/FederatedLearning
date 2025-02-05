from collections import defaultdict

import pandas as pd
import matplotlib.pyplot as plt
import numpy as np

# Function to plot round time as a bar chart for multiple files
def plot_round_time(files, round_range=(1, 50)):
    # Number of files provided
    num_files = len(files)

    # Create a figure and axes for plotting
    plt.figure(figsize=(15, 6))

    # Prepare the width of the bars for multiple files (slightly offset)
    bar_width = 0.8 / num_files

    # Extract the round range
    start_round, end_round = round_range
    x_positions = np.arange(start_round, end_round + 1)  # Range of rounds to plot

    # Loop through each file and plot its data
    for i, file_path in enumerate(files):
        # Read the CSV file
        data = pd.read_csv(f"logs/50rounds/{file_path}/fl_task_client_times.csv")

        # Extract current_round and round_time columns
        rounds = data['current_round']
        # Filter the data based on the round range
        filtered_data = data[(rounds >= start_round) & (rounds <= end_round)]
        max_server_to_client_per_round = filtered_data.groupby('current_round')['server_to_client_time'].max()

        # Set the position of the bars for this file
        positions = x_positions + i * bar_width

        # Plot a bar chart for the round times
        plt.bar(positions, max_server_to_client_per_round, bar_width, label=file_path)

    # Add labels and title
    plt.xlabel('Round')
    plt.ylabel('Tail Latency (seconds)')
    plt.title(f'Comparison of Tail Latency per Round (Rounds {start_round} to {end_round})')

    # Set x-ticks to be the center of the grouped bars
    plt.xticks(x_positions + bar_width * (num_files - 1) / 2, x_positions)

    # Add legend
    plt.legend()

    # Show grid
    plt.grid(True)

    # Display the plot
    plt.show()


files = ['[withBW]_fwd_50rounds0204_192614', '[withBW]_flowsched_agfc_50rounds_0204_232844']  # Update with the actual file paths
round_range = (1, 50)
plot_round_time(files, round_range)
