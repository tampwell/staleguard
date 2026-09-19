package reachfix.lib;

public class Derived extends Base {
    @Override
    protected void hook() {
        Vulnerable.exploitHook();
    }
}
