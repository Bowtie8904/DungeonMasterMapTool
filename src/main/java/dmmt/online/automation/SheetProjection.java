package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Interprets a declarative sheet projection: which sheet elements are automation sources, how sheet fields are
 * exposed as {@code actor} facts, and which of those facts rules may write back. Rules never patch sheets directly;
 * only declared, bounded fields are copied back.
 */
public final class SheetProjection {
    public static final String FORMAT = "dmmap-sheet-projection";
    private static final Set<String> KINDS = Set.of("value", "object", "map", "first", "collection", "engine");

    public record SourceElement(String sourceId, String instanceId, String name, ObjectNode element,
                                String quantityField, String sourcePath) {
        public SourceElement(String sourceId, String instanceId, String name, ObjectNode element, String quantityField) {
            this(sourceId, instanceId, name, element, quantityField, null);
        }
    }

    private final ObjectNode spec;
    private final String stateField, sourceField, instanceField, nameField;
    private final List<JsonNode> traitStats;
    private final Set<String> sourceFacts;
    private final Set<String> mutableStats;

    private SheetProjection(ObjectNode spec) {
        this.spec = spec;
        stateField = spec.path("stateField").asText();
        sourceField = spec.path("sourceField").asText();
        instanceField = spec.path("instanceField").asText();
        nameField = spec.path("nameField").asText();
        JsonNode stats = spec.path("stats");
        boolean[] needed = new boolean[stats.size()];
        for (int i = 0; i < stats.size(); i++) {
            String id = stats.get(i).path("id").asText();
            needed[i] = id.startsWith("trait.") || "spellcast.total".equals(id);
        }
        for (int i = stats.size() - 1; i >= 0; i--) {
            if (!needed[i]) continue;
            Set<String> refs = RuleExpression.references(stats.get(i).get("value"));
            for (int j = 0; j < i; j++) {
                String name = "actor.stats." + stats.get(j).path("id").asText();
                for (String ref : refs)
                    if ("actor.stats".equals(ref) || name.equals(ref) || name.startsWith(ref + ".") || ref.startsWith(name + ".")) {
                        needed[j] = true;
                        break;
                    }
            }
        }
        List<JsonNode> selected = new ArrayList<>();
        for (int i = 0; i < stats.size(); i++) if (needed[i]) selected.add(stats.get(i));
        traitStats = List.copyOf(selected);
        Set<String> sourceFacts = new HashSet<>();
        Set<String> mutableFacts = new HashSet<>();
        spec.path("actor").fields().forEachRemaining(fact -> {
            JsonNode writable = fact.getValue().path("writable");
            if (writable.asBoolean(false) || writable.isArray() && !writable.isEmpty()
                    || "engine".equals(fact.getValue().path("kind").asText()))
                mutableFacts.add("actor." + fact.getKey());
            String factPath = fact.getValue().path("path").asText();
            for (JsonNode source : spec.path("sources")) {
                if (overlaps(factPath, source.path("path").asText())) {
                    sourceFacts.add(fact.getKey());
                    break;
                }
            }
        });
        this.sourceFacts = Set.copyOf(sourceFacts);
        Set<String> mutableStats = new HashSet<>();
        for (JsonNode stat : stats) {
            Set<String> refs = RuleExpression.references(stat.get("value"));
            if (refs.stream().anyMatch(ref -> mutableFacts.stream().anyMatch(fact -> overlaps(ref, fact))
                    || mutableStats.stream().anyMatch(derived -> overlaps(ref, derived))))
                mutableStats.add("actor.stats." + stat.path("id").asText());
        }
        this.mutableStats = Set.copyOf(mutableStats);
    }

    boolean readsMutableStats(Set<String> references) {
        return references.stream().anyMatch(ref -> mutableStats.stream().anyMatch(stat -> overlaps(ref, stat)));
    }

