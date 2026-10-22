package dmmt.ui;

import dmmt.FxTestSupport;
import dmmt.remote.*;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RemoteNetworkAccessWindowTest {
    @TempDir Path directory;

    @BeforeAll static void startFx() throws Exception { FxTestSupport.startJavaFx(); }

    @Test
    void configuredPrivateHostingChecksOnlyAfterStartAndNeverElevates() throws Exception {
        try (var fixture = fixture()) {
            fixture.access.status = new RemoteNetworkAccess.Status(true, "Private");
            assertEquals(0, fixture.access.checks);
            fx(() -> fixture.button("#remote-host-start").fire());
            assertEquals(0, fixture.starts);
            fixture.tasks.removeFirst().run();
            fx(() -> {
                assertEquals(1, fixture.starts);
                assertEquals(1, fixture.access.checks);
                assertEquals(0, fixture.access.allows);
            });
        }
    }

    @Test
    void permissionRequiresConsentAndCompletesBeforeHostingStarts() throws Exception {
        try (var fixture = fixture()) {
            fixture.startAndChoose("Allow private LAN access");
            fx(() -> {
                assertEquals(0, fixture.starts);
                assertEquals(0, fixture.access.allows);
                assertTrue(fixture.button("#remote-host-start").isDisabled());
            });
            fixture.tasks.removeFirst().run();
            fx(() -> {
                assertEquals(1, fixture.access.allows);
                assertEquals(1, fixture.starts);
            });
        }
    }

    @Test
    void cancellationAndPublicNetworkNeverGrantPermissionImplicitly() throws Exception {
        try (var fixture = fixture()) {
            fixture.startAndChoose("Cancel");
            fx(() -> {
                assertEquals(0, fixture.access.allows);
                assertEquals(0, fixture.starts);
            });
            fixture.access.status = new RemoteNetworkAccess.Status(true, "Public");
            fixture.startAndChoose("Host without changes");
            fx(() -> {
                assertEquals(0, fixture.access.allows);
                assertEquals(1, fixture.starts);
            });
        }
    }

    @Test
    void deniedAdministratorPermissionDoesNotStartHostingAndShowsCause() throws Exception {
        try (var fixture = fixture()) {
            fixture.access.failure = new IOException("Administrator permission was cancelled.");
            fixture.startAndChoose("Allow private LAN access");
            fixture.tasks.removeFirst().run();
            fx(() -> {
                assertEquals(0, fixture.starts);
                assertTrue(((Label) fixture.stage.getScene().lookup("#remote-network-status"))
                        .getText().contains("cancelled"));
                assertFalse(fixture.button("#remote-host-start").isDisabled());
            });
        }
    }

    @Test
    void closingSetupPreventsDelayedCheckFromStartingHosting() throws Exception {
        try (var fixture = fixture()) {
            fixture.access.status = new RemoteNetworkAccess.Status(true, "Private");
            fx(() -> {
                fixture.button("#remote-host-start").fire();
                fixture.window.close();
            });
            fixture.tasks.removeFirst().run();
            fx(() -> assertEquals(0, fixture.starts));
        }
    }

    @Test
    void failedCheckStillOffersHostingWithoutChangingRules() throws Exception {
        try (var fixture = fixture()) {
            fixture.access.checkFailure = new IOException("System policy prevented the check.");
            fixture.startAndChoose("Host without changes");
            fx(() -> {
                assertEquals(1, fixture.starts);
                assertEquals(0, fixture.access.allows);
            });
        }
    }

    @Test
    void removalRequiresConfirmationAndNeverStartsHosting() throws Exception {
        try (var fixture = fixture()) {
            Platform.runLater(() -> fixture.button("#remote-network-remove").fire());
            fx(() -> choose("Cancel"));
            fx(() -> assertEquals(0, fixture.access.removals));
            Platform.runLater(() -> fixture.button("#remote-network-remove").fire());
            fx(() -> choose("OK"));
            fx(() -> assertEquals(0, fixture.access.removals));
            fixture.tasks.removeFirst().run();
            fx(() -> {
                assertEquals(1, fixture.access.removals);
                assertEquals(0, fixture.starts);
            });
        }
    }

    private Fixture fixture() throws Exception {
        var fixture = new Fixture();
        fx(() -> {
            fixture.window = new RemoteSessionPanel(fixture,
                    new RemoteSessionStorage(directory, 1024, 8192, 2048), fixture.tasks::addLast, fixture.access);
            fixture.stage = new Stage();
            fixture.stage.setTitle("Session panel test");
            var scene = new javafx.scene.Scene(fixture.window, 300, 700);
            scene.getStylesheets().add(Icons.STYLESHEET);
            fixture.stage.setScene(scene);
            fixture.stage.show();
            fixture.window.show();
        });
        fixture.tasks.removeFirst().run();
        fx(() -> {
            var root = fixture.stage.getScene().getRoot();
            root.applyCss();
            root.layout();
            @SuppressWarnings("unchecked")
            var interfaces = (ComboBox<InetAddress>) root.lookup("#remote-host-interface");
            interfaces.setValue(InetAddress.getByName("192.0.2.44"));
        });
        return fixture;
    }

    private static void choose(String text) {
        var pane = Window.getWindows().stream().filter(window -> window.getScene() != null
                && window.getScene().getRoot() instanceof DialogPane).map(window ->
                (DialogPane) window.getScene().getRoot()).findFirst().orElseThrow();
        var type = pane.getButtonTypes().stream().filter(button -> text.equals(button.getText())).findFirst().orElseThrow();
        ((Button) pane.lookupButton(type)).fire();
    }

    private static void fx(CheckedAction action) throws Exception {
        var task = new FutureTask<Void>(() -> { action.run(); return null; });
        Platform.runLater(task);
        task.get(10, TimeUnit.SECONDS);
    }

    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }

    private static final class Access implements RemoteNetworkAccess {
        Status status = new Status(false, "Private");
        IOException failure;
        IOException checkFailure;
        int checks, allows, removals;
        @Override public boolean supported() { return true; }
        @Override public Status check(RemoteHostingController.Configuration configuration) throws IOException {
            checks++;
            if (checkFailure != null) throw checkFailure;
            return status;
        }
        @Override public void allow(RemoteHostingController.Configuration configuration) throws IOException {
            allows++;
            if (failure != null) throw failure;
        }
        @Override public void remove() { removals++; }
    }

    private static final class Fixture implements RemoteSessionPanel.Actions, AutoCloseable {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        final Access access = new Access();
        RemoteSessionPanel window;
        Stage stage;
        int starts;
        Button button(String id) { return (Button) stage.getScene().lookup(id); }
        void startAndChoose(String text) throws Exception {
            fx(() -> button("#remote-host-start").fire());
            tasks.removeFirst().run();
            fx(() -> choose(text));
            fx(() -> { });
        }
        @Override public RemoteHostingController host() { return null; }
        @Override public RemoteConnectionController client() { return null; }
        @Override public CompletableFuture<RemoteHostingController.Details> start(
                RemoteHostingController.Configuration configuration) {
            starts++;
            return CompletableFuture.completedFuture(null);
        }
        @Override public void connect(RemoteConnectionController.Configuration configuration, String code) {
            fail("No client connection expected.");
        }
        @Override public void disconnect() { fail("No client connection expected."); }
        @Override public void close() throws Exception { fx(() -> { window.close(); stage.close(); }); }
    }
}
