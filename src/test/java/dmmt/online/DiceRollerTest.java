package dmmt.online;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class DiceRollerTest {
    @Test
    void zeroKeptDiceRemainVisibleButDoNotAffectTheTotal() {
        var result = DiceRoller.roll("2d8kh0+8+2", 2, faces(4, 7));
        assertEquals(List.of(4, 7), result.rolls());
        assertEquals(10, result.total());
        assertEquals(10, DiceRoller.breakdown(result, List.of(
                new DiceRoller.Contribution("Weapon", "2d8kh0+8"),
                new DiceRoller.Contribution("Bonus", "2")), 2).stream().mapToInt(DiceRoller.Detail::total).sum());
    }

    @Test
    void selectedDamageDiceRetainOtherFacesKeepsMixedSidesAndWholeRollMultipliers() {
        var original = DiceRoller.roll("(2d8kh1+1d4+3)*2", 3, faces(2, 6, 4));
        var rerolled = DiceRoller.editDice(original, List.of(0, 2), true, 3, faces(8, 1));
        assertEquals(List.of(8, 6, 1), rerolled.rolls());
        assertEquals(24, rerolled.total());
        var doubled = DiceRoller.editDice(original, List.of(0), false, 3, faces());
        assertEquals(List.of(4, 6, 4), doubled.rolls());
        assertEquals(original.total(), doubled.total());
        doubled = DiceRoller.editDice(original, List.of(1), false, 3, faces());
        assertEquals(List.of(2, 12, 4), doubled.rolls());
        assertEquals(38, doubled.total());
        assertThrows(IllegalArgumentException.class, () -> DiceRoller.editDice(original, List.of(0, 0), true, 3, faces()));
        assertThrows(IllegalArgumentException.class, () -> DiceRoller.editDice(original, List.of(0, 1), false, 3, faces()));
        assertThrows(IllegalArgumentException.class, () -> DiceRoller.editDice(original, List.of(3), true, 3, faces()));
        var negative = DiceRoller.roll("d8-d6", 2, faces(6, 2));
        assertThrows(IllegalArgumentException.class, () -> DiceRoller.editDice(negative, List.of(1), true, 2, faces()));
    }

    @Test
    void sacredResonanceMatchesAllKeptDiceAcrossSourcesWithoutDoublingModifiers() {
        var result = DiceRoller.roll("3d8kh2+2d6+9", 5, faces(1, 5, 5, 5, 3));
        assertEquals(List.of(0, 5, 5, 5, 0), DiceRoller.matchingDiceBonuses(result, 5));
        assertEquals(42, result.total() + DiceRoller.matchingDiceBonuses(result, 5).stream().mapToInt(Integer::intValue).sum());
        var discarded = DiceRoller.roll("2d8kh1+1d6", 3, faces(2, 7, 2));
        assertEquals(List.of(0, 0, 0), DiceRoller.matchingDiceBonuses(discarded, 3));
        var ties = DiceRoller.roll("3d8kh2+d6", 4, faces(2, 2, 2, 2));
        assertEquals(List.of(0, 2, 2, 2), DiceRoller.matchingDiceBonuses(ties, 4));
        var doubled = DiceRoller.roll("(2d8+7)*2", 2, faces(5, 5));
        assertEquals(List.of(10, 10), DiceRoller.matchingDiceBonuses(doubled, 2));
        var combo = new DiceRoller.Result("(2d8+7)*2", List.of(5, 3, 5), 35);
        assertEquals(List.of(10, 0, 5), DiceRoller.matchingDiceBonuses(combo, 3));
    }

    @Test
    void comboIncludesEqualFacesAndTheFinalLowerDieAndNeverModifiesThem() {
        assertEquals(List.of(1, 3, 3, 4, 2), DiceRoller.combo(4, 10, faces(1, 3, 3, 4, 2)));
        assertEquals(List.of(8, 2), DiceRoller.combo(8, 10, faces(8, 2)));
        assertThrows(IllegalArgumentException.class, () -> DiceRoller.combo(5, 10, faces()));
        assertThrows(IllegalArgumentException.class, () -> DiceRoller.combo(4, 3, faces(1, 2, 4)));
    }

    @Test
    void exactingReplacesOnesBeforeKeepingLowestPerMixedPoolWithoutChangingModifiers() {
        var result = DiceRoller.maximumOnes(DiceRoller.roll("3d8kh2+3d6kh2+1", 6, faces(1, 7, 2, 1, 3, 4)), 6);
        assertEquals(List.of(8, 7, 2, 6, 3, 4), result.rolls());
        assertEquals(26, result.total());
        var details = DiceRoller.breakdown(result, List.of(
                new DiceRoller.Contribution("Strike", "3d8kh2+3d6kh2"),
                new DiceRoller.Contribution("Bonus", "1")), 6);
        assertEquals(result.total(), details.stream().mapToInt(DiceRoller.Detail::total).sum());
        var doubled = DiceRoller.maximumOnes(DiceRoller.roll("(1d8+1)*2", 1, faces(1)), 1);
        assertEquals(18, doubled.total());
    }

    @Test
    void doublesRolledDamageIncludingKeptDiceBonusesAndCriticalWithoutExtraRolls() {
        var result = DiceRoller.roll("(3d8kh2+2+2d4+24)*2", 5, faces(1, 8, 4, 2, 3));
        assertEquals(List.of(1, 8, 4, 2, 3), result.rolls());
        assertEquals(86, result.total());
        var details = DiceRoller.breakdown(result, List.of(
                new DiceRoller.Contribution("Weapon", "(3d8kh2)*2"),
                new DiceRoller.Contribution("Modifier", "(2)*2"),
                new DiceRoller.Contribution("Marked", "(2d4)*2"),
                new DiceRoller.Contribution("Critical", "(24)*2")), 5);
        assertEquals(List.of(24, 4, 10, 48), details.stream().map(DiceRoller.Detail::total).toList());
        assertEquals(result.total(), details.stream().mapToInt(DiceRoller.Detail::total).sum());
        assertThrows(IllegalArgumentException.class, () -> DiceRoller.breakdown(result,
                List.of(new DiceRoller.Contribution("False multiplier", "3d8kh2+2+2d4+24")), 5));
        var ordinary = DiceRoller.roll("d8", 1, faces(5));
        assertThrows(IllegalArgumentException.class, () -> DiceRoller.breakdown(ordinary,
                List.of(new DiceRoller.Contribution("False multiplier", "(d8)*2")), 1));
        for (String expression : List.of("()*2", "(d8)*0", "(d8)*3", "((d8)*2)*2", "(d8+)*2", "d8*2")) {
            assertThrows(IllegalArgumentException.class, () -> DiceRoller.roll(expression, 5, faces()), expression);
        }
        assertThrows(IllegalArgumentException.class, () -> DiceRoller.roll("(3d8)*2", 2, faces()));
    }

    @Test
    void keepsHighestWithoutReorderingOrOmittingDiscardedDice() {
        var result = DiceRoller.roll("/roll 4D6KH3 + 6", 4, faces(4, 1, 6, 3));
        assertEquals("4d6kh3+6", result.expression());
        assertEquals(List.of(4, 1, 6, 3), result.rolls());
        assertEquals(19, result.total());
    }

    @Test
    void discardsExactlyTheRequiredNumberOfTiedMinima() {
        var result = DiceRoller.roll("4d6kh2", 4, faces(2, 6, 2, 2));
        assertEquals(List.of(2, 6, 2, 2), result.rolls());
        assertEquals(8, result.total());
        assertEquals(9, DiceRoller.roll("3d6kh3", 3, faces(1, 3, 5)).total());
        assertEquals(5, DiceRoller.roll("d6kh1", 1, faces(5)).total());
    }

    @Test
    void keepsGroupsIndependentAndAppliesNegativeSignAfterSelection() {
        var result = DiceRoller.roll("3d6kh2-3d8kh1+d4+2", 7, faces(1, 6, 4, 8, 2, 5, 3));
        assertEquals(List.of(1, 6, 4, -8, -2, -5, 3), result.rolls());
        assertEquals(7, result.total());
        assertEquals(-10, DiceRoller.roll("-3d6kh2", 3, faces(1, 6, 4)).total());
    }

    @Test
    void breakdownUsesKeptSumsAndPreservesAllSourceDice() {
        var result = DiceRoller.roll("4d6kh3+6-3d8kh1+2d12", 9, faces(4, 1, 6, 3, 8, 2, 5, 7, 9));
        var details = DiceRoller.breakdown(result, List.of(
                new DiceRoller.Contribution("Powerful weapon", "4d6kh3"),
                new DiceRoller.Contribution("Weapon modifier", "6"),
                new DiceRoller.Contribution("Penalty", "-3d8kh1"),
                new DiceRoller.Contribution("Hope", "d12"),
                new DiceRoller.Contribution("Fear", "d12")
        ), 9);
        assertEquals(List.of(13, 6, -8, 7, 9), details.stream().map(DiceRoller.Detail::total).toList());
        assertEquals(result.total(), details.stream().mapToInt(DiceRoller.Detail::total).sum());
        assertEquals(result.rolls(), details.stream().flatMap(d -> d.rolls().stream()).toList());
    }

    @Test
    void rejectsBreakdownsThatChangeKeptGroupsEvenWithIdenticalRawDice() {
        var result = DiceRoller.roll("4d6kh3+6", 4, faces(4, 1, 6, 3));
        for (String expression : List.of("4d6+6", "4d6kh2+6", "2d6kh1+2d6+6", "2d6+2d6kh1+6")) {
            assertThrows(IllegalArgumentException.class, () -> DiceRoller.breakdown(result,
                    List.of(new DiceRoller.Contribution("False weapon", expression)), 4), expression);
        }
        assertThrows(IllegalArgumentException.class, () -> DiceRoller.breakdown(result, List.of(
                new DiceRoller.Contribution("Split", "d6"),
                new DiceRoller.Contribution("Split", "3d6kh2+6")), 4));
        var ordinary = DiceRoller.roll("4d6+6", 4, faces(4, 1, 6, 3));
        assertThrows(IllegalArgumentException.class, () -> DiceRoller.breakdown(ordinary,
                List.of(new DiceRoller.Contribution("False keep", "4d6kh3+6")), 4));
    }

    @Test
    void rejectsInvalidKeepCountsAndCountsDiscardedDiceAgainstLimit() {
        for (String expression : List.of("4d6kh5", "4d6kh", "4d6kh-1", "4d6kh1.5",
                "0d6kh0", "4d6kh1000000", "4d6kl3", "4d6kh3kh2", "1kh1", "d6kh2",
                "4d6kh1+1d4", "5d6kh1", "999999d6kh1")) {
            assertThrows(IllegalArgumentException.class, () -> DiceRoller.roll(expression, 4, faces()), expression);
        }
        assertThrows(IllegalArgumentException.class, () -> DiceRoller.roll("d6kh1", 0, faces()));
    }

    private static Random faces(int... values) {
        return new Random() {
            private int index;

            @Override
            public int nextInt(int bound) {
                assertTrue(index < values.length, "Unexpected extra die");
                int value = values[index++];
                assertTrue(value >= 1 && value <= bound, "Face outside die range");
                return value - 1;
            }
        };
    }

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
