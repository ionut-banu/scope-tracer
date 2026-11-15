package com.ionutbanu.scopetracer.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one formatter behind every {@code [scope-tracer] WARN} reason (issue #132): {@code Type:
 * message; caused by Type: message}, bounded, escaped, and never throwing.
 */
class AgentLogTest {

  private static String describe(Throwable t) {
    return AgentLog.describe(t);
  }

  /** A chain of {@code depth} entries: {@code level <depth-2>} ... {@code level 0}, then root. */
  private static Throwable chain(int depth) {
    Throwable t = new IOException("root");
    for (int i = 0; i < depth - 1; i++) {
      t = new RuntimeException("level " + i, t);
    }
    return t;
  }

  // --- criterion 4 ---

  @Test
  void typeAndMessageThenEachCauseJoinedByCausedBy() {
    var t = new IllegalStateException("outer", new IOException("middle", new Error("inner")));

    assertThat(describe(t))
        .isEqualTo(
            "IllegalStateException: outer; caused by IOException: middle; caused by Error: inner");
  }

  @Test
  void anAnonymousTypeIsShownByItsBinaryName() {
    var t = new RuntimeException() {};

    assertThat(describe(t)).isEqualTo(t.getClass().getName()).contains("$");
    var withMessage = new RuntimeException("boom") {};
    assertThat(describe(withMessage)).isEqualTo(withMessage.getClass().getName() + ": boom");
  }

  // --- criterion 5 ---

  @Test
  void aNullEmptyOrBlankMessageIsLeftOutWithItsColon() {
    assertThat(describe(new IOException())).isEqualTo("IOException");
    assertThat(describe(new IOException(""))).isEqualTo("IOException");
    assertThat(describe(new IOException("   "))).isEqualTo("IOException");
    assertThat(describe(new IOException(" \t\n"))).isEqualTo("IOException");
    assertThat(describe(new IOException("x", new IOException(" "))))
        .isEqualTo("IOException: x; caused by IOException");
  }

  @Test
  void nullGivesUnknownErrorAndDoesNotThrow() {
    assertThat(describe(null)).isEqualTo("unknown error");
  }

  // --- criterion 6 ---

  @Test
  void aMessageThatOnlyRepeatsTheCauseIsNotPrintedTwice() {
    assertThat(describe(new RuntimeException(new IOException("disk full"))))
        .isEqualTo("RuntimeException; caused by IOException: disk full");
    assertThat(describe(new UncheckedIOException("reading t.jfr", new IOException("disk full"))))
        .isEqualTo("UncheckedIOException: reading t.jfr; caused by IOException: disk full");
  }

  @Test
  void aCauseWhoseToStringThrowsKeepsTheMessage() {
    var cause =
        new IOException("disk full") {
          @Override
          public String toString() {
            throw new IllegalStateException("no toString");
          }
        };
    var t = new RuntimeException("java.io.IOException: disk full", cause);

    assertThat(describe(t))
        .isEqualTo(
            "RuntimeException: java.io.IOException: disk full; caused by "
                + cause.getClass().getName()
                + ": disk full");
  }

  // --- criterion 7 ---

  @Test
  void controlCharactersInAMessageAreEscaped() {
    String reason = describe(new RuntimeException("a\tb\r\nc\u001bd e\u0000f"));

    assertThat(reason)
        .isEqualTo("RuntimeException: a\\tb\\r\\nc\\u001bd\\u2028e\\u0000f")
        .doesNotContain("\n")
        .doesNotContain("\u001b");
  }

  @Test
  void aTypeAndAMessageAreCutAt300CharactersEach() {
    String reason = describe(new RuntimeException("x".repeat(1_000_000)));

    assertThat(reason).isEqualTo("RuntimeException: " + "x".repeat(300) + "...");
    assertThat(describe(new RuntimeException("y".repeat(300)))).endsWith("y".repeat(300));
  }

  @Test
  void aHugeMessageOnEveryEntryStaysUnder2500Characters() {
    Throwable t = new RuntimeException("m".repeat(1_000_000));
    for (int i = 0; i < 10; i++) t = new RuntimeException("z".repeat(1_000_000), t);

    assertThat(describe(t).length()).isLessThan(2_500);
  }

  @Test
  void aLongTypeNameIsCutToo() {
    class T extends RuntimeException {}
    // the name is short; the cut for types is the same code as for messages
    assertThat(AgentLog.escape("T".repeat(400), AgentLog.MAX_PART))
        .isEqualTo("T".repeat(300) + "...");
    assertThat(describe(new T())).isEqualTo("T");
  }

