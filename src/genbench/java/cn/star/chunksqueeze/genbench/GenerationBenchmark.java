package cn.star.chunksqueeze.genbench;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

@Mod("chunksqueeze_genbench")
public final class GenerationBenchmark {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String PREFIX = "chunksqueeze.genbench.";
    private static final long SEED = 512060L;
    private static final int TARGET_COUNT = 256;
    private static final int WARMUP_COUNT = 8;
    private final JsonObject report = new JsonObject();
    private final JsonArray samples = new JsonArray();
    private final List<LevelChunk> chunks = new ArrayList<>(TARGET_COUNT);
    private final long[] wallNanos = new long[TARGET_COUNT];
    private final List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
    private Path output;
    private boolean active;
    private boolean memoryPending;
    private int warmup;
    private int completed;
    private long measureStart;
    private long cpuStart;
    private long[][] gcStart;

    public GenerationBenchmark() {
        NeoForge.EVENT_BUS.addListener(this::started);
        NeoForge.EVENT_BUS.addListener(this::tick);
    }

    private void started(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        try {
            String configuredOutput = System.getProperty(PREFIX + "output");
            require(configuredOutput != null && !configuredOutput.isBlank(), "Missing -D" + PREFIX + "output");
            output = Path.of(configuredOutput);
            require(output.isAbsolute(), "Output must be an absolute path: " + output);
            output = output.normalize();
            report.addProperty("status", "running");
            report.addProperty("startedAt", Instant.now().toString());
            report.addProperty("label", System.getProperty(PREFIX + "label", "unspecified"));
            report.addProperty("fork", System.getProperty(PREFIX + "fork", "0"));
            boolean loaded = ModList.get().isLoaded("chunksqueeze");
            report.addProperty("chunksqueezeLoaded", loaded);
            String expected = System.getProperty(PREFIX + "expected");
            require("true".equals(expected) || "false".equals(expected), "Expected must be explicitly true or false: -D" + PREFIX + "expected");
            report.addProperty("expectedChunksqueezeLoaded", Boolean.parseBoolean(expected));
            metadata(server);
            require(loaded == Boolean.parseBoolean(expected), "ChunkSqueeze presence differs from expected=" + expected);
            require(server.overworld().getSeed() == SEED, "Expected world seed " + SEED);
            report.add("initialRegions", checkFreshRegions(server));
            report.add("chunks", samples);
            active = true;
        } catch (Throwable failure) {
            fail(server, failure);
        }
    }

