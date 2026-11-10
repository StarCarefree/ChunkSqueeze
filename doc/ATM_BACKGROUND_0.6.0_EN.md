# 0.6.0 ATM10 Background Re-encoding Validation

Date: 2026-11-10. Testing used an isolated ATM10 8.5 production server distribution with Minecraft 1.21.1, NeoForge 21.1.265, and Java 21.0.10. It used the original 0.6.0 JAR that had passed local validation. Neither the production code nor the JAR changed during this test sequence.

## Correctness

Three independent server starts completed. A total of 49,078 blocks, 1,548,745 registered states, and 221 namespaces were tested through the actual persistence path: save complete NBT, re-encode in the background, close storage, and reopen it.

The tests used 12,290 synthetic chunks. Persisted records included 2,486 with exact-section references and 2,828 with shared-state references. Full NBT comparisons passed across 190 independently reopened groups and 190 groups with independent chunk rewrite checks.

An additional 64+16 new Overworld FULL chunks were generated. Subsequent server starts compared hashes of every block state at every position, with all 80 chunks checked on the final reopen. These world hashes include registry names and all block state properties, but do not cover entities, block entities, biomes, or lighting; the synthetic NBT tests separately compare complete values. Shutdown may cancel pending optimization tasks, and unoptimized standard records remain readable after reopening.

## Save Comparison

Six new worlds were tested in six independent JVM processes, serially in this order: vanilla / 0.5.2 / 0.6.0 / 0.6.0 / 0.5.2 / vanilla. Each used seed 512060, warmed up with 8 chunks, then obtained one target FULL chunk per tick for a total of 256 target chunks, with incidental neighbor generation. All runs used `-Xms4G -Xmx10G`, G1, `sync-chunk-writes=true`, view distance 3, and simulation distance 3. Mod ID/version sets were identical across all six runs except for ChunkSqueeze.

Immediately after generation, `saveEverything(true,true,true)` waited for all foreground I/O to complete. Version 0.6.0 then waited for background work, flushed again, and waited for any background tasks caused by that flush. These four stages were timed separately. Timings exclude startup, warmup, and subsequent hashing. External process-memory counters were sampled every 0.5 seconds; no JFR was attached and no explicit GC was requested in these performance processes.

Each row below is one actual run, in seconds:

| Backend | Run | Generation phase | Full foreground save | Background wait | Subsequent flush | Second background wait |
|---|---:|---:|---:|---:|---:|---:|
| Vanilla storage | 1 | 19.485 | 1.693 | 0.000 | 0.000 | 0.000 |
| 0.5.2 | 1 | 20.461 | 3.087 | 0.000 | 0.000 | 0.000 |
| 0.6.0 | 1 | 20.354 | 1.962 | 0.562 | 0.656 | 0.000 |
| 0.6.0 | 2 | 22.681 | 2.030 | 0.637 | 0.705 | 0.000 |
| 0.5.2 | 2 | 25.118 | 3.381 | 0.000 | 0.000 | 0.000 |
| Vanilla storage | 2 | 18.102 | 1.520 | 0.000 | 0.000 | 0.000 |

Medians of the two runs per backend:

| Backend | Foreground save (s) | Generation + foreground save (s) | Save through final background completion (s) | Generation through final background completion (s) |
|---|---:|---:|---:|---:|
| Vanilla storage | 1.606 | 20.400 | 1.606 | 20.400 |
| 0.5.2 | 3.234 | 26.023 | 3.234 | 26.023 |
| 0.6.0 | 1.996 | 23.513 | 3.276 | 24.793 |

Version 0.6.0 changed full foreground save time by -38.3% relative to 0.5.2, taking 1.24 times as long as vanilla. Total time through background completion must also be considered; a shorter foreground return time alone does not demonstrate less computation.

There were only two process runs per backend, with variation from JVM warmup, generation, and mod behavior. The generation phase includes intervals between ticks, neighbor generation, and asynchronous work, so it is not a measurement of the terrain algorithm alone. These results cannot be extrapolated to long-term multiplayer TPS.

## Process Memory During Active Work

The table below shows observed peak working set and private committed memory for each run, from the start of generation until the full foreground save returned. Values are in MiB and cover the entire JVM process, including heap capacity, native memory, mods, and unreclaimed garbage. They are not the mod's retained size or a definitive increase in resident memory relative to vanilla.

| Backend | Run | Samples | Observed peak working set | Observed peak private committed memory |
|---|---:|---:|---:|---:|
| Vanilla storage | 1 | 42 | 8283.29 | 8397.36 |
| 0.5.2 | 1 | 47 | 7822.96 | 7934.88 |
| 0.6.0 | 1 | 45 | 8145.95 | 8245.16 |
| 0.6.0 | 2 | 49 | 9140.76 | 9265.89 |
| 0.5.2 | 2 | 56 | 8477.12 | 8596.69 |
| Vanilla storage | 2 | 39 | 8196.18 | 8309.12 |

The background-wait and subsequent-flush windows for 0.6.0 are listed separately:

| Run | Window duration (s) | Samples | Observed peak working set (MiB) | Observed peak private committed memory (MiB) |
|---|---:|---:|---:|---:|
| 1 | 1.219 | 2 | 8049.47 | 8148.48 |
| 2 | 1.343 | 3 | 8943.27 | 9067.50 |

Sampling every 0.5 seconds can miss short peaks. Differences in GC timing, committed heap capacity, game state, and JIT state mean that process-memory differences cannot all be attributed to ChunkSqueeze. This sampling does not establish the absence of long-term leaks. See the [local validation report](BACKGROUND_REENCODING_0.6.0_EN.md) for background-worker JFR recordings from full-sample validation and static capacity bounds.

## File Sizes and Server Restoration

The following totals cover all terrain region files in each world after normal shutdown, including neighbor and spawn-area generation, CQS owner files, and fragmentation. No additional compaction was forced. Different server starts may produce different simulation results, so these totals are not a strict compression-ratio comparison of identical complete NBT. See the [local validation report](BACKGROUND_REENCODING_0.6.0_EN.md) for fixed samples from three dimensions.

| Backend | Run | Terrain files (MiB) |
|---|---:|---:|
| Vanilla storage | 1 | 16.51 |
| 0.5.2 | 1 | 6.92 |
| 0.6.0 | 1 | 7.67 |
| 0.6.0 | 2 | 7.68 |
| 0.5.2 | 2 | 6.92 |
| Vanilla storage | 2 | 16.51 |

After testing, `server.properties` was restored byte for byte, and SHA-256 checks verified that the original set of files in `mods` was fully restored. The new isolated test worlds, reports, and logs were retained. The bundled server launch scripts were not executed; only the specified Java 21 executable and the official NeoForge argument file were used.

Coverage does not include complete mod gameplay, long-term multiplayer workloads, third-party backup plugin support for CQS, or real hardware power loss.

## Raw Evidence

- `validation/background-0.6.0/atm/atm-validation.json`: all-state validation and checks across server restarts.
- `generation/*.json`: per-chunk timings, separate stage durations, mod lists, and runtime arguments for all six runs.
- `generation-*-memory.json`: externally sampled process-memory counters with UTC timestamps.
- `summary.json`, `metadata.json`, and `server-status.json`: summaries, fingerprints of the tested JARs, and restoration status.
