package dmmt.online;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dmmt.model.DmProject;

public final class OnlineTokenConditions {
    public static final String DEFAULT_CUSTOM_ICON_ID = "cond_bleeding";
    private static final java.util.List<String> ICON_IDS = java.util.List.of(
            "cond_bleeding", "cond_blessed", "cond_blinded", "cond_burning", "cond_charmed",
            "cond_concentrating", "cond_dead", "cond_deafened", "cond_exhaustion", "cond_frightened",
            "cond_grappled", "cond_hasted", "cond_hidden", "cond_incapacitated", "cond_invisible",
            "cond_paralyzed", "cond_petrified", "cond_poisoned", "cond_prone", "cond_raging",
            "cond_restrained", "cond_slowed", "cond_stunned", "cond_unconscious");
    private static final java.util.Set<String> BUILT_IN_ICON_IDS =
            java.util.Set.of("cond_invisible", "cond_restrained", "cond_concentrating");
    private static final java.util.Set<String> CUSTOM_ICON_IDS = ICON_IDS.stream()
            .filter(iconId -> !BUILT_IN_ICON_IDS.contains(iconId))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    public static final String FULL_STRESS_EFFECT_ID = "full-stress:vulnerable";
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

    public static java.util.List<String> customIconIds() {
        return ICON_IDS.stream().filter(CUSTOM_ICON_IDS::contains).toList();
    }

    public static String iconId(String type, String requestedIconId) {
        return switch (type == null ? "" : type) {
            case "hidden" -> "cond_invisible";
            case "restrained" -> "cond_restrained";
            case "vulnerable" -> "cond_concentrating";
            case "custom" -> requestedIconId != null && CUSTOM_ICON_IDS.contains(requestedIconId)
                    ? requestedIconId : DEFAULT_CUSTOM_ICON_ID;
            default -> DEFAULT_CUSTOM_ICON_ID;
        };
    }

    public static String iconValidationProblem(String type, String iconId) {
        if ("custom".equals(type) && iconId != null && !iconId.isBlank()
                && !CUSTOM_ICON_IDS.contains(iconId)) {
            return "Choose an available custom condition icon.";
        }
        String builtInIconId = switch (type == null ? "" : type) {
            case "hidden" -> "cond_invisible";
            case "restrained" -> "cond_restrained";
            case "vulnerable" -> "cond_concentrating";
            default -> null;
        };
        if (builtInIconId != null && iconId != null && !iconId.isBlank() && !builtInIconId.equals(iconId)) {
            return "The built-in condition icon cannot be changed.";
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
        effect.remove("appliedByFullStress");
        String id = "online-condition:" + tokenId + ":" + condition.getId();
        effect.put("id", id);
        effect.put("name", condition.getName());
        effect.put("kind", "hidden".equals(condition.getType()) ? "buff" : "debuff");
        effect.put("text", description(condition.getType()));
        effect.putObject("onlineCondition").put("tokenId", tokenId).put("id", condition.getId())
                .put("iconId", iconId(condition.getType(), condition.getIconId()));
        return effect;
    }

    public static void normalizeFullStress(ObjectNode sheet) {
        var stress = sheet.path("stress");
        if (!stress.isObject()) return;
        boolean full = stress.path("slots").asInt() > 0
                && stress.path("on").asInt() >= stress.path("slots").asInt();
        var effects = sheet.path("effects");
        if (!effects.isMissingNode() && !effects.isArray()) {
            throw new IllegalArgumentException("The character sheet effects must be a list.");
        }
        if (effects instanceof com.fasterxml.jackson.databind.node.ArrayNode list) {
            for (int i = list.size() - 1; i >= 0; i--) {
                var entry = list.get(i);
                if (!full && isFullStressEffect(entry)) list.remove(i);
            }
            if (!full) return;
            for (var entry : list) {
                if (isFullStressEffect(entry) || matchesActorEffect(entry, "vulnerable")
                        || "Vulnerable".equalsIgnoreCase(entry.path("name").asText().trim())) return;
            }
        } else if (!full) return;
        var list = effects instanceof com.fasterxml.jackson.databind.node.ArrayNode array
                ? array : sheet.putArray("effects");
        var entry = effect(sheet.objectNode(), "", new DmProject.TokenCondition("vulnerable", "vulnerable", "Vulnerable"));
        entry.put("id", FULL_STRESS_EFFECT_ID).put("appliedByFullStress", true);
        list.add(entry);
    }

    private static boolean isFullStressEffect(com.fasterxml.jackson.databind.JsonNode effect) {
        return FULL_STRESS_EFFECT_ID.equals(effect.path("id").asText())
                || effect.path("appliedByFullStress").asBoolean();
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
            String iconId = link.path("iconId").asText(null);
            conditions.putIfAbsent(id, new DmProject.TokenCondition(id, type, name,
                    OnlineTokenConditions.iconId(type, iconId)));
        }
        return new java.util.ArrayList<>(conditions.values());
    }
}
