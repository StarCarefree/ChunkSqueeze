package cn.star.chunksqueeze.gametest;

import cn.star.chunksqueeze.Config;
import cn.star.chunksqueeze.codec.ChunkCodec;
import cn.star.chunksqueeze.migration.*;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.DeflaterOutputStream;

@GameTestHolder("chunksqueeze")
@PrefixGameTestTemplate(false)
public final class MigrationGameTests {
    private static final String INFO = "chunk";
    private static final ChunkPos POS = new ChunkPos(0, 0);

    @GameTest(templateNamespace = "chunksqueeze", template = "empty", batch = "migration-storage", timeoutTicks = 400)
    public static void cachedStorageSwitchesWithoutLosingQueuedWrites(GameTestHelper helper) throws Exception {
        Path folder = Files.createTempDirectory("chunksqueeze-live-migration-");
        CompoundTag first = tag(1);
        createMca(folder, first);
        boolean compact = Config.compactRegions;
        Config.compactRegions = false;
        try (TestWorker worker = new TestWorker(folder)) {
            ChunkPos empty = new ChunkPos(64, 0);
            check(worker.loadAsync(empty).join().isEmpty(), "Initial empty read differs");
            MigrationGate.exclusive(() -> { MigrationGate.evict(folder, 2, 0); return null; });
            check(worker.loadAsync(empty).join().isEmpty(), "Empty native region could not reopen");
            check(first.equals(worker.loadAsync(POS).join().orElseThrow()), "Initial cached read differs");
            McaSnapshot firstSnapshot = capture(folder);
            var firstPrepared = MigrationTransaction.prepare(firstSnapshot, new ChunkCodec.Options(128, true), true, () -> false);
            CompoundTag second = tag(2);
            worker.store(POS, second).join();
            var changed = MigrationGate.exclusive(() -> {
                MigrationGate.evict(folder, 0, 0);
                return MigrationTransaction.publish(firstPrepared, () -> false);
            });
            check(changed == MigrationTransaction.PublishResult.CHANGED, "Stale snapshot overwrote a newer save");
            MigrationTransaction.discard(firstPrepared);
            check(Files.exists(folder.resolve("r.0.0.mca")), "Stale attempt removed MCA");
            var prepared = MigrationTransaction.prepare(capture(folder), new ChunkCodec.Options(128, true), true, () -> false);
            var committed = MigrationGate.exclusive(() -> {
                MigrationGate.evict(folder, 0, 0);
                return MigrationTransaction.publish(prepared, () -> false);
            });
            check(committed == MigrationTransaction.PublishResult.COMMITTED, "Migration did not commit");
            check(second.equals(worker.loadAsync(POS).join().orElseThrow()), "Cached worker did not switch to CQS");
            check(!Files.exists(folder.resolve("r.0.0.mca")), "Live MCA remained after commit");
            check(!Files.exists(prepared.directory()), "Committed originals were retained");
            AtomicReference<CompletableFuture<Void>> write = new AtomicReference<>();
            CompoundTag third = tag(3);
            MigrationGate.exclusive(() -> {
                write.set(worker.store(POS, third));
                try {
                    write.get().get(100, TimeUnit.MILLISECONDS);
                    throw new AssertionError("Storage write bypassed publication gate");
                } catch (TimeoutException expected) {
                    // 发布门释放后，排队的保存才可进入新后端。
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new IOException(interrupted);
                } catch (ExecutionException failure) { throw new IOException(failure); }
                return null;
            });
            write.get().get(5, TimeUnit.SECONDS);
            check(third.equals(worker.loadAsync(POS).join().orElseThrow()), "Queued save did not reach CQS");
            check(!Files.exists(MigrationTransaction.pendingPath(folder, 0, 0)), "Committed journal remained");
            MigrationTransaction.discard(prepared);
            helper.succeed();
        } finally {
            Config.compactRegions = compact;
        }
    }

