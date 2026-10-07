package dmmt.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dmmt.model.DmProject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class ProjectService {
    private final ObjectMapper objectMapper = JsonMappers.create()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final FogService fogService = new FogService();
    private final ThumbnailService thumbnailService = new ThumbnailService();

    /**
     * Lightweight read of just {@code map.originalFileName}, without parsing the rest of the project (fog mask,
     * lights, etc.); used for duplicate-import detection. Empty if the field is missing/blank (older saves) or the
     * file cannot be read.
     */
    public Optional<String> readOriginalFileName(Path projectFile) {
        try {
            JsonNode node = objectMapper.readTree(projectFile.toFile()).path("map").path("originalFileName");
            return node.isTextual() && !node.asText().isBlank() ? Optional.of(node.asText()) : Optional.empty();
        } catch (IOException | RuntimeException ex) {
            return Optional.empty();
        }
    }

    public DmProject load(Path projectFile) throws IOException {
        JsonNode json = objectMapper.readTree(projectFile.toFile());
        DmProject project = objectMapper.treeToValue(json, DmProject.class);
        project.setId(json.path("id").isTextual() ? json.path("id").asText() : null);
        fogService.ensureMask(project);
        return project;
    }

    /** Deep copy via serialization; used to freeze what the players see. */
    public DmProject copy(DmProject project) throws IOException {
        DmProject copy = objectMapper.readValue(objectMapper.writeValueAsBytes(project), DmProject.class);
        fogService.ensureMask(copy);
        return copy;
    }

    /** Content hash used to tell whether a project differs from what was last saved. */
    public String fingerprint(DmProject project) throws IOException {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsBytes(project));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public synchronized void save(Path projectFile, DmProject project) throws IOException {
        if (!isValidId(project.getId())) {
            project.setId(Files.isRegularFile(projectFile) ? ensureId(projectFile) : UUID.randomUUID().toString());
        }
        if (projectFile.getParent() != null) {
            Files.createDirectories(projectFile.getParent());
        }

        normalizeAssetPaths(projectFile, project);
        objectMapper.writeValue(projectFile.toFile(), project);
        try {
            thumbnailService.write(projectFile, project);
        } catch (IOException | RuntimeException ignored) {
            // A missing thumbnail must never fail a save; it is regenerated lazily.
        }
    }

    /** Indexes legacy files without migrating their content or copying assets. */
    public synchronized String ensureId(Path mapFile) throws IOException {
        JsonNode json = objectMapper.readTree(mapFile.toFile());
        if (!(json instanceof ObjectNode object)) {
            throw new IOException("The map must contain a JSON object.");
        }
        String id = object.path("id").asText(null);
        if (!isValidId(id)) {
            id = UUID.randomUUID().toString();
            object.put("id", id);
            Path staging = mapFile.resolveSibling(mapFile.getFileName() + ".identity");
            try {
                objectMapper.writeValue(staging.toFile(), object);
                Files.move(staging, mapFile, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(staging);
            }
        }
        return id;
    }

    static boolean isValidId(String id) {
        if (id == null) {
            return false;
        }
        try {
            return UUID.fromString(id).toString().equalsIgnoreCase(id);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /** Loads the map's thumbnail PNG, generating it (and storing it for packaged maps) if it is missing. */
    public byte[] loadOrCreateThumbnail(Path projectFile) throws IOException {
        Path thumbnail = ThumbnailService.thumbnailFile(projectFile);
        if (ThumbnailService.isPackage(projectFile) && Files.isRegularFile(thumbnail)) {
            return Files.readAllBytes(thumbnail);
        }
        DmProject project = load(projectFile);
        byte[] png = thumbnailService.renderPng(project, projectFile);
        if (png != null && ThumbnailService.isPackage(projectFile)) {
            Files.write(thumbnail, png);
        }
        return png;
    }

    private void normalizeAssetPaths(Path projectFile, DmProject project) throws IOException {
        Path projectDir = projectFile.getParent();
        if (projectDir == null) {
            return;
        }

        if (project.getMap() != null) {
            String imagePath = project.getMap().getImagePath();
            if (imagePath != null && !imagePath.isBlank()) {
                project.getMap().setImagePath(copyAssetIfExternal(projectDir, imagePath, "assets\\map"));
            }
        }

        List<DmProject.ImageLayer> updated = new ArrayList<>();
        for (DmProject.ImageLayer layer : project.getImageLayers()) {
            if (layer.getPath() == null || layer.getPath().isBlank()) {
                updated.add(layer);
                continue;
            }
            layer.setPath(copyAssetIfExternal(projectDir, layer.getPath(), "assets\\custom"));
            updated.add(layer);
        }
        project.setImageLayers(updated);
    }

    private String copyAssetIfExternal(Path projectDir, String originalPath, String destinationFolder) throws IOException {
        Path path = Path.of(originalPath);
        if (!path.isAbsolute()) {
            return path.toString().replace('/', '\\');
        }

        Path targetDir = projectDir.resolve(destinationFolder);
        Files.createDirectories(targetDir);
        String originalName = path.getFileName().toString();
        Path target = targetDir.resolve(originalName);
        int suffix = 1;
        while (Files.exists(target) && !Files.isSameFile(path, target)) {
            target = targetDir.resolve(withSuffix(originalName, suffix++));
        }
        if (!Files.exists(target)) {
            Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES);
        }
        return projectDir.relativize(target).toString().replace('/', '\\');
    }

    private String withSuffix(String name, int suffix) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return name + "-" + suffix;
        }
        return name.substring(0, dot) + "-" + suffix + name.substring(dot);
    }
}
