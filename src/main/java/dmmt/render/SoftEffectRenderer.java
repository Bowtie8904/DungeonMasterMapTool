package dmmt.render;

import dmmt.model.DmProject;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.stream.IntStream;

/**
 * Draws soft-edged textured effects (fire, smoke, ...) by compositing on the CPU instead of stacking many stroked
 * pattern fills: a soft-edge alpha mask per shape is computed once in world space and cached, and every frame the
 * scrolling texture layers are sampled, multiplied by the mask and written to one image that is drawn in a single call.
 */
final class SoftEffectRenderer {
    private static final int MAX_MASK_PIXELS = 400_000;
    private static final int TILE = OverlayTextures.TILE_SIZE;

    private record Mask(long key, float[] alpha, int w, int h, double originX, double originY, double cell) {
    }

    private static final class Output {
        private WritableImage image;
        private int[] pixels = new int[0];
        private int capW;
        private int capH;
        private long key;
        private int w;
        private int h;

        void ensure(int w, int h) {
            if (image != null && w <= capW && h <= capH) {
                return;
            }
            // Headroom so zooming does not reallocate the image every frame.
            capW = Math.max(w, (int) Math.ceil(w * 1.25 / 64.0) * 64);
            capH = Math.max(h, (int) Math.ceil(h * 1.25 / 64.0) * 64);
            image = new WritableImage(capW, capH);
            pixels = new int[capW * capH];
        }
    }

    private final Map<String, Mask> masks = new HashMap<>();
    private final Map<String, Output> dmOutputs = new HashMap<>();
    private final Map<String, Output> playerOutputs = new HashMap<>();
    private final Map<Image, int[]> texels = new WeakHashMap<>();

    /** Drops cached masks and images of shapes that no longer exist. */
    void retain(Collection<String> ids) {
        masks.keySet().retainAll(ids);
        dmOutputs.keySet().retainAll(ids);
        playerOutputs.keySet().retainAll(ids);
    }

    /**
     * Draws one soft textured shape. {@code featherWorld} and {@code passes} have the same meaning as for the inset
     * passes of the vector path; the mask is their continuous equivalent. {@code maxScale} caps the image resolution
     * relative to the screen. The image is only recomputed when the animation time, the view or the shape changed.
     */
    void draw(GraphicsContext gc, DmProject.OverlayShape shape, String type, Image tile, List<OverlayTextures.Layer> layers,
              double alpha, double seconds, double tileWorld, double featherWorld, int passes, boolean playerMode,
              double maxScale, double canvasW, double canvasH, DmProject.CameraState camera) {
        if (shape.getId() == null || layers.isEmpty()) {
            return;
        }
        Mask mask = mask(shape, type, featherWorld, passes);
        if (mask == null) {
            return;
        }
        double zoom = camera.getZoom();
        double camX = camera.getX();
        double camY = camera.getY();
        double halfW = canvasW / 2.0;
        double halfH = canvasH / 2.0;
        double sx0 = Math.max(0, (mask.originX() - camX) * zoom + halfW);
        double sy0 = Math.max(0, (mask.originY() - camY) * zoom + halfH);
        double sx1 = Math.min(canvasW, (mask.originX() + mask.w() * mask.cell() - camX) * zoom + halfW);
        double sy1 = Math.min(canvasH, (mask.originY() + mask.h() * mask.cell() - camY) * zoom + halfH);
        if (sx1 <= sx0 || sy1 <= sy0) {
            return;
        }
        int layerCount = layers.size();
        double[] tileScreen = new double[layerCount];
        double[] layerOx = new double[layerCount];
        double[] layerOy = new double[layerCount];
        double[] layerAlpha = new double[layerCount];
        double originX = (0 - camX) * zoom + halfW;
        double originY = (0 - camY) * zoom + halfH;
        double density = 0;
        for (int l = 0; l < layerCount; l++) {
            OverlayTextures.Layer layer = layers.get(l);
            tileScreen[l] = Math.max(8, tileWorld * layer.scale() * zoom);
            double phaseX = ((seconds * layer.vx()) % 1.0 + 1.0) % 1.0;
            double phaseY = ((seconds * layer.vy()) % 1.0 + 1.0) % 1.0;
            layerOx[l] = originX + phaseX * tileScreen[l];
            layerOy[l] = originY + phaseY * tileScreen[l];
            layerAlpha[l] = alpha * layer.alpha() * pulseFactor(layer, seconds);
            density = Math.max(density, TILE / tileScreen[l]);
        }
        // Texels are magnified when zoomed in, so the image never needs more pixels than the texture has.
        double scale = Math.max(0.05, Math.min(Math.min(1.0, maxScale), density));
        int ow = Math.max(1, (int) Math.ceil((sx1 - sx0) * scale));
        int oh = Math.max(1, (int) Math.ceil((sy1 - sy0) * scale));
        double inv = 1.0 / scale;
        Output out = (playerMode ? playerOutputs : dmOutputs).computeIfAbsent(shape.getId(), k -> new Output());
        int[] tex = texels(tile);

        long key = mask.key();
        key = 31 * key + Double.hashCode(seconds);
        key = 31 * key + Double.hashCode(zoom);
        key = 31 * key + Double.hashCode(camX);
        key = 31 * key + Double.hashCode(camY);
        key = 31 * key + Double.hashCode(canvasW);
        key = 31 * key + Double.hashCode(canvasH);
        key = 31 * key + Double.hashCode(alpha);
        key = 31 * key + Double.hashCode(scale);
        key = 31 * key + Double.hashCode(tileWorld);
        key = 31 * key + System.identityHashCode(tex);
        key = 31 * key + layers.hashCode();
        if (out.image == null || out.key != key || out.w != ow || out.h != oh) {
            out.ensure(ow, oh);
            out.key = key;
            out.w = ow;
            out.h = oh;
            composite(out.pixels, ow, oh, sx0, sy0, inv, mask, tex, layerCount, tileScreen, layerOx, layerOy, layerAlpha,
                    zoom, camX, camY, halfW, halfH);
            out.image.getPixelWriter().setPixels(0, 0, ow, oh, PixelFormat.getIntArgbPreInstance(), out.pixels, 0, ow);
        }
        gc.save();
        gc.setGlobalAlpha(1);
        gc.setImageSmoothing(true);
        gc.drawImage(out.image, 0, 0, ow, oh, sx0, sy0, ow * inv, oh * inv);
        gc.restore();
    }

