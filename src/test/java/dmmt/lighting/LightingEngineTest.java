package dmmt.lighting;

import dmmt.model.DmProject;
import dmmt.model.FogMask;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightingEngineTest {
    private static int cellAt(FogMask mask, double x, double y) {
        int col = (int) Math.floor((x - mask.getOriginX()) / mask.getCellSize());
        int row = (int) Math.floor((y - mask.getOriginY()) / mask.getCellSize());
        return row * mask.getCols() + col;
    }

    /** 1000x1000 room split by a vertical wall at x=500 with a door gap between y=450 and y=550. */
    private static DmProject projectWithWall() {
        DmProject project = DmProject.builder().build();
        project.getImageLayers().add(DmProject.ImageLayer.builder().id("base").x(0).y(0).width(1000).height(1000).build());
        project.getWalls().add(DmProject.WallSegment.builder().x1(500).y1(0).x2(500).y2(450).build());
        project.getWalls().add(DmProject.WallSegment.builder().x1(500).y1(550).x2(500).y2(1000).build());
        project.getInteractables().add(DmProject.Interactable.builder()
                .id("door").x1(500).y1(450).x2(500).y2(550).state("closed").build());
        project.getFog().setEnabled(true);
        return project;
    }

    private static DmProject.LightSource light(DmProject.RevealMode mode) {
        return DmProject.LightSource.builder().id("l1").x(300).y(500).range(400).revealMode(mode).build();
    }

    @Test
    void wallAndClosedDoorBlockSight() {
        VisibilityService service = new VisibilityService();
        DmProject project = projectWithWall();
        LightingEngine engine = new LightingEngine();
        project.getLighting().getLights().add(light(DmProject.RevealMode.PERSISTENT));
        engine.update(project);

        FogMask mask = project.getFog().getMask();
        assertTrue(mask.isRevealedCell(cellAt(mask, 450, 500)));
        assertFalse(mask.isRevealedCell(cellAt(mask, 600, 500)), "Closed door must block light");
        assertFalse(mask.isRevealedCell(cellAt(mask, 600, 300)), "Wall must block light");
        assertTrue(service.compute(300, 500, 400, new double[0]).size() > 3);
    }

    @Test
    void openDoorLetsLightThrough() {
        DmProject project = projectWithWall();
        project.getInteractables().get(0).setState("open");
        project.getLighting().getLights().add(light(DmProject.RevealMode.PERSISTENT));
        new LightingEngine().update(project);

        FogMask mask = project.getFog().getMask();
        assertTrue(mask.isRevealedCell(cellAt(mask, 600, 500)));
        assertFalse(mask.isRevealedCell(cellAt(mask, 600, 200)), "Wall beside the door still blocks");
    }

    @Test
    void persistentRevealStaysAfterLightMoves() {
        DmProject project = projectWithWall();
        DmProject.LightSource light = light(DmProject.RevealMode.PERSISTENT);
        project.getLighting().getLights().add(light);
        LightingEngine engine = new LightingEngine();
        engine.update(project);
        light.setX(100);
        light.setY(100);
        engine.update(project);

        FogMask mask = project.getFog().getMask();
        assertTrue(mask.isRevealedCell(cellAt(mask, 450, 500)), "Earlier reveal must be kept");
        assertTrue(mask.isRevealedCell(cellAt(mask, 100, 150)));
    }

    @Test
    void whileLitRevealIsTransientAndNotWrittenToMask() {
        DmProject project = projectWithWall();
        DmProject.LightSource light = light(DmProject.RevealMode.WHILE_LIT);
        project.getLighting().getLights().add(light);
        LightingEngine engine = new LightingEngine();
        engine.update(project);

        FogMask mask = project.getFog().getMask();
        int cell = cellAt(mask, 450, 500);
        assertFalse(mask.isRevealedCell(cell));
        assertTrue(engine.getLiveReveal().get(cell));

        light.setX(100);
        light.setY(100);
        engine.update(project);
        assertFalse(engine.getLiveReveal().get(cell), "Reveal must disappear once the light moves away");
    }

    @Test
    void disabledLightDoesNotRevealAndRevealsAgainWhenTurnedOn() {
        DmProject project = projectWithWall();
        DmProject.LightSource light = light(DmProject.RevealMode.PERSISTENT);
        light.setEnabled(false);
        project.getLighting().getLights().add(light);
        LightingEngine engine = new LightingEngine();
        engine.update(project);
        assertTrue(project.getFog().getMask().copyBits().isEmpty());

        light.setEnabled(true);
        engine.update(project);
        FogMask mask = project.getFog().getMask();
        assertTrue(mask.isRevealedCell(cellAt(mask, 450, 500)));
    }

    @Test
    void noneModeDoesNotReveal() {
        DmProject project = projectWithWall();
        project.getLighting().getLights().add(light(DmProject.RevealMode.NONE));
        LightingEngine engine = new LightingEngine();
        engine.update(project);

        FogMask mask = project.getFog().getMask();
        assertTrue(mask.copyBits().isEmpty());
        assertTrue(engine.getLiveReveal().isEmpty());
    }

    @Test
    void persistentLightDoesNotRevealWhileFogDisabled() {
        DmProject project = projectWithWall();
        project.getFog().setEnabled(false);
        project.getLighting().getLights().add(light(DmProject.RevealMode.PERSISTENT));
        new LightingEngine().update(project);
        assertTrue(project.getFog().getMask().copyBits().isEmpty());
    }
}
