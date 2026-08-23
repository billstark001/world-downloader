# World Mirror

**Version:** 0.4.0 · **Minecraft:** 1.21.11, 26.1.2, 26.2 · **Loader:** Fabric

A client-side Fabric mod that mirrors the world you are playing on a multiplayer server —
or even a singleplayer world — into a standard local save. As you explore, the mod captures
client-visible chunk terrain, block entities, best-effort entity snapshots, and opened
container contents. Choose the `Saves Folder` output mode to make the mirror appear directly
in Minecraft's singleplayer world list.

---

## Features

| Feature | Description |
|---------|-------------|
| **Persistent download session** | Press **P** to start or stop a download session. Chunks received from the server are recorded automatically while the session is active. |
| **Stable and adaptive pipelines** | Hardened periodic sync remains the default. An opt-in adaptive mode reacts to coalesced chunk changes with bounded durability latency. Both use main-thread capture budgets and low-memory, one-region-at-a-time background writes. |
| **Timestamp- and source-aware writes** | SQLite records successful per-chunk write times and source priorities. Older snapshots and updates outranked by a third-party source are skipped. |
| **Multi-dimension support** | Overworld, Nether, End, and custom dimensions are exported using the save layout required by the selected Minecraft version. |
| **Entity capture** | Client-visible non-player entities are snapshotted with their type IDs into per-dimension entity region files. Moves and despawns are reconciled while the affected chunks remain loaded; server-only state is necessarily unavailable. |
| **Container tracking** | The mod intercepts inventory packets when you open a chest, barrel, hopper, furnace, or any other container and saves the item stacks. They are merged into the block entity NBT on export. Double chests are handled correctly (each half is saved to its own position). |
| **Block entity data** | Signs (text), beacons (effects), banners (patterns), player heads (owner), lecterns (stored book), and all other block entities whose data the server sends to the client are persisted through Minecraft's chunk serialization path. |
| **World–mirror mapping** | Every detected server address or singleplayer world name is persistently mapped to a sanitised local folder name in `config/worldmirror/mirrors.json`. Different aliases for the same server are currently separate source IDs. |
| **Per-world settings** | Save location and conflict strategy can be overridden per world from the status screen without touching the global config. |
| **Conflict resolution** | Three built-in strategies for chunks that already exist on disk: *Overwrite* (default), *Ignore* (keep local), and *Manual* (save the server chunk to `conflict_chunks/` in MCA format for later review). |
| **Built-in Chunk Map** | Full-screen draggable and zoomable map of recorded chunks. Viewport-indexed snapshots, low-zoom bucket aggregation, and merged boundaries keep large views responsive. Colors show freshness/source; red marks unresolved conflicts. |
| **Xaero's World Map Overlay** | Optionally render the same status layer on Xaero's fullscreen map through Xaero World Map Bridge. Xaero's World Map and the bridge are both required for this integration. |
| **Export Nearby Region** | Snapshot all loaded chunks within a configurable radius (1–50 chunks) into a fresh singleplayer save with the spawn point set to your current position. |
| **Native status UI** | Press **I** to open the native Minecraft status screen. It retains download/export status, mirror information, per-world settings, safe mirror relocation, and conflict actions without a LibGui dependency. |
| **Useful, quiet diagnostics** | Operational detail stays in `latest.log`; chat is reserved for translated action results. An opt-in performance switch adds low-frequency pipeline/heap/slow-region telemetry without per-chunk spam. |
| **Cloth Config settings** | Global settings are available from the status screen. Installing Mod Menu also exposes the same screen from the title-screen mod list. |
| **Internationalisation** | UI strings are translated into English (`en_us`), Simplified Chinese (`zh_cn`), Traditional Chinese (`zh_tw`), and Japanese (`ja_jp`). |

---

## Keybindings

| Key | Action |
|-----|--------|
| **P** | Toggle download session on / off |
| **O** | Export cached data to disk immediately |
| **L** | Clear all cached chunks, entities, and containers |
| **I** | Open the in-game status screen |
| **M** | Open the chunk map directly |

