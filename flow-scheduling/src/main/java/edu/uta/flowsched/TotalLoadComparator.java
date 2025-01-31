package edu.uta.flowsched;

import java.util.*;

import static edu.uta.flowsched.Util.bitToMbit;

public class TotalLoadComparator implements Comparator<MyPath> {
    private final Collection<MyPath> paths;
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

    public TotalLoadComparator(Collection<MyPath> paths, double weightFairShare, double weightFreeCap, double weightHopCount, double weightActiveFlows) {
        this.paths = paths;
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

    public double[] computeScore(MyPath path) {
        long fairShare = this.projectedFairShare.get(path);
        int hopCount = path.linksNoEdge().size();
        double activeFlows = this.activeFlows.get(path);
        double freeCapacity = this.pathFreeCapacity.get(path);

        double normalizedFreeCap = (freeCapacity - minFreeCap) / (maxFreeCap - minFreeCap);
        double normalizedFairShare = (fairShare - minFairShare) / (maxFairShare - minFairShare);
        double normalizedHopCount = (maxHopCount - hopCount) / (maxHopCount - minHopCount);
        double normalizedActiveFlows = (maxActiveFlows - activeFlows) / (maxActiveFlows - minActiveFlows);

        double score = weightFairShare * normalizedFairShare + weightFreeCap * normalizedFreeCap + weightActiveFlows * activeFlows + weightHopCount * normalizedHopCount;
        double[] values = {score, fairShare, freeCapacity, activeFlows, hopCount, normalizedFairShare, normalizedFreeCap, normalizedActiveFlows, normalizedHopCount};
        return values;
    }

    @Override
    public int compare(MyPath path1, MyPath path2) {
        double score1 = computeScore(path1)[0];
        double score2 = computeScore(path2)[0];

        return Double.compare(score2, score1);
    }


    public String debugScore(MyPath path) {
        double[] values = computeScore(path);
        return String.format("\tScore is: %.2f, FairShare:%s, FreeCap:%s, ActiveFlows:%.2f HopCount:%s => %.2f*%.2f + %.2f*%.2f + %.2f*%.2f + %.2f*%.2f",
                values[0], bitToMbit(values[1]), bitToMbit(values[2]), values[3], values[4],
                weightFairShare, values[5], weightFreeCap, values[6], weightActiveFlows, values[7], weightHopCount, values[8]);
    }

    public double getMaxFairShare() {
        return maxFairShare;
    }

    public double getMinFairShare() {
        return minFairShare;
    }

    public double getMaxHopCount() {
        return maxHopCount;
    }

    public double getMinHopCount() {
        return minHopCount;
    }

    //
}
