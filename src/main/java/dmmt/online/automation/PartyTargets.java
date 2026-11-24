package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dmmt.online.CampaignStore;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Request-local view of saved, host-selected characters. No sheet snapshots leave the host. */
public final class PartyTargets {
    public record Member(String id, String name, String sheetVersion, long revision, Function<ObjectNode, ObjectNode> snapshot) {}
    record Selection(List<RuleDispatcher.RecipientCandidate> recipients, RuleDispatcher.TargetIntent intent) {}
    private record ProjectionKey(String id, JsonNode event) {}

    private final String packVersion, sourceVersion;
    private final Function<String, List<Member>> loadScope;
    private final Map<String, List<Member>> scopes = new HashMap<>();
    private final Map<ProjectionKey, ObjectNode> actors = new HashMap<>();
    private List<RuleDispatcher.RecipientUpdate> updates = List.of();

    void stage(List<RuleDispatcher.RecipientUpdate> updates) { this.updates = List.copyOf(updates); }
    public List<RuleDispatcher.RecipientUpdate> updates() { return updates; }
    void invalidateSnapshots() { actors.clear(); }

    public PartyTargets(String packVersion, String sourceVersion, Function<String, List<Member>> loadScope) {
        this.packVersion = packVersion;
        this.sourceVersion = sourceVersion;
        this.loadScope = loadScope;
    }

    ObjectNode describe(JsonNode binding, String ruleId, ObjectNode context) {
        JsonNode selection = binding.path("targetSelection");
        List<Member> members = eligible(selection, context);
        ObjectNode result = ((ObjectNode) selection).deepCopy();
        result.remove("where");
        ArrayNode options = result.putArray("options");
        boolean all = "all".equals(selection.path("cardinality").asText());
        for (Member member : members) {
            ObjectNode option = options.addObject().put("id", member.id()).put("label", member.name()).put("revision", member.revision());
            if (!all) option.put("version", version(binding, ruleId, context, List.of(member)));
        }
        if (all) result.put("version", version(binding, ruleId, context, members));
        return result;
    }

    Selection resolve(JsonNode binding, ObjectNode context, RuleRuntime.Intent action, JsonNode request) {
        JsonNode selection = binding.required("targetSelection");
        boolean all = "all".equals(selection.path("cardinality").asText());
        if (all) RuleJson.fields(request, "/request/target", "version");
        else RuleJson.fields(request, "/request/target", "version", "recipientId");
        String expected = RuleJson.text(request.get("version"), "/request/target/version");
        String selected = all ? null : RuleJson.id(request.get("recipientId"), "/request/target/recipientId");
        List<Member> members = eligible(selection, context);
        if (!all) members = members.stream().filter(member -> member.id().equals(selected)).toList();
        if (members.isEmpty() || !expected.equals(version(binding, action.ruleId(), context, members)))
            throw new RuleRuntime.Unavailable("staleRecipient", "The eligible characters or their sheets changed. Refresh this action.");
        var recipients = members.stream().map(member -> new RuleDispatcher.RecipientCandidate(member.id(),
                snapshot(member, context), member.revision())).toList();
        return new Selection(recipients, new RuleDispatcher.TargetIntent(action, selected, all ? null : members.getFirst().revision()));
    }

    private List<Member> eligible(JsonNode selection, ObjectNode context) {
        String scope = selection.required("scope").asText();
        List<Member> scoped = scopes.computeIfAbsent(scope, key -> {
            var loaded = new ArrayList<>(loadScope.apply(key));
            if (loaded.size() > 128) throw RuleJson.error("/recipients", "An action is limited to 128 characters.");
            var ids = new HashSet<String>();
            for (Member member : loaded) if (!ids.add(member.id())) throw RuleJson.error("/recipients", "Duplicate target.");
            loaded.sort(Comparator.comparing(Member::id));
            return List.copyOf(loaded);
        });
        return scoped.stream().filter(member -> !(selection.path("excludeSource").asBoolean()
                        && member.id().equals(context.at("/actor/id").asText())))
                .filter(member -> !selection.has("where") || RuleRuntime.recipientEligible(selection, context, snapshot(member, context)))
                .toList();
    }

    private ObjectNode snapshot(Member member, ObjectNode context) {
        if (member.id().equals(context.at("/actor/id").asText())) return (ObjectNode) context.required("actor");
        ObjectNode event = RuleJson.object(context.path("event"), "/event");
        var key = new ProjectionKey(member.id(), event.deepCopy());
        return actors.computeIfAbsent(key, ignored -> member.snapshot().apply(event.deepCopy()));
    }

    private String version(JsonNode binding, String ruleId, ObjectNode context, List<Member> members) {
        ArrayNode key = RuleJson.JSON.createArrayNode().add(packVersion).add(sourceVersion).add(context.at("/actor/id").asText())
                .add(context.at("/source/instanceId").asText()).add(ruleId).add(binding.required("id").asText());
        for (Member member : members) key.addArray().add(member.id()).add(member.sheetVersion()).add(member.revision());
        return CampaignStore.sha256(key.toString().getBytes(StandardCharsets.UTF_8));
    }
}
