package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * partyGrant (legacy partyDowntime, WarriorAutomation.synchronizeDowntime): the last active holder of a source shares
 * its downtime moves with every other active sheet; a holder without the class pool gets the fallback effect instead.
 */
class PartyGrantTest {
    private static final AutomationHost HOST = AutomationHost.bundled();

    private static String legacy(String pool, String effectName, String text) {
        return """
                {"format":"dmmap-daggerheart","version":1,"category":"classes","label":"Fixture","entries":[
                 {"id":"warrior","name":"Warrior","features":[{"name":"Training","type":"subclass","automation":{
                   "partyDowntime":true,
                   "resources":[{"id":"%1$s","label":"Dice","initial":0,"max":3}],
                   "actions":[{"id":"train","label":"Train together","timing":"downtime",
                     "steps":[{"op":"gain","resource":"%1$s","amount":1,
                       "fallbackEffect":{"name":"%2$s","kind":"buff","until":"manual","text":"%3$s"}}]}]}}]}]}"""
                .formatted(pool, effectName, text);
    }

    private static final class Fixture {
        final AutomationHost.Pack pack;
        final String sourceId;
        final String effectLabel;

        Fixture(String pool, String effectName) throws Exception {
            var converted = LegacyCatalogConverter.convert(legacy(pool, effectName, "Spend it once."));
            assertTrue(converted.diagnostics().isEmpty(), converted.diagnostics().toString());
            String text = converted.catalog().toString();
            pack = AutomationHost.compile("test", Map.of("classes", text), LegacyCatalogConverter.coreRecipes(),
                    LegacyCatalogConverter.coreRuleset());
            sourceId = pack.programs().values().stream().filter(RuleCompiler.Program::partyGrant)
                    .findFirst().orElseThrow().sourceId();
            effectLabel = effectName;
        }

        ObjectNode sheet(String name, boolean trainer) throws Exception {
            return (ObjectNode) RuleJson.parse("""
                    {"name":"%s","level":2,"hp":{"slots":6,"on":0},"stress":{"slots":6,"on":0},"armorTrack":{"slots":3,"on":0},
                     "hope":{"slots":6,"on":0},"features":%s,"heritage":[],"loadout":[],"vault":[],"inventory":[],
                     "activeWeapons":[],"activeArmor":null}""".formatted(name, trainer
                    ? "[{\"name\":\"Training\",\"automationSource\":\"" + sourceId + "\",\"instanceId\":\"f1\"}]" : "[]"));
        }

        Set<String> sync(Map<String, ObjectNode> active, Map<String, ObjectNode> inactive) {
            return HOST.synchronizePartyGrants(pack, active, inactive);
        }

        ObjectNode train(ObjectNode sheet, String instanceId) {
            ObjectNode origin = AutomationHost.event("action.request", "self", 0).put("sessionId", "session");
            ObjectNode prepared = HOST.prepareRest(pack, sheet, origin, "short", 0, RuleJson.JSON.createArrayNode(),
                    StructuredRollTest.noRandom());
            var progress = HOST.restProgress(pack, prepared);
            ObjectNode selection = RuleJson.JSON.createObjectNode().put("sourceInstanceId", instanceId)
                    .put("ruleId", "train").put("bindingId", "downtime");
            HOST.restMove(pack, sheet, (ObjectNode) prepared.get("event"), progress, selection,
                    RuleJson.JSON.createArrayNode(), StructuredRollTest.noRandom());
            return sheet;
        }
    }

