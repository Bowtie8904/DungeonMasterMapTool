package dmmt.service;

import lombok.Getter;
import dmmt.render.OverlayTextures;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
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

    public static final String HIDDEN_SECTIONS_KEY = "ui.sections.hidden";
    public static final String HIDDEN_CONTROLS_KEY = "ui.controls.hidden";

    /** One tab of the DM controls panel; its id is used in {@value #HIDDEN_SECTIONS_KEY} and ui.section.<id>.expanded. */
    public record SidebarSection(String id, String title, String description) {
    }

    public static final List<SidebarSection> SIDEBAR_SECTIONS = List.of(
            new SidebarSection("tools", "Tools", "Select tool, fog brushes, rectangles and room fill."),
            new SidebarSection("tokens", "Tokens", "Token library tab and creation controls in the left sidebar."),
            new SidebarSection("session", "Session", "LAN remote-control tab in the left sidebar (always shown while a session is active)."),
            new SidebarSection("fog", "Fog of war", "Fog sharpness, softness and fade animation."),
            new SidebarSection("lighting", "Lighting", "Light tools, time of day, ambient brightness and light tint."),
            new SidebarSection("weather", "Weather", "Rain, snow, mist, dust, embers and thunderstorms."),
            new SidebarSection("effects", "Effects", "Area effect shapes, pen, line, textures and colours."),
            new SidebarSection("text", "Text", "Text boxes, font size and colours."),
            new SidebarSection("building", "Map building", "Walls, doors and windows."),
            new SidebarSection("player", "Player view", "Player screen, calibration and player zoom."),
            new SidebarSection("performance", "Performance", "Frame rate settings."));

    public record SidebarControl(String id, String sectionId, String label) {
    }

    public static final List<SidebarControl> SIDEBAR_CONTROLS = sidebarControls();

    private static List<SidebarControl> sidebarControls() {
        List<SidebarControl> controls = new ArrayList<>();
        addControls(controls, "session", "browser|Session tab");
        addControls(controls, "tokens", "browser|Token browser tab", "create|Create token", "import|Import token image",
                "paste|Token from clipboard", "folder|New token folder", "refresh|Reload token library");
        addControls(controls, "tools", "select|Select", "ping|Ping", "laser|Laser pointer", "undo|Undo", "redo|Redo");
        addControls(controls, "fog", "enabled|Fog on/off", "revealBrush|Reveal brush", "hideBrush|Hide brush",
                "revealRect|Reveal rectangle", "hideRect|Hide rectangle", "revealAll|Reveal all", "hideAll|Hide all",
                "revealRoom|Reveal room", "brushSize|Brush size slider", "sharpness|Fog sharpness",
                "fade|Fade animation", "softness|Fog softness");
        addControls(controls, "lighting", "torch|Default torch light", "candle|Default candle light",
                "campfire|Default campfire light", "magic|Default magic light", "remove|Remove selected lights",
                "flicker|Light flicker", "revealPersistent|Keep fog revealed", "revealWhileLit|Reveal only while lit",
                "revealNone|Don't reveal fog", "day|Day", "dawn|Dawn", "dusk|Dusk", "night|Night",
                "on|Turn selected lights on", "off|Turn selected lights off", "ambient|Ambient brightness",
                "tint|Light colour tint", "brightCore|Bright core strength", "hint|Right-click light hint");
        addControls(controls, "weather", "type|Weather type", "intensity|Weather intensity",
                "lightningInterval|Lightning interval", "indoors|Weather indoors");
        addControls(controls, "effects", "circle|Circle", "rectangle|Box", "brush|Draw", "pen|Pen", "line|Line",
                "delete|Delete selected effect", "clear|Remove all effects", "color|Effect colour", "opacity|Effect opacity",
                "players|Players see effect", "texture|Effect texture", "border|Effect border",
                "light|Effect emits light", "animations|Animate effects and weather", "brushSize|Brush size slider");
        addControls(controls, "text", "add|Text tool", "layer|Show/hide text layer", "autoSize|Auto-size text box",
                "players|Players see text", "delete|Delete selected text", "size|Font size", "color|Text colour",
                "rotateLeft|Rotate text left", "rotateRight|Rotate text right", "background|Background colour",
                "noBackground|No background", "border|Border colour", "noBorder|No border");
        addControls(controls, "building", "drawWall|Draw wall", "drawDoor|Draw door", "drawWindow|Draw window",
                "roomLabel|Room label", "eraseWall|Erase wall", "wallLayer|Show/hide wall layer",
                "lock|Lock image layer", "snap|Snap image layers", "addImage|Add image layer");
        addControls(controls, "player", "window|Player window", "freeze|Freeze player view", "scaleTest|1-inch test square",
                "handout|Handout", "grid|Show player grid", "gridOpacity|Grid opacity", "screen|Player screen",
                "diagonal|Screen diagonal (diameter)", "tileSize|Tile size", "zoom|Player zoom");
        addControls(controls, "performance", "target|Target FPS", "animation|Animation FPS", "idle|Idle FPS");
        return List.copyOf(controls);
    }

    private static void addControls(List<SidebarControl> controls, String section, String... entries) {
        for (String entry : entries) {
            String[] parts = entry.split("\\|", 2);
            controls.add(new SidebarControl(section + "." + parts[0], section, parts[1]));
        }
    }

    /**
     * Kind of editor and limits of one setting, used by the settings window. {@code group} is the collapsible block
     * inside the category, nested levels separated by "/" (null = not grouped); {@code label} is the short name shown
     * inside that group; {@code keywords} are extra search words.
     */
    public record SettingInfo(String key, String category, String group, String label, String description, String keywords, Tuning.Kind kind, String defaultValue,
                              double min, double max, List<String> options, boolean restart,
                              java.util.function.Predicate<String> validator) {
    }

    /** Keys of older versions that were replaced by individual settings; dropped from the file at startup. */
    private static final Set<String> OBSOLETE_KEYS = Set.of("lightMenu.rangeTiles", "lightMenu.colors", "lightMenu.flicker",
            "lightMenu.brightness");

    private static final String[] SECTION_IDS = {"tools", "fog", "lighting", "effects", "text", "building", "player", "performance"};
    private static final Set<String> CLIENT_LOCAL_KEYS = Set.of("import.lastDirectory", "audio.lastDirectory");

    private static final class SharedMetadata {
        private static final Map<String, Entry> ENTRIES = entries();
        private static final Map<String, SettingInfo> EDITABLE = editableSettings().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(SettingInfo::key, info -> info));
        private static final Map<String, String> DEFAULTS = defaults();

        private static Map<String, Entry> entries() {
            Map<String, Entry> entries = new LinkedHashMap<>();
            sections().forEach(section -> section.entries().forEach(entry -> entries.put(entry.key(), entry)));
            return Map.copyOf(entries);
        }

        private static Map<String, String> defaults() {
            Map<String, String> defaults = new LinkedHashMap<>();
            ENTRIES.forEach((key, entry) -> {
                if (entry.defaultValue() != null) {
                    defaults.put(key, entry.defaultValue());
                }
            });
            CLIENT_LOCAL_KEYS.forEach(defaults::remove);
            return Map.copyOf(defaults);
        }
    }

    /** Raw host values; absent keys use built-in defaults, never the client's standalone overrides. */
    public record HostSnapshot(long revision, Map<String, String> values) {
        public HostSnapshot {
            if (revision < 0) {
                throw new IllegalArgumentException("Settings revision must not be negative.");
            }
            values = Map.copyOf(values);
        }
    }

    /**
     * A request, not an applied edit. Null values restore defaults. Compare the target's raw expectedValue
     * for conflicts; baseRevision alone must not reject an edit after unrelated host settings changed.
     */
    public record RemoteEdit(UUID commandId, long baseRevision, String key, String expectedValue, String value) {
        public RemoteEdit {
            Objects.requireNonNull(commandId, "commandId");
            Objects.requireNonNull(key, "key");
            if (baseRevision < 0) {
                throw new IllegalArgumentException("Settings revision must not be negative.");
            }
        }
    }

    /** Host-only revision metadata; removed keys keep their last-change revision for conflict detection. */
    public record SharedState(HostSnapshot snapshot, Map<String, Long> changedAt) {
        public SharedState {
            Objects.requireNonNull(snapshot, "snapshot");
            changedAt = Map.copyOf(changedAt);
        }
    }

    /**
     * Temporary effective settings for a remote client. Invoke lifecycle/update methods on the application
     * thread, just like ordinary settings edits. The sender must only enqueue work, never wait for network IO;
     * it must throw if it cannot enqueue. Only accepted host snapshots change the effective settings.
     */
    public final class HostContext implements AutoCloseable {
        private HostSnapshot snapshot;
        private final Consumer<RemoteEdit> sender;
        private final Map<String, String> clientValues = new LinkedHashMap<>();
        private boolean connected = true;
        private boolean closed;

        private HostContext(HostSnapshot snapshot, Consumer<RemoteEdit> sender) {
            this.snapshot = snapshot;
            this.sender = sender;
            CLIENT_LOCAL_KEYS.forEach(key -> {
                if (values.containsKey(key)) {
                    clientValues.put(key, values.get(key));
                }
            });
        }

        /** False for duplicate/older snapshots. Equal revisions with different values are a protocol error. */
        public boolean accept(HostSnapshot next) {
            Objects.requireNonNull(next, "next");
            synchronized (AppSettings.this) {
                requireActive();
                if (!connected) {
                    throw new IllegalStateException("Resume the settings connection before applying host updates.");
                }
                if (next.revision() < snapshot.revision()) {
                    return false;
                }
                if (next.revision() == snapshot.revision()) {
                    if (!next.equals(snapshot)) {
                        throw new IllegalArgumentException("Different settings for the same host revision.");
                    }
                    return false;
                }
                snapshot = next;
                applyRuntimeSettings();
                externalChange = true;
                return true;
            }
        }

        /** Pausing retains the last accepted values but prevents remote edits until resynchronization. */
        public void pause() {
            synchronized (AppSettings.this) {
                requireActive();
                connected = false;
            }
        }

        /** Resynchronization may refresh the same revision, but must not roll accepted host state backwards. */
        public void resume(HostSnapshot baseline) {
            Objects.requireNonNull(baseline, "baseline");
            synchronized (AppSettings.this) {
                requireActive();
                if (connected) {
                    throw new IllegalStateException("The settings connection is already active.");
                }
                if (baseline.revision() < snapshot.revision()
                        || (baseline.revision() == snapshot.revision() && !baseline.equals(snapshot))) {
                    throw new IllegalArgumentException("The settings baseline does not continue this host session.");
                }
                snapshot = baseline;
                applyRuntimeSettings();
                connected = true;
                externalChange = true;
            }
        }

        @Override
        public void close() {
            synchronized (AppSettings.this) {
                if (closed) {
                    return;
                }
                requireActive();
                closed = true;
                hostContext = null;
                mergeExternalEdits();
                applyRuntimeSettings();
                externalChange = true;
            }
        }

        private void requireActive() {
            if (closed || hostContext != this) {
                throw new IllegalStateException("This host settings context is no longer active.");
            }
        }

        private void request(String key, String value) {
            if (!connected) {
                throw new IllegalStateException("Host settings are disconnected; editing is paused.");
            }
            sender.accept(new RemoteEdit(UUID.randomUUID(), snapshot.revision(), key, snapshot.values().get(key), value));
        }
    }

    @Getter
    private final Path file;
    private final Map<String, String> values = new LinkedHashMap<>();
    private long lastKnownModified;
    private boolean externalChange;
    private HostContext hostContext;
    private long sharedRevision;
    private SharedState cachedSharedState;
    private final Map<String, Long> sharedChangedAt = new LinkedHashMap<>();
    private long settingsGeneration;
    private final Map<String, Long> localChangedAt = new LinkedHashMap<>();
    private PreparedSharedEdit pendingSharedEdit;

    /** Owner-thread transaction state. File IO uses detached attempts, never this object's monitor. */
    static final class PreparedSharedEdit {
        final RemoteEdit edit;
        final long generation;
        final long modified;
        boolean cancelled;
        boolean conflict;

        PreparedSharedEdit(RemoteEdit edit, long generation, long modified) {
            this.edit = edit;
            this.generation = generation;
            this.modified = modified;
        }
    }

    record SharedEditAttempt(long generation, Map<String, String> values) {
        SharedEditAttempt {
            values = Map.copyOf(values);
        }
    }

    public AppSettings(Path file) {
        this.file = file;
        if (Files.isRegularFile(file)) {
            reload();
            boolean removed = values.keySet().removeAll(OBSOLETE_KEYS);
            if (removed || !values.keySet().containsAll(knownKeys())) {
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

    /** Resolves a configured storage folder relative to the settings file, keeping app libraries together. */
    public static Path resolveConfiguredFolder(Path settingsFile, String configured, String defaultFolder) {
        String value = configured == null || configured.isBlank() ? defaultFolder : configured.trim();
        Path folder = Path.of(value);
        if (folder.isAbsolute()) {
            return folder.normalize();
        }
        Path base = settingsFile.toAbsolutePath().normalize().getParent();
        return (base == null ? Path.of(".").toAbsolutePath().normalize() : base)
                .resolve(folder).toAbsolutePath().normalize();
    }

    /**
     * Enters host-backed mode without changing the standalone settings or their file. A session coordinator
     * must finish local work before entry and marshal accepted snapshots back to the application thread.
     */
    public synchronized HostContext useHostSettings(HostSnapshot snapshot, Consumer<RemoteEdit> sender) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(sender, "sender");
        if (hostContext != null) {
            throw new IllegalStateException("A host settings context is already active.");
        }
        if (pendingSharedEdit != null) {
            throw new IllegalStateException("A host settings save is still pending.");
        }
        mergeExternalEdits();
        hostContext = new HostContext(snapshot, sender);
        applyRuntimeSettings();
        externalChange = true;
        return hostContext;
    }

    public synchronized boolean usesHostSettings() {
        return hostContext != null;
    }

    /** Captures the dialog's host revision, so a later confirmation cannot overwrite a newer setting. */
    public synchronized Consumer<String> prepareHostEdit(String key) {
        if (hostContext == null) throw new IllegalStateException("No host settings context is active.");
        var context = hostContext;
        var snapshot = context.snapshot;
        return value -> {
            synchronized (AppSettings.this) {
                context.requireActive();
                if (!context.connected) throw new IllegalStateException("Host settings are disconnected; editing is paused.");
                validateSharedEdit(key, value);
                context.sender.accept(new RemoteEdit(UUID.randomUUID(), snapshot.revision(), key,
                        snapshot.values().get(key), value));
            }
        };
    }

    /** Host values include default text for absent keys, avoiding false changes when the INI is reloaded. */
    public synchronized Map<String, String> sharedValues() {
        mergeExternalEdits();
        if (hostContext == null) {
            return sharedValuesOf(values);
        }
        Map<String, String> shared = new LinkedHashMap<>(hostContext.snapshot.values());
        CLIENT_LOCAL_KEYS.forEach(shared::remove);
        return Map.copyOf(shared);
    }

    private static Map<String, String> sharedValuesOf(Map<String, String> source) {
        Map<String, String> shared = new LinkedHashMap<>(SharedMetadata.DEFAULTS);
        shared.putAll(source);
        CLIENT_LOCAL_KEYS.forEach(shared::remove);
        return Map.copyOf(shared);
    }

    public synchronized SharedState sharedState() {
        requireStandaloneSettings();
        Map<String, String> shared = sharedValues();
        return new SharedState(new HostSnapshot(sharedRevision, shared), sharedChangedAt);
    }

    /** Captures already-applied host state without stat/read calls (safe for frequent owner-thread polling). */
    public synchronized SharedState sharedStateInMemory() {
        requireStandaloneSettings();
        if (cachedSharedState == null || cachedSharedState.snapshot().revision() != sharedRevision) {
            cachedSharedState = new SharedState(new HostSnapshot(sharedRevision, sharedValuesOf(values)), sharedChangedAt);
        }
        return cachedSharedState;
    }

    synchronized PreparedSharedEdit prepareSharedEdit(RemoteEdit edit) {
        requireStandaloneSettings();
        if (pendingSharedEdit != null) {
            throw new IllegalStateException("A host settings save is already pending.");
        }
        validateSharedEdit(edit.key(), edit.value());
        checkSharedEdit(edit);
        pendingSharedEdit = new PreparedSharedEdit(edit, settingsGeneration, lastKnownModified);
        return pendingSharedEdit;
    }

    record SharedExternalState(Map<String, String> values, long modified) {
    }

    SharedExternalState readSharedExternalState(PreparedSharedEdit prepared) throws IOException {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        long modified = Files.getLastModifiedTime(file).toMillis();
        if (modified == prepared.modified) {
            return null;
        }
        Map<String, String> loaded = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            int eq = trimmed.indexOf('=');
            if (!trimmed.startsWith("#") && !trimmed.startsWith(";") && eq > 0) {
                loaded.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
            }
        }
        return new SharedExternalState(Map.copyOf(loaded), modified);
    }

    synchronized void mergeSharedExternalState(PreparedSharedEdit prepared, SharedExternalState external) {
        requirePending(prepared);
        if (external == null) {
            return;
        }
        Map<String, String> before = new LinkedHashMap<>(values);
        Map<String, String> merged = new LinkedHashMap<>(external.values());
        localChangedAt.forEach((key, generation) -> {
            if (generation > prepared.generation) {
                store(merged, key, values.get(key));
            }
        });
        values.clear();
        values.putAll(merged);
        settingsGeneration++;
        lastKnownModified = external.modified();
        trackSharedChanges(before);
        applyRuntimeSettings();
        externalChange = true;
    }

    private void checkSharedEdit(RemoteEdit edit) {
        if (edit.baseRevision() > sharedRevision) {
            throw new IllegalArgumentException("Settings revision is ahead of the host.");
        }
        if (sharedChangedAt.getOrDefault(edit.key(), 0L) > edit.baseRevision()
                || !Objects.equals(sharedValuesOf(values).get(edit.key()), edit.expectedValue())) {
            throw new ConcurrentModificationException("This setting changed on the host: " + edit.key());
        }
    }

    synchronized SharedEditAttempt sharedEditAttempt(PreparedSharedEdit prepared) {
        requirePending(prepared);
        try {
            checkSharedEdit(prepared.edit);
        } catch (ConcurrentModificationException conflict) {
            prepared.conflict = true;
        }
        Map<String, String> candidate = new LinkedHashMap<>(values);
        if (!prepared.cancelled && !prepared.conflict) {
            store(candidate, prepared.edit.key(),
                    prepared.edit.value() == null ? null : prepared.edit.value().trim());
        }
        return new SharedEditAttempt(settingsGeneration, candidate);
    }

    /** Worker-only: no synchronized settings access, runtime changes, or owner-thread file locks. */
    long persistSharedEdit(SharedEditAttempt attempt) throws IOException {
        return writeChecked(attempt.values());
    }

    /** Returns false if a local edit raced IO; the worker must persist a newly prepared attempt. */
    synchronized boolean commitSharedEdit(PreparedSharedEdit prepared, SharedEditAttempt attempt, long modified) {
        requirePending(prepared);
        // Even a superseded successful write must not be mistaken for an external editor update.
        lastKnownModified = modified;
        if (settingsGeneration != attempt.generation()
                || (prepared.cancelled && !attempt.values().equals(values))) {
            return false;
        }
        Map<String, String> before = new LinkedHashMap<>(values);
        values.clear();
        values.putAll(attempt.values());
        pendingSharedEdit = null;
        settingsGeneration++;
        trackSharedChanges(before);
        applyRuntimeSettings();
        externalChange = true;
        return true;
    }

    synchronized void cancelSharedEdit(PreparedSharedEdit prepared) {
        requirePending(prepared);
        prepared.cancelled = true;
    }

    synchronized void failSharedEdit(PreparedSharedEdit prepared) {
        requirePending(prepared);
        pendingSharedEdit = null;
    }

    private void requirePending(PreparedSharedEdit prepared) {
        if (pendingSharedEdit != prepared) {
            throw new IllegalStateException("The settings save is no longer active.");
        }
    }

    /**
     * Validates and conflict-checks a host request, reporting persistence failure to the caller rather
     * than acknowledging a memory-only change. Call on the same serialized thread as other settings edits.
     */
    public synchronized void applySharedEdit(RemoteEdit edit) throws IOException {
        Objects.requireNonNull(edit, "edit");
        requireStandaloneSettings();
        if (pendingSharedEdit != null) {
            throw new IllegalStateException("A host settings save is already pending.");
        }
        String key = edit.key();
        String value = edit.value();
        validateSharedEdit(key, value);
        mergeExternalEdits();
        if (edit.baseRevision() > sharedRevision) {
            throw new IllegalArgumentException("Settings revision is ahead of the host.");
        }
        if (sharedChangedAt.getOrDefault(key, 0L) > edit.baseRevision()
                || !Objects.equals(sharedValuesOf(values).get(key), edit.expectedValue())) {
            throw new ConcurrentModificationException("This setting changed on the host: " + key);
        }
        Map<String, String> before = new LinkedHashMap<>(values);
        store(values, key, value == null ? null : value.trim());
        try {
            saveChecked();
        } catch (IOException | RuntimeException failure) {
            values.clear();
            values.putAll(before);
            throw failure;
        }
        trackSharedChanges(before);
        applyRuntimeSettings();
        externalChange = true;
    }

    public static void validateSharedEdit(String key, String value) {
        Objects.requireNonNull(key, "key");
        if (CLIENT_LOCAL_KEYS.contains(key)) {
            throw new IllegalArgumentException("File-picker directories belong to the client: " + key);
        }
        Entry entry = SharedMetadata.ENTRIES.get(key);
        boolean screenDiagonal = key.matches("player\\.screenDiagonalInches\\.(0|[1-9][0-9]{0,3})");
        if (entry == null && !screenDiagonal) {
            throw new IllegalArgumentException("Unknown shared setting: " + key);
        }
        if (value == null) {
            return;
        }
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Setting values must be a single line: " + key);
        }
        String raw = value.trim();
        boolean valid;
        SettingInfo info = SharedMetadata.EDITABLE.get(key);
        if (info != null) {
            valid = info.validator().test(raw)
                    && (info.kind() != Tuning.Kind.INTEGER || isNumber(raw, true))
                    && (info.kind() != Tuning.Kind.DECIMAL || isNumber(raw, false));
            if (valid && info.kind() == Tuning.Kind.LIST
                    && java.util.Arrays.stream(info.defaultValue().split(","))
                    .allMatch(number -> isNumber(number, false))) {
                valid = java.util.Arrays.stream(raw.split(",")).filter(number -> !number.isBlank())
                        .allMatch(number -> isNumber(number, false));
            }
        } else if (key.equals(HIDDEN_CONTROLS_KEY)) {
            valid = raw.isEmpty() || java.util.Arrays.stream(raw.split(",", -1))
                    .allMatch(id -> SIDEBAR_CONTROLS.stream().anyMatch(control -> control.id().equals(id.trim())));
        } else if (key.equals(HIDDEN_SECTIONS_KEY)) {
            valid = raw.isEmpty() || java.util.Arrays.stream(raw.split(",", -1))
                    .allMatch(id -> SIDEBAR_SECTIONS.stream().anyMatch(section -> section.id().equals(id.trim())));
        } else if (screenDiagonal) {
            valid = raw.indexOf(',') < 0 && isNumber(raw, false) && Double.parseDouble(raw) > 0;
        } else if (entry.defaultValue() == null || entry.defaultValue().isEmpty()) {
            valid = true;
        } else {
            Tuning.Kind kind = entry.defaultValue().matches("-?[0-9]+") ? Tuning.Kind.INTEGER
                    : inferKind(entry.defaultValue());
            valid = validForKind(kind, raw) && (kind != Tuning.Kind.DECIMAL || raw.indexOf(',') < 0);
        }
        if (!valid) {
            throw new IllegalArgumentException("Invalid value for setting: " + key);
        }
    }

    private void requireStandaloneSettings() {
        if (hostContext != null) {
            throw new IllegalStateException("A remote client cannot act as a settings host.");
        }
    }

    private void trackSharedChanges(Map<String, String> before) {
        Map<String, String> previous = sharedValuesOf(before);
        Map<String, String> current = sharedValuesOf(values);
        Set<String> changed = new HashSet<>(previous.keySet());
        changed.addAll(current.keySet());
        changed.removeIf(key -> Objects.equals(previous.get(key), current.get(key)));
        if (!changed.isEmpty()) {
            sharedRevision++;
            changed.forEach(key -> sharedChangedAt.put(key, sharedRevision));
        }
    }

    // ---- typed access (mirrors java.util.prefs.Preferences) ----

    public synchronized String get(String key, String defaultValue) {
        String value = effectiveValues(key).get(key);
        return value == null || (value.isBlank()
                && Tuning.all().stream().noneMatch(s -> s.key().equals(key) && s.allowsEmpty())) ? defaultValue : value;
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
        Objects.requireNonNull(key, "key");
        String normalized = value == null ? null : value.replace('\r', ' ').replace('\n', ' ');
        if (hostContext != null) {
            if (CLIENT_LOCAL_KEYS.contains(key)) {
                store(hostContext.clientValues, key, normalized);
            } else {
                hostContext.request(key, normalized);
            }
            return;
        }
        mergeExternalEdits();
        Map<String, String> before = new LinkedHashMap<>(values);
        store(values, key, normalized);
        settingsGeneration++;
        localChangedAt.put(key, settingsGeneration);
        save();
        trackSharedChanges(before);
    }

    private Map<String, String> effectiveValues(String key) {
        return hostContext == null ? values
                : CLIENT_LOCAL_KEYS.contains(key) ? hostContext.clientValues : hostContext.snapshot.values();
    }

    private static void store(Map<String, String> target, String key, String value) {
        if (value == null) {
            target.remove(key);
        } else {
            target.put(key, value);
        }
    }

    /** Ids of the DM controls tabs the user chose to hide. */
    public synchronized Set<String> hiddenSections() {
        Set<String> hidden = new java.util.LinkedHashSet<>();
        for (String id : get(HIDDEN_SECTIONS_KEY, "").split(",")) {
            String trimmed = id.trim().toLowerCase(java.util.Locale.ROOT);
            if (SIDEBAR_SECTIONS.stream().anyMatch(s -> s.id().equals(trimmed))) {
                hidden.add(trimmed);
            }
        }
        return hidden;
    }

    public void setHiddenSections(Set<String> ids) {
        List<String> ordered = SIDEBAR_SECTIONS.stream().map(SidebarSection::id).filter(ids::contains).toList();
        put(HIDDEN_SECTIONS_KEY, String.join(",", ordered));
    }

    public synchronized Set<String> hiddenControls() {
        Set<String> hidden = new java.util.LinkedHashSet<>();
        for (String id : get(HIDDEN_CONTROLS_KEY, "").split(",")) {
            String trimmed = id.trim();
            SIDEBAR_CONTROLS.stream().filter(c -> c.id().equalsIgnoreCase(trimmed))
                    .findFirst().ifPresent(c -> hidden.add(c.id()));
        }
        return hidden;
    }

    public void setHiddenControls(Set<String> ids) {
        put(HIDDEN_CONTROLS_KEY, String.join(",", SIDEBAR_CONTROLS.stream()
                .map(SidebarControl::id).filter(ids::contains).toList()));
    }

    /** Stores/applies a local edit, or requests a host edit without applying an unacknowledged value. */
    public synchronized void applyEdit(String key, String value) {
        put(key, value);
        if (hostContext == null) {
            applyRuntimeSettings();
        }
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
        return get(key, null);
    }

    /** True once after an external file edit or host context update; effective runtime values are already applied. */
    public synchronized boolean pollExternalChange() {
        mergeExternalEdits();
        return consumeAppliedChange();
    }

    /** Owner-pulse notification for changes already applied in memory; never probes the settings file. */
    public synchronized boolean consumeAppliedChange() {
        boolean changed = externalChange;
        externalChange = false;
        return changed;
    }

    // ---- file handling ----

    private void mergeExternalEdits() {
        if (hostContext != null || pendingSharedEdit != null) {
            return;
        }
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
        Map<String, String> before = new LinkedHashMap<>(values);
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
            settingsGeneration++;
        } catch (IOException | RuntimeException e) {
            System.err.println("Could not read settings file " + file + ": " + e.getMessage());
        }
        trackSharedChanges(before);
        applyRuntimeSettings();
    }

    private synchronized void save() {
        if (hostContext != null) {
            throw new IllegalStateException("Cannot write standalone settings while using a host context.");
        }
        if (pendingSharedEdit != null) {
            return;
        }
        try {
            saveChecked();
        } catch (IOException | RuntimeException e) {
            System.err.println("Could not write settings file " + file + ": " + e.getMessage());
        }
    }

    private void saveChecked() throws IOException {
        lastKnownModified = writeChecked(values);
    }

    private long writeChecked(Map<String, String> candidate) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, render(candidate), StandardCharsets.UTF_8);
        long modified = Files.getLastModifiedTime(temp).toMillis();
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        return modified;
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
                new Entry("ui.performanceMode", "false", "Performance mode for slow machines (true/false); see the Performance mode section."),
                new Entry("ui.effectAnimations", "true", "Animate effect textures and weather on all maps (true/false)."),
                new Entry("ui.lightFlicker", "true", "Light flicker on / off for all maps (true/false); lights flicker according to their own flicker setting while on."),
                new Entry(HIDDEN_SECTIONS_KEY, "", "DM controls tabs that are hidden from the overlay, comma separated ids: "
                        + String.join(", ", SIDEBAR_SECTIONS.stream().map(SidebarSection::id).toList()) + ". Empty = all tabs shown."),
                new Entry(HIDDEN_CONTROLS_KEY, "", "Individual DM controls hidden from the overlay, comma separated section.control ids. "
                        + "Empty = all controls shown. Configure in Settings > DM controls tabs; values and shortcuts are unchanged."),
                new Entry("ui.recentMaps.max", "5", "Number of maps kept in the recent maps list below the map library (1 to 30)."),
                new Entry("ui.recentMaps", null, "Recently opened maps, most recent first, separated by '|' (managed by the app)."))));

        List<Entry> panels = new ArrayList<>();
        for (String id : SECTION_IDS) {
            panels.add(new Entry("ui.section." + id + ".expanded", "true", null));
        }
        sections.add(new Section("Collapsible sidebar sections (true = expanded)", panels));

        sections.add(new Section(Tuning.PLAYER, List.of(
                new Entry("player.screenIndex", "0", "Index of the monitor used for the player view."),
                new Entry("player.tileInches", "1.0", "Physical size of one grid tile on the player screen, in inches."),
                new Entry("player.showGrid", "false", "Show grid lines over the player map (true/false); does not affect the DM view."),
                new Entry("player.screenDiagonalInches.0", null, "Screen diagonal per monitor index in inches, e.g. player.screenDiagonalInches.1 = 27."))));

        sections.add(new Section(Tuning.FOG, List.of(
                new Entry("fog.cellsPerGrid", String.valueOf(FogService.DEFAULT_CELLS_PER_GRID), "Fog cells per grid cell (edge sharpness of fog of war), fog.cellsPerGrid.min to fog.cellsPerGrid.max."),
                new Entry("fog.softness", String.valueOf(dmmt.render.CanvasMapRenderer.DEFAULT_FOG_SOFTNESS), "Width of the soft fog edge in grid tiles (0 = hard edge, max fog.softness.max)."),
                new Entry("fog.fadeAnimation", "true", "Fade fog in and out when it is revealed or hidden (true/false)."))));

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
                new Entry("text.backgroundColor", dmmt.model.DmProject.DEFAULT_TEXT_BACKGROUND, "Default text background colour."),
                new Entry("text.borderColor", dmmt.model.DmProject.TRANSPARENT, "Default text border colour."),
                new Entry("text.rotation", "0", "Global rotation of all text boxes on the player view in degrees: 0, 90, 180 or 270 (independent of the map rotation)."))));

        sections.add(new Section(Tuning.IMPORT, List.of(
                new Entry("import.lastDirectory", "", "Folder last used when importing a map image."))));

        sections.add(new Section(Tuning.AUDIO, List.of(
                new Entry("audio.masterVolume", "0.8", "Master volume of all audio (0 to 1); changed with the slider in the audio overlay."),
                new Entry("audio.musicVolume", "0.7", "Volume of the category music (0 to 1); changed with the slider in the audio overlay."),
                new Entry("audio.effectsVolume", "0.6", "Volume of the sound effects (0 to 1); changed with the slider in the audio overlay."),
                new Entry("audio.lastCategory", null, "Id of the music category last played in the audio overlay (managed by the app)."),
                new Entry("audio.lastDirectory", "", "Folder last used when importing audio files."))));

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

    /** Every setting that can be changed in the settings window, i.e. all settings that have no control in the DM controls. */
    public static List<SettingInfo> editableSettings() {
        List<SettingInfo> list = new ArrayList<>();
        for (Tuning.Setting<?> s : Tuning.all()) {
            Placement place = placement(s.key());
            list.add(new SettingInfo(s.key(), s.section(), place.group(), label(s.key(), place.skip()), s.comment(), keywords(s.key()), s.kind(), s.defaultText(), s.min(), s.max(),
                    s.options(), s.requiresRestart(), s::isValid));
        }
        list.add(new SettingInfo("ui.recentMaps.max", Tuning.WINDOW, null, "Recent maps max",
                "Number of maps kept in the recent maps list below the map library (1 to 30). Restart required.", keywords("ui.recentMaps.max"), Tuning.Kind.INTEGER, "5", 1, 30,
                List.of(), true, text -> isNumber(text, true)));
        for (Entry entry : textureEntries()) {
            Tuning.Kind kind = inferKind(entry.defaultValue());
            String description = entry.comment() == null ? "Effect texture setting." : entry.comment().trim();
            String[] parts = entry.key().split("\\.");
            if (parts.length >= 3 && !description.startsWith("Texture")) {
                description = "Texture '" + parts[1] + "': " + description;
            }
            Placement place = placement(entry.key());
            list.add(new SettingInfo(entry.key(), TEXTURES_CATEGORY, place.group(), label(entry.key(), place.skip()), description,
                    keywords(entry.key()), kind, entry.defaultValue(),
                    Double.NaN, Double.NaN, List.of(), false, text -> validForKind(kind, text)));
        }
        list.sort(java.util.Comparator.comparingInt(i -> categoryRank(i.category())));
        return list;
    }

    private static final String TEXTURES_CATEGORY = "Effect textures";

    /** Order of the categories in the settings window: related categories are next to each other. */
    private static final List<String> CATEGORY_ORDER = List.of(Tuning.WINDOW, Tuning.UI, Tuning.DM_VIEW, Tuning.INPUT,
            Tuning.PLAYER, Tuning.EDITING, TEXTURES_CATEGORY, Tuning.TEXT, Tuning.FOG, Tuning.LIGHTS, Tuning.TIME_OF_DAY,
            Tuning.WEATHER, Tuning.PING, Tuning.FRAME_RATES, Tuning.PERFORMANCE_MODE, Tuning.AUTOSAVE, Tuning.IMPORT,
            Tuning.AUDIO, Tuning.STORAGE, Tuning.LOCAL_API);

    private static int categoryRank(String category) {
        int index = CATEGORY_ORDER.indexOf(category);
        return index < 0 ? CATEGORY_ORDER.size() : index;
    }

    /** Where a setting is shown inside its category; {@code skip} leading key segments are implied by the group. */
    private record Placement(String group, int skip) {
    }

    private static final Map<String, String> LIGHT_PRESET_NAMES = Map.of("torch", "Torch (Add light tool)",
            "candle", "Candle", "campfire", "Campfire", "magic", "Magic light");

    private static Placement placement(String key) {
        String[] parts = key.split("\\.");
        String kind = parts.length > 1 ? parts[1] : "";
        switch (parts[0]) {
            case "api":
                return new Placement(null, 1);
            case "window":
                return new Placement("Main window", 1);
            case "player":
                if (key.startsWith("player.viewportEdgeScroll.")) {
                    return new Placement("Viewport edge scrolling", 2);
                }
                return key.startsWith("player.zoom.") ? new Placement("Player zoom", 2) : new Placement("Calibration limits", 1);
            case "dm":
                return new Placement("DM view zoom", 1);
            case "input":
                if (key.matches("input\\.(lightPickRadiusPx|layerHandleRadiusPx|shapeHandleRadiusPx|textHandleRadiusPx|wallPickRadiusPx|shapePickRadiusPx)")) {
                    return new Placement("Pick and grab distances", 1);
                }
                return key.matches("input\\.(marqueeMinDragPx|rightClickMaxMovePx)")
                        ? new Placement("Drag and click", 1) : new Placement("Input timing", 1);
            case "ping":
                return new Placement("Ping", 1);
            case "laser":
                return new Placement("Laser pointer", 1);
            case "brush":
                return new Placement("Brush", 1);
            case "effects":
                return new Placement("Effect shapes", 1);
            case "roomLabel":
                return new Placement("Room label defaults", 1);
            case "text":
                if (key.matches("text\\.(minFontSize|maxFontSize)")) {
                    return new Placement("Font size limits", 1);
                }
                return key.matches("text\\.(autoMaxWidthCells|minBoxSize)") ? new Placement("Text box size", 1) : new Placement(null, 0);
            case "fog":
                if (key.matches("fog\\.(revealSeconds|hideSeconds|lightFadeSeconds|fadeMaxStepSeconds|fadeEasing)")) {
                    return new Placement("Fog fade", 1);
                }
                return key.equals("fog.dmOpacity") ? new Placement(null, 0) : new Placement("Slider limits", 1);
            case "lighting":
                return new Placement("Light quality", 1);
            case "lightPreset":
                return new Placement("Light tool presets/" + LIGHT_PRESET_NAMES.getOrDefault(kind, capitalize(kind)), 2);
            case "lightMenu":
                if (key.startsWith("lightMenu.range")) {
                    return new Placement("Right-click menu/Range choices", 1);
                }
                if (key.startsWith("lightMenu.color.")) {
                    return new Placement("Right-click menu/Colour choices", 2);
                }
                return key.startsWith("lightMenu.flicker.") ? new Placement("Right-click menu/Flicker choices", 2)
                        : new Placement("Right-click menu/Brightness choices", 2);
            case "light":
                return new Placement("Light defaults", 1);
            case "timeOfDay":
                return new Placement(capitalize(kind), 2);
            case "weather":
                return parts.length == 3 ? new Placement(capitalize(kind), 2) : new Placement(null, 0);
            case "performance":
                return key.matches("performance\\.(interactionFps|idleFps|animationFps)")
                        ? new Placement("Frame rates", 1) : new Placement("Quality reductions", 1);
            case "autosave":
                return new Placement("Auto-save timing", 1);
            case "import":
                if (key.equals("import.autoLabelRooms") || key.equals("import.autoTags")) {
                    return new Placement(null, 1);
                }
                return key.startsWith("import.dd2vtt.") ? new Placement("dd2vtt lights", 2) : new Placement(null, 0);
            case "audio":
                return key.startsWith("audio.cut.") ? new Placement("Cut clips", 2) : new Placement(null, 1);
            case "cache":
                return key.equals("cache.textureTiles") ? new Placement(null, 0) : new Placement("Map image cache", 1);
            case "work":
                return new Placement(null, 1);
            case "ui":
                if (key.matches("ui\\.tooltip\\w+")) {
                    return new Placement("Tooltips", 1);
                }
                if (key.matches("ui\\.wall\\w+")) {
                    return new Placement("Walls", 1);
                }
                return key.matches("ui\\.(door|window)\\w+") ? new Placement("Doors and windows", 1) : new Placement(null, 0);
            case "texture":
                if (parts.length == 2) {
                    return kind.equals("tileCells") ? new Placement(null, 0) : new Placement("Soft edges", 1);
                }
                if (parts.length >= 4 && parts[2].startsWith("layer")) {
                    return new Placement(capitalize(kind) + "/" + capitalize(parts[2]), 3);
                }
                return parts[2].equals("emitsLight") || parts[2].startsWith("light")
                        ? new Placement(capitalize(kind) + "/Light emission", 2) : new Placement(capitalize(kind), 2);
            default:
                return new Placement(null, 0);
        }
    }

    // ---- search keywords ----

    private static final String KEYWORDS_RESOURCE = "/dmmt/settings-keywords.properties";
    private static java.util.Properties keywordTable;

    /**
     * Search words of a setting from {@value #KEYWORDS_RESOURCE} (the keywords of docs/SETTINGS.md). The table has a
     * line per key; generic lines use {@code *} for one key segment and a trailing {@code .*} for the rest.
     */
    static synchronized String keywords(String key) {
        if (keywordTable == null) {
            keywordTable = new java.util.Properties();
            try (java.io.InputStream in = AppSettings.class.getResourceAsStream(KEYWORDS_RESOURCE)) {
                if (in != null) {
                    keywordTable.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                // search then only uses names and descriptions
            }
        }
        String exact = keywordTable.getProperty(key);
        if (exact != null) {
            return exact;
        }
        String best = null;
        for (String pattern : keywordTable.stringPropertyNames()) {
            if (pattern.contains("*") && key.matches(patternRegex(pattern)) && (best == null || pattern.length() > best.length())) {
                best = pattern;
            }
        }
        return best == null ? "" : keywordTable.getProperty(best);
    }

    private static String patternRegex(String pattern) {
        boolean rest = pattern.endsWith(".*");
        String head = rest ? pattern.substring(0, pattern.length() - 2) : pattern;
        String regex = java.util.Arrays.stream(head.split("\\."))
                .map(part -> part.contains("*") ? java.util.regex.Pattern.quote(part).replace("*", "\\E[^.]*\\Q") : java.util.regex.Pattern.quote(part))
                .collect(java.util.stream.Collectors.joining("\\."));
        return rest ? regex + "\\..+" : regex;
    }
    private static String capitalize(String text) {
        String words = text.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replaceAll("([a-zA-Z])(\\d)", "$1 $2").toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    private static String label(String key, int skip) {
        if (key.equals("work.mode")) {
            return "Work mode";
        }
        String[] parts = key.split("\\.");
        List<String> words = new ArrayList<>();
        for (int i = Math.min(skip, parts.length - 1); i < parts.length; i++) {
            words.add(parts[i].replaceAll("([a-z0-9])([A-Z])", "$1 $2").replaceAll("([a-zA-Z])(\\d)", "$1 $2")
                    .toLowerCase(java.util.Locale.ROOT));
        }
        String joined = String.join(" › ", words);
        return Character.toUpperCase(joined.charAt(0)) + joined.substring(1);
    }


    private static List<Entry> textureEntries() {
        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry("texture.tileCells", "4", "Effect textures: size of one texture tile in grid cells (smaller = finer pattern)."));
        for (Map.Entry<String, String> e : OverlayTextures.settingsDefaults().entrySet()) {
            if (!e.getKey().equals("texture.tileCells")) {
                entries.add(new Entry(e.getKey(), e.getValue(), textureComment(e.getKey())));
            }
        }
        return entries;
    }

    private static Tuning.Kind inferKind(String value) {
        if ("true".equals(value) || "false".equals(value)) {
            return Tuning.Kind.BOOLEAN;
        }
        if (value.matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")) {
            return Tuning.Kind.COLOR;
        }
        return isNumber(value, false) ? Tuning.Kind.DECIMAL : Tuning.Kind.TEXT;
    }

    private static boolean isNumber(String text, boolean integerOnly) {
        try {
            String trimmed = text.trim();
            if (integerOnly) {
                Integer.parseInt(trimmed);
            } else {
                return Double.isFinite(Double.parseDouble(trimmed.replace(",", ".")));
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean validForKind(Tuning.Kind kind, String text) {
        return switch (kind) {
            case BOOLEAN -> "true".equalsIgnoreCase(text.trim()) || "false".equalsIgnoreCase(text.trim());
            case COLOR -> text.trim().matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?");
            case INTEGER -> isNumber(text, true);
            case DECIMAL -> isNumber(text, false);
            default -> !text.isBlank();
        };
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
