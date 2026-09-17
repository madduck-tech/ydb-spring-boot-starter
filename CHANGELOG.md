# Changelog

## 0.1.0 — 2026-09-17

First source release. Package publication is not configured yet.

- Three modules: Spring integration, Boot auto-configuration, and the dependency-only starter.
- Native SDK beans, explicit authentication selection, builder customizers, and optional health checks.
- Synchronous `YdbTemplate` with typed SDK parameters and bounded materialized results.
- Spring `@Transactional` with `REQUIRED`, `REQUIRES_NEW`, suspend/resume, rollback-only propagation, bounded operation deadlines, and an explicit unknown-commit outcome. No automatic transaction retries.
- Inactive SDK transactions are rejected before query dispatch. Late SDK responses retain ownership of sessions until terminal cleanup; health cancellation cannot recycle a session while cancellation or execution is in progress.
- Java 17 bytecode; CI tests Java 17/21/25 and Gradle/Maven consumers on Spring Boot 4.0.8/4.1.1 against pinned YDB 26.1.1.22 with SDK 2.4.11.

Limits: serializable read/write database transactions only; read-only is a hint; savepoints, cross-database transactions, and asynchronous propagation are unsupported. Direct SDK calls do not join Spring transactions. Cloud-provider combinations and network fault injection are not fully verified. Dependency vulnerability scanning, signing, and Maven Central publication remain future work; the initial CI gate does not claim to cover them.
