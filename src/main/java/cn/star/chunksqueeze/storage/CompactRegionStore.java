package cn.star.chunksqueeze.storage;

import cn.star.chunksqueeze.Config;
import cn.star.chunksqueeze.codec.ChunkCodec;
import cn.star.chunksqueeze.codec.RegionStateDictionary;
import cn.star.chunksqueeze.codec.SectionCodec;
import cn.star.chunksqueeze.migration.MigrationGate;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtAccounterException;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.util.FastBufferedInputStream;
import net.minecraft.world.level.ChunkPos;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

public final class CompactRegionStore implements AutoCloseable {
    private final Path folder;
    private final DictionaryCache<CompactRegion> dictionaryCache;
    private final LinkedHashMap<Long, CompactRegion> regions = new LinkedHashMap<>(16, .75f, true);
    private final Map<CompactRegion, long[]> attempted = new IdentityHashMap<>();
    private final java.util.concurrent.ConcurrentMap<Long, Long> regionActivity = new java.util.concurrent.ConcurrentHashMap<>();
    private long foregroundKey;
    private final BackgroundReencoder background;
    private static final AtomicLong NEXT_ID = new AtomicLong();
    private final long backgroundId = NEXT_ID.incrementAndGet();
    private volatile boolean closed;
    private volatile boolean foregroundWriting;
    private volatile long lastForegroundNanos = System.nanoTime();
    private static final int MAX_OPEN = 64;
    private static final int MAX_BACKGROUND_COMPRESSED = 16 * 1024 * 1024;
    // 所有维度共用预算；句柄身份隔离迁移、重开后的旧字典。
    private static final DictionaryCache<CompactRegion> GLOBAL_DICTIONARIES = new DictionaryCache<>(
            DictionaryCache.defaultBudget(Runtime.getRuntime().maxMemory()), 64);

    private RegionStateDictionary dictionary(CompactRegion region) throws IOException {
        RegionStateDictionary dictionary = dictionaryCache.get(region);
        if (dictionary == null) {
            byte[] bytes = region.dictionary();
            dictionary = bytes == null ? RegionStateDictionary.create() : RegionStateDictionary.fromCompressed(bytes);
            dictionaryCache.put(region, dictionary);
        }
        return dictionary;
    }

    private void beginForeground(ChunkPos pos) throws IOException {
        if (closed) throw new IOException("Region storage is closed");
        foregroundWriting = true;
        foregroundKey = regionKey(pos);
        lastForegroundNanos = System.nanoTime();
    }

    private void endForeground() {
        long now = System.nanoTime();
        if (regions.containsKey(foregroundKey)) regionActivity.put(foregroundKey, now);
        lastForegroundNanos = now;
        foregroundWriting = false;
    }

    public synchronized ChunkCodec.Result writeEncoded(ChunkPos pos, CompoundTag original,
                                                       ChunkCodec.Options options) throws IOException {
        beginForeground(pos);
        try {
            boolean owned = owns(pos);
            if (!owned && hasMca(pos)) throw new IOException("Cannot overwrite an MCA with compact storage");
            CompactRegion region = region(pos, true);
            RegionStateDictionary dictionary = dictionary(region);
            long previous = dictionary.revision();
            boolean committed = false;
            try {
                SectionCodec.Encoded encoded = SectionCodec.encodeForStorage(original, options, dictionary);
                CompoundTag tag = encoded.result().tag();
                int codec = encodedCodec(tag);
                byte[] changed = previous != dictionary.revision() ? dictionary.compressed() : null;
                region.writeWithDictionary(slot(pos), encoded.payload(), codec,
                        (int) (System.currentTimeMillis() / 1000L), changed);
                dictionaryCache.put(region, dictionary);
                committed = true;
                return encoded.result();
            } finally {
                // 未提交的追加不能在下一次写入中被误认为已持久化。
                if (!committed) dictionaryCache.remove(region);
            }
        } finally { endForeground(); }
    }

