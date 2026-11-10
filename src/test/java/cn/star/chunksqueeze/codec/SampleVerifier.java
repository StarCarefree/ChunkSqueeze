package cn.star.chunksqueeze.codec;

import com.google.gson.GsonBuilder;
import net.minecraft.nbt.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public final class SampleVerifier {
    private SampleVerifier() {}

    public static void main(String[] args) throws Exception {
        Path source = Path.of(args[0]).toAbsolutePath();
        Path output = Path.of(args[1]).toAbsolutePath();
        int limit = Integer.parseInt(args[2]);
        if (output.startsWith(source)) throw new IOException("Output must be separate from samples");
        Files.createDirectories(output.resolve("encoded"));
        List<Path> files;
        try (var walk = Files.walk(source)) {
            files = walk.filter(p -> p.toString().endsWith(".mca")).sorted().toList();
        }
        if (files.isEmpty()) throw new IOException("No MCA samples found");
        long count = 0, optimized = 0, baseBytes = 0, savedBytes = 0, originalAllocated = 0, allocated = 0;
        long encodingNanos = 0, decodingNanos = 0;
        List<Long> encodeTimes = new ArrayList<>();
        List<Long> decodeTimes = new ArrayList<>();
        Map<String, Long> modes = new TreeMap<>();
        List<Map<String, Object>> regions = new ArrayList<>();
        for (Path path : files) {
            byte[] file = Files.readAllBytes(path);
            byte[] header = new byte[8192];
            System.arraycopy(file, 4096, header, 4096, 4096);
            var body = new ByteArrayOutputStream();
            int offset = 2, inRegion = 0;
            long regionBase = 0, regionStored = 0;
            for (int slot = 0; slot < 1024; slot++) {
                if (limit == 16 && ((slot % 32) % 8 != 4 || (slot / 32) % 8 != 4)) continue;
                if (limit > 0 && limit != 16 && inRegion >= limit) break;
                int location = readInt(file, slot * 4);
                if (location == 0) continue;
                int position = (location >>> 8) * 4096;
                int length = readInt(file, position);
                if (file[position + 4] != 2) throw new IOException("Benchmark requires zlib sample: " + path);
                byte[] compressed = Arrays.copyOfRange(file, position + 5, position + 4 + length);
                CompoundTag original;
                try (var stream = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(compressed)))) {
                    original = NbtIo.read(stream, NbtAccounter.create(ChunkCodec.MAX_BYTES));
                }
                CompoundTag before = original.copy();
                long start = System.nanoTime();
                ChunkCodec.Result result = ChunkCodec.encode(original, new ChunkCodec.Options(128, true));
                long encodeTime = System.nanoTime() - start;
                encodingNanos += encodeTime;
                encodeTimes.add(encodeTime);
                byte[] stored = ChunkCodec.serialize(result.tag());
                CompoundTag diskTag = NbtIo.read(new DataInputStream(new ByteArrayInputStream(stored)), NbtAccounter.create(ChunkCodec.MAX_BYTES));
                start = System.nanoTime();
                CompoundTag decoded = ChunkCodec.decode(diskTag);
                long decodeTime = System.nanoTime() - start;
                decodingNanos += decodeTime;
                decodeTimes.add(decodeTime);
                if (!before.equals(original) || !before.equals(decoded)) throw new AssertionError("NBT mismatch: " + path + " slot " + slot);
                byte[] diskBytes = compress(stored);
                int sectorCount = ChunkCodec.sectors(diskBytes.length);
                if (sectorCount > 255) throw new IOException("Benchmark external output not supported");
                putInt(header, slot * 4, (offset << 8) | sectorCount);
                var record = new DataOutputStream(body);
                record.writeInt(diskBytes.length + 1);
                record.writeByte(2);
                record.write(diskBytes);
                record.write(new byte[sectorCount * 4096 - diskBytes.length - 5]);
                offset += sectorCount;
                int canonicalBase = result.originalBytes() == 0
                        ? IndexCodec.compressedSize(ChunkCodec.serialize(original), Deflater.DEFAULT_COMPRESSION) : result.originalBytes();
                baseBytes += canonicalBase;
                savedBytes += canonicalBase - diskBytes.length;
                originalAllocated += ChunkCodec.sectors(canonicalBase) * 4096L;
                allocated += sectorCount * 4096L;
                regionBase += canonicalBase;
                regionStored += diskBytes.length;
                count++;
                inRegion++;
                if (result.optimized()) optimized++;
                modes.merge(result.encoding(), 1L, Long::sum);
            }
            try (var stream = Files.newOutputStream(output.resolve("encoded").resolve(path.getFileName()))) {
                stream.write(header);
                body.writeTo(stream);
            }
            regions.add(Map.of("file", path.getFileName().toString(), "chunks", inRegion,
                    "baselineCompressedBytes", regionBase, "storedCompressedBytes", regionStored));
            System.out.printf(Locale.ROOT, "%s: %d chunks, %.2f%% compressed savings%n",
                    path.getFileName(), inRegion, 100.0 * (regionBase - regionStored) / Math.max(1, regionBase));
        }
        CompoundTag fixture = CodecTest.fixture();
        Files.write(output.resolve("fixture-original.nbt"), ChunkCodec.serialize(fixture));
        Files.write(output.resolve("fixture-encoded.nbt"), ChunkCodec.serialize(ChunkCodec.encode(fixture, new ChunkCodec.Options(0, false)).tag()));
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("chunksVerified", count);
        report.put("optimizedChunks", optimized);
        report.put("baselineCompressedBytes", baseBytes);
        report.put("compressedBytesSaved", savedBytes);
        report.put("baselineAllocatedBytes", originalAllocated);
        report.put("allocatedBytes", allocated);
        report.put("allocatedBytesSaved", originalAllocated - allocated);
        report.put("encodeMeanMillis", encodingNanos / 1e6 / count);
        report.put("decodeMeanMillis", decodingNanos / 1e6 / count);
        report.put("encodeP95Millis", percentile(encodeTimes, .95) / 1e6);
        report.put("decodeP95Millis", percentile(decodeTimes, .95) / 1e6);
        report.put("encodingCounts", modes);
        report.put("regions", regions);
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(report);
        Files.writeString(output.resolve("benchmark.json"), json);
        System.out.println(json);
    }

    private static long percentile(List<Long> values, double q) {
        values.sort(Long::compare);
        return values.get((int) Math.floor((values.size() - 1) * q));
    }
    private static int readInt(byte[] data, int offset) {
        return (data[offset] & 255) << 24 | (data[offset + 1] & 255) << 16 | (data[offset + 2] & 255) << 8 | data[offset + 3] & 255;
    }
    private static void putInt(byte[] data, int offset, int value) {
        for (int i = 3; i >= 0; i--) { data[offset + i] = (byte) value; value >>>= 8; }
    }
    private static byte[] compress(byte[] bytes) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var deflater = new DeflaterOutputStream(output)) { deflater.write(bytes); }
        return output.toByteArray();
    }
}
