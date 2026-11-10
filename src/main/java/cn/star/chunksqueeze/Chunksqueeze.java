package cn.star.chunksqueeze;

import cn.star.chunksqueeze.storage.StorageGuard;
import cn.star.chunksqueeze.storage.BackgroundReencoder;
import cn.star.chunksqueeze.migration.MigrationService;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.chat.Component;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

@Mod(Chunksqueeze.MODID)
public final class Chunksqueeze {
    public static final String MODID = "chunksqueeze";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Chunksqueeze() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        modEventBus.addListener(Config::onLoad);
        MinecraftForge.EVENT_BUS.addListener(this::registerCommands);
        MinecraftForge.EVENT_BUS.addListener(this::beforeTick);
        MinecraftForge.EVENT_BUS.addListener((ServerAboutToStartEvent event) -> {
            BackgroundReencoder.start(Config.backgroundThreads);
            LOGGER.info("ChunkSqueeze {}", BackgroundReencoder.status());
        });
        MinecraftForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> stopStorageWorkers(event.getServer()));
        MinecraftForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> {
            try { stopStorageWorkers(event.getServer()); }
            finally { MigrationService.stopped(event.getServer()); }
        });
    }

    public static void stopStorageWorkers(MinecraftServer server) {
        MigrationService.stopping(server);
        // 等待后台任务时不能持有迁移或存储锁。
        try { BackgroundReencoder.stopAndAwait(); }
        finally { MigrationService.stopAndAwait(server); }
    }

    private void beforeTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        StorageGuard.checkServer();
        MigrationService.tick(event.getServer());
    }

    private void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("chunksqueeze")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("status").executes(context -> {
                    CommandLanguage language = CommandLanguage.current();
                    context.getSource().sendSuccess(() -> Component.literal(StorageGuard.status(language) + " | "
                            + MigrationService.status(context.getSource().getServer(), language) + " | "
                            + BackgroundReencoder.status(language)), false);
                    return 1;
                }))
                .then(Commands.literal("migrate").requires(source -> source.hasPermission(4))
                        .executes(context -> MigrationService.start(context.getSource(), false))
                        .then(Commands.literal("force").executes(context -> MigrationService.start(context.getSource(), true)))
                        .then(Commands.literal("cancel").executes(context -> MigrationService.cancel(context.getSource())))
                        .then(Commands.literal("status").executes(context -> {
                            CommandLanguage language = CommandLanguage.current();
                            context.getSource().sendSuccess(() -> Component.literal(MigrationService.status(context.getSource().getServer(), language)), false);
                            return 1;
                        }))));
    }
}
