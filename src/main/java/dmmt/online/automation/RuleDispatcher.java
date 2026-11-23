package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * Collects user-facing bindings from host-selected active source instances.
 * The caller remains responsible for source activation, authorization and commit dispatch.
 */
public final class RuleDispatcher {
    private static final Set<String> SURFACES = RuleCompiler.SURFACES;
    private static final int MAX_TRACK_REACTION_DEPTH = 16;
    private static final int MAX_TRACK_EVENTS = 128;
    private static final Set<String> TRACK_EVENT_FIELDS = Set.of("type", "actorId", "track", "change", "requested", "before", "after");

    public record Candidate(RuleCompiler.Program program, ObjectNode context) {
        public Candidate {
            if (program == null || context == null) throw new IllegalArgumentException("Program and context are required.");
            context = context.deepCopy();
        }

        @Override
        public ObjectNode context() { return context.deepCopy(); }

        String instanceId() { return context.path("source").path("instanceId").asText(); }
    }

    public record ClaimKey(String group, String recipientId) {
        public ClaimKey {
            RuleJson.id(RuleJson.JSON.getNodeFactory().textNode(group), "/claim/group");
            RuleJson.id(RuleJson.JSON.getNodeFactory().textNode(recipientId), "/claim/recipientId");
        }
    }

    /** Claims taken so far plus optional per-group claim limits recorded from {@code claim.offer} host requests. */
    public record ClaimLedger(long revision, Set<ClaimKey> claims, Map<String, Integer> limits) {
        public ClaimLedger {
            if (revision < 0) throw RuleJson.error("/claimRevision", "Expected a nonnegative claim revision.");
            claims = Set.copyOf(claims);
            limits = Map.copyOf(limits == null ? Map.of() : limits);
            for (Map.Entry<String, Integer> limit : limits.entrySet())
                if (limit.getValue() == null || limit.getValue() < 0)
                    throw RuleJson.error("/claimLimits/" + limit.getKey(), "Expected a nonnegative claim limit.");
        }
        public ClaimLedger(long revision, Set<ClaimKey> claims) { this(revision, claims, Map.of()); }
    }

    public record RecipientCandidate(String actorId, ObjectNode snapshot, long revision) {
        public RecipientCandidate {
            RuleJson.id(RuleJson.JSON.getNodeFactory().textNode(actorId), "/recipient/actorId");
            if (snapshot == null || revision < 0) throw RuleJson.error("/recipient", "Recipient snapshot and revision are required.");
            snapshot = snapshot.deepCopy();
        }

        @Override public ObjectNode snapshot() { return snapshot.deepCopy(); }
    }

    public record ClaimIntent(RuleRuntime.Intent action, String recipientId,
                              long expectedRecipientRevision, long expectedClaimRevision) {
        public ClaimIntent {
            if (action == null) throw RuleJson.error("/claim/action", "A claim action is required.");
            RuleJson.id(RuleJson.JSON.getNodeFactory().textNode(recipientId), "/claim/recipientId");
            if (expectedRecipientRevision < 0 || expectedClaimRevision < 0)
                throw RuleJson.error("/claim", "Expected nonnegative recipient and claim revisions.");
        }
    }

    public record TargetIntent(RuleRuntime.Intent action, String recipientId, Long expectedRecipientRevision) {
        public TargetIntent {
            if (action == null) throw RuleJson.error("/target/action", "A target action is required.");
            if (recipientId == null) {
                if (expectedRecipientRevision != null)
                    throw RuleJson.error("/target/expectedRecipientRevision", "A recipient revision requires a selected recipient.");
            } else {
                RuleJson.id(RuleJson.JSON.getNodeFactory().textNode(recipientId), "/target/recipientId");
                if (expectedRecipientRevision == null || expectedRecipientRevision < 0)
                    throw RuleJson.error("/target/expectedRecipientRevision", "A selected recipient requires a nonnegative revision.");
            }
        }
    }

    public record RecipientUpdate(String actorId, ObjectNode snapshot, long revision) {
        public RecipientUpdate {
            RuleJson.id(RuleJson.JSON.getNodeFactory().textNode(actorId), "/recipient/actorId");
            if (snapshot == null || revision < 0) throw RuleJson.error("/recipient", "Snapshot and revision are required.");
            snapshot = snapshot.deepCopy();
        }

        @Override public ObjectNode snapshot() { return snapshot.deepCopy(); }
    }

    public record ClaimActionResult(ActionResult action, String recipientId, String claimGroup) {}

    public record TargetActionResult(ActionResult action, String recipientId) {}

    public record TargetResolution(List<ObjectNode> sourceContexts, List<RecipientUpdate> recipients,
                                   List<TargetActionResult> actions, List<ObjectNode> outbox) {
        public TargetResolution {
            sourceContexts = copyNodes(sourceContexts);
            recipients = List.copyOf(recipients);
            actions = List.copyOf(actions);
            outbox = copyNodes(outbox);
        }

        @Override public List<ObjectNode> sourceContexts() { return copyNodes(sourceContexts); }
        @Override public List<ObjectNode> outbox() { return copyNodes(outbox); }
    }

    public record ClaimResolution(List<ObjectNode> sourceContexts, List<RecipientUpdate> recipients,
                                  ClaimLedger ledger, List<ClaimActionResult> actions, List<ObjectNode> outbox) {
        public ClaimResolution {
            sourceContexts = copyNodes(sourceContexts);
            recipients = List.copyOf(recipients);
            actions = List.copyOf(actions);
            outbox = copyNodes(outbox);
        }

        @Override public List<ObjectNode> sourceContexts() { return copyNodes(sourceContexts); }
        @Override public List<ObjectNode> outbox() { return copyNodes(outbox); }
    }

    public record ActionResult(String sourceInstanceId, String ruleId, String bindingId, String report,
                               List<ObjectNode> publishedRolls, List<ObjectNode> diceRolls) {
        public ActionResult {
            publishedRolls = copyNodes(publishedRolls);
            diceRolls = copyNodes(diceRolls);
        }

        @Override public List<ObjectNode> publishedRolls() { return copyNodes(publishedRolls); }
        @Override public List<ObjectNode> diceRolls() { return copyNodes(diceRolls); }
    }

    public record EventResolution(List<ObjectNode> contexts, List<ActionResult> actions, List<ObjectNode> outbox) {
        public EventResolution {
            contexts = copyNodes(contexts);
            actions = List.copyOf(actions);
            outbox = copyNodes(outbox);
        }

        @Override public List<ObjectNode> contexts() { return copyNodes(contexts); }
        @Override public List<ObjectNode> outbox() { return copyNodes(outbox); }
    }

    record OwnerTrackClear(ObjectNode actor, ObjectNode event, long revision, int requested, int cleared,
                           List<ObjectNode> contexts, List<ActionResult> actions, List<ObjectNode> outbox) {
        OwnerTrackClear {
            actor = actor.deepCopy();
            event = event.deepCopy();
            contexts = copyNodes(contexts);
            actions = List.copyOf(actions);
            outbox = copyNodes(outbox);
        }
        @Override public ObjectNode actor() { return actor.deepCopy(); }
        @Override public ObjectNode event() { return event.deepCopy(); }
        @Override public List<ObjectNode> contexts() { return copyNodes(contexts); }
        @Override public List<ObjectNode> outbox() { return copyNodes(outbox); }
    }

