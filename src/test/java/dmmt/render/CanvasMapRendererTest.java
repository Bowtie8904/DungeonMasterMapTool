package dmmt.render;

import dmmt.model.DmProject;
import dmmt.service.Tuning;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CanvasMapRendererTest {
    @AfterEach
    void resetTuning() {
        Tuning.reset();
    }

    @Test
    void gridOpacityInvalidatesCachedBase() {
        DmProject project = DmProject.builder().build();
        DmProject.CameraState camera = DmProject.CameraState.builder().build();
        long before = CanvasMapRenderer.baseSignature(project, null, 800, 600, camera,
                CanvasMapRenderer.GridMode.OVERLAY);

        Tuning.apply(key -> key.equals(Tuning.GRID_OPACITY.key()) ? "0.5" : null);

        assertNotEquals(before, CanvasMapRenderer.baseSignature(project, null, 800, 600, camera,
                CanvasMapRenderer.GridMode.OVERLAY));
    }

    @Test
    void gridModeInvalidatesCachedBaseEvenWithoutCameraOrMapChanges() {
        DmProject project = DmProject.builder().build();
        DmProject.CameraState camera = DmProject.CameraState.builder().build();

        long hidden = CanvasMapRenderer.baseSignature(project, null, 800, 600, camera,
                CanvasMapRenderer.GridMode.HIDDEN);
        long overlay = CanvasMapRenderer.baseSignature(project, null, 800, 600, camera,
                CanvasMapRenderer.GridMode.OVERLAY);
        long background = CanvasMapRenderer.baseSignature(project, null, 800, 600, camera,
                CanvasMapRenderer.GridMode.BACKGROUND);

        assertNotEquals(hidden, overlay);
        assertNotEquals(background, overlay);
        assertNotEquals(background, hidden);
        assertEquals(hidden, CanvasMapRenderer.baseSignature(project, null, 800, 600, camera,
                CanvasMapRenderer.GridMode.HIDDEN));
    }
}
