package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Legacy halfProficiencyDamage (Glancing Blow, OnlineSessionServer.resolveGlancingBlow): a failed weapon attack may be
 * turned into one damage roll of the same weapon with half Proficiency (rounded up) dice per term; a fixed-dice weapon
 * keeps its own counts. The generic parts are the {@code captureWeapon} effect and the weapon-echo profile.
 */
class WeaponEchoTest {
    private static final AutomationHost HOST = AutomationHost.bundled();

    private static final String LEGACY = """
            {"format":"dmmap-daggerheart","version":1,"category":"domain-cards","label":"Fixture","entries":[
             {"id":"blow","name":"Glancing Blow","automation":{"conditionalRollTriggers":[
               {"id":"use","label":"Glancing Blow","on":"attack","failedOnly":true,"weaponAttack":true,
                "halfProficiencyDamage":true,"cost":{"stress":1}}]}}]}""";

    private static ObjectNode sheet(String source, String damage, boolean fixed, int proficiency, boolean equipped) throws Exception {
        ObjectNode sheet = (ObjectNode) RuleJson.parse("""
                {"name":"Ada","level":2,"hp":{"slots":6,"on":0},"stress":{"slots":6,"on":0},"armorTrack":{"slots":3,"on":0},
                 "hope":{"slots":6,"on":0},"proficiency":{"slots":6,"total":6,"on":%d},
                 "traits":[{"name":"Agility","mod":1,"marked":false}],
                 "features":[],"heritage":[],"loadout":[{"name":"Glancing Blow","automationSource":"%s","instanceId":"card"}],
                 "vault":[],"inventory":[],"activeArmor":null,"activeWeapons":[]}""".formatted(proficiency, source));
        if (equipped) sheet.withArray("activeWeapons").addObject().put("name", "Sword").put("slot", "Primary")
                .put("instanceId", "w1").put("automationSource", "weapons:sword").put("trait", "Agility")
                .put("range", "Melee").put("damageType", "phys").put("damage", damage).put("fixedDice", fixed);
        return sheet;
    }

    private static ObjectNode start(ObjectNode sheet, JsonNode effect, String weaponId) {
        ObjectNode instance = HOST.projection().state(sheet).withArray("effects").addObject()
                .put("id", "glance").put("effect", effect.path("key").asText()).put("sourceInstanceId", "card")
                .put("label", effect.path("label").asText());
        instance.set("profile", effect.get("profile").deepCopy());
        if (weaponId != null) instance.putObject("weapon").put("instanceId", weaponId).put("sourceId", "weapons:sword");
        return instance;
    }

    private static ObjectNode roll(String effect) {
        ObjectNode request = RuleJson.JSON.createObjectNode().put("requestId", "r").put("expectedRevision", 0);
        request.putArray("selections");
        request.putObject("profileRoll").put("sourceInstanceId", "card").put("profileId", "effect.profile")
                .put("kind", "damage").put("effectInstanceId", effect);
        return request;
    }

    private static AutomationHost.Pack pack(LegacyCatalogConverter.Result converted) {
        return AutomationHost.compile("test", Map.of("domain-cards", converted.catalog().toString()),
                LegacyCatalogConverter.coreRecipes(), LegacyCatalogConverter.coreRuleset());
    }

    private static String sourceOf(AutomationHost.Pack pack) {
        return pack.programs().keySet().stream().filter(key -> !"ruleset:base".equals(key)).findFirst().orElseThrow();
    }

    @Test
    void convertedTriggerStartsAWeaponCapturingEffectAndEndsItWithItsRoll() throws Exception {
        var converted = LegacyCatalogConverter.convert(LEGACY);
        assertEquals(converted.blocks(), converted.converted(), converted.diagnostics().toString());
        JsonNode block = converted.catalog().at("/entries/0/automation");
        JsonNode effect = block.at("/effects/use-damage");
        assertTrue(effect.path("captureWeapon").asBoolean(), block.toString());
        assertTrue(effect.at("/profile/damage/weapon").asBoolean());
        assertEquals("half", effect.at("/profile/damage/scale").asText());
        assertEquals("effect.start", block.at("/rules/0/do/0/op").asText());
        assertEquals("effect.profile", block.at("/rules/1/when/and/0/eq/1").asText(), block.at("/rules/1").toString());
        assertEquals("effect.end", block.at("/rules/1/do/0/op").asText());
    }

