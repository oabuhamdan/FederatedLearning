package edu.uta.flowsched;

import org.onosproject.net.Link;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class PathFinder {
    // debug in the background
    static ExecutorService executorService = Executors.newSingleThreadExecutor();
    static TotalLoadComparator totalLoadComparator = new TotalLoadComparator();

    public static Optional<MyPath> getPathsToServer(FLHost host) {
        List<MyPath> paths = getEntries(PathInformationDatabase.INSTANCE.getPathsToServer(host));
        List<MyPathDeepCopy> debugPaths = deepCopy(paths);
//        executorService.submit(() -> debugPaths(host, "C2S", debugPaths));
        debugPaths(host, "C2S", debugPaths);
        return paths.isEmpty() ? Optional.empty() : Optional.ofNullable(paths.get(0));
    }

    public static Optional<MyPath> getPathsToClient(FLHost host) {
        List<MyPath> paths = getEntries(PathInformationDatabase.INSTANCE.getPathsToClient(host));
        List<MyPathDeepCopy> debugPaths = deepCopy(paths);
//        executorService.submit(() -> debugPaths(host, "S2C", debugPaths));
        debugPaths(host, "S2C", debugPaths);
        return paths.isEmpty() ? Optional.empty() : Optional.ofNullable(paths.get(0));
    }

    private static List<MyPath> getEntries(List<MyPath> paths) {
        TotalLoadComparator totalLoadComparator = new TotalLoadComparator();
        return paths.stream().sorted(totalLoadComparator).collect(Collectors.toList());
    }

    private static void debugPaths(FLHost flHost, String dir, List<MyPathDeepCopy> paths) {
        if (paths.size() > 0) {
            Util.log("link_debug", String.format("***** Logging %s paths for Host %s*****", dir, flHost.getFlClientCID()));
            for (int i = 0; i < paths.size(); i++) {
                MyLinkDeepCopy bottleneck = paths.get(i).getBottleneckLink();
                Util.log("link_debug", String.format("\tPath #%d, Bottleneck Link is %s with Cap %sMbps", i, bottleneck.formatLink(), bottleneck.getEstimatedFreeCapacity() / 1000000));
                Util.log("link_debug", "\t" + paths.get(i).debugScore());
                for (int j = 1; j < paths.get(i).getLinks().size() - 1; j++) {
                    MyLinkDeepCopy link = paths.get(i).getLinks().get(j);
                    StringBuilder reserved = new StringBuilder();
                    link.getRateReservedPerHost().forEach((key, value) -> {
                        reserved.append(key);
                        reserved.append(":");
                        reserved.append(value);
                        reserved.append("; ");
                    });
                    Util.log("link_debug", String.format("\t\t%s: FreeCap %sMbps, CurThr %sMbps, ResCap %sMbps, Active Flows: %s, Reserved %s",
                            link.formatLink(), link.getEstimatedFreeCapacity() / 1000000, link.getCurrentThroughput() / 1000000, link.getReservedCapacity() / 1000000, link.getActiveFlows(), reserved));
                }
            }
        }
    }

    public static List<MyPathDeepCopy> deepCopy(List<MyPath> paths) {
        long tik = System.currentTimeMillis();
        List<MyPathDeepCopy> deepCopiedPaths = new ArrayList<>();
        for (MyPath originalPath : paths) {
            MyPathDeepCopy copiedPath = new MyPathDeepCopy();

            // Copying the bottleneck link information
            MyLinkDeepCopy bottleneckCopy = MyLinkDeepCopy.deepCopy(originalPath.getBottleneckLink());
            copiedPath.setBottleneckLink(bottleneckCopy);

            // Copying other details of the path
            List<MyLinkDeepCopy> copiedLinks = new ArrayList<>();
            for (Link originalLink : originalPath.links()) {
                copiedLinks.add(MyLinkDeepCopy.deepCopy((MyLink) originalLink));  // Assuming a deepCopy method exists in MyLink
            }
            copiedPath.setLinks(copiedLinks);
            deepCopiedPaths.add(copiedPath);
        }
//        Util.log("general", String.format("Deep Copy Time: %s", System.currentTimeMillis() - tik));
        return deepCopiedPaths;
    }
}
