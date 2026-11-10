package cn.star.chunksqueeze.migration;

import cn.star.chunksqueeze.compat.NbtAccounter;

import cn.star.chunksqueeze.codec.ChunkCodec;
import cn.star.chunksqueeze.storage.CompactRegion;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;

import static cn.star.chunksqueeze.migration.MigrationTransaction.*;
import static org.junit.jupiter.api.Assertions.*;

class MigrationTransactionTest {
    @TempDir Path temporary;
    private static final ChunkCodec.Options OPTIONS = new ChunkCodec.Options(0, false);
    private static final int[] SLOTS = {0, 33, 1023};
    private static final class PowerLoss extends Error {}

    @Test
    void migratesAllCodecsNbtTimestampsAndDeletesOriginals() throws Exception {
        for (boolean encode : new boolean[]{false, true}) {
            Path folder = fixture("normal-" + encode, false, true);
            McaSnapshot snapshot = McaSnapshot.capture(folder, 0, 0);
            Prepared prepared = prepare(snapshot, OPTIONS, encode, () -> false);
            assertEquals(snapshot, prepared.source());
            assertEquals(prepared.transactionId().toString(), prepared.directory().getFileName().toString());
            assertEquals(PublishResult.COMMITTED, publish(prepared, () -> false));
            assertFalse(Files.exists(mca(folder)));
            assertFalse(Files.exists(pendingPath(folder, 0, 0)));
            assertFalse(Files.exists(prepared.directory()));
            try (CompactRegion region = CompactRegion.open(cqs(folder), 0, 0, false)) {
                assertEquals(prepared.regionId(), region.identity());
                for (McaSnapshot.Chunk chunk : snapshot.chunks()) {
                    CompactRegion.Record stored = region.read(chunk.slot());
                    assertEquals(tag(chunk.slot()), decode(stored.payload(), stored.codec()));
                    assertEquals(encode ? 2 : chunk.codec(), stored.codec());
                    if (!encode) assertArrayEquals(chunk.payload(), stored.payload());
                }
                for (int slot = 0; slot < 1024; slot++) assertEquals(snapshot.timestamps()[slot], region.timestamp(slot));
            }
            discard(prepared);
            assertFalse(Files.exists(prepared.directory()));
            assertEquals(Recovery.NONE, recover(folder, 0, 0));
        }
    }

    @Test
    void changedSourceIsNotOverwrittenIncludingEmptySlotTimestamps() throws Exception {
        Path folder = fixture("changed", false, true);
        Prepared prepared = prepare(McaSnapshot.capture(folder, 0, 0), OPTIONS, false, () -> false);
        try (var file = new RandomAccessFile(mca(folder).toFile(), "rw")) {
            file.seek(4096 + 77 * 4); file.writeInt(888);
        }
        byte[] changed = Files.readAllBytes(mca(folder));
        assertEquals(PublishResult.CHANGED, publish(prepared, () -> false));
        assertArrayEquals(changed, Files.readAllBytes(mca(folder)));
        assertFalse(Files.exists(cqs(folder)));
        assertFalse(Files.exists(pendingPath(folder, 0, 0)));
        discard(prepared);
        assertFalse(Files.exists(prepared.directory()));
    }

    @Test
    void cancellationNeverInterruptsChannelsOrMovesSource() throws Exception {
        Path folder = fixture("cancel", false, true);
        McaSnapshot snapshot = McaSnapshot.capture(folder, 0, 0);
        var calls = new AtomicInteger();
        assertThrows(Cancelled.class, () -> prepare(snapshot, OPTIONS, true, () -> calls.incrementAndGet() > 2));
        assertArrayEquals(snapshot.fingerprint(), McaSnapshot.capture(folder, 0, 0).fingerprint());
        assertFalse(Thread.currentThread().isInterrupted());
        Prepared prepared = prepare(snapshot, OPTIONS, false, () -> false);
        assertEquals(PublishResult.CANCELLED, publish(prepared, () -> true));
        assertTrue(Files.exists(mca(folder)));
        assertFalse(Files.exists(pendingPath(folder, 0, 0)));
        discard(prepared);
        try (var dirs = Files.list(folder.resolve(".chunksqueeze-migration"))) { assertEquals(0, dirs.count()); }
    }