    private static void composite(int[] pixels, int ow, int oh, double sx0, double sy0, double inv, Mask mask, int[] tex,
                                  int layerCount, double[] tileScreen, double[] layerOx, double[] layerOy,
                                  double[] layerAlpha, double zoom, double camX, double camY, double halfW, double halfH) {
        float[] m = mask.alpha();
        int mw = mask.w();
        int mh = mask.h();
        double cell = mask.cell();
        // Mask and texture coordinates are linear in the output column, so each row only adds a step per pixel.
        double mx0 = ((sx0 + 0.5 * inv - halfW) / zoom + camX - mask.originX()) / cell - 0.5;
        double mdx = inv / zoom / cell;
        double[] u0 = new double[layerCount];
        double[] du = new double[layerCount];
        for (int l = 0; l < layerCount; l++) {
            u0[l] = (sx0 + 0.5 * inv - layerOx[l]) / tileScreen[l] * TILE - 0.5;
            du[l] = inv / tileScreen[l] * TILE;
        }
        IntStream.range(0, oh).parallel().forEach(j -> {
            double sy = sy0 + (j + 0.5) * inv;
            double my = ((sy - halfH) / zoom + camY - mask.originY()) / cell - 0.5;
            int row = j * ow;
            int my0 = (int) Math.floor(my);
            double fmy = my - my0;
            if (my0 < -1 || my0 >= mh) {
                java.util.Arrays.fill(pixels, row, row + ow, 0);
                return;
            }
            int[] ty0 = new int[layerCount];
            int[] ty1 = new int[layerCount];
            int[] wy = new int[layerCount];
            for (int l = 0; l < layerCount; l++) {
                double v = (sy - layerOy[l]) / tileScreen[l] * TILE - 0.5;
                int y0 = (int) Math.floor(v);
                wy[l] = (int) ((v - y0) * 256);
                y0 = Math.floorMod(y0, TILE);
                ty0[l] = y0 * TILE;
                ty1[l] = (y0 + 1 == TILE ? 0 : y0 + 1) * TILE;
            }
            for (int i = 0; i < ow; i++) {
                double mx = mx0 + i * mdx;
                int mxi = (int) Math.floor(mx);
                double a;
                if (mxi < -1 || mxi >= mw) {
                    a = 0;
                } else {
                    double fmx = mx - mxi;
                    double a00 = maskAt(m, mw, mh, mxi, my0);
                    double a10 = maskAt(m, mw, mh, mxi + 1, my0);
                    double a01 = maskAt(m, mw, mh, mxi, my0 + 1);
                    double a11 = maskAt(m, mw, mh, mxi + 1, my0 + 1);
                    a = (a00 + (a10 - a00) * fmx) * (1 - fmy) + (a01 + (a11 - a01) * fmx) * fmy;
                }
                if (a <= 0.002) {
                    pixels[row + i] = 0;
                    continue;
                }
                int outA = 0;
                int outR = 0;
                int outG = 0;
                int outB = 0;
                for (int l = 0; l < layerCount; l++) {
                    int la = (int) (Math.min(0.999, layerAlpha[l] * a) * 256);
                    if (la <= 0) {
                        continue;
                    }
                    double u = u0[l] + i * du[l];
                    int x0 = (int) Math.floor(u);
                    int wx = (int) ((u - x0) * 256);
                    x0 = Math.floorMod(x0, TILE);
                    int x1 = x0 + 1 == TILE ? 0 : x0 + 1;
                    int c00 = tex[ty0[l] + x0];
                    int c10 = tex[ty0[l] + x1];
                    int c01 = tex[ty1[l] + x0];
                    int c11 = tex[ty1[l] + x1];
                    int wyl = wy[l];
                    int w11 = wx * wyl;
                    int w10 = (wx << 8) - w11;
                    int w01 = (wyl << 8) - w11;
                    int w00 = 65536 - w10 - w01 - w11;
                    // Bilinear sample in 16.16 fixed point, then scaled by the layer alpha (x/256).
                    int sa = (int) (((long) (w00 * (c00 >>> 24) + w10 * (c10 >>> 24) + w01 * (c01 >>> 24) + w11 * (c11 >>> 24)) * la) >>> 24);
                    int sr = (int) (((long) (w00 * ((c00 >> 16) & 0xFF) + w10 * ((c10 >> 16) & 0xFF) + w01 * ((c01 >> 16) & 0xFF) + w11 * ((c11 >> 16) & 0xFF)) * la) >>> 24);
                    int sg = (int) (((long) (w00 * ((c00 >> 8) & 0xFF) + w10 * ((c10 >> 8) & 0xFF) + w01 * ((c01 >> 8) & 0xFF) + w11 * ((c11 >> 8) & 0xFF)) * la) >>> 24);
                    int sb = (int) (((long) (w00 * (c00 & 0xFF) + w10 * (c10 & 0xFF) + w01 * (c01 & 0xFF) + w11 * (c11 & 0xFF)) * la) >>> 24);
                    int keep = 255 - sa;
                    outA = sa + (outA * keep + 127) / 255;
                    outR = sr + (outR * keep + 127) / 255;
                    outG = sg + (outG * keep + 127) / 255;
                    outB = sb + (outB * keep + 127) / 255;
                }
                int ia = Math.min(255, outA);
                pixels[row + i] = (ia << 24) | (Math.min(ia, outR) << 16) | (Math.min(ia, outG) << 8) | Math.min(ia, outB);
            }
        });
    }
    private static double maskAt(float[] m, int w, int h, int x, int y) {
        return x < 0 || y < 0 || x >= w || y >= h ? 0 : m[y * w + x];
    }

