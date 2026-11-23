package dmmt.online.automation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static dmmt.online.automation.RuleConformanceTest.fixture;
import static org.junit.jupiter.api.Assertions.*;

class RuleCatalogTest {
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "dmmt.benchmark.automation", matches = "true")
    void measureColdPackCompilation() throws Exception {
        var files = new java.util.LinkedHashMap<String, String>();
        for (String category : RuleCatalog.CATEGORIES.stream().sorted().toList())
            files.put(category, "{\"format\":\"dmmap-daggerheart\",\"version\":2,\"category\":\"" + category + "\",\"entries\":[]}");
        files.put("classes", fixture("rewrite/host-classes").toString());
        files.put("consumables", fixture("rewrite/consumables").toString());
        files.put("domain-cards", fixture("rewrite/domain-cards").toString());
        ObjectNode recipes = fixture("rewrite/recipes");
        var list = recipes.withArray("recipes");
        ObjectNode template = ((ObjectNode) list.get(0)).deepCopy();
        for (int i = 0; i < 7; i++) list.add(template.deepCopy().put("id", "example:recipe-" + i));
        String recipeText = recipes.toString();
        var allocation = java.lang.management.ManagementFactory.getPlatformMXBean(com.sun.management.ThreadMXBean.class);
        if (allocation == null || !allocation.isThreadAllocatedMemorySupported())
            throw new IllegalStateException("The benchmark requires JVM allocation counters.");
        allocation.setThreadAllocatedMemoryEnabled(true);
        long[] nanos = new long[20], bytes = new long[20];
        long thread = Thread.currentThread().threadId();
        for (int i = -10; i < nanos.length; i++) {
            long before = allocation.getThreadAllocatedBytes(thread), start = System.nanoTime();
            var pack = AutomationHost.compile("benchmark", files, recipeText);
            long elapsed = System.nanoTime() - start, allocated = allocation.getThreadAllocatedBytes(thread) - before;
            assertFalse(pack.programs().isEmpty());
            if (i >= 0) { nanos[i] = elapsed; bytes[i] = allocated; }
        }
        java.util.Arrays.sort(nanos);
        System.out.printf(java.util.Locale.ROOT,
                "automation-bench cold pack: n=%d p50=%.2fms p95=%.2fms mean=%.2fMiB allocated%n",
                nanos.length, nanos[nanos.length / 2 - 1] / 1e6, nanos[(int) Math.ceil(nanos.length * .95) - 1] / 1e6,
                java.util.Arrays.stream(bytes).average().orElseThrow() / (1024 * 1024));
    }

    @Test
    void candidateConfigsCompileWithStableDefinitionIds() throws Exception {
        var sources = RuleCatalog.compile(fixture("rewrite/domain-cards").toString(), fixture("rewrite/recipes").toString());
        assertEquals(List.of("domain-cards:deathrun", "domain-cards:splinter-trail"), sources.stream().map(RuleCatalog.Source::id).toList());
        assertTrue(sources.get(0).program().ruleIds().contains("run.continue"));
        assertTrue(sources.get(1).program().ruleIds().contains("trail.continue"));
        assertEquals(1, RuleCatalog.compile(fixture("rewrite/consumables").toString(), fixture("rewrite/recipes").toString()).size());
    }

    @Test
    void packCompilationReusesRecipesWithoutCrossCatalogMutation() throws Exception {
        ObjectNode cards = fixture("rewrite/domain-cards");
        ObjectNode items = cards.deepCopy().put("category", "items");
        String recipes = fixture("rewrite/recipes").toString();
        var files = new java.util.LinkedHashMap<String, String>();
        files.put("domain-cards", cards.toString());
        files.put("items", items.toString());
        var pack = AutomationHost.compile("test", files, recipes);
        for (var entry : files.entrySet()) {
            for (var source : RuleCatalog.compile(entry.getValue(), recipes)) {
                var compiled = pack.programs().get(source.id());
                assertNotNull(compiled);
                assertEquals(source.program().ruleIds(), compiled.ruleIds());
                for (String id : compiled.ruleIds())
                    assertEquals(source.program().rule(id), compiled.rule(id), source.id() + "/" + id);
            }
        }
        ObjectNode invalid = fixture("rewrite/recipes");
        ((ObjectNode) invalid.at("/recipes/0/args/startCosts")).put("type", "invalid");
        assertThrows(AutomationHost.NotReady.class, () -> AutomationHost.compile("test", java.util.Map.of(), invalid.toString()));
        assertThrows(AutomationHost.NotReady.class, () -> AutomationHost.compile("test", files, invalid.toString()));
    }

    @Test
    void hostRejectsCatalogsWhoseCategoryDoesNotMatchTheFile() throws Exception {
        String recipes = fixture("rewrite/recipes").toString();
        String classes = fixture("rewrite/host-classes").toString();
        assertDoesNotThrow(() -> AutomationHost.compile("test", java.util.Map.of("classes", classes), recipes));
        var mismatch = assertThrows(AutomationHost.NotReady.class,
                () -> AutomationHost.compile("test", java.util.Map.of("items", classes), recipes));
        assertTrue(mismatch.getMessage().contains("items"), mismatch.getMessage());
        assertTrue(mismatch.getMessage().contains("does not match"), mismatch.getMessage());
        String empty = "{\"format\":\"dmmap-daggerheart\",\"version\":2,\"category\":\"items\",\"entries\":[]}";
        assertThrows(AutomationHost.NotReady.class,
                () -> AutomationHost.compile("test", java.util.Map.of("classes", empty), recipes));
        assertThrows(AutomationHost.NotReady.class,
                () -> AutomationHost.compile("test", java.util.Map.of("other", empty), recipes));
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
    void unusedRecipesValidateConcreteOperationSemantics() throws Exception {
        String catalog = """
            {"format":"dmmap-daggerheart","version":2,"category":"items","entries":[]}""";
        String document = """
            {"format":"dmmap-daggerheart-rules","version":2,"recipes":[{"id":"test","args":{},"ui":{},
             "rules":[{"id":"rule","bindings":[{"id":"go","event":"action.request","surface":"sheet","mode":"button"}],
                       "do":[%s],"ui":{"label":"Test"}}]}]}""";
        for (String operation : List.of(
                """
                {"op":"resource.gain","pool":"actor.hope.extra","amount":1}""",
                """
                {"op":"track.clear","track":"recipient.hp.extra","amount":1}""",
                """
                {"op":"roll.reroll","from":{"read":"event.roll"},"dice":{"read":"event.dice"},"choose":"fast"}""",
                """
                {"op":"plan.adjust","plan":"actor.rest","field":"moves","by":1,"label":"Moves"}""",
                """
                {"op":"roll.contribute","from":{"read":"event.roll"},"contribution":{"id":"x",
                 "kind":"dice","role":"bonus","label":"Bonus","count":1,"sides":6,"sign":0}}""",
                """
                {"op":"inventory.grant","catalog":"unknown","entry":"item","quantity":1,"label":"Item"}""",
                """
                {"op":"effect.start","effect":"bad effect","target":"actor"}""",
                """
                {"op":"value","value":1,"if":{"unknown":true}}""")) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> RuleCatalog.compile(catalog, document.formatted(operation)), operation);
            assertTrue(failure.getMessage().contains("/recipes/test/rules/0/do/0"), failure.getMessage());
        }
        String invalidCost = document.formatted("{\"op\":\"value\",\"value\":1}")
                .replace("\"do\":[", "\"costs\":[{\"op\":\"resource.spend\",\"pool\":\"recipient.hope\",\"amount\":1}],\"do\":[");
        var failure = assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(catalog, invalidCost));
        assertTrue(failure.getMessage().contains("/recipes/test/rules/0/costs/0/pool"), failure.getMessage());
    }

    @Test
    void unusedRecipeOperationsDeferOnlySourceDeclarationsAndSubstitutions() throws Exception {
        String catalog = """
            {"format":"dmmap-daggerheart","version":2,"category":"items","entries":[]}""";
        String recipes = """
            {"format":"dmmap-daggerheart-rules","version":2,"recipes":[{"id":"test",
             "args":{"amount":{"type":"integer"}},"ui":{},"rules":[{"id":"rule",
             "bindings":[{"id":"go","event":"action.request","surface":"sheet","mode":"button",
                          "targetSelection":{"cardinality":"one","scope":"connectedPlayerCharacters"}}],
             "do":[{"op":"state.increment","slot":"counter","by":1},
                   {"op":"effect.start","effect":"ward","target":"actor"},
                   {"op":"limit.reset","limit":"usage"},
                   {"op":"pool.spend","pool":"reserve","amount":1,"owner":"recipient"},
                   {"op":"value","value":{"$arg":"amount"}}],"ui":{"label":"Test"}}]}]}""";
        assertTrue(RuleCatalog.compile(catalog, recipes).isEmpty());
        String expanded = """
            {"format":"dmmap-daggerheart","version":2,"category":"items","entries":[
             {"id":"example","name":"Example","automation":{"rules":[
               {"id":"use","useRecipe":"test","args":{"amount":1}}]}}]}""";
        var failure = assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(expanded, recipes));
        assertTrue(failure.getMessage().contains("Undeclared state slot"), failure.getMessage());

        String declared = """
            {"format":"dmmap-daggerheart","version":2,"category":"items","entries":[{"id":"item","name":"Item",
             "automation":{"state":{"counter":0},"effects":{"ward":{"label":"Ward"}},
              "limits":{"usage":{"max":1,"resetOn":["rest.short"]}},
              "pools":{"reserve":{"kind":"number","scope":"actor","max":3}},
              "rules":[{"id":"use","useRecipe":"test","args":{"amount":1}}]}}]}""";
        assertDoesNotThrow(() -> RuleCatalog.compile(declared, recipes));
        ObjectNode valid = (ObjectNode) RuleJson.JSON.readTree(declared);
        for (String declaration : List.of("state", "effects", "limits", "pools")) {
            ObjectNode missing = valid.deepCopy();
            ((ObjectNode) missing.at("/entries/0/automation")).remove(declaration);
            assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(missing.toString(), recipes), declaration);
        }
        ObjectNode sourcePool = valid.deepCopy();
        ((ObjectNode) sourcePool.at("/entries/0/automation/pools/reserve")).put("scope", "source");
        failure = assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(sourcePool.toString(), recipes));
        assertTrue(failure.getMessage().contains("actor-scoped"), failure.getMessage());
        ObjectNode derivedEffect = valid.deepCopy();
        ((ObjectNode) derivedEffect.at("/entries/0/automation/effects/ward")).put("while", true);
        failure = assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(derivedEffect.toString(), recipes));
        assertTrue(failure.getMessage().contains("derived effect"), failure.getMessage());

        String parameterized = """
            {"format":"dmmap-daggerheart-rules","version":2,"recipes":[{"id":"test",
             "args":{"choice":{"type":"string"}},"ui":{},"rules":[{"id":"rule",
             "bindings":[{"id":"go","event":"action.request","surface":"sheet","mode":"button"}],
             "do":[{"op":"roll.reroll","from":{"read":"event.roll"},"dice":{"read":"event.dice"},
                    "choose":{"$arg":"choice"}}],"ui":{"label":"Test"}}]}]}""";
        assertTrue(RuleCatalog.compile(catalog, parameterized).isEmpty());
        String badChoice = """
            {"format":"dmmap-daggerheart","version":2,"category":"items","entries":[{"id":"item","name":"Item",
             "automation":{"rules":[{"id":"use","useRecipe":"test","args":{"choice":"fast"}}]}}]}""";
        failure = assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(badChoice, parameterized));
        assertTrue(failure.getMessage().contains("/choose"), failure.getMessage());
    }

    @Test
    void expandedRecipePoolCostsUseTheActualSourceDeclarations() throws Exception {
        String recipes = """
            {"format":"dmmap-daggerheart-rules","version":2,"recipes":[{"id":"test",
             "args":{"payment":{"type":"costs"}},"ui":{},"rules":[{"id":"rule",
             "bindings":[{"id":"go","event":"action.request","surface":"sheet","mode":"button"}],
             "costs":{"$arg":"payment"},"do":[{"op":"pool.gain","pool":"reserve","amount":1}],
             "ui":{"label":"Test"}}]}]}""";
        String catalog = """
            {"format":"dmmap-daggerheart","version":2,"category":"items","entries":[{"id":"item","name":"Item",
             "automation":{"pools":{"reserve":{"kind":"number","scope":"actor","max":3}},
              "rules":[{"id":"use","useRecipe":"test","args":{"payment":[
                {"op":"pool.spend","pool":"reserve","amount":1,"owner":"actor"}]}}]}}]}""";
        assertDoesNotThrow(() -> RuleCatalog.compile(catalog, recipes));
        ObjectNode missing = (ObjectNode) RuleJson.JSON.readTree(catalog);
        ((ObjectNode) missing.at("/entries/0/automation")).remove("pools");
        var failure = assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(missing.toString(), recipes));
        assertTrue(failure.getMessage().contains("Undeclared pool"), failure.getMessage());
        ObjectNode wrongKind = (ObjectNode) RuleJson.JSON.readTree(catalog);
        ((ObjectNode) wrongKind.at("/entries/0/automation/pools/reserve")).put("kind", "dice");
        failure = assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(wrongKind.toString(), recipes));
        assertTrue(failure.getMessage().contains("requires a number pool"), failure.getMessage());
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
                {"claimGroupScope":"result"}""",
                """
                {"claimGroup":"claim","claimGroupScope":"invalid"}""",
                """
                {"claimGroup":"claim","claimGroupScope":"result"}""",
                """
                {"claimGroup":"claim","claimGroupScope":"result","event":"roll.finished","surface":"roll","mode":"option"}""",
                """
                {"claimGroup":"claim","claimGroupScope":"result","event":"claim.accept","surface":"chat","mode":"button"}""",
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

    private static ObjectNode localReferenceRecipes() throws Exception {
        return (ObjectNode) RuleJson.parse("""
            {"format":"dmmap-daggerheart-rules","version":2,"recipes":[{
              "id":"test","args":{"inputId":{"type":"string","default":"amount"},
                                   "outputId":{"type":"string","default":"first"}},
              "ui":{},"rules":[{
                "id":"rule","bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button"}],
                "inputs":[{"id":"amount","kind":"integer","label":"Amount","min":0,"max":2}],
                "do":[{"op":"value","value":{"read":"input.amount"},"as":"first"},
                      {"op":"value","value":{"read":"result.first"},"as":"second"}],
                "ui":{"label":"Action","report":"{result.second}"}
              }]
            }]}""");
    }

    @Test
    void unusedRecipesLinkKnownLocalReferencesInOrderAndScope() throws Exception {
        String catalog = """
            {"format":"dmmap-daggerheart","version":2,"category":"items","entries":[]}""";
        ObjectNode recipes = localReferenceRecipes();
        assertTrue(RuleCatalog.compile(catalog, recipes.toString()).isEmpty());
        for (String invalid : List.of(
                """
                {"when":{"read":"input.amount"}}""",
                """
                {"when":{"read":"args.missing"}}""",
                """
                {"inputs":[{"id":"amount","kind":"integer","label":"Amount","min":{"read":"input.amount"},"max":2}]}""",
                """
                {"do":[{"op":"value","value":{"read":"result.later"},"as":"first"},
                       {"op":"value","value":0,"as":"later"}]}""",
                """
                {"do":[{"op":"value","value":{"read":"result.first"},"as":"first"}]}""",
                """
                {"do":[{"op":"value","value":{"read":"input.missing"},"as":"first"}]}""",
                """
                {"do":[{"op":"roll.resolve","plan":{"read":"result.missing"},"as":"first"}]}""",
                """
                {"do":[{"op":"dice.table","count":1,"outcomes":["{result.missing}","B"],"as":"first"}]}""",
                """
                {"ui":{"label":"{result.first}","report":"Done"}}""",
                """
                {"ui":{"label":"Action","report":"{result.missing}"}}"""
        )) {
            ObjectNode bad = recipes.deepCopy();
            ((ObjectNode) bad.at("/recipes/0/rules/0")).setAll((ObjectNode) RuleJson.parse(invalid));
            var error = assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(catalog, bad.toString()), invalid);
            assertTrue(error.getMessage().contains("Unknown or unavailable reference"), error.getMessage());
        }
        ObjectNode binding = recipes.deepCopy();
        ((ObjectNode) binding.at("/recipes/0/rules/0/bindings/0")).set("when", RuleJson.parse("{\"read\":\"result.first\"}"));
        assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(catalog, binding.toString()));
        ObjectNode target = recipes.deepCopy();
        ObjectNode selection = ((ObjectNode) target.at("/recipes/0/rules/0/bindings/0")).putObject("targetSelection")
                .put("cardinality", "one").put("scope", "activePlayerCharacters");
        selection.set("where", RuleJson.parse("{\"read\":\"input.amount\"}"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(catalog, target.toString()))
                .getMessage().contains("input.amount"));

        ObjectNode direct = (ObjectNode) RuleJson.parse("{\"rules\":[]}");
        direct.withArray("rules").add(recipes.at("/recipes/0/rules/0").deepCopy());
        assertNotNull(RuleCompiler.compile("test", direct, recipes));
        ObjectNode badPlan = (ObjectNode) recipes.at("/recipes/0/rules/0").deepCopy();
        ((ObjectNode) badPlan.at("/do/0")).put("op", "roll.resolve").remove("value");
        ((ObjectNode) badPlan.at("/do/0")).set("plan", RuleJson.parse("{\"read\":\"result.missing\"}"));
        direct.withArray("rules").set(0, badPlan);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> RuleCompiler.compile("test", direct, recipes))
                .getMessage().contains("result.missing"));
        ObjectNode badTable = (ObjectNode) recipes.at("/recipes/0/rules/0").deepCopy();
        ((ObjectNode) badTable.at("/do/0")).put("op", "dice.table").remove("value");
        ((ObjectNode) badTable.at("/do/0")).put("count", 1).putArray("outcomes").add("{result.missing}").add("B");
        direct.withArray("rules").set(0, badTable);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> RuleCompiler.compile("test", direct, recipes))
                .getMessage().contains("result.missing"));
        direct.withArray("rules").set(0, target.at("/recipes/0/rules/0").deepCopy());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> RuleCompiler.compile("test", direct, recipes))
                .getMessage().contains("input.amount"));
    }

    @Test
    void unusedRecipeLocalReferencesDeferOnlySubstitutionDependentScopes() throws Exception {
        String catalog = """
            {"format":"dmmap-daggerheart","version":2,"category":"items","entries":[]}""";
        ObjectNode recipes = localReferenceRecipes();
        ObjectNode rule = (ObjectNode) recipes.at("/recipes/0/rules/0");
        ((ObjectNode) rule.at("/inputs/0")).putObject("id").put("$arg", "inputId");
        ((ObjectNode) rule.at("/do/0")).putObject("as").put("$arg", "outputId");
        assertTrue(RuleCatalog.compile(catalog, recipes.toString()).isEmpty());
        ObjectNode use = (ObjectNode) RuleJson.parse("""
            {"rules":[{"id":"use","useRecipe":"test","args":{}}]}""");
        assertNotNull(RuleCompiler.compile("test", use, recipes));
        ((ObjectNode) recipes.at("/recipes/0/args/inputId")).put("default", "other");
        assertTrue(RuleCatalog.compile(catalog, recipes.toString()).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> RuleCompiler.compile("test", use, recipes));
        ((ObjectNode) recipes.at("/recipes/0/args/inputId")).put("default", "amount");
        ((ObjectNode) recipes.at("/recipes/0/args/outputId")).put("default", "other");
        assertTrue(RuleCatalog.compile(catalog, recipes.toString()).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> RuleCompiler.compile("test", use, recipes));

        ObjectNode dynamicCost = localReferenceRecipes();
        ((ObjectNode) dynamicCost.at("/recipes/0/args")).putObject("extraCosts").put("type", "costs")
                .putArray("default").addObject().put("op", "resource.spend").put("pool", "actor.hope").put("amount", 1).put("as", "bonus");
        ObjectNode costRule = (ObjectNode) dynamicCost.at("/recipes/0/rules/0");
        costRule.putObject("costs").put("$arg", "extraCosts");
        ((ObjectNode) costRule.at("/do/0")).set("value", RuleJson.parse("{\"read\":\"result.bonus\"}"));
        assertTrue(RuleCatalog.compile(catalog, dynamicCost.toString()).isEmpty());
        assertNotNull(RuleCompiler.compile("test", use, dynamicCost));
        ((ObjectNode) use.at("/rules/0/args")).putArray("extraCosts");
        assertThrows(IllegalArgumentException.class, () -> RuleCompiler.compile("test", use, dynamicCost));
    }

    @Test
    void compiledProgramsIndexUserBindingsByEventAndSurface() throws Exception {
        var source = RuleCatalog.compile(fixture("rewrite/host-classes").toString(), fixture("rewrite/recipes").toString())
                .stream().filter(item -> item.id().equals("classes:tester:features:rally")).findFirst().orElseThrow();
        assertEquals(List.of(new RuleCompiler.Program.BindingRef("rally", "sheet", false, "party", false, null)),
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
