package edu.uta.flowsched.schedulers;

public class CONFIGS {
    public static final double SWITCH_THRESHOLD = 30;
    public static final long DATA_SIZE = 140_000_000; // 17MByte
    public static final long ALMOST_DONE_DATA_THRESH = DATA_SIZE / 5; // 17MByte
    public static int PATHS_LIMIT = 10;

    public static final String DRL_MODEL_PATH = "/tmp/onos-2.7.0/apache-karaf-4.2.9/data/tmp/model.zip";
    public static final int BATCH_SIZE = 64;
    public static final int TRAIN_INTERVAL_SEC = 10;
    public static final int REPLAY_CAPACITY = 4096;
    public static final int CHECKPOINT_EVERY_SEC = 300;
    public static final double LEARNING_RATE = 1e-3;

    // reward weights
    public static final double ALPHA = 0.2; // latency
    public static final double BETA = 50;  // loss
}