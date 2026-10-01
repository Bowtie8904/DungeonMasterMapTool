package dmmt.ui;

import dmmt.ui.HandoutLayout.Rect;
import dmmt.ui.HandoutLayout.Size;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandoutLayoutTest {

    private static double area(List<Rect> rects) {
        return rects.stream().mapToDouble(r -> r.width() * r.height()).sum();
    }

    private static void assertInside(List<Rect> rects, double w, double h) {
        for (Rect r : rects) {
            assertTrue(r.x() >= -1e-6 && r.y() >= -1e-6 && r.x() + r.width() <= w + 1e-6
                    && r.y() + r.height() <= h + 1e-6, "outside: " + r);
        }
    }

    private static void assertNoOverlap(List<Rect> rects) {
        for (int i = 0; i < rects.size(); i++) {
            for (int j = i + 1; j < rects.size(); j++) {
                Rect a = rects.get(i);
                Rect b = rects.get(j);
                boolean apart = a.x() + a.width() <= b.x() + 1e-6 || b.x() + b.width() <= a.x() + 1e-6
                        || a.y() + a.height() <= b.y() + 1e-6 || b.y() + b.height() <= a.y() + 1e-6;
                assertTrue(apart, "overlap: " + a + " / " + b);
            }
        }
    }

    @Test
    void singleImageFillsTheAreaKeepingAspectRatio() {
        List<Rect> rects = HandoutLayout.compute(List.of(new Size(1000, 500)), 1920, 1080);

        assertEquals(1, rects.size());
        Rect r = rects.get(0);
        assertEquals(2.0, r.width() / r.height(), 1e-6);
        assertTrue(r.width() > 1920 * 0.95);
        assertEquals(1920 / 2.0, r.x() + r.width() / 2, 1e-6);
    }

    @Test
    void twoVeryWideImagesAreStacked() {
        List<Rect> rects = HandoutLayout.compute(List.of(new Size(3000, 1000), new Size(3000, 1000)), 1920, 1080);

        assertEquals(2, rects.size());
        assertTrue(rects.get(1).y() > rects.get(0).y());
        assertInside(rects, 1920, 1080);
        assertNoOverlap(rects);
    }

    @Test
    void twoTallImagesAreSideBySide() {
        List<Rect> rects = HandoutLayout.compute(List.of(new Size(600, 900), new Size(600, 900)), 1920, 1080);

        assertTrue(rects.get(1).x() > rects.get(0).x());
        assertEquals(rects.get(0).y(), rects.get(1).y(), 1e-6);
    }

    @Test
    void manyImagesStayInsideWithoutOverlapAndKeepTheirAspectRatio() {
        List<Size> images = List.of(new Size(800, 600), new Size(600, 800), new Size(1000, 1000),
                new Size(1600, 900), new Size(500, 700));

        List<Rect> rects = HandoutLayout.compute(images, 1920, 1080);

        assertEquals(5, rects.size());
        assertInside(rects, 1920, 1080);
        assertNoOverlap(rects);
        assertTrue(area(rects) > 1920 * 1080 * 0.4);
        for (int i = 0; i < images.size(); i++) {
            assertEquals(images.get(i).width() / images.get(i).height(), rects.get(i).width() / rects.get(i).height(),
                    1e-6);
        }
    }

    @Test
    void removingAnImageRearrangesTheRest() {
        List<Size> three = List.of(new Size(1000, 1000), new Size(1000, 1000), new Size(1000, 1000));
        List<Size> two = three.subList(0, 2);

        assertEquals(3, HandoutLayout.compute(three, 1920, 1080).size());
        List<Rect> rearranged = HandoutLayout.compute(two, 1920, 1080);
        assertEquals(2, rearranged.size());
        assertTrue(rearranged.get(0).width() > HandoutLayout.compute(three, 1920, 1080).get(0).width());
        assertTrue(HandoutLayout.compute(List.of(), 1920, 1080).isEmpty());
    }

    @Test
    void rotationSwapsTheLayoutSpaceAndHitTestingFollowsIt() {
        Size layout = HandoutLayout.layoutSize(1920, 1080, 90);
        assertEquals(1080, layout.width());
        assertEquals(1920, layout.height());

        List<Rect> rects = HandoutLayout.compute(List.of(new Size(100, 100), new Size(100, 100)), 1080, 1920);
        // Two squares stacked in the 1080x1920 layout space; rotated by 90 degrees they sit side by side.
        int hitRight = HandoutLayout.hitTest(rects, 1920 / 2.0 + 300, 1080 / 2.0, 90, 1920, 1080);
        int hitLeft = HandoutLayout.hitTest(rects, 1920 / 2.0 - 300, 1080 / 2.0, 90, 1920, 1080);

        assertTrue(hitRight >= 0 && hitLeft >= 0 && hitRight != hitLeft);
        assertEquals(-1, HandoutLayout.hitTest(rects, 5, 5, 90, 1920, 1080));
    }

    @Test
    void slimImageSitsNextToAStackOfWideImages() {
        List<Rect> rects = HandoutLayout.compute(
                List.of(new Size(1600, 900), new Size(1600, 900), new Size(300, 1200)), 1920, 1080);
        assertEquals(3, rects.size());
        assertEquals(rects.get(0).width(), rects.get(1).width(), 1e-6);
        assertTrue(rects.get(0).y() < rects.get(1).y());
        assertTrue(rects.get(2).x() >= rects.get(0).x() + rects.get(0).width() - 1e-6
                || rects.get(2).x() + rects.get(2).width() <= rects.get(0).x() + 1e-6);
        assertTrue(rects.get(2).height() > rects.get(0).height() * 1.5);
        assertNoOverlap(rects);
        assertInside(rects, 1920, 1080);
    }
}
