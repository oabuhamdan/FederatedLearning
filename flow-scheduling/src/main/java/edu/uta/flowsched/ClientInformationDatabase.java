package edu.uta.flowsched;

import org.onlab.packet.MacAddress;
import org.onlab.util.KryoNamespace;
import org.onosproject.net.*;
import org.onosproject.store.serializers.KryoNamespaces;
import org.onosproject.store.service.EventuallyConsistentMap;
import org.onosproject.store.service.WallClockTimestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class ClientInformationDatabase {
    public static final ClientInformationDatabase INSTANCE = new ClientInformationDatabase();

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientInformationDatabase.class);
    private EventuallyConsistentMap<Host, FLHost> FL_HOSTS;


    protected void activate() {
        KryoNamespace.Builder mySerializer = KryoNamespace.newBuilder().register(KryoNamespaces.API)
                .register(FLHost.class);

        FL_HOSTS = Services.storageService.<Host, FLHost>eventuallyConsistentMapBuilder()
                .withName("FL_HOSTS")
                .withTimestampProvider((k, v) -> new WallClockTimestamp())
                .withSerializer(mySerializer).build();
    }

    public void updateHost(MacAddress mac, String flClientID, String flClientCID) {
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
    }

    public FLHost getHostByFLCID(String FLCID) {
        return FL_HOSTS.values().stream().filter(flHost -> FLCID.equals(flHost.getFlClientCID())).findFirst().orElseThrow();
    }

    public FLHost getHostByHostID(HostId hostId) {
        return FL_HOSTS.get(Services.hostService.getHost(hostId));
    }

    public List<FLHost> getHostsByFLIDs(Set<String> FLIDs) {
        return FL_HOSTS.values().stream().filter(flHost -> FLIDs.contains(flHost.getFlClientID())).collect(Collectors.toList());
    }

    protected void deactivate() {
        FL_HOSTS.clear();
    }

    public Collection<FLHost> getFLHosts() {
        return Collections.unmodifiableCollection(FL_HOSTS.values());
    }
}
