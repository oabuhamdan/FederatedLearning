package edu.uta.flowsched;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static edu.uta.flowsched.Util.bitToMbit;

public class GreedyFlowScheduler {
    public static final GreedyFlowScheduler S2C_INSTANCE = new GreedyFlowScheduler(FlowDirection.S2C);
    public static final GreedyFlowScheduler C2S_INSTANCE = new GreedyFlowScheduler(FlowDirection.C2S);
    private static final double SWITCH_THRESHOLD = 0.3;
    private static final long DATA_SIZE = 140_000_000; // 17MByte
    private static final long ALMOST_DONE_DATA_THRESH = DATA_SIZE / 5; // 17MByte
    private static ExecutorService executor;
    private static ScheduledExecutorService waitExecutor;
    private final FlowDirection direction;
    private final ConcurrentLinkedQueue<FLHost> clientQueue;
    private final Map<FLHost, Set<MyPath>> clientPaths;
    private final Map<FLHost, Integer> completionTimes;
    private final Map<FLHost, Long> dataRemaining;
    private final Map<FLHost, Long> assumedRates;
    private final ConcurrentLinkedQueue<FLHost> needPhase1Processing;
    private final ConcurrentLinkedQueue<FLHost> needPhase2Processing;
    private final ConcurrentLinkedQueue<FLHost> completedClients;
    private ScheduledFuture<?> future;
    private final AtomicInteger round;


    public GreedyFlowScheduler(FlowDirection direction) {
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
//        Util.log("greedy", "Activated ...");
        executor = Executors.newFixedThreadPool(2);
        waitExecutor = Executors.newScheduledThreadPool(10);
    }

    public void startScheduling() {
        Util.log("greedy" + this.direction, String.format("******** Round %s Started **********", round));
//        Util.log("debug_paths" + this.direction, String.format("******** Round %s Started **********", round));
        executor.submit(this::scheduleFlows);
    }

    public int getRound() {
        return round.get();
    }

    public static void deactivate() {
        executor.shutdownNow();
    }

    public void addClient(FLHost client, Set<MyPath> paths) {
        clientQueue.offer(client);
        if (!clientPaths.containsKey(client)) {
            clientPaths.put(client, paths);
        }
        synchronized (this) {
            notify();
        }
    }

    private void drainNewClients(ConcurrentLinkedQueue<FLHost> phase1Queue) {
        FLHost client;
        while ((client = clientQueue.poll()) != null) {
            phase1Queue.add(client);
            dataRemaining.put(client, DATA_SIZE);
        }
    }

    private void getClientsFromQueue(ConcurrentLinkedQueue<FLHost> phase1Queue) {
        synchronized (this) {
            if (clientQueue.isEmpty()) {
                try {
                    wait();
                } catch (InterruptedException ignored) {
                }
            }
        }
        drainNewClients(phase1Queue);
    }

