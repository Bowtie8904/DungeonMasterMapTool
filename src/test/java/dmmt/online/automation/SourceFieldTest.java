package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The source.set operation and the sourceFields declaration: persisted whole numbers kept on the hosting sheet entry. */
class SourceFieldTest {
    private static final String CATALOG = """
            {"format":"dmmap-daggerheart","version":2,"category":"consumables","label":"Fixture","entries":[
              {"id":"lucky-charm","name":"Lucky Charm","automation":{
                "sourceFields":{"luck":{"label":"Luck","min":0,"max":6}},
                "rules":[{"id":"toss","bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button"}],
                  "do":[{"op":"dice.roll","count":1,"sides":6,"as":"die"},{"op":"source.set","field":"luck","value":{"read":"result.die"}}],
                  "ui":{"label":"Toss","report":"{source.name} lands on {result.die}."}}]}}]}""";

    private static AutomationHost.Pack pack(String catalog) throws Exception {
        return AutomationHost.compile("test", Map.of("consumables", catalog), RuleConformanceTest.fixture("rewrite/recipes").toString());
    }

    @Test
    void aDeclaredFieldIsWrittenBackToTheHostingSheetEntry() throws Exception {
        AutomationHost host = AutomationHost.bundled();
        var pack = pack(CATALOG);
        String sourceId = RuleCatalog.compile(CATALOG, RuleConformanceTest.fixture("rewrite/recipes").toString()).getFirst().id();
        ObjectNode sheet = (ObjectNode) RuleJson.parse("""
                {"name":"Ada","level":2,"hp":{"slots":6,"on":0},"stress":{"slots":6,"on":0},"armorTrack":{"slots":3,"on":0},
                 "hope":{"slots":6,"on":0},"features":[],"heritage":[],"loadout":[],"vault":[],
                 "inventory":[{"name":"Lucky Charm","qty":1,"luck":2,"automationSource":"%s","instanceId":"charm"}],"activeWeapons":[]}""".formatted(sourceId));
        ObjectNode request = RuleJson.JSON.createObjectNode().put("requestId", "r1").put("expectedRevision", 0);
        request.putArray("selections").addObject().put("sourceInstanceId", "charm").put("ruleId", "toss").put("bindingId", "sheet");
        ObjectNode receipt = host.execute(pack, sheet, AutomationHost.event("action.request", "charm", 0), request, "self", StructuredRollTest.faces(4));
        assertEquals(4, sheet.at("/inventory/0/luck").asInt(), sheet.toString());
        assertEquals("Lucky Charm lands on 4.", receipt.at("/actions/0/report").asText());
    }

    @Test
    void anUndeclaredOrReservedFieldIsRejectedAtCompileTime() {
        String undeclared = CATALOG.replace("\"sourceFields\":{\"luck\":{\"label\":\"Luck\",\"min\":0,\"max\":6}},", "");
        assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(undeclared, RuleConformanceTest.fixture("rewrite/recipes").toString()));
        for (String reserved : List.of("name", "quantity", "automationState", "instanceId")) {
            String bad = CATALOG.replace("\"luck\"", "\"" + reserved + "\"");
            assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(bad, RuleConformanceTest.fixture("rewrite/recipes").toString()), reserved);
        }
    }

    @Test
    void aValueOutsideTheDeclaredRangeIsRejectedAndLeavesTheSheetUntouched() throws Exception {
        String narrow = CATALOG.replace("\"max\":6", "\"max\":3");
        String recipes = RuleConformanceTest.fixture("rewrite/recipes").toString();
        var pack = pack(narrow);
        String sourceId = RuleCatalog.compile(narrow, recipes).getFirst().id();
        ObjectNode sheet = (ObjectNode) RuleJson.parse("""
                {"name":"Ada","level":2,"hp":{"slots":6,"on":0},"stress":{"slots":6,"on":0},"armorTrack":{"slots":3,"on":0},
                 "hope":{"slots":6,"on":0},"features":[],"heritage":[],"loadout":[],"vault":[],
                 "inventory":[{"name":"Lucky Charm","qty":1,"luck":2,"automationSource":"%s","instanceId":"charm"}],"activeWeapons":[]}""".formatted(sourceId));
        ObjectNode before = sheet.deepCopy();
        ObjectNode request = RuleJson.JSON.createObjectNode().put("requestId", "r1").put("expectedRevision", 0);
        request.putArray("selections").addObject().put("sourceInstanceId", "charm").put("ruleId", "toss").put("bindingId", "sheet");
        assertThrows(IllegalArgumentException.class, () -> AutomationHost.bundled().execute(pack, sheet,
                AutomationHost.event("action.request", "charm", 0), request, "self", StructuredRollTest.faces(5)));
        assertEquals(before, sheet);
    }
}