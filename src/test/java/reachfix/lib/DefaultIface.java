package reachfix.lib;

public interface DefaultIface {
    default void defaultThing() {
        Vulnerable.exploitDefault();
    }
}
