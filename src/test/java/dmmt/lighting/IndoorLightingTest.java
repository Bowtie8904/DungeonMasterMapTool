package dmmt.lighting;

import dmmt.model.DmProject;
import dmmt.service.FogService;
import dmmt.render.PerformanceMode;
import dmmt.service.Tuning;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.*;

class IndoorLightingTest {
    @AfterEach
    void resetSettings() {
        PerformanceMode.setEnabled(false);
        Tuning.reset();
    }

    @Test
    void independentSettingsAndPerformanceOverridesPreserveRoomDetectionAndRestoreDaylight() {
        DmProject project = room("window", "open");
        IndoorLighting lighting = new IndoorLighting();
        lighting.update(project);
        double daylight = lighting.daylightAt(300, 500);
        assertTrue(daylight > 0.5);

        Tuning.apply(key -> key.equals("lighting.indoorDarkness") ? "false" : null);
        lighting.update(project);
        assertFalse(lighting.hasIndoorDarkness(TimeOfDayPreset.DAY));
        assertFalse(lighting.lightActive(TimeOfDayPreset.DAWN, 500, 500));
        assertTrue(lighting.lightActive(TimeOfDayPreset.NIGHT, 500, 500));
        assertEquals(daylight, lighting.daylightAt(300, 500));

        Tuning.apply(key -> key.equals("lighting.daylightThroughOpenings") ? "false" : null);
        lighting.update(project);
        assertTrue(lighting.hasIndoorDarkness(TimeOfDayPreset.DAY));
        assertEquals(0, lighting.daylightAt(300, 500));
        assertEquals(0, lighting.lightningExposureAt(300, 500, false));
        assertEquals(1, lighting.lightningExposureAt(300, 500, true));

        Tuning.reset();
        lighting.update(project);
        long before = lighting.version();
        PerformanceMode.setEnabled(true);
        lighting.update(project);
        assertTrue(lighting.version() > before);
        assertTrue(lighting.isInside(500, 500));
        assertFalse(lighting.indoorRegions().isEmpty(), "Weather clipping must retain room boundaries");
        for (TimeOfDayPreset preset : TimeOfDayPreset.values()) {
            assertEquals(preset == TimeOfDayPreset.NIGHT, lighting.hasIndoorDarkness(preset));
            assertEquals(preset == TimeOfDayPreset.NIGHT, lighting.lightActive(preset, 500, 500));
        }
        assertEquals(0, lighting.daylightAt(300, 500));
        assertTrue(Tuning.INDOOR_DARKNESS.get());
        assertTrue(Tuning.DAYLIGHT_THROUGH_OPENINGS.get());
        PerformanceMode.setEnabled(false);
        lighting.update(project);
        assertEquals(daylight, lighting.daylightAt(300, 500));
        assertTrue(lighting.hasIndoorDarkness(TimeOfDayPreset.DAY));

        PerformanceMode.setEnabled(true);
        lighting.update(project);
        project.getInteractables().getFirst().setState("closed");
        lighting.update(project);
        PerformanceMode.setEnabled(false);
        lighting.update(project);
        assertEquals(0, lighting.daylightAt(300, 500), "Restore must use current portal state");
        assertTrue(project.getFog().getMask().copyBits().isEmpty());
    }

    public static DmProject room(String type, String state) {
        DmProject project = DmProject.builder().build();
        project.getImageLayers().add(DmProject.ImageLayer.builder().width(1000).height(1000).build());
        wall(project, 200, 200, 800, 200);
        wall(project, 800, 200, 800, 800);
        wall(project, 800, 800, 200, 800);
        wall(project, 200, 800, 200, 550);
        wall(project, 200, 450, 200, 200);
        project.getInteractables().add(DmProject.Interactable.builder()
                .id("portal").type(type).state(state).x1(200).y1(450).x2(200).y2(550).build());
        new FogService().ensureMask(project);
        return project;
    }

    @Test
    void performanceModeDoesNotChangeLightFogRevealOrConfiguredDisabledSettings() {
        DmProject project = room("door", "closed");
        project.getLighting().getLights().add(DmProject.LightSource.builder().id("torch")
                .x(500).y(500).range(200).revealMode(DmProject.RevealMode.WHILE_LIT).build());
        LightingEngine engine = new LightingEngine();
        engine.update(project);
        var reveal = (BitSet) engine.getLiveReveal().clone();
        var savedFog = project.getFog().getMask().copyBits();
        assertFalse(reveal.isEmpty());
        Tuning.apply(key -> key.equals("lighting.indoorDarkness") || key.equals("lighting.daylightThroughOpenings")
                ? "false" : null);
        PerformanceMode.setEnabled(true);
        engine.update(project);
        assertEquals(reveal, engine.getLiveReveal());
        assertEquals(savedFog, project.getFog().getMask().copyBits());
        PerformanceMode.setEnabled(false);
        engine.update(project);
        assertFalse(PerformanceMode.indoorDarkness());
        assertFalse(PerformanceMode.daylightThroughOpenings());
        assertFalse(Tuning.INDOOR_DARKNESS.get());
        assertFalse(Tuning.DAYLIGHT_THROUGH_OPENINGS.get());
        assertEquals(reveal, engine.getLiveReveal());
    }

