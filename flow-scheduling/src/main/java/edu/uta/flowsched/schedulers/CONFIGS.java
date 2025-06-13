package edu.uta.flowsched.schedulers;

import edu.uta.flowsched.schedulers.OptimizedScheduler;

public class CONFIGS {
    public static final double SWITCH_THRESHOLD = 30;
    public static final long DATA_SIZE = 140_000_000; // 17MByte
    public static final long ALMOST_DONE_DATA_THRESH = DATA_SIZE / 5; // 17MByte
    public static int PATHS_LIMIT = 10;
}