    private record Invocation(Candidate candidate, RuleRuntime.Intent intent, String mode, int priority,
                              String exclusiveGroup, boolean automatic, String recipientId, String claimGroup) {}
    private record ActionKey(String sourceInstanceId, String ruleId, String bindingId, String eachId, String recipientId) {}
    private record CheckedInvocation(Invocation invocation, RuleRuntime.Prepared prepared) {}
    private record PreparedClaim(RuleRuntime.Prepared prepared, String recipientId, String claimGroup) {}
    private record PreparedTarget(RuleRuntime.Prepared prepared, String recipientId) {}
    @FunctionalInterface
    interface OwnerProjection {
        ObjectNode project(ObjectNode staged, Map<String, ObjectNode> contexts, String operation,
                           ObjectNode previousActor, String target);
    }

    private static final class ReactionBudget {
        int depth;
        int events;
        final OwnerProjection projection;
        Map<String, ObjectNode> contexts;

        ReactionBudget() { this(null); }
        ReactionBudget(OwnerProjection projection) { this.projection = projection; }
    }

    private RuleDispatcher() {}

    public static ArrayNode describe(List<Candidate> activeSources, String surface) {
        return describe(activeSources, surface, audience -> true);
    }

    static ArrayNode describe(List<Candidate> activeSources, String surface, java.util.function.Predicate<String> visible) {
        return describe(activeSources, surface, visible, null);
    }

    static ArrayNode describe(List<Candidate> activeSources, String surface, java.util.function.Predicate<String> visible, PartyTargets party) {
        return describe(activeSources, surface, binding -> visible.test(binding.audience()), party, false);
    }

    static ArrayNode describeRollOptions(List<Candidate> sources, java.util.function.Predicate<String> visible) {
        return describe(sources, "roll", binding -> binding.option() && visible.test(binding.audience()), null, true);
    }

    private static ArrayNode describe(List<Candidate> activeSources, String surface,
                                      java.util.function.Predicate<RuleCompiler.Program.BindingRef> visible,
                                      PartyTargets party, boolean skipUnmatched) {
        if (!SURFACES.contains(surface)) throw RuleJson.error("/surface", "Unknown surface.");
        List<ObjectNode> descriptions = new ArrayList<>();
        Snapshot snapshot = validateSources(activeSources);
        if (RuleRuntime.isRestEvent(snapshot.eventType()))
            RuleRuntime.restPhase(snapshot.event());
        for (Candidate candidate : snapshot.sources()) {
            RuleCompiler.Program program = candidate.program();
            ObjectNode context = candidate.context();
            String eventType = snapshot.eventType();
            for (RuleCompiler.Program.BindingRef binding : program.bindings(eventType, surface)) {
                if (!visible.test(binding)) continue;
                if (binding.forEach())
                    RuleRuntime.describeEach(program, binding.ruleId(), binding.bindingId(), context, party)
                            .forEach(item -> descriptions.add((ObjectNode) item));
                else descriptions.add(RuleRuntime.describe(program, binding.ruleId(), binding.bindingId(), context, party));
            }
        }

        descriptions.sort(Comparator
                .comparing((ObjectNode item) -> item.path("sourceInstanceId").asText())
                .thenComparing(item -> item.path("sourceId").asText())
                .thenComparing(item -> item.path("ruleId").asText())
                .thenComparing(item -> item.path("bindingId").asText())
                .thenComparing(item -> item.path("eachId").asText()));
        ArrayNode result = RuleJson.JSON.createArrayNode();
        for (ObjectNode item : descriptions)
            if (!skipUnmatched || !"condition".equals(item.path("reasonCode").asText())) result.add(item);
        return result;
    }

    public static EventResolution executeEvent(List<Candidate> activeSources, List<RuleRuntime.Intent> selections,
                                               RandomGenerator random) {
        return executeEvent(activeSources, selections, random, programs(activeSources));
    }

    /** Supply the compiled programs referenced by effects, including sources no longer active on the sheet. */
    public static EventResolution executeEvent(List<Candidate> activeSources, List<RuleRuntime.Intent> selections,
                                               RandomGenerator random, Map<String, RuleCompiler.Program> programs) {
        return executeEvent(activeSources, selections, random, programs, false, null);
    }

    static EventResolution executeStagedMove(List<Candidate> activeSources, List<RuleRuntime.Intent> selections,
                                             RandomGenerator random, Map<String, RuleCompiler.Program> programs) {
        return executeEvent(activeSources, selections, random, programs, true, null);
    }

    static EventResolution executeOwnerTrackReactions(List<Candidate> activeSources, List<RuleRuntime.Intent> selections,
                                                      RandomGenerator random, Map<String, RuleCompiler.Program> programs) {
        return executeEvent(activeSources, selections, random, programs, false, new ReactionBudget());
    }

    static EventResolution executeSelectedWithOwnerReactions(List<Candidate> activeSources, RuleRuntime.Intent selection,
                                                            RandomGenerator random, Map<String, RuleCompiler.Program> programs) {
        if (selection == null) throw RuleJson.error("/selection", "A selected binding is required.");
        return executeEvent(activeSources, List.of(selection), random, programs, false, new ReactionBudget(), false);
    }

    static EventResolution executeDeferredWithOwnerReactions(List<Candidate> activeSources, RuleRuntime.Intent intent,
                                                            RandomGenerator random, Map<String, RuleCompiler.Program> programs) {
        if (intent == null) throw RuleJson.error("/selection", "A deferred binding is required.");
        if (programs == null) throw RuleJson.error("/programs", "Expected a compiled program registry.");
        Snapshot snapshot = validateSources(activeSources);
        Map<String, Candidate> byInstance = new HashMap<>();
        Map<String, ObjectNode> workingContexts = new HashMap<>();
        for (Candidate candidate : snapshot.sources()) {
            byInstance.put(candidate.instanceId(), candidate);
            workingContexts.put(candidate.instanceId(), candidate.context());
        }
        Candidate selected = byInstance.get(intent.sourceInstanceId());
        if (selected == null) throw new RuleRuntime.Unavailable("source", "This source is no longer active.");
        List<ActionResult> actions = new ArrayList<>();
        List<ObjectNode> outbox = new ArrayList<>();
        ReactionBudget budget = new ReactionBudget();
        budget.contexts = workingContexts;
        RuleRuntime.TrackObserver observer = ownerTrackObserver(
                snapshot, byInstance, workingContexts, random, programs, budget, actions, outbox);
        ObjectNode selectedContext = selected.context();
        selectedContext.set("actor", snapshot.actor().deepCopy());
        RuleRuntime.Resolution resolution = RuleRuntime.executeAfter(selected.program(), intent, selectedContext,
                random, programs, observer);
        ObjectNode completed = resolution.context();
        drainOutbox(completed, outbox);
        workingContexts.put(intent.sourceInstanceId(), completed);
        actions.add(new ActionResult(intent.sourceInstanceId(), intent.ruleId(), intent.bindingId(),
                resolution.report(), resolution.publishedRolls(), resolution.diceRolls()));
        List<ObjectNode> contexts = new ArrayList<>();
        for (String instanceId : workingContexts.keySet().stream().sorted().toList()) {
            ObjectNode context = workingContexts.get(instanceId).deepCopy();
            context.set("actor", completed.get("actor").deepCopy());
            context.set("event", completed.get("event").deepCopy());
            context.set("revision", RuleExpression.numeric(snapshot.revision() + 1d, "/revision"));
            contexts.add(context);
        }
        return new EventResolution(contexts, actions, outbox);
    }

    static OwnerTrackClear clearOwnerTrack(List<Candidate> activeSources, ObjectNode actor, ObjectNode event, long revision,
                                           String track, int requested, RandomGenerator random,
                                           Map<String, RuleCompiler.Program> programs) {
        return clearOwnerTrack(activeSources, actor, event, revision, track, requested, random, programs, null);
    }

