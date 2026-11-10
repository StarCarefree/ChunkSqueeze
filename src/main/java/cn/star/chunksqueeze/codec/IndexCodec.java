package cn.star.chunksqueeze.codec;

import java.io.*;
import java.util.Arrays;
import java.util.zip.Deflater;

public final class IndexCodec {
    public static final int BLOCKS = 4096;
    private IndexCodec() {}

    public static byte[] encode(long[] original, int paletteSize, boolean adaptive) throws IOException {
        if (!adaptive) return raw(original);
        try (EncodeContext context = new EncodeContext()) {
            return analyze(original, paletteSize, context).adaptive();
        }
    }

    record Analysis(byte[] raw, byte[] adaptive) {}

    static Analysis analyze(long[] original, int paletteSize, EncodeContext context) throws IOException {
        int[] values = unpackVanilla(original, paletteSize);
        byte[] raw = raw(original);
        // 直接检查每个原版 word 的未用位，避免再分配数组重打包。
        int bits = Math.max(4, bits(paletteSize)), perLong = 64 / bits, remaining = BLOCKS;
        for (long word : original) {
            int count = Math.min(perLong, remaining);
            int used = count * bits;
            if (used < 64 && (word >>> used) != 0) return new Analysis(raw, raw);
            remaining -= count;
        }
        int runs = 1, mostFrequent = 0;
        int[] frequencies = new int[paletteSize];
        for (int i = 0; i < values.length; i++) {
            mostFrequent = Math.max(mostFrequent, ++frequencies[values[i]]);
            if (i > 0 && values[i] != values[i - 1]) runs++;
        }
        byte[] best = raw;
        int bestSize = context.compressedSize(best, Deflater.BEST_SPEED);
        for (int mode = 2; mode <= 5; mode++) {
            // 游程平均不足四格或例外超过八分之一时，跳过对应的试算。
            if (mode == 3 && runs > BLOCKS / 4 || mode == 4 && BLOCKS - mostFrequent > BLOCKS / 8) continue;
            byte[] candidate = encodeValues(values, paletteSize, mode);
            int size = context.compressedSize(candidate, Deflater.BEST_SPEED);
            if (size < bestSize) {
                best = candidate;
                bestSize = size;
            }
        }
        return new Analysis(raw, best);
    }

    private static byte[] raw(long[] original) throws IOException {
        var bytes = new ByteArrayOutputStream(6 + original.length * Long.BYTES);
        var out = new DataOutputStream(bytes);
        out.writeByte(1);
        writeVarInt(out, original.length);
        for (long value : original) out.writeLong(value);
        return bytes.toByteArray();
    }

