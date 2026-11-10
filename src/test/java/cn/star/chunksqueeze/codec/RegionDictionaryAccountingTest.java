package cn.star.chunksqueeze.codec;

import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.zip.InflaterInputStream;

import static org.junit.jupiter.api.Assertions.*;

class RegionDictionaryAccountingTest {
    @Test
    void estimateTracksStatesObservationsAndSectionPromotionWithoutSideEffects() throws Exception {
        RegionStateDictionary dictionary = RegionStateDictionary.create();
        assertTrue(RegionStateDictionary.exactAccountingSupported());
        long empty = dictionary.estimatedMemoryBytes();
        assertTrue(empty >= 64 * 1024);
        CompoundTag state = new CompoundTag();
        state.putString("Name", "minecraft:stone");
        RegionStateDictionary.Addition addition = dictionary.prepare(list(state));
        assertNotNull(addition);
        assertEquals(empty, dictionary.estimatedMemoryBytes());
        dictionary.append(addition);
        long withState = dictionary.estimatedMemoryBytes();
        assertTrue(withState > empty);
        assertHeapMatchesPersistedNbt(dictionary);

        CompoundTag section = section(1, 1024);
        assertEquals(-1, dictionary.prepareSections(list(section)).mapping[0]);
        long observed = dictionary.estimatedMemoryBytes();
        assertTrue(observed > withState);
        byte[] before = dictionary.compressed();
        long revision = dictionary.revision();
        for (int i = 0; i < 3; i++) assertEquals(observed, dictionary.estimatedMemoryBytes());
        assertEquals(revision, dictionary.revision());
        assertArrayEquals(before, dictionary.compressed());

        RegionStateDictionary.SectionAddition sections = dictionary.prepareSections(list(section.copy()));
        assertEquals(0, sections.mapping[0]);
        assertEquals(observed, dictionary.estimatedMemoryBytes());
        dictionary.appendSections(sections);
        assertTrue(dictionary.estimatedMemoryBytes() > observed);
        assertEquals(0, ((Map<?, ?>) field(dictionary, "recent")).size());
        assertEquals(1, dictionary.sectionCount());
        assertHeapMatchesPersistedNbt(dictionary);

        RegionStateDictionary restored = RegionStateDictionary.fromCompressed(dictionary.compressed());
        assertEquals(dictionary.estimatedMemoryBytes(), restored.estimatedMemoryBytes());
        assertEquals(dictionary.stateSize(0), restored.stateSize(0));
        assertEquals(dictionary.sectionSize(0), restored.sectionSize(0));
    }

    @Test
    void rawObservationEvictionAndOversizedEntriesKeepTheEstimateBounded() throws Exception {
        RegionStateDictionary dictionary = RegionStateDictionary.create();
        long previous = dictionary.estimatedMemoryBytes();
        for (int i = 0; i < 5; i++) {
            dictionary.prepareSections(list(section(i, 200 * 1024)));
            long next = dictionary.estimatedMemoryBytes();
            assertTrue(next > previous);
            previous = next;
        }
        dictionary.prepareSections(list(section(5, 200 * 1024)));
        assertEquals(previous, dictionary.estimatedMemoryBytes());
        assertEquals(5, ((Map<?, ?>) field(dictionary, "recent")).size());
        assertTrue((long) field(dictionary, "recentRaw") <= 1024 * 1024);
        assertEquals(-1, dictionary.prepareSections(list(section(0, 200 * 1024))).mapping[0]);
        assertEquals(previous, dictionary.estimatedMemoryBytes());
        assertEquals(-1, dictionary.prepareSections(list(section(99, 256 * 1024))).mapping[0]);
        assertEquals(previous, dictionary.estimatedMemoryBytes());
        assertEquals(0, dictionary.revision());
    }

