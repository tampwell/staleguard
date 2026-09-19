package reachfix.lib;

/** One target per scenario, so every verdict is asserted on its own. */
public class Vulnerable {
    public static void exploitUnused() {}
    public static void exploitViaCha() {}
    public void exploitViaCallback() {}
    public static void exploitUncreated() {}
    public static void exploitStaticInit() {}
    public static void exploitLambda() {}
    public static void exploitMethodRef(String value) {}
    public static void exploitDefault() {}
    public static void exploitService() {}
    public static void exploitHook() {}
    public static void exploitUnrelated() {}
    public static void exploitQuiet() {}
    public static void exploitInherited() {}
    public static void exploitCreatedElsewhere() {}
    public static void exploitCreatedLate() {}
    public static void exploitNamedCreatedElsewhere() {}
}
