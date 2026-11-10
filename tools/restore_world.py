"""Offline ChunkSqueeze v1/v2 restoration, using only the Python standard library."""

import argparse
import importlib.util
from collections.abc import MutableMapping
import os
from pathlib import Path
import re
import shutil
import stat
import struct
import sys
import tempfile
import zlib

MAX_BYTES = 64 * 1024 * 1024
MAX_DICTIONARY_RAW = 4 * 1024 * 1024
MAX_SECTION_DICTIONARY_RAW = 8 * 1024 * 1024
MAX_DICTIONARY_PACKED = 16 * 1024 * 1024
KEY = b"chunksqueeze:storage"
SECTION_KEY = b"chunksqueeze:sections"
MARKER = "CHUNKSQUEEZE_FORMAT.txt"
SECTOR = 4096


class FormatError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise FormatError(message)


def decode_mutf8(data):
    # 按 DataInputStream.readUTF 接受过长编码及 UTF-16 代理项，拒绝四字节 UTF-8。
    if data.isascii():
        return data.decode("ascii")
    units = []
    index = 0
    while index < len(data):
        first = data[index]
        index += 1
        if first < 128:
            units.append(chr(first))
        elif 0xC0 <= first <= 0xDF:
            require(index < len(data) and data[index] & 0xC0 == 0x80, "Invalid NBT modified UTF-8")
            units.append(chr(((first & 31) << 6) | (data[index] & 63)))
            index += 1
        elif 0xE0 <= first <= 0xEF:
            require(index + 1 < len(data) and data[index] & 0xC0 == 0x80
                    and data[index + 1] & 0xC0 == 0x80, "Invalid NBT modified UTF-8")
            units.append(chr(((first & 15) << 12) | ((data[index] & 63) << 6)
                             | (data[index + 1] & 63)))
            index += 2
        else:
            raise FormatError("Invalid NBT modified UTF-8")
    return "".join(units)


class Compound(MutableMapping):
    def __init__(self):
        self.entries = {}

    def __getitem__(self, name):
        return self.entries[decode_mutf8(name)][1]

    def __setitem__(self, name, tag):
        key = decode_mutf8(name)
        original = self.entries[key][0] if key in self.entries else name
        self.entries[key] = (original, tag)

    def __delitem__(self, name):
        del self.entries[decode_mutf8(name)]

    def __iter__(self):
        return (name for name, _ in self.entries.values())

    def __len__(self):
        return len(self.entries)

    def items(self):
        return self.entries.values()


class Reader:
    def __init__(self, data):
        self.data = data
        self.pos = 0
        self.nodes = 0

    def take(self, size):
        require(0 <= size <= len(self.data) - self.pos, "Truncated binary data")
        result = self.data[self.pos:self.pos + size]
        self.pos += size
        return result

    def number(self, fmt):
        return struct.unpack(fmt, self.take(struct.calcsize(fmt)))[0]

    def string(self):
        data = self.take(self.number(">H"))
        decode_mutf8(data)
        return data

    def bounded(self, maximum, field):
        require(maximum >= 0, "Invalid " + field)
        value = 0
        for shift in range(0, 35, 7):
            part = self.number(">B")
            value |= (part & 127) << shift
            if not part & 128:
                require(value <= maximum and not (shift > 0 and part == 0),
                        "Invalid " + field)
                return value
        raise FormatError("Invalid " + field)

    def end(self):
        require(self.pos == len(self.data), "Trailing binary data")


# 标量及字段名保留原始字节；字符串校验和字段查找采用 Java modified UTF-8 语义。
class Tag:
    def __init__(self, kind, value):
        self.kind = kind
        self.value = value


