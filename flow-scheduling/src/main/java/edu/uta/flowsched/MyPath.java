package edu.uta.flowsched;

import org.onlab.graph.ScalarWeight;
import org.onosproject.net.DefaultPath;
import org.onosproject.net.Link;
import org.onosproject.net.Path;
import org.onosproject.net.provider.ProviderId;

import java.util.Comparator;
import java.util.stream.Collectors;

public class MyPath extends DefaultPath {
    private static final ProviderId PID = new ProviderId("flowsched", "edu.uta.flowsched", true);
    private MyLink bottleneckLink;
    private long availableCapacity;
    private double delay;
    private long lastUpdated;


    public MyPath(Path path) {
        super(PID, path.links().stream().map(LinkInformationDatabase.INSTANCE::getLinkInformation)
                .collect(Collectors.toList()), new ScalarWeight(1));
    }

    public MyLink getBottleneckLink() {
        return this.bottleneckLink == null? (MyLink) links().get(links().size() / 2) : this.bottleneckLink;
    }

    public void updateBottleneckLink() {
        this.bottleneckLink = (MyLink) links().stream().filter(link -> !link.type().equals(Type.EDGE))
                .min(Comparator.comparing(link -> ((MyLink) link).getEstimatedFreeCapacity()))
                .orElse(links().get(0));
    }

    public long getAvailableCapacity() {
        return availableCapacity;
    }

    public void reserveCapacity(long capacity) {
        for (Link link : this.links()) {
            if (!Type.EDGE.equals(link.type()))
                ((MyLink) link).reserveCapacity(capacity);
        }
    }

    public void releaseCapacity(long capacity) {
        for (Link link : this.links()) {
            if (!Type.EDGE.equals(link.type()))
                ((MyLink) link).releaseCapacity(capacity);
        }
    }


    public double getDelay() {
        return delay;
    }

    public void setDelay(double delay) {
        this.delay = delay;
    }


//    @Override
//    public boolean equals(Object obj) {
//        if (this == obj) {
//            return true;
//        }
//        if (obj instanceof Path) {
//            final Path other = (Path) obj;
//            return Objects.equals(
//                    this.links().subList(2, this.links().size() - 1),
//                    other.links().subList(2, this.links().size() - 1)
//            );
//        }
//        return false;
//    }
//
//    @Override
//    public int hashCode() {
//        return this.links().subList(2, this.links().size() - 1).hashCode();
//    }
}