    @Test
    void everyBatchFailureBoundaryCleansPreparedWithoutPublishingOrChangingSource() throws Exception {
        for (CompactRegion.BuildStage stop : CompactRegion.BuildStage.values()) {
            for (int kind = 0; kind < 3; kind++) {
                Path folder = fixture("prepare-failure-" + stop + "-" + kind, false, true);
                McaSnapshot snapshot = McaSnapshot.capture(folder, 0, 0);
                int failureKind = kind;
                Throwable failure = assertThrows(Throwable.class, () -> prepare(snapshot, OPTIONS, true, () -> false, stage -> {
                    if (stage != stop) return;
                    if (failureKind == 0) throw new IOException("batch IO failure");
                    if (failureKind == 1) throw new IllegalStateException("batch runtime failure");
                    throw new PowerLoss();
                }));
                assertEquals(kind == 0 ? IOException.class : kind == 1 ? IllegalStateException.class : PowerLoss.class,
                        failure.getClass());
                assertArrayEquals(snapshot.fingerprint(), McaSnapshot.capture(folder, 0, 0).fingerprint());
                assertFalse(Files.exists(cqs(folder)));
                assertFalse(Files.exists(CompactRegion.ownerPath(cqs(folder))));
                assertFalse(Files.exists(pendingPath(folder, 0, 0)));
                try (var dirs = Files.list(folder.resolve(".chunksqueeze-migration"))) { assertEquals(0, dirs.count()); }
                assertFalse(Thread.currentThread().isInterrupted());
                Prepared retry = prepare(snapshot, OPTIONS, true, () -> false);
                discard(retry);
            }
        }
    }

    @Test
    void cancellationAfterBatchForceStillCleansPreparedAndNeverInterruptsChannel() throws Exception {
        Path folder = fixture("cancel-after-force", false, true);
        McaSnapshot snapshot = McaSnapshot.capture(folder, 0, 0);
        AtomicInteger completed = new AtomicInteger();
        assertThrows(Cancelled.class, () -> prepare(snapshot, OPTIONS, false, () -> completed.get() != 0, stage -> {
            if (stage == CompactRegion.BuildStage.COMPLETE_FORCED) completed.incrementAndGet();
        }));
        assertEquals(1, completed.get());
        assertFalse(Thread.currentThread().isInterrupted());
        assertArrayEquals(snapshot.fingerprint(), McaSnapshot.capture(folder, 0, 0).fingerprint());
        assertFalse(Files.exists(cqs(folder)));
        assertFalse(Files.exists(pendingPath(folder, 0, 0)));
        try (var dirs = Files.list(folder.resolve(".chunksqueeze-migration"))) { assertEquals(0, dirs.count()); }
    }

    @Test
    void persistedBatchMustPassFullNbtAndEmptyTimestampValidationBeforePreparing() throws Exception {
        for (boolean damageNbt : new boolean[]{false, true}) {
            Path folder = fixture("validate-batch-" + damageNbt, false, true);
            McaSnapshot snapshot = McaSnapshot.capture(folder, 0, 0);
            AtomicInteger completed = new AtomicInteger();
            assertThrows(IOException.class, () -> prepare(snapshot, OPTIONS, true, () -> {
                if (completed.compareAndSet(1, 2)) {
                    Path staged;
                    try (var dirs = Files.list(folder.resolve(".chunksqueeze-migration"))) {
                        staged = cqs(dirs.findFirst().orElseThrow().resolve("prepared"));
                    } catch (IOException failure) { throw new UncheckedIOException(failure); }
                    try (CompactRegion region = CompactRegion.open(staged, 0, 0, false)) {
                        if (damageNbt) {
                            CompoundTag altered = tag(0);
                            altered.putString("custom-data", "changed outside block states");
                            region.write(0, compressed(altered, 2), 2, snapshot.timestamps()[0]);
                        } else region.write(77, null, 0, 999);
                    } catch (IOException failure) { throw new UncheckedIOException(failure); }
                }
                return false;
            }, stage -> {
                if (stage == CompactRegion.BuildStage.COMPLETE_FORCED) completed.set(1);
            }));
            assertEquals(2, completed.get());
            assertArrayEquals(snapshot.fingerprint(), McaSnapshot.capture(folder, 0, 0).fingerprint());
            assertFalse(Files.exists(cqs(folder)));
            assertFalse(Files.exists(pendingPath(folder, 0, 0)));
            try (var dirs = Files.list(folder.resolve(".chunksqueeze-migration"))) { assertEquals(0, dirs.count()); }
        }
    }

