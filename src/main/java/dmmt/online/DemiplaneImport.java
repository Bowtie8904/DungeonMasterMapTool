package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Reads only the publicly shared character, never account or entitlement data from the page. */
final class DemiplaneImport {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_BYTES = 20 * 1024 * 1024;
    private static final Pattern PATH = Pattern.compile(
            "/nexus/daggerheart/character-sheet/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/?");
    private static final Pattern FLIGHT = Pattern.compile(
            "self\\.__next_f\\.push\\((\\[.*?\\])\\)</script>", Pattern.DOTALL);

    static URI characterUri(String link) {
        URI uri;
        try { uri = URI.create(link.trim()); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Enter a valid Demiplane character link."); }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !"app.demiplane.com".equalsIgnoreCase(uri.getHost())
                || uri.getRawUserInfo() != null || uri.getPort() != -1 || !PATH.matcher(uri.getRawPath()).matches()) {
            throw new IllegalArgumentException("Use https://app.demiplane.com/nexus/daggerheart/character-sheet/<character-id>.");
        }
        return URI.create("https://app.demiplane.com" + uri.getRawPath().replaceAll("/$", ""));
    }

    static ObjectNode fetch(String link) throws IOException {
        URI uri = characterUri(link);
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(15_000);
        connection.setRequestProperty("Accept", "text/html");
        try {
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("Demiplane returned HTTP " + status
                    + ". Check that the character is publicly shared and the link opens without signing in.");
            if (connection.getContentLengthLong() > MAX_BYTES) throw new IOException("The Demiplane page is too large to import.");
            byte[] bytes;
            try (var input = connection.getInputStream()) { bytes = input.readNBytes(MAX_BYTES + 1); }
            if (bytes.length > MAX_BYTES) throw new IOException("The Demiplane page is too large to import.");
            String id = uri.getPath().substring(uri.getPath().lastIndexOf('/') + 1);
            return parse(new String(bytes, StandardCharsets.UTF_8), id);
        } finally { connection.disconnect(); }
    }

    static ObjectNode parse(String html, String id) throws IOException {
        StringBuilder flight = new StringBuilder();
        var matches = FLIGHT.matcher(html);
        while (matches.find()) {
            JsonNode chunk = MAPPER.readTree(matches.group(1));
            if (chunk.isArray() && chunk.size() > 1 && chunk.get(0).asInt(-1) == 1 && chunk.get(1).isTextual())
                flight.append(chunk.get(1).textValue());
        }
        for (String line : flight.toString().split("\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String value = line.substring(colon + 1).trim();
            if (!value.startsWith("{") && !value.startsWith("[")) continue;
            JsonNode row;
            try { row = MAPPER.readTree(value); }
            catch (com.fasterxml.jackson.core.JsonProcessingException e) { continue; } // Flight also contains non-JSON records.
            JsonNode character = findCharacter(row, id);
            if (character != null) {
                JsonNode engines = character.path("data").path("engines");
                if (!engines.isArray() || engines.isEmpty() || engines.size() > 5000)
                    throw new IOException("The shared character has no supported saved build data.");
                ObjectNode result = MAPPER.createObjectNode();
                result.put("name", character.path("name").asText(""));
                result.put("level", character.path("level").asInt());
                JsonNode format = character.path("formated_data").path("format");
                result.putObject("summary").put("class", format.path("class").asText(""))
                        .put("subclass", format.path("subclass").asText(""));
                var output = result.putArray("engines");
                for (JsonNode engine : engines) {
                    if (!engine.path("name").isTextual() || !engine.path("args").isObject())
                        throw new IOException("Demiplane returned an unsupported character selection format.");
                    ObjectNode entry = output.addObject().put("name", engine.path("name").asText())
                            .put("key", engine.path("demiplaneEngineId").asText(""));
                    ObjectNode args = engine.path("args").deepCopy();
                    args.remove("addEngineStatus");
                    entry.set("args", args);
                    if (engine.has("value")) entry.set("value", engine.get("value"));
                }
                if (MAPPER.writeValueAsBytes(result).length > OnlineSessionServer.MAX_SHEET_BYTES)
                    throw new IOException("The character build is too large to import.");
                return result;
            }
        }
        throw new IOException("No public character data was found. Enable public viewing in Demiplane and check the link. "
                + "Demiplane may also have changed its page format.");
    }

    private static JsonNode findCharacter(JsonNode node, String id) {
        if (node.isObject() && id.equalsIgnoreCase(node.path("uuid").asText())
                && node.path("data").has("engines")) return node;
        if (node.isContainerNode()) for (JsonNode child : node) {
            JsonNode result = findCharacter(child, id);
            if (result != null) return result;
        }
        return null;
    }
}
