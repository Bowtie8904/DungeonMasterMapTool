package dmmt.online;

import com.fasterxml.jackson.databind.ObjectMapper;
import dmmt.model.DmProject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OnlineTokenConditionsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void fullStressAddsOneLinkedDebuffAndClearingOneStressRemovesOnlyIt() {
        var sheet = MAPPER.createObjectNode();
        sheet.putObject("stress").put("slots", 6).put("on", 5);
        var effects = sheet.putArray("effects");
        var other = effects.addObject().put("id", "other").put("name", "Poisoned");
        OnlineTokenConditions.normalizeFullStress(sheet);
        assertEquals(1, effects.size());
        sheet.withObject("/stress").put("on", 6);
        OnlineTokenConditions.normalizeFullStress(sheet);
        OnlineTokenConditions.normalizeFullStress(sheet);
        assertEquals(2, effects.size());
        var generated = effects.get(1);
        assertEquals(OnlineTokenConditions.FULL_STRESS_EFFECT_ID, generated.path("id").asText());
        assertTrue(generated.path("appliedByFullStress").asBoolean());
        assertEquals("debuff", generated.path("kind").asText());
        assertEquals(OnlineTokenConditions.VULNERABLE_TEXT, generated.path("text").asText());
        assertEquals("vulnerable", OnlineTokenConditions.actorConditions(sheet).getFirst().getId());
        sheet.withObject("/stress").put("on", 5);
        OnlineTokenConditions.normalizeFullStress(sheet);
        assertEquals(1, effects.size());
        assertEquals(other, effects.get(0));
    }

    @Test
    void preExistingLinkedAndUnlinkedVulnerabilityNeverBecomesStressOwned() {
        for (boolean linked : java.util.List.of(false, true)) {
            var sheet = MAPPER.createObjectNode();
            sheet.putObject("stress").put("slots", 6).put("on", 6);
            var effects = sheet.putArray("effects");
            var original = MAPPER.createObjectNode().put("id", "outside-source").put("name", "Vulnerable")
                    .put("kind", "debuff");
            if (linked) OnlineTokenConditions.effect(original, "token",
                    new DmProject.TokenCondition("vulnerable", "vulnerable", "Vulnerable"));
            effects.add(original);
            var before = sheet.deepCopy();
            OnlineTokenConditions.normalizeFullStress(sheet);
            assertEquals(before, sheet);
            sheet.withObject("/stress").put("on", 0);
            OnlineTokenConditions.normalizeFullStress(sheet);
            assertEquals(1, effects.size());
            assertEquals(original, effects.get(0));
            assertFalse(original.path("appliedByFullStress").asBoolean());
        }
    }

    @Test
    void externalVulnerabilityAddedAfterFullStressSurvivesAndManualApplicationTakesOwnership() {
        var sheet = MAPPER.createObjectNode();
        sheet.putObject("stress").put("slots", 6).put("on", 6);
        OnlineTokenConditions.normalizeFullStress(sheet);
        var effects = (com.fasterxml.jackson.databind.node.ArrayNode) sheet.get("effects");
        var manual = effects.addObject().put("id", "external").put("name", "Vulnerable");
        sheet.withObject("/stress").put("on", 0);
        OnlineTokenConditions.normalizeFullStress(sheet);
        assertEquals(1, effects.size());
        assertEquals(manual, effects.get(0));
        effects.removeAll();
        sheet.withObject("/stress").put("on", 6);
        OnlineTokenConditions.normalizeFullStress(sheet);
        OnlineTokenConditions.effect((com.fasterxml.jackson.databind.node.ObjectNode) effects.get(0), "token",
                new DmProject.TokenCondition("vulnerable", "vulnerable", "Vulnerable"));
        sheet.withObject("/stress").put("on", 0);
        OnlineTokenConditions.normalizeFullStress(sheet);
        assertEquals(1, effects.size());
        assertFalse(effects.get(0).path("appliedByFullStress").asBoolean());
    }

    @Test
    void missingAndZeroSlotStressDoNotApplyVulnerable() {
        var sheet = MAPPER.createObjectNode();
        OnlineTokenConditions.normalizeFullStress(sheet);
        assertFalse(sheet.has("effects"));
        sheet.putObject("stress").put("slots", 0).put("on", 0);
        OnlineTokenConditions.normalizeFullStress(sheet);
        assertFalse(sheet.has("effects"));
    }

    @Test
    void validatesBuiltInAndCustomNames() {
        assertNull(OnlineTokenConditions.validationProblem("hidden", "Hidden"));
        assertNull(OnlineTokenConditions.validationProblem("restrained", "Restrained"));
        assertNull(OnlineTokenConditions.validationProblem("vulnerable", "Vulnerable"));
        assertNull(OnlineTokenConditions.validationProblem("custom", "On Fire"));
        assertNotNull(OnlineTokenConditions.validationProblem("hidden", "Invisible"));
        assertNotNull(OnlineTokenConditions.validationProblem("custom", "  "));
        assertNotNull(OnlineTokenConditions.validationProblem("custom", "Bad" + (char) 0 + "Name"));
    }

    @Test
    void assignsReservedBuiltInIconsAndValidatesCustomChoices() {
        assertEquals("cond_invisible", OnlineTokenConditions.iconId("hidden", null));
        assertEquals("cond_restrained", OnlineTokenConditions.iconId("restrained", null));
        assertEquals("cond_concentrating", OnlineTokenConditions.iconId("vulnerable", null));
        assertEquals("cond_bleeding", OnlineTokenConditions.iconId("custom", null));
        assertEquals("cond_bleeding", OnlineTokenConditions.iconId("custom", "cond_invisible"));
        assertEquals("cond_blessed", OnlineTokenConditions.iconId("custom", "cond_blessed"));
        assertEquals(21, OnlineTokenConditions.customIconIds().size());
        assertFalse(OnlineTokenConditions.customIconIds().contains("cond_invisible"));
        assertFalse(OnlineTokenConditions.customIconIds().contains("cond_restrained"));
        assertFalse(OnlineTokenConditions.customIconIds().contains("cond_concentrating"));
        assertNotNull(OnlineTokenConditions.iconValidationProblem("custom", "cond_invisible"));
        assertNotNull(OnlineTokenConditions.iconValidationProblem("hidden", "cond_blessed"));
        assertNull(OnlineTokenConditions.iconValidationProblem("custom", "cond_blessed"));
    }

    @Test
    void preservesCustomIconThroughLinkedActorEffects() {
        var condition = new DmProject.TokenCondition("custom-fire", "custom", "On Fire", "cond_burning");
        var effect = OnlineTokenConditions.effect(MAPPER.createObjectNode(), "token", condition);
        assertEquals("cond_burning", effect.path("onlineCondition").path("iconId").asText());
        var sheet = MAPPER.createObjectNode();
        sheet.putArray("effects").add(effect);
        assertEquals("cond_burning", OnlineTokenConditions.actorConditions(sheet).getFirst().getIconId());
    }

    @Test
    void createsDistinctLinkedEffectsWithSrdDescriptions() {
        var hidden = DmProject.TokenCondition.builder()
                .id("hidden").type("hidden").name("Hidden").build();
        var vulnerable = DmProject.TokenCondition.builder()
                .id("vulnerable").type("vulnerable").name("Vulnerable").build();

        var hiddenEffect = OnlineTokenConditions.effect(MAPPER.createObjectNode(), "token-1", hidden);
        var vulnerableEffect = OnlineTokenConditions.effect(MAPPER.createObjectNode(), "token-1", vulnerable);

        assertEquals("online-condition:token-1:hidden", hiddenEffect.path("id").asText());
        assertEquals("buff", hiddenEffect.path("kind").asText());
        assertEquals("debuff", vulnerableEffect.path("kind").asText());
        assertEquals(OnlineTokenConditions.HIDDEN_TEXT, hiddenEffect.path("text").asText());
        assertEquals("token-1", hiddenEffect.path("onlineCondition").path("tokenId").asText());
        assertEquals("hidden", hiddenEffect.path("onlineCondition").path("id").asText());
        assertEquals(OnlineTokenConditions.VULNERABLE_TEXT, vulnerableEffect.path("text").asText());
        assertNotEquals(hiddenEffect.path("id").asText(), vulnerableEffect.path("id").asText());
        assertTrue(OnlineTokenConditions.matchesEffect(hiddenEffect, "token-1", "hidden"));
        assertFalse(OnlineTokenConditions.matchesEffect(hiddenEffect, "token-2", "hidden"));
    }

    @Test
    void inheritsSavedActorConditionsIndependentlyOfOldTokenIds() {
        var sheet = MAPPER.createObjectNode();
        var effects = sheet.putArray("effects");
        effects.add(OnlineTokenConditions.effect(MAPPER.createObjectNode(), "old-token",
                new DmProject.TokenCondition("hidden", "hidden", "Hidden")));
        effects.add(OnlineTokenConditions.effect(MAPPER.createObjectNode(), "old-token",
                new DmProject.TokenCondition("restrained", "restrained", "Restrained")));
        effects.add(OnlineTokenConditions.effect(MAPPER.createObjectNode(), "old-token",
                new DmProject.TokenCondition("custom-fire", "custom", "On Fire")));
        effects.add(OnlineTokenConditions.effect(MAPPER.createObjectNode(), "duplicate-token",
                new DmProject.TokenCondition("hidden", "hidden", "Hidden")));
        effects.addObject().put("name", "Unrelated effect").put("kind", "buff");
        var first = OnlineTokenConditions.actorConditions(sheet);
        var second = OnlineTokenConditions.actorConditions(sheet);
        assertEquals(java.util.List.of("hidden", "restrained", "custom-fire"),
                first.stream().map(DmProject.TokenCondition::getId).toList());
        assertEquals("On Fire", first.get(2).getName());
        assertNotSame(first.get(0), second.get(0));
        assertTrue(OnlineTokenConditions.matchesActorEffect(effects.get(0), "hidden"));
        effects.remove(1);
        assertEquals(java.util.List.of("hidden", "custom-fire"),
                OnlineTokenConditions.actorConditions(sheet).stream().map(DmProject.TokenCondition::getId).toList());
    }
}
