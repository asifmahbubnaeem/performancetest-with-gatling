package perf.workflows;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import perf.config.TestConfig;
import perf.util.SlotGate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * CNS ingestion — heavy write, triggers internal processing on the app side.
 * Deliberately NOT part of the random mixed-workload switch. Run as its own
 * scenario/phase so it's easy to include/exclude and reason about in reports.
 *
 * Guarantees:
 *  1. Only a fixed, pre-selected subset of each tenant's users ever uploads
 *     (data/uploaders.csv — see prep note below), sized ceil(25% * tenant
 *     user count). Deterministic, not a runtime percentage roll.
 *  2. Every selected uploader walks the FULL file list, in order, EXACTLY
 *     ONCE per file (repeat() iterates FILES.size() times over a distinct
 *     index each time — by construction, no user ever uploads the same
 *     file twice in a run).
 *  3. A process-wide Semaphore caps concurrent upload+processing pipelines
 *     (TestConfig.CNS_MAX_CONCURRENT, default 2 — see TestConfig for how
 *     to override), so no matter how many uploaders/tenants are injected,
 *     only N pipelines are ever active at once — this is what prevents
 *     same-file collisions and protects the app's internal processing
 *     from pile-up. The slot is held through upload AND polling, not just
 *     the HTTP call.
 *  4. Each upload is followed by polling GetDataLogs until the file's
 *     ingestion reaches a terminal status (SUCCESS/PARTIAL_SUCCESS/FAILED)
 *     before the next file (by any user) may claim the freed slot.
 *
 * PREP: generate data/uploaders.csv once from users.csv, e.g.:
 *   awk -F, 'NR==1{print;next} {c[$3]++; rows[$3,c[$3]]=$0}
 *     END{for (k in c) { n=c[k]; lim=int((n+3)/4);
 *       for(i=1;i<=lim;i++) print rows[k,i] }}' users.csv > uploaders.csv
 *   (groups by tenant_name column 3, takes ceil(25%) of each group —
 *    adjust column index to match your actual header order)
 */
public final class CnsIngestionWorkflow {

    private CnsIngestionWorkflow() {}

    private static final String GRAPHQL = "/service/graphql";
    private static final String AUTH_HEADER = "x-isara-authorization"; // confirm via curl

    // Tunables now live in TestConfig (see TestConfig_cns_additions.txt) so
    // they're configurable the same way as every other setting in this
    // framework: -D flags, or MAVEN_OPTS / env vars if you prefer a file/
    // script-based approach over typing them on the command line each time.
    private static final int MAX_CONCURRENT_UPLOADS = TestConfig.CNS_MAX_CONCURRENT;
    private static final Semaphore UPLOAD_SLOTS =
            new Semaphore(MAX_CONCURRENT_UPLOADS, true);

    private static final java.util.Set<String> TERMINAL_STATUSES =
            java.util.Set.of("SUCCESS", "PARTIAL_SUCCESS", "FAILED");

    private static final int POLL_INTERVAL_SECONDS = TestConfig.CNS_POLL_INTERVAL_SECONDS;
    private static final int POLL_MAX_ATTEMPTS = TestConfig.CNS_POLL_MAX_ATTEMPTS;

    // File set to upload — every selected uploader goes through ALL files
    // found in src/test/resources/data/ina/, one by one, in sorted order.
    // Rename/add/remove files there and this list updates automatically —
    // no code change needed. Override entirely with
    // -DcnsFiles=ina/fileA.zip,ina/fileB.zip if you need a specific subset.
    private static final List<String> FILES = discoverFiles();

