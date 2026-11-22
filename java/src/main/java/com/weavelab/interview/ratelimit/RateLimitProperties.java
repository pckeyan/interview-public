package com.weavelab.interview.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Configuration for the global request rate limiter, bound from {@code app.rate-limit.*}.
 *
 * @param enabled  whether the {@link RateLimitFilter} is registered at all
 * @param requests number of requests drained per {@code period}
 * @param period   the drain period
 * @param capacity maximum burst size; defaults to {@code requests} when not set
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("10") int requests,
        @DefaultValue("1m") Duration period,
        Integer capacity
) {

    /** The configured capacity, or {@link #requests()} when no capacity was given. */
    public int effectiveCapacity() {
        return capacity != null ? capacity : requests;
    }
}
