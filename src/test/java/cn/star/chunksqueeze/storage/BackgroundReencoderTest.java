package cn.star.chunksqueeze.storage;

import cn.star.chunksqueeze.codec.ChunkCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class BackgroundReencoderTest {
    @TempDir Path folder;
    private static final ChunkCodec.Options OPTIONS = new ChunkCodec.Options(32, true);
    private static final long TIMEOUT = 5000;

    private CompactRegion region(String name) throws IOException {
        return CompactRegion.open(folder.resolve(name).resolve("r.0.0.cqs"), 0, 0, true);
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(TIMEOUT, TimeUnit.MILLISECONDS), "Latch timed out");
    }

    private static BitSet slots(int from, int to) {
        BitSet slots = new BitSet(1024);
        slots.set(from, to);
        return slots;
    }

    private static void unexpectedFailure(Throwable failure) {
        throw new AssertionError("Unexpected background failure", failure);
    }

    @Test
    void activeOffersMergeAndDrainInBatchesOfAtMost32() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        List<BitSet> batches = Collections.synchronizedList(new ArrayList<>());
        List<ChunkCodec.Options> options = Collections.synchronizedList(new ArrayList<>());
        ChunkCodec.Options updated = new ChunkCodec.Options(64, false);
        try (CompactRegionStore store = new CompactRegionStore(folder.resolve("unopened"));
             CompactRegion region = region("merge");
             BackgroundReencoder reencoder = new BackgroundReencoder(2, 128, 0,
                     BackgroundReencoderTest::unexpectedFailure, (s, key, r, bits, opts, cancelled) -> {
                         batches.add((BitSet) bits.clone());
                         options.add(opts);
                         if (started.getCount() != 0) {
                             started.countDown();
                             await(release);
                         }
                     })) {
            try {
                reencoder.offer(store, 7, region, 0, OPTIONS);
                await(started);
                BitSet offered = slots(1, 81);
                reencoder.offerRegion(store, 7, region, offered, updated);
                offered.clear();
                reencoder.offer(store, 7, region, 20, updated);
                assertFalse(reencoder.awaitIdle(0));
            } finally { release.countDown(); }
            assertTrue(reencoder.awaitIdle(TIMEOUT));
            assertEquals(4, batches.size());
            BitSet all = new BitSet();
            for (int i = 0; i < batches.size(); i++) {
                BitSet batch = batches.get(i);
                assertTrue(batch.cardinality() <= 32);
                assertFalse(all.intersects(batch));
                all.or(batch);
                assertEquals(i == 0 ? OPTIONS : updated, options.get(i));
            }
            assertEquals(slots(0, 81), all);
        }
    }

    @Test
    void fullCapacitySkipsNewRegionsButAcceptsActiveRegionOffers() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        List<Long> keys = Collections.synchronizedList(new ArrayList<>());
        BitSet seen = new BitSet();
        try (CompactRegionStore store = new CompactRegionStore(folder.resolve("unopened"));
             CompactRegion region = region("capacity");
             CompactRegion other = region("skipped");
             BackgroundReencoder reencoder = new BackgroundReencoder(1, 1, 0,
                     BackgroundReencoderTest::unexpectedFailure, (s, key, r, bits, opts, cancelled) -> {
                         keys.add(key);
                         seen.or(bits);
                         if (started.getCount() != 0) {
                             started.countDown();
                             await(release);
                         }
                     })) {
            try {
                reencoder.offer(store, 7, region, 0, OPTIONS);
                await(started);
                reencoder.offer(store, 8, other, 0, OPTIONS);
                reencoder.offer(store, 7, region, 1, OPTIONS);
            } finally { release.countDown(); }
            assertTrue(reencoder.awaitIdle(TIMEOUT));
            assertEquals(List.of(7L, 7L), keys);
            assertEquals(slots(0, 2), seen);
            reencoder.offer(store, 8, other, 0, OPTIONS);
            assertTrue(reencoder.awaitIdle(TIMEOUT));
            assertEquals(List.of(7L, 7L, 8L), keys);
        }
    }

    @Test
    void sameRegionIsSerialWhileOtherRegionsRunInParallel() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1), bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger sameRegionActive = new AtomicInteger(), calls = new AtomicInteger();
        try (CompactRegionStore store = new CompactRegionStore(folder.resolve("unopened"));
             CompactRegion region = region("serial");
             CompactRegion other = region("parallel");
             BackgroundReencoder reencoder = new BackgroundReencoder(2, 128, 0,
                     BackgroundReencoderTest::unexpectedFailure, (s, key, r, bits, opts, cancelled) -> {
                         calls.incrementAndGet();
                         if (key == 7) assertEquals(1, sameRegionActive.incrementAndGet());
                         try {
                             if (key == 7) firstStarted.countDown();
                             bothStarted.countDown();
                             await(release);
                         } finally {
                             if (key == 7) sameRegionActive.decrementAndGet();
                         }
                     })) {
            try {
                reencoder.offer(store, 7, region, 0, OPTIONS);
                await(firstStarted);
                reencoder.offerRegion(store, 7, region, slots(1, 65), OPTIONS);
                reencoder.offer(store, 8, other, 0, OPTIONS);
                await(bothStarted);
                assertEquals(2, calls.get());
            } finally { release.countDown(); }
            assertTrue(reencoder.awaitIdle(TIMEOUT));
            assertEquals(4, calls.get());
        }
    }

    @Test
    void oldHandleCancellationAndCompletionCannotRemoveReplacement() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<BooleanSupplier> cancellation = new AtomicReference<>();
        AtomicBoolean oldRunning = new AtomicBoolean();
        AtomicInteger replacementCalls = new AtomicInteger();
        try (CompactRegionStore store = new CompactRegionStore(folder.resolve("unopened"));
             CompactRegion old = region("old");
             CompactRegion replacement = region("new");
             BackgroundReencoder reencoder = new BackgroundReencoder(2, 128, 0,
                     BackgroundReencoderTest::unexpectedFailure, (s, key, r, bits, opts, cancelled) -> {
                         if (r == old) {
                             oldRunning.set(true);
                             cancellation.set(cancelled);
                             started.countDown();
                             try { await(release); }
                             finally { oldRunning.set(false); }
                         } else {
                             assertSame(replacement, r);
                             assertFalse(oldRunning.get());
                             assertFalse(cancelled.getAsBoolean());
                             replacementCalls.incrementAndGet();
                         }
                     })) {
            try {
                reencoder.offer(store, 7, old, 0, OPTIONS);
                await(started);
                reencoder.offer(store, 7, old, 1, OPTIONS);
                reencoder.cancel(store, old);
                assertTrue(cancellation.get().getAsBoolean());
                reencoder.offer(store, 7, replacement, 2, OPTIONS);
                reencoder.cancel(store, old);
            } finally { release.countDown(); }
            assertTrue(reencoder.awaitIdle(TIMEOUT));
            assertEquals(1, replacementCalls.get());
        }
    }

    @Test
    void sameKeyNewHandleAutomaticallyCancelsOldJob() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<BooleanSupplier> cancellation = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        try (CompactRegionStore store = new CompactRegionStore(folder.resolve("unopened"));
             CompactRegion old = region("auto-old");
             CompactRegion replacement = region("auto-new");
             BackgroundReencoder reencoder = new BackgroundReencoder(1, 128, 0,
                     BackgroundReencoderTest::unexpectedFailure, (s, key, r, bits, opts, cancelled) -> {
                         calls.incrementAndGet();
                         if (r == old) {
                             cancellation.set(cancelled);
                             started.countDown();
                             await(release);
                         } else assertSame(replacement, r);
                     })) {
            try {
                reencoder.offer(store, 7, old, 0, OPTIONS);
                await(started);
                reencoder.offer(store, 7, replacement, 1, OPTIONS);
                assertTrue(cancellation.get().getAsBoolean());
            } finally { release.countDown(); }
            assertTrue(reencoder.awaitIdle(TIMEOUT));
            assertEquals(2, calls.get());
        }
    }

    @Test
    void cancellingStoreClearsOnlyItsJobs() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<BooleanSupplier> cancellation = new AtomicReference<>();
        AtomicInteger otherCalls = new AtomicInteger();
        try (CompactRegionStore store = new CompactRegionStore(folder.resolve("unopened"));
             CompactRegionStore otherStore = new CompactRegionStore(folder.resolve("other-unopened"));
             CompactRegion region = region("cancel-store");
             CompactRegion other = region("other-store");
             BackgroundReencoder reencoder = new BackgroundReencoder(1, 128, 0,
                     BackgroundReencoderTest::unexpectedFailure, (s, key, r, bits, opts, cancelled) -> {
                         if (s == store) {
                             assertEquals(7, key);
                             cancellation.set(cancelled);
                             started.countDown();
                             await(release);
                         } else {
                             assertSame(otherStore, s);
                             assertFalse(cancelled.getAsBoolean());
                             otherCalls.incrementAndGet();
                         }
                     })) {
            try {
                reencoder.offer(store, 7, region, 0, OPTIONS);
                await(started);
                reencoder.offer(store, 8, region, 1, OPTIONS);
                reencoder.offer(otherStore, 7, other, 2, OPTIONS);
                reencoder.cancel(store);
                assertTrue(cancellation.get().getAsBoolean());
            } finally { release.countDown(); }
            assertTrue(reencoder.awaitIdle(TIMEOUT));
            assertEquals(1, otherCalls.get());
        }
    }

    @Test
    void closeCancelsAndWaitsWithoutInterruptingActiveRunner() throws Exception {
        CountDownLatch started = new CountDownLatch(1), cancelledSeen = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        ExecutorService closer = Executors.newSingleThreadExecutor();
        try (CompactRegionStore store = new CompactRegionStore(folder.resolve("unopened"));
             CompactRegion region = region("stop");
             BackgroundReencoder reencoder = new BackgroundReencoder(1, 128, 0,
                     BackgroundReencoderTest::unexpectedFailure, (s, key, r, bits, opts, cancelled) -> {
                         calls.incrementAndGet();
                         started.countDown();
                         long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT);
                         while (!cancelled.getAsBoolean()) {
                             if (System.nanoTime() - deadline >= 0) fail("Cancellation timed out");
                             Thread.yield();
                         }
                         cancelledSeen.countDown();
                         try { await(release); }
                         catch (InterruptedException failure) {
                             interrupted.set(true);
                             throw failure;
                         }
                         interrupted.set(Thread.currentThread().isInterrupted());
                     })) {
            Future<?> closing = null;
            try {
                reencoder.offer(store, 7, region, 0, OPTIONS);
                await(started);
                reencoder.offer(store, 7, region, 1, OPTIONS);
                closing = closer.submit(reencoder::close);
                await(cancelledSeen);
                assertFalse(closing.isDone());
                assertFalse(reencoder.awaitIdle(1));
                reencoder.offer(store, 8, region, 2, OPTIONS);
            } finally { release.countDown(); }
            closing.get(TIMEOUT, TimeUnit.MILLISECONDS);
            assertTrue(reencoder.awaitIdle(TIMEOUT));
            assertFalse(interrupted.get());
            assertEquals(1, calls.get());
        } finally { closer.shutdownNow(); }
    }

    @Test
    void runnerFailureStopsQueuedWorkAndIdleExposesCause() throws Exception {
        IOException failure = new IOException("Injected failure");
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), handled = new CountDownLatch(1);
        AtomicReference<Throwable> reported = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        try (CompactRegionStore store = new CompactRegionStore(folder.resolve("unopened"));
             CompactRegion region = region("failure");
             BackgroundReencoder reencoder = new BackgroundReencoder(1, 128, 0,
                     cause -> { reported.set(cause); handled.countDown(); }, (s, key, r, bits, opts, cancelled) -> {
                         calls.incrementAndGet();
                         started.countDown();
                         await(release);
                         throw failure;
                     })) {
            try {
                reencoder.offer(store, 7, region, 0, OPTIONS);
                await(started);
                reencoder.offer(store, 8, region, 1, OPTIONS);
            } finally { release.countDown(); }
            await(handled);
            IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> reencoder.awaitIdle(TIMEOUT));
            assertSame(failure, thrown.getCause());
            assertSame(failure, reported.get());
            reencoder.offer(store, 9, region, 2, OPTIONS);
            reencoder.close();
            assertEquals(1, calls.get());
        }
    }

    @Test
    void globalLifecycleCanRestartAndStopRepeatedly() throws Exception {
        try {
            BackgroundReencoder.start(0);
            BackgroundReencoder first = BackgroundReencoder.current();
            assertNotNull(first);
            assertTrue(BackgroundReencoder.awaitGlobalIdle(TIMEOUT));
            assertTrue(BackgroundReencoder.status().contains("queued=0"));
            first.completed(false);
            first.completed(true);
            assertTrue(BackgroundReencoder.status().contains("attempted=2"));
            assertTrue(BackgroundReencoder.status().contains("rewritten=1"));
            BackgroundReencoder.start(1);
            assertSame(first, BackgroundReencoder.current());
            assertTrue(first.awaitIdle(TIMEOUT));
            BackgroundReencoder.stopAndAwait();
            assertNull(BackgroundReencoder.current());
            assertTrue(BackgroundReencoder.awaitGlobalIdle(0));
            BackgroundReencoder.stopAndAwait();
            BackgroundReencoder.start(2);
            BackgroundReencoder restarted = BackgroundReencoder.current();
            assertNotNull(restarted);
            assertNotSame(first, restarted);
            restarted.close();
            assertNull(BackgroundReencoder.current());
            BackgroundReencoder.start(1);
            assertNotNull(BackgroundReencoder.current());
            assertNotSame(restarted, BackgroundReencoder.current());
        } finally { BackgroundReencoder.stopAndAwait(); }
    }
}
