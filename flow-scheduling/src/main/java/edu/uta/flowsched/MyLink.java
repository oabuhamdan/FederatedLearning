package edu.uta.flowsched;

import org.onlab.util.DataRateUnit;
import org.onosproject.net.DefaultLink;
import org.onosproject.net.Link;
import org.onosproject.net.flow.FlowEntry;
import org.onosproject.net.provider.ProviderId;

import java.util.*;

public class MyLink extends DefaultLink {
    private static final ProviderId PID = new ProviderId("flowsched", "edu.uta.flowsched", true);

    private Set<FlowEntry> flowsUsingLink;
    private long defaultCapacity;
    private long currentThroughput;
    private long lastUpdated;
    private double delay;
    private boolean congested;

    private long reservedCapacity;

    public MyLink(Link link) {
        super(PID, link.src(), link.dst(), link.type(), link.state(), link.annotations());
        this.lastUpdated = System.currentTimeMillis();
        long defaultCapacity;
//        if (link.src().elementId().toString().matches("of:00000000000001\\d{2}") && link.dst().elementId().toString().matches("of:00000000000001\\d{2}"))
//            defaultCapacity = 1_000_000000; // 1Gbps for core switches
//        else
//            defaultCapacity = 100_000000; //  100Mbps
        setDefaultCapacity(100_000000);
        this.currentThroughput = 0;
        this.delay = 0;
        this.congested = false;
        this.reservedCapacity = 0;
        this.flowsUsingLink = new HashSet<>();
    }

    public long getDefaultCapacity() {
        return defaultCapacity;
    }

    public void setDefaultCapacity(long defaultCapacity) {
        this.defaultCapacity = defaultCapacity;
    }

    public long getEstimatedFreeCapacity() {
        return getDefaultCapacity() - getCurrentThroughput() - getReservedCapacity();
    }

    private void setEstimatedFreeCapacity(long estimatedFreeCapacity) {
        if (estimatedFreeCapacity <= 0.1 * this.defaultCapacity) {
            this.congested = true;
        }
    }

    public long getCurrentThroughput() {
        return currentThroughput;
    }

    public void setCurrentThroughput(long currentThroughput) {
        this.currentThroughput = currentThroughput;
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
        return reservedCapacity;
    }

    public void setReservedCapacity(long reservedCapacity) {
    }

    public void reserveCapacity(long size) {
        this.reservedCapacity += size;
    }

    public void releaseCapacity(long size) {
        this.reservedCapacity -= size;
    }

    public Set<FlowEntry> getFlowsUsingLink() {
        return Collections.unmodifiableSet(flowsUsingLink);
    }

    public void setFlowsUsingLink(Set<FlowEntry> flowsUsingLink) {
        this.flowsUsingLink.clear();
        this.flowsUsingLink.addAll(flowsUsingLink);
    }
}
