package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.Set;

public final class StructuredDamage {
    private static final int MAX_AMOUNT = 1_000_000;
    private static final int MAX_ADJUSTMENTS = 64;
    private static final int MAX_MARKS = 1000;

    private StructuredDamage() {}

    public static ObjectNode validate(JsonNode value) {
        RuleJson.checkTree(value, "/damage", 0, new int[]{0});
        ObjectNode damage = RuleJson.object(value, "/damage").deepCopy();
        RuleJson.fields(damage, "/damage", "id", "stage", "baseAmount", "amount", "adjustments");
        RuleJson.id(damage.get("id"), "/damage/id");
        if (!"preSeverity".equals(RuleJson.text(damage.get("stage"), "/damage/stage")))
            throw RuleJson.error("/damage/stage", "Expected the preSeverity stage.");
        int baseAmount = RuleJson.integer(damage.get("baseAmount"), "/damage/baseAmount", 0, MAX_AMOUNT);
        int amount = RuleJson.integer(damage.get("amount"), "/damage/amount", 0, MAX_AMOUNT);
        if (!damage.has("adjustments")) damage.putArray("adjustments");
        JsonNode adjustments = damage.get("adjustments");
        if (!adjustments.isArray() || adjustments.size() > MAX_ADJUSTMENTS)
            throw RuleJson.error("/damage/adjustments", "Expected a bounded adjustment list.");

        Set<String> ids = new HashSet<>();
        int expectedBefore = baseAmount;
        for (int i = 0; i < adjustments.size(); i++) {
            String path = "/damage/adjustments/" + i;
            JsonNode adjustment = adjustments.get(i);
            RuleJson.fields(adjustment, path, "id", "sourceId", "label", "requested", "applied", "before", "after", "increase");
            String id = RuleJson.id(adjustment.get("id"), path + "/id");
            if (!ids.add(id)) throw RuleJson.error(path + "/id", "Duplicate damage adjustment ID.");
            RuleJson.id(adjustment.get("sourceId"), path + "/sourceId");
            RuleJson.text(adjustment.get("label"), path + "/label");
            int requested = RuleJson.integer(adjustment.get("requested"), path + "/requested", 0, MAX_AMOUNT);
            int applied = RuleJson.integer(adjustment.get("applied"), path + "/applied", 0, requested);
            int before = RuleJson.integer(adjustment.get("before"), path + "/before", 0, MAX_AMOUNT);
            int after = RuleJson.integer(adjustment.get("after"), path + "/after", 0, MAX_AMOUNT);
            boolean increase = adjustment.has("increase");
            if (increase && !adjustment.get("increase").asBoolean(false)) throw RuleJson.error(path + "/increase", "Expected true when present.");
            if (before != expectedBefore || applied != Math.min(requested, increase ? MAX_AMOUNT - before : before)
                    || after != (increase ? before + applied : before - applied))
                throw RuleJson.error(path, "Damage adjustment does not match the ordered reduction history.");
            expectedBefore = after;
        }
        if (amount != expectedBefore)
            throw RuleJson.error("/damage/amount", "Current amount does not match the ordered reduction history.");
        return damage;
    }

    static ObjectNode reduce(JsonNode value, int requested, String id, String sourceId, String label) {
        return adjust(value, requested, id, sourceId, label, false);
    }

    static ObjectNode increase(JsonNode value, int requested, String id, String sourceId, String label) {
        return adjust(value, requested, id, sourceId, label, true);
    }

    private static ObjectNode adjust(JsonNode value, int requested, String id, String sourceId, String label,
                                     boolean increase) {
        ObjectNode damage = validate(value);
        RuleJson.integer(RuleJson.JSON.getNodeFactory().numberNode(requested), "/reduction/requested", 0, MAX_AMOUNT);
        RuleJson.id(RuleJson.JSON.getNodeFactory().textNode(id), "/reduction/id");
        RuleJson.id(RuleJson.JSON.getNodeFactory().textNode(sourceId), "/reduction/sourceId");
        RuleJson.text(RuleJson.JSON.getNodeFactory().textNode(label), "/reduction/label");
        ArrayNode adjustments = (ArrayNode) damage.path("adjustments");
        for (JsonNode adjustment : adjustments)
            if (id.equals(adjustment.path("id").asText()))
                throw RuleJson.error("/reduction/id", "Damage adjustment ID has already been used.");
        if (adjustments.size() >= MAX_ADJUSTMENTS)
            throw RuleJson.error("/damage/adjustments", "Too many damage adjustments.");

        int before = damage.path("amount").asInt();
        int applied = Math.min(requested, increase ? MAX_AMOUNT - before : before);
        int after = increase ? before + applied : before - applied;
        ObjectNode adjustment = RuleJson.JSON.createObjectNode().put("id", id).put("sourceId", sourceId)
                .put("label", label).put("requested", requested).put("applied", applied)
                .put("before", before).put("after", after);
        if (increase) adjustment.put("increase", true);
        adjustments.add(adjustment.deepCopy());
        damage.put("amount", after);
        validate(damage);

        ObjectNode result = RuleJson.JSON.createObjectNode();
        result.set("damage", damage);
        result.set("adjustment", adjustment);
        return result;
    }

