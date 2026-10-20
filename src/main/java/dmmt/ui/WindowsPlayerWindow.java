package dmmt.ui;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.COM.Unknown;
import com.sun.jna.platform.win32.Guid.GUID;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.Win32Exception;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinNT.HRESULT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class WindowsPlayerWindow {
    static final String PLAYER_APP_ID = "DungeonMasterMapTool.PlayerView";
    static final int EXCLUDED_FROM_PEEK = 12;
    private static final int RPC_E_CHANGED_MODE = 0x80010106;
    private static final Object CONFIGURED = new Object();

    private WindowsPlayerWindow() {}

    public static boolean configure(Stage stage) {
        if (!Platform.isWindows()) return true;
        if (stage.getProperties().get(CONFIGURED) instanceof Boolean configured) return configured;
        HWND window;
        try {
            window = findWindow(stage);
        } catch (Win32Exception | IllegalStateException | UnsatisfiedLinkError e) {
            report("locate Player View", e);
            return false;
        }
        boolean success = true;
        Path iconFile = null;
        try {
            iconFile = WindowsPlayerIcon.cachedFile();
            WindowsPlayerIcon icons = new WindowsPlayerIcon(window, iconFile);
            stage.addEventHandler(WindowEvent.WINDOW_HIDING, event -> {
                try {
                    icons.close();
                } catch (Win32Exception | UnsatisfiedLinkError e) {
                    report("release Player View native icons", e);
                }
            });
        } catch (IOException | Win32Exception | UnsatisfiedLinkError e) {
            report("install the Player View Windows icon", e);
            success = false;
        }
        stage.addEventHandler(WindowEvent.WINDOW_HIDING, event -> {
            try {
                try {
                    setAppId(window, null);
                } finally {
                    setStringProperty(window, 3, null);
                }
            } catch (Win32Exception | UnsatisfiedLinkError e) {
                report("clear the Player View taskbar properties", e);
            }
        });
        try {
            if (iconFile != null) setStringProperty(window, 3, iconFile.toAbsolutePath() + ",0");
        } catch (Win32Exception | UnsatisfiedLinkError e) {
            report("set the Player View taskbar icon", e);
            success = false;
        }
        try {
            setAppId(window, PLAYER_APP_ID);
        } catch (Win32Exception | UnsatisfiedLinkError e) {
            report("separate the Player View taskbar button", e);
            success = false;
        }
        try (Memory enabled = new Memory(Integer.BYTES)) {
            enabled.setInt(0, 1);
            check(Dwm.INSTANCE.DwmSetWindowAttribute(window, EXCLUDED_FROM_PEEK, enabled, Integer.BYTES));
        } catch (Win32Exception | UnsatisfiedLinkError e) {
            report("protect Player View from Windows Peek", e);
            success = false;
        }
        stage.getProperties().put(CONFIGURED, success);
        return success;
    }

    static HWND findWindow(Stage stage) {
        if (!stage.isShowing()) throw new IllegalStateException("Player View must be shown first.");
        List<HWND> matches = new ArrayList<>();
        int processId = Kernel32.INSTANCE.GetCurrentProcessId();
        String title = stage.getTitle();
        boolean enumerated = User32.INSTANCE.EnumWindows((window, data) -> {
            IntByReference ownerProcess = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(window, ownerProcess);
            if (ownerProcess.getValue() == processId) {
                char[] text = new char[title.length() + 2];
                User32.INSTANCE.GetWindowText(window, text, text.length);
                if (title.equals(Native.toString(text))) matches.add(window);
            }
            return true;
        }, null);
        if (!enumerated) throw new Win32Exception(Native.getLastError());
        if (matches.size() != 1) {
            throw new IllegalStateException("Expected one Player View window in this process; found " + matches.size());
        }
        return matches.getFirst();
    }

    static void setAppId(HWND window, String appId) {
        setStringProperty(window, 5, appId);
    }

    private static void setStringProperty(HWND window, int propertyId, String textValue) {
        HRESULT initialized = Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED);
        // JavaFX may already have initialized this thread using a different COM apartment.
        if (initialized.intValue() != RPC_E_CHANGED_MODE) check(initialized);
        try {
            PointerByReference result = new PointerByReference();
            check(Shell.INSTANCE.SHGetPropertyStoreForWindow(window,
                    new GUID("{886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99}"), result));
            PropertyStore store = new PropertyStore(result.getValue());
            try (Memory value = new Memory(8L + 2L * Native.POINTER_SIZE)) {
                value.clear();
                if (textValue == null) {
                    store.setValue(propertyId, value);
                } else {
                    try (Memory text = new Memory((textValue.length() + 1L) * Native.WCHAR_SIZE)) {
                        text.setWideString(0, textValue);
                        // PROPVARIANT: VT_LPWSTR, followed by its pointer at offset 8.
                        value.setShort(0, (short) 31);
                        value.setPointer(8, text);
                        store.setValue(propertyId, value);
                    }
                }
            } finally {
                store.Release();
            }
        } finally {
            if (initialized.intValue() >= 0) Ole32.INSTANCE.CoUninitialize();
        }
    }

    private static void check(HRESULT result) {
        if (result.intValue() < 0) throw new Win32Exception(result);
    }

    private static void report(String operation, Throwable error) {
        System.err.println("Could not " + operation + ": " + error);
    }

    @Structure.FieldOrder({"fmtid", "pid"})
    public static class AppIdKey extends Structure {
        public GUID fmtid = new GUID("{9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3}");
        public int pid = 5;
    }

    private static final class PropertyStore extends Unknown {
        PropertyStore(Pointer pointer) {
            super(pointer);
        }

        void setValue(int propertyId, Pointer value) {
            AppIdKey key = new AppIdKey();
            key.pid = propertyId;
            key.write();
            // IPropertyStore::SetValue is slot 6, after IUnknown and the three getters.
            check(new HRESULT(_invokeNativeInt(6, new Object[]{getPointer(), key.getPointer(), value})));
        }
    }

    interface Shell extends StdCallLibrary {
        Shell INSTANCE = Native.load("shell32", Shell.class);

        HRESULT SHGetPropertyStoreForWindow(HWND window, GUID iid, PointerByReference result);
    }

    interface Dwm extends StdCallLibrary {
        Dwm INSTANCE = Native.load("dwmapi", Dwm.class);

        HRESULT DwmSetWindowAttribute(HWND window, int attribute, Pointer value, int size);
    }
}
