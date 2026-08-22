package io.github.billstark001.worldmirror.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Version-independent logging implementation. Minecraft-facing message delivery
 * remains in the per-version {@link WMLogger} adapter.
 */
final class WMLogBackend {

    private static final Logger LOGGER = LoggerFactory.getLogger("WorldMirror");
    private static final ConcurrentHashMap<String, RateLimitState> RATE_LIMITS = new ConcurrentHashMap<>();

    private record RateLimitState(AtomicLong nextLogMs, AtomicLong suppressed) { }

    private WMLogBackend() { }

    static void debug(String message) {
        LOGGER.debug(message);
    }

    static void info(String message) {
        LOGGER.info(message);
    }

    static void warn(String message) {
        LOGGER.warn(message);
    }

    static void warn(String message, Throwable error) {
        LOGGER.warn(message, error);
    }

    static void warnRateLimited(String key, long intervalMs, String message, Throwable error) {
        Objects.requireNonNull(key, "key");
        long now = System.currentTimeMillis();
        long nextInterval = Math.max(1L, intervalMs);
        RateLimitState state = RATE_LIMITS.computeIfAbsent(key,
                ignored -> new RateLimitState(new AtomicLong(), new AtomicLong()));
        long next = state.nextLogMs().get();
        if (now < next || !state.nextLogMs().compareAndSet(next, now + nextInterval)) {
            state.suppressed().incrementAndGet();
            return;
        }

        long suppressed = state.suppressed().getAndSet(0L);
        String summary = suppressed == 0
                ? message
                : message + " (suppressed=" + suppressed + ")";
        if (error == null) {
            LOGGER.warn(summary);
        } else {
            LOGGER.warn(summary, error);
        }
    }
}
