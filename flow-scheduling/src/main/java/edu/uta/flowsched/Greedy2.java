package edu.uta.flowsched;

import org.onosproject.net.Link;

import java.util.*;

import static edu.uta.flowsched.Util.bitToMbit;

public class Greedy2 {

    public static Greedy2 INSTANCE = new Greedy2();
    private final long D = 138_400_000; // Data size in Mbit

    public Map<FLHost, MyPath> assignMyPaths(List<FLHost> hosts, StringBuilder logger) {
        // Initialize assigned flows for each link to zero
        long t1 = System.currentTimeMillis();
        Map<Link, Integer> assignedFlows = new HashMap<>();
        for (MyLink link : LinkInformationDatabase.INSTANCE.getAllLinkInformation()) {
            assignedFlows.put(link, 0);
        }

        // Create a list of clients and compute their max initial R
        List<ClientR> clients = new ArrayList<>();
        for (FLHost host : hosts) {
            Set<MyPath> paths = PathInformationDatabase.INSTANCE.getPathsToClient(host);
            double maxR = computeMaxInitialR(assignedFlows, paths);
            clients.add(new ClientR(host, maxR, paths));
        }

        // Sort clients by their max initial R in ascending order
        clients.sort(Comparator.comparingDouble(c -> c.maxR));

        Map<FLHost, MyPath> assignments = new HashMap<>();

        for (ClientR clientR : clients) {
            int client = Integer.parseInt(clientR.host.getFlClientCID());
            MyPath bestMyPath = null;
            double bestTentativeR = -1;

            for (MyPath path : clientR.paths) {
                double tentativeR = computeTentativeR(path, assignedFlows);
                if (tentativeR > bestTentativeR) {
                    bestTentativeR = tentativeR;
                    bestMyPath = path;
                }
            }

            if (bestMyPath == null) {
                throw new IllegalStateException("No valid path for client " + client);
            }

            assignments.put(clientR.host, bestMyPath);

            // Update the assigned flows for each link in the chosen path
            for (Link link : bestMyPath.linksNoEdge()) {
                assignedFlows.put(link, assignedFlows.get(link) + 1);
            }
            logger.append(String.format("\tClient %s with MaxR %sMbps will get %sMbps with Path %s\n",
                    clientR.host.getFlClientCID(), bitToMbit(clientR.maxR), bitToMbit(bestTentativeR), Util.pathFormat(bestMyPath)));
        }

        return assignments;
    }

    private double computeMaxInitialR(Map<Link, Integer> assignedFlows, Set<MyPath> paths) {
        return paths.stream()
                .mapToDouble(path -> path.linksNoEdge().stream()
                        .mapToLong(link -> ((MyLink) link).getEstimatedFreeCapacity())
                        .min()
                        .orElse(0))
                .max()
                .orElse(0.0);
    }

    private double computeTentativeR(MyPath path, Map<Link, Integer> assignedFlows) {
        double minR = Double.POSITIVE_INFINITY;

        for (Link link : path.linksNoEdge()) {
//            int initialF = ((MyLink) link).getActiveFlows();
            int assigned = assignedFlows.get(link);
            long X = ((MyLink) link).getEstimatedFreeCapacity();

            double r = (double) X / (assigned + 1);
            if (r < minR) {
                minR = r;
            }
        }

        return minR;
    }

    private static class ClientR {
        final double maxR;
        final FLHost host;
        final Set<MyPath> paths;

        public ClientR(FLHost host, double maxR, Set<MyPath> paths) {
            this.host = host;
            this.maxR = maxR;
            this.paths = paths;
        }
    }
}
