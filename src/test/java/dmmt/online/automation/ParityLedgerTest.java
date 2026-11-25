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
            private int calls;
            // Chains that roll until a lower result would never end on constant dice, so late dice come up as 1.
            @Override public int nextInt(int bound) { return ++calls > 40 ? 0 : Math.min(3, bound) - 1; }
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
        Map<String, JsonNode> catalogs = currentCatalogs = new LinkedHashMap<>();
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
            String rollProblem = rollMismatch(pack, placed, expected.path("rolls"));
            if (rollProblem == null) rollProblem = weaponRollMismatch(pack, placed, expected.path("rolls"));
            if (rollProblem != null) failures.computeIfAbsent(row.key(), k -> new ArrayList<>()).add("rolls: " + rollProblem);
            String mitigationProblem = mitigationMismatch(pack, placed, expected.path("mitigation"));
            if (mitigationProblem != null) failures.computeIfAbsent(row.key(), k -> new ArrayList<>()).add("mitigation: " + mitigationProblem);
            String attachProblem = attachMismatch(pack, placed, expected.path("attach"));
            if (attachProblem != null) failures.computeIfAbsent(row.key(), k -> new ArrayList<>()).add("attach: " + attachProblem);
            String initProblem = initMismatch(pack, placed, expected.path("init"), legacyAutomation(row));
            if (initProblem != null) failures.computeIfAbsent(row.key(), k -> new ArrayList<>()).add("init: " + initProblem);
            String spellProblem = spellcastMismatch(pack, placed, legacyAutomation(row));
            if (spellProblem != null) failures.computeIfAbsent(row.key(), k -> new ArrayList<>()).add("spellcast: " + spellProblem);
            String altProblem = alternateProfileMismatch(pack, placed, legacyAutomation(row));
            if (altProblem != null) failures.computeIfAbsent(row.key(), k -> new ArrayList<>()).add("alternateProfiles: " + altProblem);
            String damageProblem = damageDiceMismatch(pack, placed, expected.path("damageDice"));
            if (damageProblem != null) failures.computeIfAbsent(row.key(), k -> new ArrayList<>()).add("damage: " + damageProblem);
            String reloadProblem = reloadingMismatch(pack, placed, legacyAutomation(row));
            if (reloadProblem != null) failures.computeIfAbsent(row.key(), k -> new ArrayList<>()).add("reloading: " + reloadProblem);
            String miscProblem = miscMismatch(pack, placed, legacyAutomation(row));
            if (miscProblem != null) failures.computeIfAbsent(row.key(), k -> new ArrayList<>()).add("misc: " + miscProblem);
            String deathProblem = deathMismatch(pack, placed, legacyAutomation(row));
            if (deathProblem != null) failures.computeIfAbsent(row.key(), k -> new ArrayList<>()).add("death: " + deathProblem);
            String manualProblem = manualMismatch(legacyAutomation(row));
            if (manualProblem != null) failures.computeIfAbsent(row.key(), k -> new ArrayList<>()).add("manual: " + manualProblem);
            String builderProblem = builderMismatch(pack, placed, legacyAutomation(row));
            if (builderProblem != null) failures.computeIfAbsent(row.key(), k -> new ArrayList<>()).add("builder: " + builderProblem);
            String rewardProblem = hopeRewardMismatch(pack, placed, legacyAutomation(row));
            if (rewardProblem != null) failures.computeIfAbsent(row.key(), k -> new ArrayList<>()).add("reward: " + rewardProblem);
            String banishProblem = banishMismatch(pack, placed, legacyAutomation(row));
            if (banishProblem != null) failures.computeIfAbsent(row.key(), k -> new ArrayList<>()).add("banish: " + banishProblem);
            String restBenefitProblem = restBenefitMismatch(pack, placed, legacyAutomation(row));
            if (restBenefitProblem != null) failures.computeIfAbsent(row.key(), k -> new ArrayList<>()).add("restBenefit: " + restBenefitProblem);
            String channelProblem = channelMismatch(pack, placed, legacyAutomation(row));
            if (channelProblem != null) failures.computeIfAbsent(row.key(), k -> new ArrayList<>()).add("channel: " + channelProblem);
            String partyProblem = partyDowntimeMismatch(pack, placed, legacyAutomation(row));
            if (partyProblem != null) failures.computeIfAbsent(row.key(), k -> new ArrayList<>()).add("party: " + partyProblem);
            String companionProblem = companionMismatch(pack, placed, legacyAutomation(row));
            if (companionProblem != null) failures.computeIfAbsent(row.key(), k -> new ArrayList<>()).add("companion: " + companionProblem);
            String stanceProblem = stancesMismatch(pack, placed, legacyAutomation(row));
            if (stanceProblem != null) failures.computeIfAbsent(row.key(), k -> new ArrayList<>()).add("stance: " + stanceProblem);
            String wardenProblem = wardenMismatch(pack, placed, legacyAutomation(row));
            if (wardenProblem != null) failures.computeIfAbsent(row.key(), k -> new ArrayList<>()).add("warden: " + wardenProblem);
            String handsProblem = healingHandsMismatch(pack, placed, legacyAutomation(row));
            if (handsProblem != null) failures.computeIfAbsent(row.key(), k -> new ArrayList<>()).add("healing hands: " + handsProblem);
            String beastProblem = beastformMismatch(pack, placed, legacyAutomation(row));
            if (beastProblem != null) failures.computeIfAbsent(row.key(), k -> new ArrayList<>()).add("beastform: " + beastProblem);
            String lunarProblem = lunarMismatch(pack, placed, legacyAutomation(row));
            if (lunarProblem != null) failures.computeIfAbsent(row.key(), k -> new ArrayList<>()).add("lunar: " + lunarProblem);
            String counterProblem = counterEffectMismatch(pack, placed, legacyAutomation(row));
            if (counterProblem != null) failures.computeIfAbsent(row.key(), k -> new ArrayList<>()).add("counter: " + counterProblem);
            String armorProblem = armorMismatch(pack, placed, legacyAutomation(row));
            if (armorProblem != null) failures.computeIfAbsent(row.key(), k -> new ArrayList<>()).add("armor: " + armorProblem);
            String triggerProblem = triggerMismatch(pack, placed, expected.path("triggers"));
            if (triggerProblem != null) failures.computeIfAbsent(row.key(), k -> new ArrayList<>()).add("triggers: " + triggerProblem);
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
        if (Boolean.getBoolean("parity.writeLedger")) writeLedger(failures);
        Map<String, String> fieldsByRow = new LinkedHashMap<>();
        for (String line : Files.readAllLines(Path.of("docs/automation-parity-ledger.csv")).subList(1, Files.readAllLines(Path.of("docs/automation-parity-ledger.csv")).size())) {
            if (line.isBlank()) continue;
            String[] cells = line.substring(1, line.length() - 1).split("\",\"", -1);
            fieldsByRow.put(cells[0] + cells[1], cells[3]);
        }
        List<String> blocking = failures.keySet().stream().filter(key -> java.util.Arrays.stream(fieldsByRow.get(key).split(";"))
                .allMatch(field -> field.isEmpty() || PROBED.contains(field))).toList();
        assertTrue(blocking.isEmpty(), blocking.size() + " fully probed ledger rows differ from the legacy baseline; see target/parity-report.txt: " + blocking);
    }

    /** Automation fields whose behavior the harness replays against the legacy baseline. */
    private static final java.util.Set<String> PROBED = java.util.Set.of("actions", "uses", "rest", "modifiers", "rollOptions",
            "conditionalRollOptions", "conditionalModifiers", "conditionalActions", "usesOverrides", "rollTriggers", "conditionalRollTriggers", "attach", "mitigation", "conditionalMitigation", "alternateProfiles", "damage", "spellcastDifficulty", "spellcastSuccessSteps", "hideSpellcast", "automaticRestUse", "automaticRestUseOnAttempt",
            "armorHalfEffect", "armorFullHeldEffect", "armorFullEffect", "equipEffect", "zeroHopeClearArmor", "resources", "classResourceOnly",
            "reloading", "requirement", "damageTypeChoice", "criticalMelee", "ignoreWeaponBurden", "unarmedWeapon", "conditionalDamageRewards", "dicePool", "deathMove", "manual", "creation", "companionTraining", "grantsFeatures", "groupGrantedFeatures", "beastformFree", "elementChoice", "overflowHope", "unarmoredBase", "hopeReward", "banishDifficulty", "restReroll", "experienceRefund", "actionBenefits", "channelBenefit", "partyDowntime", "lunarPhases", "effectCounters", "companion", "stances", "beastforms");

    private static void writeLedger(Map<String, List<String>> failures) throws Exception {
        Path file = Path.of("docs/automation-parity-ledger.csv");
        List<String> lines = Files.readAllLines(file);
        List<String> out = new ArrayList<>(List.of(lines.get(0)));
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) continue;
            String[] cells = line.substring(1, line.length() - 1).split("\",\"", -1);
            String key = cells[0] + cells[1];
            {
                List<String> unprobed = new ArrayList<>();
                for (String field : cells[3].split(";")) if (!field.isEmpty() && !PROBED.contains(field)) unprobed.add(field);
                if (!unprobed.isEmpty()) {
                    cells[7] = "unverified";
                    cells[6] = "no legacy probe yet for: " + String.join(";", unprobed);
                } else if (failures.containsKey(key)) {
                    cells[7] = "unverified";
                    cells[6] = "mismatch: " + failures.get(key).get(0).replace("\"", "'").replaceAll("\\s+@ .*", "");
                } else {
                    cells[7] = "verified";
                    cells[6] = "ledger-baseline.json: actions, stats, rest usage, check/attack/damage rolls";
                }
            }
            out.add("\"" + String.join("\",\"", cells) + "\"");
        }
        Files.write(file, out);
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

    /** Compares the pools the source (or a sibling feature of the same entry, as in the legacy sheet) declares with the legacy synced resources. */
    private static String initMismatch(AutomationHost.Pack pack, Placed placed, JsonNode expected, JsonNode legacyAutomation) {
        JsonNode legacy = expected.path("resources");
        if (!legacy.isObject() || legacy.isEmpty()) return null;
        String entryId = placed.sourceId().contains(":features:") ? placed.sourceId().substring(0, placed.sourceId().indexOf(":features:")) : placed.sourceId();
        java.util.Map<String, Integer> declared = new java.util.HashMap<>();
        pack.programs().forEach((id, program) -> {
            if (!id.equals(entryId) && !id.startsWith(entryId + ":")) return;
            program.pools().properties().forEach(pool -> declared.put(NON_SLUG.matcher(pool.getKey().toLowerCase(java.util.Locale.ROOT)).replaceAll(""), pool.getValue().path("initial").asInt()));
        });
        for (var resource : legacy.properties()) {
            String slug = NON_SLUG.matcher(resource.getKey().toLowerCase(java.util.Locale.ROOT)).replaceAll("");
            if (!declared.containsKey(slug)) pack.programs().values().forEach(program -> program.pools().properties().forEach(pool -> {
                if (NON_SLUG.matcher(pool.getKey().toLowerCase(java.util.Locale.ROOT)).replaceAll("").equals(slug)) declared.putIfAbsent(slug, pool.getValue().path("initial").asInt());
            }));
            if (!declared.containsKey(slug)) return "pool " + resource.getKey() + " is not declared; declared=" + declared.keySet();
            if (declared.get(slug) != resource.getValue().asInt()) return "pool " + resource.getKey() + " legacy=" + resource.getValue().asInt() + " new initial=" + declared.get(slug);
        }
        return null;
    }
    private static JsonNode currentAutomation;

    private static final List<String> ARMOR_HOSTED = List.of("armorHalfEffect", "armorFullHeldEffect", "armorFullEffect", "equipEffect", "zeroHopeClearArmor", "resources", "classResourceOnly",
            "reloading", "requirement", "damageTypeChoice", "criticalMelee", "ignoreWeaponBurden", "unarmedWeapon", "conditionalDamageRewards", "dicePool", "deathMove", "manual");
    private static final int[][] ARMOR_STEPS = {{0, 3}, {2, 3}, {4, 3}, {3, 3}, {4, 3}, {4, 0}, {4, 2}};

    private static JsonNode legacyAutomation(Row row) throws Exception {
        JsonNode legacy = new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(Path.of("dmmap-daggerheart/" + row.category() + ".json")));
        return legacy.at(row.pointer());
    }

    private static int usesTaken(JsonNode sheet) {
        int[] total = {0};
        sheet.at("/automationState/sources").properties().forEach(s -> total[0] += s.getValue().at("/usage/uses").asInt());
        return total[0];
    }

    /** Spellcast roll difficulty, use consumption on attempt or success, hidden spellcast buttons and success steps against the legacy card automation. */
    private static String spellcastMismatch(AutomationHost.Pack pack, Placed placed, JsonNode legacy) {
        if (legacy == null || !(legacy.has("spellcastDifficulty") || legacy.has("hideSpellcast"))) return null;
        JsonNode profiles = currentAutomation.path("profiles");
        JsonNode profile = null;
        for (JsonNode candidate : profiles)
            if (candidate.path("id").asText().startsWith("spellcast-roll")) profile = candidate;
        if (legacy.path("hideSpellcast").asBoolean()) return profile == null ? null : "hideSpellcast row still offers " + profile.path("id").asText();
        if (profile == null) return "no spellcast profile; profiles=" + profiles;
        int difficulty = legacy.path("spellcastDifficulty").asInt();
        if (profile.path("difficulty").asInt(-1) != difficulty) return "difficulty legacy=" + difficulty + " new=" + profile.path("difficulty");
        boolean consumes = legacy.path("automaticRestUse").asBoolean(), onAttempt = legacy.path("automaticRestUseOnAttempt").asBoolean();
        String consume = profile.path("consumeOn").asText("");
        String want = !consumes ? "" : onAttempt ? "attempt" : "success";
        if (!consume.equals(want)) return "consumeOn legacy=" + want + " new=" + consume;
        java.util.List<String> instances = new ArrayList<>();
        for (var element : HOST.sources(pack, placed.sheet())) if (element.sourceId().equals(placed.sourceId())) instances.add(element.instanceId());
        int[][] dice = {{1, 2}, {12, 11}};
        int[] taken = new int[2];
        JsonNode[] pools = new JsonNode[2];
        for (int i = 0; i < 2; i++) {
            ObjectNode sheet = placed.sheet().deepCopy();
            for (JsonNode trait : sheet.withArray("traits")) ((ObjectNode) trait).put("mod", 1);
            RuntimeException last = null;
            boolean done = false;
            for (String instance : instances) {
                ObjectNode copy = sheet.deepCopy();
                ObjectNode request = RuleJson.JSON.createObjectNode().put("requestId", "r").put("expectedRevision", HOST.revision(copy));
                request.putArray("selections");
                request.putObject("profileRoll").put("sourceInstanceId", instance).put("profileId", profile.path("id").asText()).put("kind", "spellcast");
                try {
                    HOST.execute(pack, copy, AutomationHost.event("action.request", "parity", 1), request, "self", "self", dualityDice(dice[i][0], dice[i][1]));
                    taken[i] = usesTaken(copy);
                    pools[i] = copy.at("/automationState/pools");
                    done = true;
                    break;
                } catch (RuntimeException e) {
                    last = e;
                }
            }
            if (!done) return "spellcast roll rejected: " + (last == null ? "" : last.getMessage() + " @ " + frames(last));
        }
        int failTaken = onAttempt ? 1 : 0, successTaken = consumes ? 1 : 0;
        if (taken[0] != failTaken) return "uses after failed cast legacy=" + failTaken + " new=" + taken[0];
        if (taken[1] != successTaken) return "uses after successful cast legacy=" + successTaken + " new=" + taken[1];
        if (legacy.has("spellcastSuccessSteps") && pools[0].equals(pools[1])) return "success steps did not change anything on a successful cast; pools=" + pools[1];
        return null;
    }
    private static void distinctTraits(ObjectNode sheet) {
        int mod = 0;
        for (JsonNode trait : sheet.withArray("traits")) ((ObjectNode) trait).put("mod", mod++ - 1);
    }

    /** Alternate weapon statistics attack and damage like the same weapon carrying those statistics. */
    private static String alternateProfileMismatch(AutomationHost.Pack pack, Placed placed, JsonNode legacy) throws Exception {
        if (legacy == null || !legacy.path("alternateProfiles").isArray()) return null;
        for (JsonNode alt : legacy.path("alternateProfiles")) {
            String profileId = NON_SLUG.matcher(alt.path("label").asText().toLowerCase(java.util.Locale.ROOT)).replaceAll("-").replaceAll("^-|-$", "");
            for (String kind : List.of("attack", "damage")) {
                try {
                    ObjectNode sheet = withWeapon(placed.sheet());
                    distinctTraits(sheet);
                    ObjectNode weapon = (ObjectNode) sheet.withArray("activeWeapons").get(0);
                    String instance = weapon.path("instanceId").asText();
                    ObjectNode event = AutomationHost.event("action.request", "parity", 1);
                    ObjectNode baseSheet = plainBase(sheet);
                    distinctTraits(baseSheet);
                    int plainBaseTotal = rollTotal(HOST.execute(pack, baseSheet, event, profileRequest(baseSheet, "plain", kind, null), "self", "self", constantDice()));
                    ObjectNode altPlain = plainBase(sheet);
                    distinctTraits(altPlain);
                    ObjectNode stats = (ObjectNode) altPlain.withArray("activeWeapons").get(0);
                    stats.put("trait", alt.path("trait").asText()).put("range", alt.path("range").asText()).put("damage", alt.path("damage").asText());
                    if (alt.has("damageType")) stats.put("damageType", alt.path("damageType").asText());
                    int plainAltTotal = rollTotal(HOST.execute(pack, altPlain, event, profileRequest(altPlain, "plain", kind, null), "self", "self", constantDice()));
                    ObjectNode baseProfile = sheet.deepCopy();
                    int ownBase = rollTotal(HOST.execute(pack, baseProfile, event, profileRequest(baseProfile, instance, kind, null), "self", "self", constantDice()));
                    ObjectNode altRequest = profileRequest(sheet, instance, kind, null);
                    ((ObjectNode) altRequest.get("profileRoll")).put("profileId", profileId);
                    int ownAlt = rollTotal(HOST.execute(pack, sheet, event, altRequest, "self", "self", constantDice()));
                    if (ownAlt - ownBase != plainAltTotal - plainBaseTotal)
                        return alt.path("label").asText() + " " + kind + ": legacy delta=" + (plainAltTotal - plainBaseTotal) + " new delta=" + (ownAlt - ownBase);
                } catch (RuntimeException e) {
                    return alt.path("label").asText() + " " + kind + " rejected: " + e.getMessage() + " @ " + frames(e);
                }
            }
        }
        return null;
    }
    /** Replays the legacy damage dice pipeline (roll, ones-as, explode, ones-bonus) and compares it with the new damage roll. */
    private static String damageDiceMismatch(AutomationHost.Pack pack, Placed placed, JsonNode expected) throws Exception {
        if (expected == null || expected.isMissingNode() || expected.isNull()) return null;
        for (long seed = 1; seed <= 4; seed++) {
            String joined = String.join("", java.util.stream.StreamSupport.stream(expected.path("expressions").spliterator(), false)
                    .map(JsonNode::asText).toList()).replace("+-", "-");
            Random legacyRandom = new Random(seed);
            dmmt.online.DiceRoller.Result result = dmmt.online.DiceRoller.roll(joined, 100, legacyRandom);
            JsonNode rules = expected.path("rules");
            if (rules.has("onesAs")) result = dmmt.online.DiceRoller.onesAs(result, rules.path("onesAs").asInt(), 100);
            int onesBonusDice = rules.has("onesBonus") ? dmmt.online.DiceRoller.countOnes(result, 100) : 0;
            int total = result.total();
            if (rules.has("explode")) {
                for (int[] die : dmmt.online.DiceRoller.explode(result, "repeat".equals(rules.path("explode").asText()), 100, legacyRandom)) total += die[1];
            }
            for (int i = 0; i < onesBonusDice; i++) total += legacyRandom.nextInt(rules.path("onesBonus").asInt()) + 1;
            try {
                ObjectNode sheet = withWeapon(placed.sheet());
                String instance = sheet.withArray("activeWeapons").get(0).path("instanceId").asText();
                ObjectNode event = AutomationHost.event("action.request", "parity", 1);
                int actual = rollTotal(HOST.execute(pack, sheet, event, profileRequest(sheet, instance, "damage", null), "self", "self", new Random(seed)));
                if (actual != total) return "seed " + seed + ": legacy damage total=" + total + " new=" + actual + " expr=" + joined + " rules=" + rules;
            } catch (RuntimeException e) {
                return "damage rejected: " + e.getMessage() + " @ " + frames(e);
            }
        }
        return null;
    }
    private static Random fixedDice(int value) {
        return new Random(0) {
            @Override public int nextInt(int bound) { return Math.min(value, bound) - 1; }
        };
    }

    /** Reloading weapons: the legacy check die unloads on its fail value, an unloaded weapon cannot attack and reloading costs one Stress. */
    private static String reloadingMismatch(AutomationHost.Pack pack, Placed placed, JsonNode legacy) {
        if (legacy == null || !legacy.path("reloading").isObject()) return null;
        int fail = legacy.path("reloading").path("fail").asInt(1);
        int safe = fail == 1 ? 2 : 1;
        ObjectNode event = AutomationHost.event("action.request", "parity", 1);
        try {
            ObjectNode sheet = withWeapon(placed.sheet());
            String instance = sheet.withArray("activeWeapons").get(0).path("instanceId").asText();
            HOST.execute(pack, sheet, event, profileRequest(sheet, instance, "attack", null), "self", "self", fixedDice(safe));
            HOST.execute(pack, sheet, event, profileRequest(sheet, instance, "attack", null), "self", "self", fixedDice(safe));
            HOST.execute(pack, sheet, event, profileRequest(sheet, instance, "attack", null), "self", "self", fixedDice(fail));
            try {
                HOST.execute(pack, sheet, event, profileRequest(sheet, instance, "attack", null), "self", "self", fixedDice(safe));
                return "attack with an unloaded weapon was not blocked";
            } catch (RuntimeException expectedBlock) {
                // legacy throws "is unloaded" here
            }
            int stress = sheet.at("/stress/on").asInt();
            ObjectNode reload = RuleJson.JSON.createObjectNode().put("requestId", "reload").put("expectedRevision", HOST.revision(sheet));
            reload.putArray("selections").addObject().put("sourceInstanceId", instance).put("ruleId", "reload").put("bindingId", "sheet").putObject("inputs");
            HOST.execute(pack, sheet, event, reload, "self", "self", fixedDice(safe));
            if (sheet.at("/stress/on").asInt() != stress + 1) return "reload stress legacy=" + (stress + 1) + " new=" + sheet.at("/stress/on").asInt();
            HOST.execute(pack, sheet, event, profileRequest(sheet, instance, "attack", null), "self", "self", fixedDice(safe));
        } catch (Exception e) {
            return "rejected: " + e.getMessage() + " @ " + (e instanceof RuntimeException r ? frames(r) : "");
        }
        return null;
    }

    private static JsonNode claimResult(AutomationHost.Pack pack, ObjectNode sheet, ObjectNode event, JsonNode rolled, String ruleId) {
        for (JsonNode action : rolled.path("resultActions")) {
            if (!ruleId.equals(norm(action.path("ruleId").asText())) || !ownOption(action)) continue;
            ObjectNode claim = RuleJson.JSON.createObjectNode().put("requestId", "c" + REQUESTS.incrementAndGet()).put("expectedRevision", HOST.revision(sheet))
                    .put("resultId", rolled.at("/event/resultId").asText());
            claim.putArray("selections").addObject().put("sourceInstanceId", action.path("sourceInstanceId").asText())
                    .put("ruleId", action.path("ruleId").asText()).put("bindingId", action.path("bindingId").asText());
            return HOST.execute(pack, sheet, event, claim, "self", "self", constantDice());
        }
        return null;
    }

    private static void setTrait(ObjectNode sheet, String trait, int mod) {
        for (JsonNode node : sheet.withArray("traits")) if (trait.equals(node.path("name").asText())) ((ObjectNode) node).put("mod", mod);
    }

    /** Weapon and feature rules without a dedicated family: requirement, damage type choice, critical melee, burden, unarmed, damage rewards, dice pools. */
    private static String miscMismatch(AutomationHost.Pack pack, Placed placed, JsonNode legacy) {
        if (legacy == null) return null;
        ObjectNode event = AutomationHost.event("action.request", "parity", 1);
        try {
            if (legacy.path("requirement").isObject()) {
                JsonNode requirement = legacy.path("requirement");
                for (int value = -3; value <= 3; value++) {
                    ObjectNode sheet = withWeapon(placed.sheet());
                    setTrait(sheet, requirement.path("trait").asText(), value);
                    boolean legacyBlocked = requirement.has("max") && value > requirement.path("max").asInt()
                            || requirement.has("min") && value < requirement.path("min").asInt();
                    boolean blocked = false;
                    try {
                        HOST.execute(pack, sheet, event, profileRequest(sheet, sheet.withArray("activeWeapons").get(0).path("instanceId").asText(), "attack", null), "self", "self", constantDice());
                    } catch (RuntimeException e) {
                        blocked = true;
                    }
                    if (blocked != legacyBlocked) return "requirement " + requirement + " at " + value + ": legacy blocked=" + legacyBlocked + " new blocked=" + blocked;
                }
            }
            if (legacy.path("damageTypeChoice").asBoolean()) {
                ObjectNode sheet = withWeapon(placed.sheet());
                String instance = sheet.withArray("activeWeapons").get(0).path("instanceId").asText();
                JsonNode offered = null;
                for (JsonNode option : weaponOptions(pack, sheet, event, instance, "damage")) if (ownOption(option)) offered = option;
                if (offered == null) return "damage type choice is not offered";
                String plain = HOST.execute(pack, sheet.deepCopy(), event, profileRequest(sheet, instance, "damage", null), "self", "self", constantDice()).toString();
                String chosen = HOST.execute(pack, sheet.deepCopy(), event, profileRequest(sheet, instance, "damage", offered), "self", "self", constantDice()).toString();
                if (plain.contains("\"damageType\":\"magic\"") || !chosen.contains("\"damageType\":\"magic\"")) return "choosing the magic damage type does not change the damage type";
            }
            if (legacy.path("criticalMelee").isObject()) {
                JsonNode bonus = legacy.path("criticalMelee");
                ObjectNode sheet = withWeapon(placed.sheet());
                String instance = sheet.withArray("activeWeapons").get(0).path("instanceId").asText();
                int hope = sheet.at("/hope/on").asInt(), stress = sheet.at("/stress/on").asInt();
                ObjectNode baseline = sheet.deepCopy();
                int plainDamage = rollTotal(HOST.execute(pack, baseline, event, profileRequest(baseline, instance, "damage", null), "self", "self", constantDice()));
                JsonNode rolled = HOST.execute(pack, sheet, event, profileRequest(sheet, instance, "attack", null), "self", "self", dualityDice(5, 5));
                if (claimResult(pack, sheet, event, rolled, "critical-melee-reward") == null) return "critical melee reward is not offered on a critical melee attack";
                if (sheet.at("/hope/on").asInt() != Math.min(6, hope + 1 + bonus.path("hope").asInt()) || sheet.at("/stress/on").asInt() != Math.max(0, stress - 1 - bonus.path("stress").asInt()))
                    return "critical melee reward legacy hope+" + (1 + bonus.path("hope").asInt()) + " stress-" + (1 + bonus.path("stress").asInt())
                            + " new hope " + hope + "->" + sheet.at("/hope/on").asInt() + " stress " + stress + "->" + sheet.at("/stress/on").asInt();
                int boosted = rollTotal(HOST.execute(pack, sheet, event, profileRequest(sheet, instance, "damage", null), "self", "self", constantDice()));
                ObjectNode moreProficiency = plainBase(baseline);
                int plainBase = rollTotal(HOST.execute(pack, moreProficiency, event, profileRequest(moreProficiency, "plain", "damage", null), "self", "self", constantDice()));
                moreProficiency = plainBase(baseline);
                moreProficiency.withObject("/proficiency").put("on", 2 + bonus.path("proficiency").asInt());
                int plainBoosted = rollTotal(HOST.execute(pack, moreProficiency, event, profileRequest(moreProficiency, "plain", "damage", null), "self", "self", constantDice()));
                if (boosted - plainDamage != plainBoosted - plainBase) return "critical melee damage proficiency legacy +" + (plainBoosted - plainBase) + " new +" + (boosted - plainDamage);
                int after = rollTotal(HOST.execute(pack, sheet, event, profileRequest(sheet, instance, "damage", null), "self", "self", constantDice()));
                if (after != plainDamage) return "critical melee proficiency lasts beyond the next damage roll";
            }
            if (legacy.path("ignoreWeaponBurden").asBoolean()) {
                ObjectNode plan = AutomationHost.event("equipment.plan", "self", 0);
                plan.set("equipment", RuleJson.parse("{\"handLimit\":2,\"ignoreBurden\":0,\"adjustable\":{\"ignoreBurden\":{\"min\":0,\"max\":1}}}"));
                if (HOST.automatic(pack, placed.sheet().deepCopy(), plan, constantDice()).at("/event/equipment/ignoreBurden").asInt() != 1) return "weapon burden is not ignored";
            }
            if (legacy.path("unarmedWeapon").isObject()) {
                JsonNode spec = legacy.path("unarmedWeapon");
                ObjectNode sheet = placed.sheet().deepCopy();
                sheet.putArray("activeWeapons");
                if (!HOST.synchronizeUnarmedWeapon(pack, sheet)) return "unarmed weapon is not generated";
                JsonNode weapon = sheet.at("/activeWeapons/0");
                for (String field : List.of("name", "slot", "range", "damage", "damageType"))
                    if (spec.has(field) && !spec.path(field).asText().equalsIgnoreCase(weapon.path(field).asText()))
                        return "unarmed " + field + " legacy=" + spec.path(field).asText() + " new=" + weapon.path(field).asText();
                int best = Integer.MIN_VALUE, chosen = Integer.MIN_VALUE;
                for (JsonNode trait : sheet.withArray("traits")) {
                    best = Math.max(best, trait.path("mod").asInt());
                    if (trait.path("name").asText().equals(weapon.path("trait").asText())) chosen = trait.path("mod").asInt();
                }
                if (chosen != best) return "unarmed weapon does not use the highest trait";
            }
            if (legacy.path("conditionalDamageRewards").isArray()) {
                for (JsonNode reward : legacy.path("conditionalDamageRewards")) {
                    for (int extraCards = 0; extraCards <= 4; extraCards += 3) {
                        ObjectNode sheet = placed.sheet().deepCopy();
                        for (int i = 0; i < extraCards; i++)
                            sheet.withArray("loadout").addObject().put("level", 1).put("domain", reward.path("loadoutDomain").path("domain").asText("Blood")).put("type", "Ability").put("name", "Filler " + i).put("text", "");
                        int domainCards = 0;
                        for (JsonNode card : sheet.withArray("loadout"))
                            if (reward.path("loadoutDomain").path("domain").asText().equalsIgnoreCase(card.path("domain").asText())) domainCards++;
                        for (int amount : new int[] {1, 6, 20}) {
                            ObjectNode working = sheet.deepCopy();
                            ObjectNode request = RuleJson.JSON.createObjectNode().put("requestId", "d").put("expectedRevision", HOST.revision(working));
                            request.putArray("selections");
                            request.putObject("damage").put("amount", amount).put("armor", 0).put("type", "physical");
                            HOST.execute(pack, working, AutomationHost.event("action.request", "parity", 0), request, "self", "self", constantDice());
                            int hp = working.at("/hp/on").asInt() - sheet.at("/hp/on").asInt();
                            boolean gated = hp >= Math.max(1, reward.path("minHp").asInt())
                                    && (!reward.has("loadoutDomain") || domainCards >= reward.path("loadoutDomain").path("min").asInt());
                            int armorOn = sheet.at("/armorTrack/on").asInt();
                            int expectedHope = Math.min(6, sheet.at("/hope/on").asInt() + (gated ? reward.path("gainHope").asInt() : 0));
                            int expectedArmor = armorOn - (gated ? Math.min(armorOn, reward.path("clearArmor").asInt()) : 0);
                            if (working.at("/hope/on").asInt() != expectedHope || working.at("/armorTrack/on").asInt() != expectedArmor)
                                return "damage reward amount " + amount + " cards " + domainCards + ": legacy hope " + expectedHope + " armor " + expectedArmor
                                        + " new hope " + working.at("/hope/on").asInt() + " armor " + working.at("/armorTrack/on").asInt();
                        }
                    }
                }
            }
            if (legacy.path("dicePool").isObject()) {
                JsonNode pool = legacy.path("dicePool");
                for (int mod : new int[] {1, 3})
                    for (boolean devout : new boolean[] {false, true})
                        for (long seed = 1; seed <= 3; seed++) {
                            ObjectNode sheet = placed.sheet().deepCopy();
                            setTrait(sheet, "Instinct", mod);
                            if (devout) sheet.withArray("features").addObject().put("name", "Devout").put("label", "Devout").put("text", "").put("automationSource", "classes:seraph-divine-wielder-specialization:features:devout").put("instanceId", "devout");
                            int count = mod + (devout ? pool.path("extraByFeature").path("Devout").asInt() : 0);
                            Random legacyRandom = new Random(seed);
                            List<Integer> faces = new ArrayList<>();
                            for (int i = 0; i < count; i++) faces.add(legacyRandom.nextInt(pool.path("sides").asInt()) + 1);
                            faces.sort(java.util.Comparator.reverseOrder());
                            int expectedSum = faces.subList(0, mod).stream().mapToInt(Integer::intValue).sum();
                            String instance = HOST.sources(pack, sheet).stream().filter(s -> placed.sourceId().equals(s.sourceId())).findFirst().orElseThrow().instanceId();
                            ObjectNode request = RuleJson.JSON.createObjectNode().put("requestId", "pool").put("expectedRevision", HOST.revision(sheet));
                            request.putArray("selections").addObject().put("sourceInstanceId", instance).put("ruleId", "roll").put("bindingId", "sheet").putObject("inputs");
                            HOST.execute(pack, sheet, event, request, "self", "self", new Random(seed));
                            int sum = 0, dice = 0;
                            for (JsonNode source : sheet.at("/automationState/sources"))
                                for (JsonNode face : source.at("/sourcePools/prayer/dice")) { sum += face.path("face").asInt(); dice++; }
                            if (sum != expectedSum || dice != mod)
                                return "dice pool mod " + mod + " devout " + devout + " seed " + seed + ": legacy " + mod + " dice sum " + expectedSum + " new " + dice + " dice sum " + sum + " sources=" + HOST.sources(pack, sheet).stream().map(s -> s.sourceId()).toList();
                        }
            }
        } catch (Exception e) {
            return "rejected: " + e.getMessage() + " @ " + (e instanceof RuntimeException r ? frames(r) : "");
        }
        return null;
    }

    /** Manual notes stay descriptive: legacy shows them as text, so every note must reach a player-facing rule description. */
    private static String manualMismatch(JsonNode legacy) {
        if (legacy == null || !legacy.has("manual")) return null;
        StringBuilder shown = new StringBuilder();
        for (JsonNode rule : currentAutomation.path("rules")) shown.append(rule.path("ui").path("description").asText()).append(' ');
        String haystack = shown.toString().replaceAll("[^A-Za-z0-9]", "");
        for (JsonNode note : legacy.get("manual"))
            if (!haystack.contains(note.asText().replaceAll("[^A-Za-z0-9]", ""))) return "manual note not shown: " + note.asText();
        return null;
    }

    /** Creation allowances, companion training, granted feature groups, free beastforms, element choice, armor overflow and the unarmored base against legacy. */
    private static String builderMismatch(AutomationHost.Pack pack, Placed placed, JsonNode legacy) {
        if (legacy == null) return null;
        if (legacy.has("creation")) {
            boolean applies = false;
            Map<String, Integer> planned = new TreeMap<>();
            for (JsonNode rule : currentAutomation.path("rules"))
                if ("creation".equals(rule.path("id").asText()) && "creation.apply".equals(rule.at("/bindings/0/event").asText())) {
                    applies = true;
                    for (JsonNode step : rule.path("do"))
                        if ("plan.adjust".equals(step.path("op").asText()) && "event.creation".equals(step.path("plan").asText()))
                            planned.merge(step.path("field").asText(), step.path("by").asInt(), Integer::sum);
                }
            if (!applies) return "no creation.apply rule";
            Map<String, Integer> want = new TreeMap<>();
            legacy.get("creation").properties().forEach(e -> want.put(e.getKey(), e.getValue().asInt()));
            if (!want.equals(planned)) return "creation allowances legacy " + want + " new " + planned;
        }
        if (legacy.has("companionTraining") && legacy.get("companionTraining").asInt() != currentAutomation.at("/entitlements/companionTraining").asInt(-1))
            return "companion training legacy " + legacy.get("companionTraining") + " new " + currentAutomation.at("/entitlements/companionTraining");
        if (legacy.has("grantsFeatures") && !legacy.get("grantsFeatures").equals(currentAutomation.at("/grants/features")))
            return "granted features legacy " + legacy.get("grantsFeatures") + " new " + currentAutomation.at("/grants/features");
        if (legacy.has("grantsFeatures") && !(legacy.path("groupGrantedFeatures").asBoolean() ? "grouped" : "separate").equals(currentAutomation.at("/grants/display").asText()))
            return "granted feature display legacy grouped=" + legacy.path("groupGrantedFeatures").asBoolean() + " new " + currentAutomation.at("/grants/display");
        if (legacy.has("beastformFree") && !legacy.get("beastformFree").equals(currentAutomation.at("/provides/beastformFree")))
            return "free beastforms legacy " + legacy.get("beastformFree") + " new " + currentAutomation.at("/provides/beastformFree");
        if (legacy.has("elementChoice")) {
            JsonNode input = null;
            for (JsonNode rule : currentAutomation.path("rules"))
                if ("choose-element".equals(rule.path("id").asText())) input = rule.at("/inputs/0");
            if (input == null) return "no element choice action";
            List<String> offered = input.path("options").findValuesAsText("label");
            List<String> want = new ArrayList<>();
            legacy.get("elementChoice").forEach(e -> want.add(e.asText()));
            if (!want.equals(offered)) return "elements legacy " + want + " new " + offered;
        }
        if (legacy.has("overflowHope")) return overflowMismatch(pack, placed, legacy.get("overflowHope").asInt());
        if (legacy.has("unarmoredBase")) return unarmoredMismatch(pack, placed, legacy.get("unarmoredBase"));
        return null;
    }

    /** Overflowing: clearing more Armor Slots than are marked gains Hope (bounded by the Hope track) once per clear. */
    private static String overflowMismatch(AutomationHost.Pack pack, Placed placed, int gain) {
        for (int marked : new int[] {0, 1, 3})
            for (int requested : new int[] {1, 2, 4})
                for (int hope : new int[] {3, 5, 6}) {
                    String label = "marked " + marked + " requested " + requested + " hope " + hope;
                    int cleared = Math.min(marked, requested);
                    int want = Math.min(6, hope + (requested > cleared ? gain : 0));
                    ObjectNode before = placed.sheet().deepCopy();
                    before.withObject("/armorTrack").put("on", 1);
                    ObjectNode sheet = before.deepCopy();
                    sheet.withObject("/armorTrack").put("on", 0).put("slots", 4);
                    sheet.withObject("/hope").put("on", hope);
                    ObjectNode event = AutomationHost.event("track.changed", "parity", 0);
                    event.put("track", "armorTrack").put("change", "clear").put("requested", requested);
                    event.putObject("before").put("marked", marked).put("capacity", 4);
                    event.putObject("after").put("marked", marked - cleared).put("capacity", 4);
                    try {
                        HOST.automatic(pack, sheet, event, constantDice());
                    } catch (RuntimeException e) {
                        return label + ": rejected: " + e.getMessage();
                    }
                    if (sheet.at("/hope/on").asInt() != want) return label + ": hope legacy " + want + " new " + sheet.at("/hope/on").asInt();
                }
        return null;
    }

    /** Bare Bones style armor: score, Major and Severe thresholds by tier while no armor is equipped, and no effect with armor. */
    private static String unarmoredMismatch(AutomationHost.Pack pack, Placed placed, JsonNode base) {
        for (int level : new int[] {1, 3, 5, 8, 10}) {
            int tier = level == 1 ? 1 : level < 5 ? 2 : level < 8 ? 3 : 4;
            ObjectNode sheet = placed.sheet().deepCopy();
            sheet.put("level", level);
            int trait = 0;
            for (JsonNode t : sheet.path("traits")) if (base.path("trait").asText().equals(t.path("name").asText())) trait = t.path("mod").asInt();
            int score = base.path("score").asInt() + trait;
            int major = base.at("/thresholdsByTier/" + tier + "/0").asInt() + level;
            int severe = base.at("/thresholdsByTier/" + tier + "/1").asInt() + level;
            ObjectNode stats = statsOf(pack, sheet);
            if (stats.path("armorSlots").asInt() != score || stats.path("major").asInt() != major || stats.path("severe").asInt() != severe)
                return "level " + level + " unarmored legacy " + score + "/" + major + "/" + severe + " new " + stats;
            ObjectNode armored = sheet.deepCopy();
            armored.putObject("activeArmor").put("name", "Plain").put("score", 3).put("major", 8).put("severe", 17);
            ObjectNode plain = armored.deepCopy();
            plain.withArray("loadout").removeAll();
            if (!statsOf(pack, armored).equals(statsOf(pack, plain))) return "level " + level + " unarmored base applied with armor equipped";
        }
        return null;
    }

    /** Hope-dominant, non-critical rolls offer the replacement reward (Slayer Dice, Favor or removing GM Fear) instead of Hope. */
    private static String hopeRewardMismatch(AutomationHost.Pack pack, Placed placed, JsonNode legacy) {
        if (legacy == null || !legacy.path("hopeReward").isObject()) return null;
        JsonNode reward = legacy.get("hopeReward");
        ObjectNode event = AutomationHost.event("action.request", "parity", 1);
        String trait = reward.path("roll").asText().startsWith("trait:") ? reward.path("roll").asText().substring(6) : "Agility";
        for (String scenario : List.of("hope", "fear", "critical")) {
            ObjectNode sheet = placed.sheet().deepCopy();
            JsonNode rolled;
            try {
                rolled = HOST.execute(pack, sheet, event, checkRequest(sheet, "action", trait, null), "self", "self",
                        "hope".equals(scenario) ? dualityDice(9, 3) : "fear".equals(scenario) ? dualityDice(3, 9) : dualityDice(5, 5));
            } catch (RuntimeException e) {
                return scenario + ": roll rejected: " + e.getMessage();
            }
            JsonNode claimed = null;
            ObjectNode probe = sheet.deepCopy();
            try {
                claimed = claimResult(pack, probe, event, rolled, "hope-reward");
            } catch (RuntimeException e) {
                return scenario + ": reward rejected: " + e.getMessage();
            }
            boolean legacyOffered = "hope".equals(scenario);
            if (legacyOffered != (claimed != null)) return scenario + ": legacy offers reward=" + legacyOffered + " new=" + (claimed != null);
            if (claimed == null) continue;
            if (reward.has("amount") && poolValues(probe).getOrDefault(NON_SLUG.matcher(reward.path("resource").asText()).replaceAll(""), 0) != reward.get("amount").asInt())
                return reward.path("resource").asText() + " legacy +" + reward.get("amount") + " new " + poolValues(probe);
            if (reward.has("favor") && poolValues(probe).getOrDefault("favor", 0) != 3 + reward.get("favor").asInt())
                return "favor legacy +" + reward.get("favor") + " new " + poolValues(probe);
            if (reward.has("removeFear") && !claimed.toString().contains("fear")) return "remove fear is not requested from the table: " + claimed;
        }
        return null;
    }

    /** Rest-die rerolls keep the higher die once per rest; Experience refunds discount one cost on a high enough d6. */
    private static String restBenefitMismatch(AutomationHost.Pack pack, Placed placed, JsonNode legacy) {
        if (legacy == null) return null;
        if (legacy.path("restReroll").asBoolean()) {
            for (int[] faces : new int[][] {{2, 5}, {5, 2}, {4, 4}}) {
                ObjectNode sheet = placed.sheet().deepCopy();
                ObjectNode event = AutomationHost.event("rest.roll", "parity", 1);
                event.set("roll", StructuredRoll.validate(MAPPER.createObjectNode().put("id", "rest").set("contributions", MAPPER.createArrayNode().add(
                        MAPPER.createObjectNode().put("id", "d").put("kind", "dice").put("role", "downtime").put("sourceId", "core").put("label", "Downtime")
                                .set("dice", MAPPER.createArrayNode().add(MAPPER.createObjectNode().put("id", "d1").put("sides", 12).put("face", faces[0]).put("kept", true).put("origin", "rolled")))))));
                String instance = null;
                for (var element : HOST.sources(pack, sheet)) if (element.sourceId().equals(placed.sourceId())) instance = element.instanceId();
                if (instance == null) return "source is not active";
                ObjectNode selection = MAPPER.createObjectNode().put("sourceInstanceId", instance).put("ruleId", "rest-reroll").put("bindingId", "use");
                selection.putObject("inputs");
                int rolled = faces[1];
                Random dice = new Random(0) {
                    @Override public int nextInt(int bound) { return Math.min(rolled, bound) - 1; }
                };
                int kept;
                try {
                    var result = HOST.stageRecoveryRoll(pack, sheet, event, selection, HOST.revision(sheet), dice);
                    kept = StructuredRoll.validate(result.at("/event/roll")).path("total").asInt();
                } catch (RuntimeException e) {
                    return "reroll rejected: " + e.getMessage();
                }
                if (kept != Math.max(faces[0], rolled)) return "downtime die " + faces[0] + " rerolled to " + rolled + " legacy kept " + Math.max(faces[0], rolled) + " new " + kept;
                try {
                    HOST.stageRecoveryRoll(pack, sheet, event, selection, HOST.revision(sheet), dice);
                    return "a second reroll in the same rest is accepted";
                } catch (RuntimeException expectedLimit) {
                    // legacy allows one reroll per rest
                }
            }
        }
        if (legacy.path("actionBenefits").isObject()) {
            String problem = actionBenefitsMismatch(pack, placed, legacy.path("actionBenefits"));
            if (problem != null) return problem;
        }
        if (legacy.path("experienceRefund").isObject()) {
            int min = legacy.path("experienceRefund").path("min").asInt();
            for (int face : new int[] {1, min - 1, min, 6}) {
                ObjectNode event = AutomationHost.event("roll.costs", "parity", 1);
                ObjectNode adjustments = event.putObject("costAdjustments");
                adjustments.put("g_core-experience", 0);
                adjustments.putObject("adjustable").putObject("g_core-experience").put("min", 0).put("max", 1000);
                int rolled = face;
                Random dice = new Random(0) {
                    @Override public int nextInt(int bound) { return Math.min(rolled, bound) - 1; }
                };
                ObjectNode result;
                try {
                    result = HOST.automatic(pack, placed.sheet().deepCopy(), event, dice);
                } catch (RuntimeException e) {
                    return "cost roll rejected: " + e.getMessage();
                }
                int discount = result.at("/event/costAdjustments/g_core-experience").asInt();
                if (discount != (face >= min ? 1 : 0)) return "d6 " + face + " legacy refund " + (face >= min ? 1 : 0) + " new " + discount;
            }
        }
        return null;
    }

    /** Legacy chat benefits go to other players' characters, each gated on there being something to restore. */
    private static String actionBenefitsMismatch(AutomationHost.Pack pack, Placed placed, JsonNode benefits) {
        String instance = null;
        for (var element : HOST.sources(pack, placed.sheet())) if (element.sourceId().equals(placed.sourceId())) instance = element.instanceId();
        if (instance == null) return "source is not active";
        String label = currentAutomation.at("/rules/0/when/and/0/eq/1").asText();
        for (boolean ownEffect : new boolean[] {true, false}) {
            ObjectNode started = AutomationHost.event("effect.started", "parity", 1);
            started.putObject("effect").put("label", label).put("sourceActorId", ownEffect ? "parity" : "someone");
            ObjectNode result = HOST.automatic(pack, placed.sheet().deepCopy(), started, new Random(0));
            boolean offered = result.path("outbox").toString().contains("claim.offer");
            if (offered != ownEffect) return "effect " + label + (ownEffect ? " started by the owner" : " started by someone else") + " offered=" + offered;
        }
        String group = "offer-rally-claims";
        for (var entry : benefits.properties()) {
            for (JsonNode benefit : entry.getValue()) {
                String resource = benefit.path("steps").path(0).path("resource").asText();
                boolean gain = "gain".equals(benefit.path("steps").path(0).path("op").asText());
                String track = "hope".equals(resource) ? "hope" : "stress";
                String ruleId = null;
                for (JsonNode rule : currentAutomation.path("rules"))
                    if (rule.path("ui").path("label").asText().equals(benefit.path("label").asText())) ruleId = rule.path("id").asText();
                if (ruleId == null) return "no claim for " + benefit.path("label").asText();
                for (int on : new int[] {0, 3, 6}) {
                    ObjectNode recipient = standardSheet();
                    recipient.withObject("/" + track).put("on", on).put("slots", 6);
                    boolean canGain = gain ? on < 6 : on > 0;
                    ObjectNode event = AutomationHost.event("claim.accept", "parity", 1);
                    ObjectNode selection = MAPPER.createObjectNode().put("sourceInstanceId", instance).put("ruleId", ruleId).put("bindingId", "claim");
                    selection.putObject("inputs");
                    ObjectNode source = placed.sheet().deepCopy();
                    try {
                        HOST.stageClaim(pack, source, recipient, event, instance, group, "mira", "other", selection,
                                HOST.revision(source), HOST.revision(recipient), 0, new RuleDispatcher.ClaimLedger(0, java.util.Set.of()), new Random(0));
                        if (!canGain) return benefit.path("label").asText() + " is accepted with " + track + " " + on;
                        int expected = gain ? on + 1 : on - 1;
                        if (recipient.path(track).path("on").asInt() != expected) return benefit.path("label").asText() + " with " + track + " " + on + " legacy " + expected + " new " + recipient.path(track).path("on").asInt();
                    } catch (RuntimeException e) {
                        if (canGain) return benefit.path("label").asText() + " rejected with " + track + " " + on + ": " + e.getMessage();
                    }
                }
            }
        }
        return null;
    }

    /** Runs one sheet action of the placed source; null when the engine accepts it. */
    private static RuntimeException runSheetAction(AutomationHost.Pack pack, ObjectNode sheet, Placed placed, String ruleId, Random dice) {
        for (var element : HOST.sources(pack, sheet)) {
            if (!element.sourceId().equals(placed.sourceId())) continue;
            ObjectNode request = RuleJson.JSON.createObjectNode().put("requestId", "a" + REQUESTS.incrementAndGet()).put("expectedRevision", HOST.revision(sheet));
            request.putArray("selections").addObject().put("sourceInstanceId", element.instanceId()).put("ruleId", ruleId).put("bindingId", "sheet").putObject("inputs");
            try {
                HOST.execute(pack, sheet, AutomationHost.event("action.request", "parity", 1), request, "self", "self", dice);
                return null;
            } catch (RuntimeException e) {
                return e;
            }
        }
        return new IllegalStateException("source not on the sheet");
    }

    private static JsonNode heldEffect(ObjectNode sheet, String label) {
        for (JsonNode effect : sheet.path("automationState").path("effects"))
            if (label.equals(effect.path("label").asText())) return effect;
        return null;
    }

    private static int effectValue(JsonNode effect) {
        JsonNode value = effect.path("value");
        return value.isObject() ? value.path("current").asInt(value.path("value").asInt(-1)) : value.asInt(-1);
    }

    private static RuntimeException runPick(AutomationHost.Pack pack, ObjectNode sheet, Placed placed, String ruleId, String pick, Random dice) {
        for (var element : HOST.sources(pack, sheet)) {
            if (!element.sourceId().equals(placed.sourceId())) continue;
            ObjectNode request = RuleJson.JSON.createObjectNode().put("requestId", "p" + REQUESTS.incrementAndGet()).put("expectedRevision", HOST.revision(sheet));
            request.putArray("selections").addObject().put("sourceInstanceId", element.instanceId()).put("ruleId", ruleId).put("bindingId", "sheet").putObject("inputs").put("pick", pick);
            try {
                HOST.execute(pack, sheet, AutomationHost.event("action.request", "parity", 1), request, "self", "self", dice);
                return null;
            } catch (RuntimeException e) {
                return e;
            }
        }
        return new IllegalStateException("source not on the sheet");
    }

    private static int damageDelta(AutomationHost.Pack pack, ObjectNode withEffect, ObjectNode without, String kind) throws Exception {
        int[] totals = new int[2];
        int i = 0;
        for (ObjectNode target : List.of(withWeapon(without), withWeapon(withEffect))) {
            String instance = target.withArray("activeWeapons").get(0).path("instanceId").asText();
            totals[i++] = rollTotal(HOST.execute(pack, target, AutomationHost.event("action.request", "parity", 1), profileRequest(target, instance, kind, null), "self", "self", constantDice()));
        }
        return totals[1] - totals[0];
    }

    /** Stances: shifting needs a Focus (or a Stress with Flow State), offers each stance from its tier, replaces the previous stance and applies its modifiers. */
    private static String stancesMismatch(AutomationHost.Pack pack, Placed placed, JsonNode legacy) throws Exception {
        if (legacy == null || !legacy.has("stances")) return null;
        java.util.Set<String> ruleIds = new java.util.HashSet<>();
        currentAutomation.path("rules").forEach(rule -> ruleIds.add(rule.path("id").asText()));
        String[] levels = {"1", "2", "5", "8"};
        for (String levelText : levels) {
            int level = Integer.parseInt(levelText);
            int tier = level <= 1 ? 1 : level <= 4 ? 2 : level <= 7 ? 3 : 4;
            for (JsonNode stance : legacy.path("stances")) {
                String name = stance.path("name").asText();
                boolean chooses = stance.path("chooseTrait").asBoolean();
                String pick = name.toLowerCase(java.util.Locale.ROOT) + (chooses ? "-agility" : "");
                ObjectNode sheet = placed.sheet().deepCopy();
                sheet.put("level", level);
                sheet.withObject("/automationState").withObject("pools").putObject("focus").put("max", 6).put("value", 3);
                ObjectNode bare = sheet.deepCopy();
                RuntimeException error = runPick(pack, sheet, placed, "shift", pick, constantDice());
                boolean allowed = stance.path("tier").asInt() <= tier;
                if (!allowed) {
                    if (error == null) return "level " + level + ": " + name + " (tier " + stance.path("tier").asInt() + ") accepted below its tier";
                    continue;
                }
                if (error != null) return "level " + level + ": " + name + " rejected: " + error.getMessage();
                if (poolValues(sheet).getOrDefault("focus", -1) != 2) return name + " did not spend 1 Focus: " + poolValues(sheet);
                String label = chooses ? name + " (Agility)" : name;
                if (!effectLabels(pack, sheet).contains(label)) return name + " effect " + label + " missing from " + effectLabels(pack, sheet);
                for (JsonNode modifier : stance.path("effect").path("modifiers")) {
                    String stat = modifier.path("stat").asText();
                    if (stat.isEmpty()) continue;
                    ObjectNode held = statsOf(pack, sheet), without = statsOf(pack, bare);
                    for (String key : "thresholds".equals(stat) ? List.of("major", "severe") : List.of(stat))
                        if (held.path(key).asInt() - without.path(key).asInt() != modifier.path("value").asInt()) return name + " " + key + " legacy " + modifier.path("value").asInt() + " new " + (held.path(key).asInt() - without.path(key).asInt());
                    if (!"evasion".equals(stat) && !"thresholds".equals(stat)) return name + " unchecked stat " + stat;
                }
                for (JsonNode modifier : stance.path("effect").path("modifiers")) {
                    if (!modifier.has("roll")) continue;
                    int delta = damageDelta(pack, sheet, bare, modifier.path("roll").asText());
                    if (delta != modifier.path("value").asInt()) return name + " " + modifier.path("roll").asText() + " roll legacy +" + modifier.path("value").asInt() + " new +" + delta;
                }
                if (chooses && damageDelta(pack, sheet, bare, "damage") != 1) return name + " trait damage bonus legacy +1 new +" + damageDelta(pack, sheet, bare, "damage");
                for (String group : List.of("rollOptions", "mitigation", "actions"))
                    for (JsonNode part : stance.path("effect").path(group))
                        if (!ruleIds.contains(part.path("id").asText())) return name + " " + group + " " + part.path("id").asText() + " has no rule";
                if (stance.path("effect").has("damage") && !ruleIds.contains(name.toLowerCase(java.util.Locale.ROOT) + "-damage")) return name + " extra damage dice have no rule";
                for (JsonNode other : legacy.path("stances")) {
                    if (other == stance || other.path("tier").asInt() > tier) continue;
                    String second = other.path("name").asText().toLowerCase(java.util.Locale.ROOT) + (other.path("chooseTrait").asBoolean() ? "-agility" : "");
                    ObjectNode shifted = sheet.deepCopy();
                    if (runPick(pack, shifted, placed, "shift", second, constantDice()) != null) return "second shift into " + other.path("name").asText() + " rejected";
                    String secondLabel = other.path("chooseTrait").asBoolean() ? other.path("name").asText() + " (Agility)" : other.path("name").asText();
                    java.util.Set<String> held = effectLabels(pack, shifted);
                    if (held.contains(label) || !held.contains(secondLabel)) return "shift " + name + " -> " + other.path("name").asText() + " leaves " + held;
                    break;
                }
            }
        }
        for (JsonNode action : legacy.path("actions")) {
            if (!action.path("shiftStance").asBoolean() || !action.has("requiresFeature")) continue;
            String id = action.path("id").asText();
            ObjectNode without = placed.sheet().deepCopy();
            without.put("level", 5);
            if (runPick(pack, without.deepCopy(), placed, id, "reliable", constantDice()) == null) return id + " accepted without " + action.path("requiresFeature").asText();
            ObjectNode with = without.deepCopy();
            with.withArray("features").addObject().put("name", action.path("requiresFeature").asText());
            ObjectNode stressBefore = with.deepCopy();
            RuntimeException error = runPick(pack, with, placed, id, "reliable", constantDice());
            if (error != null) return id + " rejected with the feature: " + error.getMessage();
            if (with.at("/stress/on").asInt() != stressBefore.at("/stress/on").asInt() + 1) return id + " did not mark 1 Stress";
        }
        return null;
    }

    /** Runs a rule with explicit inputs; returns the host result or the rejection. */
    private static Object runInputs(AutomationHost.Pack pack, ObjectNode sheet, Placed placed, String ruleId, ObjectNode inputs) {
        return runInputsWith(pack, sheet, placed, ruleId, inputs, constantDice());
    }

    private static Object runInputsWith(AutomationHost.Pack pack, ObjectNode sheet, Placed placed, String ruleId, ObjectNode inputs, Random dice) {
        for (var element : HOST.sources(pack, sheet)) {
            if (!element.sourceId().equals(placed.sourceId())) continue;
            ObjectNode request = RuleJson.JSON.createObjectNode().put("requestId", "i" + REQUESTS.incrementAndGet()).put("expectedRevision", HOST.revision(sheet));
            request.putArray("selections").addObject().put("sourceInstanceId", element.instanceId()).put("ruleId", ruleId).put("bindingId", "sheet").set("inputs", inputs);
            try {
                return HOST.execute(pack, sheet, AutomationHost.event("action.request", "parity", 1), request,                 "self", "self", dice);
            } catch (RuntimeException e) {
                return e;
            }
        }
        return new IllegalStateException("source not on the sheet");
    }

    /** Warden's Protection replays the shared legacy fixture (warden-protection.json): rolled report, Hope cost, once per long rest. */
    private static String wardenMismatch(AutomationHost.Pack pack, Placed placed, JsonNode legacy) throws Exception {
        if (!placed.sourceId().contains("warden-of-renewal-specialization")) return null;
        if (legacy == null || !legacy.toString().contains("report")) return null;
        JsonNode fixture = MAPPER.readTree(Files.readString(Path.of("src/test/resources/dmmt/automation/parity/warden-protection.json")));
        for (JsonNode sample : fixture.path("cases")) {
            ObjectNode sheet = placed.sheet().deepCopy();
            sheet.withObject("/hope").put("on", fixture.at("/before/hope").asInt()).put("slots", fixture.at("/before/hopeSlots").asInt());
            ObjectNode poor = sheet.deepCopy();
            poor.withObject("/hope").put("on", fixture.at("/before/insufficientHope").asInt());
            if (!(runInputs(pack, poor, placed, "use", RuleJson.JSON.createObjectNode()) instanceof RuntimeException)) return "an unaffordable use was accepted";
            Object first = runInputsWith(pack, sheet, placed, "use", RuleJson.JSON.createObjectNode(), fixedDice(sample.path("face").asInt()));
            if (first instanceof RuntimeException e) return "use rejected: " + e.getMessage();
            if (!sample.path("report").asText().equals(((JsonNode) first).at("/actions/0/report").asText())) return "report " + ((JsonNode) first).at("/actions/0/report") + " legacy " + sample.path("report");
            if (sheet.at("/hope/on").asInt() != fixture.at("/expected/hopeAfter").asInt()) return "hope " + sheet.at("/hope/on") + " legacy " + fixture.at("/expected/hopeAfter");
            if (!(runInputs(pack, sheet, placed, "use", RuleJson.JSON.createObjectNode()) instanceof RuntimeException)) return "a second use before a long rest was accepted";
            for (String kind : List.of("short", "long")) {
                ObjectNode rested = sheet.deepCopy();
                ObjectNode origin = AutomationHost.event("action.request", "parity", 1).put("sessionId", "session");
                var snapshot = HOST.restEffectSnapshot(rested);
                ObjectNode prepared = HOST.prepareRest(pack, rested, origin, kind, HOST.revision(rested), RuleJson.JSON.createArrayNode(), constantDice());
                HOST.completeRest(pack, rested, origin, HOST.restProgress(pack, prepared), snapshot, RuleJson.JSON.createArrayNode(), constantDice());
                boolean reset = !(runInputs(pack, rested, placed, "use", RuleJson.JSON.createObjectNode()) instanceof RuntimeException);
                if (reset != fixture.at("/expected/" + kind + "RestResets").asBoolean()) return kind + " rest resets=" + reset;
            }
        }
        return null;
    }

    /** Healing Hands: the offered recovery buttons follow the legacy success/failure/critical split, and the recipient lock ends on a long rest only. */
    private static String healingHandsMismatch(AutomationHost.Pack pack, Placed placed, JsonNode legacy) {
        if (legacy == null || !legacy.toString().contains("\"splendorAction\":\"healingHands\"")) return null;
        for (int[] dice : new int[][] {{12, 11}, {1, 2}, {5, 5}}) {
            ObjectNode sheet = placed.sheet().deepCopy();
            ObjectNode event = AutomationHost.event("action.request", "parity", 1);
            JsonNode rolled = null;
            for (var element : HOST.sources(pack, sheet)) {
                if (!element.sourceId().equals(placed.sourceId())) continue;
                ObjectNode request = RuleJson.JSON.createObjectNode().put("requestId", "h" + REQUESTS.incrementAndGet()).put("expectedRevision", HOST.revision(sheet));
                request.putArray("selections");
                request.putObject("profileRoll").put("sourceInstanceId", element.instanceId()).put("profileId", "spellcast-roll").put("kind", "spellcast");
                try {
                    rolled = HOST.execute(pack, sheet, event, request, "self", "self", dualityDice(dice[0], dice[1]));
                } catch (RuntimeException e) {
                    return "spellcast roll rejected: " + e.getMessage();
                }
            }
            if (rolled == null) return "no source on the sheet";
            JsonNode check = rolled.at("/event/check");
            boolean success = check.path("critical").asBoolean() || check.path("total").asInt() >= 13;
            java.util.TreeSet<String> offered = new java.util.TreeSet<>(), expected = new java.util.TreeSet<>();
            for (JsonNode action : rolled.path("resultActions")) offered.add(norm(action.path("ruleId").asText()));
            offered.retainAll(List.of("hp", "stress", "failed-hp", "failed-stress"));
            expected.addAll(success ? List.of("hp", "stress") : List.of("failed-hp", "failed-stress"));
            if (!offered.equals(expected)) return "roll " + check.path("total") + (check.path("critical").asBoolean() ? " critical" : "") + " offers " + offered + " legacy " + expected;
        }
        for (String kind : List.of("short", "long")) {
            ObjectNode sheet = placed.sheet().deepCopy();
            ObjectNode effect = sheet.withObject("/automationState").withArray("effects").addObject().put("id", "hh").put("label", "Healing Hands recovery").put("kind", "debuff");
            effect.putArray("until").add("rest.long");
            try {
                ObjectNode origin = AutomationHost.event("action.request", "parity", 1).put("sessionId", "session");
                var snapshot = HOST.restEffectSnapshot(sheet);
                ObjectNode prepared = HOST.prepareRest(pack, sheet, origin, kind, HOST.revision(sheet), RuleJson.JSON.createArrayNode(), constantDice());
                HOST.completeRest(pack, sheet, origin, HOST.restProgress(pack, prepared), snapshot, RuleJson.JSON.createArrayNode(), constantDice());
            } catch (RuntimeException e) {
                return kind + " rest rejected: " + e.getMessage();
            }
            boolean held = effectLabels(pack, sheet).contains("Healing Hands recovery");
            if (held == "long".equals(kind)) return "the recovery lock " + (held ? "survives" : "ends on") + " a " + kind + " rest";
        }
        return null;
    }

    /** Beastforms: every assumed form is accepted and rejected like the legacy rules, pays the legacy Stress and sends a request the legacy rules build from. */
    private static String beastformMismatch(AutomationHost.Pack pack, Placed placed, JsonNode legacy) throws Exception {
        if (legacy == null || !legacy.has("beastforms")) return null;
        JsonNode classes = MAPPER.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("dmmap-daggerheart", "classes.json")));
        List<JsonNode> catalog = dmmt.online.LegacyRulesBridge.beastformCatalog(classes);
        int baseStress = legacy.at("/actions/0/cost/stress").asInt(1);
        int[] levels = {1, 2, 5, 8};
        for (int level : levels) {
            int tier = level >= 8 ? 4 : level >= 5 ? 3 : level >= 2 ? 2 : 1;
            for (JsonNode form : catalog) {
                boolean ordinary = !form.has("evolved") && !form.has("hybrid");
                String name = form.path("name").asText();
                ObjectNode choice = RuleJson.JSON.createObjectNode().put("form", name);
                ObjectNode inputs = RuleJson.JSON.createObjectNode();
                String ruleId = "use";
                int expectedStress = baseStress;
                if (ordinary) inputs.putObject("form").put("name", name).put("tier", form.path("tier").asInt());
                else {
                    ruleId = "use-" + slugOf(name);
                    if (form.has("evolved")) {
                        String base = null;
                        for (JsonNode candidate : catalog)
                            if (!candidate.has("evolved") && !candidate.has("hybrid") && candidate.path("tier").asInt() <= form.path("evolved").path("maxTier").asInt()) { base = candidate.path("name").asText(); break; }
                        inputs.putObject("base").put("name", base).put("tier", 1);
                        for (JsonNode candidate : catalog) if (candidate.path("name").asText().equals(base)) ((ObjectNode) inputs.get("base")).put("tier", candidate.path("tier").asInt());
                        choice.put("base", base);
                    } else {
                        JsonNode hybrid = form.path("hybrid");
                        expectedStress += hybrid.path("stress").asInt();
                        List<JsonNode> parts = new ArrayList<>();
                        for (JsonNode candidate : catalog)
                            if (!candidate.has("evolved") && !candidate.has("hybrid") && candidate.path("tier").asInt() <= hybrid.path("maxTier").asInt() && parts.size() < hybrid.path("count").asInt()) parts.add(candidate);
                        java.util.LinkedHashSet<String> advantages = new java.util.LinkedHashSet<>(), features = new java.util.LinkedHashSet<>(), names = new java.util.LinkedHashSet<>();
                        for (JsonNode part : parts) {
                            names.add(slugOf(part.path("name").asText()));
                            part.path("advantages").forEach(a -> advantages.add(a.asText()));
                            part.path("features").forEach(f -> features.add(f.path("name").asText()));
                        }
                        ArrayNode picks = choice.putArray("categories");
                        names.forEach(picks::add);
                        ArrayNode categoryInput = inputs.putArray("categories");
                        names.forEach(categoryInput::add);
                        ArrayNode advantageInput = inputs.putArray("advantages");
                        advantages.stream().limit(hybrid.path("advantages").asInt()).forEach(a -> { advantageInput.add(slugOf(a)); });
                        ArrayNode featureInput = inputs.putArray("features");
                        features.stream().limit(hybrid.path("features").asInt()).forEach(f -> featureInput.add(slugOf(f)));
                        choice.set("advantages", advantageInput.deepCopy());
                        choice.set("features", featureInput.deepCopy());
                    }
                }
                ObjectNode legacyEffect = dmmt.online.LegacyRulesBridge.beastformBuild(level, classes, choice, false, false);
                ObjectNode sheet = placed.sheet().deepCopy();
                sheet.put("level", level);
                sheet.withObject("/stress").put("on", 0).put("max", 12);
                Object outcome = runInputs(pack, sheet, placed, ruleId, inputs);
                String at = "level " + level + " " + name + ": ";
                if (outcome instanceof RuntimeException error) {
                    if (legacyEffect != null) return at + "rejected but the legacy rules accept it: " + error.getMessage();
                    continue;
                }
                if (legacyEffect == null) return at + "accepted but the legacy rules reject it";
                JsonNode request = null;
                for (JsonNode command : ((JsonNode) outcome).path("outbox")) if ("beastform.assume".equals(command.path("kind").asText())) request = command;
                if (request == null) return at + "no beastform request: " + outcome;
                if (!name.equals(request.path("form").asText())) return at + "requested form " + request.path("form").asText();
                if (dmmt.online.LegacyRulesBridge.beastformBuild(level, classes, request, false, false) == null) return at + "the legacy rules reject the sent request " + request;
                if (!legacyEffect.equals(dmmt.online.LegacyRulesBridge.beastformBuild(level, classes, request, false, false))) return at + "the sent request builds a different form";
                int marked = sheet.at("/stress/on").asInt();
                if (marked != expectedStress) return at + "marked " + marked + " Stress, legacy " + expectedStress;
            }
        }
        return null;
    }

    /** Companion rest handling replays the legacy companion.ts rules: unavailable companions return on a long rest and clear 1 Stress, an available one clears the owner's net cleared Stress. */
    private static String companionMismatch(AutomationHost.Pack pack, Placed placed, JsonNode legacy) {
        if (legacy == null || !legacy.has("companion")) return null;
        int slots = legacy.at("/companion/maxStress").asInt(6);
        for (String kind : List.of("short", "long")) {
            for (boolean unavailable : new boolean[] {false, true}) {
                for (int stressOn : new int[] {1, 3}) {
                    ObjectNode sheet = placed.sheet().deepCopy();
                    sheet.putObject("companion").put("name", "Fox").put("unavailable", unavailable).putObject("stress").put("slots", slots).put("on", stressOn);
                    sheet.withObject("/automationState").withObject("pools").putObject("companion-stress-slots").put("max", slots).put("value", slots - stressOn);
                    int ownerBefore = sheet.at("/stress/on").asInt();
                    ObjectNode request = RuleJson.JSON.createObjectNode().put("kind", kind).put("expectedRevision", HOST.revision(sheet));
                    request.putArray("preparation");
                    ObjectNode move = request.putArray("moves").addObject();
                    move.putObject("selection").put("sourceInstanceId", "ruleset:base").put("ruleId", "long".equals(kind) ? "rest-stress-all" : "rest-stress").put("bindingId", "move");
                    move.putArray("options");
                    request.putArray("completion");
                    int companionOn = stressOn;
                    boolean available = !unavailable;
                    if ("long".equals(kind) && unavailable) { available = true; companionOn = Math.max(0, companionOn - 1); }
                    try {
                        HOST.stageSelfRest(pack, sheet, AutomationHost.event("action.request", "parity", 0).put("sessionId", "session"), request, StructuredRollTest.faces(1));
                    } catch (RuntimeException e) {
                        return kind + " unavailable=" + unavailable + " on=" + stressOn + " rejected: " + e.getMessage();
                    }
                    int net = Math.max(0, ownerBefore - sheet.at("/stress/on").asInt());
                    if (available) companionOn = Math.max(0, companionOn - net);
                    String where = kind + " unavailable=" + unavailable + " on=" + stressOn;
                    if (sheet.at("/companion/stress/on").asInt() != companionOn) return where + " companion stress legacy " + companionOn + " new " + sheet.at("/companion/stress/on").asInt();
                    boolean wantUnavailable = unavailable && !"long".equals(kind);
                    if (sheet.at("/companion/unavailable").asBoolean() != wantUnavailable) return where + " unavailable legacy " + wantUnavailable + " new " + sheet.at("/companion/unavailable").asBoolean();
                }
            }
        }
        return null;
    }

    /** Lunar Phases: the d6 roll or advance picks the phase effect of the face; the die wraps from 6 to 1 and advancing needs a rolled die. */
    private static String lunarMismatch(AutomationHost.Pack pack, Placed placed, JsonNode legacy) {
        if (legacy == null || !legacy.has("lunarPhases")) return null;
        ObjectNode base = placed.sheet().deepCopy();
        String counterLabel = legacy.at("/effectCounters/0/label").asText();
        String poolId = null;
        for (var entry : currentAutomation.path("pools").properties())
            if (counterLabel.equals(entry.getValue().path("label").asText())) poolId = entry.getKey();
        if (poolId == null) return "no pool labelled " + counterLabel;
        if (runSheetAction(pack, base.deepCopy(), placed, "advance", constantDice()) == null) return "advance accepted before the die was rolled";
        for (int face = 1; face <= 6; face++) {
            ObjectNode sheet = base.deepCopy();
            int rolled = face;
            RuntimeException error = runSheetAction(pack, sheet, placed, "roll", fixedDice(rolled));
            if (error != null) return "roll rejected: " + error.getMessage();
            String phase = lunarPhaseProblem(pack, placed, legacy, sheet, rolled, poolId, "rolled");
            if (phase != null) return phase;
            error = runSheetAction(pack, sheet, placed, "advance", constantDice());
            if (error != null) return "advance rejected at " + face + ": " + error.getMessage();
            phase = lunarPhaseProblem(pack, placed, legacy, sheet, face % 6 + 1, poolId, "advanced");
            if (phase != null) return phase;
        }
        return null;
    }

    private static String lunarPhaseProblem(AutomationHost.Pack pack, Placed placed, JsonNode legacy, ObjectNode sheet, int face, String poolId, String how) {
        if (poolValues(sheet).getOrDefault(poolId.replace("-", ""), -1) != face) return how + " die legacy " + face + " new " + poolValues(sheet);
        JsonNode phase = null;
        for (JsonNode candidate : legacy.path("lunarPhases"))
            for (JsonNode f : candidate.path("faces")) if (f.asInt() == face) phase = candidate;
        if (phase == null) return "legacy has no phase for " + face;
        JsonNode effect = phase.path("effect");
        ObjectNode bare = placed.sheet().deepCopy();
        for (JsonNode modifier : effect.path("modifiers")) {
            String stat = modifier.path("stat").asText();
            if (stat.isEmpty()) continue;
            ObjectNode held = statsOf(pack, sheet), without = statsOf(pack, bare);
            for (String key : "thresholds".equals(stat) ? List.of("major", "severe") : List.of(stat)) {
                int delta = held.path(key).asInt() - without.path(key).asInt();
                if (delta != modifier.path("value").asInt()) return how + " " + face + " " + key + " legacy +" + modifier.path("value").asInt() + " new " + delta;
            }
        }
        try {
            for (JsonNode modifier : effect.path("modifiers")) {
                if (!"damage".equals(modifier.path("roll").asText())) continue;
                int[] totals = new int[2];
                int i = 0;
                for (ObjectNode target : List.of(withWeapon(bare), withWeapon(sheet))) {
                    String instance = target.withArray("activeWeapons").get(0).path("instanceId").asText();
                    totals[i++] = rollTotal(HOST.execute(pack, target, AutomationHost.event("action.request", "parity", 1), profileRequest(target, instance, "damage", null), "self", "self", constantDice()));
                }
                if (totals[1] - totals[0] != modifier.path("value").asInt()) return how + " " + face + " damage legacy +" + modifier.path("value").asInt() + " new " + (totals[1] - totals[0]);
            }
        } catch (Exception e) {
            return "damage probe failed: " + e;
        }
        long active = effectLabels(pack, sheet).stream().filter(label -> label.startsWith("Lunar Phases")).count();
        return active == 1 ? null : how + " " + face + " active phase effects " + active;
    }

    /** Unstoppable: a leveled counter effect adds its die to damage, reduces physical severity, advances after dealt damage and ends on overflow. */
    private static String counterEffectMismatch(AutomationHost.Pack pack, Placed placed, JsonNode legacy) throws Exception {
        JsonNode action = legacy == null ? null : legacy.at("/actions/0");
        JsonNode effect = action == null ? null : action.at("/steps/0/effect");
        if (effect == null || !effect.path("counter").isObject() || !legacy.toString().contains("advanceEffect")) return null;
        String name = effect.path("name").asText();
        for (int level : new int[] {4, 5}) {
            ObjectNode sheet = withWeapon(placed.sheet());
            sheet.put("level", level);
            int max = effect.at("/counter/max").asInt();
            for (var entry : effect.at("/counter/maxByLevel").properties()) if (Integer.parseInt(entry.getKey()) <= level) max = entry.getValue().asInt();
            ObjectNode without = sheet.deepCopy();
            String instance = without.withArray("activeWeapons").get(0).path("instanceId").asText();
            int plain = rollTotal(HOST.execute(pack, without, AutomationHost.event("action.request", "parity", 1), profileRequest(without, instance, "damage", null), "self", "self", constantDice()));
            RuntimeException error = runSheetAction(pack, sheet, placed, action.path("id").asText(), constantDice());
            if (error != null) return "use rejected: " + error.getMessage();
            JsonNode held = heldEffect(sheet, name);
            if (held == null) return name + " not started";
            for (int value = effect.at("/counter/value").asInt(); value <= max + 1; value++) {
                if (value > max) {
                    if (heldEffect(sheet, name) != null) return "level " + level + " effect survives overflow past d" + max;
                    break;
                }
                held = heldEffect(sheet, name);
                if (held == null) return "level " + level + " effect ended early at " + value;
                if (effectValue(held) != value) return "level " + level + " die legacy " + value + " new " + effectValue(held) + " " + held;
                ObjectNode event = AutomationHost.event("action.request", "parity", 1);
                JsonNode picked = null;
                for (JsonNode option : weaponOptions(pack, sheet, event, instance, "damage")) if (ownOption(option) && "damage".equals(norm(option.path("ruleId").asText()))) picked = option;
                if (picked == null) return "level " + level + " die " + value + " damage option not offered";
                JsonNode rolled = HOST.execute(pack, sheet, event, profileRequest(sheet, instance, "damage", picked), "self", "self", constantDice());
                if (rollTotal(rolled) != plain + value) return "level " + level + " die " + value + " damage legacy " + (plain + value) + " new " + rollTotal(rolled);
                try {
                    if (claimResultWith(pack, sheet, event, rolled, "advance", constantDice()) == null) return "advance is not offered after damage";
                } catch (RuntimeException e) {
                    return "advance rejected: " + e.getMessage();
                }
            }
        }
        return null;
    }

    /** The party shares the downtime move with the same members as the legacy sync; a grantee trains into the fallback effect. */
    private static String partyDowntimeMismatch(AutomationHost.Pack pack, Placed placed, JsonNode legacy) {
        if (legacy == null || !legacy.path("partyDowntime").asBoolean()) return null;
        Map<String, ObjectNode> legacySheets = new LinkedHashMap<>();
        Map<String, ObjectNode> newSheets = new LinkedHashMap<>();
        for (String id : List.of("owner", "ally", "third")) {
            ObjectNode sheet = RuleJson.JSON.createObjectNode();
            sheet.putArray("features");
            if ("owner".equals(id)) sheet.withArray("features").addObject().put("name", "Martial Preparation").put("text", "").set("automation", legacy.deepCopy());
            legacySheets.put(id, sheet);
            newSheets.put(id, "owner".equals(id) ? placed.sheet().deepCopy() : standardSheet());
        }
        dmmt.online.LegacyRulesBridge.synchronizeDowntime(legacySheets);
        HOST.synchronizePartyGrants(pack, newSheets, Map.of());
        for (String id : legacySheets.keySet()) {
            boolean legacyHas = legacySheets.get(id).path("effects").size() > 0, newHas = newSheets.get(id).path("partyGrants").size() > 0;
            if (legacyHas != newHas) return id + " grant legacy " + legacyHas + " new " + newHas;
        }
        JsonNode fallback = legacy.at("/actions/0/steps/0/fallbackEffect");
        ObjectNode ally = newSheets.get("ally");
        String instance = ally.at("/partyGrants/0/instanceId").asText();
        try {
            ObjectNode origin = AutomationHost.event("action.request", "parity", 0).put("sessionId", "session");
            ObjectNode prepared = HOST.prepareRest(pack, ally, origin, "short", 0, RuleJson.JSON.createArrayNode(), constantDice());
            var progress = HOST.restProgress(pack, prepared);
            ObjectNode selection = RuleJson.JSON.createObjectNode().put("sourceInstanceId", instance)
                    .put("ruleId", legacy.at("/actions/0/id").asText()).put("bindingId", "downtime");
            HOST.restMove(pack, ally, (ObjectNode) prepared.get("event"), progress, selection, RuleJson.JSON.createArrayNode(), constantDice());
        } catch (RuntimeException e) {
            return "grantee downtime rejected: " + e.getMessage();
        }
        if (!effectLabels(pack, ally).contains(fallback.path("name").asText())) return "grantee has no " + fallback.path("name").asText();
        for (JsonNode option : fallback.path("rollOptions"))
            if (!currentAutomation.toString().contains("\"id\":\"" + option.path("id").asText() + "\"")) return "roll option " + option.path("id").asText() + " missing";
        return null;
    }

    private static ObjectNode withEffects(ObjectNode sheet, String... names) {
        ObjectNode copy = sheet.deepCopy();
        ArrayNode effects = copy.withObject("/automationState").withArray("effects");
        for (String name : names) effects.addObject().put("id", "held-" + effects.size()).put("sourceInstanceId", "owner").put("label", name).put("kind", "buff");
        return copy;
    }

    /** A channel benefit is active exactly while its required effect is held; its modifiers, actions and rules follow that gate. */
    private static String channelMismatch(AutomationHost.Pack pack, Placed placed, JsonNode legacy) {
        JsonNode benefit = legacy == null ? null : legacy.path("channelBenefit");
        if (benefit == null || benefit.isMissingNode()) return null;
        JsonNode effect = benefit.path("effect");
        String name = effect.path("name").asText(), required = effect.path("requiresEffect").asText();
        boolean aura = "aura".equals(benefit.path("element").asText());
        String element = name.substring(name.indexOf(": ") + 2);
        String[] held = aura ? new String[] {"Channeling: " + element, "Elemental Aura"} : new String[] {required};
        ObjectNode channeled = withEffects(placed.sheet(), held), bare = withEffects(placed.sheet());
        if (!effectLabels(pack, channeled).contains(name)) return "benefit " + name + " is not active while channeling " + effectLabels(pack, channeled);
        if (effectLabels(pack, bare).contains(name)) return "benefit " + name + " is active without channeling";
        String other = aura ? "Channeling: Spirit" : "Channeling: Spirit";
        if (effectLabels(pack, withEffects(placed.sheet(), aura ? new String[] {other, "Elemental Aura"} : new String[] {other})).contains(name))
            return "benefit " + name + " is active for another element";
        if (aura && effectLabels(pack, withEffects(placed.sheet(), "Channeling: " + element)).contains(name))
            return "aura " + name + " is active before the aura is activated";
        for (JsonNode modifier : effect.path("modifiers")) {
            if (!"evasion".equals(modifier.path("stat").asText())) continue;
            int delta = statsOf(pack, channeled).path("evasion").asInt() - statsOf(pack, bare).path("evasion").asInt();
            if (delta != modifier.path("value").asInt()) return "evasion legacy +" + modifier.path("value").asInt() + " new " + delta;
        }
        for (JsonNode action : legacy.path("actions")) {
            String id = action.path("id").asText();
            ObjectNode event = AutomationHost.event("action.request", "parity", 0);
            boolean on = false, off = false;
            for (JsonNode offered : HOST.describe(pack, channeled, event, "sheet", "self").path("actions"))
                on |= id.equals(norm(offered.path("ruleId").asText())) && offered.path("available").asBoolean(true);
            for (JsonNode offered : HOST.describe(pack, bare, event, "sheet", "self").path("actions"))
                off |= id.equals(norm(offered.path("ruleId").asText())) && offered.path("available").asBoolean(true);
            if (!on) return "action " + id + " is not offered while channeling";
            if (off) return "action " + id + " is offered without channeling";
        }
        JsonNode extra = effect.at("/damage/extraDice");
        if (!extra.isMissingNode() && !currentAutomation.toString().contains("\"count\":" + extra.asInt() + "}")) return "extra damage dice " + extra.asInt() + " missing";
        JsonNode mitigation = effect.path("mitigation");
        if (mitigation.size() > 0) {
            JsonNode roll = mitigation.get(0).path("roll");
            String text = currentAutomation.toString();
            if (!text.contains("\"sides\":" + roll.path("sides").asInt()) || !text.contains("\"atLeast\":" + roll.path("success").asInt()))
                return "mitigation roll d" + roll.path("sides").asInt() + " success " + roll.path("success").asInt() + " missing";
        }
        return null;
    }

    /** Banish stores the highest of one d20 per Spellcast point on the card, like the legacy server. */
    private static String banishMismatch(AutomationHost.Pack pack, Placed placed, JsonNode legacy) {
        if (legacy == null || !legacy.path("banishDifficulty").asBoolean()) return null;
        JsonNode profile = null;
        for (JsonNode candidate : currentAutomation.path("profiles"))
            if (candidate.path("id").asText().startsWith("spellcast-roll")) profile = candidate;
        if (profile == null) return "no spellcast profile";
        for (int spellcast : new int[] {1, 3}) {
            ObjectNode sheet = placed.sheet().deepCopy();
            for (JsonNode trait : sheet.withArray("traits")) ((ObjectNode) trait).put("mod", spellcast);
            int count = dmmt.online.LegacyRulesBridge.spellcastTotal(sheet, 0);
            int[] sequence = {7, 14, 3, 18, 11, 5};
            int highest = 0;
            for (int i = 0; i < count; i++) highest = Math.max(highest, sequence[i % sequence.length]);
            Random dice = new Random(0) {
                private int d20;
                @Override public int nextInt(int bound) {
                    if (bound == 20) return sequence[d20++ % sequence.length] - 1;
                    return Math.min(3, bound) - 1;
                }
            };
            JsonNode rolled = null;
            ObjectNode event = AutomationHost.event("action.request", "parity", 1);
            RuntimeException last = null;
            for (var element : HOST.sources(pack, sheet)) {
                if (!element.sourceId().equals(placed.sourceId())) continue;
                ObjectNode copy = sheet.deepCopy();
                ObjectNode request = RuleJson.JSON.createObjectNode().put("requestId", "r").put("expectedRevision", HOST.revision(copy));
                request.putArray("selections");
                request.putObject("profileRoll").put("sourceInstanceId", element.instanceId()).put("profileId", profile.path("id").asText()).put("kind", "spellcast");
                try {
                    rolled = HOST.execute(pack, copy, event, request, "self", "self", dualityDice(9, 3));
                    sheet = copy;
                    break;
                } catch (RuntimeException e) {
                    last = e;
                }
            }
            if (rolled == null) return "spellcast roll rejected: " + (last == null ? "no source" : last.getMessage());
            JsonNode claimed;
            try {
                claimed = claimResultWith(pack, sheet, event, rolled, "difficulty", dice);
            } catch (RuntimeException e) {
                return "difficulty rejected: " + e.getMessage();
            }
            if (claimed == null) return "banish difficulty is not offered";
            if (!sheet.toString().contains("\"banishDifficulty\":" + highest)) return "spellcast " + count + " banish legacy " + highest + " new " + sheet.at("/loadout");
        }
        return null;
    }

    private static JsonNode claimResultWith(AutomationHost.Pack pack, ObjectNode sheet, ObjectNode event, JsonNode rolled, String ruleId, Random dice) {
        for (JsonNode action : rolled.path("resultActions")) {
            if (!ruleId.equals(norm(action.path("ruleId").asText())) || !ownOption(action)) continue;
            ObjectNode claim = RuleJson.JSON.createObjectNode().put("requestId", "c" + REQUESTS.incrementAndGet()).put("expectedRevision", HOST.revision(sheet))
                    .put("resultId", rolled.at("/event/resultId").asText());
            claim.putArray("selections").addObject().put("sourceInstanceId", action.path("sourceInstanceId").asText())
                    .put("ruleId", action.path("ruleId").asText()).put("bindingId", action.path("bindingId").asText());
            return HOST.execute(pack, sheet, event, claim, "self", "self", dice);
        }
        return null;
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    private static ObjectNode deathEvent() {
        return AutomationHost.event("action.request", "parity", 1).put("sessionId", "session");
    }

    private static ObjectNode deathBase(Placed placed) {
        ObjectNode base = placed.sheet().deepCopy();
        base.withObject("/hp").put("on", 6).put("slots", 6);
        base.put("permanentHp", 0);
        base.putArray("effects");
        return base;
    }

    /** Death moves: the legacy DeathMoves rules are the baseline for the converted death roll options and death buttons. */
    private static String deathMismatch(AutomationHost.Pack pack, Placed placed, JsonNode legacy) {
        if (legacy == null || !legacy.path("deathMove").isObject()) return null;
        String kind = legacy.path("deathMove").path("kind").asText();
        String key = kind + ":owner", name = "Source";
        try {
            switch (kind) {
                case "hopeBonus", "hopeSpend", "mask" -> {
                    return deathRollMismatch(pack, placed, legacy, kind, key, name);
                }
                case "undying" -> {
                    return undyingMismatch(pack, placed, legacy, key, name);
                }
                case "lifeWard" -> {
                    // Legacy Life Ward is manual (only the ally effect is automated, covered by the actions probe); the death button is a new-engine superset.
                    return null;
                }
                default -> {
                    return deathButtonMismatch(pack, placed, legacy, kind, key, name);
                }
            }
        } catch (RuntimeException e) {
            return "rejected: " + e.getMessage() + " @ " + frames(e);
        } catch (Exception e) {
            return "rejected: " + e;
        }
    }

    private static JsonNode legacyTake(ObjectNode sheet, ObjectNode request, String key, String name, JsonNode legacy, Random random) {
        JsonNode entry = sheet.withArray("inventory").isEmpty() ? (sheet.withArray("loadout").isEmpty() ? null : sheet.withArray("loadout").get(0))
                : sheet.withArray("inventory").get(0);
        return dmmt.online.LegacyDeathBridge.take(sheet, request, key, name, legacy, entry, random);
    }

    private static String deathRollMismatch(AutomationHost.Pack pack, Placed placed, JsonNode legacy, String kind, String key, String name) {
        boolean spends = !"hopeBonus".equals(kind);
        for (String move : List.of("risk", "avoid")) {
            for (int[] faces : new int[][] {{5, 3}, {2, 9}, {4, 4}, {1, 12}, {12, 1}}) {
                for (int hope : new int[] {3, 0}) {
                    for (boolean spend : spends ? new boolean[] {false, true} : new boolean[] {false}) {
                        String label = kind + " " + move + " " + faces[0] + "/" + faces[1] + " hope " + hope + " spend " + spend;
                        ObjectNode base = deathBase(placed);
                        base.withObject("/hope").put("on", hope);
                        ObjectNode ls = base.deepCopy(), ns = base.deepCopy();
                        if ("mask".equals(kind)) {
                            ls.withObject("/resources").put("death-mask", 1);
                            ObjectNode gain = RuleJson.JSON.createObjectNode().put("requestId", "g" + REQUESTS.incrementAndGet()).put("expectedRevision", HOST.revision(ns));
                            gain.putArray("selections").addObject().put("sourceInstanceId", "owner").put("ruleId", "gain-token").put("bindingId", "sheet");
                            HOST.execute(pack, ns, deathEvent(), gain, "self", "self", constantDice());
                        }
                        ObjectNode request = MAPPER.createObjectNode().put("move", move);
                        if (spend && "hopeSpend".equals(kind)) request.put("blessedHope", 1);
                        if (spend && "mask".equals(kind)) request.put("mask", true).put("wearingMask", true);
                        JsonNode taken = null;
                        String legacyError = null;
                        try {
                            taken = legacyTake(ls, request, key, name, legacy, dualityDice(faces[0], faces[1]));
                        } catch (IllegalArgumentException e) {
                            legacyError = e.getMessage();
                        }
                        JsonNode chosen = null;
                        for (JsonNode option : HOST.describeDeath(pack, ns, deathEvent(), "self").at("/rolls/" + move + "/options"))
                            if (ownOption(option)) chosen = option;
                        if (spend && chosen == null) {
                            if (legacyError == null) return label + ": legacy allowed the spend but the new engine offers no option";
                            continue;
                        }
                        if (spend && legacyError != null) return label + ": new engine offers an option legacy rejects (" + legacyError + ")";
                        if (legacyError != null && !spend) return label + ": legacy rejected a plain roll: " + legacyError;
                        ObjectNode roll = checkRequest(ns, "action", "Agility", spend ? chosen : null);
                        roll.put("requestId", "d" + REQUESTS.incrementAndGet()).remove("checkRoll");
                        roll.putObject("deathRoll").put("move", move);
                        JsonNode receipt = HOST.execute(pack, ns, deathEvent(), roll, "self", "self", dualityDice(faces[0], faces[1]));
                        JsonNode check = receipt.at("/event/check");
                        if ("risk".equals(move)) {
                            String outcome = check.path("critical").asBoolean() ? "crit" : "success".equals(check.path("outcome").asText()) ? "hope" : "fear";
                            JsonNode result = taken.path("deathResult");
                            if (!outcome.equals(result.path("outcome").asText()) || check.path("adjustedHope").asInt() != result.path("adjustedHope").asInt())
                                return label + ": legacy " + result.path("outcome").asText() + " adjusted " + result.path("adjustedHope").asInt()
                                        + " new " + outcome + " adjusted " + check.path("adjustedHope").asInt();
                        } else if (ls.path("scars").asInt() != ns.path("scars").asInt() || ls.at("/hope/slots").asInt() != ns.at("/hope/slots").asInt())
                            return label + ": scars legacy " + ls.path("scars").asInt() + " new " + ns.path("scars").asInt();
                        if (ls.at("/hope/on").asInt() != ns.at("/hope/on").asInt())
                            return label + ": hope legacy " + ls.at("/hope/on").asInt() + " new " + ns.at("/hope/on").asInt();
                        int legacyUses = ls.path("usage").path(key).path("used").asInt();
                        if (legacyUses != usesTaken(ns)) return label + ": uses legacy " + legacyUses + " new " + usesTaken(ns);
                        if ("mask".equals(kind) && spend && poolTotal(ns) != ls.at("/resources/death-mask").asInt())
                            return label + ": mask tokens legacy " + ls.at("/resources/death-mask").asInt() + " new " + poolTotal(ns);
                    }
                }
            }
        }
        return null;
    }

    private static int poolTotal(JsonNode sheet) {
        java.util.Map<String, Integer> values = new java.util.TreeMap<>();
        collectPools(sheet.path("automationState"), values);
        return values.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static String deathButtonMismatch(AutomationHost.Pack pack, Placed placed, JsonNode legacy, String kind, String key, String name) {
        for (int hope : new int[] {3, 0}) {
            for (int permanent : new int[] {0, 2}) {
                for (int dice : new int[] {2, 5}) {
                    for (int qty : "egg".equals(kind) ? new int[] {2, 0} : new int[] {2}) {
                        String label = kind + " hope " + hope + " permanent " + permanent + " die " + dice + " qty " + qty;
                        ObjectNode base = deathBase(placed);
                        base.withObject("/hope").put("on", hope);
                        base.put("permanentHp", permanent);
                        if (!base.withArray("inventory").isEmpty()) ((ObjectNode) base.withArray("inventory").get(0)).put("qty", qty);
                        ObjectNode ls = base.deepCopy(), ns = base.deepCopy();
                        String legacyError = null;
                        try {
                            legacyTake(ls, MAPPER.createObjectNode().put("move", kind), key, name, legacy, fixedDice(dice));
                        } catch (IllegalArgumentException e) {
                            legacyError = e.getMessage();
                        }
                        JsonNode button = null;
                        for (JsonNode action : HOST.describeDeath(pack, ns, deathEvent(), "self").path("actions"))
                            if (ownOption(action) && "death".equals(action.path("bindingId").asText()) && action.path("available").asBoolean(true)) button = action;
                        if (button == null) {
                            if (legacyError == null) return label + ": legacy allowed it but the new engine offers no death button";
                            continue;
                        }
                        ObjectNode request = RuleJson.JSON.createObjectNode().put("requestId", "b" + REQUESTS.incrementAndGet())
                                .put("expectedRevision", HOST.revision(ns)).put("deathAction", true);
                        request.putArray("selections").addObject().put("sourceInstanceId", button.path("sourceInstanceId").asText())
                                .put("ruleId", button.path("ruleId").asText()).put("bindingId", "death").putObject("inputs");
                        try {
                            HOST.execute(pack, ns, deathEvent(), request, "self", "self", fixedDice(dice));
                        } catch (RuntimeException rejected) {
                            if (legacyError == null) return label + ": legacy allowed it but the new engine rejected: " + rejected.getMessage() + " @ " + frames(rejected);
                            continue;
                        }
                        if (legacyError != null) return label + ": new engine allowed it but legacy rejected: " + legacyError;
                        if (ls.at("/hp/on").asInt() != ns.at("/hp/on").asInt()) return label + ": hp legacy " + ls.at("/hp/on").asInt() + " new " + ns.at("/hp/on").asInt();
                        if (ls.at("/hope/on").asInt() != ns.at("/hope/on").asInt()) return label + ": hope legacy " + ls.at("/hope/on").asInt() + " new " + ns.at("/hope/on").asInt();
                        if (ls.withArray("loadout").size() != ns.withArray("loadout").size() || ls.withArray("vault").size() != ns.withArray("vault").size())
                            return label + ": loadout/vault legacy " + ls.withArray("loadout").size() + "/" + ls.withArray("vault").size()
                                    + " new " + ns.withArray("loadout").size() + "/" + ns.withArray("vault").size();
                        if (!ls.withArray("inventory").isEmpty() && ls.at("/inventory/0/qty").asInt() != ns.at("/inventory/0/qty").asInt())
                            return label + ": qty legacy " + ls.at("/inventory/0/qty").asInt() + " new " + ns.at("/inventory/0/qty").asInt();
                        boolean legacySpectral = false;
                        for (JsonNode effect : ls.path("effects")) legacySpectral |= "Spectral Form".equals(effect.path("name").asText());
                        if (legacySpectral != effectLabels(pack, ns).contains("Spectral Form"))
                            return label + ": Spectral Form legacy " + legacySpectral + " new " + !legacySpectral;
                        int legacyUses = ls.path("usage").path(key).path("used").asInt();
                        if (legacyUses != usesTaken(ns)) return label + ": uses legacy " + legacyUses + " new " + usesTaken(ns);
                    }
                }
            }
        }
        return null;
    }

    /** Won't Stay Dead: a failed Risk It All may permanently mark one HP to succeed with Hope. */
    private static String undyingMismatch(AutomationHost.Pack pack, Placed placed, JsonNode legacy, String key, String name) {
        ObjectNode base = deathBase(placed);
        ObjectNode ls = base.deepCopy(), ns = base.deepCopy();
        JsonNode taken = legacyTake(ls, MAPPER.createObjectNode().put("move", "risk"), key, name, legacy, dualityDice(2, 8));
        ObjectNode roll = MAPPER.createObjectNode().put("deathMove", "risk");
        roll.set("deathResult", taken.path("deathResult"));
        roll.putObject("featureRoll");
        dmmt.online.LegacyDeathBridge.recover(ls, roll, MAPPER.createObjectNode().put("undying", true), true);
        ObjectNode recover = MAPPER.createObjectNode().put("hp", 2).put("stress", 0);
        dmmt.online.LegacyDeathBridge.recover(ls, roll, recover, true);

        ObjectNode request = RuleJson.JSON.createObjectNode().put("requestId", "u" + REQUESTS.incrementAndGet()).put("expectedRevision", HOST.revision(ns));
        request.putArray("selections");
        request.putObject("deathRoll").put("move", "risk");
        JsonNode rolled = HOST.execute(pack, ns, deathEvent(), request, "self", "self", dualityDice(2, 8));
        if (!"failure".equals(rolled.at("/event/check/outcome").asText())) return "new engine did not fail the Risk It All roll";
        JsonNode revised = claimResult(pack, ns, deathEvent(), rolled, "undying");
        if (revised == null) return "no Won't Stay Dead button offered after a failed Risk It All roll";
        if (!"success".equals(revised.at("/event/check/outcome").asText())) return "converted roll did not succeed with Hope";
        ObjectNode apply = RuleJson.JSON.createObjectNode().put("requestId", "a" + REQUESTS.incrementAndGet()).put("expectedRevision", HOST.revision(ns))
                .put("resultId", revised.at("/event/resultId").asText());
        ObjectNode selection = apply.putArray("selections").addObject().put("sourceInstanceId", "ruleset:base")
                .put("ruleId", "death-recover").put("bindingId", "claim");
        selection.putObject("inputs").put("hp", 2).put("stress", 0);
        HOST.execute(pack, ns, deathEvent(), apply, "self", "self", constantDice());
        int legacyMarked = ls.at("/hp/on").asInt() - ls.path("permanentHp").asInt();
        if (legacyMarked != ns.at("/hp/on").asInt() || ls.at("/stress/on").asInt() != ns.at("/stress/on").asInt())
            return "after recovery marked HP legacy " + legacyMarked + " new " + ns.at("/hp/on").asInt()
                    + " stress legacy " + ls.at("/stress/on").asInt() + " new " + ns.at("/stress/on").asInt();
        if (ls.path("permanentHp").asInt() != base.at("/hp/slots").asInt() - ns.at("/hp/slots").asInt())
            return "permanently marked HP legacy " + ls.path("permanentHp").asInt() + " new " + (base.at("/hp/slots").asInt() - ns.at("/hp/slots").asInt());
        return null;
    }

    private static String armorSignature(java.util.Set<String> labels, ObjectNode sheet) {
        return String.join("+", new java.util.TreeSet<>(labels)) + "|armor=" + sheet.at("/armorTrack/on").asInt() + "|hope=" + sheet.at("/hope/on").asInt();
    }

    /** Replays equip, mark, clear and zero-hope steps through the legacy host class and through the new host's save flow. */
    private static String armorMismatch(AutomationHost.Pack pack, Placed placed, JsonNode legacyAutomation) throws Exception {
        if (legacyAutomation == null || ARMOR_HOSTED.stream().noneMatch(legacyAutomation::has)) return null;
        String name = placed.sheet().at("/activeArmor/name").asText();
        ObjectNode legacyPrevious = standardSheet();
        legacyPrevious.withObject("/armorTrack").put("on", 0);
        legacyPrevious.withArray("effects");
        legacyPrevious.withObject("/activeArmor").put("name", "Other").putObject("automation");
        ObjectNode newPrevious = legacyPrevious.deepCopy();
        newPrevious.set("activeArmor", RuleJson.JSON.createObjectNode().put("name", "Other"));
        for (int index = 0; index < ARMOR_STEPS.length; index++) {
            int[] step = ARMOR_STEPS[index];
            ObjectNode legacyNext = legacyPrevious.deepCopy();
            legacyNext.withObject("/activeArmor").put("name", name).set("automation", legacyAutomation.deepCopy());
            legacyNext.withObject("/armorTrack").put("on", step[0]);
            legacyNext.withObject("/hope").put("on", step[1]);
            dmmt.online.ArmorFeatureRules.normalize(legacyPrevious, legacyNext);
            java.util.Set<String> legacyLabels = new java.util.HashSet<>();
            for (JsonNode effect : legacyNext.path("effects")) legacyLabels.add(effect.path("name").asText());

            ObjectNode next = placed.sheet().deepCopy();
            if (newPrevious.has("automationState")) next.set("automationState", newPrevious.get("automationState").deepCopy());
            next.withObject("/armorTrack").put("on", step[0]);
            next.withObject("/hope").put("on", step[1]);
            java.util.Set<String> labels;
            try {
                saveThroughHost(pack, newPrevious, next);
                labels = effectLabels(pack, next);
            } catch (RuntimeException e) {
                return "step " + index + " rejected: " + e.getMessage();
            }
            String want = armorSignature(legacyLabels, legacyNext), got = armorSignature(labels, next);
            if (!want.equals(got)) return "step " + index + " " + java.util.Arrays.toString(step) + " legacy=" + want + " new=" + got;
            legacyPrevious = legacyNext;
            newPrevious = next;
        }
        return null;
    }

    /** Mirrors the server's sheet save: effect transitions, equipment changes and track changes become host events. */
    private static void saveThroughHost(AutomationHost.Pack pack, ObjectNode previous, ObjectNode saved) {
        List<ObjectNode> events = new ArrayList<>();
        if (!HOST.projection().equipmentChanges(previous, saved).isEmpty())
            HOST.endEffectsOfRemovedSources(pack, saved, AutomationHost.event("action.request", "parity", 0));
        events.addAll(HOST.effectTransitions(pack, previous, saved, AutomationHost.event("action.request", "parity", 0)));
        for (var change : HOST.projection().equipmentChanges(previous, saved)) {
            ObjectNode event = AutomationHost.event("equipment.changed", "parity", 0);
            ObjectNode equipment = event.putObject("equipment").put("slot", change.slot());
            equipment.set("before", change.before());
            equipment.set("after", change.after());
            events.add(event);
        }
        for (var change : HOST.projection().trackChanges(previous, saved)) {
            ObjectNode event = AutomationHost.event("track.changed", "parity", 0);
            event.put("track", change.track()).put("change", change.change()).put("requested", change.requested());
            event.set("before", change.before());
            event.set("after", change.after());
            events.add(event);
        }
        for (ObjectNode event : events) HOST.automatic(pack, saved, event, constantDice());
    }

    private static java.util.Set<String> effectLabels(AutomationHost.Pack pack, ObjectNode sheet) {
        java.util.Set<String> labels = new java.util.HashSet<>();
        for (JsonNode effect : HOST.describe(pack, sheet, AutomationHost.event("action.request", "parity", 0), "sheet", "self").path("derivedEffects"))
            labels.add(effect.path("label").asText());
        for (JsonNode effect : HOST.projection().actorFact(sheet, "effects")) labels.add(effect.path("label").asText(effect.path("name").asText()));
        return labels;
    }

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

    private static final List<String> TRAIT_NAMES = List.of("Agility", "Strength", "Finesse", "Instinct", "Presence", "Knowledge");

    private static ObjectNode resourcesOf(ObjectNode sheet) {
        return RuleJson.JSON.createObjectNode().put("hope", sheet.at("/hope/on").asInt()).put("hp", sheet.at("/hp/on").asInt())
                .put("stress", sheet.at("/stress/on").asInt()).put("armor", sheet.at("/armorTrack/on").asInt()).put("gold", sheet.path("gold").asInt());
    }

    private static ObjectNode checkRequest(ObjectNode sheet, String kind, String trait, JsonNode option) {
        ObjectNode request = RuleJson.JSON.createObjectNode().put("requestId", "r").put("expectedRevision", HOST.revision(sheet));
        request.putObject("checkRoll").put("kind", kind).put("trait", trait);
        ArrayNode selections = request.putArray("selections");
        if (option != null) {
            ObjectNode selection = selections.addObject().put("sourceInstanceId", option.path("sourceInstanceId").asText())
                    .put("ruleId", option.path("ruleId").asText()).put("bindingId", option.path("bindingId").asText());
            ObjectNode inputs = selection.putObject("inputs");
            for (JsonNode input : option.path("inputs")) {
                String id = input.path("id").asText();
                switch (input.path("kind").asText()) {
                    case "integer" -> inputs.put(id, input.has("default") ? input.path("default").asInt() : input.path("min").asInt());
                    case "choice" -> inputs.put(id, input.path("options").path(0).path("id").asText());
                    case "record.select" -> inputs.set(id, input.path("options").path(0).path("value"));
                    default -> { }
                }
            }
        }
        return request;
    }

    private static int rollTotal(JsonNode result) {
        return result.at("/event/roll/total").asInt();
    }

    private static boolean ownOption(JsonNode option) {
        return !option.path("sourceId").asText().startsWith("ruleset:");
    }

    private static final java.util.concurrent.atomic.AtomicInteger REQUESTS = new java.util.concurrent.atomic.AtomicInteger();

    private static ObjectNode profileRequest(ObjectNode sheet, String instance, String kind, JsonNode option) {
        ObjectNode request = checkRequest(sheet, "action", "Agility", option);
        request.put("requestId", "r" + REQUESTS.incrementAndGet());
        request.remove("checkRoll");
        request.putObject("profileRoll").put("sourceInstanceId", instance).put("profileId", "weapon.base").put("kind", kind);
        return request;
    }

    private static ObjectNode withWeapon(ObjectNode source) throws Exception {
        ObjectNode sheet = source.deepCopy();
        sheet.putArray("experiences").addObject().put("name", "Parity").put("mod", 2);
        if (sheet.withArray("activeWeapons").isEmpty())
            sheet.withArray("activeWeapons").addObject().put("name", "Longsword").put("slot", "Primary").put("trait", "Agility")
                    .put("range", "Melee").put("tier", 1).put("automationSource", "weapons:longsword").put("instanceId", "fallback")
                    .put("damageType", "physical").put("damage", "1d10+3");
        return sheet;
    }

    private static ObjectNode plainBase(ObjectNode sheet) {
        ObjectNode base = standardSheet();
        ObjectNode weapon = ((ObjectNode) sheet.withArray("activeWeapons").get(0)).deepCopy();
        weapon.remove("feature");
        weapon.put("automationSource", "weapons:plain").put("instanceId", "plain");
        base.withArray("activeWeapons").add(weapon);
        return base;
    }

    private static JsonNode weaponOptions(AutomationHost.Pack pack, ObjectNode sheet, ObjectNode event, String instance, String kind) {
        for (JsonNode profile : HOST.describe(pack, sheet, event, "sheet", "self").path("profiles"))
            if (instance.equals(profile.path("sourceInstanceId").asText()) && "weapon.base".equals(profile.path("id").asText()))
                return profile.path("options").path(kind);
        return RuleJson.JSON.createArrayNode();
    }

    /** Weapon attack and damage rolls: always-on contributions, offered options and each option's effect. */
    private static String weaponRollMismatch(AutomationHost.Pack pack, Placed placed, JsonNode expected) throws Exception {
        for (String kind : List.of("attack", "damage")) {
            JsonNode legacy = expected.path(kind);
            if (legacy.isMissingNode()) continue;
            ObjectNode sheet = withWeapon(placed.sheet());
            String instance = sheet.withArray("activeWeapons").get(0).path("instanceId").asText();
            ObjectNode event = AutomationHost.event("action.request", "parity", 1);
            String where = kind + ": ";
            try {
                ObjectNode baseSheet = plainBase(sheet);
                int base = rollTotal(HOST.execute(pack, baseSheet, event, profileRequest(baseSheet, "plain", kind, null), "self", "self", constantDice()));
                ObjectNode alone = sheet.deepCopy();
                ObjectNode aloneBefore = resourcesOf(alone);
                int always = rollTotal(HOST.execute(pack, alone, event, profileRequest(alone, instance, kind, null), "self", "self", constantDice())) - base;
                if (always != legacy.path("always").asInt()) return where + "always-on total legacy=" + legacy.path("always").asInt() + " new=" + always;
                ObjectNode aloneAfter = resourcesOf(alone);
                Map<String, Integer> requiredSpend = new TreeMap<>();
                legacy.path("offered").properties().forEach(e -> {
                    if (e.getValue().path("required").asBoolean())
                        e.getValue().path("spent").properties().forEach(s -> requiredSpend.merge(s.getKey(), s.getValue().asInt(), Integer::sum));
                });
                for (String field : List.of("hope", "hp", "stress", "armor", "gold")) {
                    int spent = aloneAfter.path(field).asInt() - aloneBefore.path(field).asInt();
                    if (spent != requiredSpend.getOrDefault(field, 0)) return where + "automatic " + field + " legacy=" + requiredSpend.getOrDefault(field, 0) + " new=" + spent;
                }
                Map<String, JsonNode> offered = new TreeMap<>();
                for (JsonNode option : weaponOptions(pack, sheet, event, instance, kind))
                    if (ownOption(option) && option.path("available").asBoolean(true) && !"magic-damage".equals(option.path("ruleId").asText())) offered.put(option.path("ruleId").asText(), option); // legacy offers the damage type as a select outside the option list
                for (String key : new ArrayList<>(offered.keySet())) {
                    String stripped = key.replaceFirst("((-\\d+)+|\\.start)$", "");
                    // The converter disambiguates a rule id shared with another rule of the same source.
                    if (!stripped.equals(key) && legacy.path("offered").has(stripped) && !offered.containsKey(stripped)) offered.put(stripped, offered.remove(key));
                }
                var legacyOptions = new TreeMap<String, JsonNode>();
                legacy.path("offered").properties().forEach(e -> {
                    if (e.getValue().path("required").asBoolean()) return;
                    if (!e.getValue().has("blocker") || offered.containsKey(e.getKey())) legacyOptions.put(e.getKey(), e.getValue());
                });
                if (!offered.keySet().equals(legacyOptions.keySet())) return where + "options legacy=" + legacyOptions.keySet() + " new=" + offered.keySet();
                for (var entry : legacyOptions.entrySet()) {
                    JsonNode want = entry.getValue();
                    if (want.has("blocker")) continue;
                    ObjectNode copy = sheet.deepCopy();
                    ObjectNode before = resourcesOf(copy);
                    int total = rollTotal(HOST.execute(pack, copy, event, profileRequest(copy, instance, kind, offered.get(entry.getKey())), "self", "self", constantDice()));
                    int delta = total - base - always;
                                        if (want.path("deferred").asBoolean()) {
                                            if (delta == 0 && "damage".equals(kind)) return where + "option " + entry.getKey() + " has no effect on the damage roll";
                                        } else if (delta != want.path("total").asInt()) return where + "option " + entry.getKey() + " total legacy=" + want.path("total").asInt() + " new=" + delta;
                    ObjectNode after = resourcesOf(copy);
                    for (String field : List.of("hope", "hp", "stress", "armor", "gold")) {
                        int spent = after.path(field).asInt() - before.path(field).asInt();
                        if (spent != want.path("spent").path(field).asInt())
                            return where + "option " + entry.getKey() + " " + field + " legacy=" + want.path("spent").path(field).asInt() + " new=" + spent;
                    }
                }
            } catch (RuntimeException e) {
                if (legacy.has("blocker")) continue; // both engines refuse the attack; the wording differs
                return where + "rejected: " + e.getMessage() + " @ " + frames(e);
            }
        }
        return null;
    }

    private static String frames(RuntimeException e) {
        StringBuilder frames = new StringBuilder();
        for (StackTraceElement frame : e.getStackTrace())
            if (frames.length() < 300 && frame.getClassName().startsWith("dmmt")) frames.append(frame.getClassName().substring(frame.getClassName().lastIndexOf('.') + 1)).append('.').append(frame.getMethodName()).append(':').append(frame.getLineNumber()).append(' ');
        return frames.toString();
    }

    private static Random dualityDice(int hope, int fear) {
        return new Random(0) {
            private int calls;
            @Override public int nextInt(int bound) {
                calls++;
                if (bound == 12 && calls == 1) return hope - 1;
                if (bound == 12 && calls == 2) return fear - 1;
                return Math.min(3, bound) - 1;
            }
        };
    }

    /** Result buttons offered after each roll scenario and outcome, against the legacy roll triggers. */
    private static String triggerMismatch(AutomationHost.Pack pack, Placed placed, JsonNode expected) {
        if (expected.isMissingNode() || expected.isNull()) return null;
        for (var scenario : expected.properties()) {
            if (scenario.getKey().startsWith("ally:")) {
                String problem = allyTriggerMismatch(pack, placed, scenario.getKey(), scenario.getValue());
                if (problem != null) return problem;
                continue;
            }
            String[] parts = scenario.getKey().split(":");
            String name = scenario.getKey();
            try {
                ObjectNode sheet;
                ObjectNode request;
                ObjectNode event = AutomationHost.event("action.request", "parity", 1);
                boolean damage = "damage".equals(parts[0]);
                if ("check".equals(parts[0])) {
                    sheet = placed.sheet().deepCopy();
                    request = checkRequest(sheet, parts[1], parts[2], null);
                } else {
                    sheet = withWeapon(placed.sheet());
                    request = profileRequest(sheet, sheet.withArray("activeWeapons").get(0).path("instanceId").asText(), parts[0], null);
                }
                int hope = "hope".equals(parts[parts.length - 1]) ? 5 : "fear".equals(parts[parts.length - 1]) ? 3 : 4;
                int fear = "hope".equals(parts[parts.length - 1]) ? 3 : "fear".equals(parts[parts.length - 1]) ? 5 : 4;
                JsonNode result = HOST.execute(pack, sheet, event, request, "self", "self", damage ? constantDice() : dualityDice(hope, fear));
                Map<String, JsonNode> actual = new TreeMap<>();
                for (JsonNode action : result.path("resultActions"))
                    if (ownOption(action)) actual.put(action.path("ruleId").asText(), action);
                for (String key : new ArrayList<>(actual.keySet())) {
                    String stripped = key.replaceFirst("((-\\d+)+|\\.start)$", "");
                    if (!stripped.equals(key) && expected.path(name).has(stripped) && !actual.containsKey(stripped)) actual.put(stripped, actual.remove(key));
                }
                var legacy = new TreeMap<String, JsonNode>();
                scenario.getValue().properties().forEach(e -> {
                    if (!e.getValue().has("blocker") || actual.containsKey(e.getKey())) legacy.put(e.getKey(), e.getValue());
                });
                if (!actual.keySet().equals(legacy.keySet())) return name + ": triggers legacy=" + legacy.keySet() + " new=" + actual.keySet();
            } catch (Exception e) {
                return name + ": rejected: " + e.getMessage() + " @ " + (e instanceof RuntimeException r ? frames(r) : "");
            }
        }
        return null;
    }

    /** Ally-audience triggers: another character's finished roll, described for this owner as another player. */
    private static String allyTriggerMismatch(AutomationHost.Pack pack, Placed placed, String name, JsonNode expected) {
        String[] parts = name.split(":");
        if (!"check".equals(parts[1])) return null;
        String outcome = parts[parts.length - 1];
        int hope = "hope".equals(outcome) ? 5 : "fear".equals(outcome) ? 3 : 4, fear = "hope".equals(outcome) ? 3 : "fear".equals(outcome) ? 5 : 4;
        try {
            ObjectNode roller = standardSheet();
            ObjectNode rolled = HOST.execute(pack, roller, AutomationHost.event("action.request", "roller", 1),
                    checkRequest(roller, parts[2], parts[3], null), "self", "self", dualityDice(hope, fear));
            ObjectNode event = ((ObjectNode) rolled.required("event")).deepCopy();
            JsonNode described = HOST.describe(pack, placed.sheet().deepCopy(), event, "roll", "other");
            java.util.Set<String> actual = new java.util.TreeSet<>();
            for (JsonNode action : described.path("actions"))
                if (ownOption(action)) actual.add(action.path("ruleId").asText().replaceFirst("((-\\d+)+|\\.start)$", ""));
            java.util.Set<String> legacy = new java.util.TreeSet<>();
            expected.properties().forEach(e -> legacy.add(e.getKey()));
            if (!actual.equals(legacy)) return name + ": ally triggers legacy=" + legacy + " new=" + actual;
            return null;
        } catch (Exception e) {
            return name + ": rejected: " + e.getMessage() + " @ " + (e instanceof RuntimeException r ? frames(r) : "");
        }
    }

    private static Map<String, JsonNode> currentCatalogs = new LinkedHashMap<>();

    private static String norm(String id) { return id.replaceFirst("((-\\d+)+|\\.start)$", ""); }

    /** Attachable items: which gear accepts them, and the rolls on the first compatible gear once attached and detached. */
    private static String attachMismatch(AutomationHost.Pack pack, Placed placed, JsonNode expected) {
        if (expected.isMissingNode() || expected.isNull()) return null;
        try {
            for (var gear : expected.path("blocked").properties()) {
                String[] parts = gear.getKey().split("/");
                Placed host = place(new Row(parts[0], "/entries/" + parts[1] + "/automation"), currentCatalogs.get(parts[0]));
                ObjectNode sheet = host.sheet();
                sheet.withArray("inventory").add(placed.sheet().withArray("inventory").get(0).deepCopy());
                String gearInstance = "owner";
                if ("weapons".equals(parts[0])) ((ObjectNode) sheet.withArray("activeWeapons").get(0)).put("instanceId", "gear");
                else sheet.withObject("/activeArmor").put("instanceId", "gear");
                gearInstance = "gear";
                // Both gear and item use instance "owner" in place(); the item is re-keyed here.
                ((ObjectNode) sheet.withArray("inventory").get(0)).put("instanceId", "item");
                List<String> blockers = HOST.attachBlockers(pack, sheet, "item", gearInstance);
                if (blockers.isEmpty() == gear.getValue().asBoolean())
                    return gear.getKey() + ": attach legacy=" + (gear.getValue().asBoolean() ? "blocked" : "allowed") + " new=" + (blockers.isEmpty() ? "allowed" : "blocked: " + blockers);
                JsonNode want = expected.path("rolls").path(gear.getKey());
                if (want.isMissingNode()) continue;
                ObjectNode attached = HOST.attach(pack, sheet, "item", gearInstance);
                if (attached.withArray("inventory").size() != want.path("inventory").asInt())
                    return gear.getKey() + ": inventory after attach legacy=" + want.path("inventory").asInt() + " new=" + attached.withArray("inventory").size();
                ObjectNode event = AutomationHost.event("action.request", "parity", 1);
                if ("weapons".equals(parts[0])) {
                    ObjectNode plain = standardSheet();
                    plain.putArray("experiences").addObject().put("name", "Parity").put("mod", 2);
                    ObjectNode weapon = ((ObjectNode) attached.withArray("activeWeapons").get(0)).deepCopy();
                    weapon.remove(List.of("feature", "attachments", "attachmentBase"));
                    weapon.put("automationSource", "weapons:plain").put("instanceId", "plain");
                    plain.withArray("activeWeapons").add(weapon);
                    for (String kind : List.of("attack", "damage")) {
                        int base = rollTotal(HOST.execute(pack, plain.deepCopy(), event, profileRequest(plain, "plain", kind, null), "self", "self", constantDice()));
                        int always = rollTotal(HOST.execute(pack, attached.deepCopy(), event, profileRequest(attached, "gear", kind, null), "self", "self", constantDice())) - base;
                        if (always != want.path(kind).path("always").asInt()) return gear.getKey() + ": attached " + kind + " always legacy=" + want.path(kind).path("always").asInt() + " new=" + always;
                        java.util.Set<String> offered = new java.util.TreeSet<>();
                        for (JsonNode option : weaponOptions(pack, attached, event, "gear", kind))
                            if (ownOption(option) && option.path("available").asBoolean(true) && !"magic-damage".equals(option.path("ruleId").asText())) offered.add(norm(option.path("ruleId").asText()));
                        java.util.Set<String> legacy = new java.util.TreeSet<>();
                        want.path(kind).path("offered").forEach(id -> legacy.add(id.asText()));
                        if (!offered.equals(legacy)) return gear.getKey() + ": attached " + kind + " options legacy=" + legacy + " new=" + offered;
                    }
                } else {
                    ObjectNode plain = standardSheet();
                    plain.putArray("experiences").addObject().put("name", "Parity").put("mod", 2);
                    int base = rollTotal(HOST.execute(pack, plain.deepCopy(), event, checkRequest(plain, "action", "Agility", null), "self", "self", constantDice()));
                    int always = rollTotal(HOST.execute(pack, attached.deepCopy(), event, checkRequest(attached, "action", "Agility", null), "self", "self", constantDice())) - base;
                    JsonNode legacy = want.path("check:action:Agility");
                    if (always != legacy.path("always").asInt()) return gear.getKey() + ": attached check always legacy=" + legacy.path("always").asInt() + " new=" + always;
                    java.util.Set<String> offered = new java.util.TreeSet<>();
                    for (JsonNode option : HOST.describeCheck(pack, attached, event, "action", "Agility", "self").path("options"))
                        if (ownOption(option) && option.path("available").asBoolean(true) && !"magic-damage".equals(option.path("ruleId").asText())) offered.add(norm(option.path("ruleId").asText()));
                    java.util.Set<String> wantIds = new java.util.TreeSet<>();
                    legacy.path("offered").forEach(id -> wantIds.add(id.asText()));
                    if (!offered.equals(wantIds)) return gear.getKey() + ": attached check options legacy=" + wantIds + " new=" + offered;
                }
                String attachedId = HOST.sources(pack, attached).stream().filter(s -> s.attachedTo() != null).findFirst().orElseThrow().instanceId();
                ObjectNode detached = HOST.detach(pack, attached, attachedId);
                List<String> stacks = new ArrayList<>();
                detached.withArray("inventory").forEach(i -> stacks.add(i.path("name").asText() + ":" + i.path("qty").asInt()));
                java.util.Collections.sort(stacks);
                List<String> legacyStacks = new ArrayList<>();
                want.path("detached").forEach(i -> legacyStacks.add(i.asText()));
                if (!stacks.equals(legacyStacks)) return gear.getKey() + ": inventory after detach legacy=" + legacyStacks + " new=" + stacks;
            }
        } catch (RuntimeException e) {
            return "attach rejected: " + e.getMessage() + " @ " + frames(e);
        }
        return null;
    }

    private static String slugOf(String id) {
        return NON_SLUG.matcher(id.toLowerCase(java.util.Locale.ROOT)).replaceAll("-").replaceAll("^-+|-+$", "");
    }

    /** Pool values found anywhere in the engine state, keyed by pool id without punctuation. */
    private static java.util.Map<String, Integer> poolValues(JsonNode node) {
        java.util.Map<String, Integer> out = new java.util.HashMap<>();
        collectPools(node.path("automationState"), out);
        return out;
    }

    private static void collectPools(JsonNode node, java.util.Map<String, Integer> out) {
        if (node.isObject()) {
            for (var field : node.properties()) {
                if ("pools".equals(field.getKey()) && field.getValue().isObject())
                    for (var pool : field.getValue().properties()) {
                        JsonNode value = pool.getValue().isObject() ? pool.getValue().path("value") : pool.getValue();
                        if (value.isNumber()) out.merge(NON_SLUG.matcher(pool.getKey().toLowerCase(java.util.Locale.ROOT)).replaceAll(""), value.asInt(), Integer::sum);
                    }
                else collectPools(field.getValue(), out);
            }
        } else if (node.isArray()) node.forEach(child -> collectPools(child, out));
    }

    private static final java.util.regex.Pattern NON_SLUG = java.util.regex.Pattern.compile("[^a-z0-9]+");

    /** Incoming damage: each own mitigation option chosen alone, against the legacy marks, costs and refusals. */
    private static String mitigationMismatch(AutomationHost.Pack pack, Placed placed, JsonNode expected) {
        if (expected.isMissingNode() || expected.isNull()) return null;
        ObjectNode origin = AutomationHost.event("action.request", "parity", 0);
        for (var scenario : expected.properties()) {
            String[] key = scenario.getKey().split(":");
            int amount = Integer.parseInt(key[1]);
            int armor = "armor".equals(key[2]) ? 1 : 0;
            for (var option : scenario.getValue().properties()) {
                JsonNode legacy = option.getValue();
                if (legacy.path("unavailable").asBoolean()) continue;
                String label = scenario.getKey() + " " + option.getKey();
                List<String> slugs = new ArrayList<>();
                if ("-".equals(option.getKey())) legacy.path("passive").forEach(id -> slugs.add(slugOf(id.asText())));
                else slugs.add(slugOf(option.getKey()));
                int armorRequest = armor > 0 ? Math.max(1, legacy.path("armorUsed").asInt(1)) : 0;
                ObjectNode sheet = placed.sheet().deepCopy();
                if (legacy.path("blocker").asText("").startsWith("Resource unavailable: @")) continue; // the legacy mitigation cannot charge an inventory item; the new engine can
                boolean refused = legacy.has("blocker") || legacy.has("error");
                try {
                    JsonNode described = HOST.describeDamage(pack, sheet, origin, "self", amount, key[0], armorRequest);
                    ObjectNode request = RuleJson.JSON.createObjectNode().put("requestId", "m").put("expectedRevision", HOST.revision(sheet));
                    ArrayNode selections = request.putArray("selections");
                    boolean offeredBlocked = false;
                    for (String slug : slugs)
                        for (var stage : described.path("stages").properties())
                            for (JsonNode action : stage.getValue().path("actions")) {
                                String rule = norm(action.path("ruleId").asText());
                                if (!placed.sourceId().equals(action.path("sourceId").asText())
                                        || !(rule.equals(slug) || rule.startsWith(slug + "-") || rule.startsWith(slug + "."))) continue;
                                if (!action.path("available").asBoolean(true)) { offeredBlocked = true; continue; }
                                if (!"button".equals(action.path("mode").asText("button")) && !"option".equals(action.path("mode").asText())) continue;
                                ObjectNode selection = selections.addObject().put("sourceInstanceId", action.path("sourceInstanceId").asText())
                                        .put("ruleId", action.path("ruleId").asText()).put("bindingId", action.path("bindingId").asText());
                                ObjectNode inputs = selection.putObject("inputs");
                                for (JsonNode input : action.path("inputs")) {
                                    String id = input.path("id").asText();
                                    switch (input.path("kind").asText()) {
                                        case "integer" -> inputs.put(id, Math.max(input.path("min").asInt(), 1));
                                        case "choice" -> inputs.put(id, input.path("options").path(0).path("id").asText());
                                        default -> { }
                                    }
                                }
                            }
                    request.putObject("damage").put("amount", amount).put("armor", armorRequest).put("type", key[0]);
                    if (selections.isEmpty() && !slugs.isEmpty() && refused) continue;
                    if (selections.isEmpty() && offeredBlocked && !"-".equals(option.getKey())) {
                        if (refused) continue;
                        return label + ": legacy applies the option but the new engine marks every offer unavailable";
                    }
                    ObjectNode before = sheet.deepCopy();
                    ObjectNode working = sheet.deepCopy();
                    try {
                        HOST.execute(pack, working, origin, request, "self", "self", constantDice());
                    } catch (RuntimeException rejected) {
                        if (refused) continue;
                        return label + ": rejected: " + rejected.getMessage() + " @ " + frames(rejected);
                    }
                    if (refused && !selections.isEmpty())
                        return label + ": accepted although legacy refused (" + legacy.path("blocker").asText(legacy.path("error").asText()) + ")";
                    if (refused) continue;
                    JsonNode delta = legacy.path("delta");
                    if (delta.isMissingNode()) continue;
                    for (var field : Map.of("hope", "/hope/on", "hp", "/hp/on", "stress", "/stress/on", "armor", "/armorTrack/on").entrySet()) {
                        int moved = working.at(field.getValue()).asInt() - before.at(field.getValue()).asInt();
                        if (moved != delta.path(field.getKey()).asInt())
                            return label + ": " + field.getKey() + " legacy=" + delta.path(field.getKey()).asInt() + " new=" + moved
                                    + (selections.isEmpty() ? " (no selection)" : "");
                    }
                    java.util.Map<String, Integer> pools = poolValues(working);
                    for (var res : delta.path("resourceValues").properties()) {
                        Integer now = pools.get(NON_SLUG.matcher(res.getKey()).replaceAll(""));
                        if (now == null || now != res.getValue().asInt())
                            return label + ": resource " + res.getKey() + " legacy=" + res.getValue().asInt() + " new=" + now;
                    }
                } catch (RuntimeException e) {
                    if (refused) continue;
                    return label + ": describe failed: " + e.getMessage() + " @ " + frames(e);
                }
            }
        }
        return null;
    }

    /** Check rolls: always-on contributions, offered options and each option's effect against the legacy scenario. */
    private static String rollMismatch(AutomationHost.Pack pack, Placed placed, JsonNode expected) {
        if (expected.isMissingNode() || expected.isNull()) return null;
        for (String kind : List.of("action", "reaction")) {
            for (String trait : TRAIT_NAMES) {
                JsonNode legacy = expected.path("check:" + kind + ":" + trait);
                ObjectNode sheet = placed.sheet().deepCopy();
                sheet.putArray("experiences").addObject().put("name", "Parity").put("mod", 2);
                ObjectNode event = AutomationHost.event("action.request", "parity", 1);
                String where = kind + " " + trait + " check: ";
                try {
                    int base = rollTotal(HOST.execute(pack, standardSheet(), event, checkRequest(standardSheet(), kind, trait, null), "self", "self", constantDice()));
                    int always = rollTotal(HOST.execute(pack, sheet.deepCopy(), event, checkRequest(sheet, kind, trait, null), "self", "self", constantDice())) - base;
                    if (always != legacy.path("always").asInt()) return where + "always-on total legacy=" + legacy.path("always").asInt() + " new=" + always;
                    JsonNode described = HOST.describeCheck(pack, sheet, event, kind, trait, "self");
                    Map<String, JsonNode> offered = new TreeMap<>();
                    for (JsonNode option : described.path("options"))
                        if (ownOption(option) && option.path("available").asBoolean(true) && !"magic-damage".equals(option.path("ruleId").asText())) offered.put(option.path("ruleId").asText(), option); // legacy offers the damage type as a select outside the option list
                    var legacyOptions = new TreeMap<String, JsonNode>();
                    legacy.path("offered").properties().forEach(e -> {
                        if (!e.getValue().has("blocker") || offered.containsKey(e.getKey())) legacyOptions.put(e.getKey(), e.getValue());
                    });
                    if (!offered.keySet().equals(legacyOptions.keySet())) return where + "options legacy=" + legacyOptions.keySet() + " new=" + offered.keySet();
                    for (var entry : legacyOptions.entrySet()) {
                        JsonNode want = entry.getValue();
                        if (want.has("blocker")) continue;
                        ObjectNode copy = sheet.deepCopy();
                        ObjectNode before = resourcesOf(copy);
                        int total = rollTotal(HOST.execute(pack, copy, event, checkRequest(copy, kind, trait, offered.get(entry.getKey())), "self", "self", constantDice()));
                        int delta = total - base - always;
                        if (delta != want.path("total").asInt()) return where + "option " + entry.getKey() + " total legacy=" + want.path("total").asInt() + " new=" + delta;
                        ObjectNode after = resourcesOf(copy);
                        for (String field : List.of("hope", "hp", "stress", "armor", "gold")) {
                            int spent = after.path(field).asInt() - before.path(field).asInt();
                            if (spent != want.path("spent").path(field).asInt())
                                return where + "option " + entry.getKey() + " " + field + " legacy=" + want.path("spent").path(field).asInt() + " new=" + spent;
                        }
                    }
                } catch (RuntimeException e) {
                    StringBuilder frames = new StringBuilder();
                    for (StackTraceElement frame : e.getStackTrace())
                        if (frames.length() < 300 && frame.getClassName().startsWith("dmmt")) frames.append(frame.getClassName().substring(frame.getClassName().lastIndexOf('.') + 1)).append('.').append(frame.getMethodName()).append(':').append(frame.getLineNumber()).append(' ');
                    return where + "rejected: " + e.getMessage() + " @ " + frames;
                }
            }
        }
        return null;
    }
}

