package com.ionutbanu.scopetracer.demos;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** The order demo draws the same numbers every run, which is what makes its report reproducible. */
class OrderDrawsTest {

  private static List<Integer> first(String order, String step) {
    var draws = OrderProcessingDemo.draws(order, step);
    return IntStream.range(0, 8).map(i -> draws.nextInt(1000)).boxed().toList();
  }

  @Test
  void theSameOrderAndStepGiveTheSameDraws() {
    assertThat(first("ORD-002", "fraud")).isEqualTo(first("ORD-002", "fraud"));
  }

  @Test
  void anotherStepOrAnotherOrderGivesOtherDraws() {
    assertThat(first("ORD-002", "fraud")).isNotEqualTo(first("ORD-002", "auth"));
    assertThat(first("ORD-002", "fraud")).isNotEqualTo(first("ORD-004", "fraud"));
  }

  @Test
  void exactlyTheSecondOrderIsDeclinedByTheFraudCheck() {
    // Only ORD-002 (849.99) and ORD-004 (649.99) reach the 500 limit and can be declined.
    var declined =
        List.of("ORD-002", "ORD-004").stream()
            .filter(
                id -> {
                  var draws = OrderProcessingDemo.draws(id, "fraud");
                  draws.nextInt(120); // the check's sleep
                  return draws.nextInt(10) < 3;
                })
            .toList();

    assertThat(declined).containsExactly("ORD-002");
  }
}
