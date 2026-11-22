package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static dmmt.online.automation.RuleConformanceTest.fixture;
import static org.junit.jupiter.api.Assertions.*;

class AutomationHostTest {
    private static final AutomationHost HOST = AutomationHost.bundled();

    private static AutomationHost.Pack pack() throws Exception {
        return AutomationHost.compile("test", Map.of(
                "classes", fixture("rewrite/host-classes").toString(),
                "consumables", fixture("rewrite/consumables").toString()), fixture("rewrite/recipes").toString());
    }

    private static ObjectNode sheet() throws Exception {
        return (ObjectNode) RuleJson.parse("""
                {"name":"Ada","level":2,"evasion":10,"gold":5,
                 "hp":{"slots":6,"total":12,"on":2},"stress":{"slots":6,"total":12,"on":1},
                 "armorTrack":{"slots":3,"total":12,"on":0},"hope":{"slots":6,"total":6,"on":1},
                 "proficiency":{"slots":6,"total":6,"on":1},"traits":[{"name":"Agility","mod":1,"marked":false}],
                 "features":[
                   {"name":"Rally","text":"","automationSource":"classes:tester:features:rally","instanceId":"f-rally"},
                   {"name":"Forage","text":"","automationSource":"classes:tester:features:forage","instanceId":"f-forage"},
                   {"name":"Steady","text":"","automationSource":"classes:tester:features:steady","instanceId":"f-steady"},
                   {"name":"Plain","text":"No automation"}],
                 "heritage":[],"loadout":[],"vault":[
                   {"name":"Vaulted","automationSource":"classes:tester:features:rally","instanceId":"v-rally"}],
                 "inventory":[
                   {"name":"Minor Health Potion","qty":1,"type":"consumable","automationSource":"consumables:minor-health-potion","instanceId":"i-potion"},
                   {"name":"Rope","qty":1,"type":"gear"}],
                 "activeWeapons":[]}""");
    }

    private static ObjectNode action() {
        return RuleJson.JSON.createObjectNode().put("type", "action.request");
    }

    private static ObjectNode request(String id, long revision, String instance, String rule) {
        ObjectNode request = RuleJson.JSON.createObjectNode().put("requestId", id).put("expectedRevision", revision);
        request.putArray("selections").addObject().put("sourceInstanceId", instance).put("ruleId", rule).put("bindingId", "sheet");
        return request;
    }

    private static List<String> labels(ObjectNode described) {
        return described.path("actions").findValuesAsText("label");
    }

    @Test
    void describesOnlyActiveSourcesVisibleToTheViewer() throws Exception {
        var pack = pack();
        ObjectNode sheet = sheet();
        assertEquals(List.of("Forage", "Rally (1 Hope)", "Drink Minor Health Potion"), labels(HOST.describe(pack, sheet, action(), "sheet", "self")));
        assertEquals(List.of("Rally (1 Hope)"), labels(HOST.describe(pack, sheet, action(), "sheet", "other")));
        assertEquals(List.of(), labels(HOST.describe(pack, sheet, action(), "sheet", "dm")));
        JsonNode profile = HOST.describe(pack, sheet, action(), "sheet", "self").path("profiles").get(0);
        assertEquals("f-steady", profile.path("sourceInstanceId").asText());
        assertEquals("Steady", profile.path("sourceName").asText());
        assertEquals("thrown", profile.path("id").asText());
        assertEquals(6, profile.at("/damage/sides").asInt());
        assertEquals(sheet(), sheet, "describing must not change the sheet");
    }