    private int[] texels(Image tile) {
        return texels.computeIfAbsent(tile, img -> {
            int[] data = new int[TILE * TILE];
            img.getPixelReader().getPixels(0, 0, TILE, TILE, PixelFormat.getIntArgbPreInstance(), data, 0, TILE);
            return data;
        });
    }

    private static double pulseFactor(OverlayTextures.Layer layer, double seconds) {
        if (layer.pulse() <= 0) {
            return 1;
        }
        double wave = 0.5 - 0.5 * Math.cos(2 * Math.PI * layer.pulseHz() * seconds);
        return 1 - layer.pulse() * wave;
    }

    private Mask mask(DmProject.OverlayShape shape, String type, double featherWorld, int passes) {
        long key = Objects.hash(type, shape.getX(), shape.getY(), shape.getWidth(), shape.getHeight(), shape.getRadius(),
                shape.getStrokeWidth(), featherWorld, passes);
        key = 31 * key + shape.getPoints().hashCode();
        Mask cached = masks.get(shape.getId());
        if (cached != null && cached.key() == key) {
            return cached;
        }
        Mask fresh = buildMask(shape, type, featherWorld, passes, key);
        if (fresh == null) {
            masks.remove(shape.getId());
        } else {
            masks.put(shape.getId(), fresh);
        }
        return fresh;
    }

