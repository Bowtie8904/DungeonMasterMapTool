package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Parity with the legacy buildBeastform (druid.ts, druid.test.ts). */
class BeastformRulesTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode classes() throws Exception {
        return JSON.readTree(Files.readString(Path.of("dmmap-daggerheart", "classes.json")));
    }

    private static ObjectNode choice(String json) throws Exception {
        return (ObjectNode) JSON.readTree(json);
    }

    @Test
    void catalogHasAll24Categories() throws Exception {
        assertEquals(24, BeastformRules.catalog(classes()).size());
    }

    @Test
    void aboveTierChoicesAreRejected() throws Exception {
        List<JsonNode> catalog = BeastformRules.catalog(classes());
        var error = assertThrows(IllegalArgumentException.class,
                () -> BeastformRules.build(1, catalog, choice("{\"form\":\"Powerful Beast\"}"), false, false));
        assertTrue(error.getMessage().contains("tier"));
        assertEquals("Powerful Beast", BeastformRules.build(1, catalog, choice("{\"form\":\"Powerful Beast\"}"), false, true).path("weapon").path("name").asText().replace("Beastform: ", ""));
    }

    @Test
    void powerfulBeastSuppliesAttackStatsAndTheBeastformGroup() throws Exception {
        ObjectNode effect = BeastformRules.build(5, BeastformRules.catalog(classes()), choice("{\"form\":\"Powerful Beast\"}"), false, false);
        assertEquals("beastform", effect.path("group").asText());
        assertEquals("d10+4", effect.at("/weapon/damage").asText());
        assertEquals("Strength", effect.at("/weapon/trait").asText());
    }

    @Test
    void evolutionNeedsATraitAndAddsOne() throws Exception {
        List<JsonNode> catalog = BeastformRules.catalog(classes());
        assertThrows(IllegalArgumentException.class, () -> BeastformRules.build(5, catalog, choice("{\"form\":\"Agile Scout\"}"), true, false));
        ObjectNode effect = BeastformRules.build(5, catalog, choice("{\"form\":\"Agile Scout\",\"trait\":\"Knowledge\"}"), true, false);
        assertTrue(effect.get("modifiers").toString().contains("{\"stat\":\"trait.Knowledge\",\"value\":1}"));
    }

    @Test
    void evolvedFormsUseTheBaseAndRaiseTheDamage() throws Exception {
        List<JsonNode> catalog = BeastformRules.catalog(classes());
        ObjectNode legendary = BeastformRules.build(8, catalog, choice("{\"form\":\"Legendary Beast\",\"base\":\"Pack Predator\"}"), false, false);
        assertEquals("d8+2", legendary.at("/weapon/damage").asText());
        assertTrue(legendary.get("modifiers").toString().contains("{\"roll\":\"damage\",\"value\":6}"));
        ObjectNode mythic = BeastformRules.build(8, catalog, choice("{\"form\":\"Mythic Beast\",\"base\":\"Powerful Beast\"}"), false, false);
        assertEquals("d12+4", mythic.at("/weapon/damage").asText());
        assertThrows(IllegalArgumentException.class,
                () -> BeastformRules.build(8, catalog, choice("{\"form\":\"Legendary Beast\",\"base\":\"Powerful Beast\"}"), false, false));
    }

    @Test
    void hybridPicksArriveAsSlugsAndAreValidatedAgainstTheSelectedCategories() throws Exception {
        List<JsonNode> catalog = BeastformRules.catalog(classes());
        String names = "{\"form\":\"Legendary Hybrid\",\"categories\":[\"Agile Scout\",\"Pack Predator\"],"
                + "\"advantages\":[\"deceive\",\"locate\",\"sneak\",\"attack\"],\"features\":[\"Agile\",\"Pack Hunting\"]}";
        String slugs = "{\"form\":\"Legendary Hybrid\",\"categories\":[\"agile-scout\",\"pack-predator\"],"
                + "\"advantages\":[\"deceive\",\"locate\",\"sneak\",\"attack\"],\"features\":[\"agile\",\"pack-hunting\"]}";
        ObjectNode fromNames = BeastformRules.build(8, catalog, choice(names), false, false);
        ObjectNode fromSlugs = BeastformRules.build(8, catalog, choice(slugs), false, false);
        assertEquals("d10+8", fromNames.at("/weapon/damage").asText());
        assertEquals(fromNames, fromSlugs);
        assertTrue(fromSlugs.get("features").toString().contains("Pack Hunting"));
        assertThrows(IllegalArgumentException.class, () -> BeastformRules.build(8, catalog,
                choice(slugs.replace("\"agile\",\"pack-hunting\"", "\"agile\",\"agile\"")), false, false));
        assertThrows(IllegalArgumentException.class, () -> BeastformRules.build(8, catalog,
                choice(slugs.replace("\"agile-scout\",\"pack-predator\"", "\"agile-scout\",\"agile-scout\"")), false, false));
    }

    @Test
    void fragileFormsEndOnMajorDamage() throws Exception {
        ObjectNode effect = BeastformRules.build(5, BeastformRules.catalog(classes()), choice("{\"form\":\"Agile Scout\"}"), false, false);
        assertEquals(2, effect.path("endOnDamage").asInt());
        assertFalse(BeastformRules.build(5, BeastformRules.catalog(classes()), choice("{\"form\":\"Powerful Beast\"}"), false, false).has("endOnDamage"));
    }

    @Test
    void assumeReplacesTheOldFormAndRecordsTheDuration() throws Exception {
        ObjectNode sheet = choice("{\"level\":5,\"effects\":[]}");
        BeastformRules.assume(sheet, classes(), choice("{\"form\":\"Agile Scout\"}"), false, false, "");
        BeastformRules.assume(sheet, classes(), choice("{\"form\":\"Powerful Beast\"}"), false, true, "scene");
        assertEquals(1, sheet.get("effects").size());
        assertEquals("scene", sheet.at("/effects/0/until").asText());
        assertEquals("Beastform", sheet.at("/effects/0/source").asText());
    }
}
