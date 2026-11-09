package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Reconciles campaign-wide Witch grants and the unique, transferable talisman inventory. */
final class WitchEffects {
    private WitchEffects() {}

    private record Grant(String actorId, ObjectNode marker, ObjectNode sheet) {
        String kind() { return marker.path("witchGrant").asText(); }
        String activation() { return marker.path("id").asText(); }
        String key() { return actorId + ":" + activation(); }
        String mirrorResource() { return "talisman-tokens:" + key(); }
        String resource() { return switch (kind()) {
            case "circle" -> "circle-of-power";
            case "runeWard" -> "rune-ward";
            case "sprites" -> "sprites";
            default -> "enchanted-talisman";
        }; }
        int tokens() { return Math.max(0, sheet.path("resources").path(resource()).asInt()); }
        boolean item() { return List.of("talisman", "lifeblood", "runeWard").contains(kind()); }
        boolean tokenBound() { return List.of("circle", "talisman", "runeWard", "sprites").contains(kind()); }
    }

    static void validateSave(ObjectNode sheet, ObjectNode previous) {
        if (!talismans(sheet).equals(talismans(previous)))
            throw new IllegalArgumentException("Enchanted Talismans can only be moved through an inventory transfer. Refresh the sheet.");
        for (JsonNode marker : sheet.path("effects")) {
            if (!List.of("talisman", "lifeblood", "runeWard").contains(marker.path("witchGrant").asText())) continue;
            boolean known = false;
            for (JsonNode old : previous.path("effects"))
                if (old.path("witchGrant").equals(marker.path("witchGrant")) && old.path("id").equals(marker.path("id"))) known = true;
            if (!known) throw new IllegalArgumentException("Create an Enchanted Talisman using its feature action.");
        }
        boolean activeTalisman = false;
        for (JsonNode marker : previous.path("effects"))
            if ("talisman".equals(marker.path("witchGrant").asText())) activeTalisman = true;
        if (activeTalisman && sheet.path("resources").path("enchanted-talisman").asInt()
                > previous.path("resources").path("enchanted-talisman").asInt())
            throw new IllegalArgumentException("Enchanted Talisman tokens can only be created by its feature action.");
    }

    private static List<JsonNode> talismans(JsonNode sheet) {
        List<JsonNode> items = new ArrayList<>();
        for (JsonNode item : sheet.path("inventory")) if (item.has("witchTalisman")) items.add(item);
        return items;
    }

