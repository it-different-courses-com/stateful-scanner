# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Spring Boot 4.0.3 web application using Java 25 (LTS). Standalone project with its own git repository. Uses Maven with wrapper for build management.

## Build and Run Commands

```bash
# Build and run tests
./mvnw clean install

# Run the application
./mvnw spring-boot:run

# Run tests only
./mvnw test

# Run a single test class
./mvnw test -Dtest=ClassName

# Run a single test method
./mvnw test -Dtest=ClassName#methodName
```

## Code Conventions

- Package: `com.statefulscanner` — all components go in this package or sub-packages
- Java 25 features encouraged (records, sealed classes, pattern matching, virtual threads)
- Spring Boot 4.x conventions (constructor injection, `@RestController`, `application.yml`)
- H2 in-memory database for development (runtime scope)

## Architecture

```
com.statefulscanner/
├── StatefulScannerApplication.java   # Entry point
├── config/
│   ├── ExecutorConfig.java           # Virtual thread executor bean + graceful shutdown
│   ├── HttpClientConfig.java         # java.net.http.HttpClient bean (HTTP/2, virtual threads)
│   ├── RateLimiterProperties.java    # @ConfigurationProperties record bound to scanner.rate-limiter.* (range [1, 10000], finite-only)
│   └── RateLimiterConfig.java        # Builds the Guava RateLimiter bean (smooth-bursty, or smooth-warming-up when warmupPeriodSeconds > 0)
├── exception/
│   └── HttpRetryExhaustedException.java  # Thrown when retry attempts are exhausted
├── health/
│   └── VirtualThreadHealthIndicator.java  # HealthIndicator impl — exposed at /actuator/health
└── service/
    ├── HttpRequestService.java       # Async HTTP GET with retry (IOException/TimeoutException only)
    ├── RateLimiterService.java       # Wraps Guava RateLimiter — hides @Beta type, validates inputs, exposes acquire/tryAcquire/setRate
    └── WordlistService.java          # Streaming wordlist file reader with dedup and CSV support
```

**Concurrency model:** Virtual threads via `Executors.newVirtualThreadPerTaskExecutor()`. One executor bean shared across the app. Graceful shutdown with 30-second timeout in `@PreDestroy`. Avoid `synchronized` blocks — use `java.util.concurrent` structures to prevent virtual thread pinning.

**Testing approach:** `ExecutorConfigTest` is a plain unit test (no Spring context) — instantiates `ExecutorConfig` directly. Use `@SpringBootTest` only when testing Spring wiring. AssertJ is the preferred assertion library. Tests that mock final or abstract classes (e.g. Guava `RateLimiter`) use `@ExtendWith(MockitoExtension.class)` + `@Mock`; the Surefire `argLine` in `pom.xml` loads `byte-buddy-agent` as a `-javaagent` so Mockito does not self-attach (silences the JDK 25 dynamic-agent warning).

**Rate limiting:** `--rate=N` CLI shorthand maps to `scanner.rate-limiter.permits-per-second` via a `${rate:100.0}` placeholder in `application.yml`. Range is enforced in `RateLimiterProperties`'s canonical constructor (1–10000, finite-only). Guava's `RateLimiter` is `@Beta` — keep all references to the type confined to `RateLimiterConfig` and `RateLimiterService` so it can be swapped (e.g. for Resilience4j) without touching callers.

## Dependencies

- `spring-boot-starter-web` — Spring MVC + embedded Tomcat + Jackson
- `spring-boot-starter-data-jpa` — Hibernate + Spring Data JPA
- `spring-boot-starter-actuator` — Health checks, metrics, `/actuator/health` endpoint
- `spring-boot-starter-test` — JUnit 5 + Mockito + AssertJ (test scope)
- `byte-buddy-agent` — Loaded as `-javaagent` by Surefire so Mockito can mock abstract/final classes without self-attaching (test scope)
- `guava` (`33.0.0-jre`) — Provides `RateLimiter` for request pacing
- `h2` — In-memory database (runtime scope)