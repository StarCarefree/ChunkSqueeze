package cn.star.chunksqueeze.compat;

public final class NbtAccounter extends net.minecraft.nbt.NbtAccounter {
    private final long quota;
    private final int maxDepth;
    private int depth;

    public NbtAccounter(long quota, int maxDepth) {
        super(quota);
        if (quota < 0) throw new IllegalArgumentException("Negative NBT quota");
        if (maxDepth < 0) throw new IllegalArgumentException("Negative NBT depth limit");
        this.quota = quota;
        this.maxDepth = maxDepth;
    }

    public static NbtAccounter create(long quota) {
        return new NbtAccounter(quota, 512);
    }

    public static NbtAccounter unlimitedHeap() {
        return create(Long.MAX_VALUE);
    }

    @Override
    public void accountBytes(long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("Negative NBT allocation");
        long usage = getUsage();
        if (bytes > quota - usage) {
            throw new NbtAccounterException("NBT allocation exceeds quota: " + usage + " + " + bytes + " > " + quota);
        }
        super.accountBytes(bytes);
    }

    // 仅用于直接遍历；1.20.1 原生解析器使用 load 的独立深度参数。
    public void pushDepth() {
        if (depth >= maxDepth) {
            throw new NbtAccounterException("NBT exceeds depth limit: " + maxDepth);
        }
        depth++;
    }

    public void popDepth() {
        if (depth <= 0) throw new IllegalStateException("NBT depth is already zero");
        depth--;
    }
}
