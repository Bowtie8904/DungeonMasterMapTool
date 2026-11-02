package dmmt.online;

import dmmt.model.DmProject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MoveValidatorTest {
    private static Campaign campaign() {
        Campaign c = new Campaign();
        c.setName("c");
        c.getPlayers().add(Campaign.Player.builder().id("p1").name("Ann").build());
        c.getPlayers().add(Campaign.Player.builder().id("p2").name("Bob").build());
        c.getActors().add(Campaign.Actor.builder().id("a1").name("Hero").ownerId("p1").build());
        return c;
    }

    private static DmProject.TokenInstance token(Campaign c, boolean visible) {
        return DmProject.TokenInstance.builder().id("t").definitionId("d").x(0).y(0).playerVisible(visible)
                .actorId("a1").campaignId(c.getId()).build();
    }

    @Test
    void ownerMayMoveOthersMayNot() {
        Campaign c = campaign();
        DmProject project = new DmProject();
        DmProject.TokenInstance t = token(c, true);
        assertNull(MoveValidator.check(project, c, "m", "p1", t, 10, 10));
        assertNotNull(MoveValidator.check(project, c, "m", "p2", t, 10, 10));
    }

    @Test
    void hiddenTokensAndWallsBlock() {
        Campaign c = campaign();
        DmProject project = new DmProject();
        assertNotNull(MoveValidator.check(project, c, "m", "p1", token(c, false), 10, 10));
        DmProject.WallSegment wall = new DmProject.WallSegment();
        wall.setX1(5);
        wall.setY1(-5);
        wall.setX2(5);
        wall.setY2(5);
        project.setWalls(List.of(wall));
        assertEquals("Blocked by a wall", MoveValidator.check(project, c, "m", "p1", token(c, true), 10, 0));
        assertNull(MoveValidator.check(project, c, "m", "p1", token(c, true), 3, 0));
    }

    @Test
    void segmentCrossing() {
        assertTrue(MoveValidator.crosses(0, 0, 10, 10, 0, 10, 10, 0));
        assertFalse(MoveValidator.crosses(0, 0, 1, 1, 5, 5, 6, 6));
    }
}
