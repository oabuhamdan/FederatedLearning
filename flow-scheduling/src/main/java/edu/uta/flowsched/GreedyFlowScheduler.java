package edu.uta.flowsched;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static edu.uta.flowsched.Util.bitToMbit;

public class GreedyFlowScheduler {
    public static final GreedyFlowScheduler INSTANCE = new GreedyFlowScheduler();
    private static final double THRESHOLD = 0.2;
    private static final long DATA_SIZE = 160000000;
    private static int ROUND = 1;
    private ExecutorService executorService;
    private ScheduledExecutorService scheduler;

    private GreedyFlowScheduler() {
    }

    public void activate() {
        Util.log("greedy", "Activated ...");
        executorService = Executors.newSingleThreadExecutor();
        scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    public void deactivate() {
        executorService.shutdownNow();
        scheduler.shutdownNow();
    }

    public void callS2C(Map<FLHost, List<MyPath>> clientPaths) {
        Util.log("greedy", String.format("************** S2C Round: %s **************", ROUND++));
        scheduleFlows(clientPaths);
    }

    public void callC2S(Map<FLHost, List<MyPath>> clientPaths) {
        Util.log("greedy", String.format("************** C2S Round: %s **************", ROUND++));
        scheduleFlows(clientPaths);
    }

    public void scheduleFlows(Map<FLHost, List<MyPath>> clientPaths) {
        int internalRound = 1;
        Map<FLHost, Integer> completionTimes = new HashMap<>();
        Map<FLHost, Long> dataRemaining = new HashMap<>();
        Map<FLHost, Long> assignedRate = new HashMap<>();
        List<FLHost> activeClients = new ArrayList<>(clientPaths.keySet());
        Set<FLHost> completedClients = new HashSet<>();
        int currentTime = 0;
        try {
            while (!activeClients.isEmpty()) {
                StringBuilder internalLogger = new StringBuilder();
                internalLogger.append(String.format("** Starting Internal Round %s **\n", internalRound));
                for (FLHost client : activeClients) {
                    processClient(client, clientPaths, completionTimes, dataRemaining, assignedRate, internalLogger);
                }

                int minCompletionTime = completionTimes.values().stream()
                        .min(Integer::compare)
                        .orElse(0);

                currentTime += minCompletionTime;

                internalLogger.append(String.format("\t** Sleeping For %ss **\n", minCompletionTime));
                waitTime(currentTime);

                updateClientsStatus(activeClients, completedClients, completionTimes, dataRemaining, assignedRate, minCompletionTime, internalLogger);

                activeClients.removeAll(completedClients);
                completedClients.clear();

                internalLogger.append(String.format("\t ** Finishing Internal Round %s **\n", internalRound++));
                Util.log("greedy", internalLogger.toString());
                PathRulesInstaller.INSTANCE.increasePriority(); // For Next Internal Rounds
            }
        } catch (Exception e) {
            Util.log("greedy", "Error: " + e.getMessage());
        }
        Util.log("greedy", "Total Processing is: " + currentTime);
    }

    private void waitTime(int time) {
        try {
            Thread.sleep(time * 1000L);
        } catch (Exception e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Processing interrupted", e);
        }
    }

    private void processClient(FLHost client, Map<FLHost, List<MyPath>> clientPaths,
                               Map<FLHost, Integer> completionTimes,
                               Map<FLHost, Long> dataRemaining, Map<FLHost, Long> assignedRate, StringBuilder internalLogger) {
        Set<FLHost> affectedClients = new HashSet<>();
        List<MyPath> paths = clientPaths.get(client);
        MyPath currentPath = client.getCurrentPath();
        StringBuilder clientLogger = new StringBuilder(String.format("\t** Processing Client %s **\n", client.getFlClientCID()));

        MyPath bestPath = findBestPath(paths, clientLogger);
        if (bestPath == null) return;

        long projectedFairShare = bestPath.getProjectedFairShare();

        if (shouldSwitchPath(currentPath, projectedFairShare)) {
            if (currentPath != null) {
                clientLogger.append(String.format("\t\tCurrent Path: %s\n", Util.pathFormat(currentPath)));
                affectedClients.addAll(currentPath.removeFlow(client));
            }
            updateClientPath(client, bestPath, completionTimes, dataRemaining, assignedRate, affectedClients, clientLogger);
            PathRulesInstaller.INSTANCE.installPathRules(client, bestPath);
        }
        internalLogger.append(clientLogger);
    }

    private void logAffectedClients(Set<FLHost> affectedClients, int affectedByRemove, StringBuilder clientLogger) {
        Iterator<FLHost> iterator = affectedClients.iterator();
        if (affectedByRemove > 0) {
            clientLogger.append("\t\tAffected By Remove: ");
            int i = 0;
            while (i++ < affectedByRemove && iterator.hasNext()) {
                FLHost host = iterator.next();
                clientLogger.append(String.format("Client %s Rate %sMbps; ", host.getFlClientCID(), bitToMbit(host.getCurrentPath().getCurrentFairShare())));
            }
            clientLogger.append("\n");
        }
        if (iterator.hasNext()) {
            clientLogger.append("\t\tAffected By Add: ");
            iterator.forEachRemaining(host -> clientLogger.append(String.format("Client %s - Rate %sMbps;", host.getFlClientCID(), bitToMbit(host.getCurrentPath().getCurrentFairShare()))));
            clientLogger.append("\n");
        }
    }

    private MyPath findBestPath(List<MyPath> paths, StringBuilder logger) {
        return paths.stream()
                .max(Comparator.comparingLong(MyPath::getProjectedFairShare))
                .orElse(null);
    }

    private boolean shouldSwitchPath(MyPath currentPath, long projectedFairShare) {
        if (currentPath == null) return true;

        long currentFairShare = currentPath.getCurrentFairShare();
        return (projectedFairShare - currentFairShare) / (double) currentFairShare >= THRESHOLD;
    }

    private void updateClientPath(FLHost client, MyPath newPath,
                                  Map<FLHost, Integer> completionTimes,
                                  Map<FLHost, Long> dataRemaining,
                                  Map<FLHost, Long> assignedRate, Set<FLHost> affectedClients, StringBuilder clientLogger) {
        client.setCurrentPath(newPath);

        clientLogger.append(String.format("\t\tNew Path - Rate %sMbps: %s\n", bitToMbit(newPath.getProjectedFairShare()), Util.pathFormat(newPath)));

        assignedRate.put(client, newPath.getProjectedFairShare());

        int affectedByRemove = affectedClients.size();

        affectedClients.addAll(newPath.addFlow(client));

        logAffectedClients(affectedClients, affectedByRemove, clientLogger);

        updateCompletionTimes(client, affectedClients, completionTimes, dataRemaining, assignedRate);
    }

    private void updateCompletionTimes(FLHost client, Set<FLHost> affectedClients,
                                              Map<FLHost, Integer> completionTimes,
                                              Map<FLHost, Long> dataRemaining, Map<FLHost, Long> assignedRate) {
        long fairShare = client.getCurrentPath().getCurrentFairShare();
        int completionTime = (int) Math.ceil(1.0 * dataRemaining.getOrDefault(client, DATA_SIZE) / fairShare);
        completionTimes.put(client, completionTime);

        for (FLHost affectedClient : affectedClients) {
            if (affectedClient.getCurrentPath() != null) {
                long updatedFairShare = affectedClient.getCurrentPath().getCurrentFairShare();
                int updatedCompletionTime = (int) Math.ceil(1.0 * dataRemaining.getOrDefault(affectedClient, DATA_SIZE) / updatedFairShare);
                completionTimes.put(affectedClient, updatedCompletionTime);
                assignedRate.put(affectedClient, updatedFairShare);
            }
        }
    }

    private void updateClientsStatus(List<FLHost> activeClients,
                                            Set<FLHost> completedClients,
                                            Map<FLHost, Integer> completionTimes,
                                            Map<FLHost, Long> dataRemaining,
                                            Map<FLHost, Long> assignedRates, int minCompletionTime, StringBuilder internalLogger) {
        internalLogger.append("\t******** Remaining Time and Data ********\n");
        for (FLHost client : activeClients) {
            int remainingTime = completionTimes.get(client) - minCompletionTime;
            long assignedRate = assignedRates.getOrDefault(client, 0L);
            long dataRemain = (dataRemaining.getOrDefault(client, DATA_SIZE) - (assignedRate * minCompletionTime));

            dataRemaining.put(client, dataRemain);

            if (remainingTime <= 1) {
                completedClients.add(client);
                client.getCurrentPath().removeFlow(client);
                client.setCurrentPath(null);
            } else {
                completionTimes.put(client, remainingTime);
            }
            internalLogger.append(String.format("\t - Client %s: Assigned Rate:%s, Time: %ss, Data: %sMbits \n", assignedRate,
                    client.getFlClientCID(), remainingTime, bitToMbit(dataRemaining.get(client))));
        }
    }
}
