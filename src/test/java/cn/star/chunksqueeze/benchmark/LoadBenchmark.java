package cn.star.chunksqueeze.benchmark;

import cn.star.chunksqueeze.codec.ChunkCodec;
import cn.star.chunksqueeze.codec.SectionCodec;
import cn.star.chunksqueeze.migration.MigrationGate;
import cn.star.chunksqueeze.storage.CompactRegionStore;
import cn.star.chunksqueeze.storage.StorageGuard;
import com.google.gson.GsonBuilder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.nio.file.*;
import java.util.*;

public final class LoadBenchmark {
    private static final RegionStorageInfo INFO = new RegionStorageInfo("load-benchmark", Level.OVERWORLD, "chunk");
    private static final Constructor<RegionFileStorage> NATIVE;
    private static final com.sun.management.ThreadMXBean THREAD =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static volatile CompoundTag sink;
    private static volatile long checksum;
    static {
        try {
            NATIVE = RegionFileStorage.class.getDeclaredConstructor(RegionStorageInfo.class, Path.class, boolean.class);
            NATIVE.setAccessible(true);
        } catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private record Region(Path mca, Path cqs, int x, int z, List<ChunkPos> positions) {}
    private record Request(int region, ChunkPos pos) {}
    private interface Reader extends AutoCloseable {
        CompoundTag read(ChunkPos pos) throws Exception;
        void close() throws Exception;
    }
    private static Reader open(Region region, boolean compact) throws Exception {
        if (!compact) {
            RegionFileStorage storage = NATIVE.newInstance(INFO, region.mca, false);
            return new Reader() {
                public CompoundTag read(ChunkPos pos) throws IOException { return storage.read(pos); }
                public void close() throws IOException { storage.close(); }
            };
        }
        // 对齐实际 Mixin 的 CQS 读取分支，包含门、缓存路由、CRC、解压和状态还原。
        try (var ignored = MigrationGate.folder(region.cqs)) { /* 构造时恢复检查，不计入单次读取。 */ }
        CompactRegionStore storage = new CompactRegionStore(region.cqs);
        return new Reader() {
            public CompoundTag read(ChunkPos pos) throws IOException {
                StorageGuard.check();
                try (var ignored = MigrationGate.region(region.cqs, pos.getRegionX(), pos.getRegionZ())) {
                    if (!storage.hasOpenRegion(pos) && !storage.owns(pos)) throw new IOException("Missing benchmark CQS");
                    CompoundTag stored = storage.read(pos);
                    if (stored == null) return null;
                    CompoundTag decoded = SectionCodec.decode(stored, null);
                    if (stored.contains(ChunkCodec.KEY)) StorageGuard.decoded();
                    return decoded;
                }
            }
            public void close() throws IOException { storage.close(); }
        };
    }
    private static final class Pool implements AutoCloseable {
        final Reader[] readers;
        Pool(List<Region> regions, boolean compact) throws Exception {
            readers = new Reader[regions.size()];
            Map<Path, Reader> shared = new HashMap<>();
            try {
                for (int i = 0; i < readers.length; i++) {
                    Region region = regions.get(i);
                    Path key = compact ? region.cqs : region.mca;
                    Reader reader = shared.get(key);
                    if (reader == null) { reader = open(region, compact); shared.put(key, reader); }
                    readers[i] = reader;
                }
            }
            catch (Exception failure) { close(); throw failure; }
        }
        CompoundTag read(Request request) throws Exception { return readers[request.region].read(request.pos); }
        public void close() throws Exception {
            Exception failure = null;
            Set<Reader> closed = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Reader reader : readers) if (reader != null && closed.add(reader)) {
                try { reader.close(); } catch (Exception ex) { if (failure == null) failure = ex; else failure.addSuppressed(ex); }
            }
            if (failure != null) throw failure;
        }
    }
    private static void consume(CompoundTag tag) throws IOException {
        if (tag == null) throw new IOException("Unexpected missing benchmark chunk");
        sink = tag;
        checksum += tag.getInt("xPos") * 31L + tag.getInt("zPos");
    }
    private static long allocated() { return THREAD.getThreadAllocatedBytes(Thread.currentThread().threadId()); }
    private static long[] gc() {
        long count = 0, millis = 0;
        for (var bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            count += Math.max(0, bean.getCollectionCount()); millis += Math.max(0, bean.getCollectionTime());
        }
        return new long[]{count, millis};
    }
    private static Map<String, Object> measure(Pool pool, List<Request> order, String backend, String pattern, int round) throws Exception {
        long[] samples = new long[order.size()];
        long[] gcBefore = gc(); long allocationBefore = allocated(), cpuBefore = THREAD.getCurrentThreadCpuTime();
        long start = System.nanoTime();
        for (int i = 0; i < order.size(); i++) {
            Request request = order.get(i);
            long begin = System.nanoTime();
            CompoundTag tag = pool.read(request);
            samples[i] = System.nanoTime() - begin;
            consume(tag);
        }
        long elapsed = System.nanoTime() - start;
        long cpu = THREAD.getCurrentThreadCpuTime() - cpuBefore, allocation = allocated() - allocationBefore;
        long[] gcAfter = gc();
        var result = stats(samples);
        result.put("backend", backend); result.put("pattern", pattern); result.put("round", round);
        result.put("wallMillis", elapsed / 1e6); result.put("cpuMillis", cpu / 1e6);
        result.put("chunksPerSecond", order.size() * 1e9 / elapsed);
        result.put("allocatedBytesPerChunk", allocation / (double) order.size());
        result.put("gcCollections", gcAfter[0] - gcBefore[0]); result.put("gcMillis", gcAfter[1] - gcBefore[1]);
        return result;
    }
    private static LinkedHashMap<String, Object> stats(long[] values) {
        long sum = 0; for (long value : values) sum += value;
        Arrays.sort(values);
        var result = new LinkedHashMap<String, Object>();
        result.put("reads", values.length); result.put("meanMicros", sum / 1000.0 / values.length);
        result.put("p50Micros", percentile(values, .50) / 1000.0);
        result.put("p95Micros", percentile(values, .95) / 1000.0);
        result.put("p99Micros", percentile(values, .99) / 1000.0);
        result.put("maxMicros", values[values.length - 1] / 1000.0);
        return result;
    }
    private static long percentile(long[] values, double p) { return values[Math.max(0, (int) Math.ceil(values.length * p) - 1)]; }
    private static List<Region> regions(Path root, int limit) throws Exception {
        boolean shared = Files.isDirectory(root.resolve("mca")) && Files.isDirectory(root.resolve("cqs"));
        List<Path> inputs;
        try (var paths = Files.list(shared ? root.resolve("mca") : root)) {
            inputs = paths.filter(p -> shared ? p.getFileName().toString().endsWith(".mca") : Files.isDirectory(p)).sorted().toList();
        }
        List<Region> result = new ArrayList<>();
        for (Path input : inputs) {
            if (limit > 0 && result.size() == limit) break;
            String[] name = input.getFileName().toString().split("\\.");
            int x = Integer.parseInt(name[1]), z = Integer.parseInt(name[2]);
            Path mca = shared ? root.resolve("mca") : input.resolve("mca");
            Path cqs = shared ? root.resolve("cqs") : input.resolve("cqs");
            List<ChunkPos> positions = new ArrayList<>();
            try (var file = new RandomAccessFile(mca.resolve("r." + x + "." + z + ".mca").toFile(), "r")) {
                for (int slot = 0; slot < 1024; slot++) if (file.readInt() != 0) {
                    positions.add(new ChunkPos(x * 32 + (slot & 31), z * 32 + (slot >>> 5)));
                }
            }
            if (!positions.isEmpty()) result.add(new Region(mca, cqs, x, z, positions));
        }
        if (result.isEmpty()) throw new IOException("No benchmark regions");
        return result;
    }
    private static Map<String, Object> diagnose(List<Region> regions, List<Request> requests) throws Exception {
        CompactRegionStore[] stores = new CompactRegionStore[regions.size()];
        try {
            for (int i = 0; i < stores.length; i++) {
                stores[i] = new CompactRegionStore(regions.get(i).cqs);
                stores[i].read(regions.get(i).positions.get(0));
            }
            int count = Math.min(4096, requests.size());
            long[] route = new long[count], decode = new long[count];
            long routeBytes = 0, decodeBytes = 0;
            for (int i = 0; i < count; i++) {
                Request request = requests.get((i * 61) % requests.size());
                Region region = regions.get(request.region);
                long allocation = allocated(), begin = System.nanoTime();
                StorageGuard.check();
                try (var ignored = MigrationGate.region(region.cqs, region.x, region.z)) {
                    CompactRegionStore store = stores[request.region];
                    if (!store.hasOpenRegion(request.pos) && !store.owns(request.pos)) throw new IOException("Missing CQS");
                    CompoundTag stored = store.read(request.pos);
                    route[i] = System.nanoTime() - begin;
                    routeBytes += allocated() - allocation;
                    allocation = allocated(); begin = System.nanoTime();
                    CompoundTag tag = SectionCodec.decode(stored, null);
                    decode[i] = System.nanoTime() - begin;
                    decodeBytes += allocated() - allocation;
                    consume(tag);
                }
            }
            var result = new LinkedHashMap<String, Object>();
            result.put("reads", count);
            result.put("routingReadCrcInflateParse", stats(route));
            result.put("stateDecode", stats(decode));
            result.put("routingAllocatedBytesPerChunk", routeBytes / (double) count);
            result.put("decodeAllocatedBytesPerChunk", decodeBytes / (double) count);
            return result;
        } finally { for (CompactRegionStore store : stores) if (store != null) store.close(); }
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]), output = Path.of(args[1]);
        int rounds = Integer.parseInt(args[2]), fork = Integer.parseInt(args[3]);
        int limit = args.length > 4 ? Integer.parseInt(args[4]) : 0;
        if (Files.exists(output)) throw new IOException("Result must not exist: " + output);
        if (cn.star.chunksqueeze.migration.MigrationStorageAccess.class.isAssignableFrom(RegionFileStorage.class)) {
            throw new IOException("Vanilla baseline must run without Mixin transformation");
        }
        if (!THREAD.isThreadAllocatedMemorySupported() || !THREAD.isCurrentThreadCpuTimeSupported()) throw new IOException("Missing JVM counters");
        THREAD.setThreadAllocatedMemoryEnabled(true); THREAD.setThreadCpuTimeEnabled(true);
        List<Region> regions = regions(root, limit);
        List<Request> sequential = new ArrayList<>();
        for (int i = 0; i < regions.size(); i++) for (ChunkPos pos : regions.get(i).positions) sequential.add(new Request(i, pos));
        System.out.println("START " + root.getFileName() + " fork=" + fork + " regions=" + regions.size() + " chunks=" + sequential.size());
        // 同时校验完整 NBT 并预热两条路径；首轮不声称为冷盘测试。
        try (Pool vanilla = new Pool(regions, false); Pool compact = new Pool(regions, true)) {
            for (Request request : sequential) {
                CompoundTag a = vanilla.read(request), b = compact.read(request);
                if (a == null || !a.equals(b)) throw new IOException("NBT differs at " + request.pos);
                consume(b);
            }
            for (int pass = 0; pass < 2; pass++) for (int i = 0; i < Math.min(4096, sequential.size()); i++) {
                Request request = sequential.get(i); consume(vanilla.read(request)); consume(compact.read(request));
            }
        }
        List<Map<String, Object>> results = new ArrayList<>();
        // 新建句柄后的首次读取：计入打开区域和索引检查，关闭与强制刷盘不计入读取时间。
        long[][] firstRead = new long[2][regions.size() * 5];
        for (int repeat = 0; repeat < 5; repeat++) for (int i = 0; i < regions.size(); i++) {
            Region region = regions.get(i);
            ChunkPos pos = region.positions.get((repeat * 197) % region.positions.size());
            for (int side = 0; side < 2; side++) {
                boolean compact = ((repeat + i + fork + side) & 1) != 0;
                try (Reader reader = open(region, compact)) {
                    long start = System.nanoTime(); CompoundTag tag = reader.read(pos);
                    firstRead[compact ? 1 : 0][repeat * regions.size() + i] = System.nanoTime() - start;
                    consume(tag);
                }
            }
        }
        for (int side = 0; side < 2; side++) {
            var result = stats(firstRead[side]); result.put("backend", side == 0 ? "vanilla" : "cqs");
            result.put("pattern", "reopen_first_chunk_os_cache_not_cleared"); results.add(result);
        }
        try (Pool vanilla = new Pool(regions, false); Pool compact = new Pool(regions, true)) {
            for (Request request : sequential) { consume(vanilla.read(request)); consume(compact.read(request)); }
            for (int round = 0; round < rounds; round++) {
                for (String pattern : List.of("sequential", "random")) {
                    List<Request> order = new ArrayList<>(sequential);
                    if (pattern.equals("random")) Collections.shuffle(order, new Random(0x5EEDL + round));
                    for (int side = 0; side < 2; side++) {
                        boolean useCompact = ((round + fork + side + (pattern.equals("random") ? 1 : 0)) & 1) != 0;
                        var result = measure(useCompact ? compact : vanilla, order, useCompact ? "cqs" : "vanilla", pattern, round);
                        results.add(result);
                        System.out.printf(Locale.ROOT, "%s round=%d %s mean=%.3f us p95=%.3f us wall=%.1f ms%n", pattern, round,
                                result.get("backend"), result.get("meanMicros"), result.get("p95Micros"), result.get("wallMillis"));
                    }
                }
            }
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("dataset", root.getFileName().toString()); report.put("fork", fork); report.put("rounds", rounds);
        report.put("regions", regions.size()); report.put("chunks", sequential.size()); report.put("javaVersion", System.getProperty("java.version"));
        report.put("vmArguments", ManagementFactory.getRuntimeMXBean().getInputArguments()); report.put("processors", Runtime.getRuntime().availableProcessors());
        report.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version")); report.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        report.put("storageLayout", Files.isDirectory(root.resolve("mca")) ? "one-storage-per-dimension" : "one-storage-per-region");
        report.put("fullNbtEqualityVerified", true);
        report.put("compactCodeSource", CompactRegionStore.class.getProtectionDomain().getCodeSource().getLocation().toString());
        report.put("checksum", checksum); report.put("results", results);
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(report), StandardOpenOption.CREATE_NEW);
        System.out.println("DONE " + output);
    }
}
