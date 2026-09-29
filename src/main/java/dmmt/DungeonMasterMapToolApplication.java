package dmmt;

import dmmt.model.DmProject;
import dmmt.render.CanvasMapRenderer;
import dmmt.service.Dd2vttImportService;
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
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
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
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.prefs.Preferences;

public class DungeonMasterMapToolApplication extends Application {
    private static final String PREF_LAST_IMPORT_DIRECTORY = "lastImportDirectory";
    private static final String PREF_PLAYER_SCREEN_INDEX = "playerScreenIndex";

    private final ProjectService projectService = new ProjectService();
    private final Dd2vttImportService dd2vttImportService = new Dd2vttImportService();
    private final MapRotationService rotationService = new MapRotationService();
    private final CanvasMapRenderer renderer = new CanvasMapRenderer();
    private final Preferences preferences = Preferences.userNodeForPackage(DungeonMasterMapToolApplication.class);

    private DmProject project;
    private Path projectFile;

    private Canvas dmCanvas;
    private Canvas playerCanvas;
    private Stage playerStage;
    private Label statusLabel;
    private ComboBox<String> playerScreenSelector;
    private DmProject.CameraState frozenPlayerCamera;

    private DmProject.ImageLayer selectedLayer;
    private boolean draggingLayer;
    private boolean resizingLayer;
    private boolean panningDmCamera;
    private boolean draggingPlayerViewport;
    private boolean pingArmed;
    private double dragOffsetX;
    private double dragOffsetY;
    private double lastMouseX;
    private double lastMouseY;
    private double viewportDragOffsetX;
    private double viewportDragOffsetY;

