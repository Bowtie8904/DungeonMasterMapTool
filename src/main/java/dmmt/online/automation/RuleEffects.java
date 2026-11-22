package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.Set;

/**
 * Declared effects and statistic modifiers. Effect instances live in the owning actor's {@code effects}
 * array; every instance freezes its modifier values when it starts so later state changes cannot alter it.
 */
public final class RuleEffects {
    private static final int MAX_INSTANCES = 256;

    private RuleEffects() {}

    /** Evaluates the program's passive modifiers whose conditions currently hold. */
    public static ArrayNode passives(RuleCompiler.Program program, ObjectNode hostContext) {
        ObjectNode context = RuleRuntime.context(program, null, hostContext);
        ArrayNode out = RuleJson.JSON.createArrayNode();
        for (JsonNode modifier : program.modifiers()) {
            if (modifier.has("when") && !RuleExpression.bool(RuleExpression.evaluate(modifier.get("when"), context), "/modifiers/when"))
                continue;
            ObjectNode resolved = out.addObject().put("stat", modifier.path("stat").asText())
                    .put("op", modifier.path("op").asText("add"))
                    .put("value", RuleExpression.number(RuleExpression.evaluate(modifier.get("value"), context), "/modifiers/value"));
            if (modifier.has("label")) resolved.put("label", RuleTemplate.render(modifier.path("label").asText(), context));
            resolved.put("sourceInstanceId", context.path("source").path("instanceId").asText());
        }
        return out;
    }

    /**
     * Instances of the program's derived effects (declared with {@code while}) whose condition currently holds. They are
     * never stored: the host recomputes them from the persisted actor, marks them {@code derived} and adds them to the
     * projected actor so their modifiers, labels and counts apply exactly while the condition holds.
     */
    public static ArrayNode derived(RuleCompiler.Program program, ObjectNode hostContext) {
        ObjectNode context = RuleRuntime.context(program, null, hostContext);
        ArrayNode out = RuleJson.JSON.createArrayNode();
        String instanceId = context.path("source").path("instanceId").asText();
        program.effects().fields().forEachRemaining(entry -> {
            JsonNode definition = entry.getValue();
            if (!definition.has("while")) return;
            if (!RuleExpression.bool(RuleExpression.evaluate(definition.get("while"), context), "/effects/" + entry.getKey() + "/while")) return;
            ObjectNode instance = out.addObject()
                    .put("id", instanceId + "/" + entry.getKey() + "/derived")
                    .put("effect", program.sourceId() + "#" + entry.getKey())
                    .put("sourceInstanceId", instanceId)
                    .put("label", RuleTemplate.render(definition.path("label").asText(), context));
            if (definition.has("text")) instance.put("text", RuleTemplate.render(definition.path("text").asText(), context));
            instance.put("kind", definition.path("kind").asText("buff")).put("derived", true);
            if (definition.has("profile")) instance.set("profile", definition.get("profile").deepCopy());
            instance.putArray("until");
            freeze(definition, context, instance.putArray("modifiers"));
        });
        return out;
    }

    /** Removes derived instances so only started effects are persisted. */
    public static ArrayNode stored(JsonNode effects) {
        ArrayNode kept = RuleJson.JSON.createArrayNode();
        for (JsonNode effect : effects) if (!effect.path("derived").asBoolean()) kept.add(effect.deepCopy());
        return kept;
    }

    /** Returns the effects that survive {@code event}; manual effects (no {@code until}) always survive. */
    public static ArrayNode expire(JsonNode effects, String event) {
        if (!RuleCompiler.RESET_EVENTS.contains(event)) throw RuleJson.error("/event", "Unknown expiry event.");
        ArrayNode kept = RuleJson.JSON.createArrayNode();
        for (JsonNode effect : instances(effects, "/effects")) {
            boolean ends = false;
            for (JsonNode until : effect.path("until")) ends |= event.equals(until.asText());
            if (!ends) kept.add(effect.deepCopy());
        }
        return kept;
    }

    /** Collects the frozen modifiers of every active effect instance. */
    public static ArrayNode modifiers(JsonNode effects) {
        ArrayNode out = RuleJson.JSON.createArrayNode();
        for (JsonNode effect : instances(effects, "/effects"))
            for (JsonNode modifier : effect.path("modifiers")) out.add(modifier.deepCopy());
        return out;
    }

