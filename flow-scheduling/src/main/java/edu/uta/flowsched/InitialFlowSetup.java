package edu.uta.flowsched;

import org.apache.commons.lang3.tuple.Pair;
import org.onlab.packet.IPv4;
import org.onlab.packet.MacAddress;
import org.onlab.packet.TpPort;
import org.onosproject.net.Host;
import org.onosproject.net.HostId;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.intent.PathIntent;

import java.util.List;
import java.util.Map;

import static edu.uta.flowsched.Util.MODEL_SIZE;
import static org.onlab.packet.Ethernet.TYPE_IPV4;

public class InitialFlowSetup {
    public static final InitialFlowSetup INSTANCE = new InitialFlowSetup();
    private final HostId serverHost = HostId.hostId(MacAddress.valueOf("00:00:00:00:00:AA"));

    private void call() {
        Iterable<Host> clients = Services.hostService.getHosts();
        for (Host client : clients) {
            if (client.id().equals(serverHost) || client.mac().toString().startsWith("AA"))
                continue;

//            List<Map.Entry<MyPath, Double>> clientToServerPaths = PathFinder.getPathsToServer(client.id());
//            List<Map.Entry<MyPath, Double>> serverToClientPaths = PathFinder.getPathsToClient(client.id());
            MyPath clientToServerPath = null;
            MyPath serverToClientPath = null;

            clientToServerPath.reserveCapacity(MODEL_SIZE);
            serverToClientPath.reserveCapacity(MODEL_SIZE);

            TrafficSelector clientToServerSelector = DefaultTrafficSelector.builder().matchEthSrc(client.mac()).matchEthDst(serverHost.mac())
                    .matchEthType(TYPE_IPV4)
                    .matchIPProtocol(IPv4.PROTOCOL_TCP)
                    .matchTcpDst(TpPort.tpPort(8080))
                    .build();
            TrafficSelector serverToClientSelector = DefaultTrafficSelector.builder().matchEthSrc(serverHost.mac()).matchEthDst(client.mac())
                    .matchEthType(TYPE_IPV4)
                    .matchIPProtocol(IPv4.PROTOCOL_TCP)
                    .matchTcpSrc(TpPort.tpPort(8080))
                    .build();

            PathIntent clientToServerPathIntent = PathIntent.builder().path(clientToServerPath).appId(Services.appId).priority(20).selector(clientToServerSelector).build();
            PathIntent serverToClientPathIntent = PathIntent.builder().path(serverToClientPath).appId(Services.appId).priority(20).selector(serverToClientSelector).build();

//            PathInformationDatabase.INSTANCE.setCurrentPathToClient(client.id(), serverToClientPath);
//            PathInformationDatabase.INSTANCE.setCurrentPathToServer(client.id(), clientToServerPath);

            Services.intentService.submit(clientToServerPathIntent);
            Services.intentService.submit(serverToClientPathIntent);
        }
    }


    public void activate() {
        call();
    }

    public void deactivate() {

    }
}