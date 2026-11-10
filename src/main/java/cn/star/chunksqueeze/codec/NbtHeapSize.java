package cn.star.chunksqueeze.codec;

import cn.star.chunksqueeze.compat.NbtAccounter;
import cn.star.chunksqueeze.compat.NbtAccounterException;
import net.minecraft.nbt.*;

import java.io.*;

final class NbtHeapSize {
    private static final boolean EXACT_ACCOUNTING_SUPPORTED = checkRuntime();

    private static final class UnsupportedTagException extends IOException {}

    private NbtHeapSize() {}

    static boolean exactAccountingSupported() {
        return EXACT_ACCOUNTING_SUPPORTED;
    }

    // 对齐 Forge 1.20.1 的 TagType.load；parse 和 sizeInBytes 的字符串计额不同。
    static long root(CompoundTag root, long limit) throws IOException {
        try {
            long usage = exactRoot(root, EXACT_ACCOUNTING_SUPPORTED ? limit : Long.MAX_VALUE);
            return EXACT_ACCOUNTING_SUPPORTED ? usage : measuredRoot(root, limit);
        } catch (UnsupportedTagException custom) {
            return measuredRoot(root, limit);
        }
    }

    private static long exactRoot(CompoundTag root, long limit) throws IOException {
        NbtAccounter accounter = NbtAccounter.create(limit);
        accounter.accountBytes(7); // 根类型、空名称与分配开销。
        account(root, accounter);
        return accounter.getUsage();
    }

    static long payload(Tag tag, int parentDepth, long limit) throws IOException {
        if (parentDepth < 0 || parentDepth > 512) throw new IOException("Invalid NBT parent depth");
        NbtAccounter accounter = new NbtAccounter(EXACT_ACCOUNTING_SUPPORTED ? limit : Long.MAX_VALUE, 512 - parentDepth);
        try {
            account(tag, accounter);
            if (EXACT_ACCOUNTING_SUPPORTED) return accounter.getUsage();
        } catch (UnsupportedTagException custom) {
            // 自定义 write 必须按实际写出的标准 NBT 重新计额，不能依据其父类估算。
        }
        return measuredPayload(tag, parentDepth, limit);
    }

    private static long measuredPayload(Tag tag, int parentDepth, long limit) throws IOException {
        validateDepth(tag, new NbtAccounter(Long.MAX_VALUE, 512 - parentDepth));
        var bytes = new ByteArrayOutputStream();
        tag.write(new DataOutputStream(bytes));
        if (bytes.size() != NbtSize.payload(tag, parentDepth, ChunkCodec.MAX_BYTES)) {
            throw new IOException("Custom NBT serialization does not match its tag structure");
        }
        NbtAccounter accounter = NbtAccounter.create(limit);
        var in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        Tag decoded;
        try {
            decoded = TagTypes.getType(tag.getId()).load(in, parentDepth, accounter);
        } catch (RuntimeException failure) {
            throw NbtAccounterException.normalize(failure);
        }
        if (in.available() != 0 || !sameStructure(decoded, tag)) {
            throw new IOException("Custom NBT serialization does not match its tag structure");
        }
        return accounter.getUsage();
    }

    static long listEntry(CompoundTag tag, long limit) throws IOException {
        if (EXACT_ACCOUNTING_SUPPORTED) return 4 + payload(tag, 2, limit);
        ListTag list = new ListTag();
        list.add(tag);
        return payload(list, 1, limit) - payload(new ListTag(), 1, limit);
    }

    private static long measuredRoot(CompoundTag root, long limit) throws IOException {
        validateDepth(root, NbtAccounter.unlimitedHeap());
        var bytes = new ByteArrayOutputStream();
        NbtIo.write(root, new DataOutputStream(bytes));
        if (bytes.size() != NbtSize.rootWithout(root, null, Long.MAX_VALUE)) {
            throw new IOException("Custom NBT serialization does not match its tag structure");
        }
        NbtAccounter accounter = NbtAccounter.create(limit);
        var in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        CompoundTag decoded;
        try {
            decoded = NbtIo.read(in, accounter);
        } catch (RuntimeException failure) {
            throw NbtAccounterException.normalize(failure);
        }
        if (in.available() != 0 || !sameStructure(decoded, root)) {
            throw new IOException("Custom NBT serialization does not match its tag structure");
        }
        return accounter.getUsage();
    }

