package com.weavelab.interview.ratelimit;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link RateLimitFilter} using servlet mocks; no Spring context. */
class RateLimitFilterTest {

    private static final int LIMIT = 10;

    private MutableNanoClock clock;
    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        clock = new MutableNanoClock();
        filter = new RateLimitFilter(new LeakyBucketRateLimiter(LIMIT, Duration.ofMinutes(1), LIMIT, clock));
    }

    @Test
    void passesRequestsThroughWhileUnderTheLimit() throws Exception {
        for (int i = 0; i < LIMIT; i++) {
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(apiRequest(), response, chain);

            assertThat(chain.getRequest()).as("request %d reached the chain", i + 1).isNotNull();
            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        }
    }

    @Test
    void rejectsWithTooManyRequestsOnceTheBucketIsFull() throws Exception {
        fillBucket();
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(apiRequest(), response, chain);

        assertThat(chain.getRequest()).as("rejected request must not reach the chain").isNull();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("6");
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getCharacterEncoding()).isEqualToIgnoringCase("UTF-8");
        assertThat(response.getContentAsString()).isEqualTo(RateLimitFilter.ERROR_BODY);
    }

    @Test
    void ignoresPathsOutsideTheApiTree() throws Exception {
        fillBucket();

        for (String path : new String[] {"/health", "/", "/apiary", "/error"}) {
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(new MockHttpServletRequest("GET", path), response, chain);

            assertThat(chain.getRequest()).as("%s should bypass the limiter", path).isNotNull();
            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        }
    }

    @Test
    void admitsAgainAfterTheBucketDrains() throws Exception {
        fillBucket();

        clock.advance(Duration.ofSeconds(6));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(apiRequest(), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @ParameterizedTest(name = "{0} -> Retry-After {1}")
    @CsvSource({
            "PT0S,     1",
            "PT0.001S, 1",
            "PT1S,     1",
            "PT1.001S, 2",
            "PT5.999S, 6",
            "PT6S,     6",
            "PT59.5S,  60"
    })
    void retryAfterRoundsUpToWholeSecondsWithFloorOfOne(String wait, long expectedSeconds) {
        assertThat(RateLimitFilter.retryAfterSeconds(Duration.parse(wait))).isEqualTo(expectedSeconds);
    }

    private void fillBucket() throws Exception {
        for (int i = 0; i < LIMIT; i++) {
            filter.doFilter(apiRequest(), new MockHttpServletResponse(), new MockFilterChain());
        }
    }

    private static MockHttpServletRequest apiRequest() {
        return new MockHttpServletRequest("GET", "/api/contacts");
    }
}
