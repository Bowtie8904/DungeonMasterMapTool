package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dmmt.model.DmProject;
import dmmt.service.Tuning;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.util.Duration;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;

/** Application-side owner of the optional Online Play session: starts the host and publishes the map state. */
public final class OnlineSessionController implements OnlineSessionServer.Host {
    /** What the controller needs from the running application; all calls happen on the JavaFX thread. */
    public interface App {
        DmProject project();

        Path projectFile();

        /** Stable id of the open map (manifest id for multilevel maps, otherwise the project id). */
        String mapId();

        /** Stable id of the open level (the project id for single-level maps). */
        String levelId();

        String mapName();

        /** Whether the DM's player-grid toggle is on; browsers only draw the grid when it is. */
        boolean showGrid();

        /** Applies an already validated move with undo history. */
        void applyMove(String tokenId, double x, double y);

        void applyOverlay(String id, DmProject.OverlayShape shape);

        void measurementsChanged();

        void status(String text);

        /** The DM's audio engine, or {@code null} when audio is off or mirrors another machine. */
        dmmt.audio.AudioEngine audioEngine();

        dmmt.audio.AudioLibraryService audioLibrary();

        /** Something about the session changed (roster, running state, messages). */
        void changed();
    }

    private final Path root;
    private final CampaignStore store;
    private final App app;
    private final OnlineStateBuilder builder = new OnlineStateBuilder();
    private final Deque<JsonNode> chatTail = new ArrayDeque<>();
    private OnlineSessionServer server;
    private AssetRegistry assets;
    private Campaign campaign;
    private Timeline timer;
    private int weatherSeed;
    private OnlineAudioSync audioSync;
    private boolean audioStreaming = Tuning.ONLINE_AUDIO_STREAMING.get();
    private Boolean audioPublishedEnabled;

    public OnlineSessionController(Path root, App app) {
        this.root = root;
        this.store = new CampaignStore(root);
        this.app = app;
    }

    public CampaignStore store() {
        return store;
    }

    public Campaign campaign() {
        return campaign;
    }

    public boolean running() {
        return server != null && server.isRunning();
    }

    public boolean paused() {
        return server != null && server.isPaused();
    }

    public OnlineSessionServer server() {
        return server;
    }

    public String mapKey() {
        return app.mapId() + "/" + app.levelId();
    }

    public synchronized void start(Campaign selected) throws IOException {
        if (running()) {
            return;
        }
        campaign = selected;
        audioPublishedEnabled = null;
        audioSync = null;
        weatherSeed = new SecureRandom().nextInt();
        assets = new AssetRegistry(root.resolve(".cache"), Tuning.ONLINE_MAX_IMAGE_EDGE.get(), store);
        OnlineSessionServer.Config config = new OnlineSessionServer.Config(Tuning.ONLINE_PORT.get(),
                Tuning.ONLINE_SESSION_NAME.get(), Tuning.ONLINE_MAX_PLAYERS.get(),
                Tuning.ONLINE_MAX_MESSAGE_BYTES.get(), Tuning.ONLINE_CHANGE_LOG_SIZE.get(),
                Tuning.ONLINE_MAX_DICE.get(), Tuning.ONLINE_LOGIN_MAX_FAILURES.get(),
                Tuning.ONLINE_LOGIN_LOCKOUT_SECONDS.get());
        server = new OnlineSessionServer(config, campaign, store, assets, this);
        server.start();
        publishNow();
        timer = new Timeline(new KeyFrame(Duration.millis(250), e -> {
            publishNow();
            publishAudio();
        }));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();
        app.status("Online session started for campaign '" + campaign.getName() + "' on port " + server.port() + ".");
        app.changed();
    }

    public synchronized void stop() {
        if (timer != null) {
            timer.stop();
            timer = null;
        }
        if (server != null) {
            server.stop();
            server = null;
        }
        app.changed();
    }

    public void setPaused(boolean paused) {
        if (server != null) {
            server.setPaused(paused);
            publishNow();
            app.changed();
        }
    }

    /** Persists the campaign after the DM edited it and refreshes the running session. */
    public void campaignEdited(Campaign edited) throws IOException {
        store.save(edited);
        if (server != null && edited == campaign) {
            server.setCampaign(edited);
            publishNow();
        }
    }

    public boolean audioStreaming() {
        return audioStreaming;
    }

    public void setAudioStreaming(boolean enabled) {
        audioStreaming = enabled;
        publishAudio();
        app.changed();
    }