    @Test
    void executesWritesBackJournalsAndRejectsStaleOrRepeatedUse() throws Exception {
        var pack = pack();
        ObjectNode sheet = sheet();
        ObjectNode response = HOST.execute(pack, sheet, action(), request("r1", 0, "f-rally", "rally"), "self", new SplittableRandom(1));
        assertEquals(4, sheet.path("hope").path("on").asInt(), "Rally +2 and the automatic Steady +1");
        assertEquals(1, response.path("revision").asLong());
        assertEquals(List.of("Steady.", "Ada rallied for 2 Hope."), response.path("actions").findValuesAsText("report"));
        assertEquals("countdown.start", response.path("outbox").get(0).path("kind").asText());
        assertEquals("Rally from Ada", response.path("outbox").get(0).path("label").asText());
        assertEquals(1, sheet.path("automationState").path("sources").path("f-rally").path("usage").path("once").asInt());

        ObjectNode replay = HOST.execute(pack, sheet, action(), request("r1", 0, "f-rally", "rally"), "self", new SplittableRandom(1));
        assertEquals(response, replay);
        assertEquals(4, sheet.path("hope").path("on").asInt(), "a repeated request ID must not run twice");
        assertThrows(RuleRuntime.Unavailable.class, () -> HOST.execute(pack, sheet, action(), request("r1", 0, "f-rally", "rally"),
                "self", "another-player", new SplittableRandom(1)));

        var stale = assertThrows(RuleRuntime.Unavailable.class,
                () -> HOST.execute(pack, sheet, action(), request("r2", 0, "f-rally", "rally"), "self", null));
        assertEquals("stale", stale.code());
        var spent = assertThrows(RuleRuntime.Unavailable.class,
                () -> HOST.execute(pack, sheet, action(), request("r3", 1, "f-rally", "rally"), "self", null));
        assertEquals("limit", spent.code());
        assertEquals(4, sheet.path("hope").path("on").asInt(), "failed requests leave the sheet unchanged");

        assertTrue(HOST.resetLimits(pack, sheet, "rest.short"));
        assertEquals(2, HOST.revision(sheet));
        assertFalse(HOST.resetLimits(pack, sheet, "rest.short"));
        HOST.execute(pack, sheet, action(), request("r4", 2, "f-rally", "rally"), "other", null);
        assertEquals(6, sheet.path("hope").path("on").asInt(), "Hope is capped by its slots");
    }

    @Test
    void refreshRequestsClearOtherSourcesRestLimitsButNotPools() throws Exception {
        String tea = LegacyCatalogConverter.convert("""
                {"format":"dmmap-daggerheart","version":1,"category":"consumables","label":"Fixture","entries":[
                  {"id":"minor-health-potion","name":"Tea","automation":{"actions":[{"id":"drink","label":"Drink","refreshUses":"short"}]}}]}""")
                .catalog().toString();
        var pack = AutomationHost.compile("test", Map.of("classes", fixture("rewrite/host-classes").toString(), "consumables", tea),
                fixture("rewrite/recipes").toString());
        ObjectNode sheet = sheet();
        HOST.execute(pack, sheet, action(), request("r1", 0, "f-rally", "rally"), "self", new SplittableRandom(1));
        assertEquals(1, sheet.at("/automationState/sources/f-rally/usage/once").asInt());
        ObjectNode response = HOST.execute(pack, sheet, action(), request("t1", 1, "i-potion", "drink"), "self", null);
        assertEquals("limits.refresh", response.at("/outbox/0/kind").asText(), response.toString());
        assertEquals(0, sheet.at("/automationState/sources/f-rally/usage/once").asInt(), sheet.path("automationState").toString());
        assertEquals(List.of("Rope"), sheet.path("inventory").findValuesAsText("name"), "The tea is used up.");
    }

