package edu.uta.flowsched;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.onlab.util.KryoNamespace;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.HostId;
import org.onosproject.net.Link;
import org.onosproject.net.device.DeviceEvent;
import org.onosproject.net.device.DeviceListener;
import org.onosproject.net.device.PortStatistics;
import org.onosproject.store.serializers.KryoNamespaces;
import org.onosproject.store.service.EventuallyConsistentMap;
import org.onosproject.store.service.WallClockTimestamp;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.onosproject.net.HostId.hostId;
import static org.onosproject.net.device.DeviceEvent.Type.PORT_STATS_UPDATED;

public class PathInformationDatabase {
    public static final PathInformationDatabase INSTANCE = new PathInformationDatabase();
    private static ScheduledExecutorService executorService;

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

        executorService = Executors.newSingleThreadScheduledExecutor();
        executorService.scheduleAtFixedRate(this::updateBottleneckPath, 2, 2, TimeUnit.SECONDS);
    }

    protected void deactivate() {
        executorService.shutdownNow();
        CLIENT_TO_SERVER_PATHS.clear();
        SERVER_TO_CLIENT_PATHS.clear();
    }

    public List<MyPath> getPathsToServer(HostId hostId) {
        return Optional.ofNullable(CLIENT_TO_SERVER_PATHS.get(hostId)).orElse(List.of());
    }

    public List<MyPath> getPathsToClient(HostId hostId) {
        return Optional.ofNullable(SERVER_TO_CLIENT_PATHS.get(hostId)).orElse(List.of());
    }

    public void setPathsToServer(HostId hostId) {
        // Set up a set of 5 quick paths
        List<MyPath> clientToServerPaths = Services.pathService.getKShortestPaths(hostId, HostId.hostId(Util.FL_SERVER_MAC))
                .limit(10).map(MyPath::new).collect(Collectors.toList());
        CLIENT_TO_SERVER_PATHS.put(hostId, clientToServerPaths);
    }

    public void setPathsToClient(HostId hostId) {
        List<MyPath> serverToClientPaths = Services.pathService.getKShortestPaths(HostId.hostId(Util.FL_SERVER_MAC), hostId)
                .limit(10)
                .map(MyPath::new).collect(Collectors.toList());
        SERVER_TO_CLIENT_PATHS.put(hostId, serverToClientPaths);
    }

    private void updateBottleneckPath() {
        Stream.concat(
                CLIENT_TO_SERVER_PATHS.values().stream().flatMap(List::stream),
                SERVER_TO_CLIENT_PATHS.values().stream().flatMap(List::stream)
        ).forEach(MyPath::updateBottleneckLink);
    }
}
