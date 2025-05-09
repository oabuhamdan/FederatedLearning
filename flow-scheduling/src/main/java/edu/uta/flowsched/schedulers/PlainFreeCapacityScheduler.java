package edu.uta.flowsched.schedulers;

import edu.uta.flowsched.FLHost;
import edu.uta.flowsched.FlowDirection;
import edu.uta.flowsched.MyPath;

import java.util.*;
import java.util.function.Function;

public class PlainFreeCapacityScheduler extends GreedyFlowScheduler {
    private static final GreedyFlowScheduler S2C_INSTANCE = new PlainFreeCapacityScheduler(FlowDirection.S2C);
    private static final GreedyFlowScheduler C2S_INSTANCE = new PlainFreeCapacityScheduler(FlowDirection.C2S);

    private PlainFreeCapacityScheduler(FlowDirection direction) {
        super(direction);
    }

    public static GreedyFlowScheduler getInstance(FlowDirection direction) {
        return direction.equals(FlowDirection.S2C) ? S2C_INSTANCE : C2S_INSTANCE;
    }

    @Override
    protected HashMap<MyPath, Double> scorePaths(Set<MyPath> paths, boolean initial) {
        Function<MyPath, Number> pathScore = path -> path.getBottleneckFreeCap() / 1e6;
        HashMap<MyPath, Double> pathScores = new HashMap<>();
        paths.forEach(path -> pathScores.put(path, pathScore.apply(path).doubleValue()));
        return pathScores;
    }

    @Override
    protected void phase1(StringBuilder internalLogger) {
        FLHost client;
        while ((client = needPhase1Processing.poll()) != null) {
            if (!clientAlmostDone(client)) {
                StringBuilder clientLogger = new StringBuilder();
                MyPath currentPath = client.getCurrentPath();
                if (currentPath == null) {
                    clientLogger.append(String.format("\t- Client %s: \n", client.getFlClientCID()));
                    HashMap<MyPath, Double> bestPaths = scorePaths(clientPaths.get(client), false);
                    Map.Entry<MyPath, Double> bestPath = bestPaths.entrySet()
                            .stream()
                            .max(Comparator.comparingDouble(Map.Entry::getValue))
                            .orElseThrow(() -> new NoSuchElementException("Map is empty"));

                    Set<FLHost> affectedClients = client.assignNewPath(bestPath.getKey());
                    String newPathFormat = bestPath.getKey().format();
                    clientLogger.append(String.format("\t\tNew Path: %s\n", newPathFormat));
                    updateTimeAndRate(affectedClients, internalLogger);
                }
                internalLogger.append(clientLogger);
            }
        }
    }
}
