package cn.star.chunksqueeze.codec;

import cn.star.chunksqueeze.compat.NbtAccounter;
import cn.star.chunksqueeze.compat.NbtAccounterException;

import net.minecraft.nbt.*;

import java.io.*;
import java.util.*;
import java.util.zip.DataFormatException;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;

public final class RegionStateDictionary {
    private static final int MAX_STATES = 65536;
    private static final int MAX_RAW_BYTES = 4 * 1024 * 1024;
    private static final int MAX_V2_RAW_BYTES = 8 * 1024 * 1024;
    private static final int MAX_SECTIONS = 4096;
    private static final int MAX_RECENT = 64;
    private static final int MAX_RECENT_BYTES = 1024 * 1024;
    private static final int MAX_RECENT_ENTRY = 256 * 1024;
    private static final Header HEADER = header();
    private final UUID identity;
    private final ListTag states;
    private final ListTag sections;
    private final Map<ExactCompound, Integer> ids = new HashMap<>();
    private final Map<ExactCompound, Integer> sectionIds = new HashMap<>();
    private final LinkedHashMap<ExactCompound, Observation> recent = new LinkedHashMap<>(16, 0.75f, true);
    private final List<RestoredSize> stateSizes = new ArrayList<>();
    private final List<RestoredSize> sectionSizes = new ArrayList<>();
    private long recentRaw;
    private long recentHeap;
    private long rawSize;
    private long heapSize;
    private boolean sectionFormat;
    private final long headerHeap;
    private final long sectionHeaderHeap;
    private final long sectionHeaderRaw;

    private record Observation(long raw, long heap) {}
    private record Header(long raw, long heap, long sectionRaw, long sectionHeap) {}

    private record RestoredSize(long bytes, String error) {
        static RestoredSize measure(CompoundTag tag, int depth) {
            try {
                return new RestoredSize(NbtSize.payload(tag, depth, ChunkCodec.MAX_BYTES), null);
            } catch (IOException invalid) {
                return new RestoredSize(0, invalid.getMessage());
            }
        }

        long value() throws IOException {
            if (error != null) throw new IOException(error);
            return bytes;
        }
    }

    static final class ExactCompound {
        private final CompoundTag tag;
        private final int hash;

        ExactCompound(CompoundTag tag) {
            this.tag = tag;
            this.hash = tag.hashCode();
        }

        public CompoundTag tag() { return tag; }

        @Override public int hashCode() { return hash; }

        @Override public boolean equals(Object other) {
            return this == other || other instanceof ExactCompound section && exact(tag, section.tag);
        }

        // 原版浮点 equals 不区分正负零；按 NBT 写出的 canonical NaN 位值精确比较。
        private static boolean exact(Tag left, Tag right) {
            if (left.getClass() != right.getClass() || !NbtHeapSize.standardTag(left)) return false;
            if (left == right) return true;
            if (left.getId() != right.getId()) return false;
            if (left instanceof FloatTag value) {
                return Float.floatToIntBits(value.getAsFloat()) == Float.floatToIntBits(((FloatTag) right).getAsFloat());
            }
            if (left instanceof DoubleTag value) {
                return Double.doubleToLongBits(value.getAsDouble()) == Double.doubleToLongBits(((DoubleTag) right).getAsDouble());
            }
            if (left instanceof CompoundTag compound) {
                CompoundTag other = (CompoundTag) right;
                if (!compound.getAllKeys().equals(other.getAllKeys())) return false;
                for (String key : compound.getAllKeys()) {
                    if (!exact(compound.get(key), other.get(key))) return false;
                }
                return true;
            }
            if (left instanceof ListTag list) {
                ListTag other = (ListTag) right;
                if (list.size() != other.size() || list.getElementType() != other.getElementType()) return false;
                for (int i = 0; i < list.size(); i++) if (!exact(list.get(i), other.get(i))) return false;
                return true;
            }
            return left.equals(right);
        }
    }

    private RegionStateDictionary(UUID identity, ListTag states, ListTag sections, boolean sectionFormat,
                                  long rawSize, long heapSize) throws IOException {
        this.identity = identity;
        this.states = states;
        this.sections = sections;
        this.sectionFormat = sectionFormat;
        this.rawSize = rawSize;
        this.headerHeap = HEADER.heap();
        this.sectionHeaderHeap = HEADER.sectionHeap();
        this.sectionHeaderRaw = HEADER.sectionRaw();
        this.heapSize = heapSize < 0 ? headerHeap : heapSize;
        for (int i = 0; i < states.size(); i++) {
            CompoundTag state = states.getCompound(i);
            ids.putIfAbsent(new ExactCompound(state), i);
            stateSizes.add(RestoredSize.measure(state, 5));
        }
        for (int i = 0; i < sections.size(); i++) {
            CompoundTag section = sections.getCompound(i);
            sectionIds.putIfAbsent(new ExactCompound(section), i);
            sectionSizes.add(RestoredSize.measure(section, 2));
        }
    }