    @Test
    void emptyVanillaFileIsSkippedButPartialHeaderIsRejected() throws Exception {
        Path folder = Files.createDirectory(temporary.resolve("empty"));
        Files.createFile(mca(folder));
        McaSnapshot snapshot = McaSnapshot.capture(folder, 0, 0);
        assertTrue(snapshot.chunks().isEmpty());
        assertEquals(0, snapshot.sourceBytes());
        Prepared prepared = prepare(snapshot, OPTIONS, true, () -> false);
        assertEquals(PublishResult.NO_GAIN, publish(prepared, () -> false));
        discard(prepared);
        assertEquals(0, Files.size(mca(folder)));
        Files.write(mca(folder), new byte[4096]);
        assertThrows(IOException.class, () -> McaSnapshot.capture(folder, 0, 0));
    }

    @Test
    void noGainLeavesMcaUntouched() throws Exception {
        Path folder = fixture("no-gain", false, false);
        McaSnapshot snapshot = McaSnapshot.capture(folder, 0, 0);
        Prepared prepared = prepare(snapshot, OPTIONS, false, () -> false);
        assertTrue(prepared.compactBytes() >= snapshot.sourceBytes());
        assertEquals(PublishResult.NO_GAIN, publish(prepared, () -> false));
        assertArrayEquals(snapshot.fingerprint(), McaSnapshot.capture(folder, 0, 0).fingerprint());
        assertFalse(Files.exists(pendingPath(folder, 0, 0)));
        discard(prepared);
    }

    @Test
    void migratesExternalMccAndAccountsForActualBytes() throws Exception {
        Path folder = fixture("external", true, true);
        McaSnapshot snapshot = McaSnapshot.capture(folder, 0, 0);
        long bytes = Files.size(mca(folder));
        Map<String, byte[]> originals = new HashMap<>();
        for (String name : snapshot.externalNames()) {
            originals.put(name, Files.readAllBytes(folder.resolve(name)));
            bytes += Files.size(folder.resolve(name));
        }
        assertEquals(3, snapshot.externalNames().size());
        assertEquals(bytes, snapshot.sourceBytes());
        Prepared prepared = prepare(snapshot, OPTIONS, true, () -> false);
        assertEquals(PublishResult.COMMITTED, publish(prepared, () -> false));
        for (var entry : originals.entrySet()) {
            assertFalse(Files.exists(folder.resolve(entry.getKey())));
            assertFalse(Files.exists(prepared.directory().resolve("original").resolve(entry.getKey())));
        }
        assertFalse(Files.exists(prepared.directory()));
    }

    @Test
    void interruptedJournalCreationNeverPublishesAPartialMarker() throws Exception {
        for (Stage stage : new Stage[]{Stage.JOURNAL_CREATED, Stage.JOURNAL_FORCED, Stage.JOURNAL_PUBLISHED}) {
            Path folder = fixture("journal-" + stage, false, true);
            byte[] original = Files.readAllBytes(mca(folder));
            Prepared prepared = prepare(McaSnapshot.capture(folder, 0, 0), OPTIONS, false, () -> false);
            crash(prepared, stage);
            assertArrayEquals(original, Files.readAllBytes(mca(folder)));
            boolean published = stage == Stage.JOURNAL_PUBLISHED;
            assertEquals(published, Files.exists(pendingPath(folder, 0, 0)));
            assertEquals(published ? Recovery.ROLLED_BACK : Recovery.NONE, recover(folder, 0, 0));
            assertArrayEquals(original, Files.readAllBytes(mca(folder)));
            discard(prepared);
            assertFalse(Files.exists(prepared.directory()));
            Prepared retry = prepare(McaSnapshot.capture(folder, 0, 0), OPTIONS, false, () -> false);
            assertEquals(PublishResult.COMMITTED, publish(retry, () -> false));
        }
    }

