package edu.uta.flowsched.schedulers;

import com.google.ortools.linearsolver.MPConstraint;
import com.google.ortools.linearsolver.MPObjective;
import com.google.ortools.linearsolver.MPSolver;
import com.google.ortools.linearsolver.MPVariable;
import edu.uta.flowsched.*;
import org.onosproject.net.Link;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static edu.uta.flowsched.Util.LOG_TIME_FORMATTER;

public class OptimizedScheduler extends GreedyFlowScheduler {
    private static final GreedyFlowScheduler S2C_INSTANCE = new OptimizedScheduler(FlowDirection.S2C);
    private static final GreedyFlowScheduler C2S_INSTANCE = new OptimizedScheduler(FlowDirection.C2S);
    private final MPSolver solver;

    public static GreedyFlowScheduler getInstance(FlowDirection direction) {
        return direction.equals(FlowDirection.S2C) ? S2C_INSTANCE : C2S_INSTANCE;
    }

    protected OptimizedScheduler(FlowDirection direction) {
        super(direction);
        solver = MPSolver.createSolver("SCIP");
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        solver.setNumThreads(availableProcessors / 2);
    }

    private void precomputePathProperties(Map<FLHost, Set<MyPath>> clientPaths, Set<MyLink> allLinks, Map<String, Double> adjustedRTTMap) {
        for (Set<MyPath> paths : clientPaths.values()) {
            for (MyPath path : paths) {
                double p = path.getPacketLossProbability();
                double rtt = path.getEffectiveRTT();
                adjustedRTTMap.put(path.id(), rtt * Math.sqrt(p + 1e-2));
                path.linksNoEdge().forEach(link -> allLinks.add((MyLink) link));
            }
        }
    }

    public Map<FLHost, MyPath> optimizePaths(Map<FLHost, Set<MyPath>> clientsPaths, Map<FLHost, Long> dataRemaining, StringBuilder internalLogger){
        solver.clear();
        Set<MyLink> allLinks = new HashSet<>();
        Map<String, Double> adjustedRTTMap = new HashMap<>();

        int numClients = clientsPaths.keySet().size();
        precomputePathProperties(clientsPaths, allLinks, adjustedRTTMap);
        // Variables: x[c][p] = 1 if path p is selected for FLHost c
        Map<String, Map<String, MPVariable>> xVars = new HashMap<>();
        for (FLHost client : clientsPaths.keySet()) {
            Map<String, MPVariable> map = new HashMap<>();
            for (MyPath path : clientsPaths.get(client)) {
                map.put(path.id(), solver.makeBoolVar("x_" + client.getFlClientID() + "_" + path.id()));
            }
            xVars.put(client.getFlClientID(), map);
        }

        // Variables: active_flows[l] for each link
        Map<String , MPVariable> activeFlows = new HashMap<>();
        for (MyLink link : allLinks)
            activeFlows.put(link.id(), solver.makeIntVar(0, numClients, "flows_" + link.id()));

        // Constraint: each client selects exactly one path
        for (FLHost client : clientsPaths.keySet()) {
            MPConstraint one = solver.makeConstraint(1, 1, "onePath_" + client.getFlClientID());
            for (MyPath path : clientsPaths.get(client))
                one.setCoefficient( xVars.get(client.getFlClientID()).get(path.id()), 1);
        }

        // Constraint: Define active_flows[l]
        for (MyLink link : allLinks) {
            MPConstraint eq = solver.makeConstraint(0, 0, "flowCount_" + link.id());
            eq.setCoefficient(activeFlows.get(link.id()), -1);
            for (FLHost client : clientsPaths.keySet())
                for (MyPath path : clientsPaths.get(client))
                    if (path.linksNoEdge().contains(link))
                        eq.setCoefficient( xVars.get(client.getFlClientID()).get(path.id()), 1);
        }

        // Constraint: For selected paths, ensure bottleneck >= s * adjustedRTT
        MPVariable T = solver.makeNumVar(0, Double.POSITIVE_INFINITY, "T");
        for (FLHost client : clientsPaths.keySet()) {
            double data = Util.bitToMbit(dataRemaining.get(client));
            for (MyPath path : clientsPaths.get(client)) {
                MPVariable xVar = xVars.get(client.getFlClientID()).get(path.id());
                double adjRTT = adjustedRTTMap.getOrDefault(path.id(), 1.0);
                for (Link l : path.linksNoEdge()) {
                    MyLink link = (MyLink) l;
                    double capacity = Util.bitToMbit(link.getEstimatedFreeCapacity());
                    double capEff = capacity / adjRTT;

                    MPConstraint ct = solver.makeConstraint(-Double.POSITIVE_INFINITY, numClients,
                            "time_" + client.getFlClientID() + "_" + path.id() + "_" + link.id());

                    ct.setCoefficient(activeFlows.get(link.id()), 1);
                    ct.setCoefficient(T, -(capEff / data));
                    ct.setCoefficient(xVar, numClients);
                }
            }
        }
        long tik = System.currentTimeMillis();

        MPObjective obj = solver.objective();
        obj.setCoefficient(T, 1.0);
        obj.setMinimization();
        MPSolver.ResultStatus status = solver.solve();

        internalLogger.append(String.format("\t Took %s milliseconds to find optimized paths \n", System.currentTimeMillis() - tik));

        if (status != MPSolver.ResultStatus.OPTIMAL && status != MPSolver.ResultStatus.FEASIBLE)
            return null;

        // Extract assignment
        Map<FLHost, MyPath> assignment = new HashMap<>();
        for (FLHost client : clientPaths.keySet()) {
            for (MyPath path : clientPaths.get(client)) {
                if (xVars.get(client.getFlClientID()).get(path.id()).solutionValue() > 0.5) { // Binary variable threshold
                    assignment.put(client, path);
                    break;
                }
            }
        }
        return assignment;
    }

