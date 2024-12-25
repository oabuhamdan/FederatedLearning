import pandas as pd
from matplotlib import pyplot as plt

def load_data(file_path):
    df = pd.read_csv(file_path)
    df['used'] = df['used'] // 1000000
    df['free'] = df['free'] // 1000000
    df.dropna(inplace=True)
    return df

def get_x_ticks_len(df):
    return df[df['link'] == df['link'][0]].shape[0]

def filter_data(df, direction):
    if direction == "c2s":
        return df[df['link'].str.contains(r"^020[0-9] -> 010[0-9]", regex=True)]
    elif direction == "s2c":
        return df[df['link'].str.contains(r"^010[0-9] -> 020[0-9]", regex=True)]
    return None

def plot_data(df, field, direction):
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
    plt.show()

def main():
    df = load_data("./logs/archive/fwd_with_bg/linkinfo.csv")
    plot_data(df, 'used', "c2s")

main()
