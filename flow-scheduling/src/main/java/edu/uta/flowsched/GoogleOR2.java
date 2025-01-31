package edu.uta.flowsched;

import com.google.ortools.linearsolver.MPConstraint;
import com.google.ortools.linearsolver.MPSolver;
import com.google.ortools.linearsolver.MPSolver.ResultStatus;
import com.google.ortools.linearsolver.MPVariable;
import org.onosproject.net.Link;

import java.util.*;

import static edu.uta.flowsched.Util.bitToMbit;

public class GoogleOR2 {
    private static final long DATA_SIZE = 138_400_000;
    private static final double EPS = 1e-3;

    /**
     * Solve the "minimize max completion time" problem by
     * binary searching on the per-flow throughput M (in MB/s).
     * <p>
     * If we can assign each client to exactly one path so that
     * leftover capacity is shared fairly with existing flows,
     * i.e. leftoverCapacityMBps / (numActiveFlows + newFlows) >= M,
     * then it's feasible. Otherwise, not feasible.
     *
     * @param clientMyPathsMap map: FLHost -> candidate paths
     * @return a map: client -> chosen path
     */
    public static Map<FLHost, MyPath> solveOptimalAssignment(
            Map<FLHost, List<MyPath>> clientMyPathsMap) {
        Map<FLHost, MyPath> bestAssignment = null;
        try {
            // Collect all distinct links
            Set<Link> allMyLinks = new HashSet<>();
            for (List<MyPath> paths : clientMyPathsMap.values()) {
                for (MyPath p : paths) {
                    allMyLinks.addAll(p.linksNoEdge());
                }
            }

            // 1) Determine an upper bound on M by looking at max freeCapacity among links
            //    Because if we have only 1 new flow on that link, the best it can get is linkCapacityMBps.
            double maxMyLinkCapMBps = 0.0;
            for (Link link : allMyLinks) {
                double linkCapMBps = ((MyLink) link).getEstimatedFreeCapacity(); // convert Mbps -> MB/s
                if (linkCapMBps > maxMyLinkCapMBps) {
                    maxMyLinkCapMBps = linkCapMBps;
                }
            }

            double left = 1e-9;          // minimal guess
            double right = maxMyLinkCapMBps;
            double bestFeasibleM = 0.0;
            int iterationCount = 0;

            Util.log("debug_or", "===== Starting Binary Search on M (MB/s) =====");
            Util.log("debug_or", String.format("Initial range: [%s, %s]\n", bitToMbit(left), bitToMbit(right)));

            // 2) Binary search
            while ((right - left) > EPS) {
                double mid = (left + right) * 0.5;

                Util.log("debug_or", String.format("\nIteration #%d: Testing M = %s Mb/s\n", iterationCount, bitToMbit(mid)));

                Map<FLHost, MyPath> candidateSol = checkFeasibility(clientMyPathsMap, mid);
                if (candidateSol != null) {
                    // Feasible => push M higher
                    Util.log("debug_or", String.format("  --> FEASIBLE at M = %s. Updating left to %s.\n", bitToMbit(mid), bitToMbit(mid)));
                    bestFeasibleM = mid;
                    bestAssignment = candidateSol;
                    left = mid;
                } else {
                    // Infeasible => reduce M
                    Util.log("debug_or", String.format("  --> INFEASIBLE at M = %sf. Updating right to %sf.\n", bitToMbit(mid), bitToMbit(mid)));
                    right = mid;
                }
            }

            if (bestAssignment == null) {
                Util.log("debug_or", "***************No feasible assignment found for M in [1e-9, " + maxMyLinkCapMBps + "]*******************");
                return new HashMap<>();
            }

            // The minimal completion time T* = Dmb / bestFeasibleM
            double minimalCompletionTimeSec = DATA_SIZE / bestFeasibleM;
            Util.log("debug_or", "Max feasible throughput M* = " + bestFeasibleM + " MB/s");
            Util.log("debug_or", "=> Minimal completion time = " + minimalCompletionTimeSec + " seconds");

            return bestAssignment;
        } catch (Exception e) {
            Util.log("debug_or", e.getMessage() + " ...... " + Arrays.toString(e.getStackTrace()));
        }
        return null;
    }


