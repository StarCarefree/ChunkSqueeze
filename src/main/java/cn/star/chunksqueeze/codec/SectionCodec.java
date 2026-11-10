package cn.star.chunksqueeze.codec;

import net.minecraft.nbt.*;

import java.io.*;
import java.util.*;
import java.util.zip.CRC32;

public final class SectionCodec {
    public static final String KEY = "chunksqueeze:sections";
    private static final int MAGIC = 0x43535331;
    private static final int MAX_SECTIONS = 1024;
    private static final ChunkCodec.Options ALWAYS = new ChunkCodec.Options(0, false);

    private SectionCodec() {}

    public record Encoded(ChunkCodec.Result result, byte[] payload) {}

    public static ChunkCodec.Result encode(CompoundTag original, ChunkCodec.Options options,
                                           RegionStateDictionary dictionary) throws IOException {
        return encodeInternal(original, options, dictionary, false).result();
    }

    public static Encoded encodeForStorage(CompoundTag original, ChunkCodec.Options options,
                                          RegionStateDictionary dictionary) throws IOException {
        return encodeInternal(original, options, dictionary, true);
    }

    private static Encoded encodeInternal(CompoundTag original, ChunkCodec.Options options,
                                          RegionStateDictionary dictionary, boolean requirePayload) throws IOException {
        Objects.requireNonNull(dictionary, "dictionary");
        if (original.contains(KEY) || original.contains(ChunkCodec.KEY)) {
            throw new IOException("Already encoded or conflicting storage marker");
        }
        synchronized (dictionary) {
            try (EncodeContext context = new EncodeContext()) {
                ChunkCodec.Plan plan = ChunkCodec.analyze(original, context);
                byte[] originalPayload = requirePayload || plan != null ? context.compress(original) : null;
                ChunkCodec.Encoded baseline = ChunkCodec.select(original, options, 512, 0, dictionary, true,
                        plan, context, originalPayload);
                if (plan == null) return finish(baseline, original);
                ListTag sections = original.getList("sections", Tag.TAG_COMPOUND);
                RegionStateDictionary.SectionAddition addition = dictionary.prepareSections(sections);
                int count = 0;
                for (int id : addition.mapping) if (id >= 0) count++;
                if (count == 0) return finish(baseline, original);
                CompoundTag candidate = envelope(original, dictionary, addition.mapping, count);
                // 只引用已有状态，避免残余区段编码改变 section 计划的容量预算。
                ChunkCodec.Encoded selected = ChunkCodec.select(candidate, ALWAYS, 512, 0, dictionary, false,
                        ChunkCodec.remaining(plan, addition.mapping, candidate, context), context, context.compress(candidate));
                int originalSize = originalPayload.length;
                int baselineSize = baseline.payload().length;
                int candidateSize = selected.payload().length;
                if (candidateSize >= baselineSize || originalSize - candidateSize < options.minimumSaving()
                        || (options.requireSectorSaving() && ChunkCodec.allocatedBytes(candidateSize, 512, 0)
                        >= ChunkCodec.allocatedBytes(originalSize, 512, 0))) return finish(baseline, original);
                dictionary.appendSections(addition);
                return finish(new ChunkCodec.Encoded(new ChunkCodec.Result(selected.result().tag(), originalSize,
                        candidateSize, "shared-sections"), selected.payload()), original);
            }
        }
    }

    private static Encoded finish(ChunkCodec.Encoded encoded, CompoundTag original) {
        ChunkCodec.Encoded independent = ChunkCodec.independent(encoded, original);
        return new Encoded(independent.result(), independent.payload());
    }

