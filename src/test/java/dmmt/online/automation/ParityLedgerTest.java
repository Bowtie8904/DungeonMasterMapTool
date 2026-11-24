package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replays every ledger row through the converted catalogs and compares the result with the legacy engine's captured
 * behavior (ledger-baseline.json, written by web/src/daggerheart/parity-capture.test.ts). Dice always show
 * min(3, sides) so totals are comparable between the engines.
 */
class ParityLedgerTest {
    private static final AutomationHost HOST = AutomationHost.bundled();
    private static final List<String> CATEGORIES = List.of("ancestries", "armor", "classes", "communities", "consumables",
            "domain-cards", "items", "transformations", "weapons");

    private record Row(String category, String pointer) {
        String key() { return category + pointer; }
    }

    private record Placed(ObjectNode sheet, String sourceId) {}

    static Random constantDice() {
        return new Random(0) {
            @Override public int nextInt(int bound) { return Math.min(3, bound) - 1; }
        };
    }

    private static List<Row> ledger() throws Exception {
        List<Row> rows = new ArrayList<>();
        List<String> lines = Files.readAllLines(Path.of("docs/automation-parity-ledger.csv"));
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) continue;
            String[] cells = line.split("\",\"", 3);
            rows.add(new Row(cells[0].substring(1), cells[1]));
        }
        return rows;
    }

    private static ObjectNode standardSheet() {
        ObjectNode sheet = RuleJson.JSON.createObjectNode().put("name", "Parity").put("level", 5).put("evasion", 10).put("gold", 20);
        sheet.putObject("hope").put("slots", 6).put("total", 6).put("on", 3);
        sheet.putObject("hp").put("slots", 6).put("total", 12).put("on", 3);
        sheet.putObject("stress").put("slots", 6).put("total", 12).put("on", 3);
        sheet.putObject("armorTrack").put("slots", 4).put("total", 12).put("on", 2);
        sheet.putObject("proficiency").put("slots", 6).put("total", 6).put("on", 2);
        ArrayNode traits = sheet.putArray("traits");
        for (String trait : List.of("Agility", "Strength", "Finesse", "Instinct", "Presence", "Knowledge"))
            traits.addObject().put("name", trait).put("mod", 1).put("marked", false).put("spellcast", "Instinct".equals(trait));
        for (String list : List.of("features", "heritage", "loadout", "vault", "inventory", "activeWeapons")) sheet.putArray(list);
        sheet.put("ancestry", "Human").put("ancestryFeature", "High Stamina");
        return sheet;
    }

    private static Placed place(Row row, JsonNode catalog) {
        String[] parts = row.pointer().substring(1).split("/");
        JsonNode entry = catalog.path("entries").get(Integer.parseInt(parts[1]));
        ObjectNode sheet = standardSheet();
        String id = row.category() + ":" + entry.path("id").asText();
        if ("automation".equals(parts[2])) {
            switch (row.category()) {
                case "armor" -> {
                    ObjectNode armor = sheet.putObject("activeArmor").put("name", entry.path("name").asText())
                            .put("score", entry.at("/armor/score").asInt()).put("tier", entry.path("tier").asInt(1))
                            .put("major", entry.at("/armor/major").asInt()).put("severe", entry.at("/armor/severe").asInt())
                            .put("automationSource", id).put("instanceId", "owner");
                    if (entry.has("feature")) armor.set("feature", entry.get("feature"));
                }
                case "weapons" -> {
                    JsonNode weapon = entry.path("weapon");
                    ObjectNode w = sheet.withArray("activeWeapons").addObject().put("name", entry.path("name").asText())
                            .put("slot", "secondary".equals(weapon.path("slot").asText()) ? "Secondary" : "Primary")
                            .put("trait", weapon.path("trait").asText()).put("range", weapon.path("range").asText())
                            .put("tier", entry.path("tier").asInt(1)).put("automationSource", id).put("instanceId", "owner")
                            .put("damageType", weapon.path("damage").path("type").asText("physical"))
                            .put("damage", weapon.path("damage").path("dice").asText("1d8") + (weapon.path("damage").path("bonus").asInt() == 0 ? "" : "+" + weapon.path("damage").path("bonus").asInt()));
                    if (entry.has("feature")) w.set("feature", entry.get("feature"));
                }
                case "items", "consumables" -> sheet.withArray("inventory").addObject().put("name", entry.path("name").asText())
                        .put("qty", 2).put("type", "consumables".equals(row.category()) ? "consumable" : "gear")
                        .put("automationSource", id).put("instanceId", "owner");
                case "domain-cards" -> sheet.withArray("loadout").add(card(entry, id, "owner"));
                default -> throw new IllegalStateException(row.key());
            }
            return new Placed(sheet, id);
        }
        if ("spells".equals(parts[2])) {
            sheet.withArray("loadout").add(card(entry, id, "card"));
            JsonNode spell = entry.path("spells").get(Integer.parseInt(parts[3]));
            return new Placed(sheet, id + ":spells:" + spell.path("id").asText());
        }
        JsonNode feature = entry.path("features").get(Integer.parseInt(parts[3]));
        sheet.withArray("classes".equals(row.category()) ? "features" : "heritage").addObject()
                .put("source", "parity").put("name", feature.path("name").asText()).put("text", "")
                .put("automationSource", id + ":features:" + feature.path("id").asText()).put("instanceId", "owner");
        return new Placed(sheet, id + ":features:" + feature.path("id").asText());
    }

    private static ObjectNode card(JsonNode entry, String id, String instance) {
        ObjectNode card = RuleJson.JSON.createObjectNode().put("level", entry.path("level").asInt(1))
                .put("domain", entry.path("domain").asText()).put("type", entry.path("cardType").asText("Ability"))
                .put("name", entry.path("name").asText()).put("text", entry.path("text").asText())
                .put("automationSource", id).put("instanceId", instance);
        if (entry.has("spells")) card.set("spells", entry.get("spells").deepCopy());
        return card;
    }

    @Test
    void everyLedgerRowMatchesTheLegacyBaseline(@TempDir Path staging) throws Exception {
        LegacyCatalogConverter.main(new String[]{"dmmap-daggerheart", staging.toString()});
        Map<String, String> texts = new LinkedHashMap<>();
        Map<String, JsonNode> catalogs = new LinkedHashMap<>();
        for (String category : CATEGORIES) {
            String text = Files.readString(staging.resolve(category + ".json"));
            texts.put(category, text);
            catalogs.put(category, new com.fasterxml.jackson.databind.ObjectMapper().readTree(text));
        }
        var pack = AutomationHost.compile("parity", texts, LegacyCatalogConverter.coreRecipes(), LegacyCatalogConverter.coreRuleset());
        JsonNode baseline = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(Files.readString(Path.of("src/test/resources/dmmt/automation/parity/ledger-baseline.json"))).path("rows");
        Map<String, List<String>> failures = new TreeMap<>();
        int rows = 0;
        for (Row row : ledger()) {
            rows++;
            JsonNode expected = baseline.path(row.key());
            Placed placed = place(row, catalogs.get(row.category()));
            currentAutomation = catalogs.get(row.category()).at(row.pointer());
            String statProblem = statsMismatch(pack, placed, expected.path("stats"));
            if (statProblem != null) failures.computeIfAbsent(row.key(), k -> new ArrayList<>()).add("stats: " + statProblem);
            String restProblem = restMismatch(pack, placed, expected.path("rest"));
            if (restProblem != null) failures.computeIfAbsent(row.key(), k -> new ArrayList<>()).add("rest: " + restProblem);
            for (var action : expected.path("actions").properties()) {
                String problem = actionMismatch(pack, placed, action.getKey(), action.getValue());
                if (problem != null) failures.computeIfAbsent(row.key(), k -> new ArrayList<>()).add(action.getKey() + ": " + problem);
            }
        }
        StringBuilder report = new StringBuilder();
        Map<String, Integer> reasons = new TreeMap<>();
        failures.forEach((row, list) -> list.forEach(item -> {
            report.append(row).append(" | ").append(item).append('\n');
            reasons.merge(item.substring(item.indexOf(": ") + 2).replaceAll("[0-9]+", "#"), 1, Integer::sum);
        }));
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "parity-report.txt"), report.toString());
        reasons.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(25)
                .forEach(e -> System.out.println(e.getValue() + "  " + e.getKey()));
        System.out.println("rows=" + rows + " failingRows=" + failures.size());
        assertTrue(failures.isEmpty(), failures.size() + " ledger rows differ from the legacy baseline; see target/parity-report.txt");
    }

    private static ObjectNode statsOf(AutomationHost.Pack pack, ObjectNode sheet) {
        JsonNode stats = HOST.recipientSnapshot(pack, sheet, AutomationHost.event("action.request", "parity", 0), "parity").path("stats");
        return RuleJson.JSON.createObjectNode().put("evasion", stats.path("evasion").asInt())
                .put("major", stats.at("/thresholds/major").asInt()).put("severe", stats.at("/thresholds/severe").asInt())
                .put("armorSlots", stats.path("armorScore").asInt(sheet.at("/activeArmor/score").asInt()))
                .put("loadoutMax", stats.at("/loadout/max").asInt(5));
    }

    private static String statsMismatch(AutomationHost.Pack pack, Placed placed, JsonNode expected) {
        ObjectNode base = statsOf(pack, standardSheet());
        ObjectNode actual = statsOf(pack, placed.sheet());
        for (String key : List.of("evasion", "major", "severe", "armorSlots", "loadoutMax")) {
            int want = expected.path(key).asInt();
            int got = actual.path(key).asInt() - base.path(key).asInt();
            if (want != got) return "stat " + key + " legacy=" + want + " new=" + got + " stats=" + HOST.recipientSnapshot(pack, placed.sheet(), AutomationHost.event("action.request", "parity", 0), "parity").path("stats");
        }
        return null;
    }

    /** Spends every declared limit, rests, and compares whether any use of the source survives with the legacy result. */
    private static String restMismatch(AutomationHost.Pack pack, Placed placed, JsonNode expected) {
        RuleCompiler.Program program = pack.programs().get(placed.sourceId());
        boolean legacyHasUsage = expected.path("short").path("usageBefore").size() > 0;
        if (program == null || program.limits().isEmpty()) return legacyHasUsage && !expected.path("short").path("usageBefore").fieldNames().next().isEmpty() && program == null ? "no program for the source" : null;
        for (String kind : List.of("short", "long")) {
            ObjectNode sheet = placed.sheet().deepCopy();
            ObjectNode state = HOST.projection().state(sheet);
            ObjectNode sources = state.withObject("/sources");
            String instanceName = "owner";
            for (var element : HOST.sources(pack, sheet)) if (element.sourceId().equals(placed.sourceId())) instanceName = element.instanceId();
            for (String instance : List.of(instanceName)) {
                ObjectNode usage = sources.withObject("/" + instance).withObject("/usage");
                program.limits().properties().forEach(limit -> usage.put(limit.getKey(), limit.getValue().path("max").asInt(1)));
            }
            ObjectNode origin = AutomationHost.event("action.request", "parity", 1).put("sessionId", "session");
            try {
                var snapshot = HOST.restEffectSnapshot(sheet);
                ObjectNode prepared = HOST.prepareRest(pack, sheet, origin, kind, 0, RuleJson.JSON.createArrayNode(), constantDice());
                var progress = HOST.restProgress(pack, prepared);
                HOST.completeRest(pack, sheet, origin, progress, snapshot, RuleJson.JSON.createArrayNode(), constantDice());
            } catch (RuntimeException e) {
                return kind + " rest rejected: " + e.getMessage();
            }
            boolean remains = false;
            for (JsonNode source : HOST.projection().state(sheet).path("sources"))
                for (JsonNode used : source.path("usage")) if (used.asInt() > 0) remains = true;
            boolean legacyRemains = expected.path(kind).path("usageAfter").size() > 0;
            if (legacyHasUsage && remains != legacyRemains)
                return kind + " rest: legacy keeps uses=" + legacyRemains + " new keeps uses=" + remains + " " + HOST.projection().state(sheet).path("sources");
        }
        return null;
    }

    private static JsonNode currentAutomation;

    /** Runs a downtime-surface rule through a rest move; returns "no-downtime" when the rule has no such binding. */
    private static String downtimeMismatch(AutomationHost.Pack pack, Placed placed, ObjectNode sheet, String actionId) {
        JsonNode rule = null;
        for (JsonNode candidate : currentAutomation.path("rules"))
            if (actionId.equals(candidate.path("id").asText())) rule = candidate;
        if (rule == null) return "no-downtime";
        String bindingId = null;
        for (JsonNode binding : rule.path("bindings"))
            if ("downtime.move".equals(binding.path("event").asText()) && "button".equals(binding.path("mode").asText())) bindingId = binding.path("id").asText();
        if (bindingId == null) return "no-downtime";
        String instanceId = "owner";
        ObjectNode origin = AutomationHost.event("action.request", "parity", 0).put("sessionId", "session");
        try {
            ObjectNode prepared = HOST.prepareRest(pack, sheet, origin, "short", 0, RuleJson.JSON.createArrayNode(), constantDice());
            var progress = HOST.restProgress(pack, prepared);
            ObjectNode selection = RuleJson.JSON.createObjectNode().put("sourceInstanceId", instanceId)
                    .put("ruleId", actionId).put("bindingId", bindingId);
            ObjectNode inputs = selection.putObject("inputs");
            for (JsonNode input : rule.path("inputs")) {
                String id = input.path("id").asText();
                switch (input.path("kind").asText()) {
                    case "record.select" -> {
                        JsonNode pick = input.path("from").path("literal").path(0);
                        for (JsonNode option : input.path("from").path("literal"))
                            if ("Human".equals(option.path("ancestry").asText()) && !"High Stamina".equals(option.path("feature").asText())) pick = option;
                        inputs.set(id, pick);
                    }
                    case "integer" -> inputs.put(id, input.path("min").asInt());
                    default -> { }
                }
            }
            HOST.restMove(pack, sheet, (ObjectNode) prepared.get("event"), progress, selection,
                    RuleJson.JSON.createArrayNode(), constantDice());
        } catch (RuntimeException e) {
            return "downtime rejected: " + e.getMessage();
        }
        return null;
    }

    private static String actionMismatch(AutomationHost.Pack pack, Placed placed, String actionId, JsonNode expected) {
        ObjectNode sheet = placed.sheet().deepCopy();
        ObjectNode event = AutomationHost.event("action.request", "parity", 1);
        ObjectNode ally = standardSheet();
        ally.withArray("loadout").addObject().put("level", 3).put("domain", "Arcana").put("type", "Ability")
                .put("name", "AllyCard").put("text", "").put("automationSource", "domain-cards:ally-card");
        sheet.withArray("loadout").addObject().put("level", 1).put("domain", "Arcana").put("type", "Ability")
                .put("name", "OwnCard").put("text", "");
        HOST.synchronizeBorrowedCards(pack, Map.of("parity", sheet, "ally", ally), Map.of("parity", "Parity", "ally", "Ally"));
        PartyTargets party = new PartyTargets(pack.revision(), sheet.toString(), scope -> List.of(
                new PartyTargets.Member("parity", "Parity", sheet.toString(), HOST.revision(sheet),
                        recipientEvent -> HOST.recipientSnapshot(pack, sheet, recipientEvent, "parity")),
                new PartyTargets.Member("ally", "Ally", ally.toString(), HOST.revision(ally),
                        recipientEvent -> HOST.recipientSnapshot(pack, ally, recipientEvent, "ally"))));
        JsonNode entry = null;
        try {
            for (JsonNode candidate : HOST.describe(pack, sheet, event, "sheet", "self", party).path("actions"))
                if (actionId.equals(candidate.path("ruleId").asText()) && "sheet".equals(candidate.path("bindingId").asText())
                        && placed.sourceId().equals(candidate.path("sourceId").asText())) entry = candidate;
        } catch (RuntimeException e) {
            return "describe failed: " + e.getMessage();
        }
        if (entry == null && "use".equals(actionId)) {
            for (JsonNode candidate : HOST.describe(pack, sheet, event, "sheet", "self", party).path("actions"))
                if ("run.start".equals(candidate.path("ruleId").asText()) && placed.sourceId().equals(candidate.path("sourceId").asText())) entry = candidate;
            if (entry != null) actionId = "run.start";
        }
        boolean legacyBlocked = expected.path("blocked").asBoolean();
        if (entry == null && !legacyBlocked) {
            String downtime = downtimeMismatch(pack, placed, sheet, actionId);
            if (!"no-downtime".equals(downtime)) return downtime;
        }
        if (entry == null && legacyBlocked) return null;
        if (entry == null) {
            StringBuilder offered = new StringBuilder();
            try {
                for (JsonNode candidate : HOST.describe(pack, sheet, event, "sheet", "self", party).path("actions"))
                    if (placed.sourceId().equals(candidate.path("sourceId").asText())) offered.append(candidate.path("ruleId").asText()).append(' ');
            } catch (RuntimeException ignored) { }
            return "not offered by the new engine (offered: " + offered + ")";
        }
        ObjectNode request = RuleJson.JSON.createObjectNode().put("requestId", "r").put("expectedRevision", HOST.revision(sheet));
        ObjectNode selection = request.putArray("selections").addObject()
                .put("sourceInstanceId", entry.path("sourceInstanceId").asText()).put("ruleId", actionId).put("bindingId", "sheet");
        ObjectNode inputs = selection.putObject("inputs");
        for (JsonNode input : entry.path("inputs")) {
            String id = input.path("id").asText();
            switch (input.path("kind").asText()) {
                case "integer" -> inputs.put(id, expected.has("amount") ? Math.max(input.path("min").asInt(), expected.get("amount").asInt())
                        : input.path("min").asInt());
                case "choice" -> {
                    int count = input.path("count").asInt(1);
                    if (count == 1) inputs.put(id, input.path("options").path(0).path("id").asText());
                    else {
                        ArrayNode picked = inputs.putArray(id);
                        for (int i = 0; i < count; i++) picked.add(input.path("options").path(i).path("id").asText());
                    }
                }
                case "record.select" -> inputs.set(id, input.path("options").path(0).path("value"));
                default -> { }
            }
        }
        if (entry.has("targetSelection")) {
            JsonNode offer = entry.path("targetSelection");
            ObjectNode target = request.putObject("target");
            if ("all".equals(offer.path("cardinality").asText()) || !offer.has("options"))
                target.put("version", offer.path("version").asText());
            else {
                JsonNode option = offer.path("options").path(0);
                for (JsonNode candidate : offer.path("options")) if ("parity".equals(candidate.path("id").asText())) option = candidate;
                if (option.isMissingNode()) return "no target offered";
                target.put("recipientId", option.path("id").asText()).put("version", option.path("version").asText());
            }
        }
        try {
            HOST.execute(pack, sheet, event, request, "self", "self", constantDice(), party);
        } catch (RuntimeException e) {
            if (legacyBlocked) return null;
            StringBuilder where = new StringBuilder();
            for (StackTraceElement frame : e.getStackTrace())
                if (where.length() < 400 && frame.getClassName().startsWith("dmmt")) where.append(frame.getClassName().substring(frame.getClassName().lastIndexOf('.') + 1)).append('.').append(frame.getMethodName()).append(':').append(frame.getLineNumber()).append(' ');
            return "rejected: " + e.getMessage() + " @ " + where;
        }
        if (legacyBlocked) return "accepted although legacy blocked (" + expected.path("blocker").asText() + ")";
        JsonNode after = expected.path("after");
        JsonNode spend = expected.path("delegatedDelta");
        ObjectNode actual = RuleJson.JSON.createObjectNode().put("hope", sheet.at("/hope/on").asInt()).put("hp", sheet.at("/hp/on").asInt())
                .put("stress", sheet.at("/stress/on").asInt()).put("armor", sheet.at("/armorTrack/on").asInt()).put("gold", sheet.path("gold").asInt());
        for (String field : List.of("hope", "hp", "stress", "armor", "gold")) {
            int want = after.path(field).asInt() + spend.path(field).asInt();
            if (want != actual.path(field).asInt())
                return "state " + field + " legacy=" + want + " new=" + actual.path(field).asInt();
        }
        return null;
    }
}

