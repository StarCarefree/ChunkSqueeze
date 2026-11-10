package cn.star.chunksqueeze.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class CompactRegion implements AutoCloseable {
    public static final int UNIT = 512;
    public static final int TABLE_BYTES = 1024 * 32;
    public static final int DATA_START = 3 * UNIT + 2 * TABLE_BYTES;
    public static final int MAX_PAYLOAD = 128 * 1024 * 1024;
    public static final int MAX_DICTIONARY = 16 * 1024 * 1024;
    private static final byte[] HEADER_MAGIC = ascii("CSQREG01");
    private static final byte[] OWNER_MAGIC = ascii("CSQOWN01");
    private static final byte[] COMMIT_MAGIC = ascii("CSQCMT01");

    public enum Stage { PAYLOAD_FORCED, INDEX_FORCED, FIRST_COMMIT_FORCED, MIRROR_FORCED }
    @FunctionalInterface public interface FaultInjector { void after(Stage stage) throws IOException; }
    public enum BuildStage { PAYLOAD_WRITTEN, PAYLOADS_FORCED, INDEXES_WRITTEN, COMPLETE_FORCED }
    @FunctionalInterface public interface BuildFaultInjector { void after(BuildStage stage) throws IOException; }
    public record Record(byte[] payload, int codec, int timestamp) {}
    private record Entry(long offset, int length, int timestamp, int codec, int crc, long generation) {
        static Entry empty() { return new Entry(0, 0, 0, 0, 0, 0); }
    }
    private record Commit(long generation, int slot, int tableCrc, Entry dictionary) {}

    private static final ConcurrentMap<Path, CompactRegion> OPEN_OWNERS = new ConcurrentHashMap<>();
    private final Path path;
    private Path lockKey;
    private final int regionX;
    private final int regionZ;
    private FileChannel ownerChannel;
    private FileLock ownerLock;
    private FileChannel channel;
    private boolean preferSynchronous = true;
    private boolean synchronousWrites;
    private UUID id;
    private int version = 2;
    private Entry[] entries;
    private Commit commit;
    private boolean mirrored;
    private long repairFirstOffset;
    private boolean poisoned;
    private boolean closed;
    private FaultInjector faults = stage -> {};

    public static Path ownerPath(Path path) { return path.resolveSibling(path.getFileName() + ".owner"); }

    public static CompactRegion open(Path path, int x, int z, boolean create) throws IOException {
        return open(path, x, z, create, true);
    }

    static CompactRegion open(Path path, int x, int z, boolean create, boolean synchronous) throws IOException {
        CompactRegion region = new CompactRegion(path, x, z);
        region.preferSynchronous = synchronous;
        try {
            region.initialize(create, false);
            return region;
        } catch (IOException | RuntimeException failure) {
            try { region.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    public static PreparedBuilder createPrepared(Path path, int x, int z) throws IOException {
        Path target = path.toAbsolutePath().normalize();
        Path prepared = target.getParent();
        Path transaction = prepared == null ? null : prepared.getParent();
        Path root = transaction == null ? null : transaction.getParent();
        if (root == null || root.getFileName() == null || !prepared.getFileName().toString().equals("prepared")
                || !root.getFileName().toString().equals(".chunksqueeze-migration")) {
            throw new IOException("Batch construction requires a hidden migration prepared directory");
        }
        try {
            if (!UUID.fromString(transaction.getFileName().toString()).toString().equals(transaction.getFileName().toString())) {
                throw new IllegalArgumentException("Noncanonical transaction UUID");
            }
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid migration transaction directory", invalid);
        }
        for (Path ancestor = prepared; ancestor != null; ancestor = ancestor.getParent()) {
            if (Files.isSymbolicLink(ancestor)) throw new IOException("Prepared directory must not contain symbolic links");
        }
        CompactRegion region = new CompactRegion(target, x, z);
        try {
            region.initialize(true, true);
            return new PreparedBuilder(region);
        } catch (IOException | RuntimeException | Error failure) {
            try { region.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    // 仅持有索引元数据；不暴露普通 writer，未完成时磁盘上没有有效提交。
    public static final class PreparedBuilder implements AutoCloseable {
        private final CompactRegion region;
        private final boolean[] written = new boolean[1024];
        private long end = DATA_START;
        private boolean finished;
        private byte[] dictionary;
        private BuildFaultInjector faults = stage -> {};

        private PreparedBuilder(CompactRegion region) { this.region = region; }

        public synchronized void setFaultInjector(BuildFaultInjector injector) throws IOException {
            checkActive();
            faults = Objects.requireNonNull(injector);
        }

        public synchronized void setDictionary(byte[] dictionary) throws IOException {
            try {
                checkActive();
                validateDictionary(dictionary);
                if (dictionary == null && Arrays.stream(region.entries).anyMatch(entry -> entry.codec >= 4)) {
                    throw new IOException("Region references require a dictionary");
                }
                this.dictionary = dictionary == null ? null : dictionary.clone();
            } catch (IOException | RuntimeException | Error failure) {
                region.poisoned = true;
                throw failure;
            }
        }

        public synchronized void write(int slot, byte[] payload, int codec, int timestamp) throws IOException {
            try {
                checkActive();
                region.check(slot);
                if (written[slot]) throw new IOException("Prepared slot already written");
                validatePayload(payload, codec, dictionary != null);
                if (payload == null) {
                    region.entries[slot] = new Entry(0, 0, timestamp, 0, 0, 1);
                } else {
                    writeAt(region.channel, end, payload);
                    int padding = (int) (aligned(payload.length) - payload.length);
                    if (padding > 0) writeAt(region.channel, end + payload.length, new byte[padding]);
                    region.entries[slot] = new Entry(end, payload.length, timestamp, codec, crc(payload), 1);
                    end += aligned(payload.length);
                }
                written[slot] = true;
                faults.after(BuildStage.PAYLOAD_WRITTEN);
            } catch (IOException | RuntimeException | Error failure) {
                region.poisoned = true;
                throw failure;
            }
        }

        public synchronized void finish() throws IOException {
            try {
                checkActive();
                Entry dictionaryEntry = Entry.empty();
                if (dictionary != null) {
                    writeAt(region.channel, end, dictionary);
                    int padding = (int) (aligned(dictionary.length) - dictionary.length);
                    if (padding > 0) writeAt(region.channel, end + dictionary.length, new byte[padding]);
                    dictionaryEntry = new Entry(end, dictionary.length, 0, 2, crc(dictionary), 1);
                }
                region.channel.force(true);
                faults.after(BuildStage.PAYLOADS_FORCED);
                byte[] table = table(region.entries);
                writeAt(region.channel, tableOffset(0), table);
                writeAt(region.channel, tableOffset(1), table);
                faults.after(BuildStage.INDEXES_WRITTEN);
                Commit commit = new Commit(1, 0, crc(table), dictionaryEntry);
                byte[] marker = region.commitPage(commit);
                writeAt(region.channel, UNIT, marker);
                writeAt(region.channel, 2L * UNIT, marker);
                region.channel.force(true);
                faults.after(BuildStage.COMPLETE_FORCED);
                finished = true;
            } catch (IOException | RuntimeException | Error failure) {
                region.poisoned = true;
                throw failure;
            }
        }

        private void checkActive() throws IOException {
            region.check(0);
            if (finished) throw new IOException("Prepared construction already finished");
        }

        @Override
        public synchronized void close() throws IOException { region.close(); }
    }

    private CompactRegion(Path path, int x, int z) {
        this.path = path;
        this.regionX = x;
        this.regionZ = z;
    }

    private void initialize(boolean create, boolean preparedBuild) throws IOException {
        if (!path.getFileName().toString().equals("r." + regionX + "." + regionZ + ".cqs")) {
            throw new IOException("Compact region filename/coordinates disagree");
        }
        Path owner = ownerPath(path);
        boolean fileExists = Files.exists(path);
        boolean ownerExists = Files.exists(owner);
        if (preparedBuild && (Files.exists(path, LinkOption.NOFOLLOW_LINKS) || Files.exists(owner, LinkOption.NOFOLLOW_LINKS))) {
            throw new FileAlreadyExistsException(path.toString());
        }
        if (fileExists != ownerExists) throw new IOException("Missing compact region or ownership file: " + path);
        if (!fileExists && !create) throw new NoSuchFileException(path.toString());
        if (!fileExists) Files.createDirectories(path.toAbsolutePath().getParent());
        if (Files.isSymbolicLink(path) || Files.isSymbolicLink(owner)) throw new IOException("Compact files must not be symbolic links");
        Path key = owner.toAbsolutePath().getParent().toRealPath().resolve(owner.getFileName());
        // 先阻止 JVM 内重复打开；部分系统关闭第二个描述符也会释放第一个描述符的文件锁。
        if (OPEN_OWNERS.putIfAbsent(key, this) != null) throw new IOException("Compact region is already open: " + path);
        lockKey = key;
        ownerChannel = fileExists
                ? FileChannel.open(owner, StandardOpenOption.READ, StandardOpenOption.WRITE)
                : FileChannel.open(owner, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            ownerLock = ownerChannel.tryLock();
        } catch (OverlappingFileLockException busy) {
            throw new IOException("Compact region is already open: " + path, busy);
        }
        if (ownerLock == null) throw new IOException("Compact region is locked: " + path);
        if (!fileExists) {
            id = UUID.randomUUID();
            writeAt(ownerChannel, 0, identity(OWNER_MAGIC));
            ownerChannel.force(true);
            channel = openChannel(true, !preparedBuild && preferSynchronous);
            entries = new Entry[1024];
            Arrays.fill(entries, Entry.empty());
            if (preparedBuild) {
                writeAt(channel, 0, identity(HEADER_MAGIC));
                writeAt(channel, UNIT, new byte[2 * UNIT]);
                writeAt(channel, DATA_START - 1L, new byte[1]);
                return;
            }
            byte[] table = table(entries);
            commit = new Commit(0, 0, crc(table), Entry.empty());
            mirrored = true;
            writeAt(channel, 0, identity(HEADER_MAGIC));
            writeAt(channel, tableOffset(0), table);
            writeAt(channel, tableOffset(1), table);
            writeAt(channel, UNIT, commitPage(commit));
            writeAt(channel, 2L * UNIT, commitPage(commit));
            channel.force(true);
        } else {
            if (ownerChannel.size() != UNIT) throw new IOException("Invalid compact ownership file size");
            id = parseIdentity(readAt(ownerChannel, 0, UNIT), OWNER_MAGIC);
            channel = openChannel(false, preferSynchronous);
            load();
        }
    }

    private void load() throws IOException {
        if (channel.size() < DATA_START) throw new IOException("Truncated compact region");
        UUID headerId = parseIdentity(readAt(channel, 0, UNIT), HEADER_MAGIC);
        if (!id.equals(headerId)) throw new IOException("Compact region ownership mismatch");
        Commit first = parseCommit(readAt(channel, UNIT, UNIT));
        Commit second = parseCommit(readAt(channel, 2L * UNIT, UNIT));
        if (first == null && second == null) throw new IOException("No valid compact commit");
        if (first != null && second != null && first.generation == second.generation && !first.equals(second)) {
            throw new IOException("Conflicting same-generation compact commits");
        }
        commit = first == null ? second : second == null || first.generation >= second.generation ? first : second;
        mirrored = first != null && first.equals(second);
        repairFirstOffset = commit.equals(first) ? 2L * UNIT : UNIT;
        byte[] table = readAt(channel, tableOffset(commit.slot), TABLE_BYTES);
        if (crc(table) != commit.tableCrc) throw new IOException("Committed compact index checksum mismatch");
        entries = new Entry[1024];
        ByteBuffer input = buffer(table);
        long fileSize = channel.size();
        List<long[]> spans = new ArrayList<>();
        Entry dictionary = commit.dictionary;
        if (dictionary.offset == 0) {
            if (!dictionary.equals(Entry.empty())) throw new IOException("Invalid empty compact dictionary");
        } else {
            if (dictionary.timestamp != 0 || dictionary.codec != 2 || dictionary.generation < 0
                    || dictionary.generation > commit.generation || !validAllocation(dictionary, MAX_DICTIONARY, fileSize)) {
                throw new IOException("Invalid compact dictionary descriptor");
            }
            spans.add(new long[]{dictionary.offset, dictionary.offset + aligned(dictionary.length)});
            // 字典属于已选中的提交，损坏时不能回退到旧版本。
            dictionary();
        }
        for (int slot = 0; slot < entries.length; slot++) {
            Entry entry = readEntry(input);
            if (entry.generation < 0 || entry.generation > commit.generation) throw new IOException("Invalid entry generation");
            if (entry.offset == 0) {
                if (entry.length != 0 || entry.codec != 0 || entry.crc != 0) throw new IOException("Invalid empty compact entry");
            } else {
                if (!validAllocation(entry, MAX_PAYLOAD, fileSize) || entry.codec < 1 || entry.codec > 5
                        || entry.codec >= 4 && (version < 2 || dictionary.offset == 0)) {
                    throw new IOException("Invalid compact entry at slot " + slot);
                }
                spans.add(new long[]{entry.offset, entry.offset + aligned(entry.length)});
            }
            entries[slot] = entry;
        }
        spans.sort(Comparator.comparingLong(span -> span[0]));
        long end = DATA_START;
        for (long[] span : spans) {
            if (span[0] < end) throw new IOException("Overlapping compact payloads");
            end = span[1];
        }
    }

    public synchronized Record read(int slot) throws IOException {
        check(slot);
        Entry entry = entries[slot];
        if (entry.offset == 0) return null;
        byte[] payload = readAt(channel, entry.offset, entry.length);
        if (crc(payload) != entry.crc) throw new IOException("Compact payload checksum mismatch at slot " + slot);
        return new Record(payload, entry.codec, entry.timestamp);
    }

    public synchronized int timestamp(int slot) throws IOException { check(slot); return entries[slot].timestamp; }
    public synchronized long generation() throws IOException { check(0); return commit.generation; }
    synchronized long chunkGeneration(int slot) throws IOException { check(slot); return entries[slot].generation; }
    synchronized long dictionaryGeneration() throws IOException { check(0); return commit.dictionary.generation; }
    synchronized int codec(int slot) throws IOException { check(slot); return entries[slot].codec; }
    synchronized int payloadLength(int slot) throws IOException { check(slot); return entries[slot].length; }

    synchronized java.util.BitSet plainSlots() throws IOException {
        check(0);
        java.util.BitSet slots = new java.util.BitSet(1024);
        for (int i = 0; i < entries.length; i++) if (entries[i].codec == 2) slots.set(i);
        return slots;
    }
    public synchronized UUID identity() throws IOException { check(0); return id; }

    public synchronized byte[] dictionary() throws IOException {
        check(0);
        Entry dictionary = commit.dictionary;
        if (dictionary.offset == 0) return null;
        byte[] payload = readAt(channel, dictionary.offset, dictionary.length);
        if (crc(payload) != dictionary.crc) throw new IOException("Compact dictionary checksum mismatch");
        return payload;
    }

    public synchronized void write(int slot, byte[] payload, int codec, int timestamp) throws IOException {
        writeInternal(slot, payload, codec, timestamp, null, false);
    }

    public synchronized void writeWithDictionary(int slot, byte[] payload, int codec, int timestamp,
                                                 byte[] dictionary) throws IOException {
        writeInternal(slot, payload, codec, timestamp, dictionary, true);
    }

    private void writeInternal(int slot, byte[] payload, int codec, int timestamp, byte[] dictionary,
                               boolean upgrade) throws IOException {
        check(slot);
        validateDictionary(dictionary);
        validatePayload(payload, codec, dictionary != null || commit.dictionary.offset != 0);
        if (commit.generation == Long.MAX_VALUE) throw new IOException("Compact generation exhausted");
        try {
            // 旧文件只通过原子替换升级，不能改写唯一的 header。
            if (upgrade && version == 1) compact(2);
            repairCommitMirror();
            long nextGeneration = commit.generation + 1;
            Entry[] next = entries.clone();
            List<Entry> reserved = new ArrayList<>();
            Entry nextDictionary = commit.dictionary;
            if (dictionary != null) {
                long position = allocate(aligned(dictionary.length), reserved);
                writeAt(channel, position, dictionary);
                nextDictionary = new Entry(position, dictionary.length, 0, 2, crc(dictionary), nextGeneration);
                reserved.add(nextDictionary);
            }
            if (payload == null) {
                next[slot] = new Entry(0, 0, timestamp, 0, 0, nextGeneration);
            } else {
                // 当前索引仍可能在中断后生效，其所有数据和字典都不能被覆盖。
                long position = allocate(aligned(payload.length), reserved);
                writeAt(channel, position, payload);
                next[slot] = new Entry(position, payload.length, timestamp, codec, crc(payload), nextGeneration);
            }
            persistStage();
            faults.after(Stage.PAYLOAD_FORCED);
            byte[] table = table(next);
            Commit nextCommit = new Commit(nextGeneration, 1 - commit.slot, crc(table), nextDictionary);
            writeAt(channel, tableOffset(nextCommit.slot), table);
            persistStage();
            faults.after(Stage.INDEX_FORCED);
            byte[] marker = commitPage(nextCommit);
            writeAt(channel, UNIT, marker);
            persistStage();
            faults.after(Stage.FIRST_COMMIT_FORCED);
            writeAt(channel, 2L * UNIT, marker);
            persistStage();
            faults.after(Stage.MIRROR_FORCED);
            entries = next;
            commit = nextCommit;
            mirrored = true;
            trimTail();
        } catch (IOException | RuntimeException failure) {
            poisoned = true;
            throw failure;
        }
    }

    private FileChannel openChannel(boolean create, boolean synchronous) throws IOException {
        Set<StandardOpenOption> options = EnumSet.of(StandardOpenOption.READ, StandardOpenOption.WRITE);
        if (create) options.add(StandardOpenOption.CREATE_NEW);
        if (synchronous) {
            options.add(StandardOpenOption.SYNC);
            try {
                FileChannel opened = FileChannel.open(path, options);
                synchronousWrites = true;
                return opened;
            } catch (UnsupportedOperationException unsupported) {
                options.remove(StandardOpenOption.SYNC);
            }
        }
        synchronousWrites = false;
        return FileChannel.open(path, options);
    }

    private void persistStage() throws IOException {
        // SYNC 保证每次写入的内容与元数据已同步；其他通道保留阶段 force。
        if (!synchronousWrites) channel.force(true);
    }

    synchronized boolean usesSynchronousWrites() throws IOException { check(0); return synchronousWrites; }

    private void repairCommitMirror() throws IOException {
        if (mirrored) return;
        // 恢复后先修复另一份提交，不能在下一次写入时覆盖唯一的最新提交。
        byte[] marker = commitPage(commit);
        writeAt(channel, repairFirstOffset, marker);
        channel.force(true);
        writeAt(channel, repairFirstOffset == UNIT ? 2L * UNIT : UNIT, marker);
        channel.force(true);
        mirrored = true;
    }

    private List<Entry> allocations() {
        List<Entry> occupied = new ArrayList<>();
        for (Entry entry : entries) if (entry.offset != 0) occupied.add(entry);
        if (commit.dictionary.offset != 0) occupied.add(commit.dictionary);
        return occupied;
    }

    private long allocate(long bytes, List<Entry> reserved) {
        List<Entry> occupied = allocations();
        occupied.addAll(reserved);
        occupied.sort(Comparator.comparingLong(Entry::offset));
        long position = DATA_START;
        for (Entry entry : occupied) {
            if (entry.offset - position >= bytes) return position;
            position = entry.offset + aligned(entry.length);
        }
        return position;
    }

    private void trimTail() throws IOException {
        long end = DATA_START;
        for (Entry entry : allocations()) end = Math.max(end, entry.offset + aligned(entry.length));
        if (channel.size() > end) {
            channel.truncate(end);
            channel.force(true);
        }
    }

    public synchronized void flush() throws IOException { check(0); channel.force(true); }

    public synchronized boolean compactIfWasteful() throws IOException {
        check(0);
        long packedSize = DATA_START;
        for (Entry entry : allocations()) packedSize += aligned(entry.length);
        long fileSize = channel.size();
        if (fileSize - packedSize < Math.max(1024 * 1024, fileSize / 4)) return false;
        compact();
        return true;
    }

    public synchronized void compact() throws IOException {
        check(0);
        compact(version);
    }

    private void compact(int targetVersion) throws IOException {
        Path temporary = Files.createTempFile(path.toAbsolutePath().getParent(), ".chunksqueeze-compact-", ".tmp");
        try {
            Entry[] packed = new Entry[1024];
            long offset = DATA_START;
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                writeAt(output, 0, identity(HEADER_MAGIC, targetVersion));
                Entry packedDictionary = Entry.empty();
                byte[] dictionary = dictionary();
                if (dictionary != null) {
                    writeAt(output, offset, dictionary);
                    Entry old = commit.dictionary;
                    packedDictionary = new Entry(offset, old.length, 0, 2, old.crc, old.generation);
                    offset += aligned(old.length);
                }
                for (int slot = 0; slot < entries.length; slot++) {
                    Entry old = entries[slot];
                    Record record = read(slot);
                    if (record == null) {
                        packed[slot] = old;
                    } else {
                        writeAt(output, offset, record.payload);
                        packed[slot] = new Entry(offset, old.length, old.timestamp, old.codec, old.crc, old.generation);
                        offset += aligned(old.length);
                    }
                }
                if (output.size() < offset) writeAt(output, offset - 1, new byte[1]);
                byte[] table = table(packed);
                Commit replacement = new Commit(commit.generation, 0, crc(table), packedDictionary);
                writeAt(output, tableOffset(0), table);
                writeAt(output, tableOffset(1), table);
                writeAt(output, UNIT, commitPage(replacement));
                writeAt(output, 2L * UNIT, commitPage(replacement));
                output.force(true);
            }
            // 独立 owner 锁跨越关闭、原子替换与重开，避免替换期间出现第二个写者。
            channel.close();
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                channel = openChannel(false, preferSynchronous);
            }
            load();
        } catch (IOException | RuntimeException failure) {
            poisoned = true;
            throw failure;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public synchronized void setFaultInjector(FaultInjector injector) throws IOException {
        check(0);
        faults = Objects.requireNonNull(injector);
    }

    private void check(int slot) throws IOException {
        if (closed || poisoned || channel == null || !channel.isOpen()) throw new IOException("Compact writer is closed or faulted");
        if (slot < 0 || slot >= 1024) throw new IOException("Compact slot out of range");
    }

    private byte[] identity(byte[] magic) {
        return identity(magic, Arrays.equals(magic, OWNER_MAGIC) ? 1 : version);
    }

    private byte[] identity(byte[] magic, int formatVersion) {
        byte[] page = new byte[UNIT];
        buffer(page).put(magic).putInt(formatVersion).putInt(regionX).putInt(regionZ).putInt(UNIT)
                .putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
        stamp(page);
        return page;
    }

    private UUID parseIdentity(byte[] page, byte[] expectedMagic) throws IOException {
        if (!validPage(page, 44)) throw new IOException("Compact identity checksum mismatch");
        ByteBuffer input = buffer(page);
        byte[] magic = new byte[8]; input.get(magic);
        int formatVersion = input.getInt();
        boolean header = Arrays.equals(expectedMagic, HEADER_MAGIC);
        if (!Arrays.equals(magic, expectedMagic) || (formatVersion != 1 && (!header || formatVersion != 2))
                || input.getInt() != regionX || input.getInt() != regionZ || input.getInt() != UNIT) {
            throw new IOException("Unsupported or mismatched compact identity");
        }
        if (header) version = formatVersion;
        return new UUID(input.getLong(), input.getLong());
    }

    private byte[] commitPage(Commit value) {
        byte[] page = new byte[UNIT];
        buffer(page).put(COMMIT_MAGIC).putLong(value.generation).putInt(value.slot).putInt(value.tableCrc)
                .putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
        putEntry(buffer(page).position(44), value.dictionary);
        stamp(page);
        return page;
    }

    private Commit parseCommit(byte[] page) {
        if (!validPage(page, version == 1 ? 44 : 76)) return null;
        ByteBuffer input = buffer(page);
        byte[] magic = new byte[8]; input.get(magic);
        long generation = input.getLong();
        int slot = input.getInt();
        int crc = input.getInt();
        UUID fileId = new UUID(input.getLong(), input.getLong());
        if (!Arrays.equals(magic, COMMIT_MAGIC) || generation < 0 || slot < 0 || slot > 1 || !id.equals(fileId)) return null;
        return new Commit(generation, slot, crc, readEntry(input.position(44)));
    }

    private static void validatePayload(byte[] payload, int codec, boolean hasDictionary) throws IOException {
        if (payload != null && (payload.length == 0 || payload.length > MAX_PAYLOAD || codec < 1 || codec > 5)) {
            throw new IOException("Unsupported compact payload");
        }
        if (payload != null && codec >= 4 && !hasDictionary) throw new IOException("Region references require a dictionary");
    }

    private static void validateDictionary(byte[] dictionary) throws IOException {
        if (dictionary != null && (dictionary.length == 0 || dictionary.length > MAX_DICTIONARY)) {
            throw new IOException("Unsupported compact dictionary size");
        }
    }

    private static boolean validAllocation(Entry entry, int maximum, long fileSize) {
        return entry.offset >= DATA_START && entry.offset % UNIT == 0 && entry.length > 0
                && entry.length <= maximum && entry.offset <= fileSize - entry.length;
    }

    private static Entry readEntry(ByteBuffer input) {
        return new Entry(input.getLong(), input.getInt(), input.getInt(), input.getInt(), input.getInt(), input.getLong());
    }

    private static void putEntry(ByteBuffer output, Entry entry) {
        output.putLong(entry.offset).putInt(entry.length).putInt(entry.timestamp)
                .putInt(entry.codec).putInt(entry.crc).putLong(entry.generation);
    }

    private static byte[] table(Entry[] entries) {
        ByteBuffer bytes = ByteBuffer.allocate(TABLE_BYTES).order(ByteOrder.BIG_ENDIAN);
        for (Entry entry : entries) putEntry(bytes, entry);
        return bytes.array();
    }

    private static long tableOffset(int slot) { return 3L * UNIT + (long) slot * TABLE_BYTES; }
    private static long aligned(int bytes) { return ((long) bytes + UNIT - 1) / UNIT * UNIT; }
    private static ByteBuffer buffer(byte[] data) { return ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN); }
    private static byte[] ascii(String text) { return text.getBytes(StandardCharsets.US_ASCII); }
    private static int crc(byte[] data) { CRC32 crc = new CRC32(); crc.update(data); return (int) crc.getValue(); }
    private static int pageCrc(byte[] page) {
        CRC32 crc = new CRC32();
        crc.update(page, 0, 40);
        crc.update(page, 44, UNIT - 44);
        return (int) crc.getValue();
    }
    private static void stamp(byte[] page) { buffer(page).putInt(40, pageCrc(page)); }
    private static boolean validPage(byte[] page, int reservedStart) {
        if (page.length != UNIT || buffer(page).getInt(40) != pageCrc(page)) return false;
        for (int i = reservedStart; i < UNIT; i++) if (page[i] != 0) return false;
        return true;
    }
    private static byte[] readAt(FileChannel channel, long offset, int length) throws IOException {
        ByteBuffer bytes = ByteBuffer.allocate(length);
        while (bytes.hasRemaining()) {
            int read = channel.read(bytes, offset + bytes.position());
            if (read <= 0) throw new IOException("Truncated compact file");
        }
        return bytes.array();
    }
    private static void writeAt(FileChannel channel, long offset, byte[] data) throws IOException {
        ByteBuffer bytes = ByteBuffer.wrap(data);
        while (bytes.hasRemaining()) {
            if (channel.write(bytes, offset + bytes.position()) <= 0) throw new IOException("Compact write made no progress");
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException error = null;
        for (AutoCloseable resource : new AutoCloseable[]{channel, ownerLock, ownerChannel}) {
            if (resource == null) continue;
            try { resource.close(); }
            catch (Exception failure) {
                if (error == null) error = new IOException("Cannot close compact region", failure);
                else error.addSuppressed(failure);
            }
        }
        if (lockKey != null) OPEN_OWNERS.remove(lockKey, this);
        if (error != null) throw error;
    }
}
