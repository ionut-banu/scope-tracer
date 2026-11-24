import java.util.concurrent.CountDownLatch;

/** Blocks forever on a latch nobody counts down. Stands in for an analyzer that hangs. */
public class NeverExits {
  public static void main(String[] args) throws InterruptedException {
    new CountDownLatch(1).await();
  }
}