    @Override
    public void start(Stage stage) {
        this.project = DmProject.builder().build();

        BorderPane root = new BorderPane();
        statusLabel = new Label("Ready");
        root.setBottom(statusLabel);

        dmCanvas = new Canvas(1280, 800);
        StackPane center = new StackPane(dmCanvas);
        VBox overlay = createDmOverlay(stage);
        StackPane.setAlignment(overlay, Pos.TOP_LEFT);
        StackPane.setMargin(overlay, new Insets(10));
        center.getChildren().add(overlay);
        dmCanvas.widthProperty().bind(center.widthProperty());
        dmCanvas.heightProperty().bind(center.heightProperty());
        root.setCenter(center);

        installDmInteractions();

        Scene scene = new Scene(root, 1400, 900, Color.BLACK);
        scene.setOnKeyPressed(event -> {
            if (event.isControlDown() && event.getCode() == KeyCode.S) {
                handleSave(stage);
                event.consume();
                return;
            }
            if (event.getCode() == KeyCode.P) {
                pingArmed = true;
                status("Ping mode: click map to ping players.");
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
                renderDm();
                renderPlayer();
            }
        };
        timer.start();
    }

    private VBox createDmOverlay(Stage stage) {
        Button newProject = new Button("New");
        newProject.setOnAction(e -> {
            project = DmProject.builder().build();
            project.getMap().setSourceType("custom");
            projectFile = null;
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
            rotationService.rotateCounterClockwise(project);
            status("Rotated map 90° left.");
        });
        Button rotateRight = new Button("Rotate ⟳");
        rotateRight.setOnAction(e -> {
            rotationService.rotateClockwise(project);
            status("Rotated map 90° right.");
        });
        Button addPing = new Button("Ping");
        addPing.setOnAction(e -> {
            pingArmed = true;
            status("Ping mode: click map to ping players.");
        });
        HBox editRow = new HBox(6, rotateLeft, rotateRight, addPing);

        Button openPlayer = new Button("Player On");
        openPlayer.setOnAction(e -> openPlayerWindow());
        Button closePlayer = new Button("Player Off");
        closePlayer.setOnAction(e -> closePlayerWindow());
        ToggleButton freezePlayer = new ToggleButton("Freeze Player");
        freezePlayer.setOnAction(e -> {
            setPlayerFrozen(freezePlayer.isSelected());
            status(freezePlayer.isSelected()
                    ? "Player view frozen. DM viewport can still be moved."
                    : "Player view unfrozen.");
        });
        Label screenLabel = new Label("Screen:");
        screenLabel.setTextFill(Color.WHITE);
        playerScreenSelector = new ComboBox<>();
        playerScreenSelector.setPrefWidth(320);
        refreshPlayerScreenSelector();
        playerScreenSelector.setOnAction(e -> rememberSelectedPlayerScreenIndex());
        HBox screenRow = new HBox(6, screenLabel, playerScreenSelector);

        HBox playerRow = new HBox(6, openPlayer, closePlayer, freezePlayer);

        Label title = new Label("DM Controls");
        title.setTextFill(Color.WHITE);
        VBox overlay = new VBox(8, title, fileRow, editRow, screenRow, playerRow);
        overlay.setPadding(new Insets(10));
        overlay.setBackground(new Background(new BackgroundFill(Color.color(0.1, 0.1, 0.12, 0.72), new CornerRadii(8), Insets.EMPTY)));
        overlay.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        overlay.setPickOnBounds(false);
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

        dmCanvas.setOnMousePressed(event -> {
            lastMouseX = event.getX();
            lastMouseY = event.getY();
            DmProject.CameraState camera = project.getViews().getDmCamera();
            CanvasMapRenderer.WorldPoint world = renderer.screenToWorld(
                    event.getX(), event.getY(), dmCanvas.getWidth(), dmCanvas.getHeight(), camera);

            if (event.getButton() == MouseButton.SECONDARY) {
                panningDmCamera = true;
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

            if (event.getClickCount() >= 2 && toggleInteractableNear(world.x(), world.y())) {
                return;
            }

            CanvasMapRenderer.WorldRect playerRect = getPlayerViewportRect();
            if (playerStage != null && playerRect != null && contains(playerRect, world.x(), world.y())) {
                draggingPlayerViewport = true;
                viewportDragOffsetX = world.x() - playerRect.x();
                viewportDragOffsetY = world.y() - playerRect.y();
                return;
            }

            selectedLayer = pickTopmostLayer(world.x(), world.y());
            if (selectedLayer == null) {
                return;
            }

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

            if (draggingPlayerViewport && playerStage != null) {
                DmProject.CameraState playerCam = project.getViews().getPlayerCamera();
                CanvasMapRenderer.WorldRect rect = getPlayerViewportRect();
                if (rect != null) {
                    playerCam.setX(world.x() - viewportDragOffsetX + rect.width() / 2.0);
                    playerCam.setY(world.y() - viewportDragOffsetY + rect.height() / 2.0);
                }
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
            draggingLayer = false;
            resizingLayer = false;
            panningDmCamera = false;
            draggingPlayerViewport = false;
        });
    }

    private void openPlayerWindow() {
        refreshPlayerScreenSelector();
        if (playerStage != null) {
            playerStage.toFront();
            return;
        }
        playerCanvas = new Canvas(1280, 720);
        StackPane root = new StackPane(playerCanvas);
        playerCanvas.widthProperty().bind(root.widthProperty());
        playerCanvas.heightProperty().bind(root.heightProperty());
        Scene scene = new Scene(root, 1280, 720, Color.BLACK);

        playerStage = new Stage(StageStyle.UNDECORATED);
        playerStage.setTitle("Player View");
        playerStage.setScene(scene);
        playerStage.setOnCloseRequest(event -> {
            playerStage = null;
            playerCanvas = null;
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
        drawSelectionHandle(gc);
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

            project = dd2vttImportService.importToProject(source.toPath(), projectDir);
            projectFile = targetPath;
            projectService.save(projectFile, project);
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
            project = projectService.load(file.toPath());
            projectFile = file.toPath();
            selectedLayer = null;
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
        nearest.setState("open".equalsIgnoreCase(nearest.getState()) ? "closed" : "open");
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
}
