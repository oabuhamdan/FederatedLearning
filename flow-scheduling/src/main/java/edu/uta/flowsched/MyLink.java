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

    private final AtomicInteger activeFlows;
    private final long defaultCapacity;
    private final AtomicLong currentThroughput;
    private double delay;
    private final AtomicLong reservedCapacity;
    private final ConcurrentHashMap<FLHost, Long> rateReservedPerHost;

    private long cachedCurrentFairShare;
    private long cachedProjectedFairShare;
    private boolean isCurrentCacheValid;
    private boolean isProjectedCacheValid;


    public MyLink(Link link) {
        super(PID, link.src(), link.dst(), link.type(), link.state(), link.annotations());
        this.defaultCapacity = 100_000000;
        this.currentThroughput = new AtomicLong(0);
        this.delay = 0;
        this.reservedCapacity = new AtomicLong(0);
        this.activeFlows = new AtomicInteger(0);
        this.rateReservedPerHost = new ConcurrentHashMap<>();
        this.isCurrentCacheValid = false;
        this.isProjectedCacheValid = false;
    }

    public int getActiveFlows() {
        return activeFlows.get();
    }

    public ConcurrentHashMap<FLHost, Long> getRateReservedPerHost() {
        return rateReservedPerHost;
    }

    public void decreaseActiveFlows() {

    }

    public long getDefaultCapacity() {
        return defaultCapacity;
    }


    public long getEstimatedFreeCapacity() {
        return getDefaultCapacity() - getCurrentThroughput();
    }

    public long getCurrentThroughput() {
        return currentThroughput.get();
    }

    public void setCurrentThroughput(long currentThroughput) {
        this.currentThroughput.set(currentThroughput);
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


    public Set<FLHost> addFlow(FLHost client) {
        if (type().equals(Type.EDGE))
            return Set.of();

        long freeCapacity = getEstimatedFreeCapacity();

        isCurrentCacheValid = false;
        isProjectedCacheValid = false;

        long reservedCapacity = freeCapacity / activeFlows.incrementAndGet();
        Set<FLHost> preClients = new HashSet<>(rateReservedPerHost.size());
        preClients.addAll(rateReservedPerHost.keySet());

        rateReservedPerHost.replaceAll((k, v) -> reservedCapacity);
        rateReservedPerHost.put(client, reservedCapacity);

        return preClients;
    }

    public Set<FLHost> removeFlow(FLHost client) {
        if (type().equals(Type.EDGE))
            return Set.of();

        long freeCapacity = getEstimatedFreeCapacity();

        rateReservedPerHost.remove(client);
        Set<FLHost> postClients = new HashSet<>(rateReservedPerHost.keySet());

        activeFlows.set(Math.max(0, activeFlows.get() - 1));
        isCurrentCacheValid = false;
        isProjectedCacheValid = false;

        if (activeFlows.get() > 0) {
            long reservedCapacity = freeCapacity / activeFlows.get();
            rateReservedPerHost.replaceAll((k, v) -> reservedCapacity);
        }

        return postClients;
    }

    public long getCurrentFairShare() {
        long freeCapacity = getEstimatedFreeCapacity();
        if (!isCurrentCacheValid || cachedCurrentFairShare == 0) {
            cachedCurrentFairShare = activeFlows.get() > 0 ? freeCapacity / activeFlows.get() : freeCapacity;
            isCurrentCacheValid = true;
        }
        return cachedCurrentFairShare;
    }

    public long getProjectedFairShare() {
        if (!isProjectedCacheValid || cachedProjectedFairShare == 0) {
            cachedProjectedFairShare = getEstimatedFreeCapacity() / (activeFlows.get() + 1);
            isProjectedCacheValid = true;
        }
        return cachedProjectedFairShare;
    }

    public boolean isCurrentCacheValid() {
        return isCurrentCacheValid;
    }

    public boolean isProjectedCacheValid() {
        return isProjectedCacheValid;
    }
}
