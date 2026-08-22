package io.github.billstark001.worldmirror.util;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/**
 * Centralised logger for World Mirror.
 * <p>
 * Operational messages go to the standard log only. Player chat is reserved
 * for explicit, translated command/lifecycle feedback through
 * {@link #sendSystemMessage} and {@link #sendOverlayMessage}.
 */
public final class WMLogger {

    private WMLogger() {}

    // ── Public API ────────────────────────────────────────────────────────────

    public static void debug(String msg) {
        WMLogBackend.debug(msg);
    }

    public static void info(String msg) {
        WMLogBackend.info(msg);
    }

    public static void warn(String msg) {
        WMLogBackend.warn(msg);
    }

    public static void warn(String msg, Throwable t) {
        WMLogBackend.warn(msg, t);
    }

    public static void warnRateLimited(String key, long intervalMs, String msg) {
        WMLogBackend.warnRateLimited(key, intervalMs, msg, null);
    }

    public static void warnRateLimited(String key, long intervalMs, String msg, Throwable t) {
        WMLogBackend.warnRateLimited(key, intervalMs, msg, t);
    }

    public static void sendSystemMessage(Player player, Component message) {
        if (player != null) {
            player.displayClientMessage(message, false);
        }
    }

    public static void sendOverlayMessage(Player player, Component message) {
        if (player != null) {
            player.displayClientMessage(message, true);
        }
    }

}
