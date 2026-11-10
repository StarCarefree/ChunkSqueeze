package cn.star.chunksqueeze.codec;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class IndexCodecTest {
    @Test
    void directPackingMatchesVanillaAcrossModesAndWordBoundaries() throws Exception {
        Random random = new Random(58341);
        for (int size : new int[]{1, 2, 3, 8, 15, 16, 17, 31, 32, 33, 63, 64, 65, 127, 128, 129, 255, 256, 257,
                511, 512, 513, 1023, 1024, 1025, 2047, 2048, 2049, 4095, 4096}) {
            for (int shape = 0; shape < 3; shape++) {
                int[] values = new int[4096];
                for (int i = 0; i < values.length; i++) {
                    values[i] = switch (shape) {
                        case 0 -> random.nextInt(size);
                        case 1 -> (i / 37) % size;
                        default -> i % 251 == 0 ? 0 : size - 1;
                    };
                }
                long[] expected = IndexCodec.packVanilla(values, size);
                for (int mode = 2; mode <= 5; mode++) {
                    assertArrayEquals(expected, IndexCodec.decode(IndexCodec.encodeValues(values, size, mode), size),
                            "palette=" + size + " shape=" + shape + " mode=" + mode);
                }
                Arrays.fill(values, size - 1);
                for (int mode : new int[]{3, 4}) {
                    assertArrayEquals(IndexCodec.packVanilla(values, size),
                            IndexCodec.decode(IndexCodec.encodeValues(values, size, mode), size));
                }
            }
        }
    }

    @Test
    void candidatePruningPreservesIndicesAroundRunAndSparsityThresholds() throws Exception {
        for (int size : new int[]{2, 17, 257}) {
            for (int shape = 0; shape < 6; shape++) {
                int[] values = new int[4096];
                if (shape < 3) {
                    for (int i = 0; i < values.length; i++) values[i] = (i / (shape + 3)) % size;
                } else {
                    for (int i = 0; i < 511 + shape - 3; i++) values[i * 7 % values.length] = size - 1;
                }
                long[] original = IndexCodec.packVanilla(values, size);
                try (EncodeContext context = new EncodeContext()) {
                    IndexCodec.Analysis result = context.indices(original, size);
                    assertArrayEquals(original, IndexCodec.decode(result.raw(), size));
                    assertArrayEquals(original, IndexCodec.decode(result.adaptive(), size));
                }
            }
        }
    }

    @Test
    void rawModePreservesPaddingWithoutAnUnpackedArray() throws Exception {
        int[] values = new int[4096];
        Arrays.fill(values, 16);
        long[] packed = IndexCodec.packVanilla(values, 17);
        for (int i = 0; i < packed.length; i++) packed[i] |= 0xf000000000000000L;
        byte[] encoded = IndexCodec.encode(packed, 17, true);
        assertEquals(1, encoded[0]);
        assertArrayEquals(packed, IndexCodec.decode(encoded, 17));
    }

    @Test
    void analysisPreservesFinalWordPaddingAndRejectsInvalidIndices() throws Exception {
        try (EncodeContext context = new EncodeContext()) {
            long[] padded = IndexCodec.packVanilla(new int[4096], 17);
            padded[padded.length - 1] |= 1L << 30;
            IndexCodec.Analysis analysis = context.indices(padded, 17);
            assertEquals(1, analysis.adaptive()[0]);
            assertArrayEquals(padded, IndexCodec.decode(analysis.raw(), 17));
            assertArrayEquals(padded, IndexCodec.decode(analysis.adaptive(), 17));
            long[] invalid = padded.clone();
            invalid[0] = 17;
            assertThrows(IOException.class, () -> context.indices(invalid, 17));
            assertThrows(IOException.class, () -> context.indices(new long[2], 17));
        }
    }

    @Test
    void encodingContextEndsBothDeflatersWhenSerializationThrows() throws Exception {
        EncodeContext context = new EncodeContext();
        java.util.zip.Deflater[] deflaters = new java.util.zip.Deflater[2];
        assertThrows(IOException.class, () -> {
            try (context) {
                context.compress(new net.minecraft.nbt.CompoundTag());
                context.compressedSize(new byte[]{1, 2, 3}, java.util.zip.Deflater.BEST_SPEED);
                var normalField = EncodeContext.class.getDeclaredField("normal");
                var fastField = EncodeContext.class.getDeclaredField("fast");
                normalField.setAccessible(true);
                fastField.setAccessible(true);
                deflaters[0] = (java.util.zip.Deflater) normalField.get(context);
                deflaters[1] = (java.util.zip.Deflater) fastField.get(context);
                context.compress(new net.minecraft.nbt.CompoundTag() {
                    @Override public void write(java.io.DataOutput out) throws IOException {
                        throw new IOException("serialization failure");
                    }
                });
            }
        });
        for (var deflater : deflaters) {
            assertNotNull(deflater);
            assertThrows(NullPointerException.class, deflater::getBytesRead);
        }
        context.close();
    }

    @Test
    void directModesKeepBoundsAndTrailingDataChecks() throws Exception {
        int[] values = new int[4096];
        for (int mode = 2; mode <= 5; mode++) {
            byte[] encoded = IndexCodec.encodeValues(values, 3, mode);
            assertThrows(IOException.class, () -> IndexCodec.decode(Arrays.copyOf(encoded, encoded.length - 1), 3));
            assertThrows(IOException.class, () -> IndexCodec.decode(Arrays.copyOf(encoded, encoded.length + 1), 3));
        }
        byte[] planes = IndexCodec.encodeValues(values, 3, 5);
        planes[1] = 1;
        planes[513] = 1;
        assertThrows(IOException.class, () -> IndexCodec.decode(planes, 3));
        byte[] uniformPlanes = IndexCodec.encodeValues(values, 1, 5);
        uniformPlanes[1] = 1;
        assertThrows(IOException.class, () -> IndexCodec.decode(uniformPlanes, 1));
        byte[] compact = IndexCodec.encodeValues(values, 3, 2);
        compact[1] = 3;
        assertThrows(IOException.class, () -> IndexCodec.decode(compact, 3));
        assertThrows(IOException.class, () -> IndexCodec.decode(new byte[]{3, 0, 0}, 3));
        assertThrows(IOException.class, () -> IndexCodec.decode(new byte[]{4, 3, 0}, 3));
        long[] invalid = IndexCodec.packVanilla(values, 3);
        invalid[0] = 3;
        assertThrows(IOException.class, () -> IndexCodec.decode(IndexCodec.encode(invalid, 3, false), 3));
    }
}