    @Override
    protected void phase1(AtomicLong phase1Total) {
        StringBuilder internalLogger = new StringBuilder(String.format("\tPhase 1 -------------%s------------- \n", LocalDateTime.now().format(LOG_TIME_FORMATTER)));
        long tik = System.currentTimeMillis();
        Map<FLHost, Set<MyPath>> clientsPaths = new HashMap<>();
        FLHost client;
        while ((client = needPhase1Processing.poll()) != null) {
            if (!clientAlmostDone(client) && Util.getAgeInSeconds(client.getLastPathChange()) >= Util.POLL_FREQ * 2L) {
                Set<MyPath> paths = new HashSet<>(clientPaths.get(client));
                clientsPaths.put(client, paths);
            }
        }

        Map<FLHost, MyPath> result = optimizePaths(clientsPaths, dataRemaining, internalLogger);
        if (result == null){
            internalLogger.append("\t No result found in this round ... Returning \n");
            return;
        }
        for (Map.Entry<FLHost, MyPath> entry : result.entrySet()) {
            StringBuilder clientLogger = new StringBuilder();

            MyPath currentPath = entry.getKey().getCurrentPath();
            boolean pathIsNull = currentPath == null;

            if (pathIsNull || !entry.getValue().equals(currentPath)) {
                entry.getKey().setLastPathChange(System.currentTimeMillis());
                clientLogger.append(String.format("\t- Client %s: \n", entry.getKey().getFlClientCID()));
                String currentPathFormat = Optional.ofNullable(currentPath).map(MyPath::format).orElse("No Path");
                String newPathFormat = entry.getValue().format();
                clientLogger.append(String.format("\t\tCurrent Path: %s\n", currentPathFormat));
                PathRulesInstaller.INSTANCE.installPathRules(entry.getKey(), entry.getValue(), false);
                Set<FLHost> affectedClients = entry.getKey().assignNewPath(entry.getValue());
                clientLogger.append(String.format("\t\tNew Path: %s\n", newPathFormat));
                // The client is among the affected clients from the addition
                updateTimeAndRate(affectedClients, internalLogger);
            }
            internalLogger.append(clientLogger);
        }
        phase1Total.addAndGet(System.currentTimeMillis() - tik);
        Util.log("greedy" + this.direction, internalLogger.toString());
    }

    @Override
    public List<FLHost> initialSort(List<FLHost> hosts) {
        // No sorting here, it doesn't matter.
        return hosts;
    }

    @Override
    protected HashMap<MyPath, Double> scorePaths(Set<MyPath> paths, boolean initial) {
        return null;
    }
}
