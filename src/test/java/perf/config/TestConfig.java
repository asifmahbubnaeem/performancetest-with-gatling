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

    // --- Dataset shape (must match what the seeder created) ---
    public static final int TENANTS          = integer("tenants", 5);
    public static final int USERS_PER_TENANT = integer("usersPerTenant", 10);

    // --- Injection profile (open workload model: arrival rate, not thread count) ---
    public static final double TARGET_RPS     = dbl("targetRps", 10);     // steady-state arrivals/sec
    public static final int    RAMP_SECONDS   = integer("rampSeconds", 120);
    public static final int    STEADY_SECONDS = integer("steadySeconds", 600);

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
