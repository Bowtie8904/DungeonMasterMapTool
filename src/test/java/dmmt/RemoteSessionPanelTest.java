package dmmt;

import dmmt.remote.RemoteConnectionController;
import dmmt.remote.RemoteHostingController;
import dmmt.remote.RemoteSessionStorage;
import dmmt.remote.RemoteSetupStore;
import dmmt.ui.RemoteSessionPanel;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RemoteSessionPanelTest {
    @TempDir Path directory;

    @BeforeAll
    static void startFx() throws Exception {
        FxTestSupport.startJavaFx();
    }

    @Test
    void savedSetupLoadsOnlyWhenOpenedWithoutTrustOrConnectionAndMissingInterfaceIsNotSubstituted() throws Exception {
        var saved = new RemoteSetupStore.Setup(new RemoteSetupStore.Host("Saved table", "192.0.2.77", 7172, 7173),
                new RemoteSetupStore.Client("192.168.1.8", "Saved session", "DM", 7272, 7273));
        new RemoteSetupStore(directory).save(saved);
        var tasks = new java.util.ArrayDeque<Runnable>();
        var window = new AtomicReference<RemoteSessionPanel>();
        try {
            onFx(() -> {
                window.set(create(inactiveActions(),
                        new RemoteSessionStorage(directory, 1024, 8192, 2048), tasks::addLast));
                window.get().show();
                assertEquals(1, tasks.size(), "Setup load and interface enumeration share one explicit worker task.");
            });
            tasks.removeFirst().run();
            onFx(() -> {
                var scene = remoteStage().getScene();
                scene.getRoot().applyCss();
                scene.getRoot().layout();
                var interfaces = (javafx.scene.control.ComboBox<?>) scene.lookup("#remote-host-interface");
                assertNull(interfaces.getValue(), "An unavailable saved NIC must never silently change binding.");
                assertTrue(((Button) scene.lookup("#remote-host-start")).isDisabled());
                assertEquals("Saved table", ((TextField) scene.lookup("#remote-host-name")).getText());
                assertTrue(((javafx.scene.control.Label) scene.lookup("#remote-saved-setup-status")).getText()
                        .contains("unavailable"));
                ((javafx.scene.control.ToggleButton) scene.lookup("#remote-mode-connect")).setSelected(true);
                scene.getRoot().applyCss();
                scene.getRoot().layout();
                assertEquals("192.168.1.8", ((TextField) scene.lookup("#remote-client-host")).getText());
                assertEquals("Saved session", ((TextField) scene.lookup("#remote-client-session")).getText());
                assertTrue(((TextField) scene.lookup("#remote-client-fingerprint")).getText().isEmpty());
                assertTrue(((PasswordField) scene.lookup("#remote-client-code")).getText().isEmpty());
                assertFalse(((CheckBox) scene.lookup("#remote-client-verified")).isSelected());
                assertTrue(((Button) scene.lookup("#remote-client-connect")).isDisabled());
                assertTrue(tasks.isEmpty(), "Loading configuration must not trigger discovery or connection.");
            });
        } finally { onFx(() -> { if (window.get() != null) window.get().close(); }); }
        assertEquals(saved, new RemoteSetupStore(directory).load());
    }

    @Test
    void delayedSetupLoadDoesNotOverwriteUserInputAndUnsubmittedEditsAreNotPersisted() throws Exception {
        var saved = new RemoteSetupStore.Setup(null,
                new RemoteSetupStore.Client("saved.local", "Saved", "DM", 7072, 7073));
        new RemoteSetupStore(directory).save(saved);
        var tasks = new java.util.ArrayDeque<Runnable>();
        var window = new AtomicReference<RemoteSessionPanel>();
        try {
            onFx(() -> {
                window.set(create(inactiveActions(),
                        new RemoteSessionStorage(directory, 1024, 8192, 2048), tasks::addLast));
                window.get().show();
                var scene = remoteStage().getScene();
                ((javafx.scene.control.ToggleButton) scene.lookup("#remote-mode-connect")).setSelected(true);
                scene.getRoot().applyCss();
                scene.getRoot().layout();
                ((TextField) scene.lookup("#remote-client-host")).setText("typed.local");
                ((TextField) scene.lookup("#remote-client-fingerprint")).setText("a".repeat(64));
            });
            tasks.removeFirst().run();
            onFx(() -> {
                var scene = remoteStage().getScene();
                assertEquals("typed.local", ((TextField) scene.lookup("#remote-client-host")).getText());
                assertEquals("a".repeat(64), ((TextField) scene.lookup("#remote-client-fingerprint")).getText());
                assertTrue(tasks.isEmpty());
            });
        } finally { onFx(() -> { if (window.get() != null) window.get().close(); }); }
        assertEquals(saved, new RemoteSetupStore(directory).load());
    }

    @Test
    void submittedSetupWritesAreOrderedAndPreserveTheOtherRoleWithoutPersistingTrust() throws Exception {
        var originalHost = new RemoteSetupStore.Host("Table", "127.0.0.1", 7072, 7073);
        new RemoteSetupStore(directory).save(new RemoteSetupStore.Setup(originalHost, null));
        var tasks = new java.util.ArrayDeque<Runnable>();
        var connections = new AtomicInteger();
        var window = new AtomicReference<RemoteSessionPanel>();
        try {
            onFx(() -> {
                var actions = new RemoteSessionPanel.Actions() {
                    @Override public RemoteHostingController host() { return null; }
                    @Override public RemoteConnectionController client() { return null; }
                    @Override public CompletableFuture<RemoteHostingController.Details> start(
                            RemoteHostingController.Configuration configuration) { throw new AssertionError(); }
                    @Override public void connect(RemoteConnectionController.Configuration configuration, String code) {
                        connections.incrementAndGet();
                    }
                    @Override public void disconnect() { throw new AssertionError(); }
                };
                window.set(create(actions,
                        new RemoteSessionStorage(directory, 1024, 8192, 2048), tasks::addLast));
                window.get().show();
            });
            tasks.removeFirst().run();
            onFx(() -> {
                var scene = remoteStage().getScene();
                ((javafx.scene.control.ToggleButton) scene.lookup("#remote-mode-connect")).setSelected(true);
                scene.getRoot().applyCss();
                scene.getRoot().layout();
                for (String address : java.util.List.of("first.local", "last.local")) {
                    ((TextField) scene.lookup("#remote-client-host")).setText(address);
                    ((TextField) scene.lookup("#remote-client-fingerprint")).setText("c".repeat(64));
                    ((PasswordField) scene.lookup("#remote-client-code")).setText("12345678");
                    ((CheckBox) scene.lookup("#remote-client-verified")).setSelected(true);
                    ((Button) scene.lookup("#remote-client-connect")).fire();
                }
                assertEquals(2, connections.get());
                assertEquals(1, tasks.size(), "Only the first persistence task can run until it completes.");
            });
            tasks.removeFirst().run();
            assertEquals("first.local", new RemoteSetupStore(directory).load().client().host());
            assertEquals(1, tasks.size());
            tasks.removeFirst().run();
            var saved = new RemoteSetupStore(directory).load();
            assertEquals("last.local", saved.client().host());
            assertEquals(originalHost, saved.host());
            String file = java.nio.file.Files.readString(directory.resolve("setup.properties"));
            assertFalse(file.contains("12345678"));
            assertFalse(file.contains("c".repeat(64)));
            onFx(() -> {
                var scene = remoteStage().getScene();
                ((PasswordField) scene.lookup("#remote-client-code")).setText("87654321");
                ((CheckBox) scene.lookup("#remote-client-verified")).setSelected(true);
                window.get().close();
                window.get().show();
                assertTrue(((PasswordField) scene.lookup("#remote-client-code")).getText().isEmpty());
                assertFalse(((CheckBox) scene.lookup("#remote-client-verified")).isSelected());
            });
        } finally { onFx(() -> { if (window.get() != null) window.get().close(); }); }
    }

    @Test
    void corruptSavedSetupReportsAnErrorWithoutStartingAnySessionOrPreventingManualInput() throws Exception {
        java.nio.file.Files.writeString(directory.resolve("setup.properties"), "version=1\ncredential=not-allowed");
        var tasks = new java.util.ArrayDeque<Runnable>();
        var window = new AtomicReference<RemoteSessionPanel>();
        try {
            onFx(() -> {
                window.set(create(inactiveActions(),
                        new RemoteSessionStorage(directory, 1024, 8192, 2048), tasks::addLast));
                window.get().show();
            });
            tasks.removeFirst().run();
            onFx(() -> {
                var scene = remoteStage().getScene();
                assertTrue(((javafx.scene.control.Label) scene.lookup("#remote-saved-setup-status"))
                        .getText().contains("could not be read"));
                ((javafx.scene.control.ToggleButton) scene.lookup("#remote-mode-connect")).setSelected(true);
                scene.getRoot().applyCss();
                scene.getRoot().layout();
                var host = (TextField) scene.lookup("#remote-client-host");
                host.setText("manual.local");
                assertFalse(host.isDisabled());
                assertTrue(tasks.isEmpty());
            });
        } finally { onFx(() -> { if (window.get() != null) window.get().close(); }); }
    }

    @Test
    void setupWriteFailureDoesNotUndoHostingAndLaterSubmissionsCanStillBeRemembered() throws Exception {
        var tasks = new java.util.ArrayDeque<Runnable>();
        var submissions = new AtomicInteger();
        var window = new AtomicReference<RemoteSessionPanel>();
        var submitted = new AtomicReference<RemoteHostingController.Configuration>();
        try {
            onFx(() -> {
                window.set(create(new RemoteSessionPanel.Actions() {
                    @Override public RemoteHostingController host() { return null; }
                    @Override public RemoteConnectionController client() { return null; }
                    @Override public CompletableFuture<RemoteHostingController.Details> start(
                            RemoteHostingController.Configuration configuration) {
                        submitted.set(configuration);
                        submissions.incrementAndGet();
                        return CompletableFuture.completedFuture(new RemoteHostingController.Details(
                                configuration.name(), configuration.bindAddress(), configuration.controlPort(),
                                configuration.assetPort(), "a".repeat(64), "b".repeat(64)));
                    }
                    @Override public void connect(RemoteConnectionController.Configuration configuration, String code) {
                        throw new AssertionError("Unexpected connection");
                    }
                    @Override public void disconnect() { throw new AssertionError("No client"); }
                }, new RemoteSessionStorage(directory, 1024, 8192, 2048), tasks::addLast));
                window.get().show();
            });
            tasks.removeFirst().run();
            Path occupied = java.nio.file.Files.createDirectory(directory.resolve("setup.properties"));
            Path retain = java.nio.file.Files.writeString(occupied.resolve("retain.txt"), "keep");
            onFx(() -> {
                var scene = remoteStage().getScene();
                scene.getRoot().applyCss();
                scene.getRoot().layout();
                ((Button) scene.lookup("#remote-host-start")).fire();
                assertEquals(1, submissions.get());
            });
            tasks.removeFirst().run();
            onFx(() -> assertTrue(((javafx.scene.control.Label) remoteStage().getScene()
                    .lookup("#remote-saved-setup-status")).getText().contains("could not be remembered")));
            assertEquals("keep", java.nio.file.Files.readString(retain));
            java.nio.file.Files.delete(retain);
            java.nio.file.Files.delete(occupied);
            onFx(() -> ((Button) remoteStage().getScene().lookup("#remote-host-start")).fire());
            tasks.removeFirst().run();
            var saved = new RemoteSetupStore(directory).load();
            assertEquals(2, submissions.get());
            assertNotNull(saved.host());
            assertEquals(submitted.get().bindAddress().getHostAddress(), saved.host().address());
            assertEquals(submitted.get().controlPort(), saved.host().controlPort());
            assertNull(saved.client());
        } finally { onFx(() -> { if (window.get() != null) window.get().close(); }); }
    }

    @Test
    void discoveredHostIsOnlyAHintAndSelectionClearsPreviouslyVerifiedIdentityAndCode() throws Exception {
        var tasks = new java.util.ArrayDeque<Runnable>();
        onFx(() -> {
            var window = create(inactiveActions(),
                    new RemoteSessionStorage(directory, 1024, 8192, 2048), tasks::addLast);
            try {
                assertEquals(1, tasks.size(), "Only explicitly opened setup's interface enumeration is queued.");
                window.show();
                var scene = remoteStage().getScene();
                ((javafx.scene.control.ToggleButton) scene.lookup("#remote-mode-connect")).setSelected(true);
                scene.getRoot().applyCss();
                scene.getRoot().layout();
                var fingerprint = (TextField) scene.lookup("#remote-client-fingerprint");
                var code = (PasswordField) scene.lookup("#remote-client-code");
                var verified = (CheckBox) scene.lookup("#remote-client-verified");
                fingerprint.setText("a".repeat(64));
                code.setText("12345678");
                verified.setSelected(true);
                @SuppressWarnings("unchecked")
                var discovered = (javafx.scene.control.ComboBox<dmmt.remote.RemoteDiscovery.Host>)
                        scene.lookup("#remote-discovered-hosts");
                var found = new dmmt.remote.RemoteDiscovery.Host("LAN table",
                        java.net.InetAddress.getByName("192.168.1.31"), 7172, 7173,
                        "b".repeat(64), "c".repeat(64));
                discovered.getItems().setAll(found);
                discovered.getSelectionModel().selectFirst();
                assertEquals("192.168.1.31", ((TextField) scene.lookup("#remote-client-host")).getText());
                assertEquals(found.fingerprint(), fingerprint.getText());
                assertTrue(code.getText().isEmpty());
                assertFalse(verified.isSelected());
                assertFalse(((Button) scene.lookup("#remote-client-connect")).isDisabled());
                assertEquals("Request control", ((Button) scene.lookup("#remote-client-connect")).getText());
                assertFalse(((javafx.scene.control.TitledPane) scene.lookup("#remote-client-manual")).isExpanded());
                assertEquals(1, tasks.size(), "Selecting a hint must not initiate network work.");
                ((Button) scene.lookup("#remote-discover")).fire();
                assertEquals(2, tasks.size());
                assertTrue(((Button) scene.lookup("#remote-discover")).isDisabled());
            } finally { window.close(); }
        });
        // The queued explicit search was cancelled before execution and must return without opening sockets.
        tasks.removeLast().run();
    }

    @Test
    void discoveredRequestsUseTheExactPinWithoutACodeAndManualEditsRequireVerificationAgain() throws Exception {
        var tasks = new java.util.ArrayDeque<Runnable>();
        var connections = new AtomicInteger();
        var sent = new AtomicReference<RemoteConnectionController.Configuration>();
        var submittedCode = new AtomicReference<String>("not submitted");
        onFx(() -> {
            var actions = new RemoteSessionPanel.Actions() {
                @Override public RemoteHostingController host() { return null; }
                @Override public RemoteConnectionController client() { return null; }
                @Override public CompletableFuture<RemoteHostingController.Details> start(
                        RemoteHostingController.Configuration configuration) { throw new AssertionError(); }
                @Override public void connect(RemoteConnectionController.Configuration configuration, String code) {
                    connections.incrementAndGet();
                    sent.set(configuration);
                    submittedCode.set(code);
                }
                @Override public void disconnect() { throw new AssertionError(); }
            };
            var window = create(actions,
                    new RemoteSessionStorage(directory, 1024, 8192, 2048), tasks::addLast);
            try {
                window.show();
                var scene = remoteStage().getScene();
                ((javafx.scene.control.ToggleButton) scene.lookup("#remote-mode-connect")).setSelected(true);
                scene.getRoot().applyCss();
                scene.getRoot().layout();
                var host = (TextField) scene.lookup("#remote-client-host");
                var fingerprint = (TextField) scene.lookup("#remote-client-fingerprint");
                var code = (PasswordField) scene.lookup("#remote-client-code");
                var verified = (CheckBox) scene.lookup("#remote-client-verified");
                var connect = (Button) scene.lookup("#remote-client-connect");
                @SuppressWarnings("unchecked")
                var control = (javafx.scene.control.Spinner<Integer>) scene.lookup("#remote-client-control-port");
                @SuppressWarnings("unchecked")
                var artwork = (javafx.scene.control.Spinner<Integer>) scene.lookup("#remote-client-artwork-port");
                @SuppressWarnings("unchecked")
                var discovered = (javafx.scene.control.ComboBox<dmmt.remote.RemoteDiscovery.Host>)
                        scene.lookup("#remote-discovered-hosts");
                var first = new dmmt.remote.RemoteDiscovery.Host("Table A",
                        java.net.InetAddress.getByName("192.168.1.31"), 7172, 7173,
                        "b".repeat(64), "c".repeat(64));
                var second = new dmmt.remote.RemoteDiscovery.Host("Table B",
                        java.net.InetAddress.getByName("192.168.1.32"), 7272, 7273,
                        "b".repeat(64), "d".repeat(64));
                discovered.getItems().setAll(first, second);
                discovered.setValue(first);
                assertFalse(connect.isDisabled());
                connect.fire();
                assertEquals(1, connections.get());
                assertNull(submittedCode.get(), "Approval-only requests must not submit a pairing code.");
                assertEquals(first.fingerprint(), sent.get().verifiedFingerprint());
                assertEquals(first.address().getHostAddress(), sent.get().host());
                assertEquals(first.controlPort(), sent.get().controlPort());
                assertEquals(first.assetPort(), sent.get().assetPort());
                assertFalse(verified.isSelected(), "A discovery pin is not verified host identity.");
                code.setText("12345678");
                verified.setSelected(true);
                discovered.setValue(second);
                assertEquals(second.fingerprint(), fingerprint.getText());
                assertTrue(code.getText().isEmpty());
                assertFalse(verified.isSelected());
                assertFalse(connect.isDisabled());
                connect.fire();
                assertEquals(2, connections.get(), "A new host session needs only explicit approval, not copied text.");
                assertEquals(second.fingerprint(), sent.get().verifiedFingerprint());
                assertNull(submittedCode.get());

                for (var input : java.util.List.of(host, fingerprint, control.getEditor(), artwork.getEditor())) {
                    discovered.setValue(first);
                    String original = input.getText();
                    input.setText(original + "0");
                    assertNull(discovered.getValue(), "Manual edits must clear the selected discovery route.");
                    assertFalse(verified.isSelected());
                    assertTrue(connect.isDisabled());
                    input.setText(original);
                    assertTrue(connect.isDisabled(), "Restoring text must not restore discovery permission.");
                    code.setText("12345678");
                    assertTrue(connect.isDisabled());
                    verified.setSelected(true);
                    assertFalse(connect.isDisabled(), "The separately verified manual route remains available.");
                    code.clear();
                    assertTrue(connect.isDisabled());
                }
                for (var spinner : java.util.List.of(control, artwork)) {
                    discovered.setValue(first);
                    spinner.getValueFactory().setValue(spinner.getValue() + 1);
                    assertNull(discovered.getValue());
                    assertTrue(connect.isDisabled());
                }
                discovered.setValue(first);
                window.close();
                window.show();
                assertNull(discovered.getValue(), "Reopening setup must not restore an approval-only selection.");
                assertTrue(connect.isDisabled());
                assertEquals(2, connections.get(), "Reopening setup must not reconnect.");
            } finally { window.close(); }
        });
    }

    @org.junit.jupiter.api.AfterAll
    static void closeStage() throws Exception {
        onFx(() -> { if (stage != null) { stage.close(); stage = null; } });
    }

    private static Stage stage;

    private static Stage remoteStage() { return stage; }

    private static RemoteSessionPanel create(RemoteSessionPanel.Actions actions, RemoteSessionStorage storage,
                                             java.util.concurrent.Executor worker) {
        var panel = new RemoteSessionPanel(actions, storage, worker, new dmmt.remote.RemoteNetworkAccess() {
            @Override public boolean supported() { return false; }
            @Override public Status check(RemoteHostingController.Configuration c) { throw new UnsupportedOperationException(); }
            @Override public void allow(RemoteHostingController.Configuration c) { throw new UnsupportedOperationException(); }
            @Override public void remove() { throw new UnsupportedOperationException(); }
        });
        if (stage != null) stage.close();
        stage = new Stage();
        stage.setTitle("Session panel test");
        var scene = new javafx.scene.Scene(panel, 300, 700);
        scene.getStylesheets().add(dmmt.ui.Icons.STYLESHEET);
        stage.setScene(scene);
        stage.show();
        return panel;
    }

    private static RemoteSessionPanel.Actions inactiveActions() {
        return new RemoteSessionPanel.Actions() {
            @Override public RemoteHostingController host() { return null; }
            @Override public RemoteConnectionController client() { return null; }
            @Override public CompletableFuture<RemoteHostingController.Details> start(
                    RemoteHostingController.Configuration configuration) { throw new AssertionError("Unexpected hosting"); }
            @Override public void connect(RemoteConnectionController.Configuration configuration, String code) {
                fail("Discovery must never connect automatically.");
            }
            @Override public void disconnect() { fail("No active client."); }
        };
    }

    @Test
    void clientCannotConnectUntilFingerprintIsExplicitlyVerifiedAndIdentityChangesResetConfirmation() throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        var connections = new AtomicInteger();
        var sent = new AtomicReference<RemoteConnectionController.Configuration>();
        var submittedCode = new AtomicReference<String>();
        var window = new AtomicReference<RemoteSessionPanel>();
        try {
            onFx(() -> {
                window.set(create(new RemoteSessionPanel.Actions() {
                    @Override public RemoteHostingController host() { return null; }
                    @Override public RemoteConnectionController client() { return null; }
                    @Override public CompletableFuture<RemoteHostingController.Details> start(
                            RemoteHostingController.Configuration configuration) {
                        fail("This test must not start a host.");
                        return CompletableFuture.failedFuture(new AssertionError());
                    }
                    @Override public void connect(RemoteConnectionController.Configuration configuration, String code) {
                        connections.incrementAndGet();
                        sent.set(configuration);
                        submittedCode.set(code);
                    }
                    @Override public void disconnect() { fail("This fixture has no client."); }
                }, new RemoteSessionStorage(directory, 1024, 8192, 2048), worker));
                window.get().show();
                Stage stage = remoteStage();
                var scene = stage.getScene();
                
                ((javafx.scene.control.ToggleButton) scene.lookup("#remote-mode-connect")).setSelected(true);
                scene.getRoot().applyCss();
                scene.getRoot().layout();
                var host = (TextField) scene.lookup("#remote-client-host");
                var fingerprint = (TextField) scene.lookup("#remote-client-fingerprint");
                var code = (PasswordField) scene.lookup("#remote-client-code");
                var verified = (CheckBox) scene.lookup("#remote-client-verified");
                var connect = (Button) scene.lookup("#remote-client-connect");
                assertTrue(connect.isDisabled());
                host.setText("192.168.1.20");
                fingerprint.setText("a".repeat(64));
                code.setText("12345678");
                assertTrue(connect.isDisabled());
                verified.setSelected(true);
                assertFalse(connect.isDisabled());
                host.setText("192.168.1.21");
                assertFalse(verified.isSelected());
                assertTrue(connect.isDisabled());
                verified.setSelected(true);
                fingerprint.setText("b".repeat(64));
                assertFalse(verified.isSelected());
                verified.setSelected(true);
                connect.fire();
                assertEquals(1, connections.get());
                assertEquals("192.168.1.21", sent.get().host());
                assertEquals("b".repeat(64), sent.get().verifiedFingerprint());
                assertEquals("12345678", submittedCode.get());
                assertTrue(code.getText().isEmpty(), "The pairing code must not linger after submission.");
                assertTrue(connect.isDisabled());
            });
        } finally {
            onFx(() -> { if (window.get() != null) window.get().close(); });
            worker.shutdown();
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
        }
        var remembered = new RemoteSetupStore(directory).load();
        assertNotNull(remembered.client());
        assertEquals("192.168.1.21", remembered.client().host());
        String persisted = java.nio.file.Files.readString(directory.resolve("setup.properties"));
        assertFalse(persisted.contains("b".repeat(64)), "Fingerprint trust must not be remembered.");
        assertFalse(persisted.contains("12345678"), "Pairing codes must not be remembered.");
    }

    private static void onFx(CheckedAction action) throws Exception {
        var task = new FutureTask<Void>(() -> { action.run(); return null; });
        Platform.runLater(task);
        task.get(15, TimeUnit.SECONDS);
    }

    private interface CheckedAction { void run() throws Exception; }
}
