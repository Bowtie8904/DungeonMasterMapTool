package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dmmt.online.Campaign;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * Resolves a compiled rule against a host-provided snapshot. The caller must authorize the source
 * and atomically persist the returned state and reports; this class performs no I/O.
 */
public final class RuleRuntime {
    public record Intent(String ruleId, String bindingId, String sourceInstanceId, long expectedRevision, ObjectNode inputs, String eachId) {
        public Intent(String ruleId, String bindingId, String sourceInstanceId, long expectedRevision, ObjectNode inputs) {
            this(ruleId, bindingId, sourceInstanceId, expectedRevision, inputs, null);
        }
    }
    record Prepared(Intent intent, ObjectNode context, long revision) {}
    public record Resolution(ObjectNode context, String report, List<ObjectNode> publishedRolls, List<ObjectNode> diceRolls) {}

    @FunctionalInterface
    interface TrackObserver {
        void changed(ObjectNode staged, String holder, ObjectNode event);
        default void mutated(ObjectNode staged, String operation, ObjectNode previousActor, String target) {}
        default ObjectNode rolled(ObjectNode staged, ObjectNode roll) { return roll; }
    }

    public static final class Unavailable extends IllegalStateException {
        private final String code;
        public Unavailable(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }

    private RuleRuntime() {}

    public static ObjectNode describe(RuleCompiler.Program program, String ruleId, String bindingId, ObjectNode hostContext) {
        return describe(program, ruleId, bindingId, hostContext, null);
    }

    static ObjectNode describe(RuleCompiler.Program program, String ruleId, String bindingId, ObjectNode hostContext, PartyTargets party) {
        ObjectNode rule = program.rule(ruleId);
        ObjectNode context = context(program, rule, hostContext);
        JsonNode binding = binding(rule, bindingId);
        if (binding.has("forEach")) throw RuleJson.error("/bindingId", "Use describeEach for a per-die binding.");
        return describe(program, ruleId, bindingId, rule, binding, context, null, party, null, false);
    }

    static ObjectNode describeAwaitingRecipient(RuleCompiler.Program program, String ruleId, String bindingId,
                                                 ObjectNode hostContext, PartyTargets party) {
        ObjectNode rule = program.rule(ruleId);
        ObjectNode context = context(program, rule, hostContext);
        JsonNode binding = binding(rule, bindingId);
        return describe(program, ruleId, bindingId, rule, binding, context, null, party, null, true);
    }

    static ObjectNode describeForRecipient(RuleCompiler.Program program, String ruleId, String bindingId,
                                           ObjectNode hostContext, ObjectNode targetSelection) {
        ObjectNode rule = program.rule(ruleId);
        ObjectNode context = context(program, rule, hostContext);
        JsonNode binding = binding(rule, bindingId);
        ObjectNode description = describe(program, ruleId, bindingId, rule, binding, context, null, null,
                targetSelection, false);
        description.remove("targetSelection");
        return description;
    }

    public static ArrayNode describeEach(RuleCompiler.Program program, String ruleId, String bindingId, ObjectNode hostContext) {
        return describeEach(program, ruleId, bindingId, hostContext, null);
    }

    static ArrayNode describeEach(RuleCompiler.Program program, String ruleId, String bindingId, ObjectNode hostContext, PartyTargets party) {
        ObjectNode rule = program.rule(ruleId);
        ObjectNode context = context(program, rule, hostContext);
        JsonNode binding = binding(rule, bindingId);
        if (!binding.has("forEach")) throw RuleJson.error("/bindingId", "Binding does not iterate a pool.");
        ArrayNode descriptions = RuleJson.JSON.createArrayNode();
        ObjectNode pool = poolState(program, context, binding.path("forEach").asText());
        for (JsonNode die : pool.path("dice")) {
            ObjectNode itemContext = context.deepCopy();
            itemContext.set("die", die.deepCopy());
            ObjectNode description = describe(program, ruleId, bindingId, rule, binding, itemContext,
                    die.path("id").asText(), party, null, false);
            descriptions.add(description);
        }
        return descriptions;
    }

    private static ObjectNode describe(RuleCompiler.Program program, String ruleId, String bindingId, ObjectNode rule,
                                       JsonNode binding, ObjectNode context, String eachId, PartyTargets party,
                                       ObjectNode targetSelection, boolean awaitingRecipient) {
        ObjectNode description = RuleJson.JSON.createObjectNode();
        description.put("ruleId", ruleId).put("bindingId", bindingId).put("sourceId", program.sourceId())
                .put("sourceInstanceId", context.path("source").path("instanceId").asText())
                .put("revision", revision(context)).put("mode", binding.path("mode").asText())
                .put("surface", binding.path("surface").asText());
        if (eachId != null) description.put("eachId", eachId);
        if (binding.has("group")) description.set("group", binding.get("group"));
        if (binding.has("defaultOn")) description.set("defaultOn", binding.get("defaultOn"));
        if (binding.has("exclusiveGroup")) description.set("exclusiveGroup", binding.get("exclusiveGroup"));
        if (binding.has("repeatable")) description.set("repeatable", binding.get("repeatable"));
        if (binding.has("restMove")) description.set("restMove", binding.get("restMove").deepCopy());
        if (isRestEvent(binding.path("event").asText()))
            description.put("restPhase", binding.path("restPhase").asText("beforeReset"));
        if (binding.has("claimGroup")) description.set("claimGroup", binding.get("claimGroup"));
        if (binding.has("claimGroupScope")) description.set("claimGroupScope", binding.get("claimGroupScope"));
        if (binding.has("claimScope")) description.set("claimScope", binding.get("claimScope"));
        if (binding.has("targetSelection")) description.set("targetSelection", targetSelection != null
                ? targetSelection : party == null
                ? describeTargets(binding, context) : party.describe(binding, ruleId, context));
        description.put("audience", binding.path("audience").asText("self"));
        description.put("priority", binding.path("priority").asInt());
        for (String text : List.of("label", "description")) if (rule.path("ui").has(text))
            description.put(text, awaitingRecipient
                    ? RuleTemplate.renderAwaitingRecipient(rule.path("ui").path(text).asText(), context)
                    : RuleTemplate.render(rule.path("ui").path(text).asText(), context));
        if (awaitingRecipient) {
            boolean available = !description.path("targetSelection").path("options").isEmpty();
            description.put("available", available);
            if (!available) description.put("reasonCode", "recipient")
                    .put("reason", "There are no eligible targets for this action.");
            return description;
        }
        try {
            available(program, rule, binding, context);
            if (binding.has("targetSelection") && description.path("targetSelection").path("options").isEmpty())
                throw new Unavailable("recipient", "There are no eligible targets for this action.");
            description.set("inputs", describeInputs(rule, context));
            description.put("costsRequireInput", preflightCosts(program, rule, context));
            preflightAfterCosts(program, rule, context);
            description.put("available", true);
        } catch (Unavailable ex) {
            description.put("available", false).put("reasonCode", ex.code())
                    .put("reason", unavailableText(rule, context, ex.getMessage()));
        }
        return description;
    }

    static boolean recipientDependentDescription(ObjectNode rule, JsonNode binding) {
        Set<String> reads = new HashSet<>();
        for (JsonNode part : List.of(rule.path("when"), binding.path("when"), rule.path("inputs"),
                rule.path("costs"), rule.path("after").path("costs")))
            reads.addAll(RuleExpression.references(part));
        for (String field : List.of("label", "description", "unavailable"))
            reads.addAll(RuleTemplate.references(rule.path("ui").path(field).asText("")));
        for (JsonNode input : rule.path("inputs")) {
            for (String field : List.of("label", "choiceLabel"))
                reads.addAll(RuleTemplate.references(input.path(field).asText("")));
            for (JsonNode option : input.path("options"))
                reads.addAll(RuleTemplate.references(option.path("label").asText("")));
        }
        if (reads.stream().anyMatch(read -> read.startsWith("recipient."))) return true;
        for (JsonNode costs : List.of(rule.path("costs"), rule.path("after").path("costs")))
            for (JsonNode cost : costs)
                if (cost.path("pool").asText().startsWith("recipient.")
                        || cost.path("track").asText().startsWith("recipient.")
                        || "recipient".equals(cost.path("owner").asText())
                        || "recipient".equals(cost.path("target").asText())) return true;
        return false;
    }

    /** Evaluates a target selection's per-recipient {@code where} condition against the recipient snapshot. */
    static boolean recipientEligible(JsonNode selection, ObjectNode context, ObjectNode recipient) {
        if (!selection.has("where")) return true;
        ObjectNode scoped = context.deepCopy();
        scoped.set("recipient", recipient);
        return RuleExpression.bool(eval(selection.get("where"), scoped), "/targetSelection/where");
    }

    private static ObjectNode describeTargets(JsonNode binding, ObjectNode context) {
        ObjectNode selection = ((ObjectNode) binding.path("targetSelection")).deepCopy();
        String scope = selection.path("scope").asText();
        JsonNode available = context.path("targetOptions").path(scope);
        ArrayNode options = RuleJson.JSON.createArrayNode();
        if (!available.isMissingNode()) {
            if (!available.isArray() || available.size() > 128)
                throw RuleJson.error("/targetOptions/" + scope, "Expected at most 128 host-selected targets.");
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < available.size(); i++) {
                JsonNode option = available.get(i);
                String path = "/targetOptions/" + scope + "/" + i;
                RuleJson.fields(option, path, "id", "label", "revision", "snapshot");
                String id = RuleJson.id(option.path("id"), path + "/id");
                if (!ids.add(id)) throw RuleJson.error(path + "/id", "Duplicate target option.");
                JsonNode revisionNode = option.path("revision");
                if (!revisionNode.isIntegralNumber() || !revisionNode.canConvertToLong() || revisionNode.longValue() < 0)
                    throw RuleJson.error(path + "/revision", "Expected a nonnegative host revision.");
                ObjectNode item = options.addObject().put("id", id)
                        .put("label", RuleJson.text(option.path("label"), path + "/label"));
                item.put("revision", revisionNode.longValue());
                if (selection.has("where") && option.has("snapshot")
                        && !recipientEligible(selection, context, RuleJson.object(option.get("snapshot"), path + "/snapshot")))
                    options.remove(options.size() - 1);
            }
        }
        if (selection.path("excludeSource").asBoolean(false)) {
            String sourceActorId = context.path("actor").path("id").asText();
            for (int i = options.size() - 1; i >= 0; i--)
                if (sourceActorId.equals(options.path(i).path("id").asText())) options.remove(i);
        }
        selection.set("options", options);
        return selection;
    }

    public static Resolution execute(RuleCompiler.Program program, Intent intent, ObjectNode hostContext, RandomGenerator random) {
        return execute(program, intent, hostContext, random, Map.of(program.sourceId(), program));
    }

    /** Supply all compiled programs referenced by the affected holders' effects. */
    public static Resolution execute(RuleCompiler.Program program, Intent intent, ObjectNode hostContext,
                                     RandomGenerator random, Map<String, RuleCompiler.Program> programs) {
        return executePrepared(program, prepare(program, intent, hostContext), random, false, programs);
    }

    static Resolution executeObserved(RuleCompiler.Program program, Intent intent, ObjectNode hostContext,
                                      RandomGenerator random, Map<String, RuleCompiler.Program> programs,
                                      TrackObserver observer) {
        Prepared prepared = reserveCosts(program, preflight(program, intent, hostContext), hostContext, true, observer);
        return executePrepared(program, prepared, random, false, programs, false, observer);
    }

    public static Resolution preview(RuleCompiler.Program program, Intent intent, ObjectNode hostContext) {
        return preview(program, intent, hostContext, Map.of(program.sourceId(), program));
    }

    /** A preview uses the same effect registry and safety checks as a committed resolution. */
    public static Resolution preview(RuleCompiler.Program program, Intent intent, ObjectNode hostContext,
                                     Map<String, RuleCompiler.Program> programs) {
        return executePrepared(program, prepare(program, intent, hostContext), null, true, programs);
    }

    static Prepared prepare(RuleCompiler.Program program, Intent intent, ObjectNode hostContext) {
        return reserveCosts(program, preflight(program, intent, hostContext), hostContext);
    }

    static Prepared preflight(RuleCompiler.Program program, Intent intent, ObjectNode hostContext) {
        ObjectNode rule = program.rule(intent.ruleId());
        ObjectNode next = intentContext(program, intent, hostContext, rule);
        JsonNode binding = binding(rule, intent.bindingId());
        available(program, rule, binding, next);
        validateInputs(rule, next, intent.inputs());
        preflightAfterCosts(program, rule, next);
        return new Prepared(intent, next, revision(next));
    }

    static void validateSkippedInputs(RuleCompiler.Program program, Intent intent, ObjectNode hostContext) {
        ObjectNode rule = program.rule(intent.ruleId());
        validateInputs(rule, intentContext(program, intent, hostContext, rule), intent.inputs());
    }

