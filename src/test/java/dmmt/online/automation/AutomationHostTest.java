package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static dmmt.online.automation.RuleConformanceTest.fixture;
import static org.junit.jupiter.api.Assertions.*;

class AutomationHostTest {
    private static final AutomationHost HOST = AutomationHost.bundled();

    private static AutomationHost.Pack pack() throws Exception {
        return AutomationHost.compile("test", Map.of(
                "classes", fixture("rewrite/host-classes").toString(),
                "consumables", fixture("rewrite/consumables").toString()), fixture("rewrite/recipes").toString());
    }

    private static ObjectNode sheet() throws Exception {
        return (ObjectNode) RuleJson.parse("""
                {"name":"Ada","level":2,"evasion":10,"gold":5,
                 "hp":{"slots":6,"total":12,"on":2},"stress":{"slots":6,"total":12,"on":1},
                 "armorTrack":{"slots":3,"total":12,"on":0},"hope":{"slots":6,"total":6,"on":1},
                 "proficiency":{"slots":6,"total":6,"on":1},"traits":[{"name":"Agility","mod":1,"marked":false}],
                 "features":[
                   {"name":"Rally","text":"","automationSource":"classes:tester:features:rally","instanceId":"f-rally"},
                   {"name":"Forage","text":"","automationSource":"classes:tester:features:forage","instanceId":"f-forage"},
                   {"name":"Steady","text":"","automationSource":"classes:tester:features:steady","instanceId":"f-steady"},
                   {"name":"Plain","text":"No automation"}],
                 "heritage":[],"loadout":[],"vault":[
                   {"name":"Vaulted","automationSource":"classes:tester:features:rally","instanceId":"v-rally"}],
                 "inventory":[
                   {"name":"Minor Health Potion","qty":1,"type":"consumable","automationSource":"consumables:minor-health-potion","instanceId":"i-potion"},
                   {"name":"Rope","qty":1,"type":"gear"}],
                 "activeWeapons":[]}""");
    }

    private static ObjectNode action() {
        return RuleJson.JSON.createObjectNode().put("type", "action.request");
    }

    private static ObjectNode request(String id, long revision, String instance, String rule) {
        ObjectNode request = RuleJson.JSON.createObjectNode().put("requestId", id).put("expectedRevision", revision);
        request.putArray("selections").addObject().put("sourceInstanceId", instance).put("ruleId", rule).put("bindingId", "sheet");
        return request;
    }

    private static List<String> labels(ObjectNode described) {
        return described.path("actions").findValuesAsText("label");
    }

    @Test
    void describesOnlyActiveSourcesVisibleToTheViewer() throws Exception {
        var pack = pack();
        ObjectNode sheet = sheet();
        assertEquals(List.of("Forage", "Rally (1 Hope)", "Drink Minor Health Potion"), labels(HOST.describe(pack, sheet, action(), "sheet", "self")));
        assertEquals(List.of("Rally (1 Hope)"), labels(HOST.describe(pack, sheet, action(), "sheet", "other")));
        assertEquals(List.of(), labels(HOST.describe(pack, sheet, action(), "sheet", "dm")));
        JsonNode profile = HOST.describe(pack, sheet, action(), "sheet", "self").path("profiles").get(0);
        assertEquals("f-steady", profile.path("sourceInstanceId").asText());
        assertEquals("Steady", profile.path("sourceName").asText());
        assertEquals("thrown", profile.path("id").asText());
        assertEquals(6, profile.at("/damage/sides").asInt());
        assertEquals(sheet(), sheet, "describing must not change the sheet");
    }

    @Test
    void profileOffersMatchEquippedDamageFactsAndFilterByBindingAndPrice() throws Exception {
        var classes = fixture("rewrite/host-classes");
        var rules = (com.fasterxml.jackson.databind.node.ArrayNode) classes.at("/entries/0/features/2/automation/rules");
        rules.add(RuleJson.parse("""
                {"id":"edge","bindings":[
                   {"id":"offer","event":"roll.prepare","surface":"roll","mode":"option","exclusiveGroup":"stance"},
                   {"id":"hidden","event":"roll.prepare","surface":"roll","mode":"option","audience":"others"},
                   {"id":"sheet","event":"roll.prepare","surface":"sheet","mode":"option"},
                   {"id":"button","event":"roll.prepare","surface":"roll","mode":"button"}],
                 "when":{"and":[{"eq":[{"read":"event.check.weapon"},"primary"]},
                                 {"eq":[{"read":"event.damageType"},"magic"]}]},
                 "inputs":[{"id":"n","kind":"integer","label":"Amount","min":1,"max":3,"default":2}],
                 "costs":[{"op":"resource.spend","pool":"actor.hope","amount":2}],
                 "ui":{"label":"Edge"},"do":[{"op":"resource.gain","pool":"actor.hope","amount":{"read":"input.n"}}]}"""));
        var pack = AutomationHost.compile("test", Map.of("classes", classes.toString()), fixture("rewrite/recipes").toString());
        ObjectNode sheet = sheet();
        var gear = (ObjectNode) sheet.withArray("features").remove(2);
        sheet.withArray("activeWeapons").add(gear.put("slot", "Primary").put("damageType", "mag")
                .put("trait", "Finesse").put("range", "Far").put("damage", "1d6"));
        var origin = AutomationHost.event("action.request", "actor", 0);
        JsonNode options = HOST.describe(pack, sheet, origin, "sheet", "self").requiredAt("/profiles/0/options");
        assertTrue(options.required("attack").isEmpty());
        assertEquals(List.of("offer"), options.required("damage").findValuesAsText("bindingId"));
        assertEquals("cost", options.requiredAt("/damage/0/reasonCode").textValue());
        assertTrue(HOST.describe(pack, sheet, origin, "sheet", "other").required("profiles").isEmpty());
        ((ObjectNode) sheet.required("hope")).put("on", 3);
        JsonNode offer = HOST.describe(pack, sheet, origin, "sheet", "self").requiredAt("/profiles/0/options/damage/0");
        assertTrue(offer.required("available").booleanValue());
        assertEquals("stance", offer.required("exclusiveGroup").textValue());
        assertEquals(2, offer.requiredAt("/inputs/0/default").intValue());
        var request = request("damage", 0, "f-steady", "edge");
        request.putObject("profileRoll").put("sourceInstanceId", "f-steady").put("profileId", "thrown").put("kind", "damage");
        var selected = (ObjectNode) request.requiredAt("/selections/0");
        selected.putObject("inputs").put("n", 1);
        assertThrows(IllegalArgumentException.class, () -> HOST.execute(pack, sheet, origin, request, "self", new SplittableRandom(1)));
        selected.put("bindingId", "offer");
        var result = HOST.execute(pack, sheet, origin, request, "self", new SplittableRandom(1));
        assertEquals("magic", result.requiredAt("/event/check/damageType").textValue());
        assertEquals(2, sheet.requiredAt("/hope/on").intValue());
    }

