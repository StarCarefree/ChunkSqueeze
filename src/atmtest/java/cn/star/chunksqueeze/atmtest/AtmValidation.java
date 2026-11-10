package cn.star.chunksqueeze.atmtest;

import cn.star.chunksqueeze.codec.ChunkCodec;
import cn.star.chunksqueeze.codec.RegionStateDictionary;
import cn.star.chunksqueeze.codec.SectionCodec;
import cn.star.chunksqueeze.storage.CompactRegionStore;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

@Mod("chunksqueeze_atmtest")
public final class AtmValidation {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final boolean DEFERRED = Boolean.getBoolean("chunksqueeze.atmtest.background");
    private static final Path REPORT = Path.of(System.getProperty("chunksqueeze.atmtest.report",
            "chunksqueeze-atm-section-validation.json")).toAbsolutePath();
    private static final Path SNAPSHOT = Path.of(System.getProperty("chunksqueeze.atmtest.snapshot",
            "chunksqueeze-atm-section-snapshot.json")).toAbsolutePath();
    private static final int[][] FIRST = square(1020, 1020, 8);
    private static final int[][] SECOND = square(2048, 2048, 4);
    private static final int[][] ALL = java.util.stream.Stream.concat(Arrays.stream(FIRST), Arrays.stream(SECOND)).toArray(int[][]::new);
    private static final ChunkCodec.Options OPTIONS = new ChunkCodec.Options(0, false);
    private CompletableFuture<JsonObject> work;
    private JsonObject report;
    private JsonObject baseline;
    private final JsonObject current = new JsonObject();
    private final List<LevelChunk> loaded = new ArrayList<>();
    private boolean active;
    private int nextChunk;
    private int phase = 1;
    private int[][] coordinates;
    private int verifyCount;
    private boolean generationChecked;
    private JsonObject generation;
    private final List<Long> generationNanos = new ArrayList<>();

    public AtmValidation() {
        if (Boolean.getBoolean("chunksqueeze.atmtest")) {
            NeoForge.EVENT_BUS.addListener(this::started);
            NeoForge.EVENT_BUS.addListener(this::tick);
        }
    }

    private void started(ServerStartedEvent event) {
        if (active) return;
        active = true;
        try {
            if (Files.exists(SNAPSHOT)) {
                baseline = JSON.fromJson(Files.readString(SNAPSHOT), JsonObject.class);
                report = JSON.fromJson(Files.readString(REPORT), JsonObject.class);
                String status = report.get("status").getAsString();
                boolean finalCheck = Boolean.getBoolean("chunksqueeze.atmtest.finalcheck") && "passed".equals(status);
                check(finalCheck || "awaiting_restart".equals(status) || "awaiting_second_restart".equals(status), "Unexpected previous phase: " + status);
                phase = "awaiting_restart".equals(status) ? 2 : 3;
                if (finalCheck) report.addProperty("finalArtifactReloadVerified", false);
                check(baseline.size() == (phase == 2 ? FIRST.length : ALL.length), "Unexpected snapshot chunk count");
                work = CompletableFuture.completedFuture(report);
            } else {
                check(!Files.exists(REPORT), "Report already exists without snapshot");
                // 注册已冻结，后台只读取状态定义并执行纯 NBT 和独立存储操作。
                List<Block> blocks = new ArrayList<>();
                BuiltInRegistries.BLOCK.forEach(blocks::add);
                work = CompletableFuture.supplyAsync(() -> {
                    try { return validateStates(blocks); }
                    catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
                });
            }
            coordinates = phase == 1 ? FIRST : ALL;
            verifyCount = phase == 1 ? 0 : phase == 2 ? FIRST.length : ALL.length;
        } catch (Exception failure) {
            fail(event.getServer(), failure);
        }
    }

