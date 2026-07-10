package perf.simulations;

import io.gatling.javaapi.core.Simulation;
import perf.config.TestConfig;
import perf.workflows.UserWorkflows;

import static io.gatling.javaapi.core.CoreDsl.*;

/**
 * SPIKE: steady baseline -> sudden burst -> back to baseline.
 * Exposes connection-pool exhaustion, queue buildup, and recovery behavior.
 * Watch how long p95 takes to return to baseline after the spike ends.
 */
public class SpikeSimulation extends Simulation {
    {
        setUp(
            UserWorkflows.randomUserJourney().injectOpen(
                constantUsersPerSec(TestConfig.SPIKE_BASE_RPS).during(120),
                rampUsersPerSec(TestConfig.SPIKE_BASE_RPS).to(TestConfig.SPIKE_PEAK_RPS).during(10),
                constantUsersPerSec(TestConfig.SPIKE_PEAK_RPS).during(TestConfig.SPIKE_HOLD_SECS),
                rampUsersPerSec(TestConfig.SPIKE_PEAK_RPS).to(TestConfig.SPIKE_BASE_RPS).during(10),
                constantUsersPerSec(TestConfig.SPIKE_BASE_RPS).during(180) // recovery window
            )
        )
        .protocols(UserWorkflows.HTTP_PROTOCOL);
    }
}
