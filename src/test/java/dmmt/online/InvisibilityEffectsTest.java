package dmmt.online;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InvisibilityEffectsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ObjectNode caster(int tokens) {
        ObjectNode sheet = MAPPER.createObjectNode();
        sheet.putArray("loadout").addObject().put("name", "Invisibility");
        sheet.putObject("resources").put("invisibility", tokens);
        sheet.putArray("effects");
        return sheet;
    }

    private ObjectNode recipient(String source, String id) {
        ObjectNode sheet = MAPPER.createObjectNode();
        sheet.putArray("loadout");
        sheet.putObject("resources").put("invisibility", 99);
        ObjectNode effect = sheet.putArray("effects").addObject().put("id", id).put("name", "Invisibility")
                .put("linkedTokenResource", "invisibility").put("sourceActorId", source)
                .put("sourceKey", "card:Invisibility");
        effect.putArray("rollOptions").addObject().put("id", "spend-token").put("roll", "action");
        return sheet;
    }

    private ObjectNode roll(String source) {
        return MAPPER.createObjectNode().put("kind", "duality").put("source", source);
    }

    @Test
    void selectedActionSpendsCasterNotRecipientAndLastTokenEndsBuff() {
        ObjectNode caster = caster(2);
        ObjectNode recipient = recipient("caster", "invisible");
        Map<String, ObjectNode> sheets = new LinkedHashMap<>(Map.of("caster", caster, "offline", recipient));
        ObjectNode action = roll("trait:Agility,action,option:effect:invisible:spend-token");
        assertTrue(InvisibilityEffects.spendSelected(sheets, "offline", action));
        assertEquals(1, caster.path("resources").path("invisibility").asInt());
        assertEquals(99, recipient.path("resources").path("invisibility").asInt());
        assertEquals(1, recipient.path("effects").size());
        assertTrue(InvisibilityEffects.spendSelected(sheets, "offline", action));
        assertEquals(0, caster.path("resources").path("invisibility").asInt());
        assertTrue(recipient.path("effects").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> InvisibilityEffects.spendSelected(sheets, "offline", action));
    }

    @Test
    void noSelectionAndInvalidOrReactionSelectionDoNotSpend() {
        ObjectNode caster = caster(3);
        ObjectNode recipient = recipient("caster", "invisible");
        Map<String, ObjectNode> sheets = Map.of("caster", caster, "recipient", recipient);
        assertFalse(InvisibilityEffects.spendSelected(sheets, "recipient", roll("action")));
        for (String source : new String[]{
                "action,option:effect:forged:spend-token",
                "reaction,option:effect:invisible:spend-token",
                "damage,option:effect:invisible:spend-token",
                "action,option:effect:invisible:spend-token,option:effect:invisible:spend-token"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> InvisibilityEffects.spendSelected(sheets, "recipient", roll(source)));
            assertEquals(3, caster.path("resources").path("invisibility").asInt());
            assertEquals(1, recipient.path("effects").size());
        }
    }

    @Test
    void entireSelectionIsValidatedBeforeAnyCasterPays() {
        ObjectNode first = caster(2), second = caster(0);
        ObjectNode recipient = recipient("first", "first-effect");
        recipient.withArray("effects").add(recipient("second", "second-effect").path("effects").get(0));
        Map<String, ObjectNode> sheets = Map.of("first", first, "second", second, "recipient", recipient);
        assertThrows(IllegalArgumentException.class, () -> InvisibilityEffects.spendSelected(sheets, "recipient",
                roll("action,option:effect:first-effect:spend-token,option:effect:second-effect:spend-token")));
        assertEquals(2, first.path("resources").path("invisibility").asInt());
    }

    @Test
    void castResetsRatherThanAddsAndVaultingOrZeroTokensCleansEveryRecipient() {
        ObjectNode first = caster(8), second = caster(2);
        ObjectNode recipient = recipient("first", "first-effect");
        recipient.withArray("effects").add(recipient("second", "second-effect").path("effects").get(0));
        Map<String, ObjectNode> sheets = Map.of("first", first, "second", second, "offline", recipient);
        InvisibilityEffects.prepareCast(first, 3);
        assertEquals(3, first.path("resources").path("invisibility").asInt());
        first.putArray("loadout");
        InvisibilityEffects.synchronize(sheets);
        assertEquals(0, first.path("resources").path("invisibility").asInt());
        assertEquals(1, recipient.path("effects").size());
        assertEquals("second", recipient.path("effects").get(0).path("sourceActorId").asText());
        InvisibilityEffects.prepareCast(second, -2);
        InvisibilityEffects.synchronize(sheets);
        assertTrue(recipient.path("effects").isEmpty());
    }

    @Test
    void savesCannotForgeRecipientsOrChangeAHostOwnedTokenLink() {
        ObjectNode previous = recipient("caster", "effect");
        InvisibilityEffects.validateSave(previous.deepCopy(), previous);
        ObjectNode changed = previous.deepCopy();
        ((ObjectNode) changed.path("effects").get(0)).put("sourceActorId", "another-caster");
        assertThrows(IllegalArgumentException.class, () -> InvisibilityEffects.validateSave(changed, previous));
        ObjectNode empty = previous.deepCopy();
        empty.putArray("effects");
        InvisibilityEffects.validateSave(empty, previous);
        assertThrows(IllegalArgumentException.class, () -> InvisibilityEffects.validateSave(previous, empty));
    }
}
