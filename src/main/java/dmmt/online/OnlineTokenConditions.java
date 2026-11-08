package dmmt.online;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dmmt.model.DmProject;

public final class OnlineTokenConditions {
    public static final String HIDDEN_TEXT = "Any rolls against a Hidden creature have disadvantage. After an adversary moves to where they would see you, you move into their line of sight, or you make an attack, you are no longer Hidden.";
    public static final String RESTRAINED_TEXT = "Restrained characters can’t move, but you can still take actions from their current position.";
    public static final String VULNERABLE_TEXT = "When a creature is Vulnerable, all rolls targeting them have advantage.";

    private OnlineTokenConditions() {
    }

    public static String validationProblem(String type, String name) {
        if (type == null || !java.util.Set.of("hidden", "restrained", "vulnerable", "custom").contains(type)) {
            return "Choose a supported condition.";
        }
        String normalized = name == null ? "" : name.trim();
        if (normalized.isEmpty() || normalized.length() > 80
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            return "Condition names must be 1-80 characters without control characters.";
        }
        if (!"custom".equals(type) && !normalized.equals(builtInName(type))) {
            return "The built-in condition name cannot be changed.";
        }
        return null;
    }

    public static String builtInName(String type) {
        return switch (type == null ? "" : type) {
            case "hidden" -> "Hidden";
            case "restrained" -> "Restrained";
            case "vulnerable" -> "Vulnerable";
            default -> null;
        };
    }

    public static String description(String type) {
        return switch (type == null ? "" : type) {
            case "hidden" -> HIDDEN_TEXT;
            case "restrained" -> RESTRAINED_TEXT;
            case "vulnerable" -> VULNERABLE_TEXT;
            default -> "";
        };
    }

    public static ObjectNode effect(ObjectNode effect, String tokenId, DmProject.TokenCondition condition) {
        String id = "online-condition:" + tokenId + ":" + condition.getId();
        effect.put("id", id);
        effect.put("name", condition.getName());
        effect.put("kind", "hidden".equals(condition.getType()) ? "buff" : "debuff");
        effect.put("text", description(condition.getType()));
        effect.putObject("onlineCondition").put("tokenId", tokenId).put("id", condition.getId());
        return effect;
    }

    public static boolean matchesEffect(com.fasterxml.jackson.databind.JsonNode effect, String tokenId, String conditionId) {
        return effect != null && effect.path("onlineCondition").path("tokenId").asText().equals(tokenId)
                && effect.path("onlineCondition").path("id").asText().equals(conditionId);
    }

    public static boolean matchesActorEffect(com.fasterxml.jackson.databind.JsonNode effect, String conditionId) {
        return effect != null && effect.path("onlineCondition").isObject()
                && effect.path("onlineCondition").path("id").asText().equals(conditionId);
    }

    public static java.util.List<DmProject.TokenCondition> actorConditions(com.fasterxml.jackson.databind.JsonNode sheet) {
        var conditions = new java.util.LinkedHashMap<String, DmProject.TokenCondition>();
        for (var effect : sheet.path("effects")) {
            var link = effect.path("onlineCondition");
            if (!link.isObject()) continue;
            String id = link.path("id").asText();
            String type = builtInName(id) == null ? "custom" : id;
            String name = effect.path("name").asText();
            if (id.isBlank() || validationProblem(type, name) != null) {
                throw new IllegalArgumentException("The character sheet contains an invalid linked condition.");
            }
            conditions.putIfAbsent(id, new DmProject.TokenCondition(id, type, name));
        }
        return new java.util.ArrayList<>(conditions.values());
    }
}
