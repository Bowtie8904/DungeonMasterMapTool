package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

final class SpecialCardActions {
    record Result(int fear, List<Campaign.Countdown> countdowns, List<String> lines, DiceRoller.Result roll) {}

    static Result apply(ObjectNode sheet, Campaign.Actor actor, Campaign campaign, String kind, JsonNode request,
                        int maxDice, java.util.Random random) {
        var countdowns = new ArrayList<>(campaign.getCountdowns());
        var lines = new ArrayList<String>();
        int fear = campaign.getFear();
        DiceRoller.Result roll = null;
        switch (kind) {
            case "vitality" -> {
                if (sheet.path("vitalityBenefits").size() > 0) throw new IllegalArgumentException("Vitality has already been used permanently.");
                JsonNode choices = request.path("choices");
                var distinct = new HashSet<String>();
                if (!choices.isArray() || choices.size() != 2) throw new IllegalArgumentException("Choose two distinct Vitality benefits.");
                for (JsonNode choice : choices)
                    if (!choice.isTextual() || !List.of("hp", "stress", "thresholds").contains(choice.asText()) || !distinct.add(choice.asText()))
                        throw new IllegalArgumentException("Choose two distinct Vitality benefits.");
                var totalChanges = new java.util.HashMap<String, Integer>();
                for (String trackName : List.of("hp", "stress")) if (distinct.contains(trackName)) {
                    if (!(sheet.path(trackName) instanceof ObjectNode track)) throw new IllegalArgumentException("Missing character resource track.");
                    int slots = track.path("slots").asInt() + 1;
                    totalChanges.put(trackName, Math.max(slots, track.path("total").asInt()) - track.path("total").asInt());
                    track.put("slots", slots);
                    track.put("total", Math.max(slots, track.path("total").asInt()));
                    lines.add("Permanently gained one " + ("hp".equals(trackName) ? "Hit Point" : "Stress") + " slot.");
                }
                if (distinct.contains("thresholds")) lines.add("Permanently gained +2 to both damage thresholds.");
                sheet.set("vitalityBenefits", choices.deepCopy());
                migrateVitality(sheet);
                ObjectNode totals = ((ObjectNode) vitalityEffect(sheet)).putObject("vitalityTotalChanges");
                totalChanges.forEach((stat, change) -> { if (change != 0) totals.put(stat, change); });
                if (!actor.isTestingCharacter()) {
                    var loadout = sheet.withArray("loadout");
                    boolean moved = false;
                    for (int i = 0; i < loadout.size(); i++) if ("Vitality".equals(loadout.get(i).path("name").asText())) {
                        sheet.withArray("vault").add(loadout.remove(i));
                        moved = true;
                        break;
                    }
                    if (!moved) throw new IllegalArgumentException("Vitality must be in the active loadout.");
                    lines.add("Vitality is permanently vaulted.");
                } else lines.add("Testing: Vitality remains in the fixed loadout, permanently used and unavailable.");
            }
            case "massDisguise" -> {
                if (!(sheet.path("stress") instanceof ObjectNode stress) || stress.path("on").asInt() >= stress.path("slots").asInt())
                    throw new IllegalArgumentException("Not enough unmarked Stress.");
                stress.put("on", stress.path("on").asInt() + 1);
                String name = ("Mass Disguise — " + actor.getName()).replaceAll("[\\r\\n]", " ");
                countdowns.add(new Campaign.Countdown(UUID.randomUUID().toString(), name.substring(0, Math.min(100, name.length())), 8, 8));
                lines.add("Marked 1 Stress and activated Mass Disguise Countdown (8). The GM ticks it down as a consequence; the disguises drop when it triggers.");
            }
            case "nightTerror" -> {
                JsonNode amount = request.path("amount");
                if (!amount.isInt() || amount.asInt() < 0 || amount.asInt() > fear)
                    throw new IllegalArgumentException("Choose a whole number of stolen Fear from 0 to the live GM pool (" + fear + ").");
                int count = amount.asInt();
                fear -= count;
                if (count > 0) roll = DiceRoller.roll(count + "d6", maxDice, random);
                lines.add("Stole and discarded " + count + " GM Fear (remaining: " + fear + ").");
                lines.add("Damage to each Horrified target: " + (roll == null ? "0 (no Fear stolen)" : roll.total()) + ".");
            }
            default -> throw new IllegalArgumentException("Unknown special card action.");
        }
        return new Result(fear, countdowns, lines, roll);
    }

