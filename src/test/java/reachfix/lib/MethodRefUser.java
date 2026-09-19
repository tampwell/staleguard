package reachfix.lib;

import java.util.List;

public class MethodRefUser {
    public static void apply() {
        List.of("x").forEach(Vulnerable::exploitMethodRef);
    }
}