def read_tag(reader, kind, depth=0):
    require(kind not in (9, 10) or depth < 512, "NBT nesting exceeds Java depth limit")
    reader.nodes += 1
    require(reader.nodes <= 2_000_000, "NBT tag count exceeds safety limit")
    require(1 <= kind <= 12, "Invalid NBT tag type")
    start = reader.pos
    fixed = {1: 1, 2: 2, 3: 4, 4: 8, 5: 4, 6: 8}
    if kind in fixed:
        reader.take(fixed[kind])
    elif kind in (7, 11, 12):
        size = reader.number(">i")
        require(size >= 0, "Negative NBT array length")
        reader.take(size * {7: 1, 11: 4, 12: 8}[kind])
    elif kind == 8:
        reader.string()
    elif kind == 9:
        child_kind = reader.number(">B")
        count = reader.number(">i")
        require(0 <= child_kind <= 12 and count >= 0 and (child_kind != 0 or count == 0),
                "Invalid NBT list")
        require(count <= len(reader.data) - reader.pos, "Truncated NBT list")
        # 避免列表推导额外递归帧，512 层容器在 Python 默认递归限制内可读写。
        children = []
        for _ in range(count):
            children.append(read_tag(reader, child_kind, depth + 1))
        return Tag(kind, (child_kind, children))
    elif kind == 10:
        entries = Compound()
        while True:
            child_kind = reader.number(">B")
            if child_kind == 0:
                break
            name = reader.string()
            require(name not in entries, "Duplicate NBT compound key")
            entries[name] = read_tag(reader, child_kind, depth + 1)
        return Tag(kind, entries)
    return Tag(kind, reader.data[start:reader.pos])


def read_root(reader):
    require(reader.number(">B") == 10, "NBT root must be a compound")
    name = reader.string()
    return name, read_tag(reader, 10)


def put_string(data):
    require(len(data) <= 65535, "NBT string too long")
    return struct.pack(">H", len(data)) + data


def append_nbt(out, data):
    # 在追加前限制输出，重复字典引用不能先膨胀成数 GiB 再被拒绝。
    require(len(data) <= MAX_BYTES - len(out), "Restored NBT exceeds 64 MiB safety limit")
    out.extend(data)


def write_tag(tag, out, depth=0):
    require(tag.kind not in (9, 10) or depth < 512, "NBT nesting exceeds Java depth limit")
    if tag.kind == 10:
        for name, child in tag.value.items():
            append_nbt(out, bytes([child.kind]))
            append_nbt(out, put_string(name))
            write_tag(child, out, depth + 1)
        append_nbt(out, b"\0")
    elif tag.kind == 9:
        child_kind, children = tag.value
        append_nbt(out, struct.pack(">Bi", child_kind, len(children)))
        for child in children:
            write_tag(child, out, depth + 1)
    else:
        append_nbt(out, tag.value)


def serialize(name, root):
    out = bytearray()
    append_nbt(out, b"\x0a" + put_string(name))
    write_tag(root, out)
    return bytes(out)


def member(tag, name, kind):
    require(tag.kind == 10 and name in tag.value and tag.value[name].kind == kind,
            "Missing or invalid NBT field: " + name.decode("ascii"))
    return tag.value[name]


def compound_list(tag, maximum, field):
    kind, children = tag.value
    require(kind == 10 and 1 <= len(children) <= maximum, "Invalid " + field)
    return children


