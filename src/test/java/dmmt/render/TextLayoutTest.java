package dmmt.render;

import dmmt.model.DmProject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextLayoutTest {
    /** Every character is 10 units wide per 10 font size (so size 10 => 10 wide). */
    private static final TextLayout.Metrics FIXED = new TextLayout.Metrics() {
        @Override
        public double width(String text, int fontSize) {
            return text.length() * fontSize;
        }

        @Override
        public double lineHeight(int fontSize) {
            return fontSize * 1.5;
        }

        @Override
        public double ascent(int fontSize) {
            return fontSize;
        }
    };

    private static DmProject.TextRun run(String text, int size, String color) {
        return DmProject.TextRun.builder().text(text).fontSize(size).color(color).build();
    }

    private static String lineText(TextLayout.Line line) {
        StringBuilder sb = new StringBuilder();
        line.fragments().forEach(f -> sb.append(f.text()));
        return sb.toString();
    }

    @Test
    void wrapsAtWordBoundaries() {
        TextLayout.Result result = TextLayout.layout(List.of(run("aaa bbb ccc", 10, "#fff")), 70, FIXED);
        assertEquals(2, result.lines().size());
        assertEquals("aaa bbb ", lineText(result.lines().get(0)));
        assertEquals("ccc", lineText(result.lines().get(1)));
        assertEquals(15, result.lines().get(1).y(), 0.0001);
    }

    @Test
    void honoursExplicitNewlines() {
        TextLayout.Result result = TextLayout.layout(List.of(run("ab\n\ncd", 10, "#fff")), 500, FIXED);
        assertEquals(3, result.lines().size());
        assertEquals("", lineText(result.lines().get(1)));
        assertEquals("cd", lineText(result.lines().get(2)));
    }

    @Test
    void breaksWordsLongerThanTheBox() {
        TextLayout.Result result = TextLayout.layout(List.of(run("abcdefghij", 10, "#fff")), 40, FIXED);
        assertEquals(List.of("abcd", "efgh", "ij"), result.lines().stream().map(TextLayoutTest::lineText).toList());
    }

    @Test
    void mixedStylesShareALineAndUseTheTallestHeight() {
        TextLayout.Result result = TextLayout.layout(
                List.of(run("ab", 10, "#111111"), run("cd", 20, "#222222")), 500, FIXED);
        assertEquals(1, result.lines().size());
        TextLayout.Line line = result.lines().get(0);
        assertEquals(2, line.fragments().size());
        assertEquals(240, line.fragments().get(1).x(), 0.0001);
        assertEquals("#222222", line.fragments().get(1).color());
        assertEquals(30, line.height(), 0.0001);
        assertEquals(20, line.baseline(), 0.0001);
    }

    @Test
    void centersEachLineIgnoringTrailingSpaces() {
        TextLayout.Result result = TextLayout.layout(List.of(run("ab cd", 10, "#fff")), 40, FIXED);
        assertEquals(2, result.lines().size());
        assertEquals(10, result.lines().get(0).fragments().get(0).x(), 0.0001);
        assertEquals(10, result.lines().get(1).fragments().get(0).x(), 0.0001);
    }

    @Test
    void wordSpanningStyleChangeStaysTogether() {
        TextLayout.Result result = TextLayout.layout(
                List.of(run("aa ", 10, "#111111"), run("bb", 10, "#222222"), run("cc", 10, "#333333")), 50, FIXED);
        assertEquals(List.of("aa ", "bbcc"), result.lines().stream().map(TextLayoutTest::lineText).toList());
        assertTrue(result.totalHeight() > 0);
    }

    @Test
    void emptyTextHasNoLines() {
        assertEquals(0, TextLayout.layout(List.of(), 100, FIXED).lines().size());
    }
}
