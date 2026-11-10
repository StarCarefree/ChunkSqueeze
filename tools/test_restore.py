"""Run with: python -I -X utf8 tools/test_restore.py"""

import gzip
import hashlib
import importlib.util
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
import zlib

# 隔离模式不会添加脚本目录；只按固定路径加载受控工具，不导入样本目录。
sys.dont_write_bytecode = True
TOOL = Path(__file__).with_name("restore_world.py")
spec = importlib.util.spec_from_file_location("restore_world", TOOL)
restore = importlib.util.module_from_spec(spec)
spec.loader.exec_module(restore)


def varint(value):
    result = bytearray()
    while value >= 128:
        result.append((value & 127) | 128)
        value >>= 7
    result.append(value)
    return bytes(result)


def string(value):
    return struct.pack(">H", len(value)) + value


def named(kind, name, value):
    return bytes([kind]) + string(name) + value


def compound(*entries):
    return b"".join(entries) + b"\0"


def tag_list(kind, entries):
    return struct.pack(">Bi", kind, len(entries)) + b"".join(entries)


def root(body, name=b""):
    return b"\x0a" + string(name) + body


def byte_array(data):
    return struct.pack(">i", len(data)) + data


def bit_planes(values, size):
    return b"\x05" + b"".join(
        sum(((value >> plane) & 1) << index for index, value in enumerate(values)).to_bytes(512, "little")
        for plane in range(max(1, (size - 1).bit_length())))


def vanilla(values, size):
    bits = max(4, (size - 1).bit_length())
    per = 64 // bits
    words = [sum(value << (i * bits) for i, value in enumerate(values[start:start + per]))
             for start in range(0, 4096, per)]
    return b"".join(struct.pack(">Q", word) for word in words)


MUTF = b"A\xc0\x80\xed\xa0\xbd\xed\xb8\x80Z"
# 包含所有 NBT 类型、NaN 原始位模式、非 BMP、非默认类型的空列表。
EXTRA = compound(
    named(1, b"byte", b"\xff"), named(2, b"short", b"\x80\x01"),
    named(3, b"int", struct.pack(">i", -123)),
    named(4, b"long", struct.pack(">q", -(2 ** 63))),
    named(5, b"float", bytes.fromhex("7fa12345")),
    named(6, b"double", bytes.fromhex("7ff123456789abcd")),
    named(7, b"bytes", byte_array(b"\x00\xff")),
    named(8, MUTF, string(MUTF)),
    named(8, b"utf8", string("世界".encode("utf-8") + b"\xed\xa0\xbd\xed\xb8\x80")),
    named(9, b"empty", tag_list(4, [])),
    named(9, b"end_list", tag_list(0, [])),
    named(9, b"nested", tag_list(9, [tag_list(2, [b"\xff\xff"])])),
    named(10, b"compound", compound()),
    named(11, b"ints", struct.pack(">iii", 2, -1, 2)),
    named(12, b"longs", struct.pack(">iqq", 2, -1, 2)),
)


def fixture(indices=b"\0", size=1, extra=EXTRA, version=1, crc_delta=0, payload=None,
            key_encoder=lambda name: name, status=b"minecraft:full"):
    def field(kind, name, value):
        return named(kind, key_encoder(name), value)
    states = [compound(field(8, b"Name", string(f"test:block_{i}".encode("ascii"))))
              for i in range(size)]
    if payload is None:
        dictionary = root(compound(field(9, b"states", tag_list(10, states))))
        payload = (b"CSQ1" + dictionary + varint(1) + varint(0) + varint(size)
                   + b"".join(varint(i) for i in range(size)) + varint(len(indices)) + indices)
    marker = compound(field(3, b"version", struct.pack(">i", version)),
                      field(4, b"crc32", struct.pack(">q", zlib.crc32(payload) ^ crc_delta)),
                      field(7, b"payload", byte_array(payload)))
    section = compound(field(1, b"Y", b"\xff"))
    status_field = b"" if status is None else field(8, b"Status", string(status))
    data = root(compound(field(9, b"sections", tag_list(10, [section])),
                         field(10, b"extra", extra), status_field,
                         field(10, restore.KEY, marker)), MUTF)
    return data, states, payload


