package dmmt.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChatCardsTest {
    @Test
    void keepHighestDropsLowestDice() {
        assertArrayEquals(new boolean[]{true, false, true}, ChatCards.keptRolls("3d20kh2", List.of(15, 3, 9)));
    }

    @Test
    void parsesRestReport() {
        var report = ChatCards.parseRestReport("\uD83C\uDFD5\uFE0F LONG REST\nParty\n\u2022 Healed 2 HP");
        assertNotNull(report);
        assertEquals(true, report.longRest());
        assertEquals(List.of("Healed 2 HP"), report.lines());
        assertNull(ChatCards.parseRestReport("just chatting"));
    }

    @Test
    void parsesDamageReport() {
        var report = ChatCards.parseDamageReport("DAMAGE TAKEN\nAria\n9 physical damage \u2192 6 \u00B7 Major \u00B7 2 HP marked\n\u2022 Armor used");
        assertNotNull(report);
        assertEquals(6, report.damage());
        assertEquals(2, report.hp());
        assertEquals("Major", report.severity());
    }

    @Test
    void parsesActionReport() {
        var report = ChatCards.parseActionReport("ACTION USED\nMinor Health Potion - Drink\nJitta\n\u2022 cleared 2 HP (1d4)");
        assertNotNull(report);
        assertEquals("Minor Health Potion - Drink", report.title());
        assertEquals("Jitta", report.character());
        assertEquals(List.of("cleared 2 HP (1d4)"), report.lines());
        assertNull(ChatCards.parseActionReport("Jitta: Potion - Drink: cleared 2 HP"));
    }

    @Test
    void derivesDetailsFromExpression() {
        var details = ChatCards.rollDetails("2d6+3", List.of(4, 5), false, null);
        assertEquals(2, details.size());
        assertEquals(9, details.get(0).total());
        assertEquals(3, details.get(1).total());
    }
}
