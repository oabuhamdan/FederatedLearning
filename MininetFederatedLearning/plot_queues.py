import pandas as pd
from matplotlib import pyplot as plt

def load_data(file_path):
    return pd.read_csv(file_path)

def get_x_ticks_len(df):
    return df[df['interface'] == df['interface'][0]].shape[0]

def filter_data(df, qdisc_type, field):
    filtered_df = df[df['qdisc'] == qdisc_type]
    if field == 'bytes_sent':
        filtered_df['bytes_sent'] = filtered_df['bytes_sent'] / 1024 / 1024  # Convert to MBytes
    return filtered_df

def plot_data(df, qdisc_type='htb', field='bytes_sent'):
    filtered_df = filter_data(df, qdisc_type, field)
    x_ticks_len = get_x_ticks_len(df)

    plt.figure(figsize=(10, 6))
    for interface in filtered_df['interface'].unique():
        interface_data = filtered_df[filtered_df['interface'] == interface]
        plt.plot(list(range(x_ticks_len)), interface_data[field], label=interface)

    plt.title(f"{qdisc_type.upper()} - {field.replace('_', ' ').title()}")
    plt.xlabel('Time (seconds)')
    plt.xticks(range(0, x_ticks_len, 5), [str(x * 5) for x in range(0, x_ticks_len, 5)])
    plt.ylabel(field.replace('_', ' ').title())
    plt.legend(loc='upper left')
    plt.grid(True)
    plt.tight_layout()
    plt.show()

def main():
    df = load_data("./logs/[withQoS]_flowsched_1226_115421/monitor_netem.csv")
    plot_data(df, qdisc_type='htb', field='overlimits')

main()
