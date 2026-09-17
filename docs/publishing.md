# Installation and releases

The Maven registry is `https://maven.pkg.github.com/madduck-tech/ydb-spring-boot-starter`.
Version **0.1.1** uses group `io.github.madduck-tech` and modules `ydb-spring`,
`ydb-spring-boot`, and `ydb-spring-boot-starter`. Applications normally need only the starter.

GitHub's Maven registry requires authentication even for public packages. Locally, use
a personal access token **(classic)** with `read:packages`, belonging to an account
with access to the repository. Export `GITHUB_ACTOR` as that account's login and
`GITHUB_TOKEN` as the token through your secret manager or shell environment. Never
commit credentials. This repository is currently private, so repository access is required.

## Gradle

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

## Maven

Add to the application's POM:

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

Merge this server into your local `~/.m2/settings.xml`, outside version control:

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

## Release procedure

1. Update the root version, both sample builds, README, and changelog in a pull request.
   Require successful **CI passed**, **Dependencies passed**, and **Secrets passed** checks.
2. Merge to `main`, create an annotated `vMAJOR.MINOR.PATCH` tag on the release commit,
   and push it. Wait for the tag's CI workflow to succeed.
3. Dispatch the publishing workflow from `main`, for example:

   ```sh
   gh workflow run publish.yml --ref main -f tag=v0.1.1
   ```

The workflow verifies tag ancestry, the exact commit's latest main/tag push CI result,
and matching library coordinates. It reruns OSV and Gitleaks on the release commit
before a separate job receives `packages: write`. Publication uses the job's short-lived
`GITHUB_TOKEN`; no personal publishing token, Sonatype account, namespace verification,
or PGP key is needed.

All three modules include their POM, Gradle metadata, sources, Javadoc, and license.
After upload, independent Gradle and Maven sample builds download from GitHub Packages
and check commit/rollback against YDB. Their logs are retained for 14 days. A failed
consumer check does not undo uploads: inspect the failure and publish a corrected
patch version if necessary. Do not move release tags or overwrite released versions.

Other repositories' Actions jobs need access to this repository's packages; do not
assume their `GITHUB_TOKEN` grants cross-repository access. Follow GitHub's registry
permissions guidance and use an appropriately scoped credential where required.

The original `v0.1.0` is a source-only release. The first package release is `v0.1.1`.
Maven Central and artifact signing can be added separately without changing module names.

References: [GitHub Gradle registry](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-gradle-registry),
[GitHub Maven registry](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry).
