# 0.6.0 Background Re-encoding: Local Validation and Performance

> Historical measurements from the NeoForge 1.21.1 edition. These are not performance or ATM10 validation results for this Forge 1.20.1 port.

Date: 2026-11-10. Status: local validation and testing with the ATM10 production server distribution are complete. See [ATM_BACKGROUND_0.6.0_EN.md](ATM_BACKGROUND_0.6.0_EN.md) for server performance and memory measurements during active work.

## Save Behavior

During gameplay, the CQS foreground save path compresses complete standard NBT once with zlib, confirms persistence through the existing synchronous copy-on-write (COW) protocol, and then queues an optional storage optimization request. Background workers read committed records and evaluate shared-state and exact-section encodings outside the storage lock. Before publishing, they recheck the handle identity, chunk generation, codec, and dictionary generation. A newer save, deletion, or handle close and reopen invalidates stale work; a changed dictionary causes the request to be queued again. The dictionary and encoded payload are still committed together, preserving the foreground save timestamp.

The service uses 1–2 low-priority worker threads. It admits at most 128 region jobs, with up to 1,024 deduplicated pending slots per region and 32 chunks per batch. Work within one region is serial. A region becomes eligible after 500 ms without foreground writes, and requests retain their arrival order. Queued jobs contain slot identifiers and weak references, not NBT. Shutdown requests cooperative cancellation and waits for the current operation to finish safely, without interrupting file channels.

Flush does not wait for background work. Closing or evicting a region, shutting down, or reaching queue capacity may leave optimization work unprocessed; the original data is already persistent, and reopening the region can trigger another attempt. Existing MCA saves and migration still encode synchronously. The state, section, and CQS formats are unchanged.

## Foreground Save Comparison

Storage microbenchmarks ran on Windows 11 with Java 21.0.10 and NeoForge 21.1.220, without Mixin transformation. Each dimension used the same 256 chunks from one region, with two independent JVM processes and three rounds per process, a fixed 2 GiB heap, and G1. Version 0.5.2 used the released JAR; 0.6.0 used verified production classes. Old and new versions were interleaved, all load-generating runs were serial, and input NBT plus overwrite copies were preloaded. Each measurement includes all writes and an explicit flush; construction, close, and comparison are excluded. Full NBT comparisons passed after closing and reopening storage at every stage.

Values below are medians across six rounds, in ms/chunk. The vanilla synchronous measurements come from a separate vanilla backend in the 0.6.0 benchmark processes; its durability protocol is not identical to CQS.

| Dimension | Stage | 0.5.2 foreground | 0.6.0 foreground | Change | Vanilla synchronous |
|---|---|---:|---:|---:|---:|
| Overworld | First save | 8.338 | 1.986 | -76.2% | 1.552 |
| Overworld | Overwrite | 7.445 | 1.849 | -75.2% | 1.480 |
| Nether | First save | 6.287 | 1.740 | -72.3% | 1.387 |
| Nether | Overwrite | 6.277 | 1.719 | -72.6% | 1.302 |
| Twilight Forest | First save | 3.015 | 1.016 | -66.3% | 0.698 |
| Twilight Forest | Overwrite | 2.736 | 0.993 | -63.7% | 0.581 |

These are storage-layer microbenchmarks, not measurements of ATM10's `saveEverything`, exploration throughput, or TPS. ATM10 full-save measurements for this version are available in [ATM_BACKGROUND_0.6.0_EN.md](ATM_BACKGROUND_0.6.0_EN.md).

## Deferred Work

All values below are seconds per batch of 256 chunks. The background wait includes any remaining part of the 500 ms quiet period; background work may already have overlapped the foreground phase. Total completion time is calculated by summing foreground time, background drain time, and the subsequent flush for each round, then taking the median. Adding the individual column medians does not necessarily produce that total.

| Dimension | Stage | Foreground persistence | Subsequent background wait | Subsequent flush | Through background and flush completion | 0.5.2 synchronous completion |
|---|---|---:|---:|---:|---:|---:|
| Overworld | First save | 0.508 | 2.777 | 0.0017 | 3.287 | 2.135 |
| Overworld | Overwrite | 0.473 | 2.611 | 0.0013 | 3.086 | 1.906 |
| Nether | First save | 0.445 | 2.249 | 0.0017 | 2.713 | 1.609 |
| Nether | Overwrite | 0.440 | 2.215 | 0.0014 | 2.662 | 1.607 |
| Twilight Forest | First save | 0.260 | 1.355 | 0.0022 | 1.627 | 0.772 |
| Twilight Forest | Overwrite | 0.254 | 1.325 | 0.0017 | 1.581 | 0.700 |

Foreground blocking decreased substantially, but total completion time increased; this change did not reduce total work. Saving the original NBT, reading and parsing it again, encoding it, and persisting a second version all have costs. Median process-wide CPU time over the foreground and background-wait windows increased by approximately 18%–73% compared with the old version, excluding CPU time for subsequent compaction. Moving compression out of the foreground does not make it free.

## Complete File Sizes

Each dimension contains 20 regions and 20,480 chunks written through the runtime save path. Totals include CQS indexes, dictionaries, fragmentation, and owner files. New saves produce new acknowledgement timestamps; background processing and two subsequent reopens preserve those timestamps. These are not claimed to be the original MCA timestamps. This streaming correctness test allows foreground and background work to overlap, with a 4 GiB maximum heap and two background workers. Its timings are not used in the isolated performance comparison above.

