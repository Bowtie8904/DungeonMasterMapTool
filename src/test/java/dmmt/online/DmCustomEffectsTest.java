package dmmt.online;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DmCustomEffectsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode definition() {
        ObjectNode definition = mapper.createObjectNode().put("name", "Rally Die").put("kind", "buff")
                .put("until", "scene").put("uses", 2);
        definition.putArray("parts").addObject().put("type", "option").put("target", "action")
                .put("value", 0).put("dice", "1d6");
        return definition;
    }

    @Test
    void buildsIndependentManagedInstancesAndConsumesOnlyTheSelectedInstance() {
        ObjectNode first = DmCustomEffects.create(definition());
        ObjectNode second = DmCustomEffects.create(definition());
        assertNotEquals(first.path("id"), second.path("id"));
        assertTrue(first.path("rollOptions").get(0).path("hostOwned").asBoolean());
        ObjectNode sheet = mapper.createObjectNode();
        sheet.putArray("effects").add(first).add(second);
        DmCustomEffects.consume(sheet, Set.of(first.path("id").asText()));
        assertEquals(1, first.path("dmUsesRemaining").asInt());
        assertEquals(2, second.path("dmUsesRemaining").asInt());
        DmCustomEffects.consume(sheet, Set.of(first.path("id").asText()));
        assertEquals(1, sheet.path("effects").size());
        assertEquals(second.path("id"), sheet.path("effects").get(0).path("id"));
        assertThrows(IllegalArgumentException.class, () -> DmCustomEffects.consume(sheet, Set.of(first.path("id").asText())));
    }

    @Test
    void rejectsUnsupportedOrInvalidDefinitions() {
        ObjectNode badDice = definition();
        ((ObjectNode) badDice.path("parts").get(0)).put("dice", "1d999");
        assertThrows(IllegalArgumentException.class, () -> DmCustomEffects.create(badDice));
        ObjectNode invalidStat = definition();
        ((ObjectNode) invalidStat.path("parts").get(0)).put("type", "stat").put("target", "hp");
        assertThrows(IllegalArgumentException.class, () -> DmCustomEffects.create(invalidStat));
    }

    @Test
    void capacityEffectsAdjustSlotsAndRestoreThemWhenRemoved() {
        ObjectNode definition = mapper.createObjectNode().put("name", "Vigor").put("kind", "buff");
        definition.putArray("parts").addObject().put("type", "stat").put("target", "hpSlots").put("value", 2);
        ObjectNode effect = DmCustomEffects.create(definition);
        ObjectNode sheet = mapper.createObjectNode();
        sheet.putObject("hp").put("total", 12).put("slots", 6).put("on", 6);
        sheet.putObject("stress").put("total", 12).put("slots", 6).put("on", 0);
        sheet.putArray("effects").add(effect);
        DmCustomEffects.reconcileCapacity(sheet);
        DmCustomEffects.reconcileCapacity(sheet);
        assertEquals(8, sheet.path("hp").path("slots").asInt());
        assertEquals(2, sheet.path("capacityApplied").path("hp").asInt());
        ((com.fasterxml.jackson.databind.node.ArrayNode) sheet.path("effects")).removeAll();
        DmCustomEffects.reconcileCapacity(sheet);
        assertEquals(6, sheet.path("hp").path("slots").asInt());
        assertEquals(6, sheet.path("hp").path("on").asInt());
        assertFalse(sheet.has("capacityApplied"));
    }

    @Test
    void createsDamageMitigationWithTheSelectedType() {
        ObjectNode definition = mapper.createObjectNode().put("name", "Ward").put("kind", "buff");
        definition.putArray("parts").addObject().put("type", "mitigation").put("target", "magic").put("mode", "halve");
        var option = DmCustomEffects.create(definition).path("mitigation").get(0);
        assertTrue(option.path("passive").asBoolean());
        assertTrue(option.path("halve").asBoolean());
        assertEquals("magic", option.path("when").path("type").asText());
    }

    @Test
    void createsPassivePenaltyDiceAndRejectsMalformedDice() {
        ObjectNode definition = mapper.createObjectNode().put("name", "Hex").put("kind", "debuff");
        ObjectNode part = definition.putArray("parts").addObject().put("type", "roll")
                .put("target", "attack").put("value", -1).put("dice", "-d6");
        var modifier = DmCustomEffects.create(definition).path("modifiers").get(0);
        assertEquals(-1, modifier.path("value").asInt());
        assertEquals("-1d6", modifier.path("dice").asText());
        part.put("dice", "1d6+100");
        assertThrows(IllegalArgumentException.class, () -> DmCustomEffects.create(definition));
    }

    @Test
    void matchesRollTriggersOnlyForTheRightRollKind() {
        ObjectNode sheet = mapper.createObjectNode();
        sheet.putArray("effects").addObject().put("id", "action").put("dmManaged", true)
                .put("dmUsesRemaining", 1).put("dmConsumeOnRoll", "action");
        sheet.withArray("effects").addObject().put("id", "damage").put("dmManaged", true)
                .put("dmUsesRemaining", 1).put("dmConsumeOnRoll", "damage");
        assertEquals(Set.of("action"), DmCustomEffects.matchingRolls(sheet, List.of("action", "damage"), true));
        assertEquals(Set.of("damage"), DmCustomEffects.matchingRolls(sheet, List.of("action", "damage"), false));
        assertTrue(DmCustomEffects.matchingRolls(sheet, List.of("action"), false).isEmpty());
    }
}