    static OwnerTrackClear clearOwnerTrack(List<Candidate> activeSources, ObjectNode actor, ObjectNode event, long revision,
                                           String track, int requested, RandomGenerator random,
                                           Map<String, RuleCompiler.Program> programs, OwnerProjection projection) {
        if (actor == null || event == null || programs == null)
            throw RuleJson.error("/recovery", "Actor, event and compiled programs are required.");
        RuleJson.checkTree(actor, "/actor", 0, new int[]{0});
        RuleJson.checkTree(event, "/event", 0, new int[]{0});
        RuleJson.id(actor.path("id"), "/actor/id");
        RuleJson.text(event.get("type"), "/event/type");
        ObjectNode staged = RuleJson.JSON.createObjectNode().put("revision", revision);
        RuleRuntime.revision(staged);
        Snapshot snapshot = validateSources(activeSources);
        if (!snapshot.sources().isEmpty()) {
            if (!actor.equals(snapshot.originalActor()) || !event.equals(snapshot.event())
                    || snapshot.revision() != revision)
                throw new RuleRuntime.Unavailable("stale", "Recovery sources must match the holder and event snapshot.");
            staged.set("actor", snapshot.actor().deepCopy());
        } else staged.set("actor", actor.deepCopy());
        staged.set("event", event.deepCopy());
        Map<String, Candidate> byInstance = new HashMap<>();
        Map<String, ObjectNode> workingContexts = new HashMap<>();
        for (Candidate candidate : snapshot.sources()) {
            byInstance.put(candidate.instanceId(), candidate);
            workingContexts.put(candidate.instanceId(), candidate.context());
        }
        List<ActionResult> actions = new ArrayList<>();
        List<ObjectNode> outbox = new ArrayList<>();
        ReactionBudget budget = new ReactionBudget(projection);
        budget.contexts = workingContexts;
        RuleRuntime.TrackObserver observer = snapshot.sources().isEmpty() && projection == null ? null : ownerTrackObserver(
                snapshot, byInstance, workingContexts, random, programs, budget, actions, outbox);
        int cleared = RuleRuntime.clearOwnerTrack(staged, track, requested, observer);
        drainOutbox(staged, outbox);
        boolean changed = !RuleExpression.equal(actor, staged.get("actor")) || !actions.isEmpty() || !outbox.isEmpty()
                || !RuleExpression.equal(event, staged.get("event"));
        long nextRevision = changed ? RuleExpression.numeric(revision + 1d, "/revision").longValue() : revision;
        List<ObjectNode> updated = new ArrayList<>();
        for (Candidate candidate : snapshot.sources()) {
            ObjectNode context = workingContexts.get(candidate.instanceId()).deepCopy();
            context.set("actor", staged.get("actor").deepCopy());
            context.set("event", staged.get("event").deepCopy());
            context.put("revision", nextRevision);
            updated.add(context);
        }
        return new OwnerTrackClear((ObjectNode) staged.get("actor"), (ObjectNode) staged.get("event"),
                nextRevision, requested, cleared, updated, actions, outbox);
    }

    private static EventResolution executeEvent(List<Candidate> activeSources, List<RuleRuntime.Intent> selections,
                                                RandomGenerator random, Map<String, RuleCompiler.Program> programs,
                                                boolean stagedMove, ReactionBudget budget) {
        return executeEvent(activeSources, selections, random, programs, stagedMove, budget, true);
    }

