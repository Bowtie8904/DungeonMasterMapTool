package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Validation shared by authoritative Bone/Valor transactions. */
final class BoneValorRules {
    private BoneValorRules() {}

    static void validateSave(ObjectNode before, ObjectNode next) {
        for (JsonNode effect : next.path("effects")) {
            if (!effect.path("deathrun").asBoolean()) continue;
            JsonNode original = null;
            for (JsonNode previous : before.path("effects"))
                if (previous.path("deathrun").asBoolean() && previous.path("id").equals(effect.path("id"))) original = previous;
            if (original == null || !original.equals(effect))
                throw new IllegalArgumentException("Deathrun rolls and counters are maintained by the host. Refresh the sheet.");
        }
    }

    static void validateWeaponDice(JsonNode request, JsonNode weapon, int proficiency, ObjectNode sheet) {
        JsonNode base = request.path("contributions").path(0);
        String expression = base.path("expression").asText().replaceFirst("^\\((.*)\\)\\*2$", "$1");
        if (expression.matches("\\d+")) expression = "0d" + expression + "+" + expression;
        var dice = Pattern.compile("(?i)^(\\d+)d(\\d+)(?:kh(\\d+))?(?:\\+(\\d+))?$").matcher(expression);
        var weaponDie = Pattern.compile("(?i)(\\d*)d(\\d+)").matcher(weapon.path("damage").asText());
        if (proficiency < 1 || !dice.find() || !weaponDie.find())
            throw new IllegalArgumentException("Deathrun must use the current remaining weapon dice.");
        int extra = weapon.path("automation").path("damage").path("extraDice").asInt();
        int drop = weapon.path("automation").path("damage").path("dropLowest").asInt();
        for (JsonNode effect : sheet.path("effects")) {
            JsonNode pool = effect.path("damage");
            if (pool.path("proficiencyOnly").asBoolean() && weapon.path("fixedDice").asBoolean()) continue;
            extra += pool.path("extraDice").asInt();
            drop += pool.path("dropLowest").asInt();
        }
        int count = (weapon.path("fixedDice").asBoolean()
                ? weaponDie.group(1).isEmpty() ? 1 : Integer.parseInt(weaponDie.group(1)) : proficiency) + extra;
        boolean maximized = dice.group(4) != null;
        if (maximized && !dice.group(4).equals(dice.group(2)))
            throw new IllegalArgumentException("Invalid maximized Deathrun weapon die.");
        int rolled = count - (maximized ? 1 : 0);
        if (Integer.parseInt(dice.group(1)) != rolled || !dice.group(2).equals(weaponDie.group(2))
                || (drop > 0 ? dice.group(3) == null || Integer.parseInt(dice.group(3)) != rolled - drop : dice.group(3) != null))
            throw new IllegalArgumentException("Deathrun must use the current remaining weapon dice.");
    }

    static ObjectNode deathrunSnapshot(ObjectNode roll) {
        ObjectNode snapshot = roll.deepCopy();
        ObjectNode base = (ObjectNode) snapshot.path("breakdown").path(0);
        String expression = base.path("expression").asText();
        boolean doubled = expression.matches("^\\(.*\\)\\*2$");
        expression = expression.replaceFirst("^\\((.*)\\)\\*2$", "$1");
        if (expression.matches("\\d+")) expression = "0d" + expression + "+" + expression;
        var maximized = Pattern.compile("^(\\d+)d(\\d+)(?:kh(\\d+))?\\+(\\d+)$")
                .matcher(expression);
        if (!maximized.matches()) return snapshot;
        int face = Integer.parseInt(maximized.group(4));
        if (face != Integer.parseInt(maximized.group(2))) return snapshot;
        ArrayNode baseRolls = (ArrayNode) base.path("rolls");
        int previousCount = baseRolls.size();
        baseRolls.add(face);
        ArrayNode all = snapshot.putArray("rolls");
        for (int i = 0; i < roll.path("rolls").size(); i++) {
            if (i == previousCount) all.add(face);
            all.add(roll.path("rolls").path(i));
        }
        if (previousCount == roll.path("rolls").size()) all.add(face);
        String dice = (Integer.parseInt(maximized.group(1)) + 1) + "d" + maximized.group(2)
                + (maximized.group(3) == null ? "" : "kh" + (Integer.parseInt(maximized.group(3)) + 1));
        base.put("expression", doubled ? "(" + dice + ")*2" : dice);
        List<String> parts = new java.util.ArrayList<>();
        boolean smite = false;
        for (JsonNode part : snapshot.path("breakdown")) {
            if ("Smite".equals(part.path("label").asText()) && part.path("rolls").isEmpty()) smite = true;
            else parts.add(part.path("expression").asText());
        }
        snapshot.put("expression", smite ? "(" + String.join("+", parts) + ")*2" : String.join("+", parts));
        return snapshot;
    }

