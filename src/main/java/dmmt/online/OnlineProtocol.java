package dmmt.online;

/** Protocol constants of Online Play (browser clients); independent of the DM Link {@code RemoteCompatibility}. */
public final class OnlineProtocol {
    /** Bumped on incompatible changes of the HTTP/WebSocket messages between host and browser client. */
    public static final int VERSION = 1;
    /** Compatibility of the development format-2 automation HTTP contract, separate from DM Link. */
    public static final int AUTOMATION_COMPATIBILITY_VERSION = 1;
    public static final String COOKIE = "dmmt_online";

    private OnlineProtocol() {
    }
}
