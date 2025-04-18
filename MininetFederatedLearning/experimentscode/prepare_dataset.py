import argparse

from flwr_datasets import FederatedDataset
from flwr_datasets.partitioner import IidPartitioner


def prepare_dataset():
    partitioner = IidPartitioner(num_partitions=args.num_clients)
    fds = FederatedDataset(dataset=args.dataset, partitioners={"train": partitioner, "test": 1})
    for partition_id in range(args.num_clients):
        partition = fds.load_partition(partition_id, "train")
        partition.save_to_disk(f"data/{args.dataset}/client_{partition_id + 1}_data")
    testset = fds.load_split("test")
    testset.save_to_disk(f"data/{args.dataset}/server_val_data")


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description="Prepare dataset for federated learning")
    parser.add_argument("-c", "--num-clients", type=int, default=10, help="Number of clients")
    parser.add_argument("-d", "--dataset", type=str, default="cifar10", help="dataset")
    args = parser.parse_args()
    prepare_dataset()
