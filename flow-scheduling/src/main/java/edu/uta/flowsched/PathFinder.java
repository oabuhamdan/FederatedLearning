package edu.uta.flowsched;

import org.onosproject.net.HostId;
import org.onosproject.net.Link;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class PathFinder {
    // debug in the background
    static ExecutorService executorService = Executors.newSingleThreadExecutor();

    public static Optional<MyPath> getPathsToServer(HostId hostId) {
        List<MyPath> paths = getEntries(PathInformationDatabase.INSTANCE.getPathsToServer(hostId));
        executorService.submit(() -> debugPaths(hostId, "C2S", paths));
        return paths.isEmpty()? Optional.empty() : Optional.ofNullable(paths.get(0));
    }

    public static Optional<MyPath> getPathsToClient(HostId hostId) {
        List<MyPath> paths = getEntries(PathInformationDatabase.INSTANCE.getPathsToClient(hostId));
        executorService.submit(() -> debugPaths(hostId, "S2C", paths));
        return paths.isEmpty()? Optional.empty() : Optional.ofNullable(paths.get(0));
    }

    private static List<MyPath> getEntries(List<MyPath> paths) {
        TotalLoadComparator totalLoadComparator = new TotalLoadComparator();
        return paths.stream().sorted(totalLoadComparator).collect(Collectors.toList());
    }

    private static void debugPaths(HostId hostId, String dir, List<MyPath> paths) {
        if (paths.size() > 0) {
            FLHost flHost = ClientInformationDatabase.INSTANCE.getHostByHostID(hostId);
            Util.log("link_debug", String.format("***** Logging %s paths for Host %s*****", dir, flHost.getFlClientCID()));
            for (int i = 0; i < paths.size(); i++) {
                MyLink bottleneck = paths.get(i).getBottleneckLink();
                Util.log("link_debug", String.format("\tPath #%d, Bottleneck Link is %s with Cap %s", i, Util.formatLink(bottleneck), bottleneck.getEstimatedFreeCapacity()));
                for (int j = 0; j < paths.get(i).links().size(); j++) {
                    MyLink link = (MyLink) paths.get(i).links().get(j);
                    Util.log("link_debug", String.format("\t\tLink %s with Free Capacity of %s", Util.formatLink(link), link.getEstimatedFreeCapacity()));
                }
            }
        }
    }
}
