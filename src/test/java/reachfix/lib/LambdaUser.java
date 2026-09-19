package reachfix.lib;

public class LambdaUser {
    public static Runnable make() {
        return () -> Vulnerable.exploitLambda();
    }
}
