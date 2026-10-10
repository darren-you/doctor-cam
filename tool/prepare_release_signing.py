#!/usr/bin/env python3
"""从本次发布的固定 Profile 准备并校验 DoctorCam PKCS12 签名文件。"""

import argparse
import base64
import binascii
import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile


def prepare_signing(keytool):
    fields = (
        "DOCTOR_CAM_ANDROID_SIGNING_KEYSTORE_BASE64",
        "DOCTOR_CAM_ANDROID_SIGNING_PASSWORD",
        "DOCTOR_CAM_ANDROID_SIGNING_KEY_ALIAS",
        "DOCTOR_CAM_ANDROID_SIGNING_CERT_SHA256",
    )
    values = {name: os.environ.get(name, "") for name in fields}
    for name, value in values.items():
        if not value:
            raise ValueError(f"缺少签名字段 {name}")
    if values[fields[2]] != "doctor-cam":
        raise ValueError("签名 key alias 必须为 doctor-cam")
    expected_digest = values[fields[3]].lower()
    if not re.fullmatch(r"[0-9a-f]{64}", expected_digest):
        raise ValueError("签名证书 SHA-256 必须为 64 位十六进制")
    try:
        keystore = base64.b64decode(values[fields[0]], validate=True)
    except (binascii.Error, ValueError):
        raise ValueError("签名 PKCS12 的 Base64 无效") from None
    if not keystore:
        raise ValueError("签名 PKCS12 不能为空")

    project_root = Path(__file__).resolve().parent.parent
    directory = project_root / "app/build/signing"
    output = directory / "doctor-cam-release.p12"
    for path in (project_root / "app", project_root / "app/build", directory, output):
        if path.is_symlink():
            raise ValueError("签名输出路径不能包含符号链接")
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    directory.chmod(0o700)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=directory, prefix=".signing-", delete=False) as raw:
            temporary = Path(raw.name)
            os.fchmod(raw.fileno(), 0o600)
            raw.write(keystore)
        result = subprocess.run(
            [str(keytool), "-exportcert", "-storetype", "PKCS12", "-keystore", str(temporary),
             "-alias", "doctor-cam", "-storepass:env", fields[1]],
            capture_output=True, check=False,
        )
        if result.returncode != 0:
            raise ValueError("无法用 Profile 密码和 alias 读取 PKCS12 证书")
        actual_digest = hashlib.sha256(result.stdout).hexdigest()
        if actual_digest != expected_digest:
            raise ValueError("PKCS12 证书与 Profile 的 SHA-256 不一致")
        os.replace(temporary, output)
        temporary = None
        output.chmod(0o600)
        print("release signing")
        print("  status       passed")
        print(f"  keystore     {output}")
        print(f"  certificate  {actual_digest}")
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--keytool", required=True, type=Path)
    arguments = parser.parse_args()
    try:
        prepare_signing(arguments.keytool)
    except (OSError, ValueError) as error:
        print(f"release signing\n  status  failed\n  reason  {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
