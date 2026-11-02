package dmmt.online;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class DiceRollerTest {
    @Test
    void resolvesEveryContributionFromTheOriginalSignedDice() {
        var result = DiceRoller.roll("2d12-1d6+3+1d4", 20, new Random(42));
        var details = DiceRoller.breakdown(result, List.of(
                new DiceRoller.Contribution("Hope", "1d12"),
                new DiceRoller.Contribution("Fear", "1d12"),
                new DiceRoller.Contribution("Disadvantage", "-1d6"),
                new DiceRoller.Contribution("Agility", "2"),
                new DiceRoller.Contribution("Potion", "2+1d4"),
                new DiceRoller.Contribution("Armor penalty", "-1")
        ), 20);
        assertEquals(result.total(), details.stream().mapToInt(DiceRoller.Detail::total).sum());
        assertEquals(result.rolls(), details.stream().flatMap(d -> d.rolls().stream()).toList());
        assertTrue(details.get(2).total() < 0);
        assertEquals(result.rolls().get(3) + 2, details.get(4).total());
    }

    @Test
    void rejectsFalseOrInvalidBreakdowns() {
        var result = DiceRoller.roll("2d12+2", 20, new Random(42));
        for (String expression : List.of("2d12+3", "2d6+2", "2d12-1d6+2", "d12-d12+2", "2d12x", "", "0d12")) {
            assertThrows(IllegalArgumentException.class, () -> DiceRoller.breakdown(result,
                    List.of(new DiceRoller.Contribution("Bad", expression)), 20), expression);
        }
    }

    @Test
    void preservesFormulaParsingAndDiceLimits() {
        var result = DiceRoller.roll("/roll D6 + 2 - 1d4", 2, new Random(42));
        assertEquals("d6+2-1d4", result.expression());
        assertEquals(2, result.rolls().size());
        assertEquals(result.rolls().stream().mapToInt(Integer::intValue).sum() + 2, result.total());
        for (String expression : List.of("3d6", "d0", "d1001", "2d6d4", "1d6+", "1000000")) {
            assertThrows(IllegalArgumentException.class, () -> DiceRoller.roll(expression, 2, new Random()));
        }
    }
}
