package edu.uta.flowsched;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static edu.uta.flowsched.Util.bitToMbit;

@SuppressWarnings("Convert2MethodRef")
public class GreedyFlowScheduler {
    public static final GreedyFlowScheduler S2C_INSTANCE = new GreedyFlowScheduler(FlowDirection.S2C);
    public static final GreedyFlowScheduler C2S_INSTANCE = new GreedyFlowScheduler(FlowDirection.C2S);
    private static final double SWITCH_THRESHOLD = 0.3;
    private static final long DATA_SIZE = 138_400_000; // 17MByte
    private static final long ALMOST_DONE_DATA_THRESH = DATA_SIZE / 5; // 17MByte
    private static final int TOTAL_CLIENTS = 10;
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
    private int round;


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
        round = 1;
    }

    public static void activate() {
        Util.log("greedy", "Activated ...");
        executor = Executors.newFixedThreadPool(2);
        waitExecutor = Executors.newScheduledThreadPool(TOTAL_CLIENTS);
    }

    public void startScheduling() {
        Util.log("greedy", String.format("Round %s Started for %s", round, direction));
        executor.submit(this::scheduleFlows);
    }

    public int getRound() {
        return round;
    }

    public static void deactivate() {
        executor.shutdownNow();
    }

    public void addClient(FLHost client, Set<MyPath> paths) {
        clientQueue.offer(client); // Add client to the queue
        if (!clientPaths.containsKey(client)) {
            clientPaths.put(client, paths);
        }
//        Util.log("greedy", String.format("Added Client %s to Queue ...", client.getFlClientCID()));
        synchronized (this) {
            notify(); // Notify the scheduler in case it's waiting
        }
    }

    private void drainNewClients(ConcurrentLinkedQueue<FLHost> phase1Queue) {
        FLHost client;
        while ((client = clientQueue.poll()) != null) {
            phase1Queue.add(client);
//            Util.log("greedy", String.format("Added Client %s to Active in %s ...", client.getFlClientCID(), direction));
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
        while (completedClients.size() < TOTAL_CLIENTS) {
            try {
                getClientsFromQueue(needPhase1Processing);
                StringBuilder phase1Logger = new StringBuilder("***Processing in Phase1 ****\n");

                FLHost client;
                while ((client = needPhase1Processing.poll()) != null) {
                    if (!clientAlmostDone(client, dataRemaining, completionTimes))
                        processClient(client, completionTimes, dataRemaining, assumedRates, phase1Logger);

                    needPhase2Processing.add(client);
                }

                if (!needPhase2Processing.isEmpty()) {
                    phase2(completionTimes, dataRemaining, assumedRates, needPhase1Processing, needPhase2Processing, completedClients);
                }

                Util.log("greedy", phase1Logger.toString());
                PathRulesInstaller.INSTANCE.increasePriority();
            } catch (Exception e) {
                Util.log("greedy", "Error in scheduler: " + e.getMessage() + "...." + Arrays.toString(Arrays.stream(e.getStackTrace()).toArray()));
            }
        }
        completedClients.clear();
        needPhase1Processing.clear();
        needPhase2Processing.clear();
        Util.log("greedy", String.format("Completed Round %s for %s", round++, direction));
        Util.flushWriters();
    }


    private void processClient(FLHost client, Map<FLHost, Integer> completionTimes, Map<FLHost, Long> dataRemaining,
                               Map<FLHost, Long> artAssignedRates, StringBuilder internalLogger) {
        Set<FLHost> affectedClients = ConcurrentHashMap.newKeySet();
        dataRemaining.putIfAbsent(client, DATA_SIZE);
        StringBuilder clientLogger = new StringBuilder(String.format("\t**Processing Client %s**\n", client.getFlClientCID()));

        List<PathScore> bestPaths = findBestPath(client);
        MyPath currentPath = client.getCurrentPath(this.direction);
        if (shouldSwitchPath(client, bestPaths, internalLogger)) {
            if (currentPath != null) {
                clientLogger.append(String.format("\t\tCurrent Path: %s\n", Util.pathFormat(currentPath)));
                affectedClients.addAll(currentPath.removeFlow(client, this.direction));
            }
//            int affectedByRemove = affectedClients.size();

            MyPath bestPath = bestPaths.get(0).path;
            updateClientPath(client, bestPath, affectedClients, clientLogger);
            updateTimeAndRate(client, affectedClients, completionTimes, dataRemaining, artAssignedRates);
//            logAffectedClients(affectedClients, artAssignedRates, affectedByRemove, clientLogger);

            PathRulesInstaller.INSTANCE.installPathRules(client, bestPath);
        }
        internalLogger.append(clientLogger);
    }

    private void phase2(Map<FLHost, Integer> completionTimes, Map<FLHost, Long> dataRemaining,
                        Map<FLHost, Long> artAssignedRates, ConcurrentLinkedQueue<FLHost> needPhase1Processing,
                        ConcurrentLinkedQueue<FLHost> needPhase2Processing, ConcurrentLinkedQueue<FLHost> completedClients) {
        if (future == null || future.isDone()) {
            future = waitExecutor.schedule(() -> {
                updateClientsStatus(needPhase2Processing, needPhase1Processing, completedClients, completionTimes, dataRemaining, artAssignedRates);
                synchronized (this) {
                    notifyAll(); // Notify the scheduler in case it's waiting
                }
            }, Util.POLL_FREQ, TimeUnit.SECONDS);
        }
    }


    private boolean clientAlmostDone(FLHost client, Map<FLHost, Long> dataRemaining, Map<FLHost, Integer> completionTimes) {
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


    private List<PathScore> findBestPath(FLHost client) {
        Set<MyPath> paths = clientPaths.get(client);

//        TotalLoadComparator comparator = new TotalLoadComparator(paths, 0.6, 0.2, 0.1, 0.1);
        Function<MyPath, Number> pathScore = path -> path.getBottleneckFreeCap() / 1e6;

        List<PathScore> pathScores = paths.stream()
                .map(path -> new PathScore(path, pathScore.apply(path)))
                .sorted(Comparator.comparing(ps -> ((PathScore) ps).score.doubleValue()).reversed())
                .collect(Collectors.toList());

//        debugPaths(client, comparator, pathScores);
        return pathScores;
    }

    private void debugPaths(FLHost client, TotalLoadComparator comparator, List<PathScore> pathScores) {
        StringBuilder builder = new StringBuilder(String.format("\tLog paths score for client %s direction %s\n", client.getFlClientCID(), this.direction));
        pathScores.forEach(ps -> builder.append("\t\tScore: ").append(comparator.debugScore(ps.path)).append("Path: ").append(ps.path.format()).append("\n"));
        Util.log("debug_paths", builder.toString());
    }

    private boolean shouldSwitchPath(FLHost client, List<PathScore> bestPaths, StringBuilder internalLogger) {
        MyPath currentPath = client.getCurrentPath(this.direction);
        if (currentPath == null) return true;

        double currentScore = bestPaths.get(bestPaths.indexOf(new PathScore(currentPath, 0))).score.doubleValue();
        double bestScore = bestPaths.get(0).score.doubleValue();
        boolean switching = (bestScore - currentScore) / currentScore >= SWITCH_THRESHOLD;
        if (switching)
            internalLogger.append(String.format("\t\tSwitching Path for Client %s. New Score: %s, Old Score: %s\n", client.getFlClientCID(), bestScore, currentScore));
        return switching;
    }

    private void updateClientPath(FLHost client, MyPath newPath, Set<FLHost> affectedClients, StringBuilder clientLogger) {
        affectedClients.addAll(newPath.addFlow(client, this.direction));
        client.setCurrentPath(newPath, this.direction);
        clientLogger.append(String.format("\t\tNew Path - Rate %sMbps, ActiveFlows %.2f: %s\n",
                bitToMbit(newPath.getCurrentFairShare()), newPath.getCurrentActiveFlows(), Util.pathFormat(newPath)));
    }

    private void updateTimeAndRate(FLHost client, Set<FLHost> affectedClients,
                                   Map<FLHost, Integer> completionTimes, Map<FLHost, Long> dataRemaining, Map<FLHost, Long> artAssignedRates) {

        long fairShare = client.getCurrentPath(this.direction).getCurrentFairShare();
        int completionTime = (int) Math.round(1.0 * dataRemaining.get(client) / fairShare);
        completionTimes.put(client, completionTime);
        artAssignedRates.put(client, fairShare);

        for (FLHost affectedClient : affectedClients) {
            long updatedFairShare = affectedClient.getCurrentPath(this.direction).getCurrentFairShare();
            int updatedCompletionTime = (int) Math.round(1.0 * dataRemaining.get(affectedClient) / updatedFairShare);
            completionTimes.put(affectedClient, updatedCompletionTime);
            artAssignedRates.put(affectedClient, updatedFairShare);
        }
    }

    private void updateClientsStatus(ConcurrentLinkedQueue<FLHost> needPhase2Processing,
                                     ConcurrentLinkedQueue<FLHost> needPhase1Processing,
                                     ConcurrentLinkedQueue<FLHost> completedClients,
                                     Map<FLHost, Integer> completionTimes,
                                     Map<FLHost, Long> dataRemaining,
                                     Map<FLHost, Long> artAssignedRates) {
        StringBuilder phase2Logger = new StringBuilder();

        phase2Logger.append("\tPhase 2: ");
        needPhase1Processing.forEach(h -> phase2Logger.append(h.getFlClientCID()).append(","));
        phase2Logger.append("\n");

        try {
            FLHost client;
            while ((client = needPhase2Processing.poll()) != null) {
                long assignedRate = (long) Math.max(client.networkStats.getLastPositiveRate(this.direction), 1e6);
                long dataRemain = DATA_SIZE - client.networkStats.getRoundExchangedData(this.direction, round);
                int remainingTime = (int) (dataRemain / assignedRate);

                if (remainingTime <= 0 || dataRemain <= 0) {
                    completedClients.add(client);
                    MyPath path = client.getCurrentPath(this.direction);
                    if (path != null) {
                        path.removeFlow(client, this.direction);
                    } else {
                        phase2Logger.append(String.format("\t - !! Client %s has no %s Path !!", client.getFlClientCID(), this.direction));
                    }
                    client.setCurrentPath(null, this.direction);
                    completionTimes.remove(client);
                    dataRemaining.remove(client);
                } else {
                    dataRemaining.put(client, dataRemain);
                    completionTimes.put(client, remainingTime);
                    needPhase1Processing.add(client);
                }
                phase2Logger.append(String.format("\t - Client %s: Art Rate: %sMbps, Real Rate:%sMbps, Real Rem Time: %ss, Real Rem Data: %sMbits \n",
                        client.getFlClientCID(), bitToMbit(artAssignedRates.get(client)), bitToMbit(assignedRate), remainingTime, bitToMbit(dataRemain)));
            }
            phase2Logger.append(String.format("\t** Completed %s/%s Clients**\n", completedClients.size(), TOTAL_CLIENTS));
            phase2Logger.append("\t** Finishing Internal Round**\n");
            Util.log("greedy", phase2Logger.toString());
        } catch (Exception e) {
            Util.log("greedy", e.getMessage() + "..." + Arrays.toString(e.getStackTrace()));
        }
    }

    MyPath getStaticPaths(FLHost flHost) {
        PathInformationDatabase instance = PathInformationDatabase.INSTANCE;
        switch (flHost.getFlClientCID()) {
            case "0":
                return instance.getPathFromString(flHost, "FLServer -> 1001 -> 1008 -> FL#0", "S2C");
            case "1":
                return instance.getPathFromString(flHost, "FLServer -> 1001 -> 1009 -> FL#1", "S2C");
            case "2":
                return instance.getPathFromString(flHost, "FLServer -> 1001 -> 1003 -> 1004 -> 1002 -> FL#2", "S2C");
            case "3":
                return instance.getPathFromString(flHost, "FLServer -> 1001 -> 1003 -> 1004 -> FL#3", "S2C");
            case "4":
                return instance.getPathFromString(flHost, "FLServer -> 1001 -> 1008 -> 1006 -> 1005 -> FL#4", "S2C");
            case "5":
                return instance.getPathFromString(flHost, "FLServer -> 1001 -> 1007 -> 1006 -> FL#5", "S2C");
            case "6":
                return instance.getPathFromString(flHost, "FLServer -> 1001 -> 1009 -> 1000 -> FL#6", "S2C");
            case "7":
                return instance.getPathFromString(flHost, "FLServer -> 1001 -> 1000 -> 1004 -> 1003 -> 1007 -> FL#7", "S2C");
            case "8":
                return instance.getPathFromString(flHost, "FLServer -> 1001 -> 1008 -> FL#8", "S2C");
            case "9":
                return instance.getPathFromString(flHost, "FLServer -> 1001 -> 1000 -> 1009 -> FL#9", "S2C");
        }
        return null;
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
