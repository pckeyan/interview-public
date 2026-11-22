package com.weavelab.interview.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "app.data-dir=target/test-data",
    "spring.datasource.url=jdbc:sqlite:target/test-data/app.db",
    "app.startup-check=false",
    "app.rate-limit.requests=10",
    "app.rate-limit.period=1m"
})
class RateLimitFilterIntegrationTest {

    private static final int LIMIT = 10;
    private static final Duration PERIOD = Duration.ofMinutes(1);
    private static final Duration DRAIN_INTERVAL = Duration.ofSeconds(6);

    @TestConfiguration
    static class FakeClockConfig {

        @Bean
        MutableNanoClock fakeNanoClock() {
            return new MutableNanoClock();
        }

        @Bean
        @Primary
        LeakyBucketRateLimiter fakeClockRateLimiter(RateLimitProperties properties, MutableNanoClock clock) {
            return new LeakyBucketRateLimiter(
                    properties.requests(), properties.period(), properties.effectiveCapacity(), clock);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MutableNanoClock clock;

    @BeforeEach
    void drainBucket() {
        clock.advance(PERIOD);
    }

    @Test
    void admitsLimitThenRespondsWithTooManyRequests() throws Exception {
        for (int i = 0; i < LIMIT; i++) {
            mockMvc.perform(authed(get("/api/contacts?limit=1"))).andExpect(status().isOk());
        }

        mockMvc.perform(authed(get("/api/contacts?limit=1")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "6"))
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(content().json(RateLimitFilter.ERROR_BODY));
    }

    @Test
    void healthEndpointIsNeverLimited() throws Exception {
        fillBucket();

        mockMvc.perform(get("/health")).andExpect(status().isOk());
    }

    @Test
    void unauthenticatedRequestsConsumeTheBucket() throws Exception {
        for (int i = 0; i < LIMIT; i++) {
            mockMvc.perform(get("/api/contacts")).andExpect(status().isUnauthorized());
        }

        mockMvc.perform(authed(get("/api/contacts"))).andExpect(status().isTooManyRequests());
    }

    @Test
    void bucketDrainsAsTimePasses() throws Exception {
        fillBucket();

        clock.advance(DRAIN_INTERVAL);
        mockMvc.perform(authed(get("/api/contacts"))).andExpect(status().isOk());
        mockMvc.perform(authed(get("/api/contacts"))).andExpect(status().isTooManyRequests());

        clock.advance(PERIOD);
        for (int i = 0; i < LIMIT; i++) {
            mockMvc.perform(authed(get("/api/contacts"))).andExpect(status().isOk());
        }
    }

    private void fillBucket() throws Exception {
        for (int i = 0; i < LIMIT; i++) {
            mockMvc.perform(authed(get("/api/contacts?limit=1"))).andExpect(status().isOk());
        }
    }

    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer test@example.com");
    }
}
