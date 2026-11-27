import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Starts a second JVM and records its pid in a file, then blocks forever. Stands in for an analyzer
 * that has a descendant process. Args: the pid file, the java launcher, the source file the second
 * JVM runs (which never exits).
 */
public class Spawner {
  public static void main(String[] args) throws Exception {
    var grandchild = new ProcessBuilder(args[1], args[2]).inheritIO().start();
    var pidFile = Path.of(args[0]);
    var partial = pidFile.resolveSibling(pidFile.getFileName() + ".partial");
    Files.writeString(partial, Long.toString(grandchild.pid()));
    Files.move(partial, pidFile, StandardCopyOption.ATOMIC_MOVE);
    grandchild.waitFor();
  }
}
