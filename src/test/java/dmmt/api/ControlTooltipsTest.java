package dmmt.api;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class ControlTooltipsTest {
    @Test
    void mappedDescriptionsOverrideUiTextAndUnknownControlsKeepLiveDescriptions() {
        assertEquals("Place a torch on the map", ControlTooltips.tooltip("lighting.torch", "Old help"));
        assertEquals("Combat: playing", ControlTooltips.tooltip("audio.category.test", " Combat: playing "));
        assertEquals("Rain", ControlTooltips.tooltip("audio.effect.test", null, "", "Rain"));
        assertEquals("custom.control", ControlTooltips.tooltip("custom.control", null, " "));
    }

    @Test
    void productionTooltipsCoverEveryFixedShortName() throws Exception {
        Properties names = production("control-names.properties");
        Properties tooltips = production("control-tooltips.properties");
        assertTrue(tooltips.stringPropertyNames().containsAll(names.stringPropertyNames()));
        tooltips.forEach((id, text) -> {
            assertTrue(id.toString().matches("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*"), id.toString());
            assertFalse(text.toString().isBlank(), id.toString());
        });
    }

    private static Properties production(String file) throws Exception {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("src", "main", "resources", "dmmt", "api", file),
                StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }
}
