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
    private final Set<FLHost> clientsUsingPath;

    public MyLink(Link link) {
        super(PID, link.src(), link.dst(), link.type(), link.state(), link.annotations());
        this.defaultCapacity = 95_000_000;
        this.linkThroughput = new ConcurrentLinkedQueue<>();
        this.delay = 0;
        this.reservedCapacity = new AtomicLong(0);
        this.activeFlows = new AtomicInteger(1);
        this.clientsUsingPath = ConcurrentHashMap.newKeySet();
    }

    public int getActiveFlows() {
        return activeFlows.get();
    }

    public Set<FLHost> getClientsUsingLink() {
        return Collections.unmodifiableSet(clientsUsingPath);
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
        if (this.linkThroughput.size() > 3)
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

    public void addFlow(FLHost client) {
        activeFlows.incrementAndGet();
        clientsUsingPath.add(client);
    }

    public void removeFlow(FLHost client) {
        if (activeFlows.get() > 1)
            activeFlows.decrementAndGet();
        clientsUsingPath.remove(client);
    }

    public static long estimateFairShare(double linkCapacityMbps, double freeCap, int currentActiveFlows, double windowSeconds) {
        final double MSS_BYTES = 1460;    // typical Ethernet MSS
        final double C = 0.4;         // CUBIC aggression   (RFC 8312)
        final double BETA = 0.7;
        final double RTT_Millis = 5;

        int totalFlows = currentActiveFlows + 1;
        double fairShare = linkCapacityMbps / totalFlows;  // long-term equal split
        double residual = Math.max(0.0, freeCap);

        double steadyMbps;
        if (residual >= fairShare) {
            double leftover = residual - fairShare;
            steadyMbps = 0.9 * fairShare + leftover / totalFlows;
        } else {
            steadyMbps = 0.9 * fairShare;
        }

        double bytesPerSecFair = (steadyMbps * 1_000_000) / 8.0;
        double cwndFair = bytesPerSecFair * (RTT_Millis / 1_000.0) / MSS_BYTES;

        double cwnd0 = 10;                      // RFC 6928 initial window ≈ 10 MSS
        if (cwndFair <= cwnd0) return (long) steadyMbps;  // already above fair cwnd
        double K = Math.cbrt(cwnd0 * BETA / C);
        double tEq = K + Math.cbrt((cwndFair - cwnd0) / C);   // seconds to reach cwndFair
        double penalty = Math.min(1.0, windowSeconds / tEq);
        return (long) (penalty * steadyMbps);   // Mbps averaged over the time window
    }

    public long getCurrentFairShare() {
        long defaultCapacity = getDefaultCapacity();
        double partial = Math.max(getEstimatedFreeCapacity() * 1.0 / defaultCapacity, 0.5);
        long estimatedFairShare = activeFlows.get() > 0 ? defaultCapacity / activeFlows.get() : defaultCapacity;
        return (long) (partial * estimatedFairShare);
    }

    public long getProjectedFairShare() {
        long defaultCapacity = getDefaultCapacity();
        double partial = Math.max(getEstimatedFreeCapacity() * 1.0 / defaultCapacity, 0.5);
        long estimatedFairShare = defaultCapacity / (activeFlows.get() + 1);
        return (long) (partial * estimatedFairShare);
    }

    public String format() {
        final String LINK_STRING_FORMAT = "%s -> %s";
        String src = this.src().elementId().toString().substring(15);
        String dst = this.dst().elementId().toString().substring(15);
        return String.format(LINK_STRING_FORMAT, src, dst);
    }

    public String id(){
        return String.valueOf(hashCode());
    }
}
