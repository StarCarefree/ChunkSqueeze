package cn.star.chunksqueeze;

import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.common.ForgeConfigSpec;

public final class Config {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    private static final ForgeConfigSpec.IntValue MINIMUM_SAVING = BUILDER
            .comment("Minimum compressed bytes saved per chunk.")
            .defineInRange("minimumSavingBytes", 128, 0, 1048576);
    private static final ForgeConfigSpec.BooleanValue REQUIRE_SECTOR = BUILDER
            .comment("Only encode chunks that save an allocation unit.")
            .define("requireSectorSaving", true);
    private static final ForgeConfigSpec.BooleanValue COMPACT_REGIONS = BUILDER
            .comment("Use compact files for new regions. Existing MCA regions stay unchanged.")
            .define("compactRegions", true);
    private static final ForgeConfigSpec.IntValue MIGRATION_THREADS = BUILDER
            .comment("Migration workers. 0 = auto; bounded by CPU and heap.")
            .defineInRange("migrationThreads", 0, 0, 16);
    private static final ForgeConfigSpec.IntValue BACKGROUND_THREADS = BUILDER
            .comment("Background workers. 0 = auto by CPU and heap, up to 2.")
            .defineInRange("backgroundThreads", 0, 0, 2);
    public static final ForgeConfigSpec SPEC = BUILDER.build();

    public static volatile boolean compactRegions = true;
    public static volatile int migrationThreads = 0;
    public static volatile int backgroundThreads = 0;
    public static volatile int minimumSaving = 128;
    public static volatile boolean requireSectorSaving = true;

    private Config() {}

    public static void onLoad(ModConfigEvent event) {
        if (event.getConfig().getSpec() != SPEC) return;
        compactRegions = COMPACT_REGIONS.get();
        migrationThreads = MIGRATION_THREADS.get();
        backgroundThreads = BACKGROUND_THREADS.get();
        minimumSaving = MINIMUM_SAVING.get();
        requireSectorSaving = REQUIRE_SECTOR.get();
    }
}
