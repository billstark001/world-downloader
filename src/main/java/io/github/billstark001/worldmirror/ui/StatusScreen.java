package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.conflict.ConflictManager;
import io.github.billstark001.worldmirror.core.ChunkListener;
import io.github.billstark001.worldmirror.download.DownloadManager;
import io.github.billstark001.worldmirror.download.MirrorMapping;
import io.github.billstark001.worldmirror.download.MirrorWorldContext;
import io.github.billstark001.worldmirror.download.WorldMetadata;
import me.shedaniel.autoconfig.AutoConfigClient;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/** Native status UI; rendering and screen-installation differences live in StatusScreenApi. */
@Environment(EnvType.CLIENT)
public class StatusScreen extends StatusScreenApi {
    private static final int PANEL_WIDTH = 360;
    private static final int BUTTON_HEIGHT = 20;
    private static int activeTab;

    private boolean lastExportState;
    private boolean lastDownloadState;
    private Button toggleButton;
    private Component settingsFailure;
    private long lastSyncTime;
    private int conflictCount;

    protected StatusScreen() {
        super(Component.translatable("screen.worldmirror.status.title"));
        lastExportState = DownloadManager.isExportInProgress();
        lastDownloadState = DownloadManager.isActive();
    }

    @Override
    protected void init() {
        loadPersistentStatus();
        int left = left();
        int tabWidth = (PANEL_WIDTH - 8) / 3;
        addRenderableWidget(Button.builder(tabLabel("screen.worldmirror.tab.status", 0),
                        button -> switchTab(0))
                .bounds(left, 32, tabWidth, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(tabLabel("screen.worldmirror.tab.settings", 1),
                        button -> switchTab(1))
                .bounds(left + tabWidth + 4, 32, tabWidth, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(tabLabel("screen.worldmirror.tab.conflicts", 2),
                        button -> switchTab(2))
                .bounds(left + (tabWidth + 4) * 2, 32, tabWidth, BUTTON_HEIGHT).build());

        switch (activeTab) {
            case 0 -> addStatusButtons(left);
            case 1 -> addSettingsButtons(left);
            case 2 -> addConflictButtons(left);
            default -> activeTab = 0;
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.done"),
                        button -> onClose())
                .bounds(width / 2 - 50, height - 28, 100, BUTTON_HEIGHT).build());
    }

    @Override
    public void tick() {
        super.tick();
        boolean export = DownloadManager.isExportInProgress();
        boolean downloading = DownloadManager.isActive();
        if (export != lastExportState || downloading != lastDownloadState) {
            lastExportState = export;
            lastDownloadState = downloading;
            if (toggleButton != null) {
                toggleButton.setMessage(Component.translatable(downloading
                        ? "screen.worldmirror.status.stopDownload"
                        : "screen.worldmirror.status.startDownload"));
            }
        }
    }

    @Override
    protected void renderContent(Canvas graphics) {
        graphics.centered(title, width / 2, 12, 0xFFFFFFFF);
        switch (activeTab) {
            case 0 -> renderStatus(graphics);
            case 1 -> renderSettings(graphics);
            case 2 -> renderConflicts(graphics);
            default -> { }
        }
    }

    private void addStatusButtons(int left) {
        toggleButton = addRenderableWidget(Button.builder(Component.translatable(
                        DownloadManager.isActive()
                                ? "screen.worldmirror.status.stopDownload"
                                : "screen.worldmirror.status.startDownload"),
                button -> DownloadManager.toggle(Minecraft.getInstance()))
                .bounds(left, 116, 178, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable(
                        "screen.worldmirror.status.exportNow"),
                button -> DownloadManager.exportNow(Minecraft.getInstance()))
                .bounds(left + 182, 116, 178, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable(
                        "screen.worldmirror.status.clearData"),
                button -> { DownloadManager.clearAll(Minecraft.getInstance()); refresh(); })
                .bounds(left, 140, 178, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable(
                        "screen.worldmirror.status.exportNearby"),
                button -> ExportNearbyScreen.open(this))
                .bounds(left + 182, 140, 178, BUTTON_HEIGHT).build());
    }

    private void addSettingsButtons(int left) {
        String sourceId = WorldMetadata.detectSourceId(Minecraft.getInstance());
        ModConfig.SaveLocation saveLocation = resolveSaveLocation(sourceId);
        ModConfig.ConflictStrategy strategy = resolveStrategy(sourceId);
        addRenderableWidget(Button.builder(Component.translatable(
                        "screen.worldmirror.status.saveLoc").append(": ").append(
                        Component.translatable("config.worldmirror.saveLoc."
                                + saveLocation.name().toLowerCase())),
                button -> {
                    ModConfig.SaveLocation[] values = ModConfig.SaveLocation.values();
                    ModConfig.SaveLocation target =
                            values[(saveLocation.ordinal() + 1) % values.length];
                    if (DownloadManager.isActive()) {
                        settingsFailure = Component.translatable(
                                "screen.worldmirror.move.failure.download_active");
                        return;
                    }
                    if (DownloadManager.isExportInProgress()) {
                        settingsFailure = Component.translatable(
                                "screen.worldmirror.move.failure.export_in_progress");
                        return;
                    }
                    if (Files.isDirectory(DownloadManager.previewOutputPath(
                            Minecraft.getInstance()))) {
                        showScreen(new SaveLocationMoveScreen(this, target));
                    } else {
                        DownloadManager.setMirrorSaveLocation(sourceId, target);
                        refresh();
                    }
                }).bounds(left, 116, PANEL_WIDTH, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable(
                        "screen.worldmirror.status.conflictStrategy").append(": ").append(
                        Component.translatable("config.worldmirror.conflictStrategy."
                                + strategy.name().toLowerCase())),
                button -> {
                    ModConfig.ConflictStrategy[] values = ModConfig.ConflictStrategy.values();
                    MirrorMapping.getInstance().setPerWorldConflictStrategy(sourceId,
                            values[(strategy.ordinal() + 1) % values.length].name());
                    refresh();
                }).bounds(left, 140, PANEL_WIDTH, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable(
                        "screen.worldmirror.status.openSettings"),
                button -> showScreen(AutoConfigClient.getConfigScreen(
                        ModConfig.class, this).get()))
                .bounds(left, 174, PANEL_WIDTH, BUTTON_HEIGHT).build());
    }

    private void addConflictButtons(int left) {
        Path output = DownloadManager.previewOutputPath(Minecraft.getInstance());
        if (conflictCount > 0) {
            addRenderableWidget(Button.builder(Component.translatable(
                            "screen.worldmirror.status.overwriteAll"),
                    button -> { ConflictManager.clearAllConflicts(output, true); refresh(); })
                    .bounds(left, 108, 178, BUTTON_HEIGHT).build());
            addRenderableWidget(Button.builder(Component.translatable(
                            "screen.worldmirror.status.discardAll"),
                    button -> { ConflictManager.clearAllConflicts(output, false); refresh(); })
                    .bounds(left + 182, 108, 178, BUTTON_HEIGHT).build());
        }
        addRenderableWidget(Button.builder(Component.translatable(
                        "screen.worldmirror.status.openChunkMap"),
                button -> ChunkMapScreen.open())
                .bounds(left, 142, PANEL_WIDTH, BUTTON_HEIGHT).build());
    }

    private void renderStatus(Canvas graphics) {
        Minecraft client = Minecraft.getInstance();
        String sourceType = WorldMetadata.detectSourceType(client);
        String sourceId = WorldMetadata.detectSourceId(client);
        String folder = MirrorMapping.getInstance().previewBaseFolderName(sourceId);
        int x = left();
        pairLine(graphics, "screen.worldmirror.status.sourceId", sourceType + " · " + sourceId,
                "screen.worldmirror.status.mirrorPath", folder, x, 62);
        pairLine(graphics, "screen.worldmirror.status.chunks",
                String.valueOf(ChunkListener.getTotalCount()),
                "screen.worldmirror.status.lastSync", lastSync().getString(), x, 76);
        graphics.text(Component.translatable(DownloadManager.isActive()
                ? "screen.worldmirror.status.downloadActive"
                : "screen.worldmirror.status.downloadInactive"), x, 90, 0xFFE0E0E0);
        graphics.text(Component.translatable(DownloadManager.isExportInProgress()
                ? "screen.worldmirror.status.exportRunning"
                : "screen.worldmirror.status.exportIdle"), x + 182, 90, 0xFFE0E0E0);
        MirrorWorldContext.Snapshot mirror = MirrorWorldContext.current();
        if (mirror.isMirror()) {
            graphics.centered(Component.translatable("screen.worldmirror.status.currentMirror."
                    + mirror.state().name().toLowerCase()), width / 2, 104, 0xFFFFFF55);
        }
    }

    private void renderSettings(Canvas graphics) {
        graphics.centered(Component.translatable("screen.worldmirror.tab.settingsHeader"),
                width / 2, 66, 0xFFE0E0E0);
        line(graphics, "screen.worldmirror.status.outputPath",
                DownloadManager.previewOutputPath(Minecraft.getInstance()).toString(), left(), 80);
        line(graphics, "screen.worldmirror.status.xaeroOverlay",
                bridgeStatus().getString(), left(), 94);
        if (settingsFailure != null) {
            graphics.centered(settingsFailure, width / 2, 104, 0xFFFF5555);
        }
    }

    private void renderConflicts(Canvas graphics) {
        graphics.centered(Component.translatable("screen.worldmirror.tab.conflictsHeader"),
                width / 2, 66, 0xFFE0E0E0);
        if (conflictCount == 0) {
            graphics.centered(Component.translatable("screen.worldmirror.status.noConflicts"),
                    width / 2, 88, 0xFFE0E0E0);
        } else {
            line(graphics, "screen.worldmirror.status.conflicts",
                    String.valueOf(conflictCount), left(), 88);
        }
    }

    private void line(Canvas graphics, String key, String value, int x, int y) {
        graphics.text(valueLine(key, value, PANEL_WIDTH), x, y, 0xFFE0E0E0);
    }

    private void pairLine(Canvas graphics, String leftKey, String leftValue,
                          String rightKey, String rightValue, int x, int y) {
        graphics.text(valueLine(leftKey, leftValue, 178), x, y, 0xFFE0E0E0);
        graphics.text(valueLine(rightKey, rightValue, 178), x + 182, y, 0xFFE0E0E0);
    }

    private Component valueLine(String key, String value, int maximumWidth) {
        Component prefix = Component.translatable(key).append(": ");
        int valueWidth = maximumWidth - font.width(prefix);
        if (font.width(value) > valueWidth) {
            value = font.plainSubstrByWidth(value,
                    Math.max(0, valueWidth - font.width("…"))) + "…";
        }
        return prefix.copy().append(value);
    }

    private Component tabLabel(String key, int tab) {
        return activeTab == tab
                ? Component.literal("§l").append(Component.translatable(key))
                : Component.translatable(key);
    }

    private void switchTab(int tab) {
        activeTab = tab;
        refresh();
    }

    private int left() {
        return width / 2 - PANEL_WIDTH / 2;
    }

    private static Component bridgeStatus() {
        if (!ModConfig.get().chunkMap.showXaeroWorldMapOverlay) {
            return Component.translatable("screen.worldmirror.status.xaeroOverlay.disabled");
        }
        return FabricLoader.getInstance().isModLoaded("xaero_world_map_bridge")
                ? Component.translatable("screen.worldmirror.status.xaeroOverlay.bridge")
                : Component.translatable("screen.worldmirror.status.xaeroOverlay.missing");
    }

    private void loadPersistentStatus() {
        Minecraft client = Minecraft.getInstance();
        Path output = DownloadManager.previewOutputPath(client);
        conflictCount = ConflictManager.countAllConflicts(output);
        try {
            WorldMetadata metadata = WorldMetadata.loadOrCreate(output,
                    WorldMetadata.detectSourceId(client), WorldMetadata.detectSourceType(client));
            lastSyncTime = metadata.lastSyncTime;
        } catch (Exception ignored) {
            lastSyncTime = -1L;
        }
    }

    private Component lastSync() {
        if (lastSyncTime < 0L) return Component.literal("?");
        if (lastSyncTime == 0L) {
            return Component.translatable("screen.worldmirror.status.lastSyncNever");
        }
        return formatAge((System.currentTimeMillis() - lastSyncTime) / 1_000L);
    }

    private static Component formatAge(long seconds) {
        if (seconds < 0) seconds = 0;
        if (seconds < 60) {
            return Component.translatable("screen.worldmirror.status.age.seconds", seconds);
        }
        if (seconds < 3_600) {
            return Component.translatable("screen.worldmirror.status.age.minutes", seconds / 60);
        }
        return Component.translatable("screen.worldmirror.status.age.hours", seconds / 3_600);
    }

    private static ModConfig.SaveLocation resolveSaveLocation(String sourceId) {
        String configured = MirrorMapping.getInstance().getPerWorldSaveLocation(sourceId);
        try {
            return configured != null ? ModConfig.SaveLocation.valueOf(configured)
                    : ModConfig.get().defaultSaveLocation;
        } catch (IllegalArgumentException ignored) {
            return ModConfig.get().defaultSaveLocation;
        }
    }

    private static ModConfig.ConflictStrategy resolveStrategy(String sourceId) {
        String configured = MirrorMapping.getInstance().getPerWorldConflictStrategy(sourceId);
        try {
            return configured != null ? ModConfig.ConflictStrategy.valueOf(configured)
                    : ModConfig.get().defaultConflictStrategy;
        } catch (IllegalArgumentException ignored) {
            return ModConfig.get().defaultConflictStrategy;
        }
    }

    protected void refresh() {
        showScreen(new StatusClientScreen());
    }

    public static void open() {
        showScreen(new StatusClientScreen());
    }
}
