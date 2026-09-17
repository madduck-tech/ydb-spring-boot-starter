# YDB Spring Boot Starter

[![CI](https://github.com/madduck-tech/ydb-spring-boot-starter/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/madduck-tech/ydb-spring-boot-starter/actions/workflows/ci.yml)
[![Dependencies](https://github.com/madduck-tech/ydb-spring-boot-starter/actions/workflows/dependencies.yml/badge.svg?branch=main)](https://github.com/madduck-tech/ydb-spring-boot-starter/actions/workflows/dependencies.yml)
[![Secrets](https://github.com/madduck-tech/ydb-spring-boot-starter/actions/workflows/secrets.yml/badge.svg?branch=main)](https://github.com/madduck-tech/ydb-spring-boot-starter/actions/workflows/secrets.yml)

Community integration of the native YDB Java SDK with Spring Boot: SDK beans, a synchronous `YdbTemplate`, and Spring `@Transactional` support.

The current version is **0.1.1**, targeting Java 17, 21, and 25 with Spring Boot 4.0/4.1. Library bytecode targets Java 17. Packages are distributed through authenticated GitHub Packages; see [installation and release instructions](docs/publishing.md) and the [changelog](CHANGELOG.md). Maven Central publication is not configured.

The initial implementation has been exercised with SDK 2.4.11 and YDB 26.1.1.22. A standalone Gradle consumer verified commit/rollback from the same built artifacts on every Java 17/21/25 and Boot 4.0.8/4.1.1 combination. Maven consumption was also checked on both Boot lines. These checks cover the scenarios in this repository, not every authentication provider or failure mode in the release plan.

## Add to your project

Start with an existing **Spring Boot 4** application using **Java 17, 21, or 25**. Add the repository and starter dependency using one of the examples below, then [configure the YDB connection](#configuration).

### Authenticate to GitHub Packages

GitHub's Maven registry requires authentication even for public packages. Create a [personal access token (classic)](https://github.com/settings/tokens) with **`read:packages`** and provide these environment variables to your build:

| Variable | Value |
| --- | --- |
| `GITHUB_ACTOR` | Your GitHub login |
| `GITHUB_TOKEN` | Your personal access token (classic) with `read:packages` |

Set the token through your secret manager, shell environment, or CI secrets; keep it out of source control. See [GitHub Packages authentication](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-gradle-registry#authenticating-to-github-packages) and [CI access details](docs/publishing.md).

### Gradle (Kotlin DSL)

Merge into your application's `build.gradle.kts`:

```kotlin
repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/madduck-tech/ydb-spring-boot-starter")
        credentials {
            username = providers.environmentVariable("GITHUB_ACTOR").orNull
            password = providers.environmentVariable("GITHUB_TOKEN").orNull
        }
        content { includeGroup("io.github.madduck-tech") }
    }
}
dependencies {
    implementation("io.github.madduck-tech:ydb-spring-boot-starter:0.1.1")
}
```

### Maven

Merge into your application's `pom.xml`:

```xml
<repositories>
  <repository>
    <id>starter-build</id>
    <url>https://maven.pkg.github.com/madduck-tech/ydb-spring-boot-starter</url>
  </repository>
</repositories>
<dependencies>
  <dependency>
    <groupId>io.github.madduck-tech</groupId>
    <artifactId>ydb-spring-boot-starter</artifactId>
    <version>0.1.1</version>
  </dependency>
</dependencies>
```

Merge the matching server into your local `~/.m2/settings.xml` (outside the project):

```xml
<settings>
  <servers>
    <server>
      <id>starter-build</id>
      <username>${env.GITHUB_ACTOR}</username>
      <password>${env.GITHUB_TOKEN}</password>
    </server>
  </servers>
</settings>
```

### Configure YDB

For a local YDB instance, add to your application's `application.yml`:

```yaml
ydb:
  connection-string: grpc://localhost:2136/local
  auth:
    mode: anonymous
```

Use your database endpoint and authentication mode for a remote database; see [configuration](#configuration). The starter auto-configures SDK beans, `YdbTemplate`, and a transaction manager. See [transactions](#transactions) for using `@Transactional`.

## Modules

| Module | Contents |
| --- | --- |
| `ydb-spring` | Template, bounded result mapping, Spring transaction manager; no Boot dependency |
| `ydb-spring-boot` | Properties, SDK lifecycle, bean overrides, optional health |
| `ydb-spring-boot-starter` | Dependency set for applications |

Coordinates: `io.github.madduck-tech:ydb-spring-boot-starter:0.1.1`. Apache License 2.0.

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

Separate workflows scan resolved production dependencies with OSV-Scanner and Git history with Gitleaks on pull requests, main/tag pushes, and a daily schedule. Dependabot proposes dependency and Action updates. See [security checks and their scope](docs/security.md).

The [active protection rule for `main`](https://github.com/madduck-tech/ydb-spring-boot-starter/rules/23615959) requires **CI passed**, **Dependencies passed**, and **Secrets passed** from GitHub Actions. Changes must use a pull request with one approving review, resolved discussions, and an up-to-date base. New reviewable commits dismiss previous approvals. Direct pushes, force pushes, and branch deletion are blocked, with no bypass actors. Badges show actual workflow status.

Consumer CI refreshes Gradle dependencies and uses an empty Maven local repository so that a previous build of the same release version cannot pass in place of the current artifacts. When repeatedly rebuilding the same version locally, also select a fresh Maven local repository with `-Dmaven.repo.local=...`.

To reproduce the build check locally, start YDB as above, then run:

```sh
YDB_TEST_CONNECTION=grpc://localhost:2136/local ./gradlew clean build -PtestJava=17
python3 scripts/ci/check-test-results.py
```

Release verification beyond the current tests is tracked in [the implementation criteria](docs/implementation-plan.md). Native-image, Boot 3, repositories, and cross-database transactions are outside the initial release scope.