def expected(states, packed=None, extra=EXTRA):
    block = named(9, b"palette", tag_list(10, states))
    if packed is not None:
        block += named(12, b"data", struct.pack(">i", len(packed) // 8) + packed)
    section = compound(named(1, b"Y", b"\xff"), named(10, b"block_states", compound(block)))
    return root(compound(named(9, b"sections", tag_list(10, [section])),
                         named(10, b"extra", extra),
                         named(8, b"Status", string(b"minecraft:full"))), MUTF)


def region(path, entries):
    header = bytearray(8192)
    header[4096:] = bytes(range(256)) * 16
    records = bytearray()
    offset = 2
    for slot, nbt, compression, external in entries:
        packed = {1: gzip.compress, 2: zlib.compress, 3: lambda x: x}[compression](nbt)
        if external:
            # 测试样本独立计算坐标，避免用被测路径函数构造期望。
            _, rx, rz, _ = path.name.split(".")
            sidecar = path.with_name(f"c.{int(rx) * 32 + slot % 32}.{int(rz) * 32 + slot // 32}.mcc")
            sidecar.write_bytes(packed)
            record = struct.pack(">IB", 1, compression | 128)
        else:
            record = struct.pack(">IB", len(packed) + 1, compression) + packed
        sectors = (len(record) + 4095) // 4096
        assert sectors <= 255
        struct.pack_into(">I", header, slot * 4, (offset << 8) | sectors)
        records.extend(record + b"\0" * (sectors * 4096 - len(record)))
        offset += sectors
    path.write_bytes(header + records)


def snapshot(directory):
    return {str(p.relative_to(directory)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in directory.rglob("*") if p.is_file()}


class RestoreTests(unittest.TestCase):
    def test_all_modes_and_raw_padding(self):
        data, states, _ = fixture()
        self.assertEqual(restore.restore_nbt(data), (expected(states), True))
        values = [i % 17 for i in range(4096)]
        packed = vanilla(values, 17)
        # 模式 1 必须保留每个 long 的填充位。
        raw = bytearray(packed)
        raw[0] |= 0xF0
        modes = [(b"\x01" + varint(len(raw) // 8) + raw, bytes(raw))]
        continuous = sum(value << (i * 5) for i, value in enumerate(values))
        modes.append((b"\x02" + continuous.to_bytes(2560, "little"), packed))
        runs = b"\x03" + varint(2048) + varint(16) + varint(2048) + varint(2)
        modes.append((runs, vanilla([16] * 2048 + [2] * 2048, 17)))
        sparse_values = [3] * 4096
        sparse_values[0], sparse_values[4095] = 16, 0
        sparse = b"\x04" + varint(3) + varint(2) + varint(0) + varint(16) + varint(4094) + varint(0)
        modes.append((sparse, vanilla(sparse_values, 17)))
        modes.append((bit_planes(values, 17), packed))
        for indices, want in modes:
            with self.subTest(mode=indices[0]):
                data, states, _ = fixture(indices, 17)
                self.assertEqual(restore.restore_nbt(data), (expected(states, want), True))

    def test_mutf8_semantic_keys_and_invalid_strings(self):
        for width in (2, 3):
            def overlong(name):
                char = name[0]
                prefix = bytes((0xC0 | (char >> 6), 0x80 | (char & 63))) if width == 2 else bytes((0xE0, 0x80 | (char >> 6), 0x80 | (char & 63)))
                return prefix + name[1:]
            with self.subTest(width=width):
                encoded, states, _ = fixture(key_encoder=overlong)
                want = expected(states)
                for key in (b"Y", b"sections", b"extra", b"Status"):
                    want = want.replace(string(key), string(overlong(key)))
                self.assertEqual(restore.restore_nbt(encoded), (want, True))
                name, tag = restore.read_root(restore.Reader(encoded))
                sections = restore.member(tag, b"sections", 9).value[1]
                sections[0].value[overlong(b"block_states")] = restore.Tag(10, {})
                with self.assertRaisesRegex(restore.FormatError, "Conflicting"):
                    restore.restore_nbt(restore.serialize(name, tag))
                duplicate = root(compound(named(1, b"key", b"\0"), named(1, overlong(b"key"), b"\0")))
                with self.assertRaisesRegex(restore.FormatError, "Duplicate"):
                    restore.restore_nbt(duplicate)
        for invalid in (b"\x80", b"\xc1", b"\xc1A", b"\xe0\x80", b"\xe0A\x80", b"\xff", "😀".encode("utf-8")):
            for data in (root(compound(), invalid), root(compound(named(1, invalid, b"\0"))),
                         root(compound(named(8, b"text", string(invalid))))):
                with self.subTest(invalid=invalid), self.assertRaisesRegex(restore.FormatError, "modified UTF-8"):
                    restore.restore_nbt(data)
        self.assertEqual(restore.decode_mutf8(MUTF), "A\0" + chr(0xD83D) + chr(0xDE00) + "Z")
        for nul in (b"\0", b"\xc0\x80", b"\xe0\x80\x80"):
            self.assertEqual(restore.decode_mutf8(nul), "\0")

    def test_java_depth_limits(self):
        for depth in (150, 511, 512):
            body = compound()
            for _ in range(depth):
                body = compound(named(10, b"nested", body))
            data = root(body)
            with self.subTest(compound_depth=depth):
                if depth >= 512:
                    with self.assertRaisesRegex(restore.FormatError, "depth"):
                        restore.restore_nbt(data)
                else:
                    self.assertEqual(restore.restore_nbt(data), (data, False))
                    name, tag = restore.read_root(restore.Reader(data))
                    self.assertEqual(restore.serialize(name, tag), data)
            if depth == 150:
                encoded, states, _ = fixture(extra=body)
                self.assertEqual(restore.restore_nbt(encoded), (expected(states, extra=body), True))
        for depth in (511, 512):
            nested = tag_list(1, [b"\0"])
            for _ in range(depth - 1):
                nested = tag_list(9, [nested])
            data = root(compound(named(9, b"nested", nested)))
            with self.subTest(list_depth=depth):
                if depth >= 512:
                    with self.assertRaisesRegex(restore.FormatError, "depth"):
                        restore.restore_nbt(data)
                else:
                    name, tag = restore.read_root(restore.Reader(data))
                    self.assertEqual(restore.serialize(name, tag), data)

    def test_dictionary_expansion_limit(self):
        state = compound(named(7, b"blob", byte_array(b"x" * (1024 * 1024))))
        dictionary = root(compound(named(9, b"states", tag_list(10, [state]))))
        indices = b"\x03" + varint(4096) + b"\0"
        payload = (b"CSQ1" + dictionary + varint(1) + varint(0) + varint(4096)
                   + b"\0" * 4096 + varint(len(indices)) + indices)
        encoded, _, _ = fixture(payload=payload)
        self.assertLess(len(encoded), 2 * 1024 * 1024)
        with self.assertRaisesRegex(restore.FormatError, "64 MiB"):
            restore.restore_nbt(encoded)
        output = bytearray(b"prefix")
        with self.assertRaisesRegex(restore.FormatError, "64 MiB"):
            restore.append_nbt(output, b"x" * restore.MAX_BYTES)
        self.assertEqual(output, b"prefix")

    def test_encoded_status_validation(self):
        for status in (None, b""):
            with self.subTest(status=status), self.assertRaisesRegex(restore.FormatError, "Status"):
                restore.restore_nbt(fixture(status=status)[0])
        encoded = fixture()[0]
        name, tag = restore.read_root(restore.Reader(encoded))
        tag.value[b"Status"] = restore.Tag(3, struct.pack(">i", 1))
        with self.assertRaisesRegex(restore.FormatError, "Status"):
            restore.restore_nbt(restore.serialize(name, tag))
        for body in (compound(), compound(named(8, b"Status", string(b""))),
                     compound(named(3, b"Status", struct.pack(">i", 1)))):
            data = root(body)
            self.assertEqual(restore.restore_nbt(data), (data, False))

    def test_bit_plane_boundaries_and_corruption(self):
        for size in (1, 2, 3, 17, 4096):
            with self.subTest(palette_size=size):
                values = [(i * 13 + i // 8) % size for i in range(4096)]
                encoded = bit_planes(values, size)
                self.assertEqual(len(encoded), 1 + max(1, (size - 1).bit_length()) * 512)
                self.assertEqual(restore.decode_indices(encoded, size), vanilla(values, size))
                for damaged in (encoded[:-1], encoded + b"\0"):
                    with self.assertRaises(restore.FormatError):
                        restore.decode_indices(damaged, size)
        # 在首字节、跨字节及最后一个方块设置位，明确验证 LSB 顺序。
        values = [0] * 4096
        for index, value in ((0, 1), (7, 2), (8, 4), (4095, 16)):
            values[index] = value
        encoded = bytearray(b"\x05" + b"\0" * (5 * 512))
        encoded[1] = 1
        encoded[1 + 512] = 128
        encoded[1 + 2 * 512 + 1] = 1
        encoded[-1] = 128
        self.assertEqual(restore.decode_indices(encoded, 17), vanilla(values, 17))
        for size, invalid_value in ((1, 1), (3, 3), (17, 31)):
            values = [0] * 4096
            values[4095] = invalid_value
            with self.assertRaises(restore.FormatError):
                restore.decode_indices(bit_planes(values, size), size)

    def test_nbt_types_and_non_bmp_bytes(self):
        original = root(EXTRA, MUTF)
        reader = restore.Reader(original)
        name, tag = restore.read_root(reader)
        reader.end()
        self.assertEqual(restore.serialize(name, tag), original)
        self.assertEqual(restore.restore_nbt(original), (original, False))
        data, states, _ = fixture()
        self.assertEqual(restore.restore_nbt(data)[0], expected(states))

    def test_corruption_and_bounds(self):
        valid, _, payload = fixture()
        cases = [fixture(version=2)[0], fixture(crc_delta=1)[0],
                 fixture(payload=payload[:-1])[0], fixture(payload=payload + b"\0")[0],
                 fixture(payload=b"BAD!" + payload[4:])[0]]
        cases.extend(valid[:cut] for cut in (0, 1, 3, 20, len(valid) - 1))
        for data in cases:
            with self.subTest(length=len(data)):
                with self.assertRaises(restore.FormatError):
                    restore.restore_nbt(data)
        indices = [b"\0\0", b"\x06", b"\x03\0\0", b"\x03" + varint(4097) + b"\0",
                   b"\x04\0\x01" + varint(4096) + b"\0", b"\x01\x01" + b"\0" * 8,
                   b"\x04\x80\x00\0", b"\x02" + b"\xff" * 512,
                   b"\x04\0\x80\x80\x80\x80\x80"]
        for data in indices:
            with self.subTest(indices=data[:8]):
                with self.assertRaises(restore.FormatError):
                    restore.decode_indices(data, 1)
        with self.assertRaises(restore.FormatError):
            restore.decode_indices(b"\0", 2)

    def test_empty_vanilla_region_and_partial_header(self):
        with tempfile.TemporaryDirectory(prefix="chunksqueeze-empty-") as temporary:
            base = Path(temporary)
            source = base / "r.0.0.mca"
            source.write_bytes(b"")
            self.assertEqual(restore.read_region(source), (bytes(4096), []))
            self.assertEqual(restore.read_region(source, raw=True), (bytes(4096), []))
            restore.restore_region(source, base / "restored.mca")
            self.assertEqual(restore.read_region(base / "restored.mca"), (bytes(4096), []))
            source.write_bytes(bytes(4096))
            with self.assertRaises(restore.FormatError):
                restore.read_region(source)

    def test_region_roundtrip_source_unchanged_and_cli(self):
        with tempfile.TemporaryDirectory(prefix="chunksqueeze-test-") as temporary:
            base = Path(temporary)
            source, dest = base / "source", base / "restored"
            folder = source / "DIM-1" / "region"
            folder.mkdir(parents=True)
            (source / "level.dat").write_bytes(b"unchanged world metadata")
            (folder / restore.MARKER).write_text("ChunkSqueeze", encoding="utf-8")
            (source / "empty").mkdir()
            encoded, states, _ = fixture()
            plain = root(EXTRA)
            path = folder / "r.-2.3.mca"
            region(path, [(0, encoded, 1, False), (67, encoded, 2, False), (1023, plain, 3, False)])
            untouched = folder / "r.0.0.mca"
            region(untouched, [(4, plain, 1, False)])
            before = snapshot(source)
            result = subprocess.run([sys.executable, "-I", "-X", "utf8", str(TOOL), str(source), str(dest)],
                                    capture_output=True, text=True, encoding="utf-8")
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("Restored 2 encoded chunks", result.stdout)
            self.assertEqual(snapshot(source), before)
            out = dest / "DIM-1" / "region"
            self.assertFalse((out / restore.MARKER).exists())
            self.assertEqual((dest / "level.dat").read_bytes(), b"unchanged world metadata")
            self.assertTrue((dest / "empty").is_dir())
            self.assertEqual((out / untouched.name).read_bytes(), untouched.read_bytes())
            timestamps, chunks = restore.read_region(out / path.name)
            self.assertEqual(timestamps, bytes(range(256)) * 16)
            self.assertEqual([(slot, data, changed) for slot, data, changed, _ in chunks],
                             [(0, expected(states), False), (67, expected(states), False), (1023, plain, False)])
            raw = (out / path.name).read_bytes()
            for slot in (0, 67, 1023):
                offset = struct.unpack_from(">I", raw, slot * 4)[0] >> 8
                self.assertEqual(raw[offset * 4096 + 4], 2)

    def test_external_input_and_large_external_output(self):
        for large in (False, True):
            with self.subTest(large=large), tempfile.TemporaryDirectory(prefix="chunksqueeze-test-") as temporary:
                base = Path(temporary)
                source, dest = base / "source", base / "dest"
                source.mkdir()
                extra = compound(named(7, b"random", byte_array(os.urandom(1_100_000)))) if large else EXTRA
                encoded, states, _ = fixture(extra=extra)
                path = source / "r.-2.3.mca"
                region(path, [(67, encoded, 1, True)])
                (source / "c.-62.98.mcc").write_bytes(b"unrelated sidecar")
                region(source / "r.0.0.mca", [(0, root(EXTRA), 2, True)])
                before = snapshot(source)
                self.assertEqual(restore.restore_world(source, dest), 1)
                self.assertEqual(snapshot(source), before)
                _, chunks = restore.read_region(dest / path.name)
                self.assertEqual(chunks[0][1], expected(states, extra=extra))
                raw = (dest / path.name).read_bytes()
                self.assertEqual(raw[8196], 130 if large else 2)
                self.assertEqual((dest / "c.-62.98.mcc").read_bytes(), b"unrelated sidecar")
                self.assertEqual((dest / "c.0.0.mcc").read_bytes(), (source / "c.0.0.mcc").read_bytes())
                self.assertTrue((source / "c.-61.98.mcc").is_file())
                self.assertEqual((dest / "c.-61.98.mcc").exists(), large)
                if large:
                    sidecar = dest / "c.-61.98.mcc"
                    self.assertGreater(sidecar.stat().st_size, 255 * 4096)
                    self.assertEqual(zlib.decompress(sidecar.read_bytes()), expected(states, extra=extra))
                    self.assertEqual(len(raw), 3 * 4096)

    def test_missing_final_padding_and_truncated_records(self):
        for encoded, external in ((False, False), (True, False), (True, True)):
            with self.subTest(encoded=encoded, external=external), tempfile.TemporaryDirectory(prefix="chunksqueeze-test-") as temporary:
                base = Path(temporary)
                source, dest = base / "source", base / "dest"
                source.mkdir()
                data, states, _ = fixture()
                if not encoded:
                    data = root(EXTRA)
                path = source / "r.20.1.mca"
                region(path, [(2, data, 2, external)])
                complete = path.read_bytes()
                length = struct.unpack_from(">I", complete, 8192)[0]
                payload_end = 8192 + 4 + length
                self.assertLess(payload_end, len(complete))
                short = complete[:payload_end]
                path.write_bytes(short)
                before = snapshot(source)
                self.assertEqual(restore.restore_world(source, dest), int(encoded))
                self.assertEqual(snapshot(source), before)
                _, chunks = restore.read_region(dest / path.name)
                self.assertEqual(chunks[0][1], expected(states) if encoded else data)
                if not encoded:
                    self.assertEqual((dest / path.name).read_bytes(), short)
                bad_inputs = [short[:8193], short[:8196], short[:-1]]
                overlap = bytearray(short)
                struct.pack_into(">I", overlap, 3 * 4, struct.unpack_from(">I", overlap, 2 * 4)[0])
                bad_inputs.append(overlap)
                too_many_sectors = bytearray(short)
                struct.pack_into(">I", too_many_sectors, 2 * 4, (2 << 8) | 2)
                bad_inputs.append(too_many_sectors)
                for index, bad in enumerate(bad_inputs):
                    with self.subTest(bad=index):
                        path.write_bytes(bad)
                        failed_dest = base / f"failed-{index}"
                        with self.assertRaises(restore.FormatError):
                            restore.restore_world(source, failed_dest)
                        self.assertFalse(failed_dest.exists())
                        self.assertFalse(list(base.glob(".chunksqueeze-restore-*")))

    def test_rejection_is_transactional(self):
        with tempfile.TemporaryDirectory(prefix="chunksqueeze-test-") as temporary:
            base = Path(temporary)
            source, dest = base / "source", base / "dest"
            source.mkdir()
            path = source / "r.0.0.mca"
            encoded, _, _ = fixture()
            region(path, [(0, encoded, 2, False)])
            with self.assertRaises(restore.FormatError):
                restore.restore_world(source, source / "nested")
            with self.assertRaises(restore.FormatError):
                restore.restore_world(source, base)
            dest.mkdir()
            with self.assertRaises(restore.FormatError):
                restore.restore_world(source, dest)
            dest.rmdir()
            clean = path.read_bytes()
            variants = []
            bad = bytearray(clean)
            bad[8196] = 4
            variants.append(bad)
            bad = bytearray(clean)
            struct.pack_into(">I", bad, 4, struct.unpack_from(">I", bad)[0])
            variants.append(bad)
            bad = bytearray(clean)
            struct.pack_into(">I", bad, 0, (100 << 8) | 1)
            variants.append(bad)
            bad = bytearray(clean)
            struct.pack_into(">I", bad, 8192, 0x7fffffff)
            variants.append(bad)
            region(path, [(0, fixture(crc_delta=1)[0], 2, False)])
            variants.append(path.read_bytes())
            for bad in variants:
                path.write_bytes(bad)
                before = snapshot(source)
                with self.assertRaises(restore.FormatError):
                    restore.restore_world(source, dest)
                self.assertFalse(dest.exists())
                self.assertEqual(snapshot(source), before)
                self.assertFalse(list(base.glob(".chunksqueeze-restore-*")))

    def test_external_paths_and_missing_files(self):
        with tempfile.TemporaryDirectory(prefix="chunksqueeze-test-") as temporary:
            base = Path(temporary)
            source, dest = base / "source", base / "dest"
            source.mkdir()
            encoded, _, _ = fixture()
            path = source / "r.0.0.mca"
            region(path, [(0, encoded, 2, True)])
            (source / "c.0.0.mcc").unlink()
            with self.assertRaises(OSError):
                restore.restore_world(source, dest)
            self.assertFalse(dest.exists())
            path.rename(source / "unexpected.mca")
            with self.assertRaises(restore.FormatError):
                restore.restore_world(source, dest)
            self.assertFalse(dest.exists())
            for name in ("r.1.2.mca/../../evil", "r.1x.2.mca", "r.9999999999.0.mca"):
                with self.assertRaises(restore.FormatError):
                    restore.external_path(Path(name), 0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
