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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private static final int MAX_HISTORY_INDEXES = 4;
    private static final int MAX_SHEET_READS = 128;
    private static final int MAX_CACHED_SHEET_CHARACTERS = 4 * 1024 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path root;
    private final Map<String, HistoryIndex> historyIndexes = new LinkedHashMap<>(4, .75f, true);
    private final Map<Path, CachedSheet> sheetReads = new LinkedHashMap<>(16, .75f, true);
    private long cachedSheetCharacters;

    private record CachedSheet(HistoryIndex.Stamp stamp, String json) {
    }

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
        recoverSheetTransfer(campaignId);
        JsonNode saved = MAPPER.readTree(dir(campaignId).resolve("campaign.json").toFile());
        Campaign campaign = MAPPER.treeToValue(saved, Campaign.class);
        boolean assigned = campaign.ensureAccountColors();
        if (assigned || !saved.hasNonNull("dmColor")) save(campaign);
        return campaign;
    }

    public synchronized void save(Campaign campaign) throws IOException {
        recoverSheetTransfer(campaign.getId());
        campaign.ensureAccountColors();
        Path folder = dir(campaign.getId());
        Files.createDirectories(folder);
        Path target = folder.resolve("campaign.json");
        Path temp = folder.resolve("campaign.json.tmp");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), campaign);
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    public synchronized void delete(String campaignId) throws IOException {
        Path folder = dir(campaignId);
        historyIndexes.remove(campaignId);
        for (Path file : new ArrayList<>(sheetReads.keySet())) {
            if (file.startsWith(folder)) invalidateSheet(file);
        }
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
        try {
            historyIndex(campaignId).append(entry, false);
        } catch (IOException e) {
            historyIndexes.remove(campaignId);
            throw e;
        }
    }

    public synchronized List<JsonNode> historyTail(String campaignId, int limit) {
        try {
            recoverSheetTransfer(campaignId);
            return historyIndex(campaignId).tail(limit);
        } catch (IOException ignored) {
            // empty history
        }
        return new ArrayList<>();
    }

    /** First persisted (id, ts) match, or null when absent/unreadable; returned JSON is caller-owned. */
    public synchronized JsonNode historyEntry(String campaignId, long id, long timestamp) {
        try {
            recoverSheetTransfer(campaignId);
            return historyIndex(campaignId).entry(id, timestamp);
        } catch (IOException ignored) {
            return null;
        }
    }

    /** First persisted shared-card match, with the same recovery and ownership semantics as historyEntry. */
    public synchronized JsonNode historyCard(String campaignId, String cardId) {
        try {
            recoverSheetTransfer(campaignId);
            return historyIndex(campaignId).card(cardId);
        } catch (IOException ignored) {
            return null;
        }
    }

    private HistoryIndex historyIndex(String campaignId) throws IOException {
        Path file = dir(campaignId).resolve("history.jsonl");
        HistoryIndex.Stamp stamp = HistoryIndex.Stamp.read(file);
        HistoryIndex cached = historyIndexes.get(campaignId);
        if (cached != null && cached.matches(stamp)) return cached;
        HistoryIndex index = HistoryIndex.read(file, stamp);
        historyIndexes.put(campaignId, index);
        if (historyIndexes.size() > MAX_HISTORY_INDEXES)
            historyIndexes.remove(historyIndexes.keySet().iterator().next());
        return index;
    }

    /** Rewrites history before publishing a deletion; failures leave the original file in place. */
    public synchronized void deleteHistoryEntry(String campaignId, long id, long timestamp) throws IOException {
        recoverSheetTransfer(campaignId);
        try {
            historyIndex(campaignId).delete(id, timestamp);
        } catch (IOException e) {
            historyIndexes.remove(campaignId);
            throw e;
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
        writeHistoryEntryRaw(campaignId, id, timestamp, replacement, false);
    }

    private void writeHistoryEntryRaw(String campaignId, long id, long timestamp, ObjectNode replacement,
                                      boolean allowNew) throws IOException {
        try {
            HistoryIndex index = historyIndex(campaignId);
            index.requireReadable();
            JsonNode existing = index.entry(id, timestamp);
            if (existing == null && allowNew) index.append(replacement, true);
            else if (!replacement.equals(existing)) index.replace(id, timestamp, replacement);
        } catch (IOException e) {
            historyIndexes.remove(campaignId);
            throw e;
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
        if (!Files.isRegularFile(file)) {
            invalidateSheet(file);
            return null;
        }
        HistoryIndex.Stamp stamp = HistoryIndex.Stamp.read(file);
        CachedSheet cached = sheetReads.get(file);
        if (cached != null && cached.stamp.equals(stamp)) return cached.json;
        invalidateSheet(file);
        String json = Files.readString(file, StandardCharsets.UTF_8);
        if (stamp != null && stamp.equals(HistoryIndex.Stamp.read(file))) cacheSheet(file, stamp, json);
        return json;
    }

    public synchronized void saveSheet(String campaignId, String actorId, String json) throws IOException {
        recoverSheetTransfer(campaignId);
        var sheet = MAPPER.readTree(json);
        if (sheet instanceof ObjectNode object) OnlineTokenConditions.normalizeFullStress(object);
        writeSheet(campaignId, actorId, sheet.toString());
    }

    /** Journal an actor rename together with its sheet, if one exists. */
    public synchronized void renameActor(Campaign campaign, Campaign.Actor actor, String name, ObjectNode sheet)
            throws IOException {
        recoverSheetTransfer(campaign.getId());
        ObjectNode transaction = MAPPER.createObjectNode();
        if (sheet != null) {
            OnlineTokenConditions.normalizeFullStress(sheet);
            transaction.set(actor.getId(), sheet);
        }
        transaction.putObject("$actorName").put("id", actor.getId()).put("name", name);
        writeSheetTransaction(campaign.getId(), transaction);
        actor.setName(name);
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
        saveSheetsAndHistory(campaignId, sheets, roll, false);
    }

    /** Journals a new public result with its sheet changes; replay never duplicates the message. */
    public synchronized void saveSheetsAndMessage(String campaignId, java.util.Map<String, ObjectNode> sheets,
                                                  ObjectNode message) throws IOException {
        saveSheetsAndHistory(campaignId, sheets, message, true);
    }

    private void saveSheetsAndHistory(String campaignId, java.util.Map<String, ObjectNode> sheets,
                                      ObjectNode message, boolean newMessage) throws IOException {
        saveSheetsAndResults(campaignId, sheets, newMessage ? null : message,
                newMessage && message != null ? List.of(message) : List.of());
    }

    /** Journals sheet changes, an optional updated result, and additional new reports as one transaction. */
    public synchronized void saveSheetsAndResults(String campaignId, java.util.Map<String, ObjectNode> sheets,
                                                  ObjectNode replacement, List<ObjectNode> messages) throws IOException {
        saveSheetsAndResults(campaignId, sheets, replacement, messages, null);
    }

    public synchronized void saveSheetsAndRename(Campaign campaign, Campaign.Actor actor, String name,
                                                 java.util.Map<String, ObjectNode> sheets,
                                                 ObjectNode replacement, List<ObjectNode> messages) throws IOException {
        saveSheetsAndResults(campaign.getId(), sheets, replacement, messages, null, actor, name);
        actor.setName(name);
    }

    public synchronized void saveFearReward(String campaignId, java.util.Map<String, ObjectNode> sheets,
                                             ObjectNode roll, int fear) throws IOException {
        saveSheetsAndResults(campaignId, sheets, roll, List.of(), fear);
    }

    public synchronized void saveFearReward(String campaignId, java.util.Map<String, ObjectNode> sheets,
                                             ObjectNode roll, List<ObjectNode> messages, int fear) throws IOException {
        saveSheetsAndResults(campaignId, sheets, roll, messages, fear);
    }

    public synchronized void saveSpecialCardAction(String campaignId, String actorId, ObjectNode sheet,
                                                    ObjectNode message, int fear, List<Campaign.Countdown> countdowns) throws IOException {
        recoverSheetTransfer(campaignId);
        OnlineTokenConditions.normalizeFullStress(sheet);
        ObjectNode transaction = MAPPER.createObjectNode();
        transaction.set(actorId, sheet);
        transaction.set("$message", message);
        transaction.put("$fear", fear);
        transaction.set("$countdowns", MAPPER.valueToTree(countdowns));
        writeSheetTransaction(campaignId, transaction);
    }

    private void saveSheetsAndResults(String campaignId, java.util.Map<String, ObjectNode> sheets,
                                      ObjectNode replacement, List<ObjectNode> messages, Integer fear) throws IOException {
        saveSheetsAndResults(campaignId, sheets, replacement, messages, fear, null, null);
    }

    private void saveSheetsAndResults(String campaignId, java.util.Map<String, ObjectNode> sheets,
                                      ObjectNode replacement, List<ObjectNode> messages, Integer fear,
                                      Campaign.Actor actor, String name) throws IOException {
        recoverSheetTransfer(campaignId);
        ObjectNode transaction = MAPPER.createObjectNode();
        for (var entry : sheets.entrySet()) {
            OnlineTokenConditions.normalizeFullStress(entry.getValue());
            transaction.set(entry.getKey(), entry.getValue());
        }
        if (replacement != null) transaction.set("$roll", replacement);
        if (messages.size() == 1) transaction.set("$message", messages.getFirst());
        else if (!messages.isEmpty()) transaction.set("$messages", MAPPER.valueToTree(messages));
        if (fear != null) transaction.put("$fear", fear);
        if (actor != null) transaction.putObject("$actorName").put("id", actor.getId()).put("name", name);
        writeSheetTransaction(campaignId, transaction);
    }

    private void writeSheetTransaction(String campaignId, ObjectNode transaction) throws IOException {
        Path journal = dir(campaignId).resolve("sheet-transfer.json");
        Path temp = journal.resolveSibling("sheet-transfer.json.tmp");
        Files.writeString(temp, transaction.toString(), StandardCharsets.UTF_8);
        Files.move(temp, journal, StandardCopyOption.REPLACE_EXISTING);
        recoverSheetTransfer(campaignId);
    }

    // Replay interrupted sheet transfers and roll rewards before subsequent reads/saves.
    private void recoverSheetTransfer(String campaignId) throws IOException {
        HistoryIndex.recoverAppend(dir(campaignId).resolve("history.jsonl"));
        Path journal = dir(campaignId).resolve("sheet-transfer.json");
        if (!Files.isRegularFile(journal))
            return;
        JsonNode transaction = MAPPER.readTree(journal.toFile());
        var fields = transaction.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            if (List.of("$roll", "$message", "$messages", "$fear", "$actorName", "$countdowns").contains(entry.getKey())) continue;
            writeSheet(campaignId, entry.getKey(), entry.getValue().toString());
        }
        if (transaction.get("$roll") instanceof ObjectNode roll)
            replaceHistoryEntryRaw(campaignId, roll.path("id").asLong(), roll.path("ts").asLong(), roll);
        if (transaction.get("$message") instanceof ObjectNode message)
            writeHistoryEntryRaw(campaignId, message.path("id").asLong(), message.path("ts").asLong(), message, true);
        for (JsonNode entry : transaction.path("$messages")) {
            if (!(entry instanceof ObjectNode message)) throw new IOException("Invalid journaled chat result.");
            writeHistoryEntryRaw(campaignId, message.path("id").asLong(), message.path("ts").asLong(), message, true);
        }
        if (transaction.has("$fear") || transaction.has("$actorName") || transaction.has("$countdowns")) {
            Path file = dir(campaignId).resolve("campaign.json");
            ObjectNode saved = (ObjectNode) MAPPER.readTree(file.toFile());
            if (transaction.has("$fear")) saved.put("fear", transaction.path("$fear").asInt());
            if (transaction.has("$countdowns")) saved.set("countdowns", transaction.path("$countdowns"));
            if (transaction.has("$actorName")) {
                JsonNode rename = transaction.path("$actorName");
                boolean found = false;
                for (JsonNode actor : saved.path("actors")) {
                    if (actor instanceof ObjectNode object && rename.path("id").asText().equals(actor.path("id").asText())) {
                        object.put("name", rename.path("name").asText());
                        found = true;
                        break;
                    }
                }
                if (!found) throw new IOException("Cannot rename an actor missing from the saved campaign.");
            }
            Path temp = file.resolveSibling("campaign.json.tmp");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), saved);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.delete(journal);
    }

    private void writeSheet(String campaignId, String actorId, String json) throws IOException {
        Path file = sheetFile(campaignId, actorId);
        invalidateSheet(file);
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(actorId + ".json.tmp");
        Files.writeString(temp, json, java.nio.charset.StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private void invalidateSheet(Path file) {
        CachedSheet removed = sheetReads.remove(file);
        if (removed != null) cachedSheetCharacters -= removed.json.length();
    }

    private void cacheSheet(Path file, HistoryIndex.Stamp stamp, String json) {
        invalidateSheet(file);
        if (json.length() > MAX_CACHED_SHEET_CHARACTERS) return;
        sheetReads.put(file, new CachedSheet(stamp, json));
        cachedSheetCharacters += json.length();
        while (sheetReads.size() > MAX_SHEET_READS || cachedSheetCharacters > MAX_CACHED_SHEET_CHARACTERS) {
            invalidateSheet(sheetReads.keySet().iterator().next());
        }
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