    /** Applies modifiers for {@code stat}: the last {@code set} replaces the base, then adds, then atLeast/atMost bounds. */
    public static double stat(double base, String stat, Iterable<JsonNode> modifiers) {
        double value = base, added = 0, floor = Double.NEGATIVE_INFINITY, ceiling = Double.POSITIVE_INFINITY;
        for (JsonNode modifier : modifiers) {
            if (!stat.equals(modifier.path("stat").asText())) continue;
            double amount = RuleExpression.number(modifier.get("value"), "/modifiers/value");
            switch (modifier.path("op").asText("add")) {
                case "set" -> value = amount;
                case "add" -> added += amount;
                case "atLeast" -> floor = Math.max(floor, amount);
                case "atMost" -> ceiling = Math.min(ceiling, amount);
                default -> throw RuleJson.error("/modifiers/op", "Unknown modifier operation.");
            }
        }
        return Math.min(ceiling, Math.max(floor, value + added));
    }

    static ObjectNode start(RuleCompiler.Program program, String effectId, String target, ObjectNode context) {
        ObjectNode definition = program.effect(effectId);
        ArrayNode list = owned(context, target);
        String key = program.sourceId() + "#" + effectId;
        String instanceId = context.path("source").path("instanceId").asText();
        String stacking = definition.path("stacking").asText("replace");
        int existing = 0, next = 1;
        for (JsonNode effect : list) if (matches(effect, key, instanceId)) {
            existing++;
            String[] parts = effect.path("id").asText().split("/");
            try { next = Math.max(next, Integer.parseInt(parts[parts.length - 1]) + 1); } catch (NumberFormatException ignored) {}
        }
        if ("unique".equals(stacking) && existing > 0) throw new RuleRuntime.Unavailable("effectActive", "This effect is already active.");
        if ("replace".equals(stacking)) remove(list, key, instanceId);
        if ("replaceAnySource".equals(stacking)) for (int i = list.size() - 1; i >= 0; i--)
            if (key.equals(list.get(i).path("effect").asText()) && !list.get(i).path("derived").asBoolean()) list.remove(i);
        String group = definition.path("exclusiveGroup").asText("");
        if (!group.isEmpty())
            for (int i = list.size() - 1; i >= 0; i--)
                if (group.equals(list.get(i).path("group").asText()) && !list.get(i).path("derived").asBoolean()) list.remove(i);
        if (list.size() >= MAX_INSTANCES) throw RuleJson.error("/effects", "Too many active effects.");
        ObjectNode instance = RuleJson.JSON.createObjectNode()
                .put("id", instanceId + "/" + effectId + "/" + next)
                .put("effect", key)
                .put("sourceInstanceId", instanceId)
                .put("label", RuleTemplate.render(definition.path("label").asText(), context));
        if (definition.has("text")) instance.put("text", RuleTemplate.render(definition.path("text").asText(), context));
        instance.put("kind", definition.path("kind").asText("buff"));
        if (!group.isEmpty()) instance.put("group", group);
        if (definition.has("profile")) instance.set("profile", definition.get("profile").deepCopy());
        ArrayNode until = instance.putArray("until");
        definition.path("until").forEach(until::add);
        freeze(definition, context, instance.putArray("modifiers"));
        list.add(instance);
        if (definition.path("singleHolder").asBoolean(false)) {
            ArrayNode outbox = context.has("outbox") ? (ArrayNode) context.get("outbox") : context.putArray("outbox");
            if (outbox.size() >= 32) throw RuleJson.error("/outbox", "Too many host requests.");
            outbox.addObject().put("kind", "effect.endElsewhere").put("sourceInstanceId", instanceId).put("effect", key)
                    .put("holder", context.path(target).path("id").asText()).put("label", instance.path("label").asText());
        }
        refreshCounts(program, context);
        return instance.deepCopy();
    }

    private static void freeze(JsonNode definition, ObjectNode context, ArrayNode modifiers) {
        for (JsonNode modifier : definition.path("modifiers")) {
            ObjectNode frozen = modifiers.addObject().put("stat", modifier.path("stat").asText())
                    .put("op", modifier.path("op").asText("add"))
                    .put("value", RuleExpression.number(RuleExpression.evaluate(modifier.get("value"), context), "/modifiers/value"));
            if (modifier.has("label")) frozen.put("label", RuleTemplate.render(modifier.path("label").asText(), context));
        }
    }

