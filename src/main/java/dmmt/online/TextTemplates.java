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