    static ObjectNode continueDeathrun(ObjectNode run, JsonNode removedIndex) {
        if (!removedIndex.isInt() || !(run.path("deathrunRoll") instanceof ObjectNode original)
                || !(run.path("deathrunRemaining") instanceof ArrayNode remaining))
            throw new IllegalArgumentException("Choose a remaining Deathrun weapon die to remove.");
        int remove = removedIndex.asInt(), position = -1;
        for (int i = 0; i < remaining.size(); i++) if (remaining.get(i).asInt() == remove) position = i;
        if (position < 0 || remaining.size() <= 1)
            throw new IllegalArgumentException("That die was already removed or Deathrun has no more targets.");
        remaining.remove(position);
        Set<Integer> kept = new HashSet<>();
        remaining.forEach(index -> kept.add(index.asInt()));
        ObjectNode result = original.deepCopy();
        JsonNode base = original.path("breakdown").path(0);
        int multiplier = base.path("expression").asText().matches("^\\(.*\\)\\*2$") ? 2 : 1;
        Set<Integer> initial = new HashSet<>(keptWeaponDice(base));
        ArrayNode faces = result.putArray("rolls"), baseFaces = ((ObjectNode) result.path("breakdown").path(0)).putArray("rolls");
        int lost = 0;
        for (int i = 0; i < original.path("rolls").size(); i++) {
            JsonNode face = original.path("rolls").path(i);
            if (i >= base.path("rolls").size() || kept.contains(i)) {
                faces.add(face);
                if (i < base.path("rolls").size()) baseFaces.add(face);
            } else if (initial.contains(i)) lost += face.asInt() * multiplier;
        }
        result.put("total", original.path("total").asInt() - lost);
        ObjectNode detail = (ObjectNode) result.path("breakdown").path(0);
        detail.put("total", base.path("total").asInt() - lost);
        detail.put("expression", base.path("expression").asText().replaceFirst("\\d+d", remaining.size() + "d").replaceFirst("kh\\d+", ""));
        result.put("expression", original.path("expression").asText().replaceFirst("\\d+d", remaining.size() + "d").replaceFirst("kh\\d+", ""));
        for (JsonNode part : result.path("breakdown")) {
            if (!"Smite".equals(part.path("label").asText()) || !part.path("rolls").isEmpty()) continue;
            ObjectNode smite = (ObjectNode) part;
            int bonus = smite.path("total").asInt() - lost;
            smite.put("total", bonus).put("expression", String.valueOf(bonus));
            result.put("total", result.path("total").asInt() - lost);
        }
        result.remove("deathrun");
        return result;
    }

    static List<Integer> keptWeaponDice(JsonNode base) {
        List<Integer> indices = new java.util.ArrayList<>();
        for (int i = 0; i < base.path("rolls").size(); i++) indices.add(i);
        var keep = Pattern.compile("kh(\\d+)").matcher(base.path("expression").asText());
        if (keep.find()) {
            int count = Integer.parseInt(keep.group(1));
            indices.sort(java.util.Comparator.<Integer>comparingInt(i -> base.path("rolls").path(i).asInt()).reversed()
                    .thenComparing(java.util.Comparator.reverseOrder()));
            indices = new java.util.ArrayList<>(indices.subList(0, Math.min(count, indices.size())));
            indices.sort(Integer::compareTo);
        }
        return indices;
    }

