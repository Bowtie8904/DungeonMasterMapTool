package dmmt.online;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class DeathMovesTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path root;

    private ObjectNode sheet() {
        ObjectNode sheet = mapper.createObjectNode().put("level", 3);
        sheet.putObject("hp").put("slots", 6).put("on", 6);
        sheet.putObject("hope").put("total", 6).put("slots", 6).put("on", 6);
        sheet.putArray("effects");
        return sheet;
    }

    private Random dice(int... values) {
        return new Random() {
            int index;
            @Override
            public int nextInt(int bound) {
                int value = values[index++];
                assertTrue(value >= 1 && value <= bound);
                return value - 1;
            }
        };
    }

    @Test
    void blazeOnlyReportsTheFinalActionAndDeath() {
        ObjectNode sheet = sheet();
        ObjectNode before = sheet.deepCopy();
        var result = DeathMoves.take(sheet, "blaze", dice());
        assertNull(result.dice());
        assertTrue(result.consequence().contains("critically succeeds"));
        assertTrue(result.consequence().contains("GM's discretion"));
        assertEquals(before, sheet);
    }

    @Test
    void avoidDeathScarsAtOrBelowLevelAndClampsHopeWithoutBlockingActions() {
        for (int roll : new int[]{1, 3, 4, 12}) {
            ObjectNode sheet = sheet();
            var result = DeathMoves.take(sheet, "avoid", dice(roll));
            assertEquals(roll, result.dice().total());
            assertEquals(roll <= 3 ? 1 : 0, sheet.path("scars").asInt());
            assertEquals(roll <= 3 ? 5 : 6, sheet.path("hope").path("slots").asInt());
            assertEquals(roll <= 3 ? 5 : 6, sheet.path("hope").path("on").asInt());
            var effect = sheet.path("effects").get(0);
            assertTrue(effect.path("blocksMovement").asBoolean());
            assertEquals("longRest", effect.path("until").asText());
            assertEquals(6, effect.path("unconsciousHp").asInt());
            assertFalse(effect.has("blocksActions"));
            assertThrows(IllegalArgumentException.class, () -> DeathMoves.take(sheet, "avoid", dice(1)));
        }
    }

    @Test
    void healingRemovesOnlyUnconsciousAndNeverHealsScars() {
        ObjectNode sheet = sheet();
        DeathMoves.take(sheet, "avoid", dice(1));
        sheet.withArray("effects").addObject().put("id", "other").put("blocksMovement", true);
        DeathMoves.sync(sheet);
        assertEquals(2, sheet.path("effects").size());
        ((ObjectNode) sheet.path("hp")).put("on", 5);
        DeathMoves.sync(sheet);
        assertEquals(1, sheet.path("effects").size());
        assertEquals("other", sheet.path("effects").get(0).path("id").asText());
        assertEquals(1, sheet.path("scars").asInt());
        assertEquals(5, sheet.path("hope").path("slots").asInt());
    }

    @Test
    void hostMovementCheckBlocksUnconsciousActorTokensAndAllowsThemAfterHealing() throws Exception {
        var controller = new OnlineSessionController(root, null);
        Campaign campaign = controller.store().create("Death moves");
        campaign.getActors().add(Campaign.Actor.builder().id("hero").name("Aria").build());
        var campaignField = OnlineSessionController.class.getDeclaredField("campaign");
        campaignField.setAccessible(true);
        campaignField.set(controller, campaign);
        var reason = OnlineSessionController.class.getDeclaredMethod("movementBlockReason", dmmt.model.DmProject.TokenInstance.class);
        reason.setAccessible(true);
        var token = new dmmt.model.DmProject.TokenInstance();
        token.setActorId("hero");
        token.setCampaignId(campaign.getId());
        ObjectNode sheet = sheet();
        controller.store().saveSheet(campaign.getId(), "hero", sheet.toString());
        assertNull(reason.invoke(controller, token));
        DeathMoves.take(sheet, "avoid", dice(1));
        controller.store().saveSheet(campaign.getId(), "hero", sheet.toString());
        assertNotNull(reason.invoke(controller, token));
        ((ObjectNode) sheet.path("hp")).put("on", 5);
        DeathMoves.sync(sheet);
        controller.store().saveSheet(campaign.getId(), "hero", sheet.toString());
        assertNull(reason.invoke(controller, token));
    }

    @Test
    void controllerResolvesSpectralStateFromCampaignActorAndNotTheFeatureAlone() throws Exception {
        var controller = new OnlineSessionController(root, null);
        Campaign campaign = controller.store().create("Spectral movement");
        campaign.getActors().add(Campaign.Actor.builder().id("hero").name("Aria").build());
        var field = OnlineSessionController.class.getDeclaredField("campaign");
        field.setAccessible(true);
        field.set(controller, campaign);
        var phase = OnlineSessionController.class.getDeclaredMethod("phaseMovement", dmmt.model.DmProject.TokenInstance.class);
        phase.setAccessible(true);
        var token = new dmmt.model.DmProject.TokenInstance();
        token.setActorId("hero"); token.setCampaignId(campaign.getId());
        ObjectNode sheet = sheet();
        var source = source(sheet, "feat:Spectral Form", "spectral");
        controller.store().saveSheet(campaign.getId(), "hero", sheet.toString());
        assertEquals(false, phase.invoke(controller, token));
        DeathMoves.take(sheet, mapper.createObjectNode().put("move", "spectral"), java.util.List.of(source), dice());
        controller.store().saveSheet(campaign.getId(), "hero", sheet.toString());
        assertEquals(true, phase.invoke(controller, token));
        token.setCampaignId("another-campaign");
        assertEquals(false, phase.invoke(controller, token));
        token.setCampaignId(campaign.getId());
        ((ObjectNode) sheet.path("hp")).put("on", 6);
        controller.store().saveSheet(campaign.getId(), "hero", sheet.toString());
        assertEquals(false, phase.invoke(controller, token));
    }

    @Test
    void scarsStackToZeroUsableHopeAndCanBeHealedExplicitly() {
        ObjectNode sheet = sheet().put("scars", 5);
        DeathMoves.take(sheet, "avoid", dice(1));
        assertEquals(6, sheet.path("scars").asInt());
        assertEquals(0, sheet.path("hope").path("slots").asInt());
        assertEquals(0, sheet.path("hope").path("on").asInt());
        sheet.put("scars", 5);
        DeathMoves.sync(sheet);
        assertEquals(1, sheet.path("hope").path("slots").asInt());
        assertEquals(0, sheet.path("hope").path("on").asInt());
    }

    @Test
    void riskItAllDistinguishesHopeFearAndCriticalWithoutApplyingRecovery() {
        for (int[] roll : new int[][]{{12, 1}, {1, 12}, {1, 1}, {12, 12}}) {
            ObjectNode sheet = sheet();
            ObjectNode before = sheet.deepCopy();
            var result = DeathMoves.take(sheet, "risk", dice(roll));
            assertEquals(roll[0], result.dice().rolls().get(0));
            assertEquals(roll[1], result.dice().rolls().get(1));
            assertTrue(result.consequence().contains(roll[0] == roll[1] ? "clear all Hit Points and Stress"
                    : roll[0] > roll[1] ? "clear 12 Hit Points and/or Stress" : "cross through the veil of death"));
            assertEquals(before, sheet);
        }
    }

    @Test
    void invalidMoveOrNonFullHpDoesNotRollOrMutate() {
        ObjectNode sheet = sheet();
        assertThrows(IllegalArgumentException.class, () -> DeathMoves.take(sheet, "invalid", dice()));
        ((ObjectNode) sheet.path("hp")).put("on", 5);
        ObjectNode before = sheet.deepCopy();
        for (String move : new String[]{"blaze", "avoid", "risk"})
            assertThrows(IllegalArgumentException.class, () -> DeathMoves.take(sheet, move, dice()));
        assertEquals(before, sheet);
    }

    private DeathMoves.Source source(ObjectNode sheet, String key, String kind, String... moves) {
        ObjectNode entry = sheet.withArray(key.startsWith("item:") ? "inventory" : key.startsWith("card:") ? "loadout" : "features")
                .addObject().put("name", key.substring(key.indexOf(':') + 1)).put("qty", 1);
        ObjectNode automation = entry.putObject("automation");
        ObjectNode metadata = automation.putObject("deathMove").put("kind", kind).put("bonus", 1);
        for (String move : moves) metadata.withArray("moves").add(move);
        return new DeathMoves.Source(key, entry.path("name").asText(), automation, entry);
    }

    @Test
    void bonusesUseAdjustedHopeButOnlyRawMatchingDiceAreCritical() {
        for (int[] values : new int[][]{{3, 4}, {4, 4}, {1, 12}}) {
            ObjectNode sheet = sheet();
            var reliquary = source(sheet, "item:Reliquary", "hopeBonus", "risk");
            var result = DeathMoves.take(sheet, mapper.createObjectNode().put("move", "risk"),
                    java.util.List.of(reliquary, reliquary), dice(values));
            assertEquals(1, result.deathResult().path("bonus").asInt());
            assertEquals(values[0] + 1, result.deathResult().path("budget").asInt());
            assertEquals(values[0] == values[1] ? "crit" : values[0] + 1 >= values[1] ? "hope" : "fear",
                    result.deathResult().path("outcome").asText());
            assertEquals(3, DiceRoller.breakdown(result.dice(), result.contributions(), 2).size());
        }
    }

    @Test
    void blessedCostsOnlyPositiveAcceptedSpendAndMaskRequiresWearingAndOneToken() {
        ObjectNode sheet = sheet();
        var blessed = source(sheet, "armor:Heroplate", "hopeSpend", "risk");
        ((ObjectNode) blessed.automation()).putObject("uses").put("max", 1).put("per", "longRest");
        var mask = source(sheet, "item:Mask", "mask", "risk", "avoid");
        sheet.putObject("resources").put("death-mask", 4);
        var sources = java.util.List.of(blessed, mask);
        var zero = DeathMoves.take(sheet, mapper.createObjectNode().put("move", "risk").put("blessedHope", 0), sources, dice(1, 2));
        assertEquals(0, zero.deathResult().path("bonus").asInt());
        assertFalse(sheet.has("usage"));
        var result = DeathMoves.take(sheet, mapper.createObjectNode().put("move", "risk").put("blessedHope", 2)
                .put("mask", true).put("wearingMask", true), sources, dice(1, 4));
        assertEquals("hope", result.deathResult().path("outcome").asText());
        assertEquals(3, result.deathResult().path("bonus").asInt());
        assertEquals(4, sheet.path("hope").path("on").asInt());
        assertEquals(3, sheet.path("resources").path("death-mask").asInt());
        assertEquals(1, sheet.path("usage").path(blessed.key()).path("used").asInt());
        assertThrows(IllegalArgumentException.class, () -> DeathMoves.take(sheet,
                mapper.createObjectNode().put("move", "risk").put("blessedHope", 1), sources, dice()));
        assertThrows(IllegalArgumentException.class, () -> DeathMoves.take(sheet,
                mapper.createObjectNode().put("move", "risk").put("mask", true), sources, dice()));
    }

    @Test
    void phoenixAndMaskStackOnScarCheckWithoutConsumingFeatherOrStackingCopies() {
        ObjectNode sheet = sheet();
        var feather = source(sheet, "item:Phoenix Feather", "hopeBonus", "avoid");
        var mask = source(sheet, "item:Mask", "mask", "avoid", "risk");
        sheet.putObject("resources").put("death-mask", 1);
        var result = DeathMoves.take(sheet, mapper.createObjectNode().put("move", "avoid")
                .put("mask", true).put("wearingMask", true), java.util.List.of(feather, feather, mask), dice(2));
        assertEquals(4, result.dice().total());
        assertEquals(0, sheet.path("scars").asInt());
        assertEquals(0, sheet.path("resources").path("death-mask").asInt());
        assertEquals(1, feather.entry().path("qty").asInt());
        assertTrue(sheet.path("effects").get(0).path("blocksMovement").asBoolean());
    }

    @Test
    void spectralClearsHpThenExpiresOnFurtherHealingOrFullHp() {
        ObjectNode sheet = sheet();
        var source = source(sheet, "feat:Spectral Form", "spectral");
        DeathMoves.take(sheet, mapper.createObjectNode().put("move", "spectral"), java.util.List.of(source), dice());
        assertEquals(5, sheet.path("hp").path("on").asInt());
        assertTrue(sheet.path("effects").get(0).path("phaseMovement").asBoolean());
        DeathMoves.sync(sheet);
        assertEquals(1, sheet.path("effects").size());
        ((ObjectNode) sheet.path("hp")).put("on", 6);
        DeathMoves.sync(sheet);
        assertTrue(sheet.path("effects").isEmpty());
        assertTrue(sheet.path("spectralBlocked").asBoolean());
        assertThrows(IllegalArgumentException.class, () -> DeathMoves.take(sheet,
                mapper.createObjectNode().put("move", "spectral"), java.util.List.of(source), dice()));
        ((ObjectNode) sheet.path("hp")).put("on", 5);
        DeathMoves.sync(sheet);
        assertFalse(sheet.path("spectralBlocked").asBoolean());
        ((ObjectNode) sheet.path("hp")).put("on", 6);
        DeathMoves.take(sheet, mapper.createObjectNode().put("move", "spectral"), java.util.List.of(source), dice());
        ((ObjectNode) sheet.path("hp")).put("on", 4);
        DeathMoves.sync(sheet);
        assertTrue(sheet.path("effects").isEmpty());
    }

    @Test
    void eggBattleHardenedAndUnbreakableMutateOnlyTheirApprovedState() {
        ObjectNode eggSheet = sheet();
        var egg = source(eggSheet, "item:Cathartoid Egg", "egg");
        DeathMoves.take(eggSheet, mapper.createObjectNode().put("move", "egg"), java.util.List.of(egg), dice());
        assertEquals(0, egg.entry().path("qty").asInt());
        assertEquals(6, eggSheet.path("hp").path("on").asInt());
        assertTrue(eggSheet.path("deathNarrative").asText().contains("avian"));
        ObjectNode blade = sheet();
        var battle = source(blade, "card:Battle-Hardened", "battleHardened");
        ((ObjectNode) battle.automation()).putObject("uses").put("max", 1).put("per", "longRest");
        DeathMoves.take(blade, mapper.createObjectNode().put("move", "battleHardened"), java.util.List.of(battle), dice());
        assertEquals(5, blade.path("hp").path("on").asInt());
        assertEquals(5, blade.path("hope").path("on").asInt());
        assertEquals(1, blade.path("usage").path(battle.key()).path("used").asInt());
        ObjectNode valor = sheet().put("permanentHp", 2);
        var unbreakable = source(valor, "card:Unbreakable", "unbreakable");
        var result = DeathMoves.take(valor, mapper.createObjectNode().put("move", "unbreakable"), java.util.List.of(unbreakable), dice(6));
        assertEquals(2, valor.path("hp").path("on").asInt());
        assertTrue(valor.path("loadout").isEmpty());
        assertEquals("Unbreakable", valor.path("vault").get(0).path("name").asText());
        assertTrue(result.consequence().contains("Cleared 4 HP"));
        assertEquals(1, valor.path("compendiumRevision").asInt());
    }

    private ObjectNode riskRoll(ObjectNode sheet, int hope, int fear) {
        sheet.putObject("stress").put("slots", 6).put("on", 5);
        var undying = source(sheet, "feat:Won't Stay Dead", "undying");
        var result = DeathMoves.take(sheet, mapper.createObjectNode().put("move", "risk"), java.util.List.of(undying), dice(hope, fear));
        ObjectNode roll = mapper.createObjectNode().put("deathMove", "risk");
        roll.set("deathResult", result.deathResult());
        return roll;
    }

    @Test
    void recoveryConvertsOnceAndPermanentHpSurvivesHealing() {
        ObjectNode sheet = sheet();
        ObjectNode roll = riskRoll(sheet, 3, 12);
        assertThrows(IllegalArgumentException.class, () -> DeathMoves.recover(sheet, roll,
                mapper.createObjectNode().put("hp", 3), true));
        DeathMoves.recover(sheet, roll, mapper.createObjectNode().put("undying", true), true);
        assertEquals(1, sheet.path("permanentHp").asInt());
        assertEquals("hope", roll.path("deathResult").path("outcome").asText());
        assertThrows(IllegalArgumentException.class, () -> DeathMoves.recover(sheet, roll,
                mapper.createObjectNode().put("undying", true), true));
        assertThrows(IllegalArgumentException.class, () -> DeathMoves.recover(sheet, roll,
                mapper.createObjectNode().put("hp", 3).put("stress", 1), true));
        DeathMoves.recover(sheet, roll, mapper.createObjectNode().put("hp", 2).put("stress", 1), true);
        assertEquals(4, sheet.path("hp").path("on").asInt());
        assertEquals(4, sheet.path("stress").path("on").asInt());
        assertThrows(IllegalArgumentException.class, () -> DeathMoves.recover(sheet, roll,
                mapper.createObjectNode(), true));
        ((ObjectNode) sheet.path("hp")).put("on", 0);
        DeathMoves.sync(sheet);
        assertEquals(1, sheet.path("hp").path("on").asInt());
    }

    @Test
    void permanentLastHpMeansDeathAndCriticalRecoveryRespectsPermanentHp() {
        ObjectNode sheet = sheet().put("permanentHp", 5);
        ObjectNode roll = riskRoll(sheet, 2, 12);
        DeathMoves.recover(sheet, roll, mapper.createObjectNode().put("undying", true), true);
        assertEquals(6, sheet.path("permanentHp").asInt());
        assertTrue(roll.path("deathResult").path("claimed").asBoolean());
        assertEquals("fear", roll.path("deathResult").path("outcome").asText());
        assertEquals(6, sheet.path("hp").path("on").asInt());
        ObjectNode critical = sheet().put("permanentHp", 2);
        ObjectNode criticalRoll = riskRoll(critical, 1, 1);
        DeathMoves.recover(critical, criticalRoll, mapper.createObjectNode(), true);
        assertEquals(2, critical.path("hp").path("on").asInt());
        assertEquals(0, critical.path("stress").path("on").asInt());
    }
}
