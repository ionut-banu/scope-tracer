package com.ionutbanu.scopetracer.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The listener that names a class ByteBuddy could not transform (issue #121, criterion 10): only
 * the two scope classes, at most once each, always one line, and it never throws.
 */
class TransformFailureListenerTest {

  private static final String LINE = "[scope-tracer] WARN cannot instrument ";
  private static final String INCOMPLETE = ", the trace will be incomplete: ";

  /** Runs {@code body} with stderr captured; the lines printed. */
  private static List<String> stderrOf(Runnable body) {
    var bytes = new ByteArrayOutputStream();
    PrintStream original = System.err;
    System.setErr(new PrintStream((OutputStream) bytes, true, StandardCharsets.UTF_8));
    try {
      assertThatCode(body::run).doesNotThrowAnyException();
    } finally {
      System.setErr(original);
    }
    return bytes.toString(StandardCharsets.UTF_8).lines().toList();
  }

  private static void error(TransformFailureListener listener, String type, Throwable t) {
    listener.onError(type, null, null, false, t);
  }

  @ParameterizedTest
  @ValueSource(strings = {TransformFailureListener.SCOPE, TransformFailureListener.SCOPE_IMPL})
  void namesTheClassAndByteBuddysReasonOnOneLine(String type) {
    var listener = new TransformFailureListener();
    List<String> lines =
        stderrOf(
            () -> error(listener, type, new IllegalStateException("does not define an index 9")));

    assertThat(lines)
        .containsExactly(
            LINE + type + INCOMPLETE + "IllegalStateException: does not define an index 9");
  }

  @Test
  void eachClassAtMostOnce() {
    var listener = new TransformFailureListener();
    List<String> lines =
        stderrOf(
            () -> {
              for (int i = 0; i < 3; i++) {
                error(
                    listener, TransformFailureListener.SCOPE_IMPL, new IllegalStateException("a"));
                error(listener, TransformFailureListener.SCOPE, new IllegalStateException("b"));
              }
            });

    assertThat(lines)
        .containsExactly(
            LINE + TransformFailureListener.SCOPE_IMPL + INCOMPLETE + "IllegalStateException: a",
            LINE + TransformFailureListener.SCOPE + INCOMPLETE + "IllegalStateException: b");
  }

  @Test
  void otherClassesAndANullNamePrintNothing() {
    var listener = new TransformFailureListener();
    List<String> lines =
        stderrOf(
            () -> {
              error(
                  listener,
                  "java.util.concurrent.ConcurrentHashMap",
                  new IllegalStateException("x"));
              error(listener, null, new IllegalStateException("x"));
              error(listener, "java.util.concurrent.StructuredTaskScope$Joiner", new Error("x"));
            });

    assertThat(lines).isEmpty();
  }

  @Test
  void aMessageThatThrowsStillGivesOneLine() {
    var listener = new TransformFailureListener();
    var hostile =
        new IllegalStateException() {
          @Override
          public String getMessage() {
            throw new UnsupportedOperationException("no message");
          }
        };
    List<String> lines = stderrOf(() -> error(listener, TransformFailureListener.SCOPE, hostile));

    assertThat(lines)
        .singleElement()
        .asString()
        .startsWith(LINE + TransformFailureListener.SCOPE + INCOMPLETE);
  }

  @Test
  void anAnonymousThrowableWhoseMessageThrowsIsNamedByItsBinaryName() {
    var hostile =
        new IllegalStateException() {
          @Override
          public String getMessage() {
            throw new UnsupportedOperationException("no message");
          }
        };
    List<String> lines =
        stderrOf(
            () -> error(new TransformFailureListener(), TransformFailureListener.SCOPE, hostile));

    assertThat(lines)
        .containsExactly(
            LINE + TransformFailureListener.SCOPE + INCOMPLETE + hostile.getClass().getName());
  }

  @Test
  void theCauseChainIsPartOfTheLine() {
    var t = new IllegalStateException("outer", new UnsupportedOperationException("inner"));
    List<String> lines =
        stderrOf(
            () -> error(new TransformFailureListener(), TransformFailureListener.SCOPE_IMPL, t));

    assertThat(lines)
        .containsExactly(
            LINE
                + TransformFailureListener.SCOPE_IMPL
                + INCOMPLETE
                + "IllegalStateException: outer; caused by UnsupportedOperationException: inner");
  }

  @Test
  void aNullMessageOrANullThrowableGivesOneLine() {
    List<String> nullMessage =
        stderrOf(
            () ->
                error(
                    new TransformFailureListener(),
                    TransformFailureListener.SCOPE,
                    new IllegalStateException()));
    List<String> nullThrowable =
        stderrOf(() -> error(new TransformFailureListener(), TransformFailureListener.SCOPE, null));

    assertThat(nullMessage)
        .containsExactly(
            LINE + TransformFailureListener.SCOPE + INCOMPLETE + "IllegalStateException");
    assertThat(nullThrowable).singleElement().asString().startsWith(LINE);
  }

  @Test
  void lineBreaksInTheMessageStayOnOneLine() {
    var listener = new TransformFailureListener();
    List<String> lines =
        stderrOf(
            () ->
                error(
                    listener,
                    TransformFailureListener.SCOPE_IMPL,
                    new IllegalStateException("first\nsecond\rthird\r\nfourth")));

    assertThat(lines)
        .containsExactly(
            LINE
                + TransformFailureListener.SCOPE_IMPL
                + INCOMPLETE
                + "IllegalStateException: first\\nsecond\\rthird\\r\\nfourth");
  }

  @Test
  void eightThreadsReportingTheSameClassPrintOneLine() throws Exception {
    var listener = new TransformFailureListener();
    var start = new CountDownLatch(1);
    List<String> lines =
        stderrOf(
            () -> {
              var threads = new ArrayList<Thread>();
              for (int i = 0; i < 8; i++) {
                int n = i;
                threads.add(
                    Thread.ofPlatform()
                        .start(
                            () -> {
                              try {
                                start.await(10, TimeUnit.SECONDS);
                              } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                              }
                              error(
                                  listener,
                                  TransformFailureListener.SCOPE_IMPL,
                                  new IllegalStateException("thread " + n));
                            }));
              }
              start.countDown();
              for (Thread t : threads) {
                try {
                  t.join(10_000);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }
            });

    assertThat(lines)
        .singleElement()
        .asString()
        .startsWith(
            LINE
                + TransformFailureListener.SCOPE_IMPL
                + INCOMPLETE
                + "IllegalStateException: thread ");
  }
}
