package cn.star.chunksqueeze.gametest;

import cn.star.chunksqueeze.Config;
import cn.star.chunksqueeze.codec.ChunkCodec;
import cn.star.chunksqueeze.codec.IndexCodec;
import cn.star.chunksqueeze.storage.StorageGuard;
import cn.star.chunksqueeze.storage.CompactRegion;
import net.minecraft.ReportedException;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.minecraft.nbt.visitors.CollectToTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.*;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

public final class StorageGameTests {
    private static final RegionStorageInfo INFO = new RegionStorageInfo("chunksqueeze-test", Level.OVERWORLD, "chunk");
    private static final ChunkPos POS = new ChunkPos(0, 0);

    @GameTest(template = "chunksqueeze:empty", timeoutTicks = 400)
    public static void storageRoundTrip(GameTestHelper helper) throws Exception {
        check(cn.star.chunksqueeze.storage.BackgroundReencoder.current() != null, "Background lifecycle did not start");
        boolean compactEnabled = Config.compactRegions;
        boolean sectorSaving = Config.requireSectorSaving;
        int minimum = Config.minimumSaving;
        Path folder = Files.createTempDirectory("chunksqueeze-runtime-");
        try {
            Config.compactRegions = false;
            Config.requireSectorSaving = false;
            Config.minimumSaving = 0;
            CompoundTag original = fixture();
            try (TestWorker worker = new TestWorker(folder, INFO)) {
                var pending = worker.store(POS, original);
                check(original.equals(worker.loadAsync(POS).join().orElseThrow()), "Pending read differs");
                pending.join();
                worker.synchronize(true).join();
            }
            CompoundTag stored = readRaw(folder);
            check(stored.contains(ChunkCodec.KEY), "Mixin did not encode the stored chunk");
            check(Files.exists(folder.resolve("CHUNKSQUEEZE_FORMAT.txt")), "Missing format marker");
            check(!original.contains(ChunkCodec.KEY), "Write mutated the caller's NBT");
            try (TestWorker worker = new TestWorker(folder, INFO)) {
                check(original.equals(worker.loadAsync(POS).join().orElseThrow()), "Disk read differs");
                CollectToTag visitor = new CollectToTag();
                worker.scanChunk(POS, visitor).join();
                check(original.equals(visitor.getResult()), "Streaming scan did not restore NBT");
                Config.minimumSaving = 1048576;
                check(original.equals(worker.loadAsync(POS).join().orElseThrow()), "Saving threshold affected decoding");
                worker.store(POS, original).join();
                worker.synchronize(true).join();
            }
            check(!readRaw(folder).contains(ChunkCodec.KEY), "Saving threshold was ignored");
            try (TestWorker worker = new TestWorker(folder, INFO)) {
                worker.store(POS, null).join();
                worker.synchronize(true).join();
                check(worker.loadAsync(POS).join().isEmpty(), "Chunk deletion failed");
            }
            Config.minimumSaving = 0;
            Path entities = Files.createDirectories(folder.resolve("entities"));
            RegionStorageInfo entityInfo = new RegionStorageInfo("chunksqueeze-test", Level.OVERWORLD, "entities");
            try (TestWorker worker = new TestWorker(entities, entityInfo)) {
                worker.store(POS, original).join();
                worker.synchronize(true).join();
            }
            check(!readRaw(entities).contains(ChunkCodec.KEY), "Entity storage was transformed");
            testLargeStandardChunk(folder.resolve("large"), original);
            testCorruptionGuard(folder, original, helper);
            testMalformedHeader(folder.resolve("bad-header"));
            testMetadataAndDeserializationGuards(helper);
            helper.succeed();
        } finally {
            resetGuard();
            Config.compactRegions = compactEnabled;
            Config.requireSectorSaving = sectorSaving;
            Config.minimumSaving = minimum;
        }
    }

