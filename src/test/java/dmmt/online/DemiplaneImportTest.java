package dmmt.online;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DemiplaneImportTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ID = "11111111-2222-3333-4444-555555555555";
    private static final String URL = "https://app.demiplane.com/nexus/daggerheart/character-sheet/" + ID;

    @Test
    void acceptsOnlyOfficialHttpsCharacterLinksAndStripsTrackingParameters() {
        assertEquals(URL, DemiplaneImport.characterUri(URL + "/?share=1#sheet").toString());
        for (String invalid : List.of("http://app.demiplane.com/nexus/daggerheart/character-sheet/" + ID,
                URL.replace("app.demiplane.com", "localhost"), URL.replace("app.demiplane.com", "app.demiplane.com.evil.test"),
                URL.replace("app.demiplane.com", "user@app.demiplane.com"),
                URL.replace("app.demiplane.com", "app.demiplane.com:443"),
                URL.replace("daggerheart", "pathfinder2e"), URL + "/../../admin", "https://app.demiplane.com",
                URL.replace("11111111", "%31%31%31%31%31%31%31%31"), "not a url")) {
            assertThrows(IllegalArgumentException.class, () -> DemiplaneImport.characterUri(invalid), invalid);
        }
    }

    @Test
    void acceptsOnlyPublicAvatarImagesFromTheDemiplaneBucket() {
        String image = "https://demiplane-prod-app-avatar.s3.us-west-2.amazonaws.com/140d5bef-1c8b-43ea-a638-a858d5236c5d.webp";
        assertEquals(image, DemiplaneImport.avatarUri(image).toString());
        for (String invalid : List.of(
                image.replace("https://", "http://"),
                image.replace("demiplane-prod-app-avatar.s3.us-west-2.amazonaws.com", "example.com"),
                image.replace("amazonaws.com", "amazonaws.com.evil.test"),
                image.replace("https://", "https://user@"),
                image + "?redirect=https://example.com",
                image.replace(".webp", ".svg"),
                image.replace("us-west-2", "us-east-1"))) {
            assertThrows(IllegalArgumentException.class, () -> DemiplaneImport.avatarUri(invalid), invalid);
        }
    }

    @Test
    void readsSplitFlightRecordsAndReturnsOnlyTheRequestedCharacterBuild() throws Exception {
        ObjectNode character = MAPPER.createObjectNode().put("uuid", ID).put("name", "Test Hero").put("level", 2)
                .put("user_id", "not-imported").put("accessToken", "not-imported")
                .put("avatar_url", "https://demiplane-prod-app-avatar.s3.us-west-2.amazonaws.com/avatar.webp");
        character.putObject("formated_data").putObject("format").put("class", "Bard").put("subclass", "Wordsmith")
                .put("unrelated", "not-imported");
        ObjectNode engine = character.putObject("data").putArray("engines").addObject()
                .put("name", "tabula/class/bard.eng").put("demiplaneEngineId", "class").put("value", 1);
        engine.putObject("args").put("name", "Bard").putObject("addEngineStatus").put("internal", true);
        ((com.fasterxml.jackson.databind.node.ArrayNode) character.path("data").path("engines"))
                .addObject().put("name", "character_avatar").put("demiplaneEngineId", "avatar")
                .put("value", "https://demiplane-prod-app-avatar.s3.us-west-2.amazonaws.com/avatar.webp").putObject("args").putNull("id");
        ((com.fasterxml.jackson.databind.node.ArrayNode) character.path("data").path("engines"))
                .addObject().put("name", "character_details_custom-1").put("demiplaneEngineId", "detail")
                .put("value", "Lute").putObject("args").put("label", "Instrument");
        ((com.fasterxml.jackson.databind.node.ArrayNode) character.path("data").path("engines"))
                .addObject().put("name", "element-class_custom-option_bard-motto_value").put("demiplaneEngineId", "motto")
                .put("value", "Encore").putObject("args");
        ObjectNode props = MAPPER.createObjectNode();
        props.putObject("characterSheetContent").set("character", character);
        props.putObject("currentUser").put("unrelated", "not-imported");
        String flight = "a:I[non-json flight record]\n8:" + MAPPER.writeValueAsString(List.of("$", "$L1", "key", props)) + "\n";
        int split = flight.length() / 2;
        String html = "<html>" + chunk(flight.substring(0, split)) + chunk(flight.substring(split)) + "</html>";
        ObjectNode parsed = DemiplaneImport.parse(html, ID);
        assertEquals("Test Hero", parsed.path("name").asText());
        assertEquals(2, parsed.path("level").asInt());
        assertEquals("Wordsmith", parsed.path("summary").path("subclass").asText());
        assertEquals("class", parsed.path("engines").get(0).path("key").asText());
        assertFalse(parsed.toString().contains("not-imported"));
        assertFalse(parsed.toString().contains("addEngineStatus"));
        assertFalse(parsed.toString().contains("avatar.webp"));
        assertFalse(parsed.path("engines").get(1).has("value"));
        assertEquals("Lute", parsed.path("engines").get(2).path("value").asText());
        assertEquals("Instrument", parsed.path("engines").get(2).path("args").path("label").asText());
        assertEquals("element-class_custom-option_bard-motto_value", parsed.path("engines").get(3).path("name").asText());
        assertEquals("Encore", parsed.path("engines").get(3).path("value").asText());
        assertThrows(IOException.class, () -> DemiplaneImport.parse(html, "aaaaaaaa-2222-3333-4444-555555555555"));
    }

    @Test
    void missingPrivateOrChangedPagesProduceAnActionableFailure() {
        IOException error = assertThrows(IOException.class, () -> DemiplaneImport.parse("<html>Please sign in</html>", ID));
        assertTrue(error.getMessage().contains("Enable public viewing"));
    }

    private static String chunk(String text) throws Exception {
        return "<script>self.__next_f.push(" + MAPPER.writeValueAsString(List.of(1, text)) + ")</script>";
    }
}
