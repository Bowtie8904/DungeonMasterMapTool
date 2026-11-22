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
