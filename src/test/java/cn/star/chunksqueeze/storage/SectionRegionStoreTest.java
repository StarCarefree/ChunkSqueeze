package cn.star.chunksqueeze.storage;

import cn.star.chunksqueeze.codec.*;
import cn.star.chunksqueeze.migration.MigrationTransaction;
import net.minecraft.nbt.*;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.Path;
import java.util.Random;
import java.util.zip.DeflaterOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class SectionRegionStoreTest {
    @TempDir Path folder;
    private static final ChunkCodec.Options OPTIONS = new ChunkCodec.Options(0, false);

    @Test
    void updatesAndDeletesDoNotChangeOtherSectionReferencesAcrossCompaction() throws Exception {
        CompoundTag original = chunk(7), updated = chunk(8);
        try (CompactRegionStore store = new CompactRegionStore(folder)) {
            store.writeEncoded(new ChunkPos(0, 0), original, OPTIONS);
            assertTrue(store.writeEncoded(new ChunkPos(1, 0), original, OPTIONS).tag().contains(SectionCodec.KEY));
            assertTrue(store.writeEncoded(new ChunkPos(2, 0), original, OPTIONS).tag().contains(SectionCodec.KEY));
            store.writeEncoded(new ChunkPos(1, 0), updated, OPTIONS);
            store.write(new ChunkPos(0, 0), null);
            assertEquals(original, store.read(new ChunkPos(2, 0)));
            store.flush();
        }
        try (CompactRegion region = CompactRegion.open(folder.resolve("r.0.0.cqs"), 0, 0, false)) {
            assertEquals(5, region.read(2).codec());
            region.compact();
        }
        try (CompactRegionStore store = new CompactRegionStore(folder)) {
            assertNull(store.read(new ChunkPos(0, 0)));
            assertEquals(updated, ChunkCodec.decode(store.read(new ChunkPos(1, 0))));
            assertEquals(original, store.read(new ChunkPos(2, 0)));
        }
    }

    @Test
    void fullSectionPoolGrowthAndChunkCommitRecoverTogetherAtEveryForceStage() throws Exception {
        for (CompactRegion.Stage stage : CompactRegion.Stage.values()) {
            Path path = folder.resolve(stage.name()).resolve("r.0.0.cqs");
            RegionStateDictionary dictionary = RegionStateDictionary.create();
            CompoundTag before = chunk(1), after = chunk(2);
            SectionCodec.encode(before, OPTIONS, dictionary);
            CompoundTag first = SectionCodec.encode(before, OPTIONS, dictionary).tag();
            assertTrue(first.contains(SectionCodec.KEY));
            byte[] oldDictionary = dictionary.compressed();
            try (CompactRegion region = CompactRegion.open(path, 0, 0, true)) {
                region.writeWithDictionary(0, compress(first), 5, 1, oldDictionary);
                region.write(1, compress(first), 5, 1);
                SectionCodec.encode(after, OPTIONS, dictionary);
                CompoundTag second = SectionCodec.encode(after, OPTIONS, dictionary).tag();
                assertTrue(second.contains(SectionCodec.KEY));
                region.setFaultInjector(point -> { if (point == stage) throw new IOException("simulated interruption"); });
                assertThrows(IOException.class, () -> region.writeWithDictionary(0, compress(second), 5, 2, dictionary.compressed()));
            }
            try (CompactRegion region = CompactRegion.open(path, 0, 0, false)) {
                RegionStateDictionary reopened = RegionStateDictionary.fromCompressed(region.dictionary());
                boolean committed = stage.ordinal() >= CompactRegion.Stage.FIRST_COMMIT_FORCED.ordinal();
                assertEquals(committed ? after : before, decode(region.read(0), reopened));
                assertEquals(before, decode(region.read(1), reopened));
            }
        }
    }

    @Test
    void sectionCodecWithoutDictionaryCannotBePublished() throws Exception {
        try (CompactRegion region = CompactRegion.open(folder.resolve("r.0.0.cqs"), 0, 0, true)) {
            assertThrows(IOException.class, () -> region.write(0, new byte[]{1}, 5, 0));
        }
    }

    private static CompoundTag decode(CompactRegion.Record record, RegionStateDictionary dictionary) throws IOException {
        return MigrationTransaction.readNbt(record.payload(), record.codec(), dictionary);
    }

    private static byte[] compress(CompoundTag tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes))) { NbtIo.write(tag, out); }
        return bytes.toByteArray();
    }

    private static CompoundTag chunk(int seed) {
        CompoundTag root = new CompoundTag();
        root.putString("Status", "minecraft:full");
        ListTag sections = new ListTag();
        CompoundTag section = new CompoundTag();
        section.putByte("Y", (byte) 0);
        byte[] payload = new byte[16384];
        new Random(seed).nextBytes(payload);
        section.putByteArray("mod:opaque", payload);
        CompoundTag states = new CompoundTag(), state = new CompoundTag();
        state.putString("Name", "minecraft:air");
        ListTag palette = new ListTag(); palette.add(state);
        states.put("palette", palette); section.put("block_states", states);
        sections.add(section); root.put("sections", sections);
        return root;
    }
}
