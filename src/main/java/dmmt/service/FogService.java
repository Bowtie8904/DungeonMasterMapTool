package dmmt.service;

import dmmt.model.DmProject;
import dmmt.model.FogMask;

public class FogService {
    public static final int DEFAULT_CELLS_PER_GRID = 10;
    private static final double MIN_CELL_SIZE = 2;
    private static final double MAX_CELL_SIZE = 50;
    private static final double DEFAULT_WIDTH = 1920;
    private static final double DEFAULT_HEIGHT = 1080;

    /** Global fog resolution (fog cells per map grid cell along each axis); applies to every project. */
    private static volatile int cellsPerGrid = DEFAULT_CELLS_PER_GRID;

    /** Lowest fog cells per grid cell (setting fog.cellsPerGrid.min). */
    public static int minCellsPerGrid() {
        return Math.min(Tuning.FOG_CELLS_MIN.get(), Tuning.FOG_CELLS_MAX.get());
    }

    /** Highest fog cells per grid cell (setting fog.cellsPerGrid.max). */
    public static int maxCellsPerGrid() {
        return Math.max(Tuning.FOG_CELLS_MIN.get(), Tuning.FOG_CELLS_MAX.get());
    }

    public static int getCellsPerGrid() {
        return cellsPerGrid;
    }

    public static void setCellsPerGrid(int value) {
        cellsPerGrid = Math.max(minCellsPerGrid(), Math.min(maxCellsPerGrid(), value));
    }

    /** Creates the fog mask if needed and grows it to cover all map content. Migrates legacy rect reveals. */
    public void ensureMask(DmProject project) {
        DmProject.FogState fog = project.getFog();
        if (fog == null) {
            fog = DmProject.FogState.builder().build();
            project.setFog(fog);
        }
        double[] bounds = contentBounds(project);
        FogMask mask = fog.getMask();
        if (mask == null || mask.getCols() <= 0 || mask.getRows() <= 0 || mask.getCellSize() <= 0) {
            double cellSize = cellSizeFor(project);
            int cols = (int) Math.ceil((bounds[2] - bounds[0]) / cellSize);
            int rows = (int) Math.ceil((bounds[3] - bounds[1]) / cellSize);
            mask = new FogMask(bounds[0], bounds[1], cellSize, cols, rows);
            fog.setMask(mask);
        } else {
            double cellSize = cellSizeFor(project);
            if (Math.abs(mask.getCellSize() - cellSize) > cellSize * 0.001) {
                mask.resample(cellSize);
            }
            mask.resizeToCover(bounds[0], bounds[1], bounds[2], bounds[3]);
        }

        if (fog.getRevealedRegions() != null && !fog.getRevealedRegions().isEmpty()) {
            for (DmProject.RevealedRegion region : fog.getRevealedRegions()) {
                mask.applyRect(region.getX(), region.getY(), region.getWidth(), region.getHeight(), true);
            }
            fog.getRevealedRegions().clear();
        }
    }

    public double cellSizeFor(DmProject project) {
        double pixelsPerCell = project.getMap() != null && project.getMap().getGrid() != null
                ? project.getMap().getGrid().getPixelsPerCell()
                : 100;
        return Math.max(MIN_CELL_SIZE, Math.min(MAX_CELL_SIZE, pixelsPerCell / cellsPerGrid));
    }

    /** Returns [minX, minY, maxX, maxY] of all image layers and walls. */
    public double[] contentBounds(DmProject project) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (DmProject.ImageLayer layer : project.getImageLayers()) {
            minX = Math.min(minX, layer.getX());
            minY = Math.min(minY, layer.getY());
            maxX = Math.max(maxX, layer.getX() + layer.getWidth());
            maxY = Math.max(maxY, layer.getY() + layer.getHeight());
        }
        for (DmProject.WallSegment wall : project.getWalls()) {
            minX = Math.min(minX, Math.min(wall.getX1(), wall.getX2()));
            minY = Math.min(minY, Math.min(wall.getY1(), wall.getY2()));
            maxX = Math.max(maxX, Math.max(wall.getX1(), wall.getX2()));
            maxY = Math.max(maxY, Math.max(wall.getY1(), wall.getY2()));
        }
        if (minX > maxX || minY > maxY) {
            return new double[]{0, 0, DEFAULT_WIDTH, DEFAULT_HEIGHT};
        }
        return new double[]{minX, minY, Math.max(maxX, minX + 1), Math.max(maxY, minY + 1)};
    }
}