    @Test
    void profileRollsApplyApplicableModifiersWithProvenanceAndRetryOnce() throws Exception {
        var classes = fixture("rewrite/host-classes");
        ObjectNode otherWeapon = (ObjectNode) classes.at("/entries/0/features/0/automation");
        otherWeapon.set("modifiers", RuleJson.parse("""
                [{"stat":"roll.attack","value":99},{"stat":"roll.damage","value":99}]"""));
        ObjectNode feature = (ObjectNode) classes.at("/entries/0/features/1/automation");
        feature.set("modifiers", RuleJson.parse("""
                [{"stat":"roll.action","op":"set","value":2,"label":"Action base"},
                 {"stat":"roll.action","value":3,"label":"Action bonus"},
                 {"stat":"roll.action","op":"atLeast","value":8,"label":"Action floor"},
                 {"stat":"roll.action","op":"atMost","value":7,"label":"Action ceiling"},
                 {"stat":"roll.attack","value":-1,"label":"Conditional penalty",
                  "when":{"gt":[{"read":"actor.hope.value"},0]}},
                 {"stat":"roll.damage","value":2,"label":"Damage bonus"},
                 {"stat":"trait.Finesse","value":2}]"""));
        ObjectNode weapon = (ObjectNode) classes.at("/entries/0/features/2/automation");
        weapon.set("modifiers", RuleJson.parse("""
                [{"stat":"roll.attack","value":5,"label":"Weapon bonus"},
                 {"stat":"roll.damage","value":-1,"label":"Weapon penalty"}]"""));
        weapon.withArray("rules").add(RuleJson.parse("""
                {"id":"edge","bindings":[{"id":"offer","event":"roll.prepare","surface":"roll","mode":"option"}],
                 "costs":[{"op":"resource.spend","pool":"actor.hope","amount":1}],
                 "do":[{"op":"roll.contribute","from":{"read":"event.roll"},
                        "contribution":{"id":"edge","kind":"flat","role":"modifier","label":"Edge","value":4},"as":"next"},
                       {"op":"roll.publish","roll":{"read":"result.next"}}],
                 "ui":{"label":"Edge"}}"""));
        var pack = AutomationHost.compile("test", Map.of("classes", classes.toString()), fixture("rewrite/recipes").toString());
        ObjectNode sheet = sheet();
        sheet.withArray("traits").addObject().put("name", "Finesse").put("mod", 1);
        var features = sheet.withArray("features");
        ObjectNode selectedWeapon = (ObjectNode) features.remove(2);
        ObjectNode unselectedWeapon = (ObjectNode) features.remove(0);
        sheet.withArray("activeWeapons").add(selectedWeapon.put("slot", "Primary").put("trait", "Finesse")
                .put("range", "Far").put("damage", "1d6").put("damageType", "phys"));
        sheet.withArray("activeWeapons").add(unselectedWeapon.put("slot", "Secondary").put("trait", "Agility")
                .put("range", "Melee").put("damage", "1d8").put("damageType", "phys"));
        sheet.putObject("automationState").putArray("effects").addObject()
                .put("id", "active-effect").put("sourceInstanceId", "f-forage").put("label", "Active effect")
                .putArray("modifiers").addObject().put("stat", "roll.attack").put("value", 3);
        ((ObjectNode) sheet.at("/automationState/effects/0")).withArray("modifiers")
                .addObject().put("stat", "roll.damage").put("value", 4);
        var origin = AutomationHost.event("action.request", "actor", 0);
        ObjectNode attack = request("attack", 0, "f-steady", "edge");
        ((ObjectNode) attack.at("/selections/0")).put("bindingId", "offer");
        attack.putObject("profileRoll").put("sourceInstanceId", "f-steady").put("profileId", "thrown").put("kind", "attack");
        ObjectNode result = HOST.execute(pack, sheet, origin, attack, "self", new SplittableRandom(7));
        JsonNode roll = result.at("/event/roll");
        int dice = result.at("/event/check/hopeDie").intValue() + result.at("/event/check/fearDie").intValue();
        assertEquals(dice + 21, roll.path("total").intValue(), "trait 3, action 7, attack 7 and selected option 4");
        assertTrue(roll.path("contributions").findValuesAsText("label").containsAll(
                List.of("Action base", "Action bonus", "Action floor", "Action ceiling", "Conditional penalty",
                        "Weapon bonus", "Active effect", "Edge")));
        assertFalse(roll.path("contributions").findValuesAsText("sourceId").contains("f-rally"));
        assertEquals(0, sheet.at("/hope/on").intValue());
        assertEquals(result, HOST.execute(pack, sheet, origin, attack, "self", null));
        assertEquals(0, sheet.at("/hope/on").intValue());

        ObjectNode damage = RuleJson.JSON.createObjectNode().put("requestId", "damage").put("expectedRevision", 1);
        damage.putArray("selections");
        damage.putObject("profileRoll").put("sourceInstanceId", "f-steady").put("profileId", "thrown").put("kind", "damage");
        JsonNode damageRoll = HOST.execute(pack, sheet, origin, damage, "self", new SplittableRandom(9)).at("/event/roll");
        JsonNode damageDie = damageRoll.at("/contributions/0/dice/0/face");
        assertEquals(damageDie.intValue() + 6, damageRoll.path("total").intValue(), "profile flat 1, damage modifiers 2-1+4");
        assertFalse(damageRoll.path("contributions").findValuesAsText("sourceId").contains("f-rally"));

        ObjectNode noHope = RuleJson.JSON.createObjectNode().put("requestId", "no-hope").put("expectedRevision", 2);
        noHope.putArray("selections");
        noHope.putObject("profileRoll").put("sourceInstanceId", "f-steady").put("profileId", "thrown").put("kind", "attack");
        JsonNode noHopeRoll = HOST.execute(pack, sheet, origin, noHope, "self", new SplittableRandom(7)).at("/event/roll");
        assertFalse(noHopeRoll.path("contributions").findValuesAsText("label").contains("Conditional penalty"));
        assertEquals(roll.path("total").intValue() - 3, noHopeRoll.path("total").intValue(), "same dice, no option, no penalty");
    }

