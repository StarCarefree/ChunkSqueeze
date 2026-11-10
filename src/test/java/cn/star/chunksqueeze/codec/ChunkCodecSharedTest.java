package cn.star.chunksqueeze.codec;

import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.util.*;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

class ChunkCodecSharedTest {
    private static final ChunkCodec.Options ALWAYS = new ChunkCodec.Options(0, false);

    @Test
    void supportsVanillaV1AndV2AndRequiresDictionaryOnlyForV2() throws Exception {
        RegionStateDictionary dictionary = RegionStateDictionary.create();
        CompoundTag original = CodecTest.fixture();
        assertSame(original, ChunkCodec.decode(original, dictionary));
        CompoundTag v1 = ChunkCodec.encode(original, ALWAYS).tag();
        assertEquals(1, v1.getCompound(ChunkCodec.KEY).getInt("version"));
        assertEquals(original, ChunkCodec.decode(v1));
        assertEquals(original, ChunkCodec.decode(v1, dictionary));
        CompoundTag v2 = shared(original, dictionary);
        assertEquals(original, ChunkCodec.decode(v2, dictionary));
        assertThrows(IOException.class, () -> ChunkCodec.decode(v2));
        assertEquals(20, dictionary.size());
    }

    @Test
    void sharesCompleteCompoundsAndPreservesPaletteOrderPaddingAndUnknownFields() throws Exception {
        CompoundTag original = CodecTest.fixture();
        ListTag sections = original.getList("sections", Tag.TAG_COMPOUND);
        for (int s = 0; s < sections.size(); s++) {
            CompoundTag states = sections.getCompound(s).getCompound("block_states");
            ListTag palette = states.getList("palette", Tag.TAG_COMPOUND);
            for (int i = 0; i < palette.size(); i++) {
                CompoundTag state = palette.getCompound(i);
                state.putByteArray("mod:bytes", new byte[]{(byte) i, 2, 3});
                state.putIntArray("mod:ints", new int[]{i, 5});
                CompoundTag extension = new CompoundTag();
                extension.putString("text", "完整状态 🌍 \u0000");
                state.put("mod:extension", extension);
            }
            if ((s & 1) == 1) {
                ListTag reversed = new ListTag();
                for (int i = palette.size() - 1; i >= 0; i--) reversed.add(palette.getCompound(i));
                states.put("palette", reversed);
            }
            states.getLongArray("data")[0] |= 1L << 63;
            states.getLongArray("data")[states.getLongArray("data").length - 1] |= 1L << 63;
        }
        sections.getCompound(0).getCompound("block_states").putString("mod:unknown", "保留原样");
        CompoundTag before = original.copy();
        RegionStateDictionary dictionary = RegionStateDictionary.create();
        CompoundTag encoded = shared(original, dictionary);
        assertEquals(before, original);
        assertEquals(before, ChunkCodec.decode(encoded, dictionary));
        assertEquals(sections.getCompound(0).getCompound("block_states"),
                encoded.getList("sections", Tag.TAG_COMPOUND).getCompound(0).getCompound("block_states"));
        byte[] snapshot = dictionary.compressed();
        CompoundTag decoded = ChunkCodec.decode(encoded, dictionary);
        CompoundTag first = palette(decoded, 1).getCompound(0);
        CompoundTag sameState = palette(decoded, 3).getCompound(0);
        assertNotSame(first, sameState);
        first.getByteArray("mod:bytes")[0] = 99;
        first.getIntArray("mod:ints")[0] = 99;
        first.getCompound("mod:extension").putString("text", "changed");
        decoded.getLongArray("other-array")[0] = 1;
        decoded.getList("sections", Tag.TAG_COMPOUND).getCompound(0).getByteArray("SkyLight")[0] = 1;
        assertEquals(before, ChunkCodec.decode(encoded, dictionary));
        assertArrayEquals(snapshot, dictionary.compressed());
        original.getList("sections", Tag.TAG_COMPOUND).getCompound(2).getCompound("block_states")
                .getList("palette", Tag.TAG_COMPOUND).getCompound(0).putString("Name", "changed:input");
        assertEquals(before, ChunkCodec.decode(encoded, dictionary));
    }

