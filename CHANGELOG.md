# Changelog

All notable changes to World Mirror are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

---

## [0.4.1] — 2026-08-23

### Changed

- Entity capture now uses Minecraft's vanilla serializer for complete top-level entities
  and passenger trees instead of a hand-written `ValueOutput` adapter.
- Entity persistence now models versioned partial and complete observations independently
  from terrain-cache eviction, and merges affected UUIDs through atomic entity-region
  replacements.

### Fixed

- Reconciled UUID moves across chunks and regions, recursively removed stale passenger
  copies, preserved unobserved on-disk entities during partial observations, and retained
  failed entity revisions for retry.
- Empty entity MCA placeholders no longer abort dimension-wide entity writes. Target
  placeholders are replaced atomically, and region/UUID verification now completes before
  entity revisions are acknowledged.
- The status screen now reads an open mirror's original source and metadata directly,
  refreshes metadata when an export finishes, and labels the timestamp as the last
  successful sync.
- New mirrors copy the source world's time, weather, and difficulty by default, with
  configurable morning, clear, and Peaceful fallbacks. Existing mirrors expose separate
  manual sync actions for those settings, preventing hostile entities from being discarded
  when a mirror was created with the wrong difficulty.
- Preserved immutable terrain, entity, and container snapshots when a stop-time export is
  deferred behind an active worker, preventing disconnect races from losing the final
  observed state.
- Minecraft 26.1.2 and 26.2 saves now write `world_clocks.dat` in the direct
  `data.<clock-id>.<state>` shape accepted by `PackedClockStates.CODEC`. Existing files
  with World Mirror 0.4.0's exact extra `data.clocks` wrapper are validated and repaired
  atomically; unrelated clock payloads are not modified.

---

## [0.4.0] — 2026-08-23

### Added

- Opt-in `Experimental Adaptive` download pipeline with dirty-backlog and maximum-latency triggers; the hardened `Stable Periodic` pipeline remains the default and mode changes apply only on the next activation.
- Coalesced event capture for full chunks, block changes, block entities, biomes, lighting, containers, chunk loads, and a final synchronous capture on unload.
- Configurable main-thread capture ceiling, bounded capture-hint queue, adaptive high-water mark, and export-cooldown controls.
- Default-off performance diagnostics covering queue pressure, capture/frame/tick percentiles, per-pass export triggers and stages, slow regions, failures, and JVM heap/GC use.

### Changed

- Stable periodic and adaptive scheduling now implement one `DownloadPipeline` contract,
  keeping both strategies parallel and making future strategies independent of lifecycle
  orchestration.
- Split the download controller into dedicated main-thread capture, background export,
  output-path, and nearby-export owners; `DownloadManager` remains the lifecycle/command
  facade.
- Moved the shared `WorldStructureCreator` and `StatusScreen` behavior into root source,
  leaving thin per-target adapters for Minecraft level-data, rendering, and screen APIs.
- Region output now uses Minecraft's streaming `RegionFile` API and materializes one chunk at a time instead of deep-copying and converting the whole cache through temporary byte arrays.
- Automatic exports no longer run the nearby 17×17 pre-export scan; the optional scan is reserved for explicit manual export.
- Entity regions are also streamed one region at a time instead of retaining every parsed entity MCA file for the pass.
- Adaptive high-watermark exports use low-watermark hysteresis and a cooldown; automatic requests never create immediate deferred tail passes behind an active export.
- Capture diagnostics aggregate latency by bounded reason, report rate-limited slow outliers, and wait for a complete first sampling window.
- Updated the README and Modrinth usage instructions to distinguish starting a session, flushing captured data, and selecting a world-list-visible save location.

### Fixed

- Clearing or switching a source world now clears chunk, entity, and container live state,
  while in-flight dirty snapshots retain their own lighting overlays until export finishes.
- Bulk conflict overwrite deletes a conflict MCA only after every applicable chunk was
  written successfully; failed world writes retain the conflict file for retry.
- Exact monotonic revisions prevent an update arriving during an older write from being acknowledged or invalidated accidentally.
- Dirty chunks can no longer be evicted before both region flush and SQLite durability-index commit; failed stages remain cached for retry.
- SQLite records each captured timestamp and refuses to regress a newer durability record.
- Manual conflicts use streaming region writes and remain dirty for retry if conflict persistence fails.
- Entity snapshots now include type IDs and write one-shot empty markers for moves/despawns in still-loaded chunks without deleting entities merely because their chunk unloaded.
- Replaced the unbounded per-region lock cache with fixed lock striping.

