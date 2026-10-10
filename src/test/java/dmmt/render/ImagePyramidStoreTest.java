package dmmt.render;

import dmmt.model.DmProject;
import dmmt.service.Tuning;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ImagePyramidStoreTest {
    @Test
    void migratesLegacyCacheAndReusesPyramidAfterAssetPackageMove(@TempDir Path directory) throws Exception {
        dmmt.FxTestSupport.startJavaFx();
        Path sourceDir = Files.createDirectory(directory.resolve("old"));
        Path asset = sourceDir.resolve("large.png");
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(4500, 20,
                java.awt.image.BufferedImage.TYPE_INT_RGB), "png", asset.toFile());
        Path root = directory.resolve("cache");
        var legacyMethod = ImagePyramidStore.class.getDeclaredMethod("legacyCacheKey", Path.class);
        legacyMethod.setAccessible(true);
        Path legacy = root.resolve((String) legacyMethod.invoke(null, asset));
        var expected = ImagePyramidBuilder.build(asset, legacy);
        ImagePyramidStore store = new ImagePyramidStore(root);
        DmProject project = new DmProject();
        project.getImageLayers().add(DmProject.ImageLayer.builder().path(asset.toString())
                .width(4500).height(20).build());
        project.getViews().getDmCamera().setX(2250);
        var viewport = new ImagePyramidStore.Viewport(500, 100, 1);
        var prepared = store.prepareProject(project, sourceDir.resolve("map.dmmap"),
                viewport, null, false, () -> false);
        assertTrue(prepared.matches(project, sourceDir.resolve("map.dmmap"), viewport, null));
        Path stable = root.resolve(ImagePyramidStore.cacheKey(asset));
        assertFalse(Files.exists(legacy));
        assertEquals(expected, ImagePyramidBuilder.readMeta(stable));
        Path moved = directory.resolve("renamed");
        Files.move(sourceDir, moved);
        project.getImageLayers().getFirst().setPath(moved.resolve("large.png").toString());
        var next = store.prepareProject(project, moved.resolve("map.dmmap"), viewport, null, false, () -> false);
        assertTrue(next.matches(project, moved.resolve("map.dmmap"), viewport, null));
        assertEquals(stable, root.resolve(ImagePyramidStore.cacheKey(moved.resolve("large.png"))));
        try (var caches = Files.list(root)) {
            assertEquals(1, caches.filter(Files::isDirectory).count());
        }
    }
    @Test
    void recoversImageAndPyramidWorkDroppedBySchedulerShutdownInSameJvm(@TempDir Path directory) throws Exception {
        dmmt.FxTestSupport.startJavaFx();
        Path asset = directory.resolve("large.png");
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(4500, 20,
                java.awt.image.BufferedImage.TYPE_INT_RGB), "png", asset.toFile());
        Path root = directory.resolve("cache");
        ImagePyramidStore store = new ImagePyramidStore(root);
        var blocker = new java.util.concurrent.CountDownLatch(1);
        var started = new java.util.concurrent.CountDownLatch(1);
        dmmt.service.WorkScheduler.shared().submit(dmmt.service.WorkScheduler.Kind.IMAGE, true, () -> {
            started.countDown();
            blocker.await();
            return null;
        });
        assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS));
        assertNull(store.get(asset));
        // Shutdown cancels the queued load; a reopened app gets a new scheduler and must not stay stuck.
        dmmt.service.WorkScheduler.shutdownShared();
        ImagePyramidStore.MapImage image = awaitImage(store, asset);
        assertNotNull(image);
        dmmt.service.WorkScheduler.shutdownShared();
        Path stable = root.resolve(ImagePyramidStore.cacheKey(asset));
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while (ImagePyramidBuilder.readMeta(stable) == null && System.nanoTime() < deadline) {
            assertSame(image, store.get(asset));
            Thread.sleep(20);
        }
        assertNotNull(ImagePyramidBuilder.readMeta(stable));
    }

    private static ImagePyramidStore.MapImage awaitImage(ImagePyramidStore store, Path asset) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        ImagePyramidStore.MapImage image;
        while ((image = store.get(asset)) == null && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        return image;
    }
    @Test
    void identitySurvivesPackageMoveAndRenameAndInvalidatesAssetVersionsAndOptions(@TempDir Path directory)
            throws Exception {
        Path packageDir = Files.createDirectory(directory.resolve("old"));
        Path asset = packageDir.resolve("map.png");
        Files.writeString(asset, "some asset bytes");
        String original = ImagePyramidStore.cacheKey(asset);
        Path moved = directory.resolve("renamed");
        Files.move(packageDir, moved);
        asset = moved.resolve("map.png");
        assertEquals(original, ImagePyramidStore.cacheKey(asset));
        Files.setLastModifiedTime(asset, FileTime.fromMillis(123456789000L));
        assertNotEquals(original, ImagePyramidStore.cacheKey(asset));
        String version = ImagePyramidStore.cacheKey(asset);
        Tuning.apply(key -> "cache.imageTileSize".equals(key) ? "512" : null);
        try {
            assertNotEquals(version, ImagePyramidStore.cacheKey(asset));
        } finally {
            Tuning.reset();
        }
    }

    @Test
    void initialViewportRequestsVisibleTilesOnlyAndIsBoundedWithRotation() {
        var meta = new ImagePyramidBuilder.Meta(8192, 8192, 3, 1024, "png");
        var layer = DmProject.ImageLayer.builder().width(8192).height(8192).build();
        var camera = DmProject.CameraState.builder().x(1500).y(1500).zoom(1).build();
        var viewport = new ImagePyramidStore.Viewport(500, 500, 1);
        assertEquals(List.of(new ImagePyramidStore.InitialTile(0, 1, 1)),
                ImagePyramidStore.initialTiles(meta, layer, camera, viewport, 64));
        camera.setX(-5000);
        assertTrue(ImagePyramidStore.initialTiles(meta, layer, camera, viewport, 64).isEmpty());
        camera.setX(4096);
        camera.setY(4096);
        assertEquals(3, ImagePyramidStore.initialTiles(meta, layer, camera,
                new ImagePyramidStore.Viewport(9000, 9000, 1), 3).size());
        camera.setZoom(0.05);
        assertTrue(ImagePyramidStore.initialTiles(meta, layer, camera, viewport, 64).isEmpty(),
                "An overview-only zoom needs no tile loads");

        var rectangular = new ImagePyramidBuilder.Meta(8192, 2048, 1, 1024, "png");
        layer.setWidth(2048);
        layer.setHeight(8192);
        layer.setRotationDeg(90);
        camera.setX(1024);
        camera.setY(1024);
        camera.setZoom(1);
        List<ImagePyramidStore.InitialTile> rotated = ImagePyramidStore.initialTiles(
                rectangular, layer, camera, viewport, 64);
        assertFalse(rotated.isEmpty());
        assertTrue(rotated.stream().allMatch(tile -> tile.x() <= 1 && tile.y() < 2));
    }
}
