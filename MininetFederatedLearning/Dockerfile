# Use the official Ubuntu image as a base
FROM ubuntu:latest

# Set environment variables to avoid prompts during package installation
ENV DEBIAN_FRONTEND=noninteractive

# Update the package list and install Python and venv
RUN apt update && apt install -y \
    python3 \
    python3-venv \
    python3-pip \
    python3-dev \
    iperf3 \
    net-tools \
    iputils-ping \
    iproute2 \
    nano \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Set the working directory inside the container
WORKDIR /app

# Copy the requirements file into the container
COPY requirement.txt .

# Create a virtual environment and install dependencies
RUN python3 -m venv venv && \
    ./venv/bin/pip install --upgrade pip && \
    ./venv/bin/pip install -r requirement.txt && \
    ./venv/bin/pip install torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cpu

# Now copy the rest of the application files
COPY experiments_code/FlowerClient.py .
COPY experiments_code/FlowerServer.py .
COPY experiments_code/network_stats.sh .
COPY experiments_code/my_server_class.py .
COPY experiments_code/start_iperf.sh .

#COPY venv/lib/python3.10/site-packages/flwr/server/server.py venv/lib/python3.12/site-packages/flwr/server/server.py
COPY venv/lib/python3.10/site-packages/flwr/server/app.py venv/lib/python3.12/site-packages/flwr/server/app.py

# Set the command to start an interactive shell
CMD ["/bin/bash"]