    @Test
    void halfPublicationsRollbackFromDiskAndCanBePublishedAgain() throws Exception {
        for (Stage stage : new Stage[]{Stage.MCA_MOVED, Stage.OWNER_MOVED}) {
            Path folder = fixture("rollback-" + stage, true, true);
            McaSnapshot snapshot = McaSnapshot.capture(folder, 0, 0);
            Prepared prepared = prepare(snapshot, OPTIONS, false, () -> false);
            crash(prepared, stage);
            assertFalse(Files.exists(mca(folder)));
            assertTrue(Files.exists(pendingPath(folder, 0, 0)));
            assertFalse(Files.exists(cqs(folder)));
            assertEquals(stage == Stage.OWNER_MOVED, Files.exists(CompactRegion.ownerPath(cqs(folder))));
            assertThrows(IOException.class, () -> discard(prepared));
            assertEquals(Recovery.ROLLED_BACK, recover(folder, 0, 0));
            assertArrayEquals(snapshot.fingerprint(), McaSnapshot.capture(folder, 0, 0).fingerprint());
            assertFalse(Files.exists(CompactRegion.ownerPath(cqs(folder))));
            assertEquals(PublishResult.COMMITTED, publish(prepared, () -> false));
        }
    }

    @Test
    void recoversMixedExternalLocations() throws Exception {
        Path folder = fixture("mixed", true, true);
        Prepared prepared = prepare(McaSnapshot.capture(folder, 0, 0), OPTIONS, false, () -> false);
        crash(prepared, Stage.MCA_MOVED);
        String name = prepared.source().externalNames().get(0);
        Files.move(folder.resolve(name), prepared.directory().resolve("original").resolve(name), StandardCopyOption.ATOMIC_MOVE);
        assertEquals(Recovery.ROLLED_BACK, recover(folder, 0, 0));
        assertArrayEquals(prepared.source().fingerprint(), McaSnapshot.capture(folder, 0, 0).fingerprint());
    }

    @Test
    void publishedCqsRollsForwardAndReappearedJournalPreservesNewWrites() throws Exception {
        Path folder = fixture("forward", true, true);
        Prepared prepared = prepare(McaSnapshot.capture(folder, 0, 0), OPTIONS, false, () -> false);
        crash(prepared, Stage.CQS_MOVED);
        byte[] journal = Files.readAllBytes(pendingPath(folder, 0, 0));
        assertEquals(Recovery.COMMITTED, recover(folder, 0, 0));
        CompoundTag newer = tag(0); newer.putString("new-write", "must survive");
        try (CompactRegion region = CompactRegion.open(cqs(folder), 0, 0, false)) {
            region.write(0, compressed(newer, 2), 2, 5678);
        }
        Files.write(pendingPath(folder, 0, 0), journal);
        assertEquals(Recovery.COMMITTED, recover(folder, 0, 0));
        try (CompactRegion region = CompactRegion.open(cqs(folder), 0, 0, false)) {
            assertEquals(newer, decode(region.read(0).payload(), region.read(0).codec()));
            assertEquals(5678, region.timestamp(0));
        }
        assertFalse(Files.exists(mca(folder)));
        assertFalse(Files.exists(prepared.directory()));
    }

    @Test
    void interruptedOriginalDeletionResumesAndPreservesNewWrites() throws Exception {
        for (int stopAfter = 1; stopAfter <= 5; stopAfter++) {
            Path folder = fixture("cleanup-" + stopAfter, true, true);
            Prepared prepared = prepare(McaSnapshot.capture(folder, 0, 0), OPTIONS, false, () -> false);
            int stop = stopAfter;
            AtomicInteger deleted = new AtomicInteger();
            assertThrows(PowerLoss.class, () -> publish(prepared, () -> false, stage -> {
                if ((stage == Stage.ORIGINAL_DELETED && deleted.incrementAndGet() == stop)
                        || (stage == Stage.ORIGINALS_REMOVED && stop == 5)) throw new PowerLoss();
            }));
            assertTrue(Files.exists(pendingPath(folder, 0, 0)));
            CompoundTag newer = tag(0); newer.putInt("after-publication", 1);
            try (CompactRegion region = CompactRegion.open(cqs(folder), 0, 0, false)) {
                region.write(0, compressed(newer, 2), 2, 777);
            }
            assertEquals(Recovery.COMMITTED, recover(folder, 0, 0));
            assertFalse(Files.exists(prepared.directory()));
            assertFalse(Files.exists(pendingPath(folder, 0, 0)));
            try (CompactRegion region = CompactRegion.open(cqs(folder), 0, 0, false)) {
                assertEquals(newer, decode(region.read(0).payload(), region.read(0).codec()));
                assertEquals(777, region.timestamp(0));
                assertEquals(tag(33), decode(region.read(33).payload(), region.read(33).codec()));
            }
        }
    }

