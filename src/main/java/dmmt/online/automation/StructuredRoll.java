package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.random.RandomGenerator;
import java.util.UUID;

public final class StructuredRoll {
    private StructuredRoll() {}

    public static ObjectNode validate(JsonNode roll) {
        RuleJson.checkTree(roll, "/roll", 0, new int[]{0});
        return calculate(RuleJson.object(roll, "/roll").deepCopy(), true);
    }

    public static ObjectNode resolve(JsonNode plan, JsonNode context, RandomGenerator random) {
        return resolve(plan, context, random, 1024);
    }

    static ObjectNode resolve(JsonNode plan, JsonNode context, RandomGenerator random, int budget) {
        RuleJson.checkTree(plan, "/rollPlan", 0, new int[]{0});
        RuleJson.fields(plan, "/rollPlan", "id", "contributions", "metadata");
        JsonNode definitions = plan.path("contributions");
        if (!definitions.isArray() || definitions.isEmpty() || definitions.size() > 256)
            throw RuleJson.error("/rollPlan/contributions", "Expected 1-256 contributions.");
        ObjectNode roll = RuleJson.JSON.createObjectNode();
        roll.set("id", plan.path("id"));
        if (plan.has("metadata")) {
            if (!plan.path("metadata").isObject()) throw RuleJson.error("/rollPlan/metadata", "Expected an object.");
            roll.set("metadata", plan.path("metadata").deepCopy());
        }
        ArrayNode parts = roll.putArray("contributions");
        int diceCount = 0;
        // Validate the complete plan before consuming any host RNG samples.
        for (int i = 0; i < definitions.size(); i++) {
            JsonNode definition = definitions.get(i);
            String path = "/rollPlan/contributions/" + i;
            ObjectNode part = RuleJson.object(definition, path).deepCopy();
            if ("dice".equals(part.path("kind").asText())) {
                RuleJson.fields(part, path, "id", "kind", "role", "sourceId", "label", "count", "sides", "keepHighest", "maximize", "sign", "editable");
                int count = RuleJson.integer(RuleExpression.evaluate(part.path("count"), context), path + "/count", 0, 1024);
                int sides = RuleJson.integer(RuleExpression.evaluate(part.path("sides"), context), path + "/sides", 2, 1000);
                int keep = part.has("keepHighest") ? RuleJson.integer(RuleExpression.evaluate(part.get("keepHighest"), context), path + "/keepHighest", Math.min(1, count), count) : count;
                int maximize = part.has("maximize") ? RuleJson.integer(RuleExpression.evaluate(part.get("maximize"), context), path + "/maximize", 0, count) : 0;
                diceCount += count;
                if (diceCount > budget) throw RuleJson.error(path, "Roll plan exceeds its dice limit.");
                part.remove(List.of("count", "sides", "keepHighest", "maximize"));
                ArrayNode dice = part.putArray("dice");
                for (int j = 0; j < count; j++) dice.addObject().put("id", part.path("id").asText() + ".d" + (j + 1))
                        .put("sides", sides).put("face", sides).put("kept", j < keep).put("origin", j < maximize ? "maximized" : "rolled");
            } else if ("flat".equals(part.path("kind").asText())) part.set("value", RuleExpression.evaluate(part.path("value"), context));
            else if ("scale".equals(part.path("kind").asText())) part.set("factor", RuleExpression.evaluate(part.path("factor"), context));
            parts.add(part);
        }
        validate(roll);
        if (random == null && diceCount > 0) throw RuleJson.error("/rollPlan", "Host random generator is required.");
        for (JsonNode part : parts) if ("dice".equals(part.path("kind").asText())) {
            List<ObjectNode> faces = new ArrayList<>();
            int keep = 0;
            for (JsonNode die : part.path("dice")) {
                ObjectNode face = (ObjectNode) die;
                if (face.path("kept").asBoolean()) keep++;
                if ("rolled".equals(face.path("origin").asText())) face.put("face", random.nextInt(face.path("sides").asInt()) + 1);
                face.put("kept", false);
                faces.add(face);
            }
            // Ties retain their stable original order, independent of labels and IDs.
            faces.sort(Comparator.comparingInt((ObjectNode die) -> die.path("face").asInt()).reversed());
            for (int i = 0; i < keep; i++) faces.get(i).put("kept", true);
        }
        return calculate(roll, false);
    }

