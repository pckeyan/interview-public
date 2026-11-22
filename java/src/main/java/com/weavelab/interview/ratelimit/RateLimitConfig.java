package com.weavelab.interview.ratelimit;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    @Bean
    public LeakyBucketRateLimiter rateLimiter(RateLimitProperties properties) {
        return new LeakyBucketRateLimiter(
                properties.requests(),
                properties.period(),
                properties.effectiveCapacity(),
                System::nanoTime);
    }
}