    private static Mask buildMask(DmProject.OverlayShape shape, String type, double featherWorld, int passes, long key) {
        double minX;
        double minY;
        double maxX;
        double maxY;
        double half = 0;
        List<Double> points = shape.getPoints();
        switch (type) {
            case "circle" -> {
                double r = shape.getRadius();
                minX = shape.getX() - r;
                maxX = shape.getX() + r;
                minY = shape.getY() - r;
                maxY = shape.getY() + r;
            }
            case "rect" -> {
                minX = shape.getX();
                minY = shape.getY();
                maxX = minX + shape.getWidth();
                maxY = minY + shape.getHeight();
            }
            case "brush" -> {
                if (points.size() < 2) {
                    return null;
                }
                half = shape.getStrokeWidth() / 2.0;
                minX = Double.MAX_VALUE;
                minY = Double.MAX_VALUE;
                maxX = -Double.MAX_VALUE;
                maxY = -Double.MAX_VALUE;
                for (int i = 0; i + 1 < points.size(); i += 2) {
                    minX = Math.min(minX, points.get(i));
                    maxX = Math.max(maxX, points.get(i));
                    minY = Math.min(minY, points.get(i + 1));
                    maxY = Math.max(maxY, points.get(i + 1));
                }
                minX -= half;
                minY -= half;
                maxX += half;
                maxY += half;
            }
            default -> {
                return null;
            }
        }
        double spanX = maxX - minX;
        double spanY = maxY - minY;
        if (!(spanX > 0) || !(spanY > 0)) {
            return null;
        }
        // Four mask cells across the feather keep the fade smooth after bilinear sampling.
        double cell = featherWorld > 0 ? featherWorld / 4.0 : Math.max(spanX, spanY) / 256.0;
        cell = Math.max(cell, Math.sqrt(spanX * spanY / MAX_MASK_PIXELS));
        // One empty cell around the shape so sampling fades to zero at the outer edge.
        double originX = minX - cell;
        double originY = minY - cell;
        int w = (int) Math.ceil(spanX / cell) + 2;
        int h = (int) Math.ceil(spanY / cell) + 2;
        float[] depth = new float[w * h];
        java.util.Arrays.fill(depth, Float.NEGATIVE_INFINITY);
        switch (type) {
            case "circle" -> {
                double cx = shape.getX();
                double cy = shape.getY();
                double r = shape.getRadius();
                for (int y = 0; y < h; y++) {
                    double wy = originY + (y + 0.5) * cell - cy;
                    for (int x = 0; x < w; x++) {
                        double wx = originX + (x + 0.5) * cell - cx;
                        depth[y * w + x] = (float) (r - Math.sqrt(wx * wx + wy * wy));
                    }
                }
            }
            case "rect" -> {
                for (int y = 0; y < h; y++) {
                    double wy = originY + (y + 0.5) * cell;
                    double dy = Math.min(wy - minY, maxY - wy);
                    for (int x = 0; x < w; x++) {
                        double wx = originX + (x + 0.5) * cell;
                        depth[y * w + x] = (float) Math.min(dy, Math.min(wx - minX, maxX - wx));
                    }
                }
            }
            default -> {
                // Stamp every segment into the cells within half the stroke width of it.
                int n = points.size() / 2;
                for (int s = 0; s < Math.max(1, n - 1); s++) {
                    double ax = points.get(2 * s);
                    double ay = points.get(2 * s + 1);
                    double bx = n > 1 ? points.get(2 * s + 2) : ax;
                    double by = n > 1 ? points.get(2 * s + 3) : ay;
                    int cx0 = Math.max(0, (int) Math.floor((Math.min(ax, bx) - half - originX) / cell));
                    int cx1 = Math.min(w - 1, (int) Math.ceil((Math.max(ax, bx) + half - originX) / cell));
                    int cy0 = Math.max(0, (int) Math.floor((Math.min(ay, by) - half - originY) / cell));
                    int cy1 = Math.min(h - 1, (int) Math.ceil((Math.max(ay, by) + half - originY) / cell));
                    double dx = bx - ax;
                    double dy = by - ay;
                    double len2 = dx * dx + dy * dy;
                    for (int y = cy0; y <= cy1; y++) {
                        double py = originY + (y + 0.5) * cell;
                        for (int x = cx0; x <= cx1; x++) {
                            double px = originX + (x + 0.5) * cell;
                            double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2));
                            double ex = px - (ax + t * dx);
                            double ey = py - (ay + t * dy);
                            float d = (float) (half - Math.sqrt(ex * ex + ey * ey));
                            int idx = y * w + x;
                            if (d > depth[idx]) {
                                depth[idx] = d;
                            }
                        }
                    }
                }
            }
        }
        float[] alpha = new float[w * h];
        double first = 1.0 / Math.max(1, passes);
        for (int i = 0; i < alpha.length; i++) {
            double d = depth[i];
            if (!(d >= 0)) {
                continue;
            }
            double t = featherWorld > 0 ? Math.min(1, d / featherWorld + first) : 1;
            alpha[i] = (float) (t * t * (3 - 2 * t));
        }
        return new Mask(key, alpha, w, h, originX, originY, cell);
    }
}
