# Contributing to scope-tracer

Thanks for your interest in contributing. This document covers the workflow,
build commands, and coding conventions for the project.

## Prerequisites

| Tool  | Version                         |
| ----- | ------------------------------- |
| Java  | 27+ (with `--enable-preview`)   |
| Maven | 3.9+                            |

Both are enforced by `maven-enforcer-plugin`.

## Building and testing

Run the full build, tests, coverage, and style checks:

```bash
mvn -q verify
```

This compiles the Maven modules, runs the unit tests and the agent integration
tests (the last count is in `_docs/spec.md` §4), produces Jacoco coverage reports under
`scope-tracer-*/target/site/jacoco/`, and runs SpotBugs static analysis.

### Single-module commands

```bash
# Build/test a single module plus what it depends on
mvn -pl scope-tracer-analyzer -am -q verify

# Run a single test
mvn -pl scope-tracer-core -am -Dtest=TracedScopeTest#myMethod -Dsurefire.failIfNoSpecifiedTests=false test
```

> Never run `mvn -pl <module>` without `-am`. `-am` builds the sibling modules from
> the same reactor. Without it Maven takes them from `~/.m2`, which every checkout and
> worktree on the machine shares, so the tests pass or fail against someone else's
> bytecode. Always run the full `mvn -q verify` before you push, and after changes to
> `scope-tracer-core` or anything that spans modules.

### The gallery

`docs/gallery/` and `docs/example/order-processing-report.html` are generated reports, not
hand-written ones. **If you change `HtmlRenderer`, a gallery scenario
(`GalleryScenarios`, `OrderProcessingDemo`) or the page text in `GalleryCatalog`, regenerate the
gallery and commit the result:**

```bash
# $JARS as in "Run a demo" under "Demos" in README.md
java --enable-preview -cp "$JARS" com.ionutbanu.scopetracer.demos.GalleryMain
```

After regenerating the gallery, run `.github/scripts/readme-image.sh` and commit the PNG and the
sidecar it writes (`docs/img/readme-report.png` and `.source`): the README's lead image is a
screenshot of `docs/gallery/reports/fail-fast.html`, and the sidecar holds the SHA-256 of the
report it was shot from. The script needs headless Chrome (`$CHROME`, or a Chrome or Chromium it
finds); nothing else needs one. CI runs `.github/scripts/readme-image.sh --check`, which needs no
Chrome and fails when the report changed and the image was not shot again.

CI runs the same command with `--check` after `mvn -q verify`, and fails the pull request when a
committed report no longer matches what the renderer and the JDK produce (it ignores times,
durations, thread names and JDK frames, which differ on every run). `mvn -q verify` itself runs
`GalleryTest`, which times nothing and checks the committed page, the snippets and the file sizes.

### Formatting

The project uses Spotless with google-java-format 1.36.1 and sortPom. CI
fails on style violations.

```bash
# Auto-fix formatting across the whole repo
mvn spotless:apply

# Single module (does not touch sibling poms)
mvn -pl scope-tracer-core -am spotless:apply
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

In the commands, `X.Y.Z` is the release version and `X.Y+1.0-SNAPSHOT` the next development
version (for example 0.3.0 and 0.4.0-SNAPSHOT).

```bash
# 1. Bump from SNAPSHOT to release version on a branch off main
VERSION=X.Y.Z
git checkout -b release/$VERSION
mvn versions:set -DnewVersion=$VERSION -DgenerateBackupPoms=false

# 2. Update the four things that go stale (see below), then check them
.github/scripts/check-release-docs.sh $VERSION
mvn -q verify
git commit -am "release: $VERSION"

# 3. Tag and push (the tag triggers the release workflow)
git tag v$VERSION
git push origin release/$VERSION v$VERSION

# 4. Open the release PR and merge it with "Create a merge commit", not rebase or squash:
#    both rewrite the commit, and the tag must point at a commit on main
#    (check afterwards: git merge-base --is-ancestor vX.Y.Z origin/main)
#    Merge only after the GitHub Release exists: merging publishes the landing page, whose
#    download links point at that release and 404 until it does

# 4b. After the GitHub Release exists, run the tagged README's quick start in an empty directory
#     and open trial.html: the tagged README is the one people read, and its X.Y.Z URLs only
#     resolve once the release is published. Copy the first block of the quick start
#     (https://github.com/ionut-banu/scope-tracer/blob/vX.Y.Z/README.md) into a new directory
#     outside any git tree and run it as written.

# 5. Bump to next SNAPSHOT on main
git checkout main && git pull
mvn versions:set -DnewVersion=X.Y+1.0-SNAPSHOT -DgenerateBackupPoms=false
git commit -am "chore: bump to X.Y+1.0-SNAPSHOT"
git push
```

Step 2 is what keeps a release from shipping stale text. Before the release commit:

1. Set the `<version>` in the README's quick-start dependency snippet, and any jar name
   (`scope-tracer-core-X.Y.Z.jar`) or `releases/download/vX.Y.Z` URL, to the release version.
   Do the same in `docs/index.html`: its jar names and `releases/download/vX.Y.Z` download
   URLs are literal versions, never a shell variable.
2. Set the version in `jbang-catalog.json` (the analyzer's `releases/download/vX.Y.Z` URL and
   jar name) and in `scope-tracer-demos/jbang/ScopeTracerDemo.java` (its
   `//DEPS com.ionutbanu:scope-tracer-agent:X.Y.Z:agent` line) to the release version. Both are
   literal versions, never a placeholder, `RELEASE` or a bare `group:artifact`: JBang reads
   them from `main`, so they change only in the release commit.
