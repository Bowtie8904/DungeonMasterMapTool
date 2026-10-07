package dmmt.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Loopback-only HTTP transport for the local DM control API (JDK {@code jdk.httpserver} module).
 * <p>
 * Validates and decodes requests, then calls the {@link Handler} synchronously on a bounded worker pool and
 * serializes its result as JSON. Routing and JavaFX dispatch are left to the handler.
 */
public final class LocalApiServer implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(LocalApiServer.class.getName());
    private static final byte[] LOOPBACK = {127, 0, 0, 1};
    private static final int DEFAULT_THREADS = 4;
    private static final int DEFAULT_QUEUE = 16;
    static final int MAX_URI_LENGTH = 4096;
    static final int MAX_PARAMETERS = 32;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Handles a validated request; runs on an HTTP worker thread. Throw {@link ApiException} for client errors. */
    @FunctionalInterface
    public interface Handler {
        Object handle(String path, Map<String, String> query);
    }

    /** An explicit HTTP error returned to the client as {@code {"status":..,"error":..}}. */
    public static final class ApiException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int status;

        public ApiException(int status, String message) {
            super(message);
            if (status < 400 || status > 599) {
                throw new IllegalArgumentException("API error status must be 4xx or 5xx: " + status);
            }
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private final int port;
    private final Handler handler;
    private final int threads;
    private final int queueCapacity;
    private HttpServer server;
    private ThreadPoolExecutor executor;
    private boolean started;
    private volatile int boundPort;

    public LocalApiServer(int port, Handler handler) {
        this(port, handler, DEFAULT_THREADS, DEFAULT_QUEUE);
    }

    LocalApiServer(int port, Handler handler, int threads, int queueCapacity) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("API port must be between 0 and 65535: " + port);
        }
        this.port = port;
        this.handler = Objects.requireNonNull(handler, "handler");
        this.threads = threads;
        this.queueCapacity = queueCapacity;
        this.boundPort = port;
    }

    /** Binds to 127.0.0.1 only. Port 0 picks a free port (see {@link #baseUrl()}). Can be started once. */
    public synchronized void start() throws IOException {
        if (started) {
            throw new IllegalStateException("Local API server can only be started once.");
        }
        started = true;
        HttpServer created = HttpServer.create(
                new InetSocketAddress(InetAddress.getByAddress(LOOPBACK), port), 16);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity), new WorkerThreads(),
                new ThreadPoolExecutor.AbortPolicy());
        pool.allowCoreThreadTimeOut(true);
        created.setExecutor(pool);
        created.createContext("/", this::exchange);
        created.start();
        server = created;
        executor = pool;
        boundPort = created.getAddress().getPort();
    }

    /** Stops listening and the worker pool. Safe to call repeatedly or without a successful start. */
    @Override
    public synchronized void close() {
        started = true;
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    LOG.log(System.Logger.Level.WARNING, "Local API workers did not stop within 2 seconds.");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            executor = null;
        }
    }

    /** {@code http://127.0.0.1:<port>}, using the actually bound port once started. */
    public String baseUrl() {
        return "http://127.0.0.1:" + boundPort;
    }

    /**
     * Builds a request URL: each path segment and query key/value is percent-encoded as UTF-8 (spaces as
     * {@code %20}); unreserved characters ({@code A-Z a-z 0-9 - . _ ~}) are kept. Query order is preserved.
     */
    public static String url(String baseUrl, String path, Map<String, String> query) {
        StringBuilder out = new StringBuilder(baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        String[] segments = path.split("/", -1);
        for (int i = path.startsWith("/") ? 1 : 0; i < segments.length; i++) {
            out.append('/').append(encode(segments[i]));
        }
        if (query != null && !query.isEmpty()) {
            char separator = '?';
            for (Map.Entry<String, String> entry : query.entrySet()) {
                out.append(separator).append(encode(entry.getKey())).append('=')
                        .append(encode(entry.getValue() == null ? "" : entry.getValue()));
                separator = '&';
            }
        }
        return out.toString();
    }

    static String encode(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~') {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return out.toString();
    }

    private void exchange(HttpExchange exchange) {
        try (exchange) {
            int status;
            byte[] body;
            try {
                Request request = validate(exchange);
                Object result = handler.handle(request.path(), request.query());
                body = JSON.writeValueAsBytes(result == null ? Map.of("ok", true) : result);
                status = 200;
            } catch (ApiException ex) {
                status = ex.status();
                body = errorBody(status, ex.getMessage());
            } catch (Exception ex) {
                LOG.log(System.Logger.Level.ERROR, "Local API request failed: " + describe(exchange), ex);
                status = 500;
                body = errorBody(status, "Internal error: " + ex.getClass().getSimpleName()
                        + (ex.getMessage() == null ? "" : " - " + ex.getMessage()));
            }
            respond(exchange, status, body);
        } catch (IOException ex) {
            LOG.log(System.Logger.Level.DEBUG, "Local API response could not be sent.", ex);
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.ERROR, "Local API response failed.", ex);
        }
    }

    private Request validate(HttpExchange exchange) {
        InetSocketAddress remote = exchange.getRemoteAddress();
        if (remote == null || remote.getAddress() == null || !remote.getAddress().isLoopbackAddress()) {
            throw new ApiException(403, "Only loopback clients are allowed.");
        }
        Headers headers = exchange.getRequestHeaders();
        validateHost(headers.get("Host"));
        if (headers.containsKey("Origin") || headers.containsKey("Referer")) {
            throw new ApiException(403, "Browser-origin requests are not allowed.");
        }
        List<String> fetchSite = headers.get("Sec-Fetch-Site");
        if (fetchSite != null && !(fetchSite.size() == 1 && fetchSite.getFirst().trim().equalsIgnoreCase("none"))) {
            throw new ApiException(403, "Cross-site browser requests are not allowed.");
        }
        String method = exchange.getRequestMethod();
        if (!method.equals("GET") && !method.equals("POST")) {
            exchange.getResponseHeaders().set("Allow", "GET, POST");
            throw new ApiException(405, "Method not allowed; use GET or POST.");
        }
        rejectBody(exchange, headers);
        URI uri = exchange.getRequestURI();
        if (uri.isAbsolute() || uri.getRawAuthority() != null) {
            throw new ApiException(400, "Absolute request URIs are not supported.");
        }
        String rawPath = uri.getRawPath();
        String rawQuery = uri.getRawQuery();
        int length = (rawPath == null ? 0 : rawPath.length()) + (rawQuery == null ? 0 : rawQuery.length() + 1);
        if (length > MAX_URI_LENGTH) {
            throw new ApiException(414, "Request URI is too long.");
        }
        return new Request(decodePath(rawPath), decodeQuery(rawQuery));
    }

    private void validateHost(List<String> hosts) {
        if (hosts == null || hosts.size() != 1) {
            throw new ApiException(400, "Exactly one Host header is required.");
        }
        String host = hosts.getFirst().trim().toLowerCase(Locale.ROOT);
        String suffix = ":" + boundPort;
        boolean valid = host.equals("127.0.0.1" + suffix) || host.equals("localhost" + suffix)
                || (boundPort == 80 && (host.equals("127.0.0.1") || host.equals("localhost")));
        if (!valid) {
            throw new ApiException(403, "Host must be 127.0.0.1 or localhost with port " + boundPort + ".");
        }
    }

    private static void rejectBody(HttpExchange exchange, Headers headers) {
        if (headers.containsKey("Transfer-Encoding")) {
            throw new ApiException(413, "Request bodies are not supported.");
        }
        List<String> lengths = headers.get("Content-Length");
        if (lengths != null) {
            for (String value : lengths) {
                if (!value.trim().equals("0")) {
                    throw new ApiException(413, "Request bodies are not supported.");
                }
            }
        }
        try {
            exchange.getRequestBody().close();
        } catch (IOException ex) {
            throw new ApiException(400, "Could not read request.");
        }
    }

    static String decodePath(String rawPath) {
        if (rawPath == null || !rawPath.startsWith("/")) {
            throw new ApiException(400, "Request path must start with '/'.");
        }
        String lower = rawPath.toLowerCase(Locale.ROOT);
        if (lower.contains("%2f") || lower.contains("%5c") || rawPath.contains("\\")) {
            throw new ApiException(400, "Encoded slashes are not allowed in the path.");
        }
        if (rawPath.length() > 1) {
            for (String segment : rawPath.substring(1).split("/", -1)) {
                if (segment.isEmpty()) {
                    throw new ApiException(400, "Empty path segments are not allowed.");
                }
                String decoded = decode(segment, false);
                if (decoded.equals(".") || decoded.equals("..")) {
                    throw new ApiException(400, "Dot path segments are not allowed.");
                }
            }
        }
        return decode(rawPath, false);
    }

    static Map<String, String> decodeQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return Map.of();
        }
        String[] pairs = rawQuery.split("&", -1);
        if (pairs.length > MAX_PARAMETERS) {
            throw new ApiException(400, "Too many query parameters.");
        }
        Map<String, String> query = new LinkedHashMap<>();
        for (String pair : pairs) {
            if (pair.isEmpty()) {
                throw new ApiException(400, "Empty query parameter.");
            }
            int eq = pair.indexOf('=');
            String key = decode(eq < 0 ? pair : pair.substring(0, eq), true);
            String value = eq < 0 ? "" : decode(pair.substring(eq + 1), true);
            if (key.isEmpty()) {
                throw new ApiException(400, "Query parameter name is empty.");
            }
            if (query.putIfAbsent(key, value) != null) {
                throw new ApiException(400, "Duplicate query parameter: " + key);
            }
        }
        return Collections.unmodifiableMap(query);
    }

    /** Strict UTF-8 percent-decoding; {@code +} means space only in query components. */
    static String decode(String raw, boolean plusIsSpace) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '%') {
                int hi = i + 2 < raw.length() ? Character.digit(raw.charAt(i + 1), 16) : -1;
                int lo = hi >= 0 ? Character.digit(raw.charAt(i + 2), 16) : -1;
                if (lo < 0) {
                    throw new ApiException(400, "Invalid percent-encoding in request URI.");
                }
                bytes.write((hi << 4) | lo);
                i += 2;
            } else if (c == '+' && plusIsSpace) {
                bytes.write(' ');
            } else if (c > 0x20 && c < 0x7F) {
                bytes.write(c);
            } else {
                throw new ApiException(400, "Request URI contains unencoded characters.");
            }
        }
        String decoded;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString();
        } catch (CharacterCodingException ex) {
            throw new ApiException(400, "Request URI is not valid UTF-8.");
        }
        for (int i = 0; i < decoded.length(); i++) {
            if (Character.isISOControl(decoded.charAt(i))) {
                throw new ApiException(400, "Control characters are not allowed in the request URI.");
            }
        }
        return decoded;
    }

    private static byte[] errorBody(int status, String message) {
        ObjectNode node = JSON.createObjectNode();
        node.put("status", status);
        node.put("error", message == null ? "Request failed." : message);
        try {
            return JSON.writeValueAsBytes(node);
        } catch (IOException ex) {
            return ("{\"status\":" + status + "}").getBytes(StandardCharsets.UTF_8);
        }
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static String describe(HttpExchange exchange) {
        return exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath();
    }

    private record Request(String path, Map<String, String> query) {
    }

    private static final class WorkerThreads implements ThreadFactory {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "dmmt-local-api-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
