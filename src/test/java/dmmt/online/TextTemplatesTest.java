package dmmt.online;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TextTemplatesTest {
    @Test
    void sharedConformanceCasesMatchTheBrowserFormatter() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/dmmt/text-template-cases.json")) {
            for (JsonNode c : new ObjectMapper().readTree(in)) {
                Map<String, Object> vars = new HashMap<>();
                c.path("vars").fields().forEachRemaining(e -> vars.put(e.getKey(), e.getValue().isNumber() ? e.getValue().numberValue() : e.getValue().asText()));
                assertEquals(c.path("expected").asText(), TextTemplates.format(c.path("template").asText(), vars), c.path("template").asText());
            }
        }
    }

    @Test
    void reportKeepsCostFirstAndInsertsGeneratedEffects() {
        List<String> result = TextTemplates.applyReport(List.of("{actor} feels better", "{effects}", "  "), List.of("spent 1 Hope", "Cleared 2 HP"),
                Map.of("actor", "Ayla"), "spent 1 Hope");
        assertEquals(List.of("spent 1 Hope", "Ayla feels better", "Cleared 2 HP"), result);
    }

    @Test
    void absentReportLeavesGeneratedLinesUnchanged() {
        List<String> generated = List.of("Used Rally.");
        assertEquals(generated, TextTemplates.applyReport(null, generated, Map.of(), null));
    }
}
