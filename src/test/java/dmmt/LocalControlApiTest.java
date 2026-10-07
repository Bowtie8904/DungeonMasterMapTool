package dmmt;

import dmmt.api.DmControlApi;
import dmmt.model.DmProject;
import dmmt.render.WeatherType;
import dmmt.service.AppSettings;
import dmmt.service.FogService;
import dmmt.ui.ControlVisibility;
import javafx.application.Platform;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Slider;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LocalControlApiTest {
    @TempDir Path temp;

    @BeforeAll
    static void startJavaFx() throws Exception {
        FxTestSupport.startJavaFx();
    }

    @Test
    void colorCommandChangesOnlySelectedEffectAndUsesExistingUndoRedo() throws Exception {
        onFx(() -> {
            Fixture fixture = fixture();
            fixture.api().execute("effects.color", Map.of("value", "#123456FF"));
            assertEquals("#123456", fixture.selected().getColor());
            assertEquals("#ABCDEF", fixture.other().getColor());
            assertEquals(1, fixture.undoSize());
            fixture.api().execute("tools.undo", Map.of());
            assertEquals("#445566", fixture.selected().getColor());
            assertEquals("#ABCDEF", fixture.other().getColor());
            fixture.api().execute("tools.redo", Map.of());
            assertEquals("#123456", fixture.selected().getColor());
            assertEquals("selected", get(fixture.app(), "selectedOverlayId"));
        });
    }

    @Test
    void opacityCommandsCommitMouseReleaseUndoOnceAndNeverTriggerPointerActions() throws Exception {
        onFx(() -> {
            Fixture fixture = fixture();
            Slider slider = (Slider) get(fixture.app(), "overlayAlphaSlider");
            slider.getParent().setOnMousePressed(event -> fail("API gesture must not bubble to pointer actions"));
            fixture.api().execute("effects.opacity", Map.of("value", "0.8"));
            assertEquals(.8, fixture.selected().getAlpha(), 1e-9);
            assertEquals(.3, fixture.other().getAlpha(), 1e-9);
            assertFalse(slider.isValueChanging());
            assertNull(get(fixture.app(), "overlayStyleBefore"));
            assertEquals(1, fixture.undoSize());
            fixture.api().execute("tools.undo", Map.of());
            assertEquals(.4, fixture.selected().getAlpha(), 1e-9);
            fixture.api().execute("tools.redo", Map.of());
            assertEquals(.8, fixture.selected().getAlpha(), 1e-9);
            fixture.api().execute("effects.opacity", Map.of("decrement", "0.2"));
            assertEquals(.6, fixture.selected().getAlpha(), 1e-9);
            assertEquals(2, fixture.undoSize());
            fixture.api().execute("tools.undo", Map.of());
            assertEquals(.8, fixture.selected().getAlpha(), 1e-9);
            assertEquals(2, fixture.project().getOverlays().size());
        });
    }

    @Test
    void lightTintValueChangingCommitIsUndoableAndWrappedSharpnessPersists() throws Exception {
        onFx(() -> {
            Fixture fixture = fixture();
            double tintBefore = fixture.project().getLighting().getLightTint();
            fixture.api().execute("lighting.tint", Map.of("value", "0.17"));
            assertEquals(.17, fixture.project().getLighting().getLightTint(), 1e-9);
            assertEquals(1, fixture.undoSize());
            fixture.api().execute("tools.undo", Map.of());
            assertEquals(tintBefore, fixture.project().getLighting().getLightTint(), 1e-9);
            fixture.api().execute("tools.redo", Map.of());
            assertEquals(.17, fixture.project().getLighting().getLightTint(), 1e-9);

            int sharpnessBefore = FogService.getCellsPerGrid();
            Map<String, Object> sharpness = fixture.api().describe().stream()
                    .filter(state -> state.get("id").equals("fog.sharpness")).findFirst().orElseThrow();
            int minimum = ((Number) sharpness.get("min")).intValue();
            int maximum = ((Number) sharpness.get("max")).intValue();
            int target = sharpnessBefore == maximum ? minimum : maximum;
            try {
                fixture.api().execute("fog.sharpness", Map.of("value", Integer.toString(target)));
                assertEquals(target, FogService.getCellsPerGrid());
                AppSettings preferences = (AppSettings) get(fixture.app(), "preferences");
                assertEquals(target, preferences.getInt("fog.cellsPerGrid", -1));
            } finally {
                FogService.setCellsPerGrid(sharpnessBefore);
            }
        });
    }

    @Test
    void weatherEnumChoicesUseDisplayStringsAndTextureUsesConverter() throws Exception {
        onFx(() -> {
            Fixture fixture = fixture();
            assertEquals(WeatherType.RAIN.toString(), fixture.api().execute("weather.type",
                    Map.of("value", WeatherType.RAIN.toString())).get("value"));
            ComboBox<?> weather = (ComboBox<?>) get(fixture.app(), "weatherBox");
            assertSame(WeatherType.RAIN, weather.getValue());
            int clearIndex = weather.getItems().indexOf(WeatherType.NONE);
            fixture.api().execute("weather.type", Map.of("index", Integer.toString(clearIndex)));
            assertEquals("none", fixture.project().getWeather().getType());
            Map<String, Object> texture = fixture.api().describe().stream()
                    .filter(state -> state.get("id").equals("effects.texture")).findFirst().orElseThrow();
            String choice = ((java.util.List<?>) texture.get("choices")).stream()
                    .map(Object::toString).filter(value -> !value.equals(texture.get("value")))
                    .findFirst().orElseThrow();
            assertEquals(choice, fixture.api().execute("effects.texture", Map.of("value", choice)).get("value"));
        });
    }

    private Fixture fixture() throws Exception {
        String previous = System.getProperty(AppSettings.SYSTEM_PROPERTY);
        System.setProperty(AppSettings.SYSTEM_PROPERTY, temp.resolve("controls.ini").toString());
        Stage owner = new Stage();
        try {
            DungeonMasterMapToolApplication app = new DungeonMasterMapToolApplication();
            DmProject project = DmProject.builder().build();
            set(app, "project", project);
            Method create = app.getClass().getDeclaredMethod("createControlsPanel", Stage.class);
            create.setAccessible(true);
            create.invoke(app, owner);
            DmControlApi api = new DmControlApi((ControlVisibility) get(app, "dmControlVisibility"));
            set(app, "controlApi", api);
            project.getOverlays().add(DmProject.OverlayShape.builder()
                    .id("selected").color("#445566").alpha(.4).build());
            project.getOverlays().add(DmProject.OverlayShape.builder()
                    .id("other").color("#ABCDEF").alpha(.3).build());
            set(app, "selectedOverlayId", "selected");
            Method sync = app.getClass().getDeclaredMethod("syncOverlayControls", DmProject.OverlayShape.class);
            sync.setAccessible(true);
            sync.invoke(app, project.getOverlays().getFirst());
            return new Fixture(app, project, (DmControlApi) get(app, "controlApi"));
        } finally {
            owner.close();
            if (previous == null) System.clearProperty(AppSettings.SYSTEM_PROPERTY);
            else System.setProperty(AppSettings.SYSTEM_PROPERTY, previous);
        }
    }

    private record Fixture(DungeonMasterMapToolApplication app, DmProject project, DmControlApi api) {
        DmProject.OverlayShape selected() {
            return project.getOverlays().stream().filter(shape -> shape.getId().equals("selected"))
                    .findFirst().orElseThrow();
        }

        DmProject.OverlayShape other() {
            return project.getOverlays().stream().filter(shape -> shape.getId().equals("other"))
                    .findFirst().orElseThrow();
        }

        int undoSize() throws Exception {
            return ((Deque<?>) get(app, "undoStack")).size();
        }
    }

    private static Object get(Object instance, String name) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static void set(Object instance, String name, Object value) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }

    private static void onFx(CheckedAction action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> { action.run(); return null; });
        Platform.runLater(task);
        task.get(30, TimeUnit.SECONDS);
    }

    private interface CheckedAction { void run() throws Exception; }
}
