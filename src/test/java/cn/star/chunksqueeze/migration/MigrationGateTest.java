package cn.star.chunksqueeze.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MigrationGateTest {
    @TempDir Path folder;

    @Test
    void storageOperationsAndPublicationAreMutuallyExclusive() throws Exception {
        ExecutorService threads = Executors.newFixedThreadPool(2);
        CountDownLatch writerStarted = new CountDownLatch(1), writerAcquired = new CountDownLatch(1), releaseWriter = new CountDownLatch(1);
        CountDownLatch readerStarted = new CountDownLatch(1), readerAcquired = new CountDownLatch(1);
        try {
            Future<?> writer;
            try (MigrationGate.Lease ignored = MigrationGate.access()) {
                writer = threads.submit(() -> {
                    writerStarted.countDown();
                    return MigrationGate.exclusive(() -> {
                        writerAcquired.countDown();
                        await(releaseWriter);
                        return null;
                    });
                });
                assertTrue(writerStarted.await(2, TimeUnit.SECONDS));
                assertFalse(writerAcquired.await(100, TimeUnit.MILLISECONDS));
            }
            assertTrue(writerAcquired.await(2, TimeUnit.SECONDS));
            Future<?> reader = threads.submit(() -> {
                readerStarted.countDown();
                try (MigrationGate.Lease ignored = MigrationGate.region(folder, 0, 0)) { readerAcquired.countDown(); }
                return null;
            });
            assertTrue(readerStarted.await(2, TimeUnit.SECONDS));
            assertFalse(readerAcquired.await(100, TimeUnit.MILLISECONDS));
            releaseWriter.countDown();
            writer.get(5, TimeUnit.SECONDS);
            reader.get(5, TimeUnit.SECONDS);
        } finally {
            releaseWriter.countDown();
            threads.shutdownNow();
            assertTrue(threads.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void cacheEvictionRequiresOwnershipAndMatchesOnlyItsFolder() throws Exception {
        FakeStorage first = new FakeStorage(Files.createDirectory(folder.resolve("first")));
        FakeStorage second = new FakeStorage(Files.createDirectory(folder.resolve("second")));
        MigrationGate.register(first);
        MigrationGate.register(second);
        try {
            assertThrows(IOException.class, () -> MigrationGate.evict(first.path, 0, 0));
            MigrationGate.exclusive(() -> { MigrationGate.evict(first.path, 3, -2); return null; });
            assertEquals(1, first.closed.get());
            assertEquals(0, second.closed.get());
            MigrationGate.unregister(first);
            MigrationGate.exclusive(() -> { MigrationGate.evict(first.path, 3, -2); return null; });
            assertEquals(1, first.closed.get());
        } finally {
            MigrationGate.unregister(first);
            MigrationGate.unregister(second);
        }
    }

    @Test
    void cacheCloseFailureLocksStorageBeforeMaintenanceCanEnd() throws Exception {
        MigrationStorageAccess broken = new MigrationStorageAccess() {
            public Path chunksqueeze$migrationFolder() { return folder; }
            public void chunksqueeze$closeRegionForMigration(int x, int z) throws IOException {
                throw new IOException("simulated force failure");
            }
        };
        MigrationGate.register(broken);
        try {
            assertThrows(cn.star.chunksqueeze.storage.StorageGuard.StorageFailure.class,
                    () -> MigrationGate.exclusive(() -> { MigrationGate.evict(folder, 0, 0); return null; }));
            assertTrue(cn.star.chunksqueeze.storage.StorageGuard.isFailed());
            assertThrows(cn.star.chunksqueeze.storage.StorageGuard.StorageFailure.class,
                    cn.star.chunksqueeze.storage.StorageGuard::check);
        } finally {
            MigrationGate.unregister(broken);
            var field = cn.star.chunksqueeze.storage.StorageGuard.class.getDeclaredField("FAILURE");
            field.setAccessible(true);
            ((java.util.concurrent.atomic.AtomicReference<?>) field.get(null)).set(null);
        }
    }

    @Test
    void failedRecoveryDoesNotLeakALock() throws Exception {
        Files.write(MigrationTransaction.pendingPath(folder, 0, 0), new byte[]{1, 2, 3});
        assertThrows(IOException.class, () -> MigrationGate.region(folder, 0, 0));
        assertEquals(7, MigrationGate.exclusive(() -> 7));
        Files.delete(MigrationTransaction.pendingPath(folder, 0, 0));
        try (MigrationGate.Lease ignored = MigrationGate.region(folder, 0, 0)) { assertTrue(true); }
    }

    @Test
    void nestedReadToWriteUpgradeFailsRatherThanDeadlocking() throws Exception {
        try (MigrationGate.Lease ignored = MigrationGate.access()) {
            assertThrows(IOException.class, () -> MigrationGate.exclusive(() -> null));
        }
    }

    @Test
    void exclusiveSuccessAndFailureInvalidateVerifiedAbsence() throws Exception {
        for (boolean fail : new boolean[]{false, true}) {
            Path path = Files.createDirectory(folder.resolve("invalidate-" + fail));
            try (var ignored = MigrationGate.folder(path)) { }
            try (var ignored = MigrationGate.region(path, 0, 0)) { }
            MigrationGate.IOAction<Void> mutation = () -> {
                Files.write(MigrationTransaction.pendingPath(path, 0, 0), new byte[]{1});
                if (fail) throw new IOException("simulated transaction failure");
                return null;
            };
            if (fail) assertThrows(IOException.class, () -> MigrationGate.exclusive(mutation));
            else MigrationGate.exclusive(mutation);
            assertThrows(IOException.class, () -> MigrationGate.region(path, 0, 0));
            MigrationGate.exclusive(() -> { Files.delete(MigrationTransaction.pendingPath(path, 0, 0)); return null; });
            try (var ignored = MigrationGate.region(path, 0, 0)) { }
        }
    }

    @Test
    void newStorageAlwaysRescansDespiteCachedFolder() throws Exception {
        try (var ignored = MigrationGate.folder(folder)) { }
        Files.write(MigrationTransaction.pendingPath(folder, 0, 0), new byte[]{1});
        assertThrows(IOException.class, () -> MigrationGate.folder(folder));
        assertThrows(IOException.class, () -> MigrationGate.region(folder, 0, 0));
    }

    private static void await(CountDownLatch latch) throws IOException {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new IOException("Test gate timeout"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
    }
    private static final class FakeStorage implements MigrationStorageAccess {
        final Path path;
        final AtomicInteger closed = new AtomicInteger();
        FakeStorage(Path path) { this.path = path; }
        public Path chunksqueeze$migrationFolder() { return path; }
        public void chunksqueeze$closeRegionForMigration(int x, int z) throws IOException {
            MigrationGate.requireExclusive();
            assertEquals(3, x); assertEquals(-2, z);
            closed.incrementAndGet();
        }
    }
}
