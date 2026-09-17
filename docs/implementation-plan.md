# Implementation and release criteria

This plan implements [the architecture](design.md) and [the transaction contract](transactions.md). The first release includes native SDK beans, `YdbTemplate`, and Spring transactions. Completing an early stage is not equivalent to completing the release.

## 1. Reproducible build and dependency baseline

Create one Gradle build with `ydb-spring`, `ydb-spring-boot`, and `ydb-spring-boot-starter`. Publish each as a plain JAR with sources and Javadoc. Keep Boot application packaging restricted to samples. Use the Apache-2.0 license already present in the upstream repository.

Pin the wrapper, Boot and SDK versions. The current candidates are Boot 4.0.8 for compilation, Boot 4.1.1 as the second consumer line, and SDK 2.4.11. Compile library and test source to Java 17; configure test launchers independently for Java 17, 21, and 25. The Gradle daemon's JDK is not the target application's JDK. A local machine without every required JDK must report that limitation rather than marking the matrix complete.

Keep Spring Boot's dependency BOM out of the core module's published API. The core should publish actual Spring Framework/SDK dependencies and tested version constraints without requiring Boot. The Boot integration uses the Boot BOM and aligns SDK modules. Do not export enforced dependency platforms that prevent applications from applying their own compatible BOM.

Ready when:

- A clean checkout builds without another repository or an application-specific composite build.
- The core's resolved runtime graph has no Boot dependency.
- Generated POM/Gradle metadata have the expected coordinates and dependency scopes.
- A temporary local publication can be consumed by both Maven and Gradle.
- Ignored local research/assistant files are absent from tracked files and publication artifacts.

## 2. Resource creation and authentication

Implement properties, validation by active construction path, authentication selection, builder customizers, and the SDK resource graph. Test conditions with fake factories or mocked SDK builders so configuration failure tests do not depend on a live database.

Separate property validation from resource construction. Validate all settings affecting a default client before opening its default transport. Custom query-client and transport beans suppress the corresponding lower-level creation path. A custom query client still allows a default template and manager later.

Ready when every SDK bean replacement case in the design passes, including multiple candidates, disabled configuration, ordered customizers, failure after transport creation, and continued transport destruction after a client destroy failure. Test that a custom client's configuration needs neither connection string nor authentication mode.

Prove supported modern authentication bean signatures against compiled provider examples. Test environment-provider precedence in forked processes, since it reads the process environment. Tests must not accidentally contact a machine's metadata service. Cloud provider dependencies stay opt-in; failure to find a selected provider must be actionable.

## 3. Operation engine and bounded results

Create the core's internal operation scope before exposing transaction-manager behavior. It owns deadline calculation, session acquisition, begin, query completion, cancellation, result collection, outcome classification, and cleanup. Both standalone template calls and transaction-manager paths share these rules rather than implementing separate commit classifiers.

Prefer a narrow internal adapter around public SDK operations for fault injection. Do not expose it as a new public SDK facade. Keep time injectable for deterministic deadline tests; ordinary production behavior uses a monotonic clock. Late completion handling must have one cleanup owner and must not block SDK callback threads while waiting on another SDK operation.

Expose `YdbOperations` and the proposed immutable options/result API. Collect raw protobuf result parts with row/serialized-byte counters, verify truncation and result-set indices, then map on the caller thread. Test multiple parts per result set and empty result sets. `execute` drains rows without retaining them.

Ready when standalone calls commit after successful mapping, failures do not return partial results, limits are enforced during collection, and every late-acquisition/query-timeout path releases its owned resources exactly once. Commit uncertainty must survive cleanup without being rewritten as a retryable query failure.

## 4. Spring transaction manager

Implement against Spring's transaction workflow, not a parallel thread-local transaction framework. Bind by query-client identity; store outcome independently from cleanup state. Validate configuration and effective isolation before network operations.

Required regression cases include:

