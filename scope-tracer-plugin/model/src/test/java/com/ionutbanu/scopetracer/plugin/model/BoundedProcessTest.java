package com.ionutbanu.scopetracer.plugin.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.ionutbanu.scopetracer.plugin.model.BoundedProcess.Cancelled;
import com.ionutbanu.scopetracer.plugin.model.BoundedProcess.Capture;
import com.ionutbanu.scopetracer.plugin.model.BoundedProcess.Exited;
import com.ionutbanu.scopetracer.plugin.model.BoundedProcess.TimedOut;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
}
