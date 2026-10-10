package dmmt.ui;

import dmmt.FxTestSupport;
import dmmt.model.DmProject;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Shape;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TextBoxEditorTest {
    @BeforeAll
    static void startJavaFx() throws Exception {
        FxTestSupport.startJavaFx();
    }

    @Test
    void caretMatchesEmptyTextColorAndImmediateColorChanges() throws Exception {
        Fixture fixture = onFx(() -> new Fixture());
        try {
            onFx(() -> {
                fixture.editor.setBoxColors("#1E1E1E99", "#00000000");
                fixture.editor.show(List.of(), 30, "#EEEEEE");
                fixture.assertCaret("#EEEEEE");
                fixture.editor.applyTextColor("#FFAA44");
                fixture.assertCaret("#FFAA44");
                fixture.editor.setBoxColors("#102030", "#506070");
                fixture.editor.place(20, 20, 280, 120, 1.5);
                fixture.assertCaret("#FFAA44");
                return null;
            });
        } finally {
            onFx(() -> { fixture.close(); return null; });
        }
    }

    @Test
    void caretTracksStyledRunsSelectionsAndReopening() throws Exception {
        Fixture fixture = onFx(() -> new Fixture());
        try {
            onFx(() -> {
                fixture.editor.show(List.of(
                        DmProject.TextRun.builder().text("Light").fontSize(30).color("#EEEEEE").build(),
                        DmProject.TextRun.builder().text("Gold").fontSize(30).color("#FFCC00").build()),
                        30, "#000000");
                fixture.assertCaret("#FFCC00");
                fixture.editor.node().moveTo(2);
                return null;
            });
            onFx(() -> {
                fixture.assertCaret("#EEEEEE");
                fixture.editor.node().selectRange(5, 9);
                return null;
            });
            onFx(() -> {
                fixture.assertCaret("#FFCC00");
                fixture.editor.applyTextColor("#66CCFF");
                fixture.assertCaret("#66CCFF");
                fixture.editor.node().replaceSelection("Blue");
                return null;
            });
            onFx(() -> {
                fixture.assertCaret("#66CCFF");
                fixture.editor.hide();
                fixture.editor.show(List.of(), 30, "#FFFFFF");
                fixture.assertCaret("#FFFFFF");
                return null;
            });
        } finally {
            onFx(() -> { fixture.close(); return null; });
        }
    }

    private static final class Fixture {
        final TextBoxEditor editor = new TextBoxEditor();
        final Pane root = new Pane(editor.node());
        final Stage stage = new Stage();

        Fixture() {
            Scene scene = new Scene(root, 400, 200);
            scene.getStylesheets().add(Icons.STYLESHEET);
            stage.setScene(scene);
            stage.show();
            editor.place(20, 20, 280, 120, 1);
        }

        void assertCaret(String color) {
            root.applyCss();
            root.layout();
            var carets = editor.node().lookupAll(".caret");
            assertFalse(carets.isEmpty(), "check the actual rendered caret, not just its CSS string");
            for (var caret : carets) {
                assertInstanceOf(Shape.class, caret);
                assertEquals(Color.web(color), ((Shape) caret).getStroke());
            }
        }

        void close() {
            editor.hide();
            stage.close();
        }
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        javafx.application.Platform.runLater(task);
        return task.get(15, TimeUnit.SECONDS);
    }
}
