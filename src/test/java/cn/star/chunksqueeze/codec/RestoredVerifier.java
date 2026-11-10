package cn.star.chunksqueeze.codec;

import cn.star.chunksqueeze.compat.NbtAccounter;

import net.minecraft.nbt.*;

import java.io.*;
import java.nio.file.*;
import java.util.List;
import java.util.zip.InflaterInputStream;

public final class RestoredVerifier {
    private RestoredVerifier() {}

    public static void main(String[] args) throws Exception {
        Path originals = Path.of(args[0]);
        Path restored = Path.of(args[1]);
        List<Path> files;
        try (var walk = Files.walk(originals)) {
            files = walk.filter(p -> p.toString().endsWith(".mca")).sorted().toList();
        }
        int verified = 0;
        for (Path path : files) {
            try (RandomAccessFile source = new RandomAccessFile(path.toFile(), "r");
                 RandomAccessFile target = new RandomAccessFile(restored.resolve(path.getFileName()).toFile(), "r")) {
                for (int slot = 0; slot < 1024; slot++) {
                    CompoundTag expected = read(source, slot);
                    CompoundTag actual = read(target, slot);
                    if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(path + " slot " + slot);
                    if (actual != null) {
                        if (actual.contains(ChunkCodec.KEY)) throw new AssertionError("Still encoded");
                        verified++;
                    }
                    source.seek(4096 + slot * 4L);
                    target.seek(4096 + slot * 4L);
                    if (source.readInt() != target.readInt()) throw new AssertionError("Timestamp mismatch");
                }
            }
        }
        if (verified == 0) throw new AssertionError("No restored chunks");
        System.out.println("Verified Python restoration against original NBT: " + verified + " chunks, including timestamps.");
    }

    private static CompoundTag read(RandomAccessFile file, int slot) throws Exception {
        file.seek(slot * 4L);
        int location = file.readInt();
        if (location == 0) return null;
        file.seek((location >>> 8) * 4096L);
        int length = file.readInt();
        if (file.readUnsignedByte() != 2) throw new IOException("Expected zlib benchmark output");
        byte[] packed = new byte[length - 1];
        file.readFully(packed);
        try (var stream = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(packed)))) {
            return NbtIo.read(stream, NbtAccounter.create(ChunkCodec.MAX_BYTES));
        }
    }
}
