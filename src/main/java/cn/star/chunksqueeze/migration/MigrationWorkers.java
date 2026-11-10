package cn.star.chunksqueeze.migration;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class MigrationWorkers {
    private static final long WORKER_RESERVE = 768L * 1024 * 1024;
    private MigrationWorkers() {}
    @FunctionalInterface public interface Action<T> { void run(T item) throws Exception; }

    public static int parallelism(int requested) {
        return parallelism(requested, Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory());
    }

    static int parallelism(int requested, int processors, long heap) {
        int memoryLimit = (int) Math.max(1, Math.min(16, heap / 2 / WORKER_RESERVE));
        int cpuLimit = Math.max(1, processors - 1);
        int desired = requested == 0 ? Math.min(8, cpuLimit) : Math.max(1, Math.min(16, requested));
        return Math.max(1, Math.min(desired, Math.min(cpuLimit, memoryLimit)));
    }

    public static <T> void run(List<T> items, int workers, AtomicBoolean cancelled, String name, Action<T> action) throws Exception {
        if (workers < 1 || workers > 16) throw new IllegalArgumentException("Invalid migration worker count");
        if (items.isEmpty() || cancelled.get()) return;
        int count = Math.min(workers, items.size());
        AtomicInteger next = new AtomicInteger(), names = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(count, task -> {
            Thread thread = new Thread(task, name + "-prepare-" + names.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        boolean interrupted = false;
        try {
            // 只提交固定数量的循环任务，避免数万个区域的 Future 和快照排队占用内存。
            for (int worker = 0; worker < count; worker++) {
                pool.execute(() -> {
                    try {
                        while (!cancelled.get()) {
                            int index = next.getAndIncrement();
                            if (index >= items.size() || cancelled.get()) return;
                            action.run(items.get(index));
                        }
                    } catch (Throwable error) {
                        if (!(error instanceof MigrationTransaction.Cancelled && cancelled.get())) {
                            Throwable first = failure.compareAndExchange(null, error);
                            if (first != null && first != error) synchronized (first) { first.addSuppressed(error); }
                        }
                        cancelled.set(true);
                    }
                });
            }
        } catch (RuntimeException | Error startupFailure) {
            cancelled.set(true);
            throw startupFailure;
        } finally {
            pool.shutdown();
            // 不使用 shutdownNow/cancel(true)，以免中断正在写入的文件通道。
            while (!pool.isTerminated()) {
                try { pool.awaitTermination(1, TimeUnit.SECONDS); }
                catch (InterruptedException stop) { interrupted = true; cancelled.set(true); }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
        Throwable error = failure.get();
        if (error instanceof Error fatal) throw fatal;
        if (error instanceof Exception exception) throw exception;
        if (error != null) throw new IOException("Migration worker failed", error);
    }
}
