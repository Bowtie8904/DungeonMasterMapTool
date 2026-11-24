package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ignoreWeaponBurden (legacy data.ts handsUsed / view.ts equipWeapon / warrior.test.ts) becomes an automatic
 * equipment.plan rule; unarmedWeapon (legacy brawler.ts syncBrawler) becomes a program weapon synchronized by the host.
 */
class EquipmentPlanTest {
    private static final AutomationHost HOST = AutomationHost.bundled();

    private static AutomationHost.Pack pack(String features) throws Exception {
        var converted = LegacyCatalogConverter.convert("""
                {"format":"dmmap-daggerheart","version":1,"category":"classes","label":"Fixture","entries":[
                 {"id":"c","name":"C","features":[%s]}]}""".formatted(features));
        assertTrue(converted.diagnostics().isEmpty(), converted.diagnostics().toString());
        return AutomationHost.compile("test", Map.of("classes", converted.catalog().toString()),
                LegacyCatalogConverter.coreRecipes(), LegacyCatalogConverter.coreRuleset());
    }

    private static ObjectNode sheet(AutomationHost.Pack pack, String weapons, String traits) throws Exception {
        String source = pack.programs().keySet().stream().filter(id -> id.startsWith("classes:")).findFirst().orElseThrow();
        return (ObjectNode) RuleJson.parse("""
                {"name":"A","level":2,"evasion":10,"hp":{"slots":6,"on":0},"stress":{"slots":6,"on":0},"armorTrack":{"slots":3,"on":0},
                 "hope":{"slots":6,"on":0},"features":[{"name":"F","automationSource":"%s","instanceId":"f1"}],"heritage":[],
                 "loadout":[],"vault":[],"inventory":[],"activeWeapons":%s,"activeArmor":null,"carriedWeapons":[],"traits":%s}"""
                .formatted(source, weapons, traits));
    }

    private static final String TRAITS = """
            [{"name":"Agility","mod":0},{"name":"Strength","mod":2},{"name":"Finesse","mod":2},{"name":"Instinct","mod":1},
             {"name":"Presence","mod":-1},{"name":"Knowledge","mod":0}]""";
    private static final String FISTS = """
            {"name":"F","type":"subclass","automation":{"unarmedWeapon":{"name":"Brawler's Strike","slot":"Primary",
             "damageType":"phys","range":"Melee","damage":"d8+d6","hands":1,"tier":1}}}""";

    @Test
    void burdenIgnoringBecomesAnEquipmentPlanAdjustment() throws Exception {
        var result = LegacyCatalogConverter.convert("""
                {"format":"dmmap-daggerheart","version":1,"category":"classes","label":"F","entries":[
                 {"id":"w","name":"W","features":[{"name":"Training","type":"subclass","automation":{"ignoreWeaponBurden":true}}]}]}""");
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        JsonNode rule = result.catalog().at("/entries/0/features/0/automation/rules/0");
        assertEquals("equipment.plan", rule.at("/bindings/0/event").asText());
        assertEquals("plan.adjust", rule.at("/do/0/op").asText());
        assertEquals("event.equipment", rule.at("/do/0/plan").asText());
        assertEquals("ignoreBurden", rule.at("/do/0/field").asText());
    }

    private static JsonNode plan(AutomationHost.Pack pack, ObjectNode sheet, String adjustable) throws Exception {
        ObjectNode event = AutomationHost.event("equipment.plan", "self", 0);
        event.set("equipment", RuleJson.parse("{\"handLimit\":2,\"ignoreBurden\":0,\"adjustable\":" + adjustable + "}"));
        return HOST.automatic(pack, sheet, event, StructuredRollTest.noRandom()).at("/event/equipment");
    }

    @Test
    void burdenIgnoringRaisesTheIgnoreFlagOnlyForItsHolder() throws Exception {
        var warrior = pack("{\"name\":\"Training\",\"type\":\"subclass\",\"automation\":{\"ignoreWeaponBurden\":true}}");
        String open = "{\"ignoreBurden\":{\"min\":0,\"max\":1}}";
        assertEquals(1, plan(warrior, sheet(warrior, "[]", TRAITS), open).path("ignoreBurden").asInt());
        // Legacy equipWeapon / demiplane: without the feature the plan stays at Burden rules.
        var plain = pack("{\"name\":\"Plain\",\"type\":\"subclass\",\"automation\":{\"modifiers\":[{\"stat\":\"evasion\",\"value\":1}]}}");
        assertEquals(0, plan(plain, sheet(plain, "[]", TRAITS), open).path("ignoreBurden").asInt());
    }