    public static SheetProjection parse(String json) throws java.io.IOException {
        ObjectNode spec = RuleCompiler.parseDocument(json, FORMAT);
        RuleJson.fields(spec, "/", "format", "version", "stateField", "sourceField", "instanceField", "nameField", "sources", "actor", "stats", "poolTracks");
        for (String field : List.of("stateField", "sourceField", "instanceField", "nameField")) path(spec.get(field), "/" + field);
        if (!spec.path("sources").isArray() || spec.path("sources").isEmpty()) throw RuleJson.error("/sources", "Expected source locations.");
        for (int i = 0; i < spec.path("sources").size(); i++) {
            JsonNode source = spec.path("sources").get(i);
            validateSource(source, "/sources/" + i, false);
        }
        ObjectNode actor = RuleJson.object(spec.get("actor"), "/actor");
        actor.fields().forEachRemaining(field -> validateFact(field.getKey(), field.getValue()));
        if (actor.has("stats")) throw RuleJson.error("/actor/stats", "The stats fact is reserved for derived statistics.");
        if (spec.has("poolTracks")) {
            JsonNode mappings = spec.get("poolTracks");
            if (!mappings.isArray() || mappings.size() > 32)
                throw RuleJson.error("/poolTracks", "Expected at most 32 pool-to-track mappings.");
            if (!"engine".equals(actor.path("pools").path("kind").asText())
                    || !actor.path("pools").path("default").isObject())
                throw RuleJson.error("/poolTracks", "Mapped pools require an object-valued actor pools fact.");
            Set<String> pools = new HashSet<>(), tracks = new HashSet<>(), flags = new HashSet<>();
            for (int i = 0; i < mappings.size(); i++) {
                JsonNode mapping = mappings.get(i);
                String at = "/poolTracks/" + i;
                RuleJson.fields(mapping, at, "pool", "track", "fullFlag");
                String pool = RuleJson.id(mapping.get("pool"), at + "/pool");
                String track = RuleJson.id(mapping.get("track"), at + "/track");
                JsonNode definition = actor.path(track);
                if (!pools.add(pool) || !tracks.add(track) || !"object".equals(definition.path("kind").asText())
                        || !definition.path("fields").has("marked") || !definition.path("fields").has("capacity")
                        || !contains(definition.path("writable"), "marked")
                        || definition.has("markedFloor") || definition.has("capacityReserve"))
                    throw RuleJson.error(at, "Each mapping needs a unique, writable plain marked track and pool.");
                if (mapping.has("fullFlag")) {
                    String flag = RuleJson.id(mapping.get("fullFlag"), at + "/fullFlag");
                    JsonNode flagFact = actor.path(flag);
                    if (!flags.add(flag) || !"value".equals(flagFact.path("kind").asText())
                            || !"boolean".equals(flagFact.path("type").asText())
                            || !flagFact.path("writable").asBoolean()
                            || overlaps(flagFact.path("path").asText(), definition.path("path").asText()))
                        throw RuleJson.error(at + "/fullFlag", "Expected a unique writable boolean outside the mapped track.");
                }
            }
        }
        actor.fields().forEachRemaining(field -> {
            for (JsonNode sum : field.getValue().path("markedFloor").path("actorSum")) {
                String name = sum.path("path").asText();
                if (!Set.of("collection", "engine").contains(actor.path(name).path("kind").asText()))
                    throw RuleJson.error("/actor/" + field.getKey() + "/markedFloor/actorSum",
                            "A live lock collection must name a projected collection or engine fact.");
            }
        });
        Set<String> reserveCounters = new HashSet<>();
        actor.fields().forEachRemaining(field -> {
            JsonNode reserve = field.getValue().path("capacityReserve");
            if (reserve.isMissingNode()) return;
            String counter = reserve.path("counter").asText();
            JsonNode target = actor.path(counter);
            if (counter.equals(field.getKey()) || !"value".equals(target.path("kind").asText())
                    || !target.path("writable").asBoolean() || !reserveCounters.add(counter))
                throw RuleJson.error("/actor/" + field.getKey() + "/capacityReserve/counter",
                        "A capacity reserve requires its own writable numeric fact.");
            String counterPath = target.path("path").asText();
            String trackPath = field.getValue().path("path").asText();
            for (String part : List.of(reserve.path("capacity").asText(),
                    "capacity".equals(reserve.path("capacity").asText()) ? "marked" : "value",
                    reserve.path("total").asText(""))) {
                if (!part.isEmpty() && overlaps(counterPath, trackPath + "." + field.getValue().path("fields").path(part).asText()))
                    throw RuleJson.error("/actor/" + field.getKey() + "/capacityReserve/counter",
                            "The reserve counter must not overlap a track field.");
            }
        });
        if (spec.has("stats")) {
            JsonNode stats = spec.get("stats");
            if (!stats.isArray() || stats.size() > 64) throw RuleJson.error("/stats", "Expected up to 64 derived statistics.");
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < stats.size(); i++) {
                JsonNode stat = stats.get(i);
                String at = "/stats/" + i;
                RuleJson.fields(stat, at, "id", "value", "min", "max");
                String id = RuleJson.text(stat.get("id"), at + "/id");
                if (!id.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*){0,2}") || !ids.add(id))
                    throw RuleJson.error(at + "/id", "Expected a unique dotted statistic name.");
                for (String other : ids) if (!other.equals(id) && (other.startsWith(id + ".") || id.startsWith(other + ".")))
                    throw RuleJson.error(at + "/id", "A statistic cannot also be a group of statistics.");
                RuleExpression.validate(stat.get("value"), at + "/value");
                for (String ref : RuleExpression.references(stat.get("value")))
                    if (!ref.startsWith("actor.")) throw RuleJson.error(at + "/value", "Statistics may only read actor facts.");
                for (String bound : List.of("min", "max"))
                    if (stat.has(bound)) RuleJson.integer(stat.get(bound), at + "/" + bound, -1_000_000, 1_000_000);
            }
        }
        return new SheetProjection(spec);
    }

    private static void validateSource(JsonNode source, String at, boolean child) {
        RuleJson.fields(source, at, "path", "single", "quantity", "derive", "children");
        path(source.get("path"), at + "/path");
        if (source.has("single")) RuleExpression.bool(source.get("single"), at + "/single");
        if (source.has("quantity")) path(source.get("quantity"), at + "/quantity");
        if (source.has("derive")) {
            RuleExpression.bool(source.get("derive"), at + "/derive");
            if (!child) throw RuleJson.error(at + "/derive", "Only nested sources can derive an identity.");
        }
        if (source.has("children")) {
            JsonNode children = source.get("children");
            if (!children.isArray()) throw RuleJson.error(at + "/children", "Expected nested source locations.");
            for (int i = 0; i < children.size(); i++)
                validateSource(children.get(i), at + "/children/" + i, true);
        }
    }

    /**
     * Computes the declared derived statistics in order. Each base value may read actor facts and earlier statistics
     * ({@code actor.stats.*}); the modifiers whose {@code stat} matches the statistic ID are then applied, followed by
     * the declared bounds. The result is exposed to rules as {@code actor.stats} and is never written back.
     */
    public ObjectNode stats(ObjectNode actor, List<JsonNode> modifiers) {
        return stats(actor, modifiers, spec.path("stats"));
    }

    ObjectNode traitStats(ObjectNode actor, List<JsonNode> modifiers) {
        if (traitStats.isEmpty()) return RuleJson.JSON.createObjectNode();
        return stats(actor, modifiers, traitStats);
    }

    private ObjectNode stats(ObjectNode actor, List<JsonNode> modifiers, Iterable<JsonNode> declared) {
        ObjectNode working = actor.deepCopy();
        ObjectNode stats = working.putObject("stats");
        ObjectNode context = RuleJson.JSON.createObjectNode();
        context.set("actor", working);
        for (JsonNode stat : declared) {
            String id = stat.path("id").asText();
            double value = RuleEffects.stat(RuleExpression.number(RuleExpression.evaluate(stat.get("value"), context),
                    "/stats/" + id), id, modifiers);
            if (stat.has("min")) value = Math.max(stat.path("min").asInt(), value);
            if (stat.has("max")) value = Math.min(stat.path("max").asInt(), value);
            set(stats, id, RuleExpression.numeric(value, "/stats/" + id));
        }
        return stats;
    }

    private static void validateFact(String key, JsonNode fact) {
        String at = "/actor/" + key;
        RuleJson.id(RuleJson.JSON.getNodeFactory().textNode(key), at);
        String kind = RuleJson.text(fact.path("kind"), at + "/kind");
        if (!KINDS.contains(kind)) throw RuleJson.error(at + "/kind", "Unknown projection kind.");
        path(fact.get("path"), at + "/path");
        switch (kind) {
            case "value" -> {
                RuleJson.fields(fact, at, "kind", "path", "writable", "min", "max", "default", "type");
                bounds(fact, at);
                if (fact.has("default") && !fact.get("default").isValueNode()) throw RuleJson.error(at + "/default", "Expected a plain default value.");
                if (fact.has("type") && !"boolean".equals(fact.path("type").asText()))
                    throw RuleJson.error(at + "/type", "The only explicit value type is boolean.");
                if ("boolean".equals(fact.path("type").asText())) {
                    if (fact.has("min") || fact.has("max")) throw RuleJson.error(at, "Boolean facts cannot have numeric bounds.");
                    if (fact.has("default")) RuleExpression.bool(fact.get("default"), at + "/default");
                }
            }
            case "object" -> {
                RuleJson.fields(fact, at, "kind", "path", "fields", "writable", "atLeast", "max", "markedFloor", "capacityReserve");
                ObjectNode fields = RuleJson.object(fact.get("fields"), at + "/fields");
                if (fields.isEmpty()) throw RuleJson.error(at + "/fields", "Expected mapped fields.");
                fields.fields().forEachRemaining(field -> path(field.getValue(), at + "/fields/" + field.getKey()));
                if (fact.has("writable")) for (JsonNode name : fact.get("writable"))
                    if (!fields.has(name.asText())) throw RuleJson.error(at + "/writable", "Writable fields must be mapped.");
                if (fact.has("atLeast")) RuleJson.object(fact.get("atLeast"), at + "/atLeast").fields().forEachRemaining(field -> {
                    path(RuleJson.JSON.getNodeFactory().textNode(field.getKey()), at + "/atLeast");
                    if (!fields.has(field.getValue().asText())) throw RuleJson.error(at + "/atLeast", "Expected a mapped field.");
                });
                if (fact.has("max")) RuleJson.integer(fact.get("max"), at + "/max", 1, 1_000_000);
                if (fact.has("capacityReserve")) {
                    JsonNode reserve = fact.get("capacityReserve");
                    RuleJson.fields(reserve, at + "/capacityReserve", "capacity", "counter", "total");
                    String capacity = RuleJson.text(reserve.get("capacity"), at + "/capacityReserve/capacity");
                    String expected = fields.has("capacity") ? "capacity" : "max";
                    String filled = "capacity".equals(capacity) ? "marked" : "value";
                    if (!expected.equals(capacity) || !fields.has(capacity)
                            || !fields.has(filled) || !contains(fact.path("writable"), capacity)
                            || !contains(fact.path("writable"), filled)
                            || overlaps(fields.path(capacity).asText(), fields.path(filled).asText()))
                        throw RuleJson.error(at + "/capacityReserve/capacity", "The track capacity and value must be distinct, mapped and writable.");
                    RuleJson.id(reserve.get("counter"), at + "/capacityReserve/counter");
                    if (reserve.has("total")) {
                        String total = RuleJson.id(reserve.get("total"), at + "/capacityReserve/total");
                        if (!fields.has(total) || total.equals(capacity) || total.equals(filled)
                                || contains(fact.path("writable"), total)
                                || overlaps(fields.path(total).asText(), fields.path(capacity).asText())
                                || overlaps(fields.path(total).asText(), fields.path(filled).asText()))
                            throw RuleJson.error(at + "/capacityReserve/total", "The total must be a mapped read-only field.");
                        fact.path("atLeast").fieldNames().forEachRemaining(path -> {
                            if (overlaps(path, fields.path(total).asText()))
                                throw RuleJson.error(at + "/capacityReserve/total", "The base total cannot be adjusted by atLeast.");
                        });
                    }
                }
                if (fact.has("markedFloor")) {
                    if (!fields.has("marked") || !fields.has("capacity") || fields.has("minimumMarkedBase")
                            || fields.has("minimumMarkedCollections") || fields.has("minimumCapacity"))
                        throw RuleJson.error(at + "/markedFloor", "A marked floor requires marked and capacity fields and reserves its minimum fields.");
                    JsonNode floor = fact.get("markedFloor");
                    RuleJson.fields(floor, at + "/markedFloor", "base", "sum", "actorSum");
                    path(floor.get("base"), at + "/markedFloor/base");
                    for (String list : List.of("sum", "actorSum")) {
                        JsonNode sums = floor.path(list);
                        if (sums.isMissingNode()) continue;
                        if (!sums.isArray() || sums.size() > 8)
                            throw RuleJson.error(at + "/markedFloor/" + list, "Expected at most 8 lock collections.");
                        for (int i = 0; i < sums.size(); i++) {
                            String where = at + "/markedFloor/" + list + "/" + i;
                            RuleJson.fields(sums.get(i), where, "path", "field");
                            path(sums.get(i).get("path"), where + "/path");
                            path(sums.get(i).get("field"), where + "/field");
                        }
                    }
                }
            }
            case "map" -> {
                RuleJson.fields(fact, at, "kind", "path", "key", "value");
                path(fact.get("key"), at + "/key");
                path(fact.get("value"), at + "/value");
            }
            case "first" -> {
                RuleJson.fields(fact, at, "kind", "path", "where", "fields");
                JsonNode where = fact.get("where");
                RuleJson.fields(where, at + "/where", "field", "equals");
                path(where.get("field"), at + "/where/field");
                if (!where.path("equals").isValueNode() || where.path("equals").isNull())
                    throw RuleJson.error(at + "/where/equals", "Expected a non-null scalar.");
                ObjectNode fields = RuleJson.object(fact.get("fields"), at + "/fields");
                if (fields.isEmpty()) throw RuleJson.error(at + "/fields", "Expected mapped fields.");
                fields.fields().forEachRemaining(field -> {
                    RuleJson.id(RuleJson.JSON.getNodeFactory().textNode(field.getKey()), at + "/fields");
                    path(field.getValue(), at + "/fields/" + field.getKey());
                });
            }
            case "collection" -> {
                RuleJson.fields(fact, at, "kind", "path", "fields", "defaults", "writable");
                ObjectNode fields = RuleJson.object(fact.get("fields"), at + "/fields");
                if (!fields.has("id")) throw RuleJson.error(at + "/fields/id", "Collections need an ID field.");
                fields.fields().forEachRemaining(field -> path(field.getValue(), at + "/fields/" + field.getKey()));
                if (fact.has("defaults")) RuleJson.object(fact.get("defaults"), at + "/defaults");
            }
            case "engine" -> {
                RuleJson.fields(fact, at, "kind", "path", "default");
                if (!fact.path("path").asText().contains(".")) throw RuleJson.error(at + "/path", "Engine-owned facts need a nested path.");
            }
            default -> throw RuleJson.error(at, "Unknown projection kind.");
        }
    }

    private static void bounds(JsonNode fact, String at) {
        if (fact.has("writable")) RuleExpression.bool(fact.get("writable"), at + "/writable");
        if (fact.has("min")) RuleJson.integer(fact.get("min"), at + "/min", -1_000_000, 1_000_000);
        if (fact.has("max")) RuleJson.integer(fact.get("max"), at + "/max", -1_000_000, 1_000_000);
    }

    private static boolean contains(JsonNode values, String expected) {
        for (JsonNode value : values) if (expected.equals(value.asText())) return true;
        return false;
    }

    private static boolean overlaps(String left, String right) {
        return left.equals(right) || left.startsWith(right + ".") || right.startsWith(left + ".");
    }

    static String path(JsonNode node, String at) {
        String path = RuleJson.text(node, at);
        if (!path.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*){0,7}"))
            throw RuleJson.error(at, "Expected a dotted sheet path.");
        return path;
    }

    public String stateField() { return stateField; }

    public record TrackChange(String track, String change, int requested, ObjectNode before, ObjectNode after) {}

    public boolean hasTrackChanges(ObjectNode previous, ObjectNode current) {
        var entries = spec.path("actor").fields();
        while (entries.hasNext()) {
            JsonNode fact = entries.next().getValue(), fields = fact.path("fields"), writable = fact.path("writable");
            if (!"object".equals(fact.path("kind").asText())) continue;
            String value = fields.has("marked") && fields.has("capacity") && contains(writable, "marked")
                    ? "marked" : fields.has("value") && fields.has("max") && contains(writable, "value") ? "value" : null;
            if (value == null) continue;
            String capacity = "marked".equals(value) ? "capacity" : "max";
            JsonNode before = get(previous, fact.path("path").asText()), after = get(current, fact.path("path").asText());
            if (before.isObject() && after.isObject()) {
                if (!get((ObjectNode) before, fields.path(value).asText())
                        .equals(get((ObjectNode) after, fields.path(value).asText()))) return true;
                if (contains(writable, capacity) && !get((ObjectNode) before, fields.path(capacity).asText())
                        .equals(get((ObjectNode) after, fields.path(capacity).asText()))) return true;
            }
        }
        return false;
    }

    /** Finds changes to projected writable tracks and resources in two complete sheet snapshots. */
    public List<TrackChange> trackChanges(ObjectNode previous, ObjectNode current) {
        ObjectNode before = actor(previous), after = actor(current);
        List<TrackChange> changes = new ArrayList<>();
        spec.path("actor").fields().forEachRemaining(entry -> {
            JsonNode fact = entry.getValue(), fields = fact.path("fields"), writable = fact.path("writable");
            if (!"object".equals(fact.path("kind").asText())) return;
            boolean marked = fields.has("marked") && fields.has("capacity") && contains(writable, "marked");
            boolean resource = fields.has("value") && fields.has("max") && contains(writable, "value");
            if (!marked && !resource) return;
            String value = marked ? "marked" : "value";
            String capacity = marked ? "capacity" : "max";
            JsonNode oldTrack = before.path(entry.getKey()), newTrack = after.path(entry.getKey());
            if (!(oldTrack instanceof ObjectNode oldObject) || !(newTrack instanceof ObjectNode newObject)) return;
            if (contains(writable, capacity) && !oldTrack.path(capacity).equals(newTrack.path(capacity))) {
                int oldCapacity = RuleJson.integer(oldTrack.get(capacity), "/actor/" + entry.getKey() + "/" + capacity, 0, 1000);
                int newCapacity = RuleJson.integer(newTrack.get(capacity), "/actor/" + entry.getKey() + "/" + capacity, 0, 1000);
                changes.add(new TrackChange(entry.getKey(), "capacity", newCapacity - oldCapacity,
                        oldObject.deepCopy(), newObject.deepCopy()));
            }
            if (!oldTrack.path(value).equals(newTrack.path(value))) {
                int oldValue = RuleJson.integer(oldTrack.get(value), "/actor/" + entry.getKey() + "/" + value, 0, 1000);
                int newValue = RuleJson.integer(newTrack.get(value), "/actor/" + entry.getKey() + "/" + value, 0, 1000);
                String change = marked ? newValue > oldValue ? "mark" : "clear" : newValue > oldValue ? "gain" : "spend";
                changes.add(new TrackChange(entry.getKey(), change, Math.abs(newValue - oldValue),
                        oldObject.deepCopy(), newObject.deepCopy()));
            }
        });
        return List.copyOf(changes);
    }

    Set<String> sourceFacts() { return sourceFacts; }

    public List<String> sourcePaths() {
        List<String> paths = new ArrayList<>();
        spec.path("sources").forEach(source -> sourcePaths(source, "", paths));
        return paths;
    }

    private static void sourcePaths(JsonNode location, String parent, List<String> paths) {
        String path = parent.isEmpty() ? location.path("path").asText() : parent + "." + location.path("path").asText();
        paths.add(path);
        location.path("children").forEach(child -> sourcePaths(child, path, paths));
    }

    /** Automation state block, created on demand: {revision, sources:{instanceId:{state,usage,sourcePools}}, journal:[]}. */
    public ObjectNode state(ObjectNode sheet) {
        JsonNode existing = sheet.get(stateField);
        ObjectNode state = existing instanceof ObjectNode object ? object : sheet.putObject(stateField);
        if (!state.has("revision")) state.put("revision", 0);
        if (!state.path("sources").isObject()) state.putObject("sources");
        if (!state.path("journal").isArray()) state.putArray("journal");
        return state;
    }

    public List<SourceElement> sources(ObjectNode sheet) {
        List<SourceElement> result = new ArrayList<>();
        Set<String> instances = new HashSet<>();
        for (JsonNode location : spec.path("sources")) collectSources(sheet, location, "", null, result, instances);
        return result;
    }

    private void collectSources(ObjectNode root, JsonNode location, String parentPath, SourceElement parent,
                                List<SourceElement> result, Set<String> instances) {
        String relative = location.path("path").asText();
        String sourcePath = parentPath.isEmpty() ? relative : parentPath + "." + relative;
        JsonNode value = get(root, relative);
        List<JsonNode> elements = new ArrayList<>();
        if (location.path("single").asBoolean()) { if (value.isObject()) elements.add(value); }
        else if (value.isArray()) value.forEach(elements::add);
        for (JsonNode element : elements) {
            if (!(element instanceof ObjectNode object)) continue;
            String sourceId = element.path(sourceField).isTextual() ? element.path(sourceField).asText() : null;
            String instanceId = element.path(instanceField).isTextual() ? element.path(instanceField).asText() : null;
            if (location.path("derive").asBoolean()) {
                sourceId = null;
                instanceId = null;
                if (parent != null && element.path("id").isTextual()) {
                    String id = RuleJson.id(element.get("id"), "/" + sourcePath + "/id");
                    sourceId = parent.sourceId() + ":" + relative.replace('.', ':') + ":" + id;
                    instanceId = "n" + parent.instanceId().length() + "." + parent.instanceId()
                            + "." + relative.length() + "." + relative + "." + id;
                    RuleJson.id(RuleJson.JSON.getNodeFactory().textNode(sourceId), "/" + sourcePath + "/sourceId");
                    RuleJson.id(RuleJson.JSON.getNodeFactory().textNode(instanceId), "/" + sourcePath + "/instanceId");
                }
            }
            SourceElement source = null;
            if (sourceId != null && instanceId != null) {
                if (!instances.add(instanceId))
                    throw RuleJson.error("/" + instanceField, "Duplicate source instance on the sheet: " + instanceId);
                source = new SourceElement(sourceId, instanceId, element.path(nameField).asText(""),
                        object, location.path("quantity").asText(null), sourcePath);
                result.add(source);
            }
            for (JsonNode child : location.path("children"))
                collectSources(object, child, sourcePath, source, result, instances);
        }
    }

    boolean removeSource(ObjectNode sheet, SourceElement source) {
        for (JsonNode location : spec.path("sources"))
            if (removeSource(sheet, location, source.element())) return true;
        return false;
    }

    List<String> sourceInstancesWithin(ObjectNode sheet, SourceElement source) {
        return sources(sheet).stream()
                .filter(candidate -> containsIdentity(source.element(), candidate.element()))
                .map(SourceElement::instanceId).toList();
    }

    private static boolean containsIdentity(JsonNode root, JsonNode target) {
        if (root == target) return true;
        if (!root.isContainerNode()) return false;
        for (JsonNode child : root) if (containsIdentity(child, target)) return true;
        return false;
    }

    private static boolean removeSource(ObjectNode root, JsonNode location, ObjectNode target) {
        String path = location.path("path").asText();
        JsonNode value = get(root, path);
        if (location.path("single").asBoolean()) {
            if (value == target) {
                int separator = path.lastIndexOf('.');
                JsonNode owner = separator < 0 ? root : get(root, path.substring(0, separator));
                ((ObjectNode) owner).remove(path.substring(separator + 1));
                return true;
            }
            if (value instanceof ObjectNode object)
                for (JsonNode child : location.path("children"))
                    if (removeSource(object, child, target)) return true;
        } else if (value instanceof ArrayNode elements) {
            for (int i = 0; i < elements.size(); i++) {
                JsonNode element = elements.get(i);
                if (element == target) { elements.remove(i); return true; }
                if (element instanceof ObjectNode object)
                    for (JsonNode child : location.path("children"))
                        if (removeSource(object, child, target)) return true;
            }
        }
        return false;
    }

    public ObjectNode actor(ObjectNode sheet) {
        ObjectNode actor = RuleJson.JSON.createObjectNode();
        spec.path("actor").fields().forEachRemaining(entry -> {
            JsonNode fact = entry.getValue();
            JsonNode value = get(sheet, fact.path("path").asText());
            switch (fact.path("kind").asText()) {
                case "value" -> {
                    if ("boolean".equals(fact.path("type").asText()) && !value.isMissingNode() && !value.isNull())
                        RuleExpression.bool(value, "/actor/" + entry.getKey());
                    if (value.isNumber() || value.isTextual() || value.isBoolean()) actor.set(entry.getKey(), value.deepCopy());
                    else if (fact.has("default")) actor.set(entry.getKey(), fact.get("default").deepCopy());
                }
                case "object" -> {
                    if (!value.isObject()) return;
                    ObjectNode projected = actor.putObject(entry.getKey());
                    fact.path("fields").fields().forEachRemaining(field -> {
                        JsonNode item = get((ObjectNode) value, field.getValue().asText());
                        if (!item.isMissingNode() && !item.isNull()) projected.set(field.getKey(), item.deepCopy());
                    });
                }
                case "map" -> {
                    ObjectNode projected = actor.putObject(entry.getKey());
                    for (JsonNode item : value) if (item.isObject()) {
                        JsonNode key = get((ObjectNode) item, fact.path("key").asText());
                        JsonNode mapped = get((ObjectNode) item, fact.path("value").asText());
                        if (key.isTextual() && !mapped.isMissingNode()) projected.set(key.asText(), mapped.deepCopy());
                    }
                }
                case "first" -> {
                    if (!value.isArray()) return;
                    JsonNode where = fact.path("where");
                    for (JsonNode item : value) {
                        if (!item.isObject()) continue;
                        JsonNode match = get((ObjectNode) item, where.path("field").asText());
                        if (!RuleExpression.equal(match, where.path("equals"))) continue;
                        ObjectNode projected = actor.putObject(entry.getKey());
                        fact.path("fields").fields().forEachRemaining(field -> {
                            JsonNode mapped = get((ObjectNode) item, field.getValue().asText());
                            if (!mapped.isMissingNode() && !mapped.isNull()) projected.set(field.getKey(), mapped.deepCopy());
                        });
                        break;
                    }
                }
                case "collection" -> {
                    ArrayNode projected = actor.putArray(entry.getKey());
                    for (JsonNode item : value) {
                        ObjectNode mapped = collectionEntry(fact, item);
                        if (mapped != null) projected.add(mapped);
                    }
                }
                case "engine" -> actor.set(entry.getKey(), value.isMissingNode() || value.isNull()
                        ? fact.path("default").isMissingNode() ? RuleJson.JSON.createArrayNode() : fact.path("default").deepCopy()
                        : value.deepCopy());
                default -> { }
            }
        });
        spec.path("actor").fields().forEachRemaining(entry -> {
            JsonNode fact = entry.getValue();
            if (fact.has("markedFloor") && actor.path(entry.getKey()).isObject())
                projectMarkedFloor(sheet, fact.get("markedFloor"), (ObjectNode) actor.get(entry.getKey()), "/actor/" + entry.getKey());
        });
        for (JsonNode mapping : spec.path("poolTracks")) {
            JsonNode track = actor.path(mapping.path("track").asText());
            if (track.isMissingNode() || track.isNull()) continue;
            String at = "/actor/" + mapping.path("track").asText();
            int capacity = RuleJson.integer(track.get("capacity"), at + "/capacity", 0, 1_000_000);
            int marked = RuleJson.integer(track.get("marked"), at + "/marked", 0, capacity);
            ObjectNode pools = RuleJson.object(actor.get("pools"), "/actor/pools");
            pools.putObject(mapping.path("pool").asText()).put("max", capacity).put("value", capacity - marked);
        }
        return actor;
    }

    private static void projectMarkedFloor(ObjectNode sheet, JsonNode definition, ObjectNode track, String at) {
        int capacity = RuleJson.integer(track.get("capacity"), at + "/capacity", 0, 1_000_000);
        JsonNode base = get(sheet, definition.path("base").asText());
        int permanent = base.isMissingNode() || base.isNull() ? 0
                : RuleJson.integer(base, at + "/markedFloor/base", 0, 1_000_000);
        if (permanent > capacity) throw RuleJson.error(at + "/capacity", "Track capacity is below its permanent marks.");
        long minimum = permanent;
        for (JsonNode sum : definition.path("sum")) {
            JsonNode entries = get(sheet, sum.path("path").asText());
            if (entries.isMissingNode() || entries.isNull()) continue;
            if (!entries.isArray()) throw RuleJson.error(at + "/markedFloor", "Expected a lock collection.");
            for (JsonNode entry : entries) {
                JsonNode lock = get(RuleJson.object(entry, at + "/markedFloor"), sum.path("field").asText());
                if (!lock.isMissingNode() && !lock.isNull())
                    minimum += Math.max(0, RuleJson.integer(lock, at + "/markedFloor/lock", -1_000_000, 1_000_000));
            }
        }
        track.put("minimumCapacity", permanent);
        track.put("minimumMarkedBase", (int) Math.min(1_000_000, minimum));
        if (definition.has("actorSum")) track.set("minimumMarkedCollections", definition.get("actorSum").deepCopy());
    }

    static int markedFloor(ObjectNode owner, ObjectNode track, int capacity, String at) {
        long minimum = track.has("minimumMarkedBase")
                ? RuleJson.integer(track.get("minimumMarkedBase"), at + "/minimumMarkedBase", 0, 1_000_000) : 0;
        JsonNode sums = track.path("minimumMarkedCollections");
        if (!sums.isMissingNode() && !sums.isArray()) throw RuleJson.error(at, "Expected floor lock collections.");
        for (JsonNode sum : sums) {
            JsonNode entries = get(owner, sum.path("path").asText());
            if (!entries.isArray()) throw RuleJson.error(at, "Expected a lock collection.");
            for (JsonNode entry : entries) {
                JsonNode lock = get(RuleJson.object(entry, at), sum.path("field").asText());
                if (!lock.isMissingNode() && !lock.isNull())
                    minimum += Math.max(0, RuleJson.integer(lock, at + "/lock", -1_000_000, 1_000_000));
            }
        }
        return (int) Math.min(capacity, minimum);
    }

    /** A read-only view of one engine-owned fact, without projecting unrelated sheet fields. */
    JsonNode actorFact(ObjectNode sheet, String name) {
        JsonNode fact = spec.path("actor").path(name);
        if (!"engine".equals(fact.path("kind").asText())) return actor(sheet).path(name);
        JsonNode value = get(sheet, fact.path("path").asText());
        if (!value.isMissingNode() && !value.isNull()) return value;
        return fact.has("default") ? fact.get("default") : RuleJson.JSON.createArrayNode();
    }

    private static ObjectNode collectionEntry(JsonNode fact, JsonNode item) {
        if (!item.isObject()) return null;
        ObjectNode mapped = RuleJson.JSON.createObjectNode();
        fact.path("fields").fields().forEachRemaining(field -> {
            JsonNode value = get((ObjectNode) item, field.getValue().asText());
            if (!value.isMissingNode() && !value.isNull()) mapped.set(field.getKey(), value.deepCopy());
        });
        return mapped.path("id").isTextual() && !mapped.path("id").asText().isBlank() ? mapped : null;
    }

    /** Copies declared writable facts from an executed actor snapshot back into the sheet. */
    public void write(ObjectNode sheet, ObjectNode before, ObjectNode after) {
        validateCapacityReserves(sheet, before, after);
        spec.path("actor").fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            JsonNode fact = entry.getValue();
            JsonNode next = after.path(key);
            boolean syncMappedPool = false;
            if ("pools".equals(key) && "engine".equals(fact.path("kind").asText())) {
                JsonNode stored = get(sheet, fact.path("path").asText());
                for (JsonNode mapping : spec.path("poolTracks")) {
                    String pool = mapping.path("pool").asText();
                    if (after.path(mapping.path("track").asText()).isObject()
                            && !stored.path(pool).equals(next.path(pool))) {
                        syncMappedPool = true;
                        break;
                    }
                }
            }
            if (next.equals(before.path(key)) && !syncMappedPool) return;
            String at = "/actor/" + key;
            switch (fact.path("kind").asText()) {
                case "value" -> {
                    if (!fact.path("writable").asBoolean()) throw RuleJson.error(at, "This actor fact is read-only.");
                    if ("boolean".equals(fact.path("type").asText())) {
                        set(sheet, fact.path("path").asText(),
                                RuleJson.JSON.getNodeFactory().booleanNode(RuleExpression.bool(next, at)));
                    } else {
                        set(sheet, fact.path("path").asText(), RuleExpression.numeric(RuleJson.integer(next, at,
                                fact.path("min").asInt(0), fact.path("max").asInt(1_000_000)), at));
                    }
                }
                case "object" -> {
                    ObjectNode target = RuleJson.object(get(sheet, fact.path("path").asText()), at);
                    Set<String> writable = new HashSet<>();
                    fact.path("writable").forEach(name -> writable.add(name.asText()));
                    int max = fact.path("max").asInt(1000);
                    fact.path("fields").fields().forEachRemaining(field -> {
                        if (next.path(field.getKey()).equals(before.path(key).path(field.getKey()))) return;
                        if (!writable.contains(field.getKey())) throw RuleJson.error(at + "/" + field.getKey(), "This actor fact is read-only.");
                        set(target, field.getValue().asText(), RuleExpression.numeric(
                                RuleJson.integer(next.get(field.getKey()), at + "/" + field.getKey(), 0, max), at));
                    });
                    fact.path("atLeast").fields().forEachRemaining(rule -> {
                        int floor = next.path(rule.getValue().asText()).asInt();
                        if (get(target, rule.getKey()).asInt() < floor) set(target, rule.getKey(), RuleExpression.numeric(floor, at));
                    });
                }
                case "collection" -> {
                    if (!fact.path("writable").asBoolean(true)) throw RuleJson.error(at, "This collection is read-only.");
                    writeCollection(sheet, fact, next, at);
                }
                case "engine" -> set(sheet, fact.path("path").asText(), next.deepCopy());
                default -> throw RuleJson.error(at, "This actor fact is read-only.");
            }
        });
        for (JsonNode mapping : spec.path("poolTracks")) {
            String pool = mapping.path("pool").asText(), trackName = mapping.path("track").asText();
            JsonNode previous = before.path("pools").path(pool), next = after.path("pools").path(pool);
            if (previous.equals(next)) continue;
            JsonNode track = after.path(trackName);
            if (!track.isObject()) throw RuleJson.error("/actor/" + trackName, "The mapped track is unavailable.");
            int capacity = RuleJson.integer(track.get("capacity"), "/actor/" + trackName + "/capacity", 0, 1_000_000);
            int value = RuleJson.integer(next.get("value"), "/actor/pools/" + pool + "/value", 0, capacity);
            if (RuleJson.integer(next.get("max"), "/actor/pools/" + pool + "/max", 0, 1_000_000) != capacity)
                throw RuleJson.error("/actor/pools/" + pool, "The pool capacity must match its mapped track.");
            if (!before.path(trackName).equals(track) && value != capacity - track.path("marked").asInt())
                throw RuleJson.error("/actor/" + trackName, "A mapped track and pool cannot be changed inconsistently.");
            JsonNode definition = spec.path("actor").path(trackName);
            set(sheet, definition.path("path").asText() + "." + definition.path("fields").path("marked").asText(),
                    RuleJson.JSON.getNodeFactory().numberNode(capacity - value));
            if (mapping.has("fullFlag") && value == 0 && previous.path("value").asInt() > 0) {
                JsonNode flag = spec.path("actor").path(mapping.path("fullFlag").asText());
                set(sheet, flag.path("path").asText(), RuleJson.JSON.getNodeFactory().booleanNode(true));
            }
        }
    }

    private void validateCapacityReserves(ObjectNode sheet, ObjectNode before, ObjectNode after) {
        spec.path("actor").fields().forEachRemaining(entry -> {
            JsonNode reserve = entry.getValue().path("capacityReserve");
            if (reserve.isMissingNode()) return;
            String name = entry.getKey(), capacity = reserve.path("capacity").asText(), counter = reserve.path("counter").asText();
            JsonNode oldTrack = before.path(name), newTrack = after.path(name);
            String at = "/actor/" + name + "/capacityReserve";
            JsonNode storedCounter = get(sheet, spec.path("actor").path(counter).path("path").asText());
            if (!storedCounter.isMissingNode()) RuleJson.integer(storedCounter, at + "/counter", 0, 1000);
            if (reserve.has("total")) {
                String field = reserve.path("total").asText();
                JsonNode rawTrack = get(sheet, entry.getValue().path("path").asText());
                JsonNode storedTotal = rawTrack.isObject()
                        ? get((ObjectNode) rawTrack, entry.getValue().path("fields").path(field).asText())
                        : rawTrack;
                if (!storedTotal.isMissingNode()) RuleJson.integer(storedTotal, at + "/total", 0, 1000);
            }
            if (oldTrack.path(capacity).equals(newTrack.path(capacity)) && before.path(counter).equals(after.path(counter))) return;
            RuleJson.object(oldTrack, at);
            RuleJson.object(newTrack, at);
            int oldCapacity = RuleJson.integer(oldTrack.get(capacity), at + "/capacity", 0, 1000);
            int newCapacity = RuleJson.integer(newTrack.get(capacity), at + "/capacity", 0, 1000);
            int oldCounter = RuleJson.integer(before.get(counter), at + "/counter", 0, 1000);
            int newCounter = RuleJson.integer(after.get(counter), at + "/counter", 0, 1000);
            int total = oldCapacity + oldCounter;
            if (total > 1000 || total != newCapacity + newCounter)
                throw RuleJson.error(at, "Capacity and its reserve must change together.");
            if (reserve.has("total")) {
                String field = reserve.path("total").asText();
                if (!oldTrack.path(field).equals(newTrack.path(field)))
                    throw RuleJson.error(at + "/total", "The base total cannot change with the reserve.");
                if (oldTrack.has(field) && RuleJson.integer(oldTrack.get(field), at + "/total", 0, 1000) != total)
                    throw RuleJson.error(at + "/total", "Capacity and reserve do not match the stored total.");
            }
            String filled = oldTrack.has("marked") ? "marked" : "value";
            RuleJson.integer(oldTrack.get(filled), at + "/" + filled, 0, oldCapacity);
            RuleJson.integer(newTrack.get(filled), at + "/" + filled, 0, newCapacity);
        });
    }

    private static void writeCollection(ObjectNode sheet, JsonNode fact, JsonNode next, String at) {
        if (!next.isArray()) throw RuleJson.error(at, "Expected a list.");
        JsonNode current = get(sheet, fact.path("path").asText());
        ArrayNode list = current.isArray() ? (ArrayNode) current : RuleJson.JSON.createArrayNode();
        Map<String, JsonNode> wanted = new LinkedHashMap<>();
        for (JsonNode entry : next) {
            String id = RuleJson.id(entry.get("id"), at + "/id");
            if (wanted.put(id, entry) != null) throw RuleJson.error(at, "Duplicate collection entry: " + id);
        }
        Map<String, Integer> seen = new HashMap<>();
        ArrayNode result = RuleJson.JSON.createArrayNode();
        for (JsonNode item : list) {
            ObjectNode mapped = collectionEntry(fact, item);
            if (mapped == null) { result.add(item); continue; }
            String id = mapped.path("id").asText();
            JsonNode target = wanted.get(id);
            if (target == null || seen.merge(id, 1, Integer::sum) > 1) continue;
            ObjectNode copy = ((ObjectNode) item).deepCopy();
            applyFields(fact, copy, target, mapped, at);
            result.add(copy);
        }
        for (var entry : wanted.entrySet()) {
            if (seen.containsKey(entry.getKey())) continue;
            ObjectNode created = fact.has("defaults") ? ((ObjectNode) fact.get("defaults")).deepCopy() : RuleJson.JSON.createObjectNode();
            applyFields(fact, created, entry.getValue(), RuleJson.JSON.createObjectNode(), at);
            result.add(created);
        }
        set(sheet, fact.path("path").asText(), result);
    }

    private static void applyFields(JsonNode fact, ObjectNode target, JsonNode entry, ObjectNode before, String at) {
        fact.path("fields").fields().forEachRemaining(field -> {
            JsonNode value = entry.path(field.getKey());
            if (value.isMissingNode() || value.equals(before.path(field.getKey()))) return;
            if (!value.isValueNode()) throw RuleJson.error(at + "/" + field.getKey(), "Collection fields must be plain values.");
            set(target, field.getValue().asText(), value.deepCopy());
        });
    }

    static JsonNode get(ObjectNode root, String path) {
        JsonNode node = root;
        for (String part : path.split("\\.")) node = node.path(part);
        return node;
    }

    static void set(ObjectNode root, String path, JsonNode value) {
        String[] parts = path.split("\\.");
        ObjectNode node = root;
        for (int i = 0; i < parts.length - 1; i++) {
            JsonNode child = node.get(parts[i]);
            node = child instanceof ObjectNode object ? object : node.putObject(parts[i]);
        }
        node.set(parts[parts.length - 1], value);
    }
}
