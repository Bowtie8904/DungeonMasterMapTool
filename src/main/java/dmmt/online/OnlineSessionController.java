package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private static final ObjectMapper MAPPER = new ObjectMapper();
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
        void applyRotation(String tokenId, double rotationDeg);

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
    private final java.util.List<java.util.function.Consumer<JsonNode>> chatListeners = new java.util.ArrayList<>();
    private final java.util.List<java.util.function.Consumer<String>> stockListeners = new java.util.ArrayList<>();
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

    private java.util.function.Supplier<Path> compendiumDirectory = () -> null;

    public void setCompendiumDirectory(java.util.function.Supplier<Path> supplier) {
        this.compendiumDirectory = supplier;
    }

    @Override
    public Path compendiumDirectory() {
        return compendiumDirectory.get();
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

    /** Saves a regular map token's movement grant without changing its reusable definition. */
    public void setTokenControl(Campaign edited, String mapId, String tokenId, String playerId,
                                boolean enabled) throws IOException {
        if (edited == null || edited.player(playerId) == null)
            throw new IllegalArgumentException("Select an existing campaign player.");
        if (running() && edited != campaign)
            throw new IllegalArgumentException("The hosted campaign changed; reopen the token menu.");
        DmProject project = app.project();
        DmProject.TokenInstance token = project == null ? null : project.getTokens().stream()
                .filter(t -> t.getId().equals(tokenId)).findFirst().orElse(null);
        if (token == null || !app.mapId().equals(mapId) || token.getActorId() != null)
            throw new IllegalArgumentException("The map or token changed; reopen the token menu.");
        var previous = edited.getControlledTokens();
        var next = new java.util.ArrayList<>(previous);
        next.removeIf(link -> mapId.equals(link.getMapId()) && tokenId.equals(link.getTokenId())
                && playerId.equals(link.getPlayerId()));
        if (enabled)
            next.add(new Campaign.TokenLink(playerId, mapId, tokenId));
        edited.setControlledTokens(next);
        try {
            store.save(edited);
        } catch (IOException ex) {
            edited.setControlledTokens(previous);
            throw ex;
        }
        if (running()) publishNow();
        app.changed();
    }

    /** Fear tokens the GM holds in the hosted campaign; 0 when no session runs. */
    public int fear() {
        return running() && campaign != null ? campaign.getFear() : 0;
    }

    /** Changes the hosted campaign's Fear by {@code delta} (clamped), saves it and shows it to the players. */
    public void adjustFear(int delta) {
        if (!running() || campaign == null) {
            return;
        }
        int before = campaign.getFear();
        campaign.setFear(before + delta);
        if (campaign.getFear() == before) {
            return;
        }
        try {
            store.save(campaign);
        } catch (IOException ex) {
            app.status("Online Play: could not save the Fear count: " + ex.getMessage());
        }
        publishNow();
        app.changed();
    }

    public boolean audioStreaming() {
        return audioStreaming;
    }

    public void addCountdown(String name, int initialValue) throws IOException {
        requireCountdownHost();
        var countdown = new Campaign.Countdown(java.util.UUID.randomUUID().toString(), name, initialValue, initialValue);
        var next = new java.util.ArrayList<>(campaign.getCountdowns());
        next.add(countdown);
        saveCountdowns(next);
    }

    public void stepCountdown(String id, int delta) throws IOException {
        requireCountdownHost();
        var next = new java.util.ArrayList<>(campaign.getCountdowns());
        int index = countdownIndex(id);
        var stepped = next.get(index).step(delta);
        if (stepped.equals(next.get(index))) return;
        next.set(index, stepped);
        saveCountdowns(next);
    }

    public void deleteCountdown(String id) throws IOException {
        requireCountdownHost();
        var next = new java.util.ArrayList<>(campaign.getCountdowns());
        next.remove(countdownIndex(id));
        saveCountdowns(next);
    }

    private void requireCountdownHost() {
        if (!running() || campaign == null) throw new IllegalStateException("No online campaign is being hosted.");
    }

    private int countdownIndex(String id) {
        for (int i = 0; i < campaign.getCountdowns().size(); i++) {
            if (campaign.getCountdowns().get(i).id().equals(id)) return i;
        }
        throw new IllegalArgumentException("Countdown no longer exists.");
    }

    private void saveCountdowns(java.util.List<Campaign.Countdown> next) throws IOException {
        var previous = campaign.getCountdowns();
        campaign.setCountdowns(next);
        try {
            store.save(campaign);
        } catch (IOException ex) {
            campaign.setCountdowns(previous);
            throw ex;
        }
        publishNow();
        app.changed();
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
    public boolean supportsTokenRotation() {
        return true;
    }

    @Override
    public CompletableFuture<OnlineSessionServer.MoveResult> rotateToken(String playerId, String tokenId,
                                                                         String mapId, String levelId, double rotationDeg) {
        CompletableFuture<OnlineSessionServer.MoveResult> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            if (result.isDone()) return;
            try {
                if (!running() || paused() || !app.mapId().equals(mapId) || !app.levelId().equals(levelId)) {
                    result.complete(OnlineSessionServer.MoveResult.rejected("Map changed or game paused"));
                    return;
                }
                DmProject project = app.project();
                DmProject.TokenInstance token = project == null ? null : project.getTokens().stream()
                        .filter(t -> tokenId.equals(t.getId())).findFirst().orElse(null);
                String problem = !Double.isFinite(rotationDeg) ? "Invalid rotation"
                        : MoveValidator.checkControl(campaign, mapId, playerId, token);
                if (problem != null) {
                    result.complete(OnlineSessionServer.MoveResult.rejected(problem));
                    return;
                }
                app.applyRotation(tokenId, (rotationDeg % 360 + 360) % 360);
                publishNow();
                result.complete(OnlineSessionServer.MoveResult.accepted());
            } catch (RuntimeException ex) {
                app.status("Online rotation failed: " + ex.getMessage());
                result.complete(OnlineSessionServer.MoveResult.rejected("Rotation failed"));
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
                String problem = project == null ? "No map open" : movementBlockReason(token);
                if (problem == null)
                    problem = MoveValidator.check(project, campaign, app.mapId(), playerId, token, x, y, phaseMovement(token));
                if (problem != null) {
                    result.complete(token == null ? OnlineSessionServer.MoveResult.rejected(problem)
                            : OnlineSessionServer.MoveResult.rejected(problem, token.getX(), token.getY()));
                    return;
                }
                app.applyMove(tokenId, x, y);
                result.complete(OnlineSessionServer.MoveResult.accepted());
            } catch (IOException ex) {
                result.complete(OnlineSessionServer.MoveResult.rejected("Could not verify this character's movement effects."));
            } catch (RuntimeException ex) {
                result.complete(OnlineSessionServer.MoveResult.rejected("Move failed"));
            }
        });
        return result;
    }

    private String movementBlockReason(DmProject.TokenInstance token) {
        if (token == null || campaign == null) return null;
        Campaign.Actor actor = OnlineStateBuilder.actorOf(token, campaign);
        if (actor == null) return null;
        try {
            String saved = store.loadSheet(campaign.getId(), actor.getId());
            if (saved == null) return null;
            JsonNode effects = MAPPER.readTree(saved).path("effects");
            if (effects.isArray()) {
                for (JsonNode effect : effects)
                    if (effect.path("blocksMovement").asBoolean())
                        return "This character cannot move while a movement-blocking effect is active.";
            }
        } catch (IOException ignored) {
            return "Could not verify this character's movement effects.";
        }
        return null;
    }

    private boolean phaseMovement(DmProject.TokenInstance token) throws IOException {
        if (token == null || campaign == null) return false;
        Campaign.Actor actor = OnlineStateBuilder.actorOf(token, campaign);
        if (actor == null) return false;
        String saved = store.loadSheet(campaign.getId(), actor.getId());
        if (saved == null) return false;
        ObjectNode sheet = (ObjectNode) MAPPER.readTree(saved);
        DeathMoves.sync(sheet);
        for (JsonNode effect : sheet.path("effects"))
            if (effect.has("spectralHp") && effect.path("phaseMovement").asBoolean()) return true;
        return false;
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
            if ("chatDeleted".equals(message.path("type").asText())) {
                chatTail.removeIf(m -> m.path("id").asLong() == message.path("id").asLong()
                        && m.path("ts").asLong() == message.path("ts").asLong());
            } else if ("chatUpdate".equals(message.path("type").asText())) {
                JsonNode updated = message.path("message");
                var entries = new java.util.ArrayList<>(chatTail);
                for (int i = 0; i < entries.size(); i++) {
                    if (entries.get(i).path("id").equals(updated.path("id")) && entries.get(i).path("ts").equals(updated.path("ts")))
                        entries.set(i, updated.deepCopy());
                }
                chatTail.clear();
                chatTail.addAll(entries);
            } else if ("rollUpdate".equals(message.path("type").asText())) {
                for (JsonNode entry : chatTail) {
                    if (!(entry instanceof ObjectNode roll) || !"roll".equals(entry.path("type").asText())
                            || entry.path("id").asLong() != message.path("id").asLong()
                            || entry.path("ts").asLong() != message.path("ts").asLong()) continue;
                    if (message.has("outcome")) roll.set("outcome", message.get("outcome"));
                    else roll.remove("outcome");
                    if (message.has("featureUses")) roll.set("featureUses", message.get("featureUses"));
                    if (message.has("rewardClaimed")) roll.set("rewardClaimed", message.get("rewardClaimed"));
                    for (String field : java.util.List.of("total", "expression", "rolls", "breakdown"))
                        if (message.has(field)) roll.set(field, message.get(field));
                    break;
                }
            } else {
                chatTail.addLast(OnlineSessionServer.playerView(message));
            }
            while (chatTail.size() > 50) {
                chatTail.removeFirst();
            }
            for (java.util.function.Consumer<JsonNode> listener : java.util.List.copyOf(chatListeners)) {
                listener.accept(message);
            }
            app.changed();
        });
    }

    /** Chat/roll and deletion events for the DM, on the JavaFX thread. Secret rolls keep their real values. */
    public void addChatListener(java.util.function.Consumer<JsonNode> listener) {
        chatListeners.add(listener);
    }

    @Override
    public void cardStockChanged(String actorId) {
        Platform.runLater(() -> {
            for (java.util.function.Consumer<String> listener : java.util.List.copyOf(stockListeners)) {
                listener.accept(actorId);
            }
        });
    }

    /** Listener for changed transfer-offer stock of an actor; called on the JavaFX thread. */
    public void addCardStockListener(java.util.function.Consumer<String> listener) {
        stockListeners.add(listener);
    }

    /** Units of a shared transfer offer still available; 0 when no session runs. */
    public int cardRemaining(JsonNode sharedCard) {
        OnlineSessionServer current = server;
        return current == null ? 0 : current.cardRemaining(sharedCard);
    }

    /** Chat the DM may see (public and DM whispers) from the campaign history; empty when no session runs. */
    public java.util.List<JsonNode> chatHistory(int limit) {
        OnlineSessionServer current = server;
        return current == null ? java.util.List.of() : current.dmHistory(limit);
    }

    /** Sends a DM message, public or whispered to {@code toPlayerId}. */
    public void sendChat(String text, String toPlayerId) {
        OnlineSessionServer current = server;
        if (current != null) {
            current.dmChat(text, toPlayerId);
        }
    }

    /** Rolls dice as the DM; returns an error message or {@code null}. */
    public String rollDice(String expression, String toPlayerId, boolean secret) {
        OnlineSessionServer current = server;
        return current == null ? "No online session is running." : current.dmRoll(expression, toPlayerId, secret);
    }

    public String deleteChatMessage(long id, long timestamp) {
        OnlineSessionServer current = server;
        return current == null ? "No online session is running." : current.dmDeleteMessage(id, timestamp);
    }

    public java.util.List<OnlineSessionServer.RosterEntry> roster() {
        OnlineSessionServer current = server;
        return current == null ? java.util.List.of() : current.roster();
    }

    public java.util.List<JsonNode> recentMessages() {
        return java.util.List.copyOf(chatTail);
    }
}
