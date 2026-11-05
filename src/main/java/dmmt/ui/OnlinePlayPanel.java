package dmmt.ui;

import dmmt.model.DmProject;
import dmmt.online.Campaign;
import dmmt.online.CampaignStore;
import dmmt.online.OnlineSessionController;
import dmmt.online.OnlineSessionServer;
import dmmt.online.PasswordHasher;
import dmmt.render.TokenArt;
import dmmt.service.Tuning;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.*;

import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Function;

/**
 * Sidebar tab for Online Play (3.41): a session header plus sub-tabs for campaigns, players, actors and connected
 * players. Editing happens through tables, a folder tree and context menus. Not DM control (that is DM Link).
 */
public final class OnlinePlayPanel extends VBox {
    /** Map-level actions the panel cannot do on its own. */
    public interface Actions {
        void placeActor(Campaign campaign, Campaign.Actor actor);
        void openSheet(Campaign campaign, Campaign.Actor actor);

        /** Number of actor tokens on the open map that belong to another campaign. */
        int foreignActorTokens(Campaign campaign);

        void removeForeignActorTokens(Campaign campaign);
    }

    /** Drag payload: the id of an actor of {@link #selectedCampaign()}. */
    public static final DataFormat ACTOR = new DataFormat("application/x-dmmt-actor");
    private static final DataFormat ACTOR_FOLDER = new DataFormat("application/x-dmmt-actor-folder");
    private static final PseudoClass DROP_TARGET = PseudoClass.getPseudoClass("drop-target");
    private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("open-map");

    /** A node of the actor tree: a folder ({@code actor == null}) or an actor. */
    private record ActorItem(String folder, Campaign.Actor actor) {
        String name() {
            if (actor != null) return actor.getName();
            int slash = folder.lastIndexOf('/');
            return slash < 0 ? folder : folder.substring(slash + 1);
        }
    }

    private final OnlineSessionController controller;
    private final Actions actions;

    private final Label campaignName = new Label();
    private final Label campaignSystem = new Label();
    private final Region statusDot = new Region();
    private final Label statusText = new Label();
    private final Label linkText = new Label();
    private final Button start = new Button("Start session");
    private final Button stop = new Button("Stop");
    /** Not part of the layout: pausing uses the pause key or the local API ({@code online.pause}). */
    private final ToggleButton pause = new ToggleButton("Pause");
    private final Button copyLink;

    private final TabPane tabs = new TabPane();
    private final Tab playersTab = new Tab("Players");
    private final Tab actorsTab = new Tab("Actors");
    private final Tab connectedTab = new Tab("Connected");
    private final TableView<CampaignStore.Summary> campaignTable = new TableView<>();
    private final TableView<Campaign.Player> playerTable = new TableView<>();
    private final TreeView<ActorItem> actorTree = new TreeView<>();
    private final Label actorEmpty = new Label();
    private final TableView<OnlineSessionServer.RosterEntry> rosterTable = new TableView<>();
    private final TextField actorSearch = new TextField();
    private final Label playersHeading = new Label();
    private final Label actorsHeading = new Label();
    private final HBox foreignBox = new HBox(6);
    private final Label foreignLabel = new Label();
    private final Label rosterPlaceholder = new Label();
    private final Timeline rosterTimer;

    private final Set<String> expandedFolders = new HashSet<>();
    private Map<String, OnlineSessionServer.RosterEntry> rosterByPlayer = Map.of();
    private Campaign current;
    private boolean updating;

    public OnlinePlayPanel(OnlineSessionController controller, Actions actions) {
        this.controller = controller;
        this.actions = actions;
        getStyleClass().add("online-panel");
        setId("online-panel");
        setSpacing(10);


        copyLink = Icons.button(MaterialDesignC.CONTENT_COPY, "Copy the invite link", this::copyInviteLink);
        start.setGraphic(Icons.icon(MaterialDesignP.PLAY));
        start.getStyleClass().add("primary");
        start.setDefaultButton(false);
        start.setOnAction(e -> startSession());
        stop.setGraphic(Icons.icon(MaterialDesignS.STOP));
        stop.setOnAction(e -> controller.stop());
        pause.setOnAction(e -> controller.setPaused(pause.isSelected()));

        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getStyleClass().add("online-tabs");
        Tab campaignsTab = new Tab("Campaigns", campaignsPage());
        playersTab.setContent(playersPage());
        actorsTab.setContent(actorsPage());
        connectedTab.setContent(connectedPage());
        tabs.getTabs().addAll(campaignsTab, playersTab, actorsTab, connectedTab);
        VBox.setVgrow(tabs, Priority.ALWAYS);

        getChildren().addAll(header(), tabs);

        rosterTimer = new Timeline(new KeyFrame(Duration.seconds(2), e -> refreshRoster()));
        rosterTimer.setCycleCount(Timeline.INDEFINITE);
        refresh();
    }

    /** Ids of controls that can be hidden or driven through the local API. */
    public Map<String, Node> controls() {
        return Map.of("online.start", start, "online.stop", stop, "online.pause", pause,
                "online.copyLink", copyLink);
    }

    public Campaign selectedCampaign() {
        return current;
    }

    public Menu tokenControlMenu(String mapId, DmProject.TokenInstance token) {
        Menu menu = new Menu("Player movement control");
        Campaign selected = controller.running() ? controller.campaign() : current;
        if (selected == null || selected.getPlayers().isEmpty()) {
            MenuItem hint = new MenuItem(selected == null
                    ? "Select a campaign in Online Play first" : "Add players in Online Play first");
            hint.setDisable(true);
            menu.getItems().add(hint);
            return menu;
        }
        for (Campaign.Player player : selected.getPlayers()) {
            CheckMenuItem item = new CheckMenuItem(player.getName());
            item.setSelected(dmmt.online.OnlineStateBuilder.controllers(token, null, selected, mapId)
                    .contains(player.getId()));
            item.setOnAction(event -> {
                boolean enabled = item.isSelected();
                try {
                    controller.setTokenControl(selected, mapId, token.getId(), player.getId(), enabled);
                } catch (IOException | IllegalArgumentException ex) {
                    item.setSelected(!enabled);
                    Dialogs.error(window(), "Could not save token control", ex.getMessage());
                }
            });
            menu.getItems().add(item);
        }
        return menu;
    }

