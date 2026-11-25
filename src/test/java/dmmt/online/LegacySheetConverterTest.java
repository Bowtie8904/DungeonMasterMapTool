package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LegacySheetConverterTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    static ObjectNode node(String json) {
        try { return (ObjectNode) JSON.readTree(json); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    static LegacySheetConverter.Catalogs catalogs() {
        LegacySheetConverter.Catalogs catalogs = new LegacySheetConverter.Catalogs();
        catalogs.add(node("""
                {"category":"classes","entries":[{"id":"warrior","name":"Warrior","features":[
                  {"id":"rally","name":"Rally","_catalogFeatureId":"f-rally","automation":{"limits":{"uses":{"max":1}}}},
                  {"id":"twin","name":"Twin","_catalogFeatureId":"f-twin1"},{"id":"twin-b","name":"Twin","_catalogFeatureId":"f-twin2"},
                  {"id":"deathrun","name":"Deathrun","_catalogFeatureId":"f-run","automation":{"state":{"originalEligibleDice":[]}}}]}]}"""));
        catalogs.add(node("""
                {"category":"domain-cards","entries":[{"id":"bolt","name":"Bolt","automation":{"limits":{"a":{},"b":{}}},
                  "spells":[{"name":"Zap","_catalogFeatureId":"s-zap"}]}]}"""));
        return catalogs;
    }

    private static List<LegacySheetConverter.Diagnostic> errors(List<LegacySheetConverter.Diagnostic> all) {
        return all.stream().filter(LegacySheetConverter.Diagnostic::error).toList();
    }

    @Test
    void promotesFeatureIdsAndIdentitiesAndMovesSpentLimits() {
        ObjectNode legacy = node("""
                {"features":[{"name":"Rally","catalog":{"category":"classes","id":"warrior","feature":0},"automation":{"uses":{"max":1}}}],
                 "loadout":[{"name":"Bolt","spells":[{"name":"Zap"}]}],
                 "usage":{"feat:Rally":{"used":1,"per":"rest"}}}""");
        List<LegacySheetConverter.Diagnostic> diagnostics = new ArrayList<>();
        ObjectNode sheet = LegacySheetConverter.convert(legacy, "hero", catalogs(), diagnostics);
        assertTrue(errors(diagnostics).isEmpty(), diagnostics.toString());
        JsonNode feature = sheet.at("/features/0");
        assertEquals("f-rally", feature.at("/catalog/featureId").asText());
        assertFalse(feature.at("/catalog").has("feature"));
        assertEquals("classes:warrior:features:rally", feature.path("automationSource").asText());
        assertFalse(feature.has("automation"));
        assertEquals("domain-cards:bolt", sheet.at("/loadout/0/automationSource").asText());
        assertEquals("s-zap", sheet.at("/loadout/0/spells/0/id").asText());
        String instance = feature.path("instanceId").asText();
        assertEquals(1, sheet.at("/automationState/sources/" + instance + "/usage/uses").asInt());
        assertEquals(1, sheet.at("/usage/feat:Rally/used").asInt());
    }

    @Test
    void ambiguousAndUnresolvedReferencesAreReportedAsErrors() {
        ObjectNode legacy = node("""
                {"features":[{"name":"Twin","catalog":{"category":"classes","id":"warrior"}},
                             {"name":"Gone","catalog":{"category":"classes","id":"missing"}},
                             {"name":"Nope","catalog":{"category":"classes","id":"warrior"}}]}""");
        List<LegacySheetConverter.Diagnostic> diagnostics = new ArrayList<>();
        LegacySheetConverter.convert(legacy, "hero", catalogs(), diagnostics);
        assertEquals(3, errors(diagnostics).size(), diagnostics.toString());
        assertTrue(diagnostics.get(0).message().contains("featureId"));
        assertTrue(diagnostics.get(0).path().equals("/features/0"));
    }

    @Test
    void ambiguousLimitsBlockAndCustomGearStaysUntouched() {
        ObjectNode legacy = node("""
                {"loadout":[{"name":"Bolt","spells":[]}],"usage":{"card:Bolt":{"used":1}},
                 "inventory":[{"name":"Lucky Coin","custom":true,"qty":2,"note":"mine"}],
                 "activeWeapons":[{"name":"Homebrew Axe","damage":"d10","automation":{"x":1}}]}""");
        List<LegacySheetConverter.Diagnostic> diagnostics = new ArrayList<>();
        ObjectNode sheet = LegacySheetConverter.convert(legacy, "hero", catalogs(), diagnostics);
        assertEquals(1, errors(diagnostics).size(), diagnostics.toString());
        assertEquals(legacy.get("inventory"), sheet.get("inventory"));
        assertEquals(legacy.get("activeWeapons"), sheet.get("activeWeapons"));
    }

    @Test
    void activeDeathrunAndHpLocksKeepTheirStateAndLegacyEffectsAreRemoved() {
        ObjectNode legacy = node("""
                {"name":"Hero","features":[{"name":"Deathrun","catalog":{"category":"classes","id":"warrior"}}],
                 "activeWeapons":[{"name":"Axe","instanceId":"axe-1"}],
                 "effects":[
                  {"id":"run","name":"Deathrun","deathrun":true,"counter":{"value":2,"max":9},
                   "deathrunRoll":{"total":14,"rolls":[3,5,6],"weapon":"Axe"},"deathrunRemaining":[1,2]},
                  {"id":"pact","name":"Pact","lockedHp":2,"sourceActorId":"witch","until":"longRest"},
                  {"id":"dice-1","name":"Prayer Die","dicePool":"prayer","counter":{"value":4,"max":6},"until":"session"}]}""");
        List<LegacySheetConverter.Diagnostic> diagnostics = new ArrayList<>();
        ObjectNode sheet = LegacySheetConverter.convert(legacy, "hero", catalogs(), diagnostics);
        assertTrue(errors(diagnostics).isEmpty(), diagnostics.toString());
        assertEquals(1, sheet.path("effects").size());
        assertEquals("dice-1", sheet.at("/effects/0/id").asText());
        assertEquals(4, sheet.at("/effects/0/counter/value").asInt());
        JsonNode lock = sheet.at("/automationState/effects/0");
        assertEquals(2, lock.at("/locks/hp").asInt());
        assertEquals("witch", lock.path("sourceActorId").asText());
        assertEquals("[\"rest.long\"]", lock.path("until").toString());
        String instance = sheet.at("/features/0/instanceId").asText();
        JsonNode state = sheet.at("/automationState/sources/" + instance + "/state");
        assertEquals("[1,2]", state.path("remainingDice").toString());
        assertEquals("[0,1,2]", state.path("originalEligibleDice").toString());
        assertEquals(14, state.path("firstDamage").asInt());
        assertEquals(2, state.path("uses").asInt());
        assertEquals("axe-1", state.path("weaponId").asText());
    }

    @Test
    void lockWithoutAnyOwnerIsBlockedAndConversionIsIdempotent() {
        ObjectNode legacy = node("""
                {"features":[{"name":"Rally","catalog":{"category":"classes","id":"warrior","feature":0}}],
                 "usage":{"feat:Rally":{"used":1}},
                 "effects":[{"id":"x","name":"Lock","lockedHp":1,"until":"scene"}]}""");
        List<LegacySheetConverter.Diagnostic> diagnostics = new ArrayList<>();
        LegacySheetConverter.convert(legacy, "hero", catalogs(), diagnostics);
        assertEquals(1, errors(diagnostics).size());
        legacy.withArray("effects").removeAll();
        ObjectNode once = LegacySheetConverter.convert(legacy, "hero", catalogs(), new ArrayList<>());
        ObjectNode twice = LegacySheetConverter.convert(once, "hero", catalogs(), new ArrayList<>());
        assertEquals(once, twice);
    }
}