    public static RegionStateDictionary create() {
        UUID identity = UUID.randomUUID();
        ListTag states = new ListTag();
        ListTag sections = new ListTag();
        try {
            return new RegionStateDictionary(identity, states, sections, false,
                    HEADER.raw(), -1);
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public static RegionStateDictionary fromCompressed(byte[] compressed) throws IOException {
        try {
            byte[] raw = inflate(compressed);
            var in = new DataInputStream(new ByteArrayInputStream(raw));
            NbtAccounter accounter = NbtAccounter.create(ChunkCodec.MAX_BYTES);
            CompoundTag root = NbtIo.read(in, accounter);
            if (in.available() != 0) throw new IOException("Trailing region dictionary data");
            int version = root.getInt("version");
            if (!root.contains("version", Tag.TAG_INT) || (version != 1 && version != 2)
                    || !root.contains("idMost", Tag.TAG_LONG) || !root.contains("idLeast", Tag.TAG_LONG)
                    || !root.contains("states", Tag.TAG_LIST)
                    || (version == 2 && !root.contains("sections", Tag.TAG_LIST))) {
                throw new IOException("Invalid region dictionary header");
            }
            if (raw.length > (version == 1 ? MAX_RAW_BYTES : MAX_V2_RAW_BYTES)) {
                throw new IOException("Region dictionary exceeds raw size limit");
            }
            ListTag states = (ListTag) root.get("states");
            ListTag sections = version == 1 ? new ListTag() : (ListTag) root.get("sections");
            checkEntries(states, MAX_STATES, "states");
            checkEntries(sections, MAX_SECTIONS, "sections");
            UUID identity = new UUID(root.getLong("idMost"), root.getLong("idLeast"));
            long size = NbtSize.rootWithout(root(identity, states, sections, version == 2), null,
                    version == 1 ? MAX_RAW_BYTES : MAX_V2_RAW_BYTES);
            return new RegionStateDictionary(identity, states, sections, version == 2, size, accounter.getUsage());
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid region dictionary NBT", invalid);
        }
    }

    private static void checkEntries(ListTag entries, int max, String name) throws IOException {
        if ((!entries.isEmpty() && entries.getElementType() != Tag.TAG_COMPOUND) || entries.size() > max) {
            throw new IOException("Invalid region dictionary " + name);
        }
    }

    private static byte[] inflate(byte[] compressed) throws IOException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            var bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer, 0, Math.min(buffer.length, MAX_V2_RAW_BYTES + 1 - bytes.size()));
                bytes.write(buffer, 0, count);
                if (bytes.size() > MAX_V2_RAW_BYTES) throw new IOException("Region dictionary exceeds raw size limit");
                if (count == 0 && !inflater.finished()) throw new IOException("Incomplete or invalid region dictionary zlib stream");
            }
            if (inflater.getRemaining() != 0) throw new IOException("Trailing region dictionary zlib data");
            return bytes.toByteArray();
        } catch (DataFormatException invalid) {
            throw new IOException("Invalid region dictionary zlib stream", invalid);
        } finally {
            inflater.end();
        }
    }

    public synchronized byte[] compressed() throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(new BufferedOutputStream(new DeflaterOutputStream(bytes)))) {
            NbtIo.write(root(identity, states, sections, sectionFormat), out);
        }
        return bytes.toByteArray();
    }

    private static Header header() {
        try {
            UUID identity = new UUID(0, 0);
            CompoundTag v1 = root(identity, new ListTag(), new ListTag(), false);
            CompoundTag v2 = root(identity, new ListTag(), new ListTag(), true);
            long raw = NbtSize.rootWithout(v1, null, MAX_RAW_BYTES);
            long heap = NbtHeapSize.root(v1, ChunkCodec.MAX_BYTES);
            return new Header(raw, heap, NbtSize.rootWithout(v2, null, MAX_V2_RAW_BYTES) - raw,
                    NbtHeapSize.root(v2, ChunkCodec.MAX_BYTES) - heap);
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    // 计入池与近期观察的 NBT 计额，并为索引、wrapper、尺寸缓存和容器容量预留空间。
    public synchronized long estimatedMemoryBytes() {
        return heapSize + recentHeap + 128L * (states.size() + sections.size() + recent.size()) + 64 * 1024;
    }

    public static boolean exactAccountingSupported() { return NbtHeapSize.exactAccountingSupported(); }

    public synchronized int size() { return states.size(); }
    public synchronized int sectionCount() { return sections.size(); }
    public synchronized long revision() { return (long) states.size() + sections.size(); }
    public UUID identity() { return identity; }

    private static CompoundTag root(UUID identity, ListTag states, ListTag sections) {
        return root(identity, states, sections, !sections.isEmpty());
    }

    private static CompoundTag root(UUID identity, ListTag states, ListTag sections, boolean sectionFormat) {
        CompoundTag root = new CompoundTag();
        root.putInt("version", sectionFormat ? 2 : 1);
        root.putLong("idMost", identity.getMostSignificantBits());
        root.putLong("idLeast", identity.getLeastSignificantBits());
        root.put("states", states);
        if (sectionFormat) root.put("sections", sections);
        return root;
    }

    static final class Addition {
        final int[] mapping;
        private final List<CompoundTag> added;
        private final long rawSize;
        private final long heapSize;

        private Addition(int[] mapping, List<CompoundTag> added, long rawSize, long heapSize) {
            this.mapping = mapping;
            this.added = added;
            this.rawSize = rawSize;
            this.heapSize = heapSize;
        }
    }

    synchronized Addition prepareExisting(ListTag local) {
        int[] mapping = new int[local.size()];
        for (int i = 0; i < local.size(); i++) {
            Integer id = ids.get(new ExactCompound(local.getCompound(i)));
            if (id == null) return null;
            mapping[i] = id;
        }
        return new Addition(mapping, List.of(), rawSize, heapSize);
    }

    // 调用方在整个候选比较与提交期间持有字典锁，编号只追加，不回收。
    synchronized Addition prepare(ListTag local) throws IOException {
        int[] mapping = new int[local.size()];
        List<CompoundTag> added = new ArrayList<>();
        Map<ExactCompound, Integer> pending = new HashMap<>();
        long size = rawSize;
        for (int i = 0; i < local.size(); i++) {
            CompoundTag state = local.getCompound(i);
            ExactCompound key = new ExactCompound(state);
            Integer id = ids.get(key);
            if (id == null) id = pending.get(key);
            if (id == null) {
                if (states.size() + added.size() >= MAX_STATES) return null;
                size += NbtSize.payload(state, 2, ChunkCodec.MAX_BYTES);
                if (size > (sectionFormat ? MAX_V2_RAW_BYTES : MAX_RAW_BYTES)) return null;
                id = states.size() + added.size();
                pending.put(key, id);
                added.add(state);
            }
            mapping[i] = id;
        }
        long heap = heapSize;
        try {
            for (CompoundTag state : added) {
                heap += NbtHeapSize.listEntry(state, ChunkCodec.MAX_BYTES);
                if (heap > ChunkCodec.MAX_BYTES) return null;
            }
        } catch (NbtAccounterException capacity) {
            return null;
        }
        return new Addition(mapping, added, size, heap);
    }

    synchronized void append(Addition addition) {
        while (!recent.isEmpty() && recentHeap + addition.heapSize > ChunkCodec.MAX_BYTES) removeOldest();
        for (CompoundTag entry : addition.added) {
            CompoundTag copy = entry.copy();
            ids.put(new ExactCompound(copy), states.size());
            states.add(copy);
            stateSizes.add(RestoredSize.measure(copy, 5));
        }
        rawSize = addition.rawSize;
        heapSize = addition.heapSize;
        trimRecent();
    }

    static final class SectionAddition {
        final int[] mapping;
        private final List<CompoundTag> added;
        private final long rawSize;
        private final long heapSize;

        private SectionAddition(int[] mapping, List<CompoundTag> added, long rawSize, long heapSize) {
            this.mapping = mapping;
            this.added = added;
            this.rawSize = rawSize;
            this.heapSize = heapSize;
        }
    }

    // 观察原始完整区段；近期缓存仅用于证明重复，计划获选前不写持久池。
    synchronized SectionAddition prepareSections(ListTag local) throws IOException {
        int[] mapping = new int[local.size()];
        Arrays.fill(mapping, -1);
        List<CompoundTag> added = new ArrayList<>();
        Map<ExactCompound, Integer> pending = new HashMap<>();
        long size = rawSize;
        long heap = heapSize;
        for (int i = 0; i < local.size(); i++) {
            CompoundTag section = local.getCompound(i);
            ExactCompound key = new ExactCompound(section);
            Integer id = sectionIds.get(key);
            if (id == null) id = pending.get(key);
            if (id == null) {
                Observation observation = recent.get(key);
                long bytes = observation == null ? NbtSize.payload(section, 2, ChunkCodec.MAX_BYTES) : observation.raw();
                long entryHeap = observation == null ? -1 : observation.heap();
                if (observation == null && bytes <= MAX_RECENT_ENTRY) {
                    try {
                        entryHeap = NbtHeapSize.listEntry(section, ChunkCodec.MAX_BYTES);
                    } catch (NbtAccounterException capacity) {
                        // 高内存展开的区段仍可用标准格式保存。
                    }
                }
                if (observation != null && entryHeap >= 0 && sections.size() + added.size() < MAX_SECTIONS) {
                    boolean first = !sectionFormat && added.isEmpty();
                    long nextSize = size + bytes + (first ? sectionHeaderRaw : 0);
                    long nextHeap = heap + entryHeap + (first ? sectionHeaderHeap : 0);
                    if (nextSize <= MAX_V2_RAW_BYTES && nextHeap <= ChunkCodec.MAX_BYTES) {
                        id = sections.size() + added.size();
                        added.add(section);
                        pending.put(key, id);
                        size = nextSize;
                        heap = nextHeap;
                    }
                }
                if (observation == null) observe(section, bytes, entryHeap);
            }
            if (id != null) mapping[i] = id;
        }
        return new SectionAddition(mapping, added, size, heap);
    }

    private void observe(CompoundTag section, long bytes, long heap) {
        if (bytes > MAX_RECENT_ENTRY || heap < 0 || heap + heapSize > ChunkCodec.MAX_BYTES) return;
        while (!recent.isEmpty() && (recent.size() >= MAX_RECENT || recentRaw + bytes > MAX_RECENT_BYTES
                || recentHeap + heap + heapSize > ChunkCodec.MAX_BYTES)) removeOldest();
        // 所有 raw、深度和内存计额都已完成，缓存与调用方对象保持独立。
        recent.put(new ExactCompound(section.copy()), new Observation(bytes, heap));
        recentRaw += bytes;
        recentHeap += heap;
    }

    private void removeOldest() {
        var iterator = recent.entrySet().iterator();
        Observation removed = iterator.next().getValue();
        iterator.remove();
        recentRaw -= removed.raw();
        recentHeap -= removed.heap();
    }

    private void trimRecent() {
        while (!recent.isEmpty() && recentHeap + heapSize > ChunkCodec.MAX_BYTES) removeOldest();
    }

    synchronized void appendSections(SectionAddition addition) {
        for (CompoundTag entry : addition.added) {
            Observation removed = recent.remove(new ExactCompound(entry));
            if (removed != null) {
                recentRaw -= removed.raw();
                recentHeap -= removed.heap();
            }
        }
        while (!recent.isEmpty() && recentHeap + addition.heapSize > ChunkCodec.MAX_BYTES) removeOldest();
        for (CompoundTag entry : addition.added) {
            CompoundTag copy = entry.copy();
            sectionIds.put(new ExactCompound(copy), sections.size());
            sections.add(copy);
            sectionSizes.add(RestoredSize.measure(copy, 2));
        }
        rawSize = addition.rawSize;
        heapSize = addition.heapSize;
        if (!addition.added.isEmpty()) sectionFormat = true;
        trimRecent();
    }

    synchronized long stateSize(int id) throws IOException {
        checkId(id);
        return stateSizes.get(id).value();
    }

    synchronized CompoundTag copyState(int id) throws IOException {
        checkId(id);
        return states.getCompound(id).copy();
    }

    synchronized long sectionSize(int id) throws IOException {
        checkSectionId(id);
        return sectionSizes.get(id).value();
    }

    synchronized CompoundTag copySection(int id) throws IOException {
        checkSectionId(id);
        return sections.getCompound(id).copy();
    }

    private void checkId(int id) throws IOException {
        if (id < 0 || id >= states.size()) throw new IOException("Region dictionary index out of range");
    }

    private void checkSectionId(int id) throws IOException {
        if (id < 0 || id >= sections.size()) throw new IOException("Region section dictionary index out of range");
    }
}
