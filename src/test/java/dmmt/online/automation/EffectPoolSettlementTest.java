package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static dmmt.online.automation.RuleConformanceTest.fixture;
import static org.junit.jupiter.api.Assertions.*;

class EffectPoolSettlementTest {
    private static final AutomationHost HOST = AutomationHost.bundled();
    private static final String OWNER = "classes:test:features:owner";
    private static final String SUPPORTER = "classes:test:features:supporter";
    private static final String REMOVER = "classes:test:features:remover";

    private static AutomationHost.Pack pack() throws Exception {
        String catalog = """
                {"format":"dmmap-daggerheart","version":2,"category":"classes","label":"Pool fixture","entries":[
                 {"id":"test","name":"Test","features":[
                  {"id":"owner","name":"Owner","automation":{
                   "pools":{"charge":{"kind":"number","scope":"actor","max":4,"whileEffect":"stance"}},
                   "effects":{"stance":{"label":"Stance","stacking":"stack",
                     "endWhen":{"eq":[{"read":"actor.hope.value"},0]}}},
                   "rules":[
                    {"id":"noop","bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button"}],
                     "do":[{"op":"value","value":0}],"ui":{"label":"Noop"}},
                    {"id":"open","bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button"}],
                     "do":[{"op":"effect.start","effect":"stance","target":"actor"}],"ui":{"label":"Open"}},
                    {"id":"end","bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button"}],
                     "do":[{"op":"effect.end","effect":"stance","target":"actor"}],"ui":{"label":"End"}},
                    {"id":"drain","bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button"}],
                     "do":[{"op":"resource.spend","pool":"actor.hope","amount":1}],"ui":{"label":"Drain"}},
                    {"id":"target-hope","bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button",
                      "targetSelection":{"cardinality":"one","scope":"activePlayerCharacters"}}],
                     "do":[{"op":"resource.gain","pool":"recipient.hope","amount":1}],"ui":{"label":"Hope"}},
                    {"id":"target-charge","bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button",
                      "targetSelection":{"cardinality":"one","scope":"activePlayerCharacters"}}],
                     "do":[{"op":"pool.gain","pool":"charge","owner":"recipient","amount":1}],"ui":{"label":"Charge"}}]}},
                  {"id":"supporter","name":"Supporter","automation":{
                   "pools":{"charge":{"kind":"number","scope":"actor","max":4,"whileEffect":"guard"}},
                   "effects":{"guard":{"label":"Guard","exclusiveGroup":"mode","stacking":"replaceAnySource"}},
                   "rules":[
                    {"id":"open","bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button"}],
                     "do":[{"op":"effect.start","effect":"guard","target":"actor"}],"ui":{"label":"Open"}},
                    {"id":"end","bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button"}],
                     "do":[{"op":"effect.end","effect":"guard","target":"actor"}],"ui":{"label":"End"}}]}},
                  {"id":"remover","name":"Remover","automation":{
                   "effects":{"other":{"label":"Other","exclusiveGroup":"mode"}},
                   "rules":[
                    {"id":"replace","bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button"}],
                     "do":[{"op":"effect.start","effect":"other","target":"actor"}],"ui":{"label":"Replace"}},
                    {"id":"remove","bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button"}],
                     "do":[{"op":"effect.endLabeled","label":"Guard","target":"actor"}],"ui":{"label":"Remove"}},
                    {"id":"target","bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button",
                      "targetSelection":{"cardinality":"one","scope":"activePlayerCharacters"}}],
                     "do":[{"op":"effect.endLabeled","label":"Guard","target":"recipient"}],"ui":{"label":"Remove target"}},
                    {"id":"target-all","bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button",
                      "targetSelection":{"cardinality":"all","scope":"activePlayerCharacters"}}],
                     "costs":[{"op":"resource.spend","pool":"actor.hope","amount":1}],
                     "do":[{"op":"effect.endLabeled","label":"Guard","target":"recipient"}],"ui":{"label":"Remove all"}},
                    {"id":"claim","bindings":[{"id":"chat","event":"claim.accept","surface":"chat","mode":"button",
                      "claimGroup":"clear","claimScope":{"recipients":"otherPlayersCharacters"}}],
                     "do":[{"op":"effect.endLabeled","label":"Guard","target":"recipient"}],"ui":{"label":"Claim"}}]}}
                 ]}]}""";
        return AutomationHost.compile("pool-fixture", Map.of("classes", catalog), fixture("rewrite/recipes").toString());
    }