    private static ObjectNode intentContext(RuleCompiler.Program program, Intent intent, ObjectNode hostContext, ObjectNode rule) {
        ObjectNode next = context(program, rule, hostContext);
        if (!next.path("source").path("instanceId").asText().equals(intent.sourceInstanceId()))
            throw new Unavailable("source", "The source instance changed. Reopen this action.");
        long revision = revision(next);
        if (intent.expectedRevision() != revision)
            throw new Unavailable("stale", "This action is stale. Use its latest state.");
        JsonNode binding = binding(rule, intent.bindingId());
        if (binding.has("forEach")) {
            if (intent.eachId() == null) throw RuleJson.error("/eachId", "This action requires a pool item.");
            ObjectNode pool = poolState(program, next, binding.path("forEach").asText());
            JsonNode selected = null;
            for (JsonNode die : pool.path("dice")) if (intent.eachId().equals(die.path("id").asText())) selected = die;
            if (selected == null) throw new Unavailable("poolItem", "This pool item is no longer available.");
            next.set("die", selected.deepCopy());
        } else if (intent.eachId() != null) throw RuleJson.error("/eachId", "This action does not select a pool item.");
        return next;
    }

    /** The declared maximum, raised by any other active source's limit override naming this source and limit. */
    private static int maxUses(RuleCompiler.Program program, ObjectNode context, String limit) {
        int declared = program.limits().path(limit).path("max").asInt();
        String key = context.path("source").path("name").asText() + ":" + limit;
        return Math.max(declared, context.path("actor").path("limitOverrides").path(key).asInt());
    }

    static void reserveUse(RuleCompiler.Program program, ObjectNode context, String limit) {
        ObjectNode usage = usage(program, context);
        int current = RuleJson.integer(usage.get(limit), "/usage/" + limit, 0, 1000);
        if (current >= maxUses(program, context, limit))
            throw new Unavailable("limit", "No uses remain for this action.");
        usage.put(limit, current + 1);
    }

    static void refundUse(RuleCompiler.Program program, ObjectNode context, String limit) {
        ObjectNode usage = usage(program, context);
        int current = RuleJson.integer(usage.get(limit), "/usage/" + limit, 0, 1000);
        usage.put(limit, Math.max(0, current - 1));
    }

    static Prepared reserveCosts(RuleCompiler.Program program, Prepared preflight, ObjectNode currentContext) {
        return reserveCosts(program, preflight, currentContext, true);
    }

    static Prepared reserveCosts(RuleCompiler.Program program, Prepared preflight, ObjectNode currentContext,
                                 boolean reserveSourceCosts) {
        return reserveCosts(program, preflight, currentContext, reserveSourceCosts, null);
    }

    static Prepared reserveCosts(RuleCompiler.Program program, Prepared preflight, ObjectNode currentContext,
                                 boolean reserveSourceCosts, TrackObserver observer) {
        Intent intent = preflight.intent();
        ObjectNode rule = program.rule(intent.ruleId());
        ObjectNode next = preflight.context().deepCopy();
        if (!next.path("source").path("instanceId").asText().equals(currentContext.path("source").path("instanceId").asText()))
            throw new Unavailable("source", "The source instance changed. Reopen this action.");
        long revision = revision(currentContext);
        if (revision != preflight.revision() || revision != intent.expectedRevision())
            throw new Unavailable("stale", "This action is stale. Use its latest state.");
        for (String field : List.of("actor", "recipient", "source", "state", "usage", "sourcePools"))
            if (currentContext.has(field)) copyField(currentContext, next, field);
        if (observer != null && currentContext.has("event")) next.set("event", currentContext.get("event").deepCopy());
        RuleEffects.refreshCounts(program, next);
        if (reserveSourceCosts && rule.has("limitRef") && rule.path("limitSpend").asBoolean(true)) {
            reserveUse(program, next, rule.path("limitRef").asText());
        }
        if (reserveSourceCosts) {
            List<ObjectNode> published = new ArrayList<>(), rolls = new ArrayList<>();
            for (JsonNode cost : rule.path("costs")) operation(program, cost, next, null, false, published, rolls, observer);
        }
        return new Prepared(intent, next, revision);
    }

    static Resolution executePrepared(RuleCompiler.Program program, Prepared prepared, RandomGenerator random, boolean preview,
                                      Map<String, RuleCompiler.Program> programs) {
        return executePrepared(program, prepared, random, preview, programs, false);
    }

    static Resolution executePrepared(RuleCompiler.Program program, Prepared prepared, RandomGenerator random, boolean preview,
                                      Map<String, RuleCompiler.Program> programs, boolean stagedMove) {
        return executePrepared(program, prepared, random, preview, programs, stagedMove, null);
    }

    static Resolution executePrepared(RuleCompiler.Program program, Prepared prepared, RandomGenerator random, boolean preview,
                                      Map<String, RuleCompiler.Program> programs, boolean stagedMove, TrackObserver observer) {
        if (programs == null) throw RuleJson.error("/programs", "Expected a compiled program registry.");
        Intent intent = prepared.intent();
        ObjectNode rule = program.rule(intent.ruleId());
        if (rule.has("after") && !stagedMove)
            throw new Unavailable("restTransaction", "This benefit requires a complete staged downtime move.");
        ObjectNode next = copyContext(prepared.context());
        if (!next.path("source").path("instanceId").asText().equals(intent.sourceInstanceId()))
            throw new Unavailable("source", "The source instance changed. Reopen this action.");
        long revision = revision(next);
        if (revision != prepared.revision() || revision != intent.expectedRevision())
            throw new Unavailable("stale", "This action is stale. Use its latest state.");
        JsonNode binding = binding(rule, intent.bindingId());
        if (binding.has("forEach")) {
            ObjectNode pool = poolState(program, next, binding.path("forEach").asText());
            JsonNode selected = null;
            for (JsonNode die : pool.path("dice")) if (intent.eachId().equals(die.path("id").asText())) selected = die;
            if (selected == null) throw new Unavailable("poolItem", "This pool item was already consumed.");
            next.set("die", selected.deepCopy());
        }
        String text = preview && rule.path("ui").has("preview") ? "preview" : "report";
        return executeSteps(program, prepared.context(), next, rule.path("do"), rule.path("ui").path(text),
                random, preview, programs, revision, preview ? null : observer, intent, "do");
    }

    static Resolution executeAfter(RuleCompiler.Program program, Intent intent, ObjectNode hostContext,
                                   RandomGenerator random, Map<String, RuleCompiler.Program> programs) {
        return executeAfter(program, intent, hostContext, random, programs, null);
    }

    static Resolution executeAfter(RuleCompiler.Program program, Intent intent, ObjectNode hostContext,
                                   RandomGenerator random, Map<String, RuleCompiler.Program> programs, TrackObserver observer) {
        if (programs == null) throw RuleJson.error("/programs", "Expected a compiled program registry.");
        ObjectNode rule = program.rule(intent.ruleId());
        if (!rule.has("after")) throw RuleJson.error("/ruleId", "This move benefit has no deferred operations.");
        JsonNode binding = binding(rule, intent.bindingId());
        ObjectNode next = context(program, rule, hostContext);
        if (!intent.sourceInstanceId().equals(next.path("source").path("instanceId").asText()))
            throw new Unavailable("source", "The source instance changed. Reopen this rest.");
        long revision = revision(next);
        if (revision != intent.expectedRevision())
            throw new Unavailable("stale", "This move benefit is stale. Reopen this rest.");
        if (!binding.path("event").asText().equals(next.at("/event/type").asText()))
            throw new Unavailable("event", "This benefit does not apply to this move.");
        for (JsonNode cost : rule.path("after").path("costs"))
            operation(program, cost, next, null, false, new ArrayList<>(), new ArrayList<>(), observer);
        return executeSteps(program, next.deepCopy(), next, rule.path("after").path("do"),
                rule.path("after").path("report"), random, false, programs, revision, observer, intent, "after");
    }

    private static Resolution executeSteps(RuleCompiler.Program program, ObjectNode previous, ObjectNode next,
                                           JsonNode steps, JsonNode reportTemplate, RandomGenerator random,
                                           boolean preview, Map<String, RuleCompiler.Program> programs, long revision,
                                           TrackObserver observer, Intent intent, String phase) {
        List<ObjectNode> published = new ArrayList<>(), rolls = new ArrayList<>();
        boolean effectOperation = false;
        boolean pendingInventoryGrant = false;
        Set<String> recipientPools = next.has("recipient") && !program.boundPoolIds().isEmpty()
                ? new HashSet<>() : Set.of();
        for (int index = 0; index < steps.size(); index++) {
            JsonNode step = steps.get(index);
            if (pendingInventoryGrant && (inventoryReferences(RuleExpression.references(step))
                    || step.path("op").asText().startsWith("collection.")
                    && "inventory".equals(step.path("collection").asText())
                    || "inventory.consume".equals(step.path("op").asText())))
                throw new Unavailable("inventorySettlement",
                        "A later operation cannot use inventory before its grant has been settled.");
            if (step.has("if") && !RuleExpression.bool(RuleExpression.evaluate(step.get("if"), next), "/do/if")) continue;
            operation(program, step, next, random, preview, published, rolls, observer);
            if ("inventory.grant".equals(step.path("op").asText()))
                for (JsonNode command : next.path("outbox"))
                    pendingInventoryGrant |= "inventory.grant".equals(command.path("kind").asText());
            if ("plan.adjust".equals(step.path("op").asText())) {
                JsonNode plan = RuleExpression.read(next, step.path("plan").asText());
                ArrayNode adjustments = (ArrayNode) plan.path("adjustments");
                ((ObjectNode) adjustments.get(adjustments.size() - 1))
                        .put("ruleId", intent.ruleId()).put("bindingId", intent.bindingId())
                        .put("phase", phase).put("stepIndex", index);
            }
            effectOperation |= step.path("op").asText().startsWith("effect.");
            touchedRecipientPool(program, step, recipientPools);
        }
        ObjectNode beforeSettlement = observer == null ? null : ((ObjectNode) next.path("actor")).deepCopy();
        boolean effectEnded = RuleEffects.settle(program, next);
        if (effectOperation || effectEnded || !program.boundPoolIds().isEmpty())
            RuleEffects.reconcilePools(program, previous, next, programs, recipientPools);
        if (observer != null && (effectOperation || effectEnded || !beforeSettlement.equals(next.path("actor"))))
            observer.mutated(next, "effect.settle", beforeSettlement, "actor");
        if (pendingInventoryGrant && !reportTemplate.isMissingNode()
                && inventoryReferences(RuleTemplate.references(reportTemplate.asText())))
            throw new Unavailable("inventorySettlement",
                    "A report cannot use inventory before its grant has been settled.");
        String report = reportTemplate.isMissingNode() ? "" : RuleTemplate.render(reportTemplate.asText(), next);
        next.remove("die");
        next.set("revision", RuleExpression.numeric(revision + 1d, "/revision"));
        return new Resolution(next, report, List.copyOf(published), List.copyOf(rolls));
    }

    private static boolean inventoryReferences(Set<String> references) {
        return references.stream().anyMatch(path -> {
            for (String holder : List.of("actor", "recipient")) {
                if (path.equals(holder)) return true;
                for (String field : List.of("inventory", "stats", "derived", "effects", "pools")) {
                    String prefix = holder + "." + field;
                    if (path.equals(prefix) || path.startsWith(prefix + ".")) return true;
                }
            }
            return false;
        });
    }

    private static void touchedRecipientPool(RuleCompiler.Program program, JsonNode operation, Set<String> pools) {
        String kind = operation.path("op").asText(), id = operation.path("pool").asText();
        if ("recipient".equals(operation.path("owner").asText()) && ("pool.spend".equals(kind) || "pool.gain".equals(kind))
                && program.boundPoolIds().contains(id)) pools.add(id);
    }

    /** Clears used limits that reset on any of the given events, leaving pools untouched (a "refresh uses" host request). */
    public static ObjectNode refreshLimits(RuleCompiler.Program program, ObjectNode hostContext, Set<String> events) {
        RuleJson.checkTree(hostContext, "/context", 0, new int[]{0});
        if (!program.sourceId().equals(hostContext.path("source").path("id").asText()))
            throw RuleJson.error("/source/id", "Source definition does not match the program.");
        revision(hostContext);
        ObjectNode next = hostContext.deepCopy();
        ObjectNode usage = usage(program, next);
        boolean changed = false;
        var limits = program.limits().fields();
        while (limits.hasNext()) {
            var limit = limits.next();
            boolean reset = false;
            for (JsonNode declared : limit.getValue().path("resetOn")) if (events.contains(declared.asText())) reset = true;
            if (reset && RuleJson.integer(usage.path(limit.getKey()), "/usage/" + limit.getKey(), 0, 1000) != 0) {
                usage.put(limit.getKey(), 0);
                changed = true;
            }
        }
        if (changed) next.set("revision", RuleExpression.numeric(revision(next) + 1d, "/revision"));
        return next;
    }

