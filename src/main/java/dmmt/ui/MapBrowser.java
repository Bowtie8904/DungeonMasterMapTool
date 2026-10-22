package dmmt.ui;

import dmmt.log.Log;
import dmmt.service.MapLibraryService;
import dmmt.service.MapLibraryService.Entry;
import dmmt.service.MapTreeFilter;
import dmmt.service.MultiLevelService;
import dmmt.service.RecentMaps;
import dmmt.service.ThumbnailService;
import dmmt.service.Tuning;
import javafx.animation.PauseTransition;
import javafx.css.PseudoClass;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.application.Platform;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Clipboard;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.Duration;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignL;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;
import org.kordamp.ikonli.materialdesign2.MaterialDesignT;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Left sidebar: map actions plus a file-browser style tree of the map library (folders mirror folders on disk).
 */
public class MapBrowser extends VBox
{
    private static final Log LOG = Log.of(MapBrowser.class);
    private dmmt.remote.RemoteOperations.Connection remoteConnection;
    private dmmt.remote.RemoteMaps.Listing remoteListing;
    private final Map<Path, java.util.UUID> remoteIds = new HashMap<>();
    private final Map<Path, Entry> remoteEntries = new HashMap<>();
    private final Map<Path, dmmt.remote.RemoteMaps.Node> remoteNodes = new HashMap<>();
    private final java.util.List<Path> remoteRecents = new java.util.ArrayList<>();
    private boolean remoteCommandPending;
    private long nextRemoteRefresh;
    private java.util.List<dmmt.remote.RemoteMaps.Level> remoteLevels = java.util.List.of();
    private String remoteCurrentLevelId;
    private boolean forceRemoteRefresh = true;
    private Runnable remoteLevelsChanged = () -> { };
    private String remoteLevelsFingerprint = "";
    private Consumer<Throwable> remoteErrorHandler = failure ->
            LOG.warn("Remote map browser failed", failure);
    private final Map<Path, Long> remotePreviewRetryAfter = new HashMap<>();
    private final Map<Path, Object> remotePreviewRequests = new HashMap<>();
    private final Map<String, String> remotePollingFailures = new HashMap<>();
    private dmmt.remote.RemoteMapCatalog.Listing displayedCatalog;
    private java.util.List<dmmt.remote.RemoteMaps.Node> displayedRemoteNodes = java.util.List.of();
    private boolean retryRemotePreviews;

    /** Receives original remote operation/preview failures on the JavaFX application thread. */
    public void setRemoteErrorHandler(Consumer<Throwable> handler) {
        remoteErrorHandler = java.util.Objects.requireNonNull(handler);
    }

    private static void onRemoteUi(Runnable action) {
        if (Platform.isFxApplicationThread())
            action.run();
        else
            Platform.runLater(action);
    }

