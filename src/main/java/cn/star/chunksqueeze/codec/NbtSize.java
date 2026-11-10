package cn.star.chunksqueeze.codec;

import net.minecraft.nbt.*;

import java.io.IOException;
import java.io.UTFDataFormatException;

final class NbtSize {
    private static final int MAX_DEPTH = 512;

    private NbtSize() {}

    static long rootWithout(CompoundTag tag, String excludedKey, long limit) throws IOException {
        checked(3, limit); // 根类型与空名称。
        return 3 + compound(tag, 0, limit - 3, excludedKey);
    }

    // parentDepth 是当前标签之前已进入的容器数，列表元素不带名称或类型头。
    static long payload(Tag tag, int parentDepth, long limit) throws IOException {
        return switch (tag.getId()) {
            case Tag.TAG_END -> checked(0, limit);
            case Tag.TAG_BYTE -> checked(1, limit);
            case Tag.TAG_SHORT -> checked(2, limit);
            case Tag.TAG_INT, Tag.TAG_FLOAT -> checked(4, limit);
            case Tag.TAG_LONG, Tag.TAG_DOUBLE -> checked(8, limit);
            case Tag.TAG_BYTE_ARRAY -> checked(4L + ((ByteArrayTag) tag).size(), limit);
            case Tag.TAG_STRING -> checked(utfSize(tag.getAsString()), limit);
            case Tag.TAG_LIST -> list((ListTag) tag, parentDepth, limit);
            case Tag.TAG_COMPOUND -> compound((CompoundTag) tag, parentDepth, limit, null);
            case Tag.TAG_INT_ARRAY -> checked(4L + 4L * ((IntArrayTag) tag).size(), limit);
            case Tag.TAG_LONG_ARRAY -> checked(4L + 8L * ((LongArrayTag) tag).size(), limit);
            default -> throw new IOException("Invalid NBT tag type");
        };
    }

    private static long compound(CompoundTag tag, int parentDepth, long limit, String excludedKey) throws IOException {
        checkDepth(parentDepth);
        long size = checked(1, limit); // compound 结束符。
        for (String key : tag.getAllKeys()) {
            if (key.equals(excludedKey)) continue;
            Tag value = tag.get(key);
            size += checked(1, limit - size);
            if (value.getId() != Tag.TAG_END) {
                size += checked(utfSize(key), limit - size);
                size += payload(value, parentDepth + 1, limit - size);
            }
        }
        return size;
    }

    private static long list(ListTag tag, int parentDepth, long limit) throws IOException {
        checkDepth(parentDepth);
        long size = checked(5, limit);
        for (int i = 0; i < tag.size(); i++) {
            size += payload(tag.get(i), parentDepth + 1, limit - size);
        }
        return size;
    }

    private static int utfSize(String text) throws UTFDataFormatException {
        int bytes = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            bytes += c >= 1 && c <= 0x7f ? 1 : c <= 0x7ff ? 2 : 3;
            if (bytes > 65535) throw new UTFDataFormatException("NBT string exceeds modified UTF-8 limit");
        }
        return 2 + bytes;
    }

    private static void checkDepth(int parentDepth) throws IOException {
        if (parentDepth >= MAX_DEPTH) throw new IOException("Restored NBT exceeds depth limit");
    }

    private static long checked(long size, long limit) throws IOException {
        if (size > limit) throw new IOException("Restored chunk exceeds limit");
        return size;
    }
}
