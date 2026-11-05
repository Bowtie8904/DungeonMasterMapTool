package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;
import io.javalin.websocket.WsContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.BitSet;
import java.util.Deque;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * The Online Play host: HTTP login, asset and sheet endpoints plus the WebSocket game channel (protocol 3.41.4).
 * It never touches JavaFX; the application feeds it states with {@link #publish} and answers moves through
 * {@link Host}.
 */
public final class OnlineSessionServer {
    public record Config(int port, String sessionName, int maxPlayers, int maxMessageBytes, int changeLogSize,
                         int maxDice, int loginMaxFailures, int loginLockoutSeconds) {
    }

    /** {@code x}/{@code y} are the token's authoritative position on rejection (NaN when unknown). */
    public record MoveResult(boolean ok, String reason, double x, double y) {
        public static MoveResult accepted() {
            return new MoveResult(true, null, Double.NaN, Double.NaN);
        }

        public static MoveResult rejected(String reason) {
            return new MoveResult(false, reason, Double.NaN, Double.NaN);
        }

        public static MoveResult rejected(String reason, double x, double y) {
            return new MoveResult(false, reason, x, y);
        }
    }

    /** Callbacks into the application; may be invoked from server threads. */
    public interface Host {
        default boolean supportsDrawing() { return false; }

        default void measurementsChanged() { }

        /** Folder with the Daggerheart compendium JSON files (docs/DAGGERHEART_DATA.md); null disables it. */
        default java.nio.file.Path compendiumDirectory() { return null; }

        default CompletableFuture<MoveResult> editDrawing(String playerId, JsonNode command) {
            return CompletableFuture.completedFuture(MoveResult.rejected("Drawing is unavailable on this host"));
        }
        /** Validates and applies a move on the application thread. */
        CompletableFuture<MoveResult> moveToken(String playerId, String tokenId, double x, double y);

        void rosterChanged();

        void campaignChanged(Campaign campaign);

        /** A chat, roll or deletion event that reached the DM (public or whispered to/from the DM). */
        void dmMessage(JsonNode message);

        /** The stock of transfer offers made by this actor may have changed; may run on a server thread. */
        default void cardStockChanged(String actorId) { }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> DIFF_CATEGORIES = List.of("images", "walls", "doors", "lights", "lighting",
            "weather", "dmFog", "fogGrid", "actors", "grid", "overlays", "texts", "view", "pings", "indoor",
            "effectTextures", "penWidthCells", "fear", "countdowns");
    private static final int MAX_FOG_BYTES = 4 * 1024 * 1024;
    private static final int MAX_CHAT_CHARS = 1000;

    private final Config config;
    private final CampaignStore store;
    private final CampaignNotes notes;
    private final Object handoutsLock = new Object();
    private CampaignHandouts handouts;
    private final AssetRegistry assets;
    private final Host host;
    private final LoginRateLimiter limiter;
    private final SecureRandom secureRandom = new SecureRandom();
    private final String dmCompendiumToken = java.util.UUID.randomUUID() + "-" + java.util.UUID.randomUUID();
    private String sessionId = java.util.UUID.randomUUID().toString();
    private final Random diceRandom = new Random();
    private final Map<String, String> sessions = new ConcurrentHashMap<>();
    private final Map<String, Client> clients = new ConcurrentHashMap<>();
    private final Map<String, LoginClaim> loginClaims = new java.util.HashMap<>();
    private final Set<String> sessionRolls = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private record LoginClaim(String token, long expiresAt) {
    }

    static final long LOGIN_RESERVATION_MILLIS = 30_000;
    private final Deque<long[]> logSeqs = new ArrayDeque<>();
    private final Deque<String> logJson = new ArrayDeque<>();

    private volatile Campaign campaign;
    private volatile boolean paused;
    private volatile String audioJson;
    private Javalin app;
    private ObjectNode current;
    private String currentMapKey;
    private long seq;
    private long chatId;
    private final Map<String, JsonNode> sharedCards = new java.util.LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, JsonNode> eldest) {
            return size() > 512;
        }
    };
    private final Map<String, OnlineMeasurement> measurements = new java.util.LinkedHashMap<>();

    private static final class Client {
        final WsContext ctx;
        final String playerId;
        final String playerName;
        volatile long pingId;
        volatile long pingSentNanos;
        /** Last measured round trip in milliseconds; -1 until the first pong. */
        volatile long rttMs = -1;

        Client(WsContext ctx, String playerId, String playerName) {
            this.ctx = ctx;
            this.playerId = playerId;
            this.playerName = playerName;
        }
    }

    static final long PING_INTERVAL_SECONDS = 3;
    private java.util.concurrent.ScheduledExecutorService pinger;
    private long pingCounter;

    public OnlineSessionServer(Config config, Campaign campaign, CampaignStore store, AssetRegistry assets, Host host) {
        this.config = config;
        this.campaign = campaign;
        this.store = store;
        this.notes = new CampaignNotes(store.root());
        this.chatId = Math.max(System.currentTimeMillis(), store.historyTail(campaign.getId(), Integer.MAX_VALUE)
                .stream().mapToLong(m -> m.path("id").asLong()).max().orElse(0));
        this.assets = assets;
        this.host = host;
        this.limiter = new LoginRateLimiter(config.loginMaxFailures(), config.loginLockoutSeconds() * 1000L);
    }

    // ---- lifecycle ----

    public synchronized void start() {
        if (app != null) {
            return;
        }
        sessionId = java.util.UUID.randomUUID().toString();
        sessionRolls.clear();
        store.beginSession(sessionResourceIds());
        resetSessionUses();
        Javalin javalin = Javalin.create(cfg -> {
            cfg.showJavalinBanner = false;
            cfg.http.maxRequestSize = 9L * 1024 * 1024;
            cfg.jetty.modifyWebSocketServletFactory(factory -> factory.setMaxTextMessageSize(config.maxMessageBytes()));
            cfg.staticFiles.add(files -> {
                files.directory = "/web";
                files.location = Location.CLASSPATH;
                files.hostedPath = "/";
            });
            cfg.jetty.modifyServletContextHandler(handler -> handler.addFilter(
                    new org.eclipse.jetty.servlet.FilterHolder(new jakarta.servlet.Filter() {
                        @Override
                        public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res,
                                             jakarta.servlet.FilterChain chain) throws IOException, jakarta.servlet.ServletException {
                            jakarta.servlet.http.HttpServletRequest request = (jakarta.servlet.http.HttpServletRequest) req;
                            if (playerOf(cookieValue(request.getHeader("Cookie"))) == null) {
                                ((jakarta.servlet.http.HttpServletResponse) res).sendError(401, "Login required");
                                return;
                            }
                            chain.doFilter(req, res);
                        }
                    }), "/ws", java.util.EnumSet.of(jakarta.servlet.DispatcherType.REQUEST)));
        });
        javalin.get("/api/session", this::handleSession);
        javalin.post("/api/login", this::handleLogin);
        javalin.post("/api/logout", this::handleLogout);
        javalin.get("/api/sheet/{id}", this::handleSheet);
        javalin.put("/api/sheet/{id}", this::handleSheetSave);
        javalin.post("/api/feature", this::handleFeature);
        javalin.post("/api/death-move", this::handleDeathMove);
        javalin.get("/api/death-move/{id}", this::handleDeathOptions);
        javalin.post("/api/death-recovery", this::handleDeathRecovery);
        javalin.post("/api/roll-reward", this::handleRollReward);
        javalin.post("/api/damage-taken", this::handleDamageTaken);
        javalin.get("/api/notes", ctx -> handleNotes(ctx, "list"));
        javalin.get("/api/notes/content", ctx -> handleNotes(ctx, "read"));
        javalin.post("/api/notes", ctx -> handleNotes(ctx, "edit"));
        javalin.get("/api/handouts", this::handleHandoutList);
        javalin.get("/api/handouts/{id}", this::handleHandoutImage);
        javalin.post("/api/chat-cards", this::handleShareCard);
        javalin.get("/api/chat-cards/{id}", this::handleCardStock);
        javalin.post("/api/chat-cards/{id}/grab", this::handleGrabCard);
        javalin.get("/api/compendium", this::handleCompendiumIndex);
        javalin.get("/api/compendium/{category}", this::handleCompendiumCategory);
        javalin.get("/api/dm/compendium/actors", this::handleDmCompendiumActors);
        javalin.post("/api/dm/compendium/add", ctx -> handleDmCompendiumAction(ctx, false));
        javalin.post("/api/dm/compendium/link", ctx -> handleDmCompendiumAction(ctx, true));
        javalin.put("/api/actors/{id}/artwork", this::handleArtwork);
        javalin.get("/a/{name}", this::handleAsset);
        javalin.get("/t/{key}/{level}/{tile}", this::handleTile);
        javalin.get("/sheet/{id}", this::serveIndex);
        javalin.get("/", this::serveIndex);
        javalin.ws("/ws", ws -> {
            ws.onConnect(this::onConnect);
            ws.onMessage(ctx -> {
                try {
                    onMessage(ctx, ctx.message());
                } catch (Exception e) {
                    sendTo(ctx, error("Request failed"));
                }
            });
            ws.onClose(ctx -> onDisconnect(ctx.sessionId()));
        });
        javalin.start(config.port());
        app = javalin;
        pinger = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "online-ping");
            thread.setDaemon(true);
            return thread;
        });
        pinger.scheduleWithFixedDelay(this::pingClients, 1, PING_INTERVAL_SECONDS,
                java.util.concurrent.TimeUnit.SECONDS);
    }

    /** Sends an application-level ping; the browser answers with {@code pong} so the DM sees each player's latency. */
    synchronized void pingClients() {
        publishRoster();
        for (Client client : clients.values()) {
            long id = ++pingCounter;
            client.pingId = id;
            client.pingSentNanos = System.nanoTime();
            sendTo(client.ctx, "{\"type\":\"ping\",\"id\":" + id + "}");
        }
    }

    private void onPong(Client client, long id) {
        if (id != 0 && id == client.pingId && client.pingSentNanos != 0) {
            client.rttMs = Math.max(0, (System.nanoTime() - client.pingSentNanos) / 1_000_000L);
            client.pingSentNanos = 0;
            publishRoster();
        }
    }

    public synchronized void stop() {
        if (app == null) {
            return;
        }
        if (pinger != null) {
            pinger.shutdownNow();
            pinger = null;
        }
        broadcast("{\"type\":\"kicked\",\"reason\":\"Session ended\"}");
        for (Client client : new ArrayList<>(clients.values())) {
            try {
                client.ctx.closeSession(1001, "Session ended");
            } catch (RuntimeException ignored) {
                // already closed
            }
        }
        clients.clear();
        sessions.clear();
        loginClaims.clear();
        sessionRolls.clear();
        app.stop();
        app = null;
        current = null;
        currentMapKey = null;
        measurements.clear();
        host.measurementsChanged();
        logSeqs.clear();
        logJson.clear();
    }

    public boolean isRunning() {
        return app != null;
    }

    public int port() {
        return app == null ? config.port() : app.port();
    }

    /** A local-only capability for the embedded DM window; never include this URL in invite links or snapshots. */
    public String dmCompendiumUrl() {
        if (!isRunning()) throw new IllegalStateException("Start hosting a campaign before opening the compendium.");
        return "http://127.0.0.1:" + port() + "/?dm-compendium=1#" + dmCompendiumToken;
    }

    private boolean isDmCompendium(Context ctx) {
        String address = ctx.req().getRemoteAddr();
        return ("127.0.0.1".equals(address) || "::1".equals(address)
                || "0:0:0:0:0:0:0:1".equals(address))
                && ("Bearer " + dmCompendiumToken).equals(ctx.header("Authorization"));
    }

    private boolean requireDmCompendium(Context ctx) {
        if (isDmCompendium(ctx)) return true;
        json(ctx, 403, errorBody("Only the hosting DM can use this compendium control."));
        return false;
    }

    private synchronized void handleDmCompendiumActors(Context ctx) {
        if (!requireDmCompendium(ctx)) return;
        ArrayNode actors = MAPPER.createArrayNode();
        for (Campaign.Actor actor : campaign.getActors()) {
            Campaign.Player owner = campaign.player(actor.getOwnerId());
            if (owner != null)
                actors.addObject().put("id", actor.getId()).put("name", actor.getName() + " (" + owner.getName() + ")");
        }
        json(ctx, 200, actors);
    }

    private JsonNode compendiumDocument(String category) throws IOException {
        java.nio.file.Path dir = host.compendiumDirectory();
        java.nio.file.Path file = dir == null || !COMPENDIUM_NAME.matcher(category).matches()
                ? null : dir.resolve(category + ".json");
        if (file == null || !java.nio.file.Files.isRegularFile(file))
            throw new IllegalArgumentException("Unknown compendium category.");
        return MAPPER.readTree(file.toFile());
    }

    private synchronized void handleDmCompendiumAction(Context ctx, boolean link) {
        if (!requireDmCompendium(ctx)) return;
        if (ctx.bodyAsBytes().length > config.maxMessageBytes()) {
            json(ctx, 413, errorBody("Compendium request is too large."));
            return;
        }
        try {
            JsonNode request = MAPPER.readTree(ctx.body());
            if (request == null || !request.isObject())
                throw new IllegalArgumentException("Invalid compendium request.");
            String category = request.path("category").asText();
            JsonNode document = compendiumDocument(category);
            JsonNode entry = CompendiumEntries.entry(document, request.path("entryId").asText());
            if (link) {
                ObjectNode item = ChatCards.compendiumItem(document, entry.path("id").asText());
                publishSharedCard("dm", "DM", "", "Compendium - " + document.path("label").asText(),
                        "feature", item, 0, "", true);
                json(ctx, 200, MAPPER.createObjectNode().put("linked", true));
            } else {
                Campaign.Actor actor = campaign.actor(request.path("actorId").asText());
                if (actor == null || campaign.player(actor.getOwnerId()) == null)
                    throw new IllegalArgumentException("Choose a player-owned campaign character.");
                ObjectNode sheet = storedSheet(actor.getId());
                String target = CompendiumEntries.add(sheet, category, entry);
                sheet.put("inventoryRevision", sheet.path("inventoryRevision").asLong() + 1);
                if ("domain-cards".equals(category))
                    sheet.put("compendiumRevision", sheet.path("compendiumRevision").asLong() + 1);
                if (sheet.toString().getBytes(StandardCharsets.UTF_8).length > MAX_SHEET_BYTES)
                    throw new IllegalArgumentException("The receiving character sheet is too large.");
                store.saveSheet(campaign.getId(), actor.getId(), sheet.toString());
                inventoryChanged(actor, sheet);
                json(ctx, 200, MAPPER.createObjectNode().put("target", target));
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            json(ctx, 400, errorBody("Invalid compendium request or data."));
        } catch (IllegalArgumentException e) {
            json(ctx, 400, errorBody(e.getMessage()));
        } catch (IOException e) {
            json(ctx, 500, errorBody("Could not read the compendium or save the character sheet/chat link."));
        }
    }

    // ---- state publication ----

    public synchronized void setCampaign(Campaign campaign) {
        this.campaign = campaign;
        sharedCards.clear();
    }

    public Campaign campaign() {
        return campaign;
    }

    // ---- handouts ----

    /** The handout library of the hosted campaign; players see it filtered by their grants. */
    public CampaignHandouts handouts() {
        Campaign c = campaign;
        synchronized (handoutsLock) {
            if (handouts == null || !handouts.campaignId().equals(c.getId())) {
                handouts = new CampaignHandouts(store.root(), c.getId());
                handouts.addListener(this::publishHandouts);
            }
            return handouts;
        }
    }

    /** Sends every connected player their own filtered handout list; {@code showId} also opens that image for all. */
    private void publishHandouts(String showId) {
        CampaignHandouts library = handouts();
        for (Client client : clients.values()) {
            sendHandouts(library, client);
        }
        if (showId != null) {
            try {
                CampaignHandouts.FileEntry entry = library.find(showId);
                if (entry != null) {
                    broadcast(MAPPER.createObjectNode().put("type", "handoutShow").put("id", entry.id)
                            .put("name", entry.name).toString());
                }
            } catch (IOException ignored) {
            }
        }
    }

    private void sendHandouts(CampaignHandouts library, Client client) {
        try {
            sendTo(client.ctx, handoutsJson(library.listFor(client.playerId)));
        } catch (IOException ignored) {
        }
    }

    static String handoutsJson(CampaignHandouts.Listing listing) {
        ObjectNode message = MAPPER.createObjectNode().put("type", "handouts");
        ArrayNode folders = message.putArray("folders");
        listing.folders().forEach(folders::add);
        ArrayNode files = message.putArray("files");
        for (CampaignHandouts.FileEntry f : listing.files()) {
            files.addObject().put("id", f.id).put("name", f.name).put("folder", f.folder).put("ext", f.ext).put("size", f.size);
        }
        return message.toString();
    }

    private void handleHandoutList(Context ctx) {
        String player = requirePlayer(ctx);
        if (player == null) return;
        ctx.header("Cache-Control", "no-store");
        try {
            ctx.contentType("application/json").result(handoutsJson(handouts().listFor(player)));
        } catch (IOException e) {
            json(ctx, 500, errorBody("Could not read the handouts."));
        }
    }

    private void handleHandoutImage(Context ctx) {
        String player = requirePlayer(ctx);
        if (player == null) return;
        String id = ctx.pathParam("id");
        if (!CampaignStore.safeId(id)) {
            json(ctx, 404, errorBody("Unknown handout."));
            return;
        }
        try {
            CampaignHandouts library = handouts();
            CampaignHandouts.FileEntry entry = library.find(id);
            java.nio.file.Path file = library.open(id, player, false);
            ctx.header("Cache-Control", "private, no-cache");
            ctx.header("X-Content-Type-Options", "nosniff");
            ctx.contentType(CampaignHandouts.contentType(entry.ext));
            ctx.result(java.nio.file.Files.newInputStream(file));
        } catch (CampaignHandouts.Rejected e) {
            json(ctx, e.status(), errorBody(e.getMessage()));
        } catch (IOException e) {
            json(ctx, 500, errorBody("Could not read the handout."));
        }
    }

    public boolean isPaused() {
        return paused;
    }

    /** Publishes the music and effects state to every browser; late joiners receive the latest one on connect. */
    public synchronized void publishAudio(ObjectNode audio) {
        ObjectNode message = audio.deepCopy();
        message.put("type", "audio");
        audioJson = message.toString();
        broadcast(audioJson);
    }

    public synchronized void setPaused(boolean paused) {
        this.paused = paused;
        if (current != null) {
            current.put("paused", paused);
        }
        broadcast("{\"type\":\"pause\",\"paused\":" + paused + "}");
    }

    public synchronized long seq() {
        return seq;
    }

    /**
     * Publishes the latest host state. A different map key sends fresh snapshots; otherwise the changes against the
     * previous state are serialised once and broadcast as one delta.
     */
    public synchronized void publish(ObjectNode state, String mapKey) {
        state.put("paused", paused);
        if (current == null || !mapKey.equals(currentMapKey)) {
            measurements.clear();
            current = state;
            currentMapKey = mapKey;
            seq++;
            logSeqs.clear();
            logJson.clear();
            for (Client client : clients.values()) {
                sendTo(client.ctx, snapshotJson(client));
            }
            publishMeasurements();
            host.measurementsChanged();
            return;
        }
        ArrayNode ops = MAPPER.createArrayNode();
        for (String category : DIFF_CATEGORIES) {
            JsonNode before = current.get(category);
            JsonNode after = state.get(category);
            if (before == null ? after != null : !before.equals(after)) {
                ObjectNode op = ops.addObject();
                op.put("op", "set").put("path", category);
                op.set("value", after == null ? MAPPER.nullNode() : after);
            }
        }
        ObjectNode tokenOp = diffTokens(current.get("tokens"), state.get("tokens"));
        if (tokenOp != null) {
            ops.add(tokenOp);
        }
        current = state;
        if (ops.isEmpty()) {
            return;
        }
        seq++;
        ObjectNode delta = MAPPER.createObjectNode();
        delta.put("type", "delta").put("seq", seq);
        delta.set("ops", ops);
        String json = delta.toString();
        logSeqs.addLast(new long[]{seq});
        logJson.addLast(json);
        while (logJson.size() > Math.max(1, config.changeLogSize())) {
            logSeqs.removeFirst();
            logJson.removeFirst();
        }
        broadcast(json);
    }

    private static ObjectNode diffTokens(JsonNode before, JsonNode after) {
        Map<String, JsonNode> old = new java.util.HashMap<>();
        if (before != null) {
            before.forEach(t -> old.put(t.path("id").asText(), t));
        }
        ArrayNode upsert = MAPPER.createArrayNode();
        Set<String> seen = new LinkedHashSet<>();
        if (after != null) {
            for (JsonNode token : after) {
                String id = token.path("id").asText();
                seen.add(id);
                if (!token.equals(old.get(id))) {
                    upsert.add(token);
                }
            }
        }
        ArrayNode remove = MAPPER.createArrayNode();
        old.keySet().stream().filter(id -> !seen.contains(id)).sorted().forEach(remove::add);
        if (upsert.isEmpty() && remove.isEmpty()) {
            return null;
        }
        ObjectNode op = MAPPER.createObjectNode();
        op.put("op", "tokens");
        op.set("upsert", upsert);
        op.set("remove", remove);
        return op;
    }

    private String snapshotJson(Client client) {
        ObjectNode state = current.deepCopy();
        state.put("paused", paused);
        Campaign c = campaign;
        if (c != null) {
            ArrayNode tail = state.putArray("chatTail");
            for (JsonNode entry : store.historyTail(c.getId(), 50)) {
                String to = entry.path("toId").asText("");
                if (to.isEmpty() || client.playerId.equals(to) || client.playerId.equals(entry.path("fromId").asText())) {
                    tail.add(playerView(entry));
                }
            }
        }
        String mapId = state.path("mapId").asText();
        String levelId = state.path("levelId").asText();
        if (c != null && CampaignStore.safeId(mapId) && CampaignStore.safeId(levelId)) {
            BitSet memory = store.loadFog(c.getId(), client.playerId, mapId, levelId);
            if (!memory.isEmpty()) {
                state.putObject("fogMemory").put("cells", encodeBits(memory));
            }
        }
        ObjectNode message = MAPPER.createObjectNode();
        message.put("type", "snapshot").put("seq", seq);
        message.set("state", state);
        return message.toString();
    }

    // ---- roster ----

    /** {@code pingMs} is the best round trip among the player's connections, or -1 while unknown. */
    public record RosterEntry(String playerId, String playerName, int connections, long pingMs, String chatIdentity) {
    }

    public List<RosterEntry> roster() {
        Map<String, long[]> stats = new java.util.LinkedHashMap<>();
        Map<String, String> names = new java.util.HashMap<>();
        for (Client client : clients.values()) {
            long[] s = stats.computeIfAbsent(client.playerId, k -> new long[]{0, -1});
            s[0]++;
            if (client.rttMs >= 0 && (s[1] < 0 || client.rttMs < s[1])) {
                s[1] = client.rttMs;
            }
            names.put(client.playerId, client.playerName);
        }
        List<RosterEntry> result = new ArrayList<>();
        stats.forEach((id, s) -> result.add(new RosterEntry(id, names.get(id), (int) s[0], s[1],
                chatIdentity(id))));
        return result;
    }

    private void publishRoster() {
        ObjectNode out = MAPPER.createObjectNode().put("type", "roster");
        out.set("players", MAPPER.valueToTree(roster()));
        broadcast(out.toString());
    }

    public synchronized List<OnlineMeasurement> measurements() {
        return List.copyOf(measurements.values());
    }

    /** DM edits bypass player ownership, but still target only the currently open session map. */
    public synchronized void dmMeasurement(OnlineMeasurement value, boolean delete) {
        if (!measurements.containsKey(value.id())) return;
        if (delete) measurements.remove(value.id());
        else measurements.put(value.id(), value);
        publishMeasurements();
        host.measurementsChanged();
    }

    /** DM-created measurement (new or updated); returns false when the session limit is reached. */
    public synchronized boolean dmMeasurementUpsert(OnlineMeasurement value) {
        if (!measurements.containsKey(value.id()) && measurements.size() >= 500) return false;
        measurements.put(value.id(), value);
        publishMeasurements();
        host.measurementsChanged();
        return true;
    }

    private synchronized void publishMeasurements() {
        broadcast(measurementsJson());
    }

    private String measurementsJson() {
        ObjectNode out = MAPPER.createObjectNode().put("type", "measurements")
                .put("mapKey", currentMapKey);
        out.set("items", MAPPER.valueToTree(measurements.values()));
        return out.toString();
    }

    private synchronized void onMeasurement(Client client, JsonNode msg) {
        String cmdId = msg.path("cmdId").asText("");
        try {
            if (paused) throw new IllegalArgumentException("paused");
            if (!java.util.Objects.equals(currentMapKey, msg.path("mapKey").asText())) {
                throw new IllegalArgumentException("Map changed");
            }
            String id = msg.path("id").asText("");
            OnlineMeasurement before = measurements.get(id);
            if (before != null && !before.ownerId().equals(client.playerId)) {
                throw new IllegalArgumentException("Only the owner or DM may edit this measurement");
            }
            String action = msg.path("action").asText("");
            if ("delete".equals(action)) {
                if (before == null) throw new IllegalArgumentException("Measurement not found");
                measurements.remove(id);
            } else if ("upsert".equals(action)) {
                if (before == null && measurements.size() >= 500) {
                    throw new IllegalArgumentException("Measurement limit reached; delete old measurements");
                }
                measurements.put(id, OnlineMeasurement.parse(msg, client.playerId));
            } else throw new IllegalArgumentException("Unknown measurement action");
            publishMeasurements();
            host.measurementsChanged();
        } catch (IllegalArgumentException ex) {
            sendTo(client.ctx, reject(cmdId, ex.getMessage(), null));
            sendTo(client.ctx, measurementsJson());
        }
    }

    /** Ends all connections and login sessions of a player (kick, password reset). */
    public synchronized void kick(String playerId, String reason) {
        sessions.values().removeIf(playerId::equals);
        loginClaims.remove(playerId);
        for (Client client : new ArrayList<>(clients.values())) {
            if (client.playerId.equals(playerId)) {
                sendTo(client.ctx, "{\"type\":\"kicked\",\"reason\":" + MAPPER.valueToTree(reason) + "}");
                clients.remove(client.ctx.sessionId());
                client.ctx.closeSession(1008, reason);
            }
        }
        host.rosterChanged();
        publishRoster();
    }

    /** Recent chat the DM may see: public messages and whispers to or from the DM, with secret rolls unmasked. */
    public List<JsonNode> dmHistory(int limit) {
        Campaign c = campaign;
        List<JsonNode> result = new ArrayList<>();
        if (c == null) {
            return result;
        }
        for (JsonNode entry : store.historyTail(c.getId(), limit)) {
            String to = entry.path("toId").asText("");
            if (to.isEmpty() || "dm".equals(to) || "dm".equals(entry.path("fromId").asText())) {
                result.add(entry);
            }
        }
        return result;
    }

    /** DM chat message, public or whispered to one player. */
    public void dmChat(String text, String toPlayerId) {
        if (text == null || text.isBlank()) {
            return;
        }
        ObjectNode message = chatMessage("dm", "DM", toPlayerId, clip(text));
        deliver(message, toPlayerId);
    }

    /** Host-only moderation; deliberately not exposed through any player HTTP or socket command. */
    public synchronized String dmDeleteMessage(long id, long timestamp) {
        Campaign c = campaign;
        if (c == null) {
            return "No online session is running.";
        }
        JsonNode entry = dmHistory(Integer.MAX_VALUE).stream()
                .filter(m -> m.path("id").asLong(-1) == id && m.path("ts").asLong() == timestamp)
                .findFirst().orElse(null);
        if (entry == null) {
            return "Chat message no longer exists or is not visible to the DM.";
        }
        try {
            store.deleteHistoryEntry(c.getId(), id, timestamp);
        } catch (IOException e) {
            return "Could not delete chat message: " + e.getMessage();
        }
        sharedCards.values().removeIf(m -> m.path("id").asLong(-1) == id && m.path("ts").asLong() == timestamp);
        ObjectNode deleted = MAPPER.createObjectNode().put("type", "chatDeleted").put("id", id).put("ts", timestamp);
        String to = entry.path("toId").asText("");
        String from = entry.path("fromId").asText("");
        for (Client client : clients.values()) {
            if (to.isEmpty() || to.equals(client.playerId) || from.equals(client.playerId)) {
                sendTo(client.ctx, deleted.toString());
            }
        }
        host.dmMessage(deleted);
        return null;
    }

    /**
     * DM dice roll, public or whispered. A secret roll is shown to players with the dice but no numbers: the
     * message carries {@code secret:true}, zeroed dice and total, and an expression without modifiers. The real
     * values live in {@code secretReal}, which is persisted for the DM but never sent to a player.
     *
     * @return an error message, or {@code null} when the roll was made
     */
    public String dmRoll(String expression, String toPlayerId, boolean secret) {
        DiceRoller.Result result;
        try {
            result = DiceRoller.roll(expression, config.maxDice(), diceRandom);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        String to = toPlayerId == null || toPlayerId.isEmpty() ? null : toPlayerId;
        if (to != null && clients.values().stream().noneMatch(client -> to.equals(client.playerId))) {
            return "Whisper recipient is offline. Choose another recipient.";
        }
        ObjectNode out = MAPPER.createObjectNode();
        synchronized (this) {
            out.put("id", ++chatId);
        }
        out.put("type", "roll").put("ts", System.currentTimeMillis());
        out.put("fromId", "dm").put("from", "DM");
        putRecipient(out, to);
        ArrayNode rolls = MAPPER.createArrayNode();
        result.rolls().forEach(rolls::add);
        if (secret) {
            out.put("secret", true);
            out.put("expression", maskExpression(result.expression()));
            ArrayNode hidden = out.putArray("rolls");
            result.rolls().forEach(v -> hidden.add(0));
            out.put("total", 0);
            ObjectNode real = out.putObject("secretReal");
            real.put("expression", result.expression());
            real.set("rolls", rolls);
            real.put("total", result.total());
        } else {
            out.put("expression", result.expression());
            out.set("rolls", rolls);
            out.put("total", result.total());
        }
        deliver(out, to);
        return null;
    }

    private static final java.util.regex.Pattern DICE_TERM =
            java.util.regex.Pattern.compile("([+-]?)(\\d*d\\d+(?:kh\\d+)?)");

    /** Keeps only the dice terms of an expression, so a secret roll does not leak its modifiers. */
    static String maskExpression(String expression) {
        StringBuilder out = new StringBuilder();
        java.util.regex.Matcher matcher = DICE_TERM.matcher(expression);
        while (matcher.find()) {
            if (out.isEmpty()) {
                out.append("-".equals(matcher.group(1)) ? "-" : "").append(matcher.group(2));
            } else {
                out.append("-".equals(matcher.group(1)) ? "-" : "+").append(matcher.group(2));
            }
        }
        return out.isEmpty() ? "?" : out.toString();
    }

    /** The message as players may see it: without the DM-only real values of a secret roll. */
    static JsonNode playerView(JsonNode message) {
        if (message.has("secretReal") && message instanceof ObjectNode node) {
            ObjectNode copy = node.deepCopy();
            copy.remove("secretReal");
            return copy;
        }
        return message;
    }

    // ---- HTTP ----

    private static String cookieValue(String header) {
        if (header == null) {
            return null;
        }
        for (String part : header.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(OnlineProtocol.COOKIE + "=")) {
                return trimmed.substring(OnlineProtocol.COOKIE.length() + 1);
            }
        }
        return null;
    }

    private String playerOf(String token) {
        return token == null ? null : sessions.get(token);
    }

    private LoginClaim loginClaim(String playerId) {
        LoginClaim claim = loginClaims.get(playerId);
        if (claim != null && claim.expiresAt() <= System.currentTimeMillis()) {
            loginClaims.remove(playerId);
            return null;
        }
        return claim;
    }

    private synchronized void onDisconnect(String sessionId) {
        Client removed = clients.remove(sessionId);
        if (removed != null) {
            if (clients.values().stream().noneMatch(client -> client.playerId.equals(removed.playerId))) {
                loginClaims.remove(removed.playerId);
            }
            host.rosterChanged();
            publishRoster();
        }
    }

    private void json(Context ctx, int status, JsonNode body) {
        ctx.status(status).contentType("application/json").result(body.toString());
    }

    private ObjectNode errorBody(String message) {
        return MAPPER.createObjectNode().put("error", message);
    }

    private synchronized void handleSession(Context ctx) {
        Campaign c = campaign;
        ObjectNode body = MAPPER.createObjectNode();
        body.put("protocol", OnlineProtocol.VERSION);
        body.put("sessionName", config.sessionName());
        body.put("campaignName", c == null ? "" : c.getName());
        body.put("paused", paused);
        body.put("maxPlayers", config.maxPlayers());
        ArrayNode players = body.putArray("players");
        if (c != null) {
            for (Campaign.Player player : c.getPlayers()) {
                if (loginClaim(player.getId()) == null) {
                    players.addObject().put("id", player.getId()).put("name", player.getName());
                }
            }
        }
        ctx.header("Cache-Control", "no-store");
        json(ctx, 200, body);
    }

    private void handleLogin(Context ctx) {
        JsonNode body;
        try {
            body = MAPPER.readTree(ctx.body());
        } catch (IOException e) {
            json(ctx, 400, errorBody("Invalid request"));
            return;
        }
        String playerId = body.path("playerId").asText("");
        String password = body.path("password").asText("");
        String ipKey = "ip:" + ctx.ip();
        String playerKey = "player:" + playerId;
        long wait = Math.max(limiter.retryAfterSeconds(ipKey), limiter.retryAfterSeconds(playerKey));
        if (wait > 0) {
            ObjectNode out = errorBody("Too many failed attempts");
            out.put("retryAfterSeconds", wait);
            ctx.header("Retry-After", String.valueOf(wait));
            json(ctx, 429, out);
            return;
        }
        Campaign c = campaign;
        Campaign.Player player = c == null ? null : c.player(playerId);
        boolean ok = player != null && PasswordHasher.verify(password, player.getPasswordHash());
        if (!ok) {
            limiter.recordFailure(ipKey);
            limiter.recordFailure(playerKey);
            json(ctx, 401, errorBody("Wrong player or password"));
            return;
        }
        limiter.recordSuccess(ipKey);
        limiter.recordSuccess(playerKey);
        synchronized (this) {
            LoginClaim claim = loginClaim(playerId);
            String existingToken = ctx.cookie(OnlineProtocol.COOKIE);
            if (claim != null && !claim.token().equals(existingToken)) {
                json(ctx, 409, errorBody("This player account is already connected. Choose another player."));
                return;
            }
            String token;
            if (claim != null) {
                token = claim.token();
            } else {
                byte[] raw = new byte[32];
                secureRandom.nextBytes(raw);
                token = HexFormat.of().formatHex(raw);
                sessions.values().removeIf(playerId::equals);
                sessions.put(token, playerId);
            }
            long expiresAt = claim != null && claim.expiresAt() == Long.MAX_VALUE
                    ? Long.MAX_VALUE : System.currentTimeMillis() + LOGIN_RESERVATION_MILLIS;
            loginClaims.put(playerId, new LoginClaim(token, expiresAt));
            ctx.header("Set-Cookie", OnlineProtocol.COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict");
            json(ctx, 200, MAPPER.createObjectNode().put("playerId", player.getId()).put("playerName", player.getName()));
        }
    }

    private synchronized void handleLogout(Context ctx) {
        String token = ctx.cookie(OnlineProtocol.COOKIE);
        String playerId = playerOf(token);
        if (playerId != null) {
            kick(playerId, "Logged out");
        }
        ctx.header("Set-Cookie", OnlineProtocol.COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict");
        json(ctx, 200, MAPPER.createObjectNode().put("ok", true));
    }

    private String requirePlayer(Context ctx) {
        String playerId = playerOf(ctx.cookie(OnlineProtocol.COOKIE));
        if (playerId == null) {
            json(ctx, 401, errorBody("Login required"));
        }
        return playerId;
    }

    private static final java.util.regex.Pattern COMPENDIUM_NAME = java.util.regex.Pattern.compile("[a-z0-9-]{1,64}");

    private synchronized void handleNotes(Context ctx, String operation) {
        String player = requirePlayer(ctx);
        if (player == null) return;
        ctx.header("Cache-Control", "no-store");
        Campaign c = campaign;
        if (c == null || c.player(player) == null) {
            json(ctx, 403, errorBody("Campaign player is unavailable."));
            return;
        }
        try {
            Object result;
            if ("list".equals(operation)) {
                result = notes.list(c.getId(), player);
            } else if ("read".equals(operation)) {
                result = notes.read(c.getId(), player, ctx.queryParam("path"));
            } else {
                if (ctx.bodyAsBytes().length > CampaignNotes.MAX_TEXT_BYTES * 6 + 4096) {
                    json(ctx, 413, errorBody("Note request is too large."));
                    return;
                }
                JsonNode request = MAPPER.readTree(ctx.body());
                if (request == null || !request.isObject())
                    throw new CampaignNotes.Rejected(400, "Invalid note request.");
                String path = noteString(request, "path");
                result = switch (noteString(request, "action")) {
                    case "create" -> {
                        if (!request.path("folder").isBoolean())
                            throw new CampaignNotes.Rejected(400, "Specify whether to create a note or folder.");
                        yield notes.create(c.getId(), player, path, noteString(request, "name"), request.path("folder").asBoolean());
                    }
                    case "rename" -> notes.rename(c.getId(), player, path, noteString(request, "name"));
                    case "delete" -> {
                        notes.delete(c.getId(), player, path);
                        yield Map.of("ok", true);
                    }
                    case "save" -> notes.save(c.getId(), player, path, noteString(request, "text"), noteString(request, "revision"));
                    default -> throw new CampaignNotes.Rejected(400, "Unknown notes action.");
                };
            }
            json(ctx, 200, MAPPER.valueToTree(result));
        } catch (CampaignNotes.Rejected e) {
            json(ctx, e.status(), errorBody(e.getMessage()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            json(ctx, 400, errorBody("Invalid note request JSON."));
        } catch (IOException e) {
            json(ctx, 500, errorBody("Could not access campaign notes. Your changes were not confirmed; reload before retrying."));
        }
    }

    private static String noteString(JsonNode request, String field) {
        if (!request.path(field).isTextual())
            throw new CampaignNotes.Rejected(400, "Missing or invalid notes field: " + field);
        return request.path(field).textValue();
    }

    private void handleCompendiumIndex(Context ctx) {
        if (!isDmCompendium(ctx) && requirePlayer(ctx) == null) {
            return;
        }
        ArrayNode categories = MAPPER.createArrayNode();
        java.nio.file.Path dir = host.compendiumDirectory();
        if (dir != null && java.nio.file.Files.isDirectory(dir)) {
            try (var files = java.nio.file.Files.list(dir)) {
                files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().forEach(p -> {
                    String name = p.getFileName().toString();
                    name = name.substring(0, name.length() - 5);
                    if (!COMPENDIUM_NAME.matcher(name).matches()) {
                        return;
                    }
                    try {
                        JsonNode doc = MAPPER.readTree(p.toFile());
                        ObjectNode c = categories.addObject();
                        c.put("category", name);
                        c.put("label", doc.path("label").asText(name));
                        c.put("count", doc.path("entries").size());
                    } catch (IOException ignored) {
                        // a malformed homebrew file is skipped
                    }
                });
            } catch (IOException ignored) {
                // unreadable folder: report no categories
            }
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.set("categories", categories);
        json(ctx, 200, body);
    }

    private void handleCompendiumCategory(Context ctx) {
        if (!isDmCompendium(ctx) && requirePlayer(ctx) == null) {
            return;
        }
        String name = ctx.pathParam("category");
        java.nio.file.Path dir = host.compendiumDirectory();
        java.nio.file.Path file = dir == null || !COMPENDIUM_NAME.matcher(name).matches() ? null : dir.resolve(name + ".json");
        if (file == null || !java.nio.file.Files.isRegularFile(file)) {
            json(ctx, 404, errorBody("Unknown compendium category"));
            return;
        }
        try {
            ctx.status(200).contentType("application/json").result(java.nio.file.Files.readAllBytes(file));
        } catch (IOException e) {
            json(ctx, 500, errorBody("Compendium unavailable"));
        }
    }

    private synchronized void handleSheet(Context ctx) {
        String playerId = requirePlayer(ctx);
        if (playerId == null) {
            return;
        }
        Campaign c = campaign;
        Campaign.Actor actor = c == null ? null : c.actor(ctx.pathParam("id"));
        if (actor == null || !playerId.equals(actor.getOwnerId())) {
            json(ctx, 404, errorBody("Unknown actor"));
            return;
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.put("actorId", actor.getId());
        body.put("name", actor.getName());
        body.put("testingCharacter", actor.isTestingCharacter());
        try {
            String stored = store.loadSheet(c.getId(), actor.getId());
            if (stored == null) {
                body.putNull("sheet");
            } else {
                body.set("sheet", MAPPER.readTree(stored));
            }
        } catch (IOException e) {
            json(ctx, 500, errorBody("Could not read the character sheet."));
            return;
        }
        json(ctx, 200, body);
    }

    static final int MAX_SHEET_BYTES = 512 * 1024;

    private synchronized void handleSheetSave(Context ctx) {
        String playerId = requirePlayer(ctx);
        if (playerId == null) {
            return;
        }
        Campaign c = campaign;
        Campaign.Actor actor = c == null ? null : c.actor(ctx.pathParam("id"));
        if (actor == null || !playerId.equals(actor.getOwnerId())) {
            json(ctx, 403, errorBody("Not your actor"));
            return;
        }
        byte[] bytes = ctx.bodyAsBytes();
        if (bytes.length > MAX_SHEET_BYTES) {
            json(ctx, 413, errorBody("Sheet is too large"));
            return;
        }
        try {
            JsonNode sheet = MAPPER.readTree(bytes);
            if (sheet == null || !sheet.isObject()) {
                json(ctx, 400, errorBody("Sheet must be a JSON object"));
                return;
            }
            ObjectNode previous = storedSheet(actor.getId());
            if (sheet.path("inventoryRevision").asLong(0) != previous.path("inventoryRevision").asLong(0)
                    || sheet.path("compendiumRevision").asLong(0) != previous.path("compendiumRevision").asLong(0)
                    || sheet.path("featureRevision").asLong(0) != previous.path("featureRevision").asLong(0)) {
                ObjectNode conflict = errorBody("Inventory, domain cards or feature state changed on the host. Refresh the sheet before saving.");
                conflict.set("sheet", previous);
                json(ctx, 409, conflict);
                return;
            }
            String problem = CharacterSheetRules.problem(sheet, previous, actor.isTestingCharacter(), c.getFear());
            if (problem != null) {
                json(ctx, 403, errorBody(problem));
                return;
            }
            ObjectNode saved = (ObjectNode) sheet;
            validateDeathSave(saved, previous);
            if (!saved.has("scars") && previous.has("scars"))
                saved.set("scars", previous.get("scars"));
            JsonNode scars = saved.path("scars");
            if (!scars.isMissingNode() && (!scars.isInt() || scars.asInt() < 0
                    || scars.asInt() > saved.path("hope").path("total").asInt(6))) {
                json(ctx, 400, errorBody("Scars must be a whole number between zero and the total Hope slots."));
                return;
            }
            saved.remove("transferClaims");
            if (previous.has("transferClaims"))
                saved.set("transferClaims", previous.get("transferClaims"));
            ObjectNode beforeNormalization = saved.deepCopy();
            Map<String, ObjectNode> changes = new java.util.LinkedHashMap<>();
            changes.put(actor.getId(), saved);
            List<String> deathReports = normalizeDeathChanges(changes);
            if (!saved.equals(beforeNormalization) || changes.size() > 1
                    || saved.path("longRestRevision").asLong() != previous.path("longRestRevision").asLong()
                    || !saved.path("pendingDeathRoll").equals(previous.path("pendingDeathRoll"))) {
                ObjectNode report = deathReports.isEmpty() ? null : deathStateMessage(playerId, campaign.player(playerId).getName(),
                        actor.getName(), deathReports);
                persistFeatureSheets(changes, report, report != null);
                for (var entry : changes.entrySet()) sendFeatureState(c.actor(entry.getKey()), entry.getValue());
                if (report != null) deliver(report, null, false);
            } else {
                store.saveSheet(c.getId(), actor.getId(), saved.toString());
            }
            stockChanged(actor.getId());
            json(ctx, 200, MAPPER.createObjectNode().put("saved", true));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            json(ctx, 400, errorBody("Invalid sheet"));
        } catch (IllegalArgumentException e) {
            json(ctx, 400, errorBody(e.getMessage()));
        } catch (IOException e) {
            json(ctx, 500, errorBody("Could not read or save the character sheet."));
        }
    }

    private void validateDeathSave(ObjectNode saved, ObjectNode previous) {
        int permanent = previous.path("permanentHp").asInt();
        if (saved.has("permanentHp") && (!saved.path("permanentHp").isInt() || saved.path("permanentHp").asInt() != permanent))
            throw new IllegalArgumentException("Permanent HP can only be marked by Won't Stay Dead in chat.");
        if (permanent > saved.path("hp").path("slots").asInt())
            throw new IllegalArgumentException("The HP track cannot have fewer slots than permanently marked HP.");
        if (previous.has("permanentHp")) saved.set("permanentHp", previous.get("permanentHp"));
        if (previous.has("spectralBlocked")) saved.set("spectralBlocked", previous.get("spectralBlocked"));
        saved.remove("pendingDeathRoll");
        if (previous.path("pendingDeathRoll").isObject()
                && saved.path("hp").path("on").asInt() >= previous.path("hp").path("on").asInt()
                && saved.path("stress").path("on").asInt() >= previous.path("stress").path("on").asInt())
            saved.set("pendingDeathRoll", previous.get("pendingDeathRoll"));
        int rest = DeathMoves.integer(saved, "longRestRevision", previous.path("longRestRevision").asInt());
        int oldRest = previous.path("longRestRevision").asInt();
        if (rest < oldRest || (long) rest > (long) oldRest + 1)
            throw new IllegalArgumentException("Refresh the sheet before reporting another long rest.");
        if (saved.has("longRestRevision") || previous.has("longRestRevision")) saved.put("longRestRevision", rest);
        if (saved.path("resources").has("death-mask")) {
            int tokens = DeathMoves.integer(saved.path("resources"), "death-mask", 0);
            if (tokens > 4) throw new IllegalArgumentException("Mask tokens must be between zero and four.");
        }
    }

    private List<DeathMoves.Source> deathSources(ObjectNode sheet) throws IOException {
        List<DeathMoves.Source> sources = new ArrayList<>();
        List<JsonNode> catalogs = new ArrayList<>();
        java.nio.file.Path directory = host.compendiumDirectory();
        if (directory != null) {
            try (var files = java.nio.file.Files.list(directory)) {
                for (var file : files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList())
                    catalogs.add(MAPPER.readTree(file.toFile()));
            }
        }
        for (String field : List.of("features", "heritage", "loadout", "activeArmor", "inventory")) {
            JsonNode values = sheet.path(field);
            Iterable<JsonNode> entries = values.isArray() ? values : values.isObject() ? List.of(values) : List.of();
            for (JsonNode entry : entries) {
                if ("inventory".equals(field) && entry.path("qty").asInt() <= 0) continue;
                String name = entry.path("name").asText();
                String kind = "loadout".equals(field) ? "card" : "activeArmor".equals(field) ? "armor"
                        : "inventory".equals(field) ? "item" : "feat";
                JsonNode automation = directory == null ? entry.path("automation") : null;
                for (JsonNode catalog : catalogs) {
                    JsonNode found = findCatalogFeature(catalog, name);
                    if (found != null) { automation = found; break; }
                }
                if (automation != null && automation.path("deathMove").isObject())
                    sources.add(new DeathMoves.Source(kind + ":" + name, name, automation, entry));
            }
        }
        return sources;
    }

    private synchronized void handleDeathOptions(Context ctx) {
        String playerId = requirePlayer(ctx);
        if (playerId == null) return;
        try {
            Campaign.Actor actor = ownedActor(playerId, ctx.pathParam("id"));
            ObjectNode sheet = storedSheet(actor.getId());
            json(ctx, 200, DeathMoves.options(sheet, deathSources(sheet)));
        } catch (IllegalArgumentException e) {
            json(ctx, 400, errorBody(e.getMessage()));
        } catch (IOException e) {
            json(ctx, 500, errorBody("Could not load death-move options. Check the host's Daggerheart configurations."));
        }
    }

    private synchronized void handleDeathRecovery(Context ctx) {
        String playerId = requirePlayer(ctx);
        if (playerId == null) return;
        if (ctx.bodyAsBytes().length > config.maxMessageBytes()) {
            json(ctx, 413, errorBody("Death recovery request is too large."));
            return;
        }
        try {
            JsonNode request = MAPPER.readTree(ctx.body());
            if (request == null || !request.isObject() || !request.path("roll").isObject())
                throw new IllegalArgumentException("Invalid death recovery request.");
            Campaign.Actor actor = ownedActor(playerId, request.path("actorId").asText());
            ObjectNode sheet = storedSheet(actor.getId());
            ObjectNode roll = findHistoryRoll(request.path("roll"));
            if (!playerId.equals(roll.path("fromId").asText()) || !actor.getId().equals(roll.path("actorId").asText()))
                throw new IllegalArgumentException("Only the character who made this death roll can resolve it.");
            JsonNode pending = sheet.path("pendingDeathRoll");
            if (!pending.isObject() || pending.path("id").asLong(-1) != roll.path("id").asLong()
                    || pending.path("ts").asLong(-1) != roll.path("ts").asLong())
                throw new IllegalArgumentException("That death roll is no longer pending.");
            boolean undying = deathSources(sheet).stream().anyMatch(s -> "undying".equals(s.kind()));
            DeathMoves.recover(sheet, roll, request, undying);
            commitFeatureSheets(Map.of(actor.getId(), sheet), roll);
            sendFeatureState(actor, sheet);
            broadcastRollUpdate(roll);
            json(ctx, 200, MAPPER.createObjectNode().put("ok", true));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            json(ctx, 400, errorBody("Invalid death recovery request."));
        } catch (IllegalArgumentException e) {
            json(ctx, 400, errorBody(e.getMessage()));
        } catch (IOException e) {
            json(ctx, 500, errorBody("Could not save the death recovery."));
        }
    }

    private synchronized void handleDeathMove(Context ctx) {
        String playerId = requirePlayer(ctx);
        if (playerId == null) return;
        if (ctx.bodyAsBytes().length > config.maxMessageBytes()) {
            json(ctx, 413, errorBody("Death move request is too large."));
            return;
        }
        try {
            JsonNode request = MAPPER.readTree(ctx.body());
            if (request == null || !request.isObject())
                throw new IllegalArgumentException("Invalid death move request.");
            if (campaign == null) throw new IllegalArgumentException("No online session is running.");
            Campaign.Actor actor = campaign.actor(request.path("actorId").asText());
            if (actor == null || !playerId.equals(actor.getOwnerId())) {
                json(ctx, 403, errorBody("Not your actor."));
                return;
            }
            ObjectNode sheet = storedSheet(actor.getId());
            String move = request.path("move").asText();
            Map<String, ObjectNode> normalized = new java.util.LinkedHashMap<>();
            normalized.put(actor.getId(), sheet);
            List<String> pendingReports = normalizeDeathChanges(normalized);
            if (!pendingReports.isEmpty()) {
                ObjectNode report = deathStateMessage(playerId, campaign.player(playerId).getName(), actor.getName(), pendingReports);
                persistFeatureSheets(normalized, report, true);
                for (var entry : normalized.entrySet()) sendFeatureState(campaign.actor(entry.getKey()), entry.getValue());
                deliver(report, null, false);
                if (sheet.path("hp").path("on").asInt() < sheet.path("hp").path("slots").asInt()) {
                    json(ctx, 200, MAPPER.createObjectNode().put("ok", true));
                    return;
                }
            }
            DeathMoves.Result result = DeathMoves.take(sheet, request, deathSources(sheet), diceRandom);
            ObjectNode message;
            if (result.dice() == null) {
                message = chatMessage(playerId, campaign.player(playerId).getName(), null,
                        "ACTION USED\nDeath move - " + result.title() + "\n" + actor.getName()
                                + "\n• " + actor.getName() + ": " + result.consequence());
            } else {
                ObjectNode source = MAPPER.createObjectNode().put("name", result.title())
                        .put("source", "Death move").put("text", result.consequence());
                message = featureRollMessage(actor, source, actor.getName() + " - " + result.title(), result.dice());
                message.put("from", chatName(playerId, campaign.player(playerId).getName()));
                if (List.of("risk", "avoid").contains(move)) message.put("deathMove", move);
                message.putObject("featureRoll").put("name", result.title()).put("result", result.dice().total())
                        .put("consequence", result.consequence());
                message.set("breakdown", MAPPER.valueToTree(DiceRoller.breakdown(result.dice(), result.contributions(), 2)));
                if (result.deathResult() != null) {
                    message.set("deathResult", result.deathResult());
                    message.put("outcome", result.deathResult().path("outcome").asText());
                    if (!"fear".equals(result.deathResult().path("outcome").asText()) || result.deathResult().path("undying").asBoolean())
                        sheet.putObject("pendingDeathRoll").put("id", message.path("id").asLong()).put("ts", message.path("ts").asLong());
                }
            }
            commitFeatureSheets(Map.of(actor.getId(), sheet), message, true);
            sendFeatureState(actor, sheet);
            deliver(message, null, false);
            json(ctx, 200, MAPPER.createObjectNode().put("ok", true));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            json(ctx, 400, errorBody("Invalid death move request."));
        } catch (IllegalArgumentException e) {
            json(ctx, 400, errorBody(e.getMessage()));
        } catch (IOException e) {
            json(ctx, 500, errorBody("Could not save the death move."));
        }
    }

    private synchronized void handleFeature(Context ctx) {
        String playerId = requirePlayer(ctx);
        if (playerId == null) return;
        if (ctx.bodyAsBytes().length > config.maxMessageBytes()) {
            json(ctx, 413, errorBody("Feature request is too large."));
            return;
        }
        try {
            JsonNode request = MAPPER.readTree(ctx.body());
            if (request == null || !request.isObject())
                throw new IllegalArgumentException("Invalid feature request.");
            Campaign c = campaign;
            if (c == null) throw new IllegalArgumentException("No online session is running.");
            Campaign.Actor sourceActor = ownedActor(playerId, request.path("actorId").asText());
            if (request.has("benefitId")) {
                claimActionBenefit(request, sourceActor);
                json(ctx, 200, MAPPER.createObjectNode().put("ok", true));
                return;
            }
            String key = request.path("key").asText("").strip();
            String actionId = request.path("actionId").asText("").strip();
            if (key.length() > 160 || actionId.isEmpty() || actionId.length() > 100)
                throw new IllegalArgumentException("Invalid feature action.");

            ObjectNode sourceSheet = storedSheet(sourceActor.getId());
            JsonNode source = featureSource(sourceSheet, key);
            JsonNode automation = catalogAutomation(key, source.path("name").asText(), source.path("automation"));
            if (request.has("restDie")) {
                if (!automation.path("restReroll").asBoolean() || !"reroll".equals(actionId))
                    throw new IllegalArgumentException("This feature cannot reroll downtime dice.");
                rerollRestDie(request, sourceActor, source, sourceSheet, key, automation);
                json(ctx, 200, MAPPER.createObjectNode().put("ok", true));
                return;
            }
            boolean hasRoll = request.path("roll").isObject();
            JsonNode definition = featureDefinition(automation, actionId, hasRoll);
            if (definition == null) throw new IllegalArgumentException("This feature action is no longer available.");
            if (definition.has("deathMove"))
                throw new IllegalArgumentException("Use the Death move dialog to resolve this feature and its costs.");
            if (definition.has("requiresEffect")) {
                boolean active = false;
                for (JsonNode effect : sourceSheet.path("effects"))
                    if (definition.path("requiresEffect").asText().equals(effect.path("name").asText())) active = true;
                if (!active) throw new IllegalArgumentException("The required effect is no longer active.");
            }

            ObjectNode originalRoll = hasRoll ? findSessionRoll(request.path("roll")) : null;
            if (hasRoll) validateTrigger(sourceActor, key, definition, originalRoll);
            else if (!definition.has("target") && !definition.has("check") && !definition.has("chatBenefits"))
                throw new IllegalArgumentException("This feature action must be made from its character sheet.");

            String targetMode = definition.path("target").asText("");
            boolean lifeWard = "lifeWard".equals(automation.path("deathMove").path("kind").asText());
            List<Campaign.Actor> targets;
            if (lifeWard) {
                if (!sourceSheet.path("loadout").isArray() || java.util.stream.StreamSupport.stream(sourceSheet.path("loadout").spliterator(), false)
                        .noneMatch(card -> source.path("name").asText().equals(card.path("name").asText())))
                    throw new IllegalArgumentException("Life Ward must be in the active loadout.");
                Campaign.Actor target = c.actor(request.path("targetActorId").asText());
                if (target == null || target.getOwnerId() == null || c.player(target.getOwnerId()) == null
                        || target.getId().equals(sourceActor.getId()))
                    throw new IllegalArgumentException("Choose an ally within Close range for Life Ward.");
                if (!request.path("confirmRange").asBoolean())
                    throw new IllegalArgumentException("Confirm that the ally is within Close range.");
                targets = List.of(target);
            } else {
                targets = resolveFeatureTargets(c, sourceActor, request.path("targetActorId").asText(""), targetMode);
            }
            Map<String, ObjectNode> changedSheets = new java.util.LinkedHashMap<>();
            changedSheets.put(sourceActor.getId(), sourceSheet);
            if (lifeWard) removeLifeWards(changedSheets, sourceActor.getId());
            for (Campaign.Actor target : targets) {
                changedSheets.computeIfAbsent(target.getId(), id -> {
                    try { return storedSheet(id); }
                    catch (IOException e) { throw new FeatureIoException(e); }
                });
            }

            String actionLabel = definition.path("label").asText("Feature");
            String reportSource = source.path("name").asText(sourceActor.getName());
            DiceRoller.Result damageRoll = null;
            if (definition.path("damage").isObject()) {
                JsonNode damage = definition.path("damage");
                if (!damage.path("fixed").asBoolean())
                    throw new IllegalArgumentException("Only fixed damage is supported for this feature reaction.");
                damageRoll = DiceRoller.roll(damage.path("dice").asText(""), config.maxDice(), diceRandom);
            }
            List<String> reportLines = new ArrayList<>();
            if (definition.path("tokenGain").isObject()) {
                JsonNode gain = definition.path("tokenGain");
                JsonNode amount = request.path("amount");
                int max = gain.path("max").asInt();
                if (!amount.isInt() || amount.asInt() < 1 || amount.asInt() > max)
                    throw new IllegalArgumentException("Choose a whole number of Feed tokens from 1 to " + max + ".");
                ObjectNode resources = sourceSheet.withObject("/resources");
                String resource = gain.path("resource").asText();
                int before = resources.path(resource).asInt();
                int after = Math.min(max, before + amount.asInt());
                resources.put(resource, after);
                reportLines.add("Gained " + (after - before) + " Feed tokens (" + after + "/" + max + ").");
            }
            if (definition.path("transferHope").asBoolean()) {
                JsonNode amount = request.path("amount");
                if (!amount.isInt() || amount.asInt() < 1 || targets.size() != 1 || targets.getFirst().equals(sourceActor))
                    throw new IllegalArgumentException("Choose an ally and a positive whole number of Hope.");
                ObjectNode targetSheet = changedSheets.get(targets.getFirst().getId());
                ObjectNode hopeTrack = requireTrack(targetSheet, "hope");
                if (amount.asInt() > hopeTrack.path("slots").asInt() - hopeTrack.path("on").asInt())
                    throw new IllegalArgumentException("The ally cannot receive that much Hope.");
                applyFeatureCost(sourceSheet, MAPPER.createObjectNode().put("hope", amount.asInt()));
                gainTrack(targetSheet, "hope", amount.asInt());
                reportLines.add("Gave " + amount.asInt() + " Hope to " + targets.getFirst().getName() + ".");
            }
            applyFeatureCost(sourceSheet, definition.path("cost"));
            JsonNode uses = definition.path("uses").isObject() ? definition.path("uses") : automation.path("uses");
            useFeatureLimit(sourceSheet, key, effectiveFeatureUses(sourceSheet, key, uses));

            if (definition.path("check").isObject()) {
                JsonNode check = definition.path("check");
                DiceRoller.Result result = DiceRoller.roll(check.path("dice").asText(""), config.maxDice(), diceRandom);
                int die = result.rolls().isEmpty() ? 0 : result.rolls().getFirst();
                boolean marked = die != check.path("unless").asInt(Integer.MIN_VALUE);
                String mark = check.path("mark").asText("stress");
                if (!isTrack(mark)) throw new IllegalArgumentException("Invalid feature check consequence.");
                if (marked) markTrack(sourceSheet, mark, 1);
                String resourceLabel = switch (mark) {
                    case "hp" -> "HP";
                    case "hope" -> "Hope";
                    case "armor" -> "Armor";
                    default -> "Stress";
                };
                String consequence = marked ? "Marked 1 " + resourceLabel + "." : "No " + resourceLabel + " marked.";
                changedSheets.put(sourceActor.getId(), sourceSheet);
                commitFeatureSheets(changedSheets);
                for (var entry : changedSheets.entrySet()) sendFeatureState(c.actor(entry.getKey()), entry.getValue());
                publishFeatureCheck(sourceActor, source, actionLabel, result, consequence);
                json(ctx, 200, MAPPER.createObjectNode().put("ok", true));
                return;
            }

            for (JsonNode step : definition.path("steps")) {
                JsonNode effect = step.path("effect");
                if (!"effect".equals(step.path("op").asText()) || !effect.path("endOnSourceSevere").asBoolean()) continue;
                for (Campaign.Actor actor : c.getActors()) {
                    ObjectNode existing = changedSheets.get(actor.getId());
                    boolean wasLoaded = existing != null;
                    if (existing == null) {
                        try { existing = storedSheet(actor.getId()); }
                        catch (IOException e) { throw new FeatureIoException(e); }
                    }
                    if (removeSevereEffectsFromSource(existing, sourceActor.getId(), key))
                        changedSheets.put(actor.getId(), existing);
                    else if (wasLoaded) changedSheets.put(actor.getId(), existing);
                }
            }

            if (hasRoll && definition.has("outcome")) {
                String outcome = definition.path("outcome").asText("");
                if (!"hope".equals(outcome) && !"fear".equals(outcome))
                    throw new IllegalArgumentException("Invalid outcome conversion.");
                originalRoll.put("outcome", outcome);
            }
            if (hasRoll && definition.has("addDice")) {
                DiceRoller.Result extra = DiceRoller.roll(definition.path("addDice").asText(), config.maxDice(), diceRandom);
                if (originalRoll.path("rolls").size() + extra.rolls().size() > config.maxDice())
                    throw new IllegalArgumentException("Adding these dice exceeds the host's dice limit.");
                originalRoll.put("total", Math.addExact(originalRoll.path("total").asInt(), extra.total()));
                originalRoll.put("expression", originalRoll.path("expression").asText() + "+" + extra.expression());
                for (int die : extra.rolls()) originalRoll.withArray("rolls").add(die);
                ObjectNode detail = originalRoll.withArray("breakdown").addObject()
                        .put("label", actionLabel).put("expression", extra.expression()).put("total", extra.total());
                detail.set("rolls", MAPPER.valueToTree(extra.rolls()));
                reportLines.add("Added " + extra.expression() + " = " + extra.total() + " to the original roll.");
            }

            for (Campaign.Actor target : targets) {
                ObjectNode targetSheet = changedSheets.get(target.getId());
                if (hasRoll && definition.has("endEffect")) {
                    String effectName = definition.path("endEffect").asText();
                    removeEffects(targetSheet, effectName, null, null);
                }
                if (definition.path("steps").isArray()) {
                    List<String> targetLines = new ArrayList<>();
                    applyFeatureSteps(targetSheet, definition.path("steps"), actionLabel, sourceActor, key, targetLines);
                    for (String line : targetLines)
                        reportLines.add(("party".equals(targetMode) || !target.equals(sourceActor)
                                ? target.getName() + ": " : "") + line);
                }
            }

            if (hasRoll) {
                ArrayNode featureUses = originalRoll.withArray("featureUses");
                String use = sourceActor.getId() + ":" + key + ":" + actionId;
                if (!containsText(featureUses, use)) featureUses.add(use);
            }

            ArrayNode benefits = actionBenefits(sourceSheet, key, definition);
            ObjectNode benefitReport = null;
            if (!benefits.isEmpty()) {
                if (reportLines.isEmpty()) reportLines.add("Used " + actionLabel + ".");
                benefitReport = actionReportMessage(sourceActor, source, actionReportTitle(sourceSheet, source, actionLabel), reportLines, null);
                benefitReport.put("actorId", sourceActor.getId());
                ObjectNode claims = benefitReport.putObject("actionBenefits");
                claims.put("sessionId", sessionId);
                claims.set("options", benefits);
                claims.putArray("claimedBy");
                ArrayNode eligible = claims.putArray("actorIds");
                for (Campaign.Actor actor : c.getActors())
                    if (hasPlayerSheet(actor) && !actor.getOwnerId().equals(sourceActor.getOwnerId())) eligible.add(actor.getId());
            }
            commitFeatureSheets(changedSheets, benefitReport == null ? originalRoll : benefitReport, benefitReport != null);
            if (hasRoll) broadcastRollUpdate(originalRoll);
            for (var entry : changedSheets.entrySet()) {
                Campaign.Actor actor = c.actor(entry.getKey());
                if (actor != null) sendFeatureState(actor, entry.getValue());
            }
            if (benefitReport != null) {
                sessionRolls.add(rollKey(benefitReport.path("id").asLong(), benefitReport.path("ts").asLong()));
                deliver(benefitReport, null, false);
            } else if (damageRoll != null) {
                String damageType = definition.path("damage").path("type").asText("physical");
                ObjectNode damage = featureRollMessage(sourceActor, source, actionLabel, damageRoll);
                damage.put("source", "damage");
                damage.putObject("featureRoll").put("name", reportSource).put("result", damageRoll.total())
                        .put("consequence", damageType + " damage. " + definition.path("description").asText(""));
                deliver(damage, null);
            } else if (!definition.has("reroll")) {
                if (reportLines.isEmpty()) reportLines.add("Used " + actionLabel + ".");
                if (hasRoll && definition.has("outcome"))
                    reportLines.add(0, "Changed the original roll to " + ("hope".equals(definition.path("outcome").asText()) ? "Hope" : "Fear") + ".");
                publishActionReport(sourceActor, source, actionReportTitle(sourceSheet, source, actionLabel), reportLines, originalRoll);
            }
            json(ctx, 200, MAPPER.createObjectNode().put("ok", true));
        } catch (FeatureIoException e) {
            json(ctx, 500, errorBody("Could not read the target character sheet."));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            json(ctx, 400, errorBody("Invalid feature request."));
        } catch (IllegalArgumentException e) {
            json(ctx, 400, errorBody(e.getMessage()));
        } catch (IOException e) {
            json(ctx, 500, errorBody("Could not save the feature action."));
        }
    }

    private static final class FeatureIoException extends RuntimeException {
        FeatureIoException(IOException cause) { super(cause); }
    }

    private boolean hasPlayerSheet(Campaign.Actor actor) throws IOException {
        return actor.getOwnerId() != null && campaign.player(actor.getOwnerId()) != null
                && store.loadSheet(campaign.getId(), actor.getId()) != null;
    }

    private JsonNode effectiveFeatureUses(ObjectNode sheet, String key, JsonNode uses) {
        if (!uses.isObject()) return uses;
        ObjectNode result = uses.deepCopy();
        int max = result.path("max").asInt();
        for (JsonNode feature : sheet.path("features")) {
            JsonNode automation = catalogAutomation("feat:" + feature.path("name").asText(),
                    feature.path("name").asText(), feature.path("automation"));
            max = Math.max(max, automation.path("usesOverrides").path(key).asInt());
        }
        result.put("max", max);
        return result;
    }

    private ArrayNode actionBenefits(ObjectNode sheet, String key, JsonNode action) {
        ArrayNode benefits = MAPPER.createArrayNode();
        if (action.path("chatBenefits").isArray()) benefits.addAll((ArrayNode) action.path("chatBenefits"));
        for (JsonNode feature : sheet.path("features")) {
            JsonNode automation = catalogAutomation("feat:" + feature.path("name").asText(),
                    feature.path("name").asText(), feature.path("automation"));
            JsonNode extra = automation.path("actionBenefits").path(key);
            if (extra.isArray()) benefits.addAll((ArrayNode) extra);
        }
        return benefits;
    }

    private void claimActionBenefit(JsonNode request, Campaign.Actor actor) throws IOException {
        ObjectNode sheet = storedSheet(actor.getId());
        JsonNode ref = request.path("roll");
        if (!sessionRolls.contains(rollKey(ref.path("id").asLong(-1), ref.path("ts").asLong(-1))))
            throw new IllegalArgumentException("That performance is not from this hosting session.");
        ObjectNode message = null;
        for (JsonNode entry : store.historyTail(campaign.getId(), Integer.MAX_VALUE))
            if ("chat".equals(entry.path("type").asText()) && entry.path("id").equals(ref.path("id"))
                    && entry.path("ts").equals(ref.path("ts")) && entry instanceof ObjectNode object) {
                message = object.deepCopy();
                break;
            }
        if (message == null || !message.path("actionBenefits").isObject())
            throw new IllegalArgumentException("That message has no claimable benefit.");
        ObjectNode claims = (ObjectNode) message.path("actionBenefits");
        if (!containsText(claims.withArray("actorIds"), actor.getId())
                || actor.getOwnerId().equals(message.path("fromId").asText()))
            throw new IllegalArgumentException("Choose an eligible ally who received this performance.");
        if (containsText(claims.withArray("claimedBy"), actor.getOwnerId()))
            throw new IllegalArgumentException("You already claimed a benefit from this performance.");
        JsonNode benefit = null;
        for (JsonNode option : claims.path("options"))
            if (option.path("id").asText().equals(request.path("benefitId").asText())) { benefit = option; break; }
        if (benefit == null) throw new IllegalArgumentException("That benefit is not available.");
        ObjectNode before = sheet.deepCopy();
        List<String> lines = new ArrayList<>();
        applyFeatureSteps(sheet, benefit.path("steps"), benefit.path("label").asText(), actor, "", lines);
        if (sheet.equals(before)) throw new IllegalArgumentException("No benefit available: already at maximum Hope or nothing to clear.");
        claims.withArray("claimedBy").add(actor.getOwnerId());
        commitFeatureSheets(Map.of(actor.getId(), sheet), message);
        sendFeatureState(actor, sheet);
        broadcastChatUpdate(message);
    }

    private void rerollRestDie(JsonNode request, Campaign.Actor sourceActor, JsonNode source,
                               ObjectNode sourceSheet, String key, JsonNode automation) throws IOException {
        String featureName = source.path("name").asText("Feature");
        JsonNode ref = request.path("roll");
        if (!sessionRolls.contains(rollKey(ref.path("id").asLong(-1), ref.path("ts").asLong(-1))))
            throw new IllegalArgumentException("That rest is not from this hosting session.");
        ObjectNode message = null;
        for (JsonNode entry : store.historyTail(campaign.getId(), Integer.MAX_VALUE)) {
            if ("chat".equals(entry.path("type").asText()) && entry.path("id").equals(ref.path("id"))
                    && entry.path("ts").equals(ref.path("ts")) && entry instanceof ObjectNode object) {
                message = object.deepCopy();
                break;
            }
        }
        if (message == null || !message.path("text").asText().startsWith("SHORT REST\n") || message.has("toId"))
            throw new IllegalArgumentException("Choose a public short-rest report.");
        JsonNode index = request.path("restDie");
        if (!index.isInt() || index.asInt() < 0 || !(message.path("restDice").path(index.asInt()) instanceof ObjectNode die))
            throw new IllegalArgumentException("Choose a downtime die on that report.");
        if (die.path("used").asBoolean()) throw new IllegalArgumentException("That downtime die was already rerolled.");
        Campaign.Actor recipient = campaign.actor(message.path("actorId").asText());
        if (recipient == null) throw new IllegalArgumentException("The resting character no longer exists.");
        useFeatureLimit(sourceSheet, key, automation.path("uses"));
        int previous = die.path("value").asInt();
        int rolled = DiceRoller.roll(die.path("expression").asText(), config.maxDice(), diceRandom).total();
        int kept = Math.max(previous, rolled);
        int improvement = kept - previous;
        String[] lines = message.path("text").asText().split("\n", -1);
        int line = die.path("line").asInt() + 2;
        if (line >= lines.length) throw new IllegalArgumentException("That downtime die is not visible on the report.");
        Map<String, ObjectNode> changed = new java.util.LinkedHashMap<>();
        changed.put(sourceActor.getId(), sourceSheet);
        int applied = improvement;
        if (die.has("resource")) {
            ObjectNode sheet = recipient.equals(sourceActor) ? sourceSheet : storedSheet(recipient.getId());
            ObjectNode track = requireTrack(sheet, die.path("resource").asText());
            int recoverable = "hp".equals(die.path("resource").asText())
                    ? DeathMoves.recoverableHp(sheet) : Math.max(0, track.path("on").asInt());
            applied = Math.min(improvement, Math.min(die.path("recovery").asInt(0), recoverable));
            track.put("on", track.path("on").asInt() - applied);
            die.put("recovery", Math.max(0, die.path("recovery").asInt() - applied));
            int beforeClear = die.path("cleared").asInt();
            lines[line] = lines[line].replaceFirst("(clears|cleared) " + beforeClear + "\\b", "$1 " + (beforeClear + applied));
            die.put("cleared", beforeClear + applied);
            changed.put(recipient.getId(), sheet);
        } else if (improvement > 0) {
            var clears = java.util.regex.Pattern.compile("clears (\\d+)").matcher(lines[line]);
            if (clears.find()) lines[line] = clears.replaceFirst("clears " + (Integer.parseInt(clears.group(1)) + improvement));
        }
        lines[line] = lines[line].replaceFirst(java.util.regex.Pattern.quote(die.path("expression").asText() + " = " + previous) + "(?![\\d/])",
                java.util.regex.Matcher.quoteReplacement(die.path("expression").asText() + " = " + previous + "/" + rolled + ", kept " + kept));
        lines[line] += " (" + featureName + ": " + sourceActor.getName() + ")";
        die.put("value", kept).put("used", true);
        message.put("text", String.join("\n", lines));
        commitFeatureSheets(changed, message);
        changed.forEach((id, sheet) -> sendFeatureState(campaign.actor(id), sheet));
        broadcastChatUpdate(message);
        List<String> report = new ArrayList<>();
        report.add("Rerolled " + recipient.getName() + "'s downtime die (" + die.path("expression").asText()
                + "): " + previous + " → " + rolled + "; kept " + kept + ".");
        if (die.has("resource")) {
            String resource = switch (die.path("resource").asText()) {
                case "hp" -> "HP";
                case "armor" -> "Armor";
                default -> "Stress";
            };
            report.add(applied > 0 ? recipient.getName() + " cleared " + applied + " additional " + resource + "."
                    : "No additional " + resource + " cleared.");
        } else if (improvement == 0) {
            report.add("No improvement; kept the original result.");
        }
        publishActionReport(sourceActor, source, actionReportTitle(sourceSheet, source, "Reroll downtime die"), report, null);
    }

    private void broadcastChatUpdate(ObjectNode message) {
        ObjectNode update = MAPPER.createObjectNode().put("type", "chatUpdate");
        update.set("message", message);
        broadcast(update.toString());
        host.dmMessage(update);
    }

    private JsonNode featureSource(ObjectNode sheet, String key) {
        int separator = key.indexOf(':');
        if (separator <= 0) throw new IllegalArgumentException("Invalid feature source.");
        String kind = key.substring(0, separator);
        String name = key.substring(separator + 1);
        List<String> fields = switch (kind) {
            case "feat" -> List.of("features", "heritage");
            case "card" -> List.of("loadout", "vault");
            case "weapon" -> List.of("activeWeapons");
            case "armor" -> List.of("activeArmor");
            case "item" -> List.of("inventory");
            default -> List.of();
        };
        for (String field : fields) {
            JsonNode values = sheet.path(field);
            if (values.isArray()) {
                for (JsonNode value : values) {
                    if (name.equals(value.path("name").asText())) return value;
                }
            } else if (values.isObject() && name.equals(values.path("name").asText())) {
                return values;
            }
        }
        throw new IllegalArgumentException("Feature not found on the source character.");
    }

    private JsonNode featureDefinition(JsonNode automation, String actionId, boolean trigger) {
        JsonNode list = automation.path(trigger ? "rollTriggers" : "actions");
        if (!list.isArray()) return null;
        for (JsonNode item : list) if (actionId.equals(item.path("id").asText())) return item;
        return null;
    }

    private JsonNode catalogAutomation(String key, String name, JsonNode fallback) {
        java.nio.file.Path directory = host.compendiumDirectory();
        if (directory == null || name.isBlank()) return fallback;
        try (var files = java.nio.file.Files.list(directory)) {
            List<java.nio.file.Path> ordered = files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
            String preferred = key.startsWith("card:") ? "domain-cards.json"
                    : key.startsWith("item:") ? "items.json"
                    : key.startsWith("weapon:") ? "weapons.json"
                    : key.startsWith("armor:") ? "armor.json" : "";
            if (!preferred.isEmpty()) ordered = java.util.stream.Stream.concat(
                    ordered.stream().filter(p -> p.getFileName().toString().equals(preferred)),
                    ordered.stream().filter(p -> !p.getFileName().toString().equals(preferred))).toList();
            for (java.nio.file.Path file : ordered) {
                JsonNode root = MAPPER.readTree(file.toFile());
                JsonNode found = findCatalogFeature(root, name);
                if (found != null) return found;
            }
        } catch (IOException | RuntimeException ignored) {
            return fallback;
        }
        return fallback;
    }

    private JsonNode findCatalogFeature(JsonNode node, String name) {
        if (node.isObject()) {
            if (name.equals(node.path("name").asText()) && node.path("automation").isObject())
                return node.path("automation");
            var fields = node.fields();
            while (fields.hasNext()) {
                JsonNode found = findCatalogFeature(fields.next().getValue(), name);
                if (found != null) return found;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                JsonNode found = findCatalogFeature(child, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private ObjectNode findSessionRoll(JsonNode ref) throws IOException {
        long id = ref.path("id").asLong(-1);
        long ts = ref.path("ts").asLong(-1);
        if (id < 0 || ts < 0 || !sessionRolls.contains(rollKey(id, ts)))
            throw new IllegalArgumentException("That roll is no longer available for a feature reaction.");
        return findHistoryRoll(ref);
    }

    private ObjectNode findHistoryRoll(JsonNode ref) throws IOException {
        long id = ref.path("id").asLong(-1);
        long ts = ref.path("ts").asLong(-1);
        Campaign c = campaign;
        if (c == null) throw new IllegalArgumentException("No online session is running.");
        for (JsonNode entry : store.historyTail(c.getId(), Integer.MAX_VALUE)) {
            if ("roll".equals(entry.path("type").asText()) && entry.path("id").asLong(-1) == id
                    && entry.path("ts").asLong(-1) == ts && entry instanceof ObjectNode object) return object.deepCopy();
        }
        throw new IllegalArgumentException("That roll is no longer available for a feature reaction.");
    }

    private synchronized void handleRollReward(Context ctx) {
        String playerId = requirePlayer(ctx);
        if (playerId == null) return;
        if (ctx.bodyAsBytes().length > config.maxMessageBytes()) {
            json(ctx, 413, errorBody("Roll reward request is too large."));
            return;
        }
        try {
            JsonNode request = MAPPER.readTree(ctx.body());
            if (request == null || !request.isObject() || !request.path("roll").isObject())
                throw new IllegalArgumentException("Invalid roll reward request.");
            // Recover any interrupted reward transaction before looking up its claim marker.
            Campaign.Actor actor = ownedActor(playerId, request.path("actorId").asText());
            ObjectNode sheet = storedSheet(actor.getId());
            ObjectNode roll = findHistoryRoll(request.path("roll"));
            if (!playerId.equals(roll.path("fromId").asText()) || !actor.getId().equals(roll.path("actorId").asText()))
                throw new IllegalArgumentException("Only the original roller can claim this character's reward.");
            if (!"duality".equals(roll.path("kind").asText()) || !roll.path("rewardClaimed").isBoolean())
                throw new IllegalArgumentException("This roll does not support reward claims.");
            if (List.of(roll.path("source").asText("").split(",")).contains("reaction"))
                throw new IllegalArgumentException("Reaction rolls do not grant Hope or clear Stress.");
            if (roll.path("rewardClaimed").asBoolean())
                throw new IllegalArgumentException("This roll's reward was already claimed.");
            int hope = roll.path("rolls").path(0).asInt(0);
            int fear = roll.path("rolls").path(1).asInt(0);
            String expression = roll.path("expression").asText();
            int hopeSides = expression.startsWith("1d20+") ? 20 : 12;
            int fearSides = expression.matches("^1d(?:12|20)\\+1d20.*") ? 20 : 12;
            if (hope < 1 || hope > hopeSides || fear < 1 || fear > fearSides)
                throw new IllegalArgumentException("This roll has no valid Hope and Fear dice.");
            boolean critical = hope == fear;
            boolean withHope = "hope".equals(roll.path("outcome").asText(hope > fear ? "hope" : "fear"));
            if (!critical && !withHope)
                throw new IllegalArgumentException("Only Hope or critical results have a reward.");
            int gained = gainTrack(sheet, "hope", 1);
            int cleared = 0;
            if (critical) {
                ObjectNode stress = requireTrack(sheet, "stress");
                cleared = Math.min(1, Math.max(0, stress.path("on").asInt()));
                stress.put("on", stress.path("on").asInt() - cleared);
            }
            if (gained <= 0 && cleared == 0)
                throw new IllegalArgumentException("Already at maximum Hope with no Stress to clear.");
            roll.put("rewardClaimed", true);
            sheet.put("featureRevision", sheet.path("featureRevision").asLong(0) + 1);
            if (sheet.toString().getBytes(StandardCharsets.UTF_8).length > MAX_SHEET_BYTES)
                throw new IllegalArgumentException("The character sheet is too large.");
            store.saveSheetsAndRoll(campaign.getId(), Map.of(actor.getId(), sheet), roll);
            sendFeatureState(actor, sheet);
            broadcastRollUpdate(roll);
            json(ctx, 200, MAPPER.createObjectNode().put("ok", true));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            json(ctx, 400, errorBody("Invalid roll reward request."));
        } catch (IllegalArgumentException e) {
            json(ctx, 400, errorBody(e.getMessage()));
        } catch (IOException e) {
            json(ctx, 500, errorBody("Could not save the roll reward. Reload before retrying."));
        }
    }

    private void validateTrigger(Campaign.Actor sourceActor, String key, JsonNode trigger, ObjectNode roll) throws IOException {
        String rolledActorId = roll.path("actorId").asText("");
        Campaign.Actor rolledActor = campaign.actor(rolledActorId);
        if (rolledActor == null) throw new IllegalArgumentException("This roll is not linked to a character.");
        String audience = trigger.path("audience").asText("self");
        if ("self".equals(audience) && !sourceActor.getId().equals(rolledActorId))
            throw new IllegalArgumentException("This feature only applies to your own character's roll.");
        if ("ally".equals(audience) && sourceActor.getId().equals(rolledActorId))
            throw new IllegalArgumentException("This feature only applies to another character's roll.");
        String source = roll.path("source").asText("");
        String on = trigger.path("on").asText("");
        for (JsonNode excluded : trigger.path("excludeRolls")) {
            if (List.of(source.split(",")).contains(excluded.asText()))
                throw new IllegalArgumentException("This feature does not apply to that roll.");
        }
        boolean duality = "duality".equals(roll.path("kind").asText());
        if ("duality".equals(on) && !duality)
            throw new IllegalArgumentException("This feature requires a Duality roll.");
        if (!"duality".equals(on) && !List.of(source.split(",")).contains(on))
            throw new IllegalArgumentException("This feature does not apply to that roll.");
        if (trigger.path("weaponAttack").asBoolean()
                && (!List.of(source.split(",")).contains("attack") || roll.path("weapon").asText("").isBlank()))
            throw new IllegalArgumentException("This feature requires a weapon attack.");
        if (trigger.has("weapon") && !trigger.path("weapon").asText().equals(roll.path("weapon").asText()))
            throw new IllegalArgumentException("This feature requires a " + trigger.path("weapon").asText() + " attack.");
        ArrayNode uses = roll.withArray("featureUses");
        String usageKey = sourceActor.getId() + ":" + key + ":" + trigger.path("id").asText();
        if (containsText(uses, usageKey)) throw new IllegalArgumentException("This feature was already used on that roll.");
        if (!duality) {
            if (!"damage".equals(on) || !"always".equals(trigger.path("when").asText("always"))
                    || trigger.has("outcome") || trigger.has("reroll"))
                throw new IllegalArgumentException("This feature requires a Duality roll.");
            return;
        }
        int hope = roll.path("rolls").path(0).asInt(Integer.MIN_VALUE);
        int fear = roll.path("rolls").path(1).asInt(Integer.MIN_VALUE);
        if (hope < 1 || fear < 1) throw new IllegalArgumentException("The original roll has no valid Hope and Fear dice.");
        String actualOutcome = roll.path("outcome").asText("");
        if (!"hope".equals(actualOutcome) && !"fear".equals(actualOutcome))
            actualOutcome = hope == fear ? "crit" : (fear > hope ? "fear" : "hope");
        String when = trigger.path("when").asText("always");
        if ("hope".equals(when) && !"hope".equals(actualOutcome)
                || "hopeOrCritical".equals(when) && !"hope".equals(actualOutcome) && !"crit".equals(actualOutcome)
                || "fear".equals(when) && !"fear".equals(actualOutcome)
                || ("hope".equals(actualOutcome) || "fear".equals(actualOutcome)) && "crit".equals(actualOutcome))
            throw new IllegalArgumentException("The original roll no longer meets this feature's condition.");
        if (trigger.has("outcome") && ("crit".equals(actualOutcome)
                || trigger.path("outcome").asText().equals(actualOutcome)))
            throw new IllegalArgumentException("The original roll cannot be changed to that outcome.");
    }

    private List<Campaign.Actor> resolveFeatureTargets(Campaign c, Campaign.Actor source, String targetActorId, String mode) {
        if ("allActors".equals(mode)) {
            List<Campaign.Actor> targets = new ArrayList<>();
            for (Campaign.Actor actor : c.getActors()) {
                try { if (hasPlayerSheet(actor)) targets.add(actor); }
                catch (IOException e) { throw new FeatureIoException(e); }
            }
            return targets;
        }
        if ("party".equals(mode)) {
            Set<String> online = new LinkedHashSet<>();
            clients.values().forEach(client -> online.add(client.playerId));
            return c.getActors().stream().filter(a -> a.getOwnerId() != null && online.contains(a.getOwnerId())).toList();
        }
        if ("actor".equals(mode)) {
            Campaign.Actor target = c.actor(targetActorId);
            if (target == null || target.getOwnerId() == null || campaign.player(target.getOwnerId()) == null
                    || clients.values().stream().noneMatch(client -> target.getOwnerId().equals(client.playerId)))
                throw new IllegalArgumentException("Choose a connected player's character as the target.");
            return List.of(target);
        }
        if (!targetActorId.isEmpty()) throw new IllegalArgumentException("This action does not accept a target.");
        return List.of(source);
    }

    private void applyFeatureCost(ObjectNode sheet, JsonNode costs) {
        if (!costs.isObject()) return;
        var fields = costs.fields();
        while (fields.hasNext()) {
            var cost = fields.next();
            int amount = featureAmount(cost.getValue(), sheet);
            if (amount < 0) throw new IllegalArgumentException("Invalid feature cost.");
            String resource = cost.getKey();
            if (isTrack(resource)) {
                ObjectNode track = requireTrack(sheet, resource);
                int on = track.path("on").asInt();
                if ("hope".equals(resource) ? on < amount : on + amount > track.path("slots").asInt())
                    throw new IllegalArgumentException("Not enough " + resource.toUpperCase(java.util.Locale.ROOT) + ".");
                track.put("on", "hope".equals(resource) ? on - amount : on + amount);
            } else {
                ObjectNode resources = sheet.withObject("/resources");
                if (resources.path(resource).asInt(-1) < amount)
                    throw new IllegalArgumentException("Not enough " + resource + ".");
                resources.put(resource, resources.path(resource).asInt() - amount);
            }
        }
    }

    private void useFeatureLimit(ObjectNode sheet, String key, JsonNode uses) {
        if (!uses.isObject() || uses.path("per").asText().equals("scene")) return;
        int max = uses.path("max").asInt(0);
        if (max <= 0) return;
        ObjectNode counters = sheet.withObject("/usage");
        ObjectNode counter = counters.with(key);
        int used = counter.path("used").asInt(0);
        if (used >= max) throw new IllegalArgumentException("This feature has no uses remaining.");
        counter.put("used", used + 1);
        counter.put("per", uses.path("per").asText("session"));
    }

    private void applyFeatureSteps(ObjectNode sheet, JsonNode steps, String label, Campaign.Actor sourceActor,
                                   String sourceKey, List<String> report) {
        for (JsonNode step : steps) {
            String operation = step.path("op").asText("");
            String resource = step.path("resource").asText("");
            if (("gain".equals(operation) || "mark".equals(operation) || "clear".equals(operation)) && isTrack(resource)) {
                ObjectNode track = requireTrack(sheet, resource);
                int before = track.path("on").asInt();
                int amount = featureAmount(step.path("amount"), sheet);
                if ("all".equals(step.path("amount").asText())) amount = before;
                if ("clear".equals(operation)) track.put("on", Math.max("hp".equals(resource) ? sheet.path("permanentHp").asInt() : 0,
                        before - Math.max(0, amount)));
                else track.put("on", Math.min(track.path("slots").asInt(), before + Math.max(0, amount)));
                int changed = Math.abs(before - track.path("on").asInt());
                if (changed > 0) report.add(resource.equals("hp") ? "Cleared " + changed + " HP."
                        : (("gain".equals(operation) ? "Gained " : "Marked ") + changed + " " + resource + "."));
                else if ("gain".equals(operation) && amount > 0)
                    report.add("Gained no " + resource + " (already at maximum).");
            } else if (List.of("gain", "clear", "mark").contains(operation) && !resource.isBlank()) {
                JsonNode definition = findResourceDefinition(sheet, resource);
                if (definition == null) throw new IllegalArgumentException("Resource unavailable: " + resource);
                ObjectNode resources = sheet.withObject("/resources");
                int before = resources.path(resource).asInt(0);
                int max = definition.has("max") ? featureAmount(definition.get("max"), sheet) : Integer.MAX_VALUE;
                int amount = "all".equals(step.path("amount").asText()) ? before : featureAmount(step.path("amount"), sheet);
                if (amount < 0) throw new IllegalArgumentException("Invalid resource amount.");
                int after = "gain".equals(operation) ? (int) Math.min(max, (long) before + amount) : Math.max(0, before - amount);
                resources.put(resource, after);
                report.add(("gain".equals(operation) ? "Gained " : "Spent ") + Math.abs(after - before) + " " + definition.path("label").asText(resource) + ".");
            } else if ("effect".equals(operation) && step.path("effect").isObject()) {
                ObjectNode effect = (ObjectNode) step.path("effect").deepCopy();
                String name = effect.path("name").asText();
                if (name.isBlank()) continue;
                removeEffects(sheet, name, effect.path("unique").asBoolean() ? null : sourceActor.getId(),
                        effect.path("unique").asBoolean() ? null : sourceKey);
                if (effect.path("die").isObject()) {
                    ObjectNode caster;
                    try { caster = storedSheet(sourceActor.getId()); }
                    catch (IOException e) { throw new FeatureIoException(e); }
                    String dice = effect.path("die").path("dice").asText();
                    int level = caster.path("level").asInt(1);
                    int best = 0;
                    var levels = effect.path("die").path("diceByLevel").fields();
                    while (levels.hasNext()) {
                        var entry = levels.next();
                        int threshold = Integer.parseInt(entry.getKey());
                        if (threshold <= level && threshold > best) { best = threshold; dice = entry.getValue().asText(); }
                    }
                    var upgrades = effect.path("die").path("diceByFeature").fields();
                    while (upgrades.hasNext()) {
                        var upgrade = upgrades.next();
                        for (JsonNode feature : caster.path("features"))
                            if (upgrade.getKey().equals(feature.path("name").asText())) dice = upgrade.getValue().asText();
                    }
                    resolveEffectDie(effect, dice);
                }
                effect.put("id", "f" + java.util.UUID.randomUUID());
                effect.put("source", label);
                effect.put("sourceActorId", sourceActor.getId());
                effect.put("sourceKey", sourceKey);
                sheet.withArray("effects").add(effect);
                report.add("Gained " + name + ".");
            }

        }
    }

    private void resolveEffectDie(JsonNode node, String dice) {
        if (node instanceof ObjectNode object) {
            List<String> replacements = new ArrayList<>();
            object.fields().forEachRemaining(entry -> {
                if (entry.getValue().isTextual() && "@die".equals(entry.getValue().asText())) replacements.add(entry.getKey());
                else resolveEffectDie(entry.getValue(), dice);
            });
            replacements.forEach(key -> object.put(key, dice));
        } else if (node.isArray()) node.forEach(child -> resolveEffectDie(child, dice));
    }

    private static boolean isTrack(String resource) {
        return List.of("hope", "stress", "hp", "armor").contains(resource);
    }

    private static JsonNode findResourceDefinition(JsonNode node, String id) {
        if (node.isObject()) {
            for (JsonNode resource : node.path("resources"))
                if (id.equals(resource.path("id").asText())) return resource;
        }
        if (node.isContainerNode()) {
            for (JsonNode child : node) {
                JsonNode found = findResourceDefinition(child, id);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static ObjectNode requireTrack(ObjectNode sheet, String resource) {
        String field = "armor".equals(resource) ? "armorTrack" : resource;
        JsonNode value = sheet.path(field);
        if (!(value instanceof ObjectNode track)) throw new IllegalArgumentException("Resource unavailable: " + resource);
        return track;
    }

    private int featureAmount(JsonNode amount, ObjectNode sheet) {
        if (amount.isIntegralNumber() && amount.canConvertToInt()) return amount.asInt();
        if (amount.isTextual()) {
            String text = amount.asText().strip();
            if ("all".equals(text)) return 0;
            if ("@level".equals(text)) return Math.max(1, sheet.path("level").asInt(1));
            if ("@tier".equals(text)) {
                int level = Math.max(1, sheet.path("level").asInt(1));
                return level == 1 ? 1 : level <= 4 ? 2 : level <= 7 ? 3 : 4;
            }
            try { return Integer.parseInt(text); }
            catch (NumberFormatException ignored) { }
        }
        throw new IllegalArgumentException("This feature uses a value the host cannot apply.");
    }

    private int gainTrack(ObjectNode sheet, String resource, int amount) {
        ObjectNode track = requireTrack(sheet, resource);
        int before = track.path("on").asInt();
        track.put("on", Math.min(track.path("slots").asInt(), before + amount));
        return track.path("on").asInt() - before;
    }

    private void markTrack(ObjectNode sheet, String resource, int amount) {
        ObjectNode track = requireTrack(sheet, resource);
        track.put("on", Math.min(track.path("slots").asInt(), track.path("on").asInt() + amount));
    }

    private boolean removeEffects(ObjectNode sheet, String name, String sourceActorId, String sourceKey) {
        JsonNode effects = sheet.path("effects");
        if (!effects.isArray()) return false;
        ArrayNode kept = MAPPER.createArrayNode();
        boolean removed = false;
        for (JsonNode effect : effects) {
            boolean match = (name == null || name.equals(effect.path("name").asText()))
                    && (sourceActorId == null || sourceActorId.equals(effect.path("sourceActorId").asText()))
                    && (sourceKey == null || sourceKey.equals(effect.path("sourceKey").asText()));
            if (match) removed = true;
            else kept.add(effect);
        }
        if (removed) sheet.set("effects", kept);
        return removed;
    }

    private boolean removeSevereEffectsFromSource(ObjectNode sheet, String sourceActorId, String sourceKey) {
        JsonNode effects = sheet.path("effects");
        if (!effects.isArray()) return false;
        ArrayNode kept = MAPPER.createArrayNode();
        boolean removed = false;
        for (JsonNode effect : effects) {
            boolean match = effect.path("endOnSourceSevere").asBoolean()
                    && sourceActorId.equals(effect.path("sourceActorId").asText())
                    && sourceKey.equals(effect.path("sourceKey").asText());
            if (match) removed = true;
            else kept.add(effect);
        }
        if (removed) sheet.set("effects", kept);
        return removed;
    }

    private boolean removeLifeWards(Map<String, ObjectNode> changes, String casterId) throws IOException {
        boolean anyRemoved = false;
        for (Campaign.Actor actor : campaign.getActors()) {
            ObjectNode sheet = changes.get(actor.getId());
            boolean loaded = sheet != null;
            if (!loaded) sheet = storedSheet(actor.getId());
            if (!(sheet.path("effects") instanceof ArrayNode effects)) continue;
            boolean removed = false;
            for (int i = effects.size() - 1; i >= 0; i--) {
                JsonNode effect = effects.get(i);
                if (effect.path("lifeWard").asBoolean() && casterId.equals(effect.path("sourceActorId").asText())) {
                    effects.remove(i);
                    removed = true;
                    anyRemoved = true;
                }
            }
            if (removed || loaded) changes.put(actor.getId(), sheet);
        }
        return anyRemoved;
    }

    private static boolean hasSpectral(JsonNode sheet) {
        for (JsonNode effect : sheet.path("effects")) if (effect.has("spectralHp")) return true;
        return false;
    }

    private List<String> normalizeDeathChanges(Map<String, ObjectNode> changes) throws IOException {
        List<String> reports = new ArrayList<>();
        for (String id : List.copyOf(changes.keySet())) {
            ObjectNode sheet = changes.get(id);
            ObjectNode previous = storedSheet(id);
            if (sheet.path("longRestRevision").asLong() > previous.path("longRestRevision").asLong()) {
                if (removeLifeWards(changes, id))
                    reports.add(campaign.actor(id).getName() + ": long rest ended all Life Wards cast by this character.");
            }
        }
        for (var entry : changes.entrySet()) {
            ObjectNode sheet = entry.getValue();
            ObjectNode previous = storedSheet(entry.getKey());
            String name = campaign.actor(entry.getKey()).getName();
            boolean spectral = hasSpectral(previous);
            int slots = sheet.path("hp").path("slots").asInt();
            int on = sheet.path("hp").path("on").asInt();
            if (spectral && slots > 0 && on >= slots) {
                sheet.put("spectralBlocked", true);
                if (sheet.path("effects") instanceof ArrayNode effects) {
                    for (int i = effects.size() - 1; i >= 0; i--) if (effects.get(i).has("spectralHp")) effects.remove(i);
                }
            }
            if (slots > 0 && on >= slots && sheet.path("effects") instanceof ArrayNode effects) {
                for (int i = 0; i < effects.size(); i++) {
                    JsonNode effect = effects.get(i);
                    if (!effect.path("lifeWard").asBoolean()) continue;
                    Campaign.Actor caster = campaign.actor(effect.path("sourceActorId").asText());
                    effects.remove(i);
                    int cleared = DeathMoves.clearHp(sheet, 1);
                    reports.add(name + ": Life Ward from " + (caster == null ? effect.path("source").asText("an ally") : caster.getName())
                            + " consumed; cleared " + cleared + " HP" + (cleared > 0 ? " instead of a death move." : "; all HP remain permanently marked."));
                    break;
                }
            }
            DeathMoves.sync(sheet);
            if (spectral && !hasSpectral(sheet))
                reports.add(name + ": Spectral Form ended. If inside a solid object, the GM must reposition the token to the nearest open space.");
        }
        return reports;
    }

    private void commitFeatureSheets(Map<String, ObjectNode> changedSheets) throws IOException {
        commitFeatureSheets(changedSheets, null);
    }

    private void commitFeatureSheets(Map<String, ObjectNode> changedSheets, ObjectNode roll) throws IOException {
        commitFeatureSheets(changedSheets, roll, false);
    }

    private void commitFeatureSheets(Map<String, ObjectNode> changedSheets, ObjectNode message, boolean newMessage) throws IOException {
        Map<String, ObjectNode> changes = new java.util.LinkedHashMap<>(changedSheets);
        List<String> reports = normalizeDeathChanges(changes);
        ObjectNode report = reports.isEmpty() ? null : deathStateMessage("dm", "DM", "Party", reports);
        persistFeatureSheets(changes, message, newMessage, report);
        for (var entry : changes.entrySet())
            if (!changedSheets.containsKey(entry.getKey())) sendFeatureState(campaign.actor(entry.getKey()), entry.getValue());
        if (report != null) deliver(report, null, false);
    }

    private ObjectNode deathStateMessage(String fromId, String fromName, String actorName, List<String> reports) {
        return chatMessage(fromId, fromName, null, "ACTION USED\nDeath move - Life Ward / Spectral Form\n" + actorName
                + reports.stream().map(line -> "\n• " + line).reduce("", String::concat));
    }

    private void persistFeatureSheets(Map<String, ObjectNode> changedSheets, ObjectNode message, boolean newMessage) throws IOException {
        persistFeatureSheets(changedSheets, message, newMessage, null);
    }

    private void persistFeatureSheets(Map<String, ObjectNode> changedSheets, ObjectNode message, boolean newMessage,
                                      ObjectNode report) throws IOException {
        List<String> stockChanges = new ArrayList<>();
        for (var entry : changedSheets.entrySet())
            if (entry.getValue().path("inventoryRevision").asLong() != storedSheet(entry.getKey()).path("inventoryRevision").asLong())
                stockChanges.add(entry.getKey());
        for (ObjectNode sheet : changedSheets.values()) {
            DeathMoves.sync(sheet);
            sheet.put("featureRevision", sheet.path("featureRevision").asLong(0) + 1);
            if (sheet.toString().getBytes(StandardCharsets.UTF_8).length > MAX_SHEET_BYTES)
                throw new IllegalArgumentException("The character sheet is too large.");
        }
        List<ObjectNode> newMessages = new ArrayList<>();
        if (newMessage && message != null) newMessages.add(message);
        if (report != null) newMessages.add(report);
        store.saveSheetsAndResults(campaign.getId(), changedSheets, newMessage ? null : message, newMessages);
        stockChanges.forEach(this::stockChanged);
    }

    private void sendFeatureState(Campaign.Actor actor, ObjectNode sheet) {
        ObjectNode message = MAPPER.createObjectNode().put("type", "sheetFeature").put("actorId", actor.getId())
                .put("featureRevision", sheet.path("featureRevision").asLong(0));
        for (String field : List.of("hp", "stress", "hope", "scars", "armorTrack", "effects", "usage", "resources",
                "permanentHp", "spectralBlocked", "deathNarrative", "longRestRevision",
                "inventoryRevision", "compendiumRevision", "inventory", "loadout", "vault")) {
            if (sheet.has(field)) message.set(field, sheet.get(field));
        }
        message.set("pendingDeathRoll", sheet.has("pendingDeathRoll") ? sheet.get("pendingDeathRoll") : MAPPER.nullNode());
        for (Client client : clients.values())
            if (actor.getOwnerId() != null && actor.getOwnerId().equals(client.playerId)) sendTo(client.ctx, message.toString());
    }

    private static String actionReportTitle(JsonNode sheet, JsonNode source, String action) {
        String provenance = source.path("source").asText("");
        String origin;
        if ("Hope feature".equals(provenance)) {
            origin = sheet.path("className").asText(provenance);
        } else {
            var multiclass = java.util.regex.Pattern.compile(
                    "^Multiclass (.+?) - (?:class feature|(.+) (?:foundation|specialization|mastery))$").matcher(provenance);
            origin = multiclass.matches() ? (multiclass.group(2) == null ? multiclass.group(1) : multiclass.group(2))
                    : provenance.replaceFirst("^(?:Ancestry|Community|Transformation|Class feature|Creation|Subclass (?:foundation|specialization|mastery)) - ", "");
            if (provenance.startsWith("Ancestry - ")) origin = origin.replaceFirst(" \\((?:first|second)\\)$", "");
        }
        List<String> parts = new ArrayList<>();
        for (String part : List.of(origin, source.path("name").asText("Feature"), action)) {
            String name = part.strip();
            if (name.isEmpty() || (!parts.isEmpty() && parts.getLast().equals(name))) continue;
            if (!parts.isEmpty() && List.of(" (", ":", " - ").stream().anyMatch(suffix -> name.startsWith(parts.getLast() + suffix)))
                parts.set(parts.size() - 1, name);
            else parts.add(name);
        }
        return String.join(" - ", parts);
    }

    private void publishActionReport(Campaign.Actor actor, JsonNode source, String title, List<String> lines, ObjectNode roll) {
        ObjectNode message = actionReportMessage(actor, source, title, lines, roll);
        deliver(message, message.path("toId").asText(null));
    }

    private ObjectNode actionReportMessage(Campaign.Actor actor, JsonNode source, String title, List<String> lines, ObjectNode roll) {
        String text = "ACTION USED\n" + title + "\n" + actor.getName()
                + lines.stream().map(line -> "\n• " + line).reduce("", String::concat);
        String toId = roll == null ? null : roll.path("toId").asText(null);
        ObjectNode message = chatMessage(actor.getOwnerId(), campaign.player(actor.getOwnerId()).getName(), toId, clip(text));
        message.set("rollSource", featureSourceDescription(source));
        return message;
    }

    private ObjectNode featureSourceDescription(JsonNode source) {
        ObjectNode description = MAPPER.createObjectNode();
        for (String field : new String[]{"name", "text", "source"}) {
            String value = source.path(field).asText("source".equals(field) ? "Feature" : "");
            int limit = "text".equals(field) ? 16_000 : 240;
            description.put(field, value.substring(0, Math.min(value.length(), limit)));
        }
        return description;
    }

    private void publishFeatureCheck(Campaign.Actor actor, JsonNode source, String label, DiceRoller.Result result,
                                     String consequence) {
        ObjectNode out = featureRollMessage(actor, source, label, result);
        out.putObject("featureRoll").put("name", source.path("name").asText()).put("result", result.total()).put("consequence", consequence);
        deliver(out, null);
    }

    private ObjectNode featureRollMessage(Campaign.Actor actor, JsonNode source, String label, DiceRoller.Result result) {
        String sourceName = source.path("name").asText();
        ObjectNode out = MAPPER.createObjectNode().put("id", nextChatId()).put("type", "roll")
                .put("ts", System.currentTimeMillis()).put("fromId", actor.getOwnerId())
                .put("from", campaign.player(actor.getOwnerId()).getName()).put("expression", result.expression())
                .put("total", result.total()).put("label", label).put("kind", "dice").put("actorId", actor.getId());
        ArrayNode rolls = out.putArray("rolls");
        result.rolls().forEach(rolls::add);
        out.set("rollSource", featureSourceDescription(source));
        ArrayNode breakdown = out.putArray("breakdown");
        ObjectNode detail = breakdown.addObject().put("label", sourceName).put("expression", result.expression()).put("total", result.total());
        detail.set("rolls", rolls.deepCopy());
        return out;
    }

    private long nextChatId() {
        synchronized (this) { return ++chatId; }
    }

    private void broadcastRollUpdate(ObjectNode roll) {
        ObjectNode update = MAPPER.createObjectNode().put("type", "rollUpdate").put("id", roll.path("id").asLong())
                .put("ts", roll.path("ts").asLong());
        if (roll.has("outcome")) update.set("outcome", roll.get("outcome"));
        update.set("featureUses", roll.withArray("featureUses"));
        if (roll.has("rewardClaimed")) update.set("rewardClaimed", roll.get("rewardClaimed"));
        if (roll.has("deathResult")) update.set("deathResult", roll.get("deathResult"));
        if (roll.has("deathMove") && roll.has("featureRoll")) update.set("featureRoll", roll.get("featureRoll"));
        update.set("total", roll.path("total"));
        update.set("expression", roll.path("expression"));
        update.set("rolls", roll.path("rolls"));
        if (roll.has("breakdown")) update.set("breakdown", roll.get("breakdown"));
        String toId = roll.path("toId").asText("");
        String fromId = roll.path("fromId").asText("");
        if (toId.isEmpty()) {
            broadcast(update.toString());
        } else {
            String json = update.toString();
            for (Client client : clients.values())
                if (client.playerId.equals(toId) || client.playerId.equals(fromId)) sendTo(client.ctx, json);
        }
        if (toId.isEmpty() || "dm".equals(toId) || "dm".equals(fromId)) host.dmMessage(update);
    }

    private static boolean containsText(ArrayNode list, String value) {
        for (JsonNode entry : list) if (value.equals(entry.asText())) return true;
        return false;
    }

    private static String rollKey(long id, long ts) {
        return id + ":" + ts;
    }

    private void resetSessionUses() {
        Campaign c = campaign;
        if (c == null) return;
        for (Campaign.Actor actor : c.getActors()) {
            try {
                ObjectNode sheet = storedSheet(actor.getId());
                ObjectNode usage = sheet.withObject("/usage");
                List<String> stale = new ArrayList<>();
                usage.fields().forEachRemaining(entry -> {
                    if (List.of("session", "scene").contains(entry.getValue().path("per").asText())) stale.add(entry.getKey());
                });
                ArrayNode effects = MAPPER.createArrayNode();
                boolean expired = false;
                for (JsonNode effect : sheet.path("effects")) {
                    if ("session".equals(effect.path("until").asText())) expired = true;
                    else effects.add(effect);
                }
                if (stale.isEmpty() && !expired) continue;
                if (expired) sheet.set("effects", effects);
                stale.forEach(usage::remove);
                sheet.put("featureRevision", sheet.path("featureRevision").asLong(0) + 1);
                store.saveSheet(c.getId(), actor.getId(), sheet.toString());
            } catch (IOException e) {
                throw new IllegalStateException("Could not reset session uses and effects for " + actor.getName() + ".", e);
            }

        }
    }

    private Set<String> sessionResourceIds() {
        Set<String> ids = new LinkedHashSet<>();
        java.nio.file.Path directory = host.compendiumDirectory();
        if (directory == null || !java.nio.file.Files.isDirectory(directory)) return ids;
        try (var files = java.nio.file.Files.list(directory)) {
            for (var file : files.filter(p -> p.getFileName().toString().endsWith(".json")).toList())
                collectSessionResources(MAPPER.readTree(file.toFile()), ids);
        } catch (IOException e) {
            throw new IllegalStateException("Could not load session-only compendium resources.", e);
        }
        return ids;
    }

    private static void collectSessionResources(JsonNode node, Set<String> ids) {
        if (node.isObject()) {
            for (JsonNode resource : node.path("resources"))
                if (resource.path("session").asBoolean() && !resource.path("id").asText().isBlank())
                    ids.add(resource.path("id").asText());
            node.elements().forEachRemaining(child -> collectSessionResources(child, ids));
        } else if (node.isArray()) node.forEach(child -> collectSessionResources(child, ids));
    }

    private synchronized void handleDamageTaken(Context ctx) {
        String playerId = requirePlayer(ctx);
        if (playerId == null) return;
        try {
            JsonNode request = MAPPER.readTree(ctx.body());
            if (request == null || !request.isObject())
                throw new IllegalArgumentException("Invalid damage notification.");
            Campaign.Actor actor = ownedActor(playerId, request.path("actorId").asText());
            int severity = request.path("severity").asInt(0);
            if (severity > 4 || severity < 0)
                throw new IllegalArgumentException("Only Severe or Massive damage ends this effect.");
            if (severity < 3) {
                json(ctx, 200, MAPPER.createObjectNode().put("ok", true));
                return;
            }
            Map<String, ObjectNode> changed = removeSourceSevereEffects(actor);
            if (!changed.isEmpty()) {
                for (ObjectNode sheet : changed.values())
                    sheet.put("featureRevision", sheet.path("featureRevision").asLong(0) + 1);
                store.saveSheets(campaign.getId(), changed);
                for (var entry : changed.entrySet()) {
                    Campaign.Actor changedActor = campaign.actor(entry.getKey());
                    if (changedActor != null) sendFeatureState(changedActor, entry.getValue());
                }
            }
            json(ctx, 200, MAPPER.createObjectNode().put("ok", true));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            json(ctx, 400, errorBody("Invalid damage notification."));
        } catch (IllegalArgumentException e) {
            json(ctx, 400, errorBody(e.getMessage()));
        } catch (IOException e) {
            json(ctx, 500, errorBody("Could not save effects after damage."));
        }
    }

    private Map<String, ObjectNode> removeSourceSevereEffects(Campaign.Actor source) throws IOException {
        Map<String, ObjectNode> changed = new java.util.LinkedHashMap<>();
        for (Campaign.Actor target : campaign.getActors()) {
            ObjectNode sheet = storedSheet(target.getId());
            JsonNode effects = sheet.path("effects");
            if (!effects.isArray()) continue;
            ArrayNode kept = MAPPER.createArrayNode();
            boolean removed = false;
            for (JsonNode effect : effects) {
                boolean ownLocalEffect = target.getId().equals(source.getId())
                        && !effect.hasNonNull("sourceActorId");
                boolean sourcedEffect = source.getId().equals(effect.path("sourceActorId").asText(""));
                if (effect.path("endOnSourceSevere").asBoolean() && (ownLocalEffect || sourcedEffect)) {
                    removed = true;
                } else {
                    kept.add(effect);
                }
            }
            if (removed) {
                sheet.set("effects", kept);
                changed.put(target.getId(), sheet);
            }
        }
        return changed;
    }

    private ObjectNode storedSheet(String actorId) throws IOException {
        String stored = store.loadSheet(campaign.getId(), actorId);
        if (stored == null)
            return MAPPER.createObjectNode();
        JsonNode sheet = MAPPER.readTree(stored);
        if (!(sheet instanceof ObjectNode object))
            throw new IOException("Invalid stored sheet");
        return object;
    }

    private Campaign.Actor ownedActor(String playerId, String actorId) {
        Campaign.Actor actor = campaign == null ? null : campaign.actor(actorId);
        if (actor == null || !playerId.equals(actor.getOwnerId())) {
            throw new IllegalArgumentException("Choose a character you own.");
        }
        return actor;
    }

    private synchronized void handleShareCard(Context ctx) {
        String playerId = requirePlayer(ctx);
        if (playerId == null)
            return;
        try {
            if (ctx.bodyAsBytes().length > config.maxMessageBytes()) {
                json(ctx, 413, errorBody("Shared card is too large"));
                return;
            }
            JsonNode request = MAPPER.readTree(ctx.body());
            if (request == null || !request.isObject()) throw new IllegalArgumentException("Invalid shared card request.");
            String actorId;
            String actorName;
            String category;
            ObjectNode sheet = null;
            ObjectNode item;
            if (request.has("compendium")) {
                if (request.has("offerQuantity"))
                    throw new IllegalArgumentException("Compendium links cannot offer inventory for transfer.");
                JsonNode reference = request.path("compendium");
                String name = reference.path("category").asText();
                java.nio.file.Path dir = host.compendiumDirectory();
                if (dir == null || !COMPENDIUM_NAME.matcher(name).matches())
                    throw new IllegalArgumentException("Unknown compendium category.");
                java.nio.file.Path file = dir.resolve(name + ".json");
                if (!java.nio.file.Files.isRegularFile(file))
                    throw new IllegalArgumentException("Unknown compendium category.");
                item = ChatCards.compendiumItem(MAPPER.readTree(file.toFile()), reference.path("entryId").asText());
                actorId = "";
                actorName = "Compendium - " + item.path("source").asText();
                category = "feature";
            } else {
                Campaign.Actor actor = ownedActor(playerId, request.path("actorId").asText());
                actorId = actor.getId();
                actorName = actor.getName();
                category = request.path("category").asText();
                sheet = storedSheet(actorId);
                item = ChatCards.resolve(sheet, category, request.path("item"));
            }
            int offerQuantity = 0;
            if (request.has("offerQuantity")) {
                JsonNode quantity = request.get("offerQuantity");
                if (!quantity.isIntegralNumber() || !quantity.canConvertToInt()
                        || quantity.asInt() <= 0 || quantity.asInt() > ChatCards.available(sheet, category, item))
                    throw new IllegalArgumentException("Offer a whole quantity from your available unequipped inventory.");
                offerQuantity = quantity.asInt();
            }
            String to = chatRecipient(request);
            ObjectNode card = publishSharedCard(playerId, campaign.player(playerId).getName(), actorId, actorName,
                    category, item, offerQuantity, to, request.has("compendium"));
            json(ctx, 200, card);
        } catch (IllegalArgumentException e) {
            json(ctx, 400, errorBody(e.getMessage()));
        } catch (IOException e) {
            json(ctx, 500, errorBody("Could not read the compendium, source sheet or shared card."));
        }
    }

    private ObjectNode publishSharedCard(String playerId, String playerName, String actorId, String actorName,
                                         String category, ObjectNode item, int offerQuantity, String to,
                                         boolean compendium) throws IOException {
        ObjectNode card = MAPPER.createObjectNode().put("cardId", java.util.UUID.randomUUID().toString())
                .put("actorId", actorId).put("actorName", actorName).put("category", category);
        card.set("item", item);
        if (compendium) {
            card.set("badges", item.remove("badges"));
            card.set("sections", item.remove("sections"));
        }
        if (offerQuantity > 0)
            card.put("offerQuantity", offerQuantity);
        card.put("available", offerQuantity);
        String description = item.path("text").asText(item.path("note").asText(
                item.path("feature").path("text").asText("")));
        ObjectNode message = chatMessage(playerId, playerName, to,
                (offerQuantity > 0 ? actorName + " offers " + offerQuantity + " x " : "")
                        + item.path("name").asText() + (description.isBlank() ? "" : "\n" + description));
        message.set("sharedCard", card);
        store.appendHistoryChecked(campaign.getId(), message);
        sharedCards.put(card.path("cardId").asText(), message);
        deliver(message, to, false);
        return card;
    }

    private JsonNode visibleCard(String playerId, String cardId) {
        JsonNode message = sharedCards.get(cardId);
        if (message == null) {
            for (JsonNode entry : store.historyTail(campaign.getId(), Integer.MAX_VALUE)) {
                if (cardId.equals(entry.path("sharedCard").path("cardId").asText())) {
                    message = entry;
                    sharedCards.put(cardId, message);
                    break;
                }
            }
        }
        if (message != null) {
            String to = message.path("toId").asText("");
            if (to.isEmpty() || playerId.equals(to) || playerId.equals(message.path("fromId").asText()))
                return message.path("sharedCard");
        }
        throw new IllegalArgumentException("Shared card is unavailable.");
    }

    private synchronized void handleCardStock(Context ctx) {
        String playerId = requirePlayer(ctx);
        if (playerId == null)
            return;
        try {
            JsonNode card = visibleCard(playerId, ctx.pathParam("id"));
            int count = campaign.actor(card.path("actorId").asText()) == null ? 0
                                                                              : ChatCards.remaining(storedSheet(card.path("actorId").asText()), card);
            json(ctx, 200, MAPPER.createObjectNode().put("available", count));
        } catch (IllegalArgumentException e) {
            json(ctx, 404, errorBody(e.getMessage()));
        } catch (IOException e) {
            json(ctx, 500, errorBody("Could not read inventory availability."));
        }
    }

    private synchronized void handleGrabCard(Context ctx) {
        String playerId = requirePlayer(ctx);
        if (playerId == null)
            return;
        try {
            JsonNode request = MAPPER.readTree(ctx.body());
            if (request == null || !request.isObject()) throw new IllegalArgumentException("Invalid grab request.");
            if (request.has("all") && !request.get("all").isBoolean())
                throw new IllegalArgumentException("Invalid grab quantity.");
            JsonNode card = visibleCard(playerId, ctx.pathParam("id"));
            String sourceId = card.path("actorId").asText();
            Campaign.Actor sourceActor = campaign.actor(sourceId);
            Campaign.Actor targetActor = ownedActor(playerId, request.path("actorId").asText());
            if (sourceActor == null || sourceId.equals(targetActor.getId())) {
                throw new IllegalArgumentException("Choose a different receiving character.");
            }
            ObjectNode source = storedSheet(sourceId);
            ObjectNode target = storedSheet(targetActor.getId());
            if (target.isEmpty())
                throw new IllegalArgumentException("Create and save the receiving character first.");
            int remaining = ChatCards.remaining(source, card);
            if (remaining <= 0)
                throw new IllegalArgumentException("This is not an available transfer offer. Ask the owner to offer the item.");
            int quantity = request.path("all").asBoolean(false) ? remaining : 1;
            ChatCards.transfer(source, target, card.path("category").asText(), card.path("item"), quantity);
            String cardId = card.path("cardId").asText();
            ObjectNode claims = source.withObject("/transferClaims");
            claims.put(cardId, claims.path(cardId).asInt(0) + quantity);
            source.put("inventoryRevision", source.path("inventoryRevision").asLong(0) + 1);
            target.put("inventoryRevision", target.path("inventoryRevision").asLong(0) + 1);
            store.transferSheets(campaign.getId(), sourceId, source, targetActor.getId(), target);
            inventoryChanged(sourceActor, source);
            inventoryChanged(targetActor, target);
            json(ctx, 200, MAPPER.createObjectNode().put("transferred", true).put("quantity", quantity));
        } catch (IllegalArgumentException e) {
            json(ctx, 409, errorBody(e.getMessage()));
        } catch (IOException e) {
            json(ctx, 500, errorBody("Could not complete the inventory transfer. Refresh both inventories before retrying."));
        }
    }

    private void inventoryChanged(Campaign.Actor actor, ObjectNode sheet) {
        ObjectNode message = MAPPER.createObjectNode().put("type", "sheetInventory").put("actorId", actor.getId());
        ObjectNode inventory = message.putObject("inventory");
        for (String field : List.of("inventoryRevision", "inventory", "activeWeapons", "carriedWeapons", "activeArmor", "carriedArmor",
                "compendiumRevision", "loadout", "vault")) {
            if (sheet.has(field))
                inventory.set(field, sheet.get(field));
        }
        for (Client client : clients.values()) {
            if (actor.getOwnerId().equals(client.playerId))
                sendTo(client.ctx, message.toString());
        }
        stockChanged(actor.getId());
    }

    private void stockChanged(String actorId) {
        broadcast(MAPPER.createObjectNode().put("type", "cardStock").put("actorId", actorId).toString());
        host.cardStockChanged(actorId);
    }

    /** Units of a shared transfer offer that can still be grabbed; 0 when the source is gone. */
    public synchronized int cardRemaining(JsonNode sharedCard) {
        String actorId = sharedCard.path("actorId").asText();
        try {
            return campaign == null || campaign.actor(actorId) == null ? 0
                    : ChatCards.remaining(storedSheet(actorId), sharedCard);
        } catch (IOException e) {
            return 0;
        }
    }

    private void handleArtwork(Context ctx) {
        String playerId = requirePlayer(ctx);
        if (playerId == null) {
            return;
        }
        Campaign c = campaign;
        Campaign.Actor actor = c == null ? null : c.actor(ctx.pathParam("id"));
        if (actor == null || !playerId.equals(actor.getOwnerId())) {
            json(ctx, 403, errorBody("Not your actor"));
            return;
        }
        try {
            String hash = store.storeArtwork(c.getId(), ctx.bodyAsBytes());
            actor.setArtwork(hash);
            store.save(c);
            host.campaignChanged(c);
            json(ctx, 200, MAPPER.createObjectNode().put("artwork", assets.artworkUrl(c.getId(), hash)));
        } catch (IOException e) {
            json(ctx, 400, errorBody(e.getMessage() == null ? "Invalid image" : e.getMessage()));
        }
    }

    private void handleAsset(Context ctx) throws IOException {
        sendAsset(ctx, assets.resolve(ctx.pathParam("name")));
    }

    private void handleTile(Context ctx) throws IOException {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,6})_(\\d{1,6})(\\.\\w+)?")
                .matcher(ctx.pathParam("tile"));
        String key = ctx.pathParam("key");
        String level = ctx.pathParam("level");
        if (!m.matches() || !CampaignStore.safeId(key) || !level.matches("\\d{1,2}")) {
            ctx.status(404);
            return;
        }
        sendAsset(ctx, assets.resolveTile(key, Integer.parseInt(level), Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2))));
    }

    private void sendAsset(Context ctx, AssetRegistry.Asset asset) throws IOException {
        if (asset == null) {
            ctx.status(404);
            return;
        }
        ctx.header("Cache-Control", "public, max-age=31536000, immutable");
        ctx.header("Accept-Ranges", "bytes");
        ctx.contentType(asset.contentType());
        long size = java.nio.file.Files.size(asset.file());
        long[] range = parseRange(ctx.header("Range"), size);
        if (range == null) {
            ctx.result(java.nio.file.Files.newInputStream(asset.file()));
            return;
        }
        if (range[0] < 0) {
            ctx.status(416).header("Content-Range", "bytes */" + size);
            return;
        }
        long length = Math.min(range[1] - range[0] + 1, 8L * 1024 * 1024);
        range[1] = range[0] + length - 1;
        InputStream in = java.nio.file.Files.newInputStream(asset.file());
        in.skipNBytes(range[0]);
        ctx.status(206).header("Content-Range", "bytes " + range[0] + "-" + range[1] + "/" + size);
        ctx.header("Content-Length", String.valueOf(length));
        ctx.result(in.readNBytes((int) length));
        in.close();
    }

    /** Parses a single {@code bytes=a-b} range: null = no range, {-1,-1} = unsatisfiable, else {start, end}. */
    static long[] parseRange(String header, long size) {
        if (header == null || !header.startsWith("bytes=") || header.contains(",")) {
            return null;
        }
        String spec = header.substring(6).trim();
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return null;
        }
        try {
            String a = spec.substring(0, dash);
            String b = spec.substring(dash + 1);
            long start;
            long end;
            if (a.isEmpty()) {
                long suffix = Long.parseLong(b);
                start = Math.max(0, size - suffix);
                end = size - 1;
            } else {
                start = Long.parseLong(a);
                end = b.isEmpty() ? size - 1 : Math.min(Long.parseLong(b), size - 1);
            }
            if (start < 0 || start >= size || end < start) {
                return new long[]{-1, -1};
            }
            return new long[]{start, end};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void serveIndex(Context ctx) throws IOException {
        try (InputStream in = OnlineSessionServer.class.getResourceAsStream("/web/index.html")) {
            if (in == null) {
                ctx.status(503).contentType("text/html; charset=utf-8").result(
                        "<h1>Online Play client not built</h1><p>Run <code>npm run build</code> in <code>web/</code>.</p>");
                return;
            }
            ctx.header("Cache-Control", "no-cache");
            ctx.contentType("text/html; charset=utf-8").result(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    // ---- WebSocket ----

    private synchronized void onConnect(io.javalin.websocket.WsConnectContext ctx) {
        String token = ctx.cookie(OnlineProtocol.COOKIE);
        String playerId = playerOf(token);
        Campaign c = campaign;
        Campaign.Player player = c == null ? null : c.player(playerId);
        if (player == null) {
            ctx.closeSession(1008, "Login required");
            return;
        }
        LoginClaim claim = loginClaim(playerId);
        if (claim != null && !claim.token().equals(token)) {
            ctx.closeSession(1008, "Player account already connected");
            return;
        }
        ctx.session.setIdleTimeout(java.time.Duration.ofMinutes(10));
        Set<String> connected = new LinkedHashSet<>();
        clients.values().forEach(cl -> connected.add(cl.playerId));
        if (!connected.contains(playerId) && connected.size() >= config.maxPlayers()) {
            ctx.closeSession(1008, "Session is full");
            return;
        }
        Client client = new Client(ctx, playerId, player.getName());
        loginClaims.put(playerId, new LoginClaim(token, Long.MAX_VALUE));
        synchronized (this) {
            clients.put(ctx.sessionId(), client);
            ObjectNode hello = MAPPER.createObjectNode();
            hello.put("type", "hello").put("protocol", OnlineProtocol.VERSION);
            hello.put("playerId", playerId).put("playerName", player.getName());
            hello.put("serverTime", System.currentTimeMillis());
            hello.put("sessionName", config.sessionName());
            hello.put("campaignName", c.getName());
            hello.put("paused", paused);
            hello.put("sessionId", sessionId);
            hello.put("campaignId", c.getId()).put("drawingTools", host.supportsDrawing()).put("measurementTools", true);
            hello.put("playerWhispers", true);
            hello.put("chatCards", true);
            hello.put("compendiumChat", true);
            hello.put("transferOffers", true);
            hello.put("chatIdentity", true);
            hello.put("notes", true);
            hello.put("keptDice", true);
            hello.put("rollMultipliers", true);
            hello.put("rollRewards", true);
            hello.put("partyFeatures", true);
            hello.put("deathMoves", true);
            hello.put("deathMoveOptions", true);
            hello.put("handouts", true);
            sendTo(ctx, hello.toString());
            sendHandouts(handouts(), client);
            if (audioJson != null) {
                sendTo(ctx, audioJson);
            }
            if (current != null) {
                sendTo(ctx, snapshotJson(client));
            }
            sendTo(ctx, measurementsJson());
        }
        host.rosterChanged();
        publishRoster();
    }

    private void onMessage(WsContext ctx, String text) throws IOException {
        Client client = clients.get(ctx.sessionId());
        if (client == null) {
            return;
        }
        if (text.length() > config.maxMessageBytes()) {
            sendTo(ctx, error("Message too large"));
            return;
        }
        JsonNode msg = MAPPER.readTree(text);
        switch (msg.path("type").asText("")) {
            case "clockSync" -> {
                ObjectNode out = MAPPER.createObjectNode();
                out.put("type", "clockSync");
                out.set("id", msg.path("id"));
                out.set("clientSent", msg.path("clientSent"));
                out.put("serverTime", System.currentTimeMillis());
                sendTo(ctx, out.toString());
            }
            case "pong" -> onPong(client, msg.path("id").asLong(0));
            case "command" -> onCommand(client, msg);
            case "measurement" -> onMeasurement(client, msg);
            case "chat" -> onChat(client, msg);
            case "chatIdentity" -> onChatIdentity(client, msg);
            case "roll" -> onRoll(client, msg);
            case "fogExplored" -> onFogExplored(client, msg);
            case "resync" -> onResync(client, msg.path("lastSeq").asLong(0));
            default -> sendTo(ctx, error("Unknown message"));
        }
    }

    private void onCommand(Client client, JsonNode msg) {
        String cmdId = msg.path("cmdId").asText("");
        if ("drawing".equals(msg.path("kind").asText())) {
            if (paused) {
                sendTo(client.ctx, reject(cmdId, "paused", null));
                return;
            }
            host.editDrawing(client.playerId, msg).orTimeout(5, TimeUnit.SECONDS).whenComplete((result, error) -> {
                if (error != null || result == null || !result.ok()) {
                    sendTo(client.ctx, reject(cmdId, error != null || result == null ? "Drawing failed" : result.reason(), null));
                } else {
                    sendTo(client.ctx, MAPPER.createObjectNode().put("type", "ack").put("cmdId", cmdId).put("seq", seq()).toString());
                }
            });
            return;
        }
        if (!"moveToken".equals(msg.path("kind").asText())) {
            sendTo(client.ctx, reject(cmdId, "unknown command", null));
            return;
        }
        String tokenId = msg.path("tokenId").asText("");
        double x = msg.path("x").asDouble(Double.NaN);
        double y = msg.path("y").asDouble(Double.NaN);
        if (paused) {
            sendTo(client.ctx, reject(cmdId, "paused", tokenId));
            return;
        }
        if (tokenId.isEmpty() || !Double.isFinite(x) || !Double.isFinite(y)) {
            sendTo(client.ctx, reject(cmdId, "invalid", tokenId));
            return;
        }
        host.moveToken(client.playerId, tokenId, x, y).orTimeout(5, TimeUnit.SECONDS).whenComplete((result, error) -> {
            if (error != null || result == null) {
                sendTo(client.ctx, reject(cmdId, "failed", tokenId));
            } else if (result.ok()) {
                ObjectNode ack = MAPPER.createObjectNode();
                ack.put("type", "ack").put("cmdId", cmdId).put("seq", seq());
                sendTo(client.ctx, ack.toString());
            } else {
                sendTo(client.ctx, reject(cmdId, result.reason(), tokenId, result.x(), result.y()));
            }
        });
    }

    private String reject(String cmdId, String reason, String tokenId) {
        return reject(cmdId, reason, tokenId, Double.NaN, Double.NaN);
    }

    private String reject(String cmdId, String reason, String tokenId, double hostX, double hostY) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("type", "reject").put("cmdId", cmdId).put("reason", reason);
        if (tokenId != null && Double.isFinite(hostX) && Double.isFinite(hostY)) {
            ObjectNode state = MAPPER.createObjectNode();
            state.put("id", tokenId).put("x", hostX).put("y", hostY);
            out.set("token", state);
        } else if (tokenId != null) {
            ObjectNode state = null;
            synchronized (this) {
                if (current != null) {
                    for (JsonNode token : current.path("tokens")) {
                        if (tokenId.equals(token.path("id").asText())) {
                            state = MAPPER.createObjectNode();
                            state.put("id", tokenId).put("x", token.path("x").asDouble()).put("y", token.path("y").asDouble());
                        }
                    }
                }
            }
            if (state != null) {
                out.set("token", state);
            }
        }
        return out.toString();
    }

    private synchronized void onChatIdentity(Client client, JsonNode msg) {
        JsonNode value = msg.path("identity");
        if (!value.isTextual()) {
            sendTo(client.ctx, error("Chat identity must be text."));
            return;
        }
        String identity = value.asText().strip();
        if (identity.length() > 60 || identity.codePoints().anyMatch(Character::isISOControl)) {
            sendTo(client.ctx, error("Chat identity must be at most 60 characters without control characters."));
            return;
        }
        Campaign c = campaign;
        Campaign.Player player = c == null ? null : c.player(client.playerId);
        if (player == null) {
            sendTo(client.ctx, error("Your player is no longer in this campaign."));
            return;
        }
        String previous = player.getChatIdentity();
        player.setChatIdentity(identity);
        try {
            store.save(c);
        } catch (IOException e) {
            player.setChatIdentity(previous);
            sendTo(client.ctx, error("Could not save chat identity: " + e.getMessage()));
            return;
        }
        host.campaignChanged(c);
        publishRoster();
        host.rosterChanged();
    }

    private String chatIdentity(String playerId) {
        Campaign c = campaign;
        Campaign.Player player = c == null ? null : c.player(playerId);
        return player == null || player.getChatIdentity() == null ? "" : player.getChatIdentity();
    }

    private String chatName(String playerId, String accountName) {
        String identity = chatIdentity(playerId);
        return identity.isEmpty() ? accountName : identity;
    }

    private void onChat(Client client, JsonNode msg) {
        String text = clip(msg.path("text").asText(""));
        if (text.isBlank()) {
            return;
        }
        try {
            String to = chatRecipient(msg);
            ObjectNode out = chatMessage(client.playerId, client.playerName, to, text);
            ObjectNode rollSource = rollSource(msg);
            if (rollSource != null) out.set("rollSource", rollSource);
            if (msg.has("restDice")) {
                Campaign.Actor actor = ownedActor(client.playerId, msg.path("actorId").asText());
                if (to != null || !text.startsWith("SHORT REST\n") || !msg.path("restDice").isArray() || msg.path("restDice").size() > 100)
                    throw new IllegalArgumentException("Invalid short-rest dice report.");
                ArrayNode dice = out.putArray("restDice");
                String[] lines = text.split("\n");
                for (JsonNode raw : msg.path("restDice")) {
                    String expression = raw.path("expression").asText();
                    int line = raw.path("line").asInt(-1);
                    if (!expression.replaceAll("\\s", "").matches("(?i)\\d*d\\d+(?:[+-]\\d+)*") || !raw.path("value").isInt()
                            || raw.path("value").asInt() < 0 || line < 0)
                        throw new IllegalArgumentException("Invalid downtime die.");
                    if (line + 2 >= lines.length || !lines[line + 2].contains(expression + " = " + raw.path("value").asInt())) continue;
                    ObjectNode die = dice.addObject().put("line", line).put("expression", expression).put("value", raw.path("value").asInt());
                    if (raw.has("resource")) {
                        String resource = raw.path("resource").asText();
                        if (!List.of("hp", "stress", "armor").contains(resource) || !raw.path("recovery").isInt()
                                || raw.path("recovery").asInt() < 0 || raw.path("recovery").asInt() > 12
                                || !raw.path("cleared").isInt() || raw.path("cleared").asInt() < 0 || raw.path("cleared").asInt() > 12)
                            throw new IllegalArgumentException("Invalid downtime recovery.");
                        die.put("resource", resource).put("recovery", raw.path("recovery").asInt()).put("cleared", raw.path("cleared").asInt());
                    }
                }
                out.put("actorId", actor.getId());
                sessionRolls.add(rollKey(out.path("id").asLong(), out.path("ts").asLong()));
            }
            deliver(out, to);
        } catch (IllegalArgumentException e) {
            sendTo(client.ctx, error(e.getMessage()));
        }
    }

    private String chatRecipient(JsonNode msg) {
        String to = msg.path("toId").asText("");
        if (to.isEmpty()) {
            return msg.path("whisper").asBoolean(false) ? "dm" : null;
        }
        Campaign c = campaign;
        if (!"dm".equals(to) && (c == null || c.player(to) == null)) {
            throw new IllegalArgumentException("Whisper recipient not found. Choose another recipient.");
        }
        if (!"dm".equals(to) && clients.values().stream().noneMatch(client -> to.equals(client.playerId))) {
            throw new IllegalArgumentException("Whisper recipient is offline. Choose another recipient.");
        }
        return to;
    }

    private void putRecipient(ObjectNode out, String to) {
        if (to == null || to.isEmpty()) return;
        out.put("toId", to);
        Campaign c = campaign;
        Campaign.Player player = c == null ? null : c.player(to);
        if ("dm".equals(to)) out.put("to", "Gamemaster");
        else if (player != null) out.put("to", chatName(to, player.getName()));
    }

    private void onRoll(Client client, JsonNode msg) {
        DiceRoller.Result result;
        ObjectNode rollSource;
        List<DiceRoller.Detail> breakdown = null;
        List<ArrayNode> breakdownNotes = new ArrayList<>();
        String to;
        try {
            to = chatRecipient(msg);
            rollSource = rollSource(msg);
            result = DiceRoller.roll(msg.path("expression").asText(""), config.maxDice(), diceRandom);
            if (msg.has("contributions")) {
                JsonNode sources = msg.path("contributions");
                if (!sources.isArray() || sources.isEmpty() || sources.size() > 64) {
                    throw new IllegalArgumentException("Roll contributions must contain 1 to 64 sources");
                }
                List<DiceRoller.Contribution> contributions = new ArrayList<>();
                for (JsonNode source : sources) {
                    String name = source.path("label").asText("").strip();
                    String expression = source.path("expression").asText("");
                    if (name.isEmpty() || name.length() > 120 || expression.length() > 100) {
                        throw new IllegalArgumentException("Invalid roll contribution");
                    }
                    contributions.add(new DiceRoller.Contribution(name, expression));
                    ArrayNode notes = MAPPER.createArrayNode();
                    if (source.has("notes")) {
                        if (!source.path("notes").isArray() || source.path("notes").size() > 64) {
                            throw new IllegalArgumentException("Invalid roll source notes");
                        }
                        for (JsonNode note : source.path("notes")) {
                            String noteLabel = note.path("label").asText("").strip();
                            String impact = note.path("impact").asText("").strip();
                            if (noteLabel.isEmpty() || noteLabel.length() > 120 || impact.isEmpty() || impact.length() > 120) {
                                throw new IllegalArgumentException("Invalid roll source note");
                            }
                            notes.addObject().put("label", noteLabel).put("impact", impact);
                        }
                    }
                    breakdownNotes.add(notes);
                }
                breakdown = DiceRoller.breakdown(result, contributions, config.maxDice());
            }
        } catch (IllegalArgumentException e) {
            sendTo(client.ctx, error(e.getMessage()));
            return;
        }
        ObjectNode out = MAPPER.createObjectNode();
        synchronized (this) {
            out.put("id", ++chatId);
        }
        out.put("type", "roll").put("ts", System.currentTimeMillis());
        out.put("fromId", client.playerId).put("from", chatName(client.playerId, client.playerName));
        putRecipient(out, to);
        out.put("expression", result.expression());
        ArrayNode rolls = out.putArray("rolls");
        result.rolls().forEach(rolls::add);
        out.put("total", result.total());
        if (rollSource != null) out.set("rollSource", rollSource);
        if (breakdown != null) {
            ArrayNode details = MAPPER.valueToTree(breakdown);
            for (int i = 0; i < breakdownNotes.size(); i++) {
                if (!breakdownNotes.get(i).isEmpty()) {
                    ((ObjectNode)details.get(i)).set("notes", breakdownNotes.get(i));
                }
            }
            out.set("breakdown", details);
        }
        String label = msg.path("label").asText("").strip();
        if (!label.isEmpty()) {
            out.put("label", label.length() > 80 ? label.substring(0, 80) : label);
        }
        // Opaque hints that let the roller's own client attach buttons (Damage, rerolls) to the chat card.
        for (String hint : new String[]{"ref", "actorId", "source", "weapon"}) {
            String value = msg.path(hint).asText("").strip();
            if (!value.isEmpty()) {
                out.put(hint, value.length() > 80 ? value.substring(0, 80) : value);
            }
        }
        if ("duality".equals(msg.path("kind").asText("")) && result.expression().matches("^(?:2d12|1d(?:12|20)\\+1d(?:12|20))(?:[+-](?:\\d*d\\d+|\\d+))*$")) {
            out.put("kind", "duality");
            if (!List.of(out.path("source").asText("").split(",")).contains("reaction"))
                out.put("rewardClaimed", false);
            if (msg.path("difficulty").isInt()) {
                out.put("difficulty", msg.path("difficulty").asInt());
            }
        }
        sessionRolls.add(rollKey(out.path("id").asLong(), out.path("ts").asLong()));
        deliver(out, to);
    }

    private ObjectNode rollSource(JsonNode msg) {
        if (!msg.has("rollSource")) return null;
        JsonNode source = msg.path("rollSource");
        if (!source.isObject()) throw new IllegalArgumentException("Invalid roll source description");
        ObjectNode out = MAPPER.createObjectNode();
        for (String field : new String[]{"name", "text", "source"}) {
            JsonNode value = source.path(field);
            int limit = "text".equals(field) ? 16_000 : 240;
            if (!value.isTextual() || value.asText().length() > limit
                    || ("name".equals(field) && value.asText().isBlank())) {
                throw new IllegalArgumentException("Invalid roll source " + field + " (maximum " + limit + " characters)");
            }
            out.put(field, value.asText());
        }
        return out;
    }

    private ObjectNode chatMessage(String fromId, String from, String toId, String text) {
        ObjectNode out = MAPPER.createObjectNode();
        synchronized (this) {
            out.put("id", ++chatId);
        }
        out.put("type", "chat").put("ts", System.currentTimeMillis());
        out.put("fromId", fromId).put("from", chatName(fromId, from));
        putRecipient(out, toId);
        out.put("text", text);
        return out;
    }

    /** Sends a chat/roll message to everyone, or only to the sender and the addressee for whispers. */
    private void deliver(ObjectNode message, String toId) {
        deliver(message, toId, true);
    }

    private synchronized void deliver(ObjectNode message, String toId, boolean persist) {
        Campaign c = campaign;
        if (c != null && persist) {
            store.appendHistory(c.getId(), message);
        }
        String json = playerView(message).toString();
        String fromId = message.path("fromId").asText();
        if (toId == null || toId.isEmpty()) {
            broadcast(json);
        } else {
            for (Client client : clients.values()) {
                if (client.playerId.equals(toId) || client.playerId.equals(fromId)) {
                    sendTo(client.ctx, json);
                }
            }
        }
        if (toId == null || toId.isEmpty() || "dm".equals(toId) || "dm".equals(fromId)) {
            host.dmMessage(message);
        }
    }

    private static String clip(String text) {
        String trimmed = text.strip();
        return trimmed.length() > MAX_CHAT_CHARS ? trimmed.substring(0, MAX_CHAT_CHARS) : trimmed;
    }

    private void onFogExplored(Client client, JsonNode msg) {
        Campaign c = campaign;
        String mapId = msg.path("mapId").asText("");
        String levelId = msg.path("levelId").asText("");
        if (c == null || !CampaignStore.safeId(mapId) || !CampaignStore.safeId(levelId)) {
            return;
        }
        BitSet cells = decodeBits(msg.path("cells").asText(""));
        if (cells == null || cells.isEmpty()) {
            return;
        }
        try {
            store.mergeFog(c.getId(), client.playerId, mapId, levelId, cells);
        } catch (IOException ignored) {
            // memory is best effort; the client keeps its own copy for the session
        }
    }

    private synchronized void onResync(Client client, long lastSeq) {
        if (current == null) {
            return;
        }
        if (lastSeq == seq) {
            return;
        }
        long first = lastSeq + 1;
        boolean contiguous = lastSeq > 0 && lastSeq < seq && !logSeqs.isEmpty() && logSeqs.peekFirst()[0] <= first;
        if (!contiguous) {
            sendTo(client.ctx, snapshotJson(client));
            return;
        }
        Iterator<long[]> s = logSeqs.iterator();
        Iterator<String> j = logJson.iterator();
        while (s.hasNext()) {
            long n = s.next()[0];
            String json = j.next();
            if (n >= first) {
                sendTo(client.ctx, json);
            }
        }
    }

    // ---- helpers ----

    private String error(String message) {
        return MAPPER.createObjectNode().put("type", "error").put("message", message).toString();
    }

    private void broadcast(String json) {
        for (Client client : clients.values()) {
            sendTo(client.ctx, json);
        }
    }

    private void sendTo(WsContext ctx, String json) {
        try {
            ctx.send(json);
        } catch (Exception ignored) {
        }
    }

    static String encodeBits(BitSet bits) {
        byte[] raw = bits.toByteArray();
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        deflater.setInput(raw);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    static BitSet decodeBits(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return new BitSet();
        }
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(Base64.getDecoder().decode(encoded));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                out.write(buffer, 0, n);
                if (out.size() > MAX_FOG_BYTES) {
                    return null;
                }
            }
            return BitSet.valueOf(out.toByteArray());
        } catch (IllegalArgumentException | DataFormatException e) {
            return null;
        } finally {
            inflater.end();
        }
    }
}
