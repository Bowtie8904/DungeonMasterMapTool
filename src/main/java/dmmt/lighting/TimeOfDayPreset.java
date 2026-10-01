package dmmt.lighting;

import dmmt.service.Tuning;

/**
 * Global ambient lighting presets. Darkness is the overlay alpha used in the player view. Darkness and ambient
 * colour come from the {@code timeOfDay.<preset>.*} settings ({@link Tuning}).
 */
public enum TimeOfDayPreset {
    DAY("Day"),
    DAWN("Dawn"),
    DUSK("Dusk"),
    NIGHT("Night");

    private final String label;

    TimeOfDayPreset(String label) {
        this.label = label;
    }

    private Tuning.TimeOfDaySettings settings() {
        return Tuning.timeOfDay(name());
    }

    public String label() {
        return label;
    }

    public double darkness() {
        return settings().darkness().get();
    }

    /**
     * Darkness after applying a per-map ambient brightness adjustment: 0 keeps the preset,
     * positive values brighten (1 = no darkness), negative values darken.
     */
    public double darkness(double brightness) {
        double b = clampBrightness(brightness);
        return Math.max(0.0, Math.min(1.0, darkness() * (1.0 - b)));
    }

    /** Ambient brightness adjustment range; 0 means the preset's default darkness. */
    public static final double MIN_BRIGHTNESS = -0.5;
    public static final double MAX_BRIGHTNESS = 1.0;

    public static double clampBrightness(double brightness) {
        if (Double.isNaN(brightness)) {
            return 0.0;
        }
        return Math.max(MIN_BRIGHTNESS, Math.min(MAX_BRIGHTNESS, brightness));
    }

    public double red() {
        return settings().red().get();
    }

    public double green() {
        return settings().green().get();
    }

    public double blue() {
        return settings().blue().get();
    }

    public static TimeOfDayPreset from(String name) {
        if (name == null) {
            return DAY;
        }
        for (TimeOfDayPreset preset : values()) {
            if (preset.name().equalsIgnoreCase(name.trim())) {
                return preset;
            }
        }
        return DAY;
    }
}
