package edu.uta.flowsched;

import com.google.ortools.sat.*;
import org.onosproject.net.Link;
import org.onosproject.net.Path;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

public class GoogleORPathFinder {
    // Constants
    private final Map<Path, Link> allDistinctPaths;
    private final Map<FLHost, List<MyPath>> serverToClientPaths;
    private final Set<Link> allDistinctLinks;

    private static final int PENALTY_WEIGHT = 1000; // Penalty weight for capacity violations

    public GoogleORPathFinder(Map<FLHost, List<MyPath>> serverToClientPaths) {
        this.serverToClientPaths = serverToClientPaths;
        Util.log("google_or" ,String.format("Paths no. before filtering %s", serverToClientPaths.values().stream().mapToLong(List::size).sum()));

        this.allDistinctPaths = serverToClientPaths.values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toMap(
                        path -> path,
                        path -> path.links().get(0),
                        (existing, replacement) -> existing
                ));
        Util.log("google_or" ,String.format("Paths no. After filtering %s", allDistinctPaths.size()));

        this.allDistinctLinks = allDistinctPaths.keySet().stream()
                .flatMap(path -> path.links().stream())
                .collect(Collectors.toSet());
    }

    public Map<FLHost, MyPath> findOptimalServerToClientsPath() {
        // Load OR-Tools library
        CpModel model = new CpModel();

        // Map hosts to indices
        List<FLHost> clients = new ArrayList<>(serverToClientPaths.keySet());
        int numClients = clients.size();

        int numLinks = allDistinctLinks.size();

        // Variables
        IntVar[][] x = new IntVar[numClients][]; // Assignment variables
        IntVar[] y = new IntVar[numLinks]; // Total data assigned to each link
        IntVar[] s = new IntVar[numLinks]; // Slack variables for capacity violations

        // Initialize y and s variables (link capacities and slack)
        for (int l = 0; l < numLinks; l++) {
            y[l] = model.newIntVar(0, Integer.MAX_VALUE, "y_" + l);
            s[l] = model.newIntVar(0, Integer.MAX_VALUE, "s_" + l);
        }

        // Store paths and compute communication times
        List<List<MyPath>> pathsPerClient = new ArrayList<>();
        double[][] totalTime = new double[numClients][]; // Total time per client-path

        // Set the bottleneck for each path;
        Function<MyPath, MyLink> setPathBottleneck = path -> path.links().stream()
                .filter(link -> link.type() != Link.Type.EDGE)
                .min(Comparator.comparingDouble(link -> ((MyLink) link).getEstimatedFreeCapacity()))
                .map(link -> (MyLink) link)
                .orElse((MyLink) path.links().get(0));

//        allDistinctPaths.replaceAll((path, existingBottleneck) -> setPathBottleneck.apply((MyPath) path));

        for (int c = 0; c < numClients; c++) {
            FLHost client = clients.get(c); // Array List of Clients
            List<MyPath> paths = serverToClientPaths.get(client);
            pathsPerClient.add(paths);
            int numPaths = paths.size();
            totalTime[c] = new double[numPaths];
            x[c] = new IntVar[numPaths];

            calculateComTime(model, x, totalTime, c, paths, numPaths);

            model.addEquality(LinearExpr.sum(x[c]), 1);
        }

        // Define T (the maximum total time across all clients)
        double maxTotalTime = getMaxTotalTime(totalTime);
        IntVar T = model.newIntVar(0, (int) maxTotalTime, "T");

        // Total time constraints for each client
        for (int c = 0; c < numClients; c++) {
            int numPaths = pathsPerClient.get(c).size();
            long[] times = new long[numPaths];
            for (int p = 0; p < numPaths; p++) {
                times[p] = (int) (totalTime[c][p]);
            }
            // Build the linear expression using weightedSum
            LinearExpr totalTimeExpr = LinearExpr.weightedSum(x[c], times);
            // Ensure the selected path's total time does not exceed T
            model.addLessOrEqual(totalTimeExpr, T);
        }

        // Link capacity constraints with slack variables
        int l = 0;
        for (Link link : allDistinctLinks) {
            List<IntVar> xVars = new ArrayList<>();
            for (int c = 0; c < numClients; c++) {
                int numPaths = pathsPerClient.get(c).size();
                for (int p = 0; p < numPaths; p++) {
                    MyPath path = pathsPerClient.get(c).get(p);
                    if (path.links().contains(link)) {
                        xVars.add(x[c][p]);
                    }
                }
            }
            // Convert the list to an array
            IntVar[] xVarsArray = xVars.toArray(new IntVar[0]);
            // Create an array of coefficients (dataSize for each variable)
            long[] dataSizeCoeffs = new long[xVarsArray.length];
            Arrays.fill(dataSizeCoeffs, Util.MODEL_SIZE);

            // Build the linear expression using weightedSum
            LinearExpr yExpr = LinearExpr.weightedSum(xVarsArray, dataSizeCoeffs);
            // y_l = sum over all client-paths using link l of x_{c,p} * dataSize
            model.addEquality(y[l], yExpr);

            // Get the current free capacity of the link
            long freeCapacity = ((MyLink) link).getEstimatedFreeCapacity();

            // Create a LinearExpr representing freeCapacity + s_l
            LinearExpr capacityPlusSlack = LinearExpr.sum(new LinearArgument[]{LinearExpr.constant((int) freeCapacity), s[l]});

            // Capacity constraint with slack: y_l <= freeCapacity + s_l
            model.addLessOrEqual(y[l], capacityPlusSlack);
            l++;
        }

        // Objective: minimize T + penaltyWeight * sum of slack variables
        LinearExpr totalSlackExpr = LinearExpr.sum(s);
        // Construct the objective expression
        LinearExpr objectiveExpr = LinearExpr.sum(new LinearArgument[]{T, LinearExpr.term(totalSlackExpr, PENALTY_WEIGHT)});

        // Minimize the objective
        model.minimize(objectiveExpr);

        // Create solver and solve
        CpSolver solver = new CpSolver();
        CpSolverStatus status = solver.solve(model);

        if (status == CpSolverStatus.OPTIMAL || status == CpSolverStatus.FEASIBLE) {
            Map<FLHost, MyPath> result = new HashMap<>();
            for (int c = 0; c < numClients; c++) {
                int numPaths = pathsPerClient.get(c).size();
                for (int p = 0; p < numPaths; p++) {
                    if (solver.value(x[c][p]) == 1) {
                        result.put(clients.get(c), pathsPerClient.get(c).get(p));
                        break;
                    }
                }
            }
            // Optionally, print the total slack used
            Util.log("google_or" ,"Total capacity violation (slack): " + solver.value(totalSlackExpr));
            return result;
        } else {
            // Should not reach here as the model is always feasible
            Util.log("google_or" ,"No feasible solution found.");
            return null;
        }
    }

    private void calculateComTime(CpModel model, IntVar[][] x, double[][] totalTime, int c, List<MyPath> paths, int numPaths) {
        for (int p = 0; p < numPaths; p++) {
            MyPath path = paths.get(p);
            totalTime[c][p] = Math.ceil((double) Util.MODEL_SIZE / path.getBottleneckLink().getEstimatedFreeCapacity());
            // Decision variable for this client-path
            x[c][p] = model.newBoolVar("x_" + c + "_" + p);
        }
    }


    private double getMaxTotalTime(double[][] totalTime) {
        // Find the maximum total time across all client-path combinations
        double maxTime = 0.0;
        for (double[] times : totalTime) {
            for (double time : times) {
                if (time > maxTime) {
                    maxTime = time;
                }
            }
        }
        return maxTime;
    }
}