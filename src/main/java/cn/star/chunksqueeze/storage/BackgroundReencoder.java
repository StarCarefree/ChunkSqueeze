package cn.star.chunksqueeze.storage;

import cn.star.chunksqueeze.codec.ChunkCodec;
import cn.star.chunksqueeze.CommandLanguage;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class BackgroundReencoder implements AutoCloseable {
    private static final Object LIFECYCLE = new Object();
    private static final int REGION_SLOTS = 1024;
    private static final int BATCH_SLOTS = 32;
    private static final long WORKER_MEMORY = 768L * 1024 * 1024;
    private static volatile BackgroundReencoder global;

    private final Object lock = new Object();
    private final Map<Key, Job> jobs = new LinkedHashMap<>();
    private final Set<Key> activeKeys = new HashSet<>();
    private final Set<Job> activeJobs = new HashSet<>();
    private final List<Thread> workers = new ArrayList<>();
    private final int maxRegions;
    private final long quietNanos;
    private final Consumer<Throwable> failureHandler;
    private final BatchRunner runner;
    private volatile boolean stopping;
    private volatile Throwable failure;
    private long attempted;
    private long rewritten;
    private long skipped;
    private long dropped;

    @FunctionalInterface
    interface BatchRunner {
        void run(CompactRegionStore store, long regionKey, CompactRegion region, BitSet slots,
                 ChunkCodec.Options options, BooleanSupplier cancelled) throws Exception;
    }

    BackgroundReencoder(int threads, int maxRegions, long quietMillis, Consumer<Throwable> failureHandler) {
        this(threads, maxRegions, quietMillis, failureHandler, null);
    }

    BackgroundReencoder(int threads, int maxRegions, long quietMillis,
                        Consumer<Throwable> failureHandler, BatchRunner runner) {
        if (threads < 1 || maxRegions < 1 || quietMillis < 0) throw new IllegalArgumentException();
        this.maxRegions = maxRegions;
        this.quietNanos = TimeUnit.MILLISECONDS.toNanos(quietMillis);
        this.failureHandler = Objects.requireNonNull(failureHandler);
        this.runner = runner;
        for (int i = 0; i < threads; i++) {
            Thread worker = new Thread(this::work, "ChunkSqueeze-reencoder-" + (i + 1));
            worker.setDaemon(true);
            worker.setPriority(Thread.MIN_PRIORITY);
            workers.add(worker);
        }
        for (Thread worker : workers) worker.start();
    }

    public static void start(int configuredThreads) {
        if (configuredThreads < 0 || configuredThreads > 2) throw new IllegalArgumentException("Expected 0, 1 or 2 threads");
        synchronized (LIFECYCLE) {
            BackgroundReencoder previous = global;
            if (previous != null && !previous.stopping) return;
            global = null;
            if (previous != null) previous.close();
            int cpuLimit = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
            long heapLimit = Math.max(1, Runtime.getRuntime().maxMemory() / 2 / WORKER_MEMORY);
            int requested = configuredThreads == 0 ? cpuLimit : configuredThreads;
            int threads = (int) Math.max(1, Math.min(2, Math.min(cpuLimit, Math.min(requested, heapLimit))));
            global = new BackgroundReencoder(threads, 128, 500,
                    cause -> StorageGuard.fail("background region reencoding", cause));
        }
    }

    public static BackgroundReencoder current() {
        BackgroundReencoder current = global;
        return current == null || current.stopping ? null : current;
    }

    public static void stopAndAwait() {
        synchronized (LIFECYCLE) {
            BackgroundReencoder previous = global;
            global = null;
            if (previous != null) previous.close();
        }
    }

    public static boolean awaitGlobalIdle(long timeoutMillis) throws InterruptedException {
        StorageGuard.check();
        BackgroundReencoder current = global;
        boolean idle = current == null || current.awaitIdle(timeoutMillis);
        StorageGuard.check();
        return idle;
    }

    public static String status() {
        return status(CommandLanguage.ENGLISH);
    }

    public static String status(CommandLanguage language) {
        BackgroundReencoder current = global;
        return current == null ? language.text(
                "后台已停止 | 排队区域=0 | 活动任务=0 | 尝试=0 | 重编码=0 | 跳过=0 | 丢弃请求=0",
                "background stopped | queued=0 | active=0 | attempted=0 | rewritten=0 | skipped=0 | dropped=0")
                : current.describe(language);
    }

    void offer(CompactRegionStore store, long regionKey, CompactRegion region, int slot, ChunkCodec.Options options) {
        if (slot < 0 || slot >= REGION_SLOTS) throw new IllegalArgumentException("Invalid region slot");
        BitSet slots = new BitSet(REGION_SLOTS);
        slots.set(slot);
        offerRegion(store, regionKey, region, slots, options);
    }

    void offerRegion(CompactRegionStore store, long regionKey, CompactRegion region,
                     BitSet slots, ChunkCodec.Options options) {
        Objects.requireNonNull(store);
        Objects.requireNonNull(region);
        Objects.requireNonNull(options);
        if (slots.length() > REGION_SLOTS) throw new IllegalArgumentException("Invalid region slots");
        if (slots.isEmpty()) return;
        Key key = new Key(store.backgroundId(), regionKey);
        synchronized (lock) {
            if (stopping) {
                dropped += slots.cardinality();
                return;
            }
            Job job = jobs.get(key);
            if (job != null && (job.store.get() != store || job.region.get() != region)) {
                cancelJob(job);
                jobs.remove(key, job);
                job = null;
            }
            if (job == null) {
                if (jobs.size() >= maxRegions) {
                    skipped += slots.cardinality();
                    return;
                }
                job = new Job(key, store, region, options);
                jobs.put(key, job);
            }
            job.options = options;
            for (int slot = slots.nextSetBit(0); slot >= 0; slot = slots.nextSetBit(slot + 1)) {
                if (!job.pending.get(slot)) {
                    job.order[(job.head + job.size) % REGION_SLOTS] = slot;
                    job.size++;
                    job.pending.set(slot);
                }
            }
            lock.notifyAll();
        }
    }

    void completed(boolean rewritten) {
        synchronized (lock) {
            attempted++;
            if (rewritten) this.rewritten++;
        }
    }

    void cancel(CompactRegionStore store, CompactRegion region) {
        cancel(store.backgroundId(), region);
    }

    void cancel(CompactRegionStore store) { cancel(store.backgroundId(), null); }

    private void cancel(long storeId, CompactRegion region) {
        synchronized (lock) {
            jobs.values().removeIf(job -> {
                if (job.key.storeId == storeId && (region == null || job.region.get() == region)) {
                    cancelJob(job);
                    return true;
                }
                return false;
            });
            for (Job job : activeJobs) {
                if (job.key.storeId == storeId && (region == null || job.region.get() == region)) cancelJob(job);
            }
            lock.notifyAll();
        }
    }

    private void cancelJob(Job job) {
        job.cancelled = true;
        dropped += job.pending.cardinality();
        job.pending.clear();
        job.head = 0;
        job.size = 0;
    }

    private Batch take() {
        List<Job> candidates;
        synchronized (lock) {
            if (stopping) return null;
            candidates = new ArrayList<>(jobs.values());
        }
        for (Job job : candidates) {
            CompactRegionStore store = job.store.get();
            CompactRegion region = job.region.get();
            if (store == null || region == null) {
                synchronized (lock) {
                    cancelJob(job);
                    jobs.remove(job.key, job);
                    lock.notifyAll();
                }
                continue;
            }
            // 静默检查不持队列锁，避免与存储端的锁顺序耦合。
            if (!store.backgroundReady(job.key.regionKey, System.nanoTime(), quietNanos)) continue;
            synchronized (lock) {
                if (stopping) return null;
                if (job.cancelled || jobs.get(job.key) != job || activeKeys.contains(job.key) || job.pending.isEmpty()) continue;
                BitSet slots = new BitSet(REGION_SLOTS);
                int[] ordered = new int[Math.min(job.size, BATCH_SLOTS)];
                for (int i = 0; i < ordered.length; i++) {
                    int slot = job.order[job.head];
                    job.head = (job.head + 1) % REGION_SLOTS;
                    job.size--;
                    job.pending.clear(slot);
                    ordered[i] = slot;
                    slots.set(slot);
                }
                activeKeys.add(job.key);
                activeJobs.add(job);
                return new Batch(job, store, region, slots, ordered, job.options);
            }
        }
        return null;
    }

    private void work() {
        try {
            while (!stopping) {
                Batch batch = take();
                if (batch == null) {
                    synchronized (lock) {
                        if (!stopping) lock.wait(50);
                    }
                    continue;
                }
                try {
                    BooleanSupplier cancelled = () -> stopping || batch.job.cancelled;
                    if (!cancelled.getAsBoolean()) {
                        if (runner == null) batch.store.reencodeOrderedBatch(batch.job.key.regionKey, batch.region,
                                batch.ordered, batch.options, cancelled);
                        else runner.run(batch.store, batch.job.key.regionKey, batch.region, batch.slots, batch.options, cancelled);
                    }
                } catch (Throwable cause) {
                    fail(cause);
                } finally {
                    synchronized (lock) {
                        activeJobs.remove(batch.job);
                        activeKeys.remove(batch.job.key);
                        if (batch.job.pending.isEmpty()) jobs.remove(batch.job.key, batch.job);
                        else if (jobs.get(batch.job.key) == batch.job) {
                            // 一批完成后移到末尾，让其他区域也有机会领取。
                            jobs.remove(batch.job.key);
                            jobs.put(batch.job.key, batch.job);
                        }
                        lock.notifyAll();
                    }
                }
            }
        } catch (Throwable cause) {
            fail(cause);
        }
    }

    private void fail(Throwable cause) {
        synchronized (lock) {
            if (failure != null) return;
            failure = cause;
            stopLocked();
        }
        try { failureHandler.accept(cause); }
        catch (Throwable handlerFailure) {
            if (handlerFailure != cause) cause.addSuppressed(handlerFailure);
        }
    }

    private void stopLocked() {
        stopping = true;
        for (Job job : jobs.values()) cancelJob(job);
        for (Job job : activeJobs) cancelJob(job);
        jobs.clear();
        lock.notifyAll();
    }

    public boolean awaitIdle(long timeoutMillis) throws InterruptedException {
        if (timeoutMillis < 0) throw new IllegalArgumentException("Negative timeout");
        long budget = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        long started = System.nanoTime();
        synchronized (lock) {
            while (true) {
                if (failure != null) throw new IllegalStateException("Background reencoding failed", failure);
                if (jobs.isEmpty() && activeJobs.isEmpty()) return true;
                long remaining = budget - (System.nanoTime() - started);
                if (remaining <= 0) return false;
                TimeUnit.NANOSECONDS.timedWait(lock, remaining);
            }
        }
    }

    private String describe(CommandLanguage language) {
        synchronized (lock) {
            long queued = jobs.values().stream().filter(job -> !job.pending.isEmpty()).count();
            return language.text(
                    "后台%s | 工作线程=%s | 排队区域=%s | 活动任务=%s | 尝试=%s | 重编码=%s | 跳过=%s | 丢弃请求=%s | 故障=%s",
                    "background %s | workers=%s | queued=%s | active=%s | attempted=%s | rewritten=%s | skipped=%s | dropped=%s | failed=%s",
                    language.text(stopping ? "已停止" : "运行中", stopping ? "stopped" : "running"), workers.size(), queued,
                    activeJobs.size(), attempted, rewritten, skipped, dropped, language.flag(failure != null));
        }
    }

    @Override
    public void close() {
        synchronized (lock) { stopLocked(); }
        boolean interrupted = false;
        // 不 interrupt：活动编码允许完成当前文件通道操作。
        for (Thread worker : workers) {
            if (worker == Thread.currentThread()) continue;
            boolean joined = false;
            while (!joined) {
                try {
                    worker.join();
                    joined = true;
                } catch (InterruptedException ignored) { interrupted = true; }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private record Key(long storeId, long regionKey) {}

    private static final class Job {
        private final Key key;
        private final WeakReference<CompactRegionStore> store;
        private final WeakReference<CompactRegion> region;
        private final BitSet pending = new BitSet(REGION_SLOTS);
        // 固定长度环形队列保留到达顺序，避免拆散相邻写入的重复区段。
        private final int[] order = new int[REGION_SLOTS];
        private int head;
        private int size;
        private ChunkCodec.Options options;
        private volatile boolean cancelled;

        private Job(Key key, CompactRegionStore store, CompactRegion region, ChunkCodec.Options options) {
            this.key = key;
            this.store = new WeakReference<>(store);
            this.region = new WeakReference<>(region);
            this.options = options;
        }
    }

    private record Batch(Job job, CompactRegionStore store, CompactRegion region,
                         BitSet slots, int[] ordered, ChunkCodec.Options options) {}
}