    private static EventResolution executeEvent(List<Candidate> activeSources, List<RuleRuntime.Intent> selections,
                                                RandomGenerator random, Map<String, RuleCompiler.Program> programs,
                                                boolean stagedMove, ReactionBudget budget, boolean includeAutomatic) {
        Snapshot snapshot = validateSources(activeSources);
        if (RuleRuntime.isRestEvent(snapshot.eventType()))
            RuleRuntime.restPhase(snapshot.event());
        if (stagedMove && !"downtime.move".equals(snapshot.eventType()))
            throw RuleJson.error("/event/type", "Deferred benefits require a staged downtime move.");
        Map<String, Candidate> byInstance = new HashMap<>();
        Map<String, ObjectNode> workingContexts = new HashMap<>();
        for (Candidate candidate : snapshot.sources()) {
            String instanceId = candidate.context().path("source").path("instanceId").asText();
            byInstance.put(instanceId, candidate);
            workingContexts.put(instanceId, candidate.context());
        }
        if (budget != null && budget.contexts == null) budget.contexts = workingContexts;
        if (selections == null) throw RuleJson.error("/selections", "Expected selected bindings.");

        List<Invocation> invocations = new ArrayList<>();
        Set<ActionKey> selectedKeys = new HashSet<>();
        Set<String> exclusiveGroups = new HashSet<>();
        for (RuleRuntime.Intent intent : selections) {
            if (intent == null) throw RuleJson.error("/selections", "A selected binding is missing.");
            Candidate candidate = byInstance.get(intent.sourceInstanceId());
            if (candidate == null) throw RuleJson.error("/selections", "Selected source is no longer active.");
            ObjectNode rule = candidate.program().rule(intent.ruleId());
            JsonNode binding = binding(rule, intent.bindingId());
            String mode = binding.path("mode").asText();
            if (!Set.of("button", "option").contains(mode))
                throw RuleJson.error("/selections", "Only buttons and options may be selected.");
            if (binding.has("claimGroup"))
                throw RuleJson.error("/selections", "Claim-group actions require executeClaims.");
            if (binding.has("targetSelection"))
                throw RuleJson.error("/selections", "Targeted actions require executeTargets.");
            if (!snapshot.eventType().equals(binding.path("event").asText()))
                throw new RuleRuntime.Unavailable("event", "This action does not apply to this event.");
            long expectedRevision = RuleRuntime.revision(candidate.context());
            if (intent.expectedRevision() != expectedRevision)
                throw new RuleRuntime.Unavailable("stale", "This action is stale. Use its latest state.");
            ActionKey key = new ActionKey(intent.sourceInstanceId(), intent.ruleId(), intent.bindingId(), intent.eachId(), null);
            if (!selectedKeys.add(key)) throw RuleJson.error("/selections", "A binding cannot be selected more than once.");
            String exclusiveGroup = binding.path("exclusiveGroup").asText(null);
            if (exclusiveGroup != null && !exclusiveGroups.add(exclusiveGroup))
                throw RuleJson.error("/selections", "Only one option in each exclusive group may be selected.");
            invocations.add(new Invocation(candidate, intent, mode, binding.path("priority").asInt(),
                    exclusiveGroup, false, null, null));
        }
        if (includeAutomatic) for (Candidate candidate : snapshot.sources()) {
            RuleCompiler.Program program = candidate.program();
            var automatic = program.automatic(snapshot.eventType());
            if (automatic.isEmpty()) continue;
            ObjectNode context = candidate.context();
            for (var binding : automatic) {
                ObjectNode description = RuleRuntime.describe(program, binding.ruleId(), binding.bindingId(), context);
                if (!description.path("available").asBoolean()) continue;
                RuleRuntime.Intent intent = new RuleRuntime.Intent(binding.ruleId(), binding.bindingId(),
                        context.path("source").path("instanceId").asText(),
                        snapshot.revision(), RuleJson.JSON.createObjectNode());
                invocations.add(new Invocation(candidate, intent, "automatic", binding.priority(), null, true, null, null));
            }
        }

        invocations.sort(Comparator
                .comparing(Invocation::automatic).reversed()
                .thenComparing(Invocation::priority, Comparator.reverseOrder())
                .thenComparing(item -> item.intent().sourceInstanceId())
                .thenComparing(item -> item.intent().ruleId())
                .thenComparing(item -> item.intent().bindingId())
                .thenComparing(item -> item.intent().eachId() == null ? "" : item.intent().eachId()));

        List<CheckedInvocation> checked = new ArrayList<>();
        for (Invocation invocation : invocations) {
            ObjectNode context = invocation.candidate().context();
            context.set("actor", snapshot.actor().deepCopy());
            context.set("revision", RuleExpression.numeric(snapshot.revision(), "/revision"));
            try {
                checked.add(new CheckedInvocation(invocation,
                        RuleRuntime.preflight(invocation.candidate().program(), invocation.intent(), context)));
            } catch (RuleRuntime.Unavailable unavailable) {
                if (!invocation.automatic()) throw unavailable;
            }
        }

        List<ActionResult> results = new ArrayList<>();
        List<ObjectNode> outbox = new ArrayList<>();
        RuleRuntime.TrackObserver observer = budget == null ? null : ownerTrackObserver(
                snapshot, byInstance, workingContexts, random, programs, budget, results, outbox);
        List<RuleRuntime.Prepared> prepared = new ArrayList<>();
        ObjectNode sharedActor = snapshot.actor().deepCopy();
        ObjectNode sharedEvent = snapshot.event().deepCopy();
        for (CheckedInvocation item : checked) {
            Invocation invocation = item.invocation();
            String instanceId = invocation.intent().sourceInstanceId();
            ObjectNode context = workingContexts.get(instanceId).deepCopy();
            context.set("actor", sharedActor.deepCopy());
            if (observer != null) context.set("event", sharedEvent.deepCopy());
            context.set("revision", RuleExpression.numeric(snapshot.revision(), "/revision"));
            try {
                RuleRuntime.Prepared next = RuleRuntime.reserveCosts(
                        invocation.candidate().program(), item.prepared(), context, true, observer);
                prepared.add(next);
                workingContexts.put(instanceId, next.context().deepCopy());
                mergeReactionSource(budget, instanceId, next.context());
                sharedActor = next.context().path("actor").deepCopy();
                if (observer != null) sharedEvent = next.context().path("event").deepCopy();
            } catch (RuleRuntime.Unavailable unavailable) {
                if (!invocation.automatic()) throw unavailable;
            }
        }

        for (RuleRuntime.Prepared item : prepared) {
            String instanceId = item.intent().sourceInstanceId();
            ObjectNode current = item.context().deepCopy();
            mergeTransactionState(current, workingContexts.get(instanceId));
            current.set("actor", sharedActor.deepCopy());
            current.set("event", sharedEvent.deepCopy());
            current.set("revision", RuleExpression.numeric(snapshot.revision(), "/revision"));
            RuleRuntime.Prepared executable = new RuleRuntime.Prepared(item.intent(), current, snapshot.revision());
            RuleRuntime.Resolution resolution = RuleRuntime.executePrepared(
                    byInstance.get(instanceId).program(), executable, random, false, programs, stagedMove, observer);
            ObjectNode nextContext = resolution.context();
            if (observer == null) {
                for (JsonNode command : nextContext.path("outbox")) outbox.add((ObjectNode) command);
            } else drainOutbox(nextContext, outbox);
            sharedActor = nextContext.path("actor").deepCopy();
            if (nextContext.path("event").isObject()) sharedEvent = nextContext.get("event").deepCopy();
            nextContext.set("revision", RuleExpression.numeric(snapshot.revision(), "/revision"));
            workingContexts.put(instanceId, nextContext);
            mergeReactionSource(budget, instanceId, nextContext);
            results.add(new ActionResult(instanceId, item.intent().ruleId(), item.intent().bindingId(),
                    resolution.report(), resolution.publishedRolls(), resolution.diceRolls()));
        }

        List<String> instanceIds = new ArrayList<>(workingContexts.keySet());
        instanceIds.sort(String::compareTo);
        List<ObjectNode> updated = new ArrayList<>();
        for (String instanceId : instanceIds) {
            ObjectNode context = workingContexts.get(instanceId).deepCopy();
            context.set("actor", sharedActor.deepCopy());
            context.set("event", sharedEvent.deepCopy());
            if (!prepared.isEmpty()) context.set("revision", RuleExpression.numeric(snapshot.revision() + 1d, "/revision"));
            updated.add(context);
        }
        return new EventResolution(updated, results, outbox);
    }

    private static RuleRuntime.TrackObserver ownerTrackObserver(
            Snapshot snapshot, Map<String, Candidate> byInstance, Map<String, ObjectNode> workingContexts,
            RandomGenerator random, Map<String, RuleCompiler.Program> programs, ReactionBudget budget,
            List<ActionResult> results, List<ObjectNode> outbox) {
        List<String> relevant = byInstance.entrySet().stream()
                .filter(entry -> entry.getValue().program().hasComputedActorPools()
                        || !entry.getValue().program().automatic("track.changed").isEmpty())
                .map(Map.Entry::getKey).sorted().toList();
        return new RuleRuntime.TrackObserver() {
            private void reproject(ObjectNode staged, String operation, ObjectNode previousActor, String target) {
                if ("limits.refresh".equals(operation))
                    throw new RuleRuntime.Unavailable("reactionLimits",
                            "Refreshing other sources' use budgets requires host settlement before later reactions.");
                if (Set.of("limit.restore", "inventory.grant", "damage.deal").contains(operation))
                    throw new RuleRuntime.Unavailable("reactionCommand",
                            "This host request requires settlement before later reactions: " + operation + ".");
                String currentId = staged.path("source").path("instanceId").asText();
                if (!currentId.isEmpty()) {
                    mergeSourceState(workingContexts.get(currentId), staged);
                    mergeReactionSource(budget, currentId, staged);
                }
                if (budget.projection == null) {
                    if (operation.startsWith("effect.") || operation.startsWith("collection.")
                            || "inventory.consume".equals(operation))
                        throw new RuleRuntime.Unavailable("reactionSource",
                                "This reaction changes sources or effects and requires a host projection.");
                    if (byInstance.values().stream().anyMatch(candidate -> candidate.program().hasComputedSourcePools()))
                        throw new RuleRuntime.Unavailable("reactionSourcePool",
                                "A computed source pool cannot yet be settled during a track reaction.");
                    boolean computedMutation = (operation.startsWith("state.") || operation.startsWith("pool.")
                            || "roll.addPoolDie".equals(operation))
                            && byInstance.values().stream().anyMatch(candidate -> candidate.program().hasComputedActorPools());
                    if (computedMutation || staged.path("actor").has("stats") || hasDerivedEffects(staged.path("actor")))
                        throw new RuleRuntime.Unavailable("reactionProjection",
                                "This reaction requires the host to reproject the character.");
                    return;
                }
                boolean selfRecipient = staged.has("recipient")
                        && staged.at("/actor/id").equals(staged.at("/recipient/id"));
                ObjectNode projected = budget.projection.project(staged, budget.contexts, operation, previousActor, target);
                if (projected == null) throw RuleJson.error("/actor", "The host must return a projected character.");
                staged.set("actor", projected);
                if (selfRecipient) staged.set("recipient", projected);
                Candidate current = byInstance.get(currentId);
                if (current != null) RuleEffects.refreshCounts(current.program(), staged);
            }

            @Override
            public void changed(ObjectNode staged, String holder, ObjectNode event) {
                String actorId = staged.path("actor").path("id").asText();
                if (!actorId.equals(staged.path(holder).path("id").asText()))
                    throw new RuleRuntime.Unavailable("recipientReactions",
                            "A different recipient requires that character's own track reactions.");
                if (++budget.events > MAX_TRACK_EVENTS || budget.depth >= MAX_TRACK_REACTION_DEPTH)
                    throw new RuleRuntime.Unavailable("reactionLimit", "Track reactions exceeded the transaction limit.");
                if (!RuleExpression.equal(event.get("before"), event.get("after")) || budget.projection != null)
                    reproject(staged, "track.changed", null, holder);
                else {
                    String currentId = staged.path("source").path("instanceId").asText();
                    if (!currentId.isEmpty()) {
                        mergeSourceState(workingContexts.get(currentId), staged);
                        mergeReactionSource(budget, currentId, staged);
                    }
                }
                if (relevant.isEmpty()) return;
                drainOutbox(staged, outbox);
                String currentId = staged.path("source").path("instanceId").asText();
                List<Candidate> candidates = new ArrayList<>();
                for (String instanceId : relevant) {
                    ObjectNode context = workingContexts.get(instanceId).deepCopy();
                    context.set("actor", staged.get("actor").deepCopy());
                    context.set("event", event.deepCopy());
                    context.set("revision", RuleExpression.numeric(snapshot.revision(), "/revision"));
                    context.remove(List.of("recipient", "outbox"));
                    candidates.add(new Candidate(byInstance.get(instanceId).program(), context));
                }
                EventResolution reacted;
                budget.depth++;
                try {
                    reacted = executeEvent(candidates, List.of(), random, programs, false, budget);
                } finally {
                    budget.depth--;
                }
                List<ObjectNode> contexts = reacted.contexts();
                for (ObjectNode context : contexts) {
                    String instance = context.path("source").path("instanceId").asText();
                    mergeSourceState(workingContexts.get(instance), context);
                    mergeReactionSource(budget, instance, context);
                }
                if (!currentId.isEmpty()) mergeSourceState(staged, workingContexts.get(currentId));
                if (!contexts.isEmpty()) {
                    boolean selfRecipient = staged.has("recipient")
                            && actorId.equals(staged.path("recipient").path("id").asText());
                    staged.set("actor", contexts.getFirst().get("actor").deepCopy());
                    if (selfRecipient) staged.set("recipient", staged.get("actor"));
                    mergeReactionEvent((ObjectNode) staged.get("event"), contexts.getFirst().path("event"));
                }
                results.addAll(reacted.actions());
                outbox.addAll(reacted.outbox());
            }

            @Override
            public void mutated(ObjectNode staged, String operation, ObjectNode previousActor, String target) {
                reproject(staged, operation, previousActor, target);
            }
        };
    }

