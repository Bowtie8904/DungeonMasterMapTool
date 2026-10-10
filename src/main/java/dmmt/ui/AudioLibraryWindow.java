package dmmt.ui;

import dmmt.audio.AudioCategory;
import dmmt.audio.AudioEngine;
import dmmt.audio.AudioFormats;
import dmmt.audio.AudioKind;
import dmmt.audio.AudioLibraryService;
import dmmt.audio.AudioTrack;
import dmmt.service.AppSettings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;
import org.kordamp.ikonli.materialdesign2.MaterialDesignV;
import org.kordamp.ikonli.materialdesign2.MaterialDesignW;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The audio library window (3.35.6): categories with colour and icon on the left, the tracks of the selected
 * category (or all sound effects) on the right, plus import, rename, move, delete and the cut-clips action.
 * Only one window is open at a time.
 */
public final class AudioLibraryWindow {
    private static final DataFormat TRACK_IDS = new DataFormat("application/x-dmmt-audio-tracks");
    private static Stage open;

    private final Stage stage;
    private final AudioLibraryService library;
    private final AudioEngine engine;
    private final Runnable onChanged;
    private final AppSettings settings;

    private final ObservableList<Entry> entries = FXCollections.observableArrayList();
    private final ListView<Entry> categoryList = new ListView<>(entries);
    private final ListView<Entry> effectsList = new ListView<>(
            FXCollections.observableArrayList(new Entry(null)));
    private boolean selectionSyncing;
    private final TableView<AudioTrack> trackTable = new TableView<>();
    private final TextField search = new TextField();
    private final Label summary = new Label();
    private final Label busyLabel = new Label();
    private final VBox activeImports = new VBox(6);
    private final Label importHint = new Label();
    private final Label importErrors = new Label();
    private final VBox busyBox = new VBox(8);
    private final Button cancelImports = new Button("Cancel unfinished imports");
    private final Button retryImports = new Button("Retry failed / cancelled");
    private final javafx.animation.Timeline queueRefresh = new javafx.animation.Timeline();

    /** One row of the left list: a music category, or the sound effects. */
    private record Entry(AudioCategory category) {
        boolean isEffects() {
            return category == null;
        }

        String label() {
            return isEffects() ? "Sound effects" : category.getName();
        }
    }

