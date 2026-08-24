package perf.simulations;

import io.gatling.javaapi.core.Simulation;
import perf.workflows.CnsIngestionWorkflow;

import static io.gatling.javaapi.core.CoreDsl.*;

/**
 * Dedicated CNS ingestion coverage run — heavy write, deliberately isolated
 * from the mixed random-workflow load. Every pre-selected uploader (see
 * data/uploaders.csv) runs through every configured file exactly once,
 * throttled by a shared semaphore (CnsIngestionWorkflow.MAX_CONCURRENT_UPLOADS,
 * default 2, override with -DcnsMaxConcurrent=N).
 *
 * Uploader count is read automatically from uploaders.csv at startup, so
 * tenant/customer/user counts can vary freely between runs and environments
 * with zero code changes — regenerate uploaders.csv, re-run, done.
 * Override with -DcnsUploaderCount=N only if you deliberately want to run
 * fewer than the full pre-selected set (e.g. a quick partial-coverage check).
 *
 * Run standalone:
 *   mvn gatling:test -Dgatling.simulationClass=perf.simulations.CnsIngestionSimulation \
 *     -DbaseUrl=https://<app-host> -DcnsMaxConcurrent=2 -DcnsRampSeconds=300
 */
public class CnsIngestionSimulation extends Simulation {

    {
        int rampSeconds = Integer.getInteger("cnsRampSeconds", 300); // 5 min default

        int discoveredUploaders = CnsIngestionWorkflow.discoveredUploaderCount();
        int uploaderCount = CnsIngestionWorkflow.uploaderCount();

        System.out.println("[CnsIngestionSimulation] uploaders.csv has "
                + discoveredUploaders + " data rows; injecting " + uploaderCount
                + " users over " + rampSeconds + "s");

        setUp(
            CnsIngestionWorkflow.cnsCoverageScenario()
                .injectOpen(rampUsers(uploaderCount).during(rampSeconds))
        )
        .protocols(perf.workflows.UserWorkflows.HTTP_PROTOCOL);
        // No SLO assertions — this is a coverage/soak-style run, not a
        // pass/fail gate; read the report for KOs and Grafana for impact.
    }
}