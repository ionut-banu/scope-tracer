/** Prints its first argument to stdout and its second to stderr, each as one line. */
public class Printer {
  public static void main(String[] args) {
    if (!args[0].isEmpty()) {
      System.out.println(args[0]);
      System.out.flush();
    }
    if (!args[1].isEmpty()) {
      System.err.println(args[1]);
      System.err.flush();
    }
  }
}
