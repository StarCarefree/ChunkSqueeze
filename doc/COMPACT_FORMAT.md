# CQS Compact Region Format

This specification describes the current CQS container, version **2**, and its persistence protocol. NBT payload encodings are specified separately in [FORMAT.md](FORMAT.md). Numeric versions and magic strings below identify on-disk structures, not mod releases.

All fixed-width integers are big endian. Payload allocations are aligned to 512 bytes. Files are named `r.X.Z.cqs` and require a sibling `r.X.Z.cqs.owner` with matching region coordinates and file UUID. Missing or mismatched peers are fatal. A same-region MCA alongside either CQS ownership file is an ambiguity error; storage must not fall back to MCA or empty terrain.

## File Layout

| Offset | Size | Contents |
|---:|---:|---|
| 0 | 512 | Immutable file header |
| 512 | 512 | Commit marker A |
| 1024 | 512 | Commit marker B |
| 1536 | 32768 | Index table 0: 1024 entries of 32 bytes |
| 34304 | 32768 | Index table 1: 1024 entries of 32 bytes |
| 67072 | Variable | Chunk and dictionary payload allocations |

The owner file is exactly one 512-byte identity page. Java holds an exclusive lock on it while the region is open, including during compaction and atomic replacement.

## Identity Pages

The file header and owner page have the same field layout:

| Bytes | Field |
|---|---|
| 0–7 | ASCII magic: `CSQREG01` for the header, `CSQOWN01` for the owner |
| 8–11 | Version: **2** for the header, **1** for the owner |
| 12–15 | Signed region X |
| 16–19 | Signed region Z |
| 20–23 | Allocation unit: 512 |
| 24–39 | File UUID: most-significant long, then least-significant long |
| 40–43 | CRC-32 of bytes 0–39 followed by bytes 44–511 |
| 44–511 | Reserved, zero-filled |

The owner format version and magic suffixes are independent of the container version.

## Commit Markers

| Bytes | Field |
|---|---|
| 0–7 | ASCII magic: `CSQCMT01` |
| 8–15 | Nonnegative signed 64-bit commit generation |
| 16–19 | Selected index table: 0 or 1 |
| 20–23 | CRC-32 of the complete selected 32768-byte table |
| 24–39 | File UUID |
| 40–43 | CRC-32 of bytes 0–39 followed by bytes 44–511 |
| 44–75 | Dictionary allocation entry, using the 32-byte layout below |
| 76–511 | Reserved, zero-filled |

An all-zero dictionary entry means no committed dictionary. A present dictionary entry has timestamp 0, codec 2, and a generation no greater than the commit generation. Its compressed payload limit is 16 MiB. The stricter NBT and allocation-accounting limits are defined in [FORMAT.md](FORMAT.md).

Read both markers and select the valid marker with the greatest generation. Equal-generation markers must agree on the index table, table CRC, and dictionary entry. A torn or invalid marker is tolerated if the other is valid. A valid newest commit referencing corrupt data is fatal; never roll back to an older terrain version. The selected dictionary CRC is checked on open, even before any chunk is read.

A successful write durably mirrors the same new commit to both markers. If publication is interrupted between the two markers, the valid newer commit wins. Before any subsequent allocation or commit update, repair unequal or torn mirrors by persisting the invalid or older copy first, then mirroring the other copy.

## Allocation Entries and Codecs

Chunk slots use MCA ordering: `localX + 32 * localZ`.

| Offset within entry | Type | Meaning |
|---:|---|---|
| 0 | long | Payload byte offset |
| 8 | int | Payload byte length |
| 12 | int | MCA-compatible timestamp bits |
| 16 | int | Codec identifier |
| 20 | int | CRC-32 of the stored payload bytes |
| 24 | long | Nonnegative entry generation, no greater than the selected commit generation |

| Codec | Payload |
|---:|---|
| 1 | Gzip-compressed standard NBT or chunk-local state encoding |
| 2 | Zlib-compressed standard NBT or chunk-local state encoding |
| 3 | Uncompressed standard NBT or chunk-local state encoding |
| 4 | Zlib NBT with regional state references; requires a committed dictionary |
| 5 | Zlib NBT with exact-section references and optional state encoding; requires a committed dictionary |

Marker requirements and allowed combinations are defined in [FORMAT.md](FORMAT.md). Runtime saves use zlib.

An absent or deleted chunk has offset, length, codec, and CRC all zero; timestamp and generation may remain nonzero. Present chunk payloads are limited to 128 MiB. Their offsets must be aligned and at least 67072, and the complete payload must fit within the file. Live chunk and dictionary allocations must not overlap after rounding their lengths up to 512 bytes. Unreferenced tail padding may be absent. Payload CRC is verified on read; offline conversion and export validate every payload. CQS does not require external MCC files.

## Durable Write Protocol

1. Allocate new spans without overwriting any data referenced by the current commit. Write and persist the chunk payload and, when changed, the complete dictionary snapshot. Newly reserved spans must also be protected from overlap.
2. Write and persist the complete updated index to the inactive table.
3. Write and persist commit marker A, publishing the new index and dictionary references together.
4. Write and persist the identical commit marker B. Only then acknowledge success.

Ordinary regions prefer `StandardOpenOption.SYNC`: each completed channel write synchronizes content and metadata. If opening with SYNC throws `UnsupportedOperationException`, reopen without it and use `force(true)` after each stage. Prepared builders use plain channels with batch forces. Initialization, mirror repair, truncation, explicit flush, and compaction retain explicit forces. Compacted files reopen with the same SYNC preference.

