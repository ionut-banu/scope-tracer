package com.ionutbanu.scopetracer.plugin;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.ui.jcef.JBCefApp;

/**
 * Whether the embedded browser can be used (#62). {@link JBCefApp#isSupported()} is false when the
 * registry key {@code ide.browser.jcef.enabled} is off, on a runtime other than the bundled JBR, on
 * a Linux with too old a glibc, and in a headless IDE; then the tool window shows only "Tasks". It
 * is asked before any JCEF object is made, and an IDE without the JCEF classes at all counts as
 * not supported rather than as an error.
 */
final class JcefSupport {

  private static final Logger LOG = Logger.getInstance(JcefSupport.class);

  private JcefSupport() {}

  static boolean isSupported() {
    try {
      return JBCefApp.isSupported();
    } catch (LinkageError | RuntimeException e) {
      LOG.info("Scope Tracer: JCEF is not available (" + e + "); showing the task table only");
      return false;
    }
  }
}
