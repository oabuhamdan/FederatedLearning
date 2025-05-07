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
    protected static final double SWITCH_THRESHOLD = 0.35;
    protected static final long DATA_SIZE = 140_000_000; // 17MByte
    protected static final long ALMOST_DONE_DATA_THRESH = DATA_SIZE / 5; // 17MByte
    protected static ExecutorService executor;
    protected static ScheduledExecutorService waitExecutor;
    protected final FlowDirection direction;
    protected final ConcurrentLinkedQueue<FLHost> clientQueue;
    protected final Map<FLHost, Set<MyPath>> clientPaths;
    protected final Map<FLHost, Integer> completionTimes;
    protected final Map<FLHost, Long> dataRemaining;
    protected final Map<FLHost, Long> assumedRates;
    protected final ConcurrentLinkedQueue<FLHost> needPhase1Processing;
    protected final ConcurrentLinkedQueue<FLHost> needPhase2Processing;
    protected final ConcurrentLinkedQueue<FLHost> completedClients;
    protected final AtomicInteger round;
    protected ScheduledFuture<?> future;

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
        S2C = HybridCapacityScheduler2.getInstance(FlowDirection.S2C);
        C2S = HybridCapacityScheduler2.getInstance(FlowDirection.C2S);
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

    protected void getClientsFromQueue() {
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
                PathRulesInstaller.INSTANCE.increasePriority();
                getClientsFromQueue();
                long tik = System.currentTimeMillis();
                StringBuilder phase1Logger = new StringBuilder("\tPhase 1:\n");

                needPhase2Processing.addAll(needPhase1Processing);
                int phase1ClientCount = needPhase1Processing.size();
                if (!needPhase1Processing.isEmpty()) {
                    phase1(phase1Logger);
                    phase2();
                }
                Util.log("overhead", String.format("%s,phase1,%s,%s", this.round.get(), phase1ClientCount, System.currentTimeMillis() - tik));
                Util.log("greedy" + this.direction, phase1Logger.toString());
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

    protected boolean clientAlmostDone(FLHost client) {
        return dataRemaining.getOrDefault(client, DATA_SIZE) <= ALMOST_DONE_DATA_THRESH || completionTimes.getOrDefault(client, 100) <= 5;
    }

    protected void phase1(StringBuilder internalLogger) {
        FLHost client;
        while ((client = needPhase1Processing.poll()) != null) {
            if (!clientAlmostDone(client)) {
                StringBuilder clientLogger = new StringBuilder(String.format("\t- Client %s: \n", client.getFlClientCID()));

                if (Util.getAgeInSeconds(client.getLastPathChange()) <= Util.POLL_FREQ * 2L - 1){
                    clientLogger.append("\t\tCurrent Path is Recent, Returning...\n");
                    continue;
                }

                MyPath currentPath = client.getCurrentPath();
                Set<MyPath> paths = new HashSet<>(clientPaths.get(client));
                boolean pathIsNull = currentPath == null;

                if (!pathIsNull) {// Simulate Path
                    SimMyPath simPath = new SimMyPath(currentPath, currentPath.getCurrentFairShare());
                    paths.remove(currentPath); // replace old with sim
                    paths.add(simPath);
                }

                HashMap<MyPath, Double> bestPaths = scorePaths(paths, false);
                Map.Entry<MyPath, Double> bestPath = bestPaths.entrySet()
                        .stream()
                        .max(Comparator.comparingDouble(Map.Entry::getValue))
                        .orElseThrow(() -> new NoSuchElementException("Map is empty"));
                debugPaths(client, bestPaths);
                if (shouldSwitchPath(currentPath, bestPath, bestPaths, clientLogger)) {
                    client.setLastPathChange(System.currentTimeMillis());
                    String currentPathFormat = Optional.ofNullable(currentPath).map(MyPath::format).orElse("No Path");
                    String newPathFormat = bestPath.getKey().format();
                    clientLogger.append(String.format("\t\tCurrent Path: %s\n", currentPathFormat));
                    PathRulesInstaller.INSTANCE.installPathRules(client, bestPath.getKey(), false);
                    Set<FLHost> affectedClients = client.assignNewPath(bestPath.getKey());
                    clientLogger.append(String.format("\t\tNew Path: %s\n", newPathFormat));
                    // The client is among the affected clients from the addition
                    updateTimeAndRate(affectedClients, internalLogger);
                }
                internalLogger.append(clientLogger);
            }
        }
    }

    void debugPaths(FLHost client, HashMap<MyPath, Double> bestPaths){}

    abstract protected HashMap<MyPath, Double> scorePaths(Set<MyPath> paths, boolean initial);

    protected boolean shouldSwitchPath(MyPath currentPath, Map.Entry<MyPath, Double> bestPath, HashMap<MyPath, Double> bestPaths, StringBuilder clientLogger) {
        if (currentPath == null) return true;
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

    protected void updateTimeAndRate(Set<FLHost> affectedClients, StringBuilder internalLogger) {
        for (FLHost affectedClient : affectedClients) {
            if (affectedClient.getCurrentPath() != null) {
                long updatedFairShare = affectedClient.getCurrentPath().getCurrentFairShare();
                long dataRemain = dataRemaining.getOrDefault(affectedClient, 0L);
                int updatedCompletionTime = (int) Math.round(1.0 * dataRemain / updatedFairShare);
                completionTimes.put(affectedClient, updatedCompletionTime);
                assumedRates.put(affectedClient, updatedFairShare);
//                internalLogger.append(String.format("\t\tClient CID %s, Remain Data %sMbit, Rate %sMbps, Completion Time %d\n",
//                        affectedClient.getFlClientCID(), bitToMbit(dataRemain), bitToMbit(updatedFairShare), updatedCompletionTime));
            } else {
                internalLogger.append(String.format("\t\tClient %s has no current path!\n", affectedClient.getFlClientCID()));
            }
        }
    }

    protected void phase2() {
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
        phase2Logger.append("\tPhase 2: \n");
        int phase2ClientCount = needPhase2Processing.size();
        FLHost client;
        while ((client = needPhase2Processing.poll()) != null) {
            try {
                long assignedRate = Math.max(client.networkStats.getLastPositiveRate(this.direction), (long) 1e6);
                long roundExchangedData = client.networkStats.getRoundExchangedData(this.direction, round.get());
                long dataRemain = DATA_SIZE - roundExchangedData;
                long lastExchangedRate = client.networkStats.getLastRate(this.direction);

                if (lastExchangedRate <= 1e6) {
                    phase2Logger.append(String.format("\t\tPhase 2 - Client %s exchanged only %sMbps!\n", client.getFlClientCID(), bitToMbit(lastExchangedRate)));
                }

                int remainingTime = (int) (dataRemain / assignedRate);
                if (remainingTime < Util.POLL_FREQ || dataRemain <= 5e5) { // 500KB remaining
                    completedClients.add(client);
                    if (!client.clearPath())
                        phase2Logger.append(String.format("\t\tPhase 2 - Client %s has no current path!\n", client.getFlClientCID()));
                    completionTimes.remove(client);
                    dataRemaining.remove(client);
                } else {
                    dataRemaining.put(client, dataRemain);
                    completionTimes.put(client, remainingTime);
                    needPhase1Processing.add(client);
                }
                phase2Logger.append(String.format("\t - Client %s: Art Rate: %sMbps, Real Rate:%sMbps, Real Rem Time: %ss, Real Rem Data: %sMbits\n",
                        client.getFlClientCID(), bitToMbit(assumedRates.get(client)), bitToMbit(assignedRate), remainingTime, bitToMbit(dataRemain)));
            } catch (Exception e) {
                Util.log("greedy" + this.direction, "ERROR: " + e.getMessage() + "..." + Arrays.toString(e.getStackTrace()));
            }
        }
        phase2Logger.append(String.format("\t** Completed %s/%s Clients**\n", completedClients.size(), ClientInformationDatabase.INSTANCE.getTotalFLClients()));
        phase2Logger.append("\t** Finishing Internal Round**\n");
        Util.log("greedy" + this.direction, phase2Logger.toString());
        Util.log("overhead", String.format("%s,phase2,%s,%s", this.round.get(), phase2ClientCount, System.currentTimeMillis() - tik));
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GreedyFlowScheduler that = (GreedyFlowScheduler) o;
        return direction == that.direction;
    }

    @Override
    public int hashCode() {
        return Objects.hash(direction);
    }
}