Writes omit unreferenced allocation padding while retaining aligned allocation boundaries. After both commit mirrors are durable, obsolete spans may be reused. Any write failure poisons the open writer; it cannot continue allocating from uncertain state. The runtime storage failure guard prevents further writes and empty-terrain fallback until the fault is handled and the process is restarted.

New regions initialize both tables to zero and both commits to generation 0, table 0, with no dictionary. Owner identity is durable before the new empty region is published. Incomplete creation fails closed.

## Background Re-encoding

Foreground CQS saves first commit complete standard NBT using codec 2 and the same durable protocol. A bounded queue requests optional background optimization; it never holds the only copy of an acknowledged save.

Under the storage lock, a worker captures the committed payload, slot generation, dictionary generation, timestamp, and handle identity. It prepares a candidate using a private dictionary outside the lock, then validates the captured versions before publishing. A newer save, deletion, or reopened handle rejects stale work. A changed dictionary rejects the plan and queues another attempt. Dictionary growth and the encoded chunk publish in one commit, preserving the foreground timestamp.

Flush does not wait for background encoding. Region close, eviction, queue saturation, and shutdown may leave standard records. Opening a region queues its standard zlib slots for another attempt. Shutdown cancels pending work and joins workers without interrupting file channels.

## Reads, Reclamation, and Compaction

Cached reads reuse validated handles and still verify payload CRCs. File presence, owner pairing, and MCA conflicts are checked on open, write, flush, eviction, and close. Storage construction scans pending migration journals. A bounded cache of verified journal absence is invalidated around exclusive migration operations, including exceptional exits. Reads use `FastBufferedInputStream` around decompression.

Trailing unused space may be truncated only after the latest commit has been mirrored successfully. Internal holes are reused with first-fit allocation. Normal flush considers compaction when wasted space is at least 1 MiB and at least 25% of file size. Completing background work does not itself force compaction.

Compaction copies live chunk and dictionary payloads into a complete temporary file, preserves UUIDs, timestamps, and entry generations, forces the file, and atomically replaces the region while retaining the owner lock. Unsupported atomic replacement fails without a non-atomic fallback. A leftover temporary file is never authoritative. Compaction reclaims physical storage; it does not remove or renumber logical dictionary entries.

## Migration Transactions

Online migration blocks player admission and saves the world before processing terrain MCA files from loaded dimensions. Bounded workers prepare independent regions in parallel. A fair shared/exclusive gate protects terrain I/O: source capture, handle eviction, publication, and recovery are exclusive, while encoding runs outside the gate.

A prepared region is built only under `region/.chunksqueeze-migration/<canonical UUID>/prepared/`. Its builder holds a newly created owner lock, starts without a valid commit, appends each slot at most once, and cannot overwrite an existing region. Completion forces payloads, writes both indexes and commits, then forces the finished file. Migration closes and reopens the result and compares complete decoded NBT and timestamps before allowing publication. Failed or cancelled preparation is disposable and does not own the live region.

Publication rechecks a SHA-256 fingerprint of the complete MCA header and referenced compressed payloads. Changed sources are retried at most three times. A region is skipped unless its complete CQS and owner files provide a net active-file saving. Zero-byte native regions are valid empty regions; nonempty truncated headers are rejected.

An 88-byte transaction journal is written and forced as `journal.tmp`, then atomically installed as `r.X.Z.cqs.migrating`. It contains magic `CSQMIG01`, journal version 1, transaction UUID, target CQS UUID, region coordinates, a 32-byte source fingerprint, and CRC-32. Publication moves original MCA/MCC files into the transaction's `original/` directory, installs the owner, then installs CQS last. Once the committed pair is validated, the originals are deleted before journal and empty-directory cleanup. No automatic backup remains after a successful migration.

Recovery with a valid live CQS validates its identity and payloads, removes remaining transaction originals, and rolls forward while preserving later writes. Without a live CQS, recovery validates and restores the original source. Corrupt final data never causes a rollback to old terrain. Cleanup accepts only expected regular MCA/MCC files from that transaction; unknown files or conflicts stop cleanup. The journal remains until cleanup succeeds. Directories without an active journal are not automatically cleaned up.

Cancellation is honored before publication and never interrupts file channels. Server shutdown cancels and joins storage workers before releasing the world lock, including exceptional shutdown paths. Cache-close failures trigger the storage failure guard.

## Offline Tools and Integrity Scope

`compact_world.py SOURCE DEST` builds a new world copy with terrain MCA replaced by CQS and owner files. It validates source records, preserves timestamps and compressed NBT, and leaves the source unchanged. Full-world conversion excludes POI and entity regions; flat region-sample directories are also supported. Existing CQS regions are validated and copied.

`restore_world.py SOURCE DEST` resolves state and section encodings into standard MCA, creates external MCC files when needed, preserves timestamps and non-storage files, and removes CQS ownership and format markers from the new copy. Ambiguity, missing ownership peers, unsupported formats, checksum failures, and invalid indexes abort export. Output is published only after success.

Offline tools reject active `.cqs.migrating` journals and copy `.chunksqueeze-migration` directories without interpreting or converting their contents. External edits to a live world are unsupported; stop the server first.

CRC detects accidental corruption, not malicious modification. SYNC, force, atomic replacement, and simulated interruption checks do not establish hardware power-loss guarantees for every device or filesystem. Directory durability is forced where the platform supports it.