    @GameTest(templateNamespace = "chunksqueeze", template = "empty", batch = "migration-command", timeoutTicks = 120000)
    public static void commandBlocksAdmissionAndReturnsToNormal(GameTestHelper helper) throws Exception {
        var server = helper.getLevel().getServer();
        var source = server.createCommandSourceStack();
        var dispatcher = server.getCommands().getDispatcher();
        try {
            dispatcher.execute("chunksqueeze migrate", source.withPermission(2));
            throw new AssertionError("Level-2 operator could start maintenance");
        } catch (CommandSyntaxException expected) { /* 迁移需要权限等级 4。 */ }
        var address = new InetSocketAddress("127.0.0.1", 12345);
        var profile = new GameProfile(UUID.randomUUID(), "migration_probe");
        var baselineAdmission = server.getPlayerList().canPlayerLogin(address, profile);
        check(dispatcher.execute("chunksqueeze migrate", source) == 1, "Command did not start");
        check(MigrationService.rejection(server).equals(server.getPlayerList().canPlayerLogin(address, profile)), "Maintenance allowed login");
        // 未安装最后一道入口拦截时，null 玩家会进入原版方法并立即失败。
        server.getPlayerList().placeNewPlayer(new Connection(PacketFlow.SERVERBOUND), null);
        check(server.getPlayerList().getPlayerCount() == 0, "Final admission guard added a player");
        check(dispatcher.execute("chunksqueeze migrate cancel", source) == 1, "Waiting migration could not cancel");
        check(MigrationService.rejection(server) == null, "Cancelled maintenance stayed locked");
        check(dispatcher.execute("chunksqueeze migrate", source) == 1, "Restart after cancellation failed");
        MigrationService.tick(server);
        MigrationService.stopAndAwait(server);
        check(MigrationService.rejection(server) == null, "Shutdown wait left a running migration");
        check(MigrationService.status(server).contains("CANCELLED") || MigrationService.status(server).contains("COMPLETE"),
                "Shutdown did not wait for a terminal state");
        check(dispatcher.execute("chunksqueeze migrate", source) == 1, "Restart after shutdown cancellation failed");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        helper.succeedWhen(() -> {
            // GameTestServer 不限 tick 速度，给后台磁盘任务留出执行时间。
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            String status = MigrationService.status(server);
            check(!status.contains("FAILED") && System.nanoTime() < deadline, status);
            helper.assertTrue(status.contains("COMPLETE"), status);
            helper.assertTrue(MigrationService.rejection(server) == null, "Completed maintenance stayed locked");
            helper.assertTrue(java.util.Objects.equals(server.getPlayerList().canPlayerLogin(address, profile), baselineAdmission), "Normal login policy was not restored");
        });
    }

    private static McaSnapshot capture(Path folder) throws IOException {
        return MigrationGate.exclusive(() -> {
            MigrationGate.evict(folder, 0, 0);
            return McaSnapshot.capture(folder, 0, 0);
        });
    }
    private static CompoundTag tag(int value) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", 3955);
        tag.putInt("xPos", 0); tag.putInt("zPos", 0);
        tag.putString("Status", "minecraft:full");
        tag.putInt("test:revision", value);
        ListTag sections = new ListTag();
        CompoundTag section = new CompoundTag(); section.putByte("Y", (byte) 0);
        CompoundTag states = new CompoundTag(); ListTag palette = new ListTag();
        CompoundTag stone = new CompoundTag(); stone.putString("Name", "minecraft:stone"); palette.add(stone);
        states.put("palette", palette); section.put("block_states", states); sections.add(section);
        tag.put("sections", sections);
        return tag;
    }
    private static void createMca(Path folder, CompoundTag tag) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(new DeflaterOutputStream(bytes))) { NbtIo.write(tag, output); }
        byte[] packed = bytes.toByteArray();
        try (var file = new RandomAccessFile(folder.resolve("r.0.0.mca").toFile(), "rw")) {
            file.setLength(256 * 1024);
            file.seek(0); file.writeInt((2 << 8) | 1);
            file.seek(4096); file.writeInt(42);
            file.seek(8192); file.writeInt(packed.length + 1); file.writeByte(2); file.write(packed);
        }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static final class TestWorker extends IOWorker {
        TestWorker(Path folder) { super(folder, true, INFO); }
    }
}
