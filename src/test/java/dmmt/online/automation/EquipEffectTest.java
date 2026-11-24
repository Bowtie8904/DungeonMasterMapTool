package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** equipEffect: an effect started on equip that ends when its equipment leaves the slot (legacy ArmorFeatureRules). */
class EquipEffectTest {
    private static final String VOWKEEPER = """
            {"format":"dmmap-daggerheart","version":1,"category":"armor","label":"Fixture","entries":[
              {"id":"panoply","name":"Panoply","automation":{
                "equipEffect":{"name":"Oathbound","kind":"buff","until":"manual","text":"+2 Evasion while pursuing your vow.",
                  "modifiers":[{"stat":"evasion","value":2}]},
                "actions":[{"id":"break","label":"Break vow","requiresEffect":"Oathbound","endEffect":"Oathbound",
                  "steps":[{"op":"mark","resource":"armor","amount":"@armor_available"}]}]}}]}""";
    private static final String BOOTS = """
            {"format":"dmmap-daggerheart","version":1,"category":"armor","label":"Fixture","entries":[
              {"id":"boots","name":"Boots","automation":{
                "equipEffect":{"name":"Swift","kind":"buff","until":"scene","modifiers":[{"stat":"armorScore","value":1}]}}}]}""";

    private static final class Fixture {
        final AutomationHost host = AutomationHost.bundled();
        final AutomationHost.Pack pack;
        final String sourceId;

        Fixture(String legacy) throws Exception {
            var converted = LegacyCatalogConverter.convert(legacy);
            assertTrue(converted.diagnostics().isEmpty(), converted.diagnostics().toString());
            String text = converted.catalog().toString();
            pack = AutomationHost.compile("test", Map.of("armor", text), LegacyCatalogConverter.coreRecipes());
            sourceId = RuleCatalog.compile(text, LegacyCatalogConverter.coreRecipes()).getFirst().id();
        }

        ObjectNode sheet(String armor) throws Exception {
            return (ObjectNode) RuleJson.parse("""
                    {"name":"Ada","level":2,"hp":{"slots":6,"on":0},"stress":{"slots":6,"on":0},"armorTrack":{"slots":3,"on":0},
                     "hope":{"slots":6,"on":0},"features":[],"heritage":[],"loadout":[],"vault":[],"inventory":[],
                     "activeWeapons":[],"activeArmor":%s}""".formatted(armor.isEmpty() ? "null"
                    : "{\"name\":\"Armor\",\"score\":1,\"automationSource\":\"" + sourceId + "\",\"instanceId\":\"armor1\"}"));
        }

        ObjectNode equipped(ObjectNode sheet) {
            ObjectNode event = AutomationHost.event("equipment.changed", "self", 0);
            event.putObject("equipment").put("slot", "armor").putObject("after").put("name", "Armor");
            host.automatic(pack, sheet, event, StructuredRollTest.noRandom());
            return sheet;
        }
    }

    private static JsonNode effects(ObjectNode sheet) {
        return sheet.path("automationState").path("effects");
    }

    @Test
    void equippingStartsTheEffectOnceAndUnequippingEndsIt() throws Exception {
        Fixture f = new Fixture(VOWKEEPER);
        ObjectNode sheet = f.sheet("x");
        f.equipped(sheet);
        assertEquals(1, effects(sheet).size(), sheet.toString());
        assertEquals("Oathbound", effects(sheet).get(0).path("label").asText());
        assertEquals(2, effects(sheet).get(0).at("/modifiers/0/value").asInt());
        f.equipped(sheet);
        assertEquals(1, effects(sheet).size(), "no duplicate while it is already active");
        sheet.putNull("activeArmor");
        assertTrue(f.host.endEffectsOfRemovedSources(f.pack, sheet, AutomationHost.event("action.request", "self", 0)));
        assertEquals(0, effects(sheet).size(), sheet.toString());
    }

    @Test
    void breakingTheVowEndsItAndItStaysGoneUntilTheArmorIsEquippedAgain() throws Exception {
        Fixture f = new Fixture(VOWKEEPER);
        ObjectNode sheet = f.equipped(f.sheet("x"));
        ObjectNode request = RuleJson.JSON.createObjectNode().put("requestId", "r1").put("expectedRevision", sheet.at("/automationState/revision").asLong());
        request.putArray("selections").addObject().put("sourceInstanceId", "armor1").put("ruleId", "break").put("bindingId", "sheet");
        f.host.execute(f.pack, sheet, AutomationHost.event("action.request", "armor1", 0), request, "self", StructuredRollTest.noRandom());
        assertEquals(0, effects(sheet).size(), sheet.toString());
        assertEquals(3, sheet.at("/armorTrack/on").asInt(), "break vow marks every available Armor Slot");
        assertFalse(f.host.endEffectsOfRemovedSources(f.pack, sheet, AutomationHost.event("action.request", "self", 0)));
        assertEquals(0, effects(sheet).size());
    }

    @Test
    void aSecondFixtureWithAnotherDurationAndStatUsesTheSamePrimitive() throws Exception {
        Fixture f = new Fixture(BOOTS);
        ObjectNode sheet = f.equipped(f.sheet("x"));
        assertEquals(1, effects(sheet).size(), sheet.toString());
        assertEquals("armorScore", effects(sheet).get(0).at("/modifiers/0/stat").asText());
        assertEquals("scene.end", effects(sheet).get(0).path("until").get(0).asText());
        sheet.putNull("activeArmor");
        f.host.endEffectsOfRemovedSources(f.pack, sheet, AutomationHost.event("action.request", "self", 0));
        assertEquals(0, effects(sheet).size());
    }
}
