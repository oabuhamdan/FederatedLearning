package edu.uta.flowsched.schedulers;

import com.google.ortools.linearsolver.MPConstraint;
import com.google.ortools.linearsolver.MPObjective;
import com.google.ortools.linearsolver.MPSolver;
import com.google.ortools.linearsolver.MPVariable;
import com.google.ortools.sat.*;
import edu.uta.flowsched.*;
import org.onosproject.net.Link;

import java.util.*;

public class GrokOptimizedScheduler extends GreedyFlowScheduler {
    private static final GreedyFlowScheduler S2C_INSTANCE = new GrokOptimizedScheduler(FlowDirection.S2C);
    private static final GreedyFlowScheduler C2S_INSTANCE = new GrokOptimizedScheduler(FlowDirection.C2S);

    public static GreedyFlowScheduler getInstance(FlowDirection direction) {
        return direction.equals(FlowDirection.S2C) ? S2C_INSTANCE : C2S_INSTANCE;
    }

    protected GrokOptimizedScheduler(FlowDirection direction) {
        super(direction);
    }

    public static Map<FLHost, MyPath> optimizePathAssignment(Map<FLHost, Set<MyPath>> clientPaths, Set<MyLink> networkLinks) {
        MPSolver solver = MPSolver.createSolver("SCIP");
        if (solver == null) {
            throw new RuntimeException("Solver not found");
        }

        // Binary variables x[i][j]: 1 if path j is selected for client i
        Map<String, Map<String, MPVariable>> x = new HashMap<>();
        for (FLHost client : clientPaths.keySet()) {
            x.put(client.getFlClientID(), new HashMap<>());
            for (MyPath path : clientPaths.get(client)) {
                x.get(client.getFlClientID()).put(path.id(), solver.makeBoolVar("x_" + client.getFlClientID() + "_" + path.id()));
            }
        }

        // Variables n[l]: number of flows on link l
        Map<String, MPVariable> n = new HashMap<>();
        for (MyLink link : networkLinks) {
            n.put(link.id(), solver.makeNumVar(0, clientPaths.keySet().size(), "n_" + link.id()));
        }

        // Constraint: each client selects exactly one path
        for (FLHost client : clientPaths.keySet()) {
            MPConstraint constraint = solver.makeConstraint(1, 1, "one_path_client_" + client.getFlClientID());
            for (MyPath path : clientPaths.get(client)) {
                constraint.setCoefficient(x.get(client.getFlClientID()).get(path.id()), 1);
            }
        }

        // Constraint: define n[l] as sum of flows using link l
        for (MyLink link : networkLinks) {
            MPConstraint constraint = solver.makeConstraint(0, 0, "flow_count_link_" + link.id());
            constraint.setCoefficient(n.get(link.id()), -1);
            for (FLHost client : clientPaths.keySet()) {
                for (MyPath path : clientPaths.get(client)) {
                    if (path.linksNoEdge().contains(link)) {
                        constraint.setCoefficient(x.get(client.getFlClientID()).get(path.id()), 1);
                    }
                }
            }
        }

        // Objective variable T to minimize
        MPVariable T = solver.makeNumVar(0, Double.POSITIVE_INFINITY, "T");

        // S2C: Minimize max congestion on selected paths
        double M = clientPaths.keySet().size() * 100; // Large M (adjust based on capacities)
        for (FLHost client : clientPaths.keySet()) {
            for (MyPath path : clientPaths.get(client)) {
                Set<Link> linksInPath = path.linksNoEdge();
                for (Link link : linksInPath) {
                    double cap_l = ((MyLink) link).getEstimatedFreeCapacity();
                    MPConstraint constraint = solver.makeConstraint(-Double.POSITIVE_INFINITY, M,
                            "s2c_congestion_client_" + client.getFlClientID() + "_path_" + path.id() + "_link_" + ((MyLink) link).id());
                    constraint.setCoefficient(n.get(((MyLink) link).id()), 1);
                    constraint.setCoefficient(T, -cap_l);
                    constraint.setCoefficient(x.get(client.getFlClientID()).get(path.id()), M);
                }
            }
        }


        // Objective: Minimize T
        MPObjective objective = solver.objective();
        objective.setMinimization();
        objective.setCoefficient(T, 1);

        // Solve
        MPSolver.ResultStatus resultStatus = solver.solve();
        if (resultStatus != MPSolver.ResultStatus.OPTIMAL && resultStatus != MPSolver.ResultStatus.FEASIBLE) {
            throw new RuntimeException("No Optimal not Feasible solution found");
        }

        // Extract solution
        Map<FLHost, MyPath> assignment = new HashMap<>();
        for (FLHost client : clientPaths.keySet()) {
            for (MyPath path : clientPaths.get(client)) {
                if (x.get(client.getFlClientID()).get(path.id()).solutionValue() > 0.5) { // Binary variable threshold
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
            if (!clientAlmostDone(client)) {
                Set<MyPath> paths = new HashSet<>(clientPaths.get(client));
                clientsPaths.put(client, paths);
            }
        }
        long tik = System.currentTimeMillis();
        Map<FLHost, MyPath> result = optimizePathAssignment(clientsPaths, LinkInformationDatabase.INSTANCE.allLinksNoEdge());
        internalLogger.append(String.format("\t Took %s milliseconds to find optimized paths \n", System.currentTimeMillis() - tik));
        for (Map.Entry<FLHost, MyPath> entry : result.entrySet()) {
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