    private void metadata(MinecraftServer server) {
        report.addProperty("seed", server.overworld().getSeed());
        report.addProperty("worldPath", server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().toString());
        report.addProperty("targetChunkCount", TARGET_COUNT);
        report.addProperty("targetCoordinates", "x=1020..1035, z=1020..1035; x changes fastest");
        report.addProperty("warmupChunkCount", WARMUP_COUNT);
        report.addProperty("warmupCoordinates", "x=3000..3007, z=3000");
        report.addProperty("timingScope", "One synchronous overworld getChunk(x,z) to FULL per server tick; includes incidental neighbor generation. Phase includes intervening ticks and waits; excludes startup, warmup, preflight, hashing and explicit final save.");
        report.addProperty("worldHashScope", "SHA-256 of every block state, y/z/x order, full build height; registry name and sorted properties encoded as length-prefixed UTF-16BE strings with a zero byte after each state. No entities, block entities, biomes or light.");
        report.addProperty("worldHashComparison", "Reported only; game rules unchanged, simulation and mod behavior may cause differences across boots.");
        report.addProperty("syncChunkWrites", "Record externally from server.properties sync-chunk-writes");
        JsonArray mods = new JsonArray();
        ModList.get().getMods().stream().sorted(Comparator.comparing(mod -> mod.getModId())).forEach(mod -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", mod.getModId());
            entry.addProperty("version", mod.getVersion().toString());
            mods.add(entry);
        });
        report.add("mods", mods);
        JsonObject jvm = new JsonObject();
        jvm.addProperty("version", System.getProperty("java.version"));
        jvm.addProperty("vendor", System.getProperty("java.vendor"));
        jvm.addProperty("vmName", System.getProperty("java.vm.name"));
        jvm.addProperty("vmVersion", System.getProperty("java.vm.version"));
        jvm.addProperty("maxHeapBytes", Runtime.getRuntime().maxMemory());
        jvm.addProperty("initialHeapBytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getInit());
        jvm.addProperty("committedHeapBytesAtStart", Runtime.getRuntime().totalMemory());
        jvm.add("inputArguments", JSON.toJsonTree(ManagementFactory.getRuntimeMXBean().getInputArguments()));
        jvm.addProperty("availableProcessors", Runtime.getRuntime().availableProcessors());
        jvm.addProperty("cpuIdentifier", System.getenv("PROCESSOR_IDENTIFIER"));
        jvm.addProperty("osName", System.getProperty("os.name"));
        jvm.addProperty("osVersion", System.getProperty("os.version"));
        jvm.addProperty("osArch", System.getProperty("os.arch"));
        jvm.addProperty("pid", ProcessHandle.current().pid());
        jvm.addProperty("processStartedAt", Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime()).toString());
        report.add("jvm", jvm);
    }

    private void tick(ServerTickEvent.Post event) {
        if (memoryPending) {
            memoryPending = false;
            try {
                MemoryProbe.run();
                event.getServer().halt(false);
            } catch (Throwable failure) {
                fail(event.getServer(), failure);
            }
            return;
        }
        if (!active) return;
        MinecraftServer server = event.getServer();
        try {
            if (warmup < WARMUP_COUNT) {
                server.overworld().getChunk(3000 + warmup, 3000);
                warmup++;
                return;
            }
            if (completed == 0) {
                report.add("preMeasureRegions", checkFreshRegions(server));
                report.addProperty("measureStartedAt", Instant.now().toString());
                gcStart = gcSnapshot();
                cpuStart = processCpuNanos();
                measureStart = System.nanoTime();
            }
            int x = 1020 + completed % 16;
            int z = 1020 + completed / 16;
            long start = System.nanoTime();
            LevelChunk chunk = server.overworld().getChunk(x, z);
            long end = System.nanoTime();
            wallNanos[completed] = end - start;
            chunks.add(chunk);
            completed++;
            if (completed == TARGET_COUNT) finishMeasure(end);
            JsonObject sample = new JsonObject();
            sample.addProperty("x", x);
            sample.addProperty("z", z);
            sample.addProperty("wallNanos", end - start);
            samples.add(sample);
            if (completed % 64 == 0) System.out.println("[ChunkSqueeze genbench] FULL chunks=" + completed + "/" + TARGET_COUNT);
            if (completed == TARGET_COUNT) finish(server);
        } catch (Throwable failure) {
            fail(server, failure);
        }
    }

    private void finishMeasure(long end) {
        long cpuEnd = processCpuNanos();
        long[][] gcEnd = gcSnapshot();
        report.addProperty("measureFinishedAt", Instant.now().toString());
        JsonObject measure = new JsonObject();
        measure.addProperty("wallNanos", end - measureStart);
        measure.addProperty("processCpuNanos", cpuStart < 0 || cpuEnd < 0 ? -1 : cpuEnd - cpuStart);
        measure.addProperty("processCpuSupported", cpuStart >= 0 && cpuEnd >= 0);
        JsonArray gc = new JsonArray();
        for (int i = 0; i < collectors.size(); i++) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", collectors.get(i).getName());
            entry.addProperty("collectionCount", delta(gcStart[i][0], gcEnd[i][0]));
            entry.addProperty("collectionTimeMillis", delta(gcStart[i][1], gcEnd[i][1]));
            gc.add(entry);
        }
        measure.add("gc", gc);
        report.add("measure", measure);
        long[] sorted = wallNanos.clone();
        Arrays.sort(sorted);
        JsonObject calls = new JsonObject();
        calls.addProperty("count", completed);
        calls.addProperty("sumWallNanos", Arrays.stream(sorted).sum());
        calls.addProperty("p50WallNanos", sorted[(int) Math.ceil(TARGET_COUNT * 0.50) - 1]);
        calls.addProperty("p95WallNanos", sorted[(int) Math.ceil(TARGET_COUNT * 0.95) - 1]);
        calls.addProperty("maxWallNanos", sorted[TARGET_COUNT - 1]);
        report.add("generationCalls", calls);
    }

    private void finish(MinecraftServer server) throws Exception {
        // 生成后立即保存，避免哈希期间的异步 I/O 提前消耗待保存工作。
        report.addProperty("saveStartedAt", Instant.now().toString());
        long start = System.nanoTime();
        boolean flush = Boolean.getBoolean(PREFIX + "flush");
        report.addProperty("saveWaitsForIo", flush);
        boolean saved = server.saveEverything(true, flush, true);
        long saveNanos = System.nanoTime() - start;
        report.addProperty("saveWallNanos", saveNanos);
        report.addProperty("saveFinishedAt", Instant.now().toString());
        report.addProperty("saveReturned", saved);
        require(saved, "saveEverything returned false");
        if (Boolean.getBoolean(PREFIX + "awaitReencoding")) {
            Class<?> background = Class.forName("cn.star.chunksqueeze.storage.BackgroundReencoder");
            report.addProperty("backgroundStatusAtSaveReturn", (String) background.getMethod("status").invoke(null));
            report.addProperty("reencodingStartedAt", Instant.now().toString());
            long cpu = processCpuNanos(), began = System.nanoTime();
            boolean idle = (boolean) background.getMethod("awaitGlobalIdle", long.class).invoke(null, 300000L);
            require(idle, "Background reencoding timed out");
            report.addProperty("reencodingDrainNanos", System.nanoTime() - began);
            report.addProperty("reencodingProcessCpuNanos", processCpuNanos() - cpu);
            report.addProperty("reencodingFinishedAt", Instant.now().toString());
            long flushStart = System.nanoTime();
            require(server.saveEverything(true, true, true), "Post-reencoding flush returned false");
            report.addProperty("postReencodingFlushNanos", System.nanoTime() - flushStart);
            long postFlushStart = System.nanoTime();
            require((boolean) background.getMethod("awaitGlobalIdle", long.class).invoke(null, 300000L),
                    "Post-flush reencoding timed out");
            report.addProperty("postFlushReencodingDrainNanos", System.nanoTime() - postFlushStart);
            report.addProperty("backgroundFinishedAt", Instant.now().toString());
            report.addProperty("backgroundStatusAfterDrain", (String) background.getMethod("status").invoke(null));
        }
        JsonObject hashes = new JsonObject();
        Map<BlockState, byte[]> canonicalStates = new IdentityHashMap<>();
        for (LevelChunk chunk : chunks) {
            hashes.addProperty(chunk.getPos().x + "," + chunk.getPos().z, hashChunk(chunk, server, canonicalStates));
        }
        report.add("worldStateHashes", hashes);
        report.addProperty("hashedChunkCount", hashes.size());
        report.addProperty("completedWarmupChunkCount", warmup);
        report.addProperty("completedChunkCount", completed);
        report.addProperty("finishedAt", Instant.now().toString());
        report.addProperty("status", "passed");
        writeReport();
        active = false;
        memoryPending = Boolean.getBoolean(PREFIX + "memory");
        if (!memoryPending) server.halt(false);
    }

    private static JsonArray checkFreshRegions(MinecraftServer server) throws IOException {
        Path directory = server.getWorldPath(LevelResource.ROOT).resolve("region");
        JsonArray regions = new JsonArray();
        for (int z = 31; z <= 32; z++) {
            for (int x = 31; x <= 32; x++) {
                String name = "r." + x + "." + z;
                JsonObject region = new JsonObject();
                region.addProperty("region", name);
                for (String extension : List.of(".mca", ".cqs", ".cqs.owner")) {
                    Path file = directory.resolve(name + extension);
                    boolean absent = Files.notExists(file, LinkOption.NOFOLLOW_LINKS);
                    region.addProperty(extension.substring(1) + "Absent", absent);
                    require(absent, "Target region already exists or cannot be inspected: " + file);
                }
                regions.add(region);
            }
        }
        return regions;
    }

    private long[][] gcSnapshot() {
        long[][] result = new long[collectors.size()][2];
        for (int i = 0; i < collectors.size(); i++) {
            result[i][0] = collectors.get(i).getCollectionCount();
            result[i][1] = collectors.get(i).getCollectionTime();
        }
        return result;
    }

    private static long processCpuNanos() {
        return ProcessHandle.current().info().totalCpuDuration().map(duration -> duration.toNanos()).orElse(-1L);
    }

    private static long delta(long before, long after) {
        return before < 0 || after < 0 ? -1 : after - before;
    }

    private static String hashChunk(LevelChunk chunk, MinecraftServer server, Map<BlockState, byte[]> canonicalStates) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        for (int y = server.overworld().getMinBuildHeight(); y < server.overworld().getMaxBuildHeight(); y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    position.set(chunk.getPos().getMinBlockX() + x, y, chunk.getPos().getMinBlockZ() + z);
                    byte[] canonical = canonicalStates.computeIfAbsent(chunk.getBlockState(position), state -> {
                        CompoundTag tag = NbtUtils.writeBlockState(state);
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        hashString(bytes, tag.getString("Name"));
                        CompoundTag properties = tag.getCompound("Properties");
                        hashString(bytes, Integer.toString(properties.size()));
                        properties.getAllKeys().stream().sorted().forEach(name -> {
                            hashString(bytes, name);
                            hashString(bytes, properties.getString(name));
                        });
                        return bytes.toByteArray();
                    });
                    digest.update(canonical);
                    digest.update((byte) 0);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void hashString(ByteArrayOutputStream out, String value) {
        int length = value.length();
        for (int shift = 24; shift >= 0; shift -= 8) out.write(length >>> shift);
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            out.write(c >>> 8);
            out.write(c);
        }
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }

    private void writeReport() throws IOException {
        require(output != null && output.isAbsolute(), "No valid absolute output path configured");
        Files.createDirectories(output.getParent());
        Path temporary = output.resolveSibling(output.getFileName() + ".tmp");
        Files.writeString(temporary, JSON.toJson(report), StandardCharsets.UTF_8);
        Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
    }

    private void fail(MinecraftServer server, Throwable failure) {
        active = false;
        report.addProperty("status", "failed");
        report.addProperty("finishedAt", Instant.now().toString());
        report.addProperty("completedWarmupChunkCount", warmup);
        report.addProperty("completedChunkCount", completed);
        report.addProperty("failure", failure.toString());
        StringWriter stack = new StringWriter();
        failure.printStackTrace(new PrintWriter(stack));
        report.addProperty("stackTrace", stack.toString());
        failure.printStackTrace();
        try {
            writeReport();
        } catch (Throwable writeFailure) {
            writeFailure.printStackTrace();
            System.err.println(JSON.toJson(report));
        } finally {
            server.halt(false);
        }
    }
}
