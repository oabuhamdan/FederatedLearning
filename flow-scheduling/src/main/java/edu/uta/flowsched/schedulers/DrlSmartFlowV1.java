package edu.uta.flowsched.schedulers;

import edu.uta.flowsched.*;
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

public class DrlSmartFlowV1 extends SmartFlowScheduler {
    private static final int MAX_PATHS_PER_CLIENT = 10;
    // --- UPDATED: Switched to bottleneck features for better state representation ---
    private static final int FEATURES_PER_PATH = 4; // minFreeCapacity, maxLatency, maxPacketLoss, maxFlows

    // --- Thread-safe singleton instances ---
    private static volatile DrlSmartFlowV1 s2cInstance;
    private static volatile DrlSmartFlowV1 c2sInstance;

    private final DQNAgentV1 agent;

    private DrlSmartFlowV1(FlowDirection direction) {
        super(direction);
        agent = DQNAgentV1.getInstance(direction, FEATURES_PER_PATH * MAX_PATHS_PER_CLIENT, MAX_PATHS_PER_CLIENT);
    }

    public static DrlSmartFlowV1 getInstance(FlowDirection direction) {
        // Using double-checked locking for thread-safe singleton initialization
        if (direction == FlowDirection.S2C) {
            if (s2cInstance == null) {
                synchronized (DrlSmartFlowV1.class) {
                    if (s2cInstance == null) s2cInstance = new DrlSmartFlowV1(direction);
                }
            }
            return s2cInstance;
        } else {
            if (c2sInstance == null) {
                synchronized (DrlSmartFlowV1.class) {
                    if (c2sInstance == null) c2sInstance = new DrlSmartFlowV1(direction);
                }
            }
            return c2sInstance;
        }
    }

    @Override
    protected void phase1(ProcessingContext context, AtomicLong phase1Total) {
        StringBuilder phase1Logger = new StringBuilder(Util.formatMessage(0, "Phase 1 (DRL):"));
        long start = System.currentTimeMillis();
        int processed = 0;

        FLHost client = context.needPhase1Processing.poll();
        while (client != null && !client.equals(Util.POISON_CLIENT)) {
            StringBuilder clientLog = new StringBuilder(Util.formatMessage(1, "- Client %s: ", client.getFlClientCID()));
            try {
                Collection<MyPath> pathSet = context.clientPaths.get(client);
                List<MyPath> paths = new ArrayList<>(pathSet);
                if (paths.isEmpty()) continue;

                INDArray state = buildState(paths);
                int action = agent.selectAction(state, paths.size());
                MyPath chosenPath = paths.get(action);

                if (!chosenPath.equals(client.getCurrentPath())) {
                    clientLog.append(Util.formatMessage(2, "Switching to path %s", chosenPath.format()));
                    agent.recordStateAction(client, state, action); // Record experience before applying
                    client.setLastPathChange(System.currentTimeMillis());
                    PathRulesInstaller.INSTANCE.installPathRules(client, chosenPath, false); // Assuming C2S
                    client.assignNewPath(chosenPath);
                } else {
                    clientLog.append(Util.formatMessage(2, "Keeping current path"));
                }
                context.needPhase2Processing.add(client);
                phase1Logger.append(clientLog);
                processed++;
            } catch (Exception e) {
                clientLog.append(Util.formatMessage(2, "ERROR: %s", e.getMessage()));
                phase1Logger.append(clientLog);
            }
            client = context.needPhase1Processing.poll();
        }
        phase1Total.addAndGet(System.currentTimeMillis() - start);
        Util.log("smartflow-" + direction, phase1Logger.toString());
        Util.log("smartflow-" + direction, Util.formatMessage(1, "Processed %s clients in DRL Phase 1", processed));
    }

