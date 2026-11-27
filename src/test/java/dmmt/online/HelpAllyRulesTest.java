package dmmt.online;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class HelpAllyRulesTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void mixedHelperDiceAndOwnAdvantageKeepOnlyTheHighestResult() {
        ObjectNode sheet = MAPPER.createObjectNode();
        ObjectNode input = MAPPER.createObjectNode().put("kind", "duality").put("source", "action").put("helpAdvantage", true);
        for (int sides : new int[]{8, 10}) {
            ObjectNode effect = HelpAllyRules.effect("helper-" + sides, "Helper", MAPPER.createObjectNode().put("die", sides),
                    MAPPER.createArrayNode());
            sheet.withArray("effects").add(effect);
            input.withArray("hostOptions").addObject().put("key", "effect:" + effect.path("id").asText())
                    .put("id", "help").put("helpAllyDie", 100);
        }
        ObjectNode roll = MAPPER.createObjectNode().put("total", 12);
        roll.putArray("rolls").add(3).add(5).add(4);
        roll.putArray("breakdown").addObject().put("label", "Advantage").put("expression", "1d6").put("total", 4)
                .putArray("rolls").add(4);
        Random fixed = new Random() {
            @Override public int nextInt(int bound) { return bound - 1; }
        };
        assertTrue(HelpAllyRules.applyRoll(sheet, input, roll, 50, fixed));
        assertEquals(18, roll.path("total").asInt(), "3 + 5 + highest(4, 8, 10), not all advantage dice.");
        assertEquals(0, roll.path("breakdown").get(0).path("total").asInt());
        assertEquals(0, roll.path("breakdown").get(1).path("total").asInt());
        assertEquals(10, roll.path("breakdown").get(2).path("total").asInt());
        assertEquals(0, sheet.path("effects").size());
    }

    @Test
    void individualDieRerollsRecomputeTheHighestPoolAndDiscardedDice() {
        ObjectNode roll = MAPPER.createObjectNode().put("total", 22);
        roll.putObject("helpAlly").put("highest", 8).putArray("labels").add("Help from Helper");
        ObjectNode own = roll.withArray("breakdown").addObject().put("label", "Advantage")
                .put("expression", "1d6").put("total", 0);
        own.putArray("rolls").add(6);
        own.putArray("dropped").add(0);
        ObjectNode helper = roll.withArray("breakdown").addObject().put("label", "Help from Helper")
                .put("expression", "1d8").put("total", 8);
        helper.putArray("rolls").add(8);
        // The generic reroll path first adds the die's change (6 - 2), even for a discarded die.
        own.put("total", 4);
        HelpAllyRules.refreshPool(roll);
        assertEquals(18, roll.path("total").asInt(), "10 + highest(6, 8), not the discarded die's change.");
        assertEquals(0, own.path("total").asInt());
        assertEquals(8, helper.path("total").asInt());
        assertEquals(8, roll.path("helpAlly").path("highest").asInt());

        // The former winner falls from 8 to 1: the previously discarded own die must now win.
        roll.put("total", 11);
        helper.put("total", 1);
        helper.putArray("rolls").add(1);
        HelpAllyRules.refreshPool(roll);
        assertEquals(16, roll.path("total").asInt());
        assertEquals(6, own.path("total").asInt());
        assertTrue(own.path("dropped").isEmpty());
        assertEquals(0, helper.path("total").asInt());
        assertEquals(0, helper.path("dropped").get(0).asInt());
        assertEquals(6, roll.path("helpAlly").path("highest").asInt());
    }

    @Test
    void cancelledHelpDoesNotRollDiceAndStillExpiresOnTheAction() {
        ObjectNode sheet = MAPPER.createObjectNode();
        ObjectNode effect = HelpAllyRules.effect("helper", "Helper", MAPPER.createObjectNode().put("die", 6),
                MAPPER.createArrayNode());
        sheet.withArray("effects").add(effect);
        ObjectNode input = MAPPER.createObjectNode().put("kind", "duality").put("source", "action").put("helpAdvantage", false);
        input.putArray("hostOptions").addObject().put("key", "effect:" + effect.path("id").asText()).put("id", "help").put("helpAllyDie", 10);
        ObjectNode roll = MAPPER.createObjectNode().put("total", 8);
        roll.putArray("rolls").add(3).add(5);
        assertTrue(HelpAllyRules.applyRoll(sheet, input, roll, 50, new Random() {
            @Override public int nextInt(int bound) { fail("Cancelled help must not roll."); return 0; }
        }));
        assertEquals(8, roll.path("total").asInt());
        assertEquals(2, roll.path("rolls").size());
        assertEquals(0, sheet.path("effects").size());
    }

    @Test
    void helpBuffsAllowManualRemovalButRejectForgeryAndEdits() {
        ObjectNode before = MAPPER.createObjectNode();
        before.withArray("effects").add(HelpAllyRules.effect("helper", "Helper", MAPPER.createObjectNode().put("die", 6),
                MAPPER.createArrayNode()));
        assertDoesNotThrow(() -> HelpAllyRules.validateEffects(before, MAPPER.createObjectNode()));
        ObjectNode edited = before.deepCopy();
        ((ObjectNode) edited.path("effects").get(0).path("helpAlly")).put("die", 10);
        assertThrows(IllegalArgumentException.class, () -> HelpAllyRules.validateEffects(before, edited));
        assertThrows(IllegalArgumentException.class, () -> HelpAllyRules.validateEffects(MAPPER.createObjectNode(), before));
        assertThrows(IllegalArgumentException.class, () -> HelpAllyRules.die(MAPPER.getNodeFactory().numberNode(7)));
    }
}