    public static ArrayNode dice(JsonNode roll, String role, boolean keptOnly) {
        ObjectNode resolved = validate(roll);
        Set<String> excluded = ids(resolved.path("excludedDice"), "/roll/excludedDice", false);
        ArrayNode out = RuleJson.JSON.createArrayNode();
        for (JsonNode part : resolved.path("contributions")) {
            if (!"dice".equals(part.path("kind").asText()) || role != null && !role.equals(part.path("role").asText())) continue;
            for (JsonNode face : part.path("dice")) {
                boolean kept = face.path("kept").asBoolean() && !excluded.contains(face.path("id").asText());
                if (keptOnly && !kept) continue;
                ObjectNode item = face.deepCopy();
                item.put("kept", kept).put("poolId", part.path("poolId").asText(part.path("id").asText()))
                        .put("role", part.path("role").asText());
                if (part.has("poolDieId")) item.put("poolDieId", part.path("poolDieId").asText());
                out.add(item);
            }
        }
        return out;
    }

    /**
     * Summarises a resolved roll with the same vocabulary as {@code event.check}: total, difficulty (from metadata),
     * the kept hope/fear die faces, the dominant die, whether they match, and the outcome against the difficulty.
     */
    static ObjectNode check(JsonNode roll) {
        ObjectNode resolved = validate(roll);
        ObjectNode check = RuleJson.JSON.createObjectNode();
        double total = resolved.path("total").asDouble();
        check.set("total", resolved.path("total").deepCopy());
        int hope = 0, fear = 0;
        boolean duality = false;
        for (JsonNode die : dice(resolved, "hope", true)) { hope += die.path("face").asInt(); duality = true; }
        boolean fearDie = false;
        for (JsonNode die : dice(resolved, "fear", true)) { fear += die.path("face").asInt(); fearDie = true; }
        duality &= fearDie;
        boolean critical = duality && hope == fear;
        if (duality) {
            check.put("hopeDie", hope).put("fearDie", fear);
            check.put("dominant", hope >= fear ? "hope" : "fear");
        } else check.putNull("dominant");
        check.put("critical", critical);
        JsonNode difficulty = resolved.path("metadata").path("difficulty");
        if (difficulty.isNumber()) {
            check.set("difficulty", difficulty.deepCopy());
            check.put("outcome", critical || total >= difficulty.asDouble() ? "success" : "failure");
        } else {
            check.putNull("difficulty");
            check.putNull("outcome");
        }
        return check;
    }

    static ObjectNode addPoolDie(JsonNode original, JsonNode storedDie, String poolId, String sourceId, String role, String label) {
        ObjectNode roll = validate(original);
        RuleJson.fields(storedDie, "/poolDie", "id", "sides", "face", "kept", "origin");
        String poolDieId = RuleJson.id(storedDie.get("id"), "/poolDie/id");
        int sides = RuleJson.integer(storedDie.get("sides"), "/poolDie/sides", 2, 1000);
        int face = RuleJson.integer(storedDie.get("face"), "/poolDie/face", 1, sides);
        if (!RuleExpression.bool(storedDie.get("kept"), "/poolDie/kept") || !"stored".equals(storedDie.path("origin").asText()))
            throw RuleJson.error("/poolDie", "Only a kept stored die can be added to a roll.");

        String identity = UUID.nameUUIDFromBytes((poolId + "\0" + poolDieId).getBytes(StandardCharsets.UTF_8)).toString();
        ArrayNode dice = RuleJson.JSON.createArrayNode();
        dice.addObject().put("id", "pool-die." + identity).put("sides", sides).put("face", face)
                .put("kept", true).put("origin", "stored");
        ObjectNode contribution = RuleJson.JSON.createObjectNode().put("id", "pool-roll." + identity)
                .put("kind", "dice").put("role", role).put("sourceId", sourceId).put("label", label)
                .put("poolId", poolId).put("poolDieId", poolDieId);
        contribution.set("dice", dice);
        ((ArrayNode) roll.path("contributions")).add(contribution);
        return calculate(roll, false);
    }

