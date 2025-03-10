from flwr_datasets import FederatedDataset

NUM_CLIENTS = 50
DATASET = 'cifar10'

fds = FederatedDataset(dataset=DATASET, partitioners={"train": NUM_CLIENTS, "test": 1})
for partition_id in range(NUM_CLIENTS):
    partition = fds.load_partition(partition_id, "train")
    partition.save_to_disk(f"data/{DATASET}/client_{partition_id}_data")
testset = fds.load_split("test")
testset.save_to_disk(f"data/{DATASET}/server_val_data")
