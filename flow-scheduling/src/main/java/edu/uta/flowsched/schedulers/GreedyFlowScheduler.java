package edu.uta.flowsched.schedulers;

import edu.uta.flowsched.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static edu.uta.flowsched.Util.bitToMbit;

public abstract class GreedyFlowScheduler {
    public static GreedyFlowScheduler S2C;
    public static GreedyFlowScheduler C2S;
    private static final double SWITCH_THRESHOLD = 0.3;
    private static final long DATA_SIZE = 140_000_000; // 17MByte
    private static final long ALMOST_DONE_DATA_THRESH = DATA_SIZE / 5; // 17MByte
    private static ExecutorService executor;
    private static ScheduledExecutorService waitExecutor;
    protected final FlowDirection direction;
    private final ConcurrentLinkedQueue<FLHost> clientQueue;
    protected final Map<FLHost, Set<MyPath>> clientPaths;
    private final Map<FLHost, Integer> completionTimes;
    private final Map<FLHost, Long> dataRemaining;
    private final Map<FLHost, Long> assumedRates;
    private final ConcurrentLinkedQueue<FLHost> needPhase1Processing;
    private final ConcurrentLinkedQueue<FLHost> needPhase2Processing;
    private final ConcurrentLinkedQueue<FLHost> completedClients;
    private final AtomicInteger round;
    private ScheduledFuture<?> future;

    protected GreedyFlowScheduler(FlowDirection direction) {
        this.direction = direction;
        clientQueue = new ConcurrentLinkedQueue<>();
        clientPaths = new ConcurrentHashMap<>();
        completionTimes = new ConcurrentHashMap<>();
        dataRemaining = new ConcurrentHashMap<>();
        assumedRates = new ConcurrentHashMap<>();
        needPhase1Processing = new ConcurrentLinkedQueue<>();
        needPhase2Processing = new ConcurrentLinkedQueue<>();
        completedClients = new ConcurrentLinkedQueue<>();
        future = null;
        round = new AtomicInteger(1);
    }

    public static void activate() {
        S2C = PlainFreeCapacityScheduler.getInstance(FlowDirection.S2C);
        C2S = PlainFreeCapacityScheduler.getInstance(FlowDirection.C2S);
        executor = Executors.newFixedThreadPool(2);
        waitExecutor = Executors.newScheduledThreadPool(10);
    }

    public void startScheduling() {
        Util.log("greedy" + this.direction, String.format("******** Round %s Started **********", round));
        executor.submit(this::main);
    }

    public int getRound() {
        return round.get();
    }

    public static void deactivate() {
        executor.shutdownNow();
    }

    public void addClientToQueue(FLHost client, Set<MyPath> paths) {
        clientQueue.offer(client);
        if (!clientPaths.containsKey(client)) {
            clientPaths.put(client, paths);
        }
        synchronized (this) {
            notify();
        }
    }

    private void drainNewClients() {
        FLHost client;
        while ((client = clientQueue.poll()) != null) {
            needPhase1Processing.add(client);
            dataRemaining.put(client, DATA_SIZE);
        }
    }

    private void getClientsFromQueue() {
        synchronized (this) {
            if (clientQueue.isEmpty()) {
                try {
                    wait();
                } catch (InterruptedException ignored) {
                }
            }
        }
        drainNewClients();
    }

    protected void main() {
        while (completedClients.size() < ClientInformationDatabase.INSTANCE.getTotalFLClients()) {
            try {
                getClientsFromQueue();
                long tik = System.currentTimeMillis();
                StringBuilder phase1Logger = new StringBuilder("\tPhase 1:\n");

                FLHost client;
                while ((client = needPhase1Processing.poll()) != null) {
                    if (!clientAlmostDone(client))
                        phase1(client, phase1Logger);
                    needPhase2Processing.add(client);
                }
                if (!needPhase2Processing.isEmpty()) {
                    phase2();
                }
                Util.log("overhead", String.format("controller,phase1,%s", System.currentTimeMillis() - tik));
                Util.log("greedy" + this.direction, phase1Logger.toString());
                PathRulesInstaller.INSTANCE.increasePriority();
            } catch (Exception e) {
                Util.log("greedy" + this.direction, "Error in scheduler: " + e.getMessage() + "...." + Arrays.toString(Arrays.stream(e.getStackTrace()).toArray()));
                break;
            }
        }
        completedClients.clear();
        needPhase1Processing.clear();
        needPhase2Processing.clear();
        dataRemaining.clear();
        completionTimes.clear();
        assumedRates.clear();

        Util.log("greedy" + this.direction, String.format("******** Round %s Completed ********\n", round.getAndIncrement()));
        Util.flushWriters();
    }

