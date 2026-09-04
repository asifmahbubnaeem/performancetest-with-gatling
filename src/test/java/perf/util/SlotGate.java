package perf.util;

import io.gatling.javaapi.core.ChainBuilder;

import java.util.concurrent.Semaphore;

import static io.gatling.javaapi.core.CoreDsl.*;

/**
 * Non-blocking replacement for calling Semaphore.acquireUninterruptibly() directly
 * inside a Gatling chain.
 *
 * acquireUninterruptibly() blocks whatever thread runs the exec() lambda, and on
 * Gatling that thread is one of a small, shared dispatcher pool (Pekko/Akka's
 * ForkJoinPool) used by every virtual user across every scenario in the whole
 * simulation. If a slot is held longer than expected (e.g. a slow backend under
 * load) and enough virtual users pile up blocked waiting for one, they can consume
 * the pool's entire thread-compensation ceiling — freezing the ENTIRE simulation
 * (every scenario, not just the one contending for that semaphore), while the JVM
 * itself stays alive. Confirmed via jstack during a soak run: multiple threads
 * parked on the same Semaphore$FairSync, all bottoming out at
 * CnsIngestionWorkflow's UPLOAD_SLOTS.acquireUninterruptibly() call (2026-09-05).
 *
 * acquire() below polls Semaphore.tryAcquire() on a pause-driven retry loop
 * instead. Waiting now costs a Gatling session idling between scheduled steps,
 * never a blocked dispatcher thread — so a slow/stuck op in any one gated chain
 * can no longer starve the engine for every other chain in the run.
 */
public final class SlotGate {

    private SlotGate() {}

    /**
     * Attempts to acquire a permit from {@code slots} without ever blocking a
     * dispatcher thread. Retries every {@code retryIntervalSeconds} up to
     * {@code maxWaitSeconds} total; if no permit frees up in that window, gives up,
     * logs a WARNING, and marks the session so both the caller (via
     * {@link #skippedKey}) and {@link #release} know a permit was never taken.
     *
     * @param name unique per-call-site name, used only to namespace the session
     *             attributes this sets (e.g. "cnsUpload") — not a lock identity.
     */
    public static ChainBuilder acquire(Semaphore slots, String name,
                                        int maxWaitSeconds, int retryIntervalSeconds) {
        String acquiredKey = acquiredKey(name);
        String skippedKey = skippedKey(name);
        String attemptsKey = name + "_acquireAttempts";
        int interval = Math.max(1, retryIntervalSeconds);
        int maxAttempts = Math.max(1, maxWaitSeconds / interval);

        return
            exec(session -> session
                    .set(acquiredKey, false)
                    .set(skippedKey, false)
                    .set(attemptsKey, 0))
            .asLongAs(session -> !session.getBoolean(acquiredKey) && !session.getBoolean(skippedKey))
            .on(
                exec(session -> session.set(acquiredKey, slots.tryAcquire()))
                .doIf(session -> !session.getBoolean(acquiredKey))
                .then(
                    exec(session -> session.set(attemptsKey, session.getInt(attemptsKey) + 1))
                    .doIfOrElse(session -> session.getInt(attemptsKey) >= maxAttempts)
                    .then(exec(session -> {
                        System.err.println("[SlotGate] WARNING: gave up waiting for a '" + name
                            + "' slot after " + maxAttempts + " attempts (~" + maxWaitSeconds
                            + "s) — skipping this operation this iteration.");
                        return session.set(skippedKey, true);
                    }))
                    .orElse(pause(interval))
                )
            );
    }

    /** Session attribute set true only when acquire() actually got a permit. */
    public static String acquiredKey(String name) {
        return name + "_acquired";
    }

    /** Session attribute set true when acquire() gave up without a permit. */
    public static String skippedKey(String name) {
        return name + "_skipped";
    }

    /**
     * Releases the permit taken by {@link #acquire}, but only if one was actually
     * acquired — a no-op after a skipped (gave-up) acquire, so this can never
     * release more permits than were really taken from the pool.
     */
    public static ChainBuilder release(Semaphore slots, String name) {
        String acquiredKey = acquiredKey(name);
        return exec(session -> {
            if (session.getBoolean(acquiredKey)) {
                slots.release();
            }
            return session;
        });
    }
}
