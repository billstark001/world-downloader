package io.github.billstark001.worldmirror.conflict;

import io.github.billstark001.worldmirror.util.WMLogger;

/**
 * {@link ConflictResolver} that defers decisions on conflicting chunks.
 * <p>
 * When a conflict is detected, the incoming (server-side) chunk is persisted to
 * {@code conflict_chunks/<dim>/r.X.Z.mca} inside the mirror world folder via
 * {@link ConflictManager#saveConflict}.  The player can then resolve conflicts
 * per-chunk or in bulk using the chunk-map screen (Window 1) or the in-game
 * status screen buttons.
 * <p>
 * Until explicitly resolved, the existing local copy is kept (same behaviour as
 * {@link IgnoreResolver}).
 */
public class ManualResolver implements ConflictResolver {

    @Override
    public boolean shouldWriteChunk(ConflictContext context) {
        if (context.existsLocally()) {
            // Persist the incoming server chunk so the player can review it later.
            boolean saved = ConflictManager.saveConflict(
                    context.worldFolder(),
                    context.pos(),
                    context.chunkNbt(),
                    context.dimension());
            if (!saved) {
                throw new IllegalStateException("Could not persist incoming manual conflict");
            }
            WMLogger.debug("Conflict saved for chunk " + context.pos()
                    + " [" + context.dimension().identifier() + "]"
                    + " — keeping local copy until resolved.");
            return false;
        }
        return true;
    }
}

