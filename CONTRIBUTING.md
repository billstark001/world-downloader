# Contributing to World Mirror

## Source layout and boundaries

Root `src/main/java` is the home of version-neutral behavior. A class must not be
copied wholesale into every `versions/shared-mc-<version>` tree merely because a
Minecraft method name or rendering type changed. Keep the business class in root
source and add a small package-local API adapter per target, following
`WorldStructureCreator` + `WorldStructureApi`, `StatusScreen` + `StatusScreenApi`,
and `WMPlayerMessages`.

Version adapters translate Minecraft types, method names, data encodings, or save
layouts. They must not acquire lifecycle policy, persistence policy, or duplicated
UI behavior. Do not introduce a cross-version `shared-mc-26` layer until the Gradle
source sets explicitly consume it; identical thin 26.1.2/26.2 adapters are acceptable
while their upstream APIs remain separate compatibility targets.

The download package follows the same ownership rule:

- `DownloadManager` is the public lifecycle and command facade.
- `DownloadPipeline` implementations decide when an export is due. New strategies
  implement that contract alongside the existing stable and adaptive strategies.
- `DownloadCaptureQueue` owns main-thread serialization, hint coalescing, queue limits,
  and capture diagnostics.
- `DownloadExportCoordinator` owns request serialization, snapshots, durability commits,
  retry acknowledgment, and the export worker.
- `MirrorMapping` owns output-path selection, claiming, and per-world path settings.
- `EntityTracker` owns game-thread vanilla serialization and observation boundaries;
  `EntitySnapshotStore` owns versioned partial/complete reconciliation; and
  `EntityRegionWriter` owns UUID-aware atomic entity-region merging.

Avoid adding queue locks, region transactions, strategy-specific state, or path-claiming
logic back to the manager. Prefer extending an existing owner over adding a one-method
utility file, but do not merge unrelated state merely to reduce the file count.

Captured world state is scoped to one source world. Join, server-world, and dimension
transitions must not allow cached chunks, entities, containers, or lighting overlays to
leak into another source. Background work receives immutable snapshots that remain valid
after live caches are cleared. A region write is durable only after file validation and
the persistence step appropriate to that data: terrain also requires its SQLite index
commit, while entity output requires post-write region and dimension-wide UUID
verification. Failed conflict application must retain its conflict file.

Entity dirtiness is independent from terrain-cache retention. Never prune entity updates
because a terrain entry was evicted. A successfully reopened and UUID-verified
entity-region replacement acknowledges only the exact captured revisions it represents.
Stop-time exports deferred behind an active worker must carry immutable terrain, entity,
and container snapshots so disconnect or a new source cannot change their meaning.

Version-specific saved-data repairs belong in `WorldStructureApi`. Match only a known
World Mirror-owned malformed shape, preserve unrelated or extended payloads, replace the
file atomically, and exercise the target Minecraft codec in a round-trip test. A target
that does not use that saved-data file should provide only the no-op adapter required by
the shared orchestrator.

## Documentation and release policy

Documentation changes are part of a feature or compatibility change, not a later release
chore. Before merging, compare all user-visible behavior against `README.md`, the
self-contained player description in `README_MODRINTH.md`, the current release section in
`CHANGELOG.md`, and `DATABASE.md` when persistence semantics changed.

`README_MODRINTH.md` is written for players who may not know World Mirror's internals.
Lead with the required action and observable result. In particular, always keep these
points explicit:

- pressing **P** starts/stops capture, while **O** exports already captured data;
- the default `downloaded_worlds` directory is not shown in Minecraft's world list;
- containers must be opened and client-invisible server data cannot be recovered;
- every download must exactly match its Minecraft target and required Java version.

Avoid implementation terms such as revision acknowledgment, queue hysteresis, immutable
NBT materialization, or durability indexes in the first-use instructions. Put technical
detail in the repository README and link-oriented contributor docs instead.

The supported-target metadata in root `build.gradle` is the build source of truth. Adding,
removing, or releasing a target also requires checking `settings.gradle`, its
`versions/fabric-<minecraft>` and `versions/shared-mc-<minecraft>` directories, CI/release
workflows, both READMEs, and the current changelog. CI and release jobs must call
`buildAll` and collect artifacts from `versions/fabric-*/build/libs`; the old root
`build/libs` path is not a multi-target distribution directory.

Before a release, run all target tests, `buildAll`, and one client startup smoke test per
target. The shared `run/mods` directory may contain only one enabled Minecraft-version
build of Xaero's World Map at a time. A core smoke test may disable the external bridge and
map by mod ID, but report that limitation instead of presenting it as an integration test.

## Logging policy

World Mirror has one operational logging API: the shared `WMLogger`. Minecraft's
version-specific player-message methods live separately in `WMPlayerMessages`.
Do not add another logger,
`System.out`, `System.err`, or `printStackTrace` in World Mirror code.

Choose the lowest level that still makes the event actionable:

