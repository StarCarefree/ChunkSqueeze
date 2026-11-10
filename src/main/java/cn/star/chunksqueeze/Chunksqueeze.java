package cn.star.chunksqueeze;

import cn.star.chunksqueeze.storage.StorageGuard;
import cn.star.chunksqueeze.storage.BackgroundReencoder;
import cn.star.chunksqueeze.migration.MigrationService;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.chat.Component;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import org.slf4j.Logger;

public final class Chunksqueeze implements ModInitializer {
    public static final String MODID = "chunksqueeze";
    public static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public void onInitialize() {
        Config.load();
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> registerCommands(dispatcher));
        ServerTickEvents.START_SERVER_TICK.register(this::beforeTick);
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            BackgroundReencoder.start(Config.backgroundThreads);
            LOGGER.info("ChunkSqueeze {}", BackgroundReencoder.status());
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(Chunksqueeze::stopStorageWorkers);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            try { stopStorageWorkers(server); }
            finally { MigrationService.stopped(server); }
        });
    }

    public static void stopStorageWorkers(MinecraftServer server) {
        MigrationService.stopping(server);
        // 等待后台任务时不能持有迁移或存储锁。
        try { BackgroundReencoder.stopAndAwait(); }
        finally { MigrationService.stopAndAwait(server); }
    }

    private void beforeTick(MinecraftServer server) {
        StorageGuard.checkServer();
        MigrationService.tick(server);
    }

    private void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("chunksqueeze")
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
