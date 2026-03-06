# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Spring Boot 3.2+ web application using Java 21, part of a Java learning repository (`lesson1/stateful-scanner`). Uses Maven for build management.

## Build and Run Commands

```bash
# Build the project (from project root: lesson1/stateful-scanner/)
./mvnw clean package

# Run the application
./mvnw spring-boot:run

# Run tests
./mvnw test

# Run a single test class
./mvnw test -Dtest=ClassName

# Run a single test method
./mvnw test -Dtest=ClassName#methodName

# Skip tests during build
./mvnw package -DskipTests
```

## Code Conventions

- Package naming: `com.samuil.statefulscanner` (consistent with parent repo pattern `com.samuil.<taskname>`)
- Java 21 features encouraged (records, sealed classes, pattern matching, virtual threads)
- Spring Boot 3.2+ conventions (constructor injection, `@RestController`, `application.properties` or `application.yml`)
- Standard Maven directory layout: `src/main/java`, `src/main/resources`, `src/test/java`
