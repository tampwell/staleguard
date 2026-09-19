package reachfix.app;

import reachfix.lib.Base;
import reachfix.lib.CallbackTask;
import reachfix.lib.DefaultImpl;
import reachfix.lib.Derived;
import reachfix.lib.Dispatcher;
import reachfix.lib.LambdaUser;
import reachfix.lib.MethodRefUser;
import reachfix.lib.Parser;
import reachfix.lib.Quiet;
import reachfix.lib.StaticInit;

/** The project's own code: every method here is an entry point. */
public class App {
    public void parse() {
        new Parser().parse("x");
    }

    public void cha() {
        new Dispatcher().dispatch(null);
    }

    public void callback() {
        new Thread(new CallbackTask()).start();
    }

    public void throughRunnable(Runnable runnable) {
        runnable.run();
    }

    public int staticInit() {
        return StaticInit.value;
    }

    public Runnable lambda() {
        return LambdaUser.make();
    }

    public void methodRef() {
        MethodRefUser.apply();
    }

    public void defaultMethod() {
        new DefaultImpl().defaultThing();
    }

    public void template() {
        Base base = new Derived();
        base.template();
        new Derived().inherited();
    }

    public Runnable quiet() {
        return new Quiet();
    }
}
