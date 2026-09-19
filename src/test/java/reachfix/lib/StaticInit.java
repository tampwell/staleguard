package reachfix.lib;

public class StaticInit {
    public static int value;

    static {
        Vulnerable.exploitStaticInit();
    }
}
