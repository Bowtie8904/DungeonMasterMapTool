package dmmt.remote;

import com.fasterxml.jackson.databind.ObjectMapper;
import dmmt.model.DmProject;
import dmmt.model.FogMask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RemoteProjectDocumentTest {
    @Test
    void roundTripsCompleteProjectWithoutSynchronizingDmNavigationOrTransientPings() throws Exception {
        var project = DmProject.builder().build();
        project.getViews().getDmCamera().setX(99);
        project.getViews().getPlayerCamera().setX(44);
        project.getActivePings().add(DmProject.PingEvent.builder().x(9).build());
        project.getWalls().add(DmProject.WallSegment.builder().x1(1).y1(2).x2(3).y2(4).build());
        project.getFog().setMask(new FogMask(0, 0, 10, 100, 100));
        project.getFog().getMask().applyCells(new int[]{0, 9, 4096, 9999}, true);
        project.getImageLayers().add(DmProject.ImageLayer.builder().id("z-first").width(100).height(100).build());
        project.getImageLayers().add(DmProject.ImageLayer.builder().id("a-second").width(100).height(100).build());
        var document = RemoteProjectDocument.capture(project);
        assertFalse(document.parts().containsKey("views:dmCamera"));
        var wire = RemoteWire.message(RemoteWire.Type.STATE, document);
        var received = RemoteWire.payload(wire, RemoteProjectDocument.class);
        assertEquals(document, received);
        var localCamera = DmProject.CameraState.builder().x(123).zoom(1).build();
        var restored = received.materialize(localCamera);
        assertEquals(123, restored.getViews().getDmCamera().getX());
        assertEquals(44, restored.getViews().getPlayerCamera().getX());
        assertTrue(restored.getActivePings().isEmpty());
        assertEquals(project.getWalls(), restored.getWalls());
        assertEquals(List.of("z-first", "a-second"), restored.getImageLayers().stream().map(DmProject.ImageLayer::getId).toList());
        assertEquals(project.getFog().getMask().copyBits(), restored.getFog().getMask().copyBits());
    }

    @Test
    void documentDoesNotExposeItsMutableJsonValues() {
        var document = RemoteProjectDocument.capture(DmProject.builder().build());
        var original = document.parts().get("map:grid").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) document.parts().get("map:grid")).put("pixelsPerCell", 999);
        assertEquals(original, document.parts().get("map:grid"));
    }

    @Test
    void rejectsUnboundedCompressedFogPathsUnknownTargetsNonfiniteValuesAndMismatchedEntityIds() throws Exception {
        var json = new ObjectMapper();
        for (String target : new String[]{"../../path", "views:dmCamera", "project:id", "fog:mask", "fogBits:999999999"}) {
            assertThrows(IllegalArgumentException.class, () ->
                    new RemoteProjectDocument("map-1", Map.of(target, json.valueToTree("bad"))));
        }
        assertThrows(IllegalArgumentException.class, () -> new RemoteProjectDocument(
                "map-1", Map.of("views:playerZoomStep", json.valueToTree(Double.NaN))));
        assertThrows(IllegalArgumentException.class, () -> new RemoteProjectDocument(
                "map-1", Map.of("fogBits:0", json.valueToTree("a".repeat(700)))));
        var project = DmProject.builder().build();
        var document = RemoteProjectDocument.capture(project).with(Map.of(
                "tokens:token-1", json.valueToTree(DmProject.TokenInstance.builder()
                        .id("token-2").definitionId("definition-1").build())), java.util.Set.of());
        assertThrows(IllegalArgumentException.class, () ->
                document.materialize(DmProject.CameraState.builder().build()));
    }

    @Test
    void artworkRewritingIsScopedToAssetFieldsAndWireValidationRejectsFilesystemPaths() {
        var project = DmProject.builder().build();
        project.getMap().setImagePath("C:\\host\\private-map.png");
        project.getMap().setOriginalFileName("C:\\host\\private-map.png");
        project.getImageLayers().add(DmProject.ImageLayer.builder().id("image").path("background.png").build());
        var definition = DmProject.TokenDefinition.builder().id("token-art")
                .sourceImage("file:///C:/host/token.png").image("file:///C:/host/token-crop.png").build();
        project.getTokenDefinitions().put(definition.getId(), definition);
        var local = RemoteProjectDocument.capture(project);
        assertThrows(IllegalArgumentException.class, local::validatePortableAssets);
        assertEquals(4, local.assetReferences().size());
        String reference = "asset:" + "a".repeat(64);
        var portable = local.rewriteAssets(ignored -> reference);
        assertDoesNotThrow(portable::validatePortableAssets);
        assertEquals(java.util.Set.of(reference), portable.assetReferences());
        assertEquals("C:\\host\\private-map.png", portable.parts().get("map:originalFileName").asText());
        assertEquals("C:\\host\\private-map.png", local.parts().get("map:imagePath").asText());
    }

    @Test
    void malformedWallTargetsAndNullableEntityGeometryAreRejectedBeforeApplication() throws Exception {
        var json = new ObjectMapper();
        var baseline = RemoteProjectDocument.capture(DmProject.builder().build());
        var camera = DmProject.CameraState.builder().build();
        assertThrows(IllegalArgumentException.class, () -> baseline.with(Map.of(
                "walls:fake", json.valueToTree(DmProject.WallSegment.builder().build())), java.util.Set.of()));
        assertThrows(IllegalArgumentException.class, () -> baseline.with(Map.of(
                "walls:0.0.0.0-2", json.valueToTree(DmProject.WallSegment.builder().build())), java.util.Set.of())
                .materialize(camera));
        assertThrows(IllegalArgumentException.class, () -> baseline.with(Map.of(
                "walls:0.0.0.0-1", json.valueToTree(DmProject.WallSegment.builder().x1(10).build())), java.util.Set.of())
                .materialize(camera));
        assertThrows(IllegalArgumentException.class, () -> baseline.with(Map.of(
                "overlays:invalid", json.valueToTree(DmProject.OverlayShape.builder().id("invalid").points(null).build())),
                java.util.Set.of()).materialize(camera));
    }

    @Test
    void duplicateIdsAndInvalidFogGeometryFailBeforePublishingADocument() {
        var project = DmProject.builder().build();
        project.getTokens().add(DmProject.TokenInstance.builder().id("duplicate").build());
        project.getTokens().add(DmProject.TokenInstance.builder().id("duplicate").build());
        assertThrows(IllegalArgumentException.class, () -> RemoteProjectDocument.capture(project));
        project.getTokens().clear();
        project.getFog().setMask(new FogMask(0, 0, 1, 1_000_000, 1_000_000));
        assertThrows(IllegalArgumentException.class, () -> RemoteProjectDocument.capture(project));
    }
}
