package dmmt.online;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Counts failed logins per key (IP address, player id) and locks a key out for a while after too many. */
public final class LoginRateLimiter {
    private record Entry(int failures, long lockedUntilMillis, long firstFailureMillis) {
    }

    private final int maxFailures;
    private final long lockoutMillis;
    private final LongSupplier clock;
    private final Map<String, Entry> entries = new HashMap<>();

    public LoginRateLimiter(int maxFailures, long lockoutMillis) {
        this(maxFailures, lockoutMillis, System::currentTimeMillis);
    }

    LoginRateLimiter(int maxFailures, long lockoutMillis, LongSupplier clock) {
        this.maxFailures = Math.max(1, maxFailures);
        this.lockoutMillis = Math.max(1000, lockoutMillis);
        this.clock = clock;
    }

    /** Seconds the key is still locked out; 0 when logins are allowed. */
    public synchronized int retryAfterSeconds(String key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return 0;
        }
        long now = clock.getAsLong();
        if (entry.lockedUntilMillis > now) {
            return (int) Math.max(1, (entry.lockedUntilMillis - now + 999) / 1000);
        }
        if (entry.lockedUntilMillis != 0 || now - entry.firstFailureMillis > lockoutMillis * 4) {
            entries.remove(key);
        }
        return 0;
    }

    public synchronized void recordFailure(String key) {
        long now = clock.getAsLong();
        Entry entry = entries.get(key);
        if (entry == null || entry.lockedUntilMillis != 0 && entry.lockedUntilMillis <= now) {
            entry = new Entry(0, 0, now);
        }
        int failures = entry.failures + 1;
        long lockedUntil = failures >= maxFailures ? now + lockoutMillis : 0;
        entries.put(key, new Entry(failures, lockedUntil, entry.firstFailureMillis));
    }

    public synchronized void recordSuccess(String key) {
        entries.remove(key);
    }
}
