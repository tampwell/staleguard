package reachfix.lib;

public class Parser {
    public void parse(String input) {
        Helper.normalize(input);
    }

    /** Present on the classpath, never called: the headline "not reached". */
    public void unusedPath() {
        Vulnerable.exploitUnused();
    }
}
