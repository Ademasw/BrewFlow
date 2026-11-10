# ADR-013 — versions that compiled and passed (Sprint 1 record)

Date: 2026-11-02 · State: recorded by the developer, not owner-approved.

Spring Boot 3.5 open-source support ended on 30 June 2026, so the backend uses the maintained Spring Boot 4.1 line.
Versions below are the ones the build actually resolved and the tests ran against on 2026-11-02.

| Layer | Spec candidate | Recorded version | Note |
|---|---|---|---|
| Runtime | Java 21 LTS | Temurin 21.0.11 | |
| Backend | Spring Boot 4.1.x latest patch | 4.1.2 | Spring Framework 7.0.10, Spring Security 7.1.2, Tomcat 11.0.27 |
| JSON | Jackson 3 (Boot 4 default) | tools.jackson 3.1.7 | Boot 3.x `com.fasterxml.jackson.databind` snippets not used |
| Database | PostgreSQL 18 | 18.0 (x86_64-windows) | local cluster from `scripts/dev-db.sh` |
| API docs | — | springdoc-openapi 3.2.0 (Swagger UI 5.34.0) | built against Spring Boot 4.1.2 |
| Migrations | Flyway managed by Boot | 12.4.0 | V1–V4 |
| JDBC | managed by Boot | pgjdbc 42.7.14, HikariCP 7.0.2 | |
| Build | Maven Wrapper (recommended) | wrapper 3.3.2 → Maven 3.9.11 | only one wrapper; no Gradle files |
| Tests (backend) | JUnit 5, Testcontainers postgres:18 | JUnit Jupiter 6.0.3 (Boot 4.1 BOM), real PostgreSQL 18 | see deviation 1 |
| Web | Next.js 16.x, React, TypeScript, Tailwind | Next.js 16.4.3, React 19.3.0, TypeScript 5.9.3, Tailwind 4.3.3 | App Router, Turbopack |
| Node | Node.js 24 LTS | **22.19.0 locally**, 24 in CI | see deviation 2 |

## Deviations

1. **No Docker on the development machine**, so Testcontainers cannot start `postgres:18`. Every integration test still
   runs on a real PostgreSQL 18 (local cluster, `127.0.0.1:55432`, database `brewflow_test`); the CI workflow uses the
   `postgres:18` service container. The section 1 rule "real PostgreSQL for every transaction and concurrency test" holds.
2. **Node.js 22.19.0 locally** instead of 24 LTS. Next.js 16 needs Node 20.9 or newer, so the build passes; CI pins 24.
3. Boot 4 renamed starters (`spring-boot-starter-webmvc`, `spring-boot-starter-flyway`); the pom uses the Boot 4 names.
4. Spring Security's generated in-memory user is switched off (`UserDetailsServiceAutoConfiguration` excluded), because
   the spec forbids any default credential.
