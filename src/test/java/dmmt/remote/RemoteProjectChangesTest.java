package dmmt.remote;

import dmmt.model.DmProject;
import dmmt.model.FogMask;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class RemoteProjectChangesTest {
    @Test
    void appliesOnlyAffectedTargetsPreservingExistingReferencesAndLocalCamera() throws Exception {
        DmProject live = DmProject.builder().build();
        var light = DmProject.LightSource.builder().id("torch").x(10).build();
        var layer = DmProject.ImageLayer.builder().id("image").x(20).build();
        live.getLighting().getLights().add(light);
        live.getImageLayers().add(layer);
        var camera = live.getViews().getDmCamera();
        camera.setX(88);
        var grid = live.getMap().getGrid();
        DmProject candidate = RemoteProjectDocument.capture(live).materialize(camera);
        candidate.getLighting().getLights().getFirst().setX(100);
        candidate.getImageLayers().getFirst().setX(200);
        candidate.getViews().getDmCamera().setX(999);
        candidate.getMap().getGrid().setPixelsPerCell(64);
        RemoteProjectChanges.apply(live, candidate, Set.of("lights:torch", "map:grid"));
        assertSame(light, live.getLighting().getLights().getFirst());
        assertSame(layer, live.getImageLayers().getFirst());
        assertSame(grid, live.getMap().getGrid());
        assertSame(camera, live.getViews().getDmCamera());
        assertEquals(100, light.getX());
        assertEquals(20, layer.getX());
        assertEquals(64, grid.getPixelsPerCell());
        assertEquals(88, camera.getX());
    }

    @Test
    void targetedUndoDoesNotRevertUnrelatedHostChanges() throws Exception {
        DmProject live = DmProject.builder().build();
        var overlay = DmProject.OverlayShape.builder().id("shape").build();
        live.getOverlays().add(overlay);
        var authority = new RemoteWorkspaceAuthority(() -> live, commit ->
                RemoteProjectChanges.apply(live, commit.after(), commit.targets()));
        var before = authority.state().document().materialize(live.getViews().getDmCamera());
        var edited = authority.state().document().materialize(live.getViews().getDmCamera());
        edited.getOverlays().getFirst().setX(50);
        var result = authority.execute(RemoteWorkspaceAuthority.diff(authority.state(),
                RemoteProjectDocument.capture(edited), "Move shape"));
        assertEquals(RemoteWorkspaceAuthority.Status.ACCEPTED, result.status());
        live.getMap().setRotationQuarterTurns(2);
        RemoteProjectChanges.apply(live, before, Set.of("overlays:shape"));
        assertEquals(0, overlay.getX());
        assertEquals(2, live.getMap().getRotationQuarterTurns());
        assertSame(overlay, live.getOverlays().getFirst());
    }

    @Test
    void removesDuplicateLegacyWallsIndependentlyOfTargetIterationOrder() throws Exception {
        DmProject live = DmProject.builder().build();
        live.getWalls().add(DmProject.WallSegment.builder().x1(3).x2(7).build());
        live.getWalls().add(DmProject.WallSegment.builder().x1(3).x2(7).build());
        DmProject empty = RemoteProjectDocument.capture(live).materialize(live.getViews().getDmCamera());
        empty.getWalls().clear();
        Set<String> targets = RemoteProjectDocument.capture(live).parts().keySet().stream()
                .filter(key -> key.startsWith("walls:")).sorted()
                .collect(Collectors.toCollection(LinkedHashSet::new));
        RemoteProjectChanges.apply(live, empty, targets);
        assertTrue(live.getWalls().isEmpty());
    }

    @Test
    void fogBlockApplyAndUndoPreserveOtherBlocksAndMaskIdentity() throws Exception {
        DmProject live = DmProject.builder().build();
        FogMask mask = new FogMask(0, 0, 1, 100, 100);
        live.getFog().setMask(mask);
        mask.applyCells(new int[]{1}, true);
        DmProject before = RemoteProjectDocument.capture(live).materialize(live.getViews().getDmCamera());
        DmProject after = RemoteProjectDocument.capture(live).materialize(live.getViews().getDmCamera());
        after.getFog().getMask().applyCells(new int[]{1}, false);
        after.getFog().getMask().applyCells(new int[]{2}, true);
        mask.applyCells(new int[]{9000}, true);
        RemoteProjectChanges.apply(live, after, Set.of("fogBits:0"));
        assertSame(mask, live.getFog().getMask());
        assertFalse(mask.isRevealedCell(1));
        assertTrue(mask.isRevealedCell(2));
        assertTrue(mask.isRevealedCell(9000));
        RemoteProjectChanges.apply(live, before, Set.of("fogBits:0"));
        assertTrue(mask.isRevealedCell(1));
        assertFalse(mask.isRevealedCell(2));
        assertTrue(mask.isRevealedCell(9000));
    }
}
