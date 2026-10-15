package dmmt.service;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;

/** Isolates implicit AppSettings.load() calls in both Maven and IDE test runs. */
public final class TestSettingsExtension implements BeforeAllCallback, BeforeEachCallback {
    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(TestSettingsExtension.class);

    @Override
    public void beforeAll(ExtensionContext context) throws IOException {
        install(context);
    }

    @Override
    public void beforeEach(ExtensionContext context) throws IOException {
        install(context);
    }

    private static void install(ExtensionContext context) throws IOException {
        try (InputStream fixture = Objects.requireNonNull(
                TestSettingsExtension.class.getResourceAsStream("test-settings.ini"),
                "Missing test-settings.ini fixture")) {
            Path directory = Files.createTempDirectory("dmmt-test-settings-");
            SettingsScope scope = new SettingsScope(directory, System.getProperty(AppSettings.SYSTEM_PROPERTY));
            context.getStore(NAMESPACE).put("settings", scope);
            Path settingsFile = directory.resolve(AppSettings.FILE_NAME);
            Files.copy(fixture, settingsFile);
            System.setProperty(AppSettings.SYSTEM_PROPERTY, settingsFile.toString());
        }
    }

    private record SettingsScope(Path directory, String previousProperty)
            implements ExtensionContext.Store.CloseableResource {
        @Override
        public void close() throws IOException {
            if (previousProperty == null) {
                System.clearProperty(AppSettings.SYSTEM_PROPERTY);
            } else {
                System.setProperty(AppSettings.SYSTEM_PROPERTY, previousProperty);
            }
            try (var files = Files.walk(directory)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(file);
                }
            }
        }
    }
}
