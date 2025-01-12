package edu.uta.flowsched;

import org.onlab.graph.ScalarWeight;
import org.onosproject.net.DefaultPath;
import org.onosproject.net.Link;
import org.onosproject.net.Path;
import org.onosproject.net.provider.ProviderId;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MyPath extends DefaultPath {
    private static final ProviderId PID = new ProviderId("flowsched", "edu.uta.flowsched", true);
    private Set<Link> linksWithoutEdge;
    private MyLink bottleneckLink;
    private long availableCapacity;
    private double delay;
    private long lastUpdated;

    private long cachedCurrentFairShare;
    private long cachedProjectedFairShare;


    public MyPath(Path path) {
        super(PID, path.links().stream().map(LinkInformationDatabase.INSTANCE::getLinkInformation)
                .collect(Collectors.toList()), new ScalarWeight(1));
        this.linksWithoutEdge = links().stream().filter(link -> !link.type().equals(Type.EDGE)).collect(Collectors.toSet());
    }

    public Set<Link> linksNoEdge() {
        return linksWithoutEdge;
    }

    public Set<FLHost> addFlow(FLHost client) {
        Set<FLHost> affectedClients = new HashSet<>();
        linksNoEdge().forEach(link -> affectedClients.addAll(((MyLink) link).addFlow(client)));
        return affectedClients;
    }

    public Set<FLHost> removeFlow(FLHost client) {
        Set<FLHost> affectedClients = new HashSet<>();
        linksNoEdge().forEach(link -> affectedClients.addAll(((MyLink) link).removeFlow(client)));
        return affectedClients;
    }

    public double getDelay() {
        return delay;
    }

    public void setDelay(double delay) {
        this.delay = delay;
    }

    public long getCurrentFairShare() {
        boolean currentCacheNotValid = linksNoEdge().stream().anyMatch(link -> !((MyLink)link).isCurrentCacheValid());
        if (currentCacheNotValid || cachedCurrentFairShare == 0) {
            cachedCurrentFairShare = linksNoEdge().stream()
                    .mapToLong(link -> ((MyLink) link).getCurrentFairShare())
                    .min()
                    .orElse(0);
        }
        return cachedCurrentFairShare;
    }

    public long getProjectedFairShare() {
        boolean projectedCacheNotValid = linksNoEdge().stream().anyMatch(link -> !((MyLink)link).isProjectedCacheValid());
        if (projectedCacheNotValid || cachedProjectedFairShare == 0) {
            cachedProjectedFairShare = linksNoEdge().stream()
                    .mapToLong(link -> ((MyLink) link).getProjectedFairShare())
                    .min()
                    .orElse(0);
        }
        return cachedProjectedFairShare;
    }

    public MyLink getBottleneckLink() { // TODO: Remove
        return (MyLink) links().get(0);
    }
}