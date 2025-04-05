from collections import defaultdict

import pandas as pd
import matplotlib.pyplot as plt
import numpy as np

markers = "xo*s"
def plot_round_time(files, ignore_computing):
    # Create a figure with two vertically stacked subplots
    fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(3, 5.0), sharex=True)

    # Loop through each file and plot its data
    for i, file in enumerate(files):
        data1 = pd.read_csv(f"logs_new/{file[0]}/fl_task_overall_times.csv",
                            usecols=["accuracy_cen", "loss_cen", "cumulative_time"])

        if ignore_computing:
            data2 = pd.read_csv(f"logs_new/{file[0]}/fl_task_client_times.csv")
            data2['total_time'] = data2['server_to_client_time'] + data2['client_to_server_time']
            max_per_round = data2.groupby('current_round')['total_time'].max()
            cumulative = max_per_round.cumsum() / 60
        else:
            cumulative = data1['cumulative_time'] / 60

        # Plot Accuracy in top subplot
        ax1.plot(cumulative, data1["accuracy_cen"], label=file[1], marker=markers[i], markevery=5)

        # Plot Loss in bottom subplot
        ax2.plot(cumulative, data1["loss_cen"], label=file[1], marker=markers[i], markevery=5)

    # Add labels and title for Accuracy subplot
    # ax1.set_ylabel("Evaluation Accuracy", fontsize=14)
    ax1.tick_params(axis='y', labelsize=10)
    ax1.grid(True)
    ax1.set_ylim(0.3, )

    # Add labels and title for Loss subplot
    ax2.set_xlabel('Training Duration (Minute)', fontsize=10)
    # ax2.set_ylabel("Training Loss", fontsize=14)
    ax2.tick_params(axis='y', labelsize=10)
    ax2.tick_params(axis='x', labelsize=10)
    ax2.grid(True)
    ax2.set_ylim(0.55, 1.5)
    # Set x-axis ticks
    ax2.set_xticks([x for x in range(0, 70, 10)])
    ax2.set_xticklabels([str(x) for x in range(0, 70, 10)])

    # Add a single legend
    # handles, labels = ax1.get_legend_handles_labels()
    handles, labels = ax1.get_legend_handles_labels()
    legend = fig.legend(handles, labels,fontsize=10, bbox_to_anchor=(0.93, 0.65))
    legend.get_frame().set_alpha(None)

    # Adjust layout
    plt.tight_layout()

    # Display the plot
    # plt.show()
    # Uncomment to save
    plt.savefig(f"logs/plots/gabriel25_v7_accuracy_loss.pdf", bbox_inches='tight')


files = [
         ('gabriel25_v7/fwd', "RFWD"),
         ('gabriel25_v7/agfc', "FreeCap"),
         ('gabriel25_v7/agfs', "FairShare"),
         ('gabriel25_v7/agmixed', "Hybrid"),
         ]
plot_round_time(files,True)
