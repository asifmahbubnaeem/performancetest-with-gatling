package perf.config;

/**
 * Single source of truth for all tunable parameters.
 * Everything is overridable via -D system properties (or env vars as fallback),
 * so scaling a run up/down never requires a code change.
 *
 * Example:
 *   mvn gatling:test -Dgatling.simulationClass=perf.simulations.LoadSimulation \
 *       -DbaseUrl=http://my-ec2:8080 -DtargetRps=25 -DsteadySeconds=900
 */
public final class TestConfig {

    private TestConfig() {}

    // --- Target system ---
    public static final String BASE_URL = str("baseUrl", "http://localhost:8080");
    public static final String INGESTION_URL = str("ingestionUrl", BASE_URL);
    public static final String CNS_FILE = str("cnsFile", "data/ina/heavy.zip");


    public static final int CNS_MAX_CONCURRENT       = integer("cnsMaxConcurrent", 2);
    public static final int CNS_POLL_INTERVAL_SECONDS = integer("cnsPollIntervalSeconds", 5);
    public static final int CNS_POLL_MAX_ATTEMPTS     = integer("cnsPollMaxAttempts", 80);

    // --- Dataset shape (must match what the seeder created) ---
    public static final int TENANTS          = integer("tenants", 5);
    public static final int USERS_PER_TENANT = integer("usersPerTenant", 10);

    public static final int TOTAL_USERS = TENANTS * USERS_PER_TENANT;
    // Pace: default one login every 3 seconds — stays under the per-IP limiter.
    // Override with -DwarmupSeconds if you learn the real threshold.
    public static final int WARMUP_SECONDS = integer("warmupSeconds", TOTAL_USERS * 3);

    // --- Injection profile (open workload model: arrival rate, not thread count) ---
    public static final double TARGET_RPS     = dbl("targetRps", 10);     // steady-state arrivals/sec
    public static final int    RAMP_SECONDS   = integer("rampSeconds", 120);
    public static final int    STEADY_SECONDS = integer("steadySeconds", 600);

    // CNS uploaders' single coverage pass, ramped across this window when CNS
    // ingestion runs as part of SoakSimulation (rather than standalone via
    // CnsIngestionSimulation). Defaults to the same span as STEADY_SECONDS so
    // ingestion load is spread throughout the soak instead of one burst at
    // the start — override independently with -DcnsRampSeconds if needed.
    public static final int CNS_RAMP_SECONDS = integer("cnsRampSeconds", STEADY_SECONDS);

    // --- Stress profile ---
    public static final double STRESS_START_RPS  = dbl("stressStartRps", 5);
    public static final double STRESS_STEP_RPS   = dbl("stressStepRps", 5);
    public static final int    STRESS_STEPS      = integer("stressSteps", 8);
    public static final int    STRESS_STEP_SECS  = integer("stressStepSecs", 120);

    // --- Spike profile ---
    public static final double SPIKE_BASE_RPS = dbl("spikeBaseRps", 5);
    public static final double SPIKE_PEAK_RPS = dbl("spikePeakRps", 60);
    public static final int    SPIKE_HOLD_SECS = integer("spikeHoldSecs", 60);

    // --- SLOs (used by Gatling assertions -> pass/fail in CI) ---
    public static final int    SLO_P95_MS       = integer("p95Ms", 800);
    public static final int    SLO_P99_MS       = integer("p99Ms", 1500);
    public static final double SLO_MAX_ERR_PCT  = dbl("maxErrorPct", 1.0);

    // --- Auth token cache ---
    // Must stay UNDER the backend's real access-token TTL (AUTH_ACCESS_TOKEN_TTL,
    // backend/server/src/util/auth/jwtConstants.js — defaults to 900s = 15 min), not
    // under some assumed 30 min. The old hardcoded 25-min refresh here was refreshing
    // *after* the JWT had already been dead for ~10 min, so every virtual user reusing
    // the shared cache during that window hit a real "Session expired" on its first
    // request of the journey (confirmed via 2026-08-24 soak run: 18% of KOs). If your
    // deployment overrides AUTH_ACCESS_TOKEN_TTL, override this to stay under it too.
    public static final int TOKEN_MAX_AGE_MINUTES = integer("tokenMaxAgeMinutes", 10);

    // --- AWS account workflow ---
    // Real AWS credentials must NEVER be hardcoded or committed (CLAUDE.md:
    // "Use env vars for secrets; do not add committed local secret files").
    // No real default is provided for the three security-sensitive fields —
    // supply them via -D or an (uppercased) env var each run; UserWorkflows
    // .ADD_AWS_ACCOUNT checks for blank values and fails loudly rather than
    // sending an empty credential to the API.
    public static final String AWS_ACCOUNT_ID        = str("awsAccountId", "");
    public static final String AWS_ACCESS_KEY_ID     = str("awsAccessKeyId", "");
    public static final String AWS_SECRET_ACCESS_KEY = str("awsSecretAccessKey", "");
    public static final String AWS_SCHEDULE          = str("awsSchedule", "1440");
    public static final boolean AWS_ENABLED          = Boolean.parseBoolean(str("awsEnabled", "true"));
    // applications is deliberately always sent as [] (see bodies/add_aws_account.json)
    // — no application ID is reliably available under the same tenant at the point
    // this fires (once per tenant, during warmup, before any addApplication call runs),
    // and the backend's applications arg is optional (awsAccountQL.js: skips the
    // AWSAccountApplications insert entirely when the array is empty).

    // --- Azure Key Vault workflow ---
    // Same rules as the AWS block above: real credentials NEVER hardcoded/
    // committed. "azureTenantId" here is the Azure AD tenant GUID — distinct
    // from this framework's own app tenant_id (Workspace-Id) — named with
    // the Azure prefix throughout to avoid confusing the two.
    public static final String AZURE_SUBSCRIPTION_ID = str("azureSubscriptionId", "");
    public static final String AZURE_TENANT_ID       = str("azureTenantId", "");
    public static final String AZURE_CLIENT_ID       = str("azureClientId", "");
    public static final String AZURE_CLIENT_SECRET   = str("azureClientSecret", "");
    public static final String AZURE_SCHEDULE        = str("azureSchedule", "1440");
    public static final boolean AZURE_ENABLED        = Boolean.parseBoolean(str("azureEnabled", "true"));
    // applications: [] for the same reason as AWS above — addAzureKeyVault's
    // arg is likewise optional (azureKVAccountsQL.js skips the
    // AzureKeyVaultApplications insert loop when the array is empty).

    // --- Helpers: system property first, then env var, then default ---
    private static String str(String key, String def) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) v = System.getenv(key.toUpperCase());
        return (v == null || v.isBlank()) ? def : v;
    }

    private static int integer(String key, int def) {
        return Integer.parseInt(str(key, String.valueOf(def)));
    }

    private static double dbl(String key, double def) {
        return Double.parseDouble(str(key, String.valueOf(def)));
    }
}
