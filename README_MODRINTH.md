# World Mirror

Ever wanted to take a piece of your favorite multiplayer server offline? **World Mirror** is a client-side Fabric mod that captures client-visible terrain, block entities, best-effort entity snapshots, and opened container contents, then exports them as a standard local Minecraft save.

Whether you're backing up a base, archiving a server before a wipe, or saving a minigame map, World Mirror spreads live-world capture across client ticks and moves region-file I/O to a worker thread to reduce gameplay stalls.

## ✨ Key Features

* **Stable + Experimental Pipelines:** Hardened periodic sync remains the default. An opt-in adaptive pipeline reacts to coalesced changes with bounded latency. Both serialize under a per-tick main-thread budget and write one region at a time on a worker.
* **Timestamp- and Source-Aware Updates:** Records per-chunk successful write times and source priorities in SQLite, skipping older snapshots and protecting chunks owned by higher-priority third-party sources.
* **Comprehensive Capture:**
    * **Multi-Dimension:** Writes the Overworld, Nether, End, and observed custom dimensions to the save layout required by each supported Minecraft version. Server datapacks or dimension definitions that are never sent to the client cannot be reconstructed.
    * **Entities:** Snapshots client-visible mobs, animals, dropped items, armor stands, paintings, item frames, and vehicles with type IDs. Moves and despawns are reconciled while their previous chunks remain loaded.
    * **Containers:** Intercepts inventory packets. Just open a chest, barrel, hopper, or furnace while the mod is active, and its contents will be saved to your mirrored world. Previously captured container items are preserved when later chunk snapshots are empty.
    * **Block Entities:** Persists sign text, banner patterns, player heads, beacon effects, and lectern books.
* **Fast Built-in Chunk Map:** Press **`M`** directly, or use **`I`** → Conflicts → **Open Chunk Map**, to open a draggable and zoomable view of the current dimension. Asynchronous viewport queries, low-zoom aggregation, coalesced fills, and merged boundaries keep large views responsive. Green-to-blue colors show update age, orange marks third-party sources, and red marks unresolved conflicts.
* **Optional Xaero Overlay:** Install [Xaero's World Map](https://modrinth.com/mod/xaeros-world-map) 1.40.x–1.44.x together with the matching [Xaero World Map Bridge 0.1.0](https://github.com/billstark001/xaero-world-map-bridge/releases/tag/v0.1.0) build to draw the same status layer on Xaero's fullscreen map.
* **Visual Conflict Resolution:** When using the **Manual** conflict strategy, conflicted chunks are saved to disk in MCA format. Open the Chunk Map to review them one by one or resolve all at once from the Conflicts tab.
* **Export Nearby Region:** Snapshot all loaded chunks within a configurable radius into a brand-new singleplayer save — ideal for archiving a specific area without touching your full mirror world.
* **Persistent World Mapping:** Maps each detected server address or singleplayer world name to a dedicated local folder. Per-world settings can move an existing mirror between `downloaded_worlds` and `saves` after confirmation, provided downloading and exporting are stopped.
* **International Support:** Fully translated into English, Simplified Chinese, Traditional Chinese, and Japanese.

## 🎮 How to Use

1. **Join** a multiplayer server (or singleplayer world).
2. Press **`P`** to start a download session. You'll see an active status in your action bar.
3. **Explore!** Walk around to load terrain. Remember to open any containers if you want their contents saved.
4. When you're done, press **`O`** for a final export, then press **`P`** to stop. Automatic export on stop is configurable but disabled by default.
5. **Play offline:** Your world is saved in `<.minecraft>/downloaded_worlds/` by default. *(Tip: Change the save location to your `Saves Folder` in the settings to play your mirrored worlds instantly from the singleplayer menu!)*

World Mirror only captures while the action bar says the session is active. Pressing **O**
does not start a session; it only flushes data already captured (plus the optional small
manual pre-capture). The default `downloaded_worlds` folder is not Minecraft's world list.
Choose **Saves Folder** before downloading if that is where you expect the mirror to appear.

*Need to clear the in-memory capture cache? Press **`L`**. This does not delete mirror files already written to disk.*
*Open the status screen at any time with **`I`**.*

## ⚙️ Configuration & Conflicts

Press **`I`**, open the **Settings** tab, and select **Global Settings** to configure save location, stable/experimental pipeline, sync and capture budgets, cache policy, lifecycle behavior, logging, and map rendering. Pipeline changes apply on the next download activation. Optional **Mod Menu** provides another title-screen entry to the same Cloth Config screen.

For a reproducible performance report, temporarily enable **Performance Diagnostic
Logging**, reproduce the problem for at least 30 seconds, and attach `latest.log` plus
`worldmirror.json`. Low-frequency `[perf]` lines identify capture backlog, write latency,
slow regions, failures, and heap pressure without per-chunk log spam.

You can handle **Chunk Conflicts** (when a chunk already exists on your local disk) globally or per-world using three strategies:

* **Overwrite (Default):** The server chunk always replaces your local copy.
* **Ignore:** Keeps your local copy; only brand-new chunks are written.
* **Manual:** Saves the incoming server chunk to `conflict_chunks/` in MCA format, leaving your local copy intact. Resolve conflicts later via the **Chunk Map** (per-chunk) or the Conflicts tab (**Overwrite All** / **Discard All**).

## 📥 Installation & Requirements

* **Minecraft:** 1.21.11, 26.1.2, or 26.2 — use the exactly matching World Mirror JAR
* **Java:** 21+ for Minecraft 1.21.11; 25+ for Minecraft 26.1.2 and 26.2
* **Mod Loader:** [Fabric](https://fabricmc.net/use/) (≥ 0.19.3)
* **Required:** Matching [Fabric API](https://modrinth.com/mod/fabric-api)
* **Bundled:** Cloth Config and SQLite JDBC; LibGui is not required
* **Optional:** [Mod Menu](https://modrinth.com/mod/modmenu) for a title-screen settings entry
* **Optional Xaero integration:** [Xaero's World Map](https://modrinth.com/mod/xaeros-world-map) 1.40.x–1.44.x **and** the matching [Xaero World Map Bridge 0.1.0](https://github.com/billstark001/xaero-world-map-bridge/releases/tag/v0.1.0)

---

> **Capture limits:** A client-side mod can only save data the server sends to it. Open containers to capture their contents; server-only entity state, structures, and datapack definitions may be absent. Entities in unloaded chunks retain their last client-known state until observed loaded again.