    private static ObjectNode sheet() throws Exception {
        ObjectNode sheet = (ObjectNode) RuleJson.parse("""
                {"name":"Ada","hope":{"on":1,"slots":6},
                 "features":[
                  {"name":"Owner","automationSource":"classes:test:features:owner","instanceId":"owner-a"},
                  {"name":"Owner","automationSource":"classes:test:features:owner","instanceId":"owner-b"},
                  {"name":"Supporter","automationSource":"classes:test:features:supporter","instanceId":"supporter"},
                  {"name":"Remover","automationSource":"classes:test:features:remover","instanceId":"remover"}]}
                """);
        ObjectNode state = HOST.projection().state(sheet);
        state.putObject("pools").putObject("charge").put("value", 3).put("max", 4);
        state.putArray("effects");
        return sheet;
    }

    private static void effect(ObjectNode sheet, String id, String program, String definition, String source, String label) {
        ObjectNode effect = ((ArrayNode) sheet.at("/automationState/effects")).addObject().put("id", id)
                .put("effect", program + "#" + definition).put("sourceInstanceId", source).put("label", label);
        if ("guard".equals(definition)) effect.put("group", "mode");
        effect.putArray("until");
    }

    private static ObjectNode request(String id, long revision, String instance, String rule) {
        ObjectNode request = RuleJson.JSON.createObjectNode().put("requestId", id).put("expectedRevision", revision);
        request.putArray("selections").addObject().put("sourceInstanceId", instance).put("ruleId", rule).put("bindingId", "sheet");
        return request;
    }

    private static void execute(AutomationHost.Pack pack, ObjectNode sheet, String id, long revision, String instance, String rule) {
        HOST.execute(pack, sheet, AutomationHost.event("action.request", "ada", 0), request(id, revision, instance, rule),
                "self", StructuredRollTest.noRandom());
    }

    @Test
    void actorPoolRemainsUntilTheLastCopyAcrossInstancesAndProgramsEnds() throws Exception {
        var pack = pack();
        ObjectNode sheet = sheet();
        effect(sheet, "stance-b-1", OWNER, "stance", "owner-b", "Stance");
        effect(sheet, "stance-b-2", OWNER, "stance", "owner-b", "Stance");
        effect(sheet, "guard", SUPPORTER, "guard", "supporter", "Guard");
        execute(pack, sheet, "unrelated", 0, "owner-a", "noop");
        assertEquals(3, sheet.at("/automationState/pools/charge/value").asInt());
        execute(pack, sheet, "end-guard", 1, "supporter", "end");
        assertEquals(3, sheet.at("/automationState/pools/charge/value").asInt());
        execute(pack, sheet, "end-stack", 2, "owner-b", "end");
        assertEquals(0, sheet.at("/automationState/pools/charge/value").asInt());
        assertEquals(0, sheet.at("/automationState/effects").size());
    }

    @Test
    void realStackedStartsAcrossInstancesKeepThePoolUntilEachSourceEnds() throws Exception {
        var pack = pack();
        ObjectNode sheet = sheet();
        execute(pack, sheet, "open-a-1", 0, "owner-a", "open");
        execute(pack, sheet, "open-a-2", 1, "owner-a", "open");
        execute(pack, sheet, "open-b", 2, "owner-b", "open");
        assertEquals(3, sheet.at("/automationState/effects").size());
        assertEquals(3, sheet.at("/automationState/pools/charge/value").asInt());
        execute(pack, sheet, "end-a", 3, "owner-a", "end");
        assertEquals(1, sheet.at("/automationState/effects").size());
        assertEquals(3, sheet.at("/automationState/pools/charge/value").asInt());
        execute(pack, sheet, "end-b", 4, "owner-b", "end");
        assertEquals(0, sheet.at("/automationState/effects").size());
        assertEquals(0, sheet.at("/automationState/pools/charge/value").asInt());
    }

