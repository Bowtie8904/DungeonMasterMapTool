package dmmt.lighting;

/** Global ambient lighting presets. Darkness is the overlay alpha used in the player view. */
public enum TimeOfDayPreset {
    DAY("Day", 0.0, 0.0, 0.0, 0.0),
    DAWN("Dawn", 0.35, 0.22, 0.16, 0.30),
    DUSK("Dusk", 0.55, 0.20, 0.09, 0.12),
    NIGHT("Night", 0.86, 0.01, 0.02, 0.08);

    private final String label;
    private final double darkness;
    private final double red;
    private final double green;
    private final double blue;

    TimeOfDayPreset(String label, double darkness, double red, double green, double blue) {
        this.label = label;
        this.darkness = darkness;
        this.red = red;
        this.green = green;
        this.blue = blue;
    }

    public String label() {
        return label;
    }

    public double darkness() {
        return darkness;
    }

    public double red() {
        return red;
    }

    public double green() {
        return green;
    }

    public double blue() {
        return blue;
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
