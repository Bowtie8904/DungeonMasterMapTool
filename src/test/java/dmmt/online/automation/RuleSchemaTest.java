package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleSchemaTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path SCHEMAS = Path.of("src", "main", "resources", "dmmt", "online", "automation", "schema");
    private static final Path DATA = Path.of("dmmap-daggerheart");

    private static JsonSchema schema(String name) throws IOException {
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(Files.readString(SCHEMAS.resolve(name + ".schema.json")));
    }

    private static Set<ValidationMessage> validate(String schema, Path file) throws IOException {
        return schema(schema).validate(MAPPER.readTree(file.toFile()));
    }

    @Test
    void committedSchemasMatchTheGenerator() throws IOException {
        if (Boolean.getBoolean("schema.regenerate")) RuleSchema.main(new String[]{SCHEMAS.toString()});
        for (String name : RuleSchema.NAMES)
            assertEquals(RuleSchema.generate(name), Files.readString(SCHEMAS.resolve(name + ".schema.json"), StandardCharsets.UTF_8).replace("\r\n", "\n"),
                    name + ".schema.json is stale; run with -Dschema.regenerate=true");
    }

    private static void collect(List<String> failures, String schema, String label, JsonNode document) throws IOException {
        schema(schema).validate(document).forEach(m -> failures.add(label + " " + m.getInstanceLocation() + ": " + m.getMessage()));
    }

    @Test
    void shippedAndFixtureFilesValidateAgainstTheSchemas() throws IOException {
        List<String> failures = new ArrayList<>();
        Path rules = DATA.resolve("rules");
        if (Files.exists(rules.resolve("recipes.json"))) collect(failures, "recipes", "shipped recipes", MAPPER.readTree(rules.resolve("recipes.json").toFile()));
        if (Files.exists(rules.resolve("ruleset.json"))) collect(failures, "ruleset", "shipped ruleset", MAPPER.readTree(rules.resolve("ruleset.json").toFile()));
        collect(failures, "recipes", "core recipes", MAPPER.readTree(LegacyCatalogConverter.coreRecipes()));
        collect(failures, "ruleset", "core ruleset", MAPPER.readTree(LegacyCatalogConverter.coreRuleset()));
        try (Stream<Path> files = Files.list(DATA)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                collect(failures, "catalog", "shipped " + file.getFileName(), MAPPER.readTree(file.toFile()));
                assertEquals(2, MAPPER.readTree(file.toFile()).path("version").asInt(), file + " must be format 2");
            }
        }
        try (Stream<Path> files = Files.list(dmmt.online.LegacyCatalogs.dir())) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                JsonNode legacy = MAPPER.readTree(file.toFile());
                if (!"dmmap-daggerheart".equals(legacy.path("format").asText())) continue;
                collect(failures, "catalog", "converted " + file.getFileName(), LegacyCatalogConverter.convert(Files.readString(file)).catalog());
            }
        }
        try (Stream<Path> files = Files.list(Path.of("src", "test", "resources", "dmmt", "automation", "rewrite"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                JsonNode document = MAPPER.readTree(file.toFile());
                String format = document.path("format").asText();
                if ("dmmap-daggerheart".equals(format)) collect(failures, "catalog", "fixture " + file.getFileName(), document);
                else if ("dmmap-daggerheart-rules".equals(format)) collect(failures, "recipes", "fixture " + file.getFileName(), document);
            }
        }
        assertTrue(failures.isEmpty(), failures.size() + " failures\n" + String.join("\n", failures.stream().limit(30).toList()));
    }
    @Test
    void errorsPointAtTheExactPath() throws IOException {
        JsonNode bad = MAPPER.readTree("""
                {"format":"dmmap-daggerheart","version":2,"category":"x","entries":[{"automation":{"rules":[
                  {"id":"a","bindings":[{"id":"b","event":"nope","surface":"sheet","mode":"button"}],"do":[{"op":"effect.apply"}]}]}}]}
                """);
        Set<ValidationMessage> messages = schema("catalog").validate(bad);
        assertFalse(messages.isEmpty());
        assertTrue(messages.stream().anyMatch(m -> m.getInstanceLocation().toString().startsWith("$.entries[0].automation.rules[0]")),
                messages.toString());
    }

    @Test
    void unknownKeysAndRulesetMistakesAreRejected() throws IOException {
        assertFalse(schema("ruleset").validate(MAPPER.readTree("{\"format\":\"dmmap-daggerheart-ruleset\",\"version\":2,\"name\":\"x\",\"bogus\":1}")).isEmpty());
        assertFalse(schema("recipes").validate(MAPPER.readTree("{\"format\":\"dmmap-daggerheart-rules\",\"version\":1,\"recipes\":[]}")).isEmpty());
    }
}
