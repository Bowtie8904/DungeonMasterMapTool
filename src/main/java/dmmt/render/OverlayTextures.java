package dmmt.render;

import dmmt.service.Tuning;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Procedural, seamlessly tiling textures for effect shapes. The renderer scrolls (and optionally pulses) a few
 * differently scaled layers of one tile over a shape. Every texture is colourised with the shape colour, so the
 * default colour of a texture (smoke grey, fire red, ...) can be replaced freely (orange water = lava).
 */
public final class OverlayTextures {
    public static final String NONE = "none";
    public static final String SMOKE = "smoke";
    public static final String FIRE = "fire";
    public static final String WATER = "water";
    public static final String LAVA = "lava";
    public static final String ACID = "acid";
    public static final String ICE = "ice";
    public static final String LIGHTNING = "lightning";
    public static final String ARCANE = "arcane";
    public static final String DARKNESS = "darkness";
    public static final String MIST = "mist";
    public static final String BLOOD = "blood";
    public static final String WEB = "web";
    public static final String HOLY = "holy";
    public static final String GREASE = "grease";
    public static final String SAND = "sand";
    public static final String WIND = "wind";
    public static final String RADIATION = "radiation";
    public static final String POISON = "poison";
    public static final String SWAMP = "swamp";
    public static final String RUBBLE = "rubble";
    public static final String THORNS = "thorns";
    public static final String FORCE = "force";
    public static final String NECROTIC = "necrotic";
    public static final String PORTAL = "portal";
    public static final String CHASM = "chasm";

    public static final int TILE_SIZE = 256;

    /**
     * One copy of the tile: speeds in tiles per second, scale relative to the base tile size, opacity, and an
     * optional pulse (depth 0-1 at pulseHz) that fades the layer in and out.
     */
    public record Layer(double vx, double vy, double scale, double alpha, double pulse, double pulseHz) {
        Layer(double vx, double vy, double scale, double alpha) {
            this(vx, vy, scale, alpha, 0, 0);
        }

        boolean moves() {
            return vx != 0 || vy != 0 || pulse > 0;
        }
    }

    private interface PixelFn {
        int argb(double u, double v, int rgb);
    }

    private record Definition(String label, String color, List<Layer> layers, PixelFn pixel) {
    }

    private static final Map<String, Definition> DEFINITIONS = new LinkedHashMap<>();

    static {
        DEFINITIONS.put(NONE, new Definition("Flat color", null, List.of(), (u, v, rgb) -> 0));
        DEFINITIONS.put(SMOKE, new Definition("Smoke", "#9A9A9A",
                List.of(new Layer(0.020, -0.035, 1.0, 0.85), new Layer(-0.015, -0.012, 1.7, 0.6)), OverlayTextures::smoke));
        DEFINITIONS.put(FIRE, new Definition("Fire", "#FF4A1A",
                List.of(new Layer(0.0, -0.75, 1.0, 0.95), new Layer(0.06, -1.2, 0.55, 0.65, 0.3, 3.0)), OverlayTextures::fire));
        DEFINITIONS.put(WATER, new Definition("Water", "#2A7BE0",
                List.of(new Layer(0.035, 0.018, 1.0, 0.9), new Layer(-0.028, 0.042, 0.75, 0.6)), OverlayTextures::water));
        DEFINITIONS.put(LAVA, new Definition("Lava", "#E01A08",
                List.of(new Layer(0.008, 0.005, 1.0, 0.95), new Layer(-0.006, 0.010, 1.6, 0.45, 0.5, 0.25)), OverlayTextures::lava));
        DEFINITIONS.put(ACID, new Definition("Acid / slime", "#6BD62A",
                List.of(new Layer(0.010, -0.030, 1.0, 0.9), new Layer(-0.012, -0.020, 0.7, 0.6)), OverlayTextures::acid));
        DEFINITIONS.put(ICE, new Definition("Ice / frost", "#9AD8FF",
                List.of(new Layer(0, 0, 1.0, 0.95), new Layer(0.004, 0.002, 1.5, 0.35, 0.8, 0.3)), OverlayTextures::ice));
        DEFINITIONS.put(LIGHTNING, new Definition("Lightning", "#8FB4FF",
                List.of(new Layer(0.03, 0.0, 1.0, 1.0, 0.95, 3.1), new Layer(-0.02, 0.04, 0.7, 0.9, 0.95, 4.3)), OverlayTextures::lightning));
        DEFINITIONS.put(ARCANE, new Definition("Arcane runes", "#B36BFF",
                List.of(new Layer(0.012, 0.0, 1.0, 0.9, 0.35, 0.4), new Layer(-0.010, 0.006, 0.6, 0.6, 0.5, 0.7)), OverlayTextures::arcane));
        DEFINITIONS.put(DARKNESS, new Definition("Darkness / void", "#3B2160",
                List.of(new Layer(0.022, -0.016, 1.0, 0.95), new Layer(-0.017, 0.011, 1.5, 0.85)), OverlayTextures::darkness));
        DEFINITIONS.put(MIST, new Definition("Mist / fog", "#D8E4EA",
                List.of(new Layer(0.008, -0.004, 1.5, 0.8), new Layer(-0.006, 0.003, 2.3, 0.6)), OverlayTextures::mist));
        DEFINITIONS.put(BLOOD, new Definition("Blood", "#8A0A0A",
                List.of(new Layer(0, 0, 1.0, 1.0)), OverlayTextures::blood));
        DEFINITIONS.put(WEB, new Definition("Spider web", "#E8E8E8",
                List.of(new Layer(0, 0, 0.8, 1.0)), OverlayTextures::web));
        DEFINITIONS.put(HOLY, new Definition("Holy light", "#FFD85A",
                List.of(new Layer(0.004, -0.09, 1.0, 0.9, 0.3, 0.5), new Layer(-0.006, -0.05, 0.6, 0.7, 0.5, 0.8)), OverlayTextures::holy));
        DEFINITIONS.put(GREASE, new Definition("Grease / oil", "#1C1A16",
                List.of(new Layer(0.006, 0.003, 1.0, 0.95), new Layer(-0.004, 0.005, 1.4, 0.4)), OverlayTextures::grease));
        DEFINITIONS.put(SAND, new Definition("Sand / dust storm", "#D9B26A",
                List.of(new Layer(0.25, 0.01, 1.0, 0.9), new Layer(0.40, -0.02, 0.6, 0.6)), OverlayTextures::sand));
        DEFINITIONS.put(WIND, new Definition("Wind gusts", "#DDEEEE",
                List.of(new Layer(0.50, 0.0, 1.0, 0.8), new Layer(0.80, 0.02, 0.6, 0.5)), OverlayTextures::wind));
        DEFINITIONS.put(RADIATION, new Definition("Radiation / aura", "#7CFF3A",
                List.of(new Layer(0.015, 0.010, 1.0, 0.9, 0.6, 0.5), new Layer(-0.012, 0.008, 1.4, 0.6, 0.6, 0.8)), OverlayTextures::radiation));
        DEFINITIONS.put(POISON, new Definition("Poison / toxic gas", "#8FC12A",
                List.of(new Layer(0.018, -0.012, 1.0, 0.9, 0.2, 0.35), new Layer(-0.014, 0.010, 1.6, 0.65)), OverlayTextures::poison));
        DEFINITIONS.put(SWAMP, new Definition("Swamp / mud / bog", "#4E5A2C",
                List.of(new Layer(0.004, 0.002, 1.0, 0.95), new Layer(-0.003, 0.004, 1.5, 0.45, 0.3, 0.2)), OverlayTextures::swamp));
        DEFINITIONS.put(RUBBLE, new Definition("Rubble / debris", "#8C867C",
                List.of(new Layer(0, 0, 1.0, 1.0)), OverlayTextures::rubble));
        DEFINITIONS.put(THORNS, new Definition("Thorns / brambles", "#4F7A2A",
                List.of(new Layer(0.004, 0.002, 1.0, 0.95), new Layer(-0.003, 0.003, 1.6, 0.6)), OverlayTextures::thorns));
        DEFINITIONS.put(FORCE, new Definition("Force / shield", "#5AC8FF",
                List.of(new Layer(0.0, 0.0, 1.0, 0.9, 0.5, 0.6), new Layer(0.02, 0.012, 1.7, 0.5, 0.4, 0.9)), OverlayTextures::force));
        DEFINITIONS.put(NECROTIC, new Definition("Necrotic / shadow rot", "#5B2A86",
                List.of(new Layer(0.014, -0.010, 1.0, 0.95, 0.25, 0.3), new Layer(-0.012, 0.008, 1.4, 0.75)), OverlayTextures::necrotic));
        DEFINITIONS.put(PORTAL, new Definition("Entropy / void / portal", "#8A4DFF",
                List.of(new Layer(0.010, -0.008, 1.0, 0.95, 0.35, 0.5), new Layer(-0.012, 0.010, 0.6, 0.7, 0.5, 0.8)), OverlayTextures::portal));
        DEFINITIONS.put(CHASM, new Definition("Chasm / broken earth", "#7A6248",
                List.of(new Layer(0, 0, 1.0, 1.0)), OverlayTextures::chasm));    }

