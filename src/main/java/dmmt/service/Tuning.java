package dmmt.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Behaviour and look values that a DM may want to change (zoom limits, pick tolerances, fog fade times, light
 * presets, cache sizes, ...). Each value is defined exactly once here with its settings-file key, built-in default,
 * allowed range and a short comment; {@link AppSettings} writes them to the settings file and loads them back through
 * {@link #apply(Function)}. Code reads the current value with {@code Tuning.NAME.get()}.
 * <p>
 * Numbers outside their range are clamped; values that cannot be parsed fall back to the default.
 * Every key is explained in {@code docs/SETTINGS.md}.
 */
public final class Tuning {
    private Tuning() {
    }

    // Section titles; titles that match an existing AppSettings section are merged into it.
    static final String WINDOW = "Window and panels";
    static final String PLAYER = "Player screen";
    static final String DM_VIEW = "DM view navigation";
    static final String INPUT = "Mouse and selection";
    static final String PING = "Ping and laser pointer";
    static final String EDITING = "Editing, brush and effects";
    static final String TEXT = "Text defaults";
    static final String FOG = "Fog and lighting";
    static final String LIGHTS = "Lights";
    static final String TIME_OF_DAY = "Time of day";
    static final String WEATHER = "Weather";
    static final String FRAME_RATES = "Frame rates";
    static final String PERFORMANCE_MODE = "Performance mode";
    static final String AUTOSAVE = "Auto-save";
    static final String IMPORT = "Import";
    static final String STORAGE = "Storage and caches";
    static final String UI = "User interface";

    private static final Pattern HEX_COLOR = Pattern.compile("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?");
    private static final List<Setting<?>> ALL = new ArrayList<>();

    /** A named choice of a list setting, e.g. {@code Candle=#FFD9A0}. */
    public record Choice<V>(String name, V value) {
    }

    /** One configurable value. */
    public static final class Setting<T> {
        private final String key;
        private final String section;
        private final String comment;
        private final String defaultText;
        private final boolean restart;
        private final Function<String, T> parser;
        private final T defaultValue;
        private volatile T value;

        private Setting(String section, String key, String defaultText, boolean restart, String comment,
                        Function<String, T> parser) {
            this.section = section;
            this.key = key;
            this.defaultText = defaultText;
            this.restart = restart;
            this.comment = comment;
            this.parser = parser;
            this.defaultValue = parser.apply(defaultText);
            this.value = defaultValue;
            ALL.add(this);
        }

        public T get() {
            return value;
        }

        public T defaultValue() {
            return defaultValue;
        }

        public String key() {
            return key;
        }

        public String section() {
            return section;
        }

        public String defaultText() {
            return defaultText;
        }

        /** True if the value is only read when the application starts (controls, windows, timers). */
        public boolean requiresRestart() {
            return restart;
        }

        /** Comment written above the entry in the settings file. */
        public String comment() {
            return restart ? comment + " Restart required." : comment;
        }

        void load(String raw) {
            if (raw == null || raw.isBlank()) {
                value = defaultValue;
                return;
            }
            try {
                T parsed = parser.apply(raw.trim());
                value = parsed == null ? defaultValue : parsed;
            } catch (RuntimeException e) {
                value = defaultValue;
            }
        }
    }

    // ---- factories ----

    private static Setting<Integer> integer(String section, String key, int def, int min, int max, boolean restart, String comment) {
        return new Setting<>(section, key, String.valueOf(def), restart, comment + " (" + min + " to " + max + ")",
                text -> (int) Math.max(min, Math.min(max, Math.round(Double.parseDouble(text)))));
    }

    private static Setting<Double> decimal(String section, String key, double def, double min, double max, boolean restart, String comment) {
        return new Setting<>(section, key, num(def), restart, comment + " (" + num(min) + " to " + num(max) + ")",
                text -> {
                    double v = Double.parseDouble(text.replace(',', '.'));
                    if (!Double.isFinite(v)) {
                        throw new IllegalArgumentException(text);
                    }
                    return Math.max(min, Math.min(max, v));
                });
    }

    private static Setting<Boolean> bool(String section, String key, boolean def, boolean restart, String comment) {
        return new Setting<>(section, key, String.valueOf(def), restart, comment + " (true/false)", text -> {
            if ("true".equalsIgnoreCase(text)) {
                return true;
            }
            if ("false".equalsIgnoreCase(text)) {
                return false;
            }
            throw new IllegalArgumentException(text);
        });
    }

    private static Setting<String> color(String section, String key, String def, boolean restart, String comment) {
        return new Setting<>(section, key, def, restart, comment + " (#RRGGBB)", Tuning::parseColor);
    }

    private static Setting<String> choice(String section, String key, String def, boolean restart, String comment, String... options) {
        return new Setting<>(section, key, def, restart, comment + " (" + String.join(" or ", options) + ")", text -> {
            for (String option : options) {
                if (option.equalsIgnoreCase(text)) {
                    return option;
                }
            }
            throw new IllegalArgumentException(text);
        });
    }

    private static Setting<String> text(String section, String key, String def, boolean restart, String comment) {
        return new Setting<>(section, key, def, restart, comment, text -> text);
    }

    private static Setting<List<Double>> numbers(String section, String key, String def, double min, double max, boolean restart, String comment) {
        return new Setting<>(section, key, def, restart, comment + " Comma separated, each " + num(min) + " to " + num(max) + ".", text -> {
            List<Double> values = new ArrayList<>();
            for (String part : text.split(",")) {
                if (!part.isBlank()) {
                    values.add(Math.max(min, Math.min(max, Double.parseDouble(part.trim()))));
                }
            }
            if (values.isEmpty()) {
                throw new IllegalArgumentException(text);
            }
            return Collections.unmodifiableList(values);
        });
    }

    /** Entries {@code Name=value; Name=value}; {@code value} is parsed by {@code valueParser}. */
    private static <V> Setting<List<Choice<V>>> choices(String section, String key, String def, boolean restart, String comment,
                                                       Function<String, V> valueParser) {
        return new Setting<>(section, key, def, restart, comment, text -> {
            List<Choice<V>> values = new ArrayList<>();
            for (String part : text.split(";")) {
                if (part.isBlank()) {
                    continue;
                }
                int eq = part.indexOf('=');
                String name = part.substring(0, eq).trim();
                if (name.isEmpty()) {
                    throw new IllegalArgumentException(part);
                }
                values.add(new Choice<>(name, valueParser.apply(part.substring(eq + 1).trim())));
            }
            if (values.isEmpty()) {
                throw new IllegalArgumentException(text);
            }
            return Collections.unmodifiableList(values);
        });
    }

    private static Function<String, double[]> numberTuple(int count, double min, double max) {
        return text -> {
            String[] parts = text.split("/");
            if (parts.length != count) {
                throw new IllegalArgumentException(text);
            }
            double[] values = new double[count];
            for (int i = 0; i < count; i++) {
                values[i] = Math.max(min, Math.min(max, Double.parseDouble(parts[i].trim())));
            }
            return values;
        };
    }

    static String parseColor(String text) {
        String trimmed = text.trim();
        if (!HEX_COLOR.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(text);
        }
        return trimmed.toUpperCase(Locale.ROOT);
    }

    static String num(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e9 ? String.valueOf((long) value) : String.valueOf(value);
    }

    // ---- Window ----

    public static final Setting<Integer> DM_WINDOW_WIDTH = integer(WINDOW, "window.dmWidth", 1500, 400, 10000, true,
            "Initial width of the main (DM) window in pixels.");
    public static final Setting<Integer> DM_WINDOW_HEIGHT = integer(WINDOW, "window.dmHeight", 920, 300, 10000, true,
            "Initial height of the main (DM) window in pixels.");

    // ---- Player screen ----

    public static final Setting<Double> PLAYER_ZOOM_MIN_STEP = decimal(PLAYER, "player.zoom.minStep", -2, -6, 0, true,
            "Lowest value of the player zoom slider as a power of two (-2 = 25 %).");
    public static final Setting<Double> PLAYER_ZOOM_MAX_STEP = decimal(PLAYER, "player.zoom.maxStep", 2, 0, 6, true,
            "Highest value of the player zoom slider as a power of two (2 = 400 %).");
    public static final Setting<Double> PLAYER_ZOOM_WHEEL_FACTOR = decimal(PLAYER, "player.zoom.wheelFactor", 1.1, 1.01, 4, false,
            "Player zoom change per mouse wheel notch with Ctrl held over the DM view.");
    public static final Setting<Double> PLAYER_ZOOM_CURSOR_PULL = decimal(PLAYER, "player.zoom.cursorPull", 0.4, 0, 1, false,
            "How far Ctrl+wheel zoom-in pulls the player view towards the cursor (0 = not at all, 1 = centre on cursor).");
    public static final Setting<Double> PLAYER_ZOOM_MIN = decimal(PLAYER, "player.zoom.min", 0.02, 0.001, 1, false,
            "Absolute lower limit of the calibrated player camera zoom.");
    public static final Setting<Double> PLAYER_ZOOM_MAX = decimal(PLAYER, "player.zoom.max", 48, 1, 1000, false,
            "Absolute upper limit of the calibrated player camera zoom.");
    public static final Setting<Double> PLAYER_TILE_INCHES_MIN = decimal(PLAYER, "player.tileInches.min", 0.25, 0.05, 10, true,
            "Smallest value of the 'Tile size (in)' field.");
    public static final Setting<Double> PLAYER_TILE_INCHES_MAX = decimal(PLAYER, "player.tileInches.max", 3, 0.1, 20, true,
            "Largest value of the 'Tile size (in)' field.");
    public static final Setting<Double> PLAYER_DIAGONAL_MIN = decimal(PLAYER, "player.screenDiagonal.min", 10, 1, 100, true,
            "Smallest value of the 'Screen diagonal (in)' field and of the DPI based estimate.");
    public static final Setting<Double> PLAYER_DIAGONAL_MAX = decimal(PLAYER, "player.screenDiagonal.max", 120, 10, 1000, true,
            "Largest value of the 'Screen diagonal (in)' field and of the DPI based estimate.");
    public static final Setting<Double> PLAYER_DIAGONAL_FALLBACK = decimal(PLAYER, "player.screenDiagonal.fallback", 27, 1, 1000, false,
            "Screen diagonal in inches used when the field has no value.");

    // ---- DM view ----

    public static final Setting<Double> DM_ZOOM_MIN = decimal(DM_VIEW, "dm.zoom.min", 0.1, 0.001, 1, false,
            "Furthest the DM view can zoom out.");
    public static final Setting<Double> DM_ZOOM_MAX = decimal(DM_VIEW, "dm.zoom.max", 6, 1, 100, false,
            "Closest the DM view can zoom in.");
    public static final Setting<Double> DM_ZOOM_WHEEL_FACTOR = decimal(DM_VIEW, "dm.zoom.wheelFactor", 1.1, 1.01, 4, false,
            "DM zoom change per mouse wheel notch (zooming out divides by the same factor).");

    // ---- Mouse and selection ----

    public static final Setting<Double> LIGHT_PICK_RADIUS = decimal(INPUT, "input.lightPickRadiusPx", 24, 1, 200, false,
            "Screen distance in pixels within which a click selects, removes or hovers a light.");
    public static final Setting<Double> LAYER_HANDLE_RADIUS = decimal(INPUT, "input.layerHandleRadiusPx", 16, 1, 200, false,
            "Grab distance in pixels of the resize corner of an image layer.");
    public static final Setting<Double> SHAPE_HANDLE_RADIUS = decimal(INPUT, "input.shapeHandleRadiusPx", 12, 1, 200, false,
            "Grab distance in pixels of the resize handle of an effect shape.");
    public static final Setting<Double> TEXT_HANDLE_RADIUS = decimal(INPUT, "input.textHandleRadiusPx", 9, 1, 200, false,
            "Grab distance in pixels of the eight resize handles of a text box.");
    public static final Setting<Double> WALL_PICK_RADIUS = decimal(INPUT, "input.wallPickRadiusPx", 10, 1, 200, false,
            "Click tolerance in pixels for door/window lines and for erasing walls.");
    public static final Setting<Double> SHAPE_PICK_RADIUS = decimal(INPUT, "input.shapePickRadiusPx", 6, 0, 200, false,
            "Extra click tolerance in pixels around freehand, pen and line effects.");
    public static final Setting<Double> MARQUEE_MIN_DRAG = decimal(INPUT, "input.marqueeMinDragPx", 4, 0, 200, false,
            "Mouse movement in pixels before a drag on empty map becomes a selection box.");
    public static final Setting<Double> RIGHT_CLICK_MAX_MOVE = decimal(INPUT, "input.rightClickMaxMovePx", 5, 0, 200, false,
            "Largest mouse movement in pixels for a right-click to still cancel the tool instead of panning.");
    public static final Setting<Integer> DOOR_DEBOUNCE_MS = integer(INPUT, "input.doorDebounceMs", 60, 0, 2000, false,
            "Repeated clicks on the same door within this many milliseconds are ignored (mouse switch bounce).");
    public static final Setting<Integer> RECENT_INPUT_MS = integer(INPUT, "input.recentInputMs", 1000, 0, 60000, false,
            "Milliseconds after the last input during which the target frame rate is kept before dropping to idle.");

    // ---- Ping and laser ----

    public static final Setting<Integer> PING_DURATION_MS = integer(PING, "ping.durationMs", 1200, 100, 60000, false,
            "How long a ping animation lasts in milliseconds.");
    public static final Setting<Double> PING_START_RADIUS = decimal(PING, "ping.startRadiusPx", 10, 0, 500, false,
            "Radius of the ping ring in screen pixels when it appears.");
    public static final Setting<Double> PING_GROW = decimal(PING, "ping.growPx", 50, 0, 2000, false,
            "How many pixels the ping ring grows over its lifetime.");
    public static final Setting<String> PING_COLOR = color(PING, "ping.color", "#FFE633", false,
            "Colour of the ping ring and centre dot.");
    public static final Setting<Integer> LASER_TRAIL_MS = integer(PING, "laser.trailMs", 500, 0, 10000, false,
            "How long the laser pointer trail stays visible in milliseconds.");
    public static final Setting<Double> LASER_DM_DOT = decimal(PING, "laser.dmDotPx", 6, 1, 100, false,
            "Radius of the laser dot in the DM view in pixels.");
    public static final Setting<Double> LASER_PLAYER_DOT_INCHES = decimal(PING, "laser.playerDotInches", 0.15, 0, 5, false,
            "Radius of the laser dot on the player screen in physical inches.");
    public static final Setting<Double> LASER_PLAYER_DOT_MIN = decimal(PING, "laser.playerDotMinPx", 6, 1, 200, false,
            "Smallest radius of the laser dot on the player screen in pixels.");
    public static final Setting<String> LASER_COLOR = color(PING, "laser.color", "#FF1A1A", false,
            "Colour of the laser pointer dot and trail.");

    // ---- Editing ----

    public static final Setting<Integer> HISTORY_MAX_STEPS = integer(EDITING, "history.maxSteps", 100, 1, 10000, false,
            "Number of undo steps kept.");
    public static final Setting<Double> BRUSH_DEFAULT = decimal(EDITING, "brush.defaultSizeTiles", 1.5, 0.1, 200, true,
            "Brush size in tiles when the application starts.");
    public static final Setting<Double> BRUSH_MIN = decimal(EDITING, "brush.minTiles", 0.2, 0.05, 10, true,
            "Smallest brush size of the brush slider in tiles.");
    public static final Setting<Double> BRUSH_MAX = decimal(EDITING, "brush.maxTiles", 8, 0.5, 200, true,
            "Largest brush size of the brush slider in tiles.");
    public static final Setting<String> EFFECT_DEFAULT_COLOR = color(EDITING, "effects.defaultColor", "#55AA33", true,
            "Colour of new effect shapes until a texture or colour is picked.");
    public static final Setting<Double> EFFECT_DEFAULT_OPACITY = decimal(EDITING, "effects.defaultOpacity", 0.4, 0.1, 1, true,
            "Opacity of new effect shapes until a texture or opacity is picked.");
    public static final Setting<Double> PEN_WIDTH_CELLS = decimal(EDITING, "effects.penWidthCells", 0.06, 0.005, 2, false,
            "Line width of the Pen tool in grid cells.");
    public static final Setting<Double> HIDDEN_SHAPE_OPACITY = decimal(EDITING, "effects.dmHiddenOpacity", 0.35, 0, 1, false,
            "Opacity factor of effects hidden from players, as drawn in the DM view.");

    // ---- Text ----

    public static final Setting<Double> HIDDEN_TEXT_OPACITY = decimal(TEXT, "text.dmHiddenOpacity", 0.85, 0, 1, false,
            "Opacity of text boxes hidden from players, as drawn in the DM view.");
    public static final Setting<Integer> TEXT_MIN_FONT = integer(TEXT, "text.minFontSize", 6, 1, 100, true,
            "Smallest font size of the font size field.");
    public static final Setting<Integer> TEXT_MAX_FONT = integer(TEXT, "text.maxFontSize", 400, 10, 2000, true,
            "Largest font size of the font size field.");
    public static final Setting<Double> TEXT_AUTO_MAX_CELLS = decimal(TEXT, "text.autoMaxWidthCells", 12, 1, 200, false,
            "Widest an auto-sized text box grows before wrapping, in grid cells.");
    public static final Setting<Double> TEXT_MIN_BOX = decimal(TEXT, "text.minBoxSize", 40, 5, 1000, false,
            "Smallest width and height of a text box in map pixels; also the size of a box created by a plain click.");

    // ---- Fog ----

    public static final Setting<Integer> FOG_CELLS_MIN = integer(FOG, "fog.cellsPerGrid.min", 5, 1, 100, true,
            "Lowest fog sharpness (fog cells per tile); also the resolution performance mode draws fog at.");
    public static final Setting<Integer> FOG_CELLS_MAX = integer(FOG, "fog.cellsPerGrid.max", 30, 1, 200, true,
            "Highest fog sharpness (fog cells per tile).");
    public static final Setting<Double> FOG_SOFTNESS_MAX = decimal(FOG, "fog.softness.max", 1, 0, 5, true,
            "Upper limit of the fog softness slider in tiles.");
    public static final Setting<Double> FOG_REVEAL_SECONDS = decimal(FOG, "fog.revealSeconds", 0.5, 0.01, 30, false,
            "Seconds fog needs to fade out completely when it is revealed by a tool.");
    public static final Setting<Double> FOG_HIDE_SECONDS = decimal(FOG, "fog.hideSeconds", 0.5, 0.01, 30, false,
            "Seconds fog needs to fade in completely when it is hidden by a tool.");
    public static final Setting<Double> FOG_LIGHT_FADE_SECONDS = decimal(FOG, "fog.lightFadeSeconds", 0.16, 0.01, 30, false,
            "Fade time in seconds when fog changes because of a light or door (short, so moving lights do not trail).");
    public static final Setting<Double> FOG_FADE_MAX_STEP = decimal(FOG, "fog.fadeMaxStepSeconds", 0.1, 0.01, 2, false,
            "Longest time step one frame may advance a fog fade (prevents jumps after a stall).");
    public static final Setting<String> FOG_FADE_EASING = choice(FOG, "fog.fadeEasing", "linear", false,
            "Shape of the fog fade: constant speed, or slow start and end.", "linear", "smooth");
    public static final Setting<Double> FOG_DM_OPACITY = decimal(FOG, "fog.dmOpacity", 0.58, 0, 1, false,
            "Opacity of fully fogged areas in the DM view (players always see 1).");

    // ---- Lights ----

    public static final Setting<Double> LIGHT_TINT_MAX = decimal(LIGHTS, "lighting.tint.max", 0.3, 0, 1, true,
            "Upper limit of the light tint slider.");
    public static final Setting<Double> DM_DARKNESS_FACTOR = decimal(LIGHTS, "lighting.dmDarknessFactor", 0.65, 0, 1, false,
            "Share of the time-of-day darkness shown in the DM view (players get all of it).");
    public static final Setting<Integer> LIGHT_MAP_SCALE = integer(LIGHTS, "lighting.lightMapScale", 4, 1, 32, false,
            "Light map resolution divisor: the lighting is computed at 1/N of the screen resolution.");
    public static final Setting<Integer> SHADOW_RAYS = integer(LIGHTS, "lighting.shadowRays", 96, 8, 1024, true,
            "Rays per light for line of sight and shadows; more gives rounder light circles.");

    /** Values of one light preset button. */
    public record LightPresetSettings(Setting<Double> rangeTiles, Setting<String> color, Setting<Double> flicker,
                                      Setting<Double> flickerSpeed) {
    }

    private static final Map<String, LightPresetSettings> LIGHT_PRESETS = new LinkedHashMap<>();

    private static void lightPreset(String id, String label, double range, String color, double flicker, double speed) {
        String p = "lightPreset." + id + ".";
        LIGHT_PRESETS.put(id, new LightPresetSettings(
                decimal(LIGHTS, p + "rangeTiles", range, 0.5, 1000, false, label + " preset: light range in tiles."),
                color(LIGHTS, p + "color", color, false, label + " preset: light colour."),
                decimal(LIGHTS, p + "flicker", flicker, 0, 1, false, label + " preset: flicker depth, 0 = steady."),
                decimal(LIGHTS, p + "flickerSpeed", speed, 0, 20, false, label + " preset: flicker speed, 1 = normal.")));
    }

    static {
        lightPreset("torch", "Torch (Add light)", 6, "#FFB35C", 0.22, 1.4);
        lightPreset("candle", "Candle", 2, "#FFD98A", 0.12, 2.5);
        lightPreset("lantern", "Lantern", 8, "#FFF4E0", 0.22, 1.4);
        lightPreset("campfire", "Campfire", 12, "#FF8A3D", 0.35, 1.8);
        lightPreset("magic", "Magic light", 12, "#CFE4FF", 0, 0);
    }

    /** Preset ids: torch, candle, lantern, campfire, magic. */
    public static LightPresetSettings lightPreset(String id) {
        LightPresetSettings preset = LIGHT_PRESETS.get(id);
        if (preset == null) {
            throw new IllegalArgumentException("Unknown light preset " + id);
        }
        return preset;
    }

    public static final Setting<List<Double>> LIGHT_MENU_RANGES = numbers(LIGHTS, "lightMenu.rangeTiles",
            "1, 2, 3, 4, 6, 8, 12, 16, 24, 100", 0.1, 10000, false,
            "Range choices (in tiles) of the light right-click menu.");
    public static final Setting<List<Choice<String>>> LIGHT_MENU_COLORS = choices(LIGHTS, "lightMenu.colors",
            "Warm torch=#FFB35C; Candle=#FFD9A0; Neutral=#FFF4E0; Moonlight=#A8C8FF; Arcane=#C08CFF; Fire=#FF6A3D", false,
            "Colour choices of the light right-click menu: Name=#RRGGBB separated by ';'.", Tuning::parseColor);
    public static final Setting<List<Choice<double[]>>> LIGHT_MENU_FLICKER = choices(LIGHTS, "lightMenu.flicker",
            "Off=0/0; Candle=0.12/2.5; Torch=0.22/1.4; Strong torch=0.35/1.8; Slow pulse=0.3/0.35", false,
            "Flicker choices of the light right-click menu: Name=depth/speed separated by ';' (depth 0 = off).",
            numberTuple(2, 0, 20));
    public static final Setting<List<Choice<double[]>>> LIGHT_MENU_BRIGHTNESS = choices(LIGHTS, "lightMenu.brightness",
            "Dim=0.4; Normal=0.75; Bright=1", false,
            "Brightness choices of the light right-click menu: Name=value (0-1) separated by ';'.",
            numberTuple(1, 0, 1));
    public static final Setting<Double> LIGHT_DEFAULT_RANGE = decimal(LIGHTS, "light.defaultRange", 300, 1, 100000, false,
            "Range in map pixels of a light whose saved data has no range.");
    public static final Setting<Double> LIGHT_DEFAULT_FLICKER = decimal(LIGHTS, "light.defaultFlicker", 0.18, 0, 1, false,
            "Flicker depth used when a light without saved flicker values gets flicker.");
    public static final Setting<Double> LIGHT_DEFAULT_FLICKER_SPEED = decimal(LIGHTS, "light.defaultFlickerSpeed", 1.5, 0.05, 20, false,
            "Flicker speed used when a light without saved flicker values gets flicker.");

    // ---- Time of day ----

    /** Darkness and ambient colour of one time-of-day preset. */
    public record TimeOfDaySettings(Setting<Double> darkness, Setting<Double> red, Setting<Double> green, Setting<Double> blue) {
    }

    private static final Map<String, TimeOfDaySettings> TIME_PRESETS = new LinkedHashMap<>();

    private static void timeOfDay(String id, String label, double darkness, double red, double green, double blue) {
        String p = "timeOfDay." + id + ".";
        TIME_PRESETS.put(id, new TimeOfDaySettings(
                decimal(TIME_OF_DAY, p + "darkness", darkness, 0, 1, false, label + ": darkness over unlit areas."),
                decimal(TIME_OF_DAY, p + "red", red, 0, 1, false, label + ": red part of the ambient colour."),
                decimal(TIME_OF_DAY, p + "green", green, 0, 1, false, label + ": green part of the ambient colour."),
                decimal(TIME_OF_DAY, p + "blue", blue, 0, 1, false, label + ": blue part of the ambient colour.")));
    }

    static {
        timeOfDay("day", "Day", 0, 0, 0, 0);
        timeOfDay("dawn", "Dawn", 0.35, 0.22, 0.16, 0.30);
        timeOfDay("dusk", "Dusk", 0.55, 0.20, 0.09, 0.12);
        timeOfDay("night", "Night", 0.86, 0.01, 0.02, 0.08);
    }

    /** Preset ids: day, dawn, dusk, night. */
    public static TimeOfDaySettings timeOfDay(String id) {
        TimeOfDaySettings preset = TIME_PRESETS.get(id.toLowerCase(Locale.ROOT));
        if (preset == null) {
            throw new IllegalArgumentException("Unknown time of day " + id);
        }
        return preset;
    }

    // ---- Weather ----

    public static final Setting<Double> WEATHER_DEFAULT_INTENSITY = decimal(WEATHER, "weather.defaultIntensity", 0.4, 0.1, 1, false,
            "Weather intensity of new maps and of a double-click reset of the intensity slider.");

    /** Look of one weather type. */
    public record WeatherSettings(Setting<Integer> particles, Setting<String> color, Setting<Double> opacity) {
    }

    private static final Map<String, WeatherSettings> WEATHER_TYPES = new LinkedHashMap<>();

    private static void weather(String id, String label, int particles, int maxParticles, String color, double opacity) {
        String p = "weather." + id + ".";
        WEATHER_TYPES.put(id, new WeatherSettings(
                integer(WEATHER, p + "particles", particles, 0, maxParticles, false,
                        label + ": particles at full intensity on a 1920x1080 screen."),
                color(WEATHER, p + "color", color, false, label + ": particle colour."),
                decimal(WEATHER, p + "opacity", opacity, 0, 1, false, label + ": highest particle opacity.")));
    }

    static {
        weather("rain", "Rain", 600, 10000, "#C8D7EB", 0.42);
        weather("snow", "Snow", 610, 10000, "#F5F8FF", 0.5);
        weather("mist", "Mist (particles = fog banks)", 70, 500, "#DBE3ED", 0.12);
        weather("dust", "Dust motes", 500, 10000, "#FFF0CD", 0.48);
        weather("embers", "Embers", 295, 10000, "#FF963C", 0.75);
    }

    /** Weather ids: rain, snow, mist, dust, embers. */
    public static WeatherSettings weather(String id) {
        WeatherSettings settings = WEATHER_TYPES.get(id.toLowerCase(Locale.ROOT));
        if (settings == null) {
            throw new IllegalArgumentException("Unknown weather " + id);
        }
        return settings;
    }

    // ---- Frame rates ----

    public static final Setting<Integer> MAX_FPS = integer(FRAME_RATES, "render.maxFps", 240, 1, 1000, true,
            "Upper limit for all frame rate settings.");

    // ---- Performance mode ----

    public static final Setting<Integer> PERF_INTERACTION_FPS = integer(PERFORMANCE_MODE, "performance.interactionFps", 20, 1, 240, false,
            "Frame rate while interacting with the map in performance mode (never above render.targetFps).");
    public static final Setting<Integer> PERF_IDLE_FPS = integer(PERFORMANCE_MODE, "performance.idleFps", 5, 1, 240, false,
            "Frame rate when nothing moves in performance mode (render.idleFps is used if lower).");
    public static final Setting<Integer> PERF_ANIMATION_FPS = integer(PERFORMANCE_MODE, "performance.animationFps", 10, 1, 240, false,
            "Frame rate of moving effect textures and weather in performance mode.");
    public static final Setting<Integer> PERF_LIGHT_MAP_SCALE = integer(PERFORMANCE_MODE, "performance.lightMapScale", 8, 1, 32, false,
            "Light map resolution divisor in performance mode (see lighting.lightMapScale).");
    public static final Setting<Integer> PERF_MAX_FEATHER_PASSES = integer(PERFORMANCE_MODE, "performance.maxFeatherPasses", 6, 1, 24, false,
            "Most soft-edge passes of effect textures in performance mode.");
    public static final Setting<Integer> PERF_IMAGE_LEVEL_BIAS = integer(PERFORMANCE_MODE, "performance.imageLevelBias", 2, 0, 8, false,
            "How many detail levels coarser map images are drawn in performance mode (each level halves the resolution).");
    public static final Setting<Boolean> PERF_FOG_FADE = bool(PERFORMANCE_MODE, "performance.fogFade", false, false,
            "Keep fading fog in and out while performance mode is on.");

    // ---- Auto-save ----

    public static final Setting<List<Double>> AUTOSAVE_MINUTE_OPTIONS = numbers(AUTOSAVE, "autosave.minuteOptions", "1, 2, 5, 10",
            1, 1440, true, "Interval choices (in minutes) of the auto-save menu.");
    public static final Setting<Integer> AUTOSAVE_CHECK_SECONDS = integer(AUTOSAVE, "autosave.checkSeconds", 10, 1, 600, true,
            "How often in seconds the auto-save timer checks whether a save is due.");

    // ---- Import ----

    public static final Setting<Double> DD2VTT_LIGHT_FLICKER = decimal(IMPORT, "import.dd2vtt.lightFlicker", 0.22, 0, 1, false,
            "Flicker depth given to lights imported from dd2vtt/uvtt files (0 = flicker off).");
    public static final Setting<Double> DD2VTT_LIGHT_FLICKER_SPEED = decimal(IMPORT, "import.dd2vtt.lightFlickerSpeed", 1.4, 0.05, 20, false,
            "Flicker speed given to lights imported from dd2vtt/uvtt files.");

    // ---- Storage and caches ----

    public static final Setting<String> LIBRARY_FOLDER = text(STORAGE, "library.folder", "", true,
            "Folder of the map library. Empty = 'dmmap-projects' next to the application; relative paths start there too.");
    public static final Setting<Integer> CACHE_IMAGE_TILES = integer(STORAGE, "cache.imageTiles", 96, 8, 4096, false,
            "Map image tiles kept in memory (each up to cache.imageTileSize squared pixels).");
    public static final Setting<Integer> CACHE_TILE_QUEUE = integer(STORAGE, "cache.imageTileLoadQueue", 48, 4, 4096, false,
            "Most image tile loads waiting at once; older requests are dropped when panning fast.");
    public static final Setting<Integer> CACHE_RETENTION_DAYS = integer(STORAGE, "cache.retentionDays", 60, 1, 3650, true,
            "Unused image caches on disk are deleted after this many days.");
    public static final Setting<Integer> CACHE_TILE_SIZE = integer(STORAGE, "cache.imageTileSize", 1024, 256, 4096, false,
            "Edge length in pixels of the tiles of large map images. Changing it rebuilds the image caches.");
    public static final Setting<Integer> CACHE_OVERVIEW_SIZE = integer(STORAGE, "cache.overviewMaxSize", 4096, 512, 8192, false,
            "Images up to this many pixels per side are drawn directly; larger ones get a tile pyramid. Changing it rebuilds the image caches.");
    public static final Setting<Double> CACHE_JPEG_QUALITY = decimal(STORAGE, "cache.jpegQuality", 0.92, 0.1, 1, false,
            "JPEG quality of cached image tiles. Changing it rebuilds the image caches.");
    public static final Setting<Integer> CACHE_TEXTURE_TILES = integer(STORAGE, "cache.textureTiles", 128, 8, 4096, false,
            "Generated effect texture tiles (one per texture and colour) kept in memory.");

    // ---- User interface ----

    public static final Setting<Integer> TOOLTIP_DELAY_MS = integer(UI, "ui.tooltipDelayMs", 300, 0, 10000, true,
            "Milliseconds the mouse must rest on a control before its tooltip appears.");
    public static final Setting<Integer> TOOLTIP_DURATION_SECONDS = integer(UI, "ui.tooltipDurationSeconds", 12, 1, 600, true,
            "Seconds a tooltip stays visible.");
    public static final Setting<Integer> LIBRARY_AUTO_EXPAND_MS = integer(UI, "ui.libraryAutoExpandMs", 700, 0, 10000, true,
            "Milliseconds a dragged map must hover over a closed folder in the map library before it opens.");
    public static final Setting<String> WALL_COLOR = color(UI, "ui.wallColor", "#FF2A2A", false,
            "Colour of walls in the DM view.");
    public static final Setting<Double> WALL_OPACITY = decimal(UI, "ui.wallOpacity", 0.9, 0, 1, false,
            "Opacity of walls in the DM view.");
    public static final Setting<String> DOOR_OPEN_COLOR = color(UI, "ui.doorOpenColor", "#32CD32", false,
            "Colour of open doors (line and badge) in the DM view.");
    public static final Setting<String> DOOR_CLOSED_COLOR = color(UI, "ui.doorClosedColor", "#E0473C", false,
            "Colour of closed doors (line and badge) in the DM view.");
    public static final Setting<String> WINDOW_OPEN_COLOR = color(UI, "ui.windowOpenColor", "#00BFFF", false,
            "Colour of open windows (line and badge) in the DM view.");
    public static final Setting<String> WINDOW_CLOSED_COLOR = color(UI, "ui.windowClosedColor", "#3B6FD8", false,
            "Colour of closed windows (line and badge) in the DM view.");
    public static final Setting<Double> GRID_OPACITY = decimal(UI, "ui.gridOpacity", 0.08, 0, 1, false,
            "Opacity of the grid lines.");

    // ---- loading ----

    /** Every setting in definition order. */
    public static List<Setting<?>> all() {
        return Collections.unmodifiableList(ALL);
    }

    /** Loads every setting through {@code lookup} (key to raw value, null if missing); missing or invalid values use the default. */
    public static void apply(Function<String, String> lookup) {
        for (Setting<?> setting : ALL) {
            setting.load(lookup.apply(setting.key()));
        }
    }

    /** Restores every built-in default. */
    public static void reset() {
        apply(key -> null);
    }
}
