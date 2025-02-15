package edu.uta.flowsched;

import org.onlab.graph.ScalarWeight;
import org.onosproject.net.*;
import org.onosproject.net.provider.ProviderId;

import java.util.*;
import java.util.stream.Collectors;

public class SimMyPath extends MyPath {
    List<SimMyLink> simLinks;

    public SimMyPath(Path path, long linkFreeCapAdjustment) {
        super(path);
        simLinks = new LinkedList<>();
        linksNoEdge().forEach(link -> {
            long newFreeCap = Math.min(((MyLink) link).getEstimatedFreeCapacity() + linkFreeCapAdjustment, ((MyLink) link).getDefaultCapacity());
            SimMyLink simMyLink = new SimMyLink(newFreeCap, Math.max(((MyLink) link).getActiveFlows() - 1, 0));
            simLinks.add(simMyLink);
        });
    }

    public long getCurrentFairShare() {
        return simLinks.stream()
                .mapToLong(SimMyLink::getCurrentFairShare)
                .min()
                .orElse(0);
    }

    public long getProjectedFairShare() {
        return simLinks.stream()
                .mapToLong(SimMyLink::getProjectedFairShare)
                .min()
                .orElse(0);
    }

    public long getBottleneckFreeCap() {
        return simLinks.stream()
                .mapToLong(link -> link.freeCap)
                .min()
                .orElse(0);
    }

    public double getCurrentActiveFlows() {
        return simLinks.stream()
                .mapToInt(link -> link.activeFlows)
                .max()
                .orElse(0);
    }

    static class SimMyLink {
        long freeCap;
        int activeFlows;

        public SimMyLink(long freeCap, int activeFlows) {
            this.freeCap = freeCap;
            this.activeFlows = activeFlows;
        }

        public long getProjectedFairShare() {
            return freeCap / (activeFlows + 1);
        }

        public long getCurrentFairShare() {
            return activeFlows > 0 ? freeCap / activeFlows : freeCap;
        }
    }
}