package reachfix.lib;

/** Never created with new anywhere: libraries make these reflectively. CHA must still see it. */
public class DangerousHandler implements Handler {
    @Override
    public void handle() {
        Vulnerable.exploitViaCha();
    }
}