    public static ObjectNode derive(JsonNode original, JsonNode eligibleDice, JsonNode keepDice) {
        ObjectNode roll = validate(original);
        Set<String> eligible = ids(eligibleDice, "/derive/eligible", true);
        Set<String> keep = ids(keepDice, "/derive/keep", true);
        Set<String> available = new HashSet<>();
        dice(roll, null, true).forEach(die -> available.add(die.path("id").asText()));
        if (!available.containsAll(eligible) || !eligible.containsAll(keep))
            throw RuleJson.error("/derive", "Die selection contains an unknown, dropped or already excluded die.");
        Set<String> excluded = ids(roll.path("excludedDice"), "/roll/excludedDice", false);
        for (String id : eligible) if (!keep.contains(id)) excluded.add(id);
        ArrayNode removed = roll.putArray("excludedDice");
        excluded.forEach(removed::add);
        return calculate(roll, false);
    }

    /** Read-only view of an editable die for per-die expressions under the {@code die} root. */
    static ObjectNode dieView(JsonNode part, JsonNode die) {
        ObjectNode view = die.deepCopy();
        view.put("role", part.path("role").asText()).put("contributionId", part.path("id").asText())
                .put("sourceId", part.path("sourceId").asText());
        if (!view.has("previous")) view.putArray("previous");
        if (!view.has("editedBy")) view.putArray("editedBy");
        return view;
    }

    /** Kept, unexcluded dice in editable contributions, in roll order; values are live nodes of {@code roll}. */
    static List<ObjectNode[]> editableDice(ObjectNode roll) {
        Set<String> excluded = ids(roll.path("excludedDice"), "/roll/excludedDice", false);
        List<ObjectNode[]> out = new ArrayList<>();
        for (JsonNode part : roll.path("contributions")) {
            if (!"dice".equals(part.path("kind").asText()) || !part.path("editable").asBoolean(true)) continue;
            for (JsonNode die : part.path("dice"))
                if (die.path("kept").asBoolean() && !excluded.contains(die.path("id").asText()))
                    out.add(new ObjectNode[]{(ObjectNode) part, (ObjectNode) die});
        }
        return out;
    }

    public static ArrayNode select(JsonNode original, java.util.function.Predicate<ObjectNode> where) {
        ObjectNode roll = validate(original);
        ArrayNode ids = RuleJson.JSON.createArrayNode();
        List<ObjectNode[]> dice = editableDice(roll);
        Map<String, Integer> faces = new HashMap<>();
        for (ObjectNode[] entry : dice) faces.merge(entry[0].path("role").asText() + ":" + entry[1].path("face").asInt(), 1, Integer::sum);
        for (ObjectNode[] entry : dice) {
            ObjectNode view = dieView(entry[0], entry[1]);
            view.put("matches", faces.get(entry[0].path("role").asText() + ":" + entry[1].path("face").asInt()) - 1);
            if (where.test(view)) ids.add(entry[1].path("id").asText());
        }
        return ids;
    }

    /** The summed current faces of the selected kept dice. */
    public static int faceTotal(JsonNode original, JsonNode selection) {
        ObjectNode roll = validate(original);
        Set<String> wanted = ids(selection, "/roll/dice", true);
        Set<String> found = new HashSet<>();
        int total = 0;
        for (ObjectNode[] entry : editableDice(roll)) if (wanted.contains(entry[1].path("id").asText())) {
            found.add(entry[1].path("id").asText());
            total += entry[1].path("face").asInt();
        }
        if (!found.containsAll(wanted)) throw RuleJson.error("/roll/dice", "Die selection contains an unknown, dropped, excluded or locked die.");
        return total;
    }

    /** Replaces selected faces, keeping prior faces and editing sources; unchanged faces are not recorded. */
    public static ObjectNode editFaces(JsonNode original, JsonNode selection, String sourceId, String origin,
                                       java.util.function.Function<ObjectNode, Integer> face) {
        ObjectNode roll = validate(original);
        Set<String> wanted = ids(selection, "/roll/dice", true);
        Set<String> found = new HashSet<>();
        for (ObjectNode[] entry : editableDice(roll)) {
            ObjectNode die = entry[1];
            if (!wanted.contains(die.path("id").asText())) continue;
            found.add(die.path("id").asText());
            int sides = die.path("sides").asInt();
            int next = RuleJson.integer(RuleExpression.numeric(face.apply(dieView(entry[0], die)), "/die/face"), "/die/face", 1, sides);
            if (next == die.path("face").asInt() && !"rerolled".equals(origin)) continue;
            record(die, sourceId);
            die.put("face", next).put("origin", origin);
        }
        if (!found.containsAll(wanted)) throw RuleJson.error("/roll/dice", "Die selection contains an unknown, dropped, excluded or locked die.");
        return calculate(roll, false);
    }

