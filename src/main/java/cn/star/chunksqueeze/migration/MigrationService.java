package cn.star.chunksqueeze.migration;

import cn.star.chunksqueeze.Chunksqueeze;
import cn.star.chunksqueeze.Config;
import cn.star.chunksqueeze.CommandLanguage;

import static cn.star.chunksqueeze.CommandLanguage.message;
import cn.star.chunksqueeze.codec.ChunkCodec;
import cn.star.chunksqueeze.storage.StorageGuard;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Pattern;

public final class MigrationService {
    public enum State { WAITING, SAVING, RUNNING, COMPLETE, CANCELLED, FAILED }
    private static final Pattern REGION = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static volatile Job current;
    private MigrationService() {}

    private record Target(Path folder, int x, int z) {
        @Override public String toString() { return folder.resolve("r." + x + "." + z + ".mca").toString(); }
    }
    private static final class Job {
        final MinecraftServer server;
        final long started = System.nanoTime();
        final String id = UUID.randomUUID().toString().substring(0, 8);
        final AtomicBoolean cancelled = new AtomicBoolean();
        volatile State state = State.WAITING;
        volatile boolean recoveryRequired;
        volatile int total, workers;
        final AtomicInteger checked = new AtomicInteger(), converted = new AtomicInteger(), noGain = new AtomicInteger(), busy = new AtomicInteger();
        final LongAdder beforeBytes = new LongAdder(), afterBytes = new LongAdder();
        final Set<Target> active = ConcurrentHashMap.newKeySet();
        final Set<Target> publishing = ConcurrentHashMap.newKeySet();
        volatile String failure = "";
        long lastProgress;
        volatile Thread thread;
        Job(MinecraftServer server) { this.server = server; workers = MigrationWorkers.parallelism(Config.migrationThreads); }
        boolean running() { return state == State.WAITING || state == State.SAVING || state == State.RUNNING; }
    }

    public static Component rejection(MinecraftServer server) {
        if (StorageGuard.isFailed()) return Component.literal(message("服务器存在未处理的存储故障，请稍后再连接。", "The server has an unresolved storage failure. Please reconnect later."));
        Job job = current;
        return job != null && job.server == server && (job.running() || job.recoveryRequired)
                ? Component.literal(message("服务器正在迁移区块，请稍后再连接。", "The server is migrating chunks. Please reconnect later.")) : null;
    }

    public static synchronized int start(CommandSourceStack source, boolean force) {
        MinecraftServer server = source.getServer();
        if (!server.isSameThread()) throw new IllegalStateException("Migration must start on the server thread");
        Job previous = current;
        if (StorageGuard.isFailed() || (previous != null && (previous.running() || previous.recoveryRequired))) {
            source.sendFailure(Component.literal(message("已有迁移任务或未处理的存储故障。请先查看 chunksqueeze migrate status。", "A migration or unresolved storage failure already exists. Check chunksqueeze migrate status first.")));
            return 0;
        }
        if (server.getPlayerList().getPlayerCount() != 0 && !force) {
            source.sendFailure(Component.literal(message("仍有玩家在线。请让玩家下线后在控制台执行，或明确使用 chunksqueeze migrate force。", "Players are still online. Run this from the console after they leave, or explicitly use chunksqueeze migrate force.")));
            return 0;
        }
        Job job = new Job(server);
        current = job; // 先封禁登录，再断开现有玩家。
        source.sendSuccess(() -> Component.literal(message("迁移维护已开启，任务 %s。进度见 chunksqueeze migrate status，每个区域提交校验后将删除原文件，请使用你自己的备份。", "Migration maintenance started, job %s. Check chunksqueeze migrate status. Original files are deleted after each region is committed and verified; keep your own backup.", job.id)), false);
        Chunksqueeze.LOGGER.info("ChunkSqueeze migration {} entered maintenance", job.id);
        if (force) {
            for (var player : List.copyOf(server.getPlayerList().getPlayers())) player.connection.disconnect(rejection(server));
        }
        return 1;
    }

    public static int cancel(CommandSourceStack source) {
        Job job = current;
        if (job == null || job.server != source.getServer() || !job.running()) {
            source.sendFailure(Component.literal(message("没有正在运行的迁移。", "No migration is running.")));
            return 0;
        }
        job.cancelled.set(true);
        if (job.state == State.WAITING) job.state = State.CANCELLED;
        source.sendSuccess(() -> Component.literal(message("已请求取消；正在发布的区域会先完成，已迁移区域不会回滚。", "Cancellation requested. Any region being published will finish first; migrated regions will not be rolled back.")), false);
        return 1;
    }

