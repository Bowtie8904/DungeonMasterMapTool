package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RestTargetTest {
    private static final AutomationHost HOST = AutomationHost.bundled();

    private static ObjectNode ruleset() throws Exception {
        return (ObjectNode) RuleJson.parse("""
                {"format":"dmmap-daggerheart-ruleset","version":2,"name":"Target rest",
                 "rest":{"startFacts":{"hope":"hope.value"},
                         "short":{"moves":2,"maxMoves":2,"maxLongMoves":0,"consecutiveLimit":3},
                         "long":{"moves":2,"maxMoves":2}},
                 "automation":{"effects":{"ward":{"label":"Ward","locks":{"hp":1}}},"rules":[
                   {"id":"observe","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"automatic"}],
                    "when":{"and":[{"eq":[{"read":"event.move.ally"},true]},{"gt":[{"read":"recipient.hp.marked"},5]}]},
                    "do":[{"op":"plan.adjust","plan":"event.move","field":"bonus","by":2,"label":"Observed"}],
                    "after":{"do":[{"op":"track.clear","track":"actor.stress","amount":{"read":"recipient.hp.marked"}}],
                             "report":"{recipient.name} now has {recipient.hp.marked} HP marked."},
                    "ui":{"label":"Observe"}},
                   {"id":"care","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"option"}],
                    "costs":[{"op":"resource.spend","pool":"actor.hope","amount":1}],
                    "do":[{"op":"plan.adjust","plan":"event.move","field":"bonus","by":1,"label":"Care"}],
                    "after":{"costs":[{"op":"resource.spend","pool":"actor.hope","amount":2}],
                             "report":"Care for {recipient.name}."},"ui":{"label":"Care"}},
                   {"id":"heal","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"button",
                     "restMove":{"id":"mend","kind":"any","ally":true},
                     "targetSelection":{"cardinality":"one","scope":"activeCharacters","excludeSource":true,
                       "where":{"gt":[{"read":"recipient.hp.marked"},0]}}}],
                    "costs":[{"op":"resource.spend","pool":"actor.hope","amount":1}],
                    "do":[{"op":"dice.roll","count":1,"sides":4,"as":"die"},
                          {"op":"track.clear","track":"recipient.hp",
                           "amount":{"add":[{"read":"result.die"},{"read":"event.move.bonus"}]},"as":"clear"},
                          {"op":"countdown.start","value":2,"label":"Healing"}],
                    "ui":{"label":"Heal","report":"Healed {recipient.name} for {result.clear}."}},
                   {"id":"self","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"button",
                     "restMove":{"id":"mend","kind":"any"}}],
                    "do":[{"op":"track.clear","track":"actor.hp","amount":1}],"ui":{"label":"Self"}},
                   {"id":"repair","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"button",
                     "restMove":{"id":"repair","kind":"any","ally":true},
                     "targetSelection":{"cardinality":"one","scope":"activeCharacters","excludeSource":true}}],
                    "do":[{"op":"track.clear","track":"recipient.armorTrack","amount":1}],"ui":{"label":"Repair"}},
                   {"id":"brace","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"button",
                     "restMove":{"id":"brace","kind":"any"}}],"ui":{"label":"Brace"}}
                 ]}}
                """);
    }

    private static AutomationHost.Pack pack(ObjectNode ruleset) {
        return AutomationHost.compile("target-rest", Map.of(), LegacyCatalogConverter.coreRecipes(), ruleset.toString());
    }

    private static ObjectNode sheet(String name, int hp, int hope) throws Exception {
        return (ObjectNode) RuleJson.parse("""
                {"name":"%s","permanentHp":0,"hope":{"slots":6,"on":%d},"hp":{"slots":6,"on":%d},
                 "stress":{"slots":6,"on":3},"armorTrack":{"slots":3,"on":2}}
                """.formatted(name, hope, hp));
    }

    private static ObjectNode choice(String rule) {
        return RuleJson.JSON.createObjectNode().put("sourceInstanceId", "ruleset:base")
                .put("ruleId", rule).put("bindingId", "move");
    }

    private static ArrayNode options(String... rules) {
        ArrayNode result = RuleJson.JSON.createArrayNode();
        for (String rule : rules) result.add(choice(rule));
        return result;
    }

    private record Setup(AutomationHost.Pack pack, ObjectNode source, ObjectNode recipient,
                         ObjectNode healthy, ObjectNode preparation, AutomationHost.RestProgress progress) {}

    private static Setup setup(ObjectNode ruleset, int hope) throws Exception {
        var pack = pack(ruleset);
        ObjectNode source = sheet("Ada", 6, hope);
        ObjectNode recipient = sheet("Mira", 6, 2).put("permanentHp", 2);
        recipient.putArray("effects").addObject().put("lockedHp", 1);
        ObjectNode state = HOST.projection().state(recipient).put("revision", 7);
        ObjectNode effect = state.putArray("effects").addObject().put("id", "ward").put("label", "Ward")
                .put("effect", "ruleset:base#ward").put("sourceInstanceId", "ruleset:base")
                .put("sourceActorId", "mira");
        effect.putObject("locks").put("hp", 1);
        ObjectNode healthy = sheet("Healthy", 0, 2);
        HOST.projection().state(healthy).put("revision", 2);
        ObjectNode preparation = HOST.prepareRest(pack, source,
                AutomationHost.event("action.request", "ada", 1).put("sessionId", "session"),
                "short", 0, options(), StructuredRollTest.noRandom());
        return new Setup(pack, source, recipient, healthy, preparation, HOST.restProgress(pack, preparation));
    }

    private static PartyTargets party(Setup setup, ObjectNode recipient, AtomicReference<String> scope) {
        List<PartyTargets.Member> members = new ArrayList<>();
        for (var entry : Map.of("ada", setup.source(), "mira", recipient, "healthy", setup.healthy()).entrySet()) {
            String id = entry.getKey();
            ObjectNode sheet = entry.getValue();
            members.add(new PartyTargets.Member(id, sheet.path("name").asText(), sheet.toString(), HOST.revision(sheet),
                    event -> HOST.recipientSnapshot(setup.pack(), sheet, event, id)));
        }
        return new PartyTargets(setup.pack().revision(), setup.source().toString(), selected -> {
            scope.set(selected);
            return members;
        });
    }

    private static ObjectNode targetRequest(Setup setup, PartyTargets party, String rule) {
        ObjectNode event = ((ObjectNode) setup.preparation().get("event")).deepCopy().put("type", "downtime.move");
        event.putObject("move").put("id", "mend").put("ally", true).put("long", false).put("slots", 1).put("bonus", 0);
        ObjectNode context = RuleJson.JSON.createObjectNode();
        context.set("actor", HOST.recipientSnapshot(setup.pack(), setup.source(), event, "ada"));
        context.putObject("source").put("instanceId", "ruleset:base");
        context.set("event", event);
        JsonNode binding = setup.pack().programs().get("ruleset:base").rule(rule).path("bindings").get(0);
        JsonNode offer = party.describe(binding, rule, context);
        JsonNode option = null;
        for (JsonNode candidate : offer.path("options")) if ("mira".equals(candidate.path("id").asText())) option = candidate;
        assertNotNull(option, offer.toString());
        ObjectNode request = RuleJson.JSON.createObjectNode().put("recipientId", "mira");
        request.set("version", option.get("version"));
        return request;
    }

    private static AutomationHost.RestMoveResult move(Setup setup, AutomationHost.RestProgress progress,
                                                       String rule, ArrayNode choices, PartyTargets party,
                                                       ObjectNode target, int... faces) {
        return HOST.restMove(setup.pack(), setup.source(), (ObjectNode) setup.preparation().get("event"),
                progress, choice(rule), choices, faces.length == 0 ? StructuredRollTest.noRandom() : StructuredRollTest.faces(faces),
                party == null ? null : new AutomationHost.RestTarget(party, target));
    }

    @Test
    void allyMoveUsesVersionedHostRecipientAndPostMoveDeferredFacts() throws Exception {
        Setup setup = setup(ruleset(), 4);
        AtomicReference<String> loadedScope = new AtomicReference<>();
        PartyTargets party = party(setup, setup.recipient(), loadedScope);
        ObjectNode target = targetRequest(setup, party, "heal");
        ObjectNode beforeRecipient = setup.recipient().deepCopy();
        var result = move(setup, setup.progress(), "heal", options("care"), party, target, 4);

        assertEquals("activeCharacters", loadedScope.get());
        assertEquals(0, setup.source().at("/hope/on").asInt());
        assertEquals(0, setup.source().at("/stress/on").asInt(), "Deferred body reads the healed recipient.");
        assertEquals(beforeRecipient, setup.recipient(), "The recipient sheet is not written until the caller applies the update.");
        assertEquals(1, party.updates().size());
        var update = party.updates().getFirst();
        assertEquals("mira", update.actorId());
        assertEquals(8, update.revision());
        assertEquals(4, update.snapshot().at("/hp/marked").asInt(), "The recipient's permanent and both temporary locks apply.");
        assertEquals(List.of("observe", "care", "heal", "observe", "care"), result.response().path("actions").findValuesAsText("ruleId"));
        assertEquals(3, result.response().at("/event/move/bonus").asInt());
        assertEquals("Healed Mira for 2.", result.response().at("/actions/2/report").asText());
        assertEquals("mira", result.response().at("/actions/2/recipientId").asText());
        assertEquals("Mira now has 4 HP marked.", result.response().at("/actions/3/report").asText());
        assertEquals("Care for Mira.", result.response().at("/actions/4/report").asText());
        assertEquals("Healing", result.response().at("/outbox/0/label").asText());
        assertEquals(1, result.progress().slotsUsed());
        HOST.writeRecipient(setup.recipient(), update);
        assertEquals(4, setup.recipient().at("/hp/on").asInt());
        assertEquals(8, HOST.revision(setup.recipient()));
    }

    @Test
    void missingStaleSelfIneligibleAndForgedTargetsFailWithoutStaging() throws Exception {
        Setup setup = setup(ruleset(), 4);
        PartyTargets party = party(setup, setup.recipient(), new AtomicReference<>());
        ObjectNode target = targetRequest(setup, party, "heal");
        ObjectNode beforeSource = setup.source().deepCopy(), beforeRecipient = setup.recipient().deepCopy();
        assertEquals("restTarget", assertThrows(RuleRuntime.Unavailable.class,
                () -> move(setup, setup.progress(), "heal", options(), null, null)).code());
        assertThrows(IllegalArgumentException.class,
                () -> move(setup, setup.progress(), "self", options(), party, target));
        for (String id : List.of("ada", "healthy", "offline-missing")) {
            ObjectNode forged = target.deepCopy().put("recipientId", id);
            assertEquals("staleRecipient", assertThrows(RuleRuntime.Unavailable.class,
                    () -> move(setup, setup.progress(), "heal", options(), party, forged)).code());
        }
        ObjectNode malformed = target.deepCopy().put("extra", true);
        assertThrows(IllegalArgumentException.class, () -> move(setup, setup.progress(), "heal", options(), party, malformed));

        ObjectNode changed = setup.recipient().deepCopy();
        changed.withObject("/hope").put("on", 3);
        PartyTargets changedParty = party(setup, changed, new AtomicReference<>());
        assertEquals("staleRecipient", assertThrows(RuleRuntime.Unavailable.class,
                () -> move(setup, setup.progress(), "heal", options(), changedParty, target)).code());
        assertEquals(beforeSource, setup.source());
        assertEquals(beforeRecipient, setup.recipient());
        assertTrue(party.updates().isEmpty());
        assertTrue(changedParty.updates().isEmpty());
        assertEquals(0, setup.progress().count());
    }

    @Test
    void deferredFailureRollsBackTheSourceAndNeverStagesTheRecipient() throws Exception {
        Setup setup = setup(ruleset(), 3);
        PartyTargets party = party(setup, setup.recipient(), new AtomicReference<>());
        ObjectNode target = targetRequest(setup, party, "heal");
        ObjectNode beforeSource = setup.source().deepCopy(), beforeRecipient = setup.recipient().deepCopy();
        assertEquals("cost", assertThrows(RuleRuntime.Unavailable.class,
                () -> move(setup, setup.progress(), "heal", options("care"), party, target, 4)).code());
        assertEquals(beforeSource, setup.source());
        assertEquals(beforeRecipient, setup.recipient());
        assertTrue(party.updates().isEmpty());
        assertEquals(0, setup.progress().count());
    }

    @Test
    void targetEligibilityIsRecheckedAfterMoveAdjustmentsBeforeRolling() throws Exception {
        ObjectNode ruleset = ruleset();
        ((ObjectNode) ruleset.at("/automation/rules/2/bindings/0/targetSelection")).set("where", RuleJson.parse("""
                {"and":[{"gt":[{"read":"recipient.hp.marked"},0]},
                        {"eq":[{"read":"event.move.bonus"},0]}]}
                """));
        Setup setup = setup(ruleset, 4);
        PartyTargets party = party(setup, setup.recipient(), new AtomicReference<>());
        ObjectNode target = targetRequest(setup, party, "heal");
        ObjectNode beforeSource = setup.source().deepCopy(), beforeRecipient = setup.recipient().deepCopy();
        assertEquals("staleRecipient", assertThrows(RuleRuntime.Unavailable.class,
                () -> move(setup, setup.progress(), "heal", options(), party, target)).code());
        assertEquals(beforeSource, setup.source());
        assertEquals(beforeRecipient, setup.recipient());
        assertTrue(party.updates().isEmpty());
    }

    @Test
    void commandsRequiringSettlementAreRejectedAtEveryTargetedStage() throws Exception {
        for (String path : List.of("/automation/rules/0/do", "/automation/rules/2/do", "/automation/rules/0/after/do")) {
            ObjectNode ruleset = ruleset();
            ((ArrayNode) ruleset.at(path)).addObject().put("op", "table.gain").put("resource", "fear")
                    .put("amount", 1).put("label", "Fear");
            Setup setup = setup(ruleset, 4);
            PartyTargets party = party(setup, setup.recipient(), new AtomicReference<>());
            ObjectNode target = targetRequest(setup, party, "heal");
            ObjectNode beforeSource = setup.source().deepCopy(), beforeRecipient = setup.recipient().deepCopy();
            var failure = assertThrows(RuleRuntime.Unavailable.class,
                    () -> move(setup, setup.progress(), "heal", options(), party, target, 4), path);
            assertEquals("restCommand", failure.code());
            assertTrue(failure.getMessage().contains("table.gain"));
            assertEquals(beforeSource, setup.source());
            assertEquals(beforeRecipient, setup.recipient());
            assertTrue(party.updates().isEmpty());
        }
    }

    @Test
    void subsequentMovesRequireFreshRosterAndRetainTheAppliedRecipientUpdate() throws Exception {
        Setup setup = setup(ruleset(), 4);
        PartyTargets firstParty = party(setup, setup.recipient(), new AtomicReference<>());
        ObjectNode firstTarget = targetRequest(setup, firstParty, "heal");
        var first = move(setup, setup.progress(), "heal", options(), firstParty, firstTarget, 4);
        ObjectNode sourceAfterFirst = setup.source().deepCopy();
        assertEquals("duplicateMove", assertThrows(RuleRuntime.Unavailable.class,
                () -> move(setup, first.progress(), "self", options(), null, null)).code());
        assertEquals("restTarget", assertThrows(RuleRuntime.Unavailable.class,
                () -> move(setup, first.progress(), "repair", options(), firstParty, firstTarget)).code());
        assertEquals(sourceAfterFirst, setup.source());
        assertEquals(1, firstParty.updates().size());

        ObjectNode stagedRecipient = setup.recipient().deepCopy();
        HOST.writeRecipient(stagedRecipient, firstParty.updates().getFirst());
        PartyTargets secondParty = party(setup, stagedRecipient, new AtomicReference<>());
        ObjectNode secondTarget = targetRequest(setup, secondParty, "repair");
        var second = move(setup, first.progress(), "repair", options(), secondParty, secondTarget);
        HOST.writeRecipient(stagedRecipient, secondParty.updates().getFirst());
        assertEquals(4, stagedRecipient.at("/hp/on").asInt());
        assertEquals(1, stagedRecipient.at("/armorTrack/on").asInt());
        assertEquals(9, HOST.revision(stagedRecipient));
        assertEquals(2, second.progress().slotsUsed());
        ObjectNode beforeFull = setup.source().deepCopy();
        assertEquals("restMoves", assertThrows(RuleRuntime.Unavailable.class,
                () -> move(setup, second.progress(), "brace", options(), null, null)).code());
        assertEquals(beforeFull, setup.source());
        assertEquals(6, setup.recipient().at("/hp/on").asInt(), "Neither staged update writes the original recipient.");
    }

    @Test
    void compilerRestrictsTargetedDowntimeAndDefersOnlyUnresolvedRecipeFields() throws Exception {
        ObjectNode automation = (ObjectNode) ruleset().path("automation").deepCopy();
        assertDoesNotThrow(() -> RuleCompiler.compile("ruleset:test", automation,
                RuleJson.parse(LegacyCatalogConverter.coreRecipes())));
        String path = "/rules/2/bindings/0";
        for (String mutation : List.of("noMove", "notAlly", "all", "includesSelf", "missingExclusion", "option", "wrongEvent")) {
            ObjectNode invalid = automation.deepCopy();
            ObjectNode binding = (ObjectNode) invalid.at(path);
            switch (mutation) {
                case "noMove" -> binding.remove("restMove");
                case "notAlly" -> ((ObjectNode) binding.get("restMove")).put("ally", false);
                case "all" -> ((ObjectNode) binding.get("targetSelection")).put("cardinality", "all");
                case "includesSelf" -> ((ObjectNode) binding.get("targetSelection")).put("excludeSource", false);
                case "missingExclusion" -> ((ObjectNode) binding.get("targetSelection")).remove("excludeSource");
                case "option" -> binding.put("mode", "option");
                case "wrongEvent" -> binding.put("event", "rest.short").put("surface", "rest");
            }
            assertThrows(IllegalArgumentException.class, () -> RuleCompiler.compile("ruleset:test", invalid,
                    RuleJson.parse(LegacyCatalogConverter.coreRecipes())), mutation);
        }
        ObjectNode afterMutation = automation.deepCopy();
        ((ArrayNode) afterMutation.at("/rules/0/after/do")).addObject().put("op", "track.clear")
                .put("track", "recipient.hp").put("amount", 1);
        assertThrows(IllegalArgumentException.class, () -> RuleCompiler.compile("ruleset:test", afterMutation,
                RuleJson.parse(LegacyCatalogConverter.coreRecipes())));

        ObjectNode recipes = (ObjectNode) RuleJson.parse("""
                {"format":"dmmap-daggerheart-rules","version":2,"recipes":[
                 {"id":"test:ally","args":{"cardinality":{"type":"string","default":"all"}},"ui":{},"rules":[]}]}
                """);
        ObjectNode recipeRule = (ObjectNode) automation.at("/rules/2").deepCopy();
        ((ObjectNode) recipeRule.at("/bindings/0/targetSelection")).putObject("cardinality").put("$arg", "cardinality");
        ((ArrayNode) recipes.at("/recipes/0/rules")).add(recipeRule);
        String empty = "{\"format\":\"dmmap-daggerheart\",\"version\":2,\"category\":\"items\",\"entries\":[]}";
        String used = """
                {"format":"dmmap-daggerheart","version":2,"category":"items","entries":[
                 {"id":"kit","name":"Kit","automation":{"rules":[{"id":"heal","useRecipe":"test:ally"}]}}]}
                """;
        assertTrue(RuleCatalog.compile(empty, recipes.toString()).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(used, recipes.toString()));
        ((ObjectNode) recipes.at("/recipes/0/args/cardinality")).put("default", "one");
        assertDoesNotThrow(() -> RuleCatalog.compile(used, recipes.toString()));
        ((ObjectNode) recipes.at("/recipes/0/rules/0/bindings/0/targetSelection")).put("excludeSource", false);
        assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(empty, recipes.toString()));
    }
}