    private void publishAudio() {
        OnlineSessionServer current = server;
        if (current == null) {
            return;
        }
        dmmt.audio.AudioEngine engine = app.audioEngine();
        dmmt.audio.AudioLibraryService library = app.audioLibrary();
        boolean enabled = audioStreaming && engine != null && library != null && assets != null;
        try {
            if (!enabled) {
                if (!Boolean.FALSE.equals(audioPublishedEnabled)) {
                    audioPublishedEnabled = false;
                    audioSync = null;
                    current.publishAudio(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                            .put("enabled", false));
                }
                return;
            }
            if (audioSync == null || !Boolean.TRUE.equals(audioPublishedEnabled)) {
                audioSync = new OnlineAudioSync(engine, library, assets);
                audioPublishedEnabled = true;
            }
            ObjectNode body = audioSync.changeOrResync(System.currentTimeMillis(), 5000);
            if (body != null) {
                body.put("enabled", true);
                current.publishAudio(body);
            }
        } catch (RuntimeException ex) {
            // Audio is optional; a broken library entry must not disturb the map session.
        }
    }

    public String inviteLink() {
        if (!running()) {
            return "";
        }
        return "http://" + localAddress() + ":" + server.port() + "/";
    }

    private static String localAddress() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (IOException ex) {
            return "localhost";
        }
    }

    private void publishNow() {
        OnlineSessionServer current = server;
        DmProject project = app.project();
        if (current == null || project == null || campaign == null) {
            return;
        }
        try {
            OnlineStateBuilder.Context ctx = new OnlineStateBuilder.Context(app.mapId(), app.levelId(),
                    app.mapName(), app.projectFile(), weatherSeed, current.isPaused(), app.showGrid());
            ObjectNode state = builder.build(project, ctx, campaign, assets, java.util.List.copyOf(chatTail));
            current.publish(state, app.mapId() + "/" + app.levelId());
        } catch (RuntimeException ex) {
            app.status("Online Play: could not publish the map state: " + ex.getMessage());
        }
    }

    @Override
    public boolean supportsDrawing() {
        return true;
    }

    @Override
    public CompletableFuture<OnlineSessionServer.MoveResult> editDrawing(String playerId, JsonNode command) {
        CompletableFuture<OnlineSessionServer.MoveResult> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            if (result.isDone()) return;
            try {
                if (!running() || paused() || !app.mapId().equals(command.path("mapId").asText())
                        || !app.levelId().equals(command.path("levelId").asText())) {
                    throw new IllegalArgumentException("Map changed or game paused");
                }
                String id = command.path("id").asText("");
                DmProject project = app.project();
                DmProject.OverlayShape shape = OnlineDrawingEdits.prepare(project, campaign.getId(), playerId, command);
                app.applyOverlay(id, shape);
                publishNow();
                result.complete(OnlineSessionServer.MoveResult.accepted());
            } catch (IllegalArgumentException ex) {
                result.complete(OnlineSessionServer.MoveResult.rejected(ex.getMessage()));
            } catch (RuntimeException ex) {
                app.status("Online drawing failed: " + ex.getMessage());
                result.complete(OnlineSessionServer.MoveResult.rejected("Drawing failed"));
            }
        });
        return result;
    }

    @Override
    public CompletableFuture<OnlineSessionServer.MoveResult> moveToken(String playerId, String tokenId, double x, double y) {
        CompletableFuture<OnlineSessionServer.MoveResult> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                DmProject project = app.project();
                DmProject.TokenInstance token = project == null ? null : project.getTokens().stream()
                        .filter(t -> tokenId.equals(t.getId())).findFirst().orElse(null);
                String problem = project == null ? "No map open"
                        : MoveValidator.check(project, campaign, app.mapId(), playerId, token, x, y);
                if (problem != null) {
                    result.complete(token == null ? OnlineSessionServer.MoveResult.rejected(problem)
                            : OnlineSessionServer.MoveResult.rejected(problem, token.getX(), token.getY()));
                    return;
                }
                app.applyMove(tokenId, x, y);
                result.complete(OnlineSessionServer.MoveResult.accepted());
            } catch (RuntimeException ex) {
                result.complete(OnlineSessionServer.MoveResult.rejected("Move failed"));
            }
        });
        return result;
    }

    @Override
    public void rosterChanged() {
        Platform.runLater(app::changed);
    }

    @Override
    public void measurementsChanged() {
        Platform.runLater(app::measurementsChanged);
    }

    @Override
    public void campaignChanged(Campaign changed) {
        Platform.runLater(app::changed);
    }

    @Override
    public void dmMessage(JsonNode message) {
        Platform.runLater(() -> {
            chatTail.addLast(message);
            while (chatTail.size() > 50) {
                chatTail.removeFirst();
            }
            app.changed();
        });
    }

    public java.util.List<JsonNode> recentMessages() {
        return java.util.List.copyOf(chatTail);
    }
}