    public static String status(MinecraftServer server) {
        return status(server, CommandLanguage.ENGLISH);
    }

    public static String status(MinecraftServer server, CommandLanguage language) {
        Job job = current;
        if (job == null || job.server != server) return language.text("迁移：无任务", "Migration: no job");
        long seconds = (System.nanoTime() - job.started) / 1_000_000_000L;
        State state = job.state;
        String stateName = switch (state) {
            case WAITING -> "等待玩家离线";
            case SAVING -> "保存世界";
            case RUNNING -> "运行中";
            case COMPLETE -> "已完成";
            case CANCELLED -> "已取消";
            case FAILED -> "失败";
        };
        return language.text(
                "迁移 %s：%s，区域 %s/%s，已迁移 %s，无收益跳过 %s，繁忙跳过 %s，并发 %s/%s，活动数据减少 %s 字节（已迁移原文件已删除），耗时 %s 秒",
                "Migration %s: %s, regions %s/%s, migrated %s, no-saving skips %s, busy skips %s, workers %s/%s, live data reduced by %s bytes (migrated originals deleted), elapsed %s seconds",
                job.id, language.text(stateName, state.name()), job.checked, job.total, job.converted, job.noGain, job.busy,
                job.active.size(), job.workers, job.beforeBytes.sum() - job.afterBytes.sum(), seconds)
                + (job.active.isEmpty() ? "" : language.text("，当前 %s", ", current %s", job.active.stream().findFirst().map(Target::toString).orElse("")))
                + (job.failure.isEmpty() ? "" : language.text("，错误 %s", ", error %s", job.failure))
                + (job.recoveryRequired ? language.text("，维护锁保持开启，需处理未完成事务", ", maintenance remains locked; unfinished transactions require recovery") : "");
    }

    public static void tick(MinecraftServer server) {
        Job job = current;
        if (job == null || job.server != server || job.state != State.WAITING) return;
        if (job.cancelled.get()) { job.state = State.CANCELLED; return; }
        if (server.getPlayerList().getPlayerCount() != 0) {
            if (System.nanoTime() - job.started > 30_000_000_000L) fail(job, new IOException(message("等待玩家离线超时", "Timed out waiting for players to leave")));
            return;
        }
        try {
            job.state = State.SAVING;
            server.saveEverything(true, true, true);
            StorageGuard.check();
            if (job.cancelled.get()) { job.state = State.CANCELLED; return; }
            Path root = server.getWorldPath(LevelResource.ROOT);
            Set<Path> folders = new LinkedHashSet<>();
            for (ServerLevel level : server.getAllLevels()) {
                folders.add(DimensionType.getStorageFolder(level.dimension(), root).resolve("region").toAbsolutePath().normalize());
            }
            ChunkCodec.Options options = new ChunkCodec.Options(Config.minimumSaving, Config.requireSectorSaving);
            job.state = State.RUNNING;
            job.thread = new Thread(() -> run(job, List.copyOf(folders), options), "ChunkSqueeze-migration-" + job.id);
            job.thread.setDaemon(true);
            job.thread.start();
        } catch (Throwable failure) {
            fail(job, failure);
        }
    }

