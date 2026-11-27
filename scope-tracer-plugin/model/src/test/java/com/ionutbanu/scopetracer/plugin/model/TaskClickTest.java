package com.ionutbanu.scopetracer.plugin.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * What the embedded Timeline sends back when a task bar or row is clicked (#190): {@code
 * "<scopeId>:<taskId>"}, two non-negative {@code long}s, and nothing else.
 */
class TaskClickTest {

  @Test
  void aValidPairIsParsed() {
    assertThat(TaskClick.parse("12:345")).contains(new TaskClick(12, 345));
    assertThat(TaskClick.parse("0:0")).contains(new TaskClick(0, 0));
  }

  @Test
  void aPairAtLongMaxValueIsParsed() {
    var max = Long.MAX_VALUE + ":" + Long.MAX_VALUE;

    assertThat(TaskClick.parse(max)).contains(new TaskClick(Long.MAX_VALUE, Long.MAX_VALUE));
  }

  @Test
  void nullIsRefused() {
    assertThat(TaskClick.parse(null)).isEmpty();
  }

  @Test
  void anythingButTwoNonNegativeLongsIsRefused() {
    for (var message :
        new String[] {
          "",
          "12",
          ":",
          "12:",
          ":34",
          "1:2:3",
          "-1:2",
          "1:-2",
          "+1:2",
          "a:2",
          "1:b",
          "1.0:2",
          "0x1:2",
          "1:9223372036854775808",
          "9223372036854775808:1",
          "99999999999999999999:1",
          " 1:2",
          "1:2 ",
          "1 :2",
          "1: 2",
          "1:2\n",
          "\t1:2",
          "1:2x",
          "1:2;alert(1)",
          "1:2/../../etc/passwd",
          "file:///etc/passwd",
          "١:٢" // Arabic-Indic digits, which Long.parseLong would accept
        }) {
      assertThat(TaskClick.parse(message)).as("%s", message).isEmpty();
    }
  }

  @Test
  void aVeryLongStringIsRefused() {
    var digits = new char[100_000];
    Arrays.fill(digits, '1');
    var longString = "1:" + new String(digits);

    assertThat(TaskClick.parse(longString)).isEmpty();
    assertThat(TaskClick.parse("1:2" + " ".repeat(100_000))).isEmpty();
  }

  @Test
  void theClickScriptCallsTheBridgeWithTheElementsOwnIdsOnly() {
    var script = TaskClick.script("window.bridge(msg);");

    assertThat(script)
        .contains("window.bridge(msg);")
        .contains("data-scope-id")
        .contains("data-task-id")
        .doesNotContain("eval(")
        .doesNotContain("location")
        .doesNotContain("innerHTML")
        .doesNotContain("XMLHttpRequest")
        .doesNotContain("fetch(");
    assertThat(script.indexOf("window.bridge(msg);"))
        .isEqualTo(script.lastIndexOf("window.bridge(msg);"));
  }
}