    @Override
    protected void updateClientsStats(ProcessingContext context, AtomicLong phase2Total) {
        long t0 = System.currentTimeMillis();
        super.updateClientsStats(context, phase2Total); // Assuming this updates client stats

        for (FLHost client : agent.getRecordedHosts()) {
            INDArray prevState = agent.getLastState(client);
            int prevAction = agent.getLastAction(client);

            // This client has completed an action, so we can calculate the reward
            if (prevState != null && prevAction != -1) {
                List<MyPath> paths = new ArrayList<>(context.clientPaths.get(client));
                INDArray nextState = buildState(paths);

                // --- ENHANCED: Reward function now penalizes latency and loss ---
                MyPath chosenPath = paths.get(prevAction);
                double[] features = extractPathFeatures(chosenPath);
                double maxLatency = features[1]; // from extractPathFeatures
                double maxLoss = features[2];     // from extractPathFeatures
                long realRateBps = client.networkStats.getLastPositiveRate(direction);
                double throughputMbps = Util.bitToMbit(realRateBps);

                final double LATENCY_PENALTY_WEIGHT = 0.1;
                final double LOSS_PENALTY_WEIGHT = 10.0;

                double reward = throughputMbps - (LATENCY_PENALTY_WEIGHT * maxLatency) - (LOSS_PENALTY_WEIGHT * maxLoss);

                boolean done = context.completedClients.contains(client);
                agent.observe(prevState, prevAction, reward, nextState, done);

                // Clear the recorded action so we don't reward it twice
                agent.clearStateAction(client);
            }
        }
        agent.trainStep();
        phase2Total.addAndGet(System.currentTimeMillis() - t0);
    }

    private INDArray buildState(List<MyPath> paths) {
        INDArray state = Nd4j.zeros(1, FEATURES_PER_PATH * MAX_PATHS_PER_CLIENT);
        for (int i = 0; i < paths.size() && i < MAX_PATHS_PER_CLIENT; i++) {
            double[] features = extractPathFeatures(paths.get(i));
            INDArray featureVec = Nd4j.create(features);
            state.get(NDArrayIndex.point(0),
                            NDArrayIndex.interval(i * FEATURES_PER_PATH, (i + 1) * FEATURES_PER_PATH))
                    .assign(featureVec);
        }
        return state;
    }

    /**
     * ENHANCED: Extracts bottleneck features from a path instead of averaging.
     * This provides a more accurate view of a path's performance limitations.
     */
    private double[] extractPathFeatures(MyPath path) {
        Set<Link> links = path.linksNoEdge();
        if (links.isEmpty()) {
            return new double[FEATURES_PER_PATH];
        }

        double minFreeCapacity = Double.MAX_VALUE;
        double maxLatency = 0.0;
        double maxPacketLoss = 0.0;
        double maxActiveFlows = 0.0;

        for (Link l : links) {
            MyLink link = (MyLink) l;
            minFreeCapacity = Math.min(minFreeCapacity, link.getEstimatedFreeCapacity());
            maxLatency = Math.max(maxLatency, link.getLatency());
            maxPacketLoss = Math.max(maxPacketLoss, link.getPacketLoss());
            maxActiveFlows = Math.max(maxActiveFlows, link.getActiveFlows());
        }

        return new double[]{
                minFreeCapacity,
                maxLatency,
                maxPacketLoss,
                maxActiveFlows
        };
    }

    @Override
    public void initialSort(ProcessingContext context, List<FLHost> clients) {
        // --- REVISED: More logical sorting. ---
        // Prioritizes clients that the agent thinks can get the biggest improvement
        // from a path switch. This focuses effort where it's most needed.
        clients.sort(Comparator.comparingDouble(c -> {
            Collection<MyPath> pathSet = context.clientPaths.get(c);
            if (pathSet == null || pathSet.isEmpty()) return Double.MAX_VALUE;

            INDArray state = buildState(new ArrayList<>(pathSet));
            INDArray qValues = agent.predict(state);

            double maxQ = qValues.maxNumber().doubleValue();

            // Get Q-value of current path to estimate current performance
            int currentIndex = new ArrayList<>(pathSet).indexOf(c.getCurrentPath());
            double currentQ = (currentIndex != -1) ? qValues.getDouble(currentIndex) : maxQ;

            // Sort by potential for improvement (descending)
            return -(maxQ - currentQ);
        }));
    }
}