    private boolean clientAlmostDone(FLHost client) {
        return dataRemaining.getOrDefault(client, DATA_SIZE) <= ALMOST_DONE_DATA_THRESH || completionTimes.getOrDefault(client, 100) <= 5;
    }

    protected void phase1(FLHost client, StringBuilder internalLogger) {
        Set<FLHost> affectedClients = ConcurrentHashMap.newKeySet();
        StringBuilder clientLogger = new StringBuilder(String.format("\t- Client %s: \n", client.getFlClientCID()));

        MyPath currentPath = client.getCurrentPath(this.direction);
        Set<MyPath> paths = new HashSet<>(clientPaths.get(client));
        boolean pathIsNull = currentPath == null;

        if (!pathIsNull) {// Simulate Path
            SimMyPath simPath = new SimMyPath(currentPath, client.networkStats.getLastPositiveRate(this.direction));
            paths.remove(currentPath); // replace old with sim
            paths.add(simPath);
        }

        HashMap<MyPath, Double> bestPaths = scorePaths(paths, false);
        Map.Entry<MyPath, Double> bestPath = bestPaths.entrySet()
                .stream()
                .max(Comparator.comparingDouble(Map.Entry::getValue))
                .orElseThrow(() -> new NoSuchElementException("Map is empty"));

        if (shouldSwitchPath(client, currentPath, bestPath, bestPaths, clientLogger)) {
            client.setLastPathChange(System.currentTimeMillis());
            if (!pathIsNull) {
                clientLogger.append(String.format("\t\tCurrent Path: %s\n", currentPath.format()));
                affectedClients.addAll(currentPath.removeFlow(client, this.direction));
            }

            updateClientPath(client, bestPath.getKey(), affectedClients, clientLogger);
            updateTimeAndRate(client, affectedClients);
        }
        internalLogger.append(clientLogger);
    }

    abstract protected HashMap<MyPath, Double> scorePaths(Set<MyPath> paths, boolean initial);

    protected boolean shouldSwitchPath(FLHost client, MyPath currentPath, Map.Entry<MyPath, Double> bestPath, HashMap<MyPath, Double> bestPaths, StringBuilder clientLogger) {
        if (currentPath == null) return true;
        if (Util.getAgeInSeconds(client.getLastPathChange()) <= Util.POLL_FREQ) {
            clientLogger.append("\t\tChanged Recently. Skipping\n");
            return false;
        }
        if (bestPath.getKey().equals(currentPath)) {
            clientLogger.append("\t\tCurrent Path is Best Path, Returning...\n");
            return false;
        }

        double currentScore = bestPaths.get(currentPath);
        double bestScore = bestPath.getValue();
        boolean switching = (bestScore - currentScore) / currentScore >= SWITCH_THRESHOLD;
        if (switching)
            clientLogger.append(String.format("\t\tSwitching - New Score: (%.2f), Current Score: (%.2f)\n", bestScore, currentScore));
        else
            clientLogger.append(String.format("\t\tNo Switching - Current Score (%.2f), New Score (%.2f)\n", bestScore, currentScore));
        return switching;
    }

    protected void updateClientPath(FLHost client, MyPath newPath, Set<FLHost> affectedClients, StringBuilder clientLogger) {
        PathRulesInstaller.INSTANCE.installPathRules(client, newPath, false);
        affectedClients.addAll(newPath.addFlow(client, this.direction));
        client.setCurrentPath(newPath, this.direction);
        clientLogger.append(String.format("\t\tRate %sMbps, ActiveFlows %.2f: %s\n",
                bitToMbit(newPath.getCurrentFairShare()), newPath.getCurrentActiveFlows(), newPath.format()));
    }

