package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Ephemeral, untrusted presentation hints. Never used to authorize an action or persisted. */
final class ChatActionReports {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    record Ref(long id, long ts) {}
    private record Viewer(String playerId, String name, LinkedHashMap<Ref, ArrayNode> reports) {}
    private final Map<String, Viewer> viewers = new LinkedHashMap<>();

    static ArrayNode validate(JsonNode input) {
        if (!input.isArray() || input.size() > 128) throw new IllegalArgumentException("Invalid chat action summary.");
        ArrayNode result = MAPPER.createArrayNode();
        for (JsonNode action : input) {
            if (!action.path("label").isTextual() || action.path("label").asText().isBlank()
                    || action.path("label").asText().length() > 200 || !action.path("disabled").isBoolean()
                    || !action.path("detail").isTextual() || action.path("detail").asText().length() > 200)
                throw new IllegalArgumentException("Invalid chat action label.");
            ObjectNode copy = result.addObject();
            copy.put("label", action.path("label").asText()).put("disabled", action.path("disabled").asBoolean())
                    .put("detail", action.path("detail").asText());
        }
        return result;
    }

    synchronized List<ObjectNode> update(String connection, String playerId, String name, Ref ref, ArrayNode actions) {
        Viewer viewer = viewers.computeIfAbsent(connection, key -> new Viewer(playerId, name, new LinkedHashMap<>()));
        viewer.reports().put(ref, actions.deepCopy());
        List<ObjectNode> updates = new ArrayList<>();
        if (viewer.reports().size() > 300) {
            Ref removed = viewer.reports().firstEntry().getKey();
            viewer.reports().remove(removed);
            updates.add(event(removed));
        }
        updates.add(event(ref));
        return updates;
    }

    synchronized List<ObjectNode> disconnect(String connection) {
        Viewer removed = viewers.remove(connection);
        return removed == null ? List.of() : removed.reports().keySet().stream().map(this::event).toList();
    }

    synchronized void delete(Ref ref) {
        viewers.values().forEach(viewer -> viewer.reports().remove(ref));
    }

    synchronized ArrayNode summary(Ref ref) {
        Map<String, ObjectNode> players = new LinkedHashMap<>();
        for (Viewer viewer : viewers.values()) {
            ArrayNode actions = viewer.reports().get(ref);
            if (actions == null || actions.isEmpty()) continue;
            ObjectNode player = players.computeIfAbsent(viewer.playerId(), id -> {
                ObjectNode node = MAPPER.createObjectNode().put("playerId", id).put("name", viewer.name());
                node.putArray("actions");
                return node;
            });
            ArrayNode combined = (ArrayNode) player.path("actions");
            for (JsonNode action : actions) {
                boolean duplicate = false;
                for (int i = 0; i < combined.size(); i++) {
                    JsonNode existing = combined.get(i);
                    if (!existing.path("label").equals(action.path("label"))) continue;
                    duplicate = true;
                    if (existing.path("disabled").asBoolean() && !action.path("disabled").asBoolean())
                        combined.set(i, action.deepCopy());
                    break;
                }
                if (!duplicate) combined.add(action.deepCopy());
            }
        }
        return MAPPER.createArrayNode().addAll(players.values());
    }

    private ObjectNode event(Ref ref) {
        ObjectNode event = MAPPER.createObjectNode().put("type", "chatActions").put("id", ref.id()).put("ts", ref.ts());
        event.set("players", summary(ref));
        return event;
    }
}
