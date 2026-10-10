package dmmt.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverlayTexturesTest {
    @Test
    void unknownKindsFallBackToFlat() {
        assertEquals(OverlayTextures.NONE, OverlayTextures.normalize(null));
        assertEquals(OverlayTextures.NONE, OverlayTextures.normalize("plasma"));
        assertFalse(OverlayTextures.isAnimated("plasma"));
        assertTrue(OverlayTextures.layers(OverlayTextures.NONE).isEmpty());
    }

    @Test
    void everyTextureHasVisiblePixelsAndTilesSeamlessly() {
        int size = 64;
        for (String kind : OverlayTextures.KINDS) {
            if (!OverlayTextures.isAnimated(kind)) {
                continue;
            }
            assertNotNull(OverlayTextures.defaultColor(kind), kind);
            assertFalse(OverlayTextures.layers(kind).isEmpty(), kind);
            int[] pixels = OverlayTextures.generate(kind, 0x808080, size);
            boolean anyVisible = false;
            for (int p : pixels) {
                anyVisible |= (p >>> 24) > 40;
            }
            assertTrue(anyVisible, kind + " has visible pixels");
            assertSeamless(kind, pixels, size);
        }
    }

    @Test
    void staticTexturesDoNotRequestContinuousRedraws() {
        assertFalse(OverlayTextures.isMoving(OverlayTextures.NONE));
        assertFalse(OverlayTextures.isMoving(OverlayTextures.BLOOD));
        assertFalse(OverlayTextures.isMoving(OverlayTextures.WEB));
        assertTrue(OverlayTextures.isMoving(OverlayTextures.FIRE));
        assertTrue(OverlayTextures.isMoving(OverlayTextures.LIGHTNING));
    }

    @Test
    void arcaneWanderingIsSmoothRepeatableAndChangesDirection() {
        OverlayTextures.Layer layer = new OverlayTextures.Layer(0.004, -0.002, 0.75, 0.95);
        for (boolean horizontal : new boolean[]{true, false}) {
            assertEquals(0, OverlayTextures.layerPhase(OverlayTextures.ARCANE, layer, 0, horizontal));
            boolean forward = false;
            boolean backward = false;
            double previous = 0;
            for (int frame = 1; frame <= 3600; frame++) {
                double seconds = frame / 60.0;
                double phase = OverlayTextures.layerPhase(OverlayTextures.ARCANE, layer, seconds, horizontal);
                assertEquals(phase, OverlayTextures.layerPhase(OverlayTextures.ARCANE, layer, seconds, horizontal));
                double delta = phase - previous;
                delta -= Math.round(delta);
                assertTrue(Math.abs(delta) < 0.003, "wandering should not jump between frames");
                forward |= delta > 0.00001;
                backward |= delta < -0.00001;
                previous = phase;
            }
            assertTrue(forward && backward, "wandering should reverse direction on both axes");
        }
    }

    @Test
    void wanderingHonorsStationaryLayersAndPreservesOtherTextureMotion() {
        OverlayTextures.Layer still = new OverlayTextures.Layer(0, 0, 1, 1);
        OverlayTextures.Layer moving = new OverlayTextures.Layer(-0.02, 0.04, 1, 1);
        for (double seconds : new double[]{0, 2.5, 17, 10000}) {
            assertEquals(0, OverlayTextures.layerPhase(OverlayTextures.ARCANE, still, seconds, true));
            assertEquals(0, OverlayTextures.layerPhase(OverlayTextures.ARCANE, still, seconds, false));
            for (boolean horizontal : new boolean[]{true, false}) {
                double offset = seconds * (horizontal ? moving.vx() : moving.vy());
                assertEquals(offset - Math.floor(offset),
                        OverlayTextures.layerPhase(OverlayTextures.WATER, moving, seconds, horizontal), 1e-12);
            }
        }
    }

    @Test
    void arcaneGlyphsFillTheTileAsSmallDistinctMarks() {
        int size = 64;
        int[] pixels = OverlayTextures.generate(OverlayTextures.ARCANE, 0xB36BFF, size);
        for (int cellY = 0; cellY < 4; cellY++) {
            for (int cellX = 0; cellX < 4; cellX++) {
                int strongPixels = 0;
                for (int y = cellY * size / 4; y < (cellY + 1) * size / 4; y++) {
                    for (int x = cellX * size / 4; x < (cellX + 1) * size / 4; x++) {
                        if ((pixels[y * size + x] >>> 24) > 160) {
                            strongPixels++;
                        }
                    }
                }
                assertTrue(strongPixels >= 4, "each compact rune cell should contain visible glyph strokes");
            }
        }
        assertFalse(OverlayTextures.settingsDefaults().containsKey("texture.arcane.layer2.scale"));
    }

    @Test
    void newTexturesHaveExpectedEdgeDefaultsAndAreConfigurable() {
        for (String kind : new String[]{OverlayTextures.POISON, OverlayTextures.NECROTIC, OverlayTextures.PORTAL}) {
            assertTrue(OverlayTextures.isSoft(kind), kind);
        }
        for (String kind : new String[]{OverlayTextures.SWAMP, OverlayTextures.RUBBLE, OverlayTextures.THORNS,
                OverlayTextures.FORCE}) {
            assertFalse(OverlayTextures.isSoft(kind), kind);
        }
        assertTrue(OverlayTextures.settingsDefaults().containsKey("texture.force.softEdges"));
        try {
            OverlayTextures.applySettings(key -> switch (key) {
                case "texture.force.softEdges" -> "true";
                case "texture.poison.softEdges" -> "false";
                default -> null;
            });
            assertTrue(OverlayTextures.isSoft(OverlayTextures.FORCE));
            assertFalse(OverlayTextures.isSoft(OverlayTextures.POISON));
        } finally {
            OverlayTextures.applySettings(key -> null);
        }
        assertFalse(OverlayTextures.isSoft(OverlayTextures.FORCE));
    }

    @Test
    void textureColorFollowsShapeColor() {
        int[] red = OverlayTextures.generate(OverlayTextures.WATER, 0xFF0000, 32);
        int[] blue = OverlayTextures.generate(OverlayTextures.WATER, 0x0000FF, 32);
        long redSum = 0;
        long blueSum = 0;
        for (int i = 0; i < red.length; i++) {
            redSum += (red[i] >> 16) & 0xFF;
            blueSum += (blue[i] >> 16) & 0xFF;
        }
        assertTrue(redSum > blueSum * 2, "red channel is much stronger for a red tint");
    }

    private static void assertSeamless(String kind, int[] pixels, int size) {
        long horizontalSeam = 0;
        long interior = 0;
        for (int y = 0; y < size; y++) {
            horizontalSeam += diff(pixels[y * size + size - 1], pixels[y * size]);
            interior += diff(pixels[y * size + size / 2], pixels[y * size + size / 2 + 1]);
        }
        long verticalSeam = 0;
        for (int x = 0; x < size; x++) {
            verticalSeam += diff(pixels[(size - 1) * size + x], pixels[x]);
        }
        // The wrap-around edge must not differ more than a typical neighbouring pixel pair does (with slack).
        assertTrue(horizontalSeam <= interior * 3 + size * 60L, kind + " horizontal seam");
        assertTrue(verticalSeam <= interior * 3 + size * 60L, kind + " vertical seam");
    }

    private static int diff(int a, int b) {
        return Math.abs((a >>> 24) - (b >>> 24)) + Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF))
                + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF)) + Math.abs((a & 0xFF) - (b & 0xFF));
    }
}
