package perf.simulations;

import io.gatling.javaapi.core.Simulation;
import perf.config.TestConfig;
import perf.workflows.CnsIngestionWorkflow;
import perf.workflows.UserWorkflows;

import static io.gatling.javaapi.core.CoreDsl.*;

/**
 * SOAK: moderate load for hours. Run with e.g. -DsteadySeconds=14400 (4h).
 * Hunts memory/connection leaks, WAL & disk growth, autovacuum debt.
 * Compare first-hour vs last-hour percentiles — drift means a leak.
 *
 * Paced login warm-up first (see LoadSimulation) — without it, the ramp
 * draws most of the user pool's distinct usernames within the first ~10-20s,
 * which needs more logins than the per-IP login rate limiter's burst
 * capacity allows and trips failed-login noise right at the start of a
 * long run.
 *
 * CNS ingestion coverage (CnsIngestionWorkflow) runs alongside the random
 * mixed workload rather than as its own separate phase: pre-selected
 * uploaders are ramped in across TestConfig.CNS_RAMP_SECONDS (defaults to
 * STEADY_SECONDS), so the heavy-write/ingestion path is exercised throughout
 * the soak instead of one burst at the start. Both start only after warmup
 * finishes — CNS uploaders are drawn from the same user pool and must not
 * log in outside the paced warmup either.
 *
 * Warmup here is warmupLoginsAndSoakSetup(), not the shared warmupLogins()
 * used by Load/Stress/Spike: it does the same paced login pass, plus one
 * NetmaskUpdate per user and one AddAwsAccount per tenant (see
 * UserWorkflows), both completing before CNS/RandomUserJourney start.
 */
public class SoakSimulation extends Simulation {
    {
        setUp(
            UserWorkflows.warmupLoginsAndSoakSetup()
                .injectOpen(rampUsers(TestConfig.TOTAL_USERS).during(TestConfig.WARMUP_SECONDS))
                .andThen(
                    UserWorkflows.randomUserJourney().injectOpen(
                        rampUsersPerSec(1).to(TestConfig.TARGET_RPS).during(TestConfig.RAMP_SECONDS),
                        constantUsersPerSec(TestConfig.TARGET_RPS).during(TestConfig.STEADY_SECONDS)
                    ),
                    CnsIngestionWorkflow.cnsCoverageScenario().injectOpen(
                        rampUsers(CnsIngestionWorkflow.uploaderCount()).during(TestConfig.CNS_RAMP_SECONDS)
                    )
                )
        )
        .protocols(UserWorkflows.HTTP_PROTOCOL)
        .assertions(
            global().failedRequests().percent().lt(TestConfig.SLO_MAX_ERR_PCT)
        );
    }
}
