package edu.uta.flowsched.schedulers;

import edu.uta.flowsched.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static edu.uta.flowsched.Util.bitToMbit;
import static edu.uta.flowsched.Util.formatMessage;
import static edu.uta.flowsched.schedulers.CONFIGS.DATA_SIZE;

public abstract class SmartFlowScheduler {
    public static SmartFlowScheduler S2C;
    public static SmartFlowScheduler C2S;
    protected static ExecutorService mainExecutor;
    protected static ScheduledExecutorService phase2Executor;
    protected FlowDirection direction;
    protected final AtomicInteger round;
    protected ScheduledFuture<?> future;
    private final ProcessingContext context;

    protected SmartFlowScheduler(FlowDirection direction) {
        this.direction = direction;
        round = new AtomicInteger(1);
        context = new ProcessingContext();
    }

    public static void activate() {
        S2C = GreedySmartFlow.getInstance(FlowDirection.S2C);
        C2S = GreedySmartFlow.getInstance(FlowDirection.C2S);
        mainExecutor = Executors.newFixedThreadPool(2);
        phase2Executor = Executors.newScheduledThreadPool(2);
    }

    public static void deactivate() {
        mainExecutor.shutdownNow();
        phase2Executor.shutdownNow();
    }

    public static void startRound() {
        mainExecutor.submit(() -> S2C.main());
        mainExecutor.submit(() -> C2S.main());
    }

    public void addClientToQueue(FLHost client) {
        initClient(client);
        context.needPhase1Processing.offer(client);
    }

    public void addClientsToQueue(List<FLHost> clients) {
        clients.forEach(this::initClient);
        initialSort(context, clients);
        context.needPhase1Processing.addAll(clients);
    }

    public void initialSort(ProcessingContext context, List<FLHost> clients) {
    }

    private void initClient(FLHost client) {
        client.clearPath();
        client.setLastPathChange(0);
        context.dataRemaining.put(client, DATA_SIZE);
        context.assumedRates.put(client, 0L);
        context.completionTimes.put(client, 100);
        context.clientPaths.put(client, PathInformationDatabase.INSTANCE.getPaths(client, this.direction));
    }

    protected void main() {
        AtomicLong phase1TotalTime = new AtomicLong(0), phase2TotalTime = new AtomicLong(0);
        Util.log("smartflow" + this.direction, formatMessage(0, "******** Round %s Started **********", round));
        int totalClients = ClientInformationDatabase.INSTANCE.getTotalFLClients();
        while (context.completedClients.size() < totalClients) {
            try {
                phase1(context, phase1TotalTime);
                phase2(context, phase2TotalTime);
            } catch (Exception e) {
                String trace = e.getMessage() + "; " + Arrays.toString(Arrays.stream(e.getStackTrace()).toArray());
                Util.log("smartflow" + this.direction, formatMessage(0, "ERROR: %s ", trace));
            }
        }
        context.clear();
        future = null;
        Util.log("overhead", formatMessage(0, "%s,%s,phase1,%s", direction, round.get(), phase1TotalTime.get()));
        Util.log("overhead", formatMessage(0, "%s,%s,phase2,%s", direction, round.get(), phase2TotalTime.get()));
        Util.log("smartflow" + this.direction, formatMessage(0, "******** Round %s Completed ********\n", round.getAndIncrement()));
        Util.flushWriters();
    }


    abstract protected void phase1(ProcessingContext context, AtomicLong phase1Total) throws InterruptedException;

    protected void phase2(ProcessingContext context, AtomicLong phase2Total) {
        if (!context.needPhase2Processing.isEmpty() && (future == null || future.isDone())) {
            future = phase2Executor.schedule(() -> updateClientsStats(context, phase2Total), Util.POLL_FREQ, TimeUnit.SECONDS);
        }
    }

    protected void updateClientsStats(ProcessingContext context, AtomicLong phase2TotalTime) {
        StringBuilder phase2Logger = new StringBuilder(formatMessage(0, "Phase 2:"));
        long tik = System.currentTimeMillis();
        List<FLHost> needsFurtherProcessing = new LinkedList<>();
        FLHost client;
        while ((client = context.needPhase2Processing.poll()) != null) {
            StringBuilder clientLogger = new StringBuilder(formatMessage(1, "- Client %s: ", client.getFlClientCID()));
            try {
                long assignedRate = Math.max(client.networkStats.getLastPositiveRate(this.direction), (long) 1e6);
                long dataRemain = DATA_SIZE - client.networkStats.getRoundExchangedData(this.direction);
                int remainingTime = (int) (dataRemain / assignedRate);
                if (dataRemain <= 5e5 || remainingTime < Util.POLL_FREQ / 2) {
                    if (!client.clearPath()) {
                        clientLogger.append(formatMessage(2, "Warn: Client has no Path!"));
                    }
                    context.completedClients.add(client);
                    client.networkStats.resetExchangedData(direction);
                } else {
                    needsFurtherProcessing.add(client);
                }
                clientLogger.append(formatMessage(2, "Art Rate: %sMbps, ", bitToMbit(context.assumedRates.get(client))))
                        .append(formatMessage(2, "Real Rate: %sMbps, ", bitToMbit(assignedRate)))
                        .append(formatMessage(2, "Real Rem Data: %sMbits, ", bitToMbit(dataRemain)))
                        .append(formatMessage(2, "Real Rem Time: %ss", dataRemain / assignedRate));
            } catch (Exception e) {
                String trace = e.getMessage() + "; " + Arrays.toString(Arrays.stream(e.getStackTrace()).toArray());
                clientLogger.append(formatMessage(2, "ERROR: %s ", trace));
            }
            phase2Logger.append(clientLogger);
        }
        phase2Logger.append(formatMessage(1, "******* Completed %s Clients *******", context.completedClients.size()));
        Util.log("smartflow" + this.direction, phase2Logger.toString());
        context.needPhase1Processing.addAll(needsFurtherProcessing);
        if (context.completedClients.size() == 5) {
            context.needPhase1Processing.offer(Util.POISON_CLIENT);
        }
        phase2TotalTime.addAndGet(System.currentTimeMillis() - tik);
        Util.flushWriters();
    }

    protected void updateTimeAndRate(ProcessingContext context, Set<FLHost> affectedClients, StringBuilder internalLogger) {
        for (FLHost affectedClient : affectedClients) {
            if (affectedClient.getCurrentPath() != null) {
                long updatedFairShare = affectedClient.getCurrentPath().getCurrentFairShare();
                long dataRemain = context.dataRemaining.getOrDefault(affectedClient, 0L);
                int updatedCompletionTime = (int) Math.round(1.0 * dataRemain / updatedFairShare);
                context.completionTimes.put(affectedClient, updatedCompletionTime);
                context.assumedRates.put(affectedClient, updatedFairShare);
            } else {
                internalLogger.append(formatMessage(2, "Client %s has no current path!", affectedClient.getFlClientCID()));
            }
        }
    }
}
