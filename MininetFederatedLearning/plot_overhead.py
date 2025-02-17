import pandas as pd
import matplotlib.pyplot as plt
import numpy as np

overhead = []
# Read the data from the CSV file
def plot_overhead(dir_name):
    df = pd.read_csv(f'logs/{dir_name}/flow_sched_logs/overhead.log', header=None, names=['System', 'Component', 'Overhead'])
    df_controller = df[df['System'] == 'controller']

    phase1 = df_controller[df_controller['Component'] == 'phase1']
    phase2 = df_controller[df_controller['Component'] == 'phase2']
    print(len(phase1))
    print(len(phase2))
    # Plotting the data
    plt.figure(figsize=(15,6))

    plt.plot(phase1.index, phase1['Overhead'], label='Phase1 Processing', marker='.', color='blue')
    plt.plot(phase2.index, phase2['Overhead'], label='Phase2 Processing', marker='x', color='orange')

    # Adding labels and title
    plt.xlabel('Index', fontsize=16)
    plt.ylabel('Overhead (ms)', fontsize=16)
    plt.xticks(fontsize=16)
    plt.yticks(fontsize=16)
    plt.legend(fontsize=16)

    # Display the plot
    plt.grid(True)
    plt.tight_layout()
    plt.show()

plot_overhead('50rounds_gabriel25_7/[withBW]_agmixed_small_first_50rounds_g25_70214_162242')