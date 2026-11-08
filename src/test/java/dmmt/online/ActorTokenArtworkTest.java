package dmmt.online;

import dmmt.model.DmProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ActorTokenArtworkTest {
    @TempDir Path temp;

    private Campaign.ActorArtwork settings() {
        return Campaign.ActorArtwork.builder().source("source").color("#00ff00").sizeTiles(2).build();
    }

    @Test
    void rendersTheCircularBorderAndRetainsPanZoomAndOutsideSourceBackground() throws Exception {
        var source = new BufferedImage(800, 400, BufferedImage.TYPE_INT_RGB);
        var g = source.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 400, 400);
        g.setColor(Color.BLUE);
        g.fillRect(400, 0, 400, 400);
        g.dispose();
        Path file = temp.resolve("source.png");
        ImageIO.write(source, "png", file.toFile());
        var art = settings();
        var crop = ImageIO.read(new ByteArrayInputStream(ActorTokenArtwork.render(file, art)));
        assertEquals(256, crop.getWidth());
        assertEquals(256, crop.getHeight());
        assertEquals(0, crop.getRGB(0, 0) >>> 24);
        assertEquals(Color.GREEN.getRGB(), crop.getRGB(128, 2));
        assertEquals(Color.GREEN.getRGB(), crop.getRGB(128, 12));
        assertNotEquals(Color.GREEN.getRGB(), crop.getRGB(128, 16),
                "The border must occupy 5.5% of the diameter, not a fixed six pixels");
        assertEquals(Color.RED.getRGB(), crop.getRGB(80, 128));
        assertEquals(Color.BLUE.getRGB(), crop.getRGB(180, 128));
        art.setZoom(2);
        art.setCenterX(.25);
        crop = ImageIO.read(new ByteArrayInputStream(ActorTokenArtwork.render(file, art)));
        assertEquals(Color.RED.getRGB(), crop.getRGB(180, 128));
        art.setCenterX(0);
        crop = ImageIO.read(new ByteArrayInputStream(ActorTokenArtwork.render(file, art)));
        assertEquals(Color.GREEN.darker().darker().getRGB(), crop.getRGB(80, 128));
    }

    @Test
    void rejectsInvalidBorderSizesAndCropValues() {
        var art = settings();
        art.setColor("transparent");
        assertThrows(java.io.IOException.class, () -> ActorTokenArtwork.validate(art));
        art.setColor("#123456");
        for (double size : new double[] { 0, .25, .75, Double.NaN, Double.POSITIVE_INFINITY }) {
            art.setSizeTiles(size);
            assertThrows(java.io.IOException.class, () -> ActorTokenArtwork.validate(art));
        }
        art.setSizeTiles(1);
        art.setZoom(11);
        assertThrows(java.io.IOException.class, () -> ActorTokenArtwork.validate(art));
        art.setZoom(1);
        art.setCenterX(-.1);
        assertThrows(java.io.IOException.class, () -> ActorTokenArtwork.validate(art));
    }

    @Test
    void updatesAllSharedInstancesAndRevisitedMapsWithoutChangingUnrelatedTokenState() {
        var store = new CampaignStore(temp);
        var campaign = Campaign.builder().id("campaign").build();
        var actor = Campaign.Actor.builder().id("actor").name("Actor").artwork("a".repeat(64)).tokenArtwork(settings()).build();
        campaign.getActors().add(actor);
        var map = DmProject.builder().build();
        var first = DmProject.TokenInstance.builder().id("first").actorId(actor.getId()).campaignId(campaign.getId())
                .definitionId("actor:actor").x(12).y(34).rotationDeg(75).sizeTiles(1).playerVisible(true).build();
        var second = DmProject.TokenInstance.builder().id("second").actorId(actor.getId()).campaignId(campaign.getId())
                .definitionId("actor:actor").sizeTiles(3).build();
        var foreign = DmProject.TokenInstance.builder().id("foreign").actorId(actor.getId()).campaignId("other")
                .definitionId("foreign").sizeTiles(4).build();
        first.getConditions().add(DmProject.TokenCondition.builder().id("hidden").type("hidden").name("Hidden").build());
        map.getTokens().addAll(java.util.List.of(first, second, foreign));
        assertTrue(ActorTokenArtwork.synchronize(map, campaign, store));
        assertEquals(2, first.getSizeTiles());
        assertEquals(2, second.getSizeTiles());
        assertEquals(4, foreign.getSizeTiles());
        assertEquals(12, first.getX());
        assertEquals(34, first.getY());
        assertEquals(75, first.getRotationDeg());
        assertTrue(first.isPlayerVisible());
        assertFalse(second.isPlayerVisible());
        assertEquals(1, first.getConditions().size());
        var definition = map.getTokenDefinitions().get("actor:actor");
        assertFalse(definition.isBorder(), "The finished image already includes the mandatory border");
        assertEquals(DmProject.TokenShape.CIRCLE, definition.getShape());
        first.setSizeTiles(3);
        definition.setImage(temp.resolve("snapshot").resolve(actor.getArtwork() + ".png").toUri().toString());
        assertFalse(ActorTokenArtwork.synchronize(map, campaign, store));
        assertEquals(3, first.getSizeTiles(), "An unchanged map snapshot must not undo a later DM resize");
        actor.setArtwork("b".repeat(64));
        assertTrue(ActorTokenArtwork.synchronize(map, campaign, store));
        assertEquals(2, first.getSizeTiles());
        assertEquals(2, second.getSizeTiles());
        actor.getTokenArtwork().setSizeTiles(2.5);
        assertTrue(ActorTokenArtwork.synchronize(map, campaign, store));
        assertEquals(2.5, first.getSizeTiles());
        assertEquals(2.5, second.getSizeTiles());
    }

    @Test
    void reloadedCampaignUpdatesAStoredMapAndPublishesTheSameFinishedArtworkAsTheActorList() throws Exception {
        var store = new CampaignStore(temp);
        var campaign = store.create("Artwork");
        var source = new BufferedImage(8, 4, BufferedImage.TYPE_INT_ARGB);
        var bytes = new java.io.ByteArrayOutputStream();
        ImageIO.write(source, "png", bytes);
        var art = settings();
        art.setSource(store.storeArtwork(campaign.getId(), bytes.toByteArray()));
        var actor = Campaign.Actor.builder().id("actor").name("Actor").tokenArtwork(art)
                .artwork(store.storeArtwork(campaign.getId(), ActorTokenArtwork.render(
                        store.artworkPath(campaign.getId(), art.getSource()), art))).build();
        campaign.getActors().add(actor);
        store.save(campaign);
        var map = DmProject.builder().build();
        map.getTokens().add(DmProject.TokenInstance.builder().id("token").definitionId("actor:actor")
                .actorId(actor.getId()).campaignId(campaign.getId()).playerVisible(true).build());
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Path file = temp.resolve("map.dmmap");
        mapper.writeValue(file.toFile(), map);
        map = mapper.readValue(file.toFile(), DmProject.class);
        campaign = store.load(campaign.getId());
        assertTrue(ActorTokenArtwork.synchronize(map, campaign, store));
        var assets = new AssetRegistry(temp.resolve("cache"), 2048, store);
        var state = new OnlineStateBuilder(path -> null).build(map,
                new OnlineStateBuilder.Context("map", "level", "Map", file, 0, false, false),
                campaign, assets, java.util.List.of());
        var token = state.path("tokens").get(0);
        assertEquals(state.path("actors").get(0).path("artwork"), token.path("image"));
        assertEquals(2, token.path("sizeTiles").asDouble());
        assertEquals("circle", token.path("shape").asText());
        assertFalse(token.path("border").asBoolean());
        assertFalse(token.has("zoom"), "The finished artwork must not be cropped a second time");
    }
}
