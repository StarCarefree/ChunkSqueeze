package cn.star.chunksqueeze.codec;

import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.util.UUID;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import static org.junit.jupiter.api.Assertions.*;

class RegionStateDictionaryTest {
    @Test
    void emptyDictionaryHasStableIdentityAndExactNbtFormat() throws Exception {
        RegionStateDictionary dictionary = RegionStateDictionary.create();
        assertEquals(0, dictionary.size());
        assertEquals(0, dictionary.revision());
        byte[] bytes = dictionary.compressed();
        CompoundTag root = NbtIo.read(new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(bytes))),
                NbtAccounter.create(ChunkCodec.MAX_BYTES));
        assertEquals(1, root.getInt("version"));
        assertEquals(dictionary.identity(), new UUID(root.getLong("idMost"), root.getLong("idLeast")));
        assertTrue(root.contains("states", Tag.TAG_LIST));
        assertTrue(root.getList("states", Tag.TAG_COMPOUND).isEmpty());
        RegionStateDictionary restored = RegionStateDictionary.fromCompressed(bytes);
        assertEquals(dictionary.identity(), restored.identity());
        assertEquals(0, restored.size());
        bytes[0] ^= 1;
        assertEquals(dictionary.identity(), RegionStateDictionary.fromCompressed(dictionary.compressed()).identity());
    }

    @Test
    void rejectsInvalidHeadersTypesAndTruncatedStreams() throws Exception {
        CompoundTag root = root(UUID.randomUUID(), new ListTag());
        root.putInt("version", 2);
        assertThrows(IOException.class, () -> RegionStateDictionary.fromCompressed(compress(root)));
        root.putInt("version", 1);
        root.putInt("idMost", 0);
        assertThrows(IOException.class, () -> RegionStateDictionary.fromCompressed(compress(root)));
        root.putLong("idMost", 0);
        ListTag wrong = new ListTag();
        wrong.add(StringTag.valueOf("wrong"));
        root.put("states", wrong);
        assertThrows(IOException.class, () -> RegionStateDictionary.fromCompressed(compress(root)));
        assertThrows(IOException.class, () -> RegionStateDictionary.fromCompressed(new byte[]{1, 2, 3}));
        byte[] valid = RegionStateDictionary.create().compressed();
        assertThrows(IOException.class, () -> RegionStateDictionary.fromCompressed(java.util.Arrays.copyOf(valid, valid.length - 2)));
        assertThrows(IOException.class, () -> RegionStateDictionary.fromCompressed(java.util.Arrays.copyOf(valid, valid.length + 1)));
        byte[] concatenated = java.util.Arrays.copyOf(valid, valid.length * 2);
        System.arraycopy(valid, 0, concatenated, valid.length, valid.length);
        assertThrows(IOException.class, () -> RegionStateDictionary.fromCompressed(concatenated));
        byte[] checksum = valid.clone();
        checksum[checksum.length - 1] ^= 1;
        assertThrows(IOException.class, () -> RegionStateDictionary.fromCompressed(checksum));
        var bytes = new ByteArrayOutputStream();
        try (var out = new DeflaterOutputStream(bytes)) {
            out.write(ChunkCodec.serialize(root(UUID.randomUUID(), new ListTag())));
            out.write(1);
        }
        assertThrows(IOException.class, () -> RegionStateDictionary.fromCompressed(bytes.toByteArray()));
    }

    @Test
    void rejectsEntryCountRawSizeAndHeapQuotaOverflow() throws Exception {
        ListTag entries = new ListTag();
        CompoundTag empty = new CompoundTag();
        for (int i = 0; i <= 65536; i++) entries.add(empty);
        byte[] tooMany = compress(root(UUID.randomUUID(), entries));
        assertThrows(IOException.class, () -> RegionStateDictionary.fromCompressed(tooMany));
        entries = new ListTag();
        CompoundTag entry = new CompoundTag();
        entry.putByteArray("blob", new byte[4 * 1024 * 1024]);
        entries.add(entry);
        byte[] tooLarge = compress(root(UUID.randomUUID(), entries));
        assertThrows(IOException.class, () -> RegionStateDictionary.fromCompressed(tooLarge));

        ListTag strings = new ListTag();
        for (int i = 0; i < 2_000_000; i++) strings.add(StringTag.valueOf(""));
        entry = new CompoundTag();
        entry.put("strings", strings);
        entries = new ListTag();
        entries.add(entry);
        CompoundTag heapHeavy = root(UUID.randomUUID(), entries);
        assertTrue(ChunkCodec.serialize(heapHeavy).length <= 4 * 1024 * 1024);
        byte[] tooHeavy = compress(heapHeavy);
        assertThrows(IOException.class, () -> RegionStateDictionary.fromCompressed(tooHeavy));
    }

    static CompoundTag root(UUID identity, ListTag states) {
        CompoundTag root = new CompoundTag();
        root.putInt("version", 1);
        root.putLong("idMost", identity.getMostSignificantBits());
        root.putLong("idLeast", identity.getLeastSignificantBits());
        root.put("states", states);
        return root;
    }

    static byte[] compress(CompoundTag root) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DeflaterOutputStream(bytes)) {
            NbtIo.write(root, new DataOutputStream(out));
        }
        return bytes.toByteArray();
    }
}
