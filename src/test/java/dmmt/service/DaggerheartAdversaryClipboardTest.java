package dmmt.service;

import dmmt.model.DmProject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DaggerheartAdversaryClipboardTest {
    private final DaggerheartAdversaryClipboard clipboard = new DaggerheartAdversaryClipboard();

    @Test
    void parsesAdversaryDataAndIgnoresExportedEncounterState() {
        var imported = clipboard.parse("""
                {
                  "format":"daggerheart-companion/adversary",
                  "version":1,
                  "system":"daggerheart",
                  "adversary":{
                    "sourceId":"1","name":"Acid Burrower","tier":1,
                    "category":"Solo","categoryDisplayText":"Solo",
                    "description":"A horse-sized insect.","motivesAndTactics":"Burrow, reposition",
                    "difficulty":14,"damageThresholds":"8/15","maxHp":8,"maxStress":3,
                    "attackModifier":"+3","defaultAttackName":"Claws",
                    "defaultAttackRange":"Very Close","defaultAttackDamage":"1d12+2 phy",
                    "experiences":["Tremor Sense +2"],
                    "features":[{"name":"Spit Acid","classification":"Action",
                      "description":"Targets take 2d6 physical damage."}],
                    "tags":["monster"]
                  },
                  "token":{"name":"Acid Burrower","color":null,"imageUrl":"http://localhost:9980/image"},
                  "state":{"maxHp":8,"maxStress":3,"markedHp":7,"markedStress":2,"tokens":4,"dead":true}
                }
                """);

        assertEquals("Acid Burrower", imported.adversary().getName());
        assertEquals(1, imported.adversary().getTier());
        assertEquals(8, imported.adversary().getMaxHp());
        assertEquals("Tremor Sense +2", imported.adversary().getExperiences().getFirst());
        assertTrue(imported.adversary().getFeatures().getFirst().getDescription().contains("2d6 physical damage"));
        assertEquals("http://localhost:9980/image", imported.imageUrl());
        assertNull(imported.tokenColor());
    }

    @Test
    void rejectsUnsupportedFormatsAndInvalidResourceBounds() {
        assertThrows(IllegalArgumentException.class, () -> clipboard.parse("{}"));
        assertThrows(IllegalArgumentException.class, () -> clipboard.parse("""
                {"format":"daggerheart-companion/adversary","version":2,"system":"daggerheart"}
                """));
        assertThrows(IllegalArgumentException.class, () -> clipboard.parse("""
                {"format":"daggerheart-companion/adversary","version":1,"system":"daggerheart",
                 "adversary":{"name":"Too Large","tier":1,"difficulty":14,"maxHp":101,"maxStress":0}}
                """));
    }

    @Test
    void imageDownloadOnlyAcceptsLoopbackHttpUrls() {
        assertEquals("localhost", DaggerheartAdversaryClipboard.parseLocalImageUri(
                "http://localhost:9980/statblock-images/adversary/1").getHost());
        assertEquals("127.0.0.1", DaggerheartAdversaryClipboard.parseLocalImageUri(
                "https://127.0.0.1:9980/image.png").getHost());
        assertThrows(IllegalArgumentException.class, () ->
                DaggerheartAdversaryClipboard.parseLocalImageUri("http://example.com/image.png"));
        assertThrows(IllegalArgumentException.class, () ->
                DaggerheartAdversaryClipboard.parseLocalImageUri("http://localhost.evil/image.png"));
        assertThrows(IllegalArgumentException.class, () ->
                DaggerheartAdversaryClipboard.parseLocalImageUri("http://user@localhost/image.png"));
    }

    @Test
    void persistedTokenDefinitionRetainsAdversaryAndPerTokenState() throws Exception {
        var adversary = clipboard.parse("""
                {"format":"daggerheart-companion/adversary","version":1,"system":"daggerheart",
                 "adversary":{"name":"Test","tier":1,"difficulty":14,"maxHp":8,"maxStress":3}}
                """).adversary();
        DmProject project = DmProject.builder().build();
        DmProject.TokenDefinition definition = DmProject.TokenDefinition.builder()
                .id("adversary:test").name("Test").adversary(adversary).build();
        DmProject.TokenInstance token = DmProject.TokenInstance.builder()
                .id("token").definitionId(definition.getId()).adversaryState(
                        new dmmt.model.DaggerheartAdversary.CombatState(2, 1, 3, false)).build();
        project.getTokenDefinitions().put(definition.getId(), definition);
        project.getTokens().add(token);

        DmProject restored = JsonMappers.create().readValue(JsonMappers.create().writeValueAsBytes(project), DmProject.class);
        assertEquals("Test", restored.getTokenDefinitions().get("adversary:test").getAdversary().getName());
        assertEquals(2, restored.getTokens().getFirst().getAdversaryState().getMarkedHp());
        assertEquals(3, restored.getTokens().getFirst().getAdversaryState().getTokens());
    }
}
