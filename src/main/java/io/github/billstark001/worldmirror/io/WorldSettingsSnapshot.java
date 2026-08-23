package io.github.billstark001.worldmirror.io;

/** Immutable source-world settings captured before background export begins. */
public record WorldSettingsSnapshot(
        long gameTime,
        long dayTime,
        boolean raining,
        boolean thundering,
        int difficultyId) {

    public static final long DEFAULT_TIME = 1_000L;
    public static final int DEFAULT_DIFFICULTY_ID = 0;

    public static WorldSettingsSnapshot defaults() {
        return new WorldSettingsSnapshot(
                DEFAULT_TIME, DEFAULT_TIME, false, false, DEFAULT_DIFFICULTY_ID);
    }
}
