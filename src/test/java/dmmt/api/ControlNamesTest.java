package dmmt.api;

import dmmt.service.AppSettings;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ControlNamesTest {
    @Test
    void mapsEveryActionableSidebarControlAndFixedExtraControl() {
        AppSettings.SIDEBAR_CONTROLS.stream().filter(control -> !control.id().equals("lighting.hint"))
                .forEach(control -> assertNotEquals(control.id(), ControlNames.name(control.id()), control.id()));
        for (String id : List.of("ui.library", "ui.performance", "ui.settings", "ui.controls", "ui.panelSettings",
                "maps.previous", "levels.down", "levels.up", "levels.manage", "levels.select",
                "audio.previous", "audio.play", "audio.next", "audio.overlay", "audio.musicPlay", "audio.stop", "audio.mute",
                "audio.library", "audio.effectsPause", "audio.effectsStop",
                "audio.masterVolume", "audio.musicVolume", "audio.effectsVolume")) {
            assertNotEquals(id, ControlNames.name(id), id);
        }
    }

    @Test
    void mappedNamesOverrideUiNamesWhileUnknownIdsKeepLiveNames() {
        assertEquals("Torch", ControlNames.name("lighting.torch", "Long tooltip"));
        assertEquals("Play / pause", ControlNames.name("audio.play", "Resume everything"));
        assertEquals("Battle", ControlNames.name("audio.category.test", " Battle ", "Tooltip"));
        assertEquals("Rain", ControlNames.name("audio.effect.test", "", " Rain "));
        assertEquals("custom.control", ControlNames.name("custom.control", null, " "));
    }
}
