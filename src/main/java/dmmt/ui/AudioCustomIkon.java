package dmmt.ui;

import org.kordamp.ikonli.Ikon;

/** Custom audio-picker glyphs for wilderness landmarks missing from the bundled Material Design pack. */
enum AudioCustomIkon implements Ikon {
    MOUNTAIN("dmmt-mountain", 1, "M2 21 9 7 12 13 15 9 22 21H2Z"),
    CAVE("dmmt-cave", 2, "M2 21V16a10 10 0 0 1 20 0v5H2Zm5 0v-5a5 5 0 0 1 10 0v5H7Z");

    private final String description;
    private final int code;
    private final String path;

    AudioCustomIkon(String description, int code, String path) {
        this.description = description;
        this.code = code;
        this.path = path;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public int getCode() {
        return code;
    }

    String path() {
        return path;
    }
}
