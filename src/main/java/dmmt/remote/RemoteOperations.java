package dmmt.remote;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Explicit domain operations with one bounded exact-receipt mutation stream per host session. */
public final class RemoteOperations implements AutoCloseable {
    public interface Endpoint {
        CompletableFuture<JsonNode> query(String action, JsonNode payload);
        CompletableFuture<JsonNode> execute(String action, JsonNode payload);
    }

    public interface Connection {
        boolean connected();
        CompletableFuture<JsonNode> query(String domain, String action, JsonNode payload);
        CompletableFuture<JsonNode> command(String domain, String action, JsonNode payload);
        CompletableFuture<RemoteAssetStore.AssetDescriptor> upload(Path file);
        CompletableFuture<Path> download(RemoteAssetStore.AssetDescriptor asset);
        /** Downloads an asset and names it in the transfer overlay. */
        default CompletableFuture<Path> download(RemoteAssetStore.AssetDescriptor asset, String label) {
            return download(asset);
        }
        default java.util.List<RemoteSessionClient.TransferProgress> transferProgress() { return java.util.List.of(); }
    }

    public record Command(UUID sessionId, UUID id, @JsonProperty(required = true) long sequence,
                          String domain, String action, JsonNode payload) {
        public Command {
            Objects.requireNonNull(sessionId);
            Objects.requireNonNull(id);
            if (sequence < 1) throw new IllegalArgumentException("Invalid host operation sequence.");
            identifier(domain);
            identifier(action);
            payload = body(payload);
        }
        @Override public JsonNode payload() { return payload.deepCopy(); }
    }

    public record Query(Command command, String domain, String action, JsonNode payload) {
        public Query {
            if ((domain == null) != (action == null) || command != null && domain != null) {
                throw new IllegalArgumentException("Choose exactly one host operation.");
            }
            if (domain != null) {
                identifier(domain);
                identifier(action);
                payload = body(payload);
            } else if (payload != null && !payload.isNull()) {
                throw new IllegalArgumentException("Unexpected host operation payload.");
            }
        }
        @Override public JsonNode payload() { return payload == null ? null : payload.deepCopy(); }
    }

    public enum Status { ACCEPTED, RUNNING, FAILED, RESYNC_REQUIRED }

    public record Reply(UUID sessionId, @JsonProperty(required = true) long nextSequence, UUID commandId,
                        @JsonProperty(required = true) long sequence, Status status, JsonNode value, String error,
                        RemoteFailure failure) {
        public Reply(UUID sessionId, long nextSequence, UUID commandId, long sequence, Status status,
                     JsonNode value, String error) {
            this(sessionId, nextSequence, commandId, sequence, status, value, error, null);
        }

        public Reply {
            Objects.requireNonNull(sessionId);
            Objects.requireNonNull(status);
            if (nextSequence < 1 || sequence < 0 || commandId == null && sequence != 0
                    || commandId != null && sequence < 1 || error != null && (error.isBlank() || error.length() > 500)) {
                throw new IllegalArgumentException("Invalid host operation reply.");
            }
            value = value == null ? NullNode.getInstance() : body(value);
        }
        @Override public JsonNode value() { return value.deepCopy(); }
    }

    private record Stored(Command command, Reply reply, long bytes) { }
    private final Thread ownerThread = Thread.currentThread();
    private final UUID session = UUID.randomUUID();
    private final Map<String, Endpoint> domains = new LinkedHashMap<>();
    private final LinkedHashMap<Long, Stored> receipts = new LinkedHashMap<>();
    private final Executor owner;
    private long nextSequence = 1;
    private Command running;
    private CompletableFuture<Reply> pending;
    private boolean closed;
    private long receiptBytes;

    public RemoteOperations(Executor owner) { this.owner = Objects.requireNonNull(owner); }

    public void register(String domain, Endpoint endpoint) {
        requireOwner();
        identifier(domain);
        if (closed || domains.putIfAbsent(domain, Objects.requireNonNull(endpoint)) != null) {
            throw new IllegalStateException("Host operation domain is closed or already registered.");
        }
    }

