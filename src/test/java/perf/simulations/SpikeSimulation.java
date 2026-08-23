package perf.simulations;

import io.gatling.javaapi.core.Simulation;
import perf.config.TestConfig;
import perf.workflows.UserWorkflows;

import static io.gatling.javaapi.core.CoreDsl.*;

/**
 * SPIKE: steady baseline -> sudden burst -> back to baseline.
 * Exposes connection-pool exhaustion, queue buildup, and recovery behavior.
 * Watch how long p95 takes to return to baseline after the spike ends.
 *
 * Paced login warm-up first (see LoadSimulation) — otherwise the initial
 * baseline phase itself triggers a login-rate-limiter burst from drawing
 * the user pool cold, which would contaminate the "baseline" you're trying
 * to measure recovery against.
 */
public class SpikeSimulation extends Simulation {
    {
        setUp(
            UserWorkflows.warmupLogins()
                .injectOpen(rampUsers(TestConfig.TOTAL_USERS).during(TestConfig.WARMUP_SECONDS))
                .andThen(
                    UserWorkflows.randomUserJourney().injectOpen(
                        constantUsersPerSec(TestConfig.SPIKE_BASE_RPS).during(120),
                        rampUsersPerSec(TestConfig.SPIKE_BASE_RPS).to(TestConfig.SPIKE_PEAK_RPS).during(10),
                        constantUsersPerSec(TestConfig.SPIKE_PEAK_RPS).during(TestConfig.SPIKE_HOLD_SECS),
                        rampUsersPerSec(TestConfig.SPIKE_PEAK_RPS).to(TestConfig.SPIKE_BASE_RPS).during(10),
                        constantUsersPerSec(TestConfig.SPIKE_BASE_RPS).during(180) // recovery window
                    )
                )
        )
        .protocols(UserWorkflows.HTTP_PROTOCOL);
    }
}
