package dmmt;

import dmmt.lighting.LightingEngine;
import dmmt.lighting.TimeOfDayPreset;
import dmmt.model.DmProject;
import dmmt.model.FogMask;
import dmmt.render.CanvasMapRenderer;
import dmmt.service.Dd2vttImportService;
import dmmt.service.FogService;
import dmmt.service.MapRotationService;
import dmmt.service.MapLibraryService;
import dmmt.service.ProjectService;
import dmmt.ui.CollapsibleSection;
import dmmt.ui.Dialogs;
import dmmt.ui.Icons;
import dmmt.ui.MapBrowser;
import dmmt.ui.MapLocationDialog;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Rectangle2D;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignB;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;
import org.kordamp.ikonli.materialdesign2.MaterialDesignE;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignI;
import org.kordamp.ikonli.materialdesign2.MaterialDesignL;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;
import org.kordamp.ikonli.materialdesign2.MaterialDesignT;
import org.kordamp.ikonli.materialdesign2.MaterialDesignU;
import org.kordamp.ikonli.materialdesign2.MaterialDesignW;
import javafx.scene.image.Image;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

public class DungeonMasterMapToolApplication extends Application {
    private static final String PREF_LAST_IMPORT_DIRECTORY = "lastImportDirectory";
    private static final String PREF_PLAYER_SCREEN_INDEX = "playerScreenIndex";
    private static final String PREF_SCREEN_DIAGONAL_PREFIX = "screenDiagonalInches.";
    private static final String PREF_TILE_INCHES = "playerTileInches";
    private static final String PREF_SIDEBAR_VISIBLE = "sidebarVisible";
    private static final String PREF_CONTROLS_EXPANDED = "controlsExpanded";
    private static final String PREF_FOG_CELLS_PER_GRID = "fogCellsPerGrid";
    private static final String PREF_LIGHT_TINT = "lightTint";
    private static final String APP_ICON_RESOURCE = "/dmmt/icon.png";
    private static List<Image> appIcons;

    private final ProjectService projectService = new ProjectService();
    private final Dd2vttImportService dd2vttImportService = new Dd2vttImportService();
    private final MapRotationService rotationService = new MapRotationService();
    private final LightingEngine lightingEngine = new LightingEngine();
    private final CanvasMapRenderer renderer = new CanvasMapRenderer(lightingEngine);
    private final Preferences preferences = Preferences.userNodeForPackage(DungeonMasterMapToolApplication.class);

    private DmProject project;
    private Path projectFile;

    private Canvas dmCanvas;
    private Canvas dmFogCanvas;
    private Canvas playerCanvas;
    private Canvas playerFogCanvas;
    private Stage playerStage;
    private Label statusLabel;
    private ComboBox<String> playerScreenSelector;
    private DmProject.CameraState frozenPlayerCamera;
    private DmProject frozenPlayerProject;
    private Path frozenPlayerProjectFile;
    private final LightingEngine playerLightingEngine = new LightingEngine();
    private final CanvasMapRenderer playerRenderer = new CanvasMapRenderer(playerLightingEngine);
    private Stage primaryStage;
    private MapLibraryService mapLibrary;
    private MapBrowser mapBrowser;
    private ToggleButton pingToggle;
    private ToggleButton wallLayerToggle;
    private ToggleButton playerWindowToggle;
    private HBox toolChip;
    private FontIcon toolChipIcon;
    private Label toolChipLabel;
    private final DoubleProperty brushSize = new SimpleDoubleProperty(1.5);
    private Spinner<Double> screenInchesSpinner;
    private Spinner<Double> tileInchesSpinner;
    private boolean showScaleTestSquare;
    private DmProject.WallSegment draftWall;
    private boolean snapLayersToGrid;
    private volatile boolean ioBusy;
    private long lastInputNanos = System.nanoTime();
    private long lastFrameNanos;
    private DmProject.LightSource selectedLight;
    private EditorTool activeTool = EditorTool.SELECT;
    private double brushSizeTiles = 1.5;
    private boolean fogDragging;
    private double fogDragStartWorldX;
    private double fogDragStartWorldY;
    private double fogLastWorldX;
    private double fogLastWorldY;
    private double fogCurrentWorldX;
    private double fogCurrentWorldY;
    private FogMask.Snapshot fogBeforeSnapshot;
    private FogMask.Snapshot lightDragFogBefore;
    private boolean hoverInsideCanvas;
    /** Presses on the same door closer together than this are treated as mechanical switch bounce. */
    private static final long DOOR_CHATTER_NANOS = 60_000_000L;
    private String lastDoorToggleId;
    private long lastDoorToggleNanos;
    private double hoverWorldX;
    private double hoverWorldY;
    private boolean syncingControls;
    private ContextMenu activeLightMenu;
    private String overlayColor = "#55AA33";
    private double overlayAlpha = 0.4;
    private boolean overlayPlayerVisible = true;
    private String selectedOverlayId;
    private DmProject.OverlayShape draftOverlay;
    private double overlayStartX;
    private double overlayStartY;
    private boolean draggingOverlay;
    private DmProject.OverlayShape overlayDragBefore;
    private DmProject.OverlayShape overlayStyleBefore;
    private double overlayLastX;
    private double overlayLastY;
    private ColorPicker overlayColorPicker;
    private Slider overlayAlphaSlider;
    private ToggleButton overlayPlayerToggle;
    private ToggleButton fogToggleButton;
    private ToggleButton freezePlayerButton;
    private final Map<TimeOfDayPreset, ToggleButton> timeButtons = new EnumMap<>(TimeOfDayPreset.class);
    private final Map<EditorTool, ToggleButton> toolButtons = new EnumMap<>(EditorTool.class);

    private DmProject.ImageLayer selectedLayer;
    private boolean draggingLayer;
    private boolean resizingLayer;
    private boolean draggingLight;
    private boolean panningDmCamera;
    private boolean draggingPlayerViewport;
    private boolean pingArmed;
    private double dragOffsetX;
    private double dragOffsetY;
    private double lastMouseX;
    private double lastMouseY;
    private double viewportDragOffsetX;
    private double viewportDragOffsetY;
    private double startCameraX;
    private double startCameraY;
    private double startPlayerCameraX;
    private double startPlayerCameraY;
    private double startLightX;
    private double startLightY;
    private double startLayerX;
    private double startLayerY;
    private double startLayerWidth;
    private double startLayerHeight;

    private final Deque<HistoryAction> undoStack = new ArrayDeque<>();
    private final Deque<HistoryAction> redoStack = new ArrayDeque<>();
    private static final int MAX_HISTORY = 100;

    /** Pre-scaled copies of the app icon so window title bar and taskbar get a smooth image at their size. */
    private static List<Image> appIcons() {
        if (appIcons == null) {
            var url = DungeonMasterMapToolApplication.class.getResource(APP_ICON_RESOURCE);
            appIcons = url == null ? List.of() : java.util.stream.IntStream.of(16, 24, 32, 48, 64, 128, 256)
                    .mapToObj(size -> new Image(url.toExternalForm(), size, size, true, true))
                    .toList();
        }
        return appIcons;
    }

    @Override
    public void start(Stage stage) {
        this.primaryStage = stage;
        FogService.setCellsPerGrid(preferences.getInt(PREF_FOG_CELLS_PER_GRID, FogService.DEFAULT_CELLS_PER_GRID));
        CanvasMapRenderer.setLightTint(preferences.getDouble(PREF_LIGHT_TINT, CanvasMapRenderer.DEFAULT_LIGHT_TINT));
        this.project = DmProject.builder().build();
        this.project.getMap().setSourceType("custom");
        Path libraryRoot;
        try {
            libraryRoot = resolveProjectsRoot();
        } catch (IOException ex) {
            libraryRoot = Path.of(System.getProperty("user.home"), "dmmap-projects");
        }
        mapLibrary = new MapLibraryService(libraryRoot, projectService);

        BorderPane root = new BorderPane();
        root.getStyleClass().add("app-root");

        dmCanvas = new Canvas(1280, 800);
        dmFogCanvas = new Canvas(1280, 800);
        dmFogCanvas.setMouseTransparent(true);
        StackPane center = new StackPane(dmCanvas, dmFogCanvas);
        center.setMinSize(0, 0);
        Region controls = createControlsPanel(stage);
        StackPane.setAlignment(controls, Pos.TOP_RIGHT);
        StackPane.setMargin(controls, new Insets(10));
        HBox chip = createToolChip();
        StackPane.setAlignment(chip, Pos.TOP_CENTER);
        StackPane.setMargin(chip, new Insets(12, 0, 0, 0));
        center.getChildren().addAll(chip, controls);
        dmCanvas.widthProperty().bind(center.widthProperty());
        dmCanvas.heightProperty().bind(center.heightProperty());
        dmFogCanvas.widthProperty().bind(center.widthProperty());
        dmFogCanvas.heightProperty().bind(center.heightProperty());
        root.setCenter(center);

        mapBrowser = new MapBrowser(mapLibrary, createBrowserHost());
        root.setLeft(mapBrowser);

        statusLabel = new Label("Ready");
        ToggleButton sidebarToggle = Icons.toggle(MaterialDesignD.DOCK_LEFT, "Show / hide the map library");
        sidebarToggle.setSelected(preferences.getBoolean(PREF_SIDEBAR_VISIBLE, true));
        sidebarToggle.selectedProperty().addListener((obs, was, visible) -> {
            root.setLeft(visible ? mapBrowser : null);
            preferences.putBoolean(PREF_SIDEBAR_VISIBLE, visible);
        });
        if (!sidebarToggle.isSelected()) {
            root.setLeft(null);
        }
        HBox statusBar = new HBox(sidebarToggle, statusLabel);
        statusBar.getStyleClass().add("status-bar");
        root.setBottom(statusBar);

        installDmInteractions();

        Scene scene = new Scene(root, 1500, 920, Color.BLACK);
        scene.getStylesheets().add(Icons.STYLESHEET);
        // Clicks inside the popup never reach this scene, so any click here is "outside" the menu.
        scene.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> hideLightMenu());
        scene.setOnKeyPressed(event -> {
            if (event.isControlDown() && event.getCode() == KeyCode.S) {
                handleSave();
                event.consume();
                return;
            }
            if (event.isControlDown() && event.getCode() == KeyCode.Z) {
                undo();
                event.consume();
                return;
            }
            if (event.isControlDown() && event.getCode() == KeyCode.Y) {
                redo();
                event.consume();
                return;
            }
            if (scene.getFocusOwner() instanceof TextInputControl) {
                return;
            }
            if (event.getCode() == KeyCode.P) {
                setPingArmed(true);
                return;
            }
            if (event.getCode() == KeyCode.ESCAPE) {
                setPingArmed(false);
                setActiveTool(EditorTool.SELECT);
            }
            if (event.getCode() == KeyCode.DELETE || event.getCode() == KeyCode.BACK_SPACE) {
                if (findOverlay(selectedOverlayId) != null) {
                    deleteSelectedOverlay();
                } else if (selectedLayer != null) {
                    deleteSelectedLayer();
                }
                event.consume();
            }
        });
        stage.setScene(scene);
        stage.getIcons().setAll(appIcons());
        stage.setOnCloseRequest(event -> {
            closePlayerWindow();
            Platform.exit();
        });
        updateWindowTitle();
        stage.show();

