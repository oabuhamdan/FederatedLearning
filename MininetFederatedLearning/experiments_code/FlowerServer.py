import argparse
import logging
from collections import OrderedDict

import flwr as fl
import torch
from datasets import Dataset
from flwr.common import ndarrays_to_parameters
from torch.utils.data import DataLoader
from torchvision.models import mobilenet_v3_large
from torchvision.transforms import Compose, ToTensor, Normalize

from my_server_class import MyServer, MySimpleClientManager


class CentralisedEvaluator:
    def __init__(self, dataset):
        self.device = torch.device("cpu")
        self.model = mobilenet_v3_large(weights=None, num_classes=10)
        self.test_data = "server_val_data"
        self.dataset = dataset
        self.val_dataloader = DataLoader(self.get_dataset(), batch_size=64)

    def set_parameters(self, params):
        params_dict = zip(self.model.state_dict().keys(), params)
        state_dict = OrderedDict(
            {
                k: torch.Tensor(v) if v.shape != torch.Size([]) else torch.Tensor([0])
                for k, v in params_dict
            }
        )
        self.model.load_state_dict(state_dict, strict=True)

    def get_parameters(self):
        return [val.cpu().numpy() for _, val in self.model.state_dict().items()]

    def get_dataset(self):
        img_key = "img"
        norm = Normalize(mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225])

        def apply_transforms(batch):
            pytorch_transforms = Compose([ToTensor(), norm])
            """Apply transforms to the partition from FederatedDataset."""
            batch[img_key] = [pytorch_transforms(img) for img in batch[img_key]]
            return batch

        partition = Dataset.load_from_disk(f"data/{self.dataset}/{self.test_data}")
        partition = partition.with_transform(apply_transforms)
        return partition

    def test(self):
        criterion = torch.nn.CrossEntropyLoss()
        correct, loss = 0, 0.0
        with torch.no_grad():
            for batch in self.val_dataloader:
                batch = list(batch.values())
                images, labels = batch[0], batch[1]
                outputs = self.model(images.to(self.device))
                labels = labels.to(self.device)
                loss += criterion(outputs, labels).item()
                correct += (torch.max(outputs.data, 1)[1] == labels).sum().item()
        accuracy = correct / len(self.val_dataloader.dataset)
        return loss, accuracy

    def evaluate(self, server_round, parameters, config):
        self.set_parameters(parameters)
        loss, accuracy = self.test()
        logging.info(f"Server Round: {server_round}, Accuracy: {accuracy}, Loss: {loss}")
        return loss, {"accuracy": accuracy}


def fit_config(server_round: int):
    """Return a configuration with static batch size and (local) epochs."""
    config = {
        "epochs": args.epochs,
        "batch_size": args.batch_size,
        "server_round": server_round,
    }
    return config


def main():
    evaluator = CentralisedEvaluator(args.dataset)

    def evaluate_fn(server_round, parameters, config):
        return evaluator.evaluate(server_round, parameters, config)

    # Define strategy
    strategy = fl.server.strategy.FedAvg(
        min_fit_clients=args.num_clients,
        min_evaluate_clients=args.num_clients,
        min_available_clients=args.num_clients,
        fraction_fit=1,
        fraction_evaluate=0,
        on_fit_config_fn=fit_config,
        evaluate_fn=evaluate_fn,
        initial_parameters=ndarrays_to_parameters(evaluator.get_parameters())
    )

    # Start Flower server
    server = MyServer(strategy=strategy, client_manager=MySimpleClientManager(), zmq=args.zmq,
                      my_server_address=args.server_address, onos_server_address="11.66.33.46",
                      log_path=args.log_path)
    fl.server.start_server(
        server_address=args.server_address + ":8080",
        config=fl.server.ServerConfig(num_rounds=args.rounds),
        strategy=strategy,
        server=server,
        grpc_max_concurrent_workers=args.num_clients,
    )


if __name__ == "__main__":
    # os.environ["GRPC_VERBOSITY"] = "info"
    # os.environ["GRPC_TRACE"] = "connectivity_state,server_channel,client_channel,subchannel"
    parser = argparse.ArgumentParser()
    parser.add_argument("-d", "--dataset", type=str, default="cifar10")
    parser.add_argument("-e", "--epochs", type=int, default=1)
    parser.add_argument("-r", "--rounds", type=int, default=2)
    parser.add_argument("-c", "--num-clients", type=int, default=1)
    parser.add_argument("-b", "--batch-size", type=int, default=16)
    parser.add_argument("--server-address", type=str, default="localhost")
    parser.add_argument("--log-path", type=str, default="logs/manual_testing")
    parser.add_argument("--zmq", action='store_true')
    args = parser.parse_args()
    logging.basicConfig(
        filename=f'{args.log_path}/server.log',
        level=logging.INFO,
        format="%(asctime)s.%(msecs)03d %(levelname)s %(message)s",
        datefmt='%H:%M:%S',
    )
    main()
