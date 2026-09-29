package dmmt.render;

import dmmt.model.DmProject;

import java.util.ArrayList;
import java.util.List;

/** Word-wrapping layout of styled text runs into lines of positioned fragments (world units, unscaled). */
public final class TextLayout {
    private TextLayout() {
    }

    /** Font measurements; abstracted so the wrapping logic does not need a JavaFX toolkit. */
    public interface Metrics {
        double width(String text, int fontSize);

        double lineHeight(int fontSize);

        double ascent(int fontSize);
    }

    public record Fragment(String text, int fontSize, String color, double x) {
    }

    public record Line(List<Fragment> fragments, double y, double height, double baseline) {
    }

    public record Result(List<Line> lines, double totalHeight, double contentWidth, double extentWidth) {
    }

    private enum Kind { WORD, SPACE, NEWLINE }

    private record Piece(String text, int fontSize, String color) {
    }

    private record Token(Kind kind, List<Piece> pieces) {
    }

    public static Result layout(List<DmProject.TextRun> runs, double maxWidth, Metrics metrics) {
        List<Token> tokens = tokenize(runs);
        Builder builder = new Builder(Math.max(1, maxWidth), metrics);
        for (Token token : tokens) {
            switch (token.kind) {
                case NEWLINE -> builder.newline(token.pieces.get(0).fontSize);
                case SPACE -> builder.space(token);
                case WORD -> builder.word(token);
            }
        }
        builder.finish();
        return new Result(builder.lines, builder.y, builder.contentWidth, builder.extentWidth);
    }

    private static List<Token> tokenize(List<DmProject.TextRun> runs) {
        List<Token> tokens = new ArrayList<>();
        Token current = null;
        for (DmProject.TextRun run : runs) {
            String text = run.getText() == null ? "" : run.getText();
            int size = Math.max(1, run.getFontSize());
            String color = run.getColor() == null ? DmProject.DEFAULT_TEXT_COLOR : run.getColor();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\r') {
                    continue;
                }
                Kind kind = c == '\n' ? Kind.NEWLINE : Character.isWhitespace(c) ? Kind.SPACE : Kind.WORD;
                if (c == '\t') {
                    c = ' ';
                }
                if (kind == Kind.NEWLINE || current == null || current.kind != kind) {
                    current = new Token(kind, new ArrayList<>());
                    tokens.add(current);
                }
                List<Piece> pieces = current.pieces;
                Piece last = pieces.isEmpty() ? null : pieces.get(pieces.size() - 1);
                if (last != null && last.fontSize == size && last.color.equals(color)) {
                    pieces.set(pieces.size() - 1, new Piece(last.text + c, size, color));
                } else {
                    pieces.add(new Piece(String.valueOf(c), size, color));
                }
            }
        }
        return tokens;
    }

    private static final class Builder {
        private final double maxWidth;
        private final Metrics metrics;
        private final List<Line> lines = new ArrayList<>();
        private List<Fragment> fragments = new ArrayList<>();
        private double x;
        private double y;
        private double lineHeight;
        private double lineAscent;
        private int lastFontSize = DmProject.DEFAULT_TEXT_SIZE;
        private boolean softWrapped;
        private double contentWidth;
        private double extentWidth;

        private Builder(double maxWidth, Metrics metrics) {
            this.maxWidth = maxWidth;
            this.metrics = metrics;
        }

        private void newline(int fontSize) {
            lastFontSize = fontSize;
            endLine();
            softWrapped = false;
        }

        private void space(Token token) {
            if (fragments.isEmpty() && softWrapped) {
                return;
            }
            for (Piece piece : token.pieces) {
                double width = metrics.width(piece.text, piece.fontSize);
                add(piece, width);
            }
        }

        private void word(Token token) {
            double width = 0;
            for (Piece piece : token.pieces) {
                width += metrics.width(piece.text, piece.fontSize);
            }
            if (x + width > maxWidth && !fragments.isEmpty()) {
                wrap();
            }
            if (width <= maxWidth) {
                for (Piece piece : token.pieces) {
                    add(piece, metrics.width(piece.text, piece.fontSize));
                }
                return;
            }
            // A single word wider than the box is broken between characters.
            for (Piece piece : token.pieces) {
                StringBuilder chunk = new StringBuilder();
                for (int i = 0; i < piece.text.length(); i++) {
                    String next = chunk.toString() + piece.text.charAt(i);
                    if (x + metrics.width(next, piece.fontSize) > maxWidth && (chunk.length() > 0 || !fragments.isEmpty())) {
                        if (chunk.length() > 0) {
                            add(new Piece(chunk.toString(), piece.fontSize, piece.color),
                                    metrics.width(chunk.toString(), piece.fontSize));
                            chunk.setLength(0);
                        }
                        wrap();
                        next = String.valueOf(piece.text.charAt(i));
                    }
                    chunk.setLength(0);
                    chunk.append(next);
                }
                if (chunk.length() > 0) {
                    add(new Piece(chunk.toString(), piece.fontSize, piece.color),
                            metrics.width(chunk.toString(), piece.fontSize));
                }
            }
        }

        private void wrap() {
            endLine();
            softWrapped = true;
        }

        private void add(Piece piece, double width) {
            lastFontSize = piece.fontSize;
            lineHeight = Math.max(lineHeight, metrics.lineHeight(piece.fontSize));
            lineAscent = Math.max(lineAscent, metrics.ascent(piece.fontSize));
            fragments.add(new Fragment(piece.text, piece.fontSize, piece.color, x));
            x += width;
        }

        private void endLine() {
            double height = fragments.isEmpty() ? metrics.lineHeight(lastFontSize) : lineHeight;
            double ascent = fragments.isEmpty() ? metrics.ascent(lastFontSize) : lineAscent;
            extentWidth = Math.max(extentWidth, x);
            List<Fragment> placed = center(fragments);
            lines.add(new Line(placed, y, height, ascent));
            y += height;
            fragments = new ArrayList<>();
            x = 0;
            lineHeight = 0;
            lineAscent = 0;
        }

        /** Shifts a line so its visible text (ignoring trailing spaces) is centered within the width. */
        private List<Fragment> center(List<Fragment> line) {
            double end = 0;
            for (Fragment fragment : line) {
                if (!fragment.text.isBlank()) {
                    end = fragment.x + metrics.width(fragment.text.stripTrailing(), fragment.fontSize);
                }
            }
            double start = 0;
            for (Fragment fragment : line) {
                if (!fragment.text.isBlank()) {
                    start = fragment.x;
                    break;
                }
            }
            contentWidth = Math.max(contentWidth, end - start);
            double shift = Math.max(0, (maxWidth - (end - start)) / 2) - start;
            List<Fragment> shifted = new ArrayList<>();
            for (Fragment fragment : line) {
                shifted.add(new Fragment(fragment.text, fragment.fontSize, fragment.color, fragment.x + shift));
            }
            return shifted;
        }

        private void finish() {
            if (!fragments.isEmpty()) {
                endLine();
            }
        }
    }
}