    private static void wall(DmProject project, double x1, double y1, double x2, double y2) {
        project.getWalls().add(DmProject.WallSegment.builder().x1(x1).y1(y1).x2(x2).y2(y2).build());
    }

    @Test
    void enclosureIgnoresPortalStateAndDaytimeLightsOnlyRunIndoors() {
        DmProject project = room("door", "open");
        IndoorLighting lighting = new IndoorLighting();
        lighting.update(project);
        assertTrue(lighting.isInside(500, 500));
        assertFalse(lighting.isInside(100, 100));
        for (TimeOfDayPreset preset : TimeOfDayPreset.values()) {
            assertTrue(lighting.lightActive(preset, 500, 500));
            assertEquals(preset == TimeOfDayPreset.NIGHT, lighting.lightActive(preset, 100, 100));
        }
        long before = lighting.version();
        lighting.update(project);
        assertEquals(before, lighting.version());
        project.getWalls().remove(0);
        lighting.update(project);
        assertFalse(lighting.hasRooms());
        assertTrue(lighting.version() > before);
    }

    @Test
    void windowsAndOpenDoorsAdmitOccludedFadingSunlightWithoutRevealingFog() {
        DmProject project = room("window", "open");
        IndoorLighting lighting = new IndoorLighting();
        lighting.update(project);
        assertTrue(lighting.daylightAt(300, 500) > 0.5);
        assertEquals(1, lighting.daylightAt(400, 500), 0.001, "Sunlight has a bright inner reach");
        assertTrue(lighting.daylightAt(600, 500) > 0.7, "Sunlight should fade more gently than a candle");
        assertTrue(lighting.daylightAt(300, 500) > lighting.daylightAt(600, 500));
        assertEquals(0, lighting.daylightAt(300, 250), "Wall beside window must cast a shadow");
        assertTrue(project.getFog().getMask().copyBits().isEmpty());
        project.getInteractables().getFirst().setState("closed");
        lighting.update(project);
        assertEquals(0, lighting.daylightAt(300, 500));
        project.getInteractables().getFirst().setType("door");
        project.getInteractables().getFirst().setState("open");
        lighting.update(project);
        assertTrue(lighting.daylightAt(300, 500) > 0.5);
        wall(project, 400, 200, 400, 800);
        lighting.update(project);
        assertEquals(0, lighting.daylightAt(600, 500), "Interior wall must block daylight");
    }

    @Test
    void nearbyExteriorDoorsDoNotShineThroughEachOthersOpenings() {
        DmProject project = room("door", "open");
        // A second, narrower opening close enough for the first virtual source to see through it.
        project.getWalls().remove(3);
        wall(project, 200, 550, 200, 565);
        wall(project, 200, 585, 200, 800);
        DmProject.Interactable second = DmProject.Interactable.builder().id("second")
                .type("door").state("open").x1(200).y1(565).x2(200).y2(585).build();
        project.getInteractables().add(second);
        IndoorLighting lighting = new IndoorLighting();
        lighting.update(project);
        double throughSecond = lighting.daylightAt(245, 575);
        assertTrue(throughSecond > 0.9);
        // This point is visible through the second opening from the first source, but not from
        // the second source. It must remain dark, not acquire a cross-door shadow contribution.
        assertEquals(0, lighting.daylightAt(245, 625));
        project.getInteractables().getFirst().setState("closed");
        lighting.update(project);
        assertEquals(throughSecond, lighting.daylightAt(245, 575), 0.0001);
        assertEquals(0, lighting.daylightAt(245, 625));
    }

