package dmmt.ui;

import dmmt.FxTestSupport;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tinted category and sound effect icons must survive a CSS pass with glyph, size and colour (3.35.2). */
class AudioIconsTest {

    @BeforeAll
    static void startFx() throws Exception {
        FxTestSupport.startJavaFx();
    }

    @Test
    void tintedIconsKeepTheirGlyphAndColourAfterTheStylesheetIsApplied() throws Exception {
        onFx(() -> {
            FontIcon category = assertInstanceOf(FontIcon.class,
                    AudioIcons.tinted("mdi2s-sword-cross", "#FF8800", 22));
            FontIcon effect = assertInstanceOf(FontIcon.class,
                    AudioIcons.tintedEffect("mdi2b-bird", "#33CCFF", 16));
            StackPane root = new StackPane(category, effect);
            Scene scene = new Scene(root);
            scene.getStylesheets().add(Icons.STYLESHEET);
            root.applyCss();

            assertNotNull(category.getIconCode(), "the glyph must survive the CSS pass");
            assertEquals("mdi2s-sword-cross", category.getIconCode().getDescription());
            assertEquals("mdi2b-bird", effect.getIconCode().getDescription());
            assertEquals(22, category.getIconSize());
            assertEquals(16, effect.getIconSize());
            assertEquals(Color.web("#FF8800"), category.getIconColor(),
                    "the stylesheet must not override the category colour");
            assertEquals(Color.web("#33CCFF"), effect.getIconColor());
            // The icon font is what actually draws the glyph; losing it renders an empty box.
            assertEquals("Material Design Icons", category.getFont().getFamily(),
                    "the icon font must survive the CSS pass");
            assertEquals("Material Design Icons", effect.getFont().getFamily());
            assertEquals(22, category.getFont().getSize());
        });
    }

    @Test
    void theWholeMaterialDesignSetIsSearchableByName() {
        assertTrue(AudioIcons.allChoices().size() > 5000, "the picker offers the complete icon pack");

        java.util.List<org.kordamp.ikonli.Ikon> suggested = AudioIcons.choices();
        assertSame(suggested, AudioIcons.search("  ", suggested, 300),
                "a blank query keeps the curated suggestions");

        java.util.List<org.kordamp.ikonli.Ikon> trees = AudioIcons.search("tree", suggested, 300);
        assertTrue(trees.size() > 3, "searching finds icons outside the suggestions: " + trees.size());
        assertTrue(trees.stream().allMatch(icon -> AudioIcons.searchName(icon).contains("tree")));
        assertTrue(trees.stream().anyMatch(icon -> "mdi2p-pine-tree".equals(icon.getDescription())));
        assertEquals(trees.size(), new java.util.LinkedHashSet<>(trees).size(), "no icon is listed twice");

        assertTrue(AudioIcons.search("pine tree", suggested, 300).stream()
                .allMatch(icon -> AudioIcons.searchName(icon).contains("pine")), "every word must match");
        assertTrue(AudioIcons.search("nosuchiconname", suggested, 300).isEmpty());
        assertEquals(5, AudioIcons.search("a", suggested, 5).size(), "the result list is capped");
        assertTrue(AudioIcons.search("mountain", suggested, 300).stream()
                .anyMatch(icon -> icon == AudioCustomIkon.MOUNTAIN));
        assertTrue(AudioIcons.search("cave", suggested, 300).stream()
                .anyMatch(icon -> icon == AudioCustomIkon.CAVE));
        assertTrue(suggested.contains(org.kordamp.ikonli.materialdesign2.MaterialDesignF.FOREST));
        assertTrue(suggested.contains(org.kordamp.ikonli.materialdesign2.MaterialDesignT.TERRAIN));
        assertTrue(AudioIcons.effectChoices().contains(AudioCustomIkon.CAVE));
    }

    @Test
    void customWildernessIconsRenderTintedAsVectorArtwork() throws Exception {
        onFx(() -> {
            Node caveNode = AudioIcons.tinted(AudioCustomIkon.CAVE.getDescription(), "#33CCFF", 24);
            AudioCustomIconView cave = assertInstanceOf(AudioCustomIconView.class, caveNode);
            Node mountainNode = AudioIcons.tintedEffect(AudioCustomIkon.MOUNTAIN.getDescription(), "#FF8800", 20);
            AudioCustomIconView mountain = assertInstanceOf(AudioCustomIconView.class, mountainNode);
            StackPane root = new StackPane(caveNode, mountainNode);
            Scene scene = new Scene(root);
            scene.getStylesheets().add(Icons.STYLESHEET);
            root.applyCss();

            assertEquals(AudioCustomIkon.CAVE.getDescription(), cave.iconDescription());
            assertEquals(Color.web("#33CCFF"), cave.iconColor());
            assertEquals(24, cave.getPrefWidth());
            assertEquals(AudioCustomIkon.MOUNTAIN.getDescription(), mountain.iconDescription());
            assertEquals(Color.web("#FF8800"), mountain.iconColor());
            assertTrue(AudioIcons.allChoices().contains(AudioCustomIkon.MOUNTAIN));
            assertTrue(AudioIcons.allChoices().contains(AudioCustomIkon.CAVE));
        });
    }

    private static void onFx(Runnable action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            action.run();
            return null;
        });
        Platform.runLater(task);
        task.get(30, TimeUnit.SECONDS);
    }
}