    private void scheduleFlows() {
        while (completedClients.size() < ClientInformationDatabase.INSTANCE.getTotalFLClients()) {
            try {
                getClientsFromQueue(needPhase1Processing);
                long tik = System.currentTimeMillis();
                StringBuilder phase1Logger = new StringBuilder("\tPhase 1:\n");

                FLHost client;
                while ((client = needPhase1Processing.poll()) != null) {
                    if (!clientAlmostDone(client))
                        processClient(client, phase1Logger);
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


    private void processClient(FLHost client, StringBuilder internalLogger) {
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

        HashMap<MyPath, Double> bestPaths = scorePaths(paths);
        Map.Entry<MyPath, Double> bestPath = bestPaths.entrySet()
                .stream()
                .max(Comparator.comparingDouble(Map.Entry::getValue))
                .orElseThrow(() -> new NoSuchElementException("Map is empty"));

        if (shouldSwitchPath(currentPath, bestPath, bestPaths, clientLogger)) {
            if (!pathIsNull) {
                clientLogger.append(String.format("\t\tCurrent Path: %s\n", currentPath.format()));
                affectedClients.addAll(currentPath.removeFlow(client, this.direction));
            }

            updateClientPath(client, bestPath.getKey(), affectedClients, clientLogger);
            updateTimeAndRate(client, affectedClients);
            PathRulesInstaller.INSTANCE.installPathRules(client, bestPath.getKey(), false);
        }
        internalLogger.append(clientLogger);
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

    private boolean clientAlmostDone(FLHost client) {
        return dataRemaining.getOrDefault(client, DATA_SIZE) <= ALMOST_DONE_DATA_THRESH || completionTimes.getOrDefault(client, 100) <= 3;
    }

    private void logBeforePhase2(ConcurrentLinkedQueue<FLHost> processedClients, Map<FLHost, Long> artAssignedRates, Map<FLHost, Integer> completionTimes, Map<FLHost, Long> dataRemaining, StringBuilder internalLogger) {
        internalLogger.append("****** Before Phase 2 ****** \n");
        for (FLHost host : processedClients) {
            long rate = artAssignedRates.getOrDefault(host, 1L);
            long time = completionTimes.getOrDefault(host, 100);
            long data = dataRemaining.getOrDefault(host, DATA_SIZE);
            internalLogger.append(String.format("\t - Client %s: Art Rate:%sMbps, Time: %ss, Data: %sMbits \n",
                    host.getFlClientCID(), bitToMbit(rate), time, bitToMbit(data)));
        }
    }

    private void logAffectedClients(Set<FLHost> affectedClients, Map<FLHost, Long> artAssignedRates, int affectedByRemove, StringBuilder clientLogger) {
        Iterator<FLHost> iterator = affectedClients.iterator();
        if (affectedByRemove > 0) {
            clientLogger.append("\t\tAffected By Remove: ");
            int i = 0;
            while (i++ < affectedByRemove && iterator.hasNext()) {
                FLHost host = iterator.next();
                clientLogger.append(String.format("Client %s New Art Rate %sMbps; ", host.getFlClientCID(), bitToMbit(artAssignedRates.get(host))));
            }
            clientLogger.append("\n");
        }
        if (iterator.hasNext()) {
            clientLogger.append("\t\tAffected By Add: ");
            iterator.forEachRemaining(host -> clientLogger.append(String.format("Client %s - New Art Rate %sMbps; ", host.getFlClientCID(), bitToMbit(artAssignedRates.get(host)))));
            clientLogger.append("\n");
        }
    }

    private HashMap<MyPath, Double> scorePaths(Set<MyPath> paths) {
        TotalLoadComparator comparator = new TotalLoadComparator(paths, 0.6, 0.2, 0.1, 0.1);
        Function<MyPath, Number> pathScore = path -> comparator.computeScore(path)[0];
        HashMap<MyPath, Double> pathScores = new HashMap<>();
        paths.forEach(path -> pathScores.put(path, pathScore.apply(path).doubleValue()));
//        debugPaths(pathScores);
        return pathScores;
    }

    private void debugPaths(HashMap<MyPath, Double> pathScores) {
        StringBuilder builder = new StringBuilder("\tLog paths score for client\n");
        pathScores.forEach((path, score) -> builder.append("\t\tScore: ").append(score).append("- Path: ").append(path.format()).append("\n"));
        Util.log("debug_paths" + this.direction, builder.toString());
    }

    private void debugPaths(TotalLoadComparator comparator, List<PathScore> pathScores) {
        StringBuilder builder = new StringBuilder("\tLog paths score for client\n");
        pathScores.forEach(ps -> builder.append("\t\tScore: ").append(comparator.debugScore(ps.path)).append("- Path: ").append(ps.path.format()).append("\n"));
        Util.log("debug_paths" + this.direction, builder.toString());
    }

    private boolean shouldSwitchPath(MyPath currentPath, Map.Entry<MyPath, Double> bestPath, HashMap<MyPath, Double> bestPaths, StringBuilder clientLogger) {
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

    private void updateClientPath(FLHost client, MyPath newPath, Set<FLHost> affectedClients, StringBuilder clientLogger) {
        affectedClients.addAll(newPath.addFlow(client, this.direction));
        client.setCurrentPath(newPath, this.direction);
        clientLogger.append(String.format("\t\tRate %sMbps, ActiveFlows %.2f: %s\n",
                bitToMbit(newPath.getCurrentFairShare()), newPath.getCurrentActiveFlows(), newPath.format()));
    }

    private void updateTimeAndRate(FLHost client, Set<FLHost> affectedClients) {
        long fairShare = client.getCurrentPath(this.direction).getCurrentFairShare();
        int completionTime = (int) Math.round(1.0 * dataRemaining.get(client) / fairShare);
        completionTimes.put(client, completionTime);
        assumedRates.put(client, fairShare);

        for (FLHost affectedClient : affectedClients) {
            long updatedFairShare = affectedClient.getCurrentPath(this.direction).getCurrentFairShare();
            int updatedCompletionTime = (int) Math.round(1.0 * dataRemaining.get(affectedClient) / updatedFairShare);
            completionTimes.put(affectedClient, updatedCompletionTime);
            assumedRates.put(affectedClient, updatedFairShare);
        }
    }

    private void updateClientsStats() {

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
                        client.getFlClientCID(), bitToMbit(assumedRates.get(client)), bitToMbit(assignedRate), remainingTime, bitToMbit(dataRemain) ));
            }
            phase2Logger.append(String.format("\t** Completed %s/%s Clients**\n", completedClients.size(), ClientInformationDatabase.INSTANCE.getTotalFLClients()));
            phase2Logger.append("\t** Finishing Internal Round**\n");
            Util.log("greedy" + this.direction, phase2Logger.toString());
            Util.log("overhead", String.format("controller,phase2,%s", System.currentTimeMillis() - tik));
        } catch (Exception e) {
            Util.log("greedy" + this.direction, "ERROR: " + e.getMessage() + "..." + Arrays.toString(e.getStackTrace()));
        }
    }

    static class PathScore {
        MyPath path;
        Number score;

        public PathScore(MyPath path, Number score) {
            this.path = path;
            this.score = score;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PathScore pathScore = (PathScore) o;
            return path.equals(pathScore.path);
        }

        @Override
        public int hashCode() {
            return path.hashCode();
        }
    }
}
