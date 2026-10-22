package dmmt.remote;

import com.fasterxml.jackson.databind.JsonNode;
import dmmt.model.DmProject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Serialized, host-authoritative committed map operations. Receipts survive reconnect and map switches;
 * the map context epoch separately invalidates new commands opened on an earlier map.
 */
public final class RemoteWorkspaceAuthority implements RemoteWorkspaceEndpoint, AutoCloseable {
    public static final int MAX_CHANGES = 16_384;
    private static final int MAX_RECEIPTS = 256;
    private static final int MAX_TOMBSTONES = 200_000;
    public enum Status { ACCEPTED, CONFLICT, INVALID, BUSY, RESYNC_REQUIRED }
    public enum Origin { HOST, REMOTE }
    public enum Operation { EDIT, UNDO, REDO }

    public record HistoryEntry(String label, Origin origin) {
        public HistoryEntry {
            if (label == null || label.isBlank() || label.length() > 180
                    || label.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("A bounded history description is required.");
            }
            Objects.requireNonNull(origin, "origin");
        }
    }

    public record History(long version, HistoryEntry undo, HistoryEntry redo) {
        public History {
            if (version < 0) {
                throw new IllegalArgumentException("Invalid history version.");
            }
        }
    }

    public record State(UUID sessionId, UUID contextId, long revision, long nextSequence,
                        RemoteProjectDocument document, Map<String, Long> changedAt,
                        Map<String, RemoteAssetStore.AssetDescriptor> assets, History history) {
        public State {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(contextId, "contextId");
            Objects.requireNonNull(document, "document");
            Objects.requireNonNull(history, "history");
            if (revision < 0 || nextSequence < 1) {
                throw new IllegalArgumentException("Invalid workspace revision/sequence.");
            }
            changedAt = Map.copyOf(changedAt);
            if (changedAt.size() > MAX_TOMBSTONES) {
                throw new IllegalArgumentException("Workspace revision metadata exceeds its limit.");
            }
            changedAt.forEach((target, version) -> {
                RemoteProjectDocument.validateTarget(target);
                if (version == null || version < 0 || version > revision) {
                    throw new IllegalArgumentException("Invalid target revision.");
                }
            });
            assets = Map.copyOf(assets);
            if (assets.size() > RemoteProjectDocument.MAX_TARGETS * 2) {
                throw new IllegalArgumentException("Workspace asset metadata exceeds its limit.");
            }
            assets.forEach((reference, descriptor) -> {
                if (!reference.equals(RemoteProjectAssets.referenceFor(descriptor))) {
                    throw new IllegalArgumentException("Workspace asset identity does not match its manifest.");
                }
            });
            for (String reference : document.assetReferences()) {
                if (reference.startsWith(RemoteProjectAssets.ASSET_REFERENCE_PREFIX) && !assets.containsKey(reference)) {
                    throw new IllegalArgumentException("Workspace artwork is missing from its manifest.");
                }
            }
        }

        public State(UUID sessionId, UUID contextId, long revision, long nextSequence,
                     RemoteProjectDocument document, Map<String, Long> changedAt) {
            this(sessionId, contextId, revision, nextSequence, document, changedAt, Map.of());
        }

        public State(UUID sessionId, UUID contextId, long revision, long nextSequence,
                     RemoteProjectDocument document, Map<String, Long> changedAt,
                     Map<String, RemoteAssetStore.AssetDescriptor> assets) {
            this(sessionId, contextId, revision, nextSequence, document, changedAt, assets, new History(0, null, null));
        }
    }

    /** File IO is completed before these owner-thread conversions are called. */
    public interface ProjectCodec {
        RemoteProjectDocument capture(DmProject project);

        DmProject materialize(RemoteProjectDocument document, DmProject.CameraState dmCamera) throws IOException;

        default void complete(DmProject before, DmProject after) {
        }

        default Map<String, RemoteAssetStore.AssetDescriptor> assets(RemoteProjectDocument document) {
            return Map.of();
        }
    }

