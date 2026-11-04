package dmmt.online;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The campaign's handout library: folders and image files the DM manages and shares with players.
 * Metadata lives in {@code <campaign>/handouts/index.json}; image bytes in {@code <campaign>/handouts/files/<id>.<ext>}.
 * Files are addressed by id, so rename, move and copy never touch names on disk. Players can only see and read
 * files the DM granted to them (or to everyone); all changes are made by the DM.
 */
public final class CampaignHandouts {
    public static final int MAX_IMAGE_BYTES = 25 * 1024 * 1024;
    public static final int MAX_FOLDER_DEPTH = 12;
    public static final int MAX_FILES = 2000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class FileEntry {
        public String id;
        public String name;
        public String folder = "";
        public String ext = "png";
        public long size;
        public boolean allPlayers;
        public List<String> players = new ArrayList<>();

        public FileEntry copy() {
            FileEntry copy = new FileEntry();
            copy.id = id;
            copy.name = name;
            copy.folder = folder;
            copy.ext = ext;
            copy.size = size;
            copy.allPlayers = allPlayers;
            copy.players = new ArrayList<>(players);
            return copy;
        }

        public boolean grantedTo(String playerId) {
            return allPlayers || playerId != null && players.contains(playerId);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Index {
        public int schemaVersion = 1;
        public List<String> folders = new ArrayList<>();
        public List<FileEntry> files = new ArrayList<>();
    }

    /** An immutable listing: the whole library for the DM, or only what one player may see. */
    public record Listing(List<String> folders, List<FileEntry> files) { }

    public static final class Rejected extends IllegalArgumentException {
        private final int status;

        Rejected(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() { return status; }
    }

    private final Path root;
    private final String campaignId;
    private Index index;
    private final java.util.concurrent.CopyOnWriteArrayList<Consumer<String>> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public CampaignHandouts(Path campaignsRoot, String campaignId) {
        if (!CampaignStore.safeId(campaignId)) throw new Rejected(400, "Invalid campaign.");
        this.root = campaignsRoot.toAbsolutePath().normalize().resolve(campaignId).resolve("handouts");
        this.campaignId = campaignId;
    }

    public String campaignId() { return campaignId; }

    /** Called after every change with {@code null}, or the id of a file that must be shown to every player. */
    public void addListener(Consumer<String> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<String> listener) {
        listeners.remove(listener);
    }

    // ---- names and paths ----

    public static String checkName(String name) {
        if (name == null) throw new Rejected(400, "Enter a name.");
        String trimmed = name.strip();
        if (trimmed.isEmpty() || trimmed.length() > 100 || trimmed.equals(".") || trimmed.equals("..")
                || name.chars().anyMatch(c -> c < 32 || "<>:\"/\\|?*".indexOf(c) >= 0))
            throw new Rejected(400, "Use a name of 1-100 characters without / \\ : * ? \" < > | or control characters.");
        return trimmed;
    }

    public static String parentOf(String folder) {
        int slash = folder.lastIndexOf('/');
        return slash < 0 ? "" : folder.substring(0, slash);
    }

    public static String leaf(String folder) {
        return folder.substring(folder.lastIndexOf('/') + 1);
    }

    private static String join(String parent, String name) {
        return parent.isEmpty() ? name : parent + "/" + name;
    }

    private static String normalizeFolder(String folder) {
        if (folder == null || folder.isBlank()) return "";
        String[] parts = folder.split("/", -1);
        if (parts.length > MAX_FOLDER_DEPTH) throw new Rejected(400, "Folders are nested too deeply.");
        List<String> clean = new ArrayList<>();
        for (String part : parts) clean.add(checkName(part));
        return String.join("/", clean);
    }

    private static boolean within(String path, String folder) {
        return path.equals(folder) || path.startsWith(folder + "/");
    }

    // ---- persistence ----

    private Path indexFile() { return root.resolve("index.json"); }

    private Path blobFile(FileEntry entry) { return root.resolve("files").resolve(entry.id + "." + entry.ext); }

    private Index load() throws IOException {
        if (index != null) return index;
        Path file = indexFile();
        checkLinks(file);
        Index loaded = Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                ? MAPPER.readValue(file.toFile(), Index.class) : new Index();
        loaded.files.removeIf(f -> f.id == null || !CampaignStore.safeId(f.id) || f.name == null || f.ext == null
                || !f.ext.matches("png|jpg|gif"));
        for (FileEntry f : loaded.files) {
            if (f.folder == null) f.folder = "";
            if (f.players == null) f.players = new ArrayList<>();
        }
        loaded.folders = new ArrayList<>(new LinkedHashSet<>(loaded.folders));
        index = loaded;
        return index;
    }

    private void checkLinks(Path path) throws IOException {
        Path base = root.getParent();
        for (Path part = path.toAbsolutePath().normalize(); part != null && part.startsWith(base); part = part.getParent()) {
            if (Files.isSymbolicLink(part)) throw new IOException("Handout storage must not contain symbolic links.");
        }
    }

    private void save() throws IOException {
        Files.createDirectories(root.resolve("files"));
        checkLinks(root.resolve("files"));
        Path temp = Files.createTempFile(root, ".index-", ".tmp");
        try {
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), index);
            Files.move(temp, indexFile(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void changed(String showId) {
        for (Consumer<String> listener : listeners) {
            try {
                listener.accept(showId);
            } catch (RuntimeException ignored) {
                // a failing listener must not undo a change that is already saved
            }
        }
    }

    // ---- queries ----

    public synchronized Listing list() throws IOException {
        Index data = load();
        List<FileEntry> files = data.files.stream().map(FileEntry::copy).toList();
        return new Listing(List.copyOf(data.folders.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList()), files);
    }

    /** Only granted files, with the folders that contain them (ancestors included, in the DM's names). */
    public synchronized Listing listFor(String playerId) throws IOException {
        Index data = load();
        List<FileEntry> files = data.files.stream().filter(f -> f.grantedTo(playerId)).map(FileEntry::copy).toList();
        Set<String> folders = new LinkedHashSet<>();
        for (FileEntry file : files) {
            for (String folder = file.folder; !folder.isEmpty(); folder = parentOf(folder)) folders.add(folder);
        }
        return new Listing(folders.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList(), files);
    }

    public synchronized FileEntry find(String id) throws IOException {
        return load().files.stream().filter(f -> f.id.equals(id)).findFirst().map(FileEntry::copy).orElse(null);
    }

    /** Resolves a file for reading; {@code playerId == null} means the DM. Rejects files the player may not see. */
    public synchronized Path open(String id, String playerId, boolean dm) throws IOException {
        FileEntry entry = load().files.stream().filter(f -> f.id.equals(id)).findFirst().orElse(null);
        if (entry == null || !dm && !entry.grantedTo(playerId)) throw new Rejected(404, "Unknown handout.");
        Path file = blobFile(entry);
        checkLinks(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new Rejected(404, "The handout image is missing.");
        return file;
    }

    public static String contentType(String ext) {
        return switch (ext) {
            case "jpg" -> "image/jpeg";
            case "gif" -> "image/gif";
            default -> "image/png";
        };
    }

    // ---- images ----

    /** A validated image: the bytes to store and their extension. */
    public record Prepared(byte[] bytes, String ext) { }

    public static Prepared prepare(byte[] data) throws IOException {
        if (data == null || data.length == 0) throw new Rejected(400, "The image is empty.");
        if (data.length > MAX_IMAGE_BYTES) throw new Rejected(413, "Images can be at most 25 MB.");
        String format;
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = stream == null ? null : ImageIO.getImageReaders(stream);
            if (readers == null || !readers.hasNext()) throw new Rejected(400, "This is not a supported image.");
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream);
                format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (reader.getWidth(0) <= 0 || reader.getHeight(0) <= 0 || (long) reader.getWidth(0) * reader.getHeight(0) > 120_000_000L)
                    throw new Rejected(400, "The image dimensions are not supported.");
            } finally {
                reader.dispose();
            }
        }
        String ext = switch (format) {
            case "png" -> "png";
            case "jpeg", "jpg" -> "jpg";
            case "gif" -> "gif";
            default -> null;
        };
        if (ext != null) {
            // Decoding catches truncated or corrupt files that merely have a valid header.
            if (ImageIO.read(new ByteArrayInputStream(data)) == null) throw new Rejected(400, "This image could not be read.");
            return new Prepared(data, ext);
        }
        var image = ImageIO.read(new ByteArrayInputStream(data));
        if (image == null) throw new Rejected(400, "This image could not be read.");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) throw new Rejected(400, "This image could not be converted.");
        return new Prepared(out.toByteArray(), "png");
    }

    // ---- DM mutations ----

    private static boolean nameTaken(Index data, String folder, String name, String exceptFileId, String exceptFolder) {
        for (FileEntry f : data.files)
            if (f.folder.equals(folder) && f.name.equalsIgnoreCase(name) && !f.id.equals(exceptFileId)) return true;
        for (String f : data.folders)
            if (parentOf(f).equals(folder) && leaf(f).equalsIgnoreCase(name) && !f.equals(exceptFolder)) return true;
        return false;
    }

    private static String freeName(Index data, String folder, String base) {
        String name = base;
        for (int n = 2; nameTaken(data, folder, name, null, null); n++) name = base + " (" + n + ")";
        return name;
    }

    private static void requireFolder(Index data, String folder) {
        if (!folder.isEmpty() && !data.folders.contains(folder)) throw new Rejected(404, "That folder no longer exists.");
    }

    public synchronized String createFolder(String parent, String name) throws IOException {
        Index data = load();
        parent = normalizeFolder(parent);
        requireFolder(data, parent);
        name = checkName(name);
        if (parent.split("/", -1).length >= MAX_FOLDER_DEPTH && !parent.isEmpty()) throw new Rejected(400, "Folders are nested too deeply.");
        if (nameTaken(data, parent, name, null, null)) throw new Rejected(409, "That name already exists here.");
        String path = join(parent, name);
        data.folders.add(path);
        save();
        changed(null);
        return path;
    }

    /** Stores a new image; a duplicate name gets a numeric suffix so drops and pastes never fail on names. */
    public synchronized FileEntry addImage(String folder, String name, byte[] data) throws IOException {
        Index index = load();
        folder = normalizeFolder(folder);
        requireFolder(index, folder);
        if (index.files.size() >= MAX_FILES) throw new Rejected(409, "The handout library is full.");
        Prepared prepared = prepare(data);
        FileEntry entry = new FileEntry();
        entry.id = UUID.randomUUID().toString();
        entry.name = freeName(index, folder, checkName(name));
        entry.folder = folder;
        entry.ext = prepared.ext();
        entry.size = prepared.bytes().length;
        Files.createDirectories(root.resolve("files"));
        Files.write(blobFile(entry), prepared.bytes());
        index.files.add(entry);
        try {
            save();
        } catch (IOException e) {
            index.files.remove(entry);
            Files.deleteIfExists(blobFile(entry));
            throw e;
        }
        changed(null);
        return entry.copy();
    }

    public synchronized void renameFile(String id, String name) throws IOException {
        Index data = load();
        FileEntry entry = require(data, id);
        name = checkName(name);
        if (nameTaken(data, entry.folder, name, id, null)) throw new Rejected(409, "That name already exists here.");
        entry.name = name;
        save();
        changed(null);
    }

    public synchronized String renameFolder(String folder, String name) throws IOException {
        Index data = load();
        if (!data.folders.contains(folder)) throw new Rejected(404, "That folder no longer exists.");
        name = checkName(name);
        String parent = parentOf(folder);
        String target = join(parent, name);
        if (!target.equals(folder)) {
            if (nameTaken(data, parent, name, null, folder)) throw new Rejected(409, "That name already exists here.");
            relocateFolder(data, folder, target);
            save();
            changed(null);
        }
        return target;
    }

    private static void relocateFolder(Index data, String from, String to) {
        for (int i = 0; i < data.folders.size(); i++) {
            String f = data.folders.get(i);
            if (within(f, from)) data.folders.set(i, to + f.substring(from.length()));
        }
        for (FileEntry file : data.files)
            if (within(file.folder, from)) file.folder = to + file.folder.substring(from.length());
    }

    public synchronized void moveFile(String id, String targetFolder) throws IOException {
        Index data = load();
        FileEntry entry = require(data, id);
        targetFolder = normalizeFolder(targetFolder);
        requireFolder(data, targetFolder);
        if (targetFolder.equals(entry.folder)) return;
        if (nameTaken(data, targetFolder, entry.name, id, null)) throw new Rejected(409, "A file or folder with that name already exists there.");
        entry.folder = targetFolder;
        save();
        changed(null);
    }

    public synchronized String moveFolder(String folder, String targetFolder) throws IOException {
        Index data = load();
        if (!data.folders.contains(folder)) throw new Rejected(404, "That folder no longer exists.");
        targetFolder = normalizeFolder(targetFolder);
        requireFolder(data, targetFolder);
        if (within(targetFolder, folder)) throw new Rejected(400, "A folder cannot be moved into itself.");
        String target = join(targetFolder, leaf(folder));
        if (target.equals(folder)) return folder;
        if (nameTaken(data, targetFolder, leaf(folder), null, folder)) throw new Rejected(409, "A file or folder with that name already exists there.");
        int deepest = data.folders.stream().filter(f -> within(f, folder)).mapToInt(f -> f.split("/").length - folder.split("/").length).max().orElse(0);
        if ((targetFolder.isEmpty() ? 0 : targetFolder.split("/").length) + 1 + deepest > MAX_FOLDER_DEPTH)
            throw new Rejected(400, "Folders would be nested too deeply.");
        relocateFolder(data, folder, target);
        save();
        changed(null);
        return target;
    }

    /** Copies a file (with its own id and blob); access grants are not copied. */
    public synchronized FileEntry copyFile(String id, String targetFolder) throws IOException {
        Index data = load();
        FileEntry source = require(data, id);
        targetFolder = normalizeFolder(targetFolder);
        requireFolder(data, targetFolder);
        if (data.files.size() >= MAX_FILES) throw new Rejected(409, "The handout library is full.");
        FileEntry copy = new FileEntry();
        copy.id = UUID.randomUUID().toString();
        copy.folder = targetFolder;
        copy.ext = source.ext;
        copy.size = source.size;
        copy.name = freeName(data, targetFolder, targetFolder.equals(source.folder) ? source.name + " copy" : source.name);
        Files.copy(blobFile(source), blobFile(copy));
        data.files.add(copy);
        try {
            save();
        } catch (IOException e) {
            data.files.remove(copy);
            Files.deleteIfExists(blobFile(copy));
            throw e;
        }
        changed(null);
        return copy.copy();
    }

    public synchronized void deleteFile(String id) throws IOException {
        Index data = load();
        FileEntry entry = require(data, id);
        data.files.remove(entry);
        save();
        Files.deleteIfExists(blobFile(entry));
        changed(null);
    }

    public synchronized void deleteFolder(String folder) throws IOException {
        Index data = load();
        if (!data.folders.contains(folder)) throw new Rejected(404, "That folder no longer exists.");
        List<FileEntry> removed = data.files.stream().filter(f -> within(f.folder, folder)).toList();
        data.files.removeAll(removed);
        data.folders.removeIf(f -> within(f, folder));
        save();
        for (FileEntry entry : removed) Files.deleteIfExists(blobFile(entry));
        changed(null);
    }

    /** Replaces who may see the given files. Player ids are not validated here; unknown ids simply match nobody. */
    public synchronized void setAccess(List<String> ids, boolean allPlayers, Set<String> players) throws IOException {
        Index data = load();
        Set<String> clean = new LinkedHashSet<>();
        for (String player : players) if (CampaignStore.safeId(player)) clean.add(player);
        Set<String> wanted = new HashSet<>(ids);
        for (FileEntry entry : data.files) {
            if (!wanted.contains(entry.id)) continue;
            entry.allPlayers = allPlayers;
            entry.players = new ArrayList<>(clean);
        }
        save();
        changed(null);
    }

    /** Adds access without removing existing grants, for example when sharing a whole folder. */
    public synchronized void grant(List<String> ids, boolean allPlayers, Set<String> players) throws IOException {
        Index data = load();
        Set<String> wanted = new HashSet<>(ids);
        for (FileEntry entry : data.files) {
            if (!wanted.contains(entry.id)) continue;
            if (allPlayers) entry.allPlayers = true;
            for (String player : players)
                if (CampaignStore.safeId(player) && !entry.players.contains(player)) entry.players.add(player);
        }
        save();
        changed(null);
    }

    /** Grants everyone access and asks the server to open the image for all connected players. */
    public synchronized void show(String id) throws IOException {
        Index data = load();
        FileEntry entry = require(data, id);
        entry.allPlayers = true;
        save();
        changed(entry.id);
    }

    public synchronized List<String> fileIdsIn(String folder) throws IOException {
        return load().files.stream().filter(f -> within(f.folder, folder) || folder.isEmpty()).map(f -> f.id).toList();
    }

    /** Drops grants for players that no longer exist in the campaign. */
    public synchronized void prunePlayers(Set<String> existing) throws IOException {
        Index data = load();
        boolean dirty = false;
        for (FileEntry entry : data.files) dirty |= entry.players.removeIf(p -> !existing.contains(p));
        if (dirty) {
            save();
            changed(null);
        }
    }

    private static FileEntry require(Index data, String id) {
        return data.files.stream().filter(f -> f.id.equals(id)).findFirst()
                .orElseThrow(() -> new Rejected(404, "That file no longer exists."));
    }

    public static Comparator<FileEntry> byName() {
        return Comparator.comparing((FileEntry f) -> f.name, String.CASE_INSENSITIVE_ORDER);
    }
}
