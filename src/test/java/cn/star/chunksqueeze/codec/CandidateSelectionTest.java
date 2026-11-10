package cn.star.chunksqueeze.codec;

import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.Random;
import java.util.zip.InflaterInputStream;
import static org.junit.jupiter.api.Assertions.*;

class CandidateSelectionTest {
    @Test
    void realWholeChunkPayloadControlsSavingsWithOpaqueMetadataAndSectionReferences() throws Exception {
        int optimized = 0, unchanged = 0;
        for (boolean requireUnit : new boolean[]{false, true}) {
            for (int size : new int[]{0, 511, 32768}) {
                for (int minimum : new int[]{0, 128, Integer.MAX_VALUE}) {
                    CompoundTag original = CodecTest.fixture();
                    byte[] metadata = new byte[size];
                    new Random(5300 + size).nextBytes(metadata);
                    original.putByteArray("mod:opaque", metadata);
                    CompoundTag before = original.copy();
                    RegionStateDictionary dictionary = RegionStateDictionary.create();
                    SectionCodec.encodeForStorage(original, new ChunkCodec.Options(0, false), dictionary);
                    int originalSize;
                    try (EncodeContext context = new EncodeContext()) { originalSize = context.compress(original).length; }
                    SectionCodec.Encoded encoded = SectionCodec.encodeForStorage(original,
                            new ChunkCodec.Options(minimum, requireUnit), dictionary);
                    CompoundTag stored;
                    try (var in = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(encoded.payload())))) {
                        stored = NbtIo.read(in, NbtAccounter.unlimitedHeap());
                        assertEquals(-1, in.read());
                    }
                    assertEquals(new RegionStateDictionary.ExactCompound(encoded.result().tag()),
                            new RegionStateDictionary.ExactCompound(stored));
                    assertEquals(before, original);
                    assertEquals(before, SectionCodec.decode(stored,
                            RegionStateDictionary.fromCompressed(dictionary.compressed())));
                    if (encoded.result().optimized()) {
                        optimized++;
                        assertEquals(encoded.payload().length, encoded.result().storedBytes());
                        assertEquals(originalSize, encoded.result().originalBytes());
                        assertTrue(originalSize - encoded.payload().length >= minimum);
                        if (requireUnit) assertTrue(ChunkCodec.allocatedBytes(encoded.payload().length, 512, 0)
                                < ChunkCodec.allocatedBytes(originalSize, 512, 0));
                    } else {
                        unchanged++;
                        assertSame(original, encoded.result().tag());
                    }
                }
            }
        }
        assertTrue(optimized > 0);
        assertTrue(unchanged > 0);
    }

    @Test
    void rejectedScoredCandidatesDoNotAppendPersistentDictionaryEntries() throws Exception {
        CompoundTag original = CodecTest.fixture();
        RegionStateDictionary dictionary = RegionStateDictionary.create();
        byte[] before = dictionary.compressed();
        for (int i = 0; i < 2; i++) {
            SectionCodec.Encoded encoded = SectionCodec.encodeForStorage(original,
                    new ChunkCodec.Options(Integer.MAX_VALUE, true), dictionary);
            assertSame(original, encoded.result().tag());
            assertEquals(0, dictionary.revision());
            assertArrayEquals(before, dictionary.compressed());
        }
    }
}