/**
 * DQN Agent with replay memory and target network.
 * This version includes thread-safe singleton, corrected model loading, and better constants.
 */
class DQNAgentV1 {
    // --- Constants for better maintainability ---
    private static final int REPLAY_MEMORY_CAPACITY = 10000;
    private static final int BATCH_SIZE = 32;
    private static final double LEARNING_RATE = 1e-4; // Lowered for more stable learning
    private static final double DISCOUNT_FACTOR_GAMMA = 0.99;
    private static final int TARGET_NETWORK_UPDATE_FREQUENCY = 500;
    private static final double MIN_EPSILON = 0.1;
    private static final double EPSILON_DECAY_RATE = 0.9997;
    private static final int HIDDEN_LAYER_1_NODES = 128;
    private static final int HIDDEN_LAYER_2_NODES = 64;

    private MultiLayerNetwork qNetwork;
    private final MultiLayerNetwork targetNetwork;
    private final ReplayMemoryV1 memory;
    private double epsilon = 1.0;
    private int steps = 0;

    private final Map<FLHost, INDArray> lastStateMap = new HashMap<>();
    private final Map<FLHost, Integer> lastActionMap = new HashMap<>();

    // --- Thread-safe Singleton instances ---
    private static volatile DQNAgentV1 s2cAgent;
    private static volatile DQNAgentV1 c2sAgent;

    public static DQNAgentV1 getInstance(FlowDirection dir, int inputSize, int actionSize) {
        if (dir == FlowDirection.S2C) {
            if (s2cAgent == null) {
                synchronized (DQNAgentV1.class) {
                    if (s2cAgent == null) s2cAgent = new DQNAgentV1(inputSize, actionSize, "s2c-model.zip");
                }
            }
            return s2cAgent;
        } else {
            if (c2sAgent == null) {
                synchronized (DQNAgentV1.class) {
                    if (c2sAgent == null) c2sAgent = new DQNAgentV1(inputSize, actionSize, "c2s-model.zip");
                }
            }
            return c2sAgent;
        }
    }

    private DQNAgentV1(int inputSize, int actionSize, String modelFile) {
        this.memory = new ReplayMemoryV1(REPLAY_MEMORY_CAPACITY);

        File model = new File(modelFile);
        if (model.exists()) {
            try {
                Util.log("DQNAgentV1", "Loading existing model from: " + modelFile);
                this.qNetwork = MultiLayerNetwork.load(model, true);
            } catch (IOException e) {
                Util.log("DQNAgentV1", "ERROR: Failed to load model, creating new. " + e.getMessage());
                this.qNetwork = buildNetwork(inputSize, actionSize);
            }
        } else {
            Util.log("DQNAgentV1", "No existing model found, creating new network.");
            this.qNetwork = buildNetwork(inputSize, actionSize);
        }

        // Clone the Q-network to create the target network with identical structure and initial weights
        this.targetNetwork = this.qNetwork.clone();
    }

    private MultiLayerNetwork buildNetwork(int inputSize, int actionSize) {
        NeuralNetConfiguration.ListBuilder builder = new NeuralNetConfiguration.Builder()
                .seed(new Random().nextLong())
                .updater(new Adam(LEARNING_RATE))
                .weightInit(WeightInit.XAVIER)
                .list()
                .layer(new DenseLayer.Builder().nIn(inputSize).nOut(HIDDEN_LAYER_1_NODES).activation(Activation.RELU).build())
                .layer(new DenseLayer.Builder().nIn(HIDDEN_LAYER_1_NODES).nOut(HIDDEN_LAYER_2_NODES).activation(Activation.RELU).build())
                .layer(new OutputLayer.Builder(LossFunctions.LossFunction.MSE)
                        .activation(Activation.IDENTITY).nIn(HIDDEN_LAYER_2_NODES).nOut(actionSize).build());

        MultiLayerNetwork network = new MultiLayerNetwork(builder.build());
        network.init();
        return network;
    }

