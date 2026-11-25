package dmmt.online;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Configurable catalog texts; mirrors web/src/daggerheart/texts.ts (double braces are literal, unknown placeholders stay). */
public final class TextTemplates {
    private static final Pattern TOKEN = Pattern.compile("\\{\\{|\\}\\}|\\{([A-Za-z_][A-Za-z0-9_]*)\\}");

    private TextTemplates() {
    }

    public static String format(String template, Map<String, ?> vars) {
        if (template == null) {
            return "";
        }
        Matcher matcher = TOKEN.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String token = matcher.group();
            String replacement;
            if (token.equals("{{")) {
                replacement = "{";
            } else if (token.equals("}}")) {
                replacement = "}";
            } else {
                Object value = vars == null ? null : vars.get(matcher.group(1));
                replacement = value == null ? token : String.valueOf(value);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    public static List<String> placeholders(String template) {
        List<String> names = new ArrayList<>();
        if (template != null) {
            Matcher matcher = TOKEN.matcher(template);
            while (matcher.find()) {
                if (matcher.group(1) != null) {
                    names.add(matcher.group(1));
                }
            }
        }
        return names;
    }

    private static final List<String> FIELDS = List.of("button", "blocked", "chatButton", "roll", "reportTitle", "report", "option", "optionNote", "applied");
    private static final List<String> NAMES = List.of("actor", "target", "source", "origin", "label", "amount", "dice", "result",
            "trait", "traitValue", "effect", "item", "qty", "uses", "usesMax", "effects");

    /** Diagnostics for every {@code texts} object in a catalog document (mirrors validateCatalogTexts in texts.ts). */
    public static List<String> diagnostics(String file, com.fasterxml.jackson.databind.JsonNode node) {
        List<String> problems = new ArrayList<>();
        collectDiagnostics(file, node, "", problems);
        return problems;
    }

    private static void collectDiagnostics(String file, com.fasterxml.jackson.databind.JsonNode node, String path, List<String> problems) {
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) collectDiagnostics(file, node.get(i), path + "[" + i + "]", problems);
        } else if (node.isObject()) {
            node.fields().forEachRemaining(field -> {
                String childPath = path.isEmpty() ? field.getKey() : path + "." + field.getKey();
                if (field.getKey().equals("texts")) validateTexts(file + " " + path + ".texts", field.getValue(), problems);
                else collectDiagnostics(file, field.getValue(), childPath, problems);
            });
        }
    }

    private static void validateTexts(String where, com.fasterxml.jackson.databind.JsonNode texts, List<String> problems) {
        if (!texts.isObject()) {
            problems.add(where + ": texts must be an object");
            return;
        }
        texts.fields().forEachRemaining(field -> {
            String name = field.getKey();
            com.fasterxml.jackson.databind.JsonNode value = field.getValue();
            if (!FIELDS.contains(name)) {
                problems.add(where + ": unknown texts field \"" + name + "\"");
                return;
            }
            List<String> templates = new ArrayList<>();
            boolean report = name.equals("report");
            if (report && value.isArray() && java.util.stream.StreamSupport.stream(value.spliterator(), false).allMatch(v -> v.isTextual())) {
                value.forEach(v -> templates.add(v.asText()));
            } else if (!report && value.isTextual()) {
                templates.add(value.asText());
            } else {
                problems.add(where + ": texts." + name + " must be " + (report ? "an array of strings" : "a string"));
                return;
            }
            for (String template : templates) {
                for (String placeholder : placeholders(template)) {
                    if (!NAMES.contains(placeholder) || placeholder.equals("effects") && !report) {
                        problems.add(where + ": texts." + name + " uses unknown placeholder {" + placeholder + "}");
                    }
                }
            }
        });
    }

    /**
     * Applies a configured report: "spent &lt;cost&gt;" stays first, a line equal to {@code {effects}} inserts the generated lines, empty
     * results are dropped. Without configured lines the generated lines are returned unchanged.
     */
    public static List<String> applyReport(List<String> configured, List<String> generated, Map<String, ?> vars, String costLine) {
        if (configured == null || configured.isEmpty()) {
            return generated;
        }
        boolean keepCost = costLine != null && !generated.isEmpty() && generated.get(0).equals(costLine);
        List<String> rest = keepCost ? generated.subList(1, generated.size()) : generated;
        List<String> result = new ArrayList<>();
        if (keepCost) {
            result.add(costLine);
        }
        for (String line : configured) {
            if (line.trim().equals("{effects}")) {
                result.addAll(rest);
            } else {
                String text = format(line, vars);
                if (!text.isBlank()) {
                    result.add(text);
                }
            }
        }
        return result;
    }
}
