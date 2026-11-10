package cn.star.chunksqueeze.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RegionIntegrityTest {
    @TempDir Path directory;

    @Test
    void rejectsLocationsVanillaWouldSilentlyClear() throws Exception {
        Path file = directory.resolve("r.0.0.mca");
        for (int location : new int[]{(1 << 8) | 1, 2 << 8, (100 << 8) | 1}) {
            byte[] data = new byte[12288];
            ByteBuffer.wrap(data).putInt(location);
            Files.write(file, data);
            assertThrows(IOException.class, () -> RegionIntegrity.validateHeader(file));
            assertArrayEquals(data, Files.readAllBytes(file));
        }
        byte[] overlap = new byte[12288];
        ByteBuffer.wrap(overlap).putInt((2 << 8) | 1).putInt((2 << 8) | 1);
        Files.write(file, overlap);
        assertThrows(IOException.class, () -> RegionIntegrity.validateHeader(file));
    }

    @Test
    void acceptsUnusedSlotsAndMissingLastSectorPadding() throws Exception {
        Path file = directory.resolve("r.0.0.mca");
        RegionIntegrity.validateHeader(file);
        Files.createFile(file);
        RegionIntegrity.validateHeader(file);
        byte[] data = new byte[8192 + 20];
        ByteBuffer.wrap(data).putInt((2 << 8) | 1);
        Files.write(file, data);
        RegionIntegrity.validateHeader(file);
        Files.write(file, new byte[8191]);
        assertThrows(IOException.class, () -> RegionIntegrity.validateHeader(file));
    }
}
