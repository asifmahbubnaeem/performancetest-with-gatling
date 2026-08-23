package perf.simulations;

import io.gatling.javaapi.core.Simulation;
import perf.config.TestConfig;
import perf.workflows.UserWorkflows;

import static io.gatling.javaapi.core.CoreDsl.*;

/**
 * STRESS: staircase of increasing arrival rates until the system degrades.
 * No SLO assertions here on purpose — the goal is to FIND the knee point,
 * not to pass. Correlate each step with Grafana (CPU, DB connections, GC).
 *
 * Paced login warm-up first (see LoadSimulation) so the staircase itself
 * isn't muddied by login-rate-limiter noise from the initial user pool
 * draw — the knee point you're hunting for should come from the app, not
 * from the login bucket.
 */
public class StressSimulation extends Simulation {
    {
        setUp(
            UserWorkflows.warmupLogins()
                .injectOpen(rampUsers(TestConfig.TOTAL_USERS).during(TestConfig.WARMUP_SECONDS))
                .andThen(
                    UserWorkflows.randomUserJourney().injectOpen(
                        incrementUsersPerSec(TestConfig.STRESS_STEP_RPS)
                            .times(TestConfig.STRESS_STEPS)
                            .eachLevelLasting(TestConfig.STRESS_STEP_SECS)
                            .separatedByRampsLasting(15)
                            .startingFrom(TestConfig.STRESS_START_RPS)
                    )
                )
        )
        .protocols(UserWorkflows.HTTP_PROTOCOL);
    }
}
