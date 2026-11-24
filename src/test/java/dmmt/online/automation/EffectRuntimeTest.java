package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dmmt.online.automation.RuleConformanceTest.fixture;
import static dmmt.online.automation.StructuredRollTest.noRandom;
import static org.junit.jupiter.api.Assertions.*;

class EffectRuntimeTest {
    private static RuleCompiler.Program program() throws Exception {
        return RuleCompiler.compile("features:stance-example", fixture("rewrite/effects"), fixture("rewrite/recipes"));
    }

    private static ObjectNode context() throws Exception {
        return (ObjectNode) RuleJson.parse("""
                {"source":{"id":"features:stance-example","instanceId":"inst-1","name":"Shieldwall"},
                 "actor":{"id":"actor","name":"Player"},
                 "recipient":{"id":"enemy","name":"Foe"},
                 "revision":0,"event":{"type":"action.request"}}
                """);
    }

    private static RuleRuntime.Resolution run(RuleCompiler.Program program, String rule, ObjectNode context, String inputs) throws Exception {
        String binding = program.rule(rule).path("bindings").get(0).path("id").asText();
        return RuleRuntime.execute(program, new RuleRuntime.Intent(rule, binding, "inst-1", context.path("revision").asLong(),
                (ObjectNode) RuleJson.parse(inputs)), context, noRandom());
    }

    @Test
    void effectsCarryAValidatedOnlineConditionAndMovementBlock() throws Exception {
        String block = """
                {"effects":{"veil":{"label":"Veil","blocksMovement":true,"onlineCondition":{"id":"hidden","iconId":"cond_invisible"}}},
                 "rules":[{"id":"cast","bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button"}],
                   "do":[{"op":"effect.start","effect":"veil","target":"actor"}],"ui":{"label":"Veil"}}]}""";
        RuleCompiler.Program veil = RuleCompiler.compile("features:veil", RuleJson.parse(block), fixture("rewrite/recipes"));
        ObjectNode veilContext = context();
        veilContext.withObject("/source").put("id", "features:veil");
        JsonNode effect = run(veil, "cast", veilContext, "{}").context().path("actor").path("effects").get(0);
        assertEquals("hidden", effect.path("onlineCondition").path("id").asText(), effect.toString());
        assertEquals("cond_invisible", effect.path("onlineCondition").path("iconId").asText());
        assertTrue(effect.path("blocksMovement").asBoolean());
        for (String bad : new String[]{"{\"id\":\"Bad Id\"}", "{\"id\":\"hidden\",\"iconId\":\"x\"}", "{\"id\":\"hidden\",\"tokenId\":\"t\"}"}) {
            assertThrows(RuntimeException.class, () -> RuleCompiler.compile("features:veil",
                    RuleJson.parse(block.replaceFirst("\\{\"id\":\"hidden\",\"iconId\":\"cond_invisible\"\\}", bad)), fixture("rewrite/recipes")), bad);
        }
    }

    @Test
    void startedEffectFreezesRenderedTextAndModifiersAndCountsAreExposed() throws Exception {
        RuleCompiler.Program program = program();
        ObjectNode before = context();
        assertEquals("Guard (0 active)", RuleRuntime.describe(program, "guard", "sheet", before).path("label").asText());
        var result = run(program, "guard", before, "{}");
        JsonNode effect = result.context().path("actor").path("effects").get(0);
        assertFalse(effect.required("id").asText().isBlank());
        assertEquals("inst-1", effect.required("sourceInstanceId").asText());
        assertEquals("features:stance-example#guarded", effect.path("effect").asText());
        assertEquals("Shieldwall: guarded", effect.path("label").asText());
        assertEquals("+2 Evasion until your next rest.", effect.path("text").asText());
        assertEquals("[\"rest.short\",\"rest.long\"]", effect.path("until").toString());
        assertEquals(2, effect.path("modifiers").get(0).path("value").asInt());
        assertEquals("Shieldwall", effect.path("modifiers").get(0).path("label").asText());
        assertEquals("Shieldwall: guarded", result.report());
        assertEquals("Guard (1 active)", RuleRuntime.describe(program, "guard", "sheet", result.context()).path("label").asText());
        assertFalse(RuleRuntime.describe(program, "guard", "sheet", result.context()).path("available").asBoolean());
    }