    public static ObjectNode resetLimits(RuleCompiler.Program program, ObjectNode hostContext, String event) {
        RuleJson.checkTree(hostContext, "/context", 0, new int[]{0});
        if (!Set.of("rest.short", "rest.long", "scene.end", "session.start").contains(event))
            throw RuleJson.error("/event", "Unknown limit-reset event.");
        if (!program.sourceId().equals(hostContext.path("source").path("id").asText()))
            throw RuleJson.error("/source/id", "Source definition does not match the program.");
        RuleJson.id(hostContext.path("source").path("instanceId"), "/source/instanceId");
        revision(hostContext);
        ObjectNode next = hostContext.deepCopy();
        ObjectNode usage = usage(program, next);
        ObjectNode definitions = program.limits();
        boolean changed = false;
        var limits = definitions.fields();
        while (limits.hasNext()) {
            var limit = limits.next();
            boolean reset = false;
            for (JsonNode declared : limit.getValue().path("resetOn")) if (event.equals(declared.asText())) reset = true;
            int spent = RuleJson.integer(usage.path(limit.getKey()), "/usage/" + limit.getKey(), 0, 1000);
            if (reset && spent != 0) {
                usage.put(limit.getKey(), 0);
                changed = true;
            }
        }
        ObjectNode poolDefinitions = program.pools();
        ObjectNode resetContext = null;
        var pools = poolDefinitions.fields();
        while (pools.hasNext()) {
            var definition = pools.next();
            boolean poolReset = false;
            for (JsonNode declared : definition.getValue().path("resetOn")) if (event.equals(declared.asText())) poolReset = true;
            if (poolReset) {
                if (resetContext == null) resetContext = context(program, null, hostContext);
                boolean actorScoped = "actor".equals(definition.getValue().path("scope").asText());
                JsonNode originalOwner = actorScoped ? hostContext.path("actor").path("pools") : hostContext.path("sourcePools");
                JsonNode original = originalOwner.get(definition.getKey());
                ObjectNode baselinePool = poolState(program, resetContext, definition.getKey());
                ObjectNode pool = baselinePool.deepCopy();
                if ("number".equals(definition.getValue().path("kind").asText())) {
                    JsonNode resetTo = definition.getValue().get("resetTo");
                    int initial = resetTo == null ? definition.getValue().path("initial").asInt(0)
                            : (int) Math.max(0, Math.min(pool.path("max").asInt(),
                                    Math.floor(RuleExpression.number(RuleExpression.evaluate(resetTo, resetContext), "/pools/" + definition.getKey() + "/resetTo"))));
                    pool.put("value", initial);
                } else if (!pool.path("dice").isEmpty()) {
                    pool.putArray("dice");
                }
                if (original == null ? !baselinePool.equals(pool) : !original.equals(pool)) {
                    ObjectNode owner;
                    if (actorScoped) {
                        ObjectNode actor = RuleJson.object(next.path("actor"), "/actor");
                        owner = actor.has("pools") ? RuleJson.object(actor.get("pools"), "/actor/pools") : actor.putObject("pools");
                    } else {
                        owner = next.has("sourcePools") ? RuleJson.object(next.get("sourcePools"), "/sourcePools") : next.putObject("sourcePools");
                    }
                    owner.set(definition.getKey(), pool);
                    changed = true;
                }
            }
        }
        if (changed) next.set("revision", RuleExpression.numeric(revision(next) + 1d, "/revision"));
        return next;
    }

    private static void copyField(ObjectNode from, ObjectNode to, String field) {
        if ("recipient".equals(field) && from.get("actor") == from.get("recipient")) {
            to.set("recipient", to.get("actor"));
        } else to.set(field, from.get(field).deepCopy());
    }

    private static ObjectNode copyContext(ObjectNode from) {
        if (!from.has("recipient") || from.get("recipient") != from.get("actor")) return from.deepCopy();
        ObjectNode copy = from.objectNode();
        copy.set("actor", from.get("actor").deepCopy());
        from.fieldNames().forEachRemaining(field -> {
            if (!"actor".equals(field)) copyField(from, copy, field);
        });
        return copy;
    }

    static ObjectNode context(RuleCompiler.Program program, ObjectNode rule, ObjectNode hostContext) {
        return context(program, rule, hostContext, false);
    }

    static ObjectNode projectionContext(RuleCompiler.Program program, ObjectNode hostContext) {
        return context(program, null, hostContext, true);
    }

    private static ObjectNode context(RuleCompiler.Program program, ObjectNode rule, ObjectNode hostContext,
                                      boolean preserveComputedActorPools) {
        RuleJson.checkTree(hostContext, "/context", 0, new int[]{0});
        ObjectNode next = copyContext(hostContext);
        ObjectNode source = RuleJson.object(next.path("source"), "/source");
        if (!program.sourceId().equals(source.path("id").asText())) throw RuleJson.error("/source/id", "Source definition does not match the program.");
        RuleJson.id(source.get("instanceId"), "/source/instanceId");
        if (next.has("recipient")) RuleJson.object(next.get("recipient"), "/recipient");
        revision(next);
        initializeState(program, next);
        usage(program, next);
        initializePools(program, next, preserveComputedActorPools);
        next.set("args", rule == null ? RuleJson.JSON.createObjectNode() : rule.path("args").deepCopy());
        next.putObject("input");
        next.putObject("result");
        RuleEffects.refreshCounts(program, next);
        return next;
    }

    private static void initializeState(RuleCompiler.Program program, ObjectNode context) {
        ObjectNode state = program.initialState();
        if (context.has("state")) RuleJson.object(context.get("state"), "/state").fields().forEachRemaining(field -> {
            if (!state.has(field.getKey())) throw RuleJson.error("/state/" + field.getKey(), "Undeclared state slot.");
            state.set(field.getKey(), field.getValue().deepCopy());
        });
        context.set("state", state);
    }

    private static ObjectNode usage(RuleCompiler.Program program, ObjectNode next) {
        ObjectNode usage = next.has("usage") ? RuleJson.object(next.get("usage"), "/usage") : next.putObject("usage");
        ObjectNode limits = program.limits();
        usage.fields().forEachRemaining(field -> {
            if (!limits.has(field.getKey())) throw RuleJson.error("/usage/" + field.getKey(), "Undeclared use budget.");
            RuleJson.integer(field.getValue(), "/usage/" + field.getKey(), 0, 1000);
        });
        limits.fieldNames().forEachRemaining(key -> {
            if (!usage.has(key)) usage.put(key, 0);
        });
        return usage;
    }

    /** A computed pool capacity reads its owner as {@code actor}; it is floored and clamped to 0-1,000,000. */
    private static int computedMax(JsonNode expression, ObjectNode context, String owner, String id) {
        ObjectNode view = context;
        if (owner != null && !"actor".equals(owner)) {
            view = context.objectNode();
            view.setAll(context);
            view.set("actor", context.get(owner));
        }
        double value = RuleExpression.number(RuleExpression.evaluate(expression, view), "/automation/pools/" + id + "/max");
        return (int) Math.max(0, Math.min(1_000_000, Math.floor(value)));
    }

    static void clampComputedActorPools(RuleCompiler.Program program, ObjectNode context) {
        ObjectNode definitions = program.pools();
        boolean present = false;
        var candidates = definitions.fields();
        while (candidates.hasNext()) {
            var candidate = candidates.next();
            if ("actor".equals(candidate.getValue().path("scope").asText()) && !candidate.getValue().path("max").isInt()
                    && context.path("actor").path("pools").has(candidate.getKey())) {
                present = true;
                break;
            }
        }
        if (!present) return;
        if (program.computedActorPoolsReadSourcePools()) {
            ObjectNode initialized = context(program, null, context);
            ObjectNode actorPools = RuleJson.object(context.path("actor").path("pools"), "/actor/pools");
            definitions.fields().forEachRemaining(pool -> {
                if ("actor".equals(pool.getValue().path("scope").asText()) && !pool.getValue().path("max").isInt()
                        && actorPools.has(pool.getKey()))
                    actorPools.set(pool.getKey(), initialized.path("actor").path("pools").get(pool.getKey()).deepCopy());
            });
            return;
        }
        initializeState(program, context);
        var pools = definitions.fields();
        while (pools.hasNext()) {
            var pool = pools.next();
            if ("actor".equals(pool.getValue().path("scope").asText()) && !pool.getValue().path("max").isInt()
                    && context.path("actor").path("pools").has(pool.getKey()))
                poolState(program, context, pool.getKey());
        }
    }

    static void requireStableComputedActorPools(RuleCompiler.Program program, ObjectNode context) {
        ObjectNode check = context.deepCopy();
        clampComputedActorPools(program, check);
        if (!context.path("actor").path("pools").equals(check.path("actor").path("pools")))
            throw new Unavailable("computedPoolDependencies",
                    "Computed actor pool capacities are order-dependent or cyclic. Adjust their dependencies before using automation.");
    }

    private static void initializePools(RuleCompiler.Program program, ObjectNode context, boolean preserveComputedActorPools) {
        ObjectNode definitions = program.pools();
        var pools = definitions.fields();
        while (pools.hasNext()) {
            var pool = pools.next();
            if (preserveComputedActorPools && "actor".equals(pool.getValue().path("scope").asText())
                    && !pool.getValue().path("max").isInt()
                    && context.path("actor").path("pools").has(pool.getKey())) continue;
            poolState(program, context, pool.getKey());
        }
    }

    private static ObjectNode poolState(RuleCompiler.Program program, ObjectNode context, String id) {
        return poolState(program, context, id, null);
    }