    @GameTest(template = "chunksqueeze:empty", timeoutTicks = 400)
    public static void compactStorageRoundTrip(GameTestHelper helper) throws Exception {
        boolean compactEnabled = Config.compactRegions;
        boolean sectorSaving = Config.requireSectorSaving;
        int minimum = Config.minimumSaving;
        Path folder = Files.createTempDirectory("chunksqueeze-compact-runtime-");
        try {
            Config.compactRegions = true;
            Config.requireSectorSaving = false;
            Config.minimumSaving = 0;
            CompoundTag original = fixture();
            try (TestWorker worker = new TestWorker(folder, INFO)) {
                check(worker.loadAsync(POS).join().isEmpty(), "New compact region was not empty");
                check(!Files.exists(folder.resolve("r.0.0.mca")), "Empty read created an MCA");
                worker.store(POS, original).join();
                worker.synchronize(true).join();
                check(cn.star.chunksqueeze.storage.BackgroundReencoder.current() != null, "Background service did not start");
                check(cn.star.chunksqueeze.storage.BackgroundReencoder.awaitGlobalIdle(15000), "Background encoding did not drain");
            }
            Path compact = folder.resolve("r.0.0.cqs");
            check(Files.exists(compact) && Files.exists(CompactRegion.ownerPath(compact)), "Missing compact files");
            check(!Files.exists(folder.resolve("r.0.0.mca")), "Compact writes also created MCA");
            try (CompactRegion region = CompactRegion.open(compact, 0, 0, false)) {
                var record = region.read(0);
                try (var input = new java.io.DataInputStream(new java.util.zip.InflaterInputStream(new java.io.ByteArrayInputStream(record.payload())))) {
                    check(NbtIo.read(input).contains(ChunkCodec.KEY), "512-byte backend did not use state encoding");
                }
            }
            Config.compactRegions = false;
            try (TestWorker worker = new TestWorker(folder, INFO)) {
                check(original.equals(worker.loadAsync(POS).join().orElseThrow()), "Existing CQS ignored after disabling creation");
                CollectToTag visitor = new CollectToTag();
                worker.scanChunk(POS, visitor).join();
                check(original.equals(visitor.getResult()), "Compact scan differs");
                worker.store(POS, original).join();
                worker.synchronize(true).join();
                worker.store(POS, null).join();
                check(worker.loadAsync(POS).join().isEmpty(), "Compact deletion failed");
                worker.store(POS, original).join();
            }
            check(!Files.exists(folder.resolve("r.0.0.mca")), "Disabling compact creation abandoned CQS ownership");
            Path legacy = folder.resolve("legacy");
            try (TestWorker worker = new TestWorker(legacy, INFO)) { worker.store(POS, original).join(); }
            Config.compactRegions = true;
            try (TestWorker worker = new TestWorker(legacy, INFO)) {
                check(original.equals(worker.loadAsync(POS).join().orElseThrow()), "Legacy MCA read failed");
                worker.store(POS, original).join();
            }
            check(Files.exists(legacy.resolve("r.0.0.mca")) && !Files.exists(legacy.resolve("r.0.0.cqs")), "Legacy MCA was migrated online");
            Path recreated = folder.resolve("recreated");
            try (TestWorker worker = new TestWorker(recreated, INFO.withTypeSuffix("target"))) { worker.store(POS, original).join(); }
            check(Files.exists(recreated.resolve("r.0.0.mca")), "World recreation target was unexpectedly compact");
            Files.delete(CompactRegion.ownerPath(compact));
            try (TestWorker worker = new TestWorker(folder, INFO)) {
                try {
                    worker.loadAsync(POS).join();
                    throw new AssertionError("Missing ownership was treated as empty terrain");
                } catch (CompletionException expected) {
                    check(expected.getCause() instanceof StorageGuard.StorageFailure, "Missing ownership did not lock storage");
                }
            }
            helper.succeed();
        } finally {
            resetGuard();
            Config.compactRegions = compactEnabled;
            Config.requireSectorSaving = sectorSaving;
            Config.minimumSaving = minimum;
        }
    }

    private static void testCorruptionGuard(Path folder, CompoundTag original, GameTestHelper helper) throws Exception {
        CompoundTag bad = ChunkCodec.encode(original, new ChunkCodec.Options(0, false)).tag();
        check(bad.contains(ChunkCodec.KEY), "Fixture did not compress");
        bad.getCompound(ChunkCodec.KEY).putInt("version", 999);
        try (RegionFile region = new RegionFile(INFO, folder.resolve("r.0.0.mca"), folder, true)) {
            try (var out = region.getChunkDataOutputStream(POS)) { NbtIo.write(bad, out); }
            region.flush();
        }
        try (TestWorker worker = new TestWorker(folder, INFO)) {
            try {
                worker.loadAsync(POS).join();
                throw new AssertionError("Corrupt format was accepted");
            } catch (CompletionException expected) {
                check(expected.getCause() instanceof StorageGuard.StorageFailure, "Wrong corruption failure");
            }
            try {
                worker.store(POS, null).join();
                throw new AssertionError("Deletion was allowed after a storage fault");
            } catch (CompletionException expected) {
                check(expected.getCause() instanceof StorageGuard.StorageFailure, "Write lock missing");
            }
            ChunkMap map = helper.getLevel().getChunkSource().chunkMap;
            var loadFailure = ChunkMap.class.getDeclaredMethod("handleChunkLoadFailure", Throwable.class, ChunkPos.class);
            loadFailure.setAccessible(true);
            try {
                loadFailure.invoke(map, new IOException("test"), POS);
                throw new AssertionError("Corruption could fall back to an empty chunk");
            } catch (InvocationTargetException expected) {
                check(expected.getCause() instanceof ReportedException, "Fatal load guard missing");
            }
            var replacement = ChunkMap.class.getDeclaredMethod("markPositionReplaceable", ChunkPos.class);
            replacement.setAccessible(true);
            try {
                replacement.invoke(map, POS);
                throw new AssertionError("Corruption could mark the chunk replaceable");
            } catch (InvocationTargetException expected) {
                check(expected.getCause() instanceof ReportedException, "Replacement guard missing");
            }
        } finally {
            resetGuard();
        }
    }

