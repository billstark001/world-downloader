package io.github.billstark001.worldmirror.util;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/** Version-specific delivery of translated player-facing messages. */
public final class WMPlayerMessages {
    private WMPlayerMessages() { }

    public static void sendSystemMessage(Player player, Component message) {
        if (player != null) player.displayClientMessage(message, false);
    }

    public static void sendOverlayMessage(Player player, Component message) {
        if (player != null) player.displayClientMessage(message, true);
    }
}