    public static long[] decode(byte[] bytes, int paletteSize) throws IOException {
        if (paletteSize < 1 || paletteSize > BLOCKS) throw new IOException("Invalid palette size");
        var in = new DataInputStream(new ByteArrayInputStream(bytes));
        int mode = in.readUnsignedByte();
        int storageBits = Math.max(4, bits(paletteSize));
        int perLong = 64 / storageBits;
        int packedLength = (BLOCKS + perLong - 1) / perLong;
        long[] result;
        if (mode == 1) {
            int length = bounded(in, BLOCKS, "long array");
            if (length != packedLength) throw new IOException("Invalid packed array length");
            result = new long[length];
            for (int i = 0; i < length; i++) result[i] = in.readLong();
            // 只检查有效索引，不分配 4096 个 int，也不改变原始填充位。
            if (paletteSize != (1 << storageBits)) {
                long mask = (1L << storageBits) - 1;
                int remaining = BLOCKS;
                for (long word : result) {
                    int count = Math.min(perLong, remaining);
                    for (int i = 0; i < count; i++) {
                        if ((word & mask) >= paletteSize) throw new IOException("Invalid packed state index");
                        word >>>= storageBits;
                    }
                    remaining -= count;
                }
            }
        } else if (mode == 5) {
            return decodePlanes(bytes, paletteSize, storageBits, perLong);
        } else {
            result = new long[packedLength];
            long repeatedOne = 0;
            for (int i = 0; i < perLong; i++) repeatedOne |= 1L << (i * storageBits);
            if (mode == 2) {
                int bits = bits(paletteSize), mask = (1 << bits) - 1;
                long buffer = 0;
                int available = 0, word = 0, used = 0;
                for (int i = 0; i < BLOCKS; i++) {
                    while (available < bits) {
                        buffer |= (long) in.readUnsignedByte() << available;
                        available += 8;
                    }
                    int value = (int) buffer & mask;
                    if (value >= paletteSize) throw new IOException("State index out of range");
                    result[word] |= (long) value << (used * storageBits);
                    if (++used == perLong) { used = 0; word++; }
                    buffer >>>= bits;
                    available -= bits;
                }
            } else if (mode == 3) {
                int index = 0;
                while (index < BLOCKS) {
                    int run = bounded(in, BLOCKS - index, "run length");
                    if (run == 0) throw new IOException("Empty run");
                    int value = bounded(in, paletteSize - 1, "state index");
                    fillPacked(result, index, run, value * repeatedOne, storageBits, perLong);
                    index += run;
                }
            } else if (mode == 4) {
                int defaultValue = bounded(in, paletteSize - 1, "default state");
                Arrays.fill(result, defaultValue * repeatedOne);
                int lastCount = BLOCKS % perLong;
                if (lastCount != 0) result[result.length - 1] &= -1L >>> (64 - lastCount * storageBits);
                int count = bounded(in, BLOCKS, "exceptions");
                int previous = -1;
                long mask = (1L << storageBits) - 1;
                for (int i = 0; i < count; i++) {
                    int gap = bounded(in, BLOCKS - previous - 2, "position gap");
                    int index = previous + gap + 1;
                    int value = bounded(in, paletteSize - 1, "state index");
                    int shift = (index % perLong) * storageBits;
                    result[index / perLong] = (result[index / perLong] & ~(mask << shift)) | (long) value << shift;
                    previous = index;
                }
            } else {
                throw new IOException("Unknown index encoding: " + mode);
            }
        }
        if (in.available() != 0) throw new IOException("Trailing index data");
        return result;
    }

    private static long[] decodePlanes(byte[] bytes, int paletteSize, int storageBits, int perLong) throws IOException {
        int planes = bits(paletteSize);
        if (bytes.length != 1 + planes * (BLOCKS / 8)) throw new IOException("Invalid bit-plane length");
        long[] result = new long[(BLOCKS + perLong - 1) / perLong];
        long[] spread = PlaneWords.TABLES[storageBits - 4];
        int cursor = 1;
        for (int plane = 0; plane < planes; plane++) {
            int buffer = 0, available = 0, remaining = BLOCKS;
            for (int word = 0; word < result.length; word++) {
                int count = Math.min(perLong, remaining);
                while (available < count) {
                    buffer |= (bytes[cursor++] & 255) << available;
                    available += 8;
                }
                result[word] |= spread[buffer & ((1 << count) - 1)] << plane;
                buffer >>>= count;
                available -= count;
                remaining -= count;
            }
        }
        if (paletteSize == 1 || (paletteSize & (paletteSize - 1)) != 0) {
            long mask = (1L << storageBits) - 1;
            int remaining = BLOCKS;
            for (long word : result) {
                int count = Math.min(perLong, remaining);
                for (int i = 0; i < count; i++) {
                    if ((word & mask) >= paletteSize) throw new IOException("State index out of range");
                    word >>>= storageBits;
                }
                remaining -= count;
            }
        }
        return result;
    }

    private static final class PlaneWords {
        // 固定只读表约 0.55 MiB，把一组位平面直接展开到原版 long 的槽位。
        private static final long[][] TABLES = create();
        private static long[][] create() {
            long[][] tables = new long[9][];
            for (int bits = 4; bits <= 12; bits++) {
                long[] table = new long[1 << (64 / bits)];
                for (int value = 1; value < table.length; value++) {
                    table[value] = (table[value >>> 1] << bits) | (value & 1L);
                }
                tables[bits - 4] = table;
            }
            return tables;
        }
    }

    private static void fillPacked(long[] data, int start, int count, long pattern, int bits, int perLong) {
        while (count > 0) {
            int used = start % perLong;
            int take = Math.min(count, perLong - used);
            int width = take * bits;
            long mask = width == 64 ? -1L : (1L << width) - 1;
            data[start / perLong] |= (pattern & mask) << (used * bits);
            start += take;
            count -= take;
        }
    }

