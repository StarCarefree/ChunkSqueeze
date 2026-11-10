"""CQS region formats 1 and 2, using only the Python standard library."""

import os
from pathlib import Path
import re
import stat
import struct
import uuid
import zlib

ALIGN = 512
TABLE_SIZE = 32768
TABLES = (1536, 34304)
DATA_START = 67072
MAX_PAYLOAD = 128 * 1024 * 1024
MAX_DICTIONARY = 16 * 1024 * 1024
ENTRY = struct.Struct(">qiIIIq")


class FormatError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise FormatError(message)


def coordinates(path):
    match = re.fullmatch(r"r\.(-?\d+)\.(-?\d+)\.(?:mca|cqs)(?:\.owner)?", Path(path).name, re.ASCII)
    require(match is not None, "Invalid region filename: " + str(path))
    coords = tuple(map(int, match.groups()))
    require(match.groups() == tuple(map(str, coords)), "Non-canonical region filename: " + str(path))
    require(all(-(2 ** 31) <= n < 2 ** 31 for n in coords), "Region coordinates out of range")
    return coords


def page_crc(page):
    return zlib.crc32(page[:40] + page[44:])


def finish_page(page):
    struct.pack_into(">I", page, 40, page_crc(page))
    return bytes(page)


def identity(magic, coords, identifier):
    page = bytearray(512)
    struct.pack_into(">8sIiiI16s", page, 0, magic, 1, *coords, ALIGN, identifier)
    return finish_page(page)


def commit(generation, table, crc, identifier):
    page = bytearray(512)
    struct.pack_into(">8sqII16s", page, 0, b"CSQCMT01", generation, table, crc, identifier)
    return finish_page(page)


def valid_page(page, reserved_start=44):
    return (len(page) == 512 and not any(page[reserved_start:])
            and struct.unpack_from(">I", page, 40)[0] == page_crc(page))


def check_identity(page, magic, coords, versions=(1,)):
    require(valid_page(page), "Invalid identity page CRC/reserved bytes")
    found_magic, version, x, z, unit, identifier = struct.unpack_from(">8sIiiI16s", page)
    require((found_magic, x, z, unit) == (magic, *coords, ALIGN) and version in versions,
            "Invalid identity header or coordinates")
    return identifier


def regular(path):
    info = path.lstat()
    require(stat.S_ISREG(info.st_mode) and not getattr(info, "st_file_attributes", 0) & 0x400,
            "Expected regular file, not link: " + str(path))
    return info


