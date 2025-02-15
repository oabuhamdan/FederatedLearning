package edu.uta.flowsched;

import org.onlab.packet.IpAddress;
import org.onlab.packet.MacAddress;
import org.onlab.packet.VlanId;
import org.onosproject.net.Annotations;
import org.onosproject.net.DefaultHost;
import org.onosproject.net.HostId;
import org.onosproject.net.HostLocation;
import org.onosproject.net.provider.ProviderId;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static edu.uta.flowsched.Util.bitToMbit;

class FLHost extends DefaultHost {
    private String flClientID;
    private String flClientCID;
    private MyPath currentC2SPath;
    private MyPath currentS2CPath;
    public final NetworkStats networkStats;


    public FLHost(ProviderId providerId, HostId id, MacAddress mac, VlanId vlan, HostLocation location, Set<IpAddress> ips
            , String flClientID, String flClientCID, Annotations... annotations) {
        super(providerId, id, mac, vlan, location, ips, annotations);
        this.flClientID = flClientID;
        this.flClientCID = flClientCID;
        this.currentC2SPath = null;
        this.currentS2CPath = null;
        this.networkStats = new NetworkStats();
    }

    public String getFlClientCID() {
        return flClientCID;
    }

    public void setFlClientCID(String flClientCID) {
        this.flClientCID = flClientCID;
    }

    public String getFlClientID() {
        return flClientID;
    }

    public void setFlClientID(String flClientID) {
        this.flClientID = flClientID;
    }


    @Override
    public String toString() {
        return "FLHost{" +
                "flClientID='" + flClientID + '\'' +
                ", flClientCID='" + flClientCID + '\'' +
                '}';
    }

    public void setCurrentPath(MyPath path, FlowDirection direction) {
        if (direction.equals(FlowDirection.S2C)) {
            this.currentS2CPath = path;
        } else if (direction.equals(FlowDirection.C2S)) {
            this.currentC2SPath = path;
        }
    }


    public MyPath getCurrentPath(FlowDirection direction) {
        if (direction.equals(FlowDirection.S2C)) {
            return this.currentS2CPath;
        } else if (direction.equals(FlowDirection.C2S)) {
            return this.currentC2SPath;
        } else throw new RuntimeException("Direction should be assigned");
    }

    class NetworkStats {
        private final LinkedList<Long> lastPositiveTXRate;
        private final LinkedList<Long> lastPositiveRXRate;
        private final LinkedList<Long> lastTXRate;
        private final LinkedList<Long> lastRXRate;

        private final ConcurrentHashMap<Integer, Long> roundSentData;
        private final ConcurrentHashMap<Integer, Long> roundReceivedData;

        public NetworkStats() {
            this.lastTXRate = new LinkedList<>();
            this.lastRXRate = new LinkedList<>();
            this.lastPositiveTXRate = new LinkedList<>();
            this.lastPositiveRXRate = new LinkedList<>();
            this.roundSentData = new ConcurrentHashMap<>();
            this.roundReceivedData = new ConcurrentHashMap<>();
        }

        public String printStats() {
            StringBuilder builder = new StringBuilder();
            try {
                int c2SRound = GreedyFlowScheduler.C2S_INSTANCE.getRound();
                int s2cRound = GreedyFlowScheduler.S2C_INSTANCE.getRound();

                builder.append(s2cRound).append(",");
                builder.append(c2SRound).append(",");

                builder.append(bitToMbit(getRoundReceivedData(s2cRound))).append(",");
                builder.append(bitToMbit(getRoundSentData(c2SRound))).append(",");

                builder.append(getLastPositiveTXRate()).append(",")
                        .append(getLastPositiveRXRate()).append(",");

                builder.append(getLastTXRate()).append(",")
                        .append(getLastRXRate());

                builder.append("\n");
            } catch (Exception e) {
                builder.append("ERROR: ").append(e.getMessage()).append("...").append(Arrays.toString(e.getStackTrace()));
            }
            return builder.toString();
        }

        public long getLastPositiveTXRate() {
            return (long) lastPositiveTXRate.stream().mapToLong(Long::longValue).average().orElse(0);
        }

        public long getLastPositiveRXRate() {
            return (long) lastPositiveRXRate.stream().mapToLong(Long::longValue).average().orElse(0);
        }

        public long getLastPositiveRate(FlowDirection direction) {
            return direction.equals(FlowDirection.S2C) ? this.getLastPositiveRXRate() : this.getLastPositiveTXRate();
        }


        public long getRoundExchangedData(FlowDirection dir, int round) {
            return dir.equals(FlowDirection.S2C) ? this.getRoundReceivedData(round) : this.getRoundSentData(round);
        }

        public long getLastTXRate() {
            return (long) lastTXRate.stream().mapToLong(Long::longValue).average().orElse(0);
        }

        public long getLastRXRate() {
            return (long) lastRXRate.stream().mapToLong(Long::longValue).average().orElse(0);
        }

        public void setLastPositiveTXRate(long lastPositiveTXRate) {
            this.lastPositiveTXRate.addLast(lastPositiveTXRate);
            // keep it limited to 0
            if (this.lastPositiveTXRate.size() > 3)
                this.lastPositiveTXRate.removeFirst();
        }

        public void setLastPositiveRXRate(long lastPositiveRXRate) {
            this.lastPositiveRXRate.addLast(lastPositiveRXRate);
            // keep it limited to 0
            if (this.lastPositiveRXRate.size() > 5)
                this.lastPositiveRXRate.removeFirst();
        }

        public void setLastTXRate(long lastTXRate) {
            this.lastTXRate.add(lastTXRate);
            if (this.lastTXRate.size() > 5)
                this.lastTXRate.removeFirst();

        }

        public void setLastRXRate(long lastRXRate) {
            this.lastRXRate.add(lastRXRate);
            if (this.lastRXRate.size() > 3)
                this.lastRXRate.removeFirst();
        }

        public long getRoundSentData(int round) {
            return this.roundSentData.get(round);
        }

        public void setRoundSentData(int round, long value) {
            this.roundSentData.compute(round, (k, v) -> v == null ? value : value + v);
        }

        public long getRoundReceivedData(int round) {
            return this.roundReceivedData.get(round);
        }

        public void setRoundReceivedData(int round, long value) {
            this.roundReceivedData.compute(round, (k, v) -> v == null ? value : value + v);
        }
    }
}
