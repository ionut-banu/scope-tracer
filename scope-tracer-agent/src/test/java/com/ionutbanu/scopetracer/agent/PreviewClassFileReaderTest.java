package com.ionutbanu.scopetracer.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.instrument.ClassFileTransformer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.ClassFileVersion;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.implementation.StubMethod;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.AsmClassReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The agent's type-pool reader (#176), on JDK 27 without a JDK 28: {@code java.time.Duration}'s
 * bytes from the running JDK, restamped as the preview class file JDK 28 serves under {@code
 * --enable-preview} (72.65535), fail ByteBuddy's default pool exactly as on 28, and the agent's
 * pool describes them. That the agent installs the pool and traces on 28 is the agent ITs' job, run
 * on 28 with {@code -Dscopetracer.test.preview=true}.
 */
class PreviewClassFileReaderTest {

  private static final String DURATION = "java.time.Duration";
  private static final String SYNTHETIC = "com.ionutbanu.scopetracer.agent.synthetic.Timed";
  private static final int LATEST_MAJOR = ClassFileVersion.latest().getMajorVersion();
  private static final int PREVIEW = 0xFFFF;

  private static byte[] jdkClass(String name) throws IOException {
    try (InputStream in =
        ClassLoader.getSystemResourceAsStream(name.replace('.', '/') + ".class")) {
      assertThat(in).as(name).isNotNull();
      return in.readAllBytes();
    }
  }

  /** {@code bytes} with its version set to {@code major.minor}, on a copy. */
  private static byte[] versioned(byte[] bytes, int major, int minor) {
    byte[] copy = bytes.clone();
    copy[4] = (byte) (minor >>> 8);
    copy[5] = (byte) minor;
    copy[6] = (byte) (major >>> 8);
    copy[7] = (byte) major;
    return copy;
  }

  private static int minorOf(byte[] bytes) {
    return (bytes[4] & 0xFF) << 8 | (bytes[5] & 0xFF);
  }

  private static int majorOf(byte[] bytes) {
    return (bytes[6] & 0xFF) << 8 | (bytes[7] & 0xFF);
  }

  /** A class declaring {@code public void schedule(Duration)}, as the JDK's own version. */
  private static byte[] synthetic() {
    return new ByteBuddy()
        .subclass(Object.class)
        .name(SYNTHETIC)
        .defineMethod("schedule", void.class, Visibility.PUBLIC)
        .withParameters(Duration.class)
        .intercept(StubMethod.INSTANCE)
        .make()
        .getBytes();
  }

  /** The synthetic class and {@code duration} as {@code java.time.Duration}, then the JDK. */
  private static ClassFileLocator locator(byte[] synthetic, byte[] duration) {
    return new ClassFileLocator.Compound(
        ClassFileLocator.Simple.of(SYNTHETIC, synthetic),
        ClassFileLocator.Simple.of(DURATION, duration),
        ClassFileLocator.ForClassLoader.ofSystemLoader());
  }

  /** What the agent does to a scope class: describe it through the pool, then redefine it. */
  private static byte[] redefine(
      AgentBuilder.PoolStrategy pool, ClassFileLocator locator, String name) {
    TypeDescription type = pool.typePool(locator, null, name).describe(name).resolve();
    return new ByteBuddy().redefine(type, locator).make().getBytes();
  }

  private static TypeDescription describe(AgentBuilder.PoolStrategy pool, byte[] duration) {
    TypeDescription type =
        pool.typePool(locator(synthetic(), duration), null).describe(DURATION).resolve();
    type.getModifiers(); // a lazy pool reads the class file only now
    return type;
  }

  /** Name, modifiers and the signature of each declared method. */
  private static List<String> shape(TypeDescription type) {
    var shape = new ArrayList<String>();
    shape.add(type.getName());
    shape.add(Integer.toString(type.getModifiers()));
    for (MethodDescription.InDefinedShape m : type.getDeclaredMethods()) {
      shape.add(m.getModifiers() + " " + m.toGenericString());
    }
    return shape;
  }