    /** Changes each selected die to {@code sides} and rerolls it, recording the prior face; the origin becomes {@code resized}. */
    public static ObjectNode resize(JsonNode original, JsonNode selection, String sourceId, int sides, RandomGenerator random) {
        ObjectNode roll = validate(original);
        Set<String> wanted = ids(selection, "/roll/dice", true);
        Set<String> found = new HashSet<>();
        for (ObjectNode[] entry : editableDice(roll)) {
            ObjectNode die = entry[1];
            if (!wanted.contains(die.path("id").asText())) continue;
            if (random == null) throw RuleJson.error("/dice", "Host random generator is required.");
            found.add(die.path("id").asText());
            record(die, sourceId);
            die.put("sides", sides).put("face", random.nextInt(sides) + 1).put("origin", "resized");
        }
        if (!found.containsAll(wanted)) throw RuleJson.error("/roll/dice", "Die selection contains an unknown, dropped, excluded or locked die.");
        return calculate(roll, false);
    }

    /** Adds one die after each selected die whose face satisfies {@code when}, chaining new dice, up to {@code limit} new dice. */
    public static ObjectNode explode(JsonNode original, JsonNode selection, String sourceId, int limit,
                                     java.util.function.Predicate<ObjectNode> when, RandomGenerator random) {
        ObjectNode roll = validate(original);
        Set<String> wanted = ids(selection, "/roll/dice", true);
        Set<String> found = new HashSet<>();
        int added = 0, total = 0;
        Set<String> used = new HashSet<>();
        for (JsonNode part : roll.path("contributions")) for (JsonNode die : part.path("dice")) { total++; used.add(die.path("id").asText()); }
        for (ObjectNode[] entry : editableDice(roll)) {
            if (!wanted.contains(entry[1].path("id").asText())) continue;
            found.add(entry[1].path("id").asText());
            ArrayNode dice = (ArrayNode) entry[0].path("dice");
            ObjectNode current = entry[1];
            int index = indexOf(dice, current.path("id").asText());
            while (added < limit && when.test(dieView(entry[0], current))) {
                if (random == null) throw RuleJson.error("/dice", "Host random generator is required.");
                if (++total > 1024) throw RuleJson.error("/roll", "Roll exceeds its dice limit.");
                int sides = current.path("sides").asInt();
                int n = 1;
                while (used.contains(current.path("id").asText() + ".x" + n)) n++;
                String extraId = current.path("id").asText() + ".x" + n;
                used.add(extraId);
                ObjectNode extra = RuleJson.JSON.createObjectNode().put("id", extraId)
                        .put("sides", sides).put("face", random.nextInt(sides) + 1).put("kept", true).put("origin", "exploded");
                extra.putArray("editedBy").add(sourceId);
                dice.insert(++index, extra);
                current = extra;
                added++;
            }
        }
        if (!found.containsAll(wanted)) throw RuleJson.error("/roll/dice", "Die selection contains an unknown, dropped, excluded or locked die.");
        return calculate(roll, false);
    }

    /**
     * Appends one host-resolved contribution after the roll; its ID must be new. A contribution of zero dice or a zero
     * flat value changes nothing and is not recorded.
     */
    public static ObjectNode contribute(JsonNode original, ObjectNode definition, JsonNode context, RandomGenerator random) {
        ObjectNode roll = validate(original);
        if ("scale".equals(definition.path("kind").asText())) {
            // Scales roll nothing; they are validated against the earlier contributions they scale.
            ((ArrayNode) roll.path("contributions")).add(definition.deepCopy());
            return calculate(roll, false);
        }
        int used = 0;
        for (JsonNode part : roll.path("contributions")) used += part.path("dice").size();
        ObjectNode plan = RuleJson.JSON.createObjectNode().put("id", roll.path("id").asText());
        plan.putArray("contributions").add(definition);
        JsonNode part = resolve(plan, context, random, Math.max(0, 1024 - used)).path("contributions").get(0);
        boolean empty = "dice".equals(part.path("kind").asText()) && part.path("dice").isEmpty()
                || "flat".equals(part.path("kind").asText()) && part.path("value").isNumber() && part.path("value").asDouble() == 0;
        if (!empty) ((ArrayNode) roll.path("contributions")).add(part);
        return calculate(roll, false);
    }

