package reachfix.lib;

/** A Runnable nobody creates: a call through Runnable must not reach it. */
public class UncreatedTask implements Runnable {
    @Override
    public void run() {
        Vulnerable.exploitUncreated();
    }
}