    @Test
    void equippedWeaponBaseProfilesUseSavedMixedDiceAndDistinctIds() throws Exception {
        ObjectNode classes = fixture("rewrite/host-classes");
        ((ObjectNode) classes.at("/entries/0/features/2/automation/profiles/0")).put("id", "weapon.base");
        var pack = AutomationHost.compile("test", Map.of("classes", classes.toString()), fixture("rewrite/recipes").toString());
        ObjectNode sheet = sheet();
        sheet.withArray("traits").addObject().put("name", "Finesse").put("mod", 2);
        ((ObjectNode) sheet.path("proficiency")).put("on", 2);
        ObjectNode weapon = (ObjectNode) sheet.withArray("features").remove(2);
        sheet.withArray("activeWeapons").add(weapon.put("name", "Customized Blade").put("slot", "Primary")
                .put("trait", "Finesse").put("range", "Very Close").put("damageType", "phys").put("damage", "1d8 + d6 + 3"));
        sheet.withArray("activeWeapons").addObject().put("name", "Fixed").put("slot", "Secondary")
                .put("instanceId", "w-fixed").put("automationSource", "weapons:custom-fixed")
                .put("trait", "Agility").put("range", "Melee").put("damageType", "magic")
                .put("damage", "2d8+1d4-3").put("fixedDice", true);
        ObjectNode origin = AutomationHost.event("action.request", "actor", 0);
        JsonNode profiles = HOST.describe(pack, sheet, origin, "sheet", "self").required("profiles");
        assertEquals(List.of("weapon.base.base", "weapon.base", "weapon.base"), profiles.findValuesAsText("id"));
        assertTrue(HOST.describe(pack, sheet, origin, "sheet", "other").required("profiles").isEmpty());

        ObjectNode attack = profileRequest("attack", 0, "f-steady", "weapon.base.base", "attack");
        ObjectNode attacked = HOST.execute(pack, sheet, origin, attack, "self", new SplittableRandom(2));
        assertEquals("Finesse", attacked.requiredAt("/event/check/trait").textValue());
        assertEquals("veryClose", attacked.requiredAt("/event/check/range").textValue());
        assertEquals("primary", attacked.requiredAt("/event/check/weapon").textValue());
        assertEquals("f-steady", attacked.requiredAt("/event/check/weaponSource").textValue());
        assertEquals(attacked, HOST.execute(pack, sheet, origin, attack, "self", null));

        ObjectNode damage = profileRequest("damage", 1, "f-steady", "weapon.base.base", "damage");
        JsonNode result = HOST.execute(pack, sheet, origin, damage, "self", new SplittableRandom(3));
        JsonNode event = result.required("event");
        assertEquals("physical", event.requiredAt("/check/damageType").textValue());
        assertFalse(event.requiredAt("/check/fixedDice").booleanValue());
        JsonNode parts = event.requiredAt("/roll/contributions");
        assertEquals(2, parts.get(0).required("dice").size());
        assertEquals(2, parts.get(1).required("dice").size());
        assertEquals(8, parts.get(0).requiredAt("/dice/0/sides").intValue());
        assertEquals(6, parts.get(1).requiredAt("/dice/0/sides").intValue());
        assertEquals("classes:tester:features:steady", parts.get(1).required("sourceId").textValue());
        assertEquals(parts.get(0).required("total").intValue() + parts.get(1).required("total").intValue() + 3,
                event.requiredAt("/check/total").intValue());

        ObjectNode fixed = profileRequest("fixed", 2, "w-fixed", "weapon.base", "damage");
        JsonNode fixedEvent = HOST.execute(pack, sheet, origin, fixed, "self", new SplittableRandom(4)).required("event");
        assertTrue(fixedEvent.requiredAt("/check/fixedDice").booleanValue());
        assertEquals("magic", fixedEvent.requiredAt("/check/damageType").textValue());
        JsonNode fixedParts = fixedEvent.requiredAt("/roll/contributions");
        assertEquals(2, fixedParts.get(0).required("dice").size());
        assertEquals(1, fixedParts.get(1).required("dice").size());
        assertEquals(fixedParts.get(0).required("total").intValue() + fixedParts.get(1).required("total").intValue() - 3,
                fixedEvent.requiredAt("/check/total").intValue());
    }

