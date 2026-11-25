package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class LegacyDataMigrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path temp;
    Path catalogs, campaigns, work, campaign;

    @BeforeEach
    void fixture() throws Exception {
        catalogs = Files.createDirectories(temp.resolve("catalogs"));
        campaigns = Files.createDirectories(temp.resolve("campaigns"));
        work = temp.resolve("work");
        Files.writeString(catalogs.resolve("classes.json"), """
                {"format":"dmmap-daggerheart","version":1,"category":"classes","label":"Fixture","entries":[
                 {"id":"tester","name":"Tester","features":[
                  {"name":"Second Wind","automation":{"uses":{"max":1,"per":"rest"},"actions":[
                    {"id":"use","label":"Second Wind {now}","cost":{"hope":1},"steps":[{"op":"gain","resource":"hope","amount":1}]}]}}]}]}""");
        campaign = Files.createDirectories(campaigns.resolve("camp1"));
        Files.createDirectories(campaign.resolve("sheets"));
        Files.writeString(campaign.resolve("campaign.json"), "{\"id\":\"camp1\",\"actors\":[]}");
        Files.writeString(campaign.resolve("sheets").resolve("hero.json"), HERO);
        Files.writeString(campaign.resolve("history.jsonl"), """
                {"id":1,"ts":10,"type":"chat","text":"hello","whisper":["gm"]}
                {"id":2,"ts":11,"type":"chat","text":"Second Wind","automationClaim":{"group":"g","claims":[{"status":"pending"}]},"automationRecoveryRoll":{"x":1}}
                """);
    }

    private static final String HERO = """
            {"name":"Hero","earnedChoices":{"level2":"agility"},
             "features":[{"name":"Second Wind","catalog":{"category":"classes","id":"tester","feature":0}}],
             "usage":{"feat:Second Wind":{"used":1,"per":"rest"}},
             "inventory":[{"name":"Lucky Coin","custom":true,"qty":2}],
             "activeWeapons":[{"name":"Homebrew Axe","damage":"d10"}],
             "effects":[{"id":"pact","name":"Pact","lockedHp":2,"sourceActorId":"witch","until":"longRest"},
                        {"id":"dice","name":"Prayer Die","dicePool":"prayer","counter":{"value":4,"max":6},"until":"session"}]}""";

    private JsonNode sheet() throws Exception { return JSON.readTree(campaign.resolve("sheets").resolve("hero.json").toFile()); }

    private List<String> snapshot() throws Exception {
        try (Stream<Path> walk = Files.walk(temp)) {
            return walk.filter(p -> Files.isRegularFile(p) && !temp.relativize(p).startsWith("work") && !temp.relativize(p).startsWith("work2")).sorted().map(p -> {
                try { return temp.relativize(p) + "=" + Files.readString(p); } catch (Exception e) { throw new IllegalStateException(e); }
            }).toList();
        }
    }

    @Test
    void convertsARepresentativeCampaignEndToEndAndPreservesState() throws Exception {
        var outcome = new LegacyDataMigration(catalogs, campaigns, work).run();
        assertEquals(LegacyDataMigration.Status.COMPLETE, outcome.status(), outcome.report().toString());
        assertTrue(Files.readString(catalogs.resolve("classes.json")).contains("\"version\" : 2"));
        assertTrue(Files.readString(catalogs.resolve("classes.json")).contains("_catalogFeatureId"));
        assertTrue(Files.isRegularFile(catalogs.resolve("rules").resolve("ruleset.json")));
        JsonNode sheet = sheet();
        String instance = sheet.at("/features/0/instanceId").asText();
        assertEquals("classes:tester:features:second-wind", sheet.at("/features/0/automationSource").asText());
        assertFalse(sheet.at("/features/0/catalog/featureId").asText().isBlank());
        assertEquals(1, sheet.at("/automationState/sources/" + instance + "/usage/uses").asInt());
        assertEquals("agility", sheet.at("/earnedChoices/level2").asText());
        assertEquals("Lucky Coin", sheet.at("/inventory/0/name").asText());
        assertEquals(2, sheet.at("/inventory/0/qty").asInt());
        assertEquals("Homebrew Axe", sheet.at("/activeWeapons/0/name").asText());
        assertEquals(4, sheet.at("/effects/0/counter/value").asInt());
        assertEquals(2, sheet.at("/automationState/effects/0/locks/hp").asInt());
        assertEquals("[\"rest.long\"]", sheet.at("/automationState/effects/0/until").toString());
        List<String> chat = Files.readAllLines(campaign.resolve("history.jsonl"));
        assertEquals(2, chat.size());
        assertTrue(chat.get(0).contains("\"whisper\""));
        assertFalse(chat.get(1).contains("automationClaim"));
        assertFalse(chat.get(1).contains("automationRecoveryRoll"));
        assertTrue(chat.get(1).contains("legacyClaim") && chat.get(1).contains("legacyRecoveryRoll") && chat.get(1).contains("pending"));
    }

    @Test
    void rerunningIsIdempotentBeforeAndAfterTheJournalIsRemoved() throws Exception {
        new LegacyDataMigration(catalogs, campaigns, work).run();
        List<String> converted = snapshot();
        assertEquals(LegacyDataMigration.Status.ALREADY_COMPLETE, new LegacyDataMigration(catalogs, campaigns, work).run().status());
        assertEquals(converted, snapshot());
        var again = new LegacyDataMigration(catalogs, campaigns, temp.resolve("work2")).run();
        assertEquals(LegacyDataMigration.Status.COMPLETE, again.status(), again.report().toString());
        assertEquals(0, again.report().path("changed").size());
        assertEquals(converted, snapshot());
    }

    @Test
    void interruptedCutoverResumesWithoutRepeatingOrCorruptingFiles() throws Exception {
        List<String> original = snapshot();
        assertThrows(IllegalStateException.class, () -> new LegacyDataMigration(catalogs, campaigns, work, done -> {
            if (done == 2) throw new IllegalStateException("crash");
        }).run());
        assertEquals("CUTTING_OVER", JSON.readTree(work.resolve("conversion-journal.json").toFile()).path("phase").asText());
        assertNotEquals(original, snapshot());
        assertEquals(LegacyDataMigration.Status.COMPLETE, new LegacyDataMigration(catalogs, campaigns, work).run().status());
        assertEquals("classes:tester:features:second-wind", sheet().at("/features/0/automationSource").asText());
        assertTrue(Files.readString(campaign.resolve("history.jsonl")).contains("legacyClaim"));
        assertEquals(2, Files.readAllLines(campaign.resolve("history.jsonl")).size());
    }

    @Test
    void rollbackRestoresEveryOriginalAndRemovesAddedFiles() throws Exception {
        List<String> original = snapshot();
        new LegacyDataMigration(catalogs, campaigns, work).run();
        assertNotEquals(original, snapshot());
        assertEquals(LegacyDataMigration.Status.ROLLED_BACK, new LegacyDataMigration(catalogs, campaigns, work).rollback().status());
        assertEquals(original, snapshot());
        var rerun = new LegacyDataMigration(catalogs, campaigns, work).run();
        assertEquals(LegacyDataMigration.Status.COMPLETE, rerun.status(), rerun.report().toString());
    }

    @Test
    void rollbackAfterAnInterruptedCutoverRestoresEverything() throws Exception {
        List<String> original = snapshot();
        assertThrows(IllegalStateException.class, () -> new LegacyDataMigration(catalogs, campaigns, work, done -> {
            if (done == 1) throw new IllegalStateException("crash");
        }).run());
        new LegacyDataMigration(catalogs, campaigns, work).rollback();
        assertEquals(original, snapshot());
    }

    @Test
    void unresolvedAndAmbiguousReferencesBlockActivationAndLeaveOriginalsUntouched() throws Exception {
        Files.writeString(campaign.resolve("sheets").resolve("broken.json"), """
                {"features":[{"name":"Ghost","catalog":{"category":"classes","id":"missing"}},
                             {"name":"Wrong","catalog":{"category":"classes","id":"tester"}}]}""");
        List<String> original = snapshot();
        var outcome = new LegacyDataMigration(catalogs, campaigns, work).run();
        assertEquals(LegacyDataMigration.Status.BLOCKED, outcome.status());
        long errors = 0;
        for (JsonNode d : outcome.report().path("diagnostics")) if ("error".equals(d.path("severity").asText())) errors++;
        assertEquals(2, errors, outcome.report().toString());
        assertTrue(outcome.report().toString().contains("broken.json"));
        assertEquals(original, snapshot());
        assertThrows(IllegalStateException.class, () -> new LegacyDataMigration(catalogs, campaigns, work).rollback());

        Files.delete(campaign.resolve("sheets").resolve("broken.json"));
        assertEquals(LegacyDataMigration.Status.COMPLETE, new LegacyDataMigration(catalogs, campaigns, work).run().status());
    }

    @Test
    void anUnfinishedSaveBlocksTheConversion() throws Exception {
        Files.writeString(campaign.resolve("sheet-transfer.json"), "{}");
        assertEquals(LegacyDataMigration.Status.BLOCKED, new LegacyDataMigration(catalogs, campaigns, work).run().status());
    }

    @Test
    void theWorkDirectoryMustBeSeparateFromLiveData() {
        assertThrows(IllegalArgumentException.class, () -> new LegacyDataMigration(catalogs, campaigns, catalogs.resolve("work")));
    }
}
