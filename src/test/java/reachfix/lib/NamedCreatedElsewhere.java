package reachfix.lib;

/** Created in code only by an unreached factory; plugin systems create classes like this by name. */
public class NamedCreatedElsewhere implements Handler {
    @Override
    public void handle() {
        Vulnerable.exploitNamedCreatedElsewhere();
    }
}
