package dmmt.ui;

import dmmt.log.Log;
import dmmt.model.DmProject.TokenDefinition;
import dmmt.render.TokenArt;
import dmmt.remote.RemoteImages;
import dmmt.remote.RemoteOperations;
import dmmt.remote.RemoteTokens;
import dmmt.service.TokenLibraryService;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ForkJoinPool;
import javafx.application.Platform;

public final class TokenBrowser extends VBox
{
    private static final Log LOG = Log.of(TokenBrowser.class);
    public static final DataFormat TOKEN = new DataFormat("application/x-dmmt-token");
    private static final DataFormat FOLDER = new DataFormat("application/x-dmmt-token-folder");
    private static final javafx.css.PseudoClass DROP_TARGET = javafx.css.PseudoClass.getPseudoClass("drop-target");

    private record Item(String folder, TokenDefinition token)
    {
        String name()
        {
            return token == null ? folder.isEmpty() ? "Tokens" : folder.substring(folder.lastIndexOf('/') + 1) : token.getName();
        }
    }

    private final TokenLibraryService service;
    private final Consumer<TokenDefinition> changed;
    private final TreeView<Item> tree = new TreeView<>();
    private final TextField search = new TextField();
    private TokenLibraryService.Library library = new TokenLibraryService.Library();
    private final Map<String, Node> controls = new LinkedHashMap<>();
    private final Set<String> expanded = new HashSet<>(Set.of(""));
    private final Label remoteStatus = new Label();
    private RemoteOperations.Connection remote;
    private long remoteEpoch;
    private long remoteRevision = -1;
    private long nextRemotePoll;
    private boolean remoteQuery;
    private boolean remoteMutation;

    private record EditContext(RemoteOperations.Connection connection, long epoch, long revision) {}

