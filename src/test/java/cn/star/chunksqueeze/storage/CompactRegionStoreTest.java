package cn.star.chunksqueeze.storage;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.*;
import java.util.Arrays;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class CompactRegionStoreTest {
    @TempDir Path folder;
    private static final ChunkPos POS = new ChunkPos(0, 0);
    private static CompoundTag tag() { CompoundTag tag = new CompoundTag(); tag.putInt("value", 123); return tag; }

    @Test
    void cachedReadsStillValidatePayloadCrc() throws Exception {
        try (CompactRegionStore store = new CompactRegionStore(folder)) {
            store.write(POS, tag());
            assertTrue(store.hasOpenRegion(POS));
            assertEquals(tag(), store.read(POS));
            try (var file = new RandomAccessFile(folder.resolve("r.0.0.cqs").toFile(), "rw")) {
                file.seek(CompactRegion.DATA_START); int value = file.readUnsignedByte();
                file.seek(CompactRegion.DATA_START); file.writeByte(value ^ 1);
            }
            assertThrows(IOException.class, () -> store.read(POS));
        }
    }

    @Test
    void writesFlushAndCloseCheckOwnershipAndReleaseHandlesOnFailure() throws Exception {
        for (boolean removeOwner : new boolean[]{false, true}) {
            Path path = Files.createDirectory(folder.resolve("case-" + removeOwner));
            try (CompactRegionStore creator = new CompactRegionStore(path)) { creator.write(POS, tag()); }
            Path cqs = path.resolve("r.0.0.cqs"), owner = CompactRegion.ownerPath(cqs);
            byte[] identity = Files.readAllBytes(owner);
            CompactRegionStore store = new CompactRegionStore(path);
            assertEquals(tag(), store.read(POS));
            if (removeOwner) Files.delete(owner);
            else Files.createFile(path.resolve("r.0.0.mca"));
            assertThrows(IOException.class, () -> store.write(POS, tag()));
            assertThrows(IOException.class, store::flush);
            assertThrows(IOException.class, store::close);
            assertFalse(store.hasOpenRegion(POS));
            if (removeOwner) Files.write(owner, identity, StandardOpenOption.CREATE_NEW);
            else Files.delete(path.resolve("r.0.0.mca"));
            try (CompactRegionStore reopened = new CompactRegionStore(path)) {
                assertEquals(tag(), reopened.read(POS));
            }
        }
    }

    @Test
    void bufferedReadersSupportAllCodecsAndRejectTrailingNbt() throws Exception {
        var raw = new ByteArrayOutputStream();
        try (var data = new DataOutputStream(raw)) { NbtIo.write(tag(), data); }
        for (int codec = 1; codec <= 3; codec++) {
            for (boolean trailing : new boolean[]{false, true}) {
                Path path = Files.createDirectory(folder.resolve(codec + "-" + trailing));
                byte[] bytes = trailing ? Arrays.copyOf(raw.toByteArray(), raw.size() + 1) : raw.toByteArray();
                var payload = new ByteArrayOutputStream();
                OutputStream compressed = switch (codec) {
                    case 1 -> new GZIPOutputStream(payload);
                    case 2 -> new DeflaterOutputStream(payload);
                    default -> payload;
                };
                try (compressed) { compressed.write(bytes); }
                try (CompactRegion region = CompactRegion.open(path.resolve("r.0.0.cqs"), 0, 0, true)) {
                    region.write(0, payload.toByteArray(), codec, 10);
                }
                try (CompactRegionStore store = new CompactRegionStore(path)) {
                    if (trailing) assertThrows(IOException.class, () -> store.read(POS));
                    else assertEquals(tag(), store.read(POS));
                }
            }
        }
    }
}
