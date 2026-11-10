package cn.star.chunksqueeze.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class MigrationWorkersTest {
    @Test
    void boundedWorkersRunInParallelWithoutDuplicatingTargets() throws Exception {
        AtomicBoolean cancel = new AtomicBoolean();
        AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
        Set<Integer> seen = ConcurrentHashMap.newKeySet();
        CountDownLatch firstWave = new CountDownLatch(4);
        MigrationWorkers.run(IntStream.range(0, 40).boxed().toList(), 4, cancel, "test", item -> {
            int count = active.incrementAndGet(); peak.accumulateAndGet(count, Math::max);
            try {
                assertTrue(seen.add(item));
                if (item < 4) { firstWave.countDown(); assertTrue(firstWave.await(5, TimeUnit.SECONDS)); }
                MigrationGate.exclusive(() -> { assertTrue(count <= 4); return null; });
            } finally { active.decrementAndGet(); }
        });
        assertEquals(4, peak.get()); assertEquals(40, seen.size()); assertEquals(0, active.get());
    }

    @Test
    void failureCancelsAdmissionAndWaitsForCleanupWithoutInterruptingWorkers() throws Exception {
        AtomicBoolean cancel = new AtomicBoolean(), cleaned = new AtomicBoolean(), interrupted = new AtomicBoolean();
        AtomicInteger started = new AtomicInteger();
        CountDownLatch both = new CountDownLatch(2);
        IOException failure = assertThrows(IOException.class, () -> MigrationWorkers.run(List.of(0, 1, 2, 3), 2, cancel, "failure", item -> {
            started.incrementAndGet(); both.countDown(); assertTrue(both.await(5, TimeUnit.SECONDS));
            if (item == 0) throw new IOException("injected failure");
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!cancel.get() && System.nanoTime() < deadline) Thread.onSpinWait();
                assertTrue(cancel.get());
                interrupted.set(Thread.currentThread().isInterrupted());
                throw new MigrationTransaction.Cancelled();
            } finally { cleaned.set(true); }
        }));
        assertEquals("injected failure", failure.getMessage());
        assertEquals(2, started.get()); assertTrue(cleaned.get()); assertFalse(interrupted.get());
    }

    @Test
    void cooperativeCancellationDrainsAllWorkersAndDoesNotStartQueuedRegions() throws Exception {
        AtomicBoolean cancel = new AtomicBoolean();
        AtomicInteger active = new AtomicInteger(), finished = new AtomicInteger(), started = new AtomicInteger();
        CountDownLatch both = new CountDownLatch(2);
        MigrationWorkers.run(List.of(0, 1, 2, 3), 2, cancel, "cancel", item -> {
            started.incrementAndGet(); active.incrementAndGet();
            try {
                both.countDown(); assertTrue(both.await(5, TimeUnit.SECONDS));
                cancel.set(true);
                assertFalse(Thread.currentThread().isInterrupted());
                throw new MigrationTransaction.Cancelled();
            } finally { active.decrementAndGet(); finished.incrementAndGet(); }
        });
        assertEquals(2, started.get()); assertEquals(2, finished.get()); assertEquals(0, active.get());
    }

    @Test
    void interruptedCoordinatorStillDrainsWorkersWithoutInterruptingChannels() throws Exception {
        AtomicBoolean cancel = new AtomicBoolean(), workerInterrupted = new AtomicBoolean(), finished = new AtomicBoolean();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch entered = new CountDownLatch(1);
        Thread coordinator = new Thread(() -> {
            try {
                MigrationWorkers.run(List.of(0), 1, cancel, "stop", item -> {
                    entered.countDown();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (!cancel.get() && System.nanoTime() < deadline) Thread.onSpinWait();
                    assertTrue(cancel.get());
                    workerInterrupted.set(Thread.currentThread().isInterrupted());
                    finished.set(true);
                });
                assertTrue(Thread.currentThread().isInterrupted());
            } catch (Throwable failure) { error.set(failure); }
        });
        coordinator.start(); assertTrue(entered.await(5, TimeUnit.SECONDS)); coordinator.interrupt();
        coordinator.join(5000);
        assertFalse(coordinator.isAlive()); assertNull(error.get()); assertTrue(finished.get()); assertFalse(workerInterrupted.get());
    }

    @Test
    void autoAndRequestedParallelismRespectHeapAndCpuLimits() {
        long gib = 1024L * 1024 * 1024;
        assertEquals(8, MigrationWorkers.parallelism(0, 12, 24 * gib));
        assertEquals(4, MigrationWorkers.parallelism(4, 12, 24 * gib));
        assertEquals(1, MigrationWorkers.parallelism(16, 12, 2 * gib));
        assertEquals(4, MigrationWorkers.parallelism(16, 12, 6 * gib));
        assertEquals(1, MigrationWorkers.parallelism(0, 1, 24 * gib));
        assertEquals(11, MigrationWorkers.parallelism(16, 12, 24 * gib));
    }
}
