package dmmt.ui;

import dmmt.DungeonMasterMapToolApplication;
import dmmt.FxTestSupport;
import dmmt.model.DmProject;
import dmmt.service.AppSettings;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ControlVisibilityTest {
    @TempDir
    Path dir;

    @BeforeAll
    static void startJavaFx() throws Exception {
        FxTestSupport.startJavaFx();
    }

    @Test
    void hidesReadoutsEmptyRowsAndRedundantSeparatorsAndRestoresThem() throws Exception {
        onFx(() -> {
            ControlVisibility visibility = new ControlVisibility();
            Button torch = new Button();
            Button remove = new Button();
            Button flicker = new Button();
            Region firstSeparator = Icons.separator();
            Region secondSeparator = Icons.separator();
            HBox row = new HBox(torch, firstSeparator, remove, secondSeparator, flicker);
            visibility.registerRow("lighting", row, "torch", null, "remove", null, "flicker");
            Slider slider = new Slider(0, 1, 0.4);
            Label readout = new Label("40%");
            HBox opacity = new HBox(slider, readout);
            visibility.registerRow("effects", opacity, "opacity", "opacity");
            VBox nested = new VBox(row, opacity);
            visibility.registerContainer(nested);

            visibility.apply(Set.of("lighting.remove", "effects.opacity"));
            assertFalse(remove.isManaged());
            assertTrue(firstSeparator.isManaged());
            assertFalse(secondSeparator.isManaged());
            assertFalse(slider.isVisible());
            assertFalse(readout.isManaged());
            assertFalse(opacity.isManaged());
            assertEquals(0.4, slider.getValue());
            visibility.apply(Set.of("lighting.torch", "lighting.remove", "lighting.flicker", "effects.opacity"));
            assertFalse(row.isManaged());
            assertFalse(nested.isManaged());
            visibility.apply(Set.of());
            assertTrue(row.isManaged());
            assertTrue(nested.isManaged());
            assertTrue(firstSeparator.isManaged());
            assertTrue(secondSeparator.isManaged());
            assertTrue(readout.isManaged());
        });
    }

    @Test
    void actualPanelRegistersEveryControlAndAppliesChoicesWithoutChangingValues() throws Exception {
        onFx(() -> {
            String oldProperty = System.getProperty(AppSettings.SYSTEM_PROPERTY);
            System.setProperty(AppSettings.SYSTEM_PROPERTY, dir.resolve("panel.ini").toString());
            try {
                new AppSettings(dir.resolve("panel.ini")).setHiddenControls(Set.of("lighting.candle", "player.diagonal"));
                DungeonMasterMapToolApplication app = new DungeonMasterMapToolApplication();
                Field projectField = app.getClass().getDeclaredField("project");
                projectField.setAccessible(true);
                DmProject project = DmProject.builder().build();
                projectField.set(app, project);
                Method create = app.getClass().getDeclaredMethod("createControlsPanel", Stage.class);
                create.setAccessible(true);
                create.invoke(app, new Stage());
                ControlVisibility visibility = (ControlVisibility) field(app, "dmControlVisibility");
                Set<String> expected = AppSettings.SIDEBAR_CONTROLS.stream()
                        .map(AppSettings.SidebarControl::id).collect(Collectors.toSet());
                assertEquals(expected, visibility.registeredIds());
                AppSettings settings = (AppSettings) field(app, "preferences");
                Node diagonal = (Node) field(app, "screenInchesSpinner");
                Node tile = (Node) field(app, "tileInchesSpinner");
                Slider zoom = (Slider) field(app, "playerZoomSlider");
                assertFalse(diagonal.isManaged());
                assertTrue(tile.isManaged());
                double before = zoom.getValue();
                Object activeTool = field(app, "activeTool");
                String projectBefore = project.toString();
                settings.setHiddenControls(Set.of("player.diagonal", "player.zoom", "lighting.candle",
                        "fog.brushSize", "effects.brushSize"));
                Method apply = app.getClass().getDeclaredMethod("applySectionVisibility");
                apply.setAccessible(true);
                apply.invoke(app);
                assertFalse(diagonal.isManaged());
                assertFalse(diagonal.getParent().isManaged());
                assertTrue(tile.isManaged());
                assertTrue(tile.getParent().isManaged());
                assertFalse(zoom.getParent().isManaged());
                assertEquals(before, zoom.getValue());
                assertSame(activeTool, field(app, "activeTool"));
                assertEquals(projectBefore, project.toString());
                for (String id : expected) {
                    settings.setHiddenControls(Set.of(id));
                    apply.invoke(app);
                    for (var entry : ((java.util.Map<?, ?>) field(visibility, "controls")).entrySet()) {
                        Node node = (Node) entry.getKey();
                        assertEquals(!id.equals(entry.getValue()), node.isManaged(), entry.getValue().toString());
                        assertEquals(node.isManaged(), node.isVisible());
                    }
                }
                settings.setHiddenControls(expected);
                apply.invoke(app);
                for (Object section : ((java.util.Map<?, ?>) field(app, "dmSections")).values()) {
                    VBox body = (VBox) ((VBox) section).getChildren().get(1);
                    assertTrue(body.getChildren().stream().noneMatch(Node::isManaged));
                }
                settings.setHiddenControls(Set.of());
                apply.invoke(app);
                assertTrue(diagonal.isManaged());
                assertTrue(zoom.getParent().isManaged());
                var sections = (java.util.Map<?, ?>) field(app, "dmSections");
                CollapsibleSection lighting = (CollapsibleSection) sections.get("lighting");
                lighting.setExpanded(false);
                settings.setHiddenControls(Set.of("lighting.candle"));
                settings.setHiddenSections(Set.of("lighting"));
                apply.invoke(app);
                assertFalse(lighting.isManaged());
                settings.setHiddenSections(Set.of());
                apply.invoke(app);
                assertTrue(lighting.isManaged());
                assertFalse(lighting.isExpanded());
                assertFalse(lighting.getChildren().get(1).isManaged());
                assertEquals(Set.of("lighting.candle"), settings.hiddenControls());
            } finally {
                if (oldProperty == null) {
                    System.clearProperty(AppSettings.SYSTEM_PROPERTY);
                } else {
                    System.setProperty(AppSettings.SYSTEM_PROPERTY, oldProperty);
                }
            }
        });
    }

    @Test
    void settingsExposeEveryControlAndCheckboxChangesApplyImmediately() throws Exception {
        onFx(() -> {
            AppSettings settings = new AppSettings(dir.resolve("window.ini"));
            int[] calls = {0};
            SettingsWindow.show(null, settings, () -> calls[0]++);
            try {
                Field open = SettingsWindow.class.getDeclaredField("open");
                open.setAccessible(true);
                Stage stage = (Stage) open.get(null);
                List<CheckBox> boxes = descendants(stage.getScene().getRoot()).stream()
                        .filter(CheckBox.class::isInstance).map(CheckBox.class::cast).toList();
                // Expand the Lighting controls group to access its checkboxes.
                Label lighting = descendants(stage.getScene().getRoot()).stream()
                        .filter(Label.class::isInstance).map(Label.class::cast)
                        .filter(l -> l.getText().startsWith("Lighting controls")).findFirst().orElseThrow();
                lighting.getParent().getOnMouseClicked().handle(null);
                CheckBox candle = descendants(stage.getScene().getRoot()).stream()
                        .filter(CheckBox.class::isInstance).map(CheckBox.class::cast)
                        .filter(b -> b.getText().equals("Show Default candle light")).findFirst().orElseThrow();
                assertTrue(candle.isSelected());
                candle.fire();
                assertEquals(Set.of("lighting.candle"), settings.hiddenControls());
                assertEquals(1, calls[0]);
                candle.fire();
                assertTrue(settings.hiddenControls().isEmpty());
                assertEquals(AppSettings.SIDEBAR_SECTIONS.size() + AppSettings.SIDEBAR_CONTROLS.size(), boxes.size());
                stage.close();
            } finally {
                Field open = SettingsWindow.class.getDeclaredField("open");
                open.setAccessible(true);
                Stage stage = (Stage) open.get(null);
                if (stage != null) {
                    stage.close();
                }
            }
        });
    }

    private static List<Node> descendants(Node node) {
        java.util.ArrayList<Node> result = new java.util.ArrayList<>();
        result.add(node);
        if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> result.addAll(descendants(child)));
        }
        return result;
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void onFx(ThrowingRunnable action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            action.run();
            return null;
        });
        Platform.runLater(task);
        task.get(20, TimeUnit.SECONDS);
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
