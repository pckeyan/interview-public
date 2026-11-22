package com.weavelab.interview.ratelimit;

import com.weavelab.interview.ratelimit.LeakyBucketRateLimiter.Decision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LeakyBucketRateLimiterTest {

    private static final int REQUESTS = 10;
    private static final Duration PERIOD = Duration.ofMinutes(1);
    private static final Duration DRAIN_INTERVAL = Duration.ofSeconds(6);

    private MutableNanoClock clock;
    private LeakyBucketRateLimiter limiter;

    @BeforeEach
    void setUp() {
        clock = new MutableNanoClock();
        limiter = new LeakyBucketRateLimiter(REQUESTS, PERIOD, REQUESTS, clock);
    }

    @Test
    void admitsUpToCapacityThenRejects() {
        assertThat(acquireMany(REQUESTS)).isEqualTo(REQUESTS);

        Decision rejected = limiter.tryAcquire();
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfter()).isEqualTo(DRAIN_INTERVAL);
    }

    @Test
    void admitsExactlyOneMoreAfterOneDrainInterval() {
        acquireMany(REQUESTS);

        clock.advance(DRAIN_INTERVAL);

        assertThat(limiter.tryAcquire().allowed()).isTrue();
        assertThat(limiter.tryAcquire().allowed()).isFalse();
    }

    @Test
    void admitsNothingAfterPartialDrainInterval() {
        acquireMany(REQUESTS);

        clock.advance(DRAIN_INTERVAL.minusMillis(100));

        Decision rejected = limiter.tryAcquire();
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfter()).isEqualTo(Duration.ofMillis(100));
    }

    @Test
    void fullyDrainsAfterWholePeriod() {
        acquireMany(REQUESTS);

        clock.advance(PERIOD);

        assertThat(acquireMany(REQUESTS)).isEqualTo(REQUESTS);
        assertThat(limiter.tryAcquire().allowed()).isFalse();
    }

    @Test
    void idleTimeNeverBuildsCreditBeyondCapacity() {
        clock.advance(Duration.ofHours(5));

        assertThat(acquireMany(REQUESTS)).isEqualTo(REQUESTS);
        assertThat(limiter.tryAcquire().allowed()).isFalse();
    }

    @Test
    void retryAfterShrinksAsTimePasses() {
        acquireMany(REQUESTS);

        clock.advance(Duration.ofSeconds(2));

        assertThat(limiter.tryAcquire().retryAfter()).isEqualTo(Duration.ofSeconds(4));
    }

    @Test
    void capacityOfOneEnforcesStrictSpacing() {
        limiter = new LeakyBucketRateLimiter(REQUESTS, PERIOD, 1, clock);

        assertThat(limiter.tryAcquire().allowed()).isTrue();
        assertThat(limiter.tryAcquire().allowed()).isFalse();

        clock.advance(DRAIN_INTERVAL);
        assertThat(limiter.tryAcquire().allowed()).isTrue();
    }

    @Test
    void allowedDecisionHasZeroRetryAfter() {
        assertThat(limiter.tryAcquire()).isEqualTo(new Decision(true, Duration.ZERO));
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new LeakyBucketRateLimiter(0, PERIOD, 1, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requests");
        assertThatThrownBy(() -> new LeakyBucketRateLimiter(1, PERIOD, 0, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capacity");
        assertThatThrownBy(() -> new LeakyBucketRateLimiter(1, Duration.ZERO, 1, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("period");
        assertThatThrownBy(() -> new LeakyBucketRateLimiter(1, Duration.ofSeconds(-1), 1, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("period");
        assertThatThrownBy(() -> new LeakyBucketRateLimiter(1, PERIOD, 1, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void concurrentCallersShareOneBucket() throws Exception {
        int threads = 16;
        int attemptsPerThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<Integer> task = () -> {
                    start.await();
                    int allowed = 0;
                    for (int j = 0; j < attemptsPerThread; j++) {
                        if (limiter.tryAcquire().allowed()) {
                            allowed++;
                        }
                    }
                    return allowed;
                };
                results.add(pool.submit(task));
            }
            start.countDown();

            int totalAllowed = 0;
            for (Future<Integer> result : results) {
                totalAllowed += result.get(10, TimeUnit.SECONDS);
            }
            assertThat(totalAllowed).isEqualTo(REQUESTS);
        } finally {
            pool.shutdownNow();
        }
    }

    private int acquireMany(int attempts) {
        int allowed = 0;
        for (int i = 0; i < attempts; i++) {
            if (limiter.tryAcquire().allowed()) {
                allowed++;
            }
        }
        return allowed;
    }
}
