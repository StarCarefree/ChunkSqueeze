package cn.star.chunksqueeze.migration;

import cn.star.chunksqueeze.compat.NbtAccounter;
import cn.star.chunksqueeze.compat.NbtAccounterException;

import cn.star.chunksqueeze.codec.ChunkCodec;
import cn.star.chunksqueeze.codec.RegionStateDictionary;
import cn.star.chunksqueeze.codec.SectionCodec;
import cn.star.chunksqueeze.storage.CompactRegion;
import net.minecraft.nbt.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.zip.*;

import static cn.star.chunksqueeze.migration.McaSnapshot.*;

public final class MigrationTransaction {
    private static final byte[] MAGIC = "CSQMIG01".getBytes(StandardCharsets.US_ASCII);
    private static final int JOURNAL_BYTES = 88;
    private MigrationTransaction() {}

    public enum PublishResult { COMMITTED, CHANGED, NO_GAIN, CANCELLED }
    public enum Recovery { NONE, ROLLED_BACK, COMMITTED }
    public static final class Cancelled extends IOException {
        public Cancelled() { super("Region migration cancelled"); }
    }
    public static final class Prepared {
        private final McaSnapshot source;
        private final Path directory;
        private final UUID regionId, transactionId;
        private final long compactBytes;
        private Prepared(McaSnapshot source, Path directory, UUID regionId, long compactBytes, UUID transactionId) {
            this.source = source;
            this.directory = directory;
            this.regionId = regionId;
            this.compactBytes = compactBytes;
            this.transactionId = transactionId;
        }
        public McaSnapshot source() { return source; }
        public Path directory() { return directory; }
        public UUID regionId() { return regionId; }
        public long compactBytes() { return compactBytes; }
        public UUID transactionId() { return transactionId; }
    }
    private record Journal(UUID transactionId, UUID regionId, int x, int z, byte[] fingerprint) {}

    enum Stage { JOURNAL_CREATED, JOURNAL_FORCED, JOURNAL_PUBLISHED, MCA_MOVED, OWNER_MOVED, CQS_MOVED, ORIGINAL_DELETED, ORIGINALS_REMOVED }
    @FunctionalInterface interface FaultInjector { void after(Stage stage) throws IOException; }

    public static Prepared prepare(McaSnapshot snapshot, ChunkCodec.Options options,
                                   boolean encodeStates, BooleanSupplier cancelled) throws IOException {
        return prepare(snapshot, options, encodeStates, cancelled, stage -> {});
    }

