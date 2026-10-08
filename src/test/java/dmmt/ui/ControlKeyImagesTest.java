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
            assertEquals(MaterialDesignF.FORMAT_PAINT,
                    ControlKeyImages.selectIcon("effects.texture", List.of(dropdown)));
            assertEquals(MaterialDesignP.PROJECTOR,
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
}