    /**
     * checkFeasibility:
     * Try to assign each client to exactly 1 path so that for each link L,
     * leftoverCapacityMBps / (existingFlows + newFlows) >= M.
     * <p>
     * Equivalently:
     * newFlows <= floor(linkFreeCapMBps / M) - existingFlows.
     * <p>
     * We form an ILP: sum_{(c,p): L in p} x_{c,p} <= maxNewFlows(L).
     *
     * @param M guessed throughput in MB/s
     * @return a feasible assignment (client->path) or null if infeasible
     */
    private static Map<FLHost, MyPath> checkFeasibility(
            Map<FLHost, List<MyPath>> clientMyPathsMap,
            double M) {

        try {
            // Collect all distinct links
            Set<Link> allMyLinks = new HashSet<>();
            for (List<MyPath> paths : clientMyPathsMap.values()) {
                for (MyPath p : paths) {
                    allMyLinks.addAll(p.linksNoEdge());
                }
            }

            // For each link, compute maxNewFlows = floor( freeCapMBps / M ) - existingFlows
            Map<Link, Integer> linkFlowLimits = new HashMap<>();
            for (Link link : allMyLinks) {
                double linkFreeMBps = ((MyLink) link).getEstimatedFreeCapacity(); // Mbps -> MB/s
                int maxFlows = (int) Math.floor(linkFreeMBps / M);
                int maxNewFlows = maxFlows - ((MyLink) link).getActiveFlows();

                // If maxNewFlows < 0 => infeasible at this M
                // Because even the existing flows alone saturate or exceed the link.
                if (maxNewFlows < 0) {
                    return null;
                }
                linkFlowLimits.put(link, maxNewFlows);
            }

            // Build the MIP model in OR-Tools
            MPSolver solver = new MPSolver("FeasCheck", MPSolver.OptimizationProblemType.CBC_MIXED_INTEGER_PROGRAMMING);

            // Create binary variables x_{c,p} = 1 if client c uses path p
            Map<FLHost, Map<MyPath, MPVariable>> xVars = new HashMap<>();
            for (FLHost c : clientMyPathsMap.keySet()) {
                Map<MyPath, MPVariable> cpMap = new HashMap<>();
                xVars.put(c, cpMap);

                for (MyPath p : clientMyPathsMap.get(c)) {
                    MPVariable var = solver.makeIntVar(0.0, 1.0,
                            "x_" + c.getFlClientCID() + "_" + p.hashCode());
                    cpMap.put(p, var);
                }
            }

            // (1) Each client picks exactly 1 path => sum_p x_{c,p} = 1
            for (FLHost c : clientMyPathsMap.keySet()) {
                MPConstraint cst = solver.makeConstraint(1.0, 1.0, "client_" + c.getFlClientCID() + "_exact_one");

                for (MyPath p : clientMyPathsMap.get(c)) {
                    cst.setCoefficient(xVars.get(c).get(p), 1.0);
                }
            }

            // (2) For each link L, sum_{(c,p): L in p} x_{c,p} <= linkFlowLimits.get(L)
            for (Link link : linkFlowLimits.keySet()) {
                int capacity = linkFlowLimits.get(link);
                MPConstraint linkCst =
                        solver.makeConstraint(0.0, capacity, "link_" + link.toString() + "_cap");

                // Sum of x_{c,p} for all p that include link
                for (Map.Entry<FLHost, Map<MyPath, MPVariable>> eC : xVars.entrySet()) {
                    for (Map.Entry<MyPath, MPVariable> eP : eC.getValue().entrySet()) {
                        MyPath p = eP.getKey();
                        MPVariable var = eP.getValue();

                        if (p.linksNoEdge().contains(link)) {
                            linkCst.setCoefficient(var, 1.0);
                        }
                    }
                }
            }

            // Just a feasibility check, no real objective
            solver.objective().setMinimization();
            solver.setTimeLimit(100);
            // Solve
            ResultStatus status = solver.solve();
            if (status == ResultStatus.OPTIMAL || status == ResultStatus.FEASIBLE) {
                // Build solution
                Map<FLHost, MyPath> assignment = new HashMap<>();
                for (FLHost c : clientMyPathsMap.keySet()) {
                    for (MyPath p : clientMyPathsMap.get(c)) {
                        if (xVars.get(c).get(p).solutionValue() > 0.5) {
                            assignment.put(c, p);
                            break;
                        }
                    }
                }
                return assignment;
            } else {
                return null;
            }

        } catch (Exception e) {
            Util.log("debug_or", e.getMessage() + "......" + Arrays.toString(e.getStackTrace()));
            return null;
        }
    }
}
