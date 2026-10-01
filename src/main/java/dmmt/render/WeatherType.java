package dmmt.render;

import dmmt.service.Tuning;
import javafx.scene.paint.Color;

import java.util.Locale;

/**
 * Ambient weather overlays; the project stores the lower-case {@link #key()}. Particle count, colour and opacity
 * come from the {@code weather.<key>.*} settings ({@link Tuning}).
 */
public enum WeatherType {
    NONE("None"),
    RAIN("Rain"),
    SNOW("Snow"),
    MIST("Mist"),
    DUST("Dust motes"),
    EMBERS("Embers");

    private final String label;

    WeatherType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Particles (mist: blobs) at full intensity on a 1920x1080 screen. */
    public int maxParticles() {
        return this == NONE ? 0 : Tuning.weather(key()).particles().get();
    }

    /** Particle colour. */
    public Color color() {
        return this == NONE ? Color.TRANSPARENT : Color.web(Tuning.weather(key()).color().get());
    }

    /** Highest particle opacity. */
    public double opacity() {
        return this == NONE ? 0 : Tuning.weather(key()).opacity().get();
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Unknown or missing values (older saves) mean no weather. */
    public static WeatherType from(String key) {
        if (key != null) {
            for (WeatherType type : values()) {
                if (type.name().equalsIgnoreCase(key.trim())) {
                    return type;
                }
            }
        }
        return NONE;
    }

    @Override
    public String toString() {
        return label;
    }
}
