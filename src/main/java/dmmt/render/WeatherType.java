package dmmt.render;

import java.util.Locale;

/** Ambient weather overlays; the project stores the lower-case {@link #key()}. */
public enum WeatherType {
    NONE("None", 0),
    RAIN("Rain", 600),
    SNOW("Snow", 610),
    MIST("Mist", 70),
    DUST("Dust motes", 500),
    EMBERS("Embers", 295);

    private final String label;
    private final int maxParticles;

    WeatherType(String label, int maxParticles) {
        this.label = label;
        this.maxParticles = maxParticles;
    }

    public String label() {
        return label;
    }

    /** Particles (mist: blobs) at full intensity on a 1920x1080 screen. */
    public int maxParticles() {
        return maxParticles;
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