    static void migrateVitality(ObjectNode sheet) {
        if (sheet.path("vitalityBenefits").isEmpty() || sheet.path("vitalityEffectRemoved").asBoolean()
                || vitalityEffect(sheet) != null) return;
        ObjectNode effect = sheet.withArray("effects").addObject();
        effect.put("id", "vitality-permanent").put("name", "Vitality").put("kind", "buff")
                .put("until", "manual").put("source", "Vitality").put("vitality", true);
        ObjectNode changes = effect.putObject("manualChanges");
        ObjectNode totals = effect.putObject("vitalityTotalChanges");
        var descriptions = new ArrayList<String>();
        for (JsonNode benefit : sheet.path("vitalityBenefits")) {
            String stat = benefit.asText();
            if ("hp".equals(stat) || "stress".equals(stat)) {
                changes.put(stat, 1);
                JsonNode track = sheet.path(stat);
                if (track.path("total").asInt() == track.path("slots").asInt()) totals.put(stat, 1);
                descriptions.add("+1 " + ("hp".equals(stat) ? "HP" : "Stress") + " slot");
            } else if ("thresholds".equals(stat)) {
                effect.withArray("modifiers").addObject().put("stat", "thresholds").put("value", 2);
                descriptions.add("+2 to both damage thresholds");
            }
        }
        effect.put("text", "Permanent benefits: " + String.join(", ", descriptions)
                + ". Removable only in sheet edit mode; removal reverses these benefits.");
    }

    private static JsonNode vitalityEffect(ObjectNode sheet) {
        for (JsonNode effect : sheet.path("effects")) if (effect.path("vitality").asBoolean()) return effect;
        return null;
    }

    static void preserveSave(ObjectNode sheet, ObjectNode previous, boolean testing) {
        preserveSave(sheet, previous, testing, false);
    }

    static void preserveSave(ObjectNode sheet, ObjectNode previous, boolean testing, boolean versionChecked) {
        migrateVitality(previous);
        JsonNode oldEffect = vitalityEffect(previous);
        JsonNode incomingEffect = vitalityEffect(sheet);
        JsonNode removal = sheet.remove("vitalityRemoval");
        if (removal != null) {
            // Edit mode is browser-local. Require explicit owner edit intent AND a current If-Match,
            // never infer authorization from a missing effect or a client-supplied tombstone.
            if (!versionChecked || oldEffect == null || !removal.path("editMode").asBoolean()
                    || !oldEffect.path("id").equals(removal.path("effectId")) || incomingEffect != null)
                throw new IllegalArgumentException("Remove Vitality in sheet edit mode on the latest sheet.");
            for (String stat : List.of("hp", "stress")) {
                int adjustment = oldEffect.path("manualChanges").path(stat).asInt();
                if (adjustment == 0) continue;
                int slots = Math.max(0, previous.path(stat).path("slots").asInt() - adjustment);
                int total = Math.max(slots, previous.path(stat).path("total").asInt()
                        - oldEffect.path("vitalityTotalChanges").path(stat).asInt());
                if (sheet.path(stat).path("slots").asInt() != slots || sheet.path(stat).path("total").asInt() != total)
                    throw new IllegalArgumentException("Vitality removal must reverse its granted slots. Refresh and retry.");
                if (sheet.path(stat) instanceof ObjectNode track)
                    track.put("on", Math.min(track.path("on").asInt(), slots));
            }
            sheet.put("vitalityEffectRemoved", true);
        } else if (previous.path("vitalityEffectRemoved").asBoolean()) {
            if (incomingEffect != null || !sheet.path("vitalityEffectRemoved").asBoolean())
                throw new IllegalArgumentException("Vitality was removed. Reload the latest sheet; its benefits cannot be restored by saving.");
            sheet.put("vitalityEffectRemoved", true);
        } else {
            sheet.remove("vitalityEffectRemoved");
            if (oldEffect != null && !oldEffect.equals(incomingEffect))
                throw new IllegalArgumentException("Vitality can only be removed in sheet edit mode; reload the latest sheet.");
            if (oldEffect == null && incomingEffect != null)
                throw new IllegalArgumentException("Use Vitality's card action to grant its benefits.");
        }
        if (previous.has("vitalityBenefits")) sheet.set("vitalityBenefits", previous.get("vitalityBenefits").deepCopy());
        else sheet.remove("vitalityBenefits");
        if (!testing && previous.path("vitalityBenefits").size() > 0) {
            var loadout = sheet.withArray("loadout");
            for (int i = loadout.size() - 1; i >= 0; i--)
                if ("Vitality".equals(loadout.get(i).path("name").asText())) {
                    loadout.remove(i);
                }
            if (previous.path("vault").isArray()) {
                var vault = sheet.withArray("vault");
                for (int i = vault.size() - 1; i >= 0; i--)
                    if ("Vitality".equals(vault.get(i).path("name").asText())) vault.remove(i);
                for (JsonNode card : previous.path("vault")) if ("Vitality".equals(card.path("name").asText())) vault.add(card.deepCopy());
            }
        }
    }

    private SpecialCardActions() {}
}
