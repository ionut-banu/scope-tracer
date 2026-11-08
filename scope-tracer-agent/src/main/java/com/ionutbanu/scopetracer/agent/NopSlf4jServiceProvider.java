package com.ionutbanu.scopetracer.agent;

import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.helpers.NOPLoggerFactory;
import org.slf4j.helpers.NOPMDCAdapter;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

/**
 * The only SLF4J provider the agent's relocated SLF4J finds: it discards every log call.
 *
 * <p>The agent fat-jar carries the analyzer, which logs through SLF4J, and relocates SLF4J so that
 * it does not replace the application's own copy on the bootstrap class path. The relocated copy
 * looks for providers of its relocated {@code SLF4JServiceProvider}, which no logging backend
 * implements. Without a provider it would print {@code SLF4J(W): No SLF4J providers were found}
 * into the application's output on the first auto-HTML report; this one keeps it quiet. The
 * analyzer's log lines are dropped, as they were before when the application had no binding.
 *
 * <p>Registered in {@code META-INF/services} under the relocated interface name, so only the
 * fat-jar sees it: the unshaded jar and this module's tests keep using whatever binding is on the
 * class path.
 */
public final class NopSlf4jServiceProvider implements SLF4JServiceProvider {

  /** The SLF4J API version this provider is written against, in SLF4J's own format. */
  static final String REQUESTED_API_VERSION = "2.0.99";

  private final ILoggerFactory loggerFactory = new NOPLoggerFactory();
  private final IMarkerFactory markerFactory = new BasicMarkerFactory();
  private final MDCAdapter mdcAdapter = new NOPMDCAdapter();

  /** Called by SLF4J's {@code ServiceLoader}. */
  public NopSlf4jServiceProvider() {}

  @Override
  public ILoggerFactory getLoggerFactory() {
    return loggerFactory;
  }

  @Override
  public IMarkerFactory getMarkerFactory() {
    return markerFactory;
  }

  @Override
  public MDCAdapter getMDCAdapter() {
    return mdcAdapter;
  }

  @Override
  public String getRequestedApiVersion() {
    return REQUESTED_API_VERSION;
  }

  @Override
  public void initialize() {
    // Nothing to set up: every part is created with the provider and none can fail.
  }
}
