package edu.uta.flowsched;

import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

public class MyLinkDeepCopy {
    private long estimatedFreeCapacity;
    private long currentThroughput;
    private long defaultCapacity;
    private long reservedCapacity;
    private int activeFlows;
    private String type;

    private HashMap<String, Long> rateReservedPerHost;
    private String src;
    private String dst;

    public String getSrc() {
        return src;
    }

    public String getDst() {
        return dst;
    }

    public long getEstimatedFreeCapacity() {
        return estimatedFreeCapacity;
    }

    public long getCurrentThroughput() {
        return currentThroughput;
    }

    public long getReservedCapacity() {
        return reservedCapacity;
    }

    public int getActiveFlows() {
        return activeFlows;
    }

    public HashMap<String, Long> getRateReservedPerHost() {
        return rateReservedPerHost;
    }

    public String formatLink() {
        final String LINK_STRING_FORMAT = "%s -> %s";
        return String.format(LINK_STRING_FORMAT, src.substring(15), dst.substring(15));
    }

    public void setEstimatedFreeCapacity(long estimatedFreeCapacity) {
        this.estimatedFreeCapacity = estimatedFreeCapacity;
    }

    public void setCurrentThroughput(long currentThroughput) {
        this.currentThroughput = currentThroughput;
    }

    public void setReservedCapacity(long reservedCapacity) {
        this.reservedCapacity = reservedCapacity;
    }

    public void setActiveFlows(int activeFlows) {
        this.activeFlows = activeFlows;
    }

    public void setRateReservedPerHost(HashMap<String, Long> flHostLongHashMap) {
        this.rateReservedPerHost = flHostLongHashMap;
    }

    public void setSrc(String src) {
        this.src = src;
    }

    public void setDst(String dst) {
        this.dst = dst;
    }

    public long getDefaultCapacity() {
        return defaultCapacity;
    }

    public void setDefaultCapacity(long defaultCapacity) {
        this.defaultCapacity = defaultCapacity;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }



    public static MyLinkDeepCopy deepCopy(MyLink link){
        MyLinkDeepCopy copy = new MyLinkDeepCopy();
        copy.setEstimatedFreeCapacity(link.getEstimatedFreeCapacity());
        copy.setCurrentThroughput(link.getThroughput());
        copy.setReservedCapacity(link.getReservedCapacity());
        copy.setDefaultCapacity(link.getDefaultCapacity());
        copy.setType(link.type().toString());
        copy.setSrc(link.src().toString());
        copy.setDst(link.dst().toString());
        copy.setActiveFlows(link.getActiveFlows());
//        copy.setRateReservedPerHost(copyMap(link.getRateReservedPerHost()));
        return copy;
    }
    private static HashMap<String, Long> copyMap(ConcurrentHashMap<FLHost, Long> original) {
        HashMap<String, Long> copy = new HashMap<>();
        for (FLHost key : original.keySet()) {
            copy.put(key.getFlClientCID(), original.get(key));
        }
        return copy;
    }
}
