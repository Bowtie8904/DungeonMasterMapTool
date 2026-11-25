package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dmmt.online.automation.RuleConformanceTest.fixture;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Copycat (OnlineSessionServer.copycatAction, CopycatCards) as a card.borrow rule plus the host-kept borrowCandidates and
 * borrowedCards, and a second fixture borrowing a different level range through the same primitive.
 */
class CopycatTest {
    private static final AutomationHost HOST = AutomationHost.bundled();

    private static LegacyCatalogConverter.Result convert() throws Exception {
        ObjectNode root = (ObjectNode) RuleJson.parse(Files.readString(dmmt.online.LegacyCatalogs.dir().resolve("domain-cards.json")));
        ArrayNode kept = RuleJson.JSON.createArrayNode();
        for (JsonNode entry : root.path("entries")) if ("copycat".equals(entry.path("id").asText())) kept.add(entry);
        root.set("entries", kept);
        return LegacyCatalogConverter.convert(root.toString());
    }

    private static RuleCompiler.Program copycat() throws Exception {
        var result = convert();
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        return RuleCatalog.compile(result.catalog().toString(), LegacyCatalogConverter.coreRecipes()).stream()
                .map(RuleCatalog.Source::program).findFirst().orElseThrow();
    }

    private static RuleDispatcher.EventResolution use(RuleCompiler.Program program, int hope, String pick) throws Exception {
        ObjectNode context = (ObjectNode) RuleJson.parse("""
                {"source":{"id":"%s","instanceId":"fixture","name":"Copycat"},
                 "actor":{"id":"me","name":"Ash","stats":{"tier":1},"hope":{"value":%d,"max":6},"stress":{"marked":0,"capacity":6},
                  "borrowCandidates":[%s]},"revision":0}""".formatted(program.sourceId(), hope, pick));
        context.set("event", RuleJson.parse("{\"type\":\"action.request\"}"));
        return RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, context)),
                List.of(new RuleRuntime.Intent("use", "sheet", "fixture", 0,
                        (ObjectNode) RuleJson.parse("{\"pick\":" + pick + "}"))), StructuredRollTest.noRandom());
    }

    private static String candidate(String card, int level) {
        return "{\"id\":\"b:%s\",\"actorId\":\"b\",\"actorName\":\"Bo\",\"card\":\"%s\",\"level\":%d}".formatted(card, card, level);
    }

    @Test
    void convertedCopycatPicksACandidateAndDeclaresTheBorrowRange() throws Exception {
        var result = convert();
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        JsonNode block = result.catalog().at("/entries/0/automation");
        assertEquals(1, block.at("/borrowCards/minLevel").asInt());
        assertEquals(8, block.at("/borrowCards/maxLevel").asInt());
        assertEquals("Copycat", block.at("/borrowCards/exclude/0").asText());
        assertEquals(1, block.at("/limits/uses/max").asInt());
        assertEquals("record.select", block.at("/rules/0/inputs/0/kind").asText());
        assertEquals("card.borrow", block.at("/rules/0/do/0/op").asText());
    }

    @Test
    void spendsHalfTheCardLevelRoundedUpAndRequestsTheBorrow() throws Exception {
        RuleCompiler.Program p = copycat();
        for (int level = 1; level <= 8; level++) {
            var out = use(p, 6, candidate("Brace", level)).contexts().getFirst();
            assertEquals(6 - (level + 1) / 2, out.at("/actor/hope/value").asInt(), "level " + level + " " + out);
            assertEquals("card.borrow", out.at("/outbox/0/kind").asText());
            assertEquals("b", out.at("/outbox/0/lender").asText());
            assertEquals("Brace", out.at("/outbox/0/card").asText());
        }
    }

    @Test
    void rejectsWhenHopeIsShort() throws Exception {
        var error = assertThrows(RuntimeException.class, () -> use(copycat(), 1, candidate("Brace", 8)));
        assertTrue(error.getMessage().contains("Not enough"), error.getMessage());
    }

    private static AutomationHost.Pack borrowPack(int min, int max) throws Exception {
        ObjectNode catalog = (ObjectNode) fixture("rewrite/host-classes");
        ObjectNode feature = (ObjectNode) RuleJson.parse("""
                {"id":"borrow","name":"Borrow","automation":{"borrowCards":{"minLevel":%d,"maxLevel":%d,"exclude":["Rally"]},
                 "rules":[{"id":"go","bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button"}],
                  "do":[{"op":"card.borrow","lender":"b","card":"Steady"}],"ui":{"label":"Go"}}]}}""".formatted(min, max));
        ObjectNode entry = (ObjectNode) catalog.withArray("entries").get(0);
        entry.withArray("features").add(feature);
        Map<String, String> catalogs = new LinkedHashMap<>();
        catalogs.put("classes", catalog.toString());
        return AutomationHost.compile("borrow", catalogs, fixture("rewrite/recipes").toString());
    }

    private static ObjectNode sheet(String loadout) throws Exception {
        return (ObjectNode) RuleJson.parse("""
                {"name":"S","features":[{"name":"Borrow","automationSource":"classes:tester:features:borrow","instanceId":"f-borrow"}],
                 "loadout":[%s]}""".formatted(loadout));
    }

    private static String card(String name, int level, String source, String instance) {
        return "{\"name\":\"%s\",\"level\":%d,\"automationSource\":\"%s\",\"instanceId\":\"%s\"}".formatted(name, level, source, instance);
    }

    @Test
    void candidatesAreOtherActorsLoadoutCardsInRangeSortedAndDeduplicated() throws Exception {
        var pack = borrowPack(2, 3);
        ObjectNode me = sheet("");
        ObjectNode bo = sheet(card("Steady", 3, "classes:tester:features:steady", "l1") + "," + card("Rally", 2, "classes:tester:features:rally", "l2")
                + "," + card("Low", 1, "x:low", "l3") + "," + card("Fine", 2, "x:fine", "l4"));
        ObjectNode cy = sheet(card("Fine", 2, "x:fine", "l5"));
        Map<String, ObjectNode> active = new LinkedHashMap<>(Map.of("me", me, "bo", bo));
        active.put("cy", cy);
        assertTrue(HOST.synchronizeBorrowedCards(pack, active, Map.of("bo", "Bo", "cy", "Cy")).contains("me"));
        ArrayNode list = (ArrayNode) me.path("borrowCandidates");
        assertEquals(List.of("Fine", "Steady"), list.findValuesAsText("card"), "Rally is excluded, Low is below the range, Fine appears once");
        assertEquals("Bo", list.get(0).path("actorName").asText());
        assertFalse(HOST.synchronizeBorrowedCards(pack, active, Map.of("bo", "Bo", "cy", "Cy")).contains("me"), "idempotent");
    }

    @Test
    void borrowedCardBecomesAnActiveSourceAndEndsWhenTheLenderVaultsItOrAnyoneRests() throws Exception {
        var pack = borrowPack(1, 8);
        ObjectNode me = sheet("");
        ObjectNode bo = sheet(card("Steady", 3, "classes:tester:features:steady", "l1"));
        Map<String, ObjectNode> active = new LinkedHashMap<>(Map.of("me", me, "bo", bo));
        HOST.borrowCard(pack, me, "f-borrow", "bo", "Bo", bo, "Steady");
        assertTrue(HOST.sources(pack, me).stream().anyMatch(s -> s.sourceId().equals("classes:tester:features:steady")));
        assertEquals(1L, me.path("compendiumRevision").asLong());
        assertTrue(HOST.synchronizeBorrowedCards(pack, active, Map.of()).isEmpty() || me.has("borrowedCards"), "still held");
        assertTrue(me.has("borrowedCards"));

        ObjectNode vaulted = bo.deepCopy();
        ((ArrayNode) vaulted.path("loadout")).removeAll();
        active.put("bo", vaulted);
        HOST.synchronizeBorrowedCards(pack, active, Map.of());
        assertFalse(me.has("borrowedCards"), "the lender vaulted the card");

        active.put("bo", bo);
        HOST.borrowCard(pack, me, "f-borrow", "bo", "Bo", bo, "Steady");
        me.put("shortRestsInRow", 1);
        HOST.synchronizeBorrowedCards(pack, active, Map.of());
        assertFalse(me.has("borrowedCards"), "any rest ends the borrow");

        HOST.borrowCard(pack, me, "f-borrow", "bo", "Bo", bo, "Steady");
        me.put("longRestRevision", 4);
        HOST.synchronizeBorrowedCards(pack, active, Map.of());
        assertFalse(me.has("borrowedCards"), "a long rest ends the borrow");
    }

    @Test
    void aNewBorrowReplacesTheOldOneAndTheRangeIsEnforced() throws Exception {
        var pack = borrowPack(2, 3);
        ObjectNode me = sheet("");
        ObjectNode bo = sheet(card("Steady", 3, "classes:tester:features:steady", "l1") + "," + card("Forage", 2, "classes:tester:features:forage", "l2")
                + "," + card("Low", 1, "x:low", "l3"));
        HOST.borrowCard(pack, me, "f-borrow", "bo", "Bo", bo, "Steady");
        HOST.borrowCard(pack, me, "f-borrow", "bo", "Bo", bo, "Forage");
        assertEquals(1, me.path("borrowedCards").size());
        assertEquals("Forage", me.at("/borrowedCards/0/name").asText());
        assertThrows(IllegalArgumentException.class, () -> HOST.borrowCard(pack, me, "f-borrow", "bo", "Bo", bo, "Low"));
        assertThrows(IllegalArgumentException.class, () -> HOST.borrowCard(pack, me, "f-borrow", "bo", "Bo", bo, "Missing"));
    }

    @Test
    void borrowCardsRejectsAnInvertedRange() throws Exception {
        assertThrows(RuntimeException.class, () -> borrowPack(5, 2));
    }
}
