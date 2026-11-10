package cn.star.chunksqueeze.codec;

import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class CodecTest {
    @Test
    void allIndexModesPreserveEveryPosition() throws Exception {
        Random random = new Random(4137);
        for (int paletteSize : new int[]{1, 2, 16, 17, 32, 33, 256, 257, 4096}) {
            int[] values = new int[4096];
            for (int i = 0; i < values.length; i++) values[i] = random.nextInt(paletteSize);
            long[] expected = IndexCodec.packVanilla(values, paletteSize);
            for (int mode = 2; mode <= 5; mode++) {
                assertArrayEquals(expected, IndexCodec.decode(IndexCodec.encodeValues(values, paletteSize, mode), paletteSize));
            }
            assertArrayEquals(expected, IndexCodec.decode(IndexCodec.encode(expected, paletteSize, true), paletteSize));
        }
    }

    @Test
    void rawModePreservesNonzeroPadding() throws Exception {
        long[] original = IndexCodec.packVanilla(new int[4096], 17);
        original[0] |= 1L << 63;
        byte[] encoded = IndexCodec.encode(original, 17, true);
        assertEquals(1, encoded[0]);
        assertArrayEquals(original, IndexCodec.decode(encoded, 17));
    }

    @Test
    void rejectsMalformedIndexStreams() {
        assertThrows(IOException.class, () -> IndexCodec.decode(new byte[]{9}, 2));
        assertThrows(IOException.class, () -> IndexCodec.decode(new byte[]{3, 0, 0}, 2));
        assertThrows(IOException.class, () -> IndexCodec.decode(new byte[]{4, 0, 1, (byte) 0xff, 0x7f, 1}, 2));
        assertThrows(IOException.class, () -> IndexCodec.decode(new byte[]{4, 0, 1, 0, 2}, 2));
        assertThrows(IOException.class, () -> IndexCodec.decode(new byte[]{4, 0, 0, 1}, 2));
    }

    @Test
    void roundTripPreservesWholeNbtAndDoesNotMutateInput() throws Exception {
        CompoundTag original = fixture();
        CompoundTag before = original.copy();
        ChunkCodec.Result result = ChunkCodec.encode(original, new ChunkCodec.Options(0, false));
        assertTrue(result.optimized());
        assertEquals(before, original);
        assertEquals(before, ChunkCodec.decode(result.tag()));
        assertTrue(result.storedBytes() < result.originalBytes());
        assertTrue(result.tag().contains(ChunkCodec.KEY));
    }

    @Test
    void unknownSectionFieldsRemainUntouched() throws Exception {
        CompoundTag original = fixture();
        original.getList("sections", Tag.TAG_COMPOUND).getCompound(0)
                .getCompound("block_states").putString("othermod:extension", "keep me");
        ChunkCodec.Result result = ChunkCodec.encode(original, new ChunkCodec.Options(0, false));
        assertEquals(original, ChunkCodec.decode(result.tag()));
        assertEquals("keep me", result.tag().getList("sections", Tag.TAG_COMPOUND)
                .getCompound(0).getCompound("block_states").getString("othermod:extension"));
    }

    @Test
    void minimumSavingCanForceVanillaFallback() throws Exception {
        CompoundTag original = fixture();
        ChunkCodec.Result result = ChunkCodec.encode(original, new ChunkCodec.Options(Integer.MAX_VALUE, true));
        assertSame(original, result.tag());
        assertFalse(result.optimized());
    }

    @Test
    void corruptPayloadAndUnsupportedVersionAreRejected() throws Exception {
        CompoundTag encoded = ChunkCodec.encode(fixture(), new ChunkCodec.Options(0, false)).tag();
        assertTrue(encoded.contains(ChunkCodec.KEY));
        CompoundTag corrupted = encoded.copy();
        byte[] payload = corrupted.getCompound(ChunkCodec.KEY).getByteArray("payload");
        payload[payload.length / 2] ^= 1;
        assertThrows(IOException.class, () -> ChunkCodec.decode(corrupted));
        CompoundTag future = encoded.copy();
        future.getCompound(ChunkCodec.KEY).putInt("version", 999);
        assertThrows(IOException.class, () -> ChunkCodec.decode(future));
        CompoundTag conflict = encoded.copy();
        conflict.getList("sections", Tag.TAG_COMPOUND).getCompound(0).put("block_states", new CompoundTag());
        assertThrows(IOException.class, () -> ChunkCodec.decode(conflict));
    }

    @Test
    void vanillaAndNonTerrainNbtPassThrough() throws Exception {
        CompoundTag original = new CompoundTag();
        original.putString("unknown", "数据 🌍 \u0000");
        assertSame(original, ChunkCodec.decode(original));
        assertSame(original, ChunkCodec.encode(original, new ChunkCodec.Options(0, false)).tag());
    }

    @Test
    void encodedMetadataMustRemainValid() throws Exception {
        CompoundTag encoded = ChunkCodec.encode(fixture(), new ChunkCodec.Options(0, false)).tag();
        CompoundTag missing = encoded.copy();
        missing.remove("Status");
        assertThrows(IOException.class, () -> ChunkCodec.decode(missing));
        CompoundTag wrong = encoded.copy();
        wrong.putInt("Status", 0);
        assertThrows(IOException.class, () -> ChunkCodec.decode(wrong));
        CompoundTag empty = encoded.copy();
        empty.putString("Status", "");
        assertThrows(IOException.class, () -> ChunkCodec.decode(empty));
        CompoundTag invalidInput = fixture();
        invalidInput.putString("Status", "");
        assertSame(invalidInput, ChunkCodec.encode(invalidInput, new ChunkCodec.Options(0, false)).tag());
    }

    @Test
    void rejectsDictionaryExpansionBeforeCopyingLargeArrays() throws Exception {
        CompoundTag entry = new CompoundTag();
        entry.putString("Name", "test:large_state");
        entry.putByteArray("extra", new byte[1024 * 1024]);
        ListTag entries = new ListTag();
        entries.add(entry);
        CompoundTag dictionary = new CompoundTag();
        dictionary.put("states", entries);
        var bytes = new java.io.ByteArrayOutputStream();
        var out = new java.io.DataOutputStream(bytes);
        out.writeInt(0x43535131);
        NbtIo.write(dictionary, out);
        IndexCodec.writeVarInt(out, 1);
        IndexCodec.writeVarInt(out, 0);
        IndexCodec.writeVarInt(out, 4096);
        for (int i = 0; i < 4096; i++) IndexCodec.writeVarInt(out, 0);
        byte[] indices = IndexCodec.encodeValues(new int[4096], 4096, 3);
        IndexCodec.writeVarInt(out, indices.length);
        out.write(indices);
        byte[] payload = bytes.toByteArray();
        var crc = new java.util.zip.CRC32();
        crc.update(payload);
        CompoundTag marker = new CompoundTag();
        marker.putInt("version", 1);
        marker.putLong("crc32", crc.getValue());
        marker.putByteArray("payload", payload);
        CompoundTag root = new CompoundTag();
        root.putString("Status", "minecraft:full");
        ListTag sections = new ListTag();
        sections.add(new CompoundTag());
        root.put("sections", sections);
        root.put(ChunkCodec.KEY, marker);
        IOException error = assertThrows(IOException.class, () -> ChunkCodec.decode(root));
        assertTrue(error.getMessage().contains("exceeds limit"));
    }

    @Test
    void deepModMetadataRoundTrips() throws Exception {
        CompoundTag root = fixture();
        CompoundTag parent = root;
        for (int i = 0; i < 150; i++) {
            CompoundTag child = new CompoundTag();
            parent.put("nested", child);
            parent = child;
        }
        parent.putString("leaf", "保留");
        assertEquals(root, ChunkCodec.decode(ChunkCodec.encode(root, new ChunkCodec.Options(0, false)).tag()));
    }

    @Test
    void javaDepthLimitCountsTheRootContainer() throws Exception {
        for (int children : new int[]{511, 512}) {
            CompoundTag root = new CompoundTag();
            CompoundTag current = root;
            for (int i = 0; i < children; i++) {
                CompoundTag child = new CompoundTag();
                current.put("nested", child);
                current = child;
            }
            byte[] bytes = ChunkCodec.serialize(root);
            if (children == 511) {
                assertNotNull(NbtIo.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes)), NbtAccounter.unlimitedHeap()));
            } else {
                assertThrows(RuntimeException.class, () -> NbtIo.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes)), NbtAccounter.unlimitedHeap()));
            }
        }
    }

    @Test
    void allocationPolicyUsesTheActualBackendBoundary() throws Exception {
        assertEquals(512, ChunkCodec.allocatedBytes(512, 512, 0));
        assertEquals(1024, ChunkCodec.allocatedBytes(513, 512, 0));
        assertEquals(4096, ChunkCodec.allocatedBytes(4091, 4096, 5));
        assertEquals(8192, ChunkCodec.allocatedBytes(4092, 4096, 5));
        CompoundTag original = fixture();
        ChunkCodec.Result compact = ChunkCodec.encode(original, new ChunkCodec.Options(128, true), 512, 0);
        assertEquals(original, ChunkCodec.decode(compact.tag()));
        if (compact.optimized()) {
            assertTrue(ChunkCodec.allocatedBytes(compact.storedBytes(), 512, 0)
                    < ChunkCodec.allocatedBytes(compact.originalBytes(), 512, 0));
        }
        assertThrows(IllegalArgumentException.class, () -> ChunkCodec.encode(original, new ChunkCodec.Options(0, false), 0, 0));
    }

    static CompoundTag fixture() {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", 3955);
        root.putString("Status", "minecraft:full");
        root.putInt("xPos", 0);
        root.putInt("zPos", 0);
        root.putString("mod:unknown", "原样保留 🌍 \u0000");
        root.putLongArray("other-array", new long[]{Long.MIN_VALUE, Long.MAX_VALUE});
        ListTag sections = new ListTag();
        for (int y = -4; y < 20; y++) {
            CompoundTag section = new CompoundTag();
            section.putByte("Y", (byte) y);
            section.putByteArray("SkyLight", new byte[2048]);
            CompoundTag states = new CompoundTag();
            ListTag palette = new ListTag();
            for (int i = 0; i < 20; i++) {
                CompoundTag state = new CompoundTag();
                state.putString("Name", "test:long_block_state_identifier_" + i);
                CompoundTag props = new CompoundTag();
                props.putString("unknown_property", "自定义 🌍 \u0000 value " + i);
                props.putString("waterlogged", "false");
                state.put("Properties", props);
                palette.add(state);
            }
            int[] values = new int[4096];
            for (int i = 0; i < values.length; i++) values[i] = (i / 64 + y + 4) % 20;
            states.put("palette", palette);
            states.putLongArray("data", IndexCodec.packVanilla(values, 20));
            section.put("block_states", states);
            sections.add(section);
        }
        root.put("sections", sections);
        return root;
    }
}
