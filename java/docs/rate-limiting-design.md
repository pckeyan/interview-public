# Design: Leaky Bucket Request Rate Limiting

**Project:** CRM API Server (Java / Spring Boot implementation)
**Status:** Implemented
**Date:** 2026-11-11

## 1. Requirements

Implement a basic request rate limiting solution using a Leaky Bucket algorithm.

- Process-local rate limiter. No distributed state.
- The limit applies globally across all clients, not per client.
- Allow 10 requests per minute.
- Keep the implementation clean, readable, and maintainable.
- Include appropriate tests and documentation.

## 2. Context: how the existing code is structured

The Java service is a small Spring Boot 3.3 application (Java 17 target) laid out
under `com.weavelab.interview`:

| Package      | Role                                                        |
|--------------|-------------------------------------------------------------|
| `api`        | `@RestController` classes, one per resource                 |
| `repository` | `JdbcTemplate`-backed data access against SQLite            |
| `model`      | Plain POJOs with Jackson `@JsonProperty` snake_case mapping  |
| `config`     | `AppConfig`, `DataSourceConfig`, `WebConfig`                |
| `auth`       | `AuthFilter` (parses bearer token), `AuthInterceptor` (enforces on `/api/*`) |
| `seed`       | Data seeding and the startup check                          |

Conventions the rate limiter must follow:

- **Cross-cutting concerns are servlet filters and interceptors.** `AuthFilter` is a
  `@Component @Order(1) Filter`; `AuthInterceptor` is registered in `WebConfig`.
- **Error bodies are hand-written JSON** of the form `{"error":"..."}` with an explicit
  `application/json` content type.
- **Configuration is flat `app.*` properties.** Optional behaviour toggles with
  `@ConditionalOnProperty` (see `seed/StartupCheck`).
- **Only `/api/*` is protected.** `GET /health` sits outside that tree.
- **Tests** use `@SpringBootTest` with `target/test-data` properties and
  `app.startup-check=false`. `spring-boot-starter-test` already provides MockMvc,
  JUnit 5, AssertJ and Mockito, so **no new dependencies are required**.
- **Toolchain.** The project targets Java 17. On the development machine the default
  JDK is 11, so builds must run with a 17+ JDK, for example:

  ```bash
  export JAVA_HOME=/Users/karthik/Library/Java/JavaVirtualMachines/openjdk-26.0.2/Contents/Home
  ./mvnw test
  ```

  Code must remain Java 17 compatible: records and `instanceof` patterns are fine,
  virtual threads and other 21+ features are not.

## 3. Algorithm

### 3.1 Leaky bucket as a meter

There are two things people call a "leaky bucket":

1. **Queue variant (traffic shaping).** Requests are enqueued and released at a fixed
   rate. This parks request threads and smooths traffic rather than rejecting it.
2. **Meter variant (rate limiting).** Each arriving request pours one unit of water into
   a bucket of fixed capacity. Water drains at a constant rate. If the request would
   overflow the bucket, it is rejected.

This design uses the **meter variant**. It is the form used by NGINX `limit_req` and is
the correct fit for an HTTP API that should answer `429 Too Many Requests` instead of
blocking.

### 3.2 State and arithmetic

The bucket keeps two values, guarded by a lock:

- `waterNanos`: the current water level, expressed in **nanoseconds of drain time**.
- `lastUpdateNanos`: the monotonic timestamp of the last update.

Derived constants from configuration:

- `drainIntervalNanos = period / requests` (6 s for 10 per minute). One request adds
  exactly this much water.
- `capacityNanos = capacity * drainIntervalNanos` (60 s for capacity 10).

On each `tryAcquire()`:

```text
now      = clock.nanoTime()
leaked   = now - lastUpdateNanos
water    = max(0, water - leaked)          // drain since last visit
lastUpdateNanos = now

if water + drainIntervalNanos <= capacityNanos:
    water += drainIntervalNanos            // admit, pour one unit
    return ALLOWED
else:
    wait = water + drainIntervalNanos - capacityNanos
    return REJECTED(retryAfter = wait)
```

Why this representation:

- **Integer arithmetic only.** No floating-point level that drifts over time.
- **Leak is just elapsed time.** No background thread, no scheduler; the bucket is
  updated lazily on access.
- **Monotonic clock.** `System.nanoTime()` is injected as a `LongSupplier`. A wall-clock
  `Instant` would be corrupted by NTP adjustments, and tests need a controllable clock.

### 3.3 Burst semantics

