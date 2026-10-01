package dmmt.service;

import dmmt.render.OverlayTextures;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.prefs.Preferences;

/**
 * Global, user editable settings kept in {@value #FILE_NAME} next to the application jar (or in the working
 * directory when started from an IDE). The file uses simple {@code key = value} lines with {@code #} comments and
 * always lists every known setting, including the built-in defaults of all effect textures.
 */
public final class AppSettings {
    public static final String FILE_NAME = "dmmt-settings.ini";
    public static final String SYSTEM_PROPERTY = "dmmt.settings";

    private record Entry(String key, String defaultValue, String comment) {
    }

    private record Section(String title, List<Entry> entries) {
    }

    private static final String[] SECTION_IDS = {"tools", "fog", "lighting", "effects", "text", "building", "player", "performance"};

    private final Path file;
    private final Map<String, String> values = new LinkedHashMap<>();
    private long lastKnownModified;
    private boolean externalChange;

    public AppSettings(Path file) {
        this.file = file;
        if (Files.isRegularFile(file)) {
            reload();
            if (!values.keySet().containsAll(knownKeys())) {
                // Writes newly added settings with their defaults so the file always lists every setting.
                save();
            }
        } else {
            importLegacyPreferences();
            applyRuntimeSettings();
            save();
        }
    }

    /** Settings file next to the running jar, or in the working directory when not running from a jar. */
    public static AppSettings load() {
        return new AppSettings(resolveFile());
    }

    public static Path resolveFile() {
        String override = System.getProperty(SYSTEM_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath();
        }
        try {
            Path location = Path.of(AppSettings.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(location) && location.getParent() != null) {
                return location.getParent().resolve(FILE_NAME);
            }
        } catch (URISyntaxException | RuntimeException e) {
            // fall through to the working directory
        }
        return Path.of(System.getProperty("user.dir")).resolve(FILE_NAME).toAbsolutePath();
    }

    public Path getFile() {
        return file;
    }

    // ---- typed access (mirrors java.util.prefs.Preferences) ----

