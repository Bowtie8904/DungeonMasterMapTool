package dmmt.ui;

import dmmt.FxTestSupport;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Slider;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.*;

import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlKeyImagesTest {
    @BeforeAll
    static void startFx() throws Exception {
        FxTestSupport.startJavaFx();
    }

    @Test
    void dropdownsUseTabIconsAndValueControlsUseSpecificIcons() throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            ComboBox<String> dropdown = new ComboBox<>();
            assertEquals(MaterialDesignW.WEATHER_PARTLY_RAINY,
                    ControlKeyImages.selectIcon("weather.type", List.of(dropdown)));
            assertEquals(MaterialDesignW.WEATHER_LIGHTNING,
                    ControlKeyImages.selectIcon("weather.lightningInterval", List.of(new Slider())));
            assertEquals(MaterialDesignF.FORMAT_PAINT,
                    ControlKeyImages.selectIcon("effects.texture", List.of(dropdown)));
            assertEquals(MaterialDesignM.MONITOR,
                    ControlKeyImages.selectIcon("player.screen", List.of(dropdown)));
            assertEquals(MaterialDesignL.LAYERS_TRIPLE_OUTLINE,
                    ControlKeyImages.selectIcon("levels.select", List.of(dropdown)));
            assertEquals(MaterialDesignC.CIRCLE_OPACITY,
                    ControlKeyImages.selectIcon("effects.opacity", List.of(new Slider())));
            assertEquals(MaterialDesignR.RULER_SQUARE,
                    ControlKeyImages.selectIcon("player.diagonal", List.of(new Slider())));
            assertEquals(MaterialDesignM.MAGNIFY,
                    ControlKeyImages.selectIcon("player.zoom", List.of(new Slider())));
            Button freeze = new Button();
            freeze.setGraphic(new FontIcon(MaterialDesignS.SNOWFLAKE));
            assertEquals(MaterialDesignS.SNOWFLAKE,
                    ControlKeyImages.selectIcon("player.freeze", List.of(freeze)));

            // The volume sliders carry no icon of their own and must not fall back to the generic glyph.
            assertEquals(MaterialDesignM.MUSIC_NOTE,
                    ControlKeyImages.selectIcon("audio.musicVolume", List.of(new Slider())));
            assertEquals(MaterialDesignV.VOLUME_HIGH,
                    ControlKeyImages.selectIcon("audio.masterVolume", List.of(new Slider())));
            assertEquals(MaterialDesignW.WAVES,
                    ControlKeyImages.selectIcon("audio.effectsVolume", List.of(new Slider())));
            return null;
        });
        Platform.runLater(task);
        task.get(10, TimeUnit.SECONDS);
    }

    @Test
    void audioKeyImagesUseTheCategoryOrEffectColour() throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            Button plain = new Button();
            plain.setGraphic(new FontIcon(MaterialDesignS.SNOWFLAKE));
            assertEquals(Color.WHITE, ControlKeyImages.selectColor(List.of(plain)),
                    "ordinary controls keep the white glyph");

            Button category = new Button();
            category.setGraphic(AudioIcons.tinted("mdi2s-sword-cross", "#FF8800", 22));
            assertEquals(Color.web("#FF8800"), ControlKeyImages.selectColor(List.of(category)));
            assertEquals(MaterialDesignS.SWORD_CROSS,
                    ControlKeyImages.selectIcon("audio.category.1234", List.of(category)));

            Button cave = new Button();
            cave.setGraphic(AudioIcons.tinted(AudioCustomIkon.CAVE.getDescription(), "#2288FF", 22));
            assertEquals(AudioCustomIkon.CAVE,
                    ControlKeyImages.selectIcon("audio.category.5678", List.of(cave)));
            assertEquals(Color.web("#2288FF"), ControlKeyImages.selectColor(List.of(cave)));
            byte[] caveKey = ControlKeyImages.png("audio.category.5678", List.of(cave), null);
            assertEquals(0x89, Byte.toUnsignedInt(caveKey[0]));
            assertEquals('P', caveKey[1]);
            assertEquals('N', caveKey[2]);
            assertEquals('G', caveKey[3]);

            Button dark = new Button();
            dark.setGraphic(AudioIcons.tintedEffect("mdi2b-bird", "#101014", 22));
            Color lightened = ControlKeyImages.selectColor(List.of(dark));
            assertTrue(lightened.getBrightness() >= 0.45,
                    "a nearly black colour must be lightened to stay readable: " + lightened);
            return null;
        });
        Platform.runLater(task);
        task.get(10, TimeUnit.SECONDS);
    }

    @Test
    void theFingerprintChangesWithTheIconAndTheColourButNotOtherwise() throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            String id = "audio.category.1234";
            Button combat = new Button();
            combat.setGraphic(AudioIcons.tinted("mdi2s-sword-cross", "#FF8800", 22));
            String original = ControlKeyImages.fingerprint(id, List.of(combat));

            Button same = new Button();
            same.setGraphic(AudioIcons.tinted("mdi2s-sword-cross", "#FF8800", 22));
            assertEquals(original, ControlKeyImages.fingerprint(id, List.of(same)),
                    "identical artwork must keep the cached key image");

            Button recoloured = new Button();
            recoloured.setGraphic(AudioIcons.tinted("mdi2s-sword-cross", "#2288FF", 22));
            assertNotEquals(original, ControlKeyImages.fingerprint(id, List.of(recoloured)),
                    "a new colour must invalidate the cached key image");

            Button reIconed = new Button();
            reIconed.setGraphic(AudioIcons.tinted("mdi2b-bird", "#FF8800", 22));
            assertNotEquals(original, ControlKeyImages.fingerprint(id, List.of(reIconed)),
                    "a new icon must invalidate the cached key image");
            return null;
        });
        Platform.runLater(task);
        task.get(10, TimeUnit.SECONDS);
    }
}
