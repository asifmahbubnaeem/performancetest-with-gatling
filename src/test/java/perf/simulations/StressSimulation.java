package perf.simulations;

import io.gatling.javaapi.core.Simulation;
import perf.config.TestConfig;
import perf.workflows.UserWorkflows;

import static io.gatling.javaapi.core.CoreDsl.*;

/**
 * STRESS: staircase of increasing arrival rates until the system degrades.
 * No SLO assertions here on purpose — the goal is to FIND the knee point,
 * not to pass. Correlate each step with Grafana (CPU, DB connections, GC).
 */
public class StressSimulation extends Simulation {
    {
        setUp(
            UserWorkflows.randomUserJourney().injectOpen(
                incrementUsersPerSec(TestConfig.STRESS_STEP_RPS)
                    .times(TestConfig.STRESS_STEPS)
                    .eachLevelLasting(TestConfig.STRESS_STEP_SECS)
                    .separatedByRampsLasting(15)
                    .startingFrom(TestConfig.STRESS_START_RPS)
            )
        )
        .protocols(UserWorkflows.HTTP_PROTOCOL);
    }
}
