package edu.uta.flowsched;

import org.onosproject.net.Link;

import java.util.Comparator;
import java.util.DoubleSummaryStatistics;
import java.util.List;

public class TotalLoadComparator implements Comparator<MyPath> {
    private static final double CAPACITY_WEIGHT = 0.5;
    private static final double AVG_LOAD_WEIGHT = 0.2;
    private static final double HOP_COUNT_WEIGHT = 0.3;
    private static final int MAX_REASONABLE_HOPS = 10;


    public TotalLoadComparator() {

    }

    @Override
    public int compare(MyPath path1, MyPath path2) {
        double score1 = computeScore(path1);
        double score2 = computeScore(path2);

        return Double.compare(score2, score1);
    }

    public double computeScore(MyPath path) {
        double normalizedBottleneckFreeCap = getNormalizedBottleneckScore(path);
        double normalizedAverageFreeCap = getNormalizedAverageFreeCap(path);
        double normalizedHopCount = getNormalizedHopCount(path);

        return (normalizedBottleneckFreeCap * CAPACITY_WEIGHT)
                + (normalizedAverageFreeCap * AVG_LOAD_WEIGHT)
                + (normalizedHopCount * HOP_COUNT_WEIGHT);
    }

    public String debugScore(MyPath path) {
        double normalizedBottleneckFreeCap = getNormalizedBottleneckScore(path);
        double normalizedAverageFreeCap = getNormalizedAverageFreeCap(path);
        double normalizedHopCount = getNormalizedHopCount(path);

        double total = (normalizedBottleneckFreeCap * CAPACITY_WEIGHT)
                + (normalizedAverageFreeCap * AVG_LOAD_WEIGHT)
                + (normalizedHopCount * HOP_COUNT_WEIGHT);
        return String.format("\tScore is:  %s + %s + %s = %s", (normalizedBottleneckFreeCap * CAPACITY_WEIGHT)
                , (normalizedAverageFreeCap * AVG_LOAD_WEIGHT)
                , (normalizedHopCount * HOP_COUNT_WEIGHT), total);
    }

    private double getNormalizedHopCount(MyPath path) {
        return 1 - path.links().size() / (double) MAX_REASONABLE_HOPS;
    }

    private double getNormalizedBottleneckScore(MyPath path) {
        MyLink bottleneckLink = path.getBottleneckLink();
        long freeCap = bottleneckLink.getEstimatedFreeCapacity();
        double fairShare = (double) freeCap / bottleneckLink.getActiveFlows();
        return ((freeCap + fairShare) / 2) / bottleneckLink.getDefaultCapacity();
    }

    private double getNormalizedAverageFreeCap(MyPath path) {
        DoubleSummaryStatistics stats = new DoubleSummaryStatistics();
        List<Link> links = path.links();
        for (Link link : links) {
            if (link.type().equals(Link.Type.EDGE))
                continue;
            MyLink myLink = (MyLink) link;
            stats.accept((double) myLink.getEstimatedFreeCapacity() / myLink.getDefaultCapacity());
        }

        return stats.getAverage();
    }
}
