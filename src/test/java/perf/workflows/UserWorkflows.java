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

    // --- Full journey --------------------------------------------------------

    private static final FeederBuilder<String> USERS_ONCE =
            csv("data/users.csv").queue();
 
    /** Paced pre-authentication of the whole user pool. */
    public static ScenarioBuilder warmupLogins() {
        return scenario("WarmupLogins")
                .feed(USERS_ONCE)
                .exec(ACQUIRE_TOKEN);
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