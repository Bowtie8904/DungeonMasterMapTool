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
    void readsSplitFlightRecordsAndReturnsOnlyTheRequestedCharacterBuild() throws Exception {
        ObjectNode character = MAPPER.createObjectNode().put("uuid", ID).put("name", "Test Hero").put("level", 2)
                .put("user_id", "not-imported").put("accessToken", "not-imported");
        character.putObject("formated_data").putObject("format").put("class", "Bard").put("subclass", "Wordsmith")
                .put("unrelated", "not-imported");
        ObjectNode engine = character.putObject("data").putArray("engines").addObject()
                .put("name", "tabula/class/bard.eng").put("demiplaneEngineId", "class").put("value", 1);
        engine.putObject("args").put("name", "Bard").putObject("addEngineStatus").put("internal", true);
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
