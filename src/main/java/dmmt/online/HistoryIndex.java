package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Byte offsets retain legacy JSONL ordering without retaining every message's parsed tree. */
final class HistoryIndex {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int CACHED_MESSAGES = 128;

    record Stamp(long size, FileTime modified, FileTime created, Object key) {
        static Stamp read(Path file) throws IOException {
            try {
                BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                if (!attrs.isRegularFile()) throw new IOException("Not a regular file: " + file);
                return new Stamp(attrs.size(), attrs.lastModifiedTime(), attrs.creationTime(), attrs.fileKey());
            } catch (NoSuchFileException e) {
                return null;
            }
        }
    }

    private record Key(long id, long timestamp) {
        static Key of(JsonNode node) {
            return node == null ? null : new Key(node.path("id").asLong(-1), node.path("ts").asLong(-1));
        }
    }

    private record Line(long start, long contentEnd, long end, Key key, long deletionTimestamp, String cardId,
                        boolean damaged) {
        Line shifted(long start) {
            long delta = start - this.start;
            return new Line(start, contentEnd + delta, end + delta, key, deletionTimestamp, cardId, damaged);
        }
    }

    private final Path file;
    private Stamp stamp;
    private List<Line> lines = new ArrayList<>();
    private final Map<Key, Integer> identities = new HashMap<>();
    private final Map<String, Integer> cards = new HashMap<>();
    private final Map<Integer, JsonNode> parsed = new LinkedHashMap<>(16, .75f, true);
    private boolean damaged;
    private boolean invalidEncoding;
    private boolean valid = true;

    private HistoryIndex(Path file, Stamp stamp) {
        this.file = file;
        this.stamp = stamp;
    }

    static HistoryIndex read(Path file, Stamp stamp) throws IOException {
        HistoryIndex index = new HistoryIndex(file, stamp);
        if (stamp == null) return index;
        try (var input = Files.newInputStream(file)) {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            long position = 0;
            long start = 0;
            boolean afterCr = false;
            int count;
            while ((count = input.read(buffer)) != -1) {
                for (int i = 0; i < count; i++) {
                    int value = buffer[i] & 0xff;
                    if (afterCr && value == '\n') {
                        Line previous = index.lines.removeLast();
                        index.lines.add(new Line(previous.start, previous.contentEnd, position + 1,
                                previous.key, previous.deletionTimestamp, previous.cardId, previous.damaged));
                        start = ++position;
                        afterCr = false;
                        continue;
                    }
                    afterCr = value == '\r';
                    if (value == '\r' || value == '\n') {
                        index.addLine(start, position, position + 1, line.toByteArray());
                        line.reset();
                        start = position + 1;
                    } else {
                        line.write(value);
                    }
                    position++;
                }
            }
            if (position > start) index.addLine(start, position, position, line.toByteArray());
        }
        if (!index.matches(Stamp.read(file))) throw new IOException("History changed while it was being read.");
        return index;
    }

    boolean matches(Stamp current) {
        return valid && Objects.equals(stamp, current);
    }

