package reachfix.lib;

public class Factory {
    /** Nothing calls this: its anonymous class can never exist. */
    public static Handler unusedFactory() {
        return new Handler() {
            @Override
            public void handle() {
                Vulnerable.exploitCreatedElsewhere();
            }
        };
    }

    /** Reached several hops deep, after Handler.handle was already expanded. */
    public static Handler usedFactory() {
        return new Handler() {
            @Override
            public void handle() {
                Vulnerable.exploitCreatedLate();
            }
        };
    }

    /** Nothing calls this either, but a named class can still be created by name. */
    public static Handler unusedNamedFactory() {
        return new NamedCreatedElsewhere();
    }
}
