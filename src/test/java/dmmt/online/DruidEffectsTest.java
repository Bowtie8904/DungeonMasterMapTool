package dmmt.online;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class DruidEffectsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void earthOptionsReachAllCharactersRemainOptionalAndEndWithSourceAura() throws Exception {
        ObjectNode druid = (ObjectNode) MAPPER.readTree("""
                {"effects":[{"name":"Channeling: Earth"},{"name":"Elemental Aura: Earth","requiresEffect":"Channeling: Earth"}]}
                """);
        ObjectNode ally = MAPPER.createObjectNode();
        ObjectNode offline = MAPPER.createObjectNode();
        Map<String, ObjectNode> sheets = new LinkedHashMap<>();
        sheets.put("druid", druid); sheets.put("ally", ally); sheets.put("offline", offline);
        DruidEffects.synchronizeEarth(sheets);
        for (ObjectNode sheet : sheets.values()) {
            var grant = java.util.stream.StreamSupport.stream(sheet.path("effects").spliterator(), false)
                    .filter(e -> e.path("earthAuraGrant").asBoolean()).findFirst().orElseThrow();
            assertEquals(1, grant.path("rollOptions").get(0).path("value").asInt());
            assertFalse(grant.path("rollOptions").get(0).path("defaultOn").asBoolean());
            assertFalse(grant.has("modifiers"));
        }
        DruidEffects.synchronizeEarth(sheets);
        assertEquals(1, ally.path("effects").size());
        druid.withArray("effects").remove(0);
        DruidEffects.synchronizeEarth(sheets);
        assertEquals(0, druid.path("effects").size());
        assertEquals(0, ally.path("effects").size());
        assertEquals(0, offline.path("effects").size());
    }

    @Test
    void onlyTheEndingCastersGrantIsRemoved() throws Exception {
        ObjectNode first = (ObjectNode) MAPPER.readTree("""
                {"effects":[{"name":"Channeling: Earth"},{"name":"Elemental Aura: Earth","requiresEffect":"Channeling: Earth"}]}
                """);
        ObjectNode second = first.deepCopy();
        Map<String, ObjectNode> sheets = new LinkedHashMap<>(Map.of("first", first, "second", second));
        DruidEffects.synchronizeEarth(sheets);
        first.withArray("effects").removeAll();
        DruidEffects.synchronizeEarth(sheets);
        assertEquals(1, first.path("effects").size());
        assertEquals("second", first.path("effects").get(0).path("sourceActorId").asText());
    }

    @Test
    void lastHpRemovesFormAndDependentEffectsTransitively() throws Exception {
        ObjectNode sheet = (ObjectNode) MAPPER.readTree("""
                {"hp":{"on":6,"slots":6},"effects":[
                 {"name":"Beastform: Armored Sentry","beastform":true},
                 {"name":"Shell","requiresEffect":"Beastform: Armored Sentry"},
                 {"name":"Child","requiresEffect":"Shell"}]}
                """);
        DruidEffects.normalize(sheet);
        assertEquals(0, sheet.path("effects").size());
    }

    @Test
    void raptorRerollsEachLowDieOnceAndCountsRerollsAgainstHostLimit() {
        var result = new DiceRoller.Result("3d10+11", List.of(1, 3, 8), 23);
        Random low = new Random() { @Override public int nextInt(int bound) { return 0; } };
        var rerolled = DiceRoller.rerollBelow(result, 3, 4, low);
        assertEquals(List.of(1, 3, 8), rerolled.rolls());
        assertEquals(23, rerolled.total());
        Random high = new Random() { @Override public int nextInt(int bound) { return bound - 1; } };
        assertEquals(List.of(10, 3, 8), DiceRoller.rerollBelow(result, 3, 4, high).rolls());
        assertThrows(IllegalArgumentException.class, () -> DiceRoller.rerollBelow(result, 4, 4, high));
        assertThrows(IllegalArgumentException.class, () -> DiceRoller.rerollBelow(result, 0, 20, high));
    }
}
