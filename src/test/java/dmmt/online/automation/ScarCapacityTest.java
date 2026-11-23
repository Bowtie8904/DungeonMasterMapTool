package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScarCapacityTest {
    private static final AutomationHost HOST = AutomationHost.bundled();

    private static AutomationHost.Pack convertedItems() throws Exception {
        JsonNode shipped = RuleJson.parse(Files.readString(Path.of("dmmap-daggerheart", "consumables.json")));
        ObjectNode catalog = RuleJson.JSON.createObjectNode().put("format", "dmmap-daggerheart").put("version", 1)
                .put("category", "consumables").put("label", "Scars");
        var entries = catalog.putArray("entries");
        for (JsonNode entry : shipped.path("entries"))
            if (List.of("sun-tree-sap", "snakeskin-spirit").contains(entry.path("id").asText())) entries.add(entry.deepCopy());
        assertEquals(2, entries.size());
        var converted = LegacyCatalogConverter.convert(catalog.toString());
        assertEquals(2, converted.converted(), converted.diagnostics().toString());
        return AutomationHost.compile("scar-items", Map.of("consumables", converted.catalog().toString()),
                LegacyCatalogConverter.coreRecipes());
    }

    private static ObjectNode sheet(int scars, int slots, int hope) throws Exception {
        ObjectNode sheet = (ObjectNode) RuleJson.parse("""
                {"name":"Ada","hope":{"total":6,"slots":6,"on":0},"hp":{"slots":6,"on":0},
                 "stress":{"slots":6,"on":0},"armorTrack":{"slots":3,"on":0}}
                """);
        sheet.put("scars", scars);
        sheet.withObject("/hope").put("slots", slots).put("on", hope);
        sheet.putArray("inventory").addObject().put("name", "Sun Tree Sap").put("qty", 1)
                .put("instanceId", "sap").put("automationSource", "consumables:sun-tree-sap");
        sheet.withArray("inventory").addObject().put("name", "Snakeskin Spirit").put("qty", 1)
                .put("instanceId", "spirit").put("automationSource", "consumables:snakeskin-spirit");
        return sheet;
    }

    private static ObjectNode request(String id, long revision, String instance, String rule) {
        ObjectNode request = RuleJson.JSON.createObjectNode().put("requestId", id).put("expectedRevision", revision);
        request.putArray("selections").addObject().put("sourceInstanceId", instance).put("ruleId", rule).put("bindingId", "sheet");
        return request;
    }

    @Test
    void shippedScarGainAndHealingKeepHopeCapacityAndCurrentValueConsistent() throws Exception {
        var pack = convertedItems();
        ObjectNode sheet = sheet(1, 5, 5);
        ObjectNode gain = HOST.execute(pack, sheet, AutomationHost.event("action.request", "ada", 0),
                request("gain", 0, "sap", "use"), "self", StructuredRollTest.faces(1));
        assertTrue(gain.at("/actions/0/report").asText().contains("scar"), gain.toString());
        assertEquals(2, sheet.path("scars").asInt());
        assertEquals(4, sheet.at("/hope/slots").asInt());
        assertEquals(4, sheet.at("/hope/on").asInt());
        assertEquals(6, sheet.at("/hope/total").asInt());

        HOST.execute(pack, sheet, AutomationHost.event("action.request", "ada", 0),
                request("heal", 1, "spirit", "drink"), "self", StructuredRollTest.noRandom());
        assertEquals(1, sheet.path("scars").asInt());
        assertEquals(5, sheet.at("/hope/slots").asInt());
        assertEquals(4, sheet.at("/hope/on").asInt(), "Restoring a slot must not grant Hope.");
        assertEquals(6, sheet.at("/hope/total").asInt());
    }

    @Test
    void reservesAreBoundedAndLaterStepsReadTheUpdatedCapacity() throws Exception {
        ObjectNode classes = (ObjectNode) RuleJson.parse("""
                {"format":"dmmap-daggerheart","version":2,"category":"classes","label":"Test","entries":[
                 {"id":"test","name":"Test","automation":{"rules":[
                  {"id":"reserve","bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button"}],
                   "do":[{"op":"track.capacity","track":"actor.hope","counter":"actor.scars",
                          "direction":"reserve","amount":10000,"as":"lost"},
                         {"op":"resource.gain","pool":"actor.hope","amount":6,"as":"gain"}],
                   "ui":{"label":"Reserve","report":"Capacity {actor.hope.max}, gain {result.gain}."}},
                  {"id":"release","bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button"}],
                   "do":[{"op":"track.capacity","track":"actor.hope","counter":"actor.scars","by":6,"as":"restored"}],
                   "ui":{"label":"Release","report":"Restored {result.restored}."}},
                  {"id":"invalid","bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button"}],
                   "do":[{"op":"track.capacity","track":"actor.hope","counter":"actor.scars",
                          "direction":"reserve","amount":-1}],"ui":{"label":"Invalid"}}
                 ]}}]}
                """);
        var pack = AutomationHost.compile("reserve", Map.of("classes", classes.toString()), LegacyCatalogConverter.coreRecipes());
        ObjectNode sheet = sheet(4, 2, 2);
        sheet.putArray("features").addObject().put("name", "Test").put("instanceId", "test").put("automationSource", "classes:test");
        ObjectNode receipt = HOST.execute(pack, sheet, AutomationHost.event("action.request", "ada", 0),
                request("reserve", 0, "test", "reserve"), "self", StructuredRollTest.noRandom());
        assertEquals("Capacity 0, gain 0.", receipt.at("/actions/0/report").asText());
        assertEquals(6, sheet.path("scars").asInt());
        assertEquals(0, sheet.at("/hope/slots").asInt());
        assertEquals(0, sheet.at("/hope/on").asInt());
        receipt = HOST.execute(pack, sheet, AutomationHost.event("action.request", "ada", 0),
                request("release", 1, "test", "release"), "self", StructuredRollTest.noRandom());
        assertEquals("Restored 6.", receipt.at("/actions/0/report").asText());
        assertEquals(0, sheet.path("scars").asInt());
        assertEquals(6, sheet.at("/hope/slots").asInt());
        assertEquals(0, sheet.at("/hope/on").asInt());
        ObjectNode beforeInvalid = sheet.deepCopy();
        assertThrows(IllegalArgumentException.class, () -> HOST.execute(pack, sheet,
                AutomationHost.event("action.request", "ada", 0),
                request("invalid", 2, "test", "invalid"), "self", StructuredRollTest.noRandom()));
        assertEquals(beforeInvalid, sheet);
    }

    @Test
    void recipientCapacityAndReserveChangeTogether() throws Exception {
        ObjectNode legacy = (ObjectNode) RuleJson.parse("""
                {"format":"dmmap-daggerheart","version":1,"category":"classes","label":"Test","entries":[
                 {"id":"healer","name":"Healer","automation":{"actions":[
                  {"id":"heal","label":"Heal","target":"anyActor","excludeSourceActor":true,
                   "steps":[{"op":"clear","resource":"scars","amount":"all"}]}
                 ]}}]}
                """);
        var converted = LegacyCatalogConverter.convert(legacy.toString());
        assertEquals(1, converted.converted(), converted.diagnostics().toString());
        var program = RuleCatalog.compile(converted.catalog().toString(), LegacyCatalogConverter.coreRecipes()).getFirst().program();
        JsonNode step = converted.catalog().at("/entries/0/automation/rules/0/do/0");
        assertEquals("recipient.hope", step.path("track").asText());
        assertEquals("recipient.scars", step.path("counter").asText());
        ObjectNode sourceSheet = sheet(0, 6, 2), recipientSheet = sheet(1, 5, 3);
        ObjectNode source = HOST.projection().actor(sourceSheet).put("id", "ada");
        ObjectNode recipient = HOST.projection().actor(recipientSheet).put("id", "mira");
        ObjectNode context = RuleJson.JSON.createObjectNode().put("revision", 0);
        context.putObject("source").put("id", "classes:healer").put("instanceId", "healer").put("name", "Healer");
        context.set("actor", source);
        context.set("event", AutomationHost.event("action.request", "ada", 0));
        var intent = new RuleDispatcher.TargetIntent(new RuleRuntime.Intent("heal", "sheet", "healer", 0,
                RuleJson.JSON.createObjectNode()), "mira", 0L);
        var result = RuleDispatcher.executeTargets(List.of(new RuleDispatcher.Candidate(program, context)), intent,
                List.of(new RuleDispatcher.RecipientCandidate("mira", recipient, 0)), StructuredRollTest.noRandom());
        HOST.projection().write(recipientSheet, recipient, result.recipients().getFirst().snapshot());
        assertEquals(0, recipientSheet.path("scars").asInt());
        assertEquals(6, recipientSheet.at("/hope/slots").asInt());
        assertEquals(3, recipientSheet.at("/hope/on").asInt());
        assertEquals(6, sourceSheet.at("/hope/slots").asInt());
    }

    @Test
    void invalidOrUnpairedReservesFailWithoutChangingTheSheet() throws Exception {
        var pack = convertedItems();
        for (String invalid : List.of("null", "\"bad\"", "-1", "7")) {
            ObjectNode sheet = sheet(1, 5, 3);
            sheet.set("scars", RuleJson.parse(invalid));
            ObjectNode before = sheet.deepCopy();
            assertThrows(IllegalArgumentException.class, () -> HOST.execute(pack, sheet,
                    AutomationHost.event("action.request", "ada", 0),
                    request("heal", 0, "spirit", "drink"), "self", StructuredRollTest.noRandom()), invalid);
            assertEquals(before, sheet, invalid);
        }
        for (String invalid : List.of("null", "\"bad\"", "5")) {
            ObjectNode sheet = sheet(1, 5, 3);
            sheet.withObject("/hope").set("total", RuleJson.parse(invalid));
            ObjectNode before = sheet.deepCopy();
            assertThrows(IllegalArgumentException.class, () -> HOST.execute(pack, sheet,
                    AutomationHost.event("action.request", "ada", 0),
                    request("heal", 0, "spirit", "drink"), "self", StructuredRollTest.noRandom()), invalid);
            assertEquals(before, sheet, invalid);
        }
        for (String field : List.of("scars", "total")) {
            ObjectNode sheet = sheet(0, 6, 3);
            if ("scars".equals(field)) sheet.putNull(field);
            else sheet.withObject("/hope").putNull(field);
            ObjectNode before = sheet.deepCopy();
            assertThrows(IllegalArgumentException.class, () -> HOST.execute(pack, sheet,
                    AutomationHost.event("action.request", "ada", 0),
                    request("noop-heal", 0, "spirit", "drink"), "self", StructuredRollTest.noRandom()), field);
            assertEquals(before, sheet, field);
        }
        ObjectNode oldSheet = sheet(1, 5, 3);
        oldSheet.withObject("/hope").remove("total");
        HOST.execute(pack, oldSheet, AutomationHost.event("action.request", "ada", 0),
                request("old", 0, "spirit", "drink"), "self", StructuredRollTest.noRandom());
        assertEquals(6, oldSheet.at("/hope/slots").asInt());
        assertEquals(0, oldSheet.path("scars").asInt());
        assertFalse(oldSheet.path("hope").has("total"));

        ObjectNode sheet = sheet(1, 5, 3), before = HOST.projection().actor(sheet);
        ObjectNode changedCounter = before.deepCopy().put("scars", 0);
        assertThrows(IllegalArgumentException.class, () -> HOST.projection().write(sheet, before, changedCounter));
        ObjectNode changedCapacity = before.deepCopy();
        changedCapacity.withObject("/hope").put("max", 6);
        assertThrows(IllegalArgumentException.class, () -> HOST.projection().write(sheet, before, changedCapacity));
        assertEquals(1, sheet.path("scars").asInt());
        assertEquals(5, sheet.at("/hope/slots").asInt());
    }

    @Test
    void compilerRejectsCrossOwnerAndInvalidReserveCounters() throws Exception {
        String rule = """
                {"rules":[{"id":"x","bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button"}],
                  "do":[{"op":"track.capacity","track":"actor.hope","counter":"%s","by":1}],"ui":{"label":"X"}}]}
                """;
        for (String counter : List.of("recipient.scars", "actor.hope", "state.scars", "actor.scars.extra", "scars"))
            assertThrows(IllegalArgumentException.class, () -> RuleCompiler.compile("features:test",
                    RuleJson.parse(rule.formatted(counter)), RuleJson.parse(LegacyCatalogConverter.coreRecipes())), counter);
        for (String operation : List.of(
                "\"track\":\"actor.hope\",\"amount\":1,\"direction\":\"reserve\"",
                "\"track\":\"actor.hope\",\"counter\":\"actor.scars\",\"amount\":1",
                "\"track\":\"actor.hope\",\"counter\":\"actor.scars\",\"amount\":1,\"direction\":\"wrong\"",
                "\"track\":\"actor.hope\",\"counter\":\"actor.scars\",\"amount\":1,\"direction\":\"release\",\"by\":1",
                "\"track\":\"actor.hope\",\"counter\":\"actor.scars\"")) {
            String directed = """
                    {"rules":[{"id":"x","bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button"}],
                      "do":[{"op":"track.capacity",%s}],"ui":{"label":"X"}}]}
                    """;
            assertThrows(IllegalArgumentException.class, () -> RuleCompiler.compile("features:test",
                    RuleJson.parse(directed.formatted(operation)), RuleJson.parse(LegacyCatalogConverter.coreRecipes())), operation);
        }
    }

    @Test
    void projectionRejectsInvalidReserveDeclarations() throws Exception {
        ObjectNode valid = (ObjectNode) RuleJson.parse(Files.readString(Path.of("src", "main", "resources",
                "dmmt", "online", "automation", "daggerheart-projection.json")));
        SheetProjection.parse(valid.toString());
        for (String mutation : List.of("missing-counter", "readonly-counter", "overlapping-counter", "unknown-capacity",
                "readonly-value", "overlapping-value", "writable-total", "unmapped-total", "overlapping-total", "adjusted-total")) {
            ObjectNode spec = valid.deepCopy();
            ObjectNode hope = spec.withObject("/actor/hope");
            switch (mutation) {
                case "missing-counter" -> hope.withObject("/capacityReserve").put("counter", "missing");
                case "readonly-counter" -> spec.withObject("/actor/scars").put("writable", false);
                case "overlapping-counter" -> spec.withObject("/actor/scars").put("path", "hope.slots");
                case "unknown-capacity" -> hope.withObject("/capacityReserve").put("capacity", "slots");
                case "readonly-value" -> hope.putArray("writable").add("max");
                case "overlapping-value" -> hope.withObject("/fields").put("value", "slots");
                case "writable-total" -> hope.withArray("writable").add("total");
                case "unmapped-total" -> hope.withObject("/capacityReserve").put("total", "missing");
                case "overlapping-total" -> hope.withObject("/fields").put("total", "slots");
                case "adjusted-total" -> hope.putObject("atLeast").put("total", "max");
                default -> throw new AssertionError(mutation);
            }
            assertThrows(IllegalArgumentException.class, () -> SheetProjection.parse(spec.toString()), mutation);
        }
    }
}
