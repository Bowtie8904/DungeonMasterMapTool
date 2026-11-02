package dmmt.online;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses and rolls expressions such as {@code 2d6+3} or {@code d20+1d4-2}. */
public final class DiceRoller {
    public record Result(String expression, List<Integer> rolls, int total) {
    }

    private static final Pattern TERM = Pattern.compile("([+-]?)(?:(\\d*)d(\\d+)|(\\d+))");
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
        Matcher matcher = TERM.matcher(cleaned);
        int position = 0;
        int total = 0;
        int dice = 0;
        List<Integer> rolls = new ArrayList<>();
        boolean first = true;
        while (position < cleaned.length()) {
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
                if (count < 1 || sides < 1 || sides > MAX_SIDES) {
                    throw new IllegalArgumentException("Invalid dice expression");
                }
                dice += count;
                if (dice > maxDice) {
                    throw new IllegalArgumentException("At most " + maxDice + " dice per roll");
                }
                for (int i = 0; i < count; i++) {
                    int value = random.nextInt(sides) + 1;
                    rolls.add(factor * value);
                    total += factor * value;
                }
            } else {
                total += factor * parse(matcher.group(4));
            }
            first = false;
            position = matcher.end();
        }
        return new Result(cleaned, rolls, total);
    }

    private static int parse(String digits) {
        if (digits.length() > 6) {
            throw new IllegalArgumentException("Number too large");
        }
        return Integer.parseInt(digits);
    }
}
