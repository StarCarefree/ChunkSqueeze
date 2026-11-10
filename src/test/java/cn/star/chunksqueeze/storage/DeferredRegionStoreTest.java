package cn.star.chunksqueeze.storage;

import cn.star.chunksqueeze.codec.ChunkCodec;
import cn.star.chunksqueeze.codec.IndexCodec;
import cn.star.chunksqueeze.codec.RegionStateDictionary;
import cn.star.chunksqueeze.codec.SectionCodec;
import net.minecraft.nbt.*;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class DeferredRegionStoreTest {
    @TempDir Path folder;
    private static final ChunkPos ZERO = new ChunkPos(0, 0);
    private static final ChunkCodec.Options OPTIONS = new ChunkCodec.Options(0, false);

    private static BackgroundReencoder manual() {
        return new BackgroundReencoder(1, 128, 60_000, failure -> { throw new AssertionError(failure); });
    }

    private static CompoundTag fixture(int x) {
        CompoundTag root = new CompoundTag();
        root.putString("Status", "minecraft:full");
        root.putInt("DataVersion", 3955);
        root.putInt("xPos", x);
        root.putInt("zPos", 0);
        ListTag sections = new ListTag();
        for (int y = -4; y < 20; y++) {
            CompoundTag section = new CompoundTag(), states = new CompoundTag();
            section.putByte("Y", (byte) y);
            section.putByteArray("SkyLight", new byte[2048]);
            ListTag palette = new ListTag();
            for (int i = 0; i < 20; i++) {
                CompoundTag state = new CompoundTag(), properties = new CompoundTag();
                state.putString("Name", "test:long_background_block_identifier_" + i);
                properties.putString("mod:property", "完整属性 value " + i);
                state.put("Properties", properties);
                palette.add(state);
            }
            int[] values = new int[4096];
            for (int i = 0; i < values.length; i++) values[i] = (i / 64 + y + 4) % 20;
            states.put("palette", palette);
            states.putLongArray("data", IndexCodec.packVanilla(values, 20));
            section.put("block_states", states);
            sections.add(section);
        }
        root.put("sections", sections);
        return root;
    }

    @SuppressWarnings("unchecked")
    private static CompactRegion region(CompactRegionStore store) throws Exception {
        var field = CompactRegionStore.class.getDeclaredField("regions");
        field.setAccessible(true);
        return ((Map<Long, CompactRegion>) field.get(store)).get(0L);
    }

    private static CompoundTag decoded(CompactRegionStore store, ChunkPos pos) throws IOException {
        CompoundTag result = store.read(pos);
        return result == null ? null : SectionCodec.decode(result, null);
    }

    private static CompactRegionStore.ReencodePrepared prepare(CompactRegionStore store) throws Exception {
        var context = new CompactRegionStore.ReencodeContext();
        var snapshot = store.snapshotForReencode(0, region(store), 0, context);
        assertNotNull(snapshot);
        var prepared = store.prepareReencode(snapshot, context, OPTIONS);
        assertNotNull(prepared, "Fixture must produce a smaller encoded candidate");
        return prepared;
    }

    @Test
    void rawCommitSurvivesCancellationAndLaterOpenResumesOptimization() throws Exception {
        CompoundTag original = fixture(0);
        try (BackgroundReencoder worker = manual(); CompactRegionStore store = new CompactRegionStore(folder, worker)) {
            store.writeDeferred(ZERO, original, OPTIONS);
            store.flush();
            assertEquals(2, region(store).read(0).codec());
            assertNull(region(store).dictionary());
            assertEquals(original, decoded(store, ZERO));
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (BackgroundReencoder worker = new BackgroundReencoder(1, 128, 0, failure::set);
             CompactRegionStore reopened = new CompactRegionStore(folder, worker)) {
            assertEquals(original, decoded(reopened, ZERO));
            assertTrue(worker.awaitIdle(10000));
            assertNull(failure.get());
            assertEquals(original, decoded(reopened, ZERO));
            assertTrue(region(reopened).read(0).codec() == 4 || region(reopened).read(0).codec() == 5);
        }
    }

    @Test
    void staleBackgroundResultCannotOverwriteNewerSaveOrDeletion() throws Exception {
        for (boolean deleted : new boolean[]{false, true}) {
            Path path = folder.resolve(Boolean.toString(deleted));
            try (BackgroundReencoder worker = manual(); CompactRegionStore store = new CompactRegionStore(path, worker)) {
                store.writeDeferred(ZERO, fixture(0), OPTIONS);
                var prepared = prepare(store);
                CompoundTag latest = fixture(0);
                latest.putLong("mod:revision", 92);
                if (deleted) store.write(ZERO, null);
                else store.writeDeferred(ZERO, latest, OPTIONS);
                assertFalse(store.publishReencode(0, prepared, OPTIONS, () -> false));
                assertNull(region(store).dictionary());
                assertEquals(deleted ? null : latest, decoded(store, ZERO));
                store.flush();
            }
        }
    }

    @Test
    void unrelatedRawSaveDoesNotInvalidateSnapshotAndTimestampIsPreserved() throws Exception {
        try (BackgroundReencoder worker = manual(); CompactRegionStore store = new CompactRegionStore(folder, worker)) {
            CompoundTag original = fixture(0), other = fixture(1);
            store.writeDeferred(ZERO, original, OPTIONS);
            int timestamp = region(store).timestamp(0);
            var prepared = prepare(store);
            store.writeDeferred(new ChunkPos(1, 0), other, OPTIONS);
            assertTrue(store.publishReencode(0, prepared, OPTIONS, () -> false));
            assertEquals(timestamp, region(store).timestamp(0));
            assertEquals(original, decoded(store, ZERO));
            assertEquals(other, decoded(store, new ChunkPos(1, 0)));
        }
    }

    @Test
    void dictionaryChangeRejectsOldPlanAndAllowsFreshSnapshot() throws Exception {
        try (BackgroundReencoder worker = manual(); CompactRegionStore store = new CompactRegionStore(folder, worker)) {
            CompoundTag original = fixture(0), other = fixture(1);
            store.writeDeferred(ZERO, original, OPTIONS);
            var prepared = prepare(store);
            store.writeEncoded(new ChunkPos(1, 0), other, OPTIONS);
            assertNotNull(region(store).dictionary());
            assertFalse(store.publishReencode(0, prepared, OPTIONS, () -> false));
            var fresh = prepare(store);
            assertTrue(store.publishReencode(0, fresh, OPTIONS, () -> false));
            assertEquals(original, decoded(store, ZERO));
            assertEquals(other, decoded(store, new ChunkPos(1, 0)));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void unchangedDictionaryPublicationKeepsTheParsedReadCache() throws Exception {
        try (BackgroundReencoder worker = manual(); CompactRegionStore store = new CompactRegionStore(folder, worker)) {
            store.writeDeferred(ZERO, fixture(0), OPTIONS);
            assertTrue(store.publishReencode(0, prepare(store), OPTIONS, () -> false));
            assertEquals(fixture(0), decoded(store, ZERO));
            var field = CompactRegionStore.class.getDeclaredField("dictionaryCache");
            field.setAccessible(true);
            var cache = (DictionaryCache<CompactRegion>) field.get(store);
            var cached = cache.get(region(store));
            assertNotNull(cached);
            store.writeDeferred(new ChunkPos(1, 0), fixture(1), OPTIONS);
            var context = new CompactRegionStore.ReencodeContext();
            var snapshot = store.snapshotForReencode(0, region(store), 1, context);
            var prepared = store.prepareReencode(snapshot, context, OPTIONS);
            assertNotNull(prepared);
            assertNull(prepared.changedDictionary());
            assertTrue(store.publishReencode(0, prepared, OPTIONS, () -> false));
            assertSame(cached, cache.get(region(store)));
            assertEquals(fixture(1), decoded(store, new ChunkPos(1, 0)));
        }
    }

    @Test
    void closedAndReopenedHandleCannotAcceptOldWork() throws Exception {
        try (BackgroundReencoder worker = manual(); CompactRegionStore store = new CompactRegionStore(folder, worker)) {
            CompoundTag original = fixture(0);
            store.writeDeferred(ZERO, original, OPTIONS);
            var prepared = prepare(store);
            store.closeRegion(0, 0);
            assertEquals(original, decoded(store, ZERO));
            assertFalse(store.publishReencode(0, prepared, OPTIONS, () -> false));
            assertEquals(2, region(store).read(0).codec());
            store.close();
            assertFalse(store.publishReencode(0, prepared, OPTIONS, () -> false));
            assertThrows(IOException.class, () -> store.writeDeferred(ZERO, original, OPTIONS));
        }
    }

    @Test
    void foregroundWriteAndFlushCompleteWhileBackgroundEncodingIsPaused() throws Exception {
        CountDownLatch snapshotTaken = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (BackgroundReencoder worker = new BackgroundReencoder(1, 128, 0, failure::set,
                (store, key, region, slots, options, cancelled) -> {
                    if (!first.compareAndSet(true, false)) {
                        store.reencodeBatch(key, region, slots, options, cancelled);
                        return;
                    }
                    var context = new CompactRegionStore.ReencodeContext();
                    var snapshot = store.snapshotForReencode(key, region, slots.nextSetBit(0), context);
                    snapshotTaken.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    var prepared = store.prepareReencode(snapshot, context, options);
                    assertFalse(store.publishReencode(key, prepared, options, cancelled));
                });
             CompactRegionStore store = new CompactRegionStore(folder, worker);
             ExecutorService foreground = Executors.newSingleThreadExecutor()) {
            try {
                store.writeDeferred(ZERO, fixture(0), OPTIONS);
                assertTrue(snapshotTaken.await(5, TimeUnit.SECONDS));
                CompoundTag latest = fixture(0);
                latest.putInt("mod:revision", 123);
                foreground.submit(() -> {
                    store.writeDeferred(ZERO, latest, OPTIONS);
                    store.flush();
                    return null;
                }).get(3, TimeUnit.SECONDS);
                assertEquals(latest, decoded(store, ZERO));
            } finally { release.countDown(); }
            assertTrue(worker.awaitIdle(10000));
            assertNull(failure.get());
            assertEquals(123, decoded(store, ZERO).getInt("mod:revision"));
        }
    }

    @Test
    void workerBatchLearnsRepeatedWholeSectionsAndKeepsIndependentChunks() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (BackgroundReencoder worker = new BackgroundReencoder(1, 128, 100, failure::set);
             CompactRegionStore store = new CompactRegionStore(folder, worker)) {
            for (int i = 0; i < 8; i++) store.writeDeferred(new ChunkPos(i, 0), fixture(i), OPTIONS);
            assertTrue(worker.awaitIdle(15000));
            assertNull(failure.get());
            RegionStateDictionary dictionary = RegionStateDictionary.fromCompressed(region(store).dictionary());
            assertTrue(dictionary.sectionCount() > 0);
            for (int i = 0; i < 8; i++) assertEquals(fixture(i), decoded(store, new ChunkPos(i, 0)));
        }
    }

    @Test
    void arrivalOrderPreservesDuplicatePairsAcrossDifferentSlotRows() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (BackgroundReencoder worker = new BackgroundReencoder(1, 128, 500, failure::set);
             CompactRegionStore store = new CompactRegionStore(folder, worker)) {
            for (int x = 0; x < 16; x++) {
                CompoundTag original = fixture(x);
                for (Tag section : original.getList("sections", Tag.TAG_COMPOUND)) {
                    for (Tag state : ((CompoundTag) section).getCompound("block_states").getList("palette", Tag.TAG_COMPOUND)) {
                        CompoundTag entry = (CompoundTag) state;
                        entry.putString("Name", entry.getString("Name") + "_pair_" + x);
                    }
                }
                for (int z = 0; z < 2; z++) {
                    original.putInt("zPos", z);
                    store.writeDeferred(new ChunkPos(x, z), original, OPTIONS);
                }
            }
            assertTrue(worker.awaitIdle(15000));
            assertNull(failure.get());
            assertTrue(RegionStateDictionary.fromCompressed(region(store).dictionary()).sectionCount() > 0);
        }
    }

    @Test
    void backgroundBudgetLeavesLargeStandardNbtReadable() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (BackgroundReencoder worker = new BackgroundReencoder(1, 128, 0, failure::set);
             CompactRegionStore store = new CompactRegionStore(folder, worker)) {
            CompoundTag original = new CompoundTag();
            original.putString("Status", "minecraft:full");
            original.putByteArray("mod:large", new byte[65 * 1024 * 1024]);
            store.writeDeferred(ZERO, original, OPTIONS);
            assertTrue(worker.awaitIdle(10000));
            assertNull(failure.get());
            assertEquals(2, region(store).read(0).codec());
            assertEquals(original, decoded(store, ZERO));
        }
    }

    @Test
    void activityInAnotherRegionDoesNotResetQuietEligibility() throws Exception {
        try (BackgroundReencoder worker = manual(); CompactRegionStore store = new CompactRegionStore(folder, worker)) {
            store.writeDeferred(ZERO, fixture(0), OPTIONS);
            long afterFirst = System.nanoTime();
            store.writeDeferred(new ChunkPos(32, 0), fixture(32), OPTIONS);
            long quiet = TimeUnit.SECONDS.toNanos(1);
            assertTrue(store.backgroundReady(0, afterFirst + quiet, quiet));
            assertFalse(store.backgroundReady(ChunkPos.asLong(1, 0), afterFirst + quiet, quiet));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void globalFailureGuardBlocksBackgroundPublication() throws Exception {
        try (BackgroundReencoder worker = manual(); CompactRegionStore store = new CompactRegionStore(folder, worker)) {
            CompoundTag original = fixture(0);
            store.writeDeferred(ZERO, original, OPTIONS);
            var prepared = prepare(store);
            var field = StorageGuard.class.getDeclaredField("FAILURE");
            field.setAccessible(true);
            AtomicReference<StorageGuard.StorageFailure> guard = (AtomicReference<StorageGuard.StorageFailure>) field.get(null);
            assertNull(guard.get());
            try {
                StorageGuard.fail("simulated foreground failure", new IOException("test failure"));
                assertThrows(StorageGuard.StorageFailure.class,
                        () -> store.publishReencode(0, prepared, OPTIONS, () -> false));
                assertNull(region(store).dictionary());
            } finally { guard.set(null); }
            assertEquals(original, decoded(store, ZERO));
        }
    }

    @Test
    void failedBackgroundCommitRetainsAcknowledgedNbtAtEveryStage() throws Exception {
        for (CompactRegion.Stage stage : CompactRegion.Stage.values()) {
            Path path = folder.resolve(stage.name());
            CompoundTag original = fixture(0);
            try (BackgroundReencoder worker = manual(); CompactRegionStore store = new CompactRegionStore(path, worker)) {
                store.writeDeferred(ZERO, original, OPTIONS);
                var prepared = prepare(store);
                region(store).setFaultInjector(current -> { if (current == stage) throw new IOException("simulated background interruption"); });
                assertThrows(IOException.class, () -> store.publishReencode(0, prepared, OPTIONS, () -> false));
            }
            try (CompactRegionStore reopened = new CompactRegionStore(path)) {
                assertEquals(original, decoded(reopened, ZERO));
            }
        }
    }
}
