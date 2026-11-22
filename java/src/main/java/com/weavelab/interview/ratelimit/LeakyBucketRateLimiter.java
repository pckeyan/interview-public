package com.weavelab.interview.ratelimit;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * A process-local rate limiter implementing the Leaky Bucket algorithm in its
 * "meter" form.
 *
 * <p>Every admitted request pours one unit of water into a bucket of fixed capacity.
 * Water drains out of the bucket at a constant rate. A request that would overflow the
 * bucket is rejected, never queued. This is the form used by NGINX's {@code limit_req}
 * and is the right fit for an HTTP API that should answer {@code 429 Too Many Requests}
 * rather than block the caller.
 *
 * <p>Implementation notes:
 * <ul>
 *   <li>The water level is stored as <em>nanoseconds of drain time</em>, so the leak
 *       since the previous call is simply the elapsed time. All arithmetic is integer,
 *       so the level never drifts, and no background thread is required.</li>
 *   <li>The clock is a monotonic {@link LongSupplier} (normally {@link System#nanoTime()}).
 *       A wall clock would be corrupted by NTP adjustments.</li>
 *   <li>{@link #tryAcquire()} is {@code synchronized}. At the rates this limiter is meant
 *       for, lock contention is irrelevant, and the lock makes correctness under
 *       concurrency trivially provable. A lock-free GCRA formulation was considered and
 *       rejected as harder to read.</li>
 * </ul>
 *
 * <p>With {@code requests = 10}, {@code period = 1 minute} and {@code capacity = 10}, an
 * idle bucket admits a burst of 10 requests, then one request every six seconds. Use
 * {@code capacity = 1} for a strict no-burst limiter that enforces that spacing always.
 */
public final class LeakyBucketRateLimiter {

    private final long drainIntervalNanos;
    private final long capacityNanos;
    private final LongSupplier nanoClock;
    private long waterNanos;
    private long lastUpdateNanos;

    /**
     * @param requests  number of requests drained per {@code period}; must be at least 1
     * @param period    the drain period; must be positive
     * @param capacity  maximum number of requests the bucket holds (the burst size);
     *                  must be at least 1
     * @param nanoClock monotonic clock in nanoseconds, normally {@code System::nanoTime}
     */
    public LeakyBucketRateLimiter(int requests, Duration period, int capacity, LongSupplier nanoClock) {
        if (requests < 1) {
            throw new IllegalArgumentException("requests must be at least 1, was " + requests);
        }
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1, was " + capacity);
        }
        Objects.requireNonNull(period, "period");
        if (period.isNegative() || period.isZero()) {
            throw new IllegalArgumentException("period must be positive, was " + period);
        }

        this.drainIntervalNanos = period.toNanos() / requests;
        if (this.drainIntervalNanos < 1) {
            throw new IllegalArgumentException(
                    "period " + period + " is too short for " + requests + " requests");
        }
        this.capacityNanos = Math.multiplyExact(capacity, this.drainIntervalNanos);
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.lastUpdateNanos = nanoClock.getAsLong();
    }

    /**
     * Attempts to admit one request. Never blocks.
     *
     * @return the decision, including how long to wait when rejected
     */
    public synchronized Decision tryAcquire() {
        drain(nanoClock.getAsLong());

        if (waterNanos + drainIntervalNanos <= capacityNanos) {
            waterNanos += drainIntervalNanos;
            return Decision.ALLOWED;
        }
        return Decision.rejected(waterNanos + drainIntervalNanos - capacityNanos);
    }

    /**
     * Lets water out for the time elapsed since the previous update.
     */
    private void drain(long now) {
        long elapsed = Math.max(0, now - lastUpdateNanos);
        waterNanos = Math.max(0, waterNanos - elapsed);
        lastUpdateNanos = now;
    }

    /**
     * Outcome of a {@link #tryAcquire()} call.
     *
     * @param allowed    whether the request was admitted
     * @param retryAfter when rejected, how long until the next request can be admitted;
     *                   {@link Duration#ZERO} when allowed
     */
    public record Decision(boolean allowed, Duration retryAfter) {

        private static final Decision ALLOWED = new Decision(true, Duration.ZERO);

        private static Decision rejected(long waitNanos) {

            return new Decision(false, Duration.ofNanos(waitNanos));
        }
    }
}
