from flwr_datasets import FederatedDataset
from flwr_datasets.partitioner import IidPartitioner

NUM_CLIENTS = 10
DATASET = 'cifar10'

partitioner = IidPartitioner(num_partitions=NUM_CLIENTS)
fds = FederatedDataset(dataset=DATASET, partitioners={"train": partitioner, "test": 1})
for partition_id in range(NUM_CLIENTS):
    partition = fds.load_partition(partition_id, "train")
    partition.save_to_disk(f"data/{DATASET}/client_{partition_id}_data")
testset = fds.load_split("test")
testset.save_to_disk(f"data/{DATASET}/server_val_data")
