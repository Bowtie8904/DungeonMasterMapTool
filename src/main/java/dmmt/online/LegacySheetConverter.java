package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One-time conversion of a saved legacy character sheet to format-2 identities and state. It only fills in or moves
 * data (never re-grants creation benefits), so converting an already converted sheet changes nothing.
 */
final class LegacySheetConverter {
    /** {@code error} diagnostics block activation; notes describe data that was deliberately kept as it was. */
    record Diagnostic(String actorId, String path, boolean error, String message) { }

    /** Converted catalog entries by category and entry id. */
    static final class Catalogs {
        private final Map<String, Map<String, ObjectNode>> entries = new LinkedHashMap<>();

        void add(JsonNode catalog) {
            String category = catalog.path("category").asText();
            for (JsonNode entry : catalog.path("entries"))
                if (entry instanceof ObjectNode object && object.path("id").isTextual())
                    entries.computeIfAbsent(category, k -> new LinkedHashMap<>()).put(object.path("id").asText(), object);
        }

        ObjectNode entry(String category, String id) {
            return entries.getOrDefault(category, Map.of()).get(id);
        }

        List<ObjectNode> named(String category, String name) {
            List<ObjectNode> found = new ArrayList<>();
            for (ObjectNode entry : entries.getOrDefault(category, Map.of()).values())
                if (entry.path("name").asText().equals(name)) found.add(entry);
            return found;
        }
    }

    private static final List<String> FEATURE_LISTS = List.of("features", "heritage", "partyGrants");
    private static final List<String> CARD_LISTS = List.of("loadout", "vault", "borrowedCards");
    private static final List<String> WEAPON_LISTS = List.of("activeWeapons", "carriedWeapons");
    private static final List<String> ARMOR_LISTS = List.of("activeArmor", "carriedArmor");
    private static final Map<String, List<String>> USAGE_KINDS = Map.of("feat", FEATURE_LISTS, "card", CARD_LISTS,
            "weapon", WEAPON_LISTS, "armor", ARMOR_LISTS, "item", List.of("inventory"));

    private final String actorId;
    private final Catalogs catalogs;
    private final List<Diagnostic> diagnostics;
    private final ObjectNode sheet;
    /** Rows that now point at a catalog source, with the converted block that source runs. */
    private final Map<ObjectNode, JsonNode> resolved = new java.util.IdentityHashMap<>();

    private LegacySheetConverter(ObjectNode sheet, String actorId, Catalogs catalogs, List<Diagnostic> diagnostics) {
        this.sheet = sheet;
        this.actorId = actorId;
        this.catalogs = catalogs;
        this.diagnostics = diagnostics;
    }

    /** Returns the converted copy of {@code legacy}; diagnostics are appended to {@code diagnostics}. */
    static ObjectNode convert(ObjectNode legacy, String actorId, Catalogs catalogs, List<Diagnostic> diagnostics) {
        ObjectNode sheet = legacy.deepCopy();
        LegacySheetConverter converter = new LegacySheetConverter(sheet, actorId, catalogs, diagnostics);
        converter.identities();
        converter.usage();
        converter.effects();
        return sheet;
    }

    private void error(String path, String message) { diagnostics.add(new Diagnostic(actorId, path, true, message)); }

    private void note(String path, String message) { diagnostics.add(new Diagnostic(actorId, path, false, message)); }

