package edu.uta.flowsched;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.onlab.packet.MacAddress;
import org.onosproject.net.HostId;
import org.zeromq.ZMQ;

import java.util.HashSet;
import java.util.List;
import java.util.concurrent.*;

public class ZeroMQServer {

    public static ZeroMQServer INSTANCE = new ZeroMQServer();

    private static final ExecutorService executorService = Executors.newCachedThreadPool();
    private static final ClientInformationDatabase clientInformationDatabase = ClientInformationDatabase.INSTANCE;
    private static int ROUND = 1;
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
                Util.log("general", String.format("************ Starting Round: %s ************", ROUND++));
                Util.log("flow_debug", String.format("************ Starting Round: %s ************", ROUND));
                JSONArray clients = (JSONArray) jsonMessage;
                handleServerToClientsPaths(clients);
            } else if (messageType == MessageType.CLIENT_TO_SERVER) {
                String clientCID = jsonObject.getString("sender_id");
                handleClientToServerPath(clientCID, clientTimeMS);
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
        SetupPath.clientToServer(host);
    }

    private void handleServerToClientsPaths(JSONArray clients) throws JSONException {
        HashSet<String> hashSet = new HashSet<>();
        for (int i = 0; i < clients.length(); i++) {
            hashSet.add(clients.getString(i));
        }
        List<FLHost> hosts = clientInformationDatabase.getHostsByFLIDs(hashSet);
        StringBuilder sb = new StringBuilder();
        hosts.forEach(host -> sb.append(host.getFlClientCID()).append(","));
        Util.log("general", String.format("** Server to Client Handing For: %s **", sb));
        SetupPath.serverToClient(hosts);
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
