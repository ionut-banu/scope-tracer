# Contributing to scope-tracer

Thanks for your interest in contributing. This document covers the workflow,
build commands, and coding conventions for the project.

## Prerequisites

| Tool  | Version                         |
| ----- | ------------------------------- |
| Java  | 26+ (with `--enable-preview`)   |
| Maven | 3.9+                            |

Both are enforced by `maven-enforcer-plugin`.

## Building and testing

Run the full build, tests, coverage, and style checks:

```bash
mvn -q verify
```

This compiles all four modules, runs **122 unit tests + 17 integration tests**
(139 total), produces Jacoco coverage reports under
`scope-tracer-*/target/site/jacoco/`, and runs SpotBugs static analysis.

### Single-module commands

```bash
# Build/test a single module
mvn -pl scope-tracer-core -q verify

# Run a single test
mvn -pl scope-tracer-core -Dtest=TracedScopeTest#myMethod test
```

> When switching branches or after `mvn clean`, run
> `mvn clean install -DskipTests` first. Otherwise `mvn -pl <module> test`
> resolves `scope-tracer-core` from your local Maven repository — if that jar
> is stale, the analyzer's integration tests will pass against old bytecode
> while the real build would fail. Always run the full `mvn -q verify` after
> changes to `scope-tracer-core` or anything that spans modules.

### Formatting

The project uses Spotless with google-java-format 1.27.0 and sortPom. CI
fails on style violations.

```bash
# Auto-fix formatting across the whole repo
mvn spotless:apply

# Single module (does not touch sibling poms)
mvn -pl scope-tracer-core spotless:apply
```

## Coding conventions

- **No Lombok.** Use records, sealed types, and pattern matching.
- **Public APIs in `core` require Javadoc with a usage example.**
- **Tests:** JUnit 5 + AssertJ. Use Awaitility for time-based waits.
- **Never use `Thread.sleep` outside the demos module.**
- **Logging:** SLF4J only — except in `scope-tracer-agent`, which uses the
  internal `AgentLog` class. The agent runs on the bootstrap classloader;
  bundling an SLF4J binding there would collide with the host application's
  logging. CLI entry points (`AnalyzerMain`) may use `System.out`/`System.err`
  for human-facing output. No `System.out.println` anywhere else outside demos.
- **No deprecated thread APIs** (`Thread.stop`, `Thread.suspend`, etc.).
- **New dependencies go in the parent `<dependencyManagement>` first**;
  modules declare `<groupId>/<artifactId>` without `<version>`.
- **Never disable or `@Disabled` a failing test** to make the build green.
  Fix the root cause.

### JFR events

If you add a new JFR event:

1. Add the event class under `scope-tracer-core/src/main/java/com/ionutbanu/scopetracer/core/events/`,
   extending `jdk.jfr.Event` and implementing the sealed `TracedScopeEvent`
   interface.
2. Update [docs/jfr-events.md](docs/jfr-events.md) — add a row to the
   "Event catalog" table, and if a new field is introduced, add a row to the
   "Common fields" table as well.
3. Extend the analyzer's `JfrParser` exhaustive switch over event types.

## Pull request workflow

1. Fork the repository and create a feature branch from `main`.
2. Make your changes. Keep commits focused and self-contained.
3. Run `mvn -q verify` locally and ensure it is green.
4. Run `mvn spotless:apply` to auto-fix formatting before committing.
5. Open a PR against `main`. CI must pass: build, tests, Spotless, Jacoco, and
   SpotBugs.
6. Add an entry to [CHANGELOG.md](CHANGELOG.md) under `## [Unreleased]`.

For non-trivial changes that touch more than one module, please open an issue
first to discuss the approach.

## Releasing

Releases are published to Maven Central by the `.github/workflows/release.yml`
workflow, triggered when a `v*.*.*` tag is pushed.

### Prerequisites (one-time, maintainer only)

Full step-by-step instructions are in [docs/maven-central-setup.md](docs/maven-central-setup.md).
Summary of what must be in place before the first release:

- `com.ionutbanu` namespace verified on the [Sonatype Central Portal](https://central.sonatype.com/) via DNS TXT on `ionutbanu.com`.
- GPG signing key generated; public key published to `keys.openpgp.org`.
- Four repository secrets configured under Settings → Secrets and variables → Actions:
  `CENTRAL_USERNAME`, `CENTRAL_TOKEN`, `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE`.

### Cutting a release

```bash
# 1. Bump from SNAPSHOT to release version on a branch off main
git checkout -b release/0.1.0
mvn versions:set -DnewVersion=0.1.0 -DgenerateBackupPoms=false
# Update CHANGELOG.md: rename [Unreleased] to [0.1.0] with today's date
mvn -q verify
git commit -am "release: 0.1.0"

# 2. Tag and push (the tag triggers the release workflow)
git tag v0.1.0
git push origin release/0.1.0 v0.1.0

# 3. Open and merge the release PR to main

# 4. Bump to next SNAPSHOT on main
git checkout main && git pull
mvn versions:set -DnewVersion=0.2.0-SNAPSHOT -DgenerateBackupPoms=false
git commit -am "chore: bump to 0.2.0-SNAPSHOT"
git push
```

The workflow validates that the tag matches the pom version, runs the full
`mvn -P release deploy`, waits for Central to confirm publication, then creates
a GitHub Release using the matching CHANGELOG section as the body.

### Verifying a release locally

Before pushing the tag, dry-run the release profile end-to-end:

```bash
export MAVEN_GPG_PASSPHRASE='...'
mvn -P release verify
# Expect: sources jar, javadoc jar, and .asc signatures under each module's target/
```

To do a real staging publish without the auto-release flag, temporarily set
`<autoPublish>false</autoPublish>` in the parent pom's release profile, run
`mvn -P release deploy`, then approve in the Central Portal UI.

## License

This project is licensed under the [Apache License 2.0](LICENSE). By contributing,
you agree that your contributions will be licensed under the same terms (per
Apache 2.0 Section 5, contributions are inbound = outbound).
