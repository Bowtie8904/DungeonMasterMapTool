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
        assertEquals("http://127.0.0.1:7071/api/maps/" + map.get("id") + "/switch", map.get("url"));
        assertEquals(map.get("id"), new ProjectService().load(temp.resolve("maps").resolve("Test.dmmap"))
                .getId());
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