    private static ObjectNode poolState(RuleCompiler.Program program, ObjectNode context, String id, String requestedOwner) {
        ObjectNode definition = program.pool(id);
        boolean actorScoped = "actor".equals(definition.path("scope").asText());
        if (requestedOwner != null && !actorScoped)
            throw RuleJson.error("/pool/owner", "Only actor-scoped pools can select an operation owner.");
        ObjectNode owner;
        String contextOwner = requestedOwner == null ? (actorScoped ? "actor" : null) : requestedOwner;
        if (contextOwner != null) {
            ObjectNode actor = RuleJson.object(context.path(contextOwner), "/" + contextOwner);
            owner = actor.has("pools") ? RuleJson.object(actor.get("pools"), "/" + contextOwner + "/pools") : actor.putObject("pools");
        } else {
            owner = context.has("sourcePools") ? RuleJson.object(context.get("sourcePools"), "/sourcePools") : context.putObject("sourcePools");
        }
        String statePath = contextOwner == null ? "sourcePools/" : contextOwner + "/pools/";
        ObjectNode pool = owner.has(id) ? RuleJson.object(owner.get(id), "/" + statePath + id)
                : owner.putObject(id);
        String path = "/" + statePath + id;
        boolean computed = !definition.path("max").isInt();
        int max = computed ? computedMax(definition.get("max"), context, contextOwner, id)
                : RuleJson.integer(definition.path("max"), "/automation/pools/" + id + "/max", 1,
                        "dice".equals(definition.path("kind").asText()) ? 1024 : 1_000_000);
        if ("number".equals(definition.path("kind").asText())) {
            RuleJson.fields(pool, path, "value", "max");
            if (!pool.has("value")) pool.put("value", Math.min(max, definition.path("initial").asInt(0)));
            if (computed) {
                // A computed capacity follows the actor, so the stored value is clamped to the current capacity.
                int value = RuleJson.integer(pool.get("value"), path + "/value", 0, 1_000_000);
                pool.put("max", max).put("value", Math.min(value, max));
            }
            if (!pool.has("max")) pool.put("max", max);
            int storedMax = RuleJson.integer(pool.get("max"), path + "/max", computed ? 0 : 1, 1_000_000);
            if (storedMax != max) throw RuleJson.error(path + "/max", "Pool definition changed; migrate the stored pool.");
            RuleJson.integer(pool.get("value"), path + "/value", 0, storedMax);
        } else {
            RuleJson.fields(pool, path, "nextId", "dice");
            if (!pool.has("nextId")) pool.put("nextId", 1);
            if (!pool.has("dice")) pool.putArray("dice");
            RuleJson.integer(pool.get("nextId"), path + "/nextId", 1, Integer.MAX_VALUE);
            JsonNode dice = pool.get("dice");
            if (!dice.isArray() || dice.size() > max) throw RuleJson.error(path + "/dice", "Pool exceeds its configured capacity.");
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < dice.size(); i++) {
                JsonNode die = dice.get(i);
                String at = path + "/dice/" + i;
                RuleJson.fields(die, at, "id", "sides", "face", "kept", "origin");
                String dieId = RuleJson.id(die.get("id"), at + "/id");
                if (!ids.add(dieId)) throw RuleJson.error(at + "/id", "Duplicate pool die ID.");
                RuleJson.integer(die.get("sides"), at + "/sides", 2, 1000);
                RuleJson.integer(die.get("face"), at + "/face", 1, die.path("sides").asInt());
                if (!RuleExpression.bool(die.get("kept"), at + "/kept") || !"stored".equals(die.path("origin").asText()))
                    throw RuleJson.error(at, "Pool entries must be kept stored dice.");
            }
        }
        return pool;
    }

    static long revision(ObjectNode context) {
        double value = RuleExpression.number(context.get("revision"), "/revision");
        if (value < 0 || value != Math.rint(value)) throw RuleJson.error("/revision", "Expected a nonnegative revision.");
        return (long) value;
    }

    private static JsonNode binding(ObjectNode rule, String id) {
        for (JsonNode binding : rule.path("bindings")) if (id.equals(binding.path("id").asText())) return binding;
        throw RuleJson.error("/bindingId", "Unknown binding.");
    }

    private static void available(RuleCompiler.Program program, ObjectNode rule, JsonNode binding, ObjectNode context) {
        if (!binding.path("event").asText().equals(context.path("event").path("type").asText()))
            throw new Unavailable("event", "This action does not apply to this event.");
        if (isRestEvent(binding.path("event").asText())) {
            String phase = restPhase(context.path("event"));
            if (!phase.equals(binding.path("restPhase").asText("beforeReset")))
                throw new Unavailable("restPhase", "This benefit applies during a different rest phase.");
        }
        for (JsonNode def : List.of(rule, binding))
            if (def.has("when") && !RuleExpression.bool(eval(def.get("when"), context), "/when"))
                throw new Unavailable("condition", unavailableText(rule, context, "This action is not currently available."));
        if (rule.has("limitRef")) {
            String limit = rule.path("limitRef").asText();
            if (context.path("usage").path(limit).asInt() >= maxUses(program, context, limit))
                throw new Unavailable("limit", "No uses remain for this action.");
        }
    }

    static boolean isRestEvent(String type) {
        return "rest.short".equals(type) || "rest.long".equals(type);
    }

    static String restPhase(JsonNode event) {
        JsonNode rest = event.path("rest");
        if (!rest.isMissingNode() && !rest.isObject())
            throw RuleJson.error("/event/rest", "Expected a rest object.");
        JsonNode supplied = rest.path("phase");
        String phase = supplied.isMissingNode() ? "beforeReset" : RuleJson.text(supplied, "/event/rest/phase");
        if (!Set.of("beforeReset", "afterReset").contains(phase))
            throw RuleJson.error("/event/rest/phase", "Expected beforeReset or afterReset.");
        return phase;
    }

    private static String unavailableText(ObjectNode rule, ObjectNode context, String fallback) {
        return rule.path("ui").has("unavailable") ? RuleTemplate.render(rule.path("ui").path("unavailable").asText(), context) : fallback;
    }

    private static boolean preflightCosts(RuleCompiler.Program program, ObjectNode rule, ObjectNode context) {
        ObjectNode preview = context.deepCopy();
        for (JsonNode cost : rule.path("costs")) {
            if (RuleExpression.references(cost.path("amount")).stream().anyMatch(path -> path.startsWith("input."))) return true;
            operation(program, cost, preview, null, true, new ArrayList<>(), new ArrayList<>());
        }
        return false;
    }

    private static void preflightAfterCosts(RuleCompiler.Program program, ObjectNode rule, ObjectNode context) {
        JsonNode costs = rule.path("after").path("costs");
        if (costs.isEmpty()) return;
        ObjectNode preview = context.deepCopy();
        for (JsonNode cost : costs)
            operation(program, cost, preview, null, true, new ArrayList<>(), new ArrayList<>());
    }

    private static ArrayNode describeInputs(ObjectNode rule, ObjectNode context) {
        ArrayNode out = RuleJson.JSON.createArrayNode();
        for (JsonNode input : rule.path("inputs")) {
            ObjectNode descriptor = RuleJson.JSON.createObjectNode();
            descriptor.put("id", input.path("id").asText()).put("kind", input.path("kind").asText())
                    .put("label", RuleTemplate.render(input.path("label").asText(), context));
            if ("integer".equals(input.path("kind").asText())) {
                int min = whole(input.path("min"), context, -1_000_000, 1_000_000);
                int max = whole(input.path("max"), context, min, 1_000_000);
                descriptor.put("min", min).put("max", max);
                if (input.has("default")) descriptor.put("default", Math.max(min, Math.min(max, input.path("default").asInt())));
            } else if ("choice".equals(input.path("kind").asText())) {
                int count = input.path("count").asInt(1);
                ArrayNode options = descriptor.putArray("options").addAll(options(input, context));
                if (options.size() < count) throw new Unavailable("selection", "Not enough options are currently available.");
                descriptor.put("count", count);
            } else if ("record.select".equals(input.path("kind").asText())) {
                JsonNode records = eval(input.path("from"), context);
                if (!records.isArray() || records.size() > 64)
                    throw RuleJson.error("/inputs/from", "Expected up to 64 selectable records.");
                ArrayNode options = descriptor.putArray("options");
                Set<JsonNode> distinct = new HashSet<>();
                for (JsonNode record : records) {
                    if (!record.isObject() || record.isEmpty() || record.size() > 16)
                        throw RuleJson.error("/inputs/from", "Selectable records must have 1-16 scalar fields.");
                    record.fields().forEachRemaining(field -> {
                        if (!field.getValue().isValueNode())
                            throw RuleJson.error("/inputs/from", "Selectable record fields must be scalar.");
                        if (field.getValue().isNumber()) RuleExpression.number(field.getValue(), "/inputs/from");
                    });
                    if (!distinct.add(record)) continue;
                    ObjectNode display = context.deepCopy();
                    display.withObject("/input").set("option", record);
                    ObjectNode option = options.addObject()
                            .put("label", RuleTemplate.render(input.path("choiceLabel").asText(), display));
                    option.set("value", record.deepCopy());
                }
                if (options.isEmpty()) throw new Unavailable("selection", "No options are currently available.");
            } else if ("allocation".equals(input.path("kind").asText())) {
                ArrayNode options = descriptor.putArray("options").addAll(options(input, context));
                int total = whole(input.path("total"), context, 0, 1_000);
                int max = input.has("max") ? whole(input.path("max"), context, 0, 1_000) : total;
                if (options.isEmpty() || (long) max * options.size() < total)
                    throw new Unavailable("selection", "The amount cannot be allocated to the available options.");
                descriptor.put("total", total).put("max", max);
            } else {
                int count = whole(input.path("count"), context, 1, 1024);
                int min = input.has("min") ? whole(input.path("min"), context, 0, count) : count;
                JsonNode from = eval(input.path("from"), context);
                Set<String> eligible = input.has("where")
                        ? StructuredRoll.ids(StructuredRoll.select(from, die -> RuleExpression.bool(perDie(input.path("where"), die, context), "/inputs/where")), "/inputs/where", false)
                        : StructuredRoll.ids(eval(input.path("eligible"), context), "/inputs/eligible", true);
                ArrayNode all = StructuredRoll.dice(from, null, true);
                ArrayNode choices = descriptor.putArray("choices");
                List<JsonNode> sorted = new ArrayList<>();
                for (JsonNode die : all) if (eligible.contains(die.path("id").asText())) {
                    ObjectNode item = die.deepCopy();
                    ObjectNode display = context.deepCopy();
                    display.set("die", die);
                    item.put("label", RuleTemplate.render(input.path("choiceLabel").asText(), display));
                    choices.add(item);
                    sorted.add(die);
                }
                if (choices.size() != eligible.size()) throw RuleJson.error("/inputs/eligible", "Unknown, excluded or dropped eligible die.");
                if (choices.size() < Math.max(min, 1)) throw new Unavailable("selection", "Not enough eligible dice remain.");
                // With a minimum, count is an upper bound capped by the dice actually available.
                if (input.has("min")) count = Math.min(count, choices.size());
                descriptor.put("count", count);
                if (input.has("min")) descriptor.put("min", min);
                if (input.has("default")) {
                    sorted.sort(Comparator.comparingInt(die -> die.path("face").asInt()));
                    ArrayNode defaults = descriptor.putArray("default");
                    for (int i = 0; i < min; i++) defaults.add(sorted.get(i).path("id"));
                }
            }
            out.add(descriptor);
        }
        return out;
    }

    /** Static options filtered by {@code when}, or host-derived {@code optionsFrom} items reduced to {@code {id, label}}. */
    private static ArrayNode options(JsonNode input, ObjectNode context) {
        ArrayNode out = RuleJson.JSON.createArrayNode();
        if (input.has("optionsFrom")) {
            JsonNode items = eval(input.get("optionsFrom"), context);
            if (!items.isArray() || items.size() > 64) throw RuleJson.error("/inputs/optionsFrom", "Expected up to 64 options.");
            Set<String> ids = new HashSet<>();
            for (JsonNode item : items) {
                String optionId = RuleJson.id(item.path("id"), "/inputs/optionsFrom/id");
                if (!ids.add(optionId)) throw RuleJson.error("/inputs/optionsFrom", "Duplicate option ID.");
                out.addObject().put("id", optionId).put("label", RuleJson.text(item.path("label"), "/inputs/optionsFrom/label"));
            }
            return out;
        }
        for (JsonNode option : input.path("options")) {
            if (option.has("when") && !RuleExpression.bool(eval(option.get("when"), context), "/inputs/options/when")) continue;
            ObjectNode entry = out.addObject().put("id", option.path("id").asText())
                    .put("label", RuleTemplate.render(option.path("label").asText(), context));
            if (option.has("group")) entry.put("group", option.get("group").asText());
        }
        return out;
    }

    private static void validateInputs(ObjectNode rule, ObjectNode context, ObjectNode supplied) {
        RuleJson.checkTree(supplied, "/inputs", 0, new int[]{0});
        Set<String> keys = new HashSet<>();
        for (JsonNode def : rule.path("inputs")) keys.add(def.path("id").asText());
        supplied.fieldNames().forEachRemaining(key -> {
            if (!keys.contains(key)) throw RuleJson.error("/inputs/" + key, "Unknown input.");
        });
        ObjectNode accepted = supplied.deepCopy();
        // All choices are derived from pre-action state, never from unvalidated sibling inputs.
        for (JsonNode descriptor : describeInputs(rule, context)) {
            String id = descriptor.path("id").asText();
            JsonNode value = supplied.get(id);
            if (value == null) throw RuleJson.error("/inputs/" + id, "Required input is missing.");
            if ("integer".equals(descriptor.path("kind").asText()))
                RuleJson.integer(value, "/inputs/" + id, descriptor.path("min").asInt(), descriptor.path("max").asInt());
            else if ("allocation".equals(descriptor.path("kind").asText())) {
                ObjectNode allocation = RuleJson.object(value, "/inputs/" + id);
                ObjectNode normalized = RuleJson.JSON.createObjectNode();
                int max = descriptor.path("max").asInt(), sum = 0;
                for (JsonNode option : descriptor.path("options")) {
                    String optionId = option.path("id").asText();
                    int amount = allocation.has(optionId) ? RuleJson.integer(allocation.get(optionId), "/inputs/" + id + "/" + optionId, 0, max) : 0;
                    normalized.put(optionId, amount);
                    sum += amount;
                }
                allocation.fieldNames().forEachRemaining(key -> {
                    if (!normalized.has(key)) throw RuleJson.error("/inputs/" + id + "/" + key, "Unknown allocation option.");
                });
                if (sum != descriptor.path("total").asInt())
                    throw RuleJson.error("/inputs/" + id, "Allocate exactly " + descriptor.path("total").asInt() + ".");
                accepted.set(id, normalized);
            } else if ("choice".equals(descriptor.path("kind").asText())) {
                int count = descriptor.path("count").asInt();
                Set<String> allowed = new HashSet<>();
                Map<String, String> groups = new java.util.HashMap<>();
                descriptor.path("options").forEach(option -> {
                    allowed.add(option.path("id").asText());
                    if (option.has("group")) groups.put(option.path("id").asText(), option.path("group").asText());
                });
                Set<String> selected = new HashSet<>();
                if (count == 1 && value.isTextual()) selected.add(value.asText());
                else if (count > 1 && value.isArray()) for (JsonNode item : value) if (item.isTextual()) selected.add(item.asText());
                int supplied1 = value.isArray() ? value.size() : 1;
                if (selected.size() != count || supplied1 != count || !allowed.containsAll(selected))
                    throw RuleJson.error("/inputs/" + id, "Choose " + count + " distinct available option(s).");
                Set<String> usedGroups = new HashSet<>();
                for (String choice : selected)
                    if (groups.containsKey(choice) && !usedGroups.add(groups.get(choice)))
                        throw RuleJson.error("/inputs/" + id, "Choose at most one option from each group.");
            } else if ("record.select".equals(descriptor.path("kind").asText())) {
                JsonNode selected = null;
                for (JsonNode option : descriptor.path("options"))
                    if (option.path("value").equals(value)) { selected = option.path("value"); break; }
                if (selected == null)
                    throw new Unavailable("selection", "This selection changed. Reopen the action and choose again.");
                accepted.set(id, selected.deepCopy());
            } else {
                Set<String> selected = StructuredRoll.ids(value, "/inputs/" + id, true);
                Set<String> choices = new HashSet<>();
                descriptor.path("choices").forEach(die -> choices.add(die.path("id").asText()));
                int most = descriptor.path("count").asInt(), least = descriptor.path("min").asInt(most);
                if (selected.size() < least || selected.size() > most || value.size() != selected.size() || !choices.containsAll(selected))
                    throw RuleJson.error("/inputs/" + id, "Choose the required number of distinct eligible dice.");
            }
        }
        context.set("input", accepted);
    }

    private static void operation(RuleCompiler.Program program, JsonNode op, ObjectNode context, RandomGenerator random, boolean preview,
                                  List<ObjectNode> published, List<ObjectNode> rolls) {
        operation(program, op, context, random, preview, published, rolls, null);
    }

    private static void operation(RuleCompiler.Program program, JsonNode op, ObjectNode context, RandomGenerator random, boolean preview,
                                  List<ObjectNode> published, List<ObjectNode> rolls, TrackObserver observer) {
        String kind = op.path("op").asText();
        if (observer != null && (("recipient".equals(op.path("target").asText())
                && (kind.startsWith("effect.") || kind.startsWith("collection.")))
                || ("recipient".equals(op.path("owner").asText())
                && (kind.startsWith("pool.") || "roll.addPoolDie".equals(kind)))
                || (kind.startsWith("resource.") && op.path("pool").asText().startsWith("recipient."))
                || ("flag.set".equals(kind) && op.path("flag").asText().startsWith("recipient."))
                || (kind.startsWith("track.") && (op.path("track").asText().startsWith("recipient.")
                || op.path("counter").asText().startsWith("recipient.")))))
            observer.mutated(context, kind, null, "recipient");
        String previousHolder = kind.startsWith("effect.") && "recipient".equals(op.path("target").asText())
                ? "recipient" : "actor";
        ObjectNode previousActor = observer != null && (kind.startsWith("effect.")
                || Set.of("pool.spend", "pool.gain").contains(kind) && "recipient".equals(op.path("owner").asText())
                || Set.of("collection.add", "collection.remove").contains(kind) && "recipient".equals(op.path("target").asText())
                || "flag.set".equals(kind) && op.path("flag").asText().startsWith("recipient."))
                ? ((ObjectNode) context.path(previousHolder)).deepCopy() : null;
        JsonNode result;
        switch (kind) {
            case "flag.set" -> {
                String path = op.path("flag").asText();
                String[] parts = path.split("\\.");
                ObjectNode holder = RuleJson.object(context.get(parts[0]), "/" + parts[0]);
                RuleExpression.bool(holder.get(parts[1]), "/" + path);
                boolean value = RuleExpression.bool(eval(op.path("value"), context), "/value");
                holder.put(parts[1], value);
                result = RuleJson.JSON.getNodeFactory().booleanNode(value);
            }
            case "pool.spend", "pool.gain" -> {
                String id = op.path("pool").asText();
                ObjectNode state = poolState(program, context, id, op.has("owner") ? op.path("owner").asText() : null);
                int current = RuleJson.integer(state.get("value"), "/pool/" + id + "/value", 0, state.path("max").asInt());
                int amount = whole(op.path("amount"), context, 0, 1_000_000);
                int applied;
                if ("pool.spend".equals(kind)) {
                    if (amount > current) throw new Unavailable("cost", "Not enough " + id + " to pay the cost.");
                    applied = amount;
                } else applied = Math.min(amount, state.path("max").asInt() - current);
                state.put("value", current + ("pool.spend".equals(kind) ? -applied : applied));
                result = RuleExpression.numeric(applied, "/result");
            }
            case "pool.roll" -> {
                if (preview) throw new Unavailable("randomPreview", "This preview requires a committed roll; no dice were rolled.");
                String id = op.path("pool").asText();
                ObjectNode pool = poolState(program, context, id, op.has("owner") ? op.path("owner").asText() : null);
                int count = whole(op.path("count"), context, 1, 1024);
                int sides = whole(op.path("sides"), context, 2, 1000);
                int keep = op.has("keepHighest") ? whole(op.path("keepHighest"), context, 1, count) : count;
                int available = program.pool(id).path("max").asInt() - pool.path("dice").size();
                if (keep > available) throw new Unavailable("poolCapacity", "Not enough space remains in " + id + ".");
                int previouslyRolled = rolls.stream().mapToInt(RuleRuntime::rolledDice).sum();
                if (count + previouslyRolled > 1024) throw RuleJson.error("/dice", "Action exceeds its dice limit.");
                if (random == null) throw RuleJson.error("/dice", "Host random generator is required.");
                ArrayNode faces = RuleJson.JSON.createArrayNode();
                List<Integer> values = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    int face = random.nextInt(sides) + 1;
                    values.add(face);
                    faces.add(face);
                }
                List<Integer> order = new ArrayList<>();
                for (int i = 0; i < count; i++) order.add(i);
                order.sort(Comparator.comparingInt((Integer index) -> values.get(index)).reversed());
                Set<Integer> kept = new HashSet<>(order.subList(0, keep));
                ObjectNode roll = RuleJson.JSON.createObjectNode().put("pool", id);
                roll.set("faces", faces);
                ArrayNode stored = roll.putArray("dice");
                ArrayNode poolDice = (ArrayNode) pool.path("dice");
                long nextId = pool.path("nextId").longValue();
                if (nextId > Integer.MAX_VALUE - keep) throw RuleJson.error("/pool/" + id + "/nextId", "Pool die identity limit reached.");
                for (int i = 0; i < count; i++) if (kept.contains(i)) {
                    ObjectNode die = RuleJson.JSON.createObjectNode().put("id", "d" + nextId++)
                            .put("sides", sides).put("face", values.get(i)).put("kept", true).put("origin", "stored");
                    poolDice.add(die);
                    stored.add(die.deepCopy());
                }
                pool.put("nextId", nextId);
                rolls.add(roll);
                result = roll;
            }
            case "pool.removeDie" -> {
                String id = op.path("pool").asText();
                ObjectNode pool = poolState(program, context, id, op.has("owner") ? op.path("owner").asText() : null);
                String dieId = RuleJson.text(eval(op.path("dieId"), context), "/pool/" + id + "/dieId");
                ArrayNode dice = (ArrayNode) pool.path("dice");
                int index = -1;
                for (int i = 0; i < dice.size(); i++) if (dieId.equals(dice.path(i).path("id").asText())) index = i;
                if (index < 0) throw new Unavailable("poolItem", "This pool die is no longer available.");
                result = dice.remove(index);
            }
            case "resource.spend", "resource.gain", "track.mark", "track.clear" -> {
                boolean pool = kind.startsWith("resource");
                String path = op.path(pool ? "pool" : "track").asText();
                JsonNode target = RuleExpression.read(context, path);
                if (pool && target != null && target.isNumber()) {
                    // A plain numeric fact (for example gold) is an uncapped pool written back in place.
                    double current = target.asDouble();
                    if (current < 0 || current != Math.rint(current)) throw RuleJson.error("/" + path, "Expected a nonnegative whole resource value.");
                    int amount = whole(op.path("amount"), context, 0, 1_000_000);
                    if ("resource.spend".equals(kind) && amount > current) throw new Unavailable("cost", "Not enough " + path + " to pay the cost.");
                    String[] parts = path.split("\\.");
                    JsonNode owner = context;
                    for (int i = 0; i < parts.length - 1; i++) owner = owner.path(parts[i]);
                    JsonNode value = RuleExpression.numeric(current + ("resource.spend".equals(kind) ? -amount : amount), "/" + path);
                    RuleJson.object(owner, "/" + path).set(parts[parts.length - 1], value);
                    if (observer != null) {
                        ObjectNode before = RuleJson.JSON.createObjectNode().set("value", target);
                        ObjectNode after = RuleJson.JSON.createObjectNode().set("value", value);
                        trackChanged(observer, context, path, "resource.spend".equals(kind) ? "spend" : "gain", amount, before, after);
                    }
                    result = RuleExpression.numeric(amount, "/result");
                    break;
                }
                ObjectNode track = RuleJson.object(target, "/" + path);
                String field = pool ? "value" : "marked";
                double current = RuleExpression.number(track.get(field), "/" + path + "/" + field);
                if (current < 0 || current != Math.rint(current)) throw RuleJson.error("/" + path, "Expected a nonnegative whole resource value.");
                boolean uncapped = pool && !track.has("max");
                double cap = uncapped ? 0 : RuleExpression.number(track.get(pool ? "max" : "capacity"), "/" + path);
                if (!uncapped && (cap < current || cap != Math.rint(cap))) throw RuleJson.error("/" + path, "Invalid resource capacity.");
                int amount = whole(op.path("amount"), context, 0, 1_000_000);
                double applied, value;
                if ("resource.spend".equals(kind)) {
                    if (amount > current) throw new Unavailable("cost", "Not enough " + path + " to pay the cost.");
                    applied = amount; value = current - amount;
                } else if ("track.mark".equals(kind)) {
                    if (amount > cap - current) throw new Unavailable("cost", "Not enough free slots in " + path + ".");
                    applied = amount; value = current + amount;
                } else if ("track.clear".equals(kind)) {
                    ObjectNode owner = RuleJson.object(context.path(path.substring(0, path.indexOf('.'))), "/" + path);
                    int floor = SheetProjection.markedFloor(owner, track, (int) cap, "/" + path);
                    applied = Math.min(amount, Math.max(0, current - floor)); value = current - applied;
                } else {
                    applied = uncapped ? amount : Math.min(amount, cap - current); value = current + applied;
                }
                ObjectNode before = observer == null ? null : track.deepCopy();
                track.set(field, RuleExpression.numeric(value, "/" + path));
                String change = switch (kind) {
                    case "resource.spend" -> "spend";
                    case "resource.gain" -> "gain";
                    case "track.mark" -> "mark";
                    default -> "clear";
                };
                trackChanged(observer, context, path, change, amount, before, track);
                result = RuleExpression.numeric(applied, "/result");
            }
            case "inventory.consume" -> {
                ObjectNode source = RuleJson.object(context.path("source"), "/source");
                int count = RuleJson.integer(source.get("quantity"), "/source/quantity", 0, 1_000_000);
                int amount = whole(op.path("amount"), context, 1, 1_000_000);
                if (amount > count) throw new Unavailable("stock", "Not enough of this item remains.");
                source.put("quantity", count - amount);
                result = RuleExpression.numeric(amount, "/result");
            }
            case "source.set" -> {
                ObjectNode source = RuleJson.object(context.path("source"), "/source");
                String field = op.path("field").asText();
                ObjectNode declared = program.sourceFields() == null ? null : (ObjectNode) program.sourceFields().get(field);
                if (declared == null) throw RuleJson.error("/field", "Undeclared source field: " + field);
                JsonNode value = eval(op.path("value"), context);
                int whole = RuleJson.integer(value, "/value", declared.path("min").asInt(), declared.path("max").asInt());
                source.put(field, whole);
                result = RuleExpression.numeric(whole, "/result");
            }
            case "dice.table" -> {
                if (preview) throw new Unavailable("randomPreview", "This preview requires a committed roll; no dice were rolled.");
                int count = whole(op.path("count"), context, 0, 100);
                JsonNode outcomes = op.path("outcomes");
                int sides = outcomes.size();
                int previouslyRolled = rolls.stream().mapToInt(RuleRuntime::rolledDice).sum();
                boolean keepHigher = keepHigherRestRoll(context);
                if (count * (keepHigher ? 2 : 1) + previouslyRolled > 1024)
                    throw RuleJson.error("/dice", "Action exceeds its dice limit.");
                if (random == null) throw RuleJson.error("/dice", "Host random generator is required.");
                ObjectNode roll = RuleJson.JSON.createObjectNode().put("sides", sides);
                ArrayNode faces = roll.putArray("faces");
                ArrayNode alternatives = keepHigher ? roll.putArray("alternateFaces") : null;
                ObjectNode table = RuleJson.JSON.createObjectNode();
                ArrayNode tableFaces = table.putArray("faces"), results = table.putArray("outcomes");
                int total = 0;
                for (int i = 0; i < count; i++) {
                    int face = random.nextInt(sides) + 1;
                    if (keepHigher) {
                        int alternate = random.nextInt(sides) + 1;
                        alternatives.add(Math.min(face, alternate));
                        face = Math.max(face, alternate);
                    }
                    faces.add(face); tableFaces.add(face); total += face;
                    results.add(RuleTemplate.render(outcomes.get(face - 1).asText(), context));
                }
                roll.put("total", total);
                rolls.add(roll);
                table.put("count", count);
                result = table;
            }
            case "dice.roll", "dice.hits", "dice.highest" -> {
                if (preview) throw new Unavailable("randomPreview", "This preview requires a committed roll; no dice were rolled.");
                int count = whole(op.path("count"), context, 1, 1024);
                int sides = whole(op.path("sides"), context, 2, 1000);
                int previouslyRolled = rolls.stream().mapToInt(RuleRuntime::rolledDice).sum();
                boolean keepHigher = keepHigherRestRoll(context);
                if (count * (keepHigher ? 2 : 1) + previouslyRolled > 1024)
                    throw RuleJson.error("/dice", "Action exceeds its dice limit.");
                if (random == null) throw RuleJson.error("/dice", "Host random generator is required.");
                int atLeast = "dice.hits".equals(kind) ? whole(op.path("atLeast"), context, 1, sides) : 0;
                ObjectNode roll = sampleDice(count, sides, random);
                if (keepHigher) {
                    ObjectNode alternate = sampleDice(count, sides, random);
                    if (diceScore(alternate, kind, atLeast) > diceScore(roll, kind, atLeast)) {
                        ObjectNode first = roll;
                        roll = alternate;
                        alternate = first;
                    }
                    roll.set("alternateFaces", alternate.path("faces").deepCopy());
                    roll.set("alternateTotal", alternate.path("total"));
                }
                ArrayNode faces = (ArrayNode) roll.get("faces");
                rolls.add(roll);
                if ("dice.hits".equals(kind)) {
                    int hits = 0;
                    for (JsonNode face : faces) if (face.asInt() >= atLeast) hits++;
                    roll.put("atLeast", atLeast).put("hits", hits);
                    result = RuleExpression.numeric(hits, "/result");
                } else if ("dice.highest".equals(kind)) {
                    int highest = 0;
                    for (JsonNode face : faces) highest = Math.max(highest, face.asInt());
                    roll.put("highest", highest);
                    result = RuleExpression.numeric(highest, "/result");
                } else result = RuleExpression.numeric(roll.path("total").asInt(), "/result");
            }
            case "recovery.offerRoll", "downtime.offerRoll" -> {
                String eventType = context.at("/event/type").asText();
                boolean shortRest = switch (eventType) {
                    case "downtime.move" -> "short".equals(context.at("/event/move/rest").asText());
                    case "rest.prepare", "rest.short", "downtime.completed", "track.changed" ->
                            "short".equals(context.at("/event/rest/kind").asText());
                    default -> false;
                };
                if (!shortRest)
                    throw new Unavailable("restRoll", "Downtime rolls can only be offered during a short rest.");
                if (rolls.isEmpty()) throw RuleJson.error("/roll", "A downtime offer needs a preceding roll.");
                JsonNode sampled = rolls.getLast();
                JsonNode value = eval(op.path("roll"), context);
                boolean recovery = "recovery.offerRoll".equals(kind);
                ObjectNode holder = RuleJson.object(context.get("actor"), "/actor");
                String label = RuleTemplate.render(op.path("label").asText(), context);
                ArrayNode outbox = context.has("outbox") ? (ArrayNode) context.get("outbox") : context.putArray("outbox");
                ArrayNode offered = RuleJson.JSON.createArrayNode();
                if (!recovery && sampled.has("contributions") && value.equals(sampled)) {
                    if (op.has("offset")) throw RuleJson.error("/offset", "A structured roll already contains its offsets.");
                    ObjectNode roll = StructuredRoll.validate(sampled).put("id", "downtime-" + java.util.UUID.randomUUID());
                    for (JsonNode part : roll.path("contributions"))
                        if ("dice".equals(part.path("kind").asText())) ((ObjectNode) part).put("role", "downtime");
                    int count = StructuredRoll.dice(roll, "downtime", true).size();
                    if (count > 32 || count > 0 && outbox.size() >= 32)
                        throw new Unavailable("restRoll", "A downtime action can offer at most 32 dice.");
                    if (count > 0) {
                        ObjectNode request = outbox.addObject().put("kind", kind).put("label", label)
                                .put("sourceActorId", RuleJson.id(context.at("/actor/id"), "/actor/id"))
                                .put("holderId", RuleJson.id(holder.get("id"), "/actor/id"));
                        request.set("roll", StructuredRoll.validate(roll));
                        offered.add(request.deepCopy());
                    }
                } else {
                    int sides = RuleJson.integer(sampled.get("sides"), "/roll/sides", 2, 1000);
                    JsonNode faces = sampled.path("faces");
                    if (!faces.isArray() || faces.isEmpty()
                            || !(value.equals(faces) || value.isNumber() && value.asDouble() == sampled.path("total").asDouble()))
                        throw RuleJson.error("/roll", "A downtime offer must reference the last sampled roll.");
                    boolean group = recovery || op.path("group").asBoolean();
                    double offsetValue = op.has("offset") ? RuleExpression.number(eval(op.get("offset"), context), "/offset") : 0;
                    if (offsetValue != Math.rint(offsetValue) || Math.abs(offsetValue) > 1_000_000 || offsetValue != 0 && !group)
                        throw RuleJson.error("/offset", "An offset requires a grouped roll and a bounded whole number.");
                    int offset = (int) offsetValue;
                    String trackName = null;
                    int remaining = 0;
                    if (recovery) {
                        String path = op.path("track").asText();
                        String[] parts = path.split("\\.", 2);
                        holder = RuleJson.object(context.get(parts[0]), "/" + parts[0]);
                        ObjectNode track = RuleJson.object(holder.get(parts[1]), "/" + path);
                        int capacity = RuleJson.integer(track.get("capacity"), "/" + path + "/capacity", 0, 1000);
                        int marked = RuleJson.integer(track.get("marked"), "/" + path + "/marked", 0, capacity);
                        remaining = Math.max(0, marked - SheetProjection.markedFloor(holder, track, capacity, "/" + path));
                        trackName = parts[1];
                    }
                    if (outbox.size() + faces.size() > 32)
                        throw new Unavailable("restRoll", "A downtime action can offer at most 32 dice.");
                    int groups = group ? 1 : faces.size();
                    for (int i = 0; i < groups; i++) {
                        ObjectNode roll = RuleJson.JSON.createObjectNode().put("id", "downtime-" + java.util.UUID.randomUUID());
                        ArrayNode contributions = roll.putArray("contributions");
                        ObjectNode contribution = contributions.addObject()
                                .put("id", "downtime").put("kind", "dice").put("role", "downtime")
                                .put("sourceId", program.sourceId()).put("label", label);
                        ArrayNode dice = contribution.putArray("dice");
                        int first = group ? 0 : i, last = group ? faces.size() : i + 1;
                        for (int j = first; j < last; j++)
                            dice.addObject().put("id", "downtime.d" + (j + 1))
                                    .put("sides", sides).put("face", faces.get(j).asInt()).put("kept", true).put("origin", "rolled");
                        if (offset != 0) contributions.addObject().put("id", "offset").put("kind", "flat")
                                .put("role", "downtime").put("sourceId", program.sourceId()).put("label", label)
                                .put("value", offset);
                        ObjectNode request = outbox.addObject().put("kind", kind).put("label", label)
                                .put("sourceActorId", RuleJson.id(context.at("/actor/id"), "/actor/id"))
                                .put("holderId", RuleJson.id(holder.get("id"), "/holder/id"));
                        if (recovery) request.put("track", trackName).put("remaining", remaining);
                        request.set("roll", StructuredRoll.validate(roll));
                        offered.add(request.deepCopy());
                    }
                }
                result = recovery ? offered.get(0) : offered;
            }
            case "state.set", "state.increment" -> {
                ObjectNode state = (ObjectNode) context.get("state");
                String slot = op.path("slot").asText();
                result = "state.set".equals(kind) ? eval(op.path("value"), context)
                        : RuleExpression.numeric(RuleExpression.number(state.get(slot), "/state/" + slot)
                        + RuleExpression.number(eval(op.path("by"), context), "/state/" + slot), "/state/" + slot);
                state.set(slot, result.deepCopy());
            }
            case "choice.set", "choice.clear" -> {
                ObjectNode actor = RuleJson.object(context.get("actor"), "/actor");
                ObjectNode choices = actor.path("choices").isObject() ? (ObjectNode) actor.get("choices") : actor.putObject("choices");
                String key = op.path("key").asText();
                if ("choice.clear".equals(kind)) {
                    choices.remove(key);
                    result = RuleJson.JSON.getNodeFactory().booleanNode(true);
                } else {
                    JsonNode value = eval(op.path("value"), context);
                    boolean text = value.isTextual() && !value.asText().isBlank() && value.asText().length() <= 64;
                    if (value.isArray() && !value.isEmpty() && value.size() <= 16) {
                        text = true;
                        for (JsonNode member : value) text &= member.isTextual() && !member.asText().isBlank() && member.asText().length() <= 64;
                    }
                    if (!text) throw RuleJson.error("/value", "A choice is 1-64 characters of text or a list of up to 16 such texts.");
                    if (!choices.has(key) && choices.size() >= 32) throw RuleJson.error("/key", "An actor keeps at most 32 choices.");
                    choices.set(key, value.deepCopy());
                    result = value;
                }
            }
            case "value" -> result = eval(op.path("value"), context);
            case "roll.resolve" -> {
                if (preview) throw new Unavailable("randomPreview", "This preview requires a committed roll; no dice were rolled.");
                int remaining = 1024 - rolls.stream().mapToInt(RuleRuntime::rolledDice).sum();
                boolean keepHigher = keepHigherRestRoll(context);
                JsonNode plan = eval(op.path("plan"), context);
                ObjectNode roll = StructuredRoll.resolve(plan, context, random, keepHigher ? remaining / 2 : remaining);
                ObjectNode alternate = null;
                if (keepHigher) {
                    alternate = StructuredRoll.resolve(plan, context, random, remaining / 2);
                    if (alternate.path("total").asDouble() > roll.path("total").asDouble()) {
                        ObjectNode first = roll;
                        roll = alternate;
                        alternate = first;
                    }
                }
                if (op.has("metadata")) {
                    ObjectNode metadata = roll.has("metadata") ? (ObjectNode) roll.get("metadata") : roll.putObject("metadata");
                    for (var entry : (Iterable<java.util.Map.Entry<String, JsonNode>>) () -> op.get("metadata").fields()) {
                        JsonNode value = eval(entry.getValue(), context);
                        if (!value.isValueNode()) throw RuleJson.error("/metadata/" + entry.getKey(), "Roll metadata values must be scalars.");
                        metadata.set(entry.getKey(), value);
                    }
                }
                if (observer != null) roll = observer.rolled(context, roll);
                if (alternate != null) rolls.add(alternate);
                rolls.add(roll);
                result = roll;
            }
            case "roll.capture" -> result = StructuredRoll.validate(eval(op.path("from"), context));
            case "roll.check" -> result = StructuredRoll.check(eval(op.path("from"), context));
            case "roll.addPoolDie" -> {
                String poolId = op.path("pool").asText();
                ObjectNode pool = poolState(program, context, poolId, op.has("owner") ? op.path("owner").asText() : null);
                String dieId = RuleJson.text(eval(op.path("dieId"), context), "/pool/" + poolId + "/dieId");
                ArrayNode dice = (ArrayNode) pool.path("dice");
                int index = -1;
                for (int i = 0; i < dice.size(); i++) if (dieId.equals(dice.path(i).path("id").asText())) index = i;
                if (index < 0) throw new Unavailable("poolItem", "This pool die is no longer available.");
                JsonNode die = dice.get(index).deepCopy();
                String label = RuleTemplate.render(op.path("label").asText(), context);
                result = StructuredRoll.addPoolDie(eval(op.path("from"), context), die, poolId,
                        program.sourceId(), op.path("role").asText(), label);
                dice.remove(index);
            }
            case "roll.keptDice" -> {
                ArrayNode ids = RuleJson.JSON.createArrayNode();
                StructuredRoll.dice(eval(op.path("from"), context), op.path("role").asText(), true)
                        .forEach(die -> ids.add(die.path("id")));
                result = ids;
            }
            case "set.subtract" -> {
                Set<String> from = StructuredRoll.ids(eval(op.path("from"), context), "/set/from", true);
                Set<String> items = StructuredRoll.ids(eval(op.path("items"), context), "/set/items", true);
                if (!from.containsAll(items)) throw RuleJson.error("/set/items", "Selection is not part of the original set.");
                from.removeAll(items);
                ArrayNode remaining = RuleJson.JSON.createArrayNode();
                from.forEach(remaining::add);
                result = remaining;
            }
            case "roll.derive" -> result = StructuredRoll.derive(eval(op.path("from"), context),
                    eval(op.path("keepSelectedWithin"), context), eval(op.path("keep"), context));
            case "roll.publish" -> {
                ObjectNode roll = StructuredRoll.validate(eval(op.path("roll"), context));
                published.add(roll);
                if (context.path("event").isObject()) ((ObjectNode) context.get("event")).set("roll", roll.deepCopy());
                result = roll.get("total");
            }
            case "roll.selectDice" -> result = StructuredRoll.select(eval(op.path("from"), context),
                    die -> RuleExpression.bool(perDie(op.path("where"), die, context), "/where"));
            case "roll.setFaces" -> result = StructuredRoll.editFaces(eval(op.path("from"), context), eval(op.path("dice"), context),
                    program.sourceId(), "set", die -> RuleJson.integer(perDie(op.path("face"), die, context), "/face", 1, 1000));
            case "roll.reroll" -> {
                if (preview) throw new Unavailable("randomPreview", "This preview requires a committed roll; no dice were rolled.");
                if (random == null) throw RuleJson.error("/dice", "Host random generator is required.");
                boolean higher = "higher".equals(op.path("choose").asText());
                boolean higherTotal = "higherTotal".equals(op.path("choose").asText());
                ArrayNode faces = RuleJson.JSON.createArrayNode();
                JsonNode selection = eval(op.path("dice"), context);
                if (selection.size() + rolls.stream().mapToInt(RuleRuntime::rolledDice).sum() > 1024)
                    throw RuleJson.error("/dice", "Action exceeds its dice limit.");
                ObjectNode original = StructuredRoll.validate(eval(op.path("from"), context));
                ObjectNode edited = StructuredRoll.editFaces(original, selection, program.sourceId(), "rerolled", die -> {
                    int face = random.nextInt(die.path("sides").asInt()) + 1;
                    faces.add(face);
                    return higher ? Math.max(face, die.path("face").asInt()) : face;
                });
                if (higherTotal && edited.path("total").asDouble() < original.path("total").asDouble())
                    edited = original;
                ObjectNode record = RuleJson.JSON.createObjectNode().put("rerollOf", edited.path("id").asText());
                record.set("faces", faces);
                rolls.add(record);
                result = edited;
            }
            case "roll.resize" -> {
                if (preview) throw new Unavailable("randomPreview", "This preview requires a committed roll; no dice were rolled.");
                JsonNode selection = eval(op.path("dice"), context);
                if (selection.size() + rolls.stream().mapToInt(RuleRuntime::rolledDice).sum() > 1024)
                    throw RuleJson.error("/dice", "Action exceeds its dice limit.");
                ObjectNode resized = StructuredRoll.resize(eval(op.path("from"), context), selection, program.sourceId(),
                        whole(op.path("sides"), context, 2, 1000), random);
                ArrayNode faces = RuleJson.JSON.createArrayNode();
                Set<String> wanted = new HashSet<>();
                selection.forEach(id -> wanted.add(id.asText()));
                for (JsonNode part : resized.path("contributions")) for (JsonNode die : part.path("dice"))
                    if (wanted.contains(die.path("id").asText())) faces.add(die.path("face"));
                if (!faces.isEmpty()) {
                    ObjectNode record = RuleJson.JSON.createObjectNode().put("rerollOf", resized.path("id").asText());
                    record.set("faces", faces);
                    rolls.add(record);
                }
                result = resized;
            }
            case "roll.addDice", "roll.addDicePerPool" -> {
                if (preview) throw new Unavailable("randomPreview", "This preview requires a committed roll; no dice were rolled.");
                int count = whole(op.path("count"), context, 0, 1024);
                if (count + rolls.stream().mapToInt(RuleRuntime::rolledDice).sum() > 1024)
                    throw RuleJson.error("/dice", "Action exceeds its dice limit.");
                ObjectNode from = StructuredRoll.validate(eval(op.path("from"), context));
                Set<String> before = new HashSet<>();
                for (JsonNode part : from.path("contributions")) for (JsonNode die : part.path("dice")) before.add(die.path("id").asText());
                int remaining = 1024 - rolls.stream().mapToInt(RuleRuntime::rolledDice).sum();
                ObjectNode added = "roll.addDicePerPool".equals(kind)
                        ? StructuredRoll.addDicePerPool(from, eval(op.path("dice"), context), count, program.sourceId(), random, remaining)
                        : StructuredRoll.addDice(from, eval(op.path("dice"), context), count, program.sourceId(), random);
                ArrayNode faces = RuleJson.JSON.createArrayNode();
                for (JsonNode part : added.path("contributions")) for (JsonNode die : part.path("dice"))
                    if (!before.contains(die.path("id").asText())) faces.add(die.path("face"));
                if (!faces.isEmpty()) {
                    ObjectNode record = RuleJson.JSON.createObjectNode().put("additionOf", added.path("id").asText());
                    record.set("faces", faces);
                    rolls.add(record);
                }
                result = added;
            }
            case "roll.dropLowest" -> result = StructuredRoll.dropLowest(eval(op.path("from"), context), eval(op.path("dice"), context),
                    whole(op.path("count"), context, 0, 1024));
            case "roll.dropLowestPerPool" -> result = StructuredRoll.dropLowestPerPool(eval(op.path("from"), context), eval(op.path("dice"), context),
                    whole(op.path("count"), context, 0, 1024));
            case "roll.explode" -> {
                if (preview) throw new Unavailable("randomPreview", "This preview requires a committed roll; no dice were rolled.");
                ObjectNode from = StructuredRoll.validate(eval(op.path("from"), context));
                Set<String> before = new HashSet<>();
                for (JsonNode part : from.path("contributions")) for (JsonNode die : part.path("dice")) before.add(die.path("id").asText());
                int remaining = Math.max(0, 1024 - rolls.stream().mapToInt(RuleRuntime::rolledDice).sum());
                ObjectNode exploded = StructuredRoll.explode(from, eval(op.path("dice"), context), program.sourceId(),
                        Math.min(op.path("maxDice").asInt(), remaining),
                        die -> RuleExpression.bool(perDie(op.path("when"), die, context), "/when"), random);
                ArrayNode faces = RuleJson.JSON.createArrayNode();
                for (JsonNode part : exploded.path("contributions")) for (JsonNode die : part.path("dice"))
                    if (!before.contains(die.path("id").asText())) faces.add(die.path("face"));
                ObjectNode record = RuleJson.JSON.createObjectNode().put("explosionOf", exploded.path("id").asText());
                record.set("faces", faces);
                rolls.add(record);
                result = exploded;
            }
            case "roll.diceTotal" -> result = RuleExpression.numeric(StructuredRoll.faceTotal(eval(op.path("from"), context), eval(op.path("dice"), context)), "/dice");
            case "roll.swapRoles" -> result = StructuredRoll.swapRoles(eval(op.path("from"), context),
                    op.path("first").asText(), op.path("second").asText());
            case "roll.discard" -> result = StructuredRoll.discard(eval(op.path("from"), context), op.path("role").asText());
            case "roll.annotate" -> {
                ObjectNode annotated = StructuredRoll.validate(eval(op.path("from"), context));
                JsonNode value = eval(op.path("value"), context);
                if (!value.isValueNode()) throw RuleJson.error("/value", "Roll metadata values must be scalars.");
                ObjectNode metadata = annotated.has("metadata") ? (ObjectNode) annotated.get("metadata") : annotated.putObject("metadata");
                metadata.set(op.path("field").asText(), value);
                result = annotated;
            }
            case "roll.contribute" -> {
                ObjectNode part = op.path("contribution").deepCopy();
                if (preview && "dice".equals(part.path("kind").asText()))
                    throw new Unavailable("randomPreview", "This preview requires a committed roll; no dice were rolled.");
                part.put("sourceId", program.sourceId()).put("label", RuleTemplate.render(part.path("label").asText(), context));
                int remaining = 1024 - rolls.stream().mapToInt(RuleRuntime::rolledDice).sum();
                if ("dice".equals(part.path("kind").asText()) && !part.has("until") && whole(part.path("count"), context, 0, 1024) > remaining)
                    throw RuleJson.error("/dice", "Action exceeds its dice limit.");
                JsonNode base = eval(op.path("from"), context);
                ObjectNode contributed = StructuredRoll.contribute(base, part, context, random,
                        op.path("beforeScales").asBoolean(false));
                if ("dice".equals(part.path("kind").asText())
                        && contributed.path("contributions").size() > base.path("contributions").size()) {
                    JsonNode added = null;
                    for (JsonNode candidate : contributed.path("contributions"))
                        if (candidate.path("id").equals(part.path("id"))) { added = candidate; break; }
                    if (added == null) throw RuleJson.error("/contribution/id", "The added contribution is missing.");
                    ObjectNode record = RuleJson.JSON.createObjectNode().put("contributionOf", contributed.path("id").asText());
                    ArrayNode faces = record.putArray("faces");
                    added.path("dice").forEach(die -> faces.add(die.path("face")));
                    rolls.add(record);
                }
                result = contributed;
            }
            case "effect.start" -> result = RuleEffects.start(program, op.path("effect").asText(), op.path("target").asText(), context);
            case "effect.end" -> result = RuleExpression.numeric(
                    RuleEffects.end(program, op.path("effect").asText(), op.path("target").asText(), context), "/result");
            case "effect.advance" -> result = RuleExpression.numeric(RuleEffects.advance(program, op.path("effect").asText(),
                    op.path("target").asText(), whole(op.path("by"), context, -1000, 1000), context), "/result");
            case "effect.endLabeled" -> result = RuleExpression.numeric(RuleEffects.endLabeled(program,
                    RuleTemplate.render(op.path("label").asText(), context), op.path("target").asText(), context), "/result");
            case "limit.reset" -> {
                ObjectNode usage = usage(program, context);
                String limit = op.path("limit").asText();
                result = RuleExpression.numeric(usage.path(limit).asInt(), "/result");
                usage.put(limit, 0);
            }
            case "damage.reduce", "damage.increase" -> {
                int amount = whole(op.path("amount"), context, 0, 1_000_000);
                String id = RuleTemplate.render(op.path("id").asText(), context);
                String label = RuleTemplate.render(op.path("label").asText(), context);
                boolean increase = "damage.increase".equals(kind);
                ObjectNode adjustment = increase
                        ? StructuredDamage.increase(eval(op.path("from"), context), amount, id, program.sourceId(), label)
                        : StructuredDamage.reduce(eval(op.path("from"), context), amount, id, program.sourceId(), label);
                if (adjustment.path("adjustment").path("applied").asInt() == 0)
                    throw new Unavailable("noBenefit", increase ? "This action would not increase any damage."
                            : "This action would not reduce any damage.");
                result = adjustment;
            }
            case "damage.publish" -> {
                ObjectNode damage = StructuredDamage.validate(eval(op.path("damage"), context));
                if (!context.path("event").isObject()) throw RuleJson.error("/damage", "Damage can only be published during an event.");
                ((ObjectNode) context.get("event")).set("damage", damage.deepCopy());
                result = damage.get("amount");
            }
            case "damage.resolve" -> result = StructuredDamage.resolve(eval(op.path("from"), context),
                    op.path("thresholds"), op.path("armor"), op.path("hitPoints"), context, observer);
            case "damage.assess" -> result = StructuredDamage.assess(eval(op.path("from"), context), op.path("type"),
                    op.path("thresholds"), op.path("massive"), op.path("armor"), op.path("hitPoints"), context);
            case "damage.without" -> result = StructuredDamage.without(eval(op.path("from"), context), program.sourceId());
            case "damage.adjust" -> {
                int to = whole(op.path("to"), context, -1000, 1000);
                ObjectNode adjusted = StructuredDamage.adjust(eval(op.path("from"), context), op.path("field").asText(), to,
                        RuleTemplate.render(op.path("id").asText(), context), program.sourceId(),
                        RuleTemplate.render(op.path("label").asText(), context));
                result = adjusted.get("marks");
            }
            case "action.reject" -> throw new Unavailable("condition", RuleTemplate.render(op.path("reason").asText(), context));
            case "event.block" -> {
                if (!(context.path("event") instanceof ObjectNode event)) throw RuleJson.error("/event", "Only events can be blocked.");
                ArrayNode blocked = event.has("blocked") && event.get("blocked").isArray()
                        ? (ArrayNode) event.get("blocked") : event.putArray("blocked");
                String reason = RuleTemplate.render(op.path("reason").asText(), context);
                blocked.addObject().put("sourceId", program.sourceId())
                        .put("sourceInstanceId", context.path("source").path("instanceId").asText()).put("reason", reason);
                result = RuleJson.JSON.getNodeFactory().textNode(reason);
            }
            case "plan.adjust" -> {
                String path = op.path("plan").asText();
                JsonNode found = RuleExpression.read(context, path);
                String field = op.path("field").asText();
                if (!(found instanceof ObjectNode plan) || !plan.path("adjustable").path(field).isObject())
                    throw new Unavailable("planField", "This event does not allow adjusting " + field + ".");
                JsonNode bounds = plan.path("adjustable").path(field);
                int min = bounds.path("min").asInt(0);
                int max = bounds.path("max").asInt(1000);
                int current = plan.path(field).asInt(0);
                int requested = whole(op.path("by"), context, -1000, 1000);
                int next = Math.max(min, Math.min(max, current + requested));
                plan.put(field, next);
                ArrayNode adjustments = plan.has("adjustments") && plan.get("adjustments").isArray()
                        ? (ArrayNode) plan.get("adjustments") : plan.putArray("adjustments");
                ObjectNode adjustment = adjustments.addObject().put("field", field).put("requested", requested).put("by", next - current)
                        .put("before", current).put("after", next).put("sourceId", program.sourceId())
                        .put("sourceInstanceId", context.path("source").path("instanceId").asText())
                        .put("label", RuleTemplate.render(op.path("label").asText(), context));
                if (op.has("partyRecovery")) adjustment.set("partyRecovery", op.get("partyRecovery").deepCopy());
                result = RuleExpression.numeric(next, "/result");
            }
            case "marks.publish" -> {
                ObjectNode marks = StructuredDamage.validatePlan(eval(op.path("marks"), context));
                if (!context.path("event").isObject()) throw RuleJson.error("/marks", "Marks can only be published during an event.");
                ((ObjectNode) context.get("event")).set("marks", marks.deepCopy());
                result = marks.path("hitPoints").get("marks");
            }
            case "damage.mark" -> {
                ObjectNode applied = StructuredDamage.mark(eval(op.path("from"), context), op.path("armor"),
                        op.path("hitPoints"), context, observer);
                if (context.path("event").isObject()) ((ObjectNode) context.get("event")).set("applied", applied.deepCopy());
                result = applied;
            }
            case "limit.modify" -> {
                ObjectNode usage = usage(program, context);
                String limit = op.path("limit").asText();
                int spent = usage.path(limit).asInt();
                int max = maxUses(program, context, limit);
                int by = whole(op.path("by"), context, -1000, 1000);
                int next = Math.max(0, Math.min(max, spent - by));
                usage.put(limit, next);
                result = RuleExpression.numeric(spent - next, "/result");
            }
            case "track.markPermanent" -> {
                String path = op.path("track").asText();
                ObjectNode track = RuleJson.object(RuleExpression.read(context, path), "/" + path);
                int capacity = RuleJson.integer(track.get("capacity"), "/" + path + "/capacity", 0, 1000);
                int marked = RuleJson.integer(track.get("marked"), "/" + path + "/marked", 0, capacity);
                int permanent = track.has("permanent") ? RuleJson.integer(track.get("permanent"), "/" + path + "/permanent", 0, capacity) : 0;
                int amount = whole(op.path("amount"), context, 0, 1_000_000);
                int lost = Math.min(amount, Math.max(0, capacity - permanent));
                ObjectNode before = observer == null ? null : track.deepCopy();
                track.put("permanent", permanent + lost);
                track.put("marked", Math.min(capacity, marked + lost));
                if (track.has("minimumCapacity")) track.put("minimumCapacity", permanent + lost);
                if (track.has("minimumMarkedBase")) track.put("minimumMarkedBase", track.path("minimumMarkedBase").asInt() + lost);
                if (lost > 0 && track.path("marked").asInt() != marked)
                    trackChanged(observer, context, path, "mark", track.path("marked").asInt() - marked, before, track);
                result = RuleExpression.numeric(lost, "/result");
            }
            case "track.capacity" -> {
                String path = op.path("track").asText();
                ObjectNode track = RuleJson.object(RuleExpression.read(context, path), "/" + path);
                String capacity = track.has("capacity") ? "capacity" : "max";
                String filled = track.has("marked") ? "marked" : "value";
                int current = RuleJson.integer(track.get(capacity), "/" + path + "/" + capacity, 0, 1000);
                int change = op.has("amount") ? whole(op.path("amount"), context, 0, 1_000_000)
                        * ("reserve".equals(op.path("direction").asText()) ? -1 : 1)
                        : whole(op.path("by"), context, -1000, 1000);
                int next = Math.max(0, Math.min(1000, current + change));
                ObjectNode owner = null;
                String counterName = null;
                int reserved = 0;
                if (op.has("counter")) {
                    String counter = op.path("counter").asText();
                    int dot = counter.indexOf('.');
                    owner = RuleJson.object(context.get(counter.substring(0, dot)), "/" + counter);
                    counterName = counter.substring(dot + 1);
                    reserved = RuleJson.integer(owner.get(counterName), "/" + counter, 0, 1000);
                    if (current + reserved > 1000)
                        throw RuleJson.error("/" + counter, "The capacity and reserve exceed the supported total.");
                    if (track.has("total") && RuleJson.integer(track.get("total"), "/" + path + "/total", 0, 1000) != current + reserved)
                        throw RuleJson.error("/" + path + "/total", "The capacity and reserve do not match the stored total.");
                    RuleJson.integer(track.get(filled), "/" + path + "/" + filled, 0, current);
                    next = Math.min(next, current + reserved);
                }
                if (track.has("minimumCapacity") && next < RuleJson.integer(track.get("minimumCapacity"),
                        "/" + path + "/minimumCapacity", 0, 1000))
                    throw new Unavailable("capacity", "Track capacity cannot be reduced below its permanent marks.");
                ObjectNode before = observer == null ? null : track.deepCopy();
                track.put(capacity, next);
                if (track.has(filled) && track.path(filled).asInt() > next) track.put(filled, next);
                if (owner != null) owner.put(counterName, reserved + current - next);
                trackChanged(observer, context, path, "capacity", change, before, track);
                result = RuleExpression.numeric(next - current, "/result");
            }
            case "collection.add", "collection.remove" -> {
                ObjectNode owner = RuleJson.object(context.path(op.path("target").asText()), "/" + op.path("target").asText());
                String name = op.path("collection").asText();
                if (!owner.has(name)) owner.putArray(name);
                if (!owner.get(name).isArray() || owner.get(name).size() > 256) throw RuleJson.error("/" + name, "Expected a bounded list.");
                ArrayNode list = (ArrayNode) owner.get(name);
                JsonNode item = eval(op.path("item"), context);
                if ("collection.add".equals(kind)) {
                    ObjectNode entry = RuleJson.object(item, "/item").deepCopy();
                    String id = RuleJson.id(entry.get("id"), "/item/id");
                    entry.put("grantedBy", context.path("source").path("instanceId").asText());
                    ObjectNode existing = null;
                    for (JsonNode candidate : list) if (id.equals(candidate.path("id").asText())) existing = (ObjectNode) candidate;
                    if (existing != null && existing.has("quantity") && entry.has("quantity")) {
                        int total = RuleJson.integer(existing.get("quantity"), "/item/quantity", 0, 1_000_000)
                                + RuleJson.integer(entry.get("quantity"), "/item/quantity", 1, 1_000_000);
                        existing.put("quantity", Math.min(1_000_000, total));
                        result = existing.deepCopy();
                    } else if (existing != null) throw new Unavailable("duplicate", "This entry is already present.");
                    else {
                        if (list.size() >= 256) throw RuleJson.error("/" + name, "Too many entries.");
                        list.add(entry);
                        result = entry.deepCopy();
                    }
                } else {
                    String id = RuleJson.text(item, "/item");
                    int index = -1;
                    for (int i = 0; i < list.size(); i++) if (id.equals(list.path(i).path("id").asText())) index = i;
                    if (index < 0) throw new Unavailable("missing", "This entry is no longer present.");
                    result = list.remove(index);
                }
            }
            case "damage.deal", "countdown.start", "claim.offer", "table.gain", "table.spend", "limits.refresh", "limit.restore", "inventory.grant" -> {
                ArrayNode outbox = context.has("outbox") ? (ArrayNode) context.get("outbox") : context.putArray("outbox");
                if (outbox.size() >= 32) throw RuleJson.error("/outbox", "Too many host requests.");
                ObjectNode request = outbox.addObject().put("kind", kind)
                        .put("sourceInstanceId", context.path("source").path("instanceId").asText())
                        .put("label", RuleTemplate.render(op.path("label").asText(), context));
                if ("damage.deal".equals(kind)) request.put("to", op.path("to").asText())
                        .put("amount", whole(op.path("amount"), context, 0, 1_000_000));
                else if ("claim.offer".equals(kind)) {
                    request.put("group", op.path("group").asText())
                            .put("sourceActorId", context.path("actor").path("id").asText());
                    request.set("originEvent", context.path("event").deepCopy());
                    if (op.has("limit")) request.put("limit", whole(op.path("limit"), context, 0, 64));
                }
                else if (Set.of("table.gain", "table.spend").contains(kind)) {
                    request.put("resource", op.path("resource").asText())
                            .put("amount", whole(op.path("amount"), context, 1, 1000));
                    settleTableResource(context, request);
                }
                else if ("limits.refresh".equals(kind)) request.set("on", op.path("on").deepCopy());
                else if ("limit.restore".equals(kind)) {
                    String target = op.path("to").asText();
                    String targetId = RuleJson.id(context.path(target).path("id"), "/" + target + "/id");
                    request.put("to", target).put("actorId", targetId).put("targetActorId", targetId)
                            .put("sourceActorId", RuleJson.id(context.path("actor").path("id"), "/actor/id"));
                    request.set("originEvent", context.path("event").deepCopy());
                }
                else if ("inventory.grant".equals(kind)) {
                    String target = op.path("to").asText("actor");
                    request.put("catalog", op.path("catalog").asText())
                            .put("entry", RuleJson.id(eval(op.path("entry"), context), "/entry"))
                            .put("quantity", whole(op.path("quantity"), context, 1, 100))
                            .put("to", target)
                            .put("holderId", context.path(target).path("id").asText());
                    if (op.has("tag")) request.put("tag", op.path("tag").asText());
                    if (op.has("max")) request.put("max", whole(op.path("max"), context, 1, 1000))
                            .put("scope", op.path("scope").asText("actor"));
                }
                else request.put("value", whole(op.path("value"), context, 1, 1000));
                result = request.deepCopy();
            }
            default -> throw RuleJson.error("/op", "Unsupported operation: " + kind);
        }
        if (op.has("as")) ((ObjectNode) context.get("result")).set(op.path("as").asText(), result.deepCopy());
        if (observer != null && (kind.startsWith("state.") || kind.startsWith("pool.") || "flag.set".equals(kind)
                || kind.startsWith("effect.") || kind.startsWith("collection.")
                || "inventory.consume".equals(kind) || "roll.addPoolDie".equals(kind)
                || "limits.refresh".equals(kind)
                || "inventory.grant".equals(kind) || "damage.deal".equals(kind))) {
            String target = "inventory.grant".equals(kind) ? op.path("to").asText("actor")
                    : kind.startsWith("pool.") || "roll.addPoolDie".equals(kind)
                    ? op.path("owner").asText(null)
                    : "flag.set".equals(kind) ? op.path("flag").asText().split("\\.")[0] : op.path("target").asText(null);
            observer.mutated(context, kind, previousActor, target);
        }
    }

    static void trackChanged(TrackObserver observer, ObjectNode context, String path, String change, int requested,
                             ObjectNode before, ObjectNode after) {
        if (observer == null || RuleExpression.equal(before, after) && !("clear".equals(change) && requested > 0)) return;
        int separator = path.indexOf('.');
        String holder = path.substring(0, separator);
        ObjectNode event = context.path("event").isObject()
                ? ((ObjectNode) context.get("event")).deepCopy() : RuleJson.JSON.createObjectNode();
        event.put("type", "track.changed").put("actorId",
                RuleJson.id(context.path(holder).path("id"), "/" + holder + "/id"));
        event.put("track", path.substring(separator + 1)).put("change", change).put("requested", requested);
        event.set("before", before);
        event.set("after", after.deepCopy());
        observer.changed(context, holder, event);
    }

    static int clearOwnerTrack(ObjectNode context, String track, int requested, TrackObserver observer) {
        if (track == null || !track.matches("[a-zA-Z][a-zA-Z0-9_-]*"))
            throw RuleJson.error("/track", "Expected an actor track name.");
        if (requested < 0 || requested > 1_000_000)
            throw RuleJson.error("/requested", "Expected a nonnegative recovery amount up to 1000000.");
        JsonNode previous = context.get("result");
        context.putObject("result");
        ObjectNode operation = RuleJson.JSON.createObjectNode().put("op", "track.clear")
                .put("track", "actor." + track).put("amount", requested).put("as", "cleared");
        try {
            operation(null, operation, context, null, false, new ArrayList<>(), new ArrayList<>(), observer);
            return context.at("/result/cleared").intValue();
        } finally {
            if (previous == null) context.remove("result");
            else context.set("result", previous);
        }
    }

    private static void settleTableResource(ObjectNode context, ObjectNode request) {
        String resource = request.path("resource").asText();
        if (!"fear".equals(resource))
            throw new Unavailable("tableResource", "The host does not support this table resource: " + resource + ".");
        ObjectNode table = RuleJson.object(context.at("/event/table"), "/event/table");
        int before = RuleJson.integer(table.get(resource), "/event/table/" + resource, 0, Campaign.MAX_FEAR);
        int amount = request.path("amount").intValue();
        int after;
        if ("table.gain".equals(request.path("kind").asText())) {
            after = Math.min(Campaign.MAX_FEAR, before + amount);
        } else {
            if (before < amount) throw new Unavailable("tableResource", "Not enough GM Fear.");
            after = before - amount;
        }
        table.put(resource, after);
        request.put("before", before).put("after", after);
    }

    private static JsonNode eval(JsonNode value, ObjectNode context) { return RuleExpression.evaluate(value, context); }
    private static JsonNode perDie(JsonNode expression, ObjectNode die, ObjectNode context) {
        JsonNode previous = context.get("die");
        context.set("die", die);
        try { return eval(expression, context); }
        finally { if (previous == null) context.remove("die"); else context.set("die", previous); }
    }
    private static int rolledDice(ObjectNode roll) {
        if (roll.has("faces")) return roll.path("faces").size() + roll.path("alternateFaces").size();
        int count = 0;
        for (JsonNode part : roll.path("contributions")) count += part.path("dice").size();
        return count;
    }

    private static boolean keepHigherRestRoll(ObjectNode context) {
        return "keepHigher".equals(context.at("/event/rest/rollPolicy").asText());
    }

    private static ObjectNode sampleDice(int count, int sides, RandomGenerator random) {
        ObjectNode roll = RuleJson.JSON.createObjectNode().put("sides", sides);
        ArrayNode faces = roll.putArray("faces");
        int total = 0;
        for (int i = 0; i < count; i++) {
            int face = random.nextInt(sides) + 1;
            faces.add(face);
            total += face;
        }
        return roll.put("total", total);
    }

    private static int diceScore(ObjectNode roll, String kind, int atLeast) {
        if ("dice.roll".equals(kind)) return roll.path("total").asInt();
        int score = 0;
        for (JsonNode face : roll.path("faces"))
            score = "dice.hits".equals(kind) ? score + (face.asInt() >= atLeast ? 1 : 0) : Math.max(score, face.asInt());
        return score;
    }
    private static int whole(JsonNode expr, ObjectNode context, int min, int max) {
        return RuleJson.integer(eval(expr, context), "/value", min, max);
    }
}
