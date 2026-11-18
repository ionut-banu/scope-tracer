package com.ionutbanu.scopetracer.demos;

import static com.ionutbanu.scopetracer.demos.Shape.cancelled;
import static com.ionutbanu.scopetracer.demos.Shape.failed;
import static com.ionutbanu.scopetracer.demos.Shape.nested;
import static com.ionutbanu.scopetracer.demos.Shape.scope;
import static com.ionutbanu.scopetracer.demos.Shape.succeeded;
import static com.ionutbanu.scopetracer.demos.TestModels.cancelled;
import static com.ionutbanu.scopetracer.demos.TestModels.failure;
import static com.ionutbanu.scopetracer.demos.TestModels.model;
import static com.ionutbanu.scopetracer.demos.TestModels.ok;
import static com.ionutbanu.scopetracer.demos.TestModels.task;
import static org.assertj.core.api.Assertions.assertThat;

import com.ionutbanu.scopetracer.analyzer.model.ScopeRecord;
import org.junit.jupiter.api.Test;

/** The gate: a recording is accepted only when it shows the shape its scenario declares. */
class ShapeTest {

  private static final Shape SHAPE =
      Shape.of(
          scope("s", succeeded("a"), failed("b", "IllegalStateException: boom"), cancelled("c")),
          nested("child", "s", "a", succeeded("x")));

  private static com.ionutbanu.scopetracer.analyzer.model.TraceModel matching() {
    return model(
        TestModels.scope(
            1,
            "s",
            0,
            210,
            null,
            task(1, "a", 1, 0, 50, ok()),
            task(2, "b", 2, 0, 200, failure("boom")),
            task(3, "c", 3, 0, 201, cancelled())),
        TestModels.scope(
            2, "child", 5, 40, new ScopeRecord.ParentRef(1, "s", 1), task(1, "x", 4, 5, 30, ok())));
  }

  @Test
  void aRecordingThatShowsTheShapeHasNoProblems() {
    assertThat(SHAPE.problems(matching())).isEmpty();
  }

  @Test
  void aWrongOutcomeIsReportedWithTheTaskAndBothOutcomes() {
    var model =
        model(
            TestModels.scope(
                1,
                "s",
                0,
                210,
                null,
                task(1, "a", 1, 0, 50, ok()),
                task(2, "b", 2, 0, 200, failure("boom")),
                task(3, "c", 3, 0, 201, ok())),
            matching().scopes().get(1));

    assertThat(SHAPE.problems(model))
        .containsExactly("scope s: task c is success, expected cancelled");
  }

  @Test
  void aForkWithNoCompletionIsRejected() {
    var model =
        model(
            TestModels.scope(
                1,
                "s",
                0,
                210,
                null,
                task(1, "a", 1, 0, 50, ok()),
                task(2, "b", 2, 0, 200, failure("boom")),
                task(3, "c", 3, 0, 0, null)),
            matching().scopes().get(1));

    assertThat(SHAPE.problems(model))
        .contains("task c of scope s has no completion event")
        .contains("scope s: task c is incomplete, expected cancelled");
  }

  @Test
  void aScopeWithNoCloseIsRejected() {
    var model =
        model(
            TestModels.scope(
                1,
                "s",
                0,
                -1,
                null,
                task(1, "a", 1, 0, 50, ok()),
                task(2, "b", 2, 0, 200, failure("boom")),
                task(3, "c", 3, 0, 201, cancelled())),
            matching().scopes().get(1));

    assertThat(SHAPE.problems(model)).containsExactly("scope s has no close event");
  }

  @Test
  void aWrongParentIsReported() {
    var model =
        model(
            matching().scopes().get(0),
            TestModels.scope(
                2,
                "child",
                5,
                40,
                new ScopeRecord.ParentRef(1, "s", 2),
                task(1, "x", 4, 5, 30, ok())));

    assertThat(SHAPE.problems(model)).containsExactly("scope child: opened by task b, expected a");
  }

  @Test
  void aMissingAndAnUnexpectedScopeAreBothReported() {
    var model = model(matching().scopes().get(0), TestModels.scope(2, "other", 5, 40, null));

    assertThat(SHAPE.problems(model))
        .containsExactly("scope child is missing", "unexpected scope other");
  }

  @Test
  void aFailureWithAnotherMessageIsReported() {
    var model =
        model(
            TestModels.scope(
                1,
                "s",
                0,
                210,
                null,
                task(1, "a", 1, 0, 50, ok()),
                task(2, "b", 2, 0, 200, failure("something else")),
                task(3, "c", 3, 0, 201, cancelled())),
            matching().scopes().get(1));

    assertThat(SHAPE.problems(model)).hasSize(1).first().asString().contains("something else");
  }

  @Test
  void timingChecksRunOnlyWhenTheStructureIsRight() {
    var seen = new int[1];
    var shape =
        SHAPE.with(
            m -> {
              seen[0]++;
              return java.util.List.of("too slow");
            });

    assertThat(shape.problems(matching())).containsExactly("too slow");
    assertThat(seen[0]).isEqualTo(1);

    seen[0] = 0;
    assertThat(shape.problems(model(matching().scopes().get(0)))).doesNotContain("too slow");
    assertThat(seen[0]).isZero();
  }

  @Test
  void theCatalogsTimingChecksSeeTheGapsTheyAreWrittenFor() {
    var shape = GalleryCatalog.ALL.get(0).shape(); // fail-fast
    var good =
        model(
            TestModels.scope(
                1,
                "fail-fast",
                0,
                210,
                null,
                task(1, "profile", 1, 0, 50, ok()),
                task(2, "inventory-check", 2, 0, 200, failure("IllegalStateException")),
                task(3, "recommendations", 3, 0, 220, cancelled()),
                task(4, "reviews", 4, 0, 230, cancelled())));
    var slowToCancel =
        model(
            TestModels.scope(
                1,
                "fail-fast",
                0,
                310,
                null,
                task(1, "profile", 1, 0, 50, ok()),
                task(2, "inventory-check", 2, 0, 200, failure("IllegalStateException")),
                task(3, "recommendations", 3, 0, 220, cancelled()),
                task(4, "reviews", 4, 0, 300, cancelled())));

    assertThat(shapeWithoutDetail(shape).problems(good)).isEmpty();
    assertThat(shapeWithoutDetail(shape).problems(slowToCancel))
        .containsExactly("reviews ended 100 ms from inventory-check, expected at most 50");
  }

  /** The catalog's failure detail names the real message; the hand-built model has its own. */
  private static Shape shapeWithoutDetail(Shape shape) {
    var scopes =
        shape.scopes().stream()
            .map(
                s ->
                    new Shape.ExpectedScope(
                        s.name(),
                        s.parent(),
                        s.parentTask(),
                        s.tasks().stream()
                            .map(t -> new Shape.ExpectedTask(t.name(), t.outcome(), null))
                            .toList()))
            .toList();
    return new Shape(scopes, shape.timing());
  }
}
