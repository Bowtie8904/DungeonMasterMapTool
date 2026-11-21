package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Replacement-format catalog compilation; intentionally not connected to the production loader yet. */
public final class RuleCatalog {
    static final Set<String> CATEGORIES = Set.of("ancestries", "armor", "classes", "communities", "consumables",
            "domain-cards", "items", "transformations", "weapons");

    public record Source(String id, String name, RuleCompiler.Program program) {}

    private RuleCatalog() {}

    public static List<Source> compile(String catalogJson, String recipesJson) throws IOException {
        ObjectNode catalog = RuleCompiler.parseDocument(catalogJson, "dmmap-daggerheart");
        ObjectNode recipes = RuleCompiler.parseDocument(recipesJson, "dmmap-daggerheart-rules");
        RuleJson.fields(catalog, "/", "format", "version", "category", "label", "entries");
        String category = RuleJson.text(catalog.path("category"), "/category");
        if (!CATEGORIES.contains(category)) throw RuleJson.error("/category", "Unknown catalog category.");
        if (!catalog.path("entries").isArray()) throw RuleJson.error("/entries", "Expected an entries list.");
        List<Source> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < catalog.path("entries").size(); i++) {
            JsonNode entry = catalog.path("entries").get(i);
            String path = "/entries/" + i;
            String id = RuleJson.id(entry.path("id"), path + "/id");
            if (!ids.add(id)) throw RuleJson.error(path + "/id", "Duplicate entry ID.");
            collect(entry, category + ":" + id, recipes, result, path);
        }
        Set<String> sourceIds = new HashSet<>();
        for (Source source : result)
            if (!sourceIds.add(source.id())) throw RuleJson.error("/entries", "Duplicate fully qualified source ID: " + source.id());
        return List.copyOf(result);
    }

    private static void collect(JsonNode node, String id, ObjectNode recipes, List<Source> sources, String path) {
        RuleJson.object(node, path);
        if (node.has("automation")) {
            String name = RuleJson.text(node.path("name"), path + "/name");
            try { sources.add(new Source(id, name, RuleCompiler.compile(id, node.get("automation"), recipes))); }
            catch (IllegalArgumentException ex) { throw RuleJson.error(path + "/automation (" + id + ")", ex.getMessage()); }
        }
        var fields = node.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (field.getKey().equals("automation") || field.getKey().startsWith("_") || !containsAutomation(field.getValue())) continue;
            String childPath = path + "/" + field.getKey(), childScope = id + ":" + field.getKey();
            JsonNode children = field.getValue();
            if (children.isArray()) {
                Set<String> childIds = new HashSet<>();
                for (int i = 0; i < children.size(); i++) {
                    JsonNode child = children.get(i);
                    if (!containsAutomation(child)) continue;
                    String at = childPath + "/" + i;
                    String childId = RuleJson.id(child.path("id"), at + "/id");
                    if (!childIds.add(childId)) throw RuleJson.error(at + "/id", "Duplicate nested source ID.");
                    collect(child, childScope + ":" + childId, recipes, sources, at);
                }
            } else {
                if (children.has("automation") || children.has("id"))
                    childScope += ":" + RuleJson.id(children.path("id"), childPath + "/id");
                collect(children, childScope, recipes, sources, childPath);
            }
        }
    }

    private static boolean containsAutomation(JsonNode node) {
        if (node.isObject()) {
            if (node.has("automation")) return true;
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!field.getKey().startsWith("_") && containsAutomation(field.getValue())) return true;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) if (containsAutomation(child)) return true;
        }
        return false;
    }

    public static Map<String, List<Source>> compilePack(Map<String, String> catalogs, String recipesJson) throws IOException {
        if (!catalogs.keySet().equals(CATEGORIES)) throw RuleJson.error("/pack", "A replacement pack requires all nine catalog categories.");
        Map<String, List<Source>> result = new LinkedHashMap<>();
        for (String category : CATEGORIES.stream().sorted().toList()) {
            ObjectNode file = RuleCompiler.parseDocument(catalogs.get(category), "dmmap-daggerheart");
            if (!category.equals(file.path("category").asText())) throw RuleJson.error("/pack/" + category, "Catalog category does not match its filename.");
            result.put(category, compile(catalogs.get(category), recipesJson));
        }
        return Map.copyOf(result);
    }

    /** Validates candidate files only; never rewrites catalogs or saved games. */
    public static void main(String[] args) throws IOException {
        if (args.length != 2) throw new IllegalArgumentException("Usage: RuleCatalog <catalog.json> <recipes.json>");
        Path catalog = Path.of(args[0]), recipes = Path.of(args[1]);
        if (Files.size(catalog) > RuleJson.MAX_DOCUMENT_CHARACTERS || Files.size(recipes) > RuleJson.MAX_DOCUMENT_CHARACTERS)
            throw new IllegalArgumentException("Candidate rule files exceed the size limit.");
        List<Source> sources = compile(Files.readString(catalog), Files.readString(recipes));
        int rules = sources.stream().mapToInt(source -> source.program().ruleIds().size()).sum();
        System.out.println("Validated " + sources.size() + " automation sources and " + rules + " expanded rules (format " + RuleCompiler.FORMAT_VERSION + ").");
    }
}
