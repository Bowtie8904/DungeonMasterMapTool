package dmmt;

import dmmt.ui.Icons;
import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PlayerWindowTest {
    @BeforeAll
    static void startFx() throws Exception {
        FxTestSupport.startJavaFx();
    }

    @Test
    void playerWindowIsIndependentAndCanBeClosedAndReopened() throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            DungeonMasterMapToolApplication app = new DungeonMasterMapToolApplication();
            Stage dmStage = new Stage();
            var primaryStage = DungeonMasterMapToolApplication.class.getDeclaredField("primaryStage");
            primaryStage.setAccessible(true);
            primaryStage.set(app, dmStage);
            var appIcons = DungeonMasterMapToolApplication.class.getDeclaredMethod("appIcons");
            appIcons.setAccessible(true);
            var dmIcons = ((java.util.List<?>) appIcons.invoke(null)).stream().map(Image.class::cast).toList();
            dmStage.getIcons().setAll(dmIcons);
            var playerStage = DungeonMasterMapToolApplication.class.getDeclaredField("playerStage");
            playerStage.setAccessible(true);
            var open = DungeonMasterMapToolApplication.class.getDeclaredMethod("openPlayerWindow");
            open.setAccessible(true);
            var close = DungeonMasterMapToolApplication.class.getDeclaredMethod("closePlayerWindow");
            close.setAccessible(true);
            try {
                for (int i = 0; i < 2; i++) {
                    open.invoke(app);
                    Stage player = (Stage) playerStage.get(app);
                    assertTrue(player.isShowing());
                    assertNull(player.getOwner(), "DM minimization must not propagate to the player window");
                    assertFalse(player.isAlwaysOnTop());
                    assertFalse(dmStage.isAlwaysOnTop());
                    assertEquals(Modality.NONE, player.getModality());
                    assertEquals(StageStyle.UNDECORATED, player.getStyle());
                    assertEquals(Icons.playerWindowIcons(), player.getIcons());
                    assertEquals(dmIcons, dmStage.getIcons(), "Player icon must not replace the DM icon");
                    assertEquals(java.util.List.of(16, 24, 32, 48, 64, 128, 256),
                            player.getIcons().stream().map(image -> (int) image.getWidth()).toList());
                    for (Image icon : player.getIcons()) {
                        assertFalse(icon.isError());
                        assertEquals(icon.getWidth(), icon.getHeight());
                        int size = (int) icon.getWidth();
                        var pixels = icon.getPixelReader();
                        assertEquals(0, pixels.getArgb(0, 0), "Icon background must be transparent");
                        int framePixel = pixels.getArgb(size / 2, size * 5 / 32);
                        assertEquals(0x22D3EE, framePixel & 0xFFFFFF,
                                "Monitor frame must be cyan at every size");
                        assertTrue((framePixel >>> 24) >= 200, "Monitor frame must remain visible at every size");
                        Image dmIcon = dmIcons.stream().filter(image -> image.getWidth() == size).findFirst().orElseThrow();
                        boolean differs = false;
                        for (int y = 0; y < size && !differs; y++) {
                            for (int x = 0; x < size; x++) {
                                if (pixels.getArgb(x, y) != dmIcon.getPixelReader().getArgb(x, y)) {
                                    differs = true;
                                    break;
                                }
                            }
                        }
                        assertTrue(differs, "Player and DM icons must differ at " + size + " pixels");
                    }
                    close.invoke(app);
                    assertFalse(player.isShowing());
                    assertNull(playerStage.get(app));
                }
                return null;
            } finally {
                close.invoke(app);
                dmStage.close();
            }
        });
        Platform.runLater(task);
        task.get(20, TimeUnit.SECONDS);
    }
}
