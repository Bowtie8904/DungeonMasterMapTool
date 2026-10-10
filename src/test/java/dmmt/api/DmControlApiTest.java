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
    void apiNamesAreIndependentOfTooltipsAndDynamicNamesStayLive() throws Exception {
        onFx(() -> {
            DmControlApi api = new DmControlApi(new ControlVisibility());
            Button torch = new Button("A long UI label");
            torch.setAccessibleText("A long tooltip describing the default torch");
            Button play = new Button();
            play.setAccessibleText("Pause or resume all music and sound effects");
            Button category = new Button("Combat");
            api.add("lighting.torch", torch);
            api.add("audio.play", play);
            api.add("audio.category.test", category);
            assertEquals(List.of("Torch", "Play / pause", "Combat"),
                    api.describe().stream().map(control -> control.get("label")).toList());
            assertEquals("Torch", api.execute("lighting.torch", Map.of()).get("label"));
            assertEquals("A long UI label", torch.getText());
            assertEquals("Place a torch on the map", torch.getAccessibleText());
            play.setAccessibleText("A different tooltip");
            assertEquals("Play / pause", api.describe(List.of("audio.play")).getFirst().get("label"));
            category.setText("Exploration");
            assertEquals("Exploration", api.describe(List.of("audio.category.test")).getFirst().get("label"));
        });
    }

    @Test
    void tooltipMappingIsSharedByUiDiscoveryCommandsAndLaterRefreshes() throws Exception {
        onFx(() -> {
            ControlVisibility registry = new ControlVisibility();
            Slider slider = new Slider(0, 1, .5);
            Label label = new Label("Opacity");
            HBox row = new HBox(label, slider);
            dmmt.ui.Icons.tooltip(slider, "Original help");
            registry.register("effects.opacity", row);
            assertEquals("Opacity of effects on the map", slider.getAccessibleText());
            assertEquals(slider.getAccessibleText(), label.getAccessibleText());
            DmControlApi api = new DmControlApi(registry);
            Button all = new Button();
            Button music = new Button();
            Button effects = new Button();
            api.add("audio.play", all);
            api.add("audio.musicPlay", music);
            api.add("audio.effectsPause", effects);
            dmmt.ui.Icons.tooltip(all, "Later refresh");
            assertEquals("Pause or resume music and sound effects", all.getAccessibleText());
            var hoverTooltips = all.getProperties().values().stream()
                    .filter(Tooltip.class::isInstance).map(Tooltip.class::cast).toList();
            assertEquals(1, hoverTooltips.size(), "Refreshing help must reuse the hover tooltip");
            assertEquals(all.getAccessibleText(), hoverTooltips.getFirst().getText());
            var audio = api.describe(List.of("audio.play", "audio.musicPlay", "audio.effectsPause"));
            assertEquals(3, audio.stream().map(control -> control.get("tooltip")).distinct().count());
            assertEquals(all.getAccessibleText(), audio.getFirst().get("tooltip"));
            assertEquals(all.getAccessibleText(), api.execute("audio.play", Map.of()).get("tooltip"));
            assertEquals(slider.getAccessibleText(), api.describe(List.of("effects.opacity")).getFirst().get("tooltip"));
            Button category = new Button("Combat");
            dmmt.ui.Icons.tooltip(category, "Combat: playing");
            api.replaceGroup("audio.category", Map.of("audio.category.test", category));
            assertEquals("Combat: playing", api.describe(List.of("audio.category.test")).getFirst().get("tooltip"));
            dmmt.ui.Icons.tooltip(category, "Exploration: paused");
            category.setText("Exploration");
            assertEquals("Exploration: paused", api.describe(List.of("audio.category.test")).getFirst().get("tooltip"));
            Button torch = new Button();
            Tooltip originalTooltip = new Tooltip("Original help");
            torch.setTooltip(originalTooltip);
            api.add("lighting.torch", torch);
            assertSame(originalTooltip, torch.getTooltip());
            assertEquals("Place a torch on the map", originalTooltip.getText());
            Button plain = new Button("Custom name");
            plain.setTooltip(new Tooltip("Custom help"));
            api.add("custom.control", plain);
            assertEquals("Custom help", api.describe(List.of("custom.control")).getFirst().get("tooltip"));
            plain.setTooltip(null);
            assertEquals("Custom name", api.describe(List.of("custom.control")).getFirst().get("tooltip"));
        });
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
            String expectedEditorText = decimal.getValueFactory().getConverter().toString(1.7);
            assertEquals(expectedEditorText, decimal.getEditor().getText());
            decimal.getEditor().setText("invalid draft");
            api.execute("decimal", Map.of("value", "1.7"));
            assertEquals(expectedEditorText, decimal.getEditor().getText());
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
            api.setUrlOptionsVisible(true);
            api.attachUrlMenus(() -> "http://127.0.0.1:8080", status::add);
            assertSame(old, slider.getContextMenu().getItems().getFirst());
            assertNull(row.getOnContextMenuRequested());
            // Three API URL items, a separator, then three key image URL items.
            assertEquals(7, label.getContextMenu().getItems().size());
            slider.setValue(.75);
            slider.getContextMenu().getItems().get(2).fire();
            assertEquals("http://127.0.0.1:8080/api/controls/effects/opacity?value=0.75",
                    Clipboard.getSystemClipboard().getString());
            label.getContextMenu().getItems().get(1).fire();
            assertEquals("http://127.0.0.1:8080/api/controls/effects/opacity?increment=0.1",
                    Clipboard.getSystemClipboard().getString());
            api.attachUrlMenus(() -> "http://127.0.0.1:8080", status::add);
            assertEquals(9, slider.getContextMenu().getItems().size(),
                    "re-attaching must not add a second set of items to a control that already has them");
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
                api.setUrlOptionsVisible(true);
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
            api.setUrlOptionsVisible(true);
            api.attachUrlMenus(() -> "http://127.0.0.1:7071", ignored -> {});
            combo.getContextMenu().getItems().stream().filter(item -> item.getText().equals("Copy API index URL"))
                    .findFirst().orElseThrow().fire();
            assertEquals("http://127.0.0.1:7071/api/controls/dropdown?index=2",
                    Clipboard.getSystemClipboard().getString());
            assertEquals(1, api.execute("dropdown", Map.of("value", "Second long name")).get("index"));
        });
    }

    @Test
    void keyImagesRenderWithoutTheApiAndWithoutStealingTheLiveGraphic() throws Exception {
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

            for (String id : List.of("player.freeze", "effects.opacity")) {
                var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(api.keyImage(id, null)));
                assertEquals(144, image.getWidth());
                assertEquals(144, image.getHeight());
            }
            var increase = javax.imageio.ImageIO
                    .read(new java.io.ByteArrayInputStream(api.keyImage("effects.opacity", "increment")));
            var decrease = javax.imageio.ImageIO
                    .read(new java.io.ByteArrayInputStream(api.keyImage("effects.opacity", "decrement")));
            assertNotEquals(increase.getRGB(115, 100), decrease.getRGB(115, 100),
                    "the increment and decrement badges must be distinguishable");

            // Rendering must snapshot a copy; the live control keeps its own graphic and value.
            assertSame(original, button.getGraphic());
            assertSame(originalParent, original.getParent());
            assertEquals(.5, slider.getValue());
        });
    }

    @Test
    void replacingAGroupKeepsOneMenuPerReusedControlAndGivesNewOnesKeyImages() throws Exception {
        onFx(() -> {
            DmControlApi api = new DmControlApi(new ControlVisibility());
            Slider volume = new Slider(0, 1, .5);
            Button first = new Button();
            api.replaceGroup("audio", Map.of("audio.musicVolume", volume, "audio.category.combat", first));
            api.setUrlOptionsVisible(true);
            api.attachUrlMenus(() -> "http://127.0.0.1:8080", ignored -> {});
            int volumeItems = volume.getContextMenu().getItems().size();
            assertTrue(volumeItems > 0);

            Button second = new Button();
            // The slider stays registered, only the category toggle is swapped out.
            api.replaceGroup("audio", Map.of("audio.musicVolume", volume, "audio.category.combat", second));

            assertEquals(volumeItems, volume.getContextMenu().getItems().size(),
                    "re-registering must not duplicate the menu items of a reused control");
            assertTrue(second.getContextMenu().getItems().stream()
                            .anyMatch(item -> "Copy API URL".equals(item.getText())),
                    "a new control gets the API URL menu");
            assertTrue(second.getContextMenu().getItems().stream()
                            .anyMatch(item -> "Copy key image URL".equals(item.getText())),
                    "a new control gets the key image URL item like every other control");
        });
    }

    @Test
    void keyImageUrlsCanBeCopiedForEveryVariantOfAControl() throws Exception {
        onFx(() -> {
            DmControlApi api = new DmControlApi(new ControlVisibility());
            Button button = new Button();
            Slider slider = new Slider(0, 1, .5);
            api.add("player.freeze", button);
            api.add("effects.opacity", slider);
            List<String> statuses = new ArrayList<>();
            api.setUrlOptionsVisible(true);
            api.attachUrlMenus(() -> "http://127.0.0.1:8080", statuses::add);

            fire(button, "Copy key image URL");
            assertEquals("http://127.0.0.1:8080/api/controls/player/freeze/image",
                    Clipboard.getSystemClipboard().getString());
            fire(slider, "Copy increment key image URL");
            assertEquals("http://127.0.0.1:8080/api/controls/effects/opacity/image?operation=increment",
                    Clipboard.getSystemClipboard().getString());
            fire(slider, "Copy decrement key image URL");
            assertEquals("http://127.0.0.1:8080/api/controls/effects/opacity/image?operation=decrement",
                    Clipboard.getSystemClipboard().getString());
            assertTrue(statuses.stream().allMatch(status -> status.startsWith("Copied API URL: ")), statuses.toString());
            assertTrue(button.getContextMenu().getItems().stream()
                    .noneMatch(item -> "Copy increment key image URL".equals(item.getText())));
            assertThrows(IllegalArgumentException.class, () -> api.add("player.freeze.image", new Button()));
        });
    }

    @Test
    void urlMenuOptionsAreHiddenByDefaultAndCanBeToggledWithoutRemovingOtherItems() throws Exception {
        onFx(() -> {
            DmControlApi api = new DmControlApi(new ControlVisibility());
            Button button = new Button();
            javafx.scene.control.ContextMenu existing = new javafx.scene.control.ContextMenu(
                    new javafx.scene.control.MenuItem("Existing action"));
            button.setContextMenu(existing);
            api.add("player.freeze", button);
            api.attachUrlMenus(() -> "http://127.0.0.1:8080", ignored -> {});

            assertEquals(List.of("Existing action"), existing.getItems().stream()
                    .map(javafx.scene.control.MenuItem::getText).toList());
            api.setUrlOptionsVisible(true);
            assertTrue(existing.getItems().stream().anyMatch(item -> "Copy API URL".equals(item.getText())));
            api.setUrlOptionsVisible(false);
            assertEquals(List.of("Existing action"), existing.getItems().stream()
                    .map(javafx.scene.control.MenuItem::getText).toList());
        });
    }

    private static void fire(Control control, String label) {
        control.getContextMenu().getItems().stream().filter(item -> label.equals(item.getText())).findFirst()
                .orElseThrow(() -> new AssertionError(label + " is missing")).fire();
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
