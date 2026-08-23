package io.github.billstark001.worldmirror.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Central logging API for World Mirror. Player messages use {@code WMPlayerMessages}. */
public final class WMLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger("WorldMirror");
    private static final ConcurrentHashMap<String, RateLimitState> RATE_LIMITS = new ConcurrentHashMap<>();

    private record RateLimitState(AtomicLong nextLogMs, AtomicLong suppressed) { }

    private WMLogger() { }

    public static void debug(String message) {
        LOGGER.debug(message);
    }

    public static void info(String message) {
        LOGGER.info(message);
    }

    public static void infoRateLimited(String key, long intervalMs, String message) {
        logRateLimited(key, intervalMs, message, null, false);
    }

    public static void warn(String message) {
        LOGGER.warn(message);
    }

    public static void warn(String message, Throwable error) {
        LOGGER.warn(message, error);
    }

    public static void warnRateLimited(String key, long intervalMs, String message) {
        warnRateLimited(key, intervalMs, message, null);
    }

    public static void warnRateLimited(
            String key, long intervalMs, String message, Throwable error) {
        logRateLimited(key, intervalMs, message, error, true);
    }

    private static void logRateLimited(
            String key, long intervalMs, String message, Throwable error, boolean warning) {
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
        if (!warning) {
            LOGGER.info(summary);
        } else if (error == null) {
            LOGGER.warn(summary);
        } else {
            LOGGER.warn(summary, error);
        }
    }
}
