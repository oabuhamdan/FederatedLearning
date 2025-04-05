package edu.uta.flowsched.schedulers;

import edu.uta.flowsched.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
        Function<MyPath, Number> pathScore = MyPath::getBottleneckFreeCap;
        HashMap<MyPath, Double> pathScores = new HashMap<>();
        paths.forEach(path -> pathScores.put(path, pathScore.apply(path).doubleValue()));
        return pathScores;
    }

    @Override
    protected void phase1(FLHost client, StringBuilder internalLogger) {
        Set<FLHost> affectedClients = ConcurrentHashMap.newKeySet();
        StringBuilder clientLogger = new StringBuilder(String.format("\t- Client %s: \n", client.getFlClientCID()));

        MyPath currentPath = client.getCurrentPath(this.direction);

        HashMap<MyPath, Double> bestPaths = scorePaths(clientPaths.get(client), false);
        Map.Entry<MyPath, Double> bestPath = bestPaths.entrySet()
                .stream()
                .max(Comparator.comparingDouble(Map.Entry::getValue))
                .orElseThrow(() -> new NoSuchElementException("Map is empty"));

        if (currentPath == null) {
            updateClientPath(client, bestPath.getKey(), affectedClients, clientLogger);
            updateTimeAndRate(client, affectedClients);
        }
        internalLogger.append(clientLogger);
    }
}
