package reachfix.lib;

/** Created by the program, but its non-JDK method is never called. Callbacks cover JDK overrides only. */
public class Quiet implements Runnable {
    @Override
    public void run() {
    }

    public void notAnOverride() {
        Vulnerable.exploitQuiet();
    }
}