    @Test
    void weaponDamageRejectsMalformedExpressionsAndStaleOrForgedSources() throws Exception {
        var pack = pack();
        ObjectNode sheet = sheet();
        ObjectNode weapon = sheet.withArray("activeWeapons").addObject().put("name", "Custom").put("slot", "Primary")
                .put("instanceId", "custom").put("automationSource", "weapons:custom")
                .put("trait", "Agility").put("range", "Melee").put("damageType", "phys").put("damage", "1d8");
        ObjectNode origin = AutomationHost.event("action.request", "actor", 0);
        ObjectNode damage = profileRequest("bad", 0, "custom", "weapon.base", "damage");
        for (String invalid : List.of("1d8+bad", "1d1", "1d1001", "0d6", "1d8+999999999999999999999", "d8-d6")) {
            weapon.put("damage", invalid);
            ObjectNode before = sheet.deepCopy();
            assertThrows(IllegalArgumentException.class, () -> HOST.execute(pack, sheet, origin, damage, "self", new SplittableRandom(1)), invalid);
            assertEquals(before, sheet, "Invalid damage must not commit anything.");
        }
        weapon.put("damage", "d8");
        ObjectNode forged = damage.deepCopy();
        ((ObjectNode) forged.get("profileRoll")).put("damage", "100d100");
        assertThrows(IllegalArgumentException.class, () -> HOST.execute(pack, sheet, origin, forged, "self", new SplittableRandom(1)));
        sheet.withArray("activeWeapons").removeAll();
        assertThrows(RuleRuntime.Unavailable.class, () -> HOST.execute(pack, sheet, origin, damage, "self", new SplittableRandom(1)));
    }

    @Test
    void storedEffectProfilesRollOnceAndExpireWithTheirActivation() throws Exception {
        ObjectNode classes = fixture("rewrite/host-classes");
        ObjectNode automation = (ObjectNode) classes.at("/entries/0/features/2/automation");
        ObjectNode form = automation.putObject("effects").putObject("form").put("label", "Form");
        form.set("profile", RuleJson.parse("""
                {"label":"Claws","trait":"Agility","range":"melee",
                 "damage":{"count":2,"sides":8,"flat":2,"type":"physical"}}"""));
        automation.set("rules", RuleJson.parse("""
                [{"id":"start","bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button"}],
                  "do":[{"op":"effect.start","effect":"form","target":"actor"}],"ui":{"label":"Start"}},
                 {"id":"end","bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button"}],
                  "do":[{"op":"effect.end","effect":"form","target":"actor"}],"ui":{"label":"End"}},
                 {"id":"option","bindings":[{"id":"use","event":"roll.prepare","surface":"roll","mode":"option"}],
                  "when":{"eq":[{"read":"event.profileId"},"effect.profile"]},
                  "costs":[{"op":"resource.spend","pool":"actor.hope","amount":1}],
                  "do":[{"op":"roll.contribute","from":{"read":"event.roll"},"as":"edited",
                         "contribution":{"id":"option-bonus","kind":"flat","role":"damage","label":"Bonus","value":3}},
                        {"op":"roll.publish","roll":{"read":"result.edited"}}],"ui":{"label":"Bonus"}},
                 {"id":"reaction","bindings":[{"id":"auto","event":"roll.finished","surface":"roll","mode":"automatic"}],
                  "do":[{"op":"track.mark","track":"actor.stress","amount":1}],"ui":{"label":"Reaction"}},
                 {"id":"after","bindings":[{"id":"use","event":"roll.finished","surface":"roll","mode":"button"}],
                  "do":[{"op":"resource.gain","pool":"actor.hope","amount":1}],"ui":{"label":"After"}}]"""));
        var pack = AutomationHost.compile("test", Map.of("classes", classes.toString()), fixture("rewrite/recipes").toString());
        ObjectNode sheet = sheet(), origin = AutomationHost.event("action.request", "actor", 0).put("sessionId", "session");
        assertEquals(1, HOST.describe(pack, sheet, origin, "sheet", "self").path("profiles").size());
        HOST.execute(pack, sheet, origin, request("start", 0, "f-steady", "start"), "self", new SplittableRandom(1));
        String effectId = sheet.requiredAt("/automationState/effects/0/id").textValue();
        JsonNode profile = HOST.describe(pack, sheet, origin, "sheet", "self").requiredAt("/profiles/1");
        assertEquals("effect.profile", profile.required("id").textValue());
        assertEquals(effectId, profile.required("effectInstanceId").textValue());
        assertEquals("Claws", profile.required("label").textValue());
        assertEquals("option", profile.requiredAt("/options/damage/0/ruleId").textValue());
        assertTrue(HOST.describe(pack, sheet, origin, "sheet", "other").required("profiles").isEmpty());

        ObjectNode damage = profileRequest("damage", 1, "f-steady", "effect.profile", "damage");
        ((ObjectNode) damage.required("profileRoll")).put("effectInstanceId", effectId);
        damage.withArray("selections").addObject().put("sourceInstanceId", "f-steady").put("ruleId", "option").put("bindingId", "use");
        ObjectNode result = HOST.execute(pack, sheet, origin, damage, "self", "owner", new SplittableRandom(4));
        assertEquals(effectId, result.requiredAt("/event/effectInstanceId").textValue());
        assertEquals("physical", result.requiredAt("/event/check/damageType").textValue());
        assertEquals(2, result.requiredAt("/event/roll/contributions/0/dice").size());
        assertEquals(8, result.requiredAt("/event/roll/contributions/0/dice/0/sides").intValue());
        assertEquals(result.requiredAt("/event/roll/contributions/0/total").intValue() + 5,
                result.requiredAt("/event/check/total").intValue());
        assertEquals(0, sheet.requiredAt("/hope/on").intValue());
        assertEquals(2, sheet.requiredAt("/stress/on").intValue());
        assertEquals(result, HOST.execute(pack, sheet, origin, damage, "self", "owner", null));
        String resultId = result.requiredAt("/event/resultId").textValue();
        assertEquals(1, HOST.describeResult(pack, sheet, resultId, "self", "owner", origin).required("actions").size());

        HOST.execute(pack, sheet, origin, request("end", 2, "f-steady", "end"), "self", new SplittableRandom(1));
        HOST.execute(pack, sheet, origin, request("restart", 3, "f-steady", "start"), "self", new SplittableRandom(1));
        assertNotEquals(effectId, sheet.requiredAt("/automationState/effects/0/id").textValue());
        assertThrows(RuleRuntime.Unavailable.class, () -> HOST.describeResult(pack, sheet, resultId, "self", "owner", origin));
        ObjectNode stale = profileRequest("stale", 4, "f-steady", "effect.profile", "damage");
        ((ObjectNode) stale.required("profileRoll")).put("effectInstanceId", effectId);
        ObjectNode before = sheet.deepCopy();
        assertThrows(RuleRuntime.Unavailable.class, () -> HOST.execute(pack, sheet, origin, stale, "self", StructuredRollTest.noRandom()));
        assertEquals(before, sheet);
        ((ObjectNode) stale.required("profileRoll")).put("effectInstanceId", sheet.requiredAt("/automationState/effects/0/id").textValue())
                .put("sourceInstanceId", "f-rally");
        assertThrows(RuleRuntime.Unavailable.class, () -> HOST.execute(pack, sheet, origin, stale, "self", StructuredRollTest.noRandom()));
        assertEquals(before, sheet);
    }

