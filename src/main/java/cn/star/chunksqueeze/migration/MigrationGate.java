package cn.star.chunksqueeze.migration;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;

public final class MigrationGate {
    private static final ReentrantReadWriteLock GATE = new ReentrantReadWriteLock(true);
    private static final Map<MigrationStorageAccess, Boolean> STORAGES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Pattern JOURNAL = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.cqs\\.migrating");
    private record Probe(Path folder, int x, int z, boolean wholeFolder) {}
    private static final Map<Probe, Boolean> VERIFIED = new LinkedHashMap<>(64, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Probe, Boolean> eldest) { return size() > 4096; }
    };
    private MigrationGate() {}
    @FunctionalInterface public interface IOAction<T> { T run() throws IOException; }

    public static final class Lease implements AutoCloseable {
        private final Lock lock;
        private Lease(Lock lock) { this.lock = lock; }
        @Override public void close() { lock.unlock(); }
    }

    public static Lease access() {
        GATE.readLock().lock();
        return new Lease(GATE.readLock());
    }

    public static Lease region(Path folder, int x, int z) throws IOException {
        Path key = folder.toAbsolutePath().normalize();
        for (;;) {
            GATE.readLock().lock();
            try {
                synchronized (VERIFIED) {
                    if (VERIFIED.get(new Probe(key, 0, 0, true)) != null || VERIFIED.get(new Probe(key, x, z, false)) != null) {
                        return new Lease(GATE.readLock());
                    }
                }
                if (!pending(MigrationTransaction.pendingPath(folder, x, z))) {
                    synchronized (VERIFIED) { VERIFIED.put(new Probe(key, x, z, false), true); }
                    return new Lease(GATE.readLock());
                }
            } catch (IOException | RuntimeException failure) {
                GATE.readLock().unlock();
                throw failure;
            }
            GATE.readLock().unlock();
            exclusive(() -> { recover(folder, x, z); return null; });
        }
    }

    public static Lease folder(Path folder) throws IOException {
        forget(folder);
        for (;;) {
            GATE.readLock().lock();
            List<int[]> pending;
            try {
                pending = pendingRegions(folder);
                if (pending.isEmpty()) {
                    synchronized (VERIFIED) { VERIFIED.put(new Probe(folder.toAbsolutePath().normalize(), 0, 0, true), true); }
                    return new Lease(GATE.readLock());
                }
            } catch (IOException | RuntimeException failure) {
                GATE.readLock().unlock();
                throw failure;
            }
            GATE.readLock().unlock();
            exclusive(() -> {
                for (int[] region : pending) recover(folder, region[0], region[1]);
                return null;
            });
        }
    }

    public static <T> T exclusive(IOAction<T> action) throws IOException {
        if (GATE.getReadHoldCount() != 0 && !GATE.isWriteLockedByCurrentThread()) {
            throw new IOException("Cannot upgrade a storage read lease to migration ownership");
        }
        GATE.writeLock().lock();
        // 只在独占事务之间复用检查结果；成功与失败退出都使缓存失效。
        synchronized (VERIFIED) { VERIFIED.clear(); }
        try { return action.run(); }
        finally {
            synchronized (VERIFIED) { VERIFIED.clear(); }
            GATE.writeLock().unlock();
        }
    }

    public static void register(MigrationStorageAccess storage) throws IOException {
        try (Lease ignored = folder(storage.chunksqueeze$migrationFolder())) { STORAGES.put(storage, Boolean.TRUE); }
    }
    public static void unregister(MigrationStorageAccess storage) {
        STORAGES.remove(storage);
        forget(storage.chunksqueeze$migrationFolder());
    }

    private static void forget(Path folder) {
        Path key = folder.toAbsolutePath().normalize();
        synchronized (VERIFIED) { VERIFIED.keySet().removeIf(probe -> probe.folder.equals(key)); }
    }

    public static void evict(Path folder, int x, int z) throws IOException {
        requireExclusive();
        List<MigrationStorageAccess> snapshot;
        synchronized (STORAGES) { snapshot = new ArrayList<>(STORAGES.keySet()); }
        for (MigrationStorageAccess storage : snapshot) {
            if (storage != null && sameFolder(folder, storage.chunksqueeze$migrationFolder())) {
                try { storage.chunksqueeze$closeRegionForMigration(x, z); }
                catch (IOException | RuntimeException failure) {
                    throw cn.star.chunksqueeze.storage.StorageGuard.fail("Migration cache close at " + folder, failure);
                }
            }
        }
    }

    public static void recover(Path folder, int x, int z) throws IOException {
        requireExclusive();
        if (!pending(MigrationTransaction.pendingPath(folder, x, z))) return;
        evict(folder, x, z);
        MigrationTransaction.recover(folder, x, z);
    }

    public static void requireExclusive() throws IOException {
        if (!GATE.isWriteLockedByCurrentThread()) throw new IOException("Migration cache operation requires the exclusive gate");
    }

    public static boolean pending(Path path) throws IOException {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) throw new IOException("Migration journal must be a regular file: " + path);
            return true;
        } catch (NoSuchFileException absent) {
            return false;
        }
    }

    private static List<int[]> pendingRegions(Path folder) throws IOException {
        List<int[]> regions = new ArrayList<>();
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(folder, "*.cqs.migrating")) {
            for (Path path : paths) {
                var match = JOURNAL.matcher(path.getFileName().toString());
                if (!match.matches()) throw new IOException("Invalid migration journal filename: " + path);
                try {
                    int x = Integer.parseInt(match.group(1)), z = Integer.parseInt(match.group(2));
                    if (!path.getFileName().toString().equals("r." + x + "." + z + ".cqs.migrating")) throw new NumberFormatException();
                    pending(path);
                    regions.add(new int[]{x, z});
                } catch (NumberFormatException invalid) {
                    throw new IOException("Invalid migration region coordinates: " + path, invalid);
                }
            }
        } catch (NoSuchFileException absent) {
            return regions;
        }
        return regions;
    }

    private static boolean sameFolder(Path first, Path second) throws IOException {
        if (first.toAbsolutePath().normalize().equals(second.toAbsolutePath().normalize())) return true;
        try { return Files.isSameFile(first, second); }
        catch (NoSuchFileException absent) { return false; }
    }
}
