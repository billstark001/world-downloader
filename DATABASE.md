# World Mirror Database

World Mirror stores chunk tracking metadata in a SQLite database at:

```
<mirror_world>/data/world_mirror.sqlite
```

This database is designed to be readable by third-party tools (map renderers, importers, analytics) alongside the standard Minecraft world files.

---

## Tables

### `chunks`

Records every chunk that World Mirror (or an external tool) has written to the mirror world.

| Column | Type | Description |
|--------|------|-------------|
| `source` | TEXT | World/server identifier matching `worldmirror_meta.json → sourceId` (e.g. `server:play.example.com` or `local:MySPWorld`) |
| `dimension` | TEXT | Minecraft dimension key, e.g. `minecraft:overworld` |
| `x` | INTEGER | Chunk X coordinate |
| `y` | INTEGER | Chunk Z coordinate *(named `y` for 2-D map convention)* |
| `update_time` | INTEGER | Unix timestamp in **milliseconds** of the last write |
| `update_source` | TEXT | Name of the update source that wrote this chunk (see `update_sources`) |

**Primary key:** `(source, dimension, x, y)`

---

### `update_sources`

Defines the update sources known to the system and their relative priorities.

| Column | Type | Description |
|--------|------|-------------|
| `update_source` | TEXT PRIMARY KEY | Unique source name |
| `priority` | INTEGER | Lower number = **higher** priority |
| `apply_to_source` | TEXT \| NULL | If set, this row only applies when `chunks.source` equals this value; NULL means all sources |
| `apply_to_dimension` | TEXT \| NULL | If set, this row only applies in this dimension; NULL means all dimensions |

**Default rows (seeded on first creation):**

| update_source | priority | apply_to_source | apply_to_dimension |
|---------------|----------|-----------------|-------------------|
| `player` | 0 | NULL | NULL |
| `world_mirror` | 10 | NULL | NULL |
| `game_events` | 20 | NULL | NULL |
| `map_hp` | 30 | NULL | NULL |
| `map_lp` | 50 | NULL | NULL |

---

## Priority logic

Before writing a chunk, World Mirror checks whether a higher-priority source has already written it:

1. Look up the chunk in the `chunks` table.
2. If no entry exists → write the chunk.
3. If the new capture timestamp is not newer than `update_time` → skip.
4. Look up the **existing** `update_source`'s priority in `update_sources` (filtered by `apply_to_source` and `apply_to_dimension` context).
5. Look up the **new** `update_source`'s priority similarly.
6. If the existing source's priority < the new source's priority (existing has higher priority) → skip; otherwise write.

An unregistered update source receives the lowest effective priority. If a priority
lookup fails, World Mirror logs a rate-limited warning and also treats that source as
lowest priority. If the chunk-row lookup itself fails, World Mirror fails open and allows
the region write; it does not claim durability in SQLite unless the later write and index
commit succeed.

This prevents World Mirror's periodic sync from overwriting chunks attributed to a
higher-priority source. The seeded `player` source (priority 0) outranks `world_mirror`
(priority 10). A third-party tool that also needs overwrite protection must register a
priority lower than 10 for the applicable source/dimension; the seeded `map_hp` and
`map_lp` rows are lower-priority fallback inputs and do not receive that protection.

---

## Integration for third-party tools

To register your tool as an update source with a custom priority:

```sql
INSERT OR IGNORE INTO update_sources (update_source, priority, apply_to_source, apply_to_dimension)
VALUES ('my_tool', 25, NULL, NULL);
```

When your tool writes a chunk, update the `chunks` table:

```sql
INSERT INTO chunks (source, dimension, x, y, update_time, update_source)
VALUES (?, ?, ?, ?, ?, 'my_tool')
ON CONFLICT(source, dimension, x, y) DO UPDATE SET
  update_time   = excluded.update_time,
  update_source = excluded.update_source
WHERE excluded.update_time >= chunks.update_time;
```

Replace `?` with the appropriate `source` (from `worldmirror_meta.json`), `dimension`, `x`/`y` chunk coords, and `update_time` (milliseconds since epoch).

Commit the `chunks` row only after the corresponding region entry is durably written and
can be reopened and decoded. A database row is a durability claim, not a work queue: if a
tool advances `update_time` before its MCA write succeeds, World Mirror may correctly skip
the older client snapshot and leave the save without the claimed chunk version. Use one
database transaction for a batch of completed region writes, and roll it back if the
batch cannot be committed.

World Mirror follows the same rule internally. It validates the Anvil location table,
reopens the region file, decodes each staged chunk, and only then records the represented
capture timestamp. Updates that arrive during an export retain a newer monotonic revision
and remain dirty for the next pass. When an existing region entry cannot be read, stale
World Mirror durability rows for that entry are removed so a future capture can repair it.

World Mirror will then respect your tool's priority when deciding whether to overwrite the
chunk on the next sync.
