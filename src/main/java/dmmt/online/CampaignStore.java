package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * File-backed campaign storage below one root folder: {@code <id>/campaign.json}, {@code fog/<player>/<map>_<level>.bin},
 * {@code history.jsonl} and {@code artwork/<sha256>.png}.
 */
public final class CampaignStore {
    public record Summary(String id, String name, String system, int players, int actors) {
    }

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,100}");
    private static final int MAX_ARTWORK_EDGE = 1024;
    private static final long MAX_ARTWORK_BYTES = 8L * 1024 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path root;

    public CampaignStore(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    public static boolean safeId(String id) {
        return id != null && SAFE_ID.matcher(id).matches();
    }

    private Path dir(String campaignId) {
        if (!safeId(campaignId)) {
            throw new IllegalArgumentException("Invalid campaign id");
        }
        return root.resolve(campaignId);
    }

    public synchronized List<Summary> list() {
        List<Summary> result = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return result;
        }
        try (Stream<Path> children = Files.list(root)) {
            for (Path child : (Iterable<Path>) children::iterator) {
                Path file = child.resolve("campaign.json");
                if (Files.isRegularFile(file)) {
                    try {
                        Campaign campaign = MAPPER.readValue(file.toFile(), Campaign.class);
                        result.add(new Summary(campaign.getId(), campaign.getName(),
                                campaign.getSystem() == null ? Campaign.DEFAULT_SYSTEM : campaign.getSystem(),
                                campaign.getPlayers().size(), campaign.getActors().size()));
                    } catch (IOException ignored) {
                        // unreadable campaign folders are skipped
                    }
                }
            }
        } catch (IOException ignored) {
            // empty list
        }
        result.sort(Comparator.comparing(s -> s.name() == null ? "" : s.name().toLowerCase(Locale.ROOT)));
        return result;
    }

    public synchronized Campaign create(String name) throws IOException {
        return create(name, Campaign.DEFAULT_SYSTEM);
    }

    public synchronized Campaign create(String name, String system) throws IOException {
        Campaign campaign = Campaign.builder().name(name == null || name.isBlank() ? "New campaign" : name.trim())
                .system(system == null || system.isBlank() ? Campaign.DEFAULT_SYSTEM : system).build();
        save(campaign);
        return campaign;
    }

    public synchronized Campaign load(String campaignId) throws IOException {
        return MAPPER.readValue(dir(campaignId).resolve("campaign.json").toFile(), Campaign.class);
    }

    public synchronized void save(Campaign campaign) throws IOException {
        Path folder = dir(campaign.getId());
        Files.createDirectories(folder);
        Path target = folder.resolve("campaign.json");
        Path temp = folder.resolve("campaign.json.tmp");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), campaign);
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    public synchronized void delete(String campaignId) throws IOException {
        Path folder = dir(campaignId);
        if (!Files.isDirectory(folder)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path path : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(path);
            }
        }
    }

    // ---- fog memory ----

    private Path fogFile(String campaignId, String playerId, String mapId, String levelId) {
        if (!safeId(playerId) || !safeId(mapId) || !safeId(levelId)) {
            throw new IllegalArgumentException("Invalid fog key");
        }
        return dir(campaignId).resolve("fog").resolve(playerId).resolve(mapId + "_" + levelId + ".bin");
    }

    /** Explored cells (bit i = cell i) of one player on one map level; empty when nothing was explored yet. */
    public synchronized BitSet loadFog(String campaignId, String playerId, String mapId, String levelId) {
        Path file = fogFile(campaignId, playerId, mapId, levelId);
        try {
            return Files.isRegularFile(file) ? BitSet.valueOf(Files.readAllBytes(file)) : new BitSet();
        } catch (IOException e) {
            return new BitSet();
        }
    }

    /** ORs newly explored cells into the stored memory and returns the result. */
    public synchronized BitSet mergeFog(String campaignId, String playerId, String mapId, String levelId, BitSet cells)
            throws IOException {
        BitSet memory = loadFog(campaignId, playerId, mapId, levelId);
        memory.or(cells);
        Path file = fogFile(campaignId, playerId, mapId, levelId);
        Files.createDirectories(file.getParent());
        Files.write(file, memory.toByteArray());
        return memory;
    }

    // ---- history ----

    public synchronized void appendHistory(String campaignId, ObjectNode entry) {
        try {
            appendHistoryChecked(campaignId, entry);
        } catch (IOException ignored) {
            // history is best effort
        }
    }

    public synchronized void appendHistoryChecked(String campaignId, ObjectNode entry) throws IOException {
        recoverSheetTransfer(campaignId);
        Path folder = dir(campaignId);
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("history.jsonl"), MAPPER.writeValueAsString(entry) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public synchronized List<JsonNode> historyTail(String campaignId, int limit) {
        List<JsonNode> result = new ArrayList<>();
        Path file = dir(campaignId).resolve("history.jsonl");
        if (!Files.isRegularFile(file)) {
            return result;
        }
        try {
            recoverSheetTransfer(campaignId);
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = Math.max(0, lines.size() - limit); i < lines.size(); i++) {
                try {
                    result.add(MAPPER.readTree(lines.get(i)));
                } catch (IOException ignored) {
                    // skip damaged line
                }
            }
        } catch (IOException ignored) {
            // empty history
        }
        return result;
    }

    /** Rewrites history before publishing a deletion; failures leave the original file in place. */
    public synchronized void deleteHistoryEntry(String campaignId, long id, long timestamp) throws IOException {
        recoverSheetTransfer(campaignId);
        Path file = dir(campaignId).resolve("history.jsonl");
        List<String> kept = new ArrayList<>();
        boolean found = false;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            JsonNode entry = MAPPER.readTree(line);
            if (entry != null && entry.path("id").asLong(-1) == id && entry.path("ts").asLong() == timestamp) {
                found = true;
            } else {
                kept.add(line);
            }
        }
        if (!found) {
            throw new IOException("Chat message no longer exists.");
        }
        Path temp = Files.createTempFile(file.getParent(), "history-", ".tmp");
        try {
            Files.write(temp, kept, StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Replaces a persisted history message while keeping the same identity and ordering. */
    public synchronized void replaceHistoryEntry(String campaignId, long id, long timestamp, ObjectNode replacement)
            throws IOException {
        recoverSheetTransfer(campaignId);
        replaceHistoryEntryRaw(campaignId, id, timestamp, replacement);
    }

    private void replaceHistoryEntryRaw(String campaignId, long id, long timestamp, ObjectNode replacement)
            throws IOException {
        Path file = dir(campaignId).resolve("history.jsonl");
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<String> updated = new ArrayList<>(lines.size());
        boolean found = false;
        for (String line : lines) {
            JsonNode entry = MAPPER.readTree(line);
            if (!found && entry.path("id").asLong(-1) == id && entry.path("ts").asLong(-1) == timestamp) {
                updated.add(MAPPER.writeValueAsString(replacement));
                found = true;
            } else {
                updated.add(line);
            }
        }
        if (!found) throw new IOException("Chat message no longer exists.");
        Path temp = file.resolveSibling("history.jsonl.tmp");
        try {
            Files.write(temp, updated, StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    // ---- artwork ----

    /** Validates, downsizes and re-encodes an uploaded image as PNG; returns its sha256 hex name. */
    public synchronized String storeArtwork(String campaignId, byte[] upload) throws IOException {
        if (upload == null || upload.length == 0 || upload.length > MAX_ARTWORK_BYTES) {
            throw new IOException("Image is empty or larger than 8 MB");
        }
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(upload));
        } catch (IOException | RuntimeException e) {
            throw new IOException("Unsupported image");
        }
        if (image == null || image.getWidth() < 1 || image.getHeight() < 1
                || (long) image.getWidth() * image.getHeight() > 40_000_000L) {
            throw new IOException("Unsupported image");
        }
        double scale = Math.min(1.0, (double) MAX_ARTWORK_EDGE / Math.max(image.getWidth(), image.getHeight()));
        int w = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(image.getHeight() * scale));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(image, 0, 0, w, h, null);
        g.dispose();
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(out, "png", png);
        byte[] bytes = png.toByteArray();
        String hash = sha256(bytes);
        Path folder = dir(campaignId).resolve("artwork");
        Files.createDirectories(folder);
        Files.write(folder.resolve(hash + ".png"), bytes);
        return hash;
    }

    public Path artworkPath(String campaignId, String hash) {
        if (!safeId(hash)) {
            throw new IllegalArgumentException("Invalid hash");
        }
        return dir(campaignId).resolve("artwork").resolve(hash + ".png");
    }

    // ---- character sheets ----

    private Path sheetFile(String campaignId, String actorId) {
        if (!safeId(actorId)) {
            throw new IllegalArgumentException("Invalid actor id");
        }
        return dir(campaignId).resolve("sheets").resolve(actorId + ".json");
    }

    /** Stored sheet JSON of an actor, or {@code null} when it was never saved. */
    public synchronized String loadSheet(String campaignId, String actorId) throws IOException {
        recoverSheetTransfer(campaignId);
        Path file = sheetFile(campaignId, actorId);
        return Files.isRegularFile(file) ? Files.readString(file, java.nio.charset.StandardCharsets.UTF_8) : null;
    }

    public synchronized void saveSheet(String campaignId, String actorId, String json) throws IOException {
        recoverSheetTransfer(campaignId);
        writeSheet(campaignId, actorId, json);
    }

    public synchronized void transferSheets(String campaignId, String sourceId, ObjectNode source,
                                            String targetId, ObjectNode target) throws IOException {
        java.util.Map<String, ObjectNode> sheets = new java.util.LinkedHashMap<>();
        sheets.put(sourceId, source);
        sheets.put(targetId, target);
        saveSheets(campaignId, sheets);
    }

    public synchronized void saveSheets(String campaignId, java.util.Map<String, ObjectNode> sheets) throws IOException {
        saveSheetsAndRoll(campaignId, sheets, null);
    }

    /** Journals character changes and a roll's claim marker together for idempotent crash recovery. */
    public synchronized void saveSheetsAndRoll(String campaignId, java.util.Map<String, ObjectNode> sheets,
                                               ObjectNode roll) throws IOException {
        recoverSheetTransfer(campaignId);
        ObjectNode transaction = MAPPER.createObjectNode();
        for (var entry : sheets.entrySet()) {
            transaction.set(entry.getKey(), entry.getValue());
        }
        if (roll != null) transaction.set("$roll", roll);
        Path journal = dir(campaignId).resolve("sheet-transfer.json");
        Path temp = journal.resolveSibling("sheet-transfer.json.tmp");
        Files.writeString(temp, transaction.toString(), StandardCharsets.UTF_8);
        Files.move(temp, journal, StandardCopyOption.REPLACE_EXISTING);
        recoverSheetTransfer(campaignId);
    }

    // Replay interrupted sheet transfers and roll rewards before subsequent reads/saves.
    private void recoverSheetTransfer(String campaignId) throws IOException {
        Path journal = dir(campaignId).resolve("sheet-transfer.json");
        if (!Files.isRegularFile(journal))
            return;
        JsonNode transaction = MAPPER.readTree(journal.toFile());
        var fields = transaction.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            if ("$roll".equals(entry.getKey())) continue;
            writeSheet(campaignId, entry.getKey(), entry.getValue().toString());
        }
        if (transaction.get("$roll") instanceof ObjectNode roll)
            replaceHistoryEntryRaw(campaignId, roll.path("id").asLong(), roll.path("ts").asLong(), roll);
        Files.delete(journal);
    }

    private void writeSheet(String campaignId, String actorId, String json) throws IOException {
        Path file = sheetFile(campaignId, actorId);
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(actorId + ".json.tmp");
        Files.writeString(temp, json, java.nio.charset.StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
