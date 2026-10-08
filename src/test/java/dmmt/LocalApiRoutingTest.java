package dmmt;

import dmmt.api.FxApiDispatcher;
import dmmt.api.LocalApiServer;
import dmmt.model.DmProject;
import dmmt.service.AppSettings;
import dmmt.service.MapLibraryService;
import dmmt.service.ProjectService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LocalApiRoutingTest {
    @TempDir
    Path temp;

    @BeforeAll
    static void startFx() throws Exception {
        FxTestSupport.startJavaFx();
    }

    @Test
    void discoveryIncludesPersistedMapIdsAndSwitchUrls() throws Exception {
        var app = fixture();
        var maps = (List<?>) request(app, "/api/maps", Map.of());
        assertEquals(1, maps.size());
        var map = (Map<?, ?>) maps.getFirst();
        assertEquals("Test", map.get("name"));
        assertEquals(false, map.get("multilevel"));
        assertEquals(LocalApiServer.baseUrl(7071) + "/api/maps/" + map.get("id") + "/switch", map.get("url"));
        assertEquals(map.get("id"), new ProjectService().load(temp.resolve("maps").resolve("Test.dmmap"))
                .getId());
    }

    @Test
    void copiedControlUrlsUseTheSameLanAddressAsMapDiscovery() throws Exception {
        var app = fixture();
        FxApiDispatcher.call(() -> {
            var control = new javafx.scene.control.Button();
            var api = new dmmt.api.DmControlApi(new dmmt.ui.ControlVisibility());
            api.add("player.freeze", control);
            Method method = app.getClass().getDeclaredMethod("localApiBaseUrl");
            method.setAccessible(true);
            String base = (String) method.invoke(app);
            assertEquals(LocalApiServer.baseUrl(7071), base);
            api.attachUrlMenus(() -> base, ignored -> {});
            control.getContextMenu().getItems().getFirst().fire();
            assertEquals(base + "/api/controls/player/freeze",
                    javafx.scene.input.Clipboard.getSystemClipboard().getString());
            return null;
        });
    }

    @Test
    void invalidMapCommandsAndUnsavedContentAreRejectedWithoutSwitching() throws Exception {
        var app = fixture();
        assertThrows(LocalApiServer.ApiException.class, () -> request(app, "/api/unknown", Map.of()));
        assertThrows(LocalApiServer.ApiException.class,
                () -> request(app, "/api/maps/not-a-uuid/switch", Map.of()));
        var maps = (List<?>) request(app, "/api/maps", Map.of());
        String id = (String) ((Map<?, ?>) maps.getFirst()).get("id");
        assertThrows(LocalApiServer.ApiException.class,
                () -> request(app, "/api/maps/" + id + "/switch", Map.of("level", "0")));
        FxApiDispatcher.call(() -> {
            DmProject project = (DmProject) get(app, "project");
            project.getOverlays().add(DmProject.OverlayShape.builder().id("unsaved").build());
            return null;
        });
        assertThrows(LocalApiServer.ApiException.class,
                () -> request(app, "/api/maps/" + id + "/switch", Map.of()));
        assertNull(get(app, "projectFile"));
        FxApiDispatcher.call(() -> {
            set(app, "ioBusy", true);
            return null;
        });
        assertThrows(LocalApiServer.ApiException.class, () -> request(app, "/api/maps", Map.of()));
    }

    @Test
    void copyAddressSettingChangesControlAndMapUrlsLiveWithoutRebinding() throws Exception {
        var app = fixture();
        try {
            FxApiDispatcher.call(() -> {
                AppSettings settings = (AppSettings) get(app, "preferences");
                Method base = app.getClass().getDeclaredMethod("localApiBaseUrl");
                base.setAccessible(true);
                var button = new javafx.scene.control.Button();
                var api = new dmmt.api.DmControlApi(new dmmt.ui.ControlVisibility());
                api.add("player.freeze", button);
                api.attachUrlMenus(() -> {
                    try {
                        return (String) base.invoke(app);
                    } catch (ReflectiveOperationException ex) {
                        throw new IllegalStateException(ex);
                    }
                }, ignored -> {});
                for (String choice : List.of("local", "network", "local")) {
                    settings.applyEdit("api.copyAddress", choice);
                    String expected = choice.equals("local") ? "http://127.0.0.1:7071"
                            : LocalApiServer.baseUrl(7071);
                    button.getContextMenu().getItems().getFirst().fire();
                    assertEquals(expected + "/api/controls/player/freeze",
                            javafx.scene.input.Clipboard.getSystemClipboard().getString());
                    var maps = (List<?>) request(app, "/api/maps", Map.of());
                    var map = (Map<?, ?>) maps.getFirst();
                    assertEquals(expected + "/api/maps/" + map.get("id") + "/switch", map.get("url"));
                    assertNull(get(app, "localApiServer"));
                }
                return null;
            });
        } finally {
            dmmt.service.Tuning.apply(key -> null);
        }
    }

    private DungeonMasterMapToolApplication fixture() {
        return FxApiDispatcher.call(() -> {
            String previous = System.getProperty(AppSettings.SYSTEM_PROPERTY);
            System.setProperty(AppSettings.SYSTEM_PROPERTY, temp.resolve("settings.ini").toString());
            try {
                var app = new DungeonMasterMapToolApplication();
                var service = new ProjectService();
                service.save(temp.resolve("maps").resolve("Test.dmmap"), DmProject.builder().build());
                set(app, "mapLibrary", new MapLibraryService(temp.resolve("maps"), service));
                set(app, "project", DmProject.builder().build());
                return app;
            } finally {
                if (previous == null) {
                    System.clearProperty(AppSettings.SYSTEM_PROPERTY);
                } else {
                    System.setProperty(AppSettings.SYSTEM_PROPERTY, previous);
                }

            }
        });
    }

    @Test
    void remoteActionsDoNotBypassModalDialogs() throws Exception {
        var app = fixture();
        var dialog = FxApiDispatcher.call(() -> {
            var stage = new javafx.stage.Stage();
            stage.initModality(javafx.stage.Modality.APPLICATION_MODAL);
            stage.setScene(new javafx.scene.Scene(new javafx.scene.layout.Pane(), 100, 100));
            stage.show();
            return stage;
        });
        try {
            assertThrows(LocalApiServer.ApiException.class,
                    () -> request(app, "/api/controls/fog/enabled", Map.of()));
        } finally {
            FxApiDispatcher.call(() -> {
                dialog.close();
                return null;
            });
        }
    }

    @Test
    void applicationRoutesRespondOverHttpWithoutWindowFocus() throws Exception {
        var app = fixture();
        try (var server = new LocalApiServer(0, (path, query) -> {
            try {
                return request(app, path, query);
            } catch (RuntimeException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        })) {
            server.start();
            HttpURLConnection connection = (HttpURLConnection) URI.create(server.baseUrl() + "/api/maps")
                    .toURL().openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            try {
                assertEquals(200, connection.getResponseCode());
                var maps = new com.fasterxml.jackson.databind.ObjectMapper().readTree(connection.getInputStream());
                assertEquals("Test", maps.get(0).path("name").asText());
            } finally {
                connection.disconnect();
            }
            connection = (HttpURLConnection) URI.create(server.baseUrl() + "/api/state").toURL().openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            try {
                assertEquals(200, connection.getResponseCode());
                var state = new com.fasterxml.jackson.databind.ObjectMapper().readTree(connection.getInputStream());
                assertFalse(state.path("busy").asBoolean());
                assertFalse(state.path("frozen").asBoolean());
                assertEquals(-1, state.path("level").asInt());
            } finally {
                connection.disconnect();
            }
        }
    }

    private static Object request(DungeonMasterMapToolApplication app, String path, Map<String, String> query)
            throws Exception {
        Method method = app.getClass().getDeclaredMethod("handleLocalApi", String.class, Map.class);
        method.setAccessible(true);
        try {
            return method.invoke(app, path, query);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw ex;
        }
    }

            @Test
            void liveSettingsStartStopAndRebindWithFailurePreservingListener() throws Exception {
                var app = fixture();
                try (var occupied = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
                    int port;
                    try (var free = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
                        port = free.getLocalPort();
                    }
                    applyApiSettings(app, true, port);
                    LocalApiServer first = (LocalApiServer) get(app, "localApiServer");
                    assertNotNull(first);
                    assertResponds(first.baseUrl());
                    applyApiSettings(app, true, port);
                    assertSame(first, get(app, "localApiServer"));
                    applyApiSettings(app, true, occupied.getLocalPort());
                    assertSame(first, get(app, "localApiServer"));
                    assertResponds(first.baseUrl());
                    int replacementPort;
                    try (var free = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
                        replacementPort = free.getLocalPort();
                    }
                    applyApiSettings(app, true, replacementPort);
                    var replacement = (LocalApiServer) get(app, "localApiServer");
                    assertNotSame(first, replacement);
                    assertResponds(replacement.baseUrl());
                    assertUnavailable(first.baseUrl());
                    applyApiSettings(app, false, replacementPort);
                    assertNull(get(app, "localApiServer"));
                    assertUnavailable(replacement.baseUrl());
                    applyApiSettings(app, true, replacementPort);
                    assertResponds(((LocalApiServer) get(app, "localApiServer")).baseUrl());
                } finally {
                    FxApiDispatcher.call(() -> {
                        app.stop();
                        return null;
                    });
                    dmmt.service.Tuning.apply(key -> null);
                }
                assertFalse(dmmt.service.Tuning.API_ENABLED.requiresRestart());
                assertFalse(dmmt.service.Tuning.API_PORT.requiresRestart());
            }

            private static void applyApiSettings(DungeonMasterMapToolApplication app, boolean enabled, int port) {
                FxApiDispatcher.call(() -> {
                    set(app, "statusLabel", new javafx.scene.control.Label());
                    AppSettings settings = (AppSettings) get(app, "preferences");
                    settings.applyEdit("api.enabled", Boolean.toString(enabled));
                    settings.applyEdit("api.port", Integer.toString(port));
                    Method method = app.getClass().getDeclaredMethod("applyLocalApiSettings");
                    method.setAccessible(true);
                    method.invoke(app);
                    return null;
                });
            }

            private static void assertResponds(String base) throws Exception {
                HttpURLConnection connection = (HttpURLConnection) URI.create(base + "/api/state").toURL().openConnection();
                connection.setConnectTimeout(2000);
                connection.setReadTimeout(2000);
                try {
                    assertEquals(200, connection.getResponseCode());
                } finally {
                    connection.disconnect();
                }
            }

            private static void assertUnavailable(String base) throws Exception {
                HttpURLConnection connection = (HttpURLConnection) URI.create(base + "/api/state").toURL().openConnection();
                connection.setConnectTimeout(2000);
                connection.setReadTimeout(2000);
                try {
                    assertThrows(java.io.IOException.class, connection::getResponseCode);
                } finally {
                    connection.disconnect();
                }
            }
    @Test
    void batchedDiscoveryReturnsOnlyTheRequestedControlsAndSkipsUnknownIds() throws Exception {
        var app = fixture();
        installControls(app);
        var all = (List<?>) request(app, "/api/controls", Map.of());
        assertEquals(2, all.size());
        var filtered = (List<?>) request(app, "/api/controls", Map.of("ids", "effects.opacity,missing.control"));
        assertEquals(1, filtered.size());
        assertEquals("effects.opacity", ((Map<?, ?>) filtered.getFirst()).get("id"));
        var ordered = (List<?>) request(app, "/api/controls", Map.of("ids", "effects.opacity,fog.enabled"));
        assertEquals(List.of("effects.opacity", "fog.enabled"),
                ordered.stream().map(entry -> ((Map<?, ?>) entry).get("id")).toList());
        assertThrows(LocalApiServer.ApiException.class, () -> request(app, "/api/controls", Map.of("ids", "")));
        assertThrows(LocalApiServer.ApiException.class, () -> request(app, "/api/controls", Map.of("ids", "a,,b")));
        String many = java.util.stream.IntStream.range(0, 129).mapToObj(index -> "id" + index)
                .collect(java.util.stream.Collectors.joining(","));
        assertThrows(LocalApiServer.ApiException.class, () -> request(app, "/api/controls", Map.of("ids", many)));
    }

    @Test
    void keyImagesAreServedAsPngAndRejectImpossibleOperations() throws Exception {
        var app = fixture();
        installControls(app);
        var image = (LocalApiServer.Binary) request(app, "/api/controls/fog/enabled/image", Map.of());
        assertEquals("image/png", image.contentType());
        assertArrayEquals(new byte[] {(byte) 0x89, 'P', 'N', 'G'}, java.util.Arrays.copyOf(image.body(), 4));
        var increment = (LocalApiServer.Binary) request(app, "/api/controls/effects/opacity/image",
                Map.of("operation", "increment"));
        assertFalse(java.util.Arrays.equals(image.body(), increment.body()));
        assertThrows(LocalApiServer.ApiException.class,
                () -> request(app, "/api/controls/fog/enabled/image", Map.of("operation", "increment")));
        assertThrows(LocalApiServer.ApiException.class,
                () -> request(app, "/api/controls/effects/opacity/image", Map.of("operation", "sideways")));
        assertThrows(LocalApiServer.ApiException.class,
                () -> request(app, "/api/controls/not/known/image", Map.of()));
    }

    @Test
    void keyImagesAndFilteredDiscoveryAnswerOverHttp() throws Exception {
        var app = fixture();
        installControls(app);
        try (var server = new LocalApiServer(0, (path, query) -> {
            try {
                return request(app, path, query);
            } catch (RuntimeException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        })) {
            server.start();
            HttpURLConnection connection = (HttpURLConnection) URI
                    .create(server.baseUrl() + "/api/controls/fog/enabled/image").toURL().openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            try {
                assertEquals(200, connection.getResponseCode());
                assertEquals("image/png", connection.getHeaderField("Content-Type"));
                assertEquals("private, max-age=60", connection.getHeaderField("Cache-Control"));
                byte[] body = connection.getInputStream().readAllBytes();
                assertEquals(Integer.parseInt(connection.getHeaderField("Content-Length")), body.length);
                assertArrayEquals(new byte[] {(byte) 0x89, 'P', 'N', 'G'}, java.util.Arrays.copyOf(body, 4));
            } finally {
                connection.disconnect();
            }
            connection = (HttpURLConnection) URI.create(server.baseUrl() + "/api/controls?ids=fog.enabled")
                    .toURL().openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            try {
                assertEquals(200, connection.getResponseCode());
                assertEquals("application/json; charset=utf-8", connection.getHeaderField("Content-Type"));
                var controls = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(connection.getInputStream());
                assertEquals(1, controls.size());
                assertEquals("fog.enabled", controls.get(0).path("id").asText());
            } finally {
                connection.disconnect();
            }
        }
    }

    @Test
    void stateReportsTheOpenMapUuidForMapKeys() throws Exception {
        var app = fixture();
        var state = (Map<?, ?>) request(app, "/api/state", Map.of());
        assertEquals("", state.get("id"));
        Path map = temp.resolve("maps").resolve("Test.dmmap");
        FxApiDispatcher.call(() -> {
            set(app, "projectFile", map);
            return null;
        });
        var maps = (List<?>) request(app, "/api/maps", Map.of());
        String expected = (String) ((Map<?, ?>) maps.getFirst()).get("id");
        assertEquals(expected, ((Map<?, ?>) request(app, "/api/state", Map.of())).get("id"));
        // Cached per open map file: repeated polls must keep answering the same id without rescanning.
        assertEquals(expected, ((Map<?, ?>) request(app, "/api/state", Map.of())).get("id"));
    }

    /** Gives the fixture a small control registry so the control routes can be exercised. */
    private static void installControls(DungeonMasterMapToolApplication app) {
        FxApiDispatcher.call(() -> {
            var api = new dmmt.api.DmControlApi(new dmmt.ui.ControlVisibility());
            api.add("effects.opacity", new javafx.scene.control.Slider(0.1, 1, 0.5));
            api.add("fog.enabled", new javafx.scene.control.ToggleButton());
            set(app, "controlApi", api);
            return null;
        });
    }

    private static Object get(Object instance, String name) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static void set(Object instance, String name, Object value) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }
}
