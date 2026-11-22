package com.weavelab.interview.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Applies the global {@link LeakyBucketRateLimiter} to every request under {@code /api/}.
 *
 * <p>Runs before {@link com.weavelab.interview.auth.AuthFilter} (order 1): the limit is
 * global rather than per user, so no identity is needed, and rejecting early also shields
 * the service from unauthenticated floods. Being a servlet filter, it rejects before the
 * dispatcher does any work such as multipart parsing of large uploads.
 *
 * <p>Rejected requests receive {@code 429 Too Many Requests} with a {@code Retry-After}
 * header in whole seconds and a JSON body in the service's usual error shape.
 */
@Component
@Order(0)
@ConditionalOnProperty(name = "app.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitFilter extends OncePerRequestFilter {

    static final String LIMITED_PATH_PREFIX = "/api/";
    static final String ERROR_BODY = "{\"error\":\"rate limit exceeded\"}";

    private final LeakyBucketRateLimiter rateLimiter;

    public RateLimitFilter(LeakyBucketRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    /**
     * Rounds up to whole seconds, with a floor of one, as clients expect for Retry-After.
     */
    static long retryAfterSeconds(Duration retryAfter) {
        long seconds = retryAfter.plusNanos(999_999_999).toSeconds();
        return Math.max(1, seconds);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(LIMITED_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        LeakyBucketRateLimiter.Decision decision = rateLimiter.tryAcquire();
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds(decision.retryAfter())));
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.getWriter().write(ERROR_BODY);
    }
}
