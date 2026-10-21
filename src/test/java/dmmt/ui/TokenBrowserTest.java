package dmmt.ui;

import dmmt.FxTestSupport;
import dmmt.api.DmControlApi;
import dmmt.model.DmProject.TokenDefinition;
import dmmt.render.TokenArt;
import dmmt.service.TokenLibraryService;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TokenBrowserTest
{
    @TempDir Path temp;

    @BeforeAll
    static void startFx() throws Exception
    {
        FxTestSupport.startJavaFx();
    }

    @Test
    void browserSearchIncludesFolderDescendantsAndControlsRemainAvailableWhenHidden() throws Exception
    {
        onFx(() -> {
            TokenLibraryService library = new TokenLibraryService(temp);
            library.createFolder("", "Party");
            library.createFolder("Party", "Heroes");
            library.createFolder("", "Monsters");
            library.put(TokenDefinition.builder().id("hero").name("Ranger").build(), "Party/Heroes");
            library.put(TokenDefinition.builder().id("orc").name("Orc").build(), "Monsters");
            TokenBrowser browser = new TokenBrowser(library, token -> {
            });
            new Scene(browser);
            TreeView<?> tree = (TreeView<?>)browser.getChildren().stream().filter(TreeView.class::isInstance).findFirst().orElseThrow();
            TextField search = (TextField)browser.lookup("#token-search");
            search.setText("party ranger");
            assertEquals(1, tree.getRoot().getChildren().size());
            assertEquals(1, tree.getRoot().getChildren().getFirst().getChildren().getFirst().getChildren().size());
            search.setText("no match");
            assertTrue(tree.getRoot().getChildren().isEmpty());
            search.setText("monsters");
            assertEquals(1, tree.getRoot().getChildren().size());
            assertEquals(1, tree.getRoot().getChildren().getFirst().getChildren().size());
            ControlVisibility visibility = new ControlVisibility();
            browser.controls().forEach((id, node) -> visibility.register(id, node));
            DmControlApi api = new DmControlApi(visibility);
            visibility.apply(browser.controls().keySet());
            assertTrue(browser.controls().values().stream().noneMatch(javafx.scene.Node::isVisible));
            assertEquals(browser.controls().size(), api.describe().size());
            api.execute("tokens.refresh", Map.of());
            assertTrue(api.keyImage("tokens.create", null).length > 100);
            visibility.apply(Set.of());
            assertTrue(browser.controls().values().stream().allMatch(javafx.scene.Node::isManaged));
        });
    }

    @Test
    void editorRetainsOriginalForLaterRealignmentAndSavesImageFreeTokens() throws Exception
    {
        onFx(() -> {
            WritableImage source = new WritableImage(120, 80);
            for (int y = 0; y < 80; y++)
                for (int x = 0; x < 120; x++)
                    source.getPixelWriter().setColor(x, y, Color.RED);
            TokenDefinition original = TokenDefinition.builder().id("hero").name("Hero").build();
            Dialog<TokenDefinition> dialog = TokenEditor.create(null, original, source);
            assertNull(original.getSourceImage(), "Cancel/edit staging does not mutate the original");
            Slider zoom = (Slider)dialog.getDialogPane().lookup("#token-zoom");
            zoom.setValue(2);
            ButtonType save = dialog.getDialogPane().getButtonTypes().stream().filter(b -> b != ButtonType.CANCEL).findFirst().orElseThrow();
            ((Button)dialog.getDialogPane().lookupButton(save)).fire();
            TokenDefinition result = dialog.getResultConverter().call(save);
            assertNotNull(result.getImage());
            assertNotNull(result.getSourceImage());
            assertEquals(2, result.getZoom());
            assertEquals(120, TokenArt.decode(result.getSourceImage()).getWidth());
            assertEquals(256, TokenArt.decode(result.getImage()).getWidth());
            Dialog<TokenDefinition> reopened = TokenEditor.create(null, result, null);
            assertEquals(2, ((Slider)reopened.getDialogPane().lookup("#token-zoom")).getValue());
            Dialog<TokenDefinition> plain = TokenEditor.create(null, original, null);
            ButtonType plainSave = plain.getDialogPane().getButtonTypes().stream().filter(b -> b != ButtonType.CANCEL).findFirst().orElseThrow();
            ((Button)plain.getDialogPane().lookupButton(plainSave)).fire();
            TokenDefinition colorOnly = plain.getResultConverter().call(plainSave);
            assertNull(colorOnly.getImage());
            assertEquals(1, colorOnly.getSizeTiles());
        });
    }

    @Test
    void editorShapeAndBorderChangeTheCropPreviewAndSurviveReopening() throws Exception
    {
        onFx(() -> {
            WritableImage source = new WritableImage(100, 100);
            for (int y = 0; y < 100; y++)
                for (int x = 0; x < 100; x++)
                    source.getPixelWriter().setColor(x, y, x < 50 ? Color.RED : Color.TRANSPARENT);
            var original = TokenDefinition.builder().id("shape").name("Shape").build();
            Dialog<TokenDefinition> dialog = TokenEditor.create(null, original, source);
            var pane = dialog.getDialogPane();
            CheckBox border = (CheckBox)pane.lookup("#token-border");
            @SuppressWarnings("unchecked")
            ComboBox<dmmt.model.DmProject.TokenShape> shape =
                    (ComboBox<dmmt.model.DmProject.TokenShape>)pane.lookup("#token-shape");
            var canvas = (javafx.scene.canvas.Canvas)pane.lookup("#token-preview");
            var transparent = new javafx.scene.SnapshotParameters();
            transparent.setFill(Color.TRANSPARENT);
            assertTrue(border.isSelected());
            assertEquals(dmmt.model.DmProject.TokenShape.CIRCLE, shape.getValue());
            assertEquals(0, canvas.snapshot(transparent, null).getPixelReader().getColor(15, 15).getOpacity());
            shape.setValue(dmmt.model.DmProject.TokenShape.SQUARE);
            assertEquals(Color.RED, canvas.snapshot(transparent, null).getPixelReader().getColor(15, 15));
            assertTrue(canvas.snapshot(transparent, null).getPixelReader().getColor(230, 140).getOpacity() > 0);
            border.setSelected(false);
            assertEquals(0, canvas.snapshot(transparent, null).getPixelReader().getColor(230, 140).getOpacity(),
                    "Border off also removes the added background");
            assertEquals(0, canvas.snapshot(transparent, null).getPixelReader().getColor(277, 140).getOpacity(),
                    "No outline remains");
            ButtonType save = pane.getButtonTypes().stream().filter(b -> b != ButtonType.CANCEL).findFirst().orElseThrow();
            ((Button)pane.lookupButton(save)).fire();
            var result = dialog.getResultConverter().call(save);
            assertFalse(result.isBorder());
            assertEquals(dmmt.model.DmProject.TokenShape.SQUARE, result.getShape());
            assertEquals(0, TokenArt.decode(result.getImage()).getPixelReader().getColor(200, 128).getOpacity(),
                    "Saved artwork never bakes in a background");
            var reopened = TokenEditor.create(null, result, null);
            assertFalse(((CheckBox)reopened.getDialogPane().lookup("#token-border")).isSelected());
            assertEquals(result.getShape(), ((ComboBox<?>)reopened.getDialogPane().lookup("#token-shape")).getValue());
            assertTrue(original.isBorder());
            assertEquals(dmmt.model.DmProject.TokenShape.CIRCLE, original.getShape(), "Editing is staged until save");
        });
    }

    @Test
    void editorCtrlVPastesAnImageAndLeavesOrdinaryTextPasteAlone() throws Exception
    {
        onFx(() -> {
            var clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
            var previous = new javafx.scene.input.ClipboardContent();
            for (var format : clipboard.getContentTypes())
                previous.put(format, clipboard.getContent(format));
            try
            {
                var content = new javafx.scene.input.ClipboardContent();
                content.putImage(new WritableImage(37, 29));
                clipboard.setContent(content);
                var original = TokenDefinition.builder().id("hero").name("Hero").build();
                Dialog<TokenDefinition> dialog = TokenEditor.create(null, original, null);
                var pane = dialog.getDialogPane();
                javafx.event.Event.fireEvent(pane, new javafx.scene.input.KeyEvent(
                        javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", javafx.scene.input.KeyCode.V,
                        false, true, false, false));
                ButtonType save = pane.getButtonTypes().stream().filter(b -> b != ButtonType.CANCEL).findFirst().orElseThrow();
                ((Button)pane.lookupButton(save)).fire();
                var token = dialog.getResultConverter().call(save);
                assertEquals(37, TokenArt.decode(token.getSourceImage()).getWidth());
                assertEquals(29, TokenArt.decode(token.getSourceImage()).getHeight());
                content.putString("pasted name");
                clipboard.setContent(content);
                var plain = TokenEditor.create(null, original, null);
                try
                {
                    plain.show();
                    plain.getDialogPane().applyCss();
                    var name = (TextField)plain.getDialogPane().lookup("#token-name");
                    name.selectAll();
                    javafx.event.Event.fireEvent(name, new javafx.scene.input.KeyEvent(
                            javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", javafx.scene.input.KeyCode.V,
                            false, true, false, false));
                    assertEquals("pasted name", name.getText());
                    ButtonType plainSave = plain.getDialogPane().getButtonTypes().stream().filter(b -> b != ButtonType.CANCEL).findFirst().orElseThrow();
                    assertNull(plain.getResultConverter().call(plainSave).getSourceImage());
                }
                finally
                {
                    plain.close();
                }
            }
            finally
            {
                clipboard.setContent(previous);
            }
        });
    }

    @Test
    void imageImportAcceptsTiffThroughImageIoAndReportsUnsupportedFiles() throws Exception
    {
        var image = new java.awt.image.BufferedImage(12, 8, java.awt.image.BufferedImage.TYPE_INT_RGB);
        Path file = temp.resolve("token.tiff");
        assertTrue(javax.imageio.ImageIO.write(image, "tiff", file.toFile()));
        onFx(() -> assertEquals(12, TokenEditor.readImage(file.toFile()).getWidth()));
        Path webp = temp.resolve("token.webp");
        java.nio.file.Files.write(webp, java.util.Base64.getDecoder().decode("UklGRh4AAABXRUJQVlA4TBEAAAAvAAAAEAcQERGIiP4HAA=="));
        onFx(() -> assertEquals(1, TokenEditor.readImage(webp.toFile()).getWidth()));
        java.nio.file.Files.writeString(temp.resolve("invalid.txt"), "not an image");
        onFx(() -> assertThrows(java.io.IOException.class, () -> TokenEditor.readImage(temp.resolve("invalid.txt").toFile())));
    }

    private static void onFx(CheckedAction action) throws Exception
    {
        FutureTask<Void> task = new FutureTask<>(() -> {
            action.run();
            return null;
        });
        Platform.runLater(task);
        task.get(30, TimeUnit.SECONDS);
    }

    private interface CheckedAction
    {
        void run() throws Exception;
    }
}