All keybindings are rebindable in *Options → Controls → World Mirror*.

---

## In-Game Status Screen (I key)

![Status screen](assets/in-game-screen.png)

The status screen shows:

- **Source info** — type (singleplayer / server), source ID, local mirror folder name
- **Statistics** — total chunks cached across all dimensions, time since last sync
- **Live status** — download active/inactive, export running/idle
- **Action buttons** — Start/Stop Download, Export Now, Clear Data, **Export Nearby Region**
- **Conflicts tab** — count of stored conflict chunks, with *Overwrite All* and *Discard All* buttons, plus **Open Chunk Map** to review conflicts per-chunk
- **Per-world settings** — save-location and conflict-strategy overrides stored in `mirrors.json`; moving an existing mirror requires confirmation and is blocked while downloading or exporting
- **Integration status** — output path and Xaero World Map Bridge availability
- **Global Settings** — shortcut to the Cloth Config-generated settings screen

---

## Built-in Chunk Map (M key)

![Built-in chunk map](assets/builtin-map.png)

Drag to pan, use the mouse wheel to zoom, and hover a cell to inspect its chunk
coordinates, update age, and update source. The map queries an asynchronous,
viewport-indexed status snapshot rather than the SQLite database from the render loop.
At low zoom it aggregates chunks into bounded buckets and merges same-state runs and
boundaries. Clicking a conflicted chunk opens the **Overwrite / Discard / Cancel** dialog.

---

## Configuration

![World Mirror settings screen](assets/config-screen.png)

Click **Global Settings** in the status screen. If Mod Menu is installed, the same screen
is also available from *Mod Menu → World Mirror → Settings*.

| Setting | Values | Default |
|---------|--------|---------|
| Save location | `Downloaded Folder` / `Saves Folder` | `Downloaded Folder` |
| Sync interval / adaptive maximum durability latency | 5–600 s | 30 s |
| Download pipeline | `Stable Periodic` / `Experimental Adaptive` | `Stable Periodic` |
| Conflict strategy | `Overwrite` / `Ignore` / `Manual` | `Overwrite` |
| Maximum cached chunks | 0–12800; 0 disables the limit | 0 |
| Maximum cache distance | 0–64 chunks; 0 disables the limit | 32 |
| Maximum cache age | 0–14400 s; 0 disables the limit | 1800 s |
| Invalidate cache after export | `true` / `false` | `false` |
| Main-thread capture scheduling ceiling | 250–5000 µs/tick | 1500 µs/tick |
| Adaptive dirty high watermark | 32–8192 chunks | 512 chunks |
| Adaptive high-watermark export cooldown | 1–60 s | 10 s |
| Maximum pending capture hints | 512–32768 | 8192 |
| Performance diagnostic logging | `true` / `false` | `false` |
| Slow-region diagnostic threshold | 50–10000 ms | 500 ms |
| Sparse-map cell threshold | 1–16 px | 1 px |
| Chunk-map background | `Black` / `Transparent` | `Black` |
| Xaero overlay enabled | `true` / `false` | `true` |
| Xaero overlay refresh | 1–60 s | 10 s |
| Xaero overlay max cells | 1000–50000 | 6000 |
| On join / dimension change / server-world change | `Start` / `Stop` / `Keep` | `Stop` / `Keep` / `Stop` |
| Capture nearby before export | `true` / `false` | `true` |
| Capture nearby on stop | `true` / `false` | `false` |
| Export cached chunks on stop | `true` / `false` | `false` |

Configuration is persisted in `<.minecraft>/config/worldmirror.json`.

### Optional Xaero integration