3. Update the "Released:" lines in `_docs/spec.md` §5 (the release and its date, and what
   `main` is now).
4. Rename `[Unreleased]` in `CHANGELOG.md` to `[X.Y.Z]` with today's date, and regenerate its
   `### Dependencies` list from the last tag, for example
   `git diff vPREVIOUS..HEAD -U0 -- pom.xml '*/pom.xml' .github/workflows scope-tracer-plugin/gradle.properties scope-tracer-plugin/build.gradle.kts scope-tracer-plugin/model/build.gradle.kts`
   (`vPREVIOUS` is the previous release tag). List each dependency or plugin whose
   version differs as "old → new", final values only. The release workflow builds the GitHub
   Release body from this section, so a dependency missing here is missing from the release notes.

`.github/scripts/check-release-docs.sh` checks the first three and the CHANGELOG heading and
link of the fourth. It reads `README.md`, `CONTRIBUTING.md`, `docs/*.md`, `docs/index.html`,
`jbang-catalog.json` and `scope-tracer-demos/jbang/ScopeTracerDemo.java` (the last two are
required) and fails on:

- any scope-tracer `<version>`, jar name, `group:artifact:version` coordinate or
  `releases/download/vX.Y.Z` URL that is not the release version;
- in the two JBang files, a scope-tracer coordinate with no literal `X.Y.Z`, any
  `releases/latest` URL, a catalog that names no pinned scope-tracer artifact itself, and a demo
  source without its `//DEPS com.ionutbanu:scope-tracer-agent:X.Y.Z:agent` line;
- a README with no dependency snippet, and a `README.md` or `docs/index.html` without the agent's
  `-javaagent:scope-tracer-agent-X.Y.Z-agent.jar=record` command (the quick start and the
  landing page's trial command are the same block);
- a missing `## [X.Y.Z] - date` heading or `[X.Y.Z]:` link in `CHANGELOG.md`, and a spec §5
  `Released:` line that does not list `X.Y.Z (`.

It prints every problem as a GitHub annotation, then exits 1. It does not read `pom.xml` (the
source of truth), the CHANGELOG bodies, `_docs/decisions.md`, test fixtures or any `*.java`
other than the JBang demo source, and it does not check the README's JDK prose or the §4 test
counts. The version is `X.Y.Z` only: a tag with a qualifier such as `v0.4.0-rc1` matches the
`v*.*.*` trigger, but the script exits 2 on it and the release stops. There has never been one;
supporting it is a later decision. `.github/scripts/check-release-docs-test.sh` is its self-test
and runs in CI.

The workflow's `check` job validates that the tag matches the pom version, runs
`check-release-docs.sh` against the tag's docs, then builds the tag's Central bundle with
`mvn -P release deploy -DskipUpload=true` (no credentials, no signing key) and runs
`.github/scripts/check-release-bundle.sh` on it. That script fails when the bundle holds any
artifact but the parent POM, core, analyzer and agent, or lacks one of them; the demos and the
stress tests opt out with `skipPublishing`. `.github/scripts/check-release-bundle-test.sh` is its
self-test and runs in CI. The `publish` job needs it, so nothing reaches
Central when either fails. `publish` then runs the full `mvn -P release deploy`, waits for
Central to confirm publication, then creates
a GitHub Release using the matching CHANGELOG section as the body.

To run the checks on a real runner without publishing, dispatch the `Release` workflow from
`main` with `tag` set to `v0.3.0` (or any existing tag) and `check_only` ticked, or:

```bash
gh workflow run release.yml --ref main -f tag=v0.3.0 -f check_only=true
```

The `check` job runs `main`'s script against that tag's docs and `publish` is skipped. A tag
push always publishes. A tag older than this check can fail on its own stale docs; that is the
check working.

### Verifying a release locally

Before pushing the tag, dry-run the release profile end-to-end:

```bash
export MAVEN_GPG_PASSPHRASE='...'
mvn -P release verify
# Expect: sources jar, javadoc jar, and .asc signatures under each module's target/
```

To see the Central bundle without a credential, do it on a throwaway copy of the tree with a
non-SNAPSHOT version (a SNAPSHOT stages nothing), and a scratch local repository, because the
release profile installs into it:

```bash
mvn versions:set -DnewVersion=X.Y.Z -DgenerateBackupPoms=false   # in the copy, never committed
mvn -B -P release deploy -DskipTests -DskipITs -Dgpg.skip=true -DskipUpload=true \
  -Dmaven.javadoc.skip=true -Dmaven.repo.local=/tmp/scratch-m2
.github/scripts/check-release-bundle.sh target/central-publishing/central-bundle.zip
```

To do a real staging publish without the auto-release flag, temporarily set
`<autoPublish>false</autoPublish>` in the parent pom's release profile, run
`mvn -P release deploy`, then approve in the Central Portal UI.

## License

This project is licensed under the [Apache License 2.0](LICENSE). By contributing,
you agree that your contributions will be licensed under the same terms (per
Apache 2.0 Section 5, contributions are inbound = outbound).
