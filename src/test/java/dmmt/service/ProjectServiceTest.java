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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectServiceTest {
    @Test
    void thunderstormIntervalSurvivesSaveAndCopyAndLegacyWeatherGetsDefault() throws IOException {
        ProjectService service = new ProjectService();
        DmProject project = DmProject.builder().build();
        project.getWeather().setType("thunderstorm");
        project.getWeather().setIntensity(0.8);
        project.getWeather().setLightningIntervalSeconds(9);
        Path file = tempDir.resolve("storm.dmmap");
        service.save(file, project);
        assertEquals(project.getWeather(), service.load(file).getWeather());
        DmProject copy = service.copy(project);
        project.getWeather().setLightningIntervalSeconds(30);
        assertEquals(9, copy.getWeather().getLightningIntervalSeconds());
        Files.writeString(file, "{\"map\":{},\"weather\":{\"type\":\"rain\",\"intensity\":0.4}}");
        assertEquals(Tuning.WEATHER_LIGHTNING_INTERVAL.get(), service.load(file).getWeather().getLightningIntervalSeconds());
    }

    @Test
    void savingLegacyProjectLoadedBeforeIndexingRetainsTheIndexedIdentity() throws IOException {
        Path file = tempDir.resolve("legacy.dmmap");
        Files.writeString(file, "{\"map\":{}}");
        ProjectService service = new ProjectService();
        DmProject project = service.load(file);
        String indexedId = service.ensureId(file);
        service.save(file, project);
        assertEquals(indexedId, project.getId());
        assertEquals(indexedId, service.load(file).getId());
    }

    @Test
    void legacyStoredAmbienceIsIgnoredAndRemovedWhenMapIsSaved() throws IOException {
        Path file = tempDir.resolve("legacy-audio.dmmap");
        Files.writeString(file, """
                {"map": {}, "audio": {"categoryId": "combat", "effectIds": ["rain"]}}
                """);

        ProjectService service = new ProjectService();
        DmProject project = service.load(file);
        service.save(file, project);

        assertFalse(Files.readString(file).contains("\"audio\""));
    }

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

        assertEquals(project.getId(), copy.getId(), "A frozen snapshot is still the same map");
        assertEquals(project.getFog().getMask().copyBits(), copy.getFog().getMask().copyBits());
        assertEquals(4, copy.getOverlays().get(0).getPoints().size());
        assertEquals(false, copy.getOverlays().get(0).isPlayerVisible());

        project.getFog().getMask().applyRect(0, 0, 2000, 2000, false);
        project.getOverlays().clear();
        assertTrue(!copy.getFog().getMask().copyBits().isEmpty(), "Frozen copy must not follow later edits");
        assertEquals(1, copy.getOverlays().size());
    }

    @Test
    void imageLayerLockDefaultsAndPersists() throws IOException {
        DmProject custom = DmProject.builder().build();
        assertEquals(false, custom.getMap().imageLayersLockedOrDefault(), "New custom maps start unlocked");

        DmProject legacyImport = DmProject.builder().build();
        legacyImport.getMap().setSourceType("dd2vtt");
        assertTrue(legacyImport.getMap().imageLayersLockedOrDefault(), "Older imported saves default to locked");

        legacyImport.getMap().setImageLayersLocked(false);
        Path projectFile = tempDir.resolve("lock").resolve("map.dmmap");
        ProjectService service = new ProjectService();
        service.save(projectFile, legacyImport);
        assertEquals(false, service.load(projectFile).getMap().imageLayersLockedOrDefault());
    }

    @Test
    void ambientBrightnessIsPerPresetAndPersists() throws IOException {
        DmProject project = DmProject.builder().build();
        project.getLighting().putAmbientBrightness("NIGHT", 0.4);
        project.getLighting().putAmbientBrightness("dusk", -0.2);
        assertEquals(0.0, project.getLighting().ambientBrightnessFor("DAWN"));

        Path projectFile = tempDir.resolve("ambient").resolve("map.dmmap");
        ProjectService service = new ProjectService();
        service.save(projectFile, project);
        DmProject loaded = service.load(projectFile);
        assertEquals(0.4, loaded.getLighting().ambientBrightnessFor("NIGHT"), 1e-9);
        assertEquals(-0.2, loaded.getLighting().ambientBrightnessFor("DUSK"), 1e-9);
        assertEquals(0.4, service.copy(project).getLighting().ambientBrightnessFor("NIGHT"), 1e-9);

        double night = dmmt.lighting.TimeOfDayPreset.NIGHT.darkness();
        assertEquals(night * 0.6, dmmt.lighting.TimeOfDayPreset.NIGHT.darkness(0.4), 1e-9);
        assertEquals(0.0, dmmt.lighting.TimeOfDayPreset.NIGHT.darkness(1.0), 1e-9);

        project.getLighting().putAmbientBrightness("NIGHT", 0.0);
        assertTrue(!project.getLighting().getAmbientBrightness().containsKey("NIGHT"));
    }}
