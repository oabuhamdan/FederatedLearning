package edu.uta.flowsched.schedulers;

import com.google.ortools.sat.*;
import edu.uta.flowsched.*;
import org.onosproject.net.Link;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static edu.uta.flowsched.Util.LOG_TIME_FORMATTER;

public class OptimizedScheduler extends GreedyFlowScheduler {
    private static final GreedyFlowScheduler S2C_INSTANCE = new OptimizedScheduler(FlowDirection.S2C);
    private static final GreedyFlowScheduler C2S_INSTANCE = new OptimizedScheduler(FlowDirection.C2S);

    public static GreedyFlowScheduler getInstance(FlowDirection direction) {
        return direction.equals(FlowDirection.S2C) ? S2C_INSTANCE : C2S_INSTANCE;
    }

    protected OptimizedScheduler(FlowDirection direction) {
        super(direction);
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

    public Map<FLHost, MyPath> optimizePaths(Map<FLHost, Set<MyPath>> clientsPaths, Map<FLHost, Long> dataRemaining, StringBuilder internalLogger) {
        CpModel model = new CpModel();

        Set<MyLink> allLinks = new HashSet<>();
        Map<String, Double> adjustedRTTMap = new HashMap<>();

        int numClients = clientsPaths.keySet().size();
        precomputePathProperties(clientsPaths, allLinks, adjustedRTTMap);

        // Variables: x[c][p] = 1 if path p is selected for FLHost c
        Map<String, Map<String, Literal>> xVars = new HashMap<>();
        for (FLHost client : clientsPaths.keySet()) {
            Map<String, Literal> map = new HashMap<>();
            for (MyPath path : clientsPaths.get(client)) {
                map.put(path.id(), model.newBoolVar("x_" + client.getFlClientID() + "_" + path.id()));
            }
            xVars.put(client.getFlClientID(), map);
        }

        // Variables: active_flows[l] for each link
        Map<String, IntVar> activeFlows = new HashMap<>();
        for (MyLink link : allLinks) {
            activeFlows.put(link.id(), model.newIntVar(0, numClients, "flows_" + link.id()));
        }

        // Constraint: each client selects exactly one path
        for (FLHost client : clientsPaths.keySet()) {
            List<Literal> clientVars = new ArrayList<>();
            for (MyPath path : clientsPaths.get(client)) {
                clientVars.add(xVars.get(client.getFlClientID()).get(path.id()));
            }
            model.addExactlyOne(clientVars);
        }

        // Constraint: Define active_flows[l]
        for (MyLink link : allLinks) {
            List<Literal> flowVars = new ArrayList<>();
            for (FLHost client : clientsPaths.keySet()) {
                for (MyPath path : clientsPaths.get(client)) {
                    if (path.linksNoEdge().contains(link)) {
                        flowVars.add(xVars.get(client.getFlClientID()).get(path.id()));
                    }
                }
            }

            // Sum of all path variables using this link equals the active flows on this link
            model.addEquality(LinearExpr.sum(flowVars.toArray(new Literal[0])), activeFlows.get(link.id()));
        }

        // Define T (bottleneck) as an integer variable with a large upper bound
        // Scale floating point values for CP-SAT's integer-only approach
        long scaleFactor = 1000000; // For precision in integer representation
        IntVar T = model.newIntVar(0, Long.MAX_VALUE / scaleFactor, "T");

        // Constraint: For selected paths, ensure bottleneck >= s * adjustedRTT
        for (FLHost client : clientsPaths.keySet()) {
            double data = Util.bitToMbit(dataRemaining.get(client));
            for (MyPath path : clientsPaths.get(client)) {
                Literal xVar = xVars.get(client.getFlClientID()).get(path.id());
                double adjRTT = adjustedRTTMap.getOrDefault(path.id(), 1.0);
                for (Link l : path.linksNoEdge()) {
                    MyLink link = (MyLink) l;
                    double capacity = Util.bitToMbit(link.getEstimatedFreeCapacity());
                    double capEff = capacity / adjRTT;

                    // Scale for integer conversion
                    long scaledCapEff = (long)(capEff * scaleFactor / data);

                    // Original constraint was:
                    // activeFlows[link] + T * (-capEff/data) + xVar * numClients <= numClients
                    // Rearranging: T <= (numClients - activeFlows[link]) * (data/capEff) + numClients * (1-xVar) * (data/capEff)
                    // We'll implement this using CP-SAT's conditional constraints

                    // First part: When xVar = 1, T must respect the capacity constraint
                    // We'll use a boolean literal to represent this condition
                    Literal notXVar = model.newBoolVar("not_" + xVar.toString());
                    model.addEquality(LinearExpr.sum(new Literal[]{xVar, notXVar}), 1);  // xVar + notXVar = 1

                    // T <= (numClients - activeFlows[link]) * (data/capEff) when xVar = 1
                    // Otherwise, constraint is satisfied via a large constant
                    model.addLessOrEqual(
                            LinearExpr.newBuilder()
                                    .addTerm(T, 1)
                                    .addTerm(activeFlows.get(link.id()), scaledCapEff)
                                    .build(),
                            LinearExpr.newBuilder()
                                    .addTerm(notXVar, Long.MAX_VALUE / (2 * scaleFactor))  // Big-M when xVar = 0
                                    .add(numClients * scaledCapEff)  // numClients * scaledCapEff
                                    .build()
                    );
                }
            }
        }

        long tik = System.currentTimeMillis();

        // Set objective: minimize T
        model.minimize(T);

        // Create solver and set parameters
        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(1);
        solver.getParameters().setNumWorkers(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
        // Solve the model

        CpSolverStatus status = solver.solve(model);

        internalLogger.append(String.format("\t Took %s milliseconds to find optimized paths \n", System.currentTimeMillis() - tik));

        if (status != CpSolverStatus.OPTIMAL && status != CpSolverStatus.FEASIBLE)
            return null;

        // Extract assignment
        Map<FLHost, MyPath> assignment = new HashMap<>();
        for (FLHost client : clientsPaths.keySet()) {
            for (MyPath path : clientsPaths.get(client)) {
                if (solver.booleanValue(xVars.get(client.getFlClientID()).get(path.id()))) {
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
