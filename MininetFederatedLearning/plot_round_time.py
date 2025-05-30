import pandas as pd
import matplotlib.pyplot as plt
import numpy as np


# Function to plot round time as a bar chart for multiple files
def plot_round_time(files, round_range=(1, 50)):
    # Number of files provided
    num_files = len(files)

    # Create a figure and axes for plotting
    plt.figure(figsize=(18, 6))

    # Prepare the width of the bars for multiple files (slightly offset)
    bar_width = 0.8 / num_files

    # Extract the round range
    start_round, end_round = round_range
    x_positions = np.arange(start_round, end_round + 1)  # Range of rounds to plot

    # Loop through each file and plot its data
    for i, file_path in enumerate(files):
        # Read the CSV file
        data = pd.read_csv(f"logs/{file_path}/fl_task_overall_times.csv")

        # Extract current_round and round_time columns
        rounds = data['current_round']
        # Filter the data based on the round range
        filtered_data = data[(rounds >= start_round) & (rounds <= end_round)]

        # Set the position of the bars for this file
        positions = x_positions + i * bar_width
        # Plot a bar chart for the round times
        plt.bar(positions, filtered_data['round_time'], bar_width, label=file_path)

    # Add labels and title
    plt.xlabel('Round')
    plt.ylabel('Round Time (seconds)')
    plt.title(f'Comparison of Round Times Across Files (Rounds {start_round} to {end_round})')

    # Set x-ticks to be the center of the grouped bars
    plt.xticks(x_positions + bar_width * (num_files - 1) / 2, x_positions)

    # Add legend
    plt.legend()

    # Show grid
    plt.grid(True)
    plt.tight_layout()
    # Display the plot
    plt.show()


files = ['50rounds_gabriel50_v0/fwd2_r50_g50_v0_0308_191653', '50rounds_gabriel50_v0/agmixed2_r50_g50_v0_0309_014647']
round_range = (1,29)
plot_round_time(files, round_range)
