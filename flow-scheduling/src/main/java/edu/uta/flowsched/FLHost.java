package edu.uta.flowsched;

import edu.uta.flowsched.schedulers.GreedyFlowScheduler;
import org.onlab.packet.IpAddress;
import org.onlab.packet.MacAddress;
import org.onlab.packet.VlanId;
import org.onosproject.net.Annotations;
import org.onosproject.net.DefaultHost;
import org.onosproject.net.HostId;
import org.onosproject.net.HostLocation;
import org.onosproject.net.provider.ProviderId;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static edu.uta.flowsched.Util.bitToMbit;

public class FLHost extends DefaultHost {
    private String flClientID;
    private String flClientCID;
    private long lastPathChange;
    public final NetworkStats networkStats;
    private MyPath currentPath;

    public FLHost(ProviderId providerId, HostId id, MacAddress mac, VlanId vlan, HostLocation location, Set<IpAddress> ips
            , String flClientID, String flClientCID, Annotations... annotations) {
        super(providerId, id, mac, vlan, location, ips, annotations);
        this.flClientID = flClientID;
        this.flClientCID = flClientCID;
        this.networkStats = new NetworkStats();
        this.lastPathChange = System.currentTimeMillis();
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

    public long getLastPathChange() {
        return lastPathChange;
    }

    public void setLastPathChange(long lastPathChange) {
        this.lastPathChange = lastPathChange;
    }

    public void setCurrentPath(MyPath path) {
        this.currentPath = path;
    }
    public MyPath getCurrentPath() {
        return currentPath;
    }

    public Set<FLHost> assignNewPath(MyPath newPath) {
        Set<FLHost> affectedClients = ConcurrentHashMap.newKeySet();
        if (this.currentPath != null)
            affectedClients.addAll(currentPath.removeFlow(this));
        setCurrentPath(newPath);
        affectedClients.addAll(newPath.addClient(this));
        return affectedClients;
    }

    public boolean clearPath() {
        if (this.currentPath == null) {
            return false;
        }
        currentPath.removeFlow(this);
        setCurrentPath(null);
        return true;
    }

    public static class NetworkStats {
        private final BoundedConcurrentLinkedQueue<Long> lastPositiveTXRate;
        private final BoundedConcurrentLinkedQueue<Long> lastPositiveRXRate;
        private final BoundedConcurrentLinkedQueue<Long> lastTXRate;
        private final BoundedConcurrentLinkedQueue<Long> lastRXRate;
        private final ConcurrentHashMap<Integer, Long> roundSentData;
        private final ConcurrentHashMap<Integer, Long> roundReceivedData;

        public NetworkStats() {
            this.lastTXRate = new BoundedConcurrentLinkedQueue<>(3);
            this.lastRXRate = new BoundedConcurrentLinkedQueue<>(3);
            this.lastPositiveTXRate = new BoundedConcurrentLinkedQueue<>(3);
            this.lastPositiveRXRate = new BoundedConcurrentLinkedQueue<>(3);
            this.roundSentData = new ConcurrentHashMap<>();
            this.roundReceivedData = new ConcurrentHashMap<>();
        }

        public String printStats() {
            StringBuilder builder = new StringBuilder();
            try {
                int c2SRound = GreedyFlowScheduler.C2S.getRound();
                int s2cRound = GreedyFlowScheduler.S2C.getRound();

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
            return Optional.ofNullable(this.lastPositiveTXRate.peekLast()).orElse(0L);
//            return (long) Util.weightedAverage(lastPositiveTXRate, true);
        }

        public long getLastPositiveRXRate() {
            return Optional.ofNullable(this.lastPositiveRXRate.peekLast()).orElse(0L);
//            return (long) Util.weightedAverage(lastPositiveRXRate, true);
        }

        public long getLastRate(FlowDirection direction) {
            return direction.equals(FlowDirection.S2C) ? this.getLastRXRate() : this.getLastTXRate();
        }

        public long getLastPositiveRate(FlowDirection direction) {
            return direction.equals(FlowDirection.S2C) ? this.getLastPositiveRXRate() : this.getLastPositiveTXRate();
        }


        public long getRoundExchangedData(FlowDirection dir, int round) {
            return dir.equals(FlowDirection.S2C) ? this.getRoundReceivedData(round) : this.getRoundSentData(round);
        }

        public long getLastTXRate() {
            return Optional.ofNullable(this.lastTXRate.peekLast()).orElse(0L);
//            return (long) Util.weightedAverage(lastTXRate, true);
        }

        public long getLastRXRate() {
            return Optional.ofNullable(this.lastRXRate.peekLast()).orElse(0L);
//            return (long) Util.weightedAverage(lastRXRate, true);
        }
        public void setLastPositiveTXRate(long lastPositiveTXRate) {
            this.lastPositiveTXRate.add(lastPositiveTXRate);
        }

        public void setLastPositiveRXRate(long lastPositiveRXRate) {
            this.lastPositiveRXRate.add(lastPositiveRXRate);
        }

        public void setLastTXRate(long lastTXRate) {
            this.lastTXRate.add(lastTXRate);
        }

        public void setLastRXRate(long lastRXRate) {
            this.lastRXRate.add(lastRXRate);
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
