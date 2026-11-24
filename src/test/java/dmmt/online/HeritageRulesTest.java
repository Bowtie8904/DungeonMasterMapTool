package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dmmt.online.automation.LegacyCatalogConverter;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Parity with the legacy replaceAncestry / ancestryFeatureChoices (rules.ts, transformations.test.ts). */
class HeritageRulesTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static String legacy() throws Exception {
        return Files.readString(Path.of("dmmap-daggerheart", "ancestries.json"));
    }

    private static ObjectNode sheet() throws Exception {
        return (ObjectNode) JSON.readTree("""
                {"evasion":10,"hp":{"slots":6,"total":10,"on":6},"stress":{"slots":6,"total":10,"on":0},
                 "heritage":[{"source":"Class - X","name":"Keep"}]}""");
    }

    private static List<String> names(ObjectNode sheet) {
        return sheet.get("heritage").findValuesAsText("name");
    }

    @Test
    void replacingAncestryKeepsOtherRowsAdjustsAllowancesAndRecordsThePick() throws Exception {
        JsonNode ancestries = JSON.readTree(legacy());
        ObjectNode sheet = sheet();
        assertEquals(List.of(), HeritageRules.replace(sheet, ancestries, "Giant", "Endurance"));
        assertEquals(List.of("Keep", "Endurance"), names(sheet));
        assertEquals(7, sheet.at("/hp/slots").asInt());
        assertEquals("Giant", sheet.path("ancestry").asText());
        assertEquals("Endurance", sheet.path("ancestryFeature").asText());

        assertEquals(List.of("Endurance"), HeritageRules.replace(sheet, ancestries, "Human", "High Stamina"));
        assertEquals(List.of("Keep", "High Stamina"), names(sheet));
        assertEquals(6, sheet.at("/hp/slots").asInt());
        assertEquals(6, sheet.at("/hp/on").asInt(), "on never exceeds the slots");
        assertEquals(7, sheet.at("/stress/slots").asInt());
    }

    @Test
    void sequentialPicksSeeEarlierOnesAndAnInvalidPickLeavesTheSheetAlone() throws Exception {
        JsonNode ancestries = JSON.readTree(legacy());
        ObjectNode sheet = sheet();
        HeritageRules.replace(sheet, ancestries, "Elf", "Quick Reactions");
        HeritageRules.replace(sheet, ancestries, "Elf", "Celestial Trance");
        assertEquals("Celestial Trance", sheet.path("ancestryFeature").asText());
        assertEquals(List.of("Keep", "Celestial Trance"), names(sheet));
        ObjectNode before = sheet.deepCopy();
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> HeritageRules.replace(sheet, ancestries, "Elf", "Reach"));
        assertEquals("Choose a valid ancestry and feature.", error.getMessage());
        assertEquals(before, sheet);
    }

    @Test
    void mixedAncestryOffersTheFirstFeatureOfTheFirstAndTheSecondOfTheSecond() throws Exception {
        JsonNode ancestries = JSON.readTree(legacy());
        List<ObjectNode> choices = HeritageRules.choices(ancestries, "Giant / Human");
        assertEquals(2, choices.size());
        assertEquals(List.of("Endurance", "Adaptability"), choices.stream().map(c -> c.path("name").asText()).toList());
        assertEquals("Ancestry - Human (second)", choices.get(1).path("source").asText());
    }

    @Test
    void convertedAncestryAllowancesBalanceLikeTheLegacyOnes() throws Exception {
        JsonNode converted = LegacyCatalogConverter.convert(legacy()).catalog();
        ObjectNode sheet = sheet();
        HeritageRules.replace(sheet, converted, "Giant", "Endurance");
        assertEquals(7, sheet.at("/hp/slots").asInt());
        assertTrue(sheet.at("/heritage/1/automationSource").asText().startsWith("ancestries:"));
        HeritageRules.replace(sheet, converted, "Giant", "Reach");
        assertEquals(6, sheet.at("/hp/slots").asInt());
    }
}
