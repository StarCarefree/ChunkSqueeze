# ChunkSqueeze — Fabric 1.21.1

[简体中文](README_CN.md)

**Lossless compression for Minecraft terrain chunks to reduce world save size.**

ChunkSqueeze is designed for modpacks and long-running worlds. It combines compact region storage, region-wide shared block state dictionaries, and exact section deduplication to reduce duplicate data and wasted disk allocation. It preserves block positions, all block state properties, lighting, biomes, and other mods' NBT data without relying on runtime block registry IDs.

**Version 0.6.1 · Java 21 · Fabric Loader 0.16.10+ · Fabric API 0.102.1+1.21.1**

## Features

- **Compact region storage**: Uses `.cqs` files with 512-byte alignment to reduce the space wasted by vanilla MCA's 4 KiB allocation units.
- **Region-wide shared state dictionaries**: Shares complete block states and their properties within each region, reducing duplication across chunks.
- **Exact section deduplication**: Only deduplicates sections whose entire NBT contents are identical, preserving section height, lighting, biomes, and unknown fields.
- **Background re-encoding**: Persists complete chunk data first, then uses a bounded number of background workers to optimize storage, reducing foreground save wait times. Background tasks cannot overwrite newer saves or deletions.
- **Parallel migration of existing worlds**: Converts existing MCA regions through administrative commands, with progress reporting and safe cancellation. Players cannot join during migration.
- **Integrity checks and failure protection**: Validates payloads, indexes, and file identities using copy-on-write and mirrored commits. Storage failures block further writes and prevent fallback to empty terrain.
- **Offline conversion tools**: Includes Python tools to convert worlds to compact storage or restore them to standard MCA.

The mod handles terrain chunk storage; entity and POI regions keep their original formats. Actual space savings depend on world contents. Sparse regions with only a few chunks may become larger because of fixed index overhead.

## Administrative Commands

| Command | Purpose | Permission Level |
|---|---|---:|
| `/chunksqueeze status` | View storage statistics, background tasks, and the storage failure lock | 2 |
| `/chunksqueeze migrate` | Start migration when no players are online | 4 |
| `/chunksqueeze migrate force` | Disconnect online players and start migration maintenance | 4 |
| `/chunksqueeze migrate status` | View migration progress | 4 |
| `/chunksqueeze migrate cancel` | Request safe cancellation, keeping completed migrations | 4 |

Migration only processes terrain MCA files in dimensions currently loaded by the server. It does not bulk re-encode existing CQS files. The world is saved before migration. After each region is successfully verified and committed, **its original MCA and associated MCC files are deleted without an automatic backup**. Keep your own backup. Regions with no net space savings are skipped.

In singleplayer, `force` may shut down the integrated server when it disconnects the local player. Use the offline tools to convert singleplayer saves instead.

## Configuration

Configuration file: `config/chunksqueeze.properties` (UTF-8, created on first startup).

```properties
# Minimum compressed bytes saved per chunk.
minimumSavingBytes=128
# Only encode chunks that save an allocation unit.
requireSectorSaving=true
# Use compact files for new regions.
compactRegions=true
# Migration workers. 0 = auto; bounded by CPU and heap.
migrationThreads=0
# Background workers. 0 = auto; up to 2.
backgroundThreads=0
```

All five settings are read at startup; restart after editing. `minimumSavingBytes` accepts 0–1048576, `migrationThreads` 0–16, and `backgroundThreads` 0–2. Boolean settings accept `true` or `false`. Invalid values stop startup with a configuration error. Missing keys use the defaults shown above. NeoForge TOML files are not read by this port.

The default configuration is ready to use. Automatic migration concurrency is capped at 8 workers, and background concurrency at 2; both are limited by available CPU resources and maximum JVM heap size. Changes to the background worker setting take effect after a restart. Disabling `compactRegions` only affects the creation of new regions; it does not convert existing CQS files back to MCA.

When a save returns, the complete chunk data has been persisted, but background storage optimization may still be pending. If the queue is full, a region is closed, or the server shuts down, unoptimized data remains in standard NBT form and can be retried when the region is reopened. Internal file fragmentation is reclaimed when a later flush meets the compaction thresholds, so files may not immediately reach their smallest possible size.

## Offline Conversion and Uninstallation

Stop the game or server before running the tools. Python 3.10+ is required, with no third-party dependencies. The destination directory must not already exist; the source directory is left unchanged.

Convert a standard MCA world to CQS:

```bash
python -I -X utf8 tools/compact_world.py "D:/original-world" "D:/compact-world"
```

Restore standard MCA files:

```bash
python -I -X utf8 tools/restore_world.py "D:/compact-world" "D:/restored-world"
```

**Export a standard world before uninstalling the mod, downgrading to a version that does not support the current format, or using map editors that only recognize MCA.** Offline conversion to CQS changes the storage layout only; state sharing and section deduplication are applied gradually during subsequent save optimization.

Do not modify world files with external programs while the server is running. If a storage failure occurs, stop the server and investigate. Do not delete owner files or migration journals to bypass protection.

## Validation and Performance

- [Background re-encoding and fixed-sample tests (NeoForge reference)](doc/BACKGROUND_REENCODING_0.6.0_EN.md)
- [ATM10 production server comparison (NeoForge reference)](doc/ATM_BACKGROUND_0.6.0_EN.md)
- [NBT encoding specification](doc/FORMAT.md) · [CQS region format](doc/COMPACT_FORMAT.md)

## License

This project is licensed under the [Mozilla Public License 2.0 (MPL-2.0)](LICENSE).
