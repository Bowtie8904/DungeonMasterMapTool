package dmmt.remote;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Bounded clipboard metadata, not authentication. The fingerprint must be independently verified. */
public record RemoteConnectionDetails(String name, String host, int controlPort, int assetPort,
                                      String fingerprint, String build) {
    private static final String HEADER = "DMMT remote session 1";
    private static final Set<String> KEYS = Set.of("name", "host", "control", "assets", "fingerprint", "build");

    public RemoteConnectionDetails {
        name = new RemoteSessionAccess.Client(name, "host").name();
        if (host == null || host.isBlank() || host.length() > 253
                || host.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("A bounded host address is required.");
        }
        host = host.strip();
        if (controlPort < 1 || controlPort > 65535 || assetPort < 1 || assetPort > 65535 || controlPort == assetPort
                || fingerprint == null || !fingerprint.matches("[a-fA-F0-9]{64}")
                || build == null || !build.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("Invalid remote connection identity or ports.");
        }
        fingerprint = fingerprint.toLowerCase(Locale.ROOT);
    }

    public static RemoteConnectionDetails from(RemoteHostingController.Details details) {
        if (details.bindAddress().isAnyLocalAddress()) {
            throw new IllegalArgumentException("Select a specific LAN interface before copying connection details.");
        }
        return new RemoteConnectionDetails(details.name(), details.bindAddress().getHostAddress(), details.controlPort(),
                details.assetPort(), details.fingerprint(), details.build());
    }

    public String format() {
        return HEADER + "\nname=" + name + "\nhost=" + host + "\ncontrol=" + controlPort + "\nassets=" + assetPort
                + "\nfingerprint=" + fingerprint + "\nbuild=" + build;
    }

    public static RemoteConnectionDetails parse(String text) {
        if (text == null || text.length() > 2048) {
            throw new IllegalArgumentException("Connection details are missing or too large.");
        }
        String[] lines = text.strip().split("\\r?\\n", -1);
        if (lines.length != 7 || !HEADER.equals(lines[0])) {
            throw new IllegalArgumentException("Paste connection details copied from the host setup window.");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (int index = 1; index < lines.length; index++) {
            int separator = lines[index].indexOf('=');
            if (separator < 1) throw new IllegalArgumentException("Invalid connection details field.");
            String key = lines[index].substring(0, separator);
            if (!KEYS.contains(key) || fields.putIfAbsent(key, lines[index].substring(separator + 1)) != null) {
                throw new IllegalArgumentException("Unknown or duplicate connection details field.");
            }
        }
        try {
            return new RemoteConnectionDetails(fields.get("name"), fields.get("host"),
                    Integer.parseInt(fields.get("control")), Integer.parseInt(fields.get("assets")),
                    fields.get("fingerprint"), fields.get("build"));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Connection details contain an invalid port.", invalid);
        }
    }
}
