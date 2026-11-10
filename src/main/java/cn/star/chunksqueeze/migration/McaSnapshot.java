package cn.star.chunksqueeze.migration;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public final class McaSnapshot {
    private static final int MAX_PAYLOAD = 128 * 1024 * 1024;
    private static final long MAX_TOTAL = 256L * 1024 * 1024;

    public record Chunk(int slot, int codec, int timestamp, byte[] payload, boolean external) {
        public Chunk { payload = payload.clone(); }
        @Override public byte[] payload() { return payload.clone(); }
    }

    private final Path folder;
    private final int regionX, regionZ;
    private final byte[] fingerprint;
    private final long sourceBytes;
    private final List<Chunk> chunks;
    private final List<String> externalNames;
    private final int[] timestamps;

    private McaSnapshot(Path folder, int x, int z, byte[] fingerprint, long bytes,
                        List<Chunk> chunks, List<String> names, int[] timestamps) {
        this.folder = folder;
        regionX = x;
        regionZ = z;
        this.fingerprint = fingerprint;
        sourceBytes = bytes;
        this.chunks = List.copyOf(chunks);
        externalNames = List.copyOf(names);
        this.timestamps = timestamps;
    }

    public Path folder() { return folder; }
    public int regionX() { return regionX; }
    public int regionZ() { return regionZ; }
    public byte[] fingerprint() { return fingerprint.clone(); }
    public long sourceBytes() { return sourceBytes; }
    public List<Chunk> chunks() { return chunks; }
    public List<String> externalNames() { return externalNames; }
    public int[] timestamps() { return timestamps.clone(); }

    public static McaSnapshot capture(Path folder, int rx, int rz) throws IOException {
        return capture(folder, rx, rz, null);
    }

    // 恢复时外部文件可能分布在原目录和备份目录；重复存在必须拒绝。
    static McaSnapshot capture(Path folder, int rx, int rz, Path alternate) throws IOException {
        folder = folder.toAbsolutePath().normalize();
        checkPath(folder);
        if (alternate != null) checkPath(alternate);
        if ((long) rx * 32 < Integer.MIN_VALUE || (long) rx * 32 + 31 > Integer.MAX_VALUE
                || (long) rz * 32 < Integer.MIN_VALUE || (long) rz * 32 + 31 > Integer.MAX_VALUE) {
            throw new IOException("Region coordinates exceed canonical chunk range");
        }
        Path file = folder.resolve("r." + rx + "." + rz + ".mca");
        requireFile(file);
        MessageDigest hash;
        try { hash = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        var chunks = new ArrayList<Chunk>();
        var names = new ArrayList<String>();
        int[] timestamps = new int[1024];
        long bytes, total = 0;
        try (var in = new RandomAccessFile(file.toFile(), "r")) {
            bytes = in.length();
            if (bytes == 0) return new McaSnapshot(folder, rx, rz, hash.digest(), 0, chunks, names, timestamps);
            if (bytes < 8192) throw new IOException("Incomplete MCA header: " + file);
            byte[] header = new byte[8192];
            in.readFully(header);
            hash.update(header);
            ByteBuffer table = ByteBuffer.wrap(header);
            var spans = new ArrayList<long[]>();
            for (int slot = 0; slot < 1024; slot++) {
                timestamps[slot] = table.getInt(4096 + slot * 4);
                int location = table.getInt(slot * 4);
                if (location == 0) continue;
                long start = (long) (location >>> 8) * 4096;
                int count = location & 255;
                long end = start + count * 4096L;
                if (start < 8192 || count == 0 || end > (bytes + 4095) / 4096 * 4096 || start + 5 > bytes) {
                    throw new IOException("Invalid MCA location: " + slot);
                }
                spans.add(new long[]{start, end});
            }
            spans.sort(Comparator.comparingLong(span -> span[0]));
            long end = 8192;
            for (long[] span : spans) {
                if (span[0] < end) throw new IOException("Overlapping MCA allocations");
                end = span[1];
            }
            for (int slot = 0; slot < 1024; slot++) {
                int location = table.getInt(slot * 4);
                if (location == 0) continue;
                in.seek((long) (location >>> 8) * 4096);
                int length = in.readInt();
                int flags = in.readUnsignedByte();
                int codec = flags & 127;
                boolean external = (flags & 128) != 0;
                if (codec < 1 || codec > 3 || length < 1 || length > (location & 255) * 4096 - 4) {
                    throw new IOException("Invalid MCA record: " + slot);
                }
                byte[] payload;
                if (external) {
                    if (length != 1 || (location & 255) != 1) throw new IOException("Invalid external MCA stub");
                    String name = "c." + (rx * 32 + (slot & 31)) + "." + (rz * 32 + (slot >>> 5)) + ".mcc";
                    Path externalFile = folder.resolve(name);
                    if (alternate != null && exists(alternate.resolve(name))) {
                        if (exists(externalFile)) throw new IOException("Conflicting external backup: " + name);
                        externalFile = alternate.resolve(name);
                    }
                    requireFile(externalFile);
                    long size = Files.size(externalFile);
                    if (size <= 0 || size > MAX_PAYLOAD || total + size > MAX_TOTAL) throw new IOException("External payload exceeds limits");
                    payload = Files.readAllBytes(externalFile);
                    if (payload.length != size) throw new IOException("External file changed during capture");
                    bytes += size;
                    names.add(name);
                } else {
                    int size = length - 1;
                    if (size <= 0 || size > MAX_PAYLOAD || total + size > MAX_TOTAL) throw new IOException("MCA payload exceeds limits");
                    payload = new byte[size];
                    in.readFully(payload);
                }
                total += payload.length;
                hash.update(ByteBuffer.allocate(12).putInt(slot).putInt(codec).putInt(payload.length).array());
                hash.update(payload);
                chunks.add(new Chunk(slot, codec, timestamps[slot], payload, external));
            }
        }
        return new McaSnapshot(folder, rx, rz, hash.digest(), bytes, chunks, names, timestamps);
    }

    static boolean exists(Path path) { return Files.exists(path, LinkOption.NOFOLLOW_LINKS); }
    static void requireFile(Path path) throws IOException {
        checkPath(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Expected regular file: " + path);
    }
    static void checkPath(Path path) throws IOException {
        for (Path part = path.toAbsolutePath().normalize(); part != null; part = part.getParent()) {
            if (Files.isSymbolicLink(part)) throw new IOException("Symbolic links are forbidden: " + part);
        }
    }
}
