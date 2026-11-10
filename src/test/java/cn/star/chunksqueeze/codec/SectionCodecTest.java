package cn.star.chunksqueeze.codec;

import cn.star.chunksqueeze.compat.NbtAccounter;

import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.CRC32;
import java.util.zip.InflaterInputStream;

import static org.junit.jupiter.api.Assertions.*;

class SectionCodecTest {
    private static final ChunkCodec.Options ALWAYS = new ChunkCodec.Options(0, false);

    @Test
    void observesUniqueChunksThenSharesExactSectionsAcrossChunksAndReloads() throws Exception {
        RegionStateDictionary dictionary = RegionStateDictionary.create();
        CompoundTag original = chunk(section(1, 8192));
        CompoundTag before = original.copy();
        ChunkCodec.Result first = SectionCodec.encode(original, ALWAYS, dictionary);
        assertFalse(first.tag().contains(SectionCodec.KEY));
        assertEquals(0, dictionary.sectionCount());
        long revision = dictionary.revision();
        byte[] oldSnapshot = dictionary.compressed();
        assertEquals(1, dictionaryRoot(oldSnapshot).getInt("version"));

        CompoundTag next = original.copy();
        next.putInt("xPos", 1);
        ChunkCodec.Result second = SectionCodec.encode(next, ALWAYS, dictionary);
        assertTrue(second.tag().contains(SectionCodec.KEY));
        assertEquals(1, dictionary.sectionCount());
        assertEquals(revision + 1, dictionary.revision());
        assertTrue(second.tag().getList("sections", Tag.TAG_COMPOUND).getCompound(0).isEmpty());
        assertEquals(before, original);
        assertEquals(original, SectionCodec.decode(first.tag(), dictionary));
        assertEquals(next, SectionCodec.decode(second.tag(), dictionary));
        assertThrows(IOException.class, () -> SectionCodec.decode(second.tag(), RegionStateDictionary.fromCompressed(oldSnapshot)));

        CompoundTag root = dictionaryRoot(dictionary.compressed());
        assertEquals(2, root.getInt("version"));
        assertEquals(Set.of("version", "idMost", "idLeast", "states", "sections"), root.getAllKeys());
        assertEquals(before.getList("sections", Tag.TAG_COMPOUND), root.getList("sections", Tag.TAG_COMPOUND));
        RegionStateDictionary restored = RegionStateDictionary.fromCompressed(dictionary.compressed());
        assertEquals(dictionary.identity(), restored.identity());
        assertEquals(dictionary.revision(), restored.revision());
        assertEquals(next, SectionCodec.decode(second.tag(), restored));
        CompoundTag third = original.copy();
        third.putInt("xPos", 2);
        assertTrue(SectionCodec.encode(third, ALWAYS, restored).tag().contains(SectionCodec.KEY));
        assertEquals(1, restored.sectionCount());
    }

    @Test
    void everySectionFieldAndPaletteOrderAndPaddingParticipateInEquality() throws Exception {
        List<Consumer<CompoundTag>> changes = List.of(
                section -> section.putByte("Y", (byte) 1),
                section -> section.getByteArray("SkyLight")[0] ^= 1,
                section -> section.getCompound("biomes").putString("mod:extra", "changed"),
                section -> section.putString("mod:unknown", "changed"),
                section -> {
                    ListTag palette = section.getCompound("block_states").getList("palette", Tag.TAG_COMPOUND);
                    Tag first = palette.get(0);
                    palette.set(0, palette.get(1));
                    palette.set(1, first);
                },
                section -> section.getCompound("block_states").getLongArray("data")[0] ^= 1L << 63,
                section -> section.getCompound("block_states").putString("mod:unknown", "changed"));
        for (Consumer<CompoundTag> change : changes) {
            RegionStateDictionary dictionary = RegionStateDictionary.create();
            CompoundTag first = chunk(section(7, 4096));
            SectionCodec.encode(first, ALWAYS, dictionary);
            CompoundTag storedFirst = SectionCodec.encode(first, ALWAYS, dictionary).tag();
            assertTrue(storedFirst.contains(SectionCodec.KEY));
            CompoundTag changed = first.copy();
            change.accept(changed.getList("sections", Tag.TAG_COMPOUND).getCompound(0));
            CompoundTag storedChanged = SectionCodec.encode(changed, ALWAYS, dictionary).tag();
            assertFalse(storedChanged.contains(SectionCodec.KEY));
            assertEquals(1, dictionary.sectionCount());
            assertEquals(changed, SectionCodec.decode(storedChanged, dictionary));
            CompoundTag repeatedChanged = SectionCodec.encode(changed, ALWAYS, dictionary).tag();
            assertTrue(repeatedChanged.contains(SectionCodec.KEY));
            assertEquals(2, dictionary.sectionCount());
            assertEquals(first, SectionCodec.decode(storedFirst, dictionary));
            assertEquals(changed, SectionCodec.decode(repeatedChanged, dictionary));
        }
    }