    public synchronized void writeDeferred(ChunkPos pos, CompoundTag original,
                                           ChunkCodec.Options options) throws IOException {
        if (original == null) { write(pos, null); return; }
        if (original.contains(ChunkCodec.KEY) || original.contains(SectionCodec.KEY)) {
            throw new IOException("Encoded NBT reached the deferred write API");
        }
        beginForeground(pos);
        try {
            boolean owned = owns(pos);
            if (!owned && hasMca(pos)) throw new IOException("Cannot overwrite an MCA with compact storage");
            CompactRegion region = region(pos, true);
            byte[] payload = compress(original);
            region.write(slot(pos), payload, 2, (int) (System.currentTimeMillis() / 1000L));
            // 原始完整 NBT 已提交；队列失败或饱和只影响后续空间优化。
            queuePlain(regionKey(pos), region, slot(pos), options);
        } finally { endForeground(); }
    }

    private static int encodedCodec(CompoundTag tag) {
        return tag.contains(SectionCodec.KEY) ? 5 : tag.contains(ChunkCodec.KEY)
                && tag.getCompound(ChunkCodec.KEY).getInt("version") == 2 ? 4 : 2;
    }

    private static byte[] compress(CompoundTag tag) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(new BufferedOutputStream(new DeflaterOutputStream(bytes)))) {
            NbtIo.write(tag, output);
        }
        return bytes.toByteArray();
    }

    public CompactRegionStore(Path folder) { this(folder, GLOBAL_DICTIONARIES, null); }

    public CompactRegionStore(Path folder, BackgroundReencoder background) {
        this(folder, GLOBAL_DICTIONARIES, background);
    }

    CompactRegionStore(Path folder, DictionaryCache<CompactRegion> dictionaryCache) {
        this(folder, dictionaryCache, null);
    }

    private CompactRegionStore(Path folder, DictionaryCache<CompactRegion> dictionaryCache,
                               BackgroundReencoder background) {
        this.folder = folder;
        this.dictionaryCache = dictionaryCache;
        this.background = background;
    }

    long backgroundId() { return backgroundId; }

    boolean backgroundReady(long key, long nowNanos, long quietNanos) {
        return !closed && !foregroundWriting
                && nowNanos - regionActivity.getOrDefault(key, lastForegroundNanos) >= quietNanos;
    }

    public synchronized boolean hasOpenRegion(ChunkPos pos) { return regions.containsKey(regionKey(pos)); }

    public synchronized boolean owns(ChunkPos pos) throws IOException {
        Path compact = compactPath(pos);
        boolean compactExists = Files.exists(compact);
        boolean ownerExists = Files.exists(CompactRegion.ownerPath(compact));
        boolean present = compactExists || ownerExists;
        if (compactExists != ownerExists) throw new IOException("Missing compact region or ownership file: " + compact);
        if (present && Files.exists(mcaPath(pos))) throw new IOException("Ambiguous MCA and compact region: " + compact);
        if (regions.containsKey(regionKey(pos)) && !present) throw new IOException("Open compact ownership files disappeared: " + compact);
        return present;
    }

    public boolean hasMca(ChunkPos pos) { return Files.exists(mcaPath(pos)); }

    public synchronized CompoundTag read(ChunkPos pos) throws IOException {
        CompactRegion region = region(pos, false);
        CompactRegion.Record record = region.read(slot(pos));
        if (record == null) return null;
        InputStream bytes = new ByteArrayInputStream(record.payload());
        InputStream decoded = switch (record.codec()) {
            case 1 -> new GZIPInputStream(bytes);
            case 2, 4, 5 -> new InflaterInputStream(bytes);
            case 3 -> bytes;
            default -> throw new IOException("Unsupported compact compression");
        };
        try (var input = new DataInputStream(new FastBufferedInputStream(decoded))) {
            CompoundTag tag = NbtIo.read(input, NbtAccounter.unlimitedHeap());
            if (input.read() != -1) throw new IOException("Trailing compact chunk NBT");
            if (record.codec() == 5) {
                if (!tag.contains(SectionCodec.KEY)) throw new IOException("Section record is missing its marker");
                return SectionCodec.decode(tag, dictionary(region));
            }
            if (tag.contains(SectionCodec.KEY)) throw new IOException("Section marker requires compact codec 5");
            if (record.codec() == 4) {
                if (!tag.contains(ChunkCodec.KEY) || tag.getCompound(ChunkCodec.KEY).getInt("version") != 2) {
                    throw new IOException("Shared record is missing its version 2 marker");
                }
                return ChunkCodec.decode(tag, dictionary(region));
            }
            if (tag.contains(ChunkCodec.KEY) && tag.getCompound(ChunkCodec.KEY).getInt("version") == 2) {
                throw new IOException("Shared marker requires compact codec 4");
            }
            if (record.codec() == 2 && !tag.contains(ChunkCodec.KEY)) {
                queuePlain(regionKey(pos), region, slot(pos), configuredOptions());
            }
            return tag;
        }
    }

    public synchronized void write(ChunkPos pos, CompoundTag tag) throws IOException {
        beginForeground(pos);
        try {
            boolean owned = owns(pos);
            if (!owned && hasMca(pos)) throw new IOException("Cannot overwrite an MCA with compact storage");
            CompactRegion region = region(pos, true);
            byte[] payload = null;
            if (tag != null) {
                if (tag.contains(SectionCodec.KEY)
                        || tag.contains(ChunkCodec.KEY) && tag.getCompound(ChunkCodec.KEY).getInt("version") == 2) {
                    throw new IOException("Shared marker requires the dictionary-aware write API");
                }
                payload = compress(tag);
            }
            region.write(slot(pos), payload, 2, (int) (System.currentTimeMillis() / 1000L));
        } finally { endForeground(); }
    }

    private static ChunkCodec.Options configuredOptions() {
        return new ChunkCodec.Options(Config.minimumSaving, Config.requireSectorSaving);
    }

    private void queuePlain(long key, CompactRegion region, int slot, ChunkCodec.Options options) throws IOException {
        if (background != null && region.codec(slot) == 2
                && attempted.get(region)[slot] != region.chunkGeneration(slot)) {
            background.offer(this, key, region, slot, options);
        }
    }

    private CompactRegion region(ChunkPos pos, boolean create) throws IOException {
        if (closed) throw new IOException("Region storage is closed");
        long key = regionKey(pos);
        CompactRegion cached = regions.get(key);
        if (cached != null) return cached;
        owns(pos);
        if (regions.size() >= MAX_OPEN) {
            Iterator<Map.Entry<Long, CompactRegion>> iterator = regions.entrySet().iterator();
            Map.Entry<Long, CompactRegion> oldest = iterator.next();
            iterator.remove();
            closeChecked(oldest.getKey(), oldest.getValue());
        }
        CompactRegion opened = CompactRegion.open(compactPath(pos), pos.getRegionX(), pos.getRegionZ(), create);
        regions.put(key, opened);
        regionActivity.put(key, System.nanoTime());
        if (background != null) {
            attempted.put(opened, new long[1024]);
            // 停服或队列满时留下的普通记录，在区域下次打开时恢复优化请求。
            background.offerRegion(this, key, opened, opened.plainSlots(), configuredOptions());
        }
        return opened;
    }

    public synchronized void closeRegion(int regionX, int regionZ) throws IOException {
        long key = ChunkPos.asLong(regionX, regionZ);
        CompactRegion cached = regions.remove(key);
        if (cached != null) closeChecked(key, cached);
    }

    private void checkOwnership(long key) throws IOException {
        ChunkPos pos = new ChunkPos((int) key * 32, (int) (key >>> 32) * 32);
        if (!owns(pos)) throw new IOException("Open compact ownership files disappeared: " + compactPath(pos));
    }

    private void closeChecked(long key, CompactRegion region) throws IOException {
        if (background != null) background.cancel(this, region);
        attempted.remove(region);
        regionActivity.remove(key);
        dictionaryCache.remove(region);
        try { checkOwnership(key); }
        catch (IOException | RuntimeException failure) {
            try { region.close(); } catch (IOException | RuntimeException close) { failure.addSuppressed(close); }
            throw failure;
        }
        region.close();
    }

    public synchronized void flush() throws IOException {
        if (closed) throw new IOException("Region storage is closed");
        for (Map.Entry<Long, CompactRegion> entry : regions.entrySet()) {
            checkOwnership(entry.getKey());
            entry.getValue().flush();
            entry.getValue().compactIfWasteful();
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        if (background != null) background.cancel(this);
        IOException failure = null;
        try {
            for (Map.Entry<Long, CompactRegion> entry : regions.entrySet()) {
                try { closeChecked(entry.getKey(), entry.getValue()); }
                catch (IOException | RuntimeException exception) {
                    IOException io = exception instanceof IOException e ? e : new IOException("Region close failed", exception);
                    if (failure == null) failure = io;
                    else failure.addSuppressed(io);
                }
            }
        } finally { regions.clear(); attempted.clear(); regionActivity.clear(); }
        if (failure != null) throw failure;
    }

    static final class ReencodeContext {
        CompactRegion region;
        long dictionaryGeneration;
        RegionStateDictionary dictionary;
    }

    record ReencodeSnapshot(CompactRegion region, int slot, long generation, long dictionaryGeneration,
                            int timestamp, byte[] payload, byte[] dictionary, boolean refreshDictionary) {}
    record ReencodePrepared(ReencodeSnapshot snapshot, ReencodeContext context,
                            SectionCodec.Encoded encoded, byte[] changedDictionary) {}

    ReencodeSnapshot snapshotForReencode(long key, CompactRegion expected, int slot,
                                         ReencodeContext context) throws IOException {
        try (var ignored = MigrationGate.region(folder, (int) key, (int) (key >>> 32))) {
            synchronized (this) {
                if (closed || regions.get(key) != expected) return null;
                StorageGuard.check();
                checkOwnership(key);
                if (expected.codec(slot) != 2) return null;
                long generation = expected.chunkGeneration(slot);
                long[] tried = attempted.get(expected);
                if (tried[slot] == generation) return null;
                tried[slot] = generation;
                if (expected.payloadLength(slot) > MAX_BACKGROUND_COMPRESSED) {
                    background.completed(false);
                    return null;
                }
                CompactRegion.Record record = expected.read(slot);
                long dictionaryGeneration = expected.dictionaryGeneration();
                boolean refresh = context.dictionary == null || context.region != expected
                        || context.dictionaryGeneration != dictionaryGeneration;
                return new ReencodeSnapshot(expected, slot, generation, dictionaryGeneration, record.timestamp(),
                        record.payload(), refresh ? expected.dictionary() : null, refresh);
            }
        }
    }

    ReencodePrepared prepareReencode(ReencodeSnapshot snapshot, ReencodeContext context,
                                      ChunkCodec.Options options) throws IOException {
        CompoundTag original;
        try (var input = new DataInputStream(new FastBufferedInputStream(
                new InflaterInputStream(new ByteArrayInputStream(snapshot.payload))))) {
            // 大于后台预算的合法普通 NBT 保持原样，不能因可选优化而拒绝保存。
            original = NbtIo.read(input, NbtAccounter.create(ChunkCodec.MAX_BYTES));
            if (input.read() != -1) throw new IOException("Trailing compact chunk NBT");
        } catch (NbtAccounterException oversized) { return null; }
        if (original.contains(ChunkCodec.KEY) || original.contains(SectionCodec.KEY)
                || !original.contains("Status", Tag.TAG_STRING) || original.getString("Status").isEmpty()
                || !original.contains("sections", Tag.TAG_LIST)) return null;
        if (snapshot.refreshDictionary) {
            context.region = snapshot.region;
            context.dictionaryGeneration = snapshot.dictionaryGeneration;
            context.dictionary = snapshot.dictionary == null ? RegionStateDictionary.create()
                    : RegionStateDictionary.fromCompressed(snapshot.dictionary);
        }
        long revision = context.dictionary.revision();
        SectionCodec.Encoded encoded = SectionCodec.encodeForStorage(original, options, context.dictionary);
        int oldBytes = snapshot.payload.length, newBytes = encoded.payload().length;
        // 还要对照已经落盘的载荷，避免 NBT 键迭代顺序变化误判收益。
        if (!encoded.result().optimized() || newBytes >= oldBytes || oldBytes - newBytes < options.minimumSaving()
                || options.requireSectorSaving() && ChunkCodec.allocatedBytes(newBytes, 512, 0)
                >= ChunkCodec.allocatedBytes(oldBytes, 512, 0)) {
            if (revision != context.dictionary.revision()) context.dictionary = null;
            return null;
        }
        byte[] changed = revision != context.dictionary.revision() ? context.dictionary.compressed() : null;
        return new ReencodePrepared(snapshot, context, encoded, changed);
    }

    boolean publishReencode(long key, ReencodePrepared prepared, ChunkCodec.Options options,
                            BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean()) return false;
        ReencodeSnapshot snapshot = prepared.snapshot;
        try (var ignored = MigrationGate.region(folder, (int) key, (int) (key >>> 32))) {
            synchronized (this) {
                if (cancelled.getAsBoolean() || closed || regions.get(key) != snapshot.region) return false;
                StorageGuard.check();
                checkOwnership(key);
                CompactRegion region = snapshot.region;
                if (region.chunkGeneration(snapshot.slot) != snapshot.generation || region.codec(snapshot.slot) != 2) return false;
                if (region.dictionaryGeneration() != snapshot.dictionaryGeneration) {
                    attempted.get(region)[snapshot.slot] = 0;
                    queuePlain(key, region, snapshot.slot, options);
                    return false;
                }
                boolean committed = false;
                try {
                    region.writeWithDictionary(snapshot.slot, prepared.encoded.payload(),
                            encodedCodec(prepared.encoded.result().tag()), snapshot.timestamp, prepared.changedDictionary);
                    prepared.context.dictionaryGeneration = region.dictionaryGeneration();
                    attempted.get(region)[snapshot.slot] = region.chunkGeneration(snapshot.slot);
                    committed = true;
                } finally {
                    // 字典未改变时保留读缓存；不共享后台可变字典或未提交编号。
                    if (!committed || prepared.changedDictionary != null) dictionaryCache.remove(region);
                    if (!committed) prepared.context.dictionary = null;
                }
                StorageGuard.encoded(new ChunkCodec.Result(prepared.encoded.result().tag(), snapshot.payload.length,
                        prepared.encoded.payload().length, prepared.encoded.result().encoding()), 512, 0);
                return true;
            }
        }
    }

    void reencodeBatch(long key, CompactRegion expected, BitSet slots, ChunkCodec.Options options,
                       BooleanSupplier cancelled) throws IOException {
        reencodeOrderedBatch(key, expected, slots.stream().toArray(), options, cancelled);
    }

    void reencodeOrderedBatch(long key, CompactRegion expected, int[] slots, ChunkCodec.Options options,
                              BooleanSupplier cancelled) throws IOException {
        ReencodeContext context = new ReencodeContext();
        for (int slot : slots) {
            if (cancelled.getAsBoolean()) break;
            ReencodeSnapshot snapshot = snapshotForReencode(key, expected, slot, context);
            if (snapshot == null) continue;
            if (cancelled.getAsBoolean()) break;
            ReencodePrepared prepared = prepareReencode(snapshot, context, options);
            boolean published = prepared != null && publishReencode(key, prepared, options, cancelled);
            if (prepared != null && !published) context.dictionary = null;
            background.completed(published);
        }
    }

    private Path compactPath(ChunkPos pos) { return folder.resolve("r." + pos.getRegionX() + "." + pos.getRegionZ() + ".cqs"); }
    private Path mcaPath(ChunkPos pos) { return folder.resolve("r." + pos.getRegionX() + "." + pos.getRegionZ() + ".mca"); }
    private static long regionKey(ChunkPos pos) { return ChunkPos.asLong(pos.getRegionX(), pos.getRegionZ()); }
    private static int slot(ChunkPos pos) { return pos.getRegionLocalX() + 32 * pos.getRegionLocalZ(); }
}