| Scenario | Expected observation |
| --- | --- |
| Outer `DEFAULT`, inner `SERIALIZABLE` | Both use one physical transaction; no false isolation mismatch |
| Inner `REQUIRED` fails and outer catches | Outer cannot commit; no SDK transaction restart |
| `REQUIRES_NEW` cannot acquire a session | Inner fails within its deadline; outer holder and synchronization are restored |
| Explicitly different query client inside a YDB transaction | Template rejects accidental independent enlistment |
| Mapper reenters the same transaction | Reentrant operation rejected; no second active SDK query |
| Callback marks rollback-only before commit | Manager rechecks before dispatch; no commit request is sent |
| Commit response is lost | Unknown-outcome exception; completion callback receives unknown status |
| Commit succeeds and cleanup fails | Committed outcome is retained; no replay |
| Rollback response is lost | Original failure retained; completion not falsely reported as confirmed rollback |
| Pooled application thread is reused | No old resource, isolation, read-only flag, or synchronization remains |

Use actual Spring proxies for annotation tests and Spring `TransactionTemplate` for programmatic tests. A test invoking an annotated object directly does not verify `@Transactional`. Exercise rollback rules and all propagation cases in the transaction contract. Assert synchronization callbacks and database effects, not only method invocation counts.

## 5. Boot transaction and template integration

Add the Boot transaction module and conditional creation of the template/manager. Run defaults through Boot's customizer aggregate once, then validate invariants. Verify an explicit incompatible global rollback-on-commit-failure setting produces a clear error.

Test both Boot lines for:

- Plain starter application: `@Transactional` works without manually importing the YDB configuration.
- User `YdbOperations` and YDB manager: default beans back off independently.
- JDBC/JPA/Mongo/reactive managers: automatic mode preserves existing manager selection.
- Explicit enabled mode: qualified YDB manager works without changing another manager's default.
- Disabled YDB integration: no default SDK, template, manager, or health bean.

Maintain name-based autoconfiguration ordering for optional integrations and test their real configurations; a synthetic foreign manager alone is insufficient to detect ordering mistakes. Do not promise correct ordering against arbitrary unknown third-party autoconfigurations. Applications with those configurations should explicitly configure their managers; document and test the known integrations.

## 6. Real YDB and health

Pin a local YDB server image/version compatible with Query Service and the chosen SDK. Record the image digest used by CI. Do not use `latest` or `trunk` as the release-test baseline. Choose available runner architecture explicitly; local emulation is not proof of native ARM support.

Use isolated tables/databases and deterministic fixtures. Required database tests include typed parameters, two-call atomic commit, rollback after a write, concurrent conflicting transactions, read-only hint behavior, unsupported isolation, and rejection of statements that could break the managed transaction boundary. Keep schema setup outside the template transaction API.

Inspect the SDK's transaction ID/status after representative failures. If a server statement can end or replace the transaction unexpectedly, define and test a boundary check before publishing the API; do not hide that gap behind successful happy-path tests.

Implement the optional health probe directly over its own SDK operation scope. It must not join an application's transaction. Test one total deadline, single-flight behavior, delayed session arrival, and cancellation before recycling a session. An absent health dependency must not prevent the application from loading.

## 7. Published-artifact consumer matrix

Publish once to a temporary file repository, then run isolated consumers against those exact bytes. The source compilation baseline remains Java 17 on the lower Boot line; do not rebuild the library for each consumer combination and label it binary compatibility.

| Dimension | Cases |
| --- | --- |
| Application JVM | Java 17, 21, 25 |
| Spring Boot | 4.0.8, 4.1.1 |
| Consumer build | Maven and Gradle smoke coverage |
| Dependency shape | Starter only; starter with health; selected optional authentication library |
| Context | Plain application; user resource overrides; transaction-manager coexistence |

Every JVM/Boot pair must launch and perform a real template transaction. Maven and Gradle must both discover autoconfiguration from JAR metadata. Test Spring-only use of `ydb-spring` separately. Inspect the lowest supported runtime's dependency bytecode and execute it there; `--release 17` on this repository's classes does not validate dependencies.

CI should have distinct compile/unit, database integration, and published-consumer jobs. Credentials for publication are not required for ordinary pull requests. Signing and Maven Central release are a later tag-based workflow after namespace ownership and publication metadata are verified.

## Completion record

The initial checked-in README must clearly distinguish implemented behavior from this design. As each stage completes, update configuration reference and examples from executable tests. Do not mark an API supported merely because a method signature compiles.

The release is ready when the complete matrix passes, transactional failure outcomes are covered, examples run from published artifacts, and public documentation reflects tested defaults and limitations. Native images, Boot 3, repositories, and automatic transaction retries are not release gates for 0.1.
