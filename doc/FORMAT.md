# ChunkSqueeze NBT Encoding Specification

This document describes the encodings used by the current implementation. The CQS container and its commit protocol are specified in [COMPACT_FORMAT.md](COMPACT_FORMAT.md). Numeric versions below distinguish active payload and dictionary representations, not mod releases.

Runtime CQS saves first persist complete standard NBT. Background re-encoding may later replace it with a smaller encoded representation. Data that does not meet the encoding requirements or savings thresholds remains in standard form.

## Common Rules

- Fixed-width multibyte integers are big endian. NBT strings use Java modified UTF-8.
- Unsigned variable integers use canonical seven-bit groups, low group first, with bit 7 indicating continuation and at most five bytes. Each value must satisfy its field-specific bounds.
- Encoded chunk roots must contain a nonempty string `Status`.
- Custom marker payloads are limited to 64 MiB. Dictionary parsing and fully restored custom NBT are checked against 64 MiB accounting limits and a maximum depth of 512. These custom-encoding limits do not impose a new size restriction on otherwise valid, unencoded standard NBT.
- Unsupported versions, CRC failures, trailing payload bytes, invalid references, and malformed structures are errors, never empty terrain.
- CRC-32 values are stored as unsigned values in NBT longs. CRC detects accidental corruption, not authentication.

## Block State Envelope

Encoded terrain NBT contains the compound `chunksqueeze:storage`:

| Field | NBT type | Meaning |
|---|---|---|
| `version` | Int | 1 for a chunk-local dictionary; 2 for regional references |
| `crc32` | Long | Unsigned CRC-32 of the exact payload bytes |
| `payload` | Byte array | Encoded payload, at most 64 MiB |

Only transformed `sections[i].block_states` compounds are removed. Other fields remain in place. Section identity is its original list index, not its Y coordinate. A section whose `block_states` contains unknown extension keys is left untouched.

Both dictionary representations below remain active encoder choices. Regional capacity exhaustion or candidate selection may use the chunk-local representation or retain standard NBT.

### Chunk-Local Dictionary Payload

For marker version 1, the payload contains:

1. Magic `CSQ1` (`43 53 51 31`).
2. Standard uncompressed named-root compound NBT containing `states`, a nonempty list of complete state compounds, with at most 65,536 entries.
3. Section records in the common layout below.

Palette IDs refer directly to this dictionary. No external dictionary or runtime registry ID is required.

### Regional Reference Payload

For marker version 2, the payload contains:

1. Magic `CSQ2` (`43 53 51 32`).
2. Persistent dictionary UUID: most-significant long followed by least-significant long.
3. Canonical varint local dictionary count, from 1 to 65,536.
4. That many canonical varint IDs into the persistent regional state dictionary, in local dictionary order.
5. Section records in the common layout below, whose palette IDs refer to the local mapping.

A missing dictionary, UUID mismatch, invalid mapping, or incompatible container codec is fatal. Complete state compounds, including their unknown fields, are interned by NBT equality. Runtime block registry IDs are never persisted.

### Common Section Records

The dictionary portion is followed by a canonical varint record count, from 1 to the number of chunk sections, with at most 1,024 records. Each record contains:

1. Original section-list index as a varint. Indices must be unique, and the target must not already contain `block_states`.
2. Local palette size as a varint, from 1 to 4,096.
3. One varint dictionary ID per palette entry, in original palette order. Duplicate entries are preserved if present in the input.
4. Encoded index byte length as a varint, from 1 to 65,536.
5. Exactly that many index bytes.

After validating references and the restored-size budget, the decoder restores each complete palette and its original data-array presence, removes the marker, and preserves all unrelated NBT.

## Index Encodings

The first index byte selects the mode:

| Mode | Following bytes |
|---:|---|
| 0 | None. Palette size must be 1, and the original `data` tag was absent. |
| 1 | Varint long count, followed by the original big-endian 64-bit array, including padding bits. |
| 2 | Dense LSB-first bitstream for all 4,096 local palette indices, using `max(1, ceil(log2(paletteSize)))` bits per entry. |
| 3 | Repeated `(varint runLength, varint paletteIndex)` pairs covering exactly 4,096 positions. Runs must be positive. |
| 4 | Varint default index, varint exception count, then `(varint gap, varint paletteIndex)` pairs. Start at position -1; each next position is `previous + gap + 1`. |
| 5 | Bit planes in increasing significance order, each 512 bytes. Bit `i % 8` of byte `i / 8` represents block position `i`. Plane count is `max(1, ceil(log2(paletteSize)))`. |

All reconstructed palette indices must be less than the palette size. Modes 2–5 restore Minecraft's non-crossing-long layout:

- `bits = max(4, ceil(log2(paletteSize)))`
- `entriesPerLong = floor(64 / bits)`
- Array length: `ceil(4096 / entriesPerLong)` longs
- Position `i`: low-first slot `(i % entriesPerLong) * bits` in long `i / entriesPerLong`
- Padding bits: zero

