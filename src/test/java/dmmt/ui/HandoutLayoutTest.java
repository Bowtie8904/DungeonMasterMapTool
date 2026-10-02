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

    @Test
    void normalOutputUsesTheWholeScreenAtEveryRotation() {
        for (int rotation : List.of(0, 90, 180, 270)) {
            var panels = HandoutLayout.outputPanels(1920, 1080, rotation, false);
            assertEquals(1, panels.size());
            assertEquals(new Rect(0, 0, 1920, 1080), panels.getFirst().bounds());
            assertEquals(rotation, panels.getFirst().rotation());
        }
        assertTrue(HandoutLayout.outputPanels(0, 1080, 0, true).isEmpty());
        assertTrue(HandoutLayout.outputPanels(1920, 0, 0, false).isEmpty());
    }

    @Test
    void mirroredPanelsCoverTheScreenAndFaceOppositeSidesAtEveryRotation() {
        for (Size screen : List.of(new Size(1920, 1080), new Size(1080, 1920), new Size(801, 601))) {
            for (int rotation : List.of(0, 90, 180, 270)) {
                var panels = HandoutLayout.outputPanels(screen.width(), screen.height(), rotation, true);
                assertEquals(2, panels.size());
                List<Rect> bounds = panels.stream().map(HandoutLayout.Panel::bounds).toList();
                assertInside(bounds, screen.width(), screen.height());
                assertNoOverlap(bounds);
                assertEquals(screen.width() * screen.height(), area(bounds), 1e-6);
                assertEquals(rotation, panels.getFirst().rotation());
                assertEquals((rotation + 180) % 360, panels.getLast().rotation());
                Size full = HandoutLayout.layoutSize(screen.width(), screen.height(), rotation);
                for (var panel : panels) {
                    Size space = HandoutLayout.layoutSize(panel.bounds().width(), panel.bounds().height(),
                            panel.rotation());
                    assertEquals(full.width(), space.width(), 1e-6);
                    assertEquals(full.height() / 2, space.height(), 1e-6);
                }
            }
        }
        assertEquals(new Rect(0, 540, 1920, 540),
                HandoutLayout.outputPanels(1920, 1080, 0, true).getFirst().bounds());
        assertEquals(new Rect(0, 0, 960, 1080),
                HandoutLayout.outputPanels(1920, 1080, 90, true).getFirst().bounds());
        assertEquals(HandoutLayout.outputPanels(1920, 1080, 270, true),
                HandoutLayout.outputPanels(1920, 1080, -90, true));
    }

    @Test
    void mirroredOutputReflowsInsteadOfShrinkingTheFullScreenLayout() {
        List<Size> images = List.of(new Size(3000, 1000), new Size(3000, 1000));
        List<Rect> full = HandoutLayout.compute(images, 1920, 1080);
        assertTrue(full.get(1).y() > full.get(0).y());

        var panel = HandoutLayout.outputPanels(1920, 1080, 0, true).getFirst();
        Size space = HandoutLayout.layoutSize(panel.bounds().width(), panel.bounds().height(), panel.rotation());
        List<Rect> half = HandoutLayout.compute(images, space.width(), space.height());
        assertTrue(half.get(1).x() > half.get(0).x());
        assertEquals(half.get(0).y(), half.get(1).y(), 1e-6);
        assertInside(half, space.width(), space.height());
        assertNoOverlap(half);
        assertTrue(area(half) > area(full) / 4);
    }

    @Test
    void mirroredLayoutsKeepMixedImagesInsideEachHalfWithoutDistortion() {
        List<Size> images = List.of(new Size(1600, 900), new Size(300, 1200), new Size(1000, 1000));
        for (int rotation : List.of(0, 90, 180, 270)) {
            List<Rect> firstLayout = null;
            for (var panel : HandoutLayout.outputPanels(1920, 1080, rotation, true)) {
                Size space = HandoutLayout.layoutSize(panel.bounds().width(), panel.bounds().height(),
                        panel.rotation());
                List<Rect> rects = HandoutLayout.compute(images, space.width(), space.height());
                assertEquals(images.size(), rects.size());
                assertInside(rects, space.width(), space.height());
                assertNoOverlap(rects);
                for (int i = 0; i < images.size(); i++) {
                    assertEquals(images.get(i).width() / images.get(i).height(),
                            rects.get(i).width() / rects.get(i).height(), 1e-6);
                }
                if (firstLayout == null) {
                    firstLayout = rects;
                } else {
                    assertEquals(firstLayout, rects);
                }
            }
        }
    }
}