    private static boolean hasDerivedEffects(JsonNode actor) {
        for (JsonNode effect : actor.path("effects")) if (effect.path("derived").asBoolean()) return true;
        return false;
    }

    private static void mergeReactionEvent(ObjectNode parent, JsonNode nested) {
        nested.fields().forEachRemaining(field -> {
            if (!TRACK_EVENT_FIELDS.contains(field.getKey())) parent.set(field.getKey(), field.getValue().deepCopy());
        });
    }

    private static void mergeReactionSource(ReactionBudget budget, String instanceId, ObjectNode source) {
        if (budget == null || budget.contexts == null) return;
        ObjectNode target = budget.contexts.get(instanceId);
        if (target != null && target != source) mergeSourceState(target, source);
    }

    private static void mergeSourceState(ObjectNode target, ObjectNode source) {
        for (String field : List.of("source", "state", "usage", "sourcePools")) {
            if (source.has(field)) target.set(field, source.get(field).deepCopy());
            else target.remove(field);
        }
    }

    private static void drainOutbox(ObjectNode context, List<ObjectNode> outbox) {
        JsonNode pending = context.remove("outbox");
        if (pending != null) for (JsonNode command : pending) outbox.add(((ObjectNode) command).deepCopy());
    }

    public static ClaimResolution executeClaims(List<Candidate> activeSources, List<ClaimIntent> selections,
                                                List<RecipientCandidate> eligibleRecipients, ClaimLedger ledger,
                                                RandomGenerator random) {
        return executeClaims(activeSources, selections, eligibleRecipients, ledger, random, programs(activeSources));
    }

