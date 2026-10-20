package dmmt.ui;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.COM.Unknown;
import com.sun.jna.platform.win32.Guid.GUID;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinNT.HRESULT;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import dmmt.DungeonMasterMapToolApplication;
import dmmt.FxTestSupport;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.WINDOWS)
class WindowsPlayerWindowTest {
    @TempDir
    Path temporary;

    @BeforeAll
    static void startFx() throws Exception {
        FxTestSupport.startJavaFx();
    }

    @Test
    void actualPlayerWindowGetsSeparateIdentityAndPeekProtectionOnEveryOpen() throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            DungeonMasterMapToolApplication app = new DungeonMasterMapToolApplication();
            Stage dm = new Stage();
            dm.setTitle("DM native window test");
            dm.setScene(new Scene(new StackPane(), 200, 100));
            var primaryField = DungeonMasterMapToolApplication.class.getDeclaredField("primaryStage");
            primaryField.setAccessible(true);
            primaryField.set(app, dm);
            var playerField = DungeonMasterMapToolApplication.class.getDeclaredField("playerStage");
            playerField.setAccessible(true);
            var open = DungeonMasterMapToolApplication.class.getDeclaredMethod("openPlayerWindow");
            open.setAccessible(true);
            var close = DungeonMasterMapToolApplication.class.getDeclaredMethod("closePlayerWindow");
            close.setAccessible(true);
            try {
                dm.show();
                HWND dmWindow = WindowsPlayerWindow.findWindow(dm);
                String dmId = readAppId(dmWindow);
                long dmSmall = iconHandle(dmWindow, 0);
                long dmLarge = iconHandle(dmWindow, 1);
                for (int i = 0; i < 2; i++) {
                    open.invoke(app);
                    Stage player = (Stage) playerField.get(app);
                    HWND window = WindowsPlayerWindow.findWindow(player);
                    assertEquals(WindowsPlayerWindow.PLAYER_APP_ID, readAppId(window));
                    assertNotEquals(0, iconHandle(window, 0), "Native small icon must be installed");
                    assertNotEquals(0, iconHandle(window, 1), "Native large icon must be installed");
                    String resource = readProperty(window, 3);
                    assertNotNull(resource);
                    assertTrue(resource.endsWith("player-view-v1.ico,0"));
                    assertTrue(Files.isRegularFile(Path.of(resource.substring(0, resource.length() - 2))));
                    // DWM exposes EXCLUDED_FROM_PEEK as set-only; verify native calls succeed.
                    assertTrue(WindowsPlayerWindow.configure(player));
                    assertNotEquals(dmId, readAppId(window));
                    assertNull(player.getOwner());
                    assertFalse(player.isAlwaysOnTop());
                    assertEquals(dmId, readAppId(dmWindow), "DM taskbar identity must not change");
                    assertEquals(dmSmall, iconHandle(dmWindow, 0), "DM small icon must not change");
                    assertEquals(dmLarge, iconHandle(dmWindow, 1), "DM large icon must not change");
                    AtomicBoolean cleaned = new AtomicBoolean();
                    player.addEventHandler(WindowEvent.WINDOW_HIDING, event -> {
                        assertNull(readAppId(window), "Remove window properties before native destruction");
                        assertNull(readProperty(window, 3), "Remove taskbar icon metadata before native destruction");
                        assertEquals(0, iconHandle(window, 0), "Restore previous small icon on close");
                        assertEquals(0, iconHandle(window, 1), "Restore previous large icon on close");
                        cleaned.set(true);
                    });
                    dm.setIconified(true);
                    assertTrue(player.isShowing());
                    assertFalse(player.isIconified());
                    dm.setIconified(false);
                    close.invoke(app);
                    assertTrue(cleaned.get());
                }
                return null;
            } finally {
                close.invoke(app);
                dm.close();
            }
        });
        Platform.runLater(task);
        task.get(30, TimeUnit.SECONDS);
    }

    private static String readAppId(HWND window) {
        return readProperty(window, 5);
    }

    @Test
    void multiResolutionIconContainsThePlayerPixelsAndNativeHandlesAreRestored() throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            Path file = WindowsPlayerIcon.writeFile(temporary.resolve("player.ico"));
            byte[] encoded = Files.readAllBytes(file);
            assertEquals(file, WindowsPlayerIcon.writeFile(file));
            assertArrayEquals(encoded, Files.readAllBytes(file));
            ByteBuffer data = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(0, data.getShort());
            assertEquals(1, data.getShort());
            var images = Icons.playerWindowIcons();
            assertEquals(images.size(), data.getShort());
            for (int i = 0; i < images.size(); i++) {
                var image = images.get(i);
                int size = (int) image.getWidth();
                int entry = 6 + i * 16;
                assertEquals(size == 256 ? 0 : size, Byte.toUnsignedInt(data.get(entry)));
                assertEquals(size == 256 ? 0 : size, Byte.toUnsignedInt(data.get(entry + 1)));
                assertEquals(32, data.getShort(entry + 6));
                int length = data.getInt(entry + 8);
                int offset = data.getInt(entry + 12);
                assertTrue(offset + length <= encoded.length);
                assertEquals(40, data.getInt(offset));
                assertEquals(size, data.getInt(offset + 4));
                assertEquals(size * 2, data.getInt(offset + 8));
                for (int y = 0; y < size; y++) {
                    for (int x = 0; x < size; x++) {
                        assertEquals(image.getPixelReader().getArgb(x, y),
                                data.getInt(offset + 40 + ((size - y - 1) * size + x) * 4));
                    }
                }
            }
            Stage window = new Stage();
            window.setTitle("Player icon lifecycle test");
            window.setScene(new Scene(new StackPane(), 200, 100));
            try {
                window.show();
                HWND handle = WindowsPlayerWindow.findWindow(window);
                long small = iconHandle(handle, 0);
                long large = iconHandle(handle, 1);
                WindowsPlayerIcon icons = new WindowsPlayerIcon(handle, file);
                try {
                    assertNotEquals(0, iconHandle(handle, 0));
                    assertNotEquals(0, iconHandle(handle, 1));
                } finally {
                    icons.close();
                    icons.close();
                }
                assertEquals(small, iconHandle(handle, 0));
                assertEquals(large, iconHandle(handle, 1));
            } finally {
                window.close();
            }
            return null;
        });
        Platform.runLater(task);
        task.get(30, TimeUnit.SECONDS);
    }

    private static long iconHandle(HWND window, int type) {
        return User32.INSTANCE.SendMessage(window, WindowsPlayerIcon.WM_GETICON,
                new WPARAM(type), new LPARAM(0)).longValue();
    }

    private static String readProperty(HWND window, int propertyId) {
        HRESULT initialized = Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED);
        if (initialized.intValue() != 0x80010106) assertSuccess(initialized);
        try {
            PointerByReference result = new PointerByReference();
            assertSuccess(WindowsPlayerWindow.Shell.INSTANCE.SHGetPropertyStoreForWindow(window,
                    new GUID("{886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99}"), result));
            PropertyReader store = new PropertyReader(result.getValue());
            try (Memory value = new Memory(8L + 2L * Native.POINTER_SIZE)) {
                value.clear();
                try {
                    WindowsPlayerWindow.AppIdKey key = new WindowsPlayerWindow.AppIdKey();
                    key.pid = propertyId;
                    key.write();
                    store.read(key.getPointer(), value);
                    if (value.getShort(0) == 0) return null;
                    assertEquals(31, value.getShort(0), "AppUserModelID must be VT_LPWSTR");
                    return value.getPointer(8).getWideString(0);
                } finally {
                    assertSuccess(VariantApi.INSTANCE.PropVariantClear(value));
                }
            } finally {
                store.Release();
            }
        } finally {
            if (initialized.intValue() >= 0) Ole32.INSTANCE.CoUninitialize();
        }
    }

    private static void assertSuccess(HRESULT result) {
        assertTrue(result.intValue() >= 0, () -> "HRESULT: " + Integer.toHexString(result.intValue()));
    }

    private static final class PropertyReader extends Unknown {
        PropertyReader(Pointer pointer) {
            super(pointer);
        }

        void read(Pointer key, Pointer value) {
            assertSuccess(new HRESULT(_invokeNativeInt(5, new Object[]{getPointer(), key, value})));
        }
    }

    interface VariantApi extends StdCallLibrary {
        VariantApi INSTANCE = Native.load("ole32", VariantApi.class);

        HRESULT PropVariantClear(Pointer value);
    }
}