| Level | Use it for | Do not use it for |
| --- | --- | --- |
| `debug` | Normal state transitions, cache maintenance, coalescing, and detailed counts useful during development | Per-tick success messages or expected failures |
| `info` | A session or export milestone that explains what World Mirror did | Packet-, chunk-, entity-, or container-level activity |
| `warn` | A recoverable failure, lost operation, disabled integration, or durability risk requiring investigation | Expected lifecycle states such as a world being absent during disconnect |
| `warnRateLimited` | A warning that can recur for packets, chunks, regions, codecs, or database lookups | Unique failures that should always be visible |

Use a stable, low-cardinality rate-limit key and normally a 30-second interval.
The next emitted warning includes `suppressed=<count>`. Pass the `Throwable` to
the overload whenever an exception exists; appending only `getMessage()` loses
the stack and frequently loses the cause. Lower layers should either throw a
contextual exception or log it, not both.

Messages start with a short event description and then add searchable
`key=value` context, for example:

```java
WMLogger.warnRateLimited("chunk-process-" + dimension.identifier(), 30_000L,
        "Chunk processing failed dimension=" + dimension.identifier()
                + " chunk=" + chunkPos + "; retained for retry", error);
```

Do not log complete NBT, packet or inventory payloads, authentication/session
data, player chat, or user-provided container names. Paths, dimensions, chunk
positions, counts, durations, pipeline modes, and queue sizes are appropriate
when they help identify the failed operation.

### Player messages

Logs and player messages are separate interfaces. Operational logs never enter
chat automatically. Use `WMPlayerMessages.sendSystemMessage` or
`WMPlayerMessages.sendOverlayMessage` only at an
explicit command or lifecycle boundary, and only with a translated component.
Keep the message short; detailed exception data belongs in `latest.log`.

### Performance diagnostics

Normal logs retain low-frequency session/export milestones and warnings.
Expensive or periodic telemetry is gated by **Performance Diagnostic Logging**
and uses the `[perf]` marker with stable `key=value` fields. New performance
fields should describe a queue, stage duration, volume, memory/GC delta, or
failure count and should preserve existing field names. Individual slow-stage
records must also honor the configured threshold.

The periodic snapshot is session-scoped. Capture latency fields report bounded
samples with average, p95, p99 and maximum values; capture-hint counters are
grouped by reason; unload capture is measured separately; and GC deltas are
reported per collector so concurrent and stop-the-world activity are not merged.
`captureReasonLatency` entries use `reason:count/avg/p95/p99/max` in
microseconds. Diagnostic slow-capture records are rate-limited by origin and
bounded reason; do not emit a message whenever a short capture queue drains.
Export timing includes post-flush region verification. Keep all of these fields
bounded in memory and reset them when a new download session starts.
Gameplay frame spacing and World Mirror's own tick-handler time are measured
separately: capture timing alone cannot distinguish serialization cost from a GC
pause that happened during the same call. Each diagnostic export reports its
trigger and stage timings; periodic snapshots report worker duty time, capture
budget overruns, suppressed automatic requests, and deferred explicit-request
coalescing.

Region files are not durable merely because `flush()` returned. Terrain writes must
validate the Anvil location table, reopen and decode each staged entry, and only then
advance the SQLite durability index. Entity writes must reopen affected chunks and
verify affected UUIDs across the dimension before acknowledging revisions. Failed
validation remains dirty for retry; stale terrain durability rows for unreadable entries
must be removed.

Before merging a logging change, search the complete source tree for direct
console/logging calls, check that recurring failures are limited, verify that
exceptions retain their cause, and build every supported target. Do not launch
Minecraft as part of this check unless interactive validation is specifically
required.

## Vendored ens-gijs/NBT library

Code under `io.github.ensgijs.nbt` is third-party code and is outside the
World Mirror logging policy. Do not reformat or make opportunistic changes in
that package.

As of 2026-08-23, Maven Central publishes
`io.github.ens-gijs.nbt:nbt:0.1.1` and
`io.github.ens-gijs.nbt:nbt-mca:0.2.0`. Loom could embed them with
`implementation include(...)`, but World Mirror cannot safely switch yet: the
published artifacts and upstream commit `f547ff1f0992fd7bc2bed727de104b41ea80d80a`
still initialize `SectionedChunkBase` caches inline while a superclass invokes
`initMembers()`. World Mirror carries the required initialization fix in commit
`16d7dae`; using the released JARs would regress region decoding.

Until an upstream release contains that fix, update the vendored copy only as a
deliberate dependency change:

1. Record the upstream commit and review upstream release notes.
2. Replace both the `nbt` and `nbt-mca` source trees from that same commit.
3. Reapply only the documented section-cache fix if it is still absent.
4. Review the vendor-only diff separately from World Mirror changes.
5. Run the full test suite and build all Minecraft targets.

Once a compatible release exists, prefer the two pinned Maven Central
dependencies with Loom `include(...)`, remove the vendored sources in the same
commit, and inspect the built JAR to confirm both libraries are nested.
