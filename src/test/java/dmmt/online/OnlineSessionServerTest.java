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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnlineSessionServerTest {
    @Test
    void dreadOrdinaryDamageChatRetainsOwnedActorMarkerAndRejectsOtherActors() throws Exception {
        splendorActor("damaged");
        splendorActor("other");
        allyCookie("other-player");
        campaign.actor("other").setOwnerId("other-player");
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie(), inbox);
        try {
            ObjectNode report = MAPPER.createObjectNode().put("type", "chat").put("actorId", "damaged")
                    .put("text", "Took physical damage.").put("incomingDamage", true);
            ws.sendText(report.toString(), true).join();
            JsonNode delivered = inbox.next("chat");
            assertEquals("damaged", delivered.path("actorId").asText());
            assertTrue(delivered.path("incomingDamage").asBoolean());
            report.put("actorId", "other");
            ws.sendText(report.toString(), true).join();
            assertFalse(inbox.next("error").path("message").asText().isBlank());
            report.put("actorId", "damaged").remove("incomingDamage");
            ws.sendText(report.toString(), true).join();
            assertFalse(inbox.next("chat").has("incomingDamage"));
        } finally { ws.abort(); }
    }

    @Test
    void dreadDarkArmyRequiresASuccessAndDireStrikeRemovesFearOnlyFromWeaponDamage() throws Exception {
        ObjectNode sheet = splendorActor("caster", "Dark Army", "Dire Strike");
        sheet.putArray("activeWeapons").addObject().put("name", "Sword").put("slot", "Primary")
                .put("damage", "d8").put("trait", "Strength").put("damageType", "phys");
        store.saveSheet(campaign.getId(), "caster", sheet.toString());
        campaign.setFear(3);
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            long seed = 0;
            while (true) {
                var random = new java.util.Random(seed);
                if (random.nextInt(12) != random.nextInt(12)) break;
                seed++;
            }
            diceSeed(seed);
            JsonNode failed = splendorRoll(ws, inbox, "caster", "Dark Army", "2d12-30");
            assertEquals(400, featureHttp(cookie, splendorRequest("caster", "Dark Army", "summon", failed)).statusCode());
            JsonNode cast = splendorRoll(ws, inbox, "caster", "Dark Army", "2d12+30");
            var response = featureHttp(cookie, splendorRequest("caster", "Dark Army", "summon", cast));
            assertEquals(200, response.statusCode(), response.body());
            ObjectNode summoned = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "caster"));
            assertEquals(8, summoned.path("resources").path("fiends").asInt());
            assertEquals("Dark Army", summoned.path("effects").path(0).path("name").asText());
            assertEquals(1, summoned.path("usage").path("card:Dark Army").path("used").asInt());
            assertEquals(400, featureHttp(cookie, splendorRequest("caster", "Dark Army", "summon", cast)).statusCode());
            summoned.withObject("/resources").put("fiends", 0);
            WitchEffects.normalize(summoned, null);
            assertTrue(summoned.path("effects").isEmpty());
            ObjectNode damage = MAPPER.createObjectNode().put("type", "roll").put("actorId", "caster")
                    .put("source", "damage").put("expression", "2d8").put("weapon", "Sword");
            ws.sendText(damage.toString(), true).join();
            JsonNode rolled = inbox.next("roll");
            response = featureHttp(cookie, splendorRequest("caster", "Dire Strike", "use", rolled));
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(2, campaign.getFear());
            assertEquals(5, MAPPER.readTree(store.loadSheet(campaign.getId(), "caster")).path("hope").path("on").asInt());
            assertEquals(400, featureHttp(cookie, splendorRequest("caster", "Dire Strike", "use", rolled)).statusCode());
        } finally { ws.abort(); }
    }

    @Test
    void dreadImmediateDamageUsesSpellcastAndDarkfireChargesTheSelectedPositiveHope() throws Exception {
        ObjectNode sheet = splendorActor("dread", "Summon Horror", "Darkfire");
        String cookie = cookie();
        var response = featureHttp(cookie, splendorRequest("dread", "Summon Horror", "use", null));
        assertEquals(200, response.statusCode(), response.body());
        JsonNode horror = dmMessages.stream().filter(m -> m.path("source").asText().contains("featureAction:card:Summon Horror:use"))
                .findFirst().orElseThrow();
        int count = CharacterSheetRules.spellcastTotal(sheet, campaign.getFear());
        assertEquals(count + "d8+1", horror.path("expression").asText());
        assertEquals(count, horror.path("rolls").size());
        assertEquals(1, MAPPER.readTree(store.loadSheet(campaign.getId(), "dread")).path("stress").path("on").asInt());
        assertEquals(400, featureHttp(cookie, splendorRequest("dread", "Summon Horror", "use", null)).statusCode());
        ObjectNode fire = splendorRequest("dread", "Darkfire", "use", null).put("amount", 0);
        assertEquals(400, featureHttp(cookie, fire).statusCode());
        assertEquals(6, MAPPER.readTree(store.loadSheet(campaign.getId(), "dread")).path("hope").path("on").asInt());
        fire.put("amount", 3);
        response = featureHttp(cookie, fire);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode darkfire = dmMessages.stream().filter(m -> m.path("source").asText().contains("featureAction:card:Darkfire:use"))
                .findFirst().orElseThrow();
        assertEquals(count + "d8+6", darkfire.path("expression").asText());
        assertEquals(3, MAPPER.readTree(store.loadSheet(campaign.getId(), "dread")).path("hope").path("on").asInt());
    }

    @Test
    void dreadSiphonHealingIsBoundToDamageAndRemainsAvailableAfterItsLongRestUse() throws Exception {
        ObjectNode sheet = splendorActor("siphon", "Siphon Essence");
        sheet.withObject("/usage").putObject("card:Siphon Essence").put("used", 1).put("per", "longRest");
        store.saveSheet(campaign.getId(), "siphon", sheet.toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            ObjectNode input = MAPPER.createObjectNode().put("type", "roll").put("actorId", "siphon")
                    .put("expression", "2d12+4").put("source", "damage,magicdamage,spelldamage");
            input.putObject("rollSource").put("name", "Siphon Essence").put("text", "").put("source", "Dread");
            ws.sendText(input.toString(), true).join();
            JsonNode roll = inbox.next("roll");
            ObjectNode heal = splendorRequest("siphon", "Siphon Essence", "heal", roll).put("amount", 2);
            var response = featureHttp(cookie, heal);
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(1, MAPPER.readTree(store.loadSheet(campaign.getId(), "siphon")).path("hp").path("on").asInt());
            assertEquals(400, featureHttp(cookie, heal).statusCode());
            input.withObject("/rollSource").put("name", "Another Spell");
            ws.sendText(input.toString(), true).join();
            heal.set("roll", splendorRequest("siphon", "Siphon Essence", "heal", inbox.next("roll")).path("roll"));
            assertEquals(400, featureHttp(cookie, heal).statusCode());
        } finally { ws.abort(); }
    }

    @Test
    void dreadHideousRetributionPaysStressOnlyAfterItsReactionAndDamnationRollsSelectedStress() throws Exception {
        ObjectNode sheet = splendorActor("caster", "Hideous Retribution", "Damnation");
        sheet.putObject("proficiency").put("on", 2);
        store.saveSheet(campaign.getId(), "caster", sheet.toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            ObjectNode input = MAPPER.createObjectNode().put("type", "roll").put("kind", "duality").put("actorId", "caster")
                    .put("expression", "2d12+30").put("source", "reaction,spellcast,card:Hideous Retribution,featureAction:card:Hideous Retribution:reaction");
            ws.sendText(input.toString(), true).join();
            JsonNode reaction = inbox.next("roll");
            assertEquals(0, MAPPER.readTree(store.loadSheet(campaign.getId(), "caster")).path("stress").path("on").asInt());
            var response = featureHttp(cookie, splendorRequest("caster", "Hideous Retribution", "damage", reaction));
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(1, MAPPER.readTree(store.loadSheet(campaign.getId(), "caster")).path("stress").path("on").asInt());
            JsonNode damage = dmMessages.stream().filter(m -> m.path("source").asText().contains("featureAction:card:Hideous Retribution:damage"))
                    .findFirst().orElseThrow();
            assertEquals("2d6", damage.path("expression").asText());
            inbox.next("roll");
            JsonNode cast = splendorRoll(ws, inbox, "caster", "Damnation", "2d12+30");
            response = featureHttp(cookie, splendorRequest("caster", "Damnation", "damage", cast).put("amount", 3));
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(4, MAPPER.readTree(store.loadSheet(campaign.getId(), "caster")).path("stress").path("on").asInt());
            assertTrue(dmMessages.stream().anyMatch(m -> m.path("expression").asText().equals("3d20")));
        } finally { ws.abort(); }
    }

    @Test
    void dreadVeilAndMistEndWithSceneAndMistAlsoEndsOnAnAction() throws Exception {
        splendorActor("caster", "Umbral Veil", "Spectral Mist");
        splendorActor("ally");
        String ally = allyCookie("mist-player");
        campaign.actor("ally").setOwnerId("mist-player");
        campaign.setFear(4);
        String cookie = cookie();
        var response = featureHttp(cookie, splendorRequest("caster", "Umbral Veil", "cast", null));
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(4, MAPPER.readTree(store.loadSheet(campaign.getId(), "caster")).path("resources").path("veil").asInt());
        assertEquals(1, MAPPER.readTree(store.loadSheet(campaign.getId(), "caster")).path("usage").path("card:Umbral Veil").path("used").asInt());
        assertEquals(400, featureHttp(cookie, splendorRequest("caster", "Umbral Veil", "cast", null)).statusCode());
        response = featureHttp(cookie, splendorRequest("caster", "Spectral Mist", "use", null));
        assertEquals(200, response.statusCode(), response.body());
        JsonNode message = dmMessages.stream().filter(m -> m.path("actionBenefits").isObject()).findFirst().orElseThrow();
        ObjectNode claim = splendorRequest("ally", "Spectral Mist", "claim", message).put("benefitId", "mist");
        var claimed = featureHttp(ally, claim);
        assertEquals(200, claimed.statusCode(), claimed.body());
        assertTrue(MAPPER.readTree(store.loadSheet(campaign.getId(), "ally")).path("effects").path(0).path("endOnAction").asBoolean());
        Inbox inbox = new Inbox();
        WebSocket ws = connect(ally, inbox);
        try {
            splendorRoll(ws, inbox, "ally", "Spectral Mist", "2d12");
            assertTrue(MAPPER.readTree(store.loadSheet(campaign.getId(), "ally")).path("effects").isEmpty());
            assertNull(server.endScene());
            JsonNode ended = MAPPER.readTree(store.loadSheet(campaign.getId(), "caster"));
            assertEquals(0, ended.path("resources").path("veil").asInt());
            assertTrue(ended.path("effects").isEmpty());
        } finally { ws.abort(); }
    }

    @Test
    void dreadInvokeTormentAttributesTheAddedTotalWithoutRerollingDice() throws Exception {
        splendorActor("torment", "Invoke Torment");
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie(), inbox);
        try {
            ObjectNode input = MAPPER.createObjectNode().put("type", "roll").put("actorId", "torment")
                    .put("source", "damage").put("expression", "(2d6+3+0)*2");
            var parts = input.putArray("contributions");
            parts.addObject().put("label", "Weapon").put("expression", "(2d6+3)*2");
            parts.addObject().put("label", "Invoke Torment: target has full Stress").put("expression", "(0)*2");
            for (JsonNode part : parts) ((ObjectNode) part).putArray("notes").addObject()
                    .put("label", "Invoke Torment: target has full Stress").put("impact", "Double the rolled damage total");
            ws.sendText(input.toString(), true).join();
            JsonNode roll = inbox.next("roll");
            assertEquals(2, roll.path("rolls").size());
            assertEquals(2, roll.path("breakdown").size());
            int base = roll.path("breakdown").path(0).path("total").asInt();
            assertEquals("Invoke Torment", roll.path("breakdown").path(1).path("label").asText());
            assertEquals(base, roll.path("breakdown").path(1).path("total").asInt());
            assertEquals(base * 2, roll.path("total").asInt());
        } finally { ws.abort(); }
    }

    private ObjectNode splendorActor(String id, String... cards) throws Exception {
        compendiumDir = Path.of("dmmap-daggerheart");
        campaign.getActors().add(Campaign.Actor.builder().id(id).name(id).ownerId(playerId()).build());
        ObjectNode sheet = sageArcanaSheet(cards);
        sheet.withObject("/hp").put("on", 3);
        sheet.withObject("/stress").put("on", 0);
        sheet.withObject("/hope").put("on", 6);
        store.saveSheet(campaign.getId(), id, sheet.toString());
        return sheet;
    }

    private ObjectNode splendorRequest(String actor, String card, String action, JsonNode roll) {
        ObjectNode request = MAPPER.createObjectNode().put("actorId", actor).put("key", "card:" + card).put("actionId", action);
        if (roll != null) request.putObject("roll").put("id", roll.path("id").asLong()).put("ts", roll.path("ts").asLong());
        return request;
    }

    private JsonNode splendorRoll(WebSocket ws, Inbox inbox, String actor, String card, String expression) throws Exception {
        ObjectNode input = MAPPER.createObjectNode().put("type", "roll").put("kind", "duality").put("actorId", actor)
                .put("expression", expression).put("source", "action,spellcast,card:" + card).put("difficulty", 16);
        ws.sendText(input.toString(), true).join();
        return inbox.next("roll");
    }

    @Test
    void splendorHealingHandsUsesActualSuccessAndTargetLongRestLock() throws Exception {
        splendorActor("healer", "Healing Hands");
        splendorActor("patient");
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            JsonNode roll = splendorRoll(ws, inbox, "healer", "Healing Hands", "2d12+30");
            ObjectNode request = splendorRequest("healer", "Healing Hands", "hp", roll).put("choiceId", "hp")
                    .put("targetActorId", "healer");
            assertEquals(400, featureHttp(cookie, request).statusCode());
            request.put("targetActorId", "patient");
            var response = featureHttp(cookie, request);
            assertEquals(200, response.statusCode(), response.body());
            JsonNode patient = MAPPER.readTree(store.loadSheet(campaign.getId(), "patient"));
            assertEquals(1, patient.path("hp").path("on").asInt());
            assertEquals("longRest", patient.path("effects").path(0).path("until").asText());
            assertEquals(1, MAPPER.readTree(store.loadSheet(campaign.getId(), "healer")).path("stress").path("on").asInt());
            assertEquals(400, featureHttp(cookie, request).statusCode(), "A roll cannot be replayed.");
            JsonNode second = splendorRoll(ws, inbox, "healer", "Healing Hands", "2d12+30");
            request.set("roll", splendorRequest("healer", "Healing Hands", "hp", second).path("roll"));
            assertEquals(400, featureHttp(cookie, request).statusCode(), "Another cast must respect the target lock.");
            ObjectNode rested = (ObjectNode) patient.deepCopy();
            rested.put("longRestRevision", 1);
            store.saveSheet(campaign.getId(), "patient", rested.toString());
            ObjectNode saved = rested.deepCopy();
            saved.putArray("effects");
            SplendorActions.preserveSave(saved, (ObjectNode) patient);
            assertTrue(saved.path("effects").isEmpty(), "Only the target's long rest clears the restriction.");
        } finally { ws.abort(); }
    }

    @Test
    void splendorSalvationAllocationsAreAtomicAndBoundedByStressAndWounds() throws Exception {
        splendorActor("beam", "Salvation Beam");
        splendorActor("first");
        splendorActor("second");
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            JsonNode roll = splendorRoll(ws, inbox, "beam", "Salvation Beam", "2d12+30");
            ObjectNode request = splendorRequest("beam", "Salvation Beam", "heal", roll);
            var allocations = request.putArray("allocations");
            allocations.addObject().put("actorId", "first").put("amount", 2);
            allocations.addObject().put("actorId", "second").put("amount", 4);
            assertEquals(400, featureHttp(cookie, request).statusCode());
            assertEquals(3, MAPPER.readTree(store.loadSheet(campaign.getId(), "first")).path("hp").path("on").asInt());
            assertEquals(0, MAPPER.readTree(store.loadSheet(campaign.getId(), "beam")).path("stress").path("on").asInt());
            ((ObjectNode) allocations.path(1)).put("amount", 1);
            var response = featureHttp(cookie, request);
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(1, MAPPER.readTree(store.loadSheet(campaign.getId(), "first")).path("hp").path("on").asInt());
            assertEquals(2, MAPPER.readTree(store.loadSheet(campaign.getId(), "second")).path("hp").path("on").asInt());
            assertEquals(3, MAPPER.readTree(store.loadSheet(campaign.getId(), "beam")).path("stress").path("on").asInt());
            assertEquals(400, featureHttp(cookie, request).statusCode());
        } finally { ws.abort(); }
    }

    @Test
    void splendorInvigorationOffersSpentUsesAndRestoresExactlyOneFromHostDice() throws Exception {
        ObjectNode source = splendorActor("invigorator", "Invigoration");
        ObjectNode target = splendorActor("limited", "Second Wind");
        target.withObject("/usage").withObject("/card:Second Wind").put("used", 2).put("per", "rest");
        store.saveSheet(campaign.getId(), "limited", target.toString());
        String cookie = cookie();
        var choices = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/splendor/invigorator"))
                .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, choices.statusCode(), choices.body());
        JsonNode candidates = MAPPER.readTree(choices.body());
        assertEquals(1, candidates.path("features").size());
        assertEquals("card:Second Wind", candidates.path("features").path(0).path("key").asText());
        assertEquals(1, candidates.path("allies").size());
        assertFalse(candidates.toString().contains("traits"));
        long seed = 0;
        while (new java.util.Random(seed).nextInt(6) != 5) seed++;
        diceSeed(seed);
        var response = featureHttp(cookie, splendorRequest("invigorator", "Invigoration", "restore", null).put("amount", 1));
        assertEquals(200, response.statusCode(), response.body());
        JsonNode rolled = dmMessages.stream().filter(m -> m.path("source").asText().equals("featureAction:card:Invigoration:restore"))
                .findFirst().orElseThrow();
        assertEquals(6, rolled.path("rolls").path(0).asInt());
        assertEquals(5, MAPPER.readTree(store.loadSheet(campaign.getId(), "invigorator")).path("hope").path("on").asInt());
        ObjectNode request = splendorRequest("invigorator", "Invigoration", "restore-use", rolled)
                .put("restoreActorId", "limited").put("restoreKey", "card:Second Wind");
        response = featureHttp(cookie, request);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(1, MAPPER.readTree(store.loadSheet(campaign.getId(), "limited")).path("usage").path("card:Second Wind").path("used").asInt());
        assertEquals(400, featureHttp(cookie, request).statusCode());
        assertEquals(source.path("hp"), MAPPER.readTree(store.loadSheet(campaign.getId(), "invigorator")).path("hp"));
    }

    @Test
    void splendorSunlightRollsBothDamageCasesAndResurrectionVaultsPermanently() throws Exception {
        splendorActor("sun", "Stunning Sunlight", "Resurrection");
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            JsonNode roll = splendorRoll(ws, inbox, "sun", "Stunning Sunlight", "2d12+30");
            var response = featureHttp(cookie, splendorRequest("sun", "Stunning Sunlight", "damage", roll).put("amount", 2));
            assertEquals(200, response.statusCode(), response.body());
            assertTrue(dmMessages.stream().anyMatch(m -> m.path("text").asText().contains("3d20+3")
                    && m.path("text").asText().contains("4d20+5") && m.path("text").asText().contains("magic damage")));
            roll = splendorRoll(ws, inbox, "sun", "Resurrection", "2d12+30");
            diceSeed(0);
            response = featureHttp(cookie, splendorRequest("sun", "Resurrection", "resurrect", roll));
            assertEquals(200, response.statusCode(), response.body());
            ObjectNode saved = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "sun"));
            assertTrue(saved.path("resurrectionVaulted").asBoolean());
            assertTrue(saved.path("vault").path(0).path("permanentlyVaulted").asBoolean());
            ObjectNode attempted = saved.deepCopy();
            attempted.withArray("loadout").add(attempted.withArray("vault").remove(0));
            attempted.remove("resurrectionVaulted");
            SplendorActions.preserveSave(attempted, saved);
            assertEquals(1, attempted.path("loadout").size());
            assertTrue(attempted.path("vault").path(0).path("permanentlyVaulted").asBoolean());
        } finally { ws.abort(); }
    }

    @Test
    void splendorSecondWindSharesCardAndAttackLimitAndOnlyHopeCanTargetAllies() throws Exception {
        splendorActor("wind", "Second Wind");
        splendorActor("wind-ally");
        String cookie = cookie();
        ObjectNode card = splendorRequest("wind", "Second Wind", "use", null).put("choiceId", "hp");
        var response = featureHttp(cookie, card);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(2, MAPPER.readTree(store.loadSheet(campaign.getId(), "wind")).path("hp").path("on").asInt());
        assertEquals(400, featureHttp(cookie, card).statusCode());
        ObjectNode reset = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "wind"));
        reset.withObject("/usage").withObject("/card:Second Wind").put("used", 0);
        store.saveSheet(campaign.getId(), "wind", reset.toString());
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            long seed = 0;
            while (true) {
                var random = new java.util.Random(seed);
                if (random.nextInt(12) > random.nextInt(12)) break;
                seed++;
            }
            diceSeed(seed);
            ws.sendText("{\"type\":\"roll\",\"kind\":\"duality\",\"expression\":\"2d12+30\",\"difficulty\":16,\"source\":\"action,attack\",\"actorId\":\"wind\"}", true).join();
            JsonNode roll = inbox.next("roll");
            ObjectNode request = splendorRequest("wind", "Second Wind", "use", roll).put("choiceId", "hp").put("targetActorId", "wind-ally");
            response = featureHttp(cookie, request);
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(2, MAPPER.readTree(store.loadSheet(campaign.getId(), "wind-ally")).path("hp").path("on").asInt());
            assertEquals(400, featureHttp(cookie, request).statusCode());
            assertEquals(400, featureHttp(cookie, card).statusCode());
        } finally { ws.abort(); }
    }

    @Test
    void splendorSecondWindReportsAllyStressInTitleCase() throws Exception {
        splendorActor("wind", "Second Wind");
        ObjectNode ally = splendorActor("wind-ally");
        ally.withObject("/stress").put("on", 4);
        store.saveSheet(campaign.getId(), "wind-ally", ally.toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            long seed = 0;
            while (true) {
                var random = new java.util.Random(seed);
                if (random.nextInt(12) > random.nextInt(12)) break;
                seed++;
            }
            diceSeed(seed);
            ws.sendText("{\"type\":\"roll\",\"kind\":\"duality\",\"expression\":\"2d12+30\",\"difficulty\":16,\"source\":\"action,attack\",\"actorId\":\"wind\"}", true).join();
            JsonNode roll = inbox.next("roll");
            var response = featureHttp(cookie, splendorRequest("wind", "Second Wind", "use", roll)
                    .put("choiceId", "stress").put("targetActorId", "wind-ally"));
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(1, MAPPER.readTree(store.loadSheet(campaign.getId(), "wind-ally")).path("stress").path("on").asInt());
            assertTrue(dmMessages.stream().anyMatch(message -> message.toString().contains("wind-ally: cleared 3 Stress.")));
            assertFalse(dmMessages.stream().anyMatch(message -> message.toString().contains("STRESS")));
        } finally { ws.abort(); }
    }

    @Test
    void splendorSecondWindAcceptsHiddenDifficultyButRejectsKnownFailedAttack() throws Exception {
        splendorActor("hidden-wind", "Second Wind");
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            long seed = 0;
            while (true) {
                var random = new java.util.Random(seed);
                if (random.nextInt(12) != random.nextInt(12)) break;
                seed++;
            }
            diceSeed(seed);
            ws.sendText("{\"type\":\"roll\",\"kind\":\"duality\",\"expression\":\"2d12\",\"difficulty\":100,\"source\":\"action,attack\",\"actorId\":\"hidden-wind\"}", true).join();
            JsonNode failed = inbox.next("roll");
            ObjectNode request = splendorRequest("hidden-wind", "Second Wind", "use", failed).put("choiceId", "hp");
            assertEquals(400, featureHttp(cookie, request).statusCode());
            assertEquals(3, MAPPER.readTree(store.loadSheet(campaign.getId(), "hidden-wind")).path("hp").path("on").asInt());
            diceSeed(seed);
            ws.sendText("{\"type\":\"roll\",\"kind\":\"duality\",\"expression\":\"2d12\",\"source\":\"action,attack\",\"actorId\":\"hidden-wind\"}", true).join();
            JsonNode hidden = inbox.next("roll");
            assertFalse(hidden.has("difficulty"));
            request.set("roll", splendorRequest("hidden-wind", "Second Wind", "use", hidden).path("roll"));
            var response = featureHttp(cookie, request);
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(2, MAPPER.readTree(store.loadSheet(campaign.getId(), "hidden-wind")).path("hp").path("on").asInt());
            assertEquals(400, featureHttp(cookie, request).statusCode());
        } finally { ws.abort(); }
    }

    @Test
    void splendorReassuranceRerollsOtherPlayersDiceOnHostAndPreservesModifiers() throws Exception {
        splendorActor("support", "Reassurance");
        splendorActor("support-ally");
        String ally = allyCookie("reassured-player");
        campaign.actor("support-ally").setOwnerId("reassured-player");
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(ally, inbox);
        try {
            ws.sendText("{\"type\":\"roll\",\"kind\":\"duality\",\"expression\":\"2d12+7\",\"difficulty\":16,\"source\":\"action,attack\",\"actorId\":\"support-ally\"}", true).join();
            JsonNode roll = inbox.next("roll");
            ObjectNode request = splendorRequest("support", "Reassurance", "use", roll);
            var response = featureHttp(cookie, request);
            assertEquals(200, response.statusCode(), response.body());
            JsonNode updated = dmMessages.stream().filter(m -> m.path("type").asText().equals("rollUpdate")).findFirst().orElseThrow();
            assertEquals(7, updated.path("total").asInt() - updated.path("rolls").path(0).asInt() - updated.path("rolls").path(1).asInt());
            JsonNode persisted = store.historyEntry(campaign.getId(), roll.path("id").asLong(), roll.path("ts").asLong());
            assertEquals(16, persisted.path("difficulty").asInt());
            assertEquals("support-ally", persisted.path("actorId").asText());
            assertEquals(1, MAPPER.readTree(store.loadSheet(campaign.getId(), "support")).path("usage").path("card:Reassurance").path("used").asInt());
            assertEquals(400, featureHttp(cookie, request).statusCode());
        } finally { ws.abort(); }
    }

    @Test
    void splendorSmiteDoublesAllDamageIncludingHostBonusesAndAttributesExtraSeparately() throws Exception {
        splendorActor("smiter", "Smite");
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie(), inbox);
        try {
            ObjectNode input = MAPPER.createObjectNode().put("type", "roll").put("actorId", "smiter")
                    .put("expression", "(2d6+3+8+1d4+2)*2").put("source", "damage");
            var parts = input.putArray("contributions");
            parts.addObject().put("label", "Weapon").put("expression", "(2d6+3)*2")
                    .putArray("notes").addObject().put("label", "Smite").put("impact", "Double the rolled damage total");
            parts.addObject().put("label", "Critical").put("expression", "(8)*2");
            parts.addObject().put("label", "Other bonus").put("expression", "(1d4+2)*2");
            input.putObject("damageRules").put("comboDie", 4).put("matchingDice", true);
            ws.sendText(input.toString(), true).join();
            JsonNode roll = inbox.next("roll");
            int base = 0;
            JsonNode smite = null;
            for (JsonNode part : roll.path("breakdown"))
                if ("Smite".equals(part.path("label").asText())) smite = part;
                else base += part.path("total").asInt();
            assertNotNull(smite);
            assertEquals(base, smite.path("total").asInt());
            assertEquals(base * 2, roll.path("total").asInt());
            assertEquals(8, roll.path("breakdown").path(1).path("total").asInt());
            assertEquals(roll.path("rolls").path(0).asInt() + roll.path("rolls").path(1).asInt() + 3,
                    roll.path("breakdown").path(0).path("total").asInt());
        } finally { ws.abort(); }
    }

    @Test
    void splendorSmiteRecognizesBrowserLabelAndKeepsEachBonusUndoubled() throws Exception {
        splendorActor("smiter", "Smite");
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie(), inbox);
        try {
            long seed = 0;
            while (true) {
                var random = new java.util.Random(seed);
                if (random.nextInt(6) == random.nextInt(6)) break;
                seed++;
            }
            diceSeed(seed);
            ObjectNode input = MAPPER.createObjectNode().put("type", "roll").put("actorId", "smiter")
                    .put("expression", "(2d6+3+8+1d4+2+0)*2").put("source", "damage,magicdamage");
            var parts = input.putArray("contributions");
            parts.addObject().put("label", "Cutlass damage (Proficiency 2)").put("expression", "(2d6)*2");
            parts.addObject().put("label", "Cutlass damage bonus").put("expression", "(+3)*2");
            parts.addObject().put("label", "Critical hit (maximum dice)").put("expression", "(8)*2");
            parts.addObject().put("label", "Other bonus").put("expression", "(1d4+2)*2");
            parts.addObject().put("label", "Smite: double weapon damage (magic)").put("expression", "(0)*2");
            for (JsonNode part : parts)
                ((ObjectNode) part).putArray("notes").addObject().put("label", "Smite: double weapon damage (magic)")
                        .put("impact", "Double the rolled damage total");
            input.putObject("damageRules").put("matchingDice", true);
            ws.sendText(input.toString(), true).join();
            JsonNode roll = inbox.next("roll");
            JsonNode breakdown = roll.path("breakdown");
            assertEquals(6, breakdown.size(), breakdown.toString());
            assertEquals(roll.path("rolls").path(0).asInt() + roll.path("rolls").path(1).asInt(),
                    breakdown.path(0).path("total").asInt());
            assertEquals(3, breakdown.path(1).path("total").asInt());
            assertEquals(8, breakdown.path(2).path("total").asInt());
            assertEquals(roll.path("rolls").path(2).asInt() + 2, breakdown.path(3).path("total").asInt());
            assertEquals("Sacred Resonance", breakdown.path(4).path("label").asText());
            assertTrue(breakdown.path(4).path("total").asInt() > 0);
            int base = 0;
            for (int i = 0; i < breakdown.size() - 1; i++) {
                JsonNode part = breakdown.path(i);
                base += part.path("total").asInt();
                assertFalse(part.path("expression").asText().contains("*2"));
                assertTrue(part.path("notes").isMissingNode() || part.path("notes").findValuesAsText("label").stream()
                        .noneMatch(label -> label.startsWith("Smite")));
            }
            assertEquals("Smite", breakdown.path(5).path("label").asText());
            assertEquals(base, breakdown.path(5).path("total").asInt());
            assertEquals(base * 2, roll.path("total").asInt());
        } finally { ws.abort(); }
    }

    @Test
    void splendorZoneClaimsShareCounterAndExpireAfterSixDamageUses() throws Exception {
        splendorActor("zoner", "Zone of Protection");
        String ally = allyCookie("zone-player");
        ObjectNode recipient = splendorActor("protected");
        campaign.actor("protected").setOwnerId("zone-player");
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            JsonNode roll = splendorRoll(ws, inbox, "zoner", "Zone of Protection", "2d12+30");
            var response = featureHttp(cookie, splendorRequest("zoner", "Zone of Protection", "activate", roll));
            assertEquals(200, response.statusCode(), response.body());
            JsonNode invitation = dmMessages.stream().filter(m -> m.path("actionBenefits").path("options").path(0).path("id").asText().equals("join"))
                    .findFirst().orElseThrow();
            response = featureHttp(cookie, benefitRequest("zoner", invitation, "join"));
            assertEquals(200, response.statusCode(), response.body());
            response = featureHttp(ally, benefitRequest("protected", invitation, "join"));
            assertEquals(200, response.statusCode(), response.body());
            for (int value = 1; value <= 6; value++) {
                ObjectNode before = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "protected"));
                JsonNode effect = before.path("effects").path(0);
                assertEquals(value, effect.path("mitigation").path(0).path("reduce").asInt());
                JsonNode casterBefore = MAPPER.readTree(store.loadSheet(campaign.getId(), "zoner"));
                assertTrue(java.util.stream.StreamSupport.stream(casterBefore.path("effects").spliterator(), false)
                        .anyMatch(buff -> buff.path("protectionZone").asBoolean()
                                && buff.path("mitigation").path(0).path("reduce").asInt() == effect.path("mitigation").path(0).path("reduce").asInt()));
                ObjectNode body = MAPPER.createObjectNode().put("actorId", "protected");
                ObjectNode damage = body.putObject("incomingDamage").put("report", "Protected damage");
                damage.set("sheet", before.deepCopy());
                ObjectNode input = damage.putObject("input").put("damage", 12).put("type", "physical").put("armor", false);
                String key = "effect:" + effect.path("id").asText();
                input.putArray("selected").add(key + "/protection");
                damage.putObject("result").put("armorUsed", 0).put("armorMarked", 0).put("hp", 0).put("final", 0)
                        .putArray("applied").addObject().put("key", key).put("id", "protection");
                response = featureHttp(ally, body);
                assertEquals(200, response.statusCode(), response.body());
                assertEquals(400, featureHttp(ally, body).statusCode(), "Stale shared damage cannot be replayed.");
                if (value < 6) {
                    JsonNode caster = MAPPER.readTree(store.loadSheet(campaign.getId(), "zoner"));
                    assertEquals(value + 1, caster.path("resources").path("protection").asInt());
                }
            }
            assertTrue(MAPPER.readTree(store.loadSheet(campaign.getId(), "protected")).path("effects").isEmpty());
            assertEquals(0, MAPPER.readTree(store.loadSheet(campaign.getId(), "zoner")).path("resources").path("protection").asInt());
            assertTrue(MAPPER.readTree(store.loadSheet(campaign.getId(), "zoner")).path("effects").isEmpty());
        } finally { ws.abort(); }
    }

    private ObjectNode performanceSheet() throws Exception {
        return (ObjectNode) MAPPER.readTree("""
                {"name":"Hero","featureRevision":0,"notes":"Keep me","usage":{},"resources":{},
                 "hope":{"on":3,"slots":6},"hp":{"on":0,"slots":6},"stress":{"on":0,"slots":6},
                 "effects":[],"features":[{"name":"Check","automation":{"actions":[
                   {"id":"check","label":"Check","check":{"dice":"1d6","unless":0,"mark":"stress"}}]}}]}
                """);
    }

    private HttpResponse<String> sheetRead(String cookie, String actorId) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/sheet/" + actorId))
                .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> patchSheet(String cookie, String actorId, JsonNode patch, String version) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/sheet/" + actorId))
                .header("Cookie", cookie).header("Content-Type", "application/json").header("X-DMMT-Sheet-Patch", "1");
        if (version != null) request.header("If-Match", version);
        return http.send(request.PUT(HttpRequest.BodyPublishers.ofString(patch.toString())).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void featureRepresentationsAreOptionalOwnedCommittedAndVersioned() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("performance").name("Hero").ownerId(playerId()).testingCharacter(true).build());
        campaign.getActors().add(Campaign.Actor.builder().id("other-performance").name("Other").ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), "performance", performanceSheet().toString());
        store.saveSheet(campaign.getId(), "other-performance", performanceSheet().toString());
        String cookie = cookie();
        ObjectNode action = MAPPER.createObjectNode().put("actorId", "performance").put("key", "feat:Check").put("actionId", "check");
        HttpResponse<String> legacy = featureHttp(cookie, action);
        assertEquals(200, legacy.statusCode(), legacy.body());
        assertEquals(MAPPER.readTree("{\"ok\":true}"), MAPPER.readTree(legacy.body()));
        Inbox inbox = new Inbox();
        WebSocket ws = http.newWebSocketBuilder().header("Cookie", cookie)
                .buildAsync(URI.create("ws://localhost:" + port + "/ws?sheet-sync=1"), inbox).join();
        try {
            JsonNode hello = inbox.next("hello");
            assertTrue(hello.path("sheetActionResponses").asBoolean());
            assertTrue(hello.path("sheetPatches").asBoolean());
            var response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/feature"))
                    .header("Cookie", cookie).header("X-DMMT-Sheet-Response", "1").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(action.toString())).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            JsonNode represented = MAPPER.readTree(response.body()).path("sheetState");
            assertEquals(4, represented.size());
            assertEquals("performance", represented.path("actorId").asText());
            assertTrue(represented.path("testingCharacter").asBoolean());
            assertEquals(2, represented.path("sheet").path("stress").path("on").asInt());
            JsonNode push = inbox.next("sheetChanged");
            assertEquals(represented.path("version"), push.path("version"));
            JsonNode read = MAPPER.readTree(sheetRead(cookie, "performance").body());
            assertEquals(read.path("version"), represented.path("version"));
            assertEquals(read.path("sheet"), represented.path("sheet"));
            String grant = URI.create(server.dmSheetUrl("performance")).getFragment();
            var dmAction = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/feature"))
                    .header("Authorization", "Bearer " + grant).header("X-DMMT-Sheet-Response", "1")
                    .header("Content-Type", "application/json");
            response = http.send(dmAction.POST(HttpRequest.BodyPublishers.ofString(action.toString())).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            assertEquals("performance", MAPPER.readTree(response.body()).path("sheetState").path("actorId").asText());
            response = http.send(dmAction.POST(HttpRequest.BodyPublishers.ofString(action.put("actorId", "other-performance").toString())).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(403, response.statusCode());
            assertFalse(MAPPER.readTree(response.body()).has("sheetState"));
            String ally = allyCookie("performance-outsider");
            response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/feature"))
                    .header("Cookie", ally).header("X-DMMT-Sheet-Response", "1")
                    .POST(HttpRequest.BodyPublishers.ofString(action.toString())).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, response.statusCode());
            assertFalse(MAPPER.readTree(response.body()).has("sheetState"));
        } finally {
            ws.abort();
        }
    }

    @Test
    void compactSheetSavesRequireCurrentVersionAndRetainAllNormalValidation() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("performance").name("Hero").ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), "performance", performanceSheet().toString());
        String cookie = cookie();
        JsonNode baseline = MAPPER.readTree(sheetRead(cookie, "performance").body());
        ObjectNode patch = MAPPER.createObjectNode();
        patch.putObject("fields").put("notes", "Compact edit");
        patch.putArray("removed").add("usage");
        assertEquals(428, patchSheet(cookie, "performance", patch, null).statusCode());
        assertEquals(400, patchSheet(cookie, "performance", patch, "invalid").statusCode());
        var saved = patchSheet(cookie, "performance", patch, baseline.path("version").asText());
        assertEquals(200, saved.statusCode(), saved.body());
        JsonNode current = MAPPER.readTree(saved.body());
        assertEquals("Compact edit", current.path("sheet").path("notes").asText());
        assertEquals(baseline.path("sheet").path("features"), current.path("sheet").path("features"));
        assertFalse(current.path("sheet").has("usage"));
        assertEquals(1, server.sheetDiagnostics().normalizations(), "Saving must not normalize again to construct its response.");
        assertTrue(server.sheetDiagnostics().hydrationCacheHits() > 0);
        var stale = patchSheet(cookie, "performance", patch, baseline.path("version").asText());
        assertEquals(409, stale.statusCode());
        assertEquals(current.path("version"), MAPPER.readTree(stale.body()).path("version"));
        ObjectNode invalid = patch.deepCopy();
        invalid.withObject("/fields").put("featureRevision", 999);
        assertEquals(409, patchSheet(cookie, "performance", invalid, current.path("version").asText()).statusCode());
        invalid = patch.deepCopy();
        invalid.withObject("/fields").put("name", "");
        assertEquals(400, patchSheet(cookie, "performance", invalid, current.path("version").asText()).statusCode());
        invalid = patch.deepCopy();
        invalid.withObject("/fields").put("__proto__", "bad");
        assertEquals(400, patchSheet(cookie, "performance", invalid, current.path("version").asText()).statusCode());
        invalid = patch.deepCopy();
        invalid.withArray("removed").add("notes");
        assertEquals(400, patchSheet(cookie, "performance", invalid, current.path("version").asText()).statusCode());
        assertEquals(current.path("version"), MAPPER.readTree(sheetRead(cookie, "performance").body()).path("version"));
    }

    @Test
    void compactSavesBoundReconstructedSizeAndDetectIndependentSameRevisionWrites() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("performance").name("Hero").ownerId(playerId()).build());
        ObjectNode initial = performanceSheet().put("largeNotes", "a".repeat(280_000));
        store.saveSheet(campaign.getId(), "performance", initial.toString());
        String cookie = cookie();
        JsonNode baseline = MAPPER.readTree(sheetRead(cookie, "performance").body());
        ObjectNode patch = MAPPER.createObjectNode();
        patch.putObject("fields").put("otherNotes", "b".repeat(280_000));
        patch.putArray("removed");
        var oversized = patchSheet(cookie, "performance", patch, baseline.path("version").asText());
        assertEquals(413, oversized.statusCode(), oversized.body());
        assertEquals(baseline.path("version"), MAPPER.readTree(sheetRead(cookie, "performance").body()).path("version"));
        ObjectNode independent = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "performance"));
        independent.withObject("/stress").put("on", 2);
        java.nio.file.Files.writeString(store.root().resolve(campaign.getId()).resolve("sheets").resolve("performance.json"),
                independent.toString());
        patch.withObject("/fields").removeAll().put("notes", "Stale edit");
        var stale = patchSheet(cookie, "performance", patch, baseline.path("version").asText());
        assertEquals(409, stale.statusCode(), stale.body());
        JsonNode fresh = MAPPER.readTree(stale.body());
        assertEquals(2, fresh.path("sheet").path("stress").path("on").asInt());
        assertEquals(baseline.path("sheet").path("featureRevision"), fresh.path("sheet").path("featureRevision"));
        assertEquals("Keep me", fresh.path("sheet").path("notes").asText());
    }

    @Test
    void catalogHttpRevalidatesContentAndNeverReturnsAuthenticated304ToLoggedOutClients() throws Exception {
        dmCompendiumData();
        String cookie = cookie();
        String uri = "http://localhost:" + port + "/api/compendium/items";
        var first = http.send(HttpRequest.newBuilder(URI.create(uri)).header("Cookie", cookie).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, first.statusCode(), first.body());
        String etag = first.headers().firstValue("ETag").orElseThrow();
        assertEquals("private, no-cache", first.headers().firstValue("Cache-Control").orElseThrow());
        long parses = server.catalogDiagnostics().parses();
        var same = http.send(HttpRequest.newBuilder(URI.create(uri)).header("Cookie", cookie)
                .header("If-None-Match", etag).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(304, same.statusCode(), same.body());
        assertTrue(same.body().isEmpty());
        assertEquals(parses, server.catalogDiagnostics().parses(), "Unchanged files must not be reparsed.");
        assertEquals(401, http.send(HttpRequest.newBuilder(URI.create(uri)).header("If-None-Match", etag).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        Path file = compendiumDir.resolve("items.json");
        var timestamp = java.nio.file.Files.getLastModifiedTime(file);
        java.nio.file.Files.writeString(file, java.nio.file.Files.readString(file).replace("Light.", "Flame."));
        java.nio.file.Files.setLastModifiedTime(file, timestamp);
        var edited = http.send(HttpRequest.newBuilder(URI.create(uri)).header("Cookie", cookie)
                .header("If-None-Match", etag).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, edited.statusCode(), edited.body());
        assertNotNull(edited.headers().firstValue("ETag").orElse(null));
        assertFalse(etag.equals(edited.headers().firstValue("ETag").orElseThrow()));
        assertTrue(edited.body().contains("Flame."));
        String indexUri = "http://localhost:" + port + "/api/compendium";
        var index = http.send(HttpRequest.newBuilder(URI.create(indexUri)).header("Cookie", cookie).build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(MAPPER.readTree(index.body()).path("categories").get(0).has("revision"));
        assertEquals(304, http.send(HttpRequest.newBuilder(URI.create(indexUri)).header("Cookie", cookie)
                .header("If-None-Match", index.headers().firstValue("ETag").orElseThrow()).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void catalogsAndSheetsActuallyCompressForGzipHttpClients() throws Exception {
        dmCompendiumData();
        ObjectNode catalog = (ObjectNode) MAPPER.readTree(compendiumDir.resolve("items.json").toFile());
        ((ObjectNode) catalog.path("entries").get(0)).put("text", "Long catalog rules. ".repeat(600));
        java.nio.file.Files.writeString(compendiumDir.resolve("items.json"), catalog.toString());
        ObjectNode sheet = performanceSheet().put("notes", "Long character notes. ".repeat(600));
        store.saveSheet(campaign.getId(), "target", sheet.toString());
        String cookie = cookie();
        for (String endpoint : List.of("/api/compendium/items", "/api/sheet/target")) {
            var response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + endpoint))
                    .header("Cookie", cookie).header("Accept-Encoding", "gzip").build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            assertEquals("gzip", response.headers().firstValue("Content-Encoding").orElse("identity"), endpoint);
            try (var gzip = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(response.body()))) {
                byte[] plain = gzip.readAllBytes();
                assertTrue(response.body().length < plain.length / 2);
                assertTrue(MAPPER.readTree(plain).isObject());
            }
        }
    }

    @Test
    void fullSheetClientsSkipRedundantPartialPayloadsWhileLegacyAndDmScopesRemainCorrect() throws Exception {
        Campaign.Actor actor = Campaign.Actor.builder().id("performance").name("Hero").ownerId(playerId()).build();
        campaign.getActors().add(actor);
        campaign.getActors().add(Campaign.Actor.builder().id("other-performance").name("Other").ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), actor.getId(), performanceSheet().toString());
        store.saveSheet(campaign.getId(), "other-performance", performanceSheet().toString());
        String cookie = cookie();
        Inbox full = new Inbox(), legacy = new Inbox(), scopedDm = new Inbox();
        WebSocket fullWs = http.newWebSocketBuilder().header("Cookie", cookie)
                .buildAsync(URI.create("ws://localhost:" + port + "/ws?sheet-sync=1"), full).join();
        WebSocket legacyWs = connect(cookie, legacy);
        String grant = URI.create(server.dmSheetUrl("other-performance")).getFragment();
        WebSocket dmWs = http.newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws?dm-sheet=" + grant), scopedDm).join();
        try {
            full.next("hello"); legacy.next("hello"); scopedDm.next("hello");
            ObjectNode action = MAPPER.createObjectNode().put("actorId", actor.getId()).put("key", "feat:Check").put("actionId", "check");
            assertEquals(200, featureHttp(cookie, action).statusCode());
            var inventoryChanged = OnlineSessionServer.class.getDeclaredMethod("inventoryChanged", Campaign.Actor.class, ObjectNode.class);
            inventoryChanged.setAccessible(true);
            inventoryChanged.invoke(server, actor, MAPPER.readTree(store.loadSheet(campaign.getId(), actor.getId())));
            server.dmChat("sheet-performance-barrier", null);
            List<String> fullTypes = sheetMessagesBeforeBarrier(full);
            List<String> legacyTypes = sheetMessagesBeforeBarrier(legacy);
            List<String> scopedTypes = sheetMessagesBeforeBarrier(scopedDm);
            assertTrue(fullTypes.contains("sheetChanged"));
            assertTrue(fullTypes.contains("cardStock"));
            assertFalse(fullTypes.contains("sheetFeature"));
            assertFalse(fullTypes.contains("sheetInventory"));
            assertTrue(legacyTypes.contains("sheetFeature"));
            assertTrue(legacyTypes.contains("sheetInventory"));
            assertFalse(scopedTypes.contains("sheetFeature"));
            assertFalse(scopedTypes.contains("sheetInventory"));
            assertFalse(scopedTypes.contains("sheetChanged"));
        } finally {
            fullWs.abort(); legacyWs.abort(); dmWs.abort();
        }
    }

    private List<String> sheetMessagesBeforeBarrier(Inbox inbox) throws Exception {
        List<String> types = new ArrayList<>();
        long end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end) {
            JsonNode message = inbox.messages.poll(200, TimeUnit.MILLISECONDS);
            if (message == null) continue;
            if ("sheet-performance-barrier".equals(message.path("text").asText())) return types;
            types.add(message.path("type").asText());
        }
        throw new AssertionError("No sheet synchronization barrier");
    }

    private ObjectNode bloodSheet(String... ids) throws Exception {
        compendiumDir = Path.of("dmmap-daggerheart");
        ObjectNode sheet = witchHostSheet();
        sheet.putArray("features");
        var cards = sheet.putArray("loadout");
        for (JsonNode card : MAPPER.readTree(compendiumDir.resolve("domain-cards.json").toFile()).path("entries"))
            if (List.of(ids).contains(card.path("id").asText())) cards.add(card.deepCopy());
        return sheet;
    }

    @Test
    void lifebloodHostCreationPaysHpAndRecastReplacesTheUniqueItem() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("blood").name("Blood").ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), "blood", bloodSheet("lifeblood-talisman").toString());
        ObjectNode request = MAPPER.createObjectNode().put("actorId", "blood")
                .put("key", "card:Lifeblood Talisman").put("actionId", "use");
        String cookie = cookie();
        var response = featureHttp(cookie, request);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode first = MAPPER.readTree(store.loadSheet(campaign.getId(), "blood"));
        assertEquals(1, first.path("hp").path("on").asInt());
        assertEquals(1, first.path("inventory").size());
        assertEquals(6, first.path("hope").path("on").asInt());
        response = featureHttp(cookie, request);
        assertEquals(200, response.statusCode(), response.body());
        ObjectNode second = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "blood"));
        assertEquals(2, second.path("hp").path("on").asInt());
        assertEquals(1, second.path("inventory").size());
        assertFalse(first.path("inventory").get(0).path("witchTalisman")
                .equals(second.path("inventory").get(0).path("witchTalisman")));
        second.withObject("/hp").put("on", 0);
        response = saveDeathSheet(cookie, "blood", second);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(MAPPER.readTree(store.loadSheet(campaign.getId(), "blood")).path("inventory").isEmpty());
    }

    @Test
    void vitalWardClaimsAreBoundToOneCircleAndExpireForAlliesOnRecastOrSourceRemoval() throws Exception {
        String ally = allyCookie("ward-player");
        campaign.getActors().add(Campaign.Actor.builder().id("ward").name("Caster").ownerId(playerId()).build());
        campaign.getActors().add(Campaign.Actor.builder().id("ward-ally").name("Ally").ownerId("ward-player").build());
        store.saveSheet(campaign.getId(), "ward", bloodSheet("vital-ward").toString());
        store.saveSheet(campaign.getId(), "ward-ally", bloodSheet().toString());
        String cookie = cookie();
        ObjectNode action = MAPPER.createObjectNode().put("actorId", "ward").put("key", "card:Vital Ward").put("actionId", "physical");
        var response = featureHttp(cookie, action);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode first = dmMessages.stream().filter(m -> m.has("actionBenefits")).toList().getLast();
        response = featureHttp(ally, benefitRequest("ward-ally", first, "enter"));
        assertEquals(200, response.statusCode(), response.body());
        JsonNode buff = MAPPER.readTree(store.loadSheet(campaign.getId(), "ward-ally")).path("effects").get(0);
        assertEquals("physical", buff.path("mitigation").get(0).path("when").path("type").asText());
        action.put("actionId", "magic");
        response = featureHttp(cookie, action);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(MAPPER.readTree(store.loadSheet(campaign.getId(), "ward-ally")).path("effects").isEmpty());
        assertEquals(400, featureHttp(ally, benefitRequest("ward-ally", first, "enter")).statusCode());
        JsonNode current = dmMessages.stream().filter(m -> m.has("actionBenefits")).toList().getLast();
        response = featureHttp(ally, benefitRequest("ward-ally", current, "enter"));
        assertEquals(200, response.statusCode(), response.body());
        ObjectNode source = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "ward"));
        source.putArray("effects");
        response = saveDeathSheet(cookie, "ward", source);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(MAPPER.readTree(store.loadSheet(campaign.getId(), "ward-ally")).path("effects").isEmpty());
    }

    @Test
    void bloodbathRollPaysHopeAndRestUseAtomicallyAndAlliesPayStressForOneRecovery() throws Exception {
        String ally = allyCookie("bath-player");
        campaign.getActors().add(Campaign.Actor.builder().id("bath").name("Caster").ownerId(playerId()).build());
        campaign.getActors().add(Campaign.Actor.builder().id("bath-ally").name("Ally").ownerId("bath-player").build());
        store.saveSheet(campaign.getId(), "bath", bloodSheet("bloodbath").toString());
        ObjectNode recipient = bloodSheet();
        recipient.withObject("/hp").put("on", 2);
        recipient.withObject("/stress").put("on", 6);
        store.saveSheet(campaign.getId(), "bath-ally", recipient.toString());
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie(), inbox);
        try {
            assertTrue(inbox.next("hello").path("bloodAutomation").asBoolean());
            String cast = """
                    {"type":"roll","kind":"duality","expression":"2d12","actorId":"bath",
                     "source":"action,spellcast,card:Bloodbath,featureAction:card:Bloodbath:use"}
                    """;
            ws.sendText(cast, true).join();
            JsonNode roll = inbox.next("roll");
            assertTrue(roll.has("actionBenefits"));
            JsonNode paid = MAPPER.readTree(store.loadSheet(campaign.getId(), "bath"));
            assertEquals(5, paid.path("hope").path("on").asInt());
            assertEquals(1, paid.path("usage").path("card:Bloodbath").path("used").asInt());
            ObjectNode claim = benefitRequest("bath-ally", roll, "heal");
            assertEquals(400, featureHttp(ally, claim).statusCode());
            assertEquals(2, MAPPER.readTree(store.loadSheet(campaign.getId(), "bath-ally")).path("hp").path("on").asInt());
            recipient.withObject("/stress").put("on", 1);
            store.saveSheet(campaign.getId(), "bath-ally", recipient.toString());
            var response = featureHttp(ally, claim);
            assertEquals(200, response.statusCode(), response.body());
            JsonNode healed = MAPPER.readTree(store.loadSheet(campaign.getId(), "bath-ally"));
            assertEquals(1, healed.path("hp").path("on").asInt());
            assertEquals(2, healed.path("stress").path("on").asInt());
            assertEquals(400, featureHttp(ally, claim).statusCode());
            ws.sendText(cast, true).join();
            inbox.next("error");
            assertEquals(5, MAPPER.readTree(store.loadSheet(campaign.getId(), "bath")).path("hope").path("on").asInt());
        } finally { ws.abort(); }
    }

    @Test
    void sanguineFeastChoicesShareOnePaymentAndRefundOnlyTheirCasterOnce() throws Exception {
        String ally = allyCookie("feast-player");
        campaign.getActors().add(Campaign.Actor.builder().id("feast-ally").name("Ally").ownerId("feast-player").build());
        store.saveSheet(campaign.getId(), "feast-ally", bloodSheet().toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            for (int hp = 1; hp <= 3; hp++) {
                String id = "feast-" + hp;
                campaign.getActors().add(Campaign.Actor.builder().id(id).name("Caster").ownerId(playerId()).build());
                store.saveSheet(campaign.getId(), id, bloodSheet("sanguine-feast").toString());
                ObjectNode cast = MAPPER.createObjectNode().put("type", "roll").put("kind", "duality")
                        .put("expression", "2d12+100").put("actorId", id).put("source", "action,spellcast,card:Sanguine Feast")
                        .put("difficulty", 1);
                ws.sendText(cast.toString(), true).join();
                JsonNode roll = inbox.next("roll");
                ObjectNode action = MAPPER.createObjectNode().put("actorId", id).put("key", "card:Sanguine Feast")
                        .put("actionId", List.of("one", "two", "three").get(hp - 1));
                action.putObject("roll").setAll((ObjectNode) MAPPER.createObjectNode().put("id", roll.path("id").asLong()).put("ts", roll.path("ts").asLong()));
                var response = featureHttp(cookie, action);
                assertEquals(200, response.statusCode(), response.body());
                JsonNode paid = MAPPER.readTree(store.loadSheet(campaign.getId(), id));
                assertEquals(hp, paid.path("hp").path("on").asInt());
                assertEquals(4, paid.path("hope").path("on").asInt());
                action.put("actionId", hp == 1 ? "three" : "one");
                assertEquals(400, featureHttp(cookie, action).statusCode());
                JsonNode report = dmMessages.stream().filter(m -> m.has("actionBenefits")).toList().getLast();
                assertTrue(report.path("text").asText().contains("target marks " + hp * 2 + " HP"));
                assertEquals(400, featureHttp(ally, benefitRequest("feast-ally", report, "refund")).statusCode());
                response = featureHttp(cookie, benefitRequest(id, report, "refund"));
                assertEquals(200, response.statusCode(), response.body());
                assertEquals(0, MAPPER.readTree(store.loadSheet(campaign.getId(), id)).path("hp").path("on").asInt());
                assertEquals(400, featureHttp(cookie, benefitRequest(id, report, "refund")).statusCode());
            }
        } finally { ws.abort(); }
    }

    @Test
    void vitalityMigrationAndEditRemovalAreVersionedAndCannotBeUndoneByStaleSaves() throws Exception {
        var actor = Campaign.Actor.builder().id("vitality-hero").name("Hero").ownerId(playerId()).build();
        campaign.getActors().add(actor);
        ObjectNode initial = witchHostSheet();
        initial.putArray("features");
        initial.putArray("loadout");
        initial.putArray("vault").addObject().put("name", "Vitality");
        initial.putArray("vitalityBenefits").add("hp").add("thresholds");
        initial.withObject("/hp").put("slots", 7).put("total", 12).put("on", 7);
        store.saveSheet(campaign.getId(), actor.getId(), initial.toString());
        String cookie = cookie();
        URI uri = URI.create("http://localhost:" + port + "/api/sheet/" + actor.getId());
        var loaded = http.send(HttpRequest.newBuilder(uri).header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
        JsonNode data = MAPPER.readTree(loaded.body());
        ObjectNode sheet = (ObjectNode) data.path("sheet");
        assertEquals(7, sheet.path("hp").path("slots").asInt());
        assertTrue(sheet.path("effects").get(0).path("vitality").asBoolean());
        ObjectNode stale = sheet.deepCopy();
        sheet.putArray("effects");
        var save = HttpRequest.newBuilder(uri).header("Cookie", cookie).header("Content-Type", "application/json");
        assertEquals(400, http.send(save.header("If-Match", data.path("version").asText())
                .PUT(HttpRequest.BodyPublishers.ofString(sheet.toString())).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        sheet.withObject("/hp").put("slots", 6);
        sheet.put("vitalityEffectRemoved", true);
        sheet.putObject("vitalityRemoval").put("effectId", "vitality-permanent").put("editMode", true);
        var unversioned = HttpRequest.newBuilder(uri).header("Cookie", cookie).header("Content-Type", "application/json");
        assertEquals(400, http.send(unversioned.PUT(HttpRequest.BodyPublishers.ofString(sheet.toString())).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        var removed = http.send(save.PUT(HttpRequest.BodyPublishers.ofString(sheet.toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, removed.statusCode(), removed.body());
        JsonNode result = MAPPER.readTree(removed.body());
        assertTrue(result.path("sheet").path("vitalityEffectRemoved").asBoolean());
        assertFalse(result.path("sheet").has("vitalityRemoval"));
        assertEquals(6, result.path("sheet").path("hp").path("slots").asInt());
        assertEquals(6, result.path("sheet").path("hp").path("on").asInt());
        assertTrue(result.path("sheet").path("effects").isEmpty());
        var oldSave = http.send(save.PUT(HttpRequest.BodyPublishers.ofString(stale.toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(409, oldSave.statusCode());
        var unversionedStale = http.send(unversioned.PUT(HttpRequest.BodyPublishers.ofString(stale.toString())).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, unversionedStale.statusCode());
        JsonNode persisted = MAPPER.readTree(store.loadSheet(campaign.getId(), actor.getId()));
        assertTrue(persisted.path("vitalityEffectRemoved").asBoolean());
        assertEquals(6, persisted.path("hp").path("slots").asInt());
        assertTrue(persisted.path("effects").isEmpty());
        assertEquals(1, persisted.path("vault").size());
    }

    @Test
    void specialCardsCommitPermanentChoicesCountdownAndLiveFearWithRestLimit() throws Exception {
        compendiumDir = Path.of("dmmap-daggerheart");
        var actor = Campaign.Actor.builder().id("special-hero").name("Hero").ownerId(playerId()).build();
        campaign.getActors().add(actor);
        ObjectNode sheet = witchHostSheet();
        sheet.putArray("features");
        var cards = sheet.putArray("loadout");
        for (JsonNode card : MAPPER.readTree(compendiumDir.resolve("domain-cards.json").toFile()).path("entries"))
            if (List.of("Vitality", "Mass Disguise", "Night Terror").contains(card.path("name").asText())) cards.add(card.deepCopy());
        store.saveSheet(campaign.getId(), actor.getId(), sheet.toString());
        store.save(campaign);
        String cookie = cookie();
        ObjectNode request = MAPPER.createObjectNode().put("actorId", actor.getId()).put("key", "card:Vitality").put("actionId", "choose");
        request.putArray("choices").add("hp").add("hp");
        assertEquals(400, featureHttp(cookie, request).statusCode());
        assertFalse(MAPPER.readTree(store.loadSheet(campaign.getId(), actor.getId())).has("vitalityBenefits"));
        request.putArray("choices").add("hp").add("thresholds");
        int hp = sheet.path("hp").path("slots").asInt();
        var response = featureHttp(cookie, request);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode saved = MAPPER.readTree(store.loadSheet(campaign.getId(), actor.getId()));
        assertEquals(hp + 1, saved.path("hp").path("slots").asInt());
        assertEquals(2, saved.path("vitalityBenefits").size());
        assertEquals("Vitality", saved.path("vault").get(0).path("name").asText());
        assertEquals(400, featureHttp(cookie, request).statusCode());
        request.remove("choices");
        request.put("key", "card:Mass Disguise").put("actionId", "use");
        actor.setDisabled(true);
        assertEquals(403, featureHttp(cookie, request).statusCode());
        actor.setDisabled(false);
        response = featureHttp(cookie, request);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(8, campaign.getCountdowns().getFirst().value());
        assertEquals(8, new CampaignStore(temp.resolve("campaigns")).load(campaign.getId()).getCountdowns().getFirst().value());
        saved = MAPPER.readTree(store.loadSheet(campaign.getId(), actor.getId()));
        assertEquals(sheet.path("stress").path("on").asInt() + 1, saved.path("stress").path("on").asInt());
        campaign.setFear(3);
        store.save(campaign);
        request.put("key", "card:Night Terror").put("amount", 4);
        assertEquals(400, featureHttp(cookie, request).statusCode());
        assertEquals(3, campaign.getFear());
        request.put("amount", 2);
        response = featureHttp(cookie, request);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(1, campaign.getFear());
        assertEquals(1, new CampaignStore(temp.resolve("campaigns")).load(campaign.getId()).getFear());
        assertEquals(400, featureHttp(cookie, request).statusCode());
        saved = MAPPER.readTree(store.loadSheet(campaign.getId(), actor.getId()));
        assertEquals(1, saved.path("usage").path("card:Night Terror").path("used").asInt());
    }

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
    private volatile boolean rotationSupported;
    private volatile boolean portalSupported;
    private volatile boolean mapPingsSupported;
    private final List<String> mapPings = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<String> portals = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile OnlineSessionServer.MoveResult portalResult = OnlineSessionServer.MoveResult.accepted();
    private final List<Double> rotations = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile Path compendiumDir;

    @Test
    void versionedStressSavesReturnAutomaticVulnerabilityAndPreserveOutsideSources() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("stress-hero").name("Hero").ownerId(playerId()).build());
        ObjectNode initial = MAPPER.createObjectNode();
        initial.putObject("stress").put("slots", 6).put("on", 5);
        initial.putArray("effects");
        store.saveSheet(campaign.getId(), "stress-hero", initial.toString());
        String cookie = cookie();
        String uri = "http://localhost:" + port + "/api/sheet/stress-hero";
        var loaded = http.send(HttpRequest.newBuilder(URI.create(uri)).header("Cookie", cookie).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        var data = MAPPER.readTree(loaded.body());
        var sheet = (ObjectNode) data.path("sheet");
        sheet.withObject("/stress").put("on", 6);
        var full = http.send(HttpRequest.newBuilder(URI.create(uri)).header("Cookie", cookie)
                .header("Content-Type", "application/json").header("If-Match", data.path("version").asText())
                .PUT(HttpRequest.BodyPublishers.ofString(sheet.toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, full.statusCode(), full.body());
        var result = MAPPER.readTree(full.body());
        assertTrue(result.path("sheet").path("effects").get(0).path("appliedByFullStress").asBoolean());
        sheet = (ObjectNode) result.path("sheet");
        sheet.withArray("effects").addObject().put("id", "outside").put("name", "Vulnerable").put("kind", "debuff");
        sheet.withObject("/stress").put("on", 5);
        var cleared = http.send(HttpRequest.newBuilder(URI.create(uri)).header("Cookie", cookie)
                .header("Content-Type", "application/json").header("If-Match", result.path("version").asText())
                .PUT(HttpRequest.BodyPublishers.ofString(sheet.toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, cleared.statusCode(), cleared.body());
        var effects = MAPPER.readTree(cleared.body()).path("sheet").path("effects");
        assertEquals(1, effects.size());
        assertEquals("outside", effects.get(0).path("id").asText());
        var stale = http.send(HttpRequest.newBuilder(URI.create(uri)).header("Cookie", cookie)
                .header("Content-Type", "application/json").header("If-Match", data.path("version").asText())
                .PUT(HttpRequest.BodyPublishers.ofString(initial.toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(409, stale.statusCode());
    }

    private HttpResponse<String> preferencesHttp(String cookie, String token, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/preferences"));
        if (cookie != null) request.header("Cookie", cookie);
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body == null) request.GET();
        else request.header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> artworkHttp(String actorId, String cookie, String token, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/actors/" + actorId + "/artwork"));
        if (cookie != null) request.header("Cookie", cookie);
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body == null) request.GET();
        else request.header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void characterTokenArtworkKeepsSourceAndCropAndEnforcesOwnershipAndDisabledActors() throws Exception {
        var actor = Campaign.Actor.builder().id("token-art").name("Actor").ownerId(playerId()).build();
        campaign.getActors().add(actor);
        var other = Campaign.Actor.builder().id("other-art").name("Other").ownerId("other").build();
        campaign.getActors().add(other);
        String cookie = cookie();
        assertEquals(401, artworkHttp(actor.getId(), null, null, null).statusCode());
        assertEquals(403, artworkHttp(other.getId(), cookie, null, null).statusCode());
        var initial = MAPPER.readTree(artworkHttp(actor.getId(), cookie, null, null).body());
        assertEquals(campaign.player(playerId()).getColor(), initial.path("color").asText());
        assertEquals(1, initial.path("sizeTiles").asDouble());
        assertFalse(initial.has("source"));
        assertEquals(400, artworkHttp(actor.getId(), cookie, null, "{\"color\":\"#ff0000\"}").statusCode());
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(8, 4, java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", bytes);
        ObjectNode request = MAPPER.createObjectNode().put("color", "#ff0000").put("sizeTiles", 1.5)
                .put("zoom", 2).put("centerX", .25).put("centerY", .75)
                .put("source", "forged").put("border", false).put("shape", "square")
                .put("image", "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(bytes.toByteArray()));
        var saved = artworkHttp(actor.getId(), cookie, null, request.toString());
        assertEquals(200, saved.statusCode(), saved.body());
        assertFalse(MAPPER.readTree(saved.body()).path("artwork").asText().isBlank());
        var persisted = store.load(campaign.getId()).actor(actor.getId());
        assertEquals(1.5, persisted.getTokenArtwork().getSizeTiles());
        assertEquals(.25, persisted.getTokenArtwork().getCenterX());
        assertFalse("forged".equals(persisted.getTokenArtwork().getSource()));
        var rendered = javax.imageio.ImageIO.read(store.artworkPath(campaign.getId(), actor.getArtwork()).toFile());
        assertEquals(0, rendered.getRGB(0, 0) >>> 24);
        assertEquals(java.awt.Color.RED.getRGB(), rendered.getRGB(128, 2));
        request.remove("image");
        request.put("color", "#0000ff").put("sizeTiles", 2);
        assertEquals(200, artworkHttp(actor.getId(), cookie, null, request.toString()).statusCode());
        assertEquals(persisted.getTokenArtwork().getSource(), actor.getTokenArtwork().getSource());
        var loaded = MAPPER.readTree(artworkHttp(actor.getId(), cookie, null, null).body());
        assertEquals("#0000ff", loaded.path("color").asText());
        assertEquals(2, loaded.path("sizeTiles").asDouble());
        assertFalse(loaded.path("source").asText().isBlank());
        String previous = actor.getArtwork();
        request.put("sizeTiles", .75);
        assertEquals(400, artworkHttp(actor.getId(), cookie, null, request.toString()).statusCode());
        assertEquals(previous, actor.getArtwork());
        actor.setDisabled(true);
        assertEquals(403, artworkHttp(actor.getId(), cookie, null, request.toString()).statusCode());
        String grant = URI.create(server.dmSheetUrl(actor.getId())).getFragment();
        request.put("sizeTiles", 1);
        assertEquals(200, artworkHttp(actor.getId(), null, grant, request.toString()).statusCode());
        assertEquals(403, artworkHttp(other.getId(), null, grant, null).statusCode());
        server.revokeDmSheet("http://127.0.0.1:" + port + "/sheet/" + actor.getId() + "#" + grant);
        assertEquals(403, artworkHttp(actor.getId(), null, grant, null).statusCode());
    }

    @Test
    void domainCardPreferencesAreAuthenticatedValidatedAndPersistedPerAccount() throws Exception {
        assertEquals(401, preferencesHttp(null, null, null).statusCode());
        assertEquals(401, preferencesHttp(null, null, "{\"domainCardView\":\"list\"}").statusCode());
        String cookie = cookie();
        var initial = preferencesHttp(cookie, null, null);
        assertEquals(200, initial.statusCode());
        assertEquals("card", MAPPER.readTree(initial.body()).path("domainCardView").asText());
        assertFalse(MAPPER.readTree(initial.body()).has("passwordHash"));
        Campaign.Player bob = Campaign.Player.builder().name("Bob").build();
        campaign.getPlayers().add(bob);
        for (String body : List.of("{}", "null", "{", "{\"domainCardView\":1}", "{\"domainCardView\":\"grid\"}"))
            assertEquals(400, preferencesHttp(cookie, null, body).statusCode(), body);
        var saved = preferencesHttp(cookie, null, "{\"domainCardView\":\"list\",\"playerId\":\"" + bob.getId() + "\"}");
        assertEquals(200, saved.statusCode(), saved.body());
        assertEquals("list", campaign.player(playerId()).getDomainCardView());
        assertEquals("card", bob.getDomainCardView());
        assertEquals("card", campaign.getDmDomainCardView());
        assertEquals("list", store.load(campaign.getId()).player(playerId()).getDomainCardView());
        assertEquals("list", MAPPER.readTree(preferencesHttp(cookie, null, null).body()).path("domainCardView").asText());
        assertEquals("card", MAPPER.readValue("{\"name\":\"Legacy\"}", Campaign.Player.class).getDomainCardView());
    }

    @Test
    void domainCardPreferencesKeepDmAndPlayerChoicesSeparateAndRejectExpiredGrants() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("preference-sheet").name("Sheet").ownerId(playerId()).build());
        String token = URI.create(server.dmSheetUrl("preference-sheet")).getFragment();
        var saved = preferencesHttp(null, token, "{\"domainCardView\":\"list\"}");
        assertEquals(200, saved.statusCode(), saved.body());
        assertEquals("dm:" + campaign.getId(), MAPPER.readTree(saved.body()).path("userId").asText());
        assertEquals("list", store.load(campaign.getId()).getDmDomainCardView());
        assertEquals("card", campaign.player(playerId()).getDomainCardView());
        assertEquals(403, preferencesHttp(null, "invalid", null).statusCode());
    }

    @Test
    void domainCardPreferenceSaveFailureRestoresThePreviousChoice() throws Exception {
        String cookie = cookie();
        Path file = store.root().resolve(campaign.getId()).resolve("campaign.json");
        java.nio.file.Files.delete(file);
        java.nio.file.Files.createDirectory(file);
        java.nio.file.Files.writeString(file.resolve("block"), "block replacement");
        var saved = preferencesHttp(cookie, null, "{\"domainCardView\":\"list\"}");
        assertEquals(500, saved.statusCode(), saved.body());
        assertEquals("card", campaign.player(playerId()).getDomainCardView());
        assertTrue(MAPPER.readTree(saved.body()).path("error").asText().contains("Could not save"));
    }

    @Test
    void copycatListsUniqueOtherActorLoadoutsChargesRoundedHopeAndExpiresOnSourceVaulting() throws Exception {
        compendiumDir = Path.of("dmmap-daggerheart");
        JsonNode catalog = MAPPER.readTree(compendiumDir.resolve("domain-cards.json").toFile()).path("entries");
        JsonNode definition = java.util.stream.StreamSupport.stream(catalog.spliterator(), false)
                .filter(entry -> "Copycat".equals(entry.path("name").asText())).findFirst().orElseThrow();
        ObjectNode own = (ObjectNode) MAPPER.readTree("""
                {"level":9,"hope":{"on":6,"slots":6},"hp":{"on":0,"slots":6},"stress":{"on":0,"slots":6},
                "traits":[{"name":"Presence","mod":2,"spellcast":true}],"effects":[],"resources":{},"usage":{},"loadout":[],"vault":[]}
                """);
        own.withArray("loadout").add(definition.deepCopy());
        ObjectNode other = own.deepCopy();
        other.putArray("loadout").addObject().put("name", "Flight");
        other.withArray("loadout").addObject().put("name", "Armorer");
        ObjectNode duplicate = other.deepCopy();
        duplicate.putArray("loadout").addObject().put("name", "Flight");
        for (String id : List.of("copycat", "source", "duplicate"))
            campaign.getActors().add(Campaign.Actor.builder().id(id).name(id).ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), "copycat", own.toString());
        store.saveSheet(campaign.getId(), "source", other.toString());
        store.saveSheet(campaign.getId(), "duplicate", duplicate.toString());
        String cookie = cookie();
        HttpResponse<String> choices = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/copycat/copycat"))
                .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, choices.statusCode(), choices.body());
        JsonNode candidates = MAPPER.readTree(choices.body()).path("cards");
        assertEquals(2, candidates.size());
        assertEquals("Flight", candidates.get(0).path("card").path("name").asText());
        assertEquals("Armorer", candidates.get(1).path("card").path("name").asText());
        String actionId = definition.path("automation").path("actions").get(0).path("id").asText();
        ObjectNode request = MAPPER.createObjectNode().put("actorId", "copycat").put("key", "card:Copycat")
                .put("actionId", actionId).put("copyActorId", "copycat").put("copyCardName", "Copycat");
        assertEquals(400, featureHttp(cookie, request).statusCode());
        request.put("copyActorId", "source").put("copyCardName", "Flight");
        HttpResponse<String> copied = featureHttp(cookie, request);
        assertEquals(200, copied.statusCode(), copied.body());
        JsonNode saved = MAPPER.readTree(store.loadSheet(campaign.getId(), "copycat"));
        assertEquals(4, saved.path("hope").path("on").asInt());
        assertEquals(1, saved.path("usage").path("card:Copycat").path("used").asInt());
        assertEquals("Flight", saved.path("loadout").get(0).path("borrowedCard").path("card").path("name").asText());
        assertEquals("Copycat/Flight/flight", saved.path("loadout").get(0).path("borrowedCard").path("card")
                .path("automation").path("resources").get(0).path("id").asText());
        campaign.actor("source").setDisabled(true);
        var filtered = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/copycat/copycat"))
                .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, filtered.statusCode(), filtered.body());
        assertEquals(1, MAPPER.readTree(filtered.body()).path("cards").size());
        assertEquals("duplicate", MAPPER.readTree(filtered.body()).path("cards").get(0).path("actorId").asText());
        var savedWhileDisabled = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/sheet/copycat"))
                .header("Cookie", cookie).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(saved.toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, savedWhileDisabled.statusCode(), savedWhileDisabled.body());
        assertTrue(MAPPER.readTree(store.loadSheet(campaign.getId(), "copycat")).path("loadout").get(0).has("borrowedCard"));
        campaign.actor("source").setDisabled(false);
        assertEquals(400, featureHttp(cookie, request).statusCode());
        assertEquals(4, MAPPER.readTree(store.loadSheet(campaign.getId(), "copycat")).path("hope").path("on").asInt());
        JsonNode vaulted = other.withArray("loadout").remove(0);
        other.withArray("vault").add(vaulted);
        HttpResponse<String> sourceSave = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/sheet/source"))
                .header("Cookie", cookie).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(other.toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, sourceSave.statusCode(), sourceSave.body());
        JsonNode expired = MAPPER.readTree(store.loadSheet(campaign.getId(), "copycat"));
        assertFalse(expired.path("loadout").get(0).has("borrowedCard"));
        assertTrue(expired.path("usage").has("card:Copycat"));
        assertFalse(expired.path("resources").has("Copycat/Flight/flight"));
    }

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

    private ObjectNode sageArcanaSheet(String... names) throws Exception {
        ObjectNode sheet = communitySheet("Seaborne");
        sheet.putArray("traits").addObject().put("name", "Instinct").put("mod", 3).put("spellcast", true);
        sheet.withArray("traits").addObject().put("name", "Agility").put("mod", 2);
        sheet.putArray("loadout");
        sheet.putArray("vault");
        for (String name : names)
            for (JsonNode card : MAPPER.readTree(compendiumDir.resolve("domain-cards.json").toFile()).path("entries"))
                if (name.equals(card.path("name").asText())) sheet.withArray("loadout").add(card.deepCopy());
        assertEquals(names.length, sheet.path("loadout").size());
        return sheet;
    }

    @Test
    void faneCriticalRewardAcceptsSageSpellcastsButRejectsOtherRollsAndDuplicateClaims() throws Exception {
        ObjectNode sheet = sageArcanaSheet("Fane of the Wilds", "Vicious Entangle", "Flight");
        sheet.withObject("/resources").put("fane", 0);
        campaign.getActors().add(Campaign.Actor.builder().id("sage").name("Sage").ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), "sage", sheet.toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket socket = connect(cookie, inbox);
        try {
            long criticalSeed = 0, noncriticalSeed = 0;
            while (true) {
                var random = new java.util.Random(criticalSeed);
                if (random.nextInt(12) == random.nextInt(12)) break;
                criticalSeed++;
            }
            while (true) {
                var random = new java.util.Random(noncriticalSeed);
                if (random.nextInt(12) != random.nextInt(12)) break;
                noncriticalSeed++;
            }
            ObjectNode message = MAPPER.createObjectNode().put("type", "roll").put("actorId", "sage")
                    .put("expression", "2d12").put("source", "action,spellcast,card:Vicious Entangle").put("kind", "duality");
            ObjectNode claim = MAPPER.createObjectNode().put("actorId", "sage")
                    .put("key", "card:Fane of the Wilds").put("actionId", "crit");
            for (String scenario : List.of("sage", "noncritical", "arcana", "attack", "dice")) {
                diceSeed("noncritical".equals(scenario) ? noncriticalSeed : criticalSeed);
                message.put("source", switch (scenario) {
                    case "arcana" -> "action,spellcast,card:Flight";
                    case "attack" -> "action,attack";
                    default -> "action,spellcast,card:Vicious Entangle";
                });
                message.put("kind", "dice".equals(scenario) ? "dice" : "duality");
                socket.sendText(message.toString(), true).join();
                JsonNode roll = inbox.next("roll");
                claim.putObject("roll").put("id", roll.path("id").asLong()).put("ts", roll.path("ts").asLong());
                var response = featureHttp(cookie, claim);
                assertEquals("sage".equals(scenario) ? 200 : 400, response.statusCode(), scenario + ": " + response.body());
                assertEquals(1, MAPPER.readTree(store.loadSheet(campaign.getId(), "sage")).path("resources").path("fane").asInt());
                if ("sage".equals(scenario)) {
                    assertEquals(roll.path("rolls").get(0), roll.path("rolls").get(1));
                    assertEquals(400, featureHttp(cookie, claim).statusCode());
                }
            }
        } finally { socket.abort(); }
    }

    @Test
    void viciousEntangleChatActionsRollDamageAndPayHopeWithoutBypassingTriggerValidation() throws Exception {
        ObjectNode sheet = sageArcanaSheet("Vicious Entangle");
        campaign.getActors().add(Campaign.Actor.builder().id("entangler").name("Caster").ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), "entangler", sheet.toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket socket = connect(cookie, inbox);
        try {
            ObjectNode message = MAPPER.createObjectNode().put("type", "roll").put("kind", "duality")
                    .put("actorId", "entangler").put("expression", "2d12+100")
                    .put("source", "action,spellcast,card:Vicious Entangle").put("difficulty", 10);
            socket.sendText(message.toString(), true).join();
            JsonNode original = inbox.next("roll");
            ObjectNode action = MAPPER.createObjectNode().put("actorId", "entangler")
                    .put("key", "card:Vicious Entangle").put("actionId", "damage");
            assertEquals(400, featureHttp(cookie, action).statusCode());
            action.putObject("roll").put("id", original.path("id").asLong()).put("ts", original.path("ts").asLong());
            var response = featureHttp(cookie, action);
            assertEquals(200, response.statusCode(), response.body());
            JsonNode damage = inbox.next("roll");
            assertEquals("1d8+1", damage.path("expression").asText());
            assertEquals(5, MAPPER.readTree(store.loadSheet(campaign.getId(), "entangler")).path("hope").path("on").asInt());
            assertEquals(400, featureHttp(cookie, action).statusCode());
            action.put("actionId", "restrain");
            response = featureHttp(cookie, action);
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(4, MAPPER.readTree(store.loadSheet(campaign.getId(), "entangler")).path("hope").path("on").asInt());
            assertEquals(400, featureHttp(cookie, action).statusCode());

            message.put("source", "action,spellcast,card:Flight");
            socket.sendText(message.toString(), true).join();
            JsonNode unrelated = inbox.next("roll");
            action.withObject("/roll").put("id", unrelated.path("id").asLong()).put("ts", unrelated.path("ts").asLong());
            assertEquals(400, featureHttp(cookie, action).statusCode());

            long seed = 0;
            while (true) {
                var random = new java.util.Random(seed);
                if (random.nextInt(12) != random.nextInt(12)) break;
                seed++;
            }
            diceSeed(seed);
            message.put("source", "action,spellcast,card:Vicious Entangle").put("expression", "2d12-100");
            socket.sendText(message.toString(), true).join();
            JsonNode failed = inbox.next("roll");
            action.withObject("/roll").put("id", failed.path("id").asLong()).put("ts", failed.path("ts").asLong());
            for (String id : List.of("damage", "restrain")) {
                action.put("actionId", id);
                assertEquals(400, featureHttp(cookie, action).statusCode());
            }
            assertEquals(4, MAPPER.readTree(store.loadSheet(campaign.getId(), "entangler")).path("hope").path("on").asInt());
        } finally { socket.abort(); }
    }

    @Test
    void chaosSessionRefillsAndCastDamageUsesTheSavedSpendNotTheCurrentPool() throws Exception {
        ObjectNode sheet = sageArcanaSheet("Unleash Chaos");
        sheet.remove("resources");
        campaign.getActors().add(Campaign.Actor.builder().id("chaos").name("Caster").ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), "chaos", sheet.toString());
        assertNull(server.newSession());
        assertEquals(3, MAPPER.readTree(store.loadSheet(campaign.getId(), "chaos")).path("resources").path("chaos").asInt());
        String cookie = cookie();
        ObjectNode body = MAPPER.createObjectNode().put("actorId", "chaos");
        ObjectNode cast = body.putObject("transactionalRoll").put("ref", "chaos-cast").put("kind", "duality")
                .put("expression", "2d12+100").put("source", "action,spellcast,card:Unleash Chaos,featureAction:card:Unleash Chaos:cast");
        cast.putObject("hostAction").put("key", "card:Unleash Chaos").put("id", "cast").put("amount", 0);
        assertEquals(400, featureHttp(cookie, body).statusCode());
        cast.withObject("/hostAction").put("amount", 2);
        var response = featureHttp(cookie, body);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode original = dmMessages.stream().filter(m -> "chaos-cast".equals(m.path("ref").asText())).findFirst().orElseThrow();
        assertEquals(2, original.path("spentTokens").path("amount").asInt());
        assertEquals(1, MAPPER.readTree(store.loadSheet(campaign.getId(), "chaos")).path("resources").path("chaos").asInt());
        ObjectNode damage = MAPPER.createObjectNode().put("actorId", "chaos").put("key", "card:Unleash Chaos").put("actionId", "damage");
        damage.putObject("roll").put("id", original.path("id").asLong()).put("ts", original.path("ts").asLong());
        response = featureHttp(cookie, damage);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(dmMessages.stream().anyMatch(m -> "2d10".equals(m.path("expression").asText())));
        assertEquals(400, featureHttp(cookie, damage).statusCode());
    }

    @Test
    void flightActivatesOnlyAfterSuccessAndItsLastTransactionalActionRemovesTheBuff() throws Exception {
        ObjectNode sheet = sageArcanaSheet("Flight");
        campaign.getActors().add(Campaign.Actor.builder().id("flier").name("Caster").ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), "flier", sheet.toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket socket = connect(cookie, inbox);
        try {
            long seed = 0;
            while (true) {
                var random = new java.util.Random(seed);
                if (random.nextInt(12) != random.nextInt(12)) break;
                seed++;
            }
            diceSeed(seed);
            ObjectNode message = MAPPER.createObjectNode().put("type", "roll").put("kind", "duality")
                    .put("actorId", "flier").put("expression", "2d12-100").put("source", "action,spellcast,card:Flight");
            socket.sendText(message.toString(), true).join();
            inbox.next("roll");
            assertTrue(MAPPER.readTree(store.loadSheet(campaign.getId(), "flier")).path("effects").isEmpty());
            message.put("expression", "2d12+100");
            socket.sendText(message.toString(), true).join();
            inbox.next("roll");
            ObjectNode active = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "flier"));
            assertEquals(2, active.path("resources").path("flight").asInt());
            JsonNode effect = active.path("effects").get(0);
            String effectKey = "effect:" + effect.path("id").asText();
            for (int i = 0; i < 2; i++) {
                ObjectNode body = MAPPER.createObjectNode().put("actorId", "flier");
                ObjectNode roll = body.putObject("transactionalRoll").put("ref", "flying-action-" + i).put("kind", "duality")
                        .put("expression", "2d12").put("source", "action,option:" + effectKey + ":flying");
                roll.putArray("hostOptions").addObject().put("key", effectKey).put("id", "flying");
                var response = featureHttp(cookie, body);
                assertEquals(200, response.statusCode(), response.body());
            }
            JsonNode depleted = MAPPER.readTree(store.loadSheet(campaign.getId(), "flier"));
            assertEquals(0, depleted.path("resources").path("flight").asInt());
            assertTrue(depleted.path("effects").isEmpty());
        } finally { socket.abort(); }
    }

    @Test
    void wildSurgeRejectsStaleBonusesAndItsSeventhTokenEndsTheFormWithStressAndChat() throws Exception {
        ObjectNode sheet = sageArcanaSheet("Wild Surge");
        campaign.getActors().add(Campaign.Actor.builder().id("surger").name("Caster").ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), "surger", sheet.toString());
        String cookie = cookie();
        var response = featureHttp(cookie, MAPPER.createObjectNode().put("actorId", "surger").put("key", "card:Wild Surge").put("actionId", "use"));
        assertEquals(200, response.statusCode(), response.body());
        ObjectNode active = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "surger"));
        active.withObject("/resources").put("wild-surge", 6);
        store.saveSheet(campaign.getId(), "surger", active.toString());
        String key = "effect:" + active.path("effects").get(0).path("id").asText();
        ObjectNode body = MAPPER.createObjectNode().put("actorId", "surger");
        ObjectNode roll = body.putObject("transactionalRoll").put("ref", "surge-final").put("kind", "duality")
                .put("expression", "2d12+5").put("source", "action,option:" + key + ":surge");
        roll.putArray("hostOptions").addObject().put("key", key).put("id", "surge");
        var contributions = roll.putArray("contributions");
        contributions.addObject().put("label", "Hope die").put("expression", "1d12");
        contributions.addObject().put("label", "Fear die").put("expression", "1d12");
        ObjectNode bonus = contributions.addObject().put("label", "Wild Surge die").put("expression", "5");
        assertEquals(400, featureHttp(cookie, body).statusCode());
        bonus.put("expression", "6");
        roll.put("expression", "2d12+6");
        response = featureHttp(cookie, body);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode ended = MAPPER.readTree(store.loadSheet(campaign.getId(), "surger"));
        assertTrue(ended.path("effects").isEmpty());
        assertEquals(0, ended.path("resources").path("wild-surge").asInt());
        assertEquals(5, ended.path("stress").path("on").asInt());
        assertTrue(dmMessages.stream().anyMatch(m -> m.path("featureRoll").path("consequence").asText().contains("exceeded 6")));
    }

    @Test
    void consumedLuckCharmResolvesCanonicalRerollAndEndsItsBuffOnActionDamageAndOrdinaryDice() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("gatherer").name("Gatherer").ownerId(playerId()).build());
        ObjectNode sheet = sageArcanaSheet("Forager");
        ObjectNode charm = (ObjectNode) deathEntry("consumables.json", "Foraged Luck Charm");
        ObjectNode effect = (ObjectNode) charm.path("automation").path("actions").get(0)
                .path("steps").get(0).path("effect").deepCopy();
        effect.put("id", "prepared-charm").put("source", "Foraged Luck Charm");
        sheet.putArray("inventory");
        sheet.putArray("effects").add(effect);
        store.saveSheet(campaign.getId(), "gatherer", sheet.toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket socket = connect(cookie, inbox);
        try {
            for (String scenario : List.of("action", "damage", "dice")) {
                ObjectNode saved = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "gatherer"));
                ObjectNode prepared = effect.deepCopy();
                if ("damage".equals(scenario)) prepared.put("sourceKey", "item:Foraged Luck Charm");
                // Saved client rules cannot widen the canonical one-die reroll.
                ((ObjectNode) prepared.path("rollTriggers").get(0)).put("rerollAnyDie", false);
                saved.putArray("effects").add(prepared);
                store.saveSheet(campaign.getId(), "gatherer", saved.toString());
                ObjectNode message = MAPPER.createObjectNode().put("type", "roll").put("actorId", "gatherer")
                        .put("expression", "action".equals(scenario) ? "2d12" : "2d6")
                        .put("source", scenario).put("kind", "action".equals(scenario) ? "duality" : "dice");
                socket.sendText(message.toString(), true).join();
                JsonNode roll = inbox.next("roll");
                ObjectNode request = MAPPER.createObjectNode().put("actorId", "gatherer")
                        .put("key", "effect:prepared-charm").put("actionId", "reroll");
                request.putObject("roll").set("id", roll.path("id"));
                request.withObject("/roll").set("ts", roll.path("ts"));
                request.set("expectedDice", roll.path("rolls"));
                request.putArray("dice").add(0).add(1);
                assertEquals(400, featureHttp(cookie, request).statusCode());
                assertEquals(1, MAPPER.readTree(store.loadSheet(campaign.getId(), "gatherer")).path("effects").size());
                request.putArray("dice").add(0);
                request.putArray("expectedDice").add(-1);
                assertEquals(400, featureHttp(cookie, request).statusCode());
                request.set("expectedDice", roll.path("rolls"));
                diceSeed(0);
                var response = featureHttp(cookie, request);
                assertEquals(200, response.statusCode(), response.body());
                JsonNode update = inbox.next("rollUpdate");
                assertEquals(new java.util.Random(0).nextInt("action".equals(scenario) ? 12 : 6) + 1,
                        update.path("rolls").get(0).asInt());
                assertEquals(roll.path("rolls").get(1), update.path("rolls").get(1));
                assertEquals(update.path("rolls").get(0).asInt() + update.path("rolls").get(1).asInt(),
                        update.path("total").asInt());
                saved = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "gatherer"));
                assertTrue(saved.path("inventory").isEmpty());
                assertTrue(saved.path("effects").isEmpty());
                assertEquals(400, featureHttp(cookie, request).statusCode());
            }
        } finally { socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join(); }
    }

    @Test
    void foragerRepeatedVialsStackForRolledAndChosenRewardsWithoutMergingDifferentItems() throws Exception {
        ObjectNode before = sageArcanaSheet("Forager");
        campaign.getActors().add(Campaign.Actor.builder().id("gatherer").name("Gatherer").ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), "gatherer", before.toString());
        String cookie = cookie();
        long vialSeed = 0, choiceSeed = 0;
        while (new java.util.Random(vialSeed).nextInt(6) != 3) vialSeed++;
        while (new java.util.Random(choiceSeed).nextInt(6) != 5) choiceSeed++;
        for (int attempt = 0; attempt < 3; attempt++) {
            ObjectNode saved = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "gatherer"));
            if (attempt == 1) {
                ObjectNode customized = saved.path("inventory").get(0).deepCopy();
                customized.put("note", "Personalized vial");
                saved.withArray("inventory").add(customized);
                store.saveSheet(campaign.getId(), "gatherer", saved.toString());
            }
            ObjectNode request = MAPPER.createObjectNode().put("actorId", "gatherer");
            ObjectNode rest = request.putObject("rest").put("kind", "long");
            rest.set("sheet", saved.deepCopy().put("shortRestsInRow", 0));
            rest.putArray("picks").addObject().put("move", "act:card:Forager|forage");
            diceSeed(attempt == 2 ? choiceSeed : vialSeed);
            var response = featureHttp(cookie, request);
            assertEquals(200, response.statusCode(), response.body());
            if (attempt == 2) {
                JsonNode reward = dmMessages.stream().filter(m -> m.path("actionBenefits").path("options").size() == 5)
                        .reduce((first, last) -> last).orElseThrow();
                response = featureHttp(cookie, benefitRequest("gatherer", reward, "foraged-vial"));
                assertEquals(200, response.statusCode(), response.body());
            }
            JsonNode inventory = MAPPER.readTree(store.loadSheet(campaign.getId(), "gatherer")).path("inventory");
            assertEquals(attempt == 0 ? 1 : 2, inventory.size(), inventory.toString());
            assertEquals(attempt + 1, inventory.get(0).path("qty").asInt());
            assertEquals("Foraged Healing Vial", inventory.get(0).path("name").asText());
            assertTrue(inventory.get(0).path("automation").isObject());
            if (attempt > 0) {
                assertEquals(1, inventory.get(1).path("qty").asInt());
                assertEquals("Personalized vial", inventory.get(1).path("note").asText());
            }
        }
    }

    @Test
    void foragerSixOffersOneInventoryChoiceAndThePartyCarryLimitRejectsFurtherForaging() throws Exception {
        ObjectNode before = sageArcanaSheet("Forager");
        campaign.getActors().add(Campaign.Actor.builder().id("forager").name("Forager").ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), "forager", before.toString());
        String cookie = cookie();
        ObjectNode request = MAPPER.createObjectNode().put("actorId", "forager");
        ObjectNode rest = request.putObject("rest").put("kind", "short");
        rest.set("sheet", before.deepCopy().put("shortRestsInRow", 1));
        rest.putArray("picks").addObject().put("move", "act:card:Forager|forage");
        long seed = 0;
        while (new java.util.Random(seed).nextInt(6) != 5) seed++;
        diceSeed(seed);
        var response = featureHttp(cookie, request);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode reward = dmMessages.stream().filter(m -> m.path("actionBenefits").path("options").size() == 5).findFirst().orElseThrow();
        ObjectNode claim = benefitRequest("forager", reward, "foraged-rune");
        response = featureHttp(cookie, claim);
        assertEquals(200, response.statusCode(), response.body());
        ObjectNode saved = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "forager"));
        assertEquals("Foraged Arcane Rune", saved.path("inventory").get(0).path("name").asText());
        assertEquals(2, saved.path("inventory").get(0).path("automation").path("rollOptions").get(0).path("value").asInt());
        claim.put("benefitId", "foraged-vial");
        assertEquals(400, featureHttp(cookie, claim).statusCode());
        ((ObjectNode) saved.path("inventory").get(0)).put("qty", 5);
        store.saveSheet(campaign.getId(), "forager", saved.toString());
        rest.set("sheet", saved.deepCopy().put("shortRestsInRow", 2));
        assertEquals(400, featureHttp(cookie, request).statusCode());
        assertEquals(1, MAPPER.readTree(store.loadSheet(campaign.getId(), "forager")).path("shortRestsInRow").asInt());
    }

    @Test
    void conjuredSteedsPaysSelectedHopeAndRestrictsClaimsToThePurchasedMounts() throws Exception {
        String first = allyCookie("first-rider"), second = allyCookie("second-rider");
        campaign.getActors().add(Campaign.Actor.builder().id("summoner").name("Caster").ownerId(playerId()).build());
        campaign.getActors().add(Campaign.Actor.builder().id("rider-one").name("First Rider").ownerId("first-rider").build());
        campaign.getActors().add(Campaign.Actor.builder().id("rider-two").name("Second Rider").ownerId("second-rider").build());
        store.saveSheet(campaign.getId(), "summoner", sageArcanaSheet("Conjured Steeds").toString());
        store.saveSheet(campaign.getId(), "rider-one", sageArcanaSheet().toString());
        store.saveSheet(campaign.getId(), "rider-two", sageArcanaSheet().toString());
        String cookie = cookie();
        var response = featureHttp(cookie, MAPPER.createObjectNode().put("actorId", "summoner").put("key", "card:Conjured Steeds")
                .put("actionId", "conjure").put("amount", 2));
        assertEquals(200, response.statusCode(), response.body());
        JsonNode invitation = dmMessages.stream().filter(m -> m.path("actionBenefits").path("maxClaims").asInt() == 1).findFirst().orElseThrow();
        assertEquals(3, MAPPER.readTree(store.loadSheet(campaign.getId(), "summoner")).path("hope").path("on").asInt());
        assertEquals(400, featureHttp(cookie, benefitRequest("summoner", invitation, "ride")).statusCode());
        response = featureHttp(first, benefitRequest("rider-one", invitation, "ride"));
        assertEquals(200, response.statusCode(), response.body());
        JsonNode mounted = MAPPER.readTree(store.loadSheet(campaign.getId(), "rider-one")).path("effects").get(0);
        assertEquals("longRest", mounted.path("until").asText());
        assertEquals(-2, mounted.path("rollOptions").get(0).path("value").asInt());
        assertEquals(2, mounted.path("rollOptions").get(1).path("value").asInt());
        assertEquals(400, featureHttp(second, benefitRequest("rider-two", invitation, "ride")).statusCode());
    }

    @Test
    void boneValorSupportTankRerollsAllyAndChargesOnlyFeatureOwnerAtomically() throws Exception {
        compendiumDir = Path.of("dmmap-daggerheart");
        campaign.getActors().add(Campaign.Actor.builder().id("tank").name("Tank").ownerId(playerId()).build());
        campaign.getActors().add(Campaign.Actor.builder().id("ally").name("Ally").ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), "tank", bloodSheet("support-tank").toString());
        store.saveSheet(campaign.getId(), "ally", witchHostSheet().toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
        ws.sendText("{\"type\":\"roll\",\"kind\":\"duality\",\"expression\":\"2d12\",\"source\":\"action,attack\",\"actorId\":\"ally\"}", true).join();
        JsonNode roll = inbox.next("roll");
        ObjectNode request = MAPPER.createObjectNode().put("actorId", "tank").put("key", "card:Support Tank").put("actionId", "use");
        request.putObject("roll").put("id", roll.path("id").asLong()).put("ts", roll.path("ts").asLong());
        var response = featureHttp(cookie, request);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(4, MAPPER.readTree(store.loadSheet(campaign.getId(), "tank")).path("hope").path("on").asInt());
        assertEquals(6, MAPPER.readTree(store.loadSheet(campaign.getId(), "ally")).path("hope").path("on").asInt());
        assertTrue(dmMessages.stream().anyMatch(m -> m.path("type").asText().equals("rollUpdate")
                && m.path("rolls").path(1).asInt() == roll.path("rolls").path(1).asInt()));
        assertEquals(400, featureHttp(cookie, request).statusCode());
        request.put("actionId", "fear");
        assertEquals(400, featureHttp(cookie, request).statusCode());
        assertEquals(4, MAPPER.readTree(store.loadSheet(campaign.getId(), "tank")).path("hope").path("on").asInt());
        } finally { ws.abort(); }
    }

    @Test
    void boneValorUtilityActionsRollActualDiceAndPersistTheirPaymentAndChat() throws Exception {
        compendiumDir = Path.of("dmmap-daggerheart");
        campaign.getActors().add(Campaign.Actor.builder().id("bone").name("Bone").ownerId(playerId()).build());
        ObjectNode sheet = bloodSheet("i-see-it-coming", "redirect", "hold-the-line");
        sheet.putObject("proficiency").put("on", 3);
        store.saveSheet(campaign.getId(), "bone", sheet.toString());
        String cookie = cookie();
        ObjectNode request = MAPPER.createObjectNode().put("actorId", "bone").put("key", "card:I See It Coming").put("actionId", "use");
        var response = featureHttp(cookie, request);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(1, MAPPER.readTree(store.loadSheet(campaign.getId(), "bone")).path("stress").path("on").asInt());
        assertTrue(dmMessages.stream().anyMatch(m -> m.path("kind").asText().equals("dice")
                && m.path("rolls").size() == 1 && m.path("rolls").path(0).asInt() >= 1
                && m.path("rolls").path(0).asInt() <= 4));
        request.put("key", "card:Redirect");
        long seed = 0;
        while (new java.util.Random(seed).nextInt(6) != 5) seed++;
        diceSeed(seed);
        response = featureHttp(cookie, request);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(dmMessages.stream().anyMatch(m -> m.path("expression").asText().equals("3d6")));
        assertEquals(1, MAPPER.readTree(store.loadSheet(campaign.getId(), "bone")).path("stress").path("on").asInt());
        JsonNode redirect = dmMessages.stream().filter(m -> m.path("expression").asText().equals("3d6")).findFirst().orElseThrow();
        request.put("actionId", "redirect").putObject("roll").put("id", redirect.path("id").asLong()).put("ts", redirect.path("ts").asLong());
        response = featureHttp(cookie, request);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(2, MAPPER.readTree(store.loadSheet(campaign.getId(), "bone")).path("stress").path("on").asInt());
        assertEquals(400, featureHttp(cookie, request).statusCode());
        request.remove("roll");
        request.put("actionId", "use");
        request.put("key", "card:Hold the Line");
        response = featureHttp(cookie, request);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode saved = MAPPER.readTree(store.loadSheet(campaign.getId(), "bone"));
        assertEquals(5, saved.path("hope").path("on").asInt());
        assertTrue(java.util.stream.StreamSupport.stream(saved.path("effects").spliterator(), false)
                .anyMatch(effect -> effect.path("name").asText().equals("Hold the Line")));
    }

    @Test
    void boneValorRestPaysRecoveryOnceRepairsNamedAlliesAndRejectsDuplicateCommit() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("resting").name("Resting").ownerId(playerId()).build());
        campaign.getActors().add(Campaign.Actor.builder().id("rest-ally").name("Repaired Ally").ownerId(playerId()).build());
        ObjectNode before = bloodSheet("recovery", "armorer");
        before.putObject("description").put("Notes", "r".repeat(70_000));
        before.putObject("armorTrack").put("on", 3).put("slots", 6);
        store.saveSheet(campaign.getId(), "resting", before.toString());
        ObjectNode ally = bloodSheet();
        ally.putObject("armorTrack").put("on", 2).put("slots", 6);
        store.saveSheet(campaign.getId(), "rest-ally", ally.toString());
        ObjectNode body = MAPPER.createObjectNode().put("actorId", "resting");
        ObjectNode rest = body.putObject("rest").put("kind", "short").put("report", "Repair Armor.");
        ObjectNode next = before.deepCopy().put("shortRestsInRow", 1);
        next.withObject("/armorTrack").put("on", 0);
        rest.set("sheet", next);
        rest.putArray("picks").addObject().put("move", "repair");
        ObjectNode options = rest.putObject("options");
        options.putObject("passives").put("card:Recovery|ally", true);
        options.putObject("allies").put("card:Recovery|ally", "rest-ally");
        String cookie = cookie();
        var response = featureHttp(cookie, body);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(5, MAPPER.readTree(store.loadSheet(campaign.getId(), "resting")).path("hope").path("on").asInt());
        JsonNode saved = MAPPER.readTree(store.loadSheet(campaign.getId(), "rest-ally"));
        assertEquals(1, saved.path("armorTrack").path("on").asInt());
        assertTrue(saved.path("effects").path(0).path("consumeLongRestMove").asBoolean());
        assertTrue(dmMessages.stream().anyMatch(m -> m.path("text").asText().contains("Repaired Ally cleared 1 Armor Slot")));
        assertEquals(400, featureHttp(cookie, body).statusCode());
    }

    @Test
    void boneValorIncomingDamageGrantsFearAndClearsRiseUpStressOnlyOnce() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("braced").name("Braced").ownerId(playerId()).build());
        ObjectNode before = bloodSheet("octarine-brace", "rise-up");
        before.putObject("description").put("Notes", "d".repeat(70_000));
        before.putObject("armorTrack").put("on", 0).put("slots", 6);
        before.withObject("/stress").put("on", 3);
        store.saveSheet(campaign.getId(), "braced", before.toString());
        ObjectNode body = MAPPER.createObjectNode().put("actorId", "braced");
        ObjectNode damage = body.putObject("incomingDamage").put("report", "Damage taken.");
        ObjectNode next = before.deepCopy();
        next.withObject("/armorTrack").put("on", 1);
        next.withObject("/hp").put("on", 1);
        next.withObject("/stress").put("on", 2);
        damage.set("sheet", next);
        ObjectNode input = damage.putObject("input").put("damage", 12).put("type", "physical").put("armor", true);
        input.putArray("selected").add("card:Octarine Brace/octarine");
        ObjectNode result = damage.putObject("result").put("armorUsed", 1).put("armorMarked", 1).put("hp", 1).put("final", 1);
        var applied = result.putArray("applied");
        applied.addObject().put("key", "card:Octarine Brace").put("id", "octarine");
        applied.addObject().put("key", "card:Rise Up").put("id", "rise");
        int fear = campaign.getFear();
        String cookie = cookie();
        assertTrue(MAPPER.writeValueAsBytes(body).length > 65536);
        ObjectNode oversizedSheet = body.deepCopy();
        ((ObjectNode) oversizedSheet.path("incomingDamage").path("sheet").path("description")).put("Notes", "s".repeat(OnlineSessionServer.MAX_SHEET_BYTES));
        var rejectedSheet = featureHttp(cookie, oversizedSheet);
        assertEquals(413, rejectedSheet.statusCode());
        assertEquals("Sheet is too large", MAPPER.readTree(rejectedSheet.body()).path("error").asText());
        ObjectNode oversizedMetadata = body.deepCopy();
        ((ObjectNode) oversizedMetadata.path("incomingDamage")).put("report", "m".repeat(65536));
        assertEquals(413, featureHttp(cookie, oversizedMetadata).statusCode());
        assertEquals(fear, campaign.getFear());
        assertEquals(before, MAPPER.readTree(store.loadSheet(campaign.getId(), "braced")));
        var response = featureHttp(cookie, body);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(fear + 1, campaign.getFear());
        assertTrue(dmMessages.stream().anyMatch(m -> m.path("actorId").asText().equals("braced") && m.path("incomingDamage").asBoolean()));
        assertEquals(2, MAPPER.readTree(store.loadSheet(campaign.getId(), "braced")).path("stress").path("on").asInt());
        assertEquals(400, featureHttp(cookie, body).statusCode());
        assertEquals(fear + 1, campaign.getFear());
    }

    @Test
    void ordinaryFeatureRequestsKeepTheMessageSizeLimit() throws Exception {
        ObjectNode body = MAPPER.createObjectNode().put("actorId", "ordinary")
                .put("key", "card:Hold the Line").put("actionId", "use").put("padding", "x".repeat(65536));
        var response = featureHttp(cookie(), body);
        assertEquals(413, response.statusCode(), response.body());
        assertEquals("Feature request is too large.", MAPPER.readTree(response.body()).path("error").asText());
    }

    @Test
    void boneValorIncomingDamagePreservesExistingRecoveryAndQuantityCosts() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("recovering").name("Recovering").ownerId(playerId()).build());
        ObjectNode before = bloodSheet("rise-up");
        before.putObject("armorTrack").put("on", 0).put("slots", 6);
        before.withObject("/stress").put("on", 3);
        before.withObject("/hope").put("on", 6);
        ObjectNode auto = before.withArray("features").addObject().put("name", "Mitigation").putObject("automation");
        var options = auto.putArray("mitigation");
        options.addObject().put("id", "recover").put("label", "Not Done Yet").put("passive", true).put("recoveryChoice", true);
        options.addObject().put("id", "paid").put("label", "Paid").put("quantity", true).putObject("cost").put("hope", 1);
        store.saveSheet(campaign.getId(), "recovering", before.toString());
        ObjectNode body = MAPPER.createObjectNode().put("actorId", "recovering");
        ObjectNode damage = body.putObject("incomingDamage").put("report", "Damage taken.");
        ObjectNode next = before.deepCopy();
        next.withObject("/hp").put("on", 3);
        next.withObject("/stress").put("on", 1);
        next.withObject("/hope").put("on", 4);
        damage.set("sheet", next);
        ObjectNode input = damage.putObject("input").put("damage", 30).put("type", "physical").put("recovery", "stress");
        input.putArray("selected").add("feat:Mitigation/paid");
        input.putObject("quantities").put("feat:Mitigation/paid", 2);
        ObjectNode result = damage.putObject("result").put("armorUsed", 0).put("armorMarked", 0).put("hp", 3).put("final", 3);
        var applied = result.putArray("applied");
        applied.addObject().put("key", "card:Rise Up").put("id", "rise");
        applied.addObject().put("key", "feat:Mitigation").put("id", "recover");
        applied.addObject().put("key", "feat:Mitigation").put("id", "paid");
        var response = featureHttp(cookie(), body);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode saved = MAPPER.readTree(store.loadSheet(campaign.getId(), "recovering"));
        assertEquals(1, saved.path("stress").path("on").asInt());
        assertEquals(4, saved.path("hope").path("on").asInt());
    }

    @Test
    void boneValorDeathrunCommitsFirstDiceThenReusesAndRejectsStaleCounters() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("runner").name("Runner").ownerId(playerId()).build());
        ObjectNode sheet = bloodSheet("deathrun");
        sheet.putObject("proficiency").put("on", 2);
        sheet.putArray("activeWeapons").addObject().put("name", "Sword").put("damage", "d8").put("trait", "Strength");
        store.saveSheet(campaign.getId(), "runner", sheet.toString());
        String cookie = cookie();
        ObjectNode action = MAPPER.createObjectNode().put("actorId", "runner").put("key", "card:Deathrun").put("actionId", "use");
        var response = featureHttp(cookie, action);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode effect = MAPPER.readTree(store.loadSheet(campaign.getId(), "runner")).path("effects").path(0);
        String effectId = effect.path("id").asText();
        ObjectNode body = MAPPER.createObjectNode().put("actorId", "runner");
        ObjectNode roll = body.putObject("transactionalRoll").put("ref", "run-first").put("kind", "dice")
                .put("source", "damage").put("weapon", "Sword").put("expression", "3d8");
        roll.putArray("contributions").addObject().put("label", "Sword").put("expression", "3d8");
        roll.putArray("hostOptions").addObject().put("key", "effect:" + effectId).put("id", "damage");
        roll.putObject("deathrun").put("effectId", effectId).put("count", 0);
        response = featureHttp(cookie, body);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode first = dmMessages.stream().filter(m -> m.path("ref").asText().equals("run-first")).findFirst().orElseThrow();
        assertEquals(200, featureHttp(cookie, body).statusCode());
        roll.put("ref", "stale-first");
        assertEquals(400, featureHttp(cookie, body).statusCode());
        roll.withObject("/deathrun").put("count", 1);
        roll.put("ref", "run-second").put("deathrunRemove", 1);
        response = featureHttp(cookie, body);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode second = dmMessages.stream().filter(m -> m.path("ref").asText().equals("run-second")).findFirst().orElseThrow();
        assertEquals(first.path("total").asInt() - first.path("rolls").path(1).asInt(), second.path("total").asInt());
        assertEquals(2, second.path("rolls").size());
        assertEquals(3, MAPPER.readTree(store.loadSheet(campaign.getId(), "runner")).path("hope").path("on").asInt());
    }

    @Test
    void invisibilityCastsOnOfflineSheetsReplacesRecipientAndSpendsCasterTokensOnlyOnCommittedActions() throws Exception {
        compendiumDir = Path.of("dmmap-daggerheart");
        campaign.getActors().add(Campaign.Actor.builder().id("invisible-caster").name("Caster").ownerId(playerId()).build());
        campaign.getActors().add(Campaign.Actor.builder().id("invisible-recipient").name("Recipient").ownerId(playerId()).build());
        campaign.getActors().add(Campaign.Actor.builder().id("invisible-offline").name("Offline sheet").build());
        ObjectNode caster = witchHostSheet();
        caster.putArray("features");
        caster.putObject("resources").put("invisibility", 9);
        JsonNode card = java.util.stream.StreamSupport.stream(
                MAPPER.readTree(compendiumDir.resolve("domain-cards.json").toFile()).path("entries").spliterator(), false)
                .filter(entry -> "Invisibility".equals(entry.path("name").asText())).findFirst().orElseThrow();
        caster.putArray("loadout").add(card.deepCopy());
        store.saveSheet(campaign.getId(), "invisible-caster", caster.toString());
        ObjectNode target = witchHostSheet();
        target.putArray("features");
        store.saveSheet(campaign.getId(), "invisible-recipient", target.toString());
        store.saveSheet(campaign.getId(), "invisible-offline", target.toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            inbox.next("hello");
            for (String actorId : List.of("invisible-offline", "invisible-recipient")) {
                ws.sendText("""
                        {"type":"roll","kind":"duality","expression":"2d12+30","source":"action,spellcast,card:Invisibility","actorId":"invisible-caster"}
                        """, true).join();
                JsonNode rolled = inbox.next("roll");
                ObjectNode cast = MAPPER.createObjectNode().put("actorId", "invisible-caster")
                        .put("key", "card:Invisibility").put("actionId", "cast").put("targetActorId", actorId);
                cast.putObject("roll").put("id", rolled.path("id").asLong()).put("ts", rolled.path("ts").asLong());
                var response = featureHttp(cookie, cast);
                assertEquals(200, response.statusCode(), response.body());
                assertEquals(400, featureHttp(cookie, cast).statusCode());
                JsonNode saved = MAPPER.readTree(store.loadSheet(campaign.getId(), "invisible-caster"));
                assertEquals(3, saved.path("resources").path("invisibility").asInt());
                assertEquals(0, MAPPER.readTree(store.loadSheet(campaign.getId(), actorId)).path("stress").path("on").asInt());
            }
            assertTrue(MAPPER.readTree(store.loadSheet(campaign.getId(), "invisible-offline")).path("effects").isEmpty());
            JsonNode saved = MAPPER.readTree(store.loadSheet(campaign.getId(), "invisible-caster"));
            assertEquals(2, saved.path("stress").path("on").asInt());
            JsonNode recipient = MAPPER.readTree(store.loadSheet(campaign.getId(), "invisible-recipient"));
            String effectId = recipient.path("effects").get(0).path("id").asText();
            ObjectNode action = MAPPER.createObjectNode().put("type", "roll").put("kind", "duality")
                    .put("actorId", "invisible-recipient").put("expression", "2d12")
                    .put("source", "trait:Agility,action,option:effect:" + effectId + ":spend-token").put("ref", "invisible-action");
            ws.sendText(action.toString(), true).join();
            JsonNode rolled = inbox.next("roll");
            assertEquals(2, MAPPER.readTree(store.loadSheet(campaign.getId(), "invisible-caster")).path("resources").path("invisibility").asInt());
            ws.sendText(action.toString(), true).join();
            assertEquals(rolled.path("id").asLong(), inbox.next("roll").path("id").asLong());
            assertEquals(2, MAPPER.readTree(store.loadSheet(campaign.getId(), "invisible-caster")).path("resources").path("invisibility").asInt());
            action.put("expression", "bad-expression").put("ref", "invalid");
            ws.sendText(action.toString(), true).join();
            inbox.next("error");
            assertEquals(2, MAPPER.readTree(store.loadSheet(campaign.getId(), "invisible-caster")).path("resources").path("invisibility").asInt());
            action.put("expression", "2d12");
            for (int i = 0; i < 2; i++) {
                action.put("ref", "next-" + i);
                ws.sendText(action.toString(), true).join();
                inbox.next("roll");
            }
            assertEquals(0, MAPPER.readTree(store.loadSheet(campaign.getId(), "invisible-caster")).path("resources").path("invisibility").asInt());
            assertTrue(MAPPER.readTree(store.loadSheet(campaign.getId(), "invisible-recipient")).path("effects").isEmpty());
            ws.sendText(action.put("ref", "ended").toString(), true).join();
            inbox.next("error");
        } finally { ws.abort(); }
    }

    private ObjectNode witchHostSheet() throws Exception {
        return (ObjectNode) MAPPER.readTree("""
                {"className":"Witch","hope":{"on":6,"slots":6},"hp":{"on":0,"slots":6},
                 "stress":{"on":0,"slots":6},"traits":[{"name":"Presence","mod":3,"spellcast":true}],
                 "effects":[],"inventory":[],"resources":{},"usage":{},
                 "features":[{"name":"Enchanted Talisman","automation":{
                   "uses":{"max":1,"per":"rest"},
                   "resources":[{"id":"enchanted-talisman","initial":0,"resetOn":"rest","whileEffect":"Enchanted Talisman source"}],
                   "actions":[{"id":"use","label":"Enchanted Talisman","witchAction":"talisman"}]}},
                  {"name":"Walk Between Worlds","automation":{
                   "uses":{"max":1,"per":"rest"},
                   "resources":[{"id":"walk-between-worlds","label":"Walk Between Worlds","initial":0,"whileEffect":"Walk Between Worlds"}],
                   "rollTriggers":[{"id":"enter","label":"Walk Between Worlds","on":"feat:Walk Between Worlds",
                     "when":"success","difficulty":13,"cost":{"stress":1},
                     "steps":[{"op":"clear","resource":"walk-between-worlds","amount":"all"},
                       {"op":"gain","resource":"walk-between-worlds","amount":"@spellcast"},
                       {"op":"effect","effect":{"name":"Walk Between Worlds","kind":"buff","until":"scene","tokenResource":"walk-between-worlds"}}]}]}}]}
                """);
    }

    @Test
    void witchHostTalismanActionTransfersSpendsAtomicallyRejectsStaleCopiesAndExpiresOnRest() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("witch").name("Witch").ownerId(playerId()).build());
        campaign.getActors().add(Campaign.Actor.builder().id("holder").name("Holder").ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), "witch", witchHostSheet().toString());
        ObjectNode ally = witchHostSheet();
        ally.putArray("features");
        store.saveSheet(campaign.getId(), "holder", ally.toString());
        String cookie = cookie();
        ObjectNode action = MAPPER.createObjectNode().put("actorId", "witch")
                .put("key", "feat:Enchanted Talisman").put("actionId", "use");
        for (JsonNode amount : List.of(MAPPER.nullNode(), MAPPER.valueToTree(0), MAPPER.valueToTree(-1),
                MAPPER.valueToTree(1.5), MAPPER.valueToTree(7))) {
            action.set("amount", amount);
            assertEquals(400, featureHttp(cookie, action).statusCode());
        }
        assertEquals(6, MAPPER.readTree(store.loadSheet(campaign.getId(), "witch")).path("hope").path("on").asInt());
        action.put("amount", 3);
        var activation = featureHttp(cookie, action);
        assertEquals(200, activation.statusCode(), activation.body());
        assertEquals(400, featureHttp(cookie, action).statusCode());
        ObjectNode witch = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "witch"));
        assertEquals(3, witch.path("hope").path("on").asInt());
        assertEquals(3, witch.path("resources").path("enchanted-talisman").asInt());
        assertEquals(1, witch.path("usage").path("feat:Enchanted Talisman").path("used").asInt());
        JsonNode item = witch.path("inventory").get(0);
        String mirror = "talisman-tokens:witch:" + item.path("witchTalisman").path("activationId").asText();
        ObjectNode offer = MAPPER.createObjectNode().put("actorId", "witch").put("category", "item").put("offerQuantity", 1);
        offer.set("item", item);
        var offered = cardHttp(cookie, "", offer);
        assertEquals(200, offered.statusCode(), offered.body());
        String cardId = MAPPER.readTree(offered.body()).path("cardId").asText();
        ObjectNode grab = MAPPER.createObjectNode().put("actorId", "holder");
        var transfer = cardHttp(cookie, "/" + cardId + "/grab", grab);
        assertEquals(200, transfer.statusCode(), transfer.body());
        assertEquals(409, cardHttp(cookie, "/" + cardId + "/grab", grab).statusCode());
        ObjectNode holder = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "holder"));
        assertEquals(item, holder.path("inventory").get(0));
        assertEquals(3, holder.path("resources").path(mirror).asInt());
        ObjectNode stale = holder.deepCopy();
        holder.withObject("/resources").put(mirror, 1);
        var spend = saveDeathSheet(cookie, "holder", holder);
        assertEquals(200, spend.statusCode(), spend.body());
        assertEquals(1, MAPPER.readTree(store.loadSheet(campaign.getId(), "witch")).path("resources").path("enchanted-talisman").asInt());
        assertEquals(409, saveDeathSheet(cookie, "holder", stale).statusCode());
        holder = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "holder"));
        ObjectNode copied = holder.deepCopy();
        copied.withArray("inventory").add(item.deepCopy());
        assertEquals(400, saveDeathSheet(cookie, "holder", copied).statusCode());
        holder.withObject("/resources").put(mirror, 2);
        assertEquals(400, saveDeathSheet(cookie, "holder", holder).statusCode());
        witch = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "witch"));
        witch.put("shortRestsInRow", 1);
        witch.putObject("usage");
        var rest = saveDeathSheet(cookie, "witch", witch);
        assertEquals(200, rest.statusCode(), rest.body());
        holder = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "holder"));
        assertTrue(holder.path("inventory").isEmpty());
        assertTrue(holder.path("effects").isEmpty());
        assertEquals(0, holder.path("resources").path(mirror).asInt());
        assertEquals(0, MAPPER.readTree(store.loadSheet(campaign.getId(), "witch")).path("resources").path("enchanted-talisman").asInt());
        assertEquals(0, MAPPER.readTree(cardHttp(cookie, "/" + cardId, null).body()).path("available").asInt());
        action.put("amount", 1);
        assertEquals(200, featureHttp(cookie, action).statusCode());
        assertEquals(1, MAPPER.readTree(store.loadSheet(campaign.getId(), "witch")).path("inventory").size());
    }

    @Test
    void witchHostCircleAndMoonbeamPersistOfflineGrantsAndManualDismissal() throws Exception {
        campaign.getPlayers().add(Campaign.Player.builder().id("offline-player").name("Offline player").build());
        campaign.getActors().add(Campaign.Actor.builder().id("witch").name("Witch").ownerId(playerId()).build());
        campaign.getActors().add(Campaign.Actor.builder().id("offline").name("Offline").ownerId("offline-player").build());
        campaign.getActors().add(Campaign.Actor.builder().id("npc").name("NPC").build());
        ObjectNode witch = witchHostSheet();
        witch.withObject("/resources").put("circle-of-power", 3);
        witch.withArray("effects").addObject().put("id", "circle").put("witchGrant", "circle")
                .put("name", "Circle of Power").put("kind", "buff").put("until", "rest");
        witch.withArray("effects").addObject().put("id", "moon").put("witchGrant", "moonbeam")
                .put("name", "Moonbeam source").put("kind", "buff").put("until", "scene");
        store.saveSheet(campaign.getId(), "witch", witch.toString());
        store.saveSheet(campaign.getId(), "offline", witchHostSheet().toString());
        store.saveSheet(campaign.getId(), "npc", witchHostSheet().toString());
        String cookie = cookie();
        var read = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/sheet/witch"))
                .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, read.statusCode(), read.body());
        assertEquals(2, MAPPER.readTree(store.loadSheet(campaign.getId(), "offline")).path("effects").size());
        assertTrue(MAPPER.readTree(store.loadSheet(campaign.getId(), "npc")).path("effects").isEmpty());
        witch = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "witch"));
        for (int i = witch.withArray("effects").size() - 1; i >= 0; i--)
            if (witch.withArray("effects").get(i).has("witchLinked")) witch.withArray("effects").remove(i);
        var dismissed = saveDeathSheet(cookie, "witch", witch);
        assertEquals(200, dismissed.statusCode(), dismissed.body());
        witch = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "witch"));
        assertEquals(2, witch.path("effects").size());
        witch.withObject("/resources").put("circle-of-power", 0);
        assertEquals(200, saveDeathSheet(cookie, "witch", witch).statusCode());
        assertEquals(1, MAPPER.readTree(store.loadSheet(campaign.getId(), "offline")).path("effects").size());
        assertNull(server.endScene());
        assertTrue(MAPPER.readTree(store.loadSheet(campaign.getId(), "offline")).path("effects").isEmpty());
    }

    @Test
    void witchHostWalkBetweenWorldsTriggerClearsThenGainsSpellcastAndConsumesOneRestUse() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("witch").name("Witch").ownerId(playerId()).build());
        ObjectNode witch = witchHostSheet();
        witch.withObject("/resources").put("walk-between-worlds", 9);
        witch.withArray("features").addObject().put("name", "Passive Spellcast bonus")
                .putObject("automation").putArray("modifiers").addObject().put("stat", "trait.Presence").put("value", 2);
        store.saveSheet(campaign.getId(), "witch", witch.toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            assertTrue(inbox.next("hello").path("witchAutomation").asBoolean());
            ws.sendText("""
                    {"type":"roll","kind":"duality","expression":"2d12+30","source":"action,spellcast,feat:Walk Between Worlds","actorId":"witch"}
                    """, true).join();
            JsonNode roll = inbox.next("roll");
            ObjectNode enter = MAPPER.createObjectNode().put("actorId", "witch")
                    .put("key", "feat:Walk Between Worlds").put("actionId", "enter");
            enter.putObject("roll").put("id", roll.path("id").asLong()).put("ts", roll.path("ts").asLong());
            var response = featureHttp(cookie, enter);
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(400, featureHttp(cookie, enter).statusCode());
            JsonNode saved = MAPPER.readTree(store.loadSheet(campaign.getId(), "witch"));
            assertEquals(5, saved.path("resources").path("walk-between-worlds").asInt());
            assertEquals(1, saved.path("stress").path("on").asInt());
            assertEquals(1, saved.path("usage").path("feat:Walk Between Worlds").path("used").asInt());
            assertEquals("Walk Between Worlds", saved.path("effects").get(0).path("name").asText());
            assertEquals("scene", saved.path("effects").get(0).path("until").asText());
            assertNull(server.endScene());
            saved = MAPPER.readTree(store.loadSheet(campaign.getId(), "witch"));
            assertTrue(saved.path("effects").isEmpty());
            assertEquals(0, saved.path("resources").path("walk-between-worlds").asInt());
            assertEquals(1, saved.path("usage").path("feat:Walk Between Worlds").path("used").asInt());
        } finally { ws.abort(); }
    }

    @Test
    void witchHostGlamourPreservesPaidStressAndEndsOnUnpaidHpMarks() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("witch").name("Witch").ownerId(playerId()).build());
        ObjectNode witch = witchHostSheet();
        witch.withArray("effects").addObject().put("id", "glamour").put("name", "Night's Glamour")
                .put("kind", "buff").put("until", "scene").put("endOnMarkedHp", true);
        store.saveSheet(campaign.getId(), "witch", witch.toString());
        String cookie = cookie();
        witch.withObject("/hp").put("on", 1);
        witch.withObject("/stress").put("on", 1);
        var paid = saveDeathSheet(cookie, "witch", witch);
        assertEquals(200, paid.statusCode(), paid.body());
        witch = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "witch"));
        assertEquals("Night's Glamour", witch.path("effects").get(0).path("name").asText());
        witch.withObject("/hp").put("on", 2);
        ((ObjectNode) witch.path("effects").get(0)).put("hpMaintenance", 1);
        var recoveredStress = saveDeathSheet(cookie, "witch", witch);
        assertEquals(200, recoveredStress.statusCode(), recoveredStress.body());
        witch = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "witch"));
        assertEquals("Night's Glamour", witch.path("effects").get(0).path("name").asText(),
                "A maintenance receipt preserves Glamour even when Stress recovery offsets the payment");
        witch.withObject("/hp").put("on", 3);
        var unpaid = saveDeathSheet(cookie, "witch", witch);
        assertEquals(200, unpaid.statusCode(), unpaid.body());
        assertTrue(MAPPER.readTree(store.loadSheet(campaign.getId(), "witch")).path("effects").isEmpty());
    }

    @Test
    void witchHostGlamourAutomaticallyActivatesOnlyOnItsOwnSuccessfulRollIncludingCriticals() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("witch").name("Witch").ownerId(playerId()).build());
        ObjectNode witch = witchHostSheet();
        witch.withArray("features").add(MAPPER.readTree("""
                {"name":"Night’s Glamour","automation":{"rollTriggers":[{
                 "id":"glamour","automatic":true,"on":"feat:Night’s Glamour","when":"success","difficulty":13,
                 "steps":[{"op":"effect","effect":{"name":"Night's Glamour","kind":"buff","until":"manual"}}]}]}}
                """));
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            long nonCriticalSeed = 0, criticalSeed = 0;
            while (true) {
                var random = new java.util.Random(nonCriticalSeed);
                if (random.nextInt(12) != random.nextInt(12)) break;
                nonCriticalSeed++;
            }
            while (true) {
                var random = new java.util.Random(criticalSeed);
                if (random.nextInt(12) == random.nextInt(12)) break;
                criticalSeed++;
            }
            for (int scenario = 0; scenario < 4; scenario++) {
                store.saveSheet(campaign.getId(), "witch", witch.toString());
                diceSeed(scenario == 3 ? criticalSeed : nonCriticalSeed);
                ObjectNode message = MAPPER.createObjectNode().put("type", "roll").put("kind", "duality")
                        .put("actorId", "witch").put("expression", scenario == 1 || scenario == 3 ? "2d12-30" : "2d12+30")
                        .put("source", scenario == 0 ? "action,spellcast,feat:Other spell" : "action,spellcast,feat:Night’s Glamour");
                ws.sendText(message.toString(), true).join();
                boolean expected = scenario >= 2;
                if (expected) assertEquals(1, inbox.next("sheetFeature").path("effects").size());
                JsonNode roll = inbox.next("roll");
                JsonNode saved = MAPPER.readTree(store.loadSheet(campaign.getId(), "witch"));
                assertEquals(expected ? 1 : 0, saved.path("effects").size(), "scenario " + scenario);
                assertEquals(expected ? 1 : 0, roll.path("featureUses").size());
                assertEquals(0, saved.path("stress").path("on").asInt());
                assertEquals(0, saved.path("usage").size());
                ObjectNode manual = MAPPER.createObjectNode().put("actorId", "witch")
                        .put("key", "feat:Night’s Glamour").put("actionId", "glamour");
                manual.putObject("roll").put("id", roll.path("id").asLong()).put("ts", roll.path("ts").asLong());
                assertEquals(400, featureHttp(cookie, manual).statusCode());
                if (expected) {
                    assertEquals("Night's Glamour", saved.path("effects").get(0).path("name").asText());
                    assertEquals(409, saveDeathSheet(cookie, "witch", witch.deepCopy()).statusCode(),
                            "A pre-roll debounced save cannot overwrite the automatic grant");
                    ObjectNode current = (ObjectNode) saved;
                    current.withObject("/hope").put("on", 5);
                    var followUp = saveDeathSheet(cookie, "witch", current);
                    assertEquals(200, followUp.statusCode(), followUp.body());
                    current = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "witch"));
                    assertEquals("Night's Glamour", current.path("effects").get(0).path("name").asText());
                    current.withArray("effects").removeAll();
                    assertEquals(200, saveDeathSheet(cookie, "witch", current).statusCode());
                    assertEquals(400, featureHttp(cookie, manual).statusCode(),
                            "An old successful roll cannot recreate the expired Glamour");
                    assertTrue(MAPPER.readTree(store.loadSheet(campaign.getId(), "witch")).path("effects").isEmpty());
                }
            }
            assertEquals(4, store.historyTail(campaign.getId(), 20).size(), "No extra automatic action chat report");
        } finally { ws.abort(); }
    }

    private ObjectNode warriorSheet(String subclass) throws Exception {
        ObjectNode sheet = communitySheet("Seaborne");
        sheet.put("className", "Warrior").put("subclass", "Call of the " + subclass).put("level", 5);
        sheet.putObject("proficiency").put("on", 3).put("slots", 6);
        sheet.putArray("effects");
        var features = sheet.putArray("features");
        for (JsonNode entry : MAPPER.readTree(compendiumDir.resolve("classes.json").toFile()).path("entries"))
            if ("warrior".equals(entry.path("id").asText()) || entry.path("id").asText().startsWith("warrior-call-of-the-" + subclass.toLowerCase(java.util.Locale.ROOT))) {
                for (JsonNode feature : entry.path("features")) {
                    ObjectNode learned = feature.deepCopy();
                    learned.put("source", "class".equals(entry.path("kind").asText()) ? "Class feature - Warrior"
                            : "Subclass " + entry.path("stage").asText() + " - Call of the " + subclass);
                    features.add(learned);
                }
            }
        return sheet;
    }

    @Test
    void slayerRewardIsExclusiveRejectsReactionsAndConvertsUnspentDiceOnNewSessionOnly() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("reward-actor").name("Warrior").ownerId(playerId()).build());
        ObjectNode sheet = warriorSheet("Slayer");
        sheet.withObject("/resources").put("slayer-dice", 1);
        sheet.withObject("/hope").put("on", 6);
        store.saveSheet(campaign.getId(), "reward-actor", sheet.toString());
        String cookie = cookie();
        ObjectNode roll = rewardRoll(5500, 10, 3);
        roll.put("source", "action,attack");
        store.replaceHistoryEntry(campaign.getId(), 5500, 1234, roll);
        ObjectNode request = MAPPER.createObjectNode().put("actorId", "reward-actor").put("reward", "slayer");
        request.putObject("roll").put("id", 5500).put("ts", 1234);
        var response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/roll-reward"))
                .header("Cookie", cookie).POST(HttpRequest.BodyPublishers.ofString(request.toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        JsonNode saved = MAPPER.readTree(store.loadSheet(campaign.getId(), "reward-actor"));
        assertEquals(2, saved.path("resources").path("slayer-dice").asInt());
        assertEquals(6, saved.path("hope").path("on").asInt());
        assertEquals(400, rewardHttp(cookie, "reward-actor", roll).statusCode());
        for (int i = 0; i < 3; i++) {
            roll = rewardRoll(5501 + i, i == 0 ? 2 : 10, i == 1 ? 10 : 3);
            roll.put("source", i == 2 ? "reaction,action" : "action");
            store.replaceHistoryEntry(campaign.getId(), 5501 + i, 1234, roll);
            request.withObject("/roll").put("id", 5501 + i);
            response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/roll-reward"))
                    .header("Cookie", cookie).POST(HttpRequest.BodyPublishers.ofString(request.toString())).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, response.statusCode(), response.body());
        }
        sheet = (ObjectNode) saved;
        sheet.withObject("/hope").put("on", 3);
        store.saveSheet(campaign.getId(), "reward-actor", sheet.toString());
        assertNull(server.endScene());
        assertEquals(2, MAPPER.readTree(store.loadSheet(campaign.getId(), "reward-actor")).path("resources").path("slayer-dice").asInt());
        assertNull(server.newSession());
        saved = MAPPER.readTree(store.loadSheet(campaign.getId(), "reward-actor"));
        assertEquals(0, saved.path("resources").path("slayer-dice").asInt());
        assertEquals(5, saved.path("hope").path("on").asInt());
        assertNull(server.newSession());
        assertEquals(5, MAPPER.readTree(store.loadSheet(campaign.getId(), "reward-actor")).path("hope").path("on").asInt());
    }

    @Test
    void weaponSpecialistRerollsOnlySlayerOnesOncePerLongRestOnAttackAndDamage() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("warrior").name("Warrior").ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), "warrior", warriorSheet("Slayer").toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            assertTrue(inbox.next("hello").path("warriorAutomation").asBoolean());
            for (String source : List.of("attack,action", "damage")) {
                ObjectNode message = MAPPER.createObjectNode().put("type", "roll").put("actorId", "warrior")
                        .put("source", source).put("expression", "2d12+2d6");
                if (source.contains("attack")) message.put("kind", "duality");
                var parts = message.putArray("contributions");
                parts.addObject().put("label", "Hope").put("expression", "1d12");
                parts.addObject().put("label", "Fear").put("expression", "1d12");
                parts.addObject().put("label", "Slayer").put("expression", "2d6").putArray("notes")
                        .addObject().put("label", "Dice resource").put("impact", "slayer-dice");
                long seed = 0;
                while (true) {
                    var random = new java.util.Random(seed);
                    random.nextInt(12); random.nextInt(12);
                    if (random.nextInt(6) == 0) break;
                    seed++;
                }
                diceSeed(seed);
                ws.sendText(message.toString(), true).join();
                JsonNode roll = inbox.next("roll");
                ObjectNode request = MAPPER.createObjectNode().put("actorId", "warrior")
                        .put("key", "feat:Weapon Specialist").put("actionId", "reroll");
                request.putObject("roll").put("id", roll.path("id").asLong()).put("ts", roll.path("ts").asLong());
                var response = featureHttp(cookie, request);
                assertEquals(200, response.statusCode(), response.body());
                JsonNode update = inbox.next("rollUpdate");
                assertEquals(roll.path("rolls").path(0), update.path("rolls").path(0));
                assertEquals(roll.path("rolls").path(1), update.path("rolls").path(1));
                assertEquals(400, featureHttp(cookie, request).statusCode());
                ObjectNode saved = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "warrior"));
                assertEquals(1, saved.path("usage").path("feat:Weapon Specialist").path("used").asInt());
                assertEquals("longRest", saved.path("usage").path("feat:Weapon Specialist").path("per").asText());
                saved.putObject("usage");
                store.saveSheet(campaign.getId(), "warrior", saved.toString());
            }
        } finally { ws.abort(); }
    }

    @Test
    void martialPreparationAccessIsPersistedForOfflineCampaignActorsOnSheetRead() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("warrior").name("Warrior").ownerId(playerId()).build());
        campaign.getActors().add(Campaign.Actor.builder().id("offline").name("Offline").ownerId("offline-owner").build());
        store.saveSheet(campaign.getId(), "warrior", warriorSheet("Slayer").toString());
        ObjectNode ally = communitySheet("Seaborne");
        ally.putArray("effects");
        store.saveSheet(campaign.getId(), "offline", ally.toString());
        String cookie = cookie();
        var response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/sheet/warrior"))
                .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        JsonNode saved = MAPPER.readTree(store.loadSheet(campaign.getId(), "offline"));
        assertEquals("train", saved.path("effects").path(0).path("actions").path(0).path("id").asText());
        assertTrue(saved.path("effects").path(0).path("partyDowntimeGrant").asBoolean());
        assertFalse(saved.path("resources").has("slayer-dice"));
        String previous = store.loadSheet(campaign.getId(), "offline");
        response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/sheet/warrior"))
                .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(previous, store.loadSheet(campaign.getId(), "offline"));
    }

    @Test
    void staleLegacyPartyDowntimeActionIsRejectedWhileSourceIsDisabled() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("warrior").name("Warrior").ownerId(playerId()).build());
        campaign.getActors().add(Campaign.Actor.builder().id("ally").name("Ally").ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), "warrior", warriorSheet("Slayer").toString());
        store.saveSheet(campaign.getId(), "ally", communitySheet("Seaborne").toString());
        String cookie = cookie();
        URI allySheet = URI.create("http://localhost:" + port + "/api/sheet/ally");
        assertEquals(200, http.send(HttpRequest.newBuilder(allySheet).header("Cookie", cookie).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        ObjectNode saved = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "ally"));
        ((ObjectNode) saved.path("effects").get(0)).remove("sourceActorId");
        store.saveSheet(campaign.getId(), "ally", saved.toString());
        campaign.actor("warrior").setDisabled(true);
        ObjectNode action = MAPPER.createObjectNode().put("actorId", "ally")
                .put("key", "effect:party-martial-preparation").put("actionId", "train");
        var rejected = featureHttp(cookie, action);
        assertEquals(400, rejected.statusCode(), rejected.body());
        assertEquals(200, http.send(HttpRequest.newBuilder(allySheet).header("Cookie", cookie).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals("warrior", MAPPER.readTree(store.loadSheet(campaign.getId(), "ally"))
                .path("effects").get(0).path("sourceActorId").asText());
        campaign.actor("warrior").setDisabled(false);
        assertEquals(200, http.send(HttpRequest.newBuilder(allySheet).header("Cookie", cookie).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals("warrior", MAPPER.readTree(store.loadSheet(campaign.getId(), "ally"))
                .path("effects").get(0).path("sourceActorId").asText());
    }

    @Test
    void courageRejectsReactionsAndClaimsEachFailedFearRollOnce() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("warrior").name("Warrior").ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), "warrior", warriorSheet("Brave").toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            for (String source : List.of("reaction,action", "action")) {
                diceSeed(0);
                ObjectNode message = MAPPER.createObjectNode().put("type", "roll").put("kind", "duality")
                        .put("expression", "2d12").put("actorId", "warrior").put("source", source);
                ws.sendText(message.toString(), true).join();
                JsonNode roll = inbox.next("roll");
                assertTrue(roll.path("rolls").path(0).asInt() < roll.path("rolls").path(1).asInt());
                ObjectNode request = MAPPER.createObjectNode().put("actorId", "warrior")
                        .put("key", "feat:Courage").put("actionId", "gain");
                request.putObject("roll").put("id", roll.path("id").asLong()).put("ts", roll.path("ts").asLong());
                var response = featureHttp(cookie, request);
                assertEquals(source.contains("reaction") ? 400 : 200, response.statusCode(), response.body());
                assertEquals(source.contains("reaction") ? 5 : 6,
                        MAPPER.readTree(store.loadSheet(campaign.getId(), "warrior")).path("hope").path("on").asInt());
                if (!source.contains("reaction")) assertEquals(400, featureHttp(cookie, request).statusCode());
            }
        } finally { ws.abort(); }
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

    private ObjectNode guardianSheet(int level) throws Exception {
        ObjectNode sheet = communitySheet("Seaborne");
        sheet.put("className", "Guardian").put("subclass", "Vengeance").put("level", level);
        sheet.putArray("effects");
        var features = sheet.putArray("features");
        for (JsonNode entry : MAPPER.readTree(compendiumDir.resolve("classes.json").toFile()).path("entries")) {
            if ("guardian".equals(entry.path("id").asText()) || entry.path("id").asText().startsWith("guardian-vengeance-")) {
                for (JsonNode feature : entry.path("features")) {
                    ObjectNode learned = feature.deepCopy();
                    learned.put("source", "class".equals(entry.path("kind").asText()) ? "Class feature - Guardian"
                            : "Subclass " + entry.path("stage").asText() + " - Vengeance");
                    features.add(learned);
                }
            }
        }
        return sheet;
    }

    private void guardianBuff(ObjectNode sheet, String featureName) {
        for (JsonNode feature : sheet.path("features")) {
            if (!featureName.equals(feature.path("name").asText())) continue;
            ObjectNode effect = feature.path("automation").path("actions").path(0).path("steps").path(0).path("effect").deepCopy();
            effect.put("id", "guardian-buff");
            sheet.withArray("effects").add(effect);
        }
    }

    private ObjectNode seraphSheet(String subclass) throws Exception {
        ObjectNode sheet = communitySheet("Seaborne");
        sheet.put("className", "Seraph").put("subclass", subclass).put("level", 8);
        sheet.putArray("effects");
        var features = sheet.putArray("features");
        String prefix = "seraph-" + subclass.toLowerCase(java.util.Locale.ROOT).replace(' ', '-') + "-";
        for (JsonNode entry : MAPPER.readTree(compendiumDir.resolve("classes.json").toFile()).path("entries")) {
            if (!"seraph".equals(entry.path("id").asText()) && !entry.path("id").asText().startsWith(prefix)) continue;
            for (JsonNode feature : entry.path("features")) {
                ObjectNode learned = feature.deepCopy();
                learned.put("source", "class".equals(entry.path("kind").asText()) ? "Class feature - Seraph"
                        : "Subclass " + entry.path("stage").asText() + " - " + subclass);
                features.add(learned);
            }
        }
        return sheet;
    }

    private ObjectNode warlockSheet(int level) throws Exception {
        ObjectNode sheet = communitySheet("Seaborne");
        sheet.put("className", "Warlock").put("subclass", "Pact Of The Wrathful").put("level", level);
        sheet.putArray("effects");
        sheet.withObject("/resources").put("favor", 12);
        var features = sheet.putArray("features");
        for (JsonNode entry : MAPPER.readTree(compendiumDir.resolve("classes.json").toFile()).path("entries")) {
            if (!"warlock".equals(entry.path("id").asText()) && !entry.path("id").asText().startsWith("warlock-pact-")) continue;
            for (JsonNode feature : entry.path("features")) {
                ObjectNode learned = feature.deepCopy();
                learned.put("source", "class".equals(entry.path("kind").asText()) ? "Class feature - Warlock"
                        : "Subclass " + entry.path("stage").asText() + " - " + entry.path("name").asText());
                features.add(learned);
            }
        }
        sheet.putArray("activeWeapons").addObject().put("name", "Blade").put("slot", "Primary").put("range", "Melee");
        sheet.withArray("activeWeapons").addObject().put("name", "Dagger").put("slot", "Secondary").put("range", "Melee");
        return sheet;
    }

    @Test
    void warlockPatronActionsValidateSpendRollHealAndShareRestLimits() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("warlock").name("Warlock").ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), "warlock", warlockSheet(3).toString());
        String cookie = cookie();
        ObjectNode request = MAPPER.createObjectNode().put("actorId", "warlock").put("key", "feat:Deathless Embrace").put("actionId", "use");
        for (double invalid : new double[]{0, -1, 1.5, 13}) {
            request.put("amount", invalid);
            assertEquals(400, featureHttp(cookie, request).statusCode());
        }
        assertEquals(12, MAPPER.readTree(store.loadSheet(campaign.getId(), "warlock")).path("resources").path("favor").asInt());
        request.put("amount", 3);
        diceSeed(1);
        var healRandom = new java.util.Random(1);
        List<Integer> faces = java.util.stream.IntStream.range(0, 3).map(i -> healRandom.nextInt(6) + 1).boxed().toList();
        assertTrue(faces.stream().anyMatch(n -> n >= 4) && faces.stream().anyMatch(n -> n < 4));
        var response = featureHttp(cookie, request);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode saved = MAPPER.readTree(store.loadSheet(campaign.getId(), "warlock"));
        assertEquals(9, saved.path("resources").path("favor").asInt());
        assertEquals(4 - faces.stream().filter(n -> n >= 4).count(), saved.path("hp").path("on").asInt());
        assertEquals(1, saved.path("usage").path("feat:Deathless Embrace").path("used").asInt());
        String report = store.historyTail(campaign.getId(), 1).getLast().path("text").asText();
        assertTrue(report.contains("3d6: " + faces), report);
        assertTrue(report.contains("cleared " + faces.stream().filter(n -> n >= 4).count() + " HP"), report);
        assertEquals(400, featureHttp(cookie, request).statusCode());

        request.put("key", "feat:Deadly Vengeance").put("amount", 2);
        diceSeed(1);
        response = featureHttp(cookie, request);
        assertEquals(200, response.statusCode(), response.body());
        saved = MAPPER.readTree(store.loadSheet(campaign.getId(), "warlock"));
        assertEquals(8, saved.path("resources").path("favor").asInt());
        report = store.historyTail(campaign.getId(), 1).getLast().path("text").asText();
        assertTrue(report.contains("2d6: " + faces.subList(0, 2)), report);
        assertTrue(report.contains("For 2 HP marked from the attack, the attacker marks"), report);
        assertEquals(4 - faces.stream().filter(n -> n >= 4).count(), saved.path("hp").path("on").asInt());
        request.put("amount", 7);
        assertEquals(400, featureHttp(cookie, request).statusCode());

        ObjectNode upgraded = (ObjectNode) saved;
        upgraded.put("level", 5);
        store.saveSheet(campaign.getId(), "warlock", upgraded.toString());
        request.put("key", "feat:Otherworldly Ire").put("amount", 3);
        diceSeed(123);
        var random = new java.util.Random(123);
        List<Integer> ireFaces = new ArrayList<>();
        for (int i = 0; i < 3; i++) ireFaces.add(random.nextInt(8) + 1);
        response = featureHttp(cookie, request);
        assertEquals(200, response.statusCode(), response.body());
        saved = MAPPER.readTree(store.loadSheet(campaign.getId(), "warlock"));
        assertEquals(5, saved.path("resources").path("favor").asInt());
        report = store.historyTail(campaign.getId(), 1).getLast().path("text").asText();
        assertTrue(report.contains("3d8: " + ireFaces), report);
        assertTrue(report.contains("Highest result: " + ireFaces.stream().mapToInt(Integer::intValue).max().orElseThrow()), report);
        assertTrue(report.contains("each target marks 1 HP"), report);
        assertEquals(400, featureHttp(cookie, request).statusCode());
        assertEquals(5, MAPPER.readTree(store.loadSheet(campaign.getId(), "warlock")).path("resources").path("favor").asInt());
        request.put("key", "feat:Diminish My Foes").put("amount", 2);
        response = featureHttp(cookie, request);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(store.historyTail(campaign.getId(), 1).getLast().path("text").asText().contains("the target marks 2 Stress"));
    }

    @Test
    void warlockReachDiminishAndRepeatableRerollsValidateChatContextAndPersist() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("warlock").name("Warlock").ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), "warlock", warlockSheet(5).toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            assertTrue(inbox.next("hello").path("warlockAutomation").asBoolean());
            ObjectNode request = MAPPER.createObjectNode().put("actorId", "warlock").put("key", "feat:Menacing Reach").put("actionId", "use");
            var response = featureHttp(cookie, request);
            assertEquals(200, response.statusCode(), response.body());
            JsonNode saved = MAPPER.readTree(store.loadSheet(campaign.getId(), "warlock"));
            assertEquals(11, saved.path("resources").path("favor").asInt());
            assertTrue(saved.path("effects").path(0).path("text").asText().contains("Blade reach: Very Close"));
            assertEquals(400, featureHttp(cookie, request).statusCode());
            ObjectNode message = MAPPER.createObjectNode().put("type", "roll").put("actorId", "warlock")
                    .put("source", "attack,action").put("weapon", "Dagger").put("kind", "duality").put("expression", "2d12");
            ws.sendText(message.toString(), true).join();
            JsonNode roll = inbox.next("roll");
            request.put("actionId", "end");
            request.putObject("roll").set("id", roll.path("id"));
            request.withObject("/roll").set("ts", roll.path("ts"));
            assertEquals(400, featureHttp(cookie, request).statusCode());
            message.put("weapon", "Blade");
            ws.sendText(message.toString(), true).join();
            roll = inbox.next("roll");
            request.putObject("roll").set("id", roll.path("id"));
            request.withObject("/roll").set("ts", roll.path("ts"));
            response = featureHttp(cookie, request);
            assertEquals(200, response.statusCode(), response.body());
            inbox.next("rollUpdate");
            assertEquals(0, MAPPER.readTree(store.loadSheet(campaign.getId(), "warlock")).path("effects").size());

            message.remove("kind");
            message.put("source", "damage").put("expression", "2d8+3");
            ws.sendText(message.toString(), true).join();
            roll = inbox.next("roll");
            request.put("key", "feat:Fearsome Attack").put("actionId", "reroll");
            request.putObject("roll").set("id", roll.path("id"));
            request.withObject("/roll").set("ts", roll.path("ts"));
            request.putArray("dice").add(0);
            request.set("expectedDice", roll.path("rolls"));
            for (int i = 0; i < 2; i++) {
                response = featureHttp(cookie, request);
                assertEquals(200, response.statusCode(), response.body());
                JsonNode updated = inbox.next("rollUpdate");
                assertEquals(roll.path("rolls").path(1), updated.path("rolls").path(1));
                assertEquals(updated.path("rolls").path(0).asInt() + updated.path("rolls").path(1).asInt() + 3, updated.path("total").asInt());
                assertEquals(10 - i, MAPPER.readTree(store.loadSheet(campaign.getId(), "warlock")).path("resources").path("favor").asInt());
                request.set("expectedDice", updated.path("rolls"));
            }
            request.putArray("expectedDice").add(-1);
            assertEquals(400, featureHttp(cookie, request).statusCode());
            request.remove(List.of("dice", "expectedDice"));
            request.put("key", "feat:Diminish My Foes").put("actionId", "use").put("amount", 2);
            assertEquals(400, featureHttp(cookie, request).statusCode());
            message.put("kind", "duality").put("source", "action").put("expression", "2d12");
            diceSeed(0);
            ws.sendText(message.toString(), true).join();
            roll = inbox.next("roll");
            request.putObject("roll").set("id", roll.path("id"));
            request.withObject("/roll").set("ts", roll.path("ts"));
            assertTrue(roll.path("rolls").path(0).asInt() < roll.path("rolls").path(1).asInt());
            assertEquals(400, featureHttp(cookie, request).statusCode());
            diceSeed(1);
            ws.sendText(message.toString(), true).join();
            roll = inbox.next("roll");
            assertTrue(roll.path("rolls").path(0).asInt() > roll.path("rolls").path(1).asInt());
            request.putObject("roll").set("id", roll.path("id"));
            request.withObject("/roll").set("ts", roll.path("ts"));
            response = featureHttp(cookie, request);
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(400, featureHttp(cookie, request).statusCode());
            assertEquals(7, MAPPER.readTree(store.loadSheet(campaign.getId(), "warlock")).path("resources").path("favor").asInt());
        } finally { ws.abort(); }
    }

    @Test
    void warlockFavorRewardIsExclusivePersistsAtFullHopeAndRejectsOtherOutcomes() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("reward-actor").name("Warlock").ownerId(playerId()).build());
        ObjectNode sheet = warlockSheet(3);
        sheet.withObject("/hope").put("on", 6);
        store.saveSheet(campaign.getId(), "reward-actor", sheet.toString());
        String cookie = cookie();
        ObjectNode roll = rewardRoll(5500, 10, 3);
        roll.put("source", "action,attack");
        store.replaceHistoryEntry(campaign.getId(), 5500, 1234, roll);
        ObjectNode request = MAPPER.createObjectNode().put("actorId", "reward-actor").put("reward", "favor");
        request.putObject("roll").put("id", 5500).put("ts", 1234);
        var response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/roll-reward"))
                .header("Cookie", cookie).POST(HttpRequest.BodyPublishers.ofString(request.toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        JsonNode saved = MAPPER.readTree(store.loadSheet(campaign.getId(), "reward-actor"));
        assertEquals(13, saved.path("resources").path("favor").asInt());
        assertEquals(6, saved.path("hope").path("on").asInt());
        assertEquals(400, rewardHttp(cookie, "reward-actor", roll).statusCode());
        assertTrue(store.historyTail(campaign.getId(), 1).getLast().path("text").asText().contains("Instead of gaining Hope"));
        for (int i = 0; i < 4; i++) {
            roll = rewardRoll(5501 + i, i == 0 ? 2 : 10, i == 1 ? 10 : 3);
            roll.put("source", i == 2 ? "reaction" : "action");
            if (i == 3) roll.put("difficulty", 99);
            store.replaceHistoryEntry(campaign.getId(), 5501 + i, 1234, roll);
            request.withObject("/roll").put("id", 5501 + i);
            response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/roll-reward"))
                    .header("Cookie", cookie).POST(HttpRequest.BodyPublishers.ofString(request.toString())).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, response.statusCode(), response.body());
            assertEquals(13, MAPPER.readTree(store.loadSheet(campaign.getId(), "reward-actor")).path("resources").path("favor").asInt());
        }
    }

    private ObjectNode sorcererSheet() throws Exception {
        ObjectNode sheet = communitySheet("Seaborne");
        sheet.put("className", "Sorcerer").put("subclass", "Primal Origin");
        sheet.putArray("effects");
        var features = sheet.putArray("features");
        for (JsonNode entry : MAPPER.readTree(compendiumDir.resolve("classes.json").toFile()).path("entries")) {
            if (!"sorcerer".equals(entry.path("id").asText()) && !entry.path("id").asText().startsWith("sorcerer-primal-origin-")) continue;
            for (JsonNode feature : entry.path("features")) {
                ObjectNode learned = feature.deepCopy();
                learned.put("source", "class".equals(entry.path("kind").asText()) ? "Class feature - Sorcerer"
                        : "Subclass " + entry.path("stage").asText() + " - Primal Origin");
                features.add(learned);
            }
        }
        sheet.putArray("activeWeapons").addObject().put("name", "Wand").put("damageType", "magic");
        return sheet;
    }

    @Test
    void sorcererDamageSelectionEditsTheOriginalRollAtomicallyAndRejectsStaleDuplicateAndPhysicalChoices() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("sorcerer").name("Sorcerer").ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), "sorcerer", sorcererSheet().toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            assertTrue(inbox.next("hello").path("sorcererAutomation").asBoolean());
            ObjectNode msg = MAPPER.createObjectNode().put("type", "roll").put("actorId", "sorcerer")
                    .put("source", "damage").put("weapon", "Wand").put("expression", "(2d8+1d4+3)*2").put("toId", "dm");
            var contributions = msg.putArray("contributions");
            contributions.addObject().put("label", "Weapon").put("expression", "(2d8)*2");
            contributions.addObject().put("label", "Bonus die").put("expression", "(1d4)*2");
            contributions.addObject().put("label", "Flat bonus").put("expression", "(3)*2");
            ws.sendText(msg.toString(), true).join();
            JsonNode roll = inbox.next("roll");
            ObjectNode request = MAPPER.createObjectNode().put("actorId", "sorcerer").put("key", "feat:Volatile Magic").put("actionId", "reroll");
            request.putObject("roll").set("id", roll.path("id"));
            request.withObject("/roll").set("ts", roll.path("ts"));
            request.set("expectedDice", roll.path("rolls"));
            request.putArray("dice").add(0).add(0);
            assertEquals(400, featureHttp(cookie, request).statusCode());
            assertEquals(5, MAPPER.readTree(store.loadSheet(campaign.getId(), "sorcerer")).path("hope").path("on").asInt());
            request.putArray("dice").add(0).add(2);
            var response = featureHttp(cookie, request);
            assertEquals(200, response.statusCode(), response.body());
            JsonNode update = inbox.next("rollUpdate");
            assertEquals(roll.path("id"), update.path("id"));
            assertEquals(roll.path("rolls").path(1), update.path("rolls").path(1));
            int total = (update.path("rolls").path(0).asInt() + update.path("rolls").path(1).asInt()
                    + update.path("rolls").path(2).asInt() + 3) * 2;
            assertEquals(total, update.path("total").asInt());
            assertEquals(total, java.util.stream.StreamSupport.stream(update.path("breakdown").spliterator(), false)
                    .mapToInt(detail -> detail.path("total").asInt()).sum());
            JsonNode rerollReport = inbox.next("chat");
            String rerollText = rerollReport.path("text").asText();
            assertEquals("dm", rerollReport.path("toId").asText());
            assertEquals("Volatile Magic", rerollReport.path("rollSource").path("name").asText());
            for (int i : List.of(0, 2)) assertTrue(rerollText.contains("Rerolled damage die " + (i + 1) + ": "
                    + roll.path("rolls").path(i).asInt() + " -> " + update.path("rolls").path(i).asInt() + "."));
            assertTrue(rerollText.contains("Damage total: " + roll.path("total").asInt() + " -> " + total + "."));
            assertEquals(2, MAPPER.readTree(store.loadSheet(campaign.getId(), "sorcerer")).path("hope").path("on").asInt());
            assertEquals(400, featureHttp(cookie, request).statusCode());
            request.put("key", "feat:Manipulate Magic").put("actionId", "damage");
            request.putArray("dice").add(1);
            request.putArray("expectedDice").add(-1);
            assertEquals(400, featureHttp(cookie, request).statusCode());
            request.set("expectedDice", update.path("rolls"));
            response = featureHttp(cookie, request);
            assertEquals(200, response.statusCode(), response.body());
            JsonNode doubled = inbox.next("rollUpdate");
            assertEquals(update.path("rolls").path(1).asInt() * 2, doubled.path("rolls").path(1).asInt());
            assertEquals(total + update.path("rolls").path(1).asInt() * 2, doubled.path("total").asInt());
            JsonNode doubleReport = inbox.next("chat");
            assertEquals("dm", doubleReport.path("toId").asText());
            assertEquals("Manipulate Magic", doubleReport.path("rollSource").path("name").asText());
            assertTrue(doubleReport.path("text").asText().contains("Doubled damage die 2: "
                    + update.path("rolls").path(1).asInt() + " -> " + doubled.path("rolls").path(1).asInt() + "."));
            assertTrue(doubleReport.path("text").asText().contains("Damage total: " + total + " -> " + doubled.path("total").asInt() + "."));
            assertEquals(4, MAPPER.readTree(store.loadSheet(campaign.getId(), "sorcerer")).path("stress").path("on").asInt());
            JsonNode persisted = store.historyTail(campaign.getId(), 20).stream()
                    .filter(entry -> entry.path("id").equals(roll.path("id"))).findFirst().orElseThrow();
            assertEquals(doubled.path("rolls"), persisted.path("rolls"));
            assertEquals(doubled.path("total"), persisted.path("total"));
            assertTrue(store.historyTail(campaign.getId(), 20).contains(rerollReport));
            assertTrue(store.historyTail(campaign.getId(), 20).contains(doubleReport));
            msg.put("weapon", "Sword");
            ws.sendText(msg.toString(), true).join();
            JsonNode physical = inbox.next("roll");
            request.withObject("/roll").set("id", physical.path("id"));
            request.withObject("/roll").set("ts", physical.path("ts"));
            request.set("expectedDice", physical.path("rolls"));
            assertEquals(400, featureHttp(cookie, request).statusCode());
        } finally { ws.abort(); }
    }

    @Test
    void manipulateMagicSelectsOneActionBenefitAndPaysStressOnceWithoutChangingDualityDice() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("sorcerer").name("Sorcerer").ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), "sorcerer", sorcererSheet().toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            inbox.next("hello");
            for (String choice : List.of("bonus", "reach", "target")) {
                ws.sendText("""
                        {"type":"roll","actorId":"sorcerer","source":"spellcast,action","kind":"duality","expression":"2d12+3"}
                        """, true).join();
                JsonNode roll = inbox.next("roll");
                ObjectNode request = MAPPER.createObjectNode().put("actorId", "sorcerer").put("key", "feat:Manipulate Magic")
                        .put("actionId", "action");
                request.putObject("roll").set("id", roll.path("id"));
                request.withObject("/roll").set("ts", roll.path("ts"));
                assertEquals(400, featureHttp(cookie, request).statusCode());
                request.put("choiceId", choice);
                var response = featureHttp(cookie, request);
                assertEquals(200, response.statusCode(), response.body());
                JsonNode updated = inbox.next("rollUpdate");
                assertEquals(roll.path("rolls"), updated.path("rolls"));
                assertEquals(roll.path("total").asInt() + ("bonus".equals(choice) ? 2 : 0), updated.path("total").asInt());
                String report = inbox.next("chat").path("text").asText();
                if ("bonus".equals(choice)) {
                    assertTrue(report.contains("Added 2 to the original roll."));
                    assertFalse(report.contains("2 = 2"));
                }
                request.put("choiceId", "target".equals(choice) ? "bonus" : "target");
                assertEquals(400, featureHttp(cookie, request).statusCode());
            }
            assertEquals(6, MAPPER.readTree(store.loadSheet(campaign.getId(), "sorcerer")).path("stress").path("on").asInt());
        } finally { ws.abort(); }
    }

    @Test
    void enchantedAidSwapsAllySpellcastDiceIndependentlyOfHelpAndOnlyOncePerLongRest() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("sorcerer").name("Sorcerer").ownerId(playerId()).build());
        campaign.getActors().add(Campaign.Actor.builder().id("ally").name("Ally").ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), "sorcerer", sorcererSheet().toString());
        store.saveSheet(campaign.getId(), "ally", communitySheet("Seaborne").toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            inbox.next("hello");
            JsonNode roll;
            do {
                ws.sendText("""
                        {"type":"roll","actorId":"ally","source":"spellcast,action","kind":"duality","expression":"2d12"}
                        """, true).join();
                roll = inbox.next("roll");
            } while (roll.path("rolls").path(0).equals(roll.path("rolls").path(1)));
            ObjectNode request = MAPPER.createObjectNode().put("actorId", "sorcerer").put("key", "feat:Enchanted Aid").put("actionId", "swap");
            request.putObject("roll").set("id", roll.path("id"));
            request.withObject("/roll").set("ts", roll.path("ts"));
            var response = featureHttp(cookie, request);
            assertEquals(200, response.statusCode(), response.body());
            JsonNode update = inbox.next("rollUpdate");
            assertEquals(roll.path("rolls").path(0), update.path("rolls").path(1));
            assertEquals(roll.path("rolls").path(1), update.path("rolls").path(0));
            assertEquals(roll.path("total"), update.path("total"));
            JsonNode saved = MAPPER.readTree(store.loadSheet(campaign.getId(), "sorcerer"));
            assertEquals(1, saved.path("usage").path("feat:Enchanted Aid").path("used").asInt());
            assertEquals(5, saved.path("hope").path("on").asInt());
            assertEquals(3, saved.path("stress").path("on").asInt());
            assertEquals(400, featureHttp(cookie, request).statusCode());
            do {
                ws.sendText("""
                        {"type":"roll","actorId":"ally","source":"spellcast,action","kind":"duality","expression":"2d12"}
                        """, true).join();
                roll = inbox.next("roll");
            } while (roll.path("rolls").path(0).equals(roll.path("rolls").path(1)));
            request.withObject("/roll").set("id", roll.path("id"));
            request.withObject("/roll").set("ts", roll.path("ts"));
            assertEquals(400, featureHttp(cookie, request).statusCode());
        } finally { ws.abort(); }
    }

    @Test
    void sacredResonanceDoublesMatchingBonusDiceAndKeepsBreakdownConsistent() throws Exception {
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie(), inbox);
        try {
            assertTrue(inbox.next("hello").path("seraphAutomation").asBoolean());
            ObjectNode msg = MAPPER.createObjectNode().put("type", "roll").put("expression", "2d1+1d1+9").put("source", "damage");
            msg.putObject("damageRules").put("matchingDice", true);
            var contributions = msg.putArray("contributions");
            contributions.addObject().put("label", "Weapon").put("expression", "2d1");
            contributions.addObject().put("label", "Bonus dice").put("expression", "1d1");
            contributions.addObject().put("label", "Flat modifier").put("expression", "9");
            ws.sendText(msg.toString(), true).join();
            JsonNode result = inbox.next("roll");
            assertEquals(15, result.path("total").asInt());
            assertEquals(2, result.path("breakdown").path(0).path("total").asInt());
            assertEquals(1, result.path("breakdown").path(1).path("total").asInt());
            assertEquals(9, result.path("breakdown").path(2).path("total").asInt());
            assertEquals("Sacred Resonance", result.path("breakdown").path(3).path("label").asText());
            assertEquals(3, result.path("breakdown").path(3).path("total").asInt());
            assertEquals(1, result.path("rolls").path(0).asInt());
            assertFalse(result.path("breakdown").path(1).has("notes"));
            contributions.addObject().put("label", "Divine Wielder - Sacred Resonance").put("expression", "0");
            ws.sendText(msg.toString(), true).join();
            result = inbox.next("roll");
            assertEquals(4, result.path("breakdown").size());
            assertEquals("Divine Wielder - Sacred Resonance", result.path("breakdown").path(3).path("label").asText());
            assertEquals(3, result.path("breakdown").path(3).path("total").asInt());
            int sum = 0;
            for (JsonNode detail : result.path("breakdown")) sum += detail.path("total").asInt();
            assertEquals(result.path("total").asInt(), sum);
            msg.remove("damageRules");
            ws.sendText(msg.toString(), true).join();
            assertEquals(12, inbox.next("roll").path("total").asInt());
            msg.put("expression", "4d8");
            msg.putObject("damageRules").put("matchingDice", true);
            contributions.removeAll();
            contributions.addObject().put("label", "Weapon").put("expression", "4d8");
            contributions.addObject().put("label", "Divine Wielder - Sacred Resonance").put("expression", "0");
            diceSeed(25332);
            ws.sendText(msg.toString(), true).join();
            result = inbox.next("roll");
            assertEquals(MAPPER.readTree("[4,4,3,2]"), result.path("rolls"));
            assertEquals(13, result.path("breakdown").path(0).path("total").asInt());
            assertEquals(8, result.path("breakdown").path(1).path("total").asInt());
            assertEquals(21, result.path("total").asInt());
            assertEquals(result, store.historyTail(campaign.getId(), 1).getLast());

            msg.put("expression", "(4d8)*2");
            ((ObjectNode) contributions.get(0)).put("expression", "(4d8)*2");
            ((ObjectNode) contributions.get(1)).put("expression", "(0)*2");
            diceSeed(25332);
            ws.sendText(msg.toString(), true).join();
            result = inbox.next("roll");
            assertEquals(26, result.path("breakdown").path(0).path("total").asInt());
            assertEquals(16, result.path("breakdown").path(1).path("total").asInt());
            assertEquals(42, result.path("total").asInt());
        } finally { ws.abort(); }
    }

    @Test
    void prayerDiceSpendOnPlainAndAllyRollsOnceAndExpireWithSession() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("seraph").name("Seraph").ownerId(playerId()).build());
        ObjectNode sheet = seraphSheet("Divine Wielder");
        var effects = sheet.withArray("effects");
        for (int i = 0; i < 2; i++)
            effects.addObject().put("id", "pool-prayer-" + i).put("name", "Prayer Dice " + i + " (3)")
                    .put("dicePool", "prayer").put("kind", "buff").put("until", "session")
                    .putObject("counter").put("value", 3).put("max", 4);
        store.saveSheet(campaign.getId(), "seraph", sheet.toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            inbox.next("hello");
            ws.sendText("{\"type\":\"roll\",\"expression\":\"1d1\"}", true).join();
            JsonNode roll = inbox.next("roll");
            ObjectNode request = MAPPER.createObjectNode().put("actorId", "seraph").put("key", "effect:pool-prayer-0").put("actionId", "spend");
            request.putObject("roll").put("id", roll.path("id").asLong()).put("ts", roll.path("ts").asLong());
            var response = featureHttp(cookie, request);
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(4, inbox.next("rollUpdate").path("total").asInt());
            assertEquals(1, MAPPER.readTree(store.loadSheet(campaign.getId(), "seraph")).path("effects").size());
            assertEquals(400, featureHttp(cookie, request).statusCode());
            campaign.getActors().add(Campaign.Actor.builder().id("ally").name("Ally").ownerId(playerId()).build());
            ws.sendText("{\"type\":\"roll\",\"expression\":\"1d1\",\"source\":\"damage\",\"actorId\":\"ally\"}", true).join();
            roll = inbox.next("roll");
            request.put("key", "effect:pool-prayer-1");
            request.putObject("roll").put("id", roll.path("id").asLong()).put("ts", roll.path("ts").asLong());
            response = featureHttp(cookie, request);
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(4, inbox.next("rollUpdate").path("total").asInt());
            sheet = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "seraph"));
            sheet.withArray("effects").addObject().put("id", "pool-prayer-session").put("name", "Prayer Dice")
                    .put("dicePool", "prayer").put("kind", "buff").put("until", "session");
            sheet.withObject("/usage").putObject("feat:Prayer Dice").put("used", 1).put("per", "session");
            store.saveSheet(campaign.getId(), "seraph", sheet.toString());
            assertNull(server.newSession());
            assertEquals(0, MAPPER.readTree(store.loadSheet(campaign.getId(), "seraph")).path("effects").size());
            assertFalse(MAPPER.readTree(store.loadSheet(campaign.getId(), "seraph")).path("usage").has("feat:Prayer Dice"));
        } finally { ws.abort(); }
    }

    @Test
    void etherealVisageRemovesFearInsteadOfHopeAndCannotClaimBothOrUseReactions() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("reward-actor").name("Seraph").ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), "reward-actor", seraphSheet("Winged Sentinel").toString());
        campaign.setFear(3);
        store.save(campaign);
        String cookie = cookie();
        ObjectNode roll = rewardRoll(5500, 10, 3);
        roll.put("source", "trait:Presence,action");
        store.replaceHistoryEntry(campaign.getId(), 5500, 1234, roll);
        ObjectNode request = MAPPER.createObjectNode().put("actorId", "reward-actor").put("reward", "removeFear");
        request.putObject("roll").put("id", 5500).put("ts", 1234);
        var response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/roll-reward"))
                .header("Cookie", cookie).POST(HttpRequest.BodyPublishers.ofString(request.toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(2, campaign.getFear());
        assertEquals(2, new CampaignStore(store.root()).load(campaign.getId()).getFear());
        assertEquals(5, MAPPER.readTree(store.loadSheet(campaign.getId(), "reward-actor")).path("hope").path("on").asInt());
        assertTrue(store.historyTail(campaign.getId(), 10).getLast().path("text").asText()
                .contains("Removed 1 GM Fear instead of gaining Hope (GM Fear: 2)."));
        assertEquals(400, rewardHttp(cookie, "reward-actor", roll).statusCode());
        for (String source : List.of("trait:Presence,reaction", "trait:Agility,action", "trait:Presence,action")) {
            roll = rewardRoll(5501, 10, 3);
            roll.put("source", source);
            if (source.endsWith("Presence,action")) roll.put("difficulty", 99);
            store.replaceHistoryEntry(campaign.getId(), 5501, 1234, roll);
            request.withObject("/roll").put("id", 5501);
            response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/roll-reward"))
                    .header("Cookie", cookie).POST(HttpRequest.BodyPublishers.ofString(request.toString())).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, response.statusCode(), response.body());
            assertEquals(2, campaign.getFear());
        }
    }

    @Test
    void devoutSparingTouchHasTwoSharedUsesAndReportsSelfRecipient() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("seraph").name("Seraph").ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), "seraph", seraphSheet("Divine Wielder").toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            inbox.next("hello");
            ObjectNode request = MAPPER.createObjectNode().put("actorId", "seraph").put("key", "feat:Sparing Touch")
                    .put("actionId", "hp").put("targetActorId", "seraph");
            var response = featureHttp(cookie, request);
            assertEquals(200, response.statusCode(), response.body());
            assertTrue(store.historyTail(campaign.getId(), 10).getLast().path("text").asText().contains("Seraph: Cleared 2 HP"));
            request.put("actionId", "stress");
            response = featureHttp(cookie, request);
            assertEquals(200, response.statusCode(), response.body());
            assertTrue(store.historyTail(campaign.getId(), 10).getLast().path("text").asText().contains("Seraph: Cleared 2 Stress"));
            assertEquals(400, featureHttp(cookie, request).statusCode());
            campaign.getActors().add(Campaign.Actor.builder().id("healed").name("Healed Ally").ownerId(playerId()).build());
            store.saveSheet(campaign.getId(), "seraph", seraphSheet("Divine Wielder").toString());
            store.saveSheet(campaign.getId(), "healed", seraphSheet("Divine Wielder").toString());
            request.put("targetActorId", "healed");
            response = featureHttp(cookie, request);
            assertEquals(200, response.statusCode(), response.body());
            assertTrue(store.historyTail(campaign.getId(), 10).getLast().path("text").asText().contains("Healed Ally: Cleared 2 Stress"));
        } finally { ws.abort(); }
    }

    @Test
    void contactsDieBonusConsumesOneEffectUpdatesTheRollAndAwardsAdjustedCriticals() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("rogue").name("Rogue").ownerId(playerId()).build());
        ObjectNode sheet = communitySheet("Seaborne");
        sheet.putArray("effects");
        var features = sheet.putArray("features");
        JsonNode classes = MAPPER.readTree(compendiumDir.resolve("classes.json").toFile());
        for (JsonNode entry : classes.path("entries")) {
            if (!"rogue-syndicate-specialization".equals(entry.path("id").asText())) continue;
            for (JsonNode feature : entry.path("features")) features.add(feature);
        }
        JsonNode contact = features.get(0);
        ObjectNode effect = contact.path("automation").path("actions").path(0).path("steps").path(0).path("effect").deepCopy();
        effect.put("id", "contact");
        sheet.withArray("effects").add(effect);
        store.saveSheet(campaign.getId(), "rogue", sheet.toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            assertTrue(inbox.next("hello").path("dualityDieBonus").asBoolean());
            diceSeed(42);
            JsonNode roll = null;
            for (int i = 0; i < 200; i++) {
                ws.sendText("""
                        {"type":"roll","kind":"duality","expression":"2d12","source":"action,trait:Presence","actorId":"rogue"}
                        """, true).join();
                JsonNode candidate = inbox.next("roll");
                if (Math.abs(candidate.path("rolls").path(0).asInt() - candidate.path("rolls").path(1).asInt()) == 3) {
                    roll = candidate;
                    break;
                }
            }
            assertNotNull(roll);
            int hope = roll.path("rolls").path(0).asInt(), fear = roll.path("rolls").path(1).asInt();
            String chosen = hope < fear ? "hope" : "fear";
            ObjectNode request = MAPPER.createObjectNode().put("actorId", "rogue")
                    .put("key", "effect:contact").put("actionId", chosen);
            request.putObject("roll").put("id", roll.path("id").asLong()).put("ts", roll.path("ts").asLong());
            var response = featureHttp(cookie, request);
            assertEquals(200, response.statusCode(), response.body());
            JsonNode update = inbox.next("rollUpdate");
            assertEquals(update.path("rolls").path(0), update.path("rolls").path(1));
            assertEquals(roll.path("total").asInt() + 3, update.path("total").asInt());
            assertEquals(0, MAPPER.readTree(store.loadSheet(campaign.getId(), "rogue")).path("effects").size());
            request.put("actionId", "hope".equals(chosen) ? "fear" : "hope");
            assertEquals(400, featureHttp(cookie, request).statusCode());
            response = rewardHttp(cookie, "rogue", roll);
            assertEquals(200, response.statusCode(), response.body());
            JsonNode saved = MAPPER.readTree(store.loadSheet(campaign.getId(), "rogue"));
            assertEquals(6, saved.path("hope").path("on").asInt());
            assertEquals(2, saved.path("stress").path("on").asInt());
            ObjectNode reactivated = (ObjectNode) saved;
            reactivated.withArray("effects").add(effect);
            reactivated.withObject("/hope").put("on", 5);
            store.saveSheet(campaign.getId(), "rogue", reactivated.toString());
            assertEquals(400, featureHttp(cookie, request).statusCode());
            roll = null;
            for (int i = 0; i < 200; i++) {
                ws.sendText("""
                        {"type":"roll","kind":"duality","expression":"2d12","source":"action","actorId":"rogue"}
                        """, true).join();
                JsonNode candidate = inbox.next("roll");
                if (candidate.path("rolls").path(0).asInt() >= 10
                        && candidate.path("rolls").path(0).asInt() != candidate.path("rolls").path(1).asInt()) {
                    roll = candidate;
                    break;
                }
            }
            assertNotNull(roll);
            request.put("actionId", "hope");
            request.putObject("roll").put("id", roll.path("id").asLong()).put("ts", roll.path("ts").asLong());
            response = featureHttp(cookie, request);
            assertEquals(200, response.statusCode(), response.body());
            update = inbox.next("rollUpdate");
            assertTrue(update.path("rolls").path(0).asInt() > 12);
            response = rewardHttp(cookie, "rogue", roll);
            assertEquals(200, response.statusCode(), response.body());
        } finally { ws.abort(); }
    }

    @Test
    void unstoppableAdvancesOncePerDamageRollEndsOnOverflowAndSceneEnd() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("guardian").name("Guardian").ownerId(playerId()).build());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            assertTrue(inbox.next("hello").path("guardianRolls").asBoolean());
            for (int level : List.of(1, 5)) {
                ObjectNode sheet = guardianSheet(level);
                guardianBuff(sheet, "Unstoppable");
                sheet.withObject("/usage").putObject("feat:Unstoppable").put("used", 1).put("per", "longRest");
                int max = level == 1 ? 4 : 6;
                ((ObjectNode) sheet.path("effects").path(0).path("counter")).put("value", max - 1);
                store.saveSheet(campaign.getId(), "guardian", sheet.toString());
                for (int value = max; value <= max + 1; value++) {
                    ws.sendText("""
                            {"type":"roll","expression":"1d6","source":"damage","actorId":"guardian","label":"Damage"}
                            """, true).join();
                    JsonNode roll = inbox.next("roll");
                    ObjectNode request = MAPPER.createObjectNode().put("actorId", "guardian")
                            .put("key", "feat:Unstoppable").put("actionId", "advance");
                    request.putObject("roll").put("id", roll.path("id").asLong()).put("ts", roll.path("ts").asLong());
                    var response = featureHttp(cookie, request);
                    assertEquals(200, response.statusCode(), response.body());
                    JsonNode saved = MAPPER.readTree(store.loadSheet(campaign.getId(), "guardian"));
                    assertEquals(1, saved.path("usage").path("feat:Unstoppable").path("used").asInt());
                    if (value <= max) assertEquals(value, saved.path("effects").path(0).path("counter").path("value").asInt());
                    else assertEquals(0, saved.path("effects").size());
                    assertEquals(400, featureHttp(cookie, request).statusCode());
                }
            }
            ObjectNode sheet = guardianSheet(5);
            guardianBuff(sheet, "Unstoppable");
            store.saveSheet(campaign.getId(), "guardian", sheet.toString());
            server.endScene();
            assertEquals(0, MAPPER.readTree(store.loadSheet(campaign.getId(), "guardian")).path("effects").size());
            ws.sendText("""
                    {"type":"roll","expression":"1d6","source":"damage","actorId":"guardian","label":"Damage"}
                    """, true).join();
            JsonNode roll = inbox.next("roll");
            ObjectNode request = MAPPER.createObjectNode().put("actorId", "guardian")
                    .put("key", "feat:Unstoppable").put("actionId", "advance");
            request.putObject("roll").put("id", roll.path("id").asLong()).put("ts", roll.path("ts").asLong());
            assertEquals(400, featureHttp(cookie, request).statusCode());
        } finally { ws.abort(); }
    }

    @Test
    void nemesisSwapsActualAttackDiceAndBreakdownOnceWithAnActiveBuff() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("guardian").name("Guardian").ownerId(playerId()).build());
        ObjectNode sheet = guardianSheet(10);
        guardianBuff(sheet, "Nemesis");
        store.saveSheet(campaign.getId(), "guardian", sheet.toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            JsonNode roll = null;
            for (int i = 0; i < 20; i++) {
                ws.sendText("""
                        {"type":"roll","kind":"duality","expression":"2d12+2","source":"attack,action","actorId":"guardian",
                        "contributions":[{"label":"Hope","expression":"1d12"},{"label":"Fear","expression":"1d12"},
                        {"label":"Modifier","expression":"2"}]}
                        """, true).join();
                JsonNode candidate = inbox.next("roll");
                if (candidate.path("rolls").path(0).asInt() != candidate.path("rolls").path(1).asInt()) { roll = candidate; break; }
            }
            assertNotNull(roll);
            ObjectNode request = MAPPER.createObjectNode().put("actorId", "guardian")
                    .put("key", "feat:Nemesis").put("actionId", "swap");
            request.putObject("roll").put("id", roll.path("id").asLong()).put("ts", roll.path("ts").asLong());
            var response = featureHttp(cookie, request);
            assertEquals(200, response.statusCode(), response.body());
            JsonNode update = inbox.next("rollUpdate");
            assertEquals(roll.path("rolls").path(1), update.path("rolls").path(0));
            assertEquals(roll.path("rolls").path(0), update.path("rolls").path(1));
            assertEquals(roll.path("total"), update.path("total"));
            assertEquals(roll.path("rolls").path(1), update.path("breakdown").path(0).path("rolls").path(0));
            assertEquals(update.path("rolls").path(0).asInt(), update.path("breakdown").path(0).path("total").asInt());
            assertEquals(update.path("rolls").path(0).asInt() > update.path("rolls").path(1).asInt() ? "hope" : "fear",
                    update.path("outcome").asText());
            assertEquals(400, featureHttp(cookie, request).statusCode());
            JsonNode savedRoll = store.historyTail(campaign.getId(), 50).stream()
                    .filter(entry -> entry.path("id").asLong() == update.path("id").asLong()
                            && entry.path("ts").asLong() == update.path("ts").asLong()).findFirst().orElseThrow();
            assertEquals(update.path("rolls"), savedRoll.path("rolls"));
            sheet = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "guardian"));
            assertEquals(5, sheet.path("hope").path("on").asInt());
            sheet.putArray("effects");
            store.saveSheet(campaign.getId(), "guardian", sheet.toString());
            ws.sendText("""
                    {"type":"roll","kind":"duality","expression":"2d12","source":"attack,action","actorId":"guardian"}
                    """, true).join();
            JsonNode next = inbox.next("roll");
            request.putObject("roll").put("id", next.path("id").asLong()).put("ts", next.path("ts").asLong());
            assertEquals(400, featureHttp(cookie, request).statusCode());
        } finally { ws.abort(); }
    }

    @Test
    void shadowedGritRejectsReactionsButAllowsFearActionRolls() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("hunter").name("Hunter").ownerId(playerId()).build());
        ObjectNode sheet = communitySheet("Seaborne");
        sheet.put("className", "Blood Hunter").put("subclass", "Order of the Ghost Slayer");
        var features = sheet.putArray("features");
        for (JsonNode entry : MAPPER.readTree(compendiumDir.resolve("classes.json").toFile()).path("entries"))
            if ("blood-hunter-order-of-the-ghost-slayer-foundation".equals(entry.path("id").asText()))
                features.addAll((com.fasterxml.jackson.databind.node.ArrayNode) entry.path("features").deepCopy());
        sheet.putArray("effects");
        store.saveSheet(campaign.getId(), "hunter", sheet.toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            long seed = 0;
            while (true) {
                var random = new java.util.Random(seed);
                if (random.nextInt(12) < random.nextInt(12)) break;
                seed++;
            }
            for (String source : List.of("reaction,trait:Agility", "action,reaction,trait:Agility", "action,trait:Agility")) {
                diceSeed(seed);
                ObjectNode message = MAPPER.createObjectNode().put("type", "roll").put("kind", "duality")
                        .put("expression", "2d12").put("actorId", "hunter").put("source", source);
                ws.sendText(message.toString(), true).join();
                JsonNode roll = inbox.next("roll");
                ObjectNode request = MAPPER.createObjectNode().put("actorId", "hunter")
                        .put("key", "feat:Shadowed Grit").put("actionId", "hope");
                request.putObject("roll").put("id", roll.path("id").asLong()).put("ts", roll.path("ts").asLong());
                var response = featureHttp(cookie, request);
                assertEquals(source.contains("reaction") ? 400 : 200, response.statusCode(), response.body());
                JsonNode saved = MAPPER.readTree(store.loadSheet(campaign.getId(), "hunter"));
                assertEquals(source.contains("reaction") ? 3 : 4, saved.path("stress").path("on").asInt());
                assertEquals(source.contains("reaction") ? 5 : 6, saved.path("hope").path("on").asInt());
            }
        } finally { ws.abort(); }
    }

    @Test
    void grimPsychometryRequiresSuccessfulFeatureRollAndChargesStressOnce() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("hunter").name("Hunter").ownerId(playerId()).build());
        ObjectNode sheet = communitySheet("Seaborne");
        sheet.put("className", "Blood Hunter");
        var features = sheet.putArray("features");
        for (JsonNode entry : MAPPER.readTree(compendiumDir.resolve("classes.json").toFile()).path("entries"))
            if ("blood-hunter".equals(entry.path("id").asText()))
                features.addAll((com.fasterxml.jackson.databind.node.ArrayNode) entry.path("features").deepCopy());
        sheet.putArray("effects");
        store.saveSheet(campaign.getId(), "hunter", sheet.toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            long unequalSeed = 0;
            while (true) {
                var random = new java.util.Random(unequalSeed);
                if (random.nextInt(12) != random.nextInt(12)) break;
                unequalSeed++;
            }
            for (String expression : List.of("2d12-100", "2d12+100")) {
                diceSeed(unequalSeed);
                ObjectNode message = MAPPER.createObjectNode().put("type", "roll").put("kind", "duality")
                        .put("expression", expression).put("actorId", "hunter")
                        .put("source", "action,trait:Agility,spellcast,feat:Grim Psychometry").put("difficulty", 12);
                ws.sendText(message.toString(), true).join();
                JsonNode roll = inbox.next("roll");
                ObjectNode request = MAPPER.createObjectNode().put("actorId", "hunter")
                        .put("key", "feat:Grim Psychometry").put("actionId", "vision");
                request.putObject("roll").put("id", roll.path("id").asLong()).put("ts", roll.path("ts").asLong());
                var response = featureHttp(cookie, request);
                if (expression.contains("-")) {
                    assertEquals(400, response.statusCode(), response.body());
                    assertTrue(response.body().contains("successful"));
                    assertEquals(3, MAPPER.readTree(store.loadSheet(campaign.getId(), "hunter")).path("stress").path("on").asInt());
                } else {
                    assertEquals(200, response.statusCode(), response.body());
                    JsonNode saved = MAPPER.readTree(store.loadSheet(campaign.getId(), "hunter"));
                    assertEquals(4, saved.path("stress").path("on").asInt());
                    assertEquals("Grim Psychometry", saved.path("effects").path(0).path("name").asText());
                    assertEquals(400, featureHttp(cookie, request).statusCode());
                }
            }
            long criticalSeed = 0;
            while (true) {
                var random = new java.util.Random(criticalSeed);
                if (random.nextInt(12) == random.nextInt(12)) break;
                criticalSeed++;
            }
            diceSeed(criticalSeed);
            ws.sendText("""
                    {"type":"roll","kind":"duality","expression":"2d12-100","actorId":"hunter",
                     "source":"action,spellcast,feat:Grim Psychometry","difficulty":12}
                    """, true).join();
            JsonNode critical = inbox.next("roll");
            ObjectNode claim = MAPPER.createObjectNode().put("actorId", "hunter")
                    .put("key", "feat:Grim Psychometry").put("actionId", "vision");
            claim.putObject("roll").put("id", critical.path("id").asLong()).put("ts", critical.path("ts").asLong());
            assertEquals(200, featureHttp(cookie, claim).statusCode());
        } finally {
            ws.abort();
        }
    }

    @Test
    void volatileToxinsBlocksHealingAndManualRemovalButReleasesOnEitherRest() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("mutant").name("Mutant").ownerId(playerId()).build());
        String cookie = cookie();
        for (boolean longRest : List.of(false, true)) {
            ObjectNode sheet = communitySheet("Seaborne");
            sheet.put("permanentHp", 1);
            sheet.putArray("effects").addObject().put("id", "locked").put("name", "Volatile Toxins")
                    .put("kind", "debuff").put("until", "rest").put("lockedHp", 1);
            store.saveSheet(campaign.getId(), "mutant", sheet.toString());
            assertEquals(2, DeathMoves.recoverableHp(sheet));
            assertEquals(2, DeathMoves.clearHp(sheet, 99));
            assertEquals(2, sheet.path("hp").path("on").asInt());
            sheet.withObject("/hp").put("on", 0);
            var save = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/sheet/mutant"))
                    .header("Cookie", cookie);
            var healed = http.send(save.PUT(HttpRequest.BodyPublishers.ofString(sheet.toString())).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, healed.statusCode(), healed.body());
            ObjectNode saved = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "mutant"));
            assertEquals(2, saved.path("hp").path("on").asInt());
            saved.putArray("effects");
            var removed = http.send(save.PUT(HttpRequest.BodyPublishers.ofString(saved.toString())).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, removed.statusCode(), removed.body());
            assertTrue(removed.body().contains("locked until"));
            if (longRest) saved.put("longRestRevision", 1);
            else saved.put("shortRestsInRow", 1);
            saved.withObject("/hp").put("on", 1);
            var rested = http.send(save.PUT(HttpRequest.BodyPublishers.ofString(saved.toString())).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, rested.statusCode(), rested.body());
            JsonNode afterRest = MAPPER.readTree(store.loadSheet(campaign.getId(), "mutant"));
            assertEquals(1, afterRest.path("hp").path("on").asInt());
            assertEquals(1, afterRest.path("permanentHp").asInt());
            assertEquals(0, afterRest.path("effects").size());
        }
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
            assertEquals(before, MAPPER.readTree(store.loadSheet(campaign.getId(), "ally")));
            assertNull(server.newSession());
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
                JsonNode message = inbox.nextBenefit();
                assertTrue(message.path("text").asText().contains("Cleared 1 HP."), message.toString());
                assertFalse(message.path("text").asText().contains("(1 = 1)"), message.toString());
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
            JsonNode song = inbox.nextBenefit();
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
            JsonNode speech = inbox.nextBenefit();
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
            assertTrue(inbox.next("chat").path("text").asText().contains("Added 1d4 = " + extra + " to the original roll."));
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
    void thriveInChaosRequiresDamagePaysStressAndPostsOneFeatureReport() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("wizard").name("Wizard").ownerId(playerId()).build());
        ObjectNode sheet = communitySheet("Seaborne");
        sheet.put("className", "Wizard").put("subclass", "School of War");
        var features = sheet.putArray("features");
        for (JsonNode entry : MAPPER.readTree(compendiumDir.resolve("classes.json").toFile()).path("entries")) {
            if ("wizard-school-of-war-mastery".equals(entry.path("id").asText()))
                for (JsonNode feature : entry.path("features")) {
                    ObjectNode learned = feature.deepCopy();
                    learned.put("source", "Subclass mastery - School of War");
                    features.add(learned);
                }
        }
        store.saveSheet(campaign.getId(), "wizard", sheet.toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            ObjectNode request = MAPPER.createObjectNode().put("actorId", "wizard")
                    .put("key", "feat:Thrive in Chaos").put("actionId", "use");
            ws.sendText("{\"type\":\"roll\",\"kind\":\"duality\",\"expression\":\"2d12\",\"source\":\"attack,action\",\"actorId\":\"wizard\"}", true).join();
            JsonNode attack = inbox.next("roll");
            request.set("roll", MAPPER.createObjectNode().put("id", attack.path("id").asLong()).put("ts", attack.path("ts").asLong()));
            assertEquals(400, featureHttp(cookie, request).statusCode());
            assertEquals(3, MAPPER.readTree(store.loadSheet(campaign.getId(), "wizard")).path("stress").path("on").asInt());
            ws.sendText("{\"type\":\"roll\",\"expression\":\"1d8\",\"source\":\"damage\",\"actorId\":\"wizard\",\"toId\":\"dm\"}", true).join();
            JsonNode damage = inbox.next("roll");
            request.set("roll", MAPPER.createObjectNode().put("id", damage.path("id").asLong()).put("ts", damage.path("ts").asLong()));
            var response = featureHttp(cookie, request);
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(4, MAPPER.readTree(store.loadSheet(campaign.getId(), "wizard")).path("stress").path("on").asInt());
            JsonNode report = inbox.next("chat");
            assertEquals("dm", report.path("toId").asText());
            assertEquals("Thrive in Chaos", report.path("rollSource").path("name").asText());
            assertTrue(report.path("rollSource").path("text").asText().contains("additional Hit Point"));
            assertEquals(400, featureHttp(cookie, request).statusCode());
            assertEquals(4, MAPPER.readTree(store.loadSheet(campaign.getId(), "wizard")).path("stress").path("on").asInt());
            assertTrue(new CampaignStore(store.root()).historyTail(campaign.getId(), 50).contains(report));
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
        assertEquals(2, MAPPER.readTree(store.loadSheet(campaign.getId(), "source")).path("resources").path("tide").asInt());
        assertNull(server.newSession());
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
            assertEquals(3, MAPPER.readTree(new CampaignStore(store.root()).loadSheet(campaign.getId(), "source")).path("resources").path("tide").asInt());
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
        assertEquals(3, MAPPER.readTree(store.loadSheet(campaign.getId(), "source")).path("resources").path("tide").asInt());
        assertNull(server.newSession());
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
    void dmCatalogEditsRequireTheHostAndRefreshExistingSheetsWithoutResettingThem() throws Exception {
        String token = dmCompendiumData();
        server.publish(state("m1", 0), "m1/l1");
        Path file = compendiumDir.resolve("items.json");
        ObjectNode document = (ObjectNode) MAPPER.readTree(file.toFile());
        document.put("format", "dmmap-daggerheart").put("version", 1);
        java.nio.file.Files.writeString(file, document.toString());
        ObjectNode savedSheet = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "target"));
        CompendiumEntries.add(savedSheet, "items", document.path("entries").get(0));
        savedSheet.putObject("hope").put("slots", 6).put("on", 2);
        savedSheet.putObject("usage").putObject("item:Host torch").put("used", 1).put("per", "session");
        store.saveSheet(campaign.getId(), "target", savedSheet.toString());
        String url = "http://127.0.0.1:" + port + "/api/dm/catalog/items";
        assertEquals(403, http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        String cookie = cookie();
        assertEquals(403, http.send(HttpRequest.newBuilder(URI.create(url)).header("Cookie", cookie).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        var read = http.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, read.statusCode(), read.body());
        JsonNode loaded = MAPPER.readTree(read.body());
        ObjectNode entry = ((ObjectNode) loaded.path("file").path("entries").get(0)).deepCopy();
        entry.put("name", "Renamed torch").put("text", "New rules");
        entry.putObject("automation").putObject("uses").put("max", 2).put("per", "session");
        ObjectNode request = MAPPER.createObjectNode().put("revision", loaded.path("revision").asText()).put("existingId", "torch");
        request.set("entry", entry);
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            inbox.next("hello"); inbox.next("snapshot"); inbox.next("roster");
            var edit = http.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token)
                    .PUT(HttpRequest.BodyPublishers.ofString(request.toString())).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, edit.statusCode(), edit.body());
            assertEquals("catalogChanged", inbox.next("catalogChanged").path("type").asText());
            assertEquals("New rules", MAPPER.readTree(file.toFile()).path("entries").get(0).path("text").asText());
            assertEquals(409, http.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token)
                    .PUT(HttpRequest.BodyPublishers.ofString(request.toString())).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            var current = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/sheet/target"))
                    .header("Cookie", cookie).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, current.statusCode(), current.body());
            JsonNode sheet = MAPPER.readTree(current.body()).path("sheet");
            assertEquals("Renamed torch", sheet.path("inventory").get(0).path("name").asText());
            assertEquals("New rules", sheet.path("inventory").get(0).path("note").asText());
            assertEquals(2, sheet.path("inventory").get(0).path("automation").path("uses").path("max").asInt());
            assertEquals(1, sheet.path("usage").path("item:Renamed torch").path("used").asInt());
            assertEquals(2, sheet.path("hope").path("on").asInt());
            assertEquals("Keep me", sheet.path("notes").asText());
            var persist = saveDeathSheet(cookie, "target", (ObjectNode) sheet);
            assertEquals(200, persist.statusCode(), persist.body());
            assertEquals("Renamed torch", MAPPER.readTree(store.loadSheet(campaign.getId(), "target")).path("inventory").get(0).path("name").asText());
        } finally { ws.abort(); }
    }

    private HttpResponse<String> dmSheetHttp(String actorId, String token, JsonNode body, String version) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/sheet/" + actorId))
                .header("Authorization", "Bearer " + token);
        if (version != null) request.header("If-Match", version);
        if (body != null) request.header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body.toString()));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void actorAndSheetRenamesStayInSyncAndChangeFutureChat() throws Exception {
        Campaign.Actor actor = Campaign.Actor.builder().id("hero").name("Actor").ownerId(playerId()).build();
        campaign.getActors().add(actor);
        store.save(campaign);
        store.saveSheet(campaign.getId(), actor.getId(),
                "{\"name\":\"Old sheet\",\"description\":{\"Name\":\"Old sheet\"},\"notes\":\"Keep\"}");
        String cookie = cookie();
        String token = URI.create(server.dmSheetUrl(actor.getId())).getFragment();
        Inbox inbox = new Inbox();
        WebSocket socket = connect(cookie, inbox);
        try {
            inbox.next("hello");
            socket.sendText("{\"type\":\"chat\",\"text\":\"before\"}", true).join();
            assertEquals("Actor", inbox.next("chat").path("from").asText());
            JsonNode initial = MAPPER.readTree(dmSheetHttp("hero", token, null, null).body());
            assertEquals("Actor", initial.path("sheet").path("name").asText());
            assertEquals("Actor", initial.path("sheet").path("description").path("Name").asText());
            ObjectNode edit = initial.path("sheet").deepCopy();
            edit.put("name", "Player name");
            var response = saveDeathSheet(cookie, "hero", edit);
            assertEquals(200, response.statusCode(), response.body());
            assertEquals("Player name", store.load(campaign.getId()).actor("hero").getName());
            assertEquals("Player name", MAPPER.readTree(store.loadSheet(campaign.getId(), "hero"))
                    .path("description").path("Name").asText());
            socket.sendText("{\"type\":\"chat\",\"text\":\"after\"}", true).join();
            assertEquals("Player name", inbox.next("chat").path("from").asText());
            JsonNode playerSaved = MAPPER.readTree(response.body());
            server.renameActor("hero", "DM name");
            assertEquals("DM name", actor.getName());
            assertEquals("DM name", store.load(campaign.getId()).actor("hero").getName());
            JsonNode renamed = MAPPER.readTree(dmSheetHttp("hero", token, null, null).body());
            assertEquals("DM name", renamed.path("sheet").path("name").asText());
            assertEquals("DM name", renamed.path("sheet").path("description").path("Name").asText());
            assertEquals("Keep", renamed.path("sheet").path("notes").asText());
            assertEquals(409, dmSheetHttp("hero", token, edit, playerSaved.path("version").asText()).statusCode());
            socket.sendText("{\"type\":\"chat\",\"text\":\"DM renamed\"}", true).join();
            assertEquals("DM name", inbox.next("chat").path("from").asText());
            assertEquals("Actor", store.historyTail(campaign.getId(), 50).stream()
                    .filter(item -> "before".equals(item.path("text").asText())).findFirst().orElseThrow().path("from").asText());
            ObjectNode dmEdit = renamed.path("sheet").deepCopy();
            dmEdit.put("name", "Sheet DM name");
            dmEdit.put("longRestRevision", 1);
            assertEquals(200, dmSheetHttp("hero", token, dmEdit, renamed.path("version").asText()).statusCode());
            assertEquals("Sheet DM name", store.load(campaign.getId()).actor("hero").getName());
            assertEquals("Sheet DM name", MAPPER.readTree(store.loadSheet(campaign.getId(), "hero")).path("name").asText());
        } finally {
            socket.abort();
        }
    }

    @Test
    void renamingActorWithoutSavedSheetInvalidatesStaleSheetEdits() throws Exception {
        Campaign.Actor actor = Campaign.Actor.builder().id("new-hero").name("Before").ownerId(playerId()).build();
        campaign.getActors().add(actor);
        store.save(campaign);
        String token = URI.create(server.dmSheetUrl(actor.getId())).getFragment();
        JsonNode before = MAPPER.readTree(dmSheetHttp(actor.getId(), token, null, null).body());
        assertTrue(before.path("sheet").isNull());
        server.renameActor(actor.getId(), "After");
        assertEquals(Optional.empty(), changedActorSheets.get(actor.getId()));
        JsonNode after = MAPPER.readTree(dmSheetHttp(actor.getId(), token, null, null).body());
        assertTrue(after.path("sheet").isNull());
        assertEquals("After", after.path("name").asText());
        assertFalse(before.path("version").asText().equals(after.path("version").asText()));
        ObjectNode stale = MAPPER.createObjectNode().put("name", "Before");
        assertEquals(409, dmSheetHttp(actor.getId(), token, stale, before.path("version").asText()).statusCode());
        assertNull(store.loadSheet(campaign.getId(), actor.getId()));
    }

    @Test
    void disabledActorIsReadableButRejectsPlayerEditsAndRollsWhileDmCanEdit() throws Exception {
        dmCompendiumData();
        Campaign.Actor actor = campaign.actor("target");
        actor.setDisabled(true);
        store.save(campaign);
        assertTrue(store.load(campaign.getId()).actor("target").isDisabled());
        String cookie = cookie();
        String url = "http://localhost:" + port + "/api/sheet/target";
        var read = http.send(HttpRequest.newBuilder(URI.create(url)).header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, read.statusCode(), read.body());
        JsonNode response = MAPPER.readTree(read.body());
        assertTrue(response.path("disabled").asBoolean());
        ObjectNode edited = response.path("sheet").deepCopy();
        edited.put("notes", "Player edit");
        var save = http.send(HttpRequest.newBuilder(URI.create(url)).header("Cookie", cookie)
                .PUT(HttpRequest.BodyPublishers.ofString(edited.toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, save.statusCode());
        assertEquals(403, featureHttp(cookie, MAPPER.createObjectNode().put("actorId", "target")
                .put("key", "feat:Example").put("actionId", "use")).statusCode());
        String dmToken = URI.create(server.dmSheetUrl("target")).getFragment();
        edited.put("notes", "DM edit");
        assertEquals(200, dmSheetHttp("target", dmToken, edited, null).statusCode());
        assertEquals("DM edit", MAPPER.readTree(store.loadSheet(campaign.getId(), "target")).path("notes").asText());
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            inbox.next("hello");
            ws.sendText("{\"type\":\"roll\",\"actorId\":\"target\",\"expression\":\"1d6\"}", true).join();
            assertTrue(inbox.next("error").path("message").asText().contains("disabled"));
            actor.setDisabled(false);
            ws.sendText("{\"type\":\"roll\",\"actorId\":\"target\",\"expression\":\"1d6\"}", true).join();
            assertEquals("target", inbox.next("roll").path("actorId").asText());
        } finally { ws.abort(); }
        edited.put("notes", "Player edit after enabling");
        assertEquals(200, http.send(HttpRequest.newBuilder(URI.create(url)).header("Cookie", cookie)
                .PUT(HttpRequest.BodyPublishers.ofString(edited.toString())).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void dmSheetsAreActorScopedRevocableAndSynchronizeOfflineOwnerEdits() throws Exception {
        dmCompendiumData();
        String url = server.dmSheetUrl("target");
        String token = URI.create(url).getFragment();
        assertEquals(403, dmSheetHttp("target", "wrong", null, null).statusCode());
        assertEquals(403, dmSheetHttp("unowned", token, null, null).statusCode());
        assertThrows(IllegalArgumentException.class, () -> server.dmSheetUrl("unowned"));
        Inbox dm = new Inbox();
        WebSocket socket = http.newWebSocketBuilder().buildAsync(
                URI.create("ws://127.0.0.1:" + port + "/ws?sheet-sync=1&dm-sheet=" + token), dm).join();
        try {
            assertTrue(dm.next("hello").path("sheetSync").asBoolean());
            assertTrue(server.roster().isEmpty(), "Opening an offline owner's sheet must not connect that player");
            JsonNode initial = MAPPER.readTree(dmSheetHttp("target", token, null, null).body());
            ObjectNode edited = initial.path("sheet").deepCopy();
            edited.put("notes", "DM edit");
            var saved = dmSheetHttp("target", token, edited, initial.path("version").asText());
            assertEquals(200, saved.statusCode(), saved.body());
            assertEquals("target", dm.next("sheetChanged").path("actorId").asText());
            String version = MAPPER.readTree(saved.body()).path("version").asText();
            assertFalse(version.equals(initial.path("version").asText()));
            var stale = dmSheetHttp("target", token, edited.put("notes", "Stale"), initial.path("version").asText());
            assertEquals(409, stale.statusCode());
            assertEquals("DM edit", MAPPER.readTree(stale.body()).path("sheet").path("notes").asText());
            assertEquals("DM edit", MAPPER.readTree(store.loadSheet(campaign.getId(), "target")).path("notes").asText());
            edited.put("notes", "Player edit");
            var playerSave = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/sheet/target"))
                    .header("Cookie", cookie()).header("If-Match", version)
                    .PUT(HttpRequest.BodyPublishers.ofString(edited.toString())).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, playerSave.statusCode(), playerSave.body());
            assertEquals("target", dm.next("sheetChanged").path("actorId").asText());
            assertEquals("Player edit", MAPPER.readTree(dmSheetHttp("target", token, null, null).body()).path("sheet").path("notes").asText());
            socket.sendText("{\"type\":\"roll\",\"actorId\":\"unowned\",\"expression\":\"1d6\"}", true).join();
            assertTrue(dm.next("error").path("message").asText().contains("own character"));
            server.revokeDmSheet(url);
            assertEquals(403, dmSheetHttp("target", token, null, null).statusCode());
        } finally { socket.abort(); }
        String restartedToken = URI.create(server.dmSheetUrl("target")).getFragment();
        server.stop();
        server.start();
        assertEquals(403, dmSheetHttp("target", restartedToken, null, null).statusCode());
    }

    @Test
    void dmSheetArtworkUploadsRemainActorScopedAndRevocable() throws Exception {
        dmCompendiumData();
        String url = server.dmSheetUrl("target");
        String token = URI.create(url).getFragment();
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", bytes);
        byte[] image = bytes.toByteArray();
        var upload = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/actors/target/artwork"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "image/png")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(image)).build();
        campaign.actor("target").setDisabled(true);
        var saved = http.send(upload, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, saved.statusCode(), saved.body());
        assertFalse(MAPPER.readTree(saved.body()).path("artwork").asText().isBlank());
        assertNotNull(store.load(campaign.getId()).actor("target").getArtwork());
        var other = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/actors/unowned/artwork"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "image/png")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(image)).build();
        assertEquals(403, http.send(other, HttpResponse.BodyHandlers.ofString()).statusCode());
        server.revokeDmSheet(url);
        assertEquals(403, http.send(upload, HttpResponse.BodyHandlers.ofString()).statusCode());
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

    @Test
    void pummeljoyCriticalMeleeRewardsAreAtomicCappedAndClaimedOnlyOnce() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("reward-actor").name("Brawler").ownerId(playerId()).build());
        rewardSheet(2, 3);
        compendiumDir = Path.of("dmmap-daggerheart");
        ObjectNode sheet = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "reward-actor"));
        sheet.putArray("features").addObject().put("name", "Pummeljoy");
        sheet.putArray("activeWeapons").addObject().put("name", "Brawler's Strike").put("range", "Melee");
        store.saveSheet(campaign.getId(), "reward-actor", sheet.toString());
        String cookie = cookie();
        ObjectNode roll = rewardRoll(5500, 7, 7);
        roll.put("source", "attack,action").put("weapon", "Brawler's Strike");
        store.replaceHistoryEntry(campaign.getId(), 5500, 1234, roll);
        assertEquals(200, rewardHttp(cookie, "reward-actor", roll).statusCode());
        JsonNode updated = MAPPER.readTree(store.loadSheet(campaign.getId(), "reward-actor"));
        assertEquals(4, updated.path("hope").path("on").asInt());
        assertEquals(1, updated.path("stress").path("on").asInt());
        assertEquals(400, rewardHttp(cookie, "reward-actor", roll).statusCode());
        ObjectNode ranged = rewardRoll(5501, 7, 7);
        ranged.put("source", "attack,action").put("weapon", "Bow");
        store.replaceHistoryEntry(campaign.getId(), 5501, 1234, ranged);
        assertEquals(200, rewardHttp(cookie, "reward-actor", ranged).statusCode());
        updated = MAPPER.readTree(store.loadSheet(campaign.getId(), "reward-actor"));
        assertEquals(5, updated.path("hope").path("on").asInt());
        assertEquals(0, updated.path("stress").path("on").asInt());
    }

    private ObjectNode companionRewardSheet() throws Exception {
        compendiumDir = Path.of("dmmap-daggerheart");
        rewardSheet(1, 3);
        ObjectNode sheet = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "reward-actor"));
        var features = sheet.putArray("features");
        for (JsonNode entry : MAPPER.readTree(compendiumDir.resolve("classes.json").toFile()).path("entries"))
            if ("ranger-beastbound-foundation".equals(entry.path("id").asText()))
                for (JsonNode feature : entry.path("features")) features.add(feature.deepCopy());
        features.addObject().put("name", "Pummeljoy");
        ObjectNode companion = sheet.putObject("companion").put("name", "Ash").put("attack", "Bite").put("range", "Melee");
        companion.putObject("stress").put("on", 0).put("slots", 3);
        sheet.putArray("effects");
        sheet.putObject("usage");
        store.saveSheet(campaign.getId(), "reward-actor", sheet.toString());
        return sheet;
    }

    @Test
    void companionMeleeAttackInheritsOwnerCriticalBenefitsButNotWhenUnavailableOrOutOfRange() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("reward-actor").name("Ranger").ownerId(playerId()).build());
        companionRewardSheet();
        String cookie = cookie();
        ObjectNode roll = rewardRoll(5600, 7, 7);
        roll.put("source", "companion,attack,spellcast,action").put("weapon", "Ash: Bite");
        store.replaceHistoryEntry(campaign.getId(), 5600, 1234, roll);
        assertEquals(200, rewardHttp(cookie, "reward-actor", roll).statusCode());
        JsonNode saved = MAPPER.readTree(store.loadSheet(campaign.getId(), "reward-actor"));
        assertEquals(3, saved.path("hope").path("on").asInt());
        assertEquals(1, saved.path("stress").path("on").asInt());
        for (String state : List.of("range", "unavailable")) {
            ObjectNode sheet = companionRewardSheet();
            if ("range".equals(state)) ((ObjectNode) sheet.path("companion")).put("range", "Far");
            else ((ObjectNode) sheet.path("companion")).put("unavailable", true);
            store.saveSheet(campaign.getId(), "reward-actor", sheet.toString());
            long id = "range".equals(state) ? 5601 : 5602;
            ObjectNode other = rewardRoll(id, 7, 7);
            other.put("source", "companion,attack,spellcast,action").put("weapon", "Ash: Bite");
            store.replaceHistoryEntry(campaign.getId(), id, 1234, other);
            assertEquals(200, rewardHttp(cookie, "reward-actor", other).statusCode());
            saved = MAPPER.readTree(store.loadSheet(campaign.getId(), "reward-actor"));
            assertEquals(2, saved.path("hope").path("on").asInt());
            assertEquals(2, saved.path("stress").path("on").asInt());
        }
    }

    @Test
    void companionHopeCommandOffersOnePersistedAdvantageReactionAndRejectsUnavailableCompanions() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("reward-actor").name("Ranger").ownerId(playerId()).build());
        companionRewardSheet();
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            for (boolean unavailable : List.of(false, true)) {
                if (unavailable) {
                    ObjectNode sheet = companionRewardSheet();
                    ((ObjectNode) sheet.path("companion")).put("unavailable", true);
                    store.saveSheet(campaign.getId(), "reward-actor", sheet.toString());
                }
                ws.sendText("""
                        {"type":"roll","kind":"duality","expression":"2d12","source":"companion,spellcast,action","actorId":"reward-actor"}
                        """, true).join();
                ObjectNode roll = (ObjectNode) inbox.next("roll");
                roll.putArray("rolls").add(9).add(3);
                roll.put("total", 12);
                store.replaceHistoryEntry(campaign.getId(), roll.path("id").asLong(), roll.path("ts").asLong(), roll);
                ObjectNode request = MAPPER.createObjectNode().put("actorId", "reward-actor")
                        .put("key", "feat:Companion").put("actionId", "build-on-companion");
                request.putObject("roll").put("id", roll.path("id").asLong()).put("ts", roll.path("ts").asLong());
                HttpResponse<String> response = featureHttp(cookie, request);
                if (unavailable) {
                    assertEquals(400, response.statusCode());
                    assertTrue(response.body().contains("unavailable"), response.body());
                } else {
                    assertEquals(200, response.statusCode(), response.body());
                    JsonNode saved = MAPPER.readTree(store.loadSheet(campaign.getId(), "reward-actor"));
                    assertEquals("Build on companion success", saved.path("effects").path(0).path("name").asText());
                    assertEquals("advantage", saved.path("effects").path(0).path("rollOptions").path(0).path("advantage").asText());
                    assertEquals(400, featureHttp(cookie, request).statusCode());
                }
            }
        } finally { ws.abort(); }
    }

    @Test
    void brawlerDamageResolvesExactingThenUnmodifiedComboOnHostWithAccurateBreakdown() throws Exception {
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie(), inbox);
        try {
            assertTrue(inbox.next("hello").path("brawlerRolls").asBoolean());
            diceSeed(42);
            ObjectNode msg = MAPPER.createObjectNode().put("type", "roll").put("expression", "2d8+2d6+3").put("source", "damage");
            msg.putObject("damageRules").put("maximumOnes", true).put("comboDie", 4);
            msg.putArray("contributions").addObject().put("label", "Strike").put("expression", "2d8+2d6+3");
            ws.sendText(msg.toString(), true).join();
            JsonNode result = inbox.next("roll");
            int total = 0;
            for (JsonNode part : result.path("breakdown")) total += part.path("total").asInt();
            assertEquals(result.path("total").asInt(), total);
            assertEquals(2, result.path("breakdown").size());
            JsonNode combo = result.path("breakdown").path(1);
            int count = combo.path("rolls").size();
            assertTrue(count >= 2);
            assertTrue(combo.path("rolls").path(count - 1).asInt() < combo.path("rolls").path(count - 2).asInt());
            int sum = 0;
            for (JsonNode face : combo.path("rolls")) sum += face.asInt();
            assertEquals(sum, combo.path("total").asInt());
            msg.put("source", "attack").put("kind", "duality");
            ws.sendText(msg.toString(), true).join();
            assertTrue(inbox.next("error").toString().contains("only to damage"));
        } finally { ws.abort(); }
    }

    @Test
    void invigoratingChecksEachAttackOnceCapsFocusAndRequiresItsActiveStance() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("martial").name("Martial Artist").ownerId(playerId()).build());
        ObjectNode sheet = communitySheet("Seaborne");
        sheet.putObject("resources").put("focus", 5);
        var features = sheet.putArray("features");
        for (JsonNode entry : MAPPER.readTree(compendiumDir.resolve("classes.json").toFile()).path("entries"))
            if ("brawler-martial-artist-foundation".equals(entry.path("id").asText()))
                features.addAll((com.fasterxml.jackson.databind.node.ArrayNode) entry.path("features").deepCopy());
        sheet.putArray("effects").addObject().put("name", "Invigorating").put("id", "stance").put("stance", true);
        store.saveSheet(campaign.getId(), "martial", sheet.toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            long seed = 0;
            while (new java.util.Random(seed).nextInt(4) != 3) seed++;
            for (int i = 0; i < 2; i++) {
                ws.sendText("""
                        {"type":"roll","kind":"duality","expression":"2d12","source":"attack,action","actorId":"martial"}
                        """, true).join();
                JsonNode roll = inbox.next("roll");
                ObjectNode request = MAPPER.createObjectNode().put("actorId", "martial").put("key", "feat:Stance Fighter").put("actionId", "invigorating");
                request.putObject("roll").put("id", roll.path("id").asLong()).put("ts", roll.path("ts").asLong());
                diceSeed(seed);
                var response = featureHttp(cookie, request);
                assertEquals(200, response.statusCode(), response.body());
                assertEquals(6, MAPPER.readTree(store.loadSheet(campaign.getId(), "martial")).path("resources").path("focus").asInt());
                assertEquals(400, featureHttp(cookie, request).statusCode());
                inbox.next("roll"); // The host's d4 check report.
            }
            sheet = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "martial"));
            sheet.putArray("effects");
            store.saveSheet(campaign.getId(), "martial", sheet.toString());
            ws.sendText("""
                    {"type":"roll","kind":"duality","expression":"2d12","source":"attack,action","actorId":"martial"}
                    """, true).join();
            JsonNode roll = inbox.next("roll");
            ObjectNode request = MAPPER.createObjectNode().put("actorId", "martial").put("key", "feat:Stance Fighter").put("actionId", "invigorating");
            request.putObject("roll").put("id", roll.path("id").asLong()).put("ts", roll.path("ts").asLong());
            assertEquals(400, featureHttp(cookie, request).statusCode());
        } finally { ws.abort(); }
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
    void hpRecoveryReportsKeepOnlyMeaningfulBreakdowns() {
        assertEquals("Cleared 1 HP.", OnlineSessionServer.hpRecoveryReport(1, MAPPER.valueToTree(1), 1));
        assertEquals("Cleared 0 HP.", OnlineSessionServer.hpRecoveryReport(0, MAPPER.valueToTree(" 1 "), 1));
        assertEquals("Cleared 1 HP (1d4 = 3).", OnlineSessionServer.hpRecoveryReport(1, MAPPER.valueToTree("1d4"), 3));
        assertEquals("Cleared 1 HP (@level = 2).", OnlineSessionServer.hpRecoveryReport(1, MAPPER.valueToTree("@level"), 2));
    }

    @Test
    void arcanaTouchedSwapRequiresFourActiveCardsAndSharesRestLimit() throws Exception {
        ObjectNode caster = communitySheet("Seaborne");
        JsonNode card = java.util.stream.StreamSupport.stream(
                MAPPER.readTree(compendiumDir.resolve("domain-cards.json").toFile()).path("entries").spliterator(), false)
                .filter(item -> "arcana-touched".equals(item.path("id").asText())).findFirst().orElseThrow();
        var loadout = caster.putArray("loadout");
        loadout.add(card.deepCopy());
        loadout.addObject().put("name", "A").put("domain", "Arcana");
        loadout.addObject().put("name", "B").put("domain", "arcana");
        JsonNode fourth = MAPPER.createObjectNode().put("name", "C").put("domain", "Arcana");
        caster.putArray("vault").add(fourth);
        campaign.getActors().add(Campaign.Actor.builder().id("arcana").name("Caster").ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), "arcana", caster.toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket socket = connect(cookie, inbox);
        try {
            long seed = 0;
            while (true) {
                var random = new java.util.Random(seed);
                if (random.nextInt(12) != random.nextInt(12)) break;
                seed++;
            }
            diceSeed(seed);
            socket.sendText(MAPPER.createObjectNode().put("type", "roll").put("kind", "duality")
                    .put("actorId", "arcana").put("expression", "2d12").put("source", "action").toString(), true).join();
            JsonNode roll = inbox.next("roll");
            ObjectNode request = MAPPER.createObjectNode().put("actorId", "arcana").put("key", "card:Arcana-Touched").put("actionId", "swap");
            request.putObject("roll").put("id", roll.path("id").asLong()).put("ts", roll.path("ts").asLong());
            assertEquals(400, featureHttp(cookie, request).statusCode());
            caster.withArray("loadout").add(fourth);
            caster.withArray("vault").removeAll();
            store.saveSheet(campaign.getId(), "arcana", caster.toString());
            var result = featureHttp(cookie, request);
            assertEquals(200, result.statusCode(), result.body());
            JsonNode updated = inbox.next("rollUpdate");
            assertEquals(roll.path("rolls").get(0), updated.path("rolls").get(1));
            assertEquals(roll.path("rolls").get(1), updated.path("rolls").get(0));
            assertEquals(1, MAPPER.readTree(store.loadSheet(campaign.getId(), "arcana")).path("usage").path("card:Arcana-Touched").path("used").asInt());
            assertEquals(400, featureHttp(cookie, request).statusCode());
        } finally { socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join(); }
    }

    @Test
    void dreadTouchedPreventsFearOnlyForEligibleRollsWithoutUsingRestBonus() throws Exception {
        ObjectNode caster = communitySheet("Seaborne");
        JsonNode card = java.util.stream.StreamSupport.stream(
                MAPPER.readTree(compendiumDir.resolve("domain-cards.json").toFile()).path("entries").spliterator(), false)
                .filter(item -> "dread-touched".equals(item.path("id").asText())).findFirst().orElseThrow();
        var loadout = caster.putArray("loadout");
        loadout.add(card.deepCopy());
        loadout.addObject().put("name", "A").put("domain", "Dread");
        loadout.addObject().put("name", "B").put("domain", "dread");
        JsonNode fourth = MAPPER.createObjectNode().put("name", "C").put("domain", "Dread");
        caster.putArray("vault").add(fourth);
        caster.withObject("/usage").putObject("card:Dread-Touched").put("used", 1).put("per", "rest");
        campaign.getActors().add(Campaign.Actor.builder().id("dread").name("Caster").ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), "dread", caster.toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket socket = connect(cookie, inbox);
        try {
            long seed = 0;
            while (true) {
                var random = new java.util.Random(seed);
                if (random.nextInt(12) < random.nextInt(12)) break;
                seed++;
            }
            diceSeed(seed);
            ObjectNode msg = MAPPER.createObjectNode().put("type", "roll").put("kind", "duality")
                    .put("actorId", "dread").put("expression", "2d12").put("source", "action").put("difficulty", 100);
            socket.sendText(msg.toString(), true).join();
            JsonNode roll = inbox.next("roll");
            ObjectNode request = MAPPER.createObjectNode().put("actorId", "dread").put("key", "card:Dread-Touched").put("actionId", "prevent-fear");
            request.putObject("roll").put("id", roll.path("id").asLong()).put("ts", roll.path("ts").asLong());
            assertEquals(400, featureHttp(cookie, request).statusCode());
            caster.withArray("loadout").add(fourth);
            caster.withArray("vault").removeAll();
            store.saveSheet(campaign.getId(), "dread", caster.toString());
            assertEquals(400, featureHttp(cookie, request).statusCode());
            diceSeed(seed);
            msg.remove("difficulty");
            socket.sendText(msg.toString(), true).join();
            roll = inbox.next("roll");
            request.withObject("/roll").put("id", roll.path("id").asLong()).put("ts", roll.path("ts").asLong());
            var result = featureHttp(cookie, request);
            assertEquals(200, result.statusCode(), result.body());
            JsonNode saved = MAPPER.readTree(store.loadSheet(campaign.getId(), "dread"));
            assertEquals(5, saved.path("stress").path("on").asInt());
            assertEquals(1, saved.path("usage").path("card:Dread-Touched").path("used").asInt());
            assertEquals(400, featureHttp(cookie, request).statusCode());
        } finally { socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join(); }
    }

    @Test
    void banishRollStoresHighestDieAndRejectsInactiveCardAndNonpositiveTrait() throws Exception {
        ObjectNode caster = communitySheet("Seaborne");
        caster.putArray("traits").addObject().put("name", "Knowledge").put("mod", 3).put("spellcast", true);
        JsonNode card = java.util.stream.StreamSupport.stream(
                MAPPER.readTree(compendiumDir.resolve("domain-cards.json").toFile()).path("entries").spliterator(), false)
                .filter(item -> "banish".equals(item.path("id").asText())).findFirst().orElseThrow();
        caster.putArray("loadout").add(card.deepCopy());
        caster.putArray("vault");
        campaign.getActors().add(Campaign.Actor.builder().id("banish").name("Caster").ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), "banish", caster.toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket socket = connect(cookie, inbox);
        try {
            ObjectNode message = MAPPER.createObjectNode().put("type", "roll").put("kind", "duality")
                    .put("actorId", "banish").put("expression", "2d12")
                    .put("source", "action,spellcast,card:Banish");
            socket.sendText(message.toString(), true).join();
            JsonNode original = inbox.next("roll");
            ObjectNode request = MAPPER.createObjectNode().put("actorId", "banish").put("key", "card:Banish").put("actionId", "difficulty");
            request.putObject("roll").put("id", original.path("id").asLong()).put("ts", original.path("ts").asLong());
            caster.withArray("loadout").removeAll();
            caster.withArray("vault").add(card.deepCopy());
            store.saveSheet(campaign.getId(), "banish", caster.toString());
            assertEquals(400, featureHttp(cookie, request).statusCode());
            caster.withArray("loadout").add(card.deepCopy());
            caster.withArray("vault").removeAll();
            ((ObjectNode) caster.path("traits").get(0)).put("mod", 0);
            store.saveSheet(campaign.getId(), "banish", caster.toString());
            assertEquals(400, featureHttp(cookie, request).statusCode());
            ((ObjectNode) caster.path("traits").get(0)).put("mod", 3);
            store.saveSheet(campaign.getId(), "banish", caster.toString());
            var result = featureHttp(cookie, request);
            assertEquals(200, result.statusCode(), result.body());
            JsonNode saved = MAPPER.readTree(store.loadSheet(campaign.getId(), "banish"));
            int difficulty = saved.path("loadout").get(0).path("banishDifficulty").asInt();
            assertEquals(1, saved.path("compendiumRevision").asInt());
            JsonNode rolled = inbox.next("roll");
            assertEquals("3d20", rolled.path("expression").asText());
            int highest = java.util.stream.StreamSupport.stream(rolled.path("rolls").spliterator(), false)
                    .mapToInt(JsonNode::asInt).max().orElseThrow();
            assertEquals(highest, difficulty);
            assertEquals(400, featureHttp(cookie, request).statusCode());
        } finally { socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join(); }
    }

    @Test
    void hometSpellsTrackOnlySuccessfulRollsSeparately() throws Exception {
        ObjectNode caster = communitySheet("Seaborne");
        JsonNode card = java.util.stream.StreamSupport.stream(
                MAPPER.readTree(compendiumDir.resolve("domain-cards.json").toFile()).path("entries").spliterator(), false)
                .filter(item -> "book-of-homet".equals(item.path("id").asText())).findFirst().orElseThrow();
        caster.putArray("loadout").add(card.deepCopy());
        caster.putArray("vault");
        campaign.getActors().add(Campaign.Actor.builder().id("homet").name("Caster").ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), "homet", caster.toString());
        Inbox inbox = new Inbox();
        WebSocket socket = connect(cookie(), inbox);
        try {
            long seed = 0;
            while (true) {
                var random = new java.util.Random(seed);
                if (random.nextInt(12) != random.nextInt(12)) break;
                seed++;
            }
            diceSeed(seed);
            ObjectNode message = MAPPER.createObjectNode().put("type", "roll").put("kind", "duality")
                    .put("actorId", "homet").put("expression", "2d12-30")
                    .put("source", "action,spellcast,card:Book of Homet,card:Pass Through").put("difficulty", 1);
            socket.sendText(message.toString(), true).join();
            assertEquals(13, inbox.next("roll").path("difficulty").asInt());
            assertTrue(MAPPER.readTree(store.loadSheet(campaign.getId(), "homet")).path("usage").isEmpty());
            message.put("expression", "2d12+30");
            socket.sendText(message.toString(), true).join();
            assertEquals(1, inbox.next("sheetFeature").path("usage").path("card:Book of Homet/Pass Through").path("used").asInt());
            assertEquals(13, inbox.next("roll").path("difficulty").asInt());
            message.put("source", "action,spellcast,card:Book of Homet,card:Plane Gate");
            socket.sendText(message.toString(), true).join();
            JsonNode sheet = inbox.next("sheetFeature");
            assertEquals(1, sheet.path("usage").path("card:Book of Homet/Pass Through").path("used").asInt());
            assertEquals(1, sheet.path("usage").path("card:Book of Homet/Plane Gate").path("used").asInt());
            assertEquals(14, inbox.next("roll").path("difficulty").asInt());
            socket.sendText(message.toString(), true).join();
            assertTrue(inbox.next("error").path("message").asText().contains("no uses remaining"));
            ObjectNode saved = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "homet"));
            saved.withArray("loadout").removeAll();
            saved.withArray("vault").add(card.deepCopy());
            store.saveSheet(campaign.getId(), "homet", saved.toString());
            socket.sendText(message.toString(), true).join();
            assertTrue(inbox.next("error").path("message").asText().contains("active loadout"));
            saved.withArray("vault").removeAll();
            store.saveSheet(campaign.getId(), "homet", saved.toString());
            socket.sendText(message.toString(), true).join();
            assertTrue(inbox.next("error").path("message").asText().contains("active loadout"));
        } finally { socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join(); }
    }

    @Test
    void runicLockConsumesItsRestUseOnlyOnSuccess() throws Exception {
        ObjectNode caster = communitySheet("Seaborne");
        JsonNode card = java.util.stream.StreamSupport.stream(
                MAPPER.readTree(compendiumDir.resolve("domain-cards.json").toFile()).path("entries").spliterator(), false)
                .filter(item -> "book-of-vagras".equals(item.path("id").asText())).findFirst().orElseThrow();
        caster.putArray("loadout").add(card.deepCopy());
        caster.putArray("vault");
        campaign.getActors().add(Campaign.Actor.builder().id("runic").name("Caster").ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), "runic", caster.toString());
        Inbox inbox = new Inbox();
        WebSocket socket = connect(cookie(), inbox);
        try {
            long seed = 0;
            while (true) {
                var random = new java.util.Random(seed);
                if (random.nextInt(12) != random.nextInt(12)) break;
                seed++;
            }
            diceSeed(seed);
            ObjectNode msg = MAPPER.createObjectNode().put("type", "roll").put("kind", "duality")
                    .put("actorId", "runic").put("expression", "2d12-30")
                    .put("source", "action,spellcast,card:Book of Vagras,card:Runic Lock");
            socket.sendText(msg.toString(), true).join();
            assertEquals(15, inbox.next("roll").path("difficulty").asInt());
            assertTrue(MAPPER.readTree(store.loadSheet(campaign.getId(), "runic")).path("usage").isEmpty());
            diceSeed(seed);
            msg.put("expression", "2d12+30");
            socket.sendText(msg.toString(), true).join();
            assertEquals(1, inbox.next("sheetFeature").path("usage").path("card:Book of Vagras/Runic Lock").path("used").asInt());
            assertEquals(15, inbox.next("roll").path("difficulty").asInt());
            socket.sendText(msg.toString(), true).join();
            assertTrue(inbox.next("error").path("message").asText().contains("no uses remaining"));
        } finally { socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join(); }
    }

    @Test
    void glancingBlowAtomicallyPaysOnceAndRollsOnlyTheFailedAttacksWeaponPrivately() throws Exception {
        ObjectNode fighter = communitySheet("Seaborne");
        JsonNode card = java.util.stream.StreamSupport.stream(
                MAPPER.readTree(compendiumDir.resolve("domain-cards.json").toFile()).path("entries").spliterator(), false)
                .filter(item -> "glancing-blow".equals(item.path("id").asText())).findFirst().orElseThrow();
        fighter.withArray("loadout").add(card.deepCopy());
        fighter.withObject("/proficiency").put("on", 5);
        fighter.withObject("/stress").put("on", 6).put("slots", 6);
        fighter.putArray("activeWeapons").addObject().put("name", "Blade").put("slot", "Primary")
                .put("damage", "d8+2").put("damageType", "physical");
        campaign.getActors().add(Campaign.Actor.builder().id("fighter").name("Fighter").ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), "fighter", fighter.toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket socket = connect(cookie, inbox);
        try {
            assertTrue(inbox.next("hello").path("glancingBlowDamage").asBoolean());
            diceSeed(0);
            ObjectNode message = MAPPER.createObjectNode().put("type", "roll").put("kind", "duality")
                    .put("actorId", "fighter").put("source", "action,attack").put("weapon", "Blade")
                    .put("difficulty", 99).put("expression", "2d12").put("toId", "dm");
            socket.sendText(message.toString(), true).join();
            JsonNode attack = inbox.next("roll");
            assertTrue(attack.path("rolls").path(0).asInt() != attack.path("rolls").path(1).asInt());
            ObjectNode request = MAPPER.createObjectNode().put("actorId", "fighter").put("key", "card:Glancing Blow")
                    .put("actionId", "use").put("expression", "99d20").put("proficiency", 99);
            request.putObject("roll").put("id", attack.path("id").asLong()).put("ts", attack.path("ts").asLong());
            int before = store.historyTail(campaign.getId(), 100).size();
            assertEquals(400, featureHttp(cookie, request).statusCode());
            assertEquals(before, store.historyTail(campaign.getId(), 100).size());
            assertEquals(6, MAPPER.readTree(store.loadSheet(campaign.getId(), "fighter")).path("stress").path("on").asInt());
            fighter.withObject("/stress").put("on", 0);
            store.saveSheet(campaign.getId(), "fighter", fighter.toString());
            var response = featureHttp(cookie, request);
            assertEquals(200, response.statusCode(), response.body());
            JsonNode damage = inbox.next("roll");
            assertEquals("3d8+2", damage.path("expression").asText());
            assertEquals(3, damage.path("rolls").size());
            assertEquals("Blade", damage.path("weapon").asText());
            assertEquals("dm", damage.path("toId").asText());
            assertEquals(attack.path("id"), damage.path("linkedAttack").path("id"));
            assertEquals(1, MAPPER.readTree(store.loadSheet(campaign.getId(), "fighter")).path("stress").path("on").asInt());
            assertEquals(before + 1, store.historyTail(campaign.getId(), 100).size());
            assertEquals(400, featureHttp(cookie, request).statusCode());
            assertEquals(before + 1, store.historyTail(campaign.getId(), 100).size());
            assertEquals(1, MAPPER.readTree(store.loadSheet(campaign.getId(), "fighter")).path("stress").path("on").asInt());
            message.put("difficulty", 1);
            socket.sendText(message.toString(), true).join();
            JsonNode success = inbox.next("roll");
            request.withObject("/roll").put("id", success.path("id").asLong()).put("ts", success.path("ts").asLong());
            assertEquals(400, featureHttp(cookie, request).statusCode());
            assertEquals(1, MAPPER.readTree(store.loadSheet(campaign.getId(), "fighter")).path("stress").path("on").asInt());
        } finally { socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join(); }
    }

    @Test
    void glancingBlowAcceptsNormalDamageOptionsButRejectsStaleOrForgedWeaponPoolsWithoutCost() throws Exception {
        ObjectNode fighter = communitySheet("Seaborne");
        JsonNode card = java.util.stream.StreamSupport.stream(
                MAPPER.readTree(compendiumDir.resolve("domain-cards.json").toFile()).path("entries").spliterator(), false)
                .filter(item -> "glancing-blow".equals(item.path("id").asText())).findFirst().orElseThrow();
        fighter.withArray("loadout").add(card.deepCopy());
        fighter.withObject("/proficiency").put("on", 5);
        fighter.withObject("/stress").put("on", 0).put("slots", 6);
        fighter.putArray("activeWeapons").addObject().put("name", "Blade").put("slot", "Primary")
                .put("damage", "d8+2").put("damageType", "physical");
        ObjectNode effect = fighter.withArray("effects").addObject().put("id", "empowered").put("name", "Empowered");
        effect.putArray("rollOptions").addObject().put("id", "double").put("label", "Double damage")
                .put("roll", "damage").put("multiplier", 2).put("maximizeDie", true);
        campaign.getActors().add(Campaign.Actor.builder().id("fighter").name("Fighter").ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), "fighter", fighter.toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket socket = connect(cookie, inbox);
        try {
            diceSeed(0);
            ObjectNode message = MAPPER.createObjectNode().put("type", "roll").put("kind", "duality")
                    .put("actorId", "fighter").put("source", "action,attack").put("weapon", "Blade")
                    .put("difficulty", 99).put("expression", "2d12").put("toId", "dm");
            socket.sendText(message.toString(), true).join();
            JsonNode attack = inbox.next("roll");
            ObjectNode request = MAPPER.createObjectNode().put("actorId", "fighter").put("key", "card:Glancing Blow")
                    .put("actionId", "use");
            request.putObject("roll").put("id", attack.path("id").asLong()).put("ts", attack.path("ts").asLong());
            ObjectNode damage = request.putObject("damage").put("expression", "(2d8+8+2+1d6+4)*2");
            ObjectNode context = damage.putObject("weaponDamage").put("proficiency", 3)
                    .put("proficiencyBonus", 0).put("multiplier", 2).put("maximizeDie", 0);
            context.putArray("options").addObject().put("key", "effect:empowered").put("id", "double");
            var contributions = damage.putArray("contributions");
            contributions.addObject().put("label", "Blade damage (Proficiency 3)").put("expression", "(2d8+8)*2");
            contributions.addObject().put("label", "Blade damage bonus").put("expression", "(2)*2");
            contributions.addObject().put("label", "Other damage option").put("expression", "(1d6+4)*2");
            int before = store.historyTail(campaign.getId(), 100).size();
            assertEquals(0, MAPPER.readTree(store.loadSheet(campaign.getId(), "fighter")).path("stress").path("on").asInt());
            context.put("proficiency", 99);
            assertEquals(400, featureHttp(cookie, request).statusCode());
            context.put("proficiency", 3);
            ((ObjectNode) contributions.get(0)).put("expression", "(99d20)*2");
            assertEquals(400, featureHttp(cookie, request).statusCode());
            ((ObjectNode) contributions.get(0)).put("expression", "(2d8+8)*2");
            damage.put("expression", "99d20");
            assertEquals(400, featureHttp(cookie, request).statusCode());
            damage.put("expression", "(2d8+8+2+1d6+4)*2");
            assertEquals(before, store.historyTail(campaign.getId(), 100).size());
            assertEquals(0, MAPPER.readTree(store.loadSheet(campaign.getId(), "fighter")).path("stress").path("on").asInt());
            var response = featureHttp(cookie, request);
            assertEquals(200, response.statusCode(), response.body());
            JsonNode rolled = inbox.next("roll");
            assertEquals("(2d8+8+2+1d6+4)*2", rolled.path("expression").asText());
            assertEquals(3, rolled.path("rolls").size());
            assertEquals(3, rolled.path("breakdown").size());
            assertEquals("dm", rolled.path("toId").asText());
            assertEquals(1, MAPPER.readTree(store.loadSheet(campaign.getId(), "fighter")).path("stress").path("on").asInt());
            assertEquals(400, featureHttp(cookie, request).statusCode());
            assertEquals(before + 1, store.historyTail(campaign.getId(), 100).size());
            diceSeed(0);
            socket.sendText(message.toString(), true).join();
            JsonNode disconnectedAttack = inbox.next("roll");
            request.withObject("/roll").put("id", disconnectedAttack.path("id").asLong())
                    .put("ts", disconnectedAttack.path("ts").asLong());
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
            awaitConnections(0);
            assertEquals(400, featureHttp(cookie, request).statusCode());
            assertEquals(1, MAPPER.readTree(store.loadSheet(campaign.getId(), "fighter")).path("stress").path("on").asInt());
        } finally { if (!socket.isOutputClosed()) socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join(); }
    }

    @Test
    void notGoodEnoughRerollsOnlyEligibleDamageDiceAndPersistsTheUpdatedRoll() throws Exception {
        ObjectNode fighter = communitySheet("Seaborne");
        JsonNode card = java.util.stream.StreamSupport.stream(
                MAPPER.readTree(compendiumDir.resolve("domain-cards.json").toFile()).path("entries").spliterator(), false)
                .filter(item -> "not-good-enough".equals(item.path("id").asText())).findFirst().orElseThrow();
        fighter.withArray("loadout").add(card.deepCopy());
        campaign.getActors().add(Campaign.Actor.builder().id("fighter").name("Fighter").ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), "fighter", fighter.toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket socket = connect(cookie, inbox);
        try {
            socket.sendText(MAPPER.createObjectNode().put("type", "roll").put("actorId", "fighter")
                    .put("source", "damage").put("expression", "1d1-1d1").toString(), true).join();
            JsonNode roll = inbox.next("roll");
            ObjectNode request = MAPPER.createObjectNode().put("actorId", "fighter")
                    .put("key", "card:Not Good Enough").put("actionId", "reroll");
            request.putObject("roll").put("id", roll.path("id").asLong()).put("ts", roll.path("ts").asLong());
            request.set("expectedDice", roll.path("rolls"));
            request.putArray("dice").add(1);
            assertEquals(400, featureHttp(cookie, request).statusCode());
            request.putArray("dice").add(0);
            var response = featureHttp(cookie, request);
            assertEquals(200, response.statusCode(), response.body());
            JsonNode updated = inbox.next("rollUpdate");
            assertEquals(0, updated.path("total").asInt());
            assertTrue(store.historyTail(campaign.getId(), 1).getLast().path("text").asText().contains("Rerolled damage die"));
            request.putArray("expectedDice").add(99);
            assertEquals(400, featureHttp(cookie, request).statusCode());
        } finally { socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join(); }
    }

    @Test
    void nestedDomainReactionsUseCanonicalBooksAndRejectVaultedSources() throws Exception {
        ObjectNode caster = communitySheet("Seaborne");
        caster.withObject("/hope").put("on", 5);
        JsonNode catalog = MAPPER.readTree(compendiumDir.resolve("domain-cards.json").toFile());
        for (JsonNode card : catalog.path("entries"))
            if (List.of("book-of-vagras", "book-of-tyfar").contains(card.path("id").asText()))
                caster.withArray("loadout").add(card.deepCopy());
        campaign.getActors().add(Campaign.Actor.builder().id("books").name("Caster").ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), "books", caster.toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket socket = connect(cookie, inbox);
        try {
            long seed = 0;
            while (true) {
                var random = new java.util.Random(seed);
                if (random.nextInt(12) != random.nextInt(12)) break;
                seed++;
            }
            diceSeed(seed);
            ObjectNode cast = MAPPER.createObjectNode().put("type", "roll").put("kind", "duality")
                    .put("actorId", "books").put("expression", "2d12-30").put("difficulty", 13)
                    .put("source", "action,spellcast,card:Book of Vagras,card:Arcane Door");
            socket.sendText(cast.toString(), true).join();
            JsonNode failed = inbox.next("roll");
            ObjectNode request = MAPPER.createObjectNode().put("actorId", "books")
                    .put("key", "card:Book of Vagras/Arcane Door").put("actionId", "portal");
            request.putObject("roll").put("id", failed.path("id").asLong()).put("ts", failed.path("ts").asLong());
            assertEquals(400, featureHttp(cookie, request).statusCode());
            cast.put("expression", "2d12+30");
            socket.sendText(cast.toString(), true).join();
            JsonNode success = inbox.next("roll");
            request.withObject("/roll").put("id", success.path("id").asLong()).put("ts", success.path("ts").asLong());
            assertEquals(200, featureHttp(cookie, request).statusCode());
            assertEquals(4, MAPPER.readTree(store.loadSheet(campaign.getId(), "books")).path("hope").path("on").asInt());
            assertEquals(400, featureHttp(cookie, request).statusCode());
            cast.put("source", "action,spellcast,card:Book of Tyfar,card:Wild Flame");
            socket.sendText(cast.toString(), true).join();
            JsonNode flame = inbox.next("roll");
            request.put("key", "card:Book of Tyfar/Wild Flame").put("actionId", "damage");
            request.withObject("/roll").put("id", flame.path("id").asLong()).put("ts", flame.path("ts").asLong());
            assertEquals(200, featureHttp(cookie, request).statusCode());
            assertTrue(inbox.next("roll").path("source").asText().contains("magicdamage"));
            ObjectNode stored = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "books"));
            stored.withArray("vault").add(stored.withArray("loadout").remove(0));
            store.saveSheet(campaign.getId(), "books", stored.toString());
            request.put("key", "card:Book of Vagras/Arcane Door").put("actionId", "portal");
            request.withObject("/roll").put("id", success.path("id").asLong()).put("ts", success.path("ts").asLong());
            assertEquals(400, featureHttp(cookie, request).statusCode());
        } finally { socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join(); }
    }

    @Test
    void shieldAuraRecastMovesOnlyTheCastingActorsProtection() throws Exception {
        communitySheet("Seaborne");
        JsonNode card = java.util.stream.StreamSupport.stream(
                MAPPER.readTree(compendiumDir.resolve("domain-cards.json").toFile()).path("entries").spliterator(), false)
                .filter(item -> "shield-aura".equals(item.path("id").asText())).findFirst().orElseThrow();
        for (String id : List.of("caster-a", "caster-b", "recipient-a", "recipient-b", "recipient-c")) {
            ObjectNode sheet = communitySheet("Seaborne");
            sheet.withObject("/stress").put("on", 0);
            if (id.startsWith("caster")) sheet.withArray("loadout").add(card.deepCopy());
            campaign.getActors().add(Campaign.Actor.builder().id(id).name(id).ownerId(playerId()).build());
            store.saveSheet(campaign.getId(), id, sheet.toString());
        }
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket socket = connect(cookie, inbox);
        try {
            ObjectNode request = MAPPER.createObjectNode().put("actorId", "caster-a").put("key", "card:Shield Aura")
                    .put("actionId", "use").put("targetActorId", "recipient-a");
            assertEquals(200, featureHttp(cookie, request).statusCode());
            request.put("actorId", "caster-b").put("targetActorId", "recipient-b");
            assertEquals(200, featureHttp(cookie, request).statusCode());
            request.put("actorId", "caster-a").put("targetActorId", "recipient-c");
            assertEquals(200, featureHttp(cookie, request).statusCode());
            assertTrue(MAPPER.readTree(store.loadSheet(campaign.getId(), "recipient-a")).path("effects").isEmpty());
            assertEquals("caster-b", MAPPER.readTree(store.loadSheet(campaign.getId(), "recipient-b"))
                    .path("effects").path(0).path("sourceActorId").asText());
            assertEquals("caster-a", MAPPER.readTree(store.loadSheet(campaign.getId(), "recipient-c"))
                    .path("effects").path(0).path("sourceActorId").asText());
            assertEquals(2, MAPPER.readTree(store.loadSheet(campaign.getId(), "caster-a")).path("stress").path("on").asInt());
        } finally { socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join(); }
    }

    @Test
    void counterspellVaultsOnlyAfterSuccessfulReactionAndBlightingChoicesAreExclusive() throws Exception {
        ObjectNode caster = communitySheet("Seaborne");
        caster.withObject("/hope").put("on", 5);
        JsonNode catalog = MAPPER.readTree(compendiumDir.resolve("domain-cards.json").toFile());
        for (JsonNode card : catalog.path("entries"))
            if (List.of("counterspell", "blighting-strike").contains(card.path("id").asText()))
                caster.withArray("loadout").add(card.deepCopy());
        campaign.getActors().add(Campaign.Actor.builder().id("reactions").name("Caster").ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), "reactions", caster.toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket socket = connect(cookie, inbox);
        try {
            long noncritical = 0;
            while (true) {
                var random = new java.util.Random(noncritical);
                if (random.nextInt(12) != random.nextInt(12)) break;
                noncritical++;
            }
            diceSeed(noncritical);
            ObjectNode cast = MAPPER.createObjectNode().put("type", "roll").put("kind", "duality")
                    .put("actorId", "reactions").put("expression", "2d12-30").put("difficulty", 15)
                    .put("source", "reaction,spellcast,featureAction:card:Counterspell:reaction,card:Counterspell");
            socket.sendText(cast.toString(), true).join();
            JsonNode failed = inbox.next("roll");
            ObjectNode request = MAPPER.createObjectNode().put("actorId", "reactions").put("key", "card:Counterspell").put("actionId", "stop");
            request.putObject("roll").put("id", failed.path("id").asLong()).put("ts", failed.path("ts").asLong());
            assertEquals(400, featureHttp(cookie, request).statusCode());
            assertTrue(MAPPER.readTree(store.loadSheet(campaign.getId(), "reactions")).path("vault").isEmpty());
            cast.put("expression", "2d12+30");
            socket.sendText(cast.toString(), true).join();
            JsonNode success = inbox.next("roll");
            request.withObject("/roll").put("id", success.path("id").asLong()).put("ts", success.path("ts").asLong());
            assertEquals(200, featureHttp(cookie, request).statusCode());
            assertEquals("Counterspell", MAPPER.readTree(store.loadSheet(campaign.getId(), "reactions")).path("vault").path(0).path("name").asText());
            assertEquals(400, featureHttp(cookie, request).statusCode());

            cast.put("expression", "2d12-30").put("source", "action,spellcast,card:Blighting Strike");
            diceSeed(noncritical);
            socket.sendText(cast.toString(), true).join();
            JsonNode blighting = inbox.next("roll");
            request.put("key", "card:Blighting Strike").put("actionId", "fail-hope");
            request.withObject("/roll").put("id", blighting.path("id").asLong()).put("ts", blighting.path("ts").asLong());
            assertEquals(200, featureHttp(cookie, request).statusCode());
            request.put("actionId", "fail-stress");
            assertEquals(400, featureHttp(cookie, request).statusCode());
            long critical = 0;
            while (true) {
                var random = new java.util.Random(critical);
                if (random.nextInt(12) == random.nextInt(12)) break;
                critical++;
            }
            diceSeed(critical);
            socket.sendText(cast.toString(), true).join();
            JsonNode crit = inbox.next("roll");
            request.withObject("/roll").put("id", crit.path("id").asLong()).put("ts", crit.path("ts").asLong());
            assertEquals(400, featureHttp(cookie, request).statusCode());
        } finally { socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join(); }
    }

    @Test
    void teleportConsumesLongRestUseOnFailedAndSuccessfulAttemptsIncludingCopiedCards() throws Exception {
        ObjectNode caster = communitySheet("Seaborne");
        JsonNode teleport = java.util.stream.StreamSupport.stream(
                MAPPER.readTree(compendiumDir.resolve("domain-cards.json").toFile()).path("entries").spliterator(), false)
                .filter(card -> "teleport".equals(card.path("id").asText())).findFirst().orElseThrow();
        campaign.getActors().add(Campaign.Actor.builder().id("teleport").name("Caster").ownerId(playerId()).build());
        campaign.getActors().add(Campaign.Actor.builder().id("original").name("Original").ownerId(playerId()).build());
        ObjectNode original = caster.deepCopy();
        original.putArray("loadout").add(teleport.deepCopy());
        store.saveSheet(campaign.getId(), "original", original.toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            for (boolean copied : List.of(false, true)) for (int bonus : List.of(-30, 30)) {
                ObjectNode fresh = caster.deepCopy();
                String key;
                if (copied) {
                    ObjectNode copycat = fresh.putArray("loadout").addObject().put("name", "Copycat");
                    copycat.putObject("borrowedCard").put("sourceActorId", "original")
                            .set("card", CopycatCards.prepare(teleport, fresh));
                    key = "card:Copycat/Teleport";
                } else {
                    fresh.putArray("loadout").add(teleport.deepCopy());
                    key = "card:Teleport";
                }
                store.saveSheet(campaign.getId(), "teleport", fresh.toString());
                ObjectNode request = MAPPER.createObjectNode().put("type", "roll").put("kind", "duality")
                        .put("actorId", "teleport").put("expression", "2d12" + (bonus > 0 ? "+" : "") + bonus)
                        .put("source", "action,spellcast," + key);
                diceSeed(0);
                ws.sendText(request.toString(), true).join();
                JsonNode roll = inbox.next("roll");
                assertEquals(16, roll.path("difficulty").asInt());
                assertEquals(bonus > 0, roll.path("total").asInt() >= 16);
                JsonNode saved = MAPPER.readTree(store.loadSheet(campaign.getId(), "teleport"));
                assertEquals(1, saved.path("usage").path(key).path("used").asInt());
                request.put("expression", "2d12+30");
                ws.sendText(request.toString(), true).join();
                assertTrue(inbox.next("error").toString().contains("no uses remaining"));
                assertEquals(1, MAPPER.readTree(store.loadSheet(campaign.getId(), "teleport")).path("usage").path(key).path("used").asInt());
            }
        } finally { ws.abort(); }
    }

    @Test
    void blinkOutValidatesSuccessActiveCardAndHopeBeforeSpending() throws Exception {
        ObjectNode caster = communitySheet("Seaborne");
        caster.withObject("/hope").put("on", 5);
        JsonNode card = java.util.stream.StreamSupport.stream(
                MAPPER.readTree(compendiumDir.resolve("domain-cards.json").toFile()).path("entries").spliterator(), false)
                .filter(item -> "blink-out".equals(item.path("id").asText())).findFirst().orElseThrow();
        caster.putArray("loadout").add(card.deepCopy());
        caster.putArray("vault");
        campaign.getActors().add(Campaign.Actor.builder().id("blink").name("Caster").ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), "blink", caster.toString());
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket socket = connect(cookie, inbox);
        try {
            long seed = 0;
            while (true) {
                var random = new java.util.Random(seed);
                if (random.nextInt(12) != random.nextInt(12)) break;
                seed++;
            }

            diceSeed(seed);
            ObjectNode message = MAPPER.createObjectNode().put("type", "roll").put("kind", "duality")
                    .put("actorId", "blink").put("expression", "2d12-30")
                    .put("source", "action,spellcast,card:Blink Out").put("difficulty", 12);
            socket.sendText(message.toString(), true).join();
            JsonNode failed = inbox.next("roll");
            ObjectNode request = MAPPER.createObjectNode().put("actorId", "blink").put("key", "card:Blink Out")
                    .put("actionId", "teleport").put("amount", 2);
            request.putObject("roll").put("id", failed.path("id").asLong()).put("ts", failed.path("ts").asLong());
            assertEquals(400, featureHttp(cookie, request).statusCode());
            message.put("expression", "2d12+30");
            socket.sendText(message.toString(), true).join();
            JsonNode success = inbox.next("roll");
            request.withObject("/roll").put("id", success.path("id").asLong()).put("ts", success.path("ts").asLong());
            for (int invalid : List.of(0, -1, 6)) {
                request.put("amount", invalid);
                assertEquals(400, featureHttp(cookie, request).statusCode());
            }
            caster.withArray("loadout").removeAll();
            caster.withArray("vault").add(card.deepCopy());
            store.saveSheet(campaign.getId(), "blink", caster.toString());
            request.put("amount", 2);
            assertEquals(400, featureHttp(cookie, request).statusCode());
            caster.withArray("loadout").add(card.deepCopy());
            caster.withArray("vault").removeAll();
            store.saveSheet(campaign.getId(), "blink", caster.toString());
            var result = featureHttp(cookie, request);
            assertEquals(200, result.statusCode(), result.body());
            assertEquals(3, MAPPER.readTree(store.loadSheet(campaign.getId(), "blink")).path("hope").path("on").asInt());
            assertEquals(400, featureHttp(cookie, request).statusCode());
        } finally { socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join(); }
    }

    @Test
    void shareTheBurdenTransfersStressAndHopeAtomicallyToOnlyOneClaimant() throws Exception {
                String ally = allyCookie("burden-ally");
                ObjectNode caster = communitySheet("Seaborne");
                caster.withObject("/hope").put("on", 1);
                caster.withObject("/stress").put("on", 4);
                JsonNode catalog = MAPPER.readTree(compendiumDir.resolve("domain-cards.json").toFile());
                for (JsonNode card : catalog.path("entries"))
                    if ("share-the-burden".equals(card.path("id").asText())) caster.withArray("loadout").add(card.deepCopy());
                campaign.getActors().add(Campaign.Actor.builder().id("burden-caster").name("Caster").ownerId(playerId()).build());
                campaign.getActors().add(Campaign.Actor.builder().id("burden-target").name("Ally").ownerId("burden-ally").build());
                store.saveSheet(campaign.getId(), "burden-caster", caster.toString());
                store.saveSheet(campaign.getId(), "burden-target", communitySheet("Seaborne").toString());
                ObjectNode action = MAPPER.createObjectNode().put("actorId", "burden-caster").put("key", "card:Share the Burden").put("actionId", "use");
                assertEquals(200, featureHttp(cookie(), action).statusCode());
                ObjectNode invitation = (ObjectNode) dmMessages.stream().filter(m -> m.has("actionBenefits")).findFirst().orElseThrow();
                ObjectNode claim = benefitRequest("burden-target", invitation, "transfer").put("amount", 3);
                assertEquals(400, featureHttp(ally, claim).statusCode());
                assertEquals(4, MAPPER.readTree(store.loadSheet(campaign.getId(), "burden-caster")).path("stress").path("on").asInt());
                claim.put("amount", 2);
                var result = featureHttp(ally, claim);
                assertEquals(200, result.statusCode(), result.body());
                JsonNode savedCaster = MAPPER.readTree(store.loadSheet(campaign.getId(), "burden-caster"));
                assertEquals(6, savedCaster.path("stress").path("on").asInt());
                assertEquals(3, savedCaster.path("hope").path("on").asInt());
                assertEquals(1, MAPPER.readTree(store.loadSheet(campaign.getId(), "burden-target")).path("stress").path("on").asInt());
                assertEquals(400, featureHttp(ally, claim).statusCode());
                assertTrue(dmMessages.stream().anyMatch(m -> m.path("text").asText().contains("Transferred 2 Stress")));
            }

            @Test
            void octarineWallSuccessCommitsFearUseAndDamageReportTogether() throws Exception {
                ObjectNode caster = communitySheet("Seaborne");
                JsonNode catalog = MAPPER.readTree(compendiumDir.resolve("domain-cards.json").toFile());
                for (JsonNode card : catalog.path("entries"))
                    if ("octarine-wall".equals(card.path("id").asText())) caster.withArray("loadout").add(card.deepCopy());
                campaign.getActors().add(Campaign.Actor.builder().id("octarine").name("Caster").ownerId(playerId()).build());
                store.saveSheet(campaign.getId(), "octarine", caster.toString());
                String cookie = cookie();
                Inbox inbox = new Inbox();
                WebSocket ws = connect(cookie, inbox);
                try {
                    ObjectNode roll = MAPPER.createObjectNode().put("type", "roll").put("kind", "duality")
                            .put("actorId", "octarine").put("expression", "2d12-30").put("difficulty", 14)
                            .put("source", "action,spellcast,card:Octarine Wall");
                    diceSeed(0);
                    ws.sendText(roll.toString(), true).join();
                    JsonNode failed = inbox.next("roll");
                    ObjectNode request = MAPPER.createObjectNode().put("actorId", "octarine").put("key", "card:Octarine Wall").put("actionId", "create");
                    request.putObject("roll").put("id", failed.path("id").asLong()).put("ts", failed.path("ts").asLong());
                    int before = campaign.getFear();
                    assertEquals(400, featureHttp(cookie, request).statusCode());
                    assertEquals(before, campaign.getFear());
                    roll.put("expression", "2d12+30");
                    ws.sendText(roll.toString(), true).join();
                    JsonNode success = inbox.next("roll");
                    request.withObject("/roll").put("id", success.path("id").asLong()).put("ts", success.path("ts").asLong());
                    var result = featureHttp(cookie, request);
                    assertEquals(200, result.statusCode(), result.body());
                    assertEquals(before + 1, campaign.getFear());
                    assertEquals(1, MAPPER.readTree(store.loadSheet(campaign.getId(), "octarine")).path("usage").path("card:Octarine Wall").path("used").asInt());
                    assertTrue(dmMessages.stream().anyMatch(m -> "d12".equals(m.path("actionDamage").path("damage").path("dice").asText())));
                    assertEquals(400, featureHttp(cookie, request).statusCode());
                    assertEquals(before + 1, campaign.getFear());
                } finally { ws.abort(); }
            }

            @Test
            void troublemakerKeepsHighestD4AndEndlessCharismaRevisesOnlyChosenDie() throws Exception {
                ObjectNode caster = communitySheet("Seaborne");
                caster.putObject("proficiency").put("on", 3);
                JsonNode catalog = MAPPER.readTree(compendiumDir.resolve("domain-cards.json").toFile());
                for (JsonNode card : catalog.path("entries"))
                    if (List.of("troublemaker", "endless-charisma").contains(card.path("id").asText())) caster.withArray("loadout").add(card.deepCopy());
                campaign.getActors().add(Campaign.Actor.builder().id("charisma").name("Caster").ownerId(playerId()).build());
                store.saveSheet(campaign.getId(), "charisma", caster.toString());
                String cookie = cookie();
                Inbox inbox = new Inbox();
                WebSocket ws = connect(cookie, inbox);
                try {
                    ws.sendText("""
                            {"type":"roll","kind":"duality","expression":"2d12+30","actorId":"charisma",
                            "source":"action,trait:Presence,featureAction:card:Troublemaker:use,card:Troublemaker"}
                            """, true).join();
                    JsonNode original = inbox.next("roll");
                    ObjectNode request = MAPPER.createObjectNode().put("actorId", "charisma").put("key", "card:Troublemaker").put("actionId", "stress");
                    request.putObject("roll").put("id", original.path("id").asLong()).put("ts", original.path("ts").asLong());
                    var result = featureHttp(cookie, request);
                    assertEquals(200, result.statusCode(), result.body());
                    JsonNode dice = inbox.next("roll");
                    assertEquals("3d4kh1", dice.path("expression").asText());
                    int highest = java.util.stream.StreamSupport.stream(dice.path("rolls").spliterator(), false).mapToInt(JsonNode::asInt).max().orElseThrow();
                    assertEquals(highest, dice.path("total").asInt());
                    assertTrue(dice.path("featureRoll").path("consequence").asText().contains("target marks " + highest + " Stress"));
                    assertEquals(400, featureHttp(cookie, request).statusCode());
                    request.put("key", "card:Endless Charisma").put("actionId", "fear");
                    assertEquals(200, featureHttp(cookie, request).statusCode());
                    JsonNode saved = store.historyTail(campaign.getId(), Integer.MAX_VALUE).stream()
                            .filter(m -> m.path("id").equals(original.path("id")) && m.path("ts").equals(original.path("ts"))).findFirst().orElseThrow();
                    assertEquals(original.path("rolls").path(0), saved.path("rolls").path(0));
                    assertEquals(original.path("total").asInt() - original.path("rolls").path(1).asInt() + saved.path("rolls").path(1).asInt(), saved.path("total").asInt());
                    assertEquals(4, MAPPER.readTree(store.loadSheet(campaign.getId(), "charisma")).path("hope").path("on").asInt());
                    assertEquals(400, featureHttp(cookie, request).statusCode());
                    request.put("actionId", "hope");
                    assertEquals(400, featureHttp(cookie, request).statusCode());
                    assertEquals(4, MAPPER.readTree(store.loadSheet(campaign.getId(), "charisma")).path("hope").path("on").asInt());
                    ws.sendText("""
                            {"type":"roll","kind":"duality","expression":"2d12","actorId":"charisma","source":"action,trait:Presence"}
                            """, true).join();
                    JsonNode next = inbox.next("roll");
                    request.withObject("/roll").put("id", next.path("id").asLong()).put("ts", next.path("ts").asLong());
                    assertEquals(200, featureHttp(cookie, request).statusCode());
                    request.put("actionId", "fear");
                    assertEquals(400, featureHttp(cookie, request).statusCode());
                    assertEquals(3, MAPPER.readTree(store.loadSheet(campaign.getId(), "charisma")).path("hope").path("on").asInt());
                    ws.sendText("""
                            {"type":"roll","kind":"duality","expression":"2d12","actorId":"charisma","source":"action,attack,trait:Presence"}
                            """, true).join();
                    JsonNode attack = inbox.next("roll");
                    request.withObject("/roll").put("id", attack.path("id").asLong()).put("ts", attack.path("ts").asLong());
                    assertEquals(400, featureHttp(cookie, request).statusCode());
                    request.put("actionId", "hope");
                    assertEquals(400, featureHttp(cookie, request).statusCode());
                    assertEquals(3, MAPPER.readTree(store.loadSheet(campaign.getId(), "charisma")).path("hope").path("on").asInt());
                } finally { ws.abort(); }
            }

            @Test
            void safeHavenGrantsCasterAndClaimingAllyRestEffectsAndReportsTheirChoices() throws Exception {
                String ally = allyCookie("haven-ally");
                ObjectNode caster = communitySheet("Seaborne");
                JsonNode catalog = MAPPER.readTree(compendiumDir.resolve("domain-cards.json").toFile());
                for (JsonNode card : catalog.path("entries"))
                    if ("safe-haven".equals(card.path("id").asText())) caster.withArray("loadout").add(card.deepCopy());
                campaign.getActors().add(Campaign.Actor.builder().id("haven-caster").name("Caster").ownerId(playerId()).build());
                campaign.getActors().add(Campaign.Actor.builder().id("haven-target").name("Ally").ownerId("haven-ally").build());
                campaign.getActors().add(Campaign.Actor.builder().id("haven-disabled").name("Disabled").ownerId("haven-ally").disabled(true).build());
                store.saveSheet(campaign.getId(), "haven-caster", caster.toString());
                store.saveSheet(campaign.getId(), "haven-target", communitySheet("Seaborne").toString());
                store.saveSheet(campaign.getId(), "haven-disabled", communitySheet("Seaborne").toString());
                ObjectNode action = MAPPER.createObjectNode().put("actorId", "haven-caster").put("key", "card:Safe Haven").put("actionId", "use");
                var result = featureHttp(cookie(), action);
                assertEquals(200, result.statusCode(), result.body());
                JsonNode saved = MAPPER.readTree(store.loadSheet(campaign.getId(), "haven-caster"));
                assertEquals(3, saved.path("hope").path("on").asInt());
                assertEquals(1, saved.path("effects").path(0).path("rest").path("extraMoves").asInt());
                ObjectNode invitation = (ObjectNode) dmMessages.stream().filter(m -> m.has("actionBenefits")).findFirst().orElseThrow();
                assertEquals(1, invitation.path("actionBenefits").path("actorIds").size());
                assertEquals("haven-target", invitation.path("actionBenefits").path("actorIds").get(0).asText());
                assertEquals(403, featureHttp(ally, benefitRequest("haven-disabled", invitation, "enter")).statusCode());
                campaign.actor("haven-target").setDisabled(true);
                assertEquals(403, featureHttp(ally, benefitRequest("haven-target", invitation, "enter")).statusCode());
                campaign.actor("haven-target").setDisabled(false);
                assertEquals(200, featureHttp(ally, benefitRequest("haven-target", invitation, "enter")).statusCode());
                JsonNode target = MAPPER.readTree(store.loadSheet(campaign.getId(), "haven-target"));
                assertEquals("rest", target.path("effects").path(0).path("until").asText());
                assertEquals(1, target.path("effects").path(0).path("rest").path("extraMoves").asInt());
                campaign.actor("haven-caster").setDisabled(true);
                var targetRead = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/sheet/haven-target"))
                        .header("Cookie", ally).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, targetRead.statusCode(), targetRead.body());
                assertEquals(1, MAPPER.readTree(store.loadSheet(campaign.getId(), "haven-target")).path("effects").size());
                assertTrue(dmMessages.stream().anyMatch(m -> m.path("text").asText().contains("Enter Safe Haven")));
    }

    @Test
    void graceCastFollowUpsSpendRestUsesOnlyWhenActivatedFromChat() throws Exception {
        ObjectNode base = communitySheet("Seaborne");
        JsonNode catalog = MAPPER.readTree(compendiumDir.resolve("domain-cards.json").toFile()).path("entries");
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket socket = connect(cookie, inbox);
        try {
            for (String id : List.of("enrapture", "hypnotic-shimmer")) {
                JsonNode card = java.util.stream.StreamSupport.stream(catalog.spliterator(), false)
                        .filter(item -> id.equals(item.path("id").asText())).findFirst().orElseThrow();
                String key = "card:" + card.path("name").asText();
                ObjectNode caster = base.deepCopy();
                caster.withObject("/stress").put("on", 0);
                caster.putArray("loadout").add(card.deepCopy());
                campaign.getActors().add(Campaign.Actor.builder().id(id).name("Caster").ownerId(playerId()).build());
                store.saveSheet(campaign.getId(), id, caster.toString());
                ObjectNode cast = MAPPER.createObjectNode().put("type", "roll").put("kind", "duality")
                        .put("actorId", id).put("expression", "2d12+30").put("source", "action,spellcast," + key);
                socket.sendText(cast.toString(), true).join();
                JsonNode roll = inbox.next("roll");
                JsonNode saved = MAPPER.readTree(store.loadSheet(campaign.getId(), id));
                assertEquals(0, saved.path("stress").path("on").asInt());
                assertEquals(0, saved.path("usage").path(key).path("used").asInt());
                ObjectNode request = MAPPER.createObjectNode().put("actorId", id).put("key", key).put("actionId", "use");
                request.putObject("roll").put("id", roll.path("id").asLong()).put("ts", roll.path("ts").asLong());
                var activated = featureHttp(cookie, request);
                assertEquals(200, activated.statusCode(), activated.body());
                saved = MAPPER.readTree(store.loadSheet(campaign.getId(), id));
                assertEquals(id.equals("enrapture") ? 1 : 0, saved.path("stress").path("on").asInt());
                assertEquals(1, saved.path("usage").path(key).path("used").asInt());
                assertEquals(400, featureHttp(cookie, request).statusCode());
                socket.sendText(cast.toString(), true).join();
                assertEquals(id, inbox.next("roll").path("actorId").asText());
            }
        } finally { socket.abort(); }
    }

    @Test
    void thoughtDelverPublishesSpellcastFollowUpOnlyAfterHopePayment() throws Exception {
        ObjectNode caster = communitySheet("Seaborne");
        caster.withObject("/hope").put("on", 1);
        JsonNode card = java.util.stream.StreamSupport.stream(
                MAPPER.readTree(compendiumDir.resolve("domain-cards.json").toFile()).path("entries").spliterator(), false)
                .filter(item -> "thought-delver".equals(item.path("id").asText())).findFirst().orElseThrow();
        caster.putArray("loadout").add(card.deepCopy());
        campaign.getActors().add(Campaign.Actor.builder().id("delver").name("Delver").ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), "delver", caster.toString());
        String cookie = cookie();
        WebSocket socket = connect(cookie, new Inbox());
        try {
            ObjectNode request = MAPPER.createObjectNode().put("actorId", "delver").put("key", "card:Thought Delver").put("actionId", "use");
            var paid = featureHttp(cookie, request);
            assertEquals(200, paid.statusCode(), paid.body());
            assertEquals(0, MAPPER.readTree(store.loadSheet(campaign.getId(), "delver")).path("hope").path("on").asInt());
            JsonNode report = store.historyTail(campaign.getId(), 20).stream()
                    .filter(message -> message.has("actionFollowUp")).findFirst().orElseThrow();
            assertEquals("delver", report.path("actorId").asText());
            assertEquals(playerId(), report.path("fromId").asText());
            assertEquals("Thought Delver", report.path("actionFollowUp").path("name").asText());
            assertEquals("delve", report.path("actionFollowUp").path("id").asText());
            assertTrue(report.path("actionFollowUp").path("label").asText().contains("Spellcast"));
            long reports = store.historyTail(campaign.getId(), 20).stream().filter(message -> message.has("actionFollowUp")).count();
            var unpaid = featureHttp(cookie, request);
            assertEquals(400, unpaid.statusCode(), unpaid.body());
            assertEquals(reports, store.historyTail(campaign.getId(), 20).stream().filter(message -> message.has("actionFollowUp")).count());
        } finally { socket.abort(); }
    }

    @Test
    void mendingTouchSharesUpgradeLimitButNotBaseHealing() throws Exception {
        ObjectNode caster = communitySheet("Seaborne");
        caster.withObject("/hope").put("on", 6);
        JsonNode card = java.util.stream.StreamSupport.stream(
                MAPPER.readTree(compendiumDir.resolve("domain-cards.json").toFile()).path("entries").spliterator(), false)
                .filter(item -> "mending-touch".equals(item.path("id").asText())).findFirst().orElseThrow();
        caster.putArray("loadout").add(card.deepCopy());
        campaign.getActors().add(Campaign.Actor.builder().id("mender").name("Mender").ownerId(playerId()).build());
        campaign.getActors().add(Campaign.Actor.builder().id("patient").name("Patient").ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), "mender", caster.toString());
        store.saveSheet(campaign.getId(), "patient", communitySheet("Seaborne").toString());
        String cookie = cookie();
        WebSocket socket = connect(cookie, new Inbox());
        try {
            ObjectNode request = MAPPER.createObjectNode().put("actorId", "mender").put("key", "card:Mending Touch")
                    .put("targetActorId", "patient").put("actionId", "greater-hp");
            var upgraded = featureHttp(cookie, request);
            assertEquals(200, upgraded.statusCode(), upgraded.body());
            assertEquals(2, MAPPER.readTree(store.loadSheet(campaign.getId(), "patient")).path("hp").path("on").asInt());
            var repeated = featureHttp(cookie, request.put("actionId", "greater-stress"));
            assertEquals(400, repeated.statusCode(), repeated.body());
            var normal = featureHttp(cookie, request.put("actionId", "stress"));
            assertEquals(200, normal.statusCode(), normal.body());
            assertEquals(2, MAPPER.readTree(store.loadSheet(campaign.getId(), "patient")).path("stress").path("on").asInt());
            assertEquals(2, MAPPER.readTree(store.loadSheet(campaign.getId(), "mender")).path("hope").path("on").asInt());
        } finally { socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join(); }
    }

    @Test
    void druidRegenerationRollsHealingOnTheHostAndReportsActualRecovery() throws Exception {
        ObjectNode source = communitySheet("Seaborne");
        var features = source.putArray("features");
        for (JsonNode entry : MAPPER.readTree(compendiumDir.resolve("classes.json").toFile()).path("entries"))
            if ("druid-warden-of-renewal-foundation".equals(entry.path("id").asText()))
                features.addAll((com.fasterxml.jackson.databind.node.ArrayNode) entry.path("features"));
        campaign.getActors().add(Campaign.Actor.builder().id("druid").name("Druid").ownerId(playerId()).build());
        campaign.getActors().add(Campaign.Actor.builder().id("patient").name("Patient").ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), "druid", source.toString());
        ObjectNode patient = communitySheet("Seaborne");
        store.saveSheet(campaign.getId(), "patient", patient.toString());
        String cookie = cookie();
        WebSocket socket = connect(cookie, new Inbox());
        try {
        HttpResponse<String> healed = featureHttp(cookie, MAPPER.createObjectNode().put("actorId", "druid")
                .put("key", "feat:Regeneration").put("actionId", "use").put("targetActorId", "patient"));
        assertEquals(200, healed.statusCode(), healed.body());
        JsonNode saved = MAPPER.readTree(store.loadSheet(campaign.getId(), "patient"));
        assertTrue(saved.path("hp").path("on").asInt() >= 0);
        assertTrue(saved.path("hp").path("on").asInt() <= 3);
        assertEquals(2, MAPPER.readTree(store.loadSheet(campaign.getId(), "druid")).path("hope").path("on").asInt());
        assertTrue(store.historyTail(campaign.getId(), 20).stream()
                .anyMatch(m -> m.path("text").asText().contains("Patient: Cleared") && m.path("text").asText().contains("1d4 = ")));
        } finally { socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join(); }
    }

    @Test
    void cannonballInvitationCanBeClaimedByExactlyOneAllyUsingTheChosenTrait() throws Exception {
        ObjectNode source = communitySheet("Seaborne");
        var form = source.putArray("effects").addObject().put("name", "Beastform: Armored Sentry").put("beastform", true);
        var features = form.putArray("features");
        for (JsonNode entry : MAPPER.readTree(compendiumDir.resolve("classes.json").toFile()).path("entries"))
            if ("druid".equals(entry.path("id").asText()))
                for (JsonNode feature : entry.path("features"))
                    for (JsonNode category : feature.path("automation").path("beastforms"))
                        if ("Armored Sentry".equals(category.path("name").asText()))
                            features.addAll((com.fasterxml.jackson.databind.node.ArrayNode) category.path("features"));
        campaign.getActors().add(Campaign.Actor.builder().id("druid").name("Druid").ownerId(playerId()).build());
        campaign.getActors().add(Campaign.Actor.builder().id("thrower").name("Thrower").ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), "druid", source.toString());
        store.saveSheet(campaign.getId(), "thrower", communitySheet("Seaborne").toString());
        String cookie = cookie();
        HttpResponse<String> launched = featureHttp(cookie, MAPPER.createObjectNode().put("actorId", "druid")
                .put("key", "feat:Cannonball").put("actionId", "launch"));
        assertEquals(200, launched.statusCode(), launched.body());
        ObjectNode invitation = (ObjectNode) store.historyTail(campaign.getId(), 20).stream()
                .filter(m -> m.has("actionBenefits")).findFirst().orElseThrow();
        assertTrue(invitation.path("actionBenefits").path("allowSourceOwner").asBoolean());
        assertEquals(4, MAPPER.readTree(store.loadSheet(campaign.getId(), "druid")).path("stress").path("on").asInt());
        ObjectNode claim = MAPPER.createObjectNode().put("actorId", "thrower").put("benefitId", "strength");
        claim.putObject("roll").setAll(MAPPER.createObjectNode().put("id", invitation.path("id").asLong())
                .put("ts", invitation.path("ts").asLong()));
        HttpResponse<String> claimed = featureHttp(cookie, claim);
        assertEquals(200, claimed.statusCode(), claimed.body());
        JsonNode grant = MAPPER.readTree(store.loadSheet(campaign.getId(), "thrower")).path("effects").get(0);
        assertEquals("Strength", grant.path("features").get(0).path("automation").path("actions").get(0).path("actionRoll").path("trait").asText());
        assertEquals("d12+2", grant.path("features").get(0).path("automation").path("actions").get(1).path("damage").path("dice").asText());
        assertEquals(400, featureHttp(cookie, claim).statusCode());
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
    void restartingHostingKeepsUsesUntilTheDmEndsTheSceneOrStartsANewSession() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("actor").name("Alice").ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), "actor", """
                {"featureRevision":0,"usage":{"session":{"used":1,"per":"session"},
                "scene":{"used":1,"per":"scene"},"rest":{"used":1,"per":"longRest"}},
                "effects":[{"id":"e1","name":"Fury","until":"scene"},{"id":"e2","name":"Rally","until":"session"},
                {"id":"e3","name":"Ward","until":"manual"}]}
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
            JsonNode afterRestart = MAPPER.readTree(store.loadSheet(campaign.getId(), "actor"));
            assertTrue(afterRestart.path("usage").has("session"));
            assertTrue(afterRestart.path("usage").has("scene"));
            assertEquals(3, afterRestart.path("effects").size());

            assertNull(server.endScene());
            JsonNode state = nextInbox.next("sheetFeature");
            assertFalse(state.path("usage").has("scene"));
            assertTrue(state.path("usage").has("session"));
            assertEquals(1, state.path("featureRevision").asInt());
            JsonNode sceneMessage = nextInbox.next("chat");
            assertEquals("sceneEnd", sceneMessage.path("system").asText());
            assertTrue(sceneMessage.path("text").asText().startsWith("SCENE ENDED\n"));
            assertTrue(sceneMessage.path("text").asText().contains("Alice"));
            JsonNode afterScene = MAPPER.readTree(store.loadSheet(campaign.getId(), "actor"));
            assertFalse(afterScene.path("usage").has("scene"));
            assertTrue(afterScene.path("usage").has("session"));
            assertEquals(List.of("Rally", "Ward"), java.util.stream.StreamSupport.stream(afterScene.path("effects").spliterator(), false)
                    .map(e -> e.path("name").asText()).toList());

            assertNull(server.newSession());
            assertEquals("newSession", nextInbox.next("chat").path("system").asText());
            JsonNode afterSession = MAPPER.readTree(store.loadSheet(campaign.getId(), "actor"));
            assertFalse(afterSession.path("usage").has("session"));
            assertEquals(1, afterSession.path("usage").path("rest").path("used").asInt());
            assertEquals(List.of("Ward"), java.util.stream.StreamSupport.stream(afterSession.path("effects").spliterator(), false)
                    .map(e -> e.path("name").asText()).toList());
            assertTrue(store.historyTail(campaign.getId(), 20).stream().anyMatch(m -> "newSession".equals(m.path("system").asText())));
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
            ObjectNode request = MAPPER.createObjectNode().put("actorId", "source").put("category", "item").put("offerQuantity", 2);
            request.set("item", original.path("inventory").get(0));
            HttpResponse<String> post = cardHttp(cookie, "", request);
            assertEquals(200, post.statusCode(), post.body());
            String id = MAPPER.readTree(post.body()).path("cardId").asText();
            JsonNode message = inbox.next("chat");
            assertEquals("Mara", message.path("from").asText());
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
    void goldOffersTransferChosenQuantitiesFromPlayersAndTheDm() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("source").name("Mara").ownerId(playerId()).build());
        campaign.getActors().add(Campaign.Actor.builder().id("target").name("Rook").ownerId(playerId()).build());
        store.saveSheet(campaign.getId(), "source", "{\"gold\":10,\"inventory\":[]}");
        store.saveSheet(campaign.getId(), "target", "{\"gold\":1,\"inventory\":[]}");
        server.publish(state("m1", 0), "m1/l1");
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            JsonNode hello = inbox.next("hello");
            assertTrue(hello.path("goldTransfers").asBoolean());
            assertTrue(hello.path("grabQuantity").asBoolean());
            ObjectNode request = MAPPER.createObjectNode().put("actorId", "source").put("category", "gold");
            request.putObject("item").put("name", "Gold");
            assertEquals(400, cardHttp(cookie, "", request).statusCode(), "Gold links are never posted without a quantity");
            request.put("offerQuantity", 11);
            assertEquals(400, cardHttp(cookie, "", request).statusCode(), "Cannot offer more gold than carried");
            request.put("offerQuantity", 6);
            var offer = cardHttp(cookie, "", request);
            assertEquals(200, offer.statusCode(), offer.body());
            String id = MAPPER.readTree(offer.body()).path("cardId").asText();
            ObjectNode grab = MAPPER.createObjectNode().put("actorId", "target").put("quantity", 7);
            assertEquals(409, cardHttp(cookie, "/" + id + "/grab", grab).statusCode(), "Cannot grab more than offered");
            var grabbed = cardHttp(cookie, "/" + id + "/grab", grab.put("quantity", 4));
            assertEquals(200, grabbed.statusCode(), grabbed.body());
            assertEquals(6, MAPPER.readTree(store.loadSheet(campaign.getId(), "source")).path("gold").asInt());
            assertEquals(5, MAPPER.readTree(store.loadSheet(campaign.getId(), "target")).path("gold").asInt());
            assertEquals(2, MAPPER.readTree(cardHttp(cookie, "/" + id, null).body()).path("available").asInt());

            assertNull(server.dmOfferGold(500, ""));
            JsonNode dmCard = null;
            while (dmCard == null) {
                JsonNode chat = inbox.next("chat");
                if (chat.path("sharedCard").path("fromDm").asBoolean()) dmCard = chat.path("sharedCard");
            }
            assertEquals("gold", dmCard.path("category").asText());
            assertEquals(500, dmCard.path("available").asInt());
            String dmId = dmCard.path("cardId").asText();
            ObjectNode dmGrab = MAPPER.createObjectNode().put("actorId", "target").put("quantity", 120);
            grabbed = cardHttp(cookie, "/" + dmId + "/grab", dmGrab);
            assertEquals(200, grabbed.statusCode(), grabbed.body());
            assertEquals(125, MAPPER.readTree(store.loadSheet(campaign.getId(), "target")).path("gold").asInt());
            assertEquals(380, MAPPER.readTree(cardHttp(cookie, "/" + dmId, null).body()).path("available").asInt());
            assertEquals(200, cardHttp(cookie, "/" + dmId + "/grab", MAPPER.createObjectNode().put("actorId", "source").put("all", true)).statusCode());
            assertEquals(386, MAPPER.readTree(store.loadSheet(campaign.getId(), "source")).path("gold").asInt());
            assertEquals(409, cardHttp(cookie, "/" + dmId + "/grab", dmGrab.put("quantity", 1)).statusCode(), "DM budget is exhausted");
            assertNotNull(server.dmOfferGold(0, ""));
        } finally {
            ws.abort();
        }
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
    private final Map<String, Optional<String>> changedActorSheets = new java.util.concurrent.ConcurrentHashMap<>();

    @Test
    void savingPlayerResourcesNotifiesTheDmAfterPersistence() throws Exception {
        campaign.getActors().add(Campaign.Actor.builder().id("hero").name("Hero").ownerId(playerId()).build());
        String sheet = "{\"name\":\"Hero\",\"hp\":{\"slots\":6,\"on\":2},\"stress\":{\"slots\":6,\"on\":3}}";
        var response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/sheet/hero"))
                .header("Cookie", cookie()).PUT(HttpRequest.BodyPublishers.ofString(sheet)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        JsonNode notifiedSheet = MAPPER.readTree(changedActorSheets.get("hero").orElseThrow());
        assertEquals(2, notifiedSheet.path("hp").path("on").asInt());
        assertEquals(3, notifiedSheet.path("stress").path("on").asInt());
        assertEquals(new ActorTokenResources(2, 6, 3, 6, null, null), ActorTokenResources.load(campaign, store).get("hero"));
    }

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
                    public boolean supportsTokenRotation() { return rotationSupported; }

                    @Override
                    public boolean supportsPortalControls() { return portalSupported; }

                    @Override
                    public boolean supportsMapPings() { return mapPingsSupported; }

                    @Override
                    public CompletableFuture<OnlineSessionServer.MoveResult> mapPing(String playerId, String mapId,
                                                                                     String levelId, double x, double y) {
                        assertEquals(OnlineSessionServerTest.this.playerId(), playerId);
                        assertEquals("m1", mapId);
                        assertEquals("l1", levelId);
                        mapPings.add(playerId);
                        ObjectNode next = state(mapId, 5);
                        next.putArray("pings").addObject().put("x", x).put("y", y)
                                .put("startMs", System.currentTimeMillis()).put("durationMs", 2000);
                        server.publish(next, "m1/l1");
                        return CompletableFuture.completedFuture(OnlineSessionServer.MoveResult.accepted());
                    }

                    @Override
                    public CompletableFuture<OnlineSessionServer.MoveResult> togglePortal(String playerId, String id,
                                                                                          String mapId, String levelId) {
                        assertEquals(OnlineSessionServerTest.this.playerId(), playerId);
                        assertEquals("door", id);
                        assertEquals("m1", mapId);
                        assertEquals("l1", levelId);
                        portals.add(id);
                        return CompletableFuture.completedFuture(portalResult);
                    }

                    @Override
                    public CompletableFuture<OnlineSessionServer.MoveResult> rotateToken(String playerId, String tokenId,
                                                                                          String mapId, String levelId, double rotationDeg) {
                        assertEquals(OnlineSessionServerTest.this.playerId(), playerId);
                        assertEquals("t1", tokenId);
                        assertEquals("m1", mapId);
                        assertEquals("l1", levelId);
                        rotations.add(rotationDeg);
                        return CompletableFuture.completedFuture(OnlineSessionServer.MoveResult.accepted());
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
                    public void actorSheetChanged(String actorId) {
                        try {
                            changedActorSheets.put(actorId, Optional.ofNullable(store.loadSheet(campaign.getId(), actorId)));
                        } catch (java.io.IOException ex) {
                            throw new AssertionError(ex);
                        }
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

        JsonNode nextBenefit() throws Exception {
            long end = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < end) {
                JsonNode node = messages.poll(200, TimeUnit.MILLISECONDS);
                if (node != null && "chat".equals(node.path("type").asText()) && node.has("actionBenefits")) return node;
            }
            throw new AssertionError("No claimable action message");
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
    void acceptedMovesAreRelayedToOtherConnectionsOnlyForPublishedTokens() throws Exception {
        server.publish(state("m1", 0), "m1/l1");
        String cookie = cookie();
        Inbox mover = new Inbox();
        Inbox watcher = new Inbox();
        WebSocket moverWs = connect(cookie, mover);
        WebSocket watcherWs = connect(cookie, watcher);
        try {
            mover.next("snapshot");
            watcher.next("snapshot");
            long seq = server.seq();
            moverWs.sendText("{\"type\":\"command\",\"cmdId\":\"c1\",\"kind\":\"moveToken\",\"tokenId\":\"t1\",\"x\":1.5,\"y\":2}", true).join();
            assertEquals("c1", mover.next("ack").path("cmdId").asText());
            JsonNode relayed = watcher.next("tokenMoved");
            assertEquals("m1/l1", relayed.path("mapKey").asText());
            assertEquals("t1", relayed.path("id").asText());
            assertEquals(1.5, relayed.path("x").asDouble());
            assertEquals(2, relayed.path("y").asDouble());
            assertEquals(seq, server.seq());
            Thread.sleep(150);
            assertTrue(mover.messages.stream().noneMatch(m -> "tokenMoved".equals(m.path("type").asText())));

            moverWs.sendText("{\"type\":\"command\",\"cmdId\":\"c2\",\"kind\":\"moveToken\",\"tokenId\":\"hidden\",\"x\":3,\"y\":3}", true).join();
            assertEquals("c2", mover.next("ack").path("cmdId").asText());
            Thread.sleep(150);
            assertTrue(watcher.messages.stream().noneMatch(m -> "tokenMoved".equals(m.path("type").asText())));
            server.setPaused(true);
            moverWs.sendText("{\"type\":\"command\",\"cmdId\":\"c3\",\"kind\":\"moveToken\",\"tokenId\":\"t1\",\"x\":4,\"y\":4}", true).join();
            assertEquals("c3", mover.next("reject").path("cmdId").asText());
            Thread.sleep(150);
            assertTrue(watcher.messages.stream().noneMatch(m -> "tokenMoved".equals(m.path("type").asText())));
        } finally {
            moverWs.abort();
            watcherWs.abort();
        }
    }

    @Test
    void rotationCapabilityAndCommandsAreAdditiveAndValidated() throws Exception {
        rotationSupported = true;
        server.publish(state("m1", 5), "m1/l1");
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie(), inbox);
        try {
            JsonNode hello = inbox.next("hello");
            assertEquals(OnlineProtocol.VERSION, hello.path("protocol").asInt());
            assertTrue(hello.path("tokenRotation").asBoolean());
            ObjectNode command = MAPPER.createObjectNode().put("type", "command").put("cmdId", "rotate")
                    .put("kind", "rotateToken").put("tokenId", "t1").put("mapId", "m1").put("levelId", "l1").put("rotationDeg", 90);
            for (String invalidField : List.of("tokenId", "mapId", "levelId", "rotationDeg")) {
                ObjectNode invalid = command.deepCopy();
                invalid.remove(invalidField);
                ws.sendText(invalid.toString(), true).join();
                assertEquals("rotate", inbox.next("reject").path("cmdId").asText());
            }
            ws.sendText(command.deepCopy().put("rotationDeg", "NaN").toString(), true).join();
            inbox.next("reject");
            assertTrue(rotations.isEmpty());
            server.setPaused(true);
            ws.sendText(command.toString(), true).join();
            assertEquals("paused", inbox.next("reject").path("reason").asText());
            assertTrue(rotations.isEmpty());
            server.setPaused(false);
            ws.sendText(command.toString(), true).join();
            assertEquals("rotate", inbox.next("ack").path("cmdId").asText());
            assertEquals(List.of(90.0), rotations);
        } finally { ws.abort(); }
    }

    @Test
    void mapPingCapabilityValidatesCommandsAndBroadcastsToEveryConnection() throws Exception {
        mapPingsSupported = true;
        server.publish(state("m1", 5), "m1/l1");
        String cookie = cookie();
        Inbox first = new Inbox(), second = new Inbox();
        WebSocket sender = connect(cookie, first), watcher = connect(cookie, second);
        try {
            assertTrue(first.next("hello").path("mapPings").asBoolean());
            assertEquals(OnlineProtocol.VERSION, second.next("hello").path("protocol").asInt());
            first.next("snapshot"); second.next("snapshot");
            ObjectNode command = MAPPER.createObjectNode().put("type", "command").put("cmdId", "ping")
                    .put("kind", "mapPing").put("mapId", "m1").put("levelId", "l1").put("x", 123).put("y", 456);
            for (String field : List.of("mapId", "levelId", "x", "y")) {
                ObjectNode invalid = command.deepCopy();
                invalid.remove(field);
                sender.sendText(invalid.toString(), true).join();
                assertEquals("ping", first.next("reject").path("cmdId").asText());
            }
            sender.sendText(command.deepCopy().put("x", "NaN").toString(), true).join();
            first.next("reject");
            server.setPaused(true);
            sender.sendText(command.toString(), true).join();
            assertEquals("paused", first.next("reject").path("reason").asText());
            assertTrue(mapPings.isEmpty());
            server.setPaused(false);
            sender.sendText(command.toString(), true).join();
            for (Inbox inbox : List.of(first, second)) {
                JsonNode op = inbox.next("delta").path("ops").get(0);
                assertEquals("set", op.path("op").asText());
                assertEquals("pings", op.path("path").asText());
                assertEquals(123, op.path("value").get(0).path("x").asDouble());
                assertEquals(456, op.path("value").get(0).path("y").asDouble());
            }
            assertEquals("ping", first.next("ack").path("cmdId").asText());
            assertEquals(List.of(playerId()), mapPings);
        } finally { sender.abort(); watcher.abort(); }
    }

    @Test
    void hostsWithoutMapPingsRejectOnlyTheAdditiveCommand() throws Exception {
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie(), inbox);
        try {
            assertFalse(inbox.next("hello").path("mapPings").asBoolean());
            ws.sendText("{\"type\":\"command\",\"cmdId\":\"p\",\"kind\":\"mapPing\",\"mapId\":\"m1\","
                    + "\"levelId\":\"l1\",\"x\":1,\"y\":2}", true).join();
            assertEquals("p", inbox.next("reject").path("cmdId").asText());
            assertTrue(mapPings.isEmpty());
        } finally { ws.abort(); }
    }

    @Test
    void portalCapabilityCommandsAndPauseAreValidated() throws Exception {
        portalSupported = true;
        server.publish(state("m1", 5), "m1/l1");
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie(), inbox);
        try {
            JsonNode hello = inbox.next("hello");
            assertEquals(OnlineProtocol.VERSION, hello.path("protocol").asInt());
            assertTrue(hello.path("portalControls").asBoolean());
            ObjectNode command = MAPPER.createObjectNode().put("type", "command").put("cmdId", "portal")
                    .put("kind", "togglePortal").put("id", "door").put("mapId", "m1").put("levelId", "l1");
            for (String field : List.of("id", "mapId", "levelId")) {
                for (var invalid : List.of(command.deepCopy().putNull(field), command.deepCopy().put(field, ""),
                        command.deepCopy().put(field, 42))) {
                    ws.sendText(invalid.toString(), true).join();
                    assertEquals("portal", inbox.next("reject").path("cmdId").asText());
                }
            }
            assertTrue(portals.isEmpty());
            server.setPaused(true);
            ws.sendText(command.toString(), true).join();
            assertEquals("paused", inbox.next("reject").path("reason").asText());
            assertTrue(portals.isEmpty());
            server.setPaused(false);
            portalResult = OnlineSessionServer.MoveResult.rejected("locked");
            ws.sendText(command.toString(), true).join();
            assertEquals("locked", inbox.next("reject").path("reason").asText());
            portalResult = OnlineSessionServer.MoveResult.accepted();
            ws.sendText(command.toString(), true).join();
            assertEquals("portal", inbox.next("ack").path("cmdId").asText());
            assertEquals(List.of("door", "door"), portals);
        } finally { ws.abort(); }
    }

    @Test
    void hostsWithoutPortalControlsRejectNewCommands() throws Exception {
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie(), inbox);
        try {
            assertFalse(inbox.next("hello").path("portalControls").asBoolean());
            ws.sendText("{\"type\":\"command\",\"cmdId\":\"p\",\"kind\":\"togglePortal\",\"id\":\"door\","
                    + "\"mapId\":\"m1\",\"levelId\":\"l1\"}", true).join();
            assertEquals("p", inbox.next("reject").path("cmdId").asText());
            assertTrue(portals.isEmpty());
        } finally { ws.abort(); }
    }

    @Test
    void hostsWithoutRotationKeepLegacyMovementAndRejectUnsupportedRotation() throws Exception {
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie(), inbox);
        try {
            assertFalse(inbox.next("hello").path("tokenRotation").asBoolean());
            ws.sendText("{\"type\":\"command\",\"cmdId\":\"r\",\"kind\":\"rotateToken\",\"tokenId\":\"t1\","
                    + "\"mapId\":\"m1\",\"levelId\":\"l1\",\"rotationDeg\":90}", true).join();
            assertEquals("r", inbox.next("reject").path("cmdId").asText());
            assertTrue(rotations.isEmpty());
            ws.sendText("{\"type\":\"command\",\"cmdId\":\"m\",\"kind\":\"moveToken\",\"tokenId\":\"t1\",\"x\":1,\"y\":1}", true).join();
            assertEquals("m", inbox.next("ack").path("cmdId").asText());
            assertEquals(1, moves.size());
        } finally { ws.abort(); }
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
        JsonNode hello = first.next("hello");
        assertEquals(campaign.player(playerId()).getColor(), hello.path("accountColor").asText());
        assertTrue(hello.path("measurementColors").asBoolean());
        assertEquals(OnlineProtocol.VERSION, hello.path("protocol").asInt());
        first.next("snapshot");
        first.next("measurements");
        JsonNode roster = first.next("roster");
        assertEquals(Campaign.DEFAULT_DM_COLOR, roster.path("dmColor").asText());
        assertEquals(campaign.player(playerId()).getColor(), roster.path("players").get(0).path("color").asText());
        ws.sendText("""
                {"type":"measurement","cmdId":"m1","action":"upsert","mapKey":"m1/l1",
                 "id":"ruler","kind":"distance","x1":0,"y1":0,"x2":30,"y2":40,"ownerId":"forged"}
                """, true).join();
        JsonNode created = first.next("measurements").path("items").get(0);
        assertEquals(playerId(), created.path("ownerId").asText());
        assertEquals(campaign.player(playerId()).getColor(), created.path("color").asText());
        ws.sendText("""
                {"type":"measurement","cmdId":"colored","action":"upsert","mapKey":"m1/l1",
                 "id":"ruler","kind":"distance","x1":0,"y1":0,"x2":30,"y2":40,"color":"#ff9100"}
                """, true).join();
        assertEquals("#ff9100", first.next("measurements").path("items").get(0).path("color").asText());
        ws.sendText("""
                {"type":"measurement","cmdId":"bad-color","action":"upsert","mapKey":"m1/l1",
                 "id":"ruler","kind":"distance","x1":0,"y1":0,"x2":30,"y2":40,"color":"red"}
                """, true).join();
        assertEquals("bad-color", first.next("reject").path("cmdId").asText());
        assertEquals("#ff9100", first.next("measurements").path("items").get(0).path("color").asText());
        Inbox late = new Inbox();
        WebSocket second = connect(cookie, late);
        assertEquals(1, late.next("measurements").path("items").size());
        server.dmMeasurement(server.measurements().getFirst().translate(10, 20), false);
        JsonNode moved = first.next("measurements").path("items").get(0);
        assertEquals(10, moved.path("x1").asInt());
        assertEquals("#ff9100", moved.path("color").asText());
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
    void rosterPronounsFollowSavedSheetAndFirstEnabledActor() throws Exception {
        Campaign.Actor first = Campaign.Actor.builder().id("pronouns-first").name("Mara").ownerId(playerId()).build();
        Campaign.Actor second = Campaign.Actor.builder().id("pronouns-second").name("Rook").ownerId(playerId()).build();
        campaign.getActors().add(first);
        campaign.getActors().add(second);
        ObjectNode sheet = warriorSheet("Brave");
        sheet.put("name", "Mara").put("pronouns", "she/her");
        sheet.putObject("description").put("Name", "Mara").put("Pronouns", "they/them");
        store.saveSheet(campaign.getId(), first.getId(), sheet.toString());
        store.saveSheet(campaign.getId(), second.getId(), "{\"pronouns\":\"he/him\"}");
        String cookie = cookie();
        Inbox inbox = new Inbox();
        WebSocket ws = connect(cookie, inbox);
        try {
            inbox.next("hello");
            JsonNode initial = inbox.next("roster").path("players").get(0);
            assertEquals("Mara", initial.path("chatIdentity").asText());
            assertEquals("they/them", initial.path("pronouns").asText());
            sheet.put("pronouns", "xe/xem");
            sheet.withObject("/description").put("Pronouns", "xe/xem");
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/api/sheet/" + first.getId()))
                    .header("Cookie", cookie).header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(sheet.toString())).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            assertEquals("xe/xem", nextRosterIdentity(inbox, "Mara", "xe/xem").path("pronouns").asText());
            first.setDisabled(true);
            server.setCampaign(campaign);
            JsonNode changed = nextRosterIdentity(inbox, "Rook", "he/him");
            assertEquals("Rook", changed.path("chatIdentity").asText());
            assertEquals("he/him", changed.path("pronouns").asText());
            second.setDisabled(true);
            server.setCampaign(campaign);
            changed = nextRosterIdentity(inbox, "", "");
            assertEquals("", changed.path("chatIdentity").asText());
            assertEquals("", changed.path("pronouns").asText());
        } finally {
            ws.abort();
        }
    }

    private static JsonNode nextRosterIdentity(Inbox inbox, String identity, String pronouns) throws Exception {
        long end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end) {
            JsonNode message = inbox.messages.poll(200, TimeUnit.MILLISECONDS);
            if (message == null || !"roster".equals(message.path("type").asText())) continue;
            JsonNode player = message.path("players").path(0);
            if (identity.equals(player.path("chatIdentity").asText()) && pronouns.equals(player.path("pronouns").asText()))
                return player;
        }
        throw new AssertionError("No roster for " + identity + " with pronouns " + pronouns);
    }

    @Test
    void chatNamesFollowFirstEnabledActorAndFallbackToAccount() throws Exception {
        campaign.player(playerId()).setChatIdentity("Legacy name");
        Campaign.Actor firstActor = Campaign.Actor.builder().id("first").name("Mara").ownerId(playerId()).build();
        Campaign.Actor secondActor = Campaign.Actor.builder().id("second").name("Rook").ownerId(playerId()).build();
        firstActor.setArtwork("portrait-one");
        secondActor.setArtwork("portrait-two");
        for (String hash : List.of("portrait-one", "portrait-two")) {
            Path file = store.artworkPath(campaign.getId(), hash);
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.write(file, new byte[]{1, 2, 3});
        }
        campaign.getActors().add(Campaign.Actor.builder().id("unowned").name("Other").ownerId("other").build());
        campaign.getActors().add(firstActor);
        campaign.getActors().add(secondActor);
        String cookie = cookie();
        Inbox first = new Inbox();
        WebSocket ws = connect(cookie, first);
        JsonNode hello = first.next("hello");
        assertFalse(hello.path("chatIdentity").asBoolean());
        assertEquals(OnlineProtocol.VERSION, hello.path("protocol").asInt());
        String accountName = hello.path("playerName").asText();
        assertEquals("Mara", first.next("roster").path("players").get(0).path("chatIdentity").asText());
        Inbox second = new Inbox();
        WebSocket other = connect(cookie, second);
        second.next("hello");
        second.next("roster");
        first.next("roster");
        try {
            other.sendText("{\"type\":\"chat\",\"text\":\"hello\"}", true).join();
            JsonNode original = first.next("chat");
            assertEquals("Mara", original.path("from").asText());
            assertEquals("/a/portrait-one.img", original.path("fromArtwork").asText());
            other.sendText("{\"type\":\"roll\",\"expression\":\"1d6\"}", true).join();
            JsonNode roll = first.next("roll");
            assertEquals("Mara", roll.path("from").asText());
            assertEquals("/a/portrait-one.img", roll.path("fromArtwork").asText());
            server.dmChat("private", playerId());
            assertEquals("Mara", first.next("chat").path("to").asText());
            ws.sendText("{\"type\":\"chatIdentity\",\"identity\":\"Override\"}", true).join();
            assertTrue(first.next("error").path("message").asText().contains("automatically"));
            firstActor.setDisabled(true);
            server.setCampaign(campaign);
            assertEquals("Rook", first.next("roster").path("players").get(0).path("chatIdentity").asText());
            other.sendText("{\"type\":\"chat\",\"text\":\"second actor\"}", true).join();
            JsonNode next = first.next("chat");
            assertEquals("Rook", next.path("from").asText());
            assertEquals("/a/portrait-two.img", next.path("fromArtwork").asText());
            assertEquals("/a/portrait-one.img", store.historyTail(campaign.getId(), 50).stream()
                    .filter(item -> item.path("id").equals(original.path("id"))).findFirst().orElseThrow().path("fromArtwork").asText());
            secondActor.setDisabled(true);
            server.setCampaign(campaign);
            assertEquals("", first.next("roster").path("players").get(0).path("chatIdentity").asText());
            other.sendText("{\"type\":\"chat\",\"text\":\"account fallback\"}", true).join();
            JsonNode fallback = first.next("chat");
            assertEquals(accountName, fallback.path("from").asText());
            assertFalse(fallback.has("fromArtwork"));
            firstActor.setDisabled(false);
            server.setCampaign(campaign);
            assertEquals("Mara", first.next("roster").path("players").get(0).path("chatIdentity").asText());
            firstActor.setName("Mara Renamed");
            server.setCampaign(campaign);
            assertEquals("Mara Renamed", first.next("roster").path("players").get(0).path("chatIdentity").asText());
            firstActor.setOwnerId("other");
            secondActor.setDisabled(false);
            server.setCampaign(campaign);
            assertEquals("Rook", first.next("roster").path("players").get(0).path("chatIdentity").asText());
            campaign.getActors().remove(secondActor);
            server.setCampaign(campaign);
            assertEquals("", first.next("roster").path("players").get(0).path("chatIdentity").asText());
            other.sendText("{\"type\":\"chat\",\"text\":\"no actors\"}", true).join();
            assertEquals(accountName, first.next("chat").path("from").asText());
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
