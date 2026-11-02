package dmmt.render;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dmmt.lighting.VisibilityService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Keeps the Java weather/vision maths and the browser port identical. The vectors file is shared with
 * the web client tests; delete it to regenerate it from the Java implementation.
 */
class SharedVectorsTest {
    private static final Path FILE = Path.of("src/test/resources/online/vectors.json");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static ObjectNode generate() {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode units = root.putArray("weatherUnit");
        for (int index : new int[]{0, 1, 2, 7, 100, 4096, 99999}) {
            for (int salt : new int[]{0, 1, 3, 17, 255}) {
                units.addObject().put("index", index).put("salt", salt).put("value", WeatherEffects.unit(index, salt));
            }
        }
        ArrayNode wraps = root.putArray("wrap");
        double[][] cases = {{0, 10}, {5, 10}, {10, 10}, {-3, 10}, {23.5, 10}, {-27.25, 4}, {1e6 + 0.5, 7}};
        for (double[] c : cases) {
            wraps.addObject().put("value", c[0]).put("span", c[1]).put("result", WeatherEffects.wrap(c[0], c[1]));
        }
        ArrayNode vis = root.putArray("visibility");
        double[][] scenes = {
                {},
                {100, 0, 100, 200},
                {100, -50, 100, 50, -80, 60, 80, 60},
                {20, 20, 20, -20, 20, -20, -20, -20, -20, -20, -20, 20, -20, 20, 20, 20},
        };
        for (double[] segments : scenes) {
            var polygon = new VisibilityService().compute(0, 0, 150, segments);
            ObjectNode entry = vis.addObject();
            entry.putArray("light").add(0).add(0);
            entry.put("range", 150);
            ArrayNode seg = entry.putArray("segments");
            for (double s : segments) seg.add(s);
            ArrayNode poly = entry.putArray("polygon");
            for (int i = 0; i < polygon.size(); i++) {
                poly.add(polygon.xs()[i]).add(polygon.ys()[i]);
            }
        }
        return root;
    }

    @Test
    void vectorsFileMatchesJava() throws Exception {
        ObjectNode generated = generate();
        if (!Files.exists(FILE)) {
            Files.createDirectories(FILE.getParent());
            JSON.writerWithDefaultPrettyPrinter().writeValue(FILE.toFile(), generated);
        }
        JsonNode stored = JSON.readTree(FILE.toFile());
        assertEquals(generated, stored);
    }
}
