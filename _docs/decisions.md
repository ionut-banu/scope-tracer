Decisions made while building. Newest last, one short entry each.

If a decision contradicts `_docs/spec.md`, edit the spec in the same
commit that records the decision here.

The entries dated before 2026-10-02 were reconstructed on that date
from commit history, code comments and the old `CLAUDE.md`, when this
file was created. Their dates are the dates of the commits that made
them. Every entry from 2026-10-02 on is written when the decision is.

---

2026-04-26 - JFR is the transport, not a custom format

Every JDK 26 ships a recorder that is cheap when off, safe on
virtual threads, controllable from `jcmd` on a running process, and
readable with `RecordingFile`. A custom log format would need its own
buffering, its own file handling, and its own way to be switched on
in production. Choosing JFR also means inheriting its one real
weakness - silent drops under per-thread buffer pressure - which §2
of the spec documents rather than works around.

2026-04-26 - Events form a sealed hierarchy in core

The analyzer pattern-matches over `TracedScopeEvent` exhaustively, so
a new event that the analyzer does not handle is a compile error
rather than a silently ignored record. Core carries no JFR-consumer
dependency to make this work.

2026-04-28 - Nesting is detected by JFR thread id and containment

A child scope is opened on its parent task's thread, inside that
task's lifetime. Thread names cannot be the key: unnamed virtual
threads have empty names. The JFR thread id is stable and present on
every event.

2026-04-28 - The agent instruments StructuredTaskScope itself

Wrapping is the cheap path but requires the user to change their
code, and the people most likely to need a trace are looking at code
they did not write. The agent puts its advice on the bootstrap
classpath via `Boot-Class-Path` because `StructuredTaskScope` is a
bootstrap class. ByteBuddy runs in experimental mode until it
officially supports Java 26 class files.

2026-04-30 - scopeId, not scopeName, identifies a scope

Two concurrent scopes with the same name - the normal case for a
request handler - were merged by a name-keyed parser. Every event now
carries a globally unique `scopeId`, and `ParentRef` carries the
parent's id.

2026-05-04 - Fields are only ever added, nullable, behind hasField

`exceptionMessage`, `exceptionStackTrace` and later `taskName` were
added after recordings already existed in the wild. Readers check
`event.hasField` so that a recording from any earlier release still
parses. Renaming or removing a field would break every recording
anyone has kept.

2026-05-06 - Auto-HTML on JFR.stop

People attach the agent to a service and drive JFR with `jcmd`. A
report that appears next to the `.jfr` the moment the recording stops
removes the step most of them would otherwise skip. It is on by
default and turned off with `html=false`.

2026-05-13 - The agent logs through AgentLog, not SLF4J

The agent runs on the bootstrap classloader of someone else's
application. An SLF4J binding there collides with theirs. `AgentLog`
writes `[scope-tracer]`-prefixed lines to stderr and nothing else.

2026-06-02 - Capture filters act at scope open, and drop everything

A filtered scope emits no events for its whole lifetime, rather than
emitting an open and suppressing the rest. Half a scope in a
recording is worse than none, and the existing null-state checks in
fork, close and `TracingCallable` make the excluded path free.
Exclude beats include; sampling runs last so filtered-out traffic
does not consume the sample.

2026-08-07 - The plugin runs the analyzer as a subprocess

The project is compiled with `--enable-preview` for 26, which stamps
every class file so it only loads on that JDK. The IDE runs on JBR,
pinned to 21. So the plugin can never load analyzer classes, not
because of what they use but because of how they were compiled. It
bundles the analyzer's executable jar, runs it on a user-configured
JDK 26 with `--format=json`, and parses the JSON in a plain Java 21
`:model` module. The alternative - a preview-free, lower-target copy
of the parser - would be a second implementation of the same thing.

2026-10-02 - Work is run as an orchestrated team, from historian

Adopted the process from the historian project: an orchestrator
session that dispatches PM, engineer and QA subagents per GitHub
issue, one worktree per issue, a human merging every pull request,
and a reviewer at milestone boundaries. The specification moved from
`CLAUDE.md` into `_docs/spec.md`; `CLAUDE.md` now only includes
`AGENTS.md`. `_docs/` rather than `docs/` because `docs/` is the
published GitHub Pages site.

Adapted where the projects differ: the oracle is the JDK, observed
through real JFR recordings, rather than SQLite; the conformance
count is the total test count; and the worktree hazard is the shared
`~/.m2` repository rather than a shared virtualenv, so `mvn -pl`
always takes `-am`.
