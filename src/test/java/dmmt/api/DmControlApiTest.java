package dmmt.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dmmt.DungeonMasterMapToolApplication;
import dmmt.FxTestSupport;
import dmmt.model.DmProject;
import dmmt.service.AppSettings;
import dmmt.ui.ControlVisibility;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class DmControlApiTest {
    @TempDir Path dir;

    @BeforeAll
    static void startFx() throws Exception {
        FxTestSupport.startJavaFx();
    }

    @Test
    void invokesActionsTogglesColorAndConverterDropdownWithoutUnsafeCasts() throws Exception {
        onFx(() -> {
            DmControlApi api = new DmControlApi(new ControlVisibility());
            int[] actions = {0};
            Button button = new Button();
            button.setOnAction(event -> actions[0]++);
            ToggleButton toggle = new ToggleButton();
            CheckBox check = new CheckBox();
            ColorPicker color = new ColorPicker();
            color.setOnAction(event -> {
                assertEquals(Color.web("#12345680"), color.getValue());
                actions[0]++;
            });
            ComboBox<Thread.State> combo = new ComboBox<>();
            combo.getItems().setAll(Thread.State.NEW, Thread.State.RUNNABLE);
            combo.setConverter(new StringConverter<>() {
                @Override public String toString(Thread.State state) {
                    return state == null ? "" : "State " + state.name().toLowerCase(Locale.ROOT);
                }
                @Override public Thread.State fromString(String text) { throw new UnsupportedOperationException(); }
            });
            combo.setOnAction(event -> actions[0]++);
            api.add("button", button);
            api.add("toggle", toggle);
            api.add("check", check);
            api.add("color", color);
            api.add("combo", combo);
            api.execute("button", Map.of());
            assertEquals(true, api.execute("toggle", Map.of()).get("value"));
            assertEquals(false, api.execute("toggle", Map.of()).get("value"));
            assertEquals(true, api.execute("check", Map.of()).get("value"));
            assertEquals("#12345680", api.execute("color", Map.of("value", "#12345680")).get("value"));
            assertEquals("State runnable", api.execute("combo", Map.of("value", "State runnable")).get("value"));
            assertSame(Thread.State.RUNNABLE, combo.getValue());
            assertEquals(3, actions[0]);
            assertError(400, () -> api.execute("toggle", Map.of("value", "true")));
            assertError(400, () -> api.execute("combo", Map.of("value", "RUNNABLE")));
            assertError(400, () -> api.execute("color", Map.of("value", "red")));
            assertError(404, () -> api.execute("missing", Map.of()));
            button.setDisable(true);
            assertError(409, () -> api.execute("button", Map.of()));
            assertEquals(3, actions[0]);
            new ObjectMapper().writeValueAsString(api.describe());
        });
    }

    @Test
    void skinnedDropdownInvokesItsHandlerExactlyOncePerCommand() throws Exception {
        onFx(() -> {
            ComboBox<String> combo = new ComboBox<>();
            combo.getItems().setAll("First", "Second");
            combo.setValue("First");
            combo.setSkin(new javafx.scene.control.skin.ComboBoxListViewSkin<>(combo));
            int[] actions = {0};
            combo.setOnAction(event -> actions[0]++);
            DmControlApi api = new DmControlApi(new ControlVisibility());
            api.add("combo", combo);
            api.execute("combo", Map.of("value", "Second"));
            assertEquals(1, actions[0]);
            api.execute("combo", Map.of("value", "Second"));
            assertEquals(2, actions[0]);
        });
    }

    @Test
    void clampsNumericControlsValidatesStrictlyAndCommitsSliderGesture() throws Exception {
        onFx(() -> {
            Slider slider = new Slider(0, 10, 4);
            List<String> events = new ArrayList<>();
            slider.setOnMousePressed(event -> events.add("press:" + slider.getValue()));
            slider.valueProperty().addListener((obs, before, after) -> {
                assertTrue(slider.isValueChanging());
                events.add("value:" + after);
            });
            slider.valueChangingProperty().addListener((obs, before, after) -> {
                if (!after) events.add("commit:" + slider.getValue());
            });
            slider.setOnMouseReleased(event -> events.add("release:" + slider.getValue()));
            HBox row = new HBox(slider);
            row.setOnMousePressed(event -> fail("Synthetic event must not bubble"));
            Spinner<Integer> integer = new Spinner<>(1, 8, 4);
            Spinner<Double> decimal = new Spinner<>(0.1, 5.0, 1.0, 0.1);
            decimal.setEditable(true);
            DmControlApi api = new DmControlApi(new ControlVisibility());
            api.add("slider", row);
            api.add("integer", integer);
            api.add("decimal", decimal);
            assertEquals(10.0, api.execute("slider", Map.of("increment", "100")).get("value"));
            assertEquals(List.of("press:4.0", "value:10.0", "commit:10.0", "release:10.0"), events);
            assertEquals(0.0, api.execute("slider", Map.of("decrement", "100")).get("value"));
            assertEquals(8, api.execute("integer", Map.of("value", "100")).get("value"));
            assertEquals(6, api.execute("integer", Map.of("decrement", "2")).get("value"));
            assertEquals(1.7, api.execute("decimal", Map.of("value", "1.7")).get("value"));
            assertEquals("1.7", decimal.getEditor().getText());
            decimal.getEditor().setText("invalid draft");
            api.execute("decimal", Map.of("value", "1.7"));
            assertEquals("1.7", decimal.getEditor().getText());
            for (String invalid : List.of("NaN", "Infinity", "1e400", "0x1p2", " 2", "bad")) {
                assertError(400, () -> api.execute("slider", Map.of("value", invalid)));
            }
            assertError(400, () -> api.execute("integer", Map.of("value", "1.2")));
            assertError(400, () -> api.execute("slider", Map.of("increment", "-1")));
            assertError(400, () -> api.execute("slider", Map.of("decrement", "-1")));
            assertError(400, () -> api.execute("slider", Map.of()));
            assertError(400, () -> api.execute("slider", Map.of("value", "2", "increment", "1")));
            assertError(400, () -> api.execute("slider", Map.of("other", "2")));
            slider.setValueChanging(true);
            assertError(409, () -> api.execute("slider", Map.of("value", "2")));
            slider.setValueChanging(false);
            row.setDisable(true);
            assertError(409, () -> api.execute("slider", Map.of("value", "2")));
        });
    }

    @Test
    void registrySnapshotAndWrappedMenusPreserveExistingItemsAndCopyLiveValues() throws Exception {
        onFx(() -> {
            ControlVisibility registry = new ControlVisibility();
            Slider slider = new Slider(0, 1, .25);
            Label label = new Label("opacity");
            HBox row = new HBox(label, slider);
            registry.register("effects.opacity", row);
            Map<String, List<javafx.scene.Node>> snapshot = registry.registeredNodes();
            assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
            assertThrows(UnsupportedOperationException.class, () -> snapshot.get("effects.opacity").clear());
            DmControlApi api = new DmControlApi(registry);
            MenuItem old = new MenuItem("Existing");
            slider.setContextMenu(new ContextMenu(old));
            List<String> status = new ArrayList<>();
            api.attachUrlMenus(() -> "http://127.0.0.1:8080", status::add);
            assertSame(old, slider.getContextMenu().getItems().getFirst());
            assertNull(row.getOnContextMenuRequested());
            assertEquals(3, label.getContextMenu().getItems().size());
            slider.setValue(.75);
            slider.getContextMenu().getItems().get(2).fire();
            assertEquals("http://127.0.0.1:8080/api/controls/effects/opacity?value=0.75",
                    Clipboard.getSystemClipboard().getString());
            label.getContextMenu().getItems().get(1).fire();
            assertEquals("http://127.0.0.1:8080/api/controls/effects/opacity?increment=0.1",
                    Clipboard.getSystemClipboard().getString());
            api.attachUrlMenus(() -> "http://127.0.0.1:8080", status::add);
            assertEquals(5, slider.getContextMenu().getItems().size());
            ColorPicker color = new ColorPicker(Color.web("#01020380"));
            api.add("color", color);
            api.attachUrlMenus(() -> "http://127.0.0.1:8080", status::add);
            color.getContextMenu().getItems().getFirst().fire();
            assertTrue(Clipboard.getSystemClipboard().getString().endsWith("?value=%2301020380"));
        });
    }

    @Test
    void actualSidebarCoversEveryActionableSettingIncludingWrappedRows() throws Exception {
        onFx(() -> {
            String previous = System.getProperty(AppSettings.SYSTEM_PROPERTY);
            System.setProperty(AppSettings.SYSTEM_PROPERTY, dir.resolve("api-panel.ini").toString());
            Stage owner = new Stage();
            try {
                DungeonMasterMapToolApplication app = new DungeonMasterMapToolApplication();
                Field project = app.getClass().getDeclaredField("project");
                project.setAccessible(true);
                project.set(app, DmProject.builder().build());
                Method create = app.getClass().getDeclaredMethod("createControlsPanel", Stage.class);
                create.setAccessible(true);
                create.invoke(app, owner);
                Field registry = app.getClass().getDeclaredField("dmControlVisibility");
                registry.setAccessible(true);
                DmControlApi api = new DmControlApi((ControlVisibility) registry.get(app));
                Set<String> expected = AppSettings.SIDEBAR_CONTROLS.stream().map(AppSettings.SidebarControl::id)
                        .filter(id -> !id.equals("lighting.hint")).collect(Collectors.toSet());
                assertEquals(expected, api.describe().stream().map(d -> (String) d.get("id")).collect(Collectors.toSet()));
                assertError(404, () -> api.execute("lighting.hint", Map.of()));
                api.attachUrlMenus(() -> "http://127.0.0.1:8080", ignored -> {});
                new ObjectMapper().writeValueAsString(api.describe());
            } finally {
                owner.close();
                if (previous == null) System.clearProperty(AppSettings.SYSTEM_PROPERTY);
                else System.setProperty(AppSettings.SYSTEM_PROPERTY, previous);
            }
        });
    }

    @Test
    void dropdownIndexUsesUiOrderValidatesBeforeChangingAndCopiesCurrentIndex() throws Exception {
        onFx(() -> {
            DmControlApi api = new DmControlApi(new ControlVisibility());
            ComboBox<String> combo = new ComboBox<>();
            combo.getItems().setAll("First long name", "Second long name", "Third long name");
            int[] actions = {0};
            combo.setOnAction(event -> actions[0]++);
            api.add("dropdown", combo);
            assertEquals(0, api.execute("dropdown", Map.of("index", "0")).get("index"));
            assertEquals("First long name", combo.getValue());
            assertEquals(2, api.execute("dropdown", Map.of("index", "2")).get("index"));
            assertEquals("Third long name", combo.getValue());
            assertEquals(2, actions[0]);
            for (String invalid : List.of("-1", "3", "1.5", "NaN", "", " 1", "2147483648")) {
                assertError(400, () -> api.execute("dropdown", Map.of("index", invalid)));
            }
            assertError(400, () -> api.execute("dropdown", Map.of("index", "1", "value", "Second long name")));
            assertEquals("Third long name", combo.getValue());
            assertEquals(2, actions[0]);
            api.attachUrlMenus(() -> "http://127.0.0.1:7071", ignored -> {});
            combo.getContextMenu().getItems().stream().filter(item -> item.getText().equals("Copy API index URL"))
                    .findFirst().orElseThrow().fire();
            assertEquals("http://127.0.0.1:7071/api/controls/dropdown?index=2",
                    Clipboard.getSystemClipboard().getString());
            assertEquals(1, api.execute("dropdown", Map.of("value", "Second long name")).get("index"));
        });
    }

    @Test
    void keyImagesOpenLocalBrowserPagesWithoutApiAndNumericVariantsDiffer() throws Exception {
        onFx(() -> {
            DmControlApi api = new DmControlApi(new ControlVisibility());
            Button button = new Button();
            var original = new org.kordamp.ikonli.javafx.FontIcon(
                    org.kordamp.ikonli.materialdesign2.MaterialDesignS.SNOWFLAKE);
            button.setGraphic(original);
            javafx.scene.Parent originalParent = original.getParent();
            Slider slider = new Slider(0, 1, .5);
            api.add("player.freeze", button);
            api.add("effects.opacity", slider);
            List<String> opened = new ArrayList<>();
            List<String> statuses = new ArrayList<>();
            api.attachUrlMenus(() -> null, statuses::add);
            api.attachKeyImageMenus(new dmmt.ui.ControlKeyImages(dir), opened::add, statuses::add);
            for (Control control : List.of(button, slider)) {
                for (MenuItem item : control.getContextMenu().getItems()) {
                    if (item.getText() != null && item.getText().startsWith("Open ")) {
                        item.fire();
                    }
                }
            }
            assertEquals(4, opened.size());
            assertTrue(statuses.isEmpty(), statuses.toString());
            for (String url : opened) {
                Path page = Path.of(java.net.URI.create(url));
                assertTrue(java.nio.file.Files.readString(page).contains("<img width=\"144\" height=\"144\""));
                Path png = page.resolveSibling(page.getFileName().toString().replace(".html", ".png"));
                var image = javax.imageio.ImageIO.read(png.toFile());
                assertEquals(144, image.getWidth());
                assertEquals(144, image.getHeight());
            }
            var increase = javax.imageio.ImageIO.read(dir.resolve("effects.opacity-increment.png").toFile());
            var decrease = javax.imageio.ImageIO.read(dir.resolve("effects.opacity-decrement.png").toFile());
            assertNotEquals(increase.getRGB(115, 100), decrease.getRGB(115, 100));
            assertSame(original, button.getGraphic());
            assertSame(originalParent, original.getParent());
            assertEquals(.5, slider.getValue());
        });
    }

    private static void assertError(int status, Runnable action) {
        assertEquals(status, assertThrows(LocalApiServer.ApiException.class, action::run).status());
    }

    private static void onFx(CheckedAction action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> { action.run(); return null; });
        Platform.runLater(task);
        task.get(30, TimeUnit.SECONDS);
    }

    private interface CheckedAction { void run() throws Exception; }
}