    private static Map<String, ObjectNode> party(Object... entries) {
        Map<String, ObjectNode> map = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) map.put((String) entries[i], (ObjectNode) entries[i + 1]);
        return map;
    }

    private static JsonNode effects(ObjectNode sheet) {
        return sheet.path("automationState").path("effects");
    }

    @Test
    void converterOutputKeepsTheGrantFlagAndGatesBothBranchesOnTheGrant() throws Exception {
        var converted = LegacyCatalogConverter.convert(legacy("dice", "Die", "Spend it once."));
        assertEquals(1, converted.converted());
        JsonNode block = converted.catalog().at("/entries/0/features/0/automation");
        assertTrue(block.path("partyGrant").asBoolean(), block.toString());
        JsonNode steps = block.at("/rules/0/do");
        assertEquals("effect.start", steps.get(0).path("op").asText());
        assertEquals("source.granted", steps.get(0).at("/if/exists").asText());
        assertEquals("pool.gain", steps.get(1).path("op").asText());
        assertEquals("source.granted", steps.get(1).at("/if/not/exists").asText());
        assertEquals("source.retired", block.at("/rules/0/when/not/exists").asText());
    }

    @Test
    void theLastActiveHolderSharesTheSourceAndLosingItRemovesTheGrant() throws Exception {
        Fixture f = new Fixture("dice", "Die");
        ObjectNode ada = f.sheet("Ada", true), mira = f.sheet("Mira", false), zed = f.sheet("Zed", true);
        Map<String, ObjectNode> active = party("ada", ada, "mira", mira, "zed", zed);
        assertEquals(Set.of("mira"), f.sync(active, Map.of()));
        assertEquals(1, mira.path("partyGrants").size());
        assertEquals("grant:zed:f1", mira.at("/partyGrants/0/instanceId").asText());
        assertFalse(ada.has("partyGrants"), "Ada holds the source herself");
        assertFalse(zed.has("partyGrants"), "the grantor never receives its own grant");
        assertEquals(Set.of(), f.sync(active, Map.of()), "stable when nothing changed");

        zed.putArray("features");
        assertEquals(Set.of("mira", "zed"), f.sync(active, Map.of()));
        assertFalse(ada.has("partyGrants"));
        assertEquals("grant:ada:f1", zed.at("/partyGrants/0/instanceId").asText());
        assertEquals("grant:ada:f1", mira.at("/partyGrants/0/instanceId").asText());

        ada.putArray("features");
        f.sync(active, Map.of());
        assertFalse(mira.has("partyGrants"));
        assertFalse(zed.has("partyGrants"));
    }

    @Test
    void grantsSurviveWhileTheGrantorIsOnlyDisabled() throws Exception {
        Fixture f = new Fixture("dice", "Die");
        ObjectNode ada = f.sheet("Ada", true), mira = f.sheet("Mira", false);
        f.sync(party("ada", ada, "mira", mira), Map.of());
        assertEquals(1, mira.path("partyGrants").size());
        Map<String, ObjectNode> active = party("mira", mira);
        assertEquals(Set.of(), f.sync(active, party("ada", ada)));
        assertEquals(1, mira.path("partyGrants").size(), "disabled grantor keeps the grant, like the legacy sync");
        assertEquals(Set.of("mira"), f.sync(active, Map.of()));
        assertFalse(mira.has("partyGrants"));
    }

    @Test
    void aGrantInUseIsRetiredNotLostAndStopsOfferingItsMove() throws Exception {
        Fixture f = new Fixture("dice", "Die");
        ObjectNode ada = f.sheet("Ada", true), mira = f.sheet("Mira", false);
        Map<String, ObjectNode> active = party("ada", ada, "mira", mira);
        f.sync(active, Map.of());
        f.train(mira, "grant:ada:f1");
        assertEquals(1, effects(mira).size(), mira.toString());
        ada.putArray("features");
        f.sync(active, Map.of());
        assertTrue(mira.at("/partyGrants/0/retired").asBoolean(), "the effect still points at the grant");
    }

    @Test
    void aGranteeGetsTheFallbackEffectWhileTheHolderGainsThePool() throws Exception {
        Fixture f = new Fixture("dice", "Die");
        ObjectNode ada = f.sheet("Ada", true), mira = f.sheet("Mira", false);
        f.sync(party("ada", ada, "mira", mira), Map.of());
        f.train(mira, "grant:ada:f1");
        assertEquals(1, effects(mira).size(), mira.toString());
        assertEquals("Die", effects(mira).get(0).path("label").asText());
        assertFalse(mira.toString().contains("\"dice\":{"), "no pool appears on the grantee: " + mira);

        f.train(ada, "f1");
        assertEquals(1, ada.at("/automationState/pools/dice/value").asInt(), ada.toString());
        assertEquals(0, effects(ada).size(), "the holder gains the pool and no fallback effect");
    }

    @Test
    void aDifferentPoolAndEffectBehaveTheSameWay() throws Exception {
        Fixture f = new Fixture("charges", "Charge");
        ObjectNode ada = f.sheet("Ada", true), kit = f.sheet("Kit", false);
        f.sync(party("ada", ada, "kit", kit), Map.of());
        f.train(kit, "grant:ada:f1");
        assertEquals("Charge", effects(kit).get(0).path("label").asText());
        f.train(ada, "f1");
        assertEquals(1, ada.at("/automationState/pools/charges/value").asInt(), ada.toString());
        assertEquals(0, effects(ada).size());
    }

}