    @Test
    void stackingPoliciesReplaceStackAndRejectDuplicates() throws Exception {
        RuleCompiler.Program program = program();
        ObjectNode state = context();
        for (int i = 0; i < 3; i++) state = run(program, "focus", state, "{}").context();
        assertEquals(3, state.path("actor").path("effects").size());
        List<String> originalIds = state.path("actor").path("effects").findValuesAsText("id");
        assertEquals(3, originalIds.stream().distinct().count());

        state = run(program, "oath", state, "{}").context();
        ObjectNode sworn = state;
        assertThrows(RuleRuntime.Unavailable.class, () -> run(program, "oath", sworn, "{}"));

        ObjectNode other = state.deepCopy();
        other.withObject("/source").put("instanceId", "inst-2");
        assertEquals(0, RuleRuntime.context(program, null, other).path("effects").path("focus").asInt());

        var released = run(program, "release", state, "{}");
        assertEquals("Ended 3 focus.", released.report());
        assertEquals(1, released.context().path("actor").path("effects").size());
        assertFalse(RuleRuntime.describe(program, "release", "sheet", released.context()).path("available").asBoolean());
        var recast = run(program, "focus", released.context(), "{}").context();
        assertFalse(originalIds.contains(recast.requiredAt("/actor/effects/1/id").textValue()),
                "An expired result must never address a new activation of the same effect.");
    }

    @Test
    void endLabeledRemovesEffectsOfAnySourceByLabelOnly() throws Exception {
        ObjectNode state = context();
        ArrayNode effects = state.withObject("/actor").putArray("effects");
        effects.addObject().put("id", "a").put("effect", "other#x").put("label", "Wolf Form");
        effects.addObject().put("id", "b").put("effect", "third#y").put("label", "Wolf Form");
        effects.addObject().put("id", "c").put("effect", "third#z").put("label", "Calm");
        assertEquals(2, RuleEffects.endLabeled(program(), "Wolf Form", "actor", state));
        assertEquals(1, state.path("actor").path("effects").size());
        assertEquals("Calm", state.at("/actor/effects/0/label").asText());
    }

    @Test
    void limitResetRestoresUseBudget() throws Exception {
        RuleCompiler.Program program = program();
        ObjectNode used = run(program, "guard", context(), "{}").context();
        assertEquals(1, used.path("usage").path("per-rest").asInt());
        var refreshed = run(program, "refresh", used, "{}");
        assertEquals("Reset 1 use.", refreshed.report());
        assertEquals(0, refreshed.context().path("usage").path("per-rest").asInt());
    }

    @Test
    void recipientEffectsRequireClaimGroupAndLandOnRecipient() throws Exception {
        RuleCompiler.Program program = program();
        var marked = run(program, "mark", context(), "{}");
        assertEquals("Marked by Shieldwall", marked.context().path("recipient").path("effects").get(0).path("label").asText());
        assertTrue(marked.context().path("actor").path("effects").isMissingNode());

        ObjectNode automation = fixture("rewrite/effects");
        ((ObjectNode) automation.withArray("rules").get(5).path("bindings").get(0)).remove("claimGroup");
        assertThrows(IllegalArgumentException.class, () -> RuleCompiler.compile("features:x", automation, fixture("rewrite/recipes")));
    }

