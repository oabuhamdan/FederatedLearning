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
    && rm -rf /var/lib/apt/lists/*

# Set the working directory inside the container
WORKDIR /app

# Copy the requirements file into the container
COPY requirement.txt .
COPY FlowerClient.py .
COPY FlowerServer.py .
COPY network_stats.sh .

# Create a virtual environment
RUN python3 -m venv venv

# Upgrade pip and install dependencies in the virtual environment
RUN ./venv/bin/pip install --upgrade pip
RUN ./venv/bin/pip install -r requirement.txt
RUN ./venv/bin/pip install torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cpu

# Set the command to start an interactive shell
CMD ["/bin/bash"]