    @Test
    void halfProficiencyDiceMatchLegacyForMixedAndFixedWeapons() throws Exception {
        var converted = LegacyCatalogConverter.convert(LEGACY);
        var pack = pack(converted);
        String source = sourceOf(pack);
        JsonNode definition = converted.catalog().at("/entries/0/automation/effects/use-damage");
        ObjectNode origin = AutomationHost.event("action.request", "actor", 0);
        ObjectNode effect = RuleJson.JSON.createObjectNode().put("key", source + "#use-damage").put("label", "Glancing Blow");
        effect.set("profile", definition.get("profile"));

        // Proficiency 3 rounds up to 2 dice per term; the flat bonus is kept.
        ObjectNode sheet = sheet(source, "1d8 + d6 + 2", false, 3, true);
        start(sheet, effect, "w1");
        JsonNode offered = HOST.describe(pack, sheet, origin, "sheet", "self").findValue("profiles");
        assertEquals("Glancing Blow", offered.get(offered.size() - 1).path("label").asText());
        JsonNode event = HOST.execute(pack, sheet, origin, roll("glance"), "self", new SplittableRandom(3)).required("event");
        JsonNode parts = event.requiredAt("/roll/contributions");
        assertEquals(2, parts.get(0).required("dice").size());
        assertEquals(8, parts.get(0).requiredAt("/dice/0/sides").intValue());
        assertEquals(2, parts.get(1).required("dice").size());
        assertEquals("physical", event.requiredAt("/check/damageType").textValue());
        assertEquals("w1", event.requiredAt("/check/weaponSource").textValue());
        assertEquals(parts.get(0).path("total").intValue() + parts.get(1).path("total").intValue() + 2,
                event.requiredAt("/check/total").intValue());
        assertTrue(sheet.at("/automationState/effects").isEmpty(), "The single damage roll ends the effect");

        // Proficiency 1 never drops below one die.
        sheet = sheet(source, "d10", false, 1, true);
        start(sheet, effect, "w1");
        event = HOST.execute(pack, sheet, origin, roll("glance"), "self", new SplittableRandom(3)).required("event");
        assertEquals(1, event.requiredAt("/roll/contributions/0/dice").size());

        // A fixed-dice weapon keeps its own dice counts.
        sheet = sheet(source, "2d8", true, 6, true);
        start(sheet, effect, "w1");
        event = HOST.execute(pack, sheet, origin, roll("glance"), "self", new SplittableRandom(3)).required("event");
        assertEquals(2, event.requiredAt("/roll/contributions/0/dice").size());
        assertTrue(event.requiredAt("/check/fixedDice").booleanValue());
    }

    @Test
    void anUnequippedWeaponMakesTheRollUnavailable() throws Exception {
        var converted = LegacyCatalogConverter.convert(LEGACY);
        var pack = pack(converted);
        String source = sourceOf(pack);
        ObjectNode effect = RuleJson.JSON.createObjectNode().put("key", source + "#use-damage").put("label", "Glancing Blow");
        effect.set("profile", converted.catalog().at("/entries/0/automation/effects/use-damage/profile"));
        ObjectNode sheet = sheet(source, "d8", false, 3, false);
        start(sheet, effect, "w1");
        ObjectNode origin = AutomationHost.event("action.request", "actor", 0);
        List<JsonNode> profiles = HOST.describe(pack, sheet, origin, "sheet", "self").path("profiles")
                .findParents("effectInstanceId");
        assertEquals(1, profiles.size());
        assertFalse(profiles.get(0).path("available").asBoolean(true));
        ObjectNode before = sheet.deepCopy();
        assertThrows(RuleRuntime.Unavailable.class, () -> HOST.execute(pack, sheet, origin, roll("glance"), "self",
                StructuredRollTest.noRandom()));
        assertEquals(before, sheet);
    }

    @Test
    void aFullScaleEffectUsesTheSameCaptureAndTheCompilerRequiresBothHalves() throws Exception {
        var converted = LegacyCatalogConverter.convert(LEGACY);
        ObjectNode full = (ObjectNode) converted.catalog().deepCopy();
        ((ObjectNode) full.at("/entries/0/automation/effects/use-damage/profile/damage")).remove("scale");
        var pack = pack(LegacyCatalogConverter.convert(LEGACY));
        String source = sourceOf(pack);
        var fullPack = AutomationHost.compile("test", Map.of("domain-cards", full.toString()),
                LegacyCatalogConverter.coreRecipes(), LegacyCatalogConverter.coreRuleset());
        ObjectNode effect = RuleJson.JSON.createObjectNode().put("key", source + "#use-damage").put("label", "Echo");
        effect.set("profile", full.at("/entries/0/automation/effects/use-damage/profile"));
        ObjectNode sheet = sheet(source, "d8", false, 3, true);
        start(sheet, effect, "w1");
        JsonNode event = HOST.execute(fullPack, sheet, AutomationHost.event("action.request", "actor", 0), roll("glance"),
                "self", new SplittableRandom(3)).required("event");
        assertEquals(3, event.requiredAt("/roll/contributions/0/dice").size());

        ObjectNode noCapture = (ObjectNode) converted.catalog().deepCopy();
        ((ObjectNode) noCapture.at("/entries/0/automation/effects/use-damage")).remove("captureWeapon");
        assertThrows(RuntimeException.class, () -> AutomationHost.compile("test",
                Map.of("domain-cards", noCapture.toString()), LegacyCatalogConverter.coreRecipes(),
                LegacyCatalogConverter.coreRuleset()));
    }
}