    @Test
    void labelEndUsesTheWholePackEvenWhenTheRemovedEffectSourceIsNotOnTheSheet() throws Exception {
        var pack = pack();
        ObjectNode sheet = sheet();
        sheet.withArray("features").remove(2);
        effect(sheet, "guard", SUPPORTER, "guard", "other-instance", "Guard");
        execute(pack, sheet, "remove", 0, "remover", "remove");
        assertEquals(0, sheet.at("/automationState/pools/charge/value").asInt());
        assertEquals(0, sheet.at("/automationState/effects").size());
    }

    @Test
    void replacingAStanceKeepsThePoolOnlyWhenTheReplacementSupportsIt() throws Exception {
        var pack = pack();
        ObjectNode sheet = sheet();
        effect(sheet, "old", SUPPORTER, "guard", "other-instance", "Guard");
        execute(pack, sheet, "supported", 0, "supporter", "open");
        assertEquals(3, sheet.at("/automationState/pools/charge/value").asInt());
        assertEquals("Guard", sheet.at("/automationState/effects/0/label").asText());
        execute(pack, sheet, "unsupported", 1, "remover", "replace");
        assertEquals(0, sheet.at("/automationState/pools/charge/value").asInt());
        assertEquals("Other", sheet.at("/automationState/effects/0/label").asText());
    }

    @Test
    void conditionalEndingPreservesAnotherProgramAndClearsTheLastSupport() throws Exception {
        var pack = pack();
        ObjectNode sheet = sheet();
        effect(sheet, "stance", OWNER, "stance", "owner-a", "Stance");
        effect(sheet, "guard", SUPPORTER, "guard", "supporter", "Guard");
        execute(pack, sheet, "drain", 0, "owner-a", "drain");
        assertEquals(0, sheet.at("/hope/on").asInt());
        assertEquals(3, sheet.at("/automationState/pools/charge/value").asInt());
        execute(pack, sheet, "end-guard", 1, "supporter", "end");
        assertEquals(0, sheet.at("/automationState/pools/charge/value").asInt());
    }

    private static ObjectNode holder(String id, boolean stance, boolean guard) throws Exception {
        ObjectNode holder = (ObjectNode) RuleJson.parse("""
                {"id":"%s","hope":{"value":1,"max":6},"pools":{"charge":{"value":3,"max":4}},"effects":[]}
                """.formatted(id));
        if (stance) holder.withArray("effects").addObject().put("id", "stance").put("effect", OWNER + "#stance")
                .put("sourceInstanceId", "owner").put("label", "Stance");
        if (guard) holder.withArray("effects").addObject().put("id", "guard").put("effect", SUPPORTER + "#guard")
                .put("sourceInstanceId", "supporter").put("label", "Guard");
        return holder;
    }

    private static RuleDispatcher.Candidate candidate(AutomationHost.Pack pack, String program, String instance,
                                                       ObjectNode actor, String event) {
        ObjectNode context = RuleJson.JSON.createObjectNode().put("revision", 0);
        context.putObject("source").put("id", program).put("instanceId", instance).put("name", "Source");
        context.set("actor", actor);
        context.putObject("event").put("type", event);
        return new RuleDispatcher.Candidate(pack.programs().get(program), context);
    }

    private static RuleDispatcher.Candidate remover(AutomationHost.Pack pack, ObjectNode actor, String event) {
        return candidate(pack, REMOVER, "remover", actor, event);
    }

