package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.LongNode;

import java.util.Set;
import java.util.HashSet;
import java.util.regex.Pattern;

public final class RuleExpression {
    private static final Set<String> ROOTS = Set.of("source", "actor", "recipient", "event", "state", "input", "die", "result", "args", "effects", "gear", "sourcePools");
    private static final Set<String> UNARY = Set.of("not", "floor", "ceil", "negate", "size", "flatten");
    private static final Set<String> BINARY = Set.of("subtract", "divide", "eq", "ne", "gt", "gte", "lt", "lte", "includes", "count", "pluck", "containsText", "at", "effectValue", "indexOf", "split");
    private static final Set<String> MANY = Set.of("add", "multiply", "min", "max", "and", "or");
    private static final Pattern PATH_PART = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_-]*");
    private static final double MAX_NUMBER = 9_007_199_254_740_991d;

    private RuleExpression() {}

    public static void validate(JsonNode expression, String path) {
        RuleJson.checkTree(expression, path, 0, new int[]{0});
        validateNode(expression, path);
    }

    static Set<String> references(JsonNode expression) {
        Set<String> paths = new HashSet<>();
        collectReferences(expression, paths);
        return paths;
    }

    private static void collectReferences(JsonNode node, Set<String> paths) {
        if (node.isObject()) {
            if (node.has("literal")) return;
            if (node.has("read")) paths.add(node.path("read").asText());
            if (node.has("exists")) paths.add(node.path("exists").asText());
        }
        node.elements().forEachRemaining(child -> collectReferences(child, paths));
    }

    private static void validateNode(JsonNode expression, String path) {
        if (expression.isValueNode()) return;
        if (!expression.isObject() || expression.size() != 1)
            throw RuleJson.error(path, "An expression must be a scalar or one operator.");
        String op = expression.fieldNames().next();
        JsonNode arg = expression.get(op);
        if ("literal".equals(op)) return;
        if ("object".equals(op)) {
            if (!arg.isObject() || arg.isEmpty() || arg.size() > 16) throw RuleJson.error(path + "/object", "Expected 1-16 fields.");
            arg.fields().forEachRemaining(field -> {
                RuleJson.id(RuleJson.JSON.getNodeFactory().textNode(field.getKey()), path + "/object");
                if (Set.of("read", "exists", "literal").contains(field.getKey())) throw RuleJson.error(path + "/object", "Reserved field name.");
                validateNode(field.getValue(), path + "/object/" + field.getKey());
            });
            return;
        }
        if ("read".equals(op) || "exists".equals(op)) {
            validateRead(RuleJson.text(arg, path + "/" + op), path);
            return;
        }
        if (UNARY.contains(op)) {
            validateNode(arg, path + "/" + op);
            return;
        }
        int min = BINARY.contains(op) ? 2 : MANY.contains(op) ? 1 : "if".equals(op) || "clamp".equals(op) ? 3 : -1;
        int max = MANY.contains(op) ? 128 : min;
        if (min == -1) throw RuleJson.error(path + "/" + op, "Unknown expression operator.");
        if (!arg.isArray() || arg.size() < min || arg.size() > max)
            throw RuleJson.error(path + "/" + op, "Wrong number of operands.");
        for (int i = 0; i < arg.size(); i++) validateNode(arg.get(i), path + "/" + op + "/" + i);
    }

    static void validateRead(String path, String location) {
        String[] parts = path.split("\\.", -1);
        if (parts.length < 2 || parts.length > 12 || !ROOTS.contains(parts[0]))
            throw RuleJson.error(location, "Unknown context path: " + path);
        for (String part : parts)
            if (!PATH_PART.matcher(part).matches() || RuleJson.reserved(part))
                throw RuleJson.error(location, "Invalid context path: " + path);
    }

    public static JsonNode evaluate(JsonNode expression, JsonNode context) {
        validate(expression, "/expression");
        return evaluateNode(expression, context, "/expression");
    }

    private static JsonNode evaluateNode(JsonNode expression, JsonNode context, String path) {
        if (expression.isValueNode()) return expression.deepCopy();
        String op = expression.fieldNames().next();
        JsonNode arg = expression.get(op);
        if ("literal".equals(op)) return arg.deepCopy();
        if ("object".equals(op)) {
            com.fasterxml.jackson.databind.node.ObjectNode built = RuleJson.JSON.createObjectNode();
            arg.fields().forEachRemaining(field -> built.set(field.getKey(), evaluateNode(field.getValue(), context, path)));
            return built;
        }
        if ("read".equals(op)) return read(context, arg.textValue()).deepCopy();
        if ("exists".equals(op)) return BooleanNode.valueOf(find(context, arg.textValue()) != null);
        if ("if".equals(op))
            return evaluateNode(arg.get(bool(evaluateNode(arg.get(0), context, path), path) ? 1 : 2), context, path);
        if ("and".equals(op) || "or".equals(op)) {
            boolean all = "and".equals(op);
            for (JsonNode part : arg)
                if (bool(evaluateNode(part, context, path), path) != all) return BooleanNode.valueOf(!all);
            return BooleanNode.valueOf(all);
        }
        if (UNARY.contains(op)) {
            JsonNode value = evaluateNode(arg, context, path);
            return switch (op) {
                case "not" -> BooleanNode.valueOf(!bool(value, path));
                case "floor" -> numeric(Math.floor(number(value, path)), path);
                case "ceil" -> numeric(Math.ceil(number(value, path)), path);
                case "negate" -> numeric(-number(value, path), path);
                case "size" -> {
                    if (!value.isArray()) throw RuleJson.error(path, "size requires a collection.");
                    yield LongNode.valueOf(value.size());
                }
                case "flatten" -> {
                    if (!value.isArray()) throw RuleJson.error(path, "flatten requires a collection.");
                    com.fasterxml.jackson.databind.node.ArrayNode flat = RuleJson.JSON.createArrayNode();
                    for (JsonNode element : value) if (element.isArray()) flat.addAll((com.fasterxml.jackson.databind.node.ArrayNode) element); else flat.add(element);
                    yield flat;
                }
                default -> throw RuleJson.error(path, "Unknown expression operator.");
            };
        }
        JsonNode[] values = new JsonNode[arg.size()];
        for (int i = 0; i < arg.size(); i++) values[i] = evaluateNode(arg.get(i), context, path);
        if ("eq".equals(op) || "ne".equals(op)) {
            boolean equal = equal(values[0], values[1]);
            return BooleanNode.valueOf("eq".equals(op) == equal);
        }
        if ("pluck".equals(op)) {
            if (!values[0].isArray() || !values[1].isTextual()) throw RuleJson.error(path, "pluck requires a collection and a field name.");
            com.fasterxml.jackson.databind.node.ArrayNode plucked = RuleJson.JSON.createArrayNode();
            for (JsonNode element : values[0]) if (element.has(values[1].asText())) plucked.add(element.get(values[1].asText()));
            return plucked;
        }
        if ("effectValue".equals(op)) {
            if (!values[0].isArray() || !values[1].isTextual()) throw RuleJson.error(path, "effectValue requires an effect collection and a label.");
            for (JsonNode element : values[0])
                if (values[1].asText().equals(element.path("label").asText()) && element.path("value").isIntegralNumber())
                    return numeric(element.path("value").longValue(), path);
            return numeric(0, path);
        }
        if ("at".equals(op)) {
            if (!values[0].isArray()) throw RuleJson.error(path, "at requires a collection and an index.");
            int index = (int) number(values[1], path);
            if (index < 0 || index >= values[0].size() || index != number(values[1], path))
                throw RuleJson.error(path, "at index is outside the collection.");
            return values[0].get(index).deepCopy();
        }
        if ("includes".equals(op)) {
            if (!values[0].isArray()) throw RuleJson.error(path, "includes requires a collection.");
            boolean found = false;
            for (JsonNode element : values[0]) if (equal(element, values[1])) found = true;
            return BooleanNode.valueOf(found);
        }
        if ("indexOf".equals(op)) {
            if (!values[0].isArray()) throw RuleJson.error(path, "indexOf requires a collection.");
            for (int i = 0; i < values[0].size(); i++) {
                JsonNode element = values[0].get(i);
                if (element.isTextual() && values[1].isTextual() ? element.asText().equalsIgnoreCase(values[1].asText()) : equal(element, values[1]))
                    return numeric(i, path);
            }
            return numeric(-1, path);
        }
        if ("count".equals(op)) {
            if (!values[0].isArray()) throw RuleJson.error(path, "count requires a collection.");
            int found = 0;
            for (JsonNode element : values[0]) if (equal(element, values[1])) found++;
            return numeric(found, path);
        }
        if ("split".equals(op)) {
            if (!values[0].isTextual() || !values[1].isTextual() || values[1].asText().isEmpty())
                throw RuleJson.error(path, "split requires a text and a non-empty separator.");
            var parts = RuleJson.JSON.createArrayNode();
            for (String part : values[0].asText().split(java.util.regex.Pattern.quote(values[1].asText()), -1)) parts.add(part);
            if (parts.size() > 64) throw RuleJson.error(path, "split produced too many parts.");
            return parts;
        }
        if ("containsText".equals(op)) {
            if (!values[0].isTextual() || !values[1].isTextual()) throw RuleJson.error(path, "containsText requires two texts.");
            return BooleanNode.valueOf(values[0].asText().toLowerCase(java.util.Locale.ROOT)
                    .contains(values[1].asText().toLowerCase(java.util.Locale.ROOT)));
        }
        double[] numbers = new double[values.length];
        for (int i = 0; i < values.length; i++) numbers[i] = number(values[i], path);
        double a = numbers[0], b = numbers.length > 1 ? numbers[1] : 0;
        return switch (op) {
            case "gt" -> BooleanNode.valueOf(a > b);
            case "gte" -> BooleanNode.valueOf(a >= b);
            case "lt" -> BooleanNode.valueOf(a < b);
            case "lte" -> BooleanNode.valueOf(a <= b);
            case "subtract" -> numeric(a - b, path);
            case "divide" -> {
                if (b == 0) throw RuleJson.error(path, "Division by zero.");
                yield numeric(a / b, path);
            }
            case "clamp" -> {
                if (b > numbers[2]) throw RuleJson.error(path, "Minimum exceeds maximum.");
                yield numeric(Math.max(b, Math.min(numbers[2], a)), path);
            }
            default -> {
                double total = a;
                for (int i = 1; i < numbers.length; i++) {
                    total = switch (op) {
                        case "add" -> total + numbers[i];
                        case "multiply" -> total * numbers[i];
                        case "min" -> Math.min(total, numbers[i]);
                        case "max" -> Math.max(total, numbers[i]);
                        default -> throw RuleJson.error(path, "Unknown expression operator.");
                    };
                    numeric(total, path);
                }
                yield numeric(total, path);
            }
        };
    }

    public static JsonNode read(JsonNode context, String path) {
        validateRead(path, "/read");
        JsonNode result = find(context, path);
        if (result == null) throw RuleJson.error("/read/" + path, "Context value is unavailable.");
        return result;
    }

    private static JsonNode find(JsonNode context, String path) {
        JsonNode current = context;
        for (String part : path.split("\\.")) {
            if (current == null || !current.isObject()) return null;
            current = current.get(part);
        }
        return current;
    }

    static boolean bool(JsonNode value, String path) {
        if (value == null || !value.isBoolean()) throw RuleJson.error(path, "Expected a boolean.");
        return value.booleanValue();
    }

    static double number(JsonNode value, CharSequence path) {
        if (value == null || !value.isNumber()) throw RuleJson.error(path.toString(), "Expected a number.");
        double result = value.doubleValue();
        if (!Double.isFinite(result) || Math.abs(result) > MAX_NUMBER)
            throw RuleJson.error(path.toString(), "Number exceeds the safe numeric range.");
        return result;
    }

    static JsonNode numeric(double value, String path) {
        if (!Double.isFinite(value) || Math.abs(value) > MAX_NUMBER)
            throw RuleJson.error(path, "Number exceeds the safe numeric range.");
        return value == Math.rint(value) ? LongNode.valueOf((long) value) : DoubleNode.valueOf(value);
    }

    static boolean equal(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) return a.doubleValue() == b.doubleValue();
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) if (!equal(a.get(i), b.get(i))) return false;
            return true;
        }
        if (a.isObject() && b.isObject()) {
            if (a.size() != b.size()) return false;
            var fields = a.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!b.has(field.getKey()) || !equal(field.getValue(), b.get(field.getKey()))) return false;
            }
            return true;
        }
        return a.equals(b);
    }
}