    /**
     * Rolls {@code count} more dice into the contribution of the first selected die, with that die's sides. An empty
     * selection or a zero count changes nothing.
     */
    public static ObjectNode addDice(JsonNode original, JsonNode selection, int count, String sourceId, RandomGenerator random) {
        ObjectNode roll = validate(original);
        Set<String> wanted = ids(selection, "/roll/dice", true);
        if (wanted.isEmpty() || count == 0) return roll;
        ObjectNode[] first = null;
        for (ObjectNode[] entry : editableDice(roll)) if (wanted.contains(entry[1].path("id").asText())) { first = entry; break; }
        if (first == null) throw RuleJson.error("/roll/dice", "Die selection contains an unknown, dropped, excluded or locked die.");
        int total = 0;
        Set<String> used = new HashSet<>();
        for (JsonNode part : roll.path("contributions")) for (JsonNode die : part.path("dice")) { total++; used.add(die.path("id").asText()); }
        if (total + count > 1024) throw RuleJson.error("/roll", "Roll exceeds its dice limit.");
        if (random == null) throw RuleJson.error("/dice", "Host random generator is required.");
        ArrayNode dice = (ArrayNode) first[0].path("dice");
        String contribution = first[0].path("id").asText();
        int sides = first[1].path("sides").asInt(), n = 1;
        for (int i = 0; i < count; i++) {
            while (used.contains(contribution + ".a" + n)) n++;
            String id = contribution + ".a" + n;
            used.add(id);
            ObjectNode extra = dice.addObject().put("id", id).put("sides", sides).put("face", random.nextInt(sides) + 1)
                    .put("kept", true).put("origin", "added");
            extra.putArray("editedBy").add(sourceId);
        }
        return calculate(roll, false);
    }

    /** Excludes the {@code count} lowest selected dice (earlier dice first on ties); a count above the selection excludes all of it. */
    public static ObjectNode dropLowest(JsonNode original, JsonNode selection, int count) {
        ObjectNode roll = validate(original);
        Set<String> wanted = ids(selection, "/roll/dice", true);
        List<ObjectNode> chosen = new ArrayList<>();
        for (ObjectNode[] entry : editableDice(roll)) if (wanted.contains(entry[1].path("id").asText())) chosen.add(entry[1]);
        if (chosen.size() != wanted.size()) throw RuleJson.error("/roll/dice", "Die selection contains an unknown, dropped, excluded or locked die.");
        if (count == 0 || chosen.isEmpty()) return roll;
        chosen.sort(Comparator.comparingInt(die -> die.path("face").asInt()));
        Set<String> excluded = ids(roll.path("excludedDice"), "/roll/excludedDice", false);
        for (int i = 0; i < Math.min(count, chosen.size()); i++) excluded.add(chosen.get(i).path("id").asText());
        ArrayNode removed = roll.putArray("excludedDice");
        excluded.forEach(removed::add);
        return calculate(roll, false);
    }

    /** Removes every contribution with {@code role} (and its dice); a roll left without contributions is rejected. */
    public static ObjectNode discard(JsonNode original, String role) {
        ObjectNode roll = validate(original);
        ArrayNode kept = RuleJson.JSON.createArrayNode();
        Set<String> removedDice = new HashSet<>();
        for (JsonNode part : roll.path("contributions")) {
            if (!role.equals(part.path("role").asText())) { kept.add(part); continue; }
            for (JsonNode die : part.path("dice")) removedDice.add(die.path("id").asText());
        }
        if (kept.size() == roll.path("contributions").size()) return roll;
        roll.set("contributions", kept);
        if (roll.has("excludedDice")) {
            ArrayNode excluded = RuleJson.JSON.createArrayNode();
            for (JsonNode id : roll.path("excludedDice")) if (!removedDice.contains(id.asText())) excluded.add(id);
            roll.set("excludedDice", excluded);
        }
        return calculate(roll, false);
    }
    /** Exchanges the roles of two contributions, e.g. which die counts as Hope and which as Fear. */
    public static ObjectNode swapRoles(JsonNode original, String first, String second) {
        ObjectNode roll = validate(original);
        for (JsonNode part : roll.path("contributions")) {
            String role = part.path("role").asText();
            if (role.equals(first)) ((ObjectNode) part).put("role", second);
            else if (role.equals(second)) ((ObjectNode) part).put("role", first);
        }
        return calculate(roll, false);
    }

