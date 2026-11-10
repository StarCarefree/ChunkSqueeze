package cn.star.chunksqueeze.migration;

import cn.star.chunksqueeze.codec.ChunkCodec;
import cn.star.chunksqueeze.codec.RegionStateDictionary;
import cn.star.chunksqueeze.storage.CompactRegion;
import com.google.gson.GsonBuilder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

public final class MigrationSampleVerifier {
    private static final Pattern REGION_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final ChunkCodec.Options OPTIONS = new ChunkCodec.Options(128, true);

    private MigrationSampleVerifier() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: MigrationSampleVerifier <original-samples> <new-output-directory>");
        }
        Path originals = Path.of(args[0]).toRealPath();
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        if (!Files.isDirectory(originals)) throw new IOException("Expected sample directory: " + originals);
        McaSnapshot.checkPath(output);
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Output directory must not exist: " + output);
        }
        if (output.startsWith(originals)) throw new IOException("Output must be outside the original samples");
        List<Path> files;
        try (var walk = Files.walk(originals)) {
            files = walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> p.getFileName().toString().endsWith(".mca")).sorted().toList();
        }
        if (files.isEmpty()) throw new IOException("No source samples: " + originals);
        Files.createDirectories(output.getParent());
        Files.createDirectory(output);
        long chunks = 0, originalBytes = 0, activeBytes = 0, backupBytes = 0;
        List<Map<String, Object>> regions = new ArrayList<>();
        for (Path file : files) {
            String filename = file.getFileName().toString();
            var match = REGION_NAME.matcher(filename);
            if (!match.matches()) throw new IOException("Invalid MCA filename: " + file);
            int rx = Integer.parseInt(match.group(1)), rz = Integer.parseInt(match.group(2));
            String stem = "r." + rx + "." + rz;
            if (!filename.equals(stem + ".mca")) throw new IOException("Noncanonical MCA filename: " + file);
            Path relative = originals.relativize(file);
            Path folder = output.resolve(relative).resolveSibling(stem);
            Files.createDirectories(folder.getParent());
            Files.createDirectory(folder);

            McaSnapshot expected = McaSnapshot.capture(file.getParent(), rx, rz);
            Files.copy(file, folder.resolve(filename));
            for (String name : expected.externalNames()) {
                Files.copy(file.getParent().resolve(name), folder.resolve(name));
            }
            McaSnapshot snapshot = McaSnapshot.capture(folder, rx, rz);
            if (!Arrays.equals(expected.fingerprint(), snapshot.fingerprint())) {
                throw new IOException("Copied snapshot differs: " + relative);
            }
            MigrationTransaction.Prepared prepared = MigrationTransaction.prepare(snapshot, OPTIONS, true, () -> false);
            MigrationTransaction.PublishResult result = MigrationGate.exclusive(
                    () -> MigrationTransaction.publish(prepared, () -> false));
            if (result != MigrationTransaction.PublishResult.COMMITTED) {
                throw new IOException("Migration did not commit: " + relative + " (" + result + ")");
            }

            Path target = folder.resolve(stem + ".cqs");
            McaSnapshot.Chunk[] slots = new McaSnapshot.Chunk[1024];
            for (McaSnapshot.Chunk chunk : expected.chunks()) slots[chunk.slot()] = chunk;
            int[] timestamps = expected.timestamps();
            // 两次独立打开均完整核验，避免重开检查只验证索引或可读性。
            for (int pass = 0; pass < 2; pass++) {
                try (CompactRegion region = CompactRegion.open(target, rx, rz, false)) {
                    if (!prepared.regionId().equals(region.identity())) {
                        throw new IOException("Region identity differs: " + relative);
                    }
                    verifySlots(region, slots, timestamps, relative, pass);
                }
                verifyPublication(folder, file, prepared, expected);
            }
            long savedBytes = 0;
            long currentBytes = Files.size(target) + Files.size(CompactRegion.ownerPath(target));
            if (currentBytes != prepared.compactBytes()) {
                throw new IOException("Published byte counts differ: " + relative);
            }
            long count = expected.chunks().size();
            chunks += count;
            originalBytes += expected.sourceBytes();
            activeBytes += currentBytes;
            backupBytes += savedBytes;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("file", relative.toString());
            entry.put("chunksVerified", count);
            entry.put("originalBytes", expected.sourceBytes());
            entry.put("activeBytes", currentBytes);
            entry.put("backupBytes", savedBytes);
            regions.add(entry);
            System.out.println(relative + ": COMMITTED, " + count + " chunks verified twice, "
                    + expected.sourceBytes() + " -> " + currentBytes + " bytes including owner, backup " + savedBytes);
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("regionsVerified", regions.size());
        report.put("chunksVerified", chunks);
        report.put("originalBytes", originalBytes);
        report.put("activeBytes", activeBytes);
        report.put("backupBytes", backupBytes);
        report.put("regions", regions);
        Path reportPath = output.resolve("report.json");
        Files.writeString(reportPath, new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.println("Verified " + regions.size() + " regions, " + chunks + " chunks; report: " + reportPath);
    }

    private static void verifySlots(CompactRegion region, McaSnapshot.Chunk[] slots, int[] timestamps,
                                    Path relative, int pass) throws IOException {
        byte[] bytes = region.dictionary();
        RegionStateDictionary dictionary = bytes == null ? null : RegionStateDictionary.fromCompressed(bytes);
        for (int slot = 0; slot < 1024; slot++) {
            String context = relative + " slot " + slot + " pass " + (pass + 1);
            if (region.timestamp(slot) != timestamps[slot]) throw new IOException("Timestamp differs: " + context);
            CompactRegion.Record actual = region.read(slot);
            McaSnapshot.Chunk expected = slots[slot];
            if (expected == null) {
                if (actual != null) throw new IOException("Expected empty slot: " + context);
            } else {
                if (actual == null) throw new IOException("Missing chunk: " + context);
                if (actual.codec() != 2 && actual.codec() != 4 && actual.codec() != 5) throw new IOException("Expected encoded zlib record: " + context);
                CompoundTag original = readNbt(expected.payload(), expected.codec());
                CompoundTag decoded = MigrationTransaction.readNbt(actual.payload(), actual.codec(), dictionary);
                if (!original.equals(decoded)) throw new IOException("Full NBT differs: " + context);
            }
        }
    }

    private static void verifyPublication(Path folder, Path file, MigrationTransaction.Prepared prepared,
                                          McaSnapshot expected) throws IOException {
        if (Files.exists(MigrationTransaction.pendingPath(folder, expected.regionX(), expected.regionZ()),
                LinkOption.NOFOLLOW_LINKS)) throw new IOException("Pending journal remains: " + folder);
        if (Files.exists(folder.resolve(file.getFileName()), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Active MCA remains: " + folder);
        }
        if (Files.exists(prepared.directory(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Transaction originals remain: " + prepared.directory());
        }
        for (String name : expected.externalNames()) {
            if (Files.exists(folder.resolve(name), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Active MCC remains: " + name);
            }
        }
    }

    private static CompoundTag readNbt(byte[] bytes, int codec) throws IOException {
        InputStream raw = new ByteArrayInputStream(bytes);
        InputStream stream = switch (codec) {
            case 1 -> new GZIPInputStream(raw);
            case 2 -> new InflaterInputStream(raw);
            case 3 -> raw;
            default -> throw new IOException("Unsupported codec: " + codec);
        };
        try (var input = new DataInputStream(stream)) {
            return NbtIo.read(input, NbtAccounter.create(64L * 1024 * 1024));
        }
    }
}
