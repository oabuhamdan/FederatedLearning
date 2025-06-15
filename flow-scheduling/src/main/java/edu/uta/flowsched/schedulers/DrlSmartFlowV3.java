package edu.uta.flowsched.schedulers;

import edu.uta.flowsched.*;
import org.apache.commons.lang3.tuple.Pair;
import org.deeplearning4j.nn.api.OptimizationAlgorithm;
import org.deeplearning4j.nn.conf.ComputationGraphConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.layers.DenseLayer;
import org.deeplearning4j.nn.conf.layers.OutputLayer;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.optimize.listeners.ScoreIterationListener;
import org.deeplearning4j.util.ModelSerializer;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.learning.config.Adam;
import org.nd4j.linalg.lossfunctions.LossFunctions;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static edu.uta.flowsched.Util.POISON_CLIENT;
import static edu.uta.flowsched.Util.formatMessage;
import static edu.uta.flowsched.schedulers.CONFIGS.*;

public class DrlSmartFlowV3 extends SmartFlowScheduler {

    /* ---------------------- Static helpers & singleton plumb‑up ---------------------- */

    private static SmartFlowScheduler S2C_INSTANCE, C2S_INSTANCE;

    public static synchronized SmartFlowScheduler getInstance(FlowDirection direction) {
        if (S2C_INSTANCE == null) S2C_INSTANCE = new DrlSmartFlowV3(FlowDirection.S2C);
        if (C2S_INSTANCE == null) C2S_INSTANCE = new DrlSmartFlowV3(FlowDirection.C2S);
        return direction == FlowDirection.S2C ? S2C_INSTANCE : C2S_INSTANCE;
    }

    /* -----------------------  Model & training infrastructure ----------------------- */

    private final ComputationGraph rlModel;

    /**
     * per‑decision features waiting for a ground‑truth reward (keyed by client)
     */
    private final ConcurrentMap<FLHost, INDArray> pendingDecisions = new ConcurrentHashMap<>();

    /**
     * replay buffer holding (features,targetReward) pairs
     */
    private final ArrayBlockingQueue<Pair<INDArray, Double>> replay =
            new ArrayBlockingQueue<>(REPLAY_CAPACITY);

    private final ScheduledExecutorService trainingExecutor = Executors.newSingleThreadScheduledExecutor();

    private DrlSmartFlowV3(FlowDirection direction) {
        super(direction);
        this.rlModel = loadOrInitModel();
        registerPeriodicTrainer();
    }