    @Test
    void appendKeepsEarlierChunksAndDictionarySnapshotsStable() throws Exception {
        RegionStateDictionary dictionary = RegionStateDictionary.create();
        CompoundTag first = CodecTest.fixture();
        CompoundTag storedFirst = shared(first, dictionary);
        long revision = dictionary.revision();
        byte[] snapshot = dictionary.compressed();
        RegionStateDictionary oldSnapshot = RegionStateDictionary.fromCompressed(snapshot);
        assertEquals(first, ChunkCodec.decode(storedFirst, oldSnapshot));
        CompoundTag repeated = first.copy();
        repeated.putInt("xPos", 1);
        CompoundTag storedRepeated = shared(repeated, dictionary);
        assertEquals(revision, dictionary.revision());
        CompoundTag next = first.copy();
        next.putInt("xPos", 2);
        for (Tag section : next.getList("sections", Tag.TAG_COMPOUND)) {
            ((CompoundTag) section).getCompound("block_states").getList("palette", Tag.TAG_COMPOUND)
                    .getCompound(0).putString("Name", "test:new_state");
        }
        CompoundTag storedNext = shared(next, dictionary);
        assertEquals(revision + 1, dictionary.revision());
        assertEquals(first, ChunkCodec.decode(storedFirst, dictionary));
        assertEquals(repeated, ChunkCodec.decode(storedRepeated, dictionary));
        assertEquals(next, ChunkCodec.decode(storedNext, dictionary));
        assertThrows(IOException.class, () -> ChunkCodec.decode(storedNext, oldSnapshot));
        RegionStateDictionary restored = RegionStateDictionary.fromCompressed(dictionary.compressed());
        assertEquals(dictionary.identity(), restored.identity());
        assertEquals(next, ChunkCodec.decode(storedNext, restored));
        assertEquals(first, ChunkCodec.decode(storedFirst, restored));
    }

    @Test
    void rejectsWrongUuidCrcAndBothKindsOfOutOfRangeId() throws Exception {
        RegionStateDictionary dictionary = RegionStateDictionary.create();
        CompoundTag stored = shared(CodecTest.fixture(), dictionary);
        assertThrows(IOException.class, () -> ChunkCodec.decode(stored, RegionStateDictionary.create()));
        CompoundTag corrupt = stored.copy();
        corrupt.getCompound(ChunkCodec.KEY).getByteArray("payload")[20] ^= 1;
        assertThrows(IOException.class, () -> ChunkCodec.decode(corrupt, dictionary));
        CompoundTag wrongIdentity = stored.copy();
        wrongIdentity.getCompound(ChunkCodec.KEY).getByteArray("payload")[4] ^= 1;
        checksum(wrongIdentity);
        assertThrows(IOException.class, () -> ChunkCodec.decode(wrongIdentity, dictionary));
        CompoundTag root = root();
        assertThrows(IOException.class, () -> ChunkCodec.decode(envelope(root, dictionary,
                new int[]{dictionary.size()}, new int[]{0}, new byte[]{0}), dictionary));
        assertThrows(IOException.class, () -> ChunkCodec.decode(envelope(root(), dictionary,
                new int[]{0}, new int[]{1}, new byte[]{0}), dictionary));
        CompoundTag trailing = stored.copy();
        byte[] payload = trailing.getCompound(ChunkCodec.KEY).getByteArray("payload");
        trailing.getCompound(ChunkCodec.KEY).putByteArray("payload", Arrays.copyOf(payload, payload.length + 1));
        checksum(trailing);
        assertThrows(IOException.class, () -> ChunkCodec.decode(trailing, dictionary));
    }

    @Test
    void evaluatesSharedCandidateBeforeApplyingMinimumSavingAndAllocation() throws Exception {
        CompoundTag original = CodecTest.fixture();
        RegionStateDictionary dictionary = RegionStateDictionary.create();
        ChunkCodec.Result v1 = ChunkCodec.encode(original, ALWAYS, 1, 0);
        ChunkCodec.Result v2 = ChunkCodec.encode(original, ALWAYS, 1, 0, dictionary);
        assertEquals(2, v2.tag().getCompound(ChunkCodec.KEY).getInt("version"));
        int threshold = v1.originalBytes() - v1.storedBytes() + 1;
        assertTrue(v2.originalBytes() - v2.storedBytes() >= threshold);
        ChunkCodec.Options policy = new ChunkCodec.Options(threshold, true);
        assertFalse(ChunkCodec.encode(original, policy, 1, 0).optimized());
        RegionStateDictionary fresh = RegionStateDictionary.create();
        assertEquals(2, ChunkCodec.encode(original, policy, 1, 0, fresh).tag()
                .getCompound(ChunkCodec.KEY).getInt("version"));
        RegionStateDictionary unused = RegionStateDictionary.create();
        assertFalse(ChunkCodec.encode(original, new ChunkCodec.Options(Integer.MAX_VALUE, false), 1, 0, unused).optimized());
        assertEquals(0, unused.size());
        assertFalse(ChunkCodec.encode(original, new ChunkCodec.Options(0, true), Integer.MAX_VALUE, 0, unused).optimized());
        assertEquals(0, unused.size());
    }