    @Test
    void updatesAndReturnedArraysCannotModifyOtherChunksOrDictionary() throws Exception {
        RegionStateDictionary dictionary = RegionStateDictionary.create();
        CompoundTag original = chunk(section(9, 8192));
        SectionCodec.encode(original, ALWAYS, dictionary);
        CompoundTag storedA = SectionCodec.encode(original, ALWAYS, dictionary).tag();
        CompoundTag storedB = SectionCodec.encode(original, ALWAYS, dictionary).tag();
        byte[] snapshot = dictionary.compressed();
        CompoundTag decodedA = SectionCodec.decode(storedA, dictionary);
        CompoundTag decodedB = SectionCodec.decode(storedB, dictionary);
        CompoundTag a = decodedA.getList("sections", Tag.TAG_COMPOUND).getCompound(0);
        CompoundTag b = decodedB.getList("sections", Tag.TAG_COMPOUND).getCompound(0);
        assertNotSame(a, b);
        a.getByteArray("SkyLight")[0] ^= 1;
        a.getCompound("block_states").getLongArray("data")[0] ^= 1;
        a.getCompound("biomes").putInt("changed", 1);
        assertEquals(original, decodedB);
        assertEquals(original, SectionCodec.decode(storedA, dictionary));
        assertArrayEquals(snapshot, dictionary.compressed());
        CompoundTag updated = SectionCodec.encode(decodedA, ALWAYS, dictionary).tag();
        assertEquals(decodedA, SectionCodec.decode(updated, dictionary));
        assertEquals(original, SectionCodec.decode(storedB, dictionary));
        decodedA.getList("sections", Tag.TAG_COMPOUND).getCompound(0).putString("mod:unknown", "input mutation");
        assertEquals(original, SectionCodec.decode(storedB, dictionary));
    }