An encoder may choose modes 2–5 only if reconstruction matches the complete original long array. Otherwise it uses mode 1 so nonzero padding is not discarded.

## Persistent Regional Dictionary

The dictionary is a committed allocation inside CQS, not a loose sidecar. It is stored as one bounded zlib stream without trailing compressed data. Its named-root compound contains:

| Field | NBT type | Meaning |
|---|---|---|
| `version` | Int | 1 for states only; 2 for states and a section pool |
| `idMost`, `idLeast` | Long | Persistent dictionary UUID |
| `states` | List of compounds | Complete block states |
| `sections` | List of compounds | Complete sections; required for dictionary version 2 |

| Representation | Maximum states | Maximum sections | Maximum serialized uncompressed NBT | Parsing budget |
|---|---:|---:|---:|---:|
| States only, version 1 | 65,536 | No section pool | 4 MiB | 64 MiB |
| States and sections, version 2 | 65,536 | 4,096 | 8 MiB combined | 64 MiB |

New state-only dictionaries use version 1. Dictionaries with a section pool use version 2; an empty section list is valid and retains version 2 on rewrite. Readers validate the complete stream, header, lists, and size limits before use.

State and section IDs are independently zero-based, append-only, and never reused or renumbered online. Capacity exhaustion prevents further additions but may still reuse existing entries or leave data inline. Dictionary growth and any chunk that references it are published in the same CQS commit.

## Exact Section References

A chunk with exact-section references contains `chunksqueeze:sections`, a compound with exactly these fields:

| Field | NBT type | Meaning |
|---|---|---|
| `version` | Int | 1 |
| `crc32` | Long | Unsigned CRC-32 of the payload |
| `payload` | Byte array | Encoded references, at most 64 MiB |

The payload contains:

1. Magic `CSS1` (`43 53 53 31`).
2. Persistent dictionary UUID as two longs.
3. Canonical varint reference count, from 1 to 1,024 and bounded by the chunk section count.
4. That many pairs of canonical varints: `(sectionListIndex, sectionPoolId)`.

Duplicate section indices and trailing bytes are forbidden. Every referenced section position must contain an empty compound placeholder. Missing dictionaries, UUID mismatches, nonempty placeholders, and invalid pool references are fatal.

The same chunk may use chunk-local or regional state encoding for other sections. Restore state references before whole-section references, rejecting any overlap between their section sets. Before deep-copying, account for the combined fully restored NBT under the 64 MiB and depth-512 limits.

Section identity covers the entire compound: Y, `block_states`, biome palettes, light arrays, and every unknown field. Palette order and array padding are not normalized. Hash lookup is followed by recursive, typed NBT equality; a hash collision alone never proves identity. Float and double comparisons use the canonical bit representation written by NBT, distinguishing signed zero in memory. Minecraft's own NBT parsing and zero/NaN normalization are unchanged; no stronger raw-byte guarantee is claimed than that layer provides.

## Container Codec Requirements

| CQS codec | Permitted custom markers |
|---:|---|
| 1–3 | No marker, or chunk-local state marker version 1; no section references or regional state marker |
| 4 | Required state marker version 2 and committed dictionary; no section references |
| 5 | Required section marker and committed dictionary; optional state marker version 1 or 2 |

Codecs 4 and 5 use zlib. The dictionary UUID in each reference envelope must match the committed dictionary. The independent Python exporter resolves both state and section references into standard NBT before writing MCA and retains raw NBT string bytes.

## Encoder Selection Policy

State candidates are ranked by `BEST_SPEED` compression of their encoded payloads. Only the best-ranked state candidate is compressed as complete NBT and compared with the compressed original. Whole-section candidates use the same state preselection before the final whole-chunk comparison.

Index selection always considers raw, packed, and bitplane candidates. RLE is tried only with at most 1,024 runs, and sparse encoding only with at most 512 exceptions. These are encoder heuristics, not format restrictions: decoders accept all defined modes.

A new section is admitted to the persistent pool only after an exact repeat has been observed and the resulting chunk candidate wins the complete compression and allocation comparison. The observation cache holds at most 64 entries and 1 MiB of serialized data, excludes individual entries larger than 256 KiB, and checks the combined parsing budget before copying. When limits are reached, data remains inline or uses another valid state encoding.

Final decisions use actual complete compressed lengths and configured byte-saving and allocation-saving thresholds. Background publication also compares the candidate against the already committed payload. Candidate preselection can trade some compression ratio for CPU time while preserving complete NBT and the final savings checks.

Per-chunk comparisons do not charge the full regional dictionary growth to each chunk. Migration separately requires a net saving for the entire region, including its dictionary and owner file. Sparse or frequently edited regions may not shrink.

The encoding preserves full NBT values and types, list order, palette order, and array contents. It does not promise an identical serialized ordering of compound keys.
