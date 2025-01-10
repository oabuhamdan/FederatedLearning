package edu.uta.flowsched;

import org.onosproject.net.DefaultLink;
import org.onosproject.net.Link;
import org.onosproject.net.flow.FlowEntry;
import org.onosproject.net.provider.ProviderId;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class MyLink extends DefaultLink implements Serializable {
    private static final ProviderId PID = new ProviderId("flowsched", "edu.uta.flowsched", true);

    private Set<FlowEntry> flowsUsingLink;
    private final AtomicInteger activeFlows;
    private long defaultCapacity;
    private final AtomicLong currentThroughput;
    private long lastUpdated;
    private double delay;
    private final AtomicLong reservedCapacity;
    private final ConcurrentHashMap<FLHost, Long> rateReservedPerHost;

    public MyLink(Link link) {
        super(PID, link.src(), link.dst(), link.type(), link.state(), link.annotations());
        this.lastUpdated = System.currentTimeMillis();
        this.defaultCapacity = 100_000000;
        this.currentThroughput = new AtomicLong(0);
        this.delay = 0;
        this.reservedCapacity = new AtomicLong(0);
        this.activeFlows = new AtomicInteger(1);
        this.flowsUsingLink = new HashSet<>();
        this.rateReservedPerHost = new ConcurrentHashMap<>();
    }

    public int getActiveFlows() {
        return activeFlows.get();
    }

    public ConcurrentHashMap<FLHost, Long> getRateReservedPerHost() {
        return rateReservedPerHost;
    }

    public void increaseActiveFlows() {
        this.activeFlows.getAndIncrement();
    }

    public void decreaseActiveFlows() {
        if (this.activeFlows.decrementAndGet() < 1)
            this.activeFlows.set(1);
    }

    public long getDefaultCapacity() {
        return defaultCapacity;
    }

    public void setDefaultCapacity(long defaultCapacity) {
        this.defaultCapacity = defaultCapacity;
    }

    public long getEstimatedFreeCapacity() {
        return Math.max(getDefaultCapacity() - getCurrentThroughput() - getReservedCapacity(), 0);
    }

    public long getCurrentThroughput() {
        return currentThroughput.get();
    }

    public void setCurrentThroughput(long currentThroughput) {
        this.currentThroughput.set(currentThroughput);
    }

    public long getLastUpdated() {
        return lastUpdated;
    }

    private void setLastUpdated(long lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public double getDelay() {
        return delay;
    }

    public void setDelay(double delay) {
        this.delay = delay;
    }

    public boolean isCongested() {
        return (double) getEstimatedFreeCapacity() / getDefaultCapacity() < 0.15;
    }


    public long getReservedCapacity() {
        return reservedCapacity.get();
    }

    public void reserveCapacity(long requestedBandwidth, FLHost flHost) {
        double availableBandwidth = getEstimatedFreeCapacity();
        long reservation = (long) Math.min(requestedBandwidth, availableBandwidth);
        this.reservedCapacity.addAndGet(reservation);
        rateReservedPerHost.put(flHost, reservation);
        increaseActiveFlows();
    }

    public void releaseCapacity(FLHost flHost) {
        long size = rateReservedPerHost.remove(flHost);
        if (this.reservedCapacity.addAndGet(-size) <= 0)
            this.reservedCapacity.set(0);
    }

    public Set<FlowEntry> getFlowsUsingLink() {
        return Collections.unmodifiableSet(flowsUsingLink);
    }

    public void setFlowsUsingLink(Set<FlowEntry> flowsUsingLink) {
        this.flowsUsingLink.clear();
        this.flowsUsingLink.addAll(flowsUsingLink);
    }

    public long getFairShareCapacity() {
        if (activeFlows.get() == 0) return getEstimatedFreeCapacity();
        return getEstimatedFreeCapacity() / activeFlows.get();
    }

}
