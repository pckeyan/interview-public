# CRM API Server (Java)

Spring Boot 3.3 implementation of the CRM API server. It shares the SQLite schema and
REST interface documented in the [top-level README](../README.md) with the Go version.

## Prerequisites

- JDK 17 or newer. The Maven wrapper (`./mvnw`) downloads Maven itself, but it uses
  whatever `JAVA_HOME` points to. If your default JDK is older than 17, set it explicitly:

  ```bash
  export JAVA_HOME=/path/to/jdk-17-or-newer
  ```

## Build, run, test

```bash
make seed    # one-time: seed the database with test data
make run     # start the server on http://localhost:8080
make test    # run the test suite
make build   # produce target/interview-1.0.0-SNAPSHOT.jar
make reset   # wipe data/ and reseed
```

Without `make`:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments="--app.seed=true"
./mvnw spring-boot:run
./mvnw test
```

## Project layout

```
src/main/java/com/weavelab/interview/
  api/          REST controllers, one per resource
  auth/         Bearer token parsing (filter) and enforcement on /api/* (interceptor)
  config/       Application, data source and MVC configuration
  model/        Plain data objects with snake_case JSON mapping
  ratelimit/    Global leaky-bucket request rate limiter (see below)
  repository/   JdbcTemplate data access against SQLite
  seed/         Test data seeding and the startup check
src/main/resources/
  application.properties
  schema.sql
```

## Configuration

All settings live in `src/main/resources/application.properties` and can be overridden
on the command line, for example `--server.port=9090`.

| Property                  | Default            | Description                                  |
|---------------------------|--------------------|----------------------------------------------|
| `server.port`             | `8080`             | Server listen port                           |
| `app.data-dir`            | `data`             | Directory for the database and uploaded files |
| `app.max-upload-size`     | `104857600`        | Maximum file upload size in bytes (100 MB)   |
| `app.rate-limit.enabled`  | `true`             | Register the request rate limiting filter    |
| `app.rate-limit.requests` | `10`               | Requests drained per period                  |
| `app.rate-limit.period`   | `1m`               | Drain period                                 |
| `app.rate-limit.capacity` | same as `requests` | Maximum burst size; `1` for strict spacing   |

## Rate limiting

Every request under `/api/*` passes through a global, process-local **Leaky Bucket** rate
limiter. The limit is shared by all clients rather than applied per client. `/health` is
never limited, so load balancer probes cannot exhaust the bucket.

### Behaviour

By default the bucket admits **10 requests per minute**. Think of each request as pouring
one unit of water into a bucket that holds ten units and leaks at one unit every six
seconds:

- An idle bucket accepts a burst of up to 10 requests.
- After that, one request is admitted every six seconds as the bucket drains.
- A request that would overflow the bucket is rejected immediately. Nothing is queued.
- Idle time never builds up credit beyond the bucket's capacity.
- Every admitted request counts, whatever its outcome. A 400, 404 or 401 uses capacity
  just like a 200.
- The limiter runs before authentication, so a caller with no token or an invalid one
  receives 429 rather than 401 while the bucket is full.

Set `app.rate-limit.capacity=1` to forbid bursts entirely and enforce a strict six-second
spacing between requests.

### Rejected requests

```http
HTTP/1.1 429 Too Many Requests
Content-Type: application/json;charset=UTF-8
Retry-After: 6

{"error":"rate limit exceeded"}
```

`Retry-After` is the number of whole seconds, rounded up, until the next request will be
admitted.

### Try it

With the server running:

```bash
for i in $(seq 1 12); do
  curl -s -o /dev/null -w "%{http_code}\n" \
    -H "Authorization: Bearer user@example.com" \
    "http://localhost:8080/api/contacts?limit=1"
done
# 200 x10, then 429 x2
```

### How it is wired

| Class                     | Role                                                                  |
|---------------------------|-----------------------------------------------------------------------|
| `LeakyBucketRateLimiter`  | The algorithm. Plain Java, no Spring dependency, injectable clock      |
| `RateLimitProperties`     | Type-safe binding of `app.rate-limit.*`                                |
| `RateLimitConfig`         | Builds the limiter bean on `System.nanoTime()`                          |
| `RateLimitFilter`         | Servlet filter at order 0, before auth, scoped to `/api/*`, emits 429  |

The filter runs before authentication because the limit is global and needs no user
identity. Being a servlet filter rather than an MVC interceptor, it rejects before the
dispatcher does any work, including multipart parsing of large uploads.

### Limitations

- **Process-local.** Each JVM has its own bucket. With N instances behind a load balancer
  the effective global limit is N times the configured value.
- **Global.** One abusive client can starve all others. Per-client buckets keyed by the
  bearer token would be a small extension of the same limiter class.
- **Not persisted.** The bucket resets on restart.

## Tests

```bash
./mvnw test
```

| Test class                        | Kind        | Covers                                                        |
|-----------------------------------|-------------|---------------------------------------------------------------|
| `LeakyBucketRateLimiterTest`      | Unit        | Admission, drain timing, retry-after, validation, concurrency |
| `RateLimitFilterTest`             | Unit        | Path scoping, 429 response shape, Retry-After rounding        |
| `RateLimitPropertiesTest`         | Unit        | Capacity defaulting                                           |
| `RateLimitFilterIntegrationTest`  | Integration | Full Spring context with MockMvc and a fake clock             |
| `ApplicationTests`                | Integration | Context loads                                                 |

Unit tests drive the limiter with a controllable clock, so they are deterministic and run
in milliseconds. No real waiting is involved.
