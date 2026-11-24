package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dmmt.online.Campaign;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds a character roll from a compiled profile and the active source on the saved sheet. */
final class ProfileRoll {
    private static final Pattern DAMAGE = Pattern.compile("(?i)^(\\d*d\\d+)(\\+\\d*d\\d+)*([+-]\\d+)?$");
    private static final Pattern DAMAGE_DIE = Pattern.compile("(?i)(\\d*)d(\\d+)");
    private ProfileRoll() {}

    record Modifier(JsonNode value, String sourceId, String label, boolean weapon) {}
    private record WeaponDamage(List<int[]> pools, int flat) {}

    static List<String> kinds(JsonNode profile) {
        return profile.has("rollKind") ? List.of(profile.path("rollKind").asText())
                : profile.path("damage").path("weapon").asBoolean() ? List.of("damage")
                : profile.has("damage") ? List.of("attack", "damage") : List.of("attack");
    }

    static ObjectNode weaponProfile(SheetProjection.SourceElement source, JsonNode declaredProfiles) {
        JsonNode weapon = source.element();
        String id = "weapon.base";
        while (hasProfile(declaredProfiles, id)) id += ".base";
        String name = RuleJson.text(weapon.get("name"), "/weapon/name");
        String trait = RuleJson.text(weapon.get("trait"), "/weapon/trait");
        if ("Spellcast".equalsIgnoreCase(trait)) trait = "spellcast";
        if (!List.of("Agility", "Strength", "Finesse", "Instinct", "Presence", "Knowledge", "spellcast").contains(trait))
            throw RuleJson.error("/weapon/trait", "Unknown weapon trait.");
        String range = switch (RuleJson.text(weapon.get("range"), "/weapon/range").replaceAll("\\s+", "").toLowerCase(Locale.ROOT)) {
            case "melee" -> "melee";
            case "veryclose" -> "veryClose";
            case "close" -> "close";
            case "far" -> "far";
            case "veryfar" -> "veryFar";
            default -> throw RuleJson.error("/weapon/range", "Unknown weapon range.");
        };
        String type = switch (RuleJson.text(weapon.get("damageType"), "/weapon/damageType").toLowerCase(Locale.ROOT)) {
            case "phys", "physical" -> "physical";
            case "mag", "magic" -> "magic";
            default -> throw RuleJson.error("/weapon/damageType", "Expected physical or magic weapon damage.");
        };
        if (weapon.has("fixedDice") && !weapon.get("fixedDice").isBoolean())
            throw RuleJson.error("/weapon/fixedDice", "Expected a boolean.");
        ObjectNode profile = RuleJson.JSON.createObjectNode().put("id", id).put("label", name)
                .put("trait", trait).put("range", range);
        profile.putObject("damage").put("formula", RuleJson.text(weapon.get("damage"), "/weapon/damage"))
                .put("fixedDice", weapon.path("fixedDice").asBoolean()).put("type", type);
        return profile;
    }

    private static boolean hasProfile(JsonNode profiles, String id) {
        for (JsonNode profile : profiles) if (id.equals(profile.path("id").asText())) return true;
        return false;
    }

    private static WeaponDamage weaponDamage(JsonNode damage, int proficiency) {
        String expression = RuleJson.text(damage.get("formula"), "/weapon/damage").replaceAll("\\s+", "");
        if (expression.length() > 256 || !DAMAGE.matcher(expression).matches())
            throw RuleJson.error("/weapon/damage", "Expected dice pools such as d8+d6+2.");
        boolean fixed = damage.path("fixedDice").booleanValue();
        if (!fixed && (proficiency < 1 || proficiency > 1024))
            throw RuleJson.error("/weapon/damage", "Weapon Proficiency must be between 1 and 1024.");
        List<int[]> pools = new ArrayList<>();
        Matcher matcher = DAMAGE_DIE.matcher(expression);
        int end = 0, total = 0;
        while (matcher.find()) {
            if (pools.size() >= 32) throw RuleJson.error("/weapon/damage", "Too many weapon dice pools.");
            int count = parseWeaponNumber(matcher.group(1).isEmpty() ? "1" : matcher.group(1), 1, 1024);
            int sides = parseWeaponNumber(matcher.group(2), 2, 1000);
            int actual = fixed ? count : proficiency;
            total += actual;
            if (total > 1024) throw RuleJson.error("/weapon/damage", "Weapon damage exceeds its dice limit.");
            pools.add(new int[]{actual, sides});
            end = matcher.end();
        }
        int flat = end == expression.length() ? 0 : parseWeaponNumber(expression.substring(end), -1_000_000, 1_000_000);
        return new WeaponDamage(pools, flat);
    }

