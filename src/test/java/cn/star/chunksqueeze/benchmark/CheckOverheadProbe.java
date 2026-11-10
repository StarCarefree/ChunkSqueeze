package cn.star.chunksqueeze.benchmark;

import cn.star.chunksqueeze.migration.MigrationGate;
import cn.star.chunksqueeze.storage.CompactRegionStore;
import com.google.gson.GsonBuilder;
import net.minecraft.world.level.ChunkPos;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class CheckOverheadProbe {
    private record Target(Path folder, ChunkPos pos, CompactRegionStore store) {}
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]), output = Path.of(args[1]);
        if (Files.exists(output)) throw new IOException("Result already exists");
        List<Map<String, Object>> results = new ArrayList<>();
        for (String world : List.of("overworld", "nether", "twilight")) {
            List<Target> targets = new ArrayList<>();
            try {
                List<Path> folders;
                try (var files = Files.list(root.resolve(world))) { folders = files.sorted().toList(); }
                for (Path path : folders) {
                    String[] parts = path.getFileName().toString().split("\\.");
                    ChunkPos pos = new ChunkPos(Integer.parseInt(parts[1]) * 32, Integer.parseInt(parts[2]) * 32);
                    Path folder = path.resolve("cqs");
                    CompactRegionStore store = new CompactRegionStore(folder);
                    targets.add(new Target(folder, pos, store));
                    if (store.read(pos) == null) throw new IOException("Missing fixture");
                }
                for (int i = 0; i < 1000; i++) checks(targets.get(i % targets.size()));
                for (int round = 0; round < 3; round++) {
                    long started = System.nanoTime();
                    for (int i = 0; i < 2000; i++) checks(targets.get(i % targets.size()));
                    double micros = (System.nanoTime() - started) / 1000.0 / 2000;
                    results.add(Map.of("world", world, "round", round, "reads", 2000, "meanMicros", micros));
                    System.out.printf(Locale.ROOT, "%s checks-only round=%d %.3f us%n", world, round, micros);
                }
            } finally { for (Target target : targets) target.store.close(); }
        }
        Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(results), StandardOpenOption.CREATE_NEW);
    }
    private static void checks(Target target) throws IOException {
        // 只重复生产缓存读取的门和路由；不读取载荷、不解压、不解析 NBT。
        try (var ignored = MigrationGate.region(target.folder, target.pos.getRegionX(), target.pos.getRegionZ())) {
            if (!target.store.hasOpenRegion(target.pos) && !target.store.owns(target.pos)) throw new IOException("Missing ownership");
        }
    }
}
