package edu.uta.flowsched;

import java.util.DoubleSummaryStatistics;
import java.util.List;

public class MyPathDeepCopy {
    private static final double CAPACITY_WEIGHT = 0.5;
    private static final double HOP_COUNT_WEIGHT = 0.3;
    private static final double AVG_LOAD_WEIGHT = 0.2;
    private static final int MAX_REASONABLE_HOPS = 10;

    MyLinkDeepCopy bottleneckLink;
    List<MyLinkDeepCopy> links;

    public List<MyLinkDeepCopy> getLinks() {
        return links;
    }

    public void setLinks(List<MyLinkDeepCopy> links) {
        this.links = links;
    }


    public MyLinkDeepCopy getBottleneckLink() {
        return bottleneckLink;
    }

    public void setBottleneckLink(MyLinkDeepCopy bottleneckLink) {
        this.bottleneckLink = bottleneckLink;
    }

    public String debugScore() {
        double normalizedBottleneckFreeCap = getNormalizedBottleneckScore();
        double normalizedAverageFreeCap = getNormalizedAverageFreeCap();
        double normalizedHopCount = getNormalizedHopCount();

        double total = (normalizedBottleneckFreeCap * CAPACITY_WEIGHT)
                + (normalizedAverageFreeCap * AVG_LOAD_WEIGHT)
                + (normalizedHopCount * HOP_COUNT_WEIGHT);
        return String.format("\tScore is:  %s + %s + %s = %s", (normalizedBottleneckFreeCap * CAPACITY_WEIGHT)
                , (normalizedAverageFreeCap * AVG_LOAD_WEIGHT)
                , (normalizedHopCount * HOP_COUNT_WEIGHT), total);
    }

    private double getNormalizedHopCount() {
        return 1 - getLinks().size() / (double) MAX_REASONABLE_HOPS;
    }

    private double getNormalizedBottleneckScore() {
        MyLinkDeepCopy bottleneckLink = getBottleneckLink();
        long freeCap = bottleneckLink.getEstimatedFreeCapacity();
        double fairShare = (double) freeCap / bottleneckLink.getActiveFlows();
        return ((freeCap + fairShare) / 2) / bottleneckLink.getDefaultCapacity();
    }

    private double getNormalizedAverageFreeCap() {
        DoubleSummaryStatistics stats = new DoubleSummaryStatistics();
        List<MyLinkDeepCopy> links = getLinks();
        for (MyLinkDeepCopy link : links) {
            if (link.getType().equalsIgnoreCase("EDGE"))
                continue;
            stats.accept((double) link.getEstimatedFreeCapacity() / link.getDefaultCapacity());
        }

        return stats.getAverage();
    }
}