    /** Supply the compiled programs referenced by source and recipient effects. */
    public static ClaimResolution executeClaims(List<Candidate> activeSources, List<ClaimIntent> selections,
                                                List<RecipientCandidate> eligibleRecipients, ClaimLedger ledger,
                                                RandomGenerator random, Map<String, RuleCompiler.Program> programs) {
        Snapshot snapshot = validateSources(activeSources);
        if (selections == null || eligibleRecipients == null || ledger == null)
            throw RuleJson.error("/claims", "Selections, eligible recipients and claim state are required.");
        Map<String, Candidate> byInstance = new HashMap<>();
        Map<String, ObjectNode> workingContexts = new HashMap<>();
        for (Candidate candidate : snapshot.sources()) {
            String instanceId = candidate.context().path("source").path("instanceId").asText();
            byInstance.put(instanceId, candidate);
            workingContexts.put(instanceId, candidate.context());
        }
        Map<String, RecipientCandidate> recipientsById = new HashMap<>();
        Map<String, ObjectNode> workingRecipients = new HashMap<>();
        for (RecipientCandidate recipient : eligibleRecipients) {
            ObjectNode actor = recipient.snapshot();
            if (!recipient.actorId().equals(actor.path("id").asText()))
                throw RuleJson.error("/recipients/" + recipient.actorId(), "Recipient snapshot ID does not match.");
            if (recipientsById.putIfAbsent(recipient.actorId(), recipient) != null)
                throw RuleJson.error("/recipients/" + recipient.actorId(), "Duplicate eligible recipient.");
            workingRecipients.put(recipient.actorId(), actor);
        }

        List<PreparedClaim> checkedClaims = new ArrayList<>();
        Set<ClaimKey> addedClaims = new HashSet<>();
        Set<ActionKey> selectedKeys = new HashSet<>();
        Set<String> exclusiveGroups = new HashSet<>();
        Set<String> selfClaims = new HashSet<>();
        List<Invocation> invocations = new ArrayList<>();
        for (ClaimIntent claimIntent : selections) {
            if (claimIntent == null) throw RuleJson.error("/claims", "A claim selection is missing.");
            if (claimIntent.expectedClaimRevision() != ledger.revision())
                throw new RuleRuntime.Unavailable("staleClaim", "The claim state changed. Refresh the available actions.");
            Candidate candidate = byInstance.get(claimIntent.action().sourceInstanceId());
            if (candidate == null) throw RuleJson.error("/claims", "Selected source is no longer active.");
            RecipientCandidate recipient = recipientsById.get(claimIntent.recipientId());
            if (recipient == null) throw new RuleRuntime.Unavailable("recipient", "This recipient is no longer eligible.");
            if (claimIntent.expectedRecipientRevision() != recipient.revision())
                throw new RuleRuntime.Unavailable("staleRecipient", "The recipient sheet changed. Refresh the available actions.");
            RuleRuntime.Intent intent = claimIntent.action();
            if (intent.expectedRevision() != snapshot.revision())
                throw new RuleRuntime.Unavailable("stale", "This action is stale. Use its latest state.");
            ObjectNode rule = candidate.program().rule(intent.ruleId());
            JsonNode binding = binding(rule, intent.bindingId());
            JsonNode ownerId = snapshot.actor().get("id");
            boolean self = ownerId != null && claimIntent.recipientId().equals(ownerId.asText());
            if (self && !sameSelfSnapshot(snapshot, recipient))
                throw new RuleRuntime.Unavailable("staleRecipient", "The source character changed while resolving its claim.");
            String recipients = binding.path("claimScope").path("recipients").asText("otherPlayersCharacters");
            boolean excludeSource = binding.path("claimScope").path("excludeSource").asBoolean(false);
            if ("sourceActor".equals(recipients) && !self)
                throw new RuleRuntime.Unavailable("recipient", "Only the source actor may claim this.");
            if (self && ("otherPlayersCharacters".equals(recipients) || excludeSource))
                throw RuleJson.error("/claims/recipientId", "A cross-actor claim must target another actor.");
            if (self) selfClaims.add(claimIntent.recipientId());
            if (binding.has("targetSelection"))
                throw RuleJson.error("/claims", "Targeted actions require executeTargets.");
            String group = RuleJson.id(binding.get("claimGroup"), "/binding/claimGroup");
            String mode = binding.path("mode").asText();
            if (!Set.of("button", "option").contains(mode))
                throw RuleJson.error("/claims", "Only selected buttons and options may create claims.");
            if (!snapshot.eventType().equals(binding.path("event").asText()))
                throw new RuleRuntime.Unavailable("event", "This action does not apply to this event.");

            ClaimKey claimKey = new ClaimKey(group, claimIntent.recipientId());
            if (ledger.claims().contains(claimKey) || !addedClaims.add(claimKey))
                throw new RuleRuntime.Unavailable("claimed", "This recipient has already claimed this group.");
            Integer limit = ledger.limits().get(group);
            if (limit != null) {
                long taken = ledger.claims().stream().filter(item -> item.group().equals(group)).count()
                        + addedClaims.stream().filter(item -> item.group().equals(group)).count();
                if (taken > limit) throw new RuleRuntime.Unavailable("claimLimit", "No claims remain for this offer.");
            }
            ActionKey key = new ActionKey(intent.sourceInstanceId(), intent.ruleId(), intent.bindingId(), intent.eachId(),
                    claimIntent.recipientId());
            if (!selectedKeys.add(key)) throw RuleJson.error("/claims", "A binding cannot be selected more than once.");
            String choiceScope = claimIntent.recipientId() + "\u0000";
            String exclusiveGroup = binding.path("exclusiveGroup").asText(null);
            if (exclusiveGroup != null && !exclusiveGroups.add(choiceScope + exclusiveGroup))
                throw RuleJson.error("/claims", "Only one option in each exclusive group may be selected per recipient.");

            invocations.add(new Invocation(candidate, intent, mode, binding.path("priority").asInt(),
                    exclusiveGroup, false, claimIntent.recipientId(), group));
        }

        invocations.sort(Comparator.comparing(Invocation::priority, Comparator.reverseOrder())
                .thenComparing(item -> item.intent().sourceInstanceId())
                .thenComparing(item -> item.intent().ruleId())
                .thenComparing(item -> item.intent().bindingId())
                .thenComparing(item -> item.intent().eachId() == null ? "" : item.intent().eachId()));
        for (Invocation invocation : invocations) {
            String recipientId = invocation.recipientId();
            ObjectNode context = invocation.candidate().context();
            setParticipants(context, snapshot.actor(), selfClaims.contains(recipientId) ? null
                    : recipientsById.get(recipientId).snapshot());
            context.set("revision", RuleExpression.numeric(snapshot.revision(), "/revision"));
            RuleRuntime.Prepared next = RuleRuntime.preflight(invocation.candidate().program(), invocation.intent(), context);
            checkedClaims.add(new PreparedClaim(next, recipientId, invocation.claimGroup()));
        }

        List<PreparedClaim> prepared = new ArrayList<>();
        ObjectNode sharedActor = snapshot.actor().deepCopy();
        for (PreparedClaim checked : checkedClaims) {
            RuleRuntime.Prepared item = checked.prepared();
            String instanceId = item.intent().sourceInstanceId();
            String recipientId = checked.recipientId();
            ObjectNode context = workingContexts.get(instanceId).deepCopy();
            boolean self = selfClaims.contains(recipientId);
            setParticipants(context, sharedActor, self ? null : workingRecipients.get(recipientId));
            context.set("revision", RuleExpression.numeric(snapshot.revision(), "/revision"));
            RuleRuntime.Prepared next = RuleRuntime.reserveCosts(
                    byInstance.get(instanceId).program(), item, context);
            workingContexts.put(instanceId, next.context().deepCopy());
            sharedActor = next.context().path("actor").deepCopy();
            if (!self) {
                workingRecipients.put(recipientId, next.context().path("recipient").deepCopy());
            }
            prepared.add(new PreparedClaim(next, recipientId, checked.claimGroup()));
        }

        List<ClaimActionResult> actionResults = new ArrayList<>();
        List<ObjectNode> outbox = new ArrayList<>();
        ObjectNode sharedEvent = snapshot.event().deepCopy();
        for (PreparedClaim claim : prepared) {
            RuleRuntime.Prepared item = claim.prepared();
            String instanceId = item.intent().sourceInstanceId();
            ObjectNode current = item.context().deepCopy();
            mergeTransactionState(current, workingContexts.get(instanceId));
            boolean self = selfClaims.contains(claim.recipientId());
            setParticipants(current, sharedActor, self ? null : workingRecipients.get(claim.recipientId()));
            current.set("event", sharedEvent.deepCopy());
            current.set("revision", RuleExpression.numeric(snapshot.revision(), "/revision"));
            RuleRuntime.Prepared executable = new RuleRuntime.Prepared(item.intent(), current, snapshot.revision());
            RuleRuntime.Resolution resolution = RuleRuntime.executePrepared(
                    byInstance.get(instanceId).program(), executable, random, false, programs);
            ObjectNode nextContext = resolution.context();
            collectRecipientCommands(outbox, nextContext, claim.recipientId());
            sharedActor = nextContext.path("actor").deepCopy();
            sharedEvent = nextContext.path("event").deepCopy();
            if (!self) {
                workingRecipients.put(claim.recipientId(), nextContext.path("recipient").deepCopy());
            }
            workingContexts.put(instanceId, nextContext);
            ActionResult action = new ActionResult(instanceId, item.intent().ruleId(), item.intent().bindingId(),
                    resolution.report(), resolution.publishedRolls(), resolution.diceRolls());
            actionResults.add(new ClaimActionResult(action, claim.recipientId(), claim.claimGroup()));
        }

        List<String> instanceIds = new ArrayList<>(workingContexts.keySet());
        instanceIds.sort(String::compareTo);
        List<ObjectNode> updatedSources = new ArrayList<>();
        for (String instanceId : instanceIds) {
            ObjectNode context = workingContexts.get(instanceId).deepCopy();
            context.set("actor", sharedActor.deepCopy());
            context.set("event", sharedEvent.deepCopy());
            context.remove("recipient");
            if (!prepared.isEmpty()) context.set("revision", RuleExpression.numeric(snapshot.revision() + 1d, "/revision"));
            updatedSources.add(context);
        }
        List<String> recipientIds = new ArrayList<>();
        for (ClaimKey key : addedClaims)
            if (!selfClaims.contains(key.recipientId()) && !recipientIds.contains(key.recipientId())) recipientIds.add(key.recipientId());
        recipientIds.sort(String::compareTo);
        List<RecipientUpdate> updatedRecipients = new ArrayList<>();
        for (String recipientId : recipientIds) {
            RecipientCandidate original = recipientsById.get(recipientId);
            updatedRecipients.add(new RecipientUpdate(recipientId, workingRecipients.get(recipientId),
                    original.revision() + 1));
        }
        Set<ClaimKey> allClaims = new HashSet<>(ledger.claims());
        allClaims.addAll(addedClaims);
        ClaimLedger updatedLedger = new ClaimLedger(ledger.revision() + (addedClaims.isEmpty() ? 0 : 1), allClaims, ledger.limits());
        return new ClaimResolution(updatedSources, updatedRecipients, updatedLedger, actionResults, outbox);
    }

