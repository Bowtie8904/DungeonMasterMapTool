package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

/** Reads only the publicly shared character, never account or entitlement data from the page. */
final class DemiplaneImport {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_BYTES = 20 * 1024 * 1024;
    private static final int MAX_PORTRAIT_BYTES = 8 * 1024 * 1024;
    private static final String AVATAR_HOST = "demiplane-prod-app-avatar.s3.us-west-2.amazonaws.com";
    private static final Pattern PATH = Pattern.compile(
            "/nexus/daggerheart/character-sheet/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/?");
    private static final Pattern AVATAR_PATH = Pattern.compile("/[A-Za-z0-9_-]+\\.(?:png|jpe?g|webp|gif)", Pattern.CASE_INSENSITIVE);
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
            Parsed parsed = parseCharacter(new String(bytes, StandardCharsets.UTF_8), id);
            if (parsed.avatarUrl() == null) return parsed.character();
            try {
                Portrait portrait = fetchPortrait(parsed.avatarUrl());
                parsed.character().put("portrait", "data:" + portrait.mimeType() + ";base64,"
                        + Base64.getEncoder().encodeToString(portrait.bytes()));
            } catch (IOException e) {
                parsed.character().put("portraitIssue", "The Demiplane portrait could not be downloaded. The character was imported without changing its current artwork.");
            }
            return parsed.character();
        } finally { connection.disconnect(); }
    }

    static ObjectNode parse(String html, String id) throws IOException {
        return parseCharacter(html, id).character();
    }

    static URI avatarUri(String address) {
        URI uri;
        try { uri = URI.create(address); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid Demiplane avatar URL."); }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !AVATAR_HOST.equalsIgnoreCase(uri.getHost())
                || uri.getRawUserInfo() != null || uri.getPort() != -1 || uri.getRawQuery() != null
                || uri.getRawFragment() != null || !AVATAR_PATH.matcher(uri.getRawPath()).matches()) {
            throw new IllegalArgumentException("The Demiplane portrait URL is not from the supported public avatar host.");
        }
        return uri;
    }

    private static Portrait fetchPortrait(String address) throws IOException {
        URI uri;
        try { uri = avatarUri(address); }
        catch (IllegalArgumentException e) { throw new IOException(e.getMessage(), e); }
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(10_000);
        connection.setRequestProperty("Accept", "image/png,image/jpeg,image/webp,image/gif");
        try {
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("Avatar host returned HTTP " + status + ".");
            if (connection.getContentLengthLong() > MAX_PORTRAIT_BYTES) throw new IOException("Portrait exceeds the 8 MB size limit.");
            byte[] bytes;
            try (var input = connection.getInputStream(); var output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > MAX_PORTRAIT_BYTES) throw new IOException("Portrait exceeds the 8 MB size limit.");
                    output.write(buffer, 0, count);
                }
                bytes = output.toByteArray();
            }
            String mimeType = imageMimeType(bytes);
            if (mimeType == null) throw new IOException("Demiplane returned an unsupported portrait image.");
            return new Portrait(bytes, mimeType);
        } finally { connection.disconnect(); }
    }

    private static String imageMimeType(byte[] bytes) {
        if (bytes.length >= 8 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
                && bytes[4] == 0x0d && bytes[5] == 0x0a && bytes[6] == 0x1a && bytes[7] == 0x0a) return "image/png";
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff) return "image/jpeg";
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') return "image/webp";
        if (bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F'
                && bytes[3] == '8' && (bytes[4] == '7' || bytes[4] == '9') && bytes[5] == 'a') return "image/gif";
        return null;
    }

    private static Parsed parseCharacter(String html, String id) throws IOException {
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
                String avatarUrl = character.path("avatar_url").asText(null);
                var output = result.putArray("engines");
                for (JsonNode engine : engines) {
                    if (!engine.path("name").isTextual() || !engine.path("args").isObject())
                        throw new IOException("Demiplane returned an unsupported character selection format.");
                    String engineName = engine.path("name").asText();
                    ObjectNode entry = output.addObject().put("name", engineName)
                            .put("key", engine.path("demiplaneEngineId").asText(""));
                    if (avatarUrl == null && "character_avatar".equals(engineName) && engine.path("value").isTextual())
                        avatarUrl = engine.path("value").asText();
                    ObjectNode args = engine.path("args").deepCopy();
                    args.remove("addEngineStatus");
                    entry.set("args", args);
                    if (engine.has("value") && !"character_avatar".equals(engineName)) entry.set("value", engine.get("value"));
                }
                if (MAPPER.writeValueAsBytes(result).length > OnlineSessionServer.MAX_SHEET_BYTES)
                    throw new IOException("The character build is too large to import.");
                return new Parsed(result, avatarUrl);
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

    private record Parsed(ObjectNode character, String avatarUrl) {}
    private record Portrait(byte[] bytes, String mimeType) {}
}
