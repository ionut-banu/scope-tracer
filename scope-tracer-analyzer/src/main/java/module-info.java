module com.ionutbanu.scopetracer.analyzer {
  requires com.ionutbanu.scopetracer.core;
  requires jdk.jfr;
  requires org.slf4j;

  exports com.ionutbanu.scopetracer.analyzer;
  exports com.ionutbanu.scopetracer.analyzer.model;
}