    private static List<String> discoverFiles() {
        String override = System.getProperty("cnsFiles");
        if (override != null && !override.isBlank()) {
            return List.of(override.split(","));
        }

        java.net.URL dirUrl = CnsIngestionWorkflow.class.getClassLoader()
                .getResource("data/ina");
        if (dirUrl == null) {
            throw new IllegalStateException(
                "src/test/resources/data/ina/ not found on the classpath. " +
                "Create it and add the .zip files to upload, or set -DcnsFiles=...");
        }

        java.io.File dir = new java.io.File(dirUrl.getFile());
        java.io.File[] zips = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".zip"));
        if (zips == null || zips.length == 0) {
            throw new IllegalStateException(
                "No .zip files found in src/test/resources/data/ina/. " +
                "Add files there, or set -DcnsFiles=... explicitly.");
        }

        List<String> names = new java.util.ArrayList<>();
        for (java.io.File f : zips) names.add("ina/" + f.getName());
        java.util.Collections.sort(names); // deterministic, reproducible order across runs
        return names;
    }

    private static final FeederBuilder<String> UPLOADERS =
            csv("data/uploaders.csv").queue();   // each selected uploader used once

    // --- Poll GetDataLogs until this file's ingestion reaches a terminal status ---
    // ASSUMPTION (verify once against your real API): filtering by
    // filter.search=<fileName> and reading dataLogs[0] returns the most
    // recent attempt for that file under the current tenant (Workspace-Id
    // scopes results server-side). If a tenant re-uploads the same filename
    // repeatedly across runs and ordering isn't strictly recency-first,
    // this could occasionally watch a stale entry — confirm with one
    // manual query before relying on it for a long soak run.

    private static final ChainBuilder POLL_UNTIL_TERMINAL =
        exec(session -> session.set("pollAttempts", 0).set("ingestStatus", "PENDING"))
        .asLongAs(session -> {
            String status = session.getString("ingestStatus");
            int attempts = session.getInt("pollAttempts");
            boolean terminal = status != null && TERMINAL_STATUSES.contains(status);
            return !terminal && attempts < POLL_MAX_ATTEMPTS;
        }).on(
            pause(POLL_INTERVAL_SECONDS)
            .exec(session -> session.set("pollAttempts", session.getInt("pollAttempts") + 1))
            .exec(
                http("GQL_GetDataLogs")
                    .post(GRAPHQL)
                    .header(AUTH_HEADER, "Bearer #{authToken}")
                    .header("x-isara-customer-state", "#{customer_alias}")
                    .header("Workspace-Id", "#{tenant_id}")
                    .body(ElFileBody("bodies/get_data_logs.json"))
                    .check(status().is(200))
                    .check(jsonPath("$.errors").notExists())
                    // .optional(): an empty dataLogs array (job not indexed
                    // yet) must not crash the check — treat as still pending
                    .check(jsonPath("$.data.dataLog.dataLogs[0].status")
                            .optional().saveAs("ingestStatus"))
            )
            .exec(session -> {
                if (session.getInt("pollAttempts") >= POLL_MAX_ATTEMPTS
                        && !TERMINAL_STATUSES.contains(session.getString("ingestStatus"))) {
                    System.err.println("[CnsIngestionWorkflow] WARNING: " +
                        session.getString("cnsFileName") + " for tenant " +
                        session.getString("tenant_id") +
                        " never reached a terminal status after " +
                        POLL_MAX_ATTEMPTS + " polls — proceeding anyway.");
                }
                return session;
            })
        );

    // --- The gated upload: acquire -> upload -> WAIT for completion -> release ---
    // Slot is held for the entire pipeline (upload + processing), not just
    // the HTTP call, so the concurrency cap reflects real backend load.
    //
    // Acquiring goes through SlotGate (tryAcquire + backoff) rather than
    // UPLOAD_SLOTS.acquireUninterruptibly() directly — see SlotGate's javadoc.
    // A stuck backend that holds both slots for the full POLL_MAX_ATTEMPTS
    // window used to leave arriving uploaders blocked on a Gatling dispatcher
    // thread; enough of those piling up froze the entire simulation, not just
    // CNS. The wait cap here matches the longest a slot can legitimately stay
    // held (POLL_MAX_ATTEMPTS * POLL_INTERVAL_SECONDS) — if it's not free by
    // then, something is genuinely stuck, so this iteration is skipped rather
    // than waited on indefinitely.
    private static final String UPLOAD_GATE = "cnsUpload";
    private static final int UPLOAD_SLOT_MAX_WAIT_SECONDS = POLL_MAX_ATTEMPTS * POLL_INTERVAL_SECONDS;

    private static final ChainBuilder UPLOAD_ONE_FILE =
        exec(SlotGate.acquire(UPLOAD_SLOTS, UPLOAD_GATE, UPLOAD_SLOT_MAX_WAIT_SECONDS, POLL_INTERVAL_SECONDS))
        .doIf(session -> !session.getBoolean(SlotGate.skippedKey(UPLOAD_GATE)))
        .then(
            exec(
                http("REST_IngestCNS")
                    .post(TestConfig.INGESTION_URL + "/service/ingestion/cns")
                    .header(AUTH_HEADER, "Bearer #{authToken}")
                    .header("x-isara-customer-state", "#{customer_alias}")
                    .header("Workspace-Id", "#{tenant_id}")
                    .bodyPart(
                        RawFileBodyPart("cns", "data/#{cnsFile}")
                            .fileName("#{cnsFileName}")
                            .contentType("application/zip")
                    )
                    .asMultipartForm()
                    .check(status().in(200, 201, 202))
            )
            .exec(POLL_UNTIL_TERMINAL)
        )
        // release must run whether upload/poll passed, failed, or was skipped —
        // this is the last step so it always executes next in the chain. It's a
        // no-op if this iteration never actually acquired a permit (see SlotGate).
        .exec(SlotGate.release(UPLOAD_SLOTS, UPLOAD_GATE))
        // small buffer between this user's files (real wait already
        // happened above via polling)
        .pause(5, 15);

    // Ad-hoc single-file upload for a tenant found (via a live GetDataLogs
    // check — see UserWorkflows.REQUEST_REPORT_AND_WAIT) to have no
    // SUCCESS data log yet. Picks one file from FILES rather than walking
    // the whole set, and reuses the same gated upload+poll pipeline and
    // concurrency cap as cnsCoverageScenario() so it can't pile up on top
    // of the dedicated CNS coverage scenario running concurrently.
    public static final ChainBuilder UPLOAD_ONE_RANDOM_FILE =
        exec(session -> {
            String relPath = FILES.get(ThreadLocalRandom.current().nextInt(FILES.size()));
            String baseName = relPath.contains("/")
                    ? relPath.substring(relPath.lastIndexOf('/') + 1)
                    : relPath;
            return session.set("cnsFile", relPath).set("cnsFileName", baseName);
        })
        .exec(UPLOAD_ONE_FILE);

    /**
     * Row count of data/uploaders.csv (minus header) — how many uploaders
     * were pre-selected, with no override applied.
     */
    public static int discoveredUploaderCount() {
        return countCsvDataRows("data/uploaders.csv");
    }

    /**
     * Uploaders to actually inject: discoveredUploaderCount(), or fewer via
     * -DcnsUploaderCount=N (e.g. a quick partial-coverage check). Shared by
     * both CnsIngestionSimulation (standalone) and SoakSimulation (folded
     * into the soak run) so the override behaves identically either way.
     */
    public static int uploaderCount() {
        int discovered = discoveredUploaderCount();
        int count = Integer.getInteger("cnsUploaderCount", discovered);
        if (count <= 0) {
            throw new IllegalStateException(
                "data/uploaders.csv has no data rows — nothing to run. " +
                "Regenerate it from the current users.csv first.");
        }
        return count;
    }

    private static int countCsvDataRows(String classpathResource) {
        try (InputStream is = CnsIngestionWorkflow.class
                    .getClassLoader().getResourceAsStream(classpathResource)) {
            if (is == null) {
                throw new IllegalStateException(
                    "Could not find " + classpathResource + " on the classpath. " +
                    "Run the uploader-selection script first (see class javadoc).");
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

    /**
     * Deterministic coverage scenario: pre-selected uploaders only, each
     * walking every file in FILES, in order, exactly once.
     */
    public static ScenarioBuilder cnsCoverageScenario() {
        return scenario("CnsIngestionCoverage")
                .feed(UPLOADERS)
                .exec(UserWorkflows.ACQUIRE_TOKEN)
                .exitHereIfFailed()
                .repeat(FILES.size(), "fileIdx").on(
                    exec(session -> {
                        String relPath = FILES.get(session.getInt("fileIdx"));
                        String baseName = relPath.contains("/")
                                ? relPath.substring(relPath.lastIndexOf('/') + 1)
                                : relPath;
                        return session.set("cnsFile", relPath).set("cnsFileName", baseName);
                    })
                    .exec(UPLOAD_ONE_FILE)
                );
    }
}