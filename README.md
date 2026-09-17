# YDB Spring Boot Starter

Community integration of the native YDB Java SDK with Spring Boot: SDK beans, a synchronous `YdbTemplate`, and Spring `@Transactional` support.

The first source release is **0.1.0**. Maven Central publication and namespace verification are pending; the artifacts are currently built locally. The target is Java 17, 21, and 25 with Spring Boot 4.0/4.1. Library bytecode targets Java 17. See the [changelog](CHANGELOG.md) for the release scope.

The initial implementation has been exercised with SDK 2.4.11 and YDB 26.1.1.22. A standalone Gradle consumer verified commit/rollback from the same built artifacts on every Java 17/21/25 and Boot 4.0.8/4.1.1 combination. Maven consumption was also checked on both Boot lines. These checks cover the scenarios in this repository, not every authentication provider or failure mode in the release plan.

## Modules

| Module | Contents |
| --- | --- |
| `ydb-spring` | Template, bounded result mapping, Spring transaction manager; no Boot dependency |
| `ydb-spring-boot` | Properties, SDK lifecycle, bean overrides, optional health |
| `ydb-spring-boot-starter` | Dependency set for applications |

Proposed coordinates: `io.github.madduck-tech:ydb-spring-boot-starter`. Apache License 2.0.

## Configuration

```yaml
ydb:
  connection-string: grpc://localhost:2136/local
  auth:
    mode: anonymous
```

Use `grpcs://` for TLS. Authentication modes are `anonymous`, `token`, and `environment`; a user `tech.ydb.auth.AuthProvider` bean takes precedence. Environment mode follows the SDK's credential selection and may require optional cloud/OAuth provider dependencies. Pass token credentials through external configuration.

The path-form connection string is required; the older `?database=` form is rejected. Missing connection settings fail only when the starter needs to create a transport. Supplying a `QueryClient` avoids creation of another client or transport. `ydb.enabled=false` disables all default YDB beans.

See [configuration and architecture](docs/design.md) for proposed defaults and [the transaction contract](docs/transactions.md) for semantics. Design documents also include release criteria that are not yet all verified.

Optional health activates when Spring Boot health classes are present and honors `management.health.ydb.enabled`. The base starter does not pull in Actuator. The indicator uses a separate SDK session, a bounded probe deadline, and single-flight execution.

## Transactions

Inject `YdbOperations` for synchronous parameterized calls. Invoke a Spring service method through its proxy:

```java
@Transactional(transactionManager = "ydbTransactionManager")
public void updateAccounts() {
    ydb.execute(firstQuery, firstParams);
    ydb.execute(secondQuery, secondParams);
}
```

The calls share a transaction when the template and manager use the same query-client instance. Outside a Spring YDB transaction, each template call owns a short transaction. Direct SDK calls are independent and do not automatically participate.

Supported isolation is `DEFAULT`/`SERIALIZABLE`. Read-only is a hint. Savepoints are unsupported; `NESTED` with an existing transaction fails. `REQUIRES_NEW` needs an additional pool session. Transactions do not propagate to asynchronous work. There are no automatic query or transaction retries; an unknown commit outcome has a distinct exception.

With another transaction manager present, use `ydb.transactions.mode=enabled` and select the manager explicitly. Automatic mode preserves the existing manager selection. Incompatible rollback-on-commit-failure customization is rejected.

Materialized query results default to 10,000 rows and 16 MiB of serialized parts; at most 1,024 result sets are supported. Mapping occurs on the caller thread. Large streaming workloads should use the native SDK.

## Build and run

Install Java 17 for the compiler toolchain and the desired runtime JDK. Build locally:

```sh
./gradlew build
./gradlew publishAllPublicationsToBuildRepositoryRepository
docker compose up -d --wait --wait-timeout 180
./gradlew --refresh-dependencies -p samples/minimal run
```

The example consumes published files from `build/repository`, creates a uniquely named temporary table, verifies commit and rollback, and drops that table. The pinned local YDB container binds only to localhost; its amd64 image runs under emulation on ARM hosts. It stores test data in memory.

Run the real database test and select a JVM:

```sh
YDB_TEST_CONNECTION=grpc://localhost:2136/local ./gradlew test -PtestJava=17
./gradlew --refresh-dependencies -p samples/minimal run -PruntimeJava=21 -PbootVersion=4.1.1
mvn -f samples/minimal/pom.xml compile exec:java
docker compose down
```

Without `YDB_TEST_CONNECTION`, the real database test is skipped; unit and configuration tests still run.

## Pull request checks

[GitHub Actions](.github/workflows/ci.yml) runs on every pull request, push to `main` or a `v*` tag, merge queue entry, and manual dispatch:

- Actionlint validates workflows; Docker Compose configuration and commit whitespace are checked.
- Gradle validates the wrapper, builds all modules including Javadoc, and runs all tests against a real YDB container on Java 17, 21, and 25. CI fails on missing reports or skipped tests.
- The Java 17 build publishes a temporary Maven repository. Independent Gradle and Maven consumers verify commit/rollback using those same artifacts on all six Java 17/21/25 × Spring Boot 4.0.8/4.1.1 combinations.
- Test reports and YDB/consumer logs are retained for seven days. Superseded runs are cancelled.

The stable aggregate check is **CI passed**. Select it as a required status check in the GitHub branch ruleset for `main` to block merging when any job fails or is skipped. The workflow does not configure repository rules or publish to Maven Central.

Consumer CI refreshes Gradle dependencies and uses an empty Maven local repository so that a previous build of the same release version cannot pass in place of the current artifacts. When repeatedly rebuilding the same version locally, also select a fresh Maven local repository with `-Dmaven.repo.local=...`.

To reproduce the build check locally, start YDB as above, then run:

```sh
YDB_TEST_CONNECTION=grpc://localhost:2136/local ./gradlew clean build -PtestJava=17
python3 scripts/ci/check-test-results.py
```

Release verification beyond the current tests is tracked in [the implementation criteria](docs/implementation-plan.md). Native-image, Boot 3, repositories, and cross-database transactions are outside the initial release scope.
