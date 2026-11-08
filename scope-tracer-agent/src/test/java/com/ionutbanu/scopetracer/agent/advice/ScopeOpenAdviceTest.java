package com.ionutbanu.scopetracer.agent.advice;

import static org.assertj.core.api.Assertions.assertThat;

import com.ionutbanu.scopetracer.agent.AgentState;
import com.ionutbanu.scopetracer.agent.CaptureFilter;
import com.ionutbanu.scopetracer.agent.ScopeState;
import java.nio.file.Path;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import net.bytebuddy.asm.Advice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link ScopeOpenAdvice} called directly, as plain static methods. The advice is
 * woven into every public static {@code StructuredTaskScope.open}, and an overload that delegates
 * to another one runs it once per frame on the same returned scope, innermost first. Calling {@code
 * onOpen} several times with one object is that, for any number of delegating overloads and
 * whichever of them the JDK makes the one that does the work.
 *
 * <p>That the advice is actually woven into the JDK's overloads is tested by {@code
 * ScopeTracerAgentIT}, which runs a subject under the agent.
 */
class ScopeOpenAdviceTest {

  private static final String CONFIGURED_NAME = "scope-open-advice-test";

  @TempDir Path tempDir;

  @AfterEach
  void restoreAgentState() {
    AgentState.FILTER = CaptureFilter.PASSTHROUGH;
    AgentState.PENDING_SCOPE_NAME.remove();
  }

  @Test
  void exitAdviceSuppressesEveryThrowable() throws Exception {
    var onOpen = ScopeOpenAdvice.class.getMethod("onOpen", Object.class);
    assertThat(onOpen.getAnnotation(Advice.OnMethodExit.class).suppress())
        .isEqualTo(Throwable.class);
  }

  /**
   * Three exits on one scope, as for an overload that delegates twice, give one {@code
   * ScopeOpened}, with the name the constructor advice left for the first, and one registered
   * state.
   */
  @Test
  void everyOpenFrameOfOneScopeAfterTheFirstEmitsNothing() throws Exception {
    Object scope = new Object();
    var jfr = tempDir.resolve("open-advice.jfr");
    try (var recording = new Recording()) {
      recording.enable("com.ionutbanu.scopetracer.*");
      recording.start();

      AgentState.PENDING_SCOPE_NAME.set(CONFIGURED_NAME);
      ScopeOpenAdvice.onOpen(scope);
      ScopeOpenAdvice.onOpen(scope);
      ScopeOpenAdvice.onOpen(scope);

      recording.stop();
      recording.dump(jfr);
    }

    ScopeState state = AgentState.SCOPE_STATES.remove(scope);
    var opened =
        RecordingFile.readAllEvents(jfr).stream()
            .filter(e -> e.getEventType().getName().equals("com.ionutbanu.scopetracer.ScopeOpened"))
            .toList();
    assertThat(opened).hasSize(1);
    assertThat(opened.get(0).getString("scopeName")).isEqualTo(CONFIGURED_NAME);
    assertThat(state).isNotNull();
    assertThat(state.name()).isEqualTo(CONFIGURED_NAME);
    assertThat(opened)
        .extracting((RecordedEvent e) -> e.getLong("scopeId"))
        .containsExactly(state.scopeId());
  }

  /**
   * With {@code sample.rate=0.5} the first exit on a scope draws once, and later exits on that
   * scope neither draw again nor replace its state: a kept scope keeps its first state, a dropped
   * scope stays dropped. Over 1,000 scopes about half are kept (500 ± 16; two draws would keep 750
   * ± 14).
   */
  @Test
  void theFilterDecidesOncePerScopeHoweverManyOpenFramesExit() {
    AgentState.FILTER = new CaptureFilter(null, null, null, null, 0.5);

    int kept = 0;
    for (int i = 0; i < 1_000; i++) {
      Object scope = new Object();
      ScopeOpenAdvice.onOpen(scope);
      ScopeState first = AgentState.SCOPE_STATES.get(scope);
      ScopeOpenAdvice.onOpen(scope);
      ScopeOpenAdvice.onOpen(scope);
      assertThat(AgentState.SCOPE_STATES.get(scope)).isSameAs(first);
      if (first != null) {
        kept++;
        AgentState.SCOPE_STATES.remove(scope);
      }
    }
    assertThat(kept).isBetween(400, 600);
  }

  /** Distinct scopes are distinct decisions: the guard keys on the scope instance, not the name. */
  @Test
  void distinctScopesWithOneNameAreEachRegistered() {
    Object first = new Object();
    Object second = new Object();
    AgentState.PENDING_SCOPE_NAME.set(CONFIGURED_NAME);
    ScopeOpenAdvice.onOpen(first);
    AgentState.PENDING_SCOPE_NAME.set(CONFIGURED_NAME);
    ScopeOpenAdvice.onOpen(second);

    ScopeState firstState = AgentState.SCOPE_STATES.remove(first);
    ScopeState secondState = AgentState.SCOPE_STATES.remove(second);
    assertThat(firstState).isNotNull();
    assertThat(secondState).isNotNull();
    assertThat(firstState.scopeId()).isNotEqualTo(secondState.scopeId());
  }
}
