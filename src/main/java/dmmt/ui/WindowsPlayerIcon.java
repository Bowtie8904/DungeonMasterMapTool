package dmmt.ui;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.Win32Exception;
import com.sun.jna.platform.win32.WinDef.HICON;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import javafx.scene.image.Image;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

final class WindowsPlayerIcon {
    static final int WM_SETICON = 0x0080;
    static final int WM_GETICON = 0x007F;
    private final HWND window;
    private final HICON small;
    private final HICON large;
    private final long previousSmall;
    private final long previousLarge;
    private boolean closed;

    WindowsPlayerIcon(HWND window, Path file) {
        this.window = window;
        small = load(file, 16);
        try {
            large = load(file, 32);
        } catch (Win32Exception e) {
            destroy(small);
            throw e;
        }
        previousSmall = set(0, Pointer.nativeValue(small.getPointer()));
        previousLarge = set(1, Pointer.nativeValue(large.getPointer()));
    }

    static Path cachedFile() throws IOException {
        String local = System.getenv("LOCALAPPDATA");
        Path root = local == null || local.isBlank()
                ? Path.of(System.getProperty("user.home"), ".dmmt")
                : Path.of(local, "DungeonMasterMapTool");
        return writeFile(root.resolve("window-icons").resolve("player-view-v1.ico"));
    }

    static Path writeFile(Path file) throws IOException {
        byte[] bytes = encode(Icons.playerWindowIcons());
        if (Files.exists(file) && java.util.Arrays.equals(bytes, Files.readAllBytes(file))) return file;
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "player-view-", ".ico");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return file;
    }

    private static byte[] encode(List<Image> images) {
        int headerSize = 6 + 16 * images.size();
        int total = headerSize;
        for (Image image : images) total += bitmapSize((int) image.getWidth());
        ByteBuffer buffer = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort((short) 0).putShort((short) 1).putShort((short) images.size());
        int offset = headerSize;
        for (Image image : images) {
            int size = (int) image.getWidth();
            buffer.put((byte) size).put((byte) size).put((byte) 0).put((byte) 0);
            buffer.putShort((short) 1).putShort((short) 32).putInt(bitmapSize(size)).putInt(offset);
            offset += bitmapSize(size);
        }
        for (Image image : images) {
            int size = (int) image.getWidth();
            int maskStride = ((size + 31) / 32) * 4;
            buffer.putInt(40).putInt(size).putInt(size * 2);
            buffer.putShort((short) 1).putShort((short) 32);
            buffer.putInt(0).putInt(size * size * 4 + maskStride * size);
            buffer.putInt(0).putInt(0).putInt(0).putInt(0);
            var pixels = image.getPixelReader();
            for (int y = size - 1; y >= 0; y--) {
                for (int x = 0; x < size; x++) buffer.putInt(pixels.getArgb(x, y));
            }
            for (int y = size - 1; y >= 0; y--) {
                for (int b = 0; b < maskStride; b++) {
                    int mask = 0;
                    for (int bit = 0; bit < 8; bit++) {
                        int x = b * 8 + bit;
                        if (x < size && (pixels.getArgb(x, y) >>> 24) == 0) mask |= 0x80 >> bit;
                    }
                    buffer.put((byte) mask);
                }
            }
        }
        return buffer.array();
    }

    private static int bitmapSize(int size) {
        return 40 + size * size * 4 + ((size + 31) / 32) * 4 * size;
    }

    private static HICON load(Path file, int size) {
        var handle = User32.INSTANCE.LoadImage(null, file.toAbsolutePath().toString(), 1, size, size, 0x0010);
        if (handle == null || handle.getPointer() == null) {
            throw new Win32Exception(com.sun.jna.Native.getLastError());
        }
        return new HICON(handle);
    }

    private long set(int type, long icon) {
        return User32.INSTANCE.SendMessage(window, WM_SETICON, new WPARAM(type), new LPARAM(icon)).longValue();
    }

    public void close() {
        if (closed) return;
        closed = true;
        set(0, previousSmall);
        set(1, previousLarge);
        try {
            destroy(small);
        } finally {
            destroy(large);
        }
    }

    private static void destroy(HICON icon) {
        if (!User32.INSTANCE.DestroyIcon(icon)) throw new Win32Exception(com.sun.jna.Native.getLastError());
    }
}