With `capacity = 10` and a drain of 10 per minute, an idle bucket admits a burst of 10
requests at once, then one request every six seconds. Across any 60-second window the
maximum admitted is the burst plus the steady drain, so up to 20 in the pathological
case of a burst immediately after a full refill. This is the inherent and well-understood
behaviour of leaky and token buckets, and the standard reading of "10 requests per minute".

A strict, no-burst interpretation is obtained with `capacity = 1`, which enforces a
minimum spacing of six seconds between requests. Capacity is therefore a separate
property that defaults to the request limit, so the choice is explicit and reversible
without code changes.

### 3.4 Concurrency

`tryAcquire()` is a short `synchronized` method. A lock-free formulation (generalised
cell rate algorithm on a single `AtomicLong` with compare-and-set) was considered and
rejected: it is harder to read and reason about, and at 10 requests per minute
contention on the lock is irrelevant. Correctness under concurrency is trivially
provable with the lock.

### 3.5 Public API

```java
public final class LeakyBucketRateLimiter {

    public record Decision(boolean allowed, Duration retryAfter) { }

    public LeakyBucketRateLimiter(int requests, Duration period, int capacity, LongSupplier nanoClock)

    /** Attempts to admit one request. Never blocks. */
    public Decision tryAcquire()
}
```

A single call returns both the verdict and the wait until the next permit. Splitting
this into `tryAcquire()` plus a separate `retryAfter()` would be racy under load.

## 4. HTTP integration

### 4.1 Placement in the request pipeline

A `OncePerRequestFilter` named `RateLimitFilter`, registered as a `@Component` with
`@Order(0)` so it runs **before** `AuthFilter` (`@Order(1)`).

Rationale:

- A **servlet filter**, not a `HandlerInterceptor`, rejects before `DispatcherServlet`
  does any work. In particular it avoids multipart parsing on rejected uploads of up
  to 100 MB.
- Running **before authentication** is correct because the limit is global and needs
  no user identity. It also protects the service from unauthenticated floods.
- `OncePerRequestFilter` guarantees a request is counted once even across error or
  forward dispatches.

### 4.2 Scope

Only `/api/*` is limited, implemented via `shouldNotFilter()`. `GET /health` is excluded
so load-balancer and orchestrator probes cannot exhaust the bucket. All HTTP methods on
`/api/*` count equally.

### 4.3 Rejection response

```http
HTTP/1.1 429 Too Many Requests
Content-Type: application/json
Retry-After: 6

{"error":"rate limit exceeded"}
```

- `Retry-After` is in whole seconds, rounded up, with a minimum of 1, per RFC 9110.
- The body matches the service's existing `{"error":"..."}` convention.
- The IETF `RateLimit-*` draft headers are out of scope for this change.

## 5. Configuration

A `@ConfigurationProperties(prefix = "app.rate-limit")` record, `RateLimitProperties`,
enabled from `RateLimitConfig`. A record is preferred over more `@Value` fields because
it is type-safe, validates on startup, and binds `Duration` natively.

| Property                      | Default            | Meaning                               |
|-------------------------------|--------------------|---------------------------------------|
| `app.rate-limit.enabled`      | `true`             | Register the filter at all            |
| `app.rate-limit.requests`     | `10`               | Requests drained per period           |
| `app.rate-limit.period`       | `1m`               | Drain period                          |
| `app.rate-limit.capacity`     | same as `requests` | Maximum burst size                    |

Validation: `requests >= 1`, `capacity >= 1`, `period > 0`. Invalid values fail
application startup with a clear message.

`enabled=false` removes the filter entirely via `@ConditionalOnProperty`, matching the
pattern used by `StartupCheck`.

## 6. Files

### New

| Path                                                                        | Purpose                                        |
|-----------------------------------------------------------------------------|------------------------------------------------|
| `src/main/java/com/weavelab/interview/ratelimit/LeakyBucketRateLimiter.java` | Pure algorithm, no Spring dependency           |
| `src/main/java/com/weavelab/interview/ratelimit/RateLimitProperties.java`    | `@ConfigurationProperties` record              |
| `src/main/java/com/weavelab/interview/ratelimit/RateLimitConfig.java`        | Enables properties, builds the limiter bean with `System::nanoTime` |
| `src/main/java/com/weavelab/interview/ratelimit/RateLimitFilter.java`        | `OncePerRequestFilter`, 429 handling            |
| `src/test/java/com/weavelab/interview/ratelimit/MutableNanoClock.java`         | Controllable clock shared by the tests          |
| `src/test/java/com/weavelab/interview/ratelimit/LeakyBucketRateLimiterTest.java` | Unit tests for the algorithm               |
| `src/test/java/com/weavelab/interview/ratelimit/RateLimitFilterTest.java`    | Unit tests for the filter with servlet mocks   |
| `src/test/java/com/weavelab/interview/ratelimit/RateLimitPropertiesTest.java` | Unit tests for capacity defaulting            |
| `src/test/java/com/weavelab/interview/ratelimit/RateLimitFilterIntegrationTest.java` | MockMvc tests on the full Spring context |
| `README.md`                                                                  | Java-specific README incl. rate limiting docs  |
| `docs/rate-limiting-design.md`                                               | This document                                  |

