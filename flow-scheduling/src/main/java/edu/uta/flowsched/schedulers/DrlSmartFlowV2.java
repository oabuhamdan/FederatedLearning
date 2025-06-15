package edu.uta.flowsched.schedulers;

import edu.uta.flowsched.*;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.layers.DenseLayer;
import org.deeplearning4j.nn.conf.layers.OutputLayer;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.weights.WeightInit;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.NDArrayIndex;
import org.nd4j.linalg.learning.config.Adam;
import org.nd4j.linalg.lossfunctions.LossFunctions;
import org.onosproject.net.Link;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class DrlSmartFlowV2 extends SmartFlowScheduler {
    private static final int MAX_PATHS_PER_CLIENT = 10;
    private static final int FEATURES_PER_PATH = 7;
    private static final double SWITCH_THRESHOLD = 0.1;

    private static volatile DrlSmartFlowV2 s2cInstance;
    private static volatile DrlSmartFlowV2 c2sInstance;
    private final DQNAgentV2 agent;

    private DrlSmartFlowV2(FlowDirection direction) {
        super(direction);
        agent = DQNAgentV2.getInstance(direction, FEATURES_PER_PATH * MAX_PATHS_PER_CLIENT, MAX_PATHS_PER_CLIENT);
    }

    public static DrlSmartFlowV2 getInstance(FlowDirection direction) {
        if (direction == FlowDirection.S2C) {
            if (s2cInstance == null) {
                synchronized (DrlSmartFlowV2.class) {
                    if (s2cInstance == null) s2cInstance = new DrlSmartFlowV2(direction);
                }
            }
            return s2cInstance;
        } else {
            if (c2sInstance == null) {
                synchronized (DrlSmartFlowV2.class) {
                    if (c2sInstance == null) c2sInstance = new DrlSmartFlowV2(direction);
                }
            }
            return c2sInstance;
        }
    }

    @Override
    protected void phase1(ProcessingContext context, AtomicLong phase1Total) {
        long start = System.currentTimeMillis();
        int processed = 0;
        FLHost client;
        while ((client = context.needPhase1Processing.poll()) != null && !client.equals(Util.POISON_CLIENT)) {
            try {
                List<MyPath> paths = new ArrayList<>(context.clientPaths.getOrDefault(client, Collections.emptySet()));
                if (paths.isEmpty()) continue;
                INDArray state = buildState(paths);
                int action = agent.selectAction(state, paths.size());
                MyPath chosen = paths.get(action);
                MyPath current = client.getCurrentPath();
                if (!chosen.equals(current)) {
                    agent.recordStateAction(client, state, action);
                    client.setLastPathChange(System.currentTimeMillis());
                    PathRulesInstaller.INSTANCE.installPathRules(client, chosen, false);
                    client.assignNewPath(chosen);
                }
                context.needPhase2Processing.add(client);
                processed++;
            } catch (Exception e) {
                Util.log("smartflow-" + direction, "Phase1 ERROR for client " + client.getFlClientCID() + ": " + e);
            }
        }
        phase1Total.addAndGet(System.currentTimeMillis() - start);
        Util.log("smartflow-" + direction, "Phase1 processed " + processed + " clients.");
    }

    @Override
    protected void updateClientsStats(ProcessingContext context, AtomicLong phase2Total) {
        long t0 = System.currentTimeMillis();
        super.updateClientsStats(context, phase2Total);

        for (FLHost client : agent.getRecordedHosts()) {
            INDArray prevState = agent.getLastState(client);
            int prevAction = agent.getLastAction(client);
            if (prevState != null && prevAction >= 0) {
                List<MyPath> paths = new ArrayList<>(context.clientPaths.getOrDefault(client, Collections.emptySet()));
                INDArray nextState = buildState(paths);

                MyPath chosen = paths.get(prevAction);
                double[] f = extractPathFeatures(chosen);
                double latency = f[1], loss = f[2];
                double throughput = Util.bitToMbit(client.networkStats.getLastPositiveRate(direction));
                double reward = throughput - 0.1 * latency - 10.0 * loss;
                boolean done = context.completedClients.contains(client);

                agent.observe(prevState, prevAction, reward, nextState, done);
                agent.clearStateAction(client);
            }
        }
        agent.trainStep();
        phase2Total.addAndGet(System.currentTimeMillis() - t0);
    }

    private INDArray buildState(List<MyPath> paths) {
        INDArray state = Nd4j.zeros(1, FEATURES_PER_PATH * MAX_PATHS_PER_CLIENT);
        for (int i = 0; i < paths.size() && i < MAX_PATHS_PER_CLIENT; i++) {
            INDArray feat = Nd4j.create(extractPathFeatures(paths.get(i)));
            state.get(NDArrayIndex.point(0), NDArrayIndex.interval(i * FEATURES_PER_PATH, (i + 1) * FEATURES_PER_PATH))
                    .assign(feat);
        }
        return state;
    }

    private double[] extractPathFeatures(MyPath path) {
        double minFree = Double.MAX_VALUE, maxLat = 0, maxLoss = 0, maxFlows = 0;
        for (Link l : path.linksNoEdge()) {
            MyLink ml = (MyLink) l;
            minFree = Math.min(minFree, ml.getEstimatedFreeCapacity());
            maxLat = Math.max(maxLat, ml.getLatency());
            maxLoss = Math.max(maxLoss, ml.getPacketLoss());
            maxFlows = Math.max(maxFlows, ml.getActiveFlows());
        }
        double currFS = path.getCurrentFairShare();
        double projFS = path.getProjectedFairShare();
        double eff = path.effectiveScore();
        return new double[]{minFree, maxLat, maxLoss, maxFlows, currFS, projFS, eff};
    }

    @Override
    public void initialSort(ProcessingContext context, List<FLHost> clients) {
        clients.sort(Comparator.comparingDouble(c -> {
            Collection<MyPath> ps = context.clientPaths.getOrDefault(c, Collections.emptySet());
            if (ps.isEmpty()) return Double.MAX_VALUE;
            INDArray st = buildState(new ArrayList<>(ps));
            INDArray qv = agent.predict(st);
            double maxQ = qv.maxNumber().doubleValue();
            int idx = new ArrayList<>(ps).indexOf(c.getCurrentPath());
            double currQ = (idx >= 0) ? qv.getDouble(0, idx) : maxQ;
            return -(maxQ - currQ);
        }));
    }
}

