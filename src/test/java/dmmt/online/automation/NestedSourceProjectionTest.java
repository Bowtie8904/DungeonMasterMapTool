package dmmt.online.automation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NestedSourceProjectionTest {
    private static SheetProjection projection() throws Exception {
        return SheetProjection.parse("""
                {"format":"dmmap-sheet-projection","version":2,"stateField":"automationState",
                 "sourceField":"automationSource","instanceField":"instanceId","nameField":"name",
                 "sources":[{"path":"devices","children":[{"path":"modes","derive":true}]}],
                 "actor":{},"stats":[]}""");
    }

    @Test
    void nestedModesDeriveStableDistinctIdentityFromTheirParentAndRemainIndependentlyRemovable() throws Exception {
        SheetProjection projection = projection();
        ObjectNode sheet = (ObjectNode) RuleJson.parse("""
                {"devices":[
                  {"name":"Lantern","automationSource":"items:lantern","instanceId":"first",
                   "modes":[{"id":"bright","name":"Bright","automationSource":"forged","instanceId":"forged"}]},
                  {"name":"Lantern","automationSource":"items:lantern","instanceId":"second",
                   "modes":[{"id":"bright","name":"Bright"}]}]}""");
        var sources = projection.sources(sheet);
        assertEquals(List.of("devices", "devices.modes"), projection.sourcePaths());
        assertEquals(List.of("items:lantern", "items:lantern:modes:bright", "items:lantern", "items:lantern:modes:bright"),
                sources.stream().map(SheetProjection.SourceElement::sourceId).toList());
        String first = sources.get(1).instanceId(), second = sources.get(3).instanceId();
        assertNotEquals(first, second);
        assertNotEquals("forged", first);
        assertEquals(List.of("first", first), projection.sourceInstancesWithin(sheet, sources.get(0)));
        assertEquals(List.of(first), projection.sourceInstancesWithin(sheet, sources.get(1)));

        var devices = sheet.withArray("devices");
        var left = devices.remove(0);
        devices.add(left);
        sources = projection.sources(sheet);
        assertEquals(second, sources.get(1).instanceId());
        assertEquals(first, sources.get(3).instanceId());
        assertTrue(projection.removeSource(sheet, sources.get(1)));
        assertTrue(sheet.at("/devices/0/modes").isEmpty());
        assertEquals(first, projection.sources(sheet).get(2).instanceId());
    }

    @Test
    void duplicateNestedIdsAreRejectedAndChildrenNeedAnIdentifiedParent() throws Exception {
        SheetProjection projection = projection();
        ObjectNode sheet = (ObjectNode) RuleJson.parse("""
                {"devices":[{"automationSource":"items:lantern","instanceId":"one",
                 "modes":[{"id":"bright"},{"id":"bright"}]}]}""");
        assertThrows(IllegalArgumentException.class, () -> projection.sources(sheet));
        ((ObjectNode) sheet.at("/devices/0")).remove("instanceId");
        assertTrue(projection.sources(sheet).isEmpty());
    }

    @Test
    void derivedIdentityIsOnlyValidForNestedLocations() {
        assertThrows(IllegalArgumentException.class, () -> SheetProjection.parse("""
                {"format":"dmmap-sheet-projection","version":2,"stateField":"automationState",
                 "sourceField":"automationSource","instanceField":"instanceId","nameField":"name",
                 "sources":[{"path":"devices","derive":true}],"actor":{},"stats":[]}"""));
    }

    @Test
    void declaredEquipmentLocationsProduceOrderedIndependentSnapshots() throws Exception {
        SheetProjection projection = SheetProjection.parse("""
                {"format":"dmmap-sheet-projection","version":2,"stateField":"automationState",
                 "sourceField":"automationSource","instanceField":"instanceId","nameField":"name",
                 "sources":[{"path":"tools","equipment":"tools"},{"path":"rig","single":true,"equipment":"rig"},
                            {"path":"devices"}],"actor":{},"stats":[]}""");
        ObjectNode before = (ObjectNode) RuleJson.parse("{\"tools\":[],\"devices\":[]}");
        ObjectNode after = (ObjectNode) RuleJson.parse("""
                {"tools":[{"name":"Lantern"}],"rig":{"name":"Harness"},"devices":[{"name":"Ignored"}]}""");
        var changes = projection.equipmentChanges(before, after);
        assertEquals(List.of("tools", "rig"), changes.stream().map(SheetProjection.EquipmentChange::slot).toList());
        assertTrue(changes.getFirst().before().isArray());
        assertEquals("Lantern", changes.getFirst().after().at("/0/name").asText());
        assertTrue(changes.getLast().before().isNull());
        assertEquals("Harness", changes.getLast().after().path("name").asText());
        after.withArray("tools").removeAll();
        assertEquals("Lantern", changes.getFirst().after().at("/0/name").asText());
        assertTrue(projection.equipmentChanges((ObjectNode) RuleJson.parse("{}"), before).isEmpty());
    }

    @Test
    void wholeTrackAdditionAndRemovalAreTrackChanges() throws Exception {
        SheetProjection projection = SheetProjection.parse("""
                {"format":"dmmap-sheet-projection","version":2,"stateField":"automationState",
                 "sourceField":"automationSource","instanceField":"instanceId","nameField":"name",
                 "sources":[{"path":"devices"}],"stats":[],"actor":{"ward":{"kind":"object","path":"ward",
                 "fields":{"marked":"on","capacity":"slots"},"writable":["marked"],"max":1000}}}""");
        ObjectNode without = (ObjectNode) RuleJson.parse("{}");
        ObjectNode with = (ObjectNode) RuleJson.parse("{\"ward\":{\"on\":1,\"slots\":3}}");
        assertTrue(projection.hasTrackChanges(without, with));
        assertEquals("add", projection.trackChanges(without, with).getFirst().change());
        assertEquals(3, projection.trackChanges(without, with).getFirst().requested());
        assertEquals("remove", projection.trackChanges(with, without).getFirst().change());
        assertTrue(projection.trackChanges(with, with).isEmpty());
    }

    @Test
    void equipmentLocationIdsAreUniqueAndTopLevel() {
        String header = """
                {"format":"dmmap-sheet-projection","version":2,"stateField":"automationState",
                 "sourceField":"automationSource","instanceField":"instanceId","nameField":"name",
                 "actor":{},"stats":[],"sources":%s}""";
        assertThrows(IllegalArgumentException.class, () -> SheetProjection.parse(header.formatted(
                "[{\"path\":\"tools\",\"equipment\":\"gear\"},{\"path\":\"rig\",\"equipment\":\"gear\"}]")));
        assertThrows(IllegalArgumentException.class, () -> SheetProjection.parse(header.formatted(
                "[{\"path\":\"tools\",\"children\":[{\"path\":\"modes\",\"equipment\":\"mode\"}]}]")));
    }
}
