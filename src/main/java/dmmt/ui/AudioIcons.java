package dmmt.ui;

import javafx.scene.paint.Color;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignB;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;
import org.kordamp.ikonli.materialdesign2.MaterialDesignE;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignG;
import org.kordamp.ikonli.materialdesign2.MaterialDesignH;
import org.kordamp.ikonli.materialdesign2.MaterialDesignI;
import org.kordamp.ikonli.materialdesign2.MaterialDesignJ;
import org.kordamp.ikonli.materialdesign2.MaterialDesignK;
import org.kordamp.ikonli.materialdesign2.MaterialDesignL;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;
import org.kordamp.ikonli.materialdesign2.MaterialDesignN;
import org.kordamp.ikonli.materialdesign2.MaterialDesignO;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;
import org.kordamp.ikonli.materialdesign2.MaterialDesignQ;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;
import org.kordamp.ikonli.materialdesign2.MaterialDesignT;
import org.kordamp.ikonli.materialdesign2.MaterialDesignU;
import org.kordamp.ikonli.materialdesign2.MaterialDesignV;
import org.kordamp.ikonli.materialdesign2.MaterialDesignW;
import org.kordamp.ikonli.materialdesign2.MaterialDesignX;
import org.kordamp.ikonli.materialdesign2.MaterialDesignY;
import org.kordamp.ikonli.materialdesign2.MaterialDesignZ;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The icons a music category or sound effect can be given, and how they are rendered in its colour (3.35.2). */
public final class AudioIcons {
    /** The icon packs that make up the searchable catalogue: the complete bundled Material Design set. */
    private static final List<Class<? extends Ikon>> PACKS = List.of(
            MaterialDesignA.class, MaterialDesignB.class, MaterialDesignC.class, MaterialDesignD.class,
            MaterialDesignE.class, MaterialDesignF.class, MaterialDesignG.class, MaterialDesignH.class,
            MaterialDesignI.class, MaterialDesignJ.class, MaterialDesignK.class, MaterialDesignL.class,
            MaterialDesignM.class, MaterialDesignN.class, MaterialDesignO.class, MaterialDesignP.class,
            MaterialDesignQ.class, MaterialDesignR.class, MaterialDesignS.class, MaterialDesignT.class,
            MaterialDesignU.class, MaterialDesignV.class, MaterialDesignW.class, MaterialDesignX.class,
            MaterialDesignY.class, MaterialDesignZ.class);

    /** Icons offered before anything is typed into the category icon search: adventure themes. */
    private static final List<Ikon> CHOICES = List.of(
            MaterialDesignM.MUSIC_NOTE, MaterialDesignM.MUSIC_BOX_MULTIPLE_OUTLINE, MaterialDesignS.SWORD_CROSS,
            MaterialDesignS.SHIELD_OUTLINE, MaterialDesignC.CASTLE, MaterialDesignG.GLASS_MUG_VARIANT,
            MaterialDesignC.CAMPFIRE, MaterialDesignT.TREE_OUTLINE, MaterialDesignW.WEATHER_NIGHT,
            MaterialDesignW.WAVES, MaterialDesignS.SKULL_OUTLINE, MaterialDesignD.DOOR,
            MaterialDesignH.HORSE_VARIANT, MaterialDesignS.SHIP_WHEEL, MaterialDesignM.MAGIC_STAFF,
            MaterialDesignB.BOOK_OPEN_PAGE_VARIANT_OUTLINE, MaterialDesignC.CITY_VARIANT_OUTLINE,
            MaterialDesignP.PINE_TREE, MaterialDesignS.SNOWFLAKE, MaterialDesignF.FIRE,
            MaterialDesignA.ACCOUNT_GROUP_OUTLINE, MaterialDesignD.DICE_D20_OUTLINE,
            MaterialDesignH.HEART_PULSE, MaterialDesignW.WIZARD_HAT);

    /** Icons offered before anything is typed into the sound effect icon search: weather, nature, room tone. */
    private static final List<Ikon> EFFECT_CHOICES = List.of(
            MaterialDesignW.WAVEFORM, MaterialDesignW.WEATHER_WINDY, MaterialDesignW.WEATHER_POURING,
            MaterialDesignW.WEATHER_LIGHTNING_RAINY, MaterialDesignW.WEATHER_FOG, MaterialDesignW.WEATHER_SNOWY,
            MaterialDesignB.BIRD, MaterialDesignW.WAVES, MaterialDesignF.FIRE, MaterialDesignC.CAMPFIRE,
            MaterialDesignT.TREE_OUTLINE, MaterialDesignB.BUG_OUTLINE, MaterialDesignP.PAW,
            MaterialDesignS.SHOE_PRINT, MaterialDesignD.DOOR, MaterialDesignB.BELL_OUTLINE,
            MaterialDesignA.ANVIL, MaterialDesignG.GLASS_MUG_VARIANT, MaterialDesignA.ACCOUNT_GROUP_OUTLINE,
            MaterialDesignH.HORSE_VARIANT, MaterialDesignW.WATER_OUTLINE, MaterialDesignP.PINE_TREE,
            MaterialDesignS.SKULL_OUTLINE, MaterialDesignH.HEART_PULSE);

    private static final List<Ikon> ALL = all();
    private static final Map<String, Ikon> BY_DESCRIPTION = index();