The Xaero overlay is an optional integration: World Mirror runs normally without
Xaero's World Map or the bridge. To enable it, install Xaero's World Map 1.40.x–1.44.x
and a matching Minecraft-version build from the
[Xaero World Map Bridge 0.1.0 release](https://github.com/billstark001/xaero-world-map-bridge/releases/tag/v0.1.0)
alongside [Xaero's World Map](https://modrinth.com/mod/xaeros-world-map). World Mirror's
Chunk Map settings control whether and how often its layer is rendered. The bridge's own
Mod Menu page controls exact mixin injection and the safe tail fallback; World Mirror
only supplies the chunk-status layer through the bridge API.

![World Mirror overlay on Xaero's World Map](assets/xaero-map-overlay.png)

### Save locations

| Mode | Path |
|------|------|
| `Downloaded Folder` (default) | `<.minecraft>/downloaded_worlds/<mirror-name>/` |
| `Saves Folder` | `<.minecraft>/saves/<mirror-name>/` — immediately playable in the world list |

### Conflict strategies

| Strategy | Behaviour |
|----------|-----------|
| `Overwrite` | Server chunk always replaces the local copy |
| `Ignore` | Local copy is kept; only new chunks are written |
| `Manual` | Local copy is kept; the incoming server chunk is saved to `conflict_chunks/<dim>/r.X.Z.mca` for review. Use the Chunk Map or the Conflicts tab bulk buttons to resolve. |

---

## Output Format

The exported world is a standard Minecraft save directory. These files are common to
all supported targets:

```
downloaded_worlds/<mirror-name>/
├── level.dat                       ← Loadable world metadata
├── data/world_mirror.sqlite        ← Dirty-check and source-priority database
├── conflict_chunks/                ← Present when Manual conflicts are pending
├── resourcepacks/
└── worldmirror_meta.json           ← World Mirror metadata
```

Minecraft's dimension layout differs by target version:

| Target | Overworld | Nether | End | Custom dimension |
|--------|-----------|--------|-----|------------------|
| 1.21.11 | `<world>/` | `<world>/DIM-1/` | `<world>/DIM1/` | `<world>/dimensions/<ns>/<path>/` |
| 26.1.2 / 26.2 | `<world>/dimensions/minecraft/overworld/` | `<world>/dimensions/minecraft/the_nether/` | `<world>/dimensions/minecraft/the_end/` | `<world>/dimensions/<ns>/<path>/` |

Each dimension directory contains the target version's `region/`, `entities/`, and
`poi/` structure. World Mirror also creates the player-data and saved-data directories
required by that version.

`worldmirror_meta.json` fields:

| Field | Description |
|-------|-------------|
| `modVersion` | Mod version that created / last updated the mirror |
| `sourceType` | `singleplayer` or `server` |
| `sourceId` | `local:<level-name>` or `server:<address>` in 0.3.0 |
| `lastSyncTime` | Unix-millisecond timestamp of the most recent sync |

Per-chunk dirty-check metadata is stored in `data/world_mirror.sqlite`. Older
`worldmirror_meta.json` files with a legacy `chunkUpdateTimes` field are migrated
into SQLite on the first sync after upgrade, and the JSON field is removed.

---

## Entity Serialization

Before an export, World Mirror snapshots non-player entities currently visible to the
client and writes their client-known state to per-dimension `entities/r.X.Z.mca` files.
This covers common mobs, vehicles, paintings, item frames, armour stands, and dropped
items on a best-effort basis.

Entity output is not a server-authoritative backup. Fields never sent to the
client—such as AI internals and unopened villager trades—cannot be reconstructed.
World Mirror writes type IDs and clears moved/despawned entities when their previous
chunks are still loaded. When a chunk unloads, its last known entities are preserved
because a client-side mod cannot distinguish every unload from a server-side removal.

---

## Block Entity Serialization

Block entities are serialized through Minecraft's chunk saving path
(`getBlockEntityNbtForSaving` / `SerializableChunkData`):

- **Signs / Hanging signs** — front and back text, waxed and glow-ink flags
- **Beacons** — primary and secondary effect IDs
- **Banners** — all pattern layers
- **Player heads / Skulls** — owner profile
- **Lecterns** — stored book item
- **All other block entities** — any state the server sends to the client

Container inventories (chests, barrels, hoppers, furnaces, etc.) are handled separately
by the `ContainerTracker`, which intercepts inventory packets when the player opens each
container during the session. Previously captured non-empty container item data is
preserved when later client block-entity snapshots are empty, and default GUI titles such
as `container.chest` are not persisted as custom names.

---

## Capture Limits in 0.4.0

World Mirror is client-side and cannot reconstruct data the server never sends. In
particular:

- unopened container inventories, server-only entity fields, structure metadata, and
  server datapack definitions may be absent;
- entities in unloaded chunks retain their last client-known state until that chunk is
  observed loaded again;
- light-only, block, block-entity, biome, container, load, and unload changes are
  coalesced into bounded capture work; data the server never sends is still unavailable;
- uncaptured chunks intentionally remain void, while the generated save's climate and
  dimension metadata can differ from the source world
  ([issue #5](https://github.com/billstark001/world-mirror/issues/5)).

---

## Installation

Choose the World Mirror JAR that exactly matches your Minecraft version:

| Minecraft | Java |
|-----------|------|
| 1.21.11 | 21 or newer |
| 26.1.2 | 25 or newer |
| 26.2 | 25 or newer |

1. Install [Fabric Loader](https://fabricmc.net/use/) 0.19.3 or newer.
2. Install the matching [Fabric API](https://modrinth.com/mod/fabric-api).
3. Put the matching World Mirror 0.4.0 JAR in `mods/`.
4. *(Optional)* Install [Mod Menu](https://modrinth.com/mod/modmenu) for a title-screen settings entry.
5. *(Optional)* For the Xaero overlay, install both
   [Xaero's World Map](https://modrinth.com/mod/xaeros-world-map) 1.40.x–1.44.x and the
   matching [Xaero World Map Bridge 0.1.0](https://github.com/billstark001/xaero-world-map-bridge/releases/tag/v0.1.0).

Cloth Config and SQLite JDBC are bundled in the World Mirror JAR. LibGui is not used
and does not need to be installed.

---

## Typical Usage

World Mirror does nothing until a download session is active unless a lifecycle setting
explicitly starts one. The normal workflow is:

1. Join the source world and press **P once**. Confirm that the action bar says World
   Mirror is active.
2. Explore the areas you want to retain. Open each container whose inventory matters;
   unopened inventories are not sent to the client.
3. Leave the session active while exploring. Stable Periodic writes changed chunks on
   the configured interval; pressing **O** is optional and requests an immediate final
   pass. Automatic periodic passes do not rescan a 17×17 area.
4. Press **P** to stop. If “Export cached chunks on stop” is disabled (the default), use
   **O before stopping** when you want a final pass immediately.
5. The default output is `<.minecraft>/downloaded_worlds/`, which Minecraft does not list
   as a singleplayer save. Select **Saves Folder** before downloading if it should appear
   directly in the world list.

Changing the download pipeline while a session is active is intentionally safe: the new
choice applies the next time the session starts, so two writers can never operate on the
same mirror concurrently.

### Collecting a performance log

If stutter returns, enable **Performance → Performance Diagnostic Logging**, reproduce it
for at least 30 seconds, then attach `latest.log` and the World Mirror config. Lines marked
`[perf]` report the active pipeline, cache/dirty counts, capture queue age, coalesced and
dropped hints, main-thread capture time, export duration, slow region files, failures, and
heap usage. Disable the switch afterward; it is designed to be low-frequency but is not
needed during normal play.

Distant Horizons' “slow GC” warning is selected from the JVM garbage collector name and
does not by itself attribute a pause to World Mirror. World Mirror's `[perf]` line includes
`gcCollectors`, `gcCountDelta`, and `gcTimeMsDelta`; compare those deltas with
`lastCaptureUs`, `lastExportMs`, and slow-region lines to tell collector pressure from a
capture or disk bottleneck. The 0.4 writer removes World Mirror's previous whole-cache NBT
copy/conversion spike, which can reduce GC pressure without suppressing DH's generic JVM
warning.

1. Join a multiplayer server.
2. Press **P** — the action bar shows *World Mirror: Active*.
3. Walk around to load terrain.  Open containers to capture their inventories.
4. Press **I** to check sync statistics and status at any time.
5. Press **O** for a final export, then press **P** to stop the session. Automatic export
   on stop is available but disabled by default.
6. The mirror is saved under `<.minecraft>/downloaded_worlds/<mirror-name>/` by default.
   To play offline, open the world from the
   *Saves Folder* (set save location to `Saves Folder` in settings, or copy the folder
   manually to `<.minecraft>/saves/`).

### Quick export (no session)

Press **O** at any time to trigger an immediate export, even when no download session
is active. With the default lifecycle settings, the mod first queues a small loaded area
around the player for capture.

### Starting fresh

Press **L** to clear the in-memory chunk, entity, and container caches. This does not
delete or reset any mirror data already written to disk.

---

## Building

Maintainers should read [CONTRIBUTING.md](CONTRIBUTING.md), especially the
project-wide logging policy and the vendored NBT dependency notes.

```bash
./gradlew buildAll
```

This builds the Fabric targets for Minecraft 1.21.11, 26.1.2, and 26.2. Each
target's artifacts are stored in its `versions/fabric-*/build/libs` directory.

For a Modrinth upload, build all targets and collect only the three distributable
JARs in the root [`build/modrinth`](build/modrinth) directory:

```powershell
.\scripts\build-modrinth.ps1
```

Use `-SkipBuild` only when the current version's three JARs have already been built.

### Development run configurations

Gradle generates a separate IntelliJ IDEA client run configuration for every supported Minecraft target. All targets
intentionally share the root `run/` directory, while their module, Loom launch file, and Java runtime remain
version-specific. Minecraft 1.21.11 uses Java 21; Minecraft 26.x uses Java 25.

Reloading the Gradle project refreshes the configurations automatically. They can also be rebuilt from a terminal:

```powershell
.\gradlew.bat syncIdeaRunConfigurations
```

Before regeneration, this removes the old generated configurations, including entries belonging to deleted targets.
When adding or removing a Minecraft target:

1. Update its metadata entry in `build.gradle`.
2. Update the matching `versions/fabric-<minecraft>` project in `settings.gradle` and its source directories.
3. Update the CI, release, and Modrinth matrices in `.github/workflows` and `scripts`.
4. Reload the Gradle project, or run `syncIdeaRunConfigurations`.

`buildAll` derives its project list from `targets`, so it does not need a separate update.

---

## Architecture Notes

- All chunk data is captured on the game thread in `ChunkDataMixin` and stored in
  `ChunkListener` (dimension-aware: `Map<ResourceKey<Level>, Map<ChunkPos, CapturedChunk>>`).
- Container data is captured in `ContainerMixin` and stored in `ContainerTracker`.
- Entities are snapshot-serialized on the game thread by `EntityTracker` before each export.
- Built-in and Xaero rendering share `ChunkMapView` and use asynchronous status
  snapshots, viewport-indexed lookups, low-zoom bucket aggregation, coalesced fill runs,
  and merged boundaries.
- Xaero's World Map overlay is optional and uses Xaero World Map Bridge's public overlay API; the bridge owns Xaero-specific mixins and fallbacks.
- The actual disk I/O runs on a background thread (`WM-Export`) to reduce gameplay
  stalls. The game thread incrementally captures live chunks and provides immutable
  snapshots of chunks, entities, and container overlays to the writer.
- The dirty-check (`CapturedChunk.capturedAtMs` vs `data/world_mirror.sqlite`) ensures
  unchanged chunks are not re-written on every periodic sync.
- Region files are read and written using the bundled MIT-licensed
  [ens-gijs/NBT](https://github.com/ens-gijs/NBT) fork of
  [Querz/NBT](https://github.com/Querz/NBT).

## License

MIT

