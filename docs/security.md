# Security checks

Three independent status checks are required for pull requests into `main`:

| Check | Scope |
| --- | --- |
| CI passed | Build, workflow lint, tests against YDB on Java 17/21/25, Gradle/Maven consumers on Boot 4.0.8/4.1.1 |
| Dependencies passed | OSV scan of resolved production dependency versions in the libraries and Gradle/Maven consumers on both Boot lines |
| Secrets passed | Gitleaks scan of checked-out Git history, with redacted findings |

The [active main ruleset](https://github.com/madduck-tech/ydb-spring-boot-starter/rules/23615959)
requires all three checks from GitHub Actions, an up-to-date branch, one approving review,
and resolved review discussions. New reviewable commits dismiss previous approvals.
Changes must use pull requests; direct pushes, force pushes, and deletion are blocked.
There are no bypass actors, including administrators. Publication enforces its own checks.

Dependency and secret workflows run for pull requests, pushes to main/release tags,
manual dispatch, and daily against the default branch. Publication reruns both on the
exact release commit. OSV vulnerabilities and scanner errors fail the workflow; unresolved
or empty dependency inventories also fail. There are currently no vulnerability suppressions.

The inventories contain resolved direct and transitive Maven components and versions,
including the SDK transport. They use CycloneDX 1.6 and are component inventories, not
full provenance or dependency-relationship attestations. Test dependencies, build plugins,
the runner, and the YDB container are outside this dependency scan's scope. The container
and Actions are pinned by digest/commit, which prevents silent changes but does not
establish that they are free of vulnerabilities.

OSV-Scanner 2.6.0 and Gitleaks 8.30.1 are downloaded from their upstream release assets
and verified against SHA-256 hashes committed in `scripts/ci/install-security-tools.sh`.
Maintainers must update both versions and hashes when upgrading these tools.
Dependabot proposes weekly updates for GitHub Actions, Gradle builds, and the Maven sample.

Scan results and CycloneDX inventories are available in Actions artifacts for 14 days.
Gitleaks reports are redacted. Never add credentials to badge URLs, logs, or example files.
Badges in README reflect real workflow results for main in this public repository.
A green badge only describes the configured checks at that run.

Version 0.1.1 aligns gRPC to 1.75.0 because the SDK's original 1.68.3 transport contains
Netty affected by [CVE-2025-55163](https://github.com/netty/netty/security/advisories/GHSA-prj3-ccx8-p6x4).
This is an HTTP/2 server denial-of-service advisory; this project has not demonstrated
an exploit against its YDB client usage. The update avoids distributing the affected
transport and is checked through both Maven and Gradle resolution and database tests.

These checks do not claim a complete security audit. Source SAST/CodeQL, container
scanning, a coverage threshold, signed packages, and build provenance are not configured.
New advisories can make a previously green dependency scan fail; review the
affected versions and publish a patch rather than silencing the gate globally.
