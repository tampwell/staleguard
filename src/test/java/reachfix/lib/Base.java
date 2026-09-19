package reachfix.lib;

public class Base {
    public void template() {
        hook();
    }

    protected void hook() {
    }

    /** Inherited unchanged by Derived; reached through Derived's receiver type. */
    public void inherited() {
        Vulnerable.exploitInherited();
    }
}
