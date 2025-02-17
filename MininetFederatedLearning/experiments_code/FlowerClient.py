import argparse
import json
import logging
import os
import socket
import time
import timeit
from collections import OrderedDict

import flwr as fl
import psutil
import torch
import zmq
from flwr.common import Config, Scalar
from flwr_datasets.federated_dataset import Dataset
from torch.utils.data import DataLoader
from torchvision.models import mobilenet_v3_large
from torchvision.transforms import Compose, Normalize, ToTensor
from tqdm import tqdm

torch.set_num_threads(1)


def send_data_to_server(data):
    model_update = {"sender_id": sender_id, "message_type": 3, "message": data, "time_ms": round(time.time() * 1000)}
    message = json.dumps(model_update)
    zeromq_socket.send_string(message)


def train(net, trainloader, optimizer, epochs, device):
    """Train the model on the training set."""
    criterion = torch.nn.CrossEntropyLoss()
    total_steps = len(trainloader)
    for epoch in range(epochs):
        for i, batch in enumerate(trainloader):
            batch = list(batch.values())
            images, labels = batch[0], batch[1]
            optimizer.zero_grad()
            criterion(net(images.to(device)), labels.to(device)).backward()
            optimizer.step()
            if i % 100 == 0:
                logging.info(f"Step {i} Epoch {epoch}")
            # update the controller after 80% of the steps are done
            if args.zmq and epoch == (epochs - 1) and i == round(total_steps * 0.85):
                send_data_to_server("client_to_server_path")


def test(net, testloader, device):
    """Validate the model on the test set."""
    criterion = torch.nn.CrossEntropyLoss()
    correct, loss = 0, 0.0
    with torch.no_grad():
        for batch in tqdm(testloader):
            batch = list(batch.values())
            images, labels = batch[0], batch[1]
            outputs = net(images.to(device))
            labels = labels.to(device)
            loss += criterion(outputs, labels).item()
            correct += (torch.max(outputs.data, 1)[1] == labels).sum().item()
    accuracy = correct / len(testloader.dataset)
    return loss, accuracy


# Flower client, adapted from Pytorch quickstart/simulation example
class FlowerClient(fl.client.NumPyClient):

    def __init__(self, trainset, cid):
        self.trainset = trainset
        self.cid = cid
        self.model = mobilenet_v3_large(weights=None, num_classes=10)
        # Determine device
        self.device = torch.device("cuda:0" if torch.cuda.is_available() else "cpu")
        self.model.to(self.device)  # send model to device

    def set_parameters(self, params):
        """Set model weights from a list of NumPy ndarrays."""
        params_dict = zip(self.model.state_dict().keys(), params)
        state_dict = OrderedDict(
            {
                k: torch.Tensor(v) if v.shape != torch.Size([]) else torch.Tensor([0])
                for k, v in params_dict
            }
        )
        self.model.load_state_dict(state_dict, strict=True)

    def get_parameters(self, config):
        return [val.cpu().numpy() for _, val in self.model.state_dict().items()]

    def fit(self, parameters, config):
        try:
            computing_start_time = timeit.default_timer()
            self.set_parameters(parameters)
            # Read hyperparameters from config set by the server
            batch, epochs = config["batch_size"], config["epochs"]
            # Construct dataloader
            trainloader = DataLoader(self.trainset, batch_size=batch, shuffle=True)
            # Define optimizer
            optimizer = torch.optim.SGD(self.model.parameters(), lr=0.01, momentum=0.9)
            # Train
            train(self.model, trainloader, optimizer, epochs=epochs, device=self.device)
            computing_finish_time = timeit.default_timer()
            # Return local model and statistics
            parameters = self.get_parameters({})
            metrics = {"client": self.cid, "computing_start_time": computing_start_time,
                       "computing_finish_time": computing_finish_time}
            return parameters, len(trainloader.dataset), metrics
        except Exception as e:
            logging.error(f"ERROR in FIT {e}")
            raise e

    def get_properties(self, config: Config) -> dict[str, Scalar]:
        try:
            interface = next(filter(lambda x: x.startswith("flclient"), psutil.net_if_addrs().keys()))
            addrs = psutil.net_if_addrs().get(interface)
            ip_address = next((addr.address for addr in addrs if addr.family == socket.AF_INET), None)
            mac_address = next((addr.address for addr in addrs if addr.family == socket.AF_PACKET), None)
            return {"cid": args.cid, "device": str(self.device), "ip": ip_address, "mac": mac_address}
        except Exception as e:
            logging.error(f"ERROR in get_properties {e}")


def get_dataset():
    img_key = "img"
    norm = Normalize(mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225])

    def apply_transforms(batch):
        pytorch_transforms = Compose([ToTensor(), norm])
        """Apply transforms to the partition from FederatedDataset."""
        batch[img_key] = [pytorch_transforms(img) for img in batch[img_key]]
        return batch

    # Define the transforms
    partition = Dataset.load_from_disk(f"data/{args.dataset}/client_{args.cid % 10}_data")
    partition = partition.with_transform(apply_transforms)
    return partition


def main():
    # Download dataset and partition it
    trainsets = get_dataset()
    # Start Flower client setting its associated data partition
    fl.client.start_client(
        server_address=args.fl_server + ":8080",
        client=FlowerClient(
            trainset=trainsets, cid=args.cid
        ).to_client(),
    )


def init_zmq():
    global zeromq_socket, sender_id
    context = zmq.Context()
    zeromq_socket = context.socket(zmq.PUSH)
    zeromq_socket.connect(f"tcp://{args.fl_server}:5555")
    sender_id = f"{args.cid}"


if __name__ == "__main__":
    # os.environ["GRPC_VERBOSITY"] = "debug"
    # os.environ["GRPC_TRACE"] = "connectivity_state,server_channel,client_channel,subchannel"
    parser = argparse.ArgumentParser()
    parser.add_argument("-d", "--dataset", type=str, default="cifar10")
    parser.add_argument("-c", "--cid", type=int, default=0)
    parser.add_argument("--fl-server", type=str, default="localhost")
    parser.add_argument("--log-path", type=str, default="logs/manual_testing")
    parser.add_argument("--zmq", action='store_true')
    args = parser.parse_args()
    logging.basicConfig(
        filename=f'{args.log_path}/client_{args.cid}.log',  # Log file name
        level=logging.INFO,  # Minimum log level to capture (DEBUG, INFO, WARNING, ERROR, CRITICAL)
        format="%(asctime)s.%(msecs)03d %(levelname)s %(message)s",
        datefmt='%H:%M:%S',
    )
    if args.zmq:
        zeromq_socket, sender_id = None, None
        init_zmq()

    main()
