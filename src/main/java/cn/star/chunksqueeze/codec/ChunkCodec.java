package cn.star.chunksqueeze.codec;

import net.minecraft.nbt.*;

import java.io.*;
import java.util.*;
import java.util.zip.CRC32;

public final class ChunkCodec {
    public static final String KEY = "chunksqueeze:storage";
    public static final int VERSION = 1;
    public static final int MAX_BYTES = 64 * 1024 * 1024;
    private static final int MAX_SECTIONS = 1024;
    private static final int MAX_STATES = 65536;
    private static final int MAGIC = 0x43535131;
    private static final int SHARED_VERSION = 2;
    private static final int SHARED_MAGIC = 0x43535132;

    private ChunkCodec() {}

    public record Options(int minimumSaving, boolean requireSectorSaving) {
        public Options {
            if (minimumSaving < 0) throw new IllegalArgumentException("Negative minimum saving");
        }
    }
    public record Result(CompoundTag tag, int originalBytes, int storedBytes, String encoding) {
        public boolean optimized() { return tag.contains(KEY) || tag.contains(SectionCodec.KEY); }
    }
    private record Section(int index, int[] palette, byte[] raw, byte[] adaptive) {}
    private record RestoredSection(int index, int[] palette, long[] data) {}

    public static Result encode(CompoundTag original, Options options) throws IOException {
        return encode(original, options, 4096, 5);
    }

    public static Result encode(CompoundTag original, Options options, int allocationUnit, int recordOverhead) throws IOException {
        return encodeInternal(original, options, allocationUnit, recordOverhead, null, true);
    }

    public static Result encode(CompoundTag original, Options options, int allocationUnit, int recordOverhead,
                                RegionStateDictionary dictionary) throws IOException {
        Objects.requireNonNull(dictionary, "dictionary");
        synchronized (dictionary) {
            return encodeInternal(original, options, allocationUnit, recordOverhead, dictionary, true);
        }
    }

    static Result encodeExistingStates(CompoundTag original, Options options,
                                       RegionStateDictionary dictionary) throws IOException {
        Objects.requireNonNull(dictionary, "dictionary");
        synchronized (dictionary) {
            return encodeInternal(original, options, 512, 0, dictionary, false);
        }
    }

    static final class Plan {
        final ListTag dictionary;
        final byte[] dictionaryBytes;
        final List<Section> records;

        Plan(ListTag dictionary, byte[] dictionaryBytes, List<Section> records) {
            this.dictionary = dictionary;
            this.dictionaryBytes = dictionaryBytes;
            this.records = records;
        }
    }

    record Encoded(Result result, byte[] payload) {}

    private static Result encodeInternal(CompoundTag original, Options options, int allocationUnit, int recordOverhead,
                                         RegionStateDictionary region, boolean allowNewStates) throws IOException {
        if (allocationUnit < 1 || recordOverhead < 0) throw new IllegalArgumentException("Invalid allocation layout");
        if (original.contains(KEY)) throw new IOException("Already encoded or conflicting storage marker");
        try (EncodeContext context = new EncodeContext()) {
            Plan plan = analyze(original, context);
            Encoded encoded = select(original, options, allocationUnit, recordOverhead, region, allowNewStates,
                    plan, context, null);
            return independent(encoded, original).result();
        }
    }

