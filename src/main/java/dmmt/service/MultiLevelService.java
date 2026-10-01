package dmmt.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dmmt.model.DmProject;
import dmmt.model.MultiLevelManifest;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Multilevel maps: a package {@code <Name>/<Name>.dmlevels} (manifest) with one ordinary map package per level below
 * {@code levels/<id>/level.dmmap}. Only one level is ever loaded; settings shared by all levels live in the manifest
 * and are applied to a level whenever it is loaded.
 */
public class MultiLevelService {
    public static final String EXTENSION = ".dmlevels";
    public static final String LEVELS_DIR = "levels";
    public static final String LEVEL_FILE = "level" + MapLibraryService.EXTENSION;

    /** Where a level comes from in a {@link PlanItem}. */
    public sealed interface Source permits Existing, Dd2vtt, LibraryMap, ForeignLevel, Empty {
    }

    /** A level that already belongs to the multilevel map. */
    public record Existing(String levelId) implements Source {
    }

    /** A level of another multilevel map that is moved over (merging two multilevel maps). */
    public record ForeignLevel(Path manifestFile, String levelId) implements Source {
    }

    /** A level that is moved out of the multilevel map and becomes an ordinary map named {@code mapName} next to it. */
    public record Extraction(String levelId, String mapName) {
    }

    /** A level file's multilevel map and level id. */
    public record LevelRef(Path manifestFile, String levelId) {
    }

    /** Files that belong together: several files form a multilevel map (with default level names), a single file an ordinary map. */
    public record ImportGroup(String name, List<Path> files, List<String> levelNames) {
        public boolean multiLevel() {
            return files.size() > 1;
        }
    }

    /** A dd2vtt / uvtt file that is imported as a new level. */
    public record Dd2vtt(Path file) implements Source {
    }

    /**
     * A map of the library that is moved into the multilevel map.
     *
     * @param entryPath the map package directory, or the map file itself for loose maps
     * @param mapFile   the {@code .dmmap} file
     */
    public record LibraryMap(Path entryPath, Path mapFile) implements Source {
    }

    /** A new, empty level. */
    public record Empty() implements Source {
    }

    /** One level of the wanted result, in order (lowest level first). */
    public record PlanItem(Source source, String name) {
    }

    /**
     * @param manifestFile the multilevel map, {@code null} if it was deleted (no level left) or collapsed
     * @param collapsedMap the ordinary map it became when only one level was left, else {@code null}
     * @param movedMaps    old map file -> new map file for every map/level that changed place (library map -> level,
     *                     level of another multilevel map -> level, level -> ordinary map, last level -> collapsed map)
     */
    public record ApplyResult(Path manifestFile, Path collapsedMap, Map<Path, Path> movedMaps,
                              List<String> removedLevelIds) {
    }

    /** A loaded level, ready to be shown. */
    public record LoadedLevel(MultiLevelManifest manifest, MultiLevelManifest.Level level, Path levelFile,
                              DmProject project) {
    }

    public interface Progress {
        void report(int index, int total, String name);
    }

    private final ObjectMapper mapper = JsonMappers.create()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final MapLibraryService library;
    private final ProjectService projectService;
    private final Dd2vttImportService importService;
    private final MapRotationService rotationService = new MapRotationService();

    public MultiLevelService(MapLibraryService library, ProjectService projectService, Dd2vttImportService importService) {
        this.library = library;
        this.projectService = projectService;
        this.importService = importService;
    }

    // ---- Files ----

    public static boolean isMultiLevelFile(Path path) {
        return path != null && path.getFileName() != null
                && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(EXTENSION);
    }

