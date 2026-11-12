package dmmt.lighting;

import dmmt.model.DmProject;
import dmmt.model.FogMask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightingEngineTest {
    @AfterEach
    void resetTuning() {
        dmmt.service.Tuning.reset();
    }

    @Test
    void actorSightRangeChangesLiveAndIsClamped() {
        DmProject project = projectWithWall();
        project.getInteractables().getFirst().setState("open");
        project.getMap().getGrid().setPixelsPerCell(10);
        project.getTokens().add(DmProject.TokenInstance.builder()
                .id("actor").actorId("a").playerVisible(true).x(300).y(500).build());
        LightingEngine engine = new LightingEngine();
        engine.update(project);
        FogMask mask = project.getFog().getMask();
        int cell = cellAt(mask, 600, 500);
        assertTrue(engine.getLiveReveal().get(cell));
        dmmt.service.Tuning.apply(key -> key.equals("fog.actorSightTiles") ? "10" : null);
        engine.update(project);
        assertFalse(engine.getLiveReveal().get(cell));
        dmmt.service.Tuning.apply(key -> key.equals("fog.actorSightTiles") ? "1000" : null);
        org.junit.jupiter.api.Assertions.assertEquals(100, dmmt.service.Tuning.FOG_ACTOR_SIGHT_TILES.get());
        engine.updateReplica(project);
        assertTrue(engine.getLiveReveal().get(cell));
        assertTrue(mask.copyBits().isEmpty());
    }

    @Test
    void replicaRenderingNeverCreatesResizesOrRevealsAnAuthoritativeFogMask() {
        var project = projectWithWall();
        project.getLighting().getLights().add(light(DmProject.RevealMode.PERSISTENT));
        var engine = new LightingEngine();
        engine.updateReplica(project);
        org.junit.jupiter.api.Assertions.assertNull(project.getFog().getMask());
        new dmmt.service.FogService().ensureMask(project);
        var mask = project.getFog().getMask();
        var bits = mask.copyBits();
        long version = mask.getVersion();
        project.getMap().getGrid().setPixelsPerCell(200);
        project.getLighting().getLights().getFirst().setX(800);
        engine.updateReplica(project);
        org.junit.jupiter.api.Assertions.assertSame(mask, project.getFog().getMask());
        org.junit.jupiter.api.Assertions.assertEquals(bits, mask.copyBits());
        org.junit.jupiter.api.Assertions.assertEquals(version, mask.getVersion());
        engine.update(project);
        assertFalse(project.getFog().getMask().copyBits().isEmpty());
    }

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
    void visibleActorSightIsTemporaryAndBlockedByWallsAndDoors() {
        DmProject project = projectWithWall();
        project.getMap().getGrid().setPixelsPerCell(10);
        DmProject.TokenInstance actor = DmProject.TokenInstance.builder()
                .id("actor-token").actorId("actor").playerVisible(true).x(300).y(500).build();
        project.getTokens().add(actor);
        LightingEngine engine = new LightingEngine();
        engine.update(project);
        FogMask mask = project.getFog().getMask();
        assertTrue(engine.getLiveReveal().get(cellAt(mask, 450, 500)));
        assertFalse(engine.getLiveReveal().get(cellAt(mask, 600, 500)));
        assertTrue(mask.copyBits().isEmpty());
        assertTrue(project.effectiveLights().isEmpty(), "Sight must not create a light");

        project.getInteractables().getFirst().setState("open");
        engine.update(project);
        assertTrue(engine.getLiveReveal().get(cellAt(mask, 600, 500)));
        assertFalse(engine.getLiveReveal().get(cellAt(mask, 720, 500)), "Sight ends at 40 tiles");

        project.getMap().getGrid().setPixelsPerCell(20);
        engine.update(project);
        assertTrue(engine.getLiveReveal().get(cellAt(mask, 720, 500)), "Grid changes update sight range");
        project.getMap().getGrid().setPixelsPerCell(10);
        actor.setX(800);
        engine.update(project);
        assertFalse(engine.getLiveReveal().get(cellAt(mask, 300, 500)));
        assertTrue(engine.getLiveReveal().get(cellAt(mask, 800, 500)));
        actor.setPlayerVisible(false);
        engine.update(project);
        assertTrue(engine.getLiveReveal().isEmpty());
        actor.setPlayerVisible(true);
        actor.setActorId(null);
        engine.update(project);
        assertTrue(engine.getLiveReveal().isEmpty(), "Ordinary tokens do not gain actor sight");
        assertTrue(mask.copyBits().isEmpty());
    }

    @Test
    void actorSightCombinesVisibleActorsAndTracksFogToggle() {
        DmProject project = projectWithWall();
        project.getMap().getGrid().setPixelsPerCell(10);
        project.getTokens().add(DmProject.TokenInstance.builder()
                .id("left").actorId("a").playerVisible(true).x(200).y(500).build());
        project.getTokens().add(DmProject.TokenInstance.builder()
                .id("right").actorId("b").playerVisible(true).x(800).y(500).build());
        LightingEngine engine = new LightingEngine();
        engine.update(project);
        FogMask mask = project.getFog().getMask();
        assertTrue(engine.getLiveReveal().get(cellAt(mask, 200, 500)));
        assertTrue(engine.getLiveReveal().get(cellAt(mask, 800, 500)));
        project.getFog().setEnabled(false);
        engine.update(project);
        assertTrue(engine.getLiveReveal().isEmpty());
        project.getFog().setEnabled(true);
        engine.updateReplica(project);
        assertTrue(engine.getLiveReveal().get(cellAt(mask, 200, 500)));
        assertTrue(engine.getLiveReveal().get(cellAt(mask, 800, 500)));
        assertTrue(mask.copyBits().isEmpty());
    }

    @Test
    void cachedRevealsMatchFreshEngineWhenSourcesOverlapAndGeometryChanges() {
        DmProject project = projectWithWall();
        project.getMap().getGrid().setPixelsPerCell(10);
        var first = DmProject.TokenInstance.builder().id("a").actorId("a").playerVisible(true).x(250).y(500).build();
        var second = DmProject.TokenInstance.builder().id("b").actorId("b").playerVisible(true).x(350).y(500).build();
        project.getTokens().add(first);
        project.getTokens().add(second);
        project.getLighting().getLights().add(light(DmProject.RevealMode.WHILE_LIT));
        LightingEngine cached = new LightingEngine();
        cached.update(project);
        for (int step = 0; step < 7; step++) {
            switch (step) {
                case 0 -> first.setX(270);
                case 1 -> project.getInteractables().getFirst().setState("open");
                case 2 -> second.setPlayerVisible(false);
                case 3 -> project.getLighting().getLights().getFirst().setEnabled(false);
                case 4 -> project.getFog().getMask().resizeToCover(-200, -100, 1200, 1200);
                case 5 -> project.getMap().getGrid().setPixelsPerCell(15);
                case 6 -> second.setPlayerVisible(true);
            }
            cached.update(project);
            LightingEngine fresh = new LightingEngine();
            fresh.updateReplica(project);
            org.junit.jupiter.api.Assertions.assertEquals(fresh.getLiveReveal(), cached.getLiveReveal(), "step " + step);
        }
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
    void committedEditAndRestoredFogDoNotReapplyUnchangedPersistentLights() throws Exception {
        var before = projectWithWall();
        before.getLighting().getLights().add(light(DmProject.RevealMode.PERSISTENT));
        new LightingEngine().update(before);
        before.getFog().getMask().applyRect(0, 0, 1000, 1000, false);
        var after = new dmmt.service.ProjectService().copy(before);
        var engine = new LightingEngine();
        engine.completeCommittedEdit(before, after);
        assertTrue(after.getFog().getMask().copyBits().isEmpty());
        engine.acceptCommittedState(after);
        engine.update(after);
        assertTrue(after.getFog().getMask().copyBits().isEmpty());
    }

    @Test
    void committedRevealModeChangeAndFogReenableApplyPersistentReveal() throws Exception {
        var before = projectWithWall();
        before.getLighting().getLights().add(light(DmProject.RevealMode.WHILE_LIT));
        new LightingEngine().update(before);
        var after = new dmmt.service.ProjectService().copy(before);
        after.getLighting().getLights().getFirst().setRevealMode(DmProject.RevealMode.PERSISTENT);
        new LightingEngine().completeCommittedEdit(before, after);
        assertFalse(after.getFog().getMask().copyBits().isEmpty());
        before = new dmmt.service.ProjectService().copy(after);
        before.getFog().setEnabled(false);
        before.getFog().getMask().applyRect(0, 0, 1000, 1000, false);
        after = new dmmt.service.ProjectService().copy(before);
        after.getFog().setEnabled(true);
        new LightingEngine().completeCommittedEdit(before, after);
        assertFalse(after.getFog().getMask().copyBits().isEmpty());
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