    /**
     * Folds a self-claim back into the shared actor: the source actor is both {@code actor} and {@code recipient}, so
     * at most one of the two views may change.
     */
    /** A self-target is one staged sheet with two expression roots, not two sheets to merge after execution. */
    private static void setParticipants(ObjectNode context, ObjectNode actor, ObjectNode otherRecipient) {
        ObjectNode staged = actor.deepCopy();
        context.set("actor", staged);
        context.set("recipient", otherRecipient == null ? staged : otherRecipient.deepCopy());
    }

    /**
     * Executes one JSON-declared target action against host-selected recipient snapshots. The host must filter the
     * supplied recipients by the binding's declared scope; snapshots and revisions must come from trusted storage.
     * Cardinality "all" always applies to the complete supplied eligible set and never accepts a client subset.
     */
    public static TargetResolution executeTargets(List<Candidate> activeSources, TargetIntent selection,
                                                   List<RecipientCandidate> eligibleRecipients,
                                                   RandomGenerator random) {
        return executeTargets(activeSources, selection, eligibleRecipients, random, programs(activeSources));
    }

    /** Supply the compiled programs referenced by source and recipient effects. */
    public static TargetResolution executeTargets(List<Candidate> activeSources, TargetIntent selection,
                                                   List<RecipientCandidate> eligibleRecipients,
                                                   RandomGenerator random, Map<String, RuleCompiler.Program> programs) {
        Snapshot snapshot = validateSources(activeSources);
        if (selection == null || eligibleRecipients == null)
            throw RuleJson.error("/target", "A selection and eligible recipient set are required.");
        if (eligibleRecipients.size() > 128)
            throw RuleJson.error("/recipients", "A target transaction is limited to 128 characters.");
        Candidate candidate = null;
        for (Candidate item : snapshot.sources())
            if (item.context().path("source").path("instanceId").asText().equals(selection.action().sourceInstanceId()))
                candidate = item;
        if (candidate == null) throw new RuleRuntime.Unavailable("source", "Selected source is no longer active.");
        RuleRuntime.Intent intent = selection.action();
        if (intent.expectedRevision() != snapshot.revision())
            throw new RuleRuntime.Unavailable("stale", "This action is stale. Use its latest state.");
        ObjectNode rule = candidate.program().rule(intent.ruleId());
        JsonNode binding = binding(rule, intent.bindingId());
        JsonNode targetSelection = binding.get("targetSelection");
        if (targetSelection == null) throw RuleJson.error("/target", "This binding does not select recipients.");
        if (!"button".equals(binding.path("mode").asText()))
            throw RuleJson.error("/target", "Only selected sheet buttons may target recipients.");
        if (!snapshot.eventType().equals(binding.path("event").asText()))
            throw new RuleRuntime.Unavailable("event", "This action does not apply to this event.");

        Map<String, RecipientCandidate> recipientsById = new HashMap<>();
        Map<String, ObjectNode> workingRecipients = new HashMap<>();
        for (RecipientCandidate recipient : eligibleRecipients) {
            ObjectNode actor = recipient.snapshot();
            if (!recipient.actorId().equals(actor.path("id").asText()))
                throw RuleJson.error("/recipients/" + recipient.actorId(), "Recipient snapshot ID does not match.");
            if (recipientsById.putIfAbsent(recipient.actorId(), recipient) != null)
                throw RuleJson.error("/recipients/" + recipient.actorId(), "Duplicate eligible recipient.");
            if (recipient.actorId().equals(snapshot.actor().path("id").asText()) && !sameSelfSnapshot(snapshot, recipient))
                throw new RuleRuntime.Unavailable("staleRecipient", "The source character changed while resolving its target.");
            workingRecipients.put(recipient.actorId(), actor);
        }

        String cardinality = targetSelection.path("cardinality").asText();
        String sourceActorId = snapshot.actor().path("id").asText();
        String recipientId = selection.recipientId();
        List<String> targetIds = new ArrayList<>();
        if ("one".equals(cardinality)) {
            if (recipientId == null) throw RuleJson.error("/target/recipientId", "Choose one eligible recipient.");
            RecipientCandidate recipient = recipientsById.get(recipientId);
            if (recipient == null) throw new RuleRuntime.Unavailable("recipient", "This recipient is no longer eligible.");
            if (selection.expectedRecipientRevision() != recipient.revision())
                throw new RuleRuntime.Unavailable("staleRecipient", "The target character changed. Refresh the available actions.");
            targetIds.add(recipientId);
        } else if ("all".equals(cardinality)) {
            if (recipientId != null) throw RuleJson.error("/target/recipientId", "An all-recipient action does not accept a client-selected subset.");
            targetIds.addAll(recipientsById.keySet());
            targetIds.sort(String::compareTo);
        } else throw RuleJson.error("/targetSelection/cardinality", "Unknown target cardinality.");
        if (targetSelection.path("excludeSource").asBoolean(false)) targetIds.remove(sourceActorId);
        if (targetSelection.has("where")) {
            ObjectNode eligibilityContext = candidate.context();
            targetIds.removeIf(id -> !RuleRuntime.recipientEligible(targetSelection, eligibilityContext,
                    id.equals(sourceActorId) ? snapshot.actor() : recipientsById.get(id).snapshot()));
            if ("one".equals(cardinality) && targetIds.isEmpty())
                throw new RuleRuntime.Unavailable("recipient", "This recipient is no longer eligible.");
        }
        if (targetIds.isEmpty()) throw new RuleRuntime.Unavailable("recipient", "There are no eligible targets for this action.");

        List<PreparedTarget> checked = new ArrayList<>();
        for (String id : targetIds) {
            ObjectNode context = candidate.context();
            setParticipants(context, snapshot.actor(), id.equals(sourceActorId) ? null : workingRecipients.get(id));
            context.set("revision", RuleExpression.numeric(snapshot.revision(), "/revision"));
            checked.add(new PreparedTarget(RuleRuntime.preflight(candidate.program(), intent, context), id));
        }

        ObjectNode workingSource = candidate.context();
        ObjectNode sharedActor = snapshot.actor().deepCopy();
        List<PreparedTarget> prepared = new ArrayList<>();
        boolean reserveSourceCosts = true;
        for (PreparedTarget item : checked) {
            ObjectNode context = workingSource.deepCopy();
            setParticipants(context, sharedActor, item.recipientId().equals(sourceActorId) ? null : workingRecipients.get(item.recipientId()));
            context.set("revision", RuleExpression.numeric(snapshot.revision(), "/revision"));
            RuleRuntime.Prepared next = RuleRuntime.reserveCosts(candidate.program(), item.prepared(), context, reserveSourceCosts);
            reserveSourceCosts = false;
            workingSource = next.context().deepCopy();
            sharedActor = next.context().path("actor").deepCopy();
            if (!item.recipientId().equals(sourceActorId))
                workingRecipients.put(item.recipientId(), next.context().path("recipient").deepCopy());
            prepared.add(new PreparedTarget(next, item.recipientId()));
        }

        List<TargetActionResult> actionResults = new ArrayList<>();
        List<ObjectNode> outbox = new ArrayList<>();
        ObjectNode sharedEvent = snapshot.event().deepCopy();
        for (PreparedTarget item : prepared) {
            ObjectNode current = item.prepared().context().deepCopy();
            mergeTransactionState(current, workingSource);
            setParticipants(current, sharedActor, item.recipientId().equals(sourceActorId) ? null : workingRecipients.get(item.recipientId()));
            current.set("event", sharedEvent.deepCopy());
            current.set("revision", RuleExpression.numeric(snapshot.revision(), "/revision"));
            RuleRuntime.Prepared executable = new RuleRuntime.Prepared(intent, current, snapshot.revision());
            RuleRuntime.Resolution resolution = RuleRuntime.executePrepared(candidate.program(), executable, random, false, programs);
            ObjectNode nextContext = resolution.context();
            collectRecipientCommands(outbox, nextContext, item.recipientId());
            sharedActor = nextContext.path("actor").deepCopy();
            sharedEvent = nextContext.path("event").deepCopy();
            workingSource = nextContext.deepCopy();
            if (!item.recipientId().equals(sourceActorId))
                workingRecipients.put(item.recipientId(), nextContext.path("recipient").deepCopy());
            ActionResult action = new ActionResult(intent.sourceInstanceId(), intent.ruleId(), intent.bindingId(),
                    resolution.report(), resolution.publishedRolls(), resolution.diceRolls());
            actionResults.add(new TargetActionResult(action, item.recipientId()));
        }

        List<ObjectNode> updatedSources = new ArrayList<>();
        for (Candidate source : snapshot.sources()) {
            String instanceId = source.context().path("source").path("instanceId").asText();
            ObjectNode context = instanceId.equals(intent.sourceInstanceId()) ? workingSource.deepCopy() : source.context();
            context.set("actor", sharedActor.deepCopy());
            context.set("event", sharedEvent.deepCopy());
            context.remove("recipient");
            context.set("revision", RuleExpression.numeric(snapshot.revision() + 1d, "/revision"));
            updatedSources.add(context);
        }
        List<RecipientUpdate> updates = new ArrayList<>();
        for (String id : targetIds) if (!id.equals(sourceActorId)) {
            RecipientCandidate original = recipientsById.get(id);
            updates.add(new RecipientUpdate(id, workingRecipients.get(id), original.revision() + 1));
        }
        return new TargetResolution(updatedSources, updates, actionResults, outbox);
    }

