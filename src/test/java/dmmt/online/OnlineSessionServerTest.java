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
    private volatile boolean drawingSupported;
    private volatile Path compendiumDir;

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
        ws.sendText("{\"type\":\"roll\",\"expression\":\"3d6\",\"kind\":\"duality\"}", true).join();
        assertFalse(inbox.next("roll").has("kind"));
        ws.sendText("{\"type\":\"chat\",\"text\":\"hi\"}", true).join();
        assertEquals("hi", inbox.next("chat").path("text").asText());
        assertEquals(4, store.historyTail(campaign.getId(), 10).size());
        ws.abort();
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
