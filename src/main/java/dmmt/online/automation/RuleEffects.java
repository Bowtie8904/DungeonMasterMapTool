package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
        return passives(program, hostContext, false);
    }

    static ArrayNode traitPassives(RuleCompiler.Program program, ObjectNode hostContext) {
        return passives(program, hostContext, true);
    }

    private static ArrayNode passives(RuleCompiler.Program program, ObjectNode hostContext, boolean traitsOnly) {
        ObjectNode context = RuleRuntime.context(program, null, hostContext);
        ArrayNode out = RuleJson.JSON.createArrayNode();
        for (JsonNode modifier : program.modifiers()) {
            if (traitsOnly && !modifier.path("stat").asText().startsWith("trait.")) continue;
            if (modifier.has("when") && !RuleExpression.bool(RuleExpression.evaluate(modifier.get("when"), context), "/modifiers/when"))
                continue;
            ObjectNode resolved = out.addObject().put("stat", modifier.path("stat").asText())
                    .put("op", modifier.path("op").asText("add"))
                    .put("value", RuleExpression.number(RuleExpression.evaluate(modifier.get("value"), context), "/modifiers/value"));
            if (!traitsOnly && modifier.has("label")) resolved.put("label", RuleTemplate.render(modifier.path("label").asText(), context));
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
            String sourceActorId = context.path("actor").path("id").textValue();
            if (sourceActorId != null && !sourceActorId.isBlank()) instance.put("sourceActorId", sourceActorId);
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
        return expire(effects, event, null);
    }

    static ArrayNode expire(JsonNode effects, String event, Set<String> eligibleIds) {
        if (!RuleCompiler.RESET_EVENTS.contains(event)) throw RuleJson.error("/event", "Unknown expiry event.");
        ArrayNode kept = RuleJson.JSON.createArrayNode();
        for (JsonNode effect : instances(effects, "/effects")) {
            boolean ends = false;
            if (eligibleIds == null || eligibleIds.contains(effect.path("id").asText()))
                for (JsonNode until : effect.path("until")) ends |= event.equals(until.asText());
            if (!ends) kept.add(effect.deepCopy());
        }
        return kept;
    }

    static boolean hasEarlyRestExpiry(JsonNode effects, String event, Set<String> eligibleIds) {
        if (!RuleRuntime.isRestEvent(event)) throw RuleJson.error("/event", "Expected a rest event.");
        for (JsonNode effect : instances(effects, "/effects"))
            if (earlyRestExpiry(effect, event, eligibleIds)) return true;
        return false;
    }

    static ArrayNode expireBeforeMoves(JsonNode effects, String event, Set<String> eligibleIds) {
        if (!RuleRuntime.isRestEvent(event)) throw RuleJson.error("/event", "Expected a rest event.");
        ArrayNode kept = RuleJson.JSON.createArrayNode();
        for (JsonNode effect : instances(effects, "/effects"))
            if (!earlyRestExpiry(effect, event, eligibleIds)) kept.add(effect.deepCopy());
        return kept;
    }

    private static boolean earlyRestExpiry(JsonNode effect, String event, Set<String> eligibleIds) {
        if (!"beforeMoves".equals(effect.path("restExpiry").asText())
                || eligibleIds != null && !eligibleIds.contains(effect.path("id").asText())) return false;
        for (JsonNode until : effect.path("until")) if (event.equals(until.asText())) return true;
        return false;
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
        String sourceActorId = context.path("actor").path("id").textValue();
        boolean singleHolder = definition.path("singleHolder").asBoolean(false);
        String holder = null;
        if (singleHolder) {
            sourceActorId = RuleJson.id(context.path("actor").get("id"), "/actor/id");
            holder = RuleJson.id(context.path(target).get("id"), "/" + target + "/id");
        }
        String stacking = definition.path("stacking").asText("replace");
        if (("unique".equals(stacking) || "replace".equals(stacking)) && (singleHolder || otherRecipient(context, target)))
            requireSourceOwners(list, key, instanceId);
        if ("unique".equals(stacking)) for (JsonNode effect : list)
            if (matches(effect, key, instanceId, sourceActorId)) throw new RuleRuntime.Unavailable("effectActive", "This effect is already active.");
        if ("replace".equals(stacking)) remove(list, key, instanceId, sourceActorId);
        if ("replaceAnySource".equals(stacking)) for (int i = list.size() - 1; i >= 0; i--)
            if (key.equals(list.get(i).path("effect").asText()) && !list.get(i).path("derived").asBoolean()) list.remove(i);
        String group = definition.path("exclusiveGroup").asText("");
        if (!group.isEmpty())
            for (int i = list.size() - 1; i >= 0; i--)
                if (group.equals(list.get(i).path("group").asText()) && !list.get(i).path("derived").asBoolean()) list.remove(i);
        if (list.size() >= MAX_INSTANCES) throw RuleJson.error("/effects", "Too many active effects.");
        ObjectNode instance = RuleJson.JSON.createObjectNode()
                .put("id", java.util.UUID.randomUUID().toString())
                .put("effect", key)
                .put("sourceInstanceId", instanceId)
                .put("label", RuleTemplate.render(definition.path("label").asText(), context));
        if (sourceActorId != null && !sourceActorId.isBlank()) instance.put("sourceActorId", sourceActorId);
        if (definition.has("text")) instance.put("text", RuleTemplate.render(definition.path("text").asText(), context));
        instance.put("kind", definition.path("kind").asText("buff"));
        if (!group.isEmpty()) instance.put("group", group);
        if (definition.has("profile")) instance.set("profile", definition.get("profile").deepCopy());
        if (definition.has("restExpiry")) instance.set("restExpiry", definition.get("restExpiry").deepCopy());
        if (definition.has("locks")) {
            ObjectNode locks = instance.putObject("locks");
            definition.path("locks").fields().forEachRemaining(lock -> locks.put(lock.getKey(),
                    RuleJson.integer(RuleExpression.evaluate(lock.getValue(), context),
                            "/effects/" + effectId + "/locks/" + lock.getKey(), 0, 1000)));
        }
        ArrayNode until = instance.putArray("until");
        definition.path("until").forEach(until::add);
        freeze(definition, context, instance.putArray("modifiers"));
        list.add(instance);
        if (singleHolder) {
            ArrayNode outbox = context.has("outbox") ? (ArrayNode) context.get("outbox") : context.putArray("outbox");
            if (outbox.size() >= 32) throw RuleJson.error("/outbox", "Too many host requests.");
            outbox.addObject().put("kind", "effect.endElsewhere").put("sourceActorId", sourceActorId)
                    .put("sourceInstanceId", instanceId).put("effect", key).put("holder", holder)
                    .put("label", instance.path("label").asText());
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
        ObjectNode definition = program.effect(effectId);
        ArrayNode effects = owned(context, target);
        String key = program.sourceId() + "#" + effectId;
        String instanceId = context.path("source").path("instanceId").asText();
        if (definition.path("singleHolder").asBoolean() || otherRecipient(context, target))
            requireSourceOwners(effects, key, instanceId);
        int removed = remove(effects, key, instanceId, context.path("actor").path("id").textValue());
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
    static boolean settle(RuleCompiler.Program program, ObjectNode context) {
        boolean ended = false;
        var definitions = program.effects().fields();
        while (definitions.hasNext()) {
            var definition = definitions.next();
            if (!definition.getValue().has("endWhen") || context.path("effects").path(definition.getKey()).asInt() == 0) continue;
            if (RuleExpression.bool(RuleExpression.evaluate(definition.getValue().get("endWhen"), context), "/effects/" + definition.getKey() + "/endWhen")) {
                ArrayNode effects = owned(context, "actor");
                String key = program.sourceId() + "#" + definition.getKey();
                String instanceId = context.path("source").path("instanceId").asText();
                if (definition.getValue().path("singleHolder").asBoolean()) requireSourceOwners(effects, key, instanceId);
                ended |= remove(effects, key, instanceId, context.path("actor").path("id").textValue()) > 0;
            }
        }
        if (ended) refreshCounts(program, context);
        return ended;
    }

    private record UnresolvedEffect(String instanceId, String definition) {}
    private record PoolSupport(Set<String> ids, Set<UnresolvedEffect> unresolved) {}

    private static PoolSupport poolSupport(JsonNode effects, Map<String, RuleCompiler.Program> programs, String path) {
        Set<String> ids = new HashSet<>();
        Set<UnresolvedEffect> unresolved = new HashSet<>();
        if (effects.isMissingNode()) return new PoolSupport(ids, unresolved);
        for (JsonNode instance : instances(effects, path)) {
            if (!instance.has("effect") && !instance.has("sourceInstanceId")) continue;
            String key = instance.path("effect").asText("");
            int split = key.lastIndexOf('#');
            RuleCompiler.Program program = split > 0 ? programs.get(key.substring(0, split)) : null;
            String effectId = split > 0 ? key.substring(split + 1) : "";
            if (program == null || !program.sourceId().equals(key.substring(0, split)) || !program.hasEffect(effectId)) {
                unresolved.add(new UnresolvedEffect(instance.path("id").asText(), key.isEmpty() ? "<missing>" : key));
            } else ids.addAll(program.poolsBoundTo(effectId));
        }
        return new PoolSupport(ids, unresolved);
    }

    static void reconcilePools(RuleCompiler.Program program, ObjectNode before, ObjectNode after,
                               Map<String, RuleCompiler.Program> programs, Set<String> recipientPools) {
        reconcileHolder(before.path("actor"), after.path("actor"), program.boundPoolIds(), programs, "/actor");
        if (after.has("recipient") && after.get("recipient") != after.get("actor"))
            reconcileHolder(before.path("recipient"), after.path("recipient"), recipientPools, programs, "/recipient");
    }

    static void reconcileHolder(JsonNode before, JsonNode after, Set<String> declaredPools,
                                Map<String, RuleCompiler.Program> programs, String path) {
        JsonNode oldEffects = before.path("effects"), newEffects = after.path("effects");
        boolean effectsChanged = !oldEffects.equals(newEffects);
        if (!effectsChanged && declaredPools.isEmpty()) return;
        JsonNode pools = after.path("pools");
        if (!pools.isMissingNode()) RuleJson.object(pools, path + "/pools");
        PoolSupport current = poolSupport(newEffects, programs, path + "/effects");
        Set<String> affected = new HashSet<>(declaredPools);
        if (effectsChanged) {
            PoolSupport previous = poolSupport(oldEffects, programs, path + "/effects");
            Set<UnresolvedEffect> removed = new HashSet<>(previous.unresolved());
            removed.removeAll(current.unresolved());
            if (!removed.isEmpty() && pools.size() > 0) throw unresolved(path, removed.iterator().next());
            affected.addAll(previous.ids());
        }
        if (affected.isEmpty()) return;
        affected.removeAll(current.ids());
        List<ObjectNode> clearing = new ArrayList<>();
        for (String id : affected) {
            JsonNode pool = pools.get(id);
            if (pool == null) continue;
            String poolPath = path + "/pools/" + id;
            RuleJson.fields(pool, poolPath, "value", "max");
            int max = pool.has("max") ? RuleJson.integer(pool.get("max"), poolPath + "/max", 0, 1_000_000) : 1_000_000;
            int value = RuleJson.integer(pool.get("value"), poolPath + "/value", 0, max);
            if (value != 0) clearing.add((ObjectNode) pool);
        }
        if (!clearing.isEmpty() && !current.unresolved().isEmpty())
            throw unresolved(path, current.unresolved().iterator().next());
        clearing.forEach(pool -> pool.put("value", 0));
    }

    private static IllegalArgumentException unresolved(String path, UnresolvedEffect effect) {
        return RuleJson.error(path + "/effects", "Cannot reconcile effect-bound pools for instance '"
                + effect.instanceId() + "': unknown effect definition '" + effect.definition()
                + "'. Restore its definition or supply the complete compiled program registry.");
    }

    /** Exposes {@code effects.<id>} as this source instance's active instance count on the actor. */
    static void refreshCounts(RuleCompiler.Program program, ObjectNode context) {
        ObjectNode counts = context.putObject("effects");
        JsonNode actorEffects = context.path("actor").path("effects");
        if (!actorEffects.isMissingNode()) instances(actorEffects, "/actor/effects");
        String instanceId = context.path("source").path("instanceId").asText();
        String sourceActorId = context.path("actor").path("id").textValue();
        program.effects().fieldNames().forEachRemaining(id -> {
            int count = 0;
            for (JsonNode effect : actorEffects) if (matches(effect, program.sourceId() + "#" + id, instanceId, sourceActorId)) count++;
            counts.put(id, count);
        });
    }

    private static ArrayNode owned(ObjectNode context, String target) {
        ObjectNode owner = RuleJson.object(context.path(target), "/" + target);
        if (!owner.has("effects")) return owner.putArray("effects");
        instances(owner.get("effects"), "/" + target + "/effects");
        return (ArrayNode) owner.get("effects");
    }

    static ArrayNode instances(JsonNode effects, String path) {
        if (!effects.isArray() || effects.size() > MAX_INSTANCES) throw RuleJson.error(path, "Expected a bounded effect list.");
        Set<String> ids = new HashSet<>();
        for (JsonNode effect : effects) {
            RuleJson.object(effect, path);
            if (!ids.add(RuleJson.text(effect.get("id"), path + "/id"))) throw RuleJson.error(path, "Duplicate effect instance ID.");
            if (effect.has("modifiers") && !effect.get("modifiers").isArray()) throw RuleJson.error(path + "/modifiers", "Expected a list.");
            if (effect.has("restExpiry")) {
                if (!"beforeMoves".equals(effect.path("restExpiry").asText()))
                    throw RuleJson.error(path + "/restExpiry", "Expected beforeMoves.");
                JsonNode until = effect.path("until");
                if (!until.isArray() || until.isEmpty() || until.size() > RuleCompiler.RESET_EVENTS.size())
                    throw RuleJson.error(path + "/until", "Early expiry requires rest events.");
                boolean rests = false;
                for (int i = 0; i < until.size(); i++) {
                    JsonNode event = until.get(i);
                    if (!event.isTextual() || !RuleCompiler.RESET_EVENTS.contains(event.textValue()))
                        throw RuleJson.error(path + "/until", "Unknown or duplicate reset event.");
                    for (int j = 0; j < i; j++)
                        if (until.get(j).equals(event)) throw RuleJson.error(path + "/until", "Unknown or duplicate reset event.");
                    rests |= RuleRuntime.isRestEvent(event.textValue());
                }
                if (!rests) throw RuleJson.error(path + "/until", "Early expiry requires a rest event.");
            }
            if (effect.has("locks")) {
                ObjectNode locks = RuleJson.object(effect.get("locks"), path + "/locks");
                if (locks.isEmpty() || locks.size() > 8) throw RuleJson.error(path + "/locks", "Expected 1-8 track locks.");
                locks.fields().forEachRemaining(lock -> {
                    String at = path + "/locks/" + lock.getKey();
                    RuleCompiler.trackLockName(lock.getKey(), at);
                    RuleJson.integer(lock.getValue(), at, 0, 1000);
                });
            }
        }
        return (ArrayNode) effects;
    }

    static boolean sourceActorMatches(JsonNode effect, String sourceActorId) {
        return !effect.has("sourceActorId") || sourceActorId != null && sourceActorId.equals(effect.path("sourceActorId").textValue());
    }

    static boolean hasSourceActorId(JsonNode effect) {
        String actor = effect.path("sourceActorId").textValue();
        return actor != null && actor.matches("[a-zA-Z0-9][a-zA-Z0-9_.:-]{0,127}");
    }

    private static boolean otherRecipient(ObjectNode context, String target) {
        if (!"recipient".equals(target)) return false;
        String actor = context.path("actor").path("id").textValue();
        return actor == null || !actor.equals(context.path("recipient").path("id").textValue());
    }

    private static void requireSourceOwners(ArrayNode effects, String key, String instanceId) {
        for (JsonNode effect : effects) {
            if (!key.equals(effect.path("effect").asText()) || !instanceId.equals(effect.path("sourceInstanceId").asText())) continue;
            if (!hasSourceActorId(effect))
                throw RuleJson.error("/effects", "Cannot determine the source character for effect '"
                        + effect.path("id").asText() + "'. Migrate or repair its source identity before changing it.");
        }
    }

    private static boolean matches(JsonNode effect, String key, String instanceId, String sourceActorId) {
        return key.equals(effect.path("effect").asText()) && instanceId.equals(effect.path("sourceInstanceId").asText())
                && sourceActorMatches(effect, sourceActorId);
    }

    private static int remove(ArrayNode list, String key, String instanceId, String sourceActorId) {
        int removed = 0;
        for (int i = list.size() - 1; i >= 0; i--) if (matches(list.get(i), key, instanceId, sourceActorId)) { list.remove(i); removed++; }
        return removed;
    }
}
