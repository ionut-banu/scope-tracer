import java.io.IOException;
import java.util.HexFormat;

/**
 * Writes raw bytes to stderr, then exits 0: {@code args[0]} bytes of {@code x}, then the bytes
 * given as hex in {@code args[1]} (a multi-byte character, say), then {@code args[2]} bytes of
 * {@code z}. Run by the bounded runner's tests to put a character across the 8 KiB cut.
 */
public class Utf8Edge {
  public static void main(String[] args) throws IOException {
    var out = System.err;
    out.write("x".repeat(Integer.parseInt(args[0])).getBytes());
    out.write(HexFormat.of().parseHex(args[1]));
    out.write("z".repeat(Integer.parseInt(args[2])).getBytes());
    out.flush();
  }
}
