import pandas as pd
from matplotlib import pyplot as plt

def load_data(file_path):
    return pd.read_csv(file_path)

def get_x_ticks_len(df):
    return df[df['interface'] == df['interface'][0]].shape[0] // 2

def filter_data(df, qdisc_type, field):
    filtered_df = df[df['qdisc'] == qdisc_type]
    if field == 'bytes_sent':
        filtered_df['bytes_sent'] = filtered_df['bytes_sent'] / 1024 / 1024  # Convert to MBytes
    return filtered_df

def plot_data(df, qdisc_type='htb', field='bytes_sent'):
    filtered_df = filter_data(df, qdisc_type, field)
    x_ticks_len = get_x_ticks_len(df)

    plt.figure(figsize=(14, 6))
    for interface in filtered_df['interface'].unique():
        interface_data = filtered_df[filtered_df['interface'] == interface]
        plt.plot(list(range(x_ticks_len)), interface_data[field], label=interface)

    plt.title(f"{qdisc_type.upper()} - {field.replace('_', ' ').title()}")
    plt.xlabel('Time (seconds)')
    plt.xticks(range(0, x_ticks_len, 10), [str(x * 10) for x in range(0, x_ticks_len, 10)])
    plt.ylabel(field.replace('_', ' ').title())
    plt.legend(loc='upper left')
    plt.grid(True)
    plt.tight_layout()
    plt.show()

def main():
    df = load_data("./logs/[withBW]fwd_1219_151711_mobilenet_large_5rounds_10hosts_with_bg_with_batch32/monitor_qs_output.csv")
    plot_data(df, qdisc_type='htb', field='dropped')

main()
