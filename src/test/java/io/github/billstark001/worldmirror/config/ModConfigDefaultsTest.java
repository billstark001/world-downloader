package io.github.billstark001.worldmirror.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ModConfigDefaultsTest {
    @Test
    void successfulExportsInvalidateChunkCacheByDefault() {
        assertTrue(new ModConfig().cache.invalidateAfterExport);
    }
}
