package dmmt.online;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/** Private player trees and the campaign's shared UTF-8 note, never served as static assets. */
public final class CampaignNotes {
    public static final int MAX_TEXT_BYTES = 512 * 1024;
    public static final String GROUP = "group.txt";
    public record Entry(String path, String name, boolean folder, boolean shared) { }
    public record Content(String path, String text, String revision) { }

    public static final class Rejected extends IllegalArgumentException {
        private final int status;

        Rejected(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() { return status; }
    }

    private final Path root;

    public CampaignNotes(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    private Path base(String campaign, String player) throws IOException {
        if (!CampaignStore.safeId(campaign) || !CampaignStore.safeId(player))
            throw new Rejected(400, "Invalid campaign or player.");
        Path base = root.resolve(campaign).resolve("notes");
        checkLinks(base.resolve("players").resolve(player));
        Files.createDirectories(base.resolve("players").resolve(player));
        Path group = base.resolve(GROUP);
        checkLinks(group);
        if (!Files.exists(group, LinkOption.NOFOLLOW_LINKS))
            Files.writeString(group, "", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        return base;
    }

    private void checkLinks(Path path) throws IOException {
        for (Path part = path; part != null && part.startsWith(root); part = part.getParent()) {
            if (Files.isSymbolicLink(part))
                throw new IOException("Notes storage must not contain symbolic links.");
        }
    }

    private static void name(String name) {
        if (name == null || name.isBlank() || name.length() > 100 || name.equals(".") || name.equals("..")
                || name.endsWith(".") || name.endsWith(" ") || name.chars().anyMatch(c -> c < 32 || "<>:\"/\\|?*".indexOf(c) >= 0)
                || name.split("\\.", 2)[0].toUpperCase(Locale.ROOT).matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]"))
            throw new Rejected(400, "Use a name of 1-100 characters without reserved file characters or names.");
    }

    private Path resolve(Path base, String player, String path) throws IOException {
        if (GROUP.equals(path)) return base.resolve(GROUP);
        if (path == null || !path.startsWith("private/") || path.length() > 1600)
            throw new Rejected(404, "Unknown note or folder.");
        String[] parts = path.substring("private/".length()).split("/", -1);
        if (parts.length > 16) throw new Rejected(400, "Notes folders are nested too deeply.");
        Path result = base.resolve("players").resolve(player);
        for (String part : parts) {
            name(part);
            result = result.resolve(part);
        }
        checkLinks(result);
        return result;
    }

    public synchronized List<Entry> list(String campaign, String player) throws IOException {
        Path base = base(campaign, player);
        Path privateRoot = base.resolve("players").resolve(player);
        List<Entry> result = new ArrayList<>();
        result.add(new Entry(GROUP, "Group Notes", false, true));
        try (var walk = Files.walk(privateRoot)) {
            for (Path path : walk.filter(p -> !p.equals(privateRoot)).sorted().toList()) {
                checkLinks(path);
                boolean folder = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
                if (!folder && !path.getFileName().toString().endsWith(".txt")) continue;
                String relative = privateRoot.relativize(path).toString().replace('\\', '/');
                String label = path.getFileName().toString();
                result.add(new Entry("private/" + relative, folder ? label : label.substring(0, label.length() - 4), folder, false));
            }
        }
        return result;
    }

    public synchronized Content read(String campaign, String player, String path) throws IOException {
        Path file = resolve(base(campaign, player), player, path);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !path.endsWith(".txt"))
            throw new Rejected(404, "Unknown note.");
        if (Files.size(file) > MAX_TEXT_BYTES) throw new Rejected(413, "Note is larger than 512 KB.");
        byte[] bytes = Files.readAllBytes(file);
        return new Content(path, new String(bytes, StandardCharsets.UTF_8), revision(bytes));
    }

    public synchronized Entry create(String campaign, String player, String parent, String label, boolean folder) throws IOException {
        name(label);
        String filename = folder ? label : label.endsWith(".txt") ? label : label + ".txt";
        String path = (parent == null || parent.isEmpty() ? "private" : parent) + "/" + filename;
        Path base = base(campaign, player);
        Path target = resolve(base, player, path);
        if (!Files.isDirectory(target.getParent(), LinkOption.NOFOLLOW_LINKS))
            throw new Rejected(404, "Parent folder no longer exists.");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new Rejected(409, "That name already exists.");
        if (folder) Files.createDirectory(target);
        else Files.writeString(target, "", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        return new Entry(path, folder ? filename : filename.substring(0, filename.length() - 4), folder, false);
    }

    public synchronized Entry rename(String campaign, String player, String path, String label) throws IOException {
        if (GROUP.equals(path)) throw new Rejected(403, "Group Notes cannot be renamed.");
        name(label);
        Path base = base(campaign, player);
        Path source = resolve(base, player, path);
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) throw new Rejected(404, "Note or folder no longer exists.");
        boolean folder = Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS);
        String filename = folder ? label : label.endsWith(".txt") ? label : label + ".txt";
        String newPath = path.substring(0, path.lastIndexOf('/') + 1) + filename;
        Path target = resolve(base, player, newPath);
        if (!target.equals(source)) {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new Rejected(409, "That name already exists.");
            Files.move(source, target);
        }
        return new Entry(newPath, folder ? filename : filename.substring(0, filename.length() - 4), folder, false);
    }

    public synchronized void delete(String campaign, String player, String path) throws IOException {
        if (GROUP.equals(path)) throw new Rejected(403, "Group Notes cannot be deleted.");
        Path target = resolve(base(campaign, player), player, path);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new Rejected(404, "Note or folder no longer exists.");
        try (var walk = Files.walk(target)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path item : paths) checkLinks(item);
            for (Path item : paths) Files.delete(item);
        }
    }

    public synchronized Content save(String campaign, String player, String path, String text, String expectedRevision) throws IOException {
        Content previous = read(campaign, player, path);
        if (!previous.revision().equals(expectedRevision))
            throw new Rejected(409, "This note changed since you opened it. Copy your draft, then Reload before saving.");
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT_BYTES) throw new Rejected(413, "Note is larger than 512 KB.");
        Path file = resolve(base(campaign, player), player, path);
        Path temp = Files.createTempFile(file.getParent(), ".note-", ".tmp");
        try {
            Files.write(temp, bytes);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
        return new Content(path, text, revision(bytes));
    }

    private static String revision(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
