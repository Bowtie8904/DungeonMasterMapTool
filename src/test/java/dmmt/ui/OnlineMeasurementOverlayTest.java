package dmmt.ui;

import dmmt.online.OnlineMeasurement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OnlineMeasurementOverlayTest {
    @Test
    void deleteButtonUsesScreenSizeAtEveryZoomAndPicksTopmost() {
        var first = new OnlineMeasurement("first", "alice", "distance", 0, 0, 100, 200);
        var last = new OnlineMeasurement("last", "bob", "distance", 0, 0, 100, 200);
        for (double zoom : new double[]{0.2, 1, 4}) {
            assertEquals(last, OnlineMeasurementOverlay.pickDelete(List.of(first, last),
                    100 + 12 / zoom, 200 + 12 / zoom, zoom));
            assertNull(OnlineMeasurementOverlay.pickDelete(List.of(first), 100, 200, zoom));
            assertNull(OnlineMeasurementOverlay.pickDelete(List.of(first),
                    100 + 22 / zoom, 200 + 12 / zoom, zoom));
        }
    }
}