    public int selectAction(INDArray state, int validActionCount) {
        // Epsilon decays with each decision, not just on training steps
        epsilon = Math.max(MIN_EPSILON, epsilon * EPSILON_DECAY_RATE);

        if (Math.random() < epsilon || validActionCount <= 0) {
            return new Random().nextInt(Math.max(1, validActionCount)); // Explore
        }

        // Exploit: Get Q-values and find the index of the best action
        INDArray qValues = qNetwork.output(state);
        INDArray validQValues = qValues.get(NDArrayIndex.all(), NDArrayIndex.interval(0, validActionCount));
        return Nd4j.argMax(validQValues, 1).getInt(0);
    }

    public INDArray predict(INDArray state) {
        return qNetwork.output(state);
    }

    public void recordStateAction(FLHost client, INDArray state, int action) {
        lastStateMap.put(client, state.dup()); // Use dup to avoid mutation
        lastActionMap.put(client, action);
    }

    public void clearStateAction(FLHost client) {
        lastStateMap.remove(client);
        lastActionMap.remove(client);
    }

    public Set<FLHost> getRecordedHosts() {
        return new HashSet<>(lastStateMap.keySet()); // Return a copy
    }

    public INDArray getLastState(FLHost client) {
        return lastStateMap.get(client);
    }

    public int getLastAction(FLHost client) {
        return lastActionMap.getOrDefault(client, -1);
    }

    public void observe(INDArray s, int a, double r, INDArray sNext, boolean done) {
        memory.add(new TransitionV1(s, a, r, sNext, done));
    }

    public void trainStep() {
        if (memory.size() < BATCH_SIZE) {
            return;
        }
        List<TransitionV1> batch = memory.sample(BATCH_SIZE);

        INDArray states = Nd4j.vstack(batch.stream().map(t -> t.state).collect(Collectors.toList()));
        INDArray nextStates = Nd4j.vstack(batch.stream().map(t -> t.nextState).collect(Collectors.toList()));

        INDArray currentQValues = qNetwork.output(states);
        INDArray nextQValuesFromTarget = targetNetwork.output(nextStates);
        INDArray targetQValues = currentQValues.dup(); // Start with current Q-values

        for (int i = 0; i < batch.size(); i++) {
            TransitionV1 t = batch.get(i);
            double targetQ;
            if (t.done) {
                targetQ = t.reward;
            } else {
                // Bellman equation
                double maxNextQ = nextQValuesFromTarget.getRow(i).maxNumber().doubleValue();
                targetQ = t.reward + DISCOUNT_FACTOR_GAMMA * maxNextQ;
            }
            targetQValues.putScalar(i, t.action, targetQ);
        }

        qNetwork.fit(states, targetQValues);
        steps++;

        if (steps % TARGET_NETWORK_UPDATE_FREQUENCY == 0) {
            targetNetwork.setParams(qNetwork.params());
            Util.log("DQNAgentV1", "Target network updated.");
        }
    }
}


/**
 * Replay Memory to store experiences for batch training.
 */
class ReplayMemoryV1 {
    private final Deque<TransitionV1> buffer;
    private final int capacity;
    private final Random rand = new Random();

    public ReplayMemoryV1(int capacity) {
        this.capacity = capacity;
        this.buffer = new ArrayDeque<>(capacity);
    }

    public void add(TransitionV1 t) {
        if (buffer.size() >= capacity) {
            buffer.pollFirst();
        }
        buffer.addLast(t);
    }

    public List<TransitionV1> sample(int batchSize) {
        List<TransitionV1> list = new ArrayList<>(buffer);
        Collections.shuffle(list, rand);
        return list.subList(0, Math.min(batchSize, list.size()));
    }

    public int size() {
        return buffer.size();
    }
}


/**
 * A simple data class to hold a single (State, Action, Reward, NextState) TransitionV1.
 */
class TransitionV1 {
    public final INDArray state;
    public final int action;
    public final double reward;
    public final INDArray nextState;
    public final boolean done;

    public TransitionV1(INDArray state, int action, double reward, INDArray nextState, boolean done) {
        this.state = state;
        this.action = action;
        this.reward = reward;
        this.nextState = nextState;
        this.done = done;
    }
}

