package edu.uta.flowsched;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.onlab.packet.MacAddress;
import org.onosproject.net.HostId;
import org.zeromq.ZMQ;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class ZeroMQServer {

    public static ZeroMQServer INSTANCE = new ZeroMQServer();

    private static final ExecutorService executorService = Executors.newCachedThreadPool();
    private static final ClientInformationDatabase clientInformationDatabase = ClientInformationDatabase.INSTANCE;
    private static final PathInformationDatabase pathInformationDatabase = PathInformationDatabase.INSTANCE;

    private static int TOTAL_CLIENTS = 10;
    private static int clientMessages = 0;

    ZMQ.Socket socket;
    ZMQ.Context context;

    void activate() {
        context = ZMQ.context(1);

        socket = context.socket(ZMQ.PULL);

        socket.bind("tcp://11.66.33.46:5555");

        Util.log("general", "Waiting for messages...");
        executorService.submit(this::listener);
    }

    private void listener() {
        while (!context.isClosed()) {
            String message = new String(socket.recv(0));
            executorService.submit(() -> handleMessage(message));
        }
    }

    private void handleMessage(String message) {
        try {
            JSONObject jsonObject = new JSONObject(message);
            MessageType messageType = MessageType.fromId(jsonObject.getInt("message_type"));
            long clientTimeMS = jsonObject.getLong("time_ms");
            long serverTimeMS = System.currentTimeMillis();
            Object jsonMessage = jsonObject.get("message");
            Util.log("overhead.csv", String.format("zmq,%s,%s", messageType, serverTimeMS - clientTimeMS));
            if (messageType == MessageType.UPDATE_DIRECTORY) {
                JSONObject clientInfo = (JSONObject) jsonMessage;
                updateDirectory(clientInfo);
            } else if (messageType == MessageType.SERVER_TO_CLIENTS) {
                JSONArray clients = (JSONArray) jsonMessage;
                handleServerToClientsPaths(clients);
            } else if (messageType == MessageType.CLIENT_TO_SERVER) {
                if (clientMessages == 0){
                    GreedyFlowScheduler.C2S_INSTANCE.startScheduling();
                }

                String clientCID = jsonObject.getString("sender_id");
                handleClientToServerPath(clientCID, clientTimeMS);
                if (++clientMessages == TOTAL_CLIENTS) {
                    clientMessages = 0;
                }
            }
        } catch (Exception e) {
            Util.log("general", String.format("Error processing message: %s", e.getMessage()));
            Util.log("general", String.format("%s", (Object[]) e.getStackTrace()));
        }
    }

    private void updateDirectory(JSONObject clientInfo) throws JSONException {
        long tik = System.currentTimeMillis();
        MacAddress macAddress = MacAddress.valueOf(clientInfo.getString("mac"));
        String flClientID = clientInfo.getString("client_id");
        String flClientCID = clientInfo.getString("client_cid");
        clientInformationDatabase.updateHost(macAddress, flClientID, flClientCID);
        HostId hostId = HostId.hostId(macAddress);
        PathInformationDatabase.INSTANCE.setPathsToClient(hostId);
        PathInformationDatabase.INSTANCE.setPathsToServer(hostId);
        Util.log("overhead.csv", String.format("controller,update_directory,%s", System.currentTimeMillis() - tik));
//        Util.log("general", String.format("Took %s ms to handle Update Directory for Client %s", , flClientCID));
    }

    private void handleClientToServerPath(String clientID, long time) {
        FLHost host = clientInformationDatabase.getHostByFLCID(clientID);
        GreedyFlowScheduler.C2S_INSTANCE.addClient(host, pathInformationDatabase.getPathsToServer(host));
    }

    private void handleServerToClientsPaths(JSONArray clients) throws JSONException {
        HashSet<String> hashSet = new HashSet<>();
        for (int i = 0; i < clients.length(); i++) {
            hashSet.add(clients.getString(i));
        }
        List<FLHost> hosts = clientInformationDatabase.getHostsByFLIDs(hashSet);
        StringBuilder sb = new StringBuilder();
        hosts.forEach(host -> sb.append(host.getFlClientCID()).append(","));
        Util.log("general,greedy,debug_paths", String.format("** Server to Client Handing For: %s **", sb));
        hosts = sortBasedOnMaxRate(hosts);
        for (FLHost host : hosts) {
            GreedyFlowScheduler.S2C_INSTANCE.addClient(host, pathInformationDatabase.getPathsToClient(host));
        }
        GreedyFlowScheduler.S2C_INSTANCE.startScheduling();
    }

    private List<FLHost> sortHostsBasedOnSwitchDegree(List<FLHost> hosts) {
        return hosts.stream().sorted(Comparator.comparing(flHost -> Services.linkService.
                getDeviceEgressLinks(flHost.location().deviceId()).size())).collect(Collectors.toList());
    }

    private List<FLHost> sortBasedOnMaxRate(List<FLHost> hosts){
        Map<FLHost, Double> rates = new HashMap<>();
        for (FLHost host : hosts) {
            Set<MyPath> paths = PathInformationDatabase.INSTANCE.getPathsToClient(host);
            double score = paths.stream()
                    .mapToDouble(path ->path.getBottleneckFreeCap()/1e6)
                    .max()
                    .orElse(0);
            rates.put(host, score);
        }
        StringBuilder debug = new StringBuilder("\t******** Sorted Clients ********\n");
        List<FLHost> ordered = rates.entrySet().stream().sorted(Collections.reverseOrder(Map.Entry.comparingByValue()))
                .peek(entry-> debug.append(String.format("\t\tHost: %s Score:%s\n", entry.getKey().getFlClientCID(), entry.getValue())))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        Util.log("greedy", debug.toString());
        return ordered;

    }


    void deactivate() {
        executorService.shutdownNow();
        context.term();
        context.close();
        socket.close();
    }

    enum MessageType {
        UPDATE_DIRECTORY(1),
        SERVER_TO_CLIENTS(2),
        CLIENT_TO_SERVER(3);

        MessageType(int id) {
            this.id = id;
        }

        public static MessageType fromId(int id) {
            for (MessageType type : MessageType.values()) {
                if (type.id == id) {
                    return type;
                }
            }
            throw new IllegalArgumentException("No MessageType with id " + id);
        }

        private final int id;
    }
}
