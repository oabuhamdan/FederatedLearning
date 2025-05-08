package edu.uta.flowsched.schedulers;

import edu.uta.flowsched.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;

public class HybridCapacityScheduler2 extends GreedyFlowScheduler {
    private static final GreedyFlowScheduler S2C_INSTANCE = new HybridCapacityScheduler2(FlowDirection.S2C);
    private static final GreedyFlowScheduler C2S_INSTANCE = new HybridCapacityScheduler2(FlowDirection.C2S);

    private HybridCapacityScheduler2(FlowDirection direction) {
        super(direction);
    }

    public static GreedyFlowScheduler getInstance(FlowDirection direction) {
        return direction.equals(FlowDirection.S2C) ? S2C_INSTANCE : C2S_INSTANCE;
    }

    @Override
    protected HashMap<MyPath, Double> scorePaths(Set<MyPath> paths, boolean initial) {
        HybridScoreCompute computer = new HybridScoreCompute(paths);
        Function<MyPath, Number> pathScore = computer::computeScore;
        HashMap<MyPath, Double> pathScores = new HashMap<>();
        paths.forEach(path -> pathScores.put(path, pathScore.apply(path).doubleValue()));
        return pathScores;
    }

    private static class HybridScoreCompute {
        private double maxEffectiveScore;
        private double minEffectiveScore;
        private final Map<MyPath, Double> effectiveScore;

        public HybridScoreCompute(Collection<MyPath> paths) {
            this.effectiveScore = new HashMap<>();

            // Calculate Min-Max
            this.minEffectiveScore = Double.MAX_VALUE;
            this.maxEffectiveScore = Double.MIN_VALUE;


            for (MyPath path : paths) {
                double effectiveScore = path.effectiveScore();
                this.effectiveScore.put(path, effectiveScore);
                this.minEffectiveScore = Math.min(this.minEffectiveScore, effectiveScore);
                this.maxEffectiveScore = Math.max(this.maxEffectiveScore, effectiveScore);
            }
        }

        public Number computeScore(MyPath path) {
            double effectiveScore = this.effectiveScore.get(path);
            return normalize(effectiveScore, minEffectiveScore, maxEffectiveScore, false);
        }

        private double normalize(double rawScore, double minVal, double maxVal, boolean invert) {
            if (Math.abs(maxVal - minVal) < 1e-10)
                return 0.5;
            if (invert)
                return (maxVal - rawScore) / (maxVal - minVal);
            return (rawScore - minVal) / (maxVal - minVal);
        }
    }

    protected void debugPaths(FLHost client, HashMap<MyPath, Double> pathScores) {
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss");
        StringBuilder builder = new StringBuilder(String.format("-------- Log paths score for client %s at %s --------\n", client.getFlClientCID(), now.format(formatter)));
        pathScores.entrySet()
                .stream()
                .sorted(Comparator.comparingDouble(Map.Entry::getValue))
                .forEach(entry ->
                        {
                            MyPath path = entry.getKey();
                            builder.append(String.format("\t\tScore: %.2f", entry.getValue()))
                                    .append(" - Path ").append(path.getClass().getName()).append(" : ")
                                    .append(String.format(" -- Effective Score: %.2f, PFS: %sMbps, FC:%sMbps, AF: %s", path.effectiveScore(), Util.bitToMbit(path.getProjectedFairShare()), Util.bitToMbit(path.getBottleneckFreeCap()), path.getCurrentActiveFlows()))
                                    .append("\n");
                            path.links().forEach(l -> {
                                MyLink link = (MyLink) l;
                                builder.append(String.format("\t\t\t - Link %s ->  FS %sMbps FC %sMbps AF %s latency %.1f packetLoss %.1f \n", link.format(),
                                        Util.bitToMbit(link.getProjectedFairShare()), Util.bitToMbit(link.getEstimatedFreeCapacity()),link.getActiveFlows(), link.getLatency(), link.getPacketLoss()));
                            });
                        }
                );
        Util.log("debug_paths" + this.direction, builder.toString());
    }
}