  @Test
  void aPathIsEscapedAndNeverCut() {
    String path = "/a/" + "d".repeat(500) + "\nb\u001b.jfr";

    assertThat(AgentLog.path(path)).isEqualTo("/a/" + "d".repeat(500) + "\\nb\\u001b.jfr");
  }

  // --- criterion 8 ---

  @Test
  void atMostFourEntriesThenAnEllipsis() {
    assertThat(describe(chain(11)))
        .isEqualTo(
            "RuntimeException: level 9; caused by RuntimeException: level 8; caused by"
                + " RuntimeException: level 7; caused by RuntimeException: level 6; caused by ...");
  }

  @Test
  void aFourDeepChainHasNoEllipsisAndFiveHasOne() {
    assertThat(describe(chain(4)))
        .isEqualTo(
            "RuntimeException: level 2; caused by RuntimeException: level 1; caused by"
                + " RuntimeException: level 0; caused by IOException: root");
    assertThat(describe(chain(5))).endsWith("; caused by ...");
  }

  // --- criterion 9 ---

  @Test
  void aCycleStopsAtTheFirstThrowableAlreadyPrinted() {
    var a = new IllegalStateException("a");
    var b = new IllegalArgumentException("b", a);
    a.initCause(b);

    assertThat(describe(a))
        .isEqualTo("IllegalStateException: a; caused by IllegalArgumentException: b");
  }

  @Test
  void aThrowableThatIsItsOwnCauseGivesOneEntry() {
    var self =
        new IllegalStateException("me") {
          @Override
          public synchronized Throwable getCause() {
            return this;
          }
        };

    assertThat(describe(self)).isEqualTo(self.getClass().getName() + ": me");
  }

  // --- criterion 10 ---

  @Test
  void aMessageThatThrowsLeavesTheTypeAndTheChainGoesOn() {
    var t =
        new IllegalStateException("x", new IOException("disk full")) {
          @Override
          public String getMessage() {
            throw new UnsupportedOperationException("no message");
          }
        };

    assertThat(describe(t))
        .isEqualTo(t.getClass().getName() + "; caused by IOException: disk full");
  }

  @Test
  void aCauseThatThrowsStopsTheChainAfterThatEntry() {
    var t =
        new IllegalStateException("top") {
          @Override
          public synchronized Throwable getCause() {
            throw new UnsupportedOperationException("no cause");
          }
        };

    assertThat(describe(t)).isEqualTo(t.getClass().getName() + ": top");
  }

  @Test
  void neverCallsToStringOrGetLocalizedMessageOnTheThrowable() {
    var t =
        new IllegalStateException("plain") {
          @Override
          public String toString() {
            throw new AssertionError("toString called");
          }

          @Override
          public String getLocalizedMessage() {
            throw new AssertionError("getLocalizedMessage called");
          }
        };

    assertThat(describe(t)).isEqualTo(t.getClass().getName() + ": plain");
  }

  @Test
  void throwsNothingForAnyErrorKind() {
    var t =
        new Error("x") {
          @Override
          public String getMessage() {
            throw new StackOverflowError();
          }

          @Override
          public synchronized Throwable getCause() {
            throw new OutOfMemoryError();
          }
        };

    assertThatCode(() -> describe(t)).doesNotThrowAnyException();
    assertThat(describe(t)).isEqualTo(t.getClass().getName());
  }

  // --- criterion 11 ---

  @Test
  void suppressedExceptionsAndStackTracesAreNotPrinted() {
    var t = new IOException("boom");
    t.addSuppressed(new IllegalStateException("suppressed one"));

    assertThat(describe(t))
        .isEqualTo("IOException: boom")
        .doesNotContain("suppressed")
        .doesNotContain("\tat ");
  }

  // --- criterion 15, line F, from the real entry point ---

  @Test
  void recordUnderARegularFileGivesOneEscapedWarningLine(@TempDir Path dir) throws Exception {
    Path file = Files.writeString(dir.resolve("a\nfile"), "x");
    Path target = file.resolve("x.jfr");
    var bytes = new ByteArrayOutputStream();
    PrintStream original = System.err;
    System.setErr(new PrintStream((OutputStream) bytes, true, StandardCharsets.UTF_8));
    try {
      AgentRecording.startIfRequested(AgentConfig.parse("record=" + target));
    } finally {
      System.setErr(original);
    }
    List<String> lines = bytes.toString(StandardCharsets.UTF_8).lines().toList();

    assertThat(lines)
        .singleElement()
        .asString()
        .startsWith(
            "[scope-tracer] WARN not recording: cannot record to " + AgentLog.path(target) + ": ")
        .contains("a\\nfile")
        .doesNotContain("\u001b");
  }
}