    @Test
    void dictionaryEntryAndRawCapacityFallBackToUnchangedV1Choice() throws Exception {
        CompoundTag original = CodecTest.fixture();
        ChunkCodec.Result expected = ChunkCodec.encode(original, ALWAYS, 1, 0);
        assertEquals(1, expected.tag().getCompound(ChunkCodec.KEY).getInt("version"));
        for (boolean entryLimit : new boolean[]{false, true}) {
            ListTag entries = new ListTag();
            if (entryLimit) {
                for (int i = 0; i < 65536; i++) {
                    CompoundTag state = new CompoundTag();
                    state.putInt("unique", i);
                    entries.add(state);
                }
            } else {
                CompoundTag state = new CompoundTag();
                state.putByteArray("blob", new byte[4 * 1024 * 1024 - 128]);
                entries.add(state);
            }
            RegionStateDictionary dictionary = RegionStateDictionary.fromCompressed(
                    RegionStateDictionaryTest.compress(RegionStateDictionaryTest.root(UUID.randomUUID(), entries)));
            byte[] before = dictionary.compressed();
            ChunkCodec.Result actual = ChunkCodec.encode(original, ALWAYS, 1, 0, dictionary);
            assertEquals(expected.tag(), actual.tag());
            assertEquals(expected.storedBytes(), actual.storedBytes());
            assertEquals(original, ChunkCodec.decode(actual.tag(), dictionary));
            assertArrayEquals(before, dictionary.compressed());
        }
    }

    @Test
    void cumulativeHeapCapacityFallsBackBeforeAndAfterDictionaryReload() throws Exception {
        ListTag strings = new ListTag();
        for (int i = 0; i < 900_000; i++) strings.add(StringTag.valueOf(""));
        CompoundTag firstState = new CompoundTag();
        firstState.putString("Name", "test:first");
        firstState.put("strings", strings);
        CompoundTag first = uniformChunk(firstState);
        RegionStateDictionary dictionary = RegionStateDictionary.create();
        shared(first, dictionary);
        assertEquals(1, dictionary.size());
        CompoundTag secondState = firstState.copy();
        secondState.putString("Name", "test:second");
        CompoundTag second = uniformChunk(secondState);
        ChunkCodec.Result expected = ChunkCodec.encode(second, ALWAYS, 1, 0);
        assertEquals(1, expected.tag().getCompound(ChunkCodec.KEY).getInt("version"));
        byte[] before = dictionary.compressed();
        RegionStateDictionary reloaded = RegionStateDictionary.fromCompressed(before);
        for (RegionStateDictionary full : new RegionStateDictionary[]{dictionary, reloaded}) {
            assertEquals(expected.tag(), ChunkCodec.encode(second, ALWAYS, 1, 0, full).tag());
            assertEquals(1, full.size());
            assertArrayEquals(before, full.compressed());
        }
    }

    private static CompoundTag uniformChunk(CompoundTag state) {
        CompoundTag root = root();
        ListTag sections = root.getList("sections", Tag.TAG_COMPOUND);
        sections.add(new CompoundTag());
        for (Tag section : sections) {
            ListTag palette = new ListTag();
            palette.add(state.copy());
            CompoundTag blockStates = new CompoundTag();
            blockStates.put("palette", palette);
            ((CompoundTag) section).put("block_states", blockStates);
        }
        return root;
    }

