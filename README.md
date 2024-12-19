## Federated Learning Emulation with Mininet
#### Project Overview

This project emulates Federated Learning (FL) tasks using Mininet, a network emulator. It aims to simulate a dynamic network with Open vSwitch (OVS) switches, multiple Federated Learning clients, and background clients. The clients are run as Docker instances, enabling the emulation of different hardware capabilities across the network.

The code enables seamless execution of Federated Learning tasks and data collection. By configuring the appropriate parameters, users can run the entire emulation process and gather network statistics for analysis.
Key Features
- Dynamic Emulated Network: Uses Mininet to simulate a network with OVS switches, FL clients, and background clients.
- Dockerized Clients: Each FL and BG client runs in a Docker container, providing flexibility to simulate different hardware configurations.
- Monitoring and Visualization: Includes scripts to monitor and visualize network statistics, making it easier to understand the performance and behavior of the emulated system.
- Plug-and-Play Code: The experiments_code/main.py script allows for easy setup and execution of the entire emulation with customizable parameters.

#### Prerequisites
Containernet tool should be installed to your system.

#### Setup & Usage
1- clone the code
```
git clone https://github.com/oabuhamdan/FederatedLearning.git
```

2- Build the docker image
```
cd MininetFederatedLearning/
sudo docker build -t fl_mininet_image:latest .
```

3- Create venv and install requirements
```
python3 -m venv venv
source venv/bin/activate
pip -r install requirements.txt
```

4- Prepare dataset
```
python3 prepare_dataset.py
```

5- Run the code 
```
cd experiments_code
sudo python3 main.py
```
