package com.weavelab.interview.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitPropertiesTest {

    @Test
    void capacityDefaultsToRequestsWhenNotConfigured() {
        RateLimitProperties properties = new RateLimitProperties(true, 10, Duration.ofMinutes(1), null);

        assertThat(properties.effectiveCapacity()).isEqualTo(10);
    }

    @Test
    void explicitCapacityWins() {
        RateLimitProperties properties = new RateLimitProperties(true, 10, Duration.ofMinutes(1), 1);

        assertThat(properties.effectiveCapacity()).isEqualTo(1);
    }
}