    static Plan analyze(CompoundTag original, EncodeContext context) throws IOException {
        if (!original.contains("sections", Tag.TAG_LIST) || !original.contains("Status", Tag.TAG_STRING)
                || original.getString("Status").isEmpty()) {
            return null;
        }
        ListTag sections = original.getList("sections", Tag.TAG_COMPOUND);
        if (sections.isEmpty() || sections.size() > MAX_SECTIONS) return null;
        try {
            // 在复制任意状态或元数据前验证完整输入的大小、深度和字符串。
            NbtSize.rootWithout(original, null, MAX_BYTES);
        } catch (IOException unsupported) {
            return null;
        }
        ListTag dictionary = new ListTag();
        Map<RegionStateDictionary.ExactCompound, Integer> ids = new HashMap<>();
        List<Section> records = new ArrayList<>();
        for (int i = 0; i < sections.size(); i++) {
            CompoundTag section = sections.getCompound(i);
            if (!section.contains("block_states", Tag.TAG_COMPOUND)) continue;
            CompoundTag states = section.getCompound("block_states");
            // 未知扩展字段原样留在区段中，避免破坏其他模组的数据。
            if (!Set.of("palette", "data").containsAll(states.getAllKeys())) continue;
            if (!states.contains("palette", Tag.TAG_LIST)) continue;
            ListTag palette = states.getList("palette", Tag.TAG_COMPOUND);
            if (palette.isEmpty() || palette.size() > IndexCodec.BLOCKS) continue;
            if (states.contains("data") && !states.contains("data", Tag.TAG_LONG_ARRAY)) continue;
            if (!states.contains("data") && palette.size() != 1) continue;
            byte[] raw = new byte[]{0};
            byte[] adaptive = raw;
            if (states.contains("data")) {
                try {
                    IndexCodec.Analysis analysis = context.indices(states.getLongArray("data"), palette.size());
                    raw = analysis.raw();
                    adaptive = analysis.adaptive();
                } catch (IOException unsupported) {
                    continue;
                }
            }
            int[] local = new int[palette.size()];
            for (int p = 0; p < palette.size(); p++) {
                CompoundTag state = palette.getCompound(p);
                RegionStateDictionary.ExactCompound key = new RegionStateDictionary.ExactCompound(state);
                Integer id = ids.get(key);
                if (id == null) {
                    if (dictionary.size() >= MAX_STATES) return new Plan(null, null, List.of());
                    id = dictionary.size();
                    dictionary.add(state);
                    ids.put(key, id);
                }
                local[p] = id;
            }
            records.add(new Section(i, local, raw, adaptive));
        }
        return plan(dictionary, records);
    }

    private static Plan plan(ListTag dictionary, List<Section> records) throws IOException {
        if (records.isEmpty()) return new Plan(dictionary, null, records);
        CompoundTag dictionaryTag = new CompoundTag();
        dictionaryTag.put("states", dictionary);
        try {
            // 保留读取端的内存计额，直接测量，避免反序列化出临时字典。
            NbtHeapSize.root(dictionaryTag, MAX_BYTES);
        } catch (NbtAccounterException unsupported) {
            return new Plan(dictionary, null, records);
        }
        return new Plan(dictionary, serialize(dictionaryTag), records);
    }

    static Plan remaining(Plan original, int[] referenced, CompoundTag candidate, EncodeContext context) throws IOException {
        if (original.dictionary == null) return analyze(candidate, context);
        try {
            NbtSize.rootWithout(candidate, null, MAX_BYTES);
        } catch (IOException unsupported) {
            return null;
        }
        ListTag dictionary = new ListTag();
        List<Section> records = new ArrayList<>();
        int[] mapping = new int[original.dictionary.size()];
        Arrays.fill(mapping, -1);
        for (Section section : original.records) {
            if (referenced[section.index()] >= 0) continue;
            int[] palette = new int[section.palette().length];
            for (int p = 0; p < palette.length; p++) {
                int id = section.palette()[p];
                if (mapping[id] < 0) {
                    mapping[id] = dictionary.size();
                    dictionary.add(original.dictionary.getCompound(id));
                }
                palette[p] = mapping[id];
            }
            records.add(new Section(section.index(), palette, section.raw(), section.adaptive()));
        }
        return plan(dictionary, records);
    }

