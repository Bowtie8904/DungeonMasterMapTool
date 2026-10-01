package dmmt.service;

import dmmt.model.DmProject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MapRotationServiceTest {
    @Test
    void rotatesProjectInQuarterTurns() {
        DmProject project = DmProject.builder().build();
        project.getImageLayers().add(DmProject.ImageLayer.builder()
                .id("base")
                .x(0)
                .y(0)
                .width(100)
                .height(50)
                .build());
        project.getWalls().add(DmProject.WallSegment.builder()
                .x1(0).y1(0).x2(100).y2(0)
                .build());

        MapRotationService service = new MapRotationService();
        service.rotateClockwise(project);

        DmProject.ImageLayer layer = project.getImageLayers().get(0);
        assertEquals(50, layer.getWidth(), 0.0001);
        assertEquals(100, layer.getHeight(), 0.0001);
        assertEquals(25, layer.getX(), 0.0001);
        assertEquals(-25, layer.getY(), 0.0001);
        DmProject.WallSegment wall = project.getWalls().get(0);
        assertEquals(75, wall.getX1(), 0.0001);
        assertEquals(-25, wall.getY1(), 0.0001);
        assertEquals(75, wall.getX2(), 0.0001);
        assertEquals(75, wall.getY2(), 0.0001);
        assertEquals(1, project.getMap().getRotationQuarterTurns());
    }

    @Test
    void rotatesOverlayShapesAndRoundTrips() {
        DmProject project = DmProject.builder().build();
        project.getImageLayers().add(DmProject.ImageLayer.builder().id("base").x(0).y(0).width(100).height(50).build());
        project.getOverlays().add(DmProject.OverlayShape.builder().id("r").type("rect").x(10).y(5).width(20).height(10).build());
        project.getOverlays().add(DmProject.OverlayShape.builder().id("b").type("brush").strokeWidth(8)
                .points(new java.util.ArrayList<>(java.util.List.of(10.0, 10.0, 40.0, 20.0))).build());

        MapRotationService service = new MapRotationService();
        service.rotateClockwise(project);

        DmProject.OverlayShape rect = project.getOverlays().get(0);
        assertEquals(10, rect.getWidth(), 0.0001);
        assertEquals(20, rect.getHeight(), 0.0001);
        // center (20,10) -> (cx - (y - cy), cy + (x - cx)) with c = (50,25) => (65, -5)
        assertEquals(65, rect.getX() + rect.getWidth() / 2, 0.0001);
        assertEquals(-5, rect.getY() + rect.getHeight() / 2, 0.0001);
        assertEquals(65.0, project.getOverlays().get(1).getPoints().get(0), 0.0001);

        service.rotateCounterClockwise(project);
        assertEquals(10, rect.getX(), 0.0001);
        assertEquals(5, rect.getY(), 0.0001);
        assertEquals(20, rect.getWidth(), 0.0001);
        assertEquals(10.0, project.getOverlays().get(1).getPoints().get(0), 0.0001);
        assertEquals(20.0, project.getOverlays().get(1).getPoints().get(3), 0.0001);
    }
    @Test
    void rotatesTextBoxPositionsOnlyAndRoundTrips() {
        DmProject project = DmProject.builder().build();
        project.getImageLayers().add(DmProject.ImageLayer.builder().id("base").x(0).y(0).width(100).height(50).build());
        project.getTextBoxes().add(DmProject.TextBox.builder().id("t").x(10).y(5).width(20).height(10).build());

        MapRotationService service = new MapRotationService();
        service.rotateClockwise(project);

        DmProject.TextBox box = project.getTextBoxes().get(0);
        assertEquals(20, box.getWidth(), 0.0001);
        assertEquals(10, box.getHeight(), 0.0001);
        assertEquals(65, box.getX() + box.getWidth() / 2, 0.0001);
        assertEquals(-5, box.getY() + box.getHeight() / 2, 0.0001);

        service.rotateCounterClockwise(project);
        assertEquals(10, box.getX(), 0.0001);
        assertEquals(5, box.getY(), 0.0001);
        assertEquals(20, box.getWidth(), 0.0001);
        assertEquals(10, box.getHeight(), 0.0001);
    }
}
