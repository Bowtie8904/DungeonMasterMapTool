package dmmt.ui;

import dmmt.FxTestSupport;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Slider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.*;

import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
