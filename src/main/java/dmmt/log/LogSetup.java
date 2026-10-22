package dmmt.log;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Configures application logging once at startup: a console handler plus a rotating file in the log folder.
 * <ul>
 * <li>{@code -Ddmmt.log.level=DEBUG} (ERROR, WARN, INFO, DEBUG, TRACE; default INFO) sets the level of application loggers.</li>
 * <li>{@code -Ddmmt.log.dir=...} overrides the log folder (default: {@code logs} next to the settings file).</li>
 * </ul>
 */
public final class LogSetup {
    public static final String LEVEL_PROPERTY = "dmmt.log.level";
    public static final String DIR_PROPERTY = "dmmt.log.dir";
    public static final String FILE_PATTERN = "dmmt-%g.log";

    private static final int FILE_LIMIT_BYTES = 2_000_000;
    private static final int FILE_COUNT = 5;
    // Keeps the configured logger alive: LogManager only holds loggers weakly.
    private static Logger appLogger;
    private static boolean configured;
    private static Path logFolder;

    private LogSetup() {
    }

    /** Configures logging (idempotent). {@code defaultFolder} is used when no override property is set. */
    public static synchronized void configure(Path defaultFolder) {
        if (configured) {
            return;
        }
        configured = true;
        Logger root = Logger.getLogger("");
        for (Handler handler : root.getHandlers()) {
            root.removeHandler(handler);
        }
        root.setLevel(Level.INFO);
        appLogger = Logger.getLogger("dmmt");
        appLogger.setLevel(parseLevel(System.getProperty(LEVEL_PROPERTY)));

        Formatter formatter = new LineFormatter();
        ConsoleHandler console = new ConsoleHandler();
        console.setLevel(Level.ALL);
        console.setFormatter(formatter);
        root.addHandler(console);

        String override = System.getProperty(DIR_PROPERTY);
        Path folder = override == null || override.isBlank() ? defaultFolder : Path.of(override);
        try {
            if (folder != null) {
                Files.createDirectories(folder);
                FileHandler file = new FileHandler(folder.resolve(FILE_PATTERN).toString(),
                        FILE_LIMIT_BYTES, FILE_COUNT, false);
                file.setLevel(Level.ALL);
                file.setFormatter(formatter);
                file.setEncoding("UTF-8");
                root.addHandler(file);
                logFolder = folder.toAbsolutePath();
            }
        } catch (IOException | RuntimeException e) {
            Logger.getLogger(LogSetup.class.getName()).log(Level.WARNING,
                    "Could not open the log file in " + folder + "; logging to the console only.", e);
        }
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) ->
                Log.of(LogSetup.class).error("Uncaught exception on thread {}", thread.getName(), failure));
    }

    /** The active log folder, or {@code null} when file logging is unavailable. */
    public static synchronized Path logFolder() {
        return logFolder;
    }

    static Level parseLevel(String text) {
        if (text == null) {
            return Level.INFO;
        }
        return switch (text.trim().toUpperCase(Locale.ROOT)) {
            case "ERROR", "SEVERE" -> Level.SEVERE;
            case "WARN", "WARNING" -> Level.WARNING;
            case "DEBUG", "FINE" -> Level.FINE;
            case "TRACE", "FINER", "ALL" -> Level.FINER;
            default -> Level.INFO;
        };
    }

    private static final class LineFormatter extends Formatter {
        private static final DateTimeFormatter TIME =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

        @Override
        public String format(LogRecord record) {
            StringBuilder line = new StringBuilder(160);
            // Handlers format synchronously on the logging thread.
            line.append(TIME.format(Instant.ofEpochMilli(record.getMillis()))).append(' ')
                    .append(label(record.getLevel())).append(" [")
                    .append(Thread.currentThread().getName()).append("] ")
                    .append(shortName(record.getLoggerName())).append(" - ")
                    .append(formatMessage(record)).append(System.lineSeparator());
            Throwable thrown = record.getThrown();
            if (thrown != null) {
                StringWriter trace = new StringWriter();
                thrown.printStackTrace(new PrintWriter(trace));
                line.append(trace);
            }
            return line.toString();
        }

        private static String label(Level level) {
            int value = level.intValue();
            if (value >= Level.SEVERE.intValue()) return "ERROR";
            if (value >= Level.WARNING.intValue()) return "WARN ";
            if (value >= Level.INFO.intValue()) return "INFO ";
            if (value >= Level.FINE.intValue()) return "DEBUG";
            return "TRACE";
        }

        private static String shortName(String name) {
            if (name == null || name.isEmpty()) {
                return "root";
            }
            int dot = name.lastIndexOf('.');
            return dot < 0 ? name : name.substring(dot + 1);
        }
    }
}