    public synchronized String get(String key, String defaultValue) {
        String value = values.get(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    public synchronized int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(get(key, "").trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public synchronized double getDouble(String key, double defaultValue) {
        try {
            double value = Double.parseDouble(get(key, "").trim());
            return Double.isFinite(value) ? value : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public synchronized boolean getBoolean(String key, boolean defaultValue) {
        String value = get(key, "").trim();
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        return defaultValue;
    }

    public synchronized void put(String key, String value) {
        mergeExternalEdits();
        if (value == null) {
            values.remove(key);
        } else {
            values.put(key, value.replace('\r', ' ').replace('\n', ' '));
        }
        save();
    }

    public void putInt(String key, int value) {
        put(key, String.valueOf(value));
    }

    public void putDouble(String key, double value) {
        put(key, String.valueOf(value));
    }

    public void putBoolean(String key, boolean value) {
        put(key, String.valueOf(value));
    }

    // ---- textures and tuning values ----

    /** Pushes the texture section of this file into {@link OverlayTextures}. */
    public void applyTextureSettings() {
        OverlayTextures.applySettings(this::lookup);
    }

    /** Loads the {@link Tuning} values and texture defaults from this file; called whenever the file is (re)read. */
    private void applyRuntimeSettings() {
        Tuning.apply(this::lookup);
        applyTextureSettings();
    }

    private synchronized String lookup(String key) {
        String value = values.get(key);
        return value == null || value.isBlank() ? null : value;
    }

    /** True once if the file was edited by hand since the last check; the new values are already loaded. */
    public synchronized boolean pollExternalChange() {
        mergeExternalEdits();
        boolean changed = externalChange;
        externalChange = false;
        return changed;
    }

    // ---- file handling ----

    private void mergeExternalEdits() {
        try {
            if (Files.isRegularFile(file) && Files.getLastModifiedTime(file).toMillis() != lastKnownModified) {
                reload();
                externalChange = true;
            }
        } catch (IOException e) {
            // keep the in-memory values
        }
    }

    private synchronized void reload() {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            values.clear();
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                values.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
            }
            lastKnownModified = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException | RuntimeException e) {
            System.err.println("Could not read settings file " + file + ": " + e.getMessage());
        }
        applyRuntimeSettings();
    }

    private synchronized void save() {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, render(), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            lastKnownModified = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException | RuntimeException e) {
            System.err.println("Could not write settings file " + file + ": " + e.getMessage());
        }
    }

    private String render() {
        return render(values);
    }

    /** Settings-file text with every built-in default; kept in the repository as dmmt-settings.default.ini. */
    public static String renderDefaults() {
        return render(Map.of());
    }

    private static String render(Map<String, String> values) {
        StringBuilder out = new StringBuilder();
        out.append("# Dungeon Master Map Tool settings\n");
        out.append("# Edit this file with any text editor. Lines are 'key = value'; lines starting with # are comments.\n");
        out.append("# Edits made while the application runs are picked up when its window regains focus; entries marked\n");
        out.append("# 'Restart required' (and the window/panel/player/fog/text values the app itself changes) are read at startup.\n");
        out.append("# Delete a line to fall back to the built-in default. Invalid values are ignored, numbers are clamped to their range.\n");
        out.append("# Every entry is explained in docs/SETTINGS.md of the project.\n");

        Set<String> written = new java.util.HashSet<>();
        for (Section section : sections()) {
            out.append("\n# ---- ").append(section.title()).append(" ----\n");
            for (Entry entry : section.entries()) {
                if (entry.comment() != null) {
                    out.append("# ").append(entry.comment()).append('\n');
                }
                String value = values.getOrDefault(entry.key(), entry.defaultValue());
                if (value == null) {
                    out.append("# ").append(entry.key()).append(" = \n");
                } else {
                    out.append(entry.key()).append(" = ").append(value).append('\n');
                }
                written.add(entry.key());
            }
        }

        List<String> extra = new ArrayList<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (!written.contains(e.getKey())) {
                extra.add(e.getKey() + " = " + e.getValue());
            }
        }
        if (!extra.isEmpty()) {
            out.append("\n# ---- Other ----\n");
            extra.forEach(line -> out.append(line).append('\n'));
        }
        return out.toString();
    }

    private static List<Section> sections() {
        List<Section> sections = new ArrayList<>();
        sections.add(new Section(Tuning.WINDOW, List.of(
                new Entry("ui.sidebarVisible", "true", "Show the sidebar (true/false)."),
                new Entry("ui.controlsExpanded", "true", "Show the toolbar controls (true/false)."),
                new Entry("ui.performanceMode", "false", "Performance mode for slow machines (true/false); see the Performance mode section."))));

        List<Entry> panels = new ArrayList<>();
        for (String id : SECTION_IDS) {
            panels.add(new Entry("ui.section." + id + ".expanded", "true", null));
        }
        sections.add(new Section("Collapsible sidebar sections (true = expanded)", panels));

        sections.add(new Section(Tuning.PLAYER, List.of(
                new Entry("player.screenIndex", "0", "Index of the monitor used for the player view."),
                new Entry("player.tileInches", "1.0", "Physical size of one grid tile on the player screen, in inches."),
                new Entry("player.screenDiagonalInches.0", null, "Screen diagonal per monitor index in inches, e.g. player.screenDiagonalInches.1 = 27."))));

        sections.add(new Section(Tuning.FOG, List.of(
                new Entry("fog.cellsPerGrid", String.valueOf(FogService.DEFAULT_CELLS_PER_GRID), "Fog cells per grid cell (edge sharpness of fog of war), fog.cellsPerGrid.min to fog.cellsPerGrid.max."),
                new Entry("fog.softness", String.valueOf(dmmt.render.CanvasMapRenderer.DEFAULT_FOG_SOFTNESS), "Width of the soft fog edge in grid tiles (0 = hard edge, max fog.softness.max)."),
                new Entry("fog.fadeAnimation", "true", "Fade fog in and out when it is revealed or hidden (true/false)."),
                new Entry("lighting.tint", String.valueOf(dmmt.render.CanvasMapRenderer.DEFAULT_LIGHT_TINT), "Strength of the light colour tint over lit areas (0 to lighting.tint.max)."))));

        sections.add(new Section(Tuning.FRAME_RATES, List.of(
                new Entry("render.targetFps", "60", "Frame rate while interacting with the map (1 to render.maxFps)."),
                new Entry("render.animationFps", "30", "Frame rate while only effect textures or light flicker move (capped by targetFps)."),
                new Entry("render.idleFps", "10", "Frame rate when nothing moves and there was no input for input.recentInputMs."))));

        sections.add(new Section(Tuning.AUTOSAVE, List.of(
                new Entry("autosave.enabled", "true", "Save the project automatically (true/false)."),
                new Entry("autosave.minutes", "2", "Minutes between automatic saves."))));

        sections.add(new Section(Tuning.TEXT, List.of(
                new Entry("text.fontSize", String.valueOf(dmmt.model.DmProject.DEFAULT_TEXT_SIZE), "Default font size of new text labels."),
                new Entry("text.textColor", dmmt.model.DmProject.DEFAULT_TEXT_COLOR, "Default text colour."),
                new Entry("text.backgroundColor", dmmt.model.DmProject.TRANSPARENT, "Default text background colour."),
                new Entry("text.borderColor", dmmt.model.DmProject.TRANSPARENT, "Default text border colour."))));

        sections.add(new Section(Tuning.IMPORT, List.of(
                new Entry("import.lastDirectory", "", "Folder last used when importing a map image."))));

        mergeTuning(sections);

        List<Entry> textures = new ArrayList<>();
        textures.add(new Entry("texture.tileCells", "4", "Effect textures: size of one texture tile in grid cells (smaller = finer pattern)."));
        for (Map.Entry<String, String> e : OverlayTextures.settingsDefaults().entrySet()) {
            if (e.getKey().equals("texture.tileCells")) {
                continue;
            }
            textures.add(new Entry(e.getKey(), e.getValue(), textureComment(e.getKey())));
        }
        sections.add(new Section("Effect textures. Per texture: color and opacity (start values when picked), softEdges "
                + "(fade out at the shape edge), and animated layers", textures));
        return sections;
    }

    /** Adds the {@link Tuning} values to the section of the same title, or as new sections in definition order. */
    private static void mergeTuning(List<Section> sections) {
        Map<String, List<Entry>> extra = new LinkedHashMap<>();
        for (Tuning.Setting<?> setting : Tuning.all()) {
            extra.computeIfAbsent(setting.section(), k -> new ArrayList<>())
                    .add(new Entry(setting.key(), setting.defaultText(), setting.comment()));
        }
        for (int i = 0; i < sections.size(); i++) {
            Section section = sections.get(i);
            List<Entry> more = extra.remove(section.title());
            if (more != null) {
                List<Entry> merged = new ArrayList<>(section.entries());
                merged.addAll(more);
                sections.set(i, new Section(section.title(), merged));
            }
        }
        extra.forEach((title, entries) -> sections.add(new Section(title, entries)));
    }

    /** Keys of every setting that has a default value (and is therefore always written to the file). */
    static Set<String> knownKeys() {
        Set<String> keys = new java.util.LinkedHashSet<>();
        for (Section section : sections()) {
            for (Entry entry : section.entries()) {
                if (entry.defaultValue() != null) {
                    keys.add(entry.key());
                }
            }
        }
        return keys;
    }

    private static String textureComment(String key) {
        if (key.equals("texture.featherCells")) {
            return "Width of the soft edge in grid cells.";
        }
        if (key.equals("texture.featherPasses")) {
            return "Smoothness steps of the soft edge (1-24, more = smoother but slower).";
        }
        if (key.endsWith(".color")) {
            return "Texture '" + key.split("\\.")[1] + "': default colour (#RRGGBB).";
        }
        if (key.endsWith(".opacity") && !key.contains(".layer")) {
            return "  default opacity (0.05-1).";
        }
        if (key.endsWith(".softEdges")) {
            return "  fade out at the shape edge (true/false).";
        }
        if (key.endsWith(".emitsLight")) {
            return "  new effects of this texture emit light in the effect colour (true/false).";
        }
        if (key.endsWith(".lightStrength")) {
            return "  light brightness (0-1).";
        }
        if (key.endsWith(".lightFlicker")) {
            return "  light flicker depth (0 = steady, 1 = deep dips).";
        }
        if (key.endsWith(".lightFlickerSpeed")) {
            return "  light flicker speed (1 = normal).";
        }
        if (key.endsWith(".lightRange")) {
            return "  how far the light reaches beyond the shape edge, in grid cells.";
        }
        if (key.endsWith(".layer1.speedX") || key.endsWith(".layer2.speedX")) {
            return "  layer: speedX/speedY in tiles per second, scale (bigger = larger pattern), opacity (0-1), "
                    + "pulseDepth (0-1) at pulseHz per second.";
        }
        return null;
    }

    private void importLegacyPreferences() {
        try {
            Preferences legacy = Preferences.userRoot().node("/dmmt");
            Map<String, String> map = new LinkedHashMap<>();
            map.put("lastImportDirectory", "import.lastDirectory");
            map.put("playerScreenIndex", "player.screenIndex");
            map.put("playerTileInches", "player.tileInches");
            map.put("sidebarVisible", "ui.sidebarVisible");
            map.put("controlsExpanded", "ui.controlsExpanded");
            map.put("fogCellsPerGrid", "fog.cellsPerGrid");
            map.put("lightTint", "lighting.tint");
            map.put("autoSaveEnabled", "autosave.enabled");
            map.put("autoSaveMinutes", "autosave.minutes");
            map.put("textFontSize", "text.fontSize");
            map.put("textColor", "text.textColor");
            map.put("textBackground", "text.backgroundColor");
            map.put("textBorder", "text.borderColor");
            for (String id : SECTION_IDS) {
                map.put("section." + id + ".expanded", "ui.section." + id + ".expanded");
            }
            for (int i = 0; i < 16; i++) {
                map.put("screenDiagonalInches." + i, "player.screenDiagonalInches." + i);
            }
            for (Map.Entry<String, String> e : map.entrySet()) {
                String value = legacy.get(e.getKey(), null);
                if (value != null) {
                    values.put(e.getValue(), value);
                }
            }
        } catch (RuntimeException e) {
            // no legacy preferences available
        }
    }
}
