package dmmt.online.automation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static dmmt.online.automation.RuleConformanceTest.fixture;
import static org.junit.jupiter.api.Assertions.*;

class RuleCatalogTest {
    @Test
    void candidateConfigsCompileWithStableDefinitionIds() throws Exception {
        var sources = RuleCatalog.compile(fixture("rewrite/domain-cards").toString(), fixture("rewrite/recipes").toString());
        assertEquals(List.of("domain-cards:deathrun", "domain-cards:splinter-trail"), sources.stream().map(RuleCatalog.Source::id).toList());
        assertTrue(sources.get(0).program().ruleIds().contains("run.continue"));
        assertTrue(sources.get(1).program().ruleIds().contains("trail.continue"));
        assertEquals(1, RuleCatalog.compile(fixture("rewrite/consumables").toString(), fixture("rewrite/recipes").toString()).size());
    }

    @Test
    void unusedRecipesAreCheckedEvenWhenTheCatalogHasNoAutomation() throws Exception {
        String catalog = """
                {"format":"dmmap-daggerheart","version":2,"category":"items","entries":[]}
                """;
        ObjectNode recipes = fixture("rewrite/recipes");
        assertTrue(RuleCatalog.compile(catalog, recipes.toString()).isEmpty());

        ObjectNode unknownArgument = recipes.deepCopy();
        ((ObjectNode) unknownArgument.at("/recipes/0/rules/0/costs")).put("$arg", "missing");
        var error = assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(catalog, unknownArgument.toString()));
        assertTrue(error.getMessage().contains("/recipes/core:repeat-saved-weapon-damage/rules/0/costs"), error.getMessage());
        assertTrue(error.getMessage().contains("missing"), error.getMessage());

