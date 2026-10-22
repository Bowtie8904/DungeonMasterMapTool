package dmmt.ui;

import dmmt.FxTestSupport;
import dmmt.api.DmControlApi;
import dmmt.model.DmProject.TokenDefinition;
import dmmt.render.TokenArt;
import dmmt.remote.RemoteOperations;
import dmmt.remote.RemoteAssetStore;
import dmmt.remote.RemoteTokens;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

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
    void remoteBrowserCreatesAgainstHostAndNeverReadsOrWritesTheClientLibraryWhileConnected() throws Exception
    {
        TokenLibraryService local = new TokenLibraryService(temp);
        local.put(TokenDefinition.builder().id("local").name("Local only").build(), "");
        byte[] originalFile = java.nio.file.Files.readAllBytes(temp.resolve("tokens.json"));
        AtomicReference<TokenBrowser> browser = new AtomicReference<>();
        AtomicReference<RemoteTokens.State> state = new AtomicReference<>(new RemoteTokens.State(0,
                java.util.List.of("Host folder"), java.util.List.of(new RemoteTokens.Entry("Host folder",
                new RemoteTokens.Definition("host", "Host only", "#123456", 1, dmmt.model.DmProject.TokenShape.SQUARE,
                        false, 1, .5, .5, null, null)))));
        AtomicInteger queries = new AtomicInteger();
        AtomicInteger commands = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean connected = new java.util.concurrent.atomic.AtomicBoolean(true);
        RemoteOperations.Connection connection = new RemoteOperations.Connection()
        {
            @Override public boolean connected() { return connected.get(); }
            @Override public CompletableFuture<com.fasterxml.jackson.databind.JsonNode> query(
                    String domain, String action, com.fasterxml.jackson.databind.JsonNode payload)
            {
                assertTrue(Platform.isFxApplicationThread());
                assertEquals("tokens", domain);
                assertEquals("list", action);
                queries.incrementAndGet();
                return CompletableFuture.completedFuture(RemoteTokens.json(state.get()));
            }
            @Override public CompletableFuture<com.fasterxml.jackson.databind.JsonNode> command(
                    String domain, String action, com.fasterxml.jackson.databind.JsonNode payload)
            {
                assertTrue(Platform.isFxApplicationThread());
                assertEquals("tokens", domain);
                assertEquals("change", action);
                commands.incrementAndGet();
                try
                {
                    var request = dmmt.service.JsonMappers.create().treeToValue(payload, RemoteTokens.Request.class);
                    assertEquals(RemoteTokens.Operation.PUT, request.operation());
                    assertEquals(0, request.revision());
                    assertEquals("Created on host", request.token().name());
                    var entries = new java.util.ArrayList<>(state.get().entries());
                    entries.add(new RemoteTokens.Entry(request.folder(), request.token()));
                    state.set(new RemoteTokens.State(1, state.get().folders(), entries));
                    return CompletableFuture.completedFuture(RemoteTokens.json(state.get()));
                }
                catch (java.io.IOException error)
                {
                    return CompletableFuture.failedFuture(error);
                }
            }
            @Override public CompletableFuture<RemoteAssetStore.AssetDescriptor> upload(Path file)
            {
                return CompletableFuture.failedFuture(new AssertionError("Image-free tokens need no upload"));
            }
            @Override public CompletableFuture<Path> download(RemoteAssetStore.AssetDescriptor asset)
            {
                return CompletableFuture.failedFuture(new AssertionError("Image-free tokens need no download"));
            }
        };
        onFx(() -> {
            browser.set(new TokenBrowser(local, ignored -> fail("B must not optimistically update host map definitions")));
            new Scene(browser.get());
            browser.get().beginRemote(connection);
        });
        awaitFx(() -> browser.get().find("host") != null);
        onFx(() -> {
            assertNull(browser.get().find("local"));
            var copied = browser.get().find("host");
            copied.setName("Not a library mutation");
            assertEquals("Host only", browser.get().find("host").getName());
            assertFalse(browser.get().find("host").isBorder());
        });
        java.nio.file.Files.writeString(temp.resolve("tokens.json"), "invalid local library: remote mode must never load this");
        onFx(() -> {
            Platform.runLater(() -> {
                var dialog = javafx.stage.Window.getWindows().stream()
                        .filter(window -> window.getScene() != null && window.getScene().lookup("#token-name") != null)
                        .findFirst().orElseThrow();
                ((TextField)dialog.getScene().lookup("#token-name")).setText("Created on host");
                DialogPane pane = (DialogPane)dialog.getScene().getRoot();
                var save = pane.getButtonTypes().stream().filter(button -> button != ButtonType.CANCEL).findFirst().orElseThrow();
                ((Button)pane.lookupButton(save)).fire();
            });
            ((Button)browser.get().controls().get("tokens.create")).fire();
        });
        awaitFx(() -> commands.get() == 1 && browser.get().find(state.get().entries().getLast().token().id()) != null);
        assertEquals("invalid local library: remote mode must never load this",
                java.nio.file.Files.readString(temp.resolve("tokens.json")));
        connected.set(false);
        onFx(() -> {
            int before = queries.get();
            browser.get().refresh();
            browser.get().tickRemote();
            assertEquals(before, queries.get(), "Disconnected clients must not poll or load local data");
        });
        java.nio.file.Files.write(temp.resolve("tokens.json"), originalFile);
        onFx(() -> {
            browser.get().endRemote();
            assertNotNull(browser.get().find("local"));
            assertNull(browser.get().find("host"));
        });
        assertEquals(1, commands.get());
    }

    private static void awaitFx(java.util.function.BooleanSupplier condition) throws Exception
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        var done = new java.util.concurrent.atomic.AtomicBoolean();
        while (System.nanoTime() < deadline)
        {
            onFx(() -> done.set(condition.getAsBoolean()));
            if (done.get())
                return;
            Thread.sleep(20);
        }
        fail("The remote token browser did not finish synchronization.");
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
