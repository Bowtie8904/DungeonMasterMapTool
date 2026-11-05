package dmmt.online;

import dmmt.FxTestSupport;
import dmmt.model.DmProject;
import dmmt.service.Tuning;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CountdownTest {
    @TempDir Path temp;

    @BeforeAll
    static void startFx() throws Exception {
        FxTestSupport.startJavaFx();
    }

    @Test
    void validatesNamesValuesAndBothStepBoundsWithoutOverflow() {
        var countdown = new Campaign.Countdown("one", "  Reinforcements  ", 6, 6);
        assertEquals("Reinforcements", countdown.name());
        assertEquals(6, countdown.step(1).value());
        assertEquals(5, countdown.step(-1).value());
        assertEquals(0, new Campaign.Countdown("zero", "Zero", 0, 0).step(-1).value());
        assertEquals(0, new Campaign.Countdown("zero", "Zero", 0, 0).step(1).value());
        assertEquals(Integer.MAX_VALUE,
                new Campaign.Countdown("max", "Max", Integer.MAX_VALUE, Integer.MAX_VALUE).step(1).value());
        for (String name : List.of("", " ", "a\nb", "x".repeat(101))) {
            assertThrows(IllegalArgumentException.class, () -> new Campaign.Countdown("one", name, 6, 6));
        }
        assertThrows(IllegalArgumentException.class, () -> new Campaign.Countdown("one", null, 6, 6));
        assertThrows(IllegalArgumentException.class, () -> new Campaign.Countdown("one", "Name", -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Campaign.Countdown("one", "Name", 1, 2));
        assertThrows(IllegalArgumentException.class, () -> new Campaign.Countdown("one", "Name", 1, -1));
        assertThrows(IllegalArgumentException.class, () -> countdown.step(2));
    }

    @Test
    void exportsOrderedCountdownsAndAnEmptyArrayForOlderCampaigns() throws Exception {
        CampaignStore store = new CampaignStore(temp);
        Path legacy = Files.createDirectories(temp.resolve("legacy"));
        Files.writeString(legacy.resolve("campaign.json"), "{\"id\":\"legacy\",\"name\":\"Old campaign\"}");
        Campaign campaign = store.load("legacy");
        assertEquals(List.of(), campaign.getCountdowns());
        var builder = new OnlineStateBuilder(file -> null);
        var context = new OnlineStateBuilder.Context("map", "level", "Map", null, 0, false, false);
        var assets = new AssetRegistry(temp.resolve("cache"), 2048, store);
        assertTrue(builder.build(new DmProject(), context, campaign, assets, List.of()).path("countdowns").isEmpty());
        campaign.getCountdowns().add(new Campaign.Countdown("one", "First", 6, 3));
        campaign.getCountdowns().add(new Campaign.Countdown("two", "Second", 4, 0));
        store.save(campaign);
        var state = builder.build(new DmProject(), context, new CampaignStore(temp).load("legacy"), assets, List.of());
        assertEquals(2, state.path("countdowns").size());
        assertEquals("one", state.path("countdowns").get(0).path("id").asText());
        assertEquals("First", state.path("countdowns").get(0).path("name").asText());
        assertEquals(6, state.path("countdowns").get(0).path("initialValue").asInt());
        assertEquals(3, state.path("countdowns").get(0).path("value").asInt());
        assertEquals(0, state.path("countdowns").get(1).path("value").asInt());
    }

    @Test
    void hostEditsPersistAcrossRestartsAndFailedSavesDoNotChangeState() throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            try (var socket = new java.net.ServerSocket(0)) {
                String port = String.valueOf(socket.getLocalPort());
                Tuning.apply(key -> "online.port".equals(key) ? port : null);
            }
            var controller = new OnlineSessionController(temp, new TestApp());
            try {
                assertThrows(IllegalStateException.class, () -> controller.addCountdown("Offline", 4));
                Campaign campaign = controller.store().create("Countdowns");
                controller.start(campaign);
                controller.addCountdown("Reinforcements", 2);
                String id = campaign.getCountdowns().getFirst().id();
                controller.stepCountdown(id, -1);
                assertEquals(1, controller.store().load(campaign.getId()).getCountdowns().getFirst().value());
                controller.stop();
                controller.start(new CampaignStore(temp).load(campaign.getId()));
                assertEquals(id, controller.campaign().getCountdowns().getFirst().id());
                controller.stepCountdown(id, -1);
                controller.stepCountdown(id, -1);
                assertEquals(0, controller.campaign().getCountdowns().getFirst().value());
                controller.stepCountdown(id, 1);
                controller.stepCountdown(id, 1);
                controller.stepCountdown(id, 1);
                assertEquals(2, controller.campaign().getCountdowns().getFirst().value());

                Path blockedTemp = temp.resolve(campaign.getId()).resolve("campaign.json.tmp");
                Files.createDirectory(blockedTemp);
                List<Campaign.Countdown> before = List.copyOf(controller.campaign().getCountdowns());
                assertThrows(IOException.class, () -> controller.addCountdown("Not saved", 4));
                assertThrows(IOException.class, () -> controller.stepCountdown(id, -1));
                assertThrows(IOException.class, () -> controller.deleteCountdown(id));
                assertEquals(before, controller.campaign().getCountdowns());
                assertEquals(before, controller.store().load(campaign.getId()).getCountdowns());
                Files.delete(blockedTemp);

                controller.addCountdown("Reinforcements", 0);
                assertNotEquals(id, controller.campaign().getCountdowns().getLast().id());
                controller.deleteCountdown(id);
                controller.deleteCountdown(controller.campaign().getCountdowns().getFirst().id());
                assertTrue(new CampaignStore(temp).load(campaign.getId()).getCountdowns().isEmpty());
                assertThrows(IllegalArgumentException.class, () -> controller.deleteCountdown(id));
                assertThrows(IllegalArgumentException.class, () -> controller.addCountdown(" ", 4));
            } finally {
                controller.stop();
                Tuning.reset();
            }
            return null;
        });
        Platform.runLater(task);
        task.get(30, TimeUnit.SECONDS);
    }

    private static final class TestApp implements OnlineSessionController.App {
        private final DmProject project = new DmProject();
        @Override public DmProject project() { return project; }
        @Override public Path projectFile() { return null; }
        @Override public String mapId() { return "map"; }
        @Override public String levelId() { return "level"; }
        @Override public String mapName() { return "Map"; }
        @Override public boolean showGrid() { return false; }
        @Override public void applyMove(String id, double x, double y) { fail("Unexpected player move"); }
        @Override public void applyRotation(String id, double rotationDeg) { fail("Unexpected player rotation"); }
        @Override public void togglePortal(DmProject.Interactable portal) { fail("Unexpected portal interaction"); }
        @Override public void applyOverlay(String id, DmProject.OverlayShape shape) { fail("Unexpected drawing"); }
        @Override public void measurementsChanged() { }
        @Override public void status(String text) { }
        @Override public dmmt.audio.AudioEngine audioEngine() { return null; }
        @Override public dmmt.audio.AudioLibraryService audioLibrary() { return null; }
        @Override public void changed() { }
    }
}
