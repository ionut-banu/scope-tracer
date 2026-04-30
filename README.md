# scope-tracer

Visualise Java structured concurrency task trees, lifetimes, and cancellation propagation.

Wrap your `StructuredTaskScope` code with `TracedScope`, run the program under a JFR
recording, feed the `.jfr` file to the analyzer, and open the resulting self-contained HTML
report. The report shows a per-scope Gantt timeline and task table so you can see what ran
in parallel, which tasks were cancelled, and how nested scopes relate to their parent task.

Targets JDK 26+ with `--enable-preview` (`StructuredTaskScope` is a preview API).

---

## Requirements

| Tool | Version |
|------|---------|
| Java | 26+ (with `--enable-preview`) |
| Maven | 3.9+ |

---

## Quick start

### 1. Add the dependency

```xml
<dependency>
  <groupId>dev.scopetracer</groupId>
  <artifactId>scope-tracer-core</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

> This is a snapshot release. Build from source (see below) to install it into your local
> Maven repository before adding the dependency.

### 2. Wrap your scope

Replace `StructuredTaskScope` with `TracedScope`. The API is the same: `fork()`, `join()`,
try-with-resources.

```java
import dev.scopetracer.core.TracedScope;

try (var scope = new TracedScope("checkout-flow")) {
    Subtask<Quote>       pricing   = scope.fork(() -> pricingService.quote(cart));
    Subtask<Reservation> inventory = scope.fork(() -> inventoryService.reserve(cart));
    scope.join();
    return new Checkout(pricing.get(), inventory.get());
}
```

`TracedScope` emits six JFR events at every lifecycle moment (scope opened/closed, task
forked/succeeded/failed/cancelled). No other configuration is required.

### 3. Run under JFR recording

```bash
java -XX:StartFlightRecording=filename=myapp.jfr,dumponexit=true \
     --enable-preview \
     -cp <your-classpath> \
     com.example.MyApp
```

### 4. Run the analyzer

```bash
java --enable-preview \
     -jar scope-tracer-analyzer-0.1.0-SNAPSHOT-executable.jar \
     myapp.jfr report.html
```

The `-executable` jar is a self-contained fat-jar produced by `mvn package`. It bundles
all runtime dependencies so no classpath assembly is needed.

Or use the programmatic API:

```java
import dev.scopetracer.analyzer.JfrParser;
import dev.scopetracer.analyzer.HtmlRenderer;

var model = JfrParser.parse(Path.of("myapp.jfr"));
String html = HtmlRenderer.render(model);
Files.writeString(Path.of("report.html"), html);
```

### 5. Open the report

Open `report.html` in any browser. No server required — the file is fully self-contained.

---

## What the report shows

Each `TracedScope` gets its own section containing:

- **Metadata line** — owner thread, open time (UTC), total duration, task count.
- **Task table** — one row per forked task: task ID, thread name, fork offset from scope
  open, duration, and outcome. Failed tasks show the exception type. Tasks that opened a
  nested scope show the child scope name. In all-success scopes the slowest task is
  annotated `← critical path +Xms`.
- **SVG Gantt timeline** — a blue bar for the scope lifetime, colour-coded bars for each
  task (green = success, amber = critical path, red = failed, orange = cancelled, grey =
  incomplete), with the task ID labelled inside each bar. Hover for details. The amber
  critical-path bar is the task that determined the scope's total duration.

Nested scopes are rendered indented beneath the parent task that opened them, with a
breadcrumb showing which task spawned them.

---

## Zero-code-change tracing (Java agent)

Don't want to change source code? Use the agent. It instruments `StructuredTaskScope` at
the bytecode level — any JDK 26+ application is traced without touching its source.

```bash
java --enable-preview \
     -javaagent:scope-tracer-agent/target/scope-tracer-agent-0.1.0-SNAPSHOT-agent.jar \
     -XX:StartFlightRecording=filename=myapp.jfr,dumponexit=true \
     -cp <your-classpath> \
     com.example.MyApp
```

The agent:
- Uses the name supplied to `Config.withName("my-scope")` when set. When no name is
  configured the name is derived from the call-site stack frame (format:
  `SimpleClassName#methodName`).
