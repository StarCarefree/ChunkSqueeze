"""Run with: python -I -X utf8 tools/test_compact.py"""

import importlib.util
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
from types import SimpleNamespace
import stat
import zlib

sys.dont_write_bytecode = True


def neighbor(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


test = neighbor("test_restore")
restore = test.restore
compact = neighbor("compact_world")
region = restore.load_compact()


class CompactTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="chunksqueeze-compact-test-")
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.source = self.base / "source"
        self.source.mkdir()
        self.compacted = self.base / "compact"
        self.dest = self.base / "restored"

    def make_compact(self, entries=None):
        if entries is None:
            entries = [(0, test.root(test.EXTRA), 2, False)]
        test.region(self.source / "r.-2.3.mca", entries)
        compact.compact_world(self.source, self.compacted)
        return self.compacted / "r.-2.3.cqs"

    def assert_rejected(self, source=None):
        source = source or self.compacted
        before = test.snapshot(source)
        for operation in (restore.restore_world, compact.compact_world):
            with self.subTest(operation=operation.__name__), self.assertRaises((ValueError, OSError)):
                operation(source, self.dest)
            self.assertFalse(self.dest.exists())
            self.assertFalse(list(self.base.glob(".chunksqueeze-*")))
            self.assertEqual(test.snapshot(source), before)

    def test_payloads_timestamps_codecs_mcc_and_cli(self):
        encoded, states, _ = test.fixture()
        plain = test.root(test.EXTRA, test.MUTF)
        path = self.source / "r.-2.3.mca"
        entries = [(i, encoded if i % 2 else plain, codec, external)
                   for i, (codec, external) in enumerate(( (1, False), (1, True), (2, False), (2, True), (3, False), (3, True) ))]
        test.region(path, entries)
        (self.source / "notes.txt").write_bytes(b"metadata")
        before = test.snapshot(self.source)
        timestamps, original = restore.read_region(path, raw=True)
        result = subprocess.run([sys.executable, "-I", "-X", "utf8", str(Path(compact.__file__)),
                                 str(self.source), str(self.compacted)], capture_output=True, text=True, encoding="utf-8")
        self.assertEqual(result.returncode, 0, result.stderr)
        cqs = self.compacted / "r.-2.3.cqs"
        owner = cqs.with_name(cqs.name + ".owner")
        actual_times, chunks = region.read_region(cqs)
        self.assertEqual(actual_times, timestamps)
        self.assertEqual([item[:3] for item in chunks], [item[:3] for item in original])
        self.assertEqual(owner.stat().st_size, 512)
        self.assertEqual(cqs.stat().st_size % 512, 0)
        self.assertFalse(list(self.compacted.glob("*.mcc")))
        # 独立按规范校验页布局，不用被测写入函数构造期望。
        data = cqs.read_bytes()
        self.assertEqual(struct.unpack_from(">8sIiiI", data), (b"CSQREG01", 1, -2, 3, 512))
        self.assertEqual(zlib.crc32(data[:40] + data[44:512]), struct.unpack_from(">I", data, 40)[0])
        self.assertEqual(data[512:1024], data[1024:1536])
        self.assertEqual(struct.unpack_from(">8sqI", data, 512), (b"CSQCMT01", 1, 0))
        self.assertEqual(data[24:40], owner.read_bytes()[24:40])
        for slot, payload, codec, _ in chunks:
            offset, length, timestamp, found_codec, checksum, generation = struct.unpack_from(">qiIIIq", data, 1536 + slot * 32)
            self.assertEqual((length, found_codec, checksum, generation), (len(payload), codec, zlib.crc32(payload), 1))
            self.assertEqual(offset % 512, 0)
            self.assertEqual(data[offset:offset + length], payload)
        self.assertEqual(restore.restore_world(self.compacted, self.dest), 3)
        restored_times, restored = restore.read_region(self.dest / path.name)
        self.assertEqual(restored_times, timestamps)
        self.assertEqual([entry[1] for entry in restored], [test.expected(states) if i % 2 else plain for i in range(6)])
        self.assertEqual((self.dest / "notes.txt").read_bytes(), b"metadata")
        self.assertFalse(list(self.dest.glob("*.cqs*")))
        self.assertEqual(test.snapshot(self.source), before)

    def test_all_state_codecs_survive_migration(self):
        values = [i % 2 for i in range(4096)]
        raw = test.vanilla(values, 2)
        modes = [(b"\0", 1), (b"\1" + test.varint(len(raw) // 8) + raw, 2),
                 (b"\2" + bytes([0xAA]) * 512, 2), (b"\3" + test.varint(4096) + b"\1", 2),
                 (b"\4\0\1\0\1", 2), (test.bit_planes(values, 2), 2)]
        inputs = [test.fixture(mode, size)[0] for mode, size in modes]
        path = self.make_compact([(i, nbt, 3, False) for i, nbt in enumerate(inputs)])
        _, chunks = region.read_region(path)
        self.assertEqual([entry[1] for entry in chunks], inputs)
        self.assertEqual(restore.restore_world(self.compacted, self.dest), 6)
        _, chunks = restore.read_region(self.dest / "r.-2.3.mca")
        self.assertEqual([entry[1] for entry in chunks], [restore.restore_nbt(nbt)[0] for nbt in inputs])

    def test_terrain_filter_and_existing_cqs_copy(self):
        plain = test.root(test.EXTRA)
        folders = ["region", "DIM-1/region", "dimensions/custom/test/region", "entities", "poi", "DIM-1/entities", "backup"]
        for folder in folders:
            directory = self.source / folder
            directory.mkdir(parents=True)
            test.region(directory / "r.0.0.mca", [(0, plain, 2, True)])
        (self.source / "level.dat").write_bytes(b"world")
        (self.source / "empty").mkdir()
        before = test.snapshot(self.source)
        self.assertEqual(compact.compact_world(self.source, self.compacted), 3)
        for folder in folders:
            converted = Path(folder).name == "region"
            self.assertEqual((self.compacted / folder / "r.0.0.cqs").exists(), converted)
            self.assertEqual((self.compacted / folder / "r.0.0.mca").exists(), not converted)
            self.assertEqual((self.compacted / folder / "c.0.0.mcc").exists(), not converted)
            if not converted:
                self.assertEqual((self.compacted / folder / "r.0.0.mca").read_bytes(), (self.source / folder / "r.0.0.mca").read_bytes())
        self.assertEqual(test.snapshot(self.source), before)
        compact_before = test.snapshot(self.compacted)
        self.assertEqual(compact.compact_world(self.compacted, self.dest), 0)
        self.assertEqual(test.snapshot(self.dest), compact_before)
        self.assertEqual(test.snapshot(self.compacted), compact_before)
        self.assertTrue((self.dest / "empty").is_dir())

    def test_pending_migration_rejected_before_output(self):
        for folder in (".", "region", "DIM-1/region"):
            directory = self.source / folder
            directory.mkdir(parents=True, exist_ok=True)
            journal = directory / "r.0.0.CQS.MIGRATING"
            journal.write_bytes(b"unparsed transaction")
            for has_mca in (False, True):
                if has_mca:
                    test.region(directory / "r.0.0.mca", [(0, test.root(test.EXTRA), 2, False)])
                before = test.snapshot(self.source)
                for operation in (restore.restore_world, compact.compact_world):
                    with self.subTest(folder=folder, has_mca=has_mca, operation=operation.__name__):
                        with self.assertRaisesRegex(ValueError, r"Recover transactions with ChunkSqueeze 0\.3\+"):
                            operation(self.source, self.dest)
                        self.assertFalse(self.dest.exists())
                        self.assertFalse(list(self.base.glob(".chunksqueeze-*")))
                        self.assertEqual(test.snapshot(self.source), before)
            journal.unlink()
            (directory / "r.0.0.mca").unlink()

    def test_migration_backups_preserved_and_flat_regions_converted(self):
        encoded, states, _ = test.fixture()
        test.region(self.source / "r.0.0.mca", [(0, encoded, 2, False)])
        (self.source / restore.MARKER).write_bytes(b"active old marker")
        folders = (".ChUnKsQuEeZe-MiGrAtIoN/uuid/original",
                   ".ChUnKsQuEeZe-MiGrAtIoN/uuid/prepared/region")
        for folder in folders:
            backup = self.source / folder
            backup.mkdir(parents=True)
            for name in ("r.0.0.mca", "r.0.0.cqs", "r.0.0.cqs.owner", "r.0.0.cqs.migrating",
                         "invalid.mca", "invalid.cqs", "r.1.0.cqs.owner", "r.2.0.cqs", "c.0.0.mcc", restore.MARKER):
                (backup / name).write_bytes(b"untouched backup: " + name.encode("ascii"))
        before = test.snapshot(self.source)
        self.assertEqual(compact.compact_world(self.source, self.compacted), 1)
        self.assertTrue((self.compacted / "r.0.0.cqs").is_file())
        self.assertFalse((self.compacted / "r.0.0.mca").exists())
        self.assertIn("0.2+", (self.compacted / restore.MARKER).read_text(encoding="utf-8"))
        self.assertEqual(restore.restore_world(self.compacted, self.dest), 1)
        self.assertEqual(restore.read_region(self.dest / "r.0.0.mca")[1][0][1], test.expected(states))
        self.assertFalse((self.dest / restore.MARKER).exists())
        for folder in folders:
            expected = test.snapshot(self.source / folder)
            self.assertEqual(test.snapshot(self.compacted / folder), expected)
            self.assertEqual(test.snapshot(self.dest / folder), expected)
        self.assertEqual(test.snapshot(self.source), before)

    def test_nested_migration_backup_excluded_from_discovery(self):
        backup = self.source / "dimensions/custom/world/region/.chunksqueeze-migration/uuid/original"
        backup.mkdir(parents=True)
        (backup / "orphan.cqs.owner").write_bytes(b"invalid owner")
        (backup / "broken.mca").write_bytes(b"invalid MCA")
        (backup / "r.0.0.cqs.migrating").write_bytes(b"historical transaction")
        (backup / restore.MARKER).write_bytes(b"backup marker")
        before = test.snapshot(self.source)
        for operation in (restore.restore_world, compact.compact_world):
            with self.subTest(operation=operation.__name__):
                self.assertEqual(operation(self.source, self.dest), 0)
                self.assertEqual(test.snapshot(self.dest), before)
                shutil.rmtree(self.dest)

    def test_migration_backup_reparse_points_still_rejected(self):
        backup = self.source / ".chunksqueeze-migration/uuid/original"
        backup.mkdir(parents=True)
        link = backup / "linked"
        link.mkdir()
        original_lstat = Path.lstat

        def lstat(path, *args, **kwargs):
            if path == link:
                return SimpleNamespace(st_mode=stat.S_IFDIR, st_file_attributes=0x400)
            return original_lstat(path, *args, **kwargs)

        for operation in (restore.restore_world, compact.compact_world):
            with self.subTest(operation=operation.__name__), mock.patch.object(Path, "lstat", lstat):
                with self.assertRaisesRegex(ValueError, "reparse points"):
                    operation(self.source, self.dest)
                self.assertFalse(self.dest.exists())
                self.assertFalse(list(self.base.glob(".chunksqueeze-*")))

    def test_large_external_output(self):
        extra = test.compound(test.named(7, b"random", test.byte_array(os.urandom(1_100_000))))
        encoded, states, _ = test.fixture(extra=extra)
        self.make_compact([(67, encoded, 1, True)])
        self.assertEqual(restore.restore_world(self.compacted, self.dest), 1)
        self.assertTrue((self.dest / "c.-61.98.mcc").is_file())
        _, chunks = restore.read_region(self.dest / "r.-2.3.mca")
        self.assertEqual(chunks[0][1], test.expected(states, extra=extra))

    def test_missing_peers_conflicts_and_aliases(self):
        path = self.make_compact()
        original = path.read_bytes()
        owner = path.with_name(path.name + ".owner")
        owner_data = owner.read_bytes()
        owner.unlink()
        self.assert_rejected()
        owner.write_bytes(owner_data)
        path.unlink()
        self.assert_rejected()
        path.write_bytes(original)
        collision = self.compacted / "r.-2.3.mca"
        shutil.copy2(self.source / collision.name, collision)
        self.assert_rejected()
        collision.rename(self.compacted / "r.-02.03.mca")
        self.assert_rejected()

    def test_identity_and_payload_corruption(self):
        path = self.make_compact()
        original = path.read_bytes()
        for offset in (0, 40, 44, 67072):
            data = bytearray(original)
            data[offset] ^= 1
            path.write_bytes(data)
            self.assert_rejected()
        path.write_bytes(original)
        owner = path.with_name(path.name + ".owner")
        original_owner = owner.read_bytes()
        owner.write_bytes(original_owner + b"\0")
        self.assert_rejected()
        for offset in (12, 24, 40, 44):
            data = bytearray(original_owner)
            data[offset] ^= 1
            if offset in (12, 24):
                data = region.finish_page(data)
            owner.write_bytes(data)
            self.assert_rejected()

    def test_torn_commit_recovers_and_both_bad_fail(self):
        path = self.make_compact()
        original = path.read_bytes()
        for offset in (512, 1024):
            data = bytearray(original)
            data[offset + 40] ^= 1
            path.write_bytes(data)
            self.assertEqual(len(region.read_region(path)[1]), 1)
            restore.restore_world(self.compacted, self.dest)
            shutil.rmtree(self.dest)
        data[512 + 40] ^= 1
        path.write_bytes(data)
        self.assert_rejected()

    def test_equal_generation_disagreement(self):
        path = self.make_compact()
        data = bytearray(path.read_bytes())
        data[1024:1536] = region.commit(1, 1, zlib.crc32(data[34304:67072]), data[24:40])
        path.write_bytes(data)
        self.assert_rejected()
        data[1024:1536] = region.commit(1, 0, 123, data[24:40])
        path.write_bytes(data)
        self.assert_rejected()

    def test_newest_bad_index_or_payload_never_falls_back(self):
        path = self.make_compact()
        original = path.read_bytes()
        for payload_bad in (False, True):
            data = bytearray(original)
            table = bytearray(data[1536:34304])
            if payload_bad:
                struct.pack_into(">I", table, 20, 123)
            data[34304:67072] = table
            crc = zlib.crc32(table) ^ (0 if payload_bad else 1)
            data[1024:1536] = region.commit(2, 1, crc, data[24:40])
            path.write_bytes(data)
            self.assert_rejected()
        # 更新的有效表胜出；旧表 CRC 无效不应影响它。
        data = bytearray(original)
        data[34304:67072] = data[1536:34304]
        data[1024:1536] = region.commit(2, 1, zlib.crc32(data[34304:67072]), data[24:40])
        data[1536] ^= 1
        path.write_bytes(data)
        self.assertEqual(len(region.read_region(path)[1]), 1)

    def test_index_bounds_overlap_and_generation(self):
        path = self.make_compact([(0, test.root(test.EXTRA), 2, False), (1, test.root(test.EXTRA), 2, False)])
        original = path.read_bytes()
        mutations = [(0, ">q", -512), (0, ">q", 1536), (0, ">q", 67073), (0, ">q", 2 ** 62),
                     (8, ">i", 0), (8, ">i", -1), (8, ">i", 128 * 1024 * 1024 + 1),
                     (16, ">I", 4), (24, ">q", -1), (24, ">q", 2), (32, ">q", 67072)]
        for offset, fmt, value in mutations:
            with self.subTest(offset=offset, value=value):
                data = bytearray(original)
                struct.pack_into(fmt, data, 1536 + offset, value)
                marker = region.commit(1, 0, zlib.crc32(data[1536:34304]), data[24:40])
                data[512:1536] = marker * 2
                path.write_bytes(data)
                self.assert_rejected()

    def test_tail_padding_absent_and_absent_slot_timestamp(self):
        path = self.make_compact()
        data = path.read_bytes()
        offset, length = struct.unpack_from(">qi", data, 1536)
        path.write_bytes(data[:offset + length])
        times, chunks = region.read_region(path)
        self.assertEqual(len(chunks), 1)
        restore.restore_world(self.compacted, self.dest)
        self.assertEqual(restore.read_region(self.dest / "r.-2.3.mca")[0], times)
        shutil.rmtree(self.dest)
        path.write_bytes(data[:offset + length - 1])
        self.assert_rejected()

    def test_source_destination_validation_and_links(self):
        self.make_compact()
        for operation in (compact.compact_world, restore.restore_world):
            for destination in (self.source, self.source / "nested", self.base, self.compacted):
                with self.assertRaises(ValueError):
                    operation(self.source, destination)
        link = self.source / "linked"
        try:
            link.symlink_to(self.compacted, target_is_directory=True)
        except OSError:
            self.skipTest("Creating symlinks requires Windows permission")
        self.assert_rejected(self.source)

    def test_link_and_reparse_checks_without_privileges(self):
        for mode, attributes in ((stat.S_IFLNK, 0), (stat.S_IFDIR, 0x400), (stat.S_IFREG, 0x400)):
            with mock.patch.object(Path, "lstat", return_value=SimpleNamespace(st_mode=mode, st_file_attributes=attributes)):
                with self.assertRaises(ValueError):
                    restore.reject_link(self.source)
                with self.assertRaises(ValueError):
                    region.regular(self.source)

    def test_mca_malformed_payloads_are_transactional(self):
        path = self.source / "r.0.0.mca"
        test.region(path, [(0, test.root(test.EXTRA), 2, False)])
        original = path.read_bytes()
        variants = []
        for offset, fmt, value in ((0, ">I", 0xFFFFFF01), (0, ">I", 0x200),
                                    (8192, ">I", 4096), (8196, ">B", 4), (4, ">I", 0x201)):
            data = bytearray(original)
            struct.pack_into(fmt, data, offset, value)
            variants.append(data)
        variants.append(original[:8196])
        for data in variants:
            path.write_bytes(data)
            self.assert_rejected(self.source)
        test.region(path, [(0, test.root(test.EXTRA), 2, True)])
        (self.source / "c.0.0.mcc").unlink()
        self.assert_rejected(self.source)

    def test_nonstorage_retained_markers_removed_and_mcc_collision(self):
        self.make_compact()
        (self.compacted / restore.MARKER).write_bytes(b"format marker")
        (self.compacted / "unrelated.mcc").write_bytes(b"keep")
        restore.restore_world(self.compacted, self.dest)
        self.assertFalse((self.dest / restore.MARKER).exists())
        self.assertEqual((self.dest / "unrelated.mcc").read_bytes(), b"keep")
        shutil.rmtree(self.dest)
        extra = test.compound(test.named(7, b"blob", test.byte_array(os.urandom(1_100_000))))
        payload = test.fixture(extra=extra)[0]
        region.write_region(self.compacted / "r.0.0.cqs", bytes(4096), [(0, payload, 3, None)])
        (self.compacted / "c.0.0.mcc").write_bytes(b"unrelated")
        with self.assertRaisesRegex(ValueError, "collides"):
            restore.restore_world(self.compacted, self.dest)
        self.assertFalse(self.dest.exists())
        self.assertEqual((self.compacted / "c.0.0.mcc").read_bytes(), b"unrelated")

    def test_single_file_restore_still_works(self):
        test.region(self.source / "r.0.0.mca", [(0, test.fixture()[0], 2, False)])
        standalone = self.base / "tool" / "restore_world.py"
        standalone.parent.mkdir()
        shutil.copy2(Path(restore.__file__), standalone)
        result = subprocess.run([sys.executable, "-I", "-X", "utf8", str(standalone), str(self.source), str(self.dest)],
                                capture_output=True, text=True, encoding="utf-8")
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_invalid_compression_and_nbt_migration_rejected(self):
        path = self.source / "r.0.0.mca"
        for codec, payload in ((2, b"damaged zlib"), (3, b"not NBT"),
                               (2, zlib.compress(b"not NBT")),
                               (3, test.fixture(crc_delta=1)[0])):
            for external in (False, True):
                with self.subTest(codec=codec, external=external, payload=payload[:12]):
                    test.region(path, [(0, test.root(test.EXTRA), 3, external)])
                    if external:
                        data = bytearray(path.read_bytes())
                        data[8196] = codec | 128
                        path.write_bytes(data)
                        (self.source / "c.0.0.mcc").write_bytes(payload)
                    else:
                        data = bytearray(path.read_bytes()[:8192])
                        data.extend(struct.pack(">IB", len(payload) + 1, codec) + payload)
                        path.write_bytes(data)
                    self.assert_rejected(self.source)
            self.compacted.mkdir(exist_ok=True)
            cqs = self.compacted / "r.0.0.cqs"
            # CRC 正确也不能掩盖压缩流或 NBT 损坏。
            region.write_region(cqs, bytes(4096), [(0, payload, codec, None)])
            self.assert_rejected()
            cqs.unlink()
            cqs.with_name(cqs.name + ".owner").unlink()

    def test_noncanonical_region_names_rejected(self):
        names = ("r.00.0", "r.-0.0", "r.0.00", "r.0.-0", "r.-02.3")
        for name in names:
            for suffix in (".mca", ".cqs", ".cqs.owner"):
                with self.subTest(name=name + suffix), self.assertRaisesRegex(ValueError, "Non-canonical"):
                    region.coordinates(Path(name + suffix))
            path = self.source / (name + ".mca")
            test.region(path, [(0, test.root(test.EXTRA), 2, False)])
            with self.assertRaisesRegex(ValueError, "Non-canonical"):
                compact.compact_world(self.source, self.dest)
            self.assertFalse(self.dest.exists())
            path.unlink()
        path = self.make_compact()
        owner = path.with_name(path.name + ".owner")
        path.rename(self.compacted / "r.-02.3.cqs")
        owner.rename(self.compacted / "r.-02.3.cqs.owner")
        self.assert_rejected()

    def test_compact_markers_created_and_old_hint_replaced(self):
        for folder in ("region", "DIM-1/region", "entities"):
            directory = self.source / folder
            directory.mkdir(parents=True)
            test.region(directory / "r.0.0.mca", [(0, test.root(test.EXTRA), 2, False)])
        old_hint = self.source / "region" / restore.MARKER
        old_hint.write_text("Requires ChunkSqueeze 0.1", encoding="utf-8")
        before = test.snapshot(self.source)
        compact.compact_world(self.source, self.compacted)
        for folder in ("region", "DIM-1/region"):
            hint = (self.compacted / folder / restore.MARKER).read_text(encoding="utf-8")
            self.assertIn("0.2+", hint)
            self.assertIn("Before uninstalling", hint)
            self.assertIn("restore_world.py SOURCE DEST", hint)
            self.assertNotIn("0.1", hint)
        self.assertFalse((self.compacted / "entities" / restore.MARKER).exists())
        self.assertEqual(test.snapshot(self.source), before)
        (self.compacted / "region" / restore.MARKER).write_text("Requires ChunkSqueeze 0.1", encoding="utf-8")
        (self.compacted / "DIM-1/region" / restore.MARKER).unlink()
        before_copy = test.snapshot(self.compacted)
        compact.compact_world(self.compacted, self.dest)
        for folder in ("region", "DIM-1/region"):
            self.assertIn("0.2+", (self.dest / folder / restore.MARKER).read_text(encoding="utf-8"))
        self.assertEqual(test.snapshot(self.compacted), before_copy)

    def test_nbt_limit_is_separate_from_payload_limit(self):
        # 容器允许 128 MiB payload，迁移验证和还原仍受 64 MiB NBT 限制。
        oversized = b"x" * (restore.MAX_BYTES + 1)
        path = self.compacted / "r.0.0.cqs"
        self.compacted.mkdir()
        region.write_region(path, bytes(4096), [(0, oversized, 3, None)])
        self.assertEqual(len(region.read_region(path)[1][0][1]), len(oversized))
        with self.assertRaisesRegex(ValueError, "64 MiB"):
            restore.restore_world(self.compacted, self.dest)
        self.assertFalse(self.dest.exists())
        with self.assertRaisesRegex(ValueError, "64 MiB"):
            compact.compact_world(self.compacted, self.dest)
        self.assertFalse(self.dest.exists())


if __name__ == "__main__":
    unittest.main(verbosity=1)
