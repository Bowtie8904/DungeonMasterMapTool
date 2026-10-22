package dmmt.remote;

import java.io.IOException;

public interface RemoteNetworkAccess {
    record Status(boolean configured, String profile) {
        public boolean privateNetwork() { return "Private".equals(profile); }
    }

    boolean supported();
    Status check(RemoteHostingController.Configuration configuration) throws IOException;
    void allow(RemoteHostingController.Configuration configuration) throws IOException;
    void remove() throws IOException;
}
