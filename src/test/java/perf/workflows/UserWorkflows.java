package perf.workflows;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import perf.config.TestConfig;
import perf.util.SlotGate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * GraphQL workflow layer v3 — with a cross-session token cache.
 *
 * WHY: the app rate-limits logins ("Too many login attempts"). With an open
 * injection model every arriving session would otherwise log in, hammering
 * the limiter. Real users authenticate once and stay logged in; this cache
 * reproduces that: one login per user per TestConfig.TOKEN_MAX_AGE_MINUTES,
 * shared across all virtual users via a ConcurrentHashMap (Gatling runs in
 * one JVM).
 *
 * See TestConfig.TOKEN_MAX_AGE_MINUTES for the real access-token TTL this
 * must stay under (backend default 15 min, not the 30 min once assumed here).
 */
public final class UserWorkflows {

    private UserWorkflows() {}

    private static final String GRAPHQL = "/service/graphql";
    private static final String AUTH_HEADER = "x-isara-authorization";

    /** username -> cached token + acquisition time */
    private record CachedToken(String token, Instant acquiredAt) {}
    private static final ConcurrentHashMap<String, CachedToken> TOKEN_CACHE =
            new ConcurrentHashMap<>();
    private static final long TOKEN_MAX_AGE_MINUTES = TestConfig.TOKEN_MAX_AGE_MINUTES;