    private AudioIcons() {
    }

    private static List<Ikon> all() {
        List<Ikon> icons = new ArrayList<>();
        for (Class<? extends Ikon> pack : PACKS) {
            icons.addAll(Arrays.asList(pack.getEnumConstants()));
        }
        icons.sort(Comparator.comparing(AudioIcons::searchName));
        return List.copyOf(icons);
    }

    private static Map<String, Ikon> index() {
        Map<String, Ikon> map = new LinkedHashMap<>();
        ALL.forEach(icon -> map.put(icon.getDescription(), icon));
        return map;
    }

    /**
     * The part of an Ikonli description a user searches for: {@code mdi2t-tree-outline} becomes
     * {@code tree-outline}, so typing "tree" finds every tree icon (3.35.2).
     */
    static String searchName(Ikon icon) {
        String description = icon.getDescription() == null ? "" : icon.getDescription();
        int dash = description.indexOf('-');
        return (dash < 0 ? description : description.substring(dash + 1)).toLowerCase(Locale.ROOT);
    }

    /** Every icon of the catalogue, ordered by name. */
    public static List<Ikon> allChoices() {
        return ALL;
    }

    /** The icons shown in the category icon picker before anything is searched for. */
    public static List<Ikon> choices() {
        return CHOICES;
    }

    /** The icons shown in the sound effect icon picker before anything is searched for. */
    public static List<Ikon> effectChoices() {
        return EFFECT_CHOICES;
    }

    /**
     * Icons whose name contains every whitespace-separated word of the query, suggested icons first so a blank
     * query shows the curated set. At most {@code limit} icons are returned so the picker stays responsive.
     */
    public static List<Ikon> search(String query, List<Ikon> suggested, int limit) {
        String cleaned = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (cleaned.isEmpty()) {
            return suggested;
        }
        String[] words = cleaned.split("\\s+");
        List<Ikon> matches = new ArrayList<>();
        for (Ikon icon : suggested) {
            if (matches(icon, words)) {
                matches.add(icon);
            }
        }
        for (Ikon icon : ALL) {
            if (matches.size() >= limit) {
                break;
            }
            if (matches(icon, words)) {
                matches.add(icon);
            }
        }
        List<Ikon> unique = new ArrayList<>(new LinkedHashSet<>(matches));
        return unique.size() > limit ? List.copyOf(unique.subList(0, limit)) : List.copyOf(unique);
    }

    private static boolean matches(Ikon icon, String[] words) {
        String name = searchName(icon);
        for (String word : words) {
            if (!name.contains(word)) {
                return false;
            }
        }
        return true;
    }

    /** The icon with this Ikonli description, or the default music note for unknown names. */
    public static Ikon byDescription(String description) {
        Ikon icon = description == null ? null : BY_DESCRIPTION.get(description);
        return icon == null ? MaterialDesignM.MUSIC_NOTE : icon;
    }

    /** The icon with this Ikonli description, or the default waveform for unknown sound effect names. */
    public static Ikon effectByDescription(String description) {
        Ikon icon = description == null ? null : BY_DESCRIPTION.get(description);
        return icon == null ? MaterialDesignW.WAVEFORM : icon;
    }

    /** A sound effect icon tinted with the effect colour; falls back to the default effect colour. */
    public static FontIcon tintedEffect(String description, String color, int size) {
        return tint(new FontIcon(effectByDescription(description)), effectColor(color), size);
    }

    /** Parses a {@code #RRGGBB} sound effect colour; invalid values give the default effect colour. */
    public static Color effectColor(String value) {
        try {
            return Color.web(value);
        } catch (RuntimeException e) {
            return Color.web(dmmt.audio.AudioTrack.DEFAULT_EFFECT_COLOR);
        }
    }

    /** A category icon tinted with the category colour; falls back to the default colour for invalid values. */
    public static FontIcon tinted(String description, String color, int size) {
        return tint(new FontIcon(byDescription(description)), color(color), size);
    }

    /**
     * Marks an icon that carries a user-chosen category or sound effect colour, so exported control key images
     * can use that colour instead of plain white (3.35.6).
     */
    static final String TINTED_CLASS = "audio-tinted";

    /**
     * Applies size and colour. The {@code ikonli-font-icon} style class is dropped on purpose: the stylesheet
     * gives every icon with that class a fixed {@code -fx-icon-color} and {@code -fx-icon-size}, and an author
     * stylesheet beats the setters, so the category/effect colour would be ignored. An inline style is not an
     * option either - any inline style on a {@code FontIcon} makes the CSS pass re-derive its font from the
     * inherited family, which replaced every glyph with an empty box (3.35.2).
     */
    private static FontIcon tint(FontIcon icon, Color color, int size) {
        icon.getStyleClass().remove("ikonli-font-icon");
        icon.getStyleClass().add(TINTED_CLASS);
        icon.setIconSize(size);
        icon.setIconColor(color);
        return icon;
    }

    /** Parses a {@code #RRGGBB} category colour; invalid values give the default colour. */
    public static Color color(String value) {
        try {
            return Color.web(value);
        } catch (RuntimeException e) {
            return Color.web(dmmt.audio.AudioCategory.DEFAULT_COLOR);
        }
    }
}
