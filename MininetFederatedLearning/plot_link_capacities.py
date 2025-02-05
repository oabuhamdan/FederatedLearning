import pandas as pd
from matplotlib import pyplot as plt

def load_data(exp):
    column_names = ['link', 'free', 'used']  # Adjust according to your data
    df = pd.read_csv(f"logs/{exp}/flow_sched_logs/link_util.csv.log", header=None)
    df.columns = column_names
    df['used'] = df['used'] // 1000000
    df['free'] = df['free'] // 1000000
    df.dropna(inplace=True)
    return df

def get_x_ticks_len(df):
    return df[df['link'] == df['link'][0]].shape[0]

def filter_data(df, direction):
    if direction == "c2s":
        return df[df['link'].str.contains(r"^100[0-9] -> 1001", regex=True)]
    elif direction == "s2c":
        return df[df['link'].str.contains(r"1001 -> 100[0-9]", regex=True)]
    return None

def plot_data(df, field, direction, title):
    markers = ['o', 's', 'x', '^', 'v', '*', 'D', '+', '|', '_']
    x_ticks_len = get_x_ticks_len(df)
    filtered = filter_data(df, direction)

    if filtered is None:
        return

    plt.figure(figsize=(12, 6))
    for i, link in enumerate(filtered['link'].unique()):
        link_data = df[df['link'] == link]
        plt.plot(range(x_ticks_len), link_data[field], label=link, marker=markers[i % len(markers)])

    plt.xticks(range(0, x_ticks_len, 5), [str(x * 5) for x in range(0, x_ticks_len, 5)])
    plt.title(f"{field.capitalize()} for All Links")
    plt.xlabel('Time (seconds)')
    plt.ylabel(field.replace('_', ' ').title())
    plt.legend(loc='upper left')
    plt.grid(True)
    plt.tight_layout()
    # plt.show()
    plt.savefig(f"logs/plots/{title}_link_capacities_{direction}.png")

def main():
    file = "[withBW]_flowsched_0128_105535"
    df = load_data(file)
    plot_data(df, 'used', "s2c", file)

main()
