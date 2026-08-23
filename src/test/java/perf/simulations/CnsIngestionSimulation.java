package perf.simulations;

import io.gatling.javaapi.core.Simulation;
import perf.workflows.CnsIngestionWorkflow;

import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

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

    private static int countCsvDataRows(String classpathResource) {
        try (InputStream is = CnsIngestionSimulation.class
                    .getClassLoader().getResourceAsStream(classpathResource)) {
            if (is == null) {
                throw new IllegalStateException(
                    "Could not find " + classpathResource + " on the classpath. " +
                    "Run the uploader-selection script first (see CnsIngestionWorkflow javadoc).");
            }
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                long lines = br.lines().count();
                return (int) Math.max(0, lines - 1); // minus header row
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed reading " + classpathResource, e);
        }
    }

    {
        int rampSeconds = Integer.getInteger("cnsRampSeconds", 300); // 5 min default

        int discoveredUploaders = countCsvDataRows("data/uploaders.csv");
        int uploaderCount = Integer.getInteger("cnsUploaderCount", discoveredUploaders);

        if (uploaderCount <= 0) {
            throw new IllegalStateException(
                "uploaders.csv has no data rows — nothing to run. " +
                "Regenerate it from the current users.csv first.");
        }
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