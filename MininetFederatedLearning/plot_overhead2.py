import numpy as np
import pandas as pd
import matplotlib.pyplot as plt


# Load data from three different files

def get_df_combined(dirnames):
    df_dict = {}
    for dirname in dirnames:
        df = pd.read_csv(
            f'/home/osama/PycharmProjects/FederatedLearning/MininetFederatedLearning/logs_new/{dirname[0]}/flow_sched_logs/overhead.log',
            header=None,
            names=['System', 'Component', 'Overhead'])
        df_controller = df[df['System'] == 'controller']

        phase1 = df_controller[df_controller['Component'] == 'phase1']['Overhead'].astype(int).replace(0, 1)
        phase2 = df_controller[df_controller['Component'] == 'phase2']['Overhead'].astype(int).replace(0, 1)

        df_dict.update({f"{dirname[0]}_phase1": phase1.sample(n=min(2000, len(phase1))).nlargest(25).sample(
            15).reset_index(drop=True)})
        df_dict.update({f"{dirname[0]}_phase2": phase2.sample(n=min(2000, len(phase2))).nlargest(25).sample(
            15).reset_index(drop=True)})

    # Create a new dataframe to combine all three files' data
    return pd.DataFrame(df_dict)


def plot(df_combined, dirnames):
    colors_shades = [
        ('royalblue', 'skyblue'),
        ('crimson', 'salmon'),
        # ('peru', 'linen'),
        ('seagreen', 'lightgreen'),
    ]
    legend = []
    # Plot the stacked bar chart
    fig, ax = plt.subplots(figsize=(10, 6))
    x_positions = np.arange(len(dirnames)) * 2  # Positions for each sample group
    for i, dirname in enumerate(dirnames):
        df_combined[[f"{dirname[0]}_phase1", f"{dirname[0]}_phase2"]].plot(kind='bar', stacked=True, ax=ax,
                                                                           position=i, width=0.28,
                                                                           color=colors_shades[i])
        legend.extend([f"{dirname[1]} Phase1", f"{dirname[1]} Phase2"])

    # Set the x-axis labels (for comparison)
    # ax.set_xticks(range(len(df_combined)))
    # ax.set_xticklabels([f'Point {i+1}' for i in range(len(df_combined))])

    # Set axis labels and title
    ax.set_ylabel('Overhead (ms)', fontsize=20)
    ax.set_xlabel('Samples', fontsize=20)
    plt.xticks(fontsize=20, ticks=[x for x in range(0, 15)], labels=[str(x) for x in range(1, 16)])
    plt.yticks(fontsize=20)
    plt.xlim(-1, 14.6)

    # Show the plot
    plt.legend(legend, loc='upper left', ncol=3, fontsize=20)
    plt.tight_layout()
    # plt.show()
    plt.savefig("/home/osama/PycharmProjects/FederatedLearning/MininetFederatedLearning/logs/plots/overhead2.pdf",
                bbox_inches='tight')


df_combined = get_df_combined([
    ('gabriel50_v0/agfs', "E3"),
    ('gabriel25_v7/agfs', "E2"),
    ('gabriel15_v9/agfs', "E1")
])
plot(df_combined, [
    ('gabriel50_v0/agfs', "E3"),
    ('gabriel25_v7/agfs', "E2"),
    ('gabriel15_v9/agfs', "E1")
])
