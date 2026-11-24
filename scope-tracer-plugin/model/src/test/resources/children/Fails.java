/**
 * Writes {@code args[1]} as one line on stderr, then {@code args[2]} more bytes of filler on
 * stderr, and exits with {@code args[0]}.
 */
public class Fails {
  public static void main(String[] args) {
    System.err.println(args[1]);
    int filler = Integer.parseInt(args[2]);
    var line = "y".repeat(1023) + "\n";
    for (int left = filler; left > 0; left -= line.length()) {
      System.err.print(line);
    }
    System.err.flush();
    System.exit(Integer.parseInt(args[0]));
  }
}
