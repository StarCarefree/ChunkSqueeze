package cn.star.chunksqueeze;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

public final class Config {
    public static volatile boolean compactRegions = true;
    public static volatile int migrationThreads = 0;
    public static volatile int backgroundThreads = 0;
    public static volatile int minimumSaving = 128;
    public static volatile boolean requireSectorSaving = true;

    private Config() {}

    public static void load() {
        load(FabricLoader.getInstance().getConfigDir().resolve("chunksqueeze.properties"));
    }

    static void load(Path path) {
        try {
            if (Files.notExists(path)) {
                Files.createDirectories(path.toAbsolutePath().getParent());
                Files.writeString(path, """
                        # Minimum compressed bytes saved per chunk (0..1048576).
                        minimumSavingBytes=128
                        # Only encode chunks that save an allocation unit.
                        requireSectorSaving=true
                        # Use compact files for new regions. Existing MCA files stay unchanged.
                        compactRegions=true
                        # Migration workers (0..16). 0 = auto, bounded by CPU and heap.
                        migrationThreads=0
                        # Background workers (0..2). 0 = auto, bounded by CPU and heap.
                        backgroundThreads=0
                        """, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            }
            Properties values = new Properties();
            try (Reader input = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { values.load(input); }
            int saving = integer(values, "minimumSavingBytes", 128, 0, 1048576);
            boolean sector = bool(values, "requireSectorSaving", true);
            boolean compact = bool(values, "compactRegions", true);
            int migration = integer(values, "migrationThreads", 0, 0, 16);
            int background = integer(values, "backgroundThreads", 0, 0, 2);
            minimumSaving = saving;
            requireSectorSaving = sector;
            compactRegions = compact;
            migrationThreads = migration;
            backgroundThreads = background;
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalStateException("Cannot load ChunkSqueeze configuration: " + path, failure);
        }
    }

    private static int integer(Properties values, String key, int fallback, int minimum, int maximum) {
        String text = values.getProperty(key);
        if (text == null) return fallback;
        int value = Integer.parseInt(text.trim());
        if (value < minimum || value > maximum) throw new IllegalArgumentException(key + " must be in " + minimum + ".." + maximum);
        return value;
    }

    private static boolean bool(Properties values, String key, boolean fallback) {
        String text = values.getProperty(key);
        if (text == null) return fallback;
        if (text.trim().equalsIgnoreCase("true")) return true;
        if (text.trim().equalsIgnoreCase("false")) return false;
        throw new IllegalArgumentException(key + " must be true or false");
    }
}