    /** Null replacement deletes a target; present JSON null is a property value, not deletion. */
    public record Change(String target, long expectedRevision, boolean expectedPresent, JsonNode expected,
                         boolean replacementPresent, JsonNode replacement) {
        public Change {
            RemoteProjectDocument.validateTarget(target);
            if (expectedRevision < 0) {
                throw new IllegalArgumentException("Invalid target revision.");
            }
            if ((!expectedPresent && expected != null && !expected.isNull())
                    || (!replacementPresent && replacement != null && !replacement.isNull())) {
                throw new IllegalArgumentException("Absent targets cannot carry values.");
            }
            expected = expectedPresent
                    ? (expected == null ? com.fasterxml.jackson.databind.node.NullNode.instance : expected.deepCopy()) : null;
            replacement = replacementPresent
                    ? (replacement == null ? com.fasterxml.jackson.databind.node.NullNode.instance : replacement.deepCopy()) : null;
            if (Objects.equals(expected, replacement)) {
                throw new IllegalArgumentException("A workspace change must change its target.");
            }
        }

        public Change(String target, long expectedRevision, JsonNode expected, JsonNode replacement) {
            this(target, expectedRevision, expected != null, expected, replacement != null, replacement);
        }

        @Override
        public JsonNode expected() {
            return expected == null ? null : expected.deepCopy();
        }

        @Override
        public JsonNode replacement() {
            return replacement == null ? null : replacement.deepCopy();
        }
    }

    public record Request(UUID sessionId, UUID contextId, UUID commandId, long sequence, long baseRevision,
                          String label, List<Change> changes, Operation operation, long historyVersion) {
        public Request {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(contextId, "contextId");
            Objects.requireNonNull(commandId, "commandId");
            Objects.requireNonNull(operation, "operation");
            if (sequence < 1 || baseRevision < 0 || label == null || label.isBlank() || label.length() > 160
                    || label.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("A bounded operation label and valid sequence are required.");
            }
            changes = List.copyOf(changes);
            if (changes.size() > MAX_CHANGES || (operation == Operation.EDIT && changes.isEmpty())
                    || (operation != Operation.EDIT && !changes.isEmpty()) || historyVersion < 0) {
                throw new IllegalArgumentException("The workspace operation exceeds its target limit or is empty.");
            }
            Set<String> targets = new LinkedHashSet<>();
            for (Change change : changes) {
                if (change.expectedRevision() > baseRevision) {
                    throw new IllegalArgumentException("A target revision is ahead of the command baseline.");
                }
                if (!targets.add(change.target())) {
                    throw new IllegalArgumentException("A workspace command must not repeat its targets.");
                }
            }

        }

        public Request(UUID sessionId, UUID contextId, UUID commandId, long sequence, long baseRevision,
                       String label, List<Change> changes) {
            this(sessionId, contextId, commandId, sequence, baseRevision, label, changes, Operation.EDIT, 0);
        }
    }

    public record Result(UUID commandId, long sequence, Status status, String message, State state) {
    }

    /** Called only after candidate validation; application applies through its common host history boundary. */
    public record Commit(String label, Origin origin, DmProject before, DmProject after, Set<String> targets) {
        public Commit {
            targets = Set.copyOf(targets);
        }
    }

    private record Receipt(Request request, Result result) {
    }

    private final Thread owner = Thread.currentThread();
    private final UUID sessionId = UUID.randomUUID();
    private final Supplier<DmProject> currentProject;
    private final Consumer<Commit> apply;
    private final ProjectCodec codec;
    private final Map<Long, Receipt> receipts = new LinkedHashMap<>();
    private final Map<String, Long> changedAt = new LinkedHashMap<>();
    private final Set<String> protectedTargets = new LinkedHashSet<>();
    private UUID contextId = UUID.randomUUID();
    private RemoteProjectDocument document;
    private long revision;
    private long lastSequence;
    private boolean closed;
    private State cachedState;
    private Supplier<History> history = () -> new History(0, null, null);
    private Consumer<Operation> applyHistory;
    private History observedHistory = new History(0, null, null);

    public void configureHistory(Supplier<History> history, Consumer<Operation> applyHistory) {
        requireActive();
        this.history = Objects.requireNonNull(history, "history");
        this.applyHistory = Objects.requireNonNull(applyHistory, "applyHistory");
        cachedState = null;
    }

