package dmmt.render;

import dmmt.model.DmProject;
import dmmt.model.FogMask;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HistoryFeedbackTest {
    private final DmProject project = DmProject.builder().build();
    private final HistoryFeedback feedback = new HistoryFeedback();
    private static final long NOW = 10_000_000_000L;

    @Test
    void movedAndResizedLayersUseResultingBoundsWithoutMutatingAnything() {
        var layer = DmProject.ImageLayer.builder().id("image").x(10).y(20).width(30).height(40).build();
        project.getImageLayers().add(layer);
        var before = capture();
        layer.setX(-150);
        layer.setY(250);
        layer.setWidth(80);
        feedback.show(before, capture(), NOW);

        assertEquals(List.of(new HistoryFeedback.Target(HistoryFeedback.Kind.BOUNDS, -150, 250, -70, 290)),
                feedback.targets());
        assertEquals(-150, layer.getX());
        assertEquals(0, project.getViews().getDmCamera().getX());
        assertEquals(1, project.getViews().getDmCamera().getZoom());
        assertTrue(feedback.active(NOW + 999_999_999));
        assertFalse(feedback.active(NOW + 1_000_000_000));
        assertTrue(feedback.targets().isEmpty());
    }

    @Test
    void removedAndRestoredObjectsUseTheirOwnLocations() {
        var light = DmProject.LightSource.builder().id("light").x(25).y(45).build();
        project.getLighting().getLights().add(light);
        var present = capture();
        project.getLighting().getLights().clear();
        var removed = capture();
        feedback.show(present, removed, NOW);
        var expected = new HistoryFeedback.Target(HistoryFeedback.Kind.POINT, 25, 45, 25, 45);
        assertEquals(List.of(expected), feedback.targets());
        project.getLighting().getLights().add(light);
        feedback.show(removed, capture(), NOW + 100);
        assertEquals(List.of(expected), feedback.targets());
    }

    @Test
    void detectsNestedStylesWithoutHighlightingUnchangedObjects() {
        var light = DmProject.LightSource.builder().id("light").x(5).y(6).build();
        var box = DmProject.TextBox.builder().id("text").x(10).y(20).width(30).height(40).build();
        var run = DmProject.TextRun.builder().text("Old").build();
        box.getRuns().add(run);
        project.getLighting().getLights().add(light);
        project.getTextBoxes().add(box);
        var before = capture();
        run.setText("New");
        light.getFlicker().setEnabled(true);
        feedback.show(before, capture(), NOW);
        assertEquals(2, feedback.targets().size());
        assertTrue(feedback.targets().contains(new HistoryFeedback.Target(HistoryFeedback.Kind.BOUNDS, 10, 20, 40, 60)));
    }

    @Test
    void outlinesSpecificPortalAndRemovedWall() {
        var door = DmProject.Interactable.builder().id("door").x1(20).y1(30).x2(60).y2(70).build();
        var wall = DmProject.WallSegment.builder().x1(1).y1(2).x2(3).y2(4).build();
        project.getInteractables().add(door);
        project.getWalls().add(wall);
        var before = capture();
        door.setState("open");
        project.getWalls().clear();
        feedback.show(before, capture(), NOW);
        assertEquals(List.of(
                new HistoryFeedback.Target(HistoryFeedback.Kind.SEGMENT, 20, 30, 60, 70),
                new HistoryFeedback.Target(HistoryFeedback.Kind.SEGMENT, 1, 2, 3, 4)), feedback.targets());
    }

    @Test
    void fogFeedbackUsesOnlyChangedCellsIncludingDisconnectedRegionsAndHoles() {
        var mask = new FogMask(-100, 200, 10, 100, 100);
        project.getFog().setMask(mask);
        mask.applyCells(new int[]{101}, true);
        var before = capture();
        mask.applyCells(new int[]{100, 102, 200, 201, 202, 9000}, true);
        feedback.show(before, capture(), NOW);
        assertTrue(feedback.targets().isEmpty());
        for (int cell : new int[]{100, 102, 200, 201, 202, 9000}) {
            assertTrue(feedback.fogCellAffected(cell));
        }
        assertFalse(feedback.fogCellAffected(101));
        assertFalse(feedback.fogCellAffected(500));
        assertTrue(feedback.active(NOW));
        assertTrue(mask.isRevealedCell(101));
    }

    @Test
    void objectTargetTakesPrecedenceOverIncidentalPersistentFog() {
        var mask = new FogMask(0, 0, 10, 20, 20);
        project.getFog().setMask(mask);
        var light = DmProject.LightSource.builder().id("light").build();
        project.getLighting().getLights().add(light);
        var before = capture();
        light.setX(100);
        mask.applyCells(new int[]{10, 11, 12}, true);
        feedback.show(before, capture(), NOW);
        assertEquals(1, feedback.targets().size());
        assertFalse(feedback.fogCellAffected(10));
    }

    @Test
    void globalActionsAndRotationReplaceLocalFeedbackWithStatusOnly() {
        var before = capture();
        project.getLighting().getLights().add(DmProject.LightSource.builder().id("light").build());
        feedback.show(before, capture(), NOW);
        assertTrue(feedback.active(NOW));
        before = capture();
        project.getLighting().setTimeOfDayPreset("NIGHT");
        feedback.show(before, capture(), NOW + 100);
        assertFalse(feedback.active(NOW + 100));
        before = capture();
        project.getLighting().getLights().getFirst().setX(100);
        project.getMap().setRotationQuarterTurns(1);
        feedback.show(before, capture(), NOW + 200);
        assertFalse(feedback.active(NOW + 200));
    }

    @Test
    void respectsHiddenDmLayersAndDoesNotConfusePlayerVisibilityWithDmVisibility() {
        var image = DmProject.ImageLayer.builder().id("image").visible(false).width(100).height(100).build();
        var shape = DmProject.OverlayShape.builder().id("shape").type("circle")
                .x(30).y(40).radius(10).playerVisible(false).build();
        var door = DmProject.Interactable.builder().id("door").build();
        project.getImageLayers().add(image);
        project.getOverlays().add(shape);
        project.getInteractables().add(door);
        var before = HistoryFeedback.capture(project, false);
        image.setX(50);
        shape.setRadius(20);
        door.setState("open");
        feedback.show(before, HistoryFeedback.capture(project, false), NOW);
        assertEquals(List.of(new HistoryFeedback.Target(HistoryFeedback.Kind.BOUNDS, 10, 20, 50, 60)),
                feedback.targets());
    }

    @Test
    void newActionReplacesAndClearRemovesAllFeedback() {
        var before = capture();
        project.getLighting().getLights().add(DmProject.LightSource.builder().id("one").x(5).build());
        feedback.show(before, capture(), NOW);
        before = capture();
        project.getLighting().getLights().add(DmProject.LightSource.builder().id("two").x(50).build());
        feedback.show(before, capture(), NOW + 100);
        assertEquals(List.of(new HistoryFeedback.Target(HistoryFeedback.Kind.POINT, 50, 0, 50, 0)), feedback.targets());
        feedback.clear();
        assertFalse(feedback.active(NOW + 100));
    }

    @Test
    void strokeBoundsIncludeThicknessAndViewportIntersectionDoesNotMoveTargets() {
        var shape = DmProject.OverlayShape.builder().type("brush").strokeWidth(12)
                .points(List.of(-50.0, 100.0, 10.0, 200.0)).build();
        var target = HistoryFeedback.overlayBounds(shape);
        assertEquals(new HistoryFeedback.Target(HistoryFeedback.Kind.BOUNDS, -56, 94, 16, 206), target);
        assertTrue(target.intersects(0, 0, 100, 100));
        assertFalse(target.intersects(300, 300, 400, 400));
    }

    private HistoryFeedback.Snapshot capture() {
        return HistoryFeedback.capture(project, true);
    }
}