    private ComputationGraph loadOrInitModel() {
        File f = new File(DRL_MODEL_PATH);
        try {
            if (f.exists()) {
                Util.log("smartflow" + direction, formatMessage(0, "Loading DRL model from %s", f));
                return ModelSerializer.restoreComputationGraph(f);
            }
            // build fresh model
            int numInputs = 6;
            ComputationGraphConfiguration cfg = new NeuralNetConfiguration.Builder()
                    .seed(1234)
                    .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT)
                    .updater(new Adam(LEARNING_RATE))
                    .graphBuilder()
                    .addInputs("in")
                    .addLayer("L1", new DenseLayer.Builder().nIn(numInputs).nOut(64)
                            .activation(Activation.RELU).build(), "in")
                    .addLayer("L2", new DenseLayer.Builder().nIn(64).nOut(32)
                            .activation(Activation.RELU).build(), "L1")
                    .addLayer("out", new OutputLayer.Builder(LossFunctions.LossFunction.MSE)
                            .activation(Activation.IDENTITY).nIn(32).nOut(1).build(), "L2")
                    .setOutputs("out").build();
            ComputationGraph m = new ComputationGraph(cfg);
            m.init();
            m.setListeners(new ScoreIterationListener(100));
            Util.log("smartflow" + direction, formatMessage(0, "Created fresh DRL model (params=%s)", m.numParams()));
            return m;
        } catch (Exception ex) {
            Util.log("smartflow" + direction, formatMessage(0, "Unable to (load|init) DRL model: %s", ex.getMessage()));
            return null;
        }
    }

    private void registerPeriodicTrainer() {
        trainingExecutor.scheduleAtFixedRate(this::trainOnReplay, TRAIN_INTERVAL_SEC,
                TRAIN_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    /* ------------------------------  Phase‑1  (decision) ----------------------------- */

    @Override
    protected void phase1(ProcessingContext context, AtomicLong phase1Total) throws InterruptedException {
        StringBuilder phase1Logger = new StringBuilder(formatMessage(0, "Phase 1 (DRL‑online):"));
        FLHost client = context.needPhase1Processing.take();
        if (client.equals(POISON_CLIENT)) return;
        long tik = System.currentTimeMillis();
        int processed = 0;
        while (client != null) {
            StringBuilder clientLogger = new StringBuilder(formatMessage(1, "- Client %s:", client.getFlClientCID()));
            try {
                List<MyPath> paths = new ArrayList<>(context.clientPaths.get(client));
                if (paths.isEmpty()) throw new IllegalStateException("Client has no candidate paths");

                Map<MyPath, Double> scores = new HashMap<>();
                MyPath currentPath = client.getCurrentPath();

                for (MyPath p : paths) {
                    INDArray feat = extractFeatures(client, p, currentPath);
                    synchronized (rlModel) {
                        scores.put(p, rlModel.outputSingle(feat).getDouble(0));
                    }
                }
                MyPath bestPath = scores.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();

                boolean switching = shouldSwitchPath(currentPath, bestPath, scores, clientLogger);
                if (switching) {
                    client.setLastPathChange(System.currentTimeMillis());
                    PathRulesInstaller.INSTANCE.installPathRules(client, bestPath, false);
                    Set<FLHost> affected = client.assignNewPath(bestPath);
                    updateTimeAndRate(context, affected, phase1Logger);
                    clientLogger.append(formatMessage(2, "Switched ⇢ %s", bestPath.format()));
                }
                // Record pending decision for on‑completion reward
                pendingDecisions.put(client, extractFeatures(client, bestPath, bestPath));

                context.needPhase2Processing.add(client);
                phase1Logger.append(clientLogger);
                client = context.needPhase1Processing.poll();
                processed++;
            } catch (Exception e) {
                clientLogger.append(formatMessage(2, "ERROR %s", e));
            }
        }
        phase1Total.addAndGet(System.currentTimeMillis() - tik);
        Util.log("smartflow" + direction, phase1Logger.toString());
        Util.log("smartflow" + direction, formatMessage(1, "Processed %s clients (DRL)", processed));
    }

    /* ------------------ SHOULD‑SWITCH  (added, was missing) ------------------------- */

    private boolean shouldSwitchPath(MyPath currentPath, MyPath bestPath,
                                     Map<MyPath, Double> scores, StringBuilder logger) {
        if (currentPath == null) return true; // first‑time assignment
        if (bestPath.equals(currentPath)) {
            logger.append(formatMessage(2, "Current path is already best"));
            return false;
        }
        double currScore = scores.getOrDefault(currentPath, 0.0);
        double bestScore = scores.get(bestPath);
        boolean switching = (bestScore - currScore) / Math.max(currScore, 1e-6) >= SWITCH_THRESHOLD;
        if (switching)
            logger.append(formatMessage(2, "Switch ➜ Δscore=%.3f", bestScore - currScore));
        else
            logger.append(formatMessage(2, "Hold ➜ Δscore=%.3f (below TH)", bestScore - currScore));
        return switching;
    }

    /* ------------------------------  Phase‑2  (stats)  ------------------------------ */

    @Override
    protected void updateClientsStats(ProcessingContext context, AtomicLong phase2TotalTime) {
        super.updateClientsStats(context, phase2TotalTime);
        for (FLHost completed : context.completedClients) {
            INDArray feat = pendingDecisions.remove(completed);
            if (feat == null) continue;
            double reward = computeReward(completed);
            offerExperience(feat, reward);
        }
    }

    /* ---------------------------  Feature & reward utils  --------------------------- */

    private INDArray extractFeatures(FLHost client, MyPath path, MyPath currentPath) {
        double projectedMbps = Util.bitToMbit(path.getProjectedFairShare());
        double freeCapMbps = Util.bitToMbit(path.getBottleneckFreeCap());
        double rtt = path.getEffectiveRTT();
        double loss = path.getPacketLossProbability();
        double timeSinceChg = (System.currentTimeMillis() - client.getLastPathChange()) / 1000.0;
        double isCurr = path.equals(currentPath) ? 1.0 : 0.0;
        return Nd4j.create(new double[]{projectedMbps, freeCapMbps, rtt, loss, timeSinceChg, isCurr});
    }

    private double computeReward(FLHost client) {
        long rateBits = client.networkStats.getLastPositiveRate(direction);
        double thrput = Util.bitToMbit(rateBits);
        double rtt = client.getCurrentPath() != null ? client.getCurrentPath().getEffectiveRTT() : 0;
        double loss = client.getCurrentPath() != null ? client.getCurrentPath().getPacketLossProbability() : 0;
        return thrput - ALPHA * rtt - BETA * loss * 100;
    }

    private void offerExperience(INDArray feat, double reward) {
        if (!replay.offer(Pair.of(feat, reward))) {
            replay.poll();
            replay.offer(Pair.of(feat, reward));
        }
    }

    /* ------------------------------  Online trainer  -------------------------------- */

    private void trainOnReplay() {
        try {
            if (replay.size() < BATCH_SIZE) return;
            List<Pair<INDArray, Double>> batch = new ArrayList<>(BATCH_SIZE);
            replay.drainTo(batch, BATCH_SIZE);
            if (batch.isEmpty()) return;
            INDArray input = Nd4j.vstack(batch.stream().map(Pair::getLeft).toArray(INDArray[]::new));
            INDArray labels = Nd4j.create(batch.size(), 1);
            for (int i = 0; i < batch.size(); i++) labels.putScalar(i, 0, batch.get(i).getRight());
            DataSet ds = new DataSet(input, labels);
            synchronized (rlModel) {
                rlModel.fit(ds); // direct fit – no deprecated iterator
            }
            maybeCheckpointModel();
        } catch (Exception ex) {
            Util.log("smartflow" + direction, formatMessage(0, "[DRL] Training error %s", ex));
        }
    }

    private void maybeCheckpointModel() {
        long now = System.currentTimeMillis();
        if (now % (CHECKPOINT_EVERY_SEC * 1000L) < TRAIN_INTERVAL_SEC * 1000L) {
            try {
                synchronized (rlModel) {
                    ModelSerializer.writeModel(rlModel, new File(DRL_MODEL_PATH), true);
                }
            } catch (IOException ignored) {
            }
        }
    }

    /* ------------------------------  Initial sort  ----------------------------------- */

    @Override
    public void initialSort(ProcessingContext context, List<FLHost> clients) {
        clients.sort(Comparator.comparingDouble(c -> {
            MyPath p = context.clientPaths.get(c).iterator().next();
            synchronized (rlModel) {
                return rlModel.outputSingle(extractFeatures(c, p, null)).getDouble(0);
            }
        }));
    }
}