---

## [0.3.0] — 2026-07-29

### Added

- Fabric builds for Minecraft `1.21.11`, `26.1.2`, and `26.2` from one shared source layout.
- Optional integration with Xaero World Map Bridge `0.1.0`, including World Mirror chunk-status rendering on Xaero's fullscreen map without embedding the bridge in World Mirror's jar.
- `scripts/build-modrinth.ps1` builds all Fabric targets and collects the three current-version distributable JARs in `build/modrinth`.
- Per-world mirror-location migration with confirmation, synchronized path remapping, and safeguards that require downloads and exports to stop first.
- A vanilla-readable `worldmirror_environment` data pack embedded in each mirror save, with featureless per-dimension biomes for uncaptured void terrain.
- Read-only detection of the currently-open local mirror save, including its schema compatibility and original source identity.
- Enter-before-load schema updates for outdated local mirror saves, with a targeted backup, native confirmation, progress, and completion feedback.
- Metadata lineage choices for Nearby Export made from an open mirror: inherit the original source, point at the current mirror, or create an independent snapshot.

### Changed

- Migrated the Java package namespace to `io.github.billstark001.worldmirror`.
- Replaced LibGui/Cotton status UI with native Minecraft screens while retaining source, mirror location, download/export state, per-world settings, and conflict-management views.
- The built-in map and Xaero overlay now use asynchronous status snapshots, viewport aggregation, and merged state-coloured boundaries.
- Compact status layout: source/type with mirror and cache count with last-sync time share rows; output and Xaero-bridge status are in the per-world settings tab.

### Fixed

- Status-screen text now uses opaque ARGB colours on the new GUI extraction API.
- Avoided forced immediate screen rendering when opening the status UI, eliminating the opening black flash.
- Restored state-coloured chunk boundaries on built-in and Xaero map overlays.
- Completed four-locale UI coverage for status ages, migration feedback, nearby-export messages, and chunk-map tooltips.
- Migrated existing mirrors from the flat `the_void` generator before exporting more chunks, preserving exported chunk palettes while restoring normal Overworld, Nether, and End climate baselines for uncaptured space.
- Kept data-pack asset revisions separate from the semantic world-generation schema so Minecraft command/data-pack format changes can refresh assets without redefining the migration contract.
- Added metadata to new Nearby Export saves and made the status map use a currently-open mirror save's own capture database; Xaero shows a yellow current-mirror indicator where its canvas API permits.
- Prevented an older mod from overwriting a mirror's newer worldgen schema or embedded-asset revision.
- Replaced silent download-time schema migration with explicit confirmation; current mirror saves are only warned about capture when Download is clicked, not when entered.
- Replaced Xaero's obscured yellow dot with a high-contrast `WM` UI badge beside its settings button and a one-time explanatory toast.
- Deferred the World Mirror schema prompt until Minecraft's own save-version upgrade has completed, and restored the world list correctly when that prompt is cancelled.
- Refreshed migrated metadata with the currently loaded mod version and repaired generated data-pack metadata for Minecraft's required `min_format` / `max_format` range.
- Added a one-time, backed-up cleanup for blank legacy `minecraft:the_void` chunks so they regenerate under schema 1 without deleting captured or player-modified terrain.
- Stopped schema migration from writing Minecraft's exclusively locked `session.lock` file.

---

## [0.2.3] — 2026-06-22

### Changed

- Version bump: `0.2.2` → `0.2.3`.

### Fixed

- Fixed the Xaero's World Map overlay mixin for Xaero's World Map `1.41.1` on
  Minecraft `26.2` by updating the exact injection anchor to the current
  `MapElementRenderHandler.render(...)` signature.
- Added a configurable Xaero overlay injection mode. `Exact Only` preserves the
  verified draw order, while `Tail Fallback` can keep the overlay available on
  unverified Xaero builds at the cost of drawing above Xaero map UI elements.
- Added runtime Xaero overlay capability status and non-fatal handling so
  unsupported Xaero builds no longer crash the client at startup.
