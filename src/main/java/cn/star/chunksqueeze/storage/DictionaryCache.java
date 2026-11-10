package cn.star.chunksqueeze.storage;

import cn.star.chunksqueeze.codec.RegionStateDictionary;
import java.util.LinkedHashMap;
import java.util.Map;

final class DictionaryCache<K> {
    private record Entry(RegionStateDictionary dictionary, long bytes) {}
    private final LinkedHashMap<K, Entry> entries = new LinkedHashMap<>(16, .75f, true);
    private final long budget;
    private final int maximum;
    private long bytes;

    DictionaryCache(long budget, int maximum) {
        if (budget < 0 || maximum < 1) throw new IllegalArgumentException("Invalid dictionary cache limits");
        this.budget = budget;
        this.maximum = maximum;
    }

    static long defaultBudget(long maximumHeap) {
        return Math.max(16L * 1024 * 1024, Math.min(128L * 1024 * 1024, maximumHeap / 32));
    }

    synchronized RegionStateDictionary get(K key) {
        Entry entry = entries.get(key);
        return entry == null ? null : entry.dictionary;
    }

    void put(K key, RegionStateDictionary dictionary) {
        long weight = dictionary.estimatedMemoryBytes();
        synchronized (this) {
            remove(key);
            if (weight > budget) return;
            while (!entries.isEmpty() && (entries.size() >= maximum || bytes > budget - weight)) {
                var iterator = entries.entrySet().iterator();
                Map.Entry<K, Entry> oldest = iterator.next();
                bytes -= oldest.getValue().bytes;
                iterator.remove();
            }
            entries.put(key, new Entry(dictionary, weight));
            bytes += weight;
        }
    }

    synchronized void remove(K key) {
        Entry removed = entries.remove(key);
        if (removed != null) bytes -= removed.bytes;
    }

    synchronized int size() { return entries.size(); }
    synchronized long bytes() { return bytes; }
}
