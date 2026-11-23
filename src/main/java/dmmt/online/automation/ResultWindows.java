package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Latest owner result per button. Only written inside a staged host sheet transaction. */
final class ResultWindows {
    private static final String FIELD = "resultWindows";
    private static final int MAX_BUTTONS = 256;

    record Button(String sourceInstanceId, String ruleId, String bindingId, String eachId) {
        static Button of(JsonNode item) {
            return new Button(item.required("sourceInstanceId").textValue(), item.required("ruleId").textValue(),
                    item.required("bindingId").textValue(), item.path("eachId").textValue());
        }
    }

    record ClaimGroup(String sourceInstanceId, String group) {
        static ClaimGroup of(JsonNode item) {
            String source = "result".equals(item.path("claimGroupScope").textValue())
                    ? null : item.required("sourceInstanceId").textValue();
            return new ClaimGroup(source, item.required("claimGroup").textValue());
        }
    }

    record ExclusiveGroup(String sourceInstanceId, String group) {
        static ExclusiveGroup of(JsonNode item) {
            return new ExclusiveGroup(item.required("sourceInstanceId").textValue(), item.required("exclusiveGroup").textValue());
        }
    }

    /** Source definition plus current activations, not mutable quantities, labels, or a global sheet revision. */
    static Map<String, ObjectNode> guards(List<SheetProjection.SourceElement> sources, JsonNode effects, String actorId) {
        Map<String, ObjectNode> guards = new HashMap<>();
        for (var source : sources) {
            var guard = RuleJson.JSON.createObjectNode().put("sourceId", source.sourceId());
            guard.putObject("activations");
            guards.put(source.instanceId(), guard);
        }
        for (var effect : effects) {
            var guard = guards.get(effect.path("sourceInstanceId").textValue());
            if (guard != null && RuleEffects.sourceActorMatches(effect, actorId))
                ((ObjectNode) guard.get("activations")).put(effect.required("id").textValue(), true);
        }
        return guards;
    }

    /** Supersede only the buttons which match this result, including matches temporarily unavailable due to costs. */
    static String publish(ObjectNode state, String pack, String principal, ObjectNode event, ArrayNode matches,
                          Map<String, ObjectNode> guards) {
        prune(state, guards);
        if (matches.isEmpty()) return null;
        ObjectNode book = state.withObject("/" + FIELD);
        if (!pack.equals(book.path("pack").textValue()) || !principal.equals(book.path("principal").textValue())) {
            book.removeAll();
            book.put("pack", pack).put("principal", principal);
        }
        ArrayNode windows = book.withArray("results");
        Set<Button> replaced = new HashSet<>();
        ArrayNode buttons = RuleJson.JSON.createArrayNode();
        for (JsonNode match : matches) {
            Button key = Button.of(match);
            if (!replaced.add(key)) throw RuleJson.error("/result", "Duplicate result button.");
            var item = buttons.addObject().put("sourceInstanceId", key.sourceInstanceId())
                    .put("ruleId", key.ruleId()).put("bindingId", key.bindingId());
            if (key.eachId() != null) item.put("eachId", key.eachId());
            if (match.has("claimGroup")) item.set("claimGroup", match.get("claimGroup"));
            if (match.has("claimGroupScope")) item.set("claimGroupScope", match.get("claimGroupScope"));
            if (match.has("exclusiveGroup")) item.set("exclusiveGroup", match.get("exclusiveGroup"));
            if (match.has("repeatable")) item.set("repeatable", match.get("repeatable"));
            item.set("guard", guards.get(key.sourceInstanceId()));
        }
        int count = buttons.size();
        for (int w = windows.size() - 1; w >= 0; w--) {
            ArrayNode old = (ArrayNode) windows.get(w).required("buttons");
            for (int i = old.size() - 1; i >= 0; i--) if (replaced.contains(Button.of(old.get(i)))) old.remove(i);
            if (old.isEmpty()) windows.remove(w);
            count += old.size();
        }
        if (count > MAX_BUTTONS) throw RuleJson.error("/result", "Too many open result buttons on this character.");
        String id = UUID.randomUUID().toString();
        String origin = event.required("sourceInstanceId").textValue();
        if (!guards.containsKey(origin)) throw stale();
        var window = windows.addObject().put("id", id).put("origin", origin);
        window.set("guard", guards.get(origin));
        window.set("event", event.deepCopy());
        window.set("buttons", buttons);
        return id;
    }