    static void validateRest(ObjectNode before, ObjectNode next, JsonNode request) {
        boolean shortRest = "short".equals(request.path("kind").asText());
        if (shortRest && before.path("shortRestsInRow").asInt() >= 3)
            throw new IllegalArgumentException("Take a long rest after three short rests.");
        for (String field : List.of("name", "level", "className", "subclass", "testingCharacter"))
            if (!before.path(field).equals(next.path(field))) throw new IllegalArgumentException("Resting cannot change character identity.");
        if (next.path("shortRestsInRow").asInt() != (shortRest ? before.path("shortRestsInRow").asInt() + 1 : 0))
            throw new IllegalArgumentException("This rest was already committed or its counter changed.");
        int longMoves = 0;
        Set<String> picks = new HashSet<>();
        for (JsonNode pick : request.path("picks")) {
            String move = pick.path("move").asText();
            if (move.isEmpty()) continue;
            if (!picks.add(move.equals("prepare-group") ? "prepare" : move))
                throw new IllegalArgumentException("Choose each downtime move only once.");
            if (List.of("tend-all", "stress-all", "repair-all", "project").contains(move)) longMoves++;
        }
        boolean recovery = false;
        for (String field : List.of("loadout", "features", "heritage", "inventory", "activeWeapons"))
            for (JsonNode source : before.path(field))
                recovery |= source.path("automation").path("rest").path("longMovesInShort").asBoolean();
        recovery |= before.path("activeArmor").path("automation").path("rest").path("longMovesInShort").asBoolean();
        for (JsonNode effect : before.path("effects"))
            recovery |= effect.path("rest").path("longMovesInShort").asBoolean();
        if (shortRest && (longMoves > 1 || longMoves > 0 && !recovery))
            throw new IllegalArgumentException("Recovery grants exactly one long-rest move during a short rest.");
        for (String resource : List.of("hp", "stress", "armorTrack", "hope")) {
            JsonNode track = next.path(resource);
            if (!track.path("on").isInt() || track.path("on").asInt() < 0 || track.path("on").asInt() > track.path("slots").asInt()
                    || track.path("slots").asInt() != before.path(resource).path("slots").asInt())
                throw new IllegalArgumentException("Invalid rest resource result.");
        }
        Set<String> oldEffects = new HashSet<>();
        for (JsonNode effect : before.path("effects")) oldEffects.add(effect.path("id").asText());
        for (JsonNode effect : next.path("effects"))
            if (effect.path("consumeLongRestMove").asBoolean() && !oldEffects.contains(effect.path("id").asText()))
                throw new IllegalArgumentException("Recovery must be granted by an ally's paid rest.");
        int hopeGain = 0;
        for (JsonNode pick : request.path("picks")) {
            if ("prepare".equals(pick.path("move").asText())) hopeGain++;
            if ("prepare-group".equals(pick.path("move").asText())) hopeGain += 2;
        }
        if (next.path("hope").path("on").asInt() > Math.min(next.path("hope").path("slots").asInt(),
                before.path("hope").path("on").asInt() + hopeGain))
            throw new IllegalArgumentException("The selected downtime moves do not grant that much Hope.");
    }

    static void validateDamage(ObjectNode before, ObjectNode next, int hp) {
        for (String field : List.of("name", "level", "className", "subclass", "testingCharacter"))
            if (!before.path(field).equals(next.path(field))) throw new IllegalArgumentException("Damage cannot change character identity.");
        for (String resource : List.of("hp", "stress", "armorTrack", "hope")) {
            JsonNode track = next.path(resource);
            if (!track.path("on").isInt() || track.path("on").asInt() < 0
                    || track.path("on").asInt() > track.path("slots").asInt()
                    || track.path("slots").asInt() != before.path(resource).path("slots").asInt())
                throw new IllegalArgumentException("Invalid committed damage resources.");
        }
        int expectedHp = Math.min(before.path("hp").path("slots").asInt(), before.path("hp").path("on").asInt() + hp);
        JsonNode companion = before.path("companion"), resolvedCompanion = next.path("companion");
        boolean bonded = false;
        for (JsonNode training : companion.path("training")) bonded |= "bonded".equals(training.path("id").asText());
        if (bonded && expectedHp == before.path("hp").path("slots").asInt()
                && !companion.path("bondedAttempted").asBoolean() && !companion.path("unavailable").asBoolean()
                && companion.path("stress").path("on").asInt() < companion.path("stress").path("slots").asInt()
                && resolvedCompanion.path("bondedAttempted").asBoolean() && resolvedCompanion.path("unavailable").asBoolean()
                && resolvedCompanion.path("stress").path("on").equals(resolvedCompanion.path("stress").path("slots")))
            expectedHp--;
        if (next.path("hp").path("on").asInt() < expectedHp)
            throw new IllegalArgumentException("The committed damage must mark its Hit Points.");
    }
}
