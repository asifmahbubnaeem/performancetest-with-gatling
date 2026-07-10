package perf.simulations;

import io.gatling.javaapi.core.Simulation;
import perf.config.TestConfig;
import perf.workflows.UserWorkflows;

import static io.gatling.javaapi.core.CoreDsl.*;

/**
 * SOAK: moderate load for hours. Run with e.g. -DsteadySeconds=14400 (4h).
 * Hunts memory/connection leaks, WAL & disk growth, autovacuum debt.
 * Compare first-hour vs last-hour percentiles — drift means a leak.
 */
public class SoakSimulation extends Simulation {
    {
        setUp(
            UserWorkflows.randomUserJourney().injectOpen(
                rampUsersPerSec(1).to(TestConfig.TARGET_RPS).during(TestConfig.RAMP_SECONDS),
                constantUsersPerSec(TestConfig.TARGET_RPS).during(TestConfig.STEADY_SECONDS)
            )
        )
        .protocols(UserWorkflows.HTTP_PROTOCOL)
        .assertions(
            global().failedRequests().percent().lt(TestConfig.SLO_MAX_ERR_PCT)
        );
    }
}
