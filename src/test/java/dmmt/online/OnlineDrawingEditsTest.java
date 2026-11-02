package dmmt.online;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dmmt.model.DmProject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OnlineDrawingEditsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ObjectNode command(String action) throws Exception {
        ObjectNode node = MAPPER.createObjectNode().put("action", action).put("id", "shape-1");
        node.set("shape", MAPPER.readTree("""
                {"type":"rect","x":10,"y":20,"width":100,"height":80,"radius":0,
                 "strokeWidth":12,"alpha":0.5,"color":"#55aa33","texture":"fire",
                 "emits":true,"above":true,"border":true,"points":[],
                 "ownerId":"forged","ownerCampaignId":"forged","playerVisible":false}
                """));
        return node;
    }

    @Test
    void createsNormalPersistedShapeWithHostAssignedOwner() throws Exception {
        DmProject project = DmProject.builder().build();
        var shape = OnlineDrawingEdits.prepare(project, "campaign", "alice", command("create"));
        assertEquals("alice", shape.getOwnerId());
        assertEquals("campaign", shape.getOwnerCampaignId());
        assertTrue(shape.isPlayerVisible());
        assertTrue(shape.isEmitsLight());
        project.getOverlays().add(shape);
        DmProject restored = MAPPER.readValue(MAPPER.writeValueAsBytes(project), DmProject.class);
        assertEquals(shape, restored.getOverlays().getFirst());
        assertNotNull(OnlineDrawingEdits.prepare(restored, "campaign", "alice", command("update")));
        assertNull(OnlineDrawingEdits.prepare(restored, "campaign", "alice", command("delete")));
    }

    @Test
    void forbidsOtherPlayersOtherCampaignsDmShapesAndHiddenShapes() throws Exception {
        DmProject project = DmProject.builder().build();
        var shape = OnlineDrawingEdits.prepare(project, "campaign", "alice", command("create"));
        project.getOverlays().add(shape);
        for (String action : new String[]{"update", "delete"}) {
            assertThrows(IllegalArgumentException.class, () -> OnlineDrawingEdits.prepare(project, "campaign", "bob", command(action)));
            assertThrows(IllegalArgumentException.class, () -> OnlineDrawingEdits.prepare(project, "other", "alice", command(action)));
        }
        shape.setOwnerId(null);
        assertThrows(IllegalArgumentException.class, () -> OnlineDrawingEdits.prepare(project, "campaign", "alice", command("update")));
        shape.setOwnerId("alice");
        shape.setPlayerVisible(false);
        assertThrows(IllegalArgumentException.class, () -> OnlineDrawingEdits.prepare(project, "campaign", "alice", command("delete")));
        assertThrows(IllegalArgumentException.class, () -> OnlineDrawingEdits.prepare(project, "campaign", "alice", command("create")));
    }

    @Test
    void validatesGeometryAndTextureAndNormalizesInk() throws Exception {
        for (String type : new String[]{"pen", "brush", "line", "circle", "rect"}) {
            var cmd = command("create");
            var body = (ObjectNode) cmd.get("shape");
            body.put("type", type);
            if (type.equals("pen") || type.equals("brush")) body.putArray("points").add(1).add(2);
            if (type.equals("line")) body.putArray("points").add(1).add(2).add(3).add(4);
            var shape = OnlineDrawingEdits.prepare(DmProject.builder().build(), "c", "p", cmd);
            if (type.equals("pen") || type.equals("line")) {
                assertEquals("none", shape.getTexture());
                assertFalse(shape.isEmitsLight());
                assertTrue(shape.getAboveTokens());
            }
        }
        for (String key : new String[]{"x", "y", "width", "height", "radius", "strokeWidth", "alpha"}) {
            var cmd = command("create");
            ((ObjectNode) cmd.get("shape")).put(key, "not a number");
            assertThrows(IllegalArgumentException.class, () -> OnlineDrawingEdits.prepare(DmProject.builder().build(), "c", "p", cmd));
        }
        var cmd = command("create");
        ((ObjectNode) cmd.get("shape")).put("texture", "unknown");
        assertThrows(IllegalArgumentException.class, () -> OnlineDrawingEdits.prepare(DmProject.builder().build(), "c", "p", cmd));
    }

    @Test
    void measurementsValidateAndUseGridTileUnits() throws Exception {
        var node = MAPPER.readTree("""
                {"id":"m","kind":"distance","x1":0,"y1":0,"x2":30,"y2":40}
                """);
        var m = OnlineMeasurement.parse(node, "alice");
        assertEquals("5.0 tiles", m.label(10));
        assertEquals("alice", m.translate(3, 5).ownerId());
        assertEquals("5.0 tiles", m.translate(3, 5).label(10));
        ((ObjectNode) node).put("kind", "invalid");
        assertThrows(IllegalArgumentException.class, () -> OnlineMeasurement.parse(node, "alice"));
    }
}
