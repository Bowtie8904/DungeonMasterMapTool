package dmmt.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dmmt.model.DmProject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public class ProjectService {
    private final ObjectMapper objectMapper = JsonMappers.create()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final FogService fogService = new FogService();
    private final ThumbnailService thumbnailService = new ThumbnailService();

    public DmProject load(Path projectFile) throws IOException {
        DmProject project = objectMapper.readValue(projectFile.toFile(), DmProject.class);
        fogService.ensureMask(project);
        return project;
    }

    /** Deep copy via serialization; used to freeze what the players see. */
    public DmProject copy(DmProject project) throws IOException {
        DmProject copy = objectMapper.readValue(objectMapper.writeValueAsBytes(project), DmProject.class);
        fogService.ensureMask(copy);
        return copy;
    }

    public void save(Path projectFile, DmProject project) throws IOException {
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