    @Test
    void endingEffectDuringRollDoesNotLeaveAResultOffer() throws Exception {
        ObjectNode classes = fixture("rewrite/host-classes");
        ObjectNode automation = (ObjectNode) classes.at("/entries/0/features/2/automation");
        ObjectNode form = automation.putObject("effects").putObject("form").put("label", "Form");
        form.set("profile", RuleJson.parse("""
                {"label":"Claws","damage":{"sides":6,"type":"physical"}}"""));
        automation.set("rules", RuleJson.parse("""
                [{"id":"end","bindings":[{"id":"auto","event":"roll.prepare","surface":"roll","mode":"automatic"}],
                  "do":[{"op":"effect.end","effect":"form","target":"actor"}],"ui":{"label":"End"}},
                 {"id":"after","bindings":[{"id":"use","event":"roll.finished","surface":"roll","mode":"button"}],
                  "do":[{"op":"resource.gain","pool":"actor.hope","amount":1}],"ui":{"label":"After"}}]"""));
        var pack = AutomationHost.compile("test", Map.of("classes", classes.toString()), fixture("rewrite/recipes").toString());
        ObjectNode sheet = sheet(), origin = AutomationHost.event("action.request", "actor", 0);
        ObjectNode effect = HOST.projection().state(sheet).putArray("effects").addObject()
                .put("id", "activation").put("effect", "classes:tester:features:steady#form").put("sourceInstanceId", "f-steady");
        effect.set("profile", form.required("profile").deepCopy());
        ObjectNode damage = profileRequest("damage", 0, "f-steady", "effect.profile", "damage");
        ((ObjectNode) damage.required("profileRoll")).put("effectInstanceId", "activation");
        ObjectNode result = HOST.execute(pack, sheet, origin, damage, "self", new SplittableRandom(2));
        assertTrue(sheet.requiredAt("/automationState/effects").isEmpty());
        assertFalse(result.required("event").has("resultId"));
        assertFalse(result.has("resultActions"));
        assertEquals(1, result.required("revision").intValue());
    }

    @Test
    void effectAttackUsesFrozenFactsAndRejectsInvalidSavedProfilesBeforeRolling() throws Exception {
        ObjectNode classes = fixture("rewrite/host-classes");
        ObjectNode automation = (ObjectNode) classes.at("/entries/0/features/2/automation");
        automation.putObject("effects").putObject("form").put("label", "Form").set("profile", RuleJson.parse("""
                {"label":"Catalog claw","trait":"Finesse","range":"far","damage":{"sides":6}}"""));
        var pack = AutomationHost.compile("test", Map.of("classes", classes.toString()), fixture("rewrite/recipes").toString());
        ObjectNode sheet = sheet(), origin = AutomationHost.event("action.request", "actor", 0);
        ObjectNode effect = HOST.projection().state(sheet).putArray("effects").addObject()
                .put("id", "activation").put("effect", "classes:tester:features:steady#form").put("sourceInstanceId", "f-steady");
        effect.set("profile", RuleJson.parse("""
                {"label":"Frozen claw","trait":"Agility","range":"melee","damage":{"sides":8,"type":"magic"}}"""));
        ObjectNode attack = profileRequest("attack", 0, "f-steady", "effect.profile", "attack");
        ((ObjectNode) attack.required("profileRoll")).put("effectInstanceId", "activation");
        JsonNode event = HOST.execute(pack, sheet, origin, attack, "self", new SplittableRandom(2)).required("event");
        assertEquals("Frozen claw", event.required("label").textValue());
        assertEquals("Agility", event.requiredAt("/check/trait").textValue());
        assertEquals("melee", event.requiredAt("/check/range").textValue());
        assertEquals(event.requiredAt("/check/hopeDie").intValue() + event.requiredAt("/check/fearDie").intValue() + 1,
                event.requiredAt("/check/total").intValue());

        ObjectNode stored = (ObjectNode) sheet.requiredAt("/automationState/effects/0/profile");
        stored.withObject("/damage").put("sides", 1);
        ObjectNode malformed = profileRequest("bad", 1, "f-steady", "effect.profile", "damage");
        ((ObjectNode) malformed.required("profileRoll")).put("effectInstanceId", "activation");
        ObjectNode before = sheet.deepCopy();
        assertThrows(IllegalArgumentException.class,
                () -> HOST.execute(pack, sheet, origin, malformed, "self", StructuredRollTest.noRandom()));
        assertThrows(IllegalArgumentException.class, () -> HOST.describe(pack, sheet, origin, "sheet", "self"));
        assertEquals(before, sheet);
    }