def read_region(path, with_dictionary=False):
    """Return (timestamps, records); optionally append the compressed dictionary or None.

    Records retain (slot, compressed bytes, codec, None). Codecs 4/5 require the
    dictionary returned by with_dictionary=True and cannot be written by write_region.
    """
    path = Path(path)
    coords = coordinates(path)
    info = regular(path)
    require(info.st_size >= DATA_START, "Truncated CQS file")
    owner = path.with_name(path.name + ".owner")
    require(regular(owner).st_size == 512, "Invalid owner size")
    owner_id = check_identity(owner.read_bytes(), b"CSQOWN01", coords)
    with path.open("rb") as stream:
        header = stream.read(512)
        identifier = check_identity(header, b"CSQREG01", coords, (1, 2))
        version = struct.unpack_from(">I", header, 8)[0]
        require(identifier == owner_id, "Owner UUID mismatch")
        commits = []
        for _ in range(2):
            page = stream.read(512)
            if valid_page(page, 44 if version == 1 else 76):
                magic, generation, table, crc, found_id = struct.unpack_from(">8sqII16s", page)
                if magic == b"CSQCMT01" and generation >= 0 and table in (0, 1) and found_id == identifier:
                    commits.append((generation, table, crc, ENTRY.unpack_from(page, 44)))
        require(commits, "No valid commit marker")
        if len(commits) == 2 and commits[0][0] == commits[1][0]:
            require(commits[0] == commits[1], "Equal-generation commits disagree")
        generation, table, crc, descriptor = max(commits, key=lambda value: value[0])
        stream.seek(TABLES[table])
        index = stream.read(TABLE_SIZE)
        require(len(index) == TABLE_SIZE and zlib.crc32(index) == crc, "Committed index CRC mismatch")
        timestamps = bytearray(4096)
        spans = []
        dictionary = None
        if descriptor != (0, 0, 0, 0, 0, 0):
            offset, length, timestamp, codec, checksum, entry_gen = descriptor
            require(version == 2 and timestamp == 0 and codec == 2 and 0 <= entry_gen <= generation,
                    "Invalid region dictionary descriptor")
            require(0 < length <= MAX_DICTIONARY, "Invalid region dictionary length (max 16 MiB)")
            require(offset >= DATA_START and offset % ALIGN == 0 and offset + length <= info.st_size,
                    "Invalid region dictionary bounds/alignment")
            spans.append((offset, offset + ((length + 511) // 512) * 512, -1, length, codec, checksum))
        for slot in range(1024):
            offset, length, timestamp, codec, checksum, entry_gen = ENTRY.unpack_from(index, slot * 32)
            struct.pack_into(">I", timestamps, slot * 4, timestamp)
            require(0 <= entry_gen <= generation, "Invalid entry generation")
            if offset == length == codec == checksum == 0:
                continue
            require(0 < length <= MAX_PAYLOAD and codec in (1, 2, 3, 4, 5), "Invalid payload length/codec (max 128 MiB)")
            require(codec not in (4, 5) or version == 2 and descriptor[0] != 0,
                    "Region-reference codec 4/5 requires a dictionary")
            require(offset >= DATA_START and offset % ALIGN == 0 and offset + length <= info.st_size,
                    "Invalid payload bounds/alignment")
            spans.append((offset, offset + ((length + 511) // 512) * 512, slot, length, codec, checksum))
        previous = DATA_START
        for offset, end, *_ in sorted(spans):
            require(offset >= previous, "Overlapping CQS payload/dictionary allocations")
            previous = end
        chunks = []
        for offset, _, slot, length, codec, checksum in spans:
            stream.seek(offset)
            payload = stream.read(length)
            require(len(payload) == length and zlib.crc32(payload) == checksum,
                    "Region dictionary CRC mismatch" if slot == -1 else "Payload CRC mismatch")
            if slot == -1:
                dictionary = payload
            else:
                chunks.append((slot, payload, codec, None))
    result = bytes(timestamps), chunks
    return (*result, dictionary) if with_dictionary else result


def write_region(path, timestamps, chunks):
    path = Path(path)
    coords = coordinates(path)
    chunks = list(chunks)
    require(len(timestamps) == 4096, "Invalid timestamp table size")
    seen = set()
    for slot, payload, codec, _ in chunks:
        require(codec not in (4, 5), "Cannot repackage region-reference codec 4/5 without its original CQS dictionary")
        require(0 <= slot < 1024 and slot not in seen, "Invalid or duplicate compact slot")
        require(0 < len(payload) <= MAX_PAYLOAD and codec in (1, 2, 3), "Invalid payload (max 128 MiB)")
        seen.add(slot)
    identifier = uuid.uuid4().bytes
    table = bytearray(TABLE_SIZE)
    for slot in range(1024):
        table[slot * 32 + 12:slot * 32 + 16] = timestamps[slot * 4:slot * 4 + 4]
    with path.open("xb") as stream:
        stream.write(bytes(DATA_START))
        for slot, payload, codec, _ in chunks:
            offset = stream.tell()
            timestamp = struct.unpack_from(">I", timestamps, slot * 4)[0]
            ENTRY.pack_into(table, slot * 32, offset, len(payload), timestamp, codec, zlib.crc32(payload), 1)
            stream.write(payload)
            stream.write(bytes((-len(payload)) % ALIGN))
        marker = commit(1, 0, zlib.crc32(table), identifier)
        stream.seek(0)
        stream.write(identity(b"CSQREG01", coords, identifier))
        stream.write(marker * 2)
        stream.write(table)
        stream.write(bytes(TABLE_SIZE))
        stream.flush()
        os.fsync(stream.fileno())
    with path.with_name(path.name + ".owner").open("xb") as stream:
        stream.write(identity(b"CSQOWN01", coords, identifier))
        stream.flush()
        os.fsync(stream.fileno())
