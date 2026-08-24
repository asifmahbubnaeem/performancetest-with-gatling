package perf.workflows;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import perf.config.TestConfig;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ConcurrentHashMap;

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
     * plus one NetmaskUpdate per user and one AddAwsAccount per tenant (first
     * user of that tenant only). Uses its own fresh csv("data/users.csv")
     * feeder instance — deliberately NOT the shared USERS_ONCE above — so
     * Load/Stress/Spike simulations calling warmupLogins() are unaffected by
     * either addition, and this doesn't compete with USERS_ONCE for rows.
     */
    public static ScenarioBuilder warmupLoginsAndSoakSetup() {
        return scenario("WarmupLoginsAndSoakSetup")
                .feed(csv("data/users.csv").queue())
                .exec(ACQUIRE_TOKEN)
                .exitHereIfFailed()
                .exec(UPDATE_NETMASK)
                .exec(ADD_AWS_ACCOUNT);
    }

    public static ScenarioBuilder randomUserJourney() {
        return scenario("RandomUserJourney")
                .feed(USERS)
                .exec(ACQUIRE_TOKEN)
                .exitHereIfFailed()
                .repeat(session -> 3 + java.util.concurrent.ThreadLocalRandom.current().nextInt(4))
                .on(
                    randomSwitch().on(
                        percent(55.0).then(GENERATE_STREAM_TOKEN),
                        percent(30.0).then(TOKEN_LIFECYCLE),
                        percent(15.0).then(ADD_APPLICATION)
                    )
                );
    }
}