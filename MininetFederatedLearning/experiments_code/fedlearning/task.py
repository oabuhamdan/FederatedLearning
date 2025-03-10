import threading
import time
from collections import OrderedDict
from enum import Enum

import torch
import zmq
from datasets import Dataset
from flwr.common import GetPropertiesIns
from flwr.server import SimpleClientManager
from torchvision.transforms import Compose, ToTensor, Normalize
import concurrent.futures


def get_dataset(partition, dataset="cifar10", img_key="img"):
    norm = Normalize(mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225])

    def apply_transforms(batch):
        pytorch_transforms = Compose([ToTensor(), norm])
        """Apply transforms to the partition from FederatedDataset."""
        batch[img_key] = [pytorch_transforms(img) for img in batch[img_key]]
        return batch

    partition = Dataset.load_from_disk(f"data/{dataset}/{partition}")
    partition = partition.with_transform(apply_transforms)
    return partition


def get_weights(net):
    return [val.cpu().numpy() for _, val in net.state_dict().items()]


def set_weights(net, parameters):
    params_dict = zip(net.state_dict().keys(), parameters)
    state_dict = OrderedDict({k: torch.tensor(v) for k, v in params_dict})
    net.load_state_dict(state_dict, strict=True)


class ZMQHandler:
    class MessageType(Enum):
        UPDATE_DIRECTORY = 1
        SERVER_TO_CLIENTS = 2
        CLIENT_TO_SERVER = 3

    def __init__(self, onos_server, fl_server_address):
        self.onos_server_address = onos_server
        self.fl_server_address = fl_server_address
        self.snd_socket = None
        self.recv_socket = None

        self.init_zmq()
        threading.Thread(target=self.zmq_bridge, args=(self.snd_socket, self.recv_socket,),
                         daemon=True).start()

    def init_zmq(self):
        context = zmq.Context()
        self.recv_socket = context.socket(zmq.PULL)
        self.recv_socket.bind(f"tcp://{self.fl_server_address}:5555")
        self.snd_socket = context.socket(zmq.PUSH)
        self.snd_socket.connect(f"tcp://{self.onos_server_address}:5555")

    def send_data_to_server(self, message_type: MessageType, message):
        model_update = {"sender_id": "server", "message_type": message_type.value, "message": message,
                        "time_ms": round(time.time() * 1000)}
        self.snd_socket.send_json(model_update)

    @staticmethod
    def zmq_bridge(snd_socket, recv_socket):
        while True:
            snd_socket.send(recv_socket.recv())


class MySimpleClientManager(SimpleClientManager):
    def __init__(self, zmq_handler, logger) -> None:
        super().__init__()
        self.clients_info = {}
        self.zmq_handler = zmq_handler
        self.logger = logger

    def sample(self, num_clients, min_num_clients=None, criterion=None):
        sampled_clients = super().sample(num_clients, min_num_clients, criterion)
        new_clients = list(filter(lambda c: c.cid not in self.clients_info, sampled_clients))

        with concurrent.futures.ThreadPoolExecutor() as executor:
            submitted_fs = {
                executor.submit(self.get_props, client_proxy) for client_proxy in new_clients
            }

            concurrent.futures.wait(
                fs=submitted_fs,
                timeout=None,  # Handled in the respective communication stack
            )
        return sampled_clients

    def unregister(self, client):
        self.logger.info(f"Unregistering {client.cid}")
        super().unregister(client)

    def get_props(self, client):
        try:
            tik = time.time()
            properties = client.get_properties(GetPropertiesIns({}), None, None).properties
            self.logger.info(f"Getting Properties for Client {client.cid} took {time.time() - tik}s")
            if properties:
                self.clients_info[client.cid] = dict(properties)
                if self.zmq_handler:
                    self.logger.info(f"Sending this data to server {properties}", )
                    self.zmq_handler.send_data_to_server(ZMQHandler.MessageType.UPDATE_DIRECTORY, dict(properties))
        except Exception as e:
            self.logger.error(e)