    @Test
    void writesTracksCollectionsAndConsumedSourcesThroughTheProjection() throws Exception {
        var pack = pack();
        ObjectNode sheet = sheet();
        assertThrows(SecurityException.class,
                () -> HOST.execute(pack, sheet, action(), request("x", 0, "f-forage", "forage"), "other", null));
        HOST.execute(pack, sheet, action(), request("f1", 0, "f-forage", "forage"), "self", null);
        assertEquals(2, sheet.path("stress").path("on").asInt());
        assertEquals(7, sheet.path("hp").path("slots").asInt());
        assertEquals(12, sheet.path("hp").path("total").asInt());
        var berries = sheet.path("inventory").get(2);
        assertEquals("Berries", berries.path("name").asText());
        assertEquals(2, berries.path("qty").asInt());
        assertEquals("loot", berries.path("type").asText());
        assertEquals("f-forage", berries.path("automationGrantedBy").asText());
        assertEquals("Rope", sheet.path("inventory").get(1).path("name").asText());

        HOST.execute(pack, sheet, action(), request("p1", 1, "i-potion", "drink"), "self", new SplittableRandom(3));
        assertTrue(sheet.path("hp").path("on").asInt() < 2);
        assertEquals(List.of("Rope", "Berries"), sheet.path("inventory").findValuesAsText("name"));
        assertFalse(sheet.path("automationState").path("sources").has("i-potion"));
    }

    @Test
    void projectionRejectsWritesToReadOnlyOrOutOfBoundsFacts() throws Exception {
        ObjectNode sheet = sheet();
        ((ObjectNode) sheet.path("traits").get(0)).put("spellcast", true);
        ObjectNode original = sheet.deepCopy();
        SheetProjection projection = HOST.projection();
        ObjectNode before = projection.actor(sheet);
        assertEquals(1, before.path("traits").path("Agility").asInt());
        assertEquals("Agility", before.at("/spellcastTrait/name").asText());
        assertEquals(1, before.at("/spellcastTrait/value").asInt());
        assertEquals(6, before.path("hope").path("max").asInt());
        ObjectNode level = before.deepCopy().put("level", 9);
        assertThrows(IllegalArgumentException.class, () -> projection.write(sheet, before, level));
        ObjectNode spellcast = before.deepCopy();
        ((ObjectNode) spellcast.get("spellcastTrait")).put("value", 2);
        assertThrows(IllegalArgumentException.class, () -> projection.write(sheet, before, spellcast));
        ObjectNode hopeMax = before.deepCopy();
        ((ObjectNode) hopeMax.get("hope")).put("max", 9);
        assertThrows(IllegalArgumentException.class, () -> projection.write(sheet, before, hopeMax));
        ObjectNode gold = before.deepCopy().put("gold", -1);
        assertThrows(IllegalArgumentException.class, () -> projection.write(sheet, before, gold));
        assertEquals(original, sheet);
    }

    @Test
    void projectionDerivesBoundedStatisticsAndAppliesMatchingModifiers() throws Exception {
        ObjectNode sheet = sheet().put("level", 5);
        ((ObjectNode) sheet.path("traits").get(0)).put("spellcast", true);
        ((ObjectNode) sheet.get("proficiency")).put("on", 0);
        SheetProjection projection = HOST.projection();
        ObjectNode actor = projection.actor(sheet);
        ObjectNode base = projection.stats(actor, List.of());
        assertEquals(3, base.path("tier").asInt());
        assertEquals(1, base.at("/trait/Agility").asInt());
        assertEquals(0, base.at("/trait/Knowledge").asInt());
        assertEquals(1, base.at("/spellcast/total").asInt());
        assertEquals(1, base.path("proficiency").asInt(), "Proficiency is never below 1");
        assertEquals(10, base.path("evasion").asInt());
        assertEquals(5, base.at("/thresholds/major").asInt());
        assertEquals(10, base.at("/thresholds/severe").asInt());
        assertFalse(actor.has("stats"), "Statistics are derived on a copy");

        List<com.fasterxml.jackson.databind.JsonNode> modifiers = List.of(
                RuleJson.parse("{\"stat\":\"trait.Agility\",\"value\":2}"),
                RuleJson.parse("{\"stat\":\"evasion\",\"op\":\"add\",\"value\":1}"),
                RuleJson.parse("{\"stat\":\"proficiency\",\"op\":\"atMost\",\"value\":-3}"));
        ObjectNode modified = projection.stats(actor, modifiers);
        assertEquals(3, modified.at("/trait/Agility").asInt());
        assertEquals(3, modified.at("/spellcast/total").asInt(), "Later statistics read modified earlier ones");
        assertEquals(1, modified.at("/spellcast/base").asInt());
        assertEquals(11, modified.path("evasion").asInt());
        assertEquals(1, modified.path("proficiency").asInt(), "Declared bounds apply after modifiers");
    }