    private void tick(ServerTickEvent.Post event) {
        if (!active || work == null || !work.isDone()) return;
        MinecraftServer server = event.getServer();
        try {
            if (report == null) report = work.join();
            if (nextChunk < coordinates.length) {
                boolean generating = nextChunk >= verifyCount;
                if (generating && !generationChecked) beginGeneration(server);
                int[] coordinate = coordinates[nextChunk];
                JsonObject sample = null;
                if (generating) {
                    sample = regionPresence(server, coordinate);
                    sample.addProperty("chunk", key(coordinate));
                    generation.getAsJsonArray("chunks").add(sample);
                }
                // 每 tick 一个 FULL 区块；计时只包围获取调用，不计哈希、预检和启动。
                long start = System.nanoTime();
                LevelChunk chunk = server.overworld().getChunk(coordinate[0], coordinate[1]);
                long nanos = System.nanoTime() - start;
                loaded.add(chunk);
                if (generating) {
                    generationNanos.add(nanos);
                    sample.addProperty("fullWallNanos", nanos);
                } else {
                    String actual = hashChunk(chunk, server);
                    current.addProperty(key(coordinate), actual);
                    check(baseline.has(key(coordinate)) && actual.equals(baseline.get(key(coordinate)).getAsString()),
                            "World state hash differs after restart at chunk " + key(coordinate)
                                    + ": expected=" + baseline.get(key(coordinate)) + ", actual=" + actual);
                }
                nextChunk++;
                return;
            }
            finishGeneration();
            report.addProperty("phase", phase);
            report.addProperty("restartVerifiedChunkCount", verifyCount);
            if (phase < 3) {
                // 保存前在同一 tick 重算所有区块，随后停止，避免快照后继续模拟。
                for (int i = 0; i < loaded.size(); i++) {
                    current.addProperty(key(coordinates[i]), hashChunk(loaded.get(i), server));
                }
                writeJson(SNAPSHOT, current);
                report.addProperty("status", phase == 1 ? "awaiting_restart" : "awaiting_second_restart");
            } else {
                report.addProperty("status", "passed");
                if (Boolean.getBoolean("chunksqueeze.atmtest.finalcheck")) report.addProperty("finalArtifactReloadVerified", true);
            }
            report.addProperty("worldReloadVerified", phase == 3);
            report.addProperty("worldChunkCount", coordinates.length);
            report.add("worldStateHashes", current.deepCopy());
            report.addProperty("worldHashScope", "Every block position from minimum to maximum build height; full registry name and sorted properties");
            report.addProperty("coverage", "All registered block states through synthetic NBT storage; 80 new overworld FULL chunks across three boots; not gameplay coverage or a baseline performance comparison");
            writeJson(REPORT, report);
            active = false;
            server.halt(false);
        } catch (Exception failure) {
            fail(server, failure);
        }
    }

    private void beginGeneration(MinecraftServer server) throws IOException {
        generation = new JsonObject();
        report.add("generationPhase" + phase, generation);
        generation.addProperty("seed", server.overworld().getSeed());
        generation.addProperty("regionDirectory", server.getWorldPath(LevelResource.ROOT).resolve("region").toAbsolutePath().toString());
        generation.addProperty("timingScope", "Synchronous getChunk(x,z) to FULL on server thread, one target per tick; includes incidental neighbor generation; excludes startup and hashing");
        JsonArray regions = new JsonArray();
        generation.add("initialRegions", regions);
        generation.add("chunks", new JsonArray());
        Set<String> seen = new LinkedHashSet<>();
        int existing = 0;
        for (int[] coordinate : phase == 1 ? FIRST : SECOND) {
            JsonObject presence = regionPresence(server, coordinate);
            if (seen.add(presence.get("region").getAsString())) {
                regions.add(presence);
                if (presence.get("mcaExists").getAsBoolean() || presence.get("cqsExists").getAsBoolean()
                        || presence.get("ownerExists").getAsBoolean()) existing++;
            }
        }
        generation.addProperty("initialExistingRegionCount", existing);
        check(existing == 0, "New generation target regions already exist: " + regions);
        generationChecked = true;
    }

    private static JsonObject regionPresence(MinecraftServer server, int[] coordinate) {
        Path directory = server.getWorldPath(LevelResource.ROOT).resolve("region");
        String name = "r." + (coordinate[0] >> 5) + "." + (coordinate[1] >> 5);
        JsonObject result = new JsonObject();
        result.addProperty("region", name);
        result.addProperty("mcaExists", Files.exists(directory.resolve(name + ".mca")));
        result.addProperty("cqsExists", Files.exists(directory.resolve(name + ".cqs")));
        result.addProperty("ownerExists", Files.exists(directory.resolve(name + ".cqs.owner")));
        return result;
    }

    private void finishGeneration() {
        if (generation == null || generationNanos.isEmpty()) return;
        long[] sorted = generationNanos.stream().mapToLong(Long::longValue).sorted().toArray();
        long sum = Arrays.stream(sorted).sum();
        generation.addProperty("completedChunkCount", sorted.length);
        generation.addProperty("sumWallNanos", sum);
        generation.addProperty("generationCallSeconds", sum / 1_000_000_000.0);
        generation.addProperty("p50WallNanos", sorted[(int) Math.ceil(sorted.length * 0.50) - 1]);
        generation.addProperty("p95WallNanos", sorted[(int) Math.ceil(sorted.length * 0.95) - 1]);
        generation.addProperty("maxWallNanos", sorted[sorted.length - 1]);
    }

