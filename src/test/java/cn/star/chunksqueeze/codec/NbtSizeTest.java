package cn.star.chunksqueeze.codec;

import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UTFDataFormatException;

import static org.junit.jupiter.api.Assertions.*;

class NbtSizeTest {
    @Test
    void matchesSerializationForEveryTagTypeAndListElementType() throws Exception {
        CompoundTag nested = new CompoundTag();
        nested.putString("键\u0000🌍", "\u0001\u007f\u0080߿ࠀ𐏿");
        ListTag nestedList = new ListTag();
        nestedList.add(nested);
        Tag[] values = {
                ByteTag.valueOf((byte) -1), ShortTag.valueOf((short) -2), IntTag.valueOf(-3),
                LongTag.valueOf(Long.MIN_VALUE), FloatTag.valueOf(Float.NaN), DoubleTag.valueOf(Double.POSITIVE_INFINITY),
                new ByteArrayTag(new byte[]{0, 1, -1}), StringTag.valueOf("数据 🌍 \u0000"),
                nestedList, nested, new IntArrayTag(new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE}),
                new LongArrayTag(new long[]{Long.MIN_VALUE, Long.MAX_VALUE})
        };
        CompoundTag root = new CompoundTag();
        for (Tag value : values) {
            root.put("tag" + value.getId(), value);
            ListTag list = new ListTag();
            list.add(value.copy());
            list.add(value.copy());
            root.put("list" + value.getId(), list);
        }
        root.put("emptyList", new ListTag());
        root.putByteArray("emptyBytes", new byte[0]);
        root.putIntArray("emptyInts", new int[0]);
        root.putLongArray("emptyLongs", new long[0]);
        long expected = ChunkCodec.serialize(root).length;
        assertEquals(expected, NbtSize.rootWithout(root, null, expected));
        assertThrows(IOException.class, () -> NbtSize.rootWithout(root, null, expected - 1));
        assertEquals(0, NbtSize.payload(EndTag.INSTANCE, 0, 0));
        assertEquals(4, NbtSize.rootWithout(new CompoundTag(), null, 4));
    }

    @Test
    void excludesOnlyTheRootMarker() throws Exception {
        CompoundTag root = new CompoundTag();
        root.putByteArray(ChunkCodec.KEY, new byte[1024]);
        CompoundTag nested = new CompoundTag();
        nested.putString(ChunkCodec.KEY, "keep");
        root.put("nested", nested);
        CompoundTag expected = root.copy();
        expected.remove(ChunkCodec.KEY);
        assertEquals(ChunkCodec.serialize(expected).length,
                NbtSize.rootWithout(root, ChunkCodec.KEY, Long.MAX_VALUE));
    }

    @Test
    void modifiedUtfCountsNullSurrogatesAndTheUnsignedShortBoundary() throws Exception {
        for (String text : new String[]{"", "\u0000", "🌍", "\ud800x\udfff", "a".repeat(65535),
                "\u0000".repeat(32767), "界".repeat(21845), "🌍".repeat(10922) + "abc"}) {
            var bytes = new ByteArrayOutputStream();
            new DataOutputStream(bytes).writeUTF(text);
            assertEquals(bytes.size(), NbtSize.payload(StringTag.valueOf(text), 0, Long.MAX_VALUE));
            CompoundTag root = new CompoundTag();
            root.putString(text, text);
            assertEquals(ChunkCodec.serialize(root).length, NbtSize.rootWithout(root, null, Long.MAX_VALUE));
        }
        for (String text : new String[]{"a".repeat(65536), "\u0000".repeat(32768),
                "界".repeat(21846), "🌍".repeat(10923)}) {
            assertThrows(UTFDataFormatException.class,
                    () -> NbtSize.payload(StringTag.valueOf(text), 0, Long.MAX_VALUE));
            CompoundTag root = new CompoundTag();
            root.putInt(text, 1);
            assertThrows(UTFDataFormatException.class, () -> NbtSize.rootWithout(root, null, Long.MAX_VALUE));
        }
    }

    @Test
    void countsRootAndEmptyListContainersTowardDepth() throws Exception {
        CompoundTag root = new CompoundTag();
        CompoundTag current = root;
        for (int i = 0; i < 510; i++) {
            CompoundTag child = new CompoundTag();
            current.put("nested", child);
            current = child;
        }
        ListTag list = new ListTag();
        current.put("list", list);
        assertEquals(ChunkCodec.serialize(root).length, NbtSize.rootWithout(root, null, Long.MAX_VALUE));
        list.add(new CompoundTag());
        assertThrows(IOException.class, () -> NbtSize.rootWithout(root, null, Long.MAX_VALUE));
        assertEquals(1, NbtSize.payload(new CompoundTag(), 511, 1));
        assertThrows(IOException.class, () -> NbtSize.payload(new CompoundTag(), 512, Long.MAX_VALUE));
    }
}