    private static String uuid(String... parts) {
        return UUID.nameUUIDFromBytes(String.join("|", parts).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static boolean wellFormed(JsonNode id) {
        return id != null && id.isTextual() && id.asText().matches("[a-zA-Z0-9][a-zA-Z0-9_.:-]{0,127}");
    }

    // ---- identities ----

    private void identities() {
        for (String list : FEATURE_LISTS) rows(list, (row, at) -> feature(row, list, at));
        for (String list : CARD_LISTS) rows(list, (row, at) -> card(row, list, at));
        for (String list : WEAPON_LISTS) rows(list, (row, at) -> equipment(row, list, at, "weapons"));
        for (String list : ARMOR_LISTS) rows(list, (row, at) -> equipment(row, list, at, "armor"));
        rows("inventory", (row, at) -> inventory(row, at));
        for (String list : List.of("activeWeapons", "activeArmor"))
            rows(list, (row, at) -> {
                JsonNode attachments = row.path("attachments");
                for (int i = 0; i < attachments.size(); i++)
                    if (attachments.get(i) instanceof ObjectNode attachment && attachment.path("item") instanceof ObjectNode item)
                        equipment(item, list, at + "/attachments/" + i + "/item", "items");
            });
    }

    private interface Visitor { void visit(ObjectNode row, String path); }

    private void rows(String list, Visitor visitor) {
        JsonNode value = sheet.get(list);
        if (value instanceof ObjectNode single) visitor.visit(single, "/" + list);
        else if (value instanceof ArrayNode array)
            for (int i = 0; i < array.size(); i++)
                if (array.get(i) instanceof ObjectNode row) visitor.visit(row, "/" + list + "/" + i);
    }

    private static boolean edited(ObjectNode row) {
        JsonNode baseline = row.path("catalog").path("values");
        return row.has("automation") && baseline.isObject() && !row.get("automation").equals(baseline.get("automation"));
    }

    private void bind(ObjectNode row, String path, String source, JsonNode block) {
        if (edited(row)) {
            note(path, "Edited gear keeps its own legacy automation and is not linked to the catalog source '" + source + "'.");
            return;
        }
        if (row.hasNonNull("automationSource") && !source.equals(row.path("automationSource").asText())) {
            error(path, "The row already points at '" + row.path("automationSource").asText() + "' but resolves to '"
                    + source + "'. Repair the link by hand.");
            return;
        }
        row.put("automationSource", source);
        if (!wellFormed(row.get("instanceId"))) row.put("instanceId", uuid(actorId, path, source));
        if (block != null && block.isObject()) resolved.put(row, block);
        row.remove("automation");
    }

    private void feature(ObjectNode row, String list, String path) {
        JsonNode link = row.path("catalog");
        if (!link.isObject() || !link.has("category") || !link.has("id")) {
            if (row.hasNonNull("automationSource") && !wellFormed(row.get("instanceId")))
                row.put("instanceId", uuid(actorId, path, row.path("automationSource").asText()));
            return;
        }
        String category = link.path("category").asText(), id = link.path("id").asText();
        ObjectNode entry = catalogs.entry(category, id);
        if (entry == null) {
            error(path, "The catalog entry '" + category + ":" + id + "' no longer exists. Restore it or unlink the row.");
            return;
        }
        if (!link.has("featureId") && !link.has("feature") && !entry.path("features").isArray()) {
            bind(row, path, category + ":" + id, entry.get("automation"));
            return;
        }
        JsonNode chosen = null;
        String wanted = link.path("featureId").asText("");
        List<JsonNode> matches = new ArrayList<>();
        for (JsonNode feature : entry.path("features")) if (!wanted.isEmpty() && wanted.equals(feature.path("_catalogFeatureId").asText())) matches.add(feature);
        if (matches.isEmpty() && link.path("feature").isInt()) {
            JsonNode byIndex = entry.path("features").path(link.path("feature").asInt());
            if (!byIndex.isMissingNode()) matches.add(byIndex);
        }
        if (matches.isEmpty()) {
            String name = row.path("name").asText();
            for (JsonNode feature : entry.path("features")) if (feature.path("name").asText().equals(name)) matches.add(feature);
        }
        if (matches.size() > 1) {
            error(path, "'" + row.path("name").asText() + "' matches " + matches.size() + " features of '" + category + ":" + id
                    + "'. Set catalog.featureId to the intended feature.");
            return;
        }
        if (matches.isEmpty()) {
            error(path, "No feature of '" + category + ":" + id + "' matches '" + row.path("name").asText()
                    + "'. Restore the feature or unlink the row.");
            return;
        }
        chosen = matches.get(0);
        String featureId = chosen.path("_catalogFeatureId").asText();
        if (featureId.isBlank()) featureId = CatalogEditor.featureId(entry, chosen, "features", indexOf(entry.path("features"), chosen));
        ((ObjectNode) link).put("featureId", featureId);
        ((ObjectNode) link).remove("feature");
        if (chosen.path("id").isTextual()) bind(row, path, category + ":" + id + ":features:" + chosen.path("id").asText(), chosen.get("automation"));
    }

    private static int indexOf(JsonNode list, JsonNode member) {
        for (int i = 0; i < list.size(); i++) if (list.get(i) == member) return i;
        return -1;
    }

    private void card(ObjectNode row, String list, String path) {
        JsonNode link = row.path("catalog");
        ObjectNode entry = null;
        if (link.isObject() && link.has("id")) {
            entry = catalogs.entry(link.path("category").asText("domain-cards"), link.path("id").asText());
            if (entry == null) {
                error(path, "The card '" + link.path("id").asText() + "' no longer exists in the catalog. Restore it or unlink the row.");
                return;
            }
        } else if (row.hasNonNull("automationSource") && row.path("automationSource").asText().startsWith("domain-cards:")) {
            entry = catalogs.entry("domain-cards", row.path("automationSource").asText().substring("domain-cards:".length()));
        } else {
            List<ObjectNode> named = catalogs.named("domain-cards", row.path("name").asText());
            if (named.size() > 1) {
                error(path, "The card '" + row.path("name").asText() + "' matches " + named.size()
                        + " catalog cards. Add a catalog link with the intended id.");
                return;
            }
            if (named.isEmpty()) {
                note(path, "The card '" + row.path("name").asText() + "' is not in the catalog and was kept as a custom card.");
                return;
            }
            entry = named.get(0);
        }
        if (!(row.get("catalog") instanceof ObjectNode)) row.putObject("catalog");
        ((ObjectNode) row.get("catalog")).put("category", "domain-cards").put("id", entry.path("id").asText());
        bind(row, path, "domain-cards:" + entry.path("id").asText(), entry.get("automation"));
        JsonNode spells = row.path("spells");
        for (int i = 0; i < spells.size(); i++) {
            if (!(spells.get(i) instanceof ObjectNode spell) || wellFormed(spell.get("id"))) continue;
            String name = spell.path("name").asText();
            List<JsonNode> matches = new ArrayList<>();
            for (JsonNode catalogSpell : entry.path("spells")) if (catalogSpell.path("name").asText().equals(name)) matches.add(catalogSpell);
            if (matches.size() > 1) {
                error(path + "/spells/" + i, "The spell '" + name + "' matches " + matches.size() + " spells of the card. Rename one of them.");
                continue;
            }
            String id = matches.size() == 1 && !matches.get(0).path("_catalogFeatureId").asText().isBlank()
                    ? matches.get(0).path("_catalogFeatureId").asText() : uuid(actorId, path, name);
            spell.put("id", id);
        }
    }

    private void equipment(ObjectNode row, String list, String path, String category) {
        JsonNode link = row.path("catalog");
        if (!link.isObject() || !link.has("id")) return;
        String linkCategory = link.path("category").asText(category);
        ObjectNode entry = catalogs.entry(linkCategory, link.path("id").asText());
        if (entry == null) {
            if (row.path("custom").asBoolean() || row.has("generated")) return;
            error(path, "The catalog entry '" + linkCategory + ":" + link.path("id").asText() + "' no longer exists. Restore it or unlink the row.");
            return;
        }
        bind(row, path, linkCategory + ":" + entry.path("id").asText(), entry.get("automation"));
    }

    private void inventory(ObjectNode row, String path) {
        if (row.path("custom").asBoolean()) return;
        equipment(row, "inventory", path, "items");
    }

    // ---- spent limits ----

    private ObjectNode sourceState(ObjectNode row) {
        ObjectNode state = sheet.has("automationState") && sheet.get("automationState") instanceof ObjectNode existing
                ? existing : sheet.putObject("automationState");
        if (!state.path("revision").isIntegralNumber()) state.put("revision", 0);
        ObjectNode sources = state.get("sources") instanceof ObjectNode existing ? existing : state.putObject("sources");
        String instanceId = row.path("instanceId").asText();
        return sources.get(instanceId) instanceof ObjectNode existing ? existing : sources.putObject(instanceId);
    }

    private void usage() {
        if (!(sheet.get("usage") instanceof ObjectNode usage)) return;
        usage.fields().forEachRemaining(counter -> {
            String key = counter.getKey();
            int used = counter.getValue().path("used").asInt();
            int colon = key.indexOf(':');
            if (colon < 0 || used <= 0) return;
            List<String> lists = USAGE_KINDS.get(key.substring(0, colon));
            if (lists == null) return;
            String rest = key.substring(colon + 1);
            List<ObjectNode> matches = new ArrayList<>();
            String option = "";
            for (String list : lists) {
                for (int split = rest.length(); split > 0; split = rest.lastIndexOf(':', split - 1)) {
                    String name = rest.substring(0, split);
                    List<ObjectNode> found = new ArrayList<>();
                    rows(list, (row, at) -> { if (name.equals(row.path("name").asText()) && resolved.containsKey(row)) found.add(row); });
                    if (!found.isEmpty()) {
                        matches.addAll(found);
                        option = split == rest.length() ? "" : rest.substring(split + 1);
                        break;
                    }
                }
            }
            if (matches.isEmpty()) {
                note("/usage/" + key, "Spent use of '" + key + "' belongs to a row without a catalog source and stays in the legacy counter.");
                return;
            }
            if (matches.size() > 1) {
                error("/usage/" + key, "The spent use of '" + key + "' matches " + matches.size() + " rows. Remove the duplicate rows.");
                return;
            }
            ObjectNode row = matches.get(0);
            JsonNode limits = resolved.get(row).path("limits");
            String limit = null;
            if (limits.size() == 1) limit = limits.fieldNames().next();
            else if (!option.isEmpty() && limits.has(option)) limit = option;
            else if (option.isEmpty() && limits.has("uses")) limit = "uses";
            if (limit == null) {
                error("/usage/" + key, "Cannot tell which limit of '" + row.path("automationSource").asText() + "' the spent use of '" + key
                        + "' belongs to (" + limits.size() + " limits).");
                return;
            }
            ObjectNode state = sourceState(row);
            ObjectNode converted = state.get("usage") instanceof ObjectNode existing ? existing : state.putObject("usage");
            converted.put(limit, Math.max(converted.path(limit).asInt(), used));
        });
    }

    // ---- active effects ----

    private static final Map<String, List<String>> UNTIL = Map.of("rest", List.of("rest.short", "rest.long"),
            "shortRest", List.of("rest.short"), "longRest", List.of("rest.long"), "scene", List.of("scene.end"),
            "session", List.of("session.start"), "manual", List.of());

    private ObjectNode state() {
        ObjectNode state = sheet.get("automationState") instanceof ObjectNode existing ? existing : sheet.putObject("automationState");
        if (!state.path("revision").isIntegralNumber()) state.put("revision", 0);
        return state;
    }

    private void effects() {
        if (!(sheet.get("effects") instanceof ArrayNode effects)) return;
        ArrayNode kept = effects.arrayNode();
        for (int i = 0; i < effects.size(); i++) {
            JsonNode effect = effects.get(i);
            String at = "/effects/" + i;
            if (!(effect instanceof ObjectNode legacy)) { kept.add(effect); continue; }
            backfillSource(legacy);
            if (legacy.path("lockedHp").isNumber() && legacy.path("lockedHp").asInt() > 0) {
                if (lock(legacy, at)) continue;
            } else if (legacy.path("deathrun").asBoolean() && legacy.has("deathrunRoll")) {
                if (deathrun(legacy, at)) continue;
            } else if (legacy.has("dicePool")) {
                note(at, "Prayer Dice '" + legacy.path("id").asText() + "' keep their values and spent state as legacy effect data.");
            }
            kept.add(legacy);
        }
        effects.removeAll();
        effects.addAll(kept);
    }

    /** An effect that names one of this sheet's own rows by source key was started by this character. */
    private void backfillSource(ObjectNode effect) {
        if (wellFormed(effect.get("sourceActorId"))) return;
        String key = effect.path("sourceKey").asText("");
        int colon = key.indexOf(':');
        if (colon < 0) return;
        List<String> lists = USAGE_KINDS.get(key.substring(0, colon));
        if (lists == null) return;
        String name = key.substring(colon + 1);
        int owned = 0;
        for (String list : lists) {
            int[] count = {0};
            rows(list, (row, at) -> {
                String rowName = row.path("name").asText();
                if (name.equals(rowName) || name.startsWith(rowName + ":")) count[0]++;
            });
            owned += count[0];
        }
        if (owned == 1) effect.put("sourceActorId", actorId);
    }

    private boolean lock(ObjectNode legacy, String at) {
        if (!wellFormed(legacy.get("sourceActorId"))) {
            error(at, "The HP lock '" + legacy.path("name").asText() + "' has no source character and none can be derived. Set its sourceActorId.");
            return false;
        }
        ObjectNode converted = state().withArray("effects").addObject();
        String id = wellFormed(legacy.get("id")) ? legacy.path("id").asText() : uuid(actorId, at, legacy.path("name").asText());
        converted.put("id", id).put("effect", "legacy:locked-hp").put("sourceInstanceId", "legacy:" + actorId)
                .put("sourceActorId", legacy.path("sourceActorId").asText())
                .put("label", legacy.path("name").asText(legacy.path("source").asText("Locked HP")))
                .put("kind", legacy.path("kind").asText("debuff"));
        if (legacy.has("text")) converted.put("text", legacy.path("text").asText());
        ArrayNode until = converted.putArray("until");
        List<String> events = UNTIL.get(legacy.path("until").asText("manual"));
        if (events == null) { error(at + "/until", "Unknown legacy duration '" + legacy.path("until").asText() + "'."); return false; }
        events.forEach(until::add);
        converted.putArray("modifiers");
        converted.putObject("locks").put("hp", legacy.path("lockedHp").asInt());
        return true;
    }

    private boolean deathrun(ObjectNode legacy, String at) {
        List<ObjectNode> sources = new ArrayList<>();
        resolved.forEach((row, block) -> { if (block.path("state").has("originalEligibleDice")) sources.add(row); });
        if (sources.size() != 1) {
            error(at, sources.isEmpty() ? "An active Deathrun has no Deathrun source on the sheet. Restore the feature or end the run."
                    : "An active Deathrun matches " + sources.size() + " sources. Remove the duplicate feature.");
            return false;
        }
        JsonNode roll = legacy.path("deathrunRoll");
        JsonNode weaponName = roll.path("weapon");
        String name = weaponName.isObject() ? weaponName.path("name").asText() : weaponName.asText();
        List<ObjectNode> weapons = new ArrayList<>();
        rows("activeWeapons", (row, path) -> { if (row.path("name").asText().equals(name) && wellFormed(row.get("instanceId"))) weapons.add(row); });
        if (weapons.size() != 1) {
            error(at, "The Deathrun weapon '" + name + "' is " + (weapons.isEmpty() ? "no longer equipped" : "equipped more than once")
                    + ". Equip it once to convert the run.");
            return false;
        }
        ObjectNode state = sourceState(sources.get(0)).putObject("state");
        ArrayNode eligible = state.putArray("originalEligibleDice");
        for (int i = 0; i < roll.path("rolls").size(); i++) eligible.add(i);
        ArrayNode remaining = state.putArray("remainingDice");
        for (JsonNode index : legacy.path("deathrunRemaining")) remaining.add(index.asInt());
        int uses = legacy.path("counter").path("value").asInt();
        state.put("phase", "active").put("activation", Math.max(1, uses)).put("uses", uses)
                .put("weaponId", weapons.get(0).path("instanceId").asText()).put("firstDamage", roll.path("total").asInt());
        return true;
    }
}