- Consolidated Xaero exact and tail fallback injections into one guarded mixin
  class so multiple exact anchors can be added without duplicate overlay draws.
- Extended `scripts/Get-LatestXaerosWorldMap.ps1` with release listing, specific
  Xaero version selection, and automated `GuiMap` disassembly output for future
  injection-point maintenance.
- Fixed Xaero jar reuse when the selected version already exists as
  `.jar.disabled`, avoiding unnecessary repeated downloads.
- Changed `scripts/Switch-WorldMirrorBranch.ps1` so it no longer deletes
  `build/libs` by default; full build cleanup and Xaero disassembly-cache cleanup
  now require explicit switches.

---

## [0.2.2] — 2026-06-21

### Added

- **Xaero's World Map Overlay:** Render World Mirror status fills and merged boundaries directly on Xaero's World Map screen.
- Added a PowerShell script helper `Get-LatestXaerosWorldMap.ps1` for downloading the latest Xaero's World Map jar for development/testing.
- Customizable Xaero overlay settings: toggle overlay, configure overlay refresh rate, and maximum visible cells limit.

### Changed

- Version bump: `0.2.1` → `0.2.2`.
- Updated the Minecraft/Fabric dependency set to `26.2`, Fabric Loader `0.19.3`,
  Fabric API `0.152.2+26.2`, ModMenu `20.0.0-beta.3`,
  and Cloth Config `26.2.155`.
- Reduced routine lifecycle, capture, export, migration, and world-structure logs from
  info to debug so normal gameplay chat/log output is quieter.
- Removed obsolete chest/debug mixins and centralized block-entity/container NBT handling
  in `BlockEntityNbtSupport`.
- **Chunk Map Optimizations:** Introduced `ChunkStatusCache` and `ChunkStatusSnapshot` to cache status data, reducing rendering and database query overhead on both the built-in chunk map and Xaero's overlay.
- Added merged boundary segment rendering to the built-in chunk map, reducing drawing calls when zoomed out.
- Extracted SQLite native drivers isolation for stable read-only status loading.

### Fixed

- Container block entities are no longer overwritten with empty `Items` data when a
  chunk is recaptured or exported over an existing local chunk. Previously captured or
  locally written non-empty item lists are preserved when the latest client-side block
  entity snapshot is empty.
- Container overlays are snapshotted when an export is deferred or running in the
  background, preventing later cache cleanup from dropping item data before the writer
  reaches the chunk.
- Default container titles such as `container.chest` and `container.chestDouble` are no
  longer written as `CustomName`, and old default-name compounds are stripped when chunks
  are merged.
- Double chest item ordering now follows vanilla's `ChestType.RIGHT == FIRST` combiner
  order for both full inventory packets and later single-slot updates, preventing large
  chest halves from being swapped or partially written.
- Chunk serialization now uses Minecraft's `SerializableChunkData` path, preserving
  available section light data (`BlockLight` / `SkyLight`), blending data, retrogen,
  post-processing, filtered heightmaps, and other vanilla chunk serialization fields
  that the client currently has.
- The download toggle keybinding now uses the `P` keysym instead of a raw scancode.
- Ported 26.2 client API usages for screen switching, HUD-hidden checks, and flat
  world preset lookup.

---

## [0.2.1] — 2026-03-29

### Added

- **Chunk Map (Window 1):** Full-screen draggable grid map accessible from the status screen
  (Conflicts tab → Open Chunk Map).  Each cell is color-coded:
  - Transparent — never downloaded.
  - Green → Blue (logarithmic, based on age) — downloaded via `world_mirror`.
  - Orange — written by a third-party source (e.g. `player`, `map_hp`).
  - Red inset border — chunk has an unresolved conflict stored on disk.
  Hovering shows a tooltip with chunk coordinates, last-update time, and source.
  Clicking a conflicted chunk opens a per-chunk dialog: **Overwrite** / **Discard** / **Cancel**.
- **File-based conflict storage:** When the `Manual` conflict strategy is active, incoming
  server chunks that collide with existing local chunks are now written to
  `conflict_chunks/<dim>/r.X.Z.mca` inside the mirror-world folder (MCA format) rather than
  being lost on restart.
- **Conflicts tab — bulk resolution:** *Overwrite All* (apply all stored server chunks) and
  *Discard All* (delete all stored server chunks, keep local) buttons in the status screen.
