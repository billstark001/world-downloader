# Contributing to World Mirror

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

Region files are not durable merely because `flush()` returned. New region-write
paths must validate the Anvil location table, close and reopen the file, decode
each staged entry, and only then advance the SQLite durability index. Any entry
that fails validation remains dirty for retry, and stale durability rows for an
unreadable entry must be removed.

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