    private static void testLargeStandardChunk(Path folder, CompoundTag original) throws Exception {
        CompoundTag large = original.copy();
        large.putByteArray("test:large_attachment", new byte[65 * 1024 * 1024]);
        try (TestWorker worker = new TestWorker(folder, INFO)) {
            worker.store(POS, large).join();
            worker.synchronize(true).join();
        }
        try (TestWorker worker = new TestWorker(folder, INFO)) {
            check(large.equals(worker.loadAsync(POS).join().orElseThrow()), "Large standard NBT became unreadable");
        }
    }

    private static void testMalformedHeader(Path folder) throws Exception {
        Files.createDirectories(folder);
        byte[] before = new byte[12288];
        java.nio.ByteBuffer.wrap(before).putInt((100 << 8) | 1);
        Path file = folder.resolve("r.0.0.mca");
        Files.write(file, before);
        try (TestWorker worker = new TestWorker(folder, INFO)) {
            try {
                worker.loadAsync(POS).join();
                throw new AssertionError("Invalid region index became an empty chunk");
            } catch (CompletionException expected) {
                check(expected.getCause() instanceof StorageGuard.StorageFailure, "Header guard missing");
            }
        } finally {
            resetGuard();
        }
        check(java.util.Arrays.equals(before, Files.readAllBytes(file)), "Original header was mutated");
    }

    private static void testMetadataAndDeserializationGuards(GameTestHelper helper) throws Exception {
        var valid = ChunkMap.class.getDeclaredMethod("isChunkDataValid", CompoundTag.class);
        valid.setAccessible(true);
        try {
            valid.invoke(null, new CompoundTag());
            throw new AssertionError("Invalid metadata was silently skipped");
        } catch (InvocationTargetException expected) {
            check(expected.getCause() instanceof StorageGuard.StorageFailure, "Metadata guard missing");
        } finally {
            resetGuard();
        }
        var handler = ChunkMap.class.getDeclaredMethod("handleChunkLoadFailure", Throwable.class, ChunkPos.class);
        handler.setAccessible(true);
        try {
            handler.invoke(helper.getLevel().getChunkSource().chunkMap, new IllegalArgumentException("bad biome palette"), POS);
            throw new AssertionError("Deserializer failure fell back to empty terrain");
        } catch (InvocationTargetException expected) {
            check(expected.getCause() instanceof ReportedException, "Deserializer guard missing");
        } finally {
            resetGuard();
        }
    }

    private static void resetGuard() throws Exception {
        // 仅在隔离测试中复位；先等待可能观察到故障的后台线程退出。
        cn.star.chunksqueeze.storage.BackgroundReencoder.stopAndAwait();
        var field = StorageGuard.class.getDeclaredField("FAILURE");
        field.setAccessible(true);
        ((AtomicReference<?>) field.get(null)).set(null);
        cn.star.chunksqueeze.storage.BackgroundReencoder.start(Config.backgroundThreads);
    }

    private static CompoundTag readRaw(Path folder) throws Exception {
        try (RegionFile region = new RegionFile(INFO, folder.resolve("r.0.0.mca"), folder, true);
             var input = region.getChunkDataInputStream(POS)) {
            if (input == null) throw new AssertionError("Missing test chunk");
            return NbtIo.read(input);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static CompoundTag fixture() {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", 3955);
        root.putString("Status", "minecraft:full");
        root.putInt("xPos", 0);
        root.putInt("zPos", 0);
        root.putString("test:metadata", "保持完整 🌍");
        ListTag sections = new ListTag();
        for (int y = -4; y < 20; y++) {
            CompoundTag section = new CompoundTag();
            section.putByte("Y", (byte) y);
            ListTag palette = new ListTag();
            for (int p = 0; p < 20; p++) {
                CompoundTag state = new CompoundTag();
                state.putString("Name", "test:long_block_state_identifier_" + p);
                CompoundTag properties = new CompoundTag();
                properties.putString("unknown_property", "自定义 🌍 value " + p);
                properties.putString("waterlogged", "false");
                state.put("Properties", properties);
                palette.add(state);
            }
            int[] values = new int[4096];
            for (int i = 0; i < values.length; i++) values[i] = (i / 64 + y + 4) % 20;
            CompoundTag states = new CompoundTag();
            states.put("palette", palette);
            states.putLongArray("data", IndexCodec.packVanilla(values, 20));
            section.put("block_states", states);
            sections.add(section);
        }
        root.put("sections", sections);
        return root;
    }

    private static final class TestWorker extends IOWorker {
        TestWorker(Path folder, RegionStorageInfo info) { super(info, folder, true); }
    }
}
