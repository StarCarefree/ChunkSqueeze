package cn.star.chunksqueeze.migration;

import cn.star.chunksqueeze.codec.ChunkCodec;
import cn.star.chunksqueeze.codec.RegionStateDictionary;
import cn.star.chunksqueeze.storage.CompactRegion;
import com.google.gson.GsonBuilder;
import net.minecraft.nbt.*;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.zip.*;

public final class MigrationBenchmark {
    private record Target(Path original, Path folder, int x, int z) {}
    public static void main(String[] args) throws Exception {
        Path input = Path.of(args[0]), output = Path.of(args[1]);
        int workers = Integer.parseInt(args[2]), limit = Integer.parseInt(args[3]);
        String label = args[4];
        if (Files.exists(output)) throw new IOException("Output exists");
        Files.createDirectories(output);
        List<Path> sources;
        try (var files = Files.walk(input)) { sources = files.filter(p -> p.toString().endsWith(".mca")).sorted().limit(limit).toList(); }
        if (sources.isEmpty()) throw new IOException("No samples");
        List<Target> targets = new ArrayList<>();
        for (Path source : sources) {
            String[] name = source.getFileName().toString().split("\\.");
            int x = Integer.parseInt(name[1]), z = Integer.parseInt(name[2]);
            Path folder = Files.createDirectory(output.resolve("r." + x + "." + z));
            Files.copy(source, folder.resolve(source.getFileName()));
            targets.add(new Target(source, folder, x, z));
        }
        // 预热编码与解码，不包含在迁移计时内。
        McaSnapshot warm = McaSnapshot.capture(targets.getFirst().folder, targets.getFirst().x, targets.getFirst().z);
        for (int repeat = 0; repeat < 3; repeat++) {
            for (var chunk : warm.chunks().subList(0, Math.min(64, warm.chunks().size()))) {
                CompoundTag tag = inflate(chunk.payload(), chunk.codec());
                ChunkCodec.decode(ChunkCodec.encode(tag, new ChunkCodec.Options(128, true), 512, 0).tag());
            }
        }
        warm = null;
        AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
        LongAdder captureNanos = new LongAdder(), prepareNanos = new LongAdder(), publishNanos = new LongAdder();
        AtomicBoolean cancel = new AtomicBoolean();
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        long cpuBefore = os.getProcessCpuTime(), start = System.nanoTime();
        MigrationWorkers.run(targets, workers, cancel, "migration-benchmark", target -> {
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            MigrationTransaction.Prepared prepared = null;
            try {
                long phase = System.nanoTime();
                McaSnapshot snapshot = MigrationGate.exclusive(() -> McaSnapshot.capture(target.folder, target.x, target.z));
                captureNanos.add(System.nanoTime() - phase);
                phase = System.nanoTime();
                prepared = MigrationTransaction.prepare(snapshot, new ChunkCodec.Options(128, true), true, cancel::get);
                prepareNanos.add(System.nanoTime() - phase);
                MigrationTransaction.Prepared ready = prepared;
                phase = System.nanoTime();
                var result = MigrationGate.exclusive(() -> MigrationTransaction.publish(ready, cancel::get));
                publishNanos.add(System.nanoTime() - phase);
                if (result != MigrationTransaction.PublishResult.COMMITTED) throw new IOException("Unexpected result: " + result);
            } finally {
                if (prepared != null && !MigrationGate.pending(MigrationTransaction.pendingPath(target.folder, target.x, target.z))) {
                    MigrationTransaction.discard(prepared);
                }
                active.decrementAndGet();
            }
        });
        long elapsed = System.nanoTime() - start, cpu = os.getProcessCpuTime() - cpuBefore;
        long before = 0, after = 0, chunks = 0;
        // 正式计时结束后逐块核验，不把完整比较的额外成本混入吞吐。
        for (Target target : targets) {
            McaSnapshot original = McaSnapshot.capture(target.original.getParent(), target.x, target.z);
            int[] timestamps = original.timestamps();
            McaSnapshot.Chunk[] slots = new McaSnapshot.Chunk[1024];
            for (var chunk : original.chunks()) slots[chunk.slot()] = chunk;
            Path path = target.folder.resolve("r." + target.x + "." + target.z + ".cqs");
            try (CompactRegion compact = CompactRegion.open(path, target.x, target.z, false)) {
                byte[] dictionaryBytes = compact.dictionary();
                RegionStateDictionary dictionary = dictionaryBytes == null ? null : RegionStateDictionary.fromCompressed(dictionaryBytes);
                for (int slot = 0; slot < 1024; slot++) {
                    if (compact.timestamp(slot) != timestamps[slot]) throw new IOException("Timestamp differs");
                    var record = compact.read(slot);
                    if (slots[slot] == null) { if (record != null) throw new IOException("Unexpected chunk"); }
                    else {
                        if (record == null || !inflate(slots[slot].payload(), slots[slot].codec()).equals(
                                MigrationTransaction.readNbt(record.payload(), record.codec(), dictionary))) throw new IOException("NBT differs");
                        chunks++;
                    }
                }
            }
            before += original.sourceBytes();
            after += Files.size(path) + Files.size(CompactRegion.ownerPath(path));
            try (var files = Files.walk(target.folder)) {
                if (files.anyMatch(p -> p.toString().endsWith(".mca") || p.toString().endsWith(".mcc") || p.toString().endsWith(".migrating"))) {
                    throw new IOException("Old files or journal retained");
                }
            }
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("label", label); report.put("transactionCodeSource", MigrationTransaction.class.getProtectionDomain().getCodeSource().getLocation().toString());
        report.put("workers", workers); report.put("peakActive", peak.get());
        report.put("regions", targets.size()); report.put("chunks", chunks);
        report.put("elapsedSeconds", elapsed / 1e9); report.put("processCpuSeconds", cpu / 1e9);
        report.put("averageCpuCores", cpu / (double) elapsed); report.put("chunksPerSecond", chunks * 1e9 / elapsed);
        report.put("sumCaptureSecondsIncludingWait", captureNanos.sum() / 1e9);
        report.put("sumPrepareSeconds", prepareNanos.sum() / 1e9);
        report.put("sumPublishSecondsIncludingWait", publishNanos.sum() / 1e9);
        report.put("sourceBytes", before); report.put("activeBytes", after); report.put("retainedOriginalBytes", 0);
        report.put("maxHeapBytes", Runtime.getRuntime().maxMemory()); report.put("javaVersion", System.getProperty("java.version"));
        report.put("verifiedFullNbtAndTimestamps", true);
        Files.writeString(output.resolve("report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
        System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(report));
    }
    private static CompoundTag inflate(byte[] payload, int codec) throws IOException {
        InputStream bytes = new ByteArrayInputStream(payload);
        InputStream decoded = switch (codec) { case 1 -> new GZIPInputStream(bytes); case 2 -> new InflaterInputStream(bytes); default -> bytes; };
        try (var input = new DataInputStream(new net.minecraft.util.FastBufferedInputStream(decoded))) {
            return NbtIo.read(input, NbtAccounter.unlimitedHeap());
        }
    }
}