def pack_values(values, palette_size):
    bits = max(4, (palette_size - 1).bit_length())
    per_long = 64 // bits
    longs = [0] * ((4096 + per_long - 1) // per_long)
    for i, value in enumerate(values):
        require(0 <= value < palette_size, "State index out of range")
        longs[i // per_long] |= value << ((i % per_long) * bits)
    return b"".join(struct.pack(">Q", value) for value in longs)


def decode_indices(data, palette_size):
    require(1 <= palette_size <= 4096, "Invalid palette size")
    reader = Reader(data)
    mode = reader.number(">B")
    if mode == 0:
        require(palette_size == 1, "Invalid uniform section")
        result = None
    elif mode == 1:
        count = reader.bounded(4096, "long array")
        result = reader.take(count * 8)
        bits = max(4, (palette_size - 1).bit_length())
        per_long = 64 // bits
        require(count == (4096 + per_long - 1) // per_long, "Invalid packed array length")
        longs = struct.unpack(">" + "Q" * count, result)
        for i in range(4096):
            value = (longs[i // per_long] >> ((i % per_long) * bits)) & ((1 << bits) - 1)
            require(value < palette_size, "Invalid packed state index")
    else:
        values = []
        if mode == 2:
            bits = max(1, (palette_size - 1).bit_length())
            buffer = available = 0
            for _ in range(4096):
                while available < bits:
                    buffer |= reader.number(">B") << available
                    available += 8
                values.append(buffer & ((1 << bits) - 1))
                buffer >>= bits
                available -= bits
        elif mode == 3:
            while len(values) < 4096:
                run = reader.bounded(4096 - len(values), "run length")
                require(run != 0, "Empty run")
                value = reader.bounded(palette_size - 1, "state index")
                values.extend([value] * run)
        elif mode == 4:
            default = reader.bounded(palette_size - 1, "default state")
            values = [default] * 4096
            count = reader.bounded(4096, "exceptions")
            previous = -1
            for _ in range(count):
                gap = reader.bounded(4096 - previous - 2, "position gap")
                previous += gap + 1
                values[previous] = reader.bounded(palette_size - 1, "state index")
        elif mode == 5:
            values = [0] * 4096
            for plane in range(max(1, (palette_size - 1).bit_length())):
                packed = reader.take(512)
                for i in range(4096):
                    values[i] |= ((packed[i // 8] >> (i % 8)) & 1) << plane
        else:
            raise FormatError("Unknown index encoding: " + str(mode))
        result = pack_values(values, palette_size)
    reader.end()
    return result


class Dictionary(tuple):
    # 保持旧调用者二项解包，完整区段通过属性访问。
    def __new__(cls, identifier, states, sections):
        result = super().__new__(cls, (identifier, states))
        result.sections = sections
        return result


def read_dictionary(compressed):
    """Return a (UUID bytes, state tags) tuple with a sections attribute."""
    if compressed is None:
        return None
    require(0 < len(compressed) <= MAX_DICTIONARY_PACKED, "Invalid region dictionary size (max 16 MiB)")
    raw = decompress(compressed, 2, MAX_SECTION_DICTIONARY_RAW)
    reader = Reader(raw)
    _, root = read_root(reader)
    reader.end()
    version = struct.unpack(">i", member(root, b"version", 3).value)[0]
    require(version in (1, 2), "Unsupported region dictionary version")
    require(version != 1 or len(raw) <= MAX_DICTIONARY_RAW,
            "Region dictionary exceeds 4 MiB safety limit")
    identifier = member(root, b"idMost", 4).value + member(root, b"idLeast", 4).value
    kind, states = member(root, b"states", 9).value
    require(len(states) <= 65536 and (not states or kind == 10), "Invalid region dictionary states")
    sections = []
    if version == 2:
        kind, sections = member(root, b"sections", 9).value
        require(len(sections) <= 4096 and (not sections or kind == 10),
                "Invalid region dictionary sections")
    return Dictionary(identifier, states, sections)


def restore_nbt(data, dictionary=None, require_shared=False, require_sections=False):
    require(len(data) <= MAX_BYTES, "NBT exceeds 64 MiB safety limit")
    reader = Reader(data)
    name, root = read_root(reader)
    reader.end()
    has_states = KEY in root.value
    has_sections = SECTION_KEY in root.value
    require(not require_shared or has_states, "Shared codec 4 is missing its version 2 marker")
    require(not require_shared or not has_sections, "Shared codec 4 forbids section markers")
    require(not require_sections or has_sections, "Section codec 5 is missing its section marker")
    if has_states:
        restore_states(root, dictionary, require_shared)
    if has_sections:
        restore_sections(root, dictionary)
    if not has_states and not has_sections:
        return data, False
    return serialize(name, root), True


def restore_states(root, dictionary, require_shared):
    status = member(root, b"Status", 8)
    require(len(status.value) > 2, "Encoded chunk has empty Status")
    marker = member(root, KEY, 10)
    version = struct.unpack(">i", member(marker, b"version", 3).value)[0]
    require(version in (1, 2), "Unsupported ChunkSqueeze format version")
    require(not require_shared or version == 2, "Shared codec 4 requires a version 2 marker")
    require(version != 2 or dictionary is not None, "Region dictionary required for ChunkSqueeze v2")
    expected_crc = struct.unpack(">q", member(marker, b"crc32", 4).value)[0]
    payload = member(marker, b"payload", 7).value[4:]
    require(len(payload) <= MAX_BYTES, "ChunkSqueeze payload exceeds limit")
    require(zlib.crc32(payload) == expected_crc, "ChunkSqueeze checksum mismatch")
    payload_reader = Reader(payload)
    require(payload_reader.number(">I") == (0x43535131 if version == 1 else 0x43535132),
            "Invalid ChunkSqueeze payload magic")
    if version == 1:
        _, local = read_root(payload_reader)
        states = compound_list(member(local, b"states", 9), 65536, "state dictionary")
    else:
        identifier, region_states = dictionary
        require(payload_reader.take(16) == identifier, "Region dictionary UUID mismatch")
        size = payload_reader.bounded(65536, "local dictionary size")
        require(size != 0, "Empty local dictionary")
        states = [region_states[payload_reader.bounded(len(region_states) - 1, "region dictionary index")]
                  for _ in range(size)]
    sections = compound_list(member(root, b"sections", 9), 1024, "section count")
    count = payload_reader.bounded(len(sections), "section records")
    require(count != 0, "Empty section records")
    seen = set()
    for _ in range(count):
        index = payload_reader.bounded(len(sections) - 1, "section index")
        require(index not in seen, "Duplicate section record")
        seen.add(index)
        section = sections[index]
        require(b"block_states" not in section.value, "Conflicting block state data")
        size = payload_reader.bounded(4096, "palette size")
        require(size != 0, "Empty palette")
        palette = [states[payload_reader.bounded(len(states) - 1, "dictionary index")]
                   for _ in range(size)]
        length = payload_reader.bounded(64 * 1024, "encoded indices length")
        require(length != 0, "Invalid encoded indices length")
        indices = decode_indices(payload_reader.take(length), size)
        restored = {b"palette": Tag(9, (10, palette))}
        if indices is not None:
            restored[b"data"] = Tag(12, struct.pack(">i", len(indices) // 8) + indices)
        section.value[b"block_states"] = Tag(10, restored)
    payload_reader.end()
    del root.value[KEY]


def restore_sections(root, dictionary):
    require(dictionary is not None, "Region dictionary required for section references")
    marker = member(root, SECTION_KEY, 10)
    require(len(marker.value) == 3, "Invalid ChunkSqueeze section marker fields")
    require(struct.unpack(">i", member(marker, b"version", 3).value)[0] == 1,
            "Unsupported ChunkSqueeze section format version")
    expected_crc = struct.unpack(">q", member(marker, b"crc32", 4).value)[0]
    payload = member(marker, b"payload", 7).value[4:]
    require(zlib.crc32(payload) == expected_crc, "ChunkSqueeze section checksum mismatch")
    reader = Reader(payload)
    require(reader.number(">I") == 0x43535331, "Invalid ChunkSqueeze section payload magic")
    require(reader.take(16) == dictionary[0], "Region dictionary UUID mismatch")
    sections = compound_list(member(root, b"sections", 9), 1024, "section count")
    pool = getattr(dictionary, "sections", ())
    count = reader.bounded(len(sections), "section references")
    require(count != 0, "Empty section references")
    seen = set()
    for _ in range(count):
        index = reader.bounded(len(sections) - 1, "section index")
        require(index not in seen, "Duplicate section reference")
        seen.add(index)
        pool_id = reader.bounded(len(pool) - 1, "section pool index")
        require(not sections[index].value, "Nonempty section placeholder")
        # 原样引用完整 compound，最终序列化逐次计算深度与展开大小。
        sections[index] = pool[pool_id]
    reader.end()
    del root.value[SECTION_KEY]


def reject_link(path):
    info = path.lstat()
    require(not stat.S_ISLNK(info.st_mode)
            and not getattr(info, "st_file_attributes", 0) & 0x400,
            "Symlinks and reparse points are not supported: " + str(path))
    return info


def external_path(region, slot):
    match = re.fullmatch(r"r\.(-?\d+)\.(-?\d+)\.mca", region.name, flags=re.ASCII)
    require(match is not None, "External chunks require an r.X.Z.mca filename")
    rx, rz = map(int, match.groups())
    x, z = rx * 32 + slot % 32, rz * 32 + slot // 32
    require(-(2 ** 31) <= x < 2 ** 31 and -(2 ** 31) <= z < 2 ** 31,
            "External chunk coordinates out of range")
    # 文件名只由经过校验的整数生成，绝不采用 NBT 内提供的路径。
    return region.with_name(f"c.{x}.{z}.mcc")


def decompress(data, compression, maximum=MAX_BYTES):
    limit = str(maximum // (1024 * 1024)) + " MiB safety limit"
    if compression == 3:
        require(len(data) <= maximum, "NBT exceeds " + limit)
        return data
    require(compression in (1, 2), "Unsupported MCA compression type: " + str(compression))
    decoder = zlib.decompressobj(31 if compression == 1 else 15)
    result = decoder.decompress(data, maximum + 1)
    require(len(result) <= maximum and not decoder.unconsumed_tail,
            "Decompressed NBT exceeds " + limit)
    require(decoder.eof and not decoder.unused_data, "Truncated or trailing compressed data")
    return result


def load_compact():
    # -I 下只加载脚本旁的可信模块；普通 MCA 还原仍可单文件运行。
    sys.dont_write_bytecode = True
    spec = importlib.util.spec_from_file_location("compact_region", Path(__file__).with_name("compact_region.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.FormatError = FormatError
    return module


def read_region(path, raw=False):
    info = reject_link(path)
    require(stat.S_ISREG(info.st_mode) and (info.st_size == 0 or info.st_size >= 8192),
            "Invalid MCA file size: " + str(path))
    if info.st_size == 0:
        return bytes(4096), []
    chunks = []
    with path.open("rb") as stream:
        header = stream.read(8192)
        spans = []
        for slot in range(1024):
            location = struct.unpack_from(">I", header, slot * 4)[0]
            if location == 0:
                continue
            offset, sectors = location >> 8, location & 255
            # 允许文件最后一个扇区缺少填充，但不能声明完全不存在的扇区。
            require(offset >= 2 and sectors > 0
                    and offset + sectors <= (info.st_size + SECTOR - 1) // SECTOR,
                    "Invalid MCA sector bounds at slot " + str(slot))
            spans.append((offset, offset + sectors, slot))
        previous_end = 2
        for offset, end, _ in sorted(spans):
            require(offset >= previous_end, "Overlapping MCA sectors")
            previous_end = end
        for offset, end, slot in spans:
            stream.seek(offset * SECTOR)
            record_header = stream.read(5)
            require(len(record_header) == 5, "Truncated MCA chunk header")
            length, kind = struct.unpack(">IB", record_header)
            require(1 <= length <= (end - offset) * SECTOR - 4, "Invalid MCA chunk length")
            require(offset * SECTOR + 4 + length <= info.st_size, "Truncated MCA chunk payload")
            compression = kind & 127
            require(compression in (1, 2, 3), "Unsupported MCA compression type: " + str(compression))
            external = None
            if kind & 128:
                require(length == 1 and end - offset == 1, "Invalid external chunk stub")
                external = external_path(path, slot)
                external_info = reject_link(external)
                require(stat.S_ISREG(external_info.st_mode) and external_info.st_size <= 128 * 1024 * 1024,
                        "Invalid or oversized external chunk: " + str(external))
                packed = external.read_bytes()
            else:
                packed = stream.read(length - 1)
                require(len(packed) == length - 1, "Truncated MCA chunk")
            require(0 < len(packed) <= 128 * 1024 * 1024, "Invalid payload (max 128 MiB)")
            try:
                nbt, changed = restore_nbt(decompress(packed, compression))
            except (FormatError, zlib.error) as exc:
                raise FormatError(f"{path}, slot {slot}: {exc}") from exc
            # 迁移同样完整验证 NBT，但只保存原始压缩字节。
            chunks.append((slot, packed, compression, external) if raw else (slot, nbt, changed, external))
    return header[4096:8192], chunks


def restore_compact_chunk(packed, codec, dictionary):
    return restore_nbt(decompress(packed, 2 if codec in (4, 5) else codec),
                       dictionary if codec in (4, 5) else None,
                       require_shared=codec == 4, require_sections=codec == 5)


def restore_region(source, destination):
    if source.suffix.lower() == ".cqs":
        timestamps, packed_chunks, compressed_dictionary = load_compact().read_region(source, with_dictionary=True)
        dictionary = read_dictionary(compressed_dictionary)
        chunks = []
        for slot, packed, codec, _ in packed_chunks:
            try:
                nbt, changed = restore_compact_chunk(packed, codec, dictionary)
            except (FormatError, zlib.error) as exc:
                raise FormatError(f"{source}, slot {slot}: {exc}") from exc
            chunks.append((slot, nbt, changed, None))
    else:
        timestamps, chunks = read_region(source)
    if source.suffix.lower() != ".cqs" and not any(changed for _, _, changed, _ in chunks):
        shutil.copy2(source, destination)
        return 0
    header = bytearray(4096) + timestamps
    offset = 2
    obsolete_external = []
    with destination.open("wb") as stream:
        stream.write(header)
        for slot, nbt, _, old_external in chunks:
            packed = zlib.compress(nbt)
            sectors = (len(packed) + 5 + SECTOR - 1) // SECTOR
            if sectors > 255:
                external = external_path(destination, slot)
                require(not external.exists() or old_external is not None,
                        "External output collides with an unrelated file: " + str(external))
                external.write_bytes(packed)
                record = struct.pack(">IB", 1, 130)
                sectors = 1
            else:
                record = struct.pack(">IB", len(packed) + 1, 2) + packed
                if old_external is not None:
                    obsolete_external.append(destination.with_name(old_external.name))
            require(offset < 2 ** 24, "MCA sector offset exceeds format limit")
            struct.pack_into(">I", header, slot * 4, (offset << 8) | sectors)
            stream.write(record)
            stream.write(b"\0" * (sectors * SECTOR - len(record)))
            offset += sectors
        stream.seek(0)
        stream.write(header)
    # 只在内联区域成功写完后删除其明确引用过的副本文件，绝不删除源文件。
    for external in obsolete_external:
        external.unlink(missing_ok=True)
    return sum(changed for _, _, changed, _ in chunks)


def restore_world(source, destination, compact=False):
    source = Path(source).absolute()
    destination = Path(destination).absolute()
    for path in (source, *source.parents, *destination.parents):
        if os.path.lexists(path):
            reject_link(path)
    require(not os.path.lexists(destination), "DEST already exists")
    source = source.resolve(strict=True)
    destination = destination.resolve(strict=False)
    require(source.is_dir(), "SOURCE must be a directory")
    require(source != destination and source not in destination.parents and destination not in source.parents,
            "SOURCE and DEST must not overlap")
    require(not os.path.lexists(destination), "DEST already exists")
    files, directories = [], []
    # 预检整棵目录；复制和外部区块读取都不能跟随链接越出源目录。
    def walk_error(error):
        raise error
    for base, dirs, names in os.walk(source, followlinks=False, onerror=walk_error):
        base = Path(base)
        for name in dirs:
            path = base / name
            require(stat.S_ISDIR(reject_link(path).st_mode), "Not a regular directory: " + str(path))
            directories.append(path.relative_to(source))
        for name in names:
            path = base / name
            require(stat.S_ISREG(reject_link(path).st_mode), "Not a regular file: " + str(path))
            files.append(path.relative_to(source))
    def migration_backup(relative):
        return any(part.lower() == ".chunksqueeze-migration" for part in relative.parts)

    backup_files = {p for p in files if migration_backup(p.parent)}
    active_files = [p for p in files if p not in backup_files]
    for relative in active_files:
        require(not relative.name.lower().endswith(".cqs.migrating"),
                "Pending migration transaction: " + str(relative)
                + ". Recover transactions with ChunkSqueeze 0.3+ before exporting this world.")
    cqs_files = [p for p in active_files if p.suffix.lower() == ".cqs"]
    owners = [p for p in active_files if p.name.lower().endswith(".cqs.owner")]
    storage = [p for p in active_files if p.suffix.lower() == ".mca"] + cqs_files + owners
    backend = load_compact() if compact or cqs_files or owners else None
    if backend is not None:
        groups = {}
        for relative in storage:
            key = (relative.parent, backend.coordinates(relative))
            kind = "owner" if relative in owners else relative.suffix.lower()[1:]
            group = groups.setdefault(key, {})
            require(kind not in group, "Duplicate region coordinates: " + str(relative))
            group[kind] = relative
        for group in groups.values():
            if "cqs" in group or "owner" in group:
                require(set(group) == {"cqs", "owner"}, "CQS requires owner and forbids same-region MCA")
                require(group["owner"].name == group["cqs"].name + ".owner", "Mismatched owner filename")
    flat = (not any(not migration_backup(p) for p in directories)
            and source.name.lower() not in ("entities", "poi")
            and not (source / "level.dat").exists())
    converted = {p for p in storage if p.suffix.lower() == ".mca"
                 and (not compact or p.parent.name == "region" or flat or (p.parent == Path(".") and source.name == "region"))}
    handled = converted | set(cqs_files) | set(owners)
    destination.parent.mkdir(parents=True, exist_ok=True)
    prefix = ".chunksqueeze-compact-" if compact else ".chunksqueeze-restore-"
    temporary = Path(tempfile.mkdtemp(prefix=prefix, dir=destination.parent))
    total = 0
    try:
        for relative in directories:
            (temporary / relative).mkdir()
        for relative in files:
            if relative not in handled and (compact or relative.name != MARKER or relative in backup_files):
                shutil.copy2(source / relative, temporary / relative)
        for relative in cqs_files:
            if compact:
                _, chunks, compressed_dictionary = backend.read_region(source / relative, with_dictionary=True)
                dictionary = read_dictionary(compressed_dictionary)
                for slot, packed, codec, _ in chunks:
                    try:
                        restore_compact_chunk(packed, codec, dictionary)
                    except (FormatError, zlib.error) as exc:
                        raise FormatError(f"{source / relative}, slot {slot}: {exc}") from exc
                shutil.copy2(source / relative, temporary / relative)
                owner = relative.with_name(relative.name + ".owner")
                shutil.copy2(source / owner, temporary / owner)
            else:
                total += restore_region(source / relative, (temporary / relative).with_suffix(".mca"))
        for relative in sorted(converted):
            if compact:
                timestamps, chunks = read_region(source / relative, raw=True)
                backend.write_region((temporary / relative).with_suffix(".cqs"), timestamps, chunks)
                for _, _, _, external in chunks:
                    if external is not None:
                        (temporary / relative.parent / external.name).unlink()
                total += len(chunks)
            else:
                total += restore_region(source / relative, temporary / relative)
        if compact:
            for directory in {p.parent for p in converted | set(cqs_files)}:
                (temporary / directory / MARKER).write_text(
                    "This directory contains ChunkSqueeze compact region format 1/2 (.cqs).\n"
                    "Requires ChunkSqueeze 0.2+ for format 1; shared states require 0.4.0+, exact sections require 0.5.0+.\n"
                    "Before uninstalling, stop the server and run restore_world.py SOURCE DEST\n"
                    "to create a standard MCA world, then use the restored copy.\n",
                    encoding="utf-8")
        require(not os.path.lexists(destination), "DEST appeared during conversion")
        temporary.rename(destination)
    except BaseException:
        shutil.rmtree(temporary)
        raise
    return total


def main():
    parser = argparse.ArgumentParser(description="Restore a stopped ChunkSqueeze world to a new directory.",
                                     epilog="Rejects links and unsupported compression. Payload limit: 128 MiB; restored NBT limit: 64 MiB.")
    parser.add_argument("source", metavar="SOURCE")
    parser.add_argument("destination", metavar="DEST")
    args = parser.parse_args()
    try:
        total = restore_world(args.source, args.destination)
    except (OSError, ValueError, zlib.error, struct.error, RecursionError) as exc:
        print("Restoration failed: " + str(exc), file=sys.stderr)
        return 1
    print(f"Restored {total} encoded chunks to {args.destination}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