    public static final List<String> KINDS = List.copyOf(DEFINITIONS.keySet());

    // One tile per texture+colour. Must exceed the number of combinations drawn per frame, otherwise the LRU cache
    // thrashes and every frame regenerates tiles (setting cache.textureTiles).
    private static final Map<String, Image> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) {
            return size() > Tuning.CACHE_TEXTURE_TILES.get();
        }
    };

    private OverlayTextures() {
    }

    private record Custom(String color, Double alpha, Boolean soft, List<Layer> layers, Boolean emits,
                          Double lightStrength, Double lightRange, Double flicker, Double flickerSpeed) {
    }

    private static volatile Map<String, Custom> overrides = Map.of();
    private static volatile double featherCells = 0.9;
    private static volatile int featherPasses = 8;
    private static volatile double tileCells = 4;

    /** True for every texture other than flat colour. */
    public static boolean isAnimated(String kind) {
        return !NONE.equals(normalize(kind));
    }

    /** Soft textures (fire, smoke, ...) fade out at the shape edge instead of ending in a hard cut. */
    public static boolean isSoft(String kind) {
        String k = normalize(kind);
        Custom o = overrides.get(k);
        return o != null && o.soft() != null ? o.soft() : builtInSoft(k);
    }

    private static boolean builtInSoft(String k) {
        return switch (k) {
            case SMOKE, FIRE, MIST, DARKNESS, HOLY, SAND, WIND, LIGHTNING, RADIATION, POISON, NECROTIC, PORTAL -> true;
            default -> false;
        };
    }

    /** True when the texture changes over time and so needs continuous redraws. */
    public static boolean isMoving(String kind) {
        return layers(kind).stream().anyMatch(Layer::moves);
    }

    public static String normalize(String kind) {
        return kind != null && DEFINITIONS.containsKey(kind) ? kind : NONE;
    }

    public static String label(String kind) {
        return DEFINITIONS.get(normalize(kind)).label();
    }

    /** Colour a texture starts with when it is picked; null for flat colour (the current colour is kept). */
    public static String defaultColor(String kind) {
        String k = normalize(kind);
        Custom o = overrides.get(k);
        return o != null && o.color() != null ? o.color() : DEFINITIONS.get(k).color();
    }

    /** Opacity a texture starts with when it is picked; 0 for flat colour (the current opacity is kept). */
    public static double defaultAlpha(String kind) {
        String k = normalize(kind);
        Custom o = overrides.get(k);
        return o != null && o.alpha() != null ? o.alpha() : builtInAlpha(k);
    }

    private static double builtInAlpha(String k) {
        return switch (k) {
            case SMOKE, MIST -> 0.75;
            case FIRE -> 0.90;
            case WATER -> 0.55;
            case LAVA, BLOOD, WEB, DARKNESS, RUBBLE, CHASM -> 1.0;
            case ACID, GREASE, HOLY, ARCANE, FORCE -> 0.85;
            case LIGHTNING -> 0.9;
            case ICE, SAND, RADIATION -> 0.75;
            case WIND -> 0.65;
            case POISON -> 0.75;
            case SWAMP -> 0.9;
            case THORNS -> 0.95;
            case NECROTIC, PORTAL -> 0.9;
            default -> 0;
        };
    }

    /** Whether a new effect of this texture emits light by default. */
    public static boolean defaultEmitsLight(String kind) {
        String k = normalize(kind);
        Custom o = overrides.get(k);
        return o != null && o.emits() != null ? o.emits() : builtInEmits(k);
    }

    /** Brightness (0-1) of the light emitted by an effect of this texture. */
    public static double lightStrength(String kind) {
        String k = normalize(kind);
        Custom o = overrides.get(k);
        return o != null && o.lightStrength() != null ? o.lightStrength() : builtInLightStrength(k);
    }

    /** Distance in grid cells beyond the shape edge that the emitted light reaches. */
    public static double lightRangeCells(String kind) {
        String k = normalize(kind);
        Custom o = overrides.get(k);
        return o != null && o.lightRange() != null ? o.lightRange() : builtInLightRange(k);
    }

    /** Depth (0-1) of the brightness flicker of an emitting effect; 0 = steady light. */
    public static double lightFlicker(String kind) {
        String k = normalize(kind);
        Custom o = overrides.get(k);
        return o != null && o.flicker() != null ? o.flicker() : builtInFlicker(k);
    }

    /** Speed multiplier of the flicker. */
    public static double lightFlickerSpeed(String kind) {
        String k = normalize(kind);
        Custom o = overrides.get(k);
        return o != null && o.flickerSpeed() != null ? o.flickerSpeed() : builtInFlickerSpeed(k);
    }

    private static double builtInFlicker(String k) {
        return switch (k) {
            case FIRE -> 0.3;
            case LIGHTNING -> 0.6;
            case LAVA -> 0.12;
            case ARCANE, PORTAL, RADIATION, FORCE -> 0.1;
            case HOLY -> 0.05;
            default -> 0;
        };
    }

    private static double builtInFlickerSpeed(String k) {
        return switch (k) {
            case FIRE -> 1.6;
            case LIGHTNING -> 4;
            case LAVA -> 0.5;
            default -> 0.8;
        };
    }

    private static boolean builtInEmits(String k) {
        return switch (k) {
            case FIRE, LAVA, LIGHTNING, ARCANE, HOLY, RADIATION, PORTAL, FORCE -> true;
            default -> false;
        };
    }

    private static double builtInLightStrength(String k) {
        return switch (k) {
            case FIRE -> 0.85;
            case LAVA -> 0.7;
            case LIGHTNING, HOLY -> 0.9;
            case ARCANE, PORTAL, FORCE -> 0.6;
            default -> 0.5;
        };
    }

    private static double builtInLightRange(String k) {
        return switch (k) {
            case FIRE, LIGHTNING -> 3;
            case LAVA -> 2.5;
            case HOLY -> 3.5;
            default -> 2;
        };
    }

    public static List<Layer> layers(String kind) {
        String k = normalize(kind);
        Custom o = overrides.get(k);
        return o != null && o.layers() != null ? o.layers() : DEFINITIONS.get(k).layers();
    }

    /** World size of one texture tile, in grid cells. */
    public static double tileCells() {
        return tileCells;
    }

    /** Width of the soft edge of fire, smoke and similar textures, in grid cells. */
    public static double featherCells() {
        return featherCells;
    }

    public static int featherPasses() {
        return PerformanceMode.isEnabled() ? Math.min(featherPasses, PerformanceMode.maxFeatherPasses()) : featherPasses;
    }

    /** Built-in values of every configurable setting, keyed by settings-file key, in file order. */
    public static Map<String, String> settingsDefaults() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("texture.tileCells", "4");
        map.put("texture.featherCells", "0.9");
        map.put("texture.featherPasses", "8");
        for (String kind : KINDS) {
            if (NONE.equals(kind)) {
                continue;
            }
            Definition d = DEFINITIONS.get(kind);
            String p = "texture." + kind + ".";
            map.put(p + "color", d.color());
            map.put(p + "opacity", num(builtInAlpha(kind)));
            map.put(p + "softEdges", String.valueOf(builtInSoft(kind)));
            map.put(p + "emitsLight", String.valueOf(builtInEmits(kind)));
            map.put(p + "lightStrength", num(builtInLightStrength(kind)));
            map.put(p + "lightRange", num(builtInLightRange(kind)));
            map.put(p + "lightFlicker", num(builtInFlicker(kind)));
            map.put(p + "lightFlickerSpeed", num(builtInFlickerSpeed(kind)));
            int n = 1;
            for (Layer l : d.layers()) {
                String lp = p + "layer" + n++ + ".";
                map.put(lp + "speedX", num(l.vx()));
                map.put(lp + "speedY", num(l.vy()));
                map.put(lp + "scale", num(l.scale()));
                map.put(lp + "opacity", num(l.alpha()));
                map.put(lp + "pulseDepth", num(l.pulse()));
                map.put(lp + "pulseHz", num(l.pulseHz()));
            }
        }
        return map;
    }

    private static String num(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e9 ? String.valueOf((long) value) : String.valueOf(value);
    }

    /**
     * Replaces the texture defaults with the values found through {@code lookup} (settings-file key to value).
     * Missing or invalid entries keep their built-in value; a lookup that returns nothing restores all built-ins.
     */
    public static void applySettings(java.util.function.Function<String, String> lookup) {
        featherCells = clamp(parse(lookup.apply("texture.featherCells"), 0.9), 0, 5);
        featherPasses = (int) clamp(Math.round(parse(lookup.apply("texture.featherPasses"), 8)), 1, 24);
        double tile = parse(lookup.apply("texture.tileCells"), 4);
        tileCells = tile >= 0.25 ? Math.min(tile, 64) : 4;

        Map<String, Custom> next = new LinkedHashMap<>();
        for (String kind : KINDS) {
            if (NONE.equals(kind)) {
                continue;
            }
            Definition d = DEFINITIONS.get(kind);
            String p = "texture." + kind + ".";
            String color = validColor(lookup.apply(p + "color"));
            Double alpha = null;
            double a = parse(lookup.apply(p + "opacity"), Double.NaN);
            if (!Double.isNaN(a)) {
                alpha = clamp(a, 0.05, 1.0);
            }
            String softText = lookup.apply(p + "softEdges");
            Boolean soft = softText == null ? null
                    : "true".equalsIgnoreCase(softText.trim()) ? Boolean.TRUE
                    : "false".equalsIgnoreCase(softText.trim()) ? Boolean.FALSE : null;

            List<Layer> layers = new java.util.ArrayList<>();
            int builtIn = d.layers().size();
            for (int i = 1; i <= Math.max(builtIn, 6); i++) {
                String lp = p + "layer" + i + ".";
                Layer base = i <= builtIn ? d.layers().get(i - 1) : null;
                if (base == null && lookup.apply(lp + "scale") == null) {
                    break;
                }
                Layer fallback = base != null ? base : new Layer(0, 0, 1, 0.8);
                layers.add(new Layer(
                        parse(lookup.apply(lp + "speedX"), fallback.vx()),
                        parse(lookup.apply(lp + "speedY"), fallback.vy()),
                        clamp(parse(lookup.apply(lp + "scale"), fallback.scale()), 0.1, 16),
                        clamp(parse(lookup.apply(lp + "opacity"), fallback.alpha()), 0, 1),
                        clamp(parse(lookup.apply(lp + "pulseDepth"), fallback.pulse()), 0, 1),
                        Math.max(0, parse(lookup.apply(lp + "pulseHz"), fallback.pulseHz()))));
            }
            String emitsText = lookup.apply(p + "emitsLight");
            Boolean emits = emitsText == null ? null
                    : "true".equalsIgnoreCase(emitsText.trim()) ? Boolean.TRUE
                    : "false".equalsIgnoreCase(emitsText.trim()) ? Boolean.FALSE : null;
            double strength = parse(lookup.apply(p + "lightStrength"), Double.NaN);
            double range = parse(lookup.apply(p + "lightRange"), Double.NaN);
            double flicker = parse(lookup.apply(p + "lightFlicker"), Double.NaN);
            double flickerSpeed = parse(lookup.apply(p + "lightFlickerSpeed"), Double.NaN);
            next.put(kind, new Custom(color, alpha, soft, layers, emits,
                    Double.isNaN(strength) ? null : clamp(strength, 0, 1),
                    Double.isNaN(range) ? null : clamp(range, 0, 20),
                    Double.isNaN(flicker) ? null : clamp(flicker, 0, 1),
                    Double.isNaN(flickerSpeed) ? null : clamp(flickerSpeed, 0.05, 20)));
        }
        overrides = next;
    }

    private static String validColor(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            Color.web(text.trim());
            return text.trim();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static double parse(String text, double fallback) {
        if (text == null) {
            return fallback;
        }
        try {
            double value = Double.parseDouble(text.trim());
            return Double.isFinite(value) ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
    public static synchronized Image image(String kind, int rgb) {
        String normalized = normalize(kind);
        int color = rgb & 0xFFFFFF;
        return CACHE.computeIfAbsent(normalized + color, k -> {
            WritableImage image = new WritableImage(TILE_SIZE, TILE_SIZE);
            image.getPixelWriter().setPixels(0, 0, TILE_SIZE, TILE_SIZE, PixelFormat.getIntArgbInstance(),
                    generate(normalized, color, TILE_SIZE), 0, TILE_SIZE);
            return image;
        });
    }

    /** Non-premultiplied ARGB pixels of a size x size tile whose opposite edges match. */
    public static int[] generate(String kind, int rgb, int size) {
        int[] pixels = new int[size * size];
        PixelFn fn = DEFINITIONS.get(normalize(kind)).pixel();
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                pixels[y * size + x] = fn.argb(x / (double) size, y / (double) size, rgb);
            }
        }
        return pixels;
    }

    // ---- texture definitions ----

    private static int smoke(double u, double v, int rgb) {
        double n = fbm(u, v, 3, 3, 5, 1);
        double density = smoothstep(0.30, 0.80, n);
        int color = mix(mix(rgb, 0x000000, 0.45), rgb, smoothstep(0.2, 0.9, n));
        return withAlpha(density * 0.95, color);
    }

    private static int fire(double u, double v, int rgb) {
        double n = fbm(u, v, 8, 3, 5, 7);
        // Flames are brighter at the base of each tongue: bias by vertical position within the tile.
        double heat = smoothstep(0.40, 0.80, n * (0.8 + 0.5 * (1 - Math.abs(v * 2 - 1))));
        double a = smoothstep(0.06, 0.4, heat);
        // Cool edges are the colour itself, hot cores shift towards yellow and white.
        int hot = mix(shiftHue(rgb, 30), 0xFFFFFF, 0.35);
        int color = mix(mix(rgb, 0x000000, 0.25), hot, smoothstep(0.55, 1.0, heat));
        return withAlpha(a, color);
    }

    private static int water(double u, double v, int rgb) {
        double n = fbm(u, v, 4, 4, 4, 13);
        double caustic = Math.pow(1 - Math.abs(2 * n - 1), 5);
        double depth = fbm(u, v, 2, 2, 3, 29);
        int base = mix(mix(rgb, 0x000000, 0.35), rgb, depth);
        int color = mix(base, mix(rgb, 0xFFFFFF, 0.75), Math.min(1, caustic * 1.4));
        return withAlpha(0.9 + 0.1 * caustic, color);
    }

    private static int lava(double u, double v, int rgb) {
        double[] w = worley(u, v, 5, 41);
        double crack = 1 - smoothstep(0.0, 0.16, w[1] - w[0]);
        double heat = fbm(u, v, 4, 4, 3, 43);
        int glow = mix(shiftHue(rgb, 12), 0xFFFFFF, 0.25 * crack);
        int crust = mix(mix(rgb, 0x000000, 0.8), rgb, 0.35 * heat);
        return withAlpha(1.0, mix(crust, glow, Math.min(1, crack * (0.8 + 0.5 * heat))));
    }

    private static int acid(double u, double v, int rgb) {
        double n = fbm(u, v, 3, 3, 4, 47);
        double[] w = worley(u, v, 6, 53);
        double bubble = smoothstep(0.32, 0.26, w[0]);
        double rim = bubble * smoothstep(0.18, 0.30, w[0]);
        int base = mix(mix(rgb, 0x000000, 0.45), rgb, n);
        int color = mix(base, mix(rgb, 0xFFFFFF, 0.65), Math.min(1, rim * 1.2 + bubble * 0.15));
        return withAlpha(0.72 + 0.2 * bubble, color);
    }

    private static int ice(double u, double v, int rgb) {
        double[] w = worley(u, v, 4, 59);
        double crack = 1 - smoothstep(0.0, 0.07, w[1] - w[0]);
        double frost = fbm(u, v, 6, 6, 4, 61);
        int base = mix(rgb, 0xFFFFFF, 0.25 + 0.35 * frost);
        int color = mix(base, 0xFFFFFF, crack * 0.85);
        return withAlpha(0.5 + 0.2 * frost + 0.3 * crack, color);
    }

    private static int lightning(double u, double v, int rgb) {
        double n = fbm(u, v, 3, 3, 5, 67);
        double ridge = 1 - Math.abs(2 * n - 1);
        double bolt = Math.pow(ridge, 16);
        double glow = Math.pow(ridge, 4) * 0.28;
        int color = mix(rgb, 0xFFFFFF, Math.min(1, bolt * 1.5));
        return withAlpha(Math.min(1, bolt * 1.6 + glow), color);
    }

    private static int arcane(double u, double v, int rgb) {
        double dx = u - 0.5;
        double dy = v - 0.5;
        double d = Math.sqrt(dx * dx + dy * dy);
        double angle = Math.atan2(dy, dx);
        double ring = Math.max(band(d, 0.44, 0.012), band(d, 0.36, 0.008));
        // Rune ticks between the two rings, 12-fold symmetric so the tile stays seamless.
        double sector = Math.abs(fract(angle / (2 * Math.PI) * 12) - 0.5);
        double ticks = (d > 0.375 && d < 0.435) ? smoothstep(0.42, 0.36, sector) : 0;
        double inner = band(d, 0.18, 0.006) + band(d, 0.10, 0.006);
        double[] w = worley(u, v, 7, 71);
        double sparkle = smoothstep(0.09, 0.0, w[0]);
        double a = Math.min(1, ring + ticks * 0.8 + inner * 0.7 + sparkle * 0.9);
        return withAlpha(a, mix(rgb, 0xFFFFFF, Math.min(1, 0.25 + sparkle * 0.6)));
    }

    private static int darkness(double u, double v, int rgb) {
        double warp = fbm(u, v, 2, 2, 3, 73);
        double n = fbm(fract(u + 0.35 * warp), v, 3, 3, 5, 79);
        double density = smoothstep(0.05, 0.38, n) * 0.97;
        // Wisps range from pure black to the tint colour, so the drifting motion stays visible.
        double shade = smoothstep(0.35, 0.85, fbm(u, fract(v + 0.3 * warp), 4, 4, 4, 137));
        return withAlpha(density, mix(0x000000, rgb, shade));
    }

    private static int mist(double u, double v, int rgb) {
        double n = fbm(u, v, 2, 2, 4, 83);
        return withAlpha(smoothstep(0.3, 0.9, n) * 0.6, mix(rgb, 0xFFFFFF, 0.3 * n));
    }

    private static int blood(double u, double v, int rgb) {
        double n = fbm(u, v, 3, 3, 4, 89);
        double pool = smoothstep(0.12, 0.19, n);
        double edge = smoothstep(0.30, 0.50, n);
        int color = mix(mix(rgb, 0x000000, 0.5), rgb, edge);
        double sheen = smoothstep(0.55, 0.60, n) * smoothstep(0.7, 0.6, n);
        return withAlpha(pool, mix(color, mix(rgb, 0xFFFFFF, 0.5), sheen * 0.5));
    }

    /**
     * Broken earth after a quake: warped cellular plates separated by irregular black cracks of varying width, with
     * shaded plate edges, hairline cracks and grain. Everything is tileable noise, so the tile is seamless.
     */
    private static int chasm(double u, double v, int rgb) {
        double wu = fract(u + 0.06 * (fbm(u, v, 3, 3, 3, 211) - 0.5));
        double wv = fract(v + 0.06 * (fbm(u, v, 3, 3, 3, 223) - 0.5));
        double[] w = worley(wu, wv, 4, 227);
        double gap = w[1] - w[0];
        double width = 0.05 + 0.13 * fbm(u, v, 4, 4, 2, 229);
        if (gap < width * 0.55) {
            double depth = fbm(u, v, 8, 8, 2, 233);
            return withAlpha(1, mix(0x000000, mix(rgb, 0x000000, 0.9), depth * 0.5));
        }
        double plateTone = 0.8 + 0.3 * hash((int) w[4], 5, 239);
        double grain = fbm(u, v, 16, 16, 3, 241);
        int plate = mix(mix(rgb, 0x000000, 0.35), mix(rgb, 0xFFFFFF, 0.15), clamp(plateTone * (0.4 + 0.7 * grain) - 0.2, 0, 1));
        // Shadowed lip where the plate drops into the crack, brighter on the far side of the rim.
        double lip = smoothstep(width * 0.55, width * 1.4, gap);
        int color = mix(mix(rgb, 0x000000, 0.8), plate, lip);
        double[] fine = worley(wu, wv, 10, 251);
        double hairline = smoothstep(0.03, 0.008, fine[1] - fine[0]) * smoothstep(0.35, 0.6, fbm(u, v, 6, 6, 2, 257)) * lip;
        return withAlpha(1, mix(color, 0x000000, hairline * 0.7));
    }

    /**
     * Connected web: the cell edges of a jittered grid form an irregular net that continues across tile borders, and
     * every cell has its own little hub with uneven spokes and sagging rings (some missing). Everything is derived from
     * tileable cellular noise, so the tile is seamless and there are no gaps between neighbouring webs.
     */
    private static int web(double u, double v, int rgb) {
        double[] w = worley(u, v, 3, 149);
        double f1 = w[0];
        double edge = 1 - smoothstep(0.012, 0.045, w[1] - w[0]);

        int cell = (int) w[4];
        double angle = Math.atan2(w[3], w[2]);
        double offset = hash(cell, 1, 151);
        double sector = Math.abs(fract(angle / (2 * Math.PI) * 7 + offset) - 0.5);
        double spoke = smoothstep(0.024, 0.008, f1 * sector * 2 * Math.PI / 7) * smoothstep(0.0, 0.05, f1);

        // Rings at uneven radii; a hashed angular sector decides where a thread is missing.
        double spacing = 0.2 + 0.05 * hash(cell, 2, 157);
        double ringPos = fract(f1 / spacing);
        double dist = Math.min(ringPos, 1 - ringPos) * spacing;
        int segment = (int) Math.floor((angle + Math.PI) / (2 * Math.PI) * 7);
        boolean present = hash(cell * 31 + segment, (int) Math.floor(f1 / spacing), 163) > 0.3;
        double ring = present && f1 < 0.5 ? smoothstep(0.022, 0.008, dist) : 0;

        double a = Math.max(edge, Math.max(spoke, ring));
        return withAlpha(a, rgb);
    }
    private static int holy(double u, double v, int rgb) {
        double[] w = worley(u, v, 6, 97);
        double mote = Math.exp(-Math.pow(w[0] / 0.09, 2));
        double haze = fbm(u, v, 2, 2, 3, 101);
        double a = Math.min(1, mote * 0.95 + haze * 0.22);
        return withAlpha(a, mix(rgb, 0xFFFFFF, Math.min(1, 0.2 + mote * 0.7)));
    }

    private static int grease(double u, double v, int rgb) {
        double n = fbm(u, v, 3, 3, 4, 103);
        double sheen = smoothstep(0.35, 0.75, fbm(u, v, 4, 4, 3, 107));
        int base = mix(rgb, mix(rgb, 0xFFFFFF, 0.12), n);
        Color rainbow = Color.hsb((n * 540) % 360, 0.55, 0.95);
        int rb = (clamp255(rainbow.getRed() * 255) << 16) | (clamp255(rainbow.getGreen() * 255) << 8) | clamp255(rainbow.getBlue() * 255);
        return withAlpha(0.78, mix(base, rb, sheen * 0.55));
    }

    private static int sand(double u, double v, int rgb) {
        double streak = fbm(u, v, 2, 20, 4, 109);
        double density = smoothstep(0.35, 0.8, streak);
        double grain = hash((int) (u * TILE_SIZE), (int) (v * TILE_SIZE), 113);
        double a = density * (0.35 + 0.55 * grain);
        return withAlpha(a * 0.9, mix(rgb, 0xFFFFFF, 0.2 * grain));
    }

    private static int wind(double u, double v, int rgb) {
        double streak = fbm(u, v, 2, 36, 3, 127);
        double a = smoothstep(0.55, 0.85, streak) * 0.7;
        return withAlpha(a, mix(rgb, 0xFFFFFF, 0.3));
    }

    private static int radiation(double u, double v, int rgb) {
        double dx = u - 0.5;
        double dy = v - 0.5;
        double d = Math.sqrt(dx * dx + dy * dy);
        double wave = 0.5 + 0.5 * Math.cos(2 * Math.PI * d * 5);
        double ring = Math.pow(wave, 3);
        double core = smoothstep(0.5, 0.0, d);
        double a = Math.min(1, ring * 0.75 + core * 0.35);
        return withAlpha(a, mix(rgb, 0xFFFFFF, 0.45 * ring));
    }

    private static int poison(double u, double v, int rgb) {
        double warp = fbm(u, v, 2, 2, 3, 171);
        double n = fbm(fract(u + 0.3 * warp), fract(v + 0.25 * warp), 3, 3, 5, 173);
        double density = smoothstep(0.28, 0.75, n) * 0.95;
        double swirl = fbm(fract(u - 0.2 * warp), v, 4, 4, 4, 179);
        int light = mix(shiftHue(rgb, -20), 0xFFFFFF, 0.15);
        int color = mix(mix(rgb, 0x000000, 0.55), light, smoothstep(0.3, 0.85, swirl));
        return withAlpha(density, color);
    }

    private static int swamp(double u, double v, int rgb) {
        double n = fbm(u, v, 3, 3, 5, 181);
        double silt = fbm(u, v, 6, 6, 4, 183);
        int base = mix(mix(rgb, 0x000000, 0.6), rgb, n);
        base = mix(base, mix(rgb, 0xFFFFFF, 0.2), smoothstep(0.55, 0.8, silt) * 0.6);
        double[] w = worley(u, v, 7, 187);
        boolean bubbleCell = hash((int) w[4], 3, 191) > 0.5;
        double rim = bubbleCell ? smoothstep(0.05, 0.0, Math.abs(w[0] - 0.2)) : 0;
        double shine = bubbleCell ? smoothstep(0.08, 0.0, Math.hypot(w[2] + 0.08, w[3] + 0.08)) : 0;
        int color = mix(base, mix(rgb, 0xFFFFFF, 0.6), Math.min(1, rim * 0.7 + shine));
        return withAlpha(0.92, color);
    }

    /**
     * Top-down pile of broken debris: two scatter scales of individually shaped stones (each a random blend
     * between a round pebble and an irregular, pointy-cornered shard) overlap each other in random stacking
     * order, with the higher pieces casting a thin contact shadow onto whatever sits beneath or beside them.
     * Gaps show a dusty gravel floor sprinkled with tiny pebbles.
     */
    private static int rubble(double u, double v, int rgb) {
        double[] fine = worley(u, v, 20, 233);
        boolean speck = hash((int) fine[4], 7, 239) > 0.6;
        double speckle = speck ? smoothstep(0.14, 0.0, fine[0]) : 0;
        double silt = fbm(u, v, 30, 30, 3, 241);
        int ground = mix(mix(rgb, 0x000000, 0.78 - 0.1 * silt), mix(rgb, 0xFFFFFF, 0.32), speckle);

        int[] scaleN = {4, 7};
        int[] scaleSeed = {401, 457};
        double[] scaleRadius = {0.74, 0.46};

        // Each candidate stone found in the 3x3 neighbourhood of both scatter scales: {distance, radius, depth, shade}.
        double[][] cand = new double[18][4];
        int count = 0;

        for (int s = 0; s < scaleN.length; s++) {
            int n = scaleN[s];
            int seed = scaleSeed[s];
            double baseRadius = scaleRadius[s];
            double x = u * n;
            double y = v * n;
            int cx = (int) Math.floor(x);
            int cy = (int) Math.floor(y);
            for (int j = -1; j <= 1; j++) {
                for (int i = -1; i <= 1; i++) {
                    int gx = cx + i;
                    int gy = cy + j;
                    int wx = Math.floorMod(gx, n);
                    int wy = Math.floorMod(gy, n);
                    double px = gx + hash(wx, wy, seed);
                    double py = gy + hash(wx, wy, seed + 17);
                    double dx = x - px;
                    double dy = y - py;
                    double d = Math.hypot(dx, dy);
                    int cellId = (wx * 131 + wy) * 4 + s + seed;

                    // Blend a round pebble silhouette with an irregular polygon shard: each shard vertex gets
                    // its own random length and the radius is interpolated between the two vertices bracketing
                    // the current angle, so corners stick out and edges pull back in, like a broken rock face.
                    double angle = Math.atan2(dy, dx);
                    double roundness = hash(cellId, 51, 601);
                    int facetCount = 5 + (int) (hash(cellId, 52, 601) * 4);
                    double facetAngle = 2 * Math.PI / facetCount;
                    double rot = hash(cellId, 53, 601) * facetCount;
                    double raw = angle / facetAngle + rot;
                    int vertex0 = (int) Math.floor(raw);
                    int vertex1 = vertex0 + 1;
                    double within = raw - vertex0;
                    double r0 = 0.68 + 0.35 * hash(cellId * 17 + vertex0, 70, 601);
                    double r1 = 0.68 + 0.35 * hash(cellId * 17 + vertex1, 70, 601);
                    double angularRadius = baseRadius * lerp(r0, r1, smoothstep(0, 1, within));
                    double bump = 1 + 0.07 * Math.sin(angle * 5 + hash(cellId, 54, 601) * 10);
                    double roundRadius = baseRadius * bump;
                    double radius = roundRadius * roundness + angularRadius * (1 - roundness);
                    double depth = hash(cellId, 55, 601);

                    // Shading: a soft sphere-like highlight for the round part, discrete per-facet brightness
                    // offsets so flat broken faces read as distinct planes, plus a dark contact rim all around
                    // the stone's own silhouette so neighbouring pieces visually separate from one another.
                    double nx = dx / radius;
                    double ny = dy / radius;
                    double heightSq = Math.max(0, 1 - nx * nx - ny * ny);
                    double facing = nx * -0.5 + ny * -0.75 + Math.sqrt(heightSq) * 0.45;
                    double tone = 0.26 + 0.46 * hash(cellId, 56, 601);
                    // Blend the two bracketing vertices' tones the same way the radius is blended, so any
                    // brightness change follows the same smooth corner-to-corner gradient as the silhouette
                    // instead of a hard radial seam (which would read as a cut gem rather than a rock face).
                    double facetTone0 = hash(cellId * 31 + vertex0, 57, 601) - 0.5;
                    double facetTone1 = hash(cellId * 31 + vertex1, 57, 601) - 0.5;
                    double facetTone = lerp(facetTone0, facetTone1, smoothstep(0, 1, within)) * (1 - roundness);
                    double grain = fbm(u, v, 26, 26, 3, 211 + s * 19) - 0.5;
                    double blotch = fbm(u, v, 10, 10, 2, 277 + s * 13) - 0.5;
                    double rimDark = smoothstep(radius * 0.7, radius, d) * 0.4;
                    double shade = clamp(tone + facing * 0.25 + facetTone * 0.25 + grain * 0.22 + blotch * 0.3 - rimDark, 0, 1);

                    if (count < cand.length) {
                        cand[count][0] = d;
                        cand[count][1] = radius;
                        cand[count][2] = depth;
                        cand[count][3] = shade;
                        count++;
                    }
                }
            }
        }

        int winner = -1;
        double winnerDepth = -1;
        for (int k = 0; k < count; k++) {
            if (cand[k][0] < cand[k][1] && cand[k][2] > winnerDepth) {
                winnerDepth = cand[k][2];
                winner = k;
            }
        }

        double ao = 0;
        for (int k = 0; k < count; k++) {
            if (k == winner || cand[k][2] <= winnerDepth) {
                continue;
            }
            double edgeDist = cand[k][0] - cand[k][1];
            if (edgeDist >= 0) {
                ao = Math.max(ao, smoothstep(0.12, 0.0, edgeDist));
            }
        }

        int color = winner >= 0
                ? mix(mix(rgb, 0x000000, 0.5), mix(rgb, 0xFFFFFF, 0.38), cand[winner][3])
                : ground;
        color = mix(color, 0x000000, ao * 0.4);
        return withAlpha(winner >= 0 ? 1.0 : 0.82, color);
    }

    /**
     * Tangled brambles: dark leaf-litter ground dotted with a few domed leaf clumps, overlaid by several
     * looping vine strands. Each strand is shaded like a rounded cable (a lit side, a soft highlight along
     * its spine and a dark contact rim where it lies over whatever is behind it) rather than a flat ribbon,
     * and sprouts short triangular thorns at irregular intervals that are shaded the same directional way so
     * they read as solid barbs. Strands are drawn with a per-crossing depth order so overlapping vines look
     * like they actually weave over and under one another instead of sitting on a single flat plane.
     */
    private static int thorns(double u, double v, int rgb) {
        double litter = fbm(u, v, 14, 14, 3, 701);
        double blotch = fbm(u, v, 34, 34, 2, 702);
        int ground = mix(mix(rgb, 0x000000, 0.74), mix(rgb, 0x000000, 0.5), litter);
        ground = mix(ground, mix(rgb, 0x000000, 0.3), smoothstep(0.62, 0.85, blotch) * 0.4);

        // A handful of small domed leaf clumps sitting on the ground, always covered by the vines above.
        double[] lw = worley(u, v, 9, 311);
        int leafCell = (int) lw[4];
        if (hash(leafCell, 11, 313) > 0.42) {
            double leafRadius = 0.32 + 0.12 * hash(leafCell, 12, 313);
            double rot = hash(leafCell, 13, 313) * Math.PI;
            double cosR = Math.cos(rot);
            double sinR = Math.sin(rot);
            double ox = lw[2] * cosR - lw[3] * sinR;
            double oy = -lw[2] * sinR + lw[3] * cosR;
            double aspect = 0.5 + 0.22 * hash(leafCell, 14, 313);
            double ex = ox / aspect;
            double ey = oy * aspect;
            double ed = Math.hypot(ex, ey);
            if (ed < leafRadius) {
                double nx = ex / leafRadius;
                double ny = ey / leafRadius;
                double dome = Math.sqrt(Math.max(0, 1 - nx * nx - ny * ny));
                double facing = clamp(0.3 + dome * 0.6 - ny * 0.2, 0, 1);
                double vein = smoothstep(0.05, 0.0, Math.abs(oy)) * smoothstep(leafRadius, 0, Math.abs(ox) - leafRadius * 0.1);
                int leafBase = shiftHue(rgb, 18);
                int leafColor = mix(mix(leafBase, 0x000000, 0.45), mix(leafBase, 0xFFFFFF, 0.5), facing);
                leafColor = mix(leafColor, mix(leafBase, 0xFFFFFF, 0.65), vein * 0.5);
                leafColor = mix(leafColor, 0x000000, smoothstep(leafRadius * 0.82, leafRadius, ed) * 0.5);
                ground = leafColor;
            }
        }

        int bestColor = ground;
        boolean covered = false;
        double bestDepth = -1;
        double contactShadow = 0;

        int strandCount = 4;
        for (int s = 0; s < strandCount; s++) {
            int seed = 709 + s * 97;
            double vBase = (s + 0.5) / strandCount + 0.08 * (hash(s, 1, seed) - 0.5);
            double amp1 = 0.05 + 0.05 * hash(s, 2, seed);
            double amp2 = 0.02 + 0.02 * hash(s, 3, seed);
            double k1 = 1 + Math.floor(hash(s, 4, seed) * 2);
            double k2 = 2 + Math.floor(hash(s, 5, seed) * 2);
            double phase1 = hash(s, 6, seed) * 2 * Math.PI;
            double phase2 = hash(s, 7, seed) * 2 * Math.PI;
            double radius = 0.016 + 0.009 * hash(s, 8, seed);

            double angle1 = 2 * Math.PI * k1 * u + phase1;
            double angle2 = 2 * Math.PI * k2 * u + phase2;
            double curve = vBase + amp1 * Math.sin(angle1) + amp2 * Math.sin(angle2);
            double slope = 2 * Math.PI * k1 * amp1 * Math.cos(angle1) + 2 * Math.PI * k2 * amp2 * Math.cos(angle2);

            double dv = v - curve;
            dv -= Math.round(dv);
            double perp = dv / Math.sqrt(1 + slope * slope);
            double norm = perp / radius;
            double absNorm = Math.abs(norm);

            // Thorn slots run along the strand; each slot may sprout a solid triangular barb to one side,
            // its base flush against the vine's own edge and tapering to a sharp point further out. The
            // slot count differs per strand so neighbouring vines don't sprout thorns in metronomic sync.
            int slotScale = 9 + (int) Math.floor(hash(s, 9, seed) * 7);
            double slotPos = u * slotScale + s * 6.1;
            int thornSlot = Math.floorMod((int) Math.floor(slotPos), slotScale);
            double slotFrac = fract(slotPos);
            double thornMask = 0;
            double thornShade = 0;
            if (hash(thornSlot, s, seed + 400) > 0.48) {
                double thornSide = hash(thornSlot, s + 50, seed + 401) > 0.5 ? 1 : -1;
                if (Math.signum(norm) == thornSide) {
                    double thornLenR = 3.2 + 2.6 * hash(thornSlot, s + 60, seed + 402);
                    double baseHalfWidthR = 0.55 + 0.25 * hash(thornSlot, s + 70, seed + 403);
                    double xR = (slotFrac - 0.5) / slotScale / radius;
                    double yR = absNorm - 1;
                    double tipFrac = clamp(yR, 0, thornLenR) / thornLenR;
                    // curved taper (not linear) so the barb stays needle-thin along most of its length
                    // and only flares out right at the base where it fuses into the vine.
                    double widthAt = baseHalfWidthR * Math.pow(1 - tipFrac, 1.6);
                    double lenMask = smoothstep(thornLenR + 0.15, thornLenR - 0.15, yR);
                    double widthMask = smoothstep(-0.1, 0.1, widthAt - Math.abs(xR));
                    thornMask = Math.min(lenMask, widthMask);
                    if (thornMask > 0) {
                        double along = widthAt > 0.001 ? clamp(Math.abs(xR) / widthAt, 0, 1) : 1;
                        thornShade = clamp(0.25 + (1 - along) * 0.35 + (1 - tipFrac) * 0.1 - thornSide * 0.1, 0, 1);
                    }
                }
            }
            boolean onVine = absNorm < 1.0;
            boolean onThorn = thornMask > 0.03;

            if (onVine || onThorn) {
                double height = Math.sqrt(Math.max(0, 1 - Math.min(1, norm * norm)));
                double facing = height * 0.85 - Math.signum(norm) * 0.3;
                double bark = fbm(u * 5 + s * 3, dv * 18, 20, 20, 2, 733 + s) - 0.5;
                double tone = clamp(0.28 + facing * 0.55 + bark * 0.2, 0, 1);
                int color = mix(mix(rgb, 0x000000, 0.55), mix(rgb, 0xFFFFFF, 0.5), tone);
                if (onThorn) {
                    // Thorns read as hardened, slightly browned barbs rather than soft leafy growth.
                    int thornBase = shiftHue(rgb, -22);
                    int thornColor = mix(mix(thornBase, 0x000000, 0.45), mix(thornBase, 0xFFFFFF, 0.42), thornShade);
                    color = onVine ? mix(color, thornColor, thornMask) : thornColor;
                }
                double edgeDark = onVine ? smoothstep(0.7, 1.0, absNorm) * 0.45 : 0;
                if (onThorn) {
                    // dark contact rim along the thorn's own silhouette, so it visually separates from the vine.
                    edgeDark = Math.max(edgeDark, (1 - thornMask) * 0.3);
                }
                color = mix(color, 0x000000, edgeDark);

                int crossCell = Math.floorMod((int) Math.floor(u * 5), 5);
                double depth = s * 3 + hash(crossCell, s, seed + 900) * 2.5 + (onThorn ? 0.5 : 0);
                if (depth > bestDepth) {
                    bestDepth = depth;
                    bestColor = color;
                    covered = true;
                }
            }
            contactShadow = Math.max(contactShadow, smoothstep(1.6, 1.0, absNorm) * 0.3);
        }

        if (!covered) {
            bestColor = mix(bestColor, 0x000000, contactShadow);
        }
        return withAlpha(0.95, bestColor);
    }

    /** Hex grid (period of 3 x 5 hex units so it tiles) with softly flickering cells and bright edges. */
    private static int force(double u, double v, int rgb) {
        double s3 = Math.sqrt(3);
        double x = u * 9 + 0.4;
        double y = v * 5 * s3 + 0.4;
        int ai = (int) Math.round(x / 3);
        int aj = (int) Math.round(y / s3);
        double da = hexDistance(x - 3 * ai, y - s3 * aj);
        int bi = (int) Math.floor(x / 3);
        int bj = (int) Math.floor(y / s3);
        double db = hexDistance(x - (3 * bi + 1.5), y - s3 * (bj + 0.5));
        boolean first = da <= db;
        double hd = first ? da : db;
        int cell = first ? Math.floorMod(ai, 3) * 2 * 5 + Math.floorMod(aj, 5)
                : (Math.floorMod(bi, 3) * 2 + 1) * 5 + Math.floorMod(bj, 5);
        double edge = smoothstep(0.09, 0.0, s3 / 2 - hd);
        double flicker = 0.1 + 0.3 * hash(cell, 7, 217) * fbm(u, v, 3, 3, 3, 219) * 2;
        double a = Math.min(1, edge + flicker);
        return withAlpha(a, mix(rgb, 0xFFFFFF, Math.min(1, 0.15 + edge * 0.6)));
    }

    private static double hexDistance(double dx, double dy) {
        double qx = Math.abs(dx);
        double qy = Math.abs(dy);
        return Math.max(qx * Math.sqrt(3) / 2 + qy * 0.5, qy);
    }

    private static int necrotic(double u, double v, int rgb) {
        double warp = fbm(u, v, 2, 2, 3, 229);
        double n = fbm(fract(u + 0.25 * warp), v, 3, 3, 5, 231);
        double density = smoothstep(0.2, 0.6, n) * 0.95;
        double ridge = 1 - Math.abs(2 * fbm(fract(u + 0.25 * warp), fract(v - 0.2 * warp), 4, 4, 4, 233) - 1);
        double tendril = Math.pow(ridge, 10);
        double[] w = worley(u, v, 5, 239);
        double rim = hash((int) w[4], 4, 241) > 0.5 ? smoothstep(0.08, 0.0, Math.abs(w[0] - 0.2)) : 0;
        double glow = Math.min(1, tendril * 1.4 + rim * 0.7);
        int color = mix(mix(rgb, 0x000000, 0.85), mix(rgb, 0xFFFFFF, 0.4), glow);
        return withAlpha(Math.max(density, glow), color);
    }

    /** Several small spiral vortices (one per noise cell) with dark cores and glowing rims. */
    private static int portal(double u, double v, int rgb) {
        double[] w = worley(u, v, 3, 251);
        double angle = Math.atan2(w[3], w[2]);
        double arms = 0.5 + 0.5 * Math.cos(3 * angle - 14 * w[0]);
        double fall = smoothstep(0.62, 0.2, w[0]);
        double core = smoothstep(0.14, 0.09, w[0]);
        double rim = smoothstep(0.05, 0.0, Math.abs(w[0] - 0.16));
        double haze = fbm(u, v, 3, 3, 3, 253) * 0.25;
        double a = Math.max(Math.pow(arms, 1.5) * fall, Math.max(core, Math.max(rim * 0.9, haze)));
        int color = mix(mix(0x000000, rgb, arms), mix(rgb, 0xFFFFFF, 0.6), rim);
        return withAlpha(a, mix(color, 0x000000, core));
    }
    // ---- noise and colour helpers ----

    /** Anti-aliased thin ring around radius r. */
    private static double band(double d, double r, double halfWidth) {
        return smoothstep(halfWidth * 2, halfWidth * 0.5, Math.abs(d - r));
    }

    private static double fract(double x) {
        return x - Math.floor(x);
    }

    private static double fbm(double u, double v, int periodX, int periodY, int octaves, int seed) {
        double sum = 0;
        double amplitude = 1;
        double total = 0;
        int px = periodX;
        int py = periodY;
        for (int i = 0; i < octaves; i++) {
            sum += amplitude * valueNoise(u * px, v * py, px, py, seed + i * 31);
            total += amplitude;
            amplitude *= 0.5;
            px *= 2;
            py *= 2;
        }
        return sum / total;
    }

    /** Value noise whose lattice wraps at (px, py), so the result tiles when u, v span [0, 1). */
    private static double valueNoise(double x, double y, int px, int py, int seed) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        double fx = fade(x - x0);
        double fy = fade(y - y0);
        int xa = Math.floorMod(x0, px);
        int xb = Math.floorMod(x0 + 1, px);
        int ya = Math.floorMod(y0, py);
        int yb = Math.floorMod(y0 + 1, py);
        double top = lerp(hash(xa, ya, seed), hash(xb, ya, seed), fx);
        double bottom = lerp(hash(xa, yb, seed), hash(xb, yb, seed), fx);
        return lerp(top, bottom, fy);
    }

    /** Tileable cellular noise on an n x n grid; returns {nearest, second nearest} feature distances in cell units. */
    private static double[] worley(double u, double v, int n, int seed) {
        double x = u * n;
        double y = v * n;
        int cx = (int) Math.floor(x);
        int cy = (int) Math.floor(y);
        double f1 = Double.MAX_VALUE;
        double f2 = Double.MAX_VALUE;
        double nearestDx = 0;
        double nearestDy = 0;
        int nearestCell = 0;
        for (int j = -1; j <= 1; j++) {
            for (int i = -1; i <= 1; i++) {
                int gx = cx + i;
                int gy = cy + j;
                int wx = Math.floorMod(gx, n);
                int wy = Math.floorMod(gy, n);
                double px = gx + hash(wx, wy, seed);
                double py = gy + hash(wx, wy, seed + 17);
                double d = Math.hypot(px - x, py - y);
                if (d < f1) {
                    f2 = f1;
                    f1 = d;
                    nearestDx = x - px;
                    nearestDy = y - py;
                    nearestCell = wx * 97 + wy;
                } else if (d < f2) {
                    f2 = d;
                }
            }
        }
        return new double[]{f1, f2, nearestDx, nearestDy, nearestCell};
    }

    private static double hash(int x, int y, int seed) {
        int h = x * 374761393 + y * 668265263 + seed * 1274126177;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / (double) 0x1000000;
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double smoothstep(double edge0, double edge1, double x) {
        double t = Math.max(0, Math.min(1, (x - edge0) / (edge1 - edge0)));
        return t * t * (3 - 2 * t);
    }

    private static int mix(int a, int b, double t) {
        int r = clamp255(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = clamp255(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = clamp255((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return (r << 16) | (g << 8) | bl;
    }

    private static int shiftHue(int rgb, double degrees) {
        Color c = Color.rgb((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
        Color shifted = Color.hsb((c.getHue() + degrees) % 360, c.getSaturation(), c.getBrightness());
        return (clamp255(shifted.getRed() * 255) << 16) | (clamp255(shifted.getGreen() * 255) << 8) | clamp255(shifted.getBlue() * 255);
    }

    private static int withAlpha(double alpha, int rgb) {
        return (clamp255(alpha * 255) << 24) | (rgb & 0xFFFFFF);
    }

    private static int clamp255(double value) {
        return (int) Math.max(0, Math.min(255, Math.round(value)));
    }
}














