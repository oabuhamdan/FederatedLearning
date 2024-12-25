import numpy as np
import matplotlib.pyplot as plt
import ast, re

def get_info(file_path):
    pattern_round_time = r'ROUND ([0-9]+) FIT (START|END) TIME ([0-9]+(?:\.[0-9]+)?)'

    with open(file_path) as f:
        file_content = f.read()

    matches_round_time = re.findall(pattern_round_time, file_content, re.DOTALL)


    round_time_info = {f"round_{round_number + 1}": {} for round_number in range(0, len(matches_round_time)//2)}
    for match in matches_round_time:
        round_number = match[0]  # First group: round number
        start_end = match[1]  # Second group: either START or END
        time = match[2]  # Third group: time
        round_time_info[f"round_{round_number}"][f"{start_end.lower()}_time"] = time

    return round_time_info


fwd_file_path = "logs/archive/fwd_with_bg"
fwd_file_path2 = "logs/[withBW]flowsched_1221_172403_mobilenet_large_10rounds_10hosts_with_bg_with_batch32"

fwd_info = get_info(f"{fwd_file_path}/server.log")
flowsched_info = get_info(f"{fwd_file_path2}/server.log")

flow_sched = [float(item["end_time"]) - float(item['start_time']) for item in flowsched_info.values()]
fwd = [float(item["end_time"]) - float(item['start_time']) for item in fwd_info.values()]

# Calculate deltas
delta_fwd = np.diff(fwd)
delta_flow_sched = np.diff(flow_sched)

# Plotting
bar_width = 0.35
x = np.arange(len(fwd))

plt.figure(figsize=(15, 8))
plt.bar(x - bar_width/2, flow_sched, width=bar_width, label='Flow Scheduling', color='crimson')
plt.bar(x + bar_width/2, fwd, width=bar_width, label='Reactive Forwarding', color='royalblue')
plt.xticks(x, [f'Round {i+1}' for i in range(len(fwd))], fontsize=20)
plt.axhline(0, color='black', linewidth=0.8, linestyle='--')
plt.ylabel('Time (s)', fontsize=20)
plt.yticks(fontsize=20)
plt.legend(fontsize=15)
plt.tight_layout()
plt.show()