    static Prepared prepare(McaSnapshot snapshot, ChunkCodec.Options options, boolean encodeStates,
                            BooleanSupplier cancelled, CompactRegion.BuildFaultInjector faults) throws IOException {
        Objects.requireNonNull(options);
        checkCancelled(cancelled);
        UUID transactionId = UUID.randomUUID();
        Path directory = transactionDirectory(snapshot.folder(), transactionId);
        checkPath(directory);
        Files.createDirectories(directory.getParent());
        Files.createDirectory(directory);
        Path prepared = Files.createDirectory(directory.resolve("prepared"));
        Path target = compactPath(prepared, snapshot.regionX(), snapshot.regionZ());
        try {
            UUID regionId;
            int[] timestamps = snapshot.timestamps();
            RegionStateDictionary dictionary = RegionStateDictionary.create();
            try (CompactRegion.PreparedBuilder builder = CompactRegion.createPrepared(target, snapshot.regionX(), snapshot.regionZ())) {
                builder.setFaultInjector(faults);
                boolean[] present = new boolean[1024];
                boolean sharedStarted = false;
                for (McaSnapshot.Chunk chunk : snapshot.chunks()) {
                    checkCancelled(cancelled);
                    byte[] payload = chunk.payload();
                    int codec = chunk.codec();
                    CompoundTag original = readNbt(payload, codec);
                    checkCoordinates(original, snapshot, chunk.slot());
                    if (encodeStates) {
                        SectionCodec.Encoded result = SectionCodec.encodeForStorage(original, options, dictionary);
                        CompoundTag encoded = result.result().tag();
                        payload = result.payload();
                        codec = encoded.contains(SectionCodec.KEY) ? 5 : encoded.contains(ChunkCodec.KEY)
                                && encoded.getCompound(ChunkCodec.KEY).getInt("version") == 2 ? 4 : 2;
                    }
                    if (codec >= 4 && !sharedStarted) {
                        builder.setDictionary(dictionary.compressed());
                        sharedStarted = true;
                    }
                    builder.write(chunk.slot(), payload, codec, chunk.timestamp());
                    present[chunk.slot()] = true;
                }
                for (int slot = 0; slot < 1024; slot++) {
                    checkCancelled(cancelled);
                    if (!present[slot] && timestamps[slot] != 0) builder.write(slot, null, 0, timestamps[slot]);
                }
                checkCancelled(cancelled);
                if (dictionary.revision() != 0) builder.setDictionary(dictionary.compressed());
                builder.finish();
            }
            checkCancelled(cancelled);
            // 完成持久化后重开验证，每次只解码一个原始块及对应的目标块。
            try (CompactRegion region = CompactRegion.open(target, snapshot.regionX(), snapshot.regionZ(), false)) {
                regionId = region.identity();
                byte[] dictionaryBytes = region.dictionary();
                RegionStateDictionary reopened = dictionaryBytes == null ? null : RegionStateDictionary.fromCompressed(dictionaryBytes);
                for (McaSnapshot.Chunk chunk : snapshot.chunks()) {
                    checkCancelled(cancelled);
                    CompactRegion.Record stored = region.read(chunk.slot());
                    if (stored == null || !readNbt(chunk.payload(), chunk.codec()).equals(readNbt(stored.payload(), stored.codec(), reopened))) {
                        throw new IOException("Migrated NBT differs at slot " + chunk.slot());
                    }
                }
                for (int slot = 0; slot < 1024; slot++) {
                    checkCancelled(cancelled);
                    if (region.timestamp(slot) != timestamps[slot]) throw new IOException("Migrated timestamp differs");
                }
            }
            checkCancelled(cancelled);
            syncDirectory(prepared);
            syncDirectory(directory);
            syncDirectory(directory.getParent());
            return new Prepared(snapshot, directory, regionId,
                    Files.size(target) + Files.size(CompactRegion.ownerPath(target)), transactionId);
        } catch (IOException | RuntimeException | Error failure) {
            try { deletePrepared(directory, snapshot.regionX(), snapshot.regionZ()); }
            catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public static PublishResult publish(Prepared prepared, BooleanSupplier cancelled) throws IOException {
        return publish(prepared, cancelled, stage -> {});
    }

    static PublishResult publish(Prepared prepared, BooleanSupplier cancelled, FaultInjector faults) throws IOException {
        McaSnapshot source = prepared.source();
        Path folder = source.folder();
        int x = source.regionX(), z = source.regionZ();
        checkPrepared(prepared);
        Path pending = pendingPath(folder, x, z);
        if (exists(pending)) throw new IOException("Migration recovery required: " + pending);
        if (cancelled.getAsBoolean()) return PublishResult.CANCELLED;
        if (!exists(mcaPath(folder, x, z))) return PublishResult.CHANGED;
        McaSnapshot current = McaSnapshot.capture(folder, x, z);
        if (!Arrays.equals(source.fingerprint(), current.fingerprint())) return PublishResult.CHANGED;
        if (prepared.compactBytes() >= current.sourceBytes()) return PublishResult.NO_GAIN;
        Path target = compactPath(folder, x, z);
        Path staged = compactPath(prepared.directory().resolve("prepared"), x, z);
        requireAbsent(target);
        requireAbsent(CompactRegion.ownerPath(target));
        validateCompact(staged, x, z, prepared.regionId());
        if (Files.size(staged) + Files.size(CompactRegion.ownerPath(staged)) != prepared.compactBytes()) {
            throw new IOException("Prepared size changed");
        }
        Path original = prepared.directory().resolve("original");
        checkPath(original);
        if (exists(original)) {
            try (var files = Files.list(original)) {
                if (files.findAny().isPresent()) throw new IOException("Backup directory is not empty");
            }
        } else Files.createDirectory(original);
        syncDirectory(prepared.directory());
        if (cancelled.getAsBoolean()) return PublishResult.CANCELLED;
        Journal journal = new Journal(prepared.transactionId(), prepared.regionId(), x, z, source.fingerprint());
        // journal 持久化后才可移动在线文件；Error 留给测试模拟进程突然消失。
        try {
            writeJournal(pending, journal, faults);
            move(mcaPath(folder, x, z), mcaPath(original, x, z));
            faults.after(Stage.MCA_MOVED);
            for (String name : current.externalNames()) move(folder.resolve(name), original.resolve(name));
            move(CompactRegion.ownerPath(staged), CompactRegion.ownerPath(target));
            faults.after(Stage.OWNER_MOVED);
            move(staged, target);
            faults.after(Stage.CQS_MOVED);
            if (recover(folder, x, z, faults) != Recovery.COMMITTED) throw new IOException("Publication did not commit");
            return PublishResult.COMMITTED;
        } catch (IOException | RuntimeException failure) {
            try { recover(folder, x, z); }
            catch (IOException | RuntimeException recovery) { failure.addSuppressed(recovery); }
            throw failure;
        }
    }

    public static Path pendingPath(Path folder, int rx, int rz) {
        return folder.resolve("r." + rx + "." + rz + ".cqs.migrating");
    }

    public static Recovery recover(Path folder, int rx, int rz) throws IOException {
        return recover(folder, rx, rz, stage -> {});
    }

    private static Recovery recover(Path folder, int rx, int rz, FaultInjector faults) throws IOException {
        folder = folder.toAbsolutePath().normalize();
        checkPath(folder);
        Path pending = pendingPath(folder, rx, rz);
        if (!exists(pending)) return Recovery.NONE;
        Journal journal = readJournal(pending, rx, rz);
        Path directory = transactionDirectory(folder, journal.transactionId());
        Path original = directory.resolve("original");
        Path staged = compactPath(directory.resolve("prepared"), rx, rz);
        Path target = compactPath(folder, rx, rz);
        Path source = mcaPath(folder, rx, rz);
        Path backup = mcaPath(original, rx, rz);
        checkPath(directory);
        checkPath(original);
        checkPath(staged);
        if (exists(target)) {
            requireAbsent(source);
            validateCompact(target, rx, rz, journal.regionId());
            // CQS 发布后不再回退原件；删除中断可依据日志与 CQS 身份继续清理。
            deleteOriginals(original, folder, rx, rz, faults);
            faults.after(Stage.ORIGINALS_REMOVED);
            removeJournal(pending, journal);
            deletePrepared(directory, rx, rz);
            return Recovery.COMMITTED;
        }
        if (exists(source) && exists(backup)) throw new IOException("MCA source/backup conflict");
        Path available = exists(backup) ? original : folder;
        McaSnapshot saved = McaSnapshot.capture(available, rx, rz, available.equals(original) ? folder : original);
        verifyFingerprint(saved, journal);
        Path owner = CompactRegion.ownerPath(target);
        if (exists(owner)) {
            validateOwner(owner, journal);
            requireAbsent(CompactRegion.ownerPath(staged));
        }
        // 全部冲突检查在恢复移动之前完成；恢复本身再次中断也可从混合位置继续。
        for (String name : saved.externalNames()) {
            if (exists(folder.resolve(name)) && exists(original.resolve(name))) {
                throw new IOException("External source/backup conflict: " + name);
            }
        }
        if (exists(owner)) move(owner, CompactRegion.ownerPath(staged));
        for (String name : saved.externalNames()) {
            if (exists(original.resolve(name))) move(original.resolve(name), folder.resolve(name));
        }
        if (exists(backup)) move(backup, source);
        verifyFingerprint(McaSnapshot.capture(folder, rx, rz), journal);
        removeJournal(pending, journal);
        return Recovery.ROLLED_BACK;
    }

    private static void deleteOriginals(Path original, Path folder, int x, int z, FaultInjector faults) throws IOException {
        if (!exists(original)) return;
        Set<String> allowed = new HashSet<>();
        allowed.add(mcaPath(original, x, z).getFileName().toString());
        for (int slot = 0; slot < 1024; slot++) {
            allowed.add("c." + ((long) x * 32 + (slot & 31)) + "." + ((long) z * 32 + (slot >>> 5)) + ".mcc");
        }
        List<Path> files;
        try (var entries = Files.list(original)) { files = entries.sorted().toList(); }
        // 先检查整份清单；不递归删除目录，不触碰未知文件或其他事务。
        for (Path file : files) {
            if (!allowed.contains(file.getFileName().toString())) throw new IOException("Unexpected transaction original: " + file);
            requireFile(file);
            requireAbsent(folder.resolve(file.getFileName()));
        }
        for (Path file : files) {
            Files.delete(file);
            faults.after(Stage.ORIGINAL_DELETED);
        }
        syncDirectory(original);
    }

    public static void discard(Prepared prepared) throws IOException {
        checkPrepared(prepared);
        Path pending = pendingPath(prepared.source().folder(), prepared.source().regionX(), prepared.source().regionZ());
        if (exists(pending)) throw new IOException("Cannot discard while recovery is pending");
        deletePrepared(prepared.directory(), prepared.source().regionX(), prepared.source().regionZ());
    }

    private static void deletePrepared(Path directory, int x, int z) throws IOException {
        checkPath(directory);
        Path staged = compactPath(directory.resolve("prepared"), x, z);
        for (Path file : List.of(staged, CompactRegion.ownerPath(staged), directory.resolve("journal.tmp"))) {
            if (exists(file)) { requireFile(file); Files.delete(file); }
        }
        deleteEmpty(directory.resolve("prepared"));
        deleteEmpty(directory.resolve("original"));
        deleteEmpty(directory);
    }

    private static void deleteEmpty(Path directory) throws IOException {
        checkPath(directory);
        if (!exists(directory)) return;
        try { Files.delete(directory); }
        catch (DirectoryNotEmptyException retained) { /* 原件和未知文件必须保留。 */ }
    }

    private static void checkPrepared(Prepared prepared) throws IOException {
        Path expected = transactionDirectory(prepared.source().folder(), prepared.transactionId());
        if (!expected.equals(prepared.directory().toAbsolutePath().normalize())) throw new IOException("Invalid transaction directory");
        checkPath(expected);
    }
    private static Path transactionDirectory(Path folder, UUID id) {
        return folder.toAbsolutePath().normalize().resolve(".chunksqueeze-migration").resolve(id.toString());
    }
    private static Path compactPath(Path folder, int x, int z) { return folder.resolve("r." + x + "." + z + ".cqs"); }
    private static Path mcaPath(Path folder, int x, int z) { return folder.resolve("r." + x + "." + z + ".mca"); }
    private static void checkCancelled(BooleanSupplier cancelled) throws Cancelled {
        if (cancelled.getAsBoolean()) throw new Cancelled();
    }
    private static void requireAbsent(Path path) throws IOException {
        checkPath(path);
        if (exists(path)) throw new FileAlreadyExistsException(path.toString());
    }
    private static void move(Path source, Path target) throws IOException {
        requireFile(source);
        requireAbsent(target);
        // 调用者持有独占门；ATOMIC_MOVE 在部分系统忽略“不替换”，所以必须先检查目标。
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        syncDirectory(target.getParent());
        if (!source.getParent().equals(target.getParent())) syncDirectory(source.getParent());
    }
    private static void syncDirectory(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (AccessDeniedException unsupportedOnWindows) {
            // Windows 默认 provider 不提供目录句柄；文件 force 与原子移动仍为必需。
            if (!System.getProperty("os.name", "").startsWith("Windows")) throw unsupportedOnWindows;
        }
    }
    private static void verifyFingerprint(McaSnapshot snapshot, Journal journal) throws IOException {
        if (!Arrays.equals(snapshot.fingerprint(), journal.fingerprint())) throw new IOException("Original fingerprint mismatch");
    }
    private static void validateCompact(Path path, int x, int z, UUID id) throws IOException {
        requireFile(path);
        requireFile(CompactRegion.ownerPath(path));
        try (CompactRegion region = CompactRegion.open(path, x, z, false)) {
            if (!region.identity().equals(id)) throw new IOException("Unexpected compact UUID");
            byte[] bytes = region.dictionary();
            RegionStateDictionary dictionary = bytes == null ? null : RegionStateDictionary.fromCompressed(bytes);
            for (int slot = 0; slot < 1024; slot++) {
                CompactRegion.Record record = region.read(slot);
                if (record != null && record.codec() >= 4) readNbt(record.payload(), record.codec(), dictionary);
            }
        }
    }
    private static void validateOwner(Path path, Journal journal) throws IOException {
        requireFile(path);
        if (Files.size(path) != 512) throw new IOException("Invalid owner length");
        byte[] bytes = Files.readAllBytes(path);
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, 40);
        crc.update(bytes, 44, 468);
        ByteBuffer input = ByteBuffer.wrap(bytes);
        byte[] magic = new byte[8]; input.get(magic);
        if (!Arrays.equals(magic, "CSQOWN01".getBytes(StandardCharsets.US_ASCII))
                || input.getInt() != 1 || input.getInt() != journal.x() || input.getInt() != journal.z()
                || input.getInt() != 512 || !new UUID(input.getLong(), input.getLong()).equals(journal.regionId())
                || input.getInt() != (int) crc.getValue()) throw new IOException("Invalid published owner identity");
        while (input.hasRemaining()) if (input.get() != 0) throw new IOException("Invalid owner reserved bytes");
    }
    private static void writeJournal(Path path, Journal journal, FaultInjector faults) throws IOException {
        requireAbsent(path);
        Path temporary = transactionDirectory(path.getParent(), journal.transactionId()).resolve("journal.tmp");
        requireAbsent(temporary);
        ByteBuffer bytes = ByteBuffer.allocate(JOURNAL_BYTES);
        bytes.put(MAGIC).putInt(1);
        putUuid(bytes, journal.transactionId());
        putUuid(bytes, journal.regionId());
        bytes.putInt(journal.x()).putInt(journal.z()).put(journal.fingerprint());
        CRC32 crc = new CRC32(); crc.update(bytes.array(), 0, JOURNAL_BYTES - 4);
        bytes.putInt((int) crc.getValue()).flip();
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            faults.after(Stage.JOURNAL_CREATED);
            while (bytes.hasRemaining()) if (channel.write(bytes) <= 0) throw new IOException("Journal write made no progress");
            channel.force(true);
        }
        faults.after(Stage.JOURNAL_FORCED);
        move(temporary, path);
        faults.after(Stage.JOURNAL_PUBLISHED);
    }
    private static Journal readJournal(Path path, int x, int z) throws IOException {
        requireFile(path);
        if (Files.size(path) != JOURNAL_BYTES) throw new IOException("Invalid migration journal length");
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length != JOURNAL_BYTES) throw new IOException("Journal changed during read");
        ByteBuffer input = ByteBuffer.wrap(bytes);
        byte[] magic = new byte[8]; input.get(magic);
        if (!Arrays.equals(magic, MAGIC) || input.getInt() != 1) throw new IOException("Unsupported migration journal");
        UUID transactionId = getUuid(input), regionId = getUuid(input);
        if (input.getInt() != x || input.getInt() != z) throw new IOException("Migration journal coordinates mismatch");
        byte[] fingerprint = new byte[32]; input.get(fingerprint);
        CRC32 crc = new CRC32(); crc.update(bytes, 0, JOURNAL_BYTES - 4);
        if (input.getInt() != (int) crc.getValue()) throw new IOException("Migration journal CRC mismatch");
        return new Journal(transactionId, regionId, x, z, fingerprint);
    }
    private static void putUuid(ByteBuffer bytes, UUID id) { bytes.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()); }
    private static UUID getUuid(ByteBuffer bytes) { return new UUID(bytes.getLong(), bytes.getLong()); }
    private static void removeJournal(Path path, Journal journal) throws IOException {
        Files.delete(path);
        try {
            syncDirectory(path.getParent());
        } catch (IOException failure) {
            // 删除的持久化失败时，仍通过完整临时文件恢复故障标记。
            try { writeJournal(path, journal, stage -> {}); }
            catch (IOException restore) { failure.addSuppressed(restore); }
            throw failure;
        }
    }
    private static CompoundTag readNbt(byte[] payload, int codec) throws IOException {
        return readNbt(payload, codec, null);
    }
    public static CompoundTag readNbt(byte[] payload, int codec, RegionStateDictionary dictionary) throws IOException {
        InputStream raw = new ByteArrayInputStream(payload);
        InputStream decoded = switch (codec) {
            case 1 -> new GZIPInputStream(raw);
            case 2, 4, 5 -> new InflaterInputStream(raw);
            case 3 -> raw;
            default -> throw new IOException("Unsupported NBT codec");
        };
        try (var input = new DataInputStream(new net.minecraft.util.FastBufferedInputStream(decoded))) {
            CompoundTag tag = NbtIo.read(input, NbtAccounter.create(64L * 1024 * 1024));
            if (input.read() != -1) throw new IOException("Trailing NBT data");
            boolean shared = tag.contains(ChunkCodec.KEY) && tag.getCompound(ChunkCodec.KEY).getInt("version") == 2;
            if ((codec == 5) != tag.contains(SectionCodec.KEY)
                    || codec == 4 && !shared || codec < 4 && shared) {
                throw new IOException("Shared NBT marker and record codec disagree");
            }
            return SectionCodec.decode(tag, dictionary);
        } catch (NbtAccounterException malformed) {
            throw new IOException("NBT exceeds migration quota", malformed);
        }
    }
    private static void checkCoordinates(CompoundTag tag, McaSnapshot snapshot, int slot) throws IOException {
        if ((tag.contains("xPos") && (!tag.contains("xPos", Tag.TAG_INT)
                || tag.getInt("xPos") != snapshot.regionX() * 32 + (slot & 31)))
                || (tag.contains("zPos") && (!tag.contains("zPos", Tag.TAG_INT)
                || tag.getInt("zPos") != snapshot.regionZ() * 32 + (slot >>> 5)))) {
            throw new IOException("Chunk coordinates disagree with canonical slot");
        }
    }
}
