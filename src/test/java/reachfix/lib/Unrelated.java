package reachfix.lib;

/** Same method name as Base.hook, outside the hierarchy: must never be dispatched to. */
public class Unrelated {
    protected void hook() {
        Vulnerable.exploitUnrelated();
    }
}
