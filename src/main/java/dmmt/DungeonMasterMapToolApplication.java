package dmmt;

import dmmt.api.DmControlApi;
import dmmt.api.FxApiDispatcher;
import dmmt.api.LocalApiServer;
import dmmt.lighting.LightingEngine;
import dmmt.lighting.TimeOfDayPreset;
import dmmt.model.DmProject;
import dmmt.model.FogMask;
import dmmt.model.MultiLevelManifest;
import dmmt.render.CanvasMapRenderer;
import dmmt.render.FrameProfiler;
import dmmt.render.HistoryFeedback;
import dmmt.render.OverlayTextures;
import dmmt.render.PerformanceMode;
import dmmt.render.WeatherEffects;
import dmmt.render.WeatherType;
import dmmt.service.BatchImportService;
import dmmt.service.Dd2vttImportService;
import dmmt.service.DuplicateCheckService;
import dmmt.service.FogService;
import dmmt.service.MapRotationService;
import dmmt.service.MapLibraryService;
import dmmt.service.MapTagService;
import dmmt.service.MultiLevelService;
import dmmt.service.RoomFillService;
import dmmt.service.RecentMaps;
import dmmt.service.ProjectService;
import dmmt.service.Tuning;
import dmmt.ui.AudioControls;
import dmmt.ui.CollapsibleSection;
import dmmt.ui.ControlVisibility;
import dmmt.ui.Dialogs;
import dmmt.ui.DuplicateMapsDialog;
import dmmt.ui.HandoutWindow;
import dmmt.ui.Icons;
import dmmt.ui.LevelListDialog;
import dmmt.ui.MapBrowser;
import dmmt.ui.MapLocationDialog;
import dmmt.ui.MapTagsDialog;
import dmmt.ui.PlayerViewTransition;
import dmmt.ui.SettingsWindow;
import dmmt.render.TextBoxGeometry;
import dmmt.ui.TextBoxEditor;
import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
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
import javafx.scene.effect.BlendMode;
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
import org.kordamp.ikonli.materialdesign2.MaterialDesignG;
import org.kordamp.ikonli.materialdesign2.MaterialDesignI;
import org.kordamp.ikonli.materialdesign2.MaterialDesignL;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;
import org.kordamp.ikonli.materialdesign2.MaterialDesignT;
import org.kordamp.ikonli.materialdesign2.MaterialDesignU;
import org.kordamp.ikonli.materialdesign2.MaterialDesignV;
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
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import dmmt.render.ImagePyramidBuilder;
import dmmt.render.ImagePyramidStore;
import dmmt.service.AdjacentLevelPrefetch;
import dmmt.service.WorkScheduler;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import dmmt.service.AppSettings;

public class DungeonMasterMapToolApplication extends Application {
    private static final String PREF_LAST_IMPORT_DIRECTORY = "import.lastDirectory";
    private static final String PREF_PLAYER_SCREEN_INDEX = "player.screenIndex";
    private static final String PREF_SCREEN_DIAGONAL_PREFIX = "player.screenDiagonalInches.";
    private static final String PREF_TILE_INCHES = "player.tileInches";
    private static final String PREF_PLAYER_SHOW_GRID = "player.showGrid";
    private static final String PREF_FPS_TARGET = "render.targetFps";
    private static final String PREF_FPS_ANIMATION = "render.animationFps";
    private static final String PREF_FPS_IDLE = "render.idleFps";
    private static final int DEFAULT_FPS_TARGET = 60;
    private static final int DEFAULT_FPS_ANIMATION = 30;
    private static final int DEFAULT_FPS_IDLE = 10;
    private static final String PREF_SIDEBAR_VISIBLE = "ui.sidebarVisible";
    private static final String PREF_PERFORMANCE_MODE = "ui.performanceMode";
    private static final String PREF_LIGHT_FLICKER = "ui.lightFlicker";
    private static final String PREF_EFFECT_ANIMATIONS = "ui.effectAnimations";
    private static final String PREF_CONTROLS_EXPANDED = "ui.controlsExpanded";
    private static final String PREF_FOG_CELLS_PER_GRID = "fog.cellsPerGrid";
    private static final String PREF_FOG_SOFTNESS = "fog.softness";
    private static final String PREF_FOG_FADE = "fog.fadeAnimation";
    private static final String PREF_AUTOSAVE_ENABLED = "autosave.enabled";
    private static final String PREF_AUTOSAVE_MINUTES = "autosave.minutes";
    private static final String APP_ICON_RESOURCE = "/dmmt/icon.png";
    private static List<Image> appIcons;

    /** Loaded first: it applies the tuning values that the field initializers below already read. */
    private final AppSettings preferences = AppSettings.load();
    private final ProjectService projectService = new ProjectService();
    private LocalApiServer localApiServer;
    private DmControlApi controlApi;
    private boolean apiActionRunning;
    private volatile Path apiStateMapFile;
    private volatile String apiStateMapId = "";
    private final Map<String, javafx.scene.Node> extraApiControls = new java.util.LinkedHashMap<>();
    private final Dd2vttImportService dd2vttImportService = new Dd2vttImportService();
    /** Created once {@link #mapLibrary} exists, see {@link #start}. */
    private DuplicateCheckService duplicateCheckService;
    private final MapRotationService rotationService = new MapRotationService();
    private final LightingEngine lightingEngine = new LightingEngine();
    private final CanvasMapRenderer renderer = new CanvasMapRenderer(lightingEngine);

    private DmProject project;
    private Path projectFile;
    /** Manifest of the open multilevel map ({@code projectFile} is then the open level's file), else {@code null}. */
    private Path multiLevelFile;
    private MultiLevelManifest multiLevelManifest;
    private String currentLevelId;
    private HBox levelSwitcher;
    private ComboBox<MultiLevelManifest.Level> levelSelector;
    private Button levelDownButton;
    private Button levelUpButton;
    private Label levelPositionLabel;
    private boolean syncingLevelSelector;
    private final dmmt.ui.LevelPreviewCache levelPreviews = new dmmt.ui.LevelPreviewCache(projectService);
    private AdjacentLevelPrefetch<ImagePyramidStore.PreparedImages> adjacentPrefetch;
    private long prefetchGeneration;
    private boolean stopping;
    private final java.util.ArrayDeque<Path> pendingImageAdds = new java.util.ArrayDeque<>();
    private java.util.concurrent.Future<?> imageAddTask;
    private long imageAddGeneration;

    private Canvas dmBaseCanvas;
    private final CanvasMapRenderer.BaseLayerState dmBaseState = new CanvasMapRenderer.BaseLayerState();
    private Canvas playerBaseCanvas;
    private CanvasMapRenderer.BaseLayerState playerBaseState = new CanvasMapRenderer.BaseLayerState();
    private Canvas dmBrightCoreCanvas;
    private Canvas playerBrightCoreCanvas;
    private Canvas dmAmbientCanvas;
    private Canvas playerAmbientCanvas;
    private Canvas dmCanvas;
    private Canvas dmFogCanvas;
    private Canvas playerCanvas;
    private Canvas playerFogCanvas;
    private Stage playerStage;
    private PlayerViewTransition playerTransition;
    private Label statusLabel;
    private Label playerOutputLabel;
    private javafx.scene.control.Tooltip statusTooltip;
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
    private ToggleButton laserToggle;
    private ToggleButton wallLayerToggle;
    private ToggleButton imageLockToggle;
    private HBox imageUnlockBanner;
    private ToggleButton playerWindowToggle;
    private ToggleButton playerGridToggle;
    private Slider gridOpacitySlider;
    private HBox toolChip;
    private FontIcon toolChipIcon;
    private Label toolChipLabel;
    private boolean rightClickCancelCandidate;
    private double rightPressScreenX;
    private double rightPressScreenY;
    private final DoubleProperty brushSize = new SimpleDoubleProperty(Tuning.BRUSH_DEFAULT.get());
    private Label brushSizeLabel;
    private final PauseTransition brushSizeLabelTimeout = new PauseTransition(Duration.millis(900));
    private double brushLabelCursorX;
    private double brushLabelCursorY;
    private Spinner<Double> screenInchesSpinner;
    private Spinner<Double> tileInchesSpinner;
    private boolean showPlayerGrid = preferences.getBoolean(PREF_PLAYER_SHOW_GRID, false);
    private boolean showScaleTestSquare;
    private HandoutWindow handoutWindow;
    private DmProject.WallSegment draftWall;
    private boolean snapLayersToGrid;
    private volatile boolean ioBusy;
    private StackPane mapCenter;
    private Region dmControls;
    private AudioControls audioControls;
    private final java.util.Map<String, CollapsibleSection> dmSections = new java.util.LinkedHashMap<>();
    private final ControlVisibility dmControlVisibility = new ControlVisibility();
    private StackPane loadingOverlay;
    private String savedFingerprint;
    private long historyVersion;
    private long lastAutoSaveNanos = System.nanoTime();
    private long lastInputNanos = System.nanoTime();
    private volatile int targetFps = DEFAULT_FPS_TARGET;
    private volatile int animationFps = DEFAULT_FPS_ANIMATION;
    private volatile int idleFps = DEFAULT_FPS_IDLE;
    private long lastFrameNanos;
    private Label metricsLabel;
    private javafx.scene.control.Tooltip metricsTooltip;
    private javafx.stage.Popup diagnosticsPopup;
    private ToggleButton diagnosticsToggle;
    private final Map<String, Long> metricsSectionNanos = new java.util.HashMap<>();
    private long metricsWindowStart;
    private int metricsFrames;
    private long metricsNanosSum;
    private long metricsNanosMax;
    private DmProject.LightSource selectedLight;
    private EditorTool activeTool = EditorTool.SELECT;
    private boolean fogDragging;
    private double fogDragStartWorldX;
    private double fogDragStartWorldY;
    private double fogLastWorldX;
    private double fogLastWorldY;
    private double fogCurrentWorldX;
    private double fogCurrentWorldY;
    private FogMask.Snapshot fogBeforeSnapshot;
    private FogMask.Snapshot lightDragFogBefore;
    private BitSet roomBarrier;
    private long roomBarrierSignature;
    private RoomFillService.Result roomPreview;
    private boolean roomHidePreview;
    private boolean hoverInsideCanvas;
    private boolean geometryPreviewHover;
    private String lastDoorToggleId;
    private long lastDoorToggleNanos;
    private double hoverWorldX;
    private double hoverWorldY;
    private boolean syncingControls;
    private ContextMenu activeLightMenu;
    private String overlayColor = Tuning.EFFECT_DEFAULT_COLOR.get();
    private double overlayAlpha = Tuning.EFFECT_DEFAULT_OPACITY.get();
    private boolean overlayPlayerVisible = true;
    private String overlayTexture = OverlayTextures.NONE;
    private ComboBox<String> overlayTextureBox;
    private boolean overlayBorder;
    private ToggleButton overlayBorderToggle;
    private boolean overlayEmitsLight;
    private ToggleButton overlayLightToggle;
    private String selectedOverlayId;
    private DmProject.OverlayShape draftOverlay;
    private double overlayStartX;
    private double overlayStartY;
    private boolean draggingOverlay;
    private boolean resizingOverlay;
    private DmProject.OverlayShape overlayDragBefore;
    private DmProject.OverlayShape overlayStyleBefore;
    private double overlayLastX;
    private double overlayLastY;
    private ColorPicker overlayColorPicker;
    private Slider overlayAlphaSlider;
    private ToggleButton overlayPlayerToggle;
    private static final String PREF_TEXT_FONT_SIZE = "text.fontSize";
    private static final String PREF_TEXT_COLOR = "text.textColor";
    private static final String PREF_TEXT_BACKGROUND = "text.backgroundColor";
    private static final String PREF_TEXT_BORDER = "text.borderColor";
    private static final String PREF_TEXT_ROTATION = "text.rotation";
    private static final int TEXT_HANDLE_COUNT = 8;
    private TextBoxEditor textEditor;
    private String selectedTextId;
    private String editingTextId;
    private boolean editingTextIsNew;
    private DmProject.TextBox editingTextBefore;
    private DmProject.TextBox draftText;
    private double textStartX;
    private double textStartY;
    private boolean draggingText;
    private int resizingTextHandle = -1;
    private DmProject.TextBox textDragBefore;
    private double textLastX;
    private double textLastY;
    private CopiedItems CopiedItems;
    private Spinner<Integer> textSizeSpinner;
    private ColorPicker textColorPicker;
    private ColorPicker textBackgroundPicker;
    private ColorPicker textBorderPicker;
    private ToggleButton textLayerToggle;
    private ToggleButton effectAnimationsToggle;
    private ToggleButton lightFlickerToggle;
    private ToggleButton textPlayerToggle;
    private ToggleButton textAutoSizeToggle;
    private Button removeLightButton;
    private final java.util.List<Button> lightRevealButtons = new java.util.ArrayList<>();
    private Button deleteTextButton;
    private Button deleteEffectButton;
    private ToggleButton fogToggleButton;
    private Slider ambientBrightnessSlider;
    private Slider playerZoomSlider;
    private Label playerZoomValue;
    private Label ambientBrightnessValue;
    /** Last committed ambient brightness of the current preset; the "before" value for undo. */
    private double ambientBrightnessCommitted;
    private ComboBox<WeatherType> weatherBox;
    private Slider weatherIntensitySlider;
    private Label weatherIntensityValue;
    private double weatherIntensityCommitted = WeatherEffects.defaultIntensity();
    private Slider lightningIntervalSlider;
    private Label lightningIntervalValue;
    private double lightningIntervalCommitted = Tuning.WEATHER_LIGHTNING_INTERVAL.get();
    private Slider lightTintSlider;
    private Label lightTintValue;
    /** Last committed light tint of the current map; the "before" value for undo. */
    private double lightTintCommitted = CanvasMapRenderer.DEFAULT_LIGHT_TINT;
    private Slider brightCoreSlider;
    private Label brightCoreValue;
    /** Last committed bright core strength of the current map; the "before" value for undo. */
    private double brightCoreCommitted = CanvasMapRenderer.DEFAULT_BRIGHT_CORE;
    private ToggleButton freezePlayerButton;
    private final Map<TimeOfDayPreset, ToggleButton> timeButtons = new EnumMap<>(TimeOfDayPreset.class);
    private final Map<EditorTool, ToggleButton> toolButtons = new EnumMap<>(EditorTool.class);

    /** Multi-selection: keys like "light:id", "text:id", "overlay:id", "layer:id". Replaces the single selection while non-empty. */
    private final java.util.Set<String> groupKeys = new java.util.LinkedHashSet<>();
    private boolean draggingGroup;
    private double groupLastX;
    private double groupLastY;
    private Map<String, double[]> groupStartPositions;
    private FogMask.Snapshot groupFogBefore;
    private final java.util.Set<KeyCode> nudgeArrowKeys = java.util.EnumSet.noneOf(KeyCode.class);
    private Map<String, double[]> nudgeStartPositions;
    private FogMask.Snapshot nudgeFogBefore;
    private boolean canvasMouseDown;
    private boolean marqueeActive;
    private double marqueeStartX;
    private double marqueeStartY;
    private double marqueeCurrentX;
    private double marqueeCurrentY;

    private DmProject.ImageLayer selectedLayer;
    private boolean draggingLayer;
    private boolean resizingLayer;
    private boolean draggingLight;
    private boolean panningDmCamera;
    private boolean draggingPlayerViewport;
    private boolean pingArmed;
    private final java.util.ArrayList<CanvasMapRenderer.LaserPoint> laserTrail = new java.util.ArrayList<>();
    private boolean laserActive;
    private boolean laserToolActive;
    private double dragOffsetX;
    private double dragOffsetY;
    private double lastMouseX;
    private double lastMouseY;
    private double viewportDragOffsetX;
    private double viewportDragOffsetY;
    private double viewportDragSceneX;
    private double viewportDragSceneY;
    private long viewportScrollNanos;
    private javafx.scene.control.Tooltip viewportTitleTooltip;
    private boolean viewportTitleTooltipInstalled;
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
    private final HistoryFeedback historyFeedback = new HistoryFeedback();

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
        CanvasMapRenderer.setFogSoftness(preferences.getDouble(PREF_FOG_SOFTNESS, CanvasMapRenderer.DEFAULT_FOG_SOFTNESS));
        CanvasMapRenderer.setFogFadeEnabled(preferences.getBoolean(PREF_FOG_FADE, true));
        CanvasMapRenderer.setTextQuarterTurns(Math.floorDiv(preferences.getInt(PREF_TEXT_ROTATION, 0), 90));
        targetFps = clampFps(preferences.getInt(PREF_FPS_TARGET, DEFAULT_FPS_TARGET));
        animationFps = clampFps(preferences.getInt(PREF_FPS_ANIMATION, DEFAULT_FPS_ANIMATION));
        CanvasMapRenderer.setAnimationFps(animationFps);
        idleFps = clampFps(preferences.getInt(PREF_FPS_IDLE, DEFAULT_FPS_IDLE));
        this.project = DmProject.builder().build();
        this.project.getMap().setSourceType("custom");
        Path libraryRoot = resolveProjectsRoot();
        mapLibrary = new MapLibraryService(libraryRoot, projectService);
        duplicateCheckService = new DuplicateCheckService(mapLibrary, projectService);

        BorderPane root = new BorderPane();
        root.getStyleClass().add("app-root");

        dmBaseCanvas = new Canvas(1280, 800);
        dmBaseCanvas.setMouseTransparent(true);
        dmBrightCoreCanvas = new Canvas(1280, 800);
        dmBrightCoreCanvas.setMouseTransparent(true);
        dmBrightCoreCanvas.setBlendMode(BlendMode.ADD);
        dmAmbientCanvas = new Canvas(1280, 800);
        dmAmbientCanvas.setMouseTransparent(true);
        dmAmbientCanvas.setBlendMode(BlendMode.MULTIPLY);
        dmCanvas = new Canvas(1280, 800);
        dmFogCanvas = new Canvas(1280, 800);
        dmFogCanvas.setMouseTransparent(true);
        textEditor = new TextBoxEditor();
        initTextEditor();
        StackPane center = new StackPane(dmBaseCanvas, dmBrightCoreCanvas, dmAmbientCanvas, dmCanvas, dmFogCanvas, textEditor.node());
        center.setMinSize(0, 0);
        mapCenter = center;
        Region controls = createControlsPanel(stage);
        dmControls = controls;
        StackPane.setAlignment(controls, Pos.TOP_RIGHT);
        StackPane.setMargin(controls, new Insets(10));
        HBox chip = createToolChip();
        StackPane.setAlignment(chip, Pos.TOP_CENTER);
        StackPane.setMargin(chip, new Insets(12, 0, 0, 0));
        imageUnlockBanner = createImageUnlockBanner();
        StackPane.setAlignment(imageUnlockBanner, Pos.TOP_CENTER);
        StackPane.setMargin(imageUnlockBanner, new Insets(58, 0, 0, 0));
        levelSwitcher = createLevelSwitcher();
        StackPane.setAlignment(levelSwitcher, Pos.TOP_LEFT);
        StackPane.setMargin(levelSwitcher, new Insets(10));
        center.getChildren().addAll(chip, imageUnlockBanner, levelSwitcher, controls);
        center.getChildren().add(createBrushSizeLabel());
        if (audioControls != null && Tuning.AUDIO_ENABLED.get()) {
            center.getChildren().add(audioControls.overlayLayer());
        }
        dmBaseCanvas.widthProperty().bind(center.widthProperty());
        dmBaseCanvas.heightProperty().bind(center.heightProperty());
        dmBrightCoreCanvas.widthProperty().bind(center.widthProperty());
        dmBrightCoreCanvas.heightProperty().bind(center.heightProperty());
        dmAmbientCanvas.widthProperty().bind(center.widthProperty());
        dmAmbientCanvas.heightProperty().bind(center.heightProperty());
        dmCanvas.widthProperty().bind(center.widthProperty());
        dmCanvas.heightProperty().bind(center.heightProperty());
        dmCanvas.widthProperty().addListener((obs, oldValue, newValue) -> positionBrushSizeLabel());
        dmCanvas.heightProperty().addListener((obs, oldValue, newValue) -> positionBrushSizeLabel());
        dmFogCanvas.widthProperty().bind(center.widthProperty());
        dmFogCanvas.heightProperty().bind(center.heightProperty());
        root.setCenter(center);

        mapBrowser = new MapBrowser(mapLibrary, createBrowserHost(), new RecentMaps(preferences));
        mapBrowser.addAction(createAutoSaveMenu());
        root.setLeft(mapBrowser);

        statusLabel = new Label("Ready");
        statusLabel.setMinWidth(0);
        statusLabel.setMaxWidth(Double.MAX_VALUE);
        statusLabel.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        statusLabel.getStyleClass().add("status-message");
        statusTooltip = Icons.tooltip("Current application activity");
        statusLabel.setTooltip(statusTooltip);
        ToggleButton sidebarToggle = Icons.toggle(MaterialDesignD.DOCK_LEFT, "Show / hide the map library");
        sidebarToggle.setSelected(preferences.getBoolean(PREF_SIDEBAR_VISIBLE, true));
        sidebarToggle.selectedProperty().addListener((obs, was, visible) -> {
            root.setLeft(visible ? mapBrowser : null);
            preferences.putBoolean(PREF_SIDEBAR_VISIBLE, visible);
        });
        if (!sidebarToggle.isSelected()) {
            root.setLeft(null);
        }
        ToggleButton performanceToggle = Icons.toggle(MaterialDesignS.SPEEDOMETER,
                "Performance mode — temporarily lowers shadow quality, map image detail and soft edges, freezes animations and flicker, "
                        + "and limits FPS. Your settings and maps are not changed.");
        performanceToggle.setSelected(preferences.getBoolean(PREF_PERFORMANCE_MODE, false));
        PerformanceMode.setEnabled(performanceToggle.isSelected());
        performanceToggle.selectedProperty().addListener((obs, was, on) -> {
            PerformanceMode.setEnabled(on);
            preferences.putBoolean(PREF_PERFORMANCE_MODE, on);
            status(on ? "Performance mode on." : "Performance mode off.");
        });
        metricsLabel = new Label("Waiting for the first frames...");
        metricsLabel.setWrapText(true);
        metricsLabel.setMaxWidth(460);
        metricsLabel.getStyleClass().add("diagnostics-metrics");
        metricsTooltip = Icons.tooltip("Waiting for the first frames...");
        javafx.scene.control.Tooltip.install(metricsLabel, metricsTooltip);
        Label diagnosticsTitle = new Label("Rendering diagnostics");
        diagnosticsTitle.getStyleClass().add("diagnostics-title");
        VBox diagnosticsContent = new VBox(6, diagnosticsTitle, metricsLabel);
        diagnosticsContent.getStyleClass().add("diagnostics-popup");
        diagnosticsContent.getStylesheets().add(Icons.STYLESHEET);
        diagnosticsPopup = new javafx.stage.Popup();
        diagnosticsPopup.getContent().add(diagnosticsContent);
        diagnosticsPopup.setAutoHide(false);
        diagnosticsPopup.setHideOnEscape(false);
        diagnosticsToggle = Icons.toggle(MaterialDesignC.CHART_BOX_OUTLINE,
                "Show rendering diagnostics (FPS, frame timing and memory)");
        diagnosticsToggle.setOnAction(event -> {
            if (diagnosticsToggle.isSelected()) {
                javafx.geometry.Bounds anchor = diagnosticsToggle.localToScreen(diagnosticsToggle.getBoundsInLocal());
                if (anchor == null) {
                    diagnosticsToggle.setSelected(false);
                    return;
                }
                diagnosticsPopup.show(diagnosticsToggle, anchor.getMinX(), anchor.getMinY());
                Platform.runLater(() -> {
                    if (!diagnosticsPopup.isShowing()) {
                        return;
                    }
                    javafx.geometry.Rectangle2D screen = javafx.stage.Screen.getScreensForRectangle(
                                    anchor.getMinX(), anchor.getMinY(), 1, 1).stream()
                            .findFirst().orElse(javafx.stage.Screen.getPrimary()).getVisualBounds();
                    double x = Math.max(screen.getMinX(),
                            Math.min(anchor.getMinX(), screen.getMaxX() - diagnosticsPopup.getWidth()));
                    double y = Math.max(screen.getMinY(), anchor.getMinY() - diagnosticsPopup.getHeight() - 8);
                    diagnosticsPopup.setX(x);
                    diagnosticsPopup.setY(y);
                });
            } else {
                diagnosticsPopup.hide();
            }
        });
        diagnosticsPopup.setOnHidden(event -> diagnosticsToggle.setSelected(false));
        playerOutputLabel = new Label();
        playerOutputLabel.setMinWidth(Region.USE_PREF_SIZE);
        playerOutputLabel.getStyleClass().add("player-output-status");
        updatePlayerOutputStatus();
        Label activityHeading = new Label("Activity");
        activityHeading.getStyleClass().add("status-heading");
        Button settingsButton = Icons.button(MaterialDesignC.COG_OUTLINE, "Settings", this::openSettings);
        extraApiControls.put("ui.library", sidebarToggle);
        extraApiControls.put("ui.performance", performanceToggle);
        extraApiControls.put("ui.diagnostics", diagnosticsToggle);
        extraApiControls.put("ui.settings", settingsButton);
        extraApiControls.put("maps.previous", mapBrowser.previousMapButton());
        controlApi = new DmControlApi(dmControlVisibility);
        extraApiControls.forEach(controlApi::add);
        controlApi.attachUrlMenus(this::localApiBaseUrl, this::status);
        mapBrowser.setApiUrlProvider(this::mapApiUrl);
        applyApiUrlOptionVisibility();
        HBox statusBar = new HBox(sidebarToggle, performanceToggle, diagnosticsToggle, settingsButton, activityHeading,
                statusLabel, playerOutputLabel);
        HBox.setHgrow(statusLabel, Priority.ALWAYS);
        if (audioControls != null && Tuning.AUDIO_ENABLED.get()) {
            HBox audioStatus = new HBox(audioControls.statusBarGroup());
            audioStatus.getStyleClass().add("status-audio-section");
            statusBar.getChildren().add(audioStatus);
            registerAudioApiControls();
        }
        statusBar.getStyleClass().add("status-bar");
        root.setBottom(statusBar);

        installDmInteractions();

