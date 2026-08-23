package perf.simulations;
 
import io.gatling.javaapi.core.Simulation;
import perf.config.TestConfig;
import perf.workflows.UserWorkflows;
 
import static io.gatling.javaapi.core.CoreDsl.*;
 
/**
 * LOAD with paced login warm-up.
 *
 * Phase 1 (WarmupLogins): every user from users.csv logs in exactly once,
 *   spread over WARMUP_SECONDS (default: 3s per user) to stay under the
 *   app's per-IP login rate limiter. Tokens land in the shared cache.
 * Phase 2 (RandomUserJourney): the measured load — runs entirely on cached
 *   tokens, so zero logins occur during measurement (until the 25-min
 *   refresh kicks in on long runs, still at a trickle rate).
 */
public class LoadSimulation extends Simulation {
    {
        setUp(
            UserWorkflows.warmupLogins()
                .injectOpen(rampUsers(TestConfig.TOTAL_USERS).during(TestConfig.WARMUP_SECONDS))
                .andThen(
                    UserWorkflows.randomUserJourney().injectOpen(
                        rampUsersPerSec(1).to(TestConfig.TARGET_RPS).during(TestConfig.RAMP_SECONDS),
                        constantUsersPerSec(TestConfig.TARGET_RPS).during(TestConfig.STEADY_SECONDS)
                    )
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