    static ObjectNode resolve(JsonNode value, JsonNode thresholds, JsonNode armor, JsonNode hitPoints,
                              ObjectNode context) {
        ObjectNode damage = validate(value);
        int majorAt = whole(thresholds.path("majorAt"), context, "/damage/thresholds/majorAt", 1, MAX_AMOUNT);
        int severeAt = whole(thresholds.path("severeAt"), context, "/damage/thresholds/severeAt", 1, MAX_AMOUNT);
        if (majorAt >= severeAt) throw RuleJson.error("/damage/thresholds", "Major damage must be below severe damage.");

        int amount = damage.path("amount").asInt();
        int severity = severity(amount, majorAt, severeAt);
        int severityBeforeArmor = severity;
        ObjectNode result = RuleJson.JSON.createObjectNode();
        result.set("damage", damage.deepCopy());
        result.put("stage", "resolved").put("severityBeforeArmor", severityName(severityBeforeArmor));

        if (armor != null && !armor.isMissingNode() && !armor.isNull()) {
            String trackPath = armor.path("track").asText();
            ObjectNode track = RuleJson.object(RuleExpression.read(context, trackPath), "/damage/armor/track");
            String markedField = armor.path("marked").asText();
            String capacityField = armor.path("capacity").asText();
            int marked = RuleJson.integer(track.get(markedField), "/damage/armor/marked", 0, MAX_AMOUNT);
            int capacity = RuleJson.integer(track.get(capacityField), "/damage/armor/capacity", 0, MAX_AMOUNT);
            if (marked > capacity) throw RuleJson.error("/damage/armor", "Armor track marks exceed its capacity.");
            int spend = whole(armor.path("spend"), context, "/damage/armor/spend", 0, capacity - marked);
            int steps = RuleJson.integer(armor.path("severitySteps"), "/damage/armor/severitySteps", 1, 3);
            if (spend > 0 && severity <= 1)
                throw new RuleRuntime.Unavailable("armorNoBenefit", "Armor cannot reduce this damage severity.");
            int before = marked;
            marked += spend;
            track.put(markedField, marked);
            severity = Math.max(0, severity - spend * steps);
            ObjectNode armorResult = result.putObject("armor");
            armorResult.put("spent", spend).put("before", before).put("after", marked).put("capacity", capacity);
        } else {
            result.putObject("armor").put("spent", 0);
        }

        String finalSeverity = severityName(severity);
        String hpTrackPath = hitPoints.path("track").asText();
        ObjectNode hpTrack = RuleJson.object(RuleExpression.read(context, hpTrackPath), "/damage/hitPoints/track");
        String hpMarkedField = hitPoints.path("marked").asText();
        String hpCapacityField = hitPoints.path("capacity").asText();
        int hpMarked = RuleJson.integer(hpTrack.get(hpMarkedField), "/damage/hitPoints/marked", 0, MAX_AMOUNT);
        int hpCapacity = RuleJson.integer(hpTrack.get(hpCapacityField), "/damage/hitPoints/capacity", 0, MAX_AMOUNT);
        if (hpMarked > hpCapacity) throw RuleJson.error("/damage/hitPoints", "Hit point marks exceed capacity.");
        int requestedMarks = switch (finalSeverity) {
            case "minor" -> whole(hitPoints.path("minor"), context, "/damage/hitPoints/minor", 0, MAX_AMOUNT);
            case "major" -> whole(hitPoints.path("major"), context, "/damage/hitPoints/major", 0, MAX_AMOUNT);
            case "severe" -> whole(hitPoints.path("severe"), context, "/damage/hitPoints/severe", 0, MAX_AMOUNT);
            default -> 0;
        };
        int appliedMarks = Math.min(requestedMarks, hpCapacity - hpMarked);
        int hpAfter = hpMarked + appliedMarks;
        hpTrack.put(hpMarkedField, hpAfter);
        result.put("severity", finalSeverity);
        result.putObject("hitPoints").put("requested", requestedMarks).put("applied", appliedMarks)
                .put("before", hpMarked).put("after", hpAfter).put("capacity", hpCapacity);
        return result;
    }

