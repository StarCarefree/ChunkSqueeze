package cn.star.chunksqueeze.codec;

import cn.star.chunksqueeze.migration.McaSnapshot;
import cn.star.chunksqueeze.migration.MigrationTransaction;
import cn.star.chunksqueeze.storage.CompactRegion;
import net.minecraft.nbt.CompoundTag;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;

public final class CompactExportVerifier {
    public static void main(String[] args) throws Exception {
        Path input = Path.of(args[0]), output = Path.of(args[1]);
        List<Path> files;
        try (var paths = Files.list(input)) {
            files = paths.filter(p -> p.getFileName().toString().endsWith(".cqs")).sorted().toList();
        }
        long chunks = 0, sectionRecords = 0;
        for (Path file : files) {
            String[] name = file.getFileName().toString().split("\\.");
            int x = Integer.parseInt(name[1]), z = Integer.parseInt(name[2]);
            McaSnapshot snapshot = McaSnapshot.capture(output, x, z);
            Map<Integer, McaSnapshot.Chunk> restored = new HashMap<>();
            for (var chunk : snapshot.chunks()) restored.put(chunk.slot(), chunk);
            int[] timestamps = snapshot.timestamps();
            try (CompactRegion region = CompactRegion.open(file, x, z, false)) {
                byte[] bytes = region.dictionary();
                RegionStateDictionary dictionary = bytes == null ? null : RegionStateDictionary.fromCompressed(bytes);
                for (int slot = 0; slot < 1024; slot++) {
                    if (timestamps[slot] != region.timestamp(slot)) throw new IOException("Timestamp differs at " + file + ":" + slot);
                    var record = region.read(slot);
                    var actual = restored.get(slot);
                    if (record == null) {
                        if (actual != null) throw new IOException("Unexpected restored chunk");
                        continue;
                    }
                    if (actual == null) throw new IOException("Missing restored chunk");
                    CompoundTag expected = MigrationTransaction.readNbt(record.payload(), record.codec(), dictionary);
                    CompoundTag tag = MigrationTransaction.readNbt(actual.payload(), actual.codec(), null);
                    if (tag.contains(ChunkCodec.KEY) || tag.contains(SectionCodec.KEY) || !expected.equals(tag)) {
                        throw new IOException("Full restored NBT differs at " + file + ":" + slot);
                    }
                    chunks++;
                    if (record.codec() == 5) sectionRecords++;
                }
            }
        }
        if (chunks == 0 || sectionRecords == 0) throw new IOException("No exact-section records were exercised");
        System.out.println("Independent export verified: " + chunks + " chunks, " + sectionRecords + " exact-section records; full NBT and timestamps match.");
    }
}