    public RemoteWorkspaceAuthority(Supplier<DmProject> currentProject, Consumer<Commit> apply) {
        this(currentProject, apply, new ProjectCodec() {
            @Override
            public RemoteProjectDocument capture(DmProject project) {
                return RemoteProjectDocument.capture(project);
            }

            @Override
            public DmProject materialize(RemoteProjectDocument document, DmProject.CameraState dmCamera) throws IOException {
                return document.materialize(dmCamera);
            }
        });
    }

    public RemoteWorkspaceAuthority(Supplier<DmProject> currentProject, Consumer<Commit> apply, ProjectCodec codec) {
        this(currentProject, apply, codec, codec.capture(currentProject.get()));
    }

    RemoteWorkspaceAuthority(Supplier<DmProject> currentProject, Consumer<Commit> apply, ProjectCodec codec,
                             RemoteProjectDocument baseline) {
        this.currentProject = Objects.requireNonNull(currentProject, "currentProject");
        this.apply = Objects.requireNonNull(apply, "apply");
        this.codec = Objects.requireNonNull(codec, "codec");
        document = Objects.requireNonNull(baseline, "baseline");
    }

    /** Observe committed host UI/API/simulation changes at their existing operation boundaries. */
    public boolean observeHostCommit() {
        requireActive();
        return observe(codec.capture(currentProject.get()));
    }

    public boolean observeHostDocument(RemoteProjectDocument committed) {
        requireActive();
        return observe(Objects.requireNonNull(committed, "committed"));
    }

    /** A switch gets a fresh epoch, even if it returns to a previously open map/level. */
    public void switchContext() {
        switchContext(codec.capture(currentProject.get()));
    }

    public void switchContext(RemoteProjectDocument baseline) {
        requireActive();
        cachedState = null;
        contextId = UUID.randomUUID();
        revision++;
        changedAt.clear();
        protectedTargets.clear();
        document = Objects.requireNonNull(baseline, "baseline");
        for (String target : document.targets()) {
            changedAt.put(target, revision);
        }
    }

    /** Lock before a local gesture starts, not only after it commits. Empty clears all gesture locks. */
    public void protectHostTargets(Set<String> targets) {
        requireActive();
        targets.forEach(RemoteProjectDocument::validateTarget);
        protectedTargets.clear();
        protectedTargets.addAll(targets);
    }

    public State state() {
        requireActive();
        History currentHistory = Objects.requireNonNull(history.get(), "history");
        if (!observedHistory.equals(currentHistory)) {
            observedHistory = currentHistory;
            revision++;
            cachedState = null;
        }
        if (cachedState == null) {
            cachedState = new State(sessionId, contextId, revision, lastSequence + 1, document, changedAt,
                    codec.assets(document), observedHistory);
        }
        return cachedState;
    }

    public Result execute(Request request) {
        return execute(request, null, null);
    }

    public Result busy(Request request, String reason) {
        return execute(request, Status.BUSY, Objects.requireNonNull(reason, "reason"));
    }

    public Result reject(Request request, String reason) {
        return execute(request, Status.INVALID, Objects.requireNonNull(reason, "reason"));
    }

