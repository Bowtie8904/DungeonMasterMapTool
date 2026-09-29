package dmmt.service;

import dmmt.service.MapLibraryService.Entry;

import java.util.ArrayList;
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
     * Returns a copy of the tree that only contains entries matching the query (case-insensitive substring of the
     * name). A matching map is kept together with its parent folders; a matching folder is kept with its full
     * contents. The root is always kept. A blank query returns the tree unchanged.
     */
    public static Entry filter(Entry root, String query) {
        if (!isActive(query)) {
            return root;
        }
        String needle = query.trim().toLowerCase(Locale.ROOT);
        return new Entry(root.kind(), root.name(), root.path(), root.mapFile(), filterChildren(root, needle));
    }

    private static List<Entry> filterChildren(Entry folder, String needle) {
        List<Entry> kept = new ArrayList<>();
        for (Entry child : folder.children()) {
            if (matches(child, needle)) {
                kept.add(child);
            } else if (child.isFolder()) {
                List<Entry> nested = filterChildren(child, needle);
                if (!nested.isEmpty()) {
                    kept.add(new Entry(child.kind(), child.name(), child.path(), child.mapFile(), nested));
                }
            }
        }
        return kept;
    }

    private static boolean matches(Entry entry, String needle) {
        return entry.name().toLowerCase(Locale.ROOT).contains(needle);
    }
}
