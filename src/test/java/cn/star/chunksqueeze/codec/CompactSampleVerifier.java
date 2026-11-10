package cn.star.chunksqueeze.codec;

import cn.star.chunksqueeze.storage.CompactRegion;
import com.google.gson.GsonBuilder;
import net.minecraft.nbt.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

public final class CompactSampleVerifier {
    private CompactSampleVerifier() {}

    public static void main(String[] args) throws Exception {
        Path originals = Path.of(args[0]);
        Path imported = Path.of(args[1]);
        Path output = Path.of(args[2]);
        if (Files.exists(output)) throw new IOException("Output directory must not exist");
        Files.createDirectories(output);
        List<Path> files;
        try (var walk = Files.walk(originals)) { files = walk.filter(p -> p.toString().endsWith(".mca")).sorted().toList(); }
        if (files.isEmpty()) throw new IOException("No source samples");
        long count = 0, optimized = 0, encodeNanos = 0, writeNanos = 0, compressedBytes = 0;
        List<Map<String, Object>> regions = new ArrayList<>();
        for (Path file : files) {
            String[] parts = file.getFileName().toString().split("\\.");
            int rx = Integer.parseInt(parts[1]), rz = Integer.parseInt(parts[2]);
            String name = "r." + rx + "." + rz + ".cqs";
            long regionCount = 0;
            try (RandomAccessFile source = new RandomAccessFile(file.toFile(), "r");
                 CompactRegion python = CompactRegion.open(imported.resolve(name), rx, rz, false);
                 CompactRegion java = CompactRegion.open(output.resolve(name), rx, rz, true)) {
                for (int slot = 0; slot < 1024; slot++) {
                    source.seek(slot * 4L);
                    int location = source.readInt();
                    source.seek(4096 + slot * 4L);
                    int timestamp = source.readInt();
                    if (location == 0) {
                        if (python.read(slot) != null) throw new AssertionError("Unexpected imported chunk");
                        if (timestamp != 0) java.write(slot, null, 0, timestamp);
                        continue;
                    }
                    source.seek((location >>> 8) * 4096L);
                    int length = source.readInt();
                    if (source.readUnsignedByte() != 2) throw new IOException("Expected zlib sample");
                    byte[] packed = new byte[length - 1];
                    source.readFully(packed);
                    CompoundTag expected = inflate(packed);
                    CompactRegion.Record importedRecord = python.read(slot);
                    if (importedRecord == null || importedRecord.codec() != 2 || python.timestamp(slot) != timestamp
                            || !expected.equals(ChunkCodec.decode(inflate(importedRecord.payload())))) {
                        throw new AssertionError("Python import mismatch: " + name + " slot " + slot);
                    }
                    long start = System.nanoTime();
                    ChunkCodec.Result result = ChunkCodec.encode(expected, new ChunkCodec.Options(128, true), 512, 0);
                    encodeNanos += System.nanoTime() - start;
                    byte[] payload = deflate(ChunkCodec.serialize(result.tag()));
                    start = System.nanoTime();
                    java.write(slot, payload, 2, timestamp);
                    writeNanos += System.nanoTime() - start;
                    CompactRegion.Record written = java.read(slot);
                    if (!expected.equals(ChunkCodec.decode(inflate(written.payload()))) || java.timestamp(slot) != timestamp) {
                        throw new AssertionError("Java write mismatch: " + name + " slot " + slot);
                    }
                    if (result.optimized()) optimized++;
                    compressedBytes += payload.length;
                    count++;
                    regionCount++;
                }
            }
            try (CompactRegion reopened = CompactRegion.open(output.resolve(name), rx, rz, false)) {
                for (int slot = 0; slot < 1024; slot++) reopened.read(slot);
            }
            long bytes = Files.size(output.resolve(name)) + Files.size(CompactRegion.ownerPath(output.resolve(name)));
            regions.add(Map.of("file", name, "chunks", regionCount, "regionAndOwnerBytes", bytes));
            System.out.println(name + ": " + regionCount + " verified, " + bytes + " bytes including owner");
        }
        long total;
        try (var paths = Files.list(output)) { total = paths.mapToLong(p -> { try { return Files.size(p); } catch (IOException e) { throw new UncheckedIOException(e); } }).sum(); }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("chunksVerified", count);
        report.put("stateEncodedChunks", optimized);
        report.put("compressedPayloadBytes", compressedBytes);
        report.put("regionAndOwnerBytes", total);
        report.put("meanEncodeMillis", encodeNanos / 1e6 / count);
        report.put("meanDurableWriteMillis", writeNanos / 1e6 / count);
        report.put("regions", regions);
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(report);
        Files.writeString(output.getParent().resolve("compact-benchmark.json"), json);
        System.out.println(json);
    }

    private static CompoundTag inflate(byte[] bytes) throws IOException {
        try (var input = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(bytes)))) {
            return NbtIo.read(input, NbtAccounter.unlimitedHeap());
        }
    }
    private static byte[] deflate(byte[] bytes) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var deflater = new DeflaterOutputStream(output)) { deflater.write(bytes); }
        return output.toByteArray();
    }
}
