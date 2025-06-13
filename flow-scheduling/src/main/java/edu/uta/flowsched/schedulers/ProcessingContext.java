package edu.uta.flowsched.schedulers;

import edu.uta.flowsched.BatchAwareBlockingQueue;
import edu.uta.flowsched.ClientInformationDatabase;
import edu.uta.flowsched.FLHost;
import edu.uta.flowsched.MyPath;

import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class ProcessingContext {
    public Queue<FLHost> clientQueue;
    public Map<FLHost, Integer> completionTimes;
    public Map<FLHost, Set<MyPath>> clientPaths;
    public Map<FLHost, Long> dataRemaining;
    public Map<FLHost, Long> assumedRates;
    public LinkedBlockingQueue<FLHost> needPhase1Processing;
    public ConcurrentLinkedQueue<FLHost> needPhase2Processing;
    public Set<FLHost> completedClients;

    public ProcessingContext(ProcessingContext otherContext) {
        this.clientQueue = otherContext.clientQueue;
        this.completionTimes = otherContext.completionTimes;
        this.clientPaths = otherContext.clientPaths;
        this.dataRemaining = otherContext.dataRemaining;
        this.assumedRates = otherContext.assumedRates;
        this.needPhase1Processing = otherContext.needPhase1Processing;
        this.needPhase2Processing = otherContext.needPhase2Processing;
        this.completedClients = otherContext.completedClients;
    }

    public ProcessingContext() {
        this.clientQueue = new ConcurrentLinkedQueue<>();
        this.completionTimes = new ConcurrentHashMap<>();
        this.dataRemaining = new ConcurrentHashMap<>();
        this.assumedRates = new ConcurrentHashMap<>();
        this.clientPaths = new ConcurrentHashMap<>();
        this.needPhase1Processing = new LinkedBlockingQueue<>();
        this.needPhase2Processing = new ConcurrentLinkedQueue<>();
        this.completedClients = ConcurrentHashMap.newKeySet();
    }

    public void clear(){
        this.clientQueue.clear();
        this.completionTimes.clear();
        this.dataRemaining.clear();
        this.assumedRates.clear();
        this.clientPaths.clear();
        this.needPhase1Processing.clear();
        this.needPhase2Processing.clear();
        this.completedClients.clear();
    }
}
