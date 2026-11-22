package com.weavelab.interview.ratelimit;

import java.time.Duration;
import java.util.function.LongSupplier;

/** A controllable monotonic clock for deterministic rate limiter tests. */
final class MutableNanoClock implements LongSupplier {

    private volatile long nanos;

    @Override
    public long getAsLong() {
        return nanos;
    }

    void advance(Duration duration) {
        nanos += duration.toNanos();
    }
}