    static void prune(ObjectNode state, Map<String, ObjectNode> guards) {
        if (!(state.path(FIELD).path("results") instanceof ArrayNode windows)) return;
        for (int w = windows.size() - 1; w >= 0; w--) {
            var window = windows.get(w);
            if (!window.required("guard").equals(guards.get(window.required("origin").textValue()))) {
                windows.remove(w);
                continue;
            }
            ArrayNode buttons = (ArrayNode) window.required("buttons");
            for (int i = buttons.size() - 1; i >= 0; i--) {
                JsonNode item = buttons.get(i);
                if (!item.required("guard").equals(guards.get(item.required("sourceInstanceId").textValue()))) buttons.remove(i);
            }
            if (buttons.isEmpty()) windows.remove(w);
        }
        if (windows.isEmpty()) state.remove(FIELD);
    }

    static ObjectNode lookup(ObjectNode state, String pack, String principal, String id) {
        JsonNode book = state.path(FIELD);
        if (pack.equals(book.path("pack").textValue()) && principal.equals(book.path("principal").textValue()))
            for (JsonNode window : book.path("results"))
                if (id.equals(window.path("id").textValue())) return (ObjectNode) window;
        throw stale();
    }

    static void take(ObjectNode window, Button chosen, String claimGroup, String claimGroupScope, String exclusiveGroup,
                     boolean repeatable) {
        ArrayNode buttons = (ArrayNode) window.required("buttons");
        for (int i = 0; i < buttons.size(); i++) if (Button.of(buttons.get(i)).equals(chosen)) {
            if (!java.util.Objects.equals(claimGroup, buttons.get(i).path("claimGroup").textValue())) throw stale();
            if (!claimGroupScope.equals(buttons.get(i).path("claimGroupScope").asText("source"))) throw stale();
            if (!java.util.Objects.equals(exclusiveGroup, buttons.get(i).path("exclusiveGroup").textValue())) throw stale();
            if (repeatable != buttons.get(i).path("repeatable").asBoolean(false)) throw stale();
            if (repeatable && claimGroup == null && exclusiveGroup == null) return;
            ArrayNode used = window.withArray("used");
            if (used.size() >= MAX_BUTTONS) throw RuleJson.error("/result", "Too many edits to this roll.");
            ObjectNode taken = (ObjectNode) buttons.remove(i);
            taken.remove("guard");
            used.add(taken);
            if (exclusiveGroup != null) {
                ExclusiveGroup group = ExclusiveGroup.of(taken);
                for (int other = buttons.size() - 1; other >= 0; other--)
                    if (buttons.get(other).has("exclusiveGroup") && group.equals(ExclusiveGroup.of(buttons.get(other))))
                        buttons.remove(other);
            }
            if (claimGroup != null) {
                ClaimGroup group = ClaimGroup.of(taken);
                for (int other = buttons.size() - 1; other >= 0; other--)
                    if (buttons.get(other).has("claimGroup") && group.equals(ClaimGroup.of(buttons.get(other)))) buttons.remove(other);
                ObjectNode entry = window.withArray("usedClaimGroups").addObject()
                        .put("sourceInstanceId", chosen.sourceInstanceId()).put("claimGroup", claimGroup);
                if ("result".equals(claimGroupScope)) entry.put("claimGroupScope", "result");
            }
            return;
        }
        throw stale();
    }

    static RuleRuntime.Unavailable stale() {
        return new RuleRuntime.Unavailable("result", "This result action is no longer current.");
    }

    private ResultWindows() {}
}