    static void synchronize(Map<String, ObjectNode> sheets, Map<String, ObjectNode> previous) {
        Map<String, Grant> grants = new LinkedHashMap<>();
        sheets.forEach((id, sheet) -> {
            ObjectNode old = previous.get(id);
            normalize(sheet, old);
            if (!(sheet.path("effects") instanceof ArrayNode effects)) return;
            boolean rested = old != null && (sheet.path("longRestRevision").asLong() > old.path("longRestRevision").asLong()
                    || sheet.path("shortRestsInRow").asInt() > old.path("shortRestsInRow").asInt());
            for (int i = effects.size() - 1; i >= 0; i--) {
                if (!(effects.get(i) instanceof ObjectNode marker)) continue;
                String kind = marker.path("witchGrant").asText();
                if (!List.of("circle", "moonbeam", "talisman", "lifeblood", "runeWard", "sprites").contains(kind)) continue;
                if (rested && List.of("circle", "talisman").contains(kind)
                        || "lifeblood".equals(kind) && sheet.path("hp").path("on").asInt() <= 0) {
                    effects.remove(i);
                    continue;
                }
                marker.put("until", List.of("lifeblood", "runeWard", "sprites").contains(kind) ? "manual" : "moonbeam".equals(kind) ? "scene" : "rest");
                if (marker.path("id").asText().isBlank()) marker.put("id", "witch-" + UUID.randomUUID());
                if (old != null) for (JsonNode oldMarker : old.path("effects")) {
                    if (!marker.path("id").equals(oldMarker.path("id"))
                            || !kind.equals(oldMarker.path("witchGrant").asText())) continue;
                    for (String field : List.of("witchRecipients", "witchTalismanCreated")) {
                        marker.remove(field);
                        if (oldMarker.has(field)) marker.set(field, oldMarker.get(field).deepCopy());
                    }
                }
                Grant grant = new Grant(id, marker, sheet);
                if (grant.tokenBound() && grant.tokens() == 0) {
                    effects.remove(i);
                    continue;
                }
                grants.put(grant.key(), grant);
            }
        });

        // A reduction is a spend only for an existing holder, never an initial grant or a transfer.
        for (var entry : sheets.entrySet()) {
            ObjectNode sheet = entry.getValue();
            ObjectNode old = previous.get(entry.getKey());
            if (old == null) continue;
            for (Grant grant : heldGrants(old, grants)) {
                if (grant.item() && !hasItem(sheet, grant) || !sheet.path("resources").has(grant.mirrorResource())) continue;
                JsonNode requested = sheet.path("resources").path(grant.mirrorResource());
                if (!requested.isInt() || requested.asInt() < 0)
                    throw new IllegalArgumentException("Talisman tokens must be a nonnegative whole number.");
                int before = old.path("resources").path(grant.mirrorResource()).asInt();
                if (requested.asInt() > before)
                    throw new IllegalArgumentException("Talisman tokens changed on the host. Refresh before spending.");
                int spend = before - requested.asInt();
                if (spend > grant.tokens())
                    throw new IllegalArgumentException("Not enough talisman tokens. Refresh before spending.");
                grant.sheet.withObject("/resources").put(grant.resource(), grant.tokens() - spend);
            }
        }

        for (Grant grant : List.copyOf(grants.values())) {
            if (grant.tokenBound() && grant.tokens() == 0) {
                ArrayNode effects = grant.sheet.withArray("effects");
                for (int i = effects.size() - 1; i >= 0; i--)
                    if (effects.get(i) == grant.marker) effects.remove(i);
                grants.remove(grant.key());
            } else if (grant.item() && !grant.marker.path("witchTalismanCreated").asBoolean()) {
                ObjectNode item = grant.sheet.withArray("inventory").addObject();
                item.put("name", "runeWard".equals(grant.kind()) ? "Rune Ward" : "lifeblood".equals(grant.kind()) ? "Lifeblood Talisman" : "Enchanted Talisman").put("qty", 1).put("type", "gear")
                        .put("note", "lifeblood".equals(grant.kind())
                                ? "Enchanted with the caster's life essence. When marking 2 or more HP, spend 1 Hope to mark 1 fewer. "
                                    + "Disappears when the caster has no marked HP or casts this spell again."
                                : "Imbued with protective essence. The holder can spend tokens to reduce HP marked by one per token. "
                                + "Tokens are tracked on the creator. Expires when the creator rests or its tokens are depleted.");
                if ("runeWard".equals(grant.kind()))
                    item.put("note", "Spend 1 Hope to reduce incoming damage by 1d8. On an 8 the ward is destroyed after reducing damage. The caster can infuse it again after resting.");
                item.putObject("witchTalisman").put("sourceActorId", grant.actorId).put("activationId", grant.activation());
                grant.marker.put("witchTalismanCreated", true);
                inventoryChanged(grant.sheet);
            }
        }

        Map<String, String> holders = new LinkedHashMap<>();
        sheets.forEach((id, sheet) -> {
            if (!(sheet.path("inventory") instanceof ArrayNode items)) return;
            for (int i = items.size() - 1; i >= 0; i--) {
                JsonNode item = items.get(i);
                if (!item.has("witchTalisman")) continue;
                String key = itemKey(item);
                Grant grant = grants.get(key);
                if (grant == null || !grant.item() || item.path("qty").asInt() != 1
                        || holders.putIfAbsent(key, id) != null) {
                    items.remove(i);
                    inventoryChanged(sheet);
                }
            }
        });

        sheets.forEach((id, sheet) -> {
            List<Grant> held = grants.values().stream()
                    .filter(g -> "sprites".equals(g.kind()) || List.of("talisman", "runeWard").contains(g.kind()) && id.equals(holders.get(g.key()))).toList();
            List<String> staleMirrors = new ArrayList<>();
            sheet.path("resources").fieldNames().forEachRemaining(resource -> {
                if ("talisman-tokens".equals(resource) || resource.startsWith("talisman-tokens:"))
                    staleMirrors.add(resource);
            });
            if (!held.isEmpty() || !staleMirrors.isEmpty()) {
                ObjectNode resources = sheet.withObject("/resources");
                staleMirrors.forEach(resource -> resources.put(resource, 0));
                for (Grant grant : held) resources.put(grant.mirrorResource(), grant.tokens());
            }
            if (grants.isEmpty() && !sheet.path("effects").isArray()) return;
            ArrayNode effects = sheet.withArray("effects");
            Map<String, JsonNode> existing = new LinkedHashMap<>();
            for (int i = effects.size() - 1; i >= 0; i--) {
                JsonNode effect = effects.get(i);
                if (!effect.has("witchLinked")) continue;
                existing.put(effect.path("sourceActorId").asText() + ":" + effect.path("activationId").asText(), effect);
                effects.remove(i);
            }
            for (Grant grant : grants.values()) {
                if (grant.item()) {
                    if (id.equals(holders.get(grant.key()))) effects.add(linked(grant));
                    continue;
                }
                ArrayNode recipients = grant.marker.withArray("witchRecipients");
                boolean issued = false;
                for (JsonNode recipient : recipients) if (id.equals(recipient.asText())) issued = true;
                if (issued && !existing.containsKey(grant.key())) continue;
                if (!issued) recipients.add(id);
                effects.add(linked(grant));
            }
            normalize(sheet, previous.get(id));
        });
    }

