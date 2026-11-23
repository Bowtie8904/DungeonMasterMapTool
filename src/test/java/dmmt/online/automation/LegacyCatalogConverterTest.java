package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.*;

class LegacyCatalogConverterTest {
    private static String catalog(String entries) {
        return "{\"format\":\"dmmap-daggerheart\",\"version\":1,\"category\":\"classes\",\"label\":\"Fixture\",\"entries\":[" + entries + "]}";
    }

    @Test
    void convertsSimpleActionsUsesEffectsAndModifiers() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"tester","name":"Tester","features":[
                  {"name":"Second Wind","automation":{"uses":{"max":1,"per":"rest"},"actions":[
                    {"id":"use","label":"Second Wind {now}","cost":{"hope":1,"stress":1},
                     "steps":[{"op":"clear","resource":"hp","amount":2},{"op":"clear","resource":"armor","amount":"all"},
                              {"op":"gain","resource":"hope","amount":1},
                              {"op":"effect","effect":{"name":"Braced","kind":"buff","until":"scene","text":"Steady.",
                                "modifiers":[{"stat":"thresholds","value":1}]}}],
                     "report":{"text":"Recovered {amount} HP.","amount":2}}]}},
                  {"name":"Nimble","automation":{"modifiers":[{"stat":"evasion","value":1},{"roll":"attack","value":1},{"stat":"traits","value":1}]}}
                ]}"""));
        assertEquals(2, result.blocks());
        assertEquals(2, result.converted(), result.diagnostics().toString());
        assertEquals("second-wind", result.assignedIds().get("/entries/0/features/0"));
        JsonNode block = result.catalog().at("/entries/0/features/0/automation");
        assertEquals(1, block.at("/limits/uses/max").asInt());
        assertEquals("[\"rest.short\",\"rest.long\"]", block.at("/limits/uses/resetOn").toString());
        JsonNode rule = block.at("/rules/0");
        assertEquals("uses", rule.path("limitRef").asText());
        assertEquals("button", rule.at("/bindings/0/mode").asText());
        assertEquals("Second Wind {{now}}", rule.at("/ui/label").asText());
        assertEquals("Recovered 2 HP.", rule.at("/ui/report").asText());
        assertEquals("resource.spend", rule.at("/costs/0/op").asText());
        assertEquals("actor.stress", rule.at("/costs/1/track").asText());
        assertEquals("actor.armorTrack.marked", rule.at("/do/1/amount/read").asText());
        assertEquals("braced", rule.at("/do/3/effect").asText());
        assertEquals("[\"scene.end\"]", block.at("/effects/braced/until").toString());
        assertEquals(2, block.at("/effects/braced/modifiers").size());
        JsonNode passive = result.catalog().at("/entries/0/features/1/automation/modifiers");
        assertEquals(8, passive.size());
        assertEquals("roll.attack", passive.at("/1/stat").asText());
        var sources = RuleCatalog.compile(result.catalog().toString(), LegacyCatalogConverter.coreRecipes());
        assertEquals("classes:tester:features:second-wind", sources.get(0).id());
    }

    @Test
    void announceOnlyActionsBecomeButtonRulesWithoutOperations() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"bard","name":"Bard","automation":{"uses":{"max":1,"per":"longRest"},"actions":[{"id":"use","label":"Inspire"}]}}"""));
        assertEquals(1, result.converted(), result.diagnostics().toString());
        JsonNode rule = result.catalog().at("/entries/0/automation/rules/0");
        assertFalse(rule.has("do"));
        assertEquals("[\"rest.long\"]", result.catalog().at("/entries/0/automation/limits/uses/resetOn").toString());
    }

    @Test
    void consumesHostOwnedFlagsAcrossActionsOptionsAndTriggers() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"owned","name":"Owned","automation":{
                  "actions":[{"id":"action","label":"Action","hostOwned":true}],
                  "rollOptions":[{"id":"option","label":"Option","roll":"action","value":1,"hostOwned":true}],
                  "rollTriggers":[{"id":"roll","label":"Roll","on":"attack","hostOwned":true,
                    "steps":[{"op":"gain","resource":"hope","amount":1}]}],
                  "conditionalRollTriggers":[{"id":"trigger","label":"Trigger","on":"any","when":"success","hostOwned":true,
                    "steps":[{"op":"gain","resource":"hope","amount":1}]}],
                  "mitigation":[{"id":"mitigation","label":"Ward","negate":true,"passive":true,"hostOwned":true}]}}
                """));

        assertEquals(1, result.converted(), result.diagnostics().toString());
        JsonNode rules = block(result, 0).path("rules");
        assertEquals(5, rules.size());
        for (JsonNode rule : rules) assertFalse(rule.has("hostOwned"), rule.toString());
        program(result, 0);
    }

    @Test
    void actionRollConvertsToHostResolvedPlanWithGenericMetadata() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"duelist","name":"Duelist","automation":{"actions":[
                  {"id":"riposte","label":"Riposte","cost":{"hope":1},"actionRoll":{"trait":"Agility","attack":true,"difficulty":12}}]}}"""));
        JsonNode rule = block(result, 0).path("rules").get(0);
        assertEquals("roll.resolve", rule.at("/do/0/op").asText());
        assertEquals("action", rule.at("/do/0/plan/literal/metadata/kind").asText());
        assertEquals("Agility", rule.at("/do/0/plan/literal/metadata/trait").asText());
        assertEquals("actor.traits.Agility", rule.at("/do/0/plan/literal/contributions/2/value/read").asText());
        assertEquals("Roll total: {result.actionRoll.total}.", rule.at("/ui/report").asText());

        RuleCompiler.Program program = program(result, 0);
        ObjectNode context = context(program, "{\"hope\":{\"value\":2,\"max\":6},\"traits\":{\"Agility\":3}}", "{\"type\":\"action.request\"}");
        RuleDispatcher.EventResolution transaction = run(program, context, "riposte");
        RuleDispatcher.ActionResult action = transaction.actions().getFirst();
        assertEquals(1, transaction.contexts().getFirst().path("actor").path("hope").path("value").asInt());
        assertTrue(action.report().startsWith("Roll total: "), action.report());
        JsonNode roll = action.diceRolls().getFirst();
        assertEquals(3, roll.path("contributions").size());
        assertEquals("action", roll.at("/metadata/kind").asText());
        assertEquals("Agility", roll.at("/metadata/trait").asText());
        assertTrue(roll.at("/metadata/attack").asBoolean());
        assertEquals(12, roll.at("/metadata/difficulty").asInt());
    }

    @Test
    void highestTraitActionRollUsesGenericMaxExpression() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"vampire","name":"Vampire","automation":{"actions":[
                  {"id":"fangs","label":"Fangs","actionRoll":{"trait":"highest","attack":true}}]}}"""));
        JsonNode rule = block(result, 0).path("rules").get(0);
        assertEquals("highest", rule.at("/do/0/plan/literal/metadata/trait").asText());
        assertEquals(6, rule.at("/do/0/plan/literal/contributions/2/value/max").size());

        RuleCompiler.Program program = program(result, 0);
        ObjectNode context = context(program, """
                {"traits":{"Agility":2,"Strength":1,"Finesse":5,"Instinct":3,"Presence":0,"Knowledge":4}}
                """, "{\"type\":\"action.request\"}");
        JsonNode roll = run(program, context, "fangs").actions().getFirst().diceRolls().getFirst();
        assertEquals(5, roll.at("/contributions/2/value").asInt());
        assertEquals("highest", roll.at("/metadata/trait").asText());
    }

    @Test
    void spellcastActionRollUsesProjectedSpellcastTraitValue() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"construct","name":"Construct","automation":{"actions":[
                  {"id":"command","label":"Command","actionRoll":{"trait":"spellcast"}}]}}"""));
        JsonNode rule = block(result, 0).path("rules").get(0);
        assertEquals("actor.spellcastTrait.value", rule.at("/do/0/plan/literal/contributions/2/value/read").asText());

        RuleCompiler.Program program = program(result, 0);
        ObjectNode withoutSpellcast = context(program, "{}", "{\"type\":\"action.request\"}");
        assertTrue(available(program, withoutSpellcast, "sheet").isEmpty());
        ObjectNode context = context(program, "{\"spellcastTrait\":{\"name\":\"Instinct\",\"value\":2}}",
                "{\"type\":\"action.request\"}");
        assertEquals(List.of("command"), available(program, context, "sheet"));
        JsonNode roll = run(program, context, "command").actions().getFirst().diceRolls().getFirst();
        assertEquals(2, roll.at("/contributions/2/value").asInt());
        assertEquals("spellcast", roll.at("/metadata/trait").asText());
    }

    @Test
    void actionDamageConvertsFixedDiceAndTypedDamageMetadata() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"charge","name":"Charge","automation":{"actions":[
                  {"id":"impact","label":"Impact","damage":{"dice":"2d8+3","fixed":true,"type":"magic"}}]}}"""));
        JsonNode rule = block(result, 0).path("rules").get(0);
        assertEquals("roll.resolve", rule.at("/do/0/op").asText());
        assertEquals("Damage total: {result.actionDamage.total}.", rule.at("/ui/report").asText());
        assertEquals("magic", rule.at("/do/0/plan/literal/metadata/damageType").asText());
        assertEquals(2, rule.at("/do/0/plan/literal/contributions/0/count").asInt());
        assertEquals(3, rule.at("/do/0/plan/literal/contributions/1/value").asInt());

        RuleCompiler.Program program = program(result, 0);
        ObjectNode context = context(program, "{\"proficiency\":{\"value\":4}}", "{\"type\":\"action.request\"}");
        RuleDispatcher.ActionResult action = run(program, context, "impact").actions().getFirst();
        assertTrue(action.report().startsWith("Damage total: "), action.report());
        JsonNode roll = action.diceRolls().getFirst();
        assertEquals(2, roll.at("/contributions/0/dice").size());
        assertEquals("damage", roll.at("/metadata/kind").asText());
        assertEquals("magic", roll.at("/metadata/damageType").asText());
        assertTrue(roll.at("/metadata/fixedDice").asBoolean());
    }

    @Test
    void actionDamageUsesProficiencyOrProjectedSpellcastTraitForUnfixedDice() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"features","name":"Features","automation":{"actions":[
                  {"id":"scaled","label":"Scaled","damage":{"dice":"d8-1","type":"physical"}}]}}"""));
        block(result, 0);
        RuleCompiler.Program program = program(result, 0);
        ObjectNode context = context(program, "{\"proficiency\":{\"value\":3}}", "{\"type\":\"action.request\"}");
        JsonNode roll = run(program, context, "scaled").actions().getFirst().diceRolls().getFirst();
        assertEquals(3, roll.at("/contributions/0/dice").size());
        assertEquals(-1, roll.at("/contributions/1/value").asInt());
        assertFalse(roll.at("/metadata/fixedDice").asBoolean());

        var spellcastScaled = LegacyCatalogConverter.convert(catalog("""
                {"id":"spell","name":"Spell","automation":{"actions":[
                  {"id":"darkfire","label":"Darkfire","damage":{"dice":"2d8+6","count":"spellcast","type":"magic"}}]}}"""));
        JsonNode spellcastRule = block(spellcastScaled, 0).path("rules").get(0);
        assertEquals("multiply", spellcastRule.at("/do/0/plan/literal/contributions/0/count").fieldNames().next());
        RuleCompiler.Program spellProgram = program(spellcastScaled, 0);
        ObjectNode withoutSpellcast = context(spellProgram, "{}", "{\"type\":\"action.request\"}");
        assertTrue(available(spellProgram, withoutSpellcast, "sheet").isEmpty());
        ObjectNode spellContext = context(spellProgram,
                "{\"spellcastTrait\":{\"name\":\"Instinct\",\"value\":3}}", "{\"type\":\"action.request\"}");
        JsonNode spellRoll = run(spellProgram, spellContext, "darkfire").actions().getFirst().diceRolls().getFirst();
        assertEquals(6, spellRoll.at("/contributions/0/dice").size());
        assertEquals(6, spellRoll.at("/contributions/1/value").asInt());
        assertEquals("magic", spellRoll.at("/metadata/damageType").asText());
        ObjectNode negativeSpellcast = context(spellProgram,
                "{\"spellcastTrait\":{\"name\":\"Instinct\",\"value\":-1}}", "{\"type\":\"action.request\"}");
        JsonNode zeroDiceRoll = run(spellProgram, negativeSpellcast, "darkfire").actions().getFirst().diceRolls().getFirst();
        assertEquals(0, zeroDiceRoll.at("/contributions/0/dice").size());

        var malformed = LegacyCatalogConverter.convert(catalog("""
                {"id":"bad","name":"Bad","automation":{"actions":[
                  {"id":"damage","label":"Damage","damage":{"dice":"d8","type":null}}]}}"""));
        assertEquals(0, malformed.converted());
        assertTrue(malformed.diagnostics().stream().anyMatch(d -> d.path().endsWith("/damage/type")), malformed.diagnostics().toString());
    }

    @Test
    void convertsLegacyTargetModesToExplicitHostTargetSelections() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"single","name":"Single","automation":{"actions":[
                  {"id":"use","label":"Choose a player","target":"actor","excludeSourceActor":true,"hostOwned":true,
                   "cost":{"hope":1},"steps":[{"op":"clear","resource":"stress","amount":1}]}]}},
                {"id":"any","name":"Any","automation":{"actions":[
                  {"id":"use","label":"Choose any actor","target":"anyActor","steps":[{"op":"effect","effect":{"name":"Marked"}}]}]}},
                {"id":"party","name":"Party","automation":{"actions":[
                  {"id":"use","label":"Party","target":"party","steps":[{"op":"gain","resource":"hope","amount":1}]}]}},
                {"id":"all","name":"All","automation":{"actions":[
                  {"id":"use","label":"All","target":"allActors","steps":[{"op":"clear","resource":"hp","amount":"all"}]}]}}
                """));

        assertEquals(4, result.converted(), result.diagnostics().toString());
        JsonNode single = result.catalog().at("/entries/0/automation/rules/0");
        assertEquals("one", single.at("/bindings/0/targetSelection/cardinality").asText());
        assertEquals("connectedPlayerCharacters", single.at("/bindings/0/targetSelection/scope").asText());
        assertTrue(single.at("/bindings/0/targetSelection/excludeSource").asBoolean());
        assertEquals("actor.hope", single.at("/costs/0/pool").asText());
        assertEquals("recipient.stress", single.at("/do/0/track").asText());
        assertFalse(single.has("hostOwned"));

        JsonNode any = result.catalog().at("/entries/1/automation/rules/0");
        assertEquals("one", any.at("/bindings/0/targetSelection/cardinality").asText());
        assertEquals("activeCharacters", any.at("/bindings/0/targetSelection/scope").asText());
        assertEquals("recipient", any.at("/do/0/target").asText());

        JsonNode party = result.catalog().at("/entries/2/automation/rules/0");
        assertEquals("all", party.at("/bindings/0/targetSelection/cardinality").asText());
        assertEquals("connectedPlayerCharacters", party.at("/bindings/0/targetSelection/scope").asText());
        assertEquals("recipient.hope", party.at("/do/0/pool").asText());

        JsonNode all = result.catalog().at("/entries/3/automation/rules/0");
        assertEquals("all", all.at("/bindings/0/targetSelection/cardinality").asText());
        assertEquals("activePlayerCharacters", all.at("/bindings/0/targetSelection/scope").asText());
        assertEquals("recipient.hp.marked", all.at("/do/0/amount/read").asText());
    }

    @Test
    void blocksUnsupportedShapesWithPathSpecificDiagnosticsAndRemovesTheBlock() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"a","name":"A","automation":{"actions":[{"id":"use","label":"Use","cost":{"favor":1}}]}},
                {"id":"b","name":"B","automation":{"rollOptions":[{"id":"x","label":"X","roll":"attack","target":"ally"}]}},
                {"id":"c","name":"C","automation":{"actions":[{"id":"use","label":"Roll","steps":[{"op":"clear","resource":"hp","amount":"@die"}]}]}},
                {"id":"d","name":"D","automation":{"uses":{"max":1,"per":"rest"}}},
                {"id":"e","name":"E","automation":{"modifiers":[{"stat":"evasion","value":1,"dice":"1d4"}]}}"""));
        assertEquals(5, result.blocks());
        assertEquals(0, result.converted());
        var messages = result.diagnostics().stream().map(d -> d.sourceId() + " " + d.path() + " " + d.message()).toList();
        assertTrue(messages.contains("classes:a /entries/0/automation/actions/0/cost/favor Custom resource cost needs a declared pool: favor"), messages.toString());
        assertTrue(messages.contains("classes:b /entries/1/automation/rollOptions/0/target Unsupported legacy field: target"), messages.toString());
        assertTrue(messages.stream().anyMatch(m -> m.startsWith("classes:c /entries/2/automation/actions/0/steps/0/amount")), messages.toString());
        assertTrue(messages.contains("classes:d /entries/3/automation/uses A use limit without rules has nothing to limit."), messages.toString());
        assertTrue(messages.contains("classes:e /entries/4/automation/modifiers/0/dice Unsupported legacy field: dice"), messages.toString());
        for (JsonNode entry : result.catalog().path("entries")) assertFalse(entry.has("automation"));
        assertEquals(2, result.catalog().path("version").asInt());
    }

    @Test
    void rejectsNonLegacyInputAndKeepsStagingSeparate(@TempDir Path temp) throws Exception {
        assertThrows(IllegalArgumentException.class, () -> LegacyCatalogConverter.convert(catalog("").replace("\"version\":1", "\"version\":2")));
        Path legacy = Files.createDirectories(temp.resolve("legacy"));
        Files.writeString(legacy.resolve("classes.json"), catalog("""
                {"id":"x","name":"X","automation":{"actions":[{"id":"use","label":"Use","cost":{"hope":1}}]}}"""));
        assertThrows(IllegalArgumentException.class,
                () -> LegacyCatalogConverter.main(new String[]{legacy.toString(), legacy.resolve("out").toString()}));
        Path staging = temp.resolve("staging");
        LegacyCatalogConverter.main(new String[]{legacy.toString(), staging.toString()});
        assertTrue(Files.readString(staging.resolve("classes.json")).contains("\"version\" : 2"));
        assertTrue(Files.readString(staging.resolve("conversion-report.json")).contains("\"converted\" : 1"));
        assertEquals(LegacyCatalogConverter.coreRecipes(), Files.readString(staging.resolve("rules").resolve("recipes.json")));
        assertEquals(LegacyCatalogConverter.coreRuleset(), Files.readString(staging.resolve("rules").resolve("ruleset.json")));
        assertFalse(Files.exists(staging.resolve("rules.json")));
        assertTrue(Files.readString(legacy.resolve("classes.json")).contains("\"version\":1"));
    }

    @Test
    void conversionPreservesValidatedSharedRulesAndRejectsAnIncompleteDirectory(@TempDir Path temp) throws Exception {
        Path legacy = Files.createDirectories(temp.resolve("legacy"));
        Files.writeString(legacy.resolve("classes.json"), catalog("""
                {"id":"x","name":"X","automation":{"actions":[{"id":"use","label":"Use"}]}}"""));
        Path rules = Files.createDirectories(legacy.resolve("rules"));
        Files.writeString(rules.resolve("recipes.json"), LegacyCatalogConverter.coreRecipes());
        assertThrows(IOException.class, () -> LegacyCatalogConverter.main(new String[]{legacy.toString(), temp.resolve("missing").toString()}));
        String customized = LegacyCatalogConverter.coreRuleset().replace("Daggerheart rules", "Home rules");
        Files.writeString(rules.resolve("ruleset.json"), customized);
        Path staging = temp.resolve("staging");
        LegacyCatalogConverter.main(new String[]{legacy.toString(), staging.toString()});
        assertEquals(customized, Files.readString(staging.resolve("rules").resolve("ruleset.json")));
        assertEquals(LegacyCatalogConverter.coreRecipes(), Files.readString(staging.resolve("rules").resolve("recipes.json")));
        Files.writeString(rules.resolve("ruleset.json"), "{invalid");
        assertThrows(AutomationHost.NotReady.class,
                () -> LegacyCatalogConverter.main(new String[]{legacy.toString(), temp.resolve("invalid").toString()}));
    }

    @Test
    void channelsDeriveBenefitsAurasAndEndOnSevereDamageThroughTheHost() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"warden","name":"Warden","features":[
                  {"name":"Elemental Incarnation","automation":{"grantsFeatures":["Elemental Incarnation: Fire","Elemental Incarnation: Air"],"groupGrantedFeatures":true}},
                  {"name":"Elemental Incarnation: Fire","automation":{"actions":[{"id":"channel","label":"Channel Fire","channel":"Fire","cost":{"stress":1},
                    "steps":[{"op":"effect","effect":{"name":"Channeling: Fire","kind":"buff","group":"channel","until":"rest","endOnDamage":3}}]}]}},
                  {"name":"Elemental Incarnation: Air","automation":{"actions":[{"id":"channel","label":"Channel Air","channel":"Air","cost":{"stress":1},
                    "steps":[{"op":"effect","effect":{"name":"Channeling: Air","kind":"buff","group":"channel","until":"rest","endOnDamage":3}}]}]}},
                  {"name":"Elemental Dominion: Air","automation":{"channelBenefit":{"element":"Air","effect":{"name":"Elemental Dominion: Air","kind":"buff",
                    "requiresEffect":"Channeling: Air","modifiers":[{"stat":"evasion","value":1}]}}}},
                  {"name":"Elemental Dominion: Fire","automation":{"channelBenefit":{"element":"Fire","effect":{"name":"Elemental Dominion: Fire","kind":"buff",
                    "requiresEffect":"Channeling: Fire","damage":{"extraDice":1,"dropLowest":0,"proficiencyOnly":true}}}}},
                  {"name":"Elemental Aura","automation":{"uses":{"max":1,"per":"rest"},"actions":[{"id":"use","label":"Elemental Aura","aura":true}]}},
                  {"name":"Elemental Aura: Air","automation":{"channelBenefit":{"element":"aura","effect":{"name":"Elemental Aura: Air","kind":"buff","until":"rest"}}}}
                ]}"""));
        assertEquals(result.blocks(), result.converted(), result.diagnostics().toString());
        JsonNode features = result.catalog().at("/entries/0/features");
        assertEquals("{\"features\":[\"Elemental Incarnation: Fire\",\"Elemental Incarnation: Air\"],\"display\":\"grouped\"}",
                features.at("/0/automation/grants").toString());
        assertEquals("channel", features.at("/1/automation/effects/channeling-fire/exclusiveGroup").asText());
        assertTrue(features.at("/3/automation/effects/elemental-dominion-air").has("while"));
        assertTrue(features.at("/4/automation/rules").findValuesAsText("id").contains("elemental-dominion-fire-damage"));

        var pack = AutomationHost.compile("test", Map.of("classes", result.catalog().toString()), LegacyCatalogConverter.EMPTY_RECIPES);
        String[] names = {"fire", "air", "dominion-air", "dominion-fire", "aura", "aura-air"};
        ObjectNode sheet = (ObjectNode) RuleJson.parse("""
                {"name":"Ada","level":1,"evasion":10,"hp":{"slots":6,"total":6,"on":0},"stress":{"slots":6,"total":6,"on":0},
                 "armorTrack":{"slots":3,"total":3,"on":0},"hope":{"slots":6,"total":6,"on":2},"proficiency":{"slots":6,"total":6,"on":1},
                 "traits":[],"features":[],"heritage":[],"loadout":[],"vault":[],"inventory":[],"activeWeapons":[]}""");
        for (int i = 0; i < names.length; i++)
            ((ArrayNode) sheet.get("features")).addObject().put("name", names[i]).put("text", "")
                    .put("automationSource", "classes:warden:features:" + result.assignedIds().get("/entries/0/features/" + (i + 1)))
                    .put("instanceId", "f-" + names[i]);
        AutomationHost host = AutomationHost.bundled();
        ObjectNode action = RuleJson.JSON.createObjectNode().put("type", "action.request");
        java.util.function.Function<ObjectNode, Map<String, Boolean>> available = described -> {
            Map<String, Boolean> map = new java.util.TreeMap<>();
            for (JsonNode item : described.path("actions")) map.put(item.path("label").asText(), item.path("available").asBoolean());
            return map;
        };
        java.util.function.Function<ObjectNode, List<String>> derived = described ->
                described.path("derivedEffects").findValuesAsText("label").stream().sorted().toList();
        java.util.function.BiFunction<String, String, ObjectNode> request = (instance, rule) -> {
            ObjectNode built = RuleJson.JSON.createObjectNode().put("requestId", instance + host.revision(sheet)).put("expectedRevision", host.revision(sheet));
            built.putArray("selections").addObject().put("sourceInstanceId", instance).put("ruleId", rule).put("bindingId", "sheet");
            return built;
        };

        ObjectNode before = host.describe(pack, sheet, action, "sheet", "self");
        assertFalse(available.apply(before).get("Elemental Aura"), "the aura needs an active channel");
        assertEquals(List.of(), derived.apply(before));

        host.execute(pack, sheet, action, request.apply("f-air", "channel"), "self", null);
        host.execute(pack, sheet, action, request.apply("f-aura", "use"), "self", null);
        ObjectNode air = host.describe(pack, sheet, action, "sheet", "self");
        assertEquals(List.of("Elemental Aura: Air", "Elemental Dominion: Air"), derived.apply(air));
        assertEquals(1, air.at("/derivedEffects").findValues("modifiers").stream().mapToInt(JsonNode::size).sum());
        assertEquals(List.of("Channeling: Air", "Elemental Aura"), sheet.at("/automationState/effects").findValuesAsText("label"),
                "derived effects are never persisted");

        host.execute(pack, sheet, action, request.apply("f-fire", "channel"), "self", null);
        assertEquals(List.of("Elemental Aura", "Channeling: Fire"), sheet.at("/automationState/effects").findValuesAsText("label"),
                "a new channel replaces the previous one");
        assertEquals(List.of("Elemental Dominion: Fire"), derived.apply(host.describe(pack, sheet, action, "sheet", "self")));

        ObjectNode damage = RuleJson.JSON.createObjectNode().put("type", "damage.applied");
        damage.putObject("applied").put("severity", 2);
        ObjectNode none = RuleJson.JSON.createObjectNode().put("requestId", "d1").put("expectedRevision", host.revision(sheet));
        none.putArray("selections");
        host.execute(pack, sheet, damage, none, "self", null);
        assertTrue(sheet.at("/automationState/effects").findValuesAsText("label").contains("Channeling: Fire"), "major damage keeps the channel");
        damage.with("applied").put("severity", 3);
        none.put("requestId", "d2").put("expectedRevision", host.revision(sheet));
        host.execute(pack, sheet, damage, none, "self", null);
        assertEquals(List.of("Elemental Aura"), sheet.at("/automationState/effects").findValuesAsText("label"), "severe damage ends the channel");
    }

    @Test
    void sourceSevereAndIncomingDamageEndingsBecomeAutomaticDamageRules() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"a","name":"A","automation":{"actions":[{"id":"use","label":"Storm","steps":[{"op":"effect","effect":{"name":"Storm","endOnSourceSevere":true}}]}]}},
                {"id":"b","name":"B","automation":{"actions":[{"id":"use","label":"Cloak","steps":[{"op":"effect","effect":{"name":"Cloak","endAfterIncomingDamage":true}}]}]}},
                {"id":"c","name":"C","automation":{"actions":[{"id":"use","label":"Both","steps":[{"op":"effect","effect":{"name":"Both","endOnDamage":2,"endOnSourceSevere":true}}]}]}}"""));
        assertEquals(2, result.converted(), result.diagnostics().toString());
        JsonNode storm = result.catalog().at("/entries/0/automation/rules/1");
        assertEquals("damage.applied", storm.at("/bindings/0/event").asText());
        assertTrue(storm.path("when").toString().contains("{\"gte\":[{\"read\":\"event.applied.severity\"},3]}"), storm.toString());
        assertEquals("storm", storm.at("/do/0/effect").asText());
        JsonNode cloak = result.catalog().at("/entries/1/automation/rules/1");
        assertTrue(cloak.path("when").toString().contains("{\"gte\":[{\"read\":\"event.applied.incoming\"},1]}"), cloak.toString());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.path().endsWith("/endOnSourceSevere") && d.message().contains("only one")));
    }

    @Test
    void keptEffectsSkipTheirNextDamageEndingThroughDeclaredState() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"a","name":"A","automation":{"actions":[{"id":"use","label":"Shell","steps":[{"op":"effect","effect":{"name":"Shell","endAfterIncomingDamage":true,
                "mitigation":[{"id":"keep","label":"Keep the shell","cost":{"hope":1},"preserveAfterIncomingDamage":"Shell"}]}}]}]}}"""));
        JsonNode automation = block(result, 0);
        assertFalse(automation.at("/state/keep-shell").asBoolean(true), automation.toString());
        JsonNode keep = null, ending = null;
        for (JsonNode rule : automation.path("rules")) {
            if ("damage.prepare".equals(rule.at("/bindings/0/event").asText())) keep = rule;
            if ("damage.applied".equals(rule.at("/bindings/0/event").asText())) ending = rule;
        }
        assertNotNull(keep, automation.toString());
        assertEquals("state.set", ending.at("/do/1/op").asText(), automation.toString());
        assertEquals("state.set", keep.at("/do/0/op").asText());
        RuleCompiler.Program program = program(result, 0);
        String effect = "{\"effects\":[{\"id\":\"fixture/shell/1\",\"effect\":\"" + program.sourceId() + "#shell\",\"sourceInstanceId\":\"fixture\",\"label\":\"Shell\"}]}";
        String event = "{\"type\":\"damage.applied\",\"applied\":{\"severity\":2,\"incoming\":2,\"amount\":6}}";
        ObjectNode kept = context(program, effect, event);
        kept.putObject("state").put("keep-shell", true);
        ObjectNode after = run(program, kept).contexts().get(0);
        assertEquals(1, after.at("/actor/effects").size(), after.toString());
        assertFalse(after.at("/state/keep-shell").asBoolean(true));
        ObjectNode ended = run(program, context(program, effect, event)).contexts().get(0);
        assertEquals(0, ended.at("/actor/effects").size(), ended.toString());
        var invalid = LegacyCatalogConverter.convert(catalog("""
                {"id":"b","name":"B","automation":{"mitigation":[{"id":"keep","label":"Keep","negate":true,"preserveAfterIncomingDamage":"Shell"}]}}"""));
        assertTrue(invalid.diagnostics().stream().anyMatch(d -> d.message().contains("Keeping an effect")), invalid.diagnostics().toString());
    }

    @Test
    void conditionalModifiersBecomePassiveModifiersWithReadableConditions() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"m","name":"M","automation":{
                  "modifiers":[{"stat":"evasion","markedHpDivisor":2,"max":2}],
                  "conditionalModifiers":[
                    {"stat":"armorScore","value":1,"loadoutDomain":{"domain":"Valor","min":2},"requiresArmor":true},
                    {"roll":"attack","markedStressMultiplier":1,"hopeMin":2},
                    {"stat":"evasion","value":1,"armorFull":true,"requiresEffect":"Guarded"}]}}"""));
        assertEquals(4, block(result, 0).path("modifiers").size(), block(result, 0).toString());
        RuleCompiler.Program program = program(result, 0);
        String actor = """
                {"hp":{"marked":5,"capacity":6},"stress":{"marked":3,"capacity":6},"hope":{"value":%d,"max":6},
                 "armorTrack":{"marked":3,"capacity":3},%s
                 "loadout":[{"id":"a","domain":"Valor"},{"id":"b","domain":"Valor"},{"id":"c","domain":"Blade"}],
                 "effects":[{"id":"x/g/1","effect":"other#g","sourceInstanceId":"x","label":"Guarded"}]}""";
        ArrayNode all = RuleEffects.passives(program, context(program, actor.formatted(2, "\"armor\":{\"score\":3},"), "{\"type\":\"none\"}"));
        assertEquals(4, all.size(), all.toString());
        assertEquals(2, RuleEffects.stat(0, "evasion", List.of(all.get(0))));
        assertEquals(3, RuleEffects.stat(0, "roll.attack", all));
        ArrayNode few = RuleEffects.passives(program, context(program, actor.formatted(1, ""), "{\"type\":\"none\"}"));
        assertEquals(2, few.size(), few.toString());
        var invalid = LegacyCatalogConverter.convert(catalog("""
                {"id":"b","name":"B","automation":{"conditionalModifiers":[{"stat":"evasion","value":1,"markedHpDivisor":2}]}}"""));
        assertTrue(invalid.diagnostics().stream().anyMatch(d -> d.message().contains("exactly one value source")), invalid.diagnostics().toString());
    }

    @Test
    void gatedActionAndRollOptionFamiliesConvertLikeTheirBaseFamilies() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"g","name":"G","automation":{
                  "conditionalActions":[{"id":"hope","label":"Gain Hope","requiresZeroHope":true,"loadoutDomain":{"domain":"Night","min":2},
                    "steps":[{"op":"gain","resource":"hope","amount":1}]}],
                  "conditionalRollOptions":[{"id":"full","label":"Full Stress","roll":"damage","condition":{"stressFull":true},"proficiency":1},
                    {"id":"hurt","label":"Hurt","roll":"action","condition":{"markedHp":1},"value":1}]}}"""));
        JsonNode automation = block(result, 0);
        assertEquals(3, automation.path("rules").size(), automation.toString());
        RuleCompiler.Program program = program(result, 0);
        String actor = """
                {"hope":{"value":%d,"max":6},"hp":{"marked":%d,"capacity":6},"stress":{"marked":%d,"capacity":6},
                 "loadout":[{"id":"a","domain":"Night"},{"id":"b","domain":"Night"}],"effects":[]}""";
        assertEquals(List.of("hope"), available(program, context(program, actor.formatted(0, 0, 0), "{\"type\":\"action.request\"}"), "sheet"));
        assertEquals(List.of(), available(program, context(program, actor.formatted(1, 0, 0), "{\"type\":\"action.request\"}"), "sheet"));
        String damage = "{\"type\":\"roll.prepare\",\"check\":{\"kind\":\"damage\",\"weaponSourceId\":\"w\",\"fixedDice\":false}}", action = "{\"type\":\"roll.prepare\",\"check\":{\"kind\":\"action\"}}";
        assertEquals(List.of("full"), available(program, context(program, actor.formatted(0, 0, 6), damage), "roll"));
        assertEquals(List.of(), available(program, context(program, actor.formatted(0, 0, 5), damage), "roll"));
        assertEquals(List.of("hurt"), available(program, context(program, actor.formatted(0, 1, 0), action), "roll"));
        assertEquals(List.of(), available(program, context(program, actor.formatted(0, 0, 0), action), "roll"));
    }

    @Test
    void traitMultiplierDamageDieRequiredAndWeaponTriggerOptionsConvert() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"t","name":"T","automation":{
                  "rollOptions":[{"id":"brute","label":"Brute","roll":"damage","traitValue":"Strength"},
                    {"id":"double","label":"Double","roll":"damage","multiplier":2},
                    {"id":"big","label":"Big","roll":"attack","damageDie":12}],
                  "rollTriggers":[{"id":"main","label":"Main","primaryWeapon":true,"steps":[{"op":"gain","resource":"hope","amount":1}]},
                    {"id":"any","label":"Any","anyWeapon":true,"steps":[{"op":"gain","resource":"hope","amount":1}]}]}}"""));
        JsonNode rules = block(result, 0).path("rules");
        assertEquals("all", rules.at("/1/do/0/contribution/of").asText(), rules.toString());
        assertEquals(-100, rules.at("/1/bindings/0/priority").asInt());
        RuleCompiler.Program program = program(result, 0);
        String damageRoll = """
                {"id":"dmg","metadata":{"weaponBaseContributions":["weapon"]},"contributions":[{"id":"weapon","kind":"dice","role":"damage","sourceId":"weapon:axe","label":"Axe",
                  "dice":[{"id":"w1","sides":8,"face":3,"kept":true,"origin":"rolled"}]}]}""";
        String actor = "{\"hope\":{\"value\":0,\"max\":6},\"stats\":{\"trait\":{\"Strength\":2}},\"effects\":[]}";
        ObjectNode damage = context(program, actor, """
                {"type":"roll.prepare","check":{"kind":"damage","weaponSourceId":"weapon:axe","fixedDice":false},"roll":%s}""".formatted(damageRoll));
        assertEquals((3 + 2) * 2, run(program, damage, "double", "brute").contexts().getFirst().at("/event/roll/total").asInt());

        ObjectNode attack = context(program, actor, """
                {"type":"roll.prepare","check":{"kind":"attack","weaponSourceId":"weapon:axe"},"roll":%s}""".formatted(ROLL));
        ObjectNode marked = run(program, attack, "big").contexts().getFirst();
        assertEquals(1, marked.at("/actor/effects").size(), marked.toString());
        ObjectNode finished = marked.deepCopy();
        finished.set("event", RuleJson.parse("""
                {"type":"roll.finished","check":{"kind":"damage","weaponSourceId":"weapon:axe","fixedDice":false},"roll":%s}""".formatted(damageRoll)));
        JsonNode resized = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, finished)), List.of(),
                StructuredRollTest.faces(11)).contexts().getFirst().at("/event/roll");
        assertEquals(12, resized.at("/contributions/0/dice/0/sides").asInt(), resized.toString());
        assertEquals(11, resized.path("total").asInt());

        String finishedAttack = "{\"type\":\"roll.finished\",\"check\":{\"kind\":\"attack\",\"weapon\":\"%s\",\"outcome\":\"success\"},\"roll\":" + ROLL + "}";
        assertEquals(List.of("any", "main"), available(program, context(program, actor, finishedAttack.formatted("primary")), "roll"));
        assertEquals(List.of("any"), available(program, context(program, actor, finishedAttack.formatted("secondary")), "roll"));

        var weapons = LegacyCatalogConverter.convert("""
                {"format":"dmmap-daggerheart","version":1,"category":"weapons","label":"Fixture","entries":[
                  {"id":"pain","name":"Pain","automation":{"rollOptions":[
                    {"id":"painful","label":"Painful","roll":"attack","required":true,"defaultOn":true,"cost":{"stress":1},"value":1}]}}]}""");
        JsonNode painful = block(weapons, 0).at("/rules/0");
        assertEquals("automatic", painful.at("/bindings/0/mode").asText(), painful.toString());
        assertFalse(painful.at("/bindings/0").has("defaultOn"));
        RuleCompiler.Program pain = program(weapons, 0);
        String stressed = "{\"stress\":{\"marked\":0,\"capacity\":6},\"effects\":[]}";
        String own = "{\"type\":\"roll.prepare\",\"check\":{\"kind\":\"attack\",\"weaponSource\":\"%s\"},\"roll\":" + ROLL + "}";
        ObjectNode paid = run(pain, context(pain, stressed, own.formatted("fixture"))).contexts().getFirst();
        assertEquals(1, paid.at("/actor/stress/marked").asInt(), paid.toString());
        assertEquals(2, paid.at("/event/roll/total").asInt());
        ObjectNode other = run(pain, context(pain, stressed, own.formatted("other"))).contexts().getFirst();
        assertEquals(0, other.at("/actor/stress/marked").asInt(), "Only rolls with this weapon pay.");

        var invalid = LegacyCatalogConverter.convert(catalog("""
                {"id":"b","name":"B","automation":{"rollOptions":[{"id":"x","label":"X","roll":"damage","multiplier":1,"damageDie":8,"traitValue":"Luck"}]}}"""));
        assertEquals(3, invalid.diagnostics().size(), invalid.diagnostics().toString());
    }

    @Test
    void computedPoolResetsAndAdvantagedRerollTriggersConvert() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"t","name":"T","automation":{"resources":[
                    {"id":"fane","label":"Fane","initial":0,"resetOn":"longRest","resetValue":"@cards_sage"},
                    {"id":"plan","label":"Plan","initial":0,"resetOn":"longRest","resetValue":"@total_knowledge","minimumReset":1}],
                  "rollTriggers":[{"id":"again","label":"Again","reroll":"duality","advantage":true}]}}"""));
        JsonNode automation = block(result, 0);
        assertEquals("max", automation.at("/pools/plan/resetTo").fieldNames().next(), automation.toString());
        RuleCompiler.Program program = program(result, 0);
        String actor = """
                {"stats":{"trait":{"Knowledge":-1}},"effects":[],
                 "loadout":[{"id":"a","domain":"Sage"},{"id":"b","domain":"Arcana"}],"vault":[{"id":"c","domain":"Sage"}]}""";
        ObjectNode rested = RuleRuntime.resetLimits(program, context(program, actor, "{\"type\":\"rest.long\"}"), "rest.long");
        assertEquals(2, rested.at("/actor/pools/fane/value").asInt(), rested.toString());
        assertEquals(1, rested.at("/actor/pools/plan/value").asInt(), "The minimum applies to a negative trait.");

        String duality = """
                {"id":"check","contributions":[{"id":"hope","kind":"dice","role":"hope","sourceId":"core","label":"Hope",
                  "dice":[{"id":"h","sides":12,"face":2,"kept":true,"origin":"rolled"}]},
                  {"id":"fear","kind":"dice","role":"fear","sourceId":"core","label":"Fear",
                  "dice":[{"id":"f","sides":12,"face":3,"kept":true,"origin":"rolled"}]}]}""";
        ObjectNode finished = context(program, actor, "{\"type\":\"roll.finished\",\"check\":{\"kind\":\"action\"},\"roll\":" + duality + "}");
        JsonNode roll = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, finished)),
                List.of(new RuleRuntime.Intent("again", "use", "fixture", 0, RuleJson.JSON.createObjectNode())),
                StructuredRollTest.faces(10, 11, 4)).contexts().getFirst().at("/event/roll");
        assertEquals(10 + 11 + 4, roll.path("total").asInt(), roll.toString());
        assertEquals("advantage", roll.at("/contributions/2/role").asText());

        var invalid = LegacyCatalogConverter.convert(catalog("""
                {"id":"b","name":"B","automation":{"resources":[{"id":"x","resetValue":"@cards_sage"}],
                  "rollTriggers":[{"id":"y","label":"Y","advantage":true}]}}"""));
        assertEquals(2, invalid.diagnostics().size(), invalid.diagnostics().toString());
    }

    @Test
    void giveFearAndOneDieChecksConvert() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"t","name":"T","automation":{"resources":[{"id":"focus","label":"Focus","initial":0}],
                  "actions":[{"id":"steady","label":"Steady","check":{"dice":"1d6","unless":6,"mark":"stress"}}],
                  "rollTriggers":[{"id":"fear","label":"Fear","giveFear":2},
                    {"id":"spark","label":"Spark","check":{"dice":"1d4","success":4,"gain":"focus"}},
                    {"id":"drain","label":"Drain","check":{"dice":"1d6","success":6},"choices":[
                      {"id":"hp","label":"Clear HP","steps":[{"op":"clear","resource":"hp","amount":1}]},
                      {"id":"stress","label":"Clear Stress","steps":[{"op":"clear","resource":"stress","amount":1}]}]}]}}"""));
        JsonNode rules = block(result, 0).path("rules");
        assertEquals("Check 1d6: {result.check}.", rules.at("/0/ui/report").asText(), rules.toString());
        RuleCompiler.Program program = program(result, 0);
        String actor = "{\"hp\":{\"marked\":2,\"capacity\":6},\"stress\":{\"marked\":2,\"capacity\":6},\"effects\":[]}";
        ObjectNode sheet = context(program, actor, "{\"type\":\"action.request\"}");
        var steady = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, sheet)),
                List.of(new RuleRuntime.Intent("steady", "sheet", "fixture", 0, RuleJson.JSON.createObjectNode())), StructuredRollTest.faces(3));
        assertEquals(3, steady.contexts().getFirst().at("/actor/stress/marked").asInt());
        var lucky = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, sheet)),
                List.of(new RuleRuntime.Intent("steady", "sheet", "fixture", 0, RuleJson.JSON.createObjectNode())), StructuredRollTest.faces(6));
        assertEquals(2, lucky.contexts().getFirst().at("/actor/stress/marked").asInt());

        ObjectNode finished = context(program, actor, "{\"type\":\"roll.finished\",\"table\":{\"fear\":3},\"check\":{\"kind\":\"attack\",\"outcome\":\"success\"},\"roll\":" + ROLL + "}");
        JsonNode outbox = run(program, finished, "fear").contexts().getFirst().path("outbox");
        assertEquals("table.gain", outbox.at("/0/kind").asText(), outbox.toString());
        assertEquals("fear", outbox.at("/0/resource").asText());
        assertEquals(2, outbox.at("/0/amount").asInt());
        var spark = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, finished)),
                List.of(new RuleRuntime.Intent("spark", "use", "fixture", 0, RuleJson.JSON.createObjectNode())), StructuredRollTest.faces(4));
        assertEquals(1, spark.contexts().getFirst().at("/actor/pools/focus/value").asInt(), spark.contexts().getFirst().toString());
        var drain = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, finished)),
                List.of(new RuleRuntime.Intent("drain", "use", "fixture", 0, (ObjectNode) RuleJson.parse("{\"choice\":\"hp\"}"))), StructuredRollTest.faces(6));
        assertEquals(1, drain.contexts().getFirst().at("/actor/hp/marked").asInt());
        assertEquals(2, drain.contexts().getFirst().at("/actor/stress/marked").asInt());

        var mitigation = LegacyCatalogConverter.convert("""
                {"format":"dmmap-daggerheart","version":1,"category":"armor","label":"Fixture","entries":[
                  {"id":"a","name":"A","automation":{"mitigation":[{"id":"brace","label":"Brace","when":{"armor":true},"armorSlots":"any","giveFear":1}]}}]}""");
        assertTrue(block(mitigation, 0).toString().contains("\"table.gain\""));

        var invalid = LegacyCatalogConverter.convert(catalog("""
                {"id":"b","name":"B","automation":{"actions":[{"id":"x","label":"X","check":{"dice":"2d6","success":4}},
                  {"id":"y","label":"Y","choices":[]}]}}"""));
        assertEquals(2, invalid.diagnostics().size(), invalid.diagnostics().toString());
    }

    @Test
    void scaledDiceRangeAndMagicFiltersConvert() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"t","name":"T","automation":{"rollOptions":[
                  {"id":"rite","label":"Rite","roll":"damage","dice":"1d6","diceByLevel":{"5":"2d6","8":"3d6"},
                   "diceConditions":[{"feature":"Last Stand","unmarkedHp":{"min":1,"max":1},"dieSize":8}]},
                  {"id":"mark","label":"Mark","roll":"damage","dice":"@tierd4","diceByFeature":{"Ambush":"@tierd6","Backstab":"@tierd8"}},
                  {"id":"spark","label":"Spark","roll":"damage","magicOnly":true,"value":2},
                  {"id":"close","label":"Close","roll":"damage","weapon":"any","weaponRanges":["Melee","Very Close"],"value":1},
                  {"id":"sure","label":"Sure","roll":"trait:Agility","ignoreDisadvantage":true}]}}"""));
        RuleCompiler.Program program = program(result, 0);
        String actor = "{\"stats\":{\"level\":%d,\"tier\":%d},\"features\":[%s],\"heritage\":[],\"hp\":{\"marked\":%d,\"capacity\":6},\"effects\":[]}";
        String damage = "{\"type\":\"roll.prepare\",\"check\":{\"kind\":\"damage\",\"trait\":null%s},\"roll\":{\"id\":\"d\",\"contributions\":[{\"id\":\"base\",\"kind\":\"flat\",\"role\":\"damage\",\"sourceId\":\"core\",\"label\":\"Base\",\"value\":0}]}}";
        java.util.function.BiFunction<ObjectNode, String, JsonNode> dice = (context, id) -> {
            JsonNode roll = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, context)),
                    List.of(new RuleRuntime.Intent(id, "use", "fixture", 0, RuleJson.JSON.createObjectNode())), StructuredRollTest.faces(1, 1, 1, 1, 1))
                    .contexts().getFirst().at("/event/roll/contributions/1/dice");
            return roll;
        };
        assertEquals(1, dice.apply(context(program, actor.formatted(3, 1, "", 0), damage.formatted("")), "rite").size());
        JsonNode five = dice.apply(context(program, actor.formatted(5, 2, "", 0), damage.formatted("")), "rite");
        assertEquals(2, five.size());
        assertEquals(6, five.at("/0/sides").asInt());
        assertEquals(3, dice.apply(context(program, actor.formatted(9, 4, "", 0), damage.formatted("")), "rite").size());
        JsonNode stand = dice.apply(context(program, actor.formatted(9, 4, "{\"label\":\"Last Stand\"}", 5), damage.formatted("")), "rite");
        assertEquals(3, stand.size());
        assertEquals(8, stand.at("/0/sides").asInt(), "The die size condition keeps the level-scaled count.");

        JsonNode tiered = dice.apply(context(program, actor.formatted(5, 3, "{\"label\":\"Ambush\"},{\"label\":\"Backstab\"}", 0), damage.formatted("")), "mark");
        assertEquals(3, tiered.size());
        assertEquals(8, tiered.at("/0/sides").asInt(), "The last listed present feature wins.");

        String plain = actor.formatted(1, 1, "", 0);
        assertFalse(available(program, context(program, plain, damage.formatted(",\"damageType\":\"physical\"")), "roll").contains("spark"));
        assertTrue(available(program, context(program, plain, damage.formatted(",\"damageType\":\"magic\"")), "roll").contains("spark"));
        assertTrue(available(program, context(program, plain, damage.formatted(",\"spell\":true")), "roll").contains("spark"));
        assertTrue(available(program, context(program, plain, damage.formatted(",\"weapon\":\"primary\",\"range\":\"veryClose\"")), "roll").contains("close"));
        assertFalse(available(program, context(program, plain, damage.formatted(",\"weapon\":\"primary\",\"range\":\"far\"")), "roll").contains("close"));
        assertFalse(available(program, context(program, plain, damage.formatted(",\"weapon\":\"primary\",\"range\":\"melee\",\"spell\":true")), "roll").contains("close"));

        ObjectNode agility = context(program, plain, """
                {"type":"roll.prepare","check":{"kind":"action","trait":"Agility"},"roll":{"id":"act","contributions":[
                  {"id":"hope","kind":"dice","role":"hope","sourceId":"core","label":"Hope","dice":[{"id":"h","sides":12,"face":4,"kept":true,"origin":"rolled"}]},
                  {"id":"dis","kind":"dice","role":"disadvantage","sourceId":"core","label":"Disadvantage","sign":-1,
                   "dice":[{"id":"x","sides":6,"face":5,"kept":true,"origin":"rolled"}]}]}}""");
        assertEquals(-90, block(result, 0).at("/rules/4/bindings/0/priority").asInt());
        JsonNode neutral = run(program, agility, "sure").contexts().getFirst().at("/event/roll");
        assertEquals(1, neutral.path("contributions").size(), neutral.toString());
        assertEquals(4, neutral.path("total").asInt());

        var invalid = LegacyCatalogConverter.convert(catalog("""
                {"id":"b","name":"B","automation":{"rollOptions":[{"id":"x","label":"X","roll":"damage","diceByLevel":{"5":"2d6"}},
                  {"id":"y","label":"Y","roll":"damage","weaponRanges":["Distant"]}]}}"""));
        assertEquals(2, invalid.diagnostics().size(), invalid.diagnostics().toString());
    }
    @Test
    void damageTypeChoiceMarksTheDamageRoll() throws Exception {
        var result = LegacyCatalogConverter.convert("""
                {"format":"dmmap-daggerheart","version":1,"category":"weapons","label":"Fixture","entries":[
                  {"id":"blade","name":"Blade","automation":{"damageTypeChoice":true}}]}""");
        RuleCompiler.Program program = program(result, 0);
        String prepare = """
                {"type":"roll.prepare","check":{"kind":"damage","trait":null,"weaponSource":"%s"},"roll":{"id":"d","metadata":{"kind":"damage","damageType":"physical"},
                  "contributions":[{"id":"base","kind":"flat","role":"damage","sourceId":"core","label":"Base","value":3}]}}""";
        assertEquals(List.of(), available(program, context(program, "{\"effects\":[]}", prepare.formatted("other")), "roll"));
        ObjectNode own = context(program, "{\"effects\":[]}", prepare.formatted("fixture"));
        assertEquals(List.of("magic-damage"), available(program, own, "roll"));
        JsonNode roll = run(program, own, "magic-damage").contexts().getFirst().at("/event/roll");
        assertEquals("magic", roll.at("/metadata/damageType").asText(), roll.toString());
        assertEquals(3, roll.path("total").asInt());

        var invalid = LegacyCatalogConverter.convert(catalog("""
                {"id":"b","name":"B","automation":{"damageTypeChoice":true}}"""));
        assertEquals(1, invalid.diagnostics().size(), invalid.diagnostics().toString());
    }
    @Test
    void triggerDiceAdditionsAndDamageDiceEditsConvert() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"edits","name":"Edits","automation":{"rollTriggers":[
                  {"id":"poet","label":"Poet","on":"action","addDice":"1d4","cost":{"hope":1}},
                  {"id":"bonus","label":"Bonus","on":"action","addDice":"2"},
                  {"id":"volatile","label":"Volatile","on":"damage","damageDice":"reroll"},
                  {"id":"twice","label":"Twice","on":"damage","damageDice":"double"}]}}"""));
        block(result, 0);
        RuleCompiler.Program program = program(result, 0);
        String actor = "{\"hope\":{\"value\":2,\"max\":6},\"effects\":[]}";
        ObjectNode action = context(program, actor, "{\"type\":\"roll.finished\",\"check\":{\"kind\":\"action\",\"trait\":null},\"roll\":" + ROLL + "}");
        JsonNode poet = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, action)),
                List.of(new RuleRuntime.Intent("poet", "use", "fixture", 0, RuleJson.JSON.createObjectNode())), StructuredRollTest.faces(3))
                .contexts().getFirst();
        assertEquals(1 + 3, poet.at("/event/roll/total").asInt(), poet.toString());
        assertEquals(1, poet.at("/actor/hope/value").asInt());
        assertEquals(1 + 2, RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, action)), List.of(new RuleRuntime.Intent("bonus", "use", "fixture", 0, RuleJson.JSON.createObjectNode())), StructuredRollTest.noRandom()).contexts().getFirst().at("/event/roll/total").asInt());

        String damageRoll = """
                {"type":"roll.finished","check":{"kind":"damage","trait":null},"roll":{"id":"dmg","contributions":[
                  {"id":"weapon","kind":"dice","role":"damage","sourceId":"weapon:axe","label":"Axe","dice":[
                    {"id":"w1","sides":8,"face":3,"kept":true,"origin":"rolled"},{"id":"w2","sides":8,"face":5,"kept":true,"origin":"rolled"}]}]}}""";
        ObjectNode damage = context(program, actor, damageRoll);
        assertEquals(List.of("twice", "volatile"), available(program, damage, "roll"));
        JsonNode rerolled = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, damage)),
                List.of(new RuleRuntime.Intent("volatile", "use", "fixture", 0, (ObjectNode) RuleJson.parse("{\"dice\":[\"w1\"]}"))),
                StructuredRollTest.faces(8)).contexts().getFirst().at("/event/roll");
        assertEquals(8 + 5, rerolled.path("total").asInt(), rerolled.toString());
        assertThrows(Exception.class, () -> RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, damage)),
                List.of(new RuleRuntime.Intent("volatile", "use", "fixture", 0, (ObjectNode) RuleJson.parse("{\"dice\":[]}"))),
                StructuredRollTest.noRandom()));
        JsonNode doubled = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, damage)), List.of(new RuleRuntime.Intent("twice", "use", "fixture", 0, (ObjectNode) RuleJson.parse("{\"dice\":[\"w2\"]}"))), StructuredRollTest.noRandom()).contexts().getFirst().at("/event/roll");
        assertEquals(3 + 5 + 5, doubled.path("total").asInt(), doubled.toString());
        assertThrows(Exception.class, () -> RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, damage)), List.of(new RuleRuntime.Intent("twice", "use", "fixture", 0, (ObjectNode) RuleJson.parse("{\"dice\":[\"w1\",\"w2\"]}"))), StructuredRollTest.noRandom()));

        var invalid = LegacyCatalogConverter.convert(catalog("""
                {"id":"b","name":"B","automation":{"rollTriggers":[{"id":"x","label":"X","on":"action","damageDice":"double"}]}}"""));
        assertEquals(1, invalid.diagnostics().size(), invalid.diagnostics().toString());
    }

    @Test
    void alternativeHopeRewardsBecomeRollRewardClaims() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"visage","name":"Visage","automation":{"hopeReward":{"removeFear":1,                "roll":"trait:Presence"}}},
                                {"id":"slayer","name":"Slayer","automation":{"classResourceOnly":true,"hopeReward":{"resource":"slayer-dice","amount":1,"roll":"action"},
                  "resources":[{"id":"slayer-dice","label":"Slayer Dice","initial":0,"max":3}]}}"""));
        JsonNode visage = block(result, 0);
        assertEquals("roll-reward", visage.at("/rules/0/bindings/0/claimGroup").asText(), visage.toString());
        assertEquals("result", visage.at("/rules/0/bindings/0/claimGroupScope").asText());
        assertEquals("class", block(result, 1).at("/pools/slayer-dice/section").asText(), block(result, 1).toString());
        RuleCompiler.Program program = program(result, 0);
        String actor = "{\"id\":\"me\",\"hope\":{\"value\":2,\"max\":6},\"effects\":[]}";
        String hope = "{\"type\":\"roll.finished\",\"table\":{\"fear\":3},\"check\":{\"kind\":\"action\",\"trait\":\"Presence\",\"dominant\":\"hope\",\"critical\":false,\"outcome\":null}}";
        ObjectNode presence = context(program, actor, hope);
        assertEquals(List.of("hope-reward"), available(program, presence, "roll"));
        for (String other : List.of(hope.replace("\"dominant\":\"hope\"", "\"dominant\":\"fear\""), hope.replace("\"critical\":false", "\"critical\":true"),
                hope.replace("\"outcome\":null", "\"outcome\":\"failure\""), hope.replace("\"kind\":\"action\"", "\"kind\":\"reaction\""),
                hope.replace("Presence", "Agility")))
            assertEquals(List.of(), available(program, context(program, actor, other), "roll"), other);
        var claim = RuleDispatcher.executeClaims(List.of(new RuleDispatcher.Candidate(program, presence)),
                List.of(new RuleDispatcher.ClaimIntent(new RuleRuntime.Intent("hope-reward", "claim", "fixture", 0, RuleJson.JSON.createObjectNode()), "me", 0, 0)),
                List.of(new RuleDispatcher.RecipientCandidate("me", (ObjectNode) presence.path("actor").deepCopy(), 0)),
                new RuleDispatcher.ClaimLedger(0, Set.of()), StructuredRollTest.noRandom());
        assertEquals(Set.of(new RuleDispatcher.ClaimKey("roll-reward", "me")), claim.ledger().claims());
        assertEquals("table.spend", claim.outbox().getFirst().path("kind").asText());
        assertEquals("Removed 1 GM Fear instead of gaining Hope.", claim.actions().getFirst().action().report());

        var invalid = LegacyCatalogConverter.convert(catalog("""
                {"id":"b","name":"B","automation":{"hopeReward":{"removeFear":1,"favor":1,"roll":"action"}}}"""));
        assertEquals(1, invalid.diagnostics().size(), invalid.diagnostics().toString());
    }

    @Test
    void armorSlotStatesBecomeDerivedEffectsAndTrackRules() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"plate","name":"Plate","automation":{
                  "armorHalfEffect":{"name":"Beacon","kind":"debuff","until":"manual","text":"Enemies have advantage."},
                  "armorFullHeldEffect":{"name":"Reinforced","until":"manual","modifiers":[{"stat":"thresholds","value":2,"armorFull":true}]},
                  "armorFullEffect":{"name":"Last Bastion","until":"scene","text":"Resistance."},
                  "zeroHopeClearArmor":1,"overflowHope":1}},
                {"id":"brawler","name":"Brawler","automation":{"criticalMelee":{"hope":1,"stress":1,"proficiency":1}}}"""));
        JsonNode plate = block(result, 0);
        RuleCompiler.Program program = program(result, 0);
        String actor = "{\"id\":\"me\",\"hope\":{\"value\":2,\"max\":6},\"armorTrack\":{\"marked\":%d,\"capacity\":4},\"effects\":[]}";
        List<String> derived = new java.util.ArrayList<>();
        for (int marked : new int[]{1, 2, 4}) {
            List<String> labels = new java.util.ArrayList<>();
            RuleEffects.derived(program, context(program, actor.formatted(marked), "{\"type\":\"track.changed\"}")).forEach(e -> labels.add(e.path("label").asText()));
            derived.add(String.join("+", labels));
        }
        assertEquals(List.of("", "Beacon", "Beacon+Reinforced"), derived, plate.toString());
        assertEquals(2, plate.at("/effects/reinforced/modifiers/0/value").asInt(), plate.toString());

        String full = "{\"type\":\"track.changed\",\"track\":\"armorTrack\",\"change\":\"mark\",\"requested\":1,"
                + "\"before\":{\"marked\":3,\"capacity\":4},\"after\":{\"marked\":4,\"capacity\":4}}";
        var filled = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, context(program, actor.formatted(4), full))),
                List.of(), StructuredRollTest.noRandom());
        assertEquals(1, filled.actions().size());
        assertEquals("Last Bastion started.", filled.actions().getFirst().report());
        String overflow = "{\"type\":\"track.changed\",\"track\":\"armorTrack\",\"change\":\"clear\",\"requested\":2,"
                + "\"before\":{\"marked\":1,\"capacity\":4},\"after\":{\"marked\":0,\"capacity\":4}}";
        var gained = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, context(program, actor.formatted(0), overflow))),
                List.of(), StructuredRollTest.noRandom());
        assertEquals(3, gained.contexts().getFirst().at("/actor/hope/value").asInt(), gained.contexts().toString());
        String zero = "{\"type\":\"track.changed\",\"track\":\"hope\",\"before\":{\"value\":1},\"after\":{\"value\":0}}";
        var cleared = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program,
                context(program, actor.formatted(3).replace("\"value\":2", "\"value\":0"), zero))), List.of(), StructuredRollTest.noRandom());
        assertEquals(2, cleared.contexts().getFirst().at("/actor/armorTrack/marked").asInt(), cleared.contexts().toString());

        RuleCompiler.Program brawler = program(result, 1);
        String melee = "{\"type\":\"roll.finished\",\"check\":{\"kind\":\"attack\",\"critical\":true,\"range\":\"melee\",\"dominant\":\"hope\",\"outcome\":null}}";
        String me = "{\"id\":\"me\",\"hope\":{\"value\":2,\"max\":6},\"stress\":{\"marked\":3,\"capacity\":6},\"effects\":[]}";
        ObjectNode crit = context(brawler, me, melee);
        assertEquals("result", brawler.rule("critical-melee-reward").at("/bindings/0/claimGroupScope").asText());
        assertEquals(List.of("critical-melee-reward"), available(brawler, crit, "roll"));
        assertEquals(List.of(), available(brawler, context(brawler, me, melee.replace("melee\"", "far\"")), "roll"));
        String capped = me.replace("\"value\":2", "\"value\":6").replace("\"marked\":3", "\"marked\":0");
        assertEquals(List.of(), available(brawler, context(brawler, capped, melee), "roll"));
        var claim = RuleDispatcher.executeClaims(List.of(new RuleDispatcher.Candidate(brawler, crit)),
                List.of(new RuleDispatcher.ClaimIntent(new RuleRuntime.Intent("critical-melee-reward", "claim", "fixture", 0, RuleJson.JSON.createObjectNode()), "me", 0, 0)),
                List.of(new RuleDispatcher.RecipientCandidate("me", (ObjectNode) crit.path("actor").deepCopy(), 0)),
                new RuleDispatcher.ClaimLedger(0, Set.of()), StructuredRollTest.noRandom());
        assertEquals("Gained 2 Hope and cleared 2 Stress from a critical melee attack.", claim.actions().getFirst().action().report());
        var pending = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(brawler, crit)), List.of(), StructuredRollTest.noRandom());
        assertEquals(1, pending.contexts().getFirst().at("/actor/effects").size(), pending.contexts().toString());
    }

    @Test
    void matchingDiceDoubleSharedDamageFaces() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"t","name":"T","automation":{"rollOptions":[
                  {"id":"res","label":"Resonance","roll":"damage","dice":"3d6","matchingDice":true}]}}"""));
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        RuleCompiler.Program program = program(result, 0);
        String damage = "{\"type\":\"roll.prepare\",\"check\":{\"kind\":\"damage\",\"trait\":null},\"roll\":{\"id\":\"d\",\"contributions\":[{\"id\":\"base\",\"kind\":\"flat\",\"role\":\"damage\",\"sourceId\":\"core\",\"label\":\"Base\",\"value\":0}]}}";
        String actor = "{\"stats\":{\"level\":1,\"tier\":1},\"features\":[],\"heritage\":[],\"hp\":{\"marked\":0,\"capacity\":6},\"effects\":[]}";
        JsonNode roll = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, context(program, actor, damage))),
                List.of(new RuleRuntime.Intent("res", "use", "fixture", 0, RuleJson.JSON.createObjectNode())), StructuredRollTest.faces(2, 5, 2))
                .contexts().getFirst().at("/event/roll");
        assertEquals(13, roll.path("total").asInt(), roll.toString());
        assertEquals(-80, block(result, 0).at("/rules/0/bindings/0/priority").asInt(), "Matching runs after other options add dice.");
    }

    @Test
    void consumableActionsUseUpUnitsUnlessKeptOrPortionsRemain() throws Exception {
        var result = LegacyCatalogConverter.convert("""
                {"format":"dmmap-daggerheart","version":1,"category":"consumables","label":"Fixture","entries":[
                  {"id":"jar","name":"Jar","automation":{"resources":[{"id":"bites","label":"Bites","initial":2,"max":2}],"actions":[
                    {"id":"eat","label":"Eat","portions":"bites","cost":{"bites":1}},
                    {"id":"look","label":"Look","keepItem":true,"report":{"text":"Looked."}},
                    {"id":"drink","label":"Drink","minimumHope":2,"refreshUses":"long"},
                    {"id":"throw","label":"Throw","damage":{"dice":"@bitesd8+4","fixed":true,"type":"physical"}}]}}]}""");
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        JsonNode rules = block(result, 0).path("rules");
        assertTrue(rules.get(3).toString().contains("{\"read\":\"actor.pools.bites.value\"},\"sides\":8"), rules.get(3).toString());
        assertFalse(rules.get(1).toString().contains("inventory.consume"), "Kept items are not used up.");
        assertTrue(rules.get(2).path("costs").toString().contains("inventory.consume"), rules.get(2).toString());
        assertEquals("gte", rules.get(2).path("when").fieldNames().next(), rules.get(2).toString());
        assertTrue(rules.get(2).toString().contains("\"limits.refresh\""), rules.get(2).toString());

        RuleCompiler.Program program = program(result, 0);
        String actor = "{\"hope\":{\"value\":1,\"max\":6},\"effects\":[],\"pools\":{\"bites\":{\"value\":%d}}}";
        String request = "{\"type\":\"action.request\"}";
        RuleRuntime.Intent eat = new RuleRuntime.Intent("eat", "sheet", "fixture", 0, RuleJson.JSON.createObjectNode());
        ObjectNode first = context(program, actor.formatted(2), request);
        first.with("source").put("quantity", 2);
        JsonNode kept = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, first)), List.of(eat), StructuredRollTest.noRandom()).contexts().getFirst();
        assertEquals(2, kept.at("/source/quantity").asInt(), kept.toString());
        assertEquals(1, kept.at("/actor/pools/bites/value").asInt(), kept.toString());
        ObjectNode last = context(program, actor.formatted(1), request);
        last.with("source").put("quantity", 2);
        JsonNode used = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, last)), List.of(eat), StructuredRollTest.noRandom()).contexts().getFirst();
        assertEquals(1, used.at("/source/quantity").asInt(), used.toString());
        assertEquals(2, used.at("/actor/pools/bites/value").asInt(), "The next unit starts with full portions.");
        ObjectNode broke = context(program, actor.formatted(2), request);
        broke.with("source").put("quantity", 1);
        assertFalse(available(program, broke, "sheet").contains("drink"), "Needs 2 Hope.");
        assertTrue(available(program, broke, "sheet").contains("throw"), "Throwing needs portions left.");
    }

    @Test
    void healingHandsHealsOneAllyOnceUntilTheirLongRest() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"t","name":"T","automation":{"spellcastDifficulty":13,"conditionalRollTriggers":[
                  {"id":"hp","label":"Heal 2 HP","on":"card:T","when":"success","difficulty":13,"splendorAction":"healingHands","hostOwned":true,
                   "excludeSourceActor":true,"exclusiveRollGroup":"healing","target":"actor","cost":{"stress":1},"steps":[{"op":"clear","resource":"hp","amount":2}]},
                  {"id":"stress","label":"Heal 2 Stress","on":"card:T","when":"success","difficulty":13,"splendorAction":"healingHands","hostOwned":true,
                   "excludeSourceActor":true,"exclusiveRollGroup":"healing","target":"actor","cost":{"stress":1},"steps":[{"op":"clear","resource":"stress","amount":2}]}]}}"""));
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        JsonNode selection = block(result, 0).at("/rules/0/bindings/0/targetSelection");
        assertTrue(selection.has("where"), selection.toString());
        RuleCompiler.Program program = program(result, 0);
        String actor = "{\"id\":\"owner\",\"name\":\"Ari\",\"effects\":[],\"stress\":{\"marked\":0,\"capacity\":6},\"hp\":{\"marked\":0,\"capacity\":6}}";
        ObjectNode source = context(program, actor, "{\"type\":\"roll.finished\",\"check\":{\"kind\":\"action\",\"origin\":\"card:T\",\"critical\":false,\"outcome\":null,\"total\":15,\"difficulty\":13},\"roll\":" + ROLL + "}");
        source.with("targetOptions").set(selection.path("scope").asText(), RuleJson.parse("""
                [{"id":"mira","label":"Mira","revision":2,"snapshot":{"id":"mira","effects":[]}},
                 {"id":"bo","label":"Bo","revision":1,"snapshot":{"id":"bo","effects":[{"label":"Healing Hands recovery"}]}}]"""));
        JsonNode described = RuleDispatcher.describe(List.of(new RuleDispatcher.Candidate(program, source)), "roll").get(0);
        assertEquals(1, described.at("/targetSelection/options").size(), described.toString());
        assertEquals("mira", described.at("/targetSelection/options/0/id").asText());
        String mira = "{\"id\":\"mira\",\"name\":\"Mira\",\"effects\":[],\"hp\":{\"marked\":4,\"capacity\":6}}";
        RuleDispatcher.TargetResolution resolved = RuleDispatcher.executeTargets(List.of(new RuleDispatcher.Candidate(program, source)),
                new RuleDispatcher.TargetIntent(new RuleRuntime.Intent("hp", "use", "fixture", 0, RuleJson.JSON.createObjectNode()), "mira", 2L),
                List.of(new RuleDispatcher.RecipientCandidate("mira", (ObjectNode) RuleJson.parse(mira), 2)), StructuredRollTest.noRandom());
        assertEquals(2, resolved.recipients().getFirst().snapshot().at("/hp/marked").asInt(), resolved.toString());
        assertEquals(1, resolved.sourceContexts().getFirst().at("/actor/stress/marked").asInt());
        assertEquals("Mira: cleared 2 HP.", resolved.actions().getFirst().action().report());
        for (int marked : List.of(0, 1)) {
            for (String track : List.of("hp", "stress")) {
                ObjectNode recipient = (ObjectNode) RuleJson.parse("""
                        {"id":"mira","name":"Mira","effects":[],"%s":{"marked":%d,"capacity":6}}""".formatted(track, marked));
                var limited = RuleDispatcher.executeTargets(List.of(new RuleDispatcher.Candidate(program, source)),
                        new RuleDispatcher.TargetIntent(new RuleRuntime.Intent(track, "use", "fixture", 0,
                                RuleJson.JSON.createObjectNode()), "mira", 2L),
                        List.of(new RuleDispatcher.RecipientCandidate("mira", recipient, 2)), StructuredRollTest.noRandom());
                assertEquals("Mira: cleared " + marked + ("hp".equals(track) ? " HP." : " Stress."),
                        limited.actions().getFirst().action().report());
            }
        }
        String healed = "{\"id\":\"bo\",\"name\":\"Bo\",\"hp\":{\"marked\":4,\"capacity\":6},\"effects\":[{\"label\":\"Healing Hands recovery\"}]}";
        assertThrows(RuleRuntime.Unavailable.class, () -> RuleDispatcher.executeTargets(List.of(new RuleDispatcher.Candidate(program, source)),
                new RuleDispatcher.TargetIntent(new RuleRuntime.Intent("hp", "use", "fixture", 0, RuleJson.JSON.createObjectNode()), "bo", 1L),
                List.of(new RuleDispatcher.RecipientCandidate("bo", (ObjectNode) RuleJson.parse(healed), 1)), StructuredRollTest.noRandom()));
    }

    @Test
    void stunningSunlightSpendsChosenHopeAndRollsBothReactionOutcomes() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"t","name":"T","automation":{"spellcastDifficulty":13,"rollTriggers":[
                  {"id":"sun","label":"Sunlight","on":"card:T","when":"success","difficulty":13,"splendorAction":"sunlight","hostOwned":true}]}}"""));
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        RuleCompiler.Program program = program(result, 0);
        ObjectNode source = context(program, "{\"id\":\"owner\",\"name\":\"Ari\",\"hope\":{\"value\":4,\"max\":6},\"effects\":[]}",
                "{\"type\":\"roll.finished\",\"check\":{\"kind\":\"action\",\"origin\":\"card:T\",\"critical\":false,\"outcome\":null,\"total\":15,\"difficulty\":13},\"roll\":" + ROLL + "}");
        RuleDispatcher.EventResolution done = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, source)),
                List.of(new RuleRuntime.Intent("sun", "use", "fixture", 0, (ObjectNode) RuleJson.parse("{\"amount\":3}"))),
                StructuredRollTest.faces(1, 2, 3, 4, 5, 6, 7));
        assertEquals(1, done.contexts().getFirst().at("/actor/hope/value").asInt());
        String report = done.actions().getFirst().report();
        assertTrue(report.contains("Spent 3 Hope") && report.contains("magic damage"), report);
    }

    @Test
    void secondWindClearsChosenRecoveryOnSelfOrAfterAHopefulAttackOnAnAlly() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"t","name":"T","automation":{"uses":{"max":1,"per":"rest"},
                 "actions":[{"id":"self","label":"Second Wind","hostOwned":true,"splendorAction":"secondWind"}],
                 "rollTriggers":[{"id":"ally","label":"Second Wind","on":"attack","successIfKnown":true,"hostOwned":true,"splendorAction":"secondWind"}]}}"""));
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        RuleCompiler.Program program = program(result, 0);
        String actor = "{\"id\":\"owner\",\"name\":\"Ari\",\"effects\":[],\"hp\":{\"marked\":3,\"capacity\":6},\"stress\":{\"marked\":5,\"capacity\":6}}";
        ObjectNode action = context(program, actor, "{\"type\":\"action.request\"}");
        RuleDispatcher.EventResolution done = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, action)),
                List.of(new RuleRuntime.Intent("self", "sheet", "fixture", 0, (ObjectNode) RuleJson.parse("{\"recovery\":\"stress\"}"))), StructuredRollTest.noRandom());
        assertEquals(2, done.contexts().getFirst().at("/actor/stress/marked").asInt());
        assertEquals(3, done.contexts().getFirst().at("/actor/hp/marked").asInt());
        String miss = "{\"type\":\"roll.finished\",\"check\":{\"kind\":\"attack\",\"critical\":false,\"dominant\":\"fear\",\"outcome\":\"success\",\"total\":15},\"roll\":" + ROLL + "}";
        assertFalse(available(program, context(program, actor, miss), "roll").contains("ally"));
        String hopeful = miss.replace("\"fear\"", "\"hope\"");
        ObjectNode withAlly = context(program, actor, hopeful);
        withAlly.with("targetOptions").set("connectedPlayerCharacters", RuleJson.parse("[{\"id\":\"mira\",\"label\":\"Mira\",\"revision\":1}]"));
        assertTrue(available(program, withAlly, "roll").contains("ally"));
    }

    @Test
    void salvationBeamSpendsChosenStressToHealOneWoundedAlly() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"t","name":"T","automation":{"spellcastDifficulty":16,"conditionalRollTriggers":[
                  {"id":"heal","label":"Salvation Beam","on":"card:T","when":"success","difficulty":16,"splendorAction":"salvation","hostOwned":true}]}}"""));
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        RuleCompiler.Program program = program(result, 0);
        String event = "{\"type\":\"roll.finished\",\"check\":{\"kind\":\"action\",\"origin\":\"card:T\",\"critical\":false,\"outcome\":null,\"total\":17,\"difficulty\":16},\"roll\":" + ROLL + "}";
        String actor = "{\"id\":\"owner\",\"name\":\"Ari\",\"effects\":[],\"hp\":{\"marked\":0,\"capacity\":6},\"stress\":{\"marked\":4,\"capacity\":6}}";
        ObjectNode source = context(program, actor, event);
        source.with("targetOptions").set("connectedPlayerCharacters", RuleJson.parse("[{\"id\":\"mira\",\"label\":\"Mira\",\"revision\":2}]"));
        RuleDispatcher.TargetResolution resolved = RuleDispatcher.executeTargets(List.of(new RuleDispatcher.Candidate(program, source)),
                new RuleDispatcher.TargetIntent(new RuleRuntime.Intent("heal", "use", "fixture", 0,
                        (ObjectNode) RuleJson.parse("{\"amount\":2}")), "mira", 2L),
                List.of(new RuleDispatcher.RecipientCandidate("mira", (ObjectNode) RuleJson.parse(
                        "{\"id\":\"mira\",\"name\":\"Mira\",\"hp\":{\"marked\":4,\"capacity\":6}}"), 2)), StructuredRollTest.noRandom());
        assertEquals(6, resolved.sourceContexts().getFirst().at("/actor/stress/marked").asInt());
        assertEquals(2, resolved.recipients().getFirst().snapshot().at("/hp/marked").asInt());
    }

    @Test
    void invigorationAsksTheHostToRestoreOneLimitedUseOnAnyCharacter() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"t","name":"T","automation":{"actions":[{"id":"restore","label":"Roll","resourceRoll":{"resource":"hope","sides":6},"hostOwned":true}],
                 "conditionalRollTriggers":[{"id":"restore-use","label":"Restore","on":"featureAction:card:T:restore","containsDie":6,"when":"always","splendorAction":"invigoration","hostOwned":true,"usesSource":false}]}}"""));
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        RuleCompiler.Program program = program(result, 0);
        JsonNode selection = block(result, 0).at("/rules/1/bindings/0/targetSelection");
        assertEquals("activeCharacters", selection.path("scope").asText(), selection.toString());
        assertFalse(selection.path("excludeSource").asBoolean(), selection.toString());
        assertEquals("limit.restore", block(result, 0).at("/rules/1/do/0/op").asText());
    }

    @Test
    void zoneOfProtectionSharesOneSourcePoolAcrossEveryHolder() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"t","name":"T","automation":{"spellcastDifficulty":16,"uses":{"max":1,"per":"longRest"},
                 "resources":[{"id":"protection","label":"Protection","initial":0,"max":6,"resetOn":"longRest","resetValue":0}],
                 "conditionalRollTriggers":[{"id":"activate","label":"Activate","on":"card:T","when":"success","difficulty":16,"hostOwned":true,"splendorAction":"zone"}]}}"""));
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        JsonNode block = block(result, 0);
        assertEquals("source", block.at("/pools/protection/scope").asText(), block.toString());
        JsonNode rules = block.path("rules");
        JsonNode mitigation = null;
        for (JsonNode rule : rules) if ("damage.prepare".equals(rule.at("/bindings/0/event").asText())) mitigation = rule;
        assertNotNull(mitigation, rules.toString());
        assertTrue(mitigation.toString().contains("sourcePools.protection.value"), mitigation.toString());
        assertEquals("pool.spend", mitigation.at("/do/2/op").asText(), mitigation.toString());
        assertEquals("pool.gain", mitigation.at("/do/3/op").asText(), mitigation.toString());
        RuleCompiler.Program program = program(result, 0);
        assertNotNull(program);
    }

    @Test
    void witchTalismansAreGrantedAsTransferableItems() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"ward","name":"Ward","automation":{"uses":{"max":1,"per":"rest"},
                 "actions":[{"id":"infuse","label":"Infuse","hostOwned":true,"steps":[
                   {"op":"effect","effect":{"name":"Rune Ward source","kind":"buff","until":"manual","witchGrant":"runeWard"}}]}]}},
                {"id":"charm","name":"Charm","automation":{"uses":{"max":1,"per":"rest"},
                 "actions":[{"id":"use","label":"Imbue","witchAction":"talisman"}]}}"""));
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        String ward = block(result, 0).toString(), charm = block(result, 1).toString();
        assertTrue(ward.contains("\"op\":\"inventory.grant\"") && ward.contains("\"entry\":\"rune-ward\"") && ward.contains("\"to\":\"actor\""), ward);
        assertTrue(charm.contains("\"entry\":\"enchanted-talisman\"") && charm.contains("input.amount"), charm);
        assertNotNull(program(result, 0));
        assertNotNull(program(result, 1));
    }

    @Test
    void carriedItemsPayAndExpireThroughTheirOwnQuantity() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"talisman","name":"Talisman","automation":{"rest":{"expireItem":true},
                 "mitigation":[{"id":"protect","label":"Protect","hp":-1,"quantity":true,"markedHpOnly":true,"cost":{"@item":1},"when":{"markingHp":true}}]}},
                {"id":"ward","name":"Ward","automation":{"mitigation":[{"id":"ward","label":"Ward","cost":{"hope":1},"roll":{"sides":8},"destroyResourceOnMax":"@item","reportRoll":true}]}}"""));
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        String talisman = block(result, 0).toString(), ward = block(result, 1).toString();
        assertTrue(talisman.contains("source.quantity") && talisman.contains("\"op\":\"inventory.consume\""), talisman);
        assertTrue(talisman.contains("rest.short") && talisman.contains("rest.long"), talisman);
        assertTrue(ward.contains("\"op\":\"inventory.consume\"") && ward.contains("result.reduction"), ward);
        assertNotNull(program(result, 0));
        assertNotNull(program(result, 1));
    }

    @Test
    void swapDualityResourceMultipliersAndOriginExclusionsConvert() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"aid","name":"Aid","automation":{"rollTriggers":[{"id":"swap","label":"Swap","on":"any","swapDuality":true,"repeatable":true}]}},
                {"id":"upstage","name":"Upstage","automation":{"resources":[{"id":"upstaged","label":"Upstaged","initial":0,"max":6}],
                  "rollOptions":[{"id":"up","label":"Upstaged","roll":"damage","resourceMultiplier":{"resource":"upstaged","value":5}}]}},
                {"id":"cloak","name":"Cloak","automation":{"hideSpellcast":true,
                  "conditionalRollTriggers":[{"id":"x","label":"X","on":"any","excludeRolls":["card:Cloak"],"reroll":"hope"}]}}"""));
        assertEquals(3, result.converted(), result.diagnostics().toString());
        String aid = block(result, 0).toString();
        assertTrue(aid.contains("\"op\":\"roll.swapRoles\"") && aid.contains("event.check.fearDie"), aid);
        String upstage = block(result, 1).toString();
        assertTrue(upstage.contains("\"multiply\"") && upstage.contains("actor.pools.upstaged.value"), upstage);
        assertTrue(block(result, 2).toString().contains("card:Cloak"));
        for (int i = 0; i < 3; i++) assertNotNull(program(result, i));
    }

    @Test
    void companionIsPoolStateWithInterceptsPayingItsSlots() throws Exception {
        String text = catalog("""
                {"id":"pet","name":"Companion","automation":{"companionTraining":1,"companion":{"evasion":10,"stress":3,"maxStress":6,
                  "options":[{"id":"armored","name":"Armored","max":1},{"id":"resilient","name":"Resilient","max":3,"stat":"stress","value":1}]}}},
                {"id":"loyal","name":"Loyal","automation":{"actions":[{"id":"use","label":"Loyal","requiresCompanion":true}],
                  "mitigation":[{"id":"plain","label":"Plain","companionIntercept":true,"when":{"lastHp":true},"replaceHp":true,"insteadOfArmor":true},
                   {"id":"armor","label":"Armored","companionIntercept":true,"companionArmored":true,"cost":{"armor":1},"when":{"lastHp":true},"replaceHp":true,                   "insteadOfArmor":true}]}}""");
                           var result = LegacyCatalogConverter.convert(text, LegacyCatalogConverter.sharedPools(List.of(text)));
                           assertEquals(2, result.converted(), result.diagnostics().toString());
                           JsonNode pet = block(result, 0);
        assertEquals(3, pet.at("/pools/companion-stress-slots/initial").asInt(), pet.toString());
        assertTrue(pet.at("/pools").has("companion-armored") && !pet.at("/pools").has("companion-resilient"), pet.toString());
        String loyal = block(result, 1).toString();
        assertTrue(loyal.contains("companion-stress-slots") && loyal.contains("companion-armored"), loyal);
        for (int i = 0; i < 2; i++) assertNotNull(program(result, i));
    }

    @Test
    void lunarPhasesBecomeDerivedEffectsOverADiePool() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"moon","name":"Moon","automation":{
                 "effectCounters":[{"label":"Lunar die","effect":"Lunar Phases","cardOnly":true}],
                 "lunarPhases":[{"faces":[2,3],"effect":{"name":"Lunar Phases","kind":"buff","text":"Damage."}},{"faces":[4],"effect":{"name":"Lunar Phases","kind":"buff","text":"Thresholds."}}],
                 "actions":[{"id":"roll","label":"Roll","witchAction":"lunarRoll"},{"id":"advance","label":"Advance","witchAction":"lunarAdvance"}]}}"""));
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        JsonNode block = block(result, 0);
        assertEquals("number", block.at("/pools/lunar-die/kind").asText(), block.toString());
        assertTrue(block.at("/effects/lunar-phases-die-2-3/while").has("or"), block.toString());
        assertTrue(block.at("/effects/lunar-phases-die-4/while").has("eq"), block.toString());
        assertNotNull(program(result, 0));
    }

    @Test
    void usesOverridesCardOnlyAndEffectBoundResourcesConvert() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"devout","name":"Devout","automation":{"usesOverrides":{"feat:Sparing Touch":2}}},
                {"id":"aura","name":"Aura","automation":{"actions":[{"id":"open","label":"Open","steps":[{"op":"effect","effect":{"name":"Aura source","kind":"buff","until":"rest","text":"Open."}}]}],
                 "resources":[{"id":"charge","label":"Charge","initial":0,"max":3,"cardOnly":true,"whileEffect":"Aura source"}]}},
                {"id":"bad","name":"Bad","automation":{"usesOverrides":{"Sparing Touch":2}}}"""));
        assertEquals(2, result.converted(), result.diagnostics().toString());
        JsonNode override = result.catalog().at("/entries/0/automation/limitOverrides/0");
        assertEquals("Sparing Touch", override.path("feature").asText());
        assertEquals("uses", override.path("limit").asText());
        assertEquals(2, override.path("max").asInt());
        JsonNode auraBlock = result.catalog().at("/entries/1/automation");
        JsonNode pool = auraBlock.at("/pools/charge");
        assertEquals("feature", pool.path("section").asText(), pool.toString());
        assertTrue(auraBlock.at("/effects").has(pool.path("whileEffect").asText()), pool.toString());
        assertEquals(1, result.diagnostics().size(), result.diagnostics().toString());
    }

    @Test
    void patronDiceScaleWithLevelAndHealClearOrReportPerHit() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"heal","name":"Heal","automation":{"resources":[{"id":"favor","label":"Favor","initial":0,"max":4}],
                 "actions":[{"id":"use","label":"Heal","patronDice":"heal"}]}},
                {"id":"ire","name":"Ire","automation":{"resources":[{"id":"favor","label":"Favor","initial":0,"max":4}],
                 "actions":[{"id":"use","label":"Ire","patronDice":"ire"}]}},
                {"id":"ven","name":"Ven","automation":{"resources":[{"id":"favor","label":"Favor","initial":0,"max":4}],
                 "actions":[{"id":"use","label":"Ven","patronDice":"vengeance","cost":{"favor":1}}],
                 "mitigation":[{"id":"vengeance","label":"Ven","when":{"markingHp":true},"cost":{"favor":1},"patronDice":"vengeance"}]}}"""));
        assertEquals(3, result.converted(), result.diagnostics().toString());
        JsonNode heal = block(result, 0).at("/rules/0/do");
        assertEquals("dice.hits", heal.at("/0/op").asText());
        assertEquals(4, heal.at("/0/atLeast").asInt());
        assertTrue(heal.at("/0/sides").toString().contains("actor.level"), heal.toString());
        assertEquals("track.clear", heal.at("/1/op").asText());
        assertEquals("dice.highest", block(result, 1).at("/rules/0/do/0/op").asText());
        assertEquals("event.applied.hitPoints", block(result, 2).at("/rules/1/do/0/count/read").asText());
    }

    @Test
    void hostRollActionsAreValidatedAndSpentTokensFlowIntoTheRollMetadata() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"paid","name":"Paid","automation":{"actions":[
                  {"id":"strike","label":"Strike","hostRollAction":true,"bloodAutomation":true,"cost":{"hope":1},
                   "actionRoll":{"trait":"Agility","attack":true}}]}},
                {"id":"chaos","name":"Chaos","automation":{
                  "actions":[{"id":"unleash","label":"Unleash","hostRollAction":true,"castTokens":{"resource":"hope","sides":10},
                   "actionRoll":{"trait":"spellcast"}}],
                  "conditionalRollTriggers":[{"id":"burst","label":"Burst","on":"card:Chaos","spentTokenDamage":{"sides":10}}]}},
                {"id":"help","name":"Help","automation":{"rollTriggers":[
                  {"id":"cheer","label":"Cheer","audience":"ally","otherPlayerOnly":true,"hostRollAction":true,"reroll":"duality"}]}}"""));
        assertEquals(3, result.converted(), result.diagnostics().toString());

        JsonNode unleash = block(result, 1).at("/rules/0");
        assertEquals("integer", unleash.at("/inputs/0/kind").asText());
        JsonNode annotate = unleash.at("/do");
        boolean annotated = false;
        for (JsonNode step : annotate) annotated |= "roll.resolve".equals(step.path("op").asText()) && step.at("/metadata/spentTokens/read").asText().equals("input.amount");
        assertTrue(annotated, unleash.toString());
        String burst = block(result, 1).at("/rules/1").toString();
        assertTrue(burst.contains("event.roll.metadata.spentTokens"), burst);
        assertEquals("others", block(result, 2).at("/rules/0/bindings/0/audience").asText());

        var invalid = LegacyCatalogConverter.convert(catalog("""
                {"id":"a","name":"A","automation":{"actions":[{"id":"x","label":"X","hostRollAction":true}]}},
                {"id":"b","name":"B","automation":{"actions":[{"id":"x","label":"X","hostRollAction":true,"actionRoll":{"trait":"Agility","reaction":true}}]}},
                {"id":"c","name":"C","automation":{"rollTriggers":[{"id":"x","label":"X","otherPlayerOnly":true,"reroll":"duality"}]}}"""));
        assertEquals(0, invalid.converted(), invalid.diagnostics().toString());
        assertEquals(3, invalid.diagnostics().size(), invalid.diagnostics().toString());
    }

    @Test
    void resurrectionVaultsTheCardOnlyOnFiveOrLess() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"t","name":"T","automation":{"spellcastDifficulty":13,"rollTriggers":[
                  {"id":"res","label":"Resurrect","on":"card:T","when":"success","difficulty":13,"splendorAction":"resurrection","hostOwned":true}]}}"""));
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        RuleCompiler.Program program = program(result, 0);
        String event = "{\"type\":\"roll.finished\",\"check\":{\"kind\":\"action\",\"origin\":\"card:T\",\"critical\":false,\"outcome\":null,\"total\":15,\"difficulty\":13},\"roll\":" + ROLL + "}";
        String actor = "{\"id\":\"owner\",\"name\":\"Ari\",\"effects\":[],\"loadout\":[{\"id\":\"fixture\"}],\"vault\":[]}";
        for (int face : new int[]{5, 6}) {
            ObjectNode source = context(program, actor, event);
            RuleDispatcher.EventResolution done = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, source)),
                    List.of(new RuleRuntime.Intent("res", "use", "fixture", 0, RuleJson.JSON.createObjectNode())), StructuredRollTest.faces(face));
            JsonNode after = done.contexts().getFirst().path("actor");
            assertEquals(face == 5 ? 1 : 0, after.path("vault").size(), after.toString());
            assertEquals(face == 5 ? 0 : 1, after.path("loadout").size(), after.toString());
        }
    }

    @Test
    void transferHopeMovesChosenHopeToOneAlly() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"t","name":"T","automation":{"actions":[{"id":"give","label":"Close-Knit","target":"actor","transferHope":true}]}}"""));
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        JsonNode selection = block(result, 0).at("/rules/0/bindings/0/targetSelection");
        assertEquals("one", selection.path("cardinality").asText(), selection.toString());
        assertTrue(selection.path("excludeSource").asBoolean(), selection.toString());
        RuleCompiler.Program program = program(result, 0);
        ObjectNode source = context(program, "{\"id\":\"owner\",\"name\":\"Ari\",\"hope\":{\"value\":3,\"max\":6},\"effects\":[]}",
                "{\"type\":\"action.request\"}");
        source.with("targetOptions").set(selection.path("scope").asText(), RuleJson.parse("""
                [{"id":"owner","label":"Ari","revision":0},{"id":"mira","label":"Mira","revision":2}]"""));
        RuleDispatcher.TargetResolution resolved = RuleDispatcher.executeTargets(List.of(new RuleDispatcher.Candidate(program, source)),
                new RuleDispatcher.TargetIntent(new RuleRuntime.Intent("give", "sheet", "fixture", 0,
                        (ObjectNode) RuleJson.parse("{\"amount\":2}")), "mira", 2L),
                List.of(new RuleDispatcher.RecipientCandidate("mira", (ObjectNode) RuleJson.parse(
                        "{\"id\":\"mira\",\"name\":\"Mira\",\"hope\":{\"value\":1,\"max\":6}}"), 2)), StructuredRollTest.noRandom());
        assertEquals(1, resolved.sourceContexts().getFirst().at("/actor/hope/value").asInt());
        assertEquals(3, resolved.recipients().getFirst().snapshot().at("/hope/value").asInt());
        assertEquals("Gave 2 Hope to Mira.", resolved.actions().getFirst().action().report());
    }

    @Test
    void effectRollBonusesAndDamageEndingsBecomeEffectRules() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"t","name":"T","automation":{"actions":[{"id":"drink","label":"Drink","steps":[
                  {"op":"effect","effect":{"name":"Stride","until":"manual","unique":true,"exclusiveSource":true,"endOnMarkedHp":true,"endOnDealtDamage":true,
                    "modifiers":[{"roll":"trait:Agility","value":1,"consume":true}]}}]}]}}"""));
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        JsonNode automation = block(result, 0);
        assertEquals("replaceAnySource", automation.at("/effects/stride/stacking").asText(), automation.toString());
        assertTrue(automation.at("/effects/stride/singleHolder").asBoolean(), automation.toString());
        String rules = automation.path("rules").toString();
        assertTrue(rules.contains("\"event.applied.hitPoints\""), rules);
        assertTrue(rules.contains("\"roll.finished\""), rules);

        RuleCompiler.Program program = program(result, 0);
        String actor = "{\"id\":\"ari\",\"stats\":{\"level\":1,\"tier\":1},\"features\":[],\"heritage\":[],\"effects\":[]}";
        ObjectNode started = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, context(program, actor, "{\"type\":\"action.request\"}"))),
                List.of(new RuleRuntime.Intent("drink", "sheet", "fixture", 0, RuleJson.JSON.createObjectNode())), StructuredRollTest.noRandom()).contexts().getFirst();
        assertEquals("effect.endElsewhere", started.at("/outbox/0/kind").asText(), started.toString());
        assertEquals("ari", started.at("/outbox/0/holder").asText(), started.toString());
        assertEquals("ari", started.at("/outbox/0/sourceActorId").asText(), started.toString());
        String prepare = "{\"type\":\"roll.prepare\",\"check\":{\"kind\":\"action\",\"trait\":\"Agility\"},\"roll\":{\"id\":\"d\",\"contributions\":[{\"id\":\"base\",\"kind\":\"flat\",\"role\":\"modifier\",\"sourceId\":\"core\",\"label\":\"Base\",\"value\":0}]}}";
        ObjectNode rolled = context(program, started.path("actor").toString(), prepare);
        JsonNode after = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, rolled)), List.of(), StructuredRollTest.noRandom()).contexts().getFirst();
        assertEquals(1, after.at("/event/roll/total").asInt(), after.toString());
        assertEquals(0, after.at("/actor/effects").size(), "The used-up bonus ends its effect.");
    }

    @Test
    void slotModifiersTriggerExtrasAndActionEndingsConvert() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"hammer","name":"Hammer","automation":{"modifiers":[{"roll":"attack","value":1,"weapon":"primary"}]}},
                {"id":"basher","name":"Basher","automation":{"rollOptions":[{"id":"basher","label":"Basher","roll":"damage","value":1,"excludeRolls":["spelldamage"]}]}},
                {"id":"mist","name":"Mist","automation":{"actions":[{"id":"use","label":"Mist","steps":[{"op":"effect","effect":{"name":"Mist","until":"scene","endOnAction":true}}]}]}},
                {"id":"dire","name":"Dire","automation":{"rollTriggers":[{"id":"use","label":"Dire","on":"damage","weaponDamage":true,"removeFear":1,"cost":{"hope":1}}]}},
                {"id":"star","name":"Star","automation":{"rollTriggers":[{"id":"re","label":"Rebound","on":"attack","when":"failure","reroll":"duality","disadvantage":true}]}},
                {"id":"scythe","name":"Scythe","automation":{"rollOptions":[{"id":"greedy","label":"Greedy","roll":"damage","proficiency":1,"cost":{"gold":10}}]}}"""));
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        String hammer = block(result, 0).toString();
        assertTrue(hammer.contains("\"automatic\"") && hammer.contains("\"primary\"") && hammer.contains("{source.name}"), hammer);
        assertFalse(hammer.contains("event.check.weaponSource"), "Slot modifiers apply to the equipped weapon, not this one: " + hammer);
        assertTrue(block(result, 1).toString().contains("\"event.check.spell\""), block(result, 1).toString());
        String mist = block(result, 2).path("rules").toString();
        assertTrue(mist.contains("\"spellcast\"") && mist.contains("\"action\"") && !mist.contains("\"reaction\""), mist);
        String dire = block(result, 3).toString();
        assertTrue(dire.contains("\"table.spend\"") && dire.contains("\"fear\"") && dire.contains("event.check.weapon"), dire);
        String star = block(result, 4).toString();
        assertTrue(star.contains("\"role\":\"disadvantage\"") && star.contains("\"sign\":-1"), star);
        String scythe = block(result, 5).toString();
        assertTrue(scythe.contains("\"actor.gold\""), scythe);
        for (int i = 0; i < 6; i++) program(result, i);
    }

    @Test
    void numericActorFactsCanPayResourceCosts() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"t","name":"T","automation":{"actions":[{"id":"buy","label":"Buy","cost":{"gold":10},"steps":[{"op":"gain","resource":"hope","amount":1}]}]}}"""));
        RuleCompiler.Program program = program(result, 0);
        String actor = "{\"id\":\"ari\",\"gold\":%d,\"hope\":{\"value\":0,\"max\":6},\"effects\":[]}";
        JsonNode paid = run(program, context(program, actor.formatted(12), "{\"type\":\"action.request\"}"), "buy").contexts().getFirst();
        assertEquals(2, paid.at("/actor/gold").asInt(), paid.toString());
        assertThrows(RuntimeException.class, () -> run(program, context(program, actor.formatted(9), "{\"type\":\"action.request\"}"), "buy"),
                "An unaffordable gold cost is rejected.");
    }

    @Test
    void deathMovesBecomeDeathRollOptionsAndDeathDialogButtons() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"feather","name":"Feather","automation":{"deathMove":{"kind":"hopeBonus","moves":["avoid"],"bonus":1}}},
                {"id":"mask","name":"Mask","automation":{"deathMove":{"kind":"mask","moves":["risk","avoid"],"bonus":1},
                  "resources":[{"id":"death-mask","label":"Mask tokens","initial":0,"max":4}],
                  "actions":[{"id":"gain","label":"Gain token","steps":[{"op":"gain","resource":"death-mask","amount":1}]}],
                  "manual":["Confirm the mask is worn."]}},
                {"id":"plate","name":"Plate","automation":{"deathMove":{"kind":"hopeSpend","moves":["risk"]},"uses":{"max":1,"per":"longRest"},
                  "actions":[{"id":"use","label":"Blessed","deathMove":"risk"}]}},
                {"id":"hardened","name":"Hardened","automation":{"deathMove":{"kind":"battleHardened"},"uses":{"max":1,"per":"longRest"},
                  "actions":[{"id":"use","label":"Clear HP","deathMove":"battleHardened"}]}},
                {"id":"unbreakable","name":"Unbreakable","automation":{"deathMove":{"kind":"unbreakable"},
                  "actions":[{"id":"use","label":"At last HP: clear HP","deathMove":"unbreakable"}]}},
                {"id":"egg","name":"Egg","automation":{"deathMove":{"kind":"egg"},"actions":[{"id":"use","label":"Break egg","deathMove":"egg"}]}}"""));
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        String roll = "{\"type\":\"roll.prepare\",\"check\":{\"kind\":\"%s\",\"move\":\"avoid\"},\"roll\":" + ROLL + "}";
        RuleCompiler.Program feather = program(result, 0);
        assertEquals(2, run(feather, context(feather, "{\"effects\":[]}", roll.formatted("death"))).contexts().getFirst().at("/event/roll/contributions").size());
        assertEquals(1, run(feather, context(feather, "{\"effects\":[]}", roll.formatted("action"))).contexts().getFirst().at("/event/roll/contributions").size());
        String mask = block(result, 1).toString();
        assertTrue(mask.contains("\"pool.spend\"") && mask.contains("\"option\"") && mask.contains("Confirm the mask is worn."), mask);
        String plate = block(result, 2).toString();
        assertTrue(plate.contains("\"input.quantity\"") && plate.contains("\"actor.hope\"") && plate.contains("\"risk\""), plate);
        for (int i = 0; i < 6; i++) program(result, i);

        String dying = "{\"id\":\"ari\",\"hope\":{\"value\":2,\"max\":6},\"hp\":{\"marked\":6,\"capacity\":6},\"effects\":[],"
                + "\"loadout\":[{\"id\":\"fixture\",\"name\":\"Unbreakable\"}],\"vault\":[]}";
        RuleCompiler.Program hardened = program(result, 3);
        JsonNode after = death(hardened, context(hardened, dying, "{\"type\":\"death.options\"}"), "use").contexts().getFirst();
        assertEquals(5, after.at("/actor/hp/marked").asInt(), after.toString());
        assertEquals(1, after.at("/actor/hope/value").asInt(), after.toString());
        RuleCompiler.Program unbreakable = program(result, 4);
        after = death(unbreakable, context(unbreakable, dying, "{\"type\":\"death.options\"}"), "use").contexts().getFirst();
        assertTrue(after.at("/actor/hp/marked").asInt() < 6, after.toString());
        assertEquals(0, after.at("/actor/loadout").size(), after.toString());
        assertEquals("fixture", after.at("/actor/vault/0/id").asText(), after.toString());
        assertTrue(block(result, 5).toString().contains("\"inventory.consume\""), block(result, 5).toString());
    }

    @Test
    void spectralFormEndsOnHealingAndBlocksItselfAtTheLastHitPoint() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"spectral","name":"Spectral","automation":{"deathMove":{"kind":"spectral"},
                  "actions":[{"id":"use","label":"Spectral Form death move","deathMove":"spectral"}]}}"""));
        RuleCompiler.Program program = program(result, 0);
        String actor = "{\"id\":\"ari\",\"hp\":{\"marked\":6,\"capacity\":6},\"effects\":[]}";
        JsonNode spectral = death(program, context(program, actor, "{\"type\":\"death.options\"}"), "use").contexts().getFirst();
        assertEquals("Spectral Form", spectral.at("/actor/effects/0/label").asText(), spectral.toString());
        assertEquals(5, spectral.at("/state/spectral-form-hp").asInt(), spectral.toString());
        String changed = "{\"type\":\"track.changed\",\"track\":\"hp\"}";
        ObjectNode marked = context(program, spectral.path("actor").toString(), changed);
        marked.set("state", spectral.path("state").deepCopy());
        ((ObjectNode) marked.at("/actor/hp")).put("marked", 6);
        JsonNode ended = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, marked)), List.of(), StructuredRollTest.noRandom())
                .contexts().getFirst();
        assertEquals(0, ended.at("/actor/effects").size(), ended.toString());
        assertTrue(ended.at("/state/spectral-form-blocked").asBoolean(), ended.toString());
        ObjectNode again = context(program, ended.path("actor").toString(), "{\"type\":\"death.options\"}");
        again.set("state", ended.path("state").deepCopy());
        assertEquals(List.of(), available(program, again, "death"));
        ObjectNode healed = context(program, ended.path("actor").toString(), changed);
        healed.set("state", ended.path("state").deepCopy());
        ((ObjectNode) healed.at("/actor/hp")).put("marked", 4);
        JsonNode open = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, healed)), List.of(), StructuredRollTest.noRandom())
                .contexts().getFirst();
        assertFalse(open.at("/state/spectral-form-blocked").asBoolean(true), open.toString());
    }

    @Test
    void undyingAndLifeWardConvertToGenericRules() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"undying","name":"Undying","automation":{"deathMove":{"kind":"undying"}}},
                {"id":"ward","name":"Ward","automation":{"deathMove":{"kind":"lifeWard"},"actions":[{"id":"use","label":"Life Ward","cost":{"hope":3},
                  "steps":[{"op":"effect","effect":{"name":"Life Ward","until":"manual","lifeWard":true,"text":"Clear 1 HP instead."}}]}],
                  "manual":["Choose an ally within Close range."]}}"""));
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        RuleCompiler.Program undying = program(result, 0);
        String failed = "{\"type\":\"roll.finished\",\"check\":{\"kind\":\"death\",\"move\":\"risk\",\"dominant\":\"fear\",\"critical\":false},\"roll\":" + ROLL + "}";
        ObjectNode dying = context(undying, "{\"id\":\"me\",\"hp\":{\"marked\":6,\"capacity\":6},\"effects\":[]}", failed);
        assertEquals(List.of("undying"), available(undying, dying, "roll"));
        assertEquals(List.of(), available(undying, context(undying, "{\"id\":\"me\",\"effects\":[]}", failed.replace("\"risk\"", "\"avoid\"")), "roll"));
        var claim = RuleDispatcher.executeClaims(List.of(new RuleDispatcher.Candidate(undying, dying)),
                List.of(new RuleDispatcher.ClaimIntent(new RuleRuntime.Intent("undying", "claim", "fixture", 0, RuleJson.JSON.createObjectNode()), "me", 0, 0)),
                List.of(new RuleDispatcher.RecipientCandidate("me", (ObjectNode) dying.path("actor").deepCopy(), 0)),
                new RuleDispatcher.ClaimLedger(0, Set.of()), StructuredRollTest.noRandom());
        JsonNode converted = claim.sourceContexts().getFirst();
        assertEquals(5, converted.at("/actor/hp/capacity").asInt(), converted.toString());
        assertEquals("hope", converted.at("/event/roll/metadata/deathOutcome").asText(), converted.toString());

        JsonNode ward = block(result, 1);
        assertTrue(ward.at("/rules/0/ui/description").asText().contains("Close range"), ward.toString());
        RuleCompiler.Program program = program(result, 1);
        String warded = "{\"hp\":{\"marked\":6,\"capacity\":6},\"effects\":[{\"id\":\"fixture/life-ward/1\",\"effect\":\""
                + program.sourceId() + "#life-ward\",\"sourceInstanceId\":\"fixture\",\"label\":\"Life Ward\"}]}";
        JsonNode after = death(program, context(program, warded, "{\"type\":\"death.options\"}"), "life-ward-death").contexts().getFirst();
        assertEquals(5, after.at("/actor/hp/marked").asInt(), after.toString());
        assertEquals(0, after.at("/actor/effects").size(), after.toString());
    }

    private static RuleDispatcher.EventResolution death(RuleCompiler.Program program, ObjectNode context, String rule) {
        return intent(program, context, rule, "death");
    }

    private static RuleDispatcher.EventResolution intent(RuleCompiler.Program program, ObjectNode context, String rule, String binding) {
        return RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, context)),
                List.of(new RuleRuntime.Intent(rule, binding, "fixture", 0, RuleJson.JSON.createObjectNode())), RandomGenerator.getDefault());
    }

    private static JsonNode block(LegacyCatalogConverter.Result result, int entry) {
        assertEquals(result.blocks(), result.converted(), result.diagnostics().toString());
        return result.catalog().at("/entries/" + entry + "/automation");
    }

    private static RuleCompiler.Program program(LegacyCatalogConverter.Result result, int entry) throws Exception {
        return RuleCatalog.compile(result.catalog().toString(), LegacyCatalogConverter.coreRecipes()).get(entry).program();
    }

    private static ObjectNode context(RuleCompiler.Program program, String actor, String event) throws Exception {
        ObjectNode context = (ObjectNode) RuleJson.parse("""
                {"source":{"id":"%s","instanceId":"fixture","name":"Fixture"},"actor":%s,"revision":0}
                """.formatted(program.sourceId(), actor));
        context.set("event", RuleJson.parse(event));
        return context;
    }

    private static RuleDispatcher.EventResolution run(RuleCompiler.Program program, ObjectNode context, String... rules) {
        return run(program, context, RandomGenerator.getDefault(), rules);
    }

    private static RuleDispatcher.EventResolution run(RuleCompiler.Program program, ObjectNode context, RandomGenerator random, String... rules) {
        String binding = switch (context.at("/event/type").asText()) {
            case "action.request" -> "sheet";
            case "downtime.move" -> "downtime";
            case "rest.prepare" -> "rest";
            case "rest.short" -> "rest-short";
            case "rest.long" -> "rest-long";
            default -> "use";
        };
        List<RuleRuntime.Intent> intents = java.util.Arrays.stream(rules)
                .map(rule -> new RuleRuntime.Intent(rule, binding, "fixture", context.path("revision").asLong(), RuleJson.JSON.createObjectNode())).toList();
        return RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, context)), intents, random);
    }

    private static List<String> available(RuleCompiler.Program program, ObjectNode context, String surface) {
        List<String> ids = new java.util.ArrayList<>();
        for (JsonNode rule : RuleDispatcher.describe(List.of(new RuleDispatcher.Candidate(program, context)), surface))
            if (rule.path("available").asBoolean(true)) ids.add(rule.path("ruleId").asText());
        return ids.stream().sorted().toList();
    }

    private static final String ROLL = """
            {"id":"check","contributions":[{"id":"base","kind":"flat","role":"modifier","sourceId":"core","label":"Base","value":1}]}""";

    @Test
    void resourcesBecomeActorPoolsSharedWithCostsInOtherCatalogs() throws Exception {
        String declaring = catalog("""
                {"id":"warlock","name":"Warlock","automation":{"resources":[{"id":"favor","label":"Favor {x}","max":5,"initial":2,"resetOn":"longRest"}]}}""");
        Map<String, ObjectNode> shared = LegacyCatalogConverter.sharedPools(List.of(declaring));
        JsonNode pool = block(LegacyCatalogConverter.convert(declaring, shared), 0).at("/pools/favor");
        assertEquals("actor", pool.path("scope").asText());
        assertEquals("Favor {{x}}", pool.path("label").asText());
        assertEquals(5, pool.path("max").asInt());
        assertEquals(2, pool.path("initial").asInt());
        assertEquals("[\"rest.long\"]", pool.path("resetOn").toString());

        var spending = LegacyCatalogConverter.convert(catalog("""
                {"id":"pact","name":"Pact","automation":{"actions":[{"id":"call","label":"Call","cost":{"favor":2},
                  "steps":[{"op":"gain","resource":"favor","amount":1}]}]}}"""), shared);
        JsonNode block = block(spending, 0);
        assertEquals(pool, block.at("/pools/favor"));
        assertEquals("pool.spend", block.at("/rules/0/costs/0/op").asText());
        assertEquals("pool.gain", block.at("/rules/0/do/0/op").asText());
        program(spending, 0);
        assertFalse(LegacyCatalogConverter.convert(catalog("""
                {"id":"pact","name":"Pact","automation":{"actions":[{"id":"call","label":"Call","cost":{"favor":2}}]}}""")).diagnostics().isEmpty());
    }

    @Test
    void timedActionsBecomeDowntimeMoveButtons() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"bedroll","name":"Bedroll","automation":{"actions":[
                  {"id":"rest","label":"Clear Stress","timing":"rest","steps":[{"op":"clear","resource":"stress","amount":1}]},
                  {"id":"later","label":"Later","timing":"downtime"}]}}"""));
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        JsonNode binding = block(result, 0).at("/rules/0/bindings/0");
        assertEquals("downtime.move", binding.path("event").asText());
        assertEquals("downtime", binding.path("surface").asText());
        assertEquals("rest", binding.at("/restMove/id").asText());
        assertEquals("any", binding.at("/restMove/kind").asText());
        RuleCompiler.Program program = program(result, 0);
        ObjectNode downtime = context(program, "{\"stress\":{\"marked\":2,\"capacity\":6}}", "{\"type\":\"downtime.move\"}");
        assertEquals(List.of("later", "rest"), available(program, downtime, "downtime"));
        assertEquals(List.of(), available(program, context(program, "{}", "{\"type\":\"action.request\"}"), "sheet"));
        assertEquals(1, run(program, downtime, "rest").contexts().getFirst().at("/actor/stress/marked").asInt());
        assertFalse(LegacyCatalogConverter.convert(catalog("""
                {"id":"x","name":"X","automation":{"actions":[{"id":"a","label":"A","timing":"combat"}]}}""")).diagnostics().isEmpty());
    }

    @Test
    void timedActionRestRestrictionsBecomeHostReadableMoveMetadata() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"bedroll","name":"Bedroll","automation":{"actions":[
                  {"id":"short-only","label":"Short","timing":"downtime","downtime":{"rest":"short"}},
                  {"id":"long-only","label":"Long","timing":"rest","downtime":{"rest":"long"}}]}}"""));
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        assertEquals("short", block(result, 0).at("/rules/0/bindings/0/restMove/kind").asText());
        assertEquals("long", block(result, 0).at("/rules/1/bindings/0/restMove/kind").asText());
        assertEquals("long-only", block(result, 0).at("/rules/1/bindings/0/restMove/id").asText());
        assertDoesNotThrow(() -> program(result, 0));
    }

    @Test
    void creationAllowancesAdjustTheCreationPlan() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"hardy","name":"Hardy","automation":{"creation":{"hp":1,"evasion":-1}}}"""));
        JsonNode rule = block(result, 0).at("/rules/0");
        assertEquals("creation.apply", rule.at("/bindings/0/event").asText());
        RuleCompiler.Program program = program(result, 0);
        JsonNode plan = run(program, context(program, "{}", """
                {"type":"creation.apply","creation":{"hp":6,"evasion":10,
                 "adjustable":{"hp":{"min":0,"max":20},"evasion":{"min":0,"max":20}}}}""")).contexts().getFirst().at("/event/creation");
        assertEquals(7, plan.path("hp").asInt(), plan.toString());
        assertEquals(9, plan.path("evasion").asInt());
        assertFalse(LegacyCatalogConverter.convert(catalog("""
                {"id":"x","name":"X","automation":{"creation":{"hp":0}}}""")).diagnostics().isEmpty());
    }

    @Test
    void actionTablesGateOutcomeStepsByTheRolledFace() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"sap","name":"Sap","automation":{"actions":[{"id":"drink","label":"Drink","table":{"dice":"1d6","outcomes":[
                  {"min":1,"text":"Gain a scar.","steps":[{"op":"gain","resource":"scars","amount":1}]},
                  {"min":2,"max":4,"text":"Clear 3 Stress.","steps":[{"op":"clear","resource":"stress","amount":3}]},
                  {"min":5,"max":6,"text":"Clear 2 HP.","steps":[{"op":"clear","resource":"hp","amount":2}]}]}}]}},
                {"id":"gap","name":"Gap","automation":{"actions":[{"id":"use","label":"Use","table":{"dice":"1d6","outcomes":[
                  {"min":1,"max":2,"text":"A"},{"min":4,"max":6,"text":"B"}]}}]}}"""));
        assertEquals(1, result.converted(), result.diagnostics().toString());
        assertTrue(result.diagnostics().toString().toLowerCase().contains("gap"), result.diagnostics().toString());
        JsonNode table = result.catalog().at("/entries/0/automation/rules/0/do/0");
        assertEquals("dice.table", table.path("op").asText());
        assertEquals(6, table.path("outcomes").size());
        assertEquals("Clear 3 Stress.", table.at("/outcomes/3").asText());

        RuleCompiler.Program program = RuleCatalog.compile(result.catalog().toString(), LegacyCatalogConverter.coreRecipes()).get(0).program();
        String actor = """
                {"hope":{"value":1,"max":1},"scars":0,"hp":{"marked":4,"capacity":6},"stress":{"marked":5,"capacity":6}}""";
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int seed = 0; seed < 300 && seen.size() < 6; seed++) {
            var resolution = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program,
                            context(program, actor, "{\"type\":\"action.request\"}"))),
                    List.of(new RuleRuntime.Intent("drink", "sheet", "fixture", 0, RuleJson.JSON.createObjectNode())),
                    new java.util.Random(seed));
            ObjectNode out = resolution.contexts().getFirst();
            String report = resolution.actions().getFirst().report();
            int face = Integer.parseInt(report.replaceAll("^Fixture: rolled (\\d+)\\..*$", "$1"));
            seen.add(face);
            assertEquals(face == 1 ? 1 : 0, out.at("/actor/scars").asInt(), report);
            assertEquals(face == 1 ? 0 : 1, out.at("/actor/hope/max").asInt(), report);
            assertEquals(face == 1 ? 0 : 1, out.at("/actor/hope/value").asInt(), report);
            assertEquals(face >= 2 && face <= 4 ? 2 : 5, out.at("/actor/stress/marked").asInt(), report);
            assertEquals(face >= 5 ? 2 : 4, out.at("/actor/hp/marked").asInt(), report);
        }
        assertEquals(6, seen.size());

        String full = """
                {"hope":{"value":0,"max":0},"scars":6,"hp":{"marked":0,"capacity":6},"stress":{"marked":0,"capacity":6}}""";
        for (int seed = 0; seed < 300; seed++) {
            var resolution = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program,
                            context(program, full, "{\"type\":\"action.request\"}"))),
                    List.of(new RuleRuntime.Intent("drink", "sheet", "fixture", 0, RuleJson.JSON.createObjectNode())),
                    new java.util.Random(seed));
            assertEquals(6, resolution.contexts().getFirst().at("/actor/scars").asInt());
            assertEquals(0, resolution.contexts().getFirst().at("/actor/hope/max").asInt());
        }
    }

    @Test
    void downtimeCraftingAndTablesBecomeGrantsAndTableRolls() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"recipe","name":"Recipe","automation":{"actions":[
                  {"id":"brew","label":"Brew","timing":"downtime","cost":{"stress":1},"downtime":{"craft":"vial-of-darksmoke"}}]}},
                {"id":"kit","name":"Kit","automation":{"actions":[
                  {"id":"mix","label":"Mix","timing":"downtime","downtime":{"table":{"expression":"1d6","repeat":"1d4+@instinct",
                    "outcomes":["Heal","Soothe","Empower","Enhance","Bolster","Invert"]}}}]}},
                {"id":"forager","name":"Forager","automation":{"actions":[
                  {"id":"forage","label":"Forage","timing":"downtime","downtime":{"table":{"expression":"1d6","forage":true,
                    "outcomes":["Food","Relic","Rune","Vial","Charm","Choose"]}}}]}}"""));
        assertEquals(3, result.converted(), result.diagnostics().toString());
        String actor = """
                {"id":"me","name":"Ash","stress":{"marked":0,"capacity":6},"traits":{"Instinct":2}}""";

        RuleCompiler.Program recipe = program(result, 0);
        ObjectNode brewed = run(recipe, context(recipe, actor, move("brew", "short", false, false)), "brew").contexts().getFirst();
        JsonNode grant = brewed.at("/outbox/0");
        assertEquals("inventory.grant", grant.path("kind").asText(), brewed.toString());
        assertEquals("consumables", grant.path("catalog").asText());
        assertEquals("vial-of-darksmoke", grant.path("entry").asText());
        assertEquals(5, grant.path("max").asInt());
        assertEquals("actor", grant.path("scope").asText());
        assertEquals(1, brewed.at("/actor/stress/marked").asInt());

        RuleCompiler.Program kit = program(result, 1);
        var mixed = run(kit, context(kit, actor, move("mix", "short", false, false)), "mix");
        String report = mixed.actions().getFirst().report();
        var parts = java.util.regex.Pattern.compile("Fixture: rolled ([1-6](?:, [1-6])*): (.+)\\.").matcher(report);
        assertTrue(parts.matches(), report);
        int faces = parts.group(1).split(", ").length;
        assertTrue(faces >= 3 && faces <= 6, report);
        assertEquals(faces, parts.group(2).split(", ").length, report);

        RuleCompiler.Program forager = program(result, 2);
        JsonNode rules = block(result, 2).path("rules");
        assertEquals(6, rules.size(), rules.toString());
        assertEquals("forage-forage", rules.at("/1/bindings/0/claimGroup").asText());
        boolean granted = false, offered = false;
        for (int seed = 0; seed < 200 && !(granted && offered); seed++) {
            var resolution = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(forager,
                            context(forager, actor, move("forage", "short", false, false)))),
                    List.of(new RuleRuntime.Intent("forage", "downtime", "fixture", 0, RuleJson.JSON.createObjectNode())),
                    new java.util.Random(seed));
            ObjectNode out = resolution.contexts().getFirst();
            JsonNode request = out.at("/outbox/0");
            String report2 = resolution.actions().getFirst().report();
            String[] items = {"foraged-food", "foraged-relic", "foraged-rune", "foraged-vial", "foraged-charm"};
            int face = Integer.parseInt(report2.replaceAll("^Fixture: rolled (\\d+):.*$", "$1"));
            if (face < 6) {
                granted = true;
                assertEquals(items[face - 1], request.path("entry").asText(), out.toString());
                assertEquals("party", request.path("scope").asText());
                assertEquals("foraged", request.path("tag").asText());
            } else {
                offered = true;
                assertEquals("forage-forage", request.path("group").asText(), out.toString());
            }
        }
        assertTrue(granted && offered);
        ObjectNode claim = context(forager, actor, "{\"type\":\"claim.accept\",\"id\":\"c\"}");
        var recipients = List.of(new RuleDispatcher.RecipientCandidate("me", (ObjectNode) claim.path("actor").deepCopy(), 0));
        var claimed = RuleDispatcher.executeClaims(List.of(new RuleDispatcher.Candidate(forager, claim)),
                List.of(claimIntent("forage-foraged-rune", "me", null)), recipients,
                new RuleDispatcher.ClaimLedger(0, java.util.Set.of(), Map.of("forage-forage", 1)), RandomGenerator.getDefault());
        assertEquals("Ash foraged one Foraged Rune.", claimed.actions().getFirst().action().report());
        assertFalse(LegacyCatalogConverter.convert(catalog("""
                {"id":"x","name":"X","automation":{"actions":[{"id":"a","label":"A","timing":"downtime",
                  "downtime":{"table":{"expression":"1d4","outcomes":["A","B"]}}}]}}""")).diagnostics().isEmpty());
    }

    @Test
    void sentRollsBecomeHostResolvedPlansWithComputedCountsAndEvasionBase() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"guard","name":"Guard","automation":{"actions":[
                  {"id":"dodge","label":"Dodge","sendRoll":{"dice":"@Agilityd6kh1","base":"evasion","label":"Dodge: Evasion"}},
                  {"id":"keen","label":"Keen","sendRoll":{"dice":"@tier","base":"evasion","label":"Keen"}},
                  {"id":"commune","label":"Commune","sendRoll":{"dice":"@spellcastd6","label":"Commune"}}],
                 "conditionalRollTriggers":[{"id":"stress","label":"Stress","successIfKnown":true,
                  "sendRoll":{"dice":"@total_proficiencyd4kh1","label":"Target Stress"},"report":{"text":"The target marks {result} Stress."}}]}}"""));
        JsonNode block = block(result, 0);
        assertEquals("Dodge: Evasion: {result.sentRoll.total}.", block.at("/rules/0/ui/report").asText());
        assertEquals("Target Stress: {result.sentRoll.total}. The target marks {result.sentRoll.total} Stress.",
                block.at("/rules/3/ui/report").asText());
        RuleCompiler.Program program = program(result, 0);
        String actor = "{\"stats\":{\"evasion\":10,\"tier\":3,\"spellcast\":{\"base\":0},\"trait\":{\"Agility\":2}}}";
        ObjectNode context = context(program, actor, "{\"type\":\"action.request\"}");
        JsonNode dodge = run(program, context, "dodge").actions().getFirst().diceRolls().getFirst();
        assertEquals(10, dodge.at("/contributions/0/value").asInt());
        assertEquals(2, dodge.at("/contributions/1/dice").size());
        assertEquals(1, java.util.stream.StreamSupport.stream(dodge.at("/contributions/1/dice").spliterator(), false)
                .filter(die -> die.path("kept").asBoolean()).count());
        JsonNode keen = run(program, context, "keen").actions().getFirst().diceRolls().getFirst();
        assertEquals(13, keen.path("total").asInt());
        assertFalse(available(program, context, "sheet").contains("commune"));
        ObjectNode none = context(program, "{\"stats\":{\"evasion\":10,\"trait\":{\"Agility\":-1}}}", "{\"type\":\"action.request\"}");
        JsonNode empty = run(program, none, "dodge").actions().getFirst().diceRolls().getFirst();
        assertEquals(10, empty.path("total").asInt());
        assertFalse(LegacyCatalogConverter.convert(catalog("""
                {"id":"x","name":"X","automation":{"actions":[{"id":"a","label":"A","sendRoll":{"dice":"@unknownd6","label":"A"}}]}}""")).diagnostics().isEmpty());
        assertFalse(LegacyCatalogConverter.convert(catalog("""
                {"id":"x","name":"X","automation":{"actions":[{"id":"a","label":"A","sendRoll":{"dice":"1d6","base":"armor","label":"A"}}]}}""")).diagnostics().isEmpty());
    }

    @Test
    void clearAllPoolStepsSpendTheCurrentActorPoolBalance() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"tokens","name":"Tokens","automation":{"resources":[{"id":"tokens","max":5,"initial":3}],
                  "actions":[{"id":"clear","label":"Clear tokens","steps":[{"op":"clear","resource":"tokens","amount":"all"}]}]}}"""));
        JsonNode rule = block(result, 0).path("rules").get(0);
        assertEquals("actor.pools.tokens.value", rule.at("/do/0/amount/read").asText());

        RuleCompiler.Program program = program(result, 0);
        ObjectNode context = context(program, "{\"pools\":{\"tokens\":{\"value\":3,\"max\":5}}}",
                "{\"type\":\"action.request\"}");
        ObjectNode after = run(program, context, "clear").contexts().getFirst();
        assertEquals(0, after.at("/actor/pools/tokens/value").asInt());
    }

    @Test
    void weaponDamageHandlingBecomesAutomaticEditsOfTheSourceDamageDice() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"heavy","name":"Heavy","automation":{"damage":{"extraDice":1,"dropLowest":1},
                  "alternateProfiles":[{"label":"Versatile","trait":"Strength","range":"Very Close","damage":"1d8+3","damageType":"phys"}]}},
                {"id":"brutal","name":"Brutal","automation":{"damage":{"extraDice":0,"dropLowest":0,"explode":"once","label":"Brutal"}}},
                {"id":"serrated","name":"Serrated","automation":{"damage":{"onesAs":8,"label":"Serrated"}}},
                {"id":"rampage","name":"Rampage","automation":{"damage":{"onesBonus":10,"proficiencyOnly":true,"label":"Rampage"}}},
                {"id":"recursive","name":"Recursive","automation":{"damage":{"explode":"repeat","label":"Recursive"}}}"""));
        JsonNode heavy = block(result, 0);
        assertEquals("automatic", heavy.at("/rules/0/bindings/0/mode").asText());
        assertEquals("roll.finished", heavy.at("/rules/0/bindings/0/event").asText());
        assertEquals("{\"id\":\"versatile\",\"label\":\"Versatile\",\"trait\":\"Strength\",\"range\":\"veryClose\","
                + "\"damage\":{\"sides\":8,\"flat\":3,\"type\":\"physical\"}}", heavy.at("/profiles/0").toString());
        assertEquals("Versatile", program(result, 0).profiles().at("/0/label").asText());

        assertEquals(8, weaponRoll(program(result, 0), "{\"weaponSource\":\"fixture\"}", 10,
                StructuredRollTest.faces(1), 3, 5).path("total").asInt(), "The added die is the lowest and is dropped.");
        assertEquals(14, weaponRoll(program(result, 1), "{\"weaponSource\":\"fixture\"}", 6,
                StructuredRollTest.faces(6), 6, 2).path("total").asInt(), "Brutal dice explode only once.");
        assertEquals(6 + 6 + 6 + 2, weaponRoll(program(result, 4), "{\"weaponSource\":\"fixture\"}", 6,
                StructuredRollTest.faces(6, 6, 2), 6).path("total").asInt(), "Recursive dice keep exploding.");
        assertEquals(1 + 4 + 7, weaponRoll(program(result, 2), "{\"weaponSource\":\"fixture\"}", 8,
                StructuredRollTest.noRandom(), 1, 4).path("total").asInt());
        assertEquals(5 + 4 + 9, weaponRoll(program(result, 3), "{\"weaponSource\":\"fixture\",\"fixedDice\":false}", 6,
                StructuredRollTest.faces(4, 9), 1, 1, 3).path("total").asInt());
        assertEquals(5, weaponRoll(program(result, 3), "{\"weaponSource\":\"fixture\",\"fixedDice\":true}", 6,
                StructuredRollTest.noRandom(), 1, 1, 3).path("total").asInt(), "Fixed dice ignore proficiency-only handling.");
        assertEquals(3, weaponRoll(program(result, 2), "{\"weaponSource\":\"other\"}", 8,
                StructuredRollTest.noRandom(), 1, 2).path("total").asInt(), "Other weapons are untouched.");

        RuleCompiler.Program mixedProgram = program(result, 0);
        ObjectNode mixed = (ObjectNode) RuleJson.parse("""
                {"id":"mixed","metadata":{"weaponBaseContributions":["eight","six"]},"contributions":[
                 {"id":"eight","kind":"dice","role":"damage","sourceId":"%s","label":"Eight","dice":[
                   {"id":"e1","sides":8,"face":2,"kept":true,"origin":"rolled"},
                   {"id":"e2","sides":8,"face":7,"kept":true,"origin":"rolled"}]},
                 {"id":"six","kind":"dice","role":"damage","sourceId":"%s","label":"Six","dice":[
                   {"id":"s1","sides":6,"face":3,"kept":true,"origin":"rolled"},
                   {"id":"s2","sides":6,"face":6,"kept":true,"origin":"rolled"}]},
                 {"id":"bonus","kind":"dice","role":"damage","sourceId":"%s","label":"Bonus","dice":[
                   {"id":"b1","sides":4,"face":1,"kept":true,"origin":"rolled"}]}]}"""
                .formatted(mixedProgram.sourceId(), mixedProgram.sourceId(), mixedProgram.sourceId()));
        ObjectNode event = RuleJson.JSON.createObjectNode().put("type", "roll.finished");
        event.putObject("check").put("kind", "damage").put("weaponSource", "fixture");
        event.set("roll", StructuredRoll.validate(mixed));
        ObjectNode context = context(mixedProgram, "{}", event.toString());
        JsonNode edited = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(mixedProgram, context)), List.of(),
                StructuredRollTest.faces(4, 5)).contexts().getFirst().at("/event/roll");
        assertEquals(4 + 7 + 5 + 6 + 1, edited.path("total").intValue());
        assertEquals(1, edited.at("/contributions/2/dice").size(), "Bonus dice from the same source are not weapon base dice.");
        assertEquals(2, edited.path("excludedDice").size(), "Drop the lowest die in each base pool.");
    }

    @Test
    void weaponDamageAndProfilesRejectUnsupportedShapes() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"bad","name":"Bad","automation":{"damage":{"explode":"twice","onesAs":1},
                  "alternateProfiles":[{"label":"Odd","range":"Distant","damage":"lots"}]}}"""));
        assertEquals(0, result.converted());
        String problems = result.diagnostics().toString();
        assertTrue(problems.contains("/damage/explode"), problems);
        assertTrue(problems.contains("/damage/onesAs"), problems);
        assertTrue(problems.contains("/alternateProfiles/0/range"), problems);
        assertTrue(problems.contains("/alternateProfiles/0/damage"), problems);
    }

    private static JsonNode weaponRoll(RuleCompiler.Program program, String check, int sides, java.util.Random random,
                                       int... faces) throws Exception {
        ArrayNode dice = RuleJson.JSON.createArrayNode();
        for (int i = 0; i < faces.length; i++)
            dice.addObject().put("id", "w" + i).put("sides", sides).put("face", faces[i]).put("kept", true).put("origin", "rolled");
        ObjectNode roll = (ObjectNode) RuleJson.parse("""
                {"id":"hit","metadata":{"weaponBaseContributions":["weapon"]},"contributions":[{"id":"weapon","kind":"dice","role":"damage","sourceId":"%s","label":"Weapon","dice":[]},
                  {"id":"bonus","kind":"flat","role":"modifier","sourceId":"core","label":"Bonus","value":0}]}""".formatted(program.sourceId()));
        ((ObjectNode) roll.at("/contributions/0")).set("dice", dice);
        ObjectNode checkNode = (ObjectNode) RuleJson.parse(check);
        checkNode.put("kind", "damage");
        ObjectNode event = RuleJson.JSON.createObjectNode().put("type", "roll.finished");
        event.set("check", checkNode);
        event.set("roll", StructuredRoll.validate(roll));
        ObjectNode context = context(program, "{}", event.toString());
        var resolution = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, context)), List.of(), random);
        return resolution.contexts().getFirst().at("/event/roll");
    }

    @Test
    void rollOptionsBecomeFilteredRollContributionsThatChain() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"rogue","name":"Rogue","automation":{"rollOptions":[
                  {"id":"aim","label":"Aim {+2}","roll":"attack","weapon":"primary","value":2,"dice":"1d4","exclusive":"Focus","defaultOn":true},
                  {"id":"rage","label":"Rage","roll":"any","value":1,"requiresEffect":"Raging","cost":{"hope":1}},
                  {"id":"calm","label":"Calm","roll":"trait:Instinct","advantage":"advantage","excludesEffect":"Raging"}]}}"""));
        JsonNode rules = block(result, 0).path("rules");
        assertEquals("option", rules.at("/0/bindings/0/mode").asText());
        assertTrue(rules.at("/0/bindings/0/defaultOn").asBoolean());
        assertEquals("focus", rules.at("/0/bindings/0/exclusiveGroup").asText());
        assertEquals("Aim {{+2}}", rules.at("/0/ui/label").asText());
        assertEquals("result.roll1", rules.at("/0/do/1/from/read").asText());
        assertEquals("roll.publish", rules.at("/0/do/2/op").asText());
        assertEquals("advantage", rules.at("/2/do/0/contribution/role").asText());

        RuleCompiler.Program program = program(result, 0);
        String raging = "{\"hope\":{\"value\":2,\"max\":6},\"effects\":[{\"id\":\"other/raging/1\",\"label\":\"Raging\"}]}";
        ObjectNode attack = context(program, raging, """
                {"type":"roll.prepare","check":{"kind":"attack","weapon":"primary","trait":null},"roll":%s}""".formatted(ROLL));
        assertEquals(List.of("aim", "rage"), available(program, attack, "roll"));
        ObjectNode secondary = context(program, "{\"effects\":[]}", """
                {"type":"roll.prepare","check":{"kind":"attack","weapon":"secondary","trait":"Instinct"},"roll":%s}""".formatted(ROLL));
        assertEquals(List.of("calm"), available(program, secondary, "roll"));

        var resolution = run(program, attack, "aim", "rage");
        JsonNode roll = resolution.contexts().getFirst().at("/event/roll");
        assertEquals(4, roll.path("contributions").size());
        assertEquals(1, resolution.contexts().getFirst().at("/actor/hope/value").asInt());
    }

    @Test
    void rollOptionQuantitiesResizedHopeDiceAndProficiencyDice() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"surge","name":"Surge","automation":{"rollOptions":[
                  {"id":"pour","label":"Pour Hope","roll":"damage","dice":"1d6","value":1,"diceResource":"hope-dice","cost":{"hope":1},
                   "quantity":{"resource":"hope","label":"Hope","max":3},"condition":{"unmarkedHp":{"max":2}}},
                  {"id":"vast","label":"Vast Hope","roll":"action","hopeDie":20},
                  {"id":"heavy","label":"Heavy","roll":"damage","proficiency":1},
                  {"id":"mark","label":"Mark","roll":"attack","proficiency":2}]}}"""));
        JsonNode rules = block(result, 0).path("rules");
        assertEquals("hope-dice", rules.at("/0/do/1/contribution/role").asText());
        assertEquals("input.quantity", rules.at("/0/costs/0/amount/multiply/1/read").asText());
        RuleCompiler.Program program = program(result, 0);

        String damageRoll = """
                {"id":"dmg","metadata":{"weaponBaseContributions":["weapon"]},"contributions":[{"id":"weapon","kind":"dice","role":"damage","sourceId":"weapon:axe","label":"Axe",
                  "dice":[{"id":"w1","sides":8,"face":3,"kept":true,"origin":"rolled"}]}]}""";
        String wounded = "{\"hope\":{\"value\":5,\"max\":6},\"hp\":{\"marked\":5,\"capacity\":6},\"effects\":[]}";
        ObjectNode damage = context(program, wounded, """
                {"type":"roll.prepare","check":{"kind":"damage","weaponSourceId":"weapon:axe","fixedDice":false},"roll":%s}""".formatted(damageRoll));
        assertEquals(List.of("heavy", "pour"), available(program, damage, "roll"));
        ObjectNode healthy = context(program, "{\"hope\":{\"value\":5,\"max\":6},\"hp\":{\"marked\":0,\"capacity\":6},\"effects\":[]}",
                damage.path("event").toString());
        assertFalse(available(program, healthy, "roll").contains("pour"), "Too much unmarked HP.");

        var intent = new RuleRuntime.Intent("pour", "use", "fixture", 0, (ObjectNode) RuleJson.parse("{\"quantity\":3}"));
        var poured = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, damage)), List.of(intent), StructuredRollTest.faces(1, 2, 3));
        ObjectNode after = poured.contexts().getFirst();
        assertEquals(2, after.at("/actor/hope/value").asInt());
        JsonNode roll = after.at("/event/roll");
        assertEquals(3 + 3 + (1 + 2 + 3), roll.path("total").asInt());
        assertThrows(Exception.class, () -> RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, damage)),
                List.of(new RuleRuntime.Intent("pour", "use", "fixture", 0, (ObjectNode) RuleJson.parse("{\"quantity\":4}"))),
                RandomGenerator.getDefault()));

        JsonNode heavy = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, damage)),
                List.of(new RuleRuntime.Intent("heavy", "use", "fixture", 0, RuleJson.JSON.createObjectNode())),
                StructuredRollTest.faces(7)).contexts().getFirst().at("/event/roll");
        assertEquals(3 + 7, heavy.path("total").asInt());
        ObjectNode fixed = context(program, wounded, """
                {"type":"roll.prepare","check":{"kind":"damage","weaponSourceId":"weapon:axe","fixedDice":true},"roll":%s}""".formatted(damageRoll));
        assertFalse(available(program, fixed, "roll").contains("heavy"));

        ObjectNode action = context(program, wounded, """
                {"type":"roll.prepare","check":{"kind":"action","trait":"Agility"},"roll":%s}""".formatted("""
                {"id":"act","contributions":[{"id":"hope","kind":"dice","role":"hope","sourceId":"core","label":"Hope",
                  "dice":[{"id":"h","sides":12,"face":4,"kept":true,"origin":"rolled"}]},
                 {"id":"fear","kind":"dice","role":"fear","sourceId":"core","label":"Fear",
                  "dice":[{"id":"f","sides":12,"face":5,"kept":true,"origin":"rolled"}]}]}"""));
        JsonNode vast = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, action)),
                List.of(new RuleRuntime.Intent("vast", "use", "fixture", 0, RuleJson.JSON.createObjectNode())),
                StructuredRollTest.faces(18)).contexts().getFirst().at("/event/roll");
        assertEquals(20, vast.at("/contributions/0/dice/0/sides").asInt());
        assertEquals(18, vast.at("/contributions/0/dice/0/face").asInt());
        assertEquals(12, vast.at("/contributions/1/dice/0/sides").asInt());

        ObjectNode attack = context(program, wounded, """
                {"type":"roll.prepare","check":{"kind":"attack","weaponSourceId":"weapon:axe"},"roll":%s}""".formatted(ROLL));
        ObjectNode marked = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, attack)),
                List.of(new RuleRuntime.Intent("mark", "use", "fixture", 0, RuleJson.JSON.createObjectNode())),
                RandomGenerator.getDefault()).contexts().getFirst();
        assertEquals(1, marked.at("/actor/effects").size());
        ObjectNode finished = marked.deepCopy();
        finished.set("event", RuleJson.parse("""
                {"type":"roll.finished","check":{"kind":"damage","weaponSourceId":"weapon:axe","fixedDice":false},"roll":%s}""".formatted(damageRoll)));
        ObjectNode carried = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, finished)), List.of(),
                StructuredRollTest.faces(4, 5)).contexts().getFirst();
        assertEquals(3 + 4 + 5, carried.at("/event/roll/total").asInt());
        assertEquals(0, carried.at("/actor/effects").size(), "The pending bonus is spent.");
        ObjectNode missed = marked.deepCopy();
        missed.set("event", RuleJson.parse("""
                {"type":"roll.finished","check":{"kind":"attack","outcome":"failure","weaponSourceId":"weapon:axe"},"roll":%s}""".formatted(ROLL)));
        assertEquals(0, RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, missed)), List.of(),
                StructuredRollTest.noRandom()).contexts().getFirst().at("/actor/effects").size(), "A miss discards the bonus.");
    }

    @Test
    void rollTriggersRerollAddStepsAndRespectCriticalAndWeaponFilters() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"seer","name":"Seer","automation":{"uses":{"max":2,"per":"session"},"rollTriggers":[
                  {"id":"fate","label":"Reroll Hope","reroll":"hope","when":"fear","uses":"source"},
                  {"id":"strike","label":"Strike","criticalOnly":true,"weaponAttack":true,"damage":{"dice":"1d6+1","fixed":true},
                   "steps":[{"op":"gain","resource":"hope","amount":"@proficiency"}],"usesSource":false},
                  {"id":"luck","label":"Lucky","when":"hopeDie","die":6,"outcome":"success","report":"Lucky!"}]}}"""));
        JsonNode rules = block(result, 0).path("rules");
        assertEquals("uses", rules.at("/0/limitRef").asText());
        assertEquals("roll.reroll", rules.at("/0/do/1/op").asText());
        assertFalse(rules.get(1).has("limitRef"));
        assertEquals("event.check.critical", rules.at("/1/when/and/0/eq/0/read").asText());
        JsonNode weaponFilter = rules.at("/1/when/and/1");
        for (String absent : List.of("{}", "{\"weapon\":null}"))
            assertFalse(RuleExpression.evaluate(weaponFilter, RuleJson.parse("{\"event\":{\"check\":" + absent + "}}")).booleanValue());
        assertTrue(RuleExpression.evaluate(weaponFilter, RuleJson.parse("{\"event\":{\"check\":{\"weapon\":\"primary\"}}}")).booleanValue());
        assertEquals("actor.proficiency.value", rules.at("/1/do/1/amount/read").asText());
        assertEquals("event.check.hopeDie", rules.at("/2/when/and/0/eq/0/read").asText());
        assertEquals("Lucky!", rules.at("/2/ui/report").asText());
        assertEquals("Damage total: {result.triggerDamage.total}.", rules.at("/1/ui/report").asText());

        RuleCompiler.Program program = program(result, 0);
        ObjectNode critical = context(program, "{\"hope\":{\"value\":0,\"max\":6},\"proficiency\":{\"value\":2}}", """
                {"type":"roll.finished","check":{"kind":"attack","weapon":"primary","critical":true,"dominant":"hope","hopeDie":6,"outcome":"success"},"roll":%s}"""
                .formatted(ROLL));
        assertEquals(List.of("luck", "strike"), available(program, critical, "roll"));
        var resolution = run(program, critical, "strike");
        assertEquals(2, resolution.contexts().getFirst().at("/actor/hope/value").asInt());
        JsonNode damage = resolution.actions().getFirst().diceRolls().getFirst();
        assertEquals(1, damage.at("/contributions/0/dice").size());
        assertEquals(1, damage.at("/contributions/1/value").asInt());
        assertEquals(6, damage.at("/contributions/2/value").asInt());
        assertEquals(1, resolution.contexts().getFirst().at("/event/roll/contributions").size());
    }

    @Test
    void conditionalRollTriggersConvertOutcomeGatesAndExclusiveChoices() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"striker","name":"Striker","automation":{"uses":{"max":1,"per":"rest"},"conditionalRollTriggers":[
                  {"id":"hope","label":"Hope damage","on":"card:Strike","when":"hopeOrCritical","successIfKnown":true,"damage":{"dice":"1d6"}},
                  {"id":"success","label":"Known success","on":"card:Strike","when":"success","difficulty":12},
                  {"id":"fail-hope","label":"Pay Hope","on":"card:Strike","when":"failure","exclusiveRollGroup":"failure-payment","cost":{"hope":1}},
                  {"id":"fail-stress","label":"Pay Stress","on":"card:Strike","when":"failure","exclusiveRollGroup":"failure-payment","steps":[{"op":"mark","resource":"stress","amount":1}]},
                  {"id":"trait","label":"Any trait","on":"trait","when":"always"}]}}"""));
        JsonNode rules = block(result, 0).path("rules");
        assertEquals("roll.finished", rules.at("/0/bindings/0/event").asText());
        assertEquals("card:Strike", rules.at("/0/when/and/0/eq/1").asText());
        assertEquals("failure-payment", rules.at("/2/bindings/0/exclusiveGroup").asText());
        assertEquals("event.check.total", rules.at("/1/when/and/1/and/1/or/1/gte/0/read").asText());

        RuleCompiler.Program program = program(result, 0);
        String actor = "{\"hope\":{\"value\":2,\"max\":6},\"stress\":{\"marked\":0,\"capacity\":6}}";
        String roll = "{\"type\":\"roll.finished\",\"check\":{\"kind\":\"action\",\"origin\":\"card:Strike\",\"trait\":\"Agility\",\"dominant\":\"hope\",\"critical\":false,\"outcome\":null,\"total\":10,\"difficulty\":null},\"roll\":" + ROLL + "}";
        ObjectNode unknownDifficulty = context(program, actor, roll);
        assertEquals(List.of("hope", "trait"), available(program, unknownDifficulty, "roll"));

        ObjectNode failure = context(program, actor, roll.replace("\"outcome\":null", "\"outcome\":\"failure\""));
        assertEquals(List.of("fail-hope", "fail-stress", "trait"), available(program, failure, "roll"));
        assertThrows(IllegalArgumentException.class, () -> run(program, failure, "fail-hope", "fail-stress"));

        ObjectNode success = context(program, actor, roll.replace("\"total\":10,\"difficulty\":null", "\"total\":12,\"difficulty\":12"));
        assertEquals(List.of("hope", "success", "trait"), available(program, success, "roll"));
    }

    @Test
    void ruleUseLimitsAndItemConsumptionBecomeLimitsAndCosts() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"kit","name":"Kit","automation":{"uses":{"max":3,"per":"session"},"rollOptions":[
                  {"id":"potion","label":"Drink","roll":"trait:Agility","value":1,"consumeItem":true},
                  {"id":"sprite","label":"Sprite","roll":"attack","advantage":"advantage","uses":{"max":1,"per":"shortRest"}}]}}"""));
        JsonNode block = block(result, 0);
        assertEquals("inventory.consume", block.at("/rules/0/costs/0/op").asText());
        assertEquals("uses", block.at("/rules/0/limitRef").asText());
        assertEquals("rule-sprite", block.at("/rules/1/limitRef").asText());
        assertEquals(1, block.at("/limits/rule-sprite/max").asInt());
        assertEquals(3, block.at("/limits/uses/max").asInt());
        program(result, 0);
    }

    @Test
    void conditionalDamageTriggerUsesProjectedSpellcastDiceCount() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"spell","name":"Spell","automation":{"conditionalRollTriggers":[
                  {"id":"damage","label":"Spell damage","on":"card:Strike","damage":{"dice":"2d6+3","count":"spellcast","type":"magic"}}]}}"""));
        JsonNode rule = block(result, 0).path("rules").get(0);
        assertEquals("actor.spellcastTrait.value", rule.at("/do/0/plan/literal/contributions/0/count/multiply/0/max/1/read").asText());

        RuleCompiler.Program program = program(result, 0);
        String rollEvent = "{\"type\":\"roll.finished\",\"check\":{\"kind\":\"action\",\"origin\":\"card:Strike\",\"trait\":\"Agility\",\"dominant\":\"hope\",\"critical\":false,\"outcome\":\"success\",\"total\":12,\"difficulty\":12},\"roll\":" + ROLL + "}";
        ObjectNode withoutSpellcast = context(program, "{}", rollEvent);
        assertTrue(available(program, withoutSpellcast, "roll").isEmpty());
        ObjectNode spellcast = context(program,
                "{\"spellcastTrait\":{\"name\":\"Instinct\",\"value\":2}}", rollEvent);
        var resultWithDamage = run(program, spellcast, "damage");
        JsonNode damage = resultWithDamage.actions().getFirst().diceRolls().getFirst();
        assertEquals(4, damage.at("/contributions/0/dice").size());
        assertEquals(3, damage.at("/contributions/1/value").asInt());
        assertEquals(0, damage.at("/contributions/2/value").asInt());
        assertEquals("magic", damage.at("/metadata/damageType").asText());
    }

    @Test
    void mitigationCoversSeverityBandsOptionsAndGrantedEffects() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"guard","name":"Guard","automation":{"mitigation":[
                  {"id":"ward","label":"Ward","halve":true,"when":{"severity":["severe","massive"],"type":"magic"},"defaultOn":true,
                   "effect":{"name":"Warded","until":"scene","text":"Protected."}},
                  {"id":"brace","label":"Brace","reduce":"@level","when":{"hpUnmarkedMax":2},"cost":{"stress":1}},
                  {"id":"skin","label":"Stone Skin","negate":true,"passive":true,"when":{"severity":["minor"]}}]}}"""));
        JsonNode block = block(result, 0);
        assertEquals("option", block.at("/rules/0/bindings/0/mode").asText());
        assertEquals("effect.start", block.at("/rules/0/do/2/op").asText());
        assertEquals("Warded", block.at("/effects/warded/label").asText());
        assertEquals("automatic", block.at("/rules/2/bindings/0/mode").asText());

        RuleCompiler.Program program = program(result, 0);
        String actor = "{\"level\":3,\"hp\":{\"marked\":4,\"capacity\":6},\"stress\":{\"marked\":0,\"capacity\":6},\"effects\":[]}";
        ObjectNode severe = context(program, actor, """
                {"type":"damage.prepare","damageType":"magic","thresholds":{"major":5,"severe":10},
                 "damage":{"id":"hit","stage":"preSeverity","baseAmount":12,"amount":12,"adjustments":[]}}""");
        assertEquals(List.of("brace", "ward"), available(program, severe, "damage"));
        ObjectNode warded = run(program, severe, "ward").contexts().getFirst();
        assertEquals(6, warded.at("/event/damage/amount").asInt());
        assertEquals(1, warded.at("/actor/effects").size());
        ObjectNode braced = run(program, severe, "brace").contexts().getFirst();
        assertEquals(9, braced.at("/event/damage/amount").asInt());
        assertEquals(1, braced.at("/actor/stress/marked").asInt());

        ObjectNode minor = context(program, actor, """
                {"type":"damage.prepare","damageType":"physical","thresholds":{"major":5,"severe":10},
                 "damage":{"id":"graze","stage":"preSeverity","baseAmount":3,"amount":3,"adjustments":[]}}""");
        assertEquals(0, run(program, minor).contexts().getFirst().at("/event/damage/amount").asInt());
    }

    @Test
    void mitigationIncreasesRecoveriesQuantitiesAndTableReactionsConvert() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"edge","name":"Edge","automation":{"resources":[{"id":"thorns","label":"Thorns","initial":0,"resetOn":"rest"}],"mitigation":[
                  {"id":"frail","label":"Frail","passive":true,"when":{"type":"magic"},"double":true},
                  {"id":"recover","label":"Not Done","passive":true,"when":{"severity":["severe"]},"finalSevere":true,"recoveryChoice":true},
                  {"id":"reflect","label":"Reflect","when":{"markingHp":true},"cost":{"stress":1},"reflectHp":true},
                  {"id":"braced","label":"Braced: attacker marks a Hit Point","cost":{"stress":2}},
                  {"id":"thorns","label":"Thorns","quantity":true,"roll":{"count":"quantity","sides":6},"cost":{"thorns":1}}]}}"""));
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        JsonNode block = block(result, 0);
        assertEquals("button", block.at("/rules/1/bindings/0/mode").asText());
        assertEquals("{source.name}: the attacker marks {event.applied.hitPoints} Hit Points.", block.at("/rules/2/ui/report").asText());
        assertEquals("Braced: attacker marks a Hit Point.", block.at("/rules/3/ui/report").asText());
        RuleCompiler.Program program = program(result, 0);

        String actor = "{\"hope\":{\"value\":1,\"max\":6},\"stress\":{\"marked\":3,\"capacity\":6},\"pools\":{\"thorns\":{\"value\":3}},\"effects\":[]}";
        ObjectNode magic = context(program, actor, """
                {"type":"damage.prepare","damageType":"magic","thresholds":{"major":5,"severe":10},
                 "damage":{"id":"hit","stage":"preSeverity","baseAmount":7,"amount":7,"adjustments":[]}}""");
        ObjectNode doubled = run(program, magic).contexts().getFirst();
        assertEquals(14, doubled.at("/event/damage/amount").asInt(), doubled.toString());
        assertTrue(doubled.at("/event/damage/adjustments/0/increase").asBoolean());

        var thorns = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, magic)),
                List.of(new RuleRuntime.Intent("thorns", "use", "fixture", 0, (ObjectNode) RuleJson.parse("{\"quantity\":2}"))),
                StructuredRollTest.faces(4, 4));
        ObjectNode spent = thorns.contexts().getFirst();
        assertEquals(1, spent.at("/actor/pools/thorns/value").asInt(), spent.toString());
        assertEquals(6, spent.at("/event/damage/amount").asInt(), "Doubled to 14, then two d6 of 4 each.");

        ObjectNode applied = context(program, actor, "{\"type\":\"damage.applied\",\"applied\":{\"severity\":3,\"incoming\":3,\"hitPoints\":3,\"amount\":12}}");
        for (String[] pick : new String[][]{{"hope", "/actor/hope/value", "2"}, {"stress", "/actor/stress/marked", "2"}}) {
            var recovered = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, applied)),
                    List.of(new RuleRuntime.Intent("recover", "use", "fixture", 0, (ObjectNode) RuleJson.parse("{\"recovery\":\"" + pick[0] + "\"}"))),
                    RandomGenerator.getDefault());
            ObjectNode after = recovered.contexts().getFirst();
            assertEquals(Integer.parseInt(pick[2]), after.at(pick[1]).asInt(), after.toString());
        }
    }

    @Test
    void tokenEffectsEndWhenTheirTokensRunOut() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"skin","name":"Skin","automation":{"resources":[{"id":"thorns","label":"Thorns","initial":0,"resetOn":"rest"}],
                  "actions":[{"id":"cast","label":"Sprout","steps":[{"op":"gain","resource":"thorns","amount":2},
                    {"op":"effect","effect":{"name":"Thorn Skin","until":"rest","tokenResource":"thorns"}}]}],
                  "mitigation":[{"id":"thorns","label":"Thorns","quantity":true,"roll":{"count":"quantity","sides":6},"cost":{"thorns":1}}]}}"""));
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        assertEquals("{\"lte\":[{\"read\":\"actor.pools.thorns.value\"},0]}", block(result, 0).at("/effects/thorn-skin/endWhen").toString());
        RuleCompiler.Program program = program(result, 0);
        ObjectNode cast = run(program, context(program, "{\"effects\":[]}", "{\"type\":\"action.request\"}"), "cast").contexts().getFirst();
        assertEquals(1, cast.at("/actor/effects").size(), cast.toString());

        String damage = """
                {"type":"damage.prepare","damageType":"physical","thresholds":{"major":5,"severe":10},
                 "damage":{"id":"hit","stage":"preSeverity","baseAmount":9,"amount":9,"adjustments":[]}}""";
        ObjectNode actor = cast;
        for (int remaining = 1; remaining >= 0; remaining--) {
            ObjectNode hit = context(program, actor.get("actor").toString(), damage);
            actor = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, hit)),
                    List.of(new RuleRuntime.Intent("thorns", "use", "fixture", 0, (ObjectNode) RuleJson.parse("{\"quantity\":1}"))),
                    StructuredRollTest.faces(3)).contexts().getFirst();
            assertEquals(remaining, actor.at("/actor/pools/thorns/value").asInt(), actor.toString());
            assertEquals(remaining, actor.at("/actor/effects").size(), "The effect ends with its last token.");
        }

        var unknown = LegacyCatalogConverter.convert(catalog("""
                {"id":"bad","name":"Bad","automation":{"actions":[{"id":"cast","label":"Cast","steps":[
                  {"op":"effect","effect":{"name":"Ward","tokenResource":"missing"}}]}]}}"""));
        assertTrue(unknown.diagnostics().toString().contains("Unknown token resource"), unknown.diagnostics().toString());
    }

    @Test
    void effectGrantedRulesRequireTheirEffectAndCanEndIt() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"rager","name":"Rager","automation":{"actions":[{"id":"rage","label":"Rage","steps":[{"op":"effect","effect":{
                  "name":"Raging","until":"scene",
                  "actions":[{"id":"calm","label":"Calm Down","endEffect":"Raging"}],
                  "rollOptions":[{"id":"fury","label":"Fury","roll":"damage","dice":"1d6"}],
                  "rollTriggers":[{"id":"roar","label":"Roar","on":"attack","steps":[{"op":"gain","resource":"hope","amount":1}]}],
                  "mitigation":[{"id":"tough","label":"Tough","reduce":2}]}}]}]}}"""));
        JsonNode rules = block(result, 0).path("rules");
        assertEquals(5, rules.size());
        for (int i = 1; i < 5; i++) assertTrue(rules.get(i).toString().contains("\"Raging\""), rules.get(i).toString());
        assertEquals("effect.end", rules.at("/1/do/0/op").asText());
        assertEquals("raging", rules.at("/1/do/0/effect").asText());

        RuleCompiler.Program program = program(result, 0);
        ObjectNode calm = context(program, "{\"effects\":[]}", "{\"type\":\"action.request\"}");
        assertEquals(List.of("rage"), available(program, calm, "sheet"));
        ObjectNode raging = run(program, calm, "rage").contexts().getFirst();
        raging.set("event", RuleJson.parse("{\"type\":\"action.request\"}"));
        assertEquals(List.of("calm", "rage"), available(program, raging, "sheet"));
        assertEquals(0, run(program, raging, "calm").contexts().getFirst().at("/actor/effects").size());

        var missing = LegacyCatalogConverter.convert(catalog("""
                {"id":"x","name":"X","automation":{"actions":[{"id":"stop","label":"Stop","endEffect":"Elsewhere"}]}}"""));
        assertEquals("effect.endLabeled", block(missing, 0).at("/rules/0/do/0/op").asText());
        assertEquals("Elsewhere", block(missing, 0).at("/rules/0/do/0/label").asText());
    }

    @Test
    void duplicateRuleIdsAreRenamedAndPerRuleBudgetsAreChecked() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"twin","name":"Twin","automation":{"actions":[{"id":"use","label":"A"},{"id":"use","label":"B"}],
                  "rollOptions":[{"id":"use","label":"C","roll":"action","value":1}]}}"""));
        JsonNode rules = block(result, 0).path("rules");
        assertEquals(List.of("use", "use-2", "use-3"), List.of(rules.at("/0/id").asText(), rules.at("/1/id").asText(), rules.at("/2/id").asText()));
        program(result, 0);
        var bad = LegacyCatalogConverter.convert(catalog("""
                {"id":"y","name":"Y","automation":{"actions":[{"id":"a","label":"A","uses":"source"},{"id":"b","label":"B","uses":"own"}]}}"""));
        var messages = bad.diagnostics().stream().map(LegacyCatalogConverter.Diagnostic::message).toList();
        assertTrue(messages.contains("Rule references a source use budget that is not declared."), messages.toString());
        assertTrue(messages.contains("Per-rule use limits must reference the source budget."), messages.toString());
    }

    @Test
    void rejectsMalformedConditionalTriggerMetadata() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"bad","name":"Bad","automation":{"conditionalRollTriggers":[
                  {"id":"success","label":"Success","on":"any","when":"success","difficulty":"twelve"}]}}"""));
        assertEquals(0, result.converted());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.path().endsWith("/difficulty")
                && d.message().equals("Expected a nonnegative whole difficulty.")), result.diagnostics().toString());
    }

    private static RuleDispatcher.EventResolution runWith(RuleCompiler.Program program, ObjectNode context, String rule, String inputs) throws Exception {
        String binding = "roll.finished".equals(context.at("/event/type").asText()) ? "use" : "sheet";
        var intent = new RuleRuntime.Intent(rule, binding, "fixture", context.path("revision").asLong(), (ObjectNode) RuleJson.parse(inputs));
        return RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, context)), List.of(intent), RandomGenerator.getDefault());
    }

    @Test
    void computedSignedAndDiceAmountsBecomeExpressions() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"amounts","name":"Amounts","automation":{
                  "modifiers":[{"stat":"evasion","value":"-@tier"}],
                  "actions":[{"id":"heal","label":"Heal","steps":[{"op":"clear","resource":"hp","amount":"@proficiency"},
                    {"op":"clear","resource":"stress","amount":"1d4+1"}],"report":{"text":"Cleared {amount}.","amount":"@proficiency"}},
                    {"id":"all","label":"All in","cost":{"hope":"@hope"}}]}}"""));
        JsonNode block = block(result, 0);
        assertEquals("actor.stats.tier", block.at("/modifiers/0/value/negate/read").asText());
        JsonNode heal = block.at("/rules/0");
        assertEquals("actor.proficiency.value", heal.at("/do/0/value/max/1/read").asText());
        assertEquals("actor.proficiency.value", heal.at("/do/1/amount/read").asText());
        assertEquals("dice.roll", heal.at("/do/2/op").asText());
        assertEquals(4, heal.at("/do/2/sides").asInt());
        assertEquals("Cleared {result.reportAmount}.", heal.at("/ui/report").asText());
        assertEquals("actor.hope.value", block.at("/rules/1/costs/0/amount/read").asText());

        RuleCompiler.Program program = program(result, 0);
        ObjectNode context = context(program, """
                {"hope":{"value":3,"max":6},"proficiency":{"value":2},"hp":{"marked":5,"capacity":6},
                 "stress":{"marked":6,"capacity":6}}""", "{\"type\":\"action.request\"}");
        var healed = run(program, context, "heal");
        assertEquals("Cleared 2.", healed.actions().getFirst().report());
        ObjectNode after = healed.contexts().getFirst();
        assertEquals(3, after.at("/actor/hp/marked").asInt());
        int stress = after.at("/actor/stress/marked").asInt();
        assertTrue(stress >= 1 && stress <= 4, "stress " + stress);
        assertEquals(0, run(program, context, "all").contexts().getFirst().at("/actor/hope/value").asInt());
    }

    @Test
    void selectedAmountsBecomeBoundedIntegerInputs() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"pick","name":"Pick","automation":{"actions":[{"id":"spend","label":"Spend",
                  "selectAmount":{"resource":"hope","label":"Hope to spend"},"cost":{"hope":"@amount"},
                  "steps":[{"op":"clear","resource":"stress","amount":"@amount"}]}]}}"""));
        JsonNode rule = block(result, 0).at("/rules/0");
        assertEquals("integer", rule.at("/inputs/0/kind").asText());
        assertEquals("actor.hope.value", rule.at("/inputs/0/max/read").asText());
        assertEquals(1, rule.at("/inputs/0/min").asInt());

        RuleCompiler.Program program = program(result, 0);
        ObjectNode context = context(program, """
                {"hope":{"value":3,"max":6},"stress":{"marked":4,"capacity":6}}""", "{\"type\":\"action.request\"}");
        ObjectNode after = runWith(program, context, "spend", "{\"amount\":2}").contexts().getFirst();
        assertEquals(1, after.at("/actor/hope/value").asInt());
        assertEquals(2, after.at("/actor/stress/marked").asInt());
        ObjectNode broke = context(program, """
                {"hope":{"value":0,"max":6},"stress":{"marked":4,"capacity":6}}""", "{\"type\":\"action.request\"}");
        assertFalse(available(program, broke, "sheet").contains("spend"));
    }

    @Test
    void selectedAmountsArePaidStoredHealedOrAddedToTheRoll() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"aura","name":"Aura","automation":{"resources":[{"id":"layers","label":"Layers","initial":0}],
                  "conditionalRollTriggers":[{"id":"make","label":"Make","on":"card:Aura","selectAmount":{"resource":"stress","label":"Extra","min":0,"store":"layers","base":1}}]}},
                {"id":"fane","name":"Fane","automation":{"resources":[{"id":"tokens","label":"Tokens","initial":3}],
                  "conditionalRollTriggers":[{"id":"bonus","label":"Fane","on":"spellcast","selectAmount":{"resource":"tokens","label":"Tokens","addToRoll":true}}]}},
                {"id":"heal","name":"Heal","automation":{"actions":[{"id":"heal","label":"Heal","selectAmount":{"resource":"hp","label":"HP","min":0,"heal":true}}]}},
                {"id":"pay","name":"Pay","automation":{"actions":[{"id":"pay","label":"Pay","selectAmount":{"resource":"hope","label":"Hope"}}]}}"""));
        assertEquals(4, result.converted(), result.diagnostics().toString());

        RuleCompiler.Program aura = program(result, 0);
        ObjectNode auraContext = context(aura, """
                {"stress":{"marked":1,"capacity":6},"pools":{"layers":{"value":5}}}""",
                "{\"type\":\"roll.finished\",\"check\":{\"origin\":\"card:Aura\",\"outcome\":\"success\"},\"roll\":" + ROLL + "}");
        ObjectNode made = runWith(aura, auraContext, "make", "{\"amount\":2}").contexts().getFirst();
        assertEquals(3, made.at("/actor/stress/marked").asInt());
        assertEquals(3, made.at("/actor/pools/layers/value").asInt());

        JsonNode fane = result.catalog().at("/entries/1/automation/rules/0");
        assertEquals("roll.contribute", fane.at("/do/0/op").asText());
        assertEquals("input.amount", fane.at("/do/0/contribution/value/read").asText());
        assertEquals("pool.spend", fane.at("/costs/0/op").asText());

        RuleCompiler.Program heal = program(result, 2);
        ObjectNode healed = runWith(heal, context(heal, "{\"hp\":{\"marked\":4,\"capacity\":6}}", "{\"type\":\"action.request\"}"),
                "heal", "{\"amount\":3}").contexts().getFirst();
        assertEquals(1, healed.at("/actor/hp/marked").asInt());
        assertFalse(result.catalog().at("/entries/2/automation/rules/0").has("costs"));

        RuleCompiler.Program pay = program(result, 3);
        ObjectNode paid = runWith(pay, context(pay, "{\"hope\":{\"value\":4,\"max\":6}}", "{\"type\":\"action.request\"}"),
                "pay", "{\"amount\":3}").contexts().getFirst();
        assertEquals(1, paid.at("/actor/hope/value").asInt());
    }

    @Test
    void computedCapacitiesSessionResetsAndWholePoolDiceConvert() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"slayer","name":"Slayer","automation":{"resources":[{"id":"slayer","label":"Slayer Dice","initial":0,
                  "max":"@proficiency","session":true,"sessionHope":true}]}},
                {"id":"chaos","name":"Chaos","automation":{"resources":[{"id":"chaos","label":"Chaos","initial":0,"max":"@spellcast",
                  "session":true,"sessionReset":"@spellcast"}]}},
                {"id":"sigil","name":"Sigil","automation":{"resources":[{"id":"sigil","label":"Sigil d8s","initial":0,"max":"@level"}],
                  "rollOptions":[{"id":"damage","label":"Sigil","roll":"damage","allResourceDice":{"resource":"sigil","sides":8},
                    "steps":[{"op":"clear","resource":"sigil","amount":"all"}]}]}}"""));
        assertEquals(3, result.converted(), result.diagnostics().toString());
        assertEquals("actor.proficiency.value", result.catalog().at("/entries/0/automation/pools/slayer/max/read").asText());
        assertFalse(result.catalog().at("/entries/0/automation/pools/slayer").has("resetOn"), "Hope conversion spends the pool.");

        RuleCompiler.Program slayer = program(result, 0);
        ObjectNode session = context(slayer, """
                {"proficiency":{"value":2},"hope":{"value":1,"max":6},"pools":{"slayer":{"value":5,"max":5}}}""", "{\"type\":\"session.start\"}");
        ObjectNode converted = run(slayer, session).contexts().getFirst();
        assertEquals(3, converted.at("/actor/hope/value").asInt(), "The capacity follows Proficiency, so only 2 dice remain.");
        assertEquals(0, converted.at("/actor/pools/slayer/value").asInt());
        assertEquals(2, converted.at("/actor/pools/slayer/max").asInt());

        RuleCompiler.Program chaos = program(result, 1);
        ObjectNode prepared = RuleRuntime.resetLimits(chaos, context(chaos, """
                {"stats":{"spellcast":{"base":3}}}""", "{\"type\":\"session.start\"}"), "session.start");
        assertEquals(3, prepared.at("/actor/pools/chaos/value").asInt(), prepared.toString());

        RuleCompiler.Program sigil = program(result, 2);
        String damage = "{\"type\":\"roll.prepare\",\"check\":{\"kind\":\"damage\"},\"roll\":" + ROLL + "}";
        assertEquals(List.of(), available(sigil, context(sigil, "{\"level\":3}", damage), "roll"));
        ObjectNode charged = context(sigil, "{\"level\":3,\"pools\":{\"sigil\":{\"value\":2,\"max\":3}}}", damage);
        assertEquals(List.of("damage"), available(sigil, charged, "roll"));
        ObjectNode rolled = run(sigil, charged, "damage").contexts().getFirst();
        assertEquals(0, rolled.at("/actor/pools/sigil/value").asInt());
        assertEquals(2, rolled.at("/event/roll/contributions/1/dice").size(), rolled.at("/event/roll").toString());

        var invalid = LegacyCatalogConverter.convert(catalog("""
                {"id":"b","name":"B","automation":{"resources":[{"id":"x","max":"@nothing"},{"id":"y","sessionHope":true}]}}"""));
        assertEquals(2, invalid.diagnostics().size(), invalid.diagnostics().toString());
    }

    @Test
    void resourceRollsSpendAChosenAmountAndRollThatManyDice() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"jar","name":"Jar","automation":{"actions":[{"id":"throw","label":"Throw","resourceRoll":{"resource":"gold","sides":20,"type":"magic"}}]}},
                {"id":"sky","name":"Sky","automation":{"conditionalRollTriggers":[{"id":"damage","label":"Sky","on":"card:Sky",
                  "resourceRoll":{"resource":"stress","sides":20,"bonusPerDie":2,"type":"magic"}}]}},
                {"id":"mirror","name":"Mirror","automation":{"actions":[{"id":"reflect","label":"Reflect","resourceRoll":{"resource":"hope","sides":6},
                  "report":{"text":"If any die shows 6, reflect it."}}]}}"""));
        assertEquals(3, result.converted(), result.diagnostics().toString());

        RuleCompiler.Program jar = program(result, 0);
        ObjectNode rich = context(jar, "{\"gold\":5}", "{\"type\":\"action.request\"}");
        var thrown = runWith(jar, rich, "throw", "{\"amount\":3}");
        assertEquals(2, thrown.contexts().getFirst().at("/actor/gold").asInt());
        JsonNode jarRule = result.catalog().at("/entries/0/automation/rules/0");
        assertEquals("magic", jarRule.at("/do/0/plan/literal/metadata/damageType").asText());
        assertTrue(jarRule.at("/ui/report").asText().startsWith("Spent {input.amount} gold: {input.amount}d20 magic damage"));

        JsonNode sky = result.catalog().at("/entries/1/automation/rules/0");
        assertEquals("track.mark", sky.at("/costs/0/op").asText());
        assertEquals("multiply", sky.at("/do/0/plan/literal/contributions/1/value").fieldNames().next());
        assertTrue(sky.at("/ui/report").asText().startsWith("Marked {input.amount} stress"));

        JsonNode mirror = result.catalog().at("/entries/2/automation/rules/0");
        assertEquals("resourceRoll", mirror.at("/do/0/as").asText());
        assertEquals("roll", mirror.at("/do/0/plan/literal/metadata/kind").asText());
        assertTrue(mirror.at("/ui/report").asText().endsWith("If any die shows 6, reflect it."));
        RuleCompiler.Program program = program(result, 2);
        ObjectNode after = runWith(program, context(program, "{\"hope\":{\"value\":4,\"max\":6}}", "{\"type\":\"action.request\"}"),
                "reflect", "{\"amount\":2}").contexts().getFirst();
        assertEquals(2, after.at("/actor/hope/value").asInt());
    }

    @Test
    void containsDieReadsEveryFaceOfTheFinishedRollAndRollSectionsBecomeGroups() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"redirect","name":"Redirect","automation":{"rollTriggers":[{"id":"redirect","label":"Redirect","on":"any",
                  "containsDie":6,"rollSection":"feature"}]}}"""));
        assertEquals(1, result.converted(), result.diagnostics().toString());
        assertEquals("sourceFeature", result.catalog().at("/entries/0/automation/rules/0/bindings/0/group").asText());
        RuleCompiler.Program program = program(result, 0);
        String roll = """
                {"type":"roll.finished","check":{"kind":"action"},"roll":{"id":"r","contributions":[
                  {"id":"d","kind":"dice","role":"roll","sourceId":"core","label":"d6","dice":[
                    {"id":"d.d1","sides":6,"face":%d,"kept":true,"origin":"rolled"},{"id":"d.d2","sides":6,"face":2,"kept":true,"origin":"rolled"}]}]}}""";
        assertEquals(List.of("redirect"), available(program, context(program, "{}", roll.formatted(6)), "roll"));
        assertEquals(List.of(), available(program, context(program, "{}", roll.formatted(5)), "roll"));
    }

    @Test
    void spellcastDifficultyBecomesARollThatRefundsTheUseOnFailure() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"easy","name":"Easy","automation":{"uses":{"max":1,"per":"rest"},"spellcastDifficulty":0,"automaticRestUse":true}},
                {"id":"hard","name":"Hard","automation":{"uses":{"max":1,"per":"rest"},"spellcastDifficulty":200,"automaticRestUse":true,"automaticRestUseOnAttempt":true}},
                {"id":"free","name":"Free","automation":{"spellcastDifficulty":13}},
                {"id":"bad","name":"Bad","automation":{"spellcastDifficulty":13,"automaticRestUse":true}}"""));
        assertEquals(3, result.converted(), result.diagnostics().toString());
        JsonNode easy = result.catalog().at("/entries/0/automation/rules/0");
        assertEquals("uses", easy.path("limitRef").asText());
        assertEquals(0, easy.at("/do/0/plan/literal/metadata/difficulty").asInt());
        assertEquals("roll.check", easy.at("/do/1/op").asText());
        assertEquals("limit.modify", easy.at("/do/2/op").asText());
        JsonNode hard = result.catalog().at("/entries/1/automation/rules/0");
        assertEquals(2, hard.path("do").size());
        JsonNode free = result.catalog().at("/entries/2/automation/rules/0");
        assertFalse(free.has("limitRef"));
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.path().endsWith("/automaticRestUse")), result.diagnostics().toString());

        String actor = "{\"spellcastTrait\":{\"value\":1}}";
        for (int entry : new int[]{0, 1}) {
            RuleCompiler.Program program = program(result, entry);
            ObjectNode after = run(program, context(program, actor, "{\"type\":\"action.request\"}"), "spellcast-roll").contexts().getFirst();
            assertEquals(1, after.at("/usage/uses").asInt());
        }
    }

    @Test
    void topLevelCardSpellcastsBecomeTrustedProfilesWithUniqueIdsAndUsePolicies() throws Exception {
        String cards = """
                {"format":"dmmap-daggerheart","version":1,"category":"domain-cards","label":"Cards","entries":[
                 {"id":"free","name":"Free Spell","automation":{"spellcastDifficulty":13,
                  "alternateProfiles":[{"label":"Spellcast Roll","damage":"d6"}]}},
                 {"id":"success","name":"Limited Spell","automation":{"spellcastDifficulty":14,
                  "uses":{"max":1,"per":"rest"},"automaticRestUse":true}},
                 {"id":"attempt","name":"Attempt Spell","automation":{"spellcastDifficulty":15,
                  "uses":{"max":1,"per":"longRest"},"automaticRestUse":true,"automaticRestUseOnAttempt":true}},
                 {"id":"bad","name":"Bad Spell","automation":{"spellcastDifficulty":13,"automaticRestUse":true}},
                 {"id":"orphan","name":"Orphan","automation":{"spellcastDifficulty":13,"automaticRestUseOnAttempt":true}}]}""";
        var result = LegacyCatalogConverter.convert(cards);
        assertEquals(3, result.converted(), result.diagnostics().toString());
        JsonNode free = result.catalog().requiredAt("/entries/0/automation/profiles/1");
        assertEquals("spellcast-roll-2", free.required("id").textValue());
        assertEquals("spellcast", free.required("rollKind").textValue());
        assertEquals("spellcast", free.required("trait").textValue());
        assertEquals("card:Free Spell", free.required("origin").textValue());
        assertEquals(13, free.required("difficulty").intValue());
        assertFalse(free.has("limitRef"));
        JsonNode limited = result.catalog().requiredAt("/entries/1/automation/profiles/0");
        assertEquals("uses", limited.required("limitRef").textValue());
        assertEquals("success", limited.required("consumeOn").textValue());
        assertEquals("attempt", result.catalog().requiredAt("/entries/2/automation/profiles/0/consumeOn").textValue());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.path().endsWith("/automaticRestUse")));
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.path().endsWith("/automaticRestUseOnAttempt")));
    }

    @Test
    void actionSelectorsIncludeAllLegacyDualityActionsAndWorkBeforeDiceAreRolled() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"selectors","name":"Selectors","automation":{"rollOptions":[
                  {"id":"action","label":"Action","roll":"action","value":1},
                  {"id":"duality","label":"Duality","roll":"duality","value":1},
                  {"id":"not-action","label":"Not action","roll":"any","excludeRolls":["action"],"value":1}]}}"""));
        assertEquals(1, result.converted(), result.diagnostics().toString());
        RuleCompiler.Program program = program(result, 0);
        for (String kind : List.of("action", "attack", "reaction", "spellcast")) {
            ObjectNode context = context(program, "{}", """
                    {"type":"roll.prepare","check":{"kind":"%s","duality":true,"dominant":null}}""".formatted(kind));
            assertEquals(List.of("action", "duality"), available(program, context, "roll"), kind);
        }
        ObjectNode damage = context(program, "{}", """
                {"type":"roll.prepare","check":{"kind":"damage","duality":false,"dominant":null}}""");
        assertEquals(List.of("not-action"), available(program, damage, "roll"));
    }

    @Test
    void repeatableLegacyTriggerPreservesItsResultBinding() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"repeat","name":"Repeat","automation":{"conditionalRollTriggers":[
                  {"id":"again","label":"Again","on":"action","repeatable":true,
                   "cost":{"stress":1},"report":{"text":"Applied."}}]}}"""));
        assertEquals(1, result.converted(), result.diagnostics().toString());
        assertTrue(result.catalog().requiredAt("/entries/0/automation/rules/0/bindings/0/repeatable").booleanValue());
        program(result, 0);
    }

    private static RuleDispatcher.ClaimIntent claimIntent(String rule, String recipient, ObjectNode inputs) {
        return claimIntent(rule, recipient, inputs, 0);
    }

    private static RuleDispatcher.ClaimIntent claimIntent(String rule, String recipient, ObjectNode inputs, long revision) {
        return new RuleDispatcher.ClaimIntent(new RuleRuntime.Intent(rule, "claim", "fixture", 0,
                inputs == null ? RuleJson.JSON.createObjectNode() : inputs), recipient, 0, revision);
    }

    @Test
    void chatBenefitsBecomeScopedClaimRulesWithOffers() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"boon","name":"Boon","automation":{"actions":[
                  {"id":"inspire","label":"Inspire","chatBenefits":[
                    {"id":"hope","label":"Gain Hope","exclusiveClaim":true,"cost":{"stress":1},
                     "steps":[{"op":"gain","resource":"hope","amount":1}],"report":{"text":"Onward!"}},
                    {"id":"rest","label":"Breathe","sourceOnly":true,"steps":[{"op":"clear","resource":"stress","amount":1}]}]},
                  {"id":"share","label":"Share","allowSourceOwner":true,"chatBenefits":[
                    {"id":"transfer","label":"Transfer Stress","transferStress":true,"steps":[]}]},
                  {"id":"call","label":"Call","excludeSourceActor":true,"benefitsPerAmount":true,
                   "selectAmount":{"resource":"hope","label":"Hope"},"chatBenefits":[
                    {"id":"ride","label":"Ride","steps":[{"op":"gain","resource":"hope","amount":1}]}]}]}}"""));
        var blast = LegacyCatalogConverter.convert(catalog("""
                {"id":"blast","name":"Blast","automation":{"actions":[{"id":"blast","label":"Blast","chatBenefits":[{"id":"x","label":"X","featureAction":"Go","steps":[]}]}]}}"""));
        assertTrue(blast.diagnostics().stream().anyMatch(d -> d.path().endsWith("/featureAction")), blast.diagnostics().toString());
        JsonNode block = block(result, 0);
        JsonNode rules = block.path("rules");
        assertEquals("claim.offer", rules.at("/0/do/0/op").asText());
        assertEquals("inspire-claims", rules.at("/0/do/0/group").asText());
        assertEquals(1, rules.at("/0/do/0/limit").asInt());
        assertEquals("others", rules.at("/1/bindings/0/audience").asText());
        assertEquals("{recipient.name} claimed Gain Hope. Onward!", rules.at("/1/ui/report").asText());
        assertEquals("sourceActor", rules.at("/2/bindings/0/claimScope/recipients").asText());
        assertEquals("playerCharacters", rules.at("/4/bindings/0/claimScope/recipients").asText());
        assertTrue(rules.at("/6/bindings/0/claimScope/excludeSource").asBoolean());
        assertEquals("input.amount", rules.at("/5/do/0/limit/subtract/0/read").asText(), rules.get(5).toString());

        RuleCompiler.Program program = program(result, 0);
        ObjectNode context = context(program, """
                {"id":"owner","name":"Ash","hope":{"value":2,"max":6},"stress":{"marked":3,"capacity":6}}""",
                "{\"type\":\"action.request\"}");
        JsonNode offer = run(program, context, "inspire").contexts().getFirst().path("outbox").get(0);
        assertEquals("Inspire", offer.path("label").asText());
        assertEquals(1, offer.path("limit").asInt());

        ObjectNode claim = context.deepCopy();
        claim.set("event", RuleJson.parse("{\"type\":\"claim.accept\",\"id\":\"c\"}"));
        var source = new RuleDispatcher.Candidate(program, claim);
        var recipients = List.of(new RuleDispatcher.RecipientCandidate("owner", (ObjectNode) claim.path("actor").deepCopy(), 0),
                new RuleDispatcher.RecipientCandidate("mira", (ObjectNode) RuleJson.parse("""
                        {"id":"mira","name":"Mira","hope":{"value":1,"max":6},"stress":{"marked":4,"capacity":6}}"""), 0));
        var ledger = new RuleDispatcher.ClaimLedger(0, java.util.Set.of(), Map.of("inspire-claims", 1));
        var gained = RuleDispatcher.executeClaims(List.of(source), List.of(claimIntent("inspire-hope", "mira", null)),
                recipients, ledger, RandomGenerator.getDefault());
        assertEquals(2, gained.recipients().getFirst().snapshot().at("/hope/value").asInt());
        assertEquals(5, gained.recipients().getFirst().snapshot().at("/stress/marked").asInt());
        assertEquals("Mira claimed Gain Hope. Onward!", gained.actions().getFirst().action().report());
        assertEquals("claimLimit", assertThrows(RuleRuntime.Unavailable.class, () -> RuleDispatcher.executeClaims(
                List.of(source), List.of(claimIntent("inspire-rest", "owner", null, gained.ledger().revision())), recipients, gained.ledger(),
                RandomGenerator.getDefault())).code());
        var self = RuleDispatcher.executeClaims(List.of(source), List.of(claimIntent("inspire-rest", "owner", null)),
                recipients, new RuleDispatcher.ClaimLedger(0, java.util.Set.of()), RandomGenerator.getDefault());
        assertEquals(2, self.sourceContexts().getFirst().at("/actor/stress/marked").asInt());

        var moved = RuleDispatcher.executeClaims(List.of(source), List.of(claimIntent("share-transfer", "mira",
                (ObjectNode) RuleJson.parse("{\"amount\":2}"))), recipients, new RuleDispatcher.ClaimLedger(0, java.util.Set.of()),
                RandomGenerator.getDefault());
        assertEquals(2, moved.recipients().getFirst().snapshot().at("/stress/marked").asInt());
        assertEquals(5, moved.sourceContexts().getFirst().at("/actor/stress/marked").asInt());
        assertEquals(4, moved.sourceContexts().getFirst().at("/actor/hope/value").asInt());
        assertThrows(IllegalArgumentException.class, () -> RuleDispatcher.executeClaims(
                List.of(source), List.of(claimIntent("call-ride", "owner", null)), recipients,
                new RuleDispatcher.ClaimLedger(0, java.util.Set.of()), RandomGenerator.getDefault()));
    }

    private static final String PREPARE = """
            {"type":"rest.prepare","rest":{"kind":"%s","moves":2,"longMoves":0,
             "adjustable":{"moves":{"min":0,"max":%d},"longMoves":{"min":0,"max":1}}}}""";

    private static String move(String id, String rest, boolean ally, boolean adjustable) {
        return """
                {"type":"downtime.move","move":{"id":"%s","rest":"%s","long":false,"ally":%s,"bonus":0,"allies":0,
                 "adjustable":%s}}""".formatted(id, rest, ally,
                adjustable ? "{\"bonus\":{\"min\":-5,\"max\":5},\"allies\":{\"min\":0,\"max\":4}}" : "{}");
    }

    @Test
    void restAllowancesPassivesAndMoveModifiersBecomeRestEventRules() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"camp","name":"Camp","automation":{"actions":[{"id":"ward","label":"Ward"}],"rest":{
                  "extraMoves":1,"longMovesInShort":true,
                  "passives":[
                    {"id":"tea","label":"Tea","extraMoves":1,"consumeItem":true,"defaultOn":true},
                    {"id":"pipe","label":"Pipe","move":"tend","bonus":2},
                    {"id":"plate","label":"Plate","condition":"armorMarked","rest":"short","steps":[{"op":"clear","resource":"stress","amount":1}]},
                    {"id":"idle","label":"Idle","condition":"noRecovery","minHope":1,"cost":{"hope":1},"castBeforeRest":"ward",
                     "steps":[{"op":"clear","resource":"stress","amount":1}]},
                    {"id":"share","label":"Share","chooseAlly":true,"steps":[{"op":"gain","resource":"hope","amount":1}]}],
                  "modifiers":[{"move":"repair","repairAllies":1},
                    {"move":"tend","target":"ally","bonus":1,"steps":[{"op":"clear","resource":"hp","amount":2}],"note":"Soothing."}]}}}"""));
        JsonNode block = block(result, 0);
        Map<String, JsonNode> rules = new java.util.HashMap<>();
        for (JsonNode rule : block.path("rules")) rules.put(rule.path("id").asText(), rule);
        assertEquals(java.util.Set.of("ward", "rest-moves", "rest-long-moves", "tea", "pipe", "plate", "idle", "share", "share-ally",
                "move-repair", "move-tend"), rules.keySet());
        assertEquals("option", rules.get("tea").at("/bindings/0/mode").asText());
        assertTrue(rules.get("tea").at("/bindings/0/defaultOn").asBoolean());
        assertEquals("inventory.consume", rules.get("tea").at("/costs/0/op").asText());
        assertEquals(1, rules.get("plate").path("bindings").size());
        assertEquals("rest.short", rules.get("plate").at("/bindings/0/event").asText());
        assertEquals(2, rules.get("idle").path("bindings").size());
        assertEquals("afterReset", rules.get("idle").at("/bindings/0/restPhase").asText());
        assertEquals("afterReset", rules.get("idle").at("/bindings/1/restPhase").asText());
        assertEquals("claim.offer", rules.get("share").at("/do/0/op").asText());
        assertEquals("share-ally", rules.get("share-ally").at("/bindings/0/claimGroup").asText());
        assertEquals("otherPlayersCharacters", rules.get("share-ally").at("/bindings/0/claimScope/recipients").asText());
        assertEquals("recipient.hope", rules.get("share-ally").at("/do/0/pool").asText());
        assertEquals("Soothing.", rules.get("move-tend").at("/after/report").asText());
        assertEquals("track.clear", rules.get("move-tend").at("/after/do/0/op").asText());

        RuleCompiler.Program program = program(result, 0);
        JsonNode shortRest = run(program, context(program, "{}", PREPARE.formatted("short", 8))).contexts().getFirst().at("/event/rest");
        assertEquals(3, shortRest.path("moves").asInt());
        assertEquals(1, shortRest.path("longMoves").asInt());
        assertEquals(2, shortRest.path("adjustments").size());
        assertEquals("Extra downtime moves", shortRest.at("/adjustments/1/label").asText());
        JsonNode longRest = run(program, context(program, "{}", PREPARE.formatted("long", 2))).contexts().getFirst().at("/event/rest");
        assertEquals(2, longRest.path("moves").asInt(), "Clamped to the plan's maximum.");
        assertEquals(0, longRest.path("longMoves").asInt());

        String actor = """
                {"id":"me","name":"Ash","hope":{"value":2,"max":6},"stress":{"marked":3,"capacity":6},
                 "hp":{"marked":4,"capacity":6},"armorTrack":{"marked":1,"capacity":3},"effects":[]}""";
        ObjectNode tend = context(program, actor, move("tend", "short", true, true));
        assertEquals(List.of("pipe"), available(program, tend, "downtime"));
        assertEquals("restTransaction", assertThrows(RuleRuntime.Unavailable.class, () -> run(program, tend, "pipe")).code());
        var staged = RuleDispatcher.executeStagedMove(List.of(new RuleDispatcher.Candidate(program, tend)),
                List.of(new RuleRuntime.Intent("pipe", "downtime", "fixture", 0, RuleJson.JSON.createObjectNode())),
                StructuredRollTest.noRandom(), Map.of(program.sourceId(), program));
        ObjectNode tended = staged.contexts().getFirst();
        assertEquals(3, tended.at("/event/move/bonus").asInt());
        assertEquals(4, tended.at("/actor/hp/marked").asInt(), "Modifier steps wait until after the move.");
        tended.withObject("/actor/hp").put("marked", 3);
        var afterMove = RuleRuntime.executeAfter(program,
                new RuleRuntime.Intent("move-tend", "downtime", "fixture", 1, RuleJson.JSON.createObjectNode()),
                tended, StructuredRollTest.noRandom(), Map.of(program.sourceId(), program));
        assertEquals(1, afterMove.context().at("/actor/hp/marked").asInt());
        assertEquals("Soothing.", afterMove.report());
        JsonNode repair = run(program, context(program, actor, move("repair", "long", false, true)))
                .contexts().getFirst().at("/event/move");
        assertEquals(1, repair.path("allies").asInt());
        assertEquals(1, repair.at("/adjustments/0/requested").asInt());
        assertEquals("fixture", repair.at("/adjustments/0/sourceInstanceId").asText());
        assertEquals(program.sourceId(), repair.at("/adjustments/0/sourceId").asText());
        assertEquals("planField", assertThrows(RuleRuntime.Unavailable.class,
                () -> run(program, context(program, actor, move("tend", "short", false, false)), "pipe")).code());

        ObjectNode rested = context(program, actor, """
                {"type":"rest.short","rest":{"kind":"short","start":{"hope":2,"armor":1},
                 "cleared":{"hitPoints":0,"stress":0,"armor":0}}}""");
        assertEquals(List.of("plate", "share"), available(program, rested, "rest"));
        assertEquals("Share", run(program, rested, "share").contexts().getFirst().at("/outbox/0/label").asText());
        rested.withObject("/event/rest").put("phase", "afterReset");
        assertEquals(List.of("idle"), available(program, rested, "rest"));
        ObjectNode idle = run(program, rested, "idle").contexts().getFirst();
        assertEquals(1, idle.at("/actor/hope/value").asInt());
        assertEquals(2, idle.at("/actor/stress/marked").asInt());
        ObjectNode recovered = context(program, actor.replace("\"armorTrack\":{\"marked\":1", "\"armorTrack\":{\"marked\":0"), """
                {"type":"rest.long","rest":{"kind":"long","start":{"hope":2,"armor":1},
                 "cleared":{"hitPoints":2,"stress":0,"armor":0}}}""");
        assertEquals(List.of("share"), available(program, recovered, "rest"));

        var invalid = LegacyCatalogConverter.convert(catalog("""
                {"id":"x","name":"X","automation":{"rest":{"passives":[{"id":"p","label":"P","castBeforeRest":"nowhere",
                  "steps":[{"op":"gain","resource":"hope","amount":1}]}]}}},
                {"id":"y","name":"Y","automation":{"rest":{"passives":[{"id":"p","label":"P","move":"tend","condition":"noRecovery","bonus":1}]}}},
                {"id":"z","name":"Z","automation":{"rest":{}}}"""));
        assertEquals(0, invalid.converted());
        var messages = invalid.diagnostics().stream().map(LegacyCatalogConverter.Diagnostic::message).toList();
        assertTrue(messages.contains("Cast-before-rest must name an action of the same source: nowhere"), messages.toString());
        assertTrue(messages.contains("Recovery is only known after the rest moves."), messages.toString());
        assertTrue(messages.contains("A rest block needs allowances, passives or move modifiers."), messages.toString());
    }

    @Test
    void restConditionsUseStartingFactsAndIndependentRecovery() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"camp","name":"Camp","automation":{"rest":{"passives":[
                  {"id":"hope","label":"Hope","minHope":6,"steps":[{"op":"gain","resource":"hope","amount":1}]},
                  {"id":"initial-armor","label":"Initial Armor","condition":"noArmorMarked",
                   "steps":[{"op":"clear","resource":"stress","amount":1}]},
                  {"id":"current-armor","label":"Current Armor","condition":"armorMarked",
                   "steps":[{"op":"clear","resource":"stress","amount":1}]},
                  {"id":"no-recovery","label":"No Recovery","condition":"noRecovery",
                   "steps":[{"op":"gain","resource":"hope","amount":1}]}
                ]}}}"""));
        RuleCompiler.Program program = program(result, 0);
        String actor = """
                {"id":"me","name":"Ash","hope":{"value":1,"max":6},"stress":{"marked":3,"capacity":6},
                 "hp":{"marked":3,"capacity":6},"armorTrack":{"marked":1,"capacity":3},"effects":[]}""";
        ObjectNode context = context(program, actor, """
                {"type":"rest.short","rest":{"kind":"short","start":{"hope":6,"armor":0},
                 "cleared":{"hitPoints":1,"stress":-1,"armor":0}}}""");
        assertEquals(List.of("current-armor", "hope", "initial-armor"), available(program, context, "rest"));

        context.withObject("/actor/hope").put("value", 6);
        context.withObject("/actor/armorTrack").put("marked", 0);
        context.withObject("/event/rest/start").put("hope", 5).put("armor", 1);
        context.withObject("/event/rest/cleared").put("hitPoints", 0).put("stress", 0);
        context.withObject("/event/rest").put("phase", "afterReset");
        assertEquals(List.of("no-recovery"), available(program, context, "rest"));
    }

    @Test
    void effectGrantedRestAllowancesEndWhenALongMoveIsTakenDuringAShortRest() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"dream","name":"Dream","automation":{"actions":[{"id":"sleep","label":"Sleep","steps":[{"op":"effect","effect":{
                  "name":"Restful","until":"rest","consumeLongRestMove":true,"rest":{"longMovesInShort":true}}}]}]}},
                {"id":"empty","name":"Empty","automation":{}}"""));
        assertEquals(2, result.converted(), result.diagnostics().toString());
        assertFalse(result.catalog().at("/entries/1").has("automation"), "Empty legacy blocks convert to no automation.");
        RuleCompiler.Program program = program(result, 0);
        ObjectNode plain = context(program, "{\"effects\":[]}", "{\"type\":\"action.request\"}");
        plain.set("event", RuleJson.parse(PREPARE.formatted("short", 8)));
        assertEquals(0, run(program, plain).contexts().getFirst().at("/event/rest/longMoves").asInt());

        plain.set("event", RuleJson.parse("{\"type\":\"action.request\"}"));
        ObjectNode restful = run(program, plain, "sleep").contexts().getFirst();
        restful.set("event", RuleJson.parse(PREPARE.formatted("short", 8)));
        assertEquals(1, run(program, restful).contexts().getFirst().at("/event/rest/longMoves").asInt());

        restful.set("event", RuleJson.parse(move("tend", "short", false, true).replace("\"long\":false", "\"long\":true")));
        assertEquals(0, run(program, restful).contexts().getFirst().at("/actor/effects").size());
        restful.set("event", RuleJson.parse(move("tend", "long", false, true).replace("\"long\":false", "\"long\":true")));
        assertEquals(1, run(program, restful).contexts().getFirst().at("/actor/effects").size());
    }

    @Test
    void attachableItemsBecomeAttachmentDeclarationsWhoseRulesTargetTheHostGear() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"stone","name":"Stone","automation":{"attach":{"to":"weapon","requires":{"noFeature":true,"nameIncludes":["bow"]},
                  "feature":{"name":"Brutal","text":"Roll more."},"automation":{"damage":{"explode":"once","label":"Brutal"}}}}},
                {"id":"gem","name":"Gem","automation":{"attach":{"to":"weapon","trait":"Agility"}}},
                {"id":"charm","name":"Charm","automation":{"attach":{"to":"weapon","requires":{"range":"Melee","damageType":"mag"}}}},
                {"id":"plate","name":"Plate","automation":{"attach":{"to":"armor","requires":{"noFeature":true}}}}"""));
        assertEquals(4, result.converted(), result.diagnostics().toString());
        JsonNode stone = result.catalog().at("/entries/0/automation");
        assertEquals("weapon", stone.at("/attachment/to").asText());
        assertEquals("Brutal", stone.at("/attachment/feature/name").asText());
        assertTrue(stone.at("/rules/0").toString().contains("\"source.attachedTo.instanceId\""), stone.toString());
        assertFalse(stone.at("/rules/0").toString().contains("\"source.id\""), stone.toString());

        ObjectNode source = (ObjectNode) RuleJson.parse("{\"id\":\"stone\",\"name\":\"Stone\"}");
        RuleCompiler.Program program = program(result, 0);
        assertEquals(List.of(), program.attachmentBlockers(RuleJson.parse("""
                {"kind":"weapon","name":"Longbow","range":"far","damageType":"physical","hasFeature":false,"generated":false,"hasTraitAttachment":false}"""), source));
        assertEquals(List.of("Attach this to weapon gear.", "Requires a bow."), program.attachmentBlockers(RuleJson.parse("""
                {"kind":"armor","name":"Leather","hasFeature":false,"generated":false}"""), source));
        assertEquals(List.of("Generated weapons cannot hold attachments.", "Axe already has a feature.", "Requires a bow."),
                program.attachmentBlockers(RuleJson.parse("""
                {"kind":"weapon","name":"Axe","hasFeature":true,"generated":true,"hasTraitAttachment":false}"""), source));
        RuleCompiler.Program gem = program(result, 1);
        assertEquals("Agility", gem.attachment().path("trait").asText());
        assertEquals(List.of("Sword already has an attack-trait attachment."), gem.attachmentBlockers(RuleJson.parse("""
                {"kind":"weapon","name":"Sword","hasFeature":false,"generated":false,"hasTraitAttachment":true}"""), source));
        assertEquals(List.of("Requires a Melee weapon.", "Requires a magic weapon."), program(result, 2).attachmentBlockers(RuleJson.parse("""
                {"kind":"weapon","name":"Bow","range":"far","damageType":"physical","generated":false}"""), source));
        assertEquals(List.of("Mail already has a feature."), program(result, 3).attachmentBlockers(RuleJson.parse("""
                {"kind":"armor","name":"Mail","hasFeature":true}"""), source));

        assertThrows(IllegalArgumentException.class, () -> RuleCompiler.compile("x", RuleJson.parse("""
                {"attachment":{"to":"weapon","requires":[{"when":{"read":"actor.hope"},"reason":"No."}]}}"""), RuleJson.parse(LegacyCatalogConverter.EMPTY_RECIPES)));
        var unattached = assertThrows(IllegalArgumentException.class, () -> RuleCompiler.compile("x", RuleJson.parse("""
                {"rules":[{"id":"r","bindings":[{"id":"b","event":"action.request","surface":"sheet","mode":"button"}],
                  "ui":{"label":"{source.attachedTo.name}"},"do":[{"op":"resource.gain","pool":"actor.hope","amount":1}]}]}"""),
                RuleJson.parse(LegacyCatalogConverter.EMPTY_RECIPES)));
        assertTrue(unattached.getMessage().contains("source.attachedTo.name"), unattached.getMessage());
        var invalid = LegacyCatalogConverter.convert(catalog("""
                {"id":"x","name":"X","automation":{"attach":{"to":"shield"}}},
                {"id":"y","name":"Y","automation":{"attach":{"to":"armor","requires":{"range":"Far"}}}}"""));
        assertEquals(0, invalid.converted());
    }

    @Test
    void reloadingWeaponsCheckAfterAttacksBlockWhileUnloadedAndReloadForStress() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"gun","name":"Gun","automation":{"reloading":{"dice":"1d6","fail":1}}}"""));
        block(result, 0);
        RuleCompiler.Program program = program(result, 0);
        String loaded = "{\"effects\":[],\"stress\":{\"marked\":0,\"capacity\":6}}";
        String attack = "{\"type\":\"roll.finished\",\"check\":{\"kind\":\"attack\",\"weaponSource\":\"fixture\"}}";
        var safe = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, context(program, loaded, attack))),
                List.of(), StructuredRollTest.faces(4));
        assertEquals(0, safe.contexts().getFirst().at("/actor/effects").size());
        assertEquals("Reloading d6: 4 (unloads on 1).", safe.actions().getFirst().report());
        var failed = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, context(program, loaded, attack))),
                List.of(), StructuredRollTest.faces(1));
        JsonNode unloadedActor = failed.contexts().getFirst().get("actor");
        assertEquals("Fixture: Unloaded", unloadedActor.at("/effects/0/label").asText());
        String other = "{\"type\":\"roll.finished\",\"check\":{\"kind\":\"attack\",\"weaponSource\":\"other\"}}";
        assertTrue(RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, context(program, loaded, other))),
                List.of(), StructuredRollTest.noRandom()).actions().isEmpty(), "Attacks with other weapons are not checked.");
        String spell = "{\"type\":\"roll.finished\",\"check\":{\"kind\":\"attack\"}}";
        assertTrue(RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, context(program, loaded, spell))),
                List.of(), StructuredRollTest.noRandom()).actions().isEmpty(), "Attacks without a weapon are not checked.");
        String reroll = "{\"type\":\"roll.finished\",\"check\":{\"kind\":\"attack\",\"weaponSource\":\"fixture\",\"reroll\":true}}";
        assertTrue(RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, context(program, loaded, reroll))),
                List.of(), StructuredRollTest.noRandom()).actions().isEmpty(), "Rerolls do not check again.");

        String prepare = "{\"type\":\"roll.prepare\",\"check\":{\"kind\":\"attack\",\"weaponSource\":\"fixture\"}}";
        var blocked = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program,
                context(program, unloadedActor.toString(), prepare))), List.of(), StructuredRollTest.noRandom());
        assertEquals("Fixture is unloaded. Reload it first.", blocked.contexts().getFirst().at("/event/blocked/0/reason").asText());
        var open = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program,
                context(program, loaded, prepare))), List.of(), StructuredRollTest.noRandom());
        assertTrue(open.contexts().getFirst().at("/event/blocked").isMissingNode(), "Loaded weapons are not blocked.");

        String request = "{\"type\":\"action.request\"}";
        assertEquals(List.of(), available(program, context(program, loaded, request), "sheet"));
        ObjectNode unloadedContext = context(program, unloadedActor.toString(), request);
        assertEquals(List.of("reload"), available(program, unloadedContext, "sheet"));
        JsonNode reloaded = run(program, unloadedContext, "reload").contexts().getFirst().get("actor");
        assertEquals(0, reloaded.path("effects").size());
        assertEquals(1, reloaded.at("/stress/marked").asInt());

        var invalid = LegacyCatalogConverter.convert(catalog("""
                {"id":"x","name":"X","automation":{"reloading":{"dice":"2d6"}}},
                {"id":"y","name":"Y","automation":{"reloading":{"dice":"1d6","fail":7}}}"""));
        assertEquals(0, invalid.converted());
    }

    @Test
    void typedInputsAndSpecialActionsConvert() throws Exception {
        String text = catalog("""
                {"id":"found","name":"Found","automation":{"uses":{"max":1,"per":"shortRest"},"rollOptions":[
                  {"id":"family","label":"Found family","roll":"action","cost":{"hope":1},"input":{"label":"Bonus","default":2,"min":0,"max":20}}]}},
                {"id":"leaf","name":"Leaf","automation":{"rollOptions":[
                  {"id":"fear-leaf","label":"Fear Leaf","roll":"damage","input":{"label":"Bonus","default":1,"min":1,"max":20,"source":"attackFear"}}]}},
                {"id":"disguise","name":"Disguise","automation":{"actions":[{"id":"use","label":"Mass Disguise","specialAction":"massDisguise","cost":{"stress":1}}]}},
                {"id":"terror","name":"Terror","automation":{"uses":{"max":1,"per":"longRest"},"actions":[{"id":"use","label":"Night Terror","specialAction":"nightTerror"}]}},
                {"id":"vital","name":"Vital","automation":{"actions":[{"id":"choose","label":"Choose","specialAction":"vitality"}]}}""");
        var result = LegacyCatalogConverter.convert(text);
        assertEquals(5, result.converted(), result.diagnostics().toString());
        JsonNode found = block(result, 0).at("/rules/0");
        assertEquals(2, found.at("/inputs/0/default").asInt(), found.toString());
        assertTrue(found.toString().contains("input.bonus"), found.toString());
        assertTrue(block(result, 1).toString().contains("\"role\":\"damage\""), block(result, 1).toString());
        assertTrue(block(result, 2).toString().contains("countdown.start"), block(result, 2).toString());
        String terror = block(result, 3).toString();
        assertTrue(terror.contains("table.spend") && terror.contains("event.table.fear"), terror);
        String vital = block(result, 4).toString();
        assertTrue(vital.contains("track.capacity") && vital.contains("\"count\":2") && vital.contains("\"vault\""), vital);
        assertTrue(block(result, 4).at("/effects").elements().next().at("/modifiers").toString().contains("thresholds"), vital);
        for (int i = 0; i < 5; i++) assertNotNull(program(result, i));
    }

    @Test
    void damageRewardsAndMaintainedEffectsConvert() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"blood","name":"Blood","automation":{"conditionalDamageRewards":[
                  {"minHp":1,"noArmorMarked":true,"clearArmor":1,"gainHope":1,"loadoutDomain":{"domain":"Valor","min":4}}]}},
                {"id":"avatar","name":"Avatar","automation":{"actions":[{"id":"use","label":"Avatar","cost":{"stress":1},"steps":[{"op":"effect","effect":{
                  "name":"Avatar","kind":"buff","until":"manual","maintainOnAction":"maintain","text":"Spend Hope.","rollOptions":[
                  {"id":"damage","label":"Avatar damage","roll":"damage","dice":"@feard6","defaultOn":true},
                  {"id":"maintain","label":"Maintain","roll":"action","cost":{"hope":1},"defaultOn":true}]}}]}]}}"""));
        assertEquals(2, result.converted(), result.diagnostics().toString());
        String reward = block(result, 0).toString();
        assertTrue(reward.contains("damage.applied") && reward.contains("armorMarked") && reward.contains("actor.armorTrack"), reward);
        String avatar = block(result, 1).toString();
        assertTrue(avatar.contains("revert-avatar") && avatar.contains("event.table.fear") && avatar.contains("roll.prepare"), avatar);
        for (int i = 0; i < 2; i++) assertNotNull(program(result, i));
    }
    @Test
    void repeatedDamageRunsUseTheSharedRecipe() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"deathrun","name":"Deathrun","automation":{
                 "actions":[{"id":"use","label":"Deathrun","cost":{"hope":3},"steps":[{"op":"effect","effect":{"name":"Deathrun","deathrun":true,"counter":{"value":0,"max":100}}}]}],
                 "rollOptions":[{"id":"run","label":"Deathrun","roll":"attack","weapon":"any","cost":{"hope":3},"deathrun":true}],
                 "rollTriggers":[{"id":"end","label":"End Deathrun","on":"damage","deathrunResult":true,"endEffect":"Deathrun"}]}}"""));
        assertEquals(1, result.converted(), result.diagnostics().toString());
        JsonNode rule = block(result, 0).at("/rules/0");
        assertEquals("core:repeat-saved-weapon-damage", rule.path("useRecipe").asText(), rule.toString());
        assertEquals(3, rule.at("/args/startCosts/0/amount").asInt(), rule.toString());
        assertEquals(1, rule.at("/args/firstProficiencyBonus").asInt(), rule.toString());
        assertNotNull(program(result, 0));
    }
    @Test
    void computedReportAmountsEvaluateBeforeReporting() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"clarity","name":"Clarity","automation":{"actions":[{"id":"use","label":"Clarity",
                 "report":{"amount":"@Instinct","text":"Clear {amount} Stress."}}]}},
                {"id":"snapshot","name":"Snapshot","automation":{"actions":[{"id":"use","label":"Snapshot",
                 "cost":{"hope":1},"report":{"amount":"@hope","text":"Hope: {amount}."},
                 "steps":[{"op":"gain","resource":"hope","amount":1}]}]}},
                {"id":"negative","name":"Negative","automation":{"actions":[{"id":"use","label":"Negative",
                 "report":{"amount":-2,"text":"Amount: {amount}."}}]}}"""));
        assertEquals(3, result.converted(), result.diagnostics().toString());
        RuleCompiler.Program clarity = program(result, 0);
        assertTrue(block(result, 0).toString().contains("{result.reportAmount}"), block(result, 0).toString());
        for (int total : new int[]{3, -2}) {
            ObjectNode context = context(clarity, """
                    {"traits":{"Instinct":-1},"stats":{"trait":{"Instinct":%d}}}""".formatted(total), "{\"type\":\"action.request\"}");
            assertEquals("Clear " + Math.max(0, total) + " Stress.", run(clarity, context, "use").actions().getFirst().report());
        }
        RuleCompiler.Program snapshot = program(result, 1);
        ObjectNode before = context(snapshot, "{\"hope\":{\"value\":3,\"max\":6}}", "{\"type\":\"action.request\"}");
        var after = run(snapshot, before, "use");
        assertEquals("Hope: 2.", after.actions().getFirst().report());
        assertEquals(3, after.contexts().getFirst().at("/actor/hope/value").asInt());
        RuleCompiler.Program negative = program(result, 2);
        assertEquals("Amount: 0.", run(negative, context(negative, "{}", "{\"type\":\"action.request\"}"), "use").actions().getFirst().report());

        var afterPayment = LegacyCatalogConverter.convert(catalog("""
                {"id":"paid","name":"Paid","automation":{"actions":[{"id":"use","label":"Paid","cost":{"hope":1},
                 "report":{"amount":"@Instinct","text":"Amount {amount}."}}]}}"""));
        assertEquals(0, afterPayment.converted(), afterPayment.diagnostics().toString());
        assertTrue(afterPayment.diagnostics().stream().anyMatch(d -> d.path().endsWith("/report/amount")
                && d.message().contains("recalculating actor stats")), afterPayment.diagnostics().toString());
        var trigger = LegacyCatalogConverter.convert(catalog("""
                {"id":"trigger","name":"Trigger","automation":{"rollTriggers":[{"id":"use","label":"Trigger","on":"action",
                 "report":{"amount":"@hope","text":"Amount {amount}."}}]}}"""));
        assertEquals(0, trigger.converted(), trigger.diagnostics().toString());
        assertTrue(trigger.diagnostics().stream().anyMatch(d -> d.path().endsWith("/report/amount")
                && d.message().contains("outside actions")), trigger.diagnostics().toString());
    }

    @Test
    void shippedWardenReportDiceRollOnceAndSpendOnlyOnSuccess() throws Exception {
        JsonNode classes = RuleJson.parse(Files.readString(Path.of("dmmap-daggerheart", "classes.json")));
        JsonNode feature = null;
        for (JsonNode entry : classes.path("entries")) for (JsonNode candidate : entry.path("features"))
            if ("Warden’s Protection".equals(candidate.path("name").asText())) feature = candidate;
        assertNotNull(feature);
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"warden","name":"Warden","automation":%s}""".formatted(feature.path("automation"))));
        assertEquals(1, result.converted(), result.diagnostics().toString());
        RuleCompiler.Program program = program(result, 0);
        ObjectNode before = context(program, "{\"hope\":{\"value\":6,\"max\":6}}", "{\"type\":\"action.request\"}");
        var applied = run(program, before, StructuredRollTest.faces(4), "use");
        assertEquals("Clear 2 HP on each of up to 4 allies within Close range.", applied.actions().getFirst().report());
        assertEquals(4, applied.contexts().getFirst().at("/actor/hope/value").asInt());
        assertEquals(1, applied.contexts().getFirst().at("/usage/uses").asInt());
        JsonNode roll = applied.actions().getFirst().diceRolls().getFirst();
        assertEquals(4, roll.path("total").asInt());
        assertEquals(4, roll.at("/contributions/0/dice/0/face").asInt());
        assertEquals(6, before.at("/actor/hope/value").asInt(), "The submitted state remains immutable.");

        ObjectNode unaffordable = context(program, "{\"hope\":{\"value\":1,\"max\":6}}", "{\"type\":\"action.request\"}");
        assertThrows(RuntimeException.class, () -> run(program, unaffordable, StructuredRollTest.noRandom(), "use"));
        assertEquals(1, unaffordable.at("/actor/hope/value").asInt());
        assertTrue(unaffordable.path("usage").isMissingNode());
    }

    @Test
    void reportDicePreserveSignedTermsPrecedenceAndNonnegativeDisplay() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"mixed","name":"Mixed","automation":{"actions":[{"id":"use","label":"Mixed",
                 "report":{"dice":" 2D6 - d4 + 2 - 9 ","amount":99,"text":"Result {amount}; again {amount}."},
                 "steps":[{"op":"gain","resource":"hope","amount":1}]}]}}"""));
        assertEquals(1, result.converted(), result.diagnostics().toString());
        RuleCompiler.Program program = program(result, 0);
        for (int[] faces : new int[][]{{6, 5, 1}, {1, 1, 4}}) {
            ObjectNode before = context(program, "{\"hope\":{\"value\":0,\"max\":6}}", "{\"type\":\"action.request\"}");
            var applied = run(program, before, StructuredRollTest.faces(faces), "use");
            int total = faces[0] + faces[1] - faces[2] + 2 - 9;
            assertEquals("Result " + Math.max(0, total) + "; again {amount}.", applied.actions().getFirst().report());
            assertEquals(total, applied.actions().getFirst().diceRolls().getFirst().path("total").asInt());
            assertEquals(1, applied.contexts().getFirst().at("/actor/hope/value").asInt());
        }
    }

    @Test
    void reportDicePrecedeStepDiceAndLateFailuresDoNotCommit() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("""
                {"id":"ordered","name":"Ordered","automation":{"uses":{"max":1,"per":"rest"},"actions":[
                 {"id":"use","label":"Ordered","cost":{"hope":1},
                  "report":{"dice":"1d4","text":"Report {amount}."},
                  "steps":[{"op":"clear","resource":"stress","amount":"1d6"},
                           {"op":"mark","resource":"hp","amount":2}]}]}}"""));
        assertEquals(1, result.converted(), result.diagnostics().toString());
        RuleCompiler.Program program = program(result, 0);
        ObjectNode before = context(program, """
                {"hope":{"value":2,"max":6},"stress":{"marked":4,"capacity":6},"hp":{"marked":0,"capacity":6}}""",
                "{\"type\":\"action.request\"}");
        var applied = run(program, before, StructuredRollTest.faces(4, 2), "use");
        assertEquals("Report 4.", applied.actions().getFirst().report());
        assertEquals(2, applied.contexts().getFirst().at("/actor/stress/marked").asInt());
        assertEquals(2, applied.actions().getFirst().diceRolls().size());
        assertEquals(4, applied.actions().getFirst().diceRolls().get(0).path("total").asInt());
        assertEquals(2, applied.actions().getFirst().diceRolls().get(1).path("total").asInt());

        ObjectNode full = context(program, """
                {"hope":{"value":2,"max":6},"stress":{"marked":4,"capacity":6},"hp":{"marked":5,"capacity":6}}""",
                "{\"type\":\"action.request\"}");
        ObjectNode unchanged = full.deepCopy();
        assertThrows(RuntimeException.class, () -> run(program, full, StructuredRollTest.faces(4, 2), "use"));
        assertEquals(unchanged, full);
    }

    @Test
    void rejectsMalformedUnboundedAndUnorderedReportDice() throws Exception {
        for (String expression : List.of("", "1d4oops", "1d4 2d6", "1d4++1", "1d1", "1d1001", "1025d6",
                "600d6+600d6", "1d6+1000001", "999999999999999999999999d4", "1" + "+1".repeat(256))) {
            String json = RuleJson.JSON.writeValueAsString(expression);
            var result = LegacyCatalogConverter.convert(catalog("""
                    {"id":"invalid","name":"Invalid","automation":{"actions":[{"id":"use","label":"Invalid",
                     "report":{"dice":%s,"text":"Amount {amount}."}}]}}""".formatted(json)));
            assertEquals(0, result.converted(), expression);
            assertTrue(result.diagnostics().stream().anyMatch(d -> d.path().endsWith("/report/dice")), result.diagnostics().toString());
        }
        var unordered = LegacyCatalogConverter.convert(catalog("""
                {"id":"unordered","name":"Unordered","automation":{"actions":[{"id":"use","label":"Unordered",
                 "actionRoll":{"trait":"Agility"},"report":{"dice":"1d4","text":"Amount {amount}."}}]}}"""));
        assertEquals(0, unordered.converted(), unordered.diagnostics().toString());
        assertTrue(unordered.diagnostics().stream().anyMatch(d -> d.path().endsWith("/report/dice")), unordered.diagnostics().toString());
        var trigger = LegacyCatalogConverter.convert(catalog("""
                {"id":"trigger","name":"Trigger","automation":{"rollTriggers":[{"id":"use","label":"Trigger","on":"action",
                 "report":{"dice":"1d4","text":"Amount {amount}."}}]}}"""));
        assertEquals(0, trigger.converted(), trigger.diagnostics().toString());
        assertTrue(trigger.diagnostics().stream().anyMatch(d -> d.path().endsWith("/report/dice")), trigger.diagnostics().toString());
    }

    @Test
    void reportDiceAcceptEngineBoundsAndZeroDiceWithoutRandomness() throws Exception {
        for (String expression : List.of("0d4+1", "d1000", "1000000", "1" + "+1".repeat(255), "1024d2")) {
            var result = LegacyCatalogConverter.convert(catalog("""
                    {"id":"bounded","name":"Bounded","automation":{"actions":[{"id":"use","label":"Bounded",
                     "report":{"dice":%s,"text":"Amount {amount}."}}]}}""".formatted(RuleJson.JSON.writeValueAsString(expression))));
            assertEquals(1, result.converted(), result.diagnostics().toString());
            RuleCompiler.Program program = program(result, 0);
            int count = expression.equals("1024d2") ? 1024 : expression.equals("d1000") ? 1 : 0;
            int[] faces = new int[count];
            java.util.Arrays.fill(faces, 1);
            var applied = run(program, context(program, "{}", "{\"type\":\"action.request\"}"),
                    StructuredRollTest.faces(faces), "use");
            int expected = switch (expression) {
                case "0d4+1", "d1000" -> 1;
                case "1000000" -> 1_000_000;
                case "1024d2" -> 1024;
                default -> 256;
            };
            assertEquals("Amount " + expected + ".", applied.actions().getFirst().report(), expression);
        }
    }
    @Test
    void dualityDieRerollsConvert() throws Exception {
        for (String role : new String[]{"hope", "fear"}) {
            var result = LegacyCatalogConverter.convert(catalog("""
                    {"id":"rr","name":"Reroll","automation":{"conditionalRollTriggers":[{"id":"again","label":"Reroll","on":"duality","rerollDualityDie":"%s"}]}}""".formatted(role)));
            assertEquals(1, result.converted(), result.diagnostics().toString());
            assertTrue(block(result, 0).toString().contains("\"" + role + "\""), block(result, 0).toString());
            assertNotNull(program(result, 0));
        }
    }
    @Test
    void traitHalvesToggledEffectsAndHopeAmountsConvert() throws Exception {
        var half = LegacyCatalogConverter.convert(catalog("""
                {"id":"untouchable","name":"Untouchable","automation":{"modifiers":[{"stat":"evasion","traitValue":"Agility","divisor":2,"round":"up"}]}}"""));
        assertEquals(1, half.converted(), half.diagnostics().toString());
        assertTrue(block(half, 0).toString().contains("\"ceil\""), block(half, 0).toString());
        var toggle = LegacyCatalogConverter.convert(catalog("""
                {"id":"phantom","name":"Phantom Retreat","automation":{"actions":[{"id":"use","label":"Phantom Retreat","cost":{"hope":1},
                 "toggleEffect":{"name":"Phantom Retreat","kind":"buff","until":"rest","text":"Again to end."}}]}}"""));
        assertEquals(1, toggle.converted(), toggle.diagnostics().toString());
        assertNotNull(program(toggle, 0));
        var below = LegacyCatalogConverter.convert(catalog("""
                {"id":"ngd","name":"Not Good Enough","automation":{"conditionalRollTriggers":[{"id":"reroll","label":"Reroll 1s and 2s","on":"damage","damageDice":"reroll","damageDiceBelow":3,"repeatable":true}]}}"""));
        assertEquals(1, below.converted(), below.diagnostics().toString());
        assertNotNull(program(below, 0));
        var blink = LegacyCatalogConverter.convert(catalog("""
                {"id":"blink","name":"Blink Out","automation":{"conditionalRollTriggers":[{"id":"teleport","label":"Spend Hope","on":"card:Blink Out","when":"success","spendHopeAmount":true}]}}"""));
        assertEquals(1, blink.converted(), blink.diagnostics().toString());
        assertTrue(block(blink, 0).toString().contains("Hope to spend"), block(blink, 0).toString());
    }
    @Test
    void conditionalStepsSkipWhenFalseAndCannotNameResults() throws Exception {
        String base = """
                {"rules":[{"id":"r","bindings":[{"id":"b","event":"action.request","surface":"sheet","mode":"button"}],
                  "ui":{"label":"Go"},"do":[%s]}]}""";
        RuleCompiler.compile("s", RuleJson.parse(base.formatted(
                "{\"op\":\"resource.gain\",\"pool\":\"actor.hope\",\"amount\":1,\"if\":{\"eq\":[1,1]}}")), RuleJson.parse(LegacyCatalogConverter.EMPTY_RECIPES));
        var error = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> RuleCompiler.compile("s", RuleJson.parse(base.formatted(
                "{\"op\":\"dice.roll\",\"count\":1,\"sides\":6,\"as\":\"x\",\"if\":{\"eq\":[1,1]}}")), RuleJson.parse(LegacyCatalogConverter.EMPTY_RECIPES)));
        assertTrue(error.getMessage().contains("conditional step"), error.getMessage());
    }
}
