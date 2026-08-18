# Named-Fork Call-Site Capture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Capture the fork call site (declaring class / enclosing method / source line) for every forked task — explicitly named or not — so the IntelliJ plugin's click-to-source navigation works for explicitly-named forks, not just auto-derived ones.

**Architecture:** Decouple call-site data from the display label. `TracedScope.fork(String, Callable)` derives the call site unconditionally (regardless of whether a name was supplied) and stamps it onto three new `TaskForkedEvent` fields alongside the untouched `taskName`. The data flows structurally through `JfrParser` → `TraceModelJson` → `TraceModelJsonParser` → `ScopeTracerPanel`, replacing the plugin's current approach of regex-parsing the display label.

**Tech Stack:** Java 26 (`--enable-preview`), JFR (`jdk.jfr`), Maven (core/analyzer/agent reactor), Gradle + IntelliJ Platform Gradle Plugin (plugin/model), JUnit 5, AssertJ, Gson 2.10.1.

**Spec:** [docs/superpowers/specs/2026-08-18-named-fork-call-site-design.md](../specs/2026-08-18-named-fork-call-site-design.md)

## Global Constraints

- Java 26+, Maven 3.9+; `--enable-preview` stays enabled project-wide for core/analyzer — do not disable it.
- No Lombok. Use records, sealed types, and pattern matching.
- Public APIs in `core` need Javadoc with a usage example (existing `fork` Javadoc already has one; extend, don't remove).
- Tests: JUnit 5 + AssertJ.
- Never use `Thread.sleep` outside the demos module.
- Never silently add a Maven/Gradle dependency — none is needed for this plan.
- Never disable or `@Ignore` a failing test to make the build green.
- When a JFR event's fields change, `docs/jfr-events.md` must be updated in the same task.
- After any edit touching `scope-tracer-core`, run the full `mvn -q verify` from the repo root (not `-pl <module>`) before considering the branch done — a single-module verify against a stale core jar produces false-green results.
- `scope-tracer-plugin`'s root module intentionally has `tasks.test { enabled = false }` (no IDE-sandboxed tests this milestone, per its `build.gradle.kts` comment) — do not add tests there; verify with a compile check instead. `scope-tracer-plugin/model` is a plain Java module with full JUnit coverage — add tests there.
- When running Maven against a module that depends on `scope-tracer-core` mid-plan (i.e. before the final full verify), use `-pl <module> -am` so the reactor rebuilds core fresh instead of resolving a possibly-stale jar from `~/.m2`.

---

### Task 1: Core — expose structured call-site data from `TaskNameDeriver`

**Files:**
- Modify: `scope-tracer-core/src/main/java/com/ionutbanu/scopetracer/core/TaskNameDeriver.java`
- Test: `scope-tracer-core/src/test/java/com/ionutbanu/scopetracer/core/TaskNameDeriverTest.java`

**Interfaces:**
- Consumes: nothing new (works with `java.util.concurrent.Callable`, already imported).
- Produces (for Task 2): a package-private record `TaskNameDeriver.CallSiteInfo(String className, String methodName, int line)`; a package-private method `static TaskNameDeriver.CallSiteInfo deriveCallSite(Callable<?> task)`; a package-private (no longer `private`) method `static String format(CallSiteInfo info)`. `TaskNameDeriver.derive(Callable<?> task)` keeps its existing signature and behavior (delegates to `deriveCallSite` + `format` internally) — no existing caller needs to change.

- [ ] **Step 1: Write failing tests for the new structured API**

Add these tests to `TaskNameDeriverTest.java`, right after `nullCallableReturnsCallerFrameNotNull`:

```java
  @Test
  void deriveCallSiteForRealClassHasNoMethodOrLine() {
    var info = TaskNameDeriver.deriveCallSite(new MyTask());
    assertThat(info.className()).isEqualTo("MyTask");
    assertThat(info.methodName()).isNull();
    assertThat(info.line()).isZero();
  }

  @Test
  void deriveCallSiteForLambdaCapturesCallerFrame() {
    Callable<String> lambda = () -> "v";
    var info = TaskNameDeriver.deriveCallSite(lambda);
    assertThat(info.className()).isEqualTo("TaskNameDeriverTest");
    assertThat(info.methodName()).isEqualTo("deriveCallSiteForLambdaCapturesCallerFrame");
    assertThat(info.line()).isPositive();
  }

  @Test
  void deriveFormatsTheSameDataAsDeriveCallSite() {
    Callable<String> lambda = () -> "v";
    var info = TaskNameDeriver.deriveCallSite(lambda);
    var name = TaskNameDeriver.derive(lambda);
    assertThat(name).isEqualTo(info.className() + "#" + info.methodName() + ":" + info.line());
  }
```

- [ ] **Step 2: Run the tests to confirm they fail to compile**

Run: `mvn -pl scope-tracer-core -q test -Dtest=TaskNameDeriverTest`
Expected: FAIL — `cannot find symbol: method deriveCallSite` (the method doesn't exist yet).

- [ ] **Step 3: Refactor `TaskNameDeriver.java` to expose structured data**

Replace the full file content with:

```java
package com.ionutbanu.scopetracer.core;

import java.util.concurrent.Callable;

/**
 * Derives a human-readable label for a task forked into a {@link TracedScope}.
 *
 * <p>Two-tier strategy:
 *
 * <ol>
 *   <li>If the {@link Callable} is a real (non-synthetic, non-hidden) user class, return its
 *       {@linkplain Class#getSimpleName() simple name}. This covers {@code scope.fork(new
 *       FindUserTask())}-style call sites.
 *   <li>Otherwise (typically a lambda or method reference), walk the stack and return the first
 *       caller frame outside {@code com.ionutbanu.scopetracer.core} and the JDK's {@code
 *       StructuredTaskScope}, formatted as {@code SimpleClassName#methodName:line} — matching the
 *       agent's {@code ScopeNameDeriver} convention. The line number makes sibling forks in the
 *       same method distinguishable (e.g. three {@code scope.fork(() -> …)} calls each get a unique
 *       label).
 * </ol>
 *
 * <p>Returns {@code null} when no usable label can be derived (e.g. an opaque proxy invoked from a
 * thread with no application frames). Callers stamp {@code null} on the event and the renderer
 * falls back to {@code #N}.
 */
final class TaskNameDeriver {

  private TaskNameDeriver() {}

  /**
   * Derives a label for {@code task}, or {@code null} if no usable label can be produced. Never
   * throws — any {@link RuntimeException} during derivation is swallowed and {@code null} is
   * returned.
   */
  static String derive(Callable<?> task) {
    return format(deriveCallSite(task));
  }

  /**
   * Derives the structured call-site components for {@code task}: the class name (always present
   * when derivation succeeds), the enclosing method name (present only for the caller-frame tier),
   * and the source line ({@code 0} when unknown). Returns {@code null} under the same conditions as
   * {@link #derive(Callable)} — never throws.
   *
   * <p>Used both to build {@link #derive(Callable)}'s formatted label and, independently, by {@link
   * TracedScope#fork(String, Callable)} to stamp {@code TaskForkedEvent}'s {@code callSite*} fields
   * even when the caller supplied an explicit label — so an explicitly-named fork's source location
   * is never lost.
   */
  static CallSiteInfo deriveCallSite(Callable<?> task) {
    try {
      String fromClass = fromClass(task);
      if (fromClass != null) return new CallSiteInfo(fromClass, null, 0);
      return fromCallerFrame();
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static String fromClass(Callable<?> task) {
    if (task == null) return null;
    Class<?> cls = task.getClass();
    if (cls.isHidden() || cls.isSynthetic()) return null;
    String simple = cls.getSimpleName();
    if (simple == null || simple.isBlank()) return null;
    // Defensive: getSimpleName on a synthetic-but-not-flagged lambda class typically contains
    // "$$Lambda" — skip those too.
    if (simple.contains("$$Lambda")) return null;
    return simple;
  }

  private static CallSiteInfo fromCallerFrame() {
    return StackWalker.getInstance()
        .walk(
            frames ->
                frames
                    .filter(
                        f -> {
                          String cls = f.getClassName();
                          // Exclude only the deriver and TracedScope themselves — NOT the entire
                          // com.ionutbanu.scopetracer.core package, which would also drop test
                          // classes living in that same package. JDK scope machinery is filtered
                          // by the StructuredTaskScope prefix check.
                          return !cls.equals("com.ionutbanu.scopetracer.core.TracedScope")
                              && !cls.equals("com.ionutbanu.scopetracer.core.TaskNameDeriver")
                              && !cls.startsWith("java.util.concurrent.StructuredTaskScope");
                        })
                    .findFirst()
                    .map(TaskNameDeriver::toCallSiteInfo)
                    .orElse(null));
  }

  private static CallSiteInfo toCallSiteInfo(StackWalker.StackFrame f) {
    String cls = f.getClassName();
    int dot = cls.lastIndexOf('.');
    String simple = dot >= 0 ? cls.substring(dot + 1) : cls;
    int dollar = simple.indexOf('$');
    if (dollar > 0) simple = simple.substring(0, dollar);
    String method = f.getMethodName();
    if (method.startsWith("lambda$")) {
      String inner = method.substring("lambda$".length());
      int lastDollar = inner.lastIndexOf('$');
      method = lastDollar > 0 ? inner.substring(0, lastDollar) : inner;
    }
    return new CallSiteInfo(simple, method, f.getLineNumber());
  }

  private static String format(CallSiteInfo info) {
    if (info == null) return null;
    if (info.methodName() == null) return info.className();
    return info.line() > 0
        ? info.className() + "#" + info.methodName() + ":" + info.line()
        : info.className() + "#" + info.methodName();
  }

  /**
   * Structured call-site components, as an alternative to {@link #derive(Callable)}'s pre-formatted
   * string.
   *
   * @param className always present when derivation succeeds.
   * @param methodName present only when derived from a caller stack frame (lambda/method
   *     reference); {@code null} for a named {@code Callable} class.
   * @param line source line of the caller frame; {@code 0} when unknown or not applicable (the
   *     named-class tier never has a line).
   */
  record CallSiteInfo(String className, String methodName, int line) {}
}
```

Note: `format` changed from `private` to package-private (no modifier) — `TracedScope` needs it in Task 2.

- [ ] **Step 4: Run the tests to confirm they pass**

Run: `mvn -pl scope-tracer-core -q test -Dtest=TaskNameDeriverTest`
Expected: PASS — all existing tests (`realClassReturnsSimpleName`, `lambdaFallsBackToCallerFrame`, `nullCallableReturnsCallerFrameNotNull`) plus the three new ones.

- [ ] **Step 5: Commit**

```bash
git add scope-tracer-core/src/main/java/com/ionutbanu/scopetracer/core/TaskNameDeriver.java \
        scope-tracer-core/src/test/java/com/ionutbanu/scopetracer/core/TaskNameDeriverTest.java
git commit -m "refactor(core): expose structured call-site data from TaskNameDeriver"
```

---

### Task 2: Core — capture call site unconditionally on every fork

**Files:**
- Modify: `scope-tracer-core/src/main/java/com/ionutbanu/scopetracer/core/events/TaskForkedEvent.java`
- Modify: `scope-tracer-core/src/main/java/com/ionutbanu/scopetracer/core/TracedScope.java:227-297`
- Modify: `docs/jfr-events.md`
- Test: `scope-tracer-core/src/test/java/com/ionutbanu/scopetracer/core/TracedScopeTest.java`

**Interfaces:**
- Consumes: Task 1's `TaskNameDeriver.deriveCallSite(Callable<?>)` and `TaskNameDeriver.format(CallSiteInfo)`.
- Produces (for Task 4): `TaskForkedEvent` gains `public String callSiteClassName`, `public String callSiteMethodName`, `public int callSiteLine` — populated on every `TaskForkedEvent` commit, regardless of which `fork` overload was used or whether an explicit name was supplied. Nullable/`0`-default and must be read via `event.hasField(...)`, matching the existing `taskName` convention.

- [ ] **Step 1: Write a failing test**

Add this test to `TracedScopeTest.java`, in the "task naming" section right after `forkWithNullExplicitNameStampsNullTaskName`:

```java
  /**
   * The whole point of this feature: an explicitly-named fork still gets its call site captured,
   * independently of the label. Before this change, callSite* fields didn't exist at all.
   */
  @Test
  void forkWithExplicitNameStillCapturesCallSite() throws Exception {
    var scopeName = "explicit-name-callsite";
    var events =
        capture(
            scopeName,
            () -> {
              try (var scope = TracedScope.open(scopeName, Thread.ofPlatform().factory())) {
                scope.fork("findUser", () -> "u");
                scope.join();
              }
            });

    var forked = eventsOfType(events, "com.ionutbanu.scopetracer.TaskForked");
    assertThat(forked).hasSize(1);
    var event = forked.get(0);
    assertThat(event.getString("taskName")).isEqualTo("findUser");
    assertThat(event.getString("callSiteClassName")).isEqualTo("TracedScopeTest");
    assertThat(event.getString("callSiteMethodName"))
        .isEqualTo("forkWithExplicitNameStillCapturesCallSite");
    assertThat(event.getInt("callSiteLine")).isPositive();
  }

  /** Auto-derived forks populate the same callSite* fields as the explicit-name path. */
  @Test
  void forkWithLambdaAlsoCapturesCallSite() throws Exception {
    var scopeName = "lambda-callsite";
    var events =
        capture(
            scopeName,
            () -> {
              try (var scope = TracedScope.open(scopeName, Thread.ofPlatform().factory())) {
                scope.fork(() -> "v");
                scope.join();
              }
            });

    var forked = eventsOfType(events, "com.ionutbanu.scopetracer.TaskForked");
    assertThat(forked).hasSize(1);
    var event = forked.get(0);
    assertThat(event.getString("callSiteClassName")).isEqualTo("TracedScopeTest");
    assertThat(event.getString("callSiteMethodName")).isEqualTo("forkWithLambdaAlsoCapturesCallSite");
    assertThat(event.getInt("callSiteLine")).isPositive();
  }

  /** The named-Callable-class tier has no single enclosing method or line. */
  @Test
  void forkWithCallableClassHasNoCallSiteMethodOrLine() throws Exception {
    var scopeName = "callable-class-callsite";
    var events =
        capture(
            scopeName,
            () -> {
              try (var scope = TracedScope.open(scopeName, Thread.ofPlatform().factory())) {
                scope.fork(new NamedTask());
                scope.join();
              }
            });

    var forked = eventsOfType(events, "com.ionutbanu.scopetracer.TaskForked");
    assertThat(forked).hasSize(1);
    var event = forked.get(0);
    assertThat(event.getString("callSiteClassName")).isEqualTo("NamedTask");
    assertThat(event.getString("callSiteMethodName")).isNull();
    assertThat(event.getInt("callSiteLine")).isZero();
  }
```

- [ ] **Step 2: Run the tests to confirm they fail**

Run: `mvn -pl scope-tracer-core -q test -Dtest=TracedScopeTest`
Expected: FAIL — `event.getString("callSiteClassName")` throws (field doesn't exist on `TaskForkedEvent`).

- [ ] **Step 3: Add the new fields to `TaskForkedEvent`**

In `TaskForkedEvent.java`, after the existing `taskName` field (keep everything above unchanged), append:

```java

  /**
   * Simple class name of the fork call site: the enclosing class for a lambda/method reference, or
   * the {@link java.util.concurrent.Callable}'s own class when it's a named user class. Always
   * derived independently of {@link #taskName} — populated even when the caller supplied an
   * explicit label via {@code TracedScope.fork(String, Callable)}.
   *
   * <p>Nullable: absent when derivation fails, or in recordings produced before this field was
   * added. Parsers must use {@code event.hasField("callSiteClassName")} before reading.
   */
  @Label("Call site class")
  public String callSiteClassName;

  /**
   * Enclosing method name of the fork call site. Present only when the label was derived from a
   * caller stack frame (lambda/method reference); {@code null} for a named {@link
   * java.util.concurrent.Callable} class, which has no single enclosing method.
   *
   * <p>Nullable; absent in recordings produced before this field was added — use {@code
   * event.hasField("callSiteMethodName")} before reading.
   */
  @Label("Call site method")
  public String callSiteMethodName;

  /**
   * Source line of the fork call site. {@code 0} when unknown (e.g. the named-{@code Callable}-
   * class tier, which has no single line) or in recordings produced before this field was added —
   * use {@code event.hasField("callSiteLine")} before reading.
   */
  @Label("Call site line")
  public int callSiteLine;
```

- [ ] **Step 4: Restructure `TracedScope.fork` to derive the call site once per fork, unconditionally**

In `TracedScope.java`, replace lines 227-297 (both `fork` overloads through the end of the explicit-name overload's body) with:

```java
  public <T> Subtask<T> fork(Callable<? extends T> task) {
    var callSite = TaskNameDeriver.deriveCallSite(task);
    return doFork(TaskNameDeriver.format(callSite), task, callSite);
  }

  /**
   * Forks a value-returning task into the scope with an explicit human-readable label.
   *
   * <p>The label appears in the rendered HTML report next to the per-scope task index. Use this
   * overload when the auto-derived label from {@link #fork(Callable)} (the lambda's enclosing
   * method name, e.g. {@code OrderService#checkout}) is less informative than the operation name
   * the task represents (e.g. {@code "findUser"}).
   *
   * <p>The fork's source location (class, enclosing method, and line) is captured independently of
   * this label and is unaffected by it — tooling such as the IntelliJ plugin's click-to-source
   * navigation uses it directly, so supplying an explicit label never loses navigability.
   *
   * <pre>{@code
   * try (var scope = TracedScope.open("checkout")) {
   *     scope.fork("findUser",  () -> userService.findById(id));
   *     scope.fork("loadCart",  () -> cartService.load(id));
   *     scope.join();
   * }
   * }</pre>
   *
   * @param taskName label for the task; may be {@code null} or blank, in which case the report
   *     falls back to the per-scope index.
   * @param task the callable to run as a structured subtask; must be non-null.
   * @param <T> result type of the subtask.
   * @return a {@link Subtask} handle whose value is observable after {@link #join()}.
   */
  public <T> Subtask<T> fork(String taskName, Callable<? extends T> task) {
    return doFork(taskName, task, TaskNameDeriver.deriveCallSite(task));
  }

  private <T> Subtask<T> doFork(
      String taskName, Callable<? extends T> task, TaskNameDeriver.CallSiteInfo callSite) {
    long id = taskIdCounter.incrementAndGet();

    var forked = new TaskForkedEvent();
    forked.scopeId = this.scopeId;
    forked.scopeName = name;
    forked.taskId = id;
    forked.taskName = taskName;
    forked.threadName = Thread.currentThread().getName();
    if (callSite != null) {
      forked.callSiteClassName = callSite.className();
      forked.callSiteMethodName = callSite.methodName();
      forked.callSiteLine = callSite.line();
    }
    forked.commit();

    return scope.fork(
        () -> {
          try {
            T result = task.call();
            var ev = new TaskSucceededEvent();
            ev.scopeId = this.scopeId;
            ev.scopeName = name;
            ev.taskId = id;
            ev.threadName = Thread.currentThread().getName();
            ev.commit();
            return result;
          } catch (InterruptedException e) {
            var ev = new TaskCancelledEvent();
            ev.scopeId = this.scopeId;
            ev.scopeName = name;
            ev.taskId = id;
            ev.threadName = Thread.currentThread().getName();
            ev.commit();
            Thread.currentThread().interrupt();
            throw e;
          } catch (Exception e) {
            var ev = new TaskFailedEvent();
            ev.scopeId = this.scopeId;
            ev.scopeName = name;
            ev.taskId = id;
            ev.threadName = Thread.currentThread().getName();
            ev.exceptionType = e.getClass().getName();
            ev.exceptionMessage = e.getMessage();
            ev.exceptionStackTrace = TaskFailedEvent.formatStackTrace(e);
            ev.commit();
            throw e;
          }
        });
  }
```

The Javadoc on `fork(Callable)` above these lines (lines 210-226) is unchanged.

- [ ] **Step 5: Run the tests to confirm they pass**

Run: `mvn -pl scope-tracer-core -q test -Dtest=TracedScopeTest`
Expected: PASS — all existing task-naming tests plus the three new callSite tests.

- [ ] **Step 6: Update `docs/jfr-events.md`**

In the "Event catalog" table, replace the `TaskForked` row:

```
| `com.ionutbanu.scopetracer.TaskForked`      | `TaskForkedEvent`    | `String taskName`      | When a subtask is submitted via `fork`. `taskName` is a human-readable label: either the explicit name passed to `TracedScope.fork(String, Callable)`, the `Callable`'s simple class name when not a lambda, or `SimpleClass#method:line` derived from the call site (the agent uses the same derivation). The line-number suffix disambiguates sibling forks in the same method. Nullable; absent in recordings made before this field was added — use `event.hasField("taskName")` before reading. |
```

with:

```
| `com.ionutbanu.scopetracer.TaskForked`      | `TaskForkedEvent`    | `String taskName`, `String callSiteClassName`, `String callSiteMethodName`, `int callSiteLine` | When a subtask is submitted via `fork`. `taskName` is a human-readable label: either the explicit name passed to `TracedScope.fork(String, Callable)`, the `Callable`'s simple class name when not a lambda, or `SimpleClass#method:line` derived from the call site (the agent uses the same derivation). The `callSite*` fields capture the fork's source location independently of `taskName` — populated even when an explicit label was supplied — for tooling such as the IntelliJ plugin's click-to-source navigation. `callSiteMethodName` and `callSiteLine` (`0` = unknown) are only present for the caller-frame derivation tier; a named `Callable` class sets only `callSiteClassName`. All four fields are nullable/zero-default and absent in recordings made before they were added — use `event.hasField(...)` before reading. |
```

- [ ] **Step 7: Run the full core test suite**

Run: `mvn -pl scope-tracer-core -q test`
Expected: PASS — all tests in the module, including the ones from Task 1.

- [ ] **Step 8: Commit**

```bash
git add scope-tracer-core/src/main/java/com/ionutbanu/scopetracer/core/events/TaskForkedEvent.java \
        scope-tracer-core/src/main/java/com/ionutbanu/scopetracer/core/TracedScope.java \
        scope-tracer-core/src/test/java/com/ionutbanu/scopetracer/core/TracedScopeTest.java \
        docs/jfr-events.md
git commit -m "feat(core): capture fork call site independently of the task label"
```

---

### Task 3: Analyzer — add `CallSite` model type and extend `TaskRecord`

**Files:**
- Create: `scope-tracer-analyzer/src/main/java/com/ionutbanu/scopetracer/analyzer/model/CallSite.java`
- Modify: `scope-tracer-analyzer/src/main/java/com/ionutbanu/scopetracer/analyzer/model/TaskRecord.java`

**Interfaces:**
- Consumes: nothing from earlier tasks (pure model addition).
- Produces (for Tasks 4-5): `com.ionutbanu.scopetracer.analyzer.model.CallSite(String className, String methodName, Integer line)` — a plain record, analyzer-local (the analyzer module deliberately doesn't depend on the plugin's classes). `TaskRecord` gains an 8th component `CallSite callSite`; its existing 7-argument constructor is preserved as an auxiliary constructor defaulting `callSite` to `null`, so all existing call sites that don't care about call-site data (`HtmlRendererTest`'s ten `new TaskRecord(...)` calls, `TraceModelJsonTest`'s two) keep compiling unchanged.

- [ ] **Step 1: Create the `CallSite` record**

```java
package com.ionutbanu.scopetracer.analyzer.model;

/**
 * The source location of a {@code fork()} call, captured independently of the task's display
 * label ({@link TaskRecord#taskName()}) — populated even when the label was supplied explicitly.
 *
 * @param className simple name of the declaring class (the lambda's enclosing class, or the
 *     {@link java.util.concurrent.Callable}'s own class when it's a named user class); always
 *     present.
 * @param methodName enclosing method name; present only when derived from a caller stack frame
 *     (lambda/method reference), {@code null} for a named {@code Callable} class.
 * @param line source line of the caller frame; {@code null} when unknown or not applicable.
 */
public record CallSite(String className, String methodName, Integer line) {}
```

- [ ] **Step 2: Extend `TaskRecord` with the new component and an auxiliary constructor**

Replace the full file content of `TaskRecord.java` with:

```java
package com.ionutbanu.scopetracer.analyzer.model;

import java.time.Instant;

/**
 * Represents one forked subtask within a {@link ScopeRecord}.
 *
 * @param taskId per-scope monotonic id, starting at 1.
 * @param taskName human-readable label for this task (e.g. {@code "findUser"} or {@code
 *     "OrderService#checkout"}); {@code null} when no label was emitted (older recordings or when
 *     auto-derivation failed).
 * @param threadName name of the virtual/platform thread that ran this task; blank for unnamed
 *     virtual threads.
 * @param threadId JFR Java thread ID of the thread that executed this task; {@code -1} if unknown.
 * @param forkTime when the task was submitted via {@code TracedScope.fork()}.
 * @param completionTime when the task terminated; {@code null} for truncated recordings.
 * @param outcome terminal outcome; {@code null} for truncated recordings.
 * @param callSite the fork's source location, captured independently of {@code taskName}; {@code
 *     null} when derivation failed or the recording predates this field.
 */
public record TaskRecord(
    long taskId,
    String taskName,
    String threadName,
    long threadId,
    Instant forkTime,
    Instant completionTime,
    TaskOutcome outcome,
    CallSite callSite) {

  /**
   * Convenience constructor for callers that don't have call-site data (e.g. tests exercising the
   * rendering pipeline, which never reads {@link #callSite()}). Defaults {@code callSite} to {@code
   * null}.
   */
  public TaskRecord(
      long taskId,
      String taskName,
      String threadName,
      long threadId,
      Instant forkTime,
      Instant completionTime,
      TaskOutcome outcome) {
    this(taskId, taskName, threadName, threadId, forkTime, completionTime, outcome, null);
  }
}
```

- [ ] **Step 3: Confirm existing callers still compile and pass**

Run: `mvn -pl scope-tracer-analyzer -am -q test -Dtest=HtmlRendererTest,TraceModelJsonTest`
Expected: PASS — no source changes were needed in either test file; the auxiliary constructor absorbs all existing 7-argument call sites.

- [ ] **Step 4: Commit**

```bash
git add scope-tracer-analyzer/src/main/java/com/ionutbanu/scopetracer/analyzer/model/CallSite.java \
        scope-tracer-analyzer/src/main/java/com/ionutbanu/scopetracer/analyzer/model/TaskRecord.java
git commit -m "feat(analyzer): add CallSite model type and thread it through TaskRecord"
```

---

### Task 4: Analyzer — `JfrParser` reads the structured call-site fields

**Files:**
- Modify: `scope-tracer-analyzer/src/main/java/com/ionutbanu/scopetracer/analyzer/JfrParser.java`
- Test: `scope-tracer-analyzer/src/test/java/com/ionutbanu/scopetracer/analyzer/JfrParserTest.java`

**Interfaces:**
- Consumes: Task 2's `TaskForkedEvent` fields (`callSiteClassName`, `callSiteMethodName`, `callSiteLine`); Task 3's `CallSite` record and `TaskRecord`'s 8-argument constructor.
- Produces (for Task 5): `JfrParser.parse(Path)`'s returned `TraceModel` has `TaskRecord.callSite()` populated for every task whose recording carries the new fields; `null` for older recordings or when derivation failed at emission time.

- [ ] **Step 1: Write failing tests**

Add these tests to `JfrParserTest.java`, right after `autoDerivedTaskNameSurvivesRoundTrip` (before the closing brace of the class):

```java

  // --- callSite round-trip ---

  /**
   * The fix this feature delivers: call-site data is captured even when an explicit label is
   * supplied to {@link TracedScope#fork(String, java.util.concurrent.Callable)}.
   */
  @Test
  void explicitlyNamedForkStillCapturesCallSite() throws Exception {
    var model =
        capture(
            "explicit-name-callsite",
            () -> {
              try (var scope =
                  TracedScope.open("explicit-name-callsite", Thread.ofPlatform().factory())) {
                scope.fork("findUser", () -> 1);
                scope.join();
              }
            });

    var task = model.scopes().get(0).tasks().get(0);
    assertThat(task.taskName()).isEqualTo("findUser");
    assertThat(task.callSite()).isNotNull();
    assertThat(task.callSite().className()).isEqualTo("JfrParserTest");
    assertThat(task.callSite().methodName()).isEqualTo("explicitlyNamedForkStillCapturesCallSite");
    assertThat(task.callSite().line()).isPositive();
  }

  /** Auto-derived forks also populate {@code callSite}. */
  @Test
  void autoDerivedForkCapturesCallSite() throws Exception {
    var model =
        capture(
            "auto-derived-callsite",
            () -> {
              try (var scope =
                  TracedScope.open("auto-derived-callsite", Thread.ofPlatform().factory())) {
                scope.fork(() -> 1);
                scope.join();
              }
            });

    var task = model.scopes().get(0).tasks().get(0);
    assertThat(task.callSite()).isNotNull();
    assertThat(task.callSite().className()).isEqualTo("JfrParserTest");
    assertThat(task.callSite().methodName()).isEqualTo("autoDerivedForkCapturesCallSite");
    assertThat(task.callSite().line()).isPositive();
  }
```

- [ ] **Step 2: Run the tests to confirm they fail**

Run: `mvn -pl scope-tracer-analyzer -am -q test -Dtest=JfrParserTest`
Expected: FAIL — `task.callSite()` is `null` (JfrParser doesn't read the new fields yet).

- [ ] **Step 3: Update `JfrParser.java`**

Add the import, alongside the existing ones at the top:

```java
import com.ionutbanu.scopetracer.analyzer.model.CallSite;
```

Change the `ForkData` record definition (currently line 313) to:

```java
  private record ForkData(Instant forkTime, String threadName, String taskName, CallSite callSite) {}
```

Replace the `TASK_FORKED` case body (currently lines 116-125) with:

```java
          case TASK_FORKED -> {
            if (scopeOpens.containsKey(scopeId)) {
              // taskName was added later — older recordings won't have it. hasField is required.
              String taskName = event.hasField("taskName") ? event.getString("taskName") : null;
              CallSite callSite = readCallSite(event);
              forks
                  .computeIfAbsent(scopeId, k -> new HashMap<>())
                  .put(taskId, new ForkData(time, threadName, taskName, callSite));
            }
            // else: fork before open should not occur in practice; silently drop.
          }
```

Add this private helper method right after `parse(Path)` (before `storeCompletion`):

```java
  /**
   * Reads the {@code callSite*} fields from a {@code TASK_FORKED} event into a {@link CallSite}, or
   * {@code null} when no class name was derived at emission time or the recording predates these
   * fields.
   */
  private static CallSite readCallSite(jdk.jfr.consumer.RecordedEvent event) {
    if (!event.hasField("callSiteClassName")) return null;
    String className = event.getString("callSiteClassName");
    if (className == null) return null;
    String methodName =
        event.hasField("callSiteMethodName") ? event.getString("callSiteMethodName") : null;
    Integer line = null;
    if (event.hasField("callSiteLine")) {
      int rawLine = event.getInt("callSiteLine");
      if (rawLine > 0) line = rawLine;
    }
    return new CallSite(className, methodName, line);
  }
```

Update the `TaskRecord` construction inside `parse(Path)` (currently lines 192-201) to pass the new component:

```java
        tasks.add(
            new TaskRecord(
                id,
                fork.taskName(),
                completion != null ? completion.threadName() : fork.threadName(),
                completion != null ? completion.executingThreadId() : -1L,
                fork.forkTime(),
                completion != null ? completion.completionTime() : null,
                completion != null ? completion.outcome() : null,
                fork.callSite()));
```

- [ ] **Step 4: Run the tests to confirm they pass**

Run: `mvn -pl scope-tracer-analyzer -am -q test -Dtest=JfrParserTest`
Expected: PASS — all existing `JfrParserTest` tests plus the two new callSite ones.

- [ ] **Step 5: Commit**

```bash
git add scope-tracer-analyzer/src/main/java/com/ionutbanu/scopetracer/analyzer/JfrParser.java \
        scope-tracer-analyzer/src/test/java/com/ionutbanu/scopetracer/analyzer/JfrParserTest.java
git commit -m "feat(analyzer): parse structured call-site fields into TaskRecord"
```

---

### Task 5: Analyzer — `TraceModelJson` serializes `callSite`

**Files:**
- Modify: `scope-tracer-analyzer/src/main/java/com/ionutbanu/scopetracer/analyzer/TraceModelJson.java`
- Test: `scope-tracer-analyzer/src/test/java/com/ionutbanu/scopetracer/analyzer/TraceModelJsonTest.java`

**Interfaces:**
- Consumes: Task 3's `CallSite` record and `TaskRecord.callSite()`.
- Produces (for Task 6): `TraceModelJson.toJson(TraceModel)` emits, per task, a `"callSite"` key: `{"className":"...","methodName":"..."|null,"line":42|null}` when present, otherwise `null`.

- [ ] **Step 1: Write failing tests**

Add the import to `TraceModelJsonTest.java`:

```java
import com.ionutbanu.scopetracer.analyzer.model.CallSite;
```

Add this test, right after `failedOutcomeIncludesExceptionFields`:

```java
  @Test
  void callSiteSerialisedWhenPresent() {
    var tasks =
        List.of(
            new TaskRecord(
                1,
                "findUser",
                "worker",
                -1L,
                T1,
                T2,
                new TaskOutcome.Success(),
                new CallSite("OrderService", "checkout", 42)));
    var scope = new ScopeRecord(1L, "scope", "main", -1L, T0, T2, tasks, null);
    var json = TraceModelJson.toJson(new TraceModel(List.of(scope)));
    assertThat(json).contains("\"callSite\":{\"className\":\"OrderService\"");
    assertThat(json).contains("\"methodName\":\"checkout\"");
    assertThat(json).contains("\"line\":42");
  }
```

Extend the existing `nullFieldsAreSerialisedAsJsonNull` test (it already constructs a `TaskRecord` via the 7-argument constructor, which defaults `callSite` to `null`) by adding one more assertion line after `assertThat(json).contains("\"parent\":null");`:

```java
    assertThat(json).contains("\"callSite\":null");
```

- [ ] **Step 2: Run the tests to confirm they fail**

Run: `mvn -pl scope-tracer-analyzer -am -q test -Dtest=TraceModelJsonTest`
Expected: FAIL — no `"callSite"` key is emitted yet.

- [ ] **Step 3: Update `TraceModelJson.java`**

Add the import:

```java
import com.ionutbanu.scopetracer.analyzer.model.CallSite;
```

Update the class-level schema doc-comment's `tasks` example (inside the `<pre>{@code ... }</pre>` block) from:

```
 *       "tasks": [
 *         {
 *           "taskId": 1, "taskName": "...", "threadName": "...", "threadId": 17,
 *           "forkTime": "...", "completionTime": "..." | null,
 *           "outcome": { "type": "success" | "failed" | "cancelled" | null,
 *                         "exceptionType": "...", "exceptionMessage": "...",
 *                         "stackTrace": "..." }
 *         }
 *       ]
```

to:

```
 *       "tasks": [
 *         {
 *           "taskId": 1, "taskName": "...", "threadName": "...", "threadId": 17,
 *           "forkTime": "...", "completionTime": "..." | null,
 *           "outcome": { "type": "success" | "failed" | "cancelled" | null,
 *                         "exceptionType": "...", "exceptionMessage": "...",
 *                         "stackTrace": "..." },
 *           "callSite": { "className": "...", "methodName": "..." | null, "line": 42 | null } | null
 *         }
 *       ]
```

Replace `writeTask` (currently lines 96-113) with:

```java
  private static void writeTask(StringBuilder sb, TaskRecord task) {
    sb.append('{');
    field(sb, "taskId", task.taskId());
    sb.append(',');
    field(sb, "taskName", task.taskName());
    sb.append(',');
    field(sb, "threadName", task.threadName());
    sb.append(',');
    field(sb, "threadId", task.threadId());
    sb.append(',');
    fieldInstant(sb, "forkTime", task.forkTime());
    sb.append(',');
    fieldInstant(sb, "completionTime", task.completionTime());
    sb.append(',');
    sb.append("\"outcome\":");
    writeOutcome(sb, task.outcome());
    sb.append(',');
    sb.append("\"callSite\":");
    writeCallSite(sb, task.callSite());
    sb.append('}');
  }

  private static void writeCallSite(StringBuilder sb, CallSite callSite) {
    if (callSite == null) {
      sb.append("null");
      return;
    }
    sb.append('{');
    field(sb, "className", callSite.className());
    sb.append(',');
    field(sb, "methodName", callSite.methodName());
    sb.append(',');
    sb.append("\"line\":").append(callSite.line() == null ? "null" : callSite.line());
    sb.append('}');
  }
```

- [ ] **Step 4: Run the tests to confirm they pass**

Run: `mvn -pl scope-tracer-analyzer -am -q test -Dtest=TraceModelJsonTest`
Expected: PASS.

- [ ] **Step 5: Run the full analyzer test suite**

Run: `mvn -pl scope-tracer-analyzer -am -q test`
Expected: PASS — includes `AnalyzerMainTest`, which round-trips through `TraceModelJson`/`JfrParser`.

- [ ] **Step 6: Commit**

```bash
git add scope-tracer-analyzer/src/main/java/com/ionutbanu/scopetracer/analyzer/TraceModelJson.java \
        scope-tracer-analyzer/src/test/java/com/ionutbanu/scopetracer/analyzer/TraceModelJsonTest.java
git commit -m "feat(analyzer): serialize callSite in the JSON trace model"
```

---

### Task 6: Plugin model — `PluginTaskRecord` gains `callSite`

**Files:**
- Modify: `scope-tracer-plugin/model/src/main/java/com/ionutbanu/scopetracer/plugin/model/PluginTaskRecord.java`
- Test: `scope-tracer-plugin/model/src/test/java/com/ionutbanu/scopetracer/plugin/model/TraceModelJsonParserTest.java`

**Interfaces:**
- Consumes: Task 5's JSON `"callSite"` key shape; the existing `com.ionutbanu.scopetracer.plugin.model.CallSite(String className, String methodName, Integer line)` record (already defined in this module, unchanged).
- Produces (for Task 7): `PluginTaskRecord.callSite()` (type `CallSite`, nullable), populated by `TraceModelJsonParser.parse(String)` via Gson's built-in record support (Gson 2.10.1) — no custom `TypeAdapter` needed, since `CallSite`'s components are plain `String`/`Integer`.

- [ ] **Step 1: Write failing tests**

Add these tests to `TraceModelJsonParserTest.java`, right after `parsesSuccessFailedAndCancelledOutcomes`:

```java

  @Test
  void parsesCallSiteWhenPresent() {
    var json =
        """
        {"scopes":[
          {"scopeId":1,"name":"checkout","ownerThreadName":"main","ownerThreadId":1,
           "openTime":"2026-01-01T00:00:00Z","closeTime":"2026-01-01T00:00:01Z","parent":null,
           "tasks":[
             {"taskId":1,"taskName":"findUser","threadName":"vt-1","threadId":10,
              "forkTime":"2026-01-01T00:00:00.100Z","completionTime":"2026-01-01T00:00:00.300Z",
              "outcome":{"type":"success"},
              "callSite":{"className":"OrderService","methodName":"checkout","line":42}}
           ]}
        ]}
        """;

    var model = TraceModelJsonParser.parse(json);

    var task = model.scopes().get(0).tasks().get(0);
    assertThat(task.callSite()).isEqualTo(new CallSite("OrderService", "checkout", 42));
  }

  @Test
  void treatsMissingCallSiteKeyAsNull() {
    // Recordings/JSON produced before the callSite field existed: the key is entirely absent.
    var json =
        """
        {"scopes":[
          {"scopeId":1,"name":"checkout","ownerThreadName":"main","ownerThreadId":1,
           "openTime":"2026-01-01T00:00:00Z","closeTime":"2026-01-01T00:00:01Z","parent":null,
           "tasks":[
             {"taskId":1,"taskName":"findUser","threadName":"vt-1","threadId":10,
              "forkTime":"2026-01-01T00:00:00.100Z","completionTime":"2026-01-01T00:00:00.300Z",
              "outcome":{"type":"success"}}
           ]}
        ]}
        """;

    var model = TraceModelJsonParser.parse(json);

    var task = model.scopes().get(0).tasks().get(0);
    assertThat(task.callSite()).isNull();
  }
```

- [ ] **Step 2: Run the tests to confirm they fail to compile**

Run: `cd scope-tracer-plugin && ./gradlew :model:test`
Expected: FAIL — `cannot find symbol: method callSite()` (`PluginTaskRecord` doesn't have the field yet).

- [ ] **Step 3: Add the field to `PluginTaskRecord`**

Replace the full file content with:

```java
package com.ionutbanu.scopetracer.plugin.model;

import java.time.Instant;

public record PluginTaskRecord(
    long taskId,
    String taskName,
    String threadName,
    long threadId,
    Instant forkTime,
    Instant completionTime,
    PluginTaskOutcome outcome,
    CallSite callSite) {}
```

- [ ] **Step 4: Run the tests to confirm they pass**

Run: `cd scope-tracer-plugin && ./gradlew :model:test`
Expected: PASS — all `:model` tests, including the two new ones. No changes needed in `TraceModelJsonParser.java`; Gson maps the nested `CallSite` object by field name automatically.

- [ ] **Step 5: Commit**

```bash
git add scope-tracer-plugin/model/src/main/java/com/ionutbanu/scopetracer/plugin/model/PluginTaskRecord.java \
        scope-tracer-plugin/model/src/test/java/com/ionutbanu/scopetracer/plugin/model/TraceModelJsonParserTest.java
git commit -m "feat(plugin-model): parse callSite into PluginTaskRecord"
```

---

### Task 7: Plugin model — `CallSiteParser.forTask()` resolution helper

**Files:**
- Modify: `scope-tracer-plugin/model/src/main/java/com/ionutbanu/scopetracer/plugin/model/CallSiteParser.java`
- Test: `scope-tracer-plugin/model/src/test/java/com/ionutbanu/scopetracer/plugin/model/CallSiteParserTest.java`

**Interfaces:**
- Consumes: Task 6's `PluginTaskRecord.callSite()`.
- Produces (for Task 8): `public static CallSite CallSiteParser.forTask(PluginTaskRecord task)` — returns `task.callSite()` when present, otherwise falls back to `parse(task.taskName())`; `null` if neither resolves.

- [ ] **Step 1: Write failing tests**

Add these tests to `CallSiteParserTest.java`, right after `returnsNullForLowercaseExplicitLabel`:

```java

  @Test
  void forTaskPrefersStructuredCallSiteOverParsingTaskName() {
    var callSite = new CallSite("OrderService", "checkout", 42);
    var task = new PluginTaskRecord(1, "findUser", "vt-1", 10, null, null, null, callSite);

    assertThat(CallSiteParser.forTask(task)).isEqualTo(callSite);
  }

  @Test
  void forTaskFallsBackToParsingTaskNameWhenCallSiteAbsent() {
    var task = new PluginTaskRecord(1, "OrderService#checkout:42", "vt-1", 10, null, null, null, null);

    assertThat(CallSiteParser.forTask(task)).isEqualTo(new CallSite("OrderService", "checkout", 42));
  }

  @Test
  void forTaskReturnsNullWhenNeitherSourceResolves() {
    // Legacy recording with an explicit hand-picked label and no structured callSite — the gap
    // this feature fixes for new recordings, still unresolvable for old ones.
    var task = new PluginTaskRecord(1, "validateOrder", "vt-1", 10, null, null, null, null);

    assertThat(CallSiteParser.forTask(task)).isNull();
  }
```

- [ ] **Step 2: Run the tests to confirm they fail to compile**

Run: `cd scope-tracer-plugin && ./gradlew :model:test`
Expected: FAIL — `cannot find symbol: method forTask`.

- [ ] **Step 3: Add `forTask` to `CallSiteParser`**

Update the class-level Javadoc and add the method. Replace the full file content with:

```java
package com.ionutbanu.scopetracer.plugin.model;

import java.util.regex.Pattern;

/**
 * Parses the {@code SimpleClassName[#methodName[:line]]} label convention shared by
 * scope-tracer-core's and scope-tracer-agent's {@code TaskNameDeriver}/{@code ScopeNameDeriver}.
 *
 * <p>Recordings produced after structured call-site capture was added carry a real {@link
 * CallSite} directly on {@link PluginTaskRecord#callSite()} — prefer {@link
 * #forTask(PluginTaskRecord)}, which uses that when present. {@link #parse(String)} remains the
 * fallback for older recordings, where an explicitly-named fork's call site is unrecoverable from
 * {@code taskName} alone (see the lowercase-rejection rule below).
 */
public final class CallSiteParser {

  private static final Pattern PATTERN =
      Pattern.compile("([A-Za-z_$][A-Za-z0-9_$]*)(?:#([A-Za-z_$][A-Za-z0-9_$]*))?(?::(\\d+))?");

  private CallSiteParser() {}

  /**
   * Resolves the navigable {@link CallSite} for {@code task}: the structured {@link
   * PluginTaskRecord#callSite()} when present, otherwise a best-effort fallback that parses {@link
   * PluginTaskRecord#taskName()} via {@link #parse(String)}.
   *
   * @return the resolved {@link CallSite}, or {@code null} if neither source yields one.
   */
  public static CallSite forTask(PluginTaskRecord task) {
    if (task.callSite() != null) {
      return task.callSite();
    }
    return parse(task.taskName());
  }

  /** Returns the parsed {@link CallSite}, or {@code null} if {@code raw} doesn't match. */
  public static CallSite parse(String raw) {
    if (raw == null) {
      return null;
    }
    var matcher = PATTERN.matcher(raw);
    if (!matcher.matches()) {
      return null;
    }
    var className = matcher.group(1);
    var methodName = matcher.group(2);
    // A bare identifier (no #method suffix) is ambiguous: TaskNameDeriver emits it for a named
    // Callable class (PascalCase by convention), but scope.fork(String, Callable) emits an
    // arbitrary hand-picked label the same way (camelCase by convention — every example in the
    // README and real usage). Only the former is a real class worth a PSI lookup; treat a
    // lowercase-starting bare identifier as an unparseable label instead.
    if (methodName == null && !Character.isUpperCase(className.charAt(0))) {
      return null;
    }
    var line = matcher.group(3);
    return new CallSite(className, methodName, line == null ? null : Integer.valueOf(line));
  }
}
```

- [ ] **Step 4: Run the tests to confirm they pass**

Run: `cd scope-tracer-plugin && ./gradlew :model:test`
Expected: PASS — all `CallSiteParserTest` tests, including the three new `forTask` ones.

- [ ] **Step 5: Commit**

```bash
git add scope-tracer-plugin/model/src/main/java/com/ionutbanu/scopetracer/plugin/model/CallSiteParser.java \
        scope-tracer-plugin/model/src/test/java/com/ionutbanu/scopetracer/plugin/model/CallSiteParserTest.java
git commit -m "feat(plugin-model): add CallSiteParser.forTask resolution helper"
```

---

### Task 8: Plugin UI — `ScopeTracerPanel` resolves call sites per row

**Files:**
- Modify: `scope-tracer-plugin/src/main/java/com/ionutbanu/scopetracer/plugin/ScopeTracerPanel.java`

**Interfaces:**
- Consumes: Task 7's `CallSiteParser.forTask(PluginTaskRecord)`.
- Produces: double-clicking a task row now navigates correctly for explicitly-named forks (previously a no-op); behavior for auto-derived forks and legacy recordings is unchanged.

No automated test: this module's `test` task is intentionally disabled project-wide (`tasks.test { enabled = false }` in `scope-tracer-plugin/build.gradle.kts` — "No tests live directly in this module for this milestone... requires a running IDE sandbox"). The resolution logic itself (`CallSiteParser.forTask`) is already covered by Task 7's `:model` tests; this task is a thin UI wiring change, verified by compilation.

- [ ] **Step 1: Update `ScopeTracerPanel.java`**

Add the import, alongside the existing `com.ionutbanu.scopetracer.plugin.model.*` imports:

```java
import com.ionutbanu.scopetracer.plugin.model.CallSite;
```

Replace the field declaration and its comment (currently lines 49-52):

```java
  // Parallel to tableModel's rows: the raw scope/task name behind each row, used for
  // click-to-source navigation. Explicit scope names (e.g. "order-processing-ORD-001") and
  // unlabeled tasks won't parse as a call site, so double-click on those rows is a no-op.
  private final List<String> rowRawNames = new ArrayList<>();
```

with:

```java
  // Parallel to tableModel's rows: the resolved CallSite (or null) behind each row, used for
  // click-to-source navigation. Scope header rows are always null — core never derives
  // scope-level call sites, since TracedScope.open(String) always takes a literal name. Task rows
  // resolve via CallSiteParser.forTask, which prefers the structured call site captured at fork
  // time and falls back to parsing the legacy taskName convention for older recordings.
  private final List<CallSite> rowCallSites = new ArrayList<>();
```

Replace the mouse listener body (currently lines 66-84):

```java
          @Override
          public void mouseClicked(MouseEvent e) {
            if (e.getClickCount() != 2) {
              return;
            }
            var row = table.rowAtPoint(e.getPoint());
            if (row < 0 || row >= rowCallSites.size()) {
              LOG.info("Scope Tracer: double-click at row " + row + " — out of range, ignoring");
              return;
            }
            var callSite = rowCallSites.get(row);
            LOG.info("Scope Tracer: double-click row " + row + " -> " + callSite);
            if (callSite != null) {
              TaskSourceNavigator.navigate(project, table, callSite);
            }
          }
```

Replace the `populate` method body (currently lines 123-141):

```java
  private void populate(PluginTraceModel model) {
    tableModel.setRowCount(0);
    rowCallSites.clear();
    for (PluginScopeRecord scope : model.scopes()) {
      tableModel.addRow(new Object[] {scope.name(), scope.ownerThreadName(), "", "", ""});
      rowCallSites.add(null);
      for (PluginTaskRecord task : scope.tasks()) {
        tableModel.addRow(
            new Object[] {
              "    #" + task.taskId() + " " + emptyToDash(task.taskName()),
              task.threadName(),
              formatOffset(scope.openTime(), task.forkTime()),
              formatDuration(task.forkTime(), task.completionTime()),
              formatOutcome(task.outcome())
            });
        rowCallSites.add(CallSiteParser.forTask(task));
      }
    }
  }
```

- [ ] **Step 2: Compile the plugin module**

Run: `cd scope-tracer-plugin && ./gradlew compileJava`
Expected: SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add scope-tracer-plugin/src/main/java/com/ionutbanu/scopetracer/plugin/ScopeTracerPanel.java
git commit -m "feat(plugin): resolve call sites via CallSiteParser.forTask"
```

---

### Task 9: Full-repo verification

**Files:** none — this task runs the project's standard verification commands and fixes anything they surface.

- [ ] **Step 1: Full Maven verify from the repo root**

Run: `mvn -q verify`
Expected: PASS — all 122+ unit tests (surefire) plus the agent's integration tests (failsafe). Per `CLAUDE.md`, this must be the unrestricted root-level command (not `-pl`), since `scope-tracer-core` was touched.

If this fails, fix the surfaced issue in the relevant task's files and re-run — do not skip or `@Ignore` any test.

- [ ] **Step 2: Full Gradle build for the plugin**

Run: `cd scope-tracer-plugin && ./gradlew build`
Expected: SUCCESS — builds `:model` (with tests) and the root plugin module (compile-only, tests disabled by design), and bundles the analyzer jar as a resource.

- [ ] **Step 3: Spotless check**

Run: `mvn spotless:check` (or `mvn spotless:apply` if it reports violations, then re-run `mvn -q verify`)
Expected: no formatting violations across the touched Maven modules.

- [ ] **Step 4: Final review of the diff**

Run: `git log --oneline main..HEAD` and `git diff main --stat`
Expected: nine commits (Tasks 1-8) matching this plan, touching exactly the files listed above — no stray changes.
