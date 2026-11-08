package dmmt.online;

import com.fasterxml.jackson.databind.ObjectMapper;
import dmmt.model.DmProject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OnlineTokenConditionsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
