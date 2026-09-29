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
import static org.junit.jupiter.api.Assertions.assertTrue;

class Dd2vttImportServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void importsAndCopiesDd2vttDependencies() throws Exception {
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

        Path projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);

        Dd2vttImportService service = new Dd2vttImportService();
        DmProject project = service.importToProject(dd2vtt, projectDir);

        assertEquals("dd2vtt", project.getMap().getSourceType());
        assertTrue(project.getMap().getSourcePath().contains("imports"));
        assertTrue(Files.exists(projectDir.resolve(project.getMap().getSourcePath())));
        assertFalse(project.getImageLayers().isEmpty());
        assertEquals(1, project.getWalls().size());
        assertEquals(1, project.getLighting().getLights().size());
        assertEquals(1, project.getInteractables().size());
    }
}
