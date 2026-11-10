package cn.star.chunksqueeze.storage;

import cn.star.chunksqueeze.codec.ChunkCodec;
import cn.star.chunksqueeze.codec.SectionCodec;
import cn.star.chunksqueeze.migration.McaSnapshot;
import com.google.gson.GsonBuilder;
import net.minecraft.nbt.*;
import net.minecraft.world.level.ChunkPos;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

public final class BackgroundSampleVerifier {
    private static final ChunkCodec.Options OPTIONS = new ChunkCodec.Options(128, true);
    private record Source(Path file, int x, int z, int[] acknowledgedTimestamps) {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected original MCA folder and new output folder");
        Path input = Path.of(args[0]).toRealPath(), output = Path.of(args[1]).toAbsolutePath();
        if (Files.exists(output) || output.startsWith(input)) throw new IOException("Output must be new and outside input");
        Files.createDirectories(output);
        List<Path> files;
        try (var paths = Files.list(input)) {
            files = paths.filter(p -> p.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.mca")).sorted().toList();
        }
        if (files.isEmpty()) throw new IOException("No samples");
        List<Source> sources = new ArrayList<>();
        long writeNanos = 0, originalBytes = 0, chunks = 0, foregroundBytes, drainedBytes, packedBytes, drainNanos, compactNanos;
        BackgroundReencoder.start(0);
        try {
            try (CompactRegionStore store = new CompactRegionStore(output, BackgroundReencoder.current())) {
                for (Path file : files) {
                    String[] name = file.getFileName().toString().split("\\.");
                    int x = Integer.parseInt(name[1]), z = Integer.parseInt(name[2]);
                    McaSnapshot snapshot = McaSnapshot.capture(input, x, z);
                    originalBytes += snapshot.sourceBytes();
                    int[] timestamps = new int[1024];
                    for (McaSnapshot.Chunk chunk : snapshot.chunks()) {
                        ChunkPos pos = position(x, z, chunk.slot());
                        CompoundTag original = read(chunk.payload(), chunk.codec());
                        long start = System.nanoTime();
                        store.writeDeferred(pos, original, OPTIONS);
                        writeNanos += System.nanoTime() - start;
                        synchronized (store) { timestamps[chunk.slot()] = region(store, x, z).timestamp(chunk.slot()); }
                        if (!original.equals(decoded(store, pos))) throw new IOException("Foreground NBT differs: " + pos);
                        chunks++;
                    }
                    sources.add(new Source(file, x, z, timestamps));
                    System.out.println("DURABLE " + file.getFileName() + " chunks=" + snapshot.chunks().size());
                }
                store.flush();
                synchronized (store) { foregroundBytes = bytes(output); }
                long start = System.nanoTime();
                if (!BackgroundReencoder.awaitGlobalIdle(600000)) throw new IOException("Background work did not drain");
                drainNanos = System.nanoTime() - start;
                store.flush();
                drainedBytes = bytes(output);
                verify(input, store, sources);
                start = System.nanoTime();
                synchronized (store) {
                    for (Source source : sources) region(store, source.x, source.z).compact();
                }
                compactNanos = System.nanoTime() - start;
                packedBytes = bytes(output);
            }
            for (int pass = 0; pass < 2; pass++) {
                try (CompactRegionStore reopened = new CompactRegionStore(output)) { verify(input, reopened, sources); }
            }
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("regionsVerified", sources.size());
            report.put("chunksVerified", chunks);
            report.put("fullNbtEqualityVerified", true);
            report.put("acknowledgedTimestampsPreserved", true);
            report.put("independentReopenPasses", 2);
            report.put("originalBytes", originalBytes);
            report.put("foregroundFilesBytes", foregroundBytes);
            report.put("drainedFilesBytes", drainedBytes);
            report.put("packedFilesBytes", packedBytes);
            report.put("foregroundWriteMillis", writeNanos / 1e6);
            report.put("backgroundDrainMillis", drainNanos / 1e6);
            report.put("explicitCompactionMillis", compactNanos / 1e6);
            report.put("backgroundStatus", BackgroundReencoder.status());
            report.put("note", "Streaming correctness/space test, not isolated timing. Background may run while later regions are saved. Index timestamps are new runtime save acknowledgements and remain unchanged by background work.");
            Files.writeString(output.resolve("report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report), StandardCharsets.UTF_8);
            System.out.println("PASS " + chunks + " chunks, two reopens, packed bytes=" + packedBytes);
        } finally { BackgroundReencoder.stopAndAwait(); }
    }

    @SuppressWarnings("unchecked")
    private static CompactRegion region(CompactRegionStore store, int x, int z) throws Exception {
        var field = CompactRegionStore.class.getDeclaredField("regions");
        field.setAccessible(true);
        CompactRegion region = ((Map<Long, CompactRegion>) field.get(store)).get(ChunkPos.asLong(x, z));
        if (region == null) throw new IOException("Expected open region");
        return region;
    }

    private static void verify(Path input, CompactRegionStore store, List<Source> sources) throws Exception {
        for (Source source : sources) {
            McaSnapshot snapshot = McaSnapshot.capture(input, source.x, source.z);
            for (McaSnapshot.Chunk chunk : snapshot.chunks()) {
                ChunkPos pos = position(source.x, source.z, chunk.slot());
                if (!read(chunk.payload(), chunk.codec()).equals(decoded(store, pos))) throw new IOException("Background NBT differs: " + pos);
                synchronized (store) {
                    if (region(store, source.x, source.z).timestamp(chunk.slot()) != source.acknowledgedTimestamps[chunk.slot()]) {
                        throw new IOException("Background timestamp differs: " + pos);
                    }
                }
            }
        }
    }

    private static ChunkPos position(int x, int z, int slot) { return new ChunkPos(x * 32 + (slot & 31), z * 32 + (slot >>> 5)); }
    private static CompoundTag decoded(CompactRegionStore store, ChunkPos pos) throws IOException {
        CompoundTag tag = store.read(pos);
        if (tag == null) throw new IOException("Missing stored chunk: " + pos);
        return SectionCodec.decode(tag, null);
    }
    private static CompoundTag read(byte[] bytes, int codec) throws IOException {
        InputStream raw = new ByteArrayInputStream(bytes);
        InputStream decoded = switch (codec) {
            case 1 -> new GZIPInputStream(raw);
            case 2 -> new InflaterInputStream(raw);
            case 3 -> raw;
            default -> throw new IOException("Unsupported sample codec: " + codec);
        };
        try (var in = new DataInputStream(new BufferedInputStream(decoded))) {
            CompoundTag tag = NbtIo.read(in, NbtAccounter.unlimitedHeap());
            if (in.read() != -1) throw new IOException("Trailing sample NBT");
            return tag;
        }
    }
    private static long bytes(Path folder) throws IOException {
        try (var files = Files.list(folder)) {
            long total = 0;
            for (Path file : files.toList()) {
                if (file.toString().endsWith(".cqs") || file.toString().endsWith(".cqs.owner")) total += Files.size(file);
            }
            return total;
        }
    }
}
