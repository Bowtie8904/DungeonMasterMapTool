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

    private static final String[] SECTION_IDS = {"tools", "fog", "lighting", "effects", "text", "building", "player"};

    private final Path file;
    private final Map<String, String> values = new LinkedHashMap<>();
    private long lastKnownModified;
    private boolean externalChange;

    public AppSettings(Path file) {
        this.file = file;
        if (Files.isRegularFile(file)) {
            reload();
        } else {
            importLegacyPreferences();
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

    // ---- textures ----

    /** Pushes the texture section of this file into {@link OverlayTextures}. */
    public void applyTextureSettings() {
        OverlayTextures.applySettings(key -> {
            synchronized (this) {
                String value = values.get(key);
                return value == null || value.isBlank() ? null : value;
            }
        });
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
        StringBuilder out = new StringBuilder();
        out.append("# Dungeon Master Map Tool settings\n");
        out.append("# Edit this file with any text editor. Lines are 'key = value'; lines starting with # are comments.\n");
        out.append("# Texture settings are picked up when the application window regains focus; everything else\n");
        out.append("# is read at startup. Delete a line to fall back to the built-in default. Invalid values are ignored.\n");

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
        sections.add(new Section("Window and panels", List.of(
                new Entry("ui.sidebarVisible", "true", "Show the sidebar (true/false)."),
                new Entry("ui.controlsExpanded", "true", "Show the toolbar controls (true/false)."))));

        List<Entry> panels = new ArrayList<>();
        for (String id : SECTION_IDS) {
            panels.add(new Entry("ui.section." + id + ".expanded", "true", null));
        }
        sections.add(new Section("Collapsible sidebar sections (true = expanded)", panels));

        sections.add(new Section("Player screen", List.of(
                new Entry("player.screenIndex", "0", "Index of the monitor used for the player view."),
                new Entry("player.tileInches", "1.0", "Physical size of one grid tile on the player screen, in inches."),
                new Entry("player.screenDiagonalInches.0", null, "Screen diagonal per monitor index in inches, e.g. player.screenDiagonalInches.1 = 27."))));

        sections.add(new Section("Fog and lighting", List.of(
                new Entry("fog.cellsPerGrid", String.valueOf(FogService.DEFAULT_CELLS_PER_GRID), "Fog cells per grid cell (edge sharpness of fog of war)."),
                new Entry("lighting.tint", String.valueOf(dmmt.render.CanvasMapRenderer.DEFAULT_LIGHT_TINT), "Strength of the light colour tint over lit areas (0-1)."))));

        sections.add(new Section("Auto-save", List.of(
                new Entry("autosave.enabled", "true", "Save the project automatically (true/false)."),
                new Entry("autosave.minutes", "2", "Minutes between automatic saves."))));

        sections.add(new Section("Text defaults", List.of(
                new Entry("text.fontSize", String.valueOf(dmmt.model.DmProject.DEFAULT_TEXT_SIZE), "Default font size of new text labels."),
                new Entry("text.textColor", dmmt.model.DmProject.DEFAULT_TEXT_COLOR, "Default text colour."),
                new Entry("text.backgroundColor", dmmt.model.DmProject.TRANSPARENT, "Default text background colour."),
                new Entry("text.borderColor", dmmt.model.DmProject.TRANSPARENT, "Default text border colour."))));

        sections.add(new Section("Import", List.of(
                new Entry("import.lastDirectory", "", "Folder last used when importing a map image."))));

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
