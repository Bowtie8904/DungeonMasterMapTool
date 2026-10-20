package dmmt.ui;

import dmmt.model.DmProject.TokenDefinition;
import dmmt.render.TokenArt;
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

public final class TokenBrowser extends VBox
{
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
        getChildren().addAll(title, actions, search, tree, hint);
        refresh();
    }

    public Map<String, Node> controls()
    {
        return Collections.unmodifiableMap(controls);
    }

    public TokenDefinition find(String id)
    {
        return library.getEntries().stream().filter(e -> !e.isDeleted() && e.getToken().getId().equals(id))
                      .map(TokenLibraryService.Entry::getToken).findFirst().orElse(null);
    }

    public void refresh()
    {
        attempt(() -> {
            library = service.load();
            rebuild();
        });
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
            javafx.scene.image.Image image = fromFile ? TokenEditor.chooseImage(window())
                                                      : fromClipboard ? Clipboard.getSystemClipboard().getImage() : null;
            if (fromFile && image == null)
                return;
            if (fromClipboard && image == null)
                throw new IOException("The clipboard does not contain an image.");
            TokenDefinition token = TokenDefinition.builder().id(UUID.randomUUID().toString()).name("New token").build();
            String folder = selectedFolder();
            TokenEditor.show(window(), token, image).ifPresent(result -> save(result, folder));
        });
    }

    private void edit(TokenDefinition token)
    {
        attempt(() -> TokenEditor.show(window(), token, null).ifPresent(result -> save(result, selectedFolder())));
    }

    private void save(TokenDefinition token, String folder)
    {
        attempt(() -> {
            service.put(token, folder);
            changed.accept(token);
            refresh();
        });
    }

    private void newFolder()
    {
        Dialogs.askText(window(), "New folder", "Token folder name", MaterialDesignF.FOLDER_PLUS_OUTLINE,
                        "Create", "", null).ifPresent(name -> attempt(() -> {
            expanded.add(service.createFolder(selectedFolder(), name));
            refresh();
        }));
    }

    private void rename(Item item)
    {
        if (item.token == null && item.folder.isEmpty())
            return;
        Dialogs.askText(window(), "Rename", "New name", MaterialDesignR.RENAME_BOX, "Rename", item.name(), null)
               .ifPresent(name -> attempt(() -> {
                   if (item.token != null)
                   {
                       TokenDefinition copy = TokenLibraryService.copy(item.token);
                       copy.setName(name);
                       save(copy, item.folder);
                   }
                   else
                   {
                       service.moveFolder(item.folder, parent(item.folder), name);
                       refresh();
                   }
               }));
    }

    private void delete(Item item)
    {
        if (item.token == null && item.folder.isEmpty())
            return;
        if (Dialogs.confirmDanger(window(), "Delete token library item", "Delete " + item.name() + "?",
                                  "This removes the library entry" + (item.token == null ? " and all tokens in its subfolders" : "")
                                          + " and unused library image files. Placed tokens are kept; unopened maps retain their last-saved appearance.", "Delete"))
        {
            attempt(() -> {
                if (item.token == null)
                    service.deleteFolder(item.folder);
                else
                    service.delete(item.token.getId());
                refresh();
            });
        }
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
                        Dragboard board = e.getDragboard();
                        if (board.hasContent(TOKEN))
                            service.move((String)board.getContent(TOKEN), folder);
                        else if (board.hasContent(FOLDER))
                        {
                            String source = (String)board.getContent(FOLDER);
                            service.moveFolder(source, folder, source.substring(source.lastIndexOf('/') + 1));
                        }
                        else
                            throw new IOException("Unsupported token drop.");
                        expanded.add(folder);
                        refresh();
                        success = true;
                    }
                    catch (IOException ex)
                    {
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
            Dialogs.error(window(), "Token operation failed", ex.getMessage());
        }
    }
}
