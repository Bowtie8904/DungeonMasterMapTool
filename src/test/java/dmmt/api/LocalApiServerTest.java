package dmmt.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LocalApiServerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final AtomicReference<Map<String, String>> lastQuery = new AtomicReference<>();
    private LocalApiServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    private LocalApiServer start(LocalApiServer.Handler handler) throws IOException {
        server = new LocalApiServer(0, handler);
        server.start();
        return server;
    }

    private LocalApiServer startRecording(Object result) throws IOException {
        return start((path, query) -> {
            calls.add(path);
            lastQuery.set(query);
            return result;
        });
    }

    private HttpResponse<String> get(String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private int port() {
        return URI.create(server.baseUrl()).getPort();
    }

    /** Sends a raw HTTP request; returns the status code and body, or -1 if the connection closed without a reply. */
    private Raw raw(String request) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port())) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            in.transferTo(bytes);
            String text = bytes.toString(StandardCharsets.UTF_8);
            if (text.isEmpty()) {
                return new Raw(-1, "", "");
            }
            int split = text.indexOf("\r\n\r\n");
            String head = split < 0 ? text : text.substring(0, split);
            return new Raw(Integer.parseInt(head.split(" ")[1]), head, split < 0 ? "" : text.substring(split + 4));
        }
    }

    private Raw rawGet(String target, String... extraHeaders) throws IOException {
        StringBuilder request = new StringBuilder("GET " + target + " HTTP/1.1\r\nHost: 127.0.0.1:" + port() + "\r\n");
        for (String header : extraHeaders) {
            request.append(header).append("\r\n");
        }
        return raw(request.append("Connection: close\r\n\r\n").toString());
    }

    private record Raw(int status, String head, String body) {
    }

    private static void assertJsonError(int status, String body) throws IOException {
        JsonNode node = JSON.readTree(body);
        assertEquals(status, node.get("status").asInt());
        assertFalse(node.get("error").asText().isBlank());
    }

    @Test
    void getRunsHandlerOnWorkerAndReturnsJson() throws Exception {
        AtomicReference<String> thread = new AtomicReference<>();
        start((path, query) -> {
            thread.set(Thread.currentThread().getName());
            lastQuery.set(query);
            return Map.of("path", path, "value", query.get("value"));
        });
        Map<String, String> query = new LinkedHashMap<>();
        query.put("value", "#FF0000 a+b/ü&=");
        HttpResponse<String> response = get(LocalApiServer.url(server.baseUrl(), "/api/controls/effects/color", query));

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
        assertTrue(response.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
        JsonNode body = JSON.readTree(response.body());
        assertEquals("/api/controls/effects/color", body.get("path").asText());
        assertEquals("#FF0000 a+b/ü&=", body.get("value").asText());
        assertEquals(Map.of("value", "#FF0000 a+b/ü&="), lastQuery.get());
        assertTrue(thread.get().startsWith("dmmt-local-api-"));
    }

    @Test
    void httpUrlConnectionGetWorksForDocumentedUrlsOnBothLoopbackNames() throws Exception {
        startRecording(Map.of("ok", true));
        for (String host : List.of("127.0.0.1", "localhost")) {
            for (String target : List.of("/api/controls/lighting/night", "/api/controls/effects/color?value=%23FF0000",
                    "/api/controls/effects/opacity?increment=0.1", "/api/maps/00000000-0000-0000-0000-000000000000/switch?level=0")) {
                java.net.HttpURLConnection connection = (java.net.HttpURLConnection)
                        URI.create("http://" + host + ":" + port() + target).toURL().openConnection();
                connection.setRequestMethod("GET");
                assertEquals(200, connection.getResponseCode(), host + target);
                try (InputStream in = connection.getInputStream()) {
                    assertTrue(JSON.readTree(in).get("ok").asBoolean());
                }
                connection.disconnect();
            }
        }
        assertEquals(Map.of("level", "0"), lastQuery.get());
        assertTrue(calls.contains("/api/controls/effects/color"));

        java.net.HttpURLConnection browser = (java.net.HttpURLConnection)
                URI.create(server.baseUrl() + "/api/controls/lighting/night").toURL().openConnection();
        browser.setRequestProperty("Referer", "http://evil.example/"); // Origin/Sec-Fetch-* are dropped by HttpURLConnection; raw tests cover them
        assertEquals(403, browser.getResponseCode());
        browser.disconnect();
    }

    @Test
    void apiExceptionIsNotAnIllegalArgumentException() {
        assertFalse(IllegalArgumentException.class.isAssignableFrom(LocalApiServer.ApiException.class));
        assertEquals(RuntimeException.class, LocalApiServer.ApiException.class.getSuperclass());
    }
    @Test
    void localhostHostAndPlusAndEmptyValuesAreAccepted() throws Exception {
        startRecording(List.of(1, 2));
        HttpResponse<String> response = get("http://localhost:" + port() + "/api/x?a=1+2&b&c=%2B");
        assertEquals(200, response.statusCode());
        assertEquals("[1,2]", response.body());
        assertEquals(Map.of("a", "1 2", "b", "", "c", "+"), lastQuery.get());
    }

    @Test
    void postWithoutBodyIsAcceptedAndNullResultIsOk() throws Exception {
        startRecording(null);
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/state"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(JSON.readTree(response.body()).get("ok").asBoolean());
        assertEquals(List.of("/api/state"), calls);
    }

    @Test
    void requestBodiesAreRejected() throws Exception {
        startRecording("x");
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/state"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"a\":1}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(413, response.statusCode());
        assertJsonError(413, response.body());
        assertTrue(calls.isEmpty());
    }

    @Test
    void unsupportedMethodsAreRejectedWithoutCors() throws Exception {
        startRecording("x");
        for (String method : List.of("PUT", "DELETE", "OPTIONS", "PATCH")) {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/state"))
                    .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(405, response.statusCode(), method);
            assertEquals("GET, POST", response.headers().firstValue("Allow").orElseThrow());
            assertTrue(response.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
            assertJsonError(405, response.body());
        }
        HttpResponse<Void> head = client.send(HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/state"))
                .HEAD().build(), HttpResponse.BodyHandlers.discarding());
        assertEquals(405, head.statusCode());
        assertTrue(calls.isEmpty());
    }

    @Test
    void browserOriginRequestsAreRejected() throws Exception {
        startRecording("x");
        for (String header : List.of("Origin: http://evil.example", "Origin: null", "Referer: http://evil.example/",
                "Sec-Fetch-Site: cross-site", "Sec-Fetch-Site: same-site", "Sec-Fetch-Site: same-origin")) {
            Raw response = rawGet("/api/state", header);
            assertEquals(403, response.status(), header);
            assertJsonError(403, response.body());
            assertFalse(response.head().toLowerCase().contains("access-control"), header);
        }
        assertTrue(calls.isEmpty());
        assertEquals(200, rawGet("/api/state", "Sec-Fetch-Site: none").status());
    }

    @Test
    void hostHeaderMustNameLocalInterfaceAndBoundPort() throws Exception {
        startRecording("x");
        int port = port();
        for (String host : List.of("evil.example:" + port, "127.0.0.1:" + (port == 1 ? 2 : port - 1), "127.0.0.1",
                "localhost.evil.example:" + port, "[::1]:" + port)) {
            Raw response = raw("GET /api/state HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n");
            assertEquals(403, response.status(), host);
            assertJsonError(403, response.body());
        }
        assertEquals(400, raw("GET /api/state HTTP/1.0\r\n\r\n").status());
        Raw duplicate = raw("GET /api/state HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nHost: evil.example\r\n"
                + "Connection: close\r\n\r\n");
        assertTrue(duplicate.status() >= 400, "duplicate Host must fail: " + duplicate.status());
        assertTrue(calls.isEmpty());
        assertEquals(200, raw("GET /api/state HTTP/1.1\r\nHost: LOCALHOST:" + port + "\r\nConnection: close\r\n\r\n").status());
        assertEquals(400, rawGet("http://127.0.0.1:" + port + "/api/state").status());
    }

    @Test
    void malformedQueriesAndPathsAreRejected() throws Exception {
        startRecording("x");
        for (String target : List.of("/api/x?value=1&value=2", "/api/x?value=%G1", "/api/x?value=%4", "/api/x?value=%",
                "/api/x?value=%C3%28", "/api/x?value=%00", "/api/x?a=1&&b=2", "/api/x?=1", "/api/x?%61=1&a=2",
                "/api/a%2Fb", "/api/a%5cb", "/api//x", "/api/../x", "/api/%2e%2e/x", "/api/x/", "/api/%FF")) {
            Raw response = rawGet(target);
            assertEquals(400, response.status(), target);
            if (response.head().contains("application/json")) {
                assertJsonError(400, response.body());
            }
        }
        StringBuilder many = new StringBuilder("/api/x?");
        for (int i = 0; i <= LocalApiServer.MAX_PARAMETERS; i++) {
            many.append(i == 0 ? "" : "&").append("p").append(i).append("=1");
        }
        assertEquals(400, rawGet(many.toString()).status());
        assertEquals(414, rawGet("/api/x?value=" + "a".repeat(LocalApiServer.MAX_URI_LENGTH)).status());
        assertTrue(calls.isEmpty());
    }

    @Test
    void handlerErrorsBecomeExplicitJsonStatuses() throws Exception {
        start((path, query) -> switch (path) {
            case "/missing" -> throw new LocalApiServer.ApiException(404, "Unknown API endpoint.");
            case "/busy" -> throw new LocalApiServer.ApiException(409, "Busy.");
            case "/crash" -> throw new IllegalStateException("boom");
            case "/unserializable" -> new Object();
            default -> "ok";
        });
        HttpResponse<String> missing = get(server.baseUrl() + "/missing");
        assertEquals(404, missing.statusCode());
        assertEquals("Unknown API endpoint.", JSON.readTree(missing.body()).get("error").asText());
        assertEquals(409, get(server.baseUrl() + "/busy").statusCode());
        HttpResponse<String> crash = get(server.baseUrl() + "/crash");
        assertEquals(500, crash.statusCode());
        assertTrue(JSON.readTree(crash.body()).get("error").asText().contains("boom"));
        assertEquals(500, get(server.baseUrl() + "/unserializable").statusCode());
        assertEquals(200, get(server.baseUrl() + "/fine").statusCode());
    }

    @Test
    void apiExceptionRequiresErrorStatus() {
        assertThrows(IllegalArgumentException.class, () -> new LocalApiServer.ApiException(200, "x"));
        assertEquals(409, new LocalApiServer.ApiException(409, "x").status());
    }

    @Test
    void lifecycleBindsReleasesAndRejectsRestart() throws Exception {
        startRecording("x");
        int port = port();
        assertTrue(port > 0);
        assertEquals(LocalApiServer.baseUrl(port), server.baseUrl());
        assertEquals(200, get(server.baseUrl() + "/a").statusCode());

        LocalApiServer conflicting = new LocalApiServer(port, (path, query) -> null);
        assertThrows(IOException.class, conflicting::start);
        conflicting.close();

        server.close();
        server.close();
        assertThrows(IllegalStateException.class, server::start);
        assertThrows(ConnectException.class, () -> new Socket(InetAddress.getLoopbackAddress(), port).close());

        server = new LocalApiServer(port, (path, query) -> "again");
        server.start();
        assertEquals("\"again\"", get(server.baseUrl() + "/a").body());
        assertThrows(IllegalArgumentException.class, () -> new LocalApiServer(70000, (path, query) -> null));
        assertEquals(LocalApiServer.baseUrl(7071), new LocalApiServer(7071, (path, query) -> null).baseUrl());
    }

    @Test
    void lanInterfacesAcceptRequestsAndCopiedBaseUrlUsesLanAddress() throws Exception {
        startRecording("x");
        List<InetAddress> external = new ArrayList<>();
        for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (nic.isUp() && !nic.isLoopback()) {
                Collections.list(nic.getInetAddresses()).stream()
                        .filter(address -> address instanceof Inet4Address && !address.isLinkLocalAddress())
                        .forEach(external::add);
            }
        }
        assumeTrue(!external.isEmpty(), "No non-loopback IPv4 address available");
        assertNotEquals("127.0.0.1", URI.create(server.baseUrl()).getHost());
        assertTrue(external.stream().anyMatch(address -> address.getHostAddress()
                .equals(URI.create(server.baseUrl()).getHost())));
        for (InetAddress address : external) {
            String url = "http://" + address.getHostAddress() + ":" + port() + "/api/state";
            assertEquals(200, get(url).statusCode(), url);
        }
    }

    @Test
    void workerPoolIsBounded() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server = new LocalApiServer(0, (path, query) -> {
            if (path.equals("/block")) {
                entered.countDown();
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            return path;
        }, 1, 1);
        server.start();
        CompletableFuture<Raw> first = CompletableFuture.supplyAsync(() -> quietGet("/block"));
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        CompletableFuture<Raw> queued = CompletableFuture.supplyAsync(() -> quietGet("/queued"));
        Thread.sleep(300);
        Raw rejected = quietGet("/rejected");
        assertNotEquals(200, rejected.status());
        release.countDown();
        assertEquals(200, first.get(10, TimeUnit.SECONDS).status());
        assertEquals(200, queued.get(10, TimeUnit.SECONDS).status());
        assertEquals(200, rawGet("/after").status());
    }

    private Raw quietGet(String target) {
        try {
            return rawGet(target);
        } catch (IOException ex) {
            return new Raw(-1, "", ex.toString());
        }
    }

    @Test
    void urlEncodesPathSegmentsAndQueryStrictly() {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("value", "#FF 0+/ü");
        query.put("level", "0");
        assertEquals("http://127.0.0.1:7071/api/controls/weather/type?value=%23FF%200%2B%2F%C3%BC&level=0",
                LocalApiServer.url("http://127.0.0.1:7071/", "/api/controls/weather/type", query));
        assertEquals("http://h/api/a%20b", LocalApiServer.url("http://h", "/api/a b", Map.of()));
        assertEquals("http://h/api", LocalApiServer.url("http://h", "/api", null));
        assertEquals("a-._~Z9", LocalApiServer.encode("a-._~Z9"));
    }

    @Test
    void decodingRoundTripsEncodedValues() {
        String value = "Mixed #1 + ä/€ & = ?";
        assertEquals(value, LocalApiServer.decode(LocalApiServer.encode(value), true));
        assertEquals("a+b", LocalApiServer.decode("a+b", false));
        assertThrows(LocalApiServer.ApiException.class, () -> LocalApiServer.decode("a b", true));
    }
}
