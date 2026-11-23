package com.ionutbanu.scopetracer.agent;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.commons.ClassRemapper;
import net.bytebuddy.jar.asm.commons.Remapper;

/**
 * A changed JDK for {@code AgentMatcherMissIT}: the real {@code
 * java/util/concurrent/StructuredTaskScope*} class bytes of the JDK under test, read from its
 * {@code jrt:/} file system, rewritten with the ASM inside ByteBuddy and written to a directory for
 * {@code --patch-module java.base=<dir>} (issue #130). It needs no JDK source, so it runs unchanged
 * on a newer JDK (#55); when the class it rewrites is not as expected, it fails naming that JDK.
 */
final class JdkPatch {

  static final String IMPL = "java/util/concurrent/StructuredTaskScopeImpl";

  /** The 3-argument constructor the agent's constructor advice matches on JDK 27. */
  static final String CONSTRUCTOR =
      "(Ljava/util/concurrent/StructuredTaskScope$Joiner;Ljava/util/concurrent/ThreadFactory;"
          + "Ljava/lang/String;)V";

  private static final String CONSTRUCTOR_4 = CONSTRUCTOR.replace(")V", "I)V");

  /** What the rewrite changes. */
  enum Change {
    /** Nothing: every class read and written back as it was. The control. */
    CONTROL,
    /** The 3-argument constructor gets a 4th {@code int} parameter; its caller passes {@code 0}. */
    CONSTRUCTOR,
    /** {@code StructuredTaskScopeImpl} and its nested classes become {@code ...Impl2}. */
    RENAMED
  }

  private JdkPatch() {}

  /** Writes the patched classes for {@code change} into {@code dir}; {@code dir}. */
  static Path write(Path dir, Change change) throws IOException {
    FileSystem jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
    List<Path> classes;
    try (Stream<Path> s = Files.list(jrt.getPath("/modules/java.base/java/util/concurrent"))) {
      classes =
          s.filter(p -> p.getFileName().toString().startsWith("StructuredTaskScope")).toList();
    }
    if (classes.stream()
        .noneMatch(p -> p.getFileName().toString().equals("StructuredTaskScopeImpl.class"))) {
      throw new AssertionError("no " + IMPL + ".class in the java.base of " + jdk());
    }
    var constructors = new AtomicInteger();
    var calls = new AtomicInteger();
    for (Path p : classes) {
      var reader = new ClassReader(Files.readAllBytes(p));
      var writer = new ClassWriter(0);
      String name = reader.getClassName();
      ClassVisitor visitor =
          switch (change) {
            case CONTROL -> writer;
            case RENAMED -> {
              Remapper remapper = new RenameImpl();
              name = remapper.map(name);
              yield new ClassRemapper(writer, remapper);
            }
            case CONSTRUCTOR -> new AddIntToConstructor(writer, name, constructors, calls);
          };
      reader.accept(visitor, 0);
      Path target = dir.resolve(name + ".class");
      Files.createDirectories(target.getParent());
      Files.write(target, writer.toByteArray());
    }
    if (change == Change.CONSTRUCTOR && (constructors.get() != 1 || calls.get() != 1)) {
      throw new AssertionError(
          "expected one "
              + IMPL
              + ".<init>"
              + CONSTRUCTOR
              + " and one call to it in the java.base of "
              + jdk()
              + "; found "
              + constructors.get()
              + " and "
              + calls.get());
    }
    return dir;
  }

  /** The JDK under test, for a failure message. */
  static String jdk() {
    return System.getProperty("java.vm.vendor")
        + " "
        + System.getProperty("java.runtime.version")
        + " at "
        + System.getProperty("java.home");
  }

  /** {@code StructuredTaskScopeImpl} and {@code StructuredTaskScopeImpl$*} to {@code ...Impl2}. */
  private static final class RenameImpl extends Remapper {
    @Override
    public String map(String internalName) {
      return internalName.equals(IMPL) || internalName.startsWith(IMPL + "$")
          ? IMPL + "2" + internalName.substring(IMPL.length())
          : internalName;
    }
  }

  /** Appends {@code I} to the 3-argument constructor and pushes {@code 0} at its one call. */
  private static final class AddIntToConstructor extends ClassVisitor {
    private final String owner;
    private final AtomicInteger constructors;
    private final AtomicInteger calls;

    AddIntToConstructor(
        ClassVisitor next, String owner, AtomicInteger constructors, AtomicInteger calls) {
      super(Opcodes.ASM9, next);
      this.owner = owner;
      this.constructors = constructors;
      this.calls = calls;
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String descriptor, String signature, String[] exceptions) {
      boolean isTarget =
          owner.equals(IMPL) && name.equals("<init>") && descriptor.equals(CONSTRUCTOR);
      if (isTarget) constructors.incrementAndGet();
      MethodVisitor mv =
          super.visitMethod(
              access,
              name,
              isTarget ? CONSTRUCTOR_4 : descriptor,
              isTarget ? null : signature,
              exceptions);
      return new MethodVisitor(Opcodes.ASM9, mv) {
        @Override
        public void visitMethodInsn(
            int opcode, String callOwner, String callName, String callDesc, boolean itf) {
          if (opcode == Opcodes.INVOKESPECIAL
              && callOwner.equals(IMPL)
              && callName.equals("<init>")
              && callDesc.equals(CONSTRUCTOR)) {
            calls.incrementAndGet();
            super.visitInsn(Opcodes.ICONST_0);
            super.visitMethodInsn(opcode, callOwner, callName, CONSTRUCTOR_4, itf);
          } else {
            super.visitMethodInsn(opcode, callOwner, callName, callDesc, itf);
          }
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
          // one more stack slot for the pushed 0; one more local for the new parameter
          super.visitMaxs(maxStack + 1, maxLocals + (isTarget ? 1 : 0));
        }
      };
    }
  }
}
