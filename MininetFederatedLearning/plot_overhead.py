import pandas as pd
import matplotlib.pyplot as plt
import numpy as np
from matplotlib.lines import Line2D
from scipy.signal import find_peaks

overhead = []
# Read the data from the CSV file
def plot_overhead(dir_names):
    plt.figure(figsize=(6, 4))
    for dirname in dir_names:
        df = pd.read_csv(f'logs_new/{dirname[0]}/flow_sched_logs/overhead.log', header=None, names=['System', 'Component', 'Overhead'])
        df_controller = df[df['System'] == 'controller']

        phase1 = df_controller[df_controller['Component'] == 'phase1']
        phase2 = df_controller[df_controller['Component'] == 'phase2']
        print(len(phase1))
        print(len(phase2))
        # Plotting the data

        plot_phase(phase1['Overhead'], label=f'{dirname[1]} Phase1', marker='.')
        plot_phase(phase2['Overhead'], label=f'{dirname[1]} Phase2', marker='x')
        # plt.plot(list(range(500)), phase2['Overhead'], label='Phase2', marker='x', color='orange', markersize=10)

    # Adding labels and title
    plt.xlabel('Index', fontsize=16)
    plt.ylabel('Overhead (ms)', fontsize=16)
    plt.xticks(fontsize=16)
    plt.yticks(fontsize=16)
    # plt.legend([phase1_legend, phase2_legend], ['Phase1 Processing', 'Phase2 Processing'], fontsize=16)
    plt.legend(fontsize=16)
    # Display the plot
    plt.grid(True)
    plt.tight_layout()
    plt.show()
    # plt.savefig(f'logs/plots/overhead_25_7.pdf', bbox_inches='tight')


def plot_phase(phase, **kwargs):
    x =phase.index
    phase_peaks = find_peaks(phase, distance=1, )[0][:100]
    plt.scatter(list(range(len(phase_peaks))), list(phase.iloc[phase_peaks]),  label=kwargs['label'])
    # plt.plot(x[phase_peaks], phase.iloc[phase_peaks], color=kwargs['color'], linestyle='-', zorder=4, label=kwargs['label'])
    # phase1_legend = Line2D([0], [0],  marker=kwargs['marker'], markersize=12, c=kwargs['color'], label=kwargs['label'],
    #                        markeredgecolor=kwargs['color'], markerfacecolor=kwargs['color'], linestyle='-')
    # return phase1_legend


plot_overhead([
    ('gabriel50_v0/agfs', "E3"),
    # ('gabriel25_v7/agfs', "E2"),
    ('gabriel15_v9/agfs', "E1")
])