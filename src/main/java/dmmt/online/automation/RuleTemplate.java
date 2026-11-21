package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class RuleTemplate {
    private record Part(String text, String path, String format) {}

    private RuleTemplate() {}

    public static void validate(String text) {
        parse(text);
    }

    static Set<String> references(String text) {
        return parse(text).stream().filter(part -> part.path() != null).map(Part::path).collect(Collectors.toSet());
    }

    public static String render(String text, JsonNode context) {
        StringBuilder out = new StringBuilder();
        for (Part part : parse(text)) {
            if (part.path() == null) out.append(part.text());
            else {
                JsonNode value = RuleExpression.read(context, part.path());
                String format = part.format();
                if (format.startsWith("plural:")) {
                    String[] words = format.split(":", -1);
                    out.append(RuleExpression.number(value, "/template") == 1 ? words[1] : words[2]);
                } else if ("list".equals(format)) {
                    if (!value.isArray()) throw RuleJson.error("/template", "The list formatter requires a collection.");
                    List<String> items = new ArrayList<>();
                    for (JsonNode item : value) {
                        if (!item.isValueNode()) throw RuleJson.error("/template", "The list formatter requires scalar items.");
                        items.add(item.isNumber() ? BigDecimal.valueOf(item.asDouble()).stripTrailingZeros().toPlainString() : item.asText());
                    }
                    out.append(String.join(", ", items));
                } else if ("signed".equals(format) || "number".equals(format) || value.isNumber()) {
                    double n = RuleExpression.number(value, "/template");
                    if ("signed".equals(format) && n > 0) out.append('+');
                    out.append(BigDecimal.valueOf(n).stripTrailingZeros().toPlainString());
                } else {
                    if (!value.isTextual() && !value.isBoolean()) throw RuleJson.error("/template", "Token requires a scalar value.");
                    out.append(value.asText());
                }
            }
            if (out.length() > 16_000) throw RuleJson.error("/template", "Rendered text is too long.");
        }
        return out.toString();
    }

    private static List<Part> parse(String text) {
        if (text == null || text.length() > 8_000) throw RuleJson.error("/template", "Template is too long or missing.");
        List<Part> parts = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < text.length();) {
            char c = text.charAt(i);
            if ((c == '{' || c == '}') && i + 1 < text.length() && text.charAt(i + 1) == c) {
                literal.append(c);
                i += 2;
            } else if (c == '{') {
                parts.add(new Part(literal.toString(), null, ""));
                literal.setLength(0);
                int end = text.indexOf('}', i + 1);
                if (end < 0) throw RuleJson.error("/template", "Unclosed placeholder.");
                String token = text.substring(i + 1, end);
                String[] fields = token.split("\\|", -1);
                if (fields.length > 2) throw RuleJson.error("/template", "Invalid placeholder.");
                RuleExpression.validateRead(fields[0], "/template");
                String format = fields.length == 2 ? fields[1] : "";
                if (fields.length == 2 && !format.equals("signed") && !format.equals("number") && !format.equals("list")
                        && !format.matches("plural:[^:{}|]+:[^:{}|]+"))
                    throw RuleJson.error("/template", "Unknown formatter: " + format);
                parts.add(new Part("", fields[0], format));
                i = end + 1;
            } else if (c == '}') throw RuleJson.error("/template", "Unmatched closing brace.");
            else {
                literal.append(c);
                i++;
            }
        }
        parts.add(new Part(literal.toString(), null, ""));
        return parts;
    }
}