    public static final HttpProtocolBuilder HTTP_PROTOCOL = http
            .baseUrl(TestConfig.BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .userAgentHeader("gatling-perf-framework")
            .header("x-isara-customer-state", "#{customer_alias}")
            .header("workspace-id", "#{tenant_id}");

    private static final FeederBuilder<String> USERS =
            csv("data/users.csv").random();

    // --- Login (raw) -----------------------------------------------------

    private static final ChainBuilder LOGIN = exec(
            http("GQL_Login")
                .post(GRAPHQL)
                .body(ElFileBody("bodies/login.json"))
                .check(status().is(200))
                .check(jsonPath("$.errors").notExists())
                .check(jsonPath("$.data.login.token").saveAs("authToken"))
    );

    // --- Token acquisition: cache first, login only if missing/stale -------

    private static boolean needsLogin(String username) {
        CachedToken cached = TOKEN_CACHE.get(username);
        return cached == null
            || cached.acquiredAt().isBefore(
                   Instant.now().minus(TOKEN_MAX_AGE_MINUTES, ChronoUnit.MINUTES));
    }

    public static final ChainBuilder ACQUIRE_TOKEN =
        doIfOrElse(session -> needsLogin(session.getString("username")))
            .then(
                exec(LOGIN)
                .exitHereIfFailed()
                .exec(session -> {
                    TOKEN_CACHE.put(session.getString("username"),
                        new CachedToken(session.getString("authToken"), Instant.now()));
                    return session;
                })
            )
            .orElse(
                exec(session -> session.set("authToken",
                        TOKEN_CACHE.get(session.getString("username")).token()))
            );

    // --- Operations --------------------------------------------------------

    private static final String SESSION_EXPIRED_MSG = "Session expired. Please sign in again.";

    /**
     * Wraps a bearer-token request so a stale/invalidated JWT triggers one
     * re-login + retry instead of a failure — mirrors what a real client SDK
     * does transparently. Any other failure (5xx, timeout) still fails after
     * the retry is spent, so genuine backend saturation stays visible rather
     * than being retried away.
     */
    private static ChainBuilder withAuthRetry(ChainBuilder request) {
        return exec(session -> session.set("sessionExpired", false))
            .exec(
                tryMax(2).on(
                    doIf(session -> session.getBoolean("sessionExpired"))
                        .then(
                            exec(LOGIN).exitHereIfFailed()
                            .exec(session -> {
                                TOKEN_CACHE.put(session.getString("username"),
                                        new CachedToken(session.getString("authToken"), Instant.now()));
                                return session.set("sessionExpired", false);
                            })
                        )
                    .exec(session -> session.set("gqlErrorMessage", ""))
                    .exec(request)
                    .exec(session -> session.set("sessionExpired",
                            SESSION_EXPIRED_MSG.equals(session.getString("gqlErrorMessage"))))
                )
            );
    }

    public static final ChainBuilder GENERATE_STREAM_TOKEN = exec(
            withAuthRetry(exec(
                http("GQL_StreamTokenRandom")
                    .post(GRAPHQL)
                    .header(AUTH_HEADER, "Bearer #{authToken}")
                    .body(ElFileBody("bodies/stream_token_random.json"))
                    .check(status().is(200))
                    .check(jsonPath("$.errors[0].message").optional().saveAs("gqlErrorMessage"))
                    .check(jsonPath("$.errors").notExists())
                    .check(jsonPath("$.data.streamTokenRandom").saveAs("streamToken"))
            ))
    ).pause(1, 3);

    public static final ChainBuilder SAVE_STREAM_TOKEN =
        exec(session -> session.set(
                "expiresAt",
                Instant.now().plus(7, ChronoUnit.DAYS)
                             .truncatedTo(ChronoUnit.MILLIS).toString()))
        .exec(
            withAuthRetry(exec(
                http("GQL_StreamTokenUpdate")
                    .post(GRAPHQL)
                    .header(AUTH_HEADER, "Bearer #{authToken}")
                    .body(ElFileBody("bodies/stream_token_update.json"))
                    .check(status().is(200))
                    .check(jsonPath("$.errors[0].message").optional().saveAs("gqlErrorMessage"))
                    .check(jsonPath("$.errors").notExists())
                    .check(jsonPath("$.data.streamTokenUpdate[0].id").exists())
            ))
        ).pause(2, 5);

    // exitHereIfFailed() is required: GENERATE_STREAM_TOKEN only .saveAs()s
    // "streamToken" on a 200/no-errors response. Without this guard, a
    // failed generate (500, timeout, dropped connection) still falls through
    // into SAVE_STREAM_TOKEN, whose body EL-interpolates #{streamToken} —
    // producing a *different*, misleading "No attribute named 'streamToken'
    // is defined" error that masks the real upstream failure.
    public static final ChainBuilder TOKEN_LIFECYCLE =
            exec(GENERATE_STREAM_TOKEN).exitHereIfFailed().exec(SAVE_STREAM_TOKEN);

    public static final ChainBuilder ADD_APPLICATION = exec(
            withAuthRetry(exec(
                http("GQL_AddApplication")
                    .post(GRAPHQL)
                    .header(AUTH_HEADER, "Bearer #{authToken}")
                    .body(ElFileBody("bodies/add_application.json"))
                    .check(status().is(200))
                    .check(jsonPath("$.errors[0].message").optional().saveAs("gqlErrorMessage"))
                    .check(jsonPath("$.errors").notExists())
                    .check(jsonPath("$.data.addApplication.status").is("Success"))
            ))
    ).pause(2, 5);

    // Scalar-returning mutation (no sub-selection), same shape as
    // GENERATE_STREAM_TOKEN's streamTokenRandom — .exists() is all we can
    // assert without a field to match against.
    public static final ChainBuilder PROBE_NOW = exec(
            withAuthRetry(exec(
                http("GQL_ProbeNow")
                    .post(GRAPHQL)
                    .header(AUTH_HEADER, "Bearer #{authToken}")
                    .body(ElFileBody("bodies/probe_now.json"))
                    .check(status().is(200))
                    .check(jsonPath("$.errors[0].message").optional().saveAs("gqlErrorMessage"))
                    .check(jsonPath("$.errors").notExists())
                    .check(jsonPath("$.data.probeNow").exists())
            ))
    );

    // Host probed by bodies/probe_now.json — kept in sync manually (no
    // session var links the two today since the probe body is static).
    private static final String PROBE_HOST = "1.1.1.1";

    // ASSUMPTION (same caveat as CnsIngestionWorkflow.POLL_UNTIL_TERMINAL):
    // getProbeNowResults returns one row per host (sample response: single
    // element, fixed "id":"1"), so filtering by host and taking the last
    // match is expected to be that host's current result. If the real API
    // ever returns per-probe HISTORY rows instead of an upsert-by-host row,
    // this could match a stale Success from an earlier, unrelated probe of
    // the same host — there's no per-request correlation id in the response
    // to rule that out. Worth a manual check before trusting this on a long
    // soak run where PROBE_NOW fires repeatedly against the same static host.
    private static final ChainBuilder POLL_UNTIL_PROBE_RESULT =
        exec(session -> session.set("probePollAttempts", 0).set("probeResultStatus", "PENDING"))
        .asLongAs(session -> {
            String status = session.getString("probeResultStatus");
            int attempts = session.getInt("probePollAttempts");
            return !"Success".equals(status) && attempts < TestConfig.PROBE_POLL_MAX_ATTEMPTS;
        }).on(
            pause(TestConfig.PROBE_POLL_INTERVAL_SECONDS)
            .exec(session -> session.set("probePollAttempts", session.getInt("probePollAttempts") + 1))
            .exec(
                withAuthRetry(exec(
                    http("GQL_GetProbeNowResults")
                        .post(GRAPHQL)
                        .header(AUTH_HEADER, "Bearer #{authToken}")
                        .body(ElFileBody("bodies/get_probe_now_results.json"))
                        .check(status().is(200))
                        .check(jsonPath("$.errors[0].message").optional().saveAs("gqlErrorMessage"))
                        .check(jsonPath("$.errors").notExists())
                        // findAll(): zero matches (probe not indexed yet) and
                        // one match are both valid states — .optional() so
                        // zero matches doesn't fail the check.
                        .check(jsonPath("$.data.getProbeNowResults[?(@.host=='" + PROBE_HOST + "')].resultStatus")
                                .findAll().optional().saveAs("probeResultStatuses"))
                ))
            )
            .exec(session -> {
                java.util.List<String> statuses = session.getList("probeResultStatuses");
                String latest = (statuses == null || statuses.isEmpty())
                        ? "PENDING" : statuses.get(statuses.size() - 1);
                return session.set("probeResultStatus", latest);
            })
            .exec(session -> {
                if (session.getInt("probePollAttempts") >= TestConfig.PROBE_POLL_MAX_ATTEMPTS
                        && !"Success".equals(session.getString("probeResultStatus"))) {
                    System.err.println("[UserWorkflows] WARNING: ProbeNow for host " + PROBE_HOST +
                        " never reached resultStatus=Success after " +
                        TestConfig.PROBE_POLL_MAX_ATTEMPTS + " polls (last seen: " +
                        session.getString("probeResultStatus") + ") — proceeding anyway.");
                }
                return session;
            })
        );

    // Process-wide cap on concurrent ProbeNow pipelines (mirrors
    // CnsIngestionWorkflow.UPLOAD_SLOTS). Held across PROBE_NOW AND its
    // result-polling, not just the initial HTTP call, so a slow/stuck probe
    // occupies its slot the whole time — this is what actually bounds
    // concurrent backend load, since targetRps alone only controls arrival
    // rate, not how many chains pile up when polling runs long under load.
    private static final int MAX_CONCURRENT_PROBES = TestConfig.PROBE_MAX_CONCURRENT;
    private static final Semaphore PROBE_SLOTS = new Semaphore(MAX_CONCURRENT_PROBES, true);

    // NOTE: no exitHereIfFailed() between acquire and release (unlike the
    // pre-semaphore version of this chain) — exitHereIfFailed() aborts the
    // WHOLE scenario for this virtual user, which would skip the release
    // step below and permanently leak that permit. Enough leaked permits
    // over a long soak run would deadlock every future PROBE_NOW_AND_WAIT
    // call. Same reasoning as CnsIngestionWorkflow.UPLOAD_ONE_FILE's own
    // release-always-runs comment. A failed ProbeNow now falls through into
    // polling instead of aborting the session; POLL_UNTIL_PROBE_RESULT is
    // still bounded by PROBE_POLL_MAX_ATTEMPTS, so this can't hang, just runs
    // its course and logs the existing "never reached resultStatus=Success"
    // warning.
    //
    // Acquiring goes through SlotGate (tryAcquire + backoff), not
    // PROBE_SLOTS.acquireUninterruptibly() directly — see SlotGate's javadoc:
    // blocking a Gatling dispatcher thread on a slow-to-free slot can freeze
    // the ENTIRE simulation, not just this chain. Wait cap matches the
    // longest a slot can legitimately stay held.
    private static final String PROBE_GATE = "probeNow";
    private static final int PROBE_SLOT_MAX_WAIT_SECONDS =
            TestConfig.PROBE_POLL_MAX_ATTEMPTS * TestConfig.PROBE_POLL_INTERVAL_SECONDS;

    public static final ChainBuilder PROBE_NOW_AND_WAIT =
            exec(SlotGate.acquire(PROBE_SLOTS, PROBE_GATE,
                    PROBE_SLOT_MAX_WAIT_SECONDS, TestConfig.PROBE_POLL_INTERVAL_SECONDS))
            .doIf(session -> !session.getBoolean(SlotGate.skippedKey(PROBE_GATE)))
            .then(
                exec(PROBE_NOW)
                .exec(POLL_UNTIL_PROBE_RESULT)
            )
            // release must run whether ProbeNow/polling passed, failed, or was
            // skipped — this is the last step so it always executes next in
            // the chain. No-op if this iteration never acquired a permit.
            .exec(SlotGate.release(PROBE_SLOTS, PROBE_GATE))
            .pause(1, 3);

    // reportType names, 1-indexed (index 0 unused) — no enum mapping is
    // exposed by the API itself, this is just what these values were
    // reported to mean when this workflow was added; not verified against
    // the backend beyond that. Spaces stripped (vs. the human-readable
    // names as given) so these double as Gatling request-name suffixes,
    // consistent with the no-spaces naming used everywhere else (GQL_Login,
    // GQL_StreamTokenRandom, ...).
    private static final String[] REPORT_TYPE_NAMES = {
        null,                          // 0 (unused)
        "ExecutiveSummary",            // 1
        "PQCCompliance",               // 2
        "ZoneSummary",                 // 3
        "ExecutiveTopThreats",         // 4
        "ZoneReport",                  // 5
        "ComplianceOverall",           // 6
        "ComplianceByZone",            // 7
        "ComplianceDetail",            // 8
        "CriticalSecurityAssessment",  // 9
        "PQCReadiness",                // 10
        "PCIDSSCompliance",            // 11
    };

    // requestReport returns an object (no fixed "Success" status literal
    // like addApplication/addAwsAccount use), so this checks reportID
    // exists rather than asserting a specific status value.
    public static final ChainBuilder REQUEST_REPORT = exec(session -> {
                int reportType = TestConfig.REQUEST_REPORT_TYPE >= 1 && TestConfig.REQUEST_REPORT_TYPE <= 11
                        ? TestConfig.REQUEST_REPORT_TYPE
                        : 1 + java.util.concurrent.ThreadLocalRandom.current().nextInt(11);
                return session.set("reportType", reportType)
                              .set("reportTypeName", REPORT_TYPE_NAMES[reportType]);
            })
            .exec(
                withAuthRetry(exec(
                    http("GQL_RequestReport_#{reportTypeName}")
                        .post(GRAPHQL)
                        .header(AUTH_HEADER, "Bearer #{authToken}")
                        .body(ElFileBody("bodies/request_report.json"))
                        .check(status().is(200))
                        .check(jsonPath("$.errors[0].message").optional().saveAs("gqlErrorMessage"))
                        .check(jsonPath("$.errors").notExists())
                        .check(jsonPath("$.data.requestReport.reportID").exists())
                ))
            );

    // Per instruction: check ALL objects in getReportRequests, not just the
    // one this request just created — there's no per-request correlation
    // used here (unlike the host-filtered ProbeNow check). Caveat: this
    // means a pre-existing report stuck at a non-Success status (e.g. a
    // prior failed request) would make this condition never become true
    // for the rest of the run — that's a real risk given REQUEST_REPORT
    // runs repeatedly with random types throughout randomUserJourney, so a
    // single stuck/failed report anywhere in the tenant's history blocks
    // every subsequent poll here until POLL_MAX_ATTEMPTS gives up. Empty
    // list is treated as NOT done (still pending), not vacuously successful
    // — our own just-submitted request should eventually appear in it.
    private static final ChainBuilder POLL_UNTIL_ALL_REPORTS_SUCCESS =
        exec(session -> session.set("reportPollAttempts", 0).set("allReportsSuccess", false))
        .asLongAs(session -> {
            boolean done = session.getBoolean("allReportsSuccess");
            int attempts = session.getInt("reportPollAttempts");
            return !done && attempts < TestConfig.REPORT_POLL_MAX_ATTEMPTS;
        }).on(
            pause(TestConfig.REPORT_POLL_INTERVAL_SECONDS)
            .exec(session -> session.set("reportPollAttempts", session.getInt("reportPollAttempts") + 1))
            .exec(
                withAuthRetry(exec(
                    http("GQL_GetReportRequests")
                        .post(GRAPHQL)
                        .header(AUTH_HEADER, "Bearer #{authToken}")
                        .body(ElFileBody("bodies/get_report_requests.json"))
                        .check(status().is(200))
                        .check(jsonPath("$.errors[0].message").optional().saveAs("gqlErrorMessage"))
                        .check(jsonPath("$.errors").notExists())
                        .check(jsonPath("$.data.getReportRequests[*].status")
                                .findAll().optional().saveAs("allReportStatuses"))
                ))
            )
            .exec(session -> {
                java.util.List<String> statuses = session.getList("allReportStatuses");
                boolean allSuccess = statuses != null && !statuses.isEmpty()
                        && statuses.stream().allMatch("Success"::equals);
                return session.set("allReportsSuccess", allSuccess);
            })
            .exec(session -> {
                if (session.getInt("reportPollAttempts") >= TestConfig.REPORT_POLL_MAX_ATTEMPTS
                        && !session.getBoolean("allReportsSuccess")) {
                    System.err.println("[UserWorkflows] WARNING: getReportRequests still had a " +
                        "non-Success entry after " + TestConfig.REPORT_POLL_MAX_ATTEMPTS +
                        " polls — proceeding anyway.");
                }
                return session;
            })
        );

    // Live check (not an in-process cache — real server state, so it also
    // covers tenants seeded by an earlier run or by the dedicated
    // CnsIngestionCoverage scenario running concurrently) for whether this
    // tenant has at least one successfully-ingested data log. Requesting a
    // report before that always fails with "No device data exist: cannot
    // generate report." (a real backend error, not a bug).
    private static final ChainBuilder CHECK_TENANT_HAS_DATA =
        exec(
            withAuthRetry(exec(
                http("GQL_GetDataLogsCheck")
                    .post(GRAPHQL)
                    .header(AUTH_HEADER, "Bearer #{authToken}")
                    .body(ElFileBody("bodies/get_data_logs_any.json"))
                    .check(status().is(200))
                    .check(jsonPath("$.errors[0].message").optional().saveAs("gqlErrorMessage"))
                    .check(jsonPath("$.errors").notExists())
                    .check(jsonPath("$.data.dataLog.dataLogs[*].status")
                            .findAll().optional().saveAs("tenantDataLogStatuses"))
            ))
        )
        .exec(session -> {
            java.util.List<String> statuses = session.getList("tenantDataLogStatuses");
            boolean hasData = statuses != null && statuses.contains("SUCCESS");
            return session.set("tenantHasUploadedData", hasData);
        });

    // Process-wide cap on concurrent report-generation pipelines (mirrors
    // CnsIngestionWorkflow.UPLOAD_SLOTS / PROBE_SLOTS above). Held across
    // REQUEST_REPORT AND its status-polling, not just the initial HTTP call, so
    // a slow/stuck report occupies its slot the whole time — this is what
    // actually bounds concurrent backend load, since targetRps alone only
    // controls arrival rate, not how many chains pile up when polling runs
    // long under load.
    private static final int MAX_CONCURRENT_REPORTS = TestConfig.REPORT_MAX_CONCURRENT;
    private static final Semaphore REPORT_SLOTS = new Semaphore(MAX_CONCURRENT_REPORTS, true);

    // Acquiring goes through SlotGate (tryAcquire + backoff), not
    // REPORT_SLOTS.acquireUninterruptibly() directly — see SlotGate's javadoc:
    // blocking a Gatling dispatcher thread on a slow-to-free slot can freeze
    // the ENTIRE simulation, not just this chain. Wait cap matches the
    // longest a slot can legitimately stay held.
    private static final String REPORT_GATE = "requestReport";
    private static final int REPORT_SLOT_MAX_WAIT_SECONDS =
            TestConfig.REPORT_POLL_MAX_ATTEMPTS * TestConfig.REPORT_POLL_INTERVAL_SECONDS;

    // Before requesting any report type: check for existing data via
    // CHECK_TENANT_HAS_DATA; if none found, trigger a CNS ingestion upload
    // for this tenant instead of requesting a report guaranteed to fail.
    public static final ChainBuilder REQUEST_REPORT_AND_WAIT =
            exec(CHECK_TENANT_HAS_DATA)
            .exec(
            /*    doIfOrElse(session -> session.getBoolean("tenantHasUploadedData"))
                    .then(
                        exec(REQUEST_REPORT).exitHereIfFailed().exec(POLL_UNTIL_ALL_REPORTS_SUCCESS).pause(2, 5)
                    )
                    .orElse(
                        exec(CnsIngestionWorkflow.UPLOAD_ONE_RANDOM_FILE)
                    )
	*/
	         doIf(session -> session.getBoolean("tenantHasUploadedData"))
                  .then(
                      // NOTE: no exitHereIfFailed() between acquire and release — same
                      // reasoning as PROBE_SLOTS above: exitHereIfFailed() aborts the
                      // WHOLE scenario for this virtual user, which would skip the
                      // release step and permanently leak the permit. A failed
                      // REQUEST_REPORT now falls through into polling instead of
                      // aborting the session; POLL_UNTIL_ALL_REPORTS_SUCCESS is still
                      // bounded by REPORT_POLL_MAX_ATTEMPTS, so this can't hang.
                      exec(SlotGate.acquire(REPORT_SLOTS, REPORT_GATE,
                              REPORT_SLOT_MAX_WAIT_SECONDS, TestConfig.REPORT_POLL_INTERVAL_SECONDS))
                      .doIf(session -> !session.getBoolean(SlotGate.skippedKey(REPORT_GATE)))
                      .then(
                          exec(REQUEST_REPORT)
                          .exec(POLL_UNTIL_ALL_REPORTS_SUCCESS)
                      )
                      // release must run whether REQUEST_REPORT/polling passed, failed,
                      // or was skipped — this is the last step so it always executes
                      // next. No-op if this iteration never acquired a permit.
                      .exec(SlotGate.release(REPORT_SLOTS, REPORT_GATE))
                      .pause(2, 5)
                  )
            );

    // Runs once per user — only from warmupLoginsAndSoakSetup() (SoakSimulation
    // only, see below), not from the shared warmupLogins() used by
    // Load/Stress/Spike, and not in the repeated randomUserJourney mix.
    // "name" is per-username (bodies/netmask_update.json:
    // "test_netmask_#{username}") since netmaskUpdate is keyed by name —
    // reusing a name across users would update the same record instead of
    // creating distinct ones.
    public static final ChainBuilder UPDATE_NETMASK = exec(
            withAuthRetry(exec(
                http("GQL_NetmaskUpdate")
                    .post(GRAPHQL)
                    .header(AUTH_HEADER, "Bearer #{authToken}")
                    .body(ElFileBody("bodies/netmask_update.json"))
                    .check(status().is(200))
                    .check(jsonPath("$.errors[0].message").optional().saveAs("gqlErrorMessage"))
                    .check(jsonPath("$.errors").notExists())
                    .check(jsonPath("$.data.netmaskUpdate[0].id").exists())
            ))
    ).pause(1, 3);

    // Runs once per TENANT, not per user: the first warmed-up user for a
    // given tenant_id claims it via the atomic Set.add() below (returns true
    // only for the first caller), so concurrent warmup arrivals for the same
    // tenant can't double-fire it. Only wired into warmupLoginsAndSoakSetup()
    // (SoakSimulation), same reasoning as UPDATE_NETMASK above.
    //
    // Credentials are NEVER hardcoded here — TestConfig.AWS_ACCESS_KEY_ID /
    // AWS_SECRET_ACCESS_KEY have no real default and must be supplied via
    // -D or an (uppercased) env var each run (see CLAUDE.md: never commit a
    // secret). A missing value fails this action loudly instead of silently
    // sending a blank credential to the API.
    private static final java.util.Set<String> AWS_ACCOUNT_TENANTS_DONE =
            ConcurrentHashMap.newKeySet();

    public static final ChainBuilder ADD_AWS_ACCOUNT =
        doIf(session -> AWS_ACCOUNT_TENANTS_DONE.add(session.getString("tenant_id")))
            .then(
                exec(session -> {
                    if (TestConfig.AWS_ACCOUNT_ID.isBlank()
                            || TestConfig.AWS_ACCESS_KEY_ID.isBlank()
                            || TestConfig.AWS_SECRET_ACCESS_KEY.isBlank()) {
                        throw new IllegalStateException(
                            "AddAwsAccount requires -DawsAccountId / -DawsAccessKeyId / " +
                            "-DawsSecretAccessKey (or the uppercased env vars) to be set " +
                            "— none are hardcoded here on purpose, see TestConfig.");
                    }
                    return session
                        .set("awsAccountId", TestConfig.AWS_ACCOUNT_ID)
                        .set("awsAccessKeyId", TestConfig.AWS_ACCESS_KEY_ID)
                        .set("awsSecretAccessKey", TestConfig.AWS_SECRET_ACCESS_KEY)
                        .set("awsSchedule", TestConfig.AWS_SCHEDULE)
                        .set("awsEnabled", TestConfig.AWS_ENABLED);
                })
                .exec(
                    withAuthRetry(exec(
                        http("GQL_AddAwsAccount")
                            .post(GRAPHQL)
                            .header(AUTH_HEADER, "Bearer #{authToken}")
                            .body(ElFileBody("bodies/add_aws_account.json"))
                            .check(status().is(200))
                            .check(jsonPath("$.errors[0].message").optional().saveAs("gqlErrorMessage"))
                            .check(jsonPath("$.errors").notExists())
                            .check(jsonPath("$.data.addAwsAccount.status").is("Success"))
                    ))
                )
            );

    // Runs once per TENANT, same claiming mechanism as ADD_AWS_ACCOUNT above
    // (a separate Set — a tenant independently gets one AWS attempt and one
    // Azure attempt). Only wired into warmupLoginsAndSoakSetup() (SoakSimulation).
    //
    // Unlike addAwsAccount, addAzureKeyVault calls the REAL Azure API
    // synchronously (KeyVaultManagementClient.vaults.listBySubscription(),
    // azureKVAccountsQL.js:53-67,102-116) before touching the DB, and returns
    // {status:"Error", msg:"AZURE_LIST_FAILED"} or "NO_VAULTS_FOUND" when the
    // credentials aren't a real, reachable subscription with at least one
    // vault — a legitimate business outcome, not a backend bug. So this does
    // NOT assert status == "Success" the way ADD_AWS_ACCOUNT does; it only
    // checks the response is well-formed, so placeholder/test Azure creds
    // don't inflate the soak run's failure count for an unrelated reason.
    private static final java.util.Set<String> AZURE_KV_TENANTS_DONE =
            ConcurrentHashMap.newKeySet();

    public static final ChainBuilder ADD_AZURE_KEY_VAULT =
        doIf(session -> AZURE_KV_TENANTS_DONE.add(session.getString("tenant_id")))
            .then(
                exec(session -> {
                    if (TestConfig.AZURE_SUBSCRIPTION_ID.isBlank()
                            || TestConfig.AZURE_TENANT_ID.isBlank()
                            || TestConfig.AZURE_CLIENT_ID.isBlank()
                            || TestConfig.AZURE_CLIENT_SECRET.isBlank()) {
                        throw new IllegalStateException(
                            "AddAzureKeyVault requires -DazureSubscriptionId / -DazureTenantId / " +
                            "-DazureClientId / -DazureClientSecret (or the uppercased env vars) " +
                            "to be set — none are hardcoded here on purpose, see TestConfig.");
                    }
                    return session
                        .set("azureSubscriptionId", TestConfig.AZURE_SUBSCRIPTION_ID)
                        .set("azureTenantId", TestConfig.AZURE_TENANT_ID)
                        .set("azureClientId", TestConfig.AZURE_CLIENT_ID)
                        .set("azureClientSecret", TestConfig.AZURE_CLIENT_SECRET)
                        .set("azureSchedule", TestConfig.AZURE_SCHEDULE)
                        .set("azureEnabled", TestConfig.AZURE_ENABLED);
                })
                .exec(
                    withAuthRetry(exec(
                        http("GQL_AddAzureKeyVault")
                            .post(GRAPHQL)
                            .header(AUTH_HEADER, "Bearer #{authToken}")
                            .body(ElFileBody("bodies/add_azure_key_vault.json"))
                            .check(status().is(200))
                            .check(jsonPath("$.errors[0].message").optional().saveAs("gqlErrorMessage"))
                            .check(jsonPath("$.errors").notExists())
                            .check(jsonPath("$.data.addAzureKeyVault.status").exists())
                    ))
                )
            );

    // Runs once per TENANT, same claiming mechanism as the two above (its
    // own Set — a tenant independently gets one AWS, one Azure, and one
    // Sectigo attempt). Only wired into warmupLoginsAndSoakSetup()
    // (SoakSimulation). addSectigoClm is a plain DB insert like addAwsAccount
    // (no synchronous external API call, confirmed via sectigoClmQL.js:49-115),
    // so this asserts status == "Success" the same way ADD_AWS_ACCOUNT does.
    private static final java.util.Set<String> SECTIGO_CLM_TENANTS_DONE =
            ConcurrentHashMap.newKeySet();

    public static final ChainBuilder ADD_SECTIGO_CLM =
        doIf(session -> SECTIGO_CLM_TENANTS_DONE.add(session.getString("tenant_id")))
            .then(
                exec(session -> {
                    if (TestConfig.SECTIGO_CLIENT_ID.isBlank()
                            || TestConfig.SECTIGO_CLIENT_SECRET.isBlank()) {
                        throw new IllegalStateException(
                            "AddSectigoClm requires -DsectigoClientId / -DsectigoClientSecret " +
                            "(or the uppercased env vars) to be set — none are hardcoded here " +
                            "on purpose, see TestConfig.");
                    }
                    return session
                        .set("sectigoApiUrl", TestConfig.SECTIGO_API_URL)
                        .set("sectigoTokenUrl", TestConfig.SECTIGO_TOKEN_URL)
                        .set("sectigoClientId", TestConfig.SECTIGO_CLIENT_ID)
                        .set("sectigoClientSecret", TestConfig.SECTIGO_CLIENT_SECRET)
                        .set("sectigoSchedule", TestConfig.SECTIGO_SCHEDULE)
                        .set("sectigoEnabled", TestConfig.SECTIGO_ENABLED);
                })
                .exec(
                    withAuthRetry(exec(
                        http("GQL_AddSectigoClm")
                            .post(GRAPHQL)
                            .header(AUTH_HEADER, "Bearer #{authToken}")
                            .body(ElFileBody("bodies/add_sectigo_clm.json"))
                            .check(status().is(200))
                            .check(jsonPath("$.errors[0].message").optional().saveAs("gqlErrorMessage"))
                            .check(jsonPath("$.errors").notExists())
                            .check(jsonPath("$.data.addSectigoClm.status").is("Success"))
                    ))
                )
            );

    // Runs once per TENANT, same claiming mechanism as the three above (its
    // own Set). Only wired into warmupLoginsAndSoakSetup() (SoakSimulation) —
    // NOT the repeated randomUserJourney mix, since addSchedule creates a
    // recurring cron job rather than firing a one-off action; calling it on
    // every journey iteration would pile up duplicate schedules per tenant
    // instead of exercising a realistic "add a schedule once" workflow.
    //
    // host/cron/probeType are plain config, not secrets, so — unlike AWS/
    // Azure/Sectigo — there's no blank-credential guard here; they're
    // hardcoded directly in bodies/add_schedule.json. addSchedule returns a
    // bare scalar (no sub-selection), same shape as probeNow, so .exists()
    // is all we can assert without a field to match against.
    private static final java.util.Set<String> SCHEDULE_PROBE_TENANTS_DONE =
            ConcurrentHashMap.newKeySet();

    public static final ChainBuilder SCHEDULE_PROBE =
        doIf(session -> SCHEDULE_PROBE_TENANTS_DONE.add(session.getString("tenant_id")))
            .then(
                exec(
                    withAuthRetry(exec(
                        http("GQL_AddSchedule")
                            .post(GRAPHQL)
                            .header(AUTH_HEADER, "Bearer #{authToken}")
                            .body(ElFileBody("bodies/add_schedule.json"))
                            .check(status().is(200))
                            .check(jsonPath("$.errors[0].message").optional().saveAs("gqlErrorMessage"))
                            .check(jsonPath("$.errors").notExists())
                            .check(jsonPath("$.data.addSchedule").exists())
                    ))
                )
            );

    // --- Full journey --------------------------------------------------------

    private static final FeederBuilder<String> USERS_ONCE =
            csv("data/users.csv").queue();

    /** Paced pre-authentication of the whole user pool. */
    public static ScenarioBuilder warmupLogins() {
        return scenario("WarmupLogins")
                .feed(USERS_ONCE)
                .exec(ACQUIRE_TOKEN);
    }

    /**
     * SoakSimulation-only variant of warmupLogins(): same paced login pass,
     * plus one NetmaskUpdate per user and one AddAwsAccount + AddAzureKeyVault
     * + AddSectigoClm + AddSchedule per tenant (first user of that tenant
     * only). Uses its own fresh csv("data/users.csv") feeder instance —
     * deliberately NOT the shared USERS_ONCE above — so Load/Stress/Spike
     * simulations calling warmupLogins() are unaffected by any of these
     * additions, and this doesn't compete with USERS_ONCE for rows.
     */
    public static ScenarioBuilder warmupLoginsAndSoakSetup() {
        return scenario("WarmupLoginsAndSoakSetup")
                .feed(csv("data/users.csv").queue())
                .exec(ACQUIRE_TOKEN)
                .exitHereIfFailed()
                .exec(UPDATE_NETMASK)
                .exec(ADD_AWS_ACCOUNT)
                .exec(ADD_AZURE_KEY_VAULT)
                .exec(ADD_SECTIGO_CLM);
                // .exec(SCHEDULE_PROBE);
    }

    public static ScenarioBuilder randomUserJourney() {
        return scenario("RandomUserJourney")
                .feed(USERS)
                .exec(ACQUIRE_TOKEN)
                .exitHereIfFailed()
                .repeat(session -> 3 + java.util.concurrent.ThreadLocalRandom.current().nextInt(4))
                .on(
                    randomSwitch().on(
                        //percent(40.0).then(GENERATE_STREAM_TOKEN),
                        //percent(20.0).then(TOKEN_LIFECYCLE),
                        percent(15.0).then(ADD_APPLICATION),
                        percent(15.0).then(PROBE_NOW_AND_WAIT),
                        percent(10.0).then(REQUEST_REPORT_AND_WAIT)
                    )
                );
    }
}
