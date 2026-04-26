# Scope-Tracer — Claude Code Instructions

## What this project is
A library and analyzer for visualizing Java structured concurrency
(`java.util.concurrent.StructuredTaskScope`) task trees, lifetimes,
and cancellation propagation. Targets Java engineers adopting virtual
threads on JDK 21+.

## Modules
- `scope-tracer-core` — wraps StructuredTaskScope, emits JFR events
- `scope-tracer-analyzer` — reads .jfr files, renders HTML/SVG reports
- `scope-tracer-demos` — example programs (correct + buggy)

## Build & test
- `mvn -q verify` — full build, tests, and style checks
- `mvn -pl scope-tracer-core -q verify` — single module
- Java 21+. Do NOT use `--enable-preview` flags.

## Coding conventions
- No Lombok. Use records, sealed types, and pattern matching.
- Public APIs in `core` need Javadoc with a usage example.
- Tests: JUnit 5 + AssertJ. Use Awaitility for time-based waits.
- Never use `Thread.sleep` outside the demos module.
- Logging: SLF4J only. No `System.out.println` outside demos.

## Rules for Claude
- For any change touching more than one module, use Plan Mode first.
- Run `mvn -q verify` after edits and fix failures before saying done.
- Never silently add a Maven dependency — propose it in chat first.
- Never disable or @Ignore a failing test to make the build green.
- When adding a JFR event, also update `docs/jfr-events.md`.
- Never use `Thread.stop`, `Thread.suspend`, or other deprecated APIs.