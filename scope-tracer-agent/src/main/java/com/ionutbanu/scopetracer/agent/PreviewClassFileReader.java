package com.ionutbanu.scopetracer.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.AsmClassReader;
import net.bytebuddy.utility.OpenedClassReader;

/**
 * The class reader of the agent's {@code TypePool}: it reads a class file whose minor version is
 * {@code 0xFFFF} (a preview class file) as minor 0, on a copy of the bytes, and hands every other
 * class file to ByteBuddy's ASM reader unchanged (#176).
 *
 * <p>On JDK 28 with {@code --enable-preview} the JVM serves the {@code java.base/META-INF/preview}
 * variants of 57 JDK classes, {@code java.time.Duration} among them, and their bytes are 72.65535.
 * ByteBuddy describes {@code Duration} to validate {@code
 * StructuredTaskScopeImpl.scheduleTimeout(Duration)}, and ASM rejects 72.65535 on purpose until it
 * supports value classes (ASM !473). ByteBuddy's experimental property does not help: it reads the
 * minor version as a signed {@code short}, so 72.65535 compares as older than its latest version
 * and is never patched. Without this reader the agent printed {@code cannot instrument
 * java.util.concurrent.StructuredTaskScopeImpl} and recorded only {@code ScopeOpened}.
 *
 * <p>It is used for the type pool only, which ByteBuddy reads to <em>describe</em> types (to match
 * and validate). The class being transformed is read and written by the {@code ByteBuddy}
 * instance's own reader, with its own version, so the JVM never receives a class file whose version
 * the agent changed. A preview variant of a scope class itself still fails as before, with the
 * {@code cannot instrument} line.
 *
 * <pre>{@code
 * new AgentBuilder.Default().with(PreviewClassFileReader.POOL) ...
 * }</pre>
 */
final class PreviewClassFileReader implements AsmClassReader.Factory {

  /** The pool strategy the agent installs. */
  static final AgentBuilder.PoolStrategy POOL =
      new Pool(new PreviewClassFileReader(AsmClassReader.Factory.Default.ASM_ONLY));

  private static final int PREVIEW_MINOR = 0xFFFF;

  private final AsmClassReader.Factory delegate;

  /** A reader that clears a preview minor and then reads with {@code delegate}. */
  PreviewClassFileReader(AsmClassReader.Factory delegate) {
    this.delegate = delegate;
  }

  /**
   * What {@code TypePool.Default} calls. Passes ByteBuddy's experimental property, which {@code
   * premain} sets (relocated in the fat-jar), so a class file above ByteBuddy's latest version
   * still takes ByteBuddy's own experimental path once its preview minor is cleared.
   */
  @Override
  public AsmClassReader make(byte[] binaryRepresentation) {
    return make(binaryRepresentation, OpenedClassReader.EXPERIMENTAL);
  }

  @Override
  public AsmClassReader make(byte[] binaryRepresentation, boolean experimental) {
    return delegate.make(withoutPreviewMinor(binaryRepresentation), experimental);
  }

  /**
   * {@code bytes} itself unless its minor version (bytes 4-5) is {@code 0xFFFF}; then a copy with
   * minor 0. The array passed in is never modified.
   */
  static byte[] withoutPreviewMinor(byte[] bytes) {
    if (bytes.length < 8 || ((bytes[4] & 0xFF) << 8 | (bytes[5] & 0xFF)) != PREVIEW_MINOR) {
      return bytes;
    }
    byte[] copy = bytes.clone();
    copy[4] = 0;
    copy[5] = 0;
    return copy;
  }

  /**
   * ByteBuddy's {@code PoolStrategy.Default.FAST} (lazy resolution, {@code ReaderMode.FAST}) with
   * {@code reader} as its class reader.
   */
  record Pool(AsmClassReader.Factory reader) implements AgentBuilder.PoolStrategy {

    @Override
    public TypePool typePool(ClassFileLocator classFileLocator, ClassLoader classLoader) {
      return new TypePool.LazyFacade(
          new TypePool.Default.WithLazyResolution(
              TypePool.CacheProvider.Simple.withObjectType(),
              classFileLocator,
              TypePool.Default.ReaderMode.FAST,
              reader));
    }

    @Override
    public TypePool typePool(
        ClassFileLocator classFileLocator, ClassLoader classLoader, String name) {
      return typePool(classFileLocator, classLoader);
    }
  }
}
