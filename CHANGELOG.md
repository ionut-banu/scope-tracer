# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.2.0] - 2026-05-24

### Fixed

- Median task-duration calculation in HTML reports was incorrect for even-length
  task lists (it always selected the upper-middle element instead of averaging
  the two middle values). Both the per-scope summary bar and the repeating-scope
  group row are corrected.

### Dependencies

- JUnit 6.0.3 → 6.1.0
- Spotless maven-plugin 3.4.0 → 3.5.1
- central-publishing-maven-plugin 0.9.0 → 0.10.0
- maven-javadoc-plugin 3.11.2 → 3.12.0; maven-source-plugin 3.3.1 → 3.4.0;
  maven-enforcer-plugin 3.6.2 → 3.6.3; maven-gpg-plugin 3.2.7 → 3.2.8
- softprops/action-gh-release GitHub Action v2 → v3

## [0.1.0] - 2026-05-14

### Added
- CI hardening: Jacoco code coverage, SpotBugs static analysis, and Dependabot
  weekly updates for Maven and GitHub Actions.
- `NOTICE` file with attribution required by Apache 2.0.
- Maven Central publishing infrastructure: `release` profile with sources,
  javadoc, GPG signing, and `central-publishing-maven-plugin`.
- `.github/workflows/release.yml` — tag-triggered automated publish to Maven
  Central with a GitHub Release.
- JPMS `module-info.java` descriptors for `scope-tracer-core` and
  `scope-tracer-analyzer` — both modules now declare their `requires` and
  `exports` and work on the module path.
- New `scope-tracer-stress-tests` module with high-concurrency integration tests
  (`HighConcurrencyTracedScopeIT`, `LongRunningScopeIT`, `AgentParallelStressIT`)
  and a dedicated `.github/workflows/stress.yml` CI job.
- `CONTRIBUTING.md` documenting the development workflow and coding conventions.

### Changed
- Surefire `argLine` now prepends `@{argLine}` so the Jacoco agent is attached
  during unit tests.
- **License changed from PolyForm Noncommercial 1.0.0 to Apache 2.0.** The
  library is now free for any use, including commercial.

## [0.1.0-SNAPSHOT]

Initial pre-release. The project is still under active development and the
public API may change without notice until the first tagged release.

### Added

#### `scope-tracer-core`
- `TracedScope<R>` — wraps `java.util.concurrent.StructuredTaskScope` and emits
  six JFR events covering every lifecycle moment: scope opened/closed and task
  forked/succeeded/failed/cancelled.
- Static factories `TracedScope.open(name)` (fail-fast default) and
  `TracedScope.open(name, joiner)` for custom joiners (race / collect-all).
- Globally unique `scopeId` on every event for reliable correlation across
  scopes with the same name and across parent/child nesting.
- `TaskFailedEvent` carries `exceptionType`, `exceptionMessage`, and
  `exceptionStackTrace` for failure analysis.
- Auto-derived task names (`fork(Callable)`) inferred from the callable's class
  or lambda call-site.

#### `scope-tracer-analyzer`
- `JfrParser` reads `.jfr` recordings and builds a `TraceModel` of scopes,
  tasks, outcomes, and parent/child nesting.
- Nesting detection via stable JFR thread IDs (works for unnamed virtual
  threads).
- `HtmlRenderer` produces a self-contained HTML report with inline CSS and SVG
  Gantt timelines per scope.
- Critical-path highlighting: the latest-completing task in an all-successful
  scope is rendered amber with a `← critical path +Xms` annotation.
- Width-aware bar labels — task indices shown only when the rendered bar is
  wide enough to fit them.
- Filtering and summary statistics on the rendered report.
- `AnalyzerMain` CLI entry point and shaded `-executable.jar` artifact.

#### `scope-tracer-agent`
- ByteBuddy-based Java agent that instruments `StructuredTaskScope` at the
  bytecode level, so any JDK 26+ application can be traced without source
  changes (`-javaagent:scope-tracer-agent-*-agent.jar`).
- Auto-HTML generation: any JFR recording started with `filename=` produces an
  HTML report alongside the `.jfr` file when `JFR.stop` is called.
- `AgentConfig` parses agent arguments: `html=false`, `verbose`, `output.dir`,
  `output.suffix`, `min.scopes`.
- Scope-name resolution: `Config.withName(...)` first, falling back to a
  `StackWalker`-based call-site deriver.

#### `scope-tracer-demos`
- Runnable examples: `ParallelFetchDemo`, `FailFastDemo`, `NestedScopesDemo`,
  `OrderProcessingDemo`, `LiveOrderProcessingDemo`, `LiveServiceDemo`,
  `AgentDemo`.
- Each demo writes a `.jfr` recording and an `.html` report to `target/`.

#### Build & docs
- Maven multi-module project under `com.ionutbanu:scope-tracer-*:0.1.0-SNAPSHOT`.
- GitHub Actions CI: build + tests + Spotless style check on every push and PR.
- Spotless with google-java-format 1.27.0 and sortPom.
- maven-enforcer-plugin requires Maven 3.9+ and Java 26+.
- `--enable-preview` enabled project-wide.
- License: PolyForm Noncommercial 1.0.0.
- `docs/jfr-events.md` documents the JFR event schema.

[0.1.0]: https://github.com/ionut-banu/scope-tracer/compare/v0.1.0-SNAPSHOT...v0.1.0
[0.1.0-SNAPSHOT]: https://github.com/ionut-banu/scope-tracer/releases/tag/v0.1.0-SNAPSHOT