    @Test
    void projectionRejectsReservedOrForeignStatistics() throws Exception {
        String bundled;
        try (var in = SheetProjection.class.getResourceAsStream("daggerheart-projection.json")) {
            bundled = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        ObjectNode reserved = (ObjectNode) RuleJson.parse(bundled);
        ((ObjectNode) reserved.get("actor")).putObject("stats").put("kind", "value").put("path", "level");
        assertThrows(IllegalArgumentException.class, () -> SheetProjection.parse(reserved.toString()));
        ObjectNode foreign = (ObjectNode) RuleJson.parse(bundled);
        ((com.fasterxml.jackson.databind.node.ArrayNode) foreign.get("stats")).addObject().put("id", "leak")
                .putObject("value").put("read", "event.total");
        assertThrows(IllegalArgumentException.class, () -> SheetProjection.parse(foreign.toString()));
        ObjectNode grouped = (ObjectNode) RuleJson.parse(bundled);
        ((com.fasterxml.jackson.databind.node.ArrayNode) grouped.get("stats")).addObject().put("id", "trait").put("value", 1);
        assertThrows(IllegalArgumentException.class, () -> SheetProjection.parse(grouped.toString()));
    }

    @Test
    void timedExpiryClearsOnlyPoolsWhoseLastSupportingEffectEnded() throws Exception {
        ObjectNode catalog = fixture("rewrite/host-classes");
        ObjectNode automation = (ObjectNode) catalog.at("/entries/0/features/2/automation");
        automation.putObject("effects").putObject("stance").put("label", "Stance");
        automation.putObject("pools").putObject("charge").put("kind", "number").put("scope", "actor")
                .put("whileEffect", "stance").put("max", 4);
        var pack = AutomationHost.compile("test", Map.of("classes", catalog.toString()), fixture("rewrite/recipes").toString());
        ObjectNode sheet = sheet();
        ObjectNode state = HOST.projection().state(sheet);
        ObjectNode pools = state.putObject("pools");
        pools.putObject("charge").put("value", 3).put("max", 4);
        pools.putObject("other").put("value", 2);
        var effects = state.putArray("effects");
        for (String end : List.of("scene.end", "session.start"))
            effects.addObject().put("id", end).put("effect", "classes:tester:features:steady#stance")
                    .put("sourceInstanceId", "f-steady").putArray("until").add(end);
        assertTrue(HOST.resetLimits(pack, sheet, "scene.end"));
        assertEquals(3, sheet.at("/automationState/pools/charge/value").intValue());
        assertTrue(HOST.resetLimits(pack, sheet, "session.start"));
        assertEquals(0, sheet.at("/automationState/pools/charge/value").intValue());
        assertEquals(2, sheet.at("/automationState/pools/other/value").intValue());
        assertFalse(HOST.resetLimits(pack, sheet, "session.start"));
    }

    @Test
    void duplicateInstancesAndLegacyPacksAreRejected() throws Exception {
        ObjectNode sheet = sheet();
        ((ObjectNode) sheet.path("features").get(1)).put("instanceId", "f-rally");
        assertThrows(IllegalArgumentException.class, () -> HOST.describe(pack(), sheet, action(), "sheet", "self"));
        ObjectNode legacy = fixture("rewrite/consumables").put("version", 1);
        assertThrows(AutomationHost.NotReady.class, () -> AutomationHost.compile("x",
                Map.of("consumables", legacy.toString()), fixture("rewrite/recipes").toString()));
        assertThrows(AutomationHost.NotReady.class, () -> AutomationHost.compile("x", Map.of(), null));
    }
}
