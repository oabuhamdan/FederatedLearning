#!/usr/bin/env python3
import os
import subprocess
import sys
import time
import logging
import numpy as np
from pathlib import Path

def setup_logging(log_path):
    """Set up basic logging to file."""
    Path(log_path).parent.mkdir(parents=True, exist_ok=True)
    logging.basicConfig(
        filename=log_path,
        level=logging.INFO,
        format='%(asctime)s: %(message)s',
        datefmt='%Y-%m-%d %H:%M:%S'
    )
    return logging.getLogger()


def run_server(port, log_path):
    logger = setup_logging(log_path)
    logger.info(f"Starting iperf server on port {port}")
    retires = 0
    while retires < 5:
        process = subprocess.Popen(
            ["iperf3", "-s", "-i", "10", "-p", str(port)],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        time.sleep(0.5)
        if process.poll() is None:  # still running
            logger.info(f"Server started successfully with PID {process.pid}...")
            break
        else:
            logger.info("Server failed to start .. Retrying.")
            retires += 1


def run_client(dst_ip, port, rate_mean, rate_std, time_mean, time_std, parallel, log_path):
    logger = setup_logging(log_path)
    logger.info(f"Starting client to {dst_ip}:{port} (μ_rate={rate_mean}Mbps, μ_time={time_mean}s)")

    np.random.seed(123)
    while True:
        try:
            rate = max(1, int(np.random.normal(int(rate_mean), int(rate_std))))
            interval = max(1, int(np.random.normal(int(time_mean), int(time_std))))
            logger.info(f"Traffic: {rate} Mbps for {interval} seconds")
            subprocess.run([
                "iperf3", "-c", dst_ip, "-i", "5", "-t", str(interval),
                "-b", f"{rate}M", "-p", str(port), "-P", str(parallel),
                "--connect-timeout", "500"
            ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)

            time.sleep(0.5)  # Small delay between runs
        except subprocess.CalledProcessError as e:
            error_output = e.stderr.decode('utf-8')
            logger.error(f"iperf3 client failed: {error_output}")
        except KeyboardInterrupt:
            logger.info("Client stopped by user")
            break
        except Exception as e:
            logger.error(f"Client error: {e}")

def run_detached_process(*args):
    if os.fork() > 0:
        os._exit(0)  # Parent exits here, leaving the child process running
    os.setsid()
    run_client(*args)

def main():
    args = sys.argv

    if args[1] == 'server':
        run_server(*args[2:])
    else:  # client mode
        run_detached_process(*args[2:])


if __name__ == "__main__":
    main()