| Dimension | Original MCA (MiB) | At foreground completion (MiB) | After background completion and normal flush (MiB) | After explicit compaction (MiB) | Normal flush vs. original MCA |
|---|---:|---:|---:|---:|---:|
| Overworld | 281.64 | 227.52 | 227.53 | 191.54 | -19.21% |
| Nether | 283.37 | 227.27 | 227.29 | 204.93 | -19.79% |
| Twilight Forest | 141.52 | 98.43 | 98.44 | 89.12 | -30.44% |

Exact byte counts after explicit compaction:

| Dimension | 0.6.0 bytes | Previously verified 0.5.2 compact sample bytes |
|---|---:|---:|
| Overworld | 200,840,704 | 200,847,360 |
| Nether | 214,884,864 | 214,883,840 |
| Twilight Forest | 93,453,312 | 93,463,040 |

After explicit compaction, compression is essentially unchanged from the old compact samples. However, the automatic workflow does not guarantee reaching those file sizes immediately. Normal flush only compacts when wasted space is at least 1 MiB and at least 25% of the file. These runs retained internal gaps below that threshold. Draining the background queue does not itself force compaction of entire regions. File sizes at foreground completion may already include background results for other regions, so they do not represent purely unoptimized NBT storage.

## Memory Bounds and Sampling

The fixed `int[1024]` arrays and bitmaps in a full queue of 128 regions have approximately 528 KiB of backing storage, excluding object overhead. Each open region attached to the background service also has an 8 KiB array tracking attempted generations, with at most 64 arrays per storage instance, or approximately 512 KiB per instance. Instances in different dimensions add together. These are data-structure capacities, not measured retained sizes. The existing global read-dictionary cache still evicts by estimated weight, with a budget of 1/32 of the maximum heap, clamped to 16–128 MiB, and at most 64 entries.

Active work additionally holds private dictionaries, input data, candidate NBT, and compression buffers. Compressed snapshots are limited to 16 MiB, and NBT parsing has a 64 MiB accounting budget. Worker count is estimated using 768 MiB per worker and at most half the maximum heap, with a minimum of one worker. This estimate is neither a memory reservation nor a hard limit.

An additional recording covered approximately 39.03 seconds of the local Nether streaming validation. Both background threads had execution samples. After excluding the first allocation sample from each thread, which may include allocation history from before recording began, the remaining sampled allocation weights were approximately 1,234.74 MiB and 1,142.40 MiB. These estimate allocation throughput during the window, not resident memory usage.

The entire verifier JVM had an observed peak working set of 1,550.54 MiB and peak private committed memory of 2,407.99 MiB during that window. Heap usage sampled at GC boundaries ranged from 35.47 to 1,264.85 MiB, with 0 `SystemGC` events. These measurements include sample loading, foreground validation, dictionaries, background candidates, and unreclaimed garbage. They are neither lifetime JVM peaks nor the mod's incremental memory usage. This local recording has no matching window without the mod, so it cannot establish additional resident memory in ATM10. The separate six-run ATM10 process-memory comparison is documented in [ATM_BACKGROUND_0.6.0_EN.md](ATM_BACKGROUND_0.6.0_EN.md); whole-JVM differences there likewise do not equal the mod's retained size. An earlier 15-second Overworld recording occurred near the end of reopen verification and had no background allocation samples remaining after the first samples were excluded; it is not used as evidence of memory usage during active background work.

JFR was attached only to full-sample correctness validation processes, not to the save-comparison microbenchmarks. It may affect the duration of full-sample validation. No explicit GC, live histogram, or heap dump was requested.

## Correctness Validation

- 177 Java regression tests passed, with 0 failures, errors, or skipped tests. Coverage includes stale work, newer writes, deletion, dictionary changes, close and reopen, bounded queues, cancellation, shutdown without channel interruption, FIFO ordering, the failure guard, and recovery from faults at all four persistence stages.
- All 4 tests on a real NeoForge GameTest server passed. Background-service cleanup after fault injection exists only in test sources and is excluded from the production JAR.
- Across 60 regions and 61,440 chunks, full NBT and acknowledgement timestamps matched on immediate reads, after background completion, and after two independent reopens.
- One complete region from each dimension, totaling 3,072 chunks, was exported through the independent Python tool and then compared in Java for full NBT and timestamps. All three dimensions exercised codec 5 exact-section references.
- At the end of full-sample validation, `queued=0`, `active=0`, `skipped=0`, `dropped=0`, and `failed=false`. Successful optimization counts were 20,088 / 18,882 / 15,433 respectively; all other complete data remained unchanged.
- The production JAR contains no GameTest, ATM validation, or generation benchmark probes. Production source fingerprints matched before and after testing. No hardware power-loss tests or long-term multiplayer load tests were performed.

## ATM10 Validation Complete

All 1,548,745 registered states passed actual background persistence validation, and 80 newly generated FULL chunks passed reopen checks across three server starts. The six-run vanilla / 0.5.2 / 0.6.0 comparison is complete. Median foreground save time for 0.6.0 was 1.996 seconds, a -38.3% change from 0.5.2. See [ATM_BACKGROUND_0.6.0_EN.md](ATM_BACKGROUND_0.6.0_EN.md) for all timing stages, memory measurements during active work, and test scope. The original mod files and `server.properties` were restored.

## Artifacts and Reproduction

Verified JAR: `build/libs/chunksqueeze-0.6.0.jar`.

SHA-256: `12c07ba9c64600ef4fb883d84fea3d9b9e3a712027fa9c93286a5d877fa4c8eb`.

Raw data is under `validation/background-0.6.0/`. The local serial test driver is `validation/scripts/run_background_060_local.py`; it refuses to overwrite existing output. Performance summaries are in `summary.json`, and JFR/process-memory recordings are in `local-memory-active/`. The ATM10 serial driver is `validation/scripts/run_background_060_servers.py`, with results under `atm/`. Subsequent changes to test probes are not included in the production JAR.
