package dmmt.online;

/** Protocol constants of Online Play (browser clients); independent of the DM Link {@code RemoteCompatibility}. */
public final class OnlineProtocol {
    /** Bumped on incompatible changes of the HTTP/WebSocket messages between host and browser client. */
    public static final int VERSION = 1;
    /** Compatibility of the development format-2 automation HTTP contract, separate from DM Link. */
    /** Online Play contract: format-2 catalogs and the automation endpoints. Independent of the DM Link version. */
    public static final int AUTOMATION_COMPATIBILITY_VERSION = 2;
    /** Request header carrying the browser's contract version on sheet loads and every mutating call. */
    public static final String CONTRACT_HEADER = "X-Online-Contract";
    /** WebSocket query parameter carrying the browser's contract version. */
    public static final String CONTRACT_PARAM = "contract";
    public static final String COOKIE = "dmmt_online";

    private OnlineProtocol() {
    }
}
