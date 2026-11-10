package cn.star.chunksqueeze;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigTest {
    @TempDir Path directory;
    private final int saving = Config.minimumSaving;
    private final boolean sector = Config.requireSectorSaving;
    private final boolean compact = Config.compactRegions;
    private final int migration = Config.migrationThreads;
    private final int background = Config.backgroundThreads;

    @AfterEach
    void restore() {
        Config.minimumSaving = saving;
        Config.requireSectorSaving = sector;
        Config.compactRegions = compact;
        Config.migrationThreads = migration;
        Config.backgroundThreads = background;
    }

    @Test
    void firstLoadCreatesDefaultsAndMissingKeysUseDefaults() throws Exception {
        Path path = directory.resolve("config/chunksqueeze.properties");
        Config.load(path);
        assertTrue(Files.readString(path, StandardCharsets.UTF_8).contains("minimumSavingBytes=128"));
        assertDefaults();
        Files.writeString(path, "# Empty configuration\n", StandardCharsets.UTF_8);
        Config.load(path);
        assertDefaults();
    }

    @Test
    void upperBoundsAndBooleanSettingsAreAppliedWithoutRewritingFile() throws Exception {
        Path path = directory.resolve("chunksqueeze.properties");
        String text = "minimumSavingBytes=1048576\nrequireSectorSaving=false\ncompactRegions=false\nmigrationThreads=16\nbackgroundThreads=2\n";
        Files.writeString(path, text, StandardCharsets.UTF_8);
        Config.load(path);
        assertEquals(1048576, Config.minimumSaving);
        assertFalse(Config.requireSectorSaving);
        assertFalse(Config.compactRegions);
        assertEquals(16, Config.migrationThreads);
        assertEquals(2, Config.backgroundThreads);
        assertEquals(text, Files.readString(path, StandardCharsets.UTF_8));
    }

    @Test
    void zeroValuesPreserveAutomaticWorkerSelection() throws Exception {
        Path path = directory.resolve("chunksqueeze.properties");
        Files.writeString(path, "minimumSavingBytes=0\nmigrationThreads=0\nbackgroundThreads=0\n", StandardCharsets.UTF_8);
        Config.load(path);
        assertEquals(0, Config.minimumSaving);
        assertEquals(0, Config.migrationThreads);
        assertEquals(0, Config.backgroundThreads);
    }

    @Test
    void malformedSettingsFailBeforeChangingConfiguration() throws Exception {
        Path path = directory.resolve("chunksqueeze.properties");
        for (String bad : new String[]{"minimumSavingBytes=-1", "minimumSavingBytes=1048577",
                "minimumSavingBytes=abc", "migrationThreads=-1", "migrationThreads=17",
                "backgroundThreads=-1", "backgroundThreads=3", "compactRegions=maybe", "requireSectorSaving=yes"}) {
            Config.minimumSaving = 321;
            Config.requireSectorSaving = false;
            Config.compactRegions = false;
            Config.migrationThreads = 7;
            Config.backgroundThreads = 1;
            Files.writeString(path, bad + "\n", StandardCharsets.UTF_8);
            assertThrows(IllegalStateException.class, () -> Config.load(path), bad);
            assertEquals(321, Config.minimumSaving, bad);
            assertFalse(Config.requireSectorSaving, bad);
            assertFalse(Config.compactRegions, bad);
            assertEquals(7, Config.migrationThreads, bad);
            assertEquals(1, Config.backgroundThreads, bad);
        }
    }

    private static void assertDefaults() {
        assertEquals(128, Config.minimumSaving);
        assertTrue(Config.requireSectorSaving);
        assertTrue(Config.compactRegions);
        assertEquals(0, Config.migrationThreads);
        assertEquals(0, Config.backgroundThreads);
    }
}