    public CompletableFuture<Reply> exchange(Query query, Runnable authorize) {
        requireOwner();
        if (closed) return CompletableFuture.failedFuture(new IOException("Host operations stopped."));
        authorize.run();
        if (query.command() == null) {
            if (query.domain() == null) return CompletableFuture.completedFuture(reply(null, Status.ACCEPTED, null, null));
            Endpoint endpoint = domains.get(query.domain());
            if (endpoint == null) return CompletableFuture.completedFuture(reply(null, Status.FAILED, null, "Host operation domain is unavailable."));
            try {
                return endpoint.query(query.action(), query.payload()).handleAsync((value, failure) -> {
                    requireOwner();
                    if (closed) throw new java.util.concurrent.CompletionException(new IOException("Host operations stopped."));
                    return failure == null ? reply(null, Status.ACCEPTED, value, null)
                            : failedReply(null, failure);
                }, owner);
            } catch (RuntimeException failure) {
                return CompletableFuture.completedFuture(failedReply(null, failure));
            }
        }
        var command = query.command();
        if (!session.equals(command.sessionId())) return completed(command, Status.RESYNC_REQUIRED, "Host operation session changed; the command was not repeated.");
        var stored = receipts.get(command.sequence());
        if (stored != null) return stored.command().equals(command)
                ? CompletableFuture.completedFuture(stored.reply())
                : completed(command, Status.RESYNC_REQUIRED, "The host operation receipt does not match.");
        if (command.equals(running)) return completed(command, Status.RUNNING, null);
        if (command.sequence() != nextSequence || running != null
                || receipts.values().stream().anyMatch(receipt -> receipt.command().id().equals(command.id()))) {
            return completed(command, Status.RESYNC_REQUIRED, "Host operation receipt unavailable. Inspect A before repeating the action.");
        }
        Endpoint endpoint = domains.get(command.domain());
        if (endpoint == null) return remember(command, null, new IOException("Host operation domain is unavailable."));
        running = command;
        pending = new CompletableFuture<>();
        var completion = pending;
        try {
            endpoint.execute(command.action(), command.payload()).whenCompleteAsync((value, failure) -> {
                requireOwner();
                if (closed) return;
                running = null;
                pending = null;
                completion.complete(remember(command, value, failure).join());
            }, owner);
        } catch (RuntimeException failure) {
            running = null;
            pending = null;
            completion.complete(remember(command, null, failure).join());
        }
        return completion.isDone() ? completion.copy() : completed(command, Status.RUNNING, null);
    }

    private CompletableFuture<Reply> remember(Command command, JsonNode value, Throwable failure) {
        nextSequence++;
        Reply response;
        try {
            response = failure == null ? reply(command, Status.ACCEPTED, value, null) : failedReply(command, failure);
        } catch (IllegalArgumentException invalid) {
            response = failedReply(command, invalid);
        }
        long bytes = metadataBytes(command.payload()) + metadataBytes(response.value())
                + (response.failure() == null ? 0 : response.failure().stackTrace().length() * 3L + 4096);
        receipts.put(command.sequence(), new Stored(command, response, bytes));
        receiptBytes += bytes;
        while (receipts.size() > 256 || receiptBytes > 64L * 1024 * 1024) {
            receiptBytes -= receipts.remove(receipts.keySet().iterator().next()).bytes();
        }
        return CompletableFuture.completedFuture(response);
    }

    private CompletableFuture<Reply> completed(Command command, Status status, String error) {
        return CompletableFuture.completedFuture(reply(command, status, null, error));
    }

    private Reply reply(Command command, Status status, JsonNode value, String error) {
        return new Reply(session, nextSequence, command == null ? null : command.id(),
                command == null ? 0 : command.sequence(), status, value, error);
    }

    private Reply failedReply(Command command, Throwable failure) {
        String message = describe(failure);
        return new Reply(session, nextSequence, command == null ? null : command.id(),
                command == null ? 0 : command.sequence(), Status.FAILED, NullNode.getInstance(), message,
                RemoteFailure.capture("Host " + (command == null ? "query" : command.domain() + "/" + command.action())
                        + " failed.", failure));
    }

    static String describe(Throwable failure) {
        while ((failure instanceof java.util.concurrent.CompletionException
                || failure instanceof java.util.concurrent.ExecutionException) && failure.getCause() != null) failure = failure.getCause();
        System.getLogger(RemoteOperations.class.getName()).log(System.Logger.Level.WARNING, "Host operation failed.", failure);
        String detail = RemoteFailure.capture("", failure).message().strip();
        detail = detail.replaceAll("\\p{Cntrl}", " ");
        return detail.substring(0, Math.min(500, detail.length()));
    }

    private static void identifier(String value) {
        if (value == null || !value.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("Invalid host operation identifier.");
        }
    }

    private static JsonNode body(JsonNode value) {
        Objects.requireNonNull(value, "payload");
        if (metadataBytes(value) > 24 * 1024 * 1024) throw new IllegalArgumentException("Host operation exceeds its metadata limit.");
        return value.deepCopy();
    }

    private static int metadataBytes(JsonNode value) {
        return value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    @Override public void close() {
        requireOwner();
        closed = true;
        if (pending != null) pending.completeExceptionally(new IOException("Hosting stopped; an accepted operation may still finish on A."));
        receipts.clear();
        receiptBytes = 0;
        for (Endpoint endpoint : domains.values()) {
            if (endpoint instanceof AutoCloseable closeable) {
                try { closeable.close(); }
                catch (Exception failure) {
                    System.getLogger(RemoteOperations.class.getName()).log(
                            System.Logger.Level.WARNING, "Could not close a host operation domain.", failure);
                }
            }
        }
        domains.clear();
    }

    private void requireOwner() {
        if (Thread.currentThread() != ownerThread) throw new IllegalStateException("Host operations require the application thread.");
    }
}