- **Export Nearby Region (Feature 2.1):** New screen (Status tab → Export Nearby Region…)
  lets you choose a world name and radius (1–50 chunks) to snapshot all loaded chunks in
  that area into a fresh singleplayer save.  Spawn point is set to the player's current
  block position.
- **Direct Chunk Map keybinding:** Press **M** to open the chunk map without going through
  the status screen.  The binding is configurable in *Options → Controls → World Mirror*.
- **Chunk map settings:** Added configurable sparse-render threshold (4–16, default 8)
  and map background style (black or transparent, default black).
- **MCA write support:** Added shared per-file locking and same-directory temporary-file
  replacement for terrain, entity, and conflict MCA writes.
- Language file additions for all new UI strings (en_us, zh_cn, zh_tw, ja_jp).

### Changed

- Status screen Conflicts tab redesigned: shows stored-conflict count instead of in-memory
  queue, with Overwrite All / Discard All / Open Chunk Map buttons.
- Status screen Status tab: added *Export Nearby Region…* button.
- Dimension and server-world transition detection logs now use debug level instead of
  info level.
- Chunk map rendering switches to sparse drawing when zoomed far out, avoiding full-grid
  per-cell drawing at tiny cell sizes.
- Background exports are no longer daemon threads, reducing the chance of JVM shutdown
  cutting off an in-progress save write.

### Fixed

- CI/CD: Added `-Djsse.enableSNIExtension=false` JVM argument to `gradle.properties` to fix
  TLS SNI handshake failures with `server.bbkr.space` in the GitHub Actions environment.
- Export requests made while another export is running are queued and coalesced instead of
  being dropped.
- Chunks are only marked written after their region file has been flushed successfully, so
  failed region writes stay cached for a later retry.
- Region and conflict writes now share per-file locks, reducing races between normal export,
  manual conflict storage, and conflict resolution.
- Bulk conflict overwrite now maps legacy conflict folders (`region`, `DIM-1`, `DIM1`) back
  to the mod's current per-dimension world folder layout.
- Cleaned up several definite IDE warnings in mod-owned code without changing mixin method
  signatures or Minecraft mapping-sensitive casts.

---

## [0.2.0] — 2026-03-25

### Fixed

- Container items now export correctly.
- Redstone components and other stateful blocks now export with their correct state.
- Biome and light data are now included correctly in chunk exports.
- Performance and memory issues in the caching and serialization processes have been comprehensively addressed:
  - Reduced blocking of the rendering/game threads caused by large-scale block capture before export, preventing noticeable stuttering caused by synchronous serialization.
  - Fixed an issue where container caches were not cleaned up synchronously when blocks expired, and modified the storage to be isolated by dimension to reduce memory growth after prolonged operation.
  - Cleaned up entity data that was out of sync with the block cache to prevent the continuous accumulation of entity cache data after dimension switching or frequent exports.
  - Tightened concurrent access paths for mirror mapping configurations to reduce the risk of race conditions between background exports and foreground configuration reads/writes.

### Added

- **SQLite chunk database** (`data/world_mirror.sqlite` inside each mirror world folder).
  Replaces the `chunkUpdateTimes` field in `worldmirror_meta.json` for chunk dirty-tracking.  The database also introduces an `update_sources` priority table that lets World Mirror and third-party tools (map renderers, importers) coexist without overwriting each other's data.  See `DATABASE.md` for the schema and integration guide.

- **Automatic migration** of legacy `chunkUpdateTimes` data from `worldmirror_meta.json` to SQLite on the first export after upgrading.  The JSON field is removed from the file after migration.

- **GitHub Actions release workflows** (manual trigger):
  - `release-modrinth.yml` — builds and publishes to Modrinth.
  - `release-github.yml` — builds and creates a GitHub Release with bundled JAR.

- **`DATABASE.md`** — English documentation for the SQLite schema, intended for third-party tool authors.

### Changed

- Version bump: `0.1.0` → `0.2.0`.

---

## [0.1.0] — Initial release

- Initial public release of World Mirror.
- Client-side Fabric mod for Minecraft 1.21.11.
- Captures and exports multiplayer world data (chunks, entities, containers) to a playable singleplayer save.
- Features: dirty-chunk tracking, multi-dimension support, configurable conflict resolution, container and entity tracking, in-game status screen.
