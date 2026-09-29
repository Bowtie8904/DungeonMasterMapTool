package dmmt.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import dmmt.lighting.TimeOfDayPreset;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DmProject {
    @Builder.Default
    private int schemaVersion = 1;

    @Builder.Default
    private MapInfo map = MapInfo.builder().build();

    @Builder.Default
    private List<ImageLayer> imageLayers = new ArrayList<>();

    @Builder.Default
    private List<WallSegment> walls = new ArrayList<>();

    @Builder.Default
    private List<Interactable> interactables = new ArrayList<>();

    @Builder.Default
    private ViewState views = ViewState.builder().build();

    @Builder.Default
    private FogState fog = FogState.builder().build();

    @Builder.Default
    private LightingState lighting = LightingState.builder().build();

    @Builder.Default
    private PingSettings pings = PingSettings.builder().build();

    @Builder.Default
    private List<OverlayShape> overlays = new ArrayList<>();

    @Builder.Default
    private List<TextBox> textBoxes = new ArrayList<>();

    @Builder.Default
    private boolean textLayerVisible = true;

    /** Text settings last used on this map; {@code null} until text has been used here. */
    private TextSettings lastTextSettings;
    @Builder.Default
    private List<PingEvent> activePings = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MapInfo {
        @Builder.Default
        private String sourceType = "custom";
        private String sourcePath;
        private String imagePath;
        @Builder.Default
        private GridSpec grid = GridSpec.builder().build();
        @Builder.Default
        private int rotationQuarterTurns = 0;
        /** {@code null} (older saves) falls back to {@link #defaultImageLayersLocked()}. */
        private Boolean imageLayersLocked;

        public boolean imageLayersLockedOrDefault() {
            return imageLayersLocked != null ? imageLayersLocked : defaultImageLayersLocked();
        }

        /** Imported dd2vtt maps start locked; custom maps start unlocked. */
        public boolean defaultImageLayersLocked() {
            return "dd2vtt".equalsIgnoreCase(sourceType) || (sourcePath != null && !sourcePath.isBlank());
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GridSpec {
        @Builder.Default
        private double pixelsPerCell = 100.0;
        @Builder.Default
        private double cellSizeFeet = 5.0;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImageLayer {
        private String id;
        private String path;
        @Builder.Default
        private double x = 0;
        @Builder.Default
        private double y = 0;
        @Builder.Default
        private double width = 0;
        @Builder.Default
        private double height = 0;
        @Builder.Default
        private double rotationDeg = 0;
        @Builder.Default
        private int zIndex = 0;
        @Builder.Default
        private boolean visible = true;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WallSegment {
        private double x1;
        private double y1;
        private double x2;
        private double y2;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Interactable {
        private String id;
        @Builder.Default
        private String type = "door";
        private double x1;
        private double y1;
        private double x2;
        private double y2;
        @Builder.Default
        private String state = "closed";
        @Builder.Default
        private boolean blocksSightWhenClosed = true;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ViewState {
        @Builder.Default
        private CameraState dmCamera = CameraState.builder().build();
        @Builder.Default
        private CameraState playerCamera = CameraState.builder().build();
        @Builder.Default
        private boolean playerFrozen = false;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CameraState {
        @Builder.Default
        private double x = 0;
        @Builder.Default
        private double y = 0;
        @Builder.Default
        private double zoom = 1.0;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FogState {
        @Builder.Default
        private boolean enabled = true;
        @Builder.Default
        private double maskResolution = 0.5;
        private FogMask mask;
        /** Legacy (pre-mask) rectangle reveals; migrated into {@link #mask} on load. */
        @Builder.Default
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        private List<RevealedRegion> revealedRegions = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RevealedRegion {
        @Builder.Default
        private String type = "rect";
        private double x;
        private double y;
        private double width;
        private double height;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LightingState {
        @Builder.Default
        private String timeOfDayPreset = "DAY";
        @Builder.Default
        private List<LightSource> lights = new ArrayList<>();
        /**
         * Per time-of-day ambient brightness adjustment for this map, keyed by preset name
         * (e.g. "NIGHT"). Missing entries mean 0 (preset default).
         */
        @Builder.Default
        private Map<String, Double> ambientBrightness = new TreeMap<>();

        public double ambientBrightnessFor(String presetName) {
            if (ambientBrightness == null || presetName == null) {
                return 0.0;
            }
            Double value = ambientBrightness.get(presetName.trim().toUpperCase(Locale.ROOT));
            return value == null ? 0.0 : TimeOfDayPreset.clampBrightness(value);
        }

        public void putAmbientBrightness(String presetName, double brightness) {
            if (presetName == null) {
                return;
            }
            if (ambientBrightness == null) {
                ambientBrightness = new TreeMap<>();
            }
            String key = presetName.trim().toUpperCase(Locale.ROOT);
            double value = TimeOfDayPreset.clampBrightness(brightness);
            if (Math.abs(value) < 1e-9) {
                ambientBrightness.remove(key);
            } else {
                ambientBrightness.put(key, value);
            }
        }
    }

    public enum RevealMode {
        /** Fog uncovered by the light stays revealed after the light moves away. */
        PERSISTENT,
        /** Fog is only uncovered while the light currently sees the area. */
        WHILE_LIT,
        /** The light never uncovers fog (e.g. static map lamps). */
        NONE
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LightSource {
        private String id;
        private double x;
        private double y;
        @Builder.Default
        private double range = 300;
        @Builder.Default
        private String color = "#FFD9A0";
        @Builder.Default
        private double intensity = 1.0;
        @Builder.Default
        private boolean enabled = true;
        @Builder.Default
        private boolean castsShadows = true;
        @Builder.Default
        private RevealMode revealMode = RevealMode.WHILE_LIT;
        @Builder.Default
        private Flicker flicker = Flicker.builder().build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Flicker {
        @Builder.Default
        private boolean enabled = false;
        @Builder.Default
        private double strength = 0.18;
        @Builder.Default
        private double speed = 1.5;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PingSettings {
        @Builder.Default
        private String style = "default";
        @Builder.Default
        private boolean persistHistory = false;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PingEvent {
        private double x;
        private double y;
        private long createdAtMillis;
        @Builder.Default
        private long durationMillis = 1200;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OverlayShape {
        private String id;
        /** "circle" (x,y = center, radius), "rect" (x,y = top-left, width, height) or "brush" (points, strokeWidth). */
        private String type;
        private double x;
        private double y;
        private double width;
        private double height;
        private double radius;
        private double strokeWidth;
        /** Flat list of x,y world coordinates for brush strokes. */
        @Builder.Default
        private List<Double> points = new ArrayList<>();
        @Builder.Default
        private String color = "#55AA33";
        @Builder.Default
        private double alpha = 0.4;
        @Builder.Default
        private boolean playerVisible = true;
        /** "none" (flat colour), "smoke", "fire" or "water"; the latter three are animated. */
        @Builder.Default
        private String texture = "none";
        /** Outline around a textured shape; off by default (flat shapes always have their edge line). */
        @Builder.Default
        private boolean border = false;
    }

    public static final String TRANSPARENT = "#00000000";
    public static final int DEFAULT_TEXT_SIZE = 32;
    public static final String DEFAULT_TEXT_COLOR = "#FFFFFF";

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TextBox {
        private String id;
        /** Top-left corner and size in world coordinates. */
        private double x;
        private double y;
        private double width;
        private double height;
        @Builder.Default
        private List<TextRun> runs = new ArrayList<>();
        /** "#RRGGBBAA"; fully transparent by default. */
        @Builder.Default
        private String backgroundColor = TRANSPARENT;
        @Builder.Default
        private String borderColor = TRANSPARENT;
        /** When set, the box width and height follow its text instead of being fixed. */
        private boolean autoSize;
    }

    /** A stretch of text with one font size and color; line breaks are "\n" inside the text. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TextRun {
        private String text;
        @Builder.Default
        private int fontSize = DEFAULT_TEXT_SIZE;
        @Builder.Default
        private String color = DEFAULT_TEXT_COLOR;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TextSettings {
        @Builder.Default
        private int fontSize = DEFAULT_TEXT_SIZE;
        @Builder.Default
        private String textColor = DEFAULT_TEXT_COLOR;
        @Builder.Default
        private String backgroundColor = TRANSPARENT;
        @Builder.Default
        private String borderColor = TRANSPARENT;
        /** When set, the box width and height follow its text instead of being fixed. */
        private boolean autoSize;
    }
}