    private void addLine(long start, long contentEnd, long end, byte[] bytes) {
        JsonNode node = null;
        boolean invalid = false;
        try {
            StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes));
            node = MAPPER.readTree(bytes);
        } catch (CharacterCodingException e) {
            invalid = true;
            invalidEncoding = true;
            damaged = true;
        } catch (IOException e) {
            invalid = true;
            damaged = true;
        }
        Key key = Key.of(node);
        if (key != null) identities.putIfAbsent(key, lines.size());
        String cardId = cardId(node);
        if (!cardId.isEmpty()) cards.putIfAbsent(cardId, lines.size());
        if (!invalid) cacheParsed(lines.size(), node);
        lines.add(new Line(start, contentEnd, end, key, node == null ? 0 : node.path("ts").asLong(), cardId, invalid));
    }

    private static String cardId(JsonNode node) {
        return node == null ? "" : node.path("sharedCard").path("cardId").asText("");
    }

    void requireReadable() throws IOException {
        if (damaged) throw new IOException("History contains a damaged JSON line.");
    }

    JsonNode entry(long id, long timestamp) throws IOException {
        requireUtf8();
        Integer position = identities.get(new Key(id, timestamp));
        return position == null ? null : node(position);
    }

    JsonNode card(String cardId) throws IOException {
        requireUtf8();
        Integer position = cards.get(cardId);
        return position == null ? null : node(position);
    }

    List<JsonNode> tail(int limit) throws IOException {
        requireUtf8();
        List<JsonNode> result = new ArrayList<>();
        for (int i = Math.max(0, lines.size() - Math.max(0, limit)); i < lines.size(); i++) {
            if (!lines.get(i).damaged) result.add(node(i));
        }
        return result;
    }

    private void requireUtf8() throws IOException {
        if (invalidEncoding) throw new IOException("History is not valid UTF-8.");
    }

    private JsonNode node(int position) throws IOException {
        if (parsed.containsKey(position)) {
            JsonNode cached = parsed.get(position);
            return cached == null ? null : cached.deepCopy();
        }
        Line line = lines.get(position);
        long length = line.contentEnd - line.start;
        if (length > Integer.MAX_VALUE) throw new IOException("History entry is too large.");
        ByteBuffer bytes = ByteBuffer.allocate((int) length);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            channel.position(line.start);
            while (bytes.hasRemaining()) {
                if (channel.read(bytes) == -1) throw new IOException("History changed while it was being read.");
            }
        }
        JsonNode node = MAPPER.readTree(bytes.array());
        cacheParsed(position, node);
        return node == null ? null : node.deepCopy();
    }

    private void cacheParsed(int position, JsonNode node) {
        parsed.put(position, node);
        if (parsed.size() > CACHED_MESSAGES) parsed.remove(parsed.keySet().iterator().next());
    }

    void append(ObjectNode entry, boolean journaled) throws IOException {
        byte[] content = MAPPER.writeValueAsBytes(entry);
        long start = stamp == null ? 0 : stamp.size;
        boolean separator = !lines.isEmpty() && lines.getLast().contentEnd == start;
        byte[] payload = new byte[content.length + (separator ? 2 : 1)];
        if (separator) payload[0] = '\n';
        System.arraycopy(content, 0, payload, separator ? 1 : 0, content.length);
        payload[payload.length - 1] = '\n';
        if (journaled) {
            ObjectNode intent = MAPPER.createObjectNode().put("offset", start)
                    .put("append", new String(payload, StandardCharsets.UTF_8));
            Path journal = appendJournal(file);
            Path temp = journal.resolveSibling(journal.getFileName() + ".tmp");
            Files.writeString(temp, intent.toString(), StandardCharsets.UTF_8);
            Files.move(temp, journal, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.write(file, payload, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (journaled) Files.delete(appendJournal(file));
        if (separator) {
            Line previous = lines.removeLast();
            lines.add(new Line(previous.start, previous.contentEnd, start + 1,
                    previous.key, previous.deletionTimestamp, previous.cardId, previous.damaged));
            start++;
        }
        addLine(start, start + content.length, start + content.length + 1, content);
        Stamp current = Stamp.read(file);
        valid = current != null && current.size == start + content.length + 1;
        stamp = current;
    }

    /** A torn journaled append can be completed without truncating or rewriting earlier messages. */
    static void recoverAppend(Path file) throws IOException {
        Path journal = appendJournal(file);
        if (!Files.isRegularFile(journal)) return;
        JsonNode intent = MAPPER.readTree(journal.toFile());
        if (intent == null || !intent.isObject()) throw new IOException("Invalid pending history append.");
        long offset = intent.path("offset").asLong(-1);
        if (offset < 0 || !intent.path("append").isTextual())
            throw new IOException("Invalid pending history append.");
        byte[] payload = intent.path("append").asText().getBytes(StandardCharsets.UTF_8);
        long size = Files.exists(file) ? Files.size(file) : 0;
        if (size < offset) throw new IOException("History was truncated during a pending append.");
        int present = (int) Math.min(size - offset, payload.length);
        ByteBuffer existing = ByteBuffer.allocate(present);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE,
                StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            channel.position(offset);
            while (existing.hasRemaining()) {
                if (channel.read(existing) == -1) throw new IOException("Cannot read pending history append.");
            }
            if (!Arrays.equals(existing.array(), Arrays.copyOf(payload, present)))
                throw new IOException("History changed during a pending append.");
            if (present < payload.length) {
                ByteBuffer remaining = ByteBuffer.wrap(payload, present, payload.length - present);
                while (remaining.hasRemaining()) channel.write(remaining);
            }
        }
        Files.delete(journal);
    }

    private static Path appendJournal(Path file) {
        return file.resolveSibling("history-append.json");
    }

    void replace(long id, long timestamp, ObjectNode replacement) throws IOException {
        rewrite(id, timestamp, replacement);
    }

    void delete(long id, long timestamp) throws IOException {
        rewrite(id, timestamp, null);
    }

    private void rewrite(long id, long timestamp, ObjectNode replacement) throws IOException {
        requireReadable();
        Integer first = identities.get(new Key(id, timestamp));
        if (replacement != null && first == null) throw new IOException("Chat message no longer exists.");
        byte[] content = replacement == null ? null : MAPPER.writeValueAsBytes(replacement);
        Path temp = file.resolveSibling("history.jsonl.tmp");
        List<Line> updated = new ArrayList<>(lines.size());
        boolean found = false;
        try {
            try (FileChannel source = FileChannel.open(file, StandardOpenOption.READ);
                 FileChannel target = FileChannel.open(temp, StandardOpenOption.CREATE,
                         StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                long copied = 0;
                long position = 0;
                for (int i = 0; i < lines.size(); i++) {
                    Line line = lines.get(i);
                    boolean match = replacement == null
                            ? line.key != null && line.key.id == id && line.deletionTimestamp == timestamp
                            : i == first;
                    if (match) {
                        copy(source, target, copied, line.start);
                        copied = line.end;
                        found = true;
                        if (content != null) {
                            ByteBuffer bytes = ByteBuffer.wrap(content);
                            while (bytes.hasRemaining()) target.write(bytes);
                            target.write(ByteBuffer.wrap(new byte[]{'\n'}));
                            updated.add(new Line(position, position + content.length, position + content.length + 1,
                                    Key.of(replacement), replacement.path("ts").asLong(), cardId(replacement), false));
                            position += content.length + 1;
                        }
                    } else {
                        updated.add(line.shifted(position));
                        position += line.end - line.start;
                    }
                }
                if (!found) throw new IOException("Chat message no longer exists.");
                copy(source, target, copied, stamp.size);
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            lines = updated;
            identities.clear();
            cards.clear();
            parsed.clear();
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).key != null) identities.putIfAbsent(lines.get(i).key, i);
                if (!lines.get(i).cardId.isEmpty()) cards.putIfAbsent(lines.get(i).cardId, i);
            }
            Stamp current = Stamp.read(file);
            long expectedSize = lines.isEmpty() ? 0 : lines.getLast().end;
            valid = current != null && current.size == expectedSize;
            stamp = current;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void copy(FileChannel source, FileChannel target, long start, long end) throws IOException {
        while (start < end) {
            long copied = source.transferTo(start, end - start, target);
            if (copied == 0) throw new IOException("Cannot copy history.");
            start += copied;
        }
    }
}