    @Test
    void derivedEffectProfilesFollowTheConditionAndDoNotReviveOldResults() throws Exception {
        ObjectNode classes = fixture("rewrite/host-classes");
        ObjectNode automation = (ObjectNode) classes.at("/entries/0/features/2/automation");
        ObjectNode form = automation.putObject("effects").putObject("form").put("label", "Form");
        form.set("while", RuleJson.parse("{\"gt\":[{\"read\":\"actor.hope.value\"},0]}"));
        form.set("profile", RuleJson.parse("""
                {"label":"Claws","trait":"Agility","range":"melee","damage":{"sides":8,"type":"physical"}}"""));
        automation.set("rules", RuleJson.parse("""
                [{"id":"off","bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button"}],
                  "costs":[{"op":"resource.spend","pool":"actor.hope","amount":1}],"do":[],"ui":{"label":"Off"}},
                 {"id":"on","bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button"}],
                  "do":[{"op":"resource.gain","pool":"actor.hope","amount":1}],"ui":{"label":"On"}},
                 {"id":"after","bindings":[{"id":"use","event":"roll.finished","surface":"roll","mode":"button"}],
                  "do":[{"op":"resource.gain","pool":"actor.hope","amount":1}],"ui":{"label":"After"}}]"""));
        var pack = AutomationHost.compile("test", Map.of("classes", classes.toString()), fixture("rewrite/recipes").toString());
        ObjectNode sheet = sheet(), origin = AutomationHost.event("action.request", "actor", 0).put("sessionId", "session");
        JsonNode profile = HOST.describe(pack, sheet, origin, "sheet", "self").requiredAt("/profiles/1");
        String effectId = profile.required("effectInstanceId").textValue();
        assertEquals("f-steady/form/derived", effectId);
        assertFalse(sheet.path("automationState").path("effects").isArray(), "Derived effects are not saved.");
        ObjectNode damage = profileRequest("damage", 0, "f-steady", "effect.profile", "damage");
        ((ObjectNode) damage.required("profileRoll")).put("effectInstanceId", effectId);
        ObjectNode result = HOST.execute(pack, sheet, origin, damage, "self", "owner", new SplittableRandom(3));
        String resultId = result.requiredAt("/event/resultId").textValue();
        assertEquals(effectId, result.requiredAt("/event/effectInstanceId").textValue());
        assertTrue(sheet.at("/automationState/effects").isMissingNode() || sheet.at("/automationState/effects").isEmpty());

        HOST.execute(pack, sheet, origin, request("off", 1, "f-steady", "off"), "self", "owner", null);
        assertEquals(1, HOST.describe(pack, sheet, origin, "sheet", "self").required("profiles").size());
        assertFalse(sheet.at("/automationState/resultWindows").isObject());
        ObjectNode staleRoll = profileRequest("stale", 2, "f-steady", "effect.profile", "attack");
        ((ObjectNode) staleRoll.required("profileRoll")).put("effectInstanceId", effectId);
        ObjectNode afterOff = sheet.deepCopy();
        assertThrows(RuleRuntime.Unavailable.class,
                () -> HOST.execute(pack, sheet, origin, staleRoll, "self", "owner", StructuredRollTest.noRandom()));
        assertEquals(afterOff, sheet);
        HOST.execute(pack, sheet, origin, request("on", 2, "f-steady", "on"), "self", "owner", null);
        assertEquals(effectId, HOST.describe(pack, sheet, origin, "sheet", "self").requiredAt("/profiles/1/effectInstanceId").textValue());
        assertThrows(RuleRuntime.Unavailable.class, () -> HOST.describeResult(pack, sheet, resultId, "self", "owner", origin));

        ObjectNode second = profileRequest("second", 3, "f-steady", "effect.profile", "attack");
        ((ObjectNode) second.required("profileRoll")).put("effectInstanceId", effectId);
        String secondId = HOST.execute(pack, sheet, origin, second, "self", "owner", new SplittableRandom(5))
                .requiredAt("/event/resultId").textValue();
        ((ObjectNode) sheet.required("hope")).put("on", 0);
        HOST.pruneResultWindows(pack, sheet, origin);
        ((ObjectNode) sheet.required("hope")).put("on", 1);
        assertThrows(RuleRuntime.Unavailable.class, () -> HOST.describeResult(pack, sheet, secondId, "self", "owner", origin));
    }

    private static ObjectNode profileRequest(String id, long revision, String instance, String profile, String kind) {
        ObjectNode request = RuleJson.JSON.createObjectNode().put("requestId", id).put("expectedRevision", revision);
        request.putArray("selections");
        request.putObject("profileRoll").put("sourceInstanceId", instance).put("profileId", profile).put("kind", kind);
        return request;
    }

    @Test
    void executesWritesBackJournalsAndRejectsStaleOrRepeatedUse() throws Exception {
        var pack = pack();
        ObjectNode sheet = sheet();
        ObjectNode response = HOST.execute(pack, sheet, action(), request("r1", 0, "f-rally", "rally"), "self", new SplittableRandom(1));
        assertEquals(4, sheet.path("hope").path("on").asInt(), "Rally +2 and the automatic Steady +1");
        assertEquals(1, response.path("revision").asLong());
        assertEquals(List.of("Steady.", "Ada rallied for 2 Hope."), response.path("actions").findValuesAsText("report"));
        assertEquals("countdown.start", response.path("outbox").get(0).path("kind").asText());
        assertEquals("Rally from Ada", response.path("outbox").get(0).path("label").asText());
        assertEquals(1, sheet.path("automationState").path("sources").path("f-rally").path("usage").path("once").asInt());

        ObjectNode replay = HOST.execute(pack, sheet, action(), request("r1", 0, "f-rally", "rally"), "self", new SplittableRandom(1));
        assertEquals(response, replay);
        assertEquals(4, sheet.path("hope").path("on").asInt(), "a repeated request ID must not run twice");
        assertThrows(RuleRuntime.Unavailable.class, () -> HOST.execute(pack, sheet, action(), request("r1", 0, "f-rally", "rally"),
                "self", "another-player", new SplittableRandom(1)));

        var stale = assertThrows(RuleRuntime.Unavailable.class,
                () -> HOST.execute(pack, sheet, action(), request("r2", 0, "f-rally", "rally"), "self", null));
        assertEquals("stale", stale.code());
        var spent = assertThrows(RuleRuntime.Unavailable.class,
                () -> HOST.execute(pack, sheet, action(), request("r3", 1, "f-rally", "rally"), "self", null));
        assertEquals("limit", spent.code());
        assertEquals(4, sheet.path("hope").path("on").asInt(), "failed requests leave the sheet unchanged");

        assertTrue(HOST.resetLimits(pack, sheet, "rest.short"));
        assertEquals(2, HOST.revision(sheet));
        assertFalse(HOST.resetLimits(pack, sheet, "rest.short"));
        HOST.execute(pack, sheet, action(), request("r4", 2, "f-rally", "rally"), "other", null);
        assertEquals(6, sheet.path("hope").path("on").asInt(), "Hope is capped by its slots");
    }