    /** Adjustable fields of a marks plan with their inclusive bounds. */
    static final java.util.Map<String, int[]> MARK_FIELDS = java.util.Map.of(
            "armor.allowed", new int[]{0, MAX_MARKS}, "armor.steps", new int[]{1, 4}, "armor.saved", new int[]{0, MAX_MARKS},
            "severityReduction", new int[]{0, 4}, "hitPoints.bonus", new int[]{-MAX_MARKS, MAX_MARKS},
            "hitPoints.max", new int[]{0, MAX_MARKS});

    /**
     * Turns pre-severity damage into a marks plan: incoming severity from the thresholds (4 = at least twice the severe
     * threshold when {@code massive}), the requested Armor Slots and the free armor/HP capacity. Rules adjust the plan
     * on the {@code damage.marks} event; {@link #mark} then writes it.
     */
    static ObjectNode assess(JsonNode value, JsonNode type, JsonNode thresholds, JsonNode massive, JsonNode armor,
                             JsonNode hitPoints, ObjectNode context) {
        ObjectNode damage = validate(value);
        int majorAt = whole(thresholds.path("majorAt"), context, "/damage/thresholds/majorAt", 1, MAX_AMOUNT);
        int severeAt = whole(thresholds.path("severeAt"), context, "/damage/thresholds/severeAt", 1, MAX_AMOUNT);
        if (majorAt >= severeAt) throw RuleJson.error("/damage/thresholds", "Major damage must be below severe damage.");
        JsonNode kind = RuleExpression.evaluate(type, context);
        if (!kind.isNull() && !kind.isTextual()) throw RuleJson.error("/damage/type", "Expected a damage type or null.");
        int[] armorTrack = track(armor, context, "/damage/armor");
        int[] hpTrack = track(hitPoints, context, "/damage/hitPoints");
        ObjectNode plan = RuleJson.JSON.createObjectNode().put("id", damage.path("id").asText()).put("stage", "marks");
        plan.set("type", kind.deepCopy());
        plan.put("amount", damage.path("amount").asInt());
        plan.putObject("thresholds").put("major", majorAt).put("severe", severeAt);
        plan.put("massive", RuleExpression.bool(RuleExpression.evaluate(massive, context), "/damage/massive"));
        plan.putObject("armor").put("requested", whole(armor.path("requested"), context, "/damage/armor/requested", 0, MAX_MARKS))
                .put("available", armorTrack[1] - armorTrack[0]).put("allowed", 1).put("steps", 1).put("saved", 0);
        plan.put("severityReduction", 0);
        plan.putObject("hitPoints").put("bonus", 0).put("max", MAX_MARKS).put("remaining", hpTrack[1] - hpTrack[0]);
        plan.putArray("adjustments");
        return derive(plan);
    }

