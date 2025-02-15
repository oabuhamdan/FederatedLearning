package edu.uta.flowsched;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SetupPath {
    private static GoogleORPathFinder serverToClientsNetworkOptimizer;
    private static final Map<FLHost, List<MyPath>> allPaths = new HashMap<>();
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public static void clientToServer(FLHost flHost) {
        long tik = System.currentTimeMillis();

        Optional<MyPath> clientToServerPath = PathFinder.getPathsToServer(flHost);
        if (clientToServerPath.isEmpty()) {
            Util.log("general", String.format("Client %s has no paths yet", flHost.getFlClientCID()));
            return;
        }
//        PathRulesInstaller.INSTANCE.installPathRules(flHost, clientToServerPath.get());

//        long capacityModelWillOccupy = getCapacityModelWillOccupy(clientToServerPath.get());
//        clientToServerPath.get().reserveCapacity(flHost);
        scheduleCapacityRelease(clientToServerPath.get(), flHost);

//        Util.log("general", String.format("C2S Reserved %s Mbps for Client %s ", (double) capacityModelWillOccupy / DataRateUnit.MBPS.multiplier(), flHost.getFlClientCID()));

        Util.log("overhead.csv", String.format("controller,c2s_path,%s", System.currentTimeMillis() - tik));
    }

    public static void serverToClient(List<FLHost> flHosts) {
        try {
            long tik = System.currentTimeMillis();
            for (FLHost flHost : flHosts) {
                Optional<MyPath> serverToClientPath = PathFinder.getPathsToClient(flHost);
                if (serverToClientPath.isEmpty()) {
                    Util.log("general", String.format("Client %s has no paths yet", flHost.getFlClientCID()));
                    continue;
                }
//                PathRulesInstaller.INSTANCE.installPathRules(flHost, serverToClientPath.get());

//                long capacityModelWillOccupy = getCapacityModelWillOccupy(serverToClientPath.get());
//                serverToClientPath.get().reserveCapacity(flHost);
                scheduleCapacityRelease(serverToClientPath.get(), flHost);

//                Util.log("general", String.format("S2C Reserved %s Mbps for Client %s ", (double) capacityModelWillOccupy / DataRateUnit.MBPS.multiplier(), flHost.getFlClientCID()));
            }
//            if (serverToClientsNetworkOptimizer == null || allPaths.values().stream().anyMatch(List::isEmpty)) {
//                Util.logger.info("Recalculating Setup for Google OR");
//                flHosts.forEach(host -> allPaths.put(host, PathInformationDatabase.INSTANCE.getPathsToClient(host.id())));
//                serverToClientsNetworkOptimizer = new GoogleORPathFinder(allPaths);
//            }
//            Map<FLHost, MyPath> results = serverToClientsNetworkOptimizer.findOptimalServerToClientsPath();
//            results.forEach((key, value) -> PathRulesInstaller.INSTANCE.installPathRules(key.mac(), value));
//

//            Util.log("general", String.format("Took %s ms to setup paths for every client from Server", tak - tik));
            Util.log("overhead.csv", String.format("controller,s2c_paths,%s", System.currentTimeMillis() - tik));
        } catch (Exception e) {
            Util.log("general", String.format("Error: %s", e.getMessage()));
            Util.log("general", String.format("%s", (Object[]) e.getStackTrace()));
        }
    }

//    private static long getCapacityModelWillOccupy(MyPath path) {
//        path.reserveCapacity();
//        scheduleCapacityRelease(path, capacityModelWillOccupy);
//        return capacityModelWillOccupy;
//    }

    private static void scheduleCapacityRelease(MyPath path, FLHost flHost) {
//        Runnable task = () -> path.releaseCapacity(flHost);
//        scheduler.schedule(task, Util.POLL_FREQ, TimeUnit.SECONDS);
    }
}
