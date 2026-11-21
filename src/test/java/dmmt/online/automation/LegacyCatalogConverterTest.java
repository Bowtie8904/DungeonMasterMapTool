package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
        var sources = RuleCatalog.compile(result.catalog().toString(), LegacyCatalogConverter.EMPTY_RECIPES);
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
                {"id":"e","name":"E","automation":{"modifiers":[{"stat":"evasion","value":1,"hopeMin":2}]}}"""));
        assertEquals(5, result.blocks());
        assertEquals(0, result.converted());
        var messages = result.diagnostics().stream().map(d -> d.sourceId() + " " + d.path() + " " + d.message()).toList();
        assertTrue(messages.contains("classes:a /entries/0/automation/actions/0/cost/favor Custom resource cost needs a declared pool: favor"), messages.toString());
        assertTrue(messages.contains("classes:b /entries/1/automation/rollOptions/0/target Unsupported legacy field: target"), messages.toString());
        assertTrue(messages.stream().anyMatch(m -> m.startsWith("classes:c /entries/2/automation/actions/0/steps/0/amount")), messages.toString());
        assertTrue(messages.contains("classes:d /entries/3/automation/uses A use limit without rules has nothing to limit."), messages.toString());
        assertTrue(messages.contains("classes:e /entries/4/automation/modifiers/0/hopeMin Unsupported legacy field: hopeMin"), messages.toString());
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
        assertTrue(Files.readString(legacy.resolve("classes.json")).contains("\"version\":1"));
    }

    private static JsonNode block(LegacyCatalogConverter.Result result, int entry) {
        assertEquals(result.blocks(), result.converted(), result.diagnostics().toString());
        return result.catalog().at("/entries/" + entry + "/automation");
    }

    private static RuleCompiler.Program program(LegacyCatalogConverter.Result result, int entry) throws Exception {
        return RuleCatalog.compile(result.catalog().toString(), LegacyCatalogConverter.EMPTY_RECIPES).get(entry).program();
    }

    private static ObjectNode context(RuleCompiler.Program program, String actor, String event) throws Exception {
        ObjectNode context = (ObjectNode) RuleJson.parse("""
                {"source":{"id":"%s","instanceId":"fixture","name":"Fixture"},"actor":%s,"revision":0}
                """.formatted(program.sourceId(), actor));
        context.set("event", RuleJson.parse(event));
        return context;
    }

    private static RuleDispatcher.EventResolution run(RuleCompiler.Program program, ObjectNode context, String... rules) {
        String binding = switch (context.at("/event/type").asText()) {
            case "action.request" -> "sheet";
            case "downtime.move" -> "downtime";
            default -> "use";
        };
        List<RuleRuntime.Intent> intents = java.util.Arrays.stream(rules)
                .map(rule -> new RuleRuntime.Intent(rule, binding, "fixture", context.path("revision").asLong(), RuleJson.JSON.createObjectNode())).toList();
        return RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, context)), intents, RandomGenerator.getDefault());
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
        RuleCompiler.Program program = program(result, 0);
        ObjectNode downtime = context(program, "{\"stress\":{\"marked\":2,\"capacity\":6}}", "{\"type\":\"downtime.move\"}");
        assertEquals(List.of("later", "rest"), available(program, downtime, "downtime"));
        assertEquals(List.of(), available(program, context(program, "{}", "{\"type\":\"action.request\"}"), "sheet"));
        assertEquals(1, run(program, downtime, "rest").contexts().getFirst().at("/actor/stress/marked").asInt());
        assertFalse(LegacyCatalogConverter.convert(catalog("""
                {"id":"x","name":"X","automation":{"actions":[{"id":"a","label":"A","timing":"combat"}]}}""")).diagnostics().isEmpty());
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
                {"id":"hit","contributions":[{"id":"weapon","kind":"dice","role":"damage","sourceId":"%s","label":"Weapon","dice":[]},
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
        assertEquals("event.check.weapon", rules.at("/1/when/and/1/exists").asText());
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
        assertTrue(missing.diagnostics().stream().anyMatch(d -> d.message().contains("Elsewhere")), missing.diagnostics().toString());
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
        var intent = new RuleRuntime.Intent(rule, "sheet", "fixture", context.path("revision").asLong(), (ObjectNode) RuleJson.parse(inputs));
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
        assertEquals("actor.proficiency.value", heal.at("/do/0/amount/read").asText());
        assertEquals("dice.roll", heal.at("/do/1/op").asText());
        assertEquals(4, heal.at("/do/1/sides").asInt());
        assertEquals("Cleared {actor.proficiency.value}.", heal.at("/ui/report").asText());
        assertEquals("actor.hope.value", block.at("/rules/1/costs/0/amount/read").asText());

        RuleCompiler.Program program = program(result, 0);
        ObjectNode context = context(program, """
                {"hope":{"value":3,"max":6},"proficiency":{"value":2},"hp":{"marked":5,"capacity":6},
                 "stress":{"marked":6,"capacity":6}}""", "{\"type\":\"action.request\"}");
        ObjectNode after = run(program, context, "heal").contexts().getFirst();
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
}