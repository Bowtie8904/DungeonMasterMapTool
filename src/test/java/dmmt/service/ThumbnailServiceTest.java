package dmmt.service;

import dmmt.model.DmProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class ThumbnailServiceTest {
    @TempDir Path directory;

    private Path image(String name, int color) throws Exception {
        Path file = directory.resolve(name);
        BufferedImage image = new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(new java.awt.Color(color));
        graphics.fillRect(0, 0, 32, 24);
        graphics.dispose();
        ImageIO.write(image, "png", file.toFile());
        return file;
    }

    private DmProject project(Path image) {
        DmProject project = new DmProject();
        project.getImageLayers().add(DmProject.ImageLayer.builder().id("base")
                .path(image.getFileName().toString()).width(32).height(24).build());
        return project;
    }

    @Test
    void savesSkipUnchangedPixelsAcrossServiceInstancesAndRecoverMissingThumbnail() throws Exception {
        Path asset = image("map.png", 0xff0000);
        Path map = directory.resolve("map.dmmap");
        DmProject project = project(asset);
        new ProjectService().save(map, project);
        Path thumbnail = ThumbnailService.thumbnailFile(map);
        byte[] original = Files.readAllBytes(thumbnail);
        FileTime marker = FileTime.fromMillis(123456789000L);
        Files.setLastModifiedTime(thumbnail, marker);

        project.getViews().getDmCamera().setX(900);
        project.getFog().setEnabled(false);
        project.getLighting().setTimeOfDayPreset("NIGHT");
        new ProjectService().save(map, project);
        assertEquals(marker, Files.getLastModifiedTime(thumbnail));
        assertArrayEquals(original, new ProjectService().loadOrCreateThumbnail(map));
        assertEquals(marker, Files.getLastModifiedTime(thumbnail), "Lazy browser reads also reuse the signature");
        assertTrue(Files.isRegularFile(directory.resolve(ThumbnailService.SIGNATURE_FILE)));

        Files.delete(thumbnail);
        assertArrayEquals(original, new ProjectService().loadOrCreateThumbnail(map));
        assertTrue(Files.isRegularFile(thumbnail));
    }

    @Test
    void assetChangesAndMissingAssetsInvalidateDurableSignature() throws Exception {
        Path asset = image("map.png", 0xff0000);
        Path map = directory.resolve("map.dmmap");
        Files.writeString(map, "{}");
        DmProject project = project(asset);
        ThumbnailService service = new ThumbnailService();
        assertTrue(service.write(map, project));
        byte[] red = Files.readAllBytes(ThumbnailService.thumbnailFile(map));
        String before = service.inputSignature(project, map);
        image("map.png", 0x0000ff);
        Files.setLastModifiedTime(asset, FileTime.fromMillis(987654321000L));
        assertNotEquals(before, service.inputSignature(project, map));
        assertTrue(service.write(map, project));
        assertFalse(Arrays.equals(red, Files.readAllBytes(ThumbnailService.thumbnailFile(map))));

        Files.delete(asset);
        assertFalse(service.write(map, project));
        assertFalse(Files.exists(ThumbnailService.thumbnailFile(map)));
        image("map.png", 0xff0000);
        assertTrue(service.write(map, project));
    }

    @Test
    void onlyVisibleImagesAndTheirTransformsAndOrderParticipate() throws Exception {
        Path map = directory.resolve("map.dmmap");
        DmProject project = project(image("a.png", 0xff0000));
        DmProject.ImageLayer hidden = DmProject.ImageLayer.builder()
                .path("missing.png").width(32).height(24).visible(false).build();
        project.getImageLayers().add(hidden);
        ThumbnailService service = new ThumbnailService();
        String initial = service.inputSignature(project, map);
        project.getMap().setRotationQuarterTurns(1);
        assertNotEquals(initial, service.inputSignature(project, map));
        project.getMap().setRotationQuarterTurns(0);
        hidden.setX(123);
        assertEquals(initial, service.inputSignature(project, map));
        hidden.setVisible(true);
        assertNotEquals(initial, service.inputSignature(project, map));
        project.getImageLayers().remove(hidden);
        project.getImageLayers().getFirst().setRotationDeg(90);
        assertNotEquals(initial, service.inputSignature(project, map));
        project.getImageLayers().getFirst().setRotationDeg(0);
        project.getImageLayers().getFirst().setX(12);
        assertNotEquals(initial, service.inputSignature(project, map));
        project.getImageLayers().add(DmProject.ImageLayer.builder()
                .path(image("b.png", 0x0000ff).getFileName().toString()).width(32).height(24).build());
        String ordered = service.inputSignature(project, map);
        java.util.Collections.reverse(project.getImageLayers());
        assertNotEquals(ordered, service.inputSignature(project, map), "Equal-z ordering is stable and significant");
    }
}
