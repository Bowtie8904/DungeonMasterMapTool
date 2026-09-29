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
}
