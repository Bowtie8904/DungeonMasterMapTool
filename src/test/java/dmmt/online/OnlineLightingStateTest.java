package dmmt.online;

import dmmt.lighting.TimeOfDayPreset;
import dmmt.model.DmProject;
import dmmt.service.Tuning;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class OnlineLightingStateTest {
    @TempDir
    Path temp;

    @AfterEach
    void resetTuning() {
        Tuning.reset();
    }

    @Test
    void publishesConfiguredActorSightInView() {
        var builder = new OnlineStateBuilder(file -> null);
        var project = new DmProject();
        var context = new OnlineStateBuilder.Context("map", "level", "Map", null, 0, false, false);
        var assets = new AssetRegistry(temp.resolve("cache"), 2048, null);
        assertEquals(40, builder.build(project, context, null, assets, List.of()).path("view").path("actorSightTiles").asInt());
        Tuning.apply(key -> key.equals("fog.actorSightTiles") ? "12" : null);
        assertEquals(12, builder.build(project, context, null, assets, List.of()).path("view").path("actorSightTiles").asInt());
    }

    @Test
    void recoloringTexturedDrawingPublishesNewColoredPixelsAndAssetUrl() throws Exception {
        var project = new DmProject();
        var shape = DmProject.OverlayShape.builder().id("colored").type("rect").texture("fire")
                .color("#ff0000").width(50).height(50).build();
        project.getOverlays().add(shape);
        var builder = new OnlineStateBuilder(file -> null);
        var context = new OnlineStateBuilder.Context("map", "level", "Map", null, 0, false, false);
        var assets = new AssetRegistry(temp.resolve("cache"), 2048, null);
        var first = builder.build(project, context, null, assets, List.of()).path("overlays").get(0);
        String firstUrl = first.path("tile").asText();
        assertTrue(firstUrl.startsWith("/a/"));
        var firstImage = javax.imageio.ImageIO.read(assets.resolve(firstUrl.substring(3)).file().toFile());
        shape.setColor("#0000ff");
        var second = builder.build(project, context, null, assets, List.of()).path("overlays").get(0);
        String secondUrl = second.path("tile").asText();
        assertEquals("#0000ff", second.path("color").asText());
        assertNotEquals(firstUrl, secondUrl);
        var secondImage = javax.imageio.ImageIO.read(assets.resolve(secondUrl.substring(3)).file().toFile());
        int[] firstPixels = firstImage.getRGB(0, 0, firstImage.getWidth(), firstImage.getHeight(), null, 0, firstImage.getWidth());
        int[] secondPixels = secondImage.getRGB(0, 0, secondImage.getWidth(), secondImage.getHeight(), null, 0, secondImage.getWidth());
        assertTrue(java.util.stream.IntStream.range(0, firstPixels.length).anyMatch(i -> firstPixels[i] != secondPixels[i]));
        assertEquals(secondUrl, builder.build(project, context, null, assets, List.of())
                .path("overlays").get(0).path("tile").asText());
    }

    @Test
    void exportsDmTintScalingWithoutReducingPlayerAmbientDarkness() {
        DmProject project = new DmProject();
        project.getLighting().setTimeOfDayPreset("NIGHT");
        project.getLighting().setLightTint(0.08);
        OnlineStateBuilder builder = new OnlineStateBuilder(file -> null);
        OnlineStateBuilder.Context context = new OnlineStateBuilder.Context("map", "level", "Map", null, 0, false, false);
        AssetRegistry assets = new AssetRegistry(temp.resolve("cache"), 2048, null);

        var lighting = builder.build(project, context, null, assets, List.of()).path("lighting");
        assertEquals(0.08, lighting.path("lightTint").asDouble(), 1e-12);
        assertEquals(Tuning.DM_DARKNESS_FACTOR.get(), lighting.path("tintDarknessFactor").asDouble(), 1e-12);
        assertEquals(1 - TimeOfDayPreset.NIGHT.darkness(), lighting.path("ambient").asDouble(), 1e-12);
        assertEquals("NIGHT", lighting.path("timeOfDay").asText());

        project.getLighting().setTimeOfDayPreset("DAY");
        lighting = builder.build(project, context, null, assets, List.of()).path("lighting");
        assertEquals(1 - TimeOfDayPreset.DAY.darkness(), lighting.path("ambient").asDouble(), 1e-12);
        assertEquals("DAY", lighting.path("timeOfDay").asText());
        assertEquals(0.08, lighting.path("lightTint").asDouble(), 1e-12);
    }

    @Test
    void exportsAllDmTexturesAndDrawingOwnershipWithoutHiddenShapes() {
        DmProject project = new DmProject();
        project.getOverlays().add(DmProject.OverlayShape.builder().id("owned").type("rect")
                .ownerId("alice").ownerCampaignId("campaign").width(50).height(50).build());
        project.getOverlays().add(DmProject.OverlayShape.builder().id("hidden").playerVisible(false).build());
        var state = new OnlineStateBuilder(file -> null).build(project,
                new OnlineStateBuilder.Context("map", "level", "Map", null, 0, false, false), null,
                new AssetRegistry(temp.resolve("cache"), 2048, null), List.of());
        var textures = state.path("effectTextures");
        assertEquals(dmmt.render.OverlayTextures.KINDS.size(), textures.size());
        for (int i = 0; i < textures.size(); i++) {
            String kind = dmmt.render.OverlayTextures.KINDS.get(i);
            assertEquals(kind, textures.get(i).path("id").asText());
            assertEquals(dmmt.render.OverlayTextures.label(kind), textures.get(i).path("label").asText());
            assertTrue(textures.get(i).has("emits"));
        }
        assertEquals(Tuning.PEN_WIDTH_CELLS.get(), state.path("penWidthCells").asDouble());
        assertEquals(1, state.path("overlays").size());
        assertEquals("alice", state.path("overlays").get(0).path("ownerId").asText());
        assertEquals("campaign", state.path("overlays").get(0).path("ownerCampaignId").asText());
    }
}
