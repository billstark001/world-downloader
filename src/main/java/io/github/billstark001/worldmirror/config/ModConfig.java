package io.github.billstark001.worldmirror.config;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;

/**
 * Persistent mod configuration stored in config/worldmirror.json.
 * <p>
 * Registered with Cloth Config's {@link AutoConfig} so that the library
 * can generate an in-game settings screen automatically.
 */
@Config(name = "worldmirror")
public class ModConfig implements ConfigData {

    // ── Enums ────────────────────────────────────────────────────────────────

    public enum SaveLocation {
        /** Default: game-dir/downloaded_world */
        DOWNLOADED,
        /** Directly in game-dir/saves/<name> so it is immediately playable */
        SAVES
    }

    /** Download pipeline selected for the next activation. */
    public enum DownloadPipelineMode {
        /** Hardened periodic exporter; conservative default for the 0.3 line. */
        STABLE_PERIODIC,
        /** Event-driven capture and adaptive durability scheduling. */
        EXPERIMENTAL_ADAPTIVE
    }

    public enum ConflictStrategy {
        /** Always replace local chunk with server version. */
        OVERWRITE,
        /** Keep local chunk, discard incoming server data. */
        IGNORE,
        /** Queue for manual user decision; falls back to IGNORE until UI is available. */
        MANUAL
    }

    /**
     * Describes how the download state should change on a specific lifecycle event.
     */
    public enum TransitionBehavior {
        /** Activate download automatically. */
        START,
        /** Deactivate download automatically. */
        STOP,
        /** Leave the download state exactly as it was. */
        KEEP
    }

    public enum ChunkMapBackground {
        BLACK,
        TRANSPARENT
    }

    /** How a setting is chosen when a mirror save is first created. */
    public enum NewWorldSettingBehavior {
        FOLLOW_CURRENT,
        DEFAULT
    }

    // ── Fields ───────────────────────────────────────────────────────────────

    @ConfigEntry.Gui.Tooltip
    @ConfigEntry.Gui.EnumHandler(option = ConfigEntry.Gui.EnumHandler.EnumDisplayOption.DROPDOWN)
    public SaveLocation defaultSaveLocation = SaveLocation.DOWNLOADED;

    /** Stable export cadence and adaptive maximum durability latency, in seconds. */
    @ConfigEntry.Gui.Tooltip
    @ConfigEntry.BoundedDiscrete(min = 5, max = 600)
    public int syncIntervalSeconds = 30;

    @ConfigEntry.Gui.Tooltip
    @ConfigEntry.Gui.EnumHandler(option = ConfigEntry.Gui.EnumHandler.EnumDisplayOption.DROPDOWN)
    public DownloadPipelineMode pipelineMode = DownloadPipelineMode.STABLE_PERIODIC;

    @ConfigEntry.Gui.Tooltip
    @ConfigEntry.Gui.EnumHandler(option = ConfigEntry.Gui.EnumHandler.EnumDisplayOption.DROPDOWN)
    public ConflictStrategy defaultConflictStrategy = ConflictStrategy.OVERWRITE;

    @ConfigEntry.Gui.Tooltip
    @ConfigEntry.Gui.EnumHandler(option = ConfigEntry.Gui.EnumHandler.EnumDisplayOption.DROPDOWN)
    public NewWorldSettingBehavior newWorldTime = NewWorldSettingBehavior.FOLLOW_CURRENT;

    @ConfigEntry.Gui.Tooltip
    @ConfigEntry.Gui.EnumHandler(option = ConfigEntry.Gui.EnumHandler.EnumDisplayOption.DROPDOWN)
    public NewWorldSettingBehavior newWorldWeather = NewWorldSettingBehavior.FOLLOW_CURRENT;

    @ConfigEntry.Gui.Tooltip
    @ConfigEntry.Gui.EnumHandler(option = ConfigEntry.Gui.EnumHandler.EnumDisplayOption.DROPDOWN)
    public NewWorldSettingBehavior newWorldDifficulty = NewWorldSettingBehavior.FOLLOW_CURRENT;

    // ── Cache control ─────────────────────────────────────────────────────────

    @ConfigEntry.Gui.CollapsibleObject
    public CacheConfig cache = new CacheConfig();

    @ConfigEntry.Gui.CollapsibleObject
    public PerformanceConfig performance = new PerformanceConfig();

    public static class PerformanceConfig {
        /** Non-adaptive scheduling ceiling used by every capture pipeline. */
        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.BoundedDiscrete(min = 250, max = 5000)
        public int captureBudgetMicros = 1500;

        /** Dirty backlog that makes an adaptive high-watermark export eligible. */
        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.BoundedDiscrete(min = 32, max = 8192)
        public int adaptiveDirtyHighWatermark = 512;

        /** Minimum time between adaptive high-watermark exports. */
        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.BoundedDiscrete(min = 1, max = 60)
        public int adaptiveExportCooldownSeconds = 10;

        /** Upper bound for coalesced chunk-capture hints. */
        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.BoundedDiscrete(min = 512, max = 32768)
        public int maxPendingCaptureHints = 8192;

        /** Emit low-frequency pipeline snapshots and slow-stage timings. */
        @ConfigEntry.Gui.Tooltip
        public boolean diagnosticPerformanceLogging = false;

        /** Log an individual region stage when it exceeds this duration. */
        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.BoundedDiscrete(min = 50, max = 10000)
        public int slowRegionMillis = 500;
    }

