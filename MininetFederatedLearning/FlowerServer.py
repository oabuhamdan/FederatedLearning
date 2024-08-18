import logging
from typing import List, Tuple
import argparse
import flwr as fl
from flwr.common import Metrics


def weighted_average(metrics: List[Tuple[int, Metrics]]) -> Metrics:
    """This function averages teh `accuracy` metric sent by the clients in a `evaluate`
    stage (i.e. clients received the global model and evaluate it on their local
    validation sets)."""
    # Multiply accuracy of each client by number of examples used
    accuracies = [num_examples * m["accuracy"] for num_examples, m in metrics]
    examples = [num_examples for num_examples, _ in metrics]

    # Aggregate and return custom metric (weighted average)
    return {"accuracy": sum(accuracies) / sum(examples)}


def fit_config(server_round: int):
    """Return a configuration with static batch size and (local) epochs."""
    config = {
        "epochs": args.epochs,  # Number of local epochs done by clients
        "batch_size": args.batch_size,  # Batch size to use by clients during fit()
    }
    return config


def main():
    # Define strategy
    strategy = fl.server.strategy.FedAvg(
        min_fit_clients=args.num_clients,
        min_evaluate_clients=args.num_clients,
        min_available_clients=args.num_clients,
        fraction_fit=1,
        fraction_evaluate=1,
        on_fit_config_fn=fit_config,
        evaluate_metrics_aggregation_fn=weighted_average,
    )

    # Start Flower server
    history = fl.server.start_server(
        server_address="10.0.0.100:8080",
        config=fl.server.ServerConfig(num_rounds=args.rounds),
        strategy=strategy,
        grpc_max_concurrent_workers=args.num_clients
    )

    logging.info(f"Metrics Distributed {history.metrics_distributed}")
    logging.info(f"Metrics Distributed Fit {history.metrics_distributed_fit}")
    logging.info(f"Metrics Centralised {history.metrics_centralized}")
    logging.info(f"Losses Distributed {history.losses_distributed}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("-d", "--dataset", type=str, default="cifar10")
    parser.add_argument("-e", "--epochs", type=int, default=2)
    parser.add_argument("-r", "--rounds", type=int, default=2)
    parser.add_argument("-c", "--num-clients", type=int, default=2)
    parser.add_argument("-b", "--batch-size", type=int, default=2)
    parser.add_argument("--server_address", type=str, default="10.0.0.10:8080")
    args = parser.parse_args()
    logging.basicConfig(
        filename=f'logs/{args.dataset}/server.log',  # Log file name
        level=logging.DEBUG,  # Minimum log level to capture (DEBUG, INFO, WARNING, ERROR, CRITICAL)
        format='%(message)s'  # Log message format
    )
    main()
