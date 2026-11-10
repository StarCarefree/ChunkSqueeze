package cn.star.chunksqueeze;

import cn.star.chunksqueeze.storage.StorageGuard;
import cn.star.chunksqueeze.storage.BackgroundReencoder;
import cn.star.chunksqueeze.migration.MigrationService;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

@Mod(Chunksqueeze.MODID)
public final class Chunksqueeze {
    public static final String MODID = "chunksqueeze";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Chunksqueeze(IEventBus modEventBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        modEventBus.addListener(Config::onLoad);
        NeoForge.EVENT_BUS.addListener(this::registerCommands);
        NeoForge.EVENT_BUS.addListener(this::beforeTick);
        NeoForge.EVENT_BUS.addListener((ServerAboutToStartEvent event) -> {
            BackgroundReencoder.start(Config.backgroundThreads);
            LOGGER.info("ChunkSqueeze {}", BackgroundReencoder.status());
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> stopStorageWorkers(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> {
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

    private void beforeTick(ServerTickEvent.Pre event) {
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
