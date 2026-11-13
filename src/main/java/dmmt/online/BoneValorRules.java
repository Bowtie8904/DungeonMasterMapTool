package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.HashSet;
import java.util.ArrayList;
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

    private record Pool(int count, int sides, int drop) {
        String expression(boolean maximized) {
            int rolled = count - (maximized ? 1 : 0);
            int kept = rolled - drop;
            String dice = rolled == 0 ? "" : rolled + "d" + sides + (drop > 0 ? "kh" + kept : "");
            return maximized ? (dice.isEmpty() ? "" : dice + "+") + sides : dice;
        }
    }

    private record WeaponPools(List<Pool> pools, int maximized) {}

    private static WeaponPools weaponPools(JsonNode request, JsonNode weapon, int proficiency, ObjectNode sheet) {
        JsonNode base = request.path("contributions").path(0);
        String expression = base.path("expression").asText().replaceFirst("^\\((.*)\\)\\*2$", "$1").toLowerCase(java.util.Locale.ROOT);
        String damage = weapon.path("damage").asText().replaceAll("\\s", "");
        var weaponDie = Pattern.compile("(?i)(\\d*)d(\\d+)").matcher(damage);
        if (proficiency < 1 || !damage.matches("(?i)^(?:\\d*d\\d+\\+)*\\d*d\\d+(?:[+-]\\d+)?$"))
            throw new IllegalArgumentException("Deathrun must use the current remaining weapon dice.");
        JsonNode weaponPool = weapon.path("automation").path("damage");
        boolean skipWeaponPool = weaponPool.path("proficiencyOnly").asBoolean() && weapon.path("fixedDice").asBoolean();
        int extra = skipWeaponPool ? 0 : weaponPool.path("extraDice").asInt();
        int drop = skipWeaponPool ? 0 : weaponPool.path("dropLowest").asInt();
        for (JsonNode effect : sheet.path("effects")) {
            JsonNode pool = effect.path("damage");
            if (pool.path("proficiencyOnly").asBoolean() && weapon.path("fixedDice").asBoolean()) continue;
            extra += pool.path("extraDice").asInt();
            drop += pool.path("dropLowest").asInt();
        }
        // Without a drop rule, the browser attributes extra dice as separate damage contributions.
        if (drop == 0) extra = 0;
        List<Pool> pools = new ArrayList<>();
        while (weaponDie.find()) {
            int count = (weapon.path("fixedDice").asBoolean()
                    ? weaponDie.group(1).isEmpty() ? 1 : Integer.parseInt(weaponDie.group(1)) : proficiency) + extra;
            if (count < 1 || drop < 0 || drop >= count)
                throw new IllegalArgumentException("Deathrun must use the current remaining weapon dice.");
            pools.add(new Pool(count, Integer.parseInt(weaponDie.group(2)), drop));
        }
        for (int maximized = -1; maximized < pools.size(); maximized++) {
            List<String> terms = new ArrayList<>();
            for (int i = 0; i < pools.size(); i++) terms.add(pools.get(i).expression(i == maximized));
            if (String.join("+", terms).equals(expression)) return new WeaponPools(pools, maximized);
        }
        throw new IllegalArgumentException("Deathrun must use the current remaining weapon dice.");
    }

    static void validateWeaponDice(JsonNode request, JsonNode weapon, int proficiency, ObjectNode sheet) {
        weaponPools(request, weapon, proficiency, sheet);
    }

    static ObjectNode deathrunSnapshot(ObjectNode roll, JsonNode request, JsonNode weapon, int proficiency, ObjectNode sheet) {
        ObjectNode snapshot = roll.deepCopy();
        ObjectNode base = (ObjectNode) snapshot.path("breakdown").path(0);
        String expression = base.path("expression").asText();
        boolean doubled = expression.matches("^\\(.*\\)\\*2$");
        WeaponPools parsed = weaponPools(request, weapon, proficiency, sheet);
        if (parsed.maximized() < 0) return snapshot;
        Pool pool = parsed.pools().get(parsed.maximized());
        int insertion = pool.count() - 1;
        for (int i = 0; i < parsed.maximized(); i++) insertion += parsed.pools().get(i).count();
        ArrayNode baseRolls = (ArrayNode) base.path("rolls");
        baseRolls.insert(insertion, pool.sides());
        ArrayNode all = snapshot.putArray("rolls");
        for (int i = 0; i < roll.path("rolls").size(); i++) {
            if (i == insertion) all.add(pool.sides());
            all.add(roll.path("rolls").path(i));
        }
        if (insertion == roll.path("rolls").size()) all.add(pool.sides());
        List<String> pools = new ArrayList<>();
        for (Pool p : parsed.pools()) pools.add(p.expression(false));
        String dice = String.join("+", pools);
        base.put("expression", doubled ? "(" + dice + ")*2" : dice);
        List<String> parts = new java.util.ArrayList<>();
        boolean smite = false;
        for (JsonNode part : snapshot.path("breakdown")) {
            if (List.of("Smite", "Invoke Torment", "First Strike").contains(part.path("label").asText()) && part.path("rolls").isEmpty()) smite = true;
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
        String originalBase = base.path("expression").asText();
        String updatedBase = remainingExpression(originalBase, kept);
        detail.put("expression", updatedBase);
        String originalExpression = original.path("expression").asText();
        String search = originalBase.replaceFirst("^\\((.*)\\)\\*2$", "$1");
        String replacement = updatedBase.replaceFirst("^\\((.*)\\)\\*2$", "$1");
        int baseStart = originalExpression.indexOf(search);
        if (baseStart < 0) throw new IllegalArgumentException("Deathrun damage no longer matches its weapon dice.");
        result.put("expression", originalExpression.substring(0, baseStart) + replacement
                + originalExpression.substring(baseStart + search.length()));
        for (JsonNode part : result.path("breakdown")) {
            if (!List.of("Smite", "Invoke Torment", "First Strike").contains(part.path("label").asText()) || !part.path("rolls").isEmpty()) continue;
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
        var pools = Pattern.compile("(\\d+)d(\\d+)(?:kh(\\d+))?", Pattern.CASE_INSENSITIVE).matcher(base.path("expression").asText());
        int offset = 0;
        while (pools.find()) {
            int rolled = Integer.parseInt(pools.group(1));
            int count = pools.group(3) == null ? rolled : Integer.parseInt(pools.group(3));
            List<Integer> current = new ArrayList<>();
            for (int i = offset; i < offset + rolled; i++) current.add(i);
            current.sort(java.util.Comparator.<Integer>comparingInt(i -> base.path("rolls").path(i).asInt()).reversed()
                    .thenComparing(java.util.Comparator.reverseOrder()));
            indices.addAll(current.subList(0, Math.min(count, current.size())));
            offset += rolled;
        }
        indices.sort(Integer::compareTo);
        return indices;
    }

    private static String remainingExpression(String expression, Set<Integer> kept) {
        var pools = Pattern.compile("(\\d+)d(\\d+)(?:kh(\\d+))?", Pattern.CASE_INSENSITIVE).matcher(expression);
        List<String> parts = new ArrayList<>();
        int offset = 0;
        while (pools.find()) {
            int rolled = Integer.parseInt(pools.group(1)), count = 0;
            for (int i = offset; i < offset + rolled; i++) if (kept.contains(i)) count++;
            if (count > 0) parts.add(count + "d" + pools.group(2));
            offset += rolled;
        }
        if (parts.isEmpty()) throw new IllegalArgumentException("Deathrun has no remaining weapon dice.");
        String result = String.join("+", parts);
        return expression.matches("^\\(.*\\)\\*2$") ? "(" + result + ")*2" : result;
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
