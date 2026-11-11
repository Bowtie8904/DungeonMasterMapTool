package dmmt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dmmt.model.DaggerheartAdversary;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DaggerheartAdversaryClipboard {
    public static final String FORMAT = "daggerheart-companion/adversary";
    private static final int MAX_JSON_LENGTH = 1_000_000;
    private static final int MAX_FEATURES = 100;
    private static final int MAX_TEXT_LENGTH = 20_000;
    private static final int MAX_IMAGE_BYTES = 50 * 1024 * 1024;
    private static final HttpClient IMAGE_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public record Import(DaggerheartAdversary adversary, String tokenName, String tokenColor, String imageUrl) {
    }

    private final ObjectMapper mapper = JsonMappers.create();

    public Import parse(String json) {
        if (json == null || json.length() > MAX_JSON_LENGTH) {
            throw new IllegalArgumentException("The adversary clipboard data is empty or too large.");
        }
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null || !FORMAT.equals(text(root, "format"))
                    || root.path("version").asInt(-1) != 1
                    || !"daggerheart".equals(text(root, "system"))) {
                throw new IllegalArgumentException("Clipboard data is not a supported Daggerheart adversary export.");
            }
            JsonNode data = root.path("adversary");
            if (!data.isObject()) {
                throw new IllegalArgumentException("The adversary export is missing its adversary data.");
            }
            String name = requiredText(data, "name");
            int tier = boundedInt(data, "tier", 1, 4);
            int difficulty = boundedInt(data, "difficulty", 0, 100);
            int maxHp = boundedInt(data, "maxHp", 0, 100);
            int maxStress = boundedInt(data, "maxStress", 0, 100);
            List<DaggerheartAdversary.Feature> features = features(data.path("features"));
            DaggerheartAdversary adversary = DaggerheartAdversary.builder()
                    .sourceId(optionalText(data, "sourceId"))
                    .name(name)
                    .tier(tier)
                    .category(optionalText(data, "category"))
                    .categoryDisplayText(optionalText(data, "categoryDisplayText"))
                    .description(optionalText(data, "description"))
                    .motivesAndTactics(optionalText(data, "motivesAndTactics"))
                    .difficulty(difficulty)
                    .damageThresholds(optionalText(data, "damageThresholds"))
                    .maxHp(maxHp)
                    .maxStress(maxStress)
                    .attackModifier(optionalText(data, "attackModifier"))
                    .defaultAttackName(optionalText(data, "defaultAttackName"))
                    .defaultAttackRange(optionalText(data, "defaultAttackRange"))
                    .defaultAttackDamage(optionalText(data, "defaultAttackDamage"))
                    .experiences(stringArray(data.path("experiences"), "experiences"))
                    .features(features)
                    .tags(stringArray(data.path("tags"), "tags"))
                    .build();
            JsonNode token = root.path("token");
            String color = optionalText(token, "color");
            if (color != null && !color.matches("#[0-9a-fA-F]{6}(?:[0-9a-fA-F]{2})?")) {
                throw new IllegalArgumentException("The adversary export has an unsupported token color.");
            }
            return new Import(adversary, token.path("name").isTextual() ? bounded(token.path("name").asText()) : name,
                    color, optionalText(token, "imageUrl"));
        } catch (IOException exception) {
            throw new IllegalArgumentException("The adversary clipboard data is not valid JSON.", exception);
        }
    }

    public static String downloadLocalImage(String imageUrl) throws IOException, InterruptedException {
        URI uri = parseLocalImageUri(imageUrl);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<InputStream> response = IMAGE_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("The adversary image server returned HTTP " + response.statusCode() + ".");
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("")
                    .split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (!"image/png".equals(contentType)) {
                throw new IOException("The adversary image is not a PNG.");
            }
            byte[] image = body.readNBytes(MAX_IMAGE_BYTES + 1);
            if (image.length > MAX_IMAGE_BYTES) {
                throw new IOException("The adversary image is larger than 5 MB.");
            }
            return TokenAssets.store(image);
        }
    }

    static URI parseLocalImageUri(String imageUrl) {
        try {
            URI uri = URI.create(imageUrl);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || host == null || uri.getUserInfo() != null || uri.getFragment() != null
                    || !(host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("::1"))) {
                throw new IllegalArgumentException("Adversary artwork must be served from this computer.");
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("The adversary image URL is invalid or is not local.", exception);
        }
    }

    private static List<DaggerheartAdversary.Feature> features(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return List.of();
        if (!node.isArray() || node.size() > MAX_FEATURES) {
            throw new IllegalArgumentException("The adversary export contains an invalid feature list.");
        }
        List<DaggerheartAdversary.Feature> result = new ArrayList<>();
        for (JsonNode feature : node) {
            if (!feature.isObject()) {
                throw new IllegalArgumentException("The adversary export contains an invalid feature.");
            }
            result.add(DaggerheartAdversary.Feature.builder()
                    .name(optionalText(feature, "name"))
                    .classification(optionalText(feature, "classification"))
                    .description(optionalText(feature, "description"))
                    .build());
        }
        return result;
    }

    private static List<String> stringArray(JsonNode node, String label) {
        if (node.isMissingNode() || node.isNull()) return List.of();
        if (!node.isArray() || node.size() > MAX_FEATURES) {
            throw new IllegalArgumentException("The adversary export contains an invalid " + label + " list.");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isTextual()) {
                throw new IllegalArgumentException("The adversary export contains a non-text " + label + " entry.");
            }
            values.add(bounded(value.asText()));
        }
        return values;
    }

    private static int boundedInt(JsonNode node, String field, int min, int max) {
        JsonNode value = node.path(field);
        if (!value.canConvertToInt() || value.asInt() < min || value.asInt() > max) {
            throw new IllegalArgumentException("The adversary export has an invalid " + field + " value.");
        }
        return value.asInt();
    }

    private static String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("The adversary export is missing " + field + ".");
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isTextual()) {
            throw new IllegalArgumentException("The adversary export has an invalid " + field + " value.");
        }
        return bounded(value.asText());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    private static String bounded(String value) {
        if (value.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("The adversary export contains a text field that is too long.");
        }
        return value;
    }
}
