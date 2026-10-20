package dmmt;

import javafx.application.Platform;
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
