package reachfix.lib;

/** Only the JDK ever calls run(); reachable because the program creates one. */
public class CallbackTask implements Runnable {
    @Override
    public void run() {
        new Vulnerable().exploitViaCallback();
    }
}
