package cn.star.chunksqueeze.storage;

import cn.star.chunksqueeze.codec.ChunkCodec;
import cn.star.chunksqueeze.codec.IndexCodec;
import net.minecraft.nbt.*;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class SharedRegionStoreTest {
    @TempDir Path folder;
    private static final ChunkCodec.Options OPTIONS = new ChunkCodec.Options(0, false);

    @Test
    void sharedWritesSurviveDictionaryCacheEvictionGrowthDeletionAndReopen() throws Exception {
        try (CompactRegionStore store = new CompactRegionStore(folder)) {
            for (int region = 0; region < 10; region++) {
                assertEquals(2, store.writeEncoded(new ChunkPos(region * 32, 0), fixture(region, false), OPTIONS)
                        .tag().getCompound(ChunkCodec.KEY).getInt("version"));
            }
            for (int region = 0; region < 10; region++) {
                store.writeEncoded(new ChunkPos(region * 32 + 1, 0), fixture(region, true), OPTIONS);
                assertEquals(fixture(region, false), ChunkCodec.decode(store.read(new ChunkPos(region * 32, 0))));
                assertEquals(fixture(region, true), ChunkCodec.decode(store.read(new ChunkPos(region * 32 + 1, 0))));
            }
            store.write(new ChunkPos(0, 0), null);
            store.flush();
        }
        try (CompactRegionStore store = new CompactRegionStore(folder)) {
            assertNull(store.read(new ChunkPos(0, 0)));
            for (int region = 0; region < 10; region++) {
                assertEquals(fixture(region, true), ChunkCodec.decode(store.read(new ChunkPos(region * 32 + 1, 0))));
                if (region != 0) assertEquals(fixture(region, false), ChunkCodec.decode(store.read(new ChunkPos(region * 32, 0))));
            }
        }
    }

    @Test
    void returnedStateMutationDoesNotAlterCachedDictionary() throws Exception {
        CompoundTag original = fixture(7, false);
        try (CompactRegionStore store = new CompactRegionStore(folder)) {
            store.writeEncoded(new ChunkPos(0, 0), original, OPTIONS);
            CompoundTag loaded = store.read(new ChunkPos(0, 0));
            loaded.getList("sections", Tag.TAG_COMPOUND).getCompound(0).getCompound("block_states")
                    .getList("palette", Tag.TAG_COMPOUND).getCompound(0).putString("Name", "broken:state");
            assertEquals(original, store.read(new ChunkPos(0, 0)));
        }
    }

    private static CompoundTag fixture(int seed, boolean extra) throws Exception {
        CompoundTag root = new CompoundTag();
        root.putString("Status", "minecraft:full");
        root.putString("mod:data", "unknown metadata preserved");
        ListTag sections = new ListTag();
        int count = extra ? 33 : 32;
        for (int y = 0; y < 8; y++) {
            CompoundTag section = new CompoundTag();
            section.putByte("Y", (byte) y);
            ListTag palette = new ListTag();
            for (int i = 0; i < count; i++) {
                CompoundTag state = new CompoundTag();
                state.putString("Name", "mod_" + seed + ":machine_variant_" + i);
                CompoundTag properties = new CompoundTag();
                properties.putString("facing", "north");
                properties.putString("mode", "some_long_state_property_" + i);
                state.put("Properties", properties);
                state.putIntArray("unknown_extension", new int[]{seed, i, y % 2});
                palette.add(state);
            }
            int[] values = new int[4096];
            for (int i = 0; i < values.length; i++) values[i] = (i + y) % count;
            CompoundTag states = new CompoundTag();
            states.put("palette", palette);
            states.putLongArray("data", IndexCodec.packVanilla(values, count));
            section.put("block_states", states);
            sections.add(section);
        }
        root.put("sections", sections);
        return root;
    }
}
