package dmmt.online.automation;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Generates the published JSON Schemas (draft 2020-12) for format 2 from the compiler's own vocabulary so they cannot
 * drift: {@code catalog} (entries with automation), {@code recipes} and {@code ruleset}. The schemas describe structure
 * and tagged unions (rule, binding, input, operation, expression); the compiler additionally links references.
 * Run {@link #main} with an output directory to regenerate the committed files.
 */
public final class RuleSchema {
    public static final String RESOURCE_DIRECTORY = "/dmmt/online/automation/schema/";
    public static final List<String> NAMES = List.of("catalog", "recipes", "ruleset");
    private static final String DRAFT = "https://json-schema.org/draft/2020-12/schema";
    private static final String ID = "^[a-zA-Z0-9][a-zA-Z0-9_.:-]{0,127}$";

    private final boolean recipe;
    private final ObjectNode defs = RuleJson.JSON.createObjectNode();

    private RuleSchema(boolean recipe) {
        this.recipe = recipe;
        define();
    }

    public static String generate(String name) {
        ObjectNode schema = RuleJson.JSON.createObjectNode();
        RuleSchema parts = new RuleSchema("recipes".equals(name));
        schema.put("$schema", DRAFT);
        schema.put("$id", "https://github.com/Bowtie8904/DungeonMasterMapTool/schema/" + name + ".schema.json");
        switch (name) {
            case "catalog" -> {
                schema.put("title", "Daggerheart catalog, format 2");
                schema.put("type", "object");
                schema.put("additionalProperties", false);
                schema.putArray("required").add("format").add("version").add("category").add("entries");
                ObjectNode properties = schema.putObject("properties");
                properties.putObject("format").put("const", "dmmap-daggerheart");
                properties.putObject("version").put("const", RuleCompiler.FORMAT_VERSION);
                properties.putObject("category").put("type", "string");
                properties.putObject("label").put("type", "string");
                properties.putObject("extension");
                properties.putObject("entries").put("type", "array").putObject("items").put("$ref", "#/$defs/node");
                ObjectNode node = parts.defs.putObject("node");
                node.putArray("type").add("object").add("array").add("string").add("number").add("boolean").add("null");
                node.putObject("properties").putObject("automation").put("$ref", "#/$defs/automation");
                node.putObject("patternProperties").putObject("^_");
                node.putObject("items").put("$ref", "#/$defs/node");
                node.putObject("additionalProperties").put("$ref", "#/$defs/node");
            }
            case "recipes" -> {
                schema.put("title", "Daggerheart shared recipes, format 2");
                schema.put("type", "object");
                schema.put("additionalProperties", false);
                schema.putArray("required").add("format").add("version").add("recipes");
                ObjectNode properties = schema.putObject("properties");
                properties.putObject("format").put("const", "dmmap-daggerheart-rules");
                properties.putObject("version").put("const", RuleCompiler.FORMAT_VERSION);
                properties.putObject("recipes").put("type", "array").putObject("items").put("$ref", "#/$defs/recipe");
            }
            case "ruleset" -> {
                schema.put("title", "Daggerheart ruleset, format 2");
                schema.put("type", "object");
                schema.put("additionalProperties", false);
                schema.putArray("required").add("format").add("version").add("name");
                ObjectNode properties = schema.putObject("properties");
                properties.putObject("format").put("const", "dmmap-daggerheart-ruleset");
                properties.putObject("version").put("const", RuleCompiler.FORMAT_VERSION);
                properties.putObject("name").put("type", "string").put("minLength", 1);
                properties.putObject("automation").put("$ref", "#/$defs/automation");
                properties.putObject("rest").put("$ref", "#/$defs/rest");
            }
            default -> throw new IllegalArgumentException("Unknown schema: " + name);
        }
        schema.set("$defs", parts.defs);
        return schema.toPrettyString().replace("\r\n", "\n") + "\n";
    }

    public static void main(String[] args) throws IOException {
        Path directory = Path.of(args.length == 0 ? "src/main/resources" + RESOURCE_DIRECTORY : args[0]);
        Files.createDirectories(directory);
        for (String name : NAMES)
            Files.writeString(directory.resolve(name + ".schema.json"), generate(name), StandardCharsets.UTF_8);
    }

    private ObjectNode ref(String name) {
        return RuleJson.JSON.createObjectNode().put("$ref", "#/$defs/" + name);
    }

    private ObjectNode type(String type) {
        return RuleJson.JSON.createObjectNode().put("type", type);
    }

    private ObjectNode strings(Iterable<String> values) {
        ObjectNode node = RuleJson.JSON.createObjectNode();
        ArrayNode list = node.putArray("enum");
        for (String value : new TreeSet<String>((java.util.Collection<String>) values)) list.add(value);
        return node;
    }

    /** In recipes any value may instead be a {@code $arg}/{@code $ui} substitution. */
    private ObjectNode w(ObjectNode node) {
        if (!recipe) return node;
        ObjectNode wrapped = RuleJson.JSON.createObjectNode();
        wrapped.putArray("anyOf").add(node).add(ref("substitution"));
        return wrapped;
    }

    private ObjectNode integer(int min, int max) {
        return type("integer").put("minimum", min).put("maximum", max);
    }

    private ObjectNode object(Map<String, ObjectNode> properties, String... required) {
        ObjectNode node = type("object");
        ObjectNode props = node.putObject("properties");
        properties.forEach(props::set);
        node.put("additionalProperties", false);
        if (required.length > 0) {
            ArrayNode list = node.putArray("required");
            for (String name : required) list.add(name);
        }
        return node;
    }

    private ObjectNode objectOf(ObjectNode values) {
        ObjectNode node = type("object");
        node.set("additionalProperties", values);
        return node;
    }

    private ObjectNode arrayOf(ObjectNode items, int min, int max) {
        ObjectNode node = type("array");
        node.set("items", items);
        if (min > 0) node.put("minItems", min);
        if (max > 0) node.put("maxItems", max);
        return node;
    }

    private Map<String, ObjectNode> p(Object... pairs) {
        Map<String, ObjectNode> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], w((ObjectNode) pairs[i + 1]));
        return map;
    }

    private void define() {
        ObjectNode substitution = type("object");
        substitution.put("minProperties", 1).put("maxProperties", 1);
        substitution.putArray("anyOf").add(RuleJson.JSON.createObjectNode().set("required", RuleJson.JSON.createArrayNode().add("$arg")))
                .add(RuleJson.JSON.createObjectNode().set("required", RuleJson.JSON.createArrayNode().add("$ui")));
        defs.set("substitution", substitution);
        defs.set("id", type("string").put("pattern", ID));
        defs.set("template", type("string").put("minLength", 1));
        defs.set("any", RuleJson.JSON.createObjectNode());
        defs.set("resetEvents", uniqueEnum(RuleCompiler.RESET_EVENTS));

        ObjectNode expression = RuleJson.JSON.createObjectNode();
        ArrayNode alternatives = expression.putArray("anyOf");
        alternatives.add(RuleJson.JSON.createObjectNode().set("type", RuleJson.JSON.createArrayNode().add("string").add("number").add("boolean").add("null")));
        if (recipe) alternatives.add(ref("substitution"));
        alternatives.add(operator("literal", RuleJson.JSON.createObjectNode()));
        alternatives.add(operator("object", objectOf(ref("expression"))));
        alternatives.add(operator("read", type("string")));
        alternatives.add(operator("exists", type("string")));
        for (String op : new TreeSet<>(RuleExpression.UNARY)) alternatives.add(operator(op, ref("expression")));
        for (String op : new TreeSet<>(RuleExpression.BINARY)) alternatives.add(operator(op, arrayOf(ref("expression"), 2, 2).put("minItems", 2)));
        for (String op : new TreeSet<>(RuleExpression.MANY)) alternatives.add(operator(op, arrayOf(ref("expression"), 1, 128)));
        alternatives.add(operator("if", arrayOf(ref("expression"), 3, 3)));
        alternatives.add(operator("clamp", arrayOf(ref("expression"), 3, 3)));
        defs.set("expression", expression);

        defs.set("rest", object(restProperties()));
        defs.set("automation", object(p(
                "state", objectOf(ref("any")),
                "limits", objectOf(ref("limit")),
                "pools", objectOf(ref("pool")),
                "effects", objectOf(ref("effect")),
                "modifiers", arrayOf(ref("modifier"), 0, 64),
                "profiles", arrayOf(ref("profile"), 1, 8),
                "rules", arrayOf(ref("rule"), 1, 256),
                "attachment", ref("any"), "grants", ref("any"), "entitlements", ref("any"), "limitOverrides", ref("any"), "sourceFields", ref("any"),
                "partyGrant", type("boolean"),
                "unarmedWeapon", ref("any"), "provides", ref("any"), "borrowCards", ref("any"))));

        defs.set("limit", object(p("max", integer(1, 1000), "resetOn", ref("resetEvents")), "max", "resetOn"));
        defs.set("pool", object(p("kind", strings(List.of("number", "dice")), "scope", strings(List.of("source", "actor")), "label", ref("template"),
                "max", ref("expression"), "initial", integer(0, 1_000_000), "resetOn", ref("resetEvents"), "resetTo", ref("expression"),
                "section", strings(List.of("feature", "class")), "whileEffect", ref("id")), "kind", "scope", "max"));
        defs.set("effect", object(p("label", ref("template"), "text", ref("template"), "kind", strings(List.of("buff", "debuff")),
                "until", ref("resetEvents"), "stacking", strings(List.of("replace", "replaceAnySource", "stack", "unique")),
                "modifiers", arrayOf(ref("modifier"), 0, 64), "profile", ref("any"), "while", ref("expression"), "endWhen", ref("expression"),
                "exclusiveGroup", ref("id"), "singleHolder", type("boolean"), "locks", objectOf(ref("expression")),
                "restExpiry", strings(List.of("beforeMoves")), "blocksMovement", type("boolean"), "blocksDeathMoves", type("boolean"),
                "onlineCondition", object(p("id", type("string"), "iconId", type("string")), "id"), "value", object(p("initial", ref("expression"), "max", ref("expression")), "initial"),
                "endsWithSource", ref("id"), "endsWithSourceElement", type("boolean"), "captureWeapon", type("boolean"))));
        defs.set("modifier", object(p("stat", type("string"), "op", strings(List.of("add", "set", "atLeast", "atMost")),
                "value", ref("expression"), "when", ref("expression"), "label", ref("template")), "stat", "value"));
        defs.set("profile", object(p("id", ref("id"), "label", ref("template"), "trait", type("string"), "range", strings(List.of("melee", "veryClose", "close", "far", "veryFar")),
                "damage", ref("any"), "rollKind", strings(List.of("action", "reaction", "spellcast")), "difficulty", integer(0, 1_000_000),
                "origin", type("string"), "limitRef", ref("id"), "consumeOn", strings(List.of("attempt", "success"))), "id", "label"));

        defs.set("ui", objectOf(w(ref("template"))));
        defs.set("binding", object(p("id", ref("id"), "event", strings(RuleCompiler.EVENTS), "surface", strings(RuleCompiler.SURFACES),
                "mode", strings(RuleCompiler.MODES), "when", ref("expression"), "group", strings(List.of("result", "sourceFeature", "otherFeatures")),
                "defaultOn", ref("expression"), "forEach", ref("any"), "exclusiveGroup", ref("id"), "priority", integer(-1000, 1000),
                "claimGroup", ref("id"), "claimGroupScope", strings(List.of("source", "result")),
                "claimScope", object(p("recipients", strings(RuleCompiler.CLAIM_RECIPIENTS), "excludeSource", type("boolean")), "recipients"),
                "audience", strings(RuleCompiler.AUDIENCES),
                "targetSelection", object(p("cardinality", strings(List.of("one", "all")), "scope", strings(RuleCompiler.TARGET_SCOPES),
                        "excludeSource", type("boolean"), "where", ref("expression")), "cardinality", "scope"),
                "repeatable", ref("expression"), "restMove", object(p("id", ref("id"), "kind", strings(List.of("any", "short", "long", "longMove")),
                        "group", ref("id"), "ally", type("boolean"), "narrative", type("boolean")), "id", "kind"),
                "restPhase", strings(List.of("beforeReset", "afterReset")), "restRollPolicy", strings(List.of("keepHigher"))), "id", "event", "surface", "mode"));
        defs.set("input", inputs());
        defs.set("costOperation", operations(true));
        defs.set("operation", operations(false));
        defs.set("rest", object(restProperties()));
        ObjectNode ruleBody = ruleBody();
        defs.set("ruleBody", ruleBody);
        defs.set("rule", recipe ? ref("ruleBody") : ruleDefinition());
        defs.set("recipe", object(Map.of("id", ref("id"), "args", objectOf(object(p("type", strings(List.of("integer", "string", "costs")),
                "min", type("integer"), "max", type("integer"), "default", ref("any")), "type")), "ui", objectOf(ref("template")),
                "rules", arrayOf(ref("ruleBody"), 1, 64)), "id", "rules"));
    }

    private Map<String, ObjectNode> restProperties() {
        ObjectNode limits = object(Map.of("moves", integer(0, 32), "maxMoves", integer(0, 32), "maxLongMoves", integer(0, 32),
                "consecutiveLimit", integer(1, 1000)));
        Map<String, ObjectNode> map = new TreeMap<>();
        map.put("short", limits);
        map.put("long", limits.deepCopy());
        map.put("startFacts", objectOf(type("string")));
        return map;
    }

    private ObjectNode traits() {
        return strings(List.of("Agility", "Strength", "Finesse", "Instinct", "Presence", "Knowledge"));
    }

    private Map<String, ObjectNode> textFields() {
        Map<String, ObjectNode> map = new TreeMap<>();
        for (String field : RuleCompiler.TEXT_FIELDS) map.put(field, w(ref("template")));
        return map;
    }

    private ObjectNode uniqueEnum(Iterable<String> values) {
        ObjectNode node = type("array").put("minItems", 1).put("uniqueItems", true);
        node.set("items", strings(values));
        return node;
    }

    private ObjectNode operator(String name, ObjectNode operand) {
        ObjectNode node = type("object").put("additionalProperties", false);
        node.putObject("properties").set(name, operand);
        node.putArray("required").add(name);
        return node;
    }

    private ObjectNode inputs() {
        ObjectNode input = RuleJson.JSON.createObjectNode();
        ArrayNode all = input.putArray("allOf");
        if (recipe) input.putArray("anyOf").add(ref("substitution")).add(RuleJson.JSON.createObjectNode().put("type", "object"));
        else input.put("type", "object");
        input.putArray("required").add("id").add("kind");
        Map<String, List<String>> kinds = new java.util.LinkedHashMap<>();
        kinds.put("integer", List.of("id", "kind", "min", "max", "default", "label"));
        kinds.put("die.select", List.of("id", "kind", "from", "eligible", "where", "count", "min", "default", "label", "choiceLabel"));
        kinds.put("choice", List.of("id", "kind", "label", "options", "optionsFrom", "count", "min"));
        kinds.put("record.select", List.of("id", "kind", "label", "from", "where", "choiceLabel"));
        kinds.put("allocation", List.of("id", "kind", "label", "options", "optionsFrom", "total", "max"));
        ObjectNode properties = input.putObject("properties");
        properties.set("id", w(ref("id")));
        properties.set("kind", w(strings(kinds.keySet())));
        kinds.forEach((kind, fields) -> {
            ObjectNode then = type("object").put("additionalProperties", false);
            ObjectNode props = then.putObject("properties");
            for (String field : fields) props.set(field, field.equals("id") ? w(ref("id")) : field.equals("kind") ? ObjectNodeConst.any() : w(ref("any")));
            ObjectNode rule = RuleJson.JSON.createObjectNode();
            rule.putObject("if").set("properties", RuleJson.JSON.createObjectNode().set("kind", RuleJson.JSON.createObjectNode().put("const", kind)));
            ((ObjectNode) rule.get("if")).putArray("required").add("kind");
            rule.set("then", then);
            all.add(rule);
        });
        return input;
    }

    private ObjectNode operations(boolean costs) {
        ObjectNode node = RuleJson.JSON.createObjectNode();
        node.put("type", "object");
        node.putArray("required").add("op");
        List<String> names = new java.util.ArrayList<>();
        for (String op : new TreeSet<>(RuleCompiler.OPERATIONS.keySet())) if (!costs || RuleCompiler.COSTS.contains(op)) names.add(op);
        node.putObject("properties").set("op", w(strings(names)));
        ArrayNode all = node.putArray("allOf");
        for (String op : names) {
            List<String> fields = RuleCompiler.OPERATIONS.get(op);
            ObjectNode then = type("object").put("additionalProperties", false);
            ObjectNode props = then.putObject("properties");
            props.set("op", ObjectNodeConst.any());
            props.set("as", w(ref("id")));
            if (!costs) props.set("if", w(ref("expression")));
            ArrayNode required = then.putArray("required");
            required.add("op");
            for (String field : fields) {
                props.set(field, w(ref("any")));
                if (!RuleCompiler.optionalOperationField(op, field)) required.add(field);
            }
            if (!costs) then.putObject("not").putArray("required").add("as").add("if");
            ObjectNode condition = RuleJson.JSON.createObjectNode();
            ObjectNode test = condition.putObject("if");
            test.putObject("properties").putObject("op").put("const", op);
            test.putArray("required").add("op");
            condition.set("then", then);
            all.add(condition);
        }
        return node;
    }

    private ObjectNode ruleBody() {
        Map<String, ObjectNode> props = p("id", ref("id"), "bindings", arrayOf(w(ref("binding")), 1, 32), "when", ref("expression"),
                "inputs", arrayOf(w(ref("input")), 0, 16), "costs", arrayOf(w(ref("costOperation")), 0, 128), "limitRef", ref("id"),
                "limitSpend", ref("any"), "do", arrayOf(w(ref("operation")), 0, 128),
                "after", object(p("costs", arrayOf(w(ref("costOperation")), 0, 128), "do", arrayOf(w(ref("operation")), 0, 128), "report", ref("template"))),
                "ui", ref("ui"));
        return object(props, "id", "bindings");
    }

    private ObjectNode ruleDefinition() {
        ObjectNode use = object(p("id", ref("id"), "useRecipe", type("string"), "args", ref("any"), "ui", ref("ui")), "id", "useRecipe");
        ObjectNode node = RuleJson.JSON.createObjectNode();
        ObjectNode test = node.putObject("if");
        test.put("type", "object").putArray("required").add("useRecipe");
        node.set("then", use);
        node.set("else", ref("ruleBody"));
        return node;
    }

    /** Placeholder holder so schema property bodies that must stay unconstrained are explicit. */
    private static final class ObjectNodeConst {
        static ObjectNode any() { return RuleJson.JSON.createObjectNode(); }
    }
}
