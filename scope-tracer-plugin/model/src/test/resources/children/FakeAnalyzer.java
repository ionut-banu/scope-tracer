import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * Stands in for the analyzer's executable jar: {@code <in.jfr> <out.json> --format=json}. The
 * "recording" holds one word saying what to do: {@code ok} writes a JSON trace and exits 0,
 * {@code fail} writes a line on stderr and exits 2, {@code hang} never exits.
 */
public class FakeAnalyzer {
  public static void main(String[] args) throws Exception {
    if (args.length != 3 || !args[2].equals("--format=json")) {
      System.err.println("unexpected arguments: " + String.join(" ", args));
      System.exit(64);
    }
    switch (Files.readString(Path.of(args[0])).trim()) {
      case "ok" -> Files.writeString(Path.of(args[1]), "{\"scopes\":[]}");
      case "fail" -> {
        System.err.println("error: not a recording");
        System.exit(2);
      }
      case "hang" -> new CountDownLatch(1).await();
      default -> System.exit(65);
    }
  }
}