    @Test
    void derivedEffectsFollowTheirConditionAndExclusiveGroupsReplaceAcrossSources() throws Exception {
        String rules = """
                {"effects":{
                   "stance":{"label":"Stance","kind":"buff","exclusiveGroup":"stance"},
                   "boost":{"label":"Boost","kind":"buff","while":{"includes":[{"pluck":[{"read":"actor.effects"},"label"]},"Stance"]},
                            "modifiers":[{"stat":"evasion","value":1}]}},
                 "grants":{"features":["A","B"],"display":"grouped"},
                 "rules":[{"id":"take","bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button"}],
                           "do":[{"op":"effect.start","effect":"stance","target":"actor"}],"ui":{"label":"Take"}}]}""";
        ObjectNode recipes = fixture("rewrite/recipes");
        RuleCompiler.Program program = RuleCompiler.compile("features:x", RuleJson.parse(rules), recipes);
        assertEquals("[\"A\",\"B\"]", program.grants().path("features").toString());
        ObjectNode context = context();
        context.withObject("/source").put("id", "features:x");
        context.withObject("/actor").putArray("effects");
        assertTrue(RuleEffects.derived(program, context).isEmpty());

        ObjectNode other = (ObjectNode) RuleJson.parse("{\"id\":\"y/stance/1\",\"effect\":\"features:y#stance\",\"sourceInstanceId\":\"y\",\"label\":\"Old\",\"group\":\"stance\",\"modifiers\":[]}");
        context.withObject("/actor").putArray("effects").add(other);
        ObjectNode after = run(program, "take", context, "{}").context();
        assertEquals(List.of("Stance"), after.path("actor").path("effects").findValuesAsText("label"), "a group replaces effects from any source");
        ArrayNode derived = RuleEffects.derived(program, after);
        assertEquals("inst-1/boost/derived", derived.get(0).path("id").asText());
        assertTrue(derived.get(0).path("derived").asBoolean());
        ArrayNode all = ((ArrayNode) after.path("actor").path("effects")).deepCopy().addAll(derived);
        assertEquals(1, RuleEffects.stored(all).size());

        for (String bad : List.of(
                "\"while\":true,\"until\":[\"rest.short\"]",
                "\"while\":true,\"exclusiveGroup\":\"x\"",
                "\"while\":{\"read\":\"event.type\"}",
                "\"exclusiveGroup\":\"Not An Id\""))
            assertThrows(IllegalArgumentException.class, () -> RuleCompiler.compile("features:x", RuleJson.parse(
                    "{\"effects\":{\"e\":{\"label\":\"E\"," + bad + "}}}"), recipes), bad);
        assertThrows(IllegalArgumentException.class, () -> RuleCompiler.compile("features:x", RuleJson.parse(
                rules.replace("\"effect\":\"stance\"", "\"effect\":\"boost\"")), recipes), "derived effects cannot be started");
        assertThrows(IllegalArgumentException.class, () -> RuleCompiler.compile("features:x", RuleJson.parse(
                rules.replace("\"display\":\"grouped\"", "\"display\":\"stacked\"")), recipes));
    }

    @Test
    void expiryPassivesAndStatisticComposition() throws Exception {
        RuleCompiler.Program program = program();
        ObjectNode state = run(program, "guard", context(), "{}").context();
        state = run(program, "focus", state, "{}").context();
        state = run(program, "focus", state, "{}").context();
        state = run(program, "oath", state, "{}").context();
        JsonNode effects = state.path("actor").path("effects");

        assertEquals(3, RuleEffects.expire(effects, "rest.short").size());
        assertEquals(3, RuleEffects.expire(effects, "scene.end").size());
        assertEquals(4, RuleEffects.expire(effects, "session.start").size());
        assertThrows(IllegalArgumentException.class, () -> RuleEffects.expire(effects, "nope"));

        ArrayNode modifiers = RuleEffects.modifiers(effects);
        assertEquals(12, RuleEffects.stat(10, "evasion", modifiers));
        assertEquals(2, RuleEffects.stat(0, "roll.attack", modifiers));
        assertEquals(10, RuleEffects.stat(7, "thresholds.major", modifiers));
        assertEquals(12, RuleEffects.stat(12, "thresholds.major", modifiers));

        ArrayNode passives = RuleEffects.passives(program, state);
        assertEquals(2, passives.size());
        assertEquals("Shieldwall", passives.get(0).path("label").asText());
        assertEquals(2, RuleEffects.stat(1, "trait.Agility", List.of(passives.get(1))) - 1);
        assertEquals(1, RuleEffects.passives(program, context()).size());

        ArrayNode composed = RuleJson.JSON.createArrayNode();
        composed.addObject().put("stat", "evasion").put("op", "set").put("value", 5);
        composed.addObject().put("stat", "evasion").put("value", 3);
        composed.addObject().put("stat", "evasion").put("op", "atMost").put("value", 7);
        assertEquals(7, RuleEffects.stat(10, "evasion", composed));
    }