    private static void record(ObjectNode die, String sourceId) {
        ArrayNode previous = die.has("previous") ? (ArrayNode) die.get("previous") : die.putArray("previous");
        ArrayNode editors = die.has("editedBy") ? (ArrayNode) die.get("editedBy") : die.putArray("editedBy");
        if (previous.size() >= 32) throw RuleJson.error("/die/previous", "This die has reached its edit limit.");
        previous.add(die.path("face").asInt());
        editors.add(sourceId);
    }

    private static int indexOf(ArrayNode dice, String id) {
        for (int i = 0; i < dice.size(); i++) if (id.equals(dice.get(i).path("id").asText())) return i;
        return -1;
    }

    /** {@code "of": "all"} scales the running total of every earlier contribution, including earlier scale adjustments. */
    static boolean scalesAll(JsonNode of) {
        return of.isTextual() && "all".equals(of.asText());
    }

    static Set<String> ids(JsonNode values, String path, boolean required) {
        if (!required && values.isMissingNode()) return new LinkedHashSet<>();
        if (!values.isArray() || values.size() > 1024) throw RuleJson.error(path, "Expected a bounded list of die IDs.");
        Set<String> ids = new LinkedHashSet<>();
        for (int i = 0; i < values.size(); i++) {
            String id = RuleJson.id(values.get(i), path + "/" + i);
            if (!ids.add(id)) throw RuleJson.error(path, "Duplicate die ID: " + id);
        }
        return ids;
    }