    private Result execute(Request request, Status rejection, String rejectionReason) {
        requireActive();
        Objects.requireNonNull(request, "request");
        if (!sessionId.equals(request.sessionId())) {
            return result(request, Status.RESYNC_REQUIRED, "The host workspace session changed.");
        }
        Receipt retained = receipts.get(request.sequence());
        if (retained != null) {
            return retained.request().equals(request) ? retained.result()
                    : result(request, Status.RESYNC_REQUIRED, "A command sequence was reused with different content.");
        }
        if (request.sequence() != lastSequence + 1) {
            return result(request, Status.RESYNC_REQUIRED, request.sequence() <= lastSequence
                    ? "The command receipt expired; its outcome is unknown."
                    : "A preceding workspace command is missing.");
        }
        if (receipts.values().stream().anyMatch(receipt ->
                receipt.request().commandId().equals(request.commandId()))) {
            return result(request, Status.RESYNC_REQUIRED, "A command ID was reused with a different sequence.");
        }

        Status status;
        String message;
        if (rejection != null) {
            status = rejection;
            message = rejectionReason;
        } else if (!contextId.equals(request.contextId())) {
            status = Status.CONFLICT;
            message = "The host switched maps or levels; this edit was not applied.";
        } else if (request.operation() != Operation.EDIT) {
            History current = state().history();
            if (applyHistory == null) {
                status = Status.INVALID;
                message = "Shared history is not installed on this host.";
            } else if (!protectedTargets.isEmpty()) {
                status = Status.BUSY;
                message = "The host is editing; finish the gesture before changing shared history.";
            } else if (request.historyVersion() != current.version()) {
                status = Status.CONFLICT;
                message = "The shared history changed; review the next operation before undoing or redoing.";
            } else if ((request.operation() == Operation.UNDO ? current.undo() : current.redo()) == null) {
                status = Status.INVALID;
                message = "There is no shared history operation to " + request.operation().name().toLowerCase(java.util.Locale.ROOT) + ".";
            } else {
                applyHistory.accept(request.operation());
                observe(codec.capture(currentProject.get()));
                status = Status.ACCEPTED;
                message = "Applied shared " + request.operation().name().toLowerCase(java.util.Locale.ROOT) + " on the host.";
            }
        } else {
            try {
                Set<String> targets = new LinkedHashSet<>();
                Map<String, JsonNode> replacements = new LinkedHashMap<>();
                Set<String> removals = new LinkedHashSet<>();
                if (request.baseRevision() > revision) {
                    throw new IllegalArgumentException("The remote workspace revision is ahead of the host.");
                }
                for (Change change : request.changes()) {
                    String target = change.target();
                    targets.add(target);
                    if (isProtected(target)) {
                        throw new HostBusy("The host is editing this target; the remote edit was not applied.");
                    }
                    if (changedAt.getOrDefault(target, 0L) != change.expectedRevision()
                            || !Objects.equals(document.value(target), change.expected())) {
                        throw new Conflict("This target changed on the host: " + target);
                    }
                    if (target.startsWith("fogBits:")
                            && changedAt.getOrDefault("fog:geometry", 0L)
                            > request.baseRevision()) {
                        throw new Conflict("The host changed the fog geometry; synchronize before editing fog.");
                    }
                    if (target.equals("fog:geometry") && changedAt.entrySet().stream().anyMatch(entry ->
                            entry.getKey().startsWith("fogBits:") && entry.getValue() > request.baseRevision())) {
                        throw new Conflict("The host changed the fog content; synchronize before resizing fog.");
                    }
                    if (change.replacement() == null) {
                        removals.add(target);
                    } else {
                        replacements.put(target, change.replacement());
                    }
                }
                RemoteProjectDocument candidate = document.with(replacements, removals);
                DmProject live = currentProject.get();
                DmProject completed = codec.materialize(candidate, live.getViews().getDmCamera());
                codec.complete(codec.materialize(document, live.getViews().getDmCamera()), completed);
                RemoteProjectDocument normalized = codec.capture(completed);
                Set<String> derived = new LinkedHashSet<>(candidate.targets());
                derived.addAll(normalized.targets());
                for (String target : derived) {
                    JsonNode value = normalized.value(target);
                    if (Objects.equals(candidate.value(target), value)) {
                        continue;
                    }
                    if (isProtected(target)) {
                        throw new HostBusy("The host is editing an affected simulation target; retry after the gesture.");
                    }
                    targets.add(target);
                    if (value == null) {
                        replacements.remove(target);
                        removals.add(target);
                    } else {
                        replacements.put(target, value);
                        removals.remove(target);
                    }
                }
                codec.materialize(normalized, live.getViews().getDmCamera());
                RemoteProjectDocument liveDocument = codec.capture(live);
                DmProject before = codec.materialize(liveDocument, live.getViews().getDmCamera());
                DmProject after = codec.materialize(liveDocument.with(replacements, removals), live.getViews().getDmCamera());
                apply.accept(new Commit(request.label(), Origin.REMOTE, before, after, targets));
                observe(normalized); // Do not publish an unrelated unfinished host gesture.
                status = Status.ACCEPTED;
                message = "Applied on the host: " + request.label();
            } catch (HostBusy busy) {
                status = Status.BUSY;
                message = busy.getMessage();
            } catch (Conflict conflict) {
                status = Status.CONFLICT;
                message = conflict.getMessage();
            } catch (IOException | IllegalArgumentException invalid) {
                status = Status.INVALID;
                message = "Invalid remote project edit; nothing was applied.";
            }
        }
        lastSequence = request.sequence();
        cachedState = null;
        Result result = result(request, status, message);
        receipts.put(request.sequence(), new Receipt(request, result));
        while (receipts.size() > MAX_RECEIPTS) {
            receipts.remove(receipts.keySet().iterator().next());
        }
        return result;
    }

