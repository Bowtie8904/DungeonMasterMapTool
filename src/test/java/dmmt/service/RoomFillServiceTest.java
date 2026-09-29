package dmmt.service;

import dmmt.model.DmProject;
import dmmt.model.FogMask;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoomFillServiceTest {
    private final FogMask mask = new FogMask(0, 0, 10, 100, 100);

    private static DmProject.WallSegment wall(double x1, double y1, double x2, double y2) {
        return DmProject.WallSegment.builder().x1(x1).y1(y1).x2(x2).y2(y2).build();
    }

    private static DmProject.Interactable door(String state, double x1, double y1, double x2, double y2) {
        return DmProject.Interactable.builder().id("d").state(state).x1(x1).y1(y1).x2(x2).y2(y2).build();
    }

    private static List<DmProject.WallSegment> square() {
        List<DmProject.WallSegment> walls = new ArrayList<>();
        walls.add(wall(200, 200, 600, 200));
        walls.add(wall(600, 200, 600, 600));
        walls.add(wall(600, 600, 200, 600));
        walls.add(wall(200, 600, 200, 200));
        return walls;
    }

    private boolean has(RoomFillService.Result result, double x, double y) {
        int col = (int) Math.floor(x / mask.getCellSize());
        int row = (int) Math.floor(y / mask.getCellSize());
        return result.cells().get(row * mask.getCols() + col);
    }

    private RoomFillService.Result fillAt(List<DmProject.WallSegment> walls,
                                          List<DmProject.Interactable> doors, double x, double y) {
        BitSet barrier = RoomFillService.buildBarrier(mask, walls, doors);
        return RoomFillService.fill(mask, barrier, x, y);
    }

    @Test
    void enclosedRoomIsFilledIncludingItsWalls() {
        RoomFillService.Result result = fillAt(square(), List.of(), 400, 400);
        assertTrue(has(result, 400, 400));
        assertTrue(has(result, 210, 210));
        assertTrue(has(result, 200, 400), "wall cells are revealed too");
        assertFalse(has(result, 100, 100));
        assertFalse(has(result, 700, 700));
        assertFalse(result.leaked());
    }

    @Test
    void doorsBoundTheRoomWhateverTheirState() {
        List<DmProject.WallSegment> walls = square();
        walls.remove(3);
        walls.add(wall(200, 200, 200, 350));
        walls.add(wall(200, 450, 200, 600));
        for (String state : List.of("closed", "open")) {
            RoomFillService.Result result = fillAt(walls, List.of(door(state, 200, 350, 200, 450)), 400, 400);
            assertTrue(has(result, 400, 400));
            assertFalse(has(result, 100, 400), "room must not spill through the " + state + " door");
            assertFalse(result.leaked());
        }
    }

    @Test
    void gapWithoutDoorLeaksOutside() {
        List<DmProject.WallSegment> walls = square();
        walls.remove(3);
        walls.add(wall(200, 200, 200, 350));
        walls.add(wall(200, 450, 200, 600));
        RoomFillService.Result result = fillAt(walls, List.of(), 400, 400);
        assertTrue(has(result, 100, 400));
        assertTrue(result.leaked());
    }

    @Test
    void tinyGapBetweenWallsDoesNotLeak() {
        List<DmProject.WallSegment> walls = new ArrayList<>();
        walls.add(wall(200, 200, 600, 200));
        walls.add(wall(603, 203, 603, 600));
        walls.add(wall(600, 600, 200, 600));
        walls.add(wall(200, 600, 200, 200));
        RoomFillService.Result result = fillAt(walls, List.of(), 400, 400);
        assertFalse(has(result, 700, 400));
        assertFalse(result.leaked());
    }

    @Test
    void diagonalWallBlocksFill() {
        List<DmProject.WallSegment> walls = List.of(wall(0, 1000, 1000, 0));
        RoomFillService.Result result = fillAt(walls, List.of(), 200, 200);
        assertTrue(has(result, 200, 200));
        assertFalse(has(result, 800, 800));
    }

    @Test
    void clickingOnAWallOrOutsideTheMaskDoesNothing() {
        assertTrue(fillAt(square(), List.of(), 200, 400).isEmpty());
        assertTrue(fillAt(square(), List.of(), -50, 400).isEmpty());
    }
}
