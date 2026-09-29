package dmmt.render;

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
    }

    public static final List<String> KINDS = List.copyOf(DEFINITIONS.keySet());

    private static final int MAX_CACHED = 24;
    private static final Map<String, Image> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) {
            return size() > MAX_CACHED;
        }
    };

    private OverlayTextures() {
    }

    /** True for every texture other than flat colour. */
    public static boolean isAnimated(String kind) {
        return !NONE.equals(normalize(kind));
    }

    /** Soft textures (fire, smoke, ...) fade out at the shape edge instead of ending in a hard cut. */
    public static boolean isSoft(String kind) {
        return switch (normalize(kind)) {
            case SMOKE, FIRE, MIST, DARKNESS, HOLY, SAND, WIND, LIGHTNING, RADIATION -> true;
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
        return DEFINITIONS.get(normalize(kind)).color();
    }

    /** Opacity a texture starts with when it is picked; 0 for flat colour (the current opacity is kept). */
    public static double defaultAlpha(String kind) {
        return switch (normalize(kind)) {
            case SMOKE, MIST -> 0.75;
            case FIRE -> 0.90;
            case WATER -> 0.55;
            case LAVA, BLOOD, WEB, DARKNESS -> 1.0;
            case ACID, GREASE, HOLY, ARCANE -> 0.85;
            case LIGHTNING -> 0.9;
            case ICE, SAND, RADIATION -> 0.75;
            case WIND -> 0.65;
            default -> 0;
        };
    }

    public static List<Layer> layers(String kind) {
        return DEFINITIONS.get(normalize(kind)).layers();
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
        double pool = smoothstep(0.34, 0.40, n);
        double edge = smoothstep(0.42, 0.62, n);
        int color = mix(mix(rgb, 0x000000, 0.5), rgb, edge);
        double sheen = smoothstep(0.55, 0.60, n) * smoothstep(0.7, 0.6, n);
        return withAlpha(pool, mix(color, mix(rgb, 0xFFFFFF, 0.5), sheen * 0.5));
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