  /** A reader that records each array it is handed and reads it with ASM_ONLY. */
  private static final class Recording implements AsmClassReader.Factory {
    final List<byte[]> handed = new ArrayList<>();

    @Override
    public AsmClassReader make(byte[] bytes) {
      return make(bytes, false);
    }

    @Override
    public AsmClassReader make(byte[] bytes, boolean experimental) {
      handed.add(bytes);
      return AsmClassReader.Factory.Default.ASM_ONLY.make(bytes, experimental);
    }
  }

  /** {@code reader} with ByteBuddy's experimental flag {@code true}, as {@code premain} sets it. */
  private static AsmClassReader.Factory experimental(AsmClassReader.Factory reader) {
    return new AsmClassReader.Factory() {
      @Override
      public AsmClassReader make(byte[] bytes) {
        return reader.make(bytes, true);
      }

      @Override
      public AsmClassReader make(byte[] bytes, boolean ignored) {
        return reader.make(bytes, true);
      }
    };
  }

  @Test
  void aPreviewDurationFailsByteBuddysDefaultPoolAndIsReadByTheAgentsPool() throws IOException {
    byte[] synthetic = synthetic();
    byte[] previewDuration = versioned(jdkClass(DURATION), LATEST_MAJOR, PREVIEW);
    ClassFileLocator locator = locator(synthetic, previewDuration);

    // Today's agent, which uses ByteBuddy's default pool strategy: the failure seen on JDK 28.
    assertThatThrownBy(() -> redefine(AgentBuilder.PoolStrategy.Default.FAST, locator, SYNTHETIC))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unsupported class file version " + LATEST_MAJOR + ".65535");

    byte[] redefined = redefine(PreviewClassFileReader.POOL, locator, SYNTHETIC);

    // The transformed class is read and written with its own version, never the pool's copy.
    assertThat(ClassFileVersion.ofClassFile(redefined))
        .isEqualTo(ClassFileVersion.ofClassFile(synthetic));
  }

  @Test
  void aPreviewClassFileIsDescribedAsTheSameBytesWithMinorZero() throws IOException {
    byte[] duration = jdkClass(DURATION);

    TypeDescription preview =
        describe(PreviewClassFileReader.POOL, versioned(duration, LATEST_MAJOR, PREVIEW));
    TypeDescription plain =
        describe(PreviewClassFileReader.POOL, versioned(duration, LATEST_MAJOR, 0));

    assertThat(shape(preview)).isEqualTo(shape(plain)).contains(DURATION).hasSizeGreaterThan(50);
  }