    private static ObjectNode calculate(ObjectNode roll, boolean checkTotals) {
        RuleJson.fields(roll, "/roll", "id", "contributions", "excludedDice", "total", "metadata");
        RuleJson.id(roll.get("id"), "/roll/id");
        if (roll.has("metadata") && !roll.path("metadata").isObject())
            throw RuleJson.error("/roll/metadata", "Expected an object.");
        JsonNode parts = roll.path("contributions");
        if (!parts.isArray() || parts.isEmpty() || parts.size() > 256)
            throw RuleJson.error("/roll/contributions", "Expected 1-256 contributions.");
        Set<String> excluded = ids(roll.path("excludedDice"), "/roll/excludedDice", false);
        Set<String> allDice = new HashSet<>();
        Set<String> keptDice = new HashSet<>();
        Map<String, Double> totals = new LinkedHashMap<>();
        double total = 0;
        for (int i = 0; i < parts.size(); i++) {
            String path = "/roll/contributions/" + i;
            ObjectNode part = RuleJson.object(parts.get(i), path);
            String kind = RuleJson.text(part.get("kind"), path + "/kind");
            switch (kind) {
                case "dice" -> RuleJson.fields(part, path, "id", "kind", "role", "sourceId", "label", "dice", "sign", "poolId", "poolDieId", "editable", "total");
                case "flat" -> RuleJson.fields(part, path, "id", "kind", "role", "sourceId", "label", "value", "total");
                case "scale" -> RuleJson.fields(part, path, "id", "kind", "role", "sourceId", "label", "of", "factor", "total");
                default -> throw RuleJson.error(path + "/kind", "Unknown contribution kind.");
            }
            String id = RuleJson.id(part.get("id"), path + "/id");
            if (totals.containsKey(id)) throw RuleJson.error(path + "/id", "Duplicate contribution ID.");
            RuleJson.id(part.get("sourceId"), path + "/sourceId");
            RuleJson.id(part.get("role"), path + "/role");
            RuleJson.text(part.get("label"), path + "/label");
            if (part.has("poolId")) RuleJson.id(part.get("poolId"), path + "/poolId");
            if (part.has("editable")) RuleExpression.bool(part.get("editable"), path + "/editable");
            if (part.has("poolDieId")) {
                if (!part.has("poolId")) throw RuleJson.error(path + "/poolDieId", "A pool die reference requires a pool ID.");
                RuleJson.id(part.get("poolDieId"), path + "/poolDieId");
            }
            double value = 0;
            if ("dice".equals(kind)) {
                JsonNode dice = part.path("dice");
                if (!dice.isArray() || dice.size() > 1024)
                    throw RuleJson.error(path + "/dice", "Expected 0-1024 dice.");
                int sign = part.has("sign") ? RuleJson.integer(part.get("sign"), path + "/sign", -1, 1) : 1;
                if (sign == 0) throw RuleJson.error(path + "/sign", "Dice sign must be -1 or 1.");
                for (int j = 0; j < dice.size(); j++) {
                    JsonNode die = dice.get(j);
                    String diePath = path + "/dice/" + j;
                    RuleJson.fields(die, diePath, "id", "sides", "face", "kept", "origin", "previous", "editedBy");
                    String dieId = RuleJson.id(die.get("id"), diePath + "/id");
                    if (!allDice.add(dieId) || allDice.size() > 1024)
                        throw RuleJson.error(diePath + "/id", "Duplicate die ID or too many dice.");
                    int sides = RuleJson.integer(die.get("sides"), diePath + "/sides", 2, 1000);
                    int face = RuleJson.integer(die.get("face"), diePath + "/face", 1, sides);
                    boolean kept = RuleExpression.bool(die.get("kept"), diePath + "/kept");
                    String origin = RuleJson.text(die.get("origin"), diePath + "/origin");
                    if (!Set.of("rolled", "maximized", "stored", "rerolled", "set", "exploded", "added", "resized").contains(origin))
                        throw RuleJson.error(diePath + "/origin", "Unknown die provenance.");
                    for (String history : List.of("previous", "editedBy")) {
                        if (!die.has(history)) continue;
                        JsonNode items = die.get(history);
                        if (!items.isArray() || items.size() > 32) throw RuleJson.error(diePath + "/" + history, "Expected at most 32 edits.");
                        for (int k = 0; k < items.size(); k++)
                            if ("previous".equals(history))
                                RuleJson.integer(items.get(k), diePath + "/previous/" + k, 1, "resized".equals(origin) ? 1000 : sides);
                            else RuleJson.id(items.get(k), diePath + "/editedBy/" + k);
                    }
                    if ("maximized".equals(origin) && face != sides)
                        throw RuleJson.error(diePath + "/face", "A maximized die must show its maximum.");
                    if (kept) keptDice.add(dieId);
                    if (kept && !excluded.contains(dieId)) value += sign * face;
                }
            } else if ("flat".equals(kind)) value = RuleExpression.number(part.get("value"), path + "/value");
            else {
                Set<String> dependencies;
                if (scalesAll(part.path("of"))) dependencies = new LinkedHashSet<>(totals.keySet());
                else dependencies = ids(part.path("of"), path + "/of", true);
                if (dependencies.isEmpty()) throw RuleJson.error(path + "/of", "A scale contribution needs dependencies.");
                double subtotal = 0;
                for (String dependency : dependencies) {
                    if (!totals.containsKey(dependency))
                        throw RuleJson.error(path + "/of", "Scale references must name earlier contributions.");
                    subtotal += totals.get(dependency);
                    RuleExpression.numeric(subtotal, path);
                }
                double factor = RuleExpression.number(part.get("factor"), path + "/factor");
                if (factor < 0) throw RuleJson.error(path + "/factor", "Scale factor cannot be negative.");
                // A scale adds only the adjustment; its dependencies already contribute to the roll.
                value = subtotal * (factor - 1);
            }
            JsonNode result = RuleExpression.numeric(value, path);
            if (checkTotals && part.has("total") && !RuleExpression.equal(result, part.get("total")))
                throw RuleJson.error(path + "/total", "Stored contribution total does not match its provenance.");
            part.set("total", result);
            totals.put(id, value);
            total += value;
            RuleExpression.numeric(total, "/roll/total");
        }
        if (!keptDice.containsAll(excluded)) throw RuleJson.error("/roll/excludedDice", "Only original kept dice can be excluded.");
        JsonNode result = RuleExpression.numeric(total, "/roll/total");
        if (checkTotals && roll.has("total") && !RuleExpression.equal(result, roll.get("total")))
            throw RuleJson.error("/roll/total", "Stored total does not match its provenance.");
        roll.set("total", result);
        return roll;
    }
}