    static Encoded select(CompoundTag original, Options options, int allocationUnit, int recordOverhead,
                          RegionStateDictionary region, boolean allowNewStates, Plan plan,
                          EncodeContext context, byte[] originalPayload) throws IOException {
        if (plan == null || plan.records.isEmpty() || plan.dictionaryBytes == null) {
            return new Encoded(new Result(original, 0, 0, "unchanged"), originalPayload);
        }
        if (originalPayload == null) originalPayload = context.compress(original);
        int baseSize = originalPayload.length;
        CompoundTag best = original;
        byte[] bestPayload = originalPayload;
        String bestEncoding = "vanilla";
        RegionStateDictionary.Addition addition = region == null ? null
                : allowNewStates ? region.prepare(plan.dictionary) : region.prepareExisting(plan.dictionary);
        CompoundTag base = stripped(original, plan.records);
        boolean adaptiveDiffers = plan.records.stream().anyMatch(record -> record.raw() != record.adaptive());
        CompoundTag selected = null;
        String selectedEncoding = "vanilla";
        int selectedScore = Integer.MAX_VALUE;
        // 只对变化的编码载荷快速评分，获选后才序列化、压缩完整区块。
        for (boolean shared : new boolean[]{false, true}) {
            if (shared && addition == null) continue;
            for (boolean adaptive : new boolean[]{false, true}) {
                if (adaptive && !adaptiveDiffers) continue;
                CompoundTag candidate = envelope(base, plan.dictionaryBytes, plan.records, adaptive,
                        shared ? region : null, shared ? addition.mapping : null);
                int score = context.compressedSize(candidate.getCompound(KEY).getByteArray("payload"),
                        java.util.zip.Deflater.BEST_SPEED);
                if (score < selectedScore) {
                    selected = candidate;
                    selectedScore = score;
                    selectedEncoding = shared ? (adaptive ? "shared-adaptive" : "shared-dictionary")
                            : (adaptive ? "adaptive" : "dictionary");
                }
            }
        }
        if (selected != null) {
            byte[] payload = context.compress(selected);
            if (payload.length < bestPayload.length) {
                best = selected;
                bestPayload = payload;
                bestEncoding = selectedEncoding;
            }
        }
        if (baseSize - bestPayload.length < options.minimumSaving()
                || (options.requireSectorSaving()
                && allocatedBytes(bestPayload.length, allocationUnit, recordOverhead) >= allocatedBytes(baseSize, allocationUnit, recordOverhead))) {
            return new Encoded(new Result(original, baseSize, baseSize, "vanilla"), originalPayload);
        }
        if (allowNewStates && best != original && best.getCompound(KEY).getInt("version") == SHARED_VERSION) region.append(addition);
        return new Encoded(new Result(best, baseSize, bestPayload.length, bestEncoding), bestPayload);
    }

    static Encoded independent(Encoded encoded, CompoundTag original) {
        Result result = encoded.result();
        return result.tag() == original ? encoded : new Encoded(new Result(result.tag().copy(),
                result.originalBytes(), result.storedBytes(), result.encoding()), encoded.payload());
    }

    // 候选仅在本次调用内读取；复制会被修改的容器壳，获选后统一深复制。
    static CompoundTag shell(CompoundTag original) {
        CompoundTag shell = new CompoundTag();
        for (String key : original.getAllKeys()) shell.put(key, original.get(key));
        return shell;
    }