  @Test
  void theArrayTheReaderIsHandedIsNeverModified() throws IOException {
    byte[] preview = versioned(jdkClass(DURATION), LATEST_MAJOR, PREVIEW);
    byte[] before = preview.clone();
    var recording = new Recording();

    new PreviewClassFileReader(recording).make(preview);

    assertThat(preview).isEqualTo(before);
    assertThat(recording.handed).singleElement().isNotSameAs(preview);
    byte[] read = recording.handed.getFirst();
    assertThat(minorOf(read)).isZero();
    assertThat(majorOf(read)).isEqualTo(LATEST_MAJOR);
    // Only the minor differs.
    assertThat(versioned(read, LATEST_MAJOR, PREVIEW)).isEqualTo(before);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 3})
  void anyOtherMinorReachesAsmOnlyUnchanged(int minor) throws IOException {
    byte[] bytes = versioned(jdkClass(DURATION), LATEST_MAJOR, minor);
    byte[] before = bytes.clone();
    var recording = new Recording();

    catchThrowable(() -> new PreviewClassFileReader(recording).make(bytes));

    assertThat(recording.handed).singleElement().isSameAs(bytes);
    assertThat(bytes).isEqualTo(before);
  }

  @Test
  void aMinorOtherThanPreviewFailsExactlyAsWithByteBuddysDefaultPool() throws IOException {
    byte[] minor3 = versioned(jdkClass(DURATION), LATEST_MAJOR, 3);

    Throwable byDefault =
        catchThrowable(() -> describe(AgentBuilder.PoolStrategy.Default.FAST, minor3));
    Throwable byAgent = catchThrowable(() -> describe(PreviewClassFileReader.POOL, minor3));

    assertThat(byDefault).isNotNull();
    assertThat(byAgent)
        .hasSameClassAs(byDefault)
        .hasMessage(byDefault.getMessage())
        // ByteBuddy prints the version as minor << 16 | major
        .hasMessageContaining("(" + (3 << 16 | LATEST_MAJOR) + ")");
  }

  @Test
  void aPreviewClassFileAboveTheLatestVersionTakesByteBuddysExperimentalPath() throws IOException {
    byte[] duration = jdkClass(DURATION);
    byte[] above = versioned(duration, LATEST_MAJOR + 1, PREVIEW);
    var recording = new Recording();

    // Only the minor is cleared: ByteBuddy's own version handling sees 73.0.
    new PreviewClassFileReader(recording).make(above, true);
    assertThat(majorOf(recording.handed.getFirst())).isEqualTo(LATEST_MAJOR + 1);
    assertThat(minorOf(recording.handed.getFirst())).isZero();

    // With the experimental flag, as premain sets it, it is described.
    var agentReader = new PreviewClassFileReader(AsmClassReader.Factory.Default.ASM_ONLY);
    TypeDescription described =
        describe(new PreviewClassFileReader.Pool(experimental(agentReader)), above);
    TypeDescription plain =
        describe(PreviewClassFileReader.POOL, versioned(duration, LATEST_MAJOR, 0));
    assertThat(shape(described)).isEqualTo(shape(plain));

    // Without it, ByteBuddy refuses 73.0 by its own rule, not ASM 73.65535.
    assertThatThrownBy(() -> agentReader.make(above, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("is not supported by the current version of Byte Buddy");
  }

  /**
   * ByteBuddy's transformer reports the reader's exception to the listener and rethrows it wrapped,
   * as it does for today's 72.65535 failure; the JVM's transformer manager catches whatever a
   * transformer throws, so the class loads untransformed and the application sees nothing.
   */
  @Test
  void aReaderThatThrowsGoesToByteBuddysErrorHandlingAndGivesOneLine() throws Exception {
    var failure = new IllegalStateException("reader failed");
    var throwing =
        new AsmClassReader.Factory() {
          @Override
          public AsmClassReader make(byte[] bytes) {
            throw failure;
          }

          @Override
          public AsmClassReader make(byte[] bytes, boolean experimental) {
            throw failure;
          }
        };
    ClassFileTransformer transformer =
        new AgentBuilder.Default()
            .with(new PreviewClassFileReader.Pool(throwing))
            .with(new TransformFailureListener())
            .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
            .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
            .ignore(ElementMatchers.none())
            .type(ElementMatchers.named(TransformFailureListener.SCOPE_IMPL))
            .transform((b, type, loader, module, domain) -> b)
            .makeRaw();
    String internalName = TransformFailureListener.SCOPE_IMPL.replace('.', '/');
    byte[] scopeImpl = jdkClass(TransformFailureListener.SCOPE_IMPL);

    var stderr = new ByteArrayOutputStream();
    var thrown = new ArrayList<Throwable>();
    PrintStream original = System.err;
    System.setErr(new PrintStream((OutputStream) stderr, true, StandardCharsets.UTF_8));
    try {
      for (int i = 0; i < 3; i++) {
        // What sun.instrument.TransformerManager does with a transformer's exception: drops it.
        thrown.add(
            catchThrowable(
                () -> transformer.transform(null, internalName, null, null, scopeImpl.clone())));
      }
    } finally {
      System.setErr(original);
    }

    assertThat(thrown)
        .hasSize(3)
        .allSatisfy(
            t ->
                assertThat(t)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Failed transformation of " + TransformFailureListener.SCOPE_IMPL)
                    .cause()
                    .isSameAs(failure));
    assertThat(stderr.toString(StandardCharsets.UTF_8).lines())
        .containsExactly(
            "[scope-tracer] WARN cannot instrument "
                + TransformFailureListener.SCOPE_IMPL
                + ", the trace will be incomplete: IllegalStateException: reader failed");
  }
}