    @Test
    void neighbouringRoomOnlyBlocksSunlightWhenCloserThanOneTile() {
        for (double tile : new double[]{100, 150, 200}) {
            double scale = tile / 100;
            for (double gap : new double[]{99, 100, 101, 300}) {
                DmProject project = room("door", "open");
                project.getMap().getGrid().setPixelsPerCell(tile);
                project.getImageLayers().getFirst().setX(-300);
                project.getImageLayers().getFirst().setWidth(1300);
                // Two touching doors make up this entire side of the receiving room.
                project.getWalls().remove(4);
                project.getWalls().remove(3);
                DmProject.Interactable longDoor = project.getInteractables().getFirst();
                longDoor.setY1(200);
                longDoor.setY2(650);
                project.getInteractables().add(DmProject.Interactable.builder().id("short")
                        .state("closed").x1(200).y1(650).x2(200).y2(800).build());
                double neighbouringWall = 200 - gap;
                wall(project, neighbouringWall - 100, 200, neighbouringWall, 200);
                wall(project, neighbouringWall, 200, neighbouringWall, 800);
                wall(project, neighbouringWall, 800, neighbouringWall - 100, 800);
                wall(project, neighbouringWall - 100, 800, neighbouringWall - 100, 200);
                for (DmProject.WallSegment wall : project.getWalls()) {
                    wall.setX1(wall.getX1() * scale);
                    wall.setY1(wall.getY1() * scale);
                    wall.setX2(wall.getX2() * scale);
                    wall.setY2(wall.getY2() * scale);
                }
                for (DmProject.Interactable portal : project.getInteractables()) {
                    portal.setX1(portal.getX1() * scale);
                    portal.setY1(portal.getY1() * scale);
                    portal.setX2(portal.getX2() * scale);
                    portal.setY2(portal.getY2() * scale);
                }
                project.getFog().setMask(null);
                new FogService().ensureMask(project);
                IndoorLighting lighting = new IndoorLighting();
                lighting.update(project);
                String context = "tile=" + tile + ", gap=" + gap * scale;
                double lit = lighting.daylightAt(300 * scale, 425 * scale);
                if (gap < 100) {
                    assertEquals(0, lit, context);
                } else {
                    assertTrue(lit > 0.9, context);
                    wall(project, 400 * scale, 200 * scale, 400 * scale, 800 * scale);
                    lighting.update(project);
                    assertEquals(0, lighting.daylightAt(600 * scale, 425 * scale),
                            "Receiving room walls still block sunlight: " + context);
                }
                longDoor.setState("closed");
                lighting.update(project);
                assertEquals(0, lighting.daylightAt(300 * scale, 425 * scale));
            }
        }
    }

    @Test
    void interiorPortalsDoNotGenerateSunlight() {
        DmProject project = room("door", "closed");
        wall(project, 500, 200, 500, 450);
        wall(project, 500, 550, 500, 800);
        project.getInteractables().add(DmProject.Interactable.builder()
                .type("window").state("open").x1(500).y1(450).x2(500).y2(550).build());
        IndoorLighting lighting = new IndoorLighting();
        lighting.update(project);
        assertTrue(lighting.isInside(600, 500));
        assertEquals(0, lighting.daylightAt(600, 500));
        project.getInteractables().getFirst().setState("open");
        lighting.update(project);
        assertTrue(lighting.daylightAt(600, 500) > 0, "Open interior window can transmit exterior sunlight");
        project.getInteractables().getLast().setState("closed");
        lighting.update(project);
        assertEquals(0, lighting.daylightAt(600, 500));
    }

    @Test
    void rotatedGeometryUsesTheSameEnclosureAndApertureRules() {
        DmProject project = room("window", "open");
        for (DmProject.WallSegment wall : project.getWalls()) {
            double x1 = wall.getX1();
            double x2 = wall.getX2();
            wall.setX1(1000 - wall.getY1());
            wall.setY1(x1);
            wall.setX2(1000 - wall.getY2());
            wall.setY2(x2);
        }
        DmProject.Interactable portal = project.getInteractables().getFirst();
        portal.setX1(550);
        portal.setY1(200);
        portal.setX2(450);
        portal.setY2(200);
        IndoorLighting lighting = new IndoorLighting();
        lighting.update(project);
        assertTrue(lighting.isInside(500, 500));
        assertTrue(lighting.daylightAt(500, 300) > 0.5);
        assertEquals(0, lighting.daylightAt(750, 300));
    }

    @Test
    void roomsAtMapEdgeReceiveExteriorSunlightAndResamplingInvalidatesCache() {
        DmProject project = room("window", "open");
        project.getImageLayers().getFirst().setX(200);
        project.getImageLayers().getFirst().setY(200);
        project.getImageLayers().getFirst().setWidth(600);
        project.getImageLayers().getFirst().setHeight(600);
        new FogService().ensureMask(project);
        IndoorLighting lighting = new IndoorLighting();
        lighting.update(project);
        // Existing masks grow but do not shrink; explicitly recreate for the new bounds.
        project.getFog().setMask(null);
        new FogService().ensureMask(project);
        lighting.update(project);
        assertTrue(lighting.daylightAt(300, 500) > 0.5);
        long before = lighting.version();
        project.getFog().getMask().resample(5);
        lighting.update(project);
        assertTrue(lighting.version() > before);
        assertTrue(lighting.daylightAt(300, 500) > 0.5);
    }
}