    @Test
    void anyFeatureMayAdjustTheHandLimitThroughTheSamePlan() throws Exception {
        var converted = LegacyCatalogConverter.convert("""
                {"format":"dmmap-daggerheart","version":1,"category":"classes","label":"F","entries":[
                 {"id":"c","name":"C","features":[{"name":"Third hand","type":"subclass","automation":{"ignoreWeaponBurden":true}}]}]}""");
        ObjectNode step = (ObjectNode) converted.catalog().at("/entries/0/features/0/automation/rules/0/do/0");
        step.put("field", "handLimit").put("label", "Third hand");
        var pack = AutomationHost.compile("test", Map.of("classes", converted.catalog().toString()),
                LegacyCatalogConverter.coreRecipes(), LegacyCatalogConverter.coreRuleset());
        assertEquals(3, plan(pack, sheet(pack, "[]", TRAITS), "{\"handLimit\":{\"min\":2,\"max\":4}}").path("handLimit").asInt());
    }
    @Test
    void unarmedWeaponFillsTheActiveSlotWithTheHighestTrait() throws Exception {
        var pack = pack(FISTS);
        ObjectNode sheet = sheet(pack, "[]", TRAITS);
        assertTrue(HOST.synchronizeUnarmedWeapon(pack, sheet));
        JsonNode weapon = sheet.at("/activeWeapons/0");
        assertEquals("Brawler's Strike", weapon.path("name").asText());
        assertEquals("Strength", weapon.path("trait").asText(), "first of the tied highest traits in order");
        assertEquals("brawler", weapon.path("generated").asText());
        assertEquals("d8+d6", weapon.path("damage").asText());
        assertEquals("Strength", sheet.path("brawlerTrait").asText());
        assertFalse(HOST.synchronizeUnarmedWeapon(pack, sheet), "idempotent");
    }

    @Test
    void unarmedWeaponKeepsTheChosenTraitAndYieldsToRealWeapons() throws Exception {
        var pack = pack(FISTS);
        ObjectNode sheet = sheet(pack, "[]", TRAITS);
        sheet.put("brawlerTrait", "Finesse");
        HOST.synchronizeUnarmedWeapon(pack, sheet);
        assertEquals("Finesse", sheet.at("/activeWeapons/0/trait").asText());
        ((com.fasterxml.jackson.databind.node.ArrayNode) sheet.get("carriedWeapons")).add(
                RuleJson.JSON.createObjectNode().put("name", "Old").put("generated", "brawler"));
        sheet.set("activeWeapons", RuleJson.parse("[{\"name\":\"Sword\",\"slot\":\"Primary\",\"trait\":\"Strength\"},"
                + "{\"name\":\"Brawler's Strike\",\"generated\":\"brawler\"}]"));
        assertTrue(HOST.synchronizeUnarmedWeapon(pack, sheet));
        assertEquals(1, sheet.path("activeWeapons").size());
        assertEquals("Sword", sheet.at("/activeWeapons/0/name").asText());
        assertEquals(0, sheet.path("carriedWeapons").size());
        sheet.set("activeWeapons", RuleJson.JSON.createArrayNode());
        HOST.synchronizeUnarmedWeapon(pack, sheet);
        assertEquals("Finesse", sheet.at("/activeWeapons/0/trait").asText());
    }

    private static int evasion(AutomationHost.Pack pack, ObjectNode sheet) {
        return HOST.actor(pack, sheet, HOST.projection().state(sheet), AutomationHost.event("action.request", "self", 0))
                .at("/stats/evasion").asInt();
    }

    @Test
    void unarmedWeaponAddsEvasionOnlyWhileItIsTheActiveWeapon() throws Exception {
        var pack = pack(FISTS);
        ObjectNode bare = sheet(pack, "[]", TRAITS);
        HOST.synchronizeUnarmedWeapon(pack, bare);
        ObjectNode armed = sheet(pack, "[{\"name\":\"Sword\",\"slot\":\"Primary\"}]", TRAITS);
        HOST.synchronizeUnarmedWeapon(pack, armed);
        assertEquals(1, evasion(pack, bare) - evasion(pack, armed));
    }

    @Test
    void sourcesWithoutTheDeclarationNeverGenerateAWeapon() throws Exception {
        var pack = pack("{\"name\":\"Plain\",\"type\":\"subclass\",\"automation\":{\"modifiers\":[{\"stat\":\"evasion\",\"value\":1}]}}");
        ObjectNode sheet = sheet(pack, "[{\"name\":\"Brawler's Strike\",\"generated\":\"brawler\"}]", TRAITS);
        assertTrue(HOST.synchronizeUnarmedWeapon(pack, sheet));
        assertEquals(0, sheet.path("activeWeapons").size());
    }

    @Test
    void aSecondDeclarationUsesItsOwnSlotAndDamage() throws Exception {
        var pack = pack("""
                {"name":"F","type":"subclass","automation":{"unarmedWeapon":{"name":"Claws","slot":"Secondary",
                 "damageType":"mag","range":"Close","damage":"d10","hands":1,"tier":2}}}""");
        ObjectNode sheet = sheet(pack, "[]", TRAITS);
        HOST.synchronizeUnarmedWeapon(pack, sheet);
        assertEquals("Claws", sheet.at("/activeWeapons/0/name").asText());
        assertEquals("Secondary", sheet.at("/activeWeapons/0/slot").asText());
        assertEquals("mag", sheet.at("/activeWeapons/0/damageType").asText());
    }
}