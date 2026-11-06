package dmmt.online;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CatalogEditorTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    @TempDir Path temp;

    private ObjectNode file(String entries) throws Exception {
        ObjectNode file = (ObjectNode) MAPPER.readTree("""
                {"format":"dmmap-daggerheart","version":1,"category":"items","label":"Items","extension":{"keep":true},"entries":[]}
                """);
        file.set("entries", MAPPER.readTree(entries));
        return file;
    }

    @Test
    void acceptsAllMaintainedCatalogsIncludingNestedAutomationAndSubclassStages() throws Exception {
        for (String category : CatalogEditor.CATEGORIES)
            assertDoesNotThrow(() -> CatalogEditor.validate(CatalogEditor.read(Path.of("dmmap-daggerheart"), category).file(), category), category);
    }

    @Test
    void editsAndCreatesInTheActualJsonPreservingOtherDataAndRejectingStaleDrafts() throws Exception {
        ObjectNode original = file(""" 
                [{"id":"one","name":"One","text":"Old","extra":{"keep":42}},{"id":"two","name":"Two","text":"Untouched"}]
                """);
        Path path = temp.resolve("items.json");
        Files.writeString(path, original.toString());
        var loaded = CatalogEditor.read(temp, "items");
        ObjectNode edit = ((ObjectNode) loaded.file().path("entries").get(0)).deepCopy();
        edit.put("text", "Edited");
        var saved = CatalogEditor.save(temp, "items", loaded.revision(), "one", edit);
        ObjectNode disk = (ObjectNode) MAPPER.readTree(path.toFile());
        assertEquals("Edited", disk.path("entries").get(0).path("text").asText());
        assertEquals(original.path("entries").get(1), disk.path("entries").get(1));
        assertEquals(original.path("extension"), disk.path("extension"));
        assertEquals(42, disk.path("entries").get(0).path("extra").path("keep").asInt());
        assertEquals("Old", disk.path("entries").get(0).path("_editorOriginal").path("text").asText());
        assertNotEquals(loaded.revision(), saved.revision());
        assertEquals(409, assertThrows(CatalogEditor.Rejected.class,
                () -> CatalogEditor.save(temp, "items", loaded.revision(), "one", edit)).status());
        ObjectNode added = MAPPER.createObjectNode().put("id", "three").put("name", "Three").put("text", "New");
        var created = CatalogEditor.save(temp, "items", saved.revision(), "", added);
        assertEquals(3, created.file().path("entries").size());
        assertEquals("three", MAPPER.readTree(path.toFile()).path("entries").get(2).path("id").asText());
        assertEquals(400, assertThrows(CatalogEditor.Rejected.class,
                () -> CatalogEditor.save(temp, "items", created.revision(), "", added)).status());
        edit.put("id", "changed");
        assertEquals(400, assertThrows(CatalogEditor.Rejected.class,
                () -> CatalogEditor.save(temp, "items", created.revision(), "one", edit)).status());
        assertEquals(created.file(), MAPPER.readTree(path.toFile()));
    }

    @Test
    void rejectsUnknownPathsInvalidAutomationAndInvalidNumbersWithoutWriting() throws Exception {
        ObjectNode original = file("[{\"id\":\"one\",\"name\":\"One\"}]");
        Files.writeString(temp.resolve("items.json"), original.toString());
        var loaded = CatalogEditor.read(temp, "items");
        ObjectNode invalid = MAPPER.createObjectNode().put("id", "new").put("name", "New");
        invalid.putObject("automation").putObject("uses").put("max", -1).put("per", "never");
        assertThrows(CatalogEditor.Rejected.class, () -> CatalogEditor.save(temp, "items", loaded.revision(), "", invalid));
        assertThrows(CatalogEditor.Rejected.class, () -> CatalogEditor.read(temp, "../items"));
        invalid.remove("automation");
        invalid.putObject("automation").put("actions", "not a list");
        assertThrows(CatalogEditor.Rejected.class, () -> CatalogEditor.save(temp, "items", loaded.revision(), "", invalid));
        assertEquals(original, MAPPER.readTree(temp.resolve("items.json").toFile()));
    }

    @Test
    void namedResourceKeysAndUnknownExtensionFieldsAreNotMistakenForRuleBlocks() throws Exception {
        ObjectNode file = file("""
                [{"id":"one","name":"One","extension":{"features":42,"uses":"notes"},
                  "automation":{"resources":[{"id":"actions","label":"Action points","initial":2}],
                  "actions":[{"id":"use","label":"Use","cost":{"actions":1,"uses":2},"steps":[]}]}}]
                """);
        assertDoesNotThrow(() -> CatalogEditor.validate(file, "items"));
    }

    @Test
    void refreshesRulesAndNamesWithoutResettingStateOrOverwritingManualEquipmentChanges() throws Exception {
        ObjectNode before = file("""
                [{"id":"mask","name":"Mask","text":"Old rules","automation":{"uses":{"max":1,"per":"longRest"}}}]
                """);
        ObjectNode sheet = (ObjectNode) MAPPER.readTree("""
                {"className":"Bard","evasion":12,"hp":{"slots":7,"on":4},"stress":{"on":3},"hope":{"on":2},
                 "usage":{"item:Mask":{"used":1,"per":"longRest"}},"resources":{"favor":2},
                 "inventory":[{"name":"Mask","note":"Old rules","type":"gear","qty":3,
                   "automation":{"uses":{"max":1,"per":"longRest"}}},
                   {"name":"Mask","note":"My custom item","type":"gear","qty":1,"custom":true}],
                 "features":[],"heritage":[],"notes":"Keep my notes"}
                """);
        CatalogSheets.refresh(sheet, List.of(before));
        ObjectNode after = before.deepCopy();
        ObjectNode entry = (ObjectNode) after.path("entries").get(0);
        entry.put("name", "New Mask").put("text", "New rules");
        entry.putObject("automation").putObject("uses").put("max", 2).put("per", "longRest");
        CatalogSheets.refresh(sheet, List.of(after));
        assertEquals("New Mask", sheet.path("inventory").get(0).path("name").asText());
        assertEquals("New rules", sheet.path("inventory").get(0).path("note").asText());
        assertEquals(2, sheet.path("inventory").get(0).path("automation").path("uses").path("max").asInt());
        assertEquals(3, sheet.path("inventory").get(0).path("qty").asInt());
        assertEquals("My custom item", sheet.path("inventory").get(1).path("note").asText());
        assertEquals(1, sheet.path("usage").path("item:New Mask").path("used").asInt());
        assertEquals(4, sheet.path("hp").path("on").asInt());
        assertEquals(7, sheet.path("hp").path("slots").asInt());
        assertEquals(12, sheet.path("evasion").asInt());
        assertEquals(2, sheet.path("resources").path("favor").asInt());
        ((ObjectNode) sheet.path("inventory").get(0)).put("note", "Manual override");
        entry.put("text", "Third rules");
        CatalogSheets.refresh(sheet, List.of(after));
        assertEquals("Manual override", sheet.path("inventory").get(0).path("note").asText());
    }

    @Test
    void legacySheetsRefreshAfterRestartAndArmorOverridesRemainIndependent() throws Exception {
        ObjectNode catalog = (ObjectNode) MAPPER.readTree("""
                {"category":"armor","entries":[{"id":"a","name":"New Armor","tier":1,
                "armor":{"score":4,"major":7,"severe":15},
                "_editorOriginal":{"id":"a","name":"Armor","tier":1,"armor":{"score":3,"major":5,"severe":12}}}]}
                """);
        ObjectNode sheet = (ObjectNode) MAPPER.readTree("""
                {"activeArmor":{"name":"Armor","tier":1,"score":3,"major":99,"severe":12},
                "armorTrack":{"on":2,"slots":3},"carriedArmor":[]}
                """);
        CatalogSheets.refresh(sheet, List.of(catalog));
        assertEquals("New Armor", sheet.path("activeArmor").path("name").asText());
        assertEquals(4, sheet.path("activeArmor").path("score").asInt());
        assertEquals(99, sheet.path("activeArmor").path("major").asInt());
        assertEquals(15, sheet.path("activeArmor").path("severe").asInt());
        assertEquals(2, sheet.path("armorTrack").path("on").asInt());
    }

    @Test
    void featureIdentitySurvivesRenamingAndReorderingIncludingTheFirstEditorSave() throws Exception {
        ObjectNode file = (ObjectNode) MAPPER.readTree("""
                {"format":"dmmap-daggerheart","version":1,"category":"classes","label":"Classes","entries":[
                 {"id":"sub","name":"Ranger: Example","kind":"subclass","parent":"Ranger","stage":"foundation",
                  "features":[{"name":"First","text":"Old","automation":{"uses":{"max":1,"per":"longRest"}}},
                              {"name":"Second","text":"Second rules"}]}]}
                """);
        Files.writeString(temp.resolve("classes.json"), file.toString());
        ObjectNode sheet = (ObjectNode) MAPPER.readTree("""
                {"className":"Ranger","subclass":"Example","evasion":13,"hp":{"on":3,"slots":7},
                "features":[{"name":"First","text":"Old","source":"Subclass foundation - Example",
                  "automation":{"uses":{"max":1,"per":"longRest"}}}],
                "usage":{"feat:First":{"used":1,"per":"longRest"}},"heritage":[]}
                """);
        CatalogSheets.refresh(sheet, List.of(file));
        var loaded = CatalogEditor.read(temp, "classes");
        ObjectNode edit = ((ObjectNode) CatalogEditor.editable(loaded.file()).path("entries").get(0)).deepCopy();
        edit.put("name", "Ranger: Example Plus");
        var features = edit.withArray("features");
        ObjectNode first = ((ObjectNode) features.get(0)).deepCopy();
        first.put("name", "Renamed").put("text", "New text");
        first.putObject("automation").putObject("uses").put("max", 2).put("per", "longRest");
        var second = features.get(1).deepCopy();
        features.removeAll().add(second).add(first);
        var saved = CatalogEditor.save(temp, "classes", loaded.revision(), "sub", edit);
        CatalogSheets.refresh(sheet, List.of(saved.file()));
        assertEquals("Renamed", sheet.path("features").get(0).path("name").asText());
        assertEquals("New text", sheet.path("features").get(0).path("text").asText());
        assertEquals(2, sheet.path("features").get(0).path("automation").path("uses").path("max").asInt());
        assertEquals(1, sheet.path("usage").path("feat:Renamed").path("used").asInt());
        assertEquals(13, sheet.path("evasion").asInt());
        assertEquals(3, sheet.path("hp").path("on").asInt());
        assertEquals("Example Plus", sheet.path("subclass").asText());
        CatalogSheets.refresh(sheet, List.of(saved.file()));
        assertEquals("Subclass foundation - Example Plus", sheet.path("features").get(0).path("source").asText());
        ObjectNode unlinked = (ObjectNode) MAPPER.readTree("""
                {"className":"Ranger","subclass":"Example","features":[
                  {"name":"First","text":"Old","source":"Subclass foundation - Example","automation":{"uses":{"max":1,"per":"longRest"}}}]}
                """);
        CatalogSheets.refresh(unlinked, List.of(saved.file()));
        assertEquals("Renamed", unlinked.path("features").get(0).path("name").asText());
    }
}
