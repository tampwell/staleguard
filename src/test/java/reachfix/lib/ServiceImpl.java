package reachfix.lib;

/** Created only by ServiceLoader. */
public class ServiceImpl implements Handler {
    public ServiceImpl() {
        Vulnerable.exploitService();
    }

    @Override
    public void handle() {
    }
}
