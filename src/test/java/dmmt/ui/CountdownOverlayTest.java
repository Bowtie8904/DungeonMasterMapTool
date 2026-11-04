package dmmt.ui;

import dmmt.FxTestSupport;
import dmmt.api.DmControlApi;
import dmmt.api.LocalApiServer;
import dmmt.online.Campaign;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CountdownOverlayTest {
    @BeforeAll
    static void startFx() throws Exception {
        FxTestSupport.startJavaFx();
    }

    @Test
    void contentHeightBoundsHostingVisibilityAndHiddenApiControls() throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            var actions = new ArrayList<String>();
            var overlay = new CountdownOverlay(() -> actions.add("add"),
                    (id, delta) -> actions.add(id + ":" + delta), id -> actions.add("delete:" + id));
            var root = new StackPane(overlay);
            new Scene(root, 1000, 700).getStylesheets().add(
                    CountdownOverlay.class.getResource("/dmmt/ui/dark.css").toExternalForm());
            assertFalse(overlay.isVisible());
            var visibility = new ControlVisibility();
            visibility.register("online.countdowns.add", overlay.addButton());
            var api = new DmControlApi(visibility);
            assertThrows(LocalApiServer.ApiException.class, () -> api.execute("online.countdowns.add", Map.of()));
            overlay.setHosted(true, false);
            root.applyCss();
            root.layout();
            double empty = overlay.getHeight();
            List<Campaign.Countdown> entries = java.util.stream.IntStream.range(0, 5)
                    .mapToObj(i -> new Campaign.Countdown("c" + i, "Countdown " + i, 6, i)).toList();
            assertTrue(overlay.setCountdowns(entries));
            assertFalse(overlay.setCountdowns(entries));
            root.applyCss();
            root.layout();
            double five = overlay.getHeight();
            assertTrue(five > empty);
            assertTrue(five < 260, "Five countdowns remain compact");
            api.replaceGroup("online.countdowns", overlay.apiControls());
            assertEquals(16, api.describe().size());
            assertThrows(LocalApiServer.ApiException.class, () -> api.execute("online.countdowns.c0.down", Map.of()));
            api.execute("online.countdowns.c1.down", Map.of());
            api.execute("online.countdowns.c1.up", Map.of());
            api.execute("online.countdowns.c1.delete", Map.of());
            overlay.setHosted(true, true);
            visibility.apply(java.util.Set.of("online.countdowns.add"));
            assertFalse(overlay.isVisible());
            api.execute("online.countdowns.add", Map.of());
            api.execute("online.countdowns.c2.down", Map.of());
            assertEquals(List.of("c1:-1", "c1:1", "delete:c1", "add", "c2:-1"), actions);
            overlay.setCountdowns(List.of(new Campaign.Countdown("done", "Complete", 2, 2)));
            api.replaceGroup("online.countdowns", overlay.apiControls());
            assertThrows(LocalApiServer.ApiException.class, () -> api.execute("online.countdowns.done.up", Map.of()));
            assertTrue(api.describe(List.of("online.countdowns.c1.delete")).isEmpty());
            overlay.setHosted(true, false);
            root.applyCss();
            root.layout();
            assertTrue(overlay.getHeight() < five);
            overlay.setHosted(false, false);
            assertFalse(overlay.isVisible());
            assertThrows(LocalApiServer.ApiException.class, () -> api.execute("online.countdowns.done.down", Map.of()));
            return null;
        });
        Platform.runLater(task);
        task.get(15, TimeUnit.SECONDS);
    }
}
