package edu.uta.flowsched.schedulers.drl;

import org.deeplearning4j.rl4j.learning.sync.qlearning.QLearning;
import org.deeplearning4j.rl4j.network.ac.ActorCriticFactoryStdDense;
import org.deeplearning4j.rl4j.policy.ACPolicy;
import org.deeplearning4j.rl4j.space.Box;
import org.deeplearning4j.rl4j.space.DiscreteSpace;
import org.nd4j.linalg.learning.config.Adam;
import java.util.logging.Logger;

public class DrlAgent {
    private static final Logger log = Logger.getLogger(DrlAgent.class.getName());

    // Configuration for the A2C model
    public static final QLearning.QLConfiguration A2C_CONFIG =
            new QLearning.QLConfiguration(
                    123,    // Seed
                    200,    // Max epoch steps
                    150000, // Max steps
                    150000, // Max size of experience replay
                    32,     // Batch size
                    500,    // Target network update frequency
                    10,     // Number of steps to wait between updates
                    0.01,   // Error clamp
                    0.99,   // Gamma (discount factor)
                    1.0,    // Reward scaling
                    0.1f,   // Epsilon anneal start
                    1000,   // Epsilon anneal end
                    true    // Double-DQN
            );

    // The policy model
    private static ACPolicy<Box> policy;

    // Factory for the actor-critic network
    private static final ActorCriticFactoryStdDense.Configuration A2C_NET_CONFIG =
            ActorCriticFactoryStdDense.Configuration.builder()
                    .numHiddenNodes(128)
                    .numLayers(2)
                    .updater(new Adam(0.001))
                    .build();

    // Private constructor to prevent instantiation
    private DrlAgent() {
    }

    /**
     * Initializes and returns the policy. If the policy is already created, it returns the existing one.
     * @param stateSpaceSize The size of the state space.
     * @param actionSpaceSize The size of the action space.
     * @return The initialized ACPolicy.
     */
    public static synchronized ACPolicy<Box> getInstance(int stateSpaceSize, int actionSpaceSize) {
        if (policy == null) {
            log.info("Initializing DRL Agent...");
            var netFactory = new ActorCriticFactoryStdDense(A2C_NET_CONFIG);
            var model = netFactory.buildActorCritic(stateSpaceSize, actionSpaceSize);
            policy = new ACPolicy<>(model, null);
        }
        return policy;
    }
}