    @Test
    void retriesBindTheIntentWhileAcceptingReorderedObjectsAndOldReceipts() throws Exception {
        var pack = pack();
        ObjectNode sheet = sheet();
        ObjectNode original = request("same", 0, "f-rally", "rally");
        ObjectNode response = HOST.execute(pack, sheet, action(), original, "self", "owner", new SplittableRandom(1));
        ObjectNode saved = sheet.deepCopy();
        ObjectNode reordered = (ObjectNode) RuleJson.parse("""
                {"selections":[{"bindingId":"sheet","ruleId":"rally","sourceInstanceId":"f-rally"}],
                 "expectedRevision":0,"requestId":"same"}""");
        assertEquals(response, HOST.execute(pack, sheet, action(), reordered, "self", "owner", null));
        ObjectNode altered = request("same", 0, "f-forage", "forage");
        var error = assertThrows(RuleRuntime.Unavailable.class,
                () -> HOST.execute(pack, sheet, action(), altered, "self", "owner", null));
        assertEquals("requestId", error.code());
        assertEquals(saved, sheet);
        assertThrows(RuleRuntime.Unavailable.class,
                () -> HOST.execute(pack, sheet, action(), original, "self", "another", null));

        ((ObjectNode) sheet.at("/automationState/journal/0")).remove("intentHash");
        assertEquals(response, HOST.execute(pack, sheet, action(), original, "self", "owner", null),
                "Existing saved receipts remain replayable.");
    }

    @Test
    void refreshRequestsClearOtherSourcesRestLimitsButNotPools() throws Exception {
        String tea = LegacyCatalogConverter.convert("""
                {"format":"dmmap-daggerheart","version":1,"category":"consumables","label":"Fixture","entries":[
                  {"id":"minor-health-potion","name":"Tea","automation":{"actions":[{"id":"drink","label":"Drink","refreshUses":"short"}]}}]}""")
                .catalog().toString();
        var pack = AutomationHost.compile("test", Map.of("classes", fixture("rewrite/host-classes").toString(), "consumables", tea),
                fixture("rewrite/recipes").toString());
        ObjectNode sheet = sheet();
        HOST.execute(pack, sheet, action(), request("r1", 0, "f-rally", "rally"), "self", new SplittableRandom(1));
        assertEquals(1, sheet.at("/automationState/sources/f-rally/usage/once").asInt());
        ObjectNode response = HOST.execute(pack, sheet, action(), request("t1", 1, "i-potion", "drink"), "self", null);
        assertEquals("limits.refresh", response.at("/outbox/0/kind").asText(), response.toString());
        assertEquals(0, sheet.at("/automationState/sources/f-rally/usage/once").asInt(), sheet.path("automationState").toString());
        assertEquals(List.of("Rope"), sheet.path("inventory").findValuesAsText("name"), "The tea is used up.");
    }

    @Test
    void writesTracksCollectionsAndConsumedSourcesThroughTheProjection() throws Exception {
        var pack = pack();
        ObjectNode sheet = sheet();
        assertThrows(SecurityException.class,
                () -> HOST.execute(pack, sheet, action(), request("x", 0, "f-forage", "forage"), "other", null));
        HOST.execute(pack, sheet, action(), request("f1", 0, "f-forage", "forage"), "self", null);
        assertEquals(2, sheet.path("stress").path("on").asInt());
        assertEquals(7, sheet.path("hp").path("slots").asInt());
        assertEquals(12, sheet.path("hp").path("total").asInt());
        var berries = sheet.path("inventory").get(2);
        assertEquals("Berries", berries.path("name").asText());
        assertEquals(2, berries.path("qty").asInt());
        assertEquals("loot", berries.path("type").asText());
        assertEquals("f-forage", berries.path("automationGrantedBy").asText());
        assertEquals("Rope", sheet.path("inventory").get(1).path("name").asText());

        HOST.execute(pack, sheet, action(), request("p1", 1, "i-potion", "drink"), "self", new SplittableRandom(3));
        assertTrue(sheet.path("hp").path("on").asInt() < 2);
        assertEquals(List.of("Rope", "Berries"), sheet.path("inventory").findValuesAsText("name"));
        assertFalse(sheet.path("automationState").path("sources").has("i-potion"));
    }

    @Test
    void projectionRejectsWritesToReadOnlyOrOutOfBoundsFacts() throws Exception {
        ObjectNode sheet = sheet();
        ((ObjectNode) sheet.path("traits").get(0)).put("spellcast", true);
        ObjectNode original = sheet.deepCopy();
        SheetProjection projection = HOST.projection();
        ObjectNode before = projection.actor(sheet);
        assertEquals(1, before.path("traits").path("Agility").asInt());
        assertEquals("Agility", before.at("/spellcastTrait/name").asText());
        assertEquals(1, before.at("/spellcastTrait/value").asInt());
        assertEquals(6, before.path("hope").path("max").asInt());
        ObjectNode level = before.deepCopy().put("level", 9);
        assertThrows(IllegalArgumentException.class, () -> projection.write(sheet, before, level));
        ObjectNode spellcast = before.deepCopy();
        ((ObjectNode) spellcast.get("spellcastTrait")).put("value", 2);
        assertThrows(IllegalArgumentException.class, () -> projection.write(sheet, before, spellcast));
        ObjectNode hopeMax = before.deepCopy();
        ((ObjectNode) hopeMax.get("hope")).put("max", 9);
        assertThrows(IllegalArgumentException.class, () -> projection.write(sheet, before, hopeMax));
        ObjectNode gold = before.deepCopy().put("gold", -1);
        assertThrows(IllegalArgumentException.class, () -> projection.write(sheet, before, gold));
        assertEquals(original, sheet);
    }

