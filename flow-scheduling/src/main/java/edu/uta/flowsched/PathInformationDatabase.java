package edu.uta.flowsched;

import org.onlab.util.KryoNamespace;
import org.onosproject.net.HostId;
import org.onosproject.net.Path;
import org.onosproject.store.serializers.KryoNamespaces;
import org.onosproject.store.service.EventuallyConsistentMap;
import org.onosproject.store.service.WallClockTimestamp;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PathInformationDatabase {
    public static final PathInformationDatabase INSTANCE = new PathInformationDatabase();
    private static ExecutorService executorService;

    private EventuallyConsistentMap<HostId, Set<MyPath>> CLIENT_TO_SERVER_PATHS;
    private EventuallyConsistentMap<HostId, Set<MyPath>> SERVER_TO_CLIENT_PATHS;

    protected void activate() {
        KryoNamespace.Builder mySerializer = KryoNamespace.newBuilder().register(KryoNamespaces.API)
                .register(MyLink.class)
                .register(MyPath.class);

        CLIENT_TO_SERVER_PATHS = Services.storageService.<HostId, Set<MyPath>>eventuallyConsistentMapBuilder()
                .withName("CLIENT_TO_SERVER_PATHS")
                .withTimestampProvider((k, v) -> new WallClockTimestamp())
                .withSerializer(mySerializer).build();

        SERVER_TO_CLIENT_PATHS = Services.storageService.<HostId, Set<MyPath>>eventuallyConsistentMapBuilder()
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

    public Set<MyPath> getPathsToServer(FLHost host) {
        Set<MyPath> paths = Optional.ofNullable(CLIENT_TO_SERVER_PATHS.get(host.id())).orElse(Set.of());
        return paths;
    }

    public Set<MyPath> getPathsToClient(FLHost host) {
        Set<MyPath> paths = Optional.ofNullable(SERVER_TO_CLIENT_PATHS.get(host.id())).orElse(Set.of());
        return paths;
    }

    public void setPathsToServer(HostId hostId) {
        Set<MyPath> paths = Services.pathService.getKShortestPaths(hostId, HostId.hostId(Util.FL_SERVER_MAC))
                .limit(10).map(MyPath::new)
                .collect(Collectors.toSet());

        PathRulesInstaller.INSTANCE.installPathRules(ClientInformationDatabase.INSTANCE.getHostByHostID(hostId).get(), (Path) paths.toArray()[0], true);
        CLIENT_TO_SERVER_PATHS.put(hostId, paths);
    }

    public void setPathsToClient(HostId hostId) {
        Set<MyPath> paths = Services.pathService.getKShortestPaths(HostId.hostId(Util.FL_SERVER_MAC), hostId)
                .limit(10).map(MyPath::new)
                .collect(Collectors.toSet());

        PathRulesInstaller.INSTANCE.installPathRules(ClientInformationDatabase.INSTANCE.getHostByHostID(hostId).get(), (Path) paths.toArray()[0], true);
        SERVER_TO_CLIENT_PATHS.put(hostId, paths);
    }
    void printAll() {
        Util.log("paths", "***************Server to Clients Paths***************");
        for (FLHost host : ClientInformationDatabase.INSTANCE.getFLHosts()) {
            StringBuilder stringBuilder = new StringBuilder(String.format("***** Paths for Client %s ****\n", host.getFlClientCID()));
            getPathsToClient(host).forEach(myPath -> stringBuilder.append(myPath.format()).append("\n"));
            Util.log("paths", stringBuilder.toString());
        }
        Util.log("paths", "***************Client to Server Paths***************");
        for (FLHost host : ClientInformationDatabase.INSTANCE.getFLHosts()) {
            StringBuilder stringBuilder = new StringBuilder(String.format("***** Paths for Client %s ****\n", host.getFlClientCID()));
            getPathsToServer(host).forEach(myPath -> stringBuilder.append(myPath.format()).append("\n"));
            Util.log("paths", stringBuilder.toString());
        }
    }
}