    /** The actor of the open campaign that an {@link #ACTOR} drag refers to, or {@code null}. */
    public Campaign.Actor actorForDrop(String actorId) {
        return current == null ? null : current.actor(actorId);
    }

    public void show() {
        refresh();
    }

    // ---------------------------------------------------------------- layout

    private Node header() {
        Label title = new Label("Online Play");
        title.getStyleClass().add("sidebar-title");
        Label subtitle = new Label("Players join from a web browser with restricted access.");
        subtitle.getStyleClass().add("muted");
        subtitle.setWrapText(true);

        campaignName.getStyleClass().add("current-map-name");
        campaignSystem.getStyleClass().add("muted");
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox nameRow = new HBox(6, campaignName, grow, campaignSystem);
        nameRow.setAlignment(Pos.BASELINE_LEFT);

        statusDot.getStyleClass().add("session-status-dot");
        statusText.getStyleClass().add("online-status-text");
        statusText.setWrapText(true);
        HBox statusRow = new HBox(8, statusDot, statusText);
        statusRow.setAlignment(Pos.CENTER_LEFT);

        linkText.getStyleClass().addAll("muted", "online-link");
        linkText.setMaxWidth(Double.MAX_VALUE);
        linkText.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
        HBox.setHgrow(linkText, Priority.ALWAYS);
        HBox linkRow = new HBox(4, linkText, copyLink);
        linkRow.setAlignment(Pos.CENTER_LEFT);
        linkRow.managedProperty().bind(linkRow.visibleProperty());
        linkRow.visibleProperty().bind(linkText.textProperty().isNotEmpty());

        start.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(start, Priority.ALWAYS);
        HBox buttons = new HBox(6, start, stop);

        VBox card = new VBox(8, nameRow, statusRow, linkRow, buttons);
        card.getStyleClass().addAll("session-card", "online-session-card");
        VBox header = new VBox(6, new VBox(2, title, subtitle), card);
        header.getStyleClass().add("session-panel");
        header.setStyle("-fx-padding: 0;");
        return header;
    }

