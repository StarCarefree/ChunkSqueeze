"""Run with: python -I -X utf8 tools/test_section_restore.py"""

import gzip
import importlib.util
from pathlib import Path
import struct
import sys
import tempfile
import unittest
import zlib

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("test_shared_restore", Path(__file__).with_name("test_shared_restore.py"))
shared = importlib.util.module_from_spec(spec)
spec.loader.exec_module(shared)
test = shared.test
restore = shared.restore
backend = shared.backend
IDENTIFIER = shared.IDENTIFIER
SECTION = test.compound(
    test.named(1, b"Y", b"\xfe"),
    test.named(7, b"SkyLight", test.byte_array(bytes(range(256)) * 8)),
    test.named(7, b"BlockLight", test.byte_array(b"\x12" * 2048)),
    test.named(10, b"biomes", test.compound(test.named(9, b"palette", test.tag_list(8, [test.string(b"minecraft:plains")])))),
    test.named(10, b"block_states", test.compound(test.named(9, b"palette", test.tag_list(10, shared.STATES)))),
    test.named(10, b"unknown", test.EXTRA))


def dictionary(sections=(SECTION,), states=shared.STATES, version=2, extra=b"", section_kind=10):
    return shared.dictionary(states=states, version=version, extra=(
        test.named(9, b"sections", test.tag_list(section_kind, sections)) + extra))


def marker(payload, version=1, crc_delta=0):
    return test.compound(
        test.named(3, b"version", struct.pack(">i", version)),
        test.named(4, b"crc32", struct.pack(">q", zlib.crc32(payload) ^ crc_delta)),
        test.named(7, b"payload", test.byte_array(payload)))


def chunk(sections=(b"\0",), pairs=((0, 0),), identifier=IDENTIFIER, payload=None, **kwargs):
    if payload is None:
        payload = b"CSS1" + identifier + test.varint(len(pairs)) + b"".join(
            test.varint(index) + test.varint(pool) for index, pool in pairs)
    return test.root(test.compound(
        test.named(9, b"sections", test.tag_list(10, sections)),
        test.named(10, restore.SECTION_KEY, marker(payload, **kwargs))), test.MUTF)


def expected(sections=(SECTION,)):
    return test.root(test.compound(test.named(9, b"sections", test.tag_list(10, sections))), test.MUTF)


class SectionRestoreTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="chunksqueeze-section-test-")
        self.addCleanup(temporary.cleanup)
        self.base = Path(temporary.name)
        self.source = self.base / "source"
        self.source.mkdir()
        self.file = self.source / "r.-2.3.cqs"
        self.dest = self.base / "restored"
        self.dictionary = restore.read_dictionary(dictionary())

    def decode(self, data, dictionary=None):
        return restore.restore_compact_chunk(zlib.compress(data), 5,
                                             self.dictionary if dictionary is None else dictionary)

    def test_exact_full_section_and_repeated_references(self):
        second = SECTION.replace(b"\xfe", b"\xfd", 1)
        pool = restore.read_dictionary(dictionary((SECTION, second)))
        encoded = chunk((b"\0", b"\0", b"\0"), ((0, 1), (1, 0), (2, 1)))
        self.assertEqual(self.decode(encoded, pool), (expected((second, SECTION, second)), True))
        self.assertEqual(restore.serialize(b"", pool.sections[0]), test.root(SECTION))
        identifier, states = pool
        self.assertEqual(identifier, IDENTIFIER)
        self.assertEqual(len(states), 3)
        self.assertIsInstance(pool, tuple)
        self.assertEqual(len(pool), 2)

    def test_mixed_state_versions_and_section_markers(self):
        for encoded in (test.fixture()[0], shared.shared()):
            with self.subTest(encoded=encoded[-20:]):
                name, root = restore.read_root(restore.Reader(encoded))
                sections = restore.member(root, b"sections", 9).value[1]
                sections.append(restore.Tag(10, {}))
                _, section_root = restore.read_root(restore.Reader(chunk((b"\0", b"\0"), ((1, 0),))))
                root.value[restore.SECTION_KEY] = section_root.value[restore.SECTION_KEY]
                state_nbt, _ = restore.restore_nbt(encoded, self.dictionary)
                _, state_root = restore.read_root(restore.Reader(state_nbt))
                restore.member(state_root, b"sections", 9).value[1].append(self.dictionary.sections[0])
                self.assertEqual(self.decode(restore.serialize(name, root)),
                                 (restore.serialize(name, state_root), True))
                # 同一个位置不能先还原 block_states 再被完整区段覆盖。
                root.value[restore.SECTION_KEY] = restore.read_root(restore.Reader(chunk()))[1].value[restore.SECTION_KEY]
                with self.assertRaisesRegex(ValueError, "Nonempty"):
                    self.decode(restore.serialize(name, root))

    def test_mca_export_and_whole_container_copy(self):
        times = shared.write_cqs(self.file, [(0, zlib.compress(chunk()), 5)], dictionary())
        before = test.snapshot(self.source)
        old_api = backend.read_region(self.file)
        self.assertEqual(len(old_api), 2)
        self.assertEqual(old_api[1][0][2], 5)
        copied = self.base / "copied"
        self.assertEqual(shared.compact.compact_world(self.source, copied), 0)
        for name, digest in before.items():
            self.assertEqual(test.snapshot(copied)[name], digest)
        self.assertEqual(restore.restore_world(copied, self.dest), 1)
        timestamps, chunks = restore.read_region(self.dest / "r.-2.3.mca")
        self.assertEqual(timestamps, times)
        self.assertEqual(chunks[0][1:3], (expected(), False))
        self.assertEqual(before, test.snapshot(self.source))
        output = self.base / "r.-2.3.cqs"
        with self.assertRaisesRegex(ValueError, "Cannot repackage"):
            backend.write_region(output, *old_api)
        self.assertFalse(output.exists())

    def test_corrupt_section_markers(self):
        name, root = restore.read_root(restore.Reader(chunk()))
        root.value[restore.SECTION_KEY].value[b"extra"] = restore.Tag(1, b"\0")
        with self.assertRaisesRegex(ValueError, "marker fields"):
            self.decode(restore.serialize(name, root))
        prefix = b"CSS1" + IDENTIFIER
        variants = [chunk(identifier=bytes(16)), chunk(pairs=()),
                    chunk((b"\0", b"\0"), ((0, 0), (0, 0))),
                    chunk(sections=(SECTION,)), chunk(pairs=((1, 0),)),
                    chunk(pairs=((0, 1),)), chunk(version=2), chunk(crc_delta=1),
                    chunk(payload=b"CSQ1" + IDENTIFIER + b"\1\0\0"),
                    chunk(payload=prefix + b"\x81\0\0\0"),
                    chunk(payload=prefix + b"\1\x80\0\0"),
                    chunk(payload=prefix + b"\1\0\x80\0"),
                    chunk(payload=prefix + b"\1\0\0\0")]
        payload = prefix + b"\1\0\0"
        variants.extend(chunk(payload=payload[:end]) for end in range(len(payload)))
        for data in variants:
            with self.subTest(data=data[-40:]), self.assertRaises(ValueError):
                self.decode(data)

    def test_codec_marker_and_dictionary_requirements(self):
        for codec, pack in ((1, gzip.compress), (2, zlib.compress), (3, lambda x: x), (4, zlib.compress)):
            with self.subTest(codec=codec), self.assertRaises(ValueError):
                restore.restore_compact_chunk(pack(chunk()), codec, self.dictionary)
        for data in (test.root(test.EXTRA), test.fixture()[0], shared.shared()):
            with self.assertRaisesRegex(ValueError, "missing its section marker"):
                self.decode(data)
        with self.assertRaisesRegex(ValueError, "dictionary required"):
            restore.restore_nbt(chunk())
        with self.assertRaises(ValueError):
            self.decode(chunk(), restore.read_dictionary(shared.dictionary()))
        for packed_dictionary, version in ((None, 2), (dictionary(), 1)):
            shared.write_cqs(self.file, [(0, zlib.compress(chunk()), 5)], packed_dictionary, version)
            with self.assertRaises(ValueError):
                backend.read_region(self.file)
        name, root = restore.read_root(restore.Reader(shared.shared()))
        root.value[restore.SECTION_KEY] = restore.read_root(restore.Reader(chunk()))[1].value[restore.SECTION_KEY]
        with self.assertRaisesRegex(ValueError, "forbids section markers"):
            restore.restore_compact_chunk(zlib.compress(restore.serialize(name, root)), 4, self.dictionary)

    def test_invalid_container_rejected_by_copy_and_export(self):
        shared.write_cqs(self.file, [(0, zlib.compress(chunk(identifier=bytes(16))), 5)], dictionary())
        before = test.snapshot(self.source)
        for operation in (restore.restore_world, shared.compact.compact_world):
            with self.assertRaises(ValueError):
                operation(self.source, self.dest)
            self.assertFalse(self.dest.exists())
            self.assertFalse(list(self.base.glob(".chunksqueeze-*")))
            self.assertEqual(before, test.snapshot(self.source))

    def test_dictionary_versions_and_capacity(self):
        self.assertEqual(restore.read_dictionary(dictionary(sections=(), states=())).sections, [])
        self.assertEqual(restore.read_dictionary(shared.dictionary()).sections, [])
        self.assertEqual(len(restore.read_dictionary(dictionary([b"\0"] * 4096)).sections), 4096)
        self.assertEqual(len(restore.read_dictionary(dictionary(states=[b"\0"] * 65536))[1]), 65536)
        blob = test.named(7, b"blob", test.byte_array(bytes(4 * 1024 * 1024)))
        self.assertIsNotNone(restore.read_dictionary(dictionary(extra=blob)))
        for packed in (dictionary([b"\0"] * 4097), dictionary(states=[b"\0"] * 65537),
                       dictionary(section_kind=3, sections=[bytes(4)]), dictionary(version=3),
                       dictionary(version=1, extra=blob),
                       dictionary(extra=test.named(7, b"blob", test.byte_array(bytes(8 * 1024 * 1024)))),
                       bytes(restore.MAX_DICTIONARY_PACKED + 1)):
            with self.subTest(length=len(packed)), self.assertRaises(ValueError):
                restore.read_dictionary(packed)

    def test_expanded_size_and_depth_limits(self):
        large = test.compound(test.named(7, b"blob", test.byte_array(bytes(1024 * 1024))))
        pool = restore.read_dictionary(dictionary([large]))
        encoded = chunk((b"\0",) * 65, tuple((i, 0) for i in range(65)))
        with self.assertRaisesRegex(ValueError, "64 MiB"):
            self.decode(encoded, pool)
        section = test.compound()
        for _ in range(509):
            section = test.compound(test.named(10, b"nested", section))
        pool = restore.read_dictionary(dictionary([section]))
        self.assertEqual(self.decode(chunk(), pool), (expected((section,)), True))
        too_deep = test.compound(test.named(10, b"nested", section))
        with self.assertRaisesRegex(ValueError, "depth"):
            restore.read_dictionary(dictionary([too_deep]))
        # 即使调用者直接提供对象，输出端仍校验真实嵌入深度。
        pool.sections[0] = restore.Tag(10, {b"nested": pool.sections[0]})
        with self.assertRaisesRegex(ValueError, "depth"):
            self.decode(chunk(), pool)

    def test_legacy_state_codecs_with_new_dictionary(self):
        expected_nbt = test.expected([shared.STATES[2], shared.STATES[0]], test.vanilla([0] * 4096, 2))
        self.assertEqual(restore.restore_compact_chunk(zlib.compress(shared.shared()), 4, self.dictionary),
                         (expected_nbt, True))
        data, states, _ = test.fixture()
        self.assertEqual(restore.restore_compact_chunk(zlib.compress(data), 2, self.dictionary),
                         (test.expected(states), True))
        for codec, pack in ((1, gzip.compress), (2, zlib.compress), (3, lambda x: x)):
            with self.assertRaises(ValueError):
                restore.restore_compact_chunk(pack(shared.shared()), codec, self.dictionary)


if __name__ == "__main__":
    unittest.main(verbosity=1)