    // 原生 load 从 0 起算且只拒绝 depth > 512；直接计额仍最多允许 512 个容器。
    private static void validateDepth(Tag tag, NbtAccounter accounter) {
        if (!(tag instanceof CompoundTag) && !(tag instanceof ListTag)) return;
        accounter.pushDepth();
        try {
            if (tag instanceof CompoundTag compound) {
                for (String key : compound.getAllKeys()) validateDepth(compound.get(key), accounter);
            } else if (tag instanceof ListTag list) {
                for (int i = 0; i < list.size(); i++) validateDepth(list.get(i), accounter);
            }
        } finally {
            accounter.popDepth();
        }
    }

    private static boolean sameStructure(Tag decoded, Tag original) {
        if (decoded.getId() != original.getId()) return false;
        if (decoded instanceof FloatTag value) {
            return original instanceof FloatTag other
                    && Float.floatToIntBits(value.getAsFloat()) == Float.floatToIntBits(other.getAsFloat());
        }
        if (decoded instanceof DoubleTag value) {
            return original instanceof DoubleTag other
                    && Double.doubleToLongBits(value.getAsDouble()) == Double.doubleToLongBits(other.getAsDouble());
        }
        if (decoded instanceof CompoundTag compound) {
            if (!(original instanceof CompoundTag other) || !compound.getAllKeys().equals(other.getAllKeys())) return false;
            for (String key : compound.getAllKeys()) {
                if (!sameStructure(compound.get(key), other.get(key))) return false;
            }
            return true;
        }
        if (decoded instanceof ListTag list) {
            if (!(original instanceof ListTag other) || list.size() != other.size()) return false;
            for (int i = 0; i < list.size(); i++) {
                if (!sameStructure(list.get(i), other.get(i))) return false;
            }
            return true;
        }
        return decoded.equals(original);
    }

    // 启动时逐类型核对运行中的 MC；计额变化时保留原解析保障。
    private static boolean checkRuntime() {
        Tag[] values = {
                ByteTag.valueOf((byte) 1), ShortTag.valueOf((short) 2), IntTag.valueOf(3), LongTag.valueOf(4),
                FloatTag.valueOf(-0.0f), DoubleTag.valueOf(Double.NaN),
                new ByteArrayTag(new byte[3]), new IntArrayTag(new int[3]), new LongArrayTag(new long[3]),
                StringTag.valueOf(""), StringTag.valueOf("a".repeat(128)),
                StringTag.valueOf("\u0000\u007f\u0080߿ࠀ🌍"), new ListTag(), new CompoundTag()
        };
        try {
            CompoundTag empty = new CompoundTag();
            if (exactRoot(empty, Long.MAX_VALUE) != measuredRoot(empty, Long.MAX_VALUE)) return false;
            NbtAccounter end = NbtAccounter.unlimitedHeap();
            account(EndTag.INSTANCE, end);
            if (end.getUsage() != measuredPayload(EndTag.INSTANCE, 0, Long.MAX_VALUE)) return false;
            for (Tag value : values) {
                NbtAccounter payload = NbtAccounter.unlimitedHeap();
                account(value, payload);
                if (payload.getUsage() != measuredPayload(value, 0, Long.MAX_VALUE)) return false;
                CompoundTag root = new CompoundTag();
                root.put("name\u0000\u0080ࠀ🌍", value);
                if (exactRoot(root, Long.MAX_VALUE) != measuredRoot(root, Long.MAX_VALUE)) return false;
                ListTag list = new ListTag();
                list.add(value);
                list.add(value);
                root.put("list", list);
                if (exactRoot(root, Long.MAX_VALUE) != measuredRoot(root, Long.MAX_VALUE)) return false;
            }
            return true;
        } catch (IOException | RuntimeException incompatible) {
            return false;
        }
    }