    static int end(RuleCompiler.Program program, String effectId, String target, ObjectNode context) {
        program.effect(effectId);
        int removed = remove(owned(context, target), program.sourceId() + "#" + effectId,
                context.path("source").path("instanceId").asText());
        refreshCounts(program, context);
        return removed;
    }

    /** Ends every stored effect instance on {@code target}, whatever source started it, that carries {@code label}. */
    static int endLabeled(RuleCompiler.Program program, String label, String target, ObjectNode context) {
        ArrayNode list = owned(context, target);
        int removed = 0;
        for (int i = list.size() - 1; i >= 0; i--)
            if (label.equals(list.get(i).path("label").asText()) && !list.get(i).path("derived").asBoolean()) { list.remove(i); removed++; }
        refreshCounts(program, context);
        return removed;
    }

    /** Ends this source's active actor effect instances whose {@code endWhen} condition now holds. */
    static void settle(RuleCompiler.Program program, ObjectNode context) {
        boolean ended = false;
        var definitions = program.effects().fields();
        while (definitions.hasNext()) {
            var definition = definitions.next();
            if (!definition.getValue().has("endWhen") || context.path("effects").path(definition.getKey()).asInt() == 0) continue;
            if (RuleExpression.bool(RuleExpression.evaluate(definition.getValue().get("endWhen"), context), "/effects/" + definition.getKey() + "/endWhen"))
                ended |= remove(owned(context, "actor"), program.sourceId() + "#" + definition.getKey(),
                        context.path("source").path("instanceId").asText()) > 0;
        }
        if (ended) refreshCounts(program, context);
        clearBoundPools(program, context);
    }

    /** A pool that follows an effect reads zero whenever this source's instance of that effect is not active. */
    static void clearBoundPools(RuleCompiler.Program program, ObjectNode context) {
        var pools = program.pools().fields();
        while (pools.hasNext()) {
            var pool = pools.next();
            String bound = pool.getValue().path("whileEffect").asText("");
            if (bound.isEmpty() || context.path("effects").path(bound).asInt() > 0) continue;
            JsonNode state = context.path("actor").path("pools").path(pool.getKey());
            if (state.path("value").asInt() != 0) ((ObjectNode) state).put("value", 0);
        }
    }

    /** Exposes {@code effects.<id>} as this source instance's active instance count on the actor. */
    static void refreshCounts(RuleCompiler.Program program, ObjectNode context) {
        ObjectNode counts = context.putObject("effects");
        JsonNode actorEffects = context.path("actor").path("effects");
        if (!actorEffects.isMissingNode()) instances(actorEffects, "/actor/effects");
        String instanceId = context.path("source").path("instanceId").asText();
        program.effects().fieldNames().forEachRemaining(id -> {
            int count = 0;
            for (JsonNode effect : actorEffects) if (matches(effect, program.sourceId() + "#" + id, instanceId)) count++;
            counts.put(id, count);
        });
    }

    private static ArrayNode owned(ObjectNode context, String target) {
        ObjectNode owner = RuleJson.object(context.path(target), "/" + target);
        if (!owner.has("effects")) return owner.putArray("effects");
        instances(owner.get("effects"), "/" + target + "/effects");
        return (ArrayNode) owner.get("effects");
    }

    private static ArrayNode instances(JsonNode effects, String path) {
        if (!effects.isArray() || effects.size() > MAX_INSTANCES) throw RuleJson.error(path, "Expected a bounded effect list.");
        Set<String> ids = new HashSet<>();
        for (JsonNode effect : effects) {
            RuleJson.object(effect, path);
            if (!ids.add(RuleJson.text(effect.get("id"), path + "/id"))) throw RuleJson.error(path, "Duplicate effect instance ID.");
            if (effect.has("modifiers") && !effect.get("modifiers").isArray()) throw RuleJson.error(path + "/modifiers", "Expected a list.");
        }
        return (ArrayNode) effects;
    }

    private static boolean matches(JsonNode effect, String key, String instanceId) {
        return key.equals(effect.path("effect").asText()) && instanceId.equals(effect.path("sourceInstanceId").asText());
    }

    private static int remove(ArrayNode list, String key, String instanceId) {
        int removed = 0;
        for (int i = list.size() - 1; i >= 0; i--) if (matches(list.get(i), key, instanceId)) { list.remove(i); removed++; }
        return removed;
    }
}
