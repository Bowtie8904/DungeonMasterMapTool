package dmmt.audio;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One music category (Adventure, Combat, Tavern, ...) with its colour and icon (3.35.2). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AudioCategory {
    /** Id of the built-in category that holds music without a category of its own; it cannot be deleted or renamed. */
    public static final String UNCATEGORISED_ID = "uncategorised";
    public static final String UNCATEGORISED_NAME = "Uncategorised";
    public static final String DEFAULT_COLOR = "#8AB4F8";
    /** Ikonli description of the default icon, see {@link AudioIcons}. */
    public static final String DEFAULT_ICON = "mdi2m-music-note";

    @Builder.Default
    private String id = java.util.UUID.randomUUID().toString();
    @Builder.Default
    private String name = "New category";
    /** {@code #RRGGBB}; the icon is tinted with it everywhere. */
    @Builder.Default
    private String color = DEFAULT_COLOR;
    /** Ikonli icon description, e.g. {@code mdi2s-sword-cross}. */
    @Builder.Default
    private String icon = DEFAULT_ICON;
    /** Hidden categories keep working but are left out of the audio overlay (3.35.2). */
    @Builder.Default
    private boolean hidden = false;

    public boolean isUncategorised() {
        return UNCATEGORISED_ID.equals(id);
    }

    public static AudioCategory uncategorised() {
        return AudioCategory.builder()
                .id(UNCATEGORISED_ID)
                .name(UNCATEGORISED_NAME)
                .color("#9AA0A6")
                .icon("mdi2m-music-box-multiple-outline")
                // The leftovers bin is not an ambience, so it stays out of the overlay until shown on purpose.
                .hidden(true)
                .build();
    }
}
