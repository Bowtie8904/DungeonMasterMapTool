package dmmt.render;

import dmmt.model.DmProject;
import dmmt.model.FogMask;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Transient undo/redo feedback; deliberately separate from project and shared renderer state. */
public final class HistoryFeedback {
    private static final long DURATION_NANOS = 1_000_000_000L;
    private List<Target> targets = List.of();
    private FogRegion fog;
    private long started;

    public enum Kind { BOUNDS, SEGMENT, POINT }

    public record Target(Kind kind, double x1, double y1, double x2, double y2) {
        public boolean intersects(double left, double top, double right, double bottom) {
            return Math.max(x1, x2) >= left && Math.min(x1, x2) <= right
                    && Math.max(y1, y2) >= top && Math.min(y1, y2) <= bottom;
        }
    }

    private record Item(List<?> state, Target target, boolean visible) { }
    private record FogRegion(double x, double y, double size, int cols, int rows, BitSet cells) { }
    public static final class Snapshot {
        private final Map<String, Item> items = new LinkedHashMap<>();
        private FogRegion fog;
        private int rotation;
    }

    public static Snapshot capture(DmProject project, boolean wallsVisible) {
        Snapshot snapshot = new Snapshot();
        snapshot.rotation = project.getMap().getRotationQuarterTurns();
        for (var layer : project.getImageLayers()) {
            put(snapshot, "image:" + layer.getId(), layer.isVisible(),
                    bounds(layer.getX(), layer.getY(), layer.getWidth(), layer.getHeight()),
                    layer.getPath(), layer.getX(), layer.getY(), layer.getWidth(), layer.getHeight(),
                    layer.getRotationDeg(), layer.getZIndex(), layer.isVisible());
        }
        for (var light : project.getLighting().getLights()) {
            var flicker = light.getFlicker();
            put(snapshot, "light:" + light.getId(), true,
                    new Target(Kind.POINT, light.getX(), light.getY(), light.getX(), light.getY()),
                    light.getX(), light.getY(), light.getRange(), light.getColor(), light.getIntensity(),
                    light.isEnabled(), light.isCastsShadows(), light.getRevealMode(),
                    flicker == null ? null : Arrays.asList(flicker.isEnabled(), flicker.getStrength(), flicker.getSpeed()));
        }
        for (var shape : project.getOverlays()) {
            put(snapshot, "effect:" + shape.getId(), true, overlayBounds(shape),
                    shape.getType(), shape.getX(), shape.getY(), shape.getWidth(), shape.getHeight(),
                    shape.getRadius(), shape.getStrokeWidth(), List.copyOf(shape.getPoints()), shape.getColor(),
                    shape.getAlpha(), shape.isPlayerVisible(), shape.getTexture(), shape.isBorder(), shape.isEmitsLight());
        }
        for (var box : project.getTextBoxes()) {
            double[] b = TextBoxGeometry.bounds(box, 0); // DM text is never rotated with player text.
            put(snapshot, "text:" + box.getId(), project.isTextLayerVisible(),
                    new Target(Kind.BOUNDS, b[0], b[1], b[2], b[3]),
                    box.getX(), box.getY(), box.getWidth(), box.getHeight(), box.getBackgroundColor(),
                    box.getBorderColor(), box.isAutoSize(), box.isPlayerVisible(),
                    box.getRuns().stream().map(run -> Arrays.asList(run.getText(), run.getFontSize(), run.getColor())).toList());
        }
        for (var portal : project.getInteractables()) {
            put(snapshot, "portal:" + portal.getId(), wallsVisible,
                    new Target(Kind.SEGMENT, portal.getX1(), portal.getY1(), portal.getX2(), portal.getY2()),
                    portal.getType(), portal.getState(), portal.isBlocksSightWhenClosed(),
                    portal.getX1(), portal.getY1(), portal.getX2(), portal.getY2());
        }
        for (var wall : project.getWalls()) {
            // Walls have no ids; equal geometry occupies the same feedback location.
            String key = "wall:" + wall.getX1() + ":" + wall.getY1() + ":" + wall.getX2() + ":" + wall.getY2();
            put(snapshot, key, wallsVisible,
                    new Target(Kind.SEGMENT, wall.getX1(), wall.getY1(), wall.getX2(), wall.getY2()), key);
        }
        FogMask mask = project.getFog().getMask();
        if (project.getFog().isEnabled() && mask != null) {
            snapshot.fog = new FogRegion(mask.getOriginX(), mask.getOriginY(), mask.getCellSize(),
                    mask.getCols(), mask.getRows(), mask.copyBits());
        }
        return snapshot;
    }

    private static void put(Snapshot snapshot, String key, boolean visible, Target target, Object... state) {
        snapshot.items.put(key, new Item(Arrays.asList(state), target, visible));
    }

    private static Target bounds(double x, double y, double width, double height) {
        return new Target(Kind.BOUNDS, x, y, x + width, y + height);
    }

