package edu.uta.flowsched;

import org.onlab.graph.ScalarWeight;
import org.onosproject.net.*;
import org.onosproject.net.provider.ProviderId;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class MyPath extends DefaultPath {
    private static final ProviderId PID = new ProviderId("flowsched", "edu.uta.flowsched", true);
    private final Set<Link> linksWithoutEdge;
    private double delay;
    private long lastUpdated;


    public MyPath(Path path) {
        super(PID, path.links().stream().map(LinkInformationDatabase.INSTANCE::getLinkInformation)
                .collect(Collectors.toList()), new ScalarWeight(1));
        this.linksWithoutEdge = links().stream().filter(link -> !link.type().equals(Type.EDGE)).collect(Collectors.toSet());
        lastUpdated = 0;
    }

    public Set<Link> linksNoEdge() {
        return linksWithoutEdge;
    }

    public Set<FLHost> addClient(FLHost client) {
        Set<FLHost> affectedClients = ConcurrentHashMap.newKeySet();
        linksNoEdge().forEach(link -> {
            ((MyLink) link).addFlow(client); // Putting it first to calculate it with the affected clients
            affectedClients.addAll(((MyLink) link).getClientsUsingLink());
        });
        return affectedClients;
    }

    public Set<FLHost> removeFlow(FLHost client) {
        Set<FLHost> affectedClients = ConcurrentHashMap.newKeySet();
        linksNoEdge().forEach(link -> {
            ((MyLink) link).removeFlow(client);
            affectedClients.addAll(((MyLink) link).getClientsUsingLink());
        });
        return affectedClients;
    }

    public double getDelay() {
        return delay;
    }

    public void setDelay(double delay) {
        this.delay = delay;
    }

    public long getCurrentFairShare() {
        return linksNoEdge().stream()
                .mapToLong(link -> ((MyLink) link).getCurrentFairShare())
                .min()
                .orElse(0);
    }
    public long getProjectedFairShare() {
        return linksNoEdge().stream()
                .mapToLong(link -> ((MyLink) link).getProjectedFairShare())
                .min()
                .orElse(0);
    }

    public long getBottleneckFreeCap() {
        return linksNoEdge().stream()
                .mapToLong(link -> ((MyLink) link).getEstimatedFreeCapacity())
                .min()
                .orElse(0);
    }

    public double getCurrentActiveFlows() {
        return linksNoEdge().stream()
                .mapToInt(link -> ((MyLink) link).getActiveFlows())
                .max()
                .orElse(0);
    }

    public String format() {
        StringBuilder stringBuilder = new StringBuilder();
        for (Link link : this.links()) {
            ElementId id = link.src().elementId();
            if (id instanceof HostId) {
                if (((HostId) id).mac() == Util.FL_SERVER_MAC)
                    stringBuilder.append("FLServer");
                else {
                    Optional<FLHost> host = ClientInformationDatabase.INSTANCE.getHostByHostID((HostId) id);
                    stringBuilder.append("FL#").append(host.map(FLHost::getFlClientCID).orElse(id.toString().substring(15)));
                }
            } else { // Switch
                stringBuilder.append(id.toString().substring(15));
            }
            stringBuilder.append(" -> ");
        }
        if (this.dst().hostId().mac() == Util.FL_SERVER_MAC)
            stringBuilder.append("FLServer");
        else {
            HostId dst = this.dst().hostId();
            Optional<FLHost> host = ClientInformationDatabase.INSTANCE.getHostByHostID(dst);
            stringBuilder.append("FL#").append(host.map(FLHost::getFlClientCID).orElse(dst.toString().substring(15)));
        }
        return stringBuilder.toString();
    }

    public String id(){
        return String.valueOf(hashCode());
    }
}