    /** Validates a marks plan, its ordered adjustment history and its derived values. */
    public static ObjectNode validatePlan(JsonNode value) {
        RuleJson.checkTree(value, "/marks", 0, new int[]{0});
        ObjectNode plan = RuleJson.object(value, "/marks").deepCopy();
        RuleJson.fields(plan, "/marks", "id", "stage", "type", "amount", "thresholds", "massive", "armor", "severityReduction",
                "hitPoints", "adjustments", "incoming", "severity");
        RuleJson.id(plan.get("id"), "/marks/id");
        if (!"marks".equals(RuleJson.text(plan.get("stage"), "/marks/stage"))) throw RuleJson.error("/marks/stage", "Expected the marks stage.");
        if (!plan.path("type").isNull() && !plan.path("type").isTextual()) throw RuleJson.error("/marks/type", "Expected a damage type or null.");
        RuleJson.integer(plan.get("amount"), "/marks/amount", 0, MAX_AMOUNT);
        RuleJson.fields(plan.get("thresholds"), "/marks/thresholds", "major", "severe");
        int major = RuleJson.integer(plan.path("thresholds").get("major"), "/marks/thresholds/major", 1, MAX_AMOUNT);
        if (major >= RuleJson.integer(plan.path("thresholds").get("severe"), "/marks/thresholds/severe", 1, MAX_AMOUNT))
            throw RuleJson.error("/marks/thresholds", "Major damage must be below severe damage.");
        RuleExpression.bool(plan.get("massive"), "/marks/massive");
        RuleJson.fields(plan.get("armor"), "/marks/armor", "requested", "available", "allowed", "steps", "saved", "spent");
        RuleJson.fields(plan.get("hitPoints"), "/marks/hitPoints", "bonus", "max", "remaining", "wouldMark", "marks");
        RuleJson.integer(plan.path("armor").get("requested"), "/marks/armor/requested", 0, MAX_MARKS);
        RuleJson.integer(plan.path("armor").get("available"), "/marks/armor/available", 0, MAX_AMOUNT);
        RuleJson.integer(plan.path("hitPoints").get("remaining"), "/marks/hitPoints/remaining", 0, MAX_AMOUNT);
        java.util.Map<String, Integer> current = new java.util.HashMap<>();
        for (String field : MARK_FIELDS.keySet()) current.put(field, field.equals("armor.allowed") || field.equals("armor.steps") ? 1
                : field.equals("hitPoints.max") ? MAX_MARKS : 0);
        JsonNode adjustments = plan.get("adjustments");
        if (adjustments == null || !adjustments.isArray() || adjustments.size() > MAX_ADJUSTMENTS)
            throw RuleJson.error("/marks/adjustments", "Expected a bounded adjustment list.");
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < adjustments.size(); i++) {
            String path = "/marks/adjustments/" + i;
            JsonNode adjustment = adjustments.get(i);
            RuleJson.fields(adjustment, path, "id", "sourceId", "label", "field", "before", "after");
            String id = RuleJson.id(adjustment.get("id"), path + "/id");
            String owner = RuleJson.id(adjustment.get("sourceId"), path + "/sourceId");
            if (!ids.add(owner + "\n" + id)) throw RuleJson.error(path + "/id", "Duplicate adjustment ID.");
            RuleJson.text(adjustment.get("label"), path + "/label");
            String field = RuleJson.text(adjustment.get("field"), path + "/field");
            int[] bounds = MARK_FIELDS.get(field);
            if (bounds == null) throw RuleJson.error(path + "/field", "Unknown marks field.");
            if (RuleJson.integer(adjustment.get("before"), path + "/before", bounds[0], bounds[1]) != current.get(field))
                throw RuleJson.error(path, "Adjustment does not match the ordered history.");
            current.put(field, RuleJson.integer(adjustment.get("after"), path + "/after", bounds[0], bounds[1]));
        }
        for (var entry : current.entrySet())
            if (field(plan, entry.getKey()).asInt(Integer.MIN_VALUE) != entry.getValue() || !field(plan, entry.getKey()).isIntegralNumber())
                throw RuleJson.error("/marks/" + entry.getKey().replace('.', '/'), "Value does not match the adjustment history.");
        ObjectNode derived = derive(plan.deepCopy());
        if (!derived.equals(plan)) throw RuleJson.error("/marks", "Derived values do not match the plan.");
        return plan;
    }

    /** Sets one adjustable field and records the change; returns {@code {marks, adjustment}}. */
    static ObjectNode adjust(JsonNode value, String field, int to, String id, String sourceId, String label) {
        ObjectNode plan = validatePlan(value);
        int[] bounds = MARK_FIELDS.get(field);
        if (bounds == null) throw RuleJson.error("/field", "Unknown marks field.");
        RuleJson.id(RuleJson.JSON.getNodeFactory().textNode(id), "/adjustment/id");
        RuleJson.id(RuleJson.JSON.getNodeFactory().textNode(sourceId), "/adjustment/sourceId");
        ArrayNode adjustments = (ArrayNode) plan.get("adjustments");
        for (JsonNode adjustment : adjustments)
            if (id.equals(adjustment.path("id").asText()) && sourceId.equals(adjustment.path("sourceId").asText())) throw RuleJson.error("/adjustment/id", "Adjustment ID has already been used.");
        if (adjustments.size() >= MAX_ADJUSTMENTS) throw RuleJson.error("/marks/adjustments", "Too many adjustments.");
        int before = field(plan, field).asInt();
        int after = Math.max(bounds[0], Math.min(bounds[1], to));
        ObjectNode result = RuleJson.JSON.createObjectNode();
        if (before == after) {
            result.set("marks", plan);
            result.putNull("adjustment");
            return result;
        }
        ObjectNode adjustment = RuleJson.JSON.createObjectNode().put("id", id).put("sourceId", sourceId).put("label", label)
                .put("field", field).put("before", before).put("after", after);
        adjustments.add(adjustment.deepCopy());
        String[] parts = field.split("\\.");
        if (parts.length == 1) plan.put(field, after);
        else ((ObjectNode) plan.get(parts[0])).put(parts[1], after);
        result.set("marks", validatePlan(derive(plan)));
        result.set("adjustment", adjustment);
        return result;
    }

    /**
     * Writes a marks plan: marks the spent Armor Slots minus saved ones and the planned Hit Points. The tracks must
     * still have the free capacity the plan was assessed against.
     */
    static ObjectNode mark(JsonNode value, JsonNode armor, JsonNode hitPoints, ObjectNode context) {
        ObjectNode plan = validatePlan(value);
        int[] armorTrack = track(armor, context, "/damage/armor");
        int[] hpTrack = track(hitPoints, context, "/damage/hitPoints");
        if (armorTrack[1] - armorTrack[0] != plan.path("armor").path("available").asInt()
                || hpTrack[1] - hpTrack[0] != plan.path("hitPoints").path("remaining").asInt())
            throw new RuleRuntime.Unavailable("stale", "The tracks changed after the damage was assessed.");
        int spent = plan.path("armor").path("spent").asInt();
        int saved = Math.min(spent, plan.path("armor").path("saved").asInt());
        int marks = plan.path("hitPoints").path("marks").asInt();
        ObjectNode armorNode = RuleJson.object(RuleExpression.read(context, armor.path("track").asText()), "/damage/armor/track");
        armorNode.put(armor.path("marked").asText(), armorTrack[0] + spent - saved);
        ObjectNode hpNode = RuleJson.object(RuleExpression.read(context, hitPoints.path("track").asText()), "/damage/hitPoints/track");
        hpNode.put(hitPoints.path("marked").asText(), hpTrack[0] + marks);
        ObjectNode applied = RuleJson.JSON.createObjectNode().put("id", plan.path("id").asText()).put("stage", "applied");
        applied.set("type", plan.get("type").deepCopy());
        applied.put("amount", plan.path("amount").asInt()).put("incoming", plan.path("incoming").asInt())
                .put("severity", plan.path("severity").asInt()).put("hitPoints", marks)
                .put("wouldMark", plan.path("hitPoints").path("wouldMark").asInt())
                .put("armorSpent", spent).put("armorMarked", spent - saved).put("armorSaved", saved)
                .put("hitPointsRemaining", hpTrack[1] - hpTrack[0] - marks)
                .put("armorRemaining", armorTrack[1] - armorTrack[0] - spent + saved);
        return applied;
    }

    private static ObjectNode derive(ObjectNode plan) {
        int amount = plan.path("amount").asInt();
        int major = plan.path("thresholds").path("major").asInt(), severe = plan.path("thresholds").path("severe").asInt();
        int incoming = plan.path("massive").asBoolean() && amount >= severe * 2 ? 4 : severity(amount, major, severe);
        ObjectNode armor = (ObjectNode) plan.get("armor");
        int spent = incoming == 0 ? 0 : Math.min(armor.path("requested").asInt(),
                Math.min(armor.path("allowed").asInt(), armor.path("available").asInt()));
        armor.put("spent", spent);
        int severity = Math.max(0, incoming - spent * armor.path("steps").asInt() - plan.path("severityReduction").asInt());
        ObjectNode hitPoints = (ObjectNode) plan.get("hitPoints");
        int wouldMark = Math.max(0, Math.min(hitPoints.path("remaining").asInt(), severity + hitPoints.path("bonus").asInt()));
        hitPoints.put("wouldMark", wouldMark).put("marks", Math.min(wouldMark, hitPoints.path("max").asInt()));
        plan.put("incoming", incoming).put("severity", severity);
        return plan;
    }

    private static JsonNode field(ObjectNode plan, String field) {
        String[] parts = field.split("\\.");
        return parts.length == 1 ? plan.path(field) : plan.path(parts[0]).path(parts[1]);
    }

    private static int[] track(JsonNode spec, ObjectNode context, String path) {
        ObjectNode track = RuleJson.object(RuleExpression.read(context, spec.path("track").asText()), path + "/track");
        int capacity = RuleJson.integer(track.get(spec.path("capacity").asText()), path + "/capacity", 0, MAX_AMOUNT);
        int marked = RuleJson.integer(track.get(spec.path("marked").asText()), path + "/marked", 0, capacity);
        return new int[]{marked, capacity};
    }

    private static int severity(int amount, int majorAt, int severeAt) {
        if (amount <= 0) return 0;
        if (amount >= severeAt) return 3;
        if (amount >= majorAt) return 2;
        return 1;
    }

    private static String severityName(int severity) {
        return switch (severity) {
            case 0 -> "none";
            case 1 -> "minor";
            case 2 -> "major";
            case 3 -> "severe";
            default -> throw new IllegalArgumentException("Invalid damage severity.");
        };
    }

    private static int whole(JsonNode expression, ObjectNode context, String path, int min, int max) {
        return RuleJson.integer(RuleExpression.evaluate(expression, context), path, min, max);
    }
}