        ObjectNode unknownText = recipes.deepCopy();
        ((ObjectNode) unknownText.at("/recipes/0/rules/0/ui/label")).put("$ui", "missing");
        assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(catalog, unknownText.toString()));
        ObjectNode malformedSubstitution = recipes.deepCopy();
        ((ObjectNode) malformedSubstitution.at("/recipes/0/rules/0/costs")).put("extra", true);
        assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(catalog, malformedSubstitution.toString()));

        ObjectNode invalidRange = recipes.deepCopy();
        ((ObjectNode) invalidRange.at("/recipes/0/args/removeEachTime")).put("min", 5).put("max", 2);
        assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(catalog, invalidRange.toString()));
        ObjectNode invalidDefault = recipes.deepCopy();
        ((ObjectNode) invalidDefault.at("/recipes/0/args/removeEachTime")).put("default", 100);
        assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(catalog, invalidDefault.toString()));
        ObjectNode invalidType = recipes.deepCopy();
        ((ObjectNode) invalidType.at("/recipes/0/args/startCosts")).put("type", "mystery");
        assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(catalog, invalidType.toString()));
        ObjectNode invalidCost = recipes.deepCopy();
        ((ObjectNode) invalidCost.at("/recipes/0/args/startCosts")).putArray("default")
                .addObject().put("op", "countdown.start").put("label", "Not a cost").put("value", 1);
        assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(catalog, invalidCost.toString()));
        ObjectNode invalidText = recipes.deepCopy();
        ((ObjectNode) invalidText.at("/recipes/0/ui")).put("start", "{invalid");
        assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(catalog, invalidText.toString()));
    }

    @Test
    void unusedRecipesRejectStaticRuleAndOperationShapeErrors() throws Exception {
        String catalog = """
                {"format":"dmmap-daggerheart","version":2,"category":"items","entries":[]}
                """;
        ObjectNode recipes = fixture("rewrite/recipes");
        ObjectNode invalidRule = recipes.deepCopy();
        ((ObjectNode) invalidRule.at("/recipes/0/rules/0")).put("unexpected", true);
        assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(catalog, invalidRule.toString()));
        ObjectNode invalidBinding = recipes.deepCopy();
        ((ObjectNode) invalidBinding.at("/recipes/0/rules/0/bindings/0")).put("unknown", true);
        assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(catalog, invalidBinding.toString()));
        ObjectNode invalidUi = recipes.deepCopy();
        ((ObjectNode) invalidUi.at("/recipes/0/rules/0/ui")).put("unknown", "bad");
        assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(catalog, invalidUi.toString()));
        ObjectNode invalidOp = recipes.deepCopy();
        ((ObjectNode) invalidOp.at("/recipes/0/rules/0/do/0")).put("op", "not.an.operation");
        var error = assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(catalog, invalidOp.toString()));
        assertTrue(error.getMessage().contains("/recipes/core:repeat-saved-weapon-damage/rules/0/do/0/op"), error.getMessage());
        ObjectNode missingField = recipes.deepCopy();
        ((ObjectNode) missingField.at("/recipes/0/rules/0/do/0")).remove("slot");
        assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(catalog, missingField.toString()));
        ObjectNode badField = recipes.deepCopy();
        ((ObjectNode) badField.at("/recipes/0/rules/0/do/0")).put("unknown", 1);
        assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(catalog, badField.toString()));
        ObjectNode badCost = recipes.deepCopy();
        ((ObjectNode) badCost.at("/recipes/0/rules/0")).putArray("costs")
                .addObject().put("op", "countdown.start").put("label", "Bad").put("value", 1);
        assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(catalog, badCost.toString()));
        ObjectNode duplicate = recipes.deepCopy();
        ((ObjectNode) duplicate.at("/recipes/0/rules/1/do/1")).put("as", "effectiveProficiency");
        assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(catalog, duplicate.toString()));
        assertTrue(RuleCatalog.compile(catalog, recipes.toString()).isEmpty(), "Substitutions remain valid without a source.");
    }

    @Test
    void unusedRecipesValidateConcreteBindingsAndDeferSubstitutedValues() throws Exception {
        String catalog = """
                {"format":"dmmap-daggerheart","version":2,"category":"items","entries":[]}
                """;
        ObjectNode recipes = fixture("rewrite/recipes");
        for (String field : List.of("event", "surface", "mode", "audience", "group")) {
            ObjectNode bad = recipes.deepCopy();
            ((ObjectNode) bad.at("/recipes/0/rules/0/bindings/0")).put(field, "invalid");
            var error = assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(catalog, bad.toString()));
            assertTrue(error.getMessage().contains("/bindings/0/" + field), error.getMessage());
        }
        ObjectNode badId = recipes.deepCopy();
        ((ObjectNode) badId.at("/recipes/0/rules/0/bindings/0")).put("id", "invalid id");
        assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(catalog, badId.toString()));
        ObjectNode duplicate = recipes.deepCopy();
        var bindings = (com.fasterxml.jackson.databind.node.ArrayNode) duplicate.at("/recipes/0/rules/0/bindings");
        bindings.add(bindings.get(0).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(catalog, duplicate.toString()));
        ObjectNode badPriority = recipes.deepCopy();
        ((ObjectNode) badPriority.at("/recipes/0/rules/0/bindings/0")).put("priority", 1001);
        assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(catalog, badPriority.toString()));
        ObjectNode badDefault = recipes.deepCopy();
        ((ObjectNode) badDefault.at("/recipes/0/rules/0/bindings/0")).put("defaultOn", true).put("mode", "button");
        assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(catalog, badDefault.toString()));
        ObjectNode badCondition = recipes.deepCopy();
        ((ObjectNode) badCondition.at("/recipes/0/rules/0/bindings/0")).putObject("when").put("unknown", true);
        assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(catalog, badCondition.toString()));
        ObjectNode badUi = recipes.deepCopy();
        ((ObjectNode) badUi.at("/recipes/0/rules/0/ui")).put("description", "{invalid");
        assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(catalog, badUi.toString()));

        ObjectNode dynamic = recipes.deepCopy();
        ((ObjectNode) dynamic.at("/recipes/0/args")).putObject("bindingMode").put("type", "string").put("default", "button");
        ((ObjectNode) dynamic.at("/recipes/0/rules/0/bindings/0")).putObject("mode").put("$arg", "bindingMode");
        assertTrue(RuleCatalog.compile(catalog, dynamic.toString()).isEmpty());
    }

    @Test
    void unusedRecipesValidateTargetAndClaimDeclarations() throws Exception {
        String catalog = """
                {"format":"dmmap-daggerheart","version":2,"category":"items","entries":[]}
                """;
        ObjectNode recipes = fixture("rewrite/recipes");
        String bindingPath = "/recipes/0/rules/0/bindings/0";
        for (String invalid : List.of(
                """
                {"targetSelection":{"cardinality":"several","scope":"activeCharacters"}}""",
                """
                {"targetSelection":{"cardinality":"one","scope":"unknown"}}""",
                """
                {"targetSelection":{"cardinality":"one","scope":"activeCharacters","excludeSource":"yes"}}""",
                """
                {"targetSelection":{"cardinality":"one","scope":"activeCharacters","where":{"unknown":true}}}""",
                """
                {"targetSelection":{"cardinality":"one","scope":"activeCharacters","extra":true}}""",
                """
                {"claimScope":{"recipients":"sourceActor"}}""",
                """
                {"claimGroup":"claim","claimScope":{"recipients":"unknown"}}""",
                """
                {"claimGroup":"claim","claimScope":{"recipients":"sourceActor","excludeSource":true}}""",
                """
                {"claimGroup":"claim","claimScope":{"recipients":"playerCharacters","excludeSource":"yes"}}""",
                """
                {"claimGroup":"claim","targetSelection":{"cardinality":"one","scope":"activeCharacters"}}""",
                """
                {"exclusiveGroup":"invalid group"}""",
                """
                {"mode":"automatic","claimGroup":"claim"}""",
                """
                {"mode":"option","forEach":"dice"}""",
                """
                {"event":"rest.long","targetSelection":{"cardinality":"one","scope":"activeCharacters"}}"""
        )) {
            ObjectNode bad = recipes.deepCopy();
            ((ObjectNode) bad.at(bindingPath)).setAll((ObjectNode) RuleJson.parse(invalid));
            var error = assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(catalog, bad.toString()), invalid);
            assertTrue(error.getMessage().contains("/rules/0/bindings/0"), error.getMessage());
        }

        ObjectNode dynamic = recipes.deepCopy();
        ((ObjectNode) dynamic.at("/recipes/0/args")).putObject("targetScope").put("type", "string").put("default", "unknown");
        ObjectNode target = ((ObjectNode) dynamic.at(bindingPath)).putObject("targetSelection");
        target.put("cardinality", "one").putObject("scope").put("$arg", "targetScope");
        assertTrue(RuleCatalog.compile(catalog, dynamic.toString()).isEmpty());
        var error = assertThrows(IllegalArgumentException.class,
                () -> RuleCatalog.compile(fixture("rewrite/domain-cards").toString(), dynamic.toString()));
        assertTrue(error.getMessage().contains("/targetSelection/scope"), error.getMessage());

        ObjectNode conflicting = recipes.deepCopy();
        ObjectNode binding = (ObjectNode) conflicting.at(bindingPath);
        binding.put("claimGroup", "claim").putObject("targetSelection").put("$arg", "dieRole");
        assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(catalog, conflicting.toString()));
        ObjectNode missingGroup = recipes.deepCopy();
        ((ObjectNode) missingGroup.at(bindingPath)).putObject("claimScope").put("$arg", "dieRole");
        assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(catalog, missingGroup.toString()));
        ObjectNode impossible = recipes.deepCopy();
        binding = (ObjectNode) impossible.at(bindingPath);
        binding.put("event", "rest.long").putObject("surface").put("$arg", "dieRole");
        binding.putObject("targetSelection").put("cardinality", "one").put("scope", "activeCharacters");
        assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(catalog, impossible.toString()));
    }

    @Test
    void unusedRecipesValidateConcreteInputsAndDeferParameterizedInputs() throws Exception {
        String catalog = """
                {"format":"dmmap-daggerheart","version":2,"category":"items","entries":[]}
                """;
        ObjectNode recipes = fixture("rewrite/recipes");
        for (String invalid : List.of(
                """
                [{"id":"amount","kind":"unknown","label":"Amount"}]""",
                """
                [{"id":"bad id","kind":"integer","label":"Amount","min":0,"max":2}]""",
                """
                [{"id":"amount","kind":"integer","label":"{invalid","min":0,"max":2}]""",
                """
                [{"id":"amount","kind":"integer","label":"Amount","min":{"unknown":true},"max":2}]""",
                """
                [{"id":"amount","kind":"integer","label":"Amount","min":0,"max":2,"default":"one"}]""",
                """
                [{"id":"die","kind":"die.select","label":"Die","from":[],"count":1,"choiceLabel":"Die"}]""",
                """
                [{"id":"choice","kind":"choice","label":"Choice","options":[{"id":"a","label":"A"},{"id":"a","label":"B"}]}]""",
                """
                [{"id":"allocation","kind":"allocation","label":"Allocation","options":[{"id":"a","label":"A"}],"total":{"bad":1}}]""",
                """
                [{"id":"same","kind":"integer","label":"A","min":0,"max":1},
                 {"id":"same","kind":"integer","label":"B","min":0,"max":1}]"""
        )) {
            ObjectNode bad = recipes.deepCopy();
            ((ObjectNode) bad.at("/recipes/0/rules/0")).set("inputs", RuleJson.parse(invalid));
            assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(catalog, bad.toString()), invalid);
        }

        ObjectNode tooMany = recipes.deepCopy();
        var manyInputs = ((ObjectNode) tooMany.at("/recipes/0/rules/0")).putArray("inputs");
        for (int i = 0; i < 17; i++)
            manyInputs.addObject().put("id", "amount" + i).put("kind", "integer").put("label", "Amount").put("min", 0).put("max", 2);
        assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(catalog, tooMany.toString()));

        ObjectNode automatic = recipes.deepCopy();
        ((ObjectNode) automatic.at("/recipes/0/rules/0/bindings/0")).put("mode", "automatic");
        ((ObjectNode) automatic.at("/recipes/0/rules/0")).set("inputs",
                RuleJson.parse("[{\"id\":\"amount\",\"kind\":\"integer\",\"label\":\"Amount\",\"min\":0,\"max\":2}]"));
        assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(catalog, automatic.toString()));
        ObjectNode automaticCosts = recipes.deepCopy();
        ((ObjectNode) automaticCosts.at("/recipes/0/rules/0/bindings/0")).put("mode", "automatic");
        ((ObjectNode) automaticCosts.at("/recipes/0/rules/0")).putArray("costs")
                .addObject().put("op", "resource.spend").put("pool", "actor.hope").put("amount", 1);
        assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(catalog, automaticCosts.toString()));

        ObjectNode dynamic = recipes.deepCopy();
        ((ObjectNode) dynamic.at("/recipes/0/args")).putObject("inputKind").put("type", "string").put("default", "unknown");
        ((ObjectNode) dynamic.at("/recipes/0/rules/0")).set("inputs",
                RuleJson.parse("[{\"id\":\"amount\",\"kind\":{\"$arg\":\"inputKind\"},\"label\":\"Amount\",\"min\":0,\"max\":2}]"));
        assertTrue(RuleCatalog.compile(catalog, dynamic.toString()).isEmpty());
        var error = assertThrows(IllegalArgumentException.class,
                () -> RuleCatalog.compile(fixture("rewrite/domain-cards").toString(), dynamic.toString()));
        assertTrue(error.getMessage().contains("/inputs/kind"), error.getMessage());
        ((ObjectNode) dynamic.at("/recipes/0/args/inputKind")).put("default", "integer");
        assertFalse(RuleCatalog.compile(fixture("rewrite/domain-cards").toString(), dynamic.toString()).isEmpty());
    }

    @Test
    void compiledProgramsIndexUserBindingsByEventAndSurface() throws Exception {
        var source = RuleCatalog.compile(fixture("rewrite/host-classes").toString(), fixture("rewrite/recipes").toString())
                .stream().filter(item -> item.id().equals("classes:tester:features:rally")).findFirst().orElseThrow();
        assertEquals(List.of(new RuleCompiler.Program.BindingRef("rally", "sheet", false, "party", false)),
                source.program().bindings("action.request", "sheet"));
        assertTrue(source.program().bindings("rest.long", "sheet").isEmpty());
        assertTrue(source.program().bindings("action.request", "downtime").isEmpty());
        var steady = RuleCatalog.compile(fixture("rewrite/host-classes").toString(), fixture("rewrite/recipes").toString())
                .stream().filter(item -> item.id().equals("classes:tester:features:steady")).findFirst().orElseThrow().program();
        assertEquals(List.of(new RuleCompiler.Program.AutomaticBinding("steady", "auto", 0)), steady.automatic("action.request"));
        assertTrue(steady.automatic("roll.finished").isEmpty());
        assertTrue(steady.bindings("action.request", "sheet").isEmpty());
    }

    @Test
    void nestedFeaturesAndSpellsRequireRealIdsRatherThanArrayPositionsOrNames() throws Exception {
        ObjectNode catalog = fixture("rewrite/consumables");
        ObjectNode entry = (ObjectNode) catalog.path("entries").get(0);
        ObjectNode feature = entry.putArray("features").addObject().put("id", "nested").put("name", "Nested");
        feature.set("automation", entry.remove("automation"));
        var sources = RuleCatalog.compile(catalog.toString(), fixture("rewrite/recipes").toString());
        assertEquals("consumables:minor-health-potion:features:nested", sources.getFirst().id());
        feature.put("name", "Renamed without re-keying the state");
        assertEquals(sources.getFirst().id(), RuleCatalog.compile(catalog.toString(), fixture("rewrite/recipes").toString()).getFirst().id());
        feature.remove("id");
        assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(catalog.toString(), fixture("rewrite/recipes").toString()));
    }

    @Test
    void catalogErrorsIdentifyTheOwningEntryAndNeverAcceptMixedVersionPacks() throws Exception {
        ObjectNode catalog = fixture("rewrite/domain-cards");
        ((ObjectNode) catalog.path("entries").get(1).path("automation")).put("specialAction", "old-handler");
        var error = assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(catalog.toString(), fixture("rewrite/recipes").toString()));
        assertTrue(error.getMessage().contains("/entries/1/automation"));
        assertTrue(error.getMessage().contains("domain-cards:splinter-trail"));
        assertTrue(error.getMessage().contains("specialAction"));
        var files = new HashMap<String, String>();
        for (String category : List.of("ancestries", "armor", "classes", "communities", "consumables", "domain-cards", "items", "transformations", "weapons"))
            files.put(category, "{\"format\":\"dmmap-daggerheart\",\"version\":2,\"category\":\"" + category + "\",\"entries\":[]}");
        files.put("consumables", fixture("rewrite/consumables").toString());
        assertEquals(9, RuleCatalog.compilePack(files, fixture("rewrite/recipes").toString()).size());
        files.put("items", "{\"format\":\"dmmap-daggerheart\",\"version\":1,\"category\":\"items\",\"entries\":[]}");
        assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compilePack(files, fixture("rewrite/recipes").toString()));
        files.remove("items");
        assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compilePack(files, fixture("rewrite/recipes").toString()));
    }

    @Test
    void arbitrarilyNestedAutomationIsCompiledAndEditorBaselinesAreNotExecuted() throws Exception {
        ObjectNode catalog = fixture("rewrite/consumables");
        ObjectNode entry = (ObjectNode) catalog.path("entries").get(0);
        entry.putObject("_editorOriginal").putObject("automation").put("legacyFlag", true);
        ObjectNode nested = entry.putObject("custom").putArray("attachments").addObject().put("id", "charm").put("name", "Charm");
        nested.set("automation", entry.remove("automation"));
        var sources = RuleCatalog.compile(catalog.toString(), fixture("rewrite/recipes").toString());
        assertEquals("consumables:minor-health-potion:custom:attachments:charm", sources.getFirst().id());
        ((ObjectNode) nested.path("automation")).put("unsupported", true);
        assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(catalog.toString(), fixture("rewrite/recipes").toString()));
    }

    @Test
    void catalogSizeIsSeparateFromPerProgramComplexityAndComposedIdsMustBeUnique() throws Exception {
        ObjectNode catalog = fixture("rewrite/consumables");
        var entries = catalog.withArray("entries");
        ObjectNode original = (ObjectNode) entries.get(0);
        for (int i = 0; i < 400; i++) entries.add(original.deepCopy().put("id", "item-" + i));
        assertEquals(401, RuleCatalog.compile(catalog.toString(), fixture("rewrite/recipes").toString()).size());
        entries.removeAll();
        entries.add(original);
        ObjectNode child = original.putArray("features").addObject().put("id", "nested").put("name", "Nested");
        child.set("automation", original.path("automation").deepCopy());
        entries.addObject().put("id", "minor-health-potion:features:nested").put("name", "Duplicate")
                .set("automation", child.path("automation").deepCopy());
        assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(catalog.toString(), fixture("rewrite/recipes").toString()));
    }
}
