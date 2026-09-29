package dmmt;

import dmmt.lighting.LightingEngine;
import dmmt.lighting.TimeOfDayPreset;
import dmmt.model.DmProject;
import dmmt.model.FogMask;
import dmmt.render.CanvasMapRenderer;
import dmmt.service.Dd2vttImportService;
import dmmt.service.FogService;
import dmmt.service.MapRotationService;
import dmmt.service.ProjectService;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
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
import javafx.util.StringConverter;

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
import java.util.UUID;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

public class DungeonMasterMapToolApplication extends Application {
    private static final String PREF_LAST_IMPORT_DIRECTORY = "lastImportDirectory";
    private static final String PREF_PLAYER_SCREEN_INDEX = "playerScreenIndex";

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
    private CheckBox overlayPlayerCheck;
    private ToggleButton fogToggleButton;
    private ToggleButton freezePlayerButton;
    private ComboBox<TimeOfDayPreset> timeOfDaySelector;
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

    @Override
    public void start(Stage stage) {
        this.project = DmProject.builder().build();

        BorderPane root = new BorderPane();
        statusLabel = new Label("Ready");
        root.setBottom(statusLabel);

        dmCanvas = new Canvas(1280, 800);
        dmFogCanvas = new Canvas(1280, 800);
        dmFogCanvas.setMouseTransparent(true);
        StackPane center = new StackPane(dmCanvas, dmFogCanvas);
        VBox overlay = createDmOverlay(stage);
        StackPane.setAlignment(overlay, Pos.TOP_LEFT);
        StackPane.setMargin(overlay, new Insets(10));
        center.getChildren().add(overlay);
        dmCanvas.widthProperty().bind(center.widthProperty());
        dmCanvas.heightProperty().bind(center.heightProperty());
        dmFogCanvas.widthProperty().bind(center.widthProperty());
        dmFogCanvas.heightProperty().bind(center.heightProperty());
        root.setCenter(center);

        installDmInteractions();

        Scene scene = new Scene(root, 1400, 900, Color.BLACK);
        // Clicks inside the popup never reach this scene, so any click here is "outside" the menu.
        scene.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> hideLightMenu());
        scene.setOnKeyPressed(event -> {
            if (event.isControlDown() && event.getCode() == KeyCode.S) {
                handleSave(stage);
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
            if (event.getCode() == KeyCode.P) {
                pingArmed = true;
                status("Ping mode: click map to ping players.");
                return;
            }
            if (event.getCode() == KeyCode.ESCAPE) {
                setActiveTool(EditorTool.SELECT);
            }
            if (event.getCode() == KeyCode.DELETE || event.getCode() == KeyCode.BACK_SPACE) {
                deleteSelectedOverlay();
            }
        });
        stage.setTitle("Dungeon Master Map Tool");
        stage.setScene(scene);
        stage.setOnCloseRequest(event -> {
            closePlayerWindow();
            Platform.exit();
        });
        stage.show();

        AnimationTimer timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                lightingEngine.update(project);
                renderDm();
                renderPlayer();
            }
        };
        timer.start();
    }

    private VBox createDmOverlay(Stage stage) {
        Button newProject = new Button("New");
        newProject.setOnAction(e -> {
            DmProject fresh = DmProject.builder().build();
            fresh.getMap().setSourceType("custom");
            switchProject(fresh, null);
            status("Created new empty project.");
        });

        Button importDd2vtt = new Button("Import");
        importDd2vtt.setOnAction(e -> handleImportDd2vtt(stage));

        Button open = new Button("Open");
        open.setOnAction(e -> handleOpenProject(stage));

        Button save = new Button("Save");
        save.setOnAction(e -> handleSave(stage));

        Button exit = new Button("Exit");
        exit.setOnAction(e -> stage.close());
        HBox fileRow = new HBox(6, newProject, importDd2vtt, open, save, exit);

        Button rotateLeft = new Button("Rotate ⟲");
        rotateLeft.setOnAction(e -> {
            executeWithHistory(
                    "Rotate map left",
                    () -> rotationService.rotateCounterClockwise(project),
                    () -> rotationService.rotateClockwise(project)
            );
            status("Rotated map 90° left.");
        });
        Button rotateRight = new Button("Rotate ⟳");
        rotateRight.setOnAction(e -> {
            executeWithHistory(
                    "Rotate map right",
                    () -> rotationService.rotateClockwise(project),
                    () -> rotationService.rotateCounterClockwise(project)
            );
            status("Rotated map 90° right.");
        });
        Button addPing = new Button("Ping");
        addPing.setOnAction(e -> {
            pingArmed = true;
            status("Ping mode: click map to ping players.");
        });
        HBox mapRow = new HBox(6, rotateLeft, rotateRight, addPing);

        Button addLight = new Button("Add Light");
        addLight.setOnAction(e -> addLightAtCamera());
        Button removeLight = new Button("Remove Light");
        removeLight.setOnAction(e -> {
            if (selectedLight != null) {
                removeLight(selectedLight.getId());
            } else {
                removeNearestLight();
            }
        });
        timeOfDaySelector = new ComboBox<>();
        timeOfDaySelector.getItems().addAll(TimeOfDayPreset.values());
        timeOfDaySelector.setConverter(new StringConverter<>() {
            @Override
            public String toString(TimeOfDayPreset preset) {
                return preset == null ? "" : preset.label();
            }

            @Override
            public TimeOfDayPreset fromString(String text) {
                return TimeOfDayPreset.from(text);
            }
        });
        timeOfDaySelector.setOnAction(e -> {
            if (syncingControls || timeOfDaySelector.getValue() == null) {
                return;
            }
            String before = project.getLighting().getTimeOfDayPreset();
            String after = timeOfDaySelector.getValue().name();
            if (after.equalsIgnoreCase(before)) {
                return;
            }
            executeWithHistory(
                    "Change time of day",
                    () -> setTimeOfDay(after),
                    () -> setTimeOfDay(before)
            );
            status("Time of day: " + timeOfDaySelector.getValue().label());
        });
        HBox lightRow = new HBox(6, addLight, removeLight, overlayLabel("Time:"), timeOfDaySelector);
        lightRow.setAlignment(Pos.CENTER_LEFT);

        fogToggleButton = new ToggleButton("Fog");
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
        ToggleGroup toolGroup = new ToggleGroup();
        HBox toolRow = new HBox(6, fogToggleButton);
        HBox effectToolRow = new HBox(6);
        for (EditorTool tool : EditorTool.values()) {
            ToggleButton button = new ToggleButton(tool.label);
            button.setToggleGroup(toolGroup);
            button.setOnAction(e -> setActiveTool(button.isSelected() ? tool : EditorTool.SELECT));
            toolButtons.put(tool, button);
            (tool.isAoeTool() ? effectToolRow : toolRow).getChildren().add(button);
        }

        overlayColorPicker = new ColorPicker(Color.web(overlayColor));
        overlayColorPicker.setPrefWidth(90);
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
        Label alphaLabel = overlayLabel("Opacity:");
        overlayAlphaSlider = new Slider(0.1, 0.9, overlayAlpha);
        overlayAlphaSlider.setPrefWidth(110);
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
        overlayPlayerCheck = new CheckBox("Players see");
        overlayPlayerCheck.setTextFill(Color.WHITE);
        overlayPlayerCheck.setSelected(overlayPlayerVisible);
        overlayPlayerCheck.setOnAction(e -> {
            if (syncingControls) {
                return;
            }
            overlayPlayerVisible = overlayPlayerCheck.isSelected();
            DmProject.OverlayShape selected = findOverlay(selectedOverlayId);
            if (selected != null) {
                executeOverlayChange("Change effect visibility", selected.getId(), s -> s.setPlayerVisible(overlayPlayerVisible));
            }
        });
        Button deleteEffect = new Button("Delete");
        deleteEffect.setOnAction(e -> deleteSelectedOverlay());
        Button clearEffects = new Button("Clear All");
        clearEffects.setOnAction(e -> clearOverlays());
        HBox effectStyleRow = new HBox(6, overlayColorPicker, alphaLabel, overlayAlphaSlider, overlayPlayerCheck);
        effectStyleRow.setAlignment(Pos.CENTER_LEFT);
        effectToolRow.getChildren().addAll(deleteEffect, clearEffects);

        Label brushLabel = overlayLabel(brushLabelText());
        Slider brushSlider = new Slider(0.5, 8, brushSizeTiles);
        brushSlider.setPrefWidth(160);
        brushSlider.valueProperty().addListener((obs, oldValue, newValue) -> {
            brushSizeTiles = Math.round(newValue.doubleValue() * 2) / 2.0;
            brushLabel.setText(brushLabelText());
        });
        Button revealAll = new Button("Reveal All");
        revealAll.setOnAction(e -> fillFog(true));
        Button hideAll = new Button("Hide All");
        hideAll.setOnAction(e -> fillFog(false));
        HBox brushRow = new HBox(6, brushLabel, brushSlider, revealAll, hideAll);
        brushRow.setAlignment(Pos.CENTER_LEFT);

        Button openPlayer = new Button("Player On");
        openPlayer.setOnAction(e -> openPlayerWindow());
        Button closePlayer = new Button("Player Off");
        closePlayer.setOnAction(e -> closePlayerWindow());
        freezePlayerButton = new ToggleButton("Freeze Player");
        freezePlayerButton.setOnAction(e -> {
            setPlayerFrozen(freezePlayerButton.isSelected());
            status(freezePlayerButton.isSelected()
                    ? "Player view frozen. DM viewport can still be moved."
                    : "Player view unfrozen.");
        });
        playerScreenSelector = new ComboBox<>();
        playerScreenSelector.setPrefWidth(320);
        refreshPlayerScreenSelector();
        playerScreenSelector.setOnAction(e -> rememberSelectedPlayerScreenIndex());
        HBox screenRow = new HBox(6, overlayLabel("Screen:"), playerScreenSelector);
        screenRow.setAlignment(Pos.CENTER_LEFT);

        HBox playerRow = new HBox(6, openPlayer, closePlayer, freezePlayerButton);

        VBox body = new VBox(8,
                fileRow,
                mapRow,
                sectionLabel("Lights"), lightRow,
                sectionLabel("Fog of war"), toolRow, brushRow,
                sectionLabel("Effects (spell areas)"), effectToolRow, effectStyleRow,
                sectionLabel("Player view"), screenRow, playerRow);

        Label title = new Label("DM Controls");
        title.setTextFill(Color.WHITE);
        title.setStyle("-fx-font-weight: bold;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button collapse = new Button("–");
        collapse.setOnAction(e -> {
            boolean show = !body.isVisible();
            body.setVisible(show);
            body.setManaged(show);
            collapse.setText(show ? "–" : "+");
        });
        HBox titleRow = new HBox(6, title, spacer, collapse);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        VBox overlay = new VBox(8, titleRow, body);
        overlay.setPadding(new Insets(10));
        overlay.setBackground(new Background(new BackgroundFill(Color.color(0.1, 0.1, 0.12, 0.72), new CornerRadii(8), Insets.EMPTY)));
        overlay.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        overlay.setPickOnBounds(false);
        setActiveTool(EditorTool.SELECT);
        syncControlsFromProject();
        return overlay;
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

        dmCanvas.setOnMouseMoved(event -> updateHover(event.getX(), event.getY()));
        dmCanvas.setOnMouseExited(event -> hoverInsideCanvas = false);

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
                pingArmed = false;
                status("Ping placed.");
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

            if (event.getClickCount() >= 2 && toggleInteractableNear(world.x(), world.y())) {
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

            CanvasMapRenderer.WorldRect playerRect = getPlayerViewportRect();
            if (playerStage != null && playerRect != null && contains(playerRect, world.x(), world.y())) {
                draggingPlayerViewport = true;
                viewportDragOffsetX = world.x() - playerRect.x();
                viewportDragOffsetY = world.y() - playerRect.y();
                startPlayerCameraX = project.getViews().getPlayerCamera().getX();
                startPlayerCameraY = project.getViews().getPlayerCamera().getY();
                return;
            }

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
                selectedLayer.setX(world.x() - dragOffsetX);
                selectedLayer.setY(world.y() - dragOffsetY);
            } else if (resizingLayer) {
                selectedLayer.setWidth(Math.max(16, world.x() - selectedLayer.getX()));
                selectedLayer.setHeight(Math.max(16, world.y() - selectedLayer.getY()));
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
        playerStage.setScene(scene);
        playerStage.setOnCloseRequest(event -> {
            playerStage = null;
            playerCanvas = null;
            playerFogCanvas = null;
        });

        Screen target = resolveSelectedPlayerScreen();
        Rectangle2D bounds = target.getVisualBounds();
        playerStage.setX(bounds.getMinX());
        playerStage.setY(bounds.getMinY());
        playerStage.setWidth(bounds.getWidth());
        playerStage.setHeight(bounds.getHeight());
        playerStage.show();
    }

    private void closePlayerWindow() {
        if (playerStage != null) {
            playerStage.close();
            playerStage = null;
            playerCanvas = null;
            playerFogCanvas = null;
        }
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
                selectedLight == null ? null : selectedLight.getId()
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
        renderer.render(
                gc,
                project,
                projectFile,
                playerCanvas.getWidth(),
                playerCanvas.getHeight(),
                getEffectivePlayerCamera(),
                true,
                null
        );
        renderer.renderFogLayer(
                playerFogCanvas.getGraphicsContext2D(),
                project,
                playerFogCanvas.getWidth(),
                playerFogCanvas.getHeight(),
                getEffectivePlayerCamera(),
                true,
                null,
                null
        );
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

    private void handleImportDd2vtt(Stage stage) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select DD2VTT map");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("DD2VTT", "*.dd2vtt", "*.json"));
        applyInitialImportDirectory(chooser);
        File source = chooser.showOpenDialog(stage);
        if (source == null) {
            return;
        }
        rememberImportDirectory(source.toPath().getParent());

        try {
            String baseName = sanitizeFileStem(stripExtension(source.getName()));
            Path projectsRoot = resolveProjectsRoot();
            Path projectDir = createUniqueDirectory(projectsRoot, baseName);
            Path targetPath = projectDir.resolve(baseName + ".dmmap");

            DmProject imported = dd2vttImportService.importToProject(source.toPath(), projectDir);
            projectService.save(targetPath, imported);
            switchProject(imported, targetPath);
            status("Imported " + source.getName() + " to " + targetPath);
        } catch (IOException ex) {
            status("Import failed: " + ex.getMessage());
        }
    }

    private void handleOpenProject(Stage stage) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open Project");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("DM Map Project", "*.dmmap"));
        applyInitialProjectDirectory(chooser);
        File file = chooser.showOpenDialog(stage);
        if (file == null) {
            return;
        }
        try {
            switchProject(projectService.load(file.toPath()), file.toPath());
            status("Loaded " + file.getName());
        } catch (IOException ex) {
            status("Load failed: " + ex.getMessage());
        }
    }

    private void handleSave(Stage stage) {
        if (projectFile == null) {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Save Project");
            chooser.setInitialFileName("map.dmmap");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("DM Map Project", "*.dmmap"));
            File chosen = chooser.showSaveDialog(stage);
            if (chosen == null) {
                return;
            }
            projectFile = ensureExtension(chosen.toPath(), ".dmmap");
        }
        try {
            projectService.save(projectFile, project);
            status("Saved " + projectFile.getFileName());
        } catch (IOException ex) {
            status("Save failed: " + ex.getMessage());
        }
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

    private DmProject.ImageLayer pickTopmostLayer(double worldX, double worldY) {
        return project.getImageLayers().stream()
                .filter(layer -> contains(layer, worldX, worldY))
                .max(Comparator.comparingInt(DmProject.ImageLayer::getZIndex))
                .orElse(null);
    }

    private boolean toggleInteractableNear(double worldX, double worldY) {
        DmProject.Interactable nearest = null;
        double best = Double.MAX_VALUE;
        for (DmProject.Interactable interactable : project.getInteractables()) {
            double d = pointToSegmentDistance(worldX, worldY, interactable.getX1(), interactable.getY1(), interactable.getX2(), interactable.getY2());
            if (d < best) {
                best = d;
                nearest = interactable;
            }
        }
        if (nearest == null || best > 24 / project.getViews().getDmCamera().getZoom()) {
            return false;
        }
        String previous = nearest.getState();
        String next = "open".equalsIgnoreCase(previous) ? "closed" : "open";
        String interactableId = nearest.getId();
        executeWithFogHistory(
                "Toggle " + nearest.getType(),
                () -> setInteractableState(interactableId, next),
                () -> setInteractableState(interactableId, previous)
        );
        status("Set " + nearest.getType() + " to " + nearest.getState());
        return true;
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

    private void setActiveTool(EditorTool tool) {
        activeTool = tool == null ? EditorTool.SELECT : tool;
        fogDragging = false;
        fogBeforeSnapshot = null;
        draftOverlay = null;
        toolButtons.forEach((key, button) -> button.setSelected(key == activeTool));
        switch (activeTool) {
            case SELECT -> status("Select: drag lights, layers and the player viewport. Right-click a light for options.");
            case REVEAL_BRUSH -> status("Reveal brush: paint to uncover the map.");
            case HIDE_BRUSH -> status("Hide brush: paint to cover the map with fog.");
            case REVEAL_RECT -> status("Reveal rectangle: drag to uncover an area.");
            case HIDE_RECT -> status("Hide rectangle: drag to cover an area with fog.");
            case AOE_CIRCLE -> status("Circle effect: drag from the center outward.");
            case AOE_RECT -> status("Box effect: drag from corner to corner.");
            case AOE_BRUSH -> status("Draw effect: paint a freeform area (brush size sets thickness).");
        }
    }

    private String brushLabelText() {
        return String.format("Brush: %.1f tiles", brushSizeTiles);
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
    }

    private void syncControlsFromProject() {
        syncingControls = true;
        try {
            if (fogToggleButton != null) {
                fogToggleButton.setSelected(project.getFog().isEnabled());
                fogToggleButton.setText(project.getFog().isEnabled() ? "Fog: On" : "Fog: Off");
            }
            if (timeOfDaySelector != null) {
                timeOfDaySelector.setValue(TimeOfDayPreset.from(project.getLighting().getTimeOfDayPreset()));
            }
            if (freezePlayerButton != null) {
                freezePlayerButton.setSelected(project.getViews().isPlayerFrozen());
            }
        } finally {
            syncingControls = false;
        }
    }

    private Label overlayLabel(String text) {
        Label label = new Label(text);
        label.setTextFill(Color.WHITE);
        return label;
    }

    private Label sectionLabel(String text) {
        Label label = overlayLabel(text);
        label.setTextFill(Color.web("#C9C9D6"));
        label.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");
        return label;
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
            overlayPlayerCheck.setSelected(shape.isPlayerVisible());
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

    private static final double[] LIGHT_RANGE_TILES = {1, 2, 3, 4, 6, 8, 12};
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

        CheckMenuItem shadows = new CheckMenuItem("Blocked by walls");
        shadows.setSelected(light.isCastsShadows());
        shadows.setOnAction(e -> updateLight(id, "Toggle light wall blocking", l -> l.setCastsShadows(shadows.isSelected())));

        MenuItem remove = new MenuItem("Remove light");
        remove.setOnAction(e -> removeLight(id));

        menu.getItems().addAll(power, new SeparatorMenuItem(), revealMenu, rangeMenu, flickerMenu, colorMenu, shadows, new SeparatorMenuItem(), remove);
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

    private boolean contains(CanvasMapRenderer.WorldRect rect, double x, double y) {
        return x >= rect.x() && x <= rect.x() + rect.width()
                && y >= rect.y() && y <= rect.y() + rect.height();
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

    private Path ensureExtension(Path file, String extension) {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(extension)) {
            return file;
        }
        return file.resolveSibling(file.getFileName() + extension);
    }

    private String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
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

    private void applyInitialProjectDirectory(FileChooser chooser) {
        try {
            Path projectsRoot = resolveProjectsRoot();
            Files.createDirectories(projectsRoot);
            File dir = projectsRoot.toFile();
            if (dir.exists() && dir.isDirectory()) {
                chooser.setInitialDirectory(dir);
            }
        } catch (IOException ignored) {
        }
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

    private Path createUniqueDirectory(Path parent, String folderName) throws IOException {
        Files.createDirectories(parent);
        Path candidate = parent.resolve(folderName);
        int suffix = 2;
        while (Files.exists(candidate)) {
            candidate = parent.resolve(folderName + "-" + suffix++);
        }
        Files.createDirectories(candidate);
        return candidate;
    }

    private String sanitizeFileStem(String name) {
        String sanitized = name.replaceAll("[^a-zA-Z0-9-_ ]", "").trim().replace(' ', '-');
        return sanitized.isEmpty() ? "imported-map" : sanitized;
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

    private void setPlayerFrozen(boolean frozen) {
        project.getViews().setPlayerFrozen(frozen);
        if (frozen) {
            frozenPlayerCamera = copyCamera(project.getViews().getPlayerCamera());
        } else {
            frozenPlayerCamera = null;
        }
    }

    private void addLightAtCamera() {
        DmProject.CameraState camera = project.getViews().getDmCamera();
        DmProject.LightSource light = DmProject.LightSource.builder()
                .id("light-" + UUID.randomUUID())
                .x(camera.getX())
                .y(camera.getY())
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
        status("Added torch light. Drag it to move; right-click for range, flicker, color and fog reveal.");
    }

    private void removeNearestLight() {
        DmProject.CameraState camera = project.getViews().getDmCamera();
        DmProject.LightSource nearest = pickNearestLight(camera.getX(), camera.getY(), Double.MAX_VALUE);
        if (nearest == null) {
            status("No light to remove.");
            return;
        }
        removeLight(nearest.getId());
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
        if (!project.getViews().isPlayerFrozen()) {
            return project.getViews().getPlayerCamera();
        }
        if (frozenPlayerCamera == null) {
            frozenPlayerCamera = copyCamera(project.getViews().getPlayerCamera());
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
        SELECT("Select", false, false),
        REVEAL_BRUSH("Reveal", true, false),
        HIDE_BRUSH("Hide", false, false),
        REVEAL_RECT("Reveal Area", true, true),
        HIDE_RECT("Hide Area", false, true),
        AOE_CIRCLE("Circle", false, false),
        AOE_RECT("Box", false, true),
        AOE_BRUSH("Draw", false, false);

        private final String label;
        private final boolean reveal;
        private final boolean rect;

        EditorTool(String label, boolean reveal, boolean rect) {
            this.label = label;
            this.reveal = reveal;
            this.rect = rect;
        }

        boolean isAoeTool() {
            return this == AOE_CIRCLE || this == AOE_RECT || this == AOE_BRUSH;
        }

        boolean isFogTool() {
            return this != SELECT && !isAoeTool();
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
