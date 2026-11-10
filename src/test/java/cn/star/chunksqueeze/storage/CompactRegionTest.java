package cn.star.chunksqueeze.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CompactRegionTest {
    @TempDir Path directory;

    private Path path(String name) throws IOException {
        return Files.createDirectories(directory.resolve(name)).resolve("r.0.0.cqs");
    }
    private static byte[] bytes(int length, int seed) {
        byte[] data = new byte[length];
        new Random(seed).nextBytes(data);
        return data;
    }

    @Test
    void writesReopensDeletesAndKeepsTimestamps() throws Exception {
        Path file = path("roundtrip");
        byte[] payload = bytes(1900, 1);
        try (CompactRegion region = CompactRegion.open(file, 0, 0, true)) {
            assertNull(region.read(0));
            region.write(0, payload, 2, -1);
            region.write(1023, bytes(1, 2), 3, 123);
            assertArrayEquals(payload, region.read(0).payload());
            assertEquals(-1, region.timestamp(0));
        }
        try (CompactRegion region = CompactRegion.open(file, 0, 0, false)) {
            assertArrayEquals(payload, region.read(0).payload());
            assertEquals(123, region.read(1023).timestamp());
            region.write(0, null, 0, 42);
        }
        try (CompactRegion region = CompactRegion.open(file, 0, 0, false)) {
            assertNull(region.read(0));
            assertEquals(42, region.timestamp(0));
        }
    }

    @Test
    void everyInterruptedWriteRecoversAWholeVersion() throws Exception {
        byte[] before = bytes(900, 3), after = bytes(1600, 4), neighbor = bytes(700, 5);
        for (CompactRegion.Stage stage : CompactRegion.Stage.values()) {
            Path file = path(stage.name());
            try (CompactRegion region = CompactRegion.open(file, 0, 0, true)) {
                region.write(0, before, 2, 1);
                region.write(1, neighbor, 2, 1);
                region.setFaultInjector(actual -> { if (actual == stage) throw new IOException("power interruption"); });
                assertThrows(IOException.class, () -> region.write(0, after, 2, 2));
                assertThrows(IOException.class, () -> region.write(2, before, 2, 3));
            }
            try (CompactRegion region = CompactRegion.open(file, 0, 0, false)) {
                boolean published = stage.ordinal() >= CompactRegion.Stage.FIRST_COMMIT_FORCED.ordinal();
                assertArrayEquals(published ? after : before, region.read(0).payload());
                assertArrayEquals(neighbor, region.read(1).payload());
            }
        }
    }

    @Test
    void recoveredSingleCommitIsRepairedBeforeTheNextWrite() throws Exception {
        Path file = path("repair");
        byte[] first = bytes(800, 1), second = bytes(800, 2), third = bytes(800, 3);
        try (CompactRegion region = CompactRegion.open(file, 0, 0, true)) {
            region.write(0, first, 2, 1);
            region.setFaultInjector(stage -> { if (stage == CompactRegion.Stage.FIRST_COMMIT_FORCED) throw new IOException("interrupt"); });
            assertThrows(IOException.class, () -> region.write(0, second, 2, 2));
        }
        try (CompactRegion region = CompactRegion.open(file, 0, 0, false)) {
            assertArrayEquals(second, region.read(0).payload());
            region.setFaultInjector(stage -> { if (stage == CompactRegion.Stage.INDEX_FORCED) throw new IOException("interrupt"); });
            assertThrows(IOException.class, () -> region.write(0, third, 2, 3));
        }
        flip(file, 512 + 40); // 模拟下一次首份提交的撕裂写入。
        try (CompactRegion region = CompactRegion.open(file, 0, 0, false)) {
            assertArrayEquals(second, region.read(0).payload());
        }
    }

    @Test
    void committedBadIndexNeverFallsBackToAnOlderCommit() throws Exception {
        Path file = path("index");
        byte[] oldMarker;
        try (CompactRegion region = CompactRegion.open(file, 0, 0, true)) {
            region.write(0, bytes(600, 1), 2, 1);
            oldMarker = read(file, 512, 512);
            region.write(0, bytes(600, 2), 2, 2);
        }
        try (var output = new RandomAccessFile(file.toFile(), "rw")) {
            output.seek(1024); output.write(oldMarker);
        }
        flip(file, 1536 + 20);
        assertThrows(IOException.class, () -> CompactRegion.open(file, 0, 0, false));
    }

    @Test
    void missingOwnershipAndPayloadCorruptionFailClosed() throws Exception {
        Path file = path("corrupt");
        try (CompactRegion region = CompactRegion.open(file, 0, 0, true)) { region.write(0, bytes(700, 6), 2, 1); }
        flip(file, CompactRegion.DATA_START + 1);
        try (CompactRegion region = CompactRegion.open(file, 0, 0, false)) {
            assertThrows(IOException.class, () -> region.read(0));
        }
        Files.delete(CompactRegion.ownerPath(file));
        assertThrows(IOException.class, () -> CompactRegion.open(file, 0, 0, true));
    }

    @Test
    void singleBadCommitIsRecoverableButBothBadAreNot() throws Exception {
        Path file = path("commit");
        byte[] data = bytes(800, 8);
        try (CompactRegion region = CompactRegion.open(file, 0, 0, true)) { region.write(0, data, 2, 1); }
        flip(file, 512 + 40);
        try (CompactRegion region = CompactRegion.open(file, 0, 0, false)) { assertArrayEquals(data, region.read(0).payload()); }
        flip(file, 1024 + 40);
        assertThrows(IOException.class, () -> CompactRegion.open(file, 0, 0, false));
    }

    @Test
    void compactionPreservesIdentityAndHoldsExclusiveOwnership() throws Exception {
        Path file = path("compact");
        byte[] first = bytes(8192, 1), last = bytes(8192, 3);
        UUID identity;
        try (CompactRegion region = CompactRegion.open(file, 0, 0, true)) {
            identity = region.identity();
            region.write(0, first, 2, 1);
            region.write(1, bytes(8192, 2), 2, 2);
            region.write(2, last, 2, 3);
            region.write(1, null, 0, 4);
            long before = Files.size(file);
            assertThrows(IOException.class, () -> CompactRegion.open(file, 0, 0, false));
            region.compact();
            assertEquals(before - 8192, Files.size(file));
            assertEquals(identity, region.identity());
            assertThrows(IOException.class, () -> CompactRegion.open(file, 0, 0, false));
            assertArrayEquals(first, region.read(0).payload());
            assertArrayEquals(last, region.read(2).payload());
        }
        try (CompactRegion region = CompactRegion.open(file, 0, 0, false)) {
            assertEquals(identity, region.identity());
            assertEquals(4, region.timestamp(1));
        }
    }

    @Test
    void randomizedUpdatesMatchTheReferenceAcrossReopens() throws Exception {
        Path file = path("random");
        Random random = new Random(1768);
        Map<Integer, byte[]> expected = new HashMap<>();
        for (int batch = 0; batch < 6; batch++) {
            try (CompactRegion region = CompactRegion.open(file, 0, 0, batch == 0)) {
                for (int change = 0; change < 20; change++) {
                    int slot = random.nextInt(10);
                    byte[] data = random.nextInt(4) == 0 ? null : bytes(1 + random.nextInt(4096), random.nextInt());
                    region.write(slot, data, 2, batch * 20 + change);
                    if (data == null) expected.remove(slot); else expected.put(slot, data);
                }
                if (batch % 2 == 1) region.compact();
                for (int slot = 0; slot < 10; slot++) {
                    CompactRegion.Record actual = region.read(slot);
                    assertArrayEquals(expected.get(slot), actual == null ? null : actual.payload());
                }
            }
        }
    }

    private Path preparedPath() throws IOException {
        return Files.createDirectories(directory.resolve(".chunksqueeze-migration")
                .resolve(UUID.randomUUID().toString()).resolve("prepared")).resolve("r.0.0.cqs");
    }

    @Test
    void preparedBatchPacksAllSlotsAndKeepsBothIndexesAndCommits() throws Exception {
        Path file = preparedPath();
        List<CompactRegion.BuildStage> stages = new ArrayList<>();
        long expectedSize = CompactRegion.DATA_START;
        try (var builder = CompactRegion.createPrepared(file, 0, 0)) {
            builder.setFaultInjector(stages::add);
            for (int slot = 0; slot < 1024; slot++) {
                byte[] payload = slot % 3 == 0 ? null : bytes(1 + slot, slot);
                builder.write(slot, payload, 1 + slot % 3, -slot);
                if (payload != null) expectedSize += ((payload.length + 511L) / 512) * 512;
            }
            assertThrows(IOException.class, () -> CompactRegion.open(file, 0, 0, false));
            assertArrayEquals(new byte[1024], read(file, 512, 1024));
            assertEquals(Collections.nCopies(1024, CompactRegion.BuildStage.PAYLOAD_WRITTEN), stages);
            builder.finish();
            assertEquals(List.of(CompactRegion.BuildStage.PAYLOADS_FORCED, CompactRegion.BuildStage.INDEXES_WRITTEN,
                    CompactRegion.BuildStage.COMPLETE_FORCED), stages.subList(1024, stages.size()));
            assertThrows(IOException.class, builder::finish);
        }
        assertEquals(expectedSize, Files.size(file));
        assertArrayEquals(read(file, 1536, CompactRegion.TABLE_BYTES),
                read(file, 1536L + CompactRegion.TABLE_BYTES, CompactRegion.TABLE_BYTES));
        assertArrayEquals(read(file, 512, 512), read(file, 1024, 512));
        try (CompactRegion region = CompactRegion.open(file, 0, 0, false)) {
            assertEquals(1, region.generation());
            for (int slot = 0; slot < 1024; slot++) {
                assertEquals(-slot, region.timestamp(slot));
                if (slot % 3 == 0) assertNull(region.read(slot));
                else {
                    assertArrayEquals(bytes(1 + slot, slot), region.read(slot).payload());
                    assertEquals(1 + slot % 3, region.read(slot).codec());
                }
            }
        }
    }

    @Test
    void everyFailedBatchBoundaryPoisonsBuilderAndKeepsOwnershipUntilClose() throws Exception {
        for (CompactRegion.BuildStage stop : CompactRegion.BuildStage.values()) {
            Path file = preparedPath();
            byte[] data = bytes(513, 71);
            try (var builder = CompactRegion.createPrepared(file, 0, 0)) {
                builder.setFaultInjector(stage -> { if (stage == stop) throw new IOException("interruption"); });
                if (stop == CompactRegion.BuildStage.PAYLOAD_WRITTEN) {
                    assertThrows(IOException.class, () -> builder.write(1023, data, 2, -7));
                } else {
                    builder.write(1023, data, 2, -7);
                    assertThrows(IOException.class, builder::finish);
                }
                assertThrows(IOException.class, () -> builder.write(0, data, 2, 0));
                assertThrows(IOException.class, builder::finish);
                assertThrows(IOException.class, () -> CompactRegion.open(file, 0, 0, false));
                assertThrows(IOException.class, () -> CompactRegion.createPrepared(file, 0, 0));
            }
            if (stop == CompactRegion.BuildStage.COMPLETE_FORCED) {
                try (CompactRegion region = CompactRegion.open(file, 0, 0, false)) {
                    assertArrayEquals(data, region.read(1023).payload());
                    assertEquals(-7, region.timestamp(1023));
                }
            } else assertThrows(IOException.class, () -> CompactRegion.open(file, 0, 0, false));
        }
    }

    @Test
    void abandonedBatchCannotBeOpenedAndOnlyFreshPreparedPathsAreAccepted() throws Exception {
        assertThrows(IOException.class, () -> CompactRegion.createPrepared(path("online"), 0, 0));
        Path file = preparedPath();
        try (var builder = CompactRegion.createPrepared(file, 0, 0)) { builder.write(0, bytes(1, 1), 2, 1); }
        assertThrows(IOException.class, () -> CompactRegion.open(file, 0, 0, false));
        assertThrows(IOException.class, () -> CompactRegion.createPrepared(file, 0, 0));
        Path empty = preparedPath();
        try (CompactRegion ignored = CompactRegion.open(empty, 0, 0, true)) {}
        byte[] before = Files.readAllBytes(empty);
        assertThrows(IOException.class, () -> CompactRegion.createPrepared(empty, 0, 0));
        assertArrayEquals(before, Files.readAllBytes(empty));
        for (boolean ownerOnly : new boolean[]{false, true}) {
            Path partial = preparedPath();
            Files.createFile(ownerOnly ? CompactRegion.ownerPath(partial) : partial);
            assertThrows(IOException.class, () -> CompactRegion.createPrepared(partial, 0, 0));
        }
    }

    @Test
    void invalidBatchWritesPoisonAndCompletedBuilderCannotWriteAgain() throws Exception {
        for (int invalid : new int[]{-1, 1024, 0, 1, 2}) {
            Path file = preparedPath();
            try (var builder = CompactRegion.createPrepared(file, 0, 0)) {
                if (invalid < 0 || invalid == 1024) {
                    assertThrows(IOException.class, () -> builder.write(invalid, bytes(1, 1), 2, 1));
                } else if (invalid == 0) {
                    builder.write(0, null, 0, 1);
                    assertThrows(IOException.class, () -> builder.write(0, bytes(1, 1), 2, 1));
                } else if (invalid == 1) {
                    assertThrows(IOException.class, () -> builder.write(0, new byte[0], 2, 1));
                } else assertThrows(IOException.class, () -> builder.write(0, bytes(1, 1), 4, 1));
                assertThrows(IOException.class, builder::finish);
            }
            assertThrows(IOException.class, () -> CompactRegion.open(file, 0, 0, false));
        }
        Path file = preparedPath();
        CompactRegion.PreparedBuilder retained;
        try (var builder = CompactRegion.createPrepared(file, 0, 0)) {
            retained = builder;
            builder.finish();
            assertThrows(IOException.class, () -> builder.write(0, bytes(1, 1), 2, 1));
        }
        assertThrows(IOException.class, retained::finish);
        try (CompactRegion region = CompactRegion.open(file, 0, 0, false)) { assertNull(region.read(0)); }
    }

    @Test
    void normalWritesAfterBatchStillUseAllFourDurabilityStagesAndCopyOnWrite() throws Exception {
        for (CompactRegion.Stage stop : CompactRegion.Stage.values()) {
            Path file = preparedPath();
            byte[] before = bytes(600, 31), after = bytes(900, 32), neighbor = bytes(513, 33);
            try (var builder = CompactRegion.createPrepared(file, 0, 0)) {
                builder.write(0, before, 2, 1);
                builder.write(1, neighbor, 2, 1);
                builder.finish();
            }
            List<CompactRegion.Stage> stages = new ArrayList<>();
            try (CompactRegion region = CompactRegion.open(file, 0, 0, false)) {
                region.setFaultInjector(stage -> {
                    stages.add(stage);
                    if (stage == stop) throw new IOException("interruption");
                });
                assertThrows(IOException.class, () -> region.write(0, after, 2, 2));
                assertEquals(Arrays.asList(CompactRegion.Stage.values()).subList(0, stop.ordinal() + 1), stages);
                assertArrayEquals(before, read(file, CompactRegion.DATA_START, before.length));
                assertThrows(IOException.class, () -> region.write(1, after, 2, 2));
            }
            try (CompactRegion region = CompactRegion.open(file, 0, 0, false)) {
                boolean published = stop.ordinal() >= CompactRegion.Stage.FIRST_COMMIT_FORCED.ordinal();
                assertArrayEquals(published ? after : before, region.read(0).payload());
                assertEquals(published ? 2 : 1, region.timestamp(0));
                assertArrayEquals(neighbor, region.read(1).payload());
            }
        }
    }

    private static byte[] read(Path file, long offset, int length) throws IOException {
        try (var input = new RandomAccessFile(file.toFile(), "r")) {
            input.seek(offset); byte[] bytes = new byte[length]; input.readFully(bytes); return bytes;
        }
    }
    private static void flip(Path file, long offset) throws IOException {
        try (var output = new RandomAccessFile(file.toFile(), "rw")) {
            output.seek(offset); int value = output.read(); output.seek(offset); output.write(value ^ 1);
        }
    }
}