    @Test
    void projectionDerivesBoundedStatisticsAndAppliesMatchingModifiers() throws Exception {
        ObjectNode sheet = sheet().put("level", 5);
        ((ObjectNode) sheet.path("traits").get(0)).put("spellcast", true);
        ((ObjectNode) sheet.get("proficiency")).put("on", 0);
        SheetProjection projection = HOST.projection();
        ObjectNode actor = projection.actor(sheet);
        ObjectNode base = projection.stats(actor, List.of());
        assertEquals(3, base.path("tier").asInt());
        assertEquals(1, base.at("/trait/Agility").asInt());
        assertEquals(0, base.at("/trait/Knowledge").asInt());
        assertEquals(1, base.at("/spellcast/total").asInt());
        assertEquals(1, base.path("proficiency").asInt(), "Proficiency is never below 1");
        assertEquals(10, base.path("evasion").asInt());
        assertEquals(5, base.at("/thresholds/major").asInt());
        assertEquals(10, base.at("/thresholds/severe").asInt());
        assertFalse(actor.has("stats"), "Statistics are derived on a copy");

        List<com.fasterxml.jackson.databind.JsonNode> modifiers = List.of(
                RuleJson.parse("{\"stat\":\"trait.Agility\",\"value\":2}"),
                RuleJson.parse("{\"stat\":\"evasion\",\"op\":\"add\",\"value\":1}"),
                RuleJson.parse("{\"stat\":\"proficiency\",\"op\":\"atMost\",\"value\":-3}"));
        ObjectNode modified = projection.stats(actor, modifiers);
        assertEquals(3, modified.at("/trait/Agility").asInt());
        assertEquals(3, modified.at("/spellcast/total").asInt(), "Later statistics read modified earlier ones");
        assertEquals(1, modified.at("/spellcast/base").asInt());
        assertEquals(11, modified.path("evasion").asInt());
        assertEquals(1, modified.path("proficiency").asInt(), "Declared bounds apply after modifiers");
    }

    @Test
    void projectionRejectsReservedOrForeignStatistics() throws Exception {
        String bundled;
        try (var in = SheetProjection.class.getResourceAsStream("daggerheart-projection.json")) {
            bundled = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        ObjectNode reserved = (ObjectNode) RuleJson.parse(bundled);
        ((ObjectNode) reserved.get("actor")).putObject("stats").put("kind", "value").put("path", "level");
        assertThrows(IllegalArgumentException.class, () -> SheetProjection.parse(reserved.toString()));
        ObjectNode foreign = (ObjectNode) RuleJson.parse(bundled);
        ((com.fasterxml.jackson.databind.node.ArrayNode) foreign.get("stats")).addObject().put("id", "leak")
                .putObject("value").put("read", "event.total");
        assertThrows(IllegalArgumentException.class, () -> SheetProjection.parse(foreign.toString()));
        ObjectNode grouped = (ObjectNode) RuleJson.parse(bundled);
        ((com.fasterxml.jackson.databind.node.ArrayNode) grouped.get("stats")).addObject().put("id", "trait").put("value", 1);
        assertThrows(IllegalArgumentException.class, () -> SheetProjection.parse(grouped.toString()));
    }

    @Test
    void timedExpiryClearsOnlyPoolsWhoseLastSupportingEffectEnded() throws Exception {
        ObjectNode catalog = fixture("rewrite/host-classes");
        ObjectNode automation = (ObjectNode) catalog.at("/entries/0/features/2/automation");
        automation.putObject("effects").putObject("stance").put("label", "Stance");
        automation.putObject("pools").putObject("charge").put("kind", "number").put("scope", "actor")
                .put("whileEffect", "stance").put("max", 4);
        var pack = AutomationHost.compile("test", Map.of("classes", catalog.toString()), fixture("rewrite/recipes").toString());
        ObjectNode sheet = sheet();
        ObjectNode state = HOST.projection().state(sheet);
        ObjectNode pools = state.putObject("pools");
        pools.putObject("charge").put("value", 3).put("max", 4);
        pools.putObject("other").put("value", 2);
        var effects = state.putArray("effects");
        for (String end : List.of("scene.end", "session.start"))
            effects.addObject().put("id", end).put("effect", "classes:tester:features:steady#stance")
                    .put("sourceInstanceId", "f-steady").putArray("until").add(end);
        assertTrue(HOST.resetLimits(pack, sheet, "scene.end"));
        assertEquals(3, sheet.at("/automationState/pools/charge/value").intValue());
        assertTrue(HOST.resetLimits(pack, sheet, "session.start"));
        assertEquals(0, sheet.at("/automationState/pools/charge/value").intValue());
        assertEquals(2, sheet.at("/automationState/pools/other/value").intValue());
        assertFalse(HOST.resetLimits(pack, sheet, "session.start"));
    }

    @Test
    void duplicateInstancesAndLegacyPacksAreRejected() throws Exception {
        ObjectNode sheet = sheet();
        ((ObjectNode) sheet.path("features").get(1)).put("instanceId", "f-rally");
        assertThrows(IllegalArgumentException.class, () -> HOST.describe(pack(), sheet, action(), "sheet", "self"));
        ObjectNode legacy = fixture("rewrite/consumables").put("version", 1);
        assertThrows(AutomationHost.NotReady.class, () -> AutomationHost.compile("x",
                Map.of("consumables", legacy.toString()), fixture("rewrite/recipes").toString()));
        assertThrows(AutomationHost.NotReady.class, () -> AutomationHost.compile("x", Map.of(), null));
    }
}
