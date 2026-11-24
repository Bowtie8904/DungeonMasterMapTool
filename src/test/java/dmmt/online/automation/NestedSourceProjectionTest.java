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
}
