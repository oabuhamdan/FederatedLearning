package edu.uta.flowsched.schedulers;

import edu.uta.flowsched.FlowDirection;
import edu.uta.flowsched.MyPath;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public class HybridCapacityScheduler extends GreedyFlowScheduler {
    private static final GreedyFlowScheduler S2C_INSTANCE = new HybridCapacityScheduler(FlowDirection.S2C);
    private static final GreedyFlowScheduler C2S_INSTANCE = new HybridCapacityScheduler(FlowDirection.C2S);

    private HybridCapacityScheduler(FlowDirection direction) {
        super(direction);
    }

    public static GreedyFlowScheduler getInstance(FlowDirection direction) {
        return direction.equals(FlowDirection.S2C) ? S2C_INSTANCE : C2S_INSTANCE;
    }

    @Override
    protected HashMap<MyPath, Double> scorePaths(Set<MyPath> paths, boolean initial) {
        double[] values = initial ? new double[]{0.0, 0.7, 0.3, 0.0} : new double[]{0.6, 0.2, 0.1, 0.1};
        HybridScoreCompute computer = new HybridScoreCompute(paths, values[0], values[1], values[2], values[3]);
        Function<MyPath, Number> pathScore = computer::computeScore;
        HashMap<MyPath, Double> pathScores = new HashMap<>();
        paths.forEach(path -> pathScores.put(path, pathScore.apply(path).doubleValue()));
        return pathScores;
    }

    private static class HybridScoreCompute {
        private double maxFairShare;
        private double maxFreeCap;
        private double minFairShare;
        private double minFreeCap;
        private double maxHopCount;
        private double minHopCount;
        private double maxActiveFlows;
        private double minActiveFlows;
        private final double weightFairShare;
        private final double weightHopCount;
        private final double weightFreeCap;
        private final double weightActiveFlows;
        private final Map<MyPath, Long> projectedFairShare;
        private final Map<MyPath, Long> pathFreeCapacity;
        private final Map<MyPath, Double> activeFlows;

        public HybridScoreCompute(Collection<MyPath> paths, double weightFairShare, double weightFreeCap, double weightHopCount, double weightActiveFlows) {
            this.projectedFairShare = new HashMap<>();
            this.pathFreeCapacity = new HashMap<>();
            this.activeFlows = new HashMap<>();
            this.weightFairShare = weightFairShare;
            this.weightHopCount = weightHopCount;
            this.weightFreeCap = weightFreeCap;
            this.weightActiveFlows = weightActiveFlows;

            // Calculate Min-Max
            this.minFairShare = Double.MAX_VALUE;
            this.maxFairShare = Double.MIN_VALUE;

            this.minFreeCap = Double.MAX_VALUE;
            this.maxFreeCap = Double.MIN_VALUE;

            this.maxHopCount = Double.MIN_VALUE;
            this.minHopCount = Double.MAX_VALUE;

            this.maxActiveFlows = Double.MIN_VALUE;
            this.minActiveFlows = Double.MAX_VALUE;


            for (MyPath path : paths) {
                long fairShare = path.getProjectedFairShare();
                long freeCap = path.getBottleneckFreeCap();
                double hopCount = path.linksNoEdge().size();
                double activeFlows = path.getCurrentActiveFlows();

                this.projectedFairShare.put(path, fairShare);
                this.pathFreeCapacity.put(path, freeCap);
                this.activeFlows.put(path, activeFlows);

                this.minFairShare = Math.min(this.minFairShare, fairShare);
                this.maxFairShare = Math.max(this.maxFairShare, fairShare);
                this.minFreeCap = Math.min(this.minFreeCap, freeCap);
                this.maxFreeCap = Math.max(this.maxFreeCap, freeCap);
                this.maxHopCount = Math.max(this.maxHopCount, hopCount);
                this.minHopCount = Math.min(this.minHopCount, hopCount);
                this.maxActiveFlows = Math.max(this.maxActiveFlows, activeFlows);
                this.minActiveFlows = Math.min(this.minActiveFlows, activeFlows);
            }
        }

        public Number computeScore(MyPath path) {
            long fairShare = this.projectedFairShare.get(path);
            int hopCount = path.linksNoEdge().size();
            double activeFlows = this.activeFlows.get(path);
            double freeCapacity = this.pathFreeCapacity.get(path);

            double normalizedFreeCap = normalize(freeCapacity, minFreeCap, maxFreeCap, false);
            double normalizedFairShare = normalize(fairShare, minFairShare, maxFairShare, false);
            double normalizedHopCount = normalize(hopCount, minHopCount, maxHopCount, true);
            double normalizedActiveFlows = normalize(activeFlows, minActiveFlows, maxActiveFlows, true);

            return weightFairShare * normalizedFairShare + weightFreeCap * normalizedFreeCap +
                    weightActiveFlows * normalizedActiveFlows + weightHopCount * normalizedHopCount;
        }

        private double normalize(double rawScore, double minVal, double maxVal, boolean invert) {
            if (Math.abs(maxVal - minVal) < 1e-10)
                return 0.5;
            if (invert)
                return (maxVal - rawScore) / (maxVal - minVal);
            return (rawScore - minVal) / (maxVal - minVal);
        }
    }
}
