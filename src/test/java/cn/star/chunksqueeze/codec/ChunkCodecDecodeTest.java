package cn.star.chunksqueeze.codec;

import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

class ChunkCodecDecodeTest {
    @Test
    void skipsMarkerCopyAndKeepsAllMutableValuesIndependent() throws Exception {
        CompoundTag original = CodecTest.fixture();
        CompoundTag metadata = new CompoundTag();
        metadata.putIntArray("array", new int[]{1, 2});
        original.put("metadata", metadata);
        CompoundTag stored = ChunkCodec.encode(original, new ChunkCodec.Options(0, false)).tag();
        assertTrue(stored.contains(ChunkCodec.KEY));
        CompoundTag marker = stored.getCompound(ChunkCodec.KEY);
        CompoundTag guarded = copyForbidden();
        for (String key : marker.getAllKeys()) guarded.put(key, marker.get(key));
        stored.put(ChunkCodec.KEY, guarded);

        CompoundTag restored = ChunkCodec.decode(stored);
        assertEquals(original, restored);
        assertSame(guarded, stored.getCompound(ChunkCodec.KEY));
        assertFalse(restored.contains(ChunkCodec.KEY));
        restored.getCompound("metadata").getIntArray("array")[0] = 99;
        restored.getLongArray("other-array")[0] = 99;
        ListTag sections = restored.getList("sections", Tag.TAG_COMPOUND);
        sections.getCompound(0).getByteArray("SkyLight")[0] = 99;
        CompoundTag first = sections.getCompound(0).getCompound("block_states")
                .getList("palette", Tag.TAG_COMPOUND).getCompound(0);
        CompoundTag second = sections.getCompound(1).getCompound("block_states")
                .getList("palette", Tag.TAG_COMPOUND).getCompound(0);
        assertNotSame(first, second);
        first.getCompound("Properties").putString("waterlogged", "changed");
        assertEquals("false", second.getCompound("Properties").getString("waterlogged"));
        assertEquals(1, stored.getCompound("metadata").getIntArray("array")[0]);
        assertEquals(Long.MIN_VALUE, stored.getLongArray("other-array")[0]);
        assertEquals(0, stored.getList("sections", Tag.TAG_COMPOUND).getCompound(0).getByteArray("SkyLight")[0]);
        assertEquals(original, ChunkCodec.decode(stored));
    }

    @Test
    void rejectsOversizedMetadataBeforeCallingCopy() throws Exception {
        CompoundTag root = root();
        CompoundTag metadata = copyForbidden();
        ByteArrayTag shared = new ByteArrayTag(new byte[1024 * 1024]);
        for (int i = 0; i < 64; i++) metadata.put("array" + i, shared);
        root.put("metadata", metadata);
        CompoundTag stored = envelope(root, state(), new int[]{0}, new byte[]{0});
        IOException error = assertThrows(IOException.class, () -> ChunkCodec.decode(stored));
        assertTrue(error.getMessage().contains("exceeds limit"));
    }

    @Test
    void rejectsUnwritableMetadataBeforeCallingCopy() throws Exception {
        for (boolean oversizedString : new boolean[]{false, true}) {
            CompoundTag root = root();
            CompoundTag metadata = copyForbidden();
            root.put("metadata", metadata);
            if (oversizedString) {
                metadata.putString("text", "\u0000".repeat(32768));
            } else {
                CompoundTag current = metadata;
                for (int i = 0; i < 511; i++) {
                    CompoundTag child = new CompoundTag();
                    current.put("nested", child);
                    current = child;
                }
            }
            CompoundTag stored = envelope(root, state(), new int[]{0}, new byte[]{0});
            assertThrows(IOException.class, () -> ChunkCodec.decode(stored));
        }
    }

