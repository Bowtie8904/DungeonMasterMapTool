package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnlineSessionServerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path temp;

    private CampaignStore store;
    private Campaign campaign;
    private OnlineSessionServer server;
    private int port;
    private final HttpClient http = HttpClient.newHttpClient();
    private final List<String> moves = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<String> drawings = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<JsonNode> dmMessages = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile boolean drawingSupported;
    private volatile Path compendiumDir;

    private HttpResponse<String> cardHttp(String cookie, String path, JsonNode body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/chat-cards" + path))
                .header("Cookie", cookie).header("Content-Type", "application/json");
        if (body != null) builder.POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void sharedCardsTransferLiveStockAtomicallyAndRejectStaleSaves() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("source").name("Mara").ownerId(playerId()).build());
        campaign.getActors().add(Campaign.Actor.builder().id("target").name("Rook").ownerId(playerId()).build());
        ObjectNode original = (ObjectNode) MAPPER.readTree("""
                {"name":"Mara","inventory":[{"name":"Potion","type":"consumable","note":"Clear **HP**","qty":2}]}
                """);
        store.saveSheet(campaign.getId(), "source", original.toString());
        store.saveSheet(campaign.getId(), "target", "{\"name\":\"Rook\",\"inventory\":[]}");
        server.publish(state("m1", 0), "m1/l1");
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        assertTrue(inbox.next("hello").path("chatCards").asBoolean());
        inbox.next("snapshot");
        try {
            ObjectNode request = MAPPER.createObjectNode().put("actorId", "source").put("category", "item");
            request.set("item", original.path("inventory").get(0));
            HttpResponse<String> post = cardHttp(cookie, "", request);
            assertEquals(200, post.statusCode(), post.body());
            String id = MAPPER.readTree(post.body()).path("cardId").asText();
            JsonNode message = inbox.next("chat");
            assertTrue(message.path("text").asText().contains("Clear **HP**"));
            assertEquals("Potion", message.path("sharedCard").path("item").path("name").asText());
            assertEquals(2, MAPPER.readTree(store.loadSheet(campaign.getId(), "source")).path("inventory").get(0).path("qty").asInt());
            HttpResponse<String> duplicate = cardHttp(cookie, "", request);
            assertEquals(200, duplicate.statusCode());
            String duplicateId = MAPPER.readTree(duplicate.body()).path("cardId").asText();
            var grabs = new java.util.ArrayList<CompletableFuture<HttpResponse<String>>>();
            for (int i = 0; i < 3; i++) {
                grabs.add(http.sendAsync(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                                + "/api/chat-cards/" + (i == 1 ? duplicateId : id) + "/grab")).header("Cookie", cookie)
                        .POST(HttpRequest.BodyPublishers.ofString("{\"actorId\":\"target\"}")).build(),
                        HttpResponse.BodyHandlers.ofString()));
            }
            int success = 0;
            for (var grab : grabs) {
                int status = grab.get(5, TimeUnit.SECONDS).statusCode();
                if (status == 200) success++;
                else assertEquals(409, status);
            }
            assertEquals(2, success);
            assertEquals(0, MAPPER.readTree(cardHttp(cookie, "/" + id, null).body()).path("available").asInt());
            assertEquals(0, MAPPER.readTree(cardHttp(cookie, "/" + duplicateId, null).body()).path("available").asInt());
            JsonNode source = MAPPER.readTree(store.loadSheet(campaign.getId(), "source"));
            JsonNode target = MAPPER.readTree(store.loadSheet(campaign.getId(), "target"));
            assertEquals(0, source.path("inventory").get(0).path("qty").asInt());
            assertEquals(2, target.path("inventory").get(0).path("qty").asInt());
            assertEquals(2, source.path("inventoryRevision").asInt());
            assertEquals("sheetInventory", inbox.next("sheetInventory").path("type").asText());
            var stale = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/sheet/source"))
                    .header("Cookie", cookie).PUT(HttpRequest.BodyPublishers.ofString(original.toString())).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(409, stale.statusCode());
            assertEquals(source, MAPPER.readTree(stale.body()).path("sheet"));
            var current = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/sheet/source"))
                    .header("Cookie", cookie).PUT(HttpRequest.BodyPublishers.ofString(source.toString())).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, current.statusCode());
            Inbox late = new Inbox();
            WebSocket second = connect(cookie, late);
            try {
                assertEquals(2, late.next("snapshot").path("state").path("chatTail").size());
            } finally { second.abort(); }
        } finally { ws.abort(); }
    }

    @Test
    void sharedWhispersEnforceVisibilityOwnershipAndDescriptionIntegrity() throws Exception {
        Campaign.Player bob = Campaign.Player.builder().name("Bob")
                .passwordHash(PasswordHasher.hash("secret", 64, 1, 1)).build();
        campaign.getPlayers().add(bob);
        campaign.getActors().add(Campaign.Actor.builder().id("source").name("Mara").ownerId(playerId()).build());
        campaign.getActors().add(Campaign.Actor.builder().id("target").name("Rook").ownerId(bob.getId()).build());
        ObjectNode source = (ObjectNode) MAPPER.readTree("""
                {"features":[{"source":"Class","name":"Feature","text":"**Feature** description"}],
                 "loadout":[{"name":"Spell","domain":"Sage","level":1,"type":"Spell","recall":1,"text":"Spell description","spellcast":"Instinct"}],
                 "inventory":[{"name":"Torch","type":"gear","qty":1}]}
                """);
        store.saveSheet(campaign.getId(), "source", source.toString());
        store.saveSheet(campaign.getId(), "target", "{\"name\":\"Rook\"}");
        server.publish(state("m1", 0), "m1/l1");
        String aliceCookie = cookie();
        String bobCookie = playerCookie(bob);
        Inbox aliceInbox = new Inbox();
        Inbox bobInbox = new Inbox();
        WebSocket alice = connect(aliceCookie, aliceInbox);
        WebSocket bobSocket = connect(bobCookie, bobInbox);
        try {
            ObjectNode request = MAPPER.createObjectNode().put("actorId", "source").put("category", "domain").put("toId", "dm");
            request.set("item", source.path("loadout").get(0));
            assertEquals(400, cardHttp(bobCookie, "", request).statusCode(), "Cannot post another actor's entries");
            ObjectNode forged = request.deepCopy();
            ((ObjectNode) forged.get("item")).put("text", "Forged");
            assertEquals(400, cardHttp(aliceCookie, "", forged).statusCode());
            var posted = cardHttp(aliceCookie, "", request);
            assertEquals(200, posted.statusCode());
            String id = MAPPER.readTree(posted.body()).path("cardId").asText();
            assertEquals(404, cardHttp(bobCookie, "/" + id, null).statusCode());
            assertEquals(409, cardHttp(bobCookie, "/" + id + "/grab",
                    MAPPER.createObjectNode().put("actorId", "target")).statusCode());
            Inbox late = new Inbox();
            WebSocket later = connect(bobCookie, late);
            try {
                assertTrue(late.next("snapshot").path("state").path("chatTail").isEmpty(), "Private cards stay private in snapshots");
            } finally { later.abort(); }
            request.remove("toId");
            request.put("category", "feature").set("item", source.path("features").get(0));
            var feature = cardHttp(aliceCookie, "", request);
            assertEquals(200, feature.statusCode());
            String featureId = MAPPER.readTree(feature.body()).path("cardId").asText();
            assertEquals(409, cardHttp(bobCookie, "/" + featureId + "/grab",
                    MAPPER.createObjectNode().put("actorId", "target")).statusCode());
            assertEquals(source, MAPPER.readTree(store.loadSheet(campaign.getId(), "source")));
            request.put("category", "item").set("item", source.path("inventory").get(0));
            var item = cardHttp(aliceCookie, "", request);
            assertEquals(200, item.statusCode());
            String itemId = MAPPER.readTree(item.body()).path("cardId").asText();
            assertEquals(409, cardHttp(bobCookie, "/" + itemId + "/grab",
                    MAPPER.createObjectNode().put("actorId", "source")).statusCode(), "Receiving actor must be owned");
            assertEquals(200, cardHttp(bobCookie, "/" + itemId + "/grab",
                    MAPPER.createObjectNode().put("actorId", "target")).statusCode());
            assertEquals(0, MAPPER.readTree(store.loadSheet(campaign.getId(), "source")).path("inventory").get(0).path("qty").asInt());
            assertEquals(1, MAPPER.readTree(store.loadSheet(campaign.getId(), "target")).path("inventory").get(0).path("qty").asInt());
        } finally { alice.abort(); bobSocket.abort(); }
    }

    @Test
    void compendiumRequiresLoginAndServesCategoryFiles() throws Exception {
        Path dir = temp.resolve("dh");
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve("armor.json"),
                "{\"format\":\"dmmap-daggerheart\",\"category\":\"armor\",\"label\":\"Armor\",\"entries\":[{\"id\":\"a\"}]}");
        compendiumDir = dir;
        String base = "http://localhost:" + port + "/api/compendium";
        assertEquals(401, http.send(HttpRequest.newBuilder(URI.create(base)).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        String cookie = cookie();
        HttpResponse<String> index = http.send(HttpRequest.newBuilder(URI.create(base)).header("Cookie", cookie).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, index.statusCode());
        assertTrue(index.body().contains("armor"));
        HttpResponse<String> armor = http.send(
                HttpRequest.newBuilder(URI.create(base + "/armor")).header("Cookie", cookie).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, armor.statusCode());
        assertEquals("a", MAPPER.readTree(armor.body()).path("entries").get(0).path("id").asText());
        assertEquals(404, http.send(HttpRequest.newBuilder(URI.create(base + "/..%2Fx")).header("Cookie", cookie).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(404, http.send(HttpRequest.newBuilder(URI.create(base + "/missing")).header("Cookie", cookie).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
    }
    @Test
    void testingCharactersAreDmMarkedAndUsableOnlyByTheirAssignedOwner() throws Exception {
        Campaign.Actor tester = Campaign.Actor.builder().id("tester").name("Testing hero")
                .testingCharacter(true).ownerId(playerId()).build();
        campaign.getActors().add(tester);
        campaign.getActors().add(Campaign.Actor.builder().id("normal").name("Normal").ownerId(playerId()).build());
        String cookie = cookie();
        String base = "http://localhost:" + port + "/api/sheet/";
        var read = http.send(HttpRequest.newBuilder(URI.create(base + "tester")).header("Cookie", cookie).build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(MAPPER.readTree(read.body()).path("testingCharacter").asBoolean());
        ObjectNode sheet = MAPPER.createObjectNode().put("testingCharacter", true).put("className", "Warrior");
        sheet.putObject("testingBuild").put("className", "Warrior");
        for (int i = 0; i < 30; i++) sheet.withArray("loadout").addObject().put("name", "Card " + i);
        var save = http.send(HttpRequest.newBuilder(URI.create(base + "tester")).header("Cookie", cookie)
                .PUT(HttpRequest.BodyPublishers.ofString(sheet.toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, save.statusCode(), save.body());
        assertEquals(30, MAPPER.readTree(store.loadSheet(campaign.getId(), "tester")).path("loadout").size());
        var forged = http.send(HttpRequest.newBuilder(URI.create(base + "normal")).header("Cookie", cookie)
                .PUT(HttpRequest.BodyPublishers.ofString(sheet.toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, forged.statusCode());
        sheet.put("testingCharacter", false);
        assertEquals(403, http.send(HttpRequest.newBuilder(URI.create(base + "normal")).header("Cookie", cookie)
                .PUT(HttpRequest.BodyPublishers.ofString(sheet.toString())).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        sheet.remove("testingBuild");
        assertEquals(403, http.send(HttpRequest.newBuilder(URI.create(base + "normal")).header("Cookie", cookie)
                .PUT(HttpRequest.BodyPublishers.ofString(sheet.toString())).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        tester.setOwnerId("another-player");
        assertEquals(404, http.send(HttpRequest.newBuilder(URI.create(base + "tester")).header("Cookie", cookie).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(403, http.send(HttpRequest.newBuilder(URI.create(base + "tester")).header("Cookie", cookie)
                .PUT(HttpRequest.BodyPublishers.ofString(sheet.toString())).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertTrue(tester.isTestingCharacter());
    }

    @Test
    void sheetsAreStoredPerActorForTheirOwnerOnly() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("mine").name("Mara").ownerId(playerId()).build());
        campaign.getActors().add(Campaign.Actor.builder().id("other").name("Orc").ownerId("someone-else").build());
        String cookie = cookie();
        String base = "http://localhost:" + port + "/api/sheet/";
        JsonNode first = MAPPER.readTree(http.send(HttpRequest.newBuilder(URI.create(base + "mine")).header("Cookie", cookie).build(),
                HttpResponse.BodyHandlers.ofString()).body());
        assertTrue(first.path("sheet").isNull());
        HttpResponse<String> put = http.send(HttpRequest.newBuilder(URI.create(base + "mine")).header("Cookie", cookie)
                .PUT(HttpRequest.BodyPublishers.ofString("{\"name\":\"Mara\",\"level\":2}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, put.statusCode());
        JsonNode again = MAPPER.readTree(http.send(HttpRequest.newBuilder(URI.create(base + "mine")).header("Cookie", cookie).build(),
                HttpResponse.BodyHandlers.ofString()).body());
        assertEquals(2, again.path("sheet").path("level").asInt());
        assertEquals(403, http.send(HttpRequest.newBuilder(URI.create(base + "other")).header("Cookie", cookie)
                .PUT(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(400, http.send(HttpRequest.newBuilder(URI.create(base + "mine")).header("Cookie", cookie)
                .PUT(HttpRequest.BodyPublishers.ofString("[1]")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(413, http.send(HttpRequest.newBuilder(URI.create(base + "mine")).header("Cookie", cookie)
                .PUT(HttpRequest.BodyPublishers.ofString("{\"x\":\"" + "a".repeat(OnlineSessionServer.MAX_SHEET_BYTES) + "\"}"))
                .build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    private volatile OnlineSessionServer.MoveResult drawingResult = OnlineSessionServer.MoveResult.accepted();

    @BeforeEach
    void setUp() throws Exception {
        store = new CampaignStore(temp.resolve("campaigns"));
        campaign = store.create("Test");
        Campaign.Player alice = Campaign.Player.builder().name("Alice")
                .passwordHash(PasswordHasher.hash("secret", 64, 1, 1)).build();
        campaign.getPlayers().add(alice);
        store.save(campaign);
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        OnlineSessionServer.Config config = new OnlineSessionServer.Config(port, "Game", 4, 65536, 50, 100, 3, 60);
        server = new OnlineSessionServer(config, campaign, store, new AssetRegistry(temp.resolve("cache"), 2048, store),
                new OnlineSessionServer.Host() {
                    @Override
                    public Path compendiumDirectory() {
                        return compendiumDir;
                    }

                    @Override
                    public boolean supportsDrawing() {
                        return drawingSupported;
                    }

                    @Override
                    public CompletableFuture<OnlineSessionServer.MoveResult> editDrawing(String playerId, JsonNode command) {
                        if (!drawingSupported) return OnlineSessionServer.Host.super.editDrawing(playerId, command);
                        drawings.add(playerId + ":" + command.path("id").asText());
                        return CompletableFuture.completedFuture(drawingResult);
                    }

                    @Override
                    public CompletableFuture<OnlineSessionServer.MoveResult> moveToken(String playerId, String tokenId,
                                                                                         double x, double y) {
                        moves.add(playerId + ":" + tokenId);
                        return CompletableFuture.completedFuture(OnlineSessionServer.MoveResult.accepted());
                    }

                    @Override
                    public void rosterChanged() {
                    }

                    @Override
                    public void campaignChanged(Campaign c) {
                    }

                    @Override
                    public void dmMessage(JsonNode message) {
                        dmMessages.add(message.deepCopy());
                    }
                });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private String playerId() {
        return campaign.getPlayers().get(0).getId();
    }

    private HttpResponse<String> login(String password) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/login"))
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"playerId\":\"" + playerId() + "\",\"password\":\"" + password + "\"}")).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String cookie() throws Exception {
        HttpResponse<String> response = login("secret");
        assertEquals(200, response.statusCode());
        String header = response.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(header.contains("HttpOnly"));
        return header.substring(0, header.indexOf(';'));
    }

    private JsonNode availablePlayers() throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/session")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        return MAPPER.readTree(response.body()).path("players");
    }

    private void awaitConnections(int count) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (server.roster().stream().mapToInt(OnlineSessionServer.RosterEntry::connections).sum() != count
                && System.nanoTime() < end) {
            Thread.sleep(10);
        }
        assertEquals(count, server.roster().stream().mapToInt(OnlineSessionServer.RosterEntry::connections).sum());
    }

    @Test
    void loginReservesAccountAndRejectsAnotherBrowser() throws Exception {
        assertEquals(playerId(), availablePlayers().get(0).path("id").asText());
        String cookie = cookie();
        assertTrue(availablePlayers().isEmpty());
        HttpResponse<String> conflict = login("secret");
        assertEquals(409, conflict.statusCode());
        assertTrue(MAPPER.readTree(conflict.body()).path("error").asText().contains("already connected"));
        HttpResponse<String> sameBrowser = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/login"))
                        .header("Cookie", cookie)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"playerId\":\"" + playerId() + "\",\"password\":\"secret\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, sameBrowser.statusCode());
        assertTrue(sameBrowser.headers().firstValue("Set-Cookie").orElseThrow().startsWith(cookie + ";"));
    }

    @Test
    void simultaneousLoginsHaveExactlyOneWinner() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/login"))
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"playerId\":\"" + playerId() + "\",\"password\":\"secret\"}")).build();
        var first = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        var second = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(List.of(200, 409),
                java.util.stream.Stream.of(first.join().statusCode(), second.join().statusCode()).sorted().toList());
        assertTrue(availablePlayers().isEmpty());
    }

    @Test
    void sharedCookieTabsHoldAccountUntilLastDisconnect() throws Exception {
        String cookie = cookie();
        Inbox first = new Inbox();
        WebSocket ws = connect(cookie, first);
        first.next("hello");
        Inbox second = new Inbox();
        WebSocket sheet = connect(cookie, second);
        second.next("hello");
        awaitConnections(2);
        assertEquals(409, login("secret").statusCode());
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "Closing map").join();
        awaitConnections(1);
        assertTrue(availablePlayers().isEmpty());
        sheet.sendClose(WebSocket.NORMAL_CLOSURE, "Closing sheet").join();
        awaitConnections(0);
        assertEquals(1, availablePlayers().size());

        Inbox reconnected = new Inbox();
        WebSocket again = connect(cookie, reconnected);
        reconnected.next("hello");
        assertTrue(availablePlayers().isEmpty());
        again.sendClose(WebSocket.NORMAL_CLOSURE, "Done").join();
        awaitConnections(0);
        cookie();
        assertThrows(Exception.class, () -> connect(cookie, new Inbox()));
    }

    @Test
    void logoutClosesAllTabsAndReleasesAccount() throws Exception {
        String cookie = cookie();
        Inbox inbox = new Inbox();
        connect(cookie, inbox);
        inbox.next("hello");
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/logout"))
                        .header("Cookie", cookie).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("Logged out", inbox.next("kicked").path("reason").asText());
        awaitConnections(0);
        assertEquals(1, availablePlayers().size());
        assertThrows(Exception.class, () -> connect(cookie, new Inbox()));
        assertEquals(200, login("secret").statusCode());
    }

    @Test
    void kickReleasesAccountAndInvalidatesItsCookie() throws Exception {
        String cookie = cookie();
        Inbox inbox = new Inbox();
        connect(cookie, inbox);
        inbox.next("hello");
        server.kick(playerId(), "Removed by DM");
        assertEquals("Removed by DM", inbox.next("kicked").path("reason").asText());
        awaitConnections(0);
        assertEquals(1, availablePlayers().size());
        assertThrows(Exception.class, () -> connect(cookie, new Inbox()));
        assertEquals(200, login("secret").statusCode());
    }

    @Test
    void abandonedLoginReservationExpires() throws Exception {
        String cookie = cookie();
        assertTrue(availablePlayers().isEmpty());
        Thread.sleep(OnlineSessionServer.LOGIN_RESERVATION_MILLIS + 100);
        assertEquals(1, availablePlayers().size());
        assertEquals(200, login("secret").statusCode());
        assertThrows(Exception.class, () -> connect(cookie, new Inbox()));
    }

    private static final class Inbox implements WebSocket.Listener {
        final BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                try {
                    messages.add(MAPPER.readTree(buffer.toString()));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                buffer.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        JsonNode next(String type) throws Exception {
            long end = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < end) {
                JsonNode node = messages.poll(200, TimeUnit.MILLISECONDS);
                if (node != null && type.equals(node.path("type").asText())) {
                    return node;
                }
            }
            throw new AssertionError("No " + type + " message");
        }
    }

    private WebSocket connect(String cookie, Inbox inbox) {
        return http.newWebSocketBuilder().header("Cookie", cookie)
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), inbox).orTimeout(5, TimeUnit.SECONDS).join();
    }

    private ObjectNode state(String mapId, double tokenX) {
        ObjectNode state = MAPPER.createObjectNode();
        state.put("mapId", mapId).put("levelId", "l1");
        state.putArray("walls");
        state.putArray("tokens").addObject().put("id", "t1").put("x", tokenX).put("y", 0);
        return state;
    }

    @Test
    void drawingsUseAuthenticatedIdentityAndReturnHostResultsWhileRespectingPause() throws Exception {
        drawingSupported = true;
        server.publish(state("m1", 0), "m1/l1");
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie(), inbox);
        assertTrue(inbox.next("hello").path("drawingTools").asBoolean());
        inbox.next("snapshot");
        String command = "{\"type\":\"command\",\"cmdId\":\"draw\",\"kind\":\"drawing\",\"id\":\"effect\","
                + "\"action\":\"create\",\"playerId\":\"spoofed\",\"mapId\":\"m1\",\"levelId\":\"l1\"}";
        ws.sendText(command, true).join();
        JsonNode ack = inbox.next("ack");
        assertEquals("draw", ack.path("cmdId").asText());
        assertEquals(server.seq(), ack.path("seq").asLong());
        assertEquals(List.of(playerId() + ":effect"), drawings);
        drawingResult = OnlineSessionServer.MoveResult.rejected("Only the owner or DM may edit this drawing");
        ws.sendText(command, true).join();
        assertEquals(drawingResult.reason(), inbox.next("reject").path("reason").asText());
        server.setPaused(true);
        ws.sendText(command, true).join();
        assertEquals("paused", inbox.next("reject").path("reason").asText());
        assertEquals(2, drawings.size());
        ws.abort();
    }

    @Test
    void loginIsLockedAfterRepeatedFailures() throws Exception {
        assertEquals(401, login("nope").statusCode());
        assertEquals(401, login("nope").statusCode());
        assertEquals(401, login("nope").statusCode());
        HttpResponse<String> locked = login("secret");
        assertEquals(429, locked.statusCode());
        assertTrue(MAPPER.readTree(locked.body()).path("retryAfterSeconds").asInt() > 0);
    }

    @Test
    void unauthenticatedSocketIsRefused() {
        assertThrows(Exception.class, () -> connect("dmmt_online=bogus", new Inbox()));
    }

    @Test
    void snapshotDeltaAndResyncKeepOrder() throws Exception {
        server.publish(state("m1", 0), "m1/l1");
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie(), inbox);
        assertEquals(playerId(), inbox.next("hello").path("playerId").asText());
        JsonNode snapshot = inbox.next("snapshot");
        long seq = snapshot.path("seq").asLong();

        server.publish(state("m1", 10), "m1/l1");
        server.publish(state("m1", 20), "m1/l1");
        JsonNode d1 = inbox.next("delta");
        JsonNode d2 = inbox.next("delta");
        assertEquals(seq + 1, d1.path("seq").asLong());
        assertEquals(seq + 2, d2.path("seq").asLong());
        assertEquals(20, d2.path("ops").get(0).path("upsert").get(0).path("x").asInt());

        ws.sendText("{\"type\":\"resync\",\"lastSeq\":" + seq + "}", true).join();
        assertEquals(seq + 1, inbox.next("delta").path("seq").asLong());
        assertEquals(seq + 2, inbox.next("delta").path("seq").asLong());

        ws.sendText("{\"type\":\"resync\",\"lastSeq\":0}", true).join();
        assertNotNull(inbox.next("snapshot"));
        server.publish(state("m2", 0), "m2/l1");
        assertEquals("m2", inbox.next("snapshot").path("state").path("mapId").asText());
        ws.abort();
    }

    @Test
    void pauseRejectsMovesAndResumeAllowsThem() throws Exception {
        server.publish(state("m1", 5), "m1/l1");
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie(), inbox);
        inbox.next("snapshot");
        server.setPaused(true);
        assertTrue(inbox.next("pause").path("paused").asBoolean());
        ws.sendText("{\"type\":\"command\",\"cmdId\":\"c1\",\"kind\":\"moveToken\",\"tokenId\":\"t1\",\"x\":1,\"y\":1}", true)
                .join();
        JsonNode rejected = inbox.next("reject");
        assertEquals("paused", rejected.path("reason").asText());
        assertEquals(5, rejected.path("token").path("x").asInt());
        assertTrue(moves.isEmpty());

        server.setPaused(false);
        inbox.next("pause");
        ws.sendText("{\"type\":\"command\",\"cmdId\":\"c2\",\"kind\":\"moveToken\",\"tokenId\":\"t1\",\"x\":1,\"y\":1}", true)
                .join();
        assertEquals("c2", inbox.next("ack").path("cmdId").asText());
        assertEquals(1, moves.size());
        ws.abort();
    }

    @Test
    void exploredFogSurvivesReconnectAndMapSwitches() throws Exception {
        server.publish(state("m1", 0), "m1/l1");
        String cookie = cookie();
        Inbox first = new Inbox();
        WebSocket ws = connect(cookie, first);
        first.next("snapshot");
        BitSet cells = new BitSet();
        cells.set(3);
        cells.set(9);
        ws.sendText("{\"type\":\"fogExplored\",\"mapId\":\"m1\",\"levelId\":\"l1\",\"cells\":\""
                + OnlineSessionServer.encodeBits(cells) + "\"}", true).join();
        long end = System.currentTimeMillis() + 3000;
        while (store.loadFog(campaign.getId(), playerId(), "m1", "l1").isEmpty() && System.currentTimeMillis() < end) {
            Thread.sleep(50);
        }
        assertEquals(cells, store.loadFog(campaign.getId(), playerId(), "m1", "l1"));
        ws.abort();

        server.publish(state("m2", 0), "m2/l1");
        server.publish(state("m1", 0), "m1/l1");
        Inbox second = new Inbox();
        WebSocket again = connect(cookie, second);
        JsonNode snapshot = second.next("snapshot");
        BitSet restored = OnlineSessionServer.decodeBits(snapshot.path("state").path("fogMemory").path("cells").asText());
        assertEquals(cells, restored);
        assertFalse(store.loadFog(campaign.getId(), playerId(), "m2", "l1").intersects(cells));
        again.abort();
    }

    @Test
    void keptDiceCapabilityAllowsDamageAndPreservesStrictDualityShape() throws Exception {
        server.publish(state("m1", 0), "m1/l1");
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie(), inbox);
        assertTrue(inbox.next("hello").path("keptDice").asBoolean());
        inbox.next("snapshot");
        try {
            ws.sendText("""
                    {"type":"roll","expression":"4d6kh3+6","label":"Powerful damage","contributions":[
                      {"label":"Powerful weapon","expression":"4d6kh3"},
                      {"label":"Weapon modifier","expression":"6"}]}
                    """, true).join();
            JsonNode result = inbox.next("roll");
            assertEquals(4, result.path("rolls").size());
            List<Integer> values = new ArrayList<>();
            result.path("rolls").forEach(value -> values.add(value.asInt()));
            int keptSum = values.stream().mapToInt(Integer::intValue).sum()
                    - values.stream().mapToInt(Integer::intValue).min().orElseThrow();
            assertEquals(keptSum + 6, result.path("total").asInt());
            assertEquals(result.path("rolls"), result.path("breakdown").get(0).path("rolls"));
            assertEquals(keptSum, result.path("breakdown").get(0).path("total").asInt());
            assertEquals(6, result.path("breakdown").get(1).path("total").asInt());
            assertEquals(result, store.historyTail(campaign.getId(), 1).getFirst());
            for (String expression : List.of("3d12kh2", "2d12+2d6kh1", "2d12kh2")) {
                ws.sendText(MAPPER.createObjectNode().put("type", "roll").put("expression", expression)
                        .put("kind", "duality").put("difficulty", 12).toString(), true).join();
                JsonNode notDuality = inbox.next("roll");
                assertFalse(notDuality.has("kind"));
                assertFalse(notDuality.has("difficulty"));
            }
            ws.sendText("{\"type\":\"roll\",\"expression\":\"4d6kh5\"}", true).join();
            assertTrue(inbox.next("error").path("message").asText().contains("Invalid dice"));
        } finally {
            ws.abort();
        }
    }

    @Test
    void chatAndRollsAreBroadcastAndPersisted() throws Exception {
        server.publish(state("m1", 0), "m1/l1");
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie(), inbox);
        inbox.next("snapshot");
        ws.sendText("{\"type\":\"roll\",\"expression\":\"2d6+1\"}", true).join();
        JsonNode roll = inbox.next("roll");
        assertEquals(2, roll.path("rolls").size());
        ws.sendText("{\"type\":\"roll\",\"expression\":\"2d12+1d6+2\",\"label\":\"Agility\",\"kind\":\"duality\",\"difficulty\":12}", true).join();
        JsonNode duality = inbox.next("roll");
        assertEquals("Agility", duality.path("label").asText());
        assertEquals("duality", duality.path("kind").asText());
        assertEquals(12, duality.path("difficulty").asInt());
        ws.sendText("""
                {"type":"roll","expression":"2d12-1d6+3+1d4","kind":"duality","contributions":[
                  {"label":"Hope die","expression":"1d12"},{"label":"Fear die","expression":"1d12"},
                  {"label":"Disadvantage","expression":"-1d6"},{"label":"Agility","expression":"2"},
                  {"label":"Potion","expression":"1+1d4","notes":[{"label":"Potion bonus die","impact":"+1d4"}]}]}
                """, true).join();
        JsonNode detailed = inbox.next("roll");
        assertEquals("duality", detailed.path("kind").asText());
        assertEquals(5, detailed.path("breakdown").size());
        assertEquals("Potion", detailed.path("breakdown").get(4).path("label").asText());
        assertEquals("+1d4", detailed.path("breakdown").get(4).path("notes").get(0).path("impact").asText());
        int sum = 0;
        for (JsonNode part : detailed.path("breakdown")) sum += part.path("total").asInt();
        assertEquals(detailed.path("total").asInt(), sum);
        assertEquals(detailed, store.historyTail(campaign.getId(), 1).getFirst());
        ws.sendText("""
                {"type":"roll","expression":"2d12+2","contributions":[{"label":"False bonus","expression":"2d12+99"}]}
                """, true).join();
        assertTrue(inbox.next("error").path("message").asText().contains("breakdown"));
        ws.sendText("{\"type\":\"roll\",\"expression\":\"3d6\",\"kind\":\"duality\"}", true).join();
        assertFalse(inbox.next("roll").has("kind"));
        ws.sendText("{\"type\":\"chat\",\"text\":\"hi\"}", true).join();
        assertEquals("hi", inbox.next("chat").path("text").asText());
        assertEquals(5, store.historyTail(campaign.getId(), 10).size());
        ws.abort();
    }

    @Test
    void rollSourceDescriptionsPersistReplayAndRejectInvalidFields() throws Exception {
        server.publish(state("m1", 0), "m1/l1");
        Inbox inbox = new Inbox();
        String cookie = cookie();
        WebSocket ws = connect(cookie, inbox);
        inbox.next("snapshot");
        try {
            ObjectNode source = MAPPER.createObjectNode().put("name", "Arcane Bolt")
                    .put("text", "Make a **Spellcast Roll**.").put("source", "Codex - Level 1 Spell");
            source.putObject("automation").put("untrusted", true);
            ObjectNode request = MAPPER.createObjectNode().put("type", "roll").put("expression", "2d12+2")
                    .put("kind", "duality").put("label", "Spellcast (Knowledge) - Arcane Bolt");
            request.set("rollSource", source);
            ws.sendText(request.toString(), true).join();
            JsonNode roll = inbox.next("roll");
            source.remove("automation");
            assertEquals(source, roll.path("rollSource"));
            assertEquals(2, roll.path("rolls").size());
            assertEquals(roll, store.historyTail(campaign.getId(), 1).getFirst());

            request.put("type", "chat").put("text", "Feature: rolled 1d4 [3]");
            ws.sendText(request.toString(), true).join();
            assertEquals(source, inbox.next("chat").path("rollSource"));
            Inbox late = new Inbox();
            WebSocket second = connect(cookie, late);
            try {
                JsonNode tail = late.next("snapshot").path("state").path("chatTail");
                assertEquals(2, tail.size());
                for (JsonNode entry : tail) assertEquals(source, entry.path("rollSource"));
            } finally { second.abort(); }

            for (String type : List.of("roll", "chat")) {
                request.put("type", type);
                for (JsonNode invalid : List.of(MAPPER.readTree("[]"),
                        source.deepCopy().put("name", ""),
                        source.deepCopy().put("name", "n".repeat(241)),
                        source.deepCopy().put("text", "x".repeat(16_001)),
                        source.deepCopy().put("source", 1))) {
                    request.set("rollSource", invalid);
                    ws.sendText(request.toString(), true).join();
                    assertTrue(inbox.next("error").path("message").asText().contains("roll source"));
                }
            }
            assertEquals(2, store.historyTail(campaign.getId(), 20).size());
        } finally { ws.abort(); }
    }

    @Test
    void rollsEchoRollButtonHints() throws Exception {
        server.publish(state("m1", 0), "m1/l1");
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie(), inbox);
        inbox.next("snapshot");
        ws.sendText("{\"type\":\"roll\",\"expression\":\"2d6\",\"ref\":\"r1\",\"actorId\":\"a1\",\"source\":\"attack,trait:Agility\",\"weapon\":\"Sword\"}", true).join();
        JsonNode roll = inbox.next("roll");
        assertEquals("r1", roll.path("ref").asText());
        assertEquals("a1", roll.path("actorId").asText());
        assertEquals("attack,trait:Agility", roll.path("source").asText());
        assertEquals("Sword", roll.path("weapon").asText());
        ws.sendText("{\"type\":\"roll\",\"expression\":\"2d6\"}", true).join();
        assertFalse(inbox.next("roll").has("ref"));
        ws.abort();
    }

    @Test
    void playerWhispersAndRollsReachOnlySenderAndRecipientIncludingTheirOtherTabs() throws Exception {
        server.publish(state("m1", 0), "m1/l1");
        Campaign.Player bob = Campaign.Player.builder().name("Bob")
                .passwordHash(PasswordHasher.hash("secret", 64, 1, 1)).build();
        Campaign.Player cara = Campaign.Player.builder().name("Cara")
                .passwordHash(PasswordHasher.hash("secret", 64, 1, 1)).build();
        campaign.getPlayers().addAll(List.of(bob, cara));
        Inbox sender = new Inbox();
        Inbox senderTab = new Inbox();
        Inbox recipient = new Inbox();
        Inbox outsider = new Inbox();
        String aliceCookie = cookie();
        WebSocket alice = connect(aliceCookie, sender);
        WebSocket aliceTab = connect(aliceCookie, senderTab);
        WebSocket bobSocket = connect(playerCookie(bob), recipient);
        WebSocket caraSocket = connect(playerCookie(cara), outsider);
        assertTrue(sender.next("hello").path("playerWhispers").asBoolean());
        sender.next("snapshot");
        senderTab.next("snapshot");
        recipient.next("snapshot");
        outsider.next("snapshot");
        try {
            alice.sendText(MAPPER.createObjectNode().put("type", "chat").put("text", "Secret")
                    .put("toId", bob.getId()).put("fromId", cara.getId()).put("to", "Forged").toString(), true).join();
            JsonNode chat = sender.next("chat");
            assertEquals(playerId(), chat.path("fromId").asText());
            assertEquals(bob.getId(), chat.path("toId").asText());
            assertEquals("Bob", chat.path("to").asText());
            assertEquals(chat, senderTab.next("chat"));
            assertEquals(chat, recipient.next("chat"));
            ObjectNode source = MAPPER.createObjectNode().put("name", "Private feature")
                    .put("text", "Private rules").put("source", "Class");
            alice.sendText(MAPPER.createObjectNode().put("type", "roll").put("expression", "1d20")
                    .put("toId", bob.getId()).set("rollSource", source).toString(), true).join();
            JsonNode roll = sender.next("roll");
            assertEquals(source, roll.path("rollSource"));
            assertEquals(bob.getId(), roll.path("toId").asText());
            assertEquals("Bob", roll.path("to").asText());
            assertEquals(roll, recipient.next("roll"));
            assertEquals(roll, senderTab.next("roll"));
            assertEquals(List.of(chat, roll), store.historyTail(campaign.getId(), 10));

            caraSocket.sendText("{\"type\":\"chat\",\"text\":\"Public barrier\"}", true).join();
            JsonNode publicMessage;
            do {
                publicMessage = outsider.messages.poll(5, TimeUnit.SECONDS);
                assertNotNull(publicMessage);
                assertFalse(publicMessage.has("toId"), "Other players must not receive private chat or rolls");
            } while (!"chat".equals(publicMessage.path("type").asText()));
            assertEquals("Public barrier", publicMessage.path("text").asText());
            assertFalse(dmMessages.stream().anyMatch(m -> m.has("toId")));
        } finally {
            alice.abort(); aliceTab.abort(); bobSocket.abort(); caraSocket.abort();
        }
    }

    @Test
    void whispersValidateRecipientsAndKeepLegacyDmRouting() throws Exception {
        server.publish(state("m1", 0), "m1/l1");
        Campaign.Player offline = Campaign.Player.builder().name("Offline").build();
        campaign.getPlayers().add(offline);
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie(), inbox);
        inbox.next("snapshot");
        try {
            for (String type : List.of("chat", "roll")) {
                for (String to : List.of("unknown-player", offline.getId())) {
                    ws.sendText(MAPPER.createObjectNode().put("type", type).put("text", "Secret")
                            .put("expression", "1d20").put("toId", to).toString(), true).join();
                    assertTrue(inbox.next("error").path("message").asText().contains("recipient"));
                }
            }
            assertTrue(store.historyTail(campaign.getId(), 10).isEmpty());
            assertTrue(dmMessages.isEmpty());
            ws.sendText("{\"type\":\"chat\",\"text\":\"Legacy DM\",\"whisper\":true}", true).join();
            JsonNode chat = inbox.next("chat");
            assertEquals("dm", chat.path("toId").asText());
            assertEquals("Gamemaster", chat.path("to").asText());
            ws.sendText("{\"type\":\"roll\",\"expression\":\"1d20\",\"whisper\":true}", true).join();
            assertEquals("dm", inbox.next("roll").path("toId").asText());
            ws.sendText("{\"type\":\"chat\",\"text\":\"New DM\",\"toId\":\"dm\"}", true).join();
            assertEquals("dm", inbox.next("chat").path("toId").asText());
            server.dmChat("Reply", playerId());
            assertEquals(playerId(), inbox.next("chat").path("toId").asText());
            ws.sendText("{\"type\":\"chat\",\"text\":\"Public\"}", true).join();
            assertFalse(inbox.next("chat").has("toId"));
            assertEquals(5, store.historyTail(campaign.getId(), 10).size());
        } finally {
            ws.abort();
        }
    }

    private String playerCookie(Campaign.Player player) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/login"))
                        .POST(HttpRequest.BodyPublishers.ofString(MAPPER.createObjectNode()
                                .put("playerId", player.getId()).put("password", "secret").toString())).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
    }

    @Test
    void sharedMeasurementsAreOwnedTemporaryAndAvailableToLateJoiners() throws Exception {
        server.publish(state("m1", 0), "m1/l1");
        String cookie = cookie();
        Inbox first = new Inbox();
        WebSocket ws = connect(cookie, first);
        first.next("snapshot");
        first.next("measurements");
        ws.sendText("""
                {"type":"measurement","cmdId":"m1","action":"upsert","mapKey":"m1/l1",
                 "id":"ruler","kind":"distance","x1":0,"y1":0,"x2":30,"y2":40,"ownerId":"forged"}
                """, true).join();
        JsonNode created = first.next("measurements").path("items").get(0);
        assertEquals(playerId(), created.path("ownerId").asText());
        Inbox late = new Inbox();
        WebSocket second = connect(cookie, late);
        assertEquals(1, late.next("measurements").path("items").size());
        server.dmMeasurement(server.measurements().getFirst().translate(10, 20), false);
        assertEquals(10, first.next("measurements").path("items").get(0).path("x1").asInt());
        server.publish(state("m2", 0), "m2/l1");
        assertTrue(first.next("measurements").path("items").isEmpty());
        assertTrue(server.measurements().isEmpty());
        ws.sendText("""
                {"type":"measurement","cmdId":"stale","action":"upsert","mapKey":"m1/l1",
                 "id":"ruler","kind":"distance","x1":0,"y1":0,"x2":30,"y2":40}
                """, true).join();
        assertEquals("stale", first.next("reject").path("cmdId").asText());
        assertTrue(server.measurements().isEmpty());
        ws.abort(); second.abort();
    }

    @Test
    void measurementsRejectNonOwnerEditsAndPauseButAllowDmDeletion() throws Exception {
        server.publish(state("m1", 0), "m1/l1");
        Campaign.Player bob = Campaign.Player.builder().name("Bob")
                .passwordHash(PasswordHasher.hash("secret", 64, 1, 1)).build();
        campaign.getPlayers().add(bob);
        Inbox aliceInbox = new Inbox();
        WebSocket alice = connect(cookie(), aliceInbox);
        aliceInbox.next("measurements");
        alice.sendText("""
                {"type":"measurement","action":"upsert","mapKey":"m1/l1","id":"r",
                 "kind":"rect","x1":0,"y1":0,"x2":30,"y2":40}
                """, true).join();
        aliceInbox.next("measurements");
        var login = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/login"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"playerId\":\"" + bob.getId() + "\",\"password\":\"secret\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        String bobCookie = login.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
        Inbox bobInbox = new Inbox();
        WebSocket other = connect(bobCookie, bobInbox);
        bobInbox.next("measurements");
        other.sendText("""
                {"type":"measurement","cmdId":"forbidden","action":"delete","mapKey":"m1/l1","id":"r"}
                """, true).join();
        assertTrue(bobInbox.next("reject").path("reason").asText().contains("owner"));
        server.setPaused(true);
        alice.sendText("""
                {"type":"measurement","cmdId":"paused","action":"delete","mapKey":"m1/l1","id":"r"}
                """, true).join();
        assertEquals("paused", aliceInbox.next("reject").path("reason").asText());
        assertEquals(1, server.measurements().size());
        server.dmMeasurement(server.measurements().getFirst(), true);
        assertTrue(server.measurements().isEmpty());
        alice.abort(); other.abort();
    }

    @Test
    void rosterExposesHostMeasuredLatencyAndToolsAreOptionalForOlderHosts() throws Exception {
        server.publish(state("m1", 0), "m1/l1");
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie(), inbox);
        JsonNode hello = inbox.next("hello");
        assertFalse(hello.path("drawingTools").asBoolean());
        assertTrue(hello.path("measurementTools").asBoolean());
        JsonNode roster = inbox.next("roster").path("players").get(0);
        assertEquals(playerId(), roster.path("playerId").asText());
        assertEquals(-1, roster.path("pingMs").asLong());
        server.pingClients();
        long id = inbox.next("ping").path("id").asLong();
        ws.sendText("{\"type\":\"pong\",\"id\":" + id + "}", true).join();
        long end = System.currentTimeMillis() + 4000;
        while (server.roster().getFirst().pingMs() < 0 && System.currentTimeMillis() < end) Thread.sleep(10);
        assertTrue(server.roster().getFirst().pingMs() >= 0);
        ws.abort();
    }
}