class DQNAgentV2 {
    private static final int BATCH_SIZE = 32;
    private static final double LR = 1e-4;
    private static final double GAMMA = 0.99;
    private static final double TAU = 1e-3;
    private static final double MIN_EPS = 0.1;
    private static final double EPS_DECAY = 0.9997;

    private final MultiLayerNetwork qNetwork;
    private final MultiLayerNetwork targetNetwork;
    private final ReplayMemoryV2 memory;
    private double epsilon = 1.0;

    private static volatile DQNAgentV2 s2cAgent;
    private static volatile DQNAgentV2 c2sAgent;

    private final Map<FLHost, INDArray> lastStateMap = new HashMap<>();
    private final Map<FLHost, Integer> lastActionMap = new HashMap<>();

    public static DQNAgentV2 getInstance(FlowDirection dir, int inputSize, int actionSize) {
        if (dir == FlowDirection.S2C) {
            if (s2cAgent == null) {
                synchronized (DQNAgentV2.class) {
                    if (s2cAgent == null) s2cAgent = new DQNAgentV2(inputSize, actionSize, "s2c.model");
                }
            }
            return s2cAgent;
        } else {
            if (c2sAgent == null) {
                synchronized (DQNAgentV2.class) {
                    if (c2sAgent == null) c2sAgent = new DQNAgentV2(inputSize, actionSize, "c2s.model");
                }
            }
            return c2sAgent;
        }
    }

    private DQNAgentV2(int inputSize, int actionSize, String modelFile) {
        this.memory = new ReplayMemoryV2(10000);
        MultiLayerNetwork net;
        File file = new File(modelFile);
        if (file.exists()) {
            try {
                net = MultiLayerNetwork.load(file, true);
            } catch (IOException e) {
                net = buildNetwork(inputSize, actionSize);
            }
        } else {
            net = buildNetwork(inputSize, actionSize);
        }
        this.qNetwork = net;
        this.targetNetwork = net.clone();
    }