    @Test
    void choiceInputsFilterOptionsAndValidateSelections() throws Exception {
        RuleCompiler.Program program = program();
        ObjectNode context = context();
        JsonNode input = RuleRuntime.describe(program, "stance", "sheet", context).path("inputs").get(0);
        assertEquals("choice", input.path("kind").asText());
        assertEquals(2, input.path("options").size());
        assertEquals("Guard (tier 2)", input.path("options").get(0).path("label").asText());
        assertEquals("Chose focus.", run(program, "stance", context, "{\"pick\":\"focus\"}").report());
        assertThrows(IllegalArgumentException.class, () -> run(program, "stance", context, "{\"pick\":\"veteran\"}"));
        assertThrows(IllegalArgumentException.class, () -> run(program, "stance", context, "{\"pick\":[\"focus\"]}"));

        ObjectNode veteran = context.deepCopy();
        veteran.putObject("state").put("tier", 3);
        assertEquals("Chose veteran.", run(program, "stance", veteran, "{\"pick\":\"veteran\"}").report());

        assertEquals("Trained 2 traits.", run(program, "traits", context, "{\"traits\":[\"agility\",\"finesse\"]}").report());
        assertThrows(IllegalArgumentException.class, () -> run(program, "traits", context, "{\"traits\":[\"agility\",\"agility\"]}"));
        assertThrows(IllegalArgumentException.class, () -> run(program, "traits", context, "{\"traits\":[\"agility\"]}"));
    }

    @Test
    void compilerRejectsInvalidEffectDeclarations() throws Exception {
        String[] invalid = {
                "{\"effects\":{\"a\":{\"label\":\"A\",\"stacking\":\"many\"}},\"modifiers\":[{\"stat\":\"evasion\",\"value\":1}]}",
                "{\"effects\":{\"a\":{\"label\":\"A\",\"until\":[\"sunset\"]}},\"modifiers\":[{\"stat\":\"evasion\",\"value\":1}]}",
                "{\"modifiers\":[{\"stat\":\"luck\",\"value\":1}]}",
                "{\"modifiers\":[{\"stat\":\"evasion\",\"op\":\"min\",\"value\":1}]}",
                "{\"modifiers\":[{\"stat\":\"evasion\",\"value\":{\"read\":\"input.x\"}}]}",
                "{\"effects\":{\"a\":{\"label\":\"A\",\"modifiers\":[{\"stat\":\"evasion\",\"value\":1,\"when\":true}]}},\"modifiers\":[{\"stat\":\"evasion\",\"value\":1}]}",
                "{\"rules\":[{\"id\":\"r\",\"bindings\":[{\"id\":\"b\",\"event\":\"action.request\",\"surface\":\"sheet\",\"mode\":\"button\"}],\"do\":[{\"op\":\"effect.start\",\"effect\":\"missing\",\"target\":\"actor\"}],\"ui\":{\"label\":\"x\"}}]}",
                "{\"rules\":[{\"id\":\"r\",\"bindings\":[{\"id\":\"b\",\"event\":\"action.request\",\"surface\":\"sheet\",\"mode\":\"button\"}],\"do\":[{\"op\":\"limit.reset\",\"limit\":\"missing\"}],\"ui\":{\"label\":\"x\"}}]}",
                "{\"rules\":[{\"id\":\"r\",\"bindings\":[{\"id\":\"b\",\"event\":\"action.request\",\"surface\":\"sheet\",\"mode\":\"button\"}],\"do\":[{\"op\":\"value\",\"value\":{\"read\":\"effects.missing\"}}],\"ui\":{\"label\":\"x\"}}]}",
                "{}"
        };
        for (String document : invalid)
            assertThrows(IllegalArgumentException.class,
                    () -> RuleCompiler.compile("features:x", RuleJson.parse(document), fixture("rewrite/recipes")), document);
        RuleCompiler.Program passiveOnly = RuleCompiler.compile("features:x",
                RuleJson.parse("{\"modifiers\":[{\"stat\":\"hp.max\",\"value\":1}]}"), fixture("rewrite/recipes"));
        assertTrue(passiveOnly.ruleIds().isEmpty());
    }
}
