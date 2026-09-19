package reachfix.lib;

public class Dispatcher {
    public void dispatch(Handler handler) {
        handler.handle();
    }
}
