package cn.star.chunksqueeze.storage;

import cn.star.chunksqueeze.Chunksqueeze;
import cn.star.chunksqueeze.Config;
import cn.star.chunksqueeze.CommandLanguage;
import cn.star.chunksqueeze.codec.ChunkCodec;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

public final class StorageGuard {
    private static final AtomicReference<StorageFailure> FAILURE = new AtomicReference<>();
    private static final LongAdder ATTEMPTS = new LongAdder();
    private static final LongAdder ENCODED = new LongAdder();
    private static final LongAdder DECODED = new LongAdder();
    private static final LongAdder BYTES_SAVED = new LongAdder();
    private static final LongAdder ALLOCATION_SAVED = new LongAdder();
    private static final LongAdder COMPACT_WRITES = new LongAdder();

    private StorageGuard() {}

    public static StorageFailure fail(String context, Throwable cause) {
        StorageFailure failure = new StorageFailure("ChunkSqueeze refused unsafe storage access: " + context, cause);
        if (FAILURE.compareAndSet(null, failure)) {
            Chunksqueeze.LOGGER.error("ChunkSqueeze locked region writes. Stop and restore a verified backup before restarting.", failure);
        }
        return FAILURE.get();
    }

    public static boolean isFailed() { return FAILURE.get() != null; }

    public static void check() {
        StorageFailure failure = FAILURE.get();
        if (failure != null) throw failure;
    }

    public static void checkServer() {
        StorageFailure failure = FAILURE.get();
        if (failure != null) throw new ReportedException(CrashReport.forThrowable(failure, "ChunkSqueeze storage integrity failure"));
    }

    public static void encoded(ChunkCodec.Result result) { encoded(result, 4096, 5); }

    public static void encoded(ChunkCodec.Result result, int unit, int overhead) {
        ATTEMPTS.increment();
        if (result.optimized()) {
            ENCODED.increment();
            BYTES_SAVED.add(result.originalBytes() - result.storedBytes());
            ALLOCATION_SAVED.add(ChunkCodec.allocatedBytes(result.originalBytes(), unit, overhead)
                    - ChunkCodec.allocatedBytes(result.storedBytes(), unit, overhead));
        }
    }

    public static void decoded() { DECODED.increment(); }
    public static void compactWritten() { COMPACT_WRITES.increment(); }

    public static String status() {
        return status(CommandLanguage.ENGLISH);
    }

    public static String status(CommandLanguage language) {
        return language.text(
                "ChunkSqueeze 0.6.1 | 新建紧凑区域=%s | 紧凑写入=%s | 编码尝试=%s | 已编码=%s | 已解码=%s | 估算节省字节=%s | 状态编码估算节省分配字节=%s | 存储锁定=%s",
                "ChunkSqueeze 0.6.1 | new compact regions=%s | compact writes=%s | attempts=%s | encoded=%s | decoded=%s | estimated bytes saved=%s | estimated allocation bytes saved by state encoding=%s | locked=%s",
                language.flag(Config.compactRegions), COMPACT_WRITES.sum(), ATTEMPTS.sum(), ENCODED.sum(), DECODED.sum(),
                BYTES_SAVED.sum(), ALLOCATION_SAVED.sum(), language.flag(FAILURE.get() != null));
    }

    public static final class StorageFailure extends RuntimeException {
        private StorageFailure(String message, Throwable cause) { super(message, cause); }
    }
}
