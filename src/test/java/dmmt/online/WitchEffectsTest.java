package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WitchEffectsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void lifebloodTransfersItsHopeMitigationAndExpiresOnlyOnCasterHealingOrRecast() {
        Map<String, ObjectNode> sheets = new LinkedHashMap<>();
        ObjectNode caster = source("lifeblood", 0);
        caster.putObject("hp").put("on", 2);
        ObjectNode holder = MAPPER.createObjectNode();
        sheets.put("caster", caster);
        sheets.put("holder", holder);
        WitchEffects.synchronize(sheets, copy(sheets));
        assertEquals("Lifeblood Talisman", caster.path("inventory").get(0).path("name").asText());
        Map<String, ObjectNode> previous = copy(sheets);
        ChatCards.transfer(caster, holder, "item", caster.path("inventory").get(0).deepCopy());
        WitchEffects.synchronize(sheets, previous);
        assertTrue(linked(caster, "lifeblood").isMissingNode());
        JsonNode buff = linked(holder, "lifeblood");
        assertEquals("manual", buff.path("until").asText());
        assertEquals(2, buff.path("mitigation").get(0).path("when").path("minMarkedHp").asInt());
        assertEquals(1, buff.path("mitigation").get(0).path("cost").path("hope").asInt());
        assertFalse(buff.has("tokenResource"));
        previous = copy(sheets);
        caster.put("shortRestsInRow", 1);
        caster.withObject("/hp").put("on", 1);
        WitchEffects.synchronize(sheets, previous);
        assertEquals(1, holder.path("inventory").size());
        assertFalse(linked(holder, "lifeblood").isMissingNode());
        previous = copy(sheets);
        ((ObjectNode) caster.path("effects").get(0)).put("id", "replacement");
        ((ObjectNode) caster.path("effects").get(0)).remove("witchTalismanCreated");
        WitchEffects.synchronize(sheets, previous);
        assertTrue(holder.path("inventory").isEmpty());
        assertTrue(linked(holder, "lifeblood").isMissingNode());
        assertEquals(1, caster.path("inventory").size());
        previous = copy(sheets);
        caster.withObject("/hp").put("on", 0);
        WitchEffects.synchronize(sheets, previous);
        assertTrue(caster.path("inventory").isEmpty());
        assertTrue(caster.path("effects").isEmpty());
    }

    private ObjectNode source(String kind, int tokens) {
        ObjectNode sheet = MAPPER.createObjectNode();
        sheet.putArray("effects").addObject().put("id", "activation").put("witchGrant", kind)
                .put("name", kind + " source").put("until", "moonbeam".equals(kind) ? "scene" : "rest");
        sheet.putObject("resources").put("circle".equals(kind) ? "circle-of-power" : "enchanted-talisman", tokens);
        return sheet;
    }

    private Map<String, ObjectNode> copy(Map<String, ObjectNode> sheets) {
        Map<String, ObjectNode> result = new LinkedHashMap<>();
        sheets.forEach((id, sheet) -> result.put(id, sheet.deepCopy()));
        return result;
    }

    private JsonNode linked(ObjectNode sheet, String kind) {
        for (JsonNode effect : sheet.path("effects")) if (kind.equals(effect.path("witchLinked").asText())) return effect;
        return MAPPER.missingNode();
    }

    @Test
    void circleAndMoonbeamIncludeSourceAndOfflineSheetsWithSeparateCanonicalBonuses() {
        Map<String, ObjectNode> sheets = new LinkedHashMap<>();
        sheets.put("witch", source("circle", 3));
        sheets.put("moon", source("moonbeam", 0));
        sheets.put("offline", MAPPER.createObjectNode());
        WitchEffects.synchronize(sheets, copy(sheets));
        for (ObjectNode sheet : sheets.values()) {
            JsonNode circle = linked(sheet, "circle");
            assertEquals("Circle of Power benefits", circle.path("name").asText());
            assertEquals(2, circle.path("modifiers").size());
            assertEquals("thresholds", circle.path("modifiers").get(0).path("stat").asText());
            assertEquals("evasion", circle.path("modifiers").get(1).path("stat").asText());
            assertEquals(2, circle.path("rollOptions").get(0).path("value").asInt());
            assertTrue(circle.path("rollOptions").get(0).path("defaultOn").asBoolean());
            assertEquals("feat:Circle of Power", circle.path("sourceKey").asText());
            JsonNode moon = linked(sheet, "moonbeam");
            assertEquals("scene", moon.path("until").asText());
            assertEquals("spellcast", moon.path("rollOptions").get(0).path("roll").asText());
            assertEquals(1, moon.path("rollOptions").get(0).path("value").asInt());
            assertTrue(moon.path("text").asText().contains("illusions"));
        }
        assertEquals(3, sheets.get("witch").path("effects").size());
    }

    @Test
    void manualDismissalSurvivesReadsAndSourceSavesButNewActivationRegrants() {
        Map<String, ObjectNode> sheets = new LinkedHashMap<>();
        sheets.put("witch", source("circle", 2));
        sheets.put("ally", MAPPER.createObjectNode());
        WitchEffects.synchronize(sheets, copy(sheets));
        Map<String, ObjectNode> previous = copy(sheets);
        sheets.get("ally").withArray("effects").removeAll();
        ((ObjectNode) sheets.get("witch").path("effects").get(0)).remove("witchRecipients");
        WitchEffects.synchronize(sheets, previous);
        assertTrue(sheets.get("ally").path("effects").isEmpty());
        WitchEffects.synchronize(sheets, copy(sheets));
        assertTrue(sheets.get("ally").path("effects").isEmpty());
        sheets.get("witch").withArray("effects").removeAll();
        sheets.get("witch").withArray("effects").addObject().put("id", "new").put("witchGrant", "circle");
        WitchEffects.synchronize(sheets, copy(sheets));
        assertFalse(linked(sheets.get("ally"), "circle").isMissingNode());
    }

    @Test
    void circlesExpireOnPoolDepletionAndMoonbeamOnSourceRemovalWithoutRemovingOtherSources() {
        Map<String, ObjectNode> sheets = new LinkedHashMap<>();
        sheets.put("first", source("circle", 2));
        sheets.put("second", source("circle", 2));
        sheets.put("moon", source("moonbeam", 0));
        WitchEffects.synchronize(sheets, copy(sheets));
        sheets.get("first").withObject("/resources").put("circle-of-power", 0);
        sheets.get("moon").withArray("effects").remove(0);
        WitchEffects.synchronize(sheets, copy(sheets));
        for (ObjectNode sheet : sheets.values()) {
            assertEquals("second", linked(sheet, "circle").path("sourceActorId").asText());
            assertTrue(linked(sheet, "moonbeam").isMissingNode());
        }
        assertEquals(1, sheets.get("first").path("effects").size());
    }

    @Test
    void talismanIsUniqueTransfersWithMarkerAndDebitsCreatorUntilDepletion() {
        Map<String, ObjectNode> sheets = new LinkedHashMap<>();
        ObjectNode witch = source("talisman", 3);
        ObjectNode ally = MAPPER.createObjectNode();
        sheets.put("witch", witch);
        sheets.put("ally", ally);
        WitchEffects.synchronize(sheets, copy(sheets));
        JsonNode item = witch.path("inventory").get(0).deepCopy();
        assertTrue(item.path("note").asText().contains("protective essence"));
        assertEquals("witch", item.path("witchTalisman").path("sourceActorId").asText());
        assertEquals("activation", item.path("witchTalisman").path("activationId").asText());
        String mirror = "talisman-tokens:witch:activation";
        assertEquals(3, witch.path("resources").path(mirror).asInt());
        WitchEffects.synchronize(sheets, copy(sheets));
        assertEquals(1, witch.path("inventory").size());
        Map<String, ObjectNode> previous = copy(sheets);
        ChatCards.transfer(witch, ally, "item", item);
        WitchEffects.synchronize(sheets, previous);
        assertTrue(linked(witch, "talisman").isMissingNode());
        JsonNode buff = linked(ally, "talisman");
        assertEquals(-1, buff.path("mitigation").get(0).path("hp").asInt());
        assertEquals(1, buff.path("mitigation").get(0).path("cost").path(mirror).asInt());
        assertEquals(mirror, buff.path("resources").get(0).path("id").asText());
        assertEquals(mirror, buff.path("tokenResource").asText());
        assertTrue(buff.path("mitigation").get(0).path("when").path("markingHp").asBoolean());
        assertTrue(buff.path("mitigation").get(0).path("markedHpOnly").asBoolean());
        assertTrue(buff.path("text").asText().contains("creator witch"));
        previous = copy(sheets);
        ally.withObject("/resources").put(mirror, 1);
        WitchEffects.synchronize(sheets, previous);
        assertEquals(1, witch.path("resources").path("enchanted-talisman").asInt());
        WitchEffects.synchronize(sheets, copy(sheets));
        assertEquals(1, witch.path("resources").path("enchanted-talisman").asInt());
        previous = copy(sheets);
        ally.withObject("/resources").put(mirror, 0);
        WitchEffects.synchronize(sheets, previous);
        assertEquals(0, witch.path("resources").path("enchanted-talisman").asInt());
        assertTrue(ally.path("inventory").isEmpty());
        assertTrue(linked(ally, "talisman").isMissingNode());
        assertTrue(witch.path("effects").isEmpty());
    }

    @Test
    void restRemovesTransferredTalismanAndDoesNotRecreateIt() {
        for (boolean longRest : new boolean[]{false, true}) {
            Map<String, ObjectNode> sheets = new LinkedHashMap<>();
            ObjectNode witch = source("talisman", 4);
            ObjectNode holder = MAPPER.createObjectNode();
            sheets.put("witch", witch);
            sheets.put("holder", holder);
            WitchEffects.synchronize(sheets, copy(sheets));
            Map<String, ObjectNode> previous = copy(sheets);
            ChatCards.transfer(witch, holder, "item", witch.path("inventory").get(0).deepCopy());
            WitchEffects.synchronize(sheets, previous);
            previous = copy(sheets);
            witch.put(longRest ? "longRestRevision" : "shortRestsInRow", 1);
            WitchEffects.synchronize(sheets, previous);
            assertTrue(holder.path("inventory").isEmpty());
            assertTrue(linked(holder, "talisman").isMissingNode());
            assertEquals(0, holder.path("resources").path("talisman-tokens:witch:activation").asInt());
        }
    }

    @Test
    void specialInventoryCannotBeCopiedEditedOrInventedThroughSheetSaves() {
        Map<String, ObjectNode> sheets = new LinkedHashMap<>();
        sheets.put("witch", source("talisman", 2));
        WitchEffects.synchronize(sheets, copy(sheets));
        ObjectNode previous = sheets.get("witch").deepCopy();
        ObjectNode changed = previous.deepCopy();
        changed.withArray("inventory").add(changed.path("inventory").get(0).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> WitchEffects.validateSave(changed, previous));
        assertThrows(IllegalArgumentException.class, () -> WitchEffects.validateSave(previous, MAPPER.createObjectNode()));
        ObjectNode forged = source("talisman", 3);
        assertThrows(IllegalArgumentException.class, () -> WitchEffects.validateSave(forged, MAPPER.createObjectNode()));
        ObjectNode replenished = previous.deepCopy();
        replenished.withObject("/resources").put("enchanted-talisman", 8);
        assertThrows(IllegalArgumentException.class, () -> WitchEffects.validateSave(replenished, previous));
    }

    @Test
    void multipleTalismansHaveDistinctMirrorsAndDebitOnlyTheirOwnCreatorWithoutReplenishmentOrOverdraft() {
        Map<String, ObjectNode> sheets = new LinkedHashMap<>();
        sheets.put("first", source("talisman", 2));
        sheets.put("second", source("talisman", 3));
        sheets.put("holder", MAPPER.createObjectNode());
        WitchEffects.synchronize(sheets, copy(sheets));
        Map<String, ObjectNode> previous = copy(sheets);
        ObjectNode holder = sheets.get("holder");
        for (String id : new String[]{"first", "second"})
            ChatCards.transfer(sheets.get(id), holder, "item", sheets.get(id).path("inventory").get(0).deepCopy());
        WitchEffects.synchronize(sheets, previous);
        String firstMirror = "talisman-tokens:first:activation";
        String secondMirror = "talisman-tokens:second:activation";
        assertEquals(2, holder.path("resources").path(firstMirror).asInt());
        assertEquals(3, holder.path("resources").path(secondMirror).asInt());
        long options = java.util.stream.StreamSupport.stream(holder.path("effects").spliterator(), false)
                .filter(e -> e.has("mitigation")).count();
        assertEquals(2, options);
        previous = copy(sheets);
        holder.withObject("/resources").put(secondMirror, 1);
        WitchEffects.synchronize(sheets, previous);
        assertEquals(2, sheets.get("first").path("resources").path("enchanted-talisman").asInt());
        assertEquals(1, sheets.get("second").path("resources").path("enchanted-talisman").asInt());
        previous = copy(sheets);
        holder.withObject("/resources").put(firstMirror, 0);
        WitchEffects.synchronize(sheets, previous);
        assertEquals(0, sheets.get("first").path("resources").path("enchanted-talisman").asInt());
        assertEquals(1, sheets.get("second").path("resources").path("enchanted-talisman").asInt());
        assertEquals(1, holder.path("inventory").size());
        assertEquals(secondMirror, linked(holder, "talisman").path("tokenResource").asText());
        previous = copy(sheets);
        holder.withObject("/resources").put(secondMirror, 2);
        Map<String, ObjectNode> finalPrevious = previous;
        assertThrows(IllegalArgumentException.class, () -> WitchEffects.synchronize(sheets, finalPrevious));
        holder.withObject("/resources").put(secondMirror, -1);
        assertThrows(IllegalArgumentException.class, () -> WitchEffects.synchronize(sheets, finalPrevious));
    }

    @Test
    void depletedTokenEffectsAndBoundPoolsEndImmediatelyAndGlamourEndsOnlyWhenHpMarked() throws Exception {
        ObjectNode sheet = (ObjectNode) MAPPER.readTree("""
                {"hp":{"on":1},"resources":{"walk-between-worlds":0,"other":3},"features":[{"automation":{
                 "resources":[{"id":"walk-between-worlds","whileEffect":"Walk Between Worlds"},
                              {"id":"other","whileEffect":"Missing"}]}}],
                 "effects":[{"name":"Walk Between Worlds","tokenResource":"walk-between-worlds"},
                            {"name":"Night's Glamour","endOnMarkedHp":true}]}
                """);
        ObjectNode previous = sheet.deepCopy();
        WitchEffects.normalize(sheet, previous);
        assertEquals(1, sheet.path("effects").size());
        assertEquals("Night's Glamour", sheet.path("effects").get(0).path("name").asText());
        assertEquals(0, sheet.path("resources").path("other").asInt());
        sheet.withObject("/hp").put("on", 2);
        WitchEffects.normalize(sheet, previous);
        assertTrue(sheet.path("effects").isEmpty());
    }

    @Test
    void glamourMaintenanceReceiptMustAdvanceExactlyOnceOnTheSameEffect() throws Exception {
        ObjectNode previous = (ObjectNode) MAPPER.readTree("""
                {"hp":{"on":1},"stress":{"on":2},
                 "effects":[{"id":"glamour","name":"Night's Glamour","endOnMarkedHp":true,"hpMaintenance":2}]}
                """);
        for (JsonNode receipt : List.<JsonNode>of(MAPPER.valueToTree(3), MAPPER.valueToTree(2), MAPPER.valueToTree(4),
                MAPPER.valueToTree(-1), MAPPER.valueToTree(3.5))) {
            ObjectNode sheet = previous.deepCopy();
            sheet.withObject("/hp").put("on", 2);
            ((ObjectNode) sheet.path("effects").get(0)).set("hpMaintenance", receipt);
            WitchEffects.normalize(sheet, previous);
            assertEquals(receipt.isInt() && receipt.asInt() == 3 ? 1 : 0, sheet.path("effects").size());
        }
        ObjectNode sheet = previous.deepCopy();
        sheet.withObject("/hp").put("on", 2);
        ((ObjectNode) sheet.path("effects").get(0)).put("id", "different").put("hpMaintenance", 3);
        WitchEffects.normalize(sheet, previous);
        assertTrue(sheet.path("effects").isEmpty());
    }
}