    @Test
    void damagedCqsAfterPartialCleanupRetainsRemainingOriginalsAndJournal() throws Exception {
        Path folder = fixture("cleanup-corrupt", true, true);
        Prepared prepared = prepare(McaSnapshot.capture(folder, 0, 0), OPTIONS, false, () -> false);
        crash(prepared, Stage.ORIGINAL_DELETED);
        Path original = prepared.directory().resolve("original");
        List<Path> remaining;
        try (var files = Files.list(original)) { remaining = files.sorted().toList(); }
        assertEquals(3, remaining.size());
        flip(cqs(folder), CompactRegion.DATA_START);
        assertThrows(IOException.class, () -> recover(folder, 0, 0));
        assertTrue(Files.exists(pendingPath(folder, 0, 0)));
        for (Path file : remaining) assertTrue(Files.exists(file));
    }

    @Test
    void cleanupNeverDeletesUnknownFilesOrOtherTransactions() throws Exception {
        Path folder = fixture("cleanup-unknown", true, true);
        Prepared prepared = prepare(McaSnapshot.capture(folder, 0, 0), OPTIONS, false, () -> false);
        crash(prepared, Stage.CQS_MOVED);
        Path unknown = prepared.directory().resolve("original/keep.txt");
        Files.writeString(unknown, "keep");
        assertThrows(IOException.class, () -> recover(folder, 0, 0));
        assertEquals("keep", Files.readString(unknown));
        assertTrue(Files.exists(mca(prepared.directory().resolve("original"))));
        Path other = Files.createDirectory(prepared.directory().getParent().resolve(UUID.randomUUID().toString()));
        Files.writeString(other.resolve("keep.txt"), "other");
        Files.delete(unknown);
        assertEquals(Recovery.COMMITTED, recover(folder, 0, 0));
        assertEquals("other", Files.readString(other.resolve("keep.txt")));
    }

    @Test
    void corruptFinalCqsOrMissingOwnerNeverRollsBack() throws Exception {
        for (String damage : List.of("payload", "index", "owner")) {
            Path folder = fixture("bad-final-" + damage, false, true);
            Prepared prepared = prepare(McaSnapshot.capture(folder, 0, 0), OPTIONS, false, () -> false);
            crash(prepared, Stage.CQS_MOVED);
            if (damage.equals("owner")) Files.delete(CompactRegion.ownerPath(cqs(folder)));
            else if (damage.equals("payload")) flip(cqs(folder), CompactRegion.DATA_START);
            else {
                flip(cqs(folder), 3L * 512);
                flip(cqs(folder), 3L * 512 + CompactRegion.TABLE_BYTES);
            }
            assertThrows(IOException.class, () -> recover(folder, 0, 0));
            assertFalse(Files.exists(mca(folder)));
            assertTrue(Files.exists(cqs(folder)));
            assertTrue(Files.exists(pendingPath(folder, 0, 0)));
            assertArrayEquals(prepared.source().fingerprint(), McaSnapshot.capture(prepared.directory().resolve("original"), 0, 0).fingerprint());
        }
    }

    @Test
    void badJournalIsRejectedWithoutMovingAnything() throws Exception {
        for (boolean truncate : new boolean[]{false, true}) {
            Path folder = fixture("bad-journal-" + truncate, false, true);
            Prepared prepared = prepare(McaSnapshot.capture(folder, 0, 0), OPTIONS, false, () -> false);
            crash(prepared, Stage.MCA_MOVED);
            Path pending = pendingPath(folder, 0, 0);
            if (truncate) Files.write(pending, new byte[3]);
            else flip(pending, 20);
            assertThrows(IOException.class, () -> recover(folder, 0, 0));
            assertFalse(Files.exists(mca(folder)));
            assertTrue(Files.exists(pending));
            assertArrayEquals(prepared.source().fingerprint(), McaSnapshot.capture(prepared.directory().resolve("original"), 0, 0).fingerprint());
        }
    }