    private static int parseWeaponNumber(String text, int min, int max) {
        try {
            return RuleJson.integer(RuleJson.JSON.getNodeFactory().numberNode(Long.parseLong(text)), "/weapon/damage", min, max);
        } catch (NumberFormatException ex) {
            throw RuleJson.error("/weapon/damage", "Weapon damage contains an out-of-range number.");
        }
    }

    static ObjectNode event(String type, String actorId, int fear) {
        ObjectNode event = RuleJson.JSON.createObjectNode().put("type", type).put("actorId", actorId);
        event.putObject("table").put("fear", fear);
        event.putObject("thresholds").putNull("major").putNull("severe");
        event.putNull("damage").putNull("damageType").putNull("roll").putNull("role").putNull("weaponId");
        ObjectNode check = event.putObject("check");
        for (String key : List.of("kind", "trait", "weapon", "range", "dominant", "outcome", "critical",
                "hopeDie", "fearDie", "spell", "origin", "total", "difficulty", "weaponSource", "weaponSourceId",
                "fixedDice", "damageType", "move", "adjustedHope", "recoverableHp", "reroll", "duality", "sourceName", "sourceDomain")) check.putNull(key);
        check.putArray("origins");
        check.putObject("facesByRole");
        return event;
    }

    static ObjectNode nestedEvent(ObjectNode parent, ObjectNode actor, ObjectNode source, ObjectNode roll) {
        ObjectNode defaults = event("roll.prepare", RuleJson.id(actor.get("id"), "/actor/id"),
                RuleJson.integer(parent.at("/table/fear"), "/event/table/fear", 0, Campaign.MAX_FEAR));
        ObjectNode event = parent.deepCopy();
        for (String field : List.of("type", "actorId", "thresholds", "damage", "damageType", "roll", "check"))
            event.set(field, defaults.get(field).deepCopy());
        event.remove(List.of("blocked", "resultId", "effectInstanceId", "profileId"));
        JsonNode metadata = roll.path("metadata");
        String kind = metadata.path("kind").asText();
        if ("action".equals(kind) && metadata.path("attack").asBoolean()) kind = "attack";
        ObjectNode check = (ObjectNode) event.get("check");
        check.put("kind", kind).put("reroll", false);
        String trait = metadata.path("trait").textValue();
        if ("spellcast".equals(trait)) trait = actor.path("spellcastTrait").path("name").textValue();
        if (trait != null) check.put("trait", trait);
        check.put("spell", "spellcast".equals(metadata.path("trait").asText())).put("sourceName", source.path("name").asText(null));
        for (String field : List.of("origin", "fixedDice", "damageType"))
            if (metadata.has(field)) check.set(field, metadata.get(field).deepCopy());
        ArrayNode origins = (ArrayNode) check.get("origins");
        for (String field : List.of("origin", "actionOrigin"))
            if (metadata.path(field).isTextual() && !metadata.path(field).asText().isBlank())
                origins.add(metadata.path(field).asText());
        if (actor.at("/stats/thresholds").isObject()) event.set("thresholds", actor.at("/stats/thresholds").deepCopy());
        event.put("sourceInstanceId", RuleJson.id(source.get("instanceId"), "/source/instanceId"))
                .put("label", metadata.path("label").asText(source.path("name").asText("Roll")));
        event.set("roll", roll.deepCopy());
        updateCheck(event);
        check.put("duality", check.path("hopeDie").isNumber() && check.path("fearDie").isNumber());
        return event;
    }

    static void facts(ObjectNode event, SheetProjection.SourceElement source, JsonNode profile, String kind,
                      ObjectNode actor, boolean equippedWeapon) {
        facts(event, source, profile, kind, actor, equippedWeapon, null);
    }

