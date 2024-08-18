from flwr_datasets import FederatedDataset

NUM_CLIENTS = 25

fds = FederatedDataset(dataset="cifar10", partitioners={"train": NUM_CLIENTS})
for partition_id in range(NUM_CLIENTS):
    partition = fds.load_partition(partition_id, "train")
    # Divide data on each node: 90% train, 10% test
    partition = partition.train_test_split(test_size=0.1, seed=42)
    partition.save_to_disk(f"data/cifar10/client_{partition_id}_data")
# testset = fds.load_split("test")
# testset = testset.with_transform(apply_transforms)
# testset.save_to_disk("mnist/test_set")