    static void normalize(ObjectNode sheet, ObjectNode previous) {
        if (sheet.path("effects") instanceof ArrayNode effects) {
            for (int i = effects.size() - 1; i >= 0; i--) {
                JsonNode effect = effects.get(i);
                boolean depleted = effect.has("tokenResource")
                        && sheet.path("resources").path(effect.path("tokenResource").asText()).asInt() <= 0;
                boolean markedHp = effect.path("endOnMarkedHp").asBoolean() && previous != null
                        && sheet.path("hp").path("on").asInt() > previous.path("hp").path("on").asInt()
                        && sheet.path("stress").path("on").asInt() <= previous.path("stress").path("on").asInt()
                        && !maintenancePaid(effect, previous);
                if (depleted || markedHp) effects.remove(i);
            }
        }
        List<JsonNode> resources = new ArrayList<>();
        collectBoundResources(sheet, resources);
        for (JsonNode resource : resources) {
            if (!DruidEffects.hasEffect(sheet, resource.path("whileEffect").asText())
                    && sheet.path("resources").path(resource.path("id").asText()).asInt() != 0)
                sheet.withObject("/resources").put(resource.path("id").asText(), 0);
        }
    }

    private static boolean maintenancePaid(JsonNode effect, ObjectNode previous) {
        JsonNode receipt = effect.path("hpMaintenance");
        if (!receipt.isIntegralNumber() || !receipt.canConvertToLong() || receipt.asLong() < 1) return false;
        if (effect.path("id").asText().isBlank()) return false;
        for (JsonNode old : previous.path("effects")) {
            if (!effect.path("id").equals(old.path("id"))) continue;
            JsonNode oldReceipt = old.path("hpMaintenance");
            if (!oldReceipt.isMissingNode() && (!oldReceipt.isIntegralNumber()
                    || !oldReceipt.canConvertToLong() || oldReceipt.asLong() < 0)) return false;
            long before = oldReceipt.asLong(0);
            return before < Long.MAX_VALUE && receipt.asLong() == before + 1;
        }
        return false;
    }

    private static void collectBoundResources(JsonNode node, List<JsonNode> resources) {
        if (node.isObject() && node.path("resources").isArray())
            for (JsonNode resource : node.path("resources"))
                if (resource.hasNonNull("whileEffect") && !resource.path("id").asText().isBlank()) resources.add(resource);
        if (node.isContainerNode())
            for (JsonNode child : node) collectBoundResources(child, resources);
    }

    private static List<Grant> heldGrants(JsonNode sheet, Map<String, Grant> grants) {
        List<Grant> result = new ArrayList<>();
        for (Grant grant : grants.values())
            if ("sprites".equals(grant.kind()) || List.of("talisman", "runeWard").contains(grant.kind()) && hasItem(sheet, grant)) result.add(grant);
        return result;
    }

    private static boolean hasItem(JsonNode sheet, Grant grant) {
        for (JsonNode item : sheet.path("inventory"))
            if (grant.key().equals(itemKey(item)) && item.path("qty").asInt() == 1) return true;
        return false;
    }

    private static String itemKey(JsonNode item) {
        JsonNode marker = item.path("witchTalisman");
        return marker.path("sourceActorId").asText() + ":" + marker.path("activationId").asText();
    }

    private static void inventoryChanged(ObjectNode sheet) {
        sheet.put("inventoryRevision", sheet.path("inventoryRevision").asLong() + 1);
    }

