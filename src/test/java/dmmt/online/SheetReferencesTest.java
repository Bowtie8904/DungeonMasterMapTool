package dmmt.online;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SheetReferencesTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    @TempDir Path temp;

    @Test
    void storesReferencesAndResolvesCurrentContentWithoutLosingOverridesOrState() throws Exception {
        ObjectNode classes = (ObjectNode) MAPPER.readTree("""
                {"category":"classes","entries":[{"id":"bard","name":"Bard","kind":"class","features":[
                {"name":"Gift","text":"Original feature","automation":{"marker":"old"}}]}]}
                """);
        ObjectNode cards = (ObjectNode) MAPPER.readTree("""
                {"category":"domain-cards","entries":[{"id":"book","name":"Book","text":"Original card",
                "spells":[{"name":"Spark","text":"Original spell"}],"automation":{"marker":"old"}}]}
                """);
        ObjectNode sheet = (ObjectNode) MAPPER.readTree("""
                {"className":"Bard","hp":{"on":2,"slots":6},"usage":{"feat:Gift":{"used":1}},
                 "features":[{"source":"Class feature - Bard","name":"Gift","text":"Original feature","automation":{"marker":"old"}},
                             {"source":"Custom","name":"Custom","text":"Keep custom"}],
                 "heritage":[],
                 "loadout":[{"name":"Book","text":"Original card","spells":[{"name":"Spark","text":"Original spell"}],
                              "automation":{"marker":"old"}}],"vault":[]}
                """);
        CatalogSheets.refresh(sheet, List.of(classes, cards));
        CampaignStore store = new CampaignStore(temp);
        Campaign campaign = store.create("References");
        store.saveSheet(campaign.getId(), "hero", sheet.toString());
        Path disk = temp.resolve(campaign.getId()).resolve("sheets").resolve("hero.json");
        String saved = Files.readString(disk);
        assertTrue(saved.contains("\n"));
        assertFalse(saved.contains("Original feature"));
        assertFalse(saved.contains("Original card"));
        assertFalse(saved.contains("Original spell"));
        assertFalse(saved.contains("\"marker\""));
        assertTrue(saved.contains("Keep custom"));

        ((ObjectNode) classes.path("entries").get(0).path("features").get(0)).put("text", "Updated feature")
                .withObject("/automation").put("marker", "new");
        ObjectNode card = (ObjectNode) cards.path("entries").get(0);
        card.put("text", "Updated card");
        ((ObjectNode) card.path("spells").get(0)).put("text", "Updated spell");
        card.withObject("/automation").put("marker", "new");
        ObjectNode restored = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "hero"));
        SheetReferences.resolve(restored, List.of(classes, cards));
        CatalogSheets.refresh(restored, List.of(classes, cards));
        assertEquals("Updated feature", restored.path("features").get(0).path("text").asText());
        assertEquals("new", restored.path("features").get(0).path("automation").path("marker").asText());
        assertEquals("Updated card", restored.path("loadout").get(0).path("text").asText());
        assertEquals("Updated spell", restored.path("loadout").get(0).path("spells").get(0).path("text").asText());
        assertEquals(2, restored.path("hp").path("on").asInt());
        assertEquals(1, restored.path("usage").path("feat:Gift").path("used").asInt());
        assertFalse(restored.toString().contains("_storedFields"));

        ((ObjectNode) restored.path("features").get(0)).put("text", "My override");
        store.saveSheets(campaign.getId(), Map.of("hero", restored));
        ObjectNode overridden = (ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "hero"));
        assertEquals("My override", overridden.path("features").get(0).path("text").asText());
        assertFalse(overridden.toString().contains("Updated feature"));
        SheetReferences.resolve(overridden, List.of(classes, cards));
        CatalogSheets.refresh(overridden, List.of(classes, cards));
        assertEquals("My override", overridden.path("features").get(0).path("text").asText());
        assertThrows(SheetReferences.MissingReference.class,
                () -> SheetReferences.resolve((ObjectNode) MAPPER.readTree(store.loadSheet(campaign.getId(), "hero")), List.of()));
    }

    @Test
    void nestedAutomationOverridesKeepTheirOriginalMergeBaseline() throws Exception {
        ObjectNode file = (ObjectNode) MAPPER.readTree("""
                {"category":"domain-cards","entries":[{"id":"one","name":"One","text":"Rules",
                 "automation":{"first":1,"second":2}}]}
                """);
        ObjectNode sheet = (ObjectNode) MAPPER.readTree("""
                {"loadout":[{"name":"One","text":"Rules","automation":{"first":1,"second":2}}]}
                """);
        CatalogSheets.refresh(sheet, List.of(file));
        ((ObjectNode) sheet.path("loadout").get(0).path("automation")).put("first", 99);
        SheetReferences.compact(sheet);
        ((ObjectNode) file.path("entries").get(0).path("automation")).put("first", 3).put("second", 4);
        SheetReferences.resolve(sheet, List.of(file));
        CatalogSheets.refresh(sheet, List.of(file));
        assertEquals(99, sheet.path("loadout").get(0).path("automation").path("first").asInt());
        assertEquals(4, sheet.path("loadout").get(0).path("automation").path("second").asInt());
    }

    @Test
    void missingOrRemovedLearnedFeatureIsReportedWithoutChangingStoredSheet() throws Exception {
        ObjectNode sheet = (ObjectNode) MAPPER.readTree("""
                {"features":[{"name":"Gift","source":"Class feature - Bard","catalog":{"category":"classes",
                 "id":"bard","featureId":"missing","values":{},"_storedFields":["text"]}}]}
                """);
        ObjectNode classes = (ObjectNode) MAPPER.readTree("""
                {"category":"classes","entries":[{"id":"bard","features":[]}]}
                """);
        String before = sheet.toString();
        var error = assertThrows(SheetReferences.MissingReference.class,
                () -> SheetReferences.resolve(sheet, List.of(classes)));
        assertTrue(error.getMessage().contains("classes/bard"));
        assertEquals(before, sheet.toString());
    }
}
