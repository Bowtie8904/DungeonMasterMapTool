package dmmt.service;

import dmmt.model.DmProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DaggerheartAdversaryClipboardTest {
    private final DaggerheartAdversaryClipboard clipboard = new DaggerheartAdversaryClipboard();

    @Test
    void parsesAdversaryDataAndIgnoresExportedEncounterState() {
        var imported = clipboard.parse("""
                {
                  "format":"daggerheart-companion/adversary",
                  "version":1,
                  "system":"daggerheart",
                  "adversary":{
                    "sourceId":"1","name":"Acid Burrower","tier":1,
                    "category":"Solo","categoryDisplayText":"Solo",
                    "description":"A horse-sized insect.","motivesAndTactics":"Burrow, reposition",
                    "difficulty":14,"damageThresholds":"8/15","maxHp":8,"maxStress":3,
                    "attackModifier":"+3","defaultAttackName":"Claws",
                    "defaultAttackRange":"Very Close","defaultAttackDamage":"1d12+2 phy",
                    "experiences":["Tremor Sense +2"],
                    "features":[{"name":"Spit Acid","classification":"Action",
                      "description":"Targets take 2d6 physical damage."}],
                    "tags":["monster"]
                  },
                  "token":{"name":"Acid Burrower","color":null,"imageUrl":"http://localhost:9980/image"},
                  "state":{"maxHp":8,"maxStress":3,"markedHp":7,"markedStress":2,"tokens":4,"dead":true}
                }
                """);

        assertEquals("Acid Burrower", imported.adversary().getName());
        assertEquals(1, imported.adversary().getTier());
        assertEquals(8, imported.adversary().getMaxHp());
        assertEquals("Tremor Sense +2", imported.adversary().getExperiences().getFirst());
        assertTrue(imported.adversary().getFeatures().getFirst().getDescription().contains("2d6 physical damage"));
        assertEquals("http://localhost:9980/image", imported.imageUrl());
        assertNull(imported.tokenColor());
    }

    @Test
    void rejectsUnsupportedFormatsAndInvalidResourceBounds() {
        assertThrows(IllegalArgumentException.class, () -> clipboard.parse("{}"));
        assertThrows(IllegalArgumentException.class, () -> clipboard.parse("""
                {"format":"daggerheart-companion/adversary","version":2,"system":"daggerheart"}
                """));
        assertThrows(IllegalArgumentException.class, () -> clipboard.parse("""
                {"format":"daggerheart-companion/adversary","version":1,"system":"daggerheart",
                 "adversary":{"name":"Too Large","tier":1,"difficulty":14,"maxHp":101,"maxStress":0}}
                """));
    }

    @Test
    void imageDownloadOnlyAcceptsLoopbackHttpUrls() {
        assertEquals("localhost", DaggerheartAdversaryClipboard.parseLocalImageUri(
                "http://localhost:9980/statblock-images/adversary/1").getHost());
        assertEquals("127.0.0.1", DaggerheartAdversaryClipboard.parseLocalImageUri(
                "https://127.0.0.1:9980/image.png").getHost());
        assertThrows(IllegalArgumentException.class, () ->
                DaggerheartAdversaryClipboard.parseLocalImageUri("http://example.com/image.png"));
        assertThrows(IllegalArgumentException.class, () ->
                DaggerheartAdversaryClipboard.parseLocalImageUri("http://localhost.evil/image.png"));
        assertThrows(IllegalArgumentException.class, () ->
                DaggerheartAdversaryClipboard.parseLocalImageUri("http://user@localhost/image.png"));
    }

    @Test
    void downloadsWebpArtworkAsPng() throws Exception {
        byte[] webp = java.util.Base64.getDecoder().decode("UklGRh4AAABXRUJQVlA4TBEAAAAvAAAAEAcQERGIiP4HAA==");
        var server = imageServer(webp, "image/webp", 200);
        try {
            BufferedImage converted = downloadedImage(server);
            assertEquals(1, converted.getWidth());
            assertEquals(1, converted.getHeight());
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"png", "jpeg", "gif", "bmp", "tiff", "wbmp"})
    void downloadsSupportedArtworkFormatsAsPng(String format) throws Exception {
        var image = new BufferedImage(23, 11,
                format.equals("wbmp") ? BufferedImage.TYPE_BYTE_BINARY : BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0xFFFFFFFF);
        var bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, bytes), "The test format must be encodable.");
        var server = imageServer(bytes.toByteArray(), "image/" + format, 200);
        try {
            BufferedImage converted = downloadedImage(server);
            assertEquals(23, converted.getWidth());
            assertEquals(11, converted.getHeight());
            assertEquals(255, converted.getRGB(0, 0) >>> 24);
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"application/octet-stream", "image/png", "text/plain"})
    void detectsActualImageBytesInsteadOfRequiringPngContentType(String contentType) throws Exception {
        var image = new BufferedImage(7, 5, BufferedImage.TYPE_INT_RGB);
        var bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "jpeg", bytes));
        var server = imageServer(bytes.toByteArray(), contentType, 200);
        try {
            BufferedImage converted = downloadedImage(server);
            assertEquals(7, converted.getWidth());
            assertEquals(5, converted.getHeight());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void convertedArtworkPreservesTransparency() throws Exception {
        var image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(1, 0, 0x80FF0000);
        image.setRGB(2, 0, 0xFFFFFFFF);
        var bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", bytes));
        var server = imageServer(bytes.toByteArray(), "application/octet-stream", 200);
        try {
            BufferedImage converted = downloadedImage(server);
            assertEquals(0, converted.getRGB(0, 0) >>> 24);
            assertEquals(0x80FF0000, converted.getRGB(1, 0));
            assertEquals(0xFFFFFFFF, converted.getRGB(2, 0));
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not an image", "<html>Not found</html>"})
    void rejectsUnreadableArtworkEvenWhenLabeledPng(String body) throws Exception {
        var server = imageServer(body.getBytes(java.nio.charset.StandardCharsets.UTF_8), "image/png", 200);
        try {
            IOException error = assertThrows(IOException.class,
                    () -> DaggerheartAdversaryClipboard.downloadLocalImage(imageUrl(server)));
            assertTrue(error.getMessage().contains("not a supported image"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void imageDownloadStillRejectsHttpErrors() throws Exception {
        var server = imageServer("Missing".getBytes(java.nio.charset.StandardCharsets.UTF_8), "text/plain", 404);
        try {
            IOException error = assertThrows(IOException.class,
                    () -> DaggerheartAdversaryClipboard.downloadLocalImage(imageUrl(server)));
            assertTrue(error.getMessage().contains("HTTP 404"));
        } finally {
            server.stop(0);
        }
    }

    private static com.sun.net.httpserver.HttpServer imageServer(byte[] data, String contentType, int status) throws IOException {
        var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/image", exchange -> {
            try (exchange) {
                if (contentType != null)
                    exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.sendResponseHeaders(status, data.length);
                exchange.getResponseBody().write(data);
            }
        });
        server.start();
        return server;
    }

    private static String imageUrl(com.sun.net.httpserver.HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/image";
    }

    private static BufferedImage downloadedImage(com.sun.net.httpserver.HttpServer server) throws Exception {
        String reference = DaggerheartAdversaryClipboard.downloadLocalImage(imageUrl(server));
        Path stored = Path.of(URI.create(reference));
        assertTrue(stored.getFileName().toString().endsWith(".png"));
        byte[] bytes = Files.readAllBytes(stored);
        assertArrayEquals(new byte[] {(byte)137, 80, 78, 71, 13, 10, 26, 10},
                java.util.Arrays.copyOf(bytes, 8));
        BufferedImage converted = ImageIO.read(stored.toFile());
        assertNotNull(converted);
        return converted;
    }

    @Test
    void persistedTokenDefinitionRetainsAdversaryAndPerTokenState() throws Exception {
        var adversary = clipboard.parse("""
                {"format":"daggerheart-companion/adversary","version":1,"system":"daggerheart",
                 "adversary":{"name":"Test","tier":1,"difficulty":14,"maxHp":8,"maxStress":3}}
                """).adversary();
        DmProject project = DmProject.builder().build();
        DmProject.TokenDefinition definition = DmProject.TokenDefinition.builder()
                .id("adversary:test").name("Test").adversary(adversary).build();
        DmProject.TokenInstance token = DmProject.TokenInstance.builder()
                .id("token").definitionId(definition.getId()).adversaryState(
                        new dmmt.model.DaggerheartAdversary.CombatState(2, 1, 3, false)).build();
        project.getTokenDefinitions().put(definition.getId(), definition);
        project.getTokens().add(token);

        DmProject restored = JsonMappers.create().readValue(JsonMappers.create().writeValueAsBytes(project), DmProject.class);
        assertEquals("Test", restored.getTokenDefinitions().get("adversary:test").getAdversary().getName());
        assertEquals(2, restored.getTokens().getFirst().getAdversaryState().getMarkedHp());
        assertEquals(3, restored.getTokens().getFirst().getAdversaryState().getTokens());
    }
}
