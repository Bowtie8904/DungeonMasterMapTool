package dmmt.remote;

import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.UUID;

/** Bounded diagnostics sent only over an authenticated session, never from protocol parsing. */
public record RemoteFailure(UUID id, String message, String stackTrace) {
    public static final int MAX_TRACE = 32768;

    public RemoteFailure {
        if (id == null || message == null || message.isBlank() || message.length() > 1000
                || stackTrace == null || stackTrace.length() > MAX_TRACE) {
            throw new IllegalArgumentException("Invalid remote failure details.");
        }
    }

    public static RemoteFailure capture(String context, Throwable failure) {
        Throwable root = unwrap(failure);
        String detail = description(root);
        String message = bounded(context + (detail.isBlank() ? "" : "\n" + detail), 1000);
        StringBuilder trace = new StringBuilder();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        append(trace, root, "", visited);
        return new RemoteFailure(UUID.randomUUID(), message, bounded(trace.toString(), MAX_TRACE));
    }

    public static Throwable unwrap(Throwable failure) {
        while ((failure instanceof java.util.concurrent.CompletionException
                || failure instanceof java.util.concurrent.ExecutionException) && failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure;
    }

    private static String description(Throwable failure) {
        // Jackson errors can echo entire incoming messages, including authentication secrets.
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof com.fasterxml.jackson.core.JacksonException) {
                return "Invalid serialized data; payload omitted.";
            }
        }
        if (!(failure instanceof IOException || failure instanceof IllegalArgumentException
                || failure instanceof IllegalStateException)) {
            return failure.getClass().getSimpleName();
        }
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message
                .replaceAll("(?i)((?:credential|password|pairing[ _-]*code|token|secret)\\s*[=:]\\s*)[^\\s,;]+", "$1[redacted]");
    }

    private static void append(StringBuilder trace, Throwable failure, String prefix, Set<Throwable> visited) {
        if (failure == null || trace.length() >= MAX_TRACE || !visited.add(failure)) return;
        trace.append(prefix).append(failure.getClass().getName()).append(": ")
                .append(bounded(description(failure), 1000)).append('\n');
        for (StackTraceElement frame : failure.getStackTrace()) {
            if (trace.length() >= MAX_TRACE) break;
            trace.append("\tat ").append(frame).append('\n');
        }
        for (Throwable suppressed : failure.getSuppressed()) append(trace, suppressed, "Suppressed: ", visited);
        append(trace, failure.getCause(), "Caused by: ", visited);
    }

    private static String bounded(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit - 16) + "\n[truncated]";
    }

    public IOException exception() {
        return new HostException(this);
    }

    public static final class HostException extends IOException {
        private final RemoteFailure details;

        private HostException(RemoteFailure details) {
            super(details.message());
            this.details = details;
        }

        public RemoteFailure details() {
            return details;
        }
    }
}
