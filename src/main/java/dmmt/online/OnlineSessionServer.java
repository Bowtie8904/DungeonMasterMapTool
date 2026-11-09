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
    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(OnlineSessionServer.class.getName());
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
        default boolean supportsMapPings() { return false; }

        default CompletableFuture<MoveResult> mapPing(String playerId, String mapId, String levelId, double x, double y) {
            return CompletableFuture.completedFuture(MoveResult.rejected("Map pings are unavailable on this host"));
        }

        default boolean supportsDrawing() { return false; }
        default boolean supportsTokenRotation() { return false; }
        default boolean supportsPortalControls() { return false; }

        default CompletableFuture<MoveResult> togglePortal(String playerId, String id, String mapId, String levelId) {
            return CompletableFuture.completedFuture(MoveResult.rejected("Portal controls are unavailable on this host"));
        }

        default CompletableFuture<MoveResult> rotateToken(String playerId, String tokenId, String mapId,
                                                           String levelId, double rotationDeg) {
            return CompletableFuture.completedFuture(MoveResult.rejected("Token rotation is unavailable on this host"));
        }

        default boolean supportsTokenConditions() { return false; }

        default CompletableFuture<MoveResult> setTokenCondition(String playerId, String tokenId, String mapId,
                                                                 String levelId, String type, String name,
                                                                 String conditionId, String iconId, boolean enabled) {
            return CompletableFuture.completedFuture(MoveResult.rejected("Token conditions are unavailable on this host"));
        }

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
        default void actorSheetChanged(String actorId) { }
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
    private final Map<String, ObjectNode> invisibilityRolls = new java.util.LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ObjectNode> eldest) {
            return size() > 512;
        }
    };

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
        String dmActorId;
        String dmToken;
        boolean fullSheets;
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

    private record DmSheetGrant(String actorId, String ownerId) { }
    private final Map<String, DmSheetGrant> dmSheetGrants = new java.util.concurrent.ConcurrentHashMap<>();

    public synchronized String dmSheetUrl(String actorId) {
        Campaign.Actor actor = campaign == null ? null : campaign.actor(actorId);
        if (!isRunning() || actor == null || campaign.player(actor.getOwnerId()) == null)
            throw new IllegalArgumentException("Start hosting this actor's campaign and assign it to a player before opening its sheet.");
        String token = java.util.UUID.randomUUID().toString();
        dmSheetGrants.put(token, new DmSheetGrant(actorId, actor.getOwnerId()));
        return "http://127.0.0.1:" + port() + "/sheet/" + actorId + "?dm-sheet=1#" + token;
    }

    public synchronized void revokeDmSheet(String url) {
        String token = java.net.URI.create(url).getFragment();
        DmSheetGrant grant = dmSheetGrants.remove(token);
        if (grant == null) return;
        for (Client client : new ArrayList<>(clients.values()))
            if (token.equals(client.dmToken)) client.ctx.closeSession(1001, "DM sheet closed");
    }

    private DmSheetGrant dmSheetGrant(String address, String token) {
        if (!("127.0.0.1".equals(address) || "::1".equals(address) || "0:0:0:0:0:0:0:1".equals(address)))
            return null;
        DmSheetGrant grant = token == null ? null : dmSheetGrants.get(token);
        Campaign.Actor actor = grant == null || campaign == null ? null : campaign.actor(grant.actorId());
        return actor != null && grant.ownerId().equals(actor.getOwnerId())
                && campaign.player(grant.ownerId()) != null ? grant : null;
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
        dmSheetGrants.clear();
        sessionRolls.clear();
        Javalin javalin = Javalin.create(cfg -> {
            cfg.showJavalinBanner = false;
            cfg.http.maxRequestSize = 12L * 1024 * 1024;
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
                            if (playerOf(cookieValue(request.getHeader("Cookie"))) == null
                                    && dmSheetGrant(request.getRemoteAddr(), request.getParameter("dm-sheet")) == null) {
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
        javalin.get("/api/preferences", ctx -> handlePreferences(ctx, false));
        javalin.put("/api/preferences", ctx -> handlePreferences(ctx, true));
        javalin.get("/api/sheet/{id}", this::handleSheet);
        javalin.get("/api/copycat/{id}", this::handleCopycatCandidates);
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
        javalin.get("/api/dm/catalog/{category}", ctx -> handleCatalogEditor(ctx, false));
        javalin.put("/api/dm/catalog/{category}", ctx -> handleCatalogEditor(ctx, true));
        javalin.post("/api/dm/compendium/add", ctx -> handleDmCompendiumAction(ctx, false));
        javalin.post("/api/dm/compendium/link", ctx -> handleDmCompendiumAction(ctx, true));
        javalin.put("/api/actors/{id}/artwork", this::handleArtwork);
        javalin.get("/api/actors/{id}/artwork", this::handleArtworkSettings);
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
        dmSheetGrants.clear();
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

    private synchronized void handleCatalogEditor(Context ctx, boolean save) {
        if (!requireDmCompendium(ctx)) return;
        ctx.header("Cache-Control", "no-store");
        try {
            CatalogEditor.Document document;
            if (save) {
                if (ctx.bodyAsBytes().length > CatalogEditor.MAX_BYTES) {
                    json(ctx, 413, errorBody("Catalog edit is too large."));
                    return;
                }
                JsonNode request = MAPPER.readTree(ctx.body());
                if (request == null || !(request.get("entry") instanceof ObjectNode entry)
                        || !request.path("revision").isTextual() || !request.path("existingId").isTextual())
                    throw new CatalogEditor.Rejected(400, "Specify an entry, file revision and existing ID (empty for a new entry).");
                document = CatalogEditor.save(host.compendiumDirectory(), ctx.pathParam("category"),
                        request.path("revision").asText(), request.path("existingId").asText(), entry);
                broadcast(MAPPER.createObjectNode().put("type", "catalogChanged").toString());
            } else document = CatalogEditor.read(host.compendiumDirectory(), ctx.pathParam("category"));
            ObjectNode response = MAPPER.createObjectNode().put("revision", document.revision());
            response.set("file", CatalogEditor.editable(document.file()));
            json(ctx, 200, response);
        } catch (CatalogEditor.Rejected e) {
            json(ctx, e.status(), errorBody(e.getMessage()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            json(ctx, 400, errorBody("Invalid catalog JSON. Correct the file or request before retrying."));
        } catch (IOException e) {
            json(ctx, 500, errorBody("Could not read or atomically save the catalog JSON file. Check file permissions; your draft is retained."));
        }
    }

    private void refreshCatalogSheet(ObjectNode sheet) throws IOException {
        java.nio.file.Path directory = host.compendiumDirectory();
        if (directory == null || !java.nio.file.Files.isDirectory(directory)) return;
        List<ObjectNode> files = new java.util.ArrayList<>();
        for (String category : CatalogEditor.CATEGORIES) {
            if (java.nio.file.Files.isRegularFile(directory.resolve(category + ".json")))
                files.add(CatalogEditor.read(directory, category).file());
        }
        CatalogSheets.refresh(sheet, files);
        for (String location : List.of("loadout", "vault"))
            for (JsonNode node : sheet.path(location)) {
                if (!(node instanceof ObjectNode card) || !"Copycat".equals(card.path("name").asText())
                        || !card.path("borrowedCard").isObject()) continue;
                ObjectNode borrowed = (ObjectNode) card.path("borrowedCard");
                JsonNode definition = catalogDomainCard(borrowed.path("card").path("name").asText());
                if (definition == null) { CopycatCards.clear(sheet, card); continue; }
                ObjectNode canonical = CopycatCards.prepare(definition, sheet);
                if (borrowed.path("card").has("banishDifficulty"))
                    canonical.set("banishDifficulty", borrowed.path("card").get("banishDifficulty"));
                borrowed.set("card", canonical);
            }
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
        if (this.campaign != null && !this.campaign.getId().equals(campaign.getId())) dmSheetGrants.clear();
        this.campaign = campaign;
        sharedCards.clear();
        publishRoster();
        host.rosterChanged();
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
                    registerChatArtwork(entry);
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
    public record RosterEntry(String playerId, String playerName, int connections, long pingMs, String chatIdentity,
                              String color, String pronouns) {
        public RosterEntry(String playerId, String playerName, int connections, long pingMs, String chatIdentity,
                           String color) {
            this(playerId, playerName, connections, pingMs, chatIdentity, color, "");
        }
    }

    public List<RosterEntry> roster() {
        Map<String, long[]> stats = new java.util.LinkedHashMap<>();
        Map<String, String> names = new java.util.HashMap<>();
        for (Client client : clients.values()) {
            if (client.dmActorId != null) continue;
            long[] s = stats.computeIfAbsent(client.playerId, k -> new long[]{0, -1});
            s[0]++;
            if (client.rttMs >= 0 && (s[1] < 0 || client.rttMs < s[1])) {
                s[1] = client.rttMs;
            }
            names.put(client.playerId, client.playerName);
        }
        List<RosterEntry> result = new ArrayList<>();
        stats.forEach((id, s) -> {
            Campaign.Player player = campaign.player(id);
            if (player != null) {
                Campaign.Actor actor = chatActor(id);
                result.add(new RosterEntry(id, names.get(id), (int) s[0], s[1],
                        actor == null ? "" : actor.getName(), player.getColor(), rosterPronouns(actor)));
            }
        });
        return result;
    }

    private String rosterPronouns(Campaign.Actor actor) {
        if (actor == null) return "";
        try {
            String stored = store.loadSheet(campaign.getId(), actor.getId());
            return stored == null ? "" : sheetPronouns(MAPPER.readTree(stored));
        } catch (IOException e) {
            LOG.log(java.util.logging.Level.WARNING, "Could not read pronouns for actor " + actor.getId(), e);
            return "";
        }
    }

    private static String sheetPronouns(JsonNode sheet) {
        if (sheet == null) return "";
        JsonNode description = sheet.path("description").path("Pronouns");
        String background = description.isTextual() ? description.asText() : "";
        if (!background.isBlank()) return background;
        JsonNode pronouns = sheet.path("pronouns");
        String value = pronouns.isTextual() ? pronouns.asText() : "";
        return value.isBlank() ? "" : value;
    }

    private void publishRoster() {
        ObjectNode out = MAPPER.createObjectNode().put("type", "roster");
        out.put("dmColor", campaign.getDmColor());
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
                measurements.put(id, OnlineMeasurement.parse(msg, client.playerId,
                        before == null ? campaign.player(client.playerId).getColor() : before.color()));
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
            if (client.dmActorId == null && client.playerId.equals(playerId)) {
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
            if (removed.dmActorId != null) return;
            if (clients.values().stream().noneMatch(client -> client.dmActorId == null && client.playerId.equals(removed.playerId))) {
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
        String authorization = ctx.header("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            DmSheetGrant grant = dmSheetGrant(ctx.req().getRemoteAddr(), authorization.substring(7));
            if (grant != null) {
                String path = ctx.path();
                if (path.equals("/api/preferences") || path.equals("/api/sheet/" + grant.actorId())
                        || path.equals("/api/actors/" + grant.actorId() + "/artwork")
                        || path.equals("/api/copycat/" + grant.actorId())
                        || path.equals("/api/death-move/" + grant.actorId())
                        || path.startsWith("/api/compendium") || path.startsWith("/a/") || path.startsWith("/t/"))
                    return grant.ownerId();
                if (List.of("/api/feature", "/api/death-move", "/api/death-recovery", "/api/damage-taken",
                        "/api/chat-cards", "/api/roll-reward").contains(path)) {
                    try {
                        JsonNode body = MAPPER.readTree(ctx.body());
                        if (body != null && grant.actorId().equals(body.path("actorId").asText())) return grant.ownerId();
                    } catch (IOException e) {
                        json(ctx, 400, errorBody("Invalid character sheet request."));
                        return null;
                    }
                }
                json(ctx, 403, errorBody("This DM sheet can only control its own character."));
                return null;
            }
            json(ctx, 403, errorBody("The DM sheet session has expired. Close and reopen it while hosting."));
            return null;
        }
        String playerId = playerOf(ctx.cookie(OnlineProtocol.COOKIE));
        if (playerId == null) {
            json(ctx, 401, errorBody("Login required"));
            return null;
        }
        String path = ctx.path();
        String actorId = null;
        if ("PUT".equals(ctx.method().name()) && (path.startsWith("/api/sheet/") || path.startsWith("/api/actors/"))) {
            actorId = ctx.pathParam("id");
        } else if (List.of("/api/feature", "/api/death-move", "/api/death-recovery", "/api/damage-taken",
                "/api/chat-cards", "/api/roll-reward").contains(path)
                || path.startsWith("/api/chat-cards/") && path.endsWith("/grab")) {
            try {
                JsonNode request = MAPPER.readTree(ctx.body());
                if (request != null) actorId = request.path("actorId").asText(null);
            } catch (IOException e) {
                json(ctx, 400, errorBody("Invalid character request."));
                return null;
            }
        }
        Campaign.Actor actor = campaign == null ? null : campaign.actor(actorId);
        if (actor != null && actor.isDisabled()) {
            json(ctx, 403, errorBody("This actor is disabled and its sheet is read-only."));
            return null;
        }
        return playerId;
    }

    private synchronized void handlePreferences(Context ctx, boolean save) {
        String playerId = requirePlayer(ctx);
        if (playerId == null) return;
        ctx.header("Cache-Control", "no-store");
        String authorization = ctx.header("Authorization");
        boolean dm = authorization != null && authorization.startsWith("Bearer ");
        Campaign.Player player = dm ? null : campaign.player(playerId);
        if (!dm && player == null) {
            json(ctx, 403, errorBody("The player account is no longer available."));
            return;
        }
        String previous = dm ? campaign.getDmDomainCardView() : player.getDomainCardView();
        if (save) {
            try {
                JsonNode request = MAPPER.readTree(ctx.body());
                JsonNode value = request == null ? null : request.get("domainCardView");
                if (value == null || !value.isTextual() || !List.of("card", "list").contains(value.asText())) {
                    json(ctx, 400, errorBody("Domain card view must be card or list."));
                    return;
                }
                if (dm) campaign.setDmDomainCardView(value.asText());
                else player.setDomainCardView(value.asText());
            } catch (IOException e) {
                json(ctx, 400, errorBody("Invalid preference request."));
                return;
            }
            try {
                store.save(campaign);
            } catch (IOException e) {
                if (dm) campaign.setDmDomainCardView(previous);
                else player.setDomainCardView(previous);
                json(ctx, 500, errorBody("Could not save the domain card view preference: " + e.getMessage()));
                return;
            }
        }
        String view = dm ? campaign.getDmDomainCardView() : player.getDomainCardView();
        json(ctx, 200, MAPPER.createObjectNode()
                .put("userId", dm ? "dm:" + campaign.getId() : playerId)
                .put("domainCardView", "list".equals(view) ? "list" : "card"));
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
        // The DM window polls the index; a content revision also covers changes made outside the editor.
        if (isDmCompendium(ctx)) {
            try {
                StringBuilder revisions = new StringBuilder();
                if (dir != null) for (String category : CatalogEditor.CATEGORIES) {
                    java.nio.file.Path file = dir.resolve(category + ".json");
                    if (java.nio.file.Files.isRegularFile(file))
                        revisions.append(category).append(CampaignStore.sha256(java.nio.file.Files.readAllBytes(file)));
                }
                body.put("revision", CampaignStore.sha256(revisions.toString().getBytes(StandardCharsets.UTF_8)));
            } catch (IOException e) {
                json(ctx, 500, errorBody("Could not read catalog revisions."));
                return;
            }
        }
        ctx.header("Cache-Control", "no-store");
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
            ctx.header("Cache-Control", "no-store");
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
        body.put("saved", true);
        body.put("name", actor.getName());
        body.put("testingCharacter", actor.isTestingCharacter());
        body.put("disabled", actor.isDisabled());
        ctx.header("Cache-Control", "no-store");
        try {
            Map<String, ObjectNode> grants = new java.util.LinkedHashMap<>();
            normalizeDeathChanges(grants);
            if (!grants.isEmpty()) {
                persistFeatureSheets(grants, null, false);
                for (var entry : grants.entrySet()) sendFeatureState(c.actor(entry.getKey()), entry.getValue());
            }
            String stored = store.loadSheet(c.getId(), actor.getId());
            if (stored == null) {
                body.putNull("sheet");
            } else {
                ObjectNode sheet = (ObjectNode) MAPPER.readTree(stored);
                refreshCatalogSheet(sheet);
                SpecialCardActions.migrateVitality(sheet);
                setSheetName(sheet, actor.getName());
                body.set("sheet", sheet);
            }
            body.put("version", sheetVersion(stored, actor));
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
            refreshCatalogSheet((ObjectNode) sheet);
            ObjectNode previous = storedSheet(actor.getId());
            String expected = ctx.header("If-Match");
            if (expected != null && !expected.equals(sheetVersion(store.loadSheet(c.getId(), actor.getId()), actor))) {
                ObjectNode conflict = errorBody("The character sheet changed in another window. Latest sheet loaded; please reapply your edit.");
                setSheetName(previous, actor.getName());
                conflict.set("sheet", previous);
                conflict.put("version", sheetVersion(store.loadSheet(c.getId(), actor.getId()), actor));
                json(ctx, 409, conflict);
                return;
            }
            if (sheet.path("inventoryRevision").asLong(0) != previous.path("inventoryRevision").asLong(0)
                    || sheet.path("compendiumRevision").asLong(0) != previous.path("compendiumRevision").asLong(0)
                    || sheet.path("featureRevision").asLong(0) != previous.path("featureRevision").asLong(0)) {
                ObjectNode conflict = errorBody("Inventory, domain cards or feature state changed on the host. Refresh the sheet before saving.");
                conflict.set("sheet", previous);
                json(ctx, 409, conflict);
                return;
            }
            CopycatCards.preserveSave((ObjectNode) sheet, previous);
            SpecialCardActions.preserveSave((ObjectNode) sheet, previous, actor.isTestingCharacter(), expected != null);
            String problem = CharacterSheetRules.problem(sheet, previous, actor.isTestingCharacter(), c.getFear());
            if (problem != null) {
                json(ctx, 403, errorBody(problem));
                return;
            }
            ObjectNode saved = (ObjectNode) sheet;
            JsonNode requestedName = saved.get("name");
            if (requestedName != null && (!requestedName.isTextual() || requestedName.asText().isBlank())) {
                json(ctx, 400, errorBody("Character name must not be empty."));
                return;
            }
            String name = actor.getName();
            if (requestedName != null && (expected != null || !requestedName.asText().equals(previous.path("name").asText()))
                    && !requestedName.asText().equals(actor.getName())) {
                name = requestedName.asText().trim();
            }
            setSheetName(saved, name);
            boolean renamed = !name.equals(actor.getName());
            refreshCatalogSheet(saved);
            validateDeathSave(saved, previous);
            WitchEffects.validateSave(saved, previous);
            InvisibilityEffects.validateSave(saved, previous);
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
                persistFeatureSheets(changes, report, report != null, null, renamed ? actor : null, name);
                for (var entry : changes.entrySet()) sendFeatureState(c.actor(entry.getKey()), entry.getValue());
                if (report != null) deliver(report, null, false);
            } else {
                if (renamed) store.renameActor(c, actor, name, saved);
                else store.saveSheet(c.getId(), actor.getId(), saved.toString());
                host.actorSheetChanged(actor.getId());
            }
            if (renamed || !sheetPronouns(saved).equals(sheetPronouns(previous))) {
                publishRoster();
                host.rosterChanged();
            }
            if (renamed) {
                host.campaignChanged(c);
            }
            stockChanged(actor.getId());
            handleSheet(ctx);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            json(ctx, 400, errorBody("Invalid sheet"));
        } catch (IllegalArgumentException e) {
            json(ctx, 400, errorBody(e.getMessage()));
        } catch (IOException e) {
            json(ctx, 500, errorBody("Could not read or save the character sheet."));
        }
    }

    private static String sheetVersion(String stored, Campaign.Actor actor) {
        return "\"" + CampaignStore.sha256((stored == null ? "actor:" + actor.getName() : stored)
                .getBytes(StandardCharsets.UTF_8)) + "\"";
    }

    static void setSheetName(ObjectNode sheet, String name) {
        sheet.put("name", name);
        if (sheet.path("description") instanceof ObjectNode description) description.put("Name", name);
    }

    /** Rename from the desktop actor tree under the same lock as sheet saves. */
    public synchronized void renameActor(String actorId, String name) throws IOException {
        Campaign.Actor actor = campaign.actor(actorId);
        if (actor == null) throw new IllegalArgumentException("Unknown actor.");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Enter a name.");
        String stored = store.loadSheet(campaign.getId(), actorId);
        ObjectNode sheet = stored == null ? null : (ObjectNode) MAPPER.readTree(stored);
        if (sheet != null) setSheetName(sheet, name);
        store.renameActor(campaign, actor, name, sheet);
        sheetChanged(actorId);
        publishRoster();
        host.campaignChanged(campaign);
    }

    public synchronized void sheetChanged(String actorId) {
        Campaign.Actor actor = campaign.actor(actorId);
        if (actor == null) return;
        host.actorSheetChanged(actorId);
        String message = MAPPER.createObjectNode().put("type", "sheetChanged").put("actorId", actorId).toString();
        for (Client client : clients.values())
            if (client.fullSheets && actor.getOwnerId() != null && actor.getOwnerId().equals(client.playerId)
                    && (client.dmActorId == null || client.dmActorId.equals(actorId)))
                sendTo(client.ctx, message);
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
        boolean completedRest = rest == oldRest + 1
                || saved.path("shortRestsInRow").asInt() == previous.path("shortRestsInRow").asInt() + 1
                && saved.path("shortRestsInRow").asInt() <= 3;
        if (!completedRest) {
            for (JsonNode effect : previous.path("effects")) {
                if (effect.path("lockedHp").asInt() <= 0) continue;
                boolean retained = false;
                for (JsonNode current : saved.path("effects"))
                    if (effect.path("id").equals(current.path("id")) && effect.equals(current)) retained = true;
                if (!retained) throw new IllegalArgumentException("Volatile Toxins HP stays locked until your next rest.");
            }
        }
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
        JsonNode copycat = CopycatCards.activeCard(sheet, "Copycat");
        if (copycat != null && copycat.path("borrowedCard").isObject()) {
            String name = copycat.path("borrowedCard").path("card").path("name").asText();
            String key = "card:Copycat/" + name;
            JsonNode borrowed = featureSource(sheet, key);
            if (borrowed.path("automation").path("deathMove").isObject())
                sources.add(new DeathMoves.Source(key, name, borrowed.path("automation"), copycat));
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

    private synchronized void handleCopycatCandidates(Context ctx) {
        String playerId = requirePlayer(ctx);
        if (playerId == null) return;
        try {
            Campaign.Actor borrower = ownedActor(playerId, ctx.pathParam("id"));
            if (borrower.isDisabled()) throw new IllegalArgumentException("This actor is disabled.");
            ObjectNode own = storedSheet(borrower.getId());
            if (CopycatCards.activeCard(own, "Copycat") == null)
                throw new IllegalArgumentException("Copycat must be in your active loadout.");
            Map<String, ObjectNode> unique = new java.util.TreeMap<>();
            for (Campaign.Actor actor : campaign.getActors()) {
                if (actor.isDisabled() || actor.getId().equals(borrower.getId())) continue;
                for (JsonNode loaded : storedSheet(actor.getId()).path("loadout")) {
                    String name = loaded.path("name").asText();
                    JsonNode catalog = catalogDomainCard(name);
                    if (catalog == null || "Copycat".equals(name) || catalog.path("level").asInt() < 1 || catalog.path("level").asInt() > 8) continue;
                    ObjectNode candidate = MAPPER.createObjectNode().put("actorId", actor.getId()).put("actorName", actor.getName());
                    ObjectNode card = CopycatCards.prepare(catalog, own);
                    // Candidates display ordinary rules; the embedded rules are namespaced only on activation.
                    card.set("automation", catalog.path("automation"));
                    if (catalog.has("spells")) card.set("spells", catalog.path("spells"));
                    candidate.set("card", card);
                    unique.putIfAbsent(name, candidate);
                }
            }
            ArrayNode cards = MAPPER.createArrayNode();
            unique.values().stream().sorted(java.util.Comparator.comparingInt(card -> card.path("card").path("level").asInt()))
                    .forEach(cards::add);
            ObjectNode response = MAPPER.createObjectNode();
            response.set("cards", cards);
            ctx.header("Cache-Control", "no-store");
            json(ctx, 200, response);
        } catch (IllegalArgumentException e) { json(ctx, 400, errorBody(e.getMessage())); }
        catch (IOException e) { json(ctx, 500, errorBody("Could not load Copycat candidates.")); }
    }

    private void copycatAction(JsonNode request, Campaign.Actor borrower, ObjectNode sheet, String key,
                               JsonNode automation, JsonNode definition) throws IOException {
        if (!"card:Copycat".equals(key) || request.has("roll"))
            throw new IllegalArgumentException("Choose a card using Copycat on your character sheet.");
        JsonNode cardNode = CopycatCards.activeCard(sheet, "Copycat");
        if (!(cardNode instanceof ObjectNode copycat))
            throw new IllegalArgumentException("Copycat must be in your active loadout.");
        Campaign.Actor source = campaign.actor(request.path("copyActorId").asText());
        String name = request.path("copyCardName").asText();
        if (source == null || source.isDisabled() || source.getId().equals(borrower.getId()) || "Copycat".equals(name))
            throw new IllegalArgumentException("Choose a domain card from another actor's active loadout.");
        if (CopycatCards.activeCard(storedSheet(source.getId()), name) == null)
            throw new IllegalArgumentException("The source has vaulted or removed that card. Choose again.");
        JsonNode catalog = catalogDomainCard(name);
        if (catalog == null) throw new IllegalArgumentException("The copied card is no longer in the domain catalog.");
        int hope = CopycatCards.cost(catalog.path("level").asInt());
        applyFeatureCost(sheet, MAPPER.createObjectNode().put("hope", hope));
        JsonNode uses = definition.has("uses") ? definition.path("uses") : automation.path("uses");
        if (!uses.isObject() || uses.path("max").asInt() != 1 || !"longRest".equals(uses.path("per").asText()))
            throw new IllegalArgumentException("Copycat requires its once-per-long-rest catalog limit.");
        useFeatureLimit(sheet, key, uses);
        CopycatCards.clear(sheet, copycat);
        ObjectNode embedded = CopycatCards.prepare(catalog, sheet);
        ObjectNode borrowed = copycat.putObject("borrowedCard").put("sourceActorId", source.getId()).put("sourceActorName", source.getName());
        borrowed.set("card", embedded);
        List<JsonNode> copiedSources = new ArrayList<>();
        copiedSources.add(embedded);
        embedded.path("spells").forEach(copiedSources::add);
        for (JsonNode copiedSource : copiedSources)
            for (JsonNode resource : copiedSource.path("automation").path("resources")) {
                String id = resource.path("id").asText();
                int initial = Math.max(0, featureAmount(resource.path("initial"), sheet));
                sheet.withObject("/resources").put(id, initial);
            }
        sheet.put("compendiumRevision", sheet.path("compendiumRevision").asLong() + 1);
        ObjectNode report = chatMessage(borrower.getOwnerId(), campaign.player(borrower.getOwnerId()).getName(), null,
                "ACTION USED\nCopycat - " + name + "\n" + borrower.getName() + "\n• Spent " + hope
                        + " Hope and the once-per-long-rest use. Copied from " + source.getName()
                        + "; ends on any rest or when that actor vaults the original card.");
        commitFeatureSheets(Map.of(borrower.getId(), sheet), report, true);
        sendFeatureState(borrower, sheet);
        deliver(report, null, false);
    }

    private void resolveSpecialCardAction(JsonNode request, Campaign.Actor actor, ObjectNode sheet,
                                          JsonNode source, String key, JsonNode automation, JsonNode definition) throws IOException {
        String kind = definition.path("specialAction").asText();
        String expected = switch (kind) {
            case "vitality" -> "Vitality";
            case "massDisguise" -> "Mass Disguise";
            case "nightTerror" -> "Night Terror";
            default -> throw new IllegalArgumentException("Unknown special card action.");
        };
        if (!key.equals("card:" + expected) || request.has("roll")
                || java.util.stream.StreamSupport.stream(sheet.path("loadout").spliterator(), false)
                .noneMatch(card -> expected.equals(card.path("name").asText())))
            throw new IllegalArgumentException("This special card must be in your active loadout.");
        useFeatureLimit(sheet, key, automation.path("uses"));
        SpecialCardActions.Result result = SpecialCardActions.apply(sheet, actor, campaign, kind, request, config.maxDice(), diceRandom);
        DeathMoves.sync(sheet);
        sheet.put("featureRevision", sheet.path("featureRevision").asLong() + 1);
        if (sheet.toString().getBytes(StandardCharsets.UTF_8).length > MAX_SHEET_BYTES)
            throw new IllegalArgumentException("The character sheet is too large.");
        ObjectNode report = result.roll() == null
                ? actionReportMessage(actor, source, expected, result.lines(), null)
                : featureRollMessage(actor, source, expected + " damage", result.roll());
        if (result.roll() != null) {
            report.put("source", "damage");
            report.putObject("featureRoll").put("name", expected).put("result", result.roll().total())
                    .put("consequence", String.join(" ", result.lines()));
        }
        store.saveSpecialCardAction(campaign.getId(), actor.getId(), sheet, report, result.fear(), result.countdowns());
        campaign.setFear(result.fear());
        campaign.setCountdowns(result.countdowns());
        if (current != null) {
            ObjectNode next = current.deepCopy();
            next.put("fear", result.fear());
            next.set("countdowns", MAPPER.valueToTree(result.countdowns()));
            publish(next, currentMapKey);
        }
        host.campaignChanged(campaign);
        sendFeatureState(actor, sheet);
        deliver(report, null, false);
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
            JsonNode automation = key.startsWith("effect:") ? source.path("automation")
                    : catalogAutomation(key, source.path("name").asText(), source.path("automation"));
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
            if (definition.has("specialAction")) {
                resolveSpecialCardAction(request, sourceActor, sourceSheet, source, key, automation, definition);
                json(ctx, 200, MAPPER.createObjectNode().put("ok", true));
                return;
            }
            if (definition.path("copycat").asBoolean()) {
                copycatAction(request, sourceActor, sourceSheet, key, automation, definition);
                json(ctx, 200, MAPPER.createObjectNode().put("ok", true));
                return;
            }
            boolean conditionalTrigger = false;
            if (hasRoll) for (JsonNode trigger : automation.path("conditionalRollTriggers"))
                if (actionId.equals(trigger.path("id").asText())) conditionalTrigger = true;
            if (conditionalTrigger || key.startsWith("card:")) {
                boolean active = key.startsWith("card:") && java.util.stream.StreamSupport.stream(sourceSheet.path("loadout").spliterator(), false)
                        .anyMatch(card -> key.equals("card:" + card.path("name").asText())
                                || key.startsWith("card:" + card.path("name").asText() + "/"));
                if (!active) throw new IllegalArgumentException("This card must be in your active loadout.");
                JsonNode condition = definition.path("loadoutDomain");
                if (condition.isObject()) {
                    String domain = condition.path("domain").asText();
                    int minimum = condition.path("min").asInt();
                    long count = java.util.stream.StreamSupport.stream(sourceSheet.path("loadout").spliterator(), false)
                            .filter(card -> domain.equalsIgnoreCase(card.path("domain").asText())).count();
                    if (domain.isBlank() || minimum < 1 || count < minimum)
                        throw new IllegalArgumentException("This action requires more matching domain cards in the active loadout.");
                }
            }
            if (definition.path("automatic").asBoolean())
                throw new IllegalArgumentException("This feature resolves automatically when its roll is made.");
            if (definition.path("vaultSource").asBoolean() && (!hasRoll || !key.startsWith("card:") || key.contains("/") && !key.startsWith("card:Copycat/")))
                throw new IllegalArgumentException("Vaulting requires this card's successful reaction roll.");
            if (definition.path("choices").isArray()) {
                JsonNode choice = null;
                for (JsonNode candidate : definition.path("choices"))
                    if (candidate.path("id").asText().equals(request.path("choiceId").asText())) choice = candidate;
                if (choice == null) throw new IllegalArgumentException("Choose one of this feature's roll benefits.");
                ObjectNode chosen = definition.deepCopy();
                chosen.put("label", definition.path("label").asText() + ": " + choice.path("label").asText());
                if (choice.has("addDice")) chosen.set("addDice", choice.path("addDice"));
                definition = chosen;
            }
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
            else if (!definition.has("target") && !definition.has("check") && !definition.has("chatBenefits")
                    && !definition.has("patronDice") && !definition.path("spendFavor").asBoolean()
                    && !definition.path("menacingReach").asBoolean()
                    && !List.of("talisman", "lifeblood").contains(definition.path("witchAction").asText()))
                throw new IllegalArgumentException("This feature action must be made from its character sheet.");

            if (definition.path("halfProficiencyDamage").asBoolean()) {
                if (clients.values().stream().noneMatch(client -> playerId.equals(client.playerId)))
                    throw new IllegalArgumentException("Reconnect before rolling Glancing Blow damage. No Stress was paid.");
                resolveGlancingBlow(sourceActor, sourceSheet, source, key, automation, definition, originalRoll, request.path("damage"));
                json(ctx, 200, MAPPER.createObjectNode().put("ok", true));
                return;
            }

            String targetMode = definition.path("target").asText("");
            boolean lifeWard = "lifeWard".equals(automation.path("deathMove").path("kind").asText());
            List<Campaign.Actor> targets;
            if (lifeWard) {
                if (!key.startsWith("card:Copycat/") && (!sourceSheet.path("loadout").isArray() || java.util.stream.StreamSupport.stream(sourceSheet.path("loadout").spliterator(), false)
                        .noneMatch(card -> source.path("name").asText().equals(card.path("name").asText()))))
                    throw new IllegalArgumentException("Life Ward must be in the active loadout.");
                Campaign.Actor target = c.actor(request.path("targetActorId").asText());
                if (target == null || target.isDisabled() || target.getOwnerId() == null || c.player(target.getOwnerId()) == null
                        || target.getId().equals(sourceActor.getId()))
                    throw new IllegalArgumentException("Choose an ally within Close range for Life Ward.");
                if (!request.path("confirmRange").asBoolean())
                    throw new IllegalArgumentException("Confirm that the ally is within Close range.");
                targets = List.of(target);
            } else {
                targets = resolveFeatureTargets(c, sourceActor, request.path("targetActorId").asText(""), targetMode);
            }
            if (definition.path("vaultSource").asBoolean()) {
                ArrayNode loadout = sourceSheet.withArray("loadout");
                int index = -1;
                for (int i = 0; i < loadout.size(); i++)
                    if ((key.startsWith("card:Copycat/") ? "card:Copycat" : key).equals("card:" + loadout.get(i).path("name").asText())) index = i;
                if (index < 0) throw new IllegalArgumentException("This card is no longer in your active loadout.");
                sourceSheet.withArray("vault").add(loadout.remove(index));
                sourceSheet.put("compendiumRevision", sourceSheet.path("compendiumRevision").asLong() + 1);
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
            DiceRoller.Result banishRoll = null;
            DiceRoller.Result utilityRoll = null;
            if (definition.path("sendRoll").isObject()) {
                String expression = definition.path("sendRoll").path("dice").asText()
                        .replace("@total_proficiency", String.valueOf(featureAmount(MAPPER.getNodeFactory().textNode("@total_proficiency"), sourceSheet)));
                utilityRoll = DiceRoller.roll(expression, config.maxDice(), diceRandom);
            }
            if (definition.path("damage").isObject()) {
                JsonNode damage = definition.path("damage");
                if (!damage.path("fixed").asBoolean())
                    throw new IllegalArgumentException("Only fixed damage is supported for this feature reaction.");
                damageRoll = DiceRoller.roll(damage.path("dice").asText(""), config.maxDice(), diceRandom);
            }
            List<String> reportLines = new ArrayList<>();
            int fearGrant = definition.path("giveFear").asInt();
            if (fearGrant < 0 || fearGrant > 1) throw new IllegalArgumentException("Invalid GM Fear grant.");
            if (fearGrant > 0) reportLines.add("Gave the GM " + fearGrant + " Fear.");
            if (definition.path("report").isObject()) {
                JsonNode report = definition.path("report");
                int amount = report.has("dice")
                        ? DiceRoller.roll(report.path("dice").asText(), config.maxDice(), diceRandom).total()
                        : report.has("amount") ? featureAmount(report.path("amount"), sourceSheet) : 0;
                reportLines.add(report.path("text").asText().replace("{amount}", String.valueOf(Math.max(0, amount)))
                        .replace("{result}", utilityRoll == null ? "" : String.valueOf(utilityRoll.total())));
            }
            if (InvisibilityEffects.isCast(key, definition)) {
                if (!hasRoll || targets.size() != 1)
                    throw new IllegalArgumentException("Invisibility requires a successful cast and one recipient.");
                int tokens = CharacterSheetRules.spellcastTotal(sourceSheet, c.getFear());
                InvisibilityEffects.prepareCast(sourceSheet, key, tokens);
                reportLines.add("Prepared " + Math.max(0, tokens) + " Invisibility tokens on the caster's card.");
            }
            if (definition.path("banishDifficulty").asBoolean()) {
                int count = CharacterSheetRules.spellcastTotal(sourceSheet, c.getFear());
                if (count < 1) throw new IllegalArgumentException("Banish requires a positive Spellcast trait to roll d20s.");
                banishRoll = DiceRoller.roll(count + "d20", config.maxDice(), diceRandom);
                int highest = banishRoll.rolls().stream().mapToInt(Integer::intValue).max().orElseThrow();
                ((ObjectNode) source).put("banishDifficulty", highest);
                sourceSheet.put("compendiumRevision", sourceSheet.path("compendiumRevision").asLong(0) + 1);
                reportLines.add("Banish difficulty: " + highest + ".");
            }
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
            if (definition.path("spendHopeAmount").asBoolean()) {
                JsonNode amount = request.path("amount");
                if (!amount.isInt() || amount.asInt() < 1)
                    throw new IllegalArgumentException("Choose a positive whole number of Hope.");
                applyFeatureCost(sourceSheet, MAPPER.createObjectNode().put("hope", amount.asInt()));
                reportLines.add("Spent " + amount.asInt() + " Hope.");
            }
            JsonNode uses = definition.path("uses").isObject() ? definition.path("uses")
                    : definition.path("usesSource").asBoolean(true) ? automation.path("uses") : MAPPER.nullNode();
            useFeatureLimit(sourceSheet, key, effectiveFeatureUses(sourceSheet, key, uses));
            resolveWarlockAction(sourceSheet, definition, request, reportLines);
            if ("lifeblood".equals(definition.path("witchAction").asText())) {
                removeEffects(sourceSheet, "Lifeblood Talisman source", null, null);
                sourceSheet.withArray("effects").addObject().put("id", "blood-" + java.util.UUID.randomUUID())
                        .put("witchGrant", "lifeblood").put("name", "Lifeblood Talisman source")
                        .put("kind", "buff").put("until", "manual").put("sourceKey", key);
                reportLines.add("Created a Lifeblood Talisman. Give it from Loot to transfer its protection.");
            }
            if ("talisman".equals(definition.path("witchAction").asText())) {
                JsonNode amount = request.path("amount");
                if (!amount.isInt() || amount.asInt() < 1)
                    throw new IllegalArgumentException("Choose a positive whole number of Hope for the Enchanted Talisman.");
                applyFeatureCost(sourceSheet, MAPPER.createObjectNode().put("hope", amount.asInt()));
                sourceSheet.withObject("/resources").put("enchanted-talisman", amount.asInt());
                removeEffects(sourceSheet, "Enchanted Talisman source", null, null);
                sourceSheet.withArray("effects").addObject().put("id", "witch-" + java.util.UUID.randomUUID())
                        .put("witchGrant", "talisman").put("name", "Enchanted Talisman source")
                        .put("kind", "buff").put("until", "rest").put("sourceKey", key)
                        .put("tokenResource", "enchanted-talisman");
                reportLines.add("Spent " + amount.asInt() + " Hope to create an Enchanted Talisman with "
                        + amount.asInt() + " tokens.");
            }

            if (hasRoll && definition.has("damageDice")) {
                editDamageDice(originalRoll, definition, request, reportLines);
            }
            if (hasRoll && definition.has("rerollResourceDice")) {
                WarriorAutomation.rerollOnes(originalRoll, definition.path("rerollResourceDice").asText(),
                        config.maxDice(), diceRandom, reportLines);
            }

            if (definition.path("check").isObject()) {
                JsonNode check = definition.path("check");
                DiceRoller.Result result = DiceRoller.roll(check.path("dice").asText(""), config.maxDice(), diceRandom);
                int die = result.rolls().isEmpty() ? 0 : result.rolls().getFirst();
                if (check.has("gain")) {
                    if (die == check.path("success").asInt()) {
                        ArrayNode steps = MAPPER.createArrayNode();
                        steps.addObject().put("op", "gain").put("resource", check.path("gain").asText()).put("amount", 1);
                        applyFeatureSteps(sourceSheet, steps, actionLabel, sourceActor, key, reportLines);
                    } else reportLines.add("No resource gained.");
                    if (originalRoll != null) {
                        originalRoll.withArray("featureUses").add(sourceActor.getId() + ":" + key + ":" + actionId);
                        sourceSheet.put("featureRevision", sourceSheet.path("featureRevision").asLong() + 1);
                        store.saveSheetsAndRoll(c.getId(), changedSheets, originalRoll);
                        broadcastRollUpdate(originalRoll);
                    } else commitFeatureSheets(changedSheets);
                    sendFeatureState(sourceActor, sourceSheet);
                    publishFeatureCheck(sourceActor, source, actionLabel, result, String.join(" ", reportLines));
                    json(ctx, 200, MAPPER.createObjectNode().put("ok", true));
                    return;
                }
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
                if (!"effect".equals(step.path("op").asText())
                        || !(effect.path("endOnSourceSevere").asBoolean() || effect.path("exclusiveSource").asBoolean())) continue;
                for (Campaign.Actor actor : c.getActors()) {
                    if (actor.isDisabled()) continue;
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

            if (hasRoll && definition.path("swapDuality").asBoolean()) {
                ArrayNode dice = originalRoll.withArray("rolls");
                JsonNode hope = dice.get(0);
                JsonNode fear = dice.get(1);
                dice.set(0, fear);
                dice.set(1, hope);
                originalRoll.put("outcome", fear.asInt() > hope.asInt() ? "hope" : "fear");
                int index = 0;
                for (JsonNode detail : originalRoll.path("breakdown")) {
                    if (!(detail instanceof ObjectNode part)) continue;
                    ArrayNode values = part.withArray("rolls");
                    int before = 0, after = 0;
                    for (int i = 0; i < values.size(); i++, index++) {
                        before += values.get(i).asInt();
                        if (index < 2) values.set(i, dice.get(index));
                        after += values.get(i).asInt();
                    }
                    part.put("total", part.path("total").asInt() + after - before);
                }
                reportLines.add("Swapped the original Hope and Fear dice; the total is unchanged.");
            }
            if (hasRoll && definition.has("rerollDualityDie"))
                rerollFeatureDualityDie(originalRoll, definition.path("rerollDualityDie").asText(), reportLines);
            if (hasRoll && definition.has("advanceEffect")) {
                String name = definition.path("advanceEffect").asText();
                ObjectNode effect = null;
                for (JsonNode candidate : sourceSheet.path("effects"))
                    if (name.equals(candidate.path("name").asText()) && candidate instanceof ObjectNode object) effect = object;
                if (effect == null || !effect.path("counter").isObject())
                    throw new IllegalArgumentException("The tracked effect is no longer active.");
                ObjectNode counter = effect.withObject("/counter");
                int max = counter.path("max").asInt();
                int best = 0;
                var levels = counter.path("maxByLevel").fields();
                while (levels.hasNext()) {
                    var entry = levels.next();
                    int threshold = Integer.parseInt(entry.getKey());
                    if (threshold <= sourceSheet.path("level").asInt(1) && threshold > best) {
                        best = threshold;
                        max = entry.getValue().asInt();
                    }
                }
                int value = counter.path("value").asInt() + 1;
                if (value > max) {
                    removeEffects(sourceSheet, name, null, null);
                    reportLines.add("Ended " + name + " (die exceeded d" + max + ").");
                } else {
                    counter.put("value", value);
                    reportLines.add(name + " die increased to " + value + " (d" + max + ").");
                }
            }
            if (hasRoll && definition.has("outcome")) {
                String outcome = definition.path("outcome").asText("");
                if (!"hope".equals(outcome) && !"fear".equals(outcome))
                    throw new IllegalArgumentException("Invalid outcome conversion.");
                originalRoll.put("outcome", outcome);
            }
            if (hasRoll && definition.has("dualityDieBonus")) {
                JsonNode bonus = definition.path("dualityDieBonus");
                String die = bonus.path("die").asText();
                int value = bonus.path("value").asInt();
                if (!"duality".equals(originalRoll.path("kind").asText())
                        || !List.of("hope", "fear").contains(die) || value <= 0 || value > 100)
                    throw new IllegalArgumentException("Invalid Duality die bonus.");
                String effectName = definition.path("endEffect").asText("");
                String bonusUse = sourceActor.getId() + ":effectBonus:" + effectName;
                if (!effectName.isEmpty() && containsText(originalRoll.withArray("featureUses"), bonusUse))
                    throw new IllegalArgumentException("This effect was already used on that roll.");
                ArrayNode dice = originalRoll.withArray("rolls");
                if (!originalRoll.has("originalDualityDice"))
                    originalRoll.set("originalDualityDice", MAPPER.createArrayNode().add(dice.get(0)).add(dice.get(1)));
                if (!originalRoll.path("breakdown").isArray() || originalRoll.path("breakdown").isEmpty()) {
                    ArrayNode details = originalRoll.putArray("breakdown");
                    String expression = originalRoll.path("expression").asText();
                    String hopeExpression = expression.startsWith("1d20+") ? "1d20" : "1d12";
                    String fearExpression = expression.matches("^1d(?:12|20)\\+1d20.*") ? "1d20" : "1d12";
                    details.addObject().put("label", "Hope die").put("expression", hopeExpression)
                            .put("total", dice.get(0).asInt()).putArray("rolls").add(dice.get(0));
                    details.addObject().put("label", "Fear die").put("expression", fearExpression)
                            .put("total", dice.get(1).asInt()).putArray("rolls").add(dice.get(1));
                    ObjectNode other = details.addObject().put("label", "Other modifiers and dice").put("expression", "")
                            .put("total", originalRoll.path("total").asInt() - dice.get(0).asInt() - dice.get(1).asInt());
                    ArrayNode otherDice = other.putArray("rolls");
                    for (int i = 2; i < dice.size(); i++) otherDice.add(dice.get(i));
                }
                int target = "hope".equals(die) ? 0 : 1;
                int oldValue = dice.get(target).asInt();
                dice.set(target, MAPPER.getNodeFactory().numberNode(Math.addExact(oldValue, value)));
                originalRoll.put("total", Math.addExact(originalRoll.path("total").asInt(), value));
                originalRoll.put("expression", originalRoll.path("expression").asText() + "+" + value);
                int hope = dice.get(0).asInt(), fear = dice.get(1).asInt();
                if (hope == fear) originalRoll.remove("outcome");
                else originalRoll.put("outcome", hope > fear ? "hope" : "fear");
                int index = 0;
                for (JsonNode detail : originalRoll.path("breakdown")) {
                    if (!(detail instanceof ObjectNode part)) continue;
                    ArrayNode values = part.withArray("rolls");
                    for (int i = 0; i < values.size(); i++, index++) {
                        if (index == target) {
                            values.set(i, dice.get(target));
                            part.put("total", Math.addExact(part.path("total").asInt(), value));
                            part.withArray("notes").addObject().put("label", actionLabel)
                                    .put("impact", "+" + value + " to " + die + " die (rolled " + oldValue + ")");
                        }
                    }
                }
                reportLines.add("Added " + value + " to the " + die + " die (rolled " + oldValue + ").");
                if (!effectName.isEmpty()) originalRoll.withArray("featureUses").add(bonusUse);
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
                String added = extra.rolls().isEmpty() ? String.valueOf(extra.total()) : extra.expression() + " = " + extra.total();
                reportLines.add("Added " + added + " to the original roll.");
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
                        reportLines.add(("actor".equals(targetMode) || "party".equals(targetMode) || !target.equals(sourceActor)
                                ? target.getName() + ": " : "") + line);
                }
            }

            if (hasRoll) {
                ArrayNode featureUses = originalRoll.withArray("featureUses");
                String use = sourceActor.getId() + ":" + key + ":" + actionId;
                if (!definition.path("repeatable").asBoolean() && !containsText(featureUses, use)) featureUses.add(use);
                if (definition.has("exclusiveRollGroup"))
                    featureUses.add(sourceActor.getId() + ":" + key + ":group:" + definition.path("exclusiveRollGroup").asText());
            }

            ArrayNode benefits = actionBenefits(sourceSheet, key, definition);
            ObjectNode benefitReport = null;
            if (!benefits.isEmpty()) {
                if (reportLines.isEmpty()) reportLines.add("Used " + actionLabel + ".");
                benefitReport = actionReportMessage(sourceActor, source, actionReportTitle(sourceSheet, source, actionLabel), reportLines, null);
                benefitReport.put("actorId", sourceActor.getId());
                attachActionBenefits(benefitReport, sourceActor, sourceSheet, definition, benefits);
            }
            if (definition.has("chatDamage") || fearGrant > 0) {
                if (benefitReport == null)
                    benefitReport = actionReportMessage(sourceActor, source, actionReportTitle(sourceSheet, source, actionLabel), reportLines, originalRoll);
                benefitReport.put("actorId", sourceActor.getId());
                if (definition.has("chatDamage")) {
                    ObjectNode button = benefitReport.putObject("actionDamage").put("label", "Damage");
                    button.set("damage", definition.path("chatDamage"));
                }
            }
            if (fearGrant > 0) {
                List<String> deathReports = normalizeDeathChanges(changedSheets);
                for (ObjectNode sheet : changedSheets.values()) {
                    sheet.put("featureRevision", sheet.path("featureRevision").asLong() + 1);
                    if (sheet.toString().getBytes(StandardCharsets.UTF_8).length > MAX_SHEET_BYTES)
                        throw new IllegalArgumentException("The character sheet is too large.");
                }
                int fear = Math.addExact(c.getFear(), fearGrant);
                List<ObjectNode> reports = new ArrayList<>();
                reports.add(benefitReport);
                if (!deathReports.isEmpty()) reports.add(deathStateMessage("dm", "DM", "Party", deathReports));
                store.saveFearReward(c.getId(), changedSheets, originalRoll, reports, fear);
                c.setFear(fear);
                if (current != null) {
                    ObjectNode next = current.deepCopy();
                    next.put("fear", fear);
                    publish(next, currentMapKey);
                }
                host.campaignChanged(c);
                for (int i = 1; i < reports.size(); i++) deliver(reports.get(i), null, false);
            } else if (benefitReport != null && originalRoll != null) {
                List<String> deathReports = normalizeDeathChanges(changedSheets);
                if (!deathReports.isEmpty()) benefitReport.put("text", benefitReport.path("text").asText() + "\n" + String.join("\n", deathReports));
                persistFeatureSheets(changedSheets, originalRoll, false, benefitReport);
            } else commitFeatureSheets(changedSheets, benefitReport == null ? originalRoll : benefitReport, benefitReport != null);
            if (hasRoll) broadcastRollUpdate(originalRoll);
            for (var entry : changedSheets.entrySet()) {
                Campaign.Actor actor = c.actor(entry.getKey());
                if (actor != null) sendFeatureState(actor, entry.getValue());
            }
            if (benefitReport != null) {
                sessionRolls.add(rollKey(benefitReport.path("id").asLong(), benefitReport.path("ts").asLong()));
                deliver(benefitReport, null, false);
            } else if (banishRoll != null) {
                publishFeatureCheck(sourceActor, source, actionLabel, banishRoll, String.join(" ", reportLines));
            } else if (utilityRoll != null) {
                publishFeatureCheck(sourceActor, source, actionLabel, utilityRoll, String.join(" ", reportLines));
            } else if (damageRoll != null) {
                String damageType = definition.path("damage").path("type").asText("physical");
                ObjectNode damage = featureRollMessage(sourceActor, source, actionLabel, damageRoll);
                damage.put("source", "magic".equals(damageType) ? "damage,magicdamage" : "damage");
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

    private void resolveGlancingBlow(Campaign.Actor actor, ObjectNode sheet, JsonNode source, String key,
                                     JsonNode automation, JsonNode definition, ObjectNode attack, JsonNode requestedDamage) throws IOException {
        if (attack == null || !"duality".equals(attack.path("kind").asText())
                || !actor.getId().equals(attack.path("actorId").asText()))
            throw new IllegalArgumentException("Glancing Blow requires your own failed weapon attack.");
        String weaponName = attack.path("weapon").asText();
        JsonNode weapon = null;
        JsonNode replacement = null;
        for (JsonNode effect : sheet.path("effects"))
            if (effect.path("weapon").isObject()) { replacement = effect.path("weapon"); break; }
        if (replacement != null) {
            if (weaponName.equals(replacement.path("name").asText())) weapon = replacement;
        } else for (JsonNode candidate : sheet.path("activeWeapons"))
            if (weaponName.equals(candidate.path("name").asText())) { weapon = candidate; break; }
        if (weapon == null) throw new IllegalArgumentException("The attack's weapon is no longer equipped.");
        int proficiency = Math.max(1, (CharacterSheetRules.proficiencyTotal(sheet, campaign.getFear()) + 1) / 2);
        String expression = weapon.path("damage").asText().replaceAll("\\s", "");
        if (!expression.matches("(?i)(?:\\d*d\\d+\\+)*\\d*d\\d+(?:[+-]\\d+)?"))
            throw new IllegalArgumentException("The attack's weapon has no valid damage dice.");
        int extra = weapon.path("automation").path("damage").path("extraDice").asInt();
        int drop = weapon.path("automation").path("damage").path("dropLowest").asInt();
        boolean maximumOnes = weapon.path("automation").path("damage").path("maximumOnes").asBoolean();
        for (JsonNode effect : sheet.path("effects")) {
            JsonNode pool = effect.path("damage");
            if (pool.path("proficiencyOnly").asBoolean() && weapon.path("fixedDice").asBoolean()) continue;
            extra += pool.path("extraDice").asInt();
            drop += pool.path("dropLowest").asInt();
            maximumOnes |= pool.path("maximumOnes").asBoolean();
        }
        var matcher = java.util.regex.Pattern.compile("(?i)(\\d*)d(\\d+)").matcher(expression);
        StringBuilder formula = new StringBuilder();
        while (matcher.find()) {
            int count = (weapon.path("fixedDice").asBoolean()
                    ? matcher.group(1).isEmpty() ? 1 : Integer.parseInt(matcher.group(1)) : proficiency) + extra;
            if (count < 1 || drop < 0 || drop >= count)
                throw new IllegalArgumentException("The attack's weapon damage pool is invalid.");
            matcher.appendReplacement(formula, count + "d" + matcher.group(2) + (drop > 0 ? "kh" + (count - drop) : ""));
        }
        matcher.appendTail(formula);
        ResolvedDice resolved = null;
        DiceRoller.Result result;
        if (!requestedDamage.isMissingNode()) {
            validateGlancingDamage(sheet, weapon, proficiency, extra, drop, requestedDamage);
            ObjectNode damageRequest = requestedDamage.deepCopy();
            damageRequest.put("source", "damage");
            if (maximumOnes) damageRequest.withObject("/damageRules").put("maximumOnes", true);
            resolved = resolveDice(damageRequest);
            result = resolved.result();
        } else {
            result = DiceRoller.roll(formula.toString(), config.maxDice(), diceRandom);
            if (maximumOnes) result = DiceRoller.maximumOnes(result, config.maxDice());
        }
        applyFeatureCost(sheet, definition.path("cost"));
        useFeatureLimit(sheet, key, effectiveFeatureUses(sheet, key, automation.path("uses")));
        ObjectNode damage = featureRollMessage(actor, source, "Glancing Blow - " + weaponName + " damage", result);
        if (resolved != null) putDiceBreakdown(damage, resolved);
        boolean magic = "magic".equals(weapon.path("damageType").asText())
                || List.of(requestedDamage.path("source").asText().split(",")).contains("magicdamage");
        damage.put("source", magic ? "damage,magicdamage" : "damage").put("weapon", weaponName);
        damage.putObject("linkedAttack").set("id", attack.path("id"));
        damage.withObject("/linkedAttack").set("ts", attack.path("ts"));
        damage.putObject("featureRoll").put("name", source.path("name").asText()).put("result", result.total())
                .put("consequence", "Marked 1 Stress. " + (magic ? "Magic" : "Physical")
                        + " weapon damage using half Proficiency rounded up (" + proficiency + ").");
        String recipient = attack.path("toId").asText(null);
        putRecipient(damage, recipient);
        attack.withArray("featureUses").add(actor.getId() + ":" + key + ":" + definition.path("id").asText());
        Map<String, ObjectNode> changed = new java.util.LinkedHashMap<>();
        changed.put(actor.getId(), sheet);
        normalizeDeathChanges(changed);
        persistFeatureSheets(changed, attack, false, damage);
        sessionRolls.add(rollKey(damage.path("id").asLong(), damage.path("ts").asLong()));
        sendFeatureState(actor, sheet);
        broadcastRollUpdate(attack);
        deliver(damage, recipient, false);
    }

    private void validateGlancingDamage(ObjectNode sheet, JsonNode weapon, int proficiency, int extra, int drop,
                                        JsonNode damage) {
        JsonNode context = damage.path("weaponDamage");
        if (!damage.isObject() || !context.path("proficiency").isInt()
                || context.path("proficiency").asInt() != proficiency)
            throw new IllegalArgumentException("Proficiency changed. Reopen Glancing Blow's damage window.");
        int bonus = 0;
        boolean maximize = false;
        boolean doubleDamage = false;
        if (!context.path("options").isArray() || context.path("options").size() > 64)
            throw new IllegalArgumentException("Invalid damage options.");
        Set<String> selected = new java.util.HashSet<>();
        for (JsonNode option : context.path("options")) {
            String optionKey = option.path("key").asText().replaceFirst(":roll:[^:]+$", "");
            String id = option.path("id").asText();
            if (!selected.add(optionKey + ":" + id)) throw new IllegalArgumentException("Duplicate damage option.");
            // Prepared bonuses cannot change the weapon's proficiency, multiplier or maximization.
            if (id.startsWith("prepared:")) continue;
            JsonNode source;
            if (optionKey.startsWith("effect:")) {
                source = null;
                for (JsonNode effect : sheet.path("effects"))
                    if (optionKey.equals("effect:" + effect.path("id").asText())) source = effect;
                if (source == null) throw new IllegalArgumentException("The selected effect is no longer active.");
            } else source = featureSource(sheet, optionKey);
            JsonNode auto = optionKey.startsWith("effect:") ? source : catalogAutomation(optionKey,
                    source.path("name").asText(), source.path("automation"));
            JsonNode chosen = null;
            for (String field : List.of("rollOptions", "conditionalRollOptions"))
                for (JsonNode candidate : auto.path(field))
                    if (id.equals(candidate.path("id").asText()) && "damage".equals(candidate.path("roll").asText())) chosen = candidate;
            if (chosen == null) throw new IllegalArgumentException("The selected damage option is no longer available.");
            bonus += chosen.path("proficiency").asInt();
            maximize |= chosen.path("maximizeDie").asBoolean();
            doubleDamage |= chosen.path("multiplier").asInt() == 2;
        }
        // The normal dialog permits manually marking a critical, including critical-melee proficiency.
        int criticalBonus = 0;
        if (damage.path("contributions").findValuesAsText("label").stream().anyMatch(label -> label.equals("Critical hit (maximum dice)")))
            for (String field : List.of("features", "heritage"))
                for (JsonNode feature : sheet.path(field))
                    criticalBonus += feature.path("automation").path("criticalMelee").path("proficiency").asInt();
        if (!context.path("proficiencyBonus").isInt()
                || context.path("proficiencyBonus").asInt() != bonus + criticalBonus
                || context.path("multiplier").asInt() != (doubleDamage ? 2 : 1)
                || context.has("maximizeDie") && (!maximize || !context.path("maximizeDie").isInt()
                    || context.path("maximizeDie").asInt() < 0))
            throw new IllegalArgumentException("The weapon damage options changed. Reopen the damage window.");
        String expression = weapon.path("damage").asText().replaceAll("\\s", "");
        var matcher = java.util.regex.Pattern.compile("(?i)(\\d*)d(\\d+)").matcher(expression);
        StringBuilder base = new StringBuilder();
        while (matcher.find()) {
            int count = (weapon.path("fixedDice").asBoolean()
                    ? matcher.group(1).isEmpty() ? 1 : Integer.parseInt(matcher.group(1)) : proficiency + bonus + criticalBonus) + extra;
            if (count < 1 || drop < 0 || drop >= count) throw new IllegalArgumentException("Invalid weapon damage pool.");
            matcher.appendReplacement(base, count + "d" + matcher.group(2) + (drop > 0 ? "kh" + (count - drop) : ""));
        }
        matcher.appendTail(base);
        String expected = base.toString();
        var simple = java.util.regex.Pattern.compile("(?i)^(\\d+d\\d+(?:kh\\d+)?)([+-]\\d+)?$").matcher(expected);
        if (simple.matches()) expected = simple.group(1);
        int index = context.path("maximizeDie").asInt(-1);
        if (index >= 0) expected = maximizeWeaponDamageDie(expected, index);
        if (doubleDamage) expected = "(" + expected + ")*2";
        JsonNode first = damage.path("contributions").path(0);
        String expectedLabel = weapon.path("name").asText() + " damage (Proficiency " + (proficiency + bonus + criticalBonus) + ")";
        if (!expected.equals(first.path("expression").asText())
                || !expectedLabel.substring(0, Math.min(120, expectedLabel.length())).equals(first.path("label").asText()))
            throw new IllegalArgumentException("Weapon damage must use half current Proficiency rounded up.");
    }

    private String maximizeWeaponDamageDie(String expression, int index) {
        var matcher = java.util.regex.Pattern.compile("(?i)(\\d*)d(\\d+)(?:kh(\\d+))?").matcher(expression);
        StringBuilder result = new StringBuilder();
        int offset = 0;
        while (matcher.find()) {
            int count = matcher.group(1).isEmpty() ? 1 : Integer.parseInt(matcher.group(1));
            int sides = Integer.parseInt(matcher.group(2));
            int kept = matcher.group(3) == null ? count : Integer.parseInt(matcher.group(3));
            if (index >= offset && index < offset + count) {
                int remaining = count - 1;
                matcher.appendReplacement(result, (remaining > 0 ? remaining + "d" + sides
                        + (kept < count ? "kh" + (kept - 1) : "") + "+" : "") + sides);
            }
            offset += count;
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static final class FeatureIoException extends RuntimeException {
        FeatureIoException(IOException cause) { super(cause); }
    }

    private void resolveWarlockAction(ObjectNode sheet, JsonNode action, JsonNode request, List<String> report) {
        if (action.path("menacingReach").asBoolean()) {
            JsonNode primary = null;
            for (JsonNode weapon : sheet.path("activeWeapons"))
                if ("Primary".equals(weapon.path("slot").asText())) primary = weapon;
            if (primary == null) throw new IllegalArgumentException("Equip a primary weapon first.");
            List<String> ranges = List.of("Melee", "Very Close", "Close", "Far", "Very Far");
            int index = -1;
            for (int i = 0; i < ranges.size(); i++)
                if (ranges.get(i).equalsIgnoreCase(primary.path("range").asText())) index = i;
            if (index < 0) throw new IllegalArgumentException("The primary weapon has no supported range.");
            for (JsonNode effect : sheet.path("effects"))
                if ("Menacing Reach".equals(effect.path("name").asText()))
                    throw new IllegalArgumentException("Menacing Reach is already active.");
            String range = ranges.get(Math.min(index + 1, ranges.size() - 1));
            String text = primary.path("name").asText() + " reach: " + range
                    + " (normally " + primary.path("range").asText() + "). Ends after a successful attack with this weapon.";
            sheet.withArray("effects").addObject().put("id", "f" + java.util.UUID.randomUUID())
                    .put("name", "Menacing Reach").put("kind", "buff").put("until", "manual")
                    .put("source", "Menacing Reach").put("text", text);
            report.add("Spent 1 Favor. " + text);
            return;
        }
        String mode = action.path("patronDice").asText();
        if (mode.isEmpty() && !action.path("spendFavor").asBoolean()) return;
        JsonNode input = request.path("amount");
        boolean vengeance = "vengeance".equals(mode);
        int max = vengeance ? sheet.path("hp").path("slots").asInt() : sheet.path("resources").path("favor").asInt();
        if (!input.isInt() || input.asInt() < 1 || input.asInt() > max)
            throw new IllegalArgumentException("Enter a whole number from 1 to " + max + (vengeance ? " HP marked." : " Favor."));
        int amount = input.asInt();
        if (!vengeance) applyFeatureCost(sheet, MAPPER.createObjectNode().put("favor", amount));
        if (action.path("spendFavor").asBoolean()) {
            report.add("Spent " + amount + " Favor after a successful action roll with Hope; the target marks " + amount + " Stress.");
            return;
        }
        int sides = sheet.path("level").asInt(1) >= 5 ? 8 : 6;
        DiceRoller.Result dice = DiceRoller.roll(amount + "d" + sides, config.maxDice(), diceRandom);
        report.add("Spent " + (vengeance ? 1 : amount) + " Favor. Rolled " + dice.expression() + ": " + dice.rolls() + ".");
        int hits = (int) dice.rolls().stream().filter(n -> n >= 4).count();
        switch (mode) {
            case "heal" -> {
                ObjectNode hp = requireTrack(sheet, "hp");
                int before = hp.path("on").asInt();
                hp.put("on", Math.max(DeathMoves.hpHealingFloor(sheet), before - hits));
                report.add(hits + " results of 4+: cleared " + (before - hp.path("on").asInt()) + " HP.");
            }
            case "vengeance" -> report.add("For " + amount + " HP marked from the attack, the attacker marks " + hits + " HP (one per 4+).");
            case "ire" -> report.add("Highest result: " + dice.rolls().stream().mapToInt(Integer::intValue).max().orElseThrow()
                    + ". Target that many creatures within Close range; each target marks 1 HP.");
            default -> throw new IllegalArgumentException("Unsupported Patron Dice action.");
        }
    }

    private void editDamageDice(ObjectNode roll, JsonNode definition, JsonNode request, List<String> reportLines) {
        if (!roll.path("rolls").equals(request.path("expectedDice")))
            throw new IllegalArgumentException("The damage dice changed. Reopen this action from the updated roll.");
        JsonNode selection = request.path("dice");
        boolean reroll = "reroll".equals(definition.path("damageDice").asText());
        if (!selection.isArray() || selection.isEmpty() || selection.size() > config.maxDice() || !reroll && selection.size() != 1)
            throw new IllegalArgumentException(reroll ? "Select at least one damage die." : "Select exactly one damage die.");
        java.util.Set<Integer> selected = new java.util.HashSet<>();
        for (JsonNode index : selection) {
            if (!index.isInt() || index.asInt() < 0 || index.asInt() >= roll.path("rolls").size() || !selected.add(index.asInt()))
                throw new IllegalArgumentException("Select distinct dice from this damage roll.");
            int threshold = definition.path("damageDiceBelow").asInt();
            if (threshold > 0 && (roll.path("rolls").path(index.asInt()).asInt() <= 0
                    || roll.path("rolls").path(index.asInt()).asInt() >= threshold))
                throw new IllegalArgumentException("Only eligible damage dice below " + threshold + " can be rerolled.");
        }

        ArrayNode details = roll.withArray("breakdown");
        if (details.isEmpty()) {
            ObjectNode detail = details.addObject().put("label", "Damage").put("expression", roll.path("expression").asText())
                    .put("total", roll.path("total").asInt());
            detail.set("rolls", roll.path("rolls").deepCopy());
        }
        int offset = 0, delta = 0, changed = 0;
        for (JsonNode raw : details) {
            if (!(raw instanceof ObjectNode detail)) throw new IllegalArgumentException("Invalid damage breakdown.");
            List<Integer> values = new ArrayList<>();
            for (JsonNode value : detail.path("rolls")) values.add(value.asInt());
            List<Integer> local = new ArrayList<>();
            for (int i = 0; i < values.size(); i++) if (selected.contains(offset + i)) local.add(i);
            if (!local.isEmpty()) {
                if (detail.path("label").asText().startsWith("Combo Strike"))
                    throw new IllegalArgumentException("Combo Dice cannot be modified.");
                DiceRoller.Result before = new DiceRoller.Result(detail.path("expression").asText(), values, detail.path("total").asInt());
                DiceRoller.Result after = DiceRoller.editDice(before, local, reroll, config.maxDice(), diceRandom);
                for (int i : local) reportLines.add((reroll ? "Rerolled" : "Doubled") + " damage die " + (offset + i + 1)
                        + ": " + values.get(i) + " -> " + after.rolls().get(i) + ".");
                // Preserve non-dice adjustments already recorded on this contribution.
                int originalFormulaTotal = DiceRoller.totalFor(before.expression(), before.rolls(), config.maxDice());
                int difference = after.total() - originalFormulaTotal;
                delta = Math.addExact(delta, difference);
                detail.put("total", Math.addExact(before.total(), difference));
                detail.set("rolls", MAPPER.valueToTree(after.rolls()));
                detail.withArray("notes").addObject().put("label", definition.path("label").asText())
                        .put("impact", (reroll ? "Rerolled" : "Doubled") + " selected dice: " + values + " -> " + after.rolls());
                for (int i = 0; i < values.size(); i++) roll.withArray("rolls").set(offset + i, MAPPER.getNodeFactory().numberNode(after.rolls().get(i)));
                changed += local.size();
            }
            offset += values.size();
        }
        if (changed != selected.size()) throw new IllegalArgumentException("Some selected dice are no longer available.");
        int previousTotal = roll.path("total").asInt();
        int updatedTotal = Math.addExact(previousTotal, delta);
        roll.put("total", updatedTotal);
        reportLines.add("Damage total: " + previousTotal + " -> " + updatedTotal + ".");
    }

    private void rerollFeatureDualityDie(ObjectNode roll, String die, List<String> report) {
        if (!"duality".equals(roll.path("kind").asText()) || !List.of("hope", "fear").contains(die))
            throw new IllegalArgumentException("Choose a Hope or Fear die from a Duality roll.");
        int target = "hope".equals(die) ? 0 : 1;
        ArrayNode values = roll.withArray("rolls");
        if (values.size() < 2) throw new IllegalArgumentException("The original Duality dice are unavailable.");
        String expression = roll.path("expression").asText();
        int sides = target == 0 ? expression.startsWith("1d20+") ? 20 : 12
                : expression.matches("^1d(?:12|20)\\+1d20.*") ? 20 : 12;
        int previous = values.get(target).asInt();
        int replacement = DiceRoller.roll("1d" + sides, config.maxDice(), diceRandom).total();
        int delta = replacement - previous;
        values.set(target, MAPPER.getNodeFactory().numberNode(replacement));
        roll.put("total", Math.addExact(roll.path("total").asInt(), delta));
        int offset = 0;
        for (JsonNode raw : roll.path("breakdown")) {
            if (!(raw instanceof ObjectNode detail)) continue;
            ArrayNode dice = detail.withArray("rolls");
            if (target >= offset && target < offset + dice.size()) {
                dice.set(target - offset, MAPPER.getNodeFactory().numberNode(replacement));
                detail.put("total", detail.path("total").asInt() + delta);
                detail.withArray("notes").addObject().put("label", "Endless Charisma")
                        .put("impact", "Rerolled " + die + " die: " + previous + " -> " + replacement);
            }
            offset += dice.size();
        }
        int hope = values.get(0).asInt(), fear = values.get(1).asInt();
        if (hope == fear) roll.remove("outcome");
        else roll.put("outcome", hope > fear ? "hope" : "fear");
        report.add("Rerolled the " + die + " die: " + previous + " -> " + replacement + ". Total: " + roll.path("total").asInt() + ".");
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

    private void attachActionBenefits(ObjectNode message, Campaign.Actor source, ObjectNode sheet,
                                      JsonNode action, ArrayNode benefits) throws IOException {
        benefits = benefits.deepCopy();
        boolean self = false;
        for (JsonNode benefit : benefits) {
            self |= benefit.path("sourceOnly").asBoolean();
            for (JsonNode step : benefit.path("steps")) {
                if (!(step.path("effect") instanceof ObjectNode effect) || !effect.has("requiresSourceEffect")) continue;
                JsonNode marker = null;
                for (JsonNode candidate : sheet.path("effects"))
                    if (effect.path("requiresSourceEffect").asText().equals(candidate.path("name").asText())) marker = candidate;
                if (marker == null) throw new IllegalArgumentException("The source effect is no longer active.");
                effect.put("activationId", marker.path("id").asText());
            }
        }
        ObjectNode claims = message.putObject("actionBenefits").put("sessionId", sessionId);
        if (self || action.path("allowSourceOwner").asBoolean()) claims.put("allowSourceOwner", true);
        if (java.util.stream.StreamSupport.stream(benefits.spliterator(), false).anyMatch(b -> b.path("transferStress").asBoolean()))
            claims.put("stressCapacity", Math.max(0, sheet.path("stress").path("slots").asInt() - sheet.path("stress").path("on").asInt()));
        claims.set("options", benefits);
        claims.putArray("claimedBy");
        ArrayNode eligible = claims.putArray("actorIds");
        for (Campaign.Actor actor : campaign.getActors()) {
            if (actor.isDisabled() || !hasPlayerSheet(actor)) continue;
            boolean sourceActor = actor.getId().equals(source.getId());
            if (sourceActor && self || !sourceActor && (action.path("allowSourceOwner").asBoolean()
                    || !actor.getOwnerId().equals(source.getOwnerId()))) eligible.add(actor.getId());
        }
    }

    private void claimActionBenefit(JsonNode request, Campaign.Actor actor) throws IOException {
        ObjectNode sheet = storedSheet(actor.getId());
        JsonNode ref = request.path("roll");
        if (!sessionRolls.contains(rollKey(ref.path("id").asLong(-1), ref.path("ts").asLong(-1))))
            throw new IllegalArgumentException("That performance is not from this hosting session.");
        ObjectNode message = null;
        for (JsonNode entry : store.historyTail(campaign.getId(), Integer.MAX_VALUE))
            if (List.of("chat", "roll").contains(entry.path("type").asText()) && entry.path("id").equals(ref.path("id"))
                    && entry.path("ts").equals(ref.path("ts")) && entry instanceof ObjectNode object) {
                message = object.deepCopy();
                break;
            }
        if (message == null || !message.path("actionBenefits").isObject())
            throw new IllegalArgumentException("That message has no claimable benefit.");
        ObjectNode claims = (ObjectNode) message.path("actionBenefits");
        if (!containsText(claims.withArray("actorIds"), actor.getId())
                || !claims.path("allowSourceOwner").asBoolean() && actor.getOwnerId().equals(message.path("fromId").asText()))
            throw new IllegalArgumentException("Choose an eligible ally who received this performance.");
        if (containsText(claims.withArray("claimedBy"), actor.getOwnerId()))
            throw new IllegalArgumentException("You already claimed a benefit from this performance.");
        JsonNode benefit = null;
        for (JsonNode option : claims.path("options"))
            if (option.path("id").asText().equals(request.path("benefitId").asText())) { benefit = option; break; }
        if (benefit == null) throw new IllegalArgumentException("That benefit is not available.");
        if (benefit.path("sourceOnly").asBoolean() && !actor.getId().equals(message.path("actorId").asText()))
            throw new IllegalArgumentException("Only the original caster can recover this payment.");
        if (benefit.path("exclusiveClaim").asBoolean() && !claims.withArray("claimedBy").isEmpty())
            throw new IllegalArgumentException("Another ally has already accepted this action.");
        ObjectNode before = sheet.deepCopy();
        List<String> lines = new ArrayList<>();
        Map<String, ObjectNode> changed = new java.util.LinkedHashMap<>();
        changed.put(actor.getId(), sheet);
        Campaign.Actor caster = campaign.actor(message.path("actorId").asText());
        if (caster == null || caster.isDisabled())
            throw new IllegalArgumentException("The source actor is disabled or no longer available.");
        ObjectNode casterSheetForEffects = storedSheet(caster.getId());
        for (JsonNode step : benefit.path("steps")) {
            JsonNode effect = step.path("effect");
            if (!effect.has("requiresSourceEffect")) continue;
            boolean active = false;
            for (JsonNode marker : casterSheetForEffects.path("effects"))
                if (effect.path("requiresSourceEffect").equals(marker.path("name"))
                        && effect.path("activationId").equals(marker.path("id"))) active = true;
            if (!active) throw new IllegalArgumentException("That circle has ended. Use the invitation from the current cast.");
        }
        if (benefit.has("cost") && DeathMoves.recoverableHp(sheet) <= 0
                && java.util.stream.StreamSupport.stream(benefit.path("steps").spliterator(), false)
                .anyMatch(step -> "clear".equals(step.path("op").asText()) && "hp".equals(step.path("resource").asText())))
            throw new IllegalArgumentException("There is no recoverable HP to clear. No Stress was marked.");
        applyFeatureCost(sheet, benefit.path("cost"));
        if (benefit.path("transferStress").asBoolean()) {
            if (caster == null || caster.getId().equals(actor.getId()))
                throw new IllegalArgumentException("Choose another willing creature to transfer Stress.");
            ObjectNode casterSheet = storedSheet(caster.getId());
            ObjectNode from = requireTrack(sheet, "stress");
            ObjectNode to = requireTrack(casterSheet, "stress");
            int max = Math.min(from.path("on").asInt(), Math.max(0, to.path("slots").asInt() - to.path("on").asInt()));
            JsonNode input = request.path("amount");
            if (!input.isInt() || input.asInt() < 1 || input.asInt() > max)
                throw new IllegalArgumentException("Transfer a whole number from 1 to " + max + " Stress.");
            int amount = input.asInt();
            from.put("on", from.path("on").asInt() - amount);
            to.put("on", to.path("on").asInt() + amount);
            int hope = gainTrack(casterSheet, "hope", amount);
            lines.add("Transferred " + amount + " Stress from " + actor.getName() + " to " + caster.getName()
                    + "; " + caster.getName() + " gained " + hope + " Hope.");
            changed.put(caster.getId(), casterSheet);
        } else {
            applyFeatureSteps(sheet, benefit.path("steps"), benefit.path("label").asText(),
                    caster == null ? actor : caster, "", lines);
        }
        if (sheet.equals(before)) throw new IllegalArgumentException("No benefit available: already at maximum Hope or nothing to clear.");
        if (benefit.path("report").has("text")) lines.add(benefit.path("report").path("text").asText());
        claims.withArray("claimedBy").add(actor.getOwnerId());
        ObjectNode source = MAPPER.createObjectNode().put("name", benefit.path("label").asText());
        ObjectNode report = actionReportMessage(actor, source, benefit.path("label").asText(), lines, null);
        List<String> deathReports = normalizeDeathChanges(changed);
        if (!deathReports.isEmpty()) report.put("text", report.path("text").asText() + "\n" + String.join("\n", deathReports));
        persistFeatureSheets(changed, message, false, report);
        for (var entry : changed.entrySet()) sendFeatureState(campaign.actor(entry.getKey()), entry.getValue());
        if ("roll".equals(message.path("type").asText())) broadcastRollUpdate(message);
        else broadcastChatUpdate(message);
        deliver(report, null, false);
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
        if (recipient == null || recipient.isDisabled()) throw new IllegalArgumentException("The resting character is disabled or no longer exists.");
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
        if (key.startsWith("card:Copycat/")) {
            JsonNode copycat = CopycatCards.activeCard(sheet, "Copycat");
            if (copycat == null || !copycat.path("borrowedCard").isObject())
                throw new IllegalArgumentException("Copycat has no active copied card. Copy it again after resting.");
            JsonNode borrowed = copycat.path("borrowedCard");
            String name = borrowed.path("card").path("name").asText();
            Campaign.Actor original = campaign.actor(borrowed.path("sourceActorId").asText());
            try {
                if (original == null || original.isDisabled() || CopycatCards.activeCard(storedSheet(original.getId()), name) == null)
                    throw new IllegalArgumentException("The copied card's source has vaulted or removed it.");
            } catch (IOException e) { throw new FeatureIoException(e); }
            JsonNode card = borrowed.path("card");
            try {
                JsonNode definition = catalogDomainCard(name);
                if (definition == null) throw new IllegalArgumentException("The copied card is no longer in the domain catalog.");
                ObjectNode canonical = CopycatCards.prepare(definition, sheet);
                if (card.has("banishDifficulty")) canonical.set("banishDifficulty", card.get("banishDifficulty"));
                ((ObjectNode) borrowed).set("card", canonical);
                card = canonical;
            } catch (IOException e) { throw new FeatureIoException(e); }
            String cardKey = "card:Copycat/" + name;
            if (key.equals(cardKey)) return card;
            for (JsonNode spell : card.path("spells"))
                if (key.equals(cardKey + "/" + spell.path("name").asText())) return spell;
            throw new IllegalArgumentException("That spell is not part of the copied card.");
        }
        int separator = key.indexOf(':');
        if (separator <= 0) throw new IllegalArgumentException("Invalid feature source.");
        String kind = key.substring(0, separator);
        String name = key.substring(separator + 1);
        if ("effect".equals(kind)) {
            JsonNode effect = null;
            for (JsonNode candidate : sheet.path("effects"))
                if (name.equals(candidate.path("id").asText())) effect = candidate;
            if (effect == null) throw new IllegalArgumentException("The effect is no longer active.");
            Campaign.Actor effectSource = campaign.actor(effect.path("sourceActorId").asText());
            if (effectSource != null && effectSource.isDisabled())
                throw new IllegalArgumentException("The effect's source actor is disabled.");
            if (effect.path("partyDowntimeGrant").asBoolean() && !effect.hasNonNull("sourceActorId")) {
                boolean activeSource = false;
                try {
                    for (Campaign.Actor actor : campaign.getActors()) {
                        if (actor.isDisabled() || store.loadSheet(campaign.getId(), actor.getId()) == null) continue;
                        for (JsonNode feature : storedSheet(actor.getId()).path("features"))
                            if (feature.path("automation").path("partyDowntime").asBoolean()
                                    && effect.path("name").asText().equals(feature.path("name").asText()))
                                activeSource = true;
                    }
                } catch (IOException e) { throw new FeatureIoException(e); }
                if (!activeSource) throw new IllegalArgumentException("The effect's source actor is disabled or unavailable.");
            }
            for (String field : List.of("features", "heritage")) {
                for (JsonNode feature : sheet.path(field)) {
                    JsonNode auto = catalogAutomation("feat:" + feature.path("name").asText(),
                            feature.path("name").asText(), feature.path("automation"));
                    JsonNode pool = auto.path("dicePool");
                    if (pool.isObject() && pool.path("id").asText().equals(effect.path("dicePool").asText())) {
                        int value = effect.path("counter").path("value").asInt();
                        if (value < 1 || value > pool.path("sides").asInt())
                            throw new IllegalArgumentException("Invalid stored pool die.");
                        ObjectNode source = ((ObjectNode) feature).deepCopy();
                        ObjectNode trigger = source.putObject("automation").putArray("rollTriggers").addObject();
                        trigger.put("id", "spend").put("label", "Prayer die: +" + value)
                                .put("on", "any").put("audience", "any").put("addDice", String.valueOf(value))
                                .put("endEffect", effect.path("name").asText()).put("usesSource", false);
                        return source;
                    }
                    for (JsonNode action : auto.path("actions"))
                        for (JsonNode step : action.path("steps")) {
                            JsonNode def = step.path("effect");
                            if (effect.path("name").asText().equals(def.path("name").asText()) && def.path("rollTriggers").isArray()) {
                                ObjectNode source = ((ObjectNode) feature).deepCopy();
                                source.putObject("automation").set("rollTriggers", def.path("rollTriggers"));
                                return source;
                            }
                        }
                }
            }
            throw new IllegalArgumentException("The effect's feature is no longer available.");
        }
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
                    if ("card".equals(kind) && name.startsWith(value.path("name").asText() + "/")) {
                        String spellName = name.substring(value.path("name").asText().length() + 1);
                        for (JsonNode spell : value.path("spells"))
                            if (spellName.equals(spell.path("name").asText())) return spell;
                    }
                }
            } else if (values.isObject() && name.equals(values.path("name").asText())) {
                return values;
            }
        }
        if ("feat".equals(kind)) {
            for (JsonNode effect : sheet.path("effects"))
                for (JsonNode feature : effect.path("features"))
                    if (name.equals(feature.path("name").asText())) return feature;
        }
        throw new IllegalArgumentException("Feature not found on the source character.");
    }

    private JsonNode featureDefinition(JsonNode automation, String actionId, boolean trigger) {
        for (String field : trigger ? List.of("rollTriggers", "conditionalRollTriggers") : List.of("actions", "conditionalActions")) {
            for (JsonNode item : automation.path(field))
                if (actionId.equals(item.path("id").asText())) return item;
        }
        return null;
    }

    private JsonNode catalogAutomation(String key, String name, JsonNode fallback) {
        if (key.startsWith("card:Copycat/")) return fallback;
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
                if (key.startsWith("card:") && key.contains("/") && file.getFileName().toString().equals(preferred)) {
                    String nestedName = key.substring(5);
                    int separator = nestedName.indexOf('/');
                    for (JsonNode book : root.path("entries")) {
                        if (!nestedName.substring(0, separator).equals(book.path("name").asText())) continue;
                        for (JsonNode spell : book.path("spells"))
                            if (nestedName.substring(separator + 1).equals(spell.path("name").asText()))
                                return spell.path("automation");
                    }
                }
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
            JsonNode originalDice = roll.path("originalDualityDice");
            int rolledHope = originalDice.isArray() ? originalDice.path(0).asInt(0) : hope;
            int rolledFear = originalDice.isArray() ? originalDice.path(1).asInt(0) : fear;
            String expression = roll.path("expression").asText();
            int hopeSides = expression.startsWith("1d20+") ? 20 : 12;
            int fearSides = expression.matches("^1d(?:12|20)\\+1d20.*") ? 20 : 12;
            if (rolledHope < 1 || rolledHope > hopeSides || rolledFear < 1 || rolledFear > fearSides)
                throw new IllegalArgumentException("This roll has no valid Hope and Fear dice.");
            boolean critical = hope == fear;
            boolean withHope = "hope".equals(roll.path("outcome").asText(hope > fear ? "hope" : "fear"));
            if (!critical && !withHope)
                throw new IllegalArgumentException("Only Hope or critical results have a reward.");
            if ("slayer".equals(request.path("reward").asText())) {
                JsonNode slayerSource = null;
                JsonNode reward = null;
                for (JsonNode feature : sheet.path("features")) {
                    JsonNode auto = catalogAutomation("feat:" + feature.path("name").asText(),
                            feature.path("name").asText(), feature.path("automation"));
                    JsonNode candidate = auto.path("hopeReward");
                    if ("slayer-dice".equals(candidate.path("resource").asText())
                            && List.of(roll.path("source").asText("").split(",")).contains(candidate.path("roll").asText())) {
                        slayerSource = feature;
                        reward = candidate;
                    }
                }
                if (slayerSource == null || critical)
                    throw new IllegalArgumentException("This roll has no Slayer Die reward.");
                String resource = reward.path("resource").asText();
                JsonNode definition = findResourceDefinition(sheet, resource);
                if (definition == null || sheet.path("resources").path(resource).asInt() >= featureAmount(definition.path("max"), sheet))
                    throw new IllegalArgumentException("The Slayer Dice pool is full or unavailable.");
                List<String> lines = new ArrayList<>();
                ArrayNode steps = MAPPER.createArrayNode();
                steps.addObject().put("op", "gain").put("resource", resource).set("amount", reward.path("amount"));
                applyFeatureSteps(sheet, steps, "Slayer", actor, "feat:Slayer", lines);
                lines.add("Instead of gaining Hope from this roll.");
                roll.put("rewardClaimed", true);
                ObjectNode report = actionReportMessage(actor, slayerSource, "Slayer", lines, roll);
                sheet.put("featureRevision", sheet.path("featureRevision").asLong() + 1);
                store.saveSheetsAndResults(campaign.getId(), Map.of(actor.getId(), sheet), roll, List.of(report));
                sendFeatureState(actor, sheet);
                broadcastRollUpdate(roll);
                deliver(report, report.path("toId").asText(null), false);
                json(ctx, 200, MAPPER.createObjectNode().put("ok", true));
                return;
            }
            if ("favor".equals(request.path("reward").asText())) {
                JsonNode favorSource = null;
                JsonNode favorReward = null;
                for (JsonNode feature : sheet.path("features")) {
                    JsonNode auto = catalogAutomation("feat:" + feature.path("name").asText(),
                            feature.path("name").asText(), feature.path("automation"));
                    JsonNode reward = auto.path("hopeReward");
                    if (reward.path("favor").asInt() > 0
                            && List.of(roll.path("source").asText("").split(",")).contains(reward.path("roll").asText())) {
                        favorSource = feature;
                        favorReward = reward;
                    }
                }
                if (favorSource == null || critical)
                    throw new IllegalArgumentException("Favor requires a successful action roll with Hope.");
                if (roll.has("difficulty") && roll.path("total").asDouble() < roll.path("difficulty").asDouble())
                    throw new IllegalArgumentException("Favor requires a successful action roll with Hope.");
                List<String> lines = new ArrayList<>();
                ArrayNode steps = MAPPER.createArrayNode();
                steps.addObject().put("op", "gain").put("resource", "favor").set("amount", favorReward.path("favor"));
                applyFeatureSteps(sheet, steps, "Favor", actor, "feat:Favor", lines);
                lines.add("Instead of gaining Hope from the successful action roll.");
                roll.put("rewardClaimed", true);
                ObjectNode report = actionReportMessage(actor, favorSource, "Favor", lines, roll);
                sheet.put("featureRevision", sheet.path("featureRevision").asLong() + 1);
                store.saveSheetsAndResults(campaign.getId(), Map.of(actor.getId(), sheet), roll, List.of(report));
                sendFeatureState(actor, sheet);
                broadcastRollUpdate(roll);
                deliver(report, report.path("toId").asText(null), false);
                json(ctx, 200, MAPPER.createObjectNode().put("ok", true));
                return;
            }
            if (request.has("reward")) {
                if (!"removeFear".equals(request.path("reward").asText()))
                    throw new IllegalArgumentException("Unknown roll reward.");
                JsonNode alternative = null;
                JsonNode alternativeSource = null;
                for (JsonNode feature : sheet.path("features")) {
                    JsonNode auto = catalogAutomation("feat:" + feature.path("name").asText(),
                            feature.path("name").asText(), feature.path("automation"));
                    JsonNode reward = auto.path("hopeReward");
                    if (reward.path("removeFear").asInt() > 0 && List.of(roll.path("source").asText("").split(",")).contains(reward.path("roll").asText())) {
                        alternative = reward;
                        alternativeSource = feature;
                    }
                }
                if (alternative == null || critical)
                    throw new IllegalArgumentException("This roll has no Fear-removal reward.");
                if (roll.has("difficulty") && roll.path("total").asDouble() < roll.path("difficulty").asDouble())
                    throw new IllegalArgumentException("Ethereal Visage requires a successful Presence roll.");
                int amount = alternative.path("removeFear").asInt();
                if (amount < 1 || campaign.getFear() < amount)
                    throw new IllegalArgumentException("There is not enough GM Fear to remove.");
                int remaining = campaign.getFear() - amount;
                roll.put("rewardClaimed", true);
                sheet.put("featureRevision", sheet.path("featureRevision").asLong() + 1);
                ObjectNode report = actionReportMessage(actor, alternativeSource, alternativeSource.path("name").asText(),
                        List.of("Removed " + amount + " GM Fear instead of gaining Hope (GM Fear: " + remaining + ")."), roll);
                store.saveFearReward(campaign.getId(), Map.of(actor.getId(), sheet), roll, List.of(report), remaining);
                campaign.setFear(remaining);
                if (current != null) {
                    ObjectNode next = current.deepCopy();
                    next.put("fear", remaining);
                    publish(next, currentMapKey);
                }
                host.campaignChanged(campaign);
                sendFeatureState(actor, sheet);
                broadcastRollUpdate(roll);
                deliver(report, report.path("toId").asText(null), false);
                json(ctx, 200, MAPPER.createObjectNode().put("ok", true));
                return;
            }
            JsonNode meleeReward = critical ? criticalMeleeReward(sheet, roll) : MAPPER.createObjectNode();
            int gained = gainTrack(sheet, "hope", 1 + meleeReward.path("hope").asInt());
            int cleared = 0;
            if (critical) {
                ObjectNode stress = requireTrack(sheet, "stress");
                cleared = Math.min(1 + meleeReward.path("stress").asInt(), Math.max(0, stress.path("on").asInt()));
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

    private JsonNode criticalMeleeReward(ObjectNode sheet, ObjectNode roll) {
        if (!List.of(roll.path("source").asText("").split(",")).contains("attack")) return MAPPER.createObjectNode();
        if (!matchingWeaponRange(sheet, roll, "Melee")) return MAPPER.createObjectNode();
        for (JsonNode feature : sheet.path("features")) {
            JsonNode auto = catalogAutomation("feat:" + feature.path("name").asText(), feature.path("name").asText(), feature.path("automation"));
            if (auto.path("criticalMelee").isObject()) return auto.path("criticalMelee");
        }
        return MAPPER.createObjectNode();
    }

    private boolean matchingWeaponRange(ObjectNode sheet, ObjectNode roll, String range) {
        for (JsonNode weapon : sheet.path("activeWeapons")) {
            if (weapon.path("name").asText().equals(roll.path("weapon").asText())
                    && range.equalsIgnoreCase(weapon.path("range").asText())) return true;
        }
        JsonNode companion = sheet.path("companion");
        if (!companion.isObject() || companion.path("unavailable").asBoolean()
                || companion.path("stress").path("slots").asInt() <= companion.path("stress").path("on").asInt()) return false;
        boolean granted = false;
        for (JsonNode feature : sheet.path("features")) {
            JsonNode auto = catalogAutomation("feat:" + feature.path("name").asText(), feature.path("name").asText(), feature.path("automation"));
            if (auto.path("companion").isObject()) granted = true;
        }
        return granted && List.of(roll.path("source").asText("").split(",")).contains("companion")
                && (companion.path("name").asText() + ": " + companion.path("attack").asText()).equals(roll.path("weapon").asText())
                && range.equalsIgnoreCase(companion.path("range").asText());
    }

    private void validateTrigger(Campaign.Actor sourceActor, String key, JsonNode trigger, ObjectNode roll) throws IOException {
        String recipient = roll.path("toId").asText("");
        if (roll.path("secret").asBoolean() || !recipient.isEmpty()
                && !sourceActor.getOwnerId().equals(recipient) && !sourceActor.getOwnerId().equals(roll.path("fromId").asText()))
            throw new IllegalArgumentException("This roll is not visible to you.");
        String rolledActorId = roll.path("actorId").asText("");
        Campaign.Actor rolledActor = campaign.actor(rolledActorId);
        if (rolledActor != null && rolledActor.isDisabled())
            throw new IllegalArgumentException("This roll's actor is disabled.");
        if (rolledActor == null && !"any".equals(trigger.path("on").asText()))
            throw new IllegalArgumentException("This roll is not linked to a character.");
        String audience = trigger.path("audience").asText("self");
        if ("self".equals(audience) && !sourceActor.getId().equals(rolledActorId))
            throw new IllegalArgumentException("This feature only applies to your own character's roll.");
        if ("ally".equals(audience) && sourceActor.getId().equals(rolledActorId))
            throw new IllegalArgumentException("This feature only applies to another character's roll.");
        String source = roll.path("source").asText("");
        List<String> tags = List.of(source.split(","));
        if (trigger.path("criticalOnly").asBoolean()
                && (roll.path("rolls").path(0).asInt() < 1
                    || roll.path("rolls").path(0).asInt() != roll.path("rolls").path(1).asInt()
                    || !tags.contains("attack")))
            throw new IllegalArgumentException("This feature requires a critical attack.");
        if (trigger.path("failedOnly").asBoolean()
                && (roll.path("rolls").path(0).asInt() > 0
                    && roll.path("rolls").path(0).asInt() == roll.path("rolls").path(1).asInt()
                    || roll.has("difficulty") && roll.path("total").asDouble() >= roll.path("difficulty").asDouble()))
            throw new IllegalArgumentException("This feature requires a failed roll.");
        if (trigger.has("rerollResourceDice") && (!(tags.contains("attack") || tags.contains("damage"))
                || !WarriorAutomation.hasOnes(roll, trigger.path("rerollResourceDice").asText())))
            throw new IllegalArgumentException("This attack or damage roll has no Slayer Dice showing 1.");
        boolean magic = tags.contains("magicdamage") || tags.contains("spelldamage");
        if (!magic && rolledActor != null) {
            ObjectNode rolledSheet = storedSheet(rolledActorId);
            for (JsonNode weapon : rolledSheet.path("activeWeapons"))
                if (weapon.path("name").asText().equals(roll.path("weapon").asText())
                        && "magic".equals(weapon.path("damageType").asText())) magic = true;
        }
        if (trigger.path("magicOnly").asBoolean() && !magic)
            throw new IllegalArgumentException("This feature requires magic damage.");
        if (trigger.path("magicAction").asBoolean() && !(tags.contains("spellcast") || tags.contains("attack") && magic))
            throw new IllegalArgumentException("This feature requires a Spellcast roll or a magic-damage attack.");
        if (trigger.has("damageDice") && ("duality".equals(roll.path("kind").asText()) || !tags.contains("damage")))
            throw new IllegalArgumentException("Select dice from a damage roll.");
        String on = trigger.path("on").asText("");
        for (JsonNode excluded : trigger.path("excludeRolls")) {
            if (List.of(source.split(",")).contains(excluded.asText()))
                throw new IllegalArgumentException("This feature does not apply to that roll.");
        }
        boolean duality = "duality".equals(roll.path("kind").asText());
        if ("duality".equals(on) && !duality)
            throw new IllegalArgumentException("This feature requires a Duality roll.");
        if ("trait".equals(on) && tags.stream().noneMatch(tag -> tag.startsWith("trait:")))
            throw new IllegalArgumentException("This feature requires a trait roll.");
        if (!"any".equals(on) && !"duality".equals(on) && !"trait".equals(on) && !List.of(source.split(",")).contains(on))
            throw new IllegalArgumentException("This feature does not apply to that roll.");
        if ("companion".equals(on)) {
            ObjectNode sheet = storedSheet(sourceActor.getId());
            JsonNode companion = sheet.path("companion");
            if (!companion.isObject() || companion.path("unavailable").asBoolean()
                    || companion.path("stress").path("on").asInt() >= companion.path("stress").path("slots").asInt())
                throw new IllegalArgumentException("Your companion is unavailable until the next long rest.");
        }
        if (trigger.path("weaponAttack").asBoolean()
                && (!List.of(source.split(",")).contains("attack") || roll.path("weapon").asText("").isBlank()))
            throw new IllegalArgumentException("This feature requires a weapon attack.");
        if (trigger.path("primaryWeapon").asBoolean()) {
            boolean matching = false;
            for (JsonNode weapon : storedSheet(sourceActor.getId()).path("activeWeapons"))
                if ("Primary".equals(weapon.path("slot").asText())
                        && weapon.path("name").asText().equals(roll.path("weapon").asText())) matching = true;
            if (!matching) throw new IllegalArgumentException("This feature requires a primary weapon attack.");
        }
        if (trigger.has("weapon") && !trigger.path("weapon").asText().equals(roll.path("weapon").asText()))
            throw new IllegalArgumentException("This feature requires a " + trigger.path("weapon").asText() + " attack.");
        if (trigger.has("weaponRange")) {
            if (!matchingWeaponRange(storedSheet(sourceActor.getId()), roll, trigger.path("weaponRange").asText()))
                throw new IllegalArgumentException("This feature requires a " + trigger.path("weaponRange").asText() + " weapon attack.");
        }
        if (trigger.path("weaponRanges").isArray()) {
            boolean matching = false;
            ObjectNode sheet = storedSheet(sourceActor.getId());
            for (JsonNode range : trigger.path("weaponRanges"))
                if (matchingWeaponRange(sheet, roll, range.asText())) matching = true;
            if (!matching) throw new IllegalArgumentException("This feature requires a matching weapon range.");
        }
        ArrayNode uses = roll.withArray("featureUses");
        String usageKey = sourceActor.getId() + ":" + key + ":" + trigger.path("id").asText();
        if (!trigger.path("repeatable").asBoolean() && containsText(uses, usageKey))
            throw new IllegalArgumentException("This feature was already used on that roll.");
        if (trigger.has("exclusiveRollGroup") && containsText(uses,
                sourceActor.getId() + ":" + key + ":group:" + trigger.path("exclusiveRollGroup").asText()))
            throw new IllegalArgumentException("Another choice from this feature was already used on that roll.");
        if (!duality) {
            if (!List.of("damage", "any").contains(on) || !"always".equals(trigger.path("when").asText("always"))
                    || trigger.has("outcome") || trigger.has("reroll") || trigger.has("dualityDieBonus") || trigger.path("swapDuality").asBoolean())
                throw new IllegalArgumentException("This feature requires a Duality roll.");
            return;
        }
        int hope = roll.path("rolls").path(0).asInt(Integer.MIN_VALUE);
        int fear = roll.path("rolls").path(1).asInt(Integer.MIN_VALUE);
        if (hope < 1 || fear < 1) throw new IllegalArgumentException("The original roll has no valid Hope and Fear dice.");
        if (trigger.path("swapDuality").asBoolean() && hope == fear)
            throw new IllegalArgumentException("Swapping matching Hope and Fear dice has no effect.");
        if (trigger.path("successIfKnown").asBoolean() && roll.path("difficulty").isNumber()
                && hope != fear && roll.path("total").asDouble() < roll.path("difficulty").asDouble())
            throw new IllegalArgumentException("This feature requires a successful roll.");
        String actualOutcome = roll.path("outcome").asText("");
        if (!"hope".equals(actualOutcome) && !"fear".equals(actualOutcome))
            actualOutcome = hope == fear ? "crit" : (fear > hope ? "fear" : "hope");
        String when = trigger.path("when").asText("always");
        if (trigger.path("spendFavor").asBoolean() && roll.has("difficulty")
                && roll.path("total").asDouble() < roll.path("difficulty").asDouble())
            throw new IllegalArgumentException("Diminish My Foes requires a successful action roll with Hope.");
        if ("success".equals(when)) {
            JsonNode difficulty = trigger.has("difficulty") ? trigger.path("difficulty") : roll.path("difficulty");
            if (!difficulty.isNumber() || hope != fear && roll.path("total").asDouble() < difficulty.asDouble())
                throw new IllegalArgumentException("This feature requires a successful roll.");
        }
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
                try { if (!actor.isDisabled() && hasPlayerSheet(actor)) targets.add(actor); }
                catch (IOException e) { throw new FeatureIoException(e); }
            }
            return targets;
        }
        if ("party".equals(mode)) {
            Set<String> online = new LinkedHashSet<>();
            clients.values().forEach(client -> online.add(client.playerId));
            return c.getActors().stream().filter(a -> !a.isDisabled() && a.getOwnerId() != null && online.contains(a.getOwnerId())).toList();
        }
        if ("anyActor".equals(mode)) {
            Campaign.Actor target = c.actor(targetActorId);
            if (target == null || target.isDisabled()) throw new IllegalArgumentException("Choose an active actor as the target.");
            return List.of(target);
        }
        if ("actor".equals(mode)) {
            Campaign.Actor target = c.actor(targetActorId);
            if (target == null || target.isDisabled() || target.getOwnerId() == null || campaign.player(target.getOwnerId()) == null
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
        if (!uses.isObject()) return;
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
                if ("clear".equals(operation)) track.put("on", Math.max("hp".equals(resource) ? DeathMoves.hpHealingFloor(sheet) : 0,
                        before - Math.max(0, amount)));
                else track.put("on", Math.min(track.path("slots").asInt(), before + Math.max(0, amount)));
                int changed = Math.abs(before - track.path("on").asInt());
                if ("clear".equals(operation) && "hp".equals(resource)) {
                    report.add(hpRecoveryReport(changed, step.path("amount"), amount));
                }
                else if (changed > 0) report.add(("clear".equals(operation) ? "Cleared " : "gain".equals(operation) ? "Gained " : "Marked ")
                        + changed + " " + ("hp".equals(resource) ? "HP" : "stress".equals(resource) ? "Stress" : resource) + ".");
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
                report.add("Gained " + name + "." + (effect.path("text").asText().isBlank() ? "" : " " + effect.path("text").asText()));
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

    static String hpRecoveryReport(int changed, JsonNode amountSpec, int amount) {
        String expression = amountSpec.asText().strip();
        boolean fixed = amountSpec.isNumber() || expression.matches("[+-]?\\d+");
        return "Cleared " + changed + " HP" + (fixed ? "" : " (" + expression + " = " + amount + ")") + ".";
    }

    private int featureAmount(JsonNode amount, ObjectNode sheet) {
        if (amount.isIntegralNumber() && amount.canConvertToInt()) return amount.asInt();
        if (amount.isTextual()) {
            String text = amount.asText().strip();
            if ("all".equals(text)) return 0;
            if (text.matches("\\d*d\\d+(?:[+-]\\d+)?"))
                return DiceRoller.roll(text, config.maxDice(), diceRandom).total();
            if ("@level".equals(text)) return Math.max(1, sheet.path("level").asInt(1));
            if ("@proficiency".equals(text)) return Math.max(0, sheet.path("proficiency").path("on").asInt());
            if ("@total_proficiency".equals(text)) return CharacterSheetRules.proficiencyTotal(sheet, campaign.getFear());
            if ("@spellcast".equals(text)) {
                return CharacterSheetRules.spellcastTotal(sheet, campaign.getFear());
            }
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
            boolean match = (effect.path("endOnSourceSevere").asBoolean() || effect.path("exclusiveSource").asBoolean())
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
            if (actor.isDisabled()) continue;
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
        Map<String, ObjectNode> party = new java.util.LinkedHashMap<>();
        for (Campaign.Actor actor : campaign.getActors()) {
            if (actor.isDisabled() || !hasPlayerSheet(actor)) continue;
            party.put(actor.getId(), changes.containsKey(actor.getId()) ? changes.get(actor.getId()) : storedSheet(actor.getId()));
        }
        Map<String, ObjectNode> before = new java.util.LinkedHashMap<>();
        party.forEach((id, sheet) -> before.put(id, sheet.deepCopy()));
        Map<String, ObjectNode> previousParty = new java.util.LinkedHashMap<>();
        for (String id : party.keySet()) previousParty.put(id, storedSheet(id));
        WitchEffects.synchronize(party, previousParty);
        for (ObjectNode sheet : party.values()) {
            if (!(sheet.path("effects") instanceof ArrayNode effects)) continue;
            for (int i = effects.size() - 1; i >= 0; i--) {
                JsonNode effect = effects.get(i);
                if (!effect.has("requiresSourceEffect")) continue;
                Campaign.Actor source = campaign.actor(effect.path("sourceActorId").asText());
                if (source != null && source.isDisabled()) continue;
                ObjectNode caster = party.get(effect.path("sourceActorId").asText());
                boolean active = false;
                if (caster != null) for (JsonNode marker : caster.path("effects"))
                    if (effect.path("requiresSourceEffect").equals(marker.path("name"))
                            && effect.path("activationId").equals(marker.path("id"))) active = true;
                if (!active) effects.remove(i);
            }
        }
        DruidEffects.synchronizeEarth(party);
        Map<String, ObjectNode> warriors = new java.util.LinkedHashMap<>(party);
        for (Campaign.Actor actor : campaign.getActors())
            if (!actor.isDisabled() && !warriors.containsKey(actor.getId()) && store.loadSheet(campaign.getId(), actor.getId()) != null)
                warriors.put(actor.getId(), changes.containsKey(actor.getId()) ? changes.get(actor.getId()) : storedSheet(actor.getId()));
        warriors.forEach((id, sheet) -> before.putIfAbsent(id, sheet.deepCopy()));
        Map<String, ObjectNode> previousCopycatParty = new java.util.LinkedHashMap<>();
        Map<String, ObjectNode> copycatParty = new java.util.LinkedHashMap<>(warriors);
        for (Campaign.Actor actor : campaign.getActors()) {
            if (actor.isDisabled() && store.loadSheet(campaign.getId(), actor.getId()) != null)
                copycatParty.put(actor.getId(), storedSheet(actor.getId()));
        }
        for (String id : copycatParty.keySet()) previousCopycatParty.put(id, storedSheet(id));
        CopycatCards.synchronize(copycatParty, previousCopycatParty);
        Map<String, ObjectNode> disabledSources = new java.util.LinkedHashMap<>(copycatParty);
        disabledSources.keySet().removeAll(warriors.keySet());
        WarriorAutomation.synchronizeDowntime(warriors, disabledSources);
        InvisibilityEffects.synchronize(warriors);
        for (var entry : warriors.entrySet()) restoreDisabledSourceEffects(entry.getValue(), before.get(entry.getKey()));
        warriors.forEach((id, sheet) -> {
            if (!sheet.equals(before.get(id))) changes.put(id, sheet);
        });
        party.forEach((id, sheet) -> {
            if (!sheet.equals(before.get(id))) changes.put(id, sheet);
        });
        return reports;
    }

    private void restoreDisabledSourceEffects(ObjectNode sheet, ObjectNode before) {
        if (before == null) return;
        for (JsonNode old : before.path("effects")) {
            if (old.path("partyDowntimeGrant").asBoolean()) continue;
            Campaign.Actor source = campaign.actor(old.path("sourceActorId").asText());
            if (source == null || !source.isDisabled()) continue;
            ArrayNode effects = sheet.withArray("effects");
            boolean present = false;
            for (JsonNode effect : effects)
                if (effect.path("id").equals(old.path("id")) && effect.path("sourceActorId").equals(old.path("sourceActorId"))) present = true;
            if (!present) effects.add(old.deepCopy());
        }
        for (JsonNode old : before.path("inventory")) {
            Campaign.Actor source = campaign.actor(old.path("witchTalisman").path("sourceActorId").asText());
            if (source == null || !source.isDisabled()) continue;
            ArrayNode inventory = sheet.withArray("inventory");
            boolean present = false;
            for (JsonNode item : inventory) if (item.path("witchTalisman").equals(old.path("witchTalisman"))) present = true;
            if (!present) inventory.add(old.deepCopy());
        }
        before.path("resources").fields().forEachRemaining(entry -> {
            if (!entry.getKey().startsWith("talisman-tokens:")) return;
            String[] parts = entry.getKey().split(":", 3);
            Campaign.Actor source = parts.length > 1 ? campaign.actor(parts[1]) : null;
            if (source != null && source.isDisabled()) sheet.withObject("/resources").set(entry.getKey(), entry.getValue().deepCopy());
        });
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
        persistFeatureSheets(changedSheets, message, newMessage, report, null, null);
    }

    private void persistFeatureSheets(Map<String, ObjectNode> changedSheets, ObjectNode message, boolean newMessage,
                                      ObjectNode report, Campaign.Actor renamedActor, String name) throws IOException {
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
        if (renamedActor == null)
            store.saveSheetsAndResults(campaign.getId(), changedSheets, newMessage ? null : message, newMessages);
        else
            store.saveSheetsAndRename(campaign, renamedActor, name, changedSheets, newMessage ? null : message, newMessages);
        stockChanges.forEach(this::stockChanged);
    }

    private void sendFeatureState(Campaign.Actor actor, ObjectNode sheet) {
        sheetChanged(actor.getId());
        ObjectNode message = MAPPER.createObjectNode().put("type", "sheetFeature").put("actorId", actor.getId())
                .put("featureRevision", sheet.path("featureRevision").asLong(0));
        for (String field : List.of("hp", "stress", "hope", "scars", "armorTrack", "effects", "usage", "resources",
                "permanentHp", "spectralBlocked", "deathNarrative", "longRestRevision", "vitalityBenefits", "vitalityEffectRemoved",
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
                .put("from", chatName(actor.getOwnerId(), campaign.player(actor.getOwnerId()).getName())).put("expression", result.expression())
                .put("total", result.total()).put("label", label).put("kind", "dice").put("actorId", actor.getId());
        putChatArtwork(out, actor.getOwnerId());
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

    /** DM button: ends the current scene, refreshing per-scene uses and removing scene-duration effects. */
    public synchronized String endScene() {
        return resetPeriod(false);
    }

    /** DM button: starts a new play session; also ends the scene and empties session resource pools. */
    public synchronized String newSession() {
        return resetPeriod(true);
    }

    private String resetPeriod(boolean session) {
        Campaign c = campaign;
        if (c == null) return "No online session is running.";
        Set<String> periods = session ? Set.of("scene", "session") : Set.of("scene");
        Set<String> resourceIds = session ? sessionResourceIds() : Set.of();
        Map<String, ObjectNode> changed = new java.util.LinkedHashMap<>();
        List<String> lines = new ArrayList<>();
        try {
            for (Campaign.Actor actor : c.getActors()) {
                if (actor.isDisabled()) continue;
                if (store.loadSheet(c.getId(), actor.getId()) == null) continue;
                ObjectNode sheet = storedSheet(actor.getId());
                int uses = 0;
                if (sheet.get("usage") instanceof ObjectNode usage) {
                    List<String> stale = new ArrayList<>();
                    usage.fields().forEachRemaining(entry -> {
                        if (periods.contains(entry.getValue().path("per").asText())) stale.add(entry.getKey());
                    });
                    stale.forEach(usage::remove);
                    uses = stale.size();
                }
                List<String> ended = new ArrayList<>();
                if (sheet.get("effects") instanceof ArrayNode effects) {
                    ArrayNode kept = MAPPER.createArrayNode();
                    for (JsonNode effect : effects) {
                        if (periods.contains(effect.path("until").asText())) ended.add(effect.path("name").asText("Effect"));
                        else kept.add(effect);
                    }
                    if (!ended.isEmpty()) sheet.set("effects", kept);
                }
                List<String> cleared = new ArrayList<>();
                if (sheet.get("resources") instanceof ObjectNode resources) {
                    for (String id : resourceIds) {
                        if (resources.path(id).asInt(0) != 0) {
                            JsonNode definition = findResourceDefinition(sheet, id);
                            if (definition != null && definition.path("sessionHope").asBoolean()) {
                                int dice = Math.max(0, resources.path(id).asInt());
                                int gained = gainTrack(sheet, "hope", dice);
                                cleared.add(dice + " Slayer Dice (gained " + gained + " Hope)");
                            } else {
                                cleared.add(id.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + id.substring(1));
                            }
                            resources.put(id, 0);
                        }
                    }
                }
                if (uses == 0 && ended.isEmpty() && cleared.isEmpty()) continue;
                changed.put(actor.getId(), sheet);
                List<String> parts = new ArrayList<>();
                if (uses > 0) parts.add(uses + (uses == 1 ? " use" : " uses") + " refreshed");
                if (!ended.isEmpty()) parts.add("ended " + String.join(", ", new LinkedHashSet<>(ended)));
                if (!cleared.isEmpty()) parts.add("cleared " + String.join(", ", cleared) + " tokens");
                lines.add(actor.getName() + ": " + String.join("; ", parts));
            }
            if (lines.isEmpty()) lines.add(session ? "No session or scene limits to reset." : "No scene limits to reset.");
            String text = (session ? "NEW SESSION\nSession and scene limits reset." : "SCENE ENDED\nScene limits reset.")
                    + lines.stream().map(line -> "\n• " + line).reduce("", String::concat);
            ObjectNode message = chatMessage("dm", "DM", null, clip(text));
            message.put("system", session ? "newSession" : "sceneEnd");
            commitFeatureSheets(changed, message, true);
            for (var entry : changed.entrySet()) sendFeatureState(c.actor(entry.getKey()), entry.getValue());
            deliver(message, null, false);
            return null;
        } catch (IOException | RuntimeException e) {
            return "Could not reset " + (session ? "the session" : "the scene") + ": " + e.getMessage();
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
            if (target.isDisabled()) continue;
            ObjectNode sheet = storedSheet(target.getId());
            JsonNode effects = sheet.path("effects");
            if (!effects.isArray()) continue;
            ArrayNode kept = MAPPER.createArrayNode();
            boolean removed = false;
            for (JsonNode effect : effects) {
                boolean ownLocalEffect = target.getId().equals(source.getId())
                        && !effect.hasNonNull("sourceActorId");
                boolean sourcedEffect = source.getId().equals(effect.path("sourceActorId").asText(""));
                if (effect.path("endOnSourceSevere").asBoolean() && (ownLocalEffect || sourcedEffect)
                        || target.getId().equals(source.getId()) && effect.has("endOnDamage") && effect.path("endOnDamage").asInt() <= 3) {
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
        refreshCatalogSheet(object);
        SpecialCardActions.migrateVitality(object);
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
                if (ChatCards.GOLD.equals(category) && !request.has("offerQuantity"))
                    throw new IllegalArgumentException("Gold can only be offered for transfer.");
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
        return publishSharedCard(playerId, playerName, actorId, actorName, category, item, offerQuantity, to, compendium, false);
    }

    private ObjectNode publishSharedCard(String playerId, String playerName, String actorId, String actorName,
                                         String category, ObjectNode item, int offerQuantity, String to,
                                         boolean compendium, boolean fromDm) throws IOException {
        ObjectNode card = MAPPER.createObjectNode().put("cardId", java.util.UUID.randomUUID().toString())
                .put("actorId", actorId).put("actorName", actorName).put("category", category);
        card.set("item", item);
        if (fromDm)
            card.put("fromDm", true);
        if (compendium) {
            card.set("badges", item.remove("badges"));
            card.set("sections", item.remove("sections"));
        }
        if (offerQuantity > 0)
            card.put("offerQuantity", offerQuantity);
        card.put("available", offerQuantity);
        String description = item.path("text").asText(item.path("note").asText(
                item.path("feature").path("text").asText("")));
        String offer = offerQuantity <= 0 ? "" : ChatCards.GOLD.equals(category)
                ? actorName + " offers " + offerQuantity + " gold"
                : actorName + " offers " + offerQuantity + " x ";
        ObjectNode message = chatMessage(playerId, playerName, to,
                (ChatCards.GOLD.equals(category) && offerQuantity > 0 ? offer
                        : offer + item.path("name").asText() + (description.isBlank() ? "" : "\n" + description)));
        message.set("sharedCard", card);
        store.appendHistoryChecked(campaign.getId(), message);
        sharedCards.put(card.path("cardId").asText(), message);
        deliver(message, to, false);
        return card;
    }

    private JsonNode sharedCardMessage(String cardId) {
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
        return message;
    }

    private JsonNode visibleCard(String playerId, String cardId) {
        JsonNode message = sharedCardMessage(cardId);
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
            int count = card.path("fromDm").asBoolean(false) ? dmRemaining(card)
                    : campaign.actor(card.path("actorId").asText()) == null || campaign.actor(card.path("actorId").asText()).isDisabled() ? 0
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
            JsonNode requested = request.get("quantity");
            if (requested != null && (!requested.isIntegralNumber() || !requested.canConvertToInt() || requested.asInt() <= 0))
                throw new IllegalArgumentException("Grab a whole quantity of at least 1.");
            JsonNode card = visibleCard(playerId, ctx.pathParam("id"));
            if (card.path("fromDm").asBoolean(false)) {
                grabDmOffer(playerId, card, request, requested);
                json(ctx, 200, MAPPER.createObjectNode().put("transferred", true));
                return;
            }
            String sourceId = card.path("actorId").asText();
            Campaign.Actor sourceActor = campaign.actor(sourceId);
            Campaign.Actor targetActor = ownedActor(playerId, request.path("actorId").asText());
            if (sourceActor == null || sourceActor.isDisabled() || sourceId.equals(targetActor.getId())) {
                throw new IllegalArgumentException("Choose a different receiving character.");
            }
            ObjectNode source = storedSheet(sourceId);
            ObjectNode target = storedSheet(targetActor.getId());
            if (target.isEmpty())
                throw new IllegalArgumentException("Create and save the receiving character first.");
            int remaining = ChatCards.remaining(source, card);
            if (remaining <= 0)
                throw new IllegalArgumentException("This is not an available transfer offer. Ask the owner to offer the item.");
            int quantity = grabQuantity(request, requested, remaining);
            ChatCards.transfer(source, target, card.path("category").asText(), card.path("item"), quantity);
            String cardId = card.path("cardId").asText();
            ObjectNode claims = source.withObject("/transferClaims");
            claims.put(cardId, claims.path(cardId).asInt(0) + quantity);
            source.put("inventoryRevision", source.path("inventoryRevision").asLong(0) + 1);
            target.put("inventoryRevision", target.path("inventoryRevision").asLong(0) + 1);
            Map<String, ObjectNode> transferred = new java.util.LinkedHashMap<>();
            transferred.put(sourceId, source);
            transferred.put(targetActor.getId(), target);
            commitFeatureSheets(transferred);
            sendFeatureState(sourceActor, source);
            sendFeatureState(targetActor, target);
            inventoryChanged(sourceActor, source);
            inventoryChanged(targetActor, target);
            json(ctx, 200, MAPPER.createObjectNode().put("transferred", true).put("quantity", quantity));
        } catch (IllegalArgumentException e) {
            json(ctx, 409, errorBody(e.getMessage()));
        } catch (IOException e) {
            json(ctx, 500, errorBody("Could not complete the inventory transfer. Refresh both inventories before retrying."));
        }
    }

    private static int grabQuantity(JsonNode request, JsonNode requested, int remaining) {
        if (request.path("all").asBoolean(false))
            return remaining;
        int quantity = requested == null ? 1 : requested.asInt();
        if (quantity > remaining)
            throw new IllegalArgumentException("Only " + remaining + " remaining to grab.");
        return quantity;
    }

    private void grabDmOffer(String playerId, JsonNode card, JsonNode request, JsonNode requested) throws IOException {
        if (!ChatCards.GOLD.equals(card.path("category").asText()))
            throw new IllegalArgumentException("This is not an available transfer offer.");
        Campaign.Actor targetActor = ownedActor(playerId, request.path("actorId").asText());
        ObjectNode target = storedSheet(targetActor.getId());
        if (target.isEmpty())
            throw new IllegalArgumentException("Create and save the receiving character first.");
        int remaining = dmRemaining(card);
        if (remaining <= 0)
            throw new IllegalArgumentException("There is no more to grab.");
        int quantity = grabQuantity(request, requested, remaining);
        ChatCards.creditGold(target, quantity);
        target.put("inventoryRevision", target.path("inventoryRevision").asLong(0) + 1);
        String cardId = card.path("cardId").asText();
        if (!(sharedCardMessage(cardId) instanceof ObjectNode current))
            throw new IllegalArgumentException("Shared card is unavailable.");
        ObjectNode updated = current.deepCopy();
        ObjectNode shared = (ObjectNode)updated.get("sharedCard");
        shared.put("claimed", shared.path("claimed").asInt(0) + quantity);
        shared.put("available", remaining - quantity);
        store.saveSheetsAndRoll(campaign.getId(), Map.of(targetActor.getId(), target), updated);
        sharedCards.put(cardId, updated);
        inventoryChanged(targetActor, target);
        stockChanged("");
    }

    /** DM offers have no source sheet; their budget is tracked on the persisted card itself. */
    private int dmRemaining(JsonNode card) {
        JsonNode current = sharedCardMessage(card.path("cardId").asText());
        JsonNode shared = current == null ? card : current.path("sharedCard");
        return Math.max(0, shared.path("offerQuantity").asInt(0) - shared.path("claimed").asInt(0));
    }

    /** Posts a DM transfer offer of coins that players can grab into their own characters. */
    public synchronized String dmOfferGold(int amount, String toPlayerId) {
        if (campaign == null)
            return "No online session is running.";
        if (amount <= 0)
            return "Offer a whole amount of gold of at least 1.";
        try {
            ObjectNode card = publishSharedCard("dm", "DM", "", "Gamemaster", ChatCards.GOLD, ChatCards.goldItem(), amount,
                    toPlayerId == null ? "" : toPlayerId, false, true);
            return card == null ? "Could not post the gold offer." : null;
        } catch (IOException e) {
            return "Could not post the gold offer: " + e.getMessage();
        }
    }

    private void inventoryChanged(Campaign.Actor actor, ObjectNode sheet) {
        ObjectNode message = MAPPER.createObjectNode().put("type", "sheetInventory").put("actorId", actor.getId());
        ObjectNode inventory = message.putObject("inventory");
        for (String field : List.of("inventoryRevision", "inventory", "activeWeapons", "carriedWeapons", "activeArmor", "carriedArmor",
                "compendiumRevision", "loadout", "vault", "gold")) {
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
        sheetChanged(actorId);
        broadcast(MAPPER.createObjectNode().put("type", "cardStock").put("actorId", actorId).toString());
        host.cardStockChanged(actorId);
    }

    /** Units of a shared transfer offer that can still be grabbed; 0 when the source is gone. */
    public synchronized int cardRemaining(JsonNode sharedCard) {
        String actorId = sharedCard.path("actorId").asText();
        try {
            if (campaign != null && sharedCard.path("fromDm").asBoolean(false))
                return dmRemaining(sharedCard);
            return campaign == null || campaign.actor(actorId) == null || campaign.actor(actorId).isDisabled() ? 0
                    : ChatCards.remaining(storedSheet(actorId), sharedCard);
        } catch (IOException e) {
            return 0;
        }
    }

    private synchronized void handleArtworkSettings(Context ctx) {
        String playerId = requirePlayer(ctx);
        if (playerId == null) return;
        Campaign.Actor actor = campaign == null ? null : campaign.actor(ctx.pathParam("id"));
        if (actor == null || !playerId.equals(actor.getOwnerId())) {
            json(ctx, 403, errorBody("Not your actor"));
            return;
        }
        Campaign.ActorArtwork art = actor.getTokenArtwork();
        boolean dm = ctx.header("Authorization") != null && ctx.header("Authorization").startsWith("Bearer ");
        Campaign.Player player = campaign.player(playerId);
        ObjectNode result = MAPPER.createObjectNode();
        result.put("color", art == null ? dm ? campaign.getDmColor() : player.getColor() : art.getColor());
        result.put("sizeTiles", art == null ? 1 : art.getSizeTiles());
        result.put("zoom", art == null ? 1 : art.getZoom());
        result.put("centerX", art == null ? .5 : art.getCenterX());
        result.put("centerY", art == null ? .5 : art.getCenterY());
        String source = art == null ? actor.getArtwork() : art.getSource();
        if (source != null) result.put("source", assets.artworkUrl(campaign.getId(), source));
        ctx.header("Cache-Control", "no-store");
        json(ctx, 200, result);
    }

    private synchronized void handleArtwork(Context ctx) {
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
        String previous = actor.getArtwork();
        Campaign.ActorArtwork previousSettings = actor.getTokenArtwork();
        try {
            String hash;
            Campaign.ActorArtwork settings = null;
            if (ctx.contentType() != null && ctx.contentType().startsWith("application/json")) {
                JsonNode request = MAPPER.readTree(ctx.body());
                if (request == null || !request.isObject()) throw new IOException("Invalid token artwork request.");
                settings = MAPPER.treeToValue(request, Campaign.ActorArtwork.class);
                ActorTokenArtwork.validate(settings);
                String source = previousSettings == null ? previous : previousSettings.getSource();
                if (request.has("image")) {
                    String image = request.path("image").asText();
                    if (!image.startsWith("data:image/") || !image.contains(";base64,"))
                        throw new IOException("Choose or paste an image.");
                    byte[] bytes;
                    try { bytes = Base64.getDecoder().decode(image.substring(image.indexOf(";base64,") + 8)); }
                    catch (IllegalArgumentException ex) { throw new IOException("Invalid image encoding.", ex); }
                    source = store.storeArtwork(c.getId(), bytes);
                }
                if (source == null) throw new IOException("Choose or paste an image.");
                settings.setSource(source);
                hash = store.storeArtwork(c.getId(), ActorTokenArtwork.render(store.artworkPath(c.getId(), source), settings));
            } else {
                hash = store.storeArtwork(c.getId(), ctx.bodyAsBytes());
            }
            actor.setArtwork(hash);
            actor.setTokenArtwork(settings);
            store.save(c);
            host.campaignChanged(c);
            json(ctx, 200, MAPPER.createObjectNode().put("artwork", assets.artworkUrl(c.getId(), hash)));
        } catch (IOException e) {
            actor.setArtwork(previous);
            actor.setTokenArtwork(previousSettings);
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
        DmSheetGrant grant = dmSheetGrant(ctx.session.getRemoteAddress() instanceof java.net.InetSocketAddress address
                ? address.getAddress().getHostAddress() : "", ctx.queryParam("dm-sheet"));
        String playerId = grant == null ? playerOf(token) : grant.ownerId();
        Campaign c = campaign;
        Campaign.Player player = c == null ? null : c.player(playerId);
        if (player == null) {
            ctx.closeSession(1008, "Login required");
            return;
        }
        LoginClaim claim = loginClaim(playerId);
        if (grant == null && claim != null && !claim.token().equals(token)) {
            ctx.closeSession(1008, "Player account already connected");
            return;
        }
        ctx.session.setIdleTimeout(java.time.Duration.ofMinutes(10));
        Set<String> connected = new LinkedHashSet<>();
        clients.values().stream().filter(cl -> cl.dmActorId == null).forEach(cl -> connected.add(cl.playerId));
        if (grant == null && !connected.contains(playerId) && connected.size() >= config.maxPlayers()) {
            ctx.closeSession(1008, "Session is full");
            return;
        }
        Client client = new Client(ctx, playerId, player.getName());
        client.dmActorId = grant == null ? null : grant.actorId();
        client.dmToken = grant == null ? null : ctx.queryParam("dm-sheet");
        client.fullSheets = "1".equals(ctx.queryParam("sheet-sync"));
        if (grant == null) loginClaims.put(playerId, new LoginClaim(token, Long.MAX_VALUE));
        synchronized (this) {
            clients.put(ctx.sessionId(), client);
            ObjectNode hello = MAPPER.createObjectNode();
            hello.put("type", "hello").put("protocol", OnlineProtocol.VERSION);
            hello.put("playerId", playerId).put("playerName", player.getName());
            hello.put("accountColor", client.dmActorId == null ? player.getColor() : c.getDmColor());
            hello.put("measurementColors", true);
            hello.put("serverTime", System.currentTimeMillis());
            hello.put("sessionName", config.sessionName());
            hello.put("campaignName", c.getName());
            hello.put("paused", paused);
            hello.put("sessionId", sessionId);
            hello.put("campaignId", c.getId()).put("drawingTools", host.supportsDrawing()).put("measurementTools", true);
            hello.put("tokenRotation", host.supportsTokenRotation());
            hello.put("tokenConditions", host.supportsTokenConditions());
            hello.put("portalControls", host.supportsPortalControls());
            hello.put("mapPings", host.supportsMapPings());
            hello.put("playerWhispers", true);
            hello.put("chatCards", true);
            hello.put("compendiumChat", true);
            hello.put("transferOffers", true);
            hello.put("goldTransfers", true);
            hello.put("grabQuantity", true);
            hello.put("notes", true);
            hello.put("keptDice", true);
            hello.put("zeroKeptDice", true);
            hello.put("glancingBlowDamage", true);
            hello.put("brawlerRolls", true);
            hello.put("druidAutomation", true);
            hello.put("rollMultipliers", true);
            hello.put("rollRewards", true);
            hello.put("partyFeatures", true);
            hello.put("warriorAutomation", true);
            hello.put("guardianRolls", true);
            hello.put("dualityDieBonus", true);
            hello.put("seraphAutomation", true);
            hello.put("sorcererAutomation", true);
            hello.put("warlockAutomation", true);
            hello.put("witchAutomation", true);
            hello.put("domainCardAutomation", true);
            hello.put("domainCardAutomationV2", true);
            hello.put("domainCardAutomationV3", true);
            hello.put("bloodAutomation", true);
            hello.put("specialCardActions", true);
            hello.put("deathMoves", true);
            hello.put("deathMoveOptions", true);
            hello.put("sheetSync", true);
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
        if (client.dmActorId == null && List.of("roll", "chat").contains(msg.path("type").asText())
                && msg.hasNonNull("actorId")) {
            Campaign.Actor actor = campaign == null ? null : campaign.actor(msg.path("actorId").asText());
            if (actor != null && actor.isDisabled()) {
                sendTo(ctx, error("This actor is disabled."));
                return;
            }
        }
        if (client.dmActorId != null) {
            if (!dmSheetGrants.containsKey(client.dmToken)
                    || campaign.actor(client.dmActorId) == null
                    || !client.playerId.equals(campaign.actor(client.dmActorId).getOwnerId())) {
                ctx.closeSession(1008, "The DM sheet session has expired.");
                return;
            }
            String type = msg.path("type").asText();
            boolean infrastructure = List.of("clockSync", "pong", "resync").contains(type);
            boolean sheetAction = List.of("roll", "chat").contains(type) && client.dmActorId.equals(msg.path("actorId").asText());
            if (!infrastructure && !sheetAction) {
                sendTo(ctx, error("This DM sheet can only control its own character."));
                return;
            }
        }
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
            case "chatIdentity" -> sendTo(ctx, error("Chat names are chosen automatically from your first enabled actor."));
            case "roll" -> onRoll(client, msg);
            case "fogExplored" -> onFogExplored(client, msg);
            case "resync" -> onResync(client, msg.path("lastSeq").asLong(0));
            default -> sendTo(ctx, error("Unknown message"));
        }
    }

    private void onCommand(Client client, JsonNode msg) {
        String cmdId = msg.path("cmdId").asText("");
        if ("mapPing".equals(msg.path("kind").asText())) {
            double x = msg.path("x").asDouble(Double.NaN);
            double y = msg.path("y").asDouble(Double.NaN);
            if (paused || !host.supportsMapPings() || !msg.path("x").isNumber() || !msg.path("y").isNumber()
                    || !Double.isFinite(x) || !Double.isFinite(y)
                    || !msg.path("mapId").isTextual() || msg.path("mapId").asText().isBlank()
                    || !msg.path("levelId").isTextual() || msg.path("levelId").asText().isBlank()) {
                sendTo(client.ctx, reject(cmdId, paused ? "paused" : "Invalid or unsupported map ping", null));
                return;
            }
            host.mapPing(client.playerId, msg.path("mapId").asText(), msg.path("levelId").asText(), x, y)
                    .orTimeout(5, TimeUnit.SECONDS).whenComplete((result, error) -> {
                        if (error != null || result == null || !result.ok()) {
                            sendTo(client.ctx, reject(cmdId, error != null || result == null ? "Map ping failed" : result.reason(), null));
                        } else {
                            sendTo(client.ctx, MAPPER.createObjectNode().put("type", "ack").put("cmdId", cmdId).put("seq", seq()).toString());
                        }
                    });
            return;
        }
        if ("setTokenCondition".equals(msg.path("kind").asText())) {
            String tokenId = msg.path("tokenId").asText("");
            String type = msg.path("condition").path("type").asText("");
            String name = msg.path("condition").path("name").asText("");
            String conditionId = msg.path("condition").path("id").asText("");
            String iconId = msg.path("condition").path("iconId").asText(null);
            boolean enabled = msg.path("enabled").asBoolean(false);
            if (paused || !host.supportsTokenConditions() || tokenId.isBlank()
                    || msg.path("condition").isMissingNode() || msg.path("condition").isNull()
                    || !msg.path("mapId").isTextual() || msg.path("mapId").asText().isBlank()
                    || !msg.path("levelId").isTextual() || msg.path("levelId").asText().isBlank()
                    || !msg.path("enabled").isBoolean()) {
                sendTo(client.ctx, reject(cmdId, paused ? "paused" : "Invalid or unsupported token condition", null));
                return;
            }
            host.setTokenCondition(client.playerId, tokenId, msg.path("mapId").asText(),
                    msg.path("levelId").asText(), type, name, conditionId, iconId, enabled)
                    .orTimeout(5, TimeUnit.SECONDS).whenComplete((result, error) -> {
                        if (error != null || result == null || !result.ok()) {
                            sendTo(client.ctx, reject(cmdId,
                                    error != null || result == null ? "Condition update failed" : result.reason(), null));
                        } else {
                            sendTo(client.ctx, MAPPER.createObjectNode().put("type", "ack")
                                    .put("cmdId", cmdId).put("seq", seq()).toString());
                        }
                    });
            return;
        }
        if ("togglePortal".equals(msg.path("kind").asText())) {
            if (paused || !host.supportsPortalControls()
                    || !msg.path("id").isTextual() || msg.path("id").asText().isBlank()
                    || !msg.path("mapId").isTextual() || msg.path("mapId").asText().isBlank()
                    || !msg.path("levelId").isTextual() || msg.path("levelId").asText().isBlank()) {
                sendTo(client.ctx, reject(cmdId, paused ? "paused" : "Invalid or unsupported portal interaction", null));
                return;
            }
            host.togglePortal(client.playerId, msg.path("id").asText(), msg.path("mapId").asText(), msg.path("levelId").asText())
                    .orTimeout(5, TimeUnit.SECONDS).whenComplete((result, error) -> {
                        if (error != null || result == null || !result.ok()) {
                            sendTo(client.ctx, reject(cmdId, error != null || result == null ? "Portal interaction failed" : result.reason(), null));
                        } else {
                            sendTo(client.ctx, MAPPER.createObjectNode().put("type", "ack").put("cmdId", cmdId).put("seq", seq()).toString());
                        }
                    });
            return;
        }
        if ("rotateToken".equals(msg.path("kind").asText())) {
            String tokenId = msg.path("tokenId").asText("");
            double rotation = msg.path("rotationDeg").asDouble(Double.NaN);
            if (paused || !host.supportsTokenRotation() || tokenId.isEmpty() || !msg.path("rotationDeg").isNumber()
                    || !Double.isFinite(rotation) || msg.path("mapId").asText("").isEmpty()
                    || msg.path("levelId").asText("").isEmpty()) {
                sendTo(client.ctx, reject(cmdId, paused ? "paused" : "Invalid or unsupported token rotation", null));
                return;
            }
            host.rotateToken(client.playerId, tokenId, msg.path("mapId").asText(), msg.path("levelId").asText(), rotation)
                    .orTimeout(5, TimeUnit.SECONDS).whenComplete((result, error) -> {
                        if (error != null || result == null || !result.ok()) {
                            sendTo(client.ctx, reject(cmdId, error != null || result == null ? "Rotation failed" : result.reason(), null));
                        } else {
                            sendTo(client.ctx, MAPPER.createObjectNode().put("type", "ack").put("cmdId", cmdId).put("seq", seq()).toString());
                        }
                    });
            return;
        }
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
                relayTokenMove(client, tokenId, x, y);
            } else {
                sendTo(client.ctx, reject(cmdId, result.reason(), tokenId, result.x(), result.y()));
            }
        });
    }

    /**
     * Forwards an accepted move to the other browser connections at once instead of waiting for the next published
     * delta. Only tokens in the published player state are relayed, so hidden tokens never leak; the authoritative
     * position still arrives with the next delta.
     */
    private synchronized void relayTokenMove(Client sender, String tokenId, double x, double y) {
        if (current == null || currentMapKey == null) {
            return;
        }
        boolean published = false;
        for (JsonNode token : current.path("tokens")) {
            if (tokenId.equals(token.path("id").asText())) {
                published = true;
                break;
            }
        }
        if (!published) {
            return;
        }
        String json = MAPPER.createObjectNode().put("type", "tokenMoved").put("mapKey", currentMapKey)
                .put("id", tokenId).put("x", x).put("y", y).toString();
        for (Client other : clients.values()) {
            if (other != sender) {
                sendTo(other.ctx, json);
            }
        }
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

    private Campaign.Actor chatActor(String playerId) {
        Campaign c = campaign;
        if (c == null || playerId == null || c.player(playerId) == null) return null;
        return c.getActors().stream()
                .filter(actor -> playerId.equals(actor.getOwnerId()) && !actor.isDisabled()
                        && actor.getName() != null && !actor.getName().isBlank())
                .findFirst().orElse(null);
    }

    private String chatIdentity(String playerId) {
        Campaign.Actor actor = chatActor(playerId);
        return actor == null ? "" : actor.getName();
    }

    private void putChatArtwork(ObjectNode message, String playerId) {
        Campaign.Actor actor = chatActor(playerId);
        if (actor == null || actor.getArtwork() == null) return;
        String url = assets.artworkUrl(campaign.getId(), actor.getArtwork());
        if (url != null) message.put("fromArtwork", url);
    }

    private void registerChatArtwork(JsonNode message) {
        String url = message.path("fromArtwork").asText("");
        if (url.startsWith("/a/") && url.endsWith(".img"))
            assets.artworkUrl(campaign.getId(), url.substring(3, url.length() - 4));
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

    private synchronized void onRoll(Client client, JsonNode msg) {
        String invisibilityRef = invisibilityRollRef(client, msg);
        if (invisibilityRef != null && invisibilityRolls.containsKey(invisibilityRef)) {
            sendTo(client.ctx, invisibilityRolls.get(invisibilityRef).toString());
            return;
        }
        DiceRoller.Result result;
        ObjectNode rollSource;
        List<DiceRoller.Detail> breakdown = null;
        List<ArrayNode> breakdownNotes = new ArrayList<>();
        String to;
        try {
            to = chatRecipient(msg);
            rollSource = rollSource(msg);
            Campaign.Actor roller = campaign == null ? null : campaign.actor(msg.path("actorId").asText());
            if (roller != null && client.playerId.equals(roller.getOwnerId())) {
                ObjectNode rollerSheet = null;
                for (String tag : msg.path("source").asText("").split(",")) {
                    if (tag.startsWith("card:Copycat/")) {
                        try {
                            if (rollerSheet == null) rollerSheet = storedSheet(roller.getId());
                            featureSource(rollerSheet, tag);
                        }
                        catch (IOException e) { throw new IllegalArgumentException("Could not verify the copied card.", e); }
                    }
                    if (tag.startsWith("option:effect:")) {
                        try {
                            if (rollerSheet == null) rollerSheet = storedSheet(roller.getId());
                        } catch (IOException e) { throw new IllegalArgumentException("Could not verify the selected effect.", e); }
                        for (JsonNode effect : rollerSheet.path("effects")) {
                            String prefix = "option:effect:" + effect.path("id").asText() + ":";
                            Campaign.Actor source = campaign.actor(effect.path("sourceActorId").asText());
                            if (tag.startsWith(prefix) && source != null && source.isDisabled())
                                throw new IllegalArgumentException("The selected effect's source actor is disabled.");
                        }
                    }
                }
            }
            ResolvedDice resolved = resolveDice(msg);
            result = resolved.result();
            breakdown = resolved.breakdown();
            breakdownNotes = resolved.notes();
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
        putChatArtwork(out, client.playerId);
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
        completeRoll(client, msg, out, to, result);
    }

    private record ResolvedDice(DiceRoller.Result result, List<DiceRoller.Detail> breakdown, List<ArrayNode> notes) {}

    private void putDiceBreakdown(ObjectNode out, ResolvedDice resolved) {
        if (resolved.breakdown() == null) return;
        ArrayNode details = MAPPER.valueToTree(resolved.breakdown());
        for (int i = 0; i < resolved.notes().size(); i++)
            if (!resolved.notes().get(i).isEmpty()) ((ObjectNode) details.get(i)).set("notes", resolved.notes().get(i));
        out.set("breakdown", details);
    }

    private ResolvedDice resolveDice(JsonNode msg) {
        DiceRoller.Result result = DiceRoller.roll(msg.path("expression").asText(""), config.maxDice(), diceRandom);
        List<DiceRoller.Detail> breakdown = null;
        List<ArrayNode> breakdownNotes = new ArrayList<>();
            if (msg.has("damageRules")) {
                if (!msg.path("damageRules").isObject() || !List.of(msg.path("source").asText("").split(",")).contains("damage")
                        || "duality".equals(msg.path("kind").asText()))
                    throw new IllegalArgumentException("Brawler dice rules apply only to damage rolls.");
                if (msg.path("damageRules").path("maximumOnes").asBoolean()) result = DiceRoller.maximumOnes(result, config.maxDice());
                if (msg.path("damageRules").has("rerollBelow")) {
                    JsonNode threshold = msg.path("damageRules").path("rerollBelow");
                    if (!threshold.isInt()) throw new IllegalArgumentException("Invalid reroll threshold.");
                    result = DiceRoller.rerollBelow(result, threshold.asInt(), config.maxDice(), diceRandom);
                }
            }
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
                if (msg.path("damageRules").path("maximumOnes").asBoolean() && !breakdownNotes.isEmpty())
                    breakdownNotes.getFirst().addObject().put("label", "Exacting").put("impact", "Damage dice showing 1 count as maximum; Combo Dice remain unmodified.");
                if (msg.path("damageRules").has("rerollBelow") && !breakdownNotes.isEmpty())
                    breakdownNotes.getFirst().addObject().put("label", "Deadly Raptor")
                            .put("impact", "Rerolled damage dice below Proficiency (" + msg.path("damageRules").path("rerollBelow").asInt() + ") once.");
            }
            String matchingExpression = result.expression();
            if (msg.path("damageRules").has("comboDie")) {
                JsonNode die = msg.path("damageRules").path("comboDie");
                if (!die.isInt()) throw new IllegalArgumentException("Invalid Combo Die.");
                List<Integer> combo = DiceRoller.combo(die.asInt(), config.maxDice() - result.rolls().size(), diceRandom);
                int extra = combo.stream().mapToInt(Integer::intValue).sum();
                if (breakdown == null) breakdown = new ArrayList<>(List.of(new DiceRoller.Detail("Damage", result.expression(), result.rolls(), result.total())));
                breakdown.add(new DiceRoller.Detail("Combo Strike rolls", combo.size() + "d" + die.asInt(), combo, extra));
                List<Integer> all = new ArrayList<>(result.rolls());
                all.addAll(combo);
                result = new DiceRoller.Result(result.expression() + "+" + extra, all, result.total() + extra);
            }
            if (msg.path("damageRules").path("matchingDice").asBoolean()) {
                List<Integer> bonuses = DiceRoller.matchingDiceBonuses(
                        new DiceRoller.Result(matchingExpression, result.rolls(), result.total()), config.maxDice());
                int extra = bonuses.stream().mapToInt(Integer::intValue).sum();
                if (breakdown == null) breakdown = new ArrayList<>(List.of(
                        new DiceRoller.Detail("Damage", result.expression(), result.rolls(), result.total())));
                int resonanceIndex = -1;
                for (int i = 0; i < breakdown.size(); i++) {
                    DiceRoller.Detail detail = breakdown.get(i);
                    if (detail.rolls().isEmpty() && detail.total() == 0
                            && ("Sacred Resonance".equals(detail.label()) || detail.label().endsWith(" - Sacred Resonance"))) {
                        resonanceIndex = i;
                        break;
                    }
                }
                if (resonanceIndex >= 0) {
                    DiceRoller.Detail detail = breakdown.get(resonanceIndex);
                    breakdown.set(resonanceIndex, new DiceRoller.Detail(detail.label(), String.valueOf(extra), List.of(), extra));
                } else {
                    resonanceIndex = breakdown.size();
                    breakdown.add(new DiceRoller.Detail("Sacred Resonance", String.valueOf(extra), List.of(), extra));
                }
                while (breakdownNotes.size() <= resonanceIndex) breakdownNotes.add(MAPPER.createArrayNode());
                breakdownNotes.get(resonanceIndex).addObject().put("label", "Matched")
                        .put("impact", "Matching damage dice add " + extra + " additional damage.");
                result = new DiceRoller.Result(result.expression(), result.rolls(), result.total() + extra);
            }
        return new ResolvedDice(result, breakdown, breakdownNotes);
    }

    private void completeRoll(Client client, JsonNode msg, ObjectNode out, String to, DiceRoller.Result result) {
        String invisibilityRef = invisibilityRollRef(client, msg);
        String label = msg.path("label").asText("").strip();
        if (!label.isEmpty()) {
            out.put("label", label.length() > 80 ? label.substring(0, 80) : label);
        }
        // Opaque hints that let the roller's own client attach buttons (Damage, rerolls) to the chat card.
        for (String hint : new String[]{"ref", "actorId", "source", "weapon"}) {
            String value = msg.path(hint).asText("").strip();
            if (!value.isEmpty()) {
                int maxLength = "source".equals(hint) ? 4096 : 80;
                out.put(hint, value.length() > maxLength ? value.substring(0, maxLength) : value);
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
        try {
            boolean persisted = applyAutomaticFeatureTriggers(client, out);
            if (persisted && invisibilityRef != null) invisibilityRolls.put(invisibilityRef, out.deepCopy());
            deliver(out, to, !persisted);
        } catch (IOException | IllegalArgumentException e) {
            sessionRolls.remove(rollKey(out.path("id").asLong(), out.path("ts").asLong()));
            sendTo(client.ctx, error("Could not apply the automatic feature: " + e.getMessage()));
        }
    }

    private synchronized boolean applyAutomaticFeatureTriggers(Client client, ObjectNode roll) throws IOException {
        if (!"duality".equals(roll.path("kind").asText())) return false;
        Campaign.Actor actor = campaign.actor(roll.path("actorId").asText());
        boolean linkedTokens = roll.path("source").asText().contains("option:effect:");
        if (actor == null || !client.playerId.equals(actor.getOwnerId())) {
            if (linkedTokens) throw new IllegalArgumentException("Only the recipient's owner can spend Invisibility tokens.");
            return false;
        }
        ObjectNode sheet = storedSheet(actor.getId());
        boolean changed = false;
        Map<String, ObjectNode> changedSheets = new java.util.LinkedHashMap<>();
        changedSheets.put(actor.getId(), sheet);
        if (linkedTokens) {
            Map<String, ObjectNode> party = new java.util.LinkedHashMap<>();
            Map<String, ObjectNode> before = new java.util.LinkedHashMap<>();
            for (Campaign.Actor candidate : campaign.getActors()) {
                if (candidate.isDisabled()) continue;
                if (!candidate.getId().equals(actor.getId()) && store.loadSheet(campaign.getId(), candidate.getId()) == null) continue;
                ObjectNode other = candidate.getId().equals(actor.getId()) ? sheet : storedSheet(candidate.getId());
                party.put(candidate.getId(), other);
                before.put(candidate.getId(), other.deepCopy());
            }
            changed = InvisibilityEffects.spendSelected(party, actor.getId(), roll);
            party.forEach((id, other) -> {
                if (!other.equals(before.get(id))) changedSheets.put(id, other);
            });
        }
        List<String> tags = List.of(roll.path("source").asText("").split(","));
        for (String tag : tags) {
            if (!tag.startsWith("featureAction:card:")) continue;
            String key = tag.substring("featureAction:".length(), tag.lastIndexOf(':'));
            JsonNode source = featureSource(sheet, key);
            JsonNode automation = catalogAutomation(key, source.path("name").asText(), source.path("automation"));
            for (JsonNode action : automation.path("actions")) {
                if (!action.path("actionRoll").isObject() || !action.path("chatBenefits").isArray()
                        || !tag.equals("featureAction:" + key + ":" + action.path("id").asText())) continue;
                boolean active = key.startsWith("card:Copycat/");
                for (JsonNode card : sheet.path("loadout"))
                    if (key.equals("card:" + card.path("name").asText())) active = true;
                if (!active) throw new IllegalArgumentException("This spell must be in your active loadout.");
                if (action.path("hostRollAction").asBoolean()) {
                    if (!tags.contains("spellcast") || tags.contains("reaction"))
                        throw new IllegalArgumentException("This action requires a Spellcast action roll.");
                    applyFeatureCost(sheet, action.path("cost"));
                    useFeatureLimit(sheet, key, automation.path("uses"));
                }
                attachActionBenefits(roll, actor, sheet, action, actionBenefits(sheet, key, action));
                changed = true;
            }
        }
        if (tags.contains("spellcast")) {
            for (String tag : tags) {
                if (!tag.startsWith("card:Copycat/")) continue;
                JsonNode copiedSource = featureSource(sheet, tag);
                JsonNode automation = copiedSource.path("automation");
                if (!automation.path("automaticRestUse").asBoolean()) continue;
                JsonNode difficulty = automation.path("spellcastDifficulty");
                if (!difficulty.isInt() || !automation.path("uses").isObject())
                    throw new IllegalArgumentException("Invalid copied spell difficulty or rest limit.");
                roll.put("difficulty", difficulty.asInt());
                int hope = roll.path("rolls").path(0).asInt();
                int fear = roll.path("rolls").path(1).asInt();
                if (!automation.path("automaticRestUseOnAttempt").asBoolean()
                        && hope != fear && roll.path("total").asDouble() < difficulty.asDouble()) continue;
                useFeatureLimit(sheet, tag, automation.path("uses"));
                roll.withArray("featureUses").add(actor.getId() + ":" + tag + ":automaticRestUse");
                changed = true;
            }
            for (String tag : tags) {
                if (!tag.startsWith("card:")) continue;
                String bookName = tag.substring("card:".length());
                boolean present = false;
                for (String location : List.of("loadout", "vault"))
                    for (JsonNode card : sheet.path(location))
                        if (bookName.equals(card.path("name").asText())) present = true;
                if (present) continue;
                JsonNode catalogCard = catalogDomainCard(bookName);
                if (catalogCard == null) continue;
                if (catalogCard.path("automation").path("automaticRestUse").asBoolean())
                    throw new IllegalArgumentException("This spell must be in your active loadout.");
                for (JsonNode spell : catalogCard.path("spells"))
                    if (tags.contains("card:" + spell.path("name").asText())
                            && spell.path("automation").path("automaticRestUse").asBoolean())
                        throw new IllegalArgumentException("This spell must be in your active loadout.");
            }
            for (String location : List.of("loadout", "vault")) for (JsonNode card : sheet.path(location)) {
                String bookName = card.path("name").asText();
                if (!tags.contains("card:" + bookName)) continue;
                JsonNode catalogCard = catalogDomainCard(bookName);
                JsonNode spells = catalogCard == null ? card.path("spells") : catalogCard.path("spells");
                List<JsonNode> trackedSources = new ArrayList<>();
                trackedSources.add(catalogCard == null ? card : catalogCard);
                spells.forEach(trackedSources::add);
                for (JsonNode spell : trackedSources) {
                    String spellName = spell.path("name").asText();
                    if (!tags.contains("card:" + spellName)) continue;
                    JsonNode automation = spell.path("automation");
                    if (!automation.path("automaticRestUse").asBoolean()) continue;
                    if (!"loadout".equals(location))
                        throw new IllegalArgumentException("This spell must be in your active loadout.");
                    JsonNode difficulty = automation.path("spellcastDifficulty");
                    if (!difficulty.isInt() || !automation.path("uses").isObject())
                        throw new IllegalArgumentException("Invalid spell difficulty or rest limit.");
                    roll.put("difficulty", difficulty.asInt());
                    int hope = roll.path("rolls").path(0).asInt();
                    int fear = roll.path("rolls").path(1).asInt();
                    if (!automation.path("automaticRestUseOnAttempt").asBoolean()
                            && hope != fear && roll.path("total").asDouble() < difficulty.asDouble()) continue;
                    String key = "card:" + bookName + (spellName.equals(bookName) ? "" : "/" + spellName);
                    useFeatureLimit(sheet, key, automation.path("uses"));
                    roll.withArray("featureUses").add(actor.getId() + ":" + key + ":automaticRestUse");
                    changed = true;
                }
            }
        }
        for (String key : roll.path("source").asText("").split(",")) {
            if (!key.startsWith("feat:")) continue;
            JsonNode source;
            boolean available = false;
            for (String field : List.of("features", "heritage"))
                for (JsonNode feature : sheet.path(field))
                    if (key.equals("feat:" + feature.path("name").asText())) available = true;
            for (JsonNode effect : sheet.path("effects"))
                for (JsonNode feature : effect.path("features"))
                    if (key.equals("feat:" + feature.path("name").asText())) available = true;
            if (!available) continue;
            source = featureSource(sheet, key);
            JsonNode automation = catalogAutomation(key, source.path("name").asText(), source.path("automation"));
            for (JsonNode trigger : automation.path("rollTriggers")) {
                if (!trigger.path("automatic").asBoolean() || !key.equals(trigger.path("on").asText())
                        || !"success".equals(trigger.path("when").asText())
                        || !"self".equals(trigger.path("audience").asText("self"))
                        || trigger.has("cost") || trigger.has("uses") || !trigger.path("steps").isArray()) continue;
                boolean effectOnly = !trigger.path("steps").isEmpty();
                for (JsonNode step : trigger.path("steps"))
                    if (!"effect".equals(step.path("op").asText()) || !step.path("effect").isObject()) effectOnly = false;
                if (!effectOnly) continue;
                JsonNode difficulty = trigger.has("difficulty") ? trigger.path("difficulty") : roll.path("difficulty");
                if (!difficulty.isNumber()) throw new IllegalArgumentException("Automatic feature success requires a numeric difficulty.");
                int hope = roll.path("rolls").path(0).asInt();
                int fear = roll.path("rolls").path(1).asInt();
                if (hope != fear && roll.path("total").asDouble() < difficulty.asDouble()) continue;
                validateTrigger(actor, key, trigger, roll);
                applyFeatureSteps(sheet, trigger.path("steps"), source.path("name").asText(), actor, key, new ArrayList<>());
                roll.withArray("featureUses").add(actor.getId() + ":" + key + ":" + trigger.path("id").asText());
                changed = true;
            }
        }
        if (!changed) return false;
        commitFeatureSheets(changedSheets, roll, true);
        for (var entry : changedSheets.entrySet()) sendFeatureState(campaign.actor(entry.getKey()), entry.getValue());
        return true;
    }

    private String invisibilityRollRef(Client client, JsonNode request) {
        if (!request.path("source").asText().contains("option:effect:")) return null;
        String ref = request.path("ref").asText();
        if (ref.isBlank() || ref.length() > 80) return null;
        return sessionId + ":" + client.playerId + ":" + request.path("actorId").asText() + ":" + ref;
    }

    private JsonNode catalogDomainCard(String name) throws IOException {
        java.nio.file.Path directory = host.compendiumDirectory();
        if (directory == null) return null;
        java.nio.file.Path file = directory.resolve("domain-cards.json");
        if (!java.nio.file.Files.isRegularFile(file)) return null;
        for (JsonNode card : MAPPER.readTree(file.toFile()).path("entries"))
            if (name.equals(card.path("name").asText())) return card;
        return null;
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
        putChatArtwork(out, fromId);
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