    private AudioLibraryWindow(Window owner, AudioLibraryService library, AudioEngine engine, AppSettings settings,
                               Runnable onChanged) {
        this.library = library;
        this.engine = engine;
        this.settings = settings;
        this.onChanged = onChanged == null ? () -> {
        } : onChanged;
        this.stage = new Stage();
        stage.setTitle("Audio library");
        if (owner != null) {
            stage.initOwner(owner);
            stage.initModality(Modality.NONE);
        }
        Dialogs.inheritIcons(stage, owner);
        stage.setScene(new Scene(buildRoot(), 940, 600));
        stage.setMinWidth(620);
        stage.setMinHeight(600);
        stage.getScene().getStylesheets().add(Icons.STYLESHEET);
        stage.setOnHidden(event -> {
            queueRefresh.stop();
            open = null;
        });
        queueRefresh.getKeyFrames().add(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(0.5),
                event -> refreshQueue()));
        queueRefresh.setCycleCount(javafx.animation.Animation.INDEFINITE);
        queueRefresh.play();
    }

    /** Opens the library window, or brings the open one to the front. */
    public static void show(Window owner, AudioLibraryService library, AudioEngine engine, Runnable onChanged) {
        show(owner, library, engine, null, onChanged);
    }

    static void show(Window owner, AudioLibraryService library, AudioEngine engine, AppSettings settings,
                     Runnable onChanged) {
        if (open != null && open.isShowing()) {
            open.toFront();
            open.requestFocus();
            return;
        }
        AudioLibraryWindow window = new AudioLibraryWindow(owner, library, engine,
                settings == null ? AppSettings.load() : settings, onChanged);
        open = window.stage;
        window.refreshCategories();
        window.refreshQueue();
        window.stage.show();
    }

    // ---- Layout ----

    private Region buildRoot() {
        BorderPane root = new BorderPane();
        root.getStyleClass().add("app-root");
        root.setLeft(buildCategories());
        root.setCenter(buildTracks());
        root.setPadding(new Insets(12));
        return root;
    }

    private Region buildCategories() {
        Label title = new Label("Music categories");
        title.getStyleClass().add("panel-title");

        categoryList.setPrefWidth(240);
        categoryList.setCellFactory(list -> new CategoryCell());
        categoryList.setContextMenu(categoryMenu());
        VBox.setVgrow(categoryList, Priority.ALWAYS);

        effectsList.setPrefWidth(240);
        effectsList.setCellFactory(list -> new CategoryCell());
        effectsList.setFixedCellSize(28);
        effectsList.setPrefHeight(32);
        effectsList.setMinHeight(32);
        effectsList.setMaxHeight(32);
        effectsList.getStyleClass().add("audio-effects-list");
        Icons.tooltip(effectsList, "All sound effects; they are not organised in categories.");

        // The two lists form one selection: picking in one clears the other (3.35.6).
        categoryList.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null && !selectionSyncing) {
                selectionSyncing = true;
                effectsList.getSelectionModel().clearSelection();
                selectionSyncing = false;
            }
            refreshTracks();
        });
        effectsList.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null && !selectionSyncing) {
                selectionSyncing = true;
                categoryList.getSelectionModel().clearSelection();
                selectionSyncing = false;
            }
            refreshTracks();
        });

        Button add = Icons.button(MaterialDesignP.PLUS, "Create a music category", this::createCategory);
        Button rename = Icons.button(MaterialDesignR.RENAME_BOX, "Rename the selected category", this::renameCategory);
        Button delete = Icons.button(MaterialDesignD.DELETE_OUTLINE,
                "Delete the selected category (its music moves to " + AudioCategory.UNCATEGORISED_NAME + ")",
                this::deleteCategory);
        delete.getStyleClass().add("danger");

        Button color = Icons.button(MaterialDesignP.PALETTE,
                "Colour of the selected category; its icon is tinted with it", this::chooseColor);
        Button icon = Icons.button(MaterialDesignS.SHAPE_OUTLINE, "Choose the icon of the selected category",
                this::chooseIcon);

        HBox buttons = new HBox(6, add, rename, delete, Icons.separator(), color, icon);
        buttons.setAlignment(Pos.CENTER_LEFT);
        buttons.setPadding(new Insets(8, 0, 0, 0));

        Runnable updateButtons = () -> {
            Entry current = selectedEntry();
            boolean category = current != null && !current.isEffects();
            boolean editable = category && !current.category().isUncategorised();
            rename.setDisable(!editable);
            delete.setDisable(!editable);
            color.setDisable(!category);
            icon.setDisable(!category);
        };
        categoryList.getSelectionModel().selectedItemProperty()
                .addListener((observable, oldValue, newValue) -> updateButtons.run());
        effectsList.getSelectionModel().selectedItemProperty()
                .addListener((observable, oldValue, newValue) -> updateButtons.run());
        updateButtons.run();

        Label effectsTitle = new Label("Sound effects");
        effectsTitle.getStyleClass().add("panel-title");

        VBox box = new VBox(8, title, categoryList, buttons, new javafx.scene.control.Separator(), effectsTitle,
                effectsList);
        box.setPadding(new Insets(0, 12, 0, 0));
        return box;
    }

    private Region buildTracks() {
        TableColumn<AudioTrack, String> name = new TableColumn<>("Name");
        name.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(data.getValue().getName()));
        name.setPrefWidth(260);
        name.setCellFactory(column -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(String text, boolean empty) {
                super.updateItem(text, empty);
                setText(empty ? null : text);
                AudioTrack track = empty || getIndex() >= getTableView().getItems().size()
                        ? null : getTableView().getItems().get(getIndex());
                setGraphic(track == null || track.getKind() != AudioKind.EFFECT ? null
                        : AudioIcons.tintedEffect(track.getIcon(), track.getColor(), 14));
            }
        });
        TableColumn<AudioTrack, String> duration = new TableColumn<>("Length");
        duration.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(data.getValue().durationText()));
        duration.setPrefWidth(90);
        TableColumn<AudioTrack, String> source = new TableColumn<>("Imported from");
        source.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                data.getValue().getOriginalFileName() == null ? "" : data.getValue().getOriginalFileName()));
        source.setPrefWidth(160);
        TableColumn<AudioTrack, String> gain = new TableColumn<>("Gain");
        gain.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                AudioLoudnessDialog.decibels(data.getValue().effectiveGainDb())
                        + (data.getValue().getGainOverrideDb() != null ? " (manual)"
                        : data.getValue().isLoudnessAnalyzed() ? " (auto)" : " (original)")));
        gain.setPrefWidth(145);
        TableColumn<AudioTrack, String> status = new TableColumn<>("Preparation");
        status.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(preparationText(data.getValue())));
        status.setPrefWidth(220);
        trackTable.getColumns().setAll(List.of(name, duration, gain, source, status));
        trackTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        trackTable.setPlaceholder(new Label("No audio here yet - use \"Import files\"."));
        trackTable.setContextMenu(trackMenu());
        trackTable.setRowFactory(table -> {
            javafx.scene.control.TableRow<AudioTrack> row = new javafx.scene.control.TableRow<>();
            // Hidden sound effects are greyed out instead of carrying a text marker (3.35.2).
            row.itemProperty().addListener((observable, oldValue, newValue) -> {
                row.getStyleClass().remove("audio-hidden");
                if (newValue != null && newValue.isHidden()) {
                    row.getStyleClass().add("audio-hidden");
                }
            });
            row.setOnDragDetected(event -> {
                if (row.isEmpty()) {
                    return;
                }
                Dragboard board = row.startDragAndDrop(TransferMode.MOVE);
                ClipboardContent content = new ClipboardContent();
                content.put(TRACK_IDS, selectedTracks().stream().map(AudioTrack::getId).toList());
                content.putString(row.getItem().getName());
                board.setContent(content);
                event.consume();
            });
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    preview(row.getItem());
                }
            });
            return row;
        });
        VBox.setVgrow(trackTable, Priority.ALWAYS);

        search.setPromptText("Search audio");
        search.textProperty().addListener((observable, oldValue, newValue) -> refreshTracks());
        HBox.setHgrow(search, Priority.ALWAYS);

        Button importFiles = new Button("Import files");
        importFiles.setGraphic(Icons.icon(MaterialDesignF.FILE_MUSIC_OUTLINE));
        importFiles.setOnAction(event -> importFiles());
        Button importFolder = Icons.button(MaterialDesignF.FOLDER_MUSIC_OUTLINE,
                "Import a folder and all of its subfolders; every subfolder becomes a music category",
                this::importFolder);
        Button rename = Icons.button(MaterialDesignR.RENAME_BOX, "Rename the selected track", this::renameTrack);
        Button delete = Icons.button(MaterialDesignD.DELETE_OUTLINE, "Delete the selected tracks", this::deleteTracks);
        delete.getStyleClass().add("danger");
        Button cut = new Button("Cut clips");
        cut.setGraphic(Icons.icon(MaterialDesignC.CONTENT_CUT));
        Icons.tooltip(cut, "Split a long recording into standalone clips with a waveform view.");
        cut.setOnAction(event -> cutClips());
        Button play = Icons.button(MaterialDesignP.PLAY, "Listen to the selected track in the waveform view", () -> {
            AudioTrack track = trackTable.getSelectionModel().getSelectedItem();
            if (track != null) {
                preview(track);
            }
        });
        Button loudness = Icons.button(MaterialDesignV.VOLUME_HIGH,
                "Adjust the selected file's loudness or restore its automatic level", this::adjustLoudness);
        loudness.setId("audioLibraryLoudness");
        loudness.disableProperty().bind(javafx.beans.binding.Bindings.size(
                trackTable.getSelectionModel().getSelectedItems()).isNotEqualTo(1));

        javafx.scene.layout.FlowPane tools = new javafx.scene.layout.FlowPane(6, 6,
                importFiles, importFolder, Icons.separator(), play, loudness, rename, delete,
                Icons.separator(), cut);
        tools.setAlignment(Pos.CENTER_LEFT);

        summary.getStyleClass().add("muted");
        busyLabel.setId("audioImportSummary");
        busyLabel.setWrapText(true);
        busyLabel.getStyleClass().add("panel-title");
        activeImports.setId("audioImportProgress");
        importHint.setWrapText(true);
        importHint.getStyleClass().add("muted");
        importErrors.setWrapText(true);
        importErrors.setId("audioImportErrors");
        cancelImports.setId("audioImportCancel");
        cancelImports.setMinWidth(Region.USE_PREF_SIZE);
        cancelImports.setOnAction(event -> {
            library.preparationQueue().cancelRemaining();
            refreshQueue();
        });
        Icons.tooltip(cancelImports, "Stop queued and running imports. Ready tracks are kept; cancelled files can be retried.");
        retryImports.setId("audioImportRetry");
        retryImports.setMinWidth(Region.USE_PREF_SIZE);
        retryImports.setOnAction(event -> {
            run(() -> library.preparationQueue().retryFailed(), "Could not retry preparation");
            refreshQueue();
        });
        Icons.tooltip(retryImports, "Retry failed and cancelled imports, using the library copy when it is available.");
        javafx.scene.layout.FlowPane actions = new javafx.scene.layout.FlowPane(8, 6, cancelImports, retryImports);
        actions.setId("audioImportActions");
        busyBox.getStyleClass().add("audio-import-panel");
        busyBox.setId("audioImportPanel");
        busyBox.getChildren().setAll(busyLabel, activeImports, actions, importErrors, importHint);
        showBusy(false);

        VBox footer = new VBox(8, summary, busyBox);
        VBox box = new VBox(8, tools, search, trackTable, footer);
        box.setMinWidth(0);
        return box;
    }

    /** Keeps metadata controls usable while preparations continue in the library-owned queue. */
    private void showBusy(boolean busy) {
        busyBox.setVisible(busy);
        busyBox.setManaged(busy);
    }

    /** Right-click menu of the category list: style, hide from the audio overlay (3.35.2) and delete. */
    private ContextMenu categoryMenu() {
        ContextMenu menu = new ContextMenu();
        MenuItem rename = new MenuItem("Rename...");
        rename.setOnAction(event -> renameCategory());
        MenuItem color = new MenuItem("Choose colour...");
        color.setOnAction(event -> chooseColor());
        MenuItem icon = new MenuItem("Choose icon...");
        icon.setOnAction(event -> chooseIcon());
        MenuItem hide = new MenuItem("Hide in overlay");
        hide.setOnAction(event -> toggleCategoryHidden());
        MenuItem delete = new MenuItem("Delete");
        delete.getStyleClass().add("danger");
        delete.setOnAction(event -> deleteCategory());
        menu.getItems().setAll(rename, color, icon, hide, new javafx.scene.control.SeparatorMenuItem(), delete);
        menu.setOnShowing(event -> {
            Entry entry = selectedEntry();
            boolean category = entry != null && !entry.isEffects();
            boolean editable = category && !entry.category().isUncategorised();
            rename.setDisable(!editable);
            delete.setDisable(!editable);
            color.setDisable(!category);
            icon.setDisable(!category);
            hide.setDisable(!category);
            hide.setText(category && entry.category().isHidden() ? "Show in overlay" : "Hide in overlay");
        });
        return menu;
    }

    private void toggleCategoryHidden() {
        Entry entry = selectedEntry();
        if (entry == null || entry.isEffects()) {
            return;
        }
        boolean hidden = entry.category().isHidden();
        run(() -> library.setCategoryHidden(entry.category().getId(), !hidden),
                "Could not change the overlay visibility");
    }

    private void toggleEffectsHidden() {
        List<AudioTrack> tracks = selectedTracks();
        if (tracks.isEmpty()) {
            return;
        }
        boolean hidden = tracks.get(0).isHidden();
        run(() -> {
            for (AudioTrack track : tracks) {
                library.setTrackHidden(track.getId(), !hidden);
            }
        }, "Could not change the overlay visibility");
    }

    private void chooseEffectIcon() {
        AudioTrack track = trackTable.getSelectionModel().getSelectedItem();
        if (track == null) {
            return;
        }
        AudioIconPicker.show(stage, track, icon ->
                run(() -> library.styleTrack(track.getId(), null, icon), "Could not change the icon"));
    }

    private void chooseEffectColor() {
        AudioTrack track = trackTable.getSelectionModel().getSelectedItem();
        if (track == null) {
            return;
        }
        AudioColorPicker.show(stage, "Colour of \"" + track.getName() + "\"", track.getColor(),
                AudioIcons.effectByDescription(track.getIcon()),
                color -> run(() -> library.styleTrack(track.getId(), color, null),
                        "Could not change the colour"));
    }

    private ContextMenu trackMenu() {
        ContextMenu menu = new ContextMenu();
        MenuItem play = new MenuItem("Play", Icons.icon(MaterialDesignP.PLAY));
        play.setOnAction(event -> {
            AudioTrack track = trackTable.getSelectionModel().getSelectedItem();
            settings.put("audio.lastCategory", track.getCategoryId());
            engine.playTrack(track.getId());
        });
        Menu moveTo = new Menu("Move to");
        MenuItem rename = new MenuItem("Rename...");
        rename.setOnAction(event -> renameTrack());
        MenuItem makeEffect = new MenuItem("Use as sound effect");
        makeEffect.setOnAction(event -> changeKind(AudioKind.EFFECT));
        MenuItem makeMusic = new MenuItem("Use as music");
        makeMusic.setOnAction(event -> changeKind(AudioKind.MUSIC));
        MenuItem cut = new MenuItem("Cut clips...");
        cut.setOnAction(event -> cutClips());
        MenuItem loudness = new MenuItem("Loudness...");
        loudness.setOnAction(event -> adjustLoudness());
        MenuItem effectIcon = new MenuItem("Choose icon...");
        effectIcon.setOnAction(event -> chooseEffectIcon());
        MenuItem effectColor = new MenuItem("Choose colour...");
        effectColor.setOnAction(event -> chooseEffectColor());
        MenuItem hide = new MenuItem("Hide in overlay");
        hide.setOnAction(event -> toggleEffectsHidden());
        MenuItem delete = new MenuItem("Delete");
        delete.getStyleClass().add("danger");
        delete.setOnAction(event -> deleteTracks());
        MenuItem prioritize = new MenuItem("Prepare next");
        prioritize.setOnAction(event -> {
            AudioTrack selected = trackTable.getSelectionModel().getSelectedItem();
            if (selected != null) {
                library.preparationQueue().prioritize(selected.getId());
            }
        });
        MenuItem retry = new MenuItem("Retry preparation");
        retry.setOnAction(event -> run(() -> library.preparationQueue().retry(
                trackTable.getSelectionModel().getSelectedItem().getId()), "Could not retry preparation"));
        menu.getItems().setAll(play, rename, loudness, moveTo, makeEffect, makeMusic, cut, effectColor, effectIcon, hide,
                prioritize, retry,
                new javafx.scene.control.SeparatorMenuItem(), delete);
        menu.setOnShowing(event -> {
            moveTo.getItems().clear();
            for (AudioCategory category : library.categories()) {
                MenuItem item = new MenuItem(category.getName());
                item.setGraphic(AudioIcons.tinted(category.getIcon(), category.getColor(), 14));
                item.setOnAction(e -> moveSelected(category.getId()));
                moveTo.getItems().add(item);
            }
            boolean effects = isEffectsView();
            AudioTrack selected = trackTable.getSelectionModel().getSelectedItem();
            play.setVisible(!effects);
            boolean notReady = selected == null || !selected.isReady();
            play.setDisable(effects || selectedTracks().size() != 1 || notReady);
            loudness.setDisable(selectedTracks().size() != 1 || notReady);
            cut.setDisable(notReady);
            prioritize.setDisable(!notReady || selected == null);
            retry.setDisable(selected == null || selected.isReady()
                    || selected.getPreparationState() == AudioTrack.PreparationState.PENDING);
            moveTo.setDisable(effects || moveTo.getItems().isEmpty());
            makeEffect.setDisable(effects);
            makeMusic.setDisable(!effects);
            effectIcon.setVisible(effects);
            effectColor.setVisible(effects);
            effectIcon.setDisable(selected == null);
            effectColor.setDisable(selected == null);
            hide.setVisible(effects);
            hide.setDisable(selected == null);
            hide.setText(selected != null && selected.isHidden() ? "Show in overlay" : "Hide in overlay");
        });
        return menu;
    }

    /** Category rows and the sound effects row are drop targets, so tracks move and change kind by drag & drop. */
    private final class CategoryCell extends ListCell<Entry> {
        private CategoryCell() {
            setOnDragOver(event -> {
                if (event.getGestureSource() != this && isDropTarget(event.getDragboard())) {
                    event.acceptTransferModes(TransferMode.MOVE);
                }
                event.consume();
            });
            setOnDragEntered(event -> {
                if (isDropTarget(event.getDragboard())) {
                    getStyleClass().add("audio-drop-target");
                }
            });
            setOnDragExited(event -> getStyleClass().remove("audio-drop-target"));
            setOnDragDropped(event -> {
                getStyleClass().remove("audio-drop-target");
                if (!isDropTarget(event.getDragboard())) {
                    return;
                }
                @SuppressWarnings("unchecked")
                List<String> ids = (List<String>) event.getDragboard().getContent(TRACK_IDS);
                dropTracks(ids, getItem());
                event.setDropCompleted(true);
                event.consume();
            });
        }

        private boolean isDropTarget(Dragboard board) {
            return board.hasContent(TRACK_IDS) && getItem() != null;
        }

        @Override
        protected void updateItem(Entry Entry, boolean empty) {
            super.updateItem(Entry, empty);
            getStyleClass().removeAll("audio-hidden", "audio-drop-target");
            if (empty || Entry == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            setText(Entry.label() + "  (" + count(Entry) + ")");
            if (!Entry.isEffects() && Entry.category().isHidden()) {
                getStyleClass().add("audio-hidden");
            }
            setGraphic(Entry.isEffects()
                    ? Icons.icon(MaterialDesignW.WAVES)
                    : AudioIcons.tinted(Entry.category().getIcon(), Entry.category().getColor(), 16));
        }

        private int count(Entry Entry) {
            return (int) library.tracks().stream().filter(track -> Entry.isEffects()
                    ? track.getKind() == AudioKind.EFFECT
                    : track.isMusic() && java.util.Objects.equals(track.getCategoryId(), Entry.category().getId())).count();
        }
    }

    // ---- Data ----

    private void refreshCategories() {
        Entry selected = selectedEntry();
        boolean wasEffects = selected != null && selected.isEffects();
        List<Entry> items = new ArrayList<>();
        library.categories().forEach(category -> items.add(new Entry(category)));
        entries.setAll(items);
        selectionSyncing = true;
        if (wasEffects || items.isEmpty()) {
            categoryList.getSelectionModel().clearSelection();
            effectsList.getSelectionModel().select(0);
        } else {
            effectsList.getSelectionModel().clearSelection();
            Entry wanted = items.stream()
                    .filter(entry -> selected != null && entry.label().equals(selected.label()))
                    .findFirst().orElse(items.get(0));
            categoryList.getSelectionModel().select(wanted);
        }
        selectionSyncing = false;
        // The sound effect row shows a track count that may have changed too.
        effectsList.refresh();
        refreshTracks();
        onChanged.run();
    }

    private void refreshTracks() {
        Entry Entry = selectedEntry();
        List<AudioTrack> tracks = library.tracks().stream()
                .filter(track -> Entry == null || Entry.isEffects() ? track.getKind() == AudioKind.EFFECT
                        : track.isMusic() && java.util.Objects.equals(track.getCategoryId(), Entry.category().getId()))
                .sorted(java.util.Comparator.comparing(AudioTrack::getName, String.CASE_INSENSITIVE_ORDER)).toList();
        String filter = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        if (!filter.isEmpty()) {
            tracks = tracks.stream().filter(track -> track.getName().toLowerCase(Locale.ROOT).contains(filter)).toList();
        }
        trackTable.getItems().setAll(tracks);
        long totalMs = tracks.stream().mapToLong(AudioTrack::getDurationMs).sum();
        summary.setText(tracks.size() + (tracks.size() == 1 ? " track, " : " tracks, ")
                + AudioTrack.formatDuration(totalMs) + " total - library folder: " + library.root());
    }

    private Entry selectedEntry() {
        Entry effects = effectsList.getSelectionModel().getSelectedItem();
        return effects != null ? effects : categoryList.getSelectionModel().getSelectedItem();
    }

    private boolean isEffectsView() {
        Entry Entry = selectedEntry();
        return Entry == null || Entry.isEffects();
    }

    private List<AudioTrack> selectedTracks() {
        return List.copyOf(trackTable.getSelectionModel().getSelectedItems());
    }

    private String currentCategoryId() {
        Entry Entry = selectedEntry();
        return Entry == null || Entry.isEffects() ? AudioCategory.UNCATEGORISED_ID : Entry.category().getId();
    }

    private AudioKind currentKind() {
        return isEffectsView() ? AudioKind.EFFECT : AudioKind.MUSIC;
    }

    // ---- Actions ----

    private void createCategory() {
        Dialogs.askText(stage, "New category", "Name of the new music category", MaterialDesignM.MUSIC_BOX_OUTLINE,
                        "Create", "", name -> name.isBlank() ? "Enter a name." : null)
                .ifPresent(name -> run(() -> library.createCategory(name, AudioCategory.DEFAULT_COLOR,
                        AudioCategory.DEFAULT_ICON), "Could not create the category"));
    }

    private void renameCategory() {
        Entry Entry = selectedEntry();
        if (Entry == null || Entry.isEffects()) {
            return;
        }
        Dialogs.askText(stage, "Rename category", "New name", MaterialDesignR.RENAME_BOX, "Rename",
                        Entry.category().getName(), name -> name.isBlank() ? "Enter a name." : null)
                .ifPresent(name -> run(() -> library.renameCategory(Entry.category().getId(), name),
                        "Could not rename the category"));
    }

    private void deleteCategory() {
        Entry Entry = selectedEntry();
        if (Entry == null || Entry.isEffects()) {
            return;
        }
        int tracks = library.musicOf(Entry.category().getId()).size();
        String message = tracks == 0
                ? "The category is empty."
                : tracks + (tracks == 1 ? " track moves" : " tracks move") + " to " + AudioCategory.UNCATEGORISED_NAME
                + ". No audio file is deleted.";
        if (Dialogs.confirmDanger(stage, "Delete category", "Delete \"" + Entry.category().getName() + "\"?",
                message, "Delete")) {
            run(() -> library.deleteCategory(Entry.category().getId()), "Could not delete the category");
        }
    }

    private void chooseIcon() {
        Entry Entry = selectedEntry();
        if (Entry == null || Entry.isEffects()) {
            return;
        }
        AudioIconPicker.show(stage, Entry.category(), icon ->
                run(() -> library.styleCategory(Entry.category().getId(), null, icon), "Could not change the icon"));
    }

    private void chooseColor() {
        Entry entry = selectedEntry();
        if (entry == null || entry.isEffects()) {
            return;
        }
        AudioCategory category = entry.category();
        AudioColorPicker.show(stage, "Colour of \"" + category.getName() + "\"", category.getColor(),
                AudioIcons.byDescription(category.getIcon()),
                color -> run(() -> library.styleCategory(category.getId(), color, null),
                        "Could not change the category colour"));
    }

    private void importFiles() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import audio files");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(AudioFormats.FILTER_DESCRIPTION,
                AudioFormats.FILTER_PATTERNS));
        File start = lastDirectory();
        if (start != null) {
            chooser.setInitialDirectory(start);
        }
        List<File> files = chooser.showOpenMultipleDialog(stage);
        if (files != null && !files.isEmpty()) {
            rememberDirectory(files.get(0).getParentFile());
            importAll(files.stream().map(file -> new ImportEntry(file.toPath(), null)).toList());
        }
    }

    private void importFolder() {
        javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
        chooser.setTitle("Import a folder of audio files");
        File start = lastDirectory();
        if (start != null) {
            chooser.setInitialDirectory(start);
        }
        File folder = chooser.showDialog(stage);
        if (folder == null) {
            return;
        }
        rememberDirectory(folder);
        javafx.concurrent.Task<List<ImportEntry>> scan = new javafx.concurrent.Task<>() {
            @Override
            protected List<ImportEntry> call() throws IOException {
                return AudioLibraryService.scanFolder(folder.toPath()).stream()
                        .map(found -> new ImportEntry(found.file(), found.folderCategory())).toList();
            }
        };
        scan.setOnSucceeded(event -> importAll(scan.getValue()));
        scan.setOnFailed(event -> Dialogs.error(stage, "Could not read the folder", scan.getException().getMessage()));
        dmmt.service.WorkScheduler.shared().executor(dmmt.service.WorkScheduler.Kind.COPY).execute(scan);
    }

    /** One file to import, with the subfolder name that should become its category, or {@code null}. */
    private record ImportEntry(Path file, String folderCategory) {
    }

    private void importAll(List<ImportEntry> files) {
        if (files.isEmpty()) {
            summary.setText("No supported audio files found (" + AudioFormats.FILTER_DESCRIPTION + ").");
            return;
        }
        AudioKind kind = currentKind();
        String categoryId = currentCategoryId();
        for (ImportEntry entry : files) {
            library.preparationQueue().enqueue(entry.file(), kind, categoryId, entry.folderCategory());
        }
        refreshQueue();
    }

    private String queueSignature = "";

    private String preparationText(AudioTrack track) {
        if (track.isReady()) {
            return "Ready";
        }
        var item = library.preparationQueue().items().stream()
                .filter(task -> track.getId().equals(task.trackId())).findFirst();
        return item.map(task -> stageText(task.stage()) + (isActive(task.stage())
                ? " " + Math.round(task.progress() * 100) + "%" : "")).orElse(
                switch (track.getPreparationState()) {
                    case READY -> "Ready";
                    case PENDING -> "Waiting";
                    case FAILED -> "Failed";
                    case CANCELLED -> "Cancelled";
                })
                + (track.getPreparationError() == null ? "" : ": " + track.getPreparationError());
    }

    static String stageText(dmmt.audio.AudioPreparationQueue.Stage stage) {
        return switch (stage) {
            case QUEUED -> "Waiting";
            case COPYING -> "Copying into library";
            case ANALYZING -> "Analysing loudness and waveform";
            case PLAYBACK -> "Preparing playback";
            case READY -> "Ready";
            case FAILED -> "Failed";
            case CANCELLED -> "Cancelled";
        };
    }

    private static boolean isActive(dmmt.audio.AudioPreparationQueue.Stage stage) {
        return stage == dmmt.audio.AudioPreparationQueue.Stage.COPYING
                || stage == dmmt.audio.AudioPreparationQueue.Stage.ANALYZING
                || stage == dmmt.audio.AudioPreparationQueue.Stage.PLAYBACK;
    }

    private void refreshQueue() {
        List<dmmt.audio.AudioPreparationQueue.Item> tasks = library.preparationQueue().items();
        var active = tasks.stream().filter(item -> isActive(item.stage())).toList();
        long queued = tasks.stream().filter(item -> item.stage() == dmmt.audio.AudioPreparationQueue.Stage.QUEUED).count();
        long failed = tasks.stream().filter(item -> item.stage() == dmmt.audio.AudioPreparationQueue.Stage.FAILED).count();
        long cancelled = tasks.stream().filter(item -> item.stage() == dmmt.audio.AudioPreparationQueue.Stage.CANCELLED).count();
        long ready = tasks.stream().filter(item -> item.stage() == dmmt.audio.AudioPreparationQueue.Stage.READY).count();
        // Persisted failures/cancellations remain discoverable even after restarting the application.
        showBusy(!tasks.isEmpty() || library.tracks().stream().anyMatch(track -> !track.isReady()));
        busyLabel.setText("Audio imports: " + ready + " ready · " + active.size() + " active · "
                + queued + " waiting · " + failed + " failed · " + cancelled + " cancelled");
        activeImports.getChildren().clear();
        for (var item : active) {
            Label name = new Label(item.source().getFileName().toString());
            name.setMinWidth(0);
            name.setMaxWidth(Double.MAX_VALUE);
            Icons.tooltip(name, item.source().toString());
            Label stage = new Label(stageText(item.stage()) + " — " + Math.round(item.progress() * 100) + "%");
            stage.setWrapText(true);
            ProgressBar progress = new ProgressBar(item.progress());
            progress.setMinWidth(0);
            progress.setMaxWidth(Double.MAX_VALUE);
            VBox row = new VBox(3, name, stage, progress);
            row.getStyleClass().add("audio-import-file");
            activeImports.getChildren().add(row);
        }
        activeImports.setVisible(!active.isEmpty());
        activeImports.setManaged(!active.isEmpty());
        cancelImports.setDisable(active.isEmpty() && queued == 0);
        retryImports.setDisable(failed + cancelled == 0);
        importHint.setText(active.isEmpty() && queued == 0 && failed + cancelled == 0
                ? "All imports are ready to play."
                : "Percentages show the current step, not the whole import. Only ready tracks can play. "
                + "Cancel stops unfinished work, not ready tracks. "
                + "Imports continue when you close this window.");
        String signature = tasks.stream().map(item -> item.trackId() + ":" + item.stage() + ":" + item.error())
                .collect(java.util.stream.Collectors.joining("|"));
        if (!signature.equals(queueSignature)) {
            queueSignature = signature;
            List<String> selection = selectedTracks().stream().map(AudioTrack::getId).toList();
            refreshCategories();
            for (AudioTrack track : trackTable.getItems()) {
                if (selection.contains(track.getId())) {
                    trackTable.getSelectionModel().select(track);
                }
            }
        }
        trackTable.refresh();
        String failures = tasks.stream().filter(item -> item.error() != null)
                .map(item -> item.source().getFileName() + ": " + item.error())
                .collect(java.util.stream.Collectors.joining("\n"));
        importErrors.setText(failed + cancelled == 0 ? ""
                : "Files needing attention: " + failed + " failed, " + cancelled
                + " cancelled. Retry them here, or right-click a track to retry it individually.");
        importErrors.setVisible(failed + cancelled > 0);
        importErrors.setManaged(failed + cancelled > 0);
        Icons.tooltip(importErrors, failures);
    }

    private void renameTrack() {
        AudioTrack track = trackTable.getSelectionModel().getSelectedItem();
        if (track == null) {
            return;
        }
        Dialogs.askText(stage, "Rename audio", "New name", MaterialDesignR.RENAME_BOX, "Rename", track.getName(),
                        name -> name.isBlank() ? "Enter a name." : null)
                .ifPresent(name -> run(() -> library.renameTrack(track.getId(), name), "Could not rename the track"));
    }

    private void deleteTracks() {
        List<AudioTrack> tracks = selectedTracks();
        if (tracks.isEmpty()) {
            return;
        }
        String what = tracks.size() == 1 ? "\"" + tracks.get(0).getName() + "\"" : tracks.size() + " tracks";
        if (!Dialogs.confirmDanger(stage, "Delete audio", "Delete " + what + "?",
                "The audio files are deleted from the library folder. This cannot be undone.", "Delete")) {
            return;
        }
        run(() -> {
            for (AudioTrack track : tracks) {
                engine.setEffectActive(track.getId(), false);
                library.deleteTrack(track.getId());
            }
        }, "Could not delete the audio");
    }

    private void changeKind(AudioKind kind) {
        List<AudioTrack> tracks = selectedTracks();
        if (tracks.isEmpty()) {
            return;
        }
        run(() -> {
            for (AudioTrack track : tracks) {
                library.changeKind(track.getId(), kind, currentCategoryId());
            }
        }, "Could not change the kind");
    }

    private void moveSelected(String categoryId) {
        moveTracks(selectedTracks().stream().map(AudioTrack::getId).toList(), categoryId);
    }

    private void moveTracks(List<String> trackIds, String categoryId) {
        run(() -> {
            for (String id : trackIds) {
                library.moveTrack(id, categoryId);
            }
        }, "Could not move the audio");
    }

    /**
     * Drop target of the left list: a category turns the dropped tracks into music of that category, the
     * "Sound effects" row turns them into sound effects (3.35.2).
     */
    private void dropTracks(List<String> trackIds, Entry target) {
        if (target == null || trackIds == null || trackIds.isEmpty()) {
            return;
        }
        AudioKind kind = target.isEffects() ? AudioKind.EFFECT : AudioKind.MUSIC;
        String categoryId = target.isEffects() ? null : target.category().getId();
        run(() -> {
            for (String id : trackIds) {
                library.changeKind(id, kind, categoryId);
            }
        }, "Could not move the audio");
    }

    private void cutClips() {
        AudioTrack track = trackTable.getSelectionModel().getSelectedItem();
        if (track == null) {
            Dialogs.error(stage, "Select a track first",
                    "Select the long recording you want to split into clips, then choose \"Cut clips\".");
            return;
        }
        if (!track.isReady()) {
            return;
        }
        AudioCutWindow.show(stage, library, track, this::refreshCategories);
    }

    /** Opens the waveform view, where the track can be listened to and split into clips. */
    private void preview(AudioTrack track) {
        if (!track.isReady()) {
            return;
        }
        AudioCutWindow.show(stage, library, track, this::refreshCategories);
    }

    private void adjustLoudness() {
        List<AudioTrack> selected = selectedTracks();
        if (selected.size() != 1) {
            return;
        }
        AudioTrack track = selected.get(0);
        if (!track.isReady()) {
            return;
        }
        new AudioLoudnessDialog(stage, library, track, () -> {
            engine.refreshTrackVolumes();
            trackTable.refresh();
            onChanged.run();
        }, () -> preview(track)).show();
    }

    /** @return the window of this library, for dialogs opened by callers. */
    public Stage stage() {
        return stage;
    }

    private File lastDirectory() {
        String stored = settings.get("audio.lastDirectory", "");
        if (stored == null || stored.isBlank()) {
            return null;
        }
        File file = new File(stored);
        return file.isDirectory() ? file : null;
    }

    private void rememberDirectory(File folder) {
        if (folder != null && folder.isDirectory()) {
            settings.put("audio.lastDirectory", folder.getAbsolutePath());
        }
    }

    /** Runs a library change, reports failures and refreshes everything that shows library data. */
    private void run(LibraryAction action, String failureHeader) {
        try {
            action.run();
            refreshCategories();
        } catch (IOException | RuntimeException e) {
            Dialogs.error(stage, failureHeader, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private interface LibraryAction {
        void run() throws IOException;
    }
}