        // Clamp to the usable screen area (minus room for window decorations) so small displays never get an oversized window.
        javafx.geometry.Rectangle2D screenArea = javafx.stage.Screen.getPrimary().getVisualBounds();
        double initialWidth = Math.min(Tuning.DM_WINDOW_WIDTH.get(), screenArea.getWidth() - 16);
        double initialHeight = Math.min(Tuning.DM_WINDOW_HEIGHT.get(), screenArea.getHeight() - 40);
        Scene scene = new Scene(root, initialWidth, initialHeight, Color.BLACK);
        scene.getStylesheets().add(Icons.STYLESHEET);
        // Clicks inside the popup never reach this scene, so any click here is "outside" the menu.
        scene.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> {
            finishNudge();
            hideLightMenu();
        });
        scene.setOnKeyPressed(event -> {
            if (event.isControlDown() && event.getCode() == KeyCode.S) {
                handleSave();
                event.consume();
                return;
            }
            if (textEditor.isFocused()) {
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
            if (laserToolActive && event.getCode() != KeyCode.ESCAPE && event.getCode() != KeyCode.P
                    && event.getCode() != KeyCode.L) {
                return;
            }
            if ((event.getCode() == KeyCode.PAGE_UP || event.getCode() == KeyCode.PAGE_DOWN) && multiLevelFile != null) {
                stepLevel(event.getCode() == KeyCode.PAGE_UP ? 1 : -1);
                event.consume();
                return;
            }
            if (event.isControlDown() && event.getCode() == KeyCode.C) {
                if (copySelection()) {
                    event.consume();
                }
                return;
            }
            if (event.isControlDown() && event.getCode() == KeyCode.V) {
                pasteSelection();
                event.consume();
                return;
            }
            if (event.getCode() == KeyCode.P) {
                setPingArmed(true);
                return;
            }
            if (event.getCode() == KeyCode.M && !event.isControlDown() && !event.isAltDown()
                    && !event.isShiftDown() && !event.isMetaDown()) {
                if (audioControls != null && Tuning.AUDIO_ENABLED.get()) {
                    audioControls.toggleOverlay();
                    event.consume();
                }
                return;
            }
            if (event.getCode() == KeyCode.L && !event.isControlDown() && !event.isAltDown() && !event.isMetaDown()) {
                setLaserToolActive(!laserToolActive);
                event.consume();
                return;
            }
            if (event.getCode() == KeyCode.ESCAPE) {
                if (audioControls != null && audioControls.closeOverlay()) {
                    event.consume();
                    return;
                }
                cancelActiveTool();
            }
            if (event.getCode() == KeyCode.DELETE || event.getCode() == KeyCode.BACK_SPACE) {
                finishNudge();
                pruneGroup();
                if (!groupKeys.isEmpty()) {
                    deleteGroup();
                } else if (findTextBox(selectedTextId) != null) {
                    deleteSelectedText();
                } else if (findOverlay(selectedOverlayId) != null) {
                    deleteSelectedOverlay();
                } else if (selectedLight != null && findLightById(selectedLight.getId()) != null) {
                    removeLight(selectedLight.getId());
                } else if (selectedLayer != null && !isImageLayerLocked()) {
                    deleteSelectedLayer();
                }
                event.consume();
            }
        });
        stage.setScene(scene);
        stage.getIcons().setAll(appIcons());
        stage.setOnCloseRequest(event -> {
            saveOnExit();
            if (diagnosticsPopup != null) {
                diagnosticsPopup.hide();
            }
            if (handoutWindow != null) {
                handoutWindow.close();
            }
            if (audioControls != null) {
                audioControls.shutdown();
            }
            closePlayerWindow();
            Platform.exit();
        });
        updateWindowTitle();
        stage.show();
        applyLocalApiSettings();

        stage.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                roomHidePreview = false;
                finishNudge();
                finishPlayerViewportDrag();
                autoSaveIfDirty();
            } else if (preferences.pollExternalChange()) {
                // The reload already applied tuning values and texture settings edited by hand.
                applySectionVisibility();
                applyPlayerGridSetting();
                applyApiUrlOptionVisibility();
                if (audioControls != null) {
                    audioControls.applySettings();
                }
                status("Settings reloaded from " + preferences.getFile().getFileName()
                        + " (entries marked 'Restart required' apply after a restart)");
                applyLocalApiSettings();
            }
        });
        javafx.animation.Timeline autoSaveTicker = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.seconds(Tuning.AUTOSAVE_CHECK_SECONDS.get()), e -> autoSaveTick()));
        autoSaveTicker.setCycleCount(javafx.animation.Animation.INDEFINITE);
        autoSaveTicker.play();

        scene.addEventFilter(javafx.scene.input.InputEvent.ANY, e -> {
            // In performance mode plain hovering with the select tool is not interaction and must not raise the frame rate.
            boolean hoverOnly = e instanceof javafx.scene.input.MouseEvent me && !me.isPrimaryButtonDown()
                    && !me.isSecondaryButtonDown() && !me.isMiddleButtonDown()
                    && (me.getEventType() == javafx.scene.input.MouseEvent.MOUSE_MOVED
                    || me.getEventType() == javafx.scene.input.MouseEvent.MOUSE_ENTERED
                    || me.getEventType() == javafx.scene.input.MouseEvent.MOUSE_EXITED
                    || me.getEventType() == javafx.scene.input.MouseEvent.MOUSE_ENTERED_TARGET
                    || me.getEventType() == javafx.scene.input.MouseEvent.MOUSE_EXITED_TARGET);
            if (hoverOnly && PerformanceMode.isEnabled() && activeTool == EditorTool.SELECT && !pingArmed && !laserToolActive) {
                return;
            }
            lastInputNanos = System.nanoTime();
        });
        AnimationTimer timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                boolean viewportScrolling = playerViewportScrollVelocity().magnitude() > 0;
                if (!viewportScrolling) {
                    viewportScrollNanos = 0;
                }
                // Frame rates are configurable: target while interacting, animation for moving effects, idle otherwise.
                boolean fogFading = renderer.isFogAnimating() || playerRenderer.isFogAnimating();
                boolean animated = hasAnimation(project)
                        || (frozenPlayerProject != null && hasAnimation(frozenPlayerProject));
                boolean recentInput = now - lastInputNanos < Tuning.RECENT_INPUT_MS.get() * 1_000_000L || laserActive || laserToolActive || !laserTrail.isEmpty()
                        || fogFading || historyFeedback.active(now) || viewportScrolling;
                int fps = PerformanceMode.isEnabled()
                        ? recentInput ? Math.min(PerformanceMode.interactionFps(), targetFps)
                        : animated ? PerformanceMode.textureAnimationFps() : Math.min(PerformanceMode.idleFps(), idleFps)
                        : recentInput ? targetFps : animated ? Math.min(animationFps, targetFps) : idleFps;
                // 10% slack so vsync jitter does not push a frame to the next tick.
                long minInterval = (long) (900_000_000L / fps);
                if (now - lastFrameNanos < minInterval) {
                    return;
                }
                lastFrameNanos = now;
                long frameStart = System.nanoTime();
                long section = FrameProfiler.start();
                lightingEngine.update(project);
                applyPlayerScale();
                updatePlayerViewportDrag(now);
                if (frozenPlayerProject != null) {
                    playerLightingEngine.update(frozenPlayerProject);
                }
                FrameProfiler.lap("light engine", section);
                renderDm();
                section = FrameProfiler.start();
                renderPlayer();
                FrameProfiler.lap("player total", section);
                recordFrame(System.nanoTime() - frameStart, now, fps);
            }
        };
        timer.start();
    }

    /** Collects per-frame render times and refreshes the status bar readout about once a second. */
    private void recordFrame(long frameNanos, long nowNanos, int targetFramesPerSecond) {
        if (metricsWindowStart == 0) {
            metricsWindowStart = nowNanos;
        }
        metricsFrames++;
        metricsNanosSum += frameNanos;
        metricsNanosMax = Math.max(metricsNanosMax, frameNanos);
        FrameProfiler.drain().forEach((name, nanos) -> metricsSectionNanos.merge(name, nanos, Long::sum));
        long elapsed = nowNanos - metricsWindowStart;
        if (elapsed < 1_000_000_000L) {
            return;
        }
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long allocatedMb = rt.totalMemory() / (1024 * 1024);
        double frames = metricsFrames;
        List<Map.Entry<String, Long>> sections = metricsSectionNanos.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()).toList();
        StringBuilder top = new StringBuilder();
        StringBuilder detail = new StringBuilder("Average time per frame by part (ms):");
        for (int i = 0; i < sections.size(); i++) {
            Map.Entry<String, Long> entry = sections.get(i);
            String ms = String.format(Locale.ROOT, "%.1f", entry.getValue() / 1e6 / frames);
            detail.append("\n").append(entry.getKey()).append(": ").append(ms);
            if (i < 2 && !entry.getKey().equals("player total")) {
                top.append(top.length() == 0 ? "" : ", ").append(entry.getKey()).append(' ').append(ms);
            }
        }
        detail.append("\n\n'player total' includes the player parts listed above it. 'base redraw' only appears when the map image layer had to be redrawn.");
        metricsLabel.setText(String.format(Locale.ROOT, "%.0f/%d fps | %.1f ms average (max %.1f) | %d/%d MB | %s",
                metricsFrames * 1e9 / elapsed, targetFramesPerSecond,
                        metricsNanosSum / 1e6 / metricsFrames, metricsNanosMax / 1e6, usedMb, allocatedMb, top));
        metricsTooltip.setText("Frames per second (actual/limit) | average and worst frame time | heap in use/allocated | slowest parts\n\n" + detail);
        metricsSectionNanos.clear();
        metricsWindowStart = nowNanos;
        metricsFrames = 0;
        metricsNanosSum = 0;
        metricsNanosMax = 0;
    }

    // ---- DM controls panel (right side) ----

    private Region createControlsPanel(Stage stage) {
        brushSize.set(clamp(brushSize.get(), Math.min(Tuning.BRUSH_MIN.get(), Tuning.BRUSH_MAX.get()),
                Math.max(Tuning.BRUSH_MIN.get(), Tuning.BRUSH_MAX.get())));
        ToggleGroup toolGroup = new ToggleGroup();
        for (EditorTool tool : EditorTool.values()) {
            ToggleButton button = Icons.toggle(tool.icon, tool.label + " — " + tool.description());
            button.setToggleGroup(toolGroup);
            button.setOnAction(e -> setActiveTool(button.isSelected() ? tool : EditorTool.SELECT));
            toolButtons.put(tool, button);
        }

        // Tools
        pingToggle = Icons.toggle(MaterialDesignC.CROSSHAIRS_GPS, "Ping (P) — click the map to flash a marker for the players");
        pingToggle.setOnAction(e -> setPingArmed(pingToggle.isSelected()));
        laserToggle = Icons.toggle(MaterialDesignL.LASER_POINTER,
                "Laser pointer (L) — shows a laser dot to the players that follows your cursor until you leave the tool. "
                        + "Holding the middle mouse button does the same briefly with any tool. No other interaction while it is active");
        laserToggle.setOnAction(e -> setLaserToolActive(laserToggle.isSelected()));
        wallLayerToggle = Icons.toggle(MaterialDesignL.LAYERS_OUTLINE,
                "Show / hide the wall layer (wall lines, doors and windows) in the DM view — lights stay visible");
        wallLayerToggle.setSelected(renderer.isWallLayerVisible());
        wallLayerToggle.setOnAction(e -> setWallLayerVisible(wallLayerToggle.isSelected()));
        Region toolSpacer = new Region();
        HBox.setHgrow(toolSpacer, Priority.ALWAYS);
        HBox toolsRow = row(toolButtons.get(EditorTool.SELECT), pingToggle, laserToggle, toolSpacer,
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
                toolButtons.get(EditorTool.REVEAL_ROOM),
                Icons.separator(),
                brushSlider());
        HBox fogSharpnessRow = fogSharpnessSlider();
        HBox fogEffectsRow = fogEffectsRow();

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
        removeLightButton = Icons.button(MaterialDesignL.LIGHTBULB_OFF_OUTLINE,
                "Remove the selected lights (Del) — select lights first by clicking one or dragging a box around them",
                this::removeSelectedLights);
        removeLightButton.setDisable(true);
        lightFlickerToggle = Icons.toggle(MaterialDesignP.PLAY_CIRCLE_OUTLINE,
                "Light flicker for all maps. Off = no light flickers; on = every light flickers according to its own flicker setting. "
                        + "Independent of the effect animations toggle; always off in performance mode");
        lightFlickerToggle.setSelected(preferences.getBoolean(PREF_LIGHT_FLICKER, true));
        CanvasMapRenderer.setLightFlickerEnabled(lightFlickerToggle.isSelected());
        lightFlickerToggle.setOnAction(e -> {
            boolean on = lightFlickerToggle.isSelected();
            CanvasMapRenderer.setLightFlickerEnabled(on);
            preferences.putBoolean(PREF_LIGHT_FLICKER, on);
            status(on ? "Light flicker on." : "Light flicker off.");
            renderDm();
            renderPlayer();
        });
        Label revealLabel = new Label("Fog reveal");
        revealLabel.getStyleClass().add("muted");
        HBox revealRow = row(revealLabel,
                lightRevealButton(MaterialDesignM.MAP_CHECK_OUTLINE, "Keep revealed", DmProject.RevealMode.PERSISTENT,
                        "the fog they uncover stays revealed"),
                lightRevealButton(MaterialDesignM.MAP_MARKER_RADIUS_OUTLINE, "Only while lit", DmProject.RevealMode.WHILE_LIT,
                        "they reveal the fog only while they are on and lighting it"),
                lightRevealButton(MaterialDesignM.MAP_MARKER_OFF_OUTLINE, "Don't reveal", DmProject.RevealMode.NONE,
                        "they do not reveal any fog"));
        HBox lightRow = row(
                toolButtons.get(EditorTool.LIGHT_ADD),
                toolButtons.get(EditorTool.LIGHT_CANDLE),
                toolButtons.get(EditorTool.LIGHT_CAMPFIRE),
                toolButtons.get(EditorTool.LIGHT_MAGIC),
                Icons.separator(),
                removeLightButton,
                Icons.separator(),
                lightFlickerToggle);
        HBox timeRow = row(timeSegment, Icons.separator(),
                lightPowerButton(MaterialDesignL.LIGHTBULB_ON_OUTLINE, "Turn on the selected lights", true),
                lightPowerButton(MaterialDesignL.LIGHTBULB_OUTLINE, "Turn off the selected lights", false));
        Label lightHint = new Label("Right-click a light for range, color, flicker and on/off.");
        lightHint.getStyleClass().add("muted");
        lightHint.setWrapText(true);
        // Without a fixed pref width the label reports its single-line height, so the panel sizes
        // itself too short once the text wraps and shows a needless scrollbar.
        lightHint.setPrefWidth(220);
        lightHint.setMinHeight(Region.USE_PREF_SIZE);
        HBox lightTintRow = lightTintSlider();
        HBox brightCoreRow = brightCoreSlider();
        HBox ambientBrightnessRow = ambientBrightnessSlider();

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
        overlayAlphaSlider = new Slider(0.1, 1.0, overlayAlpha);
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
        deleteEffectButton = Icons.button(MaterialDesignD.DELETE_OUTLINE, "Delete the selected effect (Del)",
                this::deleteSelectedOverlay);
        Button clearEffects = Icons.button(MaterialDesignD.DELETE_SWEEP_OUTLINE, "Remove all effects", this::clearOverlays);
        clearEffects.getStyleClass().add("danger");
        Region effectSpacer = new Region();
        HBox.setHgrow(effectSpacer, Priority.ALWAYS);
        HBox effectToolsRow = row(toolButtons.get(EditorTool.AOE_CIRCLE), toolButtons.get(EditorTool.AOE_RECT),
                toolButtons.get(EditorTool.AOE_BRUSH), toolButtons.get(EditorTool.AOE_PEN), toolButtons.get(EditorTool.AOE_LINE), effectSpacer, deleteEffectButton, clearEffects);
        HBox effectStyleRow = row(overlayColorPicker, overlayAlphaSlider, overlayPlayerToggle);
        overlayTextureBox = new ComboBox<>();
        overlayTextureBox.getItems().addAll(OverlayTextures.KINDS);
        overlayTextureBox.setValue(overlayTexture);
        overlayTextureBox.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(String kind) {
                return kind == null ? "" : OverlayTextures.label(kind);
            }

            @Override
            public String fromString(String text) {
                return OverlayTextures.NONE;
            }
        });
        Icons.tooltip(overlayTextureBox, "Effect texture (also changes the selected effect). Picking one loads its default color and opacity (smoke grey, fire red, water blue, ...); adjust both afterwards.");
        overlayTextureBox.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(overlayTextureBox, Priority.ALWAYS);
        overlayTextureBox.setOnAction(e -> {
            if (syncingControls || overlayTextureBox.getValue() == null) {
                return;
            }
            overlayTexture = overlayTextureBox.getValue();
            String textureColor = OverlayTextures.defaultColor(overlayTexture);
            double textureAlpha = OverlayTextures.defaultAlpha(overlayTexture);
            boolean textureLight = OverlayTextures.defaultEmitsLight(overlayTexture);
            syncingControls = true;
            try {
                overlayEmitsLight = textureLight;
                overlayLightToggle.setSelected(textureLight);
                if (textureColor != null) {
                    overlayColor = textureColor;
                    overlayColorPicker.setValue(Color.web(textureColor));
                }
                if (textureAlpha > 0) {
                    overlayAlpha = textureAlpha;
                    overlayAlphaSlider.setValue(textureAlpha);
                }
            } finally {
                syncingControls = false;
            }
            DmProject.OverlayShape selected = findOverlay(selectedOverlayId);
            if (selected != null) {
                executeOverlayChange("Change effect texture", selected.getId(), s -> {
                    s.setTexture(overlayTexture);
                    s.setEmitsLight(textureLight);
                    if (textureColor != null) {
                        s.setColor(textureColor);
                    }
                    if (textureAlpha > 0) {
                        s.setAlpha(textureAlpha);
                    }
                });
            }
        });
        overlayBorderToggle = Icons.toggle(MaterialDesignS.SQUARE_OUTLINE,
                "Border around textured effects (off by default) — also changes the selected effect");
        overlayBorderToggle.setSelected(overlayBorder);
        overlayBorderToggle.setOnAction(e -> {
            if (syncingControls) {
                return;
            }
            overlayBorder = overlayBorderToggle.isSelected();
            DmProject.OverlayShape selected = findOverlay(selectedOverlayId);
            if (selected != null) {
                executeOverlayChange("Change effect border", selected.getId(), s -> s.setBorder(overlayBorder));
            }
        });
        overlayLightToggle = Icons.toggle(MaterialDesignL.LIGHTBULB_ON_OUTLINE,
                "Effect emits light in its color (visible when the map is dark) — also changes the selected effect. Works for every texture; picking a texture applies its default from the settings file");
        overlayLightToggle.setSelected(overlayEmitsLight);
        overlayLightToggle.setOnAction(e -> {
            if (syncingControls) {
                return;
            }
            overlayEmitsLight = overlayLightToggle.isSelected();
            DmProject.OverlayShape selected = findOverlay(selectedOverlayId);
            if (selected != null) {
                executeOverlayChange("Change effect light", selected.getId(), s -> s.setEmitsLight(overlayEmitsLight));
            }
        });
        effectAnimationsToggle = Icons.toggle(MaterialDesignP.PLAY_CIRCLE_OUTLINE,
                "Animate effect textures and weather on all maps (light flicker has its own toggle in the Lighting section; turn off to improve performance)");
        effectAnimationsToggle.setSelected(preferences.getBoolean(PREF_EFFECT_ANIMATIONS, true));
        CanvasMapRenderer.setEffectAnimationsEnabled(effectAnimationsToggle.isSelected());
        effectAnimationsToggle.setOnAction(e -> {
            boolean on = effectAnimationsToggle.isSelected();
            CanvasMapRenderer.setEffectAnimationsEnabled(on);
            preferences.putBoolean(PREF_EFFECT_ANIMATIONS, on);
            status(on ? "Effect animations on." : "Effect animations off.");
            renderDm();
            renderPlayer();
        });
        HBox effectTextureRow = row(overlayTextureBox, overlayBorderToggle, overlayLightToggle, effectAnimationsToggle);
        HBox effectBrushRow = row(brushSlider());

        // Text
        textLayerToggle = Icons.toggle(MaterialDesignE.EYE_OUTLINE,
                "Show / hide all text boxes (DM and player view) — text is drawn below the fog");
        textLayerToggle.setSelected(true);
        textLayerToggle.setOnAction(e -> {
            if (!syncingControls) {
                setTextLayerVisible(textLayerToggle.isSelected());
            }
        });
        deleteTextButton = Icons.button(MaterialDesignD.DELETE_OUTLINE, "Delete the selected text box (Del)", this::deleteSelectedText);
        Region textSpacer = new Region();
        HBox.setHgrow(textSpacer, Priority.ALWAYS);
        textAutoSizeToggle = Icons.toggle(MaterialDesignA.ARROW_EXPAND_ALL,
                "Auto-size: the selected text box grows and shrinks to fit its text");
        textAutoSizeToggle.setOnAction(e -> {
            if (!syncingControls) {
                setTextAutoSize(textAutoSizeToggle.isSelected());
            }
        });
        Button rotateTextLeft = Icons.button(MaterialDesignR.ROTATE_LEFT,
                "Rotate all text boxes 90° counter-clockwise on the player view only (the DM view is not affected)",
                () -> rotateTexts(-1));
        Button rotateTextRight = Icons.button(MaterialDesignR.ROTATE_RIGHT,
                "Rotate all text boxes 90° clockwise on the player view only (the DM view is not affected)",
                () -> rotateTexts(1));
        textPlayerToggle = Icons.toggle(MaterialDesignA.ACCOUNT_GROUP_OUTLINE,
                "Players see the selected text box - turn off for DM-only notes");
        textPlayerToggle.setSelected(true);
        textPlayerToggle.setOnAction(e -> {
            if (!syncingControls && findTextBox(selectedTextId) != null && !findTextBox(selectedTextId).isRoomLabel()) {
                boolean value = textPlayerToggle.isSelected();
                executeTextChange(value ? "Show text box to players" : "Hide text box from players", selectedTextId,
                        b -> b.setPlayerVisible(value));
            }
        });
        HBox textToolsRow = row(toolButtons.get(EditorTool.TEXT), textLayerToggle, textAutoSizeToggle, textPlayerToggle, textSpacer, deleteTextButton);

        textSizeSpinner = new Spinner<>();
        textSizeSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(textMinFont(), textMaxFont(),
                Math.max(textMinFont(), Math.min(textMaxFont(), DmProject.DEFAULT_TEXT_SIZE)), 2));
        textSizeSpinner.setEditable(true);
        textSizeSpinner.setPrefWidth(84);
        Icons.tooltip(textSizeSpinner, "Font size — applies to the selected text while typing, or to new text; "
                + "with a text box selected it changes all of its text");
        textSizeSpinner.getEditor().focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                try {
                    int typed = (int) Math.round(Double.parseDouble(textSizeSpinner.getEditor().getText().replace(',', '.')));
                    textSizeSpinner.getValueFactory().setValue(clampFontSize(typed));
                } catch (NumberFormatException ex) {
                    textSizeSpinner.getEditor().setText(String.valueOf(textSizeSpinner.getValue()));
                }
            }
        });
        textSizeSpinner.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (!syncingControls && newValue != null) {
                applyTextFontSize(newValue);
            }
        });
        textColorPicker = new ColorPicker(Color.web(DmProject.DEFAULT_TEXT_COLOR));
        Icons.tooltip(textColorPicker, "Text color — applies to the selected text while typing, or to new text");
        textColorPicker.setOnAction(e -> {
            if (!syncingControls) {
                applyTextColor(toHex(textColorPicker.getValue()));
            }
        });
        textBackgroundPicker = new ColorPicker(Color.web(DmProject.DEFAULT_TEXT_BACKGROUND));
        Icons.tooltip(textBackgroundPicker, "Text box background color (light gray by default)");
        textBackgroundPicker.setOnAction(e -> {
            if (!syncingControls) {
                applyTextBoxStyle("Change text background", b -> b.setBackgroundColor(toRgba(textBackgroundPicker.getValue())));
            }
        });
        textBorderPicker = new ColorPicker(Color.TRANSPARENT);
        Icons.tooltip(textBorderPicker, "Text box border color (transparent by default)");
        textBorderPicker.setOnAction(e -> {
            if (!syncingControls) {
                applyTextBoxStyle("Change text border", b -> b.setBorderColor(toRgba(textBorderPicker.getValue())));
            }
        });
        Button noFill = Icons.button(MaterialDesignC.CLOSE_CIRCLE_OUTLINE, "No background", () -> {
            textBackgroundPicker.setValue(Color.TRANSPARENT);
            applyTextBoxStyle("Change text background", b -> b.setBackgroundColor(DmProject.TRANSPARENT));
        });
        Button noBorder = Icons.button(MaterialDesignC.CLOSE_CIRCLE_OUTLINE, "No border", () -> {
            textBorderPicker.setValue(Color.TRANSPARENT);
            applyTextBoxStyle("Change text border", b -> b.setBorderColor(DmProject.TRANSPARENT));
        });
        HBox textSizeRow = row(Icons.icon(MaterialDesignF.FORMAT_SIZE), textSizeSpinner,
                Icons.icon(MaterialDesignF.FORMAT_COLOR_TEXT), textColorPicker, rotateTextLeft, rotateTextRight);
        HBox textBoxColorRow = row(Icons.icon(MaterialDesignF.FORMAT_COLOR_FILL), textBackgroundPicker, noFill,
                Icons.icon(MaterialDesignB.BORDER_COLOR), textBorderPicker, noBorder);

        // Map building
        ToggleButton snapLayers = Icons.toggle(MaterialDesignM.MAGNET,
                "Snap image layers to half-tile steps while moving / resizing");
        snapLayers.setOnAction(e -> {
            snapLayersToGrid = snapLayers.isSelected();
            status(snapLayersToGrid ? "Image layers snap to half-tile steps while moving/resizing." : "Layer snapping off.");
        });
        imageLockToggle = Icons.toggle(MaterialDesignL.LOCK_OUTLINE,
                "Lock / unlock the image layer — while locked, map images can't be selected, moved, resized or deleted "
                        + "(lights, doors and all other tools still work)");
        imageLockToggle.setOnAction(e -> {
            if (!syncingControls) {
                setImageLayerLocked(imageLockToggle.isSelected());
            }
        });
        updateImageLockToggle();
        HBox portalRow = row(toolButtons.get(EditorTool.DOOR_DRAW), toolButtons.get(EditorTool.WINDOW_DRAW),
                toolButtons.get(EditorTool.ROOM_LABEL));
        HBox buildRow = row(toolButtons.get(EditorTool.WALL_DRAW), toolButtons.get(EditorTool.WALL_ERASE), wallLayerToggle,
                Icons.separator(), imageLockToggle, snapLayers,
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
        Button handoutButton = Icons.button(MaterialDesignI.IMAGE_FRAME,
                "Handout — paste images from the clipboard and show them to the players", () -> openHandoutWindow(stage));
        playerGridToggle = Icons.toggle(MaterialDesignG.GRID,
                "Show grid over the player map (does not remove grid lines baked into the image)");
        playerGridToggle.setSelected(showPlayerGrid);
        playerGridToggle.setOnAction(e -> {
            showPlayerGrid = playerGridToggle.isSelected();
            preferences.putBoolean(PREF_PLAYER_SHOW_GRID, showPlayerGrid);
            status(showPlayerGrid ? "Player grid shown." : "Player grid hidden.");
        });
        HBox playerRow = row(playerWindowToggle, freezePlayerButton, scaleTest, handoutButton);
        gridOpacitySlider = new Slider(Tuning.GRID_OPACITY.min(), Tuning.GRID_OPACITY.max(), Tuning.GRID_OPACITY.get());
        gridOpacitySlider.setMajorTickUnit(0.01);
        gridOpacitySlider.setMinorTickCount(0);
        gridOpacitySlider.setSnapToTicks(true);
        gridOpacitySlider.setBlockIncrement(0.01);
        gridOpacitySlider.setPrefWidth(90);
        HBox.setHgrow(gridOpacitySlider, Priority.ALWAYS);
        Icons.tooltip(gridOpacitySlider, "Grid opacity (0-100%, shared with the DM background grid)");
        Label gridOpacityValue = new Label(Math.round(gridOpacitySlider.getValue() * 100) + "%");
        gridOpacityValue.getStyleClass().add("value-label");
        gridOpacitySlider.valueProperty().addListener((obs, oldValue, newValue) -> {
            gridOpacityValue.setText(Math.round(newValue.doubleValue() * 100) + "%");
            if (!syncingControls) {
                preferences.applyEdit(Tuning.GRID_OPACITY.key(), String.valueOf(newValue.doubleValue()));
            }
        });
        HBox playerGridRow = row(playerGridToggle, gridOpacitySlider, gridOpacityValue);

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

        screenInchesSpinner = createDoubleSpinner(diagonalMin(), diagonalMax(), loadScreenDiagonal(), 0.5, 84);
        Icons.tooltip(screenInchesSpinner, "Diagonal of the player screen in inches (used for the 1-inch grid)");
        screenInchesSpinner.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (!syncingControls && newValue != null) {
                preferences.putDouble(PREF_SCREEN_DIAGONAL_PREFIX + selectedScreenIndex(), newValue);
            }
        });
        tileInchesSpinner = createDoubleSpinner(Math.min(Tuning.PLAYER_TILE_INCHES_MIN.get(), Tuning.PLAYER_TILE_INCHES_MAX.get()),
                Math.max(Tuning.PLAYER_TILE_INCHES_MIN.get(), Tuning.PLAYER_TILE_INCHES_MAX.get()),
                preferences.getDouble(PREF_TILE_INCHES, 1.0), 0.05, 84);
        Icons.tooltip(tileInchesSpinner, "Size of one map tile on the player screen in inches");
        tileInchesSpinner.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue != null) {
                preferences.putDouble(PREF_TILE_INCHES, newValue);
            }
        });
        HBox diagonalRow = calibrationRow("Screen diagonal (in)", screenInchesSpinner);
        HBox tileSizeRow = calibrationRow("Tile size (in)", tileInchesSpinner);
        VBox scaleGrid = new VBox(4, diagonalRow, tileSizeRow);
        dmControlVisibility.registerRow("player", diagonalRow, "diagonal", "diagonal");
        dmControlVisibility.registerRow("player", tileSizeRow, "tileSize", "tileSize");
        dmControlVisibility.registerContainer(scaleGrid);

        playerZoomSlider = new Slider(Tuning.PLAYER_ZOOM_MIN_STEP.get(), Tuning.PLAYER_ZOOM_MAX_STEP.get(), 0);
        playerZoomSlider.setMajorTickUnit(0.05);
        playerZoomSlider.setMinorTickCount(0);
        playerZoomSlider.setSnapToTicks(true);
        playerZoomSlider.setBlockIncrement(0.05);
        playerZoomSlider.setPrefWidth(90);
        HBox.setHgrow(playerZoomSlider, Priority.ALWAYS);
        Icons.tooltip(playerZoomSlider, "Player view zoom for this map (saved with the map). 0 = calibrated tile size; "
                + "lower zooms out, higher zooms in. Double-click to reset (also via the Reset button in the player view box on the map).");
        playerZoomValue = new Label(formatPlayerZoom(0));
        playerZoomValue.getStyleClass().add("value-label");
        playerZoomSlider.valueProperty().addListener((obs, oldValue, newValue) -> {
            playerZoomValue.setText(formatPlayerZoom(newValue.doubleValue()));
            if (!syncingControls) {
                project.getViews().setPlayerZoomStep(newValue.doubleValue());
            }
        });
        playerZoomSlider.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                playerZoomSlider.setValue(0);
            }
        });
        HBox playerZoomRow = row(Icons.icon(MaterialDesignM.MAGNIFY), playerZoomSlider, playerZoomValue);
        HBox.setHgrow(playerZoomRow, Priority.ALWAYS);

        HBox weatherRow = weatherRow();
        HBox lightningIntervalRow = lightningIntervalRow();

        dmControlVisibility.registerRow("tools", toolsRow, "select", "ping", "laser", null, "undo", "redo");
        dmControlVisibility.registerRow("fog", fogToolsRow, "enabled", null, "revealBrush", "hideBrush", "revealRect", "hideRect");
        dmControlVisibility.registerRow("fog", fogFillRow, "revealAll", "hideAll", "revealRoom", null, "brushSize");
        dmControlVisibility.register("fog.sharpness", fogSharpnessRow);
        dmControlVisibility.registerRow("fog", fogEffectsRow, "fade", "softness", "softness", "softness");
        dmControlVisibility.registerRow("lighting", lightRow, "torch", "candle", "campfire", "magic", null, "remove", null, "flicker");
        dmControlVisibility.registerRow("lighting", revealRow, null, "revealPersistent", "revealWhileLit", "revealNone");
        dmControlVisibility.registerRow("lighting", timeSegment, "day", "dawn", "dusk", "night");
        dmControlVisibility.registerRow("lighting", timeRow, "", null, "on", "off");
        dmControlVisibility.register("lighting.ambient", ambientBrightnessRow);
        dmControlVisibility.register("lighting.tint", lightTintRow);
        dmControlVisibility.register("lighting.brightCore", brightCoreRow);
        dmControlVisibility.register("lighting.hint", lightHint);
        dmControlVisibility.registerRow("weather", weatherRow, "type", "intensity", "intensity");
        dmControlVisibility.registerRow("weather", lightningIntervalRow,
                "lightningInterval", "lightningInterval", "lightningInterval");
        dmControlVisibility.registerRow("effects", effectToolsRow, "circle", "rectangle", "brush", "pen", "line", null, "delete", "clear");
        dmControlVisibility.registerRow("effects", effectStyleRow, "color", "opacity", "players");
        dmControlVisibility.registerRow("effects", effectTextureRow, "texture", "border", "light", "animations");
        dmControlVisibility.register("effects.brushSize", effectBrushRow);
        dmControlVisibility.registerRow("text", textToolsRow, "add", "layer", "autoSize", "players", null, "delete");
        dmControlVisibility.registerRow("text", textSizeRow, "size", "size", "color", "color", "rotateLeft", "rotateRight");
        dmControlVisibility.registerRow("text", textBoxColorRow, "background", "background", "noBackground", "border", "border", "noBorder");
        dmControlVisibility.registerRow("building", buildRow, "drawWall", "eraseWall", "wallLayer", null, "lock", "snap", "addImage");
        dmControlVisibility.registerRow("building", portalRow, "drawDoor", "drawWindow", "roomLabel");
        dmControlVisibility.registerRow("player", playerRow, "window", "freeze", "scaleTest", "handout");
        dmControlVisibility.registerRow("player", playerGridRow, "grid", "gridOpacity", "gridOpacity");
        dmControlVisibility.register("player.screen", screenRow);
        dmControlVisibility.register("player.zoom", playerZoomRow);

        if (Tuning.AUDIO_ENABLED.get()) {
            audioControls = new AudioControls(preferences, () -> primaryStage);
            audioControls.setApiControlsChangedHandler(this::registerAudioApiControls);
        }

        VBox sections = new VBox(
                new CollapsibleSection("Tools", MaterialDesignC.CURSOR_DEFAULT, preferences, "tools", toolsRow),
                new CollapsibleSection("Fog of war", MaterialDesignW.WEATHER_FOG, preferences, "fog", fogToolsRow, fogFillRow, fogSharpnessRow, fogEffectsRow),
                new CollapsibleSection("Lighting", MaterialDesignL.LIGHTBULB_OUTLINE, preferences, "lighting", lightRow, revealRow, timeRow, ambientBrightnessRow, lightTintRow, brightCoreRow, lightHint),
                new CollapsibleSection("Weather", MaterialDesignW.WEATHER_PARTLY_RAINY, preferences, "weather",
                        weatherRow, lightningIntervalRow),
                new CollapsibleSection("Effects", MaterialDesignF.FORMAT_PAINT, preferences, "effects",
                        effectToolsRow, effectStyleRow, effectTextureRow, effectBrushRow),
                new CollapsibleSection("Text", MaterialDesignT.TEXT_BOX_OUTLINE, preferences, "text",
                        textToolsRow, textSizeRow, textBoxColorRow),
                new CollapsibleSection("Map building", MaterialDesignW.WALL, preferences, "building", buildRow, portalRow),
                new CollapsibleSection("Player view", MaterialDesignP.PROJECTOR, preferences, "player",
                        playerRow, playerGridRow, screenRow, scaleGrid, playerZoomRow),
                new CollapsibleSection("Performance", MaterialDesignS.SPEEDOMETER, preferences, "performance",
                        frameRateGrid()));

        String[] sectionIds = {"tools", "fog", "lighting", "weather", "effects", "text", "building", "player", "performance"};
        for (int i = 0; i < sectionIds.length; i++) {
            dmSections.put(sectionIds[i], (CollapsibleSection) sections.getChildren().get(i));
        }
        applySectionVisibility();

        // Reports the content's preferred height so hiding or collapsing sections resizes the panel in the same layout pass.
        ScrollPane scroll = new ScrollPane(sections) {
            @Override
            protected double computePrefHeight(double width) {
                return sections.prefHeight(Math.max(0, width - 2)) + 2;
            }
        };
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
        Button settingsButton = Icons.button(MaterialDesignC.COG_OUTLINE, "Settings: all options that are not part of the DM controls, "
                + "and which tabs and individual controls are shown", this::openSettings);
        extraApiControls.put("ui.controls", collapse);
        extraApiControls.put("ui.panelSettings", settingsButton);
        HBox titleRow = new HBox(Icons.icon(MaterialDesignT.TUNE_VARIANT), title, spacer, settingsButton, collapse);
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

    private String localApiBaseUrl() {
        int port = localApiServer == null ? Tuning.API_PORT.get() : localApiServer.port();
        return Tuning.API_COPY_ADDRESS.get().equals("local")
                ? "http://127.0.0.1:" + port : LocalApiServer.baseUrl(port);
    }

    private String mapApiUrl(Path file) {
        try {
            var map = mapLibrary.findApiMap(file).orElseThrow(
                    () -> new LocalApiServer.ApiException(404, "Map is not in the library."));
            return localApiBaseUrl() + "/api/maps/" + map.id() + "/switch";
        } catch (IOException ex) {
            throw new LocalApiServer.ApiException(500, "Could not read map ID: " + ex.getMessage());
        }
    }

    private void applyLocalApiSettings() {
        if (!Tuning.API_ENABLED.get()) {
            if (localApiServer != null) {
                LocalApiServer previous = localApiServer;
                localApiServer = null;
                previous.close();
                status("Local control API disabled.");
            }
            return;
        }
        int port = Tuning.API_PORT.get();
        if (localApiServer != null && localApiServer.port() == port) {
            return;
        }
        LocalApiServer server = new LocalApiServer(port, this::handleLocalApi);
        try {
            server.start();
            LocalApiServer previous = localApiServer;
            localApiServer = server;
            if (previous != null) {
                previous.close();
            }
            status("Local control API listening at " + server.baseUrl());
        } catch (IOException ex) {
            server.close();
            status("Could not start local control API: " + ex.getMessage()
                    + (localApiServer == null ? "" : ". Still listening at " + localApiServer.baseUrl()));
        }
    }

    @Override
    public void stop() {
        stopping = true;
        prefetchGeneration++;
        cancelImageAdds();
        if (adjacentPrefetch != null) {
            adjacentPrefetch.close();
        }
        levelPreviews.close();
        if (localApiServer != null) {
            localApiServer.close();
            localApiServer = null;
        }
        WorkScheduler.shutdownShared();
    }

    private Object handleLocalApi(String path, Map<String, String> query) {
        try {
            if (path.equals("/api/controls")) {
                requireApiParameters(query, Set.of("ids"));
                if (query.containsKey("ids")) {
                    List<String> ids = apiControlIds(query.get("ids"));
                    return FxApiDispatcher.call(() -> controlApi.describe(ids));
                }
                return FxApiDispatcher.call(controlApi::describe);
            }
            if (path.startsWith("/api/controls/") && path.endsWith("/image")) {
                requireApiParameters(query, Set.of("operation"));
                String id = path.substring("/api/controls/".length(), path.length() - "/image".length())
                        .replace('/', '.');
                String operation = query.get("operation");
                if (operation != null && !operation.equals("increment") && !operation.equals("decrement")) {
                    throw new LocalApiServer.ApiException(400,
                            "Key image operation must be 'increment' or 'decrement'.");
                }
                return new LocalApiServer.Binary("image/png",
                        FxApiDispatcher.call(() -> controlApi.keyImage(id, operation)), 60);
            }
            if (path.startsWith("/api/controls/")) {
                String id = path.substring("/api/controls/".length()).replace('/', '.');
                return FxApiDispatcher.call(() -> {
                    ensureApiCanAct();
                    finishNudge();
                    lastInputNanos = System.nanoTime();
                    apiActionRunning = true;
                    try {
                        return controlApi.execute(id, query);
                    } finally {
                        apiActionRunning = false;
                    }
                });
            }
            if (path.equals("/api/maps")) {
                requireApiParameters(query, Set.of());
                FxApiDispatcher.call(() -> {
                    if (ioBusy) {
                        throw new LocalApiServer.ApiException(409, "A map file operation is in progress.");
                    }
                    return null;
                });
                return mapLibrary.listApiMaps().stream().map(map -> Map.of(
                        "id", map.id(), "name", map.name(), "multilevel", map.multilevel(),
                        "url", localApiBaseUrl() + "/api/maps/" + map.id() + "/switch")).toList();
            }
            if (path.startsWith("/api/maps/") && path.endsWith("/switch")) {
                requireApiParameters(query, Set.of("level"));
                FxApiDispatcher.call(() -> {
                    if (ioBusy) {
                        throw new LocalApiServer.ApiException(409, "A map file operation is in progress.");
                    }
                    return null;
                });
                String id = path.substring("/api/maps/".length(), path.length() - "/switch".length());
                try {
                    if (!UUID.fromString(id).toString().equalsIgnoreCase(id)) {
                        throw new IllegalArgumentException("UUID must use its canonical form.");
                    }
                } catch (IllegalArgumentException ex) {
                    throw new LocalApiServer.ApiException(400, "Invalid map UUID.");
                }
                var map = mapLibrary.resolveApiMap(id).orElseThrow(
                        () -> new LocalApiServer.ApiException(404, "Map ID is not in the library."));
                String levelId = null;
                if (query.containsKey("level")) {
                    if (!map.multilevel()) {
                        throw new LocalApiServer.ApiException(400, "Only multilevel maps accept a level index.");
                    }
                    int index;
                    try {
                        index = Integer.parseInt(query.get("level"));
                    } catch (NumberFormatException ex) {
                        throw new LocalApiServer.ApiException(400, "Level index must be an integer.");
                    }
                    MultiLevelManifest manifest = mapLibrary.multiLevels().loadManifest(map.path());
                    if (index < 0 || index >= manifest.getLevels().size()) {
                        throw new LocalApiServer.ApiException(400, "Level index is outside this map's range.");
                    }
                    levelId = manifest.getLevels().get(index).getId();
                }
                String desiredLevel = levelId;
                return FxApiDispatcher.call(() -> {
                    ensureApiCanAct();
                    commitTextEdit();
                    if (projectFile == null && hasContent(project)) {
                        throw new LocalApiServer.ApiException(409, "Save the new map before switching via the API.");
                    }
                    finishNudge();
                    switchToMap(map.path(), desiredLevel);
                    boolean alreadyOpen = openMapFile() != null
                            && map.path().toAbsolutePath().normalize().equals(openMapFile().toAbsolutePath().normalize())
                            && (desiredLevel == null || desiredLevel.equals(currentLevelId));
                    if (!ioBusy && !alreadyOpen) {
                        throw new LocalApiServer.ApiException(500, "Map switch could not be started; see the DM status bar.");
                    }
                    return Map.of("accepted", true, "id", id, "message",
                            "Map switch requested; loading and any errors are shown in the DM window.");
                });
            }
            if (path.equals("/api/state")) {
                requireApiParameters(query, Set.of());
                AtomicReference<Path> openFile = new AtomicReference<>();
                Map<String, Object> state = FxApiDispatcher.call(() -> {
                    openFile.set(openMapFile());
                    Map<String, Object> values = new LinkedHashMap<>();
                    values.put("busy", ioBusy);
                    values.put("map", openMapFile() == null ? "" : MapBrowser.displayName(openMapFile()));
                    values.put("level", multiLevelManifest == null ? -1 : multiLevelManifest.indexOf(currentLevelId));
                    values.put("frozen", frozenPlayerProject != null);
                    return values;
                });
                // Resolved off the JavaFX thread and cached, so polling never scans the library per request.
                state.put("id", apiMapId(openFile.get()));
                return Map.copyOf(state);
            }
            throw new LocalApiServer.ApiException(404, "Unknown API endpoint.");
        } catch (IOException ex) {
            throw new LocalApiServer.ApiException(500, "Map library operation failed: " + ex.getMessage());
        }
    }

    private static void requireApiParameters(Map<String, String> query, Set<String> allowed) {
        if (!allowed.containsAll(query.keySet())) {
            throw new LocalApiServer.ApiException(400, "Unsupported query parameter.");
        }
    }

    /**
     * The library UUID of the open map for {@code /api/state} (3.36.1), cached per map file so that polling a
     * map key does not rescan the library. Called on an API worker thread, never on the JavaFX thread.
     */
    private String apiMapId(Path file) {
        if (file == null) {
            apiStateMapFile = null;
            apiStateMapId = "";
            return "";
        }
        if (file.equals(apiStateMapFile)) {
            return apiStateMapId;
        }
        String id;
        try {
            id = mapLibrary.findApiMap(file).map(MapLibraryService.ApiMap::id).orElse("");
        } catch (IOException ex) {
            return "";
        }
        apiStateMapFile = file;
        apiStateMapId = id;
        return id;
    }

    /** Parses the comma-separated {@code ids} filter of batched control discovery (3.36.1). */    private static List<String> apiControlIds(String value) {
        List<String> ids = new ArrayList<>();
        for (String part : value.split(",", -1)) {
            String id = part.trim();
            if (id.isEmpty()) {
                throw new LocalApiServer.ApiException(400, "The ids parameter contains an empty control id.");
            }
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            throw new LocalApiServer.ApiException(400, "The ids parameter must list at least one control id.");
        }
        if (ids.size() > 128) {
            throw new LocalApiServer.ApiException(400, "At most 128 control ids can be requested at once.");
        }
        return List.copyOf(ids);
    }

    private void ensureApiCanAct() {
        if (ioBusy || apiActionRunning || canvasMouseDown || fogDragging) {
            throw new LocalApiServer.ApiException(409, "A DM operation is in progress.");
        }
        boolean modalDialog = javafx.stage.Window.getWindows().stream()
                .anyMatch(window -> window instanceof Stage stage && stage.isShowing()
                        && stage.getModality() != javafx.stage.Modality.NONE);
        if (modalDialog) {
            throw new LocalApiServer.ApiException(409, "Close the modal DM dialog before sending commands.");
        }
    }

    private void openSettings() {
        SettingsWindow.show(primaryStage, preferences, () -> {
            applySectionVisibility();
            applyPlayerGridSetting();
            applyApiUrlOptionVisibility();
            applyLocalApiSettings();
            if (audioControls != null) {
                audioControls.applySettings();
            }
        });
    }

    private void applyApiUrlOptionVisibility() {
        boolean visible = Tuning.API_SHOW_URL_OPTIONS.get();
        if (controlApi != null) {
            controlApi.setUrlOptionsVisible(visible);
        }
        if (mapBrowser != null) {
            mapBrowser.setApiUrlOptionsVisible(visible);
        }
    }

    private void applyPlayerGridSetting() {
        showPlayerGrid = preferences.getBoolean(PREF_PLAYER_SHOW_GRID, false);
        if (playerGridToggle != null) {
            playerGridToggle.setSelected(showPlayerGrid);
        }
        if (gridOpacitySlider != null) {
            boolean wasSyncing = syncingControls;
            syncingControls = true;
            try {
                gridOpacitySlider.setValue(Tuning.GRID_OPACITY.get());
            } finally {
                syncingControls = wasSyncing;
            }
        }
    }

    /**
     * Registers the audio endpoints of the status bar group and the overlay, including one toggle per music
     * category and per sound effect; called again whenever the library changed (3.35.6).
     */
    private void registerAudioApiControls() {
        if (controlApi == null || audioControls == null || !Tuning.AUDIO_ENABLED.get()) {
            return;
        }
        controlApi.replaceGroup("audio", audioControls.apiControls());
    }

    /** Shows or hides the tabs of the DM controls according to the {@code ui.sections.hidden} setting. */    private void applySectionVisibility() {
        dmControlVisibility.apply(preferences.hiddenControls());
        updateSelectionControls();
        java.util.Set<String> hidden = preferences.hiddenSections();
        dmSections.forEach((id, section) -> {
            boolean show = !hidden.contains(id);
            section.setVisible(show);
            section.setManaged(show);
        });
    }

    private HBox row(javafx.scene.Node... nodes) {
        HBox row = new HBox(nodes);
        row.getStyleClass().add("control-row");
        return row;
    }

    private HBox calibrationRow(String title, javafx.scene.Node control) {
        Label label = mutedLabel(title);
        label.setMinWidth(136);
        HBox box = new HBox(8, label, control);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private Label mutedLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("muted");
        return label;
    }

    /** A brush size slider; all brush sliders share one value. */
    private HBox brushSlider() {
        Slider slider = new Slider(Math.min(Tuning.BRUSH_MIN.get(), Tuning.BRUSH_MAX.get()), Math.max(Tuning.BRUSH_MIN.get(), Tuning.BRUSH_MAX.get()), brushSize.get());
        slider.setMajorTickUnit(0.1);
        slider.setMinorTickCount(0);
        slider.setSnapToTicks(true);
        slider.setBlockIncrement(0.1);
        slider.valueProperty().bindBidirectional(brushSize);
        HBox.setHgrow(slider, Priority.ALWAYS);
        slider.setPrefWidth(90);
        Icons.tooltip(slider, "Brush size in tiles — Alt + mouse wheel over the map with a fog brush, Draw or Line");
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
     * Global fog edge sharpness (fog cells per grid cell). Stored in the settings file so it applies
     * to every project; the fog mask is resampled when the slider is released.
     */
    private HBox fogSharpnessSlider() {
        int initial = FogService.getCellsPerGrid();
        Slider slider = new Slider(FogService.minCellsPerGrid(), FogService.maxCellsPerGrid(), initial);
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

    /** Global soft fog edge width and the fade-in/out toggle; both are stored in the settings file. */
    private HBox fogEffectsRow() {
        ToggleButton fade = Icons.toggle(MaterialDesignA.ANIMATION_OUTLINE,
                "Fade fog in and out when it is revealed or hidden (applies to all maps)");
        fade.setSelected(CanvasMapRenderer.isFogFadeEnabled());
        fade.setOnAction(e -> {
            CanvasMapRenderer.setFogFadeEnabled(fade.isSelected());
            preferences.putBoolean(PREF_FOG_FADE, fade.isSelected());
            lastInputNanos = System.nanoTime();
        });
        Slider slider = new Slider(0, CanvasMapRenderer.maxFogSoftness(), CanvasMapRenderer.getFogSoftness());
        slider.setBlockIncrement(0.05);
        HBox.setHgrow(slider, Priority.ALWAYS);
        slider.setPrefWidth(90);
        Icons.tooltip(slider, "Soft fog edge width in tiles (0 = hard edge, applies to all maps)");
        Label value = new Label(Math.round(slider.getValue() * 100) + "%");
        value.getStyleClass().add("value-label");
        slider.valueProperty().addListener((obs, oldValue, newValue) -> {
            double softness = Math.round(newValue.doubleValue() * 20) / 20.0;
            value.setText(Math.round(softness * 100) + "%");
            CanvasMapRenderer.setFogSoftness(softness);
            preferences.putDouble(PREF_FOG_SOFTNESS, softness);
            lastInputNanos = System.nanoTime();
        });
        FontIcon icon = Icons.icon(MaterialDesignB.BLUR_LINEAR);
        icon.getStyleClass().add("muted-icon");
        HBox box = row(fade, icon, slider, value);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    /**
     * Global strength of the light colour tint over lit areas. Stored in the settings file so it
     * applies to every light in every project; the renderer picks it up on the next frame.
     */
    /**
     * Strength of the light colour tint over lit areas on this map. Saved with the map (in {@code lighting.lightTint}
     * of the project file) so different maps can use different strengths; the renderer picks it up on the next frame.
     */
    private HBox lightTintSlider() {
        double initial = CanvasMapRenderer.DEFAULT_LIGHT_TINT;
        Slider slider = new Slider(CanvasMapRenderer.MIN_LIGHT_TINT, CanvasMapRenderer.maxLightTint(), initial);
        slider.setMajorTickUnit(0.01);
        slider.setMinorTickCount(0);
        slider.setSnapToTicks(true);
        slider.setBlockIncrement(0.01);
        HBox.setHgrow(slider, Priority.ALWAYS);
        slider.setPrefWidth(90);
        Icons.tooltip(slider, "Light colour tint strength on this map (saved with the map). "
                + "Lower keeps the map's own colours, higher tints lit areas with the light colour. Double-click to reset.");
        Label value = new Label(Math.round(initial * 100) + "%");
        value.getStyleClass().add("value-label");
        lightTintSlider = slider;
        lightTintValue = value;
        slider.valueProperty().addListener((obs, oldValue, newValue) -> {
            value.setText(Math.round(newValue.doubleValue() * 100) + "%");
            if (syncingControls) {
                return;
            }
            project.getLighting().setLightTint(CanvasMapRenderer.clampLightTint(newValue.doubleValue()));
            if (!slider.isValueChanging()) {
                commitLightTint();
            }
        });
        slider.valueChangingProperty().addListener((obs, wasChanging, changing) -> {
            if (!changing && !syncingControls) {
                commitLightTint();
            }
        });
        slider.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                slider.setValue(CanvasMapRenderer.DEFAULT_LIGHT_TINT);
                commitLightTint();
            }
        });
        FontIcon icon = Icons.icon(MaterialDesignP.PALETTE_OUTLINE);
        icon.getStyleClass().add("muted-icon");
        HBox box = row(icon, slider, value);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    /**
     * Strength of each light's bright-core highlight on this map: a small additive hot-spot at each light's own
     * position that brightens the map art underneath (not a flat overlay). Saved with the map (in
     * {@code lighting.brightCore} of the project file); the renderer picks it up on the next frame.
     */
    private HBox brightCoreSlider() {
        double initial = CanvasMapRenderer.DEFAULT_BRIGHT_CORE;
        Slider slider = new Slider(CanvasMapRenderer.MIN_BRIGHT_CORE, CanvasMapRenderer.maxBrightCore(), initial);
        slider.setMajorTickUnit(0.01);
        slider.setMinorTickCount(0);
        slider.setSnapToTicks(true);
        slider.setBlockIncrement(0.01);
        HBox.setHgrow(slider, Priority.ALWAYS);
        slider.setPrefWidth(90);
        Icons.tooltip(slider, "Bright core strength on this map (saved with the map). "
                + "0% is off; higher brightens the map art at each light's own center instead of covering it. Double-click to reset.");
        Label value = new Label(Math.round(initial * 100) + "%");
        value.getStyleClass().add("value-label");
        brightCoreSlider = slider;
        brightCoreValue = value;
        slider.valueProperty().addListener((obs, oldValue, newValue) -> {
            value.setText(Math.round(newValue.doubleValue() * 100) + "%");
            if (syncingControls) {
                return;
            }
            project.getLighting().setBrightCore(CanvasMapRenderer.clampBrightCore(newValue.doubleValue()));
            if (!slider.isValueChanging()) {
                commitBrightCore();
            }
        });
        slider.valueChangingProperty().addListener((obs, wasChanging, changing) -> {
            if (!changing && !syncingControls) {
                commitBrightCore();
            }
        });
        slider.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                slider.setValue(CanvasMapRenderer.DEFAULT_BRIGHT_CORE);
                commitBrightCore();
            }
        });
        FontIcon icon = Icons.icon(MaterialDesignW.WHITE_BALANCE_SUNNY);
        icon.getStyleClass().add("muted-icon");
        HBox box = row(icon, slider, value);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    /**
     * Ambient brightness of the current time-of-day preset for this map (saved in the project).
     * Updates live while dragging; the change is recorded as one undo step on release.
     */
    private HBox ambientBrightnessSlider() {
        Slider slider = new Slider(TimeOfDayPreset.MIN_BRIGHTNESS, TimeOfDayPreset.MAX_BRIGHTNESS, 0);
        slider.setMajorTickUnit(0.05);
        slider.setMinorTickCount(0);
        slider.setSnapToTicks(true);
        slider.setBlockIncrement(0.05);
        HBox.setHgrow(slider, Priority.ALWAYS);
        slider.setPrefWidth(90);
        Icons.tooltip(slider, "Ambient brightness for the current time of day on this map "
                + "(saved with the map). Raise it if e.g. Night is too dark without a light. Double-click to reset.");
        Label value = new Label(formatAmbientBrightness(0));
        value.getStyleClass().add("value-label");
        ambientBrightnessSlider = slider;
        ambientBrightnessValue = value;
        slider.valueProperty().addListener((obs, oldValue, newValue) -> {
            value.setText(formatAmbientBrightness(newValue.doubleValue()));
            if (syncingControls) {
                return;
            }
            project.getLighting().putAmbientBrightness(project.getLighting().getTimeOfDayPreset(), newValue.doubleValue());
            if (!slider.isValueChanging()) {
                commitAmbientBrightness();
            }
        });
        slider.valueChangingProperty().addListener((obs, wasChanging, changing) -> {
            if (!changing && !syncingControls) {
                commitAmbientBrightness();
            }
        });
        slider.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                slider.setValue(0);
                commitAmbientBrightness();
            }
        });
        FontIcon icon = Icons.icon(MaterialDesignB.BRIGHTNESS_6);
        icon.getStyleClass().add("muted-icon");
        HBox box = row(icon, slider, value);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    /** Ambient weather of this map: type dropdown plus a subtle-to-stronger intensity slider, both undoable. */
    private HBox weatherRow() {
        weatherBox = new ComboBox<>();
        weatherBox.getItems().addAll(WeatherType.values());
        weatherBox.setValue(WeatherType.NONE);
        weatherBox.setMaxWidth(Double.MAX_VALUE);
        Icons.tooltip(weatherBox, "Ambient weather for this map, shown to DM and players above the map and below fog and text.");
        weatherBox.setOnAction(e -> {
            if (!syncingControls && weatherBox.getValue() != null) {
                changeWeather(weatherBox.getValue().key(), currentWeather().getIntensity());
            }
        });

        Slider slider = new Slider(WeatherEffects.MIN_INTENSITY, WeatherEffects.MAX_INTENSITY,
                WeatherEffects.defaultIntensity());
        slider.setMajorTickUnit(0.05);
        slider.setMinorTickCount(0);
        slider.setSnapToTicks(true);
        slider.setBlockIncrement(0.05);
        slider.setPrefWidth(70);
        HBox.setHgrow(slider, Priority.ALWAYS);
        Icons.tooltip(slider, "Weather intensity (rain amount for thunderstorms, not lightning frequency). Double-click to reset.");
        Label value = new Label(Math.round(slider.getValue() * 100) + "%");
        value.getStyleClass().add("value-label");
        weatherIntensitySlider = slider;
        weatherIntensityValue = value;
        slider.valueProperty().addListener((obs, oldValue, newValue) -> {
            value.setText(Math.round(newValue.doubleValue() * 100) + "%");
            if (syncingControls) {
                return;
            }
            project.getWeather().setIntensity(newValue.doubleValue());
            if (!slider.isValueChanging()) {
                commitWeatherIntensity();
            }
        });
        slider.valueChangingProperty().addListener((obs, wasChanging, changing) -> {
            if (!changing && !syncingControls) {
                commitWeatherIntensity();
            }
        });
        slider.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                slider.setValue(WeatherEffects.defaultIntensity());
                commitWeatherIntensity();
            }
        });
        HBox box = row(weatherBox, slider, value);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    private DmProject.WeatherState currentWeather() {
        if (project.getWeather() == null) {
            project.setWeather(DmProject.WeatherState.builder().build());
        }
        return project.getWeather();
    }

    private HBox lightningIntervalRow() {
        Label label = new Label("Lightning interval");
        Slider slider = new Slider(WeatherEffects.MIN_LIGHTNING_INTERVAL, WeatherEffects.MAX_LIGHTNING_INTERVAL,
                Tuning.WEATHER_LIGHTNING_INTERVAL.get());
        slider.setBlockIncrement(1);
        slider.setPrefWidth(70);
        HBox.setHgrow(slider, Priority.ALWAYS);
        Icons.tooltip(slider, "Seconds between lightning strikes, with natural variation. Lower = more frequent; independent of rain intensity. Double-click to reset.");
        Label value = new Label();
        value.getStyleClass().add("value-label");
        lightningIntervalSlider = slider;
        lightningIntervalValue = value;
        slider.valueProperty().addListener((obs, oldValue, newValue) -> {
            value.setText(String.format(java.util.Locale.ROOT, "%.1f s", newValue.doubleValue()));
            if (syncingControls) {
                return;
            }
            currentWeather().setLightningIntervalSeconds(newValue.doubleValue());
            if (!slider.isValueChanging()) {
                commitLightningInterval();
            }
        });
        slider.valueChangingProperty().addListener((obs, wasChanging, changing) -> {
            if (!changing && !syncingControls) {
                commitLightningInterval();
            }
        });
        slider.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                slider.setValue(Tuning.WEATHER_LIGHTNING_INTERVAL.get());
                commitLightningInterval();
            }
        });
        slider.setDisable(true);
        value.setText(String.format(java.util.Locale.ROOT, "%.1f s", slider.getValue()));
        return row(label, slider, value);
    }

    private void commitLightningInterval() {
        double before = lightningIntervalCommitted;
        double after = currentWeather().getLightningIntervalSeconds();
        if (Math.abs(after - before) < 1e-9) {
            return;
        }
        lightningIntervalCommitted = after;
        recordHistory("Change lightning interval",
                () -> setLightningInterval(after), () -> setLightningInterval(before));
    }

    private void setLightningInterval(double seconds) {
        currentWeather().setLightningIntervalSeconds(WeatherEffects.clampLightningInterval(seconds));
        syncControlsFromProject();
    }

    private void changeWeather(String typeKey, double intensity) {
        DmProject.WeatherState weather = currentWeather();
        String beforeType = weather.getType();
        double beforeIntensity = weather.getIntensity();
        if (WeatherType.from(beforeType) == WeatherType.from(typeKey)) {
            return;
        }
        executeWithHistory(
                "Change weather",
                () -> setWeather(typeKey, intensity),
                () -> setWeather(beforeType, beforeIntensity)
        );
        status("Weather: " + WeatherType.from(typeKey).label());
    }

    private void commitWeatherIntensity() {
        double before = weatherIntensityCommitted;
        double after = currentWeather().getIntensity();
        if (Math.abs(after - before) < 1e-9) {
            return;
        }
        weatherIntensityCommitted = after;
        String typeKey = currentWeather().getType();
        recordHistory(
                "Change weather intensity",
                () -> setWeather(typeKey, after),
                () -> setWeather(typeKey, before)
        );
    }

    private void setWeather(String typeKey, double intensity) {
        DmProject.WeatherState weather = currentWeather();
        weather.setType(WeatherType.from(typeKey).key());
        weather.setIntensity(WeatherEffects.clampIntensity(intensity));
        syncControlsFromProject();
    }

    private static String formatPlayerZoom(double step) {
        return Math.round(Math.pow(2, step) * 100) + "%";
    }

    private static String formatAmbientBrightness(double brightness) {
        long percent = Math.round(brightness * 100);
        return (percent > 0 ? "+" : "") + percent + "%";
    }

    private void commitAmbientBrightness() {
        String presetName = project.getLighting().getTimeOfDayPreset();
        double before = ambientBrightnessCommitted;
        double after = project.getLighting().ambientBrightnessFor(presetName);
        if (Math.abs(after - before) < 1e-9) {
            return;
        }
        ambientBrightnessCommitted = after;
        TimeOfDayPreset preset = TimeOfDayPreset.from(presetName);
        recordHistory(
                "Change " + preset.label().toLowerCase(Locale.ROOT) + " brightness",
                () -> setAmbientBrightness(presetName, after),
                () -> setAmbientBrightness(presetName, before)
        );
        status(preset.label() + " brightness on this map: " + formatAmbientBrightness(after) + ".");
    }

    private void setAmbientBrightness(String presetName, double brightness) {
        project.getLighting().putAmbientBrightness(presetName, brightness);
        syncControlsFromProject();
    }

    private void commitLightTint() {
        double before = lightTintCommitted;
        double after = project.getLighting().getLightTint();
        if (Math.abs(after - before) < 1e-9) {
            return;
        }
        lightTintCommitted = after;
        recordHistory(
                "Change light tint",
                () -> setLightTint(after),
                () -> setLightTint(before)
        );
        status("Light tint on this map: " + Math.round(after * 100) + "%.");
    }

    private void setLightTint(double value) {
        project.getLighting().setLightTint(CanvasMapRenderer.clampLightTint(value));
        syncControlsFromProject();
    }

    private void commitBrightCore() {
        double before = brightCoreCommitted;
        double after = project.getLighting().getBrightCore();
        if (Math.abs(after - before) < 1e-9) {
            return;
        }
        brightCoreCommitted = after;
        recordHistory(
                "Change bright core",
                () -> setBrightCore(after),
                () -> setBrightCore(before)
        );
        status("Bright core on this map: " + Math.round(after * 100) + "%.");
    }

    private void setBrightCore(double value) {
        project.getLighting().setBrightCore(CanvasMapRenderer.clampBrightCore(value));
        syncControlsFromProject();
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
        Label hint = new Label("Esc or right-click to exit");
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
        if (laserToolActive) {
            toolChipIcon.setIconCode(MaterialDesignL.LASER_POINTER);
            toolChipLabel.setText("Laser pointer");
            toolChip.setVisible(true);
        } else if (pingArmed) {
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

    private void setLaserToolActive(boolean active) {
        if (active) {
            setPingArmed(false);
            setActiveTool(EditorTool.SELECT);
        }
        laserToolActive = active;
        if (laserToggle != null && laserToggle.isSelected() != active) {
            laserToggle.setSelected(active);
        }
        if (active) {
            status("Laser pointer: move the mouse to point at the map.");
            if (hoverInsideCanvas) {
                addLaserPoint(hoverWorldX, hoverWorldY);
            }
        } else {
            laserTrail.clear();
        }
        updateToolChip();
        updateCanvasCursor();
    }

    private void setPingArmed(boolean armed) {
        if (armed && activeTool == EditorTool.WALL_DRAW) {
            draftWall = null;
        }
        if (armed && laserToolActive) {
            setLaserToolActive(false);
        }
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
        if (laserToolActive) {
            return Cursor.CROSSHAIR;
        }
        if (pingArmed) {
            return Icons.cursor(MaterialDesignC.CROSSHAIRS_GPS, 0.5, 0.5);
        }
        return switch (activeTool) {
            case SELECT -> Cursor.DEFAULT;
            case REVEAL_BRUSH -> Icons.cursor(MaterialDesignE.ERASER, 0.2, 0.82);
            case HIDE_BRUSH -> Icons.cursor(MaterialDesignB.BRUSH, 0.15, 0.85);
            case AOE_BRUSH -> Icons.tipCursor(MaterialDesignB.BRUSH, 0.0, 1.0);
            case AOE_PEN -> Icons.tipCursor(MaterialDesignP.PEN, 0.0, 1.0);
            case AOE_LINE -> Cursor.CROSSHAIR;
            case TEXT, ROOM_LABEL -> Cursor.TEXT;
            case WALL_DRAW, DOOR_DRAW, WINDOW_DRAW -> Icons.tipCursor(MaterialDesignP.PENCIL, 0.0, 1.0);
            case WALL_ERASE -> Icons.cursor(MaterialDesignE.ERASER_VARIANT, 0.2, 0.82);
            case LIGHT_ADD, LIGHT_CANDLE, LIGHT_CAMPFIRE, LIGHT_MAGIC ->
                    Icons.cursor(activeTool.icon, 0.5, 0.5);
            default -> Cursor.CROSSHAIR;
        };
    }

    /** Cursor for the current tool, or for what is under the mouse in Select mode. */
    private void updateCanvasCursor() {
        if (dmCanvas == null) {
            return;
        }
        Cursor cursor;
        if (panningDmCamera || draggingLight || draggingLayer || draggingOverlay || draggingText || draggingPlayerViewport
                || draggingGroup) {
            cursor = Cursor.CLOSED_HAND;
        } else if (resizingTextHandle >= 0) {
            cursor = textResizeCursor(resizingTextHandle);
        } else if (resizingLayer || resizingOverlay) {
            cursor = Cursor.SE_RESIZE;
        } else if (pingArmed || laserToolActive || activeTool != EditorTool.SELECT) {
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
        if (isOnPlayerViewportResetButton(worldX, worldY)) {
            return Cursor.HAND;
        }
        if (isOnPlayerViewportTitleBar(worldX, worldY)) {
            return Cursor.MOVE;
        }
        if (renderer.isWallLayerVisible() && pickInteractableBadge(worldX, worldY) != null) {
            return Cursor.HAND;
        }
        if (pickNearestLight(worldX, worldY, Tuning.LIGHT_PICK_RADIUS.get() / zoom) != null) {
            return Cursor.HAND;
        }
        if (renderer.isWallLayerVisible() && pickInteractableLine(worldX, worldY) != null) {
            return Cursor.HAND;
        }
        if (project.isTextLayerVisible()) {
            DmProject.TextBox selectedText = findTextBox(selectedTextId);
            int handle = selectedText == null ? -1 : pickTextHandle(selectedText, worldX, worldY, zoom);
            if (handle >= 0) {
                return textResizeCursor(handle);
            }
            if (pickTextBox(worldX, worldY) != null) {
                return Cursor.OPEN_HAND;
            }
        }
        if (isOnOverlayHandle(findOverlay(selectedOverlayId), worldX, worldY, zoom)) {
            return Cursor.SE_RESIZE;
        }
        if (pickOverlay(worldX, worldY, zoom) != null) {
            return Cursor.OPEN_HAND;
        }
        DmProject.ImageLayer layer = isImageLayerLocked() ? null : pickTopmostLayer(worldX, worldY);
        if (layer != null
                && distance(worldX, worldY, layer.getX() + layer.getWidth(), layer.getY() + layer.getHeight()) < Tuning.LAYER_HANDLE_RADIUS.get() / zoom) {
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
            public void importMapFolder(Path suggestedFolder) {
                handleImportDd2vttFolder(suggestedFolder);
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
                return openMapFile();
            }

            @Override
            public String currentLevelName() {
                MultiLevelManifest.Level level = currentLevel();
                return level == null ? null : level.getName();
            }

            @Override
            public void importMultiLevelMap(Path suggestedFolder) {
                handleImportMultiLevel(suggestedFolder);
            }

            @Override
            public void mergeIntoMultiLevelMap(List<MapLibraryService.Entry> maps) {
                handleMergeIntoMultiLevel(maps);
            }

            @Override
            public void manageLevels(Path manifestFile) {
                handleManageLevels(manifestFile);
            }

            @Override
            public void manageTags(List<MapLibraryService.Entry> maps) {
                handleManageTags(maps);
            }

            @Override
            public void dropMapOnMap(List<MapLibraryService.Entry> dragged, MapLibraryService.Entry target) {
                handleDropMapOnMap(dragged, target);
            }

            @Override
            public void dissolveMultiLevel(Path manifestFile) {
                handleDissolveMultiLevel(manifestFile);
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

    private record TagDialogData(List<String> tags, List<String> known) {
    }

    private void handleManageTags(List<MapLibraryService.Entry> maps) {
        MapTagService tags = new MapTagService(mapLibrary, projectService);
        runInBackground("Loading tags...", "Could not read map tags: ", () ->
                new TagDialogData(maps.size() == 1 ? tags.readTags(maps.getFirst().mapFile()) : List.of(),
                        tags.knownTags()), data -> {
            Optional<MapTagsDialog.Result> edited = MapTagsDialog.show(primaryStage, maps.getFirst().name(),
                    maps.size(), maps.getFirst().isMultiLevel(), data.tags(), data.known());
            if (edited.isEmpty() || (edited.get().additions().isEmpty() && edited.get().removals().isEmpty())) {
                return;
            }
            MapTagsDialog.Result changes = edited.get();
            Path openFile = projectFile;
            DmProject openProject = project;
            Path affected = openFile == null ? null : maps.stream()
                    .filter(map -> openFile.toAbsolutePath().normalize().startsWith(map.path().toAbsolutePath().normalize()))
                    .map(MapLibraryService.Entry::path).findFirst().orElse(null);
            List<String> liveTags = new ArrayList<>();
            runLibraryOperation("Updating tags...", affected, () -> {
                tags.updateTags(maps.stream().map(MapLibraryService.Entry::mapFile).toList(),
                        changes.additions(), changes.removals());
                if (affected != null) {
                    liveTags.addAll(tags.readTags(openFile));
                }
                return new MapLibraryService.Result(Map.of(), null);
            }, result -> {
                if (affected != null && project == openProject) {
                    boolean clean = !hasUnsavedChanges();
                    project.getMap().setTags(new ArrayList<>(liveTags));
                    if (clean) {
                        savedFingerprint = fingerprintOrNull(project);
                    }
                }
                mapBrowser.refresh();
                status("Updated tags for " + (maps.size() == 1 ? maps.getFirst().name() : maps.size() + " maps") + ".");
            });
        });
    }

    private void installDmInteractions() {
        viewportTitleTooltip = Icons.tooltip("Drag the Player view title bar to move the player viewport. "
                + "Hold near a map edge to scroll across the map.");
        dmCanvas.setFocusTraversable(true);
        dmCanvas.setOnKeyPressed(this::handleNudgePressed);
        dmCanvas.setOnKeyReleased(event -> {
            if (nudgeArrowKeys.remove(event.getCode())) {
                event.consume();
                if (nudgeArrowKeys.isEmpty()) {
                    finishNudge();
                }
            }
        });
        dmCanvas.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                finishNudge();
                finishPlayerViewportDrag();
            }
        });
        dmCanvas.sceneProperty().addListener((obs, oldScene, scene) -> {
            if (scene != null) {
                installNudgeFocusListener(scene);
                installRoomPreviewKeyFilter(scene);
            }
        });
        if (dmCanvas.getScene() != null) {
            installNudgeFocusListener(dmCanvas.getScene());
            installRoomPreviewKeyFilter(dmCanvas.getScene());
        }
        dmCanvas.addEventFilter(javafx.scene.input.MouseEvent.ANY, event ->
                roomHidePreview = event.isShiftDown());
        dmCanvas.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> {
            finishNudge();
            canvasMouseDown = true;
        });
        dmCanvas.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_RELEASED, event ->
                canvasMouseDown = event.isPrimaryButtonDown() || event.isSecondaryButtonDown() || event.isMiddleButtonDown());
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
            finishNudge();
            if (event.isControlDown()) {
                if (event.getDeltaY() != 0 && playerZoomSlider != null) {
                    double delta = Math.log(Tuning.PLAYER_ZOOM_WHEEL_FACTOR.get()) / Math.log(2);
                    double oldStep = playerZoomSlider.getValue();
                    CanvasMapRenderer.WorldRect box = getPlayerViewportRect();
                    CanvasMapRenderer.WorldPoint cursor = renderer.screenToWorld(
                            event.getX(), event.getY(), dmCanvas.getWidth(), dmCanvas.getHeight(),
                            project.getViews().getDmCamera());
                    playerZoomSlider.setValue(oldStep + (event.getDeltaY() > 0 ? delta : -delta));
                    double ratio = Math.pow(2, oldStep - playerZoomSlider.getValue());
                    boolean inside = box != null && cursor.x() >= box.x() && cursor.x() <= box.x() + box.width()
                            && cursor.y() >= box.y() && cursor.y() <= box.y() + box.height();
                    if (inside) {
                        // Keep the world point under the cursor at the same spot inside the box.
                        DmProject.CameraState playerCamera = project.getViews().getPlayerCamera();
                        double anchoredX = cursor.x() + (playerCamera.getX() - cursor.x()) * ratio;
                        double anchoredY = cursor.y() + (playerCamera.getY() - cursor.y()) * ratio;
                        // When zooming in, also pull the box centre towards the cursor so the target ends up centred.
                        double pull = ratio < 1 ? Tuning.PLAYER_ZOOM_CURSOR_PULL.get() : 0;
                        playerCamera.setX(anchoredX + (cursor.x() - anchoredX) * pull);
                        playerCamera.setY(anchoredY + (cursor.y() - anchoredY) * pull);
                    }
                }
                return;
            }
            if (event.isAltDown()) {
                event.consume();
                if (event.getDeltaY() == 0 || fogDragging
                        || draftOverlay != null || pingArmed || laserActive || laserToolActive) {
                    return;
                }
                if (activeTool == EditorTool.SELECT) {
                    if (interactionInProgress() || draggingGroup || marqueeActive) {
                        return;
                    }
                    java.util.List<String> ids = selectedLightIds();
                    if (ids.isEmpty()) {
                        return;
                    }
                    double cell = project.getMap().getGrid().getPixelsPerCell();
                    java.util.List<DmProject.LightSource> before = new java.util.ArrayList<>();
                    java.util.List<DmProject.LightSource> after = new java.util.ArrayList<>();
                    double minRadius = Double.POSITIVE_INFINITY;
                    double maxRadius = Double.NEGATIVE_INFINITY;
                    for (String id : ids) {
                        DmProject.LightSource light = findLightById(id);
                        double radius = Math.max(cell, light.getRange() + Math.copySign(cell, event.getDeltaY()));
                        minRadius = Math.min(minRadius, radius);
                        maxRadius = Math.max(maxRadius, radius);
                        if (!same(radius, light.getRange())) {
                            before.add(cloneLight(light));
                            DmProject.LightSource changed = cloneLight(light);
                            changed.setRange(radius);
                            after.add(changed);
                        }
                    }
                    if (!after.isEmpty()) {
                        executeWithFogHistory("Change light range",
                                () -> after.forEach(this::applyLightState),
                                () -> before.forEach(this::applyLightState));
                        status("Change light range.");
                    }
                    updateHover(event.getX(), event.getY());
                    String label = ids.size() == 1
                            ? String.format(Locale.ROOT, "Light radius: %.1f tiles", minRadius / cell)
                            : String.format(Locale.ROOT, "Light radii: %.1f-%.1f tiles (%d lights)",
                                    minRadius / cell, maxRadius / cell, ids.size());
                    showSizeLabel(label, event.getX(), event.getY());
                    renderDm();
                    return;
                }
                if (!activeTool.supportsBrushSize()) {
                    return;
                }
                double size = Math.round((brushSize.get() + Math.copySign(0.1, event.getDeltaY())) * 1e10) / 1e10;
                brushSize.set(clamp(size, Math.min(Tuning.BRUSH_MIN.get(), Tuning.BRUSH_MAX.get()),
                        Math.max(Tuning.BRUSH_MIN.get(), Tuning.BRUSH_MAX.get())));
                updateHover(event.getX(), event.getY());
                showSizeLabel(String.format(Locale.ROOT, "Brush: %.1f tiles", brushSize.get()),
                        event.getX(), event.getY());
                renderDm();
                return;
            }
            DmProject.CameraState camera = project.getViews().getDmCamera();
            double wheel = Tuning.DM_ZOOM_WHEEL_FACTOR.get();
            double factor = event.getDeltaY() > 0 ? wheel : 1 / wheel;
            double newZoom = clamp(camera.getZoom() * factor, Math.min(Tuning.DM_ZOOM_MIN.get(), Tuning.DM_ZOOM_MAX.get()),
                    Math.max(Tuning.DM_ZOOM_MIN.get(), Tuning.DM_ZOOM_MAX.get()));
            CanvasMapRenderer.WorldPoint before = renderer.screenToWorld(
                    event.getX(), event.getY(), dmCanvas.getWidth(), dmCanvas.getHeight(), camera);
            camera.setZoom(newZoom);
            CanvasMapRenderer.WorldPoint after = renderer.screenToWorld(
                    event.getX(), event.getY(), dmCanvas.getWidth(), dmCanvas.getHeight(), camera);
            camera.setX(camera.getX() + (before.x() - after.x()));
            camera.setY(camera.getY() + (before.y() - after.y()));
            if (activeTool == EditorTool.WALL_DRAW) {
                updateHover(event.getX(), event.getY());
            }
        });

        dmCanvas.setOnMouseMoved(event -> {
            updateHover(event.getX(), event.getY());
            showViewportTitleTooltip(activeTool == EditorTool.SELECT
                    && isOnPlayerViewportTitleBar(hoverWorldX, hoverWorldY));
            brushLabelCursorX = event.getX();
            brushLabelCursorY = event.getY();
            positionBrushSizeLabel();
            if (laserToolActive) {
                addLaserPoint(hoverWorldX, hoverWorldY);
            }
            updateCanvasCursor();
        });
        dmCanvas.setOnMouseExited(event -> {
            showViewportTitleTooltip(false);
            hoverInsideCanvas = false;
            geometryPreviewHover = false;
            roomPreview = null;
            hideBrushSizeLabel();
            if (laserToolActive && !event.isPrimaryButtonDown()) {
                laserTrail.clear();
            }
        });

        // Runs after the press/release handlers below have updated the drag state.
        dmCanvas.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> {
            // Take keyboard focus away from the map tree so Delete/F2 act on the canvas selection.
            dmCanvas.requestFocus();
            Platform.runLater(this::updateCanvasCursor);
        });
        dmCanvas.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_RELEASED, event -> Platform.runLater(this::updateCanvasCursor));

        dmCanvas.setOnMousePressed(event -> {
            hideBrushSizeLabel();
            commitTextEdit();
            lastMouseX = event.getX();
            lastMouseY = event.getY();
            DmProject.CameraState camera = project.getViews().getDmCamera();
            CanvasMapRenderer.WorldPoint world = renderer.screenToWorld(
                    event.getX(), event.getY(), dmCanvas.getWidth(), dmCanvas.getHeight(), camera);

            if (event.getButton() == MouseButton.SECONDARY) {
                finishPlayerViewportDrag();
                rightClickCancelCandidate = pingArmed || laserToolActive || activeTool != EditorTool.SELECT;
                rightPressScreenX = event.getX();
                rightPressScreenY = event.getY();
                DmProject.Interactable portal = rightClickCancelCandidate ? null : pickInteractableForClick(world.x(), world.y());
                if (portal != null) {
                    showInteractableContextMenu(portal, event.getScreenX(), event.getScreenY());
                    return;
                }
                DmProject.LightSource light = rightClickCancelCandidate ? null : pickNearestLight(world.x(), world.y(), Tuning.LIGHT_PICK_RADIUS.get() / Math.max(0.01, camera.getZoom()));
                if (light != null) {
                    selectedLight = light;
                    selectedLayer = null;
                    showLightContextMenu(light, event.getScreenX(), event.getScreenY());
                    return;
                }
                DmProject.TextBox textBox = rightClickCancelCandidate ? null : pickTextBox(world.x(), world.y());
                if (textBox != null) {
                    selectedTextId = textBox.getId();
                    selectedOverlayId = null;
                    selectedLayer = null;
                    selectedLight = null;
                    syncTextControls(textBox);
                    showTextContextMenu(textBox, event.getScreenX(), event.getScreenY());
                    return;
                }
                DmProject.OverlayShape shape = rightClickCancelCandidate ? null : pickOverlay(world.x(), world.y(), camera.getZoom());
                if (shape != null) {
                    selectedOverlayId = shape.getId();
                    selectedLayer = null;
                    selectedLight = null;
                    syncOverlayControls(shape);
                    showOverlayContextMenu(shape, event.getScreenX(), event.getScreenY());
                    return;
                }
                panningDmCamera = true;
                startCameraX = camera.getX();
                startCameraY = camera.getY();
                return;
            }

            if (event.getButton() == MouseButton.MIDDLE) {
                laserActive = true;
                addLaserPoint(world.x(), world.y());
                return;
            }

            if (event.getButton() != MouseButton.PRIMARY) {
                return;
            }

            if (laserToolActive) {
                addLaserPoint(world.x(), world.y());
                return;
            }

            if (pingArmed) {
                addPing(world.x(), world.y());
                setPingArmed(false);
                status("Ping placed.");
                return;
            }

            if (activeTool.isLightPlaceTool()) {
                LightPreset preset = LightPreset.forTool(activeTool);
                addLightAt(world.x(), world.y(), preset);
                setActiveTool(EditorTool.SELECT);
                String name = activeTool == EditorTool.LIGHT_ADD ? "torch" : activeTool.label.toLowerCase(Locale.ROOT);
                status("Added " + name + ". Drag it to move; right-click for range, flicker, color and fog reveal.");
                return;
            }

            if (activeTool == EditorTool.REVEAL_ROOM) {
                revealRoomAt(world.x(), world.y(), !event.isShiftDown());
                return;
            }
            if (activeTool == EditorTool.ROOM_LABEL) {
                placeRoomLabel(world.x(), world.y());
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

            if (activeTool == EditorTool.TEXT) {
                pressWithTextTool(world.x(), world.y());
                return;
            }

            if (activeTool == EditorTool.WALL_DRAW) {
                updateHover(event.getX(), event.getY());
                placeWallPoint(world.x(), world.y(), event.isShiftDown());
                return;
            }

            if (activeTool.isSegmentDrawTool()) {
                double[] p = snapWallPoint(world.x(), world.y(), event.isShiftDown());
                draftWall = DmProject.WallSegment.builder().x1(p[0]).y1(p[1]).x2(p[0]).y2(p[1]).build();
                return;
            }

            if (activeTool == EditorTool.WALL_ERASE) {
                eraseWallAt(world.x(), world.y(), camera.getZoom());
                return;
            }

            // The viewport grab bar sits on top of everything else, like a window title bar.
            if (isOnPlayerViewportResetButton(world.x(), world.y())) {
                resetPlayerZoom();
                return;
            }
            if (isOnPlayerViewportTitleBar(world.x(), world.y())) {
                CanvasMapRenderer.WorldRect playerRect = getPlayerViewportRect();
                draggingPlayerViewport = true;
                viewportDragSceneX = event.getSceneX();
                viewportDragSceneY = event.getSceneY();
                viewportScrollNanos = 0;
                showViewportTitleTooltip(false);
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
                        && nowNanos - lastDoorToggleNanos < (Tuning.DOOR_DEBOUNCE_MS.get() * 1_000_000L);
                if (!chatter) {
                    toggleInteractable(door);
                    lastDoorToggleId = door.getId();
                    lastDoorToggleNanos = nowNanos;
                }
                return;
            }

            if (pressGroupSelection(world.x(), world.y(), camera.getZoom(), event.isControlDown())) {
                return;
            }

            selectedLight = pickNearestLight(world.x(), world.y(), Tuning.LIGHT_PICK_RADIUS.get() / Math.max(0.01, camera.getZoom()));
            if (selectedLight != null) {
                draggingLight = true;
                dragOffsetX = world.x() - selectedLight.getX();
                dragOffsetY = world.y() - selectedLight.getY();
                startLightX = selectedLight.getX();
                startLightY = selectedLight.getY();
                lightDragFogBefore = snapshotFog();
                selectedLayer = null;
                selectedOverlayId = null;
                selectedTextId = null;
                return;
            }

            if (pressTextWithSelectTool(world.x(), world.y(), camera.getZoom(), event.getClickCount())) {
                return;
            }
            selectedTextId = null;

            DmProject.OverlayShape handleOverlay = findOverlay(selectedOverlayId);
            if (isOnOverlayHandle(handleOverlay, world.x(), world.y(), camera.getZoom())) {
                selectedLayer = null;
                selectedLight = null;
                resizingOverlay = true;
                overlayDragBefore = cloneOverlay(handleOverlay);
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

            selectedLayer = isImageLayerLocked() ? null : pickTopmostLayer(world.x(), world.y());
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
            if (handleDistance < Tuning.LAYER_HANDLE_RADIUS.get() / Math.max(0.01, camera.getZoom())) {
                resizingLayer = true;
            } else {
                draggingLayer = true;
                dragOffsetX = world.x() - selectedLayer.getX();
                dragOffsetY = world.y() - selectedLayer.getY();
            }
        });

        dmCanvas.setOnMouseDragged(event -> {
            if (draggingPlayerViewport) {
                if (!event.isPrimaryButtonDown()) {
                    finishPlayerViewportDrag();
                    return;
                }
                viewportDragSceneX = event.getSceneX();
                viewportDragSceneY = event.getSceneY();
                if (playerViewportScrollVelocity().magnitude() == 0) {
                    viewportScrollNanos = 0;
                }
                movePlayerViewportToCursor(event.getX(), event.getY());
                return;
            }
            updateHover(event.getX(), event.getY());
            DmProject.CameraState camera = project.getViews().getDmCamera();
            CanvasMapRenderer.WorldPoint world = renderer.screenToWorld(
                    event.getX(), event.getY(), dmCanvas.getWidth(), dmCanvas.getHeight(), camera);

            if (laserToolActive && !panningDmCamera) {
                addLaserPoint(world.x(), world.y());
                return;
            }

            if (laserActive) {
                if (event.isMiddleButtonDown()) {
                    addLaserPoint(world.x(), world.y());
                }
                return;
            }

            if (panningDmCamera) {
                double dxScreen = event.getX() - lastMouseX;
                double dyScreen = event.getY() - lastMouseY;
                camera.setX(camera.getX() - dxScreen / camera.getZoom());
                camera.setY(camera.getY() - dyScreen / camera.getZoom());
                lastMouseX = event.getX();
                lastMouseY = event.getY();
                return;
            }

            if (marqueeActive) {
                marqueeCurrentX = world.x();
                marqueeCurrentY = world.y();
                return;
            }

            if (draggingGroup) {
                moveGroupBy(world.x() - groupLastX, world.y() - groupLastY);
                groupLastX = world.x();
                groupLastY = world.y();
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
                if (activeTool == EditorTool.WALL_DRAW) {
                    return;
                }
                double[] p = snapWallPoint(world.x(), world.y(), event.isShiftDown());
                draftWall.setX2(p[0]);
                draftWall.setY2(p[1]);
                return;
            }

            if (draftText != null) {
                updateTextDraft(world.x(), world.y());
                return;
            }

            if (draggingText) {
                DmProject.TextBox moving = findTextBox(selectedTextId);
                if (moving != null) {
                    if (!same(world.x(), textLastX) || !same(world.y(), textLastY)) {
                        moving.setRoomLabelAnchored(false);
                    }
                    moving.setX(moving.getX() + world.x() - textLastX);
                    moving.setY(moving.getY() + world.y() - textLastY);
                }
                textLastX = world.x();
                textLastY = world.y();
                return;
            }

            if (resizingTextHandle >= 0) {
                DmProject.TextBox resizing = findTextBox(selectedTextId);
                if (resizing != null && textDragBefore != null) {
                    resizeText(resizing, textDragBefore, resizingTextHandle, world.x(), world.y());
                }
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

            if (resizingOverlay) {
                DmProject.OverlayShape resizing = findOverlay(selectedOverlayId);
                if (resizing != null && overlayDragBefore != null) {
                    resizeOverlay(resizing, overlayDragBefore, world.x(), world.y());
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
            if (activeTool == EditorTool.WALL_DRAW) {
                updateHover(event.getX(), event.getY());
            }
            if (event.getButton() == MouseButton.PRIMARY) {
                finishPlayerViewportDrag();
            }
            if (event.getButton() == MouseButton.MIDDLE) {
                laserActive = false;
                return;
            }
            if (event.getButton() == MouseButton.SECONDARY && rightClickCancelCandidate) {
                rightClickCancelCandidate = false;
                if (panningDmCamera && Math.hypot(event.getX() - rightPressScreenX, event.getY() - rightPressScreenY)
                        <= Tuning.RIGHT_CLICK_MAX_MOVE.get()) {
                    DmProject.CameraState camera = project.getViews().getDmCamera();
                    camera.setX(startCameraX);
                    camera.setY(startCameraY);
                    panningDmCamera = false;
                    cancelActiveTool();
                    return;
                }
            }
            if (marqueeActive) {
                marqueeActive = false;
                selectInMarquee();
                return;
            }
            if (draggingGroup) {
                finishGroupDrag();
                return;
            }

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

            if (draftWall != null && activeTool != EditorTool.WALL_DRAW) {
                finishWallDraw();
                return;
            }

            if (draftText != null) {
                finishTextDraft();
                return;
            }

            if (draggingText || resizingTextHandle >= 0) {
                DmProject.TextBox changed = findTextBox(selectedTextId);
                if (changed != null && textDragBefore != null && !textDragBefore.equals(changed)) {
                    recordTextChange(draggingText ? "Move text" : "Resize text", changed.getId(), textDragBefore, cloneText(changed));
                }
                draggingText = false;
                resizingTextHandle = -1;
                textDragBefore = null;
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

            if (resizingOverlay) {
                DmProject.OverlayShape resized = findOverlay(selectedOverlayId);
                if (resized != null && overlayDragBefore != null && !overlayDragBefore.equals(resized)) {
                    recordOverlayChange("Resize effect", resized.getId(), overlayDragBefore, cloneOverlay(resized));
                }
                resizingOverlay = false;
                overlayDragBefore = null;
                return;
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
        playerBaseCanvas = new Canvas(1280, 720);
        playerBaseCanvas.setMouseTransparent(true);
        playerBaseState = new CanvasMapRenderer.BaseLayerState();
        playerBrightCoreCanvas = new Canvas(1280, 720);
        playerBrightCoreCanvas.setMouseTransparent(true);
        playerBrightCoreCanvas.setBlendMode(BlendMode.ADD);
        playerAmbientCanvas = new Canvas(1280, 720);
        playerAmbientCanvas.setMouseTransparent(true);
        playerAmbientCanvas.setBlendMode(BlendMode.MULTIPLY);
        playerCanvas = new Canvas(1280, 720);
        playerFogCanvas = new Canvas(1280, 720);
        playerFogCanvas.setMouseTransparent(true);
        StackPane root = new StackPane(playerBaseCanvas, playerBrightCoreCanvas, playerAmbientCanvas, playerCanvas, playerFogCanvas);
        playerBaseCanvas.widthProperty().bind(root.widthProperty());
        playerBaseCanvas.heightProperty().bind(root.heightProperty());
        playerBrightCoreCanvas.widthProperty().bind(root.widthProperty());
        playerBrightCoreCanvas.heightProperty().bind(root.heightProperty());
        playerAmbientCanvas.widthProperty().bind(root.widthProperty());
        playerAmbientCanvas.heightProperty().bind(root.heightProperty());
        playerCanvas.widthProperty().bind(root.widthProperty());
        playerCanvas.heightProperty().bind(root.heightProperty());
        playerFogCanvas.widthProperty().bind(root.widthProperty());
        playerFogCanvas.heightProperty().bind(root.heightProperty());
        playerTransition = new PlayerViewTransition(root);
        Scene scene = new Scene(root, 1280, 720, Color.BLACK);

        playerStage = new Stage(StageStyle.UNDECORATED);
        // An owned window gets no taskbar button of its own on Windows
        playerStage.initOwner(primaryStage);
        playerStage.setTitle("Player View");
        playerStage.getIcons().setAll(appIcons());
        playerStage.setScene(scene);
        playerStage.setOnHidden(event -> {
            finishPlayerViewportDrag();
            playerTransition.cancel();
            playerTransition = null;
            playerStage = null;
            playerCanvas = null;
            playerBaseCanvas = null;
            playerBrightCoreCanvas = null;
            playerAmbientCanvas = null;
            playerFogCanvas = null;
            syncPlayerWindowToggle();
            updatePlayerOutputStatus();
        });

        Screen target = resolveSelectedPlayerScreen();
        Rectangle2D bounds = target.getBounds();
        playerStage.setX(bounds.getMinX());
        playerStage.setY(bounds.getMinY());
        playerStage.setWidth(bounds.getWidth());
        playerStage.setHeight(bounds.getHeight());
        playerStage.show();
        syncPlayerWindowToggle();
        updatePlayerOutputStatus();
        status("Player window opened.");
    }

    private void openHandoutWindow(Stage owner) {
        if (handoutWindow == null) {
            handoutWindow = new HandoutWindow(owner, appIcons(), () -> playerStage != null,
                    () -> lastInputNanos = System.nanoTime(), () -> lastInputNanos = System.nanoTime());
        }
        handoutWindow.show();
    }

    private void syncPlayerWindowToggle() {
        if (handoutWindow != null) {
            handoutWindow.playerWindowChanged();
        }
        if (playerWindowToggle != null) {
            playerWindowToggle.setSelected(playerStage != null);
        }
        updatePlayerOutputStatus();
    }

    private void closePlayerWindow() {
        finishPlayerViewportDrag();
        if (playerStage != null) {
            playerStage.close();
            playerStage = null;
            playerCanvas = null;
            playerBaseCanvas = null;
            playerBrightCoreCanvas = null;
            playerAmbientCanvas = null;
            playerFogCanvas = null;
        }
        syncPlayerWindowToggle();
    }

    private void addLaserPoint(double worldX, double worldY) {
        laserTrail.add(new CanvasMapRenderer.LaserPoint(worldX, worldY, System.currentTimeMillis()));
    }

    /** Drops expired trail points; while the button is held the newest point stays so the dot remains visible. */
    private void pruneLaserTrail() {
        long now = System.currentTimeMillis();
        if ((laserActive || laserToolActive) && !laserTrail.isEmpty()) {
            CanvasMapRenderer.LaserPoint last = laserTrail.getLast();
            if (now - last.millis() > 50) {
                laserTrail.set(laserTrail.size() - 1,
                        new CanvasMapRenderer.LaserPoint(last.x(), last.y(), now));
            }
        }
        laserTrail.removeIf(p -> now - p.millis() > CanvasMapRenderer.laserTrailMillis());
    }

    private void drawLaser(GraphicsContext gc, Canvas canvas, DmProject.CameraState camera, double dotRadius) {
        if (laserTrail.isEmpty()) {
            return;
        }
        renderer.drawLaser(gc, laserTrail, laserActive || laserToolActive, dotRadius, canvas.getWidth(), canvas.getHeight(), camera);
    }

    private void renderDm() {
        if (nudgeStartPositions != null && !nudgeStartPositions.keySet().equals(selectedMovementKeys())) {
            finishNudge();
        }
        double playerZoomStep = project.getViews().getPlayerZoomStep();
        renderer.setViewportZoom(formatPlayerZoom(playerZoomStep), Math.abs(playerZoomStep) > 1e-9);
        renderer.renderBase(dmBaseCanvas.getGraphicsContext2D(), dmBaseState, project, projectFile,
                dmCanvas.getWidth(), dmCanvas.getHeight(), project.getViews().getDmCamera());
        renderer.renderBrightCore(dmBrightCoreCanvas.getGraphicsContext2D(), project, dmCanvas.getWidth(),
                dmCanvas.getHeight(), project.getViews().getDmCamera(), false);
        renderer.renderAmbientLight(dmAmbientCanvas.getGraphicsContext2D(), project, dmCanvas.getWidth(),
                dmCanvas.getHeight(), project.getViews().getDmCamera(), false);
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
        long toolsStart = FrameProfiler.start();
        pruneLaserTrail();
        drawLaser(fogGc, dmFogCanvas, project.getViews().getDmCamera(), Tuning.LASER_DM_DOT.get());
        drawSelectionHandle(fogGc);
        updateEffectStyleControls();
        updateSelectionControls();
        drawOverlaySelection(fogGc);
        drawTextSelection(fogGc);
        drawGroupSelection(fogGc);
        drawToolPreview(fogGc);
        historyFeedback.draw(fogGc, dmCanvas.getWidth(), dmCanvas.getHeight(),
                project.getViews().getDmCamera(), System.nanoTime());
        updateTextEditorPlacement();
        FrameProfiler.lap("dm tools/ui", toolsStart);
    }

    private void renderPlayer() {
        if (playerCanvas == null || playerStage == null) {
            return;
        }
        GraphicsContext gc = playerCanvas.getGraphicsContext2D();
        if (handoutWindow != null && handoutWindow.isShownToPlayers()) {
            playerTransition.cancel();
            playerFogCanvas.getGraphicsContext2D().clearRect(0, 0, playerFogCanvas.getWidth(), playerFogCanvas.getHeight());
            playerBrightCoreCanvas.getGraphicsContext2D().clearRect(0, 0, playerBrightCoreCanvas.getWidth(), playerBrightCoreCanvas.getHeight());
            playerAmbientCanvas.getGraphicsContext2D().clearRect(0, 0, playerAmbientCanvas.getWidth(), playerAmbientCanvas.getHeight());
            gc.setFill(Color.BLACK);
            gc.fillRect(0, 0, playerCanvas.getWidth(), playerCanvas.getHeight());
            HandoutWindow.drawBoard(gc, handoutWindow.getImages(), handoutWindow.getRotation(),
                    playerCanvas.getWidth(), playerCanvas.getHeight(), handoutWindow.isMirrored());
            return;
        }
        boolean frozen = frozenPlayerProject != null;
        DmProject shown = frozen ? frozenPlayerProject : project;
        CanvasMapRenderer playerView = frozen ? playerRenderer : renderer;
        playerView.renderBase(playerBaseCanvas.getGraphicsContext2D(), playerBaseState, shown,
                frozen ? frozenPlayerProjectFile : projectFile, playerCanvas.getWidth(), playerCanvas.getHeight(),
                getEffectivePlayerCamera(), showPlayerGrid
                        ? CanvasMapRenderer.GridMode.OVERLAY : CanvasMapRenderer.GridMode.HIDDEN);
        playerView.renderBrightCore(playerBrightCoreCanvas.getGraphicsContext2D(), shown, playerCanvas.getWidth(),
                playerCanvas.getHeight(), getEffectivePlayerCamera(), true);
        playerView.renderAmbientLight(playerAmbientCanvas.getGraphicsContext2D(), shown, playerCanvas.getWidth(),
                playerCanvas.getHeight(), getEffectivePlayerCamera(), true);
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
        if (!frozen) {
            drawLaser(playerFogCanvas.getGraphicsContext2D(), playerFogCanvas, getEffectivePlayerCamera(),
                    Math.max(Tuning.LASER_PLAYER_DOT_MIN.get(), Tuning.LASER_PLAYER_DOT_INCHES.get() * playerPixelsPerInch()));
        }
        if (showScaleTestSquare) {
            drawScaleTestSquare(playerFogCanvas.getGraphicsContext2D());
        }
        playerTransition.frameRendered();
    }

    private void capturePlayerTransition() {
        if (playerTransition != null && (handoutWindow == null || !handoutWindow.isShownToPlayers())) {
            playerTransition.capture();
            lastInputNanos = System.nanoTime();
        }
    }

    private void drawSelectionHandle(GraphicsContext gc) {
        if (selectedLayer == null || isImageLayerLocked()) {
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

    /** True when the map has flickering lights or moving effect textures and animations are enabled. */
    private static boolean hasAnimation(DmProject candidate) {
        boolean animated = CanvasMapRenderer.effectAnimationsOn();
        boolean flicker = CanvasMapRenderer.flickerOn(candidate);
        if (animated && candidate.getWeather() != null && WeatherType.from(candidate.getWeather().getType()) != WeatherType.NONE) {
            return true;
        }
        return candidate.getOverlays().stream().anyMatch(o -> animated && OverlayTextures.isMoving(o.getTexture())
                || flicker && o.isEmitsLight() && OverlayTextures.lightFlicker(o.getTexture()) > 0)
                || flicker && candidate.getLighting().getLights().stream().anyMatch(l -> l.isEnabled()
                && l.getFlicker() != null && l.getFlicker().isEnabled() && l.getFlicker().getStrength() > 0);
    }

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
                || !candidate.getTextBoxes().isEmpty()
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
        chooser.setTitle("Select DD2VTT maps");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Universal VTT", "*.dd2vtt", "*.uvtt"));
        applyInitialImportDirectory(chooser);
        List<File> selected = chooser.showOpenMultipleDialog(primaryStage);
        if (selected == null || selected.isEmpty()) {
            return;
        }
        rememberImportDirectory(selected.get(0).toPath().getParent());
        List<Path> sources = filterOutDuplicates(primaryStage, selected.stream().map(File::toPath).toList());
        if (sources == null) {
            return;
        }
        if (sources.isEmpty()) {
            status("Nothing to import: every selected file is already in the library.");
            return;
        }
        if (sources.size() > 1) {
            importBatch(sources, suggestedFolder);
            return;
        }
        Path sourcePath = sources.get(0);
        String sourceName = sourcePath.getFileName().toString();

        Optional<MapLocationDialog.Selection> selection = MapLocationDialog.show(primaryStage, mapLibrary,
                "Import map", MaterialDesignF.FILE_IMPORT_OUTLINE, "Import",
                MapLibraryService.stripExtension(sourceName), suggestedFolder);
        if (selection.isEmpty()) {
            return;
        }
        MapLocationDialog.Selection target = selection.get();
        leaveCurrentMap(() -> {
            if (ioBusy) {
                status("Still working on the previous file operation.");
                return;
            }
            ImagePyramidStore.Viewport dm = imageViewport(dmBaseCanvas);
            ImagePyramidStore.Viewport player = imageViewport(playerBaseCanvas);
            setMapLoading(true, "Importing " + sourceName + "...");
            runInBackground("Importing " + sourceName + "...", "Import failed: ", () -> {
                Path targetPath = mapLibrary.newMapFile(target.folder(), target.name());
                Path projectDir = targetPath.getParent();
                Files.createDirectories(projectDir);
                try {
                    DmProject imported = dd2vttImportService.importToProject(sourcePath, projectDir);
                    new MapTagService(mapLibrary, projectService).applyKnownTags(imported, sourcePath);
                    projectService.save(targetPath, imported);
                    return prepareLoadedProject(imported, targetPath, null, dm, player);
                } catch (IOException | RuntimeException ex) {
                    try {
                        MapLibraryService.deleteRecursive(projectDir);
                    } catch (IOException ignored) {
                    }
                    throw ex;
                }
            }, loaded -> {
                try {
                    ImagePyramidStore.shared().adopt(loaded.images());
                    switchProject(loaded.project(), loaded.file());
                    mapBrowser.refresh();
                    mapBrowser.select(loaded.file());
                    status("Imported " + sourceName + " as " + MapBrowser.displayName(loaded.file()) + ".");
                } finally {
                    setMapLoading(false, null);
                }
            }, ex -> setMapLoading(false, null));
        });
    }

    /**
     * Compares {@code sources} against the library's original file names (see {@link DuplicateCheckService}) and,
     * if any match, applies {@code import.duplicateBehavior}: {@code always}/{@code never} silently keep/drop every
     * duplicate, {@code ask} (default) shows {@link DuplicateMapsDialog} to decide which of them to still import.
     * Returns the filtered list (non-duplicates are always kept), or {@code null} if the user cancelled the whole
     * import.
     */
    private List<Path> filterOutDuplicates(javafx.stage.Window owner, List<Path> sources) {
        if (duplicateCheckService == null || sources.isEmpty()) {
            return sources;
        }
        List<Path> duplicates;
        try {
            duplicates = duplicateCheckService.findDuplicates(sources);
        } catch (IOException ex) {
            return sources;
        }
        if (duplicates.isEmpty()) {
            return sources;
        }
        String behavior = Tuning.IMPORT_DUPLICATE_BEHAVIOR.get();
        if ("always".equalsIgnoreCase(behavior)) {
            return sources;
        }
        if ("never".equalsIgnoreCase(behavior)) {
            Set<Path> duplicateSet = new HashSet<>(duplicates);
            return sources.stream().filter(source -> !duplicateSet.contains(source)).toList();
        }
        Optional<DuplicateMapsDialog.Result> decision = DuplicateMapsDialog.show(owner, duplicates);
        if (decision.isEmpty()) {
            return null;
        }
        Set<Path> accepted = decision.get().accepted();
        List<Path> filtered = new ArrayList<>();
        for (Path source : sources) {
            if (!duplicates.contains(source) || accepted.contains(source)) {
                filtered.add(source);
            }
        }
        return filtered;
    }

    private void handleImportDd2vttFolder(Path suggestedFolder) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select a folder with DD2VTT maps");
        applyInitialImportDirectory(chooser);
        File folder = chooser.showDialog(primaryStage);
        if (folder == null) {
            return;
        }
        rememberImportDirectory(folder.toPath());
        List<Path> maps;
        try {
            maps = BatchImportService.findMaps(folder.toPath());
        } catch (IOException ex) {
            status("Could not read the folder: " + ex.getMessage());
            return;
        }
        if (maps.isEmpty()) {
            Dialogs.error(primaryStage, "No maps found", "The folder \"" + folder.getName()
                    + "\" (including its sub-folders) contains no .dd2vtt or .uvtt files.");
            return;
        }
        List<Path> filtered = filterOutDuplicates(primaryStage, maps);
        if (filtered == null) {
            return;
        }
        if (filtered.isEmpty()) {
            status("Nothing to import: every map found is already in the library.");
            return;
        }
        importBatch(filtered, suggestedFolder, folder.toPath());
    }

    private void importBatch(List<Path> sources, Path suggestedFolder) {
        importBatch(sources, suggestedFolder, null);
    }

    /**
     * {@code sourceRoot} is the chosen folder for a folder import (its sub-folder structure is re-created in the
     * library, empty sub-folders are skipped), or {@code null} for a flat multi-file selection.
     */
    private void importBatch(List<Path> sources, Path suggestedFolder, Path sourceRoot) {
        Optional<Path> target = MapLocationDialog.showFolder(primaryStage, mapLibrary,
                "Import " + sources.size() + " maps", MaterialDesignF.FILE_IMPORT_OUTLINE, "Import", suggestedFolder);
        if (target.isEmpty()) {
            return;
        }
        BatchImportService batch = new BatchImportService(mapLibrary, projectService, dd2vttImportService);
        runInBackground("Importing " + sources.size() + " maps...", "Import failed: ", () -> batch.importAll(
                sources, target.get(), sourceRoot,
                (index, total, name) -> Platform.runLater(() -> status("Importing " + index + "/" + total + ": " + name + "..."))),
                result -> {
                    mapBrowser.refresh();
                    if (!result.imported().isEmpty()) {
                        mapBrowser.select(result.imported().get(0));
                    }
                    int multiLevel = (int) result.imported().stream().filter(MultiLevelService::isMultiLevelFile).count();
                    int failed = result.failures().size();
                    int importedFiles = result.total() - failed;
                    status("Imported " + importedFiles + " of " + result.total() + " maps"
                            + (multiLevel == 0 ? "." : " (" + multiLevel + (multiLevel == 1 ? " multilevel map" : " multilevel maps")
                            + " from files numbered like levels)."));
                    if (!result.failures().isEmpty()) {
                        StringBuilder text = new StringBuilder();
                        for (BatchImportService.Failure failure : result.failures()) {
                            text.append(failure.source().getFileName()).append(": ").append(failure.reason()).append('\n');
                        }
                        Dialogs.error(primaryStage, result.failures().size() + " maps could not be imported",
                                text.toString().trim());
                    }
                });
    }

    private void openMapFromLibrary(Path file) {
        Path open = openMapFile();
        if (open != null && file.toAbsolutePath().normalize().equals(open.toAbsolutePath().normalize())) {
            status("That map is already open.");
            return;
        }
        if (apiActionRunning) {
            commitTextEdit();
            if (projectFile == null && hasContent(project)) {
                throw new LocalApiServer.ApiException(409, "Save the new map before switching via the API.");
            }
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
        if (apiActionRunning && !ioBusy) {
            throw new LocalApiServer.ApiException(500, "Map switch could not be started; see the DM status bar.");
        }
    }

    private void handleSave() {
        finishNudge();
        if (projectFile == null) {
            saveNewMap(null);
        } else {
            saveCurrentThen(null);
        }
    }

    private void saveCurrentThen(Runnable next) {
        SaveTarget target = currentSaveTarget();
        DmProject savedProject = project;
        DmProject snapshot;
        long version = historyVersion;
        try {
            snapshot = projectService.copy(project);
        } catch (IOException ex) {
            status("Save failed: " + ex.getMessage());
            return;
        }
        runInBackground("Saving...", "Save failed: ", () -> {
            saveTo(target, snapshot);
            return target;
        }, saved -> {
            if (project == savedProject) {
                adoptCopiedAssetPaths(snapshot);
                markSaved(snapshot, version);
            }
            mapBrowser.invalidateThumbnails();
            status("Saved " + saved.displayName() + ".");
            if (next != null) {
                next.run();
            }
        });
    }

    /** Where the open map is saved: a plain map file, or a level of a multilevel map. */
    private record SaveTarget(Path file, Path manifest, String levelId) {
        String displayName() {
            return MapBrowser.displayName(manifest != null ? manifest : file);
        }
    }

    private SaveTarget currentSaveTarget() {
        return projectFile == null ? null : new SaveTarget(projectFile, multiLevelFile, currentLevelId);
    }

    /** Blocking save (background thread); a level also stores the shared settings of its multilevel map. */
    private void saveTo(SaveTarget target, DmProject snapshot) throws IOException {
        if (target.manifest() != null) {
            mapLibrary.multiLevels().saveLevel(target.manifest(), target.levelId(), snapshot);
        } else {
            projectService.save(target.file(), snapshot);
        }
    }

    /** The open map as the library knows it: the multilevel map's manifest, else the map file. */
    private Path openMapFile() {
        return multiLevelFile != null ? multiLevelFile : projectFile;
    }

    private MultiLevelManifest.Level currentLevel() {
        return multiLevelManifest == null ? null : multiLevelManifest.findLevel(currentLevelId);
    }

    private String fingerprintOrNull(DmProject candidate) {
        try {
            return projectService.fingerprint(candidate);
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    /** Records the just-saved state; edits made while the save ran keep the map dirty. */
    private void markSaved(DmProject snapshot, long versionAtSnapshot) {
        savedFingerprint = fingerprintOrNull(historyVersion == versionAtSnapshot ? project : snapshot);
    }

    private boolean autoSaveEnabled() {
        return preferences.getBoolean(PREF_AUTOSAVE_ENABLED, true);
    }

    private int autoSaveMinutes() {
        return preferences.getInt(PREF_AUTOSAVE_MINUTES, 2);
    }

    private HBox createAutoSaveMenu() {
        CheckMenuItem enabled = new CheckMenuItem("Auto-save");
        enabled.setSelected(autoSaveEnabled());
        enabled.selectedProperty().addListener((obs, was, on) -> {
            preferences.putBoolean(PREF_AUTOSAVE_ENABLED, on);
            lastAutoSaveNanos = System.nanoTime();
        });
        javafx.scene.control.MenuButton button = new javafx.scene.control.MenuButton();
        button.setGraphic(Icons.icon(MaterialDesignA.AUTORENEW));
        Icons.tooltip(button, "Auto-save settings: saves maps that are already on disk in the background");
        button.getItems().add(enabled);
        button.getItems().add(new SeparatorMenuItem());
        ToggleGroup group = new ToggleGroup();
        int[] minuteOptions = Tuning.AUTOSAVE_MINUTE_OPTIONS.get().stream()
                .mapToInt(v -> (int) Math.round(v)).distinct().toArray();
        for (int minutes : minuteOptions) {
            RadioMenuItem item = new RadioMenuItem("Every " + minutes + (minutes == 1 ? " minute" : " minutes"));
            item.setToggleGroup(group);
            item.setSelected(minutes == autoSaveMinutes());
            item.setOnAction(e -> {
                preferences.putInt(PREF_AUTOSAVE_MINUTES, minutes);
                lastAutoSaveNanos = System.nanoTime();
            });
            button.getItems().add(item);
        }
        return new HBox(button);
    }

    private void autoSaveTick() {
        long intervalNanos = autoSaveMinutes() * 60_000_000_000L;
        if (System.nanoTime() - lastAutoSaveNanos >= intervalNanos) {
            autoSaveIfDirty();
        }
    }

    private boolean interactionInProgress() {
        return draggingLayer || resizingLayer || draggingLight || fogDragging || draggingOverlay || resizingOverlay
                || draggingText || resizingTextHandle >= 0 || draftText != null
                || draggingPlayerViewport || panningDmCamera
                || (draftWall != null && (activeTool != EditorTool.WALL_DRAW || canvasMouseDown))
                || draftOverlay != null;
    }

    private void installNudgeFocusListener(Scene scene) {
        scene.focusOwnerProperty().addListener((obs, oldOwner, owner) -> {
            if (owner != dmCanvas) {
                finishNudge();
                finishPlayerViewportDrag();
            }
        });
    }

    private void installRoomPreviewKeyFilter(Scene scene) {
        scene.addEventFilter(javafx.scene.input.KeyEvent.ANY, event -> {
            if (event.getEventType() == javafx.scene.input.KeyEvent.KEY_PRESSED
                    || event.getEventType() == javafx.scene.input.KeyEvent.KEY_RELEASED) {
                roomHidePreview = event.getCode() == KeyCode.SHIFT
                        ? event.getEventType() == javafx.scene.input.KeyEvent.KEY_PRESSED
                        : event.isShiftDown();
            }
        });
    }

    private boolean hasUnsavedChanges() {
        if (projectFile == null || project == null) {
            return false;
        }
        String current = fingerprintOrNull(project);
        return current != null && !current.equals(savedFingerprint);
    }

    /** Background save of a map that already exists on disk; postponed during drags, skipped while another save runs. */
    private void autoSaveIfDirty() {
        if (!autoSaveEnabled() || projectFile == null || ioBusy || interactionInProgress()
                || nudgeStartPositions != null || !hasUnsavedChanges()) {
            return;
        }
        lastAutoSaveNanos = System.nanoTime();
        SaveTarget target = currentSaveTarget();
        DmProject savedProject = project;
        long version = historyVersion;
        DmProject snapshot;
        try {
            snapshot = projectService.copy(project);
        } catch (IOException ex) {
            status("Auto-save failed: " + ex.getMessage());
            return;
        }
        runInBackground("Auto-saving...", "Auto-save failed: ", () -> {
            saveTo(target, snapshot);
            return target;
        }, saved -> {
            if (project == savedProject) {
                adoptCopiedAssetPaths(snapshot);
                markSaved(snapshot, version);
            }
            mapBrowser.invalidateThumbnails();
            status("Auto-saved " + java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));
        });
    }

    /** The app is about to exit, so the save has to finish before the window closes. */
    private void saveOnExit() {
        if (!autoSaveEnabled() || ioBusy || !hasUnsavedChanges()) {
            return;
        }
        try {
            saveTo(currentSaveTarget(), projectService.copy(project));
        } catch (IOException | RuntimeException ex) {
            status("Auto-save failed: " + ex.getMessage());
        }
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
        long version = historyVersion;
        runInBackground("Saving...", "Save failed: ", () -> {
            projectService.save(target, snapshot);
            return target;
        }, saved -> {
            if (project == savedProject) {
                projectFile = saved;
                adoptCopiedAssetPaths(snapshot);
                markSaved(snapshot, version);
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

    private record LibraryOutcome(MapLibraryService.Result result, Path openMapMovedTo, DmProject reloadedOpenMap,
                                  Path manifestMovedTo) {
    }

    /**
     * Runs a map library operation (move, rename, copy, delete, ...). If it touches the open map, the map is saved
     * first and its file reference is updated afterwards.
     */
    private void runLibraryOperation(String busyMessage, Path affectedPath, MapBrowser.LibraryOperation operation,
                                     Consumer<MapLibraryService.Result> onDone) {
        Path openFile = projectFile == null ? null : projectFile.toAbsolutePath().normalize();
        Path openManifest = multiLevelFile == null ? null : multiLevelFile.toAbsolutePath().normalize();
        SaveTarget saveTarget = currentSaveTarget();
        boolean touchesOpenMap = openFile != null && affectedPath != null
                && openFile.startsWith(affectedPath.toAbsolutePath().normalize());
        DmProject savedProject = project;
        long version = historyVersion;
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
        if (touchesOpenMap && adjacentPrefetch != null) {
            prefetchGeneration++;
            adjacentPrefetch.clear();
        }
        runInBackground(busyMessage, "Library operation failed: ", () -> {
            if (toSave != null) {
                saveTo(saveTarget, toSave);
            }
            MapLibraryService.Result result = operation.run();
            Path movedTo = touchesOpenMap ? movedLocation(result, openFile) : null;
            DmProject reloaded = movedTo != null ? projectService.load(movedTo) : null;
            Path manifestMovedTo = touchesOpenMap && openManifest != null ? movedLocation(result, openManifest) : null;
            return new LibraryOutcome(result, movedTo, reloaded, manifestMovedTo);
        }, outcome -> {
            if (touchesOpenMap && project == savedProject) {
                if (toSave != null) {
                    markSaved(toSave, version);
                }
                if (outcome.openMapMovedTo() != null) {
                    projectFile = outcome.openMapMovedTo();
                    if (outcome.manifestMovedTo() != null) {
                        multiLevelFile = outcome.manifestMovedTo();
                    }
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
            if (touchesOpenMap) {
                scheduleAdjacentPrefetch();
            }
            if (statusLabel.getText().equals(busyMessage)) {
                status("Done.");
            }
        }, ex -> {
            Dialogs.error(primaryStage, "That did not work", ex.getMessage());
            mapBrowser.refresh();
            if (touchesOpenMap) {
                scheduleAdjacentPrefetch();
            }
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
            String name;
            if (projectFile == null) {
                name = "Unsaved new map";
            } else if (multiLevelFile != null) {
                MultiLevelManifest.Level level = currentLevel();
                name = MapBrowser.displayName(multiLevelFile) + (level == null ? "" : " — " + level.getName());
            } else {
                name = MapBrowser.displayName(projectFile);
            }
            primaryStage.setTitle("Dungeon Master Map Tool — " + name);
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

    private record LoadedProject(DmProject project, Path file, LevelContext level,
                                 ImagePyramidStore.PreparedImages images) {
        LoadedProject(DmProject project, Path file) {
            this(project, file, null, null);
        }

        LoadedProject(DmProject project, Path file, LevelContext level) {
            this(project, file, level, null);
        }
    }

    private LoadedProject prepareLoadedProject(DmProject loaded, Path file, LevelContext level,
                                               ImagePyramidStore.Viewport dm, ImagePyramidStore.Viewport player)
            throws IOException {
        centerUnsetCameras(loaded);
        ImagePyramidStore.PreparedImages images = ImagePyramidStore.shared().prepareProject(
                loaded, file, dm, player, false, () -> false);
        return new LoadedProject(loaded, file, level, images);
    }

    /** The multilevel map a loaded level belongs to. */
    private record LevelContext(Path manifestFile, MultiLevelManifest manifest, String levelId) {
        static LevelContext of(Path manifestFile, MultiLevelService.LoadedLevel loaded) {
            return new LevelContext(manifestFile.toAbsolutePath().normalize(), loaded.manifest(), loaded.level().getId());
        }
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
            } catch (OutOfMemoryError ex) {
                IOException failure = new IOException("Not enough memory to prepare the map.", ex);
                Platform.runLater(() -> {
                    ioBusy = false;
                    status(failurePrefix + failure.getMessage());
                    if (onFailure != null) {
                        onFailure.accept(failure);
                    }
                });
            }
        }, "dmmt-io");
        thread.setDaemon(true);
        thread.start();
    }

    private void addImageLayerFromFile(Path imagePath) {
        if (pendingImageAdds.size() >= 128) {
            status("Too many queued images. Wait for the current images to finish.");
            return;
        }
        pendingImageAdds.addLast(imagePath);
        startNextImageAdd();
    }

    private void cancelImageAdds() {
        imageAddGeneration++;
        pendingImageAdds.clear();
        if (imageAddTask != null) {
            imageAddTask.cancel(true);
            imageAddTask = null;
        }
    }

    private void startNextImageAdd() {
        if (imageAddTask != null || pendingImageAdds.isEmpty()) {
            return;
        }
        Path imagePath = pendingImageAdds.removeFirst();
        DmProject destination = project;
        long generation = imageAddGeneration;
        try {
            imageAddTask = WorkScheduler.shared().submit(WorkScheduler.Kind.IMAGE, true, () -> {
                try {
                    int[] size = ImagePyramidBuilder.requireDimensions(imagePath);
                    Platform.runLater(() -> {
                        if (generation == imageAddGeneration && project == destination) {
                            imageAddTask = null;
                            applyImageLayer(imagePath, size);
                            startNextImageAdd();
                        }
                    });
                } catch (IOException | RuntimeException ex) {
                    Platform.runLater(() -> {
                        if (generation == imageAddGeneration) {
                            imageAddTask = null;
                            status("Could not add image: " + ex.getMessage());
                            startNextImageAdd();
                        }
                    });
                }
                return null;
            });
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            status("Could not queue image: " + ex.getMessage());
            pendingImageAdds.clear();
        }
    }

    private void applyImageLayer(Path imagePath, int[] size) {
        if (project == null) {
            project = DmProject.builder().build();
        }
        project.getMap().setSourceType("custom");
        if (isImageLayerLocked()) {
            setImageLayerLocked(false);
        }
        DmProject.ImageLayer layer = DmProject.ImageLayer.builder()
                .id("layer-" + UUID.randomUUID())
                .path(imagePath.toAbsolutePath().toString())
                .x(0)
                .y(0)
                .width(size[0])
                .height(size[1])
                .zIndex(project.getImageLayers().size())
                .build();
        project.getImageLayers().add(layer);
        clearGroup();
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

    private void changeInteractableType(DmProject.Interactable portal, String type) {
        if (portal == null || !List.of("door", "window").contains(type) || type.equals(portal.getType())) {
            return;
        }
        String before = portal.getType();
        executeWithFogHistory("Change portal type", () -> portal.setType(type), () -> portal.setType(before));
    }

    private void showInteractableContextMenu(DmProject.Interactable portal, double screenX, double screenY) {
        ContextMenu menu = new ContextMenu();
        ToggleGroup types = new ToggleGroup();
        for (String type : List.of("door", "window")) {
            RadioMenuItem item = new RadioMenuItem(type.equals("door") ? "Door" : "Window");
            item.setToggleGroup(types);
            item.setSelected(type.equalsIgnoreCase(portal.getType()));
            item.setOnAction(e -> changeInteractableType(portal, type));
            menu.getItems().add(item);
        }
        MenuItem delete = new MenuItem("Delete");
        delete.setOnAction(e -> deleteInteractable(portal));
        menu.getItems().addAll(new SeparatorMenuItem(), delete);
        hideLightMenu();
        activeLightMenu = menu;
        menu.setOnHidden(e -> {
            if (activeLightMenu == menu) {
                activeLightMenu = null;
            }
        });
        menu.show(dmCanvas, screenX, screenY);
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
        double best = Tuning.WALL_PICK_RADIUS.get() / zoom;
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
        if (pickNearestLight(worldX, worldY, Tuning.LIGHT_PICK_RADIUS.get() / zoom) != null) {
            return null;
        }
        return pickInteractableLine(worldX, worldY);
    }

    private String hoveredInteractableId() {
        if (!hoverInsideCanvas || pingArmed || laserToolActive || activeTool != EditorTool.SELECT
                || panningDmCamera || draggingLight || draggingLayer || draggingOverlay || resizingOverlay || draggingPlayerViewport || resizingLayer) {
            return null;
        }
        DmProject.Interactable hovered = pickInteractableForClick(hoverWorldX, hoverWorldY);
        return hovered == null ? null : hovered.getId();
    }

    private void showViewportTitleTooltip(boolean enabled) {
        if (enabled == viewportTitleTooltipInstalled) {
            return;
        }
        viewportTitleTooltipInstalled = enabled;
        if (enabled) {
            javafx.scene.control.Tooltip.install(dmCanvas, viewportTitleTooltip);
        } else {
            javafx.scene.control.Tooltip.uninstall(dmCanvas, viewportTitleTooltip);
            viewportTitleTooltip.hide();
        }
    }

    private Rectangle2D playerViewportInteractionArea() {
        double right = dmCanvas.getWidth();
        double top = 0;
        if (dmControls != null && dmControls.isVisible()) {
            var bounds = dmCanvas.sceneToLocal(dmControls.localToScene(dmControls.getBoundsInLocal()));
            right = clamp(bounds.getMinX(), 0, right);
        }
        for (javafx.scene.Node overlay : new javafx.scene.Node[]{toolChip, imageUnlockBanner, levelSwitcher}) {
            if (overlay != null && overlay.isVisible()) {
                var bounds = dmCanvas.sceneToLocal(overlay.localToScene(overlay.getBoundsInLocal()));
                if (bounds.getMinX() < right && bounds.getMaxX() > 0) {
                    top = Math.max(top, bounds.getMaxY());
                }
            }
        }
        top = clamp(top, 0, dmCanvas.getHeight());
        return new Rectangle2D(0, top, right, dmCanvas.getHeight() - top);
    }

    private javafx.geometry.Point2D playerViewportScrollVelocity() {
        if (!draggingPlayerViewport || playerStage == null || activeTool != EditorTool.SELECT
                || !Tuning.PLAYER_VIEWPORT_EDGE_SCROLL.get() || ioBusy
                || (primaryStage != null && !primaryStage.isFocused())) {
            return javafx.geometry.Point2D.ZERO;
        }
        var cursor = dmCanvas.sceneToLocal(viewportDragSceneX, viewportDragSceneY);
        if (dmControls != null && dmControls.isVisible()
                && dmCanvas.sceneToLocal(dmControls.localToScene(dmControls.getBoundsInLocal())).contains(cursor)) {
            return javafx.geometry.Point2D.ZERO;
        }
        // Mouse drags are captured by the canvas, so the event target does not identify overlays beneath the cursor.
        if (mapCenter != null) {
            for (javafx.scene.Node overlay : mapCenter.getChildren()) {
                if (overlay == dmCanvas || overlay.isMouseTransparent() || !overlay.isVisible()) {
                    continue;
                }
                var bounds = dmCanvas.sceneToLocal(overlay.localToScene(overlay.getBoundsInLocal()));
                if (bounds.contains(cursor)) {
                    return javafx.geometry.Point2D.ZERO;
                }
            }
        }
        return dmmt.ui.ViewportEdgeScroll.velocity(playerViewportInteractionArea(), cursor.getX(), cursor.getY(),
                Tuning.PLAYER_VIEWPORT_EDGE_ZONE.get(), Tuning.PLAYER_VIEWPORT_EDGE_SPEED.get());
    }

    private void updatePlayerViewportDrag(long now) {
        if (!draggingPlayerViewport || playerStage == null) {
            viewportScrollNanos = 0;
            return;
        }
        var velocity = playerViewportScrollVelocity();
        if (velocity.magnitude() > 0) {
            if (viewportScrollNanos != 0) {
                double seconds = (now - viewportScrollNanos) / 1_000_000_000.0;
                var camera = project.getViews().getDmCamera();
                camera.setX(camera.getX() + velocity.getX() * seconds / camera.getZoom());
                camera.setY(camera.getY() + velocity.getY() * seconds / camera.getZoom());
            }
            viewportScrollNanos = now;
        } else {
            viewportScrollNanos = 0;
        }
        var cursor = dmCanvas.sceneToLocal(viewportDragSceneX, viewportDragSceneY);
        movePlayerViewportToCursor(cursor.getX(), cursor.getY());
    }

    private void movePlayerViewportToCursor(double x, double y) {
        var rect = getPlayerViewportRect();
        if (rect == null) {
            return;
        }
        updateHover(x, y);
        var playerCamera = project.getViews().getPlayerCamera();
        playerCamera.setX(hoverWorldX - viewportDragOffsetX + rect.width() / 2.0);
        playerCamera.setY(hoverWorldY - viewportDragOffsetY + rect.height() / 2.0);
    }

    private void finishPlayerViewportDrag() {
        viewportScrollNanos = 0;
        if (!draggingPlayerViewport) {
            return;
        }
        draggingPlayerViewport = false;
        var playerCamera = project.getViews().getPlayerCamera();
        if (!same(startPlayerCameraX, playerCamera.getX()) || !same(startPlayerCameraY, playerCamera.getY())) {
            double beforeX = startPlayerCameraX;
            double beforeY = startPlayerCameraY;
            double afterX = playerCamera.getX();
            double afterY = playerCamera.getY();
            executeWithHistory("Move player viewport",
                    () -> {
                        playerCamera.setX(afterX);
                        playerCamera.setY(afterY);
                    },
                    () -> {
                        playerCamera.setX(beforeX);
                        playerCamera.setY(beforeY);
                    });
        }
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

    private boolean isOnPlayerViewportResetButton(double worldX, double worldY) {
        CanvasMapRenderer.WorldRect rect = getPlayerViewportRect();
        if (playerStage == null || rect == null || dmCanvas == null) {
            return false;
        }
        DmProject.CameraState dmCamera = project.getViews().getDmCamera();
        double w = dmCanvas.getWidth();
        double h = dmCanvas.getHeight();
        return renderer.isOnViewportResetButton(rect,
                renderer.worldToScreenX(worldX, w, dmCamera), renderer.worldToScreenY(worldY, h, dmCamera),
                w, h, dmCamera);
    }

    private void resetPlayerZoom() {
        playerZoomSlider.setValue(0);
        status("Player view zoom reset to normal (100%).");
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
                .durationMillis(Tuning.PING_DURATION_MS.get())
                .build());
    }

    // ---- Tools & fog editing ----

    private Label createBrushSizeLabel() {
        brushSizeLabel = new Label();
        brushSizeLabel.setManaged(false);
        brushSizeLabel.setMouseTransparent(true);
        brushSizeLabel.setVisible(false);
        brushSizeLabel.setStyle("-fx-background-color: rgba(0,0,0,0.75); -fx-text-fill: white; "
                + "-fx-padding: 4 8; -fx-background-radius: 4;");
        brushSizeLabelTimeout.setOnFinished(event -> brushSizeLabel.setVisible(false));
        return brushSizeLabel;
    }

    private void hideBrushSizeLabel() {
        brushSizeLabelTimeout.stop();
        if (brushSizeLabel != null) {
            brushSizeLabel.setVisible(false);
        }
    }

    private void showSizeLabel(String text, double x, double y) {
        brushSizeLabel.setText(text);
        brushSizeLabel.setVisible(true);
        brushLabelCursorX = x;
        brushLabelCursorY = y;
        positionBrushSizeLabel();
        brushSizeLabelTimeout.playFromStart();
    }

    private void positionBrushSizeLabel() {
        if (brushSizeLabel == null || !brushSizeLabel.isVisible()) {
            return;
        }
        double width = Math.min(brushSizeLabel.prefWidth(-1), dmCanvas.getWidth());
        double height = Math.min(brushSizeLabel.prefHeight(width), dmCanvas.getHeight());
        brushSizeLabel.resize(width, height);
        brushSizeLabel.relocate(clamp(brushLabelCursorX + 16, 0, dmCanvas.getWidth() - width),
                clamp(brushLabelCursorY + 20, 0, dmCanvas.getHeight() - height));
    }

    private void updateHover(double screenX, double screenY) {
        CanvasMapRenderer.WorldPoint world = renderer.screenToWorld(
                screenX, screenY, dmCanvas.getWidth(), dmCanvas.getHeight(), project.getViews().getDmCamera());
        hoverInsideCanvas = true;
        geometryPreviewHover = screenX >= 0 && screenY >= 0
                && screenX < dmCanvas.getWidth() && screenY < dmCanvas.getHeight();
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

    private boolean isImageLayerLocked() {
        return project != null && project.getMap().imageLayersLockedOrDefault();
    }

    private void setImageLayerLocked(boolean locked) {
        finishNudge();
        project.getMap().setImageLayersLocked(locked);
        if (locked) {
            selectedLayer = null;
            draggingLayer = false;
            resizingLayer = false;
        }
        updateImageLockToggle();
        updateCanvasCursor();
        status(locked ? "Image layer locked — map images can't be moved." : "Image layer unlocked — map images can be moved and resized with Select.");
    }

    private HBox createImageUnlockBanner() {
        Label label = new Label("Image layer is unlocked — map images can be moved and resized.");
        label.getStyleClass().add("image-unlock-banner-label");
        Button lockButton = new Button("Lock", Icons.icon(MaterialDesignL.LOCK_OUTLINE));
        lockButton.setOnAction(e -> {
            if (project != null) {
                setImageLayerLocked(true);
            }
        });
        HBox banner = new HBox(new FontIcon(MaterialDesignL.LOCK_OPEN_VARIANT_OUTLINE), label, lockButton);
        banner.getStyleClass().add("image-unlock-banner");
        banner.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        banner.setVisible(false);
        return banner;
    }

    private void updateImageLockToggle() {
        if (imageUnlockBanner != null) {
            imageUnlockBanner.setVisible(project != null && !isImageLayerLocked());
        }
        if (imageLockToggle == null) {
            return;
        }
        boolean locked = isImageLayerLocked();
        if (imageLockToggle.isSelected() != locked) {
            imageLockToggle.setSelected(locked);
        }
        imageLockToggle.setGraphic(Icons.icon(locked ? MaterialDesignL.LOCK_OUTLINE : MaterialDesignL.LOCK_OPEN_VARIANT_OUTLINE));
    }

    private void cancelActiveTool() {
        finishPlayerViewportDrag();
        if (fogDragging) {
            restoreFog(fogBeforeSnapshot);
        }
        setPingArmed(false);
        setLaserToolActive(false);
        setActiveTool(EditorTool.SELECT);
    }

    private void setActiveTool(EditorTool tool) {
        finishPlayerViewportDrag();
        hideBrushSizeLabel();
        commitTextEdit();
        clearGroup();
        if (draftText != null) {
            DmProject.TextBox abandoned = draftText;
            project.getTextBoxes().remove(abandoned);
        }
        draftText = null;
        draggingText = false;
        resizingTextHandle = -1;
        activeTool = tool == null ? EditorTool.SELECT : tool;
        roomPreview = null;
        geometryPreviewHover = false;
        if (activeTool == EditorTool.TEXT || activeTool == EditorTool.ROOM_LABEL) {
            if (!project.isTextLayerVisible()) {
                setTextLayerVisible(true);
            }
            selectedTextId = null;
            loadTextSettingsIntoControls();
        }
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
        if (laserToolActive && tool != null && tool != EditorTool.SELECT) {
            setLaserToolActive(false);
        }
        updateToolChip();
        updateCanvasCursor();
        switch (activeTool) {
            case SELECT -> status("Select: click a door/window icon to open or close it; drag lights, layers and the player viewport. Right-click an element for options.");
            case REVEAL_BRUSH -> status("Reveal brush: paint to uncover the map.");
            case HIDE_BRUSH -> status("Hide brush: paint to cover the map with fog.");
            case REVEAL_RECT -> status("Reveal rectangle: drag to uncover an area.");
            case HIDE_RECT -> status("Hide rectangle: drag to cover an area with fog.");
            case REVEAL_ROOM -> status("Reveal room: click inside a room to uncover it (Shift+click covers it).");
            case AOE_CIRCLE -> status("Circle effect: drag from the center outward.");
            case AOE_RECT -> status("Box effect: drag from corner to corner.");
            case AOE_BRUSH -> status("Draw effect: paint a freeform area (brush size sets thickness).");
            case AOE_PEN -> status("Pen: draw a thin freehand line in the selected color.");
            case AOE_LINE -> status("Line: drag to draw a straight line (brush size and color, no texture).");
            case TEXT -> status("Text box: drag to draw a box and type; click a text box to edit it.");
            case WALL_DRAW -> status("Wall: click points to draw connected walls; Esc or right-click ends the chain (half-tile snap; Shift for free placement).");
            case DOOR_DRAW, WINDOW_DRAW -> status(activeTool.label + ": drag endpoints (half-tile snap; Shift for free placement).");
            case ROOM_LABEL -> status("Room label: preview a room in blue; click and type its DM-only name.");
            case WALL_ERASE -> status("Erase walls: hover to highlight a wall, door or window; click to remove it.");
            case LIGHT_ADD -> status("Add light: click the map where the torch should go.");
            case LIGHT_CANDLE, LIGHT_CAMPFIRE, LIGHT_MAGIC ->
                    status(activeTool.label + ": click the map where the light should go.");
        }
    }

    private double brushRadiusWorld() {
        return brushSize.get() * project.getMap().getGrid().getPixelsPerCell() / 2.0;
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
        finishNudge();
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

    private RoomFillService.Result roomAt(double worldX, double worldY) {
        FogMask mask = project.getFog().getMask();
        if (mask == null) {
            return null;
        }
        long signature = RoomFillService.geometrySignature(mask, project.getWalls(), project.getInteractables());
        if (roomBarrier == null || signature != roomBarrierSignature) {
            roomBarrier = RoomFillService.buildBarrier(mask, project.getWalls(), project.getInteractables());
            roomBarrierSignature = signature;
            roomPreview = null;
        }
        int col = (int) Math.floor((worldX - mask.getOriginX()) / mask.getCellSize());
        int row = (int) Math.floor((worldY - mask.getOriginY()) / mask.getCellSize());
        if (col < 0 || row < 0 || col >= mask.getCols() || row >= mask.getRows()) {
            return new RoomFillService.Result(new BitSet(), false);
        }
        int index = row * mask.getCols() + col;
        if (roomPreview != null && !roomBarrier.get(index) && roomPreview.cells().get(index)) {
            return roomPreview;
        }
        roomPreview = RoomFillService.fill(mask, roomBarrier, worldX, worldY);
        return roomPreview;
    }

    private void revealRoomAt(double worldX, double worldY, boolean reveal) {
        if (!project.getFog().isEnabled()) {
            status("Fog is off. Turn fog on to edit it.");
            return;
        }
        FogMask mask = project.getFog().getMask();
        RoomFillService.Result room = mask == null ? null : roomAt(worldX, worldY);
        if (room == null || room.isEmpty()) {
            status("Click inside a room, not on a wall.");
            return;
        }
        FogMask.Snapshot before = mask.snapshot();
        mask.applyCells(room.cells().stream().toArray(), reveal);
        FogMask.Snapshot after = mask.snapshot();
        if (!before.sameBits(after)) {
            recordHistory(reveal ? "Reveal room" : "Hide room", () -> restoreFog(after), () -> restoreFog(before));
        }
        String verb = reveal ? "Revealed" : "Covered";
        status(room.leaked()
                ? verb + " an area that is not closed off — check for gaps in the walls."
                : verb + " the room.");
    }

    private void drawRoomPreview(GraphicsContext gc) {
        FogMask mask = project.getFog().getMask();
        boolean labelPreview = activeTool == EditorTool.ROOM_LABEL;
        if (!hoverInsideCanvas || (labelPreview && !geometryPreviewHover) || mask == null
                || editingTextId != null || pingArmed || laserToolActive
                || panningDmCamera || (!labelPreview && !project.getFog().isEnabled())) {
            return;
        }
        RoomFillService.Result room = roomAt(hoverWorldX, hoverWorldY);
        if (room == null || room.isEmpty()) {
            return;
        }
        DmProject.CameraState camera = project.getViews().getDmCamera();
        double w = dmFogCanvas.getWidth();
        double h = dmFogCanvas.getHeight();
        CanvasMapRenderer.WorldPoint topLeft = renderer.screenToWorld(0, 0, w, h, camera);
        CanvasMapRenderer.WorldPoint bottomRight = renderer.screenToWorld(w, h, w, h, camera);
        double cell = mask.getCellSize();
        int cols = mask.getCols();
        int minCol = Math.max(0, (int) Math.floor((Math.min(topLeft.x(), bottomRight.x()) - mask.getOriginX()) / cell));
        int maxCol = Math.min(cols - 1, (int) Math.floor((Math.max(topLeft.x(), bottomRight.x()) - mask.getOriginX()) / cell));
        int minRow = Math.max(0, (int) Math.floor((Math.min(topLeft.y(), bottomRight.y()) - mask.getOriginY()) / cell));
        int maxRow = Math.min(mask.getRows() - 1, (int) Math.floor((Math.max(topLeft.y(), bottomRight.y()) - mask.getOriginY()) / cell));
        gc.setFill(labelPreview ? Color.web("#429BFF", 0.3) : roomHidePreview ? Color.web("#FF8A7A", 0.3)
                : room.leaked() ? Color.web("#FFB020", 0.35) : Color.web("#7CFFB2", 0.3));
        BitSet cells = room.cells();
        double cellScreen = cell * camera.getZoom();
        for (int row = minRow; row <= maxRow; row++) {
            double y = renderer.worldToScreenY(mask.getOriginY() + row * cell, h, camera);
            int col = minCol;
            while (col <= maxCol) {
                if (!cells.get(row * cols + col)) {
                    col++;
                    continue;
                }
                int runStart = col;
                while (col <= maxCol && cells.get(row * cols + col)) {
                    col++;
                }
                double x = renderer.worldToScreenX(mask.getOriginX() + runStart * cell, w, camera);
                gc.fillRect(x, y, (col - runStart) * cellScreen + 0.5, cellScreen + 0.5);
            }
        }
    }

    private void drawOverlaySizeLabel(GraphicsContext gc) {
        DmProject.OverlayShape shape = draftOverlay;
        boolean circle = "circle".equals(shape.getType());
        if (!circle && !"rect".equals(shape.getType())) {
            return;
        }
        DmProject.CameraState cam = project.getViews().getDmCamera();
        double w = dmFogCanvas.getWidth();
        double h = dmFogCanvas.getHeight();
        double cell = project.getMap().getGrid().getPixelsPerCell();
        double cx;
        double cy;
        String text;
        if (circle) {
            cx = renderer.worldToScreenX(shape.getX(), w, cam);
            cy = renderer.worldToScreenY(shape.getY(), h, cam);
            text = formatTiles(shape.getRadius() / cell);
        } else {
            cx = renderer.worldToScreenX(shape.getX() + shape.getWidth() / 2.0, w, cam);
            cy = renderer.worldToScreenY(shape.getY() + shape.getHeight() / 2.0, h, cam);
            text = formatTiles(shape.getWidth() / cell) + " x " + formatTiles(shape.getHeight() / cell);
        }
        javafx.scene.text.Text measure = new javafx.scene.text.Text(text);
        measure.setFont(javafx.scene.text.Font.font("System", javafx.scene.text.FontWeight.BOLD, 16));
        double tw = measure.getLayoutBounds().getWidth();
        double th = measure.getLayoutBounds().getHeight();
        gc.setFill(Color.web("#000000", 0.65));
        gc.fillRoundRect(cx - tw / 2 - 8, cy - th / 2 - 4, tw + 16, th + 8, 8, 8);
        gc.setFont(measure.getFont());
        gc.setFill(Color.WHITE);
        gc.setTextAlign(javafx.scene.text.TextAlignment.CENTER);
        gc.setTextBaseline(javafx.geometry.VPos.CENTER);
        gc.fillText(text, cx, cy);
        gc.setTextAlign(javafx.scene.text.TextAlignment.LEFT);
        gc.setTextBaseline(javafx.geometry.VPos.BASELINE);
    }

    private static String formatTiles(double tiles) {
        double rounded = Math.round(tiles * 10.0) / 10.0;
        return rounded == Math.floor(rounded) ? String.valueOf((long) rounded) : String.valueOf(rounded);
    }

    private void drawToolPreview(GraphicsContext gc) {
        if (activeTool == EditorTool.ROOM_LABEL) {
            drawRoomPreview(gc);
            return;
        }
        if (activeTool == EditorTool.WALL_ERASE) {
            drawErasePreview(gc);
            return;
        }
        if (draftOverlay != null) {
            drawOverlaySizeLabel(gc);
        }
        if (draftWall != null && (activeTool != EditorTool.WALL_DRAW
                || (hoverInsideCanvas && geometryPreviewHover && !panningDmCamera && !pingArmed && !laserToolActive))) {
            DmProject.CameraState wallCamera = project.getViews().getDmCamera();
            double ww = dmFogCanvas.getWidth();
            double wh = dmFogCanvas.getHeight();
            double[] endpoint = activeTool == EditorTool.WALL_DRAW
                    ? snapWallPoint(hoverWorldX, hoverWorldY, roomHidePreview)
                    : new double[]{draftWall.getX2(), draftWall.getY2()};
            gc.setStroke(Color.web("#FFD24A"));
            gc.setLineWidth(3);
            gc.strokeLine(renderer.worldToScreenX(draftWall.getX1(), ww, wallCamera), renderer.worldToScreenY(draftWall.getY1(), wh, wallCamera),
                    renderer.worldToScreenX(endpoint[0], ww, wallCamera), renderer.worldToScreenY(endpoint[1], wh, wallCamera));
            if (activeTool == EditorTool.WALL_DRAW) {
                gc.setFill(Color.web("#FFD24A"));
                gc.fillOval(renderer.worldToScreenX(draftWall.getX1(), ww, wallCamera) - 4,
                        renderer.worldToScreenY(draftWall.getY1(), wh, wallCamera) - 4, 8, 8);
                gc.fillOval(renderer.worldToScreenX(endpoint[0], ww, wallCamera) - 4,
                        renderer.worldToScreenY(endpoint[1], wh, wallCamera) - 4, 8, 8);
            }
        }
        if ((activeTool == EditorTool.AOE_BRUSH || activeTool == EditorTool.AOE_LINE) && hoverInsideCanvas) {
            DmProject.CameraState brushCamera = project.getViews().getDmCamera();
            double bcx = renderer.worldToScreenX(hoverWorldX, dmFogCanvas.getWidth(), brushCamera);
            double bcy = renderer.worldToScreenY(hoverWorldY, dmFogCanvas.getHeight(), brushCamera);
            double br = brushRadiusWorld() * brushCamera.getZoom();
            gc.setStroke(Color.web("#FFFFFF", 0.9));
            gc.setLineWidth(1.5);
            gc.setLineDashes(6, 4);
            gc.strokeOval(bcx - br, bcy - br, br * 2, br * 2);
            gc.setLineDashes((double[]) null);
            return;
        }
        if (!activeTool.isFogTool()) {
            return;
        }
        if (activeTool == EditorTool.REVEAL_ROOM) {
            drawRoomPreview(gc);
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
        switchProject(next, file, null);
    }

    private void switchProject(DmProject next, Path file, LevelContext level) {
        prefetchGeneration++;
        cancelImageAdds();
        if (adjacentPrefetch != null) {
            adjacentPrefetch.close();
            adjacentPrefetch = null;
        }
        levelPreviews.clear();
        finishNudge();
        finishPlayerViewportDrag();
        commitTextEdit();
        if (frozenPlayerProject == null) {
            capturePlayerTransition();
        }
        project = next;
        project.getViews().setPlayerFrozen(false);
        projectFile = file;
        multiLevelFile = level == null ? null : level.manifestFile();
        multiLevelManifest = level == null ? null : level.manifest();
        currentLevelId = level == null ? null : level.levelId();
        updateLevelSwitcher();
        new FogService().ensureMask(project);
        centerUnsetCameras(project);
        lightingEngine.reset();
        roomBarrier = null;
        roomPreview = null;
        geometryPreviewHover = false;
        clearGroup();
        selectedLayer = null;
        selectedLight = null;
        selectedOverlayId = null;
        draftOverlay = null;
        draftWall = null;
        draggingOverlay = false;
        resizingOverlay = false;
        selectedTextId = null;
        draftText = null;
        draggingText = false;
        resizingTextHandle = -1;
        fogDragging = false;
        undoStack.clear();
        redoStack.clear();
        historyFeedback.clear();
        savedFingerprint = fingerprintOrNull(project);
        syncControlsFromProject();
        if (activeTool == EditorTool.TEXT) {
            loadTextSettingsIntoControls();
        }
        if (mapBrowser != null) {
            mapBrowser.updateCurrentMap();
        }
        updateWindowTitle();
        scheduleAdjacentPrefetch();
    }

    private static ImagePyramidStore.Viewport imageViewport(Canvas canvas) {
        if (canvas == null) {
            return null;
        }
        var window = canvas.getScene() == null ? null : canvas.getScene().getWindow();
        double scale = window == null ? 1 : Math.max(window.getOutputScaleX(), window.getOutputScaleY());
        return new ImagePyramidStore.Viewport(canvas.getWidth(), canvas.getHeight(), Math.max(1, scale));
    }

    private void scheduleAdjacentPrefetch() {
        long generation = ++prefetchGeneration;
        if (adjacentPrefetch != null) {
            adjacentPrefetch.close();
            adjacentPrefetch = null;
        }
        if (stopping || multiLevelFile == null || multiLevelManifest == null) {
            return;
        }
        Path manifestFile = multiLevelFile;
        List<MultiLevelManifest.Level> levels = List.copyOf(multiLevelManifest.getLevels());
        DmProject expectedProject = project;
        ImagePyramidStore.Viewport dm = imageViewport(dmBaseCanvas);
        ImagePyramidStore.Viewport player = imageViewport(playerBaseCanvas);
        adjacentPrefetch = new AdjacentLevelPrefetch<>((file, cancelled) -> {
            String id = levels.stream().filter(level -> MultiLevelService.levelFile(manifestFile, level).equals(file))
                    .map(MultiLevelManifest.Level::getId).findFirst().orElseThrow();
            DmProject floor = mapLibrary.multiLevels().loadLevelForPreparation(manifestFile, id).project();
            centerUnsetCameras(floor);
            return ImagePyramidStore.shared().prepareProject(floor, file, dm, player, true, cancelled);
        }, (file, failure) -> Platform.runLater(() -> {
            if (generation == prefetchGeneration && project == expectedProject) {
                status("Could not prepare adjacent level " + MapBrowser.displayName(file) + ": " + failure.getMessage());
            }
        }));
        adjacentPrefetch.update(AdjacentLevelPrefetch.adjacentFiles(multiLevelFile, multiLevelManifest, currentLevelId));
    }

    /** Cameras still at the origin have never been positioned, so start them at the middle of the map. */
    private void centerUnsetCameras(DmProject target) {
        double[] bounds = new FogService().contentBounds(target);
        double centerX = (bounds[0] + bounds[2]) / 2.0;
        double centerY = (bounds[1] + bounds[3]) / 2.0;
        for (DmProject.CameraState camera : List.of(target.getViews().getDmCamera(), target.getViews().getPlayerCamera())) {
            if (camera.getX() == 0 && camera.getY() == 0) {
                camera.setX(centerX);
                camera.setY(centerY);
            }
        }
    }

    private void syncControlsFromProject() {
        syncingControls = true;
        try {
            if (fogToggleButton != null) {
                fogToggleButton.setSelected(project.getFog().isEnabled());
            }
            TimeOfDayPreset preset = TimeOfDayPreset.from(project.getLighting().getTimeOfDayPreset());
            timeButtons.forEach((key, button) -> button.setSelected(key == preset));
            if (ambientBrightnessSlider != null) {
                double brightness = project.getLighting().ambientBrightnessFor(preset.name());
                ambientBrightnessCommitted = brightness;
                ambientBrightnessSlider.setValue(brightness);
                ambientBrightnessValue.setText(formatAmbientBrightness(brightness));
                // Day has no ambient darkness, so there is nothing to adjust.
                ambientBrightnessSlider.setDisable(preset.darkness() <= 0);
            }
            if (playerZoomSlider != null) {
                playerZoomSlider.setValue(project.getViews().getPlayerZoomStep());
            }
            if (freezePlayerButton != null) {
                freezePlayerButton.setSelected(frozenPlayerProject != null);
            }
            updateImageLockToggle();
            if (weatherBox != null) {
                DmProject.WeatherState weather = currentWeather();
                weatherBox.setValue(WeatherType.from(weather.getType()));
                weatherIntensityCommitted = weather.getIntensity();
                weatherIntensitySlider.setValue(weather.getIntensity());
                weatherIntensityValue.setText(Math.round(weather.getIntensity() * 100) + "%");
                weatherIntensitySlider.setDisable(WeatherType.from(weather.getType()) == WeatherType.NONE);
                lightningIntervalCommitted = weather.getLightningIntervalSeconds();
                lightningIntervalSlider.setValue(weather.getLightningIntervalSeconds());
                lightningIntervalValue.setText(String.format(java.util.Locale.ROOT, "%.1f s", weather.getLightningIntervalSeconds()));
                lightningIntervalSlider.setDisable(WeatherType.from(weather.getType()) != WeatherType.THUNDERSTORM);
            }
            if (lightTintSlider != null) {
                double tint = CanvasMapRenderer.clampLightTint(project.getLighting().getLightTint());
                lightTintCommitted = tint;
                lightTintSlider.setValue(tint);
                lightTintValue.setText(Math.round(tint * 100) + "%");
            }
            if (brightCoreSlider != null) {
                double core = CanvasMapRenderer.clampBrightCore(project.getLighting().getBrightCore());
                brightCoreCommitted = core;
                brightCoreSlider.setValue(core);
                brightCoreValue.setText(Math.round(core * 100) + "%");
            }
            if (textLayerToggle != null) {
                textLayerToggle.setSelected(project.isTextLayerVisible());
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

    private void placeWallPoint(double worldX, double worldY, boolean free) {
        double[] point = snapWallPoint(worldX, worldY, free);
        if (draftWall != null) {
            if (distance(draftWall.getX1(), draftWall.getY1(), point[0], point[1]) < 2) {
                return;
            }
            DmProject.WallSegment wall = DmProject.WallSegment.builder()
                    .x1(draftWall.getX1()).y1(draftWall.getY1()).x2(point[0]).y2(point[1]).build();
            executeWithFogHistory("Add wall", () -> project.getWalls().add(wall),
                    () -> project.getWalls().remove(wall));
        }
        draftWall = DmProject.WallSegment.builder()
                .x1(point[0]).y1(point[1]).x2(point[0]).y2(point[1]).build();
    }

    private void finishWallDraw() {
        DmProject.WallSegment wall = draftWall;
        draftWall = null;
        if (distance(wall.getX1(), wall.getY1(), wall.getX2(), wall.getY2()) < 2) {
            return;
        }
        if (activeTool == EditorTool.DOOR_DRAW || activeTool == EditorTool.WINDOW_DRAW) {
            DmProject.Interactable portal = DmProject.Interactable.builder()
                    .id("portal-" + UUID.randomUUID())
                    .type(activeTool == EditorTool.DOOR_DRAW ? "door" : "window")
                    .x1(wall.getX1()).y1(wall.getY1()).x2(wall.getX2()).y2(wall.getY2()).build();
            executeWithFogHistory("Add " + portal.getType(), () -> project.getInteractables().add(portal),
                    () -> project.getInteractables().remove(portal));
        } else {
            executeWithHistory("Add wall", () -> project.getWalls().add(wall), () -> project.getWalls().remove(wall));
        }
    }

    private record EraseTarget(DmProject.WallSegment wall, DmProject.Interactable portal) {
    }

    private EraseTarget pickEraseTarget(double worldX, double worldY, double zoom) {
        if (!renderer.isWallLayerVisible()) {
            return null;
        }
        DmProject.Interactable badge = pickInteractableBadge(worldX, worldY);
        if (badge != null) {
            return new EraseTarget(null, badge);
        }
        double tolerance = Tuning.WALL_PICK_RADIUS.get() / Math.max(0.01, zoom);
        DmProject.WallSegment nearest = null;
        double best = tolerance;
        for (DmProject.WallSegment wall : project.getWalls()) {
            double d = distanceToSegment(worldX, worldY, wall);
            if (d <= best) {
                best = d;
                nearest = wall;
            }
        }
        DmProject.Interactable portal = pickInteractableLine(worldX, worldY);
        if (portal != null && pointToSegmentDistance(worldX, worldY,
                portal.getX1(), portal.getY1(), portal.getX2(), portal.getY2()) <= best) {
            return new EraseTarget(null, portal);
        }
        return nearest == null ? null : new EraseTarget(nearest, null);
    }

    private void eraseWallAt(double worldX, double worldY, double zoom) {
        EraseTarget target = pickEraseTarget(worldX, worldY, zoom);
        if (target == null) {
            return;
        }
        if (target.portal() != null) {
            deleteInteractable(target.portal());
        } else {
            DmProject.WallSegment wall = target.wall();
            int index = project.getWalls().indexOf(wall);
            executeWithFogHistory("Erase wall", () -> project.getWalls().remove(wall),
                    () -> project.getWalls().add(Math.min(index, project.getWalls().size()), wall));
        }
    }

    private void deleteInteractable(DmProject.Interactable portal) {
        int index = project.getInteractables().indexOf(portal);
        if (index < 0) {
            return;
        }
        executeWithFogHistory("Delete " + portal.getType(), () -> project.getInteractables().remove(portal),
                () -> project.getInteractables().add(Math.min(index, project.getInteractables().size()), portal));
    }

    private void drawErasePreview(GraphicsContext gc) {
        if (!hoverInsideCanvas || !geometryPreviewHover || editingTextId != null
                || panningDmCamera || pingArmed || laserToolActive) {
            return;
        }
        DmProject.CameraState camera = project.getViews().getDmCamera();
        EraseTarget target = pickEraseTarget(hoverWorldX, hoverWorldY, camera.getZoom());
        if (target == null) {
            return;
        }
        double x1 = target.portal() == null ? target.wall().getX1() : target.portal().getX1();
        double y1 = target.portal() == null ? target.wall().getY1() : target.portal().getY1();
        double x2 = target.portal() == null ? target.wall().getX2() : target.portal().getX2();
        double y2 = target.portal() == null ? target.wall().getY2() : target.portal().getY2();
        double w = dmFogCanvas.getWidth();
        double h = dmFogCanvas.getHeight();
        double sx1 = renderer.worldToScreenX(x1, w, camera);
        double sy1 = renderer.worldToScreenY(y1, h, camera);
        double sx2 = renderer.worldToScreenX(x2, w, camera);
        double sy2 = renderer.worldToScreenY(y2, h, camera);
        gc.save();
        gc.setStroke(Color.web("#FF6B6B", 0.95));
        gc.setLineWidth(8);
        gc.setLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
        gc.strokeLine(sx1, sy1, sx2, sy2);
        if (target.portal() != null) {
            double radius = CanvasMapRenderer.INTERACTABLE_BADGE_RADIUS + 3;
            gc.setLineWidth(3);
            gc.strokeOval((sx1 + sx2) / 2 - radius, (sy1 + sy2) / 2 - radius, radius * 2, radius * 2);
        }
        gc.restore();
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
        if (stored >= diagonalMin()) {
            return stored;
        }
        Screen screen = screens.isEmpty() ? Screen.getPrimary() : screens.get(index);
        Rectangle2D bounds = screen.getBounds();
        double physicalWidth = bounds.getWidth() * screen.getOutputScaleX();
        double physicalHeight = bounds.getHeight() * screen.getOutputScaleY();
        double dpi = Math.max(48, screen.getDpi());
        double guess = Math.hypot(physicalWidth, physicalHeight) / dpi;
        return Math.max(diagonalMin(), Math.min(diagonalMax(), Math.round(guess * 2) / 2.0));
    }

    /** Player-canvas pixels (device-independent) per physical inch on the selected screen. */
    private double playerPixelsPerInch() {
        Screen screen = resolveSelectedPlayerScreen();
        Rectangle2D bounds = screen.getBounds();
        double diagonal = screenInchesSpinner == null || screenInchesSpinner.getValue() == null
                ? Tuning.PLAYER_DIAGONAL_FALLBACK.get() : screenInchesSpinner.getValue();
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
        double step = clamp(target.getViews().getPlayerZoomStep(), Tuning.PLAYER_ZOOM_MIN_STEP.get(), Tuning.PLAYER_ZOOM_MAX_STEP.get());
        double zoom = clamp(ppi * tileInchesSpinner.getValue() * Math.pow(2, step) / cell,
                Math.min(Tuning.PLAYER_ZOOM_MIN.get(), Tuning.PLAYER_ZOOM_MAX.get()), Math.max(Tuning.PLAYER_ZOOM_MIN.get(), Tuning.PLAYER_ZOOM_MAX.get()));
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

    private static int clampFps(int fps) {
        return Math.max(1, Math.min(Tuning.MAX_FPS.get(), fps));
    }

    private static double diagonalMin() {
        return Math.min(Tuning.PLAYER_DIAGONAL_MIN.get(), Tuning.PLAYER_DIAGONAL_MAX.get());
    }

    private static double diagonalMax() {
        return Math.max(Tuning.PLAYER_DIAGONAL_MIN.get(), Tuning.PLAYER_DIAGONAL_MAX.get());
    }

    private static int textMinFont() {
        return Math.min(Tuning.TEXT_MIN_FONT.get(), Tuning.TEXT_MAX_FONT.get());
    }

    private static int textMaxFont() {
        return Math.max(Tuning.TEXT_MIN_FONT.get(), Tuning.TEXT_MAX_FONT.get());
    }

    private static int clampFontSize(int size) {
        return Math.max(textMinFont(), Math.min(textMaxFont(), size));
    }

    private VBox frameRateGrid() {
        HBox targetRow = calibrationRow("Target FPS", fpsSpinner(PREF_FPS_TARGET, targetFps, Math.min(10, Tuning.MAX_FPS.get()), Tuning.MAX_FPS.get(),
                "Frame rate while you interact with the map (mouse, keyboard, laser). Applies to DM and player view.",
                value -> targetFps = value));
        HBox animationRow = calibrationRow("Animation FPS", fpsSpinner(PREF_FPS_ANIMATION, animationFps, 1, Tuning.MAX_FPS.get(),
                "Frame rate of effect textures and light flicker. Flicker never updates faster than this, even while you interact (never above the target FPS).",
                value -> {
                    animationFps = value;
                    CanvasMapRenderer.setAnimationFps(value);
                }));
        HBox idleRow = calibrationRow("Idle FPS", fpsSpinner(PREF_FPS_IDLE, idleFps, 1, Math.min(60, Tuning.MAX_FPS.get()),
                "Frame rate when nothing moves and there was no input for a moment. Lower saves CPU.",
                value -> idleFps = value));
        VBox grid = new VBox(4, targetRow, animationRow, idleRow);
        dmControlVisibility.registerRow("performance", targetRow, "target", "target");
        dmControlVisibility.registerRow("performance", animationRow, "animation", "animation");
        dmControlVisibility.registerRow("performance", idleRow, "idle", "idle");
        dmControlVisibility.registerContainer(grid);
        return grid;
    }

    private Spinner<Integer> fpsSpinner(String key, int initial, int min, int max, String tooltip,
                                        java.util.function.IntConsumer apply) {
        Spinner<Integer> spinner = new Spinner<>();
        spinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(min, max,
                Math.max(min, Math.min(max, initial)), 5));
        spinner.setEditable(true);
        spinner.setPrefWidth(84);
        Icons.tooltip(spinner, tooltip);
        spinner.getEditor().focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                try {
                    int typed = (int) Math.round(Double.parseDouble(spinner.getEditor().getText().replace(',', '.')));
                    spinner.getValueFactory().setValue(Math.max(min, Math.min(max, typed)));
                } catch (NumberFormatException ex) {
                    spinner.getEditor().setText(String.valueOf(spinner.getValue()));
                }
            }
        });
        spinner.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue != null) {
                apply.accept(newValue);
                preferences.putInt(key, newValue);
            }
        });
        return spinner;
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
        switchToMap(file, null);
    }

    /**
     * Saves the open map and opens {@code file}. For a multilevel map {@code levelId} picks the level ({@code null}:
     * the level opened last, else the lowest one).
     */
    private void switchToMap(Path file, String levelId) {
        Path target = file.toAbsolutePath().normalize();
        boolean multiLevel = MultiLevelService.isMultiLevelFile(target);
        if (multiLevel) {
            if (multiLevelFile != null && target.equals(multiLevelFile.toAbsolutePath().normalize())
                    && (levelId == null || levelId.equals(currentLevelId))) {
                status("That level is already open.");
                return;
            }
        } else if (projectFile != null && target.equals(projectFile.toAbsolutePath().normalize())) {
            status("That map is already open.");
            return;
        }
        SaveTarget current = currentSaveTarget();
        DmProject snapshot;
        try {
            snapshot = current == null ? null : projectService.copy(project);
        } catch (IOException ex) {
            status("Could not switch map: " + ex.getMessage());
            return;
        }
        if (ioBusy) {
            status("Still working on the previous file operation.");
            updateLevelSwitcher();
            return;
        }
        boolean sameMultiLevel = multiLevel && multiLevelFile != null
                && target.equals(multiLevelFile.toAbsolutePath().normalize());
        MultiLevelManifest.Level wanted = sameMultiLevel ? multiLevelManifest.findLevel(levelId) : null;
        ImagePyramidStore.PreparedImages prefetched = adjacentPrefetch != null && wanted != null
                ? adjacentPrefetch.take(MultiLevelService.levelFile(target, wanted)) : null;
        prefetchGeneration++;
        if (adjacentPrefetch != null) {
            adjacentPrefetch.clear();
        }
        levelPreviews.clear();
        ImagePyramidStore.Viewport dmViewport = imageViewport(dmBaseCanvas);
        ImagePyramidStore.Viewport playerViewport = imageViewport(playerBaseCanvas);
        String loadingName = wanted != null ? wanted.getName() : MapBrowser.displayName(file);
        setMapLoading(true, "Loading " + loadingName + "...");
        runInBackground(sameMultiLevel ? "Switching level..." : "Loading map...", "Could not switch map: ", () -> {
            if (snapshot != null) {
                saveTo(current, snapshot);
            }
            if (multiLevel) {
                MultiLevelService.LoadedLevel loaded = mapLibrary.multiLevels().loadLevelForPreparation(target, levelId);
                centerUnsetCameras(loaded.project());
                ImagePyramidStore.PreparedImages images = prefetched != null
                        && prefetched.matches(loaded.project(), loaded.levelFile(), dmViewport, playerViewport)
                        ? prefetched : ImagePyramidStore.shared().prepareProject(loaded.project(), loaded.levelFile(),
                        dmViewport, playerViewport, false, () -> false);
                mapLibrary.multiLevels().activateLevel(target, loaded);
                return new LoadedProject(loaded.project(), loaded.levelFile(), LevelContext.of(target, loaded), images);
            }
            DmProject loaded = projectService.load(file);
            centerUnsetCameras(loaded);
            ImagePyramidStore.PreparedImages images = ImagePyramidStore.shared().prepareProject(loaded, file,
                    dmViewport, playerViewport, false, () -> false);
            return new LoadedProject(loaded, file, null, images);
        }, loaded -> {
            try {
                ImagePyramidStore.shared().adopt(loaded.images());
                switchProject(loaded.project(), loaded.file(), loaded.level());
                String frozenNote = frozenPlayerProject != null
                        ? ". Player view is still frozen on the previous " + (sameMultiLevel ? "level." : "map.") : ".";
                if (sameMultiLevel) {
                    MultiLevelManifest.Level level = currentLevel();
                    status("Switched to " + (level == null ? "level" : level.getName()) + frozenNote);
                } else {
                    status("Switched to " + MapBrowser.displayName(file) + frozenNote);
                }
                if (multiLevel) {
                    mapBrowser.invalidateThumbnails();
                }
            } finally {
                setMapLoading(false, null);
            }
        }, ex -> {
            setMapLoading(false, null);
            updateLevelSwitcher();
            scheduleAdjacentPrefetch();
        });
    }

    // ---- Multilevel maps ----

    private HBox createLevelSwitcher() {
        FontIcon icon = Icons.icon(MaterialDesignL.LAYERS_TRIPLE_OUTLINE);
        icon.getStyleClass().add("level-switcher-icon");
        levelDownButton = Icons.button(MaterialDesignA.ARROW_DOWN_BOLD, "One level down (Page Down)", () -> stepLevel(-1));
        levelUpButton = Icons.button(MaterialDesignA.ARROW_UP_BOLD, "One level up (Page Up)", () -> stepLevel(1));
        levelSelector = new ComboBox<>();
        levelSelector.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(MultiLevelManifest.Level level) {
                return level == null ? "" : level.getName();
            }

            @Override
            public MultiLevelManifest.Level fromString(String name) {
                return levelSelector.getItems().stream().filter(level -> level.getName().equals(name))
                        .findFirst().orElse(null);
            }
        });
        levelSelector.getStyleClass().add("level-selector");
        levelSelector.setPrefWidth(190);
        levelSelector.setVisibleRowCount(12);
        Icons.tooltip(levelSelector, "Go to a level (lowest level at the top)");
        levelSelector.setCellFactory(view -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(MultiLevelManifest.Level item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getName());
                if (empty || item == null || multiLevelFile == null) {
                    setTooltip(null);
                    return;
                }
                Image preview = levelPreviews.get(MultiLevelService.levelFile(multiLevelFile, item), () -> {
                    if (getItem() == item) {
                        updateItem(item, false);
                    }
                });
                javafx.scene.control.Tooltip tooltip = MapBrowser.previewTooltip(item.getName()
                        + (item.getId().equals(currentLevelId) ? " (open)" : ""), preview);
                tooltip.setShowDelay(javafx.util.Duration.millis(250));
                setTooltip(tooltip);
            }
        });
        levelSelector.setButtonCell(new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(MultiLevelManifest.Level item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getName());
            }
        });
        levelSelector.setOnAction(event -> {
            MultiLevelManifest.Level level = levelSelector.getValue();
            if (!syncingLevelSelector && level != null && !level.getId().equals(currentLevelId)) {
                switchLevel(level.getId());
            }
        });
        levelPositionLabel = new Label();
        levelPositionLabel.getStyleClass().add("level-position");
        Button manage = Icons.button(MaterialDesignP.PENCIL_OUTLINE, "Manage levels: add, remove, rename, reorder",
                () -> {
                    if (multiLevelFile != null) {
                        handleManageLevels(multiLevelFile);
                    }
                });
        extraApiControls.put("levels.down", levelDownButton);
        extraApiControls.put("levels.up", levelUpButton);
        extraApiControls.put("levels.manage", manage);
        extraApiControls.put("levels.select", levelSelector);
        HBox box = new HBox(6, icon, levelSelector, levelDownButton, levelUpButton, levelPositionLabel, manage);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("level-switcher");
        box.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        box.setVisible(false);
        return box;
    }

    /** Shows the level switcher for multilevel maps with at least two levels and syncs it with the open level. */
    private void updateLevelSwitcher() {
        if (levelSwitcher == null) {
            return;
        }
        List<MultiLevelManifest.Level> levels = multiLevelManifest == null ? List.of() : multiLevelManifest.getLevels();
        levelSwitcher.setVisible(multiLevelFile != null && levels.size() >= 2);
        int index = multiLevelManifest == null ? -1 : multiLevelManifest.indexOf(currentLevelId);
        syncingLevelSelector = true;
        try {
            levelSelector.getItems().setAll(levels);
            levelSelector.getSelectionModel().select(index);
        } finally {
            syncingLevelSelector = false;
        }
        levelDownButton.setDisable(index <= 0);
        levelUpButton.setDisable(index < 0 || index >= levels.size() - 1);
        levelPositionLabel.setText(index < 0 ? "" : (index + 1) + " / " + levels.size());
    }

    /** {@code +1} goes one level up, {@code -1} one level down. */
    private void stepLevel(int delta) {
        if (multiLevelManifest == null) {
            return;
        }
        List<MultiLevelManifest.Level> levels = multiLevelManifest.getLevels();
        int index = multiLevelManifest.indexOf(currentLevelId) + delta;
        if (index < 0 || index >= levels.size()) {
            status(delta > 0 ? "This is the highest level." : "This is the lowest level.");
            return;
        }
        switchLevel(levels.get(index).getId());
    }

    private void switchLevel(String levelId) {
        if (multiLevelFile == null) {
            return;
        }
        commitTextEdit();
        switchToMap(multiLevelFile, levelId);
    }

    private List<Path> pickDd2vttFiles(javafx.stage.Window owner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select the levels (DD2VTT)");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Universal VTT", "*.dd2vtt", "*.uvtt"));
        applyInitialImportDirectory(chooser);
        List<File> selected = chooser.showOpenMultipleDialog(owner);
        if (selected == null || selected.isEmpty()) {
            return List.of();
        }
        rememberImportDirectory(selected.get(0).toPath().getParent());
        List<Path> filtered = filterOutDuplicates(owner, selected.stream().map(File::toPath).toList());
        return filtered == null ? List.of() : filtered;
    }

    private void handleImportMultiLevel(Path suggestedFolder) {
        List<Path> files = new ArrayList<>(pickDd2vttFiles(primaryStage));
        if (files.isEmpty()) {
            return;
        }
        files.sort((a, b) -> MultiLevelService.NATURAL_ORDER.compare(LevelListDialog.stem(a), LevelListDialog.stem(b)));
        List<String> stems = files.stream().map(LevelListDialog::stem).toList();
        List<String> names = MultiLevelService.defaultLevelNames(stems);
        List<LevelListDialog.Row> rows = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            rows.add(new LevelListDialog.Row(new MultiLevelService.Dd2vtt(files.get(i)), names.get(i),
                    "Import " + files.get(i).getFileName()));
        }
        Optional<LevelListDialog.Result> plan = LevelListDialog.show(primaryStage, mapLibrary,
                "Import multilevel map", "Order the levels — the lowest level is at the top",
                MaterialDesignL.LAYERS_PLUS, "Next", rows, false, this::pickDd2vttFiles);
        if (plan.isEmpty() || plan.get().plan().isEmpty()) {
            return;
        }
        Path folder = suggestedFolder != null ? suggestedFolder : mapLibrary.getRoot();
        Optional<MapLocationDialog.Selection> selection = MapLocationDialog.show(primaryStage, mapLibrary,
                "Import multilevel map", MaterialDesignL.LAYERS_PLUS, "Import",
                unusedMapName(folder, MultiLevelService.commonName(stems)), suggestedFolder);
        if (selection.isEmpty()) {
            return;
        }
        MapLocationDialog.Selection target = selection.get();
        List<MultiLevelService.PlanItem> items = plan.get().plan();
        leaveCurrentMap(() -> runLevelChange("Creating multilevel map...", "Could not create the multilevel map", true,
                (levels, progress) -> levels.create(target.folder(), target.name(), items, progress),
                result -> "Created the multilevel map " + MapBrowser.displayName(result.manifestFile()) + " with "
                        + items.size() + " levels."));
    }

    private void handleMergeIntoMultiLevel(List<MapLibraryService.Entry> maps) {
        handleMergeIntoMultiLevel(maps, null, true);
    }

    /**
     * Creates a multilevel map from library maps; {@code folder} is where it goes ({@code null}: the first map's
     * folder). {@code sortByName} orders the maps by name, else they keep the given order (lowest first).
     */
    private void handleMergeIntoMultiLevel(List<MapLibraryService.Entry> maps, Path folder, boolean sortByName) {
        if (maps.isEmpty()) {
            return;
        }
        List<MapLibraryService.Entry> sorted = new ArrayList<>(maps);
        if (sortByName) {
            sorted.sort((a, b) -> MultiLevelService.NATURAL_ORDER.compare(a.name(), b.name()));
        }
        List<String> mapNames = sorted.stream().map(MapLibraryService.Entry::name).toList();
        List<String> names = MultiLevelService.defaultLevelNames(mapNames);
        List<LevelListDialog.Row> rows = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            rows.add(LevelListDialog.libraryRow(mapLibrary, sorted.get(i), names.get(i)));
        }
        Optional<LevelListDialog.Result> plan = LevelListDialog.show(primaryStage, mapLibrary,
                "Create multilevel map", "Order the levels — the lowest level is at the top.\n"
                        + "The maps are moved into the new multilevel map.",
                MaterialDesignL.LAYERS_PLUS, "Next", rows, false, this::pickDd2vttFiles);
        if (plan.isEmpty() || plan.get().plan().isEmpty()) {
            return;
        }
        Path targetFolder = folder != null ? folder : sorted.get(0).containingFolder();
        Optional<MapLocationDialog.Selection> selection = MapLocationDialog.show(primaryStage, mapLibrary,
                "Create multilevel map", MaterialDesignL.LAYERS_PLUS, "Create",
                unusedMapName(targetFolder, MultiLevelService.commonName(mapNames)), targetFolder);
        if (selection.isEmpty()) {
            return;
        }
        MapLocationDialog.Selection target = selection.get();
        List<MultiLevelService.PlanItem> items = plan.get().plan();
        runLevelChange("Creating multilevel map...", "Could not create the multilevel map", false,
                (levels, progress) -> levels.create(target.folder(), target.name(), items, progress),
                result -> "Created the multilevel map " + MapBrowser.displayName(result.manifestFile()) + " with "
                        + items.size() + " levels.");
    }

    private void handleManageLevels(Path manifestFile) {
        showLevelDialog(manifestFile, List.of(), "Manage levels",
                MapBrowser.displayName(manifestFile) + " — the lowest level is at the top", "Apply");
    }

    /** Drag and drop of library maps onto another map (see {@link MapBrowser.Host#dropMapOnMap}). */
    private void handleDropMapOnMap(List<MapLibraryService.Entry> dragged, MapLibraryService.Entry target) {
        if (dragged.isEmpty()) {
            return;
        }
        boolean anyMulti = target.isMultiLevel() || dragged.stream().anyMatch(MapLibraryService.Entry::isMultiLevel);
        if (!anyMulti) {
            List<MapLibraryService.Entry> maps = new ArrayList<>();
            maps.add(target);
            maps.addAll(dragged);
            handleMergeIntoMultiLevel(maps, target.containingFolder(), false);
            return;
        }
        MapLibraryService.Entry main = target.isMultiLevel() ? target
                : dragged.stream().filter(MapLibraryService.Entry::isMultiLevel).findFirst().orElseThrow();
        List<MapLibraryService.Entry> others = new ArrayList<>();
        if (main != target) {
            others.add(target);
        }
        for (MapLibraryService.Entry entry : dragged) {
            if (entry != main) {
                others.add(entry);
            }
        }
        List<LevelListDialog.Row> rows = new ArrayList<>();
        List<String> removedMaps = new ArrayList<>();
        for (MapLibraryService.Entry other : others) {
            if (!other.isMultiLevel()) {
                rows.add(LevelListDialog.libraryRow(mapLibrary, other, other.name()));
                continue;
            }
            MultiLevelManifest manifest;
            try {
                manifest = mapLibrary.multiLevels().loadManifest(other.mapFile());
            } catch (IOException | RuntimeException ex) {
                Dialogs.error(primaryStage, "Could not read the multilevel map " + other.name(), ex.getMessage());
                return;
            }
            for (MultiLevelManifest.Level level : manifest.getLevels()) {
                rows.add(new LevelListDialog.Row(new MultiLevelService.ForeignLevel(other.mapFile(), level.getId()),
                        level.getName(), "Level of " + other.name()));
            }
            removedMaps.add(other.name());
        }
        String names = others.size() == 1 ? others.get(0).name() : others.size() + " maps";
        String header = "Add " + names + " to " + main.name() + " — the lowest level is at the top.\n"
                + (removedMaps.isEmpty() ? (others.size() == 1 ? "The map is" : "The maps are")
                + " moved into the multilevel map."
                : "Multilevel maps whose levels are all taken (" + String.join(", ", removedMaps)
                + ") are removed afterwards.");
        boolean merge = !removedMaps.isEmpty();
        showLevelDialog(main.mapFile(), rows, merge ? "Merge multilevel maps" : (rows.size() == 1 ? "Add level" : "Add levels"),
                header, merge ? "Merge" : "Add");
    }

    /** The level dialog of a multilevel map, with {@code extraRows} (new levels) appended at the top end. */
    private void showLevelDialog(Path manifestFile, List<LevelListDialog.Row> extraRows, String title, String header,
                                 String actionLabel) {
        MultiLevelManifest manifest;
        try {
            manifest = mapLibrary.multiLevels().loadManifest(manifestFile);
        } catch (IOException | RuntimeException ex) {
            Dialogs.error(primaryStage, "Could not read the multilevel map", ex.getMessage());
            return;
        }
        boolean open = multiLevelFile != null
                && multiLevelFile.toAbsolutePath().normalize().equals(manifestFile.toAbsolutePath().normalize());
        List<LevelListDialog.Row> rows = new ArrayList<>();
        for (MultiLevelManifest.Level level : manifest.getLevels()) {
            String detail = open && level.getId().equals(currentLevelId) ? "Open level" : null;
            rows.add(new LevelListDialog.Row(new MultiLevelService.Existing(level.getId()), level.getName(), detail,
                    MultiLevelService.suggestedMapName(manifestFile, level)));
        }
        rows.addAll(extraRows);
        Optional<LevelListDialog.Result> edited = LevelListDialog.show(primaryStage, mapLibrary, title, header,
                MaterialDesignL.LAYERS_TRIPLE_OUTLINE, actionLabel, rows, true, this::pickDd2vttFiles);
        if (edited.isEmpty()) {
            return;
        }
        LevelListDialog.Result result = edited.get();
        String name = MapBrowser.displayName(manifestFile);
        runLevelChange("Updating levels...", "Could not change the levels", false,
                (levels, progress) -> levels.apply(manifestFile, result.plan(), result.extractions(), progress),
                applied -> levelChangeMessage(name, applied, result.extractions().size()));
    }

    private void handleDissolveMultiLevel(Path manifestFile) {
        MultiLevelManifest manifest;
        try {
            manifest = mapLibrary.multiLevels().loadManifest(manifestFile);
        } catch (IOException | RuntimeException ex) {
            Dialogs.error(primaryStage, "Could not read the multilevel map", ex.getMessage());
            return;
        }
        String name = MapBrowser.displayName(manifestFile);
        List<String> mapNames = manifest.getLevels().stream()
                .map(level -> MultiLevelService.suggestedMapName(manifestFile, level)).toList();
        if (!Dialogs.confirm(primaryStage, "Dissolve multilevel map", "Split " + name + " into separate maps?",
                MaterialDesignL.LAYERS_OFF_OUTLINE, "Every level becomes a separate map next to it:\n\n• "
                        + String.join("\n• ", mapNames) + "\n\nThe multilevel map itself is removed. Nothing is deleted.",
                "Dissolve")) {
            return;
        }
        runLevelChange("Dissolving multilevel map...", "Could not dissolve the multilevel map", false,
                (levels, progress) -> levels.dissolve(manifestFile, progress),
                applied -> "Split " + name + " into " + mapNames.size() + " separate maps.");
    }

    private static String levelChangeMessage(String name, MultiLevelService.ApplyResult result, int movedOut) {
        String moved = movedOut == 0 ? "" : " " + movedOut + (movedOut == 1 ? " level was" : " levels were")
                + " moved out as separate maps.";
        if (result.collapsedMap() != null) {
            return name + " has only one level left and is now an ordinary map." + moved;
        }
        if (result.manifestFile() == null) {
            return movedOut > 0 ? "Removed the multilevel map " + name + "." + moved
                    : "Deleted the multilevel map " + name + ".";
        }
        return "Updated the levels of " + name + "." + moved;
    }

    /** {@code name}, or a variation of it that is still free in {@code folder}. */
    private String unusedMapName(Path folder, String name) {
        List<String> candidates = new ArrayList<>(List.of(name, name + " (multilevel)"));
        for (int i = 2; i < 100; i++) {
            candidates.add(name + " " + i);
        }
        for (String candidate : candidates) {
            try {
                mapLibrary.newMapFile(folder, candidate);
                return candidate;
            } catch (IOException ex) {
                // taken or invalid: try the next one
            }
        }
        return name;
    }

    @FunctionalInterface
    private interface LevelWork {
        MultiLevelService.ApplyResult run(MultiLevelService levels, MultiLevelService.Progress progress) throws IOException;
    }

    /** {@code loaded}: the map to open afterwards; {@code fresh}: the open map is gone; else {@code manifest} refreshes the switcher. */
    private record LevelPlanOutcome(MultiLevelService.ApplyResult result, LoadedProject loaded, boolean fresh,
                                    MultiLevelManifest manifest) {
    }

    /**
     * Creates or changes multilevel maps in the background. The open map is saved first and cannot be edited while
     * the change runs. Afterwards it is re-targeted: a map/level that was moved is reopened at its new place (as a
     * level or as an ordinary map), a deleted open level is replaced by the nearest remaining level (or the map the
     * multilevel map collapsed into), and an untouched open level stays open with its undo history.
     *
     * @param openResult open the resulting multilevel map (imports)
     */
    private void runLevelChange(String busy, String failureTitle, boolean openResult, LevelWork work,
                                Function<MultiLevelService.ApplyResult, String> doneMessage) {
        if (ioBusy) {
            status("Still working on the previous file operation.");
            return;
        }
        Path openFile = projectFile == null ? null : projectFile.toAbsolutePath().normalize();
        Path openManifest = multiLevelFile == null ? null : multiLevelFile.toAbsolutePath().normalize();
        SaveTarget saveTarget = currentSaveTarget();
        DmProject savedProject = project;
        long version = historyVersion;
        DmProject snapshot = null;
        if (saveTarget != null && !openResult) {
            try {
                snapshot = projectService.copy(project);
            } catch (IOException ex) {
                status("Could not save the open map: " + ex.getMessage());
                return;
            }
        }
        DmProject toSave = snapshot;
        // The open map may be moved or replaced, so it must not be edited while the change runs.
        ImagePyramidStore.Viewport dm = imageViewport(dmBaseCanvas);
        ImagePyramidStore.Viewport player = imageViewport(playerBaseCanvas);
        prefetchGeneration++;
        if (adjacentPrefetch != null) {
            adjacentPrefetch.clear();
        }
        levelPreviews.clear();
        setMapLoading(true, busy);
        runInBackground(busy, failureTitle + ": ", () -> {
            if (toSave != null) {
                saveTo(saveTarget, toSave);
            }
            MultiLevelService levels = mapLibrary.multiLevels();
            MultiLevelService.Progress progress = (index, total, levelName) -> Platform.runLater(
                    () -> status(busy.replace("...", "") + " " + index + "/" + total + ": " + levelName + "..."));
            MultiLevelService.ApplyResult result = work.run(levels, progress);
            Path movedTo = openFile == null ? null
                    : movedLocation(new MapLibraryService.Result(result.movedMaps(), null), openFile);
            if (openResult && result.manifestFile() != null) {
                MultiLevelService.LoadedLevel loaded = levels.loadLevelForPreparation(result.manifestFile(), null);
                LoadedProject ready = prepareLoadedProject(loaded.project(), loaded.levelFile(),
                        LevelContext.of(result.manifestFile(), loaded), dm, player);
                levels.activateLevel(result.manifestFile(), loaded);
                return new LevelPlanOutcome(result, ready, false, null);
            }
            if (movedTo != null) {
                return new LevelPlanOutcome(result, loadAnyMap(levels, movedTo, null, dm, player), false, null);
            }
            if (openFile != null && !Files.exists(openFile)) {
                if (openManifest != null && Files.isRegularFile(openManifest)) {
                    return new LevelPlanOutcome(result, loadAnyMap(levels, openManifest, null, dm, player), false, null);
                }
                Path collapsed = result.collapsedMap();
                if (openManifest != null && collapsed != null
                        && collapsed.toAbsolutePath().normalize().getParent().equals(openManifest.getParent())) {
                    return new LevelPlanOutcome(result, loadAnyMap(levels, collapsed, null, dm, player), false, null);
                }
                return new LevelPlanOutcome(result, null, true, null);
            }
            if (openManifest != null && Files.isRegularFile(openManifest)) {
                return new LevelPlanOutcome(result, null, false, levels.loadManifest(openManifest));
            }
            return new LevelPlanOutcome(result, null, false, null);
        }, outcome -> {
            try {
                MultiLevelService.ApplyResult result = outcome.result();
                if (toSave != null && project == savedProject) {
                    markSaved(toSave, version);
                }
                if (outcome.loaded() != null) {
                    ImagePyramidStore.shared().adopt(outcome.loaded().images());
                    switchProject(outcome.loaded().project(), outcome.loaded().file(), outcome.loaded().level());
                } else if (outcome.fresh()) {
                    switchProject(freshProject(), null);
                } else if (outcome.manifest() != null && project == savedProject) {
                    multiLevelManifest = outcome.manifest();
                    updateLevelSwitcher();
                    updateWindowTitle();
                    mapBrowser.updateCurrentMap();
                    scheduleAdjacentPrefetch();
                }
                if (frozenPlayerProjectFile != null) {
                    Path frozenMoved = movedLocation(new MapLibraryService.Result(result.movedMaps(), null),
                            frozenPlayerProjectFile.toAbsolutePath().normalize());
                    if (frozenMoved != null) {
                        frozenPlayerProjectFile = frozenMoved;
                    }
                }
                mapBrowser.refresh();
                mapBrowser.invalidateThumbnails();
                Path select = result.manifestFile() != null ? result.manifestFile() : result.collapsedMap();
                if (select == null && !result.movedMaps().isEmpty()) {
                    select = result.movedMaps().values().iterator().next();
                }
                if (select != null) {
                    mapBrowser.select(select);
                }
                status(doneMessage.apply(result));
            } finally {
                setMapLoading(false, null);
            }
        }, ex -> {
            setMapLoading(false, null);
            Dialogs.error(primaryStage, failureTitle, ex.getMessage());
            mapBrowser.refresh();
        });
    }

    /** Loads {@code file} as whatever it is now: a multilevel map, a level of one, or an ordinary map. */
    private LoadedProject loadAnyMap(MultiLevelService levels, Path file, String levelId,
                                     ImagePyramidStore.Viewport dm, ImagePyramidStore.Viewport player) throws IOException {
        Path manifestFile = file;
        String id = levelId;
        if (!MultiLevelService.isMultiLevelFile(file)) {
            MultiLevelService.LevelRef ref = levels.locateLevel(file);
            if (ref == null) {
                return prepareLoadedProject(projectService.load(file), file, null, dm, player);
            }
            manifestFile = ref.manifestFile();
            id = ref.levelId();
        }
        MultiLevelService.LoadedLevel loaded = levels.loadLevelForPreparation(manifestFile, id);
        LoadedProject ready = prepareLoadedProject(loaded.project(), loaded.levelFile(),
                LevelContext.of(manifestFile, loaded), dm, player);
        levels.activateLevel(manifestFile, loaded);
        return ready;
    }

    /** Shows or hides a spinner over the DM canvas while a map is being switched. */
    private void setMapLoading(boolean loading, String message) {
        if (loadingOverlay == null) {
            javafx.scene.control.ProgressIndicator spinner = new javafx.scene.control.ProgressIndicator();
            spinner.setPrefSize(56, 56);
            Label label = new Label();
            label.setStyle("-fx-text-fill: white; -fx-font-size: 14px;");
            VBox box = new VBox(12, spinner, label);
            box.setAlignment(Pos.CENTER);
            loadingOverlay = new StackPane(box);
            loadingOverlay.setStyle("-fx-background-color: rgba(0, 0, 0, 0.55);");
            loadingOverlay.setVisible(false);
            loadingOverlay.setUserData(label);
            mapCenter.getChildren().add(loadingOverlay);
        }
        if (loading) {
            ((Label) loadingOverlay.getUserData()).setText(message);
        }
        loadingOverlay.setVisible(loading);
        loadingOverlay.toFront();
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
                .texture(source.getTexture())
                .border(source.isBorder())
                .emitsLight(source.isEmitsLight())
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
            case AOE_PEN -> "pen";
            case AOE_LINE -> "line";
            default -> "brush";
        };
        boolean pen = "pen".equals(type) || "line".equals(type);
        DmProject.OverlayShape shape = DmProject.OverlayShape.builder()
                .id("overlay-" + UUID.randomUUID())
                .type(type)
                .x(worldX)
                .y(worldY)
                .strokeWidth("pen".equals(type) ? Tuning.PEN_WIDTH_CELLS.get() * pixelsPerCell : brushSize.get() * pixelsPerCell)
                .color(overlayColor)
                .alpha(overlayAlpha)
                .playerVisible(overlayPlayerVisible)
                .texture(pen ? OverlayTextures.NONE : overlayTexture)
                .border(!pen && overlayBorder)
                .emitsLight(!pen && overlayEmitsLight)
                .build();
        if (isStrokeOverlay(shape)) {
            shape.getPoints().add(worldX);
            shape.getPoints().add(worldY);
            if ("line".equals(type)) {
                shape.getPoints().add(worldX);
                shape.getPoints().add(worldY);
            }
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
            case "line" -> {
                List<Double> points = shape.getPoints();
                points.set(2, worldX);
                points.set(3, worldY);
            }
            default -> {
                List<Double> points = shape.getPoints();
                double lastX = points.get(points.size() - 2);
                double lastY = points.getLast();
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
            case "line" -> distance(shape.getPoints().get(0), shape.getPoints().get(1),
                    shape.getPoints().get(2), shape.getPoints().get(3)) < 3;
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
        double tolerance = Tuning.SHAPE_PICK_RADIUS.get() / Math.max(0.01, zoom);
        for (int i = overlays.size() - 1; i >= 0; i--) {
            DmProject.OverlayShape shape = overlays.get(i);
            boolean hit = switch (shape.getType() == null ? "" : shape.getType()) {
                case "circle" -> distance(worldX, worldY, shape.getX(), shape.getY()) <= shape.getRadius();
                case "rect" -> worldX >= shape.getX() && worldX <= shape.getX() + shape.getWidth()
                        && worldY >= shape.getY() && worldY <= shape.getY() + shape.getHeight();
                case "brush", "pen", "line" -> brushHit(shape, worldX, worldY, shape.getStrokeWidth() / 2.0 + tolerance);
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

    private static boolean isStrokeOverlay(DmProject.OverlayShape shape) {
        return "brush".equals(shape.getType()) || "pen".equals(shape.getType()) || "line".equals(shape.getType());
    }

    /** Bounds {minX, minY, maxX, maxY} of the point path of a freehand/pen shape, without stroke padding. */
    private static double[] pointBounds(DmProject.OverlayShape shape) {
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
        return new double[]{minX, minY, maxX, maxY};
    }

    /** World position of the resize handle: bottom-right corner of the shape's bounds, or null if it has none. */
    private static double[] overlayHandle(DmProject.OverlayShape shape) {
        if (shape == null) {
            return null;
        }
        if ("circle".equals(shape.getType())) {
            return new double[]{shape.getX() + shape.getRadius(), shape.getY() + shape.getRadius()};
        }
        if ("rect".equals(shape.getType())) {
            return new double[]{shape.getX() + shape.getWidth(), shape.getY() + shape.getHeight()};
        }
        if (isStrokeOverlay(shape) && shape.getPoints().size() >= 2) {
            double[] b = pointBounds(shape);
            double pad = shape.getStrokeWidth() / 2.0;
            return new double[]{b[2] + pad, b[3] + pad};
        }
        return null;
    }

    private boolean isOnOverlayHandle(DmProject.OverlayShape shape, double worldX, double worldY, double zoom) {
        double[] handle = overlayHandle(shape);
        return handle != null && distance(worldX, worldY, handle[0], handle[1]) < Tuning.SHAPE_HANDLE_RADIUS.get() / Math.max(0.01, zoom);
    }

    /** Stretches the shape so that its bottom-right handle follows the mouse; always derived from the state before the drag. */
    private void resizeOverlay(DmProject.OverlayShape shape, DmProject.OverlayShape before, double worldX, double worldY) {
        double min = 8;
        switch (before.getType() == null ? "" : before.getType()) {
            case "circle" -> shape.setRadius(Math.max(min / 2, ((worldX - before.getX()) + (worldY - before.getY())) / 2.0));
            case "rect" -> {
                shape.setWidth(Math.max(min, worldX - before.getX()));
                shape.setHeight(Math.max(min, worldY - before.getY()));
            }
            default -> {
                List<Double> original = before.getPoints();
                if (original.size() < 2) {
                    return;
                }
                double[] b = pointBounds(before);
                double stroke = before.getStrokeWidth();
                double extentX = b[2] - b[0];
                double extentY = b[3] - b[1];
                double boxW = extentX + stroke;
                double boxH = extentY + stroke;
                double left = b[0] - stroke / 2.0;
                double top = b[1] - stroke / 2.0;
                double factorX = Math.max(min, worldX - left) / boxW;
                double factorY = Math.max(min, worldY - top) / boxH;
                // Freehand blobs scale as a whole (stroke included) so they do not turn into rings; pen lines keep their width.
                double newStroke = "pen".equals(before.getType()) || "line".equals(before.getType()) ? stroke : stroke * (factorX + factorY) / 2.0;
                double newExtentX = Math.max(0, boxW * factorX - newStroke);
                double newExtentY = Math.max(0, boxH * factorY - newStroke);
                double scaleX = extentX < 1 ? 1 : newExtentX / extentX;
                double scaleY = extentY < 1 ? 1 : newExtentY / extentY;
                double originX = left + newStroke / 2.0;
                double originY = top + newStroke / 2.0;
                shape.setStrokeWidth(newStroke);
                List<Double> points = shape.getPoints();
                for (int i = 0; i + 1 < original.size(); i += 2) {
                    points.set(i, originX + (original.get(i) - b[0]) * scaleX);
                    points.set(i + 1, originY + (original.get(i + 1) - b[1]) * scaleY);
                }
            }
        }
    }

    /** The texture, border and light controls do not apply to pen lines; light also needs a light-emitting texture. */
    /** Enables buttons that act on a selection only while such an element is selected. */
    private void updateSelectionControls() {
        if (removeLightButton == null || textPlayerToggle == null) {
            return;
        }
        boolean lightsSelected = !selectedLightIds().isEmpty();
        removeLightButton.setDisable(!lightsSelected);
        lightRevealButtons.forEach(button -> button.setDisable(!lightsSelected));
        deleteEffectButton.setDisable(findOverlay(selectedOverlayId) == null);
        DmProject.TextBox text = findTextBox(selectedTextId);
        deleteTextButton.setDisable(text == null);
        textAutoSizeToggle.setDisable(text == null);
        boolean roomLabel = text != null && text.isRoomLabel();
        textPlayerToggle.setDisable(text == null || roomLabel);
        boolean showPlayerControl = !roomLabel && !preferences.hiddenControls().contains("text.players");
        textPlayerToggle.setVisible(showPlayerControl);
        textPlayerToggle.setManaged(showPlayerControl);
        if (text == null && (textPlayerToggle.isSelected() || textAutoSizeToggle.isSelected())) {
            syncingControls = true;
            try {
                textPlayerToggle.setSelected(false);
                textAutoSizeToggle.setSelected(false);
            } finally {
                syncingControls = false;
            }
        }
    }

    private void updateEffectStyleControls() {
        if (overlayTextureBox == null) {
            return;
        }
        DmProject.OverlayShape selected = findOverlay(selectedOverlayId);
        boolean pen = activeTool == EditorTool.AOE_PEN || activeTool == EditorTool.AOE_LINE
                || selected != null && ("pen".equals(selected.getType()) || "line".equals(selected.getType()));
        overlayTextureBox.setDisable(pen);
        overlayBorderToggle.setDisable(pen);
        overlayLightToggle.setDisable(pen);
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
            overlayTexture = OverlayTextures.normalize(shape.getTexture());
            overlayTextureBox.setValue(overlayTexture);
            overlayBorder = shape.isBorder();
            overlayBorderToggle.setSelected(overlayBorder);
            overlayEmitsLight = shape.isEmitsLight();
            overlayLightToggle.setSelected(overlayEmitsLight);
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
        double[] handle = overlayHandle(shape);
        if (handle != null) {
            double hx = renderer.worldToScreenX(handle[0], w, camera);
            double hy = renderer.worldToScreenY(handle[1], h, camera);
            gc.setFill(Color.YELLOW);
            gc.fillRect(hx - 5, hy - 5, 10, 10);
            gc.setStroke(Color.BLACK);
            gc.setLineWidth(1);
            gc.strokeRect(hx - 5, hy - 5, 10, 10);
        }
    }

    private static String toHex(Color color) {
        return String.format(Locale.ROOT, "#%02X%02X%02X",
                (int) Math.round(color.getRed() * 255),
                (int) Math.round(color.getGreen() * 255),
                (int) Math.round(color.getBlue() * 255));
    }

    // ---- Text boxes ----

    private void initTextEditor() {
        textEditor.setOnContentChanged(() -> {
            DmProject.TextBox box = findTextBox(editingTextId);
            if (box != null) {
                box.setRuns(textEditor.runs());
                if (box.isAutoSize()) {
                    fitTextBox(box);
                    updateTextEditorPlacement();
                }
            }
        });
        textEditor.setOnCaretStyleChanged((size, color) -> syncTextStyleControls(size, color));
        textEditor.setOnFinish(this::commitTextEdit);
    }

    private void showTextContextMenu(DmProject.TextBox box, double screenX, double screenY) {
        String id = box.getId();
        ContextMenu menu = new ContextMenu();
        CheckMenuItem visible = new CheckMenuItem("Visible to players");
        visible.setSelected(box.isPlayerVisible());
        visible.setOnAction(e -> {
            boolean value = visible.isSelected();
            executeTextChange(value ? "Show text box to players" : "Hide text box from players", id, b -> b.setPlayerVisible(value));
            DmProject.TextBox current = findTextBox(id);
            if (current != null) {
                syncTextControls(current);
            }
        });
        if (!box.isRoomLabel()) {
            menu.getItems().add(visible);
        }
        MenuItem edit = new MenuItem("Edit text");
        edit.setOnAction(e -> beginTextEdit(box, false));
        MenuItem delete = new MenuItem("Delete text");
        delete.setOnAction(e -> deleteSelectedText());
        menu.getItems().addAll(edit, delete);
        hideLightMenu();
        activeLightMenu = menu;
        menu.setOnHidden(e -> {
            if (activeLightMenu == menu) {
                activeLightMenu = null;
            }
        });
        menu.show(dmCanvas, screenX, screenY);
    }

    private DmProject.TextBox findTextBox(String id) {
        if (id == null) {
            return null;
        }
        return project.getTextBoxes().stream().filter(t -> id.equals(t.getId())).findFirst().orElse(null);
    }

    private DmProject.TextBox cloneText(DmProject.TextBox source) {
        List<DmProject.TextRun> runs = new java.util.ArrayList<>();
        for (DmProject.TextRun run : source.getRuns()) {
            runs.add(DmProject.TextRun.builder().text(run.getText()).fontSize(run.getFontSize()).color(run.getColor()).build());
        }
        return DmProject.TextBox.builder()
                .id(source.getId())
                .x(source.getX())
                .y(source.getY())
                .width(source.getWidth())
                .height(source.getHeight())
                .runs(runs)
                .backgroundColor(source.getBackgroundColor())
                .borderColor(source.getBorderColor())
                .autoSize(source.isAutoSize())
                .playerVisible(source.isPlayerVisible())
                .roomLabel(source.isRoomLabel())
                .roomLabelAnchored(source.isRoomLabelAnchored())
                .roomLabelCenterX(source.getRoomLabelCenterX())
                .roomLabelCenterY(source.getRoomLabelCenterY())
                .build();
    }

    /** Fits an auto-size box to its text; while editing, an empty box is sized for the typing font. */
    private void fitTextBox(DmProject.TextBox box) {
        double cell = project.getMap().getGrid().getPixelsPerCell();
        int emptySize = editingTextId != null && editingTextId.equals(box.getId())
                ? textEditor.typingSize() : textSizeSpinner.getValue();
        renderer.fitTextBox(box, Tuning.TEXT_AUTO_MAX_CELLS.get() * cell, emptySize);
    }

    private void setTextAutoSize(boolean enabled) {
        applyTextBoxStyle("Toggle text auto-size", box -> {
            box.setAutoSize(enabled);
            if (enabled) {
                fitTextBox(box);
            }
        });
        if (editingTextId != null) {
            updateTextEditorPlacement();
        }
    }

    private DmProject.TextBox cloneTextOrNull(DmProject.TextBox source) {
        return source == null ? null : cloneText(source);
    }

    /** Sets the text box to the given state, inserting it if missing, or removes it when state is null. */
    private void applyTextState(String id, DmProject.TextBox state) {
        if (id.equals(editingTextId)) {
            editingTextId = null;
            editingTextBefore = null;
            textEditor.hide();
            renderer.setEditingTextBoxId(null);
        }
        List<DmProject.TextBox> boxes = project.getTextBoxes();
        int index = -1;
        for (int i = 0; i < boxes.size(); i++) {
            if (id.equals(boxes.get(i).getId())) {
                index = i;
                break;
            }
        }
        if (state == null) {
            if (index >= 0) {
                boxes.remove(index);
            }
            if (id.equals(selectedTextId)) {
                selectedTextId = null;
            }
            return;
        }
        DmProject.TextBox copy = cloneText(state);
        if (index >= 0) {
            boxes.set(index, copy);
        } else {
            boxes.add(copy);
        }
    }

    private void recordTextChange(String label, String id, DmProject.TextBox before, DmProject.TextBox after) {
        DmProject.TextBox beforeCopy = cloneTextOrNull(before);
        DmProject.TextBox afterCopy = cloneTextOrNull(after);
        pushHistory(new HistoryAction(
                label,
                () -> applyTextState(id, afterCopy),
                () -> applyTextState(id, beforeCopy)
        ));
    }

    private void executeTextChange(String label, String id, Consumer<DmProject.TextBox> mutator) {
        DmProject.TextBox box = findTextBox(id);
        if (box == null) {
            return;
        }
        DmProject.TextBox before = cloneText(box);
        mutator.accept(box);
        if (box.isAutoSize()) {
            fitTextBox(box);
        }
        if (!before.equals(box)) {
            recordTextChange(label, id, before, cloneText(box));
        }
    }

    private DmProject.TextBox pickTextBox(double worldX, double worldY) {
        if (!project.isTextLayerVisible()) {
            return null;
        }
        List<DmProject.TextBox> boxes = project.getTextBoxes();
        for (int i = boxes.size() - 1; i >= 0; i--) {
            DmProject.TextBox box = boxes.get(i);
            if (worldX >= box.getX() && worldX <= box.getX() + box.getWidth()
                    && worldY >= box.getY() && worldY <= box.getY() + box.getHeight()) {
                return box;
            }
        }
        return null;
    }

    /** Handle order: NW, N, NE, E, SE, S, SW, W. */
    private double[] textHandlePosition(DmProject.TextBox box, int handle) {
        double left = box.getX();
        double top = box.getY();
        double right = left + box.getWidth();
        double bottom = top + box.getHeight();
        double midX = (left + right) / 2.0;
        double midY = (top + bottom) / 2.0;
        return switch (handle) {
            case 0 -> new double[]{left, top};
            case 1 -> new double[]{midX, top};
            case 2 -> new double[]{right, top};
            case 3 -> new double[]{right, midY};
            case 4 -> new double[]{right, bottom};
            case 5 -> new double[]{midX, bottom};
            case 6 -> new double[]{left, bottom};
            default -> new double[]{left, midY};
        };
    }

    private void rotateTexts(int steps) {
        int turns = TextBoxGeometry.normalize(CanvasMapRenderer.getTextQuarterTurns() + steps);
        CanvasMapRenderer.setTextQuarterTurns(turns);
        preferences.putInt(PREF_TEXT_ROTATION, turns * 90);
        status("Text boxes rotated to " + turns * 90 + "° on the player view.");
    }

    private int pickTextHandle(DmProject.TextBox box, double worldX, double worldY, double zoom) {
        double reach = Tuning.TEXT_HANDLE_RADIUS.get() / Math.max(0.01, zoom);
        int best = -1;
        double bestDistance = reach;
        for (int handle = 0; handle < TEXT_HANDLE_COUNT; handle++) {
            double[] p = textHandlePosition(box, handle);
            double d = distance(worldX, worldY, p[0], p[1]);
            if (d <= bestDistance) {
                bestDistance = d;
                best = handle;
            }
        }
        return best;
    }

    private Cursor textResizeCursor(int handle) {
        return switch (handle) {
            case 0 -> Cursor.NW_RESIZE;
            case 1 -> Cursor.N_RESIZE;
            case 2 -> Cursor.NE_RESIZE;
            case 3 -> Cursor.E_RESIZE;
            case 4 -> Cursor.SE_RESIZE;
            case 5 -> Cursor.S_RESIZE;
            case 6 -> Cursor.SW_RESIZE;
            default -> Cursor.W_RESIZE;
        };
    }

    private void resizeText(DmProject.TextBox box, DmProject.TextBox original, int handle, double worldX, double worldY) {
        boolean moveLeft = handle == 0 || handle == 6 || handle == 7;
        boolean moveRight = handle == 2 || handle == 3 || handle == 4;
        boolean moveTop = handle == 0 || handle == 1 || handle == 2;
        boolean moveBottom = handle == 4 || handle == 5 || handle == 6;
        double left = original.getX();
        double top = original.getY();
        double right = left + original.getWidth();
        double bottom = top + original.getHeight();
        if (moveLeft) {
            left = Math.min(worldX, right - Tuning.TEXT_MIN_BOX.get());
        }
        if (moveRight) {
            right = Math.max(worldX, left + Tuning.TEXT_MIN_BOX.get());
        }
        if (moveTop) {
            top = Math.min(worldY, bottom - Tuning.TEXT_MIN_BOX.get());
        }
        if (moveBottom) {
            bottom = Math.max(worldY, top + Tuning.TEXT_MIN_BOX.get());
        }
        box.setAutoSize(false);
        box.setX(left);
        box.setY(top);
        box.setWidth(right - left);
        box.setHeight(bottom - top);
        box.recenterRoomLabel();
    }

    private boolean pressTextWithSelectTool(double worldX, double worldY, double zoom, int clickCount) {
        if (!project.isTextLayerVisible()) {
            return false;
        }
        DmProject.TextBox selected = findTextBox(selectedTextId);
        if (selected != null) {
            int handle = pickTextHandle(selected, worldX, worldY, zoom);
            if (handle >= 0) {
                resizingTextHandle = handle;
                textDragBefore = cloneText(selected);
                return true;
            }
        }
        DmProject.TextBox hit = pickTextBox(worldX, worldY);
        if (hit == null) {
            return false;
        }
        selectedTextId = hit.getId();
        selectedOverlayId = null;
        selectedLayer = null;
        selectedLight = null;
        syncTextControls(hit);
        if (clickCount >= 2) {
            beginTextEdit(hit, false);
            return true;
        }
        draggingText = true;
        textDragBefore = cloneText(hit);
        textLastX = worldX;
        textLastY = worldY;
        return true;
    }

    private void pressWithTextTool(double worldX, double worldY) {
        DmProject.TextBox hit = pickTextBox(worldX, worldY);
        selectedOverlayId = null;
        selectedLayer = null;
        selectedLight = null;
        if (hit != null) {
            selectedTextId = hit.getId();
            syncTextControls(hit);
            beginTextEdit(hit, false);
            return;
        }
        DmProject.TextBox box = DmProject.TextBox.builder()
                .id("text-" + UUID.randomUUID())
                .x(worldX)
                .y(worldY)
                .backgroundColor(toRgba(textBackgroundPicker.getValue()))
                .borderColor(toRgba(textBorderPicker.getValue()))
                .build();
        textStartX = worldX;
        textStartY = worldY;
        project.getTextBoxes().add(box);
        selectedTextId = box.getId();
        draftText = box;
    }

    private void placeRoomLabel(double worldX, double worldY) {
        if (!project.isTextLayerVisible()) {
            setTextLayerVisible(true);
        }
        RoomFillService.Result room = roomAt(worldX, worldY);
        FogMask mask = project.getFog().getMask();
        double[] position = mask == null ? new double[]{worldX, worldY}
                : RoomFillService.labelPosition(mask, roomBarrier, room, worldX, worldY);
        DmProject.TextBox box = DmProject.TextBox.builder()
                .id("text-" + UUID.randomUUID()).x(position[0]).y(position[1])
                .roomLabel(true).playerVisible(false).autoSize(true)
                .roomLabelAnchored(true).roomLabelCenterX(position[0]).roomLabelCenterY(position[1])
                .backgroundColor(Tuning.ROOM_LABEL_BACKGROUND_COLOR.get())
                .borderColor(Tuning.ROOM_LABEL_BORDER_COLOR.get()).build();
        project.getTextBoxes().add(box);
        selectedTextId = box.getId();
        selectedOverlayId = null;
        selectedLayer = null;
        selectedLight = null;
        syncTextControls(box);
        syncTextStyleControls(clampFontSize(Tuning.ROOM_LABEL_FONT_SIZE.get()), Tuning.ROOM_LABEL_TEXT_COLOR.get());
        beginTextEdit(box, true);
        updateSelectionControls();
    }

    private void updateTextDraft(double worldX, double worldY) {
        DmProject.TextBox box = draftText;
        box.setX(Math.min(textStartX, worldX));
        box.setY(Math.min(textStartY, worldY));
        box.setWidth(Math.abs(worldX - textStartX));
        box.setHeight(Math.abs(worldY - textStartY));
    }

    private void finishTextDraft() {
        DmProject.TextBox box = draftText;
        draftText = null;
        double cell = project.getMap().getGrid().getPixelsPerCell();
        if (box.getWidth() < 30 && box.getHeight() < 30) {
            box.setX(textStartX);
            box.setY(textStartY);
            box.setAutoSize(true);
            fitTextBox(box);
        } else {
            box.setWidth(Math.max(Tuning.TEXT_MIN_BOX.get(), box.getWidth()));
            box.setHeight(Math.max(Tuning.TEXT_MIN_BOX.get(), box.getHeight()));
        }
        beginTextEdit(box, true);
    }

    private void beginTextEdit(DmProject.TextBox box, boolean isNew) {
        commitTextEdit();
        roomPreview = null;
        geometryPreviewHover = false;
        editingTextId = box.getId();
        editingTextIsNew = isNew;
        editingTextBefore = isNew ? null : cloneText(box);
        selectedTextId = box.getId();
        renderer.setEditingTextBoxId(box.getId());
        textEditor.setBoxColors(box.getBackgroundColor(), box.getBorderColor());
        int typingSize = isNew && box.isRoomLabel() ? Tuning.ROOM_LABEL_FONT_SIZE.get() : textSizeSpinner.getValue();
        textEditor.show(box.getRuns(), typingSize, toHex(textColorPicker.getValue()));
        if (box.isAutoSize()) {
            fitTextBox(box);
        }
        syncingControls = true;
        try {
            textAutoSizeToggle.setSelected(box.isAutoSize());
            textPlayerToggle.setSelected(box.isPlayerVisible());
        } finally {
            syncingControls = false;
        }
        updateSelectionControls();
        updateTextEditorPlacement();
        syncTextStyleControls(textEditor.typingSize(), textEditor.typingColor());
        status("Editing text — Esc or click outside to finish. Change size and color with the Text controls.");
    }

    /** Writes the editor content into its box, records one undo step, and closes the editor. */
    private void commitTextEdit() {
        if (editingTextId == null) {
            return;
        }
        roomPreview = null;
        geometryPreviewHover = false;
        String id = editingTextId;
        boolean isNew = editingTextIsNew;
        DmProject.TextBox before = editingTextBefore;
        List<DmProject.TextRun> runs = textEditor.runs();
        editingTextId = null;
        editingTextBefore = null;
        editingTextIsNew = false;
        textEditor.hide();
        renderer.setEditingTextBoxId(null);
        if (dmCanvas != null) {
            dmCanvas.requestFocus();
        }
        DmProject.TextBox box = findTextBox(id);
        if (box == null) {
            return;
        }
        box.setRuns(runs);
        if (isNew) {
            if (runs.isEmpty()) {
                project.getTextBoxes().remove(box);
                selectedTextId = null;
            } else {
                recordTextChange("Add text", id, null, cloneText(box));
                rememberTextSettings();
            }
        } else if (before != null && !before.equals(box)) {
            recordTextChange("Edit text", id, before, cloneText(box));
        }
    }

    private void updateTextEditorPlacement() {
        if (editingTextId == null) {
            return;
        }
        DmProject.TextBox box = findTextBox(editingTextId);
        if (box == null) {
            editingTextId = null;
            textEditor.hide();
            renderer.setEditingTextBoxId(null);
            return;
        }
        DmProject.CameraState camera = project.getViews().getDmCamera();
        textEditor.place(
                renderer.worldToScreenX(box.getX(), dmCanvas.getWidth(), camera),
                renderer.worldToScreenY(box.getY(), dmCanvas.getHeight(), camera),
                box.getWidth(), box.getHeight(), camera.getZoom());
    }

    private void deleteSelectedText() {
        DmProject.TextBox box = findTextBox(selectedTextId);
        if (box == null) {
            return;
        }
        commitTextEdit();
        DmProject.TextBox before = cloneText(box);
        applyTextState(box.getId(), null);
        recordTextChange("Delete text", before.getId(), before, null);
        status("Deleted text box.");
    }

    private void setTextLayerVisible(boolean visible) {
        commitTextEdit();
        project.setTextLayerVisible(visible);
        if (textLayerToggle != null && textLayerToggle.isSelected() != visible) {
            syncingControls = true;
            try {
                textLayerToggle.setSelected(visible);
            } finally {
                syncingControls = false;
            }
        }
        if (!visible) {
            selectedTextId = null;
            draggingText = false;
            resizingTextHandle = -1;
            if (activeTool == EditorTool.TEXT) {
                setActiveTool(EditorTool.SELECT);
            }
        }
        updateCanvasCursor();
        status(visible ? "Text layer shown." : "Text layer hidden for DM and players.");
    }

    /** Keys of the copyable items (lights, text boxes, effect shapes) in the current selection. */
    private java.util.List<String> copyableKeys() {
        pruneGroup();
        java.util.List<String> keys = new java.util.ArrayList<>(groupKeys);
        if (keys.isEmpty()) {
            if (selectedLight != null && findLightById(selectedLight.getId()) != null) {
                keys.add(groupKey("light", selectedLight.getId()));
            }
            if (findTextBox(selectedTextId) != null) {
                keys.add(groupKey("text", selectedTextId));
            }
            if (findOverlay(selectedOverlayId) != null) {
                keys.add(groupKey("overlay", selectedOverlayId));
            }
        }
        keys.removeIf(key -> key.startsWith("layer:"));
        return keys;
    }

    private boolean copySelection() {
        java.util.List<String> keys = copyableKeys();
        if (keys.isEmpty()) {
            status("Select lights, effects or text boxes first, then copy them with Ctrl+C.");
            return false;
        }
        CopiedItems content = new CopiedItems();
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (String key : keys) {
            String id = keyId(key);
            if (key.startsWith("light:")) {
                content.lights.add(cloneLight(findLightById(id)));
            } else if (key.startsWith("text:")) {
                content.texts.add(cloneText(findTextBox(id)));
            } else {
                content.overlays.add(cloneOverlay(findOverlay(id)));
            }
            double[] bounds = groupBounds(key);
            if (bounds != null) {
                minX = Math.min(minX, bounds[0]);
                minY = Math.min(minY, bounds[1]);
                maxX = Math.max(maxX, bounds[2]);
                maxY = Math.max(maxY, bounds[3]);
            }
        }
        content.centerX = (minX + maxX) / 2.0;
        content.centerY = (minY + maxY) / 2.0;
        CopiedItems = content;
        status("Copied " + keys.size() + (keys.size() == 1 ? " item" : " items")
                + ". Paste with Ctrl+V, also on another map.");
        return true;
    }

    /** Pastes the copied items centered on the cursor as one undo step. */
    private void pasteSelection() {
        CopiedItems content = CopiedItems;
        if (content == null || content.isEmpty()) {
            status("Nothing copied yet.");
            return;
        }
        commitTextEdit();
        if (!content.texts.isEmpty() && !project.isTextLayerVisible()) {
            setTextLayerVisible(true);
        }
        DmProject.CameraState camera = project.getViews().getDmCamera();
        double dx = (hoverInsideCanvas ? hoverWorldX : camera.getX()) - content.centerX;
        double dy = (hoverInsideCanvas ? hoverWorldY : camera.getY()) - content.centerY;

        java.util.List<DmProject.LightSource> lights = new java.util.ArrayList<>();
        for (DmProject.LightSource source : content.lights) {
            DmProject.LightSource copy = cloneLight(source);
            copy.setId("light-" + UUID.randomUUID());
            copy.setX(copy.getX() + dx);
            copy.setY(copy.getY() + dy);
            lights.add(copy);
        }
        java.util.List<DmProject.OverlayShape> overlays = new java.util.ArrayList<>();
        for (DmProject.OverlayShape source : content.overlays) {
            DmProject.OverlayShape copy = cloneOverlay(source);
            copy.setId("overlay-" + UUID.randomUUID());
            translateOverlay(copy, dx, dy);
            overlays.add(copy);
        }
        java.util.List<DmProject.TextBox> texts = new java.util.ArrayList<>();
        for (DmProject.TextBox source : content.texts) {
            DmProject.TextBox copy = cloneText(source);
            copy.setRoomLabelAnchored(false);
            copy.setId("text-" + UUID.randomUUID());
            copy.setX(copy.getX() + dx);
            copy.setY(copy.getY() + dy);
            texts.add(copy);
        }

        Runnable redo = () -> {
            for (DmProject.LightSource light : lights) {
                if (findLightById(light.getId()) == null) {
                    project.getLighting().getLights().add(cloneLight(light));
                }
            }
            overlays.forEach(shape -> applyOverlayState(shape.getId(), shape));
            texts.forEach(box -> applyTextState(box.getId(), box));
        };
        Runnable undo = () -> {
            lights.forEach(light -> project.getLighting().getLights()
                    .removeIf(l -> light.getId().equals(l.getId())));
            overlays.forEach(shape -> applyOverlayState(shape.getId(), null));
            texts.forEach(box -> applyTextState(box.getId(), null));
            pruneGroup();
        };
        executeWithFogHistory("Paste", redo, undo);

        clearGroup();
        clearSingleSelection();
        java.util.List<String> pasted = new java.util.ArrayList<>();
        lights.forEach(l -> pasted.add(groupKey("light", l.getId())));
        overlays.forEach(o -> pasted.add(groupKey("overlay", o.getId())));
        texts.forEach(t -> pasted.add(groupKey("text", t.getId())));
        if (pasted.size() == 1) {
            if (!lights.isEmpty()) {
                selectedLight = findLightById(lights.get(0).getId());
            } else if (!overlays.isEmpty()) {
                selectedOverlayId = overlays.get(0).getId();
            } else {
                selectedTextId = texts.get(0).getId();
                syncTextControls(findTextBox(selectedTextId));
            }
        } else {
            groupKeys.addAll(pasted);
        }
        status("Pasted " + pasted.size() + (pasted.size() == 1 ? " item." : " items."));
    }

    /** Copied items in the coordinates of the map they came from; survives map switches. */
    private static final class CopiedItems {
        private final java.util.List<DmProject.LightSource> lights = new java.util.ArrayList<>();
        private final java.util.List<DmProject.OverlayShape> overlays = new java.util.ArrayList<>();
        private final java.util.List<DmProject.TextBox> texts = new java.util.ArrayList<>();
        private double centerX;
        private double centerY;

        private boolean isEmpty() {
            return lights.isEmpty() && overlays.isEmpty() && texts.isEmpty();
        }
    }

    // Text style controls

    private void applyTextFontSize(int size) {
        if (editingTextId != null) {
            textEditor.applyFontSize(size);
            DmProject.TextBox editing = findTextBox(editingTextId);
            if (editing != null && editing.isAutoSize()) {
                fitTextBox(editing);
                updateTextEditorPlacement();
            }
            textEditor.focus();
        } else {
            executeTextChange("Change text size", selectedTextId,
                    box -> box.getRuns().forEach(run -> run.setFontSize(size)));
        }
        rememberTextSettings();
    }

    private void applyTextColor(String color) {
        if (editingTextId != null) {
            textEditor.applyTextColor(color);
            textEditor.focus();
        } else {
            executeTextChange("Change text color", selectedTextId,
                    box -> box.getRuns().forEach(run -> run.setColor(color)));
        }
        rememberTextSettings();
    }

    /** Background and border belong to the whole box, so they change live while editing and undo with the edit. */
    private void applyTextBoxStyle(String label, Consumer<DmProject.TextBox> mutator) {
        if (editingTextId != null) {
            DmProject.TextBox box = findTextBox(editingTextId);
            if (box != null) {
                mutator.accept(box);
                textEditor.setBoxColors(box.getBackgroundColor(), box.getBorderColor());
                textEditor.focus();
            }
        } else {
            executeTextChange(label, selectedTextId, mutator);
        }
        rememberTextSettings();
    }

    private DmProject.TextSettings currentTextSettings() {
        return DmProject.TextSettings.builder()
                .fontSize(textSizeSpinner.getValue())
                .textColor(toHex(textColorPicker.getValue()))
                .backgroundColor(toRgba(textBackgroundPicker.getValue()))
                .borderColor(toRgba(textBorderPicker.getValue()))
                .build();
    }

    /** Stores the current text settings as this map's last used ones and as the global fallback. */
    private void rememberTextSettings() {
        DmProject.TextBox selected = findTextBox(editingTextId != null ? editingTextId : selectedTextId);
        if (selected != null && selected.isRoomLabel()) {
            return;
        }
        DmProject.TextSettings settings = currentTextSettings();
        project.setLastTextSettings(settings);
        preferences.putInt(PREF_TEXT_FONT_SIZE, settings.getFontSize());
        preferences.put(PREF_TEXT_COLOR, settings.getTextColor());
        preferences.put(PREF_TEXT_BACKGROUND, settings.getBackgroundColor());
        preferences.put(PREF_TEXT_BORDER, settings.getBorderColor());
    }

    /** The map's own last used settings, or the globally last used ones when the map has none yet. */
    private void loadTextSettingsIntoControls() {
        DmProject.TextSettings settings = project.getLastTextSettings();
        if (settings == null) {
            settings = DmProject.TextSettings.builder()
                    .fontSize(preferences.getInt(PREF_TEXT_FONT_SIZE, DmProject.DEFAULT_TEXT_SIZE))
                    .textColor(preferences.get(PREF_TEXT_COLOR, DmProject.DEFAULT_TEXT_COLOR))
                    .backgroundColor(preferences.get(PREF_TEXT_BACKGROUND, DmProject.DEFAULT_TEXT_BACKGROUND))
                    .borderColor(preferences.get(PREF_TEXT_BORDER, DmProject.TRANSPARENT))
                    .build();
        }
        syncingControls = true;
        try {
            textSizeSpinner.getValueFactory().setValue(clampFontSize(settings.getFontSize()));
            textColorPicker.setValue(CanvasMapRenderer.parseColor(settings.getTextColor()));
            textBackgroundPicker.setValue(CanvasMapRenderer.parseColor(settings.getBackgroundColor()));
            textBorderPicker.setValue(CanvasMapRenderer.parseColor(settings.getBorderColor()));
        } finally {
            syncingControls = false;
        }
    }

    /** Shows the selected box's style in the controls without treating it as a user change. */
    private void syncTextControls(DmProject.TextBox box) {
        syncingControls = true;
        try {
            if (!box.getRuns().isEmpty()) {
                DmProject.TextRun last = box.getRuns().get(box.getRuns().size() - 1);
                textSizeSpinner.getValueFactory().setValue(clampFontSize(last.getFontSize()));
                textColorPicker.setValue(CanvasMapRenderer.parseColor(last.getColor()));
            }
            textBackgroundPicker.setValue(CanvasMapRenderer.parseColor(box.getBackgroundColor()));
            textBorderPicker.setValue(CanvasMapRenderer.parseColor(box.getBorderColor()));
            textAutoSizeToggle.setSelected(box.isAutoSize());
            textPlayerToggle.setSelected(box.isPlayerVisible());
        } finally {
            syncingControls = false;
        }
        updateSelectionControls();
    }

    private void syncTextStyleControls(int size, String color) {
        syncingControls = true;
        try {
            textSizeSpinner.getValueFactory().setValue(clampFontSize(size));
            textColorPicker.setValue(CanvasMapRenderer.parseColor(color));
        } finally {
            syncingControls = false;
        }
    }

    private void drawTextSelection(GraphicsContext gc) {
        DmProject.TextBox box = findTextBox(selectedTextId);
        if (box == null || !project.isTextLayerVisible() || box.getId().equals(editingTextId)) {
            return;
        }
        DmProject.CameraState camera = project.getViews().getDmCamera();
        double w = dmFogCanvas.getWidth();
        double h = dmFogCanvas.getHeight();
        double x = renderer.worldToScreenX(box.getX(), w, camera);
        double y = renderer.worldToScreenY(box.getY(), h, camera);
        gc.setStroke(Color.YELLOW);
        gc.setLineWidth(1.5);
        gc.setLineDashes(8, 6);
        gc.strokeRect(x, y, box.getWidth() * camera.getZoom(), box.getHeight() * camera.getZoom());
        gc.setLineDashes((double[]) null);
        if (draftText != null) {
            return;
        }
        gc.setFill(Color.YELLOW);
        for (int handle = 0; handle < TEXT_HANDLE_COUNT; handle++) {
            double[] p = textHandlePosition(box, handle);
            gc.fillRect(renderer.worldToScreenX(p[0], w, camera) - 5, renderer.worldToScreenY(p[1], h, camera) - 5, 10, 10);
        }
    }

    private static String toRgba(Color color) {
        return String.format(Locale.ROOT, "#%02X%02X%02X%02X",
                (int) Math.round(color.getRed() * 255),
                (int) Math.round(color.getGreen() * 255),
                (int) Math.round(color.getBlue() * 255),
                (int) Math.round(color.getOpacity() * 255));
    }

    // ---- Multi-selection (rectangle / Ctrl+click) ----

    private static String groupKey(String kind, String id) {
        return kind + ":" + id;
    }

    private void clearGroup() {
        finishNudge();
        groupKeys.clear();
        draggingGroup = false;
        marqueeActive = false;
        groupStartPositions = null;
        groupFogBefore = null;
    }

    private void clearSingleSelection() {
        finishNudge();
        selectedLight = null;
        selectedTextId = null;
        selectedOverlayId = null;
        selectedLayer = null;
    }

    /** Drops group members that no longer exist (deleted, undone, or image layers that got locked). */
    private void pruneGroup() {
        groupKeys.removeIf(key -> groupBounds(key) == null);
    }

    private void seedGroupFromSingleSelection() {
        if (selectedLight != null && findLightById(selectedLight.getId()) != null) {
            groupKeys.add(groupKey("light", selectedLight.getId()));
        }
        if (findTextBox(selectedTextId) != null) {
            groupKeys.add(groupKey("text", selectedTextId));
        }
        if (findOverlay(selectedOverlayId) != null) {
            groupKeys.add(groupKey("overlay", selectedOverlayId));
        }
        if (selectedLayer != null && !isImageLayerLocked() && findLayerById(selectedLayer.getId()) != null) {
            groupKeys.add(groupKey("layer", selectedLayer.getId()));
        }
    }

    /** Topmost selectable item under the point, in the same priority order as single selection. */
    private String pickGroupKey(double worldX, double worldY, double zoom) {
        DmProject.LightSource light = pickNearestLight(worldX, worldY, Tuning.LIGHT_PICK_RADIUS.get() / Math.max(0.01, zoom));
        if (light != null) {
            return groupKey("light", light.getId());
        }
        DmProject.TextBox box = pickTextBox(worldX, worldY);
        if (box != null) {
            return groupKey("text", box.getId());
        }
        DmProject.OverlayShape overlay = pickOverlay(worldX, worldY, zoom);
        if (overlay != null) {
            return groupKey("overlay", overlay.getId());
        }
        DmProject.ImageLayer layer = isImageLayerLocked() ? null : pickTopmostLayer(worldX, worldY);
        return layer == null ? null : groupKey("layer", layer.getId());
    }

    private boolean onSingleSelectionHandle(double worldX, double worldY, double zoom) {
        DmProject.TextBox text = project.isTextLayerVisible() ? findTextBox(selectedTextId) : null;
        if (text != null && pickTextHandle(text, worldX, worldY, zoom) >= 0) {
            return true;
        }
        if (isOnOverlayHandle(findOverlay(selectedOverlayId), worldX, worldY, zoom)) {
            return true;
        }
        return selectedLayer != null && !isImageLayerLocked()
                && distance(worldX, worldY, selectedLayer.getX() + selectedLayer.getWidth(),
                selectedLayer.getY() + selectedLayer.getHeight()) < Tuning.LAYER_HANDLE_RADIUS.get() / Math.max(0.01, zoom);
    }

    /** Handles Select-tool presses for group selection; returns true when the press was consumed. */
    private boolean pressGroupSelection(double worldX, double worldY, double zoom, boolean ctrl) {
        pruneGroup();
        String hit = pickGroupKey(worldX, worldY, zoom);
        if (ctrl) {
            if (hit == null) {
                return true;
            }
            if (groupKeys.isEmpty()) {
                seedGroupFromSingleSelection();
            }
            if (!groupKeys.remove(hit)) {
                groupKeys.add(hit);
            }
            clearSingleSelection();
            status(groupKeys.size() + " selected. Drag to move, Del to delete, Ctrl+click to add or remove.");
            return true;
        }
        if (!groupKeys.isEmpty()) {
            if (hit != null && groupKeys.contains(hit)) {
                draggingGroup = true;
                groupLastX = worldX;
                groupLastY = worldY;
                groupStartPositions = captureGroupPositions();
                groupFogBefore = groupKeys.stream().anyMatch(k -> k.startsWith("light:")) ? snapshotFog() : null;
                return true;
            }
            clearGroup();
        }
        if (hit == null && !onSingleSelectionHandle(worldX, worldY, zoom)) {
            clearSingleSelection();
            marqueeActive = true;
            marqueeStartX = worldX;
            marqueeStartY = worldY;
            marqueeCurrentX = worldX;
            marqueeCurrentY = worldY;
            return true;
        }
        return false;
    }

    private static String keyId(String key) {
        return key.substring(key.indexOf(':') + 1);
    }

    private double[] groupPosition(String key) {
        String id = keyId(key);
        if (key.startsWith("light:")) {
            DmProject.LightSource light = findLightById(id);
            return light == null ? null : new double[]{light.getX(), light.getY()};
        }
        if (key.startsWith("text:")) {
            DmProject.TextBox box = findTextBox(id);
            return box == null ? null : box.isRoomLabel()
                    ? new double[]{box.getX(), box.getY(), box.isRoomLabelAnchored() ? 1 : 0,
                    box.getRoomLabelCenterX(), box.getRoomLabelCenterY()}
                    : new double[]{box.getX(), box.getY()};
        }
        if (key.startsWith("overlay:")) {
            DmProject.OverlayShape shape = findOverlay(id);
            return shape == null ? null : new double[]{shape.getX(), shape.getY()};
        }
        DmProject.ImageLayer layer = findLayerById(id);
        return layer == null ? null : new double[]{layer.getX(), layer.getY()};
    }

    private Map<String, double[]> captureGroupPositions() {
        return capturePositions(groupKeys);
    }

    private Map<String, double[]> capturePositions(java.util.Set<String> keys) {
        Map<String, double[]> positions = new java.util.LinkedHashMap<>();
        for (String key : keys) {
            double[] position = groupPosition(key);
            if (position != null) {
                positions.put(key, position);
            }
        }
        return positions;
    }

    private void moveGroupBy(double dx, double dy) {
        for (String key : groupKeys) {
            double[] p = groupPosition(key);
            if (p != null) {
                moveGroupItemTo(key, p[0] + dx, p[1] + dy);
            }
        }
    }

    private void moveGroupItemTo(String key, double x, double y) {
        String id = keyId(key);
        if (key.startsWith("light:")) {
            DmProject.LightSource light = findLightById(id);
            if (light != null) {
                light.setX(x);
                light.setY(y);
            }
        } else if (key.startsWith("text:")) {
            DmProject.TextBox box = findTextBox(id);
            if (box != null) {
                if (!same(box.getX(), x) || !same(box.getY(), y)) {
                    box.setRoomLabelAnchored(false);
                }
                box.setX(x);
                box.setY(y);
            }
        } else if (key.startsWith("overlay:")) {
            DmProject.OverlayShape shape = findOverlay(id);
            if (shape != null) {
                translateOverlay(shape, x - shape.getX(), y - shape.getY());
            }
        } else {
            DmProject.ImageLayer layer = findLayerById(id);
            if (layer != null) {
                layer.setX(x);
                layer.setY(y);
            }
        }
    }

    private void finishGroupDrag() {
        draggingGroup = false;
        Map<String, double[]> before = groupStartPositions;
        FogMask.Snapshot fogBefore = groupFogBefore;
        groupStartPositions = null;
        groupFogBefore = null;
        if (before == null) {
            return;
        }
        recordPositionMove(before, capturePositions(before.keySet()), fogBefore);
    }

    private void recordPositionMove(Map<String, double[]> before, Map<String, double[]> after,
                                    FogMask.Snapshot fogBefore) {
        boolean changed = before.entrySet().stream().anyMatch(e -> {
            double[] now = after.get(e.getKey());
            return now != null && !java.util.Arrays.equals(e.getValue(), now);
        });
        if (!changed) {
            FogMask.Snapshot fogAfter = fogBefore == null ? null : snapshotFog();
            if (fogAfter == null || fogBefore.sameBits(fogAfter)) {
                return;
            }
        }
        Runnable redo = () -> after.forEach(this::restoreGroupPosition);
        Runnable undo = () -> before.forEach(this::restoreGroupPosition);
        if (fogBefore != null) {
            recordWithFog("Move selection", fogBefore, redo, undo);
        } else {
            recordHistory("Move selection", redo, undo);
        }
    }

    private void restoreGroupPosition(String key, double[] position) {
        moveGroupItemTo(key, position[0], position[1]);
        if (key.startsWith("text:") && position.length >= 5) {
            DmProject.TextBox box = findTextBox(keyId(key));
            if (box != null) {
                box.setRoomLabelAnchored(position[2] != 0);
                box.setRoomLabelCenterX(position[3]);
                box.setRoomLabelCenterY(position[4]);
            }
        }
    }

    private java.util.Set<String> selectedMovementKeys() {
        java.util.Set<String> keys = new java.util.LinkedHashSet<>(groupKeys);
        if (keys.isEmpty()) {
            if (selectedLight != null) {
                keys.add(groupKey("light", selectedLight.getId()));
            }
            if (selectedTextId != null) {
                keys.add(groupKey("text", selectedTextId));
            }
            if (selectedOverlayId != null) {
                keys.add(groupKey("overlay", selectedOverlayId));
            }
            if (selectedLayer != null) {
                keys.add(groupKey("layer", selectedLayer.getId()));
            }
        }
        keys.removeIf(key -> groupBounds(key) == null);
        return keys;
    }

    private void handleNudgePressed(javafx.scene.input.KeyEvent event) {
        KeyCode code = event.getCode();
        if (code != KeyCode.UP && code != KeyCode.DOWN && code != KeyCode.LEFT && code != KeyCode.RIGHT) {
            return;
        }
        if (dmCanvas.getScene() == null || dmCanvas.getScene().getFocusOwner() != dmCanvas
                || (dmCanvas.getScene().getWindow() != null && !dmCanvas.getScene().getWindow().isFocused())
                || event.isControlDown() || event.isAltDown() || event.isMetaDown()
                || editingTextId != null
                || (textEditor != null && textEditor.isShowing())
                || interactionInProgress() || draggingGroup || marqueeActive || canvasMouseDown
                || pingArmed || laserActive || laserToolActive) {
            return;
        }
        java.util.Set<String> keys = selectedMovementKeys();
        if (nudgeStartPositions != null && !nudgeStartPositions.keySet().equals(keys)) {
            finishNudge();
        }
        if (keys.isEmpty()) {
            return;
        }
        if (nudgeStartPositions == null) {
            nudgeStartPositions = capturePositions(keys);
            nudgeFogBefore = keys.stream().anyMatch(key -> key.startsWith("light:")) ? snapshotFog() : null;
        }
        nudgeArrowKeys.add(code);
        double step = project.getMap().getGrid().getPixelsPerCell() * (event.isShiftDown() ? 1.0 : 0.1);
        double dx = code == KeyCode.LEFT ? -step : code == KeyCode.RIGHT ? step : 0;
        double dy = code == KeyCode.UP ? -step : code == KeyCode.DOWN ? step : 0;
        for (String key : keys) {
            double[] position = groupPosition(key);
            moveGroupItemTo(key, position[0] + dx, position[1] + dy);
        }
        // Rotation transforms the model itself; world axes already match the displayed DM axes.
        lightingEngine.update(project);
        historyVersion++;
        lastInputNanos = System.nanoTime();
        renderDm();
        renderPlayer();
        event.consume();
    }

    private void finishNudge() {
        Map<String, double[]> before = nudgeStartPositions;
        FogMask.Snapshot fogBefore = nudgeFogBefore;
        nudgeStartPositions = null;
        nudgeFogBefore = null;
        nudgeArrowKeys.clear();
        if (before != null) {
            recordPositionMove(before, capturePositions(before.keySet()), fogBefore);
        }
    }

    private void deleteGroup() {
        commitTextEdit();
        java.util.List<String> keys = new java.util.ArrayList<>(groupKeys);
        Map<String, Object> backups = new java.util.LinkedHashMap<>();
        for (String key : keys) {
            String id = keyId(key);
            if (key.startsWith("light:")) {
                backups.put(key, cloneLight(findLightById(id)));
            } else if (key.startsWith("text:")) {
                backups.put(key, cloneText(findTextBox(id)));
            } else if (key.startsWith("overlay:")) {
                backups.put(key, cloneOverlay(findOverlay(id)));
            } else {
                backups.put(key, cloneLayer(findLayerById(id)));
            }
        }
        Runnable redo = () -> {
            for (String key : keys) {
                String id = keyId(key);
                if (key.startsWith("light:")) {
                    project.getLighting().getLights().removeIf(l -> id.equals(l.getId()));
                } else if (key.startsWith("text:")) {
                    applyTextState(id, null);
                } else if (key.startsWith("overlay:")) {
                    applyOverlayState(id, null);
                } else {
                    project.getImageLayers().removeIf(l -> id.equals(l.getId()));
                }
            }
            clearGroup();
            clearSingleSelection();
        };
        Runnable undo = () -> {
            for (Map.Entry<String, Object> entry : backups.entrySet()) {
                String key = entry.getKey();
                String id = keyId(key);
                if (key.startsWith("light:")) {
                    if (findLightById(id) == null) {
                        project.getLighting().getLights().add(cloneLight((DmProject.LightSource) entry.getValue()));
                    }
                } else if (key.startsWith("text:")) {
                    applyTextState(id, (DmProject.TextBox) entry.getValue());
                } else if (key.startsWith("overlay:")) {
                    applyOverlayState(id, (DmProject.OverlayShape) entry.getValue());
                } else if (findLayerById(id) == null) {
                    project.getImageLayers().add(cloneLayer((DmProject.ImageLayer) entry.getValue()));
                }
            }
            groupKeys.clear();
            groupKeys.addAll(keys);
        };
        int count = keys.size();
        executeWithFogHistory("Delete selection", redo, undo);
        status("Deleted " + count + " selected items.");
    }

    /** World bounds {minX, minY, maxX, maxY} of a group member, or null when it no longer exists / is not selectable. */
    private double[] groupBounds(String key) {
        String id = keyId(key);
        if (key.startsWith("light:")) {
            DmProject.LightSource light = findLightById(id);
            return light == null ? null : new double[]{light.getX(), light.getY(), light.getX(), light.getY()};
        }
        if (key.startsWith("text:")) {
            DmProject.TextBox box = project.isTextLayerVisible() ? findTextBox(id) : null;
            return box == null ? null
                    : new double[]{box.getX(), box.getY(), box.getX() + box.getWidth(), box.getY() + box.getHeight()};
        }
        if (key.startsWith("overlay:")) {
            DmProject.OverlayShape shape = findOverlay(id);
            if (shape == null) {
                return null;
            }
            HistoryFeedback.Target bounds = HistoryFeedback.overlayBounds(shape);
            return bounds == null ? null : new double[]{bounds.x1(), bounds.y1(), bounds.x2(), bounds.y2()};
        }
        DmProject.ImageLayer layer = isImageLayerLocked() ? null : findLayerById(id);
        return layer == null ? null
                : new double[]{layer.getX(), layer.getY(), layer.getX() + layer.getWidth(), layer.getY() + layer.getHeight()};
    }

    private void selectInMarquee() {
        double zoom = Math.max(0.01, project.getViews().getDmCamera().getZoom());
        if (Math.abs(marqueeCurrentX - marqueeStartX) < Tuning.MARQUEE_MIN_DRAG.get() / zoom
                && Math.abs(marqueeCurrentY - marqueeStartY) < Tuning.MARQUEE_MIN_DRAG.get() / zoom) {
            return;
        }
        groupKeys.clear();
        groupKeys.addAll(keysInMarquee());
        if (!groupKeys.isEmpty()) {
            status(groupKeys.size() + " selected. Drag to move, Del to delete, Ctrl+click to add or remove.");
        }
    }

    private java.util.List<String> keysInMarquee() {
        double minX = Math.min(marqueeStartX, marqueeCurrentX);
        double maxX = Math.max(marqueeStartX, marqueeCurrentX);
        double minY = Math.min(marqueeStartY, marqueeCurrentY);
        double maxY = Math.max(marqueeStartY, marqueeCurrentY);
        java.util.List<String> candidates = new java.util.ArrayList<>();
        project.getLighting().getLights().forEach(l -> candidates.add(groupKey("light", l.getId())));
        project.getTextBoxes().forEach(t -> candidates.add(groupKey("text", t.getId())));
        project.getOverlays().forEach(o -> candidates.add(groupKey("overlay", o.getId())));
        project.getImageLayers().forEach(l -> candidates.add(groupKey("layer", l.getId())));
        java.util.List<String> result = new java.util.ArrayList<>();
        for (String key : candidates) {
            double[] b = groupBounds(key);
            if (b == null) {
                continue;
            }
            // Big image layers only count when fully enclosed; everything else when touched by the rectangle.
            boolean selected = key.startsWith("layer:")
                    ? b[0] >= minX && b[1] >= minY && b[2] <= maxX && b[3] <= maxY
                    : b[2] >= minX && b[0] <= maxX && b[3] >= minY && b[1] <= maxY;
            if (selected) {
                result.add(key);
            }
        }
        return result;
    }

    private void strokeGroupItem(GraphicsContext gc, String key, DmProject.CameraState camera, double w, double h) {
        double[] b = groupBounds(key);
        if (b == null) {
            return;
        }
        double zoom = camera.getZoom();
        double x = renderer.worldToScreenX(b[0], w, camera);
        double y = renderer.worldToScreenY(b[1], h, camera);
        if (key.startsWith("light:")) {
            gc.strokeOval(x - 14, y - 14, 28, 28);
        } else {
            gc.strokeRect(x - 2, y - 2, (b[2] - b[0]) * zoom + 4, (b[3] - b[1]) * zoom + 4);
        }
    }

    private void drawGroupSelection(GraphicsContext gc) {
        DmProject.CameraState camera = project.getViews().getDmCamera();
        double w = dmFogCanvas.getWidth();
        double h = dmFogCanvas.getHeight();
        double zoom = camera.getZoom();
        gc.setLineDashes(8, 6);
        if (!groupKeys.isEmpty()) {
            gc.setStroke(Color.YELLOW);
            gc.setLineWidth(1.5);
            for (String key : groupKeys) {
                strokeGroupItem(gc, key, camera, w, h);
            }
        }
        if (marqueeActive) {
            java.util.List<String> inside = keysInMarquee();
            boolean any = !inside.isEmpty();
            Color accent = any ? Color.LIMEGREEN : Color.CYAN;
            double x = renderer.worldToScreenX(Math.min(marqueeStartX, marqueeCurrentX), w, camera);
            double y = renderer.worldToScreenY(Math.min(marqueeStartY, marqueeCurrentY), h, camera);
            double rw = Math.abs(marqueeCurrentX - marqueeStartX) * zoom;
            double rh = Math.abs(marqueeCurrentY - marqueeStartY) * zoom;
            gc.setFill(Color.color(accent.getRed(), accent.getGreen(), accent.getBlue(), any ? 0.22 : 0.12));
            gc.fillRect(x, y, rw, rh);
            gc.setStroke(accent);
            gc.setLineWidth(1.5);
            gc.strokeRect(x, y, rw, rh);
            gc.setLineDashes((double[]) null);
            gc.setLineWidth(2.5);
            for (String key : inside) {
                strokeGroupItem(gc, key, camera, w, h);
            }
            if (any) {
                gc.setFill(accent);
                gc.fillText(String.valueOf(inside.size()), x + 6, y + 16);
            }
        }
        gc.setLineDashes((double[]) null);
    }

    // ---- Light context menu ----

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
        for (double tiles : Tuning.lightMenuRanges()) {
            RadioMenuItem item = new RadioMenuItem((tiles == Math.floor(tiles) ? String.valueOf((int) tiles) : String.valueOf(tiles)) + " tiles");
            item.setToggleGroup(rangeGroup);
            item.setSelected(Math.abs(light.getRange() - tiles * cell) < 0.5);
            item.setOnAction(e -> updateLight(id, "Change light range", l -> l.setRange(tiles * cell)));
            rangeMenu.getItems().add(item);
        }

        Menu flickerMenu = new Menu("Flicker");
        ToggleGroup flickerGroup = new ToggleGroup();
        for (Tuning.Choice<double[]> preset : Tuning.lightMenuFlicker()) {
            String name = preset.name();
            double strength = preset.value()[0];
            double speed = preset.value()[1];
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
        for (Tuning.Choice<String> option : Tuning.lightMenuColors()) {
            String color = option.value();
            RadioMenuItem item = new RadioMenuItem(option.name());
            item.setToggleGroup(colorGroup);
            item.setSelected(color.equalsIgnoreCase(light.getColor()));
            javafx.scene.shape.Rectangle swatch = new javafx.scene.shape.Rectangle(12, 12, Color.web(color));
            item.setGraphic(swatch);
            item.setOnAction(e -> updateLight(id, "Change light color", l -> l.setColor(color)));
            colorMenu.getItems().add(item);
        }

        Menu brightnessMenu = new Menu("Brightness");
        ToggleGroup brightnessGroup = new ToggleGroup();
        for (Tuning.Choice<Double> option : Tuning.lightMenuBrightness()) {
            double value = option.value();
            RadioMenuItem item = new RadioMenuItem(option.name());
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

    private void showOverlayContextMenu(DmProject.OverlayShape shape, double screenX, double screenY) {
        String id = shape.getId();
        ContextMenu menu = new ContextMenu();
        CheckMenuItem visible = new CheckMenuItem("Visible to players");
        visible.setSelected(shape.isPlayerVisible());
        visible.setOnAction(e -> {
            boolean value = visible.isSelected();
            executeOverlayChange(value ? "Show effect to players" : "Hide effect from players", id, s -> s.setPlayerVisible(value));
            DmProject.OverlayShape current = findOverlay(id);
            if (current != null) {
                syncOverlayControls(current);
            }
        });
        menu.getItems().add(visible);
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

    private java.util.List<String> selectedLightIds() {
        pruneGroup();
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (String key : groupKeys) {
            if (key.startsWith("light:")) {
                ids.add(keyId(key));
            }
        }
        if (ids.isEmpty() && selectedLight != null && findLightById(selectedLight.getId()) != null) {
            ids.add(selectedLight.getId());
        }
        return ids;
    }

    private Button lightRevealButton(Ikon icon, String name, DmProject.RevealMode mode, String effect) {
        Button button = Icons.button(icon, "Set the selected lights to \"" + name + "\": " + effect
                + " (select lights first by clicking one or dragging a box around them)", () -> setSelectedLightsReveal(mode));
        button.setDisable(true);
        lightRevealButtons.add(button);
        return button;
    }

    private Button lightPowerButton(Ikon icon, String text, boolean on) {
        Button button = Icons.button(icon, text + " (select lights first by clicking one or dragging a box around them)",
                () -> setSelectedLightsEnabled(on));
        button.setDisable(true);
        lightRevealButtons.add(button);
        return button;
    }

    private void setSelectedLightsEnabled(boolean on) {
        java.util.List<DmProject.LightSource> before = new java.util.ArrayList<>();
        java.util.List<DmProject.LightSource> after = new java.util.ArrayList<>();
        for (String id : selectedLightIds()) {
            DmProject.LightSource light = findLightById(id);
            if (light != null && light.isEnabled() != on) {
                before.add(cloneLight(light));
                DmProject.LightSource changed = cloneLight(light);
                changed.setEnabled(on);
                after.add(changed);
            }
        }
        if (after.isEmpty()) {
            return;
        }
        executeWithFogHistory(on ? "Turn lights on" : "Turn lights off",
                () -> after.forEach(this::applyLightState),
                () -> before.forEach(this::applyLightState));
        status("Turned " + after.size() + (after.size() == 1 ? " light " : " lights ") + (on ? "on." : "off."));
    }

    private void setSelectedLightsReveal(DmProject.RevealMode mode) {
        java.util.List<DmProject.LightSource> before = new java.util.ArrayList<>();
        java.util.List<DmProject.LightSource> after = new java.util.ArrayList<>();
        for (String id : selectedLightIds()) {
            DmProject.LightSource light = findLightById(id);
            DmProject.RevealMode current = light == null || light.getRevealMode() == null
                    ? DmProject.RevealMode.WHILE_LIT : light.getRevealMode();
            if (light != null && current != mode) {
                before.add(cloneLight(light));
                DmProject.LightSource changed = cloneLight(light);
                changed.setRevealMode(mode);
                after.add(changed);
            }
        }
        if (after.isEmpty()) {
            return;
        }
        executeWithFogHistory("Change light reveal mode",
                () -> after.forEach(this::applyLightState),
                () -> before.forEach(this::applyLightState));
        status("Changed the fog reveal of " + after.size() + (after.size() == 1 ? " light." : " lights."));
    }

    private void removeSelectedLights() {
        java.util.List<String> ids = selectedLightIds();
        if (ids.isEmpty()) {
            return;
        }
        if (ids.size() == 1) {
            removeLight(ids.get(0));
            return;
        }
        java.util.List<DmProject.LightSource> backups = new java.util.ArrayList<>();
        for (String id : ids) {
            backups.add(cloneLight(findLightById(id)));
        }
        executeWithHistory(
                "Remove lights",
                () -> {
                    project.getLighting().getLights().removeIf(l -> ids.contains(l.getId()));
                    groupKeys.removeIf(key -> key.startsWith("light:"));
                    selectedLight = null;
                },
                () -> {
                    for (DmProject.LightSource backup : backups) {
                        if (findLightById(backup.getId()) == null) {
                            project.getLighting().getLights().add(cloneLight(backup));
                        }
                    }
                }
        );
        status("Removed " + ids.size() + " lights.");
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
        String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".webp");
    }

    /** Map library folder: setting library.folder, default 'dmmap-projects' beside the audio library. */
    private Path resolveProjectsRoot() {
        return AppSettings.resolveConfiguredFolder(AppSettings.resolveFile(), Tuning.LIBRARY_FOLDER.get(),
                "dmmap-projects");
    }

    private void applyInitialImportDirectory(FileChooser chooser) {
        lastImportDirectory().ifPresent(chooser::setInitialDirectory);
    }

    private void applyInitialImportDirectory(DirectoryChooser chooser) {
        lastImportDirectory().ifPresent(chooser::setInitialDirectory);
    }

    private Optional<File> lastImportDirectory() {
        String lastImportDirectory = preferences.get(PREF_LAST_IMPORT_DIRECTORY, null);
        if (lastImportDirectory == null || lastImportDirectory.isBlank()) {
            return Optional.empty();
        }
        File dir = Path.of(lastImportDirectory).toFile();
        return dir.exists() && dir.isDirectory() ? Optional.of(dir) : Optional.empty();
    }

    private void rememberImportDirectory(Path directory) {
        if (directory == null) {
            return;
        }
        preferences.put(PREF_LAST_IMPORT_DIRECTORY, directory.toAbsolutePath().normalize().toString());
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
            if (statusTooltip != null) {
                statusTooltip.setText("Current activity: " + text);
            }
            statusLabel.setAccessibleText("Current activity: " + text);
        }
    }

    private void updatePlayerOutputStatus() {
        if (playerOutputLabel == null) {
            return;
        }
        String text;
        String tooltip;
        String stateClass;
        if (frozenPlayerProject != null) {
            text = playerStage == null ? "Player view: Frozen (window off)" : "Player view: Frozen";
            tooltip = playerStage == null
                    ? "The player window is closed. Its frozen snapshot is retained for when it is reopened."
                    : "Players see a frozen snapshot while you prepare changes in the DM view.";
            stateClass = "player-output-frozen";
        } else if (playerStage == null) {
            text = "Player view: Off";
            tooltip = "The player window is closed; no player output is currently visible.";
            stateClass = "player-output-off";
        } else {
            text = "Player view: Live";
            tooltip = "The player window is open and follows the current DM player view.";
            stateClass = "player-output-live";
        }
        playerOutputLabel.setText(text);
        playerOutputLabel.setAccessibleText(text);
        playerOutputLabel.setTooltip(Icons.tooltip(tooltip));
        playerOutputLabel.getStyleClass().removeAll(
                "player-output-off", "player-output-frozen", "player-output-live");
        playerOutputLabel.getStyleClass().add(stateClass);
    }

    /**
     * Freezing snapshots the whole project (map, fog, lights, effects, camera) for the player view,
     * so the DM can edit or switch maps without the players seeing any of it until unfreezing.
     */
    private void setPlayerFrozen(boolean frozen) {
        if (!frozen) {
            if (frozenPlayerProject != null && PlayerViewTransition.contentChanged(
                    frozenPlayerProject, frozenPlayerProjectFile, frozenPlayerCamera,
                    project, projectFile, project.getViews().getPlayerCamera())) {
                capturePlayerTransition();
            }
            frozenPlayerProject = null;
            frozenPlayerProjectFile = null;
            frozenPlayerCamera = null;
            playerRenderer.setWeatherFrozen(false);
            playerLightingEngine.reset();
            updatePlayerOutputStatus();
            return;
        }
        try {
            frozenPlayerProject = projectService.copy(project);
            frozenPlayerProjectFile = projectFile;
            frozenPlayerCamera = copyCamera(project.getViews().getPlayerCamera());
            playerRenderer.setWeatherFrozen(true);
            playerLightingEngine.reset();
            updatePlayerOutputStatus();
        } catch (IOException ex) {
            frozenPlayerProject = null;
            updatePlayerOutputStatus();
            status("Could not freeze player view: " + ex.getMessage());
            syncControlsFromProject();
        }
    }

    /** Light placed by a light tool; the values come from the {@code lightPreset.<id>.*} settings. */
    private record LightPreset(String id) {
        static final LightPreset TORCH = new LightPreset("torch");
        static final LightPreset CANDLE = new LightPreset("candle");
        static final LightPreset CAMPFIRE = new LightPreset("campfire");
        static final LightPreset MAGIC = new LightPreset("magic");

        private Tuning.LightPresetSettings settings() {
            return Tuning.lightPreset(id);
        }

        double rangeTiles() {
            return settings().rangeTiles().get();
        }

        String color() {
            return settings().color().get();
        }

        boolean flicker() {
            return settings().flicker().get() > 0;
        }

        double flickerStrength() {
            return flicker() ? settings().flicker().get() : Tuning.LIGHT_DEFAULT_FLICKER.get();
        }

        double flickerSpeed() {
            double speed = settings().flickerSpeed().get();
            return flicker() && speed > 0 ? speed : Tuning.LIGHT_DEFAULT_FLICKER_SPEED.get();
        }

        static LightPreset forTool(EditorTool tool) {
            return switch (tool) {
                case LIGHT_CANDLE -> CANDLE;
                case LIGHT_CAMPFIRE -> CAMPFIRE;
                case LIGHT_MAGIC -> MAGIC;
                default -> TORCH;
            };
        }
    }

    private void addLightAt(double x, double y, LightPreset preset) {
        DmProject.LightSource light = DmProject.LightSource.builder()
                .id("light-" + UUID.randomUUID())
                .x(x)
                .y(y)
                .range(project.getMap().getGrid().getPixelsPerCell() * preset.rangeTiles())
                .color(preset.color())
                .flicker(DmProject.Flicker.builder().enabled(preset.flicker())
                        .strength(preset.flickerStrength()).speed(preset.flickerSpeed()).build())
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
        finishNudge();
        doAction.run();
        pushHistory(new HistoryAction(label, doAction, undoAction));
    }

    private void pushHistory(HistoryAction action) {
        historyVersion++;
        undoStack.push(action);
        redoStack.clear();
        while (undoStack.size() > Tuning.HISTORY_MAX_STEPS.get()) {
            undoStack.removeLast();
        }
    }

    private void undo() {
        if (activeTool == EditorTool.WALL_DRAW) {
            draftWall = null;
        }
        finishNudge();
        finishPlayerViewportDrag();
        commitTextEdit();
        historyFeedback.clear();
        if (undoStack.isEmpty()) {
            status("Nothing to undo.");
            return;
        }
        HistoryAction action = undoStack.pop();
        historyVersion++;
        HistoryFeedback.Snapshot before = HistoryFeedback.capture(project, renderer.isWallLayerVisible());
        action.undo.run();
        historyFeedback.show(before, HistoryFeedback.capture(project, renderer.isWallLayerVisible()), System.nanoTime());
        redoStack.push(action);
        status("Undid: " + action.label);
    }

    private void redo() {
        if (activeTool == EditorTool.WALL_DRAW) {
            draftWall = null;
        }
        finishNudge();
        finishPlayerViewportDrag();
        commitTextEdit();
        historyFeedback.clear();
        if (redoStack.isEmpty()) {
            status("Nothing to redo.");
            return;
        }
        HistoryAction action = redoStack.pop();
        historyVersion++;
        HistoryFeedback.Snapshot before = HistoryFeedback.capture(project, renderer.isWallLayerVisible());
        action.redo.run();
        historyFeedback.show(before, HistoryFeedback.capture(project, renderer.isWallLayerVisible()), System.nanoTime());
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
                        .strength(source.getFlicker() != null ? source.getFlicker().getStrength() : Tuning.LIGHT_DEFAULT_FLICKER.get())
                        .speed(source.getFlicker() != null ? source.getFlicker().getSpeed() : Tuning.LIGHT_DEFAULT_FLICKER_SPEED.get())
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
        SELECT("Select & move", "click doors and windows, drag lights, effects, text boxes, image layers and the player viewport; right-click an element for its options; Alt + mouse wheel adjusts each selected light's radius by 1 tile",
                MaterialDesignC.CURSOR_DEFAULT, false, false),
        REVEAL_BRUSH("Reveal brush", "paint to remove fog", MaterialDesignE.ERASER, true, false),
        HIDE_BRUSH("Fog brush", "paint fog back over the map", MaterialDesignB.BRUSH, false, false),
        REVEAL_RECT("Reveal area", "drag a rectangle to remove fog", MaterialDesignS.SELECTION_DRAG, true, true),
        HIDE_RECT("Fog area", "drag a rectangle to cover it with fog", MaterialDesignR.RECTANGLE, false, true),
        REVEAL_ROOM("Reveal room", "click inside a room to reveal it up to its walls and doors; Shift+click covers it with fog again",
                MaterialDesignD.DOOR_OPEN, true, false),
        AOE_CIRCLE("Circle effect", "drag from the center outward to draw a round spell area",
                MaterialDesignC.CIRCLE_OUTLINE, false, false),
        AOE_RECT("Box effect", "drag corner to corner to draw a rectangular spell area",
                MaterialDesignS.SQUARE_OUTLINE, false, true),
        AOE_BRUSH("Freehand effect", "paint a free-form spell area with the brush", MaterialDesignB.BRUSH, false, false),
        AOE_PEN("Pen", "draw a thin freehand line in the selected color (no texture, ignores brush size)",
                MaterialDesignP.PEN, false, false),
        AOE_LINE("Line", "drag to draw a straight line in the selected color and brush size (no texture)",
                MaterialDesignV.VECTOR_LINE, false, false),
        TEXT("Text box", "drag on the map to draw a text box and type; click a text box to edit it",
                MaterialDesignT.TEXT_BOX_OUTLINE, false, false),
        WALL_DRAW("Draw walls", "click points to draw connected walls; Esc or right-click ends the chain; half-tile snap, Shift for free placement",
                MaterialDesignW.WALL, false, false),
        DOOR_DRAW("Draw door", "drag to draw a door; snaps to half tiles, hold Shift for free placement",
                MaterialDesignD.DOOR_CLOSED, false, false),
        WINDOW_DRAW("Draw window", "drag to draw a window; snaps to half tiles, hold Shift for free placement",
                MaterialDesignW.WINDOW_CLOSED, false, false),
        ROOM_LABEL("Room label", "hover to preview a room in blue; click to place and edit its permanently DM-only name",
                MaterialDesignT.TEXT_BOX_OUTLINE, false, false),
        WALL_ERASE("Erase walls", "hover to highlight a wall, door or window; click to remove it", MaterialDesignE.ERASER_VARIANT, false, false),
        LIGHT_ADD("Add light", "click the map to place a torch", MaterialDesignL.LIGHTBULB_ON, false, false),
        LIGHT_CANDLE("Candle", "click the map to place a candle", MaterialDesignC.CANDLE, false, false),
        LIGHT_CAMPFIRE("Campfire", "click the map to place a campfire", MaterialDesignC.CAMPFIRE, false, false),
        LIGHT_MAGIC("Magic light", "click the map to place a magical light", MaterialDesignA.AUTO_FIX, false, false);

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

        String description() {
            return description + (supportsBrushSize() ? "; Alt + mouse wheel over the map adjusts brush size" : "");
        }

        boolean supportsBrushSize() {
            return this == REVEAL_BRUSH || this == HIDE_BRUSH || this == AOE_BRUSH || this == AOE_LINE;
        }

        boolean isAoeTool() {
            return this == AOE_CIRCLE || this == AOE_RECT || this == AOE_BRUSH || this == AOE_PEN || this == AOE_LINE;
        }

        boolean isWallTool() {
            return isSegmentDrawTool() || this == WALL_ERASE;
        }

        boolean isSegmentDrawTool() {
            return this == WALL_DRAW || this == DOOR_DRAW || this == WINDOW_DRAW;
        }

        boolean isLightTool() {
            return isLightPlaceTool();
        }

        boolean isLightPlaceTool() {
            return this == LIGHT_ADD || this == LIGHT_CANDLE
                    || this == LIGHT_CAMPFIRE || this == LIGHT_MAGIC;
        }

        boolean isFogTool() {
            return this != SELECT && this != TEXT && this != ROOM_LABEL && !isAoeTool() && !isWallTool() && !isLightTool();
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
