package com.ionutbanu.scopetracer.agent.util;

import static org.assertj.core.api.Assertions.assertThat;

import net.bytebuddy.ByteBuddy;
import org.junit.jupiter.api.Test;

class StackFilterTest {

  private static final String SELF = ScopeNameDeriver.class.getName();

  @Test
  void userFrameIsKept() {
    assertThat(StackFilter.isUserFrame("com.acme.OrderService", SELF)).isTrue();
  }

  @Test
  void agentPackageIsKeptBecauseUserCodeMayLiveThere() {
    assertThat(StackFilter.isUserFrame("com.ionutbanu.scopetracer.agent.AgentTestSubject", SELF))
        .isTrue();
  }

  @Test
  void derivingClassIsSkipped() {
    assertThat(StackFilter.isUserFrame(SELF, SELF)).isFalse();
  }

  @Test
  void scopeMachineryIsSkipped() {
    assertThat(StackFilter.isUserFrame("java.util.concurrent.StructuredTaskScope", SELF)).isFalse();
    assertThat(StackFilter.isUserFrame("java.util.concurrent.StructuredTaskScopeImpl", SELF))
        .isFalse();
  }

  @Test
  void unrelocatedByteBuddyIsSkipped() {
    assertThat(StackFilter.isUserFrame("net.bytebuddy.asm.Advice", SELF)).isFalse();
  }

  /** In the fat-jar this is the relocated package; the class literal follows the relocation. */
  @Test
  void agentsOwnByteBuddyIsSkipped() {
    assertThat(StackFilter.isUserFrame(ByteBuddy.class.getName(), SELF)).isFalse();
  }

  @Test
  void packageMerelyStartingWithByteBuddysNameIsKept() {
    assertThat(StackFilter.isUserFrame("net.bytebuddyfan.App", SELF)).isTrue();
  }
}
