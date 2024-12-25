package edu.uta.flowsched;

import org.onosproject.net.Link;

import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

public class TotalLoadComparator implements Comparator<MyPath> {
    private static final double CAPACITY_WEIGHT = 0.6;
    private static final double AVG_LOAD_WEIGHT = 0.3;
    private static final double HOP_COUNT_WEIGHT = 0.1;
    private static final int MAX_REASONABLE_HOPS = 10;


    public TotalLoadComparator() {

    }

    @Override
    public int compare(MyPath path1, MyPath path2) {
        double score1 = computeScore(path1);
        double score2 = computeScore(path2);

        return Double.compare(score2, score1); // Should be flipped
    }

    public double computeScore(MyPath path) {
        double normalizedCapacity = getNormalizedBottleneckCapacity(path);
        double normalizedHopCount = getNormalizedHopCount(path);
        double normalizedAverageLoad = getNormalizedAverageLoad(path);

        return (normalizedCapacity * CAPACITY_WEIGHT) +
                (normalizedHopCount * HOP_COUNT_WEIGHT) +
                (normalizedAverageLoad * AVG_LOAD_WEIGHT);
    }

    public String debug(MyPath path) {
        return "***************************************************\n" +
                String.format("Score is %s\n", computeScore(path)) +
                String.format("Bottleneck link is %s\n", Util.formatLink(path.getBottleneckLink())) +
                String.format("Normalized Bottleneck Capacity is %s\n", getNormalizedBottleneckCapacity(path)) +
                String.format("Bottleneck RC %s, CT %s\n", path.getBottleneckLink().getReservedCapacity(), path.getBottleneckLink().getCurrentThroughput()) +
//                String.format("Normalized Length is %s\n", path.links().size() / maxPathsLength) +
                "***************************************************";
    }

    private double getNormalizedHopCount(MyPath path) {
        return 1.0 - (Math.min(path.links().size(), MAX_REASONABLE_HOPS) / (double) MAX_REASONABLE_HOPS);
    }
    private double getNormalizedBottleneckCapacity(MyPath path) {
        MyLink bottleneckLink = path.getBottleneckLink();
        return (double) bottleneckLink.getEstimatedFreeCapacity() / bottleneckLink.getDefaultCapacity();
    }

    private double getNormalizedAverageLoad(MyPath path) {
        double totalFreeCapacityRatio = 0.0;
        List<Link> links = path.links();

        for (Link link : links) {
            if (link.type().equals(Link.Type.EDGE))
                continue;
            MyLink myLink = (MyLink) link;
            double freeCapacityRatio = (double) myLink.getEstimatedFreeCapacity() / myLink.getDefaultCapacity();
            totalFreeCapacityRatio += freeCapacityRatio;
        }

        return (totalFreeCapacityRatio / links.size() - 2);
    }
}