    /** Shared world footprint for effect feedback and group selection. */
    public static Target overlayBounds(DmProject.OverlayShape shape) {
        if ("circle".equals(shape.getType())) {
            double r = shape.getRadius();
            return bounds(shape.getX() - r, shape.getY() - r, r * 2, r * 2);
        }
        if ("rect".equals(shape.getType())) {
            return bounds(shape.getX(), shape.getY(), shape.getWidth(), shape.getHeight());
        }
        List<Double> points = shape.getPoints();
        if (points.size() < 2) {
            return null;
        }
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (int i = 0; i + 1 < points.size(); i += 2) {
            minX = Math.min(minX, points.get(i));
            minY = Math.min(minY, points.get(i + 1));
            maxX = Math.max(maxX, points.get(i));
            maxY = Math.max(maxY, points.get(i + 1));
        }
        double pad = shape.getStrokeWidth() / 2;
        return new Target(Kind.BOUNDS, minX - pad, minY - pad, maxX + pad, maxY + pad);
    }

    public void show(Snapshot before, Snapshot after, long now) {
        clear();
        if (before.rotation != after.rotation) {
            return;
        }
        List<Target> changed = new ArrayList<>();
        Map<String, Item> all = new LinkedHashMap<>(before.items);
        all.putAll(after.items);
        boolean objectChanged = false;
        for (String key : all.keySet()) {
            Item old = before.items.get(key), next = after.items.get(key);
            if (Objects.equals(old, next)) {
                continue;
            }
            objectChanged = true;
            Item affected = next == null ? old : next;
            if (affected.visible && affected.target != null) {
                changed.add(affected.target);
            }
        }
        targets = List.copyOf(changed);
        // Object actions may also restore persistent fog; emphasize their actual local target.
        if (!objectChanged && before.fog != null && after.fog != null) {
            FogRegion a = before.fog, b = after.fog;
            if (a.x == b.x && a.y == b.y && a.size == b.size && a.cols == b.cols && a.rows == b.rows) {
                BitSet cells = (BitSet) a.cells.clone();
                cells.xor(b.cells);
                if (!cells.isEmpty()) {
                    fog = new FogRegion(b.x, b.y, b.size, b.cols, b.rows, cells);
                }
            }
        }
        started = now;
    }

    public List<Target> targets() {
        return targets;
    }

    public boolean fogCellAffected(int index) {
        return fog != null && fog.cells.get(index);
    }

    public boolean active(long now) {
        if (now - started >= DURATION_NANOS) {
            clear();
        }
        return !targets.isEmpty() || fog != null;
    }

    public void clear() {
        targets = List.of();
        fog = null;
    }

    public void draw(GraphicsContext gc, double width, double height, DmProject.CameraState camera, long now) {
        if (!active(now)) {
            return;
        }
        double zoom = camera.getZoom();
        double left = camera.getX() - width / (2 * zoom), top = camera.getY() - height / (2 * zoom);
        double right = left + width / zoom, bottom = top + height / zoom;
        gc.save();
        gc.beginPath();
        gc.rect(0, 0, width, height);
        gc.clip();
        gc.setGlobalAlpha(0.9 * (1 - (double) (now - started) / DURATION_NANOS));
        gc.setStroke(Color.CYAN);
        gc.setLineWidth(3);
        gc.setLineDashes();
        for (Target target : targets) {
            double margin = target.kind == Kind.POINT ? 13 / zoom : 0;
            if (!target.intersects(left - margin, top - margin, right + margin, bottom + margin)) {
                continue;
            }
            double x = (target.x1 - left) * zoom, y = (target.y1 - top) * zoom;
            switch (target.kind) {
                case POINT -> gc.strokeOval(x - 13, y - 13, 26, 26);
                case SEGMENT -> gc.strokeLine(x, y, (target.x2 - left) * zoom, (target.y2 - top) * zoom);
                case BOUNDS -> gc.strokeRect(x, y, (target.x2 - target.x1) * zoom, (target.y2 - target.y1) * zoom);
            }
        }
        if (fog != null) {
            drawFog(gc, left, top, right, bottom, zoom);
        }
        gc.restore();
    }

    private void drawFog(GraphicsContext gc, double left, double top, double right, double bottom, double zoom) {
        int firstRow = Math.max(0, (int) Math.floor((top - fog.y) / fog.size));
        int lastRow = Math.min(fog.rows - 1, (int) Math.floor((bottom - fog.y) / fog.size));
        int firstCol = Math.max(0, (int) Math.floor((left - fog.x) / fog.size));
        int lastCol = Math.min(fog.cols - 1, (int) Math.floor((right - fog.x) / fog.size));
        if (firstCol > lastCol || firstRow > lastRow) {
            return;
        }
        double size = fog.size * zoom;
        gc.beginPath();
        for (int row = firstRow; row <= lastRow; row++) {
            int end = row * fog.cols + lastCol;
            for (int i = fog.cells.nextSetBit(row * fog.cols + firstCol); i >= 0 && i <= end;
                 i = fog.cells.nextSetBit(i + 1)) {
                int col = i % fog.cols;
                double x = (fog.x + col * fog.size - left) * zoom;
                double y = (fog.y + row * fog.size - top) * zoom;
                if (row == 0 || !fog.cells.get(i - fog.cols)) edge(gc, x, y, x + size, y);
                if (row == fog.rows - 1 || !fog.cells.get(i + fog.cols)) edge(gc, x, y + size, x + size, y + size);
                if (col == 0 || !fog.cells.get(i - 1)) edge(gc, x, y, x, y + size);
                if (col == fog.cols - 1 || !fog.cells.get(i + 1)) edge(gc, x + size, y, x + size, y + size);
            }
        }
        gc.stroke();
    }

    private static void edge(GraphicsContext gc, double x1, double y1, double x2, double y2) {
        gc.moveTo(x1, y1);
        gc.lineTo(x2, y2);
    }
}