    @Test
    void validatesExpansionDepthAndMetadataBeforeAnyCopies() throws Exception {
        for (int children : new int[]{506, 507}) {
            CompoundTag state = new CompoundTag();
            CompoundTag current = state;
            for (int i = 0; i < children; i++) {
                CompoundTag child = new CompoundTag();
                current.put("nested", child);
                current = child;
            }
            RegionStateDictionary dictionary = withState(state);
            CompoundTag stored = envelope(root(), dictionary, new int[]{0}, new int[]{0}, new byte[]{0});
            if (children == 506) assertNotNull(ChunkCodec.decode(stored, dictionary));
            else assertThrows(IOException.class, () -> ChunkCodec.decode(stored, dictionary));
        }
        CompoundTag state = new CompoundTag();
        state.putByteArray("large", new byte[32 * 1024]);
        RegionStateDictionary dictionary = withState(state);
        CompoundTag root = root();
        root.put("guarded", new CompoundTag() {
            @Override public CompoundTag copy() { throw new AssertionError("Copied before full validation"); }
        });
        CompoundTag expanded = envelope(root, dictionary, new int[]{0}, new int[4096],
                IndexCodec.encodeValues(new int[4096], 4096, 3));
        assertThrows(IOException.class, () -> ChunkCodec.decode(expanded, dictionary));
        root = root();
        CompoundTag metadata = new CompoundTag() {
            @Override public CompoundTag copy() { throw new AssertionError("Copied invalid metadata"); }
        };
        root.put("metadata", metadata);
        ByteArrayTag shared = new ByteArrayTag(new byte[1024 * 1024]);
        for (int i = 0; i < 64; i++) metadata.put("array" + i, shared);
        CompoundTag oversized = envelope(root, dictionary, new int[]{0}, new int[]{0}, new byte[]{0});
        assertThrows(IOException.class, () -> ChunkCodec.decode(oversized, dictionary));
        CompoundTag input = CodecTest.fixture();
        input.put("metadata", metadata);
        assertSame(input, ChunkCodec.encode(input, ALWAYS, 1, 0, dictionary).tag());
    }

    private static RegionStateDictionary withState(CompoundTag state) throws IOException {
        ListTag entries = new ListTag();
        entries.add(state);
        return RegionStateDictionary.fromCompressed(RegionStateDictionaryTest.compress(
                RegionStateDictionaryTest.root(UUID.randomUUID(), entries)));
    }

    private static ListTag palette(CompoundTag root, int section) {
        return root.getList("sections", Tag.TAG_COMPOUND).getCompound(section)
                .getCompound("block_states").getList("palette", Tag.TAG_COMPOUND);
    }

    private static CompoundTag shared(CompoundTag original, RegionStateDictionary dictionary) throws IOException {
        CompoundTag stored = ChunkCodec.encode(original, ALWAYS, 1, 0, dictionary).tag();
        assertEquals(2, stored.getCompound(ChunkCodec.KEY).getInt("version"));
        return stored;
    }

    private static CompoundTag root() {
        CompoundTag root = new CompoundTag();
        root.putString("Status", "minecraft:full");
        ListTag sections = new ListTag();
        sections.add(new CompoundTag());
        root.put("sections", sections);
        return root;
    }

    private static CompoundTag envelope(CompoundTag root, RegionStateDictionary dictionary, int[] mapping,
                                        int[] palette, byte[] indices) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        out.writeInt(0x43535132);
        out.writeLong(dictionary.identity().getMostSignificantBits());
        out.writeLong(dictionary.identity().getLeastSignificantBits());
        IndexCodec.writeVarInt(out, mapping.length);
        for (int id : mapping) IndexCodec.writeVarInt(out, id);
        IndexCodec.writeVarInt(out, 1);
        IndexCodec.writeVarInt(out, 0);
        IndexCodec.writeVarInt(out, palette.length);
        for (int id : palette) IndexCodec.writeVarInt(out, id);
        IndexCodec.writeVarInt(out, indices.length);
        out.write(indices);
        CompoundTag marker = new CompoundTag();
        marker.putInt("version", 2);
        marker.putByteArray("payload", bytes.toByteArray());
        root.put(ChunkCodec.KEY, marker);
        checksum(root);
        return root;
    }

    private static void checksum(CompoundTag stored) {
        CompoundTag marker = stored.getCompound(ChunkCodec.KEY);
        CRC32 crc = new CRC32();
        crc.update(marker.getByteArray("payload"));
        marker.putLong("crc32", crc.getValue());
    }
}