    @Test
    void dictionaryDepthIsCheckedAtTheRestoredPalettePosition() throws Exception {
        for (int children : new int[]{506, 507}) {
            CompoundTag entry = state();
            CompoundTag current = entry;
            for (int i = 0; i < children; i++) {
                CompoundTag child = new CompoundTag();
                current.put("nested", child);
                current = child;
            }
            CompoundTag stored = envelope(root(), entry, new int[]{0}, new byte[]{0});
            if (children == 506) {
                CompoundTag restored = ChunkCodec.decode(stored);
                byte[] bytes = ChunkCodec.serialize(restored);
                assertNotNull(NbtIo.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes)),
                        NbtAccounter.unlimitedHeap()));
            } else {
                IOException error = assertThrows(IOException.class, () -> ChunkCodec.decode(stored));
                assertTrue(error.getMessage().contains("depth limit"));
            }
        }
    }

    @Test
    void acceptsExactly64MiBAndRejectsOneMoreByteBeforePaletteCopies() throws Exception {
        CompoundTag entry = state();
        entry.putByteArray("extra", new byte[16 * 1024 - 64]);
        CompoundTag root = root();
        root.putString("\u0000界🌍", "\u0000界🌍");
        root.putByteArray("padding", new byte[0]);
        int paletteSize = 4096;
        int[] ids = new int[paletteSize];
        long[] data = IndexCodec.packVanilla(new int[4096], paletteSize);
        long expanded = ChunkCodec.serialize(root).length + 31L
                + paletteSize * (ChunkCodec.serialize(entry).length - 3L) + 11L + data.length * 8L;
        int padding = Math.toIntExact(ChunkCodec.MAX_BYTES - expanded);
        assertTrue(padding > 0);
        root.putByteArray("padding", new byte[padding]);
        byte[] indices = IndexCodec.encodeValues(new int[4096], paletteSize, 3);
        CompoundTag stored = envelope(root, entry, ids, indices);
        CompoundTag restored = ChunkCodec.decode(stored);
        assertEquals(ChunkCodec.MAX_BYTES, serializedSize(restored));
        ListTag palette = restored.getList("sections", Tag.TAG_COMPOUND).getCompound(0)
                .getCompound("block_states").getList("palette", Tag.TAG_COMPOUND);
        assertArrayEquals(data, restored.getList("sections", Tag.TAG_COMPOUND).getCompound(0)
                .getCompound("block_states").getLongArray("data"));
        assertNotSame(palette.getCompound(0), palette.getCompound(1));
        palette.getCompound(0).getByteArray("extra")[0] = 1;
        assertEquals(0, palette.getCompound(1).getByteArray("extra")[0]);
        stored.putByteArray("padding", new byte[padding + 1]);
        IOException error = assertThrows(IOException.class, () -> ChunkCodec.decode(stored));
        assertTrue(error.getMessage().contains("exceeds limit"));
    }

    private static CompoundTag copyForbidden() {
        return new CompoundTag() {
            @Override
            public CompoundTag copy() {
                throw new AssertionError("Copied before validation or copied the storage marker");
            }
        };
    }

    private static CompoundTag root() {
        CompoundTag root = new CompoundTag();
        root.putString("Status", "minecraft:full");
        ListTag sections = new ListTag();
        sections.add(new CompoundTag());
        root.put("sections", sections);
        return root;
    }

    private static CompoundTag state() {
        CompoundTag entry = new CompoundTag();
        entry.putString("Name", "test:state");
        return entry;
    }

    private static CompoundTag envelope(CompoundTag root, CompoundTag entry, int[] ids, byte[] indices) throws IOException {
        ListTag entries = new ListTag();
        entries.add(entry);
        CompoundTag dictionary = new CompoundTag();
        dictionary.put("states", entries);
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        out.writeInt(0x43535131);
        NbtIo.write(dictionary, out);
        IndexCodec.writeVarInt(out, 1);
        IndexCodec.writeVarInt(out, 0);
        IndexCodec.writeVarInt(out, ids.length);
        for (int id : ids) IndexCodec.writeVarInt(out, id);
        IndexCodec.writeVarInt(out, indices.length);
        out.write(indices);
        byte[] payload = bytes.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(payload);
        CompoundTag marker = new CompoundTag();
        marker.putInt("version", ChunkCodec.VERSION);
        marker.putLong("crc32", crc.getValue());
        marker.putByteArray("payload", payload);
        root.put(ChunkCodec.KEY, marker);
        return root;
    }

    private static long serializedSize(CompoundTag tag) throws IOException {
        var counter = new OutputStream() {
            long size;
            @Override public void write(int value) { size++; }
            @Override public void write(byte[] data, int offset, int length) { size += length; }
        };
        NbtIo.write(tag, new DataOutputStream(counter));
        return counter.size;
    }
}
