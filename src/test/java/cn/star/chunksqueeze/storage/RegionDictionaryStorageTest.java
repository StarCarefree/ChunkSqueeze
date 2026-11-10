package cn.star.chunksqueeze.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class RegionDictionaryStorageTest {
    @TempDir Path directory;

    private Path path(String name) throws IOException {
        return Files.createDirectories(directory.resolve(name)).resolve("r.0.0.cqs");
    }

    private Path preparedPath() throws IOException {
        return Files.createDirectories(directory.resolve(".chunksqueeze-migration")
                .resolve(UUID.randomUUID().toString()).resolve("prepared")).resolve("r.0.0.cqs");
    }

    private static byte[] bytes(int length, int seed) {
        byte[] data = new byte[length];
        new Random(seed).nextBytes(data);
        return data;
    }

    private static byte[] dictionary(int length, int seed) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var zlib = new DeflaterOutputStream(output)) {
            // 根 compound 包含名为 data 的 byte array，底层只保存压缩字节。
            zlib.write(new byte[]{10, 0, 0, 7, 0, 4, 'd', 'a', 't', 'a'});
            zlib.write(ByteBuffer.allocate(4).putInt(length).array());
            zlib.write(bytes(length, seed));
            zlib.write(0);
        }
        return output.toByteArray();
    }

    @Test
    void dictionaryRoundTripIsIndependentAndOrdinaryWritesKeepIt() throws Exception {
        Path file = path("roundtrip");
        byte[] dictionary = dictionary(900, 1), payload = bytes(700, 2);
        UUID identity;
        try (var region = CompactRegion.open(file, 0, 0, true)) {
            assertNull(region.dictionary());
            identity = region.identity();
            byte[] supplied = dictionary.clone();
            region.writeWithDictionary(0, payload, 4, -7, supplied);
            supplied[0] ^= 1;
            byte[] returned = region.dictionary();
            assertArrayEquals(dictionary, returned);
            returned[0] ^= 1;
            assertArrayEquals(dictionary, region.dictionary());
            region.write(1, bytes(100, 3), 2, 11);
            region.write(2, bytes(100, 4), 4, 12);
            region.writeWithDictionary(3, bytes(100, 5), 4, 13, null);
            region.write(1, null, 0, 14);
            assertArrayEquals(dictionary, region.dictionary());
            assertEquals(4, region.read(0).codec());
        }
        try (var region = CompactRegion.open(file, 0, 0, false)) {
            assertEquals(identity, region.identity());
            assertArrayEquals(dictionary, region.dictionary());
            assertArrayEquals(payload, region.read(0).payload());
            assertEquals(-7, region.timestamp(0));
            assertEquals(4, region.read(2).codec());
            assertEquals(4, region.read(3).codec());
        }
        assertEquals(2, ByteBuffer.wrap(read(file, 0, 512)).getInt(8));
        assertEquals(1, ByteBuffer.wrap(read(CompactRegion.ownerPath(file), 0, 512)).getInt(8));
        byte[] marker = read(file, 512, 512);
        assertArrayEquals(marker, read(file, 1024, 512));
        assertEquals(0, ByteBuffer.wrap(marker).getInt(56));
        assertEquals(2, ByteBuffer.wrap(marker).getInt(60));
        assertArrayEquals(new byte[512 - 76], Arrays.copyOfRange(marker, 76, 512));
    }

    @Test
    void v1ReadsAndWritesStayCompatibleThenUpgradeKeepsIdentityAndOwner() throws Exception {
        Path file = path("legacy");
        byte[] before = bytes(800, 1), neighbor = bytes(900, 2), dictionary = dictionary(600, 3);
        UUID identity;
        try (var region = CompactRegion.open(file, 0, 0, true)) {
            region.write(0, before, 2, 1);
            identity = region.identity();
        }
        makeV1(file);
        byte[] owner = Files.readAllBytes(CompactRegion.ownerPath(file));
        try (var region = CompactRegion.open(file, 0, 0, false)) {
            assertArrayEquals(before, region.read(0).payload());
            assertNull(region.dictionary());
            region.write(1, neighbor, 3, 2);
            region.compact();
            assertEquals(1, ByteBuffer.wrap(read(file, 0, 512)).getInt(8));
            assertArrayEquals(new byte[512 - 44], read(file, 512 + 44, 512 - 44));
            long generation = region.generation();
            region.writeWithDictionary(0, before, 4, 3, dictionary);
            assertEquals(generation + 1, region.generation());
            assertEquals(identity, region.identity());
        }
        assertArrayEquals(owner, Files.readAllBytes(CompactRegion.ownerPath(file)));
        assertEquals(2, ByteBuffer.wrap(read(file, 0, 512)).getInt(8));
        try (var region = CompactRegion.open(file, 0, 0, false)) {
            assertEquals(identity, region.identity());
            assertArrayEquals(before, region.read(0).payload());
            assertEquals(4, region.read(0).codec());
            assertArrayEquals(neighbor, region.read(1).payload());
            assertArrayEquals(dictionary, region.dictionary());
        }
    }

    @Test
    void interruptedFirstDictionaryWriteAfterV1UpgradeKeepsAWholeCommit() throws Exception {
        for (var stop : CompactRegion.Stage.values()) {
            Path file = path("upgrade-" + stop);
            byte[] before = bytes(900, 1), after = bytes(1200, 2), dictionary = dictionary(700, 3);
            try (var region = CompactRegion.open(file, 0, 0, true)) { region.write(0, before, 2, 1); }
            makeV1(file);
            byte[] owner = Files.readAllBytes(CompactRegion.ownerPath(file));
            try (var region = CompactRegion.open(file, 0, 0, false)) {
                region.setFaultInjector(stage -> { if (stage == stop) throw new IOException("interruption"); });
                assertThrows(IOException.class, () -> region.writeWithDictionary(0, after, 4, 2, dictionary));
            }
            assertEquals(2, ByteBuffer.wrap(read(file, 0, 512)).getInt(8));
            assertArrayEquals(owner, Files.readAllBytes(CompactRegion.ownerPath(file)));
            try (var region = CompactRegion.open(file, 0, 0, false)) {
                boolean published = stop.ordinal() >= CompactRegion.Stage.FIRST_COMMIT_FORCED.ordinal();
                assertArrayEquals(published ? dictionary : null, region.dictionary());
                assertArrayEquals(published ? after : before, region.read(0).payload());
                assertEquals(published ? 4 : 2, region.read(0).codec());
            }
        }
    }

    @Test
    void dictionaryGrowthAndChunkAreAtomicAtEveryStageWithoutOverwritingOldAllocations() throws Exception {
        for (var stop : CompactRegion.Stage.values()) {
            Path file = path("atomic-" + stop);
            byte[] oldDictionary = dictionary(700, 1), newDictionary = dictionary(8193, 2);
            byte[] before = bytes(700, 3), after = bytes(1500, 4), neighbor = bytes(900, 5);
            long oldDictionaryOffset, oldChunkOffset;
            try (var region = CompactRegion.open(file, 0, 0, true)) {
                region.writeWithDictionary(0, before, 4, 1, oldDictionary);
                region.write(1, neighbor, 4, 1);
                region.write(2, bytes(2048, 6), 2, 1);
                region.write(3, bytes(513, 7), 2, 1);
                region.write(2, null, 0, 1);
                byte[] marker = read(file, 512, 512);
                oldDictionaryOffset = ByteBuffer.wrap(marker).getLong(44);
                int tableSlot = ByteBuffer.wrap(marker).getInt(16);
                oldChunkOffset = ByteBuffer.wrap(read(file, 1536L + tableSlot * CompactRegion.TABLE_BYTES, 32)).getLong();
                region.setFaultInjector(stage -> { if (stage == stop) throw new IOException("interruption"); });
                assertThrows(IOException.class, () -> region.writeWithDictionary(0, after, 4, 2, newDictionary));
                assertArrayEquals(oldDictionary, read(file, oldDictionaryOffset, oldDictionary.length));
                assertArrayEquals(before, read(file, oldChunkOffset, before.length));
                assertThrows(IOException.class, region::dictionary);
            }
            try (var region = CompactRegion.open(file, 0, 0, false)) {
                boolean published = stop.ordinal() >= CompactRegion.Stage.FIRST_COMMIT_FORCED.ordinal();
                assertArrayEquals(published ? newDictionary : oldDictionary, region.dictionary());
                assertArrayEquals(published ? after : before, region.read(0).payload());
                assertArrayEquals(neighbor, region.read(1).payload());
                assertEquals(published ? 2 : 1, region.timestamp(0));
            }
        }
    }

    @Test
    void mirrorRepairProtectsTheNewDictionaryBeforeAnotherInterruptedWrite() throws Exception {
        Path file = path("repair");
        byte[] oldDictionary = dictionary(700, 1), newDictionary = dictionary(1800, 2), payload = bytes(800, 3);
        try (var region = CompactRegion.open(file, 0, 0, true)) {
            region.writeWithDictionary(0, payload, 4, 1, oldDictionary);
            region.setFaultInjector(stage -> {
                if (stage == CompactRegion.Stage.FIRST_COMMIT_FORCED) throw new IOException("interruption");
            });
            assertThrows(IOException.class, () -> region.writeWithDictionary(0, payload, 4, 2, newDictionary));
        }
        try (var region = CompactRegion.open(file, 0, 0, false)) {
            assertArrayEquals(newDictionary, region.dictionary());
            region.setFaultInjector(stage -> {
                if (stage == CompactRegion.Stage.INDEX_FORCED) throw new IOException("interruption");
            });
            assertThrows(IOException.class, () -> region.writeWithDictionary(1, payload, 4, 3, oldDictionary));
        }
        flip(file, 512 + 40);
        try (var region = CompactRegion.open(file, 0, 0, false)) {
            assertArrayEquals(newDictionary, region.dictionary());
            assertEquals(2, region.timestamp(0));
            assertNull(region.read(1));
        }
    }

    @Test
    void trimAndCompactionPreserveADictionaryWithoutReferenceChunks() throws Exception {
        Path file = path("trim");
        byte[] dictionary = dictionary(1300, 1);
        UUID id;
        try (var region = CompactRegion.open(file, 0, 0, true)) {
            id = region.identity();
            region.write(0, bytes(8192, 2), 2, 1);
            region.writeWithDictionary(1, null, 0, 2, dictionary);
            long end = Files.size(file);
            region.write(0, null, 0, 3);
            assertEquals(end, Files.size(file));
            assertArrayEquals(dictionary, region.dictionary());
            region.compact();
            assertEquals(CompactRegion.DATA_START + ((dictionary.length + 511L) / 512) * 512, Files.size(file));
            assertArrayEquals(dictionary, region.dictionary());
            assertEquals(id, region.identity());
            region.write(2, bytes(513, 3), 4, 4);
            region.write(2, null, 0, 5);
            assertArrayEquals(dictionary, region.dictionary());
        }
        try (var region = CompactRegion.open(file, 0, 0, false)) {
            assertArrayEquals(dictionary, region.dictionary());
            assertEquals(id, region.identity());
            assertNull(region.read(2));
        }
    }

    @Test
    void compactIfWastefulCountsDictionaryAllocationAndPreservesReferences() throws Exception {
        Path file = path("waste");
        byte[] dictionary = dictionary(2 * 1024 * 1024, 1), payload = bytes(600, 2);
        try (var region = CompactRegion.open(file, 0, 0, true)) {
            region.writeWithDictionary(0, payload, 4, 1, dictionary);
            assertFalse(region.compactIfWasteful());
            region.write(1, bytes(2 * 1024 * 1024, 3), 2, 1);
            region.write(2, payload, 4, 1);
            region.write(1, null, 0, 2);
            assertTrue(region.compactIfWasteful());
            assertArrayEquals(dictionary, region.dictionary());
            assertArrayEquals(payload, region.read(0).payload());
            assertArrayEquals(payload, region.read(2).payload());
        }
        try (var region = CompactRegion.open(file, 0, 0, false)) {
            assertArrayEquals(dictionary, region.dictionary());
            assertEquals(4, region.read(2).codec());
        }
    }

    @Test
    void committedBadDictionaryNeverFallsBackAndDictionaryReadsCheckCrc() throws Exception {
        Path file = path("crc");
        byte[] payload = bytes(600, 1), oldDictionary = dictionary(600, 2), newDictionary = dictionary(900, 3);
        try (var region = CompactRegion.open(file, 0, 0, true)) {
            region.writeWithDictionary(0, payload, 4, 1, oldDictionary);
            region.setFaultInjector(stage -> {
                if (stage == CompactRegion.Stage.FIRST_COMMIT_FORCED) throw new IOException("interruption");
            });
            assertThrows(IOException.class, () -> region.writeWithDictionary(0, payload, 4, 2, newDictionary));
        }
        long offset = ByteBuffer.wrap(read(file, 512, 512)).getLong(44);
        try (var region = CompactRegion.open(file, 0, 0, false)) {
            flip(file, offset);
            assertThrows(IOException.class, region::dictionary);
        }
        assertThrows(IOException.class, () -> CompactRegion.open(file, 0, 0, false));
    }

    @Test
    void descriptorValidationRejectsBoundsMetadataOverlapAndOversizeWithoutFallback() throws Exception {
        for (int invalid = 0; invalid < 7; invalid++) {
            Path file = path("descriptor-" + invalid);
            try (var region = CompactRegion.open(file, 0, 0, true)) {
                region.writeWithDictionary(0, bytes(700, 1), 4, 1, dictionary(700, 2));
            }
            byte[] marker = read(file, 512, 512);
            ByteBuffer buffer = ByteBuffer.wrap(marker);
            switch (invalid) {
                case 0 -> buffer.putLong(44, CompactRegion.DATA_START + 1L);
                case 1 -> buffer.putInt(52, CompactRegion.MAX_DICTIONARY + 1);
                case 2 -> buffer.putInt(56, 1);
                case 3 -> buffer.putInt(60, 4);
                case 4 -> buffer.putLong(68, buffer.getLong(8) + 1);
                case 5 -> buffer.putLong(44, Files.size(file) + 512);
                case 6 -> {
                    // 将 chunk 指向字典，且使用正确 CRC，单独检验 allocation 重叠。
                    int tableSlot = buffer.getInt(16);
                    byte[] table = read(file, 1536L + tableSlot * CompactRegion.TABLE_BYTES, CompactRegion.TABLE_BYTES);
                    ByteBuffer.wrap(table).putLong(0, buffer.getLong(44));
                    write(file, 1536L + tableSlot * CompactRegion.TABLE_BYTES, table);
                    buffer.putInt(20, crc(table));
                }
            }
            stamp(marker);
            write(file, 512, marker);
            write(file, 1024, marker);
            assertThrows(IOException.class, () -> CompactRegion.open(file, 0, 0, false));
        }
    }

    @Test
    void codec4WithoutDictionaryIsRejectedByWriterAndLoader() throws Exception {
        Path file = path("missing");
        byte[] payload = bytes(600, 1);
        try (var region = CompactRegion.open(file, 0, 0, true)) {
            assertThrows(IOException.class, () -> region.write(0, payload, 4, 1));
            assertThrows(IOException.class, () -> region.writeWithDictionary(0, payload, 4, 1, null));
            assertThrows(IOException.class, () -> region.writeWithDictionary(0, payload, 4, 1, new byte[0]));
            assertThrows(IOException.class, () -> region.writeWithDictionary(0, payload, 4, 1,
                    new byte[CompactRegion.MAX_DICTIONARY + 1]));
            assertEquals(0, region.generation());
            region.write(0, payload, 2, 1);
        }
        byte[] marker = read(file, 512, 512);
        int slot = ByteBuffer.wrap(marker).getInt(16);
        long tableOffset = 1536L + slot * CompactRegion.TABLE_BYTES;
        byte[] table = read(file, tableOffset, CompactRegion.TABLE_BYTES);
        ByteBuffer.wrap(table).putInt(16, 4);
        write(file, tableOffset, table);
        ByteBuffer.wrap(marker).putInt(20, crc(table));
        stamp(marker);
        write(file, 512, marker);
        write(file, 1024, marker);
        assertThrows(IOException.class, () -> CompactRegion.open(file, 0, 0, false));
    }

    @Test
    void exactDictionarySizeLimitIsAccepted() throws Exception {
        Path file = path("limit");
        byte[] dictionary = bytes(CompactRegion.MAX_DICTIONARY, 1);
        try (var region = CompactRegion.open(file, 0, 0, true)) {
            region.writeWithDictionary(0, null, 0, 1, dictionary);
            assertArrayEquals(dictionary, region.dictionary());
        }
        try (var region = CompactRegion.open(file, 0, 0, false)) {
            assertArrayEquals(dictionary, region.dictionary());
        }
    }

    @Test
    void preparedDictionaryIsCopiedAndRecoveredWithChunks() throws Exception {
        Path file = preparedPath();
        byte[] dictionary = dictionary(900, 1), payload = bytes(700, 2);
        try (var builder = CompactRegion.createPrepared(file, 0, 0)) {
            byte[] supplied = dictionary.clone();
            builder.setDictionary(supplied);
            supplied[0] ^= 1;
            builder.write(0, payload, 4, -9);
            builder.write(1, payload, 2, 8);
            builder.finish();
            assertThrows(IOException.class, () -> builder.setDictionary(dictionary));
        }
        try (var region = CompactRegion.open(file, 0, 0, false)) {
            assertEquals(1, region.generation());
            assertArrayEquals(dictionary, region.dictionary());
            assertArrayEquals(payload, region.read(0).payload());
            assertEquals(4, region.read(0).codec());
            assertEquals(-9, region.timestamp(0));
            region.compact();
            region.write(2, payload, 4, 10);
            assertArrayEquals(dictionary, region.dictionary());
        }
    }

    @Test
    void preparedDictionarySharesEveryBuildFailureBoundary() throws Exception {
        for (var stop : CompactRegion.BuildStage.values()) {
            Path file = preparedPath();
            byte[] dictionary = dictionary(900, 1), payload = bytes(600, 2);
            try (var builder = CompactRegion.createPrepared(file, 0, 0)) {
                builder.setDictionary(dictionary);
                builder.setFaultInjector(stage -> { if (stage == stop) throw new IOException("interruption"); });
                if (stop == CompactRegion.BuildStage.PAYLOAD_WRITTEN) {
                    assertThrows(IOException.class, () -> builder.write(0, payload, 4, 1));
                } else {
                    builder.write(0, payload, 4, 1);
                    assertThrows(IOException.class, builder::finish);
                }
                assertThrows(IOException.class, () -> builder.setDictionary(dictionary));
            }
            if (stop == CompactRegion.BuildStage.COMPLETE_FORCED) {
                try (var region = CompactRegion.open(file, 0, 0, false)) {
                    assertArrayEquals(dictionary, region.dictionary());
                    assertArrayEquals(payload, region.read(0).payload());
                    assertEquals(4, region.read(0).codec());
                }
            } else assertThrows(IOException.class, () -> CompactRegion.open(file, 0, 0, false));
        }
    }

    @Test
    void preparedDictionaryCannotBeRemovedAfterReferenceChunksOrExceedSizeLimit() throws Exception {
        for (int invalid = 0; invalid < 3; invalid++) {
            Path file = preparedPath();
            try (var builder = CompactRegion.createPrepared(file, 0, 0)) {
                if (invalid == 0) {
                    builder.setDictionary(dictionary(600, 1));
                    builder.write(0, bytes(600, 2), 4, 1);
                    assertThrows(IOException.class, () -> builder.setDictionary(null));
                } else if (invalid == 1) {
                    assertThrows(IOException.class, () -> builder.setDictionary(new byte[0]));
                } else {
                    assertThrows(IOException.class, () -> builder.setDictionary(new byte[CompactRegion.MAX_DICTIONARY + 1]));
                }
                assertThrows(IOException.class, builder::finish);
            }
            assertThrows(IOException.class, () -> CompactRegion.open(file, 0, 0, false));
        }
    }

    private static void makeV1(Path file) throws IOException {
        byte[] header = read(file, 0, 512);
        ByteBuffer.wrap(header).putInt(8, 1);
        stamp(header);
        write(file, 0, header);
    }

    private static int crc(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return (int) crc.getValue();
    }

    private static void stamp(byte[] page) {
        CRC32 crc = new CRC32();
        crc.update(page, 0, 40);
        crc.update(page, 44, page.length - 44);
        ByteBuffer.wrap(page).putInt(40, (int) crc.getValue());
    }

    private static byte[] read(Path file, long offset, int length) throws IOException {
        try (var input = new RandomAccessFile(file.toFile(), "r")) {
            input.seek(offset);
            byte[] bytes = new byte[length];
            input.readFully(bytes);
            return bytes;
        }
    }

    private static void write(Path file, long offset, byte[] bytes) throws IOException {
        try (var output = new RandomAccessFile(file.toFile(), "rw")) {
            output.seek(offset);
            output.write(bytes);
        }
    }

    private static void flip(Path file, long offset) throws IOException {
        try (var output = new RandomAccessFile(file.toFile(), "rw")) {
            output.seek(offset);
            int value = output.read();
            output.seek(offset);
            output.write(value ^ 1);
        }
    }
}
