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

## Dependencies

- `spring-boot-starter-web` — Spring MVC + embedded Tomcat + Jackson
- `spring-boot-starter-data-jpa` — Hibernate + Spring Data JPA
- `spring-boot-starter-test` — JUnit 5 + Mockito (test scope)
- `h2` — In-memory database (runtime scope)