package com.ionutbanu.scopetracer.demos;

import static com.ionutbanu.scopetracer.demos.TestModels.cancelled;
import static com.ionutbanu.scopetracer.demos.TestModels.failure;
import static com.ionutbanu.scopetracer.demos.TestModels.model;
import static com.ionutbanu.scopetracer.demos.TestModels.ok;
import static com.ionutbanu.scopetracer.demos.TestModels.task;
import static org.assertj.core.api.Assertions.assertThat;

import com.ionutbanu.scopetracer.analyzer.HtmlRenderer;
import com.ionutbanu.scopetracer.analyzer.model.ScopeRecord;
import com.ionutbanu.scopetracer.analyzer.model.TaskRecord;
import com.ionutbanu.scopetracer.analyzer.model.TraceModel;
import org.junit.jupiter.api.Test;

/** What {@code GalleryMain --check} ignores and what it does not. */
class ReportSkeletonTest {

  private static String render(TraceModel model) {
    return HtmlRenderer.render(model);
  }

  private static boolean sameSkeleton(String a, String b) {
    return ReportSkeleton.firstDifference(a, b) == null;
  }

  @Test
  void aChangedDurationAndTimestampAreIgnored() {
    var fast = render(TestModels.simple(0, 10, 100));
    var slow = render(TestModels.simple(86_400_000, 10, 640));

    assertThat(fast).isNotEqualTo(slow);
    assertThat(sameSkeleton(fast, slow)).isTrue();
  }

  @Test
  void aChangedTickCountIsIgnored() {
    // 100 ms and 40 s scopes draw a different number of axis ticks.
    var short1 = render(TestModels.simple(0, 10, 100));
    var long1 = render(TestModels.simple(0, 10, 40_000));

    assertThat(long1.length()).isNotEqualTo(short1.length());
    assertThat(sameSkeleton(short1, long1)).isTrue();
  }

  @Test
  void aChangedThreadIdAndNameAreIgnored() {
    var a = render(TestModels.simple(0, 10, 100));
    var b = render(TestModels.simple(0, 4711, 100));

    assertThat(a).isNotEqualTo(b);
    assertThat(sameSkeleton(a, b)).isTrue();
  }

  @Test
  void aJdkFrameInAStackTraceIsIgnored() {
    var withFrames =
        stackTraceReport(
            "java.lang.IllegalStateException: boom\n\tat app.Main.run(Main.java:12)\n"
                + "\tat java.base/java.lang.VirtualThread.run(VirtualThread.java:470)");
    var otherJdk =
        stackTraceReport(
            "java.lang.IllegalStateException: boom\n\tat app.Main.run(Main.java:12)\n"
                + "\tat java.base/java.lang.VirtualThread.run(VirtualThread.java:512)\n"
                + "\tat java.base/jdk.internal.misc.Foo.bar(Foo.java:9)");

    assertThat(withFrames).isNotEqualTo(otherJdk);
    assertThat(sameSkeleton(withFrames, otherJdk)).isTrue();
  }

  private static String stackTraceReport(String stackTrace) {
    var failed =
        new com.ionutbanu.scopetracer.analyzer.model.TaskOutcome.Failed(
            "java.lang.IllegalStateException", "boom", stackTrace);
    return render(model(TestModels.scope(1, "s", 0, 50, null, task(1, "a", 1, 0, 40, failed))));
  }

  @Test
  void whichTaskFinishedLastIsIgnored() {
    // Two tasks that end within a millisecond of each other: the amber note moves.
    var aLast = twoSucceeded(40, 41);
    var bLast = twoSucceeded(41, 40);

    assertThat(aLast).isNotEqualTo(bLast);
    assertThat(sameSkeleton(aLast, bLast)).isTrue();
  }

  private static String twoSucceeded(long aEnd, long bEnd) {
    return render(
        model(
            TestModels.scope(
                1,
                "s",
                0,
                50,
                null,
                task(1, "a", 1, 0, aEnd, ok()),
                task(2, "b", 2, 0, bEnd, ok()))));
  }

  @Test
  void aChangedTaskNameIsDetected() {
    var original = render(TestModels.simple(0, 10, 100));
    var renamed = original.replace("<td>a</td>", "<td>renamed</td>");

    assertThat(sameSkeleton(original, renamed)).isFalse();
    assertThat(ReportSkeleton.firstDifference(original, renamed)).contains("renamed");
  }

  @Test
  void aChangedOutcomeClassIsDetected() {
    var original = render(TestModels.simple(0, 10, 100));
    var changed = original.replaceFirst("outcome-success\">success", "outcome-failed\">success");

    assertThat(sameSkeleton(original, changed)).isFalse();
  }

  @Test
  void aDeletedRowIsDetected() {
    var original = render(TestModels.simple(0, 10, 100));
    int row = original.indexOf("<tr><td>2</td>");
    var withoutRow =
        original.substring(0, row) + original.substring(original.indexOf("</tr>", row) + 6);

    assertThat(sameSkeleton(original, withoutRow)).isFalse();
  }

  @Test
  void aChangedCssClassIsDetected() {
    var original = render(TestModels.simple(0, 10, 100));
    var changed =
        original.replace("critical-path-note", "critical-note").replace("scope-meta", "scope-info");

    assertThat(sameSkeleton(original, changed)).isFalse();
  }

  @Test
  void siblingChildScopesAreComparedWhateverTheirOrder() {
    var parentRef = new ScopeRecord.ParentRef(1, "root", 1);
    var parentRef2 = new ScopeRecord.ParentRef(1, "root", 2);
    TaskRecord first = task(1, "left", 1, 0, 50, ok());
    TaskRecord second = task(2, "right", 2, 0, 50, ok());
    var ab =
        model(
            TestModels.scope(1, "root", 0, 60, null, first, second),
            TestModels.scope(2, "child-a", 1, 40, parentRef, task(1, "x", 3, 1, 30, ok())),
            TestModels.scope(3, "child-b", 2, 40, parentRef2, task(1, "y", 4, 2, 30, ok())));
    var ba =
        model(
            TestModels.scope(1, "root", 0, 60, null, first, second),
            TestModels.scope(3, "child-b", 1, 40, parentRef2, task(1, "y", 4, 1, 30, ok())),
            TestModels.scope(2, "child-a", 2, 40, parentRef, task(1, "x", 3, 2, 30, ok())));

    assertThat(render(ab)).isNotEqualTo(render(ba));
    assertThat(sameSkeleton(render(ab), render(ba))).isTrue();
  }

  @Test
  void aCancelledTaskThatBecomesASuccessIsDetected() {
    var a =
        render(model(TestModels.scope(1, "s", 0, 50, null, task(1, "a", 1, 0, 40, cancelled()))));
    var b = render(model(TestModels.scope(1, "s", 0, 50, null, task(1, "a", 1, 0, 40, ok()))));

    assertThat(sameSkeleton(a, b)).isFalse();
  }

  @Test
  void aFailureWithAnotherExceptionIsDetected() {
    var a =
        render(
            model(TestModels.scope(1, "s", 0, 50, null, task(1, "a", 1, 0, 40, failure("boom")))));
    var b =
        render(
            model(TestModels.scope(1, "s", 0, 50, null, task(1, "a", 1, 0, 40, failure("bang")))));

    assertThat(sameSkeleton(a, b)).isFalse();
  }
}
