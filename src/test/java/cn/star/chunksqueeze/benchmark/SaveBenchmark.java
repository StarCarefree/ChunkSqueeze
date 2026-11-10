package cn.star.chunksqueeze.benchmark;

import cn.star.chunksqueeze.codec.ChunkCodec;
import cn.star.chunksqueeze.codec.SectionCodec;
import cn.star.chunksqueeze.migration.MigrationGate;
import cn.star.chunksqueeze.migration.MigrationStorageAccess;
import cn.star.chunksqueeze.storage.CompactRegionStore;
import cn.star.chunksqueeze.storage.StorageGuard;
import com.google.gson.GsonBuilder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SaveBenchmark {
    private static final int COUNT = 256;
    private static final int ROUNDS = 3;
    private static final String REVISION = "benchmark:revision";
    private static final ChunkCodec.Options OPTIONS = new ChunkCodec.Options(128, true);
    private static final RegionStorageInfo INFO = new RegionStorageInfo("save-benchmark", Level.OVERWORLD, "chunk");
    private static final Constructor<RegionFileStorage> NATIVE;
    private static final Method WRITE;
    private static final Method FLUSH;
    private static final com.sun.management.ThreadMXBean THREAD =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final com.sun.management.OperatingSystemMXBean OS =
            (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    static {
        try {
            NATIVE = RegionFileStorage.class.getDeclaredConstructor(RegionStorageInfo.class, Path.class, boolean.class);
            WRITE = RegionFileStorage.class.getDeclaredMethod("write", ChunkPos.class, CompoundTag.class);
            FLUSH = RegionFileStorage.class.getDeclaredMethod("flush");
            NATIVE.setAccessible(true);
            WRITE.setAccessible(true);
            FLUSH.setAccessible(true);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private enum Backend {
        vanilla_async(false, "RegionFileStorage sync=false; no per-chunk force; explicit final flush"),
        vanilla_sync(true, "RegionFileStorage sync=true; native synchronous writes; explicit final flush"),
        cqs(false, "CompactRegionStore synchronous multi-phase commit; explicit final flush including compaction");

        final boolean sync;
        final String policy;
        Backend(boolean sync, String policy) { this.sync = sync; this.policy = policy; }
    }

    private record Chunk(ChunkPos pos, CompoundTag original, CompoundTag overwrite) {}
    private record Input(Path region, List<Chunk> chunks) {}
    private interface Store extends AutoCloseable {
        void write(ChunkPos pos, CompoundTag tag) throws Exception;
        CompoundTag read(ChunkPos pos) throws Exception;
        void flush() throws Exception;
        void close() throws Exception;
        default Map<String, Object> drain() throws Exception { return Map.of(); }
    }

    private static Store open(Path folder, Backend backend) throws Exception {
        if (backend != Backend.cqs) {
            RegionFileStorage storage = NATIVE.newInstance(INFO, folder, backend.sync);
            return new Store() {
                public void write(ChunkPos pos, CompoundTag tag) throws Exception { WRITE.invoke(storage, pos, tag); }
                public CompoundTag read(ChunkPos pos) throws IOException { return storage.read(pos); }
                public void flush() throws Exception { FLUSH.invoke(storage); }
                public void close() throws IOException { storage.close(); }
            };
        }
        try (var ignored = MigrationGate.folder(folder)) { /* 构造期恢复检查不计时。 */ }
        boolean deferred = Boolean.getBoolean("chunksqueeze.benchmark.deferred");
        if (deferred && cn.star.chunksqueeze.storage.BackgroundReencoder.current() == null) {
            cn.star.chunksqueeze.storage.BackgroundReencoder.start(0);
        }
        CompactRegionStore storage = deferred
                ? new CompactRegionStore(folder, cn.star.chunksqueeze.storage.BackgroundReencoder.current())
                : new CompactRegionStore(folder);
        return new Store() {
            public void write(ChunkPos pos, CompoundTag tag) throws IOException {
                StorageGuard.check();
                try (var ignored = MigrationGate.region(folder, pos.getRegionX(), pos.getRegionZ())) {
                    if (deferred) storage.writeDeferred(pos, tag, OPTIONS);
                    else StorageGuard.encoded(storage.writeEncoded(pos, tag, OPTIONS), 512, 0);
                    StorageGuard.compactWritten();
                } catch (IOException | RuntimeException failure) {
                    throw StorageGuard.fail(folder + " " + pos, failure);
                }
            }
            public CompoundTag read(ChunkPos pos) throws IOException {
                StorageGuard.check();
                try (var ignored = MigrationGate.region(folder, pos.getRegionX(), pos.getRegionZ())) {
                    CompoundTag stored = storage.read(pos);
                    if (stored == null) return null;
                    CompoundTag decoded = ChunkCodec.decode(stored);
                    if (stored.contains(ChunkCodec.KEY)) StorageGuard.decoded();
                    return decoded;
                }
            }
            public void flush() throws IOException {
                StorageGuard.check();
                try (var ignored = MigrationGate.access()) { storage.flush(); }
            }
            public void close() throws IOException {
                try (var ignored = MigrationGate.access()) { storage.close(); }
            }
            public Map<String, Object> drain() throws Exception {
                if (!deferred) return Map.of();
                Map<String, Object> result = new LinkedHashMap<>();
                long cpu = OS.getProcessCpuTime(), start = System.nanoTime();
                if (!cn.star.chunksqueeze.storage.BackgroundReencoder.awaitGlobalIdle(300000)) {
                    throw new IOException("Background reencoding timed out");
                }
                result.put("backgroundDrainWallMillis", (System.nanoTime() - start) / 1e6);
                result.put("backgroundDrainProcessCpuMillis", (OS.getProcessCpuTime() - cpu) / 1e6);
                long flushStart = System.nanoTime();
                flush();
                result.put("postBackgroundFlushMillis", (System.nanoTime() - flushStart) / 1e6);
                result.put("backgroundStatus", cn.star.chunksqueeze.storage.BackgroundReencoder.status());
                return result;
            }
        };
    }

    private static Input preload(Path root) throws Exception {
        Path mca = root.resolve("mca");
        Path region;
        try (var paths = Files.list(mca)) {
            region = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.mca"))
                    .sorted().findFirst().orElseThrow(() -> new IOException("No benchmark regions: " + mca));
        }
        String[] name = region.getFileName().toString().split("\\.");
        int regionX = Integer.parseInt(name[1]), regionZ = Integer.parseInt(name[2]);
        List<ChunkPos> positions = new ArrayList<>();
        try (var file = new RandomAccessFile(region.toFile(), "r")) {
            if (file.length() < 8192 || file.length() % 4096 != 0) {
                throw new IOException("Input must already be sector padded: " + region);
            }
            for (int slot = 0; slot < 1024 && positions.size() < COUNT; slot++) {
                if (file.readInt() != 0) positions.add(new ChunkPos(regionX * 32 + (slot & 31), regionZ * 32 + (slot >>> 5)));
            }
        }
        if (positions.size() != COUNT) throw new IOException("First region has fewer than 256 chunks: " + region);
        List<Chunk> chunks = new ArrayList<>();
        try (Store source = open(mca, Backend.vanilla_async)) {
            for (ChunkPos pos : positions) {
                CompoundTag original = source.read(pos);
                if (original == null || !original.contains("xPos", 3) || !original.contains("zPos", 3)
                        || original.getInt("xPos") != pos.x || original.getInt("zPos") != pos.z) {
                    throw new IOException("Missing chunk or coordinate mismatch: " + pos);
                }
                if (original.contains(ChunkCodec.KEY) || original.contains(SectionCodec.KEY)) {
                    throw new IOException("Input must contain original vanilla NBT: " + pos);
                }
                if (original.contains(REVISION)) throw new IOException("Input already contains benchmark revision: " + pos);
                CompoundTag overwrite = original.copy();
                overwrite.putInt(REVISION, 1);
                chunks.add(new Chunk(pos, original, overwrite));
            }
        }
        return new Input(region, chunks);
    }

    private static long allocated() { return THREAD.getThreadAllocatedBytes(Thread.currentThread().threadId()); }
    private static long percentile(long[] sorted, double p) { return sorted[(int) Math.ceil(sorted.length * p) - 1]; }
    private static Map<String, Object> options(Backend backend) {
        return backend == Backend.cqs ? Map.of("minimumSaving", 128, "requireSectorSaving", true)
                : Map.of("sync", backend.sync);
    }

    private static Map<String, Object> measure(Store store, List<Chunk> chunks, Backend backend,
                                               int round, boolean overwrite) throws Exception {
        long[] samples = new long[chunks.size()];
        long allocationBefore = allocated(), threadBefore = THREAD.getCurrentThreadCpuTime(), cpuBefore = OS.getProcessCpuTime();
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            CompoundTag tag = overwrite ? chunk.overwrite : chunk.original;
            long start = System.nanoTime();
            store.write(chunk.pos, tag);
            samples[i] = System.nanoTime() - start;
        }
        long processCpu = OS.getProcessCpuTime() - cpuBefore;
        long threadCpu = THREAD.getCurrentThreadCpuTime() - threadBefore, allocation = allocated() - allocationBefore;
        long wall = 0;
        for (long sample : samples) wall += sample;
        long flushAllocationBefore = allocated(), flushThreadBefore = THREAD.getCurrentThreadCpuTime();
        long flushCpuBefore = OS.getProcessCpuTime(), flushStart = System.nanoTime();
        store.flush();
        long flushWall = System.nanoTime() - flushStart, flushCpu = OS.getProcessCpuTime() - flushCpuBefore;
        long flushThread = THREAD.getCurrentThreadCpuTime() - flushThreadBefore, flushAllocation = allocated() - flushAllocationBefore;
        Arrays.sort(samples);
        var result = new LinkedHashMap<String, Object>();
        result.put("label", backend.name());
        result.put("round", round);
        result.put("stage", overwrite ? "overwrite" : "first_save");
        result.put("count", chunks.size());
        result.put("syncPolicy", backend.policy);
        result.put("options", options(backend));
        result.put("meanMicros", wall / 1000.0 / chunks.size());
        result.put("p50Micros", percentile(samples, .50) / 1000.0);
        result.put("p95Micros", percentile(samples, .95) / 1000.0);
        result.put("p99Micros", percentile(samples, .99) / 1000.0);
        result.put("maxMicros", samples[samples.length - 1] / 1000.0);
        result.put("writeWallMillis", wall / 1e6);
        result.put("writeProcessCpuMillis", processCpu / 1e6);
        result.put("writeThreadCpuMillis", threadCpu / 1e6);
        result.put("writeThreadAllocatedBytes", allocation);
        result.put("writeThreadAllocatedBytesPerChunk", allocation / (double) chunks.size());
        result.put("flushWallMillis", flushWall / 1e6);
        result.put("flushProcessCpuMillis", flushCpu / 1e6);
        result.put("flushThreadCpuMillis", flushThread / 1e6);
        result.put("flushThreadAllocatedBytes", flushAllocation);
        result.put("writePlusFlushWallMillis", (wall + flushWall) / 1e6);
        result.put("writePlusFlushProcessCpuMillis", (processCpu + flushCpu) / 1e6);
        result.put("writePlusFlushThreadCpuMillis", (threadCpu + flushThread) / 1e6);
        result.put("writePlusFlushThreadAllocatedBytes", allocation + flushAllocation);
        return result;
    }

    private static void verify(Path folder, Backend backend, List<Chunk> chunks, boolean overwrite) throws Exception {
        try (Store reader = open(folder, backend)) {
            for (Chunk chunk : chunks) {
                CompoundTag expected = overwrite ? chunk.overwrite : chunk.original;
                if (!expected.equals(reader.read(chunk.pos))) throw new IOException("Full NBT differs: " + folder + " " + chunk.pos);
            }
        }
    }

    private static long outputBytes(Path folder) throws IOException {
        long total = 0;
        try (var paths = Files.walk(folder)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) total += Files.size(path);
        }
        return total;
    }

    private static String codeSource(Class<?> type) {
        var source = type.getProtectionDomain().getCodeSource();
        return source == null ? "unavailable" : source.getLocation().toExternalForm();
    }

    public static void main(String[] args) throws Exception {
        try { run(args); }
        finally {
            if (Boolean.getBoolean("chunksqueeze.benchmark.deferred")) cn.star.chunksqueeze.storage.BackgroundReencoder.stopAndAwait();
        }
    }

    private static void run(String[] args) throws Exception {
        long started = System.nanoTime();
        if (args.length != 3) throw new IllegalArgumentException("Usage: SaveBenchmark <input-root> <new-output-root> <fork>");
        Path inputRoot = Path.of(args[0]).toRealPath();
        Path outputRoot = Path.of(args[1]).toAbsolutePath().normalize();
        int fork = Integer.parseInt(args[2]);
        if (Files.exists(outputRoot)) throw new IOException("Output root must not exist: " + outputRoot);
        if (outputRoot.startsWith(inputRoot)) throw new IOException("Output must be outside input root");
        if (MigrationStorageAccess.class.isAssignableFrom(RegionFileStorage.class)) {
            throw new IOException("Vanilla baseline must run without Mixin transformation");
        }
        if (!THREAD.isThreadAllocatedMemorySupported() || !THREAD.isCurrentThreadCpuTimeSupported()
                || OS.getProcessCpuTime() < 0) throw new IOException("Missing JVM counters");
        THREAD.setThreadAllocatedMemoryEnabled(true);
        THREAD.setThreadCpuTimeEnabled(true);
        Input input = preload(inputRoot);
        Files.createDirectories(outputRoot.getParent());
        if (outputRoot.getParent().toRealPath().startsWith(inputRoot)) throw new IOException("Output resolves inside input root");
        Files.createDirectory(outputRoot);
        Backend[] backends = Backend.values();
        for (Backend backend : backends) {
            Path scratch = outputRoot.resolve("warmup").resolve(backend.name());
            Files.createDirectories(scratch);
            try (Store store = open(scratch, backend)) {
                for (Chunk chunk : input.chunks.subList(0, 16)) store.write(chunk.pos, chunk.original);
                store.flush();
                store.drain();
            }
        }
        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, Long> backendBytes = new LinkedHashMap<>();
        for (int round = 0; round < ROUNDS; round++) {
            for (int order = 0; order < backends.length; order++) {
                Backend backend = backends[(int) Math.floorMod((long) fork + round + order, backends.length)];
                Path folder = outputRoot.resolve("round-" + round).resolve(backend.name());
                Files.createDirectories(folder.getParent());
                Files.createDirectory(folder);
                for (boolean overwrite : new boolean[]{false, true}) {
                    Map<String, Object> result;
                    try (Store store = open(folder, backend)) {
                        result = measure(store, input.chunks, backend, round, overwrite);
                        result.put("foregroundOutputBytes", outputBytes(folder));
                        result.putAll(store.drain());
                    }
                    // 每阶段均关闭后重开逐块比对；验证不调用额外 flush，不进入计时。
                    verify(folder, backend, input.chunks, overwrite);
                    if (Boolean.getBoolean("chunksqueeze.benchmark.deferred")
                            && !cn.star.chunksqueeze.storage.BackgroundReencoder.awaitGlobalIdle(300000)) {
                        throw new IOException("Verification tasks did not drain");
                    }
                    result.put("fullNBTverified", true);
                    result.put("orderInRound", order);
                    result.put("outputFolder", folder.toString());
                    result.put("outputBytes", outputBytes(folder));
                    results.add(result);
                    System.out.println("VERIFIED " + backend + " round=" + round + " stage=" + result.get("stage"));
                }
                backendBytes.merge(backend.name(), outputBytes(folder), Long::sum);
            }
        }
        Map<String, Object> sources = new LinkedHashMap<>();
        for (Class<?> type : List.of(SaveBenchmark.class, CompactRegionStore.class, ChunkCodec.class,
                SectionCodec.class, StorageGuard.class, MigrationGate.class, RegionFileStorage.class)) {
            sources.put(type.getName(), codeSource(type));
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("deferredEncoding", Boolean.getBoolean("chunksqueeze.benchmark.deferred"));
        report.put("inputRoot", inputRoot.toString());
        report.put("sourceRegion", input.region.toString());
        report.put("outputRoot", outputRoot.toString());
        report.put("fork", fork);
        report.put("rounds", ROUNDS);
        report.put("chunksPerStage", COUNT);
        report.put("warmupChunksPerBackend", 16);
        report.put("codeSource", sources);
        report.put("javaVersion", System.getProperty("java.version"));
        report.put("vmArguments", ManagementFactory.getRuntimeMXBean().getInputArguments());
        report.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        report.put("committedHeapBytes", Runtime.getRuntime().totalMemory());
        report.put("usedHeapBytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
        report.put("processors", Runtime.getRuntime().availableProcessors());
        report.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        report.put("backendOutputBytesExcludingWarmup", backendBytes);
        report.put("notes", List.of(
                "Three durability policies; compare async final-flush and sync baselines separately against CQS synchronous multi-phase commits.",
                "Vanilla constructor/write/flush use reflection without Mixin; Method.invoke overhead is included in microsecond/millisecond timings.",
                "CQS options are 128,true. Deferred mode measures durable standard NBT first, background drain and later flush separately; synchronous mode includes candidate encoding in write.",
                "Background drain includes remaining quiet-period delay; workers may overlap foreground work. Current-thread allocation excludes worker allocations.",
                "Input NBT and revision=1 overwrite copies are preloaded; sections and coordinates are unchanged. Warmup uses the same original inputs.",
                "Each round/backend has a fresh directory; overwrite reopens the first-save output after full NBT verification.",
                "Wall totals sum write calls only. CPU/allocation counters span the write loop including sampling overhead; allocation is current-thread only, CPU includes process-wide work.",
                "Explicit flush is timed separately. Construction, close, verification and size scans are excluded. Total elapsed includes setup, warmup and validation.",
                "One region with 256 chunks; outputBytes is logical file length, including CQS sparse/fixed overhead, not physical allocated disk space. OS caches are not cleared."));
        report.put("results", results);
        report.put("elapsedMillis", (System.nanoTime() - started) / 1e6);
        Path resultFile = outputRoot.resolve("results.json");
        Files.writeString(resultFile, new GsonBuilder().setPrettyPrinting().create().toJson(report),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.println("DONE " + resultFile);
    }
}