    /** {@code echo} is the equipped weapon a weapon-echo effect profile rolls instead of its own source. */
    static void facts(ObjectNode event, SheetProjection.SourceElement source, JsonNode profile, String kind,
                      ObjectNode actor, boolean equippedWeapon, SheetProjection.SourceElement echo) {
        if (profile.path("damage").path("weapon").asBoolean() && echo == null)
            throw new RuleRuntime.Unavailable("weapon", "The attack's weapon is no longer equipped.");
        if (echo != null) equippedWeapon = true;
        boolean damageRoll = "damage".equals(kind);
        if (!kinds(profile).contains(kind))
            throw RuleJson.error("/request/profileRoll/kind", "This roll kind is not available for the selected profile.");
        SheetProjection.SourceElement held = echo != null ? echo : source;
        JsonNode gear = held.element();
        JsonNode damage = profile.path("damage");
        if (damageRoll && !damage.isObject()) throw new RuleRuntime.Unavailable("profile", "This profile has no damage roll.");
        JsonNode stats = actor.path("stats");
        String declaredTrait = profile.path("trait").asText(equippedWeapon && !profile.has("rollKind")
                ? gear.path("trait").textValue() : null);
        String trait = "spellcast".equals(declaredTrait) ? actor.path("spellcastTrait").path("name").textValue() : declaredTrait;
        ObjectNode check = event.withObject("/check");
        check.put("kind", kind).put("duality", !damageRoll).put("trait", trait).put("range", profile.path("range").asText(null))
                .put("spell", "spellcast".equals(kind)).put("sourceName", source.name());
        if (!profile.has("rollKind")) {
            check.put("weaponSource", held.instanceId()).put("weaponSourceId", held.sourceId());
            if (equippedWeapon) {
                event.put("weaponId", held.instanceId());
                if ("damage".equals(kind)) event.put("role", "weaponDamage");
                else if ("attack".equals(kind)) event.put("role", "weaponAttack");
            }
        }
        if (equippedWeapon && !profile.has("rollKind")) {
            String slot = gear.path("slot").asText("").toLowerCase(Locale.ROOT);
            if ("primary".equals(slot) || "secondary".equals(slot)) check.put("weapon", slot);
        }
        if (!profile.has("rollKind")) {
            String domain = "Copycat".equals(source.name()) ? gear.path("borrowedCard").path("card").path("domain").asText(null)
                    : gear.path("domain").asText(null);
            if (domain != null && !domain.isBlank()) check.put("sourceDomain", domain);
        }
        if (profile.has("difficulty")) check.set("difficulty", profile.get("difficulty").deepCopy());
        if (profile.has("origin")) {
            check.set("origin", profile.get("origin").deepCopy());
            check.withArray("origins").removeAll().add(profile.path("origin").asText());
        }
        event.put("type", "roll.prepare").put("sourceInstanceId", source.instanceId())
                .put("profileId", profile.path("id").asText()).put("label", profile.path("label").asText());
        if (stats.path("thresholds").isObject()) event.set("thresholds", stats.get("thresholds").deepCopy());
        if (damageRoll) {
            String type = damage.path("type").asText(equippedWeapon ? gear.path("damageType").textValue() : null);
            if ("phys".equals(type)) type = "physical";
            if ("mag".equals(type)) type = "magic";
            check.put("fixedDice", damage.has("count") || damage.path("fixedDice").asBoolean()
                    || echo != null && gear.path("fixedDice").asBoolean()).put("damageType", type);
            event.put("damageType", type);
        } else if (equippedWeapon && !profile.has("rollKind")) {
            // An attack carries the damage type of the weapon so magic-weapon attacks can be told apart from other actions.
            String type = gear.path("damageType").asText(null);
            if ("phys".equals(type)) type = "physical";
            if ("mag".equals(type)) type = "magic";
            if (type != null) check.put("damageType", type);
        }
    }

    static void deathFacts(ObjectNode event, SheetProjection.SourceElement source, ObjectNode actor, String move) {
        if (!Set.of("risk", "avoid").contains(move)) throw RuleJson.error("/request/deathRoll/move", "Unknown death roll.");
        event.put("type", "roll.prepare").put("sourceInstanceId", source.instanceId())
                .put("profileId", "death." + move).put("label", "risk".equals(move) ? "Risk It All" : "Avoid Death");
        event.withObject("/check").put("kind", "death").put("move", move).put("duality", "risk".equals(move)).put("reroll", false);
        if (actor.at("/stats/thresholds").isObject()) event.set("thresholds", actor.at("/stats/thresholds").deepCopy());
    }

    static void prepareDeath(ObjectNode event, SheetProjection.SourceElement source, ObjectNode actor, String move,
                             RandomGenerator random) {
        deathFacts(event, source, actor, move);
        ObjectNode plan = RuleJson.JSON.createObjectNode().put("id", java.util.UUID.randomUUID().toString());
        ArrayNode parts = plan.putArray("contributions");
        dice(parts, "hope", "Hope die", "core", 1, 12);
        if ("risk".equals(move)) dice(parts, "fear", "Fear die", "core", 1, 12);
        event.set("roll", StructuredRoll.resolve(plan, RuleJson.JSON.createObjectNode(), random));
        updateCheck(event);
    }

