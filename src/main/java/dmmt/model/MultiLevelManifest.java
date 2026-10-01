package dmmt.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Manifest ({@code .dmlevels}) of a multilevel map: the ordered levels (lowest first), the level opened last and the
 * settings shared by all levels. Every level itself is an ordinary {@code .dmmap} package below {@code levels/}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MultiLevelManifest {
    @Builder.Default
    private int schemaVersion = 1;

    /** Lowest level first. */
    @Builder.Default
    private List<Level> levels = new ArrayList<>();

    /** Id of the level opened last; {@code null} if the map was never opened. */
    private String currentLevelId;

    /** Map-wide settings; {@code null} until they were taken from a level. */
    private SharedSettings shared;

    public Level findLevel(String id) {
        if (id == null) {
            return null;
        }
        return levels.stream().filter(level -> id.equals(level.getId())).findFirst().orElse(null);
    }

    public int indexOf(String id) {
        for (int i = 0; i < levels.size(); i++) {
            if (levels.get(i).getId().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /** The level that is opened: the one opened last, else the lowest one; {@code null} without levels. */
    public Level startLevel() {
        Level current = findLevel(currentLevelId);
        if (current != null) {
            return current;
        }
        return levels.isEmpty() ? null : levels.get(0);
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Level {
        private String id;
        private String name;
        /** Level package folder relative to the manifest, e.g. {@code levels/ab12cd34}. */
        private String folder;
    }

    /** Settings that are the same on every level of the map. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SharedSettings {
        private String timeOfDayPreset;
        @Builder.Default
        private Map<String, Double> ambientBrightness = new TreeMap<>();
        private DmProject.WeatherState weather;
        private Boolean imageLayersLocked;
        @Builder.Default
        private boolean fogEnabled = true;
        @Builder.Default
        private int rotationQuarterTurns = 0;
        @Builder.Default
        private double playerZoomStep = 0;
        @Builder.Default
        private boolean textLayerVisible = true;
        private DmProject.TextSettings lastTextSettings;
    }
}
