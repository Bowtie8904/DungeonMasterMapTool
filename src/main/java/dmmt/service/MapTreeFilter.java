package dmmt.service;

import dmmt.service.MapLibraryService.Entry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Search filter for the map library tree. */
public final class MapTreeFilter {
    private MapTreeFilter() {
    }

    public static boolean isActive(String query) {
        return query != null && !query.isBlank();
    }

    /**
     * Returns a copy of the tree that only contains entries matching the query. The query is split into
     * whitespace-separated words; every word must be a case-insensitive substring of the entry's name or (for maps)
     * of one of its tags, and different words may match different fields. A matching map is kept together with its
     * parent folders; a matching folder is kept with its full contents. The root is always kept. A blank query
     * returns the tree unchanged.
     */
    public static Entry filter(Entry root, String query) {
        if (!isActive(query)) {
            return root;
        }
        List<String> words = words(query);
        return copyWith(root, filterChildren(root, words));
    }

    /** The lower-case, whitespace-separated words of a search query. */
    public static List<String> words(String query) {
        if (!isActive(query)) {
            return List.of();
        }
        return Arrays.stream(query.trim().toLowerCase(Locale.ROOT).split("\\s+"))
                .filter(word -> !word.isEmpty())
                .toList();
    }

    private static List<Entry> filterChildren(Entry folder, List<String> words) {
        List<Entry> kept = new ArrayList<>();
        for (Entry child : folder.children()) {
            if (matches(child, words)) {
                kept.add(child);
            } else if (child.isFolder()) {
                List<Entry> nested = filterChildren(child, words);
                if (!nested.isEmpty()) {
                    kept.add(copyWith(child, nested));
                }
            }
        }
        return kept;
    }

    private static Entry copyWith(Entry entry, List<Entry> children) {
        return new Entry(entry.kind(), entry.name(), entry.path(), entry.mapFile(), children, entry.tags());
    }

    /** Whether every query word occurs in the entry's name or one of its tags (case-insensitive). */
    public static boolean matches(Entry entry, List<String> words) {
        String name = entry.name().toLowerCase(Locale.ROOT);
        List<String> tags = entry.tags().stream().map(tag -> tag.toLowerCase(Locale.ROOT)).toList();
        for (String rawWord : words) {
            String word = rawWord.toLowerCase(Locale.ROOT);
            if (!name.contains(word) && tags.stream().noneMatch(tag -> tag.contains(word))) {
                return false;
            }
        }
        return true;
    }
}