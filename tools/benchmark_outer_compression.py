"""Compare outer compression without modifying any region files."""
import argparse
import importlib.util
import json
from pathlib import Path
import struct
import sys
import time
import zlib
import lzma


def text(value):
    value = value.encode("ascii")
    return struct.pack(">H", len(value)) + value


def named(kind, name, body):
    return bytes([kind]) + text(name) + body


def record(file, slot):
    file.seek(slot * 4)
    location = int.from_bytes(file.read(4), "big")
    if not location:
        return None
    file.seek((location >> 8) * 4096)
    length = int.from_bytes(file.read(4), "big")
    codec = file.read(1)
    if codec != b"\x02" or not 1 <= length <= (location & 255) * 4096 - 4:
        raise ValueError("Expected a valid inline zlib sample")
    packed = file.read(length - 1)
    decoder = zlib.decompressobj()
    raw = decoder.decompress(packed, 64 * 1024 * 1024 + 1)
    if len(raw) > 64 * 1024 * 1024 or not decoder.eof or decoder.unused_data:
        raise ValueError("Invalid or oversized chunk")
    return raw, len(packed)


def sectors(length):
    return (length + 5 + 4095) // 4096 * 4096


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--current", type=Path, required=True)
    parser.add_argument("--original", type=Path, required=True)
    parser.add_argument("--dependencies", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--full", action="store_true")
    parser.add_argument("--variants", default="current,standard")
    parser.add_argument("--codecs", default="zlib-6,zlib-9,zstd-3,zstd-6,zstd-9,zstd-15,zstd-19,xz-1,xz-6")
    args = parser.parse_args()
    sys.path.insert(0, str(args.dependencies.resolve()))
    import zstandard
    spec = importlib.util.spec_from_file_location("restore_reader", Path(__file__).with_name("restore_world.py"))
    reader = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(reader)
    codec_names = args.codecs.split(",")
    compressors = {name: zstandard.ZstdCompressor(level=int(name.split("-")[1]), write_checksum=True)
                   for name in codec_names if name.startswith("zstd-")}
    decompressor = zstandard.ZstdDecompressor()
    totals = {}
    count = 0
    input_compressed = input_allocated = 0
    files = sorted(args.current.glob("*.mca"))
    if not files:
        raise ValueError("No sample region files")
    for region in files:
        original = args.original / region.stem / region.name
        with region.open("rb") as current_file, original.open("rb") as original_file:
            for slot in range(1024):
                if not args.full and (slot % 32 % 8 != 4 or slot // 32 % 8 != 4):
                    continue
                current = record(current_file, slot)
                standard = record(original_file, slot)
                if current is None or standard is None:
                    raise ValueError("Expected matching full sample regions")
                _, parsed = reader.read_root(reader.Reader(standard[0]))
                status = parsed.value[b"Status"].value
                version = parsed.value[b"DataVersion"].value
                count += 1
                input_compressed += current[1]
                input_allocated += sectors(current[1])
                for variant, raw in (("current", current[0]), ("standard", standard[0])):
                    if variant not in args.variants.split(","):
                        continue
                    for codec in codec_names:
                        start = time.perf_counter_ns()
                        if codec.startswith("zlib-"):
                            output = zlib.compress(raw, int(codec.split("-")[1]))
                        else:
                            packed = compressors[codec].compress(raw) if codec.startswith("zstd-") else lzma.compress(raw, preset=int(codec.split("-")[1]))
                            # 原型仍放在普通 zlib MCA 中，计入 NBT 封装及二次压缩开销。
                            prefix = (b"\x0a\0\0" + named(8, "Status", status) + named(3, "DataVersion", version)
                                      + b"\x0a" + text("chunksqueeze:storage") + named(3, "version", struct.pack(">i", 2))
                                      + named(8, "codec", text(codec.split("-")[0]))
                                      + b"\x07" + text("payload") + struct.pack(">i", len(packed)))
                            output = zlib.compress(prefix + packed + b"\0\0", 6)
                        encode_ns = time.perf_counter_ns() - start
                        start = time.perf_counter_ns()
                        restored = zlib.decompress(output)
                        if not codec.startswith("zlib-"):
                            packed_again = restored[len(prefix):-2]
                            restored = decompressor.decompress(packed_again, max_output_size=64 * 1024 * 1024) if codec.startswith("zstd-") else lzma.decompress(packed_again)
                        decode_ns = time.perf_counter_ns() - start
                        if restored != raw:
                            raise AssertionError(f"Round trip mismatch: {region.name}, {slot}, {variant}, {codec}")
                        row = totals.setdefault(variant + "/" + codec,
                                                dict(compressedBytes=0, allocatedBytes=0, encodeNanos=0, decodeNanos=0,
                                                     selectedAllocatedBytes=0, selectedCompressedBytes=0, selectedChunks=0))
                        beneficial = current[1] - len(output) >= 128 and sectors(len(output)) < sectors(current[1])
                        row["selectedAllocatedBytes"] += sectors(len(output)) if beneficial else sectors(current[1])
                        row["selectedCompressedBytes"] += len(output) if beneficial else current[1]
                        row["selectedChunks"] += beneficial
                        row["compressedBytes"] += len(output)
                        row["allocatedBytes"] += sectors(len(output))
                        row["encodeNanos"] += encode_ns
                        row["decodeNanos"] += decode_ns
        print("Compared " + region.name, flush=True)
    for row in totals.values():
        row["extraAllocatedSavingPercent"] = 100 * (input_allocated - row["allocatedBytes"]) / input_allocated
        row["selectedSavingPercent"] = 100 * (input_allocated - row["selectedAllocatedBytes"]) / input_allocated
        row["meanEncodeMillis"] = row.pop("encodeNanos") / count / 1e6
        row["meanDecodeMillis"] = row.pop("decodeNanos") / count / 1e6
        if args.full:
            row["totalRegionBytes"] = row["allocatedBytes"] + len(files) * 8192
            row["selectedTotalRegionBytes"] = row["selectedAllocatedBytes"] + len(files) * 8192
    result = dict(chunks=count, full=args.full, zstandardVersion=zstandard.__version__,
                  currentCompressedBytes=input_compressed, currentAllocatedBytes=input_allocated,
                  results=totals)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
