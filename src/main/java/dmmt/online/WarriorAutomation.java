package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

final class WarriorAutomation {
    private WarriorAutomation() {}

    static void synchronizeDowntime(Map<String, ObjectNode> sheets) {
        JsonNode grantingFeature = null;
        for (ObjectNode sheet : sheets.values()) {
            for (JsonNode feature : sheet.path("features"))
                if (feature.path("automation").path("partyDowntime").asBoolean()) grantingFeature = feature;
        }
        for (ObjectNode sheet : sheets.values()) {
            if (grantingFeature == null && !sheet.path("effects").isArray()) continue;
            ArrayNode effects = sheet.withArray("effects");
            for (int i = effects.size() - 1; i >= 0; i--)
                if (effects.get(i).path("partyDowntimeGrant").asBoolean()) effects.remove(i);
            boolean owns = false;
            for (JsonNode feature : sheet.path("features"))
                if (feature.path("automation").path("partyDowntime").asBoolean()) owns = true;
            if (grantingFeature == null || owns) continue;
            ObjectNode effect = effects.addObject();
            effect.put("id", "party-martial-preparation").put("name", grantingFeature.path("name").asText())
                    .put("kind", "buff").put("until", "manual").put("partyDowntimeGrant", true)
                    .put("source", grantingFeature.path("name").asText()).put("text", grantingFeature.path("text").asText());
            effect.set("actions", grantingFeature.path("automation").path("actions").deepCopy());
        }
    }

    static boolean resourceDice(JsonNode detail, String resource) {
        for (JsonNode note : detail.path("notes"))
            if ("Dice resource".equals(note.path("label").asText()) && resource.equals(note.path("impact").asText())) return true;
        return false;
    }

    static boolean hasOnes(JsonNode roll, String resource) {
        for (JsonNode detail : roll.path("breakdown"))
            if (resourceDice(detail, resource))
                for (JsonNode die : detail.path("rolls")) if (die.asInt() == 1) return true;
        return false;
    }

    static void rerollOnes(ObjectNode roll, String resource, int maxDice, Random random, List<String> report) {
        if (!hasOnes(roll, resource)) throw new IllegalArgumentException("No Slayer Dice showing 1 are available on this roll.");
        int offset = 0;
        int delta = 0;
        for (JsonNode raw : roll.path("breakdown")) {
            ObjectNode detail = (ObjectNode) raw;
            List<Integer> faces = new ArrayList<>();
            for (JsonNode face : detail.path("rolls")) faces.add(face.asInt());
            if (resourceDice(detail, resource)) {
                String expression = detail.path("expression").asText();
                if (!expression.contains("d6") || expression.replace("d6", "").matches(".*d\\d+.*"))
                    throw new IllegalArgumentException("Slayer Dice must be d6s.");
                List<Integer> indices = new ArrayList<>();
                for (int i = 0; i < faces.size(); i++) if (faces.get(i) == 1) indices.add(i);
                if (!indices.isEmpty()) {
                    DiceRoller.Result before = new DiceRoller.Result(expression, faces, detail.path("total").asInt());
                    DiceRoller.Result after = DiceRoller.editDice(before, indices, true, maxDice, random);
                    int difference = after.total() - DiceRoller.totalFor(expression, faces, maxDice);
                    detail.put("total", Math.addExact(before.total(), difference));
                    ArrayNode values = detail.withArray("rolls");
                    for (int i : indices) {
                        values.set(i, detail.numberNode(after.rolls().get(i)));
                        roll.withArray("rolls").set(offset + i, detail.numberNode(after.rolls().get(i)));
                        report.add("Rerolled Slayer Die: 1 -> " + after.rolls().get(i) + ".");
                    }
                    delta = Math.addExact(delta, difference);
                }
            }
            offset += faces.size();
        }
        int previous = roll.path("total").asInt();
        roll.put("total", Math.addExact(previous, delta));
        report.add("Roll total: " + previous + " -> " + roll.path("total").asInt() + ".");
    }
}
 