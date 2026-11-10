package cn.star.chunksqueeze.codec;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

import java.io.*;
import java.util.*;
import java.util.zip.Deflater;

// 仅存活于一次编码调用，所有原生压缩器都由调用方的 try-with-resources 释放。
final class EncodeContext implements AutoCloseable {
    private Deflater normal;
    private Deflater fast;
    private final byte[] buffer = new byte[8192];
    private final Bytes serialized = new Bytes();
    private final ByteArrayOutputStream compressed = new ByteArrayOutputStream();
    private final Map<Indices, IndexCodec.Analysis> indices = new HashMap<>();

    private record Indices(long[] words, int palette) {
        @Override public int hashCode() { return 31 * Arrays.hashCode(words) + palette; }
        @Override public boolean equals(Object other) {
            return other instanceof Indices key && palette == key.palette && Arrays.equals(words, key.words);
        }
    }

    private static final class Bytes extends ByteArrayOutputStream {
        byte[] array() { return buf; }
    }

    IndexCodec.Analysis indices(long[] data, int palette) throws IOException {
        Indices key = new Indices(data, palette);
        IndexCodec.Analysis found = indices.get(key);
        if (found != null) return found;
        IndexCodec.Analysis analyzed = IndexCodec.analyze(data, palette, this);
        indices.put(key, analyzed);
        return analyzed;
    }

    byte[] compress(CompoundTag tag) throws IOException {
        serialized.reset();
        NbtIo.write(tag, new DataOutputStream(serialized));
        compressed.reset();
        deflate(serialized.array(), serialized.size(), Deflater.DEFAULT_COMPRESSION, compressed);
        return compressed.toByteArray();
    }

    int compressedSize(byte[] data, int level) {
        return deflate(data, data.length, level, null);
    }

    private int deflate(byte[] data, int length, int level, ByteArrayOutputStream target) {
        Deflater deflater;
        if (level == Deflater.BEST_SPEED) {
            if (fast == null) fast = new Deflater(level);
            deflater = fast;
        } else {
            if (normal == null) normal = new Deflater(level);
            deflater = normal;
        }
        deflater.reset();
        deflater.setInput(data, 0, length);
        deflater.finish();
        int total = 0;
        while (!deflater.finished()) {
            int count = deflater.deflate(buffer);
            total += count;
            if (target != null) target.write(buffer, 0, count);
        }
        return total;
    }

    @Override public void close() {
        if (normal != null) { normal.end(); normal = null; }
        if (fast != null) { fast.end(); fast = null; }
    }
}
