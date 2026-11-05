package dmmt.online;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses and rolls expressions such as {@code 2d6+3}, {@code d20+1d4-2}, or {@code 4d6kh3+6}. */
public final class DiceRoller {
    public record Result(String expression, List<Integer> rolls, int total) {
    }

    public record Contribution(String label, String expression) {
    }

    public record Detail(String label, String expression, List<Integer> rolls, int total) {
    }

    private record Keep(int offset, int count, int kept) {
    }

    private record Terms(List<Integer> dice, int modifier, List<Keep> keeps, int multiplier) {
    }

    private static final Pattern TERM = Pattern.compile("([+-]?)(?:(\\d*)d(\\d+)(?:kh(\\d+))?|(\\d+))");
    private static final int MAX_SIDES = 1000;

    private DiceRoller() {
    }

    /** @throws IllegalArgumentException when the expression is invalid or exceeds {@code maxDice} dice */
    public static Result roll(String expression, int maxDice, Random random) {
        String cleaned = expression == null ? "" : expression.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        if (cleaned.startsWith("/roll")) {
            cleaned = cleaned.substring(5);
        }
        if (cleaned.isEmpty() || cleaned.length() > 100) {
            throw new IllegalArgumentException("Invalid dice expression");
        }
        Terms terms = terms(cleaned, maxDice);
        List<Integer> rolls = new ArrayList<>();
        for (int die : terms.dice()) {
            int value = Integer.signum(die) * (random.nextInt(Math.abs(die)) + 1);
            rolls.add(value);
        }
        return new Result(cleaned, rolls, total(terms, rolls));
    }

    /** Resolves sheet-provided descriptions using host dice, rejecting a different formula. */
    public static List<Detail> breakdown(Result result, List<Contribution> contributions, int maxDice) {
        Terms expected = terms(result.expression(), maxDice);
        List<Integer> dice = new ArrayList<>();
        List<Keep> keeps = new ArrayList<>();
        List<Detail> details = new ArrayList<>();
        int modifier = 0;
        int index = 0;
        for (Contribution contribution : contributions) {
            Terms part = terms(contribution.expression(), maxDice);
            if (part.multiplier() != expected.multiplier()) {
                throw new IllegalArgumentException("Roll breakdown does not match the damage multiplier");
            }
            for (Keep keep : part.keeps()) {
                keeps.add(new Keep(index + keep.offset(), keep.count(), keep.kept()));
            }
            dice.addAll(part.dice());
            modifier += part.modifier();
            if (index + part.dice().size() > result.rolls().size()) {
                throw new IllegalArgumentException("Roll breakdown does not match the dice expression");
            }
            List<Integer> values = List.copyOf(result.rolls().subList(index, index + part.dice().size()));
            index += values.size();
            details.add(new Detail(contribution.label(), contribution.expression(), values,
                    total(part, values)));
        }
        if (!dice.equals(expected.dice()) || modifier != expected.modifier() || !keeps.equals(expected.keeps())) {
            throw new IllegalArgumentException("Roll breakdown does not match the dice expression");
        }
        return details;
    }

    private static int total(Terms terms, List<Integer> rolls) {
        int total = terms.modifier() + rolls.stream().mapToInt(Integer::intValue).sum();
        for (Keep keep : terms.keeps()) {
            List<Integer> values = rolls.subList(keep.offset(), keep.offset() + keep.count());
            int sign = Integer.signum(values.getFirst());
            int discarded = values.stream().mapToInt(Math::abs).sorted()
                    .limit(keep.count() - keep.kept()).sum();
            total -= sign * discarded;
        }
        return total * terms.multiplier();
    }

    private static Terms terms(String expression, int maxDice) {
        expression = expression == null ? "" : expression.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        if (expression.isEmpty() || expression.length() > 100) {
            throw new IllegalArgumentException("Invalid dice expression");
        }
        int multiplier = 1;
        if (expression.startsWith("(") && expression.endsWith(")*2")) {
            expression = expression.substring(1, expression.length() - 3);
            multiplier = 2;
        }
        Matcher matcher = TERM.matcher(expression);
        int position = 0;
        int total = 0;
        int dice = 0;
        List<Integer> sidesList = new ArrayList<>();
        List<Keep> keeps = new ArrayList<>();
        boolean first = true;
        while (position < expression.length()) {
            if (!matcher.find(position) || matcher.start() != position) {
                throw new IllegalArgumentException("Invalid dice expression");
            }
            String sign = matcher.group(1);
            if (!first && sign.isEmpty()) {
                throw new IllegalArgumentException("Invalid dice expression");
            }
            int factor = "-".equals(sign) ? -1 : 1;
            if (matcher.group(3) != null) {
                int count = matcher.group(2).isEmpty() ? 1 : parse(matcher.group(2));
                int sides = parse(matcher.group(3));
                int kept = matcher.group(4) == null ? count : parse(matcher.group(4));
                if (count < 1 || sides < 1 || sides > MAX_SIDES || kept < 1 || kept > count) {
                    throw new IllegalArgumentException("Invalid dice expression");
                }
                dice += count;
                if (dice > maxDice) {
                    throw new IllegalArgumentException("At most " + maxDice + " dice per roll");
                }
                if (kept < count) {
                    keeps.add(new Keep(sidesList.size(), count, kept));
                }
                for (int i = 0; i < count; i++) {
                    sidesList.add(factor * sides);
                }
            } else {
                total += factor * parse(matcher.group(5));
            }
            first = false;
            position = matcher.end();
        }
        if (first) throw new IllegalArgumentException("Invalid dice expression");
        return new Terms(sidesList, total, keeps, multiplier);
    }

    private static int parse(String digits) {
        if (digits.length() > 6) {
            throw new IllegalArgumentException("Number too large");
        }
        return Integer.parseInt(digits);
    }
}
