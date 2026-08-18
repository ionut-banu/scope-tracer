# Capture call-site for explicitly-named forks

Date: 2026-08-18
Status: Approved

## Problem

`TaskForkedEvent.taskName` is a single string that today serves two
incompatible purposes:

1. A **display label** — either the caller's explicit name (`fork(String,
   Callable)`) or an auto-derived one (`fork(Callable)`, via
   `TaskNameDeriver`).
2. The **only source** the IntelliJ plugin's click-to-source feature has for
   locating the fork call site: `CallSiteParser` regex-parses `taskName`
   against the `SimpleClassName[#methodName[:line]]` convention that
   `TaskNameDeriver` happens to produce.

Because both purposes share one field, an explicitly-named fork
(`scope.fork("findUser", () -> ...)`) loses call-site information entirely —
`CallSiteParser` deliberately rejects a bare lowercase-starting identifier
(see `CallSiteParserTest.returnsNullForLowercaseExplicitLabel`) since it's
indistinguishable from a hand-picked label. Double-clicking such a row in the
plugin is a no-op.

## Goal

Capture call-site data (declaring class / enclosing method / source line) for
every forked task — explicitly named or not — independently of the display
label, and thread it through the full pipeline (JFR event → analyzer →
plugin) so the IntelliJ plugin's click-to-source navigation works uniformly.

Out of scope: scope-level call sites (`TracedScope.open(String)` always
takes a literal, user-supplied name in `scope-tracer-core` — there is no
auto-derivation to lose), and the `scope-tracer-agent` module (it
instruments the raw JDK `StructuredTaskScope.fork(Callable)`, which has no
name parameter, so there is no "explicitly-named fork" case to fix there).

## Design

### `scope-tracer-core`

- `TaskForkedEvent` gains three new fields, all following the existing
  nullable-field convention (nullable, `hasField()`-guarded on read):
  - `callSiteClassName` (`String`, nullable)
  - `callSiteMethodName` (`String`, nullable — absent when the task is a
    named `Callable` class rather than a lambda/method reference)
  - `callSiteLine` (`int`, `0` = unknown — matching the sentinel convention
    already used in `TaskNameDeriver.format()`)
- The stack-walk/class-inspection logic in `TaskNameDeriver` is factored so
  the class/method/line components are available as structured data, not
  just pre-formatted into a string. `TaskNameDeriver` uses this internally
  to keep producing its existing formatted label unchanged.
- `TracedScope.fork(String taskName, Callable task)` calls the structured
  deriver **unconditionally** — regardless of whether `taskName` was
  supplied explicitly or came from `TaskNameDeriver.derive(task)` via
  `fork(Callable)` — and stamps the three new fields on the
  `TaskForkedEvent` alongside `taskName`. `fork(Callable)` requires no
  change; it already delegates to `fork(String, Callable)`.
- No change to what `taskName` contains or when it's null — display
  behavior for existing recordings and reports is identical.

### `scope-tracer-analyzer`

- `TaskRecord` gains matching fields: `callSiteClassName` (`String`),
  `callSiteMethodName` (`String`), `callSiteLine` (`Integer`, `null` when
  the event's `callSiteLine` was `0`/absent).
- `JfrParser` reads the three new `TaskForkedEvent` fields with
  `hasField()` guards (same pattern as `taskName`/`exceptionStackTrace`)
  into `ForkData`, then into `TaskRecord`.
- `TraceModelJson` serializes the three fields per task; the schema
  doc-comment on the class is updated to match.
- `HtmlRenderer` is **not** touched — the static HTML report has no
  navigation feature to consume this data, and adding one is out of scope
  (YAGNI).

### `scope-tracer-plugin/model`

- `PluginTaskRecord` gains the same three fields. `TraceModelJsonParser`
  needs no custom adapter — Gson maps plain `String`/`Integer` fields by
  name automatically.
- `CallSite` and `CallSiteParser` are unchanged in shape and stay in the
  codebase, but their role changes from "the only source of call-site data"
  to "legacy fallback for recordings produced before this change."

### `scope-tracer-plugin`

- `ScopeTracerPanel.populate()` resolves each task row's `CallSite` once,
  at populate time, instead of deferring to parse-on-click:
  - If `task.callSiteClassName()` is non-null, build the `CallSite`
    directly from the structured fields.
  - Otherwise, fall back to `CallSiteParser.parse(task.taskName())` (old
    recordings / old JSON without the new fields).
- `rowRawNames: List<String>` is replaced with `rowCallSites: List<CallSite>`
  (nullable entries); the double-click handler uses the pre-resolved value
  instead of calling `CallSiteParser.parse` at click time.
- Scope header rows keep resolving to `null` — core never derives
  scope-level call sites, so this is unchanged behavior.
- Update the stale comment at `ScopeTracerPanel.java:49-52` describing the
  old raw-name-based approach.

## Compatibility

All three new fields are nullable/`0`-sentinel and read via `hasField()` on
the analyzer side. Recordings and JSON produced before this change parse
cleanly with no call-site data, and the plugin transparently falls back to
today's regex-based behavior for them. `taskName` semantics and rendered
labels are unchanged for both explicit and auto-derived forks.

## Testing

- **Core**: extend the existing JFR-recording-based tests to assert the
  three new fields are populated for both `fork(Callable)` and
  `fork(String, Callable)`.
- **Analyzer**: `JfrParserTest` — new-field presence, and `hasField`
  fallback against a recording that lacks them. `TraceModelJsonTest`
  (and `AnalyzerMainTest` if it asserts on JSON shape) — new JSON keys.
- **Plugin model**: `TraceModelJsonParserTest` — round-trip the new fields
  into `PluginTaskRecord`. Existing `CallSiteParserTest` (including the
  lowercase-rejection case) stays as a legacy-fallback regression test.
- **Plugin**: add/extend a test confirming an explicitly-named fork now
  resolves a navigable `CallSite` via the structured fields.
- Full `mvn -q verify` at the end, since the change touches
  `scope-tracer-core` (per `CLAUDE.md`, single-module verify against a
  stale core jar would false-green).

## Docs

Update `docs/jfr-events.md`'s `TaskForkedEvent` row in the event catalog
per `CLAUDE.md`'s rule to update that file whenever a JFR event's fields
change.
