import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * Stands in for the analyzer's executable jar: {@code <in.jfr> <out.json> --format=json} writes
 * JSON, {@code <in.jfr> <out.html>} (no option, the CLI's default) writes HTML. The "recording"
 * holds one word saying what to do: {@code ok} writes the output and exits 0, {@code fail} writes
 * a line on stderr and exits 2, {@code hang} never exits, and {@code htmlfail} behaves as {@code
 * ok} for JSON and fails with exit 3 for HTML. {@code empty} exits 0 and leaves an empty output
 * file, as an analyzer that wrote nothing would.
 */
public class FakeAnalyzer {
  public static void main(String[] args) throws Exception {
    boolean json = args.length == 3 && args[2].equals("--format=json");
    if (!json && args.length != 2) {
      System.err.println("unexpected arguments: " + String.join(" ", args));
      System.exit(64);
    }
    var out = Path.of(args[1]);
    switch (Files.readString(Path.of(args[0])).trim()) {
      case "ok" -> write(out, json);
      case "empty" -> Files.writeString(out, "");
      case "htmlfail" -> {
        if (json) {
          write(out, true);
        } else {
          System.err.println("error: cannot render");
          System.exit(3);
        }
      }
      case "fail" -> {
        System.err.println("error: not a recording");
        System.exit(2);
      }
      case "hang" -> new CountDownLatch(1).await();
      default -> System.exit(65);
    }
  }

  private static void write(Path out, boolean json) throws Exception {
    Files.writeString(out, json ? "{\"scopes\":[]}" : "<html>ok</html>\n");
  }
}