    private static ObjectNode linked(Grant grant) {
        String name = switch (grant.kind()) {
            case "circle" -> "Circle of Power";
            case "moonbeam" -> "Moonbeam";
            case "lifeblood" -> "Lifeblood Talisman";
            case "runeWard" -> "Rune Ward";
            case "sprites" -> "Forest Sprites";
            default -> "Enchanted Talisman";
        };
        ObjectNode effect = JsonNodeFactory.instance.objectNode()
                .put("id", "witch-" + grant.key()).put("name", name + " benefits").put("kind", "buff")
                .put("until", List.of("lifeblood", "runeWard", "sprites").contains(grant.kind()) ? "manual" : "moonbeam".equals(grant.kind()) ? "scene" : "rest")
                .put("witchLinked", grant.kind()).put("sourceActorId", grant.actorId)
                .put("sourceKey", grant.marker.path("sourceKey").asText("feat:" + name))
                .put("source", name).put("activationId", grant.activation());
        if ("circle".equals(grant.kind())) {
            effect.put("text", "+2 damage thresholds, +2 Evasion, and +2 attack rolls while within the Circle of Power. "
                    + "Remove these benefits manually when you leave the circle; a new activation grants them again.");
            ArrayNode modifiers = effect.putArray("modifiers");
            modifiers.addObject().put("stat", "thresholds").put("value", 2);
            modifiers.addObject().put("stat", "evasion").put("value", 2);
        }
        if (List.of("runeWard", "sprites").contains(grant.kind())) {
            effect.put("tokenResource", grant.mirrorResource());
            effect.putArray("resources").addObject().put("id", grant.mirrorResource()).put("label", name + " tokens").put("initial", 0);
            if ("runeWard".equals(grant.kind())) {
                ObjectNode protect = effect.putArray("mitigation").addObject().put("id", "ward").put("label", "Rune Ward")
                        .put("description", "Spend 1 Hope to reduce damage by 1d8. On an 8 destroy the ward after the reduction.")
                        .put("destroyResourceOnMax", grant.mirrorResource()).put("reportRoll", true);
                protect.putObject("cost").put("hope", 1);
                protect.putObject("roll").put("sides", 8);
            } else {
                effect.put("text", "Use within Melee range of a sprite. Each benefit consumes one sprite shared by the party.");
                ObjectNode attack = effect.putArray("rollOptions").addObject().put("id", "sprite-attack").put("label", "Forest Sprite nearby")
                        .put("roll", "attack").put("value", 3);
                attack.putObject("cost").put(grant.mirrorResource(), 1);
                ObjectNode armor = effect.putArray("mitigation").addObject().put("id", "sprite-armor").put("label", "Forest Sprite: additional Armor Slot")
                        .put("armorSlots", 1);
                armor.putObject("when").put("armor", true);
                armor.putObject("cost").put(grant.mirrorResource(), 1);
            }
        } else if ("lifeblood".equals(grant.kind())) {
            effect.put("text", "When marking 2 or more HP, spend 1 Hope to mark 1 fewer. "
                    + "Ends when the caster has no marked HP or casts Lifeblood Talisman again.");
            ObjectNode protect = effect.putArray("mitigation").addObject().put("id", "protect")
                    .put("label", name).put("hp", -1).put("markedHpOnly", true);
            protect.putObject("when").put("minMarkedHp", 2);
            protect.putObject("cost").put("hope", 1);
        } else if (!"talisman".equals(grant.kind())) {
            effect.putArray("rollOptions").addObject().put("id", grant.kind()).put("label", name)
                    .put("roll", "circle".equals(grant.kind()) ? "attack" : "spellcast")
                    .put("value", "circle".equals(grant.kind()) ? 2 : 1).put("defaultOn", true);
            if ("moonbeam".equals(grant.kind()))
                effect.put("text", "+1 Spellcast while within the Close-range moonlight, and you can see through illusions. "
                        + "Remove these benefits manually when you leave the moonlight.");
        } else {
            effect.put("text", "Protective essence from creator " + grant.actorId
                    + ". Spend tokens from this creator's Enchanted Talisman pool to reduce HP marked by one per token. "
                    + "Expires when the creator rests or this pool is depleted.");
            effect.put("tokenResource", grant.mirrorResource());
            effect.putArray("resources").addObject().put("id", grant.mirrorResource()).put("label", "Talisman tokens").put("initial", 0);
            ObjectNode protect = effect.putArray("mitigation").addObject().put("id", "protect")
                    .put("label", "Enchanted Talisman").put("hp", -1).put("quantity", true).put("markedHpOnly", true)
                    .put("description", "Spend this talisman's tokens to reduce HP marked by one per token.");
            protect.putObject("cost").put(grant.mirrorResource(), 1);
            protect.putObject("when").put("markingHp", true);
        }
        return effect;
    }
}
