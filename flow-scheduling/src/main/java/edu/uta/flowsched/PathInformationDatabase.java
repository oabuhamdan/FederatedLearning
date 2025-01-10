package edu.uta.flowsched;

import org.onlab.util.KryoNamespace;
import org.onosproject.net.HostId;
import org.onosproject.store.serializers.KryoNamespaces;
import org.onosproject.store.service.EventuallyConsistentMap;
import org.onosproject.store.service.WallClockTimestamp;

import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class PathInformationDatabase {
    public static final PathInformationDatabase INSTANCE = new PathInformationDatabase();
    private static ExecutorService executorService;

    private EventuallyConsistentMap<HostId, List<MyPath>> CLIENT_TO_SERVER_PATHS;
    private EventuallyConsistentMap<HostId, List<MyPath>> SERVER_TO_CLIENT_PATHS;

    protected void activate() {
        KryoNamespace.Builder mySerializer = KryoNamespace.newBuilder().register(KryoNamespaces.API)
                .register(MyLink.class)
                .register(MyPath.class);

        CLIENT_TO_SERVER_PATHS = Services.storageService.<HostId, List<MyPath>>eventuallyConsistentMapBuilder()
                .withName("CLIENT_TO_SERVER_PATHS")
                .withTimestampProvider((k, v) -> new WallClockTimestamp())
                .withSerializer(mySerializer).build();

        SERVER_TO_CLIENT_PATHS = Services.storageService.<HostId, List<MyPath>>eventuallyConsistentMapBuilder()
                .withName("SERVER_TO_CLIENTS_PATHS")
                .withTimestampProvider((k, v) -> new WallClockTimestamp())
                .withSerializer(mySerializer).build();

        executorService = Executors.newCachedThreadPool();
    }

    protected void deactivate() {
        executorService.shutdownNow();
        CLIENT_TO_SERVER_PATHS.clear();
        SERVER_TO_CLIENT_PATHS.clear();
    }

    public List<MyPath> getPathsToServer(FLHost host) {
        List<MyPath> paths = Optional.ofNullable(CLIENT_TO_SERVER_PATHS.get(host.id())).orElse(List.of());
        Util.log("general", String.format("%s CLIENT_TO_SERVER_PATHS paths found for client %s", paths.size(), host.getFlClientCID()));
        return paths;
    }

    public List<MyPath> getPathsToClient(FLHost host) {
        List<MyPath> paths = Optional.ofNullable(SERVER_TO_CLIENT_PATHS.get(host.id())).orElse(List.of());
        Util.log("general", String.format("%s SERVER_TO_CLIENT_PATHS paths found for client %s", paths.size(), host.getFlClientCID()));
        return paths;
    }

    public void setPathsToServer(HostId hostId) {
        CLIENT_TO_SERVER_PATHS.put(hostId, new LinkedList<>());
        Services.pathService.getKShortestPaths(hostId, HostId.hostId(Util.FL_SERVER_MAC))
                .limit(10).map(MyPath::new)
                .forEach(path -> CLIENT_TO_SERVER_PATHS.get(hostId).add(path));
    }

    public void setPathsToClient(HostId hostId) {
        SERVER_TO_CLIENT_PATHS.put(hostId, new LinkedList<>());
        Services.pathService.getKShortestPaths(HostId.hostId(Util.FL_SERVER_MAC), hostId)
                .limit(10).map(MyPath::new)
                .forEach(path -> SERVER_TO_CLIENT_PATHS.get(hostId).add(path));
    }

    public void updateBottleneckPath() {
//        Util.log("general", "Updating Bottleneck Links");
        executorService.submit(() -> CLIENT_TO_SERVER_PATHS.values().forEach(myPaths -> myPaths.forEach(MyPath::updateBottleneckLink)));
        executorService.submit(() -> SERVER_TO_CLIENT_PATHS.values().forEach(myPaths -> myPaths.forEach(MyPath::updateBottleneckLink)));
    }
}
