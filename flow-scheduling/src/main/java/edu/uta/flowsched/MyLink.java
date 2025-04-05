package edu.uta.flowsched;

import org.onosproject.net.DefaultLink;
import org.onosproject.net.Link;
import org.onosproject.net.provider.ProviderId;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class MyLink extends DefaultLink implements Serializable {
    private static final ProviderId PID = new ProviderId("flowsched", "edu.uta.flowsched", true);

    private final AtomicInteger activeFlows;
    private final long defaultCapacity;
    private final ConcurrentLinkedQueue<Long> linkThroughput;
    private double delay;
    private final AtomicLong reservedCapacity;
    private final Set<FLHost> C2SHosts;
    private final Set<FLHost> S2CHosts;

    public MyLink(Link link) {
        super(PID, link.src(), link.dst(), link.type(), link.state(), link.annotations());
        this.defaultCapacity = 95_000_000;
        this.linkThroughput = new ConcurrentLinkedQueue<>();
        this.delay = 0;
        this.reservedCapacity = new AtomicLong(0);
        this.activeFlows = new AtomicInteger(0);
        C2SHosts = ConcurrentHashMap.newKeySet();
        S2CHosts = ConcurrentHashMap.newKeySet();
    }

    public int getActiveFlows() {
        return activeFlows.get();
    }

    public Set<FLHost> getClientsUsingLink(FlowDirection direction) {
        return direction.equals(FlowDirection.C2S)? C2SHosts: S2CHosts;
    }

    public void decreaseActiveFlows() {

    }
    public long getDefaultCapacity() {
        return defaultCapacity;
    }


    public long getEstimatedFreeCapacity() {
        return getDefaultCapacity() - getThroughput();
    }

    public long getThroughput() {
        return linkThroughput.stream().collect(Collectors.averagingLong(Long::intValue)).longValue();
    }

    public void setCurrentThroughput(long currentThroughput) {
        this.linkThroughput.add(currentThroughput);
        // keep it limited to 10
        if (this.linkThroughput.size() > 5)
            this.linkThroughput.poll();
    }

    public double getDelay() {
        return delay;
    }

    public void setDelay(double delay) {
        this.delay = delay;
    }

    public long getReservedCapacity() {
        return reservedCapacity.get();
    }


    public void addFlow(FLHost client, FlowDirection direction) {
        activeFlows.incrementAndGet();
        if (direction.equals(FlowDirection.C2S)){
            C2SHosts.add(client);
        }
        else {
            S2CHosts.add(client);
        }
    }

    public void removeFlow(FLHost client, FlowDirection direction) {
        activeFlows.decrementAndGet();
        if (direction.equals(FlowDirection.C2S)){
            C2SHosts.remove(client);
        }
        else {
            S2CHosts.remove(client);
        }
    }

    public long getCurrentFairShare() {
        long freeCapacity = getEstimatedFreeCapacity();
        return activeFlows.get() > 0 ? freeCapacity / activeFlows.get() : freeCapacity;
    }

    public long getProjectedFairShare() {
        return getEstimatedFreeCapacity() / (activeFlows.get() + 1);
    }
}
