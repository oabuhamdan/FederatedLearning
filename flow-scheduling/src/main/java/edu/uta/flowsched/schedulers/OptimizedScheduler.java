package edu.uta.flowsched.schedulers;

import edu.uta.flowsched.*;

import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.IntVar;
import com.google.ortools.sat.LinearExpr;
import org.onosproject.net.Link;


import java.util.*;

public class OptimizedScheduler extends GreedyFlowScheduler {

    // Scale factor to convert fractions to integers.
    private final int SCALE = 1000;

    private static final GreedyFlowScheduler S2C_INSTANCE = new OptimizedScheduler(FlowDirection.S2C);
    private static final GreedyFlowScheduler C2S_INSTANCE = new OptimizedScheduler(FlowDirection.C2S);

    public static GreedyFlowScheduler getInstance(FlowDirection direction) {
        return direction.equals(FlowDirection.S2C) ? S2C_INSTANCE : C2S_INSTANCE;
    }

    protected OptimizedScheduler(FlowDirection direction) {
        super(direction);
    }

    /**
     * Optimize the path assignment.
     *
     * @param clientPaths A map from each client to its candidate paths.
     * @return A map from each client to its selected path.
     */
    public Map<FLHost, MyPath> optimize(Map<FLHost, Set<MyPath>> clientPaths) {
        CpModel model = new CpModel();

        // Decision variables: x[c][p] = 1 if client c selects path p.
        Map<FLHost, Map<MyPath, IntVar>> x = new HashMap<>();
        for (FLHost client : clientPaths.keySet()) {
            Map<MyPath, IntVar> pathVars = new HashMap<>();
            for (MyPath path : clientPaths.get(client)) {
                String varName = "x_" + client.getFlClientID() + "_" + path.format();
                pathVars.put(path, model.newBoolVar(varName));
            }
            x.put(client, pathVars);
            // Each client must choose exactly one path.
            Collection<IntVar> vars = pathVars.values();
            model.addEquality(LinearExpr.sum(vars.toArray(new IntVar[0])), 1);
        }

        // Gather all unique links across all paths.
        Set<Link> allLinks = new HashSet<>();
        for (Set<MyPath> paths : clientPaths.values()) {
            for (MyPath path : paths) {
                allLinks.addAll(path.linksNoEdge());
            }
        }

        // For each link, compute the weighted effective load:
        // effectiveLoad(l) = sum_{clients and paths using l} ( (scale / capacity(l)) * x[c,p] )
        // This allows a high-capacity link to have a lower weight per flow.
        Map<Link, IntVar> effectiveLoadVars = new HashMap<>();
        int maxClients = clientPaths.size(); // maximum flows per link.
        // Also store the computed upper bounds.
        Map<Link, Integer> ubMap = new HashMap<>();
        Map<Link, Integer> coeffMap = new HashMap<>();
        for (Link link : allLinks) {
            int coeff = (int) (SCALE / ((MyLink) link).getEstimatedFreeCapacity()); // assuming capacity divides scale
            coeffMap.put(link, coeff);
            int ub = coeff * maxClients;
            ubMap.put(link, ub);
            effectiveLoadVars.put(link, model.newIntVar(0, ub, "effLoad_" + ((MyLink) link).format()));
        }

        // For each link, set effectiveLoad(l) = sum_{clients and paths using l} (coeff * x)
        for (Link link : allLinks) {
            List<IntVar> terms = new ArrayList<>();
            List<Long> longCoeffs = new ArrayList<>();
            int coeff = coeffMap.get(link);
            for (FLHost client : clientPaths.keySet()) {
                for (MyPath path : clientPaths.get(client)) {
                    if (path.linksNoEdge().contains(link)) {
                        terms.add(x.get(client).get(path));
                        longCoeffs.add((long) coeff);
                    }
                }
            }
            if (!terms.isEmpty()) {
                long[] coeffsArray = longCoeffs.stream().mapToLong(l -> l).toArray();
                model.addEquality(effectiveLoadVars.get(link),
                        LinearExpr.weightedSum(terms.toArray(new IntVar[0]), coeffsArray));
            }
        }

        // Compute the maximum upper bound among all links.
        int maxUB = 0;
        for (Link link : allLinks) {
            int ub = ubMap.get(link);
            if (ub > maxUB) {
                maxUB = ub;
            }
        }
        // Introduce a variable maxEffLoad that is at least every link's effective load.
        IntVar maxEffLoad = model.newIntVar(0, maxUB, "maxEffLoad");
        for (Link link : allLinks) {
            model.addLessOrEqual(effectiveLoadVars.get(link), maxEffLoad);
        }
        // Objective: minimize the worst (maximum) effective load.
        model.minimize(maxEffLoad);

        // Solve the model.
        CpSolver solver = new CpSolver();
        CpSolverStatus status = solver.solve(model);
        Map<FLHost, MyPath> assignment = new HashMap<>();
        if (status == CpSolverStatus.OPTIMAL || status == CpSolverStatus.FEASIBLE) {
            for (FLHost client : clientPaths.keySet()) {
                for (MyPath path : clientPaths.get(client)) {
                    if (solver.value(x.get(client).get(path)) == 1) {
                        assignment.put(client, path);
                        break;
                    }
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
            if (!clientAlmostDone(client)) {
                Set<MyPath> paths = new HashSet<>(clientPaths.get(client));
                clientsPaths.put(client, paths);
            }
        }
        long tik = System.currentTimeMillis();
        Map<FLHost, MyPath> result = optimize(clientsPaths);
        internalLogger.append(String.format("\t Took %s milliseconds to find optimized paths \n", System.currentTimeMillis() - tik));
        for (Map.Entry<FLHost, MyPath> entry: result.entrySet()) {
            StringBuilder clientLogger = new StringBuilder(String.format("\t- Client %s: \n", entry.getKey().getFlClientCID()));

            MyPath currentPath = entry.getKey().getCurrentPath();
            boolean pathIsNull = currentPath == null;

            if (pathIsNull || !entry.getValue().equals(currentPath)) {
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
