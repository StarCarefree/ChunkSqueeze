"""Run with: python -I -X utf8 tools/test_shared_restore.py"""

import importlib.util
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
import zlib

sys.dont_write_bytecode = True


def neighbor(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


test = neighbor("test_restore")
restore = test.restore
backend = restore.load_compact()
compact = neighbor("compact_world")
IDENTIFIER = bytes.fromhex("8090a0b0c0d0e0f0ffeeddccbbaa9988")
FILE_ID = bytes(range(16))
STATES = test.fixture(size=3)[1]


def dictionary(states=STATES, identifier=IDENTIFIER, version=1, extra=b""):
    return zlib.compress(test.root(test.compound(
        test.named(3, b"version", struct.pack(">i", version)),
        test.named(4, b"idMost", identifier[:8]),
        test.named(4, b"idLeast", identifier[8:]),
        test.named(9, b"states", test.tag_list(10, states)), extra)))


def shared(mapping=(2, 0), palette=(0, 1), indices=b"\x03\x80\x20\0", identifier=IDENTIFIER,
           version=2, magic=b"CSQ2", records=None, extra=test.EXTRA):
    if records is None:
        records = (test.varint(1) + test.varint(0) + test.varint(len(palette))
                   + b"".join(test.varint(index) for index in palette)
                   + test.varint(len(indices)) + indices)
    payload = (magic + identifier + test.varint(len(mapping))
               + b"".join(test.varint(index) for index in mapping) + records)
    return test.fixture(payload=payload, version=version, extra=extra)[0]


def stamp(page):
    struct.pack_into(">I", page, 40, zlib.crc32(page[:40] + page[44:]))
    return bytes(page)


def identity(magic, version):
    page = bytearray(512)
    struct.pack_into(">8sIiiI16s", page, 0, magic, version, -2, 3, 512, FILE_ID)
    return stamp(page)


def write_cqs(path, records=None, packed_dictionary=None, version=2):
    if records is None:
        records = [(0, zlib.compress(shared()), 4)]
    table = bytearray(32768)
    timestamps = bytes(range(256)) * 16
    for slot in range(1024):
        table[slot * 32 + 12:slot * 32 + 16] = timestamps[slot * 4:slot * 4 + 4]
    data = bytearray()
    for slot, payload, codec in records:
        struct.pack_into(">qiIIIq", table, slot * 32, 67072 + len(data), len(payload),
                         struct.unpack_from(">I", timestamps, slot * 4)[0], codec, zlib.crc32(payload), 2)
        data.extend(payload)
        data.extend(bytes((-len(payload)) % 512))
    marker = bytearray(512)
    struct.pack_into(">8sqII16s", marker, 0, b"CSQCMT01", 2, 0, zlib.crc32(table), FILE_ID)
    if packed_dictionary is not None:
        struct.pack_into(">qiIIIq", marker, 44, 67072 + len(data), len(packed_dictionary), 0, 2,
                         zlib.crc32(packed_dictionary), 2)
        data.extend(packed_dictionary)
        data.extend(bytes((-len(packed_dictionary)) % 512))
    marker = stamp(marker)
    path.write_bytes(identity(b"CSQREG01", version) + marker * 2 + table * 2 + data)
    path.with_name(path.name + ".owner").write_bytes(identity(b"CSQOWN01", 1))
    return timestamps


class SharedRestoreTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="chunksqueeze-shared-test-")
        self.addCleanup(temporary.cleanup)
        self.base = Path(temporary.name)
        self.source = self.base / "source"
        self.source.mkdir()
        self.file = self.source / "r.-2.3.cqs"
        self.dest = self.base / "restored"

    def assert_rejected(self):
        before = test.snapshot(self.source)
        for operation in (restore.restore_world, compact.compact_world):
            with self.subTest(operation=operation.__name__), self.assertRaises((ValueError, OSError, zlib.error)):
                operation(self.source, self.dest)
            self.assertFalse(self.dest.exists())
            self.assertFalse(list(self.base.glob(".chunksqueeze-*")))
            self.assertEqual(before, test.snapshot(self.source))

    def test_v2_cli_roundtrip_and_legacy_reader_tuple(self):
        encoded = shared()
        packed_dictionary = dictionary()
        times = write_cqs(self.file, packed_dictionary=packed_dictionary)
        result = backend.read_region(self.file)
        self.assertEqual(len(result), 2)
        self.assertEqual(result[0], times)
        self.assertEqual(result[1], [(0, zlib.compress(encoded), 4, None)])
        self.assertEqual(backend.read_region(self.file, with_dictionary=True), (*result, packed_dictionary))
        before = test.snapshot(self.source)
        run = subprocess.run([sys.executable, "-I", "-X", "utf8", str(Path(restore.__file__)),
                              str(self.source), str(self.dest)], capture_output=True, text=True, encoding="utf-8")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertIn("Restored 1 encoded chunks", run.stdout)
        times_actual, chunks = restore.read_region(self.dest / "r.-2.3.mca")
        self.assertEqual(times_actual, times)
        expected = test.expected([STATES[2], STATES[0]], test.vanilla([0] * 4096, 2))
        self.assertEqual(chunks[0][1], expected)
        self.assertFalse(list(self.dest.glob("*.cqs*")))
        self.assertEqual(before, test.snapshot(self.source))

    def test_all_index_modes_with_region_to_local_mapping(self):
        values = [i % 2 for i in range(4096)]
        packed = test.vanilla(values, 2)
        modes = [(b"\0", (0,), None),
                 (b"\1" + test.varint(len(packed) // 8) + packed, (0, 1), packed),
                 (b"\2" + bytes([0xAA]) * 512, (0, 1), packed),
                 (b"\3" + test.varint(4096) + b"\1", (0, 1), test.vanilla([1] * 4096, 2)),
                 (b"\4\0\1\0\1", (0, 1), test.vanilla([1] + [0] * 4095, 2)),
                 (test.bit_planes(values, 2), (0, 1), packed)]
        parsed_dictionary = restore.read_dictionary(dictionary())
        for indices, palette, data in modes:
            with self.subTest(mode=indices[0]):
                encoded = shared(palette=palette, indices=indices)
                expected = test.expected([STATES[(2, 0)[index]] for index in palette], data)
                self.assertEqual(restore.restore_nbt(encoded, parsed_dictionary, require_shared=True), (expected, True))

    def test_compact_world_copies_whole_v2_container_and_owner(self):
        write_cqs(self.file, packed_dictionary=dictionary())
        before = test.snapshot(self.source)
        copied = self.base / "copied"
        self.assertEqual(compact.compact_world(self.source, copied), 0)
        self.assertEqual(self.file.read_bytes(), (copied / self.file.name).read_bytes())
        owner = self.file.with_name(self.file.name + ".owner")
        self.assertEqual(owner.read_bytes(), (copied / owner.name).read_bytes())
        self.assertIn("format 2 requires", (copied / restore.MARKER).read_text(encoding="utf-8"))
        self.assertEqual(restore.restore_world(copied, self.dest), 1)
        self.assertEqual(before, test.snapshot(self.source))

    def test_v1_and_dictionary_free_v2_stay_compatible(self):
        plain = test.root(test.EXTRA)
        for version in (1, 2):
            for packed_dictionary in (None, dictionary(states=[])) if version == 2 else (None,):
                with self.subTest(version=version, dictionary=packed_dictionary is not None):
                    write_cqs(self.file, [(0, zlib.compress(plain), 2)], packed_dictionary, version)
                    timestamps, chunks, found = backend.read_region(self.file, with_dictionary=True)
                    self.assertEqual(found, packed_dictionary)
                    self.assertEqual(restore.restore_world(self.source, self.dest), 0)
                    self.assertEqual(restore.read_region(self.dest / "r.-2.3.mca")[1][0][1], plain)
                    shutil.rmtree(self.dest)

    def test_repackage_codec4_rejected_before_creating_output(self):
        write_cqs(self.file, packed_dictionary=dictionary())
        timestamps, chunks = backend.read_region(self.file)
        output = self.base / "r.-2.3.cqs"
        with self.assertRaisesRegex(ValueError, "Cannot repackage"):
            backend.write_region(output, timestamps, chunks)
        self.assertFalse(output.exists())
        self.assertFalse(output.with_name(output.name + ".owner").exists())

    def test_missing_dictionary_and_wrong_codec_or_marker(self):
        variants = [(shared(), 4, None), (shared(), 2, dictionary()),
                    (test.fixture()[0], 4, dictionary()), (test.root(test.EXTRA), 4, dictionary())]
        for encoded, codec, packed_dictionary in variants:
            with self.subTest(codec=codec, dictionary=packed_dictionary is not None):
                write_cqs(self.file, [(0, zlib.compress(encoded), codec)], packed_dictionary)
                self.assert_rejected()
        write_cqs(self.file, packed_dictionary=dictionary(), version=1)
        self.assert_rejected()

    def test_uuid_mapping_and_section_corruption(self):
        variants = [shared(identifier=FILE_ID), shared(mapping=(3,)), shared(mapping=()),
                    shared(mapping=(0,), palette=(1,)), shared(magic=b"CSQ1"), shared(version=3),
                    shared(records=b"\0"), shared(records=b"\1\1\1\0\1\0"),
                    shared(records=b"\2\0\1\0\1\0\0\1\0\1\0"),
                    shared(records=b"\x81\0"), shared(records=b"\xff\xff\xff\xff\x7f")]
        for encoded in variants:
            with self.subTest(encoded=encoded[-30:]):
                write_cqs(self.file, [(0, zlib.compress(encoded), 4)], dictionary())
                self.assert_rejected()
        write_cqs(self.file, packed_dictionary=dictionary(states=[]))
        self.assert_rejected()
        encoded = shared()
        name, tag = restore.read_root(restore.Reader(encoded))
        marker = restore.member(tag, restore.KEY, 10)
        marker.value[b"crc32"] = restore.Tag(4, struct.pack(">q", 1))
        write_cqs(self.file, [(0, zlib.compress(restore.serialize(name, tag)), 4)], dictionary())
        self.assert_rejected()

    def test_dictionary_nbt_size_compression_and_state_count_rejected(self):
        raw = zlib.decompress(dictionary())
        variants = [b"bad zlib", dictionary() + b"trailing", dictionary()[:-1], zlib.compress(raw + b"\0"),
                    dictionary(version=2), dictionary(states=[b"\0"] * 65537),
                    zlib.compress(b"x" * (restore.MAX_DICTIONARY_RAW + 1)),
                    zlib.compress(test.root(test.compound(test.named(3, b"version", struct.pack(">i", 1))))),
                    dictionary(extra=test.named(3, b"version", struct.pack(">i", 1)))]
        for packed_dictionary in variants:
            with self.subTest(size=len(packed_dictionary)):
                write_cqs(self.file, packed_dictionary=packed_dictionary)
                self.assert_rejected()
        wrong_states = test.root(test.compound(
            test.named(3, b"version", struct.pack(">i", 1)),
            test.named(4, b"idMost", IDENTIFIER[:8]), test.named(4, b"idLeast", IDENTIFIER[8:]),
            test.named(9, b"states", test.tag_list(3, [struct.pack(">i", 0)]))))
        write_cqs(self.file, packed_dictionary=zlib.compress(wrong_states))
        self.assert_rejected()

    def test_dictionary_descriptor_bounds_overlap_generation_and_reserved(self):
        write_cqs(self.file, packed_dictionary=dictionary())
        original = self.file.read_bytes()
        mutations = [(44, ">q", 67073), (44, ">q", 67072), (44, ">q", len(original) + 512),
                     (52, ">i", 0), (52, ">i", 16 * 1024 * 1024 + 1),
                     (56, ">I", 1), (60, ">I", 4), (68, ">q", -1), (68, ">q", 3), (76, ">I", 1)]
        for offset, fmt, value in mutations:
            with self.subTest(offset=offset, value=value):
                data = bytearray(original)
                page = bytearray(data[512:1024])
                struct.pack_into(fmt, page, offset, value)
                page = stamp(page)
                data[512:1536] = page * 2
                self.file.write_bytes(data)
                self.assert_rejected()
        # 合法 CRC 的 descriptor 指向 chunk，仍必须按 allocation 重叠拒绝。
        data = bytearray(original)
        page = bytearray(data[512:1024])
        offset, length, _, _, checksum, _ = struct.unpack_from(">qiIIIq", data, 1536)
        struct.pack_into(">qiIIIq", page, 44, offset, length, 0, 2, checksum, 2)
        data[512:1536] = stamp(page) * 2
        self.file.write_bytes(data)
        self.assert_rejected()

    def test_newest_bad_dictionary_cannot_fall_back(self):
        write_cqs(self.file, packed_dictionary=dictionary())
        data = bytearray(self.file.read_bytes())
        old = bytearray(data[512:1024])
        struct.pack_into(">q", old, 8, 1)
        old[44:76] = bytes(32)
        data[1024:1536] = stamp(old)
        offset = struct.unpack_from(">q", data, 512 + 44)[0]
        data[offset] ^= 1
        self.file.write_bytes(data)
        self.assert_rejected()

    def test_equal_generation_dictionary_descriptors_must_match(self):
        write_cqs(self.file, packed_dictionary=dictionary())
        data = bytearray(self.file.read_bytes())
        page = bytearray(data[1024:1536])
        struct.pack_into(">q", page, 68, 1)
        data[1024:1536] = stamp(page)
        self.file.write_bytes(data)
        self.assert_rejected()

    def test_torn_commit_and_last_allocation_padding(self):
        write_cqs(self.file, packed_dictionary=dictionary())
        data = bytearray(self.file.read_bytes())
        data[512 + 40] ^= 1
        offset, length = struct.unpack_from(">qi", data, 1024 + 44)
        self.file.write_bytes(data[:offset + length])
        self.assertEqual(restore.restore_world(self.source, self.dest), 1)
        shutil.rmtree(self.dest)
        self.file.write_bytes(data[:offset + length - 1])
        self.assert_rejected()

    def test_decompression_trailing_data_and_bomb(self):
        for payload in (zlib.compress(shared()) + b"trailing", zlib.compress(shared())[:-1],
                        zlib.compress(b"x" * (restore.MAX_BYTES + 1))):
            with self.subTest(size=len(payload)):
                write_cqs(self.file, [(0, payload, 4)], dictionary())
                self.assert_rejected()

    def test_dictionary_expansion_and_restored_depth_are_bounded(self):
        state = test.compound(test.named(7, b"blob", test.byte_array(b"x" * (1024 * 1024))))
        encoded = shared(mapping=(0,), palette=(0,) * 4096)
        parsed_dictionary = restore.read_dictionary(dictionary(states=[state]))
        with self.assertRaisesRegex(ValueError, "64 MiB"):
            restore.restore_nbt(encoded, parsed_dictionary, require_shared=True)
        state = test.compound()
        for _ in range(508):
            state = test.compound(test.named(10, b"nested", state))
        packed_dictionary = dictionary(states=[state])
        parsed_dictionary = restore.read_dictionary(packed_dictionary)
        with self.assertRaisesRegex(ValueError, "depth"):
            restore.restore_nbt(shared(mapping=(0,), palette=(0,), indices=b"\0"), parsed_dictionary, require_shared=True)


if __name__ == "__main__":
    unittest.main(verbosity=1)