    static boolean standardTag(Tag tag) {
        return tag.getClass() == switch (tag.getId()) {
            case Tag.TAG_END -> EndTag.class;
            case Tag.TAG_BYTE -> ByteTag.class;
            case Tag.TAG_SHORT -> ShortTag.class;
            case Tag.TAG_INT -> IntTag.class;
            case Tag.TAG_LONG -> LongTag.class;
            case Tag.TAG_FLOAT -> FloatTag.class;
            case Tag.TAG_DOUBLE -> DoubleTag.class;
            case Tag.TAG_BYTE_ARRAY -> ByteArrayTag.class;
            case Tag.TAG_STRING -> StringTag.class;
            case Tag.TAG_LIST -> ListTag.class;
            case Tag.TAG_COMPOUND -> CompoundTag.class;
            case Tag.TAG_INT_ARRAY -> IntArrayTag.class;
            case Tag.TAG_LONG_ARRAY -> LongArrayTag.class;
            default -> null;
        };
    }

    private static void account(Tag tag, NbtAccounter accounter) throws IOException {
        Class<?> type = tag.getClass();
        if (type == ByteTag.class) accounter.accountBytes(9);
        else if (type == ShortTag.class) accounter.accountBytes(10);
        else if (type == IntTag.class || type == FloatTag.class) accounter.accountBytes(12);
        else if (type == LongTag.class || type == DoubleTag.class) accounter.accountBytes(16);
        else if (type == EndTag.class) accounter.accountBytes(8);
        else if (type == ByteArrayTag.class) accounter.accountBytes(24L + ((ByteArrayTag) tag).size());
        else if (type == IntArrayTag.class) accounter.accountBytes(24L + 4L * ((IntArrayTag) tag).size());
        else if (type == LongArrayTag.class) accounter.accountBytes(24L + 8L * ((LongArrayTag) tag).size());
        else if (type == StringTag.class) {
            accounter.accountBytes(36);
            accountUtf(tag.getAsString(), accounter);
        } else if (type == ListTag.class) {
            accounter.pushDepth();
            try {
                ListTag list = (ListTag) tag;
                accounter.accountBytes(37L + 4L * list.size());
                for (int i = 0; i < list.size(); i++) {
                    Tag entry = list.get(i);
                    if (entry.getId() == Tag.TAG_END || entry.getId() != list.getElementType()) {
                        throw new IOException("Invalid NBT list element type");
                    }
                    account(entry, accounter);
                }
            } finally {
                accounter.popDepth();
            }
        } else if (type == CompoundTag.class) {
            accounter.pushDepth();
            try {
                CompoundTag compound = (CompoundTag) tag;
                accounter.accountBytes(48);
                for (String key : compound.getAllKeys()) {
                    Tag entry = compound.get(key);
                    if (entry == null || entry.getId() == Tag.TAG_END) {
                        throw new IOException("Invalid NBT compound entry");
                    }
                    accounter.accountBytes(2);
                    accountUtf(key, accounter);
                    accounter.accountBytes(28L + 2L * key.length()); // 原生 CompoundTag.load 的名称对象开销。
                    accounter.accountBytes(4);
                    account(entry, accounter);
                    accounter.accountBytes(36);
                }
                accounter.accountBytes(2);
            } finally {
                accounter.popDepth();
            }
        } else {
            throw new UnsupportedTagException();
        }
    }

    private static void accountUtf(String text, NbtAccounter accounter) throws IOException {
        int bytes = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            bytes += c >= 1 && c <= 0x7f ? 1 : c <= 0x7ff ? 2 : 3;
            if (bytes > 65535) throw new UTFDataFormatException("NBT string exceeds modified UTF-8 limit");
        }
        accounter.accountBytes(2L + bytes);
    }
}