    static void prepare(ObjectNode event, SheetProjection.SourceElement source, JsonNode profile, String kind,
                        ObjectNode actor, boolean equippedWeapon, List<Modifier> modifiers, RandomGenerator random) {
        prepare(event, source, profile, kind, actor, equippedWeapon, modifiers, random, null);
    }

    static void prepare(ObjectNode event, SheetProjection.SourceElement source, JsonNode profile, String kind,
                        ObjectNode actor, boolean equippedWeapon, List<Modifier> modifiers, RandomGenerator random,
                        SheetProjection.SourceElement echo) {
        facts(event, source, profile, kind, actor, equippedWeapon, echo);
        SheetProjection.SourceElement held = echo != null ? echo : source;
        JsonNode check = event.path("check"), stats = actor.path("stats"), damage = profile.path("damage");
        ObjectNode plan = RuleJson.JSON.createObjectNode().put("id", java.util.UUID.randomUUID().toString());
        ArrayNode parts = plan.putArray("contributions");
        if (!"damage".equals(kind)) {
            dice(parts, "hope", "Hope die", "core", 1, 12);
            dice(parts, "fear", "Fear die", "core", 1, 12);
            String trait = check.path("trait").textValue();
            if ("spellcast".equals(profile.path("trait").asText()) && trait == null)
                throw new RuleRuntime.Unavailable("trait", "Choose a Spellcast trait before making this roll.");
            if (trait != null) {
                JsonNode mod = "spellcast".equals(profile.path("trait").asText())
                        ? stats.path("spellcast").path("total") : stats.path("trait").path(trait);
                if (!mod.isNumber()) throw new RuleRuntime.Unavailable("trait", "The profile trait is not available.");
                parts.addObject().put("id", "trait").put("kind", "flat").put("role", "modifier")
                        .put("sourceId", source.sourceId()).put("label", trait).set("value", mod);
            }
            if (profile.has("difficulty")) plan.putObject("metadata").set("difficulty", profile.get("difficulty").deepCopy());
        } else {
            ObjectNode metadata = plan.putObject("metadata");
            metadata.set("damageType", check.get("damageType"));
            int flat;
            if (damage.has("formula") || echo != null) {
                int proficiency = stats.path("proficiency").asInt();
                if (echo != null && "half".equals(damage.path("scale").asText())) proficiency = Math.max(1, (proficiency + 1) / 2);
                JsonNode weaponDamage = echo == null ? damage : RuleJson.JSON.createObjectNode()
                        .put("formula", RuleJson.text(echo.element().get("damage"), "/weapon/damage"))
                        .put("fixedDice", echo.element().path("fixedDice").asBoolean());
                WeaponDamage base = weaponDamage(weaponDamage, proficiency);
                for (int[] pool : base.pools())
                    dice(parts, parts.isEmpty() ? "damage" : "damage." + (parts.size() + 1), "damage",
                            profile.path("label").asText(), held.sourceId(), pool[0], pool[1]);
                flat = base.flat();
            } else {
                dice(parts, "damage", profile.path("label").asText(), source.sourceId(),
                        damage.path("count").asInt(stats.path("proficiency").asInt()), damage.path("sides").intValue());
                flat = damage.path("flat").asInt();
            }
            ArrayNode baseIds = metadata.putArray("weaponBaseContributions");
            for (JsonNode part : parts) baseIds.add(part.path("id").asText());
            if (flat != 0) parts.addObject().put("id", "bonus").put("kind", "flat")
                    .put("role", "damage").put("sourceId", held.sourceId()).put("label", profile.path("label").asText())
                    .put("value", flat);
        }
        List<String> statsForRoll = switch (kind) {
            case "attack" -> List.of("roll.action", "roll.attack");
            case "damage" -> List.of("roll.damage");
            case "action" -> List.of("roll.action");
            case "reaction" -> List.of("roll.reaction");
            case "spellcast" -> List.of("roll.action", "roll.spellcast");
            default -> throw RuleJson.error("/request/profileRoll/kind", "Unknown profile roll kind.");
        };
        for (String stat : statsForRoll)
            addModifiers(parts, modifiers, stat, held.instanceId(), kind);
        event.set("roll", StructuredRoll.resolve(plan, RuleJson.JSON.createObjectNode(), random));
        updateCheck(event);
    }

