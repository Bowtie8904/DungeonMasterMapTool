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

    @Test
    void copyIsIndependentAndKeepsFogAndOverlays() throws IOException {
        DmProject project = DmProject.builder().build();
        project.getOverlays().add(DmProject.OverlayShape.builder().id("o").type("brush")
                .points(new java.util.ArrayList<>(java.util.List.of(1.0, 2.0, 3.0, 4.0))).playerVisible(false).build());
        new FogService().ensureMask(project);
        project.getFog().getMask().applyRect(0, 0, 200, 200, true);

        ProjectService service = new ProjectService();
        DmProject copy = service.copy(project);

        assertEquals(project.getFog().getMask().copyBits(), copy.getFog().getMask().copyBits());
        assertEquals(4, copy.getOverlays().get(0).getPoints().size());
        assertEquals(false, copy.getOverlays().get(0).isPlayerVisible());

        project.getFog().getMask().applyRect(0, 0, 2000, 2000, false);
        project.getOverlays().clear();
        assertTrue(!copy.getFog().getMask().copyBits().isEmpty(), "Frozen copy must not follow later edits");
        assertEquals(1, copy.getOverlays().size());
    }}
