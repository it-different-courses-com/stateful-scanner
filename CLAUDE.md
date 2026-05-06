# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Spring Boot 4.0.3 web application using Java 25 (LTS). Standalone project with its own git repository. Uses Maven with wrapper for build management. Long-term goal is a stateful HTTP scanner driven by virtual-thread concurrency.

## Build and Run Commands

```bash
# Full CI-equivalent build (compile, checkstyle, tests, package)
./mvnw clean verify

# Build + install to the local Maven repo
./mvnw clean install

# Run the application
./mvnw spring-boot:run

# Run tests only
./mvnw test

# Run a single test class / method
./mvnw test -Dtest=ClassName
./mvnw test -Dtest=ClassName#methodName

# Lint only (checkstyle is also bound to the validate phase, so it runs on every build)
./mvnw checkstyle:check
```

CI (`.github/workflows/ci.yml`) runs `./mvnw clean verify` on Ubuntu with Temurin 25 — match it locally to avoid CI-only failures.

**Checkstyle gotcha:** `NewlineAtEndOfFile` requires every source file to end with a newline. Files written without one fail the `validate` phase before tests run.

## Code Conventions

- Package: `com.statefulscanner` — all components go in this package or sub-packages
- Java 25 features encouraged (records, sealed classes, pattern matching, virtual threads, unnamed variables `_`)
- Spring Boot 4.x conventions (constructor injection, `@RestController`, `application.yml`)
- H2 in-memory database for development (runtime scope)

## Architecture

```
com.statefulscanner/
├── StatefulScannerApplication.java   # Entry point
├── config/
│   ├── ExecutorConfig.java           # Virtual thread executor bean + graceful shutdown
│   └── HttpClientConfig.java         # java.net.http.HttpClient bean (HTTP/2, virtual threads)
├── core/
│   ├── UrlGenerator.java             # Combine base URL + wordlist entry → absolute URL
│   └── RequestQueue.java             # Bounded BlockingQueue<ScanRequest> with backpressure
├── model/
│   └── ScanRequest.java              # Immutable record — one unit of scan work
├── exception/
│   └── HttpRetryExhaustedException.java  # Thrown when retry attempts are exhausted
├── health/
│   └── VirtualThreadHealthIndicator.java  # HealthIndicator impl — exposed at /actuator/health
└── service/
    ├── HttpRequestService.java       # Async HTTP GET with retry (IOException/TimeoutException only)
    └── WordlistService.java          # Streaming wordlist file reader with dedup and CSV support
```

### Scanner pipeline (the big picture)

The scanner is a producer-consumer pipeline. Each stage owns one transformation and knows nothing about its neighbours:

```
filepath ──► WordlistService ──► Stream<String>
                                       │
                                       ▼
                                 UrlGenerator ──► ScanRequest
                                                       │
                                                       ▼
                                                 RequestQueue   (buffer + backpressure)
                                                       │
                                                       ▼
                                                 HttpRequestService ──► CompletableFuture<HttpResponse>
```

`ScanRequest` is the unit of work that flows from URL generation through the queue to HTTP execution. `RequestQueue` is the only stateful coordination primitive in the pipeline — everything else is a stateless transformation. Classes in `core/` are deliberately **not** Spring beans: their lifetime and parameters (base URL, queue capacity) are scan-specific, so an orchestrator service constructs them per scan instead.

### Concurrency model

Virtual threads via `Executors.newVirtualThreadPerTaskExecutor()`. One executor bean is shared across the app, with a 30-second graceful-shutdown window in `@PreDestroy`. **Avoid `synchronized` blocks** — use `java.util.concurrent` structures (`LinkedBlockingQueue`, `ReentrantLock`-backed primitives) so blocking calls unmount carrier threads instead of pinning them.

## Testing

- **Plain unit vs. Spring test:** instantiate classes directly when testing logic. `ExecutorConfigTest` is a plain unit test (no Spring context). Use `@SpringBootTest` only when verifying Spring wiring.
- **AssertJ** is the preferred assertion library.
- **Class-level `@Timeout(5, TimeUnit.SECONDS)`** is the project convention — caps any hung test.
- **`@Nested` groups** organize related cases inside large test classes (see `UrlGeneratorTest`, `RequestQueueTest`).
- **`@AfterEach` cleanup** for any closeable resource the test opens (streams, executors). Tests that leak `ExecutorService` instances cause flaky teardown.
- **Concurrency assertions:** verify "still blocked" with bounded `Future.get(timeout)` expecting `TimeoutException`, not `Thread.sleep + isDone()`. When asserting "exactly once" delivery, use code-assigned UUIDs (e.g. `ScanRequest.id()`) as the dedup key, not construction-unique strings.

## Dependencies

- `spring-boot-starter-web` — Spring MVC + embedded Tomcat + Jackson
- `spring-boot-starter-data-jpa` — Hibernate + Spring Data JPA
- `spring-boot-starter-actuator` — Health checks, metrics, `/actuator/health` endpoint
- `spring-boot-starter-test` — JUnit 5 + Mockito + AssertJ (test scope)
- `h2` — In-memory database (runtime scope)
- Maven plugins: `maven-checkstyle-plugin` (validate phase), `jacoco-maven-plugin` (test phase coverage report)