        scene.addEventFilter(javafx.scene.input.InputEvent.ANY, e -> lastInputNanos = System.nanoTime());
        AnimationTimer timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                // Idle scenes without active lights only redraw ~10x per second.
                boolean animated = project.getLighting().getLights().stream().anyMatch(l -> l.isEnabled())
                        || (frozenPlayerProject != null && frozenPlayerProject.getLighting().getLights().stream().anyMatch(l -> l.isEnabled()));
                boolean recentInput = now - lastInputNanos < 1_000_000_000L;
                if (!animated && !recentInput && now - lastFrameNanos < 100_000_000L) {
                    return;
                }
                lastFrameNanos = now;
                lightingEngine.update(project);
                applyPlayerScale();
                if (frozenPlayerProject != null) {
                    playerLightingEngine.update(frozenPlayerProject);
                }
                renderDm();
                renderPlayer();
            }
        };
        timer.start();
    }

    // ---- DM controls panel (right side) ----

    private Region createControlsPanel(Stage stage) {
        brushSize.addListener((obs, oldValue, newValue) -> brushSizeTiles = Math.round(newValue.doubleValue() * 2) / 2.0);
        ToggleGroup toolGroup = new ToggleGroup();
        for (EditorTool tool : EditorTool.values()) {
            ToggleButton button = Icons.toggle(tool.icon, tool.label + " — " + tool.description);
            button.setToggleGroup(toolGroup);
            button.setOnAction(e -> setActiveTool(button.isSelected() ? tool : EditorTool.SELECT));
            toolButtons.put(tool, button);
        }

        // Tools
        pingToggle = Icons.toggle(MaterialDesignC.CROSSHAIRS_GPS, "Ping (P) — click the map to flash a marker for the players");
        pingToggle.setOnAction(e -> setPingArmed(pingToggle.isSelected()));
        wallLayerToggle = Icons.toggle(MaterialDesignL.LAYERS_OUTLINE,
                "Show / hide the wall layer (wall lines, doors and windows) in the DM view — lights stay visible");
        wallLayerToggle.setSelected(renderer.isWallLayerVisible());
        wallLayerToggle.setOnAction(e -> setWallLayerVisible(wallLayerToggle.isSelected()));
        Region toolSpacer = new Region();
        HBox.setHgrow(toolSpacer, Priority.ALWAYS);
        HBox toolsRow = row(toolButtons.get(EditorTool.SELECT), pingToggle, toolSpacer,
                Icons.button(MaterialDesignU.UNDO, "Undo (Ctrl+Z)", this::undo),
                Icons.button(MaterialDesignR.REDO, "Redo (Ctrl+Y)", this::redo));

        // Fog of war
        fogToggleButton = Icons.toggle(MaterialDesignW.WEATHER_FOG,
                "Fog of war on / off — revealed areas are remembered while fog is off");
        fogToggleButton.setOnAction(e -> {
            if (syncingControls) {
                return;
            }
            boolean enabled = fogToggleButton.isSelected();
            executeWithFogHistory(
                    enabled ? "Enable fog" : "Disable fog",
                    () -> setFogEnabled(enabled),
                    () -> setFogEnabled(!enabled)
            );
            status(enabled ? "Fog enabled." : "Fog disabled (reveals are kept).");
        });
        HBox fogToolsRow = row(fogToggleButton, Icons.separator(),
                toolButtons.get(EditorTool.REVEAL_BRUSH), toolButtons.get(EditorTool.HIDE_BRUSH),
                toolButtons.get(EditorTool.REVEAL_RECT), toolButtons.get(EditorTool.HIDE_RECT));
        HBox fogFillRow = row(
                Icons.button(MaterialDesignE.EYE_OUTLINE, "Reveal the whole map", () -> fillFog(true)),
                Icons.button(MaterialDesignE.EYE_OFF_OUTLINE, "Cover the whole map with fog", () -> fillFog(false)),
                Icons.separator(),
                brushSlider());
        HBox fogSharpnessRow = fogSharpnessSlider();

        // Lighting
        HBox timeSegment = new HBox();
        timeSegment.getStyleClass().add("segmented");
        ToggleGroup timeGroup = new ToggleGroup();
        for (TimeOfDayPreset preset : TimeOfDayPreset.values()) {
            ToggleButton button = Icons.toggle(timeOfDayIcon(preset), "Time of day: " + preset.label());
            button.setToggleGroup(timeGroup);
            button.setOnAction(e -> {
                if (syncingControls) {
                    return;
                }
                if (!button.isSelected()) {
                    button.setSelected(true);
                    return;
                }
                changeTimeOfDay(preset);
            });
            timeButtons.put(preset, button);
            timeSegment.getChildren().add(button);
        }
        HBox lightRow = row(
                toolButtons.get(EditorTool.LIGHT_ADD),
                toolButtons.get(EditorTool.LIGHT_REMOVE),
                Icons.separator(),
                timeSegment);
        Label lightHint = new Label("Right-click a light for range, color, flicker and on/off.");
        lightHint.getStyleClass().add("muted");
        lightHint.setWrapText(true);
        // Without a fixed pref width the label reports its single-line height, so the panel sizes
        // itself too short once the text wraps and shows a needless scrollbar.
        lightHint.setPrefWidth(220);
        lightHint.setMinHeight(Region.USE_PREF_SIZE);
        HBox lightTintRow = lightTintSlider();

        // Effects
        overlayColorPicker = new ColorPicker(Color.web(overlayColor));
        Icons.tooltip(overlayColorPicker, "Effect color (also changes the selected effect)");
        overlayColorPicker.setOnAction(e -> {
            if (syncingControls) {
                return;
            }
            overlayColor = toHex(overlayColorPicker.getValue());
            DmProject.OverlayShape selected = findOverlay(selectedOverlayId);
            if (selected != null) {
                executeOverlayChange("Change effect color", selected.getId(), s -> s.setColor(overlayColor));
            }
        });
        overlayAlphaSlider = new Slider(0.1, 0.9, overlayAlpha);
        Icons.tooltip(overlayAlphaSlider, "Effect opacity");
        HBox.setHgrow(overlayAlphaSlider, Priority.ALWAYS);
        overlayAlphaSlider.setPrefWidth(80);
        overlayAlphaSlider.setOnMousePressed(e -> overlayStyleBefore = cloneOverlayOrNull(findOverlay(selectedOverlayId)));
        overlayAlphaSlider.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (syncingControls) {
                return;
            }
            overlayAlpha = newValue.doubleValue();
            DmProject.OverlayShape selected = findOverlay(selectedOverlayId);
            if (selected != null) {
                selected.setAlpha(overlayAlpha);
            }
        });
        overlayAlphaSlider.setOnMouseReleased(e -> {
            DmProject.OverlayShape selected = findOverlay(selectedOverlayId);
            if (selected != null && overlayStyleBefore != null && overlayStyleBefore.getId().equals(selected.getId())
                    && !same(overlayStyleBefore.getAlpha(), selected.getAlpha())) {
                recordOverlayChange("Change effect opacity", selected.getId(), overlayStyleBefore, cloneOverlay(selected));
            }
            overlayStyleBefore = null;
        });
        overlayPlayerToggle = Icons.toggle(MaterialDesignA.ACCOUNT_GROUP_OUTLINE,
                "Players see this effect — turn off for DM-only markings");
        overlayPlayerToggle.setSelected(overlayPlayerVisible);
        overlayPlayerToggle.setOnAction(e -> {
            if (syncingControls) {
                return;
            }
            overlayPlayerVisible = overlayPlayerToggle.isSelected();
            DmProject.OverlayShape selected = findOverlay(selectedOverlayId);
            if (selected != null) {
                executeOverlayChange("Change effect visibility", selected.getId(), s -> s.setPlayerVisible(overlayPlayerVisible));
            }
        });
        Button deleteEffect = Icons.button(MaterialDesignD.DELETE_OUTLINE, "Delete the selected effect (Del)",
                this::deleteSelectedOverlay);
        Button clearEffects = Icons.button(MaterialDesignD.DELETE_SWEEP_OUTLINE, "Remove all effects", this::clearOverlays);
        clearEffects.getStyleClass().add("danger");
        Region effectSpacer = new Region();
        HBox.setHgrow(effectSpacer, Priority.ALWAYS);
        HBox effectToolsRow = row(toolButtons.get(EditorTool.AOE_CIRCLE), toolButtons.get(EditorTool.AOE_RECT),
                toolButtons.get(EditorTool.AOE_BRUSH), effectSpacer, deleteEffect, clearEffects);
        HBox effectStyleRow = row(overlayColorPicker, overlayAlphaSlider, overlayPlayerToggle);
        HBox effectBrushRow = row(brushSlider());

        // Map building
        ToggleButton snapLayers = Icons.toggle(MaterialDesignM.MAGNET,
                "Snap image layers to half-tile steps while moving / resizing");
        snapLayers.setOnAction(e -> {
            snapLayersToGrid = snapLayers.isSelected();
            status(snapLayersToGrid ? "Image layers snap to half-tile steps while moving/resizing." : "Layer snapping off.");
        });
        HBox buildRow = row(toolButtons.get(EditorTool.WALL_DRAW), toolButtons.get(EditorTool.WALL_ERASE), wallLayerToggle,
                Icons.separator(), snapLayers,
                Icons.button(MaterialDesignI.IMAGE_PLUS,
                        "Add an image layer (or drag image files onto the map). Move it with Select, resize at the corner.",
                        () -> chooseImageLayers(stage)));

        // Player view
        playerWindowToggle = Icons.toggle(MaterialDesignP.PROJECTOR,
                "Player window on / off (borderless fullscreen on the selected screen)");
        playerWindowToggle.setOnAction(e -> {
            if (playerWindowToggle.isSelected()) {
                openPlayerWindow();
            } else {
                closePlayerWindow();
            }
        });
        freezePlayerButton = Icons.toggle(MaterialDesignS.SNOWFLAKE,
                "Freeze — players keep seeing the current view while you prepare or switch maps");
        freezePlayerButton.setOnAction(e -> {
            setPlayerFrozen(freezePlayerButton.isSelected());
            status(freezePlayerButton.isSelected()
                    ? "Player view frozen. DM viewport can still be moved."
                    : "Player view unfrozen.");
        });
        ToggleButton scaleTest = Icons.toggle(MaterialDesignR.RULER_SQUARE,
                "Show a 1-inch test square on the player screen to check the scale");
        scaleTest.setOnAction(e -> {
            showScaleTestSquare = scaleTest.isSelected();
            status(showScaleTestSquare
                    ? "A 1-inch square is shown on the player screen. If it does not measure 1 inch, correct the screen size."
                    : "Test square hidden.");
        });
        HBox playerRow = row(playerWindowToggle, freezePlayerButton, scaleTest);

        playerScreenSelector = new ComboBox<>();
        playerScreenSelector.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(playerScreenSelector, Priority.ALWAYS);
        Icons.tooltip(playerScreenSelector, "Screen used for the player window");
        refreshPlayerScreenSelector();
        playerScreenSelector.setOnAction(e -> {
            rememberSelectedPlayerScreenIndex();
            if (screenInchesSpinner != null) {
                syncingControls = true;
                try {
                    screenInchesSpinner.getValueFactory().setValue(loadScreenDiagonal());
                } finally {
                    syncingControls = false;
                }
            }
        });
        HBox screenRow = row(Icons.icon(MaterialDesignM.MONITOR_SHARE), playerScreenSelector);

        screenInchesSpinner = createDoubleSpinner(10, 120, loadScreenDiagonal(), 0.5, 84);
        Icons.tooltip(screenInchesSpinner, "Diagonal of the player screen in inches (used for the 1-inch grid)");
        screenInchesSpinner.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (!syncingControls && newValue != null) {
                preferences.putDouble(PREF_SCREEN_DIAGONAL_PREFIX + selectedScreenIndex(), newValue);
            }
        });
        tileInchesSpinner = createDoubleSpinner(0.25, 3, preferences.getDouble(PREF_TILE_INCHES, 1.0), 0.05, 84);
        Icons.tooltip(tileInchesSpinner, "Size of one map tile on the player screen in inches");
        tileInchesSpinner.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue != null) {
                preferences.putDouble(PREF_TILE_INCHES, newValue);
            }
        });
        GridPane scaleGrid = new GridPane();
        scaleGrid.setHgap(8);
        scaleGrid.setVgap(4);
        scaleGrid.addRow(0, mutedLabel("Screen diagonal (in)"), screenInchesSpinner);
        scaleGrid.addRow(1, mutedLabel("Tile size (in)"), tileInchesSpinner);

        VBox sections = new VBox(
                new CollapsibleSection("Tools", MaterialDesignC.CURSOR_DEFAULT, preferences, "tools", toolsRow),
                new CollapsibleSection("Fog of war", MaterialDesignW.WEATHER_FOG, preferences, "fog", fogToolsRow, fogFillRow, fogSharpnessRow),
                new CollapsibleSection("Lighting", MaterialDesignL.LIGHTBULB_OUTLINE, preferences, "lighting", lightRow, lightTintRow, lightHint),
                new CollapsibleSection("Effects", MaterialDesignF.FORMAT_PAINT, preferences, "effects",
                        effectToolsRow, effectStyleRow, effectBrushRow),
                new CollapsibleSection("Map building", MaterialDesignW.WALL, preferences, "building", buildRow),
                new CollapsibleSection("Player view", MaterialDesignP.PROJECTOR, preferences, "player",
                        playerRow, screenRow, scaleGrid));

        ScrollPane scroll = new ScrollPane(sections);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setMinHeight(0);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Label title = new Label("DM Controls");
        title.getStyleClass().add("panel-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button collapse = Icons.button(MaterialDesignC.CHEVRON_UP, "Collapse / expand the controls", null);
        collapse.setOnAction(e -> {
            boolean show = !scroll.isVisible();
            scroll.setVisible(show);
            scroll.setManaged(show);
            ((FontIcon) collapse.getGraphic()).setIconCode(show ? MaterialDesignC.CHEVRON_UP : MaterialDesignC.CHEVRON_DOWN);
            preferences.putBoolean(PREF_CONTROLS_EXPANDED, show);
        });
        if (!preferences.getBoolean(PREF_CONTROLS_EXPANDED, true)) {
            collapse.fire();
        }
        HBox titleRow = new HBox(Icons.icon(MaterialDesignT.TUNE_VARIANT), title, spacer, collapse);
        titleRow.getStyleClass().add("panel-header");

        VBox panel = new VBox(titleRow, scroll);
        panel.getStyleClass().add("dm-panel");
        panel.setPrefWidth(268);
        panel.setMaxWidth(268);
        panel.setMaxHeight(Region.USE_PREF_SIZE);
        panel.setPickOnBounds(false);
        setActiveTool(EditorTool.SELECT);
        syncControlsFromProject();
        return panel;
    }

    private HBox row(javafx.scene.Node... nodes) {
        HBox row = new HBox(nodes);
        row.getStyleClass().add("control-row");
        return row;
    }

    private Label mutedLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("muted");
        return label;
    }

    /** A brush size slider; all brush sliders share one value. */
    private HBox brushSlider() {
        Slider slider = new Slider(0.5, 8, brushSize.get());
        slider.setMajorTickUnit(0.5);
        slider.setMinorTickCount(0);
        slider.setSnapToTicks(true);
        slider.valueProperty().bindBidirectional(brushSize);
        HBox.setHgrow(slider, Priority.ALWAYS);
        slider.setPrefWidth(90);
        Icons.tooltip(slider, "Brush size in tiles");
        Label value = new Label();
        value.getStyleClass().add("value-label");
        value.textProperty().bind(brushSize.asString("%.1f t"));
        FontIcon icon = Icons.icon(MaterialDesignB.BRUSH);
        icon.getStyleClass().add("muted-icon");
        HBox box = row(icon, slider, value);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    /**
     * Global fog edge sharpness (fog cells per grid cell). Stored in user preferences so it applies
     * to every project; the fog mask is resampled when the slider is released.
     */
    private HBox fogSharpnessSlider() {
        int initial = FogService.getCellsPerGrid();
        Slider slider = new Slider(FogService.MIN_CELLS_PER_GRID, FogService.MAX_CELLS_PER_GRID, initial);
        slider.setMajorTickUnit(1);
        slider.setMinorTickCount(0);
        slider.setSnapToTicks(true);
        slider.setBlockIncrement(1);
        HBox.setHgrow(slider, Priority.ALWAYS);
        slider.setPrefWidth(90);
        Icons.tooltip(slider, "Fog shadow edge sharpness (fog cells per tile, applies to all maps). "
                + "Higher is smoother but uses more memory and CPU.");
        Label value = new Label(initial + "/t");
        value.getStyleClass().add("value-label");
        Runnable apply = () -> {
            int cells = (int) Math.round(slider.getValue());
            if (cells != FogService.getCellsPerGrid()) {
                FogService.setCellsPerGrid(cells);
                preferences.putInt(PREF_FOG_CELLS_PER_GRID, FogService.getCellsPerGrid());
                status("Fog sharpness: " + FogService.getCellsPerGrid() + " cells per tile.");
            }
        };
        slider.valueProperty().addListener((obs, oldValue, newValue) -> {
            value.setText(Math.round(newValue.doubleValue()) + "/t");
            if (!slider.isValueChanging()) {
                apply.run();
            }
        });
        slider.valueChangingProperty().addListener((obs, wasChanging, changing) -> {
            if (!changing) {
                apply.run();
            }
        });
        FontIcon icon = Icons.icon(MaterialDesignB.BLUR);
        icon.getStyleClass().add("muted-icon");
        HBox box = row(icon, slider, value);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    /**
     * Global strength of the light colour tint over lit areas. Stored in user preferences so it
     * applies to every light in every project; the renderer picks it up on the next frame.
     */
    private HBox lightTintSlider() {
        double initial = CanvasMapRenderer.getLightTint();
        Slider slider = new Slider(CanvasMapRenderer.MIN_LIGHT_TINT, CanvasMapRenderer.MAX_LIGHT_TINT, initial);
        slider.setMajorTickUnit(0.01);
        slider.setMinorTickCount(0);
        slider.setSnapToTicks(true);
        slider.setBlockIncrement(0.01);
        HBox.setHgrow(slider, Priority.ALWAYS);
        slider.setPrefWidth(90);
        Icons.tooltip(slider, "Light colour tint strength (applies to all lights and maps). "
                + "Lower keeps the map's own colours, higher tints lit areas with the light colour.");
        Label value = new Label(Math.round(initial * 100) + "%");
        value.getStyleClass().add("value-label");
        slider.valueProperty().addListener((obs, oldValue, newValue) -> {
            CanvasMapRenderer.setLightTint(newValue.doubleValue());
            value.setText(Math.round(CanvasMapRenderer.getLightTint() * 100) + "%");
            if (!slider.isValueChanging()) {
                preferences.putDouble(PREF_LIGHT_TINT, CanvasMapRenderer.getLightTint());
            }
        });
        slider.valueChangingProperty().addListener((obs, wasChanging, changing) -> {
            if (!changing) {
                preferences.putDouble(PREF_LIGHT_TINT, CanvasMapRenderer.getLightTint());
                status("Light tint: " + Math.round(CanvasMapRenderer.getLightTint() * 100) + "%.");
            }
        });
        FontIcon icon = Icons.icon(MaterialDesignP.PALETTE_OUTLINE);
        icon.getStyleClass().add("muted-icon");
        HBox box = row(icon, slider, value);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    private static Ikon timeOfDayIcon(TimeOfDayPreset preset) {
        return switch (preset) {
            case DAY -> MaterialDesignW.WEATHER_SUNNY;
            case DAWN -> MaterialDesignW.WEATHER_SUNSET_UP;
            case DUSK -> MaterialDesignW.WEATHER_SUNSET_DOWN;
            case NIGHT -> MaterialDesignW.WEATHER_NIGHT;
        };
    }

    private void changeTimeOfDay(TimeOfDayPreset preset) {
        String before = project.getLighting().getTimeOfDayPreset();
        String after = preset.name();
        if (after.equalsIgnoreCase(before)) {
            return;
        }
        executeWithHistory(
                "Change time of day",
                () -> setTimeOfDay(after),
                () -> setTimeOfDay(before)
        );
        status("Time of day: " + preset.label());
    }

    private void chooseImageLayers(Stage stage) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Add image layer");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.webp"));
        applyInitialImportDirectory(chooser);
        List<File> files = chooser.showOpenMultipleDialog(stage);
        if (files == null || files.isEmpty()) {
            return;
        }
        rememberImportDirectory(files.get(0).toPath().getParent());
        for (File file : files) {
            addImageLayerFromFile(file.toPath());
        }
    }

    // ---- Active tool feedback (chip + cursor) ----

    private HBox createToolChip() {
        toolChipIcon = new FontIcon(MaterialDesignC.CURSOR_DEFAULT);
        toolChipLabel = new Label();
        toolChipLabel.getStyleClass().add("tool-chip-label");
        Label hint = new Label("Esc to exit");
        hint.getStyleClass().add("tool-chip-hint");
        toolChip = new HBox(toolChipIcon, toolChipLabel, hint);
        toolChip.getStyleClass().add("tool-chip");
        toolChip.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        toolChip.setMouseTransparent(true);
        toolChip.setVisible(false);
        return toolChip;
    }

    private void updateToolChip() {
        if (toolChip == null) {
            return;
        }
        if (pingArmed) {
            toolChipIcon.setIconCode(MaterialDesignC.CROSSHAIRS_GPS);
            toolChipLabel.setText("Ping — click the map");
            toolChip.setVisible(true);
        } else if (activeTool != EditorTool.SELECT) {
            toolChipIcon.setIconCode(activeTool.icon);
            toolChipLabel.setText(activeTool.label);
            toolChip.setVisible(true);
        } else {
            toolChip.setVisible(false);
        }
    }

    private void setPingArmed(boolean armed) {
        pingArmed = armed;
        if (pingToggle != null && pingToggle.isSelected() != armed) {
            pingToggle.setSelected(armed);
        }
        if (armed) {
            status("Ping mode: click the map to ping players.");
        }
        updateToolChip();
        updateCanvasCursor();
    }

    private Cursor toolCursor() {
        if (pingArmed) {
            return Icons.cursor(MaterialDesignC.CROSSHAIRS_GPS, 0.5, 0.5);
        }
        return switch (activeTool) {
            case SELECT -> Cursor.DEFAULT;
            case REVEAL_BRUSH -> Icons.cursor(MaterialDesignE.ERASER, 0.2, 0.82);
            case HIDE_BRUSH -> Icons.cursor(MaterialDesignB.BRUSH, 0.15, 0.85);
            case AOE_BRUSH -> Icons.tipCursor(MaterialDesignD.DRAW, 0.0, 1.0);
            case WALL_DRAW -> Icons.tipCursor(MaterialDesignP.PENCIL, 0.0, 1.0);
            case WALL_ERASE -> Icons.cursor(MaterialDesignE.ERASER_VARIANT, 0.2, 0.82);
            case LIGHT_ADD -> Icons.cursor(MaterialDesignL.LIGHTBULB_ON_OUTLINE, 0.5, 0.5);
            case LIGHT_REMOVE -> hoverInsideCanvas && pickNearestLight(hoverWorldX, hoverWorldY,
                    24 / Math.max(0.01, project.getViews().getDmCamera().getZoom())) != null
                    ? Icons.cursor(MaterialDesignL.LIGHTBULB_OFF_OUTLINE, 0.5, 0.5)
                    : Cursor.DEFAULT;
            default -> Cursor.CROSSHAIR;
        };
    }

    /** Cursor for the current tool, or for what is under the mouse in Select mode. */
    private void updateCanvasCursor() {
        if (dmCanvas == null) {
            return;
        }
        Cursor cursor;
        if (panningDmCamera || draggingLight || draggingLayer || draggingOverlay || draggingPlayerViewport) {
            cursor = Cursor.CLOSED_HAND;
        } else if (resizingLayer) {
            cursor = Cursor.SE_RESIZE;
        } else if (pingArmed || activeTool != EditorTool.SELECT) {
            cursor = toolCursor();
        } else {
            cursor = hoverInsideCanvas ? selectHoverCursor(hoverWorldX, hoverWorldY) : Cursor.DEFAULT;
        }
        if (dmCanvas.getCursor() != cursor) {
            dmCanvas.setCursor(cursor);
        }
    }

    private Cursor selectHoverCursor(double worldX, double worldY) {
        double zoom = Math.max(0.01, project.getViews().getDmCamera().getZoom());
        if (isOnPlayerViewportTitleBar(worldX, worldY)) {
            return Cursor.MOVE;
        }
        if (renderer.isWallLayerVisible() && pickInteractableBadge(worldX, worldY) != null) {
            return Cursor.HAND;
        }
        if (pickNearestLight(worldX, worldY, 24 / zoom) != null) {
            return Cursor.HAND;
        }
        if (renderer.isWallLayerVisible() && pickInteractableLine(worldX, worldY) != null) {
            return Cursor.HAND;
        }
        if (pickOverlay(worldX, worldY, zoom) != null) {
            return Cursor.OPEN_HAND;
        }
        DmProject.ImageLayer layer = pickTopmostLayer(worldX, worldY);
        if (layer != null
                && distance(worldX, worldY, layer.getX() + layer.getWidth(), layer.getY() + layer.getHeight()) < 16 / zoom) {
            return Cursor.SE_RESIZE;
        }
        return layer != null ? Cursor.OPEN_HAND : Cursor.DEFAULT;
    }

    // ---- Map library integration ----

    private MapBrowser.Host createBrowserHost() {
        return new MapBrowser.Host() {
            @Override
            public void newMap() {
                handleNewMap();
            }

            @Override
            public void newMapIn(Path folder) {
                handleNewMapIn(folder);
            }

            @Override
            public void importMap(Path suggestedFolder) {
                handleImportDd2vtt(suggestedFolder);
            }

            @Override
            public void saveMap() {
                handleSave();
            }

            @Override
            public void rotateMap(boolean clockwise) {
                rotateMapInPlace(clockwise);
            }

            @Override
            public void openMap(Path mapFile) {
                openMapFromLibrary(mapFile);
            }

            @Override
            public Path currentMapFile() {
                return projectFile;
            }

            @Override
            public void runLibraryOperation(String busyMessage, Path affectedPath, MapBrowser.LibraryOperation operation,
                                            Consumer<MapLibraryService.Result> onDone) {
                DungeonMasterMapToolApplication.this.runLibraryOperation(busyMessage, affectedPath, operation, onDone);
            }
        };
    }

    private void rotateMapInPlace(boolean clockwise) {
        if (clockwise) {
            executeWithHistory("Rotate map right",
                    () -> rotationService.rotateClockwise(project),
                    () -> rotationService.rotateCounterClockwise(project));
            status("Rotated map 90° clockwise.");
        } else {
            executeWithHistory("Rotate map left",
                    () -> rotationService.rotateCounterClockwise(project),
                    () -> rotationService.rotateClockwise(project));
            status("Rotated map 90° counter-clockwise.");
        }
    }

    private void installDmInteractions() {
        dmCanvas.setOnDragOver(event -> {
            Dragboard board = event.getDragboard();
            if (board.hasFiles() && board.getFiles().stream().anyMatch(this::isImageFile)) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });

        dmCanvas.setOnDragDropped(event -> {
            Dragboard board = event.getDragboard();
            boolean success = false;
            if (board.hasFiles()) {
                for (File file : board.getFiles()) {
                    if (isImageFile(file)) {
                        addImageLayerFromFile(file.toPath());
                        success = true;
                    }
                }
            }
            event.setDropCompleted(success);
            event.consume();
        });

        dmCanvas.setOnScroll(event -> {
            DmProject.CameraState camera = project.getViews().getDmCamera();
            double factor = event.getDeltaY() > 0 ? 1.1 : 0.9;
            double newZoom = clamp(camera.getZoom() * factor, 0.1, 6.0);
            CanvasMapRenderer.WorldPoint before = renderer.screenToWorld(
                    event.getX(), event.getY(), dmCanvas.getWidth(), dmCanvas.getHeight(), camera);
            camera.setZoom(newZoom);
            CanvasMapRenderer.WorldPoint after = renderer.screenToWorld(
                    event.getX(), event.getY(), dmCanvas.getWidth(), dmCanvas.getHeight(), camera);
            camera.setX(camera.getX() + (before.x() - after.x()));
            camera.setY(camera.getY() + (before.y() - after.y()));
        });

        dmCanvas.setOnMouseMoved(event -> {
            updateHover(event.getX(), event.getY());
            updateCanvasCursor();
        });
        dmCanvas.setOnMouseExited(event -> hoverInsideCanvas = false);

        // Runs after the press/release handlers below have updated the drag state.
        dmCanvas.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> {
            // Take keyboard focus away from the map tree so Delete/F2 act on the canvas selection.
            dmCanvas.requestFocus();
            Platform.runLater(this::updateCanvasCursor);
        });
        dmCanvas.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_RELEASED, event -> Platform.runLater(this::updateCanvasCursor));

        dmCanvas.setOnMousePressed(event -> {
            lastMouseX = event.getX();
            lastMouseY = event.getY();
            DmProject.CameraState camera = project.getViews().getDmCamera();
            CanvasMapRenderer.WorldPoint world = renderer.screenToWorld(
                    event.getX(), event.getY(), dmCanvas.getWidth(), dmCanvas.getHeight(), camera);

            if (event.getButton() == MouseButton.SECONDARY) {
                DmProject.LightSource light = pickNearestLight(world.x(), world.y(), 24 / Math.max(0.01, camera.getZoom()));
                if (light != null) {
                    selectedLight = light;
                    selectedLayer = null;
                    showLightContextMenu(light, event.getScreenX(), event.getScreenY());
                    return;
                }
                panningDmCamera = true;
                startCameraX = camera.getX();
                startCameraY = camera.getY();
                return;
            }

            if (event.getButton() != MouseButton.PRIMARY) {
                return;
            }

            if (pingArmed) {
                addPing(world.x(), world.y());
                setPingArmed(false);
                status("Ping placed.");
                return;
            }

            if (activeTool == EditorTool.LIGHT_ADD) {
                addLightAt(world.x(), world.y());
                setActiveTool(EditorTool.SELECT);
                status("Added torch light. Drag it to move; right-click for range, flicker, color and fog reveal.");
                return;
            }

            if (activeTool == EditorTool.LIGHT_REMOVE) {
                DmProject.LightSource hit = pickNearestLight(world.x(), world.y(), 24 / Math.max(0.01, camera.getZoom()));
                if (hit == null) {
                    status("No light there — click directly on the light you want to remove.");
                    return;
                }
                removeLight(hit.getId());
                setActiveTool(EditorTool.SELECT);
                status("Removed light.");
                return;
            }

            if (activeTool.isFogTool()) {
                if (!project.getFog().isEnabled()) {
                    status("Fog is off. Turn fog on to edit it.");
                    return;
                }
                FogMask mask = project.getFog().getMask();
                if (mask == null) {
                    return;
                }
                fogDragging = true;
                fogDragStartWorldX = world.x();
                fogDragStartWorldY = world.y();
                fogLastWorldX = world.x();
                fogLastWorldY = world.y();
                fogCurrentWorldX = world.x();
                fogCurrentWorldY = world.y();
                fogBeforeSnapshot = mask.snapshot();
                if (!activeTool.rect) {
                    mask.applyCircle(world.x(), world.y(), brushRadiusWorld(), activeTool.reveal);
                }
                return;
            }

            if (activeTool.isAoeTool()) {
                beginOverlayDraw(world.x(), world.y());
                return;
            }

            if (activeTool == EditorTool.WALL_DRAW) {
                double[] p = snapWallPoint(world.x(), world.y(), event.isShiftDown());
                draftWall = DmProject.WallSegment.builder().x1(p[0]).y1(p[1]).x2(p[0]).y2(p[1]).build();
                return;
            }

            if (activeTool == EditorTool.WALL_ERASE) {
                eraseWallAt(world.x(), world.y(), camera.getZoom());
                return;
            }

            // The viewport grab bar sits on top of everything else, like a window title bar.
            if (isOnPlayerViewportTitleBar(world.x(), world.y())) {
                CanvasMapRenderer.WorldRect playerRect = getPlayerViewportRect();
                draggingPlayerViewport = true;
                viewportDragOffsetX = world.x() - playerRect.x();
                viewportDragOffsetY = world.y() - playerRect.y();
                startPlayerCameraX = project.getViews().getPlayerCamera().getX();
                startPlayerCameraY = project.getViews().getPlayerCamera().getY();
                return;
            }

            DmProject.Interactable door = pickInteractableForClick(world.x(), world.y());
            if (door != null) {
                // Debounce only mouse switch chatter (a few ms); real human double-clicks toggle twice.
                long nowNanos = System.nanoTime();
                boolean chatter = door.getId() != null && door.getId().equals(lastDoorToggleId)
                        && nowNanos - lastDoorToggleNanos < DOOR_CHATTER_NANOS;
                if (!chatter) {
                    toggleInteractable(door);
                    lastDoorToggleId = door.getId();
                    lastDoorToggleNanos = nowNanos;
                }
                return;
            }

            selectedLight = pickNearestLight(world.x(), world.y(), 24 / Math.max(0.01, camera.getZoom()));
            if (selectedLight != null) {
                draggingLight = true;
                dragOffsetX = world.x() - selectedLight.getX();
                dragOffsetY = world.y() - selectedLight.getY();
                startLightX = selectedLight.getX();
                startLightY = selectedLight.getY();
                lightDragFogBefore = snapshotFog();
                selectedLayer = null;
                selectedOverlayId = null;
                return;
            }

            DmProject.OverlayShape hitOverlay = pickOverlay(world.x(), world.y(), camera.getZoom());
            if (hitOverlay != null) {
                selectedOverlayId = hitOverlay.getId();
                selectedLayer = null;
                selectedLight = null;
                draggingOverlay = true;
                overlayDragBefore = cloneOverlay(hitOverlay);
                overlayLastX = world.x();
                overlayLastY = world.y();
                syncOverlayControls(hitOverlay);
                return;
            }
            selectedOverlayId = null;

            selectedLayer = pickTopmostLayer(world.x(), world.y());
            if (selectedLayer == null) {
                return;
            }
            selectedLight = null;
            startLayerX = selectedLayer.getX();
            startLayerY = selectedLayer.getY();
            startLayerWidth = selectedLayer.getWidth();
            startLayerHeight = selectedLayer.getHeight();

            double handleX = selectedLayer.getX() + selectedLayer.getWidth();
            double handleY = selectedLayer.getY() + selectedLayer.getHeight();
            double handleDistance = distance(world.x(), world.y(), handleX, handleY);
            if (handleDistance < 16 / Math.max(0.01, camera.getZoom())) {
                resizingLayer = true;
            } else {
                draggingLayer = true;
                dragOffsetX = world.x() - selectedLayer.getX();
                dragOffsetY = world.y() - selectedLayer.getY();
            }
        });

        dmCanvas.setOnMouseDragged(event -> {
            updateHover(event.getX(), event.getY());
            DmProject.CameraState camera = project.getViews().getDmCamera();
            CanvasMapRenderer.WorldPoint world = renderer.screenToWorld(
                    event.getX(), event.getY(), dmCanvas.getWidth(), dmCanvas.getHeight(), camera);

            if (panningDmCamera) {
                double dxScreen = event.getX() - lastMouseX;
                double dyScreen = event.getY() - lastMouseY;
                camera.setX(camera.getX() - dxScreen / camera.getZoom());
                camera.setY(camera.getY() - dyScreen / camera.getZoom());
                lastMouseX = event.getX();
                lastMouseY = event.getY();
                return;
            }

            if (fogDragging) {
                FogMask mask = project.getFog().getMask();
                fogCurrentWorldX = world.x();
                fogCurrentWorldY = world.y();
                if (mask != null && !activeTool.rect) {
                    paintBrushSegment(mask, fogLastWorldX, fogLastWorldY, world.x(), world.y());
                }
                fogLastWorldX = world.x();
                fogLastWorldY = world.y();
                return;
            }

            if (draftOverlay != null) {
                updateOverlayDraw(world.x(), world.y());
                return;
            }

            if (draftWall != null) {
                double[] p = snapWallPoint(world.x(), world.y(), event.isShiftDown());
                draftWall.setX2(p[0]);
                draftWall.setY2(p[1]);
                return;
            }

            if (draggingOverlay) {
                DmProject.OverlayShape moving = findOverlay(selectedOverlayId);
                if (moving != null) {
                    translateOverlay(moving, world.x() - overlayLastX, world.y() - overlayLastY);
                }
                overlayLastX = world.x();
                overlayLastY = world.y();
                return;
            }

            if (draggingPlayerViewport && playerStage != null) {
                DmProject.CameraState playerCam = project.getViews().getPlayerCamera();
                CanvasMapRenderer.WorldRect rect = getPlayerViewportRect();
                if (rect != null) {
                    playerCam.setX(world.x() - viewportDragOffsetX + rect.width() / 2.0);
                    playerCam.setY(world.y() - viewportDragOffsetY + rect.height() / 2.0);
                }
                return;
            }

            if (selectedLight != null && draggingLight) {
                selectedLight.setX(world.x() - dragOffsetX);
                selectedLight.setY(world.y() - dragOffsetY);
                return;
            }

            if (selectedLayer == null) {
                return;
            }
            if (draggingLayer) {
                selectedLayer.setX(snapLayer(world.x() - dragOffsetX));
                selectedLayer.setY(snapLayer(world.y() - dragOffsetY));
            } else if (resizingLayer) {
                selectedLayer.setWidth(Math.max(16, snapLayer(world.x()) - selectedLayer.getX()));
                selectedLayer.setHeight(Math.max(16, snapLayer(world.y()) - selectedLayer.getY()));
            }
        });

        dmCanvas.setOnMouseReleased(event -> {
            if (panningDmCamera) {
                DmProject.CameraState camera = project.getViews().getDmCamera();
                if (!same(startCameraX, camera.getX()) || !same(startCameraY, camera.getY())) {
                    double beforeX = startCameraX;
                    double beforeY = startCameraY;
                    double afterX = camera.getX();
                    double afterY = camera.getY();
                    executeWithHistory(
                            "Pan DM camera",
                            () -> {
                                camera.setX(afterX);
                                camera.setY(afterY);
                            },
                            () -> {
                                camera.setX(beforeX);
                                camera.setY(beforeY);
                            }
                    );
                }
            }

            if (fogDragging) {
                fogDragging = false;
                FogMask mask = project.getFog().getMask();
                if (mask != null && fogBeforeSnapshot != null) {
                    if (activeTool.rect) {
                        mask.applyRect(
                                Math.min(fogDragStartWorldX, fogCurrentWorldX),
                                Math.min(fogDragStartWorldY, fogCurrentWorldY),
                                Math.abs(fogCurrentWorldX - fogDragStartWorldX),
                                Math.abs(fogCurrentWorldY - fogDragStartWorldY),
                                activeTool.reveal);
                    }
                    FogMask.Snapshot before = fogBeforeSnapshot;
                    FogMask.Snapshot after = mask.snapshot();
                    if (!before.sameBits(after)) {
                        String label = activeTool.reveal ? "Reveal fog" : "Hide fog";
                        recordHistory(label, () -> restoreFog(after), () -> restoreFog(before));
                    }
                }
                fogBeforeSnapshot = null;
                return;
            }

            if (draftOverlay != null) {
                finishOverlayDraw();
                return;
            }

            if (draftWall != null) {
                finishWallDraw();
                return;
            }

            if (draggingOverlay) {
                DmProject.OverlayShape moved = findOverlay(selectedOverlayId);
                if (moved != null && overlayDragBefore != null
                        && (!same(moved.getX(), overlayDragBefore.getX()) || !same(moved.getY(), overlayDragBefore.getY())
                        || !moved.getPoints().equals(overlayDragBefore.getPoints()))) {
                    recordOverlayChange("Move effect", moved.getId(), overlayDragBefore, cloneOverlay(moved));
                }
                draggingOverlay = false;
                overlayDragBefore = null;
                return;
            }

            if (draggingPlayerViewport && playerStage != null) {
                DmProject.CameraState playerCam = project.getViews().getPlayerCamera();
                if (!same(startPlayerCameraX, playerCam.getX()) || !same(startPlayerCameraY, playerCam.getY())) {
                    double beforeX = startPlayerCameraX;
                    double beforeY = startPlayerCameraY;
                    double afterX = playerCam.getX();
                    double afterY = playerCam.getY();
                    executeWithHistory(
                            "Move player viewport",
                            () -> {
                                playerCam.setX(afterX);
                                playerCam.setY(afterY);
                            },
                            () -> {
                                playerCam.setX(beforeX);
                                playerCam.setY(beforeY);
                            }
                    );
                }
            }

            if (draggingLight && selectedLight != null) {
                if (!same(startLightX, selectedLight.getX()) || !same(startLightY, selectedLight.getY())) {
                    String lightId = selectedLight.getId();
                    double beforeX = startLightX;
                    double beforeY = startLightY;
                    double afterX = selectedLight.getX();
                    double afterY = selectedLight.getY();
                    recordWithFog(
                            "Move light",
                            lightDragFogBefore,
                            () -> {
                                DmProject.LightSource light = findLightById(lightId);
                                if (light != null) {
                                    light.setX(afterX);
                                    light.setY(afterY);
                                }
                            },
                            () -> {
                                DmProject.LightSource light = findLightById(lightId);
                                if (light != null) {
                                    light.setX(beforeX);
                                    light.setY(beforeY);
                                }
                            }
                    );
                }
                lightDragFogBefore = null;
            }

            if (selectedLayer != null && (draggingLayer || resizingLayer)) {
                if (!same(startLayerX, selectedLayer.getX())
                        || !same(startLayerY, selectedLayer.getY())
                        || !same(startLayerWidth, selectedLayer.getWidth())
                        || !same(startLayerHeight, selectedLayer.getHeight())) {
                    String layerId = selectedLayer.getId();
                    double beforeX = startLayerX;
                    double beforeY = startLayerY;
                    double beforeW = startLayerWidth;
                    double beforeH = startLayerHeight;
                    double afterX = selectedLayer.getX();
                    double afterY = selectedLayer.getY();
                    double afterW = selectedLayer.getWidth();
                    double afterH = selectedLayer.getHeight();
                    executeWithHistory(
                            "Edit image layer",
                            () -> applyLayerBounds(layerId, afterX, afterY, afterW, afterH),
                            () -> applyLayerBounds(layerId, beforeX, beforeY, beforeW, beforeH)
                    );
                }
            }

            draggingLayer = false;
            resizingLayer = false;
            draggingLight = false;
            panningDmCamera = false;
            draggingPlayerViewport = false;
            fogDragging = false;
            fogBeforeSnapshot = null;
        });
    }

    private void openPlayerWindow() {
        refreshPlayerScreenSelector();
        if (playerStage != null) {
            playerStage.toFront();
            return;
        }
        playerCanvas = new Canvas(1280, 720);
        playerFogCanvas = new Canvas(1280, 720);
        playerFogCanvas.setMouseTransparent(true);
        StackPane root = new StackPane(playerCanvas, playerFogCanvas);
        playerCanvas.widthProperty().bind(root.widthProperty());
        playerCanvas.heightProperty().bind(root.heightProperty());
        playerFogCanvas.widthProperty().bind(root.widthProperty());
        playerFogCanvas.heightProperty().bind(root.heightProperty());
        Scene scene = new Scene(root, 1280, 720, Color.BLACK);

        playerStage = new Stage(StageStyle.UNDECORATED);
        playerStage.setTitle("Player View");
        playerStage.getIcons().setAll(appIcons());
        playerStage.setScene(scene);
        playerStage.setOnCloseRequest(event -> {
            playerStage = null;
            playerCanvas = null;
            playerFogCanvas = null;
            syncPlayerWindowToggle();
        });

        Screen target = resolveSelectedPlayerScreen();
        Rectangle2D bounds = target.getBounds();
        playerStage.setX(bounds.getMinX());
        playerStage.setY(bounds.getMinY());
        playerStage.setWidth(bounds.getWidth());
        playerStage.setHeight(bounds.getHeight());
        playerStage.show();
        syncPlayerWindowToggle();
        status("Player window opened.");
    }

    private void syncPlayerWindowToggle() {
        if (playerWindowToggle != null) {
            playerWindowToggle.setSelected(playerStage != null);
        }
    }

    private void closePlayerWindow() {
        if (playerStage != null) {
            playerStage.close();
            playerStage = null;
            playerCanvas = null;
            playerFogCanvas = null;
        }
        syncPlayerWindowToggle();
    }

    private void renderDm() {
        GraphicsContext gc = dmCanvas.getGraphicsContext2D();
        renderer.render(
                gc,
                project,
                projectFile,
                dmCanvas.getWidth(),
                dmCanvas.getHeight(),
                project.getViews().getDmCamera(),
                false,
                getPlayerViewportRect()
        );
        GraphicsContext fogGc = dmFogCanvas.getGraphicsContext2D();
        renderer.renderFogLayer(
                fogGc,
                project,
                dmFogCanvas.getWidth(),
                dmFogCanvas.getHeight(),
                project.getViews().getDmCamera(),
                false,
                getPlayerViewportRect(),
                selectedLight == null ? null : selectedLight.getId(),
                hoveredInteractableId()
        );
        drawSelectionHandle(fogGc);
        drawOverlaySelection(fogGc);
        drawToolPreview(fogGc);
    }

    private void renderPlayer() {
        if (playerCanvas == null || playerStage == null) {
            return;
        }
        GraphicsContext gc = playerCanvas.getGraphicsContext2D();
        boolean frozen = frozenPlayerProject != null;
        DmProject shown = frozen ? frozenPlayerProject : project;
        CanvasMapRenderer playerView = frozen ? playerRenderer : renderer;
        playerView.render(
                gc,
                shown,
                frozen ? frozenPlayerProjectFile : projectFile,
                playerCanvas.getWidth(),
                playerCanvas.getHeight(),
                getEffectivePlayerCamera(),
                true,
                null
        );
        playerView.renderFogLayer(
                playerFogCanvas.getGraphicsContext2D(),
                shown,
                playerFogCanvas.getWidth(),
                playerFogCanvas.getHeight(),
                getEffectivePlayerCamera(),
                true,
                null,
                null,
                null
        );
        if (showScaleTestSquare) {
            drawScaleTestSquare(playerFogCanvas.getGraphicsContext2D());
        }
    }

    private void drawSelectionHandle(GraphicsContext gc) {
        if (selectedLayer == null) {
            return;
        }
        DmProject.CameraState camera = project.getViews().getDmCamera();
        double x = renderer.worldToScreenX(selectedLayer.getX(), dmCanvas.getWidth(), camera);
        double y = renderer.worldToScreenY(selectedLayer.getY(), dmCanvas.getHeight(), camera);
        double w = selectedLayer.getWidth() * camera.getZoom();
        double h = selectedLayer.getHeight() * camera.getZoom();
        gc.setStroke(Color.YELLOW);
        gc.setLineWidth(2);
        gc.strokeRect(x, y, w, h);
        gc.setFill(Color.YELLOW);
        gc.fillRect(x + w - 6, y + h - 6, 12, 12);
    }

    // ---- New / import / open / save ----

    private DmProject freshProject() {
        DmProject fresh = DmProject.builder().build();
        fresh.getMap().setSourceType("custom");
        return fresh;
    }

    private boolean hasContent(DmProject candidate) {
        return !candidate.getImageLayers().isEmpty()
                || !candidate.getWalls().isEmpty()
                || !candidate.getLighting().getLights().isEmpty()
                || !candidate.getOverlays().isEmpty()
                || (candidate.getMap().getImagePath() != null && !candidate.getMap().getImagePath().isBlank());
    }

    /**
     * Runs {@code next} once the current map may be left: saved maps are saved automatically, unsaved new maps
     * with content ask whether to save them into the library first.
     */
    private void leaveCurrentMap(Runnable next) {
        if (projectFile != null) {
            saveCurrentThen(next);
            return;
        }
        if (!hasContent(project)) {
            next.run();
            return;
        }
        switch (Dialogs.askSaveChanges(primaryStage, "Save the new map first?",
                "This map has not been saved yet. Save it to your map library before leaving it?")) {
            case SAVE -> saveNewMap(next);
            case DISCARD -> next.run();
            case CANCEL -> {
            }
        }
    }

    private void handleNewMap() {
        leaveCurrentMap(() -> {
            switchProject(freshProject(), null);
            status("New empty map. Drop images onto it to build it, then save with Ctrl+S.");
        });
    }

    private void handleNewMapIn(Path folder) {
        String folderName = folder.equals(mapLibrary.getRoot()) ? "Library" : folder.getFileName().toString();
        Optional<String> name = Dialogs.askText(primaryStage, "New map", "Create an empty map in \"" + folderName + "\"",
                MaterialDesignM.MAP_PLUS, "Create", "New map", text -> {
                    try {
                        mapLibrary.newMapFile(folder, text);
                        return null;
                    } catch (IOException ex) {
                        return ex.getMessage();
                    }
                });
        if (name.isEmpty()) {
            return;
        }
        leaveCurrentMap(() -> runInBackground("Creating map...", "Could not create the map: ", () -> {
            Path file = mapLibrary.newMapFile(folder, name.get());
            DmProject fresh = freshProject();
            new FogService().ensureMask(fresh);
            projectService.save(file, fresh);
            return new LoadedProject(fresh, file);
        }, loaded -> {
            switchProject(loaded.project(), loaded.file());
            mapBrowser.refresh();
            mapBrowser.select(loaded.file());
            status("Created " + MapBrowser.displayName(loaded.file()) + ". Drop images onto it to build the map.");
        }));
    }

    private void handleImportDd2vtt(Path suggestedFolder) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select DD2VTT map");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Universal VTT", "*.dd2vtt", "*.uvtt"));
        applyInitialImportDirectory(chooser);
        File source = chooser.showOpenDialog(primaryStage);
        if (source == null) {
            return;
        }
        rememberImportDirectory(source.toPath().getParent());

        Optional<MapLocationDialog.Selection> selection = MapLocationDialog.show(primaryStage, mapLibrary,
                "Import map", MaterialDesignF.FILE_IMPORT_OUTLINE, "Import",
                MapLibraryService.stripExtension(source.getName()), suggestedFolder);
        if (selection.isEmpty()) {
            return;
        }
        Path sourcePath = source.toPath();
        String sourceName = source.getName();
        MapLocationDialog.Selection target = selection.get();
        leaveCurrentMap(() -> runInBackground("Importing " + sourceName + "...", "Import failed: ", () -> {
            Path targetPath = mapLibrary.newMapFile(target.folder(), target.name());
            Path projectDir = targetPath.getParent();
            Files.createDirectories(projectDir);
            try {
                DmProject imported = dd2vttImportService.importToProject(sourcePath, projectDir);
                projectService.save(targetPath, imported);
                return new LoadedProject(imported, targetPath);
            } catch (IOException | RuntimeException ex) {
                try {
                    MapLibraryService.deleteRecursive(projectDir);
                } catch (IOException ignored) {
                }
                throw ex;
            }
        }, loaded -> {
            switchProject(loaded.project(), loaded.file());
            mapBrowser.refresh();
            mapBrowser.select(loaded.file());
            status("Imported " + sourceName + " as " + MapBrowser.displayName(loaded.file()) + ".");
        }));
    }

    private void openMapFromLibrary(Path file) {
        if (projectFile != null && file.toAbsolutePath().normalize().equals(projectFile.toAbsolutePath().normalize())) {
            status("That map is already open.");
            return;
        }
        if (projectFile == null && hasContent(project)) {
            switch (Dialogs.askSaveChanges(primaryStage, "Save the new map first?",
                    "This map has not been saved yet. Save it to your map library before opening another one?")) {
                case SAVE -> saveNewMap(() -> switchToMap(file));
                case DISCARD -> switchToMap(file);
                case CANCEL -> {
                }
            }
            return;
        }
        switchToMap(file);
    }

    private void handleSave() {
        if (projectFile == null) {
            saveNewMap(null);
        } else {
            saveCurrentThen(null);
        }
    }

    private void saveCurrentThen(Runnable next) {
        Path target = projectFile;
        DmProject savedProject = project;
        DmProject snapshot;
        try {
            snapshot = projectService.copy(project);
        } catch (IOException ex) {
            status("Save failed: " + ex.getMessage());
            return;
        }
        runInBackground("Saving...", "Save failed: ", () -> {
            projectService.save(target, snapshot);
            return target;
        }, saved -> {
            if (project == savedProject) {
                adoptCopiedAssetPaths(snapshot);
            }
            status("Saved " + MapBrowser.displayName(saved) + ".");
            if (next != null) {
                next.run();
            }
        });
    }

    /** Saving a new map asks for a name and a folder inside the map library. */
    private void saveNewMap(Runnable next) {
        Optional<MapLocationDialog.Selection> selection = MapLocationDialog.show(primaryStage, mapLibrary, "Save map",
                MaterialDesignC.CONTENT_SAVE_OUTLINE, "Save", "New map", mapBrowser.selectedFolder());
        if (selection.isEmpty()) {
            return;
        }
        Path target;
        DmProject snapshot;
        try {
            target = mapLibrary.newMapFile(selection.get().folder(), selection.get().name());
            snapshot = projectService.copy(project);
        } catch (IOException ex) {
            Dialogs.error(primaryStage, "Could not save the map", ex.getMessage());
            return;
        }
        DmProject savedProject = project;
        runInBackground("Saving...", "Save failed: ", () -> {
            projectService.save(target, snapshot);
            return target;
        }, saved -> {
            if (project == savedProject) {
                projectFile = saved;
                adoptCopiedAssetPaths(snapshot);
                updateWindowTitle();
            }
            mapBrowser.refresh();
            mapBrowser.select(saved);
            status("Saved " + MapBrowser.displayName(saved) + " to the map library.");
            if (next != null) {
                next.run();
            }
        });
    }

    private record LibraryOutcome(MapLibraryService.Result result, Path openMapMovedTo, DmProject reloadedOpenMap) {
    }

    /**
     * Runs a map library operation (move, rename, copy, delete, ...). If it touches the open map, the map is saved
     * first and its file reference is updated afterwards.
     */
    private void runLibraryOperation(String busyMessage, Path affectedPath, MapBrowser.LibraryOperation operation,
                                     Consumer<MapLibraryService.Result> onDone) {
        Path openFile = projectFile == null ? null : projectFile.toAbsolutePath().normalize();
        boolean touchesOpenMap = openFile != null && affectedPath != null
                && openFile.startsWith(affectedPath.toAbsolutePath().normalize());
        DmProject savedProject = project;
        DmProject snapshot = null;
        if (touchesOpenMap) {
            try {
                snapshot = projectService.copy(project);
            } catch (IOException ex) {
                status("Could not save the open map: " + ex.getMessage());
                return;
            }
        }
        DmProject toSave = snapshot;
        runInBackground(busyMessage, "Library operation failed: ", () -> {
            if (toSave != null) {
                projectService.save(openFile, toSave);
            }
            MapLibraryService.Result result = operation.run();
            Path movedTo = touchesOpenMap ? movedLocation(result, openFile) : null;
            DmProject reloaded = movedTo != null ? projectService.load(movedTo) : null;
            return new LibraryOutcome(result, movedTo, reloaded);
        }, outcome -> {
            if (touchesOpenMap && project == savedProject) {
                if (outcome.openMapMovedTo() != null) {
                    projectFile = outcome.openMapMovedTo();
                    adoptCopiedAssetPaths(outcome.reloadedOpenMap());
                } else if (!Files.exists(openFile)) {
                    switchProject(freshProject(), null);
                    status("The open map was deleted.");
                }
            }
            if (frozenPlayerProjectFile != null) {
                Path frozenMoved = movedLocation(outcome.result(), frozenPlayerProjectFile.toAbsolutePath().normalize());
                if (frozenMoved != null) {
                    frozenPlayerProjectFile = frozenMoved;
                }
            }
            onDone.accept(outcome.result());
            mapBrowser.updateCurrentMap();
            updateWindowTitle();
            if (statusLabel.getText().equals(busyMessage)) {
                status("Done.");
            }
        }, ex -> {
            Dialogs.error(primaryStage, "That did not work", ex.getMessage());
            mapBrowser.refresh();
        });
    }

    private static Path movedLocation(MapLibraryService.Result result, Path file) {
        for (Map.Entry<Path, Path> entry : result.movedMaps().entrySet()) {
            if (entry.getKey().toAbsolutePath().normalize().equals(file)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private void updateWindowTitle() {
        if (primaryStage != null) {
            primaryStage.setTitle("Dungeon Master Map Tool — "
                    + (projectFile == null ? "Unsaved new map" : MapBrowser.displayName(projectFile)));
        }
    }

    /** After a save the snapshot points at the copies inside the project folder; the live project should too. */
    private void adoptCopiedAssetPaths(DmProject saved) {
        if (saved == null) {
            return;
        }
        for (DmProject.ImageLayer savedLayer : saved.getImageLayers()) {
            DmProject.ImageLayer live = findLayerById(savedLayer.getId());
            if (live != null) {
                live.setPath(savedLayer.getPath());
            }
        }
        if (saved.getMap() != null && saved.getMap().getImagePath() != null) {
            project.getMap().setImagePath(saved.getMap().getImagePath());
        }
    }

    private record LoadedProject(DmProject project, Path file) {
    }

    private interface IoWork<T> {
        T run() throws IOException;
    }

    private <T> void runInBackground(String busyMessage, String failurePrefix, IoWork<T> work, Consumer<T> onSuccess) {
        runInBackground(busyMessage, failurePrefix, work, onSuccess, null);
    }

    /** Runs blocking file work off the FX thread; result handling happens back on the FX thread. */
    private <T> void runInBackground(String busyMessage, String failurePrefix, IoWork<T> work, Consumer<T> onSuccess,
                                     Consumer<Exception> onFailure) {
        if (ioBusy) {
            status("Still working on the previous file operation.");
            return;
        }
        ioBusy = true;
        status(busyMessage);
        Thread thread = new Thread(() -> {
            try {
                T result = work.run();
                Platform.runLater(() -> {
                    ioBusy = false;
                    onSuccess.accept(result);
                });
            } catch (IOException | RuntimeException ex) {
                Platform.runLater(() -> {
                    ioBusy = false;
                    status(failurePrefix + ex.getMessage());
                    if (onFailure != null) {
                        onFailure.accept(ex);
                    }
                });
            }
        }, "dmmt-io");
        thread.setDaemon(true);
        thread.start();
    }

    private void addImageLayerFromFile(Path imagePath) {
        try {
            BufferedImage image = ImageIO.read(imagePath.toFile());
            if (image == null) {
                return;
            }
            if (project == null) {
                project = DmProject.builder().build();
            }
            project.getMap().setSourceType("custom");
            DmProject.ImageLayer layer = DmProject.ImageLayer.builder()
                    .id("layer-" + UUID.randomUUID())
                    .path(imagePath.toAbsolutePath().toString())
                    .x(0)
                    .y(0)
                    .width(image.getWidth())
                    .height(image.getHeight())
                    .zIndex(project.getImageLayers().size())
                    .build();
            project.getImageLayers().add(layer);
            selectedLayer = layer;
            selectedLight = null;
            executeWithHistory(
                    "Add image layer",
                    () -> {
                        if (findLayerById(layer.getId()) == null) {
                            project.getImageLayers().add(cloneLayer(layer));
                        }
                    },
                    () -> project.getImageLayers().removeIf(l -> l.getId().equals(layer.getId()))
            );
            status("Added image layer: " + imagePath.getFileName());
        } catch (IOException ex) {
            status("Could not add image: " + ex.getMessage());
        }
    }

    private void deleteSelectedLayer() {
        DmProject.ImageLayer layer = findLayerById(selectedLayer == null ? null : selectedLayer.getId());
        if (layer == null) {
            selectedLayer = null;
            return;
        }
        DmProject.ImageLayer backup = cloneLayer(layer);
        executeWithHistory(
                "Delete image layer",
                () -> {
                    project.getImageLayers().removeIf(l -> l.getId().equals(backup.getId()));
                    if (selectedLayer != null && backup.getId().equals(selectedLayer.getId())) {
                        selectedLayer = null;
                    }
                },
                () -> {
                    if (findLayerById(backup.getId()) == null) {
                        project.getImageLayers().add(cloneLayer(backup));
                    }
                    selectedLayer = findLayerById(backup.getId());
                }
        );
        status("Deleted image layer.");
    }

    private DmProject.ImageLayer pickTopmostLayer(double worldX, double worldY) {
        return project.getImageLayers().stream()
                .filter(layer -> contains(layer, worldX, worldY))
                .max(Comparator.comparingInt(DmProject.ImageLayer::getZIndex))
                .orElse(null);
    }

    private boolean toggleInteractable(DmProject.Interactable target) {
        if (target == null) {
            return false;
        }
        String previous = target.getState();
        String next = "open".equalsIgnoreCase(previous) ? "closed" : "open";
        String interactableId = target.getId();
        executeWithFogHistory(
                "Toggle " + target.getType(),
                () -> setInteractableState(interactableId, next),
                () -> setInteractableState(interactableId, previous)
        );
        status("Set " + target.getType() + " to " + target.getState());
        return true;
    }

    /** Door/window whose icon badge (drawn at the middle of the door line) is under the given world point. */
    private DmProject.Interactable pickInteractableBadge(double worldX, double worldY) {
        double zoom = Math.max(0.01, project.getViews().getDmCamera().getZoom());
        double tolerance = (CanvasMapRenderer.INTERACTABLE_BADGE_RADIUS + 3) / zoom;
        DmProject.Interactable nearest = null;
        double best = tolerance;
        for (DmProject.Interactable interactable : project.getInteractables()) {
            double d = distance(worldX, worldY,
                    (interactable.getX1() + interactable.getX2()) / 2.0,
                    (interactable.getY1() + interactable.getY2()) / 2.0);
            if (d <= best) {
                best = d;
                nearest = interactable;
            }
        }
        return nearest;
    }

    /** Door/window whose line is within a few screen pixels of the given world point. */
    private DmProject.Interactable pickInteractableLine(double worldX, double worldY) {
        double zoom = Math.max(0.01, project.getViews().getDmCamera().getZoom());
        DmProject.Interactable nearest = null;
        double best = 10 / zoom;
        for (DmProject.Interactable interactable : project.getInteractables()) {
            double d = pointToSegmentDistance(worldX, worldY, interactable.getX1(), interactable.getY1(), interactable.getX2(), interactable.getY2());
            if (d <= best) {
                best = d;
                nearest = interactable;
            }
        }
        return nearest;
    }

    /** Door/window under the mouse in Select mode, respecting the same priority as clicks (badge, light, line). */
    private DmProject.Interactable pickInteractableForClick(double worldX, double worldY) {
        if (!renderer.isWallLayerVisible()) {
            return null;
        }
        DmProject.Interactable badge = pickInteractableBadge(worldX, worldY);
        if (badge != null) {
            return badge;
        }
        double zoom = Math.max(0.01, project.getViews().getDmCamera().getZoom());
        if (pickNearestLight(worldX, worldY, 24 / zoom) != null) {
            return null;
        }
        return pickInteractableLine(worldX, worldY);
    }

    private String hoveredInteractableId() {
        if (!hoverInsideCanvas || pingArmed || activeTool != EditorTool.SELECT
                || panningDmCamera || draggingLight || draggingLayer || draggingOverlay || draggingPlayerViewport || resizingLayer) {
            return null;
        }
        DmProject.Interactable hovered = pickInteractableForClick(hoverWorldX, hoverWorldY);
        return hovered == null ? null : hovered.getId();
    }

    private CanvasMapRenderer.WorldRect getPlayerViewportRect() {
        if (playerCanvas == null || playerStage == null) {
            return null;
        }
        DmProject.CameraState playerCamera = project.getViews().getPlayerCamera();
        double worldWidth = playerCanvas.getWidth() / Math.max(playerCamera.getZoom(), 0.01);
        double worldHeight = playerCanvas.getHeight() / Math.max(playerCamera.getZoom(), 0.01);
        return new CanvasMapRenderer.WorldRect(
                playerCamera.getX() - worldWidth / 2.0,
                playerCamera.getY() - worldHeight / 2.0,
                worldWidth,
                worldHeight
        );
    }

    /** True if the world point is on the player viewport's grab bar (only when the player window is open). */
    private boolean isOnPlayerViewportTitleBar(double worldX, double worldY) {
        CanvasMapRenderer.WorldRect rect = getPlayerViewportRect();
        if (playerStage == null || rect == null || dmCanvas == null) {
            return false;
        }
        DmProject.CameraState dmCamera = project.getViews().getDmCamera();
        double w = dmCanvas.getWidth();
        double h = dmCanvas.getHeight();
        return renderer.isOnViewportTitleBar(rect,
                renderer.worldToScreenX(worldX, w, dmCamera), renderer.worldToScreenY(worldY, h, dmCamera),
                w, h, dmCamera);
    }

    private void addPing(double worldX, double worldY) {
        project.getActivePings().add(DmProject.PingEvent.builder()
                .x(worldX)
                .y(worldY)
                .createdAtMillis(System.currentTimeMillis())
                .durationMillis(1200)
                .build());
    }

    // ---- Tools & fog editing ----

    private void updateHover(double screenX, double screenY) {
        CanvasMapRenderer.WorldPoint world = renderer.screenToWorld(
                screenX, screenY, dmCanvas.getWidth(), dmCanvas.getHeight(), project.getViews().getDmCamera());
        hoverInsideCanvas = true;
        hoverWorldX = world.x();
        hoverWorldY = world.y();
    }

    private void setWallLayerVisible(boolean visible) {
        renderer.setWallLayerVisible(visible);
        if (wallLayerToggle != null && wallLayerToggle.isSelected() != visible) {
            wallLayerToggle.setSelected(visible);
        }
        updateCanvasCursor();
        status(visible ? "Wall layer shown (walls, doors and windows)." : "Wall layer hidden — doors and windows can't be clicked until it is shown again.");
    }

    private void setActiveTool(EditorTool tool) {
        activeTool = tool == null ? EditorTool.SELECT : tool;
        if (activeTool.isWallTool() && !renderer.isWallLayerVisible()) {
            setWallLayerVisible(true);
        }
        fogDragging = false;
        fogBeforeSnapshot = null;
        draftOverlay = null;
        draftWall = null;
        toolButtons.forEach((key, button) -> button.setSelected(key == activeTool));
        if (pingArmed) {
            setPingArmed(false);
        }
        updateToolChip();
        updateCanvasCursor();
        switch (activeTool) {
            case SELECT -> status("Select: click a door/window icon to open or close it; drag lights, layers and the player viewport. Right-click a light for options.");
            case REVEAL_BRUSH -> status("Reveal brush: paint to uncover the map.");
            case HIDE_BRUSH -> status("Hide brush: paint to cover the map with fog.");
            case REVEAL_RECT -> status("Reveal rectangle: drag to uncover an area.");
            case HIDE_RECT -> status("Hide rectangle: drag to cover an area with fog.");
            case AOE_CIRCLE -> status("Circle effect: drag from the center outward.");
            case AOE_RECT -> status("Box effect: drag from corner to corner.");
            case AOE_BRUSH -> status("Draw effect: paint a freeform area (brush size sets thickness).");
            case WALL_DRAW -> status("Wall: drag to draw a wall that blocks light (snaps to half tiles, hold Shift for free placement).");
            case WALL_ERASE -> status("Erase wall: click a wall segment to remove it.");
            case LIGHT_ADD -> status("Add light: click the map where the light should go.");
            case LIGHT_REMOVE -> status("Remove light: click the light you want to remove.");
        }
    }

    private double brushRadiusWorld() {
        return brushSizeTiles * project.getMap().getGrid().getPixelsPerCell() / 2.0;
    }

    private void paintBrushSegment(FogMask mask, double fromX, double fromY, double toX, double toY) {
        double radius = brushRadiusWorld();
        double length = distance(fromX, fromY, toX, toY);
        double step = Math.max(mask.getCellSize(), radius / 2.0);
        int steps = Math.max(1, (int) Math.ceil(length / step));
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            mask.applyCircle(fromX + (toX - fromX) * t, fromY + (toY - fromY) * t, radius, activeTool.reveal);
        }
    }

    private void fillFog(boolean reveal) {
        FogMask mask = project.getFog().getMask();
        if (mask == null) {
            return;
        }
        FogMask.Snapshot before = mask.snapshot();
        mask.applyRect(mask.getOriginX(), mask.getOriginY(), mask.getWidth(), mask.getHeight(), reveal);
        FogMask.Snapshot after = mask.snapshot();
        if (!before.sameBits(after)) {
            recordHistory(reveal ? "Reveal all fog" : "Hide all fog", () -> restoreFog(after), () -> restoreFog(before));
        }
        status(reveal ? "Revealed the whole map." : "Covered the whole map with fog.");
    }

    private void setFogEnabled(boolean enabled) {
        project.getFog().setEnabled(enabled);
        syncControlsFromProject();
    }

    private void setTimeOfDay(String presetName) {
        project.getLighting().setTimeOfDayPreset(presetName);
        syncControlsFromProject();
    }

    private FogMask.Snapshot snapshotFog() {
        FogMask mask = project.getFog().getMask();
        return mask == null ? null : mask.snapshot();
    }

    private void restoreFog(FogMask.Snapshot snapshot) {
        FogMask mask = project.getFog().getMask();
        if (mask != null && snapshot != null) {
            mask.restore(snapshot);
        }
        lightingEngine.markPersistentRevealsApplied(project);
    }

    private void recordHistory(String label, Runnable redo, Runnable undo) {
        pushHistory(new HistoryAction(label, redo, undo));
    }

    /**
     * Runs an action that may cause persistent lights to reveal fog, and records fog
     * snapshots so undo/redo restores the exact fog state.
     */
    private void executeWithFogHistory(String label, Runnable doAction, Runnable undoAction) {
        FogMask.Snapshot before = snapshotFog();
        doAction.run();
        recordWithFog(label, before, doAction, undoAction);
    }

    private void recordWithFog(String label, FogMask.Snapshot before, Runnable redo, Runnable undo) {
        lightingEngine.update(project);
        FogMask.Snapshot after = snapshotFog();
        pushHistory(new HistoryAction(
                label,
                () -> {
                    redo.run();
                    restoreFog(after);
                },
                () -> {
                    undo.run();
                    restoreFog(before);
                }
        ));
    }

    private void drawToolPreview(GraphicsContext gc) {
        if (draftWall != null) {
            DmProject.CameraState wallCamera = project.getViews().getDmCamera();
            double ww = dmFogCanvas.getWidth();
            double wh = dmFogCanvas.getHeight();
            gc.setStroke(Color.web("#FFD24A"));
            gc.setLineWidth(3);
            gc.strokeLine(renderer.worldToScreenX(draftWall.getX1(), ww, wallCamera), renderer.worldToScreenY(draftWall.getY1(), wh, wallCamera),
                    renderer.worldToScreenX(draftWall.getX2(), ww, wallCamera), renderer.worldToScreenY(draftWall.getY2(), wh, wallCamera));
        }
        if (!activeTool.isFogTool()) {
            return;
        }
        DmProject.CameraState camera = project.getViews().getDmCamera();
        double w = dmFogCanvas.getWidth();
        double h = dmFogCanvas.getHeight();
        Color stroke = activeTool.reveal ? Color.web("#7CFFB2") : Color.web("#FF8A7A");
        gc.setStroke(stroke);
        gc.setLineWidth(1.5);
        gc.setLineDashes(6, 4);
        if (activeTool.rect) {
            if (fogDragging) {
                double x1 = renderer.worldToScreenX(fogDragStartWorldX, w, camera);
                double y1 = renderer.worldToScreenY(fogDragStartWorldY, h, camera);
                double x2 = renderer.worldToScreenX(fogCurrentWorldX, w, camera);
                double y2 = renderer.worldToScreenY(fogCurrentWorldY, h, camera);
                gc.setFill(stroke.deriveColor(0, 1, 1, 0.15));
                gc.fillRect(Math.min(x1, x2), Math.min(y1, y2), Math.abs(x2 - x1), Math.abs(y2 - y1));
                gc.strokeRect(Math.min(x1, x2), Math.min(y1, y2), Math.abs(x2 - x1), Math.abs(y2 - y1));
            }
        } else if (hoverInsideCanvas) {
            double cx = renderer.worldToScreenX(hoverWorldX, w, camera);
            double cy = renderer.worldToScreenY(hoverWorldY, h, camera);
            double r = brushRadiusWorld() * camera.getZoom();
            gc.strokeOval(cx - r, cy - r, r * 2, r * 2);
        }
        gc.setLineDashes((double[]) null);
    }

    // ---- Project switching & control sync ----

    private void switchProject(DmProject next, Path file) {
        project = next;
        project.getViews().setPlayerFrozen(false);
        projectFile = file;
        new FogService().ensureMask(project);
        lightingEngine.reset();
        selectedLayer = null;
        selectedLight = null;
        selectedOverlayId = null;
        draftOverlay = null;
        draggingOverlay = false;
        fogDragging = false;
        undoStack.clear();
        redoStack.clear();
        syncControlsFromProject();
        if (mapBrowser != null) {
            mapBrowser.updateCurrentMap();
        }
        updateWindowTitle();
    }

    private void syncControlsFromProject() {
        syncingControls = true;
        try {
            if (fogToggleButton != null) {
                fogToggleButton.setSelected(project.getFog().isEnabled());
            }
            TimeOfDayPreset preset = TimeOfDayPreset.from(project.getLighting().getTimeOfDayPreset());
            timeButtons.forEach((key, button) -> button.setSelected(key == preset));
            if (freezePlayerButton != null) {
                freezePlayerButton.setSelected(frozenPlayerProject != null);
            }
        } finally {
            syncingControls = false;
        }
    }

    // ---- Wall editing ----

    private double snapLayer(double value) {
        if (!snapLayersToGrid) {
            return value;
        }
        double step = project.getMap().getGrid().getPixelsPerCell() / 2.0;
        return Math.round(value / step) * step;
    }

    private double[] snapWallPoint(double worldX, double worldY, boolean free) {
        if (free) {
            return new double[]{worldX, worldY};
        }
        double step = project.getMap().getGrid().getPixelsPerCell() / 2.0;
        return new double[]{Math.round(worldX / step) * step, Math.round(worldY / step) * step};
    }

    private void finishWallDraw() {
        DmProject.WallSegment wall = draftWall;
        draftWall = null;
        if (distance(wall.getX1(), wall.getY1(), wall.getX2(), wall.getY2()) < 2) {
            return;
        }
        executeWithHistory("Add wall", () -> project.getWalls().add(wall), () -> project.getWalls().remove(wall));
    }

    private void eraseWallAt(double worldX, double worldY, double zoom) {
        double tolerance = 10 / Math.max(0.01, zoom);
        DmProject.WallSegment nearest = null;
        double best = tolerance;
        for (DmProject.WallSegment wall : project.getWalls()) {
            double d = distanceToSegment(worldX, worldY, wall);
            if (d <= best) {
                best = d;
                nearest = wall;
            }
        }
        if (nearest == null) {
            return;
        }
        DmProject.WallSegment target = nearest;
        int index = project.getWalls().indexOf(target);
        executeWithHistory("Erase wall", () -> project.getWalls().remove(target),
                () -> project.getWalls().add(Math.min(index, project.getWalls().size()), target));
    }

    private double distanceToSegment(double px, double py, DmProject.WallSegment wall) {
        double dx = wall.getX2() - wall.getX1();
        double dy = wall.getY2() - wall.getY1();
        double lengthSq = dx * dx + dy * dy;
        double t = lengthSq == 0 ? 0 : clamp(((px - wall.getX1()) * dx + (py - wall.getY1()) * dy) / lengthSq, 0, 1);
        return distance(px, py, wall.getX1() + t * dx, wall.getY1() + t * dy);
    }

    // ---- Player scale (1 inch per tile) ----

    private int selectedScreenIndex() {
        int index = playerScreenSelector == null ? -1 : playerScreenSelector.getSelectionModel().getSelectedIndex();
        return index >= 0 ? index : preferences.getInt(PREF_PLAYER_SCREEN_INDEX, 0);
    }

    /** Stored diagonal for the selected screen, or an estimate from the OS-reported DPI. */
    private double loadScreenDiagonal() {
        List<Screen> screens = Screen.getScreens();
        int index = Math.max(0, Math.min(selectedScreenIndex(), screens.size() - 1));
        double stored = preferences.getDouble(PREF_SCREEN_DIAGONAL_PREFIX + index, 0);
        if (stored >= 10) {
            return stored;
        }
        Screen screen = screens.isEmpty() ? Screen.getPrimary() : screens.get(index);
        Rectangle2D bounds = screen.getBounds();
        double physicalWidth = bounds.getWidth() * screen.getOutputScaleX();
        double physicalHeight = bounds.getHeight() * screen.getOutputScaleY();
        double dpi = Math.max(48, screen.getDpi());
        double guess = Math.hypot(physicalWidth, physicalHeight) / dpi;
        return Math.max(10, Math.min(120, Math.round(guess * 2) / 2.0));
    }

    /** Player-canvas pixels (device-independent) per physical inch on the selected screen. */
    private double playerPixelsPerInch() {
        Screen screen = resolveSelectedPlayerScreen();
        Rectangle2D bounds = screen.getBounds();
        double diagonal = screenInchesSpinner == null || screenInchesSpinner.getValue() == null ? 27 : screenInchesSpinner.getValue();
        return Math.hypot(bounds.getWidth(), bounds.getHeight()) / Math.max(1, diagonal);
    }

    /** Keeps the player camera zoom so that one map tile measures the configured number of inches. */
    private void applyPlayerScale() {
        if (playerStage == null || tileInchesSpinner == null || tileInchesSpinner.getValue() == null) {
            return;
        }
        double ppi = playerPixelsPerInch();
        applyScaleTo(project.getViews().getPlayerCamera(), project, ppi);
        if (frozenPlayerProject != null && frozenPlayerCamera != null) {
            applyScaleTo(frozenPlayerCamera, frozenPlayerProject, ppi);
        }
    }

    private void applyScaleTo(DmProject.CameraState camera, DmProject target, double ppi) {
        double cell = Math.max(1, target.getMap().getGrid().getPixelsPerCell());
        double zoom = clamp(ppi * tileInchesSpinner.getValue() / cell, 0.05, 12);
        if (Math.abs(camera.getZoom() - zoom) > 1e-4) {
            camera.setZoom(zoom);
        }
    }

    private void drawScaleTestSquare(GraphicsContext gc) {
        double size = playerPixelsPerInch();
        double x = (playerFogCanvas.getWidth() - size) / 2.0;
        double y = (playerFogCanvas.getHeight() - size) / 2.0;
        gc.setFill(Color.color(1, 1, 1, 0.85));
        gc.fillRect(x, y, size, size);
        gc.setStroke(Color.RED);
        gc.setLineWidth(2);
        gc.strokeRect(x, y, size, size);
        gc.setFill(Color.BLACK);
        gc.fillText("1 inch", x + size / 2.0 - 16, y + size / 2.0 + 4);
    }

    private Spinner<Double> createDoubleSpinner(double min, double max, double initial, double step, double width) {
        Spinner<Double> spinner = new Spinner<>();
        spinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(min, max, Math.max(min, Math.min(max, initial)), step));
        spinner.setEditable(true);
        spinner.setPrefWidth(width);
        // Commit typed text when focus leaves the editor.
        spinner.getEditor().focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                try {
                    double typed = Double.parseDouble(spinner.getEditor().getText().replace(',', '.'));
                    spinner.getValueFactory().setValue(Math.max(min, Math.min(max, typed)));
                } catch (NumberFormatException ex) {
                    spinner.getEditor().setText(String.valueOf(spinner.getValue()));
                }
            }
        });
        return spinner;
    }

    // ---- Map switching ----

    private void switchToMap(Path file) {
        if (projectFile != null && file.toAbsolutePath().normalize().equals(projectFile.toAbsolutePath().normalize())) {
            status("That map is already open.");
            return;
        }
        Path current = projectFile;
        DmProject snapshot;
        try {
            snapshot = current == null ? null : projectService.copy(project);
        } catch (IOException ex) {
            status("Could not switch map: " + ex.getMessage());
            return;
        }
        runInBackground("Loading map...", "Could not switch map: ", () -> {
            if (snapshot != null) {
                projectService.save(current, snapshot);
            }
            return new LoadedProject(projectService.load(file), file);
        }, loaded -> {
            switchProject(loaded.project(), loaded.file());
            status("Switched to " + MapBrowser.displayName(file)
                    + (frozenPlayerProject != null ? ". Player view is still frozen on the previous map." : "."));
        });
    }

    // ---- Effects (AOE overlays) ----

    private DmProject.OverlayShape findOverlay(String id) {
        if (id == null) {
            return null;
        }
        return project.getOverlays().stream().filter(o -> id.equals(o.getId())).findFirst().orElse(null);
    }

    private DmProject.OverlayShape cloneOverlay(DmProject.OverlayShape source) {
        return DmProject.OverlayShape.builder()
                .id(source.getId())
                .type(source.getType())
                .x(source.getX())
                .y(source.getY())
                .width(source.getWidth())
                .height(source.getHeight())
                .radius(source.getRadius())
                .strokeWidth(source.getStrokeWidth())
                .points(new java.util.ArrayList<>(source.getPoints()))
                .color(source.getColor())
                .alpha(source.getAlpha())
                .playerVisible(source.isPlayerVisible())
                .build();
    }

    private DmProject.OverlayShape cloneOverlayOrNull(DmProject.OverlayShape source) {
        return source == null ? null : cloneOverlay(source);
    }

    /** Sets the overlay to the given state, inserting it if missing, or removes it when state is null. */
    private void applyOverlayState(String id, DmProject.OverlayShape state) {
        List<DmProject.OverlayShape> overlays = project.getOverlays();
        int index = -1;
        for (int i = 0; i < overlays.size(); i++) {
            if (id.equals(overlays.get(i).getId())) {
                index = i;
                break;
            }
        }
        if (state == null) {
            if (index >= 0) {
                overlays.remove(index);
            }
            if (id.equals(selectedOverlayId)) {
                selectedOverlayId = null;
            }
            return;
        }
        DmProject.OverlayShape copy = cloneOverlay(state);
        if (index >= 0) {
            overlays.set(index, copy);
        } else {
            overlays.add(copy);
        }
    }

    private void recordOverlayChange(String label, String id, DmProject.OverlayShape before, DmProject.OverlayShape after) {
        DmProject.OverlayShape beforeCopy = cloneOverlayOrNull(before);
        DmProject.OverlayShape afterCopy = cloneOverlayOrNull(after);
        pushHistory(new HistoryAction(
                label,
                () -> applyOverlayState(id, afterCopy),
                () -> applyOverlayState(id, beforeCopy)
        ));
    }

    private void executeOverlayChange(String label, String id, Consumer<DmProject.OverlayShape> mutator) {
        DmProject.OverlayShape shape = findOverlay(id);
        if (shape == null) {
            return;
        }
        DmProject.OverlayShape before = cloneOverlay(shape);
        mutator.accept(shape);
        recordOverlayChange(label, id, before, cloneOverlay(shape));
    }

    private void beginOverlayDraw(double worldX, double worldY) {
        double pixelsPerCell = project.getMap().getGrid().getPixelsPerCell();
        String type = switch (activeTool) {
            case AOE_CIRCLE -> "circle";
            case AOE_RECT -> "rect";
            default -> "brush";
        };
        DmProject.OverlayShape shape = DmProject.OverlayShape.builder()
                .id("overlay-" + UUID.randomUUID())
                .type(type)
                .x(worldX)
                .y(worldY)
                .strokeWidth(brushSizeTiles * pixelsPerCell)
                .color(overlayColor)
                .alpha(overlayAlpha)
                .playerVisible(overlayPlayerVisible)
                .build();
        if ("brush".equals(type)) {
            shape.getPoints().add(worldX);
            shape.getPoints().add(worldY);
        }
        overlayStartX = worldX;
        overlayStartY = worldY;
        selectedOverlayId = null;
        project.getOverlays().add(shape);
        draftOverlay = shape;
    }

    private void updateOverlayDraw(double worldX, double worldY) {
        DmProject.OverlayShape shape = draftOverlay;
        switch (shape.getType()) {
            case "circle" -> shape.setRadius(distance(overlayStartX, overlayStartY, worldX, worldY));
            case "rect" -> {
                shape.setX(Math.min(overlayStartX, worldX));
                shape.setY(Math.min(overlayStartY, worldY));
                shape.setWidth(Math.abs(worldX - overlayStartX));
                shape.setHeight(Math.abs(worldY - overlayStartY));
            }
            default -> {
                List<Double> points = shape.getPoints();
                double lastX = points.get(points.size() - 2);
                double lastY = points.get(points.size() - 1);
                if (distance(lastX, lastY, worldX, worldY) >= Math.max(2, shape.getStrokeWidth() / 8.0)) {
                    points.add(worldX);
                    points.add(worldY);
                }
            }
        }
    }

    private void finishOverlayDraw() {
        DmProject.OverlayShape shape = draftOverlay;
        draftOverlay = null;
        boolean tooSmall = switch (shape.getType()) {
            case "circle" -> shape.getRadius() < 3;
            case "rect" -> shape.getWidth() < 3 || shape.getHeight() < 3;
            default -> false;
        };
        if (tooSmall) {
            project.getOverlays().removeIf(o -> o.getId().equals(shape.getId()));
            return;
        }
        selectedOverlayId = shape.getId();
        recordOverlayChange("Draw effect", shape.getId(), null, cloneOverlay(shape));
    }

    private DmProject.OverlayShape pickOverlay(double worldX, double worldY, double zoom) {
        List<DmProject.OverlayShape> overlays = project.getOverlays();
        double tolerance = 6 / Math.max(0.01, zoom);
        for (int i = overlays.size() - 1; i >= 0; i--) {
            DmProject.OverlayShape shape = overlays.get(i);
            boolean hit = switch (shape.getType() == null ? "" : shape.getType()) {
                case "circle" -> distance(worldX, worldY, shape.getX(), shape.getY()) <= shape.getRadius();
                case "rect" -> worldX >= shape.getX() && worldX <= shape.getX() + shape.getWidth()
                        && worldY >= shape.getY() && worldY <= shape.getY() + shape.getHeight();
                case "brush" -> brushHit(shape, worldX, worldY, shape.getStrokeWidth() / 2.0 + tolerance);
                default -> false;
            };
            if (hit) {
                return shape;
            }
        }
        return null;
    }

    private boolean brushHit(DmProject.OverlayShape shape, double worldX, double worldY, double reach) {
        List<Double> points = shape.getPoints();
        int n = points.size() / 2;
        if (n == 1) {
            return distance(worldX, worldY, points.get(0), points.get(1)) <= reach;
        }
        for (int i = 0; i + 1 < n; i++) {
            if (pointToSegmentDistance(worldX, worldY,
                    points.get(2 * i), points.get(2 * i + 1), points.get(2 * i + 2), points.get(2 * i + 3)) <= reach) {
                return true;
            }
        }
        return false;
    }

    private void translateOverlay(DmProject.OverlayShape shape, double dx, double dy) {
        shape.setX(shape.getX() + dx);
        shape.setY(shape.getY() + dy);
        List<Double> points = shape.getPoints();
        for (int i = 0; i + 1 < points.size(); i += 2) {
            points.set(i, points.get(i) + dx);
            points.set(i + 1, points.get(i + 1) + dy);
        }
    }

    private void deleteSelectedOverlay() {
        DmProject.OverlayShape shape = findOverlay(selectedOverlayId);
        if (shape == null) {
            return;
        }
        DmProject.OverlayShape before = cloneOverlay(shape);
        applyOverlayState(shape.getId(), null);
        recordOverlayChange("Delete effect", before.getId(), before, null);
        status("Deleted effect.");
    }

    private void clearOverlays() {
        if (project.getOverlays().isEmpty()) {
            return;
        }
        List<DmProject.OverlayShape> backup = new java.util.ArrayList<>();
        for (DmProject.OverlayShape shape : project.getOverlays()) {
            backup.add(cloneOverlay(shape));
        }
        executeWithHistory(
                "Clear effects",
                () -> {
                    project.getOverlays().clear();
                    selectedOverlayId = null;
                },
                () -> {
                    project.getOverlays().clear();
                    for (DmProject.OverlayShape shape : backup) {
                        project.getOverlays().add(cloneOverlay(shape));
                    }
                }
        );
        status("Cleared all effects.");
    }

    private void syncOverlayControls(DmProject.OverlayShape shape) {
        syncingControls = true;
        try {
            overlayColorPicker.setValue(Color.web(shape.getColor()));
            overlayAlphaSlider.setValue(shape.getAlpha());
            overlayPlayerToggle.setSelected(shape.isPlayerVisible());
            overlayColor = shape.getColor();
            overlayAlpha = shape.getAlpha();
            overlayPlayerVisible = shape.isPlayerVisible();
        } finally {
            syncingControls = false;
        }
    }

    private void drawOverlaySelection(GraphicsContext gc) {
        DmProject.OverlayShape shape = findOverlay(selectedOverlayId);
        if (shape == null) {
            return;
        }
        DmProject.CameraState camera = project.getViews().getDmCamera();
        double w = dmFogCanvas.getWidth();
        double h = dmFogCanvas.getHeight();
        double zoom = camera.getZoom();
        gc.setStroke(Color.YELLOW);
        gc.setLineWidth(1.5);
        gc.setLineDashes(8, 6);
        switch (shape.getType()) {
            case "circle" -> {
                double cx = renderer.worldToScreenX(shape.getX(), w, camera);
                double cy = renderer.worldToScreenY(shape.getY(), h, camera);
                double r = shape.getRadius() * zoom;
                gc.strokeOval(cx - r, cy - r, r * 2, r * 2);
            }
            case "rect" -> gc.strokeRect(
                    renderer.worldToScreenX(shape.getX(), w, camera),
                    renderer.worldToScreenY(shape.getY(), h, camera),
                    shape.getWidth() * zoom, shape.getHeight() * zoom);
            default -> {
                List<Double> points = shape.getPoints();
                double minX = Double.MAX_VALUE;
                double minY = Double.MAX_VALUE;
                double maxX = -Double.MAX_VALUE;
                double maxY = -Double.MAX_VALUE;
                for (int i = 0; i + 1 < points.size(); i += 2) {
                    minX = Math.min(minX, points.get(i));
                    maxX = Math.max(maxX, points.get(i));
                    minY = Math.min(minY, points.get(i + 1));
                    maxY = Math.max(maxY, points.get(i + 1));
                }
                double pad = shape.getStrokeWidth() / 2.0;
                gc.strokeRect(
                        renderer.worldToScreenX(minX - pad, w, camera),
                        renderer.worldToScreenY(minY - pad, h, camera),
                        (maxX - minX + 2 * pad) * zoom, (maxY - minY + 2 * pad) * zoom);
            }
        }
        gc.setLineDashes((double[]) null);
    }

    private static String toHex(Color color) {
        return String.format("#%02X%02X%02X",
                (int) Math.round(color.getRed() * 255),
                (int) Math.round(color.getGreen() * 255),
                (int) Math.round(color.getBlue() * 255));
    }

    // ---- Light context menu ----

    private static final double[] LIGHT_RANGE_TILES = {1, 2, 3, 4, 6, 8, 12, 24, 100};
    private static final String[][] LIGHT_COLORS = {
            {"Warm torch", "#FFB35C"},
            {"Candle", "#FFD9A0"},
            {"Neutral", "#FFF4E0"},
            {"Moonlight", "#A8C8FF"},
            {"Arcane", "#C08CFF"},
            {"Fire", "#FF6A3D"}
    };
    private static final Object[][] FLICKER_PRESETS = {
            {"Off", 0.0, 0.0},
            {"Candle", 0.12, 2.5},
            {"Torch", 0.22, 1.4},
            {"Strong torch", 0.35, 1.8},
            {"Slow pulse", 0.3, 0.35}
    };

    private void showLightContextMenu(DmProject.LightSource light, double screenX, double screenY) {
        String id = light.getId();
        ContextMenu menu = new ContextMenu();

        CheckMenuItem power = new CheckMenuItem("Light on");
        power.setSelected(light.isEnabled());
        power.setOnAction(e -> updateLight(id, power.isSelected() ? "Turn light on" : "Turn light off", l -> l.setEnabled(power.isSelected())));

        Menu revealMenu = new Menu("Fog reveal");
        ToggleGroup revealGroup = new ToggleGroup();
        Object[][] revealOptions = {
                {"Keep revealed", DmProject.RevealMode.PERSISTENT},
                {"Only while lit", DmProject.RevealMode.WHILE_LIT},
                {"Don't reveal", DmProject.RevealMode.NONE}
        };
        DmProject.RevealMode currentMode = light.getRevealMode() == null ? DmProject.RevealMode.WHILE_LIT : light.getRevealMode();
        for (Object[] option : revealOptions) {
            DmProject.RevealMode mode = (DmProject.RevealMode) option[1];
            RadioMenuItem item = new RadioMenuItem((String) option[0]);
            item.setToggleGroup(revealGroup);
            item.setSelected(mode == currentMode);
            item.setOnAction(e -> updateLight(id, "Change light reveal mode", l -> l.setRevealMode(mode)));
            revealMenu.getItems().add(item);
        }

        Menu rangeMenu = new Menu("Range");
        ToggleGroup rangeGroup = new ToggleGroup();
        double cell = project.getMap().getGrid().getPixelsPerCell();
        for (double tiles : LIGHT_RANGE_TILES) {
            RadioMenuItem item = new RadioMenuItem((tiles == Math.floor(tiles) ? String.valueOf((int) tiles) : String.valueOf(tiles)) + " tiles");
            item.setToggleGroup(rangeGroup);
            item.setSelected(Math.abs(light.getRange() - tiles * cell) < 0.5);
            item.setOnAction(e -> updateLight(id, "Change light range", l -> l.setRange(tiles * cell)));
            rangeMenu.getItems().add(item);
        }

        Menu flickerMenu = new Menu("Flicker");
        ToggleGroup flickerGroup = new ToggleGroup();
        for (Object[] preset : FLICKER_PRESETS) {
            String name = (String) preset[0];
            double strength = (Double) preset[1];
            double speed = (Double) preset[2];
            boolean off = strength <= 0;
            DmProject.Flicker current = light.getFlicker();
            boolean selected = off
                    ? current == null || !current.isEnabled()
                    : current != null && current.isEnabled() && same(current.getStrength(), strength) && same(current.getSpeed(), speed);
            RadioMenuItem item = new RadioMenuItem(name);
            item.setToggleGroup(flickerGroup);
            item.setSelected(selected);
            item.setOnAction(e -> updateLight(id, "Change light flicker", l -> {
                DmProject.Flicker flicker = l.getFlicker() == null ? DmProject.Flicker.builder().build() : l.getFlicker();
                flicker.setEnabled(!off);
                if (!off) {
                    flicker.setStrength(strength);
                    flicker.setSpeed(speed);
                }
                l.setFlicker(flicker);
            }));
            flickerMenu.getItems().add(item);
        }

        Menu colorMenu = new Menu("Color");
        ToggleGroup colorGroup = new ToggleGroup();
        for (String[] option : LIGHT_COLORS) {
            RadioMenuItem item = new RadioMenuItem(option[0]);
            item.setToggleGroup(colorGroup);
            item.setSelected(option[1].equalsIgnoreCase(light.getColor()));
            javafx.scene.shape.Rectangle swatch = new javafx.scene.shape.Rectangle(12, 12, Color.web(option[1]));
            item.setGraphic(swatch);
            item.setOnAction(e -> updateLight(id, "Change light color", l -> l.setColor(option[1])));
            colorMenu.getItems().add(item);
        }

        Menu brightnessMenu = new Menu("Brightness");
        ToggleGroup brightnessGroup = new ToggleGroup();
        for (Object[] option : new Object[][]{{"Dim", 0.4}, {"Normal", 0.75}, {"Bright", 1.0}}) {
            double value = (double) option[1];
            RadioMenuItem item = new RadioMenuItem((String) option[0]);
            item.setToggleGroup(brightnessGroup);
            item.setSelected(Math.abs(light.getIntensity() - value) < 0.05);
            item.setOnAction(e -> updateLight(id, "Change light brightness", l -> l.setIntensity(value)));
            brightnessMenu.getItems().add(item);
        }

        CheckMenuItem shadows = new CheckMenuItem("Blocked by walls");
        shadows.setSelected(light.isCastsShadows());
        shadows.setOnAction(e -> updateLight(id, "Toggle light wall blocking", l -> l.setCastsShadows(shadows.isSelected())));

        MenuItem remove = new MenuItem("Remove light");
        remove.setOnAction(e -> removeLight(id));

        menu.getItems().addAll(power, new SeparatorMenuItem(), revealMenu, rangeMenu, brightnessMenu, flickerMenu, colorMenu, shadows, new SeparatorMenuItem(), remove);
        hideLightMenu();
        activeLightMenu = menu;
        menu.setOnHidden(e -> {
            if (activeLightMenu == menu) {
                activeLightMenu = null;
            }
        });
        menu.show(dmCanvas, screenX, screenY);
    }

    private void hideLightMenu() {
        if (activeLightMenu != null) {
            activeLightMenu.hide();
            activeLightMenu = null;
        }
    }

    private void updateLight(String id, String label, Consumer<DmProject.LightSource> mutator) {
        DmProject.LightSource light = findLightById(id);
        if (light == null) {
            return;
        }
        DmProject.LightSource before = cloneLight(light);
        DmProject.LightSource after = cloneLight(light);
        mutator.accept(after);
        executeWithFogHistory(label, () -> applyLightState(after), () -> applyLightState(before));
        status(label + ".");
    }

    private void applyLightState(DmProject.LightSource state) {
        DmProject.LightSource light = findLightById(state.getId());
        if (light == null) {
            return;
        }
        if (light.getRevealMode() != state.getRevealMode()) {
            lightingEngine.invalidatePersistentReveal(light.getId());
        }
        DmProject.LightSource copy = cloneLight(state);
        light.setX(copy.getX());
        light.setY(copy.getY());
        light.setRange(copy.getRange());
        light.setColor(copy.getColor());
        light.setIntensity(copy.getIntensity());
        light.setCastsShadows(copy.isCastsShadows());
        light.setEnabled(copy.isEnabled());
        light.setRevealMode(copy.getRevealMode());
        light.setFlicker(copy.getFlicker());
    }

    private void removeLight(String id) {
        DmProject.LightSource light = findLightById(id);
        if (light == null) {
            return;
        }
        DmProject.LightSource backup = cloneLight(light);
        executeWithHistory(
                "Remove light",
                () -> {
                    project.getLighting().getLights().removeIf(l -> l.getId().equals(id));
                    if (selectedLight != null && id.equals(selectedLight.getId())) {
                        selectedLight = null;
                    }
                },
                () -> {
                    if (findLightById(id) == null) {
                        project.getLighting().getLights().add(cloneLight(backup));
                    }
                }
        );
        status("Removed light.");
    }

    private boolean contains(DmProject.ImageLayer layer, double x, double y) {
        return x >= layer.getX() && x <= layer.getX() + layer.getWidth()
                && y >= layer.getY() && y <= layer.getY() + layer.getHeight();
    }

    private double pointToSegmentDistance(double px, double py, double x1, double y1, double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        if (dx == 0 && dy == 0) {
            return distance(px, py, x1, y1);
        }
        double t = ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy);
        t = clamp(t, 0, 1);
        double sx = x1 + t * dx;
        double sy = y1 + t * dy;
        return distance(px, py, sx, sy);
    }

    private double distance(double x1, double y1, double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private boolean isImageFile(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".webp");
    }

    private Path resolveProjectsRoot() throws IOException {
        return resolveApplicationHome().resolve("dmmap-projects");
    }

    private void applyInitialImportDirectory(FileChooser chooser) {
        String lastImportDirectory = preferences.get(PREF_LAST_IMPORT_DIRECTORY, null);
        if (lastImportDirectory == null || lastImportDirectory.isBlank()) {
            return;
        }
        File dir = Path.of(lastImportDirectory).toFile();
        if (dir.exists() && dir.isDirectory()) {
            chooser.setInitialDirectory(dir);
        }
    }

    private void rememberImportDirectory(Path directory) {
        if (directory == null) {
            return;
        }
        preferences.put(PREF_LAST_IMPORT_DIRECTORY, directory.toAbsolutePath().normalize().toString());
    }

    private Path resolveApplicationHome() throws IOException {
        try {
            Path codeSource = Path.of(DungeonMasterMapToolLauncher.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI())
                    .toAbsolutePath()
                    .normalize();
            return Files.isRegularFile(codeSource) ? codeSource.getParent() : codeSource;
        } catch (URISyntaxException | NullPointerException ex) {
            throw new IOException("Could not resolve application directory.", ex);
        }
    }

    private void refreshPlayerScreenSelector() {
        if (playerScreenSelector == null) {
            return;
        }
        List<Screen> screens = Screen.getScreens();
        playerScreenSelector.getItems().clear();
        for (int i = 0; i < screens.size(); i++) {
            Rectangle2D bounds = screens.get(i).getVisualBounds();
            String label = "Screen " + (i + 1) + " (" + (int) bounds.getWidth() + "x" + (int) bounds.getHeight()
                    + " at " + (int) bounds.getMinX() + "," + (int) bounds.getMinY() + ")";
            playerScreenSelector.getItems().add(label);
        }

        int preferredIndex = preferences.getInt(PREF_PLAYER_SCREEN_INDEX, 0);
        int safeIndex = Math.max(0, Math.min(preferredIndex, Math.max(0, screens.size() - 1)));
        if (!playerScreenSelector.getItems().isEmpty()) {
            playerScreenSelector.getSelectionModel().select(safeIndex);
        }
    }

    private void rememberSelectedPlayerScreenIndex() {
        if (playerScreenSelector == null) {
            return;
        }
        int selected = playerScreenSelector.getSelectionModel().getSelectedIndex();
        if (selected >= 0) {
            preferences.putInt(PREF_PLAYER_SCREEN_INDEX, selected);
        }
    }

    private Screen resolveSelectedPlayerScreen() {
        List<Screen> screens = Screen.getScreens();
        if (screens.isEmpty()) {
            return Screen.getPrimary();
        }
        int preferred = preferences.getInt(PREF_PLAYER_SCREEN_INDEX, 0);
        if (playerScreenSelector != null) {
            int selected = playerScreenSelector.getSelectionModel().getSelectedIndex();
            if (selected >= 0) {
                preferred = selected;
            }
        }
        int safe = Math.max(0, Math.min(preferred, screens.size() - 1));
        return screens.get(safe);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private void status(String text) {
        if (statusLabel != null) {
            statusLabel.setText(text);
        }
    }

    /**
     * Freezing snapshots the whole project (map, fog, lights, effects, camera) for the player view,
     * so the DM can edit or switch maps without the players seeing any of it until unfreezing.
     */
    private void setPlayerFrozen(boolean frozen) {
        if (!frozen) {
            frozenPlayerProject = null;
            frozenPlayerProjectFile = null;
            frozenPlayerCamera = null;
            playerLightingEngine.reset();
            return;
        }
        try {
            frozenPlayerProject = projectService.copy(project);
            frozenPlayerProjectFile = projectFile;
            frozenPlayerCamera = copyCamera(project.getViews().getPlayerCamera());
            playerLightingEngine.reset();
        } catch (IOException ex) {
            frozenPlayerProject = null;
            status("Could not freeze player view: " + ex.getMessage());
            syncControlsFromProject();
        }
    }

    private void addLightAt(double x, double y) {
        DmProject.LightSource light = DmProject.LightSource.builder()
                .id("light-" + UUID.randomUUID())
                .x(x)
                .y(y)
                .range(project.getMap().getGrid().getPixelsPerCell() * 4)
                .color("#FFB35C")
                .flicker(DmProject.Flicker.builder().enabled(true).strength(0.22).speed(1.4).build())
                .build();
        executeWithFogHistory(
                "Add light",
                () -> {
                    if (findLightById(light.getId()) == null) {
                        project.getLighting().getLights().add(cloneLight(light));
                    }
                    selectedLight = findLightById(light.getId());
                    selectedLayer = null;
                },
                () -> project.getLighting().getLights().removeIf(l -> l.getId().equals(light.getId()))
        );
    }

    private void executeWithHistory(String label, Runnable doAction, Runnable undoAction) {
        doAction.run();
        pushHistory(new HistoryAction(label, doAction, undoAction));
    }

    private void pushHistory(HistoryAction action) {
        undoStack.push(action);
        redoStack.clear();
        while (undoStack.size() > MAX_HISTORY) {
            undoStack.removeLast();
        }
    }

    private void undo() {
        if (undoStack.isEmpty()) {
            status("Nothing to undo.");
            return;
        }
        HistoryAction action = undoStack.pop();
        action.undo.run();
        redoStack.push(action);
        status("Undid: " + action.label);
    }

    private void redo() {
        if (redoStack.isEmpty()) {
            status("Nothing to redo.");
            return;
        }
        HistoryAction action = redoStack.pop();
        action.redo.run();
        undoStack.push(action);
        status("Redid: " + action.label);
    }

    private DmProject.ImageLayer findLayerById(String id) {
        if (id == null) {
            return null;
        }
        return project.getImageLayers().stream().filter(l -> id.equals(l.getId())).findFirst().orElse(null);
    }

    private DmProject.LightSource findLightById(String id) {
        if (id == null) {
            return null;
        }
        return project.getLighting().getLights().stream().filter(l -> id.equals(l.getId())).findFirst().orElse(null);
    }

    private DmProject.LightSource pickNearestLight(double x, double y, double maxDistance) {
        DmProject.LightSource nearest = null;
        double best = maxDistance;
        for (DmProject.LightSource light : project.getLighting().getLights()) {
            double d = distance(x, y, light.getX(), light.getY());
            if (d < best) {
                best = d;
                nearest = light;
            }
        }
        return nearest;
    }

    private void applyLayerBounds(String layerId, double x, double y, double width, double height) {
        DmProject.ImageLayer layer = findLayerById(layerId);
        if (layer == null) {
            return;
        }
        layer.setX(x);
        layer.setY(y);
        layer.setWidth(width);
        layer.setHeight(height);
        selectedLayer = layer;
    }

    private void setInteractableState(String interactableId, String state) {
        for (DmProject.Interactable interactable : project.getInteractables()) {
            if (interactableId.equals(interactable.getId())) {
                interactable.setState(state);
                return;
            }
        }
    }

    private DmProject.ImageLayer cloneLayer(DmProject.ImageLayer source) {
        return DmProject.ImageLayer.builder()
                .id(source.getId())
                .path(source.getPath())
                .x(source.getX())
                .y(source.getY())
                .width(source.getWidth())
                .height(source.getHeight())
                .rotationDeg(source.getRotationDeg())
                .zIndex(source.getZIndex())
                .visible(source.isVisible())
                .build();
    }

    private DmProject.LightSource cloneLight(DmProject.LightSource source) {
        return DmProject.LightSource.builder()
                .id(source.getId())
                .x(source.getX())
                .y(source.getY())
                .range(source.getRange())
                .color(source.getColor())
                .intensity(source.getIntensity())
                .castsShadows(source.isCastsShadows())
                .enabled(source.isEnabled())
                .revealMode(source.getRevealMode())
                .flicker(DmProject.Flicker.builder()
                        .enabled(source.getFlicker() != null && source.getFlicker().isEnabled())
                        .strength(source.getFlicker() != null ? source.getFlicker().getStrength() : 0.18)
                        .speed(source.getFlicker() != null ? source.getFlicker().getSpeed() : 1.5)
                        .build())
                .build();
    }

    private boolean same(double a, double b) {
        return Math.abs(a - b) < 0.00001;
    }

    private DmProject.CameraState getEffectivePlayerCamera() {
        if (frozenPlayerProject == null || frozenPlayerCamera == null) {
            return project.getViews().getPlayerCamera();
        }
        return frozenPlayerCamera;
    }

    private DmProject.CameraState copyCamera(DmProject.CameraState source) {
        return DmProject.CameraState.builder()
                .x(source.getX())
                .y(source.getY())
                .zoom(source.getZoom())
                .build();
    }

    private enum EditorTool {
        SELECT("Select & move", "drag lights, effects, image layers and the player viewport; right-click a light for settings (Esc)",
                MaterialDesignC.CURSOR_DEFAULT, false, false),
        REVEAL_BRUSH("Reveal brush", "paint to remove fog", MaterialDesignE.ERASER, true, false),
        HIDE_BRUSH("Fog brush", "paint fog back over the map", MaterialDesignB.BRUSH, false, false),
        REVEAL_RECT("Reveal area", "drag a rectangle to remove fog", MaterialDesignS.SELECTION_DRAG, true, true),
        HIDE_RECT("Fog area", "drag a rectangle to cover it with fog", MaterialDesignR.RECTANGLE, false, true),
        AOE_CIRCLE("Circle effect", "drag from the center outward to draw a round spell area",
                MaterialDesignC.CIRCLE_OUTLINE, false, false),
        AOE_RECT("Box effect", "drag corner to corner to draw a rectangular spell area",
                MaterialDesignS.SQUARE_OUTLINE, false, true),
        AOE_BRUSH("Freehand effect", "paint a free-form spell area with the brush", MaterialDesignD.DRAW, false, false),
        WALL_DRAW("Draw walls", "drag to draw a wall that blocks light; snaps to half tiles, hold Shift for free placement",
                MaterialDesignW.WALL, false, false),
        WALL_ERASE("Erase walls", "click a wall to remove it", MaterialDesignE.ERASER_VARIANT, false, false),
        LIGHT_ADD("Add light", "click the map to place a torch light", MaterialDesignL.LIGHTBULB_ON_OUTLINE, false, false),
        LIGHT_REMOVE("Remove light", "click a light to remove it", MaterialDesignL.LIGHTBULB_OFF_OUTLINE, false, false);

        private final String label;
        private final String description;
        private final Ikon icon;
        private final boolean reveal;
        private final boolean rect;

        EditorTool(String label, String description, Ikon icon, boolean reveal, boolean rect) {
            this.label = label;
            this.description = description;
            this.icon = icon;
            this.reveal = reveal;
            this.rect = rect;
        }

        boolean isAoeTool() {
            return this == AOE_CIRCLE || this == AOE_RECT || this == AOE_BRUSH;
        }

        boolean isWallTool() {
            return this == WALL_DRAW || this == WALL_ERASE;
        }

        boolean isLightTool() {
            return this == LIGHT_ADD || this == LIGHT_REMOVE;
        }

        boolean isFogTool() {
            return this != SELECT && !isAoeTool() && !isWallTool() && !isLightTool();
        }
    }

    private static final class HistoryAction {
        private final String label;
        private final Runnable redo;
        private final Runnable undo;

        private HistoryAction(String label, Runnable redo, Runnable undo) {
            this.label = label;
            this.redo = redo;
            this.undo = undo;
        }
    }
}
