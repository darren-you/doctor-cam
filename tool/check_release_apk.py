#!/usr/bin/env python3
"""验证最终 Release APK 的真实签名、证书和应用发布标志。"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET


def inspect_apk(apk, apksigner, apkanalyzer):
    expected_digest = os.environ.get("UVC_VISION_ANDROID_SIGNING_CERT_SHA256", "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", expected_digest):
        raise ValueError("缺少有效的 UVC_VISION_ANDROID_SIGNING_CERT_SHA256")
    signature = subprocess.run(
        [str(apksigner), "verify", "--verbose", "--print-certs", str(apk)],
        capture_output=True, text=True, check=False,
    )
    if signature.returncode != 0:
        raise ValueError("apksigner 拒绝最终 APK 的签名")
    signer_counts = re.findall(r"^Number of signers:\s*(\d+)\s*$", signature.stdout, re.MULTILINE)
    certificates = set(re.findall(
        r"^(?:V\d(?:\.\d)? )?Signer(?: #\d+)?:? certificate SHA-256 digest:\s*([0-9a-fA-F]{64})\s*$",
        signature.stdout, re.MULTILINE,
    ))
    certificates = {digest.lower() for digest in certificates}
    errors = []
    if signer_counts != ["1"] or certificates != {expected_digest}:
        errors.append("最终 APK 必须只有一个签名者，证书必须与固定 Profile 一致")
    signer_names = re.findall(
        r"^(?:V\d(?:\.\d)? )?Signer(?: #\d+)?:? certificate DN:\s*(.+)$",
        signature.stdout, re.MULTILINE,
    )
    if any(re.search(r"(?:^|,)\s*CN=Android Debug(?:,|$)", name) for name in signer_names):
        errors.append("正式 APK 不能使用 Android Debug 签名证书")
    manifest_result = subprocess.run(
        [str(apkanalyzer), "manifest", "print", str(apk)],
        capture_output=True, text=True, check=False,
    )
    if manifest_result.returncode != 0:
        raise ValueError("apkanalyzer 无法读取最终 APK Manifest")
    application = ET.fromstring(manifest_result.stdout).find("application")
    if application is None:
        raise ValueError("最终 APK 没有 application Manifest")
    android = "{http://schemas.android.com/apk/res/android}"
    debuggable = application.get(android + "debuggable", "false")
    test_only = application.get(android + "testOnly", "false")
    if debuggable != "false":
        errors.append("正式 APK 的 debuggable 必须为 false")
    if test_only != "false":
        errors.append("正式 APK 的 testOnly 必须为 false")
    with apk.open("rb") as raw:
        digest = hashlib.file_digest(raw, "sha256").hexdigest()
    return {"apk": str(apk.resolve()), "apk_sha256": digest,
            "certificate_sha256": sorted(certificates), "debuggable": debuggable,
            "test_only": test_only, "errors": errors, "passed": not errors}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--apksigner", required=True, type=Path)
    parser.add_argument("--apkanalyzer", required=True, type=Path)
    parser.add_argument("--report", required=True, type=Path)
    arguments = parser.parse_args()
    try:
        report = inspect_apk(arguments.apk, arguments.apksigner, arguments.apkanalyzer)
        arguments.report.parent.mkdir(parents=True, exist_ok=True)
        arguments.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    except (OSError, ValueError, ET.ParseError) as error:
        print(f"release APK\n  status  failed\n  reason  {error}", file=sys.stderr)
        return 1
    print("release APK")
    print(f"  status       {'passed' if report['passed'] else 'failed'}")
    print(f"  apk          {arguments.apk}")
    print(f"  certificate  {', '.join(report['certificate_sha256'])}")
    print(f"  report       {arguments.report}")
    if report["errors"]:
        print("  reason       " + "\n               ".join(report["errors"]), file=sys.stderr)
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