    @Test
    void isolatedExecutionAndPreviewRequireDefinitionsForPotentialSupport() throws Exception {
        var pack = pack();
        ObjectNode actor = holder("ada", false, true);
        var owner = candidate(pack, OWNER, "owner", actor, "action.request");
        ObjectNode original = owner.context();
        var intent = new RuleRuntime.Intent("noop", "sheet", "owner", 0, RuleJson.JSON.createObjectNode());
        var program = pack.programs().get(OWNER);
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> RuleRuntime.execute(program, intent, original, StructuredRollTest.noRandom()));
        assertTrue(missing.getMessage().contains(SUPPORTER + "#guard"), missing.getMessage());
        assertTrue(missing.getMessage().contains("complete compiled program registry"), missing.getMessage());
        assertThrows(IllegalArgumentException.class, () -> RuleRuntime.preview(program, intent, original));
        assertThrows(IllegalArgumentException.class,
                () -> RuleDispatcher.executeEvent(List.of(owner), List.of(intent), StructuredRollTest.noRandom()));
        assertEquals(3, original.at("/actor/pools/charge/value").asInt());
        assertEquals(3, RuleRuntime.execute(program, intent, original, StructuredRollTest.noRandom(), pack.programs())
                .context().at("/actor/pools/charge/value").asInt());
        assertEquals(3, RuleRuntime.preview(program, intent, original, pack.programs())
                .context().at("/actor/pools/charge/value").asInt());
        assertEquals(3, RuleDispatcher.executeEvent(List.of(owner), List.of(intent),
                StructuredRollTest.noRandom(), pack.programs()).contexts().getFirst().at("/actor/pools/charge/value").asInt());
        assertThrows(IllegalArgumentException.class, () -> RuleRuntime.execute(program, intent, original,
                StructuredRollTest.noRandom(), Map.of(OWNER, program, SUPPORTER, program)));
        assertEquals(owner.context(), original);
    }

    @Test
    void unknownDefinitionsCannotBeRemovedOrUsedToJustifyAProspectiveClear() throws Exception {
        var pack = pack();
        for (String definition : List.of("classes:missing:features:source#guard", OWNER + "#missing", "")) {
            ObjectNode sheet = sheet();
            effect(sheet, "unknown", "placeholder", "guard", "absent", "Guard");
            ObjectNode instance = (ObjectNode) sheet.at("/automationState/effects/0");
            if (definition.isEmpty()) instance.remove("effect"); else instance.put("effect", definition);
            ObjectNode before = sheet.deepCopy();
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> execute(pack, sheet, "remove", 0, "remover", "remove"));
            assertTrue(error.getMessage().contains("unknown effect definition"), error.getMessage());
            assertEquals(before, sheet, "An unresolved removal must not spend or persist anything");

            error = assertThrows(IllegalArgumentException.class,
                    () -> execute(pack, sheet, "noop", 0, "owner-a", "noop"));
            assertTrue(error.getMessage().contains("unknown effect definition"), error.getMessage());
            assertEquals(before, sheet);
        }
    }

    @Test
    void unrelatedManualEffectsAndUnresolvableEffectsWithoutPoolsDoNotBlockActions() throws Exception {
        var pack = pack();
        ObjectNode sheet = sheet();
        ((ArrayNode) sheet.at("/automationState/effects")).addObject().put("id", "manual").put("label", "Manual");
        execute(pack, sheet, "noop", 0, "owner-a", "noop");
        assertEquals(0, sheet.at("/automationState/pools/charge/value").asInt());
        assertEquals("manual", sheet.at("/automationState/effects/0/id").asText());

        ObjectNode actor = holder("ada", false, false);
        actor.remove("pools");
        actor.withArray("effects").addObject().put("id", "unknown").put("effect", "missing#guard")
                .put("sourceInstanceId", "foreign").put("label", "Guard");
        var intent = new RuleRuntime.Intent("remove", "sheet", "remover", 0, RuleJson.JSON.createObjectNode());
        var result = RuleRuntime.execute(pack.programs().get(REMOVER), intent,
                remover(pack, actor, "action.request").context(), StructuredRollTest.noRandom(), pack.programs());
        assertTrue(result.context().at("/actor/effects").isEmpty());
    }

    @Test
    void targetedActionsLeaveUnrelatedRecipientPoolsAloneButReconcileTouchedPools() throws Exception {
        var pack = pack();
        ObjectNode actor = holder("ada", true, false), recipient = holder("mira", false, false);
        var owner = candidate(pack, OWNER, "owner", actor, "action.request");
        var recipients = List.of(new RuleDispatcher.RecipientCandidate("mira", recipient, 0));
        var hope = new RuleRuntime.Intent("target-hope", "sheet", "owner", 0, RuleJson.JSON.createObjectNode());
        var result = RuleDispatcher.executeTargets(List.of(owner), new RuleDispatcher.TargetIntent(hope, "mira", 0L),
                recipients, StructuredRollTest.noRandom(), pack.programs());
        assertEquals(2, result.recipients().getFirst().snapshot().at("/hope/value").asInt());
        assertEquals(3, result.recipients().getFirst().snapshot().at("/pools/charge/value").asInt());
        assertEquals(3, result.sourceContexts().getFirst().at("/actor/pools/charge/value").asInt());

        var charge = new RuleRuntime.Intent("target-charge", "sheet", "owner", 0, RuleJson.JSON.createObjectNode());
        result = RuleDispatcher.executeTargets(List.of(owner), new RuleDispatcher.TargetIntent(charge, "mira", 0L),
                recipients, StructuredRollTest.noRandom(), pack.programs());
        assertEquals(0, result.recipients().getFirst().snapshot().at("/pools/charge/value").asInt());
        ObjectNode supported = holder("mira", false, true);
        result = RuleDispatcher.executeTargets(List.of(owner), new RuleDispatcher.TargetIntent(charge, "mira", 0L),
                List.of(new RuleDispatcher.RecipientCandidate("mira", supported, 0)), StructuredRollTest.noRandom(), pack.programs());
        assertEquals(4, result.recipients().getFirst().snapshot().at("/pools/charge/value").asInt());

        ObjectNode unrelatedMalformed = holder("mira", false, false);
        ((ObjectNode) unrelatedMalformed.at("/pools/charge")).put("value", "bad");
        result = RuleDispatcher.executeTargets(List.of(owner), new RuleDispatcher.TargetIntent(hope, "mira", 0L),
                List.of(new RuleDispatcher.RecipientCandidate("mira", unrelatedMalformed, 0)),
                StructuredRollTest.noRandom(), pack.programs());
        assertEquals("bad", result.recipients().getFirst().snapshot().at("/pools/charge/value").asText());

        result = RuleDispatcher.executeTargets(List.of(owner), new RuleDispatcher.TargetIntent(charge, "ada", 0L),
                List.of(new RuleDispatcher.RecipientCandidate("ada", actor, 0)), StructuredRollTest.noRandom(), pack.programs());
        assertTrue(result.recipients().isEmpty());
        assertEquals(4, result.sourceContexts().getFirst().at("/actor/pools/charge/value").asInt());
        assertEquals(3, recipient.at("/pools/charge/value").asInt());
    }

    @Test
    void incompletePublicTargetAndClaimRegistriesRejectUnknownRemovedEffects() throws Exception {
        var pack = pack();
        ObjectNode actor = holder("ada", true, false), recipient = holder("mira", false, true);
        var target = new RuleRuntime.Intent("target", "sheet", "remover", 0, RuleJson.JSON.createObjectNode());
        var candidate = remover(pack, actor, "action.request");
        var selected = new RuleDispatcher.TargetIntent(target, "mira", 0L);
        var recipients = List.of(new RuleDispatcher.RecipientCandidate("mira", recipient, 0));
        assertThrows(IllegalArgumentException.class,
                () -> RuleDispatcher.executeTargets(List.of(candidate), selected, recipients, StructuredRollTest.noRandom()));
        assertEquals(0, RuleDispatcher.executeTargets(List.of(candidate), selected, recipients,
                StructuredRollTest.noRandom(), pack.programs()).recipients().getFirst().snapshot().at("/pools/charge/value").asInt());

        var claim = new RuleRuntime.Intent("claim", "chat", "remover", 0, RuleJson.JSON.createObjectNode());
        var claimIntent = List.of(new RuleDispatcher.ClaimIntent(claim, "mira", 0, 0));
        var ledger = new RuleDispatcher.ClaimLedger(0, Set.of());
        var claimCandidate = remover(pack, actor, "claim.accept");
        assertThrows(IllegalArgumentException.class, () -> RuleDispatcher.executeClaims(List.of(claimCandidate),
                claimIntent, recipients, ledger, StructuredRollTest.noRandom()));
        assertEquals(0, RuleDispatcher.executeClaims(List.of(claimCandidate), claimIntent, recipients, ledger,
                StructuredRollTest.noRandom(), pack.programs()).recipients().getFirst().snapshot().at("/pools/charge/value").asInt());
        assertEquals(0, ledger.revision());
        assertEquals(3, recipient.at("/pools/charge/value").asInt());
    }

    @Test
    void lateRecipientFailureRollsBackTheEntireFanOutAndSourceCost() throws Exception {
        var pack = pack();
        ObjectNode actor = holder("ada", true, false);
        ObjectNode mira = holder("mira", false, true);
        ObjectNode noor = holder("noor", false, true);
        ((ObjectNode) noor.at("/effects/0")).put("effect", "missing#guard");
        ObjectNode originalActor = actor.deepCopy(), originalMira = mira.deepCopy(), originalNoor = noor.deepCopy();
        var all = new RuleRuntime.Intent("target-all", "sheet", "remover", 0, RuleJson.JSON.createObjectNode());
        var candidate = remover(pack, actor, "action.request");
        var recipients = List.of(new RuleDispatcher.RecipientCandidate("mira", mira, 0),
                new RuleDispatcher.RecipientCandidate("noor", noor, 0));
        assertThrows(IllegalArgumentException.class, () -> RuleDispatcher.executeTargets(List.of(candidate),
                new RuleDispatcher.TargetIntent(all, null, null), recipients, StructuredRollTest.noRandom(), pack.programs()));
        assertEquals(originalActor, actor);
        assertEquals(originalActor, candidate.context().path("actor"));
        assertEquals(originalMira, mira);
        assertEquals(originalNoor, noor);
        assertEquals(originalMira, recipients.getFirst().snapshot());
    }

    @Test
    void malformedAffectedPoolsRejectExplicitEndingAndTimedExpiryAtomically() throws Exception {
        var pack = pack();
        for (String[] invalid : List.of(new String[]{"value", "\"broken\""}, new String[]{"value", "-1"},
                new String[]{"value", "5"}, new String[]{"value", "1.5"}, new String[]{"max", "\"broken\""},
                new String[]{"max", "-1"})) {
            ObjectNode sheet = sheet();
            sheet.withArray("features").remove(2);
            sheet.withArray("features").remove(1);
            sheet.withArray("features").remove(0);
            effect(sheet, "guard", SUPPORTER, "guard", "inactive", "Guard");
            ((ObjectNode) sheet.at("/automationState/pools/charge")).set(invalid[0], RuleJson.parse(invalid[1]));
            ObjectNode before = sheet.deepCopy();
            assertThrows(IllegalArgumentException.class, () -> execute(pack, sheet, "remove", 0, "remover", "remove"));
            assertEquals(before, sheet);

            ((ArrayNode) sheet.at("/automationState/effects/0/until")).add("scene.end");
            before = sheet.deepCopy();
            assertThrows(IllegalArgumentException.class,
                    () -> HOST.resetLimits(pack, sheet, AutomationHost.event("scene.end", "ada", 0)));
            assertEquals(before, sheet);
        }
    }

    @Test
    void expiryHandlesSharedSupportUnknownDefinitionsAndMissingSources() throws Exception {
        var pack = pack();
        ObjectNode sheet = sheet();
        sheet.withArray("features").remove(2);
        effect(sheet, "guard", SUPPORTER, "guard", "inactive", "Guard");
        effect(sheet, "stance", OWNER, "stance", "owner-b", "Stance");
        ((ArrayNode) sheet.at("/automationState/effects/0/until")).add("scene.end");
        ((ArrayNode) sheet.at("/automationState/effects/1/until")).add("session.start");
        assertTrue(HOST.resetLimits(pack, sheet, AutomationHost.event("scene.end", "ada", 0)));
        assertEquals(3, sheet.at("/automationState/pools/charge/value").asInt());
        assertTrue(HOST.resetLimits(pack, sheet, AutomationHost.event("session.start", "ada", 0)));
        assertEquals(0, sheet.at("/automationState/pools/charge/value").asInt());

        ObjectNode unknown = sheet();
        effect(unknown, "unknown", "missing", "guard", "inactive", "Guard");
        ((ArrayNode) unknown.at("/automationState/effects/0/until")).add("scene.end");
        ObjectNode before = unknown.deepCopy();
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> HOST.resetLimits(pack, unknown, AutomationHost.event("scene.end", "ada", 0)));
        assertTrue(failure.getMessage().contains("missing#guard"), failure.getMessage());
        assertEquals(before, unknown);
    }

    @Test
    void everyEqualValuedPoolBoundToTheSameEffectIsCleared() throws Exception {
        ObjectNode automation = (ObjectNode) RuleJson.parse("""
                {"pools":{"first":{"kind":"number","scope":"actor","max":4,"whileEffect":"stance"},
                          "second":{"kind":"number","scope":"actor","max":4,"whileEffect":"stance"}},
                 "effects":{"stance":{"label":"Stance"}},
                 "rules":[{"id":"end","bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button"}],
                           "do":[{"op":"effect.end","effect":"stance","target":"actor"}],"ui":{"label":"End"}}]}
                """);
        var program = RuleCompiler.compile("features:two-pools", automation, fixture("rewrite/recipes"));
        ObjectNode context = (ObjectNode) RuleJson.parse("""
                {"source":{"id":"features:two-pools","instanceId":"source","name":"Source"},
                 "actor":{"pools":{"first":{"value":3,"max":4},"second":{"value":3,"max":4}},
                          "effects":[{"id":"stance","effect":"features:two-pools#stance","sourceInstanceId":"source"}]},
                 "event":{"type":"action.request"},"revision":0}
                """);
        var result = RuleRuntime.execute(program, new RuleRuntime.Intent("end", "sheet", "source", 0,
                RuleJson.JSON.createObjectNode()), context, StructuredRollTest.noRandom());
        assertEquals(0, result.context().at("/actor/pools/first/value").asInt());
        assertEquals(0, result.context().at("/actor/pools/second/value").asInt());
    }

    @Test
    void targetAndClaimActionsReconcileRecipientPoolsWithoutChangingTheSource() throws Exception {
        var pack = pack();
        ObjectNode actor = holder("ada", true, false), recipient = holder("mira", false, true);
        var candidate = remover(pack, actor, "action.request");
        var action = new RuleRuntime.Intent("target", "sheet", "remover", 0, RuleJson.JSON.createObjectNode());
        var result = RuleDispatcher.executeTargets(List.of(candidate), new RuleDispatcher.TargetIntent(action, "mira", 0L),
                List.of(new RuleDispatcher.RecipientCandidate("mira", recipient, 0)), StructuredRollTest.noRandom(), pack.programs());
        assertEquals(3, result.sourceContexts().getFirst().at("/actor/pools/charge/value").asInt());
        assertEquals(0, result.recipients().getFirst().snapshot().at("/pools/charge/value").asInt());
        assertEquals(3, recipient.at("/pools/charge/value").asInt(), "The caller's snapshot is not mutated");

        ObjectNode supported = holder("mira", true, true);
        result = RuleDispatcher.executeTargets(List.of(candidate), new RuleDispatcher.TargetIntent(action, "mira", 0L),
                List.of(new RuleDispatcher.RecipientCandidate("mira", supported, 0)), StructuredRollTest.noRandom(), pack.programs());
        assertEquals(3, result.recipients().getFirst().snapshot().at("/pools/charge/value").asInt());

        var claimCandidate = remover(pack, actor, "claim.accept");
        var claimAction = new RuleRuntime.Intent("claim", "chat", "remover", 0, RuleJson.JSON.createObjectNode());
        var claim = RuleDispatcher.executeClaims(List.of(claimCandidate),
                List.of(new RuleDispatcher.ClaimIntent(claimAction, "mira", 0, 0)),
                List.of(new RuleDispatcher.RecipientCandidate("mira", recipient, 0)),
                new RuleDispatcher.ClaimLedger(0, Set.of()), StructuredRollTest.noRandom(), pack.programs());
        assertEquals(0, claim.recipients().getFirst().snapshot().at("/pools/charge/value").asInt());
    }

    @Test
    void selfTargetUsesOneHolderAndReconcilesOnlyOnce() throws Exception {
        var pack = pack();
        ObjectNode actor = holder("ada", false, true);
        var action = new RuleRuntime.Intent("target", "sheet", "remover", 0, RuleJson.JSON.createObjectNode());
        var result = RuleDispatcher.executeTargets(List.of(remover(pack, actor, "action.request")),
                new RuleDispatcher.TargetIntent(action, "ada", 0L),
                List.of(new RuleDispatcher.RecipientCandidate("ada", actor, 0)), StructuredRollTest.noRandom(), pack.programs());
        assertEquals(0, result.sourceContexts().getFirst().at("/actor/pools/charge/value").asInt());
        assertTrue(result.recipients().isEmpty());
    }
}
