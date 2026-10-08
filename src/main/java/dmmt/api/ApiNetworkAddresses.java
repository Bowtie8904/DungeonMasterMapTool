package dmmt.api;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Local interface addresses, without DNS lookups or contacting an external service. */
final class ApiNetworkAddresses {
    private ApiNetworkAddresses() {
    }

    private record Address(String host, int priority, int interfaceIndex) {
    }

    static List<String> hosts() {
        List<Address> addresses = new ArrayList<>();
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) {
                    continue;
                }
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        int priority = (network.isVirtual() ? 4 : 0)
                                + (address.isSiteLocalAddress() ? 0 : address.isLinkLocalAddress() ? 2 : 1);
                        addresses.add(new Address(address.getHostAddress(), priority, network.getIndex()));
                    }
                }
            }
        } catch (SocketException ex) {
            throw new IllegalStateException("Could not determine the API network address.", ex);
        }
        return addresses.stream().sorted(Comparator.comparingInt(Address::priority)
                .thenComparingInt(Address::interfaceIndex).thenComparing(Address::host))
                .map(Address::host).distinct().toList();
    }
}
