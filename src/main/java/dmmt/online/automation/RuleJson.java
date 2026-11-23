package dmmt.online.automation;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

final class RuleJson {
    static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    static final int MAX_NODES = 16_384;
    static final int MAX_DEPTH = 32;
    static final int MAX_DOCUMENT_CHARACTERS = 16_000_000;
    static final int MAX_DOCUMENT_NODES = 1_000_000;
    private static final Set<String> RESERVED_FIELDS = Set.of("__proto__", "constructor", "prototype");
    private static final Pattern ID = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.:-]{0,127}");

    private RuleJson() {}

    static JsonNode parse(String text) throws IOException {
        return parse(text, 2_000_000, MAX_NODES);
    }

    static JsonNode parse(String text, int maxCharacters, int maxNodes) throws IOException {
        if (text.length() > maxCharacters) throw error("/", "Rule document is too large.");
        JsonNode node = JSON.readTree(text);
        checkTree(node, "/", 0, new int[]{0}, maxNodes);
        return node;
    }

    static void checkTree(JsonNode node, String path, int depth, int[] count) {
        checkTree(node, path, depth, count, MAX_NODES);
    }

    static void checkTree(JsonNode node, String path, int depth, int[] count, int maxNodes) {
        checkTree(node, new StringBuilder(path), depth, count, maxNodes);
    }

    private static void checkTree(JsonNode node, StringBuilder path, int depth, int[] count, int maxNodes) {
        if (node == null || node.isMissingNode()) throw error(path.toString(), "Missing JSON value.");
        if (depth > MAX_DEPTH || ++count[0] > maxNodes)
            throw error(path.toString(), "Rule data exceeds its complexity limit.");
        if (node.isNumber()) RuleExpression.number(node, path);
        if (node.isTextual() && node.textValue().length() > 16_000) throw error(path.toString(), "Text is too long.");
        int length = path.length();
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                path.append('/').append(field.getKey());
                if (reserved(field.getKey())) throw error(path.toString(), "Reserved field name.");
                checkTree(field.getValue(), path, depth + 1, count, maxNodes);
                path.setLength(length);
            }
        }
        if (node.isArray()) for (int i = 0; i < node.size(); i++) {
            path.append('/').append(i);
            checkTree(node.get(i), path, depth + 1, count, maxNodes);
            path.setLength(length);
        }
    }

    static boolean reserved(String name) {
        return RESERVED_FIELDS.contains(name);
    }

    static ObjectNode object(JsonNode node, String path) {
        if (!(node instanceof ObjectNode object)) throw error(path, "Expected an object.");
        return object;
    }

    static void fields(JsonNode node, String path, String... allowed) {
        object(node, path);
        Set<String> names = new HashSet<>(Arrays.asList(allowed));
        node.fieldNames().forEachRemaining(name -> {
            if (!names.contains(name)) throw error(path + "/" + name, "Unknown field.");
        });
    }

    static String text(JsonNode node, String path) {
        if (node == null || !node.isTextual() || node.textValue().isBlank())
            throw error(path, "Expected nonempty text.");
        return node.textValue();
    }

    static String id(JsonNode node, String path) {
        String id = text(node, path);
        if (!ID.matcher(id).matches()) throw error(path, "Invalid stable ID.");
        return id;
    }

    static int integer(JsonNode node, String path, int min, int max) {
        double value = RuleExpression.number(node, path);
        if (value != Math.rint(value) || value < min || value > max)
            throw error(path, "Expected a whole number between " + min + " and " + max + ".");
        return (int) value;
    }

    static IllegalArgumentException error(String path, String message) {
        return new IllegalArgumentException(path + ": " + message);
    }
}
