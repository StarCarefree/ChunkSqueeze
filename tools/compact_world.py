"""Copy an offline world to compact regions without changing its NBT payloads."""

import argparse
import importlib.util
from pathlib import Path
import sys

# 隔离模式只从工具所在目录导入，不搜索世界目录或当前目录。
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("restore_world", Path(__file__).with_name("restore_world.py"))
restore = importlib.util.module_from_spec(spec)
spec.loader.exec_module(restore)


def compact_world(source, destination):
    return restore.restore_world(source, destination, compact=True)


def main():
    parser = argparse.ArgumentParser(description="Copy a stopped world into a new compact world directory.",
                                     epilog="Payload limit: 128 MiB. Migration validation and restoration have a 64 MiB NBT limit; compressed payloads are preserved.")
    parser.add_argument("source", metavar="SOURCE")
    parser.add_argument("destination", metavar="DEST")
    args = parser.parse_args()
    try:
        total = compact_world(args.source, args.destination)
    except (OSError, ValueError, restore.zlib.error, restore.struct.error, RecursionError) as exc:
        print("Compaction failed: " + str(exc), file=sys.stderr)
        return 1
    print(f"Compacted {total} chunks to {args.destination}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
