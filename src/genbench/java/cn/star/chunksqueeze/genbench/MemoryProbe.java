package cn.star.chunksqueeze.genbench;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// 仅供隔离测试探针使用；不触发显式 GC，采样窗口暂停世界 tick。
final class MemoryProbe {
    private static final String PREFIX = "chunksqueeze.genbench.";
    private static final List<AutoCloseable> EXTRA = new ArrayList<>();
    private static final JsonObject REPORT = new JsonObject();
    private static final JsonArray SAMPLES = new JsonArray();

    static void run() throws Exception {
        Path output = Path.of(System.getProperty(PREFIX + "memoryOutput"));
        boolean compact = Boolean.getBoolean(PREFIX + "expected");
        REPORT.addProperty("pid", ProcessHandle.current().pid());
        REPORT.addProperty("compact", compact);
        REPORT.addProperty("explicitGcRequested", false);
        REPORT.addProperty("heapScope", "Observed heap usage includes uncollected garbage; not a retained-size measurement");
        REPORT.add("samples", SAMPLES);
        try {
            samplePhase(output, "generated", compact);
            Path inputs = Path.of(System.getProperty(PREFIX + "memoryInputs"));
            for (String world : new String[]{"overworld", "nether", "twilight"}) {
                Path folder = inputs.resolve(world).resolve(compact ? "cqs" : "mca");
                Object store;
                Method read;
                if (compact) {
                    Class<?> type = Class.forName("cn.star.chunksqueeze.storage.CompactRegionStore");
                    store = type.getConstructor(Path.class).newInstance(folder);
                    read = type.getMethod("read", ChunkPos.class);
                } else {
                    var constructor = RegionFileStorage.class.getDeclaredConstructor(RegionStorageInfo.class, Path.class, boolean.class);
                    constructor.setAccessible(true);
                    store = constructor.newInstance(new RegionStorageInfo("memory-probe", Level.OVERWORLD, "chunk"), folder, false);
                    read = RegionFileStorage.class.getMethod("read", ChunkPos.class);
                }
                EXTRA.add((AutoCloseable) store);
                String suffix = compact ? ".cqs" : ".mca";
                List<Path> files;
                try (var paths = Files.list(folder)) { files = paths.filter(p -> p.toString().endsWith(suffix)).sorted().toList(); }
                if (files.size() != 20) throw new IllegalStateException("Expected 20 regions: " + folder);
                for (Path file : files) {
                    String[] parts = file.getFileName().toString().split("\\.");
                    ChunkPos pos = new ChunkPos(Integer.parseInt(parts[1]) * 32, Integer.parseInt(parts[2]) * 32);
                    if (!(read.invoke(store, pos) instanceof CompoundTag)) throw new IllegalStateException("Missing sample chunk " + file);
                }
            }
            REPORT.addProperty("extraRegionsOpened", 60);
            samplePhase(output, "warm60", compact);
        } finally {
            for (AutoCloseable store : EXTRA) store.close();
            EXTRA.clear();
        }
        samplePhase(output, "closed60", compact);
        REPORT.addProperty("status", "passed");
        write(output);
    }

    private static void samplePhase(Path output, String phase, boolean compact) throws Exception {
        REPORT.addProperty("status", "sampling");
        REPORT.addProperty("phase", phase);
        System.out.println("[ChunkSqueeze memory] " + phase);
        for (int i = 0; i < 5; i++) {
            var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            var nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
            JsonObject sample = new JsonObject();
            sample.addProperty("phase", phase);
            sample.addProperty("at", Instant.now().toString());
            sample.addProperty("heapUsedBytes", heap.getUsed());
            sample.addProperty("heapCommittedBytes", heap.getCommitted());
            sample.addProperty("heapMaxBytes", heap.getMax());
            sample.addProperty("nonHeapUsedBytes", nonHeap.getUsed());
            sample.addProperty("nonHeapCommittedBytes", nonHeap.getCommitted());
            if (compact) {
                Class<?> type = Class.forName("cn.star.chunksqueeze.storage.CompactRegionStore");
                var field = type.getDeclaredField("GLOBAL_DICTIONARIES");
                field.setAccessible(true);
                Object cache = field.get(null);
                synchronized (cache) {
                    for (String name : new String[]{"bytes", "size"}) {
                        Method method = cache.getClass().getDeclaredMethod(name);
                        method.setAccessible(true);
                        sample.addProperty(name.equals("bytes") ? "dictionaryEstimatedBytes" : "cachedRegionCount", (Number) method.invoke(cache));
                    }
                    var budget = cache.getClass().getDeclaredField("budget");
                    budget.setAccessible(true);
                    sample.addProperty("dictionaryBudgetBytes", budget.getLong(cache));
                }
            }
            JsonArray gc = new JsonArray();
            for (var collector : ManagementFactory.getGarbageCollectorMXBeans()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("name", collector.getName());
                entry.addProperty("count", collector.getCollectionCount());
                entry.addProperty("millis", collector.getCollectionTime());
                gc.add(entry);
            }
            sample.add("gc", gc);
            SAMPLES.add(sample);
            write(output);
            Thread.sleep(1000);
        }
    }

    private static void write(Path output) throws Exception {
        Path temporary = output.resolveSibling(output.getFileName() + ".tmp");
        Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(REPORT), StandardCharsets.UTF_8);
        Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
    }
}
