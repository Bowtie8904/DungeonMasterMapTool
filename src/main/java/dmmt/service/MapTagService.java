package dmmt.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dmmt.model.DmProject;
import dmmt.model.MultiLevelManifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Map tags ({@code map.tags} of every ordinary map and multilevel level). A multilevel map has the union of its
 * levels' tags; changing its tags changes every level. Tag edits only touch {@code map.tags} in the JSON, so images,
 * fog, thumbnails and all other fields stay exactly as they are.
 */
public class MapTagService {
    private final ObjectMapper mapper = JsonMappers.create()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final MapLibraryService library;

    public MapTagService(MapLibraryService library, ProjectService projectService) {
        this.library = library;
    }

    // ---- Normalizing and matching ----

    /** Trimmed, uppercase, non-blank tags without case-insensitive duplicates, in their original order. */
    public static List<String> normalize(Collection<String> tags) {
        List<String> result = new ArrayList<>();
        if (tags == null) {
            return result;
        }
        Set<String> seen = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String tag : tags) {
            if (tag == null) {
                continue;
            }
            String trimmed = tag.trim();
            if (!trimmed.isEmpty() && seen.add(trimmed)) {
                String uppercase = trimmed.toUpperCase(Locale.ROOT);
                if (!containsTag(result, uppercase)) {
                    result.add(uppercase);
                }
            }
        }
        return result;
    }

    static String key(String tag) {
        return tag.trim().toLowerCase(Locale.ROOT);
    }

    /** Whether {@code tags} contains {@code tag}, ignoring case and surrounding spaces. */
    public static boolean containsTag(Collection<String> tags, String tag) {
        if (tags == null || tag == null) {
            return false;
        }
        String wanted = tag.trim();
        for (String existing : tags) {
            if (existing != null && existing.trim().equalsIgnoreCase(wanted)) {
                return true;
            }
        }
        return false;
    }

    /** {@code current} without {@code removals} and with {@code additions} appended (all case-insensitive). */
    public static List<String> change(List<String> current, Collection<String> additions, Collection<String> removals) {
        List<String> removeList = normalize(removals);
        List<String> result = new ArrayList<>();
        for (String tag : normalize(current)) {
            if (!containsTag(removeList, tag)) {
                result.add(tag);
            }
        }
        result.addAll(normalize(additions));
        return normalize(result);
    }

    /** Known tags whose full name occurs in the file name (without extension) of {@code source}, ignoring case. */
    public static List<String> matchingTags(Collection<String> knownTags, Path source) {
        List<String> matches = new ArrayList<>();
        if (source == null || source.getFileName() == null) {
            return matches;
        }
        String fileName = MapLibraryService.stripExtension(source.getFileName().toString()).toLowerCase(Locale.ROOT);
        for (String tag : normalize(knownTags)) {
            if (fileName.contains(key(tag))) {
                matches.add(tag);
            }
        }
        return matches;
    }

    /** Adds the {@link #matchingTags} of {@code source} to the project's tags. */
    public static void applyMatchingTags(DmProject project, Path source, Collection<String> knownTags) {
        List<String> matches = matchingTags(knownTags, source);
        if (matches.isEmpty()) {
            return;
        }
        project.getMap().setTags(change(project.getMap().getTags(), matches, List.of()));
    }

    // ---- Library ----

    /** Every tag used anywhere in the library (maps and multilevel levels), deduplicated, sorted case-insensitively. */
    public List<String> knownTags() throws IOException {
        List<String> all = new ArrayList<>();
        collect(library.scan(), all);
        List<String> known = normalize(all);
        known.sort(String.CASE_INSENSITIVE_ORDER);
        return known;
    }

    private static void collect(MapLibraryService.Entry entry, List<String> into) {
        into.addAll(entry.tags());
        for (MapLibraryService.Entry child : entry.children()) {
            collect(child, into);
        }
    }

    /** Library tags plus configured automatic tags, deduplicated and sorted case-insensitively. */
    public List<String> importTags() throws IOException {
        List<String> all = new ArrayList<>(knownTags());
        all.addAll(Tuning.IMPORT_AUTO_TAGS.get());
        List<String> result = normalize(all);
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    /** Adds library and configured automatic tags contained in the import file name to {@code project}. */
    public void applyKnownTags(DmProject project, Path source) throws IOException {
        applyMatchingTags(project, source, importTags());
    }

    // ---- Reading ----

    /** Tags of a map; for a multilevel map the union of all its levels' tags. */
    public List<String> readTags(Path mapFile) throws IOException {
        if (MultiLevelService.isMultiLevelFile(mapFile)) {
            List<String> union = new ArrayList<>();
            for (Path levelFile : levelFiles(mapFile)) {
                union.addAll(readMapTags(levelFile));
            }
            return normalize(union);
        }
        return readMapTags(mapFile);
    }

    /** {@link #readTags} for the library scan; a failure names the map whose tags could not be read. */
    List<String> readTagsForScan(Path mapFile) throws IOException {
        try {
            return readTags(mapFile);
        } catch (IOException | RuntimeException ex) {
            String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            throw new IOException("Could not read the tags of \"" + mapFile + "\": " + reason, ex);
        }
    }

    /** Streams just {@code map.tags} of a {@code .dmmap} file without parsing fog, images or anything else. */
    private List<String> readMapTags(Path mapFile) throws IOException {
        try (JsonParser parser = mapper.getFactory().createParser(mapFile.toFile())) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("\"" + mapFile.getFileName() + "\" is not a map file.");
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("map".equals(field) && value == JsonToken.START_OBJECT) {
                    return readTagsField(parser);
                }
                parser.skipChildren();
            }
            return new ArrayList<>();
        }
    }

    private static List<String> readTagsField(JsonParser parser) throws IOException {
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String field = parser.currentName();
            JsonToken value = parser.nextToken();
            if ("tags".equals(field)) {
                List<String> tags = new ArrayList<>();
                if (value == JsonToken.START_ARRAY) {
                    JsonToken element;
                    while ((element = parser.nextToken()) != JsonToken.END_ARRAY) {
                        if (element == null) {
                            throw new IOException("Unexpected end of map tags.");
                        }
                        if (parser.currentToken() == JsonToken.VALUE_STRING) {
                            tags.add(parser.getText());
                        } else {
                            parser.skipChildren();
                        }
                    }
                }
                return normalize(tags);
            }
            parser.skipChildren();
        }
        return new ArrayList<>();
    }

    private List<Path> levelFiles(Path manifestFile) throws IOException {
        MultiLevelManifest manifest = library.multiLevels().loadManifest(manifestFile);
        List<Path> files = new ArrayList<>();
        for (MultiLevelManifest.Level level : manifest.getLevels()) {
            files.add(MultiLevelService.levelFile(manifestFile, level));
        }
        return files;
    }

    // ---- Changing ----

    /**
     * Adds and removes tags (case-insensitive) on every given map; on a multilevel map on every level. Only
     * {@code map.tags} is rewritten. Every file is read and its new content written to a temporary file before any
     * map is replaced; if replacing one fails, the maps already replaced are restored, so either all maps change or
     * none (unless restoring fails too, which is reported). Save an open map before calling this, and update its
     * in-memory tags afterwards.
     */
    public void updateTags(List<Path> mapFiles, List<String> additions, List<String> removals) throws IOException {
        Map<Path, byte[]> originals = new LinkedHashMap<>();
        for (Path mapFile : mapFiles) {
            List<Path> files = MultiLevelService.isMultiLevelFile(mapFile) ? levelFiles(mapFile) : List.of(mapFile);
            for (Path file : files) {
                Path normalized = file.toAbsolutePath().normalize();
                if (!originals.containsKey(normalized)) {
                    originals.put(normalized, Files.readAllBytes(normalized));
                }
            }
        }
        Map<Path, Path> prepared = new LinkedHashMap<>();
        try {
            for (Map.Entry<Path, byte[]> original : originals.entrySet()) {
                Path file = original.getKey();
                ObjectNode document = parseDocument(file, original.getValue());
                ObjectNode mapNode = mapNode(document);
                List<String> before = tagsOf(mapNode);
                List<String> after = change(before, additions, removals);
                if (mapNode.path("tags").equals(mapper.valueToTree(after))) {
                    continue;
                }
                ArrayNode array = mapNode.putArray("tags");
                after.forEach(array::add);
                Path temp = tempFile(file);
                prepared.put(file, temp);
                mapper.writeValue(temp.toFile(), document);
            }
            replaceAll(prepared, originals);
        } finally {
            for (Path temp : prepared.values()) {
                Files.deleteIfExists(temp);
            }
        }
    }

    /** Moves the prepared files into place; on failure the maps already replaced get their original bytes back. */
    void replaceAll(Map<Path, Path> prepared, Map<Path, byte[]> originals) throws IOException {
        List<Path> replaced = new ArrayList<>();
        try {
            for (Map.Entry<Path, Path> entry : prepared.entrySet()) {
                moveIntoPlace(entry.getValue(), entry.getKey());
                replaced.add(entry.getKey());
            }
        } catch (IOException | RuntimeException ex) {
            List<Path> notRestored = new ArrayList<>();
            for (Path file : replaced) {
                Path restore = tempFile(file);
                try {
                    Files.write(restore, originals.get(file));
                    Files.move(restore, file, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException | RuntimeException restoreFailure) {
                    ex.addSuppressed(restoreFailure);
                    notRestored.add(file);
                } finally {
                    try {
                        Files.deleteIfExists(restore);
                    } catch (IOException cleanupFailure) {
                        ex.addSuppressed(cleanupFailure);
                    }
                }
            }
            if (!notRestored.isEmpty()) {
                throw new IOException("Changing the tags failed and these maps could not be restored: " + notRestored
                        + " (" + ex.getMessage() + ")", ex);
            }
            throw ex;
        }
    }

    /** Replaces {@code target} with {@code prepared}; a seam for tests that simulate a failing disk. */
    void moveIntoPlace(Path prepared, Path target) throws IOException {
        Files.move(prepared, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static Path tempFile(Path file) {
        return file.resolveSibling(file.getFileName() + ".tags-" + System.nanoTime() + ".tmp");
    }

    private ObjectNode parseDocument(Path file, byte[] content) throws IOException {
        JsonNode node = mapper.readTree(content);
        if (!(node instanceof ObjectNode object)) {
            throw new IOException("\"" + file.getFileName() + "\" is not a map file.");
        }
        return object;
    }

    private static ObjectNode mapNode(ObjectNode document) {
        JsonNode map = document.get("map");
        return map instanceof ObjectNode object ? object : document.putObject("map");
    }

    private static List<String> tagsOf(ObjectNode mapNode) {
        List<String> tags = new ArrayList<>();
        JsonNode array = mapNode.get("tags");
        if (array != null && array.isArray()) {
            for (JsonNode tag : array) {
                if (tag.isTextual()) {
                    tags.add(tag.asText());
                }
            }
        }
        return normalize(tags);
    }
}
