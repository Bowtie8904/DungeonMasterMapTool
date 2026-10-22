package dmmt.log;

import java.lang.System.Logger.Level;

/**
 * Thin, slf4j-style facade over {@link System.Logger} (which the JDK routes to {@code java.util.logging}, configured
 * by {@link LogSetup}). Messages use {@code {}} placeholders; a trailing {@link Throwable} argument is logged as the
 * exception. Arguments are only formatted when the level is enabled.
 */
public final class Log {
    private final System.Logger delegate;

    private Log(System.Logger delegate) {
        this.delegate = delegate;
    }

    public static Log of(Class<?> owner) {
        return new Log(System.getLogger(owner.getName()));
    }

    public boolean isDebugEnabled() {
        return delegate.isLoggable(Level.DEBUG);
    }

    public void debug(String message, Object... args) {
        log(Level.DEBUG, message, args);
    }

    public void info(String message, Object... args) {
        log(Level.INFO, message, args);
    }

    public void warn(String message, Object... args) {
        log(Level.WARNING, message, args);
    }

    public void error(String message, Object... args) {
        log(Level.ERROR, message, args);
    }

    private void log(Level level, String message, Object[] args) {
        if (!delegate.isLoggable(level)) {
            return;
        }
        Throwable failure = null;
        int count = args == null ? 0 : args.length;
        if (count > 0 && args[count - 1] instanceof Throwable t) {
            failure = t;
            count--;
        }
        String text = format(message, args, count);
        if (failure == null) {
            delegate.log(level, text);
        } else {
            delegate.log(level, text, failure);
        }
    }

    static String format(String message, Object[] args, int count) {
        if (message == null) {
            return "null";
        }
        if (count == 0) {
            return message;
        }
        StringBuilder out = new StringBuilder(message.length() + 16 * count);
        int used = 0;
        int from = 0;
        while (used < count) {
            int at = message.indexOf("{}", from);
            if (at < 0) {
                break;
            }
            out.append(message, from, at).append(args[used++]);
            from = at + 2;
        }
        return out.append(message, from, message.length()).toString();
    }
}