    static byte[] encodeValues(int[] values, int paletteSize, int mode) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        out.writeByte(mode);
        if (mode == 2) {
            int bits = bits(paletteSize);
            long buffer = 0;
            int available = 0;
            for (int value : values) {
                buffer |= (long) value << available;
                available += bits;
                while (available >= 8) {
                    out.writeByte((int) buffer);
                    buffer >>>= 8;
                    available -= 8;
                }
            }
            if (available > 0) out.writeByte((int) buffer);
        } else if (mode == 3) {
            int start = 0;
            while (start < BLOCKS) {
                int end = start + 1;
                while (end < BLOCKS && values[end] == values[start]) end++;
                writeVarInt(out, end - start);
                writeVarInt(out, values[start]);
                start = end;
            }
        } else if (mode == 5) {
            for (int plane = 0; plane < bits(paletteSize); plane++) {
                for (int group = 0; group < BLOCKS / 8; group++) {
                    int packed = 0;
                    for (int bit = 0; bit < 8; bit++) packed |= ((values[group * 8 + bit] >>> plane) & 1) << bit;
                    out.writeByte(packed);
                }
            }
        } else {
            int[] counts = new int[paletteSize];
            for (int value : values) counts[value]++;
            int defaultValue = 0;
            for (int i = 1; i < counts.length; i++) if (counts[i] > counts[defaultValue]) defaultValue = i;
            writeVarInt(out, defaultValue);
            writeVarInt(out, BLOCKS - counts[defaultValue]);
            int previous = -1;
            for (int i = 0; i < BLOCKS; i++) {
                if (values[i] != defaultValue) {
                    writeVarInt(out, i - previous - 1);
                    writeVarInt(out, values[i]);
                    previous = i;
                }
            }
        }
        return bytes.toByteArray();
    }

    public static int[] unpackVanilla(long[] data, int paletteSize) throws IOException {
        if (paletteSize < 1 || paletteSize > BLOCKS) throw new IOException("Invalid palette size");
        int bits = Math.max(4, bits(paletteSize));
        int perLong = 64 / bits;
        if (data.length != (BLOCKS + perLong - 1) / perLong) throw new IOException("Invalid packed array length");
        int[] values = new int[BLOCKS];
        long mask = (1L << bits) - 1;
        for (int i = 0; i < BLOCKS; i++) {
            values[i] = (int) ((data[i / perLong] >>> ((i % perLong) * bits)) & mask);
            if (values[i] >= paletteSize) throw new IOException("Invalid packed state index");
        }
        return values;
    }

    public static long[] packVanilla(int[] values, int paletteSize) {
        int bits = Math.max(4, bits(paletteSize));
        int perLong = 64 / bits;
        long[] data = new long[(BLOCKS + perLong - 1) / perLong];
        for (int i = 0; i < BLOCKS; i++) data[i / perLong] |= (long) values[i] << ((i % perLong) * bits);
        return data;
    }

    private static int bits(int size) { return Math.max(1, 32 - Integer.numberOfLeadingZeros(size - 1)); }

    public static void writeVarInt(DataOutput out, int value) throws IOException {
        if (value < 0) throw new IOException("Negative integer");
        while ((value & ~127) != 0) {
            out.writeByte((value & 127) | 128);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    public static int bounded(DataInput in, int maximum, String field) throws IOException {
        if (maximum < 0) throw new IOException("Invalid " + field);
        long value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int next = in.readUnsignedByte();
            value |= (long) (next & 127) << shift;
            if ((next & 128) == 0) {
                if (value > maximum || (shift > 0 && next == 0)) throw new IOException("Invalid " + field);
                return (int) value;
            }
        }
        throw new IOException("Invalid " + field);
    }

    public static int compressedSize(byte[] data, int level) {
        Deflater deflater = new Deflater(level);
        try {
            deflater.setInput(data);
            deflater.finish();
            byte[] buffer = new byte[8192];
            int total = 0;
            while (!deflater.finished()) total += deflater.deflate(buffer);
            return total;
        } finally {
            deflater.end();
        }
    }
}