    private static void addModifiers(ArrayNode parts, List<Modifier> modifiers, String stat, String sourceInstanceId,
                                     String kind) {
        Modifier set = null, floor = null, ceiling = null;
        double value = 0;
        for (Modifier modifier : modifiers) {
            JsonNode item = modifier.value();
            if (!stat.equals(item.path("stat").asText())
                    || modifier.weapon() && !sourceInstanceId.equals(modifier.sourceId())) continue;
            double amount = RuleExpression.number(item.get("value"), "/modifiers/value");
            switch (item.path("op").asText("add")) {
                case "add" -> {
                    value += amount;
                    addModifier(parts, modifier, kind, amount);
                }
                case "set" -> set = modifier;
                case "atLeast" -> {
                    if (floor == null || amount > floor.value().path("value").asDouble()) floor = modifier;
                }
                case "atMost" -> {
                    if (ceiling == null || amount < ceiling.value().path("value").asDouble()) ceiling = modifier;
                }
                default -> throw RuleJson.error("/modifiers/op", "Unknown modifier operation.");
            }
        }
        if (set != null) {
            double amount = set.value().path("value").asDouble();
            value += amount;
            addModifier(parts, set, kind, amount);
        }
        if (floor != null) {
            double adjusted = Math.max(value, floor.value().path("value").asDouble());
            addModifier(parts, floor, kind, adjusted - value);
            value = adjusted;
        }
        if (ceiling != null) {
            double adjusted = Math.min(value, ceiling.value().path("value").asDouble());
            addModifier(parts, ceiling, kind, adjusted - value);
        }
    }

    private static void addModifier(ArrayNode parts, Modifier modifier, String kind, double value) {
        if (value == 0) return;
        parts.addObject().put("id", "modifier." + parts.size()).put("kind", "flat")
                .put("role", "damage".equals(kind) ? "damage" : "modifier")
                .put("sourceId", modifier.sourceId()).put("label", modifier.label())
                .set("value", RuleExpression.numeric(value, "/modifiers/value"));
    }

    static void updateCheck(ObjectNode event) {
        JsonNode roll = event.path("roll");
        ObjectNode check = (ObjectNode) event.path("check");
        check.putNull("hopeDie").putNull("fearDie").setAll(StructuredRoll.check(roll));
        ObjectNode byRole = check.putObject("facesByRole");
        if (roll.isObject()) for (JsonNode die : StructuredRoll.dice(roll, null, true))
            byRole.withArray(die.path("role").asText()).add(die.path("face").asInt());
        if ("death".equals(check.path("kind").asText())) {
            ArrayNode hopeDice = StructuredRoll.dice(roll, "hope", true);
            ArrayNode fearDice = StructuredRoll.dice(roll, "fear", true);
            boolean risk = "risk".equals(check.path("move").asText());
            if (hopeDice.size() != 1 || fearDice.size() != (risk ? 1 : 0))
                throw new RuleRuntime.Unavailable("deathDice", risk
                        ? "Risk It All requires one Hope die and one Fear die." : "Avoid Death requires one Hope die.");
            int hope = hopeDice.get(0).path("face").asInt(), fear = risk ? fearDice.get(0).path("face").asInt() : 0;
            int adjusted = RuleJson.integer(RuleExpression.numeric(roll.path("total").asDouble() - fear,
                    "/event/check/adjustedHope"), "/event/check/adjustedHope", -1_000_000, 1_000_000);
            check.put("hopeDie", hope).put("adjustedHope", adjusted);
            if (risk) {
                boolean critical = hope == fear;
                boolean succeeds = critical || adjusted >= fear || "hope".equals(roll.at("/metadata/deathOutcome").asText());
                check.put("fearDie", fear).put("critical", critical).put("dominant", succeeds ? "hope" : "fear")
                        .put("outcome", succeeds ? "success" : "failure");
            } else check.put("critical", false).putNull("dominant").putNull("outcome");
        }
        if ("damage".equals(check.path("kind").asText())) {
            String type = roll.path("metadata").path("damageType").asText(null);
            event.put("damageType", type);
            check.put("damageType", type);
        }
    }

    private static void dice(ArrayNode parts, String role, String label, String sourceId, int count, int sides) {
        dice(parts, role, role, label, sourceId, count, sides);
    }

    private static void dice(ArrayNode parts, String id, String role, String label, String sourceId, int count, int sides) {
        parts.addObject().put("id", id).put("kind", "dice").put("role", role).put("label", label)
                .put("sourceId", sourceId).put("count", count).put("sides", sides);
    }
}