    @Test
    void hashCollisionsDoNotMergeDifferentCompounds() throws Exception {
        CompoundTag a = section(5, 4096);
        CompoundTag b = a.copy();
        a.putString("collision", "Aa");
        b.putString("collision", "BB");
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, b);
        RegionStateDictionary dictionary = RegionStateDictionary.create();
        SectionCodec.encode(chunk(a), ALWAYS, dictionary);
        assertFalse(SectionCodec.encode(chunk(b), ALWAYS, dictionary).tag().contains(SectionCodec.KEY));
        assertEquals(0, dictionary.sectionCount());
        CompoundTag storedA = SectionCodec.encode(chunk(a), ALWAYS, dictionary).tag();
        CompoundTag storedB = SectionCodec.encode(chunk(b), ALWAYS, dictionary).tag();
        assertEquals(2, dictionary.sectionCount());
        assertEquals(chunk(a), SectionCodec.decode(storedA, dictionary));
        assertEquals(chunk(b), SectionCodec.decode(storedB, dictionary));
    }

    @Test
    void collidingSignedZerosRemainDistinctInRecentPendingAndSectionPool() throws Exception {
        for (int shape = 0; shape < 6; shape++) {
            CompoundTag a = signedZeroSection(shape, false);
            CompoundTag b = signedZeroSection(shape, true);
            assertEquals(a.hashCode(), b.hashCode());
            assertEquals(a, b);
            assertNotEquals(new RegionStateDictionary.ExactCompound(a), new RegionStateDictionary.ExactCompound(b));
            RegionStateDictionary dictionary = RegionStateDictionary.create();
            SectionCodec.encode(chunk(a), ALWAYS, dictionary);
            assertFalse(SectionCodec.encode(chunk(b), ALWAYS, dictionary).tag().contains(SectionCodec.KEY));
            assertEquals(0, dictionary.sectionCount());
            CompoundTag stored = SectionCodec.encode(chunk(a, b), ALWAYS, dictionary).tag();
            assertTrue(stored.contains(SectionCodec.KEY));
            assertEquals(2, dictionary.sectionCount());
            assertEquals(0, dictionary.size());
            ListTag decoded = SectionCodec.decode(stored, dictionary).getList("sections", Tag.TAG_COMPOUND);
            assertSignedZeros(decoded.getCompound(0), shape, false);
            assertSignedZeros(decoded.getCompound(1), shape, true);
            CompoundTag againA = SectionCodec.encode(chunk(a), ALWAYS, dictionary).tag();
            CompoundTag againB = SectionCodec.encode(chunk(b), ALWAYS, dictionary).tag();
            assertSignedZeros(SectionCodec.decode(againA, dictionary).getList("sections", Tag.TAG_COMPOUND).getCompound(0), shape, false);
            assertSignedZeros(SectionCodec.decode(againB, dictionary).getList("sections", Tag.TAG_COMPOUND).getCompound(0), shape, true);
            assertEquals(2, dictionary.sectionCount());
        }
    }

    @Test
    void signedZeroStateExtensionsAreNotMergedByLocalOrRegionDictionaries() throws Exception {
        CompoundTag a = signedZeroSection(4, false);
        CompoundTag b = signedZeroSection(4, true);
        a.putString("Name", "test:floating_state");
        b.putString("Name", "test:floating_state");
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals(a, b);
        CompoundTag original = placeholders(32);
        ListTag sections = original.getList("sections", Tag.TAG_COMPOUND);
        for (int i = 0; i < sections.size(); i++) {
            CompoundTag states = new CompoundTag();
            states.put("palette", list((i & 1) == 0 ? a : b));
            sections.getCompound(i).put("block_states", states);
        }
        RegionStateDictionary dictionary = RegionStateDictionary.create();
        CompoundTag stored = ChunkCodec.encode(original, ALWAYS, 512, 0, dictionary).tag();
        assertEquals(2, stored.getCompound(ChunkCodec.KEY).getInt("version"));
        assertEquals(2, dictionary.size());
        ListTag decoded = ChunkCodec.decode(stored, dictionary).getList("sections", Tag.TAG_COMPOUND);
        for (int i = 0; i < decoded.size(); i++) {
            CompoundTag state = decoded.getCompound(i).getCompound("block_states")
                    .getList("palette", Tag.TAG_COMPOUND).getCompound(0);
            assertSignedZeros(state, 4, (i & 1) != 0);
        }
        RegionStateDictionary.Addition existing = dictionary.prepareExisting(list(a, b));
        assertNotNull(existing);
        assertNotEquals(existing.mapping[0], existing.mapping[1]);
        assertEquals(2, dictionary.size());
    }

    @Test
    void mixesWholeSectionReferencesWithExistingStateV2WithoutAppendingCandidateStates() throws Exception {
        for (int minimumSaving : new int[]{0, 128}) {
            RegionStateDictionary dictionary = RegionStateDictionary.create();
            CompoundTag reference = section(13, 8192);
            SectionCodec.encode(chunk(reference), ALWAYS, dictionary);
            CompoundTag original = CodecTest.fixture();
            ListTag sections = original.getList("sections", Tag.TAG_COMPOUND);
            sections.set(0, reference.copy());
            CompoundTag baseline = ChunkCodec.encode(original, ALWAYS, 512, 0, dictionary).tag();
            assertEquals(2, baseline.getCompound(ChunkCodec.KEY).getInt("version"));
            long revision = dictionary.revision();
            int states = dictionary.size();
            CompoundTag stored = SectionCodec.encode(original,
                    new ChunkCodec.Options(minimumSaving, false), dictionary).tag();
            assertTrue(stored.contains(SectionCodec.KEY));
            assertEquals(2, stored.getCompound(ChunkCodec.KEY).getInt("version"));
            assertEquals(states, dictionary.size());
            assertEquals(revision + 1, dictionary.revision());
            assertEquals(original, SectionCodec.decode(stored, dictionary));
            assertEquals(original, SectionCodec.decode(stored, RegionStateDictionary.fromCompressed(dictionary.compressed())));
        }
    }

    @Test
    void existingStateCandidatesNeverAppendAndMissingStatesFallBackToV1Losslessly() throws Exception {
        RegionStateDictionary dictionary = RegionStateDictionary.create();
        CompoundTag original = CodecTest.fixture();
        ChunkCodec.encode(original, ALWAYS, 512, 0, dictionary);
        long revision = dictionary.revision();
        byte[] snapshot = dictionary.compressed();
        CompoundTag stored = ChunkCodec.encodeExistingStates(original, ALWAYS, dictionary).tag();
        assertEquals(2, stored.getCompound(ChunkCodec.KEY).getInt("version"));
        assertEquals(original, ChunkCodec.decode(stored, dictionary));
        assertEquals(revision, dictionary.revision());
        assertArrayEquals(snapshot, dictionary.compressed());

        CompoundTag unknown = original.copy();
        unknown.getList("sections", Tag.TAG_COMPOUND).getCompound(0)
                .getCompound("block_states").getList("palette", Tag.TAG_COMPOUND)
                .getCompound(0).putString("Name", "test:absent_state");
        CompoundTag fallback = ChunkCodec.encodeExistingStates(unknown, ALWAYS, dictionary).tag();
        assertEquals(1, fallback.getCompound(ChunkCodec.KEY).getInt("version"));
        assertEquals(ChunkCodec.encode(unknown, ALWAYS, 512, 0).tag(), fallback);
        assertEquals(unknown, ChunkCodec.decode(fallback, dictionary));
        assertEquals(revision, dictionary.revision());
        assertArrayEquals(snapshot, dictionary.compressed());

        RegionStateDictionary withSections = dictionary(dictionaryRoot(snapshot).getList("states", Tag.TAG_COMPOUND),
                list(section(8, 4096)));
        CompoundTag mixed = unknown.copy();
        mixed.getList("sections", Tag.TAG_COMPOUND).set(0, new CompoundTag());
        // 将缺失状态移到保留的区段，验证带 section marker 的候选也能回退。
        mixed.getList("sections", Tag.TAG_COMPOUND).getCompound(1)
                .getCompound("block_states").getList("palette", Tag.TAG_COMPOUND)
                .getCompound(0).putString("Name", "test:absent_state");
        references(mixed, withSections, new int[]{0}, new int[]{0});
        long mixedRevision = withSections.revision();
        CompoundTag mixedFallback = ChunkCodec.encodeExistingStates(mixed, ALWAYS, withSections).tag();
        assertEquals(1, mixedFallback.getCompound(ChunkCodec.KEY).getInt("version"));
        CompoundTag expected = mixed.copy();
        expected.remove(SectionCodec.KEY);
        expected.getList("sections", Tag.TAG_COMPOUND).set(0, section(8, 4096));
        assertEquals(expected, SectionCodec.decode(mixedFallback, withSections));
        assertEquals(mixedRevision, withSections.revision());
    }

    @Test
    void minimumSavingAnd512ByteAllocationGatePoolCommit() throws Exception {
        RegionStateDictionary dictionary = RegionStateDictionary.create();
        CompoundTag original = chunk(section(11, 8192));
        SectionCodec.encode(original, ALWAYS, dictionary);
        CompoundTag refused = SectionCodec.encode(original, new ChunkCodec.Options(Integer.MAX_VALUE, false), dictionary).tag();
        assertFalse(refused.contains(SectionCodec.KEY));
        assertEquals(0, dictionary.sectionCount());
        assertTrue(SectionCodec.encode(original, ALWAYS, dictionary).tag().contains(SectionCodec.KEY));
        assertEquals(1, dictionary.sectionCount());

        CompoundTag tiny = new CompoundTag();
        byte[] noise = new byte[180];
        new Random(11).nextBytes(noise);
        tiny.putByteArray("noise", noise);
        CompoundTag small = chunk(tiny);
        RegionStateDictionary unused = RegionStateDictionary.create();
        SectionCodec.encode(small, ALWAYS, unused);
        int size = IndexCodec.compressedSize(ChunkCodec.serialize(small), -1);
        assertTrue(size < 512);
        assertFalse(SectionCodec.encode(small, new ChunkCodec.Options(0, true), unused).tag().contains(SectionCodec.KEY));
        assertEquals(0, unused.sectionCount());
        assertTrue(SectionCodec.encode(small, ALWAYS, unused).tag().contains(SectionCodec.KEY));
    }

    @Test
    void candidateCacheIsBoundedByCountRawBytesAndEntrySizeAndOwnsCopies() throws Exception {
        RegionStateDictionary dictionary = RegionStateDictionary.create();
        for (int i = 0; i < 65; i++) dictionary.prepareSections(list(section(i, 1024)));
        assertEquals(64, ((Map<?, ?>) field(dictionary, "recent")).size());
        assertEquals(-1, dictionary.prepareSections(list(section(0, 1024))).mapping[0]);
        assertEquals(0, dictionary.sectionCount());

        dictionary = RegionStateDictionary.create();
        for (int i = 0; i < 8; i++) dictionary.prepareSections(list(section(i, 200 * 1024)));
        assertTrue((long) field(dictionary, "recentRaw") <= 1024 * 1024);
        assertTrue(((Map<?, ?>) field(dictionary, "recent")).size() <= 5);
        CompoundTag big = section(99, 256 * 1024);
        assertEquals(-1, dictionary.prepareSections(list(big)).mapping[0]);
        assertEquals(-1, dictionary.prepareSections(list(big)).mapping[0]);

        dictionary = RegionStateDictionary.create();
        CompoundTag mutable = section(1, 1024);
        CompoundTag before = mutable.copy();
        dictionary.prepareSections(list(mutable));
        mutable.getByteArray("SkyLight")[0] ^= 1;
        assertEquals(-1, dictionary.prepareSections(list(mutable)).mapping[0]);
        assertEquals(0, dictionary.prepareSections(list(before)).mapping[0]);
        assertEquals(0, dictionary.sectionCount());
        assertEquals(0, dictionary.revision());
    }

    @Test
    void fullSectionPoolAndRawCapacityFallBackLosslessly() throws Exception {
        for (boolean entries : new boolean[]{true, false}) {
            ListTag pool = new ListTag();
            if (entries) {
                for (int i = 0; i < 4096; i++) {
                    CompoundTag section = new CompoundTag();
                    section.putInt("unique", i);
                    pool.add(section);
                }
            } else {
                CompoundTag section = new CompoundTag();
                section.putByteArray("blob", new byte[8 * 1024 * 1024 - 256]);
                pool.add(section);
            }
            RegionStateDictionary dictionary = dictionary(new ListTag(), pool);
            CompoundTag original = chunk(section(2, 8192));
            byte[] before = dictionary.compressed();
            SectionCodec.encode(original, ALWAYS, dictionary);
            CompoundTag stored = SectionCodec.encode(original, ALWAYS, dictionary).tag();
            assertFalse(stored.contains(SectionCodec.KEY));
            assertEquals(original, SectionCodec.decode(stored, dictionary));
            // 基线可以追加状态，但 section 池绝不收录未获选的候选。
            assertEquals(pool.size(), dictionary.sectionCount());
            if (!entries) assertArrayEquals(before, dictionary.compressed());
        }
    }

    @Test
    void heapQuotaPreventsCacheAndPoolCopiesBeforeAndAfterReload() throws Exception {
        CompoundTag heavy = new CompoundTag();
        heavy.put("strings", strings(1_500_000));
        RegionStateDictionary dictionary = dictionary(new ListTag(), list(heavy));
        byte[] snapshot = dictionary.compressed();
        CompoundTag next = new CompoundTag();
        next.put("strings", strings(100_000));
        for (RegionStateDictionary full : new RegionStateDictionary[]{dictionary, RegionStateDictionary.fromCompressed(snapshot)}) {
            assertEquals(-1, full.prepareSections(list(next)).mapping[0]);
            assertEquals(-1, full.prepareSections(list(next)).mapping[0]);
            assertEquals(0, ((Map<?, ?>) field(full, "recent")).size());
            assertEquals(1, full.sectionCount());
            assertArrayEquals(snapshot, full.compressed());
        }
    }

    @Test
    void legacyDictionaryAndCodecRemainReadable() throws Exception {
        RegionStateDictionary dictionary = RegionStateDictionary.create();
        CompoundTag original = CodecTest.fixture();
        CompoundTag v1 = ChunkCodec.encode(original, ALWAYS).tag();
        assertEquals(original, SectionCodec.decode(v1, null));
        CompoundTag v2 = ChunkCodec.encode(original, ALWAYS, 512, 0, dictionary).tag();
        assertEquals(2, v2.getCompound(ChunkCodec.KEY).getInt("version"));
        assertEquals(1, dictionaryRoot(dictionary.compressed()).getInt("version"));
        RegionStateDictionary reloaded = RegionStateDictionary.fromCompressed(dictionary.compressed());
        assertEquals(original, SectionCodec.decode(v2, reloaded));
        assertSame(original, SectionCodec.decode(original, null));
    }

    @Test
    void rejectsMissingDictionaryWrongUuidCrcMagicAndMalformedMarker() throws Exception {
        RegionStateDictionary dictionary = dictionary(new ListTag(), list(section(4, 1024)));
        CompoundTag stored = references(chunk(new CompoundTag()), dictionary, new int[]{0}, new int[]{0});
        assertThrows(IOException.class, () -> SectionCodec.decode(stored, null));
        assertThrows(IOException.class, () -> SectionCodec.decode(stored, RegionStateDictionary.create()));
        for (Consumer<CompoundTag> damage : List.<Consumer<CompoundTag>>of(
                tag -> tag.putString(SectionCodec.KEY, "wrong"),
                tag -> tag.getCompound(SectionCodec.KEY).putInt("version", 2),
                tag -> tag.getCompound(SectionCodec.KEY).putInt("crc32", 0),
                tag -> tag.getCompound(SectionCodec.KEY).remove("payload"),
                tag -> tag.getCompound(SectionCodec.KEY).putInt("unknown", 1),
                tag -> tag.remove("Status"),
                tag -> tag.getCompound(SectionCodec.KEY).getByteArray("payload")[0] ^= 1,
                tag -> { tag.getCompound(SectionCodec.KEY).getByteArray("payload")[4] ^= 1; checksum(tag, SectionCodec.KEY); },
                tag -> { tag.getCompound(SectionCodec.KEY).getByteArray("payload")[0] ^= 1; checksum(tag, SectionCodec.KEY); })) {
            CompoundTag bad = stored.copy();
            damage.accept(bad);
            assertThrows(IOException.class, () -> SectionCodec.decode(bad, dictionary));
        }
    }

    @Test
    void rejectsDuplicateOutOfRangeNoncanonicalTrailingAndNonemptyReferences() throws Exception {
        RegionStateDictionary dictionary = dictionary(new ListTag(), list(section(4, 1024)));
        for (int[][] refs : new int[][][]{ {{}, {}}, {{0, 0}, {0, 0}}, {{2}, {0}}, {{0}, {1}} }) {
            CompoundTag stored = references(chunk(new CompoundTag(), new CompoundTag()), dictionary, refs[0], refs[1]);
            assertThrows(IOException.class, () -> SectionCodec.decode(stored, dictionary));
        }
        CompoundTag stored = references(chunk(new CompoundTag()), dictionary, new int[]{0}, new int[]{0});
        CompoundTag conflict = stored.copy();
        conflict.getList("sections", Tag.TAG_COMPOUND).getCompound(0).putByte("Y", (byte) 0);
        assertThrows(IOException.class, () -> SectionCodec.decode(conflict, dictionary));
        for (int offset : new int[]{20, 21, 22}) {
            CompoundTag bad = stored.copy();
            byte[] payload = bad.getCompound(SectionCodec.KEY).getByteArray("payload");
            byte[] noncanonical = new byte[payload.length + 1];
            System.arraycopy(payload, 0, noncanonical, 0, offset);
            noncanonical[offset] = (byte) (payload[offset] | 128);
            System.arraycopy(payload, offset, noncanonical, offset + 1, payload.length - offset);
            noncanonical[offset + 1] = 0;
            bad.getCompound(SectionCodec.KEY).putByteArray("payload", noncanonical);
            checksum(bad, SectionCodec.KEY);
            assertThrows(IOException.class, () -> SectionCodec.decode(bad, dictionary));
        }
        byte[] payload = stored.getCompound(SectionCodec.KEY).getByteArray("payload");
        for (byte[] invalid : new byte[][]{Arrays.copyOf(payload, payload.length + 1), Arrays.copyOf(payload, payload.length - 1)}) {
            CompoundTag bad = stored.copy();
            bad.getCompound(SectionCodec.KEY).putByteArray("payload", invalid);
            checksum(bad, SectionCodec.KEY);
            assertThrows(IOException.class, () -> SectionCodec.decode(bad, dictionary));
        }
    }

    @Test
    void rejectsEitherReservedInputMarkerEvenWithWrongType() {
        for (String key : new String[]{SectionCodec.KEY, ChunkCodec.KEY}) {
            CompoundTag original = chunk(section(4, 1024));
            original.putInt(key, 0);
            assertThrows(IOException.class, () -> SectionCodec.encode(original, ALWAYS, RegionStateDictionary.create()));
        }
    }

    @Test
    void expansionAndDepthAreValidatedBeforeAnyCopies() throws Exception {
        CompoundTag large = new CompoundTag();
        large.putByteArray("blob", new byte[1024 * 1024]);
        RegionStateDictionary dictionary = dictionary(new ListTag(), list(large));
        CompoundTag stored = placeholders(65);
        stored.put("guarded", guarded());
        int[] indices = new int[65];
        for (int i = 0; i < indices.length; i++) indices[i] = i;
        references(stored, dictionary, indices, new int[65]);
        CompoundTag expanded = stored;
        assertThrows(IOException.class, () -> SectionCodec.decode(expanded, dictionary));

        stored = references(placeholders(1), dictionary, new int[]{0}, new int[]{0});
        stored.put("guarded", guarded());
        CompoundTag current = stored;
        for (int i = 0; i < 512; i++) {
            CompoundTag child = new CompoundTag();
            current.put("nested", child);
            current = child;
        }
        CompoundTag deep = stored;
        assertThrows(IOException.class, () -> SectionCodec.decode(deep, dictionary));
        assertSame(deep, encodeWithoutMarker(deep, dictionary));
    }

    @Test
    void stateAndSectionCombinedExpansionIsRejectedBeforeAnyCopies() throws Exception {
        CompoundTag large = new CompoundTag();
        large.putByteArray("blob", new byte[1024 * 1024]);
        RegionStateDictionary dictionary = dictionary(list(large), list(large));
        CompoundTag stored = placeholders(61);
        stored.put("guarded", guarded());
        int[] indices = new int[60];
        for (int i = 0; i < indices.length; i++) indices[i] = i;
        references(stored, dictionary, indices, new int[60]);
        stateReference(stored, dictionary, 60, 10);
        assertThrows(IOException.class, () -> SectionCodec.decode(stored, dictionary));
        CompoundTag overlapping = references(placeholders(1), dictionary, new int[]{0}, new int[]{0});
        stateReference(overlapping, dictionary, 0, 1);
        assertThrows(IOException.class, () -> SectionCodec.decode(overlapping, dictionary));
    }

    @Test
    void loadedV2WithEmptyPoolKeepsItsEightMiBFormatAcrossSnapshots() throws Exception {
        CompoundTag large = new CompoundTag();
        large.putByteArray("blob", new byte[5 * 1024 * 1024]);
        RegionStateDictionary dictionary = dictionary(list(large), new ListTag());
        assertEquals(0, dictionary.sectionCount());
        assertEquals(1, dictionary.revision());
        byte[] snapshot = dictionary.compressed();
        assertEquals(2, dictionaryRoot(snapshot).getInt("version"));
        dictionary = RegionStateDictionary.fromCompressed(snapshot);
        CompoundTag original = chunk(section(3, 8192));
        SectionCodec.encode(original, ALWAYS, dictionary);
        CompoundTag stored = SectionCodec.encode(original, ALWAYS, dictionary).tag();
        assertTrue(stored.contains(SectionCodec.KEY));
        assertEquals(original, SectionCodec.decode(stored, RegionStateDictionary.fromCompressed(dictionary.compressed())));
    }

    @Test
    void exactExpansionLimitDoesNotCountTheRemovedSectionMarker() throws Exception {
        CompoundTag large = new CompoundTag();
        large.putByteArray("blob", new byte[1024 * 1024]);
        RegionStateDictionary dictionary = dictionary(list(large), list(new CompoundTag()));
        CompoundTag stored = references(placeholders(2), dictionary, new int[]{0}, new int[]{0});
        stateReference(stored, dictionary, 1, 63);
        CompoundTag metadata = new CompoundTag();
        for (String key : stored.getAllKeys()) {
            if (!key.equals(SectionCodec.KEY) && !key.equals(ChunkCodec.KEY)) metadata.put(key, stored.get(key));
        }
        long stateBytes = 31 + 63 * dictionary.stateSize(0) + 11
                + 8L * IndexCodec.packVanilla(new int[4096], 63).length;
        int padding = (int) (ChunkCodec.MAX_BYTES - NbtSize.rootWithout(metadata, null, ChunkCodec.MAX_BYTES)
                - stateBytes - 14); // padding 标签头与 byte[] 长度。
        stored.putByteArray("padding", new byte[padding]);
        CompoundTag restored = SectionCodec.decode(stored, dictionary);
        assertEquals(ChunkCodec.MAX_BYTES, NbtSize.rootWithout(restored, null, ChunkCodec.MAX_BYTES));
        assertFalse(restored.contains(SectionCodec.KEY));
        assertFalse(restored.contains(ChunkCodec.KEY));
        assertTrue(restored.getList("sections", Tag.TAG_COMPOUND).getCompound(0).isEmpty());
        assertEquals(63, restored.getList("sections", Tag.TAG_COMPOUND).getCompound(1)
                .getCompound("block_states").getList("palette", Tag.TAG_COMPOUND).size());
    }

    @Test
    void dictionaryV2ValidatesPoolCountTypesRawHeapAndDepthLimits() throws Exception {
        CompoundTag empty = new CompoundTag();
        ListTag pool = new ListTag();
        for (int i = 0; i < 4097; i++) pool.add(empty);
        ListTag excessive = pool;
        assertThrows(IOException.class, () -> dictionary(new ListTag(), excessive));
        ListTag wrong = new ListTag();
        wrong.add(StringTag.valueOf("wrong"));
        assertThrows(IOException.class, () -> dictionary(new ListTag(), wrong));
        CompoundTag oversized = new CompoundTag();
        oversized.putByteArray("blob", new byte[8 * 1024 * 1024]);
        assertThrows(IOException.class, () -> dictionary(new ListTag(), list(oversized)));
        CompoundTag largeV1 = RegionStateDictionaryTest.root(UUID.randomUUID(), list(oversized));
        oversized.putByteArray("blob", new byte[5 * 1024 * 1024]);
        assertThrows(IOException.class, () -> RegionStateDictionary.fromCompressed(RegionStateDictionaryTest.compress(largeV1)));
        CompoundTag heavy = new CompoundTag();
        heavy.put("strings", strings(1_700_000));
        assertThrows(IOException.class, () -> dictionary(new ListTag(), list(heavy)));
        CompoundTag deep = new CompoundTag();
        CompoundTag current = deep;
        for (int i = 0; i < 510; i++) {
            CompoundTag child = new CompoundTag();
            current.put("nested", child);
            current = child;
        }
        assertThrows(IOException.class, () -> dictionary(new ListTag(), list(deep)));
    }

    @Test
    void storagePayloadIsExactlyTheSelectedTagForBaselineSectionsAndFallback() throws Exception {
        RegionStateDictionary dictionary = RegionStateDictionary.create();
        CompoundTag original = CodecTest.fixture();
        SectionCodec.Encoded baseline = SectionCodec.encodeForStorage(original, ALWAYS, dictionary);
        assertTrue(baseline.result().optimized());
        assertStoredPayload(baseline);
        assertEquals(original, SectionCodec.decode(baseline.result().tag(), dictionary));

        CompoundTag repeated = chunk(section(131, 8192), section(131, 8192));
        SectionCodec.encodeForStorage(repeated, ALWAYS, dictionary);
        SectionCodec.Encoded references = SectionCodec.encodeForStorage(repeated, ALWAYS, dictionary);
        assertTrue(references.result().tag().contains(SectionCodec.KEY));
        assertStoredPayload(references);
        assertEquals(repeated, SectionCodec.decode(references.result().tag(), dictionary));

        SectionCodec.Encoded refused = SectionCodec.encodeForStorage(original,
                new ChunkCodec.Options(Integer.MAX_VALUE, false), dictionary);
        assertSame(original, refused.result().tag());
        assertStoredPayload(refused);
        CompoundTag plain = new CompoundTag();
        plain.putString("unknown", "plain NBT");
        SectionCodec.Encoded unchanged = SectionCodec.encodeForStorage(plain, ALWAYS, dictionary);
        assertSame(plain, unchanged.result().tag());
        assertStoredPayload(unchanged);
    }

    @Test
    void selectedShellOwnsAllMutableMetadataAndUntouchedSectionFields() throws Exception {
        RegionStateDictionary dictionary = RegionStateDictionary.create();
        CompoundTag original = CodecTest.fixture();
        original.putByteArray("metadata", new byte[]{1, 2, 3});
        CompoundTag untouched = original.getList("sections", Tag.TAG_COMPOUND).getCompound(0);
        untouched.getCompound("block_states").putByteArray("mod:unknown", new byte[]{4, 5});
        CompoundTag before = original.copy();
        SectionCodec.Encoded encoded = SectionCodec.encodeForStorage(original, ALWAYS, dictionary);
        assertTrue(encoded.result().optimized());
        assertStoredPayload(encoded);
        CompoundTag stored = encoded.result().tag();
        stored.getByteArray("metadata")[0] = 99;
        stored.getList("sections", Tag.TAG_COMPOUND).getCompound(0)
                .getCompound("block_states").getByteArray("mod:unknown")[0] = 99;
        stored.getList("sections", Tag.TAG_COMPOUND).getCompound(1).getByteArray("SkyLight")[0] = 99;
        assertEquals(before, original);
        original.getByteArray("metadata")[1] = 88;
        assertEquals(2, stored.getByteArray("metadata")[1]);
        assertEquals(before, SectionCodec.decode(readStoredPayload(encoded), dictionary));
    }

    @Test
    void storageFallbackKeepsStandardNbtBeyondCodecRawLimit() throws Exception {
        CompoundTag original = chunk(new CompoundTag());
        original.putByteArray("large", new byte[ChunkCodec.MAX_BYTES + 1]);
        SectionCodec.Encoded encoded = SectionCodec.encodeForStorage(original, ALWAYS, RegionStateDictionary.create());
        assertSame(original, encoded.result().tag());
        assertFalse(encoded.result().optimized());
        assertEquals(original, readStoredPayload(encoded));
    }

    @Test
    void concurrentStorageCallsKeepCompressionAndPlansIsolated() throws Exception {
        CompoundTag original = CodecTest.fixture();
        RegionStateDictionary shared = RegionStateDictionary.create();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            List<java.util.concurrent.Callable<Void>> calls = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                int index = i;
                calls.add(() -> {
                    RegionStateDictionary dictionary = (index & 1) == 0 ? shared : RegionStateDictionary.create();
                    CompoundTag input = original.copy();
                    input.putInt("xPos", index);
                    SectionCodec.Encoded encoded = SectionCodec.encodeForStorage(input, ALWAYS, dictionary);
                    assertStoredPayload(encoded);
                    assertEquals(input, SectionCodec.decode(encoded.result().tag(), dictionary));
                    return null;
                });
            }
            for (var future : executor.invokeAll(calls)) future.get();
        } finally {
            executor.shutdownNow();
        }
    }

    private static CompoundTag readStoredPayload(SectionCodec.Encoded encoded) throws IOException {
        try (var in = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(encoded.payload())))) {
            return NbtIo.read(in, NbtAccounter.unlimitedHeap());
        }
    }

    private static void assertStoredPayload(SectionCodec.Encoded encoded) throws IOException {
        assertEquals(new RegionStateDictionary.ExactCompound(encoded.result().tag()),
                new RegionStateDictionary.ExactCompound(readStoredPayload(encoded)));
        try (var in = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(encoded.payload())))) {
            NbtIo.read(in, NbtAccounter.unlimitedHeap());
            assertEquals(-1, in.read());
        }
        if (encoded.result().storedBytes() != 0) assertEquals(encoded.result().storedBytes(), encoded.payload().length);
    }

    private static CompoundTag encodeWithoutMarker(CompoundTag original, RegionStateDictionary dictionary) throws IOException {
        original.remove(SectionCodec.KEY);
        return SectionCodec.encode(original, ALWAYS, dictionary).tag();
    }

    private static CompoundTag guarded() {
        return new CompoundTag() {
            @Override public CompoundTag copy() { throw new AssertionError("Copied before validation"); }
        };
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static ListTag strings(int count) {
        ListTag strings = new ListTag();
        for (int i = 0; i < count; i++) strings.add(StringTag.valueOf(""));
        return strings;
    }

    private static CompoundTag signedZeroSection(int shape, boolean reversed) throws Exception {
        CompoundTag section = new CompoundTag();
        byte[] noise = new byte[4096];
        new Random(73).nextBytes(noise);
        section.putByteArray("noise", noise);
        boolean doubles = shape == 1 || shape == 4 || shape == 5;
        Tag a = signedZero(doubles, reversed);
        Tag b = signedZero(doubles, !reversed);
        if (shape <= 1) {
            section.put("a", a);
            section.put("b", b);
        } else if (shape == 2 || shape == 4) {
            CompoundTag nested = new CompoundTag();
            nested.put("a", a);
            nested.put("b", b);
            if (shape == 2) section.put("nested", nested);
            else section.put("nested", list(nested));
        } else {
            ListTag nested = new ListTag();
            nested.add(a);
            nested.add(b);
            section.put("nested", nested);
        }
        return section;
    }

    private static Tag signedZero(boolean doubles, boolean negative) throws Exception {
        // valueOf 会把负零折叠成 ZERO，反射构造用于验证原版 equals 的边界。
        if (doubles) {
            var constructor = DoubleTag.class.getDeclaredConstructor(double.class);
            constructor.setAccessible(true);
            return constructor.newInstance(negative ? -0.0D : 0.0D);
        }
        var constructor = FloatTag.class.getDeclaredConstructor(float.class);
        constructor.setAccessible(true);
        return constructor.newInstance(negative ? -0.0F : 0.0F);
    }

    private static void assertSignedZeros(CompoundTag section, int shape, boolean reversed) {
        Tag a;
        Tag b;
        if (shape <= 1) {
            a = section.get("a");
            b = section.get("b");
        } else if (shape == 2 || shape == 4) {
            CompoundTag nested = shape == 2 ? section.getCompound("nested")
                    : section.getList("nested", Tag.TAG_COMPOUND).getCompound(0);
            a = nested.get("a");
            b = nested.get("b");
        } else {
            ListTag nested = section.getList("nested", shape == 3 ? Tag.TAG_FLOAT : Tag.TAG_DOUBLE);
            a = nested.get(0);
            b = nested.get(1);
        }
        if (a instanceof DoubleTag value) {
            assertEquals(Double.doubleToLongBits(reversed ? -0.0D : 0.0D), Double.doubleToLongBits(value.getAsDouble()));
            assertEquals(Double.doubleToLongBits(reversed ? 0.0D : -0.0D), Double.doubleToLongBits(((DoubleTag) b).getAsDouble()));
        } else {
            assertEquals(Float.floatToIntBits(reversed ? -0.0F : 0.0F), Float.floatToIntBits(((FloatTag) a).getAsFloat()));
            assertEquals(Float.floatToIntBits(reversed ? 0.0F : -0.0F), Float.floatToIntBits(((FloatTag) b).getAsFloat()));
        }
    }

    private static CompoundTag section(int seed, int bytes) {
        Random random = new Random(seed);
        CompoundTag section = new CompoundTag();
        section.putByte("Y", (byte) 0);
        byte[] light = new byte[bytes];
        random.nextBytes(light);
        section.putByteArray("SkyLight", light);
        CompoundTag biomes = new CompoundTag();
        ListTag biomePalette = new ListTag();
        biomePalette.add(StringTag.valueOf("minecraft:plains"));
        biomes.put("palette", biomePalette);
        section.put("biomes", biomes);
        section.putString("mod:unknown", "完整区段\u0000");
        CompoundTag states = new CompoundTag();
        ListTag palette = new ListTag();
        for (int i = 0; i < 17; i++) {
            CompoundTag state = new CompoundTag();
            state.putString("Name", "test:block_" + i);
            palette.add(state);
        }
        states.put("palette", palette);
        int[] values = new int[4096];
        for (int i = 0; i < values.length; i++) values[i] = random.nextInt(17);
        long[] data = IndexCodec.packVanilla(values, 17);
        data[0] |= 1L << 63;
        states.putLongArray("data", data);
        section.put("block_states", states);
        return section;
    }

    private static CompoundTag chunk(CompoundTag... sections) {
        CompoundTag chunk = new CompoundTag();
        chunk.putString("Status", "minecraft:full");
        chunk.putInt("xPos", 0);
        chunk.put("sections", list(sections));
        return chunk;
    }

    private static ListTag list(CompoundTag... entries) {
        ListTag list = new ListTag();
        for (CompoundTag entry : entries) list.add(entry);
        return list;
    }

    private static CompoundTag placeholders(int count) {
        CompoundTag[] sections = new CompoundTag[count];
        Arrays.setAll(sections, i -> new CompoundTag());
        return chunk(sections);
    }

    private static RegionStateDictionary dictionary(ListTag states, ListTag sections) throws IOException {
        CompoundTag root = RegionStateDictionaryTest.root(UUID.randomUUID(), states);
        root.putInt("version", 2);
        root.put("sections", sections);
        return RegionStateDictionary.fromCompressed(RegionStateDictionaryTest.compress(root));
    }

    private static CompoundTag dictionaryRoot(byte[] compressed) throws IOException {
        return NbtIo.read(new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(compressed))),
                NbtAccounter.create(ChunkCodec.MAX_BYTES));
    }

    private static CompoundTag references(CompoundTag root, RegionStateDictionary dictionary, int[] indices, int[] ids) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        out.writeInt(0x43535331);
        out.writeLong(dictionary.identity().getMostSignificantBits());
        out.writeLong(dictionary.identity().getLeastSignificantBits());
        IndexCodec.writeVarInt(out, indices.length);
        for (int i = 0; i < indices.length; i++) {
            IndexCodec.writeVarInt(out, indices[i]);
            IndexCodec.writeVarInt(out, ids[i]);
        }
        CompoundTag marker = new CompoundTag();
        marker.putInt("version", 1);
        marker.putByteArray("payload", bytes.toByteArray());
        root.put(SectionCodec.KEY, marker);
        checksum(root, SectionCodec.KEY);
        return root;
    }

    private static void stateReference(CompoundTag root, RegionStateDictionary dictionary, int index, int palette) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        out.writeInt(0x43535132);
        out.writeLong(dictionary.identity().getMostSignificantBits());
        out.writeLong(dictionary.identity().getLeastSignificantBits());
        IndexCodec.writeVarInt(out, 1);
        IndexCodec.writeVarInt(out, 0);
        IndexCodec.writeVarInt(out, 1);
        IndexCodec.writeVarInt(out, index);
        IndexCodec.writeVarInt(out, palette);
        for (int i = 0; i < palette; i++) IndexCodec.writeVarInt(out, 0);
        byte[] data = palette == 1 ? new byte[]{0} : IndexCodec.encodeValues(new int[4096], palette, 3);
        IndexCodec.writeVarInt(out, data.length);
        out.write(data);
        CompoundTag marker = new CompoundTag();
        marker.putInt("version", 2);
        marker.putByteArray("payload", bytes.toByteArray());
        root.put(ChunkCodec.KEY, marker);
        checksum(root, ChunkCodec.KEY);
    }

    private static void checksum(CompoundTag root, String key) {
        CompoundTag marker = root.getCompound(key);
        CRC32 crc = new CRC32();
        crc.update(marker.getByteArray("payload"));
        marker.putLong("crc32", crc.getValue());
    }
}
