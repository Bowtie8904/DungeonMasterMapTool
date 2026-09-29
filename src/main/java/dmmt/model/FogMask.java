package dmmt.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import dmmt.lighting.PolygonRaster;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.BitSet;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Grid-based fog-of-war reveal mask in world coordinates.
 * A set bit means the cell is revealed. Cells are tested by their center point.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FogMask {
    private double originX;
    private double originY;
    private double cellSize = 15;
    private int cols;
    private int rows;
    private BitSet revealed = new BitSet();
    private long version;

    public FogMask() {
    }

    public FogMask(double originX, double originY, double cellSize, int cols, int rows) {
        this.originX = originX;
        this.originY = originY;
        this.cellSize = cellSize;
        this.cols = Math.max(1, cols);
        this.rows = Math.max(1, rows);
    }

    public double getOriginX() {
        return originX;
    }

    public void setOriginX(double originX) {
        this.originX = originX;
    }

    public double getOriginY() {
        return originY;
    }

    public void setOriginY(double originY) {
        this.originY = originY;
    }

    public double getCellSize() {
        return cellSize;
    }

    public void setCellSize(double cellSize) {
        this.cellSize = cellSize;
    }

    public int getCols() {
        return cols;
    }

    public void setCols(int cols) {
        this.cols = cols;
    }

    public int getRows() {
        return rows;
    }

    public void setRows(int rows) {
        this.rows = rows;
    }

    @JsonProperty("revealed")
    public String getRevealedEncoded() {
        byte[] raw = revealed.toByteArray();
        if (raw.length == 0) {
            return "";
        }
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(raw);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    @JsonProperty("revealed")
    public void setRevealedEncoded(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            revealed = new BitSet();
            version++;
            return;
        }
        byte[] compressed = Base64.getDecoder().decode(encoded);
        Inflater inflater = new Inflater();
        inflater.setInput(compressed);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                out.write(buffer, 0, n);
            }
        } catch (DataFormatException ex) {
            throw new IllegalArgumentException("Corrupt fog mask data", ex);
        } finally {
            inflater.end();
        }
        revealed = BitSet.valueOf(out.toByteArray());
        version++;
    }

    /** Increments whenever the mask content or geometry changes; used for render caching. */
    @JsonIgnore
    public long getVersion() {
        return version;
    }

    @JsonIgnore
    public double getWidth() {
        return cols * cellSize;
    }

    @JsonIgnore
    public double getHeight() {
        return rows * cellSize;
    }

    public boolean isRevealedCell(int index) {
        return revealed.get(index);
    }

    public BitSet copyBits() {
        return (BitSet) revealed.clone();
    }

    public void applyCircle(double centerX, double centerY, double radius, boolean reveal) {
        if (radius <= 0) {
            return;
        }
        int rowStart = rowFloor(centerY - radius);
        int rowEnd = rowFloor(centerY + radius);
        for (int row = Math.max(0, rowStart); row <= Math.min(rows - 1, rowEnd); row++) {
            double dy = rowCenterY(row) - centerY;
            double halfSq = radius * radius - dy * dy;
            if (halfSq < 0) {
                continue;
            }
            double half = Math.sqrt(halfSq);
            setSpan(row, centerX - half, centerX + half, reveal);
        }
        version++;
    }

    public void applyRect(double x, double y, double width, double height, boolean reveal) {
        double minX = Math.min(x, x + width);
        double maxX = Math.max(x, x + width);
        double minY = Math.min(y, y + height);
        double maxY = Math.max(y, y + height);
        for (int row = Math.max(0, rowFloor(minY)); row <= Math.min(rows - 1, rowFloor(maxY)); row++) {
            double cy = rowCenterY(row);
            if (cy < minY || cy > maxY) {
                continue;
            }
            setSpan(row, minX, maxX, reveal);
        }
        version++;
    }

    public void applyPolygon(double[] xs, double[] ys, boolean reveal) {
        applyCells(cellsInPolygon(xs, ys), reveal);
    }

    public void applyCells(int[] cells, boolean reveal) {
        if (cells.length == 0) {
            return;
        }
        for (int cell : cells) {
            revealed.set(cell, reveal);
        }
        version++;
    }

    /** Returns indices (row * cols + col) of all cells whose center lies inside the polygon. */
    public int[] cellsInPolygon(double[] xs, double[] ys) {
        int n = xs.length;
        if (n < 3) {
            return new int[0];
        }
        double[] cx = new double[n];
        double[] cy = new double[n];
        for (int i = 0; i < n; i++) {
            cx[i] = (xs[i] - originX) / cellSize;
            cy[i] = (ys[i] - originY) / cellSize;
        }
        IntList result = new IntList();
        PolygonRaster.fill(cx, cy, cols, rows, (row, colStart, colEnd) -> {
            for (int col = colStart; col <= colEnd; col++) {
                result.add(row * cols + col);
            }
        });
        return result.toArray();
    }

    /** Grows the mask (never shrinks) so it covers the given world rectangle, keeping existing reveals. */
    public boolean resizeToCover(double minX, double minY, double maxX, double maxY) {
        double tolerance = cellSize * 0.01;
        int addLeft = minX < originX - tolerance ? (int) Math.ceil((originX - minX) / cellSize) : 0;
        int addTop = minY < originY - tolerance ? (int) Math.ceil((originY - minY) / cellSize) : 0;
        double right = originX + getWidth();
        double bottom = originY + getHeight();
        int addRight = maxX > right + tolerance ? (int) Math.ceil((maxX - right) / cellSize) : 0;
        int addBottom = maxY > bottom + tolerance ? (int) Math.ceil((maxY - bottom) / cellSize) : 0;
        if (addLeft == 0 && addTop == 0 && addRight == 0 && addBottom == 0) {
            return false;
        }
        int newCols = cols + addLeft + addRight;
        int newRows = rows + addTop + addBottom;
        BitSet next = new BitSet();
        for (int idx = revealed.nextSetBit(0); idx >= 0; idx = revealed.nextSetBit(idx + 1)) {
            int row = idx / cols;
            int col = idx % cols;
            next.set((row + addTop) * newCols + col + addLeft);
        }
        originX -= addLeft * cellSize;
        originY -= addTop * cellSize;
        cols = newCols;
        rows = newRows;
        revealed = next;
        version++;
        return true;
    }

    /** Rotates the mask 90 degrees around a world point, matching MapRotationService's point transform. */
    public void rotateQuarter(double centerX, double centerY, boolean clockwise) {
        BitSet next = new BitSet();
        int newCols = rows;
        int newRows = cols;
        double newOriginX;
        double newOriginY;
        if (clockwise) {
            newOriginX = centerX + centerY - originY - rows * cellSize;
            newOriginY = centerY - centerX + originX;
        } else {
            newOriginX = centerX - centerY + originY;
            newOriginY = centerY + centerX - originX - cols * cellSize;
        }
        for (int idx = revealed.nextSetBit(0); idx >= 0; idx = revealed.nextSetBit(idx + 1)) {
            int row = idx / cols;
            int col = idx % cols;
            int newCol = clockwise ? rows - 1 - row : row;
            int newRow = clockwise ? col : cols - 1 - col;
            next.set(newRow * newCols + newCol);
        }
        originX = newOriginX;
        originY = newOriginY;
        cols = newCols;
        rows = newRows;
        revealed = next;
        version++;
    }

    public Snapshot snapshot() {
        return new Snapshot(originX, originY, cols, rows, (BitSet) revealed.clone());
    }

    public void restore(Snapshot snapshot) {
        originX = snapshot.originX();
        originY = snapshot.originY();
        cols = snapshot.cols();
        rows = snapshot.rows();
        revealed = (BitSet) snapshot.bits().clone();
        version++;
    }

    @JsonIgnore
    public double cellCenterX(int col) {
        return originX + (col + 0.5) * cellSize;
    }

    public double rowCenterY(int row) {
        return originY + (row + 0.5) * cellSize;
    }

    private int rowFloor(double y) {
        return (int) Math.floor((y - originY) / cellSize);
    }

    private void setSpan(int row, double minX, double maxX, boolean reveal) {
        int colStart = Math.max(0, (int) Math.ceil((minX - originX) / cellSize - 0.5));
        int colEnd = Math.min(cols - 1, (int) Math.floor((maxX - originX) / cellSize - 0.5));
        if (colStart > colEnd) {
            return;
        }
        revealed.set(row * cols + colStart, row * cols + colEnd + 1, reveal);
    }

    public record Snapshot(double originX, double originY, int cols, int rows, BitSet bits) {
        public boolean sameBits(Snapshot other) {
            return other != null
                    && cols == other.cols
                    && rows == other.rows
                    && Double.compare(originX, other.originX) == 0
                    && Double.compare(originY, other.originY) == 0
                    && bits.equals(other.bits);
        }
    }

    private static final class IntList {
        private int[] data = new int[256];
        private int size;

        void add(int value) {
            if (size == data.length) {
                data = java.util.Arrays.copyOf(data, size * 2);
            }
            data[size++] = value;
        }

        int[] toArray() {
            return java.util.Arrays.copyOf(data, size);
        }
    }
}