    @Test
    void cachedRestorationFailureRemainsLazyForAValidDictionaryAtTheDepthBoundary() throws Exception {
        CompoundTag shallow = new CompoundTag();
        shallow.putString("Name", "minecraft:stone");
        CompoundTag deep = NbtHeapSizeTest.nested(510);
        ListTag states = list(shallow);
        states.add(deep);
        CompoundTag root = RegionStateDictionaryTest.root(UUID.randomUUID(), states);
        RegionStateDictionary dictionary = RegionStateDictionary.fromCompressed(RegionStateDictionaryTest.compress(root));
        assertEquals(2, dictionary.size());
        assertEquals(NbtSize.payload(shallow, 5, ChunkCodec.MAX_BYTES), dictionary.stateSize(0));
        for (int i = 0; i < 2; i++) {
            IOException invalid = assertThrows(IOException.class, () -> dictionary.stateSize(1));
            assertTrue(invalid.getMessage().contains("depth limit"));
        }
        assertEquals(deep, dictionary.copyState(1));
        assertThrows(IOException.class, () -> dictionary.stateSize(-1));
        assertThrows(IOException.class, () -> dictionary.sectionSize(0));
    }

    @Test
    void persistentKeysAndSizeCachesOwnTheirStateAndSectionCopies() throws Exception {
        RegionStateDictionary dictionary = RegionStateDictionary.create();
        CompoundTag state = new CompoundTag();
        state.putString("Name", "minecraft:stone");
        CompoundTag original = state.copy();
        dictionary.append(dictionary.prepare(list(state)));
        state.putString("Name", "minecraft:dirt");
        RegionStateDictionary.Addition known = dictionary.prepareExisting(list(original));
        assertNotNull(known);
        assertEquals(0, known.mapping[0]);
        assertNull(dictionary.prepareExisting(list(state)));
        assertEquals(NbtSize.payload(original, 5, ChunkCodec.MAX_BYTES), dictionary.stateSize(0));
        dictionary.copyState(0).putString("Name", "changed externally");
        assertEquals(original, dictionary.copyState(0));

        CompoundTag section = section(1, 1024);
        CompoundTag before = section.copy();
        dictionary.prepareSections(list(section));
        section.getByteArray("blob")[0] = 1;
        assertEquals(-1, dictionary.prepareSections(list(section)).mapping[0]);
        dictionary.appendSections(dictionary.prepareSections(list(before)));
        before.getByteArray("blob")[0] = 2;
        assertEquals(0, dictionary.copySection(0).getByteArray("blob")[0]);
        assertEquals(NbtSize.payload(dictionary.copySection(0), 2, ChunkCodec.MAX_BYTES), dictionary.sectionSize(0));
    }

    @Test
    void exactWrapperComputesHashOnlyAtConstruction() {
        int[] calls = {0};
        CompoundTag tag = new CompoundTag() {
            @Override public int hashCode() {
                calls[0]++;
                return super.hashCode();
            }
        };
        tag.putInt("value", 1);
        RegionStateDictionary.ExactCompound key = new RegionStateDictionary.ExactCompound(tag);
        int hash = key.hashCode();
        assertEquals(hash, key.hashCode());
        assertEquals(1, calls[0]);
        tag.putInt("value", 2);
        assertEquals(hash, key.hashCode());
        assertNotEquals(hash, new RegionStateDictionary.ExactCompound(tag).hashCode());
        assertEquals(2, calls[0]);
    }

    private static void assertHeapMatchesPersistedNbt(RegionStateDictionary dictionary) throws Exception {
        NbtAccounter accounter = NbtAccounter.create(ChunkCodec.MAX_BYTES);
        NbtIo.read(new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(dictionary.compressed()))), accounter);
        assertEquals(accounter.getUsage(), (long) field(dictionary, "heapSize"));
    }

    private static Object field(Object value, String name) throws Exception {
        Field field = value.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(value);
    }

    private static CompoundTag section(int id, int bytes) {
        CompoundTag section = new CompoundTag();
        section.putInt("unique", id);
        section.putByteArray("blob", new byte[bytes]);
        return section;
    }

    private static ListTag list(CompoundTag entry) {
        ListTag list = new ListTag();
        list.add(entry);
        return list;
    }
}
