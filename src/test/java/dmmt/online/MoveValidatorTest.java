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
    void disabledActorCannotBeMovedOrRotatedEvenWithExplicitControl() {
        Campaign c = campaign();
        DmProject.TokenInstance t = token(c, true);
        c.getControlledTokens().add(Campaign.TokenLink.builder().playerId("p2").mapId("m").tokenId("t").build());
        c.actor("a1").setDisabled(true);
        assertTrue(OnlineStateBuilder.controllers(t, c.actor("a1"), c, "m").isEmpty());
        assertNotNull(MoveValidator.checkControl(c, "m", "p1", t));
        assertNotNull(MoveValidator.checkControl(c, "m", "p2", t));
        c.actor("a1").setDisabled(false);
        assertNull(MoveValidator.checkControl(c, "m", "p1", t));
        assertNull(MoveValidator.checkControl(c, "m", "p2", t));
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

    @Test
    void spectralIgnoresWallsAndDoorsButNotOwnershipVisibilityPositionOrMapLimits() {
        Campaign c = campaign();
        DmProject project = new DmProject();
        DmProject.WallSegment wall = new DmProject.WallSegment();
        wall.setX1(5); wall.setY1(-5); wall.setX2(5); wall.setY2(5);
        project.setWalls(List.of(wall));
        DmProject.Interactable door = new DmProject.Interactable();
        door.setX1(6); door.setY1(-5); door.setX2(6); door.setY2(5);
        project.setInteractables(List.of(door));
        assertNull(MoveValidator.check(project, c, "m", "p1", token(c, true), 10, 0, true));
        assertNotNull(MoveValidator.check(project, c, "m", "p2", token(c, true), 10, 0, true));
        assertNotNull(MoveValidator.check(project, c, "m", "p1", token(c, false), 10, 0, true));
        assertNotNull(MoveValidator.check(project, c, "m", "p1", token(c, true), Double.NaN, 0, true));
        DmProject.ImageLayer image = new DmProject.ImageLayer();
        image.setWidth(20); image.setHeight(20); image.setVisible(true);
        project.setImageLayers(List.of(image));
        assertNull(MoveValidator.check(project, c, "m", "p1", token(c, true), 20, 20, true));
        assertEquals("Outside the map", MoveValidator.check(project, c, "m", "p1", token(c, true), 21, 20, true));
    }
}
