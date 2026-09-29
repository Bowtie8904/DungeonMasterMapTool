package dmmt.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

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
    }
}
