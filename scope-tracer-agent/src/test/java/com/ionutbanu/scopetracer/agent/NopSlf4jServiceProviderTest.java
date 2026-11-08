package com.ionutbanu.scopetracer.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

class NopSlf4jServiceProviderTest {

  private final NopSlf4jServiceProvider provider = new NopSlf4jServiceProvider();

  @Test
  void initializeDoesNotThrow() {
    assertThatCode(provider::initialize).doesNotThrowAnyException();
  }

  @Test
  void loggersDiscardEverything() {
    provider.initialize();
    var logger = provider.getLoggerFactory().getLogger("any");
    assertThat(logger).isInstanceOf(NOPLogger.class);
    assertThat(logger.isErrorEnabled()).isFalse();
  }

  @Test
  void mdcKeepsNothing() {
    provider.getMDCAdapter().put("key", "value");
    assertThat(provider.getMDCAdapter().get("key")).isNull();
  }

  @Test
  void markersWork() {
    assertThat(provider.getMarkerFactory().getMarker("m").getName()).isEqualTo("m");
  }

  /** SLF4J 2.0 accepts any provider whose requested version starts with "2.0". */
  @Test
  void requestsTheSlf4jTwoApi() {
    assertThat(provider.getRequestedApiVersion()).startsWith("2.0");
  }
}