    private Node campaignsPage() {
        Label heading = heading("All campaigns");
        Button add = Icons.button(MaterialDesignP.PLUS, "New campaign", this::createCampaign);
        HBox bar = toolbar(heading, add);

        TableColumn<CampaignStore.Summary, String> name = column("Name", CampaignStore.Summary::name);
        name.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty ? null : value);
                CampaignStore.Summary row = empty ? null : getTableRow().getItem();
                pseudoClassStateChanged(ACTIVE, row != null && current != null && row.id().equals(current.getId()));
            }
        });
        TableColumn<CampaignStore.Summary, String> system = column("System", CampaignStore.Summary::system);
        TableColumn<CampaignStore.Summary, String> players = column("Players", s -> String.valueOf(s.players()));
        TableColumn<CampaignStore.Summary, String> actors = column("Actors", s -> String.valueOf(s.actors()));
        numeric(players, actors);
        name.setPrefWidth(110);
        system.setPrefWidth(80);
        campaignTable.getColumns().setAll(List.of(name, system, players, actors));
        styleTable(campaignTable);

        VBox empty = new VBox(8, new Label("No campaigns yet."),
                linkButton("Create your first campaign", this::createCampaign));
        empty.setAlignment(Pos.CENTER);
        campaignTable.setPlaceholder(empty);

        campaignTable.getSelectionModel().selectedItemProperty().addListener((obs, was, now) -> {
            if (updating || now == null) return;
            if (current != null && now.id().equals(current.getId())) return;
            if (controller.running()) {
                Platform.runLater(this::refresh);
                return;
            }
            openCampaign(now);
        });
        campaignTable.setRowFactory(t -> {
            TableRow<CampaignStore.Summary> row = new TableRow<>();
            row.itemProperty().addListener((obs, was, item) -> row.setContextMenu(item == null
                    ? menu(item("New campaign…", MaterialDesignP.PLUS, this::createCampaign))
                    : campaignMenu(item)));
            row.setContextMenu(menu(item("New campaign…", MaterialDesignP.PLUS, this::createCampaign)));
            return row;
        });
        campaignTable.setOnKeyPressed(e -> {
            CampaignStore.Summary selected = campaignTable.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            if (e.getCode() == KeyCode.F2) renameCampaign(selected);
            else if (e.getCode() == KeyCode.DELETE) deleteCampaign(selected);
            else return;
            e.consume();
        });
        return page(bar, campaignTable, hint("Select a campaign to work on it. Right-click for more."));
    }

    private Node playersPage() {
        Button add = Icons.button(MaterialDesignA.ACCOUNT_PLUS_OUTLINE, "Add player", this::addPlayer);
        HBox bar = toolbar(playersHeading, add);
        add.disableProperty().bind(playersHeading.textProperty().isEqualTo(""));

        TableColumn<Campaign.Player, String> name = column("Username", Campaign.Player::getName);
        TableColumn<Campaign.Player, String> password = column("Password",
                p -> hasPassword(p) ? "Set" : "Missing");
        password.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty ? null : value);
                getStyleClass().remove("online-warning-text");
                if (!empty && "Missing".equals(value)) getStyleClass().add("online-warning-text");
            }
        });
        TableColumn<Campaign.Player, String> owned = column("Actors", p -> current == null ? "0"
                : String.valueOf(current.getActors().stream().filter(a -> p.getId().equals(a.getOwnerId())).count()));
        numeric(owned);
        TableColumn<Campaign.Player, String> status = column("Status", this::playerStatus);
        status.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty ? null : value);
                getStyleClass().remove("online-good-text");
                if (!empty && value != null && value.startsWith("Online")) getStyleClass().add("online-good-text");
            }
        });
        name.setPrefWidth(100);
        status.setPrefWidth(90);
        playerTable.getColumns().setAll(List.of(name, password, owned, status));
        styleTable(playerTable);
        playerTable.setPlaceholder(new Label("No players yet. Right-click to add one."));
        playerTable.setRowFactory(t -> {
            TableRow<Campaign.Player> row = new TableRow<>();
            row.itemProperty().addListener((obs, was, item) -> row.setContextMenu(item == null
                    ? menu(item("Add player…", MaterialDesignA.ACCOUNT_PLUS_OUTLINE, this::addPlayer))
                    : playerMenu(item)));
            row.setContextMenu(menu(item("Add player…", MaterialDesignA.ACCOUNT_PLUS_OUTLINE, this::addPlayer)));
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && row.getItem() != null) setPassword(row.getItem());
            });
            return row;
        });
        playerTable.setOnKeyPressed(e -> {
            Campaign.Player selected = playerTable.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            if (e.getCode() == KeyCode.F2) renamePlayer(selected);
            else if (e.getCode() == KeyCode.DELETE) deletePlayer(selected);
            else return;
            e.consume();
        });
        return page(bar, playerTable, hint("Double-click a player to set the password. Right-click for more."));
    }

    private Node actorsPage() {
        Button addActor = Icons.button(MaterialDesignP.PLUS, "New actor", () -> addActor(""));
        Button addFolder = Icons.button(MaterialDesignF.FOLDER_PLUS_OUTLINE, "New folder", () -> addFolder(""));
        addActor.disableProperty().bind(actorsHeading.textProperty().isEqualTo(""));
        addFolder.disableProperty().bind(actorsHeading.textProperty().isEqualTo(""));
        HBox bar = toolbar(actorsHeading, addActor, addFolder);

        actorSearch.setPromptText("Search actors or owners");
        actorSearch.textProperty().addListener((obs, was, now) -> rebuildActors());
        actorSearch.getStyleClass().add("online-search");

        actorTree.setShowRoot(false);
        actorTree.getStyleClass().add("online-actor-tree");
        actorTree.setCellFactory(t -> new ActorCell());
        actorTree.setContextMenu(menu(
                item("New actor…", MaterialDesignP.PLUS, () -> addActor("")),
                item("New testing character…", MaterialDesignP.PLUS, () -> addActor("", true)),
                item("New folder…", MaterialDesignF.FOLDER_PLUS_OUTLINE, () -> addFolder(""))));
        actorTree.setOnKeyPressed(e -> {
            TreeItem<ActorItem> selected = actorTree.getSelectionModel().getSelectedItem();
            if (selected == null || selected.getValue() == null) return;
            ActorItem item = selected.getValue();
            if (e.getCode() == KeyCode.F2) {
                if (item.actor() != null) renameActor(item.actor());
                else renameFolder(item.folder());
            } else if (e.getCode() == KeyCode.DELETE) {
                if (item.actor() != null) removeActor(item.actor());
                else deleteFolder(item.folder());
            } else {
                return;
            }
            e.consume();
        });

        FontIcon warn = Icons.icon(MaterialDesignA.ALERT_CIRCLE_OUTLINE);
        foreignLabel.setWrapText(true);
        foreignLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(foreignLabel, Priority.ALWAYS);
        Hyperlink cleanup = new Hyperlink("Remove");
        cleanup.setOnAction(e -> {
            if (current != null) {
                actions.removeForeignActorTokens(current);
                refresh();
            }
        });
        foreignBox.getChildren().setAll(warn, foreignLabel, cleanup);
        foreignBox.setAlignment(Pos.CENTER_LEFT);
        foreignBox.getStyleClass().add("online-warning");
        foreignBox.managedProperty().bind(foreignBox.visibleProperty());

        actorEmpty.getStyleClass().add("online-hint");
        actorEmpty.setMouseTransparent(true);
        actorEmpty.setWrapText(true);
        StackPane treeStack = new StackPane(actorTree, actorEmpty);
        return page(bar, actorSearch, treeStack, foreignBox,
                hint("Double-click an actor to open its character sheet. Drag onto the map to place it. Right-click for owner, artwork and more."));
    }

    private Node connectedPage() {
        TableColumn<OnlineSessionServer.RosterEntry, String> name = column("Player",
                OnlineSessionServer.RosterEntry::playerName);
        TableColumn<OnlineSessionServer.RosterEntry, String> tabsColumn = column("Tabs",
                r -> String.valueOf(r.connections()));
        TableColumn<OnlineSessionServer.RosterEntry, String> ping = column("Ping",
                r -> r.pingMs() < 0 ? "–" : r.pingMs() + " ms");
        numeric(tabsColumn, ping);
        ping.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty ? null : value);
                getStyleClass().removeAll("ping-good", "ping-ok", "ping-bad");
                OnlineSessionServer.RosterEntry row = empty ? null : getTableRow().getItem();
                if (row != null && row.pingMs() >= 0) {
                    getStyleClass().add(row.pingMs() < 80 ? "ping-good" : row.pingMs() < 200 ? "ping-ok" : "ping-bad");
                }
                setAlignment(Pos.CENTER_RIGHT);
            }
        });
        name.setPrefWidth(130);
        rosterTable.getColumns().setAll(List.of(name, tabsColumn, ping));
        styleTable(rosterTable);
        rosterPlaceholder.setWrapText(true);
        rosterPlaceholder.setPadding(new Insets(12));
        rosterTable.setPlaceholder(rosterPlaceholder);
        rosterTable.setRowFactory(t -> {
            TableRow<OnlineSessionServer.RosterEntry> row = new TableRow<>();
            row.itemProperty().addListener((obs, was, item) -> row.setContextMenu(item == null ? null
                    : menu(item("Kick " + item.playerName(), MaterialDesignA.ACCOUNT_REMOVE_OUTLINE,
                            () -> kick(item.playerId(), item.playerName())))));
            return row;
        });
        return page(toolbar(heading("Connected players")), rosterTable,
                hint("Ping is measured every few seconds. Right-click a player to kick them."));
    }

    // ---------------------------------------------------------------- refresh

    public void refresh() {
        updating = true;
        try {
            boolean running = controller.running();
            if (running) current = controller.campaign();
            List<CampaignStore.Summary> all = store().list();
            if (current != null && all.stream().noneMatch(s -> s.id().equals(current.getId()))) current = null;
            campaignTable.getItems().setAll(all);
            campaignTable.getSelectionModel().clearSelection();
            if (current != null) {
                all.stream().filter(s -> s.id().equals(current.getId())).findFirst()
                        .ifPresent(s -> campaignTable.getSelectionModel().select(s));
            }
            campaignTable.refresh();

            campaignName.setText(current == null ? "No campaign selected" : current.getName());
            if (current == null) {
                if (!campaignName.getStyleClass().contains("unsaved")) campaignName.getStyleClass().add("unsaved");
            } else {
                campaignName.getStyleClass().remove("unsaved");
            }
            campaignSystem.setText(current == null ? "" : systemOf(current));
            statusDot.getStyleClass().removeAll("live", "busy");
            if (running) statusDot.getStyleClass().add(controller.paused() ? "busy" : "live");
            statusText.setText(statusLine(running));
            linkText.setText(running ? controller.inviteLink() : "");
            start.setDisable(running || current == null);
            stop.setDisable(!running);
            pause.setDisable(!running);
            pause.setSelected(running && controller.paused());
            copyLink.setDisable(!running);

            playersHeading.setText(current == null ? "" : "Players of " + current.getName());
            actorsHeading.setText(current == null ? "" : "Actors of " + current.getName());
            playerTable.setPlaceholder(new Label(current == null ? "Select a campaign first."
                    : "No players yet. Right-click to add one."));
            int foreign = current == null ? 0 : actions.foreignActorTokens(current);
            foreignLabel.setText(foreign + " actor token(s) on this map belong to another campaign.");
            foreignBox.setVisible(foreign > 0);
            refreshRoster();
            rebuildActors();
            if (running) rosterTimer.play();
            else rosterTimer.stop();
        } finally {
            updating = false;
        }
    }

    private void refreshRoster() {
        boolean running = controller.running() && controller.server() != null;
        List<OnlineSessionServer.RosterEntry> entries = running ? controller.server().roster() : List.of();
        Map<String, OnlineSessionServer.RosterEntry> byPlayer = new HashMap<>();
        entries.forEach(r -> byPlayer.put(r.playerId(), r));
        rosterByPlayer = byPlayer;
        String selectedId = Optional.ofNullable(rosterTable.getSelectionModel().getSelectedItem())
                .map(OnlineSessionServer.RosterEntry::playerId).orElse(null);
        rosterTable.getItems().setAll(entries);
        entries.stream().filter(r -> r.playerId().equals(selectedId)).findFirst()
                .ifPresent(r -> rosterTable.getSelectionModel().select(r));
        rosterPlaceholder.setText(running ? "No players connected yet. Share the invite link."
                : "Start the session to see connected players.");
        connectedTab.setText(running && !entries.isEmpty() ? "Connected (" + entries.size() + ")" : "Connected");

        String selectedPlayer = Optional.ofNullable(playerTable.getSelectionModel().getSelectedItem())
                .map(Campaign.Player::getId).orElse(null);
        playerTable.getItems().setAll(current == null ? List.of() : current.getPlayers());
        playerTable.getItems().stream().filter(p -> p.getId().equals(selectedPlayer)).findFirst()
                .ifPresent(p -> playerTable.getSelectionModel().select(p));
        playerTable.refresh();
    }

    private String statusLine(boolean running) {
        if (!running) return current == null ? "Offline. Select or create a campaign." : "Offline. Nothing is hosted yet.";
        OnlineSessionServer server = controller.server();
        if (controller.paused()) {
            String key = Tuning.ONLINE_PAUSE_KEY.get().trim();
            return key.isEmpty() ? "Paused" : "Paused · press " + keyLabel(key) + " to resume";
        }
        String key = Tuning.ONLINE_PAUSE_KEY.get().trim();
        return "Live" + (server == null ? "" : " on port " + server.port())
                + (key.isEmpty() ? "" : " · " + keyLabel(key) + " pauses");
    }

    private static String keyLabel(String key) {
        try {
            return KeyCode.valueOf(key.toUpperCase(Locale.ROOT)).getName();
        } catch (IllegalArgumentException ex) {
            return key;
        }
    }

    private String playerStatus(Campaign.Player player) {
        if (!controller.running()) return "–";
        OnlineSessionServer.RosterEntry entry = rosterByPlayer.get(player.getId());
        if (entry == null) return "Offline";
        return entry.pingMs() < 0 ? "Online" : "Online · " + entry.pingMs() + " ms";
    }

    private void rebuildActors() {
        String selectedKey = Optional.ofNullable(actorTree.getSelectionModel().getSelectedItem())
                .map(TreeItem::getValue).map(OnlinePlayPanel::key).orElse(null);
        TreeItem<ActorItem> root = new TreeItem<>(new ActorItem("", null));
        root.setExpanded(true);
        actorTree.setRoot(root);
        actorEmpty.setText(current == null ? "Select a campaign first."
                : "No actors yet. Right-click to add one.");
        actorEmpty.setVisible(current == null || current.getActors().isEmpty() && current.getActorFolders().isEmpty());
        if (current == null) return;
        String query = actorSearch.getText() == null ? "" : actorSearch.getText().trim().toLowerCase(Locale.ROOT);

        TreeSet<String> folders = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String f : current.getActorFolders()) addWithParents(folders, normalize(f));
        for (Campaign.Actor a : current.getActors()) addWithParents(folders, normalize(a.getFolder()));
        folders.remove("");

        Map<String, TreeItem<ActorItem>> folderItems = new HashMap<>();
        folderItems.put("", root);
        for (String folder : folders) {
            TreeItem<ActorItem> item = new TreeItem<>(new ActorItem(folder, null));
            item.setExpanded(!query.isEmpty() || expandedFolders.contains(folder));
            item.expandedProperty().addListener((obs, was, now) -> {
                if (!actorSearch.getText().isBlank()) return;
                if (now) expandedFolders.add(folder);
                else expandedFolders.remove(folder);
            });
            folderItems.put(folder, item);
        }
        for (String folder : folders) {
            folderItems.get(parentOf(folder)).getChildren().add(folderItems.get(folder));
        }
        List<Campaign.Actor> actors = new ArrayList<>(current.getActors());
        actors.sort(Comparator.comparing(a -> Objects.requireNonNullElse(a.getName(), ""), String.CASE_INSENSITIVE_ORDER));
        for (Campaign.Actor actor : actors) {
            if (!query.isEmpty() && !matches(actor, query)) continue;
            TreeItem<ActorItem> parent = folderItems.getOrDefault(normalize(actor.getFolder()), root);
            parent.getChildren().add(new TreeItem<>(new ActorItem(normalize(actor.getFolder()), actor)));
        }
        if (!query.isEmpty()) prune(root);
        if (selectedKey != null) select(root, selectedKey);
    }

    private boolean matches(Campaign.Actor actor, String query) {
        if (actor.getName() != null && actor.getName().toLowerCase(Locale.ROOT).contains(query)) return true;
        Campaign.Player owner = current.player(actor.getOwnerId());
        return owner != null && owner.getName().toLowerCase(Locale.ROOT).contains(query);
    }

    private static boolean prune(TreeItem<ActorItem> item) {
        item.getChildren().removeIf(child -> child.getValue().actor() == null && !prune(child));
        return !item.getChildren().isEmpty();
    }

    private boolean select(TreeItem<ActorItem> item, String key) {
        for (TreeItem<ActorItem> child : item.getChildren()) {
            if (key.equals(key(child.getValue()))) {
                actorTree.getSelectionModel().select(child);
                return true;
            }
            if (select(child, key)) return true;
        }
        return false;
    }

    private static String key(ActorItem item) {
        return item.actor() != null ? "a:" + item.actor().getId() : "f:" + item.folder();
    }

    // ---------------------------------------------------------------- campaigns

    private ContextMenu campaignMenu(CampaignStore.Summary summary) {
        boolean running = controller.running();
        boolean active = current != null && summary.id().equals(current.getId());
        MenuItem open = item("Open", MaterialDesignF.FOLDER_OPEN_OUTLINE, () -> openCampaign(summary));
        open.setDisable(running || active);
        MenuItem delete = item("Delete…", MaterialDesignD.DELETE_OUTLINE, () -> deleteCampaign(summary));
        delete.setDisable(running && active);
        return menu(open, item("Rename…", MaterialDesignR.RENAME_BOX, () -> renameCampaign(summary)),
                new SeparatorMenuItem(),
                item("New campaign…", MaterialDesignP.PLUS, this::createCampaign),
                new SeparatorMenuItem(), delete);
    }

    private void openCampaign(CampaignStore.Summary summary) {
        if (controller.running()) return;
        try {
            current = store().load(summary.id());
            expandedFolders.clear();
        } catch (IOException ex) {
            Dialogs.error(window(), "Could not open the campaign", ex.getMessage());
            current = null;
        }
        Platform.runLater(this::refresh);
    }

    private Function<String, String> campaignNameProblem(String exceptId) {
        return name -> {
            if (name == null || name.isBlank()) return "Enter a name.";
            boolean taken = store().list().stream()
                    .anyMatch(s -> !s.id().equals(exceptId) && s.name() != null && s.name().equalsIgnoreCase(name.trim()));
            return taken ? "A campaign with this name already exists." : null;
        };
    }

    private void createCampaign() {
        OnlineDialogs.createCampaign(window(), campaignNameProblem(null)).ifPresent(created -> {
            try {
                Campaign campaign = store().create(created.name(), created.system());
                if (!controller.running()) {
                    current = campaign;
                    expandedFolders.clear();
                }
                refresh();
            } catch (IOException ex) {
                Dialogs.error(window(), "Could not create the campaign", ex.getMessage());
            }
        });
    }

    private void renameCampaign(CampaignStore.Summary summary) {
        Dialogs.askText(window(), "Rename campaign", "Rename " + summary.name(), MaterialDesignR.RENAME_BOX,
                "Rename", summary.name(), campaignNameProblem(summary.id())).ifPresent(name -> {
            try {
                if (current != null && current.getId().equals(summary.id())) {
                    current.setName(name);
                    controller.campaignEdited(current);
                } else {
                    Campaign campaign = store().load(summary.id());
                    campaign.setName(name);
                    store().save(campaign);
                }
            } catch (IOException ex) {
                Dialogs.error(window(), "Could not rename the campaign", ex.getMessage());
            }
            refresh();
        });
    }

    private void deleteCampaign(CampaignStore.Summary summary) {
        boolean active = current != null && summary.id().equals(current.getId());
        if (active && controller.running()) {
            Dialogs.error(window(), "Campaign in use", "Stop the online session before deleting this campaign.");
            return;
        }
        if (!Dialogs.confirmDanger(window(), "Delete campaign", "Delete " + summary.name() + "?",
                "Its players, actors, fog memory and chat history are deleted. Maps are not changed.", "Delete")) {
            return;
        }
        try {
            store().delete(summary.id());
        } catch (IOException ex) {
            Dialogs.error(window(), "Could not delete the campaign", ex.getMessage());
        }
        if (active) current = null;
        refresh();
    }

    // ---------------------------------------------------------------- players

    private ContextMenu playerMenu(Campaign.Player player) {
        boolean online = rosterByPlayer.containsKey(player.getId());
        MenuItem kick = item("Kick", MaterialDesignA.ACCOUNT_REMOVE_OUTLINE, () -> kick(player.getId(), player.getName()));
        kick.setDisable(!online);
        return menu(item("Set password…", MaterialDesignL.LOCK_OUTLINE, () -> setPassword(player)),
                item("Rename…", MaterialDesignR.RENAME_BOX, () -> renamePlayer(player)),
                kick, new SeparatorMenuItem(),
                item("Add player…", MaterialDesignA.ACCOUNT_PLUS_OUTLINE, this::addPlayer),
                new SeparatorMenuItem(),
                item("Delete…", MaterialDesignD.DELETE_OUTLINE, () -> deletePlayer(player)));
    }

    private Function<String, String> playerNameProblem(String exceptId) {
        return name -> {
            if (name == null || name.isBlank()) return "Enter a username.";
            boolean taken = current.getPlayers().stream()
                    .anyMatch(p -> !p.getId().equals(exceptId) && p.getName().equalsIgnoreCase(name.trim()));
            return taken ? "A player with this username already exists." : null;
        };
    }

    private void addPlayer() {
        if (current == null) return;
        OnlineDialogs.addPlayer(window(), playerNameProblem(null)).ifPresent(created -> {
            current.getPlayers().add(Campaign.Player.builder().name(created.name())
                    .passwordHash(PasswordHasher.hash(created.password())).build());
            persist();
        });
    }

    private void setPassword(Campaign.Player player) {
        OnlineDialogs.setPassword(window(), player.getName()).ifPresent(password -> {
            player.setPasswordHash(PasswordHasher.hash(password));
            signOut(player.getId(), "Your password was changed by the DM");
            persist();
        });
    }

    private void renamePlayer(Campaign.Player player) {
        Dialogs.askText(window(), "Rename player", "Rename " + player.getName(), MaterialDesignR.RENAME_BOX,
                "Rename", player.getName(), playerNameProblem(player.getId())).ifPresent(name -> {
            if (name.equals(player.getName())) return;
            player.setName(name);
            signOut(player.getId(), "Your username was changed by the DM");
            persist();
        });
    }

    private void deletePlayer(Campaign.Player player) {
        if (!Dialogs.confirmDanger(window(), "Delete player", "Delete " + player.getName() + "?",
                "Their actors become unassigned and they are signed out.", "Delete")) {
            return;
        }
        current.getPlayers().remove(player);
        current.getActors().forEach(a -> {
            if (player.getId().equals(a.getOwnerId())) a.setOwnerId(null);
        });
        current.getControlledTokens().removeIf(l -> player.getId().equals(l.getPlayerId()));
        signOut(player.getId(), "Removed by the DM");
        persist();
    }

    private void kick(String playerId, String name) {
        if (controller.server() == null) return;
        if (Dialogs.confirmDanger(window(), "Kick player", "Kick " + name + "?",
                "All of their browser tabs are disconnected. They can sign in again.", "Kick")) {
            controller.server().kick(playerId, "Removed by the DM");
            refreshRoster();
        }
    }

    private void signOut(String playerId, String reason) {
        if (controller.running() && controller.server() != null) controller.server().kick(playerId, reason);
    }

    private static boolean hasPassword(Campaign.Player player) {
        return player.getPasswordHash() != null && !player.getPasswordHash().isBlank();
    }

    // ---------------------------------------------------------------- actors

    private ContextMenu actorMenu(Campaign.Actor actor) {
        Menu owner = new Menu("Owner");
        owner.setGraphic(Icons.icon(MaterialDesignA.ACCOUNT_CIRCLE_OUTLINE));
        ToggleGroup group = new ToggleGroup();
        RadioMenuItem none = new RadioMenuItem("Unassigned");
        none.setToggleGroup(group);
        none.setSelected(actor.getOwnerId() == null || current.player(actor.getOwnerId()) == null);
        none.setOnAction(e -> setOwner(actor, null));
        owner.getItems().add(none);
        if (!current.getPlayers().isEmpty()) owner.getItems().add(new SeparatorMenuItem());
        for (Campaign.Player player : current.getPlayers()) {
            RadioMenuItem choice = new RadioMenuItem(player.getName());
            choice.setToggleGroup(group);
            choice.setSelected(player.getId().equals(actor.getOwnerId()));
            choice.setOnAction(e -> setOwner(actor, player.getId()));
            owner.getItems().add(choice);
        }
        MenuItem removeArt = item("Remove artwork", MaterialDesignI.IMAGE_OFF_OUTLINE, () -> {
            actor.setArtwork(null);
            persist();
        });
        removeArt.setDisable(actor.getArtwork() == null);
        return menu(item("Place at view centre", MaterialDesignM.MAP_MARKER_RADIUS_OUTLINE,
                        () -> actions.placeActor(current, actor)),
                item("Open character sheet", MaterialDesignB.BOOK_OPEN_VARIANT, () -> actions.openSheet(current, actor)),
                owner,
                new SeparatorMenuItem(),
                item("Change artwork…", MaterialDesignI.IMAGE_PLUS, () -> chooseArtwork(actor)),
                removeArt,
                new SeparatorMenuItem(),
                item("Rename…", MaterialDesignR.RENAME_BOX, () -> renameActor(actor)),
                item("Remove…", MaterialDesignD.DELETE_OUTLINE, () -> removeActor(actor)));
    }

    private ContextMenu folderMenu(String folder) {
        return menu(item("New actor here…", MaterialDesignP.PLUS, () -> addActor(folder)),
                item("New testing character here…", MaterialDesignP.PLUS, () -> addActor(folder, true)),
                item("New folder…", MaterialDesignF.FOLDER_PLUS_OUTLINE, () -> addFolder(folder)),
                new SeparatorMenuItem(),
                item("Rename…", MaterialDesignR.RENAME_BOX, () -> renameFolder(folder)),
                item("Delete folder…", MaterialDesignD.DELETE_OUTLINE, () -> deleteFolder(folder)));
    }

    private static Function<String, String> required(String what) {
        return name -> name == null || name.isBlank() ? "Enter " + what + "." : null;
    }

    private void addActor(String folder) {
        addActor(folder, false);
    }

    private void addActor(String folder, boolean testing) {
        if (current == null) return;
        Dialogs.askText(window(), testing ? "New testing character" : "New actor",
                testing ? "Testing only: unrestricted character, never a normal actor" : folder.isEmpty() ? "Add an actor" : "Add an actor to " + folder,
                MaterialDesignP.PLUS, "Add actor", "", required("a name")).ifPresent(name -> {
            Campaign.Actor actor = Campaign.Actor.builder().name(name).testingCharacter(testing)
                    .folder(folder.isEmpty() ? null : folder).build();
            current.getActors().add(actor);
            if (!folder.isEmpty()) expandedFolders.add(folder);
            persist();
            select(actorTree.getRoot(), "a:" + actor.getId());
        });
    }

    private void renameActor(Campaign.Actor actor) {
        Dialogs.askText(window(), "Rename actor", "Rename " + actor.getName(), MaterialDesignR.RENAME_BOX,
                "Rename", actor.getName(), required("a name")).ifPresent(name -> {
            actor.setName(name);
            persist();
        });
    }

    private void removeActor(Campaign.Actor actor) {
        if (!Dialogs.confirmDanger(window(), "Remove actor", "Remove " + actor.getName() + "?",
                "Tokens of this actor stay on maps but show as an unknown actor.", "Remove")) {
            return;
        }
        current.getActors().remove(actor);
        persist();
    }

    private void setOwner(Campaign.Actor actor, String playerId) {
        actor.setOwnerId(playerId);
        persist();
    }

    private void chooseArtwork(Campaign.Actor actor) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Artwork for " + actor.getName());
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.gif"));
        java.io.File file = chooser.showOpenDialog(window());
        if (file == null) return;
        try {
            actor.setArtwork(store().storeArtwork(current.getId(), Files.readAllBytes(file.toPath())));
            persist();
        } catch (IOException | RuntimeException ex) {
            Dialogs.error(window(), "Could not use this image", ex.getMessage());
        }
    }

    private Function<String, String> folderNameProblem(String parent, String except) {
        return name -> {
            if (name == null || name.isBlank()) return "Enter a folder name.";
            if (name.contains("/") || name.contains("\\")) return "Folder names cannot contain slashes.";
            String path = join(parent, name.trim());
            return !path.equalsIgnoreCase(except) && folderExists(path) ? "This folder already exists." : null;
        };
    }

    private boolean folderExists(String path) {
        return current.getActorFolders().stream().anyMatch(f -> normalize(f).equalsIgnoreCase(path))
                || current.getActors().stream().anyMatch(a -> {
                    String f = normalize(a.getFolder());
                    return f.equalsIgnoreCase(path) || f.toLowerCase(Locale.ROOT).startsWith(path.toLowerCase(Locale.ROOT) + "/");
                });
    }

    private void addFolder(String parent) {
        if (current == null) return;
        Dialogs.askText(window(), "New folder", parent.isEmpty() ? "Create a folder" : "Create a folder in " + parent,
                MaterialDesignF.FOLDER_PLUS_OUTLINE, "Create", "", folderNameProblem(parent, null)).ifPresent(name -> {
            String path = join(parent, name);
            current.getActorFolders().add(path);
            if (!parent.isEmpty()) expandedFolders.add(parent);
            persist();
        });
    }

    private void renameFolder(String folder) {
        String parent = parentOf(folder);
        String name = new ActorItem(folder, null).name();
        Dialogs.askText(window(), "Rename folder", "Rename " + name, MaterialDesignR.RENAME_BOX, "Rename", name,
                folderNameProblem(parent, folder)).ifPresent(newName -> {
            moveFolder(folder, join(parent, newName));
            persist();
        });
    }

    private void deleteFolder(String folder) {
        String parent = parentOf(folder);
        if (!Dialogs.confirmDanger(window(), "Delete folder", "Delete folder " + new ActorItem(folder, null).name() + "?",
                "Its actors and sub-folders move to " + (parent.isEmpty() ? "the top level" : parent) + ".", "Delete")) {
            return;
        }
        String prefix = folder + "/";
        current.getActorFolders().removeIf(f -> normalize(f).equals(folder));
        for (int i = 0; i < current.getActorFolders().size(); i++) {
            String f = normalize(current.getActorFolders().get(i));
            if (f.startsWith(prefix)) current.getActorFolders().set(i, join(parent, f.substring(prefix.length())));
        }
        for (Campaign.Actor actor : current.getActors()) {
            String f = normalize(actor.getFolder());
            if (f.equals(folder)) actor.setFolder(parent.isEmpty() ? null : parent);
            else if (f.startsWith(prefix)) actor.setFolder(join(parent, f.substring(prefix.length())));
        }
        persist();
    }

    /** Moves (renames) a folder with its sub-folders and actors. */
    private void moveFolder(String from, String to) {
        if (from.equals(to)) return;
        String prefix = from + "/";
        List<String> folders = new ArrayList<>();
        for (String f : current.getActorFolders()) {
            String n = normalize(f);
            folders.add(n.equals(from) ? to : n.startsWith(prefix) ? to + "/" + n.substring(prefix.length()) : n);
        }
        if (folders.stream().noneMatch(to::equals)) folders.add(to);
        current.setActorFolders(new ArrayList<>(new LinkedHashSet<>(folders)));
        for (Campaign.Actor actor : current.getActors()) {
            String f = normalize(actor.getFolder());
            if (f.equals(from)) actor.setFolder(to);
            else if (f.startsWith(prefix)) actor.setFolder(to + "/" + f.substring(prefix.length()));
        }
        boolean wasExpanded = expandedFolders.remove(from);
        if (wasExpanded) expandedFolders.add(to);
    }

    private void moveActor(Campaign.Actor actor, String folder) {
        if (normalize(actor.getFolder()).equals(folder)) return;
        actor.setFolder(folder.isEmpty() ? null : folder);
        if (!folder.isEmpty()) expandedFolders.add(folder);
        persist();
    }

    // ---------------------------------------------------------------- actor tree cell

    private final class ActorCell extends TreeCell<ActorItem> {
        private final Canvas thumb = new Canvas(32, 32);
        private final Label name = new Label();
        private final Label detail = new Label();
        private final VBox text = new VBox(0, name, detail);
        private final HBox actorBox = new HBox(8, thumb, text);

        ActorCell() {
            detail.getStyleClass().add("muted");
            detail.setStyle("-fx-font-size: 11px;");
            actorBox.setAlignment(Pos.CENTER_LEFT);
            text.setAlignment(Pos.CENTER_LEFT);

            setOnDragDetected(e -> {
                ActorItem item = getItem();
                if (item == null || current == null) return;
                ClipboardContent content = new ClipboardContent();
                Dragboard board;
                if (item.actor() != null) {
                    board = startDragAndDrop(TransferMode.COPY_OR_MOVE);
                    content.put(ACTOR, item.actor().getId());
                    SnapshotParameters params = new SnapshotParameters();
                    params.setFill(Color.TRANSPARENT);
                    board.setDragView(thumb.snapshot(params, null), 16, 16);
                } else {
                    board = startDragAndDrop(TransferMode.MOVE);
                    content.put(ACTOR_FOLDER, item.folder());
                }
                board.setContent(content);
                e.consume();
            });
            setOnDragOver(e -> {
                if (acceptsDrop(e.getDragboard())) e.acceptTransferModes(TransferMode.MOVE);
                e.consume();
            });
            setOnDragEntered(e -> pseudoClassStateChanged(DROP_TARGET, getItem() != null && acceptsDrop(e.getDragboard())));
            setOnDragExited(e -> pseudoClassStateChanged(DROP_TARGET, false));
            setOnDragDropped(e -> {
                boolean done = false;
                if (acceptsDrop(e.getDragboard())) {
                    String target = targetFolder();
                    Dragboard board = e.getDragboard();
                    if (board.hasContent(ACTOR)) {
                        Campaign.Actor actor = current.actor((String) board.getContent(ACTOR));
                        if (actor != null) {
                            moveActor(actor, target);
                            done = true;
                        }
                    } else if (board.hasContent(ACTOR_FOLDER)) {
                        String folder = (String) board.getContent(ACTOR_FOLDER);
                        moveFolder(folder, join(target, new ActorItem(folder, null).name()));
                        if (!target.isEmpty()) expandedFolders.add(target);
                        persist();
                        done = true;
                    }
                }
                e.setDropCompleted(done);
                e.consume();
            });
            setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && getItem() != null && getItem().actor() != null) {
                    actions.openSheet(current, getItem().actor());
                    e.consume();
                }
            });
        }

        /** Folder a drop on this cell lands in: the folder itself, an actor's folder, or the root for empty cells. */
        private String targetFolder() {
            ActorItem item = getItem();
            if (item == null) return "";
            return item.folder();
        }

        private boolean acceptsDrop(Dragboard board) {
            if (current == null) return false;
            String target = targetFolder();
            if (board.hasContent(ACTOR)) {
                Campaign.Actor actor = current.actor((String) board.getContent(ACTOR));
                return actor != null && !normalize(actor.getFolder()).equals(target);
            }
            if (board.hasContent(ACTOR_FOLDER)) {
                String folder = (String) board.getContent(ACTOR_FOLDER);
                String moved = join(target, new ActorItem(folder, null).name());
                return !folder.equals(target) && !target.startsWith(folder + "/") && !parentOf(folder).equals(target)
                        && !folderExists(moved);
            }
            return false;
        }

        @Override protected void updateItem(ActorItem item, boolean empty) {
            super.updateItem(item, empty);
            pseudoClassStateChanged(DROP_TARGET, false);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                setContextMenu(null);
                return;
            }
            if (item.actor() == null) {
                setText(item.name());
                setGraphic(Icons.icon(getTreeItem() != null && getTreeItem().isExpanded()
                        ? MaterialDesignF.FOLDER_OPEN_OUTLINE : MaterialDesignF.FOLDER_OUTLINE));
                setContextMenu(folderMenu(item.folder()));
                return;
            }
            Campaign.Actor actor = item.actor();
            setText(null);
            name.setText(actor.getName());
            Campaign.Player owner = current == null ? null : current.player(actor.getOwnerId());
            detail.setText((actor.isTestingCharacter() ? "TESTING - " : "") + (owner == null ? "Unassigned" : owner.getName()));
            drawThumb(actor);
            setGraphic(actorBox);
            setContextMenu(actorMenu(actor));
        }

        private void drawThumb(Campaign.Actor actor) {
            var gc = thumb.getGraphicsContext2D();
            gc.clearRect(0, 0, thumb.getWidth(), thumb.getHeight());
            DmProject.TokenDefinition definition = DmProject.TokenDefinition.builder()
                    .id("actor:" + actor.getId()).name(actor.getName()).build();
            if (actor.getArtwork() != null && current != null) {
                definition.setImage(store().artworkPath(current.getId(), actor.getArtwork()).toUri().toString());
            }
            try {
                TokenArt.draw(gc, definition, 16, 16, 30);
            } catch (RuntimeException ex) {
                definition.setImage(null);
                TokenArt.draw(gc, definition, 16, 16, 30);
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private CampaignStore store() {
        return controller.store();
    }

    private Window window() {
        return getScene() == null ? null : getScene().getWindow();
    }

    private void persist() {
        if (current == null) return;
        try {
            controller.campaignEdited(current);
        } catch (IOException ex) {
            Dialogs.error(window(), "Could not save the campaign", ex.getMessage());
        }
        refresh();
    }

    private void copyInviteLink() {
        if (!controller.running()) return;
        ClipboardContent content = new ClipboardContent();
        content.putString(controller.inviteLink());
        Clipboard.getSystemClipboard().setContent(content);
    }

    private void startSession() {
        if (current == null) return;
        try {
            controller.start(current);
        } catch (IOException | RuntimeException ex) {
            Dialogs.error(window(), "Could not start the online session", ex.getMessage());
        }
        refresh();
    }

    private static String systemOf(Campaign campaign) {
        return campaign.getSystem() == null || campaign.getSystem().isBlank() ? Campaign.DEFAULT_SYSTEM : campaign.getSystem();
    }

    static String normalize(String folder) {
        if (folder == null) return "";
        String trimmed = folder.replace('\\', '/').trim();
        while (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed;
    }

    static String parentOf(String folder) {
        int slash = folder.lastIndexOf('/');
        return slash < 0 ? "" : folder.substring(0, slash);
    }

    static String join(String parent, String name) {
        return parent == null || parent.isEmpty() ? name : parent + "/" + name;
    }

    private static void addWithParents(Set<String> folders, String folder) {
        while (!folder.isEmpty()) {
            folders.add(folder);
            folder = parentOf(folder);
        }
    }

    private static Label heading(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("online-heading");
        return label;
    }

    private static HBox toolbar(Label heading, Node... buttons) {
        heading.getStyleClass().add("online-heading");
        heading.setMaxWidth(Double.MAX_VALUE);
        heading.setTextOverrun(OverrunStyle.ELLIPSIS);
        HBox.setHgrow(heading, Priority.ALWAYS);
        HBox bar = new HBox(2, heading);
        bar.getChildren().addAll(buttons);
        bar.getStyleClass().add("toolbar-row");
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private static Label hint(String text) {
        Label label = new Label(text);
        label.getStyleClass().addAll("muted", "online-hint");
        label.setWrapText(true);
        return label;
    }

    private static Hyperlink linkButton(String text, Runnable action) {
        Hyperlink link = new Hyperlink(text);
        link.setOnAction(e -> action.run());
        return link;
    }

    private static VBox page(Node... children) {
        VBox page = new VBox(8, children);
        page.getStyleClass().add("online-page");
        page.setPadding(new Insets(8, 0, 0, 0));
        for (Node child : children) {
            if (child instanceof TableView<?> || child instanceof TreeView<?> || child instanceof StackPane) {
                VBox.setVgrow(child, Priority.ALWAYS);
            }
        }
        return page;
    }

    private static <S> TableColumn<S, String> column(String title, Function<S, String> value) {
        TableColumn<S, String> column = new TableColumn<>(title);
        column.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(
                c.getValue() == null ? "" : Objects.requireNonNullElse(value.apply(c.getValue()), "")));
        column.setReorderable(false);
        column.setStyle("-fx-alignment: CENTER-LEFT;");
        return column;
    }

    @SafeVarargs
    private static <S> void numeric(TableColumn<S, String>... columns) {
        for (TableColumn<S, String> column : columns) {
            column.setPrefWidth(52);
            column.setMinWidth(44);
            column.setStyle("-fx-alignment: CENTER-RIGHT;");
        }
    }

    private static void styleTable(TableView<?> table) {
        table.getStyleClass().add("online-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        table.setFixedCellSize(28);
    }

    private static MenuItem item(String text, org.kordamp.ikonli.Ikon icon, Runnable action) {
        MenuItem item = new MenuItem(text, Icons.icon(icon));
        item.setOnAction(e -> action.run());
        return item;
    }

    private static ContextMenu menu(MenuItem... items) {
        return new ContextMenu(items);
    }
}