    @Test
    void conflictsAndDamagedBackupAreNeverOverwritten() throws Exception {
        for (String damage : List.of("mca", "mcc", "backup", "owner")) {
            Path folder = fixture("conflict-" + damage, true, true);
            Prepared prepared = prepare(McaSnapshot.capture(folder, 0, 0), OPTIONS, false, () -> false);
            crash(prepared, Stage.OWNER_MOVED);
            Path original = prepared.directory().resolve("original");
            if (damage.equals("mca")) Files.copy(mca(original), mca(folder));
            if (damage.equals("mcc")) {
                String name = prepared.source().externalNames().get(0);
                Files.copy(original.resolve(name), folder.resolve(name));
            }
            if (damage.equals("backup")) flip(mca(original), 4096);
            if (damage.equals("owner")) flip(CompactRegion.ownerPath(cqs(folder)), 24);
            assertThrows(IOException.class, () -> recover(folder, 0, 0));
            assertTrue(Files.exists(pendingPath(folder, 0, 0)));
            assertTrue(Files.exists(mca(original)));
        }
    }

    @Test
    void ordinaryPublishFailureImmediatelyRecovers() throws Exception {
        Path folder = fixture("exception", true, true);
        Prepared prepared = prepare(McaSnapshot.capture(folder, 0, 0), OPTIONS, false, () -> false);
        assertThrows(IOException.class, () -> publish(prepared, () -> false, stage -> {
            if (stage == Stage.OWNER_MOVED) throw new IOException("simulated IO failure");
        }));
        assertFalse(Files.exists(pendingPath(folder, 0, 0)));
        assertArrayEquals(prepared.source().fingerprint(), McaSnapshot.capture(folder, 0, 0).fingerprint());
    }

    @Test
    void failedRecoveryStaysPendingAndIsSuppressedOnPublishFailure() throws Exception {
        Path folder = fixture("failed-recovery", false, true);
        Prepared prepared = prepare(McaSnapshot.capture(folder, 0, 0), OPTIONS, false, () -> false);
        IOException failure = assertThrows(IOException.class, () -> publish(prepared, () -> false, stage -> {
            if (stage == Stage.OWNER_MOVED) {
                flip(mca(prepared.directory().resolve("original")), 4096);
                throw new IOException("publish failure");
            }
        }));
        assertEquals(1, failure.getSuppressed().length);
        assertTrue(Files.exists(pendingPath(folder, 0, 0)));
        assertFalse(Files.exists(mca(folder)));
        assertTrue(Files.exists(mca(prepared.directory().resolve("original"))));
    }

    @Test
    void malformedNbtAndWrongChunkCoordinatesCannotBePrepared() throws Exception {
        for (boolean wrongCoordinates : new boolean[]{false, true}) {
            Path folder = fixture("invalid-nbt-" + wrongCoordinates, false, true);
            byte[] payload;
            if (wrongCoordinates) {
                CompoundTag wrong = tag(0); wrong.putInt("xPos", 99);
                payload = compressed(wrong, 3);
            } else payload = new byte[]{99};
            try (var file = new RandomAccessFile(mca(folder).toFile(), "rw")) {
                file.seek(8192); file.writeInt(payload.length + 1); file.writeByte(3); file.write(payload);
            }
            McaSnapshot snapshot = McaSnapshot.capture(folder, 0, 0);
            assertThrows(IOException.class, () -> prepare(snapshot, OPTIONS, true, () -> false));
            assertArrayEquals(snapshot.fingerprint(), McaSnapshot.capture(folder, 0, 0).fingerprint());
            assertFalse(Files.exists(pendingPath(folder, 0, 0)));
        }
    }