    private static void run(Job job, List<Path> folders, ChunkCodec.Options options) {
        try {
            List<Target> targets = new ArrayList<>();
            for (Path folder : folders) {
                try (MigrationGate.Lease ignored = MigrationGate.folder(folder)) {
                    try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*.mca")) {
                        for (Path file : files) {
                            var match = REGION.matcher(file.getFileName().toString());
                            if (!match.matches()) throw new IOException(message("非法区域文件名：%s", "Invalid region filename: %s", file));
                            int x = Integer.parseInt(match.group(1)), z = Integer.parseInt(match.group(2));
                            if (!file.getFileName().toString().equals("r." + x + "." + z + ".mca")) throw new IOException(message("非规范区域文件名：%s", "Noncanonical region filename: %s", file));
                            targets.add(new Target(folder, x, z));
                        }
                    } catch (NoSuchFileException absent) { /* 尚未生成的维度没有 region 目录。 */ }
                }
            }
            targets.sort(Comparator.comparing(Target::toString));
            job.total = targets.size();
            MigrationWorkers.run(targets, job.workers, job.cancelled, "ChunkSqueeze-" + job.id,
                    target -> migrate(job, target, options));
            StorageGuard.check();
            job.state = job.cancelled.get() ? State.CANCELLED : State.COMPLETE;
            Chunksqueeze.LOGGER.info("{}", message("%s；已提交区域的原文件已清理，维护结束", "%s; original files of committed regions removed; maintenance ended", status(job.server, CommandLanguage.current())));
        } catch (Throwable failure) {
            fail(job, failure);
        }
    }

    private static void migrate(Job job, Target target, ChunkCodec.Options options) throws Exception {
        job.active.add(target);
        try {
            for (int attempt = 0; attempt < 3 && !job.cancelled.get(); attempt++) {
                StorageGuard.check();
                McaSnapshot snapshot = MigrationGate.exclusive(() -> {
                    if (job.cancelled.get()) throw new MigrationTransaction.Cancelled();
                    MigrationGate.recover(target.folder, target.x, target.z);
                    MigrationGate.evict(target.folder, target.x, target.z);
                    return McaSnapshot.capture(target.folder, target.x, target.z);
                });
                MigrationTransaction.Prepared prepared = null;
                boolean finished = false;
                try {
                    prepared = MigrationTransaction.prepare(snapshot, options, true, job.cancelled::get);
                    MigrationTransaction.Prepared ready = prepared;
                    job.publishing.add(target);
                    MigrationTransaction.PublishResult result;
                    try {
                        result = MigrationGate.exclusive(() -> {
                            MigrationGate.evict(target.folder, target.x, target.z);
                            StorageGuard.check();
                            return MigrationTransaction.publish(ready, job.cancelled::get);
                        });
                        job.publishing.remove(target);
                    } catch (Throwable failure) {
                        job.cancelled.set(true);
                        recoverFailedPublication(job, target, failure);
                        throw failure;
                    }
                    switch (result) {
                        case COMMITTED -> {
                            job.converted.incrementAndGet();
                            job.beforeBytes.add(snapshot.sourceBytes());
                            job.afterBytes.add(prepared.compactBytes());
                            finished = true;
                        }
                        case NO_GAIN -> { job.noGain.incrementAndGet(); finished = true; }
                        case CANCELLED -> job.cancelled.set(true);
                        case CHANGED -> { if (attempt == 2) { job.busy.incrementAndGet(); finished = true; } }
                    }
                } finally {
                    if (prepared != null && !MigrationGate.pending(MigrationTransaction.pendingPath(target.folder, target.x, target.z))) {
                        MigrationTransaction.discard(prepared);
                    }
                }
                if (finished) {
                    job.checked.incrementAndGet();
                    progress(job);
                    return;
                }
            }
        } finally { job.active.remove(target); }
    }

    private static void progress(Job job) {
        synchronized (job) {
            long now = System.nanoTime();
            if (now - job.lastProgress >= 1_000_000_000L || job.checked.get() == job.total) {
                job.lastProgress = now;
                Chunksqueeze.LOGGER.info("{}", status(job.server, CommandLanguage.current()));
            }
        }
    }

    private static void recoverFailedPublication(Job job, Target target, Throwable failure) {
        try {
            MigrationGate.exclusive(() -> { MigrationGate.recover(target.folder, target.x, target.z); return null; });
            job.publishing.remove(target);
        } catch (Throwable recoveryFailure) {
            if (recoveryFailure != failure) failure.addSuppressed(recoveryFailure);
            job.recoveryRequired = true;
            StorageGuard.fail("Migration recovery at " + target, recoveryFailure);
        }
    }

    private static void fail(Job job, Throwable failure) {
        job.cancelled.set(true);
        for (Target target : List.copyOf(job.publishing)) recoverFailedPublication(job, target, failure);
        job.recoveryRequired |= StorageGuard.isFailed();
        job.failure = failure.toString();
        job.state = State.FAILED;
        Chunksqueeze.LOGGER.error("ChunkSqueeze migration {} failed; recoveryRequired={}", job.id, job.recoveryRequired, failure);
    }

    public static void stopping(MinecraftServer server) {
        Job job = current;
        if (job != null && job.server == server && job.running()) {
            job.cancelled.set(true);
            if (job.state == State.WAITING) job.state = State.CANCELLED;
        }
    }

    public static void stopAndAwait(MinecraftServer server) {
        stopping(server);
        Job job = current;
        if (job == null || job.server != server) return;
        Thread worker = job.thread;
        if (worker == null || worker == Thread.currentThread()) return;
        boolean interrupted = false;
        // 异常停服也必须在释放世界锁之前等发布/恢复结束，不能中断文件通道。
        while (worker.isAlive()) {
            try { worker.join(); }
            catch (InterruptedException interruption) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    public static synchronized void stopped(MinecraftServer server) {
        stopAndAwait(server);
        Job job = current;
        if (job != null && job.server == server) {
            job.cancelled.set(true);
            current = null;
        }
    }
}