    private static void collectRecipientCommands(List<ObjectNode> outbox, ObjectNode context, String recipientId) {
        JsonNode commands = context.remove("outbox");
        if (commands != null) for (JsonNode command : commands)
            outbox.add(((ObjectNode) command).put("recipientId", recipientId));
    }

    private record Snapshot(List<Candidate> sources, ObjectNode actor, ObjectNode originalActor, ObjectNode event, String eventType,
                            long revision) {}

    private static boolean sameSelfSnapshot(Snapshot source, RecipientCandidate recipient) {
        ObjectNode actor = recipient.snapshot();
        return recipient.revision() == source.revision()
                && (actor.equals(source.originalActor()) || actor.equals(source.actor()));
    }

    private static Map<String, RuleCompiler.Program> programs(List<Candidate> sources) {
        if (sources == null) return Map.of();
        Map<String, RuleCompiler.Program> programs = new HashMap<>();
        for (Candidate source : sources)
            if (source != null) programs.putIfAbsent(source.program().sourceId(), source.program());
        return programs;
    }

    private static Snapshot validateSources(List<Candidate> activeSources) {
        if (activeSources == null) throw RuleJson.error("/sources", "Expected active sources.");
        List<Candidate> sources = new ArrayList<>(activeSources);
        sources.sort(Comparator.comparing(candidate -> candidate.context().path("source").path("instanceId").asText()));
        Set<String> instances = new HashSet<>();
        ObjectNode sharedActor = null;
        ObjectNode sharedEvent = null;
        String sharedType = null;
        Long sharedRevision = null;
        for (Candidate candidate : sources) {
            ObjectNode context = candidate.context();
            RuleJson.checkTree(context, "/context", 0, new int[]{0});
            ObjectNode source = RuleJson.object(context.path("source"), "/source");
            if (!candidate.program().sourceId().equals(source.path("id").asText()))
                throw RuleJson.error("/source/id", "Source definition does not match the program.");
            String instanceId = RuleJson.id(source.get("instanceId"), "/source/instanceId");
            if (!instances.add(instanceId)) throw RuleJson.error("/source/instanceId", "Duplicate active source instance.");
            ObjectNode actor = RuleJson.object(context.path("actor"), "/actor");
            ObjectNode event = RuleJson.object(context.path("event"), "/event");
            String eventType = RuleJson.text(event.get("type"), "/event/type");
            long revision = RuleRuntime.revision(context);
            if (sharedActor == null) {
                sharedActor = actor.deepCopy();
                sharedEvent = event.deepCopy();
                sharedType = eventType;
                sharedRevision = revision;
            } else if (!sharedActor.equals(actor)) {
                throw RuleJson.error("/actor", "Active sources must use the same actor snapshot.");
            } else if (!sharedType.equals(eventType) || !sharedEvent.equals(event)) {
                throw RuleJson.error("/event", "Active sources must be described against the same event snapshot.");
            } else if (sharedRevision != revision) {
                throw RuleJson.error("/revision", "Active sources must use the same revision.");
            }
        }
        ObjectNode originalActor;
        if (sharedActor == null) {
            sharedActor = RuleJson.JSON.createObjectNode();
            originalActor = sharedActor.deepCopy();
            sharedEvent = RuleJson.JSON.createObjectNode();
            sharedType = "";
            sharedRevision = 0L;
        } else {
            originalActor = sources.stream().anyMatch(candidate -> candidate.program().hasComputedActorPools())
                    ? sharedActor.deepCopy() : sharedActor;
            for (Candidate candidate : sources) {
                if (!candidate.program().hasComputedActorPools()) continue;
                ObjectNode view = candidate.context();
                view.set("actor", sharedActor);
                RuleRuntime.clampComputedActorPools(candidate.program(), view);
                sharedActor = (ObjectNode) view.get("actor");
            }
            if (!originalActor.equals(sharedActor)) {
                boolean derivedEffects = false;
                for (JsonNode effect : originalActor.path("effects"))
                    derivedEffects |= effect.path("derived").asBoolean();
                if (originalActor.has("stats") || derivedEffects)
                    throw new RuleRuntime.Unavailable("computedPoolProjection",
                            "Computed pools changed on an already projected actor. Reproject its statistics and effects before dispatch.");
                List<Candidate> normalized = new ArrayList<>();
                for (Candidate candidate : sources) {
                    ObjectNode context = candidate.context();
                    context.set("actor", sharedActor);
                    normalized.add(new Candidate(candidate.program(), context));
                }
                sources = normalized;
            }
            for (Candidate candidate : sources) {
                if (!candidate.program().hasComputedActorPools()) continue;
                ObjectNode view = candidate.context();
                view.set("actor", sharedActor);
                RuleRuntime.requireStableComputedActorPools(candidate.program(), view);
            }
        }
        return new Snapshot(List.copyOf(sources), sharedActor, originalActor, sharedEvent, sharedType, sharedRevision);
    }

    static JsonNode binding(ObjectNode rule, String id) {
        for (JsonNode binding : rule.path("bindings")) if (id.equals(binding.path("id").asText())) return binding;
        throw RuleJson.error("/bindingId", "Unknown binding.");
    }

    private static void mergeTransactionState(ObjectNode target, ObjectNode current) {
        for (String field : List.of("actor", "recipient", "source", "state", "usage", "sourcePools")) {
            if (current.has(field)) target.set(field, current.path(field).deepCopy());
            else target.remove(field);
        }
    }

    private static List<ObjectNode> copyNodes(List<ObjectNode> nodes) {
        return nodes.stream().map(ObjectNode::deepCopy).toList();
    }
}
