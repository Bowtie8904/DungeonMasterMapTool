package dmmt.service;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Creates ObjectMappers that can read very large maps (e.g. dd2vtt files with embedded base64 images). */
final class JsonMappers {
    private JsonMappers() {
    }

    static ObjectMapper create() {
        JsonFactory factory = JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxStringLength(Integer.MAX_VALUE)
                        .build())
                .build();
        return new ObjectMapper(factory);
    }
}
