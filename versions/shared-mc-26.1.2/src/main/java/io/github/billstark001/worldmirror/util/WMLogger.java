package io.github.billstark001.worldmirror.util;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Centralised logger for World Mirror.
 * <p>
 * Operational messages go to the standard log only. Player chat is reserved
 * for explicit, translated command/lifecycle feedback through
 * {@link #sendSystemMessage} and {@link #sendOverlayMessage}.
 */
public final class WMLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger("WorldMirror");
    private static final ConcurrentHashMap<String, RateLimitState> RATE_LIMITS = new ConcurrentHashMap<>();
    private record RateLimitState(AtomicLong nextLogMs, AtomicLong suppressed) { }

    private WMLogger() {}

    // ── Public API ────────────────────────────────────────────────────────────

    public static void debug(String msg) {
        LOGGER.debug(msg);
    }

    public static void info(String msg) {
        LOGGER.info(msg);
    }

    public static void warn(String msg) {
        LOGGER.warn(msg);
    }

    public static void warn(String msg, Throwable t) {
        LOGGER.warn(msg, t);
    }

    public static void warnRateLimited(String key, long intervalMs, String msg) {
        long now = System.currentTimeMillis();
        RateLimitState state = RATE_LIMITS.computeIfAbsent(key,
                ignored -> new RateLimitState(new AtomicLong(), new AtomicLong()));
        long next = state.nextLogMs().get();
        if (now < next || !state.nextLogMs().compareAndSet(next, now + intervalMs)) {
            state.suppressed().incrementAndGet();
            return;
        }
        long suppressed = state.suppressed().getAndSet(0L);
        LOGGER.warn(msg + (suppressed == 0 ? "" : " (" + suppressed
                + " similar message(s) suppressed)"));
    }

    public static void sendSystemMessage(Player player, Component message) {
        if (player != null) {
            player.sendSystemMessage(message);
        }
    }

    public static void sendOverlayMessage(Player player, Component message) {
        if (player != null) {
            player.sendOverlayMessage(message);
        }
    }

}
