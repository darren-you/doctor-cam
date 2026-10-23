#!/usr/bin/env python3
"""准备固定官方扩展算子制品中实际消费的 Paddle Lite 头文件与 ARM64 静态库。"""

import argparse
import hashlib
import shutil
import sys
import tarfile
import tempfile
import urllib.request
from pathlib import Path


PADDLE_LITE_VERSION = "2.14-rc"
ARCHIVE_URL = (
    "https://github.com/PaddlePaddle/Paddle-Lite/releases/download/"
    "v2.14-rc/inference_lite_lib.android.armv8.clang.c%2B%2B_shared.with_extra.tar.gz"
)
ARCHIVE_SHA256 = "cb6a03060fa10676f7f599aed41efdcc48bbcee9779c24499afcde111313be03"
ARCHIVE_ROOT = "inference_lite_lib.android.armv8.clang.c++_shared.with_extra"
BUNDLED_LIBRARY_SHA256 = "3942ced06f7064d85fcb0c81ab93aee2422a06b0de5ed53c150488a51d697751"
MEMBERS = {
    "cxx/include/paddle_api.h": "include/paddle_api.h",
    "cxx/include/paddle_place.h": "include/paddle_place.h",
    "cxx/lib/libpaddle_api_light_bundled.a": "lib/arm64-v8a/libpaddle_api_light_bundled.a",
}


def prepare(output_directory: Path) -> None:
    with tempfile.TemporaryDirectory(prefix="uvc-vision-paddle-lite-") as temporary:
        archive_path = Path(temporary) / "paddle-lite.tar.gz"
        request = urllib.request.Request(ARCHIVE_URL, headers={"User-Agent": "UvcVision-build"})
        digest = hashlib.sha256()
        with urllib.request.urlopen(request, timeout=60) as response, archive_path.open("wb") as archive:
            while chunk := response.read(1024 * 1024):
                archive.write(chunk)
                digest.update(chunk)
        if digest.hexdigest() != ARCHIVE_SHA256:
            raise ValueError("官方 Paddle Lite 压缩包 SHA-256 不匹配")

        # 只读取精确登记的普通文件，不展开 SDK 的其余 ABI、Java 或工具文件。
        with tarfile.open(archive_path, "r:gz") as archive:
            for source, destination in MEMBERS.items():
                member = archive.getmember(f"{ARCHIVE_ROOT}/{source}")
                if not member.isfile():
                    raise ValueError(f"SDK 成员不是普通文件：{source}")
                stream = archive.extractfile(member)
                if stream is None:
                    raise ValueError(f"无法读取 SDK 成员：{source}")
                staged_path = Path(temporary) / destination
                staged_path.parent.mkdir(parents=True, exist_ok=True)
                with stream, staged_path.open("wb") as staged:
                    shutil.copyfileobj(stream, staged)
        bundled_library = Path(temporary) / MEMBERS["cxx/lib/libpaddle_api_light_bundled.a"]
        if hashlib.sha256(bundled_library.read_bytes()).hexdigest() != BUNDLED_LIBRARY_SHA256:
            raise ValueError("官方 Paddle Lite ARM64 静态库 SHA-256 不匹配")
        for destination in MEMBERS.values():
            target = output_directory / destination
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(Path(temporary) / destination, target)
        print("Paddle Lite SDK")
        print(f"  版本  {PADDLE_LITE_VERSION}")
        print("  算子  with_extra（原模型需要 hard_swish）")
        print(f"  校验  SHA-256 {ARCHIVE_SHA256}")
        print("  内容  2 个 C++ 头文件、arm64-v8a 静态库")
        print(f"  静态库  {BUNDLED_LIBRARY_SHA256}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", required=True, type=Path)
    arguments = parser.parse_args()
    try:
        prepare(arguments.output_dir)
    except (OSError, ValueError, KeyError, tarfile.TarError) as error:
        print(f"Paddle Lite SDK 准备失败\n  原因  {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
