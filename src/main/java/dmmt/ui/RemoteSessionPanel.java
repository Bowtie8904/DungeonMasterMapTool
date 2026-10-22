package dmmt.ui;

import dmmt.remote.RemoteConnectionController;
import dmmt.remote.RemoteConnectionDetails;
import dmmt.remote.RemoteHostingController;
import dmmt.remote.RemoteSessionStorage;
import dmmt.remote.RemoteDiscovery;
import dmmt.remote.RemoteSetupStore;
import dmmt.remote.RemoteNetworkAccess;
import dmmt.remote.WindowsRemoteFirewall;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.Duration;
import javafx.util.StringConverter;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Sidebar tab for hosting, connecting and monitoring a LAN remote-control session. */
public final class RemoteSessionPanel extends VBox {
    public interface Actions {
        RemoteHostingController host();
        RemoteConnectionController client();
        CompletableFuture<RemoteHostingController.Details> start(RemoteHostingController.Configuration configuration);
        void connect(RemoteConnectionController.Configuration configuration, String code);
        void disconnect();
    }

    private final Actions actions;
    private final RemoteSessionStorage storage;
    private final Executor worker;
    private final RemoteSetupStore setupStore;
    private final RemoteNetworkAccess networkAccess;
    private final Label networkStatus = new Label();
    private final Button checkAccess = new Button("Check access");
    private final Button allowAccess = new Button("Allow private LAN access...");
    private final Button removeAccess = new Button("Remove permission...");
    private final javafx.beans.property.BooleanProperty networkBusy =
            new javafx.beans.property.SimpleBooleanProperty();
    private long networkGeneration;
    private CompletableFuture<Void> setupIO = CompletableFuture.completedFuture(null);
    private RemoteSetupStore.Setup rememberedSetup;
    private long hostInputVersion;
    private long clientInputVersion;
    private final Label savedSetupStatus = new Label();
    private final TextField hostName = field("Table session", "remote-host-name");
    private final ComboBox<InetAddress> bindAddress = new ComboBox<>();
    private final Spinner<Integer> hostControl = port(7072);
    private final Spinner<Integer> hostAssets = port(7073);
    private final TextField host = field("", "remote-client-host");
    private final TextField sessionName = field("Table session", "remote-client-session");
    private final TextField clientName = field("Remote DM", "remote-client-name");
    private final Spinner<Integer> clientControl = port(7072);
    private final Spinner<Integer> clientAssets = port(7073);
    private final TextField fingerprint = field("", "remote-client-fingerprint");
    private final PasswordField code = new PasswordField();
    private final CheckBox verified = new CheckBox("I verified this fingerprint using details from the intended host.");
    private final Label status = new Label();
    private final Label problem = new Label();
    private final Label identity = new Label();
    private final Label pairing = new Label();
    private final Label approval = new Label();
    private final Label discoveryStatus = new Label();
    private final Label hostDiscoveryStatus = new Label();
    private final ComboBox<RemoteDiscovery.Host> discoveredHosts = new ComboBox<>();
    private final javafx.beans.property.ObjectProperty<RemoteDiscovery.Host> selectedDiscovery =
            new javafx.beans.property.SimpleObjectProperty<>();
    private final Button discover = new Button("Discover LAN hosts");
    private RemoteDiscovery.Search discoverySearch;
    private boolean selectingDiscovery;
    private final Button start = new Button("Start hosting");
    private final Button copy = new Button("Copy connection details");
    private final Button regenerate = new Button("Regenerate pairing code");
    private final Button stop = new Button("Stop hosting");
    private final Button approve = new Button("Allow once");
    private final Button approveAlways = new Button("Always allow");
    private final Button block = new Button("Block");
    private final Button reject = new Button("Deny");
    private final javafx.scene.control.ListView<dmmt.remote.RemoteClientTrustStore.Entry> remembered =
            new javafx.scene.control.ListView<>();
    private final Button forget = new Button("Remove selected");
    private final Button revoke = new Button("Revoke client");
    private final Button connect = new Button("Request control");
    private final Button reconnect = new Button("Reconnect");
    private final Button disconnect = new Button("Disconnect");
    private final Timeline ticker;
    private final ToggleButton hostMode = new ToggleButton("Host");
    private final ToggleButton connectMode = new ToggleButton("Connect");
    private final HBox modeSwitch = new HBox(hostMode, connectMode);
    private final VBox hostIdle = new VBox(14);
    private final VBox hostLive = new VBox(14);
    private final VBox clientIdle = new VBox(14);
    private final VBox clientLive = new VBox(14);
    private final VBox approvalCard = new VBox(12);
    private final Region statusDot = new Region();
    private final Label statusTitle = new Label("Not connected");
    private final ProgressBar activityBar = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
    private boolean addressesLoaded;
    private boolean disposed;
    private VBox hostPane;
    private VBox clientPane;

    public RemoteSessionPanel(Actions actions, RemoteSessionStorage storage, Executor worker) {
        this(actions, storage, worker, new WindowsRemoteFirewall());
    }

