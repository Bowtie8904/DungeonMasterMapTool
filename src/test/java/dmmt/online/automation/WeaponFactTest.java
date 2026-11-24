package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** actor.primaryWeapon and the indexOf expression: legacy Menacing Reach (OnlineSessionServer.resolveWarlockAction). */
class WeaponFactTest {
    private static final String REACH = """
            {"format":"dmmap-daggerheart","version":1,"category":"classes","label":"Fixture","entries":[
              {"id":"reach","name":"Menacing Reach","automation":{
                "resources":[{"id":"favor","label":"Favor","initial":2,"max":4}],
                "actions":[{"id":"use","label":"Increase weapon range","menacingReach":true,"cost":{"favor":1}}],
                "rollTriggers":[{"id":"end","label":"Menacing Reach: end after successful attack","on":"attack","primaryWeapon":true,
                  "requiresEffect":"Menacing Reach","endEffect":"Menacing Reach"}]}}]}""";
    private static final String LONGSHOT = """
            {"format":"dmmap-daggerheart","version":2,"category":"classes","label":"Fixture","entries":[
              {"id":"longshot","name":"Longshot","automation":{"rules":[{"id":"aim","bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button"}],
                "do":[{"op":"action.reject","reason":"No primary weapon.","if":{"not":{"exists":"actor.primaryWeapon.range"}}},
                      {"op":"action.reject","reason":"Too close to aim.","if":{"lt":[{"indexOf":[{"literal":["Melee","Very Close","Close","Far","Very Far"]},{"read":"actor.primaryWeapon.range"}]},3]}},
                      {"op":"value","as":"slot","value":{"read":"actor.primaryWeapon.name"}}],
                "ui":{"label":"Aim","report":"Aiming {result.slot} at {actor.primaryWeapon.range}."}}]}}]}""";

    private static final class Fixture {
        final AutomationHost host = AutomationHost.bundled();
        final AutomationHost.Pack pack;
        final String sourceId;

        Fixture(String legacy) throws Exception {
            String text = legacy;
            if (legacy.contains("\"version\":1")) {
                var converted = LegacyCatalogConverter.convert(legacy);
                assertTrue(converted.diagnostics().isEmpty(), converted.diagnostics().toString());
                text = converted.catalog().toString();
            }
            pack = AutomationHost.compile("test", Map.of("classes", text), LegacyCatalogConverter.coreRecipes());
            sourceId = RuleCatalog.compile(text, LegacyCatalogConverter.coreRecipes()).getFirst().id();
        }

        ObjectNode sheet(String weapons) throws Exception {
            return (ObjectNode) RuleJson.parse("""
                    {"name":"Ada","level":2,"hp":{"slots":6,"on":0},"stress":{"slots":6,"on":0},"armorTrack":{"slots":3,"on":0},
                     "hope":{"slots":6,"on":0},"features":[{"name":"Feature","automationSource":"%s","instanceId":"feat"}],"heritage":[],"loadout":[],"vault":[],
                     "inventory":[],"activeWeapons":%s}""".formatted(sourceId, weapons));
        }

        JsonNode use(ObjectNode sheet, String rule) {
            ObjectNode request = RuleJson.JSON.createObjectNode().put("requestId", "r" + System.nanoTime())
                    .put("expectedRevision", sheet.at("/automationState/revision").asLong());
            request.putArray("selections").addObject().put("sourceInstanceId", "feat").put("ruleId", rule).put("bindingId", "sheet");
            return host.execute(pack, sheet, AutomationHost.event("action.request", "feat", 0), request, "self", StructuredRollTest.noRandom());
        }
    }

    private static String weapon(String slot, String range) {
        return "[{\"name\":\"Longbow\",\"slot\":\"" + slot + "\",\"range\":\"" + range + "\",\"trait\":\"Agility\",\"damage\":\"d8\",\"hands\":2}]";
    }

    @Test
    void theRangeRisesOneStepAndTheEffectCarriesTheLegacyText() throws Exception {
        Fixture f = new Fixture(REACH);
        ObjectNode sheet = f.sheet(weapon("Primary", "Close"));
        JsonNode receipt = f.use(sheet, "use");
        JsonNode effects = sheet.at("/automationState/effects");
        assertEquals(1, effects.size(), sheet.toString());
        String text = "Longbow reach: Far (normally Close). Ends after a successful attack with this weapon.";
        assertEquals("Menacing Reach", effects.get(0).path("label").asText());
        assertEquals(text, effects.get(0).path("text").asText());
        assertEquals("Spent 1 Favor. " + text, receipt.at("/actions/0/report").asText(), receipt.toString());
    }

    @Test
    void theRangeIsCappedAtVeryFarAndMatchingIsCaseInsensitive() throws Exception {
        Fixture f = new Fixture(REACH);
        ObjectNode sheet = f.sheet(weapon("Primary", "very far"));
        f.use(sheet, "use");
        assertEquals("Longbow reach: Very Far (normally very far). Ends after a successful attack with this weapon.",
                sheet.at("/automationState/effects/0/text").asText());
    }

    @Test
    void legacyRejectionsLeaveTheSheetUntouched() throws Exception {
        Fixture f = new Fixture(REACH);
        for (var rejection : Map.of(weapon("Secondary", "Close"), "Equip a primary weapon first.", "[]", "Equip a primary weapon first.",
                weapon("Primary", "Reachy"), "The primary weapon has no supported range.").entrySet()) {
            ObjectNode sheet = f.sheet(rejection.getKey());
            RuntimeException error = assertThrows(RuntimeException.class, () -> f.use(sheet, "use"));
            assertTrue(error.getMessage().contains(rejection.getValue()), error.getMessage());
            assertEquals(0, sheet.at("/automationState/effects").size());
        }
        ObjectNode active = f.sheet(weapon("Primary", "Melee"));
        f.use(active, "use");
        RuntimeException again = assertThrows(RuntimeException.class, () -> f.use(active, "use"));
        assertTrue(again.getMessage().contains("Menacing Reach is already active."), again.getMessage());
        assertEquals(1, active.at("/automationState/effects").size());
    }

    @Test
    void aSecondFixtureReadsTheSameFactsThroughIndexOf() throws Exception {
        Fixture f = new Fixture(LONGSHOT);
        JsonNode receipt = f.use(f.sheet(weapon("Primary", "Far")), "aim");
        assertEquals("Aiming Longbow at Far.", receipt.at("/actions/0/report").asText(), receipt.toString());
        assertTrue(assertThrows(RuntimeException.class, () -> f.use(f.sheet(weapon("Primary", "Close")), "aim")).getMessage().contains("Too close to aim."));
        assertTrue(assertThrows(RuntimeException.class, () -> f.use(f.sheet(weapon("Secondary", "Far")), "aim")).getMessage().contains("No primary weapon."));
    }
}
