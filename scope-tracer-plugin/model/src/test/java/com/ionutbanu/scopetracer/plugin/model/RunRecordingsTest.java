package com.ionutbanu.scopetracer.plugin.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.ionutbanu.scopetracer.plugin.model.RunRecordings.Found;
import com.ionutbanu.scopetracer.plugin.model.RunRecordings.Outcome;
import com.ionutbanu.scopetracer.plugin.model.RunRecordings.Show;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RunRecordingsTest {

  private static final Path DIR = Path.of("/tmp/scope-tracer-run-1");

  private static Path rec(long pid) {
    return DIR.resolve("scope-tracer-" + pid + ".jfr");
  }

  private static Found found(long pid, long size, long modified) {
    return new Found(rec(pid), size, modified);
  }

  @Test
  void oneRecordingIsAnalyzedAndDeletedAfterwards() {
    var selection = RunRecordings.select(List.of(found(100, 4096, 1)));

    assertThat(selection.analyze()).containsExactly(rec(100));
    assertThat(selection.nothingRecorded()).isFalse();
    assertThat(selection.keepDirectory()).isFalse();
    assertThat(selection.deleteAfterAnalysis()).isTrue();
  }

  /** A JVM killed before it wrote anything leaves a 0-byte file (measured, SIGKILL). */
  @Test
  void emptyFilesAreIgnored() {
    var selection = RunRecordings.select(List.of(found(100, 0, 1), found(101, 2048, 2)));

    assertThat(selection.analyze()).containsExactly(rec(101));
    assertThat(selection.recorded()).isEqualTo(1);
  }

  @Test
  void aDirectoryWithNothingOrOnlyEmptyFilesRecordedNothing() {
    assertThat(RunRecordings.select(List.of()).nothingRecorded()).isTrue();
    var empties = RunRecordings.select(List.of(found(1, 0, 1), found(2, 0, 2)));
    assertThat(empties.nothingRecorded()).isTrue();
    assertThat(empties.deleteAfterAnalysis()).isTrue();
    assertThat(RunRecordings.show(empties, List.of())).isEqualTo(new Show.NothingRecorded());
  }

  @Test
  void filesThatAreNotRecordingsAreIgnored() {
    var selection =
        RunRecordings.select(
            List.of(new Found(DIR.resolve("notes.txt"), 10, 1), found(7, 10, 2)));

    assertThat(selection.analyze()).containsExactly(rec(7));
  }

  @Test
  void recordingsAreAnalyzedOldestFirst() {
    var selection =
        RunRecordings.select(List.of(found(3, 10, 30), found(1, 10, 10), found(2, 10, 20)));

    assertThat(selection.analyze()).containsExactly(rec(1), rec(2), rec(3));
  }

  @Test
  void recordingsWithTheSameModificationTimeAreOrderedByName() {
    var selection = RunRecordings.select(List.of(found(22, 10, 5), found(11, 10, 5)));

    assertThat(selection.analyze()).containsExactly(rec(11), rec(22));
  }

  @Test
  void tenRecordingsAreAllOpenedAndDeleted() {
    var found = new ArrayList<Found>();
    for (int i = 0; i < 10; i++) {
      found.add(found(1000 + i, 10, i));
    }

    var selection = RunRecordings.select(found);

    assertThat(selection.analyze()).hasSize(10);
    assertThat(selection.keepDirectory()).isFalse();
    assertThat(selection.deleteAfterAnalysis()).isTrue();
  }

  @Test
  void moreThanTenRecordingsOpenTheFirstTenByModificationTimeAndKeepAll() {
    var found = new ArrayList<Found>();
    for (int i = 11; i >= 0; i--) {
      found.add(found(1000 + i, 10, i));
    }

    var selection = RunRecordings.select(found);

    assertThat(RunRecordings.MAX_OPENED).isEqualTo(10);
    assertThat(selection.analyze())
        .containsExactly(
            rec(1000), rec(1001), rec(1002), rec(1003), rec(1004), rec(1005), rec(1006), rec(1007),
            rec(1008), rec(1009));
    assertThat(selection.recorded()).isEqualTo(12);
    assertThat(selection.keepDirectory()).isTrue();
    assertThat(selection.deleteAfterAnalysis()).isFalse();
    assertThat(RunRecordings.keptNotice(selection, DIR))
        .isEqualTo(
            "The run left 12 recordings. The first 10 are opened; all of them are kept in "
                + DIR
                + ".");
  }

  @Test
  void oneRecordingIsShownInTheMainContentAsBefore() {
    var selection = RunRecordings.select(List.of(found(5, 10, 1)));

    assertThat(RunRecordings.show(selection, List.of(new Outcome.Parsed(rec(5), true))))
        .isEqualTo(new Show.Main(rec(5)));
  }

  /** As before #64: one recording without scopes replaces the views, which say it is empty. */
  @Test
  void oneRecordingWithoutScopesIsStillShownInTheMainContent() {
    var selection = RunRecordings.select(List.of(found(5, 10, 1)));

    assertThat(RunRecordings.show(selection, List.of(new Outcome.Parsed(rec(5), false))))
        .isEqualTo(new Show.Main(rec(5)));
  }

  /** JUnit fork mode: the parent JVM's recording holds no scopes and is not shown. */
  @Test
  void theParentJvmsRecordingWithoutScopesIsSkippedAndTheOneForkIsShownInTheMainContent() {
    var selection = RunRecordings.select(List.of(found(1, 10, 1), found(2, 10, 2)));

    var show =
        RunRecordings.show(
            selection,
            List.of(new Outcome.Parsed(rec(1), false), new Outcome.Parsed(rec(2), true)));

    assertThat(show).isEqualTo(new Show.Main(rec(2)));
  }

  @Test
  void severalRecordingsWithScopesAreEachShownInATabInOrder() {
    var selection =
        RunRecordings.select(List.of(found(1, 10, 1), found(2, 10, 2), found(3, 10, 3)));

    var show =
        RunRecordings.show(
            selection,
            List.of(
                new Outcome.Parsed(rec(1), false),
                new Outcome.Parsed(rec(2), true),
                new Outcome.Parsed(rec(3), true)));

    assertThat(show).isEqualTo(new Show.Tabs(List.of(rec(2), rec(3))));
  }

  @Test
  void severalRecordingsNoneWithScopesGiveOneNoScopesNotice() {
    var selection = RunRecordings.select(List.of(found(1, 10, 1), found(2, 10, 2)));

    var show =
        RunRecordings.show(
            selection,
            List.of(new Outcome.Parsed(rec(1), false), new Outcome.Parsed(rec(2), false)));

    assertThat(show).isEqualTo(new Show.NoScopes());
  }

  @Test
  void aFailedAnalysisDoesNotStopTheOthers() {
    var selection =
        RunRecordings.select(List.of(found(1, 10, 1), found(2, 10, 2), found(3, 10, 3)));

    var show =
        RunRecordings.show(
            selection,
            List.of(
                new Outcome.Failed(rec(1)),
                new Outcome.Parsed(rec(2), true),
                new Outcome.Parsed(rec(3), true)));

    assertThat(show).isEqualTo(new Show.Tabs(List.of(rec(2), rec(3))));
  }

  @Test
  void whenEveryAnalysisFailedThereIsNothingMoreToShow() {
    var selection = RunRecordings.select(List.of(found(1, 10, 1), found(2, 10, 2)));

    var show =
        RunRecordings.show(
            selection, List.of(new Outcome.Failed(rec(1)), new Outcome.Failed(rec(2))));

    assertThat(show).isEqualTo(new Show.Nothing());
    assertThat(
            RunRecordings.show(
                RunRecordings.select(List.of(found(1, 10, 1))),
                List.of(new Outcome.Failed(rec(1)))))
        .isEqualTo(new Show.Nothing());
  }

  @Test
  void thePidIsReadFromTheFileName() {
    assertThat(RunRecordings.pidOf(rec(48213))).isEqualTo(48213L);
    assertThat(RunRecordings.pidOf(Path.of("scope-tracer-7.jfr"))).isEqualTo(7L);
  }

  @Test
  void aFileNameWithoutAPidHasNone() {
    assertThat(RunRecordings.pidOf(DIR.resolve("scope-tracer-%p.jfr"))).isNull();
    assertThat(RunRecordings.pidOf(DIR.resolve("hotspot-pid-1-id-1.jfr"))).isNull();
    assertThat(RunRecordings.pidOf(DIR.resolve("scope-tracer-.jfr"))).isNull();
    assertThat(RunRecordings.pidOf(Path.of("/"))).isNull();
  }

  @Test
  void aTabIsNamedAfterTheConfigurationAndThePid() {
    assertThat(RunRecordings.tabName("All in com.example", rec(4242)))
        .isEqualTo("All in com.example (pid 4242)");
  }

  @Test
  void aFileWithoutAPidStillGetsATabName() {
    assertThat(RunRecordings.tabName("MyTest", DIR.resolve("other.jfr")))
        .isEqualTo("MyTest (other.jfr)");
  }
}
