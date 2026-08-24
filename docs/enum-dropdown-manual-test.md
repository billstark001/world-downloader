# Enum dropdown manual test checklist

Run this checklist on Minecraft 1.21.11, 26.1.2, and 26.2.

## Global settings

1. Open Mod Menu, then World Mirror settings.
2. Check Save Location, Download Pipeline, Conflict Strategy, all three New Mirror settings,
   Chunk Map > Map Background, and all three Lifecycle Behaviour fields.
3. For each field, click once and verify every valid translated choice is visible, the current
   value is highlighted, the control cannot be typed into, and selecting a value closes the list.
4. Repeat inside collapsed sections near the top and bottom of the viewport. Verify the list is
   drawn above neighbouring entries, remains focused, and scrolls with normal Cloth Config input.
5. Verify keyboard/controller focus opens the list, moves through choices, selects a choice, and
   Escape closes it without changing the pending value.
6. Verify Cancel discards edits, Done saves edits, and Reset restores defaults.
7. Inspect `config/worldmirror.json` and confirm enum values still use uppercase names such as
   `DOWNLOADED`, `STABLE_PERIODIC`, and `KEEP`.

## Per-world settings

1. Open World Mirror Status > Settings in a source world.
2. Open Save Location and Conflict Strategy. Verify the full translated list appears over the
   surrounding controls, the current value has a marker, and no cycling or typing is required.
3. Verify mouse selection, keyboard/controller focus and activation, outside click, and Escape.
4. Verify choosing the current value is a no-op; changing Save Location still shows the move
   confirmation when a mirror exists; changing Conflict Strategy persists after reopening.