    private static int[][] square(int x, int z, int side) {
        int[][] result = new int[side * side][2];
        for (int i = 0; i < result.length; i++) result[i] = new int[]{x + i % side, z + i / side};
        return result;
    }

    private static JsonObject validateStates(List<Block> blocks) throws Exception {
        Path directory = Files.createTempDirectory(REPORT.getParent(), "chunksqueeze-atm-section-store-");
        Map<String, long[]> namespaces = new TreeMap<>();
        List<CompoundTag> batch = new ArrayList<>(256);
        Counters counters = new Counters();
        try (counters) {
            for (Block block : blocks) {
                String namespace = BuiltInRegistries.BLOCK.getKey(block).getNamespace();
                long[] counts = namespaces.computeIfAbsent(namespace, ignored -> new long[2]);
                counts[0]++;
                for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                    batch.add(NbtUtils.writeBlockState(state));
                    counts[1]++;
                    counters.states++;
                    if (batch.size() == 256) {
                        validateBatch(batch, directory, counters);
                        batch.clear();
                    }
                }
            }
            if (!batch.isEmpty()) validateBatch(batch, directory, counters);
            finishBatchGroup(directory, counters);
        }
        check(counters.shared > 0 && counters.persistedShared > 0, "No full-section references were exercised");
        JsonObject result = new JsonObject();
        result.addProperty("exactAccountingFastPath", RegionStateDictionary.exactAccountingSupported());
        result.addProperty("deferredPersistenceVerified", DEFERRED);
        result.addProperty("blockCount", blocks.size());
        result.addProperty("stateCount", counters.states);
        result.addProperty("modNamespaceCount", namespaces.size());
        JsonObject perMod = new JsonObject();
        namespaces.forEach((namespace, counts) -> {
            JsonObject count = new JsonObject();
            count.addProperty("blocks", counts[0]);
            count.addProperty("states", counts[1]);
            perMod.add(namespace, count);
        });
        result.add("perModNamespace", perMod);
        result.addProperty("syntheticChunkCount", counters.chunks);
        result.addProperty("encodedChunkCount", counters.encoded);
        result.addProperty("sectionReferenceChunkCount", counters.shared);
        result.addProperty("persistedSectionReferenceChunkCount", counters.persistedShared);
        result.addProperty("stateSharedChunkCount", counters.stateShared);
        result.addProperty("persistedStateSharedChunkCount", counters.persistedStateShared);
        result.addProperty("independentChunkRewriteBatchCount", counters.rewriteChecks);
        result.addProperty("syntheticBatchCount", counters.batches);
        result.addProperty("reopenBatchSize", 32);
        result.addProperty("reopenedGroupCount", counters.reopenedGroups);
        result.addProperty("sectionReferenceKey", SectionCodec.KEY);
        result.addProperty("fullNbtEqualityVerified", true);
        result.addProperty("dictionaryCompressedReopenVerified", true);
        result.addProperty("storeReopenVerified", true);
        result.addProperty("independentChunkRewriteVerified", true);
        result.addProperty("storageDirectory", directory.toString());
        result.addProperty("status", "state_validation_passed");
        return result;
    }

    private static void validateBatch(List<CompoundTag> palette, Path directory, Counters counters) throws Exception {
        int batch = counters.batches;
        boolean rewriteCheck = batch % 32 == 0;
        // 每区域最多 8192 个新状态；每区域首批额外检查第三份引用和独立修改。
        if (rewriteCheck) {
            counters.dictionary = RegionStateDictionary.create();
            if (DEFERRED) {
                check(cn.star.chunksqueeze.storage.BackgroundReencoder.current() != null, "Background service is missing");
                counters.store = new CompactRegionStore(directory, cn.star.chunksqueeze.storage.BackgroundReencoder.current());
            } else counters.store = new CompactRegionStore(directory);
        }
        int x = (batch / 32) * 32 + batch % 32;
        CompoundTag[] originals = new CompoundTag[rewriteCheck ? 3 : 2];
        CompoundTag[] encodedTags = new CompoundTag[originals.length];
        for (int z = 0; z < originals.length; z++) {
            CompoundTag original = fixture(palette, x, z);
            originals[z] = original;
            CompoundTag untouched = original.copy();
            ChunkCodec.Result encoded = SectionCodec.encode(original, OPTIONS, counters.dictionary);
            encodedTags[z] = encoded.tag();
            if (encoded.optimized()) counters.encoded++;
            if (shared(encoded.tag())) counters.shared++;
            if (stateShared(encoded.tag())) counters.stateShared++;
            check(untouched.equals(original), "Encoder mutated its input at batch " + batch);
            check(original.equals(SectionCodec.decode(encoded.tag(), counters.dictionary)), "Full NBT round trip differs at batch " + batch);
            ChunkPos pos = new ChunkPos(x, z);
            if (DEFERRED) counters.store.writeDeferred(pos, original, OPTIONS);
            else {
                ChunkCodec.Result written = counters.store.writeEncoded(pos, original, OPTIONS);
                if (shared(written.tag())) counters.persistedShared++;
                if (stateShared(written.tag())) counters.persistedStateShared++;
            }
            check(original.equals(readStored(counters.store, pos)), "Immediate persisted NBT differs at " + pos);
        }
        if (rewriteCheck) {
            CompoundTag changed = originals[0].copy();
            markRewrite(changed);
            if (DEFERRED) counters.store.writeDeferred(new ChunkPos(x, 0), changed, OPTIONS);
            else counters.store.writeEncoded(new ChunkPos(x, 0), changed, OPTIONS);
            for (int z = 0; z < originals.length; z++) {
                check((z == 0 ? changed : originals[z]).equals(readStored(counters.store, new ChunkPos(x, z))),
                        "Chunk rewrite affected shared section at " + x + "," + z);
            }
            counters.rewriteChecks++;
        }
        // 只保留 palette 和编码结果；重开时重建原始完整 NBT，避免整区域原始数据常驻。
        counters.pending.add(new PendingBatch(List.copyOf(palette), x, encodedTags, rewriteCheck));
        counters.batches++;
        counters.chunks += originals.length;
        if (counters.pending.size() == 32) finishBatchGroup(directory, counters);
        if ((batch + 1) % 512 == 0) System.out.println("[ChunkSqueeze ATM] section batches=" + (batch + 1)
                + " states=" + counters.states + " chunks=" + counters.chunks);
    }

    private static void finishBatchGroup(Path directory, Counters counters) throws Exception {
        if (counters.pending.isEmpty()) return;
        RegionStateDictionary reopenedDictionary = RegionStateDictionary.fromCompressed(counters.dictionary.compressed());
        for (PendingBatch pending : counters.pending) {
            for (int z = 0; z < pending.encodedTags().length; z++) {
                CompoundTag original = fixture(pending.palette(), pending.x(), z);
                check(original.equals(SectionCodec.decode(pending.encodedTags()[z], reopenedDictionary)),
                        "Reopened dictionary round trip differs at " + pending.x() + "," + z);
            }
        }
        if (DEFERRED) check(cn.star.chunksqueeze.storage.BackgroundReencoder.awaitGlobalIdle(120000),
                "Deferred synthetic group did not drain");
        counters.store.flush();
        counters.close();
        if (DEFERRED) {
            int rx = counters.pending.getFirst().x() >> 5;
            try (var region = cn.star.chunksqueeze.storage.CompactRegion.open(directory.resolve("r." + rx + ".0.cqs"), rx, 0, false)) {
                for (PendingBatch pending : counters.pending) {
                    for (int z = 0; z < pending.encodedTags().length; z++) {
                        var record = region.read((pending.x() & 31) + z * 32);
                        check(record != null, "Missing deferred synthetic record");
                        try (var input = new java.io.DataInputStream(new java.util.zip.InflaterInputStream(
                                new java.io.ByteArrayInputStream(record.payload())))) {
                            CompoundTag stored = net.minecraft.nbt.NbtIo.read(input, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
                            if (shared(stored)) counters.persistedShared++;
                            if (stateShared(stored)) counters.persistedStateShared++;
                        }
                    }
                }
            }
        }
        try (CompactRegionStore reopened = new CompactRegionStore(directory)) {
            for (PendingBatch pending : counters.pending) {
                for (int z = 0; z < pending.encodedTags().length; z++) {
                    CompoundTag expected = fixture(pending.palette(), pending.x(), z);
                    if (pending.rewriteCheck() && z == 0) markRewrite(expected);
                    check(expected.equals(readStored(reopened, new ChunkPos(pending.x(), z))),
                            "Reopened store NBT differs at " + pending.x() + "," + z);
                }
            }
        }
        counters.pending.clear();
        counters.dictionary = null;
        counters.reopenedGroups++;
    }

    private static void markRewrite(CompoundTag tag) {
        tag.getList("sections", 10).getCompound(0).putString("atmtest:rewrite", "仅修改第一个区块");
    }

    private static CompoundTag readStored(CompactRegionStore store, ChunkPos pos) throws IOException {
        CompoundTag tag = store.read(pos);
        return tag == null ? null : SectionCodec.decode(tag, null);
    }

    private static CompoundTag fixture(List<CompoundTag> palette, int x, int z) {
        CompoundTag chunk = new CompoundTag();
        chunk.putString("Status", "minecraft:full");
        chunk.putInt("DataVersion", 3955);
        chunk.putInt("xPos", x);
        chunk.putInt("zPos", z);
        chunk.putString("atmtest:sentinel", "完整状态与扩展字段");
        ListTag sections = new ListTag();
        int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
        int perLong = 64 / bits;
        long[] packed = new long[(4096 + perLong - 1) / perLong];
        for (int i = 0; i < 4096; i++) packed[i / perLong] |= (long) (i % palette.size()) << ((i % perLong) * bits);
        for (int y = 0; y < 16; y++) {
            CompoundTag section = new CompoundTag();
            section.putByte("Y", (byte) y);
            CompoundTag states = new CompoundTag();
            ListTag entries = new ListTag();
            palette.forEach(state -> entries.add(state.copy()));
            states.put("palette", entries);
            if (palette.size() > 1) states.putLongArray("data", packed.clone());
            section.put("block_states", states);
            CompoundTag biomes = new CompoundTag();
            ListTag biomePalette = new ListTag();
            biomePalette.add(net.minecraft.nbt.StringTag.valueOf("minecraft:plains"));
            biomes.put("palette", biomePalette);
            section.put("biomes", biomes);
            byte[] light = new byte[2048];
            Arrays.fill(light, (byte) (y * 17));
            section.putByteArray("SkyLight", light);
            section.putByteArray("BlockLight", new byte[2048]);
            section.putLong("atmtest:section", y);
            sections.add(section);
        }
        chunk.put("sections", sections);
        return chunk;
    }

    private static String hashChunk(LevelChunk chunk, MinecraftServer server) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        Map<BlockState, byte[]> canonicalStates = new IdentityHashMap<>();
        for (int y = server.overworld().getMinBuildHeight(); y < server.overworld().getMaxBuildHeight(); y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    position.set(chunk.getPos().getMinBlockX() + x, y, chunk.getPos().getMinBlockZ() + z);
                    byte[] canonical = canonicalStates.computeIfAbsent(chunk.getBlockState(position), state -> {
                        CompoundTag tag = NbtUtils.writeBlockState(state);
                        var bytes = new java.io.ByteArrayOutputStream();
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

    private static void hashString(java.io.ByteArrayOutputStream out, String value) {
        int length = value.length();
        for (int shift = 24; shift >= 0; shift -= 8) out.write(length >>> shift);
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            out.write(c >>> 8);
            out.write(c);
        }
    }

    private static boolean shared(CompoundTag tag) { return tag.contains(SectionCodec.KEY); }

    private static boolean stateShared(CompoundTag tag) {
        return tag.contains(ChunkCodec.KEY) && tag.getCompound(ChunkCodec.KEY).getInt("version") == 2;
    }

    private static String key(int[] coordinate) { return coordinate[0] + "," + coordinate[1]; }

    private static void check(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }

    private static void writeJson(Path path, JsonObject data) throws IOException {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, JSON.toJson(data), StandardCharsets.UTF_8);
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
    }

    private void fail(MinecraftServer server, Exception failure) {
        if (report == null) report = new JsonObject();
        finishGeneration();
        report.addProperty("phase", phase);
        report.addProperty("completedWorldChunkCount", nextChunk);
        report.add("observedWorldStateHashes", current.deepCopy());
        report.addProperty("status", "failed");
        report.addProperty("failure", failure.toString());
        if (failure.getCause() != null) report.addProperty("cause", failure.getCause().toString());
        failure.printStackTrace();
        try { writeJson(REPORT, report); }
        catch (IOException writeFailure) { writeFailure.printStackTrace(); }
        active = false;
        server.halt(false);
    }

    private record PendingBatch(List<CompoundTag> palette, int x, CompoundTag[] encodedTags, boolean rewriteCheck) {}

    private static final class Counters implements AutoCloseable {
        final List<PendingBatch> pending = new ArrayList<>(32);
        CompactRegionStore store;
        int reopenedGroups;
        long states;
        int chunks;
        int batches;
        int rewriteChecks;
        int encoded;
        int shared;
        int persistedShared;
        int stateShared;
        int persistedStateShared;
        RegionStateDictionary dictionary;

        @Override
        public void close() throws IOException {
            CompactRegionStore closing = store;
            store = null;
            if (closing != null) closing.close();
        }
    }
}