    public TokenBrowser(TokenLibraryService service, Consumer<TokenDefinition> changed)
    {
        this.service = service;
        this.changed = changed;
        getStyleClass().add("sidebar");
        setPrefWidth(270);
        setMinWidth(200);
        Label title = new Label("Token Library");
        title.getStyleClass().add("sidebar-title");
        Button create = Icons.button(MaterialDesignA.ACCOUNT_PLUS_OUTLINE, "Create a token", () -> create(false, false));
        Button image = Icons.button(MaterialDesignF.FILE_IMAGE_PLUS_OUTLINE, "Import a token image", () -> create(true, false));
        Button paste = Icons.button(MaterialDesignC.CONTENT_PASTE, "Create token from clipboard", () -> create(false, true));
        Button folder = Icons.button(MaterialDesignF.FOLDER_PLUS_OUTLINE, "New token folder", this::newFolder);
        Button reload = Icons.button(MaterialDesignR.REFRESH, "Reload tokens", this::refresh);
        controls.put("tokens.create", create);
        controls.put("tokens.import", image);
        controls.put("tokens.paste", paste);
        controls.put("tokens.folder", folder);
        controls.put("tokens.refresh", reload);
        HBox actions = new HBox(create, image, paste, folder, reload);
        actions.getStyleClass().add("toolbar-row");
        search.setPromptText("Search tokens and folders");
        search.setId("token-search");
        search.textProperty().addListener((obs, was, value) -> rebuild());
        tree.setCellFactory(view -> new TokenCell());
        VBox.setVgrow(tree, Priority.ALWAYS);
        tree.setOnKeyPressed(e -> {
            Item selected = selected();
            if (e.getCode() == KeyCode.ENTER && selected != null && selected.token != null)
                edit(selected.token);
            else if (e.getCode() == KeyCode.F2 && selected != null)
                rename(selected);
            else if (e.getCode() == KeyCode.DELETE && selected != null)
                delete(selected);
            else if (e.isControlDown() && e.getCode() == KeyCode.V)
                create(false, true);
            else
                return;
            e.consume();
        });
        tree.setOnContextMenuRequested(e -> {
            if (e.getTarget() == tree)
            {
                menu(new Item("", null)).show(tree, e.getScreenX(), e.getScreenY());
                e.consume();
            }
        });
        Label hint = new Label("Drag tokens onto the map or into folders. Right-click to edit. Ctrl+V imports a clipboard image.");
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);
        remoteStatus.getStyleClass().add("muted");
        remoteStatus.setWrapText(true);
        remoteStatus.setVisible(false);
        remoteStatus.setManaged(false);
        getChildren().addAll(title, actions, search, tree, hint, remoteStatus);
        refresh();
    }

    public Map<String, Node> controls()
    {
        return Collections.unmodifiableMap(controls);
    }

    public TokenDefinition find(String id)
    {
        return library.getEntries().stream().filter(e -> !e.isDeleted() && e.getToken().getId().equals(id))
                      .map(e -> TokenLibraryService.copy(e.getToken())).findFirst().orElse(null);
    }

    public TokenDefinition resolveDefinition(String id)
    {
        if (remote != null && (!remote.connected() || remoteRevision < 0))
            return null;
        return find(id);
    }

    public void beginRemote(RemoteOperations.Connection connection)
    {
        remote = Objects.requireNonNull(connection);
        remoteEpoch++;
        remoteRevision = -1;
        remoteQuery = false;
        remoteMutation = false;
        nextRemotePoll = 0;
        library = new TokenLibraryService.Library();
        rebuild();
        remoteStatus.setManaged(true);
        remoteStatus.setVisible(true);
        remoteStatus.setText("Loading host token library...");
        tickRemote();
    }

    public void endRemote()
    {
        remote = null;
        remoteEpoch++;
        remoteQuery = false;
        remoteMutation = false;
        remoteRevision = -1;
        remoteStatus.setManaged(false);
        remoteStatus.setVisible(false);
        refresh();
    }

    /** Frame hook: no work in standalone mode and at most one query per second while connected. */
    public void tickRemote()
    {
        if (remote == null || remoteQuery || remoteMutation || System.nanoTime() < nextRemotePoll)
            return;
        nextRemotePoll = System.nanoTime() + 1_000_000_000L;
        if (!remote.connected())
        {
            remoteStatus.setText("Host disconnected. Token edits are unavailable until reconnection.");
            return;
        }
        queryRemote(false);
    }

    public void refresh()
    {
        if (remote != null)
        {
            queryRemote(true);
            return;
        }
        attempt(() -> {
            library = service.load();
            rebuild();
        });
    }

    private void queryRemote(boolean force)
    {
        if (remoteQuery || remoteMutation)
            return;
        RemoteOperations.Connection connection = remote;
        long epoch = remoteEpoch;
        if (!connection.connected())
        {
            remoteStatus.setText("Host disconnected. Reconnect to reload tokens.");
            return;
        }
        remoteQuery = true;
        connection.query(RemoteTokens.DOMAIN, "list", RemoteTokens.json(new RemoteTokens.Stamp(force ? -1 : remoteRevision)))
                .thenCompose(value -> {
                    if (remote != connection || remoteEpoch != epoch || value == null || value.isNull())
                        return CompletableFuture.completedFuture(null);
                    try
                    {
                        return RemoteTokens.download(RemoteTokens.state(value), connection,
                                ForkJoinPool.commonPool(), Platform::runLater);
                    }
                    catch (IOException error)
                    {
                        return CompletableFuture.failedFuture(error);
                    }
                }).whenCompleteAsync((prepared, error) -> {
                    if (remote != connection || remoteEpoch != epoch)
                        return;
                    remoteQuery = false;
                    if (error != null)
                        remoteStatus.setText(message(error));
                    else if (prepared != null)
                        accept(prepared);
                    else
                        remoteStatus.setText("Host token library");
                }, Platform::runLater);
    }

    private void accept(RemoteTokens.Prepared prepared)
    {
        if (prepared.state().revision() < remoteRevision)
            return;
        prepared.acceptArtwork();
        library = prepared.library();
        remoteRevision = prepared.state().revision();
        remoteStatus.setText("Host token library");
        rebuild();
    }

    private EditContext editContext() throws IOException
    {
        if (remote != null && (!remote.connected() || remoteRevision < 0 || remoteMutation))
            throw new IOException("The host token library is not ready. Reconnect or reload and retry.");
        return new EditContext(remote, remoteEpoch, remoteRevision);
    }

    private void requireContext(EditContext context) throws IOException
    {
        if (context.connection != remote || context.epoch != remoteEpoch)
            throw new IOException("The remote session changed while this dialog was open. Reopen it and retry.");
        if (remote != null && !remote.connected())
            throw new IOException("The host is disconnected.");
    }

    private void remoteChange(EditContext context, RemoteTokens.Request request,
                              CompletableFuture<RemoteTokens.Definition> upload)
    {
        attempt(() -> {
            requireContext(context);
            if (remoteMutation)
                throw new IOException("Another token edit is awaiting host acceptance.");
            remoteMutation = true;
            remoteStatus.setText("Waiting for host token library...");
            upload.thenCompose(definition -> {
                try
                {
                    requireContext(context);
                    var completed = definition == null ? request : new RemoteTokens.Request(request.revision(),
                            request.operation(), definition, request.id(), request.folder(), request.parent(), request.name());
                    return context.connection.command(RemoteTokens.DOMAIN, "change", RemoteTokens.json(completed));
                }
                catch (IOException error)
                {
                    return CompletableFuture.failedFuture(error);
                }
            }).thenCompose(value -> {
                try
                {
                    return RemoteTokens.download(RemoteTokens.state(value), context.connection,
                            ForkJoinPool.commonPool(), Platform::runLater);
                }
                catch (IOException error)
                {
                    return CompletableFuture.failedFuture(error);
                }
            }).whenCompleteAsync((prepared, error) -> {
                if (remote != context.connection || remoteEpoch != context.epoch)
                    return;
                remoteMutation = false;
                if (error == null)
                    accept(prepared);
                else
                {
                    remoteStatus.setText(message(error));
                    LOG.warn("Token operation rejected by host", error);
                    Dialogs.error(window(), "Token operation rejected", message(error));
                    queryRemote(true);
                }
            }, Platform::runLater);
        });
    }

    private void remoteChange(EditContext context, RemoteTokens.Operation operation, String id,
                              String folder, String parent, String name)
    {
        remoteChange(context, new RemoteTokens.Request(context.revision, operation, null, id, folder, parent, name),
                CompletableFuture.completedFuture(null));
    }

    private static String message(Throwable error)
    {
        while ((error instanceof CompletionException || error instanceof java.util.concurrent.ExecutionException)
                && error.getCause() != null)
            error = error.getCause();
        return error.getMessage() == null ? "The token operation could not be completed." : error.getMessage();
    }

    private void rebuild()
    {
        Map<String, TreeItem<Item>> folders = new LinkedHashMap<>();
        TreeItem<Item> root = new TreeItem<>(new Item("", null));
        folders.put("", root);
        for (String folder : library.getFolders().stream().sorted().toList())
        {
            TreeItem<Item> item = new TreeItem<>(new Item(folder, null));
            folders.put(folder, item);
            folders.get(parent(folder)).getChildren().add(item);
        }
        for (var entry : library.getEntries().stream().filter(e -> !e.isDeleted())
                                .sorted(Comparator.comparing(e -> e.getToken().getName(), String.CASE_INSENSITIVE_ORDER)).toList())
        {
            if (TokenLibraryService.matches(entry.getToken().getName(), entry.getFolder(), search.getText()))
            {
                folders.get(entry.getFolder()).getChildren().add(new TreeItem<>(new Item(entry.getFolder(), entry.getToken())));
            }
        }
        if (!search.getText().isBlank())
            prune(root);
        folders.forEach((path, item) -> {
            item.setExpanded(!search.getText().isBlank() || expanded.contains(path));
            item.expandedProperty().addListener((obs, was, value) -> {
                if (search.getText().isBlank())
                {
                    if (value)
                        expanded.add(path);
                    else
                        expanded.remove(path);
                }
            });
        });
        tree.setRoot(root);
    }

    private void prune(TreeItem<Item> folder)
    {
        folder.getChildren().removeIf(item -> {
            if (item.getValue().token != null)
                return false;
            prune(item);
            return item.getChildren().isEmpty()
                    && !TokenLibraryService.matches("", item.getValue().folder, search.getText());
        });
    }

    private Item selected()
    {
        TreeItem<Item> selected = tree.getSelectionModel().getSelectedItem();
        return selected == null ? null : selected.getValue();
    }

    private String selectedFolder()
    {
        return selected() == null ? "" : selected().folder;
    }

    private Window window()
    {
        return getScene() == null ? null : getScene().getWindow();
    }

    private static String parent(String folder)
    {
        int slash = folder.lastIndexOf('/');
        return slash < 0 ? "" : folder.substring(0, slash);
    }

    private void create(boolean fromFile, boolean fromClipboard)
    {
        attempt(() -> {
            EditContext context = editContext();
            String folder = selectedFolder();
            if (context.connection != null)
            {
                var original = TokenDefinition.builder().id(UUID.randomUUID().toString()).name("New token").build();
                if (fromFile)
                {
                    java.io.File file = TokenEditor.chooseImageFile(window());
                    if (file != null)
                        prepareEditor(original, file.toPath(), folder, context);
                }
                else
                {
                    var image = fromClipboard ? Clipboard.getSystemClipboard().getImage() : null;
                    if (fromClipboard && image == null)
                        throw new IOException("The clipboard does not contain an image.");
                    TokenEditor.showRemote(window(), original, image, null)
                            .ifPresent(result -> save(result, folder, context));
                }
                return;
            }
            javafx.scene.image.Image image = fromFile ? TokenEditor.chooseImage(window())
                                                      : fromClipboard ? Clipboard.getSystemClipboard().getImage() : null;
            if (fromFile && image == null)
                return;
            if (fromClipboard && image == null)
                throw new IOException("The clipboard does not contain an image.");
            TokenDefinition token = TokenDefinition.builder().id(UUID.randomUUID().toString()).name("New token").build();
            TokenEditor.show(window(), token, image).ifPresent(result -> save(result, folder, context));
        });
    }

    private void edit(TokenDefinition token)
    {
        attempt(() -> {
            EditContext context = editContext();
            String folder = selectedFolder();
            if (context.connection != null)
            {
                prepareEditor(TokenLibraryService.copy(token), null, folder, context);
                return;
            }
            TokenEditor.show(window(), token, null).ifPresent(result -> save(result, folder, context));
        });
    }

    private void save(TokenDefinition token, String folder)
    {
        attempt(() -> {
            save(token, folder, editContext());
        });
    }

    private void prepareEditor(TokenDefinition original, java.nio.file.Path importFile, String folder, EditContext context)
    {
        CompletableFuture.supplyAsync(() -> {
            try
            {
                java.nio.file.Path source = importFile != null ? importFile
                        : original.getSourceImage() == null ? null : RemoteTokens.localArtwork(original.getSourceImage());
                return source == null ? null : RemoteImages.read(source, importFile == null, 0);
            }
            catch (IOException error)
            {
                throw new CompletionException(error);
            }
        }, ForkJoinPool.commonPool()).whenCompleteAsync((image, error) -> attempt(() -> {
            requireContext(context);
            if (error != null)
                throw new IOException(message(error), error);
            TokenEditor.showRemote(window(), original, importFile == null ? null : image, importFile == null ? image : null)
                    .ifPresent(result -> save(result, folder, context));
        }), Platform::runLater);
    }

    private void save(TokenDefinition token, String folder, EditContext context)
    {
        attempt(() -> {
            requireContext(context);
            if (context.connection != null)
            {
                var placeholder = new RemoteTokens.Definition(token.getId(), token.getName(), token.getColor(),
                        token.getSizeTiles(), token.getShape(), token.isBorder(), token.getZoom(),
                        token.getCenterX(), token.getCenterY(), null, null);
                remoteChange(context, new RemoteTokens.Request(context.revision, RemoteTokens.Operation.PUT,
                        placeholder, null, folder, null, null), RemoteTokens.upload(token, context.connection));
                return;
            }
            service.put(token, folder);
            LOG.info("Saved token '{}' in library folder '{}'", token.getName(), folder);
            changed.accept(token);
            refresh();
        });
    }

    private void newFolder()
    {
        attempt(() -> {
        EditContext context = editContext();
        String parent = selectedFolder();
        Dialogs.askText(window(), "New folder", "Token folder name", MaterialDesignF.FOLDER_PLUS_OUTLINE,
                        "Create", "", null).ifPresent(name -> attempt(() -> {
            requireContext(context);
            if (context.connection != null)
            {
                expanded.add(parent.isEmpty() ? name.trim() : parent + "/" + name.trim());
                remoteChange(context, RemoteTokens.Operation.CREATE_FOLDER, null, null, parent, name.trim());
                return;
            }
            expanded.add(service.createFolder(parent, name));
            LOG.info("Created token folder '{}' in '{}'", name, parent);
            refresh();
        }));
        });
    }

    private void rename(Item item)
    {
        if (item.token == null && item.folder.isEmpty())
            return;
        attempt(() -> {
        EditContext context = editContext();
        Dialogs.askText(window(), "Rename", "New name", MaterialDesignR.RENAME_BOX, "Rename", item.name(), null)
               .ifPresent(name -> attempt(() -> {
                   requireContext(context);
                   if (item.token != null)
                   {
                       TokenDefinition copy = TokenLibraryService.copy(item.token);
                       copy.setName(name);
                       save(copy, item.folder, context);
                   }
                   else
                   {
                       if (context.connection != null)
                       {
                           remoteChange(context, RemoteTokens.Operation.MOVE_FOLDER, null, item.folder, parent(item.folder), name.trim());
                           return;
                       }
                       service.moveFolder(item.folder, parent(item.folder), name);
                       LOG.info("Renamed token folder '{}' to '{}'", item.folder, name);
                       refresh();
                   }
               }));
        });
    }

    private void delete(Item item)
    {
        if (item.token == null && item.folder.isEmpty())
            return;
        attempt(() -> {
        EditContext context = editContext();
        if (Dialogs.confirmDanger(window(), "Delete token library item", "Delete " + item.name() + "?",
                                  "This removes the library entry" + (item.token == null ? " and all tokens in its subfolders" : "")
                                          + " and unused library image files. Placed tokens are kept; unopened maps retain their last-saved appearance.", "Delete"))
        {
            attempt(() -> {
                requireContext(context);
                if (context.connection != null)
                {
                    LOG.info("Deleting host token library {} '{}'", item.token == null ? "folder" : "token", item.name());
                    remoteChange(context, item.token == null ? RemoteTokens.Operation.DELETE_FOLDER : RemoteTokens.Operation.DELETE,
                            item.token == null ? null : item.token.getId(), item.token == null ? item.folder : null, null, null);
                    return;
                }
                if (item.token == null)
                    service.deleteFolder(item.folder);
                else
                    service.delete(item.token.getId());
                LOG.info("Deleted token library {} '{}'", item.token == null ? "folder" : "token", item.name());
                refresh();
            });
        }
        });
    }

    private ContextMenu menu(Item item)
    {
        ContextMenu menu = new ContextMenu();
        if (item.token != null)
        {
            menu.getItems().add(action("Edit image, color and size...", () -> edit(item.token)));
            menu.getItems().add(action("Duplicate", () -> save(TokenLibraryService.duplicate(item.token), item.folder)));
        }
        else
        {
            menu.getItems().add(action("New token here...", () -> create(false, false)));
            menu.getItems().add(action("New folder...", this::newFolder));
        }
        if (item.token != null || !item.folder.isEmpty())
        {
            menu.getItems().addAll(action("Rename...", () -> rename(item)), action("Delete...", () -> delete(item)));
        }
        return menu;
    }

    private static MenuItem action(String name, Runnable action)
    {
        MenuItem item = new MenuItem(name);
        item.setOnAction(e -> action.run());
        return item;
    }

    private final class TokenCell extends TreeCell<Item>
    {
        TokenCell()
        {
            setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && getItem() != null && getItem().token != null)
                    edit(getItem().token);
            });
            setOnContextMenuRequested(e -> {
                if (getItem() == null)
                    return;
                tree.getSelectionModel().select(getTreeItem());
                menu(getItem()).show(this, e.getScreenX(), e.getScreenY());
                e.consume();
            });
            setOnDragDetected(e -> {
                Item item = getItem();
                if (item == null || (item.token == null && item.folder.isEmpty()))
                    return;
                Dragboard board = startDragAndDrop(item.token == null ? new TransferMode[] { TransferMode.MOVE } : TransferMode.COPY_OR_MOVE);
                ClipboardContent content = new ClipboardContent();
                content.put(item.token == null ? FOLDER : TOKEN, item.token == null ? item.folder : item.token.getId());
                board.setContent(content);
                e.consume();
            });
            setOnDragOver(e -> {
                if (getItem() != null && (e.getDragboard().hasContent(TOKEN) || e.getDragboard().hasContent(FOLDER)))
                {
                    e.acceptTransferModes(TransferMode.MOVE);
                    pseudoClassStateChanged(DROP_TARGET, true);
                }
                e.consume();
            });
            setOnDragEntered(e -> {
                if (getItem() != null && getItem().token == null
                        && (e.getDragboard().hasContent(TOKEN) || e.getDragboard().hasContent(FOLDER)))
                {
                    getTreeItem().setExpanded(true);
                }
            });
            setOnDragExited(e -> pseudoClassStateChanged(DROP_TARGET, false));
            setOnDragDropped(e -> {
                pseudoClassStateChanged(DROP_TARGET, false);
                boolean success = false;
                if (getItem() != null)
                {
                    String folder = getItem().folder;
                    try
                    {
                        EditContext context = editContext();
                        Dragboard board = e.getDragboard();
                        if (board.hasContent(TOKEN))
                        {
                            if (context.connection != null)
                                remoteChange(context, RemoteTokens.Operation.MOVE, (String)board.getContent(TOKEN), folder, null, null);
                            else
                                service.move((String)board.getContent(TOKEN), folder);
                        }
                        else if (board.hasContent(FOLDER))
                        {
                            String source = (String)board.getContent(FOLDER);
                            if (context.connection != null)
                                remoteChange(context, RemoteTokens.Operation.MOVE_FOLDER, null, source, folder, source.substring(source.lastIndexOf('/') + 1));
                            else
                                service.moveFolder(source, folder, source.substring(source.lastIndexOf('/') + 1));
                        }
                        else
                            throw new IOException("Unsupported token drop.");
                        expanded.add(folder);
                        if (context.connection == null)
                            refresh();
                        success = true;
                    }
                    catch (IOException | IllegalArgumentException ex)
                    {
                        LOG.warn("Could not move token library item", ex);
                        Dialogs.error(window(), "Could not move token", ex.getMessage());
                    }
                }
                e.setDropCompleted(success);
                e.consume();
            });
        }

        @Override
        protected void updateItem(Item item, boolean empty)
        {
            super.updateItem(item, empty);
            setText(null);
            setGraphic(null);
            setTooltip(null);
            if (empty || item == null)
                return;
            setText(item.name());
            setTooltip(new Tooltip(item.name()));
            if (item.token == null)
                setGraphic(Icons.icon(MaterialDesignF.FOLDER_OUTLINE));
            else
            {
                Canvas thumb = new Canvas(36, 36);
                TokenArt.draw(thumb.getGraphicsContext2D(), item.token, 18, 18, 34);
                setGraphic(thumb);
            }
        }
    }

    @FunctionalInterface
    private interface Operation
    {
        void run() throws IOException;
    }

    private void attempt(Operation action)
    {
        try
        {
            action.run();
        }
        catch (IOException | IllegalArgumentException ex)
        {
            LOG.warn("Token operation failed", ex);
            Dialogs.error(window(), "Token operation failed", ex.getMessage());
        }
    }
}
