package cn.star.chunksqueeze.codec;

import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;

import java.io.*;

import static org.junit.jupiter.api.Assertions.*;

class NbtHeapSizeTest {
    @Test
    void runtimeProfileEnablesDirectAccountingAndMatchesAllTagAndListTypes() throws Exception {
        assertTrue(NbtHeapSize.exactAccountingSupported());
        CompoundTag nested = new CompoundTag();
        nested.putString("name\u0000🌍", "\u0001\u007f\u0080߿ࠀ𐏿");
        ListTag nestedList = new ListTag();
        nestedList.add(nested);
        Tag[] values = {
                ByteTag.valueOf((byte) -1), ShortTag.valueOf((short) -2), IntTag.valueOf(-3),
                LongTag.valueOf(Long.MIN_VALUE), FloatTag.valueOf(Float.NaN), DoubleTag.valueOf(-0.0),
                new ByteArrayTag(new byte[]{0, 1, -1}), StringTag.valueOf("数据 🌍 \u0000"),
                nestedList, nested, new IntArrayTag(new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE}),
                new LongArrayTag(new long[]{Long.MIN_VALUE, Long.MAX_VALUE})
        };
        for (Tag value : values) {
            CompoundTag root = new CompoundTag();
            root.put("tag" + value.getId(), value);
            ListTag list = new ListTag();
            list.add(value.copy());
            list.add(value.copy());
            root.put("list" + value.getId(), list);
            root.put("emptyList", new ListTag());
            assertMatchesRead(root);
            assertEquals(payloadUsage(value), NbtHeapSize.payload(value, 0, Long.MAX_VALUE));
        }
        assertEquals(payloadUsage(EndTag.INSTANCE), NbtHeapSize.payload(EndTag.INSTANCE, 0, Long.MAX_VALUE));
        assertMatchesRead(new CompoundTag());
    }

    @Test
    void stringsAndNamesMatchActualLoadRatherThanSizeInBytesOrParse() throws Exception {
        for (String text : new String[]{"", "a".repeat(128), "\u0000".repeat(128), "界".repeat(128),
                "🌍".repeat(128), "\ud800x\udfff", "a".repeat(65535)}) {
            StringTag value = StringTag.valueOf(text);
            assertEquals(payloadUsage(value), NbtHeapSize.payload(value, 0, Long.MAX_VALUE));
            CompoundTag root = new CompoundTag();
            root.putString(text, text);
            assertMatchesRead(root);
        }
        assertEquals(130, NbtSize.payload(StringTag.valueOf("a".repeat(128)), 0, Long.MAX_VALUE));
        assertEquals(292, NbtHeapSize.payload(StringTag.valueOf("a".repeat(128)), 0, Long.MAX_VALUE));
        assertThrows(UTFDataFormatException.class,
                () -> NbtHeapSize.payload(StringTag.valueOf("a".repeat(65536)), 0, Long.MAX_VALUE));
    }

    @Test
    void vanillaFactoriesAndSerializationRetainMinecraftNumericNormalization() throws Exception {
        assertEquals(0, Float.floatToRawIntBits(FloatTag.valueOf(-0.0f).getAsFloat()));
        assertEquals(0L, Double.doubleToRawLongBits(DoubleTag.valueOf(-0.0).getAsDouble()));
        CompoundTag root = new CompoundTag();
        root.putFloat("float", -0.0f);
        root.putDouble("double", -0.0);
        root.putFloat("floatNaN", Float.intBitsToFloat(0x7fc00001));
        root.putDouble("doubleNaN", Double.longBitsToDouble(0x7ff8000000000001L));
        CompoundTag loaded = NbtIo.read(new DataInputStream(new ByteArrayInputStream(ChunkCodec.serialize(root))),
                NbtAccounter.unlimitedHeap());
        assertEquals(0, Float.floatToRawIntBits(loaded.getFloat("float")));
        assertEquals(0L, Double.doubleToRawLongBits(loaded.getDouble("double")));
        assertEquals(Float.floatToIntBits(Float.NaN), Float.floatToRawIntBits(loaded.getFloat("floatNaN")));
        assertEquals(Double.doubleToLongBits(Double.NaN), Double.doubleToRawLongBits(loaded.getDouble("doubleNaN")));
    }

    @Test
    void largeArraysAndQuotaBoundaryMatchTheRealAccounter() throws Exception {
        CompoundTag root = new CompoundTag();
        root.putByteArray("bytes", new byte[1024 * 1024]);
        root.putIntArray("ints", new int[65536]);
        root.putLongArray("longs", new long[65536]);
        root.putByteArray("emptyBytes", new byte[0]);
        root.putIntArray("emptyInts", new int[0]);
        root.putLongArray("emptyLongs", new long[0]);
        long usage = readUsage(root, Long.MAX_VALUE);
        assertEquals(usage, NbtHeapSize.root(root, usage));
        assertEquals(usage, readUsage(root, usage));
        assertThrows(NbtAccounterException.class, () -> NbtHeapSize.root(root, usage - 1));
        assertThrows(RuntimeException.class, () -> readUsage(root, usage - 1));
        long entryUsage = payloadUsage(root);
        assertEquals(entryUsage, NbtHeapSize.payload(root, 2, entryUsage));
        assertThrows(NbtAccounterException.class, () -> NbtHeapSize.payload(root, 2, entryUsage - 1));
    }

    @Test
    void countsAllContainersAndRetainsThe512DepthBoundary() throws Exception {
        CompoundTag deepest = nested(512);
        assertMatchesRead(deepest);
        CompoundTag tooDeep = nested(513);
        assertThrows(NbtAccounterException.class, () -> NbtHeapSize.root(tooDeep, Long.MAX_VALUE));
        assertThrows(RuntimeException.class, () -> readUsage(tooDeep, Long.MAX_VALUE));
        assertEquals(payloadUsage(nested(507)), NbtHeapSize.payload(nested(507), 5, Long.MAX_VALUE));
        assertThrows(NbtAccounterException.class,
                () -> NbtHeapSize.payload(nested(508), 5, Long.MAX_VALUE));
        assertThrows(NbtAccounterException.class,
                () -> NbtHeapSize.payload(new ListTag(), 512, Long.MAX_VALUE));
        assertEquals(12, NbtHeapSize.payload(IntTag.valueOf(1), 512, 12));
    }

    @Test
    void customSubclassesFallBackWithoutCopyingAndCannotUnderstateSerializedData() throws Exception {
        CompoundTag custom = new CompoundTag() {
            @Override public CompoundTag copy() { throw new AssertionError("Copied while accounting"); }
        };
        custom.putString("name", "custom");
        assertEquals(readUsage(custom, Long.MAX_VALUE), NbtHeapSize.root(custom, Long.MAX_VALUE));
        assertEquals(payloadUsage(custom), NbtHeapSize.payload(custom, 2, Long.MAX_VALUE));
        ByteArrayTag misleading = new ByteArrayTag(new byte[1]) {
            @Override public void write(DataOutput out) throws IOException {
                new ByteArrayTag(new byte[1024]).write(out);
            }
        };
        assertThrows(IOException.class, () -> NbtHeapSize.payload(misleading, 0, Long.MAX_VALUE));
        CompoundTag root = new CompoundTag();
        root.put("custom", misleading);
        assertThrows(IOException.class, () -> NbtHeapSize.root(root, Long.MAX_VALUE));

        ListTag strings = new ListTag();
        for (int i = 0; i < 10; i++) strings.add(StringTag.valueOf(""));
        CompoundTag structure = new CompoundTag();
        structure.put("strings", strings);
        CompoundTag alternative = new CompoundTag();
        alternative.putByteArray("blob", new byte[0]);
        int padding = (int) (NbtSize.payload(structure, 0, Long.MAX_VALUE)
                - NbtSize.payload(alternative, 0, Long.MAX_VALUE));
        alternative.putByteArray("blob", new byte[padding]);
        CompoundTag sameRawButDifferentHeap = new CompoundTag() {
            @Override public void write(DataOutput out) throws IOException { alternative.write(out); }
        };
        sameRawButDifferentHeap.put("strings", strings);
        assertEquals(NbtSize.payload(structure, 0, Long.MAX_VALUE),
                NbtSize.payload(alternative, 0, Long.MAX_VALUE));
        assertThrows(IOException.class,
                () -> NbtHeapSize.payload(sameRawButDifferentHeap, 0, Long.MAX_VALUE));
    }

    private static void assertMatchesRead(CompoundTag root) throws Exception {
        assertEquals(readUsage(root, Long.MAX_VALUE), NbtHeapSize.root(root, Long.MAX_VALUE));
    }

    private static long readUsage(CompoundTag root, long limit) throws IOException {
        NbtAccounter accounter = NbtAccounter.create(limit);
        NbtIo.read(new DataInputStream(new ByteArrayInputStream(ChunkCodec.serialize(root))), accounter);
        return accounter.getUsage();
    }

    private static long payloadUsage(Tag tag) throws IOException {
        var bytes = new ByteArrayOutputStream();
        tag.write(new DataOutputStream(bytes));
        NbtAccounter accounter = NbtAccounter.unlimitedHeap();
        TagTypes.getType(tag.getId()).load(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())), accounter);
        return accounter.getUsage();
    }

    static CompoundTag nested(int containers) {
        CompoundTag root = new CompoundTag();
        CompoundTag current = root;
        for (int i = 1; i < containers; i++) {
            CompoundTag child = new CompoundTag();
            current.put("nested", child);
            current = child;
        }
        return root;
    }
}
