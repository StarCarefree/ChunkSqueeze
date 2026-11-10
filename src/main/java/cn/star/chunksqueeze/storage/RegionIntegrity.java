package cn.star.chunksqueeze.storage;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;

public final class RegionIntegrity {
    private RegionIntegrity() {}

    public static void validateHeader(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var file = new RandomAccessFile(path.toFile(), "r")) {
            long size = file.length();
            if (size == 0) return;
            if (size < 8192) throw new IOException("Truncated region header: " + path);
            long availableSectors = (size + 4095) / 4096;
            var spans = new ArrayList<int[]>();
            for (int slot = 0; slot < 1024; slot++) {
                int location = file.readInt();
                if (location == 0) continue;
                int offset = location >>> 8;
                int count = location & 255;
                if (offset < 2 || count == 0 || (long) offset + count > availableSectors
                        || (long) offset * 4096 + 5 > size) {
                    throw new IOException("Invalid region location at slot " + slot + ": " + path);
                }
                spans.add(new int[]{offset, offset + count});
            }
            spans.sort(Comparator.comparingInt(span -> span[0]));
            int previousEnd = 2;
            for (int[] span : spans) {
                if (span[0] < previousEnd) throw new IOException("Overlapping region sectors: " + path);
                previousEnd = span[1];
            }
        }
    }
}
