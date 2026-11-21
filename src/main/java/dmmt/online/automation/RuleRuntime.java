package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
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

    public static final class Unavailable extends IllegalStateException {
        private final String code;
        Unavailable(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }

    private RuleRuntime() {}

    public static ObjectNode describe(RuleCompiler.Program program, String ruleId, String bindingId, ObjectNode hostContext) {
        ObjectNode rule = program.rule(ruleId);
        ObjectNode context = context(program, rule, hostContext);
        JsonNode binding = binding(rule, bindingId);
        if (binding.has("forEach")) throw RuleJson.error("/bindingId", "Use describeEach for a per-die binding.");
        return describe(program, ruleId, bindingId, rule, binding, context, null);
    }

    public static ArrayNode describeEach(RuleCompiler.Program program, String ruleId, String bindingId, ObjectNode hostContext) {
        ObjectNode rule = program.rule(ruleId);
        ObjectNode context = context(program, rule, hostContext);
        JsonNode binding = binding(rule, bindingId);
        if (!binding.has("forEach")) throw RuleJson.error("/bindingId", "Binding does not iterate a pool.");
        ArrayNode descriptions = RuleJson.JSON.createArrayNode();
        ObjectNode pool = poolState(program, context, binding.path("forEach").asText());
        for (JsonNode die : pool.path("dice")) {
            ObjectNode itemContext = context.deepCopy();
            itemContext.set("die", die.deepCopy());
            ObjectNode description = describe(program, ruleId, bindingId, rule, binding, itemContext, die.path("id").asText());
            descriptions.add(description);
        }
        return descriptions;
    }

    private static ObjectNode describe(RuleCompiler.Program program, String ruleId, String bindingId, ObjectNode rule,
                                       JsonNode binding, ObjectNode context, String eachId) {
        ObjectNode description = RuleJson.JSON.createObjectNode();
        description.put("ruleId", ruleId).put("bindingId", bindingId).put("sourceId", program.sourceId())
                .put("sourceInstanceId", context.path("source").path("instanceId").asText())
                .put("revision", revision(context)).put("mode", binding.path("mode").asText())
                .put("surface", binding.path("surface").asText());
        if (eachId != null) description.put("eachId", eachId);
        if (binding.has("group")) description.set("group", binding.get("group"));
        if (binding.has("defaultOn")) description.set("defaultOn", binding.get("defaultOn"));
        if (binding.has("exclusiveGroup")) description.set("exclusiveGroup", binding.get("exclusiveGroup"));
        if (binding.has("claimGroup")) description.set("claimGroup", binding.get("claimGroup"));
        if (binding.has("claimScope")) description.set("claimScope", binding.get("claimScope"));
        if (binding.has("targetSelection")) description.set("targetSelection", describeTargets(binding, context));
        description.put("audience", binding.path("audience").asText("self"));
        description.put("priority", binding.path("priority").asInt());
        for (String text : List.of("label", "description")) if (rule.path("ui").has(text))
            description.put(text, RuleTemplate.render(rule.path("ui").path(text).asText(), context));
        try {
            available(program, rule, binding, context);
            if (binding.has("targetSelection") && description.path("targetSelection").path("options").isEmpty())
                throw new Unavailable("recipient", "There are no eligible targets for this action.");
            description.set("inputs", describeInputs(rule, context));
            description.put("costsRequireInput", preflightCosts(program, rule, context));
            description.put("available", true);
        } catch (Unavailable ex) {
            description.put("available", false).put("reasonCode", ex.code())
                    .put("reason", unavailableText(rule, context, ex.getMessage()));
        }
        return description;
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
                RuleJson.fields(option, path, "id", "label", "revision");
                String id = RuleJson.id(option.path("id"), path + "/id");
                if (!ids.add(id)) throw RuleJson.error(path + "/id", "Duplicate target option.");
                JsonNode revisionNode = option.path("revision");
                if (!revisionNode.isIntegralNumber() || !revisionNode.canConvertToLong() || revisionNode.longValue() < 0)
                    throw RuleJson.error(path + "/revision", "Expected a nonnegative host revision.");
                ObjectNode item = options.addObject().put("id", id)
                        .put("label", RuleJson.text(option.path("label"), path + "/label"));
                item.put("revision", revisionNode.longValue());
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
        return executePrepared(program, prepare(program, intent, hostContext), random, false);
    }

    public static Resolution preview(RuleCompiler.Program program, Intent intent, ObjectNode hostContext) {
        return executePrepared(program, prepare(program, intent, hostContext), null, true);
    }

    static Prepared prepare(RuleCompiler.Program program, Intent intent, ObjectNode hostContext) {
        return reserveCosts(program, preflight(program, intent, hostContext), hostContext);
    }

    static Prepared preflight(RuleCompiler.Program program, Intent intent, ObjectNode hostContext) {
        ObjectNode rule = program.rule(intent.ruleId());
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
        available(program, rule, binding, next);
        validateInputs(rule, next, intent.inputs());
        return new Prepared(intent, next, revision);
    }

    static Prepared reserveCosts(RuleCompiler.Program program, Prepared preflight, ObjectNode currentContext) {
        return reserveCosts(program, preflight, currentContext, true);
    }

    static Prepared reserveCosts(RuleCompiler.Program program, Prepared preflight, ObjectNode currentContext,
                                 boolean reserveSourceCosts) {
        Intent intent = preflight.intent();
        ObjectNode rule = program.rule(intent.ruleId());
        ObjectNode next = preflight.context().deepCopy();
        if (!next.path("source").path("instanceId").asText().equals(currentContext.path("source").path("instanceId").asText()))
            throw new Unavailable("source", "The source instance changed. Reopen this action.");
        long revision = revision(currentContext);
        if (revision != preflight.revision() || revision != intent.expectedRevision())
            throw new Unavailable("stale", "This action is stale. Use its latest state.");
        for (String field : List.of("actor", "recipient", "source", "state", "usage", "sourcePools"))
            if (currentContext.has(field)) next.set(field, currentContext.path(field).deepCopy());
        RuleEffects.refreshCounts(program, next);
        if (reserveSourceCosts && rule.has("limitRef")) {
            String limit = rule.path("limitRef").asText();
            ObjectNode usage = (ObjectNode) next.get("usage");
            int current = RuleJson.integer(usage.get(limit), "/usage/" + limit, 0, 1000);
            int maximum = program.limits().path(limit).path("max").asInt();
            if (current >= maximum) throw new Unavailable("limit", "No uses remain for this action.");
            usage.put(limit, current + 1);
        }
        if (reserveSourceCosts) {
            List<ObjectNode> published = new ArrayList<>(), rolls = new ArrayList<>();
            for (JsonNode cost : rule.path("costs")) operation(program, cost, next, null, false, published, rolls);
        }
        return new Prepared(intent, next, revision);
    }

    static Resolution executePrepared(RuleCompiler.Program program, Prepared prepared, RandomGenerator random, boolean preview) {
        Intent intent = prepared.intent();
        ObjectNode rule = program.rule(intent.ruleId());
        ObjectNode next = prepared.context().deepCopy();
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
        List<ObjectNode> published = new ArrayList<>(), rolls = new ArrayList<>();
        for (JsonNode step : rule.path("do")) operation(program, step, next, random, preview, published, rolls);
        String text = preview && rule.path("ui").has("preview") ? "preview" : "report";
        String report = rule.path("ui").has(text) ? RuleTemplate.render(rule.path("ui").path(text).asText(), next) : "";
        next.remove("die");
        next.set("revision", RuleExpression.numeric(revision + 1d, "/revision"));
        return new Resolution(next, report, List.copyOf(published), List.copyOf(rolls));
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
        var pools = poolDefinitions.fields();
        while (pools.hasNext()) {
            var definition = pools.next();
            boolean poolReset = false;
            for (JsonNode declared : definition.getValue().path("resetOn")) if (event.equals(declared.asText())) poolReset = true;
            if (poolReset) {
                ObjectNode pool = poolState(program, next, definition.getKey());
                if ("number".equals(definition.getValue().path("kind").asText())) {
                    int initial = definition.getValue().path("initial").asInt(0);
                    if (pool.path("value").asInt() != initial) { pool.put("value", initial); changed = true; }
                } else if (!pool.path("dice").isEmpty()) {
                    pool.putArray("dice");
                    changed = true;
                }
            }
        }
        if (changed) next.set("revision", RuleExpression.numeric(revision(next) + 1d, "/revision"));
        return next;
    }

    static ObjectNode context(RuleCompiler.Program program, ObjectNode rule, ObjectNode hostContext) {
        RuleJson.checkTree(hostContext, "/context", 0, new int[]{0});
        ObjectNode next = hostContext.deepCopy();
        ObjectNode source = RuleJson.object(next.path("source"), "/source");
        if (!program.sourceId().equals(source.path("id").asText())) throw RuleJson.error("/source/id", "Source definition does not match the program.");
        RuleJson.id(source.get("instanceId"), "/source/instanceId");
        if (next.has("recipient")) RuleJson.object(next.get("recipient"), "/recipient");
        revision(next);
        ObjectNode state = program.initialState();
        if (next.has("state")) RuleJson.object(next.get("state"), "/state").fields().forEachRemaining(field -> {
            if (!state.has(field.getKey())) throw RuleJson.error("/state/" + field.getKey(), "Undeclared state slot.");
            state.set(field.getKey(), field.getValue().deepCopy());
        });
        next.set("state", state);
        usage(program, next);
        initializePools(program, next);
        next.set("args", rule == null ? RuleJson.JSON.createObjectNode() : rule.path("args").deepCopy());
        next.putObject("input");
        next.putObject("result");
        RuleEffects.refreshCounts(program, next);
        return next;
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

    private static void initializePools(RuleCompiler.Program program, ObjectNode context) {
        ObjectNode definitions = program.pools();
        var pools = definitions.fields();
        while (pools.hasNext()) poolState(program, context, pools.next().getKey());
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
        int max = RuleJson.integer(definition.path("max"), "/automation/pools/" + id + "/max", 1,
                "dice".equals(definition.path("kind").asText()) ? 1024 : 1_000_000);
        if ("number".equals(definition.path("kind").asText())) {
            RuleJson.fields(pool, path, "value", "max");
            if (!pool.has("value")) pool.put("value", definition.path("initial").asInt(0));
            if (!pool.has("max")) pool.put("max", max);
            int storedMax = RuleJson.integer(pool.get("max"), path + "/max", 1, 1_000_000);
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
        for (JsonNode def : List.of(rule, binding))
            if (def.has("when") && !RuleExpression.bool(eval(def.get("when"), context), "/when"))
                throw new Unavailable("condition", unavailableText(rule, context, "This action is not currently available."));
        if (rule.has("limitRef")) {
            String limit = rule.path("limitRef").asText();
            if (context.path("usage").path(limit).asInt() >= program.limits().path(limit).path("max").asInt())
                throw new Unavailable("limit", "No uses remain for this action.");
        }
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
            } else if ("choice".equals(input.path("kind").asText())) {
                int count = input.path("count").asInt(1);
                ArrayNode options = descriptor.putArray("options").addAll(options(input, context));
                if (options.size() < count) throw new Unavailable("selection", "Not enough options are currently available.");
                descriptor.put("count", count);
            } else if ("allocation".equals(input.path("kind").asText())) {
                ArrayNode options = descriptor.putArray("options").addAll(options(input, context));
                int total = whole(input.path("total"), context, 0, 1_000);
                int max = input.has("max") ? whole(input.path("max"), context, 0, 1_000) : total;
                if (options.isEmpty() || (long) max * options.size() < total)
                    throw new Unavailable("selection", "The amount cannot be allocated to the available options.");
                descriptor.put("total", total).put("max", max);
            } else {
                int count = whole(input.path("count"), context, 1, 1024);
                Set<String> eligible = StructuredRoll.ids(eval(input.path("eligible"), context), "/inputs/eligible", true);
                ArrayNode all = StructuredRoll.dice(eval(input.path("from"), context), null, true);
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
                if (choices.size() < count) throw new Unavailable("selection", "Not enough eligible dice remain.");
                descriptor.put("count", count);
                if (input.has("default")) {
                    sorted.sort(Comparator.comparingInt(die -> die.path("face").asInt()));
                    ArrayNode defaults = descriptor.putArray("default");
                    for (int i = 0; i < count; i++) defaults.add(sorted.get(i).path("id"));
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
        for (JsonNode option : input.path("options"))
            if (!option.has("when") || RuleExpression.bool(eval(option.get("when"), context), "/inputs/options/when"))
                out.addObject().put("id", option.path("id").asText())
                        .put("label", RuleTemplate.render(option.path("label").asText(), context));
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
                descriptor.path("options").forEach(option -> allowed.add(option.path("id").asText()));
                Set<String> selected = new HashSet<>();
                if (count == 1 && value.isTextual()) selected.add(value.asText());
                else if (count > 1 && value.isArray()) for (JsonNode item : value) if (item.isTextual()) selected.add(item.asText());
                int supplied1 = value.isArray() ? value.size() : 1;
                if (selected.size() != count || supplied1 != count || !allowed.containsAll(selected))
                    throw RuleJson.error("/inputs/" + id, "Choose " + count + " distinct available option(s).");
            } else {
                Set<String> selected = StructuredRoll.ids(value, "/inputs/" + id, true);
                Set<String> choices = new HashSet<>();
                descriptor.path("choices").forEach(die -> choices.add(die.path("id").asText()));
                if (selected.size() != descriptor.path("count").asInt() || !choices.containsAll(selected))
                    throw RuleJson.error("/inputs/" + id, "Choose the required number of distinct eligible dice.");
            }
        }
        context.set("input", accepted);
    }

    private static void operation(RuleCompiler.Program program, JsonNode op, ObjectNode context, RandomGenerator random, boolean preview,
                                  List<ObjectNode> published, List<ObjectNode> rolls) {
        String kind = op.path("op").asText();
        JsonNode result;
        switch (kind) {
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
                ObjectNode track = RuleJson.object(RuleExpression.read(context, path), "/" + path);
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
                    applied = Math.min(amount, current); value = current - applied;
                } else {
                    applied = uncapped ? amount : Math.min(amount, cap - current); value = current + applied;
                }
                track.set(field, RuleExpression.numeric(value, "/" + path));
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
            case "dice.roll", "dice.hits" -> {
                if (preview) throw new Unavailable("randomPreview", "This preview requires a committed roll; no dice were rolled.");
                int count = whole(op.path("count"), context, 1, 1024);
                int sides = whole(op.path("sides"), context, 2, 1000);
                int previouslyRolled = rolls.stream().mapToInt(RuleRuntime::rolledDice).sum();
                if (count + previouslyRolled > 1024) throw RuleJson.error("/dice", "Action exceeds its dice limit.");
                if (random == null) throw RuleJson.error("/dice", "Host random generator is required.");
                ObjectNode roll = RuleJson.JSON.createObjectNode().put("sides", sides);
                ArrayNode faces = roll.putArray("faces");
                int total = 0;
                for (int i = 0; i < count; i++) {
                    int face = random.nextInt(sides) + 1;
                    faces.add(face); total += face;
                }
                roll.put("total", total);
                rolls.add(roll);
                if ("dice.hits".equals(kind)) {
                    int atLeast = whole(op.path("atLeast"), context, 1, sides);
                    int hits = 0;
                    for (JsonNode face : faces) if (face.asInt() >= atLeast) hits++;
                    roll.put("atLeast", atLeast).put("hits", hits);
                    result = RuleExpression.numeric(hits, "/result");
                } else result = RuleExpression.numeric(total, "/result");
            }
            case "state.set", "state.increment" -> {
                ObjectNode state = (ObjectNode) context.get("state");
                String slot = op.path("slot").asText();
                result = "state.set".equals(kind) ? eval(op.path("value"), context)
                        : RuleExpression.numeric(RuleExpression.number(state.get(slot), "/state/" + slot)
                        + RuleExpression.number(eval(op.path("by"), context), "/state/" + slot), "/state/" + slot);
                state.set(slot, result.deepCopy());
            }
            case "value" -> result = eval(op.path("value"), context);
            case "roll.resolve" -> {
                if (preview) throw new Unavailable("randomPreview", "This preview requires a committed roll; no dice were rolled.");
                int remaining = 1024 - rolls.stream().mapToInt(RuleRuntime::rolledDice).sum();
                ObjectNode roll = StructuredRoll.resolve(eval(op.path("plan"), context), context, random, remaining);
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
                ArrayNode faces = RuleJson.JSON.createArrayNode();
                JsonNode selection = eval(op.path("dice"), context);
                if (selection.size() + rolls.stream().mapToInt(RuleRuntime::rolledDice).sum() > 1024)
                    throw RuleJson.error("/dice", "Action exceeds its dice limit.");
                ObjectNode edited = StructuredRoll.editFaces(eval(op.path("from"), context), selection, program.sourceId(), "rerolled", die -> {
                    int face = random.nextInt(die.path("sides").asInt()) + 1;
                    faces.add(face);
                    return higher ? Math.max(face, die.path("face").asInt()) : face;
                });
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
            case "roll.addDice" -> {
                if (preview) throw new Unavailable("randomPreview", "This preview requires a committed roll; no dice were rolled.");
                int count = whole(op.path("count"), context, 0, 1024);
                if (count + rolls.stream().mapToInt(RuleRuntime::rolledDice).sum() > 1024)
                    throw RuleJson.error("/dice", "Action exceeds its dice limit.");
                ObjectNode from = StructuredRoll.validate(eval(op.path("from"), context));
                Set<String> before = new HashSet<>();
                for (JsonNode part : from.path("contributions")) for (JsonNode die : part.path("dice")) before.add(die.path("id").asText());
                ObjectNode added = StructuredRoll.addDice(from, eval(op.path("dice"), context), count, program.sourceId(), random);
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
            case "roll.contribute" -> {
                ObjectNode part = op.path("contribution").deepCopy();
                if (preview && "dice".equals(part.path("kind").asText()))
                    throw new Unavailable("randomPreview", "This preview requires a committed roll; no dice were rolled.");
                part.put("sourceId", program.sourceId()).put("label", RuleTemplate.render(part.path("label").asText(), context));
                int remaining = 1024 - rolls.stream().mapToInt(RuleRuntime::rolledDice).sum();
                if ("dice".equals(part.path("kind").asText()) && whole(part.path("count"), context, 0, 1024) > remaining)
                    throw RuleJson.error("/dice", "Action exceeds its dice limit.");
                JsonNode base = eval(op.path("from"), context);
                ObjectNode contributed = StructuredRoll.contribute(base, part, context, random);
                if ("dice".equals(part.path("kind").asText())
                        && contributed.path("contributions").size() > base.path("contributions").size()) {
                    JsonNode added = contributed.path("contributions").get(contributed.path("contributions").size() - 1);
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
            case "limit.reset" -> {
                ObjectNode usage = usage(program, context);
                String limit = op.path("limit").asText();
                result = RuleExpression.numeric(usage.path(limit).asInt(), "/result");
                usage.put(limit, 0);
            }
            case "damage.reduce" -> {
                int amount = whole(op.path("amount"), context, 0, 1_000_000);
                String id = RuleTemplate.render(op.path("id").asText(), context);
                String label = RuleTemplate.render(op.path("label").asText(), context);
                ObjectNode reduction = StructuredDamage.reduce(eval(op.path("from"), context), amount, id,
                        program.sourceId(), label);
                if (reduction.path("adjustment").path("applied").asInt() == 0)
                    throw new Unavailable("noBenefit", "This action would not reduce any damage.");
                result = reduction;
            }
            case "damage.publish" -> {
                ObjectNode damage = StructuredDamage.validate(eval(op.path("damage"), context));
                if (!context.path("event").isObject()) throw RuleJson.error("/damage", "Damage can only be published during an event.");
                ((ObjectNode) context.get("event")).set("damage", damage.deepCopy());
                result = damage.get("amount");
            }
            case "damage.resolve" -> result = StructuredDamage.resolve(eval(op.path("from"), context),
                    op.path("thresholds"), op.path("armor"), op.path("hitPoints"), context);
            case "damage.assess" -> result = StructuredDamage.assess(eval(op.path("from"), context), op.path("type"),
                    op.path("thresholds"), op.path("massive"), op.path("armor"), op.path("hitPoints"), context);
            case "damage.adjust" -> {
                int to = whole(op.path("to"), context, -1000, 1000);
                ObjectNode adjusted = StructuredDamage.adjust(eval(op.path("from"), context), op.path("field").asText(), to,
                        RuleTemplate.render(op.path("id").asText(), context), program.sourceId(),
                        RuleTemplate.render(op.path("label").asText(), context));
                result = adjusted.get("marks");
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
                int next = Math.max(min, Math.min(max, current + whole(op.path("by"), context, -1000, 1000)));
                plan.put(field, next);
                ArrayNode adjustments = plan.has("adjustments") && plan.get("adjustments").isArray()
                        ? (ArrayNode) plan.get("adjustments") : plan.putArray("adjustments");
                adjustments.addObject().put("field", field).put("by", next - current).put("sourceId", program.sourceId())
                        .put("label", RuleTemplate.render(op.path("label").asText(), context));
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
                        op.path("hitPoints"), context);
                if (context.path("event").isObject()) ((ObjectNode) context.get("event")).set("applied", applied.deepCopy());
                result = applied;
            }
            case "limit.modify" -> {
                ObjectNode usage = usage(program, context);
                String limit = op.path("limit").asText();
                int spent = usage.path(limit).asInt();
                int max = program.limits().path(limit).path("max").asInt();
                int by = whole(op.path("by"), context, -1000, 1000);
                int next = Math.max(0, Math.min(max, spent - by));
                usage.put(limit, next);
                result = RuleExpression.numeric(spent - next, "/result");
            }
            case "track.capacity" -> {
                String path = op.path("track").asText();
                ObjectNode track = RuleJson.object(RuleExpression.read(context, path), "/" + path);
                String capacity = track.has("capacity") ? "capacity" : "max";
                String filled = track.has("marked") ? "marked" : "value";
                int current = RuleJson.integer(track.get(capacity), "/" + path + "/" + capacity, 0, 1000);
                int next = Math.max(0, Math.min(1000, current + whole(op.path("by"), context, -1000, 1000)));
                track.put(capacity, next);
                if (track.has(filled) && track.path(filled).asInt() > next) track.put(filled, next);
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
            case "damage.deal", "countdown.start", "claim.offer" -> {
                ArrayNode outbox = context.has("outbox") ? (ArrayNode) context.get("outbox") : context.putArray("outbox");
                if (outbox.size() >= 32) throw RuleJson.error("/outbox", "Too many host requests.");
                ObjectNode request = outbox.addObject().put("kind", kind)
                        .put("sourceInstanceId", context.path("source").path("instanceId").asText())
                        .put("label", RuleTemplate.render(op.path("label").asText(), context));
                if ("damage.deal".equals(kind)) request.put("to", op.path("to").asText())
                        .put("amount", whole(op.path("amount"), context, 0, 1_000_000));
                else if ("claim.offer".equals(kind)) {
                    request.put("group", op.path("group").asText());
                    if (op.has("limit")) request.put("limit", whole(op.path("limit"), context, 0, 64));
                }
                else request.put("value", whole(op.path("value"), context, 1, 1000));
                result = request.deepCopy();
            }
            default -> throw RuleJson.error("/op", "Unsupported operation: " + kind);
        }
        if (op.has("as")) ((ObjectNode) context.get("result")).set(op.path("as").asText(), result.deepCopy());
    }

    private static JsonNode eval(JsonNode value, ObjectNode context) { return RuleExpression.evaluate(value, context); }
    private static JsonNode perDie(JsonNode expression, ObjectNode die, ObjectNode context) {
        JsonNode previous = context.get("die");
        context.set("die", die);
        try { return eval(expression, context); }
        finally { if (previous == null) context.remove("die"); else context.set("die", previous); }
    }
    private static int rolledDice(ObjectNode roll) {
        if (roll.has("faces")) return roll.path("faces").size();
        int count = 0;
        for (JsonNode part : roll.path("contributions")) count += part.path("dice").size();
        return count;
    }
    private static int whole(JsonNode expr, ObjectNode context, int min, int max) {
        return RuleJson.integer(eval(expr, context), "/value", min, max);
    }
}
