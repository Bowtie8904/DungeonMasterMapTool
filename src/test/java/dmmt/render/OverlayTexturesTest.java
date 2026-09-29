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

