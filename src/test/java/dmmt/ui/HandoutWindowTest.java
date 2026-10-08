package dmmt.ui;

import dmmt.DungeonMasterMapToolApplication;
import dmmt.FxTestSupport;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class HandoutWindowTest {
    @BeforeAll
    static void startJavaFx() throws Exception {
        FxTestSupport.startJavaFx();
    }

    @Test
    void applicationReusesImagesAfterClosingAndReopening() throws Exception {
        onFx(() -> {
            DungeonMasterMapToolApplication app = new DungeonMasterMapToolApplication();
            Stage owner = new Stage();
            HandoutWindow window = null;
            try {
                invoke(app, "openHandoutWindow", new Class<?>[]{Stage.class}, owner);
                window = (HandoutWindow) get(app, "handoutWindow");
                Image first = new WritableImage(20, 30);
                Image second = new WritableImage(40, 20);
                addImages(window, first, second);
                window.close();

                assertFalse(window.isShowing());
                assertSame(window, get(app, "handoutWindow"));
                invoke(app, "openHandoutWindow", new Class<?>[]{Stage.class}, owner);
                assertSame(window, get(app, "handoutWindow"));
                assertTrue(window.isShowing());
                assertEquals(List.of(first, second), window.getImages());
                assertFalse(window.isShownToPlayers());
                assertFalse(((Button) get(window, "clearButton")).isDisabled());

                Image third = new WritableImage(10, 10);
                addImages(window, third);
                assertEquals(List.of(first, second, third), window.getImages());
            } finally {
                if (window != null) {
                    window.close();
                }
                owner.close();
            }
        });
    }

    @Test
    void closingStopsSelectedOnlyDisplayWithoutDiscardingImages() throws Exception {
        onFx(() -> {
            HandoutWindow window = new HandoutWindow(new Stage(), List.of(), () -> true, () -> {}, () -> {});
            try {
                Image first = new WritableImage(20, 30);
                Image second = new WritableImage(40, 20);
                addImages(window, first, second);
                window.show();
                invoke(window, "click", new Class<?>[]{int.class, boolean.class}, 1, false);
                ((ToggleButton) get(window, "showSelectedToggle")).fire();
                assertTrue(window.isShownToPlayers());
                assertEquals(List.of(second), window.getImages());

                window.close();
                assertFalse(window.isShownToPlayers());
                assertEquals(List.of(first, second), window.getImages());
                window.show();
                assertFalse(((ToggleButton) get(window, "showToggle")).isSelected());
                assertFalse(((ToggleButton) get(window, "showSelectedToggle")).isSelected());
                assertTrue(((Button) get(window, "deleteButton")).isDisabled());

                ((ToggleButton) get(window, "showToggle")).fire();
                assertTrue(window.isShownToPlayers());
                assertEquals(List.of(first, second), window.getImages());
                window.close();
                window.show();
                assertFalse(window.isShownToPlayers());
                assertEquals(List.of(first, second), window.getImages());
            } finally {
                window.close();
            }
        });
    }

    @Test
    void deletedAndClearedImagesDoNotReturnOnReopen() throws Exception {
        onFx(() -> {
            HandoutWindow window = new HandoutWindow(new Stage(), List.of(), () -> true, () -> {}, () -> {});
            try {
                Image first = new WritableImage(20, 30);
                Image second = new WritableImage(40, 20);
                addImages(window, first, second);
                window.show();
                invoke(window, "click", new Class<?>[]{int.class, boolean.class}, 0, false);
                ((Button) get(window, "deleteButton")).fire();
                window.close();
                window.show();
                assertEquals(List.of(second), window.getImages());

                ((Button) get(window, "clearButton")).fire();
                window.close();
                window.show();
                assertTrue(window.getImages().isEmpty());
                assertTrue(((ToggleButton) get(window, "showToggle")).isDisabled());
                assertTrue(((Button) get(window, "clearButton")).isDisabled());
            } finally {
                window.close();
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static void addImages(HandoutWindow window, Image... images) throws Exception {
        ((List<Image>) get(window, "images")).addAll(List.of(images));
        invoke(window, "afterImagesChanged", new Class<?>[0]);
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        method.invoke(target, args);
    }

    private static void onFx(ThrowingRunnable action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            action.run();
            return null;
        });
        Platform.runLater(task);
        task.get(10, TimeUnit.SECONDS);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
