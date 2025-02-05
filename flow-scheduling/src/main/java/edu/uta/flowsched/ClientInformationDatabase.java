package edu.uta.flowsched;

import org.onlab.packet.MacAddress;
import org.onlab.util.KryoNamespace;
import org.onosproject.net.*;
import org.onosproject.net.device.DeviceEvent;
import org.onosproject.net.device.DeviceListener;
import org.onosproject.net.device.PortStatistics;
import org.onosproject.store.serializers.KryoNamespaces;
import org.onosproject.store.service.EventuallyConsistentMap;
import org.onosproject.store.service.WallClockTimestamp;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static edu.uta.flowsched.Util.bitToMbit;
import static org.onosproject.net.device.DeviceEvent.Type.PORT_STATS_UPDATED;

public class ClientInformationDatabase {
    public static final ClientInformationDatabase INSTANCE = new ClientInformationDatabase();
    private EventuallyConsistentMap<Host, FLHost> FL_HOSTS;
    private LinkThroughputWatcher linkThroughputWatcher;
    private static int HOST_COUNT = 10;

    protected void activate() {
        KryoNamespace.Builder mySerializer = KryoNamespace.newBuilder().register(KryoNamespaces.API)
                .register(FLHost.class);
        linkThroughputWatcher = new LinkThroughputWatcher();
        Services.deviceService.addListener(linkThroughputWatcher);

        FL_HOSTS = Services.storageService.<Host, FLHost>eventuallyConsistentMapBuilder()
                .withName("FL_HOSTS")
                .withTimestampProvider((k, v) -> new WallClockTimestamp())
                .withSerializer(mySerializer).build();
    }

    public FLHost updateHost(MacAddress mac, String flClientID, String flClientCID) {
        HostId hostId = HostId.hostId(mac);
        Host host = Services.hostService.getHost(hostId);
        FLHost flHost;
        if (FL_HOSTS.containsKey(host)) {
            flHost = FL_HOSTS.get(host);
            flHost.setFlClientCID(flClientCID);
            flHost.setFlClientID(flClientID);
        } else {
            flHost = new FLHost(host.providerId(), host.id(), host.mac(), host.vlan(), host.location(), host.ipAddresses(), flClientID, flClientCID
                    , host.annotations());
            FL_HOSTS.put(host, flHost);
        }
        return flHost;
    }

    public FLHost getHostByFLCID(String FLCID) {
        return FL_HOSTS.values().stream().filter(flHost -> FLCID.equals(flHost.getFlClientCID())).findFirst().orElseThrow();
    }

    public Optional<FLHost> getHostByHostID(HostId hostId) {
        return Optional.ofNullable(FL_HOSTS.get(Services.hostService.getHost(hostId)));
    }

    public List<FLHost> getHostsByFLIDs(Set<String> FLIDs) {
        return FL_HOSTS.values().stream().filter(flHost -> FLIDs.contains(flHost.getFlClientID())).collect(Collectors.toList());
    }

    protected void deactivate() {
        FL_HOSTS.clear();
        Services.deviceService.removeListener(linkThroughputWatcher);
    }

    public Collection<FLHost> getFLHosts() {
        return Collections.unmodifiableCollection(FL_HOSTS.values());
    }

    private class LinkThroughputWatcher implements DeviceListener {
        AtomicInteger currentCount = new AtomicInteger(0);
        long threshold = (long) 1e6; // 2Mbps

        @Override
        public void event(DeviceEvent event) {
            DeviceEvent.Type type = event.type();
            if (type == PORT_STATS_UPDATED) {
                if (currentCount.incrementAndGet() >= HOST_COUNT) {
                    currentCount.set(0);
                    updateDeviceLinksUtilization();
                }
            }
        }

        private void updateDeviceLinksUtilization() {
            getFLHosts().forEach(flHost -> {
                HostLocation location = flHost.location();
                PortStatistics portStatistics = Services.deviceService.getDeltaStatisticsForPort(location.deviceId(), location.port());
                long receivedBits = portStatistics.bytesReceived() * 8;
                long sentBits = portStatistics.bytesSent() * 8;
                long receivedRate = receivedBits / Util.POLL_FREQ;
                long sentRate = sentBits/ Util.POLL_FREQ;

                flHost.networkStats.setLastRXRate(sentRate); // RX Rate for Host is the TX Rate for Port
                flHost.networkStats.setLastTXRate(receivedRate); // TX Rate for Host is the RX Rate for Port
                flHost.networkStats.setRoundReceivedData(GreedyFlowScheduler.S2C_INSTANCE.getRound(), sentBits);
                flHost.networkStats.setRoundSentData(GreedyFlowScheduler.C2S_INSTANCE.getRound(), receivedBits);

                if (sentRate > threshold) {
                    flHost.networkStats.setLastPositiveRXRate(sentRate);
                }
                if (receivedRate > threshold) {
                    flHost.networkStats.setLastPositiveTXRate(receivedRate);
                }

//                roundSentData.get(flHost).set(ZeroMQServer.getCurrentRound(), portStatistics.bytesSent() * 8);
//                roundReceivedData.get(flHost).set(ZeroMQServer.getCurrentRound(), portStatistics.bytesSent() * 8);
            });
        }
    }
}
