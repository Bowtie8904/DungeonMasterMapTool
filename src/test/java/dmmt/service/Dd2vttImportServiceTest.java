package dmmt.service;

import dmmt.model.DmProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Dd2vttImportServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void importsDd2vttWithImageLargerThanDefaultJacksonStringLimit() throws Exception {
        // Jackson's default max string length is 20,000,000 chars; exceed it with a valid base64 payload.
        BufferedImage buffered = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        ImageIO.write(buffered, "png", png);
        byte[] padded = java.util.Arrays.copyOf(png.toByteArray(), 16_000_000);
        String base64 = java.util.Base64.getEncoder().encodeToString(padded);
        assertTrue(base64.length() > 20_000_000);

        Path dd2vtt = tempDir.resolve("huge.dd2vtt");
        Files.writeString(dd2vtt, "{\"image\":\"" + base64 + "\",\"pixels_per_grid\":100}");
        Path projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);

        DmProject project = new Dd2vttImportService().importToProject(dd2vtt, projectDir);

        assertTrue(Files.exists(projectDir.resolve(project.getMap().getImagePath())));
    }

    @Test
    void importsAndCopiesDd2vttDependencies() throws Exception {
        Path dd2vtt = writeSampleDd2vtt();
        Path projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);

        Dd2vttImportService service = new Dd2vttImportService();
        DmProject project = service.importToProject(dd2vtt, projectDir);

        assertEquals("dd2vtt", project.getMap().getSourceType());
        assertEquals(Boolean.TRUE, project.getMap().getImageLayersLocked());
        assertNull(project.getMap().getSourcePath());
        assertTrue(Files.notExists(projectDir.resolve("imports").resolve("encounter").resolve("encounter.dd2vtt")));
        assertFalse(project.getImageLayers().isEmpty());
        assertEquals(1, project.getWalls().size());
        assertEquals(1, project.getLighting().getLights().size());
        assertEquals(1, project.getInteractables().size());
        assertTrue(project.getLighting().getLights().get(0).getFlicker().isEnabled());
    }

    @Test
    void zeroImportFlickerDepthTurnsFlickerOff() throws Exception {
        Path dd2vtt = writeSampleDd2vtt();
        Path projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        Tuning.apply(key -> "import.dd2vtt.lightFlicker".equals(key) ? "0" : null);
        try {
            DmProject project = new Dd2vttImportService().importToProject(dd2vtt, projectDir);
            assertFalse(project.getLighting().getLights().get(0).getFlicker().isEnabled());
        } finally {
            Tuning.reset();
        }
    }

    private Path writeSampleDd2vtt() throws Exception {
        Path sourceDir = tempDir.resolve("usb");
        Files.createDirectories(sourceDir);

        Path image = sourceDir.resolve("map.png");
        BufferedImage buffered = new BufferedImage(120, 80, BufferedImage.TYPE_INT_ARGB);
        ImageIO.write(buffered, "png", image.toFile());

        Path dd2vtt = sourceDir.resolve("encounter.dd2vtt");
        String json = """
                {
                  "image":"map.png",
                  "pixels_per_grid":140,
                  "line_of_sight":[[0,0,10,10]],
                  "lights":[{"id":"l1","x":5,"y":6,"range":90}],
                  "portals":[{"id":"d1","type":"door","bounds":[1,2,3,4],"closed":true}]
                }
                """;
        Files.writeString(dd2vtt, json);
        return dd2vtt;
    }
}
