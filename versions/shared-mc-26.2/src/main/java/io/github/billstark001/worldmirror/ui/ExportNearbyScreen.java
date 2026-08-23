package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.download.DownloadManager;
import io.github.billstark001.worldmirror.download.MirrorWorldContext;
import io.github.billstark001.worldmirror.download.NearbyExporter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;

/**
 * Feature 2.1 — Export Nearby Region.
 *
 * <p>Allows the player to snapshot all loaded chunks within a configurable
 * radius into a brand-new singleplayer save.  The spawn point of the new
 * world is set to the player's current block position.
 *
 * <p>The radius can be adjusted with ← / → buttons (range 1–50 chunks).
 * The world name defaults to the server / world name.
 */
@Environment(EnvType.CLIENT)
public class ExportNearbyScreen extends Screen {

    private static final int RADIUS_MIN = 1;
    private static final int RADIUS_MAX = 50;
    private static final int RADIUS_DEFAULT = 16;

    private int radius = RADIUS_DEFAULT;
    private EditBox nameField;
    private NearbyExporter.Choice lineageChoice = NearbyExporter.Choice.INHERIT_ORIGINAL;
    private boolean choosingLineage;

    private final Screen parent;

    public ExportNearbyScreen(Screen parent) {
        super(Component.translatable("screen.worldmirror.exportNearby.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;
        choosingLineage = MirrorWorldContext.current().isMirror();
        int nameY = choosingLineage ? cy - 60 : cy - 40;
        int radiusY = choosingLineage ? cy + 20 : cy;

        // World-name field
        nameField = new EditBox(
                this.font,
                cx - 100, nameY, 200, 20,
                Component.translatable("screen.worldmirror.exportNearby.worldName"));
        nameField.setMaxLength(80);
        nameField.setValue(defaultWorldName());
        nameField.setHint(
                Component.translatable("screen.worldmirror.exportNearby.worldName"));
        addRenderableWidget(nameField);

        if (choosingLineage) {
            addRenderableWidget(Button.builder(lineageLabel(), btn -> {
                lineageChoice = switch (lineageChoice) {
                    case INHERIT_ORIGINAL -> NearbyExporter.Choice.CURRENT_MIRROR;
                    case CURRENT_MIRROR -> NearbyExporter.Choice.INDEPENDENT;
                    case INDEPENDENT -> NearbyExporter.Choice.INHERIT_ORIGINAL;
                };
                btn.setMessage(lineageLabel());
            }).bounds(cx - 100, cy - 25, 200, 20).build());
        }

        // Radius decrement
        addRenderableWidget(Button.builder(
                Component.literal("<"),
                btn -> { radius = Math.max(RADIUS_MIN, radius - 1); }
        ).bounds(cx - 60, radiusY, 20, 20).build());

        // Radius increment
        addRenderableWidget(Button.builder(
                Component.literal(">"),
                btn -> { radius = Math.min(RADIUS_MAX, radius + 1); }
        ).bounds(cx + 40, radiusY, 20, 20).build());

        // Confirm
        addRenderableWidget(Button.builder(
                Component.translatable("screen.worldmirror.exportNearby.export"),
                btn -> {
                    String name = nameField.getValue().isBlank()
                            ? defaultWorldName() : nameField.getValue();
                    Minecraft mc = Minecraft.getInstance();
                    ClientScreens.set(null);
                    DownloadManager.exportNearbyToNewSave(mc, name, radius, lineageChoice);
                }
        ).bounds(cx - 100, radiusY + 30, 94, 20).build());

        // Cancel
        addRenderableWidget(Button.builder(
                Component.translatable("gui.cancel"),
                btn -> ClientScreens.set(parent)
        ).bounds(cx + 6, radiusY + 30, 94, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);

        int cx = this.width / 2;
        int cy = this.height / 2;

        // Title
        context.centeredText(this.font, this.title,
                cx, choosingLineage ? cy - 100 : cy - 70, 0xFFFFFFFF);

        // World name label
        context.text(this.font,
                Component.translatable("screen.worldmirror.exportNearby.worldName"),
                cx - 100, choosingLineage ? cy - 75 : cy - 55, 0xFFAAAAAA);

        int radiusY = choosingLineage ? cy + 20 : cy;
        if (choosingLineage) {
            context.text(this.font,
                    Component.translatable("screen.worldmirror.exportNearby.lineage"),
                    cx - 100, cy - 40, 0xFFAAAAAA);
        }

        // Radius display
        context.centeredText(this.font,
                Component.translatable("screen.worldmirror.exportNearby.radius")
                        .append(": " + radius),
                cx, radiusY + 5, 0xFFFFFFFF);

        // Chunk count hint
        long chunkCount = (long) (2 * radius + 1) * (2 * radius + 1);
        context.centeredText(this.font,
                Component.translatable("screen.worldmirror.exportNearby.chunkCount", chunkCount),
                cx, radiusY + 18, 0xFFAAAAAA);
    }

    private Component lineageLabel() {
        return Component.translatable("screen.worldmirror.exportNearby.lineage")
                .append(": ")
                .append(Component.translatable("screen.worldmirror.exportNearby.lineage."
                        + lineageChoice.name().toLowerCase()));
    }

    private static String defaultWorldName() {
        Minecraft mc = Minecraft.getInstance();
        ServerData server = mc.getCurrentServer();
        if (server != null) {
            String host = server.ip;
            return "Nearby-" + host.replaceAll("[:/\\\\]", "_");
        }
        if (mc.hasSingleplayerServer()) {
            return "NearbySingleplayer";
        }
        return "NearbyExport";
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Opens the ExportNearby screen on the game thread. */
    public static void open(Screen parent) {
        ClientScreens.setLater(new ExportNearbyScreen(parent));
    }
}
