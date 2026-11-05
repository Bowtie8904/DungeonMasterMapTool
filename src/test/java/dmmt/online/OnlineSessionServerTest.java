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

    private ObjectNode communitySheet(String name) throws Exception {
        compendiumDir = Path.of("dmmap-daggerheart");
        JsonNode entry = java.util.stream.StreamSupport.stream(
                MAPPER.readTree(compendiumDir.resolve("communities.json").toFile()).path("entries").spliterator(), false)
                .filter(item -> name.equals(item.path("name").asText())).findFirst().orElseThrow();
        ObjectNode sheet = MAPPER.createObjectNode().put("level", 3);
        sheet.set("heritage", entry.path("features").deepCopy());
        for (JsonNode feature : sheet.path("heritage")) ((ObjectNode) feature).put("source", "Community - " + name);
        sheet.putObject("usage");
        sheet.putObject("resources");
        sheet.putObject("hope").put("on", 5).put("slots", 6);
        sheet.putObject("hp").put("on", 4).put("slots", 6);
        sheet.putObject("stress").put("on", 3).put("slots", 6);
        sheet.putObject("armorTrack").put("on", 3).put("slots", 6);
        return sheet;
    }

    private HttpResponse<String> featureHttp(String cookie, ObjectNode body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/feature"))
                .header("Cookie", cookie).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
    }

    private ObjectNode bardSheet(String subclass) throws Exception {
        ObjectNode sheet = communitySheet("Seaborne");
        sheet.put("className", "Bard").put("subclass", subclass).put("level", 5);
        sheet.putArray("effects");
        var features = sheet.putArray("features");
        for (JsonNode entry : MAPPER.readTree(compendiumDir.resolve("classes.json").toFile()).path("entries")) {
            if ("bard".equals(entry.path("id").asText()) || entry.path("id").asText().startsWith("bard-" + subclass.toLowerCase(java.util.Locale.ROOT))) {
                for (JsonNode feature : entry.path("features")) {
                    ObjectNode learned = feature.deepCopy();
                    learned.put("source", "class".equals(entry.path("kind").asText()) ? "Class feature - Bard"
                            : "Subclass " + entry.path("stage").asText() + " - " + subclass);
                    features.add(learned);
                }
            }
        }
        return sheet;
    }

    private String allyCookie(String id) throws Exception {
        campaign.getPlayers().add(Campaign.Player.builder().id(id).name("Ally")
                .passwordHash(PasswordHasher.hash("secret", 64, 1, 1)).build());
        var response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/login"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"playerId\":\"" + id + "\",\"password\":\"secret\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
    }

    private ObjectNode benefitRequest(String actorId, JsonNode message, String benefit) {
        ObjectNode request = MAPPER.createObjectNode().put("actorId", actorId).put("benefitId", benefit);
        request.putObject("roll").put("id", message.path("id").asLong()).put("ts", message.path("ts").asLong());
        return request;
    }

    @Test
    void bardRallyGrantsResolvedDiceToOfflineActorsAndClearsThemOnlyOnNewSessions() throws Exception {
        campaign.getPlayers().add(Campaign.Player.builder().id("offline").name("Offline").build());
        campaign.getActors().add(Campaign.Actor.builder().id("bard").name("Bard").ownerId(playerId()).build());
        campaign.getActors().add(Campaign.Actor.builder().id("ally").name("Ally").ownerId("offline").build());
        campaign.getActors().add(Campaign.Actor.builder().id("unsaved").name("Unsaved").ownerId("offline").build());
        campaign.getActors().add(Campaign.Actor.builder().id("npc").name("NPC").build());
        store.saveSheet(campaign.getId(), "ally", communitySheet("Seaborne").toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            assertTrue(inbox.next("hello").path("partyFeatures").asBoolean());
            for (int level : List.of(1, 5, 10)) {
                ObjectNode bard = bardSheet(level == 10 ? "Wordsmith" : "Troubadour");
                bard.put("level", level);
                store.saveSheet(campaign.getId(), "bard", bard.toString());
                ObjectNode request = MAPPER.createObjectNode().put("actorId", "bard").put("key", "feat:Rally").put("actionId", "use");
                var response = featureHttp(cookie, request);
                assertEquals(200, response.statusCode(), response.body());
                String dice = level == 1 ? "1d6" : level == 5 ? "1d8" : "1d10";
                for (String actor : List.of("bard", "ally")) {
                    JsonNode saved = MAPPER.readTree(store.loadSheet(campaign.getId(), actor));
                    assertEquals(1, saved.path("effects").size());
                    JsonNode effect = saved.path("effects").path(0);
                    assertEquals("session", effect.path("until").asText());
                    assertEquals(dice, effect.path("rollOptions").path(0).path("dice").asText());
                    assertEquals(dice, effect.path("actions").path(0).path("steps").path(0).path("amount").asText());
                }
                assertEquals(400, featureHttp(cookie, request).statusCode());
            }
            JsonNode before = MAPPER.readTree(store.loadSheet(campaign.getId(), "ally"));
            ws.abort();
            awaitConnections(0);
            ws = connect(cookie, new Inbox());
            assertEquals(before, MAPPER.readTree(store.loadSheet(campaign.getId(), "ally")));
            assertEquals(null, store.loadSheet(campaign.getId(), "unsaved"));
            ws.abort();
            awaitConnections(0);
            server.stop();
            server.start();
            assertEquals(0, MAPPER.readTree(store.loadSheet(campaign.getId(), "ally")).path("effects").size());
            assertFalse(MAPPER.readTree(store.loadSheet(campaign.getId(), "bard")).path("usage").has("feat:Rally"));
        } finally { ws.abort(); }
    }

    @Test
    void bardSongsApplyOwnerBenefitsAndPersistOneClaimPerPlayerWithVirtuosoLimits() throws Exception {
        String allyCookie = allyCookie("ally-player");
        campaign.getActors().add(Campaign.Actor.builder().id("bard").name("Bard").ownerId(playerId()).build());
        campaign.getActors().add(Campaign.Actor.builder().id("ally").name("Ally").ownerId("ally-player").build());
        campaign.getActors().add(Campaign.Actor.builder().id("second").name("Second").ownerId("ally-player").build());
        store.saveSheet(campaign.getId(), "bard", bardSheet("Troubadour").toString());
        store.saveSheet(campaign.getId(), "ally", communitySheet("Seaborne").toString());
        store.saveSheet(campaign.getId(), "second", communitySheet("Seaborne").toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            ObjectNode action = MAPPER.createObjectNode().put("actorId", "bard").put("key", "feat:Relaxing Song").put("actionId", "use");
            for (int use = 0; use < 2; use++) {
                var response = featureHttp(cookie, action);
                assertEquals(200, response.statusCode(), response.body());
                JsonNode message = inbox.next("chat");
                assertEquals(3 - use, MAPPER.readTree(store.loadSheet(campaign.getId(), "bard")).path("hp").path("on").asInt());
                ObjectNode claim = benefitRequest("ally", message, "heal");
                assertEquals(200, featureHttp(allyCookie, claim).statusCode());
                assertEquals(3 - use, MAPPER.readTree(store.loadSheet(campaign.getId(), "ally")).path("hp").path("on").asInt());
                assertEquals(400, featureHttp(allyCookie, claim.put("actorId", "second")).statusCode());
                assertEquals(400, featureHttp(cookie, claim.put("actorId", "bard")).statusCode());
                JsonNode updated = inbox.next("chatUpdate").path("message");
                assertEquals("ally-player", updated.path("actionBenefits").path("claimedBy").path(0).asText());
                assertTrue(store.historyTail(campaign.getId(), Integer.MAX_VALUE).stream().anyMatch(entry -> entry.equals(updated)));
            }
            assertEquals(400, featureHttp(cookie, action).statusCode());
            action.put("key", "feat:Heartbreaking Song");
            assertEquals(200, featureHttp(cookie, action).statusCode());
            JsonNode song = inbox.next("chat");
            assertEquals(6, MAPPER.readTree(store.loadSheet(campaign.getId(), "bard")).path("hope").path("on").asInt());
            assertEquals(200, featureHttp(allyCookie, benefitRequest("ally", song, "hope")).statusCode());
            assertEquals(6, MAPPER.readTree(store.loadSheet(campaign.getId(), "ally")).path("hope").path("on").asInt());
            action.put("key", "feat:Epic Song");
            assertEquals(400, featureHttp(cookie, action).statusCode());
        } finally { ws.abort(); }
    }

    @Test
    void maestroAndRousingSpeechClaimsValidateChoicesCapacityAndSession() throws Exception {
        String allyCookie = allyCookie("ally-player");
        campaign.getActors().add(Campaign.Actor.builder().id("bard").name("Bard").ownerId(playerId()).build());
        campaign.getActors().add(Campaign.Actor.builder().id("ally").name("Ally").ownerId("ally-player").build());
        store.saveSheet(campaign.getId(), "bard", bardSheet("Troubadour").toString());
        ObjectNode ally = communitySheet("Seaborne");
        ((ObjectNode) ally.path("hope")).put("on", 6);
        store.saveSheet(campaign.getId(), "ally", ally.toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        JsonNode rally;
        try {
            ObjectNode action = MAPPER.createObjectNode().put("actorId", "bard").put("key", "feat:Rally").put("actionId", "use");
            assertEquals(200, featureHttp(cookie, action).statusCode());
            rally = inbox.next("chat");
            assertEquals(2, rally.path("actionBenefits").path("options").size());
            assertEquals(400, featureHttp(allyCookie, benefitRequest("ally", rally, "hope")).statusCode());
            assertEquals(400, featureHttp(allyCookie, benefitRequest("ally", rally, "invented")).statusCode());
            assertEquals(200, featureHttp(allyCookie, benefitRequest("ally", rally, "stress")).statusCode());
            assertEquals(2, MAPPER.readTree(store.loadSheet(campaign.getId(), "ally")).path("stress").path("on").asInt());
            assertEquals(400, featureHttp(allyCookie, benefitRequest("ally", rally, "hope")).statusCode());
            store.saveSheet(campaign.getId(), "bard", bardSheet("Wordsmith").toString());
            action.put("key", "feat:Rousing Speech");
            assertEquals(200, featureHttp(cookie, action).statusCode());
            JsonNode speech = inbox.next("chat");
            assertEquals(200, featureHttp(allyCookie, benefitRequest("ally", speech, "stress")).statusCode());
            assertEquals(0, MAPPER.readTree(store.loadSheet(campaign.getId(), "ally")).path("stress").path("on").asInt());
            assertEquals(400, featureHttp(cookie, action).statusCode());
            assertEquals(3, MAPPER.readTree(store.loadSheet(campaign.getId(), "bard")).path("stress").path("on").asInt());
        } finally { ws.abort(); }
        server.stop();
        server.start();
        String newAllyCookie = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/login"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"playerId\":\"ally-player\",\"password\":\"secret\"}")).build(),
                HttpResponse.BodyHandlers.ofString()).headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
        assertEquals(400, featureHttp(newAllyCookie, benefitRequest("ally", rally, "stress")).statusCode());
    }

    @Test
    void interruptedSongClaimRecoversTheSheetAndMarkerBeforeRetrying() throws Exception {
        String allyCookie = allyCookie("ally-player");
        campaign.getActors().add(Campaign.Actor.builder().id("bard").name("Bard").ownerId(playerId()).build());
        campaign.getActors().add(Campaign.Actor.builder().id("ally").name("Ally").ownerId("ally-player").build());
        store.saveSheet(campaign.getId(), "bard", bardSheet("Troubadour").toString());
        store.saveSheet(campaign.getId(), "ally", communitySheet("Seaborne").toString());
        ObjectNode action = MAPPER.createObjectNode().put("actorId", "bard").put("key", "feat:Relaxing Song").put("actionId", "use");
        assertEquals(200, featureHttp(cookie(), action).statusCode());
        ObjectNode message = (ObjectNode) dmMessages.stream().filter(m -> m.has("actionBenefits")).findFirst().orElseThrow().deepCopy();
        ObjectNode recovered = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "ally"));
        ((ObjectNode) recovered.path("hp")).put("on", 3);
        recovered.put("featureRevision", recovered.path("featureRevision").asLong() + 1);
        ((ObjectNode) message.path("actionBenefits")).withArray("claimedBy").add("ally-player");
        ObjectNode journal = MAPPER.createObjectNode();
        journal.set("ally", recovered);
        journal.set("$roll", message);
        Path journalFile = temp.resolve("campaigns").resolve(campaign.getId()).resolve("sheet-transfer.json");
        java.nio.file.Files.writeString(journalFile, journal.toString());
        assertEquals(400, featureHttp(allyCookie, benefitRequest("ally", message, "heal")).statusCode());
        assertEquals(3, MAPPER.readTree(store.loadSheet(campaign.getId(), "ally")).path("hp").path("on").asInt());
        assertTrue(java.nio.file.Files.notExists(journalFile));
        assertTrue(store.historyTail(campaign.getId(), Integer.MAX_VALUE).stream().anyMatch(m -> m.equals(message)));
    }

    @Test
    void heartOfAPoetAddsAHostRolledD4ToTheOriginalActionOnce() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("bard").name("Bard").ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), "bard", bardSheet("Wordsmith").toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            ws.sendText("{\"type\":\"roll\",\"kind\":\"duality\",\"expression\":\"2d12\",\"source\":\"action,trait:Presence\",\"actorId\":\"bard\"}", true).join();
            JsonNode original = inbox.next("roll");
            ObjectNode request = MAPPER.createObjectNode().put("actorId", "bard").put("key", "feat:Heart of a Poet").put("actionId", "poet");
            request.putObject("roll").put("id", original.path("id").asLong()).put("ts", original.path("ts").asLong());
            var response = featureHttp(cookie, request);
            assertEquals(200, response.statusCode(), response.body());
            JsonNode update = inbox.next("rollUpdate");
            int extra = update.path("total").asInt() - original.path("total").asInt();
            assertTrue(extra >= 1 && extra <= 4);
            assertEquals("1d4", update.path("breakdown").path(update.path("breakdown").size() - 1).path("expression").asText());
            assertFalse(update.has("outcome"));
            assertEquals(4, MAPPER.readTree(store.loadSheet(campaign.getId(), "bard")).path("hope").path("on").asInt());
            JsonNode saved = store.historyTail(campaign.getId(), Integer.MAX_VALUE).stream()
                    .filter(entry -> entry.path("id").equals(original.path("id"))).findFirst().orElseThrow();
            assertEquals(original.path("rolls").path(0), saved.path("rolls").path(0));
            assertEquals(original.path("rolls").path(1), saved.path("rolls").path(1));
            assertEquals(extra, saved.path("rolls").path(2).asInt());
            assertEquals("2d12+1d4", saved.path("expression").asText());
            assertEquals(update.path("total"), saved.path("total"));
            assertEquals(400, featureHttp(cookie, request).statusCode());
        } finally { ws.abort(); }
    }

    private void diceSeed(long seed) throws Exception {
        var field = OnlineSessionServer.class.getDeclaredField("diceRandom");
        field.setAccessible(true);
        ((java.util.Random) field.get(server)).setSeed(seed);
    }

    private HttpResponse<String> deathMoveHttp(String cookie, String actorId, String move) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/death-move"))
                .header("Cookie", cookie).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.createObjectNode().put("actorId", actorId)
                        .put("move", move).toString())).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void deathMovesAuthenticateValidateRollPersistAndSynchronizeAllOwnerTabs() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("dying").name("Aria").ownerId(playerId()).build());
        campaign.getActors().add(Campaign.Actor.builder().id("foreign").name("Foreign").ownerId("someone").build());
        ObjectNode sheet = communitySheet("Seaborne");
        sheet.put("level", 10);
        ((ObjectNode) sheet.path("hope")).put("total", 6).put("on", 6);
        store.saveSheet(campaign.getId(), "dying", sheet.toString());
        assertEquals(401, deathMoveHttp("", "dying", "avoid").statusCode());
        String cookie = cookie();
        assertEquals(403, deathMoveHttp(cookie, "foreign", "avoid").statusCode());
        assertEquals(400, deathMoveHttp(cookie, "dying", "avoid").statusCode());
        ((ObjectNode) sheet.path("hp")).put("on", 6);
        store.saveSheet(campaign.getId(), "dying", sheet.toString());
        Inbox first = new Inbox();
        Inbox second = new Inbox();
        WebSocket ws = connect(cookie, first);
        WebSocket other = connect(cookie, second);
        try {
            JsonNode hello = first.next("hello");
            assertTrue(hello.path("deathMoves").asBoolean());
            assertEquals(OnlineProtocol.VERSION, hello.path("protocol").asInt());
            diceSeed(0); // First d12 is 1.
            assertEquals(200, deathMoveHttp(cookie, "dying", "avoid").statusCode());
            for (Inbox inbox : List.of(first, second)) {
                JsonNode update = inbox.next("sheetFeature");
                assertEquals(1, update.path("scars").asInt());
                assertEquals(5, update.path("hope").path("slots").asInt());
                assertTrue(update.path("effects").get(0).path("blocksMovement").asBoolean());
                JsonNode roll = inbox.next("roll");
                assertEquals("avoid", roll.path("deathMove").asText());
                assertEquals(1, roll.path("rolls").get(0).asInt());
                assertFalse(roll.has("rewardClaimed"));
                assertTrue(roll.path("featureRoll").path("consequence").asText().contains("permanent scar"));
            }
            assertEquals(400, deathMoveHttp(cookie, "dying", "avoid").statusCode());
            JsonNode saved = MAPPER.readTree(store.loadSheet(campaign.getId(), "dying"));
            assertEquals(1, saved.path("scars").asInt());
            assertEquals(1, store.historyTail(campaign.getId(), 20).size());
            var stale = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/sheet/dying"))
                    .header("Cookie", cookie).PUT(HttpRequest.BodyPublishers.ofString(sheet.toString())).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(409, stale.statusCode());
            campaign.getActors().add(Campaign.Actor.builder().id("healer").name("Healer").ownerId(playerId()).build());
            store.saveSheet(campaign.getId(), "healer", """
                    {"usage":{},"features":[{"name":"Healing","automation":{"actions":[
                    {"id":"heal","label":"Heal","target":"actor","steps":[{"op":"clear","resource":"hp","amount":1}]}]}}]}
                    """);
            var healed = featureHttp(cookie, MAPPER.createObjectNode().put("actorId", "healer").put("key", "feat:Healing")
                    .put("actionId", "heal").put("targetActorId", "dying"));
            assertEquals(200, healed.statusCode(), healed.body());
            JsonNode awake = MAPPER.readTree(store.loadSheet(campaign.getId(), "dying"));
            assertEquals(5, awake.path("hp").path("on").asInt());
            assertTrue(awake.path("effects").isEmpty());
            assertEquals(1, awake.path("scars").asInt());
            assertEquals(5, awake.path("hope").path("slots").asInt());
        } finally { ws.abort(); other.abort(); }
    }

    @Test
    void blazeAndRiskPublishPublicReportsWithoutOrdinaryRollRewardsOrRecovery() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("dying").name("Aria").ownerId(playerId()).build());
        ObjectNode sheet = communitySheet("Seaborne");
        ((ObjectNode) sheet.path("hp")).put("on", 6);
        store.saveSheet(campaign.getId(), "dying", sheet.toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            assertEquals(200, deathMoveHttp(cookie, "dying", "blaze").statusCode());
            JsonNode chat = inbox.next("chat");
            assertTrue(chat.path("text").asText().startsWith("ACTION USED\nDeath move - Blaze of Glory\nAria"));
            assertTrue(chat.path("text").asText().contains("critically succeeds"));
            assertFalse(chat.has("toId"));
            assertEquals(200, deathMoveHttp(cookie, "dying", "risk").statusCode());
            JsonNode roll = inbox.next("roll");
            assertEquals("risk", roll.path("deathMove").asText());
            assertEquals(2, roll.path("rolls").size());
            assertEquals("Hope die", roll.path("breakdown").get(0).path("label").asText());
            assertEquals("Fear die", roll.path("breakdown").get(1).path("label").asText());
            assertFalse(roll.has("rewardClaimed"));
            assertFalse(roll.has("toId"));
            assertEquals(400, rewardHttp(cookie, "dying", roll).statusCode());
            JsonNode saved = MAPPER.readTree(store.loadSheet(campaign.getId(), "dying"));
            assertEquals(6, saved.path("hp").path("on").asInt());
            assertEquals(sheet.path("stress"), saved.path("stress"));
            assertEquals(2, store.historyTail(campaign.getId(), 20).size());
        } finally { ws.abort(); }
    }

    private ObjectNode transformationSheet(String name) throws Exception {
        ObjectNode sheet = communitySheet("Seaborne");
        JsonNode entry = java.util.stream.StreamSupport.stream(
                MAPPER.readTree(compendiumDir.resolve("transformations.json").toFile()).path("entries").spliterator(), false)
                .filter(item -> name.equals(item.path("name").asText())).findFirst().orElseThrow();
        sheet.put("transformation", name);
        sheet.set("heritage", entry.path("features").deepCopy());
        for (JsonNode feature : sheet.path("heritage")) ((ObjectNode) feature).put("source", "Transformation - " + name);
        sheet.putArray("effects");
        return sheet;
    }

    private JsonNode deathEntry(String file, String name) throws Exception {
        for (JsonNode entry : MAPPER.readTree(compendiumDir.resolve(file).toFile()).path("entries"))
            if (name.equals(entry.path("name").asText())) return entry.deepCopy();
        throw new AssertionError("Missing catalog entry " + name);
    }

    private HttpResponse<String> deathRequest(String cookie, String endpoint, ObjectNode body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + endpoint))
                .header("Cookie", cookie).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> saveDeathSheet(String cookie, String actor, ObjectNode sheet) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/sheet/" + actor))
                .header("Cookie", cookie).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(sheet.toString())).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void deathExtensionsResolveCatalogBonusesAndClaimRecoveryOnceAcrossOwnerTabs() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("dying").name("Aria").ownerId(playerId()).build());
        ObjectNode sheet = communitySheet("Seaborne");
        ((ObjectNode) sheet.path("hp")).put("on", 6);
        ((ObjectNode) sheet.path("hope")).put("total", 6);
        sheet.putArray("effects");
        ObjectNode relic = (ObjectNode) deathEntry("items.json", "Reliquary of the Sightless Saint");
        relic.put("qty", 2);
        relic.withObject("/automation").withObject("/deathMove").put("bonus", 99);
        sheet.putArray("inventory").add(relic);
        sheet.set("activeArmor", deathEntry("armor.json", "Hallowed Heroplate"));
        store.saveSheet(campaign.getId(), "dying", sheet.toString());
        String cookie = cookie();
        var options = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/death-move/dying"))
                .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, options.statusCode(), options.body());
        assertEquals(2, MAPPER.readTree(options.body()).path("bonuses").size());
        Inbox first = new Inbox(), second = new Inbox();
        WebSocket ws = connect(cookie, first), other = connect(cookie, second);
        try {
            assertTrue(first.next("hello").path("deathMoveOptions").asBoolean());
            diceSeed(0);
            var response = deathRequest(cookie, "/api/death-move", MAPPER.createObjectNode().put("actorId", "dying")
                    .put("move", "risk").put("blessedHope", 3));
            assertEquals(200, response.statusCode(), response.body());
            JsonNode roll = first.next("roll");
            assertEquals(1, roll.path("deathResult").path("hope").asInt());
            assertEquals(5, roll.path("deathResult").path("fear").asInt());
            assertEquals(5, roll.path("deathResult").path("adjustedHope").asInt());
            assertEquals("hope", roll.path("deathResult").path("outcome").asText());
            assertEquals(4, roll.path("breakdown").size());
            ObjectNode claim = MAPPER.createObjectNode().put("actorId", "dying").put("hp", 3).put("stress", 2);
            claim.set("roll", MAPPER.createObjectNode().setAll(java.util.Map.of("id", roll.path("id"), "ts", roll.path("ts"))));
            var recovered = deathRequest(cookie, "/api/death-recovery", claim);
            assertEquals(200, recovered.statusCode(), recovered.body());
            for (Inbox inbox : List.of(first, second)) {
                JsonNode update = inbox.next("rollUpdate");
                assertTrue(update.path("deathResult").path("claimed").asBoolean());
                assertTrue(update.path("featureRoll").path("consequence").asText().contains("cleared 3 HP and 2 Stress"));
            }
            assertEquals(400, deathRequest(cookie, "/api/death-recovery", claim).statusCode());
            JsonNode saved = MAPPER.readTree(store.loadSheet(campaign.getId(), "dying"));
            assertEquals(3, saved.path("hp").path("on").asInt());
            assertEquals(1, saved.path("stress").path("on").asInt());
            assertEquals(2, saved.path("hope").path("on").asInt());
            assertEquals(1, saved.path("usage").path("armor:Hallowed Heroplate").path("used").asInt());
            assertEquals(1, store.historyTail(campaign.getId(), 10).size());
            assertFalse(saved.has("pendingDeathRoll"));
            assertEquals(409, saveDeathSheet(cookie, "dying", sheet).statusCode());
        } finally { ws.abort(); other.abort(); }
    }

    @Test
    void deathExtensionsConvertFailedRiskAndProtectPermanentHpAgainstOrdinarySaves() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("dying").name("Aria").ownerId(playerId()).build());
        ObjectNode sheet = transformationSheet("Reanimated");
        ((ObjectNode) sheet.path("hp")).put("on", 6);
        store.saveSheet(campaign.getId(), "dying", sheet.toString());
        String cookie = cookie();
        diceSeed(0);
        assertEquals(200, deathMoveHttp(cookie, "dying", "risk").statusCode());
        JsonNode roll = store.historyTail(campaign.getId(), 10).getFirst();
        ObjectNode convert = MAPPER.createObjectNode().put("actorId", "dying").put("undying", true);
        convert.set("roll", MAPPER.createObjectNode().setAll(java.util.Map.of("id", roll.path("id"), "ts", roll.path("ts"))));
        var converted = deathRequest(cookie, "/api/death-recovery", convert);
        assertEquals(200, converted.statusCode(), converted.body());
        assertEquals(400, deathRequest(cookie, "/api/death-recovery", convert).statusCode());
        JsonNode convertedRoll = store.historyTail(campaign.getId(), 10).getFirst();
        assertEquals(roll.path("rolls"), convertedRoll.path("rolls"));
        assertEquals("hope", convertedRoll.path("deathResult").path("outcome").asText());
        ObjectNode claim = convert.deepCopy();
        claim.remove("undying");
        claim.put("hp", 1);
        assertEquals(200, deathRequest(cookie, "/api/death-recovery", claim).statusCode());
        ObjectNode saved = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "dying"));
        assertEquals(1, saved.path("permanentHp").asInt());
        ((ObjectNode) saved.path("hp")).put("on", 0);
        var healed = saveDeathSheet(cookie, "dying", saved);
        assertEquals(200, healed.statusCode(), healed.body());
        saved = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "dying"));
        assertEquals(1, saved.path("hp").path("on").asInt());
        saved.put("permanentHp", 0);
        assertEquals(400, saveDeathSheet(cookie, "dying", saved).statusCode());
    }

    @Test
    void deathExtensionsLifeWardSupportsOfflineAlliesRecastLongRestAndLastHpSaves() throws Exception {
        campaign.getPlayers().add(Campaign.Player.builder().id("offline").name("Offline player").build());
        campaign.getActors().add(Campaign.Actor.builder().id("caster").name("Caster").ownerId(playerId()).build());
        campaign.getActors().add(Campaign.Actor.builder().id("ally").name("Ally").ownerId("offline").build());
        campaign.getActors().add(Campaign.Actor.builder().id("other").name("Other").ownerId("offline").build());
        ObjectNode caster = communitySheet("Seaborne");
        ((ObjectNode) caster.path("hope")).put("on", 6).put("total", 6);
        caster.putArray("loadout").add(deathEntry("domain-cards.json", "Life Ward"));
        ObjectNode ally = communitySheet("Seaborne");
        ally.putArray("effects");
        store.saveSheet(campaign.getId(), "caster", caster.toString());
        store.saveSheet(campaign.getId(), "ally", ally.toString());
        store.saveSheet(campaign.getId(), "other", ally.toString());
        String cookie = cookie();
        ObjectNode ward = MAPPER.createObjectNode().put("actorId", "caster").put("key", "card:Life Ward")
                .put("actionId", "use").put("targetActorId", "ally");
        assertEquals(400, featureHttp(cookie, ward).statusCode());
        ward.put("confirmRange", true);
        var cast = featureHttp(cookie, ward);
        assertEquals(200, cast.statusCode(), cast.body());
        JsonNode protectedAlly = MAPPER.readTree(store.loadSheet(campaign.getId(), "ally"));
        assertTrue(protectedAlly.path("effects").get(0).path("lifeWard").asBoolean());
        assertEquals("caster", protectedAlly.path("effects").get(0).path("sourceActorId").asText());
        ward.put("targetActorId", "other");
        assertEquals(200, featureHttp(cookie, ward).statusCode());
        assertTrue(MAPPER.readTree(store.loadSheet(campaign.getId(), "ally")).path("effects").isEmpty());
        assertFalse(MAPPER.readTree(store.loadSheet(campaign.getId(), "other")).path("effects").isEmpty());
        caster = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "caster"));
        caster.put("longRestRevision", 1);
        ((ObjectNode) caster.path("hope")).put("on", 6);
        var rest = saveDeathSheet(cookie, "caster", caster);
        assertEquals(200, rest.statusCode(), rest.body());
        assertTrue(MAPPER.readTree(store.loadSheet(campaign.getId(), "other")).path("effects").isEmpty());
        ward.put("targetActorId", "ally");
        assertEquals(200, featureHttp(cookie, ward).statusCode());
        caster = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "caster"));
        caster.putArray("features").addObject().put("name", "Mark last HP")
                .putObject("automation").putArray("actions").addObject().put("id", "use").put("target", "actor")
                .putArray("steps").addObject().put("op", "mark").put("resource", "hp").put("amount", 2);
        compendiumDir = null;
        store.saveSheet(campaign.getId(), "caster", caster.toString());
        campaign.actor("ally").setOwnerId(playerId());
        ObjectNode dying = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "ally"));
        ((ObjectNode) dying.path("hp")).put("on", 6);
        var save = saveDeathSheet(cookie, "ally", dying);
        assertEquals(200, save.statusCode(), save.body());
        JsonNode awake = MAPPER.readTree(store.loadSheet(campaign.getId(), "ally"));
        assertEquals(5, awake.path("hp").path("on").asInt());
        assertTrue(awake.path("effects").isEmpty());
        assertEquals(400, deathMoveHttp(cookie, "ally", "avoid").statusCode());
        assertTrue(store.historyTail(campaign.getId(), 10).stream().anyMatch(m -> m.path("text").asText().contains("Life Ward from Caster consumed")));
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            assertEquals(200, featureHttp(cookie, ward).statusCode());
            var marked = featureHttp(cookie, MAPPER.createObjectNode().put("actorId", "caster").put("key", "feat:Mark last HP")
                    .put("actionId", "use").put("targetActorId", "ally"));
            assertEquals(200, marked.statusCode(), marked.body());
            JsonNode protectedAgain = MAPPER.readTree(store.loadSheet(campaign.getId(), "ally"));
            assertEquals(5, protectedAgain.path("hp").path("on").asInt());
            assertTrue(protectedAgain.path("effects").isEmpty());
        } finally { ws.abort(); }
    }

    private JsonNode deathFeature(JsonNode node, String name) {
        if (name.equals(node.path("name").asText()) && node.path("automation").path("deathMove").isObject())
            return node.deepCopy();
        for (JsonNode child : node) {
            JsonNode found = deathFeature(child, name);
            if (found != null) return found;
        }
        return null;
    }

    @Test
    void deathExtensionsCatalogAlternativesConsumeEggVaultCardAndExpireSpectralState() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("hero").name("Hero").ownerId(playerId()).build());
        ObjectNode sheet = communitySheet("Seaborne");
        ((ObjectNode) sheet.path("hp")).put("on", 6);
        sheet.putArray("effects");
        ObjectNode egg = (ObjectNode) deathEntry("consumables.json", "Cathartoid Egg");
        egg.put("qty", 1);
        sheet.putArray("inventory").add(egg);
        sheet.putArray("loadout").add(deathEntry("domain-cards.json", "Battle-Hardened"))
                .add(deathEntry("domain-cards.json", "Unbreakable"));
        JsonNode spectral = deathFeature(MAPPER.readTree(compendiumDir.resolve("classes.json").toFile()), "Spectral Form");
        assertNotNull(spectral);
        sheet.putArray("features").add(spectral);
        store.saveSheet(campaign.getId(), "hero", sheet.toString());
        String cookie = cookie();
        var eggResponse = deathMoveHttp(cookie, "hero", "egg");
        assertEquals(200, eggResponse.statusCode(), eggResponse.body());
        ObjectNode saved = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "hero"));
        assertEquals(0, saved.path("inventory").get(0).path("qty").asInt());
        assertEquals(1, saved.path("inventoryRevision").asInt());
        assertEquals(6, saved.path("hp").path("on").asInt());
        assertTrue(saved.path("deathNarrative").asText().contains("avian"));
        assertEquals(400, deathMoveHttp(cookie, "hero", "egg").statusCode());
        assertEquals(200, deathMoveHttp(cookie, "hero", "spectral").statusCode());
        saved = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "hero"));
        assertEquals(5, saved.path("hp").path("on").asInt());
        assertTrue(saved.path("effects").get(0).path("phaseMovement").asBoolean());
        ((ObjectNode) saved.path("hp")).put("on", 6);
        assertEquals(200, saveDeathSheet(cookie, "hero", saved).statusCode());
        saved = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "hero"));
        assertTrue(saved.path("effects").isEmpty());
        assertTrue(saved.path("spectralBlocked").asBoolean());
        assertEquals(400, deathMoveHttp(cookie, "hero", "spectral").statusCode());
        assertEquals(200, deathMoveHttp(cookie, "hero", "battleHardened").statusCode());
        saved = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "hero"));
        assertEquals(5, saved.path("hp").path("on").asInt());
        assertEquals(4, saved.path("hope").path("on").asInt());
        assertEquals(1, saved.path("usage").path("card:Battle-Hardened").path("used").asInt());
        ((ObjectNode) saved.path("hp")).put("on", 6);
        assertEquals(200, saveDeathSheet(cookie, "hero", saved).statusCode());
        assertEquals(400, deathMoveHttp(cookie, "hero", "battleHardened").statusCode());
        diceSeed(0);
        assertEquals(200, deathMoveHttp(cookie, "hero", "unbreakable").statusCode());
        saved = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "hero"));
        assertEquals("Battle-Hardened", saved.path("loadout").get(0).path("name").asText());
        assertEquals("Unbreakable", saved.path("vault").get(0).path("name").asText());
        assertEquals(1, saved.path("compendiumRevision").asInt());
        assertEquals(2, saved.path("inventoryRevision").asInt());
        assertTrue(store.historyTail(campaign.getId(), 20).stream().anyMatch(m -> m.path("text").asText().contains("Spectral Form ended")));
    }

    @Test
    void stealthExpertiseRejectsOwnAndAllyAttacksButAcceptsNonAttackFearActions() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("stealth").name("Stealth").ownerId(playerId()).build());
        campaign.getActors().add(Campaign.Actor.builder().id("ally").name("Ally").ownerId(playerId()).build());
        ObjectNode sheet = communitySheet("Seaborne");
        var loadout = sheet.putArray("loadout");
        for (JsonNode card : MAPPER.readTree(compendiumDir.resolve("domain-cards.json").toFile()).path("entries"))
            if ("Stealth Expertise".equals(card.path("name").asText())) loadout.add(card.deepCopy());
        assertEquals(1, loadout.size());
        store.saveSheet(campaign.getId(), "stealth", sheet.toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            for (String actor : List.of("stealth", "ally")) {
                for (String source : List.of("attack,action,trait:Agility", "action,trait:Agility")) {
                    diceSeed(0);
                    ws.sendText(MAPPER.createObjectNode().put("type", "roll").put("kind", "duality")
                            .put("expression", "2d12").put("source", source).put("actorId", actor).toString(), true).join();
                    JsonNode roll = inbox.next("roll");
                    assertTrue(roll.path("rolls").path(1).asInt() > roll.path("rolls").path(0).asInt());
                    ObjectNode request = MAPPER.createObjectNode().put("actorId", "stealth")
                            .put("key", "card:Stealth Expertise").put("actionId", "hope");
                    request.putObject("roll").put("id", roll.path("id").asLong()).put("ts", roll.path("ts").asLong());
                    JsonNode before = MAPPER.readTree(store.loadSheet(campaign.getId(), "stealth"));
                    var response = featureHttp(cookie, request);
                    if (source.startsWith("attack")) {
                        assertEquals(400, response.statusCode(), response.body());
                        assertEquals(before, MAPPER.readTree(store.loadSheet(campaign.getId(), "stealth")));
                    } else {
                        assertEquals(200, response.statusCode(), response.body());
                        assertEquals(before.path("stress").path("on").asInt() + 1,
                                MAPPER.readTree(store.loadSheet(campaign.getId(), "stealth")).path("stress").path("on").asInt());
                    }
                }
            }
        } finally { ws.abort(); }
    }

    @Test
    void assassinReactionsValidateRollKindsPayCostsAndPersistTheMark() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("assassin").name("Assassin").ownerId(playerId()).build());
        ObjectNode sheet = communitySheet("Seaborne");
        sheet.put("className", "Assassin");
        sheet.put("subclass", "Executioners Guild");
        sheet.putArray("effects");
        var features = sheet.putArray("features");
        for (JsonNode entry : MAPPER.readTree(compendiumDir.resolve("classes.json").toFile()).path("entries")) {
            if (entry.path("id").asText().startsWith("assassin"))
                for (JsonNode feature : entry.path("features")) {
                    ObjectNode learned = feature.deepCopy();
                    learned.put("source", "Assassin".equals(entry.path("name").asText())
                            ? "Class feature - Assassin" : "Subclass " + entry.path("stage").asText() + " - Executioners Guild");
                    features.add(learned);
                }
        }
        sheet.withObject("/resources").put("poison", 2);
        store.saveSheet(campaign.getId(), "assassin", sheet.toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            ObjectNode request = MAPPER.createObjectNode().put("actorId", "assassin").put("key", "feat:Marked For Death").put("actionId", "use");
            assertTrue(inbox.next("hello").path("rollMultipliers").asBoolean());
            ws.sendText("{\"type\":\"roll\",\"kind\":\"duality\",\"expression\":\"2d12\",\"source\":\"attack,action\",\"actorId\":\"assassin\"}", true).join();
            JsonNode unarmed = inbox.next("roll");
            request.set("roll", MAPPER.createObjectNode().put("id", unarmed.path("id").asLong()).put("ts", unarmed.path("ts").asLong()));
            assertEquals(400, featureHttp(cookie, request).statusCode());
            ws.sendText("{\"type\":\"roll\",\"kind\":\"duality\",\"expression\":\"2d12\",\"source\":\"attack,action\",\"weapon\":\"Dagger\",\"actorId\":\"assassin\",\"rollSource\":{\"name\":\"Dagger\",\"text\":\"Weapon description\",\"source\":\"Weapon\"}}", true).join();
            JsonNode attack = inbox.next("roll");
            request.set("roll", MAPPER.createObjectNode().put("id", attack.path("id").asLong()).put("ts", attack.path("ts").asLong()));
            var response = featureHttp(cookie, request);
            assertEquals(200, response.statusCode(), response.body());
            JsonNode saved = MAPPER.readTree(store.loadSheet(campaign.getId(), "assassin"));
            assertEquals(4, saved.path("stress").path("on").asInt());
            assertEquals("Marked for Death", saved.path("effects").path(0).path("name").asText());
            assertEquals("rest", saved.path("effects").path(0).path("until").asText());
            JsonNode markReport = inbox.next("chat");
            JsonNode markedFeature = java.util.stream.StreamSupport.stream(features.spliterator(), false)
                    .filter(feature -> "Marked For Death".equals(feature.path("name").asText())).findFirst().orElseThrow();
            assertEquals(markedFeature.path("name"), markReport.path("rollSource").path("name"));
            assertEquals(markedFeature.path("text"), markReport.path("rollSource").path("text"));
            assertEquals("Class feature - Assassin", markReport.path("rollSource").path("source").asText());
            assertFalse(markReport.has("toId"));
            assertEquals(400, featureHttp(cookie, request).statusCode());

            request.put("key", "feat:Toxic Concoctions").put("actionId", "poison");
            response = featureHttp(cookie, request);
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(1, MAPPER.readTree(store.loadSheet(campaign.getId(), "assassin")).path("resources").path("poison").asInt());
            assertEquals("Toxic Concoctions", inbox.next("chat").path("rollSource").path("name").asText());
            assertEquals(400, featureHttp(cookie, request).statusCode());

            request.put("key", "feat:True Strike").put("actionId", "use");
            response = featureHttp(cookie, request);
            assertEquals(200, response.statusCode(), response.body());
            saved = MAPPER.readTree(store.loadSheet(campaign.getId(), "assassin"));
            assertEquals(4, saved.path("hope").path("on").asInt());
            assertEquals(1, saved.path("usage").path("feat:True Strike").path("used").asInt());
            assertEquals("True Strike", inbox.next("chat").path("rollSource").path("name").asText());

            ws.sendText("{\"type\":\"roll\",\"expression\":\"(1d8+2)*2\",\"source\":\"damage\",\"weapon\":\"Dagger\",\"actorId\":\"assassin\",\"toId\":\"dm\"}", true).join();
            JsonNode damage = inbox.next("roll");
            assertEquals(1, damage.path("rolls").size());
            assertEquals((damage.path("rolls").path(0).asInt() + 2) * 2, damage.path("total").asInt());
            request.set("roll", MAPPER.createObjectNode().put("id", damage.path("id").asLong()).put("ts", damage.path("ts").asLong()));
            assertEquals(400, featureHttp(cookie, request).statusCode());
            request.put("key", "feat:Marked For Death");
            assertEquals(400, featureHttp(cookie, request).statusCode());
            request.put("key", "feat:Death Strike");
            response = featureHttp(cookie, request);
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(5, MAPPER.readTree(store.loadSheet(campaign.getId(), "assassin")).path("stress").path("on").asInt());
            JsonNode deathReport = inbox.next("chat");
            assertEquals("dm", deathReport.path("toId").asText());
            JsonNode deathFeature = java.util.stream.StreamSupport.stream(features.spliterator(), false)
                    .filter(feature -> "Death Strike".equals(feature.path("name").asText())).findFirst().orElseThrow();
            assertEquals(deathFeature.path("name"), deathReport.path("rollSource").path("name"));
            assertEquals(deathFeature.path("text"), deathReport.path("rollSource").path("text"));
            assertEquals("Subclass specialization - Executioners Guild", deathReport.path("rollSource").path("source").asText());
            assertEquals(400, featureHttp(cookie, request).statusCode());
            for (JsonNode feature : features) ((ObjectNode) feature).put("text", "Changed after use");
            store.saveSheet(campaign.getId(), "assassin", sheet.toString());
            List<JsonNode> history = new CampaignStore(store.root()).historyTail(campaign.getId(), 50);
            assertTrue(history.contains(markReport));
            assertTrue(history.contains(deathReport));
        } finally { ws.abort(); }
    }

    @Test
    void feedValidatesFangsAndAmountPaysStressCapsTokensAndRejectsDuplicateClaims() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("vampire").name("Vampire").ownerId(playerId()).build());
        ObjectNode sheet = transformationSheet("Vampire");
        sheet.withObject("/resources").put("feedTokens", 4);
        store.saveSheet(campaign.getId(), "vampire", sheet.toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            ObjectNode request = MAPPER.createObjectNode().put("actorId", "vampire").put("key", "feat:Feed").put("actionId", "feed").put("amount", 3);
            for (String weapon : List.of("Sword", "Fangs")) {
                ws.sendText(MAPPER.createObjectNode().put("type", "roll").put("kind", "duality")
                        .put("expression", "2d12").put("source", "attack,action").put("weapon", weapon).put("actorId", "vampire").toString(), true).join();
                JsonNode roll = inbox.next("roll");
                request.putObject("roll").put("id", roll.path("id").asLong()).put("ts", roll.path("ts").asLong());
                if ("Sword".equals(weapon)) {
                    assertEquals(400, featureHttp(cookie, request).statusCode());
                    continue;
                }
                for (int amount : List.of(0, -1, 7)) assertEquals(400, featureHttp(cookie, request.put("amount", amount)).statusCode());
                assertEquals(sheet, MAPPER.readTree(store.loadSheet(campaign.getId(), "vampire")));
                var response = featureHttp(cookie, request.put("amount", 3));
                assertEquals(200, response.statusCode(), response.body());
                JsonNode saved = MAPPER.readTree(store.loadSheet(campaign.getId(), "vampire"));
                assertEquals(6, saved.path("resources").path("feedTokens").asInt());
                assertEquals(4, saved.path("stress").path("on").asInt());
                assertTrue(inbox.next("chat").path("text").asText().contains("Feed tokens (6/6)"));
                assertEquals(400, featureHttp(cookie, request).statusCode());
                assertEquals(6, MAPPER.readTree(new CampaignStore(store.root()).loadSheet(campaign.getId(), "vampire"))
                        .path("resources").path("feedTokens").asInt());
            }
        } finally { ws.abort(); }
    }

    @Test
    void wolfHopeStressRequiresActiveFormAcceptsCriticalAndRejectsFearOrDuplicate() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("wolf").name("Wolf").ownerId(playerId()).build());
        ObjectNode sheet = transformationSheet("Werewolf");
        store.saveSheet(campaign.getId(), "wolf", sheet.toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            diceSeed(0);
            ws.sendText("{\"type\":\"roll\",\"kind\":\"duality\",\"expression\":\"2d12\",\"source\":\"action\",\"actorId\":\"wolf\"}", true).join();
            JsonNode fear = inbox.next("roll");
            ObjectNode request = MAPPER.createObjectNode().put("actorId", "wolf").put("key", "feat:Wolf Form").put("actionId", "hope-stress");
            request.putObject("roll").put("id", fear.path("id").asLong()).put("ts", fear.path("ts").asLong());
            assertEquals(400, featureHttp(cookie, request).statusCode());
            sheet.withArray("effects").addObject().put("name", "Wolf Form").put("kind", "buff").put("until", "rest");
            store.saveSheet(campaign.getId(), "wolf", sheet.toString());
            assertEquals(400, featureHttp(cookie, request).statusCode());
            long seed = 0;
            while (true) {
                java.util.Random random = new java.util.Random(seed);
                if (random.nextInt(12) == random.nextInt(12)) break;
                seed++;
            }
            diceSeed(seed);
            ws.sendText("{\"type\":\"roll\",\"kind\":\"duality\",\"expression\":\"2d12\",\"source\":\"action\",\"actorId\":\"wolf\"}", true).join();
            JsonNode critical = inbox.next("roll");
            request.putObject("roll").put("id", critical.path("id").asLong()).put("ts", critical.path("ts").asLong());
            var response = featureHttp(cookie, request);
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(4, MAPPER.readTree(store.loadSheet(campaign.getId(), "wolf")).path("stress").path("on").asInt());
            assertEquals(400, featureHttp(cookie, request).statusCode());
        } finally { ws.abort(); }
    }

    @Test
    void fearD20RollsKeepDualityAndRewardClaimsWithEitherHopeDie() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("vampire").name("Vampire").ownerId(playerId()).build());
        ObjectNode sheet = transformationSheet("Vampire");
        store.saveSheet(campaign.getId(), "vampire", sheet.toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            for (int hopeSides : List.of(12, 20)) {
                sheet = transformationSheet("Vampire");
                store.saveSheet(campaign.getId(), "vampire", sheet.toString());
                long seed = 0;
                while (true) {
                    java.util.Random random = new java.util.Random(seed);
                    if (random.nextInt(hopeSides) >= random.nextInt(20)) break;
                    seed++;
                }
                diceSeed(seed);
                String expression = "1d" + hopeSides + "+1d20+2";
                ws.sendText(MAPPER.createObjectNode().put("type", "roll").put("kind", "duality")
                        .put("expression", expression).put("source", "action").put("actorId", "vampire").toString(), true).join();
                JsonNode roll = inbox.next("roll");
                assertEquals("duality", roll.path("kind").asText());
                assertTrue(roll.path("rewardClaimed").isBoolean());
                var response = rewardHttp(cookie, "vampire", roll);
                assertEquals(200, response.statusCode(), response.body());
                assertEquals(400, rewardHttp(cookie, "vampire", roll).statusCode());
            }
        } finally { ws.abort(); }
    }

    @Test
    void closeKnitValidatesAmountAndAllyThenTransfersHopeAndCountsOneUse() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("source").name("Giver").ownerId(playerId()).build());
        campaign.getActors().add(Campaign.Actor.builder().id("target").name("Ally").ownerId(playerId()).build());
        ObjectNode source = communitySheet("Hearthborne");
        ObjectNode target = source.deepCopy();
        ((ObjectNode) target.path("hope")).put("on", 1);
        store.saveSheet(campaign.getId(), "source", source.toString());
        store.saveSheet(campaign.getId(), "target", target.toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            ObjectNode request = MAPPER.createObjectNode().put("actorId", "source").put("key", "feat:Close-Knit")
                    .put("actionId", "use").put("targetActorId", "target").put("amount", 0);
            assertEquals(400, featureHttp(cookie, request).statusCode());
            assertEquals(400, featureHttp(cookie, request.put("amount", 6)).statusCode());
            assertEquals(source, MAPPER.readTree(store.loadSheet(campaign.getId(), "source")));
            assertEquals(400, featureHttp(cookie, request.put("amount", 1).put("targetActorId", "source")).statusCode());
            var result = featureHttp(cookie, request.put("amount", 3).put("targetActorId", "target"));
            assertEquals(200, result.statusCode(), result.body());
            assertTrue(inbox.next("chat").path("text").asText().startsWith("ACTION USED\nHearthborne - Close-Knit\n"));
            assertEquals(2, MAPPER.readTree(store.loadSheet(campaign.getId(), "source")).path("hope").path("on").asInt());
            assertEquals(4, MAPPER.readTree(store.loadSheet(campaign.getId(), "target")).path("hope").path("on").asInt());
            assertEquals(1, MAPPER.readTree(store.loadSheet(campaign.getId(), "source")).path("usage").path("feat:Close-Knit").path("used").asInt());
            assertEquals(400, featureHttp(cookie, request).statusCode());
        } finally { ws.abort(); }
    }

    @Test
    void oasisUpdatesAnAlliesRestAndRecoveryOnlyByTheImprovementAndRejectsDuplicateAndHistoricalUses() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("source").name("Dune").ownerId(playerId()).build());
        Campaign.Player bob = Campaign.Player.builder().name("Bob").passwordHash(PasswordHasher.hash("secret", 64, 1, 1)).build();
        campaign.getPlayers().add(bob);
        campaign.getActors().add(Campaign.Actor.builder().id("target").name("Ally").ownerId(bob.getId()).build());
        store.saveSheet(campaign.getId(), "source", communitySheet("Duneborne").toString());
        store.saveSheet(campaign.getId(), "target", communitySheet("Freeborne").toString());
        String cookie = cookie();
        Inbox sourceInbox = new Inbox();
        Inbox targetInbox = new Inbox();
        WebSocket ws = connect(cookie, sourceInbox);
        WebSocket targetWs = connect(playerCookie(bob), targetInbox);
        try {
            ObjectNode chat = MAPPER.createObjectNode().put("type", "chat").put("actorId", "target")
                    .put("text", "SHORT REST\nAlly\n\u2022 Tend to Wounds on self: clears 2 HP (1d4+1 = 2)");
            chat.putArray("restDice").addObject().put("line", 0).put("expression", "1d4+1").put("value", 2)
                    .put("resource", "hp").put("recovery", 4).put("cleared", 2);
            targetWs.sendText(chat.toString(), true).join();
            JsonNode report = sourceInbox.next("chat");
            ObjectNode request = MAPPER.createObjectNode().put("actorId", "source").put("key", "feat:Oasis")
                    .put("actionId", "reroll").put("restDie", 0);
            request.putObject("roll").put("id", report.path("id").asLong()).put("ts", report.path("ts").asLong());
            diceSeed(0);
            var result = featureHttp(cookie, request);
            assertEquals(200, result.statusCode(), result.body());
            assertEquals(1, sourceInbox.next("sheetFeature").path("usage").path("feat:Oasis").path("used").asInt());
            JsonNode updated = sourceInbox.next("chatUpdate").path("message");
            assertTrue(updated.path("text").asText().contains("2/4, kept 4"));
            assertTrue(updated.path("text").asText().contains("clears 4 HP"));
            assertEquals(2, targetInbox.next("sheetFeature").path("hp").path("on").asInt());
            assertEquals(2, MAPPER.readTree(store.loadSheet(campaign.getId(), "target")).path("hp").path("on").asInt());
            JsonNode action = sourceInbox.next("chat");
            assertTrue(action.path("text").asText().startsWith("ACTION USED\nDuneborne - Oasis - Reroll downtime die\nDune\n"));
            assertTrue(action.path("text").asText().contains("Ally's downtime die"));
            assertTrue(action.path("text").asText().contains("kept 4"));
            assertTrue(action.path("text").asText().contains("cleared 2 additional HP"));
            assertEquals(400, featureHttp(cookie, request).statusCode());
            assertTrue(new CampaignStore(store.root()).historyTail(campaign.getId(), 2).contains(updated));
            for (int previous : List.of(2, 5)) {
                chat.put("text", "SHORT REST\nAlly\n\u2022 Tend to Wounds on self: clears 2 HP (1d4+1 = " + previous + ")");
                ObjectNode die = (ObjectNode) chat.path("restDice").get(0);
                die.put("value", previous).put("recovery", 0);
                targetWs.sendText(chat.toString(), true).join();
                JsonNode cappedReport = sourceInbox.next("chat");
                request.putObject("roll").put("id", cappedReport.path("id").asLong()).put("ts", cappedReport.path("ts").asLong());
                assertEquals(400, featureHttp(cookie, request).statusCode(), "Oasis is shared across all allies' rest reports");
                ObjectNode refreshed = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "source"));
                ((ObjectNode) refreshed.path("usage")).remove("feat:Oasis");
                store.saveSheet(campaign.getId(), "source", refreshed.toString());
                diceSeed(0);
                assertEquals(200, featureHttp(cookie, request).statusCode());
                assertEquals(1, sourceInbox.next("sheetFeature").path("usage").path("feat:Oasis").path("used").asInt());
                JsonNode capped = sourceInbox.next("chatUpdate").path("message");
                assertTrue(capped.path("text").asText().contains("clears 2 HP"));
                assertTrue(capped.path("text").asText().contains("kept " + Math.max(previous, 4)));
                assertEquals(2, MAPPER.readTree(store.loadSheet(campaign.getId(), "target")).path("hp").path("on").asInt());
                assertTrue(sourceInbox.next("chat").path("text").asText().contains("No additional HP cleared."));
            }
            ObjectNode restedSource = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "source"));
            ((ObjectNode) restedSource.path("usage")).remove("feat:Oasis");
            store.saveSheet(campaign.getId(), "source", restedSource.toString());
            chat.put("actorId", "source").put("text", "SHORT REST\nDune\n\u2022 Clear Stress: clears 2 Stress (1d4+1 = 2)");
            ((ObjectNode) chat.path("restDice").get(0)).put("resource", "stress").put("value", 2).put("recovery", 4);
            ws.sendText(chat.toString(), true).join();
            JsonNode ownReport = sourceInbox.next("chat");
            request.putObject("roll").put("id", ownReport.path("id").asLong()).put("ts", ownReport.path("ts").asLong());
            diceSeed(0);
            assertEquals(200, featureHttp(cookie, request).statusCode());
            JsonNode ownState = sourceInbox.next("sheetFeature");
            assertEquals(1, ownState.path("usage").path("feat:Oasis").path("used").asInt());
            assertEquals(restedSource.path("stress").path("on").asInt() - 2, ownState.path("stress").path("on").asInt());
            assertEquals(restedSource.path("featureRevision").asInt() + 1, ownState.path("featureRevision").asInt());
            sourceInbox.next("chatUpdate");
            assertTrue(sourceInbox.next("chat").path("text").asText().contains("cleared 2 additional Stress."));
            assertEquals(400, featureHttp(cookie, request).statusCode());
            server.stop();
            server.start();
            assertEquals(400, featureHttp(cookie(), request).statusCode());
        } finally { ws.abort(); targetWs.abort(); }
    }

    @Test
    void tideFearReactionIsCappedIdempotentAndSessionOnlyAndDedicatedD20KeepsDualityAndRewards() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("source").name("Sailor").ownerId(playerId()).build());
        ObjectNode sheet = communitySheet("Seaborne");
        ((ObjectNode) sheet.path("resources")).put("tide", 2);
        store.saveSheet(campaign.getId(), "source", sheet.toString());
        server.stop();
        server.start();
        server.publish(state("m1", 0), "m1/l1");
        assertEquals(0, MAPPER.readTree(store.loadSheet(campaign.getId(), "source")).path("resources").path("tide").asInt());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            ObjectNode request = MAPPER.createObjectNode().put("actorId", "source").put("key", "feat:Know the Tide").put("actionId", "gain");
            for (int i = 0; i < 4; i++) {
                diceSeed(0);
                ws.sendText("{\"type\":\"roll\",\"kind\":\"duality\",\"expression\":\"2d12\",\"actorId\":\"source\",\"source\":\"action\"}", true).join();
                JsonNode roll = inbox.next("roll");
                assertTrue(roll.path("rolls").path(1).asInt() > roll.path("rolls").path(0).asInt());
                request.putObject("roll").put("id", roll.path("id").asLong()).put("ts", roll.path("ts").asLong());
                var result = featureHttp(cookie, request);
                assertEquals(200, result.statusCode(), result.body());
                assertEquals(Math.min(3, i + 1), inbox.next("sheetFeature").path("resources").path("tide").asInt());
                assertEquals(400, featureHttp(cookie, request).statusCode());
            }
            assertFalse(MAPPER.readTree(new CampaignStore(store.root()).loadSheet(campaign.getId(), "source")).path("resources").has("tide"));
            long hopeSeed = 0;
            while (new java.util.Random(hopeSeed).nextInt(20) < 12) hopeSeed++;
            diceSeed(hopeSeed);
            ws.sendText("{\"type\":\"roll\",\"kind\":\"duality\",\"expression\":\"1d20+1d12+2\",\"actorId\":\"source\",\"source\":\"action\"}", true).join();
            JsonNode dedicated = inbox.next("roll");
            assertEquals("duality", dedicated.path("kind").asText());
            assertTrue(dedicated.path("rewardClaimed").isBoolean());
            assertEquals("1d20+1d12+2", dedicated.path("expression").asText());
            assertTrue(dedicated.path("rolls").path(0).asInt() > 12);
            var reward = rewardHttp(cookie, "source", dedicated);
            assertEquals(200, reward.statusCode(), reward.body());
            assertEquals(6, inbox.next("sheetFeature").path("hope").path("on").asInt());
        } finally { ws.abort(); }
        server.stop();
        server.start();
        assertEquals(0, MAPPER.readTree(store.loadSheet(campaign.getId(), "source")).path("resources").path("tide").asInt());
    }

    private HttpResponse<String> dmCompendiumHttp(String action, JsonNode body, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/dm/compendium/" + action))
                .header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String dmCompendiumData() throws Exception {
        compendiumDir = temp.resolve("dm-compendium");
        java.nio.file.Files.createDirectories(compendiumDir);
        java.nio.file.Files.writeString(compendiumDir.resolve("items.json"), """
                {"category":"items","label":"Items","entries":[{"id":"torch","name":"Host torch","text":"Light.","automation":{"uses":{"max":1,"per":"session"}}}]}
                """);
        java.nio.file.Files.writeString(compendiumDir.resolve("domain-cards.json"), """
                {"category":"domain-cards","label":"Domain cards","entries":[{"id":"spell","name":"Host spell","domain":"Grace","level":1,"cardType":"Spell","text":"Spell.","recall":2}]}
                """);
        campaign.getActors().add(Campaign.Actor.builder().id("target").name("Hero").ownerId(playerId()).build());
        campaign.getActors().add(Campaign.Actor.builder().id("unowned").name("NPC").build());
        store.saveSheet(campaign.getId(), "target", """
                {"className":"Bard","inventory":[],"carriedArmor":[],"carriedWeapons":[],"loadout":[],"vault":[],
                "traits":[{"name":"Presence","spellcast":true}],"notes":"Keep me"}
                """);
        return URI.create(server.dmCompendiumUrl()).getFragment();
    }

    @Test
    void dmCompendiumRejectsMissingForgedAndPlayerCredentialsAndListsOfflineActors() throws Exception {
        String token = dmCompendiumData();
        assertEquals(403, dmCompendiumHttp("actors", null, null).statusCode());
        assertEquals(403, dmCompendiumHttp("actors", null, "wrong").statusCode());
        var playerRequest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/dm/compendium/actors"))
                .header("Cookie", cookie()).header("X-Forwarded-For", "127.0.0.1").build();
        assertEquals(403, http.send(playerRequest, HttpResponse.BodyHandlers.ofString()).statusCode());
        JsonNode actors = MAPPER.readTree(dmCompendiumHttp("actors", null, token).body());
        assertEquals(1, actors.size());
        assertEquals("target", actors.get(0).path("id").asText());
        assertEquals("Hero (Alice)", actors.get(0).path("name").asText());
        var index = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/compendium"))
                .header("Authorization", "Bearer " + token).build();
        assertEquals(200, http.send(index, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertFalse(http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/session")).build(),
                HttpResponse.BodyHandlers.ofString()).body().contains(token));
    }

    @Test
    void dmCompendiumAddsOfflinePersistsCapsAndRejectsBadTargetsAndStaleSaves() throws Exception {
        String token = dmCompendiumData();
        ObjectNode request = MAPPER.createObjectNode().put("category", "items").put("entryId", "torch").put("actorId", "target");
        request.putObject("entry").put("name", "Forged");
        ObjectNode previous = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "target"));
        assertEquals(200, dmCompendiumHttp("add", request, token).statusCode());
        JsonNode saved = MAPPER.readTree(new CampaignStore(store.root()).loadSheet(campaign.getId(), "target"));
        assertEquals("Host torch", saved.path("inventory").get(0).path("name").asText());
        assertTrue(saved.path("inventory").get(0).has("automation"));
        assertEquals("Keep me", saved.path("notes").asText());
        assertEquals(1, saved.path("inventoryRevision").asInt());
        var staleSave = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/sheet/target"))
                .header("Cookie", cookie()).PUT(HttpRequest.BodyPublishers.ofString(previous.toString())).build();
        assertEquals(409, http.send(staleSave, HttpResponse.BodyHandlers.ofString()).statusCode());
        ObjectNode capped = (ObjectNode) saved;
        ((ObjectNode) capped.path("inventory").get(0)).put("qty", 99);
        store.saveSheet(campaign.getId(), "target", capped.toString());
        assertEquals(400, dmCompendiumHttp("add", request, token).statusCode());
        assertEquals(capped.toString(), store.loadSheet(campaign.getId(), "target"));
        assertEquals(400, dmCompendiumHttp("add", request.put("actorId", "unowned"), token).statusCode());
        assertEquals(400, dmCompendiumHttp("add", request.put("actorId", "missing"), token).statusCode());
        assertEquals(400, dmCompendiumHttp("add", request.put("actorId", "target").put("category", "../items"), token).statusCode());
        assertEquals(400, dmCompendiumHttp("add", request.put("category", "items").put("entryId", "missing"), token).statusCode());
    }

    @Test
    void dmCompendiumLinksPublicChatAndUpdatesLiveDomainCardsWithoutGrantingTransfers() throws Exception {
        String token = dmCompendiumData();
        server.publish(state("m1", 0), "m1/l1");
        Inbox inbox = new Inbox();
        String cookie = cookie();
        WebSocket ws = connect(cookie, inbox);
        try {
            inbox.next("snapshot");
            ObjectNode request = MAPPER.createObjectNode().put("category", "domain-cards").put("entryId", "spell").put("actorId", "target");
            assertEquals(200, dmCompendiumHttp("add", request, token).statusCode());
            JsonNode update = inbox.next("sheetInventory").path("inventory");
            assertEquals(1, update.path("compendiumRevision").asInt());
            assertEquals("Host spell", update.path("vault").get(0).path("name").asText());
            assertEquals("Presence", update.path("vault").get(0).path("spellcast").asText());
            ObjectNode oldClient = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "target"));
            oldClient.remove("compendiumRevision");
            var stale = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/sheet/target"))
                    .header("Cookie", cookie).PUT(HttpRequest.BodyPublishers.ofString(oldClient.toString())).build();
            assertEquals(409, http.send(stale, HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(200, dmCompendiumHttp("link", request, token).statusCode());
            JsonNode chat = inbox.next("chat");
            assertEquals("dm", chat.path("fromId").asText());
            assertEquals("Compendium - Domain cards", chat.path("sharedCard").path("actorName").asText());
            assertFalse(chat.path("sharedCard").has("offerQuantity"));
            assertEquals(0, chat.path("sharedCard").path("available").asInt());
            assertEquals(chat, dmMessages.getLast());
            assertTrue(new CampaignStore(store.root()).historyTail(campaign.getId(), 20).contains(chat));
        } finally { ws.abort(); }
    }

    @Test
    void allyFearReactionUpdatesTheOriginalRollAndBothSheetsWithoutASecondRoll() throws Exception {
        Campaign.Player bob = Campaign.Player.builder().name("Bob")
                .passwordHash(PasswordHasher.hash("secret", 64, 1, 1)).build();
        campaign.getPlayers().add(bob);
        campaign.getActors().add(Campaign.Actor.builder().id("caster").name("Alice").ownerId(playerId()).build());
        campaign.getActors().add(Campaign.Actor.builder().id("roller").name("Bob").ownerId(bob.getId()).build());
        String sourceJson = """
                {"featureRevision":0,"usage":{},"hope":{"on":0,"slots":5},"features":[{"name":"Hallowed Aura",
                "automation":{"uses":{"max":1,"per":"longRest"},"rollTriggers":[{"id":"hope","label":"Hallowed Aura",
                "on":"action","when":"fear","outcome":"hope","audience":"ally"}]}}]}
                """;
        String rollerJson = """
                {"featureRevision":0,"usage":{},"hope":{"on":0,"slots":5},"stress":{"on":0,"slots":6},
                "hp":{"on":0,"slots":6},"effects":[]}
                """;
        store.saveSheet(campaign.getId(), "caster", sourceJson);
        store.saveSheet(campaign.getId(), "roller", rollerJson);
        ObjectNode stale = (ObjectNode) MAPPER.readTree(sourceJson);
        String aliceCookie = cookie();
        Inbox inbox = new Inbox();
        Inbox bobInbox = new Inbox();
        WebSocket ws = connect(aliceCookie, inbox);
        String bobCookie = playerCookie(bob);
        WebSocket bobWs = connect(bobCookie, bobInbox);
        try {
            JsonNode fearRoll = null;
            for (int attempt = 0; attempt < 20; attempt++) {
                bobWs.sendText("""
                        {"type":"roll","kind":"duality","expression":"2d12","source":"action",
                        "actorId":"roller","label":"Test action"}
                        """, true).join();
                JsonNode message = inbox.next("roll");
                if (message.path("rolls").get(1).asInt() > message.path("rolls").get(0).asInt()) {
                    fearRoll = message;
                    break;
                }
            }
            assertNotNull(fearRoll, "Expected to roll Fear within a reasonable number of attempts");
            JsonNode rolledFear = java.util.Objects.requireNonNull(fearRoll);
            ObjectNode reaction = MAPPER.createObjectNode().put("actorId", "caster")
                    .put("key", "feat:Hallowed Aura").put("actionId", "hope");
            reaction.putObject("roll").put("id", rolledFear.path("id").asLong()).put("ts", rolledFear.path("ts").asLong());
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/feature"))
                    .header("Cookie", aliceCookie).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(reaction.toString())).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            JsonNode update = inbox.next("rollUpdate");
            assertEquals("hope", bobInbox.next("rollUpdate").path("outcome").asText());
            assertEquals(rolledFear.path("id").asLong(), update.path("id").asLong());
            assertEquals("hope", update.path("outcome").asText());
            JsonNode persistedRoll = store.historyTail(campaign.getId(), 50).stream()
                    .filter(entry -> entry.path("id").asLong() == rolledFear.path("id").asLong()
                            && entry.path("ts").asLong() == rolledFear.path("ts").asLong()).findFirst().orElseThrow();
            assertEquals(rolledFear.path("rolls"), persistedRoll.path("rolls"));
            assertEquals("hope", persistedRoll.path("outcome").asText());
            assertEquals(0, MAPPER.readTree(store.loadSheet(campaign.getId(), "roller")).path("hope").path("on").asInt());
            assertEquals(400, rewardHttp(aliceCookie, "caster", persistedRoll).statusCode());
            assertEquals(200, rewardHttp(bobCookie, "roller", persistedRoll).statusCode());
            assertEquals(1, bobInbox.next("sheetFeature").path("hope").path("on").asInt());
            assertTrue(bobInbox.next("rollUpdate").path("rewardClaimed").asBoolean());
            assertEquals(400, rewardHttp(bobCookie, "roller", persistedRoll).statusCode());
            assertEquals(1, MAPPER.readTree(store.loadSheet(campaign.getId(), "caster")).path("usage")
                    .path("feat:Hallowed Aura").path("used").asInt());
            assertEquals(1, store.historyTail(campaign.getId(), 50).stream()
                    .filter(entry -> "chat".equals(entry.path("type").asText())
                            && entry.path("text").asText().contains("ACTION USED")).count());
            var staleSave = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/sheet/caster"))
                    .header("Cookie", aliceCookie).PUT(HttpRequest.BodyPublishers.ofString(stale.toString())).build();
            assertEquals(409, http.send(staleSave, HttpResponse.BodyHandlers.ofString()).statusCode());
        } finally {
            ws.abort();
            bobWs.abort();
        }
    }

    private HttpResponse<String> rewardHttp(String cookie, String actorId, JsonNode roll) throws Exception {
        ObjectNode body = MAPPER.createObjectNode().put("actorId", actorId);
        body.putObject("roll").put("id", roll.path("id").asLong()).put("ts", roll.path("ts").asLong());
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/roll-reward"))
                .header("Cookie", cookie).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
    }

    private ObjectNode rewardRoll(long id, int hope, int fear) throws Exception {
        ObjectNode roll = MAPPER.createObjectNode().put("type", "roll").put("id", id).put("ts", 1234)
                .put("fromId", playerId()).put("actorId", "reward-actor").put("kind", "duality")
                .put("rewardClaimed", false);
        roll.putArray("rolls").add(hope).add(fear);
        store.appendHistoryChecked(campaign.getId(), roll);
        return roll;
    }

    private void rewardSheet(int hope, int stress) throws Exception {
        ObjectNode sheet = MAPPER.createObjectNode().put("featureRevision", 0);
        sheet.putObject("hope").put("on", hope).put("slots", 6);
        sheet.putObject("stress").put("on", stress).put("slots", 6);
        store.saveSheet(campaign.getId(), "reward-actor", sheet.toString());
    }

    @Test
    void reactionRollsCannotClaimHopeOrCriticalStressRecoveryEvenWithLegacyRewardMarker() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("reward-actor").name("Hero").ownerId(playerId()).build());
        rewardSheet(3, 2);
        String cookie = cookie();
        for (int fear : new int[]{3, 10}) {
            ObjectNode roll = rewardRoll(5100 + fear, 10, fear);
            roll.put("source", "trait:Agility,reaction");
            store.replaceHistoryEntry(campaign.getId(), roll.path("id").asLong(), 1234, roll);
            var response = rewardHttp(cookie, "reward-actor", roll);
            assertEquals(400, response.statusCode());
            assertTrue(response.body().contains("Reaction rolls"), response.body());
        }
        JsonNode sheet = MAPPER.readTree(store.loadSheet(campaign.getId(), "reward-actor"));
        assertEquals(3, sheet.path("hope").path("on").asInt());
        assertEquals(2, sheet.path("stress").path("on").asInt());
    }

    @Test
    void rollRewardsValidateOutcomeOwnershipCapsAndPersistOneClaimAcrossReloads() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("reward-actor").name("Hero").ownerId(playerId()).build());
        rewardSheet(3, 2);
        String cookie = cookie();
        ObjectNode hope = rewardRoll(5001, 10, 3);
        ObjectNode stale = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "reward-actor"));
        assertEquals(200, rewardHttp(cookie, "reward-actor", hope).statusCode());
        assertEquals(4, MAPPER.readTree(store.loadSheet(campaign.getId(), "reward-actor")).path("hope").path("on").asInt());
        assertEquals(2, MAPPER.readTree(store.loadSheet(campaign.getId(), "reward-actor")).path("stress").path("on").asInt());
        assertEquals(400, rewardHttp(cookie, "reward-actor", hope).statusCode());
        assertTrue(new CampaignStore(store.root()).historyTail(campaign.getId(), 20).getFirst().path("rewardClaimed").asBoolean());
        var staleSave = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/sheet/reward-actor"))
                .header("Cookie", cookie).PUT(HttpRequest.BodyPublishers.ofString(stale.toString())).build();
        assertEquals(409, http.send(staleSave, HttpResponse.BodyHandlers.ofString()).statusCode());

        assertEquals(400, rewardHttp(cookie, "reward-actor", rewardRoll(5002, 2, 11)).statusCode());
        ObjectNode converted = rewardRoll(5003, 2, 11);
        converted.put("outcome", "hope");
        store.replaceHistoryEntry(campaign.getId(), 5003, 1234, converted);
        assertEquals(200, rewardHttp(cookie, "reward-actor", converted).statusCode());
        assertEquals(5, MAPPER.readTree(store.loadSheet(campaign.getId(), "reward-actor")).path("hope").path("on").asInt());

        ObjectNode nonDuality = rewardRoll(5004, 10, 3);
        nonDuality.put("kind", "dice");
        store.replaceHistoryEntry(campaign.getId(), 5004, 1234, nonDuality);
        assertEquals(400, rewardHttp(cookie, "reward-actor", nonDuality).statusCode());
        ObjectNode legacy = rewardRoll(5005, 10, 3);
        legacy.remove("rewardClaimed");
        store.replaceHistoryEntry(campaign.getId(), 5005, 1234, legacy);
        assertEquals(400, rewardHttp(cookie, "reward-actor", legacy).statusCode());
        ObjectNode otherRoller = rewardRoll(5006, 10, 3);
        otherRoller.put("fromId", "someone-else");
        store.replaceHistoryEntry(campaign.getId(), 5006, 1234, otherRoller);
        assertEquals(400, rewardHttp(cookie, "reward-actor", otherRoller).statusCode());
        assertEquals(400, rewardHttp(cookie, "not-owned", hope).statusCode());

        rewardSheet(6, 2);
        ObjectNode capped = rewardRoll(5007, 10, 3);
        assertEquals(400, rewardHttp(cookie, "reward-actor", capped).statusCode());
        assertFalse(store.historyTail(campaign.getId(), 20).getLast().path("rewardClaimed").asBoolean());
        ObjectNode critical = rewardRoll(5008, 8, 8);
        assertEquals(200, rewardHttp(cookie, "reward-actor", critical).statusCode());
        JsonNode sheet = MAPPER.readTree(store.loadSheet(campaign.getId(), "reward-actor"));
        assertEquals(6, sheet.path("hope").path("on").asInt());
        assertEquals(1, sheet.path("stress").path("on").asInt());
        assertEquals(400, rewardHttp(cookie, "reward-actor", critical).statusCode());
        rewardSheet(6, 0);
        assertEquals(400, rewardHttp(cookie, "reward-actor", rewardRoll(5009, 8, 8)).statusCode());
        rewardSheet(5, 1);
        assertEquals(200, rewardHttp(cookie, "reward-actor", rewardRoll(5010, 8, 8)).statusCode());
        sheet = MAPPER.readTree(store.loadSheet(campaign.getId(), "reward-actor"));
        assertEquals(6, sheet.path("hope").path("on").asInt());
        assertEquals(0, sheet.path("stress").path("on").asInt());
    }

    @Test
    void newDualityRollAdvertisesRewardAndClaimSynchronizesChatAndSheet() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("reward-actor").name("Hero").ownerId(playerId()).build());
        rewardSheet(0, 2);
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            assertTrue(inbox.next("hello").path("rollRewards").asBoolean());
            ws.sendText("""
                    {"type":"roll","kind":"duality","expression":"2d12","source":"trait:Agility,reaction","actorId":"reward-actor"}
                    """, true).join();
            JsonNode reaction = inbox.next("roll");
            assertEquals("trait:Agility,reaction", reaction.path("source").asText());
            assertEquals("duality", reaction.path("kind").asText());
            assertFalse(reaction.has("rewardClaimed"));
            assertEquals(400, rewardHttp(cookie, "reward-actor", reaction).statusCode());
            JsonNode roll = null;
            for (int attempt = 0; attempt < 50; attempt++) {
                ws.sendText("""
                        {"type":"roll","kind":"duality","expression":"2d12","source":"action","actorId":"reward-actor"}
                        """, true).join();
                JsonNode message = inbox.next("roll");
                assertTrue(message.path("rewardClaimed").isBoolean());
                if (message.path("rolls").path(0).asInt() >= message.path("rolls").path(1).asInt()) {
                    roll = message;
                    break;
                }
            }
            assertNotNull(roll);
            assertEquals(0, MAPPER.readTree(store.loadSheet(campaign.getId(), "reward-actor")).path("hope").path("on").asInt());
            assertEquals(200, rewardHttp(cookie, "reward-actor", roll).statusCode());
            assertEquals(1, inbox.next("sheetFeature").path("hope").path("on").asInt());
            JsonNode update = inbox.next("rollUpdate");
            assertTrue(update.path("rewardClaimed").asBoolean());
            assertEquals(roll.path("id"), update.path("id"));
            assertTrue(dmMessages.stream().anyMatch(m -> "rollUpdate".equals(m.path("type").asText())
                    && m.path("rewardClaimed").asBoolean()));
        } finally { ws.abort(); }
    }

    @Test
    void simultaneousRewardClaimsGrantOnlyOnce() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("reward-actor").name("Hero").ownerId(playerId()).build());
        rewardSheet(0, 2);
        JsonNode roll = rewardRoll(6001, 7, 7);
        String cookie = cookie();
        ObjectNode body = MAPPER.createObjectNode().put("actorId", "reward-actor");
        body.putObject("roll").put("id", roll.path("id").asLong()).put("ts", roll.path("ts").asLong());
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/roll-reward"))
                .header("Cookie", cookie).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        var first = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        var second = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(List.of(200, 400), java.util.stream.Stream.of(first.join().statusCode(), second.join().statusCode()).sorted().toList());
        JsonNode sheet = MAPPER.readTree(store.loadSheet(campaign.getId(), "reward-actor"));
        assertEquals(1, sheet.path("hope").path("on").asInt());
        assertEquals(1, sheet.path("stress").path("on").asInt());
        assertEquals(1, sheet.path("featureRevision").asInt());
    }

    @Test
    void targetFeatureAppliesStepsToTargetAndDamageNotificationEndsSevereSourceEffects() throws Exception {
        Campaign.Player bob = Campaign.Player.builder().name("Bob")
                .passwordHash(PasswordHasher.hash("secret", 64, 1, 1)).build();
        campaign.getPlayers().add(bob);
        campaign.getActors().add(Campaign.Actor.builder().id("source").name("Caster").ownerId(playerId()).build());
        campaign.getActors().add(Campaign.Actor.builder().id("target").name("Ally").ownerId(bob.getId()).build());
        store.saveSheet(campaign.getId(), "source", """
                {"featureRevision":0,"usage":{},"hope":{"on":2,"slots":5},"features":[{"name":"Lifespring",
                "automation":{"uses":{"max":1,"per":"session"},"actions":[{"id":"heal","label":"Heal",
                "target":"actor","cost":{"hope":1},"steps":[{"op":"clear","resource":"hp","amount":1}]}]}},
                {"name":"Luckbringer","automation":{"uses":{"max":1,"per":"session"},"actions":[{"id":"share",
                "label":"Share Hope","target":"party","steps":[{"op":"gain","resource":"hope","amount":1}]}]}}]}
                """);
        store.saveSheet(campaign.getId(), "target", """
                {"featureRevision":0,"usage":{},"hope":{"on":4,"slots":5},"hp":{"on":3,"slots":6},"effects":[
                {"name":"Storm Ward","endOnSourceSevere":true,"sourceActorId":"source","sourceKey":"feat:Eye of the Storm"},
                {"name":"Other effect","endOnSourceSevere":false,"sourceActorId":"source","sourceKey":"feat:Other"}]}
                """);
        String cookie = cookie();
        Inbox inbox = new Inbox();
        Inbox aliceInbox = new Inbox();
        WebSocket ws = connect(playerCookie(bob), inbox);
        WebSocket aliceWs = connect(cookie, aliceInbox);
        try {
        ObjectNode heal = MAPPER.createObjectNode().put("actorId", "source").put("key", "feat:Lifespring")
                .put("actionId", "heal").put("targetActorId", "target");
        HttpResponse<String> healed = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/feature"))
                .header("Cookie", cookie).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(heal.toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, healed.statusCode(), healed.body());
        JsonNode sourceAfterHeal = MAPPER.readTree(store.loadSheet(campaign.getId(), "source"));
        JsonNode targetAfterHeal = MAPPER.readTree(store.loadSheet(campaign.getId(), "target"));
        assertEquals(1, sourceAfterHeal.path("hope").path("on").asInt());
        assertEquals(1, sourceAfterHeal.path("usage").path("feat:Lifespring").path("used").asInt());
        assertEquals(2, targetAfterHeal.path("hp").path("on").asInt());

        ObjectNode partyAction = MAPPER.createObjectNode().put("actorId", "source")
                .put("key", "feat:Luckbringer").put("actionId", "share");
        HttpResponse<String> partyResult = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/feature"))
                .header("Cookie", cookie).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(partyAction.toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, partyResult.statusCode(), partyResult.body());
        assertEquals(2, MAPPER.readTree(store.loadSheet(campaign.getId(), "source")).path("hope").path("on").asInt());
        assertEquals(5, MAPPER.readTree(store.loadSheet(campaign.getId(), "target")).path("hope").path("on").asInt());
        JsonNode actionReport = store.historyTail(campaign.getId(), 50).stream()
                .filter(entry -> entry.path("text").asText().contains("Luckbringer"))
                .findFirst().orElseThrow();
        assertTrue(actionReport.path("text").asText().contains("Caster: Gained 1 hope."));
        assertTrue(actionReport.path("text").asText().contains("Ally: Gained 1 hope."));

        ObjectNode damage = MAPPER.createObjectNode().put("actorId", "source").put("severity", 2);
        HttpResponse<String> minor = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/damage-taken"))
                .header("Cookie", cookie).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(damage.toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, minor.statusCode());
        assertEquals(2, MAPPER.readTree(store.loadSheet(campaign.getId(), "target")).path("effects").size());
        damage.put("severity", 3);
        HttpResponse<String> severe = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/damage-taken"))
                .header("Cookie", cookie).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(damage.toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, severe.statusCode(), severe.body());
        JsonNode targetAfterSevere = MAPPER.readTree(store.loadSheet(campaign.getId(), "target"));
        assertEquals(1, targetAfterSevere.path("effects").size());
        assertEquals("Other effect", targetAfterSevere.path("effects").get(0).path("name").asText());
        } finally {
            ws.abort();
            aliceWs.abort();
        }
    }

    @Test
    void startingANewHostSessionResetsOnlySessionUses() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("actor").name("Alice").ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), "actor", """
                {"featureRevision":0,"usage":{"session":{"used":1,"per":"session"},
                "rest":{"used":1,"per":"longRest"}}}
                """);
        Inbox initialInbox = new Inbox();
        WebSocket initial = connect(cookie(), initialInbox);
        String oldSession = initialInbox.next("hello").path("sessionId").asText();
        initial.abort();
        server.stop();
        server.start();

        Inbox nextInbox = new Inbox();
        WebSocket next = connect(cookie(), nextInbox);
        try {
            String nextSession = nextInbox.next("hello").path("sessionId").asText();
            assertFalse(oldSession.equals(nextSession));
            JsonNode usage = MAPPER.readTree(store.loadSheet(campaign.getId(), "actor")).path("usage");
            assertFalse(usage.has("session"));
            assertEquals(1, usage.path("rest").path("used").asInt());
        } finally {
            next.abort();
        }
    }

    @Test
    void chargeReactionsRollFixedDamageOnceForBothAncestries() throws Exception {
        JsonNode ancestries = MAPPER.readTree(Path.of("dmmap-daggerheart", "ancestries.json").toFile()).path("entries");
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            for (String ancestry : List.of("firbolg", "centaur")) {
                JsonNode charge = java.util.stream.StreamSupport.stream(ancestries.spliterator(), false)
                        .filter(entry -> ancestry.equals(entry.path("id").asText())).findFirst().orElseThrow()
                        .path("features").get(0);
                campaign.getActors().add(Campaign.Actor.builder().id(ancestry).name(ancestry).ownerId(playerId()).build());
                ObjectNode sheet = MAPPER.createObjectNode().put("featureRevision", 0);
                sheet.putObject("stress").put("on", 0).put("slots", 6);
                sheet.putObject("proficiency").put("on", 5).put("total", 5);
                sheet.putArray("heritage").add(charge);
                store.saveSheet(campaign.getId(), ancestry, sheet.toString());
                ws.sendText(MAPPER.createObjectNode().put("type", "roll").put("kind", "duality")
                        .put("expression", "2d12").put("source", "action,trait:Agility")
                        .put("actorId", ancestry).toString(), true).join();
                JsonNode original = inbox.next("roll");
                ObjectNode action = MAPPER.createObjectNode().put("actorId", ancestry)
                        .put("key", "feat:Charge").put("actionId", "charge");
                action.putObject("roll").put("id", original.path("id").asLong()).put("ts", original.path("ts").asLong());
                var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/feature"))
                        .header("Cookie", cookie).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(action.toString())).build();
                var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), response.body());
                JsonNode damage = inbox.next("roll");
                assertEquals("damage", damage.path("source").asText());
                assertEquals(ancestry, damage.path("actorId").asText());
                assertEquals("1d12", damage.path("expression").asText());
                assertEquals(1, damage.path("rolls").size(), "Charge must not scale with Proficiency");
                assertTrue(damage.path("total").asInt() >= 1 && damage.path("total").asInt() <= 12);
                assertEquals(damage.path("rolls").get(0).asInt(), damage.path("total").asInt());
                assertEquals(1, MAPPER.readTree(store.loadSheet(campaign.getId(), ancestry)).path("stress").path("on").asInt());
                assertEquals(400, http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
                assertEquals(1, MAPPER.readTree(store.loadSheet(campaign.getId(), ancestry)).path("stress").path("on").asInt());
                List<JsonNode> history = store.historyTail(campaign.getId(), 50);
                assertEquals(1, history.stream().filter(entry -> "damage".equals(entry.path("source").asText())
                        && ancestry.equals(entry.path("actorId").asText())).count());
                JsonNode savedOriginal = history.stream().filter(entry -> entry.path("id").equals(original.path("id"))
                        && entry.path("ts").equals(original.path("ts"))).findFirst().orElseThrow();
                assertEquals(original.path("rolls"), savedOriginal.path("rolls"));
                assertEquals(original.path("total"), savedOriginal.path("total"));
                assertTrue(savedOriginal.path("featureUses").toString().contains(ancestry + ":feat:Charge:charge"));
                assertEquals(0, history.stream().filter(entry -> entry.path("text").asText().contains("ACTION USED")).count());
            }
        } finally {
            ws.abort();
        }
    }

    @Test
    void featureCheckPublishesOneRollAndAppliesConfiguredStressConsequence() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("actor").name("Alice").ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), "actor", """
                {"featureRevision":0,"usage":{},"stress":{"on":0,"slots":6},
                "features":[{"name":"Unshakable","source":"Ancestry - Firbolg",
                "text":"When you would mark a Stress, roll a **d6**. On a result of 6, do not mark it.",
                "automation":{"actions":[{"id":"check","label":"Unshakable",
                "check":{"dice":"1d6","unless":6,"mark":"stress"}}]}}]}
                """);
        String cookie = cookie();
        ObjectNode action = MAPPER.createObjectNode().put("actorId", "actor")
                .put("key", "feat:Unshakable").put("actionId", "check");
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/feature"))
                .header("Cookie", cookie).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(action.toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        JsonNode result = store.historyTail(campaign.getId(), 10).stream()
                .filter(entry -> entry.path("featureRoll").isObject()).findFirst().orElseThrow();
        int die = result.path("featureRoll").path("result").asInt();
        assertTrue(die >= 1 && die <= 6);
        assertEquals(die == 6 ? "No Stress marked." : "Marked 1 Stress.", result.path("featureRoll").path("consequence").asText());
        assertEquals("Unshakable", result.path("rollSource").path("name").asText());
        assertEquals("Ancestry - Firbolg", result.path("rollSource").path("source").asText());
        assertEquals("When you would mark a Stress, roll a **d6**. On a result of 6, do not mark it.",
                result.path("rollSource").path("text").asText());
        assertEquals(die == 6 ? 0 : 1, MAPPER.readTree(store.loadSheet(campaign.getId(), "actor"))
                .path("stress").path("on").asInt());
        assertEquals(0, store.historyTail(campaign.getId(), 10).stream()
                .filter(entry -> entry.path("text").asText().contains("ACTION USED")).count());
    }

    @Test
    void campaignNotesAuthenticateIsolateOwnersAndShareWithLaterPlayers() throws Exception {
        assertEquals(401, notesHttp(null, "", null).statusCode());
        String alice = cookie();
        assertEquals(1, MAPPER.readTree(notesHttp(alice, "", null).body()).size());
        assertEquals(200, notesHttp(alice, "", MAPPER.readTree("""
                {"action":"create","path":"private","name":"Sessions","folder":true}
                """)).statusCode());
        JsonNode created = MAPPER.readTree(notesHttp(alice, "", MAPPER.readTree("""
                {"action":"create","path":"private/Sessions","name":"Secret","folder":false}
                """)).body());
        assertEquals("private/Sessions/Secret.txt", created.path("path").asText());
        Campaign.Player bob = Campaign.Player.builder().name("Bob")
                .passwordHash(PasswordHasher.hash("secret", 64, 1, 1)).build();
        campaign.getPlayers().add(bob);
        String bobCookie = playerCookie(bob);
        assertEquals(1, MAPPER.readTree(notesHttp(bobCookie, "", null).body()).size());
        assertEquals(404, notesHttp(bobCookie, "/content?path=private%2FSessions%2FSecret.txt", null).statusCode());
        for (String action : List.of("rename", "delete", "save")) {
            ObjectNode forged = MAPPER.createObjectNode().put("action", action).put("path", created.path("path").asText())
                    .put("name", "Stolen").put("text", "Stolen").put("revision", "").put("playerId", playerId());
            assertEquals(404, notesHttp(bobCookie, "", forged).statusCode());
        }
        JsonNode original = MAPPER.readTree(notesHttp(alice, "/content?path=group.txt", null).body());
        ObjectNode save = MAPPER.createObjectNode().put("action", "save").put("path", "group.txt")
                .put("text", "Shared plans").put("revision", original.path("revision").asText());
        assertEquals(200, notesHttp(alice, "", save).statusCode());
        assertEquals("Shared plans", MAPPER.readTree(notesHttp(bobCookie, "/content?path=group.txt", null).body()).path("text").asText());
        assertEquals(409, notesHttp(bobCookie, "", save.put("text", "Stale draft")).statusCode());
        assertEquals("Shared plans", new CampaignNotes(store.root()).read(campaign.getId(), bob.getId(), "group.txt").text());
        assertEquals(403, notesHttp(bobCookie, "", MAPPER.readTree("""
                {"action":"delete","path":"group.txt"}
                """)).statusCode());
        Inbox inbox = new Inbox();
        WebSocket ws = connect(alice, inbox);
        try {
            assertTrue(inbox.next("hello").path("notes").asBoolean());
        } finally { ws.abort(); }
    }

    @Test
    void campaignNotesRejectMalformedAndTraversalRequestsWithoutLosingText() throws Exception {
        String cookie = cookie();
        for (String body : List.of("null", "[]", "{}", "{\"action\":\"create\",\"path\":\"private\",\"folder\":\"yes\",\"name\":\"X\"}",
                "{\"action\":\"save\",\"path\":\"group.txt\",\"revision\":\"x\",\"text\":42}")) {
            assertEquals(400, notesHttp(cookie, "", MAPPER.readTree(body)).statusCode(), body);
        }
        assertEquals(400, notesHttp(cookie, "/content?path=private%2F..%2Fother.txt", null).statusCode());
        assertEquals(404, notesHttp(cookie, "/content?path=players%2Fsomeone%2Fsecret.txt", null).statusCode());
        assertEquals(404, notesHttp(cookie, "/content", null).statusCode());
        assertEquals("", MAPPER.readTree(notesHttp(cookie, "/content?path=group.txt", null).body()).path("text").asText());
    }

    @Test
    void campaignNotesStorageFailuresReturnExplicitErrors() throws Exception {
        String cookie = cookie();
        Path notesFolder = store.root().resolve(campaign.getId()).resolve("notes");
        java.nio.file.Files.writeString(notesFolder, "not a directory");
        var response = notesHttp(cookie, "", null);
        assertEquals(500, response.statusCode());
        assertTrue(MAPPER.readTree(response.body()).path("error").asText().contains("Could not access campaign notes"));
    }

    private HttpResponse<String> notesHttp(String cookie, String path, JsonNode body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/notes" + path))
                .header("Content-Type", "application/json");
        if (cookie != null) request.header("Cookie", cookie);
        if (body != null) request.POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> handoutHttp(String cookie, String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/handouts" + path));
        if (cookie != null) request.header("Cookie", cookie);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void handoutsAreFilteredPerPlayerServedOnlyWhenGrantedAndBroadcastWhenShown() throws Exception {
        var image = new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", png);
        CampaignHandouts handouts = server.handouts();
        handouts.createFolder("", "Maps");
        var secret = handouts.addImage("Maps", "Secret", png.toByteArray());
        var open = handouts.addImage("Maps", "Open", png.toByteArray());
        Campaign.Player bob = Campaign.Player.builder().name("Bob")
                .passwordHash(PasswordHasher.hash("secret", 64, 1, 1)).build();
        campaign.getPlayers().add(bob);
        String alice = cookie();
        String bobCookie = playerCookie(bob);

        assertEquals(401, handoutHttp(null, "").statusCode());
        assertEquals(0, MAPPER.readTree(handoutHttp(alice, "").body()).path("files").size());
        assertEquals(404, handoutHttp(alice, "/" + secret.id).statusCode());

        handouts.grant(List.of(open.id), false, java.util.Set.of(playerId()));
        JsonNode aliceList = MAPPER.readTree(handoutHttp(alice, "").body());
        assertEquals(1, aliceList.path("files").size());
        assertEquals("Open", aliceList.path("files").get(0).path("name").asText());
        assertEquals("Maps", aliceList.path("files").get(0).path("folder").asText());
        assertEquals(200, handoutHttp(alice, "/" + open.id).statusCode());
        assertEquals(404, handoutHttp(bobCookie, "/" + open.id).statusCode());
        assertEquals(0, MAPPER.readTree(handoutHttp(bobCookie, "").body()).path("files").size());

        Inbox inbox = new Inbox();
        WebSocket ws = connect(alice, inbox);
        try {
            assertTrue(inbox.next("hello").path("handouts").asBoolean());
            assertEquals(1, inbox.next("handouts").path("files").size());
            handouts.show(secret.id);
            assertEquals(secret.id, inbox.next("handoutShow").path("id").asText());
            assertEquals(200, handoutHttp(alice, "/" + secret.id).statusCode());
            assertEquals(200, handoutHttp(bobCookie, "/" + secret.id).statusCode());
        } finally { ws.abort(); }
    }

    @Test
    void onlyHostCanDeleteMessagesAndDeletionPersistsInSnapshots() throws Exception {
        server.publish(state("m1", 0), "m1/l1");
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        inbox.next("snapshot");
        try {
            ws.sendText("{\"type\":\"chat\",\"text\":\"remove me\"}", true).join();
            JsonNode message = inbox.next("chat");
            long id = message.path("id").asLong();
            long ts = message.path("ts").asLong();
            ws.sendText("{\"type\":\"chatDeleted\",\"id\":" + id + ",\"ts\":" + ts + "}", true).join();
            assertEquals("Unknown message", inbox.next("error").path("message").asText());
            assertEquals(1, server.dmHistory(100).size(), "A forged player deletion does not change history");
            assertEquals(null, server.dmDeleteMessage(id, ts));
            JsonNode deleted = inbox.next("chatDeleted");
            assertEquals(id, deleted.path("id").asLong());
            assertEquals(ts, deleted.path("ts").asLong());
            assertEquals(deleted, dmMessages.getLast());
            assertTrue(server.dmHistory(100).isEmpty());
            assertTrue(new CampaignStore(store.root()).historyTail(campaign.getId(), 100).isEmpty());
            assertNotNull(server.dmDeleteMessage(id, ts), "Deleting a missing message reports an error");
            Inbox late = new Inbox();
            WebSocket later = connect(cookie, late);
            try {
                assertTrue(late.next("snapshot").path("state").path("chatTail").isEmpty());
            } finally { later.abort(); }
            assertEquals(null, server.dmRoll("1d6", null, true));
            JsonNode roll = inbox.next("roll");
            assertEquals(null, server.dmDeleteMessage(roll.path("id").asLong(), roll.path("ts").asLong()));
            inbox.next("chatDeleted");
            assertTrue(server.dmHistory(100).isEmpty(), "Rolls can also be deleted");
        } finally { ws.abort(); }
    }

    @Test
    void whisperDeletionOnlyReachesItsAudienceAndCannotTargetPlayerPrivateChat() throws Exception {
        Campaign.Player bob = Campaign.Player.builder().name("Bob")
                .passwordHash(PasswordHasher.hash("secret", 64, 1, 1)).build();
        campaign.getPlayers().add(bob);
        server.publish(state("m1", 0), "m1/l1");
        Inbox aliceInbox = new Inbox();
        Inbox bobInbox = new Inbox();
        WebSocket alice = connect(cookie(), aliceInbox);
        WebSocket bobSocket = connect(playerCookie(bob), bobInbox);
        aliceInbox.next("snapshot");
        bobInbox.next("snapshot");
        try {
            server.dmChat("private DM message", playerId());
            JsonNode whisper = aliceInbox.next("chat");
            assertEquals(null, server.dmDeleteMessage(whisper.path("id").asLong(), whisper.path("ts").asLong()));
            aliceInbox.next("chatDeleted");
            server.dmChat("barrier", null);
            while (true) {
                JsonNode seen = bobInbox.messages.poll(5, TimeUnit.SECONDS);
                assertNotNull(seen, "Public barrier arrives after the private deletion");
                assertFalse("chatDeleted".equals(seen.path("type").asText()));
                assertTrue(seen.path("id").asLong() != whisper.path("id").asLong());
                if ("barrier".equals(seen.path("text").asText())) break;
            }
            alice.sendText("{\"type\":\"chat\",\"text\":\"private player message\",\"toId\":\"" + bob.getId() + "\"}", true).join();
            JsonNode privateChat = bobInbox.next("chat");
            assertNotNull(server.dmDeleteMessage(privateChat.path("id").asLong(), privateChat.path("ts").asLong()));
            assertTrue(store.historyTail(campaign.getId(), 100).contains(privateChat));
        } finally { alice.abort(); bobSocket.abort(); }
    }

    @Test
    void failedDeletionKeepsMessagesAndDeletingSharedOffersRevokesCachedAccess() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("source").name("Mara").ownerId(playerId()).build());
        campaign.getActors().add(Campaign.Actor.builder().id("target").name("Rook").ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), "source", "{\"inventory\":[{\"name\":\"Potion\",\"type\":\"consumable\",\"qty\":2}]}");
        store.saveSheet(campaign.getId(), "target", "{\"inventory\":[]}");
        server.publish(state("m1", 0), "m1/l1");
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        inbox.next("snapshot");
        try {
            ObjectNode request = MAPPER.createObjectNode().put("actorId", "source").put("category", "item").put("offerQuantity", 2);
            request.set("item", MAPPER.readTree("{\"name\":\"Potion\",\"type\":\"consumable\",\"qty\":2}"));
            var posted = cardHttp(cookie, "", request);
            assertEquals(200, posted.statusCode());
            String cardId = MAPPER.readTree(posted.body()).path("cardId").asText();
            JsonNode message = inbox.next("chat");
            long id = message.path("id").asLong();
            long ts = message.path("ts").asLong();
            Path history = store.root().resolve(campaign.getId()).resolve("history.jsonl");
            java.nio.file.Files.writeString(history, "invalid\n", java.nio.file.StandardOpenOption.APPEND);
            String error = server.dmDeleteMessage(id, ts);
            assertNotNull(error);
            assertTrue(error.startsWith("Could not delete chat message:"));
            assertTrue(server.dmHistory(100).contains(message));
            assertEquals("chat", dmMessages.getLast().path("type").asText(), "No deletion notification on failure");
            assertEquals(200, cardHttp(cookie, "/" + cardId, null).statusCode());
            java.nio.file.Files.writeString(history, message + "\n");
            assertEquals(null, server.dmDeleteMessage(id, ts));
            inbox.next("chatDeleted");
            assertEquals(404, cardHttp(cookie, "/" + cardId, null).statusCode());
            assertEquals(409, cardHttp(cookie, "/" + cardId + "/grab",
                    MAPPER.createObjectNode().put("actorId", "target")).statusCode());
            assertEquals(2, MAPPER.readTree(store.loadSheet(campaign.getId(), "source")).path("inventory").get(0).path("qty").asInt());
        } finally { ws.abort(); }
    }

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
        JsonNode hello = inbox.next("hello");
        assertTrue(hello.path("chatCards").asBoolean());
        assertTrue(hello.path("transferOffers").asBoolean());
        inbox.next("snapshot");
        inbox.next("roster");
        try {
            ws.sendText("{\"type\":\"chatIdentity\",\"identity\":\"Mara's player\"}", true).join();
            assertEquals("Mara's player", inbox.next("roster").path("players").get(0).path("chatIdentity").asText());
            ObjectNode request = MAPPER.createObjectNode().put("actorId", "source").put("category", "item").put("offerQuantity", 2);
            request.set("item", original.path("inventory").get(0));
            HttpResponse<String> post = cardHttp(cookie, "", request);
            assertEquals(200, post.statusCode(), post.body());
            String id = MAPPER.readTree(post.body()).path("cardId").asText();
            JsonNode message = inbox.next("chat");
            assertEquals("Mara's player", message.path("from").asText());
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
            assertEquals(0, source.path("inventory").size(), "Emptied stacks are removed");
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
            request.put("category", "item").put("offerQuantity", 1).set("item", source.path("inventory").get(0));
            var item = cardHttp(aliceCookie, "", request);
            assertEquals(200, item.statusCode());
            String itemId = MAPPER.readTree(item.body()).path("cardId").asText();
            assertEquals(409, cardHttp(bobCookie, "/" + itemId + "/grab",
                    MAPPER.createObjectNode().put("actorId", "source")).statusCode(), "Receiving actor must be owned");
            assertEquals(200, cardHttp(bobCookie, "/" + itemId + "/grab",
                    MAPPER.createObjectNode().put("actorId", "target")).statusCode());
            assertEquals(0, MAPPER.readTree(store.loadSheet(campaign.getId(), "source")).path("inventory").size());
            assertEquals(1, MAPPER.readTree(store.loadSheet(campaign.getId(), "target")).path("inventory").get(0).path("qty").asInt());
        } finally { alice.abort(); bobSocket.abort(); }
    }

    @Test
    void linksCannotBeGrabbedAndOfferBudgetsSurviveReloadAndForgedSaves() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("source").name("Mara").ownerId(playerId()).build());
        campaign.getActors().add(Campaign.Actor.builder().id("target").name("Rook").ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), "source", """
                {"inventory":[{"name":"Potion","type":"consumable","qty":5}],
                 "activeWeapons":[{"name":"Sword"}],"carriedWeapons":[],
                 "activeArmor":{"name":"Leather"},"carriedArmor":[]}
                """);
        store.saveSheet(campaign.getId(), "target", "{\"inventory\":[]}");
        server.publish(state("m1", 0), "m1/l1");
        String cookie = cookie();
        ObjectNode request = MAPPER.createObjectNode().put("actorId", "source").put("category", "item");
        request.set("item", MAPPER.readTree("{\"name\":\"Potion\",\"type\":\"consumable\",\"qty\":5}"));
        var link = cardHttp(cookie, "", request);
        assertEquals(200, link.statusCode());
        String linkId = MAPPER.readTree(link.body()).path("cardId").asText();
        ObjectNode grab = MAPPER.createObjectNode().put("actorId", "target");
        assertEquals(0, MAPPER.readTree(cardHttp(cookie, "/" + linkId, null).body()).path("available").asInt());
        assertEquals(409, cardHttp(cookie, "/" + linkId + "/grab", grab).statusCode());
        for (String invalid : List.of("0", "-1", "6", "1.5", "\"2\"")) {
            request.set("offerQuantity", MAPPER.readTree(invalid));
            assertEquals(400, cardHttp(cookie, "", request).statusCode());
        }
        request.put("offerQuantity", 2);
        var offer = cardHttp(cookie, "", request);
        assertEquals(200, offer.statusCode(), offer.body());
        String id = MAPPER.readTree(offer.body()).path("cardId").asText();
        assertEquals(200, cardHttp(cookie, "/" + id + "/grab", grab).statusCode());
        assertEquals(1, MAPPER.readTree(cardHttp(cookie, "/" + id, null).body()).path("available").asInt());
        assertEquals(200, cardHttp(cookie, "/" + id + "/grab", grab.put("all", true)).statusCode());
        assertEquals(409, cardHttp(cookie, "/" + id + "/grab", grab).statusCode());
        ObjectNode source = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "source"));
        assertEquals(3, source.path("inventory").get(0).path("qty").asInt(), "Only offered quantity moves");
        source.putObject("transferClaims").put(id, 0);
        var saved = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/sheet/source"))
                        .header("Cookie", cookie).PUT(HttpRequest.BodyPublishers.ofString(source.toString())).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, saved.statusCode(), saved.body());
        assertEquals(2, MAPPER.readTree(store.loadSheet(campaign.getId(), "source")).path("transferClaims").path(id).asInt());
        // A new server has neither card cache nor an in-memory claim count.
        server.stop();
        store = new CampaignStore(temp.resolve("campaigns"));
        server = new OnlineSessionServer(new OnlineSessionServer.Config(port, "Game", 4, 65536, 50, 100, 3, 60),
                campaign, store, new AssetRegistry(temp.resolve("cache"), 2048, store), new OnlineSessionServer.Host() {
                    @Override
                    public CompletableFuture<OnlineSessionServer.MoveResult> moveToken(String playerId, String tokenId, double x, double y) {
                        return CompletableFuture.completedFuture(OnlineSessionServer.MoveResult.accepted());
                    }
                    @Override
                    public void rosterChanged() {}
                    @Override
                    public void campaignChanged(Campaign c) {}
                    @Override
                    public void dmMessage(JsonNode message) {}
                });
        server.start();
        server.publish(state("m1", 0), "m1/l1");
        cookie = cookie();
        assertEquals(0, MAPPER.readTree(cardHttp(cookie, "/" + id, null).body()).path("available").asInt());
        assertEquals(409, cardHttp(cookie, "/" + id + "/grab", grab).statusCode());
        request.put("offerQuantity", 3);
        var bulk = cardHttp(cookie, "", request);
        assertEquals(200, bulk.statusCode(), bulk.body());
        String bulkId = MAPPER.readTree(bulk.body()).path("cardId").asText();
        store.saveSheet(campaign.getId(), "target", "{\"inventory\":[{\"name\":\"Potion\",\"type\":\"consumable\",\"qty\":4}]}");
        assertEquals(409, cardHttp(cookie, "/" + bulkId + "/grab", grab).statusCode(), "Grab all fails atomically if the cap would be exceeded");
        assertEquals(3, MAPPER.readTree(cardHttp(cookie, "/" + bulkId, null).body()).path("available").asInt());
        assertEquals(4, MAPPER.readTree(store.loadSheet(campaign.getId(), "target")).path("inventory").get(0).path("qty").asInt());
        store.saveSheet(campaign.getId(), "target", "{\"inventory\":[{\"name\":\"Potion\",\"type\":\"consumable\",\"qty\":2}]}");
        var grabbed = cardHttp(cookie, "/" + bulkId + "/grab", grab);
        assertEquals(200, grabbed.statusCode(), grabbed.body());
        assertEquals(3, MAPPER.readTree(grabbed.body()).path("quantity").asInt());
        assertEquals(0, MAPPER.readTree(cardHttp(cookie, "/" + bulkId, null).body()).path("available").asInt());
        assertEquals(5, MAPPER.readTree(store.loadSheet(campaign.getId(), "target")).path("inventory").get(0).path("qty").asInt());
        request.put("category", "weapon").put("offerQuantity", 1).set("item", source.path("activeWeapons").get(0));
        assertEquals(400, cardHttp(cookie, "", request).statusCode(), "Active weapons cannot be offered");
        request.put("category", "armor").set("item", source.path("activeArmor"));
        assertEquals(400, cardHttp(cookie, "", request).statusCode(), "Active armor cannot be offered");
    }

    @Test
    void compendiumChatLinksResolveHostDataWithoutInventoryAndCannotTransfer() throws Exception {
        Path dir = temp.resolve("chat-compendium");
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve("items.json"), """
                {"category":"items","label":"Items","entries":[{"id":"potion","name":"Host potion","rarity":"Rare","text":"Clear **HP**."}]}
                """);
        compendiumDir = dir;
        server.publish(state("m1", 0), "m1/l1");
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            assertTrue(inbox.next("hello").path("compendiumChat").asBoolean());
            inbox.next("snapshot");
            inbox.next("roster");
            ObjectNode request = MAPPER.createObjectNode();
            ObjectNode reference = request.putObject("compendium").put("category", "items").put("entryId", "potion");
            request.putObject("item").put("name", "Forged client name");
            assertEquals(401, cardHttp("", "", request).statusCode());
            HttpResponse<String> posted = cardHttp(cookie, "", request);
            assertEquals(200, posted.statusCode(), posted.body());
            JsonNode card = MAPPER.readTree(posted.body());
            assertEquals("feature", card.path("category").asText());
            assertEquals("Host potion", card.path("item").path("name").asText());
            assertEquals("Compendium - Items", card.path("actorName").asText());
            assertEquals(MAPPER.readTree("[\"gear\",\"Rare\"]"), card.path("badges"));
            assertFalse(card.path("item").has("badges"));
            assertEquals("Clear **HP**.", card.path("sections").get(0).path("text").asText());
            assertFalse(card.path("item").has("sections"));
            assertEquals("", card.path("actorId").asText());
            assertEquals(0, card.path("available").asInt());
            assertFalse(card.has("offerQuantity"));
            JsonNode message = inbox.next("chat");
            assertEquals(card, message.path("sharedCard"));
            assertTrue(message.path("text").asText().contains("Clear **HP**."));
            assertTrue(server.dmHistory(100).contains(message));
            request.put("offerQuantity", 1);
            assertEquals(400, cardHttp(cookie, "", request).statusCode());
            request.remove("offerQuantity");
            reference.put("entryId", "missing");
            assertEquals(400, cardHttp(cookie, "", request).statusCode());
            reference.put("category", "../items").put("entryId", "potion");
            assertEquals(400, cardHttp(cookie, "", request).statusCode());
        } finally { ws.abort(); }
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
    void secretDmRollsAreMaskedForPlayersButKeptForTheDm() throws Exception {
        String cookie = cookie();
        Inbox inbox = new Inbox();
        connect(cookie, inbox);
        inbox.next("hello");
        assertEquals(null, server.dmRoll("2d6+3", null, true));
        JsonNode seen = inbox.next("roll");
        assertTrue(seen.path("secret").asBoolean());
        assertFalse(seen.has("secretReal"));
        assertEquals("2d6", seen.path("expression").asText());
        assertEquals(0, seen.path("total").asInt());
        assertEquals(2, seen.path("rolls").size());
        seen.path("rolls").forEach(r -> assertEquals(0, r.asInt()));

        JsonNode own = server.dmHistory(10).get(server.dmHistory(10).size() - 1);
        assertEquals("2d6+3", own.path("secretReal").path("expression").asText());
        assertTrue(own.path("secretReal").path("total").asInt() >= 5);

        assertEquals(null, server.dmRoll("1d20", null, false));
        JsonNode open = inbox.next("roll");
        assertFalse(open.path("secret").asBoolean());
        assertTrue(open.path("total").asInt() >= 1);
    }

    @Test
    void maskExpressionKeepsOnlyDiceTerms() {
        assertEquals("2d6+1d8kh1", OnlineSessionServer.maskExpression("2d6+4+1d8kh1"));
        assertEquals("?", OnlineSessionServer.maskExpression("5"));
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

    @Test
    void countdownsReplicateAndRejectAllPlayerEdits() throws Exception {
        var countdown = new Campaign.Countdown("clock", "Reinforcements", 6, 4);
        campaign.getCountdowns().add(countdown);
        store.save(campaign);
        ObjectNode first = state("m1", 0);
        first.set("countdowns", MAPPER.valueToTree(campaign.getCountdowns()));
        server.publish(first, "m1/l1");
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie(), inbox);
        try {
            assertEquals(4, inbox.next("snapshot").path("state").path("countdowns").get(0).path("value").asInt());
            for (String action : List.of("create", "delete", "step")) {
                ws.sendText("{\"type\":\"command\",\"cmdId\":\"forged\",\"kind\":\"countdown\",\"action\":\""
                        + action + "\",\"id\":\"clock\",\"value\":0,\"name\":\"Forged\",\"initialValue\":1}", true).join();
                assertEquals("unknown command", inbox.next("reject").path("reason").asText());
                assertEquals(List.of(countdown), store.load(campaign.getId()).getCountdowns());
            }
            ObjectNode stepped = first.deepCopy();
            ((ObjectNode) stepped.path("countdowns").get(0)).put("value", 3);
            server.publish(stepped, "m1/l1");
            JsonNode op = inbox.next("delta").path("ops").get(0);
            assertEquals("countdowns", op.path("path").asText());
            assertEquals(3, op.path("value").get(0).path("value").asInt());
            ObjectNode deleted = stepped.deepCopy();
            deleted.putArray("countdowns");
            server.publish(deleted, "m1/l1");
            assertTrue(inbox.next("delta").path("ops").get(0).path("value").isEmpty());
            ws.sendText("{\"type\":\"resync\",\"lastSeq\":0}", true).join();
            assertTrue(inbox.next("snapshot").path("state").path("countdowns").isEmpty());
        } finally {
            ws.abort();
        }
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
    void chatIdentityIsValidatedAndSharedWithoutChangingAccountNamesOrCompatibility() throws Exception {
        String cookie = cookie();
        Inbox first = new Inbox();
        WebSocket ws = connect(cookie, first);
        JsonNode hello = first.next("hello");
        assertTrue(hello.path("chatIdentity").asBoolean());
        assertEquals(OnlineProtocol.VERSION, hello.path("protocol").asInt());
        String accountName = hello.path("playerName").asText();
        first.next("roster");
        Inbox second = new Inbox();
        WebSocket other = connect(cookie, second);
        second.next("hello");
        second.next("roster");
        first.next("roster");
        try {
            ws.sendText("{\"type\":\"chatIdentity\",\"identity\":\"  Rook  \"}", true).join();
            JsonNode roster = second.next("roster").path("players").get(0);
            first.next("roster");
            assertEquals("Rook", roster.path("chatIdentity").asText());
            Campaign loaded = store.load(campaign.getId());
            assertEquals("Rook", loaded.player(playerId()).getChatIdentity());
            server.setCampaign(loaded);
            assertEquals(accountName, roster.path("playerName").asText());
            assertEquals(2, roster.path("connections").asInt());
            other.sendText("{\"type\":\"chat\",\"text\":\"hello\"}", true).join();
            assertEquals("Rook", first.next("chat").path("from").asText());
            other.sendText("{\"type\":\"roll\",\"expression\":\"1d6\"}", true).join();
            assertEquals("Rook", first.next("roll").path("from").asText());
            server.dmChat("private", playerId());
            assertEquals("Rook", first.next("chat").path("to").asText());
            for (String invalid : List.of("x".repeat(61), "bad\nname")) {
                ws.sendText(MAPPER.createObjectNode().put("type", "chatIdentity").put("identity", invalid).toString(), true).join();
                assertTrue(first.next("error").path("message").asText().contains("Chat identity"));
                assertEquals("Rook", server.roster().getFirst().chatIdentity());
            }
            ws.sendText("{\"type\":\"chatIdentity\",\"identity\":123}", true).join();
            assertTrue(first.next("error").path("message").asText().contains("must be text"));
            ws.sendText("{\"type\":\"chatIdentity\",\"identity\":\"\"}", true).join();
            first.next("roster");
            other.sendText("{\"type\":\"chat\",\"text\":\"account name again\"}", true).join();
            assertEquals(accountName, first.next("chat").path("from").asText());
            assertEquals("", store.load(campaign.getId()).player(playerId()).getChatIdentity());
            java.nio.file.Path blockedSave = temp.resolve("campaigns").resolve(campaign.getId()).resolve("campaign.json.tmp");
            java.nio.file.Files.createDirectory(blockedSave);
            try {
                ws.sendText("{\"type\":\"chatIdentity\",\"identity\":\"Unsaved\"}", true).join();
                assertTrue(first.next("error").path("message").asText().contains("Could not save chat identity"));
                assertEquals("", server.roster().getFirst().chatIdentity());
                assertEquals("", store.load(campaign.getId()).player(playerId()).getChatIdentity());
            } finally {
                java.nio.file.Files.delete(blockedSave);
            }
        } finally {
            ws.abort();
            other.abort();
        }
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