    private static CompoundTag envelope(CompoundTag original, byte[] dictionaryBytes, List<Section> records,
                                        boolean adaptive, RegionStateDictionary region, int[] mapping) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        out.writeInt(region == null ? MAGIC : SHARED_MAGIC);
        if (region == null) {
            out.write(dictionaryBytes);
        } else {
            out.writeLong(region.identity().getMostSignificantBits());
            out.writeLong(region.identity().getLeastSignificantBits());
            IndexCodec.writeVarInt(out, mapping.length);
            for (int id : mapping) IndexCodec.writeVarInt(out, id);
        }
        IndexCodec.writeVarInt(out, records.size());
        for (Section record : records) {
            IndexCodec.writeVarInt(out, record.index());
            IndexCodec.writeVarInt(out, record.palette().length);
            for (int id : record.palette()) IndexCodec.writeVarInt(out, id);
            byte[] data = adaptive ? record.adaptive() : record.raw();
            IndexCodec.writeVarInt(out, data.length);
            out.write(data);
        }
        byte[] payload = bytes.toByteArray();
        if (payload.length > MAX_BYTES) throw new IOException("Encoded payload exceeds limit");
        CRC32 crc = new CRC32();
        crc.update(payload);
        CompoundTag marker = new CompoundTag();
        marker.putInt("version", region == null ? VERSION : SHARED_VERSION);
        marker.putLong("crc32", crc.getValue());
        marker.putByteArray("payload", payload);
        CompoundTag copy = shell(original);
        copy.put(KEY, marker);
        return copy;
    }

    private static CompoundTag stripped(CompoundTag original, List<Section> records) {
        CompoundTag copy = shell(original);
        ListTag sections = new ListTag();
        for (Tag section : original.getList("sections", Tag.TAG_COMPOUND)) sections.add(section);
        for (Section record : records) {
            CompoundTag section = shell(sections.getCompound(record.index()));
            section.remove("block_states");
            sections.set(record.index(), section);
        }
        copy.put("sections", sections);
        return copy;
    }

    public static CompoundTag decode(CompoundTag stored) throws IOException {
        return decode(stored, null);
    }

    public static CompoundTag decode(CompoundTag stored, RegionStateDictionary region) throws IOException {
        return decode(stored, region, 0, null);
    }

    static CompoundTag decode(CompoundTag stored, RegionStateDictionary region, long additionalBytes,
                              BitSet referencedSections) throws IOException {
        if (additionalBytes < 0 || additionalBytes > MAX_BYTES) throw new IOException("Invalid additional NBT size");
        if (!stored.contains(KEY)) return stored;
        if (!stored.contains("Status", Tag.TAG_STRING) || stored.getString("Status").isEmpty()) {
            throw new IOException("Missing or empty encoded chunk Status");
        }
        if (!stored.contains(KEY, Tag.TAG_COMPOUND)) throw new IOException("Invalid ChunkSqueeze marker");
        CompoundTag marker = stored.getCompound(KEY);
        if (!marker.contains("version", Tag.TAG_INT)) throw new IOException("Unsupported ChunkSqueeze format version");
        int version = marker.getInt("version");
        if (version != VERSION && version != SHARED_VERSION) throw new IOException("Unsupported ChunkSqueeze format version");
        if (version == SHARED_VERSION && region == null) throw new IOException("Region dictionary required for ChunkSqueeze v2");
        if (!marker.contains("payload", Tag.TAG_BYTE_ARRAY) || !marker.contains("crc32", Tag.TAG_LONG)) {
            throw new IOException("Incomplete ChunkSqueeze envelope");
        }
        byte[] payload = marker.getByteArray("payload");
        if (payload.length > MAX_BYTES) throw new IOException("ChunkSqueeze payload exceeds limit");
        CRC32 crc = new CRC32();
        crc.update(payload);
        if (crc.getValue() != marker.getLong("crc32")) throw new IOException("ChunkSqueeze checksum mismatch");
        var in = new DataInputStream(new ByteArrayInputStream(payload));
        if (in.readInt() != (version == VERSION ? MAGIC : SHARED_MAGIC)) throw new IOException("Invalid ChunkSqueeze payload magic");
        ListTag dictionary;
        int[] mapping;
        if (version == VERSION) {
            CompoundTag dictionaryTag = NbtIo.read(in, NbtAccounter.create(MAX_BYTES));
            if (!dictionaryTag.contains("states", Tag.TAG_LIST)) throw new IOException("Missing state dictionary");
            dictionary = dictionaryTag.getList("states", Tag.TAG_COMPOUND);
            mapping = null;
        } else {
            UUID identity = new UUID(in.readLong(), in.readLong());
            if (!identity.equals(region.identity())) throw new IOException("Region dictionary UUID mismatch");
            int size = IndexCodec.bounded(in, MAX_STATES, "dictionary size");
            if (size == 0) throw new IOException("Invalid dictionary size");
            mapping = new int[size];
            int bound = region.size() - 1;
            for (int i = 0; i < size; i++) mapping[i] = IndexCodec.bounded(in, bound, "region dictionary index");
            dictionary = null;
        }
        int dictionarySize = mapping == null ? dictionary.size() : mapping.length;
        if (dictionarySize == 0 || dictionarySize > MAX_STATES) throw new IOException("Invalid dictionary size");
        if (!stored.contains("sections", Tag.TAG_LIST)) throw new IOException("Missing chunk sections");
        long expandedBytes = NbtSize.rootWithout(stored, KEY, MAX_BYTES) + additionalBytes;
        if (expandedBytes > MAX_BYTES) throw new IOException("Restored chunk exceeds limit");
        long[] stateSizes = new long[dictionarySize];
        // 还原后的路径为根、sections、区段、block_states、palette，再进入状态 compound。
        for (int i = 0; i < stateSizes.length; i++) {
            stateSizes[i] = mapping == null ? NbtSize.payload(dictionary.getCompound(i), 5, MAX_BYTES)
                    : region.stateSize(mapping[i]);
        }
        ListTag storedSections = stored.getList("sections", Tag.TAG_COMPOUND);
        if (storedSections.isEmpty() || storedSections.size() > MAX_SECTIONS) throw new IOException("Invalid section count");
        int count = IndexCodec.bounded(in, storedSections.size(), "section records");
        if (count == 0) throw new IOException("Empty section records");
        List<RestoredSection> records = new ArrayList<>();
        BitSet seen = new BitSet();
        for (int i = 0; i < count; i++) {
            int index = IndexCodec.bounded(in, storedSections.size() - 1, "section index");
            if (seen.get(index)) throw new IOException("Duplicate section record");
            if (referencedSections != null && referencedSections.get(index)) {
                throw new IOException("Conflicting state and section references");
            }
            seen.set(index);
            if (storedSections.getCompound(index).contains("block_states")) throw new IOException("Conflicting block state data");
            int size = IndexCodec.bounded(in, IndexCodec.BLOCKS, "palette size");
            if (size == 0) throw new IOException("Empty palette");
            int[] ids = new int[size];
            // 所有展开计额、深度和格式检查完成后才开始深复制。
            expandedBytes += 31; // block_states 标签、palette 列表头与 compound 结束符。
            for (int p = 0; p < size; p++) {
                ids[p] = IndexCodec.bounded(in, dictionarySize - 1, "dictionary index");
                expandedBytes += stateSizes[ids[p]];
                if (expandedBytes > MAX_BYTES) throw new IOException("Restored chunk exceeds limit");
            }
            int length = IndexCodec.bounded(in, 64 * 1024, "encoded indices length");
            if (length == 0 || length > in.available()) throw new IOException("Invalid encoded indices length");
            byte[] indices = in.readNBytes(length);
            long[] data = null;
            if (indices[0] == 0) {
                if (indices.length != 1 || size != 1) throw new IOException("Invalid uniform section");
            } else {
                data = IndexCodec.decode(indices, size);
                expandedBytes += 11L + data.length * 8L;
                if (expandedBytes > MAX_BYTES) throw new IOException("Restored chunk exceeds limit");
            }
            records.add(new RestoredSection(index, ids, data));
        }
        if (in.available() != 0) throw new IOException("Trailing ChunkSqueeze payload data");
        CompoundTag restored = new CompoundTag();
        for (String key : stored.getAllKeys()) {
            if (!KEY.equals(key)) restored.put(key, stored.get(key).copy());
        }
        ListTag sections = restored.getList("sections", Tag.TAG_COMPOUND);
        for (RestoredSection record : records) {
            CompoundTag states = new CompoundTag();
            if (record.data() != null) states.putLongArray("data", record.data());
            ListTag palette = new ListTag();
            for (int id : record.palette()) palette.add(mapping == null ? dictionary.getCompound(id).copy() : region.copyState(mapping[id]));
            states.put("palette", palette);
            sections.getCompound(record.index()).put("block_states", states);
        }
        return restored;
    }

    public static byte[] serialize(CompoundTag tag) throws IOException {
        var bytes = new ByteArrayOutputStream();
        NbtIo.write(tag, new DataOutputStream(bytes));
        return bytes.toByteArray();
    }

    public static long allocatedBytes(int compressedBytes, int unit, int overhead) {
        return ((long) compressedBytes + overhead + unit - 1) / unit * unit;
    }

    public static int sectors(int compressedBytes) { return (compressedBytes + 5 + 4095) / 4096; }
}
