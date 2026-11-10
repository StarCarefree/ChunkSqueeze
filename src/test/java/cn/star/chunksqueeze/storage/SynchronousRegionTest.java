package cn.star.chunksqueeze.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class SynchronousRegionTest {
    @TempDir Path folder;

    @Test
    void syncAndForceChannelsBothRecoverWholeDictionaryCommitsAtEveryStage() throws Exception {
        for (boolean synchronous : new boolean[]{false, true}) {
            for (CompactRegion.Stage stop : CompactRegion.Stage.values()) {
                Path file = folder.resolve(synchronous + "-" + stop).resolve("r.0.0.cqs");
                byte[] before = bytes(513, 1), after = bytes(789, 2);
                byte[] oldDictionary = bytes(137, 3), newDictionary = bytes(1031, 4);
                try (CompactRegion region = CompactRegion.open(file, 0, 0, true, synchronous)) {
                    assertEquals(synchronous, region.usesSynchronousWrites());
                    region.writeWithDictionary(0, before, 5, 1, oldDictionary);
                    region.write(1, before, 5, 2);
                    region.setFaultInjector(stage -> { if (stage == stop) throw new IOException("simulated interruption"); });
                    assertThrows(IOException.class, () -> region.writeWithDictionary(0, after, 5, 3, newDictionary));
                }
                try (CompactRegion region = CompactRegion.open(file, 0, 0, false, synchronous)) {
                    boolean committed = stop.ordinal() >= CompactRegion.Stage.FIRST_COMMIT_FORCED.ordinal();
                    assertArrayEquals(committed ? after : before, region.read(0).payload());
                    assertArrayEquals(committed ? newDictionary : oldDictionary, region.dictionary());
                    assertArrayEquals(before, region.read(1).payload());
                    region.write(2, after, 5, 4);
                    region.compact();
                    assertArrayEquals(before, region.read(1).payload());
                    assertArrayEquals(after, region.read(2).payload());
                    assertEquals(synchronous, region.usesSynchronousWrites());
                }
            }
        }
    }

    @Test
    void missingTailPaddingIsValidAndNextAllocationRemainsAligned() throws Exception {
        for (boolean synchronous : new boolean[]{false, true}) {
            Path file = folder.resolve("padding-" + synchronous).resolve("r.0.0.cqs");
            byte[] first = bytes(513, 11), next = bytes(137, 12);
            try (CompactRegion region = CompactRegion.open(file, 0, 0, true, synchronous)) {
                region.write(0, first, 2, 1);
                assertEquals(CompactRegion.DATA_START + first.length, Files.size(file));
            }
            try (CompactRegion region = CompactRegion.open(file, 0, 0, false, synchronous)) {
                assertArrayEquals(first, region.read(0).payload());
                region.write(1, next, 2, 2);
                assertEquals(CompactRegion.DATA_START + 1024 + next.length, Files.size(file));
                region.write(1, null, 0, 3);
                region.flush();
            }
            try (CompactRegion region = CompactRegion.open(file, 0, 0, false, synchronous)) {
                assertArrayEquals(first, region.read(0).payload());
                assertNull(region.read(1));
            }
        }
    }

    private static byte[] bytes(int size, int value) {
        byte[] result = new byte[size];
        Arrays.fill(result, (byte) value);
        return result;
    }
}
