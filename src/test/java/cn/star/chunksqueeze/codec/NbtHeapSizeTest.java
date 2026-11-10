package cn.star.chunksqueeze.codec;

import cn.star.chunksqueeze.compat.NbtAccounter;
import cn.star.chunksqueeze.compat.NbtAccounterException;
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
        assertEquals(166, NbtHeapSize.payload(StringTag.valueOf("a".repeat(128)), 0, Long.MAX_VALUE));
        assertThrows(UTFDataFormatException.class,
                () -> NbtHeapSize.payload(StringTag.valueOf("a".repeat(65536)), 0, Long.MAX_VALUE));
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
        // 1.20.1 原生根深度为 0，允许 513 层；项目直接计额仍限制为 512 层。
        assertDoesNotThrow(() -> readUsage(tooDeep, Long.MAX_VALUE));
        RuntimeException nativeDepth = assertThrows(RuntimeException.class,
                () -> readUsage(nested(514), Long.MAX_VALUE));
        RuntimeException normalized = NbtAccounterException.normalize(nativeDepth);
        assertInstanceOf(NbtAccounterException.class, normalized);
        assertSame(nativeDepth, normalized.getCause());
        assertEquals(payloadUsage(nested(507)), NbtHeapSize.payload(nested(507), 5, Long.MAX_VALUE));
        assertThrows(NbtAccounterException.class,
                () -> NbtHeapSize.payload(nested(508), 5, Long.MAX_VALUE));
        assertThrows(NbtAccounterException.class,
                () -> NbtHeapSize.payload(new ListTag(), 512, Long.MAX_VALUE));
        assertEquals(12, NbtHeapSize.payload(IntTag.valueOf(1), 512, 12));
        assertDoesNotThrow(() -> payloadUsage(nested(508), 5));
        assertThrows(RuntimeException.class, () -> payloadUsage(nested(509), 5));
        assertDoesNotThrow(() -> payloadUsage(new ListTag(), 512));
        assertThrows(RuntimeException.class, () -> payloadUsage(new ListTag(), 513));

        CompoundTag custom = new CompoundTag() {};
        custom.put("nested", nested(511));
        assertMatchesRead(custom);
        assertEquals(payloadUsage(custom, 0), NbtHeapSize.payload(custom, 0, Long.MAX_VALUE));
        assertThrows(NbtAccounterException.class, () -> NbtHeapSize.payload(custom, 1, Long.MAX_VALUE));
        custom.put("nested", nested(512));
        assertDoesNotThrow(() -> readUsage(custom, Long.MAX_VALUE));
        assertThrows(NbtAccounterException.class, () -> NbtHeapSize.root(custom, Long.MAX_VALUE));
        assertThrows(NbtAccounterException.class, () -> NbtHeapSize.payload(custom, 0, Long.MAX_VALUE));
        assertThrows(NbtAccounterException.class,
                () -> NbtHeapSize.payload(new ListTag() {}, 512, Long.MAX_VALUE));
    }

    @Test
    void customSubclassesFallBackWithoutCopyingAndCannotUnderstateSerializedData() throws Exception {
        CompoundTag custom = new CompoundTag() {
            @Override public CompoundTag copy() { throw new AssertionError("Copied while accounting"); }
        };
        custom.putString("name", "custom");
        assertEquals(readUsage(custom, Long.MAX_VALUE), NbtHeapSize.root(custom, Long.MAX_VALUE));
        assertEquals(payloadUsage(custom), NbtHeapSize.payload(custom, 2, Long.MAX_VALUE));
        assertThrows(NbtAccounterException.class,
                () -> NbtHeapSize.root(custom, readUsage(custom, Long.MAX_VALUE) - 1));
        assertThrows(NbtAccounterException.class,
                () -> NbtHeapSize.payload(custom, 2, payloadUsage(custom) - 1));
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

    @Test
    void compoundNamesIncludeTheNative120AllocationOverhead() throws Exception {
        assertEquals(57, NbtHeapSize.root(new CompoundTag(), Long.MAX_VALUE));
        for (String key : new String[]{"", "a", "\u0000", "界", "🌍"}) {
            CompoundTag root = new CompoundTag();
            root.putInt(key, 1);
            var name = new ByteArrayOutputStream();
            new DataOutputStream(name).writeUTF(key);
            long expected = 57 + 2 + name.size() + 28 + 2L * key.length() + 4 + 12 + 36;
            assertEquals(expected, readUsage(root, Long.MAX_VALUE));
            assertEquals(expected, NbtHeapSize.root(root, Long.MAX_VALUE));
        }
    }

    @Test
    void compatibilityAccountersKeepQuotasDepthAndInstancesIndependent() throws Exception {
        NbtAccounter first = NbtAccounter.unlimitedHeap();
        NbtAccounter second = NbtAccounter.unlimitedHeap();
        assertNotSame(first, second);
        first.accountBytes(7);
        assertEquals(7, first.getUsage());
        assertEquals(0, second.getUsage());
        assertEquals("\u0000界🌍", second.readUTF("\u0000界🌍"));
        assertEquals(13, second.getUsage());
        assertEquals(0, NbtAccounter.unlimitedHeap().getUsage());

        NbtAccounter bounded = NbtAccounter.create(27);
        byte[] bytes = {0, 0, 0, 3, 0, 1, 2};
        ByteArrayTag.TYPE.load(new DataInputStream(new ByteArrayInputStream(bytes)), 0, bounded);
        assertEquals(27, bounded.getUsage());
        assertThrows(NbtAccounterException.class, () -> ByteArrayTag.TYPE.load(
                new DataInputStream(new ByteArrayInputStream(bytes)), 0, NbtAccounter.create(26)));
        assertThrows(NbtAccounterException.class, () -> bounded.accountBytes(1));
        assertEquals(27, bounded.getUsage());
        assertThrows(IllegalArgumentException.class, () -> bounded.accountBytes(-1));
        assertEquals(27, bounded.getUsage());

        NbtAccounter overflow = NbtAccounter.unlimitedHeap();
        overflow.accountBytes(Long.MAX_VALUE);
        assertThrows(NbtAccounterException.class, () -> overflow.accountBytes(1));
        assertEquals(Long.MAX_VALUE, overflow.getUsage());

        NbtAccounter depth = new NbtAccounter(0, 1);
        depth.pushDepth();
        assertThrows(NbtAccounterException.class, depth::pushDepth);
        depth.popDepth();
        depth.pushDepth();
        depth.popDepth();
        assertThrows(IllegalStateException.class, depth::popDepth);
        assertThrows(NbtAccounterException.class, () -> new NbtAccounter(0, 0).pushDepth());
    }

    @Test
    void corruptPayloadsAndCustomWriteFailuresAreNotCapacityExceptions() throws Exception {
        byte[] missingListType = {0, 0, 0, 0, 1};
        RuntimeException malformed = assertThrows(RuntimeException.class, () -> ListTag.TYPE.load(
                new DataInputStream(new ByteArrayInputStream(missingListType)), 0, NbtAccounter.unlimitedHeap()));
        assertEquals("Missing type on ListTag", malformed.getMessage());
        assertSame(malformed, NbtAccounterException.normalize(malformed));
        byte[] negativeLength = {-1, -1, -1, -1};
        IllegalArgumentException negative = assertThrows(IllegalArgumentException.class, () -> ByteArrayTag.TYPE.load(
                new DataInputStream(new ByteArrayInputStream(negativeLength)), 0, NbtAccounter.unlimitedHeap()));
        assertSame(negative, NbtAccounterException.normalize(negative));
        assertThrows(IOException.class, () -> TagTypes.getType(99).load(
                new DataInputStream(new ByteArrayInputStream(new byte[0])), 0, NbtAccounter.unlimitedHeap()));
        ByteArrayTag unknown = new ByteArrayTag(new byte[0]) {
            @Override public byte getId() { return 99; }
        };
        assertThrows(IOException.class, () -> NbtHeapSize.payload(unknown, 0, Long.MAX_VALUE));
        CompoundTag unknownRoot = new CompoundTag();
        unknownRoot.put("unknown", unknown);
        assertThrows(IOException.class, () -> NbtHeapSize.root(unknownRoot, Long.MAX_VALUE));

        IOException writeFailure = new IOException("Broken custom payload");
        ByteArrayTag broken = new ByteArrayTag(new byte[0]) {
            @Override public void write(DataOutput out) throws IOException { throw writeFailure; }
        };
        assertSame(writeFailure, assertThrows(IOException.class,
                () -> NbtHeapSize.payload(broken, 0, Long.MAX_VALUE)));
        CompoundTag root = new CompoundTag();
        root.put("broken", broken);
        assertSame(writeFailure, assertThrows(IOException.class, () -> NbtHeapSize.root(root, Long.MAX_VALUE)));
        RuntimeException unrelated = new RuntimeException("Broken custom writer");
        CompoundTag brokenRoot = new CompoundTag() {
            @Override public void write(DataOutput out) { throw unrelated; }
        };
        assertSame(unrelated, assertThrows(RuntimeException.class,
                () -> NbtHeapSize.root(brokenRoot, Long.MAX_VALUE)));
    }

    private static void assertMatchesRead(CompoundTag root) throws Exception {
        assertEquals(readUsage(root, Long.MAX_VALUE), NbtHeapSize.root(root, Long.MAX_VALUE));
    }

    private static long readUsage(CompoundTag root, long limit) throws IOException {
        net.minecraft.nbt.NbtAccounter accounter = new net.minecraft.nbt.NbtAccounter(limit);
        NbtIo.read(new DataInputStream(new ByteArrayInputStream(ChunkCodec.serialize(root))), accounter);
        return accounter.getUsage();
    }

    private static long payloadUsage(Tag tag) throws IOException {
        return payloadUsage(tag, 0);
    }

    private static long payloadUsage(Tag tag, int nativeDepth) throws IOException {
        var bytes = new ByteArrayOutputStream();
        tag.write(new DataOutputStream(bytes));
        net.minecraft.nbt.NbtAccounter accounter = new net.minecraft.nbt.NbtAccounter(Long.MAX_VALUE);
        TagTypes.getType(tag.getId()).load(
                new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())), nativeDepth, accounter);
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
