package cn.star.chunksqueeze.storage;

import cn.star.chunksqueeze.codec.*;
import net.minecraft.nbt.*;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.Path;
import java.util.zip.DeflaterOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class DictionaryCacheTest {
    @TempDir Path folder;

    @Test
    void lruHonorsMemoryAndEntryLimitsAndRefreshesWeights() throws Exception {
        RegionStateDictionary a = dictionary(1), b = dictionary(1), large = dictionary(1024 * 1024);
        long weight = a.estimatedMemoryBytes();
        assertEquals(weight, b.estimatedMemoryBytes());
        var cache = new DictionaryCache<Integer>(weight * 2, 3);
        cache.put(1, a); cache.put(2, b);
        assertSame(a, cache.get(1));
        cache.put(3, a);
        assertNull(cache.get(2));
        assertSame(a, cache.get(1));
        assertSame(a, cache.get(3));
        assertEquals(weight * 2, cache.bytes());
        cache.put(1, large);
        assertNull(cache.get(1));
        assertEquals(weight, cache.bytes());
        cache.remove(3);
        assertEquals(0, cache.bytes());
        var countLimited = new DictionaryCache<Integer>(Long.MAX_VALUE, 1);
        countLimited.put(1, a); countLimited.put(2, b);
        assertNull(countLimited.get(1));
        assertSame(b, countLimited.get(2));
        assertEquals(1, countLimited.size());
    }

    @Test
    void noCacheForOversizedDictionaryAndBudgetIsBounded() throws Exception {
        var dictionary = dictionary(1);
        var cache = new DictionaryCache<Object>(dictionary.estimatedMemoryBytes() - 1, 64);
        cache.put(this, dictionary);
        assertNull(cache.get(this));
        assertEquals(0, cache.bytes());
        assertEquals(16L << 20, DictionaryCache.defaultBudget(128L << 20));
        assertEquals(64L << 20, DictionaryCache.defaultBudget(2L << 30));
        assertEquals(128L << 20, DictionaryCache.defaultBudget(24L << 30));
    }

    @Test
    void evictionCloseAndFailedEncodeKeepCommittedDataAuthoritative() throws Exception {
        var cache = new DictionaryCache<CompactRegion>(128L << 20, 1);
        CompoundTag original = chunk();
        ChunkPos first = new ChunkPos(0, 0), second = new ChunkPos(32, 0);
        try (CompactRegionStore store = new CompactRegionStore(folder, cache)) {
            store.writeEncoded(first, original, new ChunkCodec.Options(0, false));
            store.writeEncoded(second, original, new ChunkCodec.Options(0, false));
            assertEquals(1, cache.size());
            assertEquals(original, SectionCodec.decode(store.read(first), null));
            CompoundTag invalid = original.copy();
            invalid.put(SectionCodec.KEY, new CompoundTag());
            assertThrows(IOException.class, () -> store.writeEncoded(first, invalid, new ChunkCodec.Options(0, false)));
            assertEquals(0, cache.size());
            assertEquals(original, SectionCodec.decode(store.read(first), null));
            store.closeRegion(0, 0);
            assertEquals(0, cache.size());
            assertEquals(original, SectionCodec.decode(store.read(first), null));
        }
        assertEquals(0, cache.size());
        assertEquals(0, cache.bytes());
        try (CompactRegionStore reopened = new CompactRegionStore(folder, cache)) {
            assertEquals(original, SectionCodec.decode(reopened.read(first), null));
            assertEquals(original, SectionCodec.decode(reopened.read(second), null));
        }
        assertEquals(0, cache.size());
    }

    private static RegionStateDictionary dictionary(int length) throws Exception {
        CompoundTag root = new CompoundTag();
        root.putInt("version", 1);root.putLong("idMost", 1);root.putLong("idLeast", 2);
        ListTag states = new ListTag();
        CompoundTag state = new CompoundTag();state.putString("Name", "test:state");
        state.putByteArray("unknown", new byte[length]);states.add(state);root.put("states", states);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes))) { NbtIo.write(root, out); }
        return RegionStateDictionary.fromCompressed(bytes.toByteArray());
    }

    private static CompoundTag chunk() throws Exception {
        CompoundTag root = new CompoundTag();root.putString("Status", "minecraft:full");
        ListTag sections = new ListTag();
        for (int y = 0; y < 8; y++) {
            CompoundTag section = new CompoundTag();section.putByte("Y", (byte) y);
            ListTag palette = new ListTag();
            for (int i = 0; i < 32; i++) {
                CompoundTag state = new CompoundTag();state.putString("Name", "test:long_machine_variant_" + i);
                CompoundTag properties = new CompoundTag();properties.putString("mode", "some_long_mode_" + i);
                state.put("Properties", properties);palette.add(state);
            }
            int[] values = new int[4096];for (int i = 0; i < values.length; i++) values[i] = i % 32;
            CompoundTag states = new CompoundTag();states.put("palette", palette);
            states.putLongArray("data", IndexCodec.packVanilla(values, 32));section.put("block_states", states);
            sections.add(section);
        }
        root.put("sections", sections);return root;
    }
}