    @ConfigEntry.Gui.CollapsibleObject
    public ChunkMapConfig chunkMap = new ChunkMapConfig();

    public static class ChunkMapConfig {

        /**
         * At or below this cell size, the chunk map skips the per-chunk grid.
         * Low zoom may still draw coarse guide lines because they are cheap.
         */
        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.BoundedDiscrete(min = 1, max = 16)
        public int sparseRenderCellThreshold = 1;

        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.Gui.EnumHandler(option = ConfigEntry.Gui.EnumHandler.EnumDisplayOption.DROPDOWN)
        public ChunkMapBackground background = ChunkMapBackground.BLACK;

        @ConfigEntry.Gui.Tooltip
        public boolean showXaeroWorldMapOverlay = true;

        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.BoundedDiscrete(min = 1, max = 60)
        public int xaeroWorldMapOverlayRefreshSeconds = 10;

        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.BoundedDiscrete(min = 1000, max = 50000)
        public int xaeroWorldMapOverlayMaxCells = 6000;
    }

    public static class CacheConfig {

        /**
         * Maximum number of chunks to keep in the in-memory cache across all dimensions.
         * When the limit is exceeded, the oldest captured chunks are evicted first.
         * Set to 0 to disable the limit.
         */
        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.BoundedDiscrete(min = 0, max = 12800)
        public int maxCachedChunks = 0;

        /**
         * Maximum distance (in chunks) from the player at which cached chunks are retained.
         * Chunks farther than this radius are evicted during the periodic sync tick.
         * Set to 0 to disable distance-based eviction.
         */
        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.BoundedDiscrete(min = 0, max = 64)
        public int maxCacheDistanceChunks = 32;

        /**
         * Maximum age (in seconds) of a cached chunk before it is evicted.
         * Set to 0 to disable time-based eviction.
         */
        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.BoundedDiscrete(min = 0, max = 14400)
        public int maxCacheAgeSeconds = 1800;

        /**
         * If {@code true}, a chunk is removed from the in-memory cache immediately after
         * it has been successfully written to disk.  This reduces RAM usage at the cost of
         * re-capturing the chunk if it needs to be re-exported.
         */
        @ConfigEntry.Gui.Tooltip
        public boolean invalidateAfterExport = false;
    }

    // ── Lifecycle behaviour ───────────────────────────────────────────────────

    @ConfigEntry.Gui.CollapsibleObject
    public LifecycleConfig lifecycle = new LifecycleConfig();

    /**
     * Controls how the download state changes on world lifecycle events.
     * Each field uses {@link TransitionBehavior} to describe whether to
     * start, stop, or keep the current state.
     */
    public static class LifecycleConfig {

        /**
         * Behaviour when the player first joins a world (or reconnects to a server).
         * Default: STOP — downloading does not start automatically.
         */
        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.Gui.EnumHandler(option = ConfigEntry.Gui.EnumHandler.EnumDisplayOption.DROPDOWN)
        public TransitionBehavior onJoinWorld = TransitionBehavior.STOP;

        /**
         * Behaviour when the player travels to a different dimension within the
         * same world / server (e.g. entering the Nether or the End).
         * Default: KEEP — the current download state is preserved.
         */
        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.Gui.EnumHandler(option = ConfigEntry.Gui.EnumHandler.EnumDisplayOption.DROPDOWN)
        public TransitionBehavior onDimensionChange = TransitionBehavior.KEEP;

        /**
         * Behaviour when the player is transferred to a different logical world on
         * the same server (e.g. via a multiworld / per-world plugin such as Multiverse).
         * Detected by a change in {@code sourceId} while still connected to the same server.
         * Default: STOP — downloading stops to avoid mixing data from different worlds.
         */
        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.Gui.EnumHandler(option = ConfigEntry.Gui.EnumHandler.EnumDisplayOption.DROPDOWN)
        public TransitionBehavior onServerWorldChange = TransitionBehavior.STOP;

        /**
         * If enabled, capture loaded chunks around the player right before each export.
         * Uses a short radius to keep the game-thread cost low.
         */
        @ConfigEntry.Gui.Tooltip
        public boolean captureNearbyBeforeExport = true;

        /**
         * If enabled, capture loaded chunks around the player once when download stops.
         * This runs before optional stop-time export.
         */
        @ConfigEntry.Gui.Tooltip
        public boolean captureNearbyOnStop = false;

        /**
         * If enabled, trigger one final export of all cached chunks when download stops.
         */
        @ConfigEntry.Gui.Tooltip
        public boolean exportAllCachedOnStop = false;
    }

    // ── Singleton + AutoConfig integration ────────────────────────────────────

    @ConfigEntry.Gui.Excluded
    private static boolean registered = false;

    /**
     * Registers the config with AutoConfig (idempotent).
     * Must be called once during mod initialisation before any call to {@link #get()}.
     */
    public static void register() {
        if (!registered) {
            AutoConfig.register(ModConfig.class, GsonConfigSerializer::new);
            registered = true;
        }
    }

    /** Returns the live config instance managed by AutoConfig. */
    public static ModConfig get() {
        if (!registered) {
            register();
        }
        return AutoConfig.getConfigHolder(ModConfig.class).getConfig();
    }

    /** Persists the current config to disk via AutoConfig. */
    public void save() {
        if (registered) {
            AutoConfig.getConfigHolder(ModConfig.class).save();
        }
    }
}
