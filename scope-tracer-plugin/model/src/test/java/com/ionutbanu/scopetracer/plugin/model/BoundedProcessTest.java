package com.ionutbanu.scopetracer.plugin.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.ionutbanu.scopetracer.plugin.model.BoundedProcess.Cancelled;
import com.ionutbanu.scopetracer.plugin.model.BoundedProcess.Capture;
import com.ionutbanu.scopetracer.plugin.model.BoundedProcess.Exited;
import com.ionutbanu.scopetracer.plugin.model.BoundedProcess.TimedOut;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BoundedProcessTest {

  private static final int ONE_MB = 1024 * 1024;
  private static final Duration GENEROUS = Duration.ofSeconds(60);

  @TempDir Path temp;

  @AfterEach
  void noChildLeftRunning() {
    assertThat(Children.aliveChildren()).isEmpty();
  }

  @Test
  void aChildWritingAMegabyteToEachStreamCompletesWithoutDeadlock() {
    var result =
        assertTimeoutPreemptively(
            Duration.ofSeconds(30),
            () ->
                BoundedProcess.run(
                    Children.command("Chatty", String.valueOf(ONE_MB)),
                    Capture.STDERR,
                    GENEROUS,
                    () -> false,
                    temp));

    assertThat(result).isInstanceOfSatisfying(Exited.class, e -> assertThat(e.exitCode()).isZero());
  }

  @Test
  void aChildThatNeverExitsIsKilledAndReapedWhenTheTimeoutPasses() {
    var result =
        assertTimeoutPreemptively(
            Duration.ofSeconds(5),
            () ->
                BoundedProcess.run(
                    Children.command("NeverExits"),
                    Capture.STDERR,
                    Duration.ofSeconds(1),
                    () -> false,
                    temp));

    assertThat(result).isInstanceOf(TimedOut.class);
    assertThat(result.process()).hasValueSatisfying(p -> assertThat(p.isAlive()).isFalse());
  }

  @Test
  void cancellingWhileTheChildRunsReturnsCancelledWithinTwoSecondsWithTheChildDead() {
    var flippedAt = new AtomicLong();
    var cancelled = new AtomicBoolean();
    // Flip once the child is running, from the runner's own polling: no sleeping thread needed.
    var started = System.nanoTime();
    var result =
        assertTimeoutPreemptively(
            Duration.ofSeconds(15),
            () ->
                BoundedProcess.run(
                    Children.command("NeverExits"),
                    Capture.STDERR,
                    GENEROUS,
                    () -> {
                      if (!cancelled.get()
                          && System.nanoTime() - started > Duration.ofSeconds(2).toNanos()) {
                        flippedAt.set(System.nanoTime());
                        cancelled.set(true);
                      }
                      return cancelled.get();
                    },
                    temp));
    var returnedAt = System.nanoTime();

    assertThat(result).isInstanceOf(Cancelled.class);
    assertThat(Duration.ofNanos(returnedAt - flippedAt.get())).isLessThan(Duration.ofSeconds(2));
    assertThat(result.process()).hasValueSatisfying(p -> assertThat(p.isAlive()).isFalse());
  }

  @Test
  void cancellationAlreadyRequestedBeforeTheStartLeavesNoChildRunning() {
    var result =
        assertTimeoutPreemptively(
            Duration.ofSeconds(5),
            () ->
                BoundedProcess.run(
                    Children.command("NeverExits"), Capture.STDERR, GENEROUS, () -> true, temp));

    assertThat(result).isInstanceOf(Cancelled.class);
    assertThat(result.process().map(ProcessHandle::isAlive)).isNotEqualTo(Optional.of(true));
  }

  @Test
  void interruptingTheCallerKillsTheChildAndKeepsTheInterruptFlag() {
    var interruptedAfterReturn = new AtomicBoolean();
    var result =
        assertTimeoutPreemptively(
            Duration.ofSeconds(15),
            () -> {
              var started = System.nanoTime();
              var r =
                  BoundedProcess.run(
                      Children.command("NeverExits"),
                      Capture.STDERR,
                      GENEROUS,
                      () -> {
                        // The check runs on the calling thread: interrupt it once, after the start.
                        if (System.nanoTime() - started > Duration.ofSeconds(2).toNanos()
                            && !interruptedAfterReturn.get()) {
                          interruptedAfterReturn.set(true);
                          Thread.currentThread().interrupt();
                        }
                        return false;
                      },
                      temp);
              var stillInterrupted = Thread.interrupted();
              assertThat(stillInterrupted).as("interrupt flag restored").isTrue();
              return r;
            });

    assertThat(result).isInstanceOf(Cancelled.class);
    assertThat(result.process()).hasValueSatisfying(p -> assertThat(p.isAlive()).isFalse());
  }

  @Test
  void aNonZeroExitCarriesTheExitCodeAndTheStderrLine() {
    var result =
        assertTimeoutPreemptively(
            Duration.ofSeconds(30),
            () ->
                BoundedProcess.run(
                    Children.command("Fails", "3", "error: not a recording", "0"),
                    Capture.STDERR,
                    GENEROUS,
                    () -> false,
                    temp));

    assertThat(result)
        .isInstanceOfSatisfying(
            Exited.class,
            e -> {
              assertThat(e.exitCode()).isEqualTo(3);
              assertThat(e.output()).contains("error: not a recording");
            });
  }

  @Test
  void theOutputKeptForTheMessageIsBoundedWhenTheChildWritesAMegabyteOfStderr() {
    var result =
        assertTimeoutPreemptively(
            Duration.ofSeconds(30),
            () ->
                BoundedProcess.run(
                    Children.command("Fails", "4", "error: first line", String.valueOf(ONE_MB)),
                    Capture.STDERR,
                    GENEROUS,
                    () -> false,
                    temp));

    assertThat(result)
        .isInstanceOfSatisfying(
            Exited.class,
            e -> {
              assertThat(e.exitCode()).isEqualTo(4);
              assertThat(e.output()).startsWith("error: first line");
              assertThat(e.output().length()).isLessThan(BoundedProcess.OUTPUT_LIMIT_BYTES + 100);
            });
  }

  @Test
  void theCaptureFileIsDeletedAfterTheRun() throws Exception {
    assertTimeoutPreemptively(
        Duration.ofSeconds(30),
        () ->
            BoundedProcess.run(
                Children.command("Fails", "1", "boom", "0"),
                Capture.STDERR,
                GENEROUS,
                () -> false,
                temp));

    try (var files = Files.list(temp)) {
      assertThat(files).isEmpty();
    }
  }

  private static void failingDelete(Path file) throws IOException {
    throw new java.nio.file.FileSystemException(file.toString(), null, "cannot delete");
  }

  @Test
  void aFailedDeleteDoesNotReplaceAnExit() {
    var result =
        assertTimeoutPreemptively(
            Duration.ofSeconds(30),
            () ->
                BoundedProcess.run(
                    Children.command("Fails", "3", "error: x", "0"),
                    Capture.STDERR,
                    GENEROUS,
                    () -> false,
                    temp,
                    BoundedProcessTest::failingDelete));

    assertThat(result)
        .isInstanceOfSatisfying(
            Exited.class,
            e -> {
              assertThat(e.exitCode()).isEqualTo(3);
              assertThat(e.output()).contains("error: x");
            });
  }

  @Test
  void aFailedDeleteDoesNotReplaceATimeout() {
    var result =
        assertTimeoutPreemptively(
            Duration.ofSeconds(10),
            () ->
                BoundedProcess.run(
                    Children.command("NeverExits"),
                    Capture.STDERR,
                    Duration.ofSeconds(1),
                    () -> false,
                    temp,
                    BoundedProcessTest::failingDelete));

    assertThat(result).isInstanceOf(TimedOut.class);
    assertThat(result.process()).hasValueSatisfying(p -> assertThat(p.isAlive()).isFalse());
  }

  @Test
  void aFailedDeleteDoesNotReplaceACancel() {
    var polls = new AtomicInteger();
    var result =
        assertTimeoutPreemptively(
            Duration.ofSeconds(10),
            () ->
                BoundedProcess.run(
                    Children.command("NeverExits"),
                    Capture.STDERR,
                    GENEROUS,
                    () -> polls.incrementAndGet() > 2,
                    temp,
                    BoundedProcessTest::failingDelete));

    assertThat(result).isInstanceOf(Cancelled.class);
    assertThat(result.process()).hasValueSatisfying(p -> assertThat(p.isAlive()).isFalse());
  }

  @Test
  void aFailedDeleteDoesNotReplaceAnExceptionAlreadyPropagatingAndIsSuppressedOnIt() {
    var polls = new AtomicInteger();

    assertTimeoutPreemptively(
        Duration.ofSeconds(10),
        () ->
            assertThatThrownBy(
                    () ->
                        BoundedProcess.run(
                            Children.command("NeverExits"),
                            Capture.STDERR,
                            GENEROUS,
                            () -> {
                              if (polls.incrementAndGet() > 2) {
                                throw new IllegalStateException("check broke");
                              }
                              return false;
                            },
                            temp,
                            BoundedProcessTest::failingDelete))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("check broke")
                .satisfies(
                    e ->
                        assertThat(e.getSuppressed())
                            .singleElement()
                            .isInstanceOf(java.nio.file.FileSystemException.class)));
  }

  private static final int LIMIT = BoundedProcess.OUTPUT_LIMIT_BYTES;
  private static final String NOTE = "\n[... output truncated after 8 KiB]";

  private Exited stderrOf(int pad, String hex, int tail) {
    var result =
        assertTimeoutPreemptively(
            Duration.ofSeconds(30),
            () ->
                BoundedProcess.run(
                    Children.command("Utf8Edge", String.valueOf(pad), hex, String.valueOf(tail)),
                    Capture.STDERR,
                    GENEROUS,
                    () -> false,
                    temp));
    return (Exited) result;
  }

  @ParameterizedTest
  @CsvSource({"c3a9,2", "e282ac,3", "f09f9880,4"})
  void aCharacterCutByTheLimitIsDroppedNotKeptAsAReplacementCharacter(String hex, int length) {
    var out = stderrOf(LIMIT - (length - 1), hex, 100).output();

    assertThat(out).isEqualTo("x".repeat(LIMIT - (length - 1)) + NOTE);
    assertThat(out).doesNotContain("\uFFFD");
  }

  @ParameterizedTest
  @CsvSource({"c3a9,2", "e282ac,3", "f09f9880,4"})
  void aCharacterEndingExactlyAtTheLimitIsKept(String hex, int length) {
    var kept = new String(HexFormat.of().parseHex(hex), java.nio.charset.StandardCharsets.UTF_8);
    var out = stderrOf(LIMIT - length, hex, 100).output();

    assertThat(out).isEqualTo("x".repeat(LIMIT - length) + kept + NOTE);
  }

  @Test
  void exactlyTheLimitEndingOnACharacterBoundaryIsReturnedWholeWithNoNote() {
    var out = stderrOf(LIMIT - 3, "e282ac", 0).output();

    assertThat(out).isEqualTo("x".repeat(LIMIT - 3) + "\u20ac");
  }

  @Test
  void invalidUtf8InsideTheKeptHeadStillDecodesToReplacementCharacters() {
    var out = stderrOf(10, "ff", 10).output();

    assertThat(out).isEqualTo("x".repeat(10) + "\uFFFD" + "z".repeat(10));
  }
}
