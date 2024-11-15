from flwr_datasets import FederatedDataset

NUM_CLIENTS = 10
DATASET = 'cifar10'

fds = FederatedDataset(dataset=DATASET, partitioners={"train": NUM_CLIENTS, "test": 1})
for partition_id in range(NUM_CLIENTS):
    partition = fds.load_partition(partition_id, "train")
    # Divide data on each node: 80% train, 10% test
    # partition = partition.train_test_split(test_size=0.2, seed=42)
    partition.save_to_disk(f"data/{DATASET}/client_{partition_id}_data")
testset = fds.load_split("test")
testset.save_to_disk(f"data/{DATASET}/server_val_data")