    public RemoteSessionPanel(Actions actions, RemoteSessionStorage storage, Executor worker,
                              RemoteNetworkAccess networkAccess) {
        super(10);
        this.actions = java.util.Objects.requireNonNull(actions);
        this.storage = java.util.Objects.requireNonNull(storage);
        this.worker = java.util.Objects.requireNonNull(worker);
        this.networkAccess = java.util.Objects.requireNonNull(networkAccess);
        setupStore = new RemoteSetupStore(storage.directory());
        bindAddress.setId("remote-host-interface");
        clientControl.setId("remote-client-control-port");
        clientAssets.setId("remote-client-artwork-port");
        bindAddress.setConverter(new StringConverter<>() {
            @Override public String toString(InetAddress address) {
                return address == null ? "" : address.getHostAddress() + (address.isLoopbackAddress() ? " (this PC only)" : "");
            }
            @Override public InetAddress fromString(String text) {
                throw new UnsupportedOperationException("Select a listed local interface.");
            }
        });
        var hostPorts = form();
        hostPorts.addRow(0, new Label("Control port"), hostControl);
        hostPorts.addRow(1, new Label("Artwork port"), hostAssets);
        var hostManual = new TitledPane("Connection details",
                new VBox(8, copy, identity, pairing, regenerate));
        hostManual.setExpanded(false);
        var hostWarning = new Label("Approving grants full workspace control, settings changes and file transfer. "
                + "Client names are unauthenticated.");
        hostWarning.setWrapText(true);
        hostWarning.getStyleClass().add("muted");
        networkStatus.setId("remote-network-status");
        networkStatus.setWrapText(true);
        networkStatus.setText(networkAccess.supported()
                ? "Start hosting checks private-LAN permission. Windows may ask for administrator approval once. "
                    + "Permission persists for this app location and ports; no commands need to be copied."
                : "Private-LAN permission is only available on Windows.");
        checkAccess.setId("remote-network-check");
        allowAccess.setId("remote-network-allow");
        removeAccess.setId("remote-network-remove");
        networkStatus.getStyleClass().add("muted");
        var networkPane = new TitledPane("Advanced: ports and private LAN access",
                new VBox(8, hostPorts, networkStatus, new FlowPane(6, 6, checkAccess, allowAccess, removeAccess)));
        networkPane.setExpanded(false);
        approvalCard.getStyleClass().addAll("session-card", "attention");
        approvalCard.getChildren().setAll(new Label("Control request"), approval,
                new FlowPane(8, 8, approve, approveAlways, reject, block), hostWarning);
        approvalCard.getChildren().getFirst().getStyleClass().add("session-title");
        approve.getStyleClass().add("primary");
        approve.setId("remote-approve-once");
        approveAlways.setId("remote-approve-always");
        reject.setId("remote-reject");
        block.setId("remote-block");
        approve.setTooltip(new javafx.scene.control.Tooltip("Grant control for this session only."));
        approveAlways.setTooltip(new javafx.scene.control.Tooltip(
                "Grant control now and automatically accept this client name and address in the future."));
        reject.setTooltip(new javafx.scene.control.Tooltip("Refuse this request; the client may ask again."));
        block.setTooltip(new javafx.scene.control.Tooltip(
                "Refuse this request and silently refuse future requests from this client name and address."));
        remembered.setId("remote-remembered-clients");
        remembered.setPrefHeight(110);
        remembered.setPlaceholder(new Label("No remembered clients."));
        remembered.setCellFactory(list -> new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(dmmt.remote.RemoteClientTrustStore.Entry entry, boolean empty) {
                super.updateItem(entry, empty);
                setText(empty || entry == null ? null : (entry.decision()
                        == dmmt.remote.RemoteClientTrustStore.Decision.ALLOWED ? "Always allowed: " : "Blocked: ")
                        + entry.label());
            }
        });
        forget.setId("remote-forget-client");
        forget.disableProperty().bind(remembered.getSelectionModel().selectedItemProperty().isNull());
        var rememberedNote = new Label("Clients are matched by their unauthenticated name and IP address. "
                + "Only allow clients on a network you trust.");
        rememberedNote.setWrapText(true);
        rememberedNote.getStyleClass().add("muted");
        var rememberedPane = new TitledPane("Allowed and blocked clients",
                new VBox(10, rememberedNote, remembered, forget));
        rememberedPane.setExpanded(false);
        hostIdle.getChildren().setAll(labeled("Session name", hostName), labeled("Network interface", bindAddress),
                start, networkPane);
        hostLive.getChildren().setAll(hostDiscoveryStatus, approvalCard, hostManual, rememberedPane,
                new HBox(8, revoke, stop));
        hostDiscoveryStatus.getStyleClass().add("muted");
        hostDiscoveryStatus.setWrapText(true);
        var hostPane = new VBox(14, hostIdle, hostLive);
        var clientForm = form();
        clientForm.addRow(0, new Label("Session"), sessionName);
        clientForm.addRow(1, new Label("Host"), host);
        clientForm.addRow(2, new Label("Control port"), clientControl);
        clientForm.addRow(3, new Label("Artwork port"), clientAssets);
        clientForm.addRow(4, new Label("Fingerprint"), fingerprint);
        clientForm.addRow(5, new Label("Pairing code"), code);
        Button paste = new Button("Paste host connection details");
        discover.setText("Find hosts on the LAN");
        discover.setMaxWidth(Double.MAX_VALUE);
        discoveredHosts.setMaxWidth(Double.MAX_VALUE);
        discover.setId("remote-discover");
        discoveredHosts.setId("remote-discovered-hosts");
        discoveryStatus.setId("remote-discovery-status");
        discoveryStatus.setWrapText(true);
        discoveryStatus.getStyleClass().add("muted");
        discoveryStatus.setText("Start hosting on the other PC, then find it here. "
                + "Discovered hosts are unverified; the host must approve your request.");
        discoveredHosts.setPromptText("Select a host");
        discoveredHosts.setConverter(new StringConverter<>() {
            @Override public String toString(RemoteDiscovery.Host found) {
                return found == null ? "" : found.name() + " - " + found.address().getHostAddress() + ":" + found.controlPort();
            }
            @Override public RemoteDiscovery.Host fromString(String text) {
                throw new UnsupportedOperationException("Select a discovered host.");
            }
        });
        discoveredHosts.valueProperty().addListener((observable, before, found) -> {
            if (found != null) selectDiscoveredHost(found);
            else selectedDiscovery.set(null);
        });
        discover.setOnAction(event -> act(this::discoverHosts));
        var manual = new TitledPane("Manual verified connection",
                new VBox(10, paste, clientForm, verified));
        manual.setId("remote-client-manual");
        manual.setExpanded(false);
        connect.getStyleClass().add("primary");
        connect.setMaxWidth(Double.MAX_VALUE);
        start.getStyleClass().add("primary");
        start.setMaxWidth(Double.MAX_VALUE);
        clientIdle.getChildren().setAll(labeled("Your name", clientName), discover, discoveredHosts, discoveryStatus,
                connect, manual);
        clientLive.getChildren().setAll(new HBox(8, reconnect, disconnect));
        var clientPane = new VBox(14, clientIdle, clientLive);
        var modes = new ToggleGroup();
        hostMode.setToggleGroup(modes);
        connectMode.setToggleGroup(modes);
        hostMode.setId("remote-mode-host");
        connectMode.setId("remote-mode-connect");
        hostMode.setSelected(true);
        modeSwitch.getStyleClass().add("session-mode");
        hostMode.setMaxWidth(Double.MAX_VALUE);
        connectMode.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(hostMode, Priority.ALWAYS);
        HBox.setHgrow(connectMode, Priority.ALWAYS);
        modes.selectedToggleProperty().addListener((observable, before, now) -> {
            if (now == null) before.setSelected(true);
            else refresh();
        });
        statusDot.getStyleClass().add("session-status-dot");
        statusTitle.getStyleClass().add("session-title");
        status.setWrapText(true);
        status.getStyleClass().add("muted");
        problem.setWrapText(true);
        problem.getStyleClass().add("error-label");
        identity.setWrapText(true);
        identity.setStyle("-fx-font-family: monospace;");
        approval.setWrapText(true);
        savedSetupStatus.setWrapText(true);
        savedSetupStatus.getStyleClass().add("muted");
        savedSetupStatus.setId("remote-saved-setup-status");
        activityBar.setMaxWidth(Double.MAX_VALUE);
        var statusHead = new HBox(8, statusDot, statusTitle);
        statusHead.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        var statusCard = new VBox(6, statusHead, status, activityBar, problem);
        statusCard.getStyleClass().add("session-card");
        getStyleClass().add("session-panel");
        getChildren().setAll(statusCard, modeSwitch, hostPane, clientPane, savedSetupStatus);
        setMinWidth(0);
        getProperties().put("remote.lifecycle", true);
        this.hostPane = hostPane;
        this.clientPane = clientPane;
        code.setId("remote-client-code");
        verified.setId("remote-client-verified");
        connect.setId("remote-client-connect");
        status.setId("remote-status");
        start.setId("remote-host-start");
        for (var input : List.of(fingerprint, host, clientControl.getEditor(), clientAssets.getEditor())) {
            input.textProperty().addListener((observable, before, after) -> clearDiscoverySelection());
        }
        clientControl.valueProperty().addListener((observable, before, after) -> clearDiscoverySelection());
        clientAssets.valueProperty().addListener((observable, before, after) -> clearDiscoverySelection());
        connect.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(
                () -> networkBusy.get() || !discoveredSelectionMatches() && (!verified.isSelected()
                        || !fingerprint.getText().strip().matches("[a-fA-F0-9]{64}")
                        || host.getText().isBlank() || code.getText().length() != 8),
                selectedDiscovery, verified.selectedProperty(), fingerprint.textProperty(), host.textProperty(),
                code.textProperty(), clientControl.getEditor().textProperty(), clientAssets.getEditor().textProperty(),
                clientControl.valueProperty(), clientAssets.valueProperty(), networkBusy));
        connect.textProperty().bind(javafx.beans.binding.Bindings.when(
                        selectedDiscovery.isNotNull().or(manual.expandedProperty().not()))
                .then("Request control").otherwise("Connect"));
        start.setOnAction(event -> act(() -> prepareHosting(hostConfiguration())));
        checkAccess.setOnAction(event -> act(() -> checkNetwork(hostConfiguration(), false)));
        allowAccess.setOnAction(event -> act(() -> requestPermission(hostConfiguration(), false)));
        removeAccess.setOnAction(event -> act(() -> {
            var confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                    "Remove only DMMT's private-LAN rules for this app location? "
                            + "Other firewall rules are untouched. Future hosting may need permission again.",
                    ButtonType.OK, ButtonType.CANCEL);
            confirmation.initOwner(owner());
            confirmation.setTitle("Remove DMMT network permission");
            if (confirmation.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
                networkTask(() -> { networkAccess.remove(); return null; },
                        ignored -> networkStatus.setText("DMMT's managed permission was removed. Other firewall rules were not changed."));
            }
        }));
        copy.setOnAction(event -> act(() -> {
            var details = RemoteConnectionDetails.from(actions.host().status().details());
            var clipboard = new ClipboardContent();
            clipboard.putString(details.format());
            Clipboard.getSystemClipboard().setContent(clipboard);
            status.setText("Connection details copied. Share the pairing code separately.");
        }));
        paste.setOnAction(event -> act(() -> paste(Clipboard.getSystemClipboard().getString())));
        stop.setOnAction(event -> act(() -> observeStop(actions.host().stop())));
        regenerate.setOnAction(event -> act(() -> actions.host().regenerateCode()));
        approve.setOnAction(event -> act(() -> actions.host().approve(actions.host().status().access().pending().id())));
        reject.setOnAction(event -> act(() -> actions.host().reject(actions.host().status().access().pending().id())));
        approveAlways.setOnAction(event -> act(() ->
                actions.host().approveAlways(actions.host().status().access().pending().id())));
        block.setOnAction(event -> act(() -> actions.host().block(actions.host().status().access().pending().id())));
        forget.setOnAction(event -> act(() -> {
            var entry = remembered.getSelectionModel().getSelectedItem();
            if (entry != null) actions.host().forgetClient(entry);
        }));
        revoke.setOnAction(event -> act(() -> actions.host().revoke()));
        connect.setOnAction(event -> act(() -> {
            if (clientActive() || hostActive()) throw new IllegalStateException("Stop or disconnect the current session first.");
            boolean discovered = discoveredSelectionMatches();
            var configuration = new RemoteConnectionController.Configuration(host.getText(), selectedPort(clientControl),
                    selectedPort(clientAssets), fingerprint.getText().strip(), clientName.getText(), sessionName.getText(),
                    10_000, storage);
            if (!discovered && (!verified.isSelected() || code.getText().length() != 8)) {
                throw new IllegalStateException("Verify the updated host identity and connection details before connecting.");
            }
            cancelDiscovery();
            actions.connect(configuration, discovered ? null : code.getText());
            remember(null, new RemoteSetupStore.Client(configuration.host(), configuration.sessionName(),
                    configuration.clientName(), configuration.controlPort(), configuration.assetPort()));
            code.clear();
        }));
        reconnect.setOnAction(event -> act(() -> actions.client().reconnect()));
        disconnect.setOnAction(event -> act(actions::disconnect));
        ticker = new Timeline(new KeyFrame(Duration.millis(200), event -> refresh()));
        ticker.setCycleCount(Timeline.INDEFINITE);
        sceneProperty().addListener((observable, before, scene) -> {
            if (scene == null) ticker.stop();
            else { refresh(); ticker.play(); }
        });
        for (var field : List.of(hostName, hostControl.getEditor(), hostAssets.getEditor())) {
            field.textProperty().addListener((observable, before, after) -> hostInputVersion++);
        }
        bindAddress.valueProperty().addListener((observable, before, after) -> hostInputVersion++);
        for (var field : List.of(host, sessionName, clientName, clientControl.getEditor(), clientAssets.getEditor(),
                fingerprint, code)) {
            field.textProperty().addListener((observable, before, after) -> clientInputVersion++);
        }
        verified.selectedProperty().addListener((observable, before, after) -> clientInputVersion++);
        loadInterfaces();
    }

    public void show() {
        refresh();
    }

    /** Cancels transient work (discovery, pending network prompts) and clears secrets. */
    public void close() {
        networkGeneration++;
        cancelDiscovery();
        code.clear();
        clearDiscoverySelection();
    }

    public void dispose() {
        disposed = true;
        close();
        ticker.stop();
    }

    private Window owner() {
        return getScene() == null ? null : getScene().getWindow();
    }

    private boolean live() {
        return !disposed && getScene() != null;
    }

    private static VBox labeled(String text, javafx.scene.Node node) {
        var label = new Label(text);
        label.getStyleClass().add("caption");
        return new VBox(4, label, node);
    }

    private RemoteHostingController.Configuration hostConfiguration() {
        return new RemoteHostingController.Configuration(hostName.getText(), bindAddress.getValue(),
                selectedPort(hostControl), selectedPort(hostAssets), 10_000, storage);
    }

    private void prepareHosting(RemoteHostingController.Configuration configuration) {
        if (networkAccess.supported() && !configuration.bindAddress().isLoopbackAddress()) {
            checkNetwork(configuration, true);
        } else {
            startHosting(configuration);
        }
    }

    private void startHosting(RemoteHostingController.Configuration configuration) {
        var started = actions.start(configuration);
        remember(new RemoteSetupStore.Host(configuration.name(), configuration.bindAddress().getHostAddress(),
                configuration.controlPort(), configuration.assetPort()), null);
        started.whenComplete((details, failure) -> Platform.runLater(() -> {
            if (failure != null) problem.setText("Hosting could not start. Check the local interface, ports and storage.");
            refresh();
        }));
        refresh();
    }

    private void checkNetwork(RemoteHostingController.Configuration configuration, boolean startAfter) {
        networkTask(() -> networkAccess.check(configuration), result -> {
            networkStatus.setText("Network profile: " + result.profile() + ". "
                    + (result.configured() ? "DMMT private-LAN rules are configured. "
                    : "DMMT private-LAN permission has not been configured for this app location and ports. ")
                    + (result.privateNetwork() ? "" : "Private-only rules do not apply to this network. "
                    + "Only mark a network Private in Windows Settings if it is trusted. ")
                    + "This check cannot prove reachability or detect every firewall/security policy.");
            if (startAfter) {
                if (result.configured() && result.privateNetwork()) startHosting(configuration);
                else requestPermission(configuration, true);
            }
        }, () -> {
            if (!startAfter) return;
            var unchanged = new ButtonType("Host without changes", ButtonBar.ButtonData.OK_DONE);
            var confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                    networkStatus.getText() + "\nYou can still start hosting using existing network permissions.",
                    unchanged, ButtonType.CANCEL);
            confirmation.initOwner(owner());
            confirmation.setTitle("Windows network-access check failed");
            if (confirmation.showAndWait().orElse(ButtonType.CANCEL) == unchanged) startHosting(configuration);
        });
    }

    private void requestPermission(RemoteHostingController.Configuration configuration, boolean startAfter) {
        var allow = new ButtonType("Allow private LAN access", ButtonBar.ButtonData.OK_DONE);
        var unchanged = new ButtonType("Host without changes", ButtonBar.ButtonData.OTHER);
        var confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.initOwner(owner());
        confirmation.setTitle("Allow DMMT on your private LAN");
        confirmation.setHeaderText("Windows administrator approval is required to configure network access");
        confirmation.setContentText("Allow only this DMMT executable, inbound TCP " + configuration.controlPort()
                + " and " + configuration.assetPort() + " plus discovery UDP 7074, from the local subnet "
                + "on Private networks. No Public-network access, router changes or firewall disabling. "
                + "Permission persists across restarts and same-location updates; use Remove permission to undo it. "
                + "If this network is not Private, these rules will not apply. No network profile will be changed.");
        confirmation.getButtonTypes().setAll(startAfter ? List.of(allow, unchanged, ButtonType.CANCEL)
                : List.of(allow, ButtonType.CANCEL));
        var choice = confirmation.showAndWait().orElse(ButtonType.CANCEL);
        if (choice == allow) {
            networkTask(() -> { networkAccess.allow(configuration); return null; }, ignored -> {
                networkStatus.setText("DMMT private-LAN permission configured. It applies only on Private networks. "
                        + "Other firewalls or network isolation may still prevent connections.");
                if (startAfter) startHosting(configuration);
            });
        } else if (choice == unchanged) {
            startHosting(configuration);
        }
    }

    private <T> void networkTask(java.util.concurrent.Callable<T> task, java.util.function.Consumer<T> success) {
        networkTask(task, success, () -> { });
    }

    private <T> void networkTask(java.util.concurrent.Callable<T> task, java.util.function.Consumer<T> success,
                                 Runnable failed) {
        if (networkBusy.get()) throw new IllegalStateException("A Windows network-access request is already running.");
        networkBusy.set(true);
        long generation = networkGeneration;
        networkStatus.setText("Checking/updating Windows network access. Complete any Windows permission prompt.");
        refresh();
        try {
            CompletableFuture.supplyAsync(() -> {
                try { return task.call(); }
                catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
            }, worker).whenComplete((result, failure) -> Platform.runLater(() -> {
                networkBusy.set(false);
                if (failure != null) {
                    var cause = dmmt.remote.RemoteFailure.unwrap(failure);
                    System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,
                            "Windows network-access request failed.", cause);
                    networkStatus.setText(cause.getMessage() + " Hosting was not started by this request. "
                            + "Use Check access to retry; you can also host without changing rules.");
                    if (generation == networkGeneration && live()) act(failed);
                } else if (generation == networkGeneration && live()) {
                    act(() -> success.accept(result));
                } else {
                    networkStatus.setText("The Windows network-access request finished while setup was closed. "
                            + "Use Check access to see the current permission. Hosting was not started.");
                }
                refresh();
            }));
        } catch (java.util.concurrent.RejectedExecutionException unavailable) {
            networkBusy.set(false);
            networkStatus.setText("The network-access worker is unavailable. No permission request was started.");
            throw new IllegalStateException("The network-access worker is unavailable.", unavailable);
        }
    }

    private void paste(String text) {
        var details = RemoteConnectionDetails.parse(text);
        clearDiscoverySelection();
        sessionName.setText(details.name());
        host.setText(details.host());
        clientControl.getValueFactory().setValue(details.controlPort());
        clientAssets.getValueFactory().setValue(details.assetPort());
        fingerprint.setText(details.fingerprint());
        verified.setSelected(false);
        status.setText("Details pasted. Verify the fingerprint with the intended host before connecting.");
    }

    private void selectDiscoveredHost(RemoteDiscovery.Host found) {
        selectingDiscovery = true;
        selectedDiscovery.set(null);
        try {
            sessionName.setText(found.name());
            host.setText(found.address().getHostAddress());
            clientControl.getValueFactory().setValue(found.controlPort());
            clientAssets.getValueFactory().setValue(found.assetPort());
            fingerprint.setText(found.fingerprint());
            code.clear();
            verified.setSelected(false);
        } finally {
            selectingDiscovery = false;
        }
        selectedDiscovery.set(found);
        discoveryStatus.setText("Unverified LAN host selected. Request control, then have A approve your expected request. "
                + "TLS must match this exact discovered certificate pin, but discovery is not proof of identity. "
                + "If the host session changes, rediscover it.");
    }

    private void clearDiscoverySelection() {
        if (selectingDiscovery) return;
        selectedDiscovery.set(null);
        discoveredHosts.setValue(null);
        verified.setSelected(false);
    }

    private boolean discoveredSelectionMatches() {
        var found = selectedDiscovery.get();
        return found != null && host.getText().equals(found.address().getHostAddress())
                && clientControl.getEditor().getText().equals(Integer.toString(found.controlPort()))
                && clientAssets.getEditor().getText().equals(Integer.toString(found.assetPort()))
                && fingerprint.getText().equals(found.fingerprint());
    }

    private void discoverHosts() {
        if (hostActive() || clientActive()) throw new IllegalStateException("Stop or disconnect before searching.");
        cancelDiscovery();
        var search = new RemoteDiscovery.Search();
        discoverySearch = search;
        discoveredHosts.getItems().clear();
        discoveryStatus.setText("Searching the IPv4 LAN...");
        discover.setDisable(true);
        try {
            CompletableFuture.supplyAsync(() -> {
                try { return search.discover(); }
                catch (java.io.IOException failure) { throw new java.util.concurrent.CompletionException(failure); }
            }, worker).whenComplete((hosts, failure) -> Platform.runLater(() -> {
                if (discoverySearch != search) return;
                discoverySearch = null;
                search.close();
                discoveredHosts.getItems().setAll(failure == null ? hosts : List.of());
                if (failure == null && !hosts.isEmpty()) discoveredHosts.setValue(hosts.get(0));
                discoveryStatus.setText(failure != null
                        ? "LAN search failed. Check multicast/UDP 7074 or enter host details manually."
                        : hosts.isEmpty() ? "No hosts found. Start hosting on A and use its Private LAN access > Check access. "
                            + "Both PCs must share a trusted LAN; VPNs, guest Wi-Fi and security software can block discovery."
                        : "Found " + hosts.size() + " unverified host(s); the first is selected. Pick another if needed, then Request control.");
                if (failure != null) System.getLogger(RemoteSessionPanel.class.getName())
                        .log(System.Logger.Level.WARNING, "LAN host search failed.", failure);
                refresh();
            }));
        } catch (java.util.concurrent.RejectedExecutionException unavailable) {
            cancelDiscovery();
            throw new IllegalStateException("The discovery worker is unavailable; enter host details manually.", unavailable);
        }
    }

    private void cancelDiscovery() {
        if (discoverySearch != null) {
            discoverySearch.close();
            discoverySearch = null;
            discoveryStatus.setText("LAN search cancelled. Manual connection remains available.");
        }
    }

    private void refresh() {
        boolean hosting = hostActive();
        boolean connecting = clientActive();
        if ((hosting || connecting) && discoverySearch != null) cancelDiscovery();
        discover.setDisable(hosting || connecting || networkBusy.get() || discoverySearch != null);
        discoveredHosts.setDisable(hosting || connecting || discoverySearch != null);
        hostDiscoveryStatus.setText(hosting ? actions.host().discoveryMessage() : "");
        start.setDisable(!addressesLoaded || hosting || connecting || networkBusy.get() || bindAddress.getValue() == null);
        checkAccess.setDisable(!networkAccess.supported() || networkBusy.get() || bindAddress.getValue() == null);
        allowAccess.setDisable(!networkAccess.supported() || networkBusy.get() || connecting || bindAddress.getValue() == null);
        removeAccess.setDisable(!networkAccess.supported() || networkBusy.get() || hosting || connecting);
        hostName.setDisable(networkBusy.get() || hosting);
        bindAddress.setDisable(networkBusy.get() || hosting);
        hostControl.setDisable(networkBusy.get() || hosting);
        hostAssets.setDisable(networkBusy.get() || hosting);
        copy.setDisable(!hosting || actions.host().status().details() == null);
        stop.setDisable(!hosting);
        regenerate.setDisable(!hosting);
        approve.setDisable(true);
        approveAlways.setDisable(true);
        block.setDisable(true);
        reject.setDisable(true);
        revoke.setDisable(true);
        disconnect.setDisable(!connecting);
        reconnect.setDisable(!connecting || !actions.client().status().canReconnect());
        if (hosting) {
            var state = actions.host().status();
            status.setText(state.message());
            identity.setText(state.details() == null ? "" : "SHA-256: " + state.details().fingerprint()
                    + "\nControl " + state.details().controlPort() + " / artwork " + state.details().assetPort());
            var access = state.access();
            boolean approvedClient = access != null && access.client() != null;
            revoke.setDisable(!approvedClient);
            regenerate.setDisable(access == null || approvedClient);
            pairing.setText(access == null || approvedClient ? "" : "Pairing code: "
                    + actions.host().pairingCode().value() + " (expires " + actions.host().pairingCode().expiresAt() + ")");
            var pending = access == null ? null : access.pending();
            approve.setDisable(pending == null);
            approveAlways.setDisable(pending == null);
            block.setDisable(pending == null);
            reject.setDisable(pending == null);
            var known = actions.host().rememberedClients();
            if (!known.equals(remembered.getItems())) remembered.getItems().setAll(known);
            approval.setText(pending == null ? "" : "Unverified client name: " + pending.client().name() + " at "
                    + pending.client().address() + ". Allow only an expected request. "
                    + "Access grants full workspace/settings control and file transfer.");
        } else {
            pairing.setText("");
            approval.setText("");
            identity.setText("");
            if (connecting || actions.client() != null) status.setText(actions.client().status().message());
            else if (actions.host() != null) status.setText(actions.host().status().message());
        }
        present(hosting, connecting);
    }

    private void present(boolean hosting, boolean connecting) {
        boolean active = hosting || connecting;
        if (active) (hosting ? hostMode : connectMode).setSelected(true);
        modeSwitch.setDisable(active);
        boolean hostView = hostMode.isSelected();
        show(modeSwitch, !active);
        show(hostPane, hostView);
        show(clientPane, !hostView);
        show(hostIdle, !hosting);
        show(hostLive, hosting);
        show(clientIdle, !connecting);
        show(clientLive, connecting);
        var pending = hosting && actions.host().status().access() != null
                && actions.host().status().access().pending() != null;
        show(approvalCard, pending);
        show(problem, !problem.getText().isBlank());
        String dot = "";
        String title = "Not connected";
        boolean busy = networkBusy.get();
        if (hosting) {
            var phase = actions.host().status().phase();
            title = switch (phase) {
                case WAITING -> "Hosting - waiting for a client";
                case AWAITING_APPROVAL -> "Approval needed";
                case CONNECTED -> "Client connected";
                case DISCONNECTED -> "Client disconnected";
                case ERROR -> "Hosting problem";
                default -> "Hosting";
            };
            dot = phase == RemoteHostingController.Phase.ERROR ? "error"
                    : phase == RemoteHostingController.Phase.CONNECTED ? "live" : "busy";
            busy |= phase == RemoteHostingController.Phase.STARTING || phase == RemoteHostingController.Phase.STOPPING;
        } else if (connecting) {
            var phase = actions.client().status().phase();
            title = switch (phase) {
                case PREPARING, CONNECTING -> "Connecting...";
                case AWAITING_APPROVAL -> "Waiting for host approval";
                case SYNCHRONIZING -> "Synchronizing workspace...";
                case CONNECTED -> "Remote control active";
                case PAUSED -> "Connection paused";
                case ERROR -> "Connection problem";
                default -> "Remote control";
            };
            dot = phase == RemoteConnectionController.Phase.ERROR || phase == RemoteConnectionController.Phase.PAUSED
                    ? "error" : phase == RemoteConnectionController.Phase.CONNECTED ? "live" : "busy";
            busy |= phase != RemoteConnectionController.Phase.CONNECTED
                    && phase != RemoteConnectionController.Phase.PAUSED
                    && phase != RemoteConnectionController.Phase.ERROR;
        }
        statusTitle.setText(title);
        statusDot.getStyleClass().removeAll("live", "busy", "error");
        if (!dot.isEmpty()) statusDot.getStyleClass().add(dot);
        show(activityBar, busy);
    }

    private static void show(javafx.scene.Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private void loadInterfaces() {
        start.setDisable(true);
        long hostVersion = hostInputVersion;
        long clientVersion = clientInputVersion;
        var loading = CompletableFuture.supplyAsync(() -> {
            String setupError = null;
            try {
                rememberedSetup = setupStore.load();
            } catch (java.io.IOException failure) {
                setupError = "Saved setup could not be read. Enter connection details manually; nothing was connected.";
                System.getLogger(RemoteSessionPanel.class.getName())
                        .log(System.Logger.Level.WARNING, "Could not read local remote setup.", failure);
            }
            try {
                List<InetAddress> addresses = new ArrayList<>();
                for (var adapter : java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (adapter.isUp()) {
                        for (var address : java.util.Collections.list(adapter.getInetAddresses())) {
                            if (!address.isMulticastAddress() && !address.isLinkLocalAddress()) addresses.add(address);
                        }
                    }
                }
                addresses.sort(Comparator.comparing(InetAddress::isLoopbackAddress)
                        .thenComparing(address -> !(address instanceof java.net.Inet4Address))
                        .thenComparing(InetAddress::getHostAddress));
                return new SetupInputs(addresses, rememberedSetup, setupError);
            } catch (java.net.SocketException failure) {
                throw new java.util.concurrent.CompletionException(failure);
            }
        }, worker);
        setupIO = loading.handle((ignored, failure) -> null);
        loading.whenComplete((inputs, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                problem.setText("Could not enumerate local interfaces. Check the network adapter configuration.");
            } else {
                addressesLoaded = true;
                bindAddress.getItems().setAll(inputs.addresses());
                var saved = inputs.setup();
                if (hostVersion == hostInputVersion && saved != null && saved.host() != null) {
                    var configuration = saved.host();
                    hostName.setText(configuration.name());
                    hostControl.getValueFactory().setValue(configuration.controlPort());
                    hostAssets.getValueFactory().setValue(configuration.assetPort());
                    var address = inputs.addresses().stream().filter(candidate ->
                            candidate.getHostAddress().equals(configuration.address())).findFirst().orElse(null);
                    bindAddress.setValue(address);
                    if (address == null) savedSetupStatus.setText(
                            "The saved host interface is unavailable. Select a local interface before hosting.");
                } else if (bindAddress.getValue() == null) {
                    bindAddress.getSelectionModel().selectFirst();
                }
                if (clientVersion == clientInputVersion && saved != null && saved.client() != null) {
                    var configuration = saved.client();
                    host.setText(configuration.host());
                    sessionName.setText(configuration.sessionName());
                    clientName.setText(configuration.name());
                    clientControl.getValueFactory().setValue(configuration.controlPort());
                    clientAssets.getValueFactory().setValue(configuration.assetPort());
                }
                if (inputs.error() != null) savedSetupStatus.setText(inputs.error());
            }
            refresh();
        }));
    }

    private record SetupInputs(List<InetAddress> addresses, RemoteSetupStore.Setup setup, String error) { }

    private void remember(RemoteSetupStore.Host hostConfiguration, RemoteSetupStore.Client clientConfiguration) {
        try {
            setupIO = setupIO.thenRunAsync(() -> {
                var next = new RemoteSetupStore.Setup(
                        hostConfiguration != null ? hostConfiguration : rememberedSetup == null ? null : rememberedSetup.host(),
                        clientConfiguration != null ? clientConfiguration : rememberedSetup == null ? null : rememberedSetup.client());
                try {
                    setupStore.save(next);
                    rememberedSetup = next;
                } catch (java.io.IOException failure) {
                    throw new java.util.concurrent.CompletionException(failure);
                }
            }, worker).handle((ignored, failure) -> {
                if (failure != null) {
                    System.getLogger(RemoteSessionPanel.class.getName())
                            .log(System.Logger.Level.WARNING, "Could not remember remote setup.", failure);
                    Platform.runLater(() -> savedSetupStatus.setText(
                            "The session action was submitted, but setup could not be remembered. See application logs."));
                } else {
                    Platform.runLater(() -> savedSetupStatus.setText(
                            "Setup remembered on this PC. Identity verification and access permissions were not saved."));
                }
                return null;
            });
        } catch (java.util.concurrent.RejectedExecutionException unavailable) {
            savedSetupStatus.setText("The session action was submitted, but the setup storage worker is unavailable.");
            System.getLogger(RemoteSessionPanel.class.getName())
                    .log(System.Logger.Level.WARNING, "Could not schedule remote setup persistence.", unavailable);
        }
    }

    private boolean hostActive() { return actions.host() != null && actions.host().active(); }
    private boolean clientActive() { return actions.client() != null && actions.client().active(); }

    private void observeStop(CompletableFuture<Void> completion) {
        completion.whenComplete((unused, failure) -> Platform.runLater(() -> {
            if (failure != null) problem.setText("Session cleanup failed; see application logs.");
            refresh();
        }));
    }

    private void act(Runnable action) {
        try {
            problem.setText("");
            action.run();
        } catch (IllegalArgumentException | IllegalStateException | dmmt.remote.RemoteSessionAccess.AccessException failure) {
            problem.setText(failure.getMessage());
        }
        refresh();
    }

    private static TextField field(String initial, String id) {
        var result = new TextField(initial);
        result.setId(id);
        return result;
    }

    private static GridPane form() {
        var grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        return grid;
    }

    private static Spinner<Integer> port(int value) {
        var spinner = new Spinner<Integer>(1, 65535, value);
        spinner.setEditable(true);
        return spinner;
    }

    private static int selectedPort(Spinner<Integer> spinner) {
        int value;
        try {
            value = Integer.parseInt(spinner.getEditor().getText().strip());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Enter a port between 1 and 65535.", invalid);
        }
        if (value < 1 || value > 65535) throw new IllegalArgumentException("Enter a port between 1 and 65535.");
        spinner.getValueFactory().setValue(value);
        return value;
    }
}
