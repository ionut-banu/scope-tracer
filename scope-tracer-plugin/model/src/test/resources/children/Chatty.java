import java.io.IOException;

/**
 * Writes {@code args[0]} bytes to stdout, then as many to stderr, then exits 0. Run by the bounded
 * runner's tests: more than a pipe buffer on each stream deadlocks a parent that reads one stream
 * to the end before draining the other.
 */
public class Chatty {
  public static void main(String[] args) throws IOException {
    int bytes = Integer.parseInt(args[0]);
    var chunk = new byte[8192];
    java.util.Arrays.fill(chunk, (byte) 'x');
    write(System.out, bytes, chunk);
    write(System.err, bytes, chunk);
  }

  private static void write(java.io.PrintStream out, int bytes, byte[] chunk) throws IOException {
    for (int left = bytes; left > 0; left -= chunk.length) {
      out.write(chunk, 0, Math.min(left, chunk.length));
    }
    out.flush();
  }
}
