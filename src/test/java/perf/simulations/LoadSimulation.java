package perf.simulations;

import io.gatling.javaapi.core.Simulation;
import perf.config.TestConfig;
import perf.workflows.UserWorkflows;

import static io.gatling.javaapi.core.CoreDsl.*;

/**
 * LOAD: ramp to expected arrival rate, hold steady. Open workload model.
 * Pass/fail is decided by the SLO assertions (CI-friendly).
 */
public class LoadSimulation extends Simulation {
    {
        setUp(
            UserWorkflows.randomUserJourney().injectOpen(
                rampUsersPerSec(1).to(TestConfig.TARGET_RPS).during(TestConfig.RAMP_SECONDS),
                constantUsersPerSec(TestConfig.TARGET_RPS).during(TestConfig.STEADY_SECONDS)
            )
        )
        .protocols(UserWorkflows.HTTP_PROTOCOL)
        .assertions(
            global().responseTime().percentile(95.0).lt(TestConfig.SLO_P95_MS),
            global().responseTime().percentile(99.0).lt(TestConfig.SLO_P99_MS),
            global().failedRequests().percent().lt(TestConfig.SLO_MAX_ERR_PCT)
        );
    }
}