### Modified

| Path                                       | Change                                            |
|--------------------------------------------|---------------------------------------------------|
| `src/main/resources/application.properties` | Add the four `app.rate-limit.*` properties        |
| `../README.md`                             | One-line pointer to `java/README.md`              |

No changes to `pom.xml`. The Go implementation is untouched.

## 7. Testing strategy

### 7.1 Unit tests: `LeakyBucketRateLimiterTest`

Deterministic, using a mutable fake `LongSupplier` clock.

- Admits exactly `capacity` requests from an idle bucket; the next is rejected.
- After one drain interval (6 s), exactly one more request is admitted.
- After a partial interval (e.g. 5.9 s), nothing is admitted.
- After a full period (60 s), the bucket is empty and admits `capacity` again.
- Long idle time never accumulates credit beyond `capacity`.
- `retryAfter` on rejection equals the time until the next permit.
- Constructor rejects `requests < 1`, `capacity < 1`, non-positive `period`.
- Concurrency: with a frozen clock, many threads calling `tryAcquire()` concurrently
  are admitted exactly `capacity` times in total.

### 7.2 Unit tests: `RateLimitFilterTest` and `RateLimitPropertiesTest`

Servlet-mock tests with no Spring context, using `MockHttpServletRequest`,
`MockHttpServletResponse` and `MockFilterChain` from `spring-test`.

- Requests reach the chain while under the limit; the next one does not.
- Rejection sets status 429, `Retry-After`, `application/json` in UTF-8, and the error body.
- Paths outside `/api/` (`/health`, `/`, `/error`, `/apiary`) bypass the limiter.
- Advancing the clock by one drain interval admits one more request.
- `retryAfterSeconds` rounds up to whole seconds with a floor of one (parameterised).
- `effectiveCapacity()` defaults to `requests` and honours an explicit value.

### 7.3 Integration tests: `RateLimitFilterIntegrationTest`

`@SpringBootTest` + `@AutoConfigureMockMvc`, mirroring the existing test properties
(`target/test-data`, `app.startup-check=false`). A `@TestConfiguration` supplies a
`@Primary` limiter built on the fake clock so tests can advance time.

- The eleventh request to `/api/contacts` within the window returns 429 with the JSON
  body and a `Retry-After` header.
- `/health` is never limited, even when the bucket is full.
- Unauthenticated `/api/*` requests also consume the bucket (filter runs before auth).
- Advancing the fake clock by a full period drains the bucket and requests succeed again.
- Existing `ApplicationTests.contextLoads` continues to pass.

### 7.4 Manual verification

```bash
cd java && make run
for i in $(seq 1 12); do
  curl -s -o /dev/null -w "%{http_code}\n" \
    -H "Authorization: Bearer user@example.com" \
    http://localhost:8080/api/contacts?limit=1
done
```

Expected: ten `200` responses followed by `429`. Inspect headers on a rejected call
with `curl -i` to see `Retry-After`.

## 8. Operational notes and limitations

- **Process-local.** Each JVM instance has its own bucket. Behind a load balancer with
  N instances the effective global limit is N times the configured value. A shared
  store (for example Redis with a Lua script) would be required for a true cluster-wide
  limit; that is explicitly out of scope.
- **Global, not per-client.** One abusive client can starve all others. Per-client
  buckets keyed by the bearer token would be a small extension of the same limiter
  class but are not part of this requirement.
- **No persistence.** The bucket resets on restart, which is acceptable for this
  purpose.
- **Memory and CPU cost** are constant: two `long` fields and one lock.

## 9. Implementation order

1. `LeakyBucketRateLimiter` and its unit tests.
2. `RateLimitProperties`, `RateLimitConfig`, and `application.properties` entries.
3. `RateLimitFilter`.
4. `RateLimitFilterTest`.
5. README section and Javadoc.
6. Full `./mvnw test` under a 17+ JDK, manual curl verification, review pass.