    private static CompoundTag envelope(CompoundTag original, RegionStateDictionary dictionary,
                                        int[] mapping, int count) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        out.writeInt(MAGIC);
        out.writeLong(dictionary.identity().getMostSignificantBits());
        out.writeLong(dictionary.identity().getLeastSignificantBits());
        IndexCodec.writeVarInt(out, count);
        for (int i = 0; i < mapping.length; i++) {
            if (mapping[i] < 0) continue;
            IndexCodec.writeVarInt(out, i);
            IndexCodec.writeVarInt(out, mapping[i]);
        }
        byte[] payload = bytes.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(payload);
        CompoundTag marker = new CompoundTag();
        marker.putInt("version", 1);
        marker.putLong("crc32", crc.getValue());
        marker.putByteArray("payload", payload);
        CompoundTag candidate = ChunkCodec.shell(original);
        ListTag sections = new ListTag();
        ListTag source = original.getList("sections", Tag.TAG_COMPOUND);
        for (int i = 0; i < mapping.length; i++) sections.add(mapping[i] >= 0 ? new CompoundTag() : source.get(i));
        candidate.put("sections", sections);
        candidate.put(KEY, marker);
        return candidate;
    }

    public static CompoundTag decode(CompoundTag stored, RegionStateDictionary dictionary) throws IOException {
        if (!stored.contains(KEY)) return ChunkCodec.decode(stored, dictionary);
        if (dictionary == null) throw new IOException("Region dictionary required for section references");
        synchronized (dictionary) {
            if (!stored.contains("Status", Tag.TAG_STRING) || stored.getString("Status").isEmpty()) {
                throw new IOException("Missing or empty encoded chunk Status");
            }
            if (!stored.contains(KEY, Tag.TAG_COMPOUND)) throw new IOException("Invalid section marker");
            CompoundTag marker = stored.getCompound(KEY);
            if (!marker.contains("version", Tag.TAG_INT) || marker.getInt("version") != 1
                    || !marker.getAllKeys().equals(Set.of("version", "crc32", "payload"))) {
                throw new IOException("Unsupported section envelope");
            }
            byte[] payload = checkedPayload(marker);
            var in = new DataInputStream(new ByteArrayInputStream(payload));
            if (in.readInt() != MAGIC) throw new IOException("Invalid section payload magic");
            UUID identity = new UUID(in.readLong(), in.readLong());
            if (!identity.equals(dictionary.identity())) throw new IOException("Region dictionary UUID mismatch");
            if (!stored.contains("sections", Tag.TAG_LIST)) throw new IOException("Missing chunk sections");
            ListTag sections = (ListTag) stored.get("sections");
            if (sections.isEmpty() || sections.size() > MAX_SECTIONS || sections.getElementType() != Tag.TAG_COMPOUND) {
                throw new IOException("Invalid section count");
            }
            int count = IndexCodec.bounded(in, sections.size(), "section references");
            if (count == 0) throw new IOException("Empty section references");
            int[] indices = new int[count];
            int[] ids = new int[count];
            BitSet seen = new BitSet();
            CompoundTag stateStored = new CompoundTag();
            for (String key : stored.getAllKeys()) {
                if (!KEY.equals(key)) stateStored.put(key, stored.get(key));
            }
            long expanded = 0;
            for (int i = 0; i < count; i++) {
                int index = IndexCodec.bounded(in, sections.size() - 1, "section index");
                if (seen.get(index)) throw new IOException("Duplicate section reference");
                seen.set(index);
                if (!sections.getCompound(index).isEmpty()) throw new IOException("Nonempty section placeholder");
                int id = IndexCodec.bounded(in, dictionary.sectionCount() - 1, "region section index");
                expanded += dictionary.sectionSize(id) - 1;
                if (expanded > ChunkCodec.MAX_BYTES) throw new IOException("Restored chunk exceeds limit");
                indices[i] = index;
                ids[i] = id;
            }
            if (in.available() != 0) throw new IOException("Trailing section payload data");
            // 同一次状态解析合并区段展开计额，全部验证完成后才复制。
            CompoundTag restored;
            if (stateStored.contains(ChunkCodec.KEY)) {
                restored = ChunkCodec.decode(stateStored, dictionary, expanded, seen);
            } else {
                if (NbtSize.rootWithout(stateStored, null, ChunkCodec.MAX_BYTES) + expanded > ChunkCodec.MAX_BYTES) {
                    throw new IOException("Restored chunk exceeds limit");
                }
                restored = stateStored.copy();
            }
            ListTag target = restored.getList("sections", Tag.TAG_COMPOUND);
            for (int i = 0; i < count; i++) target.set(indices[i], dictionary.copySection(ids[i]));
            return restored;
        }
    }

    private static byte[] checkedPayload(CompoundTag marker) throws IOException {
        if (!marker.contains("crc32", Tag.TAG_LONG) || !marker.contains("payload", Tag.TAG_BYTE_ARRAY)) {
            throw new IOException("Incomplete codec envelope");
        }
        byte[] payload = marker.getByteArray("payload");
        if (payload.length > ChunkCodec.MAX_BYTES) throw new IOException("Codec payload exceeds limit");
        CRC32 crc = new CRC32();
        crc.update(payload);
        if (crc.getValue() != marker.getLong("crc32")) throw new IOException("Codec checksum mismatch");
        return payload;
    }

}
