package dmmt.audio;

import java.util.Locale;

/** The two kinds of audio in the library: category music and layered sound effect loops (3.35.2). */
public enum AudioKind {
    MUSIC("music", "Music"),
    EFFECT("effect", "Sound effect");

    private final String key;
    private final String label;

    AudioKind(String key, String label) {
        this.key = key;
        this.label = label;
    }

    /** Stable name used in the library index and in the local control API. */
    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    /** The kind with this key; {@link #MUSIC} for unknown or missing keys. */
    public static AudioKind from(String key) {
        if (key != null) {
            String wanted = key.trim().toLowerCase(Locale.ROOT);
            for (AudioKind kind : values()) {
                if (kind.key.equals(wanted)) {
                    return kind;
                }
            }
        }
        return MUSIC;
    }

    @Override
    public String toString() {
        return label;
    }
}
