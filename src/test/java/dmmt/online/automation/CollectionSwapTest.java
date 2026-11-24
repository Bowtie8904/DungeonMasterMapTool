package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** {@code collection.swap}, the {@code transfer} projection group, {@code length} facts and filtered record choices. */
class CollectionSwapTest {
    private static final String SPEC = """
            {"format":"dmmap-sheet-projection","version":2,
             "stateField":"automationState","sourceField":"automationSource","instanceField":"instanceId","nameField":"name",
             "sources":[{"path":"features"}],
             "actor":{
               "loadout":{"kind":"collection","path":"loadout","fields":{"id":"name","level":"level"},"writable":false,"transfer":"cards"},
               "vault":{"kind":"collection","path":"vault","fields":{"id":"name","level":"level"},"writable":false,"transfer":"cards"},
               "notes":{"kind":"length","path":"notes"}}}""";

    private static ObjectNode sheet() throws Exception {
        return (ObjectNode) RuleJson.parse("""
                {"name":"Ari","notes":["a","b"],
                 "loadout":[{"name":"Alpha","level":1,"text":"first","instanceId":"i1"},{"name":"Beta","level":2,"text":"second","instanceId":"i2"}],
                 "vault":[{"name":"Gamma","level":3,"text":"third","instanceId":"i3","permanentlyVaulted":false}]}""");
    }

    @Test
    void transferGroupWritesSwappedCardsBackWithTheirRawFields() throws Exception {
        SheetProjection projection = SheetProjection.parse(SPEC);
        ObjectNode sheet = sheet();
        ObjectNode before = projection.actor(sheet);
        ObjectNode after = before.deepCopy();
        JsonNode loadout = after.get("loadout").get(1), vault = after.get("vault").get(0);
        ((com.fasterxml.jackson.databind.node.ArrayNode) after.get("loadout")).set(1, vault);
        ((com.fasterxml.jackson.databind.node.ArrayNode) after.get("vault")).set(0, loadout);
        projection.write(sheet, before, after);
        assertEquals("Gamma", sheet.at("/loadout/1/name").asText());
        assertEquals("third", sheet.at("/loadout/1/text").asText());
        assertEquals("i3", sheet.at("/loadout/1/instanceId").asText());
        assertEquals("Beta", sheet.at("/vault/0/name").asText());
        assertEquals("i2", sheet.at("/vault/0/instanceId").asText());
        assertTrue(sheet.at("/vault/0").has("permanentlyVaulted") || !sheet.at("/vault/0").has("x"));
    }

    @Test
    void transferGroupRejectsCreatingEditingAndDeletingCards() throws Exception {
        SheetProjection projection = SheetProjection.parse(SPEC);
        ObjectNode sheet = sheet();
        ObjectNode before = projection.actor(sheet);
        ObjectNode created = before.deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) created.get("vault")).addObject().put("id", "Made").put("level", 1);
        assertThrows(RuntimeException.class, () -> projection.write(sheet(), before, created));
        ObjectNode edited = before.deepCopy();
        ((ObjectNode) edited.get("vault").get(0)).put("level", 9);
        assertThrows(RuntimeException.class, () -> projection.write(sheet(), before, edited));
        ObjectNode deleted = before.deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) deleted.get("vault")).remove(0);
        assertThrows(RuntimeException.class, () -> projection.write(sheet(), before, deleted));
    }

    @Test
    void lengthFactsProjectTheListSizeOrZero() throws Exception {
        SheetProjection projection = SheetProjection.parse(SPEC);
        assertEquals(2, projection.actor(sheet()).path("notes").asInt());
        assertEquals(0, projection.actor(RuleJson.JSON.createObjectNode()).path("notes").asInt());
    }

    @Test
    void recordChoicesCanBeFilteredPerOptionAndSwapsRejectMissingCards() throws Exception {
        var sources = RuleCatalog.compile("""
                {"format":"dmmap-daggerheart","version":2,"category":"classes","label":"T","entries":[{"id":"t","name":"T","automation":{
                 "rules":[{"id":"pick","bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button"}],
                  "inputs":[{"id":"note","kind":"record.select","label":"Note","from":{"read":"actor.vault"},
                    "where":{"gte":[{"read":"input.option.level"},3]},"choiceLabel":"{input.option.id}"}],
                  "do":[{"op":"collection.swap","target":"actor","collection":"vault","with":"loadout",
                         "item":{"read":"input.note.id"},"withItem":"Beta"}],
                  "ui":{"label":"Pick"}}]}}]}""", LegacyCatalogConverter.coreRecipes());
        RuleCompiler.Program program = sources.getFirst().program();
        String actor = "{\"vault\":[{\"id\":\"Low\",\"level\":1},{\"id\":\"High\",\"level\":3}],\"loadout\":[{\"id\":\"Beta\",\"level\":2}]}";
        ObjectNode context = (ObjectNode) RuleJson.parse("{\"source\":{\"id\":\"" + program.sourceId()
                + "\",\"instanceId\":\"x\",\"name\":\"T\"},\"actor\":" + actor + ",\"revision\":0,\"event\":{\"type\":\"action.request\"}}");
        assertThrows(RuleRuntime.Unavailable.class, () -> RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, context.deepCopy())),
                List.of(new RuleRuntime.Intent("pick", "sheet", "x", 0, (ObjectNode) RuleJson.parse("{\"note\":{\"id\":\"Low\",\"level\":1}}"))),
                StructuredRollTest.noRandom()));
        var done = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, context.deepCopy())),
                List.of(new RuleRuntime.Intent("pick", "sheet", "x", 0, (ObjectNode) RuleJson.parse("{\"note\":{\"id\":\"High\",\"level\":3}}"))),
                StructuredRollTest.noRandom());
        JsonNode after = done.contexts().getFirst().path("actor");
        assertEquals("Beta", after.at("/vault/1/id").asText(), after.toString());
        assertEquals("High", after.at("/loadout/0/id").asText(), after.toString());
    }
}