- Emits the same six JFR events as `TracedScope`, so the analyzer pipeline is identical.
- **Do not** combine with `TracedScope` — each scope would emit duplicate events.

> **Note:** The agent jar is self-bootstrapped via `Boot-Class-Path` in its manifest. No
> extra JVM flags are needed for the bootstrap classloader setup.

---

## Demos

Five runnable examples are included in `scope-tracer-demos`:

| Demo | What it shows |
|------|--------------|
| `ParallelFetchDemo` | Happy path — three tasks run in parallel, all succeed |
| `FailFastDemo` | Cancellation — one task fails, its sibling is interrupted |
| `NestedScopesDemo` | Nesting — a task inside the outer scope opens an inner scope |
| `AgentDemo` | Zero-code-change — plain `StructuredTaskScope`, traced by the agent |
| `LiveServiceDemo` | On-demand monitoring — long-running service; use `jcmd` to turn tracing on/off without restarting |

The first four demos write a `.jfr` and `.html` file to `target/` and exit. `LiveServiceDemo`
runs until Ctrl+C and prints ready-to-paste `jcmd` commands at startup.

**Run a demo:**

```bash
mvn -q package -DskipTests
CP=$(mvn -pl scope-tracer-demos -q dependency:build-classpath -DforceStdout)
JARS="scope-tracer-core/target/scope-tracer-core-0.1.0-SNAPSHOT.jar:\
scope-tracer-analyzer/target/scope-tracer-analyzer-0.1.0-SNAPSHOT.jar:\
scope-tracer-demos/target/scope-tracer-demos-0.1.0-SNAPSHOT.jar:$CP"

java --enable-preview -cp "$JARS" dev.scopetracer.demos.ParallelFetchDemo
java --enable-preview -cp "$JARS" dev.scopetracer.demos.FailFastDemo
java --enable-preview -cp "$JARS" dev.scopetracer.demos.NestedScopesDemo

# AgentDemo uses the agent — no TracedScope in source
java --enable-preview \
     -javaagent:scope-tracer-agent/target/scope-tracer-agent-0.1.0-SNAPSHOT-agent.jar \
     -cp "$JARS" dev.scopetracer.demos.AgentDemo

# LiveServiceDemo — long-running; copy the jcmd commands it prints, then Ctrl+C to stop
java --enable-preview \
     -javaagent:scope-tracer-agent/target/scope-tracer-agent-0.1.0-SNAPSHOT-agent.jar \
     -cp "$JARS" dev.scopetracer.demos.LiveServiceDemo
```

**Using LiveServiceDemo** (in a second terminal while the service is running):

```bash
# capture a window of activity
jcmd <pid> JFR.start name=trace filename=/tmp/scope-trace.jfr

# ... wait for a few orders to process ...

# stop and dump
jcmd <pid> JFR.stop name=trace

# analyze
java --enable-preview \
     -jar scope-tracer-analyzer/target/scope-tracer-analyzer-0.1.0-SNAPSHOT-executable.jar \
     /tmp/scope-trace.jfr /tmp/report.html
open /tmp/report.html
```

You can repeat `JFR.start` / `JFR.stop` as many times as you like without restarting the
service. Each recording captures only the orders that ran during that window.

---

## Building from source

```bash
git clone <repo-url>
cd scope-tracer
mvn -q verify        # compile, test, style check
mvn spotless:apply   # auto-fix formatting if needed
```

See [CLAUDE.md](CLAUDE.md) for architecture details, coding conventions, and contributor
guidelines.

---

## Modules

| Module | Artifact | Purpose |
|--------|----------|---------|
| `scope-tracer-core` | `dev.scopetracer:scope-tracer-core` | `TracedScope` wrapper; emits JFR events |
| `scope-tracer-analyzer` | `dev.scopetracer:scope-tracer-analyzer` | Parses `.jfr` files; renders HTML/SVG reports |
| `scope-tracer-agent` | `dev.scopetracer:scope-tracer-agent` | Java agent; instruments `StructuredTaskScope` at bytecode level |
| `scope-tracer-demos` | `dev.scopetracer:scope-tracer-demos` | Runnable example programs |