    @Test
    void snapshotRejectsBadBoundariesOverlapAndCodecButIgnoresFreePadding() throws Exception {
        Path folder = fixture("snapshot", false, true);
        McaSnapshot before = McaSnapshot.capture(folder, 0, 0);
        flip(mca(folder), Files.size(mca(folder)) - 1);
        assertArrayEquals(before.fingerprint(), McaSnapshot.capture(folder, 0, 0).fingerprint());
        Path noPadding = fixture("missing-tail-padding", false, false);
        byte[] intact = McaSnapshot.capture(noPadding, 0, 0).fingerprint();
        try (var file = new RandomAccessFile(mca(noPadding).toFile(), "rw")) {
            file.seek(4L * 4096);
            file.setLength(4L * 4096 + 4 + file.readInt());
        }
        assertArrayEquals(intact, McaSnapshot.capture(noPadding, 0, 0).fingerprint());
        for (String damage : List.of("boundary", "overlap", "codec", "length")) {
            Path broken = fixture(damage, false, true);
            try (var file = new RandomAccessFile(mca(broken).toFile(), "rw")) {
                switch (damage) {
                    case "boundary" -> file.setLength(8192 + 4);
                    case "overlap" -> { file.seek(33 * 4); file.writeInt((2 << 8) | 1); }
                    case "codec" -> { file.seek(8192 + 4); file.writeByte(4); }
                    case "length" -> { file.seek(8192); file.writeInt(4096); }
                }
            }
            assertThrows(IOException.class, () -> McaSnapshot.capture(broken, 0, 0));
        }
    }

    private static void crash(Prepared prepared, Stage stop) {
        assertThrows(PowerLoss.class, () -> publish(prepared, () -> false, stage -> {
            if (stage == stop) throw new PowerLoss();
        }));
    }
    private Path fixture(String name, boolean external, boolean padding) throws Exception {
        Path folder = Files.createDirectory(temporary.resolve(name));
        try (var file = new RandomAccessFile(mca(folder).toFile(), "rw")) {
            file.setLength((padding ? 64L : 5L) * 4096);
            for (int i = 0; i < SLOTS.length; i++) {
                int slot = SLOTS[i], codec = i + 1;
                byte[] payload = compressed(tag(slot), codec);
                assertTrue(payload.length < 4091);
                file.seek(slot * 4L); file.writeInt(((i + 2) << 8) | 1);
                file.seek(4096 + slot * 4L); file.writeInt(100 + i);
                file.seek((i + 2) * 4096L);
                file.writeInt(external ? 1 : payload.length + 1);
                file.writeByte(codec | (external ? 128 : 0));
                if (external) Files.write(folder.resolve("c." + (slot & 31) + "." + (slot >>> 5) + ".mcc"), payload);
                else file.write(payload);
            }
            file.seek(4096 + 77 * 4); file.writeInt(-123);
        }
        return folder;
    }
    private static CompoundTag tag(int slot) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("xPos", slot & 31); tag.putInt("zPos", slot >>> 5);
        tag.putString("Status", "minecraft:full");
        tag.putString("custom-data", "preserved including fields unrelated to block states");
        ListTag sections = new ListTag();
        for (int y = 0; y < 4; y++) {
            CompoundTag section = new CompoundTag(); section.putByte("Y", (byte) y);
            CompoundTag states = new CompoundTag();
            ListTag palette = new ListTag();
            CompoundTag state = new CompoundTag(); state.putString("Name", "minecraft:stone");
            palette.add(state); states.put("palette", palette); section.put("block_states", states);
            sections.add(section);
        }
        tag.put("sections", sections);
        return tag;
    }
    private static byte[] compressed(CompoundTag tag, int codec) throws IOException {
        var bytes = new ByteArrayOutputStream();
        OutputStream stream = switch (codec) {
            case 1 -> new GZIPOutputStream(bytes);
            case 2 -> new DeflaterOutputStream(bytes);
            default -> bytes;
        };
        try (var output = new DataOutputStream(stream)) { NbtIo.write(tag, output); }
        return bytes.toByteArray();
    }
    private static CompoundTag decode(byte[] bytes, int codec) throws IOException {
        InputStream raw = new ByteArrayInputStream(bytes);
        InputStream stream = switch (codec) {
            case 1 -> new GZIPInputStream(raw);
            case 2 -> new InflaterInputStream(raw);
            default -> raw;
        };
        try (var input = new DataInputStream(stream)) {
            return ChunkCodec.decode(NbtIo.read(input, NbtAccounter.create(64L * 1024 * 1024)));
        }
    }
    private static void flip(Path path, long offset) throws IOException {
        try (var file = new RandomAccessFile(path.toFile(), "rw")) {
            file.seek(offset); int value = file.readUnsignedByte();
            file.seek(offset); file.writeByte(value ^ 1);
        }
    }
    private static Path mca(Path folder) { return folder.resolve("r.0.0.mca"); }
    private static Path cqs(Path folder) { return folder.resolve("r.0.0.cqs"); }
}