    /** The manifest directly inside {@code dir}, or {@code null} if {@code dir} is not a multilevel map package. */
    public static Path findManifest(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        Path found = null;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                if (Files.isRegularFile(child) && isMultiLevelFile(child)) {
                    if (found != null) {
                        return null;
                    }
                    found = child;
                }
            }
        }
        return found;
    }

    public static Path levelFile(Path manifestFile, MultiLevelManifest.Level level) {
        return packageDir(manifestFile).resolve(level.getFolder()).resolve(LEVEL_FILE).normalize();
    }

    private static Path packageDir(Path manifestFile) {
        return manifestFile.toAbsolutePath().normalize().getParent();
    }

    public MultiLevelManifest loadManifest(Path manifestFile) throws IOException {
        MultiLevelManifest manifest = mapper.readValue(manifestFile.toFile(), MultiLevelManifest.class);
        if (manifest.getLevels() == null) {
            manifest.setLevels(new ArrayList<>());
        }
        return manifest;
    }

    public void saveManifest(Path manifestFile, MultiLevelManifest manifest) throws IOException {
        Files.createDirectories(manifestFile.toAbsolutePath().getParent());
        Path temp = manifestFile.resolveSibling(manifestFile.getFileName() + ".tmp");
        mapper.writeValue(temp.toFile(), manifest);
        Files.move(temp, manifestFile, StandardCopyOption.REPLACE_EXISTING);
    }

    // ---- Loading and saving levels ----

    /** Loads a level ({@code null} = the level opened last, else the lowest), applies the shared settings and remembers it as the current level. */
    public LoadedLevel loadLevel(Path manifestFile, String levelId) throws IOException {
        MultiLevelManifest manifest = loadManifest(manifestFile);
        MultiLevelManifest.Level level = levelId == null ? manifest.startLevel() : manifest.findLevel(levelId);
        if (level == null) {
            throw new IOException(levelId == null ? "The multilevel map has no levels." : "The level no longer exists.");
        }
        Path levelFile = levelFile(manifestFile, level);
        DmProject project = projectService.load(levelFile);
        boolean changed = !level.getId().equals(manifest.getCurrentLevelId());
        manifest.setCurrentLevelId(level.getId());
        if (manifest.getShared() == null) {
            manifest.setShared(captureShared(project));
            changed = true;
        } else {
            applyShared(manifest.getShared(), project);
        }
        if (changed) {
            saveManifest(manifestFile, manifest);
            writePackageThumbnail(manifestFile, levelFile);
        }
        return new LoadedLevel(manifest, level, levelFile, project);
    }

    /** Saves the open level, remembers it as the current level and stores its shared settings for all levels. */
    public MultiLevelManifest saveLevel(Path manifestFile, String levelId, DmProject project) throws IOException {
        MultiLevelManifest manifest = loadManifest(manifestFile);
        MultiLevelManifest.Level level = manifest.findLevel(levelId);
        if (level == null) {
            throw new IOException("The level no longer exists.");
        }
        Path levelFile = levelFile(manifestFile, level);
        projectService.save(levelFile, project);
        manifest.setCurrentLevelId(levelId);
        manifest.setShared(captureShared(project));
        saveManifest(manifestFile, manifest);
        writePackageThumbnail(manifestFile, levelFile);
        return manifest;
    }

    public static MultiLevelManifest.SharedSettings captureShared(DmProject project) {
        DmProject.LightingState lighting = project.getLighting();
        DmProject.WeatherState weather = project.getWeather() == null ? null : DmProject.WeatherState.builder()
                .type(project.getWeather().getType())
                .intensity(project.getWeather().getIntensity())
                .build();
        DmProject.TextSettings text = project.getLastTextSettings();
        return MultiLevelManifest.SharedSettings.builder()
                .timeOfDayPreset(lighting.getTimeOfDayPreset())
                .ambientBrightness(lighting.getAmbientBrightness() == null ? new TreeMap<>()
                        : new TreeMap<>(lighting.getAmbientBrightness()))
                .weather(weather)
                .imageLayersLocked(project.getMap().imageLayersLockedOrDefault())
                .fogEnabled(project.getFog().isEnabled())
                .rotationQuarterTurns(Math.floorMod(project.getMap().getRotationQuarterTurns(), 4))
                .playerZoomStep(project.getViews().getPlayerZoomStep())
                .dmZoom(project.getViews().getDmCamera().getZoom())
                .textLayerVisible(project.isTextLayerVisible())
                .lastTextSettings(text == null ? null : DmProject.TextSettings.builder()
                        .fontSize(text.getFontSize())
                        .textColor(text.getTextColor())
                        .backgroundColor(text.getBackgroundColor())
                        .borderColor(text.getBorderColor())
                        .autoSize(text.isAutoSize())
                        .build())
                .build();
    }

    /** Makes a level match the shared settings; the level is rotated to the shared rotation. */
    public void applyShared(MultiLevelManifest.SharedSettings shared, DmProject project) {
        if (shared == null) {
            return;
        }
        if (shared.getTimeOfDayPreset() != null) {
            project.getLighting().setTimeOfDayPreset(shared.getTimeOfDayPreset());
        }
        project.getLighting().setAmbientBrightness(shared.getAmbientBrightness() == null ? new TreeMap<>()
                : new TreeMap<>(shared.getAmbientBrightness()));
        if (shared.getWeather() != null) {
            project.setWeather(DmProject.WeatherState.builder()
                    .type(shared.getWeather().getType())
                    .intensity(shared.getWeather().getIntensity())
                    .build());
        }
        if (shared.getImageLayersLocked() != null) {
            project.getMap().setImageLayersLocked(shared.getImageLayersLocked());
        }
        project.getFog().setEnabled(shared.isFogEnabled());
        project.getViews().setPlayerZoomStep(shared.getPlayerZoomStep());
        if (shared.getDmZoom() != null && shared.getDmZoom() > 0) {
            project.getViews().getDmCamera().setZoom(shared.getDmZoom());
        }
        project.setTextLayerVisible(shared.isTextLayerVisible());
        if (shared.getLastTextSettings() != null) {
            DmProject.TextSettings text = shared.getLastTextSettings();
            project.setLastTextSettings(DmProject.TextSettings.builder()
                    .fontSize(text.getFontSize())
                    .textColor(text.getTextColor())
                    .backgroundColor(text.getBackgroundColor())
                    .borderColor(text.getBorderColor())
                    .autoSize(text.isAutoSize())
                    .build());
        }
        int turns = Math.floorMod(shared.getRotationQuarterTurns() - project.getMap().getRotationQuarterTurns(), 4);
        for (int i = 0; i < turns; i++) {
            rotationService.rotateClockwise(project);
        }
    }

    // ---- Thumbnails ----

    /** Thumbnail of the whole map: the level opened last (or the lowest level). */
    public byte[] loadOrCreateThumbnail(Path manifestFile) throws IOException {
        Path thumbnail = ThumbnailService.thumbnailFile(manifestFile);
        if (Files.isRegularFile(thumbnail)) {
            return Files.readAllBytes(thumbnail);
        }
        MultiLevelManifest manifest = loadManifest(manifestFile);
        MultiLevelManifest.Level start = manifest.startLevel();
        if (start == null) {
            return null;
        }
        byte[] png = projectService.loadOrCreateThumbnail(levelFile(manifestFile, start));
        if (png != null) {
            Files.write(thumbnail, png);
        }
        return png;
    }

    private void writePackageThumbnail(Path manifestFile, Path levelFile) {
        try {
            Path target = ThumbnailService.thumbnailFile(manifestFile);
            Path source = ThumbnailService.thumbnailFile(levelFile);
            if (Files.isRegularFile(source)) {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.deleteIfExists(target);
            }
        } catch (IOException | RuntimeException ignored) {
            // A missing thumbnail never fails a save; it is regenerated lazily.
        }
    }

    // ---- Creating and changing the level list ----

    /** Creates a new multilevel map named {@code name} in {@code folder} from the planned levels (at least two). */
    public ApplyResult create(Path folder, String name, List<PlanItem> plan, Progress progress) throws IOException {
        if (plan.size() < 2) {
            throw new IOException("A multilevel map needs at least two levels.");
        }
        for (PlanItem item : plan) {
            if (item.source() instanceof Existing) {
                throw new IOException("A new multilevel map cannot contain existing levels.");
            }
        }
        Path manifestFile = library.newMultiLevelFile(folder, name);
        Path dir = manifestFile.getParent();
        Files.createDirectories(dir);
        try {
            return apply(manifestFile, plan, List.of(), progress);
        } catch (MapsLeftBehindException ex) {
            // Maps that could not be moved back are still in the package: keep it so nothing is lost.
            throw ex;
        } catch (IOException | RuntimeException ex) {
            // Everything was rolled back, so the half-built package can go.
            try {
                MapLibraryService.deleteRecursive(dir);
            } catch (IOException ignored) {
            }
            throw ex;
        }
    }

    /** Splits a multilevel map into ordinary maps next to it (one per level) and removes the multilevel map. */
    public ApplyResult dissolve(Path manifestFile, Progress progress) throws IOException {
        MultiLevelManifest manifest = loadManifest(manifestFile);
        List<Extraction> extractions = new ArrayList<>();
        for (MultiLevelManifest.Level level : manifest.getLevels()) {
            extractions.add(new Extraction(level.getId(), suggestedMapName(manifestFile, level)));
        }
        return apply(manifestFile, List.of(), extractions, progress);
    }

    /** Name for a level that becomes an ordinary map: its original name, else {@code <map name> <level name>}. */
    public static String suggestedMapName(Path manifestFile, MultiLevelManifest.Level level) {
        if (level.getOriginalName() != null && !level.getOriginalName().isBlank()) {
            return level.getOriginalName().trim();
        }
        return MapLibraryService.stripExtension(manifestFile.getFileName().toString()) + " " + level.getName();
    }

    public ApplyResult apply(Path manifestFile, List<PlanItem> plan, Progress progress) throws IOException {
        return apply(manifestFile, plan, List.of(), progress);
    }

    /**
     * Makes the levels of a multilevel map match {@code plan}: new levels are imported/moved in (also levels of other
     * multilevel maps), existing levels are renamed and reordered, {@code extractions} become ordinary maps next to
     * the multilevel map, and levels in neither list are deleted. With no level left the map is deleted, with one
     * level left it becomes an ordinary map. Everything up to saving the new manifest is rolled back on failure.
     */
    public ApplyResult apply(Path manifestFile, List<PlanItem> plan, List<Extraction> extractions, Progress progress)
            throws IOException {
        manifestFile = manifestFile.toAbsolutePath().normalize();
        Path dir = manifestFile.getParent();
        Path folder = dir.getParent();
        MultiLevelManifest manifest = Files.isRegularFile(manifestFile) ? loadManifest(manifestFile)
                : MultiLevelManifest.builder().build();
        validatePlan(manifestFile, manifest, plan, extractions);
        Map<Path, MultiLevelManifest> foreign = new LinkedHashMap<>();
        for (PlanItem item : plan) {
            if (item.source() instanceof ForeignLevel level) {
                Path file = level.manifestFile().toAbsolutePath().normalize();
                if (!foreign.containsKey(file)) {
                    foreign.put(file, loadManifest(file));
                }
            }
        }

        int total = (int) plan.stream().filter(item -> !(item.source() instanceof Existing)).count() + extractions.size();
        int done = 0;
        List<MultiLevelManifest.Level> created = new ArrayList<>(plan.size());
        List<Path> createdDirs = new ArrayList<>();
        Map<Path, Path> moved = new LinkedHashMap<>();
        try {
            // Imports can fail on bad files, so they run before anything in the library is moved.
            for (PlanItem item : plan) {
                if (item.source() instanceof Dd2vtt || item.source() instanceof Empty) {
                    if (progress != null) {
                        progress.report(++done, total, item.name());
                    }
                    MultiLevelManifest.Level level = newLevel(item.name());
                    Path levelDir = dir.resolve(level.getFolder());
                    createdDirs.add(levelDir);
                    Files.createDirectories(levelDir);
                    DmProject project;
                    if (item.source() instanceof Dd2vtt(Path dd2vttFile)) {
                        level.setOriginalName(MapLibraryService.stripExtension(dd2vttFile.getFileName().toString()));
                        project = importService.importToProject(dd2vttFile, levelDir);
                    } else {
                        project = DmProject.builder().build();
                        project.getMap().setSourceType("custom");
                        new FogService().ensureMask(project);
                    }
                    projectService.save(levelDir.resolve(LEVEL_FILE), project);
                    created.add(level);
                } else {
                    created.add(null);
                }
            }
        } catch (IOException | RuntimeException ex) {
            for (Path createdDir : createdDirs) {
                try {
                    MapLibraryService.deleteRecursive(createdDir);
                } catch (IOException ignored) {
                }
            }
            throw ex;
        }

        List<Undo> undoMoves = new ArrayList<>();
        List<Path> looseOriginals = new ArrayList<>();
        List<MultiLevelManifest.Level> levels = new ArrayList<>(plan.size());
        Set<String> kept = new HashSet<>();
        Set<String> extractedIds = new HashSet<>();
        Map<Path, Path> extractedMaps = new LinkedHashMap<>();
        List<MultiLevelManifest.Level> removed;
        List<String> removedIds;
        MultiLevelManifest.SharedSettings sharedBefore = manifest.getShared();
        try {
            // Taken before anything is moved, so a map that cannot be read fails the change without side effects.
            MultiLevelManifest.SharedSettings shared = manifest.getShared();
            if (shared == null && !plan.isEmpty()) {
                shared = captureShared(projectService.load(firstLevelFile(manifestFile, manifest, plan, created, foreign)));
            }
            for (int i = 0; i < plan.size(); i++) {
                PlanItem item = plan.get(i);
                switch (item.source()) {
                    case LibraryMap map -> {
                        if (progress != null) {
                            progress.report(++done, total, item.name());
                        }
                        MultiLevelManifest.Level level = newLevel(item.name());
                        level.setOriginalName(MapLibraryService.stripExtension(map.mapFile().getFileName().toString()));
                        Path levelFile = dir.resolve(level.getFolder()).resolve(LEVEL_FILE);
                        undoMoves.add(0, moveLibraryMap(map, levelFile));
                        if (!Files.isDirectory(map.entryPath())) {
                            looseOriginals.add(map.mapFile());
                        }
                        moved.put(map.mapFile().toAbsolutePath().normalize(), levelFile);
                        created.set(i, level);
                    }
                    case ForeignLevel(Path foreignManifest, String foreignLevelId) -> {
                        if (progress != null) {
                            progress.report(++done, total, item.name());
                        }
                        Path sourceManifest = foreignManifest.toAbsolutePath().normalize();
                        MultiLevelManifest.Level sourceLevel = foreign.get(sourceManifest).findLevel(foreignLevelId);
                        MultiLevelManifest.Level level = newLevel(item.name());
                        level.setOriginalName(sourceLevel.getOriginalName());
                        Path from = levelFile(sourceManifest, sourceLevel);
                        Path to = dir.resolve(level.getFolder()).resolve(LEVEL_FILE);
                        undoMoves.add(0, movePackage(from.getParent(), from, to));
                        moved.put(from, to);
                        created.set(i, level);
                    }
                    case Existing _ -> {
                    }
                    case Dd2vtt _ -> {
                    }
                    case Empty _ -> {
                    }
                }
            }
            for (Extraction extraction : extractions) {
                MultiLevelManifest.Level level = manifest.findLevel(extraction.levelId());
                if (progress != null) {
                    progress.report(++done, total, extraction.mapName());
                }
                Path from = levelFile(manifestFile, level);
                Path mapFile = library.uniqueNewMapFile(folder, extraction.mapName());
                undoMoves.add(0, movePackage(from.getParent(), from, mapFile));
                extractedIds.add(level.getId());
                extractedMaps.put(from, mapFile);
            }

            for (int i = 0; i < plan.size(); i++) {
                PlanItem item = plan.get(i);
                if (item.source() instanceof Existing(String levelId)) {
                    MultiLevelManifest.Level level = manifest.findLevel(levelId);
                    level.setName(cleanLevelName(item.name(), i));
                    levels.add(level);
                    kept.add(level.getId());
                } else {
                    levels.add(created.get(i));
                }
            }
            removed = manifest.getLevels().stream()
                    .filter(level -> !kept.contains(level.getId()) && !extractedIds.contains(level.getId())).toList();
            removedIds = removed.stream().map(MultiLevelManifest.Level::getId).toList();
            if (!levels.isEmpty()) {
                manifest.setCurrentLevelId(nextCurrentLevel(manifest, kept));
                manifest.setLevels(levels);
                manifest.setShared(shared);
                saveManifest(manifestFile, manifest);
            }
        } catch (IOException | RuntimeException ex) {
            boolean allBack = true;
            for (Undo undo : undoMoves) {
                allBack &= undo.run();
            }
            for (Path createdDir : createdDirs) {
                try {
                    MapLibraryService.deleteRecursive(createdDir);
                } catch (IOException ignored) {
                }
            }
            if (!allBack) {
                throw new MapsLeftBehindException(ex.getMessage() + " Some maps could not be moved back; they are in \""
                        + dir.resolve(LEVELS_DIR) + "\".", ex);
            }
            throw ex;
        }

        // From here on the new state is saved; clean-up failures no longer lose anything.
        moved.putAll(extractedMaps);
        for (Path loose : looseOriginals) {
            try {
                Files.deleteIfExists(loose);
            } catch (IOException ignored) {
                // a leftover copy of a map that is now a level
            }
        }
        for (Path mapFile : extractedMaps.values()) {
            // Outside the multilevel map the level keeps the look it had inside.
            try {
                DmProject project = projectService.load(mapFile);
                applyShared(sharedBefore, project);
                projectService.save(mapFile, project);
            } catch (IOException | RuntimeException ignored) {
                // the map just keeps its own settings
            }
        }
        for (Map.Entry<Path, MultiLevelManifest> source : foreign.entrySet()) {
            releaseTakenLevels(source.getKey(), source.getValue(), moved);
        }
        for (MultiLevelManifest.Level level : removed) {
            Path levelDir = dir.resolve(level.getFolder()).normalize();
            if (levelDir.startsWith(dir.resolve(LEVELS_DIR)) && Files.isDirectory(levelDir)) {
                try {
                    MapLibraryService.deleteRecursive(levelDir);
                } catch (IOException ignored) {
                    // no longer listed, so it is just unused disk space
                }
            }
        }
        if (levels.isEmpty()) {
            try {
                MapLibraryService.deleteRecursive(dir);
            } catch (IOException ignored) {
                // every level is gone or moved out already; a leftover file only wastes disk space
            }
            return new ApplyResult(null, null, moved, removedIds);
        }
        if (levels.size() == 1) {
            Path collapsed = collapseQuietly(manifestFile, moved);
            if (collapsed != null) {
                return new ApplyResult(null, collapsed, moved, removedIds);
            }
        }
        Path thumbnailLevel = levelFile(manifestFile, manifest.startLevel());
        try {
            projectService.loadOrCreateThumbnail(thumbnailLevel);
        } catch (IOException | RuntimeException ignored) {
            // the level just has no thumbnail
        }
        writePackageThumbnail(manifestFile, thumbnailLevel);
        return new ApplyResult(manifestFile, null, moved, removedIds);
    }

    /** Removes the levels another multilevel map gave away; it collapses or is deleted when 1 / 0 levels are left. */
    private void releaseTakenLevels(Path sourceManifest, MultiLevelManifest before, Map<Path, Path> moved) {
        try {
            Set<String> left = new HashSet<>();
            List<MultiLevelManifest.Level> remaining = new ArrayList<>();
            for (MultiLevelManifest.Level level : before.getLevels()) {
                if (Files.isRegularFile(levelFile(sourceManifest, level))) {
                    remaining.add(level);
                    left.add(level.getId());
                }
            }
            if (remaining.isEmpty()) {
                MapLibraryService.deleteRecursive(sourceManifest.getParent());
                return;
            }
            String current = nextCurrentLevel(before, left);
            before.setLevels(remaining);
            before.setCurrentLevelId(current);
            saveManifest(sourceManifest, before);
            if (remaining.size() == 1) {
                collapseQuietly(sourceManifest, moved);
            } else {
                writePackageThumbnail(sourceManifest, levelFile(sourceManifest, before.startLevel()));
            }
        } catch (IOException | RuntimeException ignored) {
            // the other multilevel map keeps its (now shorter) list of levels
        }
    }

    /** {@link #collapse}, recording the move; {@code null} (map unchanged) if it failed. */
    private Path collapseQuietly(Path manifestFile, Map<Path, Path> moved) {
        try {
            MultiLevelManifest manifest = loadManifest(manifestFile);
            Path levelFile = levelFile(manifestFile, manifest.getLevels().get(0));
            Path mapFile = collapse(manifestFile, manifest);
            relocate(moved, levelFile, mapFile);
            return mapFile;
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    /**
     * Turns a multilevel map with a single level into an ordinary map of the same name at the same place
     * (shared settings applied). Returns the new map file.
     */
    private Path collapse(Path manifestFile, MultiLevelManifest manifest) throws IOException {
        MultiLevelManifest.Level level = manifest.getLevels().get(0);
        Path levelFile = levelFile(manifestFile, level);
        DmProject project = projectService.load(levelFile);
        applyShared(manifest.getShared(), project);
        projectService.save(levelFile, project);

        Path dir = manifestFile.getParent();
        String name = MapLibraryService.stripExtension(manifestFile.getFileName().toString());
        Path renamedLevelFile = levelFile.resolveSibling(name + MapLibraryService.EXTENSION);
        Files.move(levelFile, renamedLevelFile);
        Path old = dir.resolveSibling(dir.getFileName() + ".collapsing-" + System.nanoTime());
        try {
            Files.move(dir, old);
        } catch (IOException | RuntimeException ex) {
            Files.move(renamedLevelFile, levelFile);
            throw ex;
        }
        Path levelDirInOld = old.resolve(level.getFolder());
        try {
            Files.move(levelDirInOld, dir);
        } catch (IOException | RuntimeException ex) {
            Files.move(old, dir);
            Files.move(renamedLevelFile, levelFile);
            throw ex;
        }
        try {
            MapLibraryService.deleteRecursive(old);
        } catch (IOException ignored) {
            // leftover of the old package; the map itself is complete
        }
        return dir.resolve(name + MapLibraryService.EXTENSION);
    }

    /** Records {@code from -> to}; earlier moves that ended at {@code from} now end at {@code to}. */
    private static void relocate(Map<Path, Path> moved, Path from, Path to) {
        Path key = from.toAbsolutePath().normalize();
        boolean chained = false;
        for (Map.Entry<Path, Path> entry : moved.entrySet()) {
            if (entry.getValue().toAbsolutePath().normalize().equals(key)) {
                entry.setValue(to);
                chained = true;
            }
        }
        if (!chained) {
            moved.put(key, to);
        }
    }

    /** Where a level file lives: its multilevel map and level id, or {@code null} if it is no level. */
    public LevelRef locateLevel(Path file) {
        try {
            Path levelFile = file.toAbsolutePath().normalize();
            Path levelDir = levelFile.getParent();
            Path levelsDir = levelDir == null ? null : levelDir.getParent();
            if (levelsDir == null || levelsDir.getFileName() == null
                    || !LEVELS_DIR.equals(levelsDir.getFileName().toString())) {
                return null;
            }
            Path manifestFile = findManifest(levelsDir.getParent());
            if (manifestFile == null) {
                return null;
            }
            for (MultiLevelManifest.Level level : loadManifest(manifestFile).getLevels()) {
                if (levelFile(manifestFile, level).equals(levelFile)) {
                    return new LevelRef(manifestFile.toAbsolutePath().normalize(), level.getId());
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // not readable: treat as no level
        }
        return null;
    }

    /** The file the lowest planned level is read from (before anything is moved). */
    private static Path firstLevelFile(Path manifestFile, MultiLevelManifest manifest, List<PlanItem> plan,
                                       List<MultiLevelManifest.Level> created, Map<Path, MultiLevelManifest> foreign) {
        PlanItem first = plan.get(0);
        return switch (first.source()) {
            case Existing(String levelId) -> levelFile(manifestFile, manifest.findLevel(levelId));
            case LibraryMap(_, Path mapFile) -> mapFile;
            case ForeignLevel(Path foreignManifest, String foreignLevelId) -> {
                Path sourceManifest = foreignManifest.toAbsolutePath().normalize();
                yield levelFile(sourceManifest, foreign.get(sourceManifest).findLevel(foreignLevelId));
            }
            case Dd2vtt _ -> levelFile(manifestFile, created.get(0));
            case Empty _ -> levelFile(manifestFile, created.get(0));
        };
    }

    /** Undo of one map move; {@code false} if the map could not be put back. */
    private interface Undo {
        boolean run();
    }

    /** A failed change left maps inside the multilevel package, so the package must not be deleted. */
    static final class MapsLeftBehindException extends IOException {
        MapsLeftBehindException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private void validatePlan(Path manifestFile, MultiLevelManifest manifest, List<PlanItem> plan,
                              List<Extraction> extractions) throws IOException {
        Set<String> seen = new HashSet<>();
        Path dir = manifestFile.getParent();
        for (PlanItem item : plan) {
            switch (item.source()) {
            case Existing(String levelId) -> {
                if (manifest.findLevel(levelId) == null) {
                    throw new IOException("A level of the map no longer exists.");
                }
                if (!seen.add(levelId)) {
                    throw new IOException("A level is listed twice.");
                }
            }
            case LibraryMap map -> {
                Path entry = map.entryPath().toAbsolutePath().normalize();
                if (!Files.exists(map.mapFile())) {
                    throw new IOException("\"" + item.name() + "\" no longer exists.");
                }
                if (isMultiLevelFile(map.mapFile())) {
                    throw new IOException("A multilevel map cannot become a level of another one.");
                }
                if (entry.startsWith(dir) || dir.startsWith(entry) && Files.isDirectory(entry)) {
                    throw new IOException("\"" + item.name() + "\" cannot be moved into the multilevel map.");
                }
                if (!seen.add(entry.toString())) {
                    throw new IOException("\"" + item.name() + "\" is listed twice.");
                }
            }
            case ForeignLevel(Path foreignManifest, String foreignLevelId) -> {
                Path sourceManifest = foreignManifest.toAbsolutePath().normalize();
                if (sourceManifest.equals(manifestFile.toAbsolutePath().normalize())) {
                    throw new IOException("\"" + item.name() + "\" already belongs to this multilevel map.");
                }
                if (!Files.isRegularFile(sourceManifest)) {
                    throw new IOException("\"" + item.name() + "\" no longer exists.");
                }
                if (!seen.add(sourceManifest + "#" + foreignLevelId)) {
                    throw new IOException("\"" + item.name() + "\" is listed twice.");
                }
            }
            case Dd2vtt(Path file) -> {
                if (!Files.isRegularFile(file)) {
                    throw new IOException(file.getFileName() + " no longer exists.");
                }
            }
            case Empty _ -> {
            }
            }
        }
        for (Extraction extraction : extractions) {
            if (manifest.findLevel(extraction.levelId()) == null) {
                throw new IOException("A level of the map no longer exists.");
            }
            if (!seen.add(extraction.levelId())) {
                throw new IOException("A level cannot stay and be moved out at the same time.");
            }
            String problem = levelNameProblem(extraction.mapName());
            if (problem != null) {
                throw new IOException(problem);
            }
        }
    }

    /** The level to open after a change: the current one if kept, else the nearest kept level (lower first). */
    private static String nextCurrentLevel(MultiLevelManifest before, Set<String> kept) {
        String current = before.getCurrentLevelId();
        if (current == null || kept.contains(current)) {
            return current;
        }
        int index = before.indexOf(current);
        List<MultiLevelManifest.Level> old = before.getLevels();
        for (int distance = 1; index >= 0 && distance < old.size(); distance++) {
            for (int candidate : new int[]{index - distance, index + distance}) {
                if (candidate >= 0 && candidate < old.size() && kept.contains(old.get(candidate).getId())) {
                    return old.get(candidate).getId();
                }
            }
        }
        return null;
    }

    /** Moves a library map into a level folder; returns an undo that puts it back. Loose originals stay in place. */
    private Undo moveLibraryMap(LibraryMap map, Path levelFile) throws IOException {
        Path levelDir = levelFile.getParent();
        if (Files.isDirectory(map.entryPath())) {
            return movePackage(map.entryPath(), map.mapFile(), levelFile);
        }
        Files.createDirectories(levelDir.getParent());
        // Loose maps share their folder with other files, so they are re-saved with their assets copied in.
        DmProject project = projectService.load(map.mapFile());
        Path base = map.mapFile().toAbsolutePath().getParent();
        if (project.getMap() != null) {
            project.getMap().setImagePath(absolutize(base, project.getMap().getImagePath()));
        }
        for (DmProject.ImageLayer layer : project.getImageLayers()) {
            layer.setPath(absolutize(base, layer.getPath()));
        }
        try {
            projectService.save(levelFile, project);
        } catch (IOException | RuntimeException ex) {
            deleteQuietly(levelDir);
            throw ex;
        }
        // The original stays until the change is saved, so removing the copy loses nothing.
        return () -> deleteQuietly(levelDir);
    }

    /**
     * Moves the map package {@code fromDir} (containing {@code fromFile}) so that its map file ends up at
     * {@code toFile}; returns an undo that puts it back.
     */
    private static Undo movePackage(Path fromDir, Path fromFile, Path toFile) throws IOException {
        Path toDir = toFile.getParent();
        Path movedFile = toDir.resolve(fromDir.relativize(fromFile).toString());
        Files.createDirectories(toDir.getParent());
        Undo undo = () -> {
            try {
                if (Files.exists(toFile) && !Files.exists(movedFile)) {
                    Files.move(toFile, movedFile);
                }
                Files.move(toDir, fromDir);
                return true;
            } catch (IOException | RuntimeException ex) {
                return false;
            }
        };
        Files.move(fromDir, toDir);
        if (!movedFile.getFileName().toString().equals(toFile.getFileName().toString())) {
            try {
                Files.move(movedFile, toFile);
            } catch (IOException | RuntimeException ex) {
                if (!undo.run()) {
                    throw new MapsLeftBehindException("\"" + fromFile.getFileName()
                            + "\" could not be moved back from \"" + toDir + "\".", ex);
                }
                throw ex;
            }
        }
        return undo;
    }

    /** Removes a level copy of a loose map; the original is still in place, so a failure loses nothing. */
    private static boolean deleteQuietly(Path dir) {
        try {
            MapLibraryService.deleteRecursive(dir);
        } catch (IOException ignored) {
            // leftover copy only
        }
        return true;
    }

    private static String absolutize(Path base, String path) {
        if (path == null || path.isBlank() || Path.of(path).isAbsolute()) {
            return path;
        }
        Path resolved = base.resolve(path).normalize();
        return Files.exists(resolved) ? resolved.toString() : path;
    }

    private static MultiLevelManifest.Level newLevel(String name) {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return MultiLevelManifest.Level.builder()
                .id(id)
                .name(name)
                .folder(LEVELS_DIR + "/" + id)
                .build();
    }

    // ---- Names ----

    /** Problem with a level name, or {@code null} if it is fine. */
    public static String levelNameProblem(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            return "Please enter a name.";
        }
        if (name.length() > 100) {
            return "The name is too long (max. 100 characters).";
        }
        return null;
    }

    private static String cleanLevelName(String raw, int index) {
        return levelNameProblem(raw) == null ? raw.trim() : "Level " + (index + 1);
    }

    /** Compares names like a human would: case-insensitive, numbers by value ({@code 2 < 10}). */
    public static final Comparator<String> NATURAL_ORDER = MultiLevelService::compareNatural;

    private static int compareNatural(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int startA = i;
                int startB = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) {
                    i++;
                }
                while (j < b.length() && Character.isDigit(b.charAt(j))) {
                    j++;
                }
                String numberA = a.substring(startA, i).replaceFirst("^0+(?=.)", "");
                String numberB = b.substring(startB, j).replaceFirst("^0+(?=.)", "");
                int byLength = Integer.compare(numberA.length(), numberB.length());
                if (byLength != 0) {
                    return byLength;
                }
                int byValue = numberA.compareTo(numberB);
                if (byValue != 0) {
                    return byValue;
                }
            } else {
                int byChar = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
                if (byChar != 0) {
                    return byChar;
                }
                i++;
                j++;
            }
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    /** Underscores read as spaces, runs of spaces collapsed. */
    private static String normalizeName(String name) {
        return name.replace('_', ' ').replaceAll("\\s+", " ").trim();
    }

    /** Name ending in a number: {@code haus_03}, {@code Inn2}. */
    private static final java.util.regex.Pattern TRAILING_NUMBER =
            java.util.regex.Pattern.compile("^(.*?)[\\s\\-.]*(\\d+)$");
    /** Number followed by a digit-free room label: {@code tower upper levels 02 barracks}. */
    private static final java.util.regex.Pattern LABELLED_NUMBER =
            java.util.regex.Pattern.compile("^(.+?)[\\s\\-.]+(\\d+)[\\s\\-.]+(\\D*\\p{L}\\D*)$");

    /** A file name split into level parts: shared {@code prefix}, level {@code number} and optional {@code label}. */
    private record LevelFileName(String prefix, String number, String label) {
        java.math.BigInteger value() {
            return new java.math.BigInteger(number);
        }
    }

    private static LevelFileName parseLevelFileName(String normalizedName) {
        java.util.regex.Matcher trailing = TRAILING_NUMBER.matcher(normalizedName);
        if (trailing.matches()) {
            String prefix = trimSeparators(trailing.group(1));
            return prefix.isEmpty() ? null : new LevelFileName(prefix, trailing.group(2), "");
        }
        java.util.regex.Matcher labelled = LABELLED_NUMBER.matcher(normalizedName);
        if (labelled.matches()) {
            String prefix = trimSeparators(labelled.group(1));
            return prefix.isEmpty() ? null
                    : new LevelFileName(prefix, labelled.group(2), trimSeparators(labelled.group(3)));
        }
        return null;
    }

    /**
     * Groups import files that look like the levels of one building (same folder, case-insensitive): files sharing the
     * part before a level number, optionally followed by a room label ({@code haus_00 … haus_03},
     * {@code turm_upper_02_barracks … turm_upper_10}), at least two of them and all numbers distinct. A file without a
     * number whose whole name starts that shared part ({@code turm}) joins as the lowest level, if it fits exactly one
     * group. Every other file is a group of its own. Groups keep the order in which their first file appears.
     */
    public static List<ImportGroup> groupLevelFiles(List<Path> files) {
        Map<String, List<Path>> numbered = new LinkedHashMap<>();
        Map<Path, LevelFileName> parsed = new java.util.HashMap<>();
        List<Path> unnumbered = new ArrayList<>();
        for (Path file : files) {
            LevelFileName name = parseLevelFileName(normalizeName(MapLibraryService.stripExtension(file.getFileName().toString())));
            if (name == null) {
                unnumbered.add(file);
            } else {
                parsed.put(file, name);
                numbered.computeIfAbsent(folderKey(file) + name.prefix().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(file);
            }
        }
        Map<String, List<Path>> valid = new LinkedHashMap<>();
        for (Map.Entry<String, List<Path>> entry : numbered.entrySet()) {
            long distinct = entry.getValue().stream().map(file -> parsed.get(file).value()).distinct().count();
            if (entry.getValue().size() > 1 && distinct == entry.getValue().size()) {
                valid.put(entry.getKey(), entry.getValue());
            }
        }
        Map<String, Path> bases = new java.util.HashMap<>();
        for (Path file : unnumbered) {
            String name = normalizeName(MapLibraryService.stripExtension(file.getFileName().toString())).toLowerCase(Locale.ROOT);
            if (name.isEmpty()) {
                continue;
            }
            String folder = folderKey(file);
            List<String> matches = valid.keySet().stream()
                    .filter(key -> key.startsWith(folder))
                    .filter(key -> {
                        String prefix = key.substring(folder.length());
                        return prefix.equals(name) || prefix.startsWith(name + " ");
                    }).toList();
            if (matches.size() == 1) {
                String key = matches.get(0);
                Path current = bases.get(key);
                if (current == null || current.getFileName().toString().length() < file.getFileName().toString().length()) {
                    bases.put(key, file);
                }
            }
        }
        Map<Path, String> groupOf = new java.util.HashMap<>();
        valid.forEach((key, members) -> members.forEach(file -> groupOf.put(file, key)));
        bases.forEach((key, file) -> groupOf.put(file, key));
        List<ImportGroup> groups = new ArrayList<>();
        Set<String> emitted = new HashSet<>();
        for (Path file : files) {
            String key = groupOf.get(file);
            if (key == null) {
                String name = MapLibraryService.stripExtension(file.getFileName().toString());
                groups.add(new ImportGroup(name, List.of(file), List.of(name)));
            } else if (emitted.add(key)) {
                groups.add(levelGroup(valid.get(key), bases.get(key), parsed));
            }
        }
        return groups;
    }

    private static String folderKey(Path file) {
        return file.toAbsolutePath().normalize().getParent() + "|";
    }

    private static ImportGroup levelGroup(List<Path> members, Path base, Map<Path, LevelFileName> parsed) {
        List<Path> ordered = new ArrayList<>(members);
        ordered.sort(Comparator.comparing(file -> parsed.get(file).value()));
        List<Path> groupFiles = new ArrayList<>();
        List<String> levelNames = new ArrayList<>();
        LevelFileName lowest = parsed.get(ordered.get(0));
        if (base != null) {
            groupFiles.add(base);
            java.math.BigInteger below = lowest.value().subtract(java.math.BigInteger.ONE);
            levelNames.add(below.signum() < 0 ? "Base"
                    : "Level " + String.format("%0" + lowest.number().length() + "d", below));
        }
        for (Path file : ordered) {
            LevelFileName name = parsed.get(file);
            groupFiles.add(file);
            levelNames.add("Level " + name.number() + (name.label().isEmpty() ? "" : " – " + name.label()));
        }
        String groupName = base != null ? normalizeName(MapLibraryService.stripExtension(base.getFileName().toString()))
                : parsed.get(members.get(0)).prefix();
        return new ImportGroup(groupName, groupFiles, levelNames);
    }

    /** Length of the longest common prefix that ends at a word boundary in every name. */
    private static int commonPrefixLength(List<String> names) {
        if (names.isEmpty()) {
            return 0;
        }
        String first = names.get(0);
        int limit = first.length();
        for (String name : names) {
            limit = Math.min(limit, name.length());
        }
        int same = 0;
        while (same < limit) {
            char c = Character.toLowerCase(first.charAt(same));
            int index = same;
            if (names.stream().allMatch(name -> Character.toLowerCase(name.charAt(index)) == c)) {
                same++;
            } else {
                break;
            }
        }
        for (int length = same; length > 0; length--) {
            int end = length;
            if (names.stream().allMatch(name -> end == name.length() || !Character.isLetterOrDigit(name.charAt(end))
                    || !Character.isLetterOrDigit(name.charAt(end - 1)))) {
                return length;
            }
        }
        return 0;
    }

    private static String trimSeparators(String text) {
        return text.replaceAll("^[\\s\\-_.]+|[\\s\\-_.]+$", "");
    }

    /** Suggested name of a multilevel map made of maps with these names: the part they all share. */
    public static String commonName(List<String> names) {
        if (names.isEmpty()) {
            return "Multilevel map";
        }
        List<String> normalized = names.stream().map(MultiLevelService::normalizeName).toList();
        String common = trimSeparators(normalized.get(0).substring(0, commonPrefixLength(normalized)));
        return common.isEmpty() ? normalized.get(0) : common;
    }

    /**
     * Suggested level names: the names without the part they all share ({@code Level N} if nothing is left, a bare
     * number becomes {@code Level <number>}). A single name is kept as it is.
     */
    public static List<String> defaultLevelNames(List<String> names) {
        if (names.size() == 1) {
            return List.of(normalizeName(names.get(0)).isEmpty() ? "Level 1" : normalizeName(names.get(0)));
        }
        List<String> normalized = names.stream().map(MultiLevelService::normalizeName).toList();
        int prefix = commonPrefixLength(normalized);
        List<String> result = new ArrayList<>(names.size());
        for (int i = 0; i < normalized.size(); i++) {
            String rest = trimSeparators(normalized.get(i).substring(prefix));
            if (rest.isEmpty()) {
                result.add("Level " + (i + 1));
            } else if (rest.chars().allMatch(Character::isDigit)) {
                result.add("Level " + rest);
            } else {
                result.add(rest);
            }
        }
        return result;
    }
}
