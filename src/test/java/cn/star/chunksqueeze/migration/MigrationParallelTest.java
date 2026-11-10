package cn.star.chunksqueeze.migration;

import cn.star.chunksqueeze.codec.ChunkCodec;
import cn.star.chunksqueeze.storage.CompactRegion;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;

import static org.junit.jupiter.api.Assertions.*;

class MigrationParallelTest {
    @TempDir Path temporary;
    private record Target(Path folder, CompoundTag expected) {}
    private List<Target> fixtures(int count) throws Exception {
        List<Target> targets = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Path folder = Files.createDirectory(temporary.resolve("region-" + i));
            CompoundTag tag = new CompoundTag();
            tag.putInt("xPos", 0); tag.putInt("zPos", 0); tag.putString("Status", "minecraft:full");
            tag.putInt("test:region", i);
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(new DeflaterOutputStream(bytes))) { NbtIo.write(tag, out); }
            try (var file = new RandomAccessFile(folder.resolve("r.0.0.mca").toFile(), "rw")) {
                file.setLength(256 * 1024);
                file.seek(0); file.writeInt((2 << 8) | 1);
                file.seek(4096); file.writeInt(100);
                file.seek(8192); file.writeInt(bytes.size() + 1); file.writeByte(2); file.write(bytes.toByteArray());
            }
            targets.add(new Target(folder, tag));
        }
        return targets;
    }
    @Test
    void parallelPreparationPublishesEachRegionExactlyOnce() throws Exception {
        var targets = fixtures(8);
        AtomicBoolean cancel = new AtomicBoolean();
        CountDownLatch firstWave = new CountDownLatch(4);
        MigrationWorkers.run(targets, 4, cancel, "parallel-test", target -> {
            McaSnapshot snapshot = MigrationGate.exclusive(() -> McaSnapshot.capture(target.folder, 0, 0));
            firstWave.countDown(); assertTrue(firstWave.await(5, TimeUnit.SECONDS));
            var prepared = MigrationTransaction.prepare(snapshot, new ChunkCodec.Options(128, true), true, cancel::get);
            assertEquals(MigrationTransaction.PublishResult.COMMITTED,
                    MigrationGate.exclusive(() -> MigrationTransaction.publish(prepared, cancel::get)));
            MigrationTransaction.discard(prepared);
        });
        for (Target target : targets) {
            assertFalse(Files.exists(target.folder.resolve("r.0.0.mca")));
            assertFalse(Files.exists(MigrationTransaction.pendingPath(target.folder, 0, 0)));
            try (CompactRegion region = CompactRegion.open(target.folder.resolve("r.0.0.cqs"), 0, 0, false)) {
                assertEquals(100, region.timestamp(0));
                try (var input = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(region.read(0).payload())))) {
                    assertEquals(target.expected, ChunkCodec.decode(NbtIo.read(input)));
                }
            }
        }
    }
    @Test
    void onePublicationFailureDrainsOtherWorkersWithoutLeavingHalfPublishedRegions() throws Exception {
        var targets = fixtures(8);
        AtomicBoolean cancel = new AtomicBoolean();
        CountDownLatch preparedTogether = new CountDownLatch(4);
        assertThrows(IOException.class, () -> MigrationWorkers.run(targets, 4, cancel, "failure-test", target -> {
            var snapshot = MigrationGate.exclusive(() -> McaSnapshot.capture(target.folder, 0, 0));
            var prepared = MigrationTransaction.prepare(snapshot, new ChunkCodec.Options(128, true), true, cancel::get);
            try {
                preparedTogether.countDown(); assertTrue(preparedTogether.await(5, TimeUnit.SECONDS));
                MigrationGate.exclusive(() -> MigrationTransaction.publish(prepared, cancel::get, stage -> {
                    if (target == targets.get(0) && stage == MigrationTransaction.Stage.MCA_MOVED) throw new IOException("injected publication failure");
                }));
            } finally {
                if (!Files.exists(MigrationTransaction.pendingPath(target.folder, 0, 0))) MigrationTransaction.discard(prepared);
            }
        }));
        assertTrue(cancel.get());
        for (Target target : targets) {
            assertFalse(Files.exists(MigrationTransaction.pendingPath(target.folder, 0, 0)));
            boolean old = Files.exists(target.folder.resolve("r.0.0.mca"));
            assertNotEquals(old, Files.exists(target.folder.resolve("r.0.0.cqs")));
            if (old) {
                var snapshot = McaSnapshot.capture(target.folder, 0, 0);
                try (var input = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(snapshot.chunks().get(0).payload())))) {
                    assertEquals(target.expected, NbtIo.read(input));
                }
            } else try (CompactRegion region = CompactRegion.open(target.folder.resolve("r.0.0.cqs"), 0, 0, false)) {
                try (var input = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(region.read(0).payload())))) {
                    assertEquals(target.expected, ChunkCodec.decode(NbtIo.read(input)));
                }
            }
        }
    }
}