    private MultiLayerNetwork buildNetwork(int inputSize, int actionSize) {
        MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder()
                .seed(12345)
                .updater(new Adam(LR))
                .weightInit(WeightInit.XAVIER)
                .list()
                .layer(new DenseLayer.Builder().nIn(inputSize).nOut(128).activation(Activation.RELU).build())
                .layer(new DenseLayer.Builder().nIn(128).nOut(64).activation(Activation.RELU).build())
                .layer(new OutputLayer.Builder(LossFunctions.LossFunction.MSE)
                        .activation(Activation.IDENTITY).nIn(64).nOut(actionSize).build())
                .build();
        MultiLayerNetwork network = new MultiLayerNetwork(conf);
        network.init();
        return network;
    }

    public int selectAction(INDArray state, int validCount) {
        epsilon = Math.max(MIN_EPS, epsilon * EPS_DECAY);
        if (Math.random() < epsilon) {
            return new Random().nextInt(validCount);
        }
        INDArray qValues = qNetwork.output(state);
        INDArray validQ = qValues.get(NDArrayIndex.point(0), NDArrayIndex.interval(0, validCount));
        return Nd4j.argMax(validQ, 1).getInt(0);
    }

    public INDArray predict(INDArray state) {
        return qNetwork.output(state);
    }

    public void recordStateAction(FLHost client, INDArray state, int action) {
        lastStateMap.put(client, state.dup());
        lastActionMap.put(client, action);
    }

    public void clearStateAction(FLHost client) {
        lastStateMap.remove(client);
        lastActionMap.remove(client);
    }

    public Set<FLHost> getRecordedHosts() {
        return new HashSet<>(lastStateMap.keySet());
    }

    public INDArray getLastState(FLHost client) {
        return lastStateMap.get(client);
    }

    public int getLastAction(FLHost client) {
        return lastActionMap.getOrDefault(client, -1);
    }

    public void observe(INDArray state, int action, double reward, INDArray nextState, boolean done) {
        memory.add(new TransitionV2(state, action, reward, nextState, done));
    }

    public void trainStep() {
        if (memory.size() < BATCH_SIZE) return;
        List<TransitionV2> batch = memory.sample(BATCH_SIZE);
        INDArray states = Nd4j.vstack(batch.stream().map(t -> t.state).collect(Collectors.toList()));
        INDArray nextStates = Nd4j.vstack(batch.stream().map(t -> t.nextState).collect(Collectors.toList()));

        INDArray qMain = qNetwork.output(states);
        INDArray qNextMain = qNetwork.output(nextStates);
        INDArray qNextTarget = targetNetwork.output(nextStates);

        INDArray targetQ = qMain.dup();
        for (int i = 0; i < batch.size(); i++) {
            TransitionV2 tr = batch.get(i);
            double targetValue;
            if (tr.done) {
                targetValue = tr.reward;
            } else {
                int bestNext = Nd4j.argMax(qNextMain.getRow(i), 1).getInt(0);
                double nextQ = qNextTarget.getDouble(i, bestNext);
                targetValue = tr.reward + GAMMA * nextQ;
            }
            targetQ.putScalar(new int[]{i, tr.action}, targetValue);
        }

        qNetwork.fit(states, targetQ);

        // Soft update target network
        INDArray mainParams = qNetwork.params();
        INDArray targetParams = targetNetwork.params();
        targetParams.muli(1 - TAU).addi(mainParams.mul(TAU));
        targetNetwork.setParams(targetParams);
    }
}

class ReplayMemoryV2 {
    private final Deque<TransitionV2> buffer;
    private final int capacity;
    private final Random rnd = new Random();

    public ReplayMemoryV2(int capacity) {
        this.capacity = capacity;
        this.buffer = new ArrayDeque<>(capacity);
    }

    public void add(TransitionV2 t) {
        if (buffer.size() >= capacity) buffer.pollFirst();
        buffer.addLast(t);
    }

    public List<TransitionV2> sample(int batchSize) {
        List<TransitionV2> list = new ArrayList<>(buffer);
        Collections.shuffle(list, rnd);
        return list.subList(0, Math.min(batchSize, list.size()));
    }

    public int size() {
        return buffer.size();
    }
}

class TransitionV2 {
    public final INDArray state;
    public final int action;
    public final double reward;
    public final INDArray nextState;
    public final boolean done;

    public TransitionV2(INDArray state, int action, double reward, INDArray nextState, boolean done) {
        this.state = state;
        this.action = action;
        this.reward = reward;
        this.nextState = nextState;
        this.done = done;
    }
}
