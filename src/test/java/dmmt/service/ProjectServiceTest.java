package dmmt.service;

import dmmt.model.DmProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void savesAndCopiesExternalImageAssets() throws IOException {
        Path external = tempDir.resolve("external");
        Files.createDirectories(external);
        Path image = external.resolve("tile.png");
        BufferedImage buffered = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        ImageIO.write(buffered, "png", image.toFile());

        DmProject project = DmProject.builder().build();
        project.getImageLayers().add(DmProject.ImageLayer.builder()
                .id("layer-1")
                .path(image.toAbsolutePath().toString())
                .width(64)
                .height(64)
                .build());

        Path projectFile = tempDir.resolve("project").resolve("map.dmmap");
        ProjectService service = new ProjectService();
        service.save(projectFile, project);

        String savedPath = project.getImageLayers().get(0).getPath();
        assertTrue(savedPath.startsWith("assets\\custom"));
        Path copiedPath = projectFile.getParent().resolve(savedPath);
        assertTrue(Files.exists(copiedPath));

        DmProject loaded = service.load(projectFile);
        assertEquals(savedPath, loaded.getImageLayers().get(0).getPath());
    }
}
