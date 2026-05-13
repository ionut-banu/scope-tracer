module com.ionutbanu.scopetracer.core {
  requires jdk.jfr;
  requires org.slf4j;

  exports com.ionutbanu.scopetracer.core;
  exports com.ionutbanu.scopetracer.core.events;

  // JFR reads public Event fields reflectively when serializing.
  opens com.ionutbanu.scopetracer.core.events to
      jdk.jfr;
}
