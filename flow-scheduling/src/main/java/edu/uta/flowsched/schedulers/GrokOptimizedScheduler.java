package edu.uta.flowsched.schedulers;

import com.google.ortools.linearsolver.MPConstraint;
import com.google.ortools.linearsolver.MPSolver;
import com.google.ortools.linearsolver.MPVariable;
import edu.uta.flowsched.*;
import org.onosproject.net.Link;

import java.util.*;

public class GrokOptimizedScheduler extends GreedyFlowScheduler {
    private static final GreedyFlowScheduler S2C_INSTANCE = new GrokOptimizedScheduler(FlowDirection.S2C);
    private static final GreedyFlowScheduler C2S_INSTANCE = new GrokOptimizedScheduler(FlowDirection.C2S);
    private final MPSolver solver;
    private Set<MyLink> allLinks;
    private Map<String, Double> adjustedRTTMap; // Precomputed adjustedEffectiveRTT per path


    public static GreedyFlowScheduler getInstance(FlowDirection direction) {
        return direction.equals(FlowDirection.S2C) ? S2C_INSTANCE : C2S_INSTANCE;
    }

    protected GrokOptimizedScheduler(FlowDirection direction) {
        super(direction);
        solver = MPSolver.createSolver("SCIP");
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        solver.setNumThreads(availableProcessors / 2);
//        solver.setTimeLimit(500);
    }

    public Map<FLHost, MyPath> optimizePaths(Map<FLHost, Set<MyPath>> clientsPaths, StringBuilder internalLogger) {
        double epsilon = 1;
        double lb = 0.0;
        double ub = 50; // Adjust based on expected max score if known
        int numClients = clientsPaths.keySet().size();
        this.allLinks = new HashSet<>();
        this.adjustedRTTMap = new HashMap<>();
        precomputePathProperties(clientsPaths);
        Map<FLHost, MyPath> bestAssignment = null;
        while (ub - lb > epsilon) {
            long tik = System.currentTimeMillis();
            internalLogger.append(String.format("\t\t Upper: %s, Lower: %s\n", ub, lb));
            double s = (lb + ub) / 2;
            Map<FLHost, MyPath> assignment = checkFeasibility(clientsPaths, numClients, s);
            if (assignment != null) {
                internalLogger.append(String.format("\t\t Found solution at S: %s\n",s));
                lb = s;
                bestAssignment = assignment;
            } else {
                ub = s;
            }
            internalLogger.append(String.format("\t\t Time in Optimization Internal Loop %s\n", System.currentTimeMillis() - tik));
        }
        return bestAssignment;
    }

    private void precomputePathProperties(Map<FLHost, Set<MyPath>> clientPaths) {
        for (Set<MyPath> paths : clientPaths.values()) {
            for (MyPath path : paths) {
                double p = path.getPacketLossProbability();
                double rtt = path.getEffectiveRTT();
                adjustedRTTMap.put(path.id(), rtt * Math.sqrt(p + 1e-2));
                path.linksNoEdge().forEach(link -> allLinks.add((MyLink) link));
            }
        }
    }

    private Map<FLHost, MyPath> checkFeasibility(Map<FLHost, Set<MyPath>> clientPaths, int numClients, double s) {
        solver.clear();
        // Variables: x[c][p] = 1 if path p is selected for FLHost c
        Map<String, Map<String, MPVariable>> xVars = new HashMap<>();
        for (FLHost client : clientPaths.keySet()) {
            xVars.put(client.getFlClientID(), new HashMap<>());
            for (MyPath path: clientPaths.get(client)) {
                xVars.get(client.getFlClientID()).put(path.id(), solver.makeBoolVar("x_" + client.getFlClientID() + "_" + path.id()));
            }
        }

        // Variables: active_flows[l] for each link
        Map<String , MPVariable> activeFlows = new HashMap<>();
        for (MyLink link : allLinks) {
            activeFlows.put(link.id(), solver.makeIntVar(0, numClients, "flows_" + link.id()));
        }

        // Constraint: each client selects exactly one path
        for (FLHost client : clientPaths.keySet()) {
            MPConstraint constraint = solver.makeConstraint(1, 1, "one_path_client_" + client.getFlClientID());
            for (MyPath path : clientPaths.get(client)) {
                constraint.setCoefficient(xVars.get(client.getFlClientID()).get(path.id()), 1);
            }
        }

        // Constraint: Define active_flows[l]
        for (MyLink link : allLinks) {
            MPConstraint constraint = solver.makeConstraint(0, 0, "flow_count_link_" + link.id());
            constraint.setCoefficient(activeFlows.get(link.id()), -1);
            for (FLHost client : clientPaths.keySet()) {
                for (MyPath path : clientPaths.get(client)) {
                    if (path.linksNoEdge().contains(link)) {
                        constraint.setCoefficient(xVars.get(client.getFlClientID()).get(path.id()), 1);
                    }
                }
            }
        }

        // Constraint: For selected paths, ensure bottleneck >= s * adjustedRTT
        for (FLHost client : clientPaths.keySet()) {
            Set<MyPath> paths = clientPaths.get(client);
            for (MyPath path: paths) {
                double rP = adjustedRTTMap.get(path.id());
                for (Link l : path.linksNoEdge()) {
                    double capacity = Util.bitToMbit(((MyLink)l).getEstimatedFreeCapacity());
                    double rhs = capacity / (s * rP);
                    MPConstraint ct = solver.makeConstraint(-Double.POSITIVE_INFINITY, rhs + numClients,
                            "score_" + client.getFlClientID() + "_" + path.id() + "_" + ((MyLink) l).id());
                    ct.setCoefficient(activeFlows.get(((MyLink) l).id()), 1);
                    ct.setCoefficient(xVars.get(client.getFlClientID()).get(path.id()), numClients);
                }
            }
        }

        // No objective needed; we just check feasibility
        MPSolver.ResultStatus result = solver.solve();
        if (result != MPSolver.ResultStatus.OPTIMAL && result != MPSolver.ResultStatus.FEASIBLE) {
            return null;
        }

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
    protected void phase1(StringBuilder internalLogger) {
        Map<FLHost, Set<MyPath>> clientsPaths = new HashMap<>();
        FLHost client;
        while ((client = needPhase1Processing.poll()) != null) {
            if (!clientAlmostDone(client) && Util.getAgeInSeconds(client.getLastPathChange()) >= Util.POLL_FREQ * 2L) {
                Set<MyPath> paths = new HashSet<>(clientPaths.get(client));
                clientsPaths.put(client, paths);
            }
        }

        long tik = System.currentTimeMillis();
        Map<FLHost, MyPath> result = optimizePaths(clientsPaths, internalLogger);
        internalLogger.append(String.format("\t Took %s milliseconds to find optimized paths \n", System.currentTimeMillis() - tik));
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
