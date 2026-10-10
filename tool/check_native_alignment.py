#!/usr/bin/env python3
"""检查本工程 ARM64 APK 的实际 ELF 与未压缩 Native ZIP 对齐。"""

import argparse
import hashlib
import json
from pathlib import Path
import struct
import sys
import zipfile


PAGE_SIZE_BYTES = 16384


def inspect_elf(data):
    if data[:6] != b"\x7fELF\x02\x01" or len(data) < 64:
        raise ValueError("必须是 little-endian ELF64")
    if struct.unpack_from("<H", data, 18)[0] != 183:
        raise ValueError("必须是 AArch64 ELF")
    header_offset = struct.unpack_from("<Q", data, 32)[0]
    header_size, header_count = struct.unpack_from("<HH", data, 54)
    if header_size < 56 or header_offset + header_size * header_count > len(data):
        raise ValueError("ELF program header 越界")
    loads = []
    relro_segments = []
    errors = []
    for index in range(header_count):
        kind, flags, offset, address, _, _, memory_size, alignment = struct.unpack_from(
            "<IIQQQQQQ", data, header_offset + index * header_size
        )
        if kind == 1:  # PT_LOAD
            loads.append({"offset": offset, "virtual_address": address,
                          "memory_size": memory_size, "flags": flags, "alignment": alignment})
            if alignment < PAGE_SIZE_BYTES or alignment & (alignment - 1):
                errors.append(f"LOAD[{index}] 对齐 {alignment} 不满足 16 KiB")
            if (address - offset) % PAGE_SIZE_BYTES:
                errors.append(f"LOAD[{index}] offset / address 不满足 16 KiB 同余")
        elif kind == 0x6474E552:  # PT_GNU_RELRO
            end = address + memory_size
            relro_segments.append({"start": address, "end": end,
                                   "protected_start": address // PAGE_SIZE_BYTES * PAGE_SIZE_BYTES,
                                   "protected_end": (end + PAGE_SIZE_BYTES - 1)
                                   // PAGE_SIZE_BYTES * PAGE_SIZE_BYTES})
    # Android linker 按页扩展 mprotect；允许 lld 留隔离间隙，不能只判断 RELRO 末端余数。
    # bionic/linker/linker_phdr.cpp: _phdr_table_set_gnu_relro_prot
    for relro in relro_segments:
        for index, load in enumerate(loads):
            if not load["flags"] & 2:  # PF_W
                continue
            start = load["virtual_address"]
            end = start + load["memory_size"]
            outside_relro = ((start, min(end, relro["start"])),
                             (max(start, relro["end"]), end))
            if any(begin < limit and max(begin, relro["protected_start"])
                   < min(limit, relro["protected_end"]) for begin, limit in outside_relro):
                errors.append(f"GNU_RELRO 的 16 KiB 保护范围覆盖 LOAD[{index}] 的其他可写内存")
    if not loads:
        errors.append("没有 PT_LOAD")
    return {"load_segments": loads, "relro_segments": relro_segments, "errors": errors}


def inspect_apk(apk_path):
    libraries = []
    errors = []
    with zipfile.ZipFile(apk_path) as archive, apk_path.open("rb") as raw:
        native = [entry for entry in archive.infolist()
                  if entry.filename.startswith("lib/") and entry.filename.endswith(".so")]
        if not native:
            errors.append("APK 没有 Native 库，不能提供本工程的适配证据")
        names = set()
        for entry in native:
            data = archive.read(entry)
            item = {"name": entry.filename, "size_bytes": len(data),
                    "sha256": hashlib.sha256(data).hexdigest(), "errors": []}
            if entry.filename in names:
                item["errors"].append("重复 Native ZIP 条目")
            names.add(entry.filename)
            if not entry.filename.startswith("lib/arm64-v8a/"):
                item["errors"].append("本工程只允许 arm64-v8a")
            try:
                elf = inspect_elf(data)
                elf["errors"] = item["errors"] + elf["errors"]
                item.update(elf)
            except (ValueError, struct.error) as error:
                item["errors"].append(str(error))
            raw.seek(entry.header_offset)
            local_header = raw.read(30)
            if len(local_header) != 30 or local_header[:4] != b"PK\x03\x04":
                item["errors"].append("无效 ZIP local header")
            else:
                name_size, extra_size = struct.unpack_from("<HH", local_header, 26)
                data_offset = entry.header_offset + 30 + name_size + extra_size
                item["zip_data_offset"] = data_offset
                if entry.compress_type != zipfile.ZIP_STORED:
                    item["errors"].append("本工程 Native 库必须直接从未压缩 APK 加载")
                elif data_offset % PAGE_SIZE_BYTES:
                    item["errors"].append(f"ZIP 数据偏移 {data_offset} 未按 16 KiB 对齐")
            errors.extend(f"{entry.filename}: {reason}" for reason in item["errors"])
            libraries.append(item)
    with apk_path.open("rb") as raw:
        digest = hashlib.file_digest(raw, "sha256").hexdigest()
    return {"apk": str(apk_path.resolve()), "apk_sha256": digest, "page_size_bytes": PAGE_SIZE_BYTES,
        "native": libraries, "errors": errors, "passed": not errors}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--report", type=Path, help="保存完整 JSON 证据")
    arguments = parser.parse_args()
    try:
        report = inspect_apk(arguments.apk)
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        print(f"native alignment\n  status  failed\n  reason  {error}", file=sys.stderr)
        return 1
    if arguments.report:
        arguments.report.parent.mkdir(parents=True, exist_ok=True)
        arguments.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print("native alignment")
    print(f"  apk      {arguments.apk}")
    print(f"  page     {PAGE_SIZE_BYTES} bytes")
    print(f"  native   {len(report['native'])} 个")
    print(f"  status   {'passed' if report['passed'] else 'failed'}")
    if arguments.report:
        print(f"  report   {arguments.report}")
    if report["errors"]:
        print("  reason   " + "\n           ".join(report["errors"]), file=sys.stderr)
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
