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

        /** A chat or roll message that reached the DM (public or whispered to the DM). */
        void dmMessage(JsonNode message);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> DIFF_CATEGORIES = List.of("images", "walls", "doors", "lights", "lighting",
            "weather", "dmFog", "fogGrid", "actors", "grid", "overlays", "texts", "view", "pings", "indoor",
            "effectTextures", "penWidthCells", "fear");
    private static final int MAX_FOG_BYTES = 4 * 1024 * 1024;
    private static final int MAX_CHAT_CHARS = 1000;

    private final Config config;
    private final CampaignStore store;
    private final AssetRegistry assets;
    private final Host host;
    private final LoginRateLimiter limiter;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Random diceRandom = new Random();
    private final Map<String, String> sessions = new ConcurrentHashMap<>();
    private final Map<String, Client> clients = new ConcurrentHashMap<>();
    private final Map<String, LoginClaim> loginClaims = new java.util.HashMap<>();

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
        this.assets = assets;
        this.host = host;
        this.limiter = new LoginRateLimiter(config.loginMaxFailures(), config.loginLockoutSeconds() * 1000L);
    }

    // ---- lifecycle ----

    public synchronized void start() {
        if (app != null) {
            return;
        }
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
        javalin.post("/api/chat-cards", this::handleShareCard);
        javalin.get("/api/chat-cards/{id}", this::handleCardStock);
        javalin.post("/api/chat-cards/{id}/grab", this::handleGrabCard);
        javalin.get("/api/compendium", this::handleCompendiumIndex);
        javalin.get("/api/compendium/{category}", this::handleCompendiumCategory);
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

    // ---- state publication ----

    public synchronized void setCampaign(Campaign campaign) {
        this.campaign = campaign;
        sharedCards.clear();
    }

    public Campaign campaign() {
        return campaign;
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
                    tail.add(entry);
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

    /** DM chat message, public or whispered to one player. */
    public void dmChat(String text, String toPlayerId) {
        if (text == null || text.isBlank()) {
            return;
        }
        ObjectNode message = chatMessage("dm", "DM", toPlayerId, clip(text));
        deliver(message, toPlayerId);
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

    private void json(Context ctx, int status, ObjectNode body) {
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

    private void handleCompendiumIndex(Context ctx) {
        if (requirePlayer(ctx) == null) {
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
        if (requirePlayer(ctx) == null) {
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
            if (sheet.path("inventoryRevision").asLong(0) != previous.path("inventoryRevision").asLong(0)) {
                ObjectNode conflict = errorBody("Inventory changed in chat. Refresh inventory before saving.");
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
            saved.remove("transferClaims");
            if (previous.has("transferClaims"))
                saved.set("transferClaims", previous.get("transferClaims"));
            store.saveSheet(c.getId(), actor.getId(), saved.toString());
            stockChanged(actor.getId());
            json(ctx, 200, MAPPER.createObjectNode().put("saved", true));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            json(ctx, 400, errorBody("Invalid sheet"));
        } catch (IOException e) {
            json(ctx, 500, errorBody("Could not read or save the character sheet."));
        }
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
            Campaign.Actor actor = ownedActor(playerId, request.path("actorId").asText());
            String category = request.path("category").asText();
            ObjectNode sheet = storedSheet(actor.getId());
            ObjectNode item = ChatCards.resolve(sheet, category, request.path("item"));
            int offerQuantity = 0;
            if (request.has("offerQuantity")) {
                JsonNode quantity = request.get("offerQuantity");
                if (!quantity.isIntegralNumber() || !quantity.canConvertToInt()
                        || quantity.asInt() <= 0 || quantity.asInt() > ChatCards.available(sheet, category, item))
                    throw new IllegalArgumentException("Offer a whole quantity from your available unequipped inventory.");
                offerQuantity = quantity.asInt();
            }
            String to = chatRecipient(request);
            ObjectNode card = MAPPER.createObjectNode().put("cardId", java.util.UUID.randomUUID().toString())
                                    .put("actorId", actor.getId()).put("actorName", actor.getName()).put("category", category);
            card.set("item", item);
            if (offerQuantity > 0)
                card.put("offerQuantity", offerQuantity);
            card.put("available", offerQuantity);
            String description = item.path("text").asText(item.path("note").asText(
                    item.path("feature").path("text").asText("")));
            ObjectNode message = chatMessage(playerId, campaign.player(playerId).getName(), to,
                                             (offerQuantity > 0 ? actor.getName() + " offers " + offerQuantity + " x " : "")
                                                     + item.path("name").asText() + (description.isBlank() ? "" : "\n" + description));
            message.set("sharedCard", card);
            store.appendHistoryChecked(campaign.getId(), message);
            sharedCards.put(card.path("cardId").asText(), message);
            deliver(message, to, false);
            json(ctx, 200, card);
        } catch (IllegalArgumentException e) {
            json(ctx, 400, errorBody(e.getMessage()));
        } catch (IOException e) {
            json(ctx, 500, errorBody("Could not read the source sheet or shared card."));
        }
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
        for (String field : List.of("inventoryRevision", "inventory", "activeWeapons", "carriedWeapons", "activeArmor", "carriedArmor")) {
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
            hello.put("campaignId", c.getId()).put("drawingTools", host.supportsDrawing()).put("measurementTools", true);
            hello.put("playerWhispers", true);
            hello.put("chatCards", true);
            hello.put("transferOffers", true);
            hello.put("chatIdentity", true);
            hello.put("keptDice", true);
            sendTo(ctx, hello.toString());
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
        if ("duality".equals(msg.path("kind").asText("")) && result.expression().matches("^2d12(?:[+-](?:\\d*d\\d+|\\d+))*$")) {
            out.put("kind", "duality");
            if (msg.path("difficulty").isInt()) {
                out.put("difficulty", msg.path("difficulty").asInt());
            }
        }
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

    private void deliver(ObjectNode message, String toId, boolean persist) {
        Campaign c = campaign;
        if (c != null && persist) {
            store.appendHistory(c.getId(), message);
        }
        String json = message.toString();
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