    public static Request diff(State baseline, RemoteProjectDocument edited, String label) {
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(edited, "edited");
        if (!baseline.document().projectId().equals(edited.projectId())) {
            throw new IllegalArgumentException("A project switch is not an edit to the previous map.");
        }

        Set<String> targets = new LinkedHashSet<>(baseline.document().targets());
        targets.addAll(edited.targets());
        List<Change> changes = new ArrayList<>();
        for (String target : targets) {
            JsonNode before = baseline.document().value(target);
            JsonNode after = edited.value(target);
            if (!Objects.equals(before, after)) {
                changes.add(new Change(target, baseline.changedAt().getOrDefault(target, 0L), before, after));
            }
        }
        return new Request(baseline.sessionId(), baseline.contextId(), UUID.randomUUID(),
                baseline.nextSequence(), baseline.revision(), label, changes);
    }

    public static Request historyRequest(State baseline, Operation operation) {
        if (operation == Operation.EDIT) {
            throw new IllegalArgumentException("An undo or redo operation is required.");
        }
        return new Request(baseline.sessionId(), baseline.contextId(), UUID.randomUUID(),
                baseline.nextSequence(), baseline.revision(), operation == Operation.UNDO ? "Undo" : "Redo",
                List.of(), operation, baseline.history().version());
    }

    @Override
    public void close() {
        requireOwner();
        closed = true;
        receipts.clear();
        protectedTargets.clear();
        changedAt.clear();
    }

    private boolean observe(RemoteProjectDocument next) {
        if (!document.projectId().equals(next.projectId())) {
            throw new IllegalStateException("A map switch must use the workspace context boundary.");
        }
        if (document.equals(next)) {
            return false;
        }
        Set<String> changed = new LinkedHashSet<>(document.targets());
        changed.addAll(next.targets());
        changed.removeIf(target -> Objects.equals(document.value(target), next.value(target)));
        if (changedAt.size() + changed.size() > MAX_TOMBSTONES) {
            // Rotate the context rather than drop tombstones and accidentally accept an old command.
            contextId = UUID.randomUUID();
            changedAt.clear();
        }
        revision++;
        cachedState = null;
        for (String target : changed) {
            changedAt.put(target, revision);
        }
        document = next;
        return true;
    }

    private boolean isProtected(String target) {
        return protectedTargets.contains(target)
                || (target.startsWith("fogBits:") && protectedTargets.contains("fog:geometry"))
                || (target.equals("fog:geometry") && protectedTargets.stream().anyMatch(t -> t.startsWith("fogBits:")));
    }

    private Result result(Request request, Status status, String message) {
        return new Result(request.commandId(), request.sequence(), status, message, state());
    }

    private void requireActive() {
        requireOwner();
        if (closed) {
            throw new IllegalStateException("The host workspace session is closed.");
        }
    }

    private void requireOwner() {
        if (owner != Thread.currentThread()) {
            throw new IllegalStateException("Workspace commands must run on the application owner thread.");
        }
    }

    private static final class Conflict extends RuntimeException {
        Conflict(String message) {
            super(message);
        }
    }

    private static final class HostBusy extends RuntimeException {
        HostBusy(String message) {
            super(message);
        }
    }
}