    protected void updateTimeAndRate(FLHost client, Set<FLHost> affectedClients) {
        long fairShare = client.getCurrentPath(this.direction).getCurrentFairShare();
        int completionTime = (int) Math.round(1.0 * dataRemaining.get(client) / fairShare);
        completionTimes.put(client, completionTime);
        assumedRates.put(client, fairShare);

        for (FLHost affectedClient : affectedClients) {
            long updatedFairShare = affectedClient.getCurrentPath(this.direction).getCurrentFairShare();
            int updatedCompletionTime = (int) Math.round(1.0 * dataRemaining.getOrDefault(affectedClient, 0L) / updatedFairShare);
            completionTimes.put(affectedClient, updatedCompletionTime);
            assumedRates.put(affectedClient, updatedFairShare);
        }
    }

    private void phase2() {
        if (future == null || future.isDone()) {
            future = waitExecutor.schedule(() -> {
                updateClientsStats();
                synchronized (this) {
                    notifyAll(); // Notify the scheduler in case it's waiting
                }
            }, Util.POLL_FREQ, TimeUnit.SECONDS);
        }
    }

    protected void updateClientsStats() {
        StringBuilder phase2Logger = new StringBuilder();
        long tik = System.currentTimeMillis();
        phase2Logger.append("\tPhase 2: ");
        needPhase1Processing.forEach(h -> phase2Logger.append(h.getFlClientCID()).append(","));
        phase2Logger.append("\n");

        try {
            FLHost client;
            while ((client = needPhase2Processing.poll()) != null) {
                long assignedRate = (long) Math.max(client.networkStats.getLastPositiveRate(this.direction), 1e6);
                long roundExchangedData = client.networkStats.getRoundExchangedData(this.direction, round.get());
                long dataRemain = DATA_SIZE - roundExchangedData;
                int remainingTime = (int) (dataRemain / assignedRate);

                if (remainingTime <= 0 || dataRemain <= 0) {
                    completedClients.add(client);
                    MyPath path = client.getCurrentPath(this.direction);
                    if (path != null) {
                        path.removeFlow(client, this.direction);
                    } else {
                        phase2Logger.append(String.format("\t - !! Client %s has no %s Path !!\n", client.getFlClientCID(), this.direction));
                    }
                    client.setCurrentPath(null, this.direction);
                    completionTimes.remove(client);
                    dataRemaining.remove(client);
                } else {
                    dataRemaining.put(client, dataRemain);
                    completionTimes.put(client, remainingTime);
                    needPhase1Processing.add(client);
                }
                phase2Logger.append(String.format("\t - Client %s: Art Rate: %sMbps, Real Rate:%sMbps, Real Rem Time: %ss, Real Rem Data: %sMbits\n",
                        client.getFlClientCID(), bitToMbit(assumedRates.get(client)), bitToMbit(assignedRate), remainingTime, bitToMbit(dataRemain)));
            }
            phase2Logger.append(String.format("\t** Completed %s/%s Clients**\n", completedClients.size(), ClientInformationDatabase.INSTANCE.getTotalFLClients()));
            phase2Logger.append("\t** Finishing Internal Round**\n");
            Util.log("greedy" + this.direction, phase2Logger.toString());
            Util.log("overhead", String.format("controller,phase2,%s", System.currentTimeMillis() - tik));
        } catch (Exception e) {
            Util.log("greedy" + this.direction, "ERROR: " + e.getMessage() + "..." + Arrays.toString(e.getStackTrace()));
        }
    }

    public List<FLHost> initialSort(List<FLHost> hosts) {
        Map<FLHost, Double> rates = new HashMap<>();
        for (FLHost host : hosts) {
            Map<MyPath, Double> scores = scorePaths(PathInformationDatabase.INSTANCE.getPathsToClient(host), true);
            double score = scores.values().stream()
                    .min(Comparator.naturalOrder())
                    .orElse(0.0);
            rates.put(host, score);
        }
        return rates.entrySet().stream().sorted(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }
}
