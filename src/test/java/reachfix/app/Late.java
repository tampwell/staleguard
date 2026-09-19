package reachfix.app;

import reachfix.lib.Factory;

/** Several hops deep on purpose, so the creation is reached after the dispatch site. */
public class Late {
    public void start() {
        stepOne();
    }

    private void stepOne() {
        stepTwo();
    }

    private void stepTwo() {
        Factory.usedFactory();
    }
}