    private void reportRemoteFailure(String status, Throwable failure) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> reportRemoteFailure(status, failure));
            return;
        }
        Throwable cause = failure;
        while ((cause instanceof java.util.concurrent.CompletionException
                || cause instanceof java.util.concurrent.ExecutionException) && cause.getCause() != null)
            cause = cause.getCause();
        catalogStatus.setText(status + " Use Refresh to retry.");
        remoteErrorHandler.accept(cause);
    }

    private void reportRemotePollingFailure(String operation, String status, Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null)
            cause = cause.getCause();
        String fingerprint = cause.getClass().getName() + ":" + cause.getMessage();
        if (!fingerprint.equals(remotePollingFailures.put(operation, fingerprint)))
            reportRemoteFailure(status, failure);
    }
    public void setRemoteLevelsChanged(Runnable changed) {
        remoteLevelsChanged = java.util.Objects.requireNonNull(changed);
    }
    private final com.fasterxml.jackson.databind.ObjectMapper remoteMapper = dmmt.service.JsonMappers.create();

    /**
     * Full remote backend for the same tree, menus, dialogs and action row.
     */
    public void beginRemote(dmmt.remote.RemoteOperations.Connection connection)
    {
        long generation = catalogGeneration + 1;
        remoteConnection = java.util.Objects.requireNonNull(connection);
        beginRemote(() -> {
            var request = remoteMapper.createObjectNode().put("refresh", forceRemoteRefresh);
            forceRemoteRefresh = false;
            return connection.query("maps", "list", request)
                                    .thenApply(value -> {
                                        try
                                        {
                                            if (generation != catalogGeneration)
                                                throw new java.util.concurrent.CancellationException("The remote map browser was detached.");
                                            remoteListing = remoteMapper.treeToValue(value, dmmt.remote.RemoteMaps.Listing.class);
                                            return new dmmt.remote.RemoteMapCatalog.Listing(remoteListing.nodes().stream()
                                                                                                         .map(n -> new dmmt.remote.RemoteMapCatalog.Node(n.id(), n.parent(), n.name(), n.kind()))
                                                                                                         .toList(),
                                                                                            remoteListing.current(), remoteListing.currentLabel(), remoteListing.context(), null);
                                        }
                                        catch (IOException failure)
                                        {
                                            throw new java.util.concurrent.CompletionException(failure);
                                        }
                                    });
        });
    }

    /**
     * Called by the application's existing UI pulse; never runs client-side library maintenance.
     */
    public void tickRemote()
    {
        if (remoteConnection == null || !remoteConnection.connected() || remoteCommandPending)
            return;
        long now = System.nanoTime();
        if (now >= nextRemoteRefresh)
        {
            nextRemoteRefresh = now + 2_000_000_000L;
            refreshRemote();
        }
    }

    public java.util.List<dmmt.remote.RemoteMaps.Level> remoteLevels()
    {
        return remoteLevels;
    }

    public String remoteCurrentLevelId()
    {
        return remoteCurrentLevelId;
    }

    public void remoteOpenLevel(String id)
    {
        Entry current = remoteEntries.get(remoteCurrent);
        if (current == null || remoteLevels.stream().noneMatch(level -> level.id().equals(id)))
            return;
        remoteWorkflow("openLevel", java.util.List.of(current), null, null, remoteMapper.createObjectNode().put("id", id));
    }

    public void remoteManageCurrentLevels()
    {
        Entry current = remoteEntries.get(remoteCurrent);
        if (current != null && current.isMultiLevel())
            manageLevelsAction(current);
    }

    private void refreshRemoteLevels()
    {
        Entry current = remoteEntries.get(remoteCurrent);
        if (remoteConnection == null || remoteListing == null || current == null || !current.isMultiLevel())
        {
            remoteLevels = java.util.List.of();
            remoteCurrentLevelId = null;
            notifyRemoteLevels();
            return;
        }
        var guard = remoteListing;
        var identity = remoteCurrent;
        long generation = catalogGeneration;
        var command = new dmmt.remote.RemoteMaps.Command(guard.context(), guard.revision(),
                                                         java.util.List.of(remoteIds.get(identity)), null, null, null);
        remoteConnection.query("maps", "levels", remoteMapper.valueToTree(command)).whenComplete((value, failure) -> onRemoteUi(() -> {
            if (generation != catalogGeneration || !java.util.Objects.equals(identity, remoteCurrent)
                    || remoteListing == null || !guard.context().equals(remoteListing.context()))
                return;
            if (failure != null) {
                reportRemotePollingFailure("levels", "Could not read host levels.", failure);
                return;
            }
            remotePollingFailures.remove("levels");
            var levels = new java.util.ArrayList<dmmt.remote.RemoteMaps.Level>();
            for (var level : value.path("levels"))
                levels.add(new dmmt.remote.RemoteMaps.Level(
                        level.path("id").asText(), level.path("name").asText()));
            remoteLevels = java.util.List.copyOf(levels);
            remoteCurrentLevelId = value.path("currentLevelId").isTextual() ? value.path("currentLevelId").asText() : null;
            notifyRemoteLevels();
        }));
    }

    private void notifyRemoteLevels() {
        String fingerprint = remoteLevels.toString() + ":" + remoteCurrentLevelId;
        if (!fingerprint.equals(remoteLevelsFingerprint)) {
            remoteLevelsFingerprint = fingerprint;
            remoteLevelsChanged.run();
        }
    }

    /**
     * Application menu workflows can use the same handle/context/revision guarded dispatch.
     */
    public void remoteWorkflow(String action, java.util.List<Entry> entries, Path destination,
                               String name, com.fasterxml.jackson.databind.JsonNode options)
    {
        sendRemote(action, entries, destination, name, options, remoteListing);
    }

    public void remoteNewMap() { dispatchRemote("new", java.util.List.of(), selectedFolder()); }
    public void remoteImportMaps() { importAction("import", selectedFolder()); }
    public void remoteImportFolder() { importAction("importFolder", selectedFolder()); }
    public void remoteImportMultiLevelMap() { importAction("importMultiLevel", selectedFolder()); }
    public void remoteExportMaps() {
        var maps = selectedMaps();
        Entry current = remoteEntries.get(remoteCurrent);
        if (maps.isEmpty() && current != null) maps = java.util.List.of(current);
        if (!maps.isEmpty()) exportAction(maps);
    }

    private java.util.concurrent.CompletableFuture<com.fasterxml.jackson.databind.JsonNode> sendRemote(String action, java.util.List<Entry> selected, Path destination, String name,
                                                                                                       com.fasterxml.jackson.databind.JsonNode options, dmmt.remote.RemoteMaps.Listing guard)
    {
        if (remoteConnection == null || guard == null || remoteCommandPending || !remoteConnection.connected())
        {
            var failure = new IllegalStateException("Host map operation unavailable. Check the connection and refresh.");
            reportRemoteFailure("Host map operation unavailable.", failure);
            return java.util.concurrent.CompletableFuture.failedFuture(failure);
        }
        try
        {
            var handles = selected.stream().map(e -> java.util.Objects.requireNonNull(remoteIds.get(e.path())))
                                  .toList();
            java.util.UUID target = destination == null ? null : remoteIds.get(destination);
            if (destination != null && target == null)
                throw new IllegalStateException("The destination changed. Refresh and retry.");
            var command = new dmmt.remote.RemoteMaps.Command(guard.context(), guard.revision(), handles, target, name, options);
            long generation = catalogGeneration;
            remoteCommandPending = true;
            LOG.info("Host map operation {} ({} maps)", action, selected.size());
            catalogStatus.setText("Waiting for host map operation...");
            var work = remoteConnection.command("maps", action, remoteMapper.valueToTree(command));
            work.whenComplete((value, failure) -> onRemoteUi(() -> {
                if (generation != catalogGeneration)
                    return;
                remoteCommandPending = false;
                if (failure != null)
                {
                    reportRemoteFailure("Host map operation failed.", failure);
                }
                else if (action.equals("import") || action.equals("importFolder") || action.equals("importMultiLevel")) {
                    String partial = remoteImportFailures(value);
                    if (partial != null) reportRemoteFailure("Some maps were not imported.", new IOException(partial));
                }
                if (failure == null)
                    refresh();
            }));
            return work;
        }

        catch (RuntimeException failure)
        {
            remoteCommandPending = false;
            LOG.warn("Host map operation {} unavailable", action, failure);
            reportRemoteFailure("Host map operation unavailable.", failure);
            return java.util.concurrent.CompletableFuture.failedFuture(failure);
        }
    }

    static String remoteImportFailures(com.fasterxml.jackson.databind.JsonNode report) {
        if (report == null || !report.path("failures").isArray() || report.path("failures").isEmpty()) return null;
        int imported = Math.max(0, report.path("imported").asInt());
        int total = Math.max(imported, report.path("total").asInt());
        var message = new StringBuilder("Imported ").append(imported).append(" of ").append(total)
                .append(" maps on the host.\n\nThe following sources were not imported:\n");
        for (var failure : report.path("failures")) {
            String source = failure.path("source").asText("Unknown source");
            String reason = failure.path("reason").asText("The host could not import this source.");
            message.append('\n').append(source).append(": ").append(reason);
        }
        return message.toString();
    }

    private boolean dispatchRemote(String action, java.util.List<Entry> entries, Path destination)
    {
        if (remoteConnection == null)
            return false;
        if (action.equals("new")) {
            var guard = remoteListing;
            Dialogs.askText(window(), "New map", "Create an empty map in the host library",
                    MaterialDesignM.MAP_PLUS, "Create", "New map", MapLocationDialog::nameProblem)
                    .ifPresent(name -> sendRemote("new", entries, destination, name, null, guard));
            return true;
        }
        if (action.equals("merge") || action.equals("drop"))
        {
            combineRemote(entries, destination);
            return true;
        }
        if (action.equals("dissolve"))
        {
            var guard = remoteListing;
            if (Dialogs.confirmDanger(window(), "Dissolve multilevel map", "Move every level out as a separate map?",
                                      "The multilevel map will be replaced by its individual levels. This cannot be undone.", "Dissolve"))
                sendRemote(action, entries, destination, null, null, guard);
            return true;
        }
        remoteWorkflow(action, entries, destination, null, null);
        return true;
    }

    private void combineRemote(java.util.List<Entry> dragged, Path destination)
    {
        var guard = remoteListing;
        long generation = catalogGeneration;
        var selected = new java.util.ArrayList<Entry>(dragged);
        if (destination != null && remoteEntries.get(destination) != null)
            selected.addFirst(remoteEntries.get(destination));
        Entry target = selected.stream().filter(Entry::isMultiLevel).findFirst().orElse(null);
        var rows = new java.util.ArrayList<LevelListDialog.Row>();
        java.util.concurrent.CompletableFuture<Void> metadata = java.util.concurrent.CompletableFuture.completedFuture(null);
        for (Entry entry : selected)
        {
            if (!entry.isMultiLevel())
            {
                metadata = metadata.thenRun(() -> rows.add(new LevelListDialog.Row(
                        new MultiLevelService.LibraryMap(entry.path(), entry.mapFile()), entry.name(), "Host map")));
            }
            else
            {
                var command = new dmmt.remote.RemoteMaps.Command(guard.context(), guard.revision(),
                                                                 java.util.List.of(remoteIds.get(entry.path())), null, null, null);
                metadata = metadata.thenCompose(ignored -> remoteConnection.query("maps", "levels", remoteMapper.valueToTree(command))
                                                                           .thenAccept(value -> {
                                                                               for (var level : value.path("levels"))
                                                                                   rows.add(new LevelListDialog.Row(
                                                                                           entry == target ? new MultiLevelService.Existing(level.path("id").asText())
                                                                                                           : new MultiLevelService.ForeignLevel(entry.mapFile(), level.path("id").asText()),
                                                                                           level.path("name").asText(), entry.name()));
                                                                           }));
            }
        }
        metadata.whenComplete((ignored, failure) -> Platform.runLater(() -> {
            if (generation != catalogGeneration)
                return;
            if (failure != null)
            {
                reportRemoteFailure("Could not combine host maps.", failure);
                return;
            }
            String name = null;
            if (target == null)
            {
                var requested = Dialogs.askText(window(), "New multilevel map", "Name of the combined map",
                                                MaterialDesignL.LAYERS_PLUS, "Continue", selected.getFirst().name() + " multilevel", MapLocationDialog::nameProblem);
                if (requested.isEmpty())
                    return;
                name = requested.get();
            }
            Entry projection = remoteRoot;
            var projectedLibrary = new MapLibraryService(projection.path(), new dmmt.service.ProjectService())
            {
                @Override
                public Entry scan()
                {
                    return projection;
                }
            };
            String finalName = name;
            LevelListDialog.show(window(), projectedLibrary, "Combine host maps", "Arrange the combined levels",
                                 MaterialDesignL.LAYERS_PLUS, "Combine", rows, target != null, this::pickLocalLevels)
                           .ifPresent(result -> {
                               var options = remoteMapper.createObjectNode();
                               var plan = options.putArray("plan");
                               var uploads = new java.util.ArrayList<java.util.Map.Entry<com.fasterxml.jackson.databind.node.ObjectNode, Path>>();
                               for (var item : result.plan())
                               {
                                   var row = plan.addObject().put("name", item.name());
                                   if (item.source() instanceof MultiLevelService.Existing existing)
                                       row.put("id", existing.levelId());
                                   else if (item.source() instanceof MultiLevelService.ForeignLevel foreign)
                                   {
                                       int index = -1;
                                       for (int i = 0; i < selected.size(); i++)
                                           if (selected.get(i).mapFile().equals(foreign.manifestFile()))
                                               index = i;
                                       row.put("entry", index).put("levelId", foreign.levelId());
                                   }
                                   else if (item.source() instanceof MultiLevelService.LibraryMap map)
                                   {
                                       Entry source = remoteEntries.get(map.entryPath());
                                       int index = selected.indexOf(source);
                                       if (index < 0)
                                       {
                                           index = selected.size();
                                           selected.add(source);
                                       }
                                       row.put("entry", index);
                                   }
                                   else if (item.source() instanceof MultiLevelService.Dd2vtt imported) {
                                       row.put("sourceName", imported.file().getFileName().toString());
                                       uploads.add(java.util.Map.entry(row, imported.file()));
                                   }
                                   else
                                       row.put("empty", true);
                               }
                               options.set("extractions", remoteMapper.valueToTree(result.extractions()));
                               var connection = remoteConnection;
                               java.util.concurrent.CompletableFuture<Void> transfer = java.util.concurrent.CompletableFuture.completedFuture(null);
                               for (var upload : uploads) transfer = transfer.thenCompose(done ->
                                       connection.upload(upload.getValue()).thenAccept(asset -> upload.getKey().set("asset", remoteMapper.valueToTree(asset))));
                               transfer.whenComplete((done, uploadFailure) -> Platform.runLater(() -> {
                                   if (generation != catalogGeneration) return;
                                   if (uploadFailure != null) reportRemoteFailure("Could not upload map level.", uploadFailure);
                                   else sendRemote("merge", selected, null, finalName, options, guard);
                               }));
                           });
        }));
    }

    private void remoteTags(java.util.List<Entry> maps)
    {
        var guard = remoteListing;
        var known = remoteNodes.values().stream().flatMap(node -> node.tags().stream()).distinct().sorted().toList();
        MapTagsDialog.show(window(), maps.getFirst().name(), maps.size(), maps.getFirst().isMultiLevel(),
                           maps.size() == 1 ? maps.getFirst().tags() : java.util.List.of(), known).ifPresent(result ->
                                                                                                                     sendRemote("tags", maps, null, null, remoteMapper.valueToTree(result), guard));
    }

    private java.util.List<Path> pickLocalLevels(Window owner) {
        var chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Add local map levels to the host");
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("Maps", "*.dd2vtt", "*.uvtt"));
        var files = chooser.showOpenMultipleDialog(owner);
        return files == null ? java.util.List.of() : files.stream().map(java.io.File::toPath).toList();
    }

    static java.util.List<Path> prepareRemoteLevelImport(LevelListDialog.Result edited,
            com.fasterxml.jackson.databind.node.ObjectNode options) {
        if (edited.plan().size() < 2 || edited.plan().size() > 512)
            throw new IllegalArgumentException("Import between 2 and 512 levels.");
        var sources = new java.util.ArrayList<Path>();
        var plan = options.putArray("plan");
        var names = options.putArray("levelNames");
        for (var item : edited.plan()) {
            var row = plan.addObject().put("name", item.name());
            if (item.source() instanceof MultiLevelService.Dd2vtt imported) {
                row.put("source", sources.size());
                sources.add(imported.file());
                names.add(item.name());
            } else if (item.source() instanceof MultiLevelService.Empty) row.put("source", -1);
            else throw new IllegalArgumentException("An initial import must use local source files or empty levels.");
        }
        return java.util.List.copyOf(sources);
    }

    private Image remoteThumbnail(Path mapFile)
    {
        Path key = mapFile.toAbsolutePath().normalize();
        Thumbnail cached = thumbnails.get(key);
        if (cached != null)
            return cached.image();
        if (remoteListing == null || !remoteIds.containsKey(key)
                || System.nanoTime() < remotePreviewRetryAfter.getOrDefault(key, 0L)
                || remotePreviewRequests.size() >= 3
                || !loadingThumbnails.add(key))
            return null;
        long generation = catalogGeneration;
        var node = remoteNodes.get(key);
        var context = remoteListing.context();
        var connection = remoteConnection;
        Object request = new Object();
        remotePreviewRequests.put(key, request);
        var command = new dmmt.remote.RemoteMaps.Command(remoteListing.context(), remoteListing.revision(),
                                                         java.util.List.of(remoteIds.get(key)), null, null, null);
        java.util.concurrent.CompletableFuture<com.fasterxml.jackson.databind.JsonNode> query;
        try {
            query = connection.query("maps", "preview", remoteMapper.valueToTree(command));
        } catch (RuntimeException failure) {
            query = java.util.concurrent.CompletableFuture.failedFuture(failure);
        }
        query.thenCompose(value -> {
            if (value == null || value.isNull())
                return java.util.concurrent.CompletableFuture.<Path>completedFuture(null);
            try
            {
                return connection.download(remoteMapper.treeToValue(value, dmmt.remote.RemoteAssetStore.AssetDescriptor.class),
                        "Map preview");
            }
            catch (IOException failure)
            {
                return java.util.concurrent.CompletableFuture.<Path>failedFuture(failure);
            }
        }).thenApplyAsync(path -> {
            if (path == null)
                return (Image)null;
            try
            {
                return dmmt.remote.RemoteImages.read(path, true, 256);
            }
            catch (IOException failure)
            {
                throw new java.util.concurrent.CompletionException(failure);
            }
        }, thumbnailLoader).whenComplete((image, failure) -> Platform.runLater(() -> {
            if (generation != catalogGeneration || remotePreviewRequests.get(key) != request)
                return;
            remotePreviewRequests.remove(key);
            loadingThumbnails.remove(key);
            if (remoteListing != null && context.equals(remoteListing.context())
                    && java.util.Objects.equals(node, remoteNodes.get(key)))
            {
                if (failure != null) {
                    remotePreviewRetryAfter.put(key, System.nanoTime() + 30_000_000_000L);
                    Throwable cause = failure;
                    while (cause.getCause() != null)
                        cause = cause.getCause();
                    // A stale revision just means the host library changed; the next listing refresh retries quietly.
                    if (cause instanceof IllegalStateException)
                        remotePreviewRetryAfter.put(key, System.nanoTime() + 2_000_000_000L);
                    else
                        reportRemoteFailure("Could not load a host map preview.", failure);
                    tree.refresh();
                    recentList.refresh();
                    return;
                }
                remotePreviewRetryAfter.remove(key);
                thumbnails.put(key, new Thumbnail(image, node == null ? -1 : node.stamp(), node == null ? 0 : node.levels()));
                tree.refresh();
                recentList.refresh();
            }
        }));
        return null;
    }

    private void importAction(String action, Path destination)
    {
        if (remoteConnection == null)
        {
            localLibraryAction(() -> {
                switch (action)
                {
                    case "importFolder" -> host.importMapFolder(destination);
                    case "importMultiLevel" -> host.importMultiLevelMap(destination);
                    default -> host.importMap(destination);
                }
            });
            return;
        }
        var guard = remoteListing;
        var connection = remoteConnection;
        long generation = catalogGeneration;
        Path root;
        java.util.List<Path> files;
        if (action.equals("importFolder"))
        {
            var chooser = new javafx.stage.DirectoryChooser();
            chooser.setTitle("Import maps from a local folder to the host");
            var chosen = chooser.showDialog(window());
            if (chosen == null)
                return;
            root = chosen.toPath();
            files = null;
        }
        else
        {
            var chooser = new javafx.stage.FileChooser();
            chooser.setTitle("Import local maps to the host");
            chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("Maps", "*.dd2vtt", "*.uvtt", "*.dmmpack"));
            var chosen = chooser.showOpenMultipleDialog(window());
            if (chosen == null || chosen.isEmpty())
                return;
            files = chosen.stream().map(java.io.File::toPath).toList();
            root = null;
        }
        if (remoteCommandPending)
            return;
        remoteCommandPending = true;
        catalogStatus.setText("Uploading maps to the host...");
        var selected = files;
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try
            {
                if (selected != null)
                {
                    if (selected.size() > 512)
                        throw new IOException("Choose at most 512 maps.");
                    return selected;
                }
                try (var walk = Files.walk(root, 25))
                {
                    var found = walk.filter(p -> Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                                    .filter(p -> {
                                        String n = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                                        return n.endsWith(".dd2vtt") || n.endsWith(".uvtt") || n.endsWith(".dmmpack");
                                    }).limit(513).toList();
                    if (found.size() > 512)
                        throw new IOException("Choose at most 512 maps.");
                    return found;
                }
            }
            catch (IOException failure)
            {
                throw new java.util.concurrent.CompletionException(failure);
            }
        }, thumbnailLoader).whenComplete((sources, scanFailure) -> Platform.runLater(() -> {
            if (generation != catalogGeneration)
                return;
            if (scanFailure != null)
            {
                remoteCommandPending = false;
                reportRemoteFailure("Could not import maps.", scanFailure);
                return;
            }
            if (action.equals("importMultiLevel")) finishRemoteImport(action, destination, root, sources, guard, generation,
                    connection, remoteMapper.createObjectNode());
            else preflightRemoteImport(sources, guard, generation, (approved, preflight) ->
                    finishRemoteImport(action, destination, root, approved, guard, generation, connection, preflight));
        }));
    }

    private void preflightRemoteImport(java.util.List<Path> sources, dmmt.remote.RemoteMaps.Listing guard, long generation,
            java.util.function.BiConsumer<java.util.List<Path>, com.fasterxml.jackson.databind.JsonNode> accepted) {
        preflightRemoteImport(sources, guard, generation, false, accepted);
    }

    private void preflightRemoteImport(java.util.List<Path> sources, dmmt.remote.RemoteMaps.Listing guard, long generation,
            boolean keepEmptyLevels, java.util.function.BiConsumer<java.util.List<Path>, com.fasterxml.jackson.databind.JsonNode> accepted) {
        if (sources.isEmpty()) {
            remoteCommandPending = false;
            catalogStatus.setText("No supported map files were found.");
            return;
        }
        var options = remoteMapper.createObjectNode();
        options.set("names", remoteMapper.valueToTree(sources.stream().map(p -> p.getFileName().toString()).toList()));
        var command = new dmmt.remote.RemoteMaps.Command(guard.context(), guard.revision(), java.util.List.of(), null, null, options);
        remoteConnection.query("maps", "importPreflight", remoteMapper.valueToTree(command)).whenComplete((report, failure) -> {
            if (generation != catalogGeneration) return;
            if (failure != null) {
                remoteCommandPending = false;
                reportRemoteFailure("Could not check host duplicate maps.", failure);
                return;
            }
            var names = new HashSet<String>();
            report.path("duplicates").forEach(n -> names.add(n.asText()));
            var duplicates = sources.stream().filter(p -> names.contains(p.getFileName().toString())).toList();
            java.util.Set<Path> approved = Set.of();
            String behavior = report.path("behavior").asText("ask");
            if ("always".equals(behavior)) approved = Set.copyOf(duplicates);
            else if (!"never".equals(behavior) && !duplicates.isEmpty()) {
                var decision = DuplicateMapsDialog.show(window(), duplicates);
                if (decision.isEmpty()) { remoteCommandPending = false; catalogStatus.setText(""); return; }
                approved = decision.get().accepted();
            }
            var chosen = approved;
            var filtered = sources.stream().filter(p -> !duplicates.contains(p) || chosen.contains(p)).toList();
            if (filtered.isEmpty() && !keepEmptyLevels) {
                remoteCommandPending = false;
                catalogStatus.setText("Nothing to import: every selected map is already in the host library.");
                return;
            }
            var prepared = report.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) prepared).set("duplicateAccepted",
                    remoteMapper.valueToTree(chosen.stream().map(p -> p.getFileName().toString()).toList()));
            accepted.accept(filtered, prepared);
        });
    }

    private MapLibraryService remoteLocationLibrary() {
        Entry projection = remoteRoot;
        return new MapLibraryService(projection.path(), new dmmt.service.ProjectService()) {
            @Override public Entry scanFolders() { return projection; }
            @Override public Path newMapFile(Path folder, String name) throws IOException {
                return folder.resolve(MapLibraryService.cleanName(name) + MapLibraryService.EXTENSION);
            }
            @Override public Path createFolder(Path parent, String name) throws IOException {
                throw new IOException("Create host folders in the map browser, then reopen this location dialog.");
            }
        };
    }

    private void finishRemoteImport(String action, Path destination, Path root, java.util.List<Path> sources,
            dmmt.remote.RemoteMaps.Listing guard, long generation, dmmt.remote.RemoteOperations.Connection connection,
            com.fasterxml.jackson.databind.JsonNode preflight) {
            var options = remoteMapper.createObjectNode();
            options.set("preflight", preflight.path("preflight"));
            options.set("duplicateAccepted", preflight.path("duplicateAccepted"));
            options.put("multiLevel", action.equals("importMultiLevel"));
            options.put("batch", action.equals("importFolder") || sources.size() != 1);
            Path targetFolder = destination;
            if (action.equals("import") && sources.size() == 1
                    && !sources.getFirst().getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".dmmpack")) {
                var location = MapLocationDialog.show(window(), remoteLocationLibrary(), "Import map", MaterialDesignF.FILE_IMPORT_OUTLINE,
                        "Import", MapLibraryService.stripExtension(sources.getFirst().getFileName().toString()), targetFolder);
                if (location.isEmpty()) { remoteCommandPending = false; catalogStatus.setText(""); return; }
                targetFolder = location.get().folder();
                options.put("name", location.get().name());
            } else if (!action.equals("importMultiLevel")) {
                var location = MapLocationDialog.showFolder(window(), remoteLocationLibrary(), "Import maps",
                        MaterialDesignF.FILE_IMPORT_OUTLINE, "Import", targetFolder);
                if (location.isEmpty()) { remoteCommandPending = false; catalogStatus.setText(""); return; }
                targetFolder = location.get();
            }
            java.util.List<Path> transferSources = sources;
            if (action.equals("importMultiLevel")) {
                var name = MapLocationDialog.show(window(), remoteLocationLibrary(), "Import multilevel map",
                        MaterialDesignL.LAYERS_PLUS, "Continue", "Multilevel map", targetFolder);
                if (name.isEmpty()) { remoteCommandPending = false; return; }
                options.put("name", name.get().name());
                targetFolder = name.get().folder();
                var names = MultiLevelService.defaultLevelNames(sources.stream()
                        .map(p -> MapLibraryService.stripExtension(p.getFileName().toString())).toList());
                var rows = new java.util.ArrayList<LevelListDialog.Row>();
                for (int i = 0; i < sources.size(); i++)
                    rows.add(new LevelListDialog.Row(new MultiLevelService.Dd2vtt(sources.get(i)), names.get(i),
                            sources.get(i).getFileName().toString()));
                Entry projection = remoteRoot;
                var projectedLibrary = new MapLibraryService(projection.path(), new dmmt.service.ProjectService()) {
                    @Override public Entry scan() {
                        return new Entry(MapLibraryService.Kind.FOLDER, projection.name(), projection.path(), null, java.util.List.of());
                    }
                };
                var edited = LevelListDialog.show(window(), projectedLibrary, "Import multilevel map", name.get().name(),
                        MaterialDesignL.LAYERS_PLUS, "Import", rows, false, this::pickLocalLevels);
                if (edited.isEmpty()) { remoteCommandPending = false; catalogStatus.setText(""); return; }
                var prepared = prepareRemoteLevelImport(edited.get(), options);
                transferSources = prepared;
            }
            Path acceptedFolder = targetFolder;
            if (action.equals("importMultiLevel") && !transferSources.isEmpty()) {
                var original = transferSources;
                preflightRemoteImport(original, guard, generation, true, (approved, result) -> {
                    options.set("preflight", result.path("preflight"));
                    options.set("duplicateAccepted", result.path("duplicateAccepted"));
                    var retained = remoteMapper.createArrayNode();
                    var levelNames = remoteMapper.createArrayNode();
                    for (var level : options.path("plan")) {
                        int index = level.path("source").asInt(-1);
                        if (index == -1) retained.add(level);
                        else {
                            int replacement = approved.indexOf(original.get(index));
                            if (replacement >= 0) {
                                var row = level.deepCopy();
                                ((com.fasterxml.jackson.databind.node.ObjectNode) row).put("source", replacement);
                                retained.add(row);
                            }
                        }
                    }
                    for (Path source : approved) {
                        int index = original.indexOf(source);
                        for (var level : options.path("plan"))
                            if (level.path("source").asInt(-1) == index) { levelNames.add(level.path("name").asText()); break; }
                    }
                    if (retained.size() < 2) {
                        remoteCommandPending = false;
                        catalogStatus.setText("Fewer than two levels remain after duplicate filtering. Reopen the import and choose more levels.");
                        return;
                    }
                    options.set("plan", retained);
                    options.set("levelNames", levelNames);
                    uploadRemoteImports(action, acceptedFolder, root, approved, options, guard, generation, connection);
                });
            } else uploadRemoteImports(action, acceptedFolder, root, transferSources, options, guard, generation, connection);
    }

    private void uploadRemoteImports(String action, Path acceptedFolder, Path root, java.util.List<Path> sources,
            com.fasterxml.jackson.databind.node.ObjectNode options, dmmt.remote.RemoteMaps.Listing guard,
            long generation, dmmt.remote.RemoteOperations.Connection connection) {
            var uploads = options.putArray("sources");
            java.util.concurrent.CompletableFuture<Void> chain = java.util.concurrent.CompletableFuture.completedFuture(null);
            for (Path file : sources)
            {
                chain = chain.thenCompose(ignored -> connection.upload(file).thenAccept(asset -> {
                    var item = uploads.addObject().put("name", file.getFileName().toString());
                    if (root != null && !root.equals(file.getParent()))
                        item.put("relative", root.relativize(file.getParent()).toString().replace('\\', '/'));
                    item.set("asset", remoteMapper.valueToTree(asset));
                }));
            }
            chain.whenComplete((ignored, failure) -> Platform.runLater(() -> {
                if (generation != catalogGeneration)
                    return;
                remoteCommandPending = false;
                if (failure != null)
                    reportRemoteFailure("Could not upload maps.", failure);
                else if (!sources.isEmpty() || options.has("plan"))
                    sendRemote(action, java.util.List.of(), acceptedFolder, null, options, guard);
                else
                    catalogStatus.setText("No supported map files were found in that folder.");
            }));
    }

    private void exportAction(java.util.List<Entry> maps)
    {
        if (remoteConnection == null)
        {
            LOG.info("Exporting {} maps", maps.size());
            host.exportMaps(maps);
            return;
        }
        var guard = remoteListing;
        var connection = remoteConnection;
        var chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Export host maps to this PC");
        chooser.setInitialFileName(maps.size() == 1 ? maps.getFirst().name() + ".dmmpack" : "maps.dmmpack");
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("Map pack", "*.dmmpack"));
        var destination = chooser.showSaveDialog(window());
        if (destination == null)
            return;
        sendRemote("export", maps, null, null, null, guard).thenCompose(value -> {
            try
            {
                return connection.download(remoteMapper.treeToValue(value, dmmt.remote.RemoteAssetStore.AssetDescriptor.class),
                        maps.size() == 1 ? "Map pack: " + maps.getFirst().name() : "Map pack");
            }
            catch (IOException failure)
            {
                return java.util.concurrent.CompletableFuture.<Path>failedFuture(failure);
            }
        }).thenAcceptAsync(file -> {
            try
            {
                Files.copy(file, destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            catch (IOException failure)
            {
                throw new java.util.concurrent.CompletionException(failure);
            }
        }, thumbnailLoader).whenComplete((ignored, failure) -> {
            if (failure != null)
                reportRemoteFailure("Could not export maps.", failure);
        });
    }

    private void manageLevelsAction(Entry entry)
    {
        if (remoteConnection == null)
        {
            host.manageLevels(entry.mapFile());
            return;
        }
        var guard = remoteListing;
        var command = new dmmt.remote.RemoteMaps.Command(guard.context(), guard.revision(),
                                                         java.util.List.of(remoteIds.get(entry.path())), null, null, null);
        remoteConnection.query("maps", "levels", remoteMapper.valueToTree(command)).whenComplete((value, failure) -> {
            if (remoteConnection == null)
                return;
            if (failure != null)
            {
                reportRemoteFailure("Could not read host levels.", failure);
                return;
            }
            var rows = new java.util.ArrayList<LevelListDialog.Row>();
            for (var level : value.path("levels"))
                rows.add(new LevelListDialog.Row(
                        new MultiLevelService.Existing(level.path("id").asText()), level.path("name").asText(), "Host level"));
            Entry projection = remoteRoot;
            var projectedLibrary = new MapLibraryService(projection.path(), new dmmt.service.ProjectService())
            {
                @Override
                public Entry scan()
                {
                    return projection;
                }
            };
            LevelListDialog.show(window(), projectedLibrary, "Manage host levels", entry.name(), MaterialDesignL.LAYERS_TRIPLE_OUTLINE,
                                 "Apply", rows, true, owner -> {
                        var chooser = new javafx.stage.FileChooser();
                        chooser.setTitle("Add local map levels to the host");
                        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("Maps", "*.dd2vtt", "*.uvtt"));
                        var files = chooser.showOpenMultipleDialog(owner);
                        return files == null ? java.util.List.of() : files.stream().map(java.io.File::toPath).toList();
                    }).ifPresent(result -> {
                var options = remoteMapper.createObjectNode();
                var plan = options.putArray("plan");
                var selected = new java.util.ArrayList<Entry>();
                selected.add(entry);
                var uploads = new java.util.ArrayList<java.util.Map.Entry<com.fasterxml.jackson.databind.node.ObjectNode, Path>>();
                for (var item : result.plan())
                {
                    var row = plan.addObject().put("name", item.name());
                    if (item.source() instanceof MultiLevelService.Existing existing)
                        row.put("id", existing.levelId());
                    else if (item.source() instanceof MultiLevelService.LibraryMap map)
                    {
                        Entry source = remoteEntries.get(map.entryPath());
                        if (source == null)
                        {
                            catalogStatus.setText("A selected map changed. Reopen the level dialog.");
                            return;
                        }
                        int index = selected.indexOf(source);
                        if (index < 0)
                        {
                            index = selected.size();
                            selected.add(source);
                        }
                        row.put("entry", index);
                    }
                    else if (item.source() instanceof MultiLevelService.Dd2vtt imported)
                    {
                        row.put("sourceName", imported.file().getFileName().toString());
                        uploads.add(java.util.Map.entry(row, imported.file()));
                    }
                    else
                        row.put("empty", true);
                }
                options.set("extractions", remoteMapper.valueToTree(result.extractions()));
                var connection = remoteConnection;
                long generation = catalogGeneration;
                java.util.concurrent.CompletableFuture<Void> transfer = java.util.concurrent.CompletableFuture.completedFuture(null);
                for (var upload : uploads)
                    transfer = transfer.thenCompose(ignored ->
                                                            connection.upload(upload.getValue()).thenAccept(asset -> upload.getKey().set("asset", remoteMapper.valueToTree(asset))));
                transfer.whenComplete((ignored, uploadFailure) -> Platform.runLater(() -> {
                    if (generation != catalogGeneration)
                        return;
                    if (uploadFailure != null)
                        reportRemoteFailure("Could not upload map level.", uploadFailure);
                    else
                        sendRemote("manageLevels", selected, null, null, options, guard);
                }));
            });
        });
    }

    private java.util.function.Supplier<java.util.concurrent.CompletableFuture<dmmt.remote.RemoteMapCatalog.Listing>> remoteCatalog;
    private java.util.concurrent.CompletableFuture<dmmt.remote.RemoteMapCatalog.Listing> catalogWork;
    private final Label catalogStatus = new Label();
    private Path remoteCurrent;
    private String remoteCurrentLabel = "Synchronizing host map";
    private Entry remoteRoot;
    private java.util.UUID displayedContext, catalogContext;
    private volatile long catalogGeneration;
    private LocalBrowser retainedBrowser;

    private record LocalBrowser(Entry root, String search, Set<Path> expanded, java.util.List<Path> selected,
                                Map<Path, Thumbnail> thumbnails)
    {
    }

    /**
     * Reuse this browser's tree/search without ever interpreting host paths on the client.
     */
    public void beginRemote(java.util.function.Supplier<java.util.concurrent.CompletableFuture<dmmt.remote.RemoteMapCatalog.Listing>> query)
    {
        if (retainedBrowser != null)
            throw new IllegalStateException("The map browser is already remote.");
        var selected = tree.getSelectionModel().getSelectedItems().stream()
                           .filter(item -> item != null && item.getValue() != null).map(item -> item.getValue().path()).toList();
        retainedBrowser = new LocalBrowser(scannedRoot, searchField.getText(), Set.copyOf(expandedFolders),
                                           selected, Map.copyOf(thumbnails));
        remoteCatalog = java.util.Objects.requireNonNull(query);
        catalogGeneration++;
        closeLibraryMenu();
        expandedFolders.clear();
        thumbnails.clear();
        loadingThumbnails.clear();
        remotePreviewRequests.clear();
        remotePreviewRetryAfter.clear();
        remotePollingFailures.clear();
        displayedCatalog = null;
        displayedRemoteNodes = java.util.List.of();
        scannedRoot = remoteRoot = null;
        remoteCurrent = null;
        displayedContext = catalogContext = null;
        searchField.clear();
        tree.setRoot(null);
        resetTreeViewport = true;
        noResults.setVisible(false);
        noResults.setManaged(false);
        recentList.getItems().clear();
        previousMap = null;
        previousMapButton.setDisable(true);
        updateCurrentMap();
        refresh();
    }

    public void endRemote()
    {
        if (retainedBrowser == null)
            return;
        catalogGeneration++;
        if (catalogWork != null)
            catalogWork.cancel(false);
        catalogWork = null;
        remoteCatalog = null;
        remoteConnection = null;
        remoteListing = null;
        displayedCatalog = null;
        displayedRemoteNodes = java.util.List.of();
        remotePreviewRequests.clear();
        remotePreviewRetryAfter.clear();
        remotePollingFailures.clear();
        retryRemotePreviews = false;
        remoteCommandPending = false;
        remoteIds.clear();
        remoteEntries.clear();
        remoteNodes.clear();
        remoteRecents.clear();
        remoteLevels = java.util.List.of();
        remoteCurrentLevelId = null;
        notifyRemoteLevels();
        remoteRoot = null;
        remoteCurrent = null;
        displayedContext = catalogContext = null;
        remoteCurrentLabel = "Synchronizing host map";
        closeLibraryMenu();
        var local = retainedBrowser;
        retainedBrowser = null;
        scannedRoot = local.root();
        expandedFolders.clear();
        expandedFolders.addAll(local.expanded());
        thumbnails.clear();
        thumbnails.putAll(local.thumbnails());
        loadingThumbnails.clear();
        catalogStatus.setText("");
        searchField.setText(local.search());
        rebuildTree();
        tree.getSelectionModel().clearSelection();
        for (Path selected : local.selected())
        {
            TreeItem<Entry> item = tree.getRoot() == null ? null : findItem(tree.getRoot(), selected.toAbsolutePath().normalize());
            if (item != null)
                tree.getSelectionModel().select(item);
        }
    }

    private void closeLibraryMenu()
    {
        if (openMenu != null)
            openMenu.hide();
        draggedEntries = null;
    }

    private void refreshRemote()
    {
        if (catalogWork != null)
            return;
        if (displayedCatalog == null && !remotePollingFailures.containsKey("catalog"))
            catalogStatus.setText("Loading host map library...");
        long generation = catalogGeneration;
        try {
            catalogWork = remoteCatalog.get();
        } catch (RuntimeException failure) {
            reportRemotePollingFailure("catalog", "Could not read the host library.", failure);
            return;
        }
        catalogWork.whenComplete((listing, failure) -> Platform.runLater(() -> {
            if (generation != catalogGeneration || remoteCatalog == null)
                return;
            catalogWork = null;
            if (failure != null || listing == null || listing.error() != null)
            {
                reportRemotePollingFailure("catalog", "Could not read the host library.",
                        failure != null ? failure : new IOException(listing == null ? "The host returned no library listing." : listing.error()));
                return;
            }
            remotePollingFailures.remove("catalog");
            var richerNodes = remoteListing == null ? java.util.List.<dmmt.remote.RemoteMaps.Node>of() : remoteListing.nodes();
            boolean treeChanged = displayedCatalog == null || !listing.context().equals(displayedCatalog.context())
                    || !listing.nodes().equals(displayedCatalog.nodes()) || !richerNodes.equals(displayedRemoteNodes);
            boolean currentChanged = displayedCatalog == null || !java.util.Objects.equals(listing.current(), displayedCatalog.current())
                    || !java.util.Objects.equals(listing.currentLabel(), displayedCatalog.currentLabel());
            var oldRecents = java.util.List.copyOf(remoteRecents);
            if (!treeChanged) {
                remoteRecents.clear();
                if (remoteListing != null)
                    remoteListing.recent().stream().map(id -> remoteIds.entrySet().stream()
                            .filter(entry -> entry.getValue().equals(id)).map(Map.Entry::getKey).findFirst().orElse(null))
                            .filter(java.util.Objects::nonNull).forEach(remoteRecents::add);
                remoteCurrent = listing.current() == null ? null : remoteIds.entrySet().stream()
                        .filter(entry -> entry.getValue().equals(listing.current())).map(Map.Entry::getKey).findFirst().orElse(null);
                remoteCurrentLabel = listing.currentLabel();
                displayedCatalog = listing;
                invalidateStaleCatalogContext();
                if (currentChanged)
                    updateCurrentMap();
                else if (!oldRecents.equals(remoteRecents))
                    updateRecentList();
                if (retryRemotePreviews) {
                    retryRemotePreviews = false;
                    tree.refresh();
                    recentList.refresh();
                }
                refreshRemoteLevels();
                if (!remoteCommandPending && remotePreviewRetryAfter.isEmpty() && remotePollingFailures.isEmpty())
                    catalogStatus.setText(remoteConnection == null ? "Host library is read-only." : "");
                return;
            }
            var entries = new java.util.LinkedHashMap<java.util.UUID, Entry>();
            var paths = new java.util.HashMap<java.util.UUID, Path>();
            var oldNodes = new HashMap<>(remoteNodes);
            remoteIds.clear();
            remoteEntries.clear();
            remoteNodes.clear();
            var richer = new HashMap<java.util.UUID, dmmt.remote.RemoteMaps.Node>();
            if (remoteListing != null)
                remoteListing.nodes().forEach(n -> richer.put(n.id(), n));
            for (var node : listing.nodes())
            {
                Path parent = node.parent() == null ? Path.of(".dmmt-remote-library") : paths.get(node.parent());
                String suffix = switch (node.kind())
                {
                    case FOLDER -> "";
                    case MAP -> ".dmmap";
                    case MULTILEVEL -> ".dmlevels";
                };
                Path path = parent.resolve(node.id() + suffix).toAbsolutePath().normalize();
                paths.put(node.id(), path);
                var entry = new Entry(node.kind() == dmmt.remote.RemoteMapCatalog.Kind.FOLDER
                                      ? MapLibraryService.Kind.FOLDER : MapLibraryService.Kind.MAP, node.name(), path,
                                      node.kind() == dmmt.remote.RemoteMapCatalog.Kind.FOLDER ? null : path,
                                      new java.util.ArrayList<>(), richer.containsKey(node.id()) ? richer.get(node.id()).tags() : java.util.List.of());
                entries.put(node.id(), entry);
                remoteIds.put(path, node.id());
                remoteEntries.put(path, entry);
                if (richer.containsKey(node.id()))
                    remoteNodes.put(path, richer.get(node.id()));
                if (node.parent() != null)
                    entries.get(node.parent()).children().add(entry);
            }
            boolean contextChanged = displayedCatalog != null && !listing.context().equals(displayedCatalog.context());
            thumbnails.entrySet().removeIf(item -> contextChanged || !java.util.Objects.equals(oldNodes.get(item.getKey()), remoteNodes.get(item.getKey())));
            remotePreviewRequests.keySet().removeIf(key -> {
                if (!contextChanged && java.util.Objects.equals(oldNodes.get(key), remoteNodes.get(key)))
                    return false;
                loadingThumbnails.remove(key);
                return true;
            });
            remotePreviewRetryAfter.keySet().removeIf(key -> contextChanged || !java.util.Objects.equals(oldNodes.get(key), remoteNodes.get(key)));
            remoteRecents.clear();
            if (remoteListing != null)
                remoteListing.recent().stream().map(paths::get).filter(java.util.Objects::nonNull)
                             .forEach(remoteRecents::add);
            remoteRoot = entries.get(listing.nodes().getFirst().id());
            scannedRoot = remoteRoot;
            remoteCurrent = paths.get(listing.current());
            remoteCurrentLabel = listing.currentLabel();
            catalogContext = listing.context();
            displayedCatalog = listing;
            displayedRemoteNodes = java.util.List.copyOf(richerNodes);
            retryRemotePreviews = false;
            invalidateStaleCatalogContext();
            rebuildTree();
            refreshRemoteLevels();
            if (!remoteCommandPending && remotePreviewRetryAfter.isEmpty() && remotePollingFailures.isEmpty())
                catalogStatus.setText(remoteConnection == null ? "Host library is read-only." : "");
        }));
    }

    private boolean remoteActionUnavailable()
    {
        if (remoteConnection != null)
            return false;
        if (remoteCatalog == null)
            return false;
        catalogStatus.setText("This host library action is not integrated yet. No local file operation was started.");
        return true;
    }

    private void localLibraryAction(Runnable action)
    {
        if (remoteConnection != null)
            action.run();
        else if (!remoteActionUnavailable())
            action.run();
    }

    private void openMap(Path path)
    {
        if (remoteConnection != null)
        {
            Entry entry = remoteEntries.get(path);
            if (entry != null)
                dispatchRemote("open", java.util.List.of(entry), null);
            return;
        }
        if (!remoteActionUnavailable())
            host.openMap(path);
    }

    private Path libraryRoot()
    {
        return remoteCatalog == null ? library.getRoot() : remoteRoot == null ? null : remoteRoot.path();
    }

    private Path currentMapFile()
    {
        return remoteCatalog == null ? host.currentMapFile() : remoteCurrent;
    }

    public void remoteContext(java.util.UUID context)
    {
        if (remoteCatalog == null)
            return;
        displayedContext = context;
        if (!java.util.Objects.equals(context, catalogContext))
        {
            remoteLevels = java.util.List.of();
            remoteCurrentLevelId = null;
            notifyRemoteLevels();
        }
        invalidateStaleCatalogContext();
        updateCurrentMap();
    }

    private void invalidateStaleCatalogContext()
    {
        if (displayedContext != null && catalogContext != null && !displayedContext.equals(catalogContext))
        {
            remoteCurrent = null;
            remoteCurrentLabel = "Host map changed - refresh library";
        }
    }

    private Function<Path, String> apiUrlProvider;
    private boolean apiUrlOptionsVisible;

    /**
     * Supplies the map-switch URL; failures are displayed rather than silently copying an invalid URL.
     */
    public void setApiUrlProvider(Function<Path, String> apiUrlProvider)
    {
        this.apiUrlProvider = apiUrlProvider;
    }

    public void setApiUrlOptionsVisible(boolean visible)
    {
        apiUrlOptionsVisible = visible;
    }

    private void copyApiUrl(Path mapFile)
    {
        if (remoteActionUnavailable())
            return;
        try
        {
            String url = apiUrlProvider.apply(mapFile);
            if (url == null || url.isBlank())
            {
                throw new IllegalStateException("The local API URL is not available.");
            }
            ClipboardContent content = new ClipboardContent();
            content.putString(url);
            if (!Clipboard.getSystemClipboard().setContent(content))
            {
                throw new IllegalStateException("Could not write to the clipboard.");
            }
        }
        catch (RuntimeException ex)
        {
            LOG.warn("Could not copy API URL for {}", mapFile, ex);
            Dialogs.error(window(), "Could not copy API URL", ex.getMessage());
        }
    }

    /**
     * Callbacks into the application.
     */
    public interface Host
    {
        void newMap();

        void newMapIn(Path folder);

        void importMap(Path suggestedFolder);

        void importMapFolder(Path suggestedFolder);

        void saveMap();

        void rotateMap(boolean clockwise);

        void openMap(Path mapFile);

        /**
         * The open map: the {@code .dmmap} file, or the {@code .dmlevels} manifest of an open multilevel map.
         */
        Path currentMapFile();

        /**
         * Name of the open level of a multilevel map, {@code null} for ordinary maps.
         */
        default String currentLevelName()
        {
            return null;
        }

        /**
         * Imports several dd2vtt files as the levels of one multilevel map.
         */
        void importMultiLevelMap(Path suggestedFolder);

        /**
         * Merges library maps into a new multilevel map (the maps are moved into it).
         */
        void mergeIntoMultiLevelMap(java.util.List<Entry> maps);

        /**
         * Opens the level dialog of a multilevel map.
         */
        void manageLevels(Path manifestFile);

        /**
         * Exports the maps into one {@code .dmmpack} archive chosen by the user.
         */
        void exportMaps(java.util.List<Entry> maps);

        /**
         * Edits one map's tags, or adds tags to every selected map.
         */
        void manageTags(java.util.List<Entry> maps);

        /**
         * Maps were dropped onto another map ({@code dragged} never contains {@code target}): ordinary maps only
         * create a multilevel map; otherwise everything is added to / merged into the target if it is a multilevel
         * map, else into the first dragged multilevel map. The host shows the level dialog first.
         */
        void dropMapOnMap(java.util.List<Entry> dragged, Entry target);

        /**
         * Splits a multilevel map into separate maps (after a confirmation).
         */
        void dissolveMultiLevel(Path manifestFile);

        /**
         * Runs a library operation in the background. The host saves the open map first if it lives under
         * {@code affectedPath} and fixes up its own references afterwards. {@code onDone} runs on the FX thread.
         */
        void runLibraryOperation(String busyMessage, Path affectedPath, LibraryOperation operation,
                                 Consumer<MapLibraryService.Result> onDone);
    }

    @FunctionalInterface
    public interface LibraryOperation
    {
        MapLibraryService.Result run() throws IOException;
    }

    /**
     * A loaded thumbnail (or {@code null} image if the map has none), the file stamp it was loaded for and, for multilevel maps, the number of levels.
     */
    private record Thumbnail(Image image, long stamp, int levels)
    {
    }

    private static final double THUMB_WIDTH = 48;
    private static final double THUMB_HEIGHT = 32;
    private static final double RECENT_CELL_HEIGHT = 26;
    private static final int RECENT_VISIBLE_ROWS = 5;
    private static final PseudoClass OPEN_MAP = PseudoClass.getPseudoClass("open-map");
    private static final PseudoClass DROP_TARGET = PseudoClass.getPseudoClass("drop-target");
    private static final PseudoClass MERGE_TARGET = PseudoClass.getPseudoClass("merge-target");

    /**
     * What dropping a dragged library entry onto a cell does.
     */
    private enum DropAction
    {
        MOVE,
        CREATE_MULTILEVEL,
        ADD_TO_MULTILEVEL,
        MERGE_MULTILEVEL
    }

    private final MapLibraryService library;
    private final Host host;
    private final TreeView<Entry> tree = new TreeView<>();
    private final Set<Path> expandedFolders = new HashSet<>();
    private final Label currentMapName = new Label();
    private final TextField searchField = new TextField();
    private final Label noResults = new Label("No maps or folders found");
    private final Map<Path, Thumbnail> thumbnails = new HashMap<>();
    private final Set<Path> loadingThumbnails = new HashSet<>();
    private final ExecutorService thumbnailLoader = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "map-thumbnails");
        thread.setDaemon(true);
        return thread;
    });
    private HBox actions;
    private final RecentMaps recentMaps;
    private boolean resetTreeViewport;
    private final ListView<Path> recentList = new ListView<>();
    private final Button previousMapButton;
    private Path previousMap;
    private Entry scannedRoot;
    private java.util.List<Entry> draggedEntries;
    private ContextMenu openMenu;

    public MapBrowser(MapLibraryService library, Host host, RecentMaps recentMaps)
    {
        this.library = library;
        this.host = host;
        this.recentMaps = recentMaps;
        previousMapButton = Icons.button(MaterialDesignA.ARROW_LEFT, "Previous map",
                                         () -> openMap(previousMap));
        previousMapButton.setId("previous-map");
        previousMapButton.setDisable(true);
        getStyleClass().add("sidebar");
        setPrefWidth(270);
        setMinWidth(200);

        Label title = new Label("Maps");
        title.getStyleClass().add("sidebar-title");
        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        HBox titleRow = new HBox(4, title, titleSpacer,
                                 Icons.button(MaterialDesignF.FOLDER_PLUS_OUTLINE, "New folder in the selected folder",
                                              () -> createFolder(selectedFolder())),
                                 Icons.button(MaterialDesignR.REFRESH, "Reload the library from disk", this::refresh));
        titleRow.getStyleClass().add("toolbar-row");

        HBox actions = new HBox(
                Icons.button(MaterialDesignM.MAP_PLUS, "New empty map (drop images onto it to build a custom map)",
                             () -> {
                                 if (!dispatchRemote("new", java.util.List.of(), selectedFolder()))
                                     localLibraryAction(host::newMap);
                             }),
                Icons.button(MaterialDesignF.FILE_IMPORT_OUTLINE,
                             "Import one or more .dd2vtt or .uvtt maps",
                             () -> importAction("import", selectedFolder())),
                Icons.button(MaterialDesignF.FOLDER_DOWNLOAD_OUTLINE, "Import all maps from a folder",
                             () -> importAction("importFolder", selectedFolder())),
                Icons.button(MaterialDesignL.LAYERS_PLUS,
                             "Import a multilevel map: several .dd2vtt or .uvtt levels (e.g. building floors) as one map",
                             () -> importAction("importMultiLevel", selectedFolder())),
                Icons.button(MaterialDesignC.CONTENT_SAVE_OUTLINE, "Save the open map (Ctrl+S)", host::saveMap));
        actions.getStyleClass().add("toolbar-row");
        this.actions = actions;

        Label caption = new Label("OPEN MAP");
        caption.getStyleClass().add("caption");
        currentMapName.getStyleClass().add("current-map-name");
        currentMapName.setMaxWidth(Double.MAX_VALUE);
        VBox nameBox = new VBox(1, caption, currentMapName);
        HBox.setHgrow(nameBox, Priority.ALWAYS);
        nameBox.setMinWidth(0);
        HBox currentCard = new HBox(4, nameBox,
                                    Icons.button(MaterialDesignR.ROTATE_LEFT, "Rotate the map 90° counter-clockwise",
                                                 () -> host.rotateMap(false)),
                                    Icons.button(MaterialDesignR.ROTATE_RIGHT, "Rotate the map 90° clockwise",
                                                 () -> host.rotateMap(true)));
        currentCard.getStyleClass().add("current-map-card");
        currentCard.setStyle("-fx-alignment: center-left;");

        tree.setShowRoot(true);
        tree.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        tree.setCellFactory(view -> new LibraryCell());
        VBox.setVgrow(tree, Priority.ALWAYS);
        tree.setOnKeyPressed(event -> {
            TreeItem<Entry> item = tree.getSelectionModel().getSelectedItem();
            Entry entry = item == null ? null : item.getValue();
            if (entry == null)
            {
                return;
            }
            boolean single = tree.getSelectionModel().getSelectedItems().size() == 1;
            if (event.getCode() == KeyCode.ENTER && single && entry.isMap())
            {
                openMap(entry.mapFile());
                event.consume();
            }
            else if (event.getCode() == KeyCode.F2 && single && !isRoot(entry))
            {
                rename(entry);
                event.consume();
            }
            else if (event.getCode() == KeyCode.DELETE && !isRoot(entry))
            {
                java.util.List<Entry> maps = selectedMaps();
                if (!maps.isEmpty())
                {
                    deleteMaps(maps);
                }
                else if (single)
                {
                    delete(entry);
                }
                event.consume();
            }
        });

        Label hint = new Label("Double-click to open · drag to move · Ctrl+click to select several · right-click for more");
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);

        HBox searchBox = buildSearchBox();
        noResults.getStyleClass().add("muted");
        noResults.setVisible(false);
        noResults.setManaged(false);

        // Status text is reported through dialogs; the label stays in the scene (state holder) but is never shown.
        catalogStatus.setId("map-catalog-status");
        catalogStatus.setVisible(false);
        catalogStatus.setManaged(false);
        getChildren().addAll(titleRow, actions, currentCard, searchBox, catalogStatus, noResults, tree, hint, buildRecentBox());
        refresh();
    }

    private VBox buildRecentBox()
    {
        Label caption = new Label("RECENT MAPS");
        caption.getStyleClass().add("caption");
        recentList.getStyleClass().add("recent-maps");
        recentList.setFixedCellSize(RECENT_CELL_HEIGHT);
        recentList.setPlaceholder(new Label("No maps opened yet"));
        recentList.setCellFactory(view -> new RecentCell());
        recentList.setOnKeyPressed(event -> {
            Path selected = recentList.getSelectionModel().getSelectedItem();
            if (event.getCode() == KeyCode.ENTER && selected != null)
            {
                openMap(selected);
                event.consume();
            }
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox heading = new HBox(4, caption, spacer, previousMapButton);
        heading.getStyleClass().add("toolbar-row");
        heading.setStyle("-fx-alignment: center-left;");
        VBox box = new VBox(4, heading, recentList);
        box.getStyleClass().add("recent-maps-box");
        return box;
    }

    private void updateRecentList()
    {
        if (remoteCatalog != null)
        {
            recentList.getItems().setAll(remoteRecents);
            recentList.setPlaceholder(new Label("No host maps opened yet"));
            recentList.refresh();
            previousMap = remoteRecents.stream().filter(p -> !p.equals(remoteCurrent)).findFirst().orElse(null);
            previousMapButton.setDisable(previousMap == null);
            double height = Math.max(1, Math.min(remoteRecents.size(), RECENT_VISIBLE_ROWS)) * RECENT_CELL_HEIGHT + 2;
            recentList.setPrefHeight(height);
            recentList.setMinHeight(height);
            recentList.setMaxHeight(height);
            return;
        }
        recentList.setPlaceholder(new Label("No maps opened yet"));
        recentList.getItems().setAll(recentMaps.existing());
        recentList.refresh();
        int rows = Math.max(1, Math.min(recentList.getItems().size(), RECENT_VISIBLE_ROWS));
        double height = rows * RECENT_CELL_HEIGHT + 2;
        recentList.setPrefHeight(height);
        recentList.setMinHeight(height);
        recentList.setMaxHeight(height);
        previousMap = recentMaps.previous(host.currentMapFile()).orElse(null);
        previousMapButton.setDisable(previousMap == null);
    }

    public Button previousMapButton()
    {
        return previousMapButton;
    }

    /**
     * Adds a control to the row of map actions (new / import / save).
     */
    public void addAction(javafx.scene.Node node)
    {
        actions.getChildren().add(node);
    }

    private HBox buildSearchBox()
    {
        searchField.setPromptText("Search maps and folders");
        searchField.getStyleClass().add("search-field");
        HBox.setHgrow(searchField, Priority.ALWAYS);
        Button clear = Icons.button(MaterialDesignC.CLOSE_CIRCLE_OUTLINE, "Clear the search (Esc)", this::clearSearch);
        clear.visibleProperty().bind(searchField.textProperty().isNotEmpty());
        clear.managedProperty().bind(clear.visibleProperty());
        searchField.textProperty().addListener((observable, oldValue, newValue) -> rebuildTree());
        searchField.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE)
            {
                clearSearch();
                event.consume();
            }
        });
        HBox box = new HBox(4, Icons.icon(MaterialDesignM.MAGNIFY), searchField, clear);
        box.getStyleClass().addAll("toolbar-row", "search-row");
        box.setStyle("-fx-alignment: center-left;");
        return box;
    }

    private void clearSearch()
    {
        searchField.clear();
        tree.requestFocus();
    }

    // ---- Public API ----

    public void refresh()
    {
        if (remoteCatalog != null)
        {
            forceRemoteRefresh = true;
            retryRemotePreviews |= !remotePreviewRetryAfter.isEmpty();
            remotePreviewRetryAfter.clear();
            remotePollingFailures.clear();
            refreshRemote();
            return;
        }
        try
        {
            scannedRoot = library.scan();
        }
        catch (IOException exception)
        {
            Dialogs.error(window(), "Could not read the map library", exception.getMessage());
            return;
        }
        invalidateThumbnails();
        rebuildTree();
    }

    /**
     * Rebuilds the tree from the last scan, applying the current search filter.
     */
    private void rebuildTree()
    {
        if (scannedRoot == null)
        {
            return;
        }
        Path selected = selectedPath();
        boolean filtering = MapTreeFilter.isActive(searchField.getText());
        Entry root = MapTreeFilter.filter(scannedRoot, searchField.getText());
        TreeItem<Entry> rootItem = build(root, filtering);
        rootItem.setExpanded(true);
        tree.setRoot(rootItem);
        boolean empty = filtering && root.children().isEmpty();
        noResults.setVisible(empty);
        noResults.setManaged(empty);
        if (selected != null)
        {
            select(selected);
        }
        updateCurrentMap();
        if (resetTreeViewport && remoteCatalog != null && rootItem.getChildren().size() > 0)
        {
            resetTreeViewport = false;
            // The virtual flow keeps a stale viewport/scroll bar from before the host tree existed; rebuild it after layout.
            Platform.runLater(() -> {
                tree.requestLayout();
                Platform.runLater(() -> {
                    tree.layout();
                    boolean scrollable = tree.lookupAll(".scroll-bar").stream().anyMatch(n ->
                            n instanceof javafx.scene.control.ScrollBar bar
                                    && bar.getOrientation() == javafx.geometry.Orientation.VERTICAL && bar.isVisible());
                    // Without a scroll bar everything fits, so any scroll offset is stale and hides the top rows.
                    if (!scrollable)
                    {
                        tree.scrollTo(0);
                        tree.refresh();
                    }
                });
            });
        }
    }

    /**
     * Drops cached thumbnails whose file changed on disk (e.g. after a save) so they are reloaded.
     */
    public void invalidateThumbnails()
    {
        if (remoteCatalog != null)
            return;
        thumbnails.entrySet().removeIf(entry -> stamp(entry.getKey()) != entry.getValue().stamp());
        tree.refresh();
    }

    /**
     * Selects (and reveals) a folder, map package or map file.
     */
    public void select(Path path)
    {
        if (path == null || tree.getRoot() == null)
        {
            return;
        }
        Path normalized = path.toAbsolutePath().normalize();
        TreeItem<Entry> item = findItem(tree.getRoot(), normalized);
        if (item == null)
        {
            return;
        }
        for (TreeItem<Entry> parent = item.getParent(); parent != null; parent = parent.getParent())
        {
            parent.setExpanded(true);
        }
        tree.getSelectionModel().clearSelection();
        tree.getSelectionModel().select(item);
        int row = tree.getRow(item);
        if (row >= 0)
        {
            tree.scrollTo(Math.max(0, row - 3));
        }
    }

    public void updateCurrentMap()
    {
        if (remoteCatalog != null)
        {
            currentMapName.getStyleClass().remove("unsaved");
            currentMapName.setText(remoteCurrentLabel);
            updateRecentList();
            tree.refresh();
            return;
        }
        Path current = host.currentMapFile();
        currentMapName.getStyleClass().remove("unsaved");
        if (current == null)
        {
            currentMapName.setText("Unsaved new map");
            currentMapName.getStyleClass().add("unsaved");
        }
        else
        {
            String level = host.currentLevelName();
            currentMapName.setText(displayName(current) + (level == null ? "" : " · " + level));
            recentMaps.record(current);
        }
        updateRecentList();
        tree.refresh();
    }

    /**
     * The folder new maps should go to by default: the selected folder, or the folder of the selected map.
     */
    public Path selectedFolder()
    {
        TreeItem<Entry> item = tree.getSelectionModel().getSelectedItem();
        if (item == null || item.getValue() == null)
        {
            return libraryRoot();
        }
        Entry entry = item.getValue();
        return entry.isFolder() ? entry.path() : entry.containingFolder();
    }

    public static String displayName(Path mapFile)
    {
        String stem = MapLibraryService.stripExtension(mapFile.getFileName().toString());
        return stem;
    }

    // ---- Tree building ----

    /**
     * While filtering, all folders are expanded and the user's own expand/collapse state is left untouched.
     */
    private TreeItem<Entry> build(Entry entry, boolean filtering)
    {
        TreeItem<Entry> item = new TreeItem<>(entry);
        if (entry.isFolder())
        {
            Path key = entry.path().toAbsolutePath().normalize();
            if (filtering)
            {
                item.setExpanded(true);
            }
            else
            {
                item.setExpanded(expandedFolders.contains(key));
                item.expandedProperty().addListener((observable, oldValue, newValue) -> {
                    if (newValue)
                    {
                        expandedFolders.add(key);
                    }
                    else
                    {
                        expandedFolders.remove(key);
                    }
                });
            }
            for (Entry child : entry.children())
            {
                item.getChildren().add(build(child, filtering));
            }
        }
        return item;
    }

    // ---- Thumbnails ----

    /**
     * Modification stamp of what the thumbnail is derived from (thumbnail file if present, else the map file).
     */
    private static long stamp(Path mapFile)
    {
        try
        {
            Path file = ThumbnailService.thumbnailFile(mapFile);
            if (MultiLevelService.isMultiLevelFile(mapFile))
            {
                // Level names/order live in the manifest, the picture in the thumbnail: either may change.
                long manifest = Files.getLastModifiedTime(mapFile).toMillis();
                return Files.isRegularFile(file) ? Math.max(manifest, Files.getLastModifiedTime(file).toMillis()) : manifest;
            }
            if (ThumbnailService.isPackage(mapFile) && Files.isRegularFile(file))
            {
                return Files.getLastModifiedTime(file).toMillis();
            }
            return Files.getLastModifiedTime(mapFile).toMillis();
        }
        catch (IOException exception)
        {
            return -1;
        }
    }

    /**
     * Number of levels of a multilevel map (once its thumbnail was loaded), else 0.
     */
    private int levelCount(Path mapFile)
    {
        Thumbnail cached = thumbnails.get(mapFile.toAbsolutePath().normalize());
        return cached == null ? 0 : cached.levels();
    }

    /**
     * The cached thumbnail, or {@code null} while it is loading (a background load is started) or missing.
     */
    private Image thumbnailFor(Path mapFile)
    {
        if (remoteConnection != null)
            return remoteThumbnail(mapFile);
        if (remoteCatalog != null)
            return null;
        Path key = mapFile.toAbsolutePath().normalize();
        Thumbnail cached = thumbnails.get(key);
        if (cached != null)
        {
            return cached.image();
        }
        if (loadingThumbnails.add(key))
        {
            long generation = catalogGeneration;
            thumbnailLoader.execute(() -> {
                if (generation != catalogGeneration)
                    return;
                Image image = null;
                int levels = 0;
                try
                {
                    byte[] png = library.loadOrCreateThumbnail(key);
                    if (png != null)
                    {
                        image = new Image(new ByteArrayInputStream(png));
                    }
                }
                catch (IOException | RuntimeException ignored)
                {
                    // No thumbnail: the placeholder stays.
                }
                if (MultiLevelService.isMultiLevelFile(key))
                {
                    try
                    {
                        levels = library.multiLevels().loadManifest(key).getLevels().size();
                    }
                    catch (IOException | RuntimeException ignored)
                    {
                        // the tooltip just has no level count
                    }
                }
                Image loaded = image;
                int levelCount = levels;
                long stamp = stamp(key);
                Platform.runLater(() -> {
                    if (generation != catalogGeneration)
                        return;
                    loadingThumbnails.remove(key);
                    thumbnails.put(key, new Thumbnail(loaded, stamp, levelCount));
                    tree.refresh();
                    recentList.refresh();
                });
            });
        }
        return null;
    }

    /**
     * Tooltip with the map name and a larger preview of its thumbnail (when loaded).
     */
    public static Tooltip previewTooltip(String text, Image image)
    {
        Tooltip tooltip = Icons.tooltip(text);
        tooltip.setMaxWidth(420);
        if (image != null)
        {
            ImageView large = new ImageView(image);
            large.setPreserveRatio(true);
            large.setSmooth(true);
            large.setFitWidth(Math.min(256, image.getWidth()));
            large.setFitHeight(Math.min(256, image.getHeight()));
            tooltip.setGraphic(large);
            tooltip.setContentDisplay(ContentDisplay.TOP);
        }
        return tooltip;
    }

    private static TreeItem<Entry> findItem(TreeItem<Entry> item, Path path)
    {
        Entry entry = item.getValue();
        if (entry != null && (entry.path().toAbsolutePath().normalize().equals(path)
                || (entry.mapFile() != null && entry.mapFile().toAbsolutePath().normalize().equals(path))))
        {
            return item;
        }
        for (TreeItem<Entry> child : item.getChildren())
        {
            TreeItem<Entry> found = findItem(child, path);
            if (found != null)
            {
                return found;
            }
        }
        return null;
    }

    private Path selectedPath()
    {
        TreeItem<Entry> item = tree.getSelectionModel().getSelectedItem();
        return item == null || item.getValue() == null ? null : item.getValue().path();
    }

    /**
     * All selected maps in tree order, or none if the selection includes a folder.
     */
    private java.util.List<Entry> selectedMaps()
    {
        java.util.List<Entry> maps = new java.util.ArrayList<>();
        for (TreeItem<Entry> item : tree.getSelectionModel().getSelectedItems())
        {
            if (item == null || item.getValue() == null || !item.getValue().isMap())
            {
                return java.util.List.of();
            }
            maps.add(item.getValue());
        }
        return java.util.List.copyOf(maps);
    }

    private boolean isRoot(Entry entry)
    {
        return entry.path().toAbsolutePath().normalize().equals(libraryRoot());
    }

    private boolean isOpenMap(Entry entry)
    {
        Path current = currentMapFile();
        return entry.isMap() && current != null
                && entry.mapFile().toAbsolutePath().normalize().equals(current.toAbsolutePath().normalize());
    }

    private Window window()
    {
        return getScene() == null ? null : getScene().getWindow();
    }

    // ---- Actions ----

    private void createFolder(Path parent)
    {
        if (remoteActionUnavailable())
            return;
        var guard = remoteListing;
        Optional<String> name = Dialogs.askText(window(), "New folder",
                                                "Create a folder in \"" + folderLabel(parent) + "\"", MaterialDesignF.FOLDER_PLUS_OUTLINE, "Create", "",
                                                MapLocationDialog::nameProblem);
        if (name.isEmpty())
        {
            return;
        }
        if (remoteConnection != null)
        {
            sendRemote("createFolder", java.util.List.of(), parent, name.get(), null, guard);
            return;
        }
        Path[] created = new Path[1];
        LOG.info("Creating map folder '{}' in {}", name.get(), parent);
        host.runLibraryOperation("Creating folder…", null, () -> {
            created[0] = library.createFolder(parent, name.get());
            return new MapLibraryService.Result(java.util.Map.of(), null);
        }, result -> {
            expandedFolders.add(parent.toAbsolutePath().normalize());
            refresh();
            select(created[0]);
        });
    }

    private void rename(Entry entry)
    {
        if (remoteActionUnavailable())
            return;
        var guard = remoteListing;
        String kind = entry.isFolder() ? "folder" : "map";
        Optional<String> name = Dialogs.askText(window(), "Rename " + kind, "Rename \"" + entry.name() + "\"",
                                                MaterialDesignR.RENAME_BOX, "Rename", entry.name(), MapLocationDialog::nameProblem);
        if (name.isEmpty() || name.get().equals(entry.name()))
        {
            return;
        }
        if (remoteConnection != null)
        {
            sendRemote("rename", java.util.List.of(entry), null, name.get(), null, guard);
            return;
        }
        LOG.info("Renaming {} to '{}'", entry.path(), name.get());
        host.runLibraryOperation("Renaming…", entry.path(), () -> library.rename(entry, name.get()), result -> {
            Path renamed = entry.containingFolder().resolve(name.get().trim());
            if (entry.isFolder())
            {
                remapExpanded(entry.path(), renamed);
            }
            refresh();
            Path newMapFile = result.movedMaps().get(entry.mapFile());
            select(entry.isMap() && newMapFile != null ? newMapFile : renamed);
        });
    }

    private void copyMaps(java.util.List<Entry> maps)
    {
        if (dispatchRemote("copy", maps, null))
            return;
        if (remoteActionUnavailable())
            return;
        java.util.List<Path> created = new java.util.ArrayList<>();
        java.util.List<String> failures = new java.util.ArrayList<>();
        LOG.info("Duplicating {} maps", maps.size());
        host.runLibraryOperation("Copying " + maps.size() + " maps…", affectedOpenMap(maps), () -> {
            for (Entry map : maps)
            {
                try
                {
                    created.add(library.copy(map).createdMap());
                }
                catch (IOException exception)
                {
                    LOG.warn("Could not duplicate map {}", map.path(), exception);
                    failures.add(map.name() + ": " + exception.getMessage());
                }
            }
            return new MapLibraryService.Result(java.util.Map.of(), null);
        }, result -> {
            refresh();
            tree.getSelectionModel().clearSelection();
            for (Path path : created)
            {
                TreeItem<Entry> item = findItem(tree.getRoot(), path.toAbsolutePath().normalize());
                if (item != null)
                {
                    tree.getSelectionModel().select(item);
                }
            }
            showBatchFailures("duplicated", failures);
        });
    }

    private void deleteMaps(java.util.List<Entry> maps)
    {
        if (remoteActionUnavailable())
            return;
        var guard = remoteListing;
        if (maps.size() == 1)
        {
            delete(maps.get(0));
            return;
        }
        String names = String.join("\n", maps.stream().map(Entry::name).toList());
        boolean confirmed = Dialogs.confirmDanger(window(), "Delete maps", "Delete " + maps.size() + " maps?",
                                                  "These maps and all of their images and saved state will be permanently deleted from disk:\n\n"
                                                          + names + "\n\nThis cannot be undone.", "Delete");
        if (!confirmed)
        {
            return;
        }
        if (remoteConnection != null)
        {
            sendRemote("delete", maps, null, null, null, guard);
            return;
        }
        java.util.List<String> failures = new java.util.ArrayList<>();
        LOG.info("Deleting {} maps", maps.size());
        host.runLibraryOperation("Deleting " + maps.size() + " maps…", affectedOpenMap(maps), () -> {
            for (Entry map : maps)
            {
                try
                {
                    library.delete(map);
                }
                catch (IOException exception)
                {
                    LOG.warn("Could not delete map {}", map.path(), exception);
                    failures.add(map.name() + ": " + exception.getMessage());
                }
            }
            return new MapLibraryService.Result(java.util.Map.of(), null);
        }, result -> {
            refresh();
            showBatchFailures("deleted", failures);
        });
    }

    private Path affectedOpenMap(java.util.List<Entry> maps)
    {
        return maps.stream().filter(this::isOpenMap).map(Entry::path).findFirst().orElse(null);
    }

    private void showBatchFailures(String action, java.util.List<String> failures)
    {
        if (!failures.isEmpty())
        {
            Dialogs.error(window(), failures.size() + (failures.size() == 1 ? " map was" : " maps were")
                    + " not " + action, String.join("\n", failures));
        }
    }

    private void delete(Entry entry)
    {
        if (remoteActionUnavailable())
            return;
        var guard = remoteListing;
        String message;
        if (entry.isMap())
        {
            message = "The map \"" + entry.name() + "\" and all of its images and saved state will be permanently "
                    + "deleted from disk.";
        }
        else
        {
            int maps = MapLibraryService.countMaps(entry);
            message = "The folder \"" + entry.name() + "\" " + (maps == 0 ? "(empty) "
                                                                          : "and the " + maps + (maps == 1 ? " map" : " maps") + " inside it ")
                    + "will be permanently deleted from disk.";
        }
        boolean confirmed = Dialogs.confirmDanger(window(), "Delete " + (entry.isMap() ? "map" : "folder"),
                                                  "Delete \"" + entry.name() + "\"?", message + "\n\nThis cannot be undone.", "Delete");
        if (!confirmed)
        {
            return;
        }
        if (remoteConnection != null)
        {
            sendRemote("delete", java.util.List.of(entry), null, null, null, guard);
            return;
        }
        LOG.info("Deleting {} {}", entry.isMap() ? "map" : "folder", entry.path());
        host.runLibraryOperation("Deleting…", entry.path(), () -> {
            library.delete(entry);
            return new MapLibraryService.Result(java.util.Map.of(), null);
        }, result -> refresh());
    }

    private void move(Entry entry, Path targetFolder)
    {
        if (dispatchRemote("move", java.util.List.of(entry), targetFolder))
            return;
        if (remoteActionUnavailable())
            return;
        LOG.info("Moving {} to {}", entry.path(), targetFolder);
        host.runLibraryOperation("Moving…", entry.path(), () -> library.move(entry, targetFolder), result -> {
            Path destination = targetFolder.resolve(entry.path().getFileName());
            if (entry.isFolder())
            {
                remapExpanded(entry.path(), destination);
            }
            expandedFolders.add(targetFolder.toAbsolutePath().normalize());
            refresh();
            Path newMapFile = entry.isMap() ? result.movedMaps().get(entry.mapFile()) : null;
            select(newMapFile != null ? newMapFile : destination);
        });
    }

    private void remapExpanded(Path oldFolder, Path newFolder)
    {
        Path oldNormalized = oldFolder.toAbsolutePath().normalize();
        Path newNormalized = newFolder.toAbsolutePath().normalize();
        Set<Path> updated = new HashSet<>();
        for (Path path : expandedFolders)
        {
            updated.add(path.startsWith(oldNormalized) ? newNormalized.resolve(oldNormalized.relativize(path)) : path);
        }
        expandedFolders.clear();
        expandedFolders.addAll(updated);
    }

    private String folderLabel(Path folder)
    {
        if (remoteConnection != null)
        {
            Entry entry = remoteEntries.get(folder);
            return entry == null ? "Library" : entry.name();
        }
        return folder.toAbsolutePath().normalize().equals(library.getRoot()) ? "Library"
                                                                             : folder.getFileName().toString();
    }

    ContextMenu buildMenu(Entry entry)
    {
        ContextMenu menu = new ContextMenu();
        if (remoteCatalog != null && remoteConnection == null)
        {
            menu.getItems().add(item("Refresh host library", MaterialDesignR.REFRESH, this::refresh));
            if (entry != null && entry.isMap())
            {
                var pending = new MenuItem("Host map actions are not integrated yet");
                pending.setDisable(true);
                menu.getItems().add(pending);
            }
            return menu;
        }
        if (entry == null)
        {
            entry = tree.getRoot().getValue();
        }
        Entry target = entry;
        java.util.List<Entry> maps = selectedMaps();
        if (maps.isEmpty() && tree.getSelectionModel().getSelectedItems().size() <= 1 && entry.isMap())
        {
            maps = java.util.List.of(entry);
        }
        java.util.List<Entry> selected = maps;
        MenuItem duplicate = item("Duplicate", MaterialDesignC.CONTENT_COPY, () -> copyMaps(selected));
        MenuItem delete = danger(item("Delete…", MaterialDesignD.DELETE_OUTLINE, () -> {
            if (target.isMap())
            {
                deleteMaps(selected);
            }
            else
            {
                delete(target);
            }
        }));
        duplicate.setDisable(selected.isEmpty());
        delete.setDisable(target.isMap() && selected.isEmpty());
        MenuItem merge = item("Combine into multilevel map", MaterialDesignL.LAYERS_PLUS,
                              () -> {
                                  if (!dispatchRemote("merge", selected, null))
                                      host.mergeIntoMultiLevelMap(selected);
                              });
        merge.setDisable(selected.size() < 2 || selected.stream().anyMatch(Entry::isMultiLevel));
        MenuItem tags = item(selected.size() > 1 ? "Add tags…" : "Manage tags…",
                             MaterialDesignT.TAG_MULTIPLE_OUTLINE, () -> {
                    if (remoteConnection != null)
                        remoteTags(selected);
                    else
                        host.manageTags(selected);
                });
        tags.setDisable(selected.isEmpty());
        MenuItem export = item(selected.size() > 1 ? "Export " + selected.size() + " maps…" : "Export…",
                               MaterialDesignF.FILE_EXPORT_OUTLINE, () -> exportAction(selected));
        export.setDisable(selected.isEmpty());
        if (entry.isMultiLevel())
        {
            menu.getItems().addAll(
                    item("Open", MaterialDesignM.MAP_OUTLINE, () -> openMap(target.mapFile())),
                    tags,
                    item("Manage levels…", MaterialDesignL.LAYERS_TRIPLE_OUTLINE, () -> manageLevelsAction(target)),
                    item("Dissolve into separate maps…", MaterialDesignL.LAYERS_OFF_OUTLINE,
                         () -> {
                             if (!dispatchRemote("dissolve", java.util.List.of(target), null))
                                 host.dissolveMultiLevel(target.mapFile());
                         }),
                    new SeparatorMenuItem(),
                    item("Rename…", MaterialDesignR.RENAME_BOX, () -> rename(target)),
                    duplicate,
                    export,
                    new SeparatorMenuItem(),
                    delete);
        }
        else if (entry.isMap())
        {
            menu.getItems().addAll(
                    item("Open", MaterialDesignM.MAP_OUTLINE, () -> openMap(target.mapFile())),
                    tags,
                    new SeparatorMenuItem(),
                    item("Rename…", MaterialDesignR.RENAME_BOX, () -> rename(target)),
                    duplicate,
                    merge,
                    export,
                    new SeparatorMenuItem(),
                    delete);
        }
        else
        {
            menu.getItems().addAll(
                    item("New map here…", MaterialDesignM.MAP_PLUS, () -> {
                        if (!dispatchRemote("new", java.util.List.of(), target.path()))
                            host.newMapIn(target.path());
                    }),
                    item("Import maps here…", MaterialDesignF.FILE_IMPORT_OUTLINE, () -> importAction("import", target.path())),
                    item("Import folder here…", MaterialDesignF.FOLDER_DOWNLOAD_OUTLINE,
                         () -> importAction("importFolder", target.path())),
                    item("Import multilevel map here…", MaterialDesignL.LAYERS_PLUS,
                         () -> importAction("importMultiLevel", target.path())),
                    item("New folder…", MaterialDesignF.FOLDER_PLUS_OUTLINE, () -> createFolder(target.path())));
            if (!isRoot(entry))
            {
                menu.getItems().addAll(
                        new SeparatorMenuItem(),
                        item("Rename…", MaterialDesignR.RENAME_BOX, () -> rename(target)),
                        delete);
            }
        }
        if (remoteCatalog == null && entry.isMap() && apiUrlProvider != null && apiUrlOptionsVisible)
        {
            menu.getItems().add(item("Copy API URL", MaterialDesignC.CONTENT_COPY,
                                     () -> copyApiUrl(target.mapFile())));
        }
        if (tree.getSelectionModel().getSelectedItems().size() > 1)
        {
            for (MenuItem action : menu.getItems())
            {
                if (action != duplicate && action != merge && action != tags && action != export
                        && !(action == delete && target.isMap()))
                {
                    action.setDisable(true);
                }
            }
        }
        return menu;
    }

    private static MenuItem item(String text, Ikon ikon, Runnable action)
    {
        MenuItem item = new MenuItem(text, Icons.icon(ikon));
        item.setOnAction(event -> action.run());
        return item;
    }

    private static MenuItem danger(MenuItem item)
    {
        item.getStyleClass().add("danger");
        return item;
    }

    // ---- Drag & drop ----

    private Path dropFolder(Entry target)
    {
        if (target == null)
        {
            return libraryRoot();
        }
        return target.isFolder() ? target.path() : target.containingFolder();
    }

    private boolean canDrop(Entry dragged, Path targetFolder)
    {
        Path target = targetFolder.toAbsolutePath().normalize();
        if (target.equals(dragged.containingFolder().toAbsolutePath().normalize()))
        {
            return false;
        }
        return !dragged.isFolder() || !target.startsWith(dragged.path().toAbsolutePath().normalize());
    }

    /**
     * Dropping maps onto another map merges them; anything else dropped somewhere moves it to that folder.
     */
    private DropAction dropAction(java.util.List<Entry> dragged, Entry target)
    {
        if (dragged == null || dragged.isEmpty())
        {
            return null;
        }
        boolean onlyMaps = dragged.stream().allMatch(Entry::isMap);
        if (target != null && target.isMap() && onlyMaps)
        {
            java.util.List<Entry> others = othersThan(dragged, target);
            if (others.isEmpty())
            {
                return null;
            }
            boolean anyMulti = others.stream().anyMatch(Entry::isMultiLevel);
            if (!anyMulti && !target.isMultiLevel())
            {
                return DropAction.CREATE_MULTILEVEL;
            }
            return anyMulti && (target.isMultiLevel() || others.stream().filter(Entry::isMultiLevel).count() > 1)
                   ? DropAction.MERGE_MULTILEVEL : DropAction.ADD_TO_MULTILEVEL;
        }
        Path folder = dropFolder(target);
        return dragged.stream().anyMatch(entry -> canDrop(entry, folder)) ? DropAction.MOVE : null;
    }

    /**
     * The dragged maps without {@code target}.
     */
    private static java.util.List<Entry> othersThan(java.util.List<Entry> dragged, Entry target)
    {
        if (target == null || target.mapFile() == null)
        {
            return dragged;
        }
        Path targetFile = target.mapFile().toAbsolutePath().normalize();
        return dragged.stream()
                      .filter(entry -> entry.mapFile() == null || !entry.mapFile().toAbsolutePath().normalize().equals(targetFile))
                      .toList();
    }

    private static String dropHint(DropAction action, java.util.List<Entry> dragged, Entry target)
    {
        int count = othersThan(dragged, target).size();
        String maps = count > 1 ? " (" + count + " maps)" : "";
        return switch (action)
        {
            case CREATE_MULTILEVEL -> "Create a multilevel map" + maps;
            case ADD_TO_MULTILEVEL -> "Add to the multilevel map" + maps;
            case MERGE_MULTILEVEL -> "Merge into a multilevel map" + maps;
            case MOVE -> null;
        };
    }

    /**
     * What a drag starting at {@code item} carries: all selected entries if {@code item} is selected (in tree order,
     * without the library root and without entries inside another selected folder), else just {@code item}.
     */
    private java.util.List<Entry> draggedSelection(TreeItem<Entry> item)
    {
        java.util.List<TreeItem<Entry>> selected = new java.util.ArrayList<>(tree.getSelectionModel().getSelectedItems());
        if (!selected.contains(item))
        {
            return item.getValue() == null || isRoot(item.getValue()) ? java.util.List.of() : java.util.List.of(item.getValue());
        }
        selected.removeIf(candidate -> candidate == null || candidate.getValue() == null || isRoot(candidate.getValue()));
        selected.sort(java.util.Comparator.comparingInt(tree::getRow));
        java.util.List<Entry> entries = new java.util.ArrayList<>();
        for (TreeItem<Entry> candidate : selected)
        {
            Path path = candidate.getValue().path().toAbsolutePath().normalize();
            boolean insideSelectedFolder = selected.stream().anyMatch(other -> other != candidate
                    && other.getValue().isFolder()
                    && path.startsWith(other.getValue().path().toAbsolutePath().normalize()));
            if (!insideSelectedFolder)
            {
                entries.add(candidate.getValue());
            }
        }
        return entries;
    }

    /**
     * Moves several entries into {@code targetFolder}; ones that cannot be moved are skipped and listed afterwards.
     */
    private void moveAll(java.util.List<Entry> entries, Path targetFolder)
    {
        if (dispatchRemote("move", entries, targetFolder))
            return;
        if (remoteActionUnavailable())
            return;
        java.util.List<Entry> movable = entries.stream().filter(entry -> canDrop(entry, targetFolder)).toList();
        if (movable.size() == 1 && entries.size() == 1)
        {
            move(movable.get(0), targetFolder);
            return;
        }
        java.util.List<String> skipped = new java.util.ArrayList<>();
        for (Entry entry : entries)
        {
            if (!movable.contains(entry) && entry.isFolder()
                    && targetFolder.toAbsolutePath().normalize().startsWith(entry.path().toAbsolutePath().normalize()))
            {
                skipped.add(entry.name() + ": a folder cannot be moved into itself.");
            }
        }
        java.util.List<Path> destinations = new java.util.ArrayList<>();
        // The open map may be among them: the library root makes the host save it first and follow the move.
        host.runLibraryOperation("Moving " + movable.size() + " items…", library.getRoot(), () -> {
            java.util.Map<Path, Path> moved = new java.util.LinkedHashMap<>();
            for (Entry entry : movable)
            {
                try
                {
                    moved.putAll(library.move(entry, targetFolder).movedMaps());
                    destinations.add(targetFolder.resolve(entry.path().getFileName()));
                }
                catch (IOException | RuntimeException ex)
                {
                    skipped.add(entry.name() + ": " + ex.getMessage());
                }
            }
            return new MapLibraryService.Result(moved, null);
        }, result -> {
            for (Entry entry : movable)
            {
                if (entry.isFolder())
                {
                    remapExpanded(entry.path(), targetFolder.resolve(entry.path().getFileName()));
                }
            }
            expandedFolders.add(targetFolder.toAbsolutePath().normalize());
            refresh();
            if (!destinations.isEmpty())
            {
                Entry first = movable.get(0);
                Path firstMap = first.isMap() ? result.movedMaps().get(first.mapFile().toAbsolutePath().normalize()) : null;
                select(firstMap != null ? firstMap : destinations.get(0));
            }
            if (!skipped.isEmpty())
            {
                Dialogs.error(window(), skipped.size() + (skipped.size() == 1 ? " item was" : " items were")
                        + " not moved", String.join("\n", skipped));
            }
        });
    }

    private final class RecentCell extends ListCell<Path>
    {
        RecentCell()
        {
            setOnMouseClicked(event -> {
                if (getItem() != null && event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2)
                {
                    openMap(getItem());
                    event.consume();
                }
            });
        }

        @Override
        protected void updateItem(Path path, boolean empty)
        {
            super.updateItem(path, empty);
            if (empty || path == null)
            {
                setText(null);
                setTooltip(null);
                pseudoClassStateChanged(OPEN_MAP, false);
                return;
            }
            String name = remoteEntries.containsKey(path) ? remoteEntries.get(path).name() : displayName(path);
            setText(name);
            Path current = currentMapFile();
            boolean open = current != null && current.toAbsolutePath().normalize().equals(path);
            setTooltip(previewTooltip(name + (open ? " (open)" : "") + "\nDouble-click to open",
                                      thumbnailFor(path)));
            pseudoClassStateChanged(OPEN_MAP, open);
        }
    }

    private final class LibraryCell extends TreeCell<Entry>
    {
        private final FontIcon icon = new FontIcon();
        private final FontIcon placeholder = new FontIcon(MaterialDesignM.MAP_OUTLINE);
        private final ImageView thumbnailView = new ImageView();
        private final FontIcon multiLevelBadge = new FontIcon(MaterialDesignL.LAYERS_TRIPLE);
        private final FontIcon dropBadge = new FontIcon(MaterialDesignL.LAYERS_PLUS);
        private final StackPane thumbnailBox = new StackPane(placeholder, thumbnailView, multiLevelBadge, dropBadge);
        private final PauseTransition autoExpand = new PauseTransition(Duration.millis(Tuning.LIBRARY_AUTO_EXPAND_MS.get()));
        private TreeItem<Entry> observedItem;
        private final javafx.beans.value.ChangeListener<Boolean> expandedListener =
                (observable, oldValue, newValue) -> updateFolderIcon();

        LibraryCell()
        {
            thumbnailBox.getStyleClass().add("thumbnail-box");
            thumbnailBox.setMinSize(THUMB_WIDTH, THUMB_HEIGHT);
            thumbnailBox.setPrefSize(THUMB_WIDTH, THUMB_HEIGHT);
            thumbnailBox.setMaxSize(THUMB_WIDTH, THUMB_HEIGHT);
            placeholder.getStyleClass().add("map-icon");
            multiLevelBadge.getStyleClass().add("multilevel-badge");
            StackPane.setAlignment(multiLevelBadge, javafx.geometry.Pos.BOTTOM_RIGHT);
            multiLevelBadge.setVisible(false);
            dropBadge.getStyleClass().add("merge-drop-badge");
            dropBadge.setVisible(false);
            thumbnailView.setFitWidth(THUMB_WIDTH);
            thumbnailView.setFitHeight(THUMB_HEIGHT);
            thumbnailView.setPreserveRatio(true);
            thumbnailView.setSmooth(true);
            addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
                if (event.getButton() == MouseButton.SECONDARY && getTreeItem() != null
                        && tree.getSelectionModel().getSelectedItems().contains(getTreeItem()))
                {
                    tree.requestFocus();
                    event.consume();
                }
            });
            setOnMouseClicked(event -> {
                Entry entry = getItem();
                if (entry != null && entry.isMap() && event.getButton() == MouseButton.PRIMARY
                        && event.getClickCount() == 2)
                {
                    openMap(entry.mapFile());
                    event.consume();
                }
            });
            setOnContextMenuRequested(event -> {
                if (openMenu != null)
                {
                    openMenu.hide();
                }
                if (getTreeItem() != null && !tree.getSelectionModel().getSelectedItems().contains(getTreeItem()))
                {
                    tree.getSelectionModel().clearSelection();
                    tree.getSelectionModel().select(getTreeItem());
                }
                else if (isEmpty())
                {
                    tree.getSelectionModel().clearSelection();
                }
                openMenu = buildMenu(isEmpty() ? null : getItem());
                openMenu.show(this, event.getScreenX(), event.getScreenY());
                event.consume();
            });

            setOnDragDetected(event -> {
                Entry entry = getItem();
                if (remoteCatalog != null || entry == null || isRoot(entry) || event.getButton() != MouseButton.PRIMARY)
                {
                    return;
                }
                draggedEntries = draggedSelection(getTreeItem());
                if (draggedEntries.isEmpty())
                {
                    return;
                }
                Dragboard dragboard = startDragAndDrop(TransferMode.MOVE);
                ClipboardContent content = new ClipboardContent();
                content.putString(entry.path().toString());
                dragboard.setContent(content);
                if (draggedEntries.size() > 1)
                {
                    Label count = new Label(draggedEntries.size() + " items");
                    count.setStyle("-fx-background-color: #3d3424; -fx-text-fill: white; -fx-padding: 4 10;"
                                           + " -fx-background-radius: 10;");
                    dragboard.setDragView(count.snapshot(null, null));
                }
                else
                {
                    dragboard.setDragView(snapshot(null, null));
                }
                event.consume();
            });
            setOnDragOver(event -> {
                if (draggedEntries != null && dropAction(draggedEntries, isEmpty() ? null : getItem()) != null)
                {
                    event.acceptTransferModes(TransferMode.MOVE);
                }
                event.consume();
            });
            setOnDragEntered(event -> {
                DropAction action = draggedEntries == null ? null : dropAction(draggedEntries, isEmpty() ? null : getItem());
                if (action == DropAction.MOVE)
                {
                    pseudoClassStateChanged(DROP_TARGET, true);
                    TreeItem<Entry> item = getTreeItem();
                    if (item != null && item.getValue() != null && item.getValue().isFolder() && !item.isExpanded())
                    {
                        autoExpand.setOnFinished(finished -> item.setExpanded(true));
                        autoExpand.playFromStart();
                    }
                }
                else if (action != null)
                {
                    pseudoClassStateChanged(MERGE_TARGET, true);
                    dropBadge.setIconCode(action == DropAction.MERGE_MULTILEVEL ? MaterialDesignC.CALL_MERGE
                                                                                : MaterialDesignL.LAYERS_PLUS);
                    dropBadge.setVisible(true);
                    setText(getItem().name() + "  —  " + dropHint(action, draggedEntries, getItem()));
                }
            });
            setOnDragExited(event -> {
                pseudoClassStateChanged(DROP_TARGET, false);
                clearMergeMark();
                autoExpand.stop();
            });
            setOnDragDropped(event -> {
                boolean success = false;
                if (draggedEntries != null)
                {
                    java.util.List<Entry> dragged = draggedEntries;
                    Entry targetEntry = isEmpty() ? null : getItem();
                    DropAction action = dropAction(dragged, targetEntry);
                    if (action == DropAction.MOVE)
                    {
                        moveAll(dragged, dropFolder(targetEntry));
                        success = true;
                    }
                    else if (action != null)
                    {
                        clearMergeMark();
                        java.util.List<Entry> others = othersThan(dragged, targetEntry);
                        // After the drag gesture has finished: the host opens a modal dialog.
                        Platform.runLater(() -> {
                            if (!dispatchRemote("drop", others, targetEntry.path()))
                                host.dropMapOnMap(others, targetEntry);
                        });
                        success = true;
                    }
                }
                event.setDropCompleted(success);
                event.consume();
            });
            setOnDragDone(event -> draggedEntries = null);
        }

        private void clearMergeMark()
        {
            pseudoClassStateChanged(MERGE_TARGET, false);
            if (dropBadge.isVisible())
            {
                dropBadge.setVisible(false);
                if (getItem() != null)
                {
                    setText(getItem().name());
                }
            }
        }

        @Override
        protected void updateItem(Entry entry, boolean empty)
        {
            super.updateItem(entry, empty);
            if (observedItem != null)
            {
                observedItem.expandedProperty().removeListener(expandedListener);
                observedItem = null;
            }
            pseudoClassStateChanged(OPEN_MAP, false);
            pseudoClassStateChanged(DROP_TARGET, false);
            pseudoClassStateChanged(MERGE_TARGET, false);
            dropBadge.setVisible(false);
            icon.getStyleClass().removeAll("folder-icon", "map-icon");
            if (empty || entry == null)
            {
                setText(null);
                setGraphic(null);
                setTooltip(null);
                return;
            }
            setText(entry.name());
            if (entry.isFolder())
            {
                icon.getStyleClass().add("folder-icon");
                observedItem = getTreeItem();
                if (observedItem != null)
                {
                    observedItem.expandedProperty().addListener(expandedListener);
                }
                updateFolderIcon();
                setTooltip(null);
            }
            else
            {
                pseudoClassStateChanged(OPEN_MAP, isOpenMap(entry));
                Image image = thumbnailFor(entry.mapFile());
                thumbnailView.setImage(image);
                placeholder.setVisible(image == null);
                boolean multiLevel = entry.isMultiLevel();
                multiLevelBadge.setVisible(multiLevel);
                String kind = "";
                if (multiLevel)
                {
                    int levels = levelCount(entry.mapFile());
                    kind = "\nMultilevel map" + (levels > 0 ? " · " + levels + (levels == 1 ? " level" : " levels") : "");
                }
                setTooltip(previewTooltip(entry.name() + (isOpenMap(entry) ? " (open)" : "") + kind
                                                  + "\nDouble-click to open", image));
                setGraphic(thumbnailBox);
                return;
            }
            setGraphic(icon);
        }

        private void updateFolderIcon()
        {
            TreeItem<Entry> item = getTreeItem();
            if (item == null || item.getValue() == null || !item.getValue().isFolder())
            {
                return;
            }
            if (isRoot(item.getValue()))
            {
                icon.setIconCode(MaterialDesignF.FOLDER_HOME_OUTLINE);
            }
            else
            {
                icon.setIconCode(item.isExpanded() ? MaterialDesignF.FOLDER_OPEN_OUTLINE : MaterialDesignF.FOLDER_OUTLINE);
            }
